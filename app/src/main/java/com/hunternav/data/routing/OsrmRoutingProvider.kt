package com.hunternav.data.routing

import com.hunternav.core.result.AppErrorKind
import com.hunternav.core.result.AppResult
import com.hunternav.core.result.toAppErrorKind
import com.hunternav.core.util.DebugLog
import com.hunternav.data.network.HttpTransport
import com.hunternav.data.network.NetworkClient
import com.hunternav.data.network.RequestRateLimiter
import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.Route
import com.hunternav.domain.repository.RoutingProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.atomic.AtomicInteger

/**
 * OSRM HTTP routing provider.
 *
 * V1 uses the public demo server (development endpoint, NOT for production volume).
 * The base URL is injected from BuildConfig (see app/build.gradle.kts + local.properties).
 * The navigation engine and UI depend on [RoutingProvider] only — swap in
 * GraphHopperRoutingProvider later without touching navigation code.
 *
 * Reliability (spec §6):
 *  - conservative app-level rate limit (≤1 request/second) via [RequestRateLimiter],
 *  - bounded retries with exponential backoff for transient failures only
 *    (network/timeout/5xx/429) — never for invalid requests (4xx other than 429),
 *  - every response is validated (routes array, geometry, distance, duration, steps)
 *    before it can be reported as a route,
 *  - HTTP error bodies are retained in debug diagnostics and mapped to distinct,
 *    human-readable failure categories.
 *
 * NOTE: `/route/v1/driving/` is OSRM's **car** profile. It is not proven
 * motorcycle-optimized routing; see README "Motorcycle-routing limitation".
 */
class OsrmRoutingProvider(
    private val baseUrl: String,
    private val networkClient: HttpTransport = NetworkClient(),
    private val rateLimiter: RequestRateLimiter = RequestRateLimiter(MIN_REQUEST_INTERVAL_MS),
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val retryBaseDelayMs: Long = RETRY_BASE_DELAY_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : RoutingProvider {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val requestSeq = AtomicInteger(0)

    /**
     * Builds the OSRM route URL.
     *
     * OSRM takes waypoint pairs as `lon,lat` **joined with `;` inside a single path segment**
     * (`/route/v1/driving/lon1,lat1;lon2,lat2`). Emitting each pair as its own path segment
     * (slash-separated) makes the demo server answer HTTP 400 — that was the real-device bug.
     * Internal so unit tests can assert the exact URL shape.
     */
    internal fun buildRouteUrl(origin: Coordinate, destination: Coordinate, alternatives: Boolean): String =
        baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("route/v1/driving")
            // OSRM expects lon,lat order, semicolon-joined within ONE path segment.
            .addPathSegment("${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}")
            .addQueryParameter("overview", "full")
            .addQueryParameter("geometries", "geojson")
            .addQueryParameter("steps", "true")
            .addQueryParameter("alternatives", if (alternatives) "true" else "false")
            .build()
            .toString()

    /** Outcome of a single HTTP attempt plus whether retrying could change the result. */
    private data class Attempt(val result: AppResult<List<Route>>, val retryable: Boolean)

    override suspend fun getRoutes(
        origin: Coordinate,
        destination: Coordinate,
        alternatives: Boolean,
    ): AppResult<List<Route>> {
        val url = buildRouteUrl(origin, destination, alternatives)
        val requestId = requestSeq.incrementAndGet()
        val startedAt = nowMs()

        DebugLog.d(
            "ROUTE_REQUEST",
            "req=$requestId origin=${origin.latitude},${origin.longitude} " +
                "destination=${destination.latitude},${destination.longitude} " +
                "alternatives=$alternatives url=$url",
        )

        var lastFailure: AppResult.Failure = AppResult.Failure(AppErrorKind.UNKNOWN, "Routing failed")
        for (attempt in 1..maxAttempts) {
            val outcome = attemptRequest(url, requestId)
            val result = outcome.result
            if (result is AppResult.Success) {
                DebugLog.d(
                    "ROUTE_RESPONSE",
                    "req=$requestId attempt=$attempt ok elapsed_ms=${nowMs() - startedAt} " +
                        "routes=${result.value.size} " +
                        "distance_m=${result.value.first().distanceMeters} " +
                        "duration_s=${result.value.first().durationSeconds}",
                )
                return result
            }
            val failure = result as AppResult.Failure
            if (outcome.retryable && attempt < maxAttempts) {
                val backoffMs = retryBaseDelayMs * (1L shl (attempt - 1))
                DebugLog.d(
                    "ROUTE_RETRY",
                    "req=$requestId attempt=$attempt kind=${failure.kind} backoff_ms=$backoffMs",
                )
                delay(backoffMs)
                lastFailure = failure
                continue
            }
            DebugLog.d(
                "ROUTE_RESPONSE",
                "req=$requestId attempt=$attempt failed kind=${failure.kind} " +
                    "elapsed_ms=${nowMs() - startedAt} message=${failure.message}",
            )
            return failure
        }
        return lastFailure
    }

    /** One HTTP round-trip: executes, classifies and parses/validates the response. */
    private suspend fun attemptRequest(url: String, requestId: Int): Attempt = try {
        rateLimiter.withPermit {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", NetworkClient.USER_AGENT)
                .get()
                .build()
            networkClient.execute(request).use { response ->
                if (!response.isSuccessful) {
                    // Retain the server's explanation (e.g. OSRM's 400 message) in diagnostics.
                    val errorBody = runCatching { response.body.string() }.getOrDefault("")
                    DebugLog.d(
                        "ROUTE_RESPONSE",
                        "req=$requestId http=${response.code} body=${errorBody.take(500).ifBlank { "<empty>" }}",
                    )
                    val message = when (response.code) {
                        400 -> "Routing request was rejected as invalid (HTTP 400)."
                        429 -> "Routing service is rate limiting requests (HTTP 429). Try again shortly."
                        in 500..599 -> "Routing service error (HTTP ${response.code})."
                        else -> "Routing request failed (HTTP ${response.code})."
                    }
                    val retryable = response.code == 429 || response.code in 500..599
                    Attempt(AppResult.Failure(AppErrorKind.SERVER, message), retryable)
                } else {
                    val body = response.body.string()
                    if (body.isBlank()) {
                        Attempt(AppResult.Failure(AppErrorKind.PARSE, "Empty response body"), retryable = false)
                    } else {
                        parseAndValidate(body, requestId)
                    }
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val kind = e.toAppErrorKind()
        DebugLog.d("ROUTE_RESPONSE", "req=$requestId exception=${e::class.simpleName} kind=$kind message=${e.message}")
        val retryable = kind == AppErrorKind.NETWORK || kind == AppErrorKind.TIMEOUT
        Attempt(AppResult.Failure(kind, e.message, e), retryable)
    }

    /**
     * Parses an OSRM success body and validates it before it may be shown as a route:
     * `code=Ok`, a non-empty routes array, and at least one route with real geometry,
     * finite distance/duration and maneuver steps. Failures are never replaced with
     * sample data.
     */
    private fun parseAndValidate(body: String, requestId: Int): Attempt {
        val dto = try {
            json.decodeFromString(OsrmResponseDto.serializer(), body)
        } catch (e: Exception) {
            DebugLog.d("ROUTE_PARSE_FAILURE", "req=$requestId stage=decode exception=${e::class.simpleName} message=${e.message}")
            return Attempt(AppResult.Failure(AppErrorKind.PARSE, "Malformed routing response", e), retryable = false)
        }
        if (dto.code != "Ok") {
            val kind = when (dto.code) {
                "NoRoute" -> AppErrorKind.NO_ROUTE
                "NoSegment" -> AppErrorKind.UNREACHABLE_DESTINATION
                else -> AppErrorKind.SERVER
            }
            DebugLog.d("ROUTE_PARSE_FAILURE", "req=$requestId stage=status code=${dto.code} kind=$kind message=${dto.message}")
            return Attempt(AppResult.Failure(kind, dto.message ?: "Routing failed (${dto.code})"), retryable = false)
        }
        if (dto.routes.isEmpty()) {
            DebugLog.d("ROUTE_PARSE_FAILURE", "req=$requestId stage=routes reason=empty")
            return Attempt(AppResult.Failure(AppErrorKind.NO_ROUTE, "No route found to this destination."), retryable = false)
        }

        val valid = dto.routes.filter { isValid(it) }.map { OsrmMappers.toRoute(it) }
        if (valid.isEmpty()) {
            // Routes array present but unusable: missing geometry/distance/steps — a malformed
            // navigation response, NOT a successful route and NOT sample data.
            DebugLog.d("ROUTE_PARSE_FAILURE", "req=$requestId stage=validation reason=no_usable_route")
            return Attempt(
                AppResult.Failure(AppErrorKind.PARSE, "Route response was missing navigation data."),
                retryable = false,
            )
        }

        val first = valid.first()
        val firstPoint = first.geometry.firstOrNull()
        val lastPoint = first.geometry.lastOrNull()
        DebugLog.d(
            "ROUTE_PARSE_SUCCESS",
            "req=$requestId routes=${valid.size}/${dto.routes.size} " +
                "distance_m=${first.distanceMeters} duration_s=${first.durationSeconds} " +
                "steps=${first.legs.sumOf { it.steps.size }} " +
                "first_pt=${firstPoint?.let { "${it.longitude},${it.latitude}" } ?: "n/a"} " +
                "last_pt=${lastPoint?.let { "${it.longitude},${it.latitude}" } ?: "n/a"}",
        )
        return Attempt(AppResult.Success(valid), retryable = false)
    }

    /** A routable OSRM route entry must carry geometry, finite metrics and maneuver steps. */
    private fun isValid(dto: OsrmRouteDto): Boolean {
        val coords = dto.geometry?.coordinates
        return coords != null && coords.size >= 2 &&
            dto.distance.isFinite() && dto.distance >= 0.0 &&
            dto.duration.isFinite() && dto.duration >= 0.0 &&
            dto.legs.any { leg -> leg.steps.isNotEmpty() }
    }

    private companion object {
        /** Public demo servers: never exceed one request per second. */
        const val MIN_REQUEST_INTERVAL_MS = 1_000L
        /** 1 initial attempt + 2 bounded retries. */
        const val DEFAULT_MAX_ATTEMPTS = 3
        /** First retry waits this long; the second attempt waits 2× (exponential backoff). */
        const val RETRY_BASE_DELAY_MS = 700L
    }
}
