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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.atomic.AtomicInteger

/**
 * Google Geocoding API provider for destination search and reverse geocoding
 * (docs/google-maps.md). Selected in [com.hunternav.di.AppContainer] when
 * `google.maps.apiKey` is configured.
 *
 * Endpoints (both authenticate with the `key` query parameter):
 *  - search:   `maps.googleapis.com/maps/api/geocode/json?address=…&region=IN`
 *  - reverse:  `maps.googleapis.com/maps/api/geocode/json?latlng=…`
 *
 * Contract guarantees (same as the previous provider):
 *  - results WITHOUT usable coordinates are dropped, never routed to as a fake (0,0);
 *  - reverse geocoding is LABEL-ONLY: the pressed coordinate stays authoritative — Google's
 *    response describes the nearest mapped entity which can sit metres away from the pin;
 *  - no reverse lookup hit ⇒ the destination becomes a "Dropped pin" at the pressed
 *    coordinate rather than a failure — the pin is already valid without a name;
 *  - ≤1 request/second app-wide across search AND reverse ([RequestRateLimiter]);
 *  - successful searches cached ~10 minutes so repeated queries never hit the network;
 *  - the caller's GPS position is never sent with ordinary text search — an explicit
 *    proximity hint ([search]'s `near`) is the only way a location bias is transmitted;
 *  - 401/403 surface with messages naming the credential problem; nothing retries forever.
 */
class GoogleGeocodingProvider(
    private val baseUrl: String,
    private val apiKey: String,
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
        if (apiKey.isBlank()) {
            return AppResult.Failure(
                AppErrorKind.SERVER,
                "Google geocoding is not configured (set google.maps.apiKey, see docs/google-maps.md)",
            )
        }
        if (query.isBlank()) return AppResult.Success(emptyList())
        val normalized = query.trim().lowercase()

        // Cache hit: repeated queries are answered locally.
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
            .addPathSegments("maps/api/geocode/json")
            .addQueryParameter("address", query)
            .addQueryParameter("language", "en")
            .addQueryParameter("region", "IN")
            // Optional proximity bias ONLY when the caller explicitly supplies one.
            .apply {
                if (near != null) {
                    addQueryParameter("location", "${near.latitude},${near.longitude}")
                    addQueryParameter("radius", "50000")
                }
            }
            .addQueryParameter("key", apiKey)
            .build()

        DebugLog.d(
            "GEOCODING_REQUEST",
            "req=$requestId provider=google source=search query=\"$query\" " +
                "near=${if (near != null) "supplied" else "none"} limit=$limit",
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
                        AppResult.Failure(AppErrorKind.SERVER, httpErrorMessage(response.code, "search"))
                    } else {
                        val body = response.body.string()
                        if (body.isBlank()) {
                            DebugLog.d("GEOCODING_RESULT", "req=$requestId source=search failed reason=empty_body")
                            AppResult.Failure(AppErrorKind.PARSE, "Empty response body")
                        } else {
                            parseResults(body).also { r ->
                                if (r is AppResult.Success) {
                                    // Cache only real results; failures/no-results are not sticky.
                                    synchronized(searchCache) {
                                        if (searchCache.size >= cacheMaxEntries) {
                                            val eldest = searchCache.entries.iterator()
                                            if (eldest.hasNext()) eldest.next()
                                        }
                                        searchCache[normalized] = CachedSearch(nowMs(), r.value.take(limit.coerceIn(1, 20)))
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
        if (apiKey.isBlank()) {
            return AppResult.Failure(
                AppErrorKind.SERVER,
                "Google geocoding is not configured (set google.maps.apiKey, see docs/google-maps.md)",
            )
        }
        val requestId = requestSeq.incrementAndGet()
        val startedAt = nowMs()
        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("maps/api/geocode/json")
            .addQueryParameter("latlng", "${coordinate.latitude},${coordinate.longitude}")
            .addQueryParameter("language", "en")
            .addQueryParameter("result_type", "street_address|route|premise|point_of_interest|locality")
            .addQueryParameter("key", apiKey)
            .build()

        DebugLog.d(
            "GEOCODING_REQUEST",
            "req=$requestId provider=google source=reverse lat=${coordinate.latitude} lon=${coordinate.longitude}",
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
                        AppResult.Failure(AppErrorKind.SERVER, httpErrorMessage(response.code, "reverse"))
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

    private fun httpErrorMessage(code: Int, source: String): String = when (code) {
        400 -> "Geocoding request was rejected as invalid (HTTP 400)."
        401, 403 -> "Google API key rejected or Geocoding API not enabled (HTTP $code). " +
            "Check google.maps.apiKey and that the Geocoding API is enabled for the project."
        429 -> "Geocoding service is rate limiting requests (HTTP 429). Try again shortly."
        in 500..599 -> "Geocoding service error (HTTP $code)."
        else -> "Geocoding $source failed (HTTP $code)."
    }

    /**
     * Parses a Geocoding API response body into destinations. Internal so unit tests can
     * exercise it with captured fixtures. Honors the top-level `status` field —
     * ZERO_RESULTS is an empty success, REQUEST_DENIED names the credential problem.
     */
    internal fun parseResults(body: String): AppResult<List<Destination>> {
        val obj = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            return AppResult.Failure(AppErrorKind.PARSE, "Malformed geocoder response", e)
        }
        return when (val status = obj["status"]?.jsonPrimitive?.content ?: "") {
            "OK" -> {
                val results = obj["results"]?.jsonArray
                // Coordinates are mandatory: results without a geometry are dropped,
                // never (0,0).
                AppResult.Success(results?.mapNotNull { el ->
                    destinationFrom(el.jsonObject, fallbackCoordinate = null)
                } ?: emptyList())
            }
            "ZERO_RESULTS" -> AppResult.Success(emptyList())
            "REQUEST_DENIED" -> AppResult.Failure(
                AppErrorKind.SERVER,
                "Google geocoding request denied — check google.maps.apiKey and that the " +
                    "Geocoding API is enabled.",
            )
            "OVER_QUERY_LIMIT" -> AppResult.Failure(
                AppErrorKind.SERVER,
                "Google geocoding quota exceeded (OVER_QUERY_LIMIT). Try again later.",
            )
            else -> AppResult.Failure(AppErrorKind.SERVER, "Geocoding failed (status=$status)")
        }
    }

    /**
     * Parses a reverse-geocode response for [coordinate]. The pressed coordinate is
     * AUTHORITATIVE: the response supplies the label, but its own lat/lng describe the
     * nearest mapped entity, which can sit tens of metres away from the pin — returning
     * those would silently move the user's destination (marker + route endpoint).
     */
    internal fun parseReverse(body: String, coordinate: Coordinate): AppResult<Destination> {
        return try {
            val obj = json.parseToJsonElement(body).jsonObject
            val status = obj["status"]?.jsonPrimitive?.content ?: ""
            if (status != "OK") {
                // No address found: the pressed pin is already a valid destination.
                AppResult.Success(Destination(coordinate, title = "Dropped pin"))
            } else {
                val resolved = obj["results"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.let { destinationFrom(it, fallbackCoordinate = coordinate) }
                    ?: Destination(coordinate, title = "Dropped pin")
                AppResult.Success(resolved.copy(coordinate = coordinate))
            }
        } catch (e: Exception) {
            AppResult.Failure(AppErrorKind.PARSE, "Malformed geocoder response", e)
        }
    }

    /**
     * Maps one Geocoding API result object to a [Destination]. Returns null when the
     * response carries no usable coordinates and no [fallbackCoordinate] is available —
     * a destination without latitude/longitude can never be routed to, so it must not
     * become a fake (0,0) entry.
     */
    private fun destinationFrom(obj: JsonObject, fallbackCoordinate: Coordinate?): Destination? {
        val location = obj["geometry"]?.jsonObject?.get("location")?.jsonObject
        val lat = location?.get("lat")?.jsonPrimitive?.content?.toDoubleOrNull()
        val lng = location?.get("lng")?.jsonPrimitive?.content?.toDoubleOrNull()
        val coordinate = when {
            lat != null && lng != null -> Coordinate(lat, lng)
            fallbackCoordinate != null -> fallbackCoordinate
            else -> return null
        }

        val formatted = obj["formatted_address"]?.jsonPrimitive?.content ?: ""
        // Title = first component ("Charminar, Hyderabad"), subtitle = the rest — the same
        // presentation contract the previous geocoder had.
        val title = formatted.substringBefore(',').ifBlank { "Dropped pin" }
        val subtitle = formatted.substringAfter(',', "").trim().ifBlank { null }

        return Destination(
            coordinate = coordinate,
            title = title,
            subtitle = subtitle,
        )
    }

    private companion object {
        /** At most one geocoding request per second, app-wide. */
        const val MIN_REQUEST_INTERVAL_MS = 1_000L
        /** Successful search results are reused for a few minutes. */
        const val CACHE_TTL_MS = 10 * 60 * 1_000L
        const val CACHE_MAX_ENTRIES = 64
    }
}
