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
 * Mappls-based geocoder (optional; see docs/mappls.md). Selected in [com.hunternav.di.AppContainer]
 * only when a Mappls Console access token is configured — otherwise Nominatim stays the default.
 *
 * Endpoints (official Mappls REST APIs, `access_token` query-parameter authentication):
 *  - search:   `search.mappls.com/search/places/autosuggest/json`
 *  - reverse:  `search.mappls.com/search/address/rev-geocode`
 *
 * Coordinate contract (the part the public docs understate):
 *  - The public response samples only list eLoc/placeName/placeAddress, but the official
 *    Mappls SDK's own response model (`com.mappls.sdk.services.api.autosuggest.model.ELocation`)
 *    declares `latitude`/`longitude` — the fields ARE returned in live responses. They are
 *    parsed defensively and a result without usable coordinates is DROPPED, never routed to
 *    as a fake (0,0): a destination without latitude/longitude can never be navigated to.
 *  - Reverse geocoding is label-only: the caller's pressed coordinate stays authoritative
 *    (Mappls answers with the nearest mapped entity's own coordinates, which can sit tens of
 *    metres away — returning those would silently move the marker and the route endpoint).
 *
 * Usage policy (same guarantees as the Nominatim provider, spec §5):
 *  - one app-wide rate limiter: at most 1 request/second across search AND reverse,
 *  - successful searches cached briefly so repeated queries never hit the network,
 *  - the caller's GPS position is only sent when a proximity hint is explicitly supplied,
 *  - no retry loop here: failures surface immediately as finite states.
 */
class MapplsGeocodingProvider(
    private val baseUrl: String,
    private val accessToken: String,
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
        if (accessToken.isBlank()) {
            // Fail fast with a diagnosable message instead of firing an unauthenticated request.
            return AppResult.Failure(
                AppErrorKind.SERVER,
                "Mappls access token not configured (set mappls.accessToken, see docs/mappls.md)",
            )
        }
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
            .addPathSegment("places")
            .addPathSegment("autosuggest")
            .addPathSegment("json")
            .addQueryParameter("query", query)
            .addQueryParameter("access_token", accessToken)
            .apply {
                // Optional proximity bias ONLY when the caller explicitly supplies one — the
                // production search path passes none, so live GPS never leaves the device.
                if (near != null) {
                    addQueryParameter("location", "${near.latitude},${near.longitude}")
                }
            }
            .build()

        DebugLog.d(
            "GEOCODING_REQUEST",
            "req=$requestId provider=mappls source=search query=\"$query\" near=${if (near != null) "supplied" else "none"} limit=$limit",
        )

        return try {
            rateLimiter.withPermit {
                networkClient.execute(Request.Builder().url(url).header("User-Agent", NetworkClient.USER_AGENT).get().build())
                    .use { response ->
                        if (!response.isSuccessful) {
                            DebugLog.d("GEOCODING_RESULT", "req=$requestId source=search failed http=${response.code} elapsed_ms=${nowMs() - startedAt}")
                            failureFor(response.code)
                        } else {
                            val body = response.body.string()
                            if (body.isBlank()) {
                                DebugLog.d("GEOCODING_RESULT", "req=$requestId source=search failed reason=empty_body")
                                AppResult.Failure(AppErrorKind.PARSE, "Empty response body")
                            } else {
                                parseSearch(body, limit).also { r ->
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
        if (accessToken.isBlank()) {
            return AppResult.Failure(
                AppErrorKind.SERVER,
                "Mappls access token not configured (set mappls.accessToken, see docs/mappls.md)",
            )
        }
        val requestId = requestSeq.incrementAndGet()
        val startedAt = nowMs()
        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addPathSegment("address")
            .addPathSegment("rev-geocode")
            .addQueryParameter("lat", coordinate.latitude.toString())
            .addQueryParameter("lng", coordinate.longitude.toString())
            .addQueryParameter("access_token", accessToken)
            .build()

        DebugLog.d(
            "GEOCODING_REQUEST",
            "req=$requestId provider=mappls source=reverse lat=${coordinate.latitude} lon=${coordinate.longitude}",
        )

        return try {
            rateLimiter.withPermit {
                networkClient.execute(Request.Builder().url(url).header("User-Agent", NetworkClient.USER_AGENT).get().build())
                    .use { response ->
                        if (!response.isSuccessful) {
                            DebugLog.d("GEOCODING_RESULT", "req=$requestId source=reverse failed http=${response.code} elapsed_ms=${nowMs() - startedAt}")
                            failureFor(response.code)
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

    /**
     * Parses an autosuggest response into destinations, keeping [limit] results. Internal so
     * unit tests can exercise it directly. Results without coordinates are dropped (see class doc).
     */
    internal fun parseSearch(body: String, limit: Int): AppResult<List<Destination>> {
        return try {
            val obj = json.parseToJsonElement(body).jsonObject
            val suggestions = obj["suggestedLocations"]?.jsonArray.orEmpty()
            val destinations = suggestions.mapNotNull { el -> suggestionToDestination(el as? JsonObject ?: return@mapNotNull null) }
            AppResult.Success(destinations.take(limit.coerceIn(1, 20)))
        } catch (e: Exception) {
            AppResult.Failure(AppErrorKind.PARSE, "Malformed geocoder response", e)
        }
    }

    /**
     * Parses a rev-geocode response for [coordinate]. The pressed coordinate is AUTHORITATIVE:
     * the response supplies the label only — its own lat/lng describe the nearest mapped entity
     * and must never move the user's destination (marker + route endpoint).
     * No matching address ⇒ a "Dropped pin" fallback at the pressed coordinate, never a failure:
     * the pin is already valid without a name.
     */
    internal fun parseReverse(body: String, coordinate: Coordinate): AppResult<Destination> {
        return try {
            val obj = json.parseToJsonElement(body).jsonObject
            val first = obj["results"]?.jsonArray?.firstOrNull()?.let { it as? JsonObject }
            if (first == null) {
                AppResult.Success(Destination(coordinate, title = "Dropped pin"))
            } else {
                val address = first.string("formatted_address")
                val poi = first.string("poi")
                val street = first.string("street")
                AppResult.Success(
                    Destination(
                        coordinate = coordinate,
                        title = poi ?: street ?: address?.substringBefore(',')?.trim() ?: "Dropped pin",
                        subtitle = address,
                    ),
                )
            }
        } catch (e: Exception) {
            AppResult.Failure(AppErrorKind.PARSE, "Malformed geocoder response", e)
        }
    }

    /**
     * Maps one autosuggest suggestion to a [Destination]. Returns null when the response carries
     * no usable coordinates — such a result can never be routed to, so it must not become a
     * fake entry (see the coordinate contract in the class doc).
     */
    private fun suggestionToDestination(obj: JsonObject): Destination? {
        val lat = obj.string("latitude")?.toDoubleOrNull()
        val lng = obj.string("longitude")?.toDoubleOrNull()
        if (lat == null || lng == null) return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null

        val placeName = obj.string("placeName")
        val placeAddress = obj.string("placeAddress")
        val pin = obj.string("mapplsPin") ?: obj.string("eLoc")
        return Destination(
            coordinate = Coordinate(lat, lng),
            title = placeName ?: placeAddress?.substringBefore(',')?.trim() ?: pin ?: "Unnamed place",
            subtitle = placeAddress,
        )
    }

    /** Reads a string field leniently: missing, null or blank all collapse to null. */
    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    /**
     * Mappls error codes with diagnosable messages (401/403 almost always mean a bad or
     * missing Console credential, not a transient fault — they must never look like a
     * network hiccup).
     */
    private fun failureFor(code: Int): AppResult.Failure = when (code) {
        401 -> AppResult.Failure(AppErrorKind.SERVER, "HTTP 401 — Mappls access token rejected (check mappls.accessToken)")
        403 -> AppResult.Failure(AppErrorKind.SERVER, "HTTP 403 — Mappls access denied or daily limit reached")
        else -> AppResult.Failure(AppErrorKind.SERVER, "HTTP $code")
    }

    private companion object {
        /** Conservative app-wide policy: at most one geocoder request per second. */
        const val MIN_REQUEST_INTERVAL_MS = 1_000L
        /** Successful search results are reused for a few minutes. */
        const val CACHE_TTL_MS = 10 * 60 * 1_000L
        const val CACHE_MAX_ENTRIES = 64
    }
}
