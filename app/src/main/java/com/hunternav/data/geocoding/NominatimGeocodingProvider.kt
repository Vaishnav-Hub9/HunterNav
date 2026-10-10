package com.hunternav.data.geocoding

import com.hunternav.core.result.AppErrorKind
import com.hunternav.core.result.AppResult
import com.hunternav.core.result.toAppErrorKind
import com.hunternav.core.util.DebugLog
import com.hunternav.data.network.HttpTransport
import com.hunternav.data.network.NetworkClient
import com.hunternav.data.network.RequestRateLimiter
import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.Destination
import com.hunternav.domain.repository.GeocodingProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.atomic.AtomicInteger

/**
 * Nominatim-based geocoder (low-volume OSM service, development use).
 *
 * Usage-policy compliance (spec §5):
 *  - one app-wide rate limiter: at most 1 request/second across search AND reverse,
 *  - successful searches are cached briefly so repeated queries never hit the network,
 *  - identified with a User-Agent, requests only what the caller explicitly submits,
 *  - the base URL is configurable via BuildConfig for self-hosting later.
 *
 * Callers must NOT pass live GPS coordinates ([near]) for ordinary text search — the
 * viewbox hint is only for the rare case where proximity is genuinely required, and the
 * production search path does not use it.
 */
class NominatimGeocodingProvider(
    private val baseUrl: String,
    private val networkClient: HttpTransport = NetworkClient(),
    private val rateLimiter: RequestRateLimiter = RequestRateLimiter(MIN_REQUEST_INTERVAL_MS),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val cacheTtlMs: Long = CACHE_TTL_MS,
    private val cacheMaxEntries: Int = CACHE_MAX_ENTRIES,
) : GeocodingProvider {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val requestSeq = AtomicInteger(0)

    /** Query → (fetched-at, results). Synchronized: reads/writes are O(1) and never block IO. */
    private val searchCache = LinkedHashMap<String, CachedSearch>(cacheMaxEntries, 0.75f, true)

    private data class CachedSearch(val fetchedAtMs: Long, val results: List<Destination>)

    override suspend fun search(query: String, near: Coordinate?, limit: Int): AppResult<List<Destination>> {
        if (query.isBlank()) return AppResult.Success(emptyList())
        val normalized = query.trim().lowercase()

        // Cache hit: repeated queries are answered locally (policy: cache repeated queries).
        synchronized(searchCache) {
            val cached = searchCache[normalized]
            if (cached != null && nowMs() - cached.fetchedAtMs <= cacheTtlMs) {
                DebugLog.d("GEOCODING_RESULT", "source=search cache=hit query=\"$normalized\" count=${cached.results.size}")
                return AppResult.Success(cached.results)
            }
        }

        val requestId = requestSeq.incrementAndGet()
        val startedAt = nowMs()
        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", query)
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("addressdetails", "1")
            .addQueryParameter("limit", limit.coerceIn(1, 20).toString())
            .apply {
                // Optional proximity hint ONLY when the caller explicitly supplies one.
                if (near != null) {
                    val dLat = 0.7
                    val dLon = 0.7
                    addQueryParameter("viewbox", "${near.longitude - dLon},${near.latitude + dLat},${near.longitude + dLon},${near.latitude - dLat}")
                }
            }
            .build()

        DebugLog.d(
            "GEOCODING_REQUEST",
            "req=$requestId source=search query=\"$query\" near=${if (near != null) "supplied" else "none"} limit=$limit",
        )

        return try {
            rateLimiter.withPermit {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", NetworkClient.USER_AGENT)
                    .get()
                    .build()
                networkClient.execute(request).use { response ->
                    if (!response.isSuccessful) {
                        DebugLog.d("GEOCODING_RESULT", "req=$requestId source=search failed http=${response.code} elapsed_ms=${nowMs() - startedAt}")
                        AppResult.Failure(AppErrorKind.SERVER, "HTTP ${response.code}")
                    } else {
                        val body = response.body.string()
                        if (body.isBlank()) {
                            DebugLog.d("GEOCODING_RESULT", "req=$requestId source=search failed reason=empty_body")
                            AppResult.Failure(AppErrorKind.PARSE, "Empty response body")
                        } else {
                            parseSearch(body).also { r ->
                                if (r is AppResult.Success) {
                                    // Cache only real results; failures/no-results are not sticky.
                                    synchronized(searchCache) {
                                        if (searchCache.size >= cacheMaxEntries) {
                                            val eldest = searchCache.entries.iterator()
                                            if (eldest.hasNext()) eldest.next()
                                        }
                                        searchCache[normalized] = CachedSearch(nowMs(), r.value)
                                    }
                                }
                                DebugLog.d(
                                    "GEOCODING_RESULT",
                                    if (r is AppResult.Success) {
                                        "req=$requestId source=search ok count=${r.value.size} elapsed_ms=${nowMs() - startedAt} " +
                                            "titles=${r.value.take(3).joinToString(" | ") { it.title }}"
                                    } else {
                                        val f = r as AppResult.Failure
                                        "req=$requestId source=search failed kind=${f.kind} message=${f.message} elapsed_ms=${nowMs() - startedAt}"
                                    },
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.d(
                "GEOCODING_RESULT",
                "req=$requestId source=search exception=${e::class.simpleName} message=${e.message} elapsed_ms=${nowMs() - startedAt}",
            )
            AppResult.Failure(e.toAppErrorKind(), e.message, e)
        }
    }

    override suspend fun reverse(coordinate: Coordinate): AppResult<Destination> {
        val requestId = requestSeq.incrementAndGet()
        val startedAt = nowMs()
        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegment("reverse")
            .addQueryParameter("lat", coordinate.latitude.toString())
            .addQueryParameter("lon", coordinate.longitude.toString())
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("addressdetails", "1")
            .build()

        DebugLog.d(
            "GEOCODING_REQUEST",
            "req=$requestId source=reverse lat=${coordinate.latitude} lon=${coordinate.longitude}",
        )

        return try {
            rateLimiter.withPermit {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", NetworkClient.USER_AGENT)
                    .get()
                    .build()
                networkClient.execute(request).use { response ->
                    if (!response.isSuccessful) {
                        DebugLog.d("GEOCODING_RESULT", "req=$requestId source=reverse failed http=${response.code} elapsed_ms=${nowMs() - startedAt}")
                        AppResult.Failure(AppErrorKind.SERVER, "HTTP ${response.code}")
                    } else {
                        val body = response.body.string()
                        if (body.isBlank()) {
                            DebugLog.d("GEOCODING_RESULT", "req=$requestId source=reverse failed reason=empty_body")
                            AppResult.Failure(AppErrorKind.PARSE, "Empty response body")
                        } else {
                            parseReverse(body, coordinate).also { r ->
                                DebugLog.d(
                                    "GEOCODING_RESULT",
                                    if (r is AppResult.Success) {
                                        "req=$requestId source=reverse ok title=${r.value.title} elapsed_ms=${nowMs() - startedAt}"
                                    } else {
                                        val f = r as AppResult.Failure
                                        "req=$requestId source=reverse failed kind=${f.kind} message=${f.message} elapsed_ms=${nowMs() - startedAt}"
                                    },
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.d(
                "GEOCODING_RESULT",
                "req=$requestId source=reverse exception=${e::class.simpleName} message=${e.message} elapsed_ms=${nowMs() - startedAt}",
            )
            AppResult.Failure(e.toAppErrorKind(), e.message, e)
        }
    }

    /** Parses a Nominatim `jsonv2` search array. Internal so unit tests can exercise it. */
    internal fun parseSearch(body: String): AppResult<List<Destination>> {
        return try {
            val arr = json.parseToJsonElement(body).jsonArray
            // Coordinates are mandatory: results without lat/lon are dropped, never (0,0).
            AppResult.Success(arr.mapNotNull { el -> destinationFrom(el.jsonObject, fallbackCoordinate = null) })
        } catch (e: Exception) {
            AppResult.Failure(AppErrorKind.PARSE, "Malformed geocoder response", e)
        }
    }

    /** Parses a Nominatim reverse response; falls back to the pressed coordinate (spec §4). */
    internal fun parseReverse(body: String, coordinate: Coordinate): AppResult<Destination> {
        return try {
            val obj = json.parseToJsonElement(body).jsonObject
            if (obj.containsKey("error")) {
                AppResult.Success(Destination(coordinate, title = "Dropped pin"))
            } else {
                AppResult.Success(
                    destinationFrom(obj, fallbackCoordinate = coordinate)
                        ?: Destination(coordinate, title = "Dropped pin"),
                )
            }
        } catch (e: Exception) {
            AppResult.Failure(AppErrorKind.PARSE, "Malformed geocoder response", e)
        }
    }

    /**
     * Maps one Nominatim object to a [Destination]. Returns null when the response carries no
     * usable coordinates and no [fallbackCoordinate] is available — a destination without
     * latitude/longitude can never be routed to, so it must not become a fake (0,0) entry.
     */
    private fun destinationFrom(obj: JsonObject, fallbackCoordinate: Coordinate?): Destination? {
        val name = obj["name"]?.jsonPrimitive?.takeIf { it.isString && it.content.isNotBlank() }
        val displayName = obj["display_name"]?.jsonPrimitive?.content ?: ""
        val address = obj["address"] as? JsonObject
        val subtitle = address?.let {
            val road = it["road"]?.let { v -> runCatching { v.jsonPrimitive.content }.getOrNull() }
            val city = it["city"]?.let { v -> runCatching { v.jsonPrimitive.content }.getOrNull() }
                ?: it["town"]?.let { v -> runCatching { v.jsonPrimitive.content }.getOrNull() }
                ?: it["village"]?.let { v -> runCatching { v.jsonPrimitive.content }.getOrNull() }
            listOfNotNull(road, city).joinToString(", ").ifBlank { null }
        }

        val lat = obj["lat"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val lon = obj["lon"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val coordinate = when {
            lat != null && lon != null -> Coordinate(lat, lon)
            fallbackCoordinate != null -> fallbackCoordinate
            else -> return null
        }

        return Destination(
            coordinate = coordinate,
            title = name?.content ?: displayName.substringBefore(',').ifBlank { "Dropped pin" },
            subtitle = subtitle ?: displayName.substringAfter(',').trim().ifBlank { null },
        )
    }

    private companion object {
        /** Public Nominatim policy: at most one request per second, app-wide. */
        const val MIN_REQUEST_INTERVAL_MS = 1_000L
        /** Successful search results are reused for a few minutes. */
        const val CACHE_TTL_MS = 10 * 60 * 1_000L
        const val CACHE_MAX_ENTRIES = 64
    }
}
