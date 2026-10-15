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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicInteger

/**
 * Google Routes API v2 routing provider (docs/google-maps.md). Selected in
 * [com.hunternav.di.AppContainer] when `google.maps.apiKey` is configured.
 *
 * Endpoint: `POST routes.googleapis.com/directions/v2:computeRoutes`
 *  - `travelMode=TWO_WHEELER` — genuine motorcycle/scooter routing (documented Google mode);
 *  - `routingPreference=TRAFFIC_AWARE` — live-traffic-adjusted distance/ETA. If the request
 *    is rejected as invalid (HTTP 400), the provider falls back ONCE to the default
 *    (best-guess) routing preference and logs `ROUTE_TRAFFIC_FALLBACK` — the route stays
 *    real, only the traffic adjustment is missing. Auth (401/403), quota (429) and server
 *    (5xx) failures never trigger the fallback.
 *
 * Reliability (same guarantees as the previous providers, spec §6):
 *  - app-level rate limit (≤1 request/second) via [RequestRateLimiter],
 *  - bounded retries with exponential backoff for transient failures only
 *    (network/timeout/5xx/429) — never for invalid requests,
 *  - every response is validated (routes array, overview polyline, finite distance and
 *    duration, maneuver steps) before it can be reported as a route; malformed responses
 *    are failures, never sample data.
 */
class GoogleRoutesProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val networkClient: HttpTransport = NetworkClient(routing = true),
    private val rateLimiter: RequestRateLimiter = RequestRateLimiter(MIN_REQUEST_INTERVAL_MS),
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val retryBaseDelayMs: Long = RETRY_BASE_DELAY_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : RoutingProvider {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val requestSeq = AtomicInteger(0)

    /**
     * Builds the computeRoutes JSON body. Internal so unit tests can assert the exact
     * payload shape (coordinates, travel mode, routing preference, alternatives flag).
     */
    internal fun buildRequestBody(
        origin: Coordinate,
        destination: Coordinate,
        alternatives: Boolean,
        trafficAware: Boolean,
    ): String = """
        {
          "origin": {"location": {"latLng": {"latitude": ${origin.latitude}, "longitude": ${origin.longitude}}}},
          "destination": {"location": {"latLng": {"latitude": ${destination.latitude}, "longitude": ${destination.longitude}}}},
          "travelMode": "TWO_WHEELER",
          "routingPreference": "${if (trafficAware) "TRAFFIC_AWARE" else "ROUTING_PREFERENCE_UNSPECIFIED"}",
          "computeAlternativeRoutes": $alternatives,
          "languageCode": "en",
          "regionCode": "IN"
        }
    """.trimIndent()

    /** Outcome of a single HTTP attempt: the result, retryability and the HTTP status (if any). */
    private data class Attempt(
        val result: AppResult<List<Route>>,
        val retryable: Boolean,
        val httpCode: Int? = null,
    )

    override suspend fun getRoutes(
        origin: Coordinate,
        destination: Coordinate,
        alternatives: Boolean,
    ): AppResult<List<Route>> {
        if (apiKey.isBlank()) {
            // Fail fast with a diagnosable message instead of firing an unauthenticated request.
            return AppResult.Failure(
                AppErrorKind.SERVER,
                "Google routing is not configured (set google.maps.apiKey, see docs/google-maps.md)",
            )
        }
        val requestId = requestSeq.incrementAndGet()
        val startedAt = nowMs()

        DebugLog.d(
            "ROUTE_REQUEST",
            "req=$requestId provider=google_routes preference=traffic_aware " +
                "origin=${origin.latitude},${origin.longitude} " +
                "destination=${destination.latitude},${destination.longitude} " +
                "alternatives=$alternatives",
        )

        val primary = requestWithRetries(origin, destination, alternatives, trafficAware = true, requestId, startedAt)
        val primaryFailure = primary.result as? AppResult.Failure
            ?: return logSuccess(primary.result, requestId, startedAt)

        // Traffic-aware request rejected as invalid ⇒ one documented fallback to the default
        // routing preference. NOT applied to auth (401/403), quota (429), server (5xx) or
        // transport failures — those are not routing-preference problems.
        if (primary.httpCode == 400) {
            DebugLog.d(
                "ROUTE_TRAFFIC_FALLBACK",
                "req=$requestId from=TRAFFIC_AWARE to=default http=400 " +
                    "reason=preference_unsupported continuing_without_traffic_adjustment",
            )
            val fb = requestWithRetries(origin, destination, alternatives, trafficAware = false, requestId, startedAt)
            val fbFailure = fb.result as? AppResult.Failure
                ?: return logSuccess(fb.result, requestId, startedAt)
            return logFailure(fbFailure, requestId, startedAt)
        }
        return logFailure(primaryFailure, requestId, startedAt)
    }

    private fun logSuccess(result: AppResult<List<Route>>, requestId: Int, startedAt: Long): AppResult<List<Route>> {
        (result as AppResult.Success).value.firstOrNull()?.let { first ->
            DebugLog.d(
                "ROUTE_RESPONSE",
                "req=$requestId ok elapsed_ms=${nowMs() - startedAt} routes=${result.value.size} " +
                    "distance_m=${first.distanceMeters} duration_s=${first.durationSeconds}",
            )
        }
        return result
    }

    private fun logFailure(failure: AppResult.Failure, requestId: Int, startedAt: Long): AppResult.Failure {
        DebugLog.d(
            "ROUTE_RESPONSE",
            "req=$requestId failed kind=${failure.kind} elapsed_ms=${nowMs() - startedAt} message=${failure.message}",
        )
        return failure
    }

    /**
     * Runs the bounded retry loop against one routing preference. Returns the winning/last
     * [Attempt] so its HTTP status survives — the caller needs it to decide on fallback.
     */
    private suspend fun requestWithRetries(
        origin: Coordinate,
        destination: Coordinate,
        alternatives: Boolean,
        trafficAware: Boolean,
        requestId: Int,
        startedAt: Long,
    ): Attempt {
        val body = buildRequestBody(origin, destination, alternatives, trafficAware)
        var lastFailure = Attempt(AppResult.Failure(AppErrorKind.UNKNOWN, "Routing failed"), retryable = false)
        for (attempt in 1..maxAttempts) {
            val outcome = attemptRequest(body, requestId)
            val result = outcome.result
            if (result is AppResult.Success) return outcome
            val failure = result as AppResult.Failure
            if (outcome.retryable && attempt < maxAttempts) {
                val backoffMs = retryBaseDelayMs * (1L shl (attempt - 1))
                DebugLog.d("ROUTE_RETRY", "req=$requestId attempt=$attempt kind=${failure.kind} backoff_ms=$backoffMs")
                delay(backoffMs)
                lastFailure = outcome
                continue
            }
            return outcome
        }
        return lastFailure
    }

    /**
     * One HTTP round-trip: executes, classifies and parses/validates the response. Parsing
     * and validation run on [Dispatchers.Default] — decoding a multi-thousand-point geometry
     * on the main thread is a real jank source.
     */
    private suspend fun attemptRequest(body: String, requestId: Int): Attempt = try {
        rateLimiter.withPermit {
            val request = Request.Builder()
                .url(endpointUrl())
                .header("Content-Type", "application/json")
                .header("X-Goog-Api-Key", apiKey)
                .header("X-Goog-FieldMask", FIELD_MASK)
                .post(body.toRequestBody(JSON_MEDIA))
                .build()
            networkClient.execute(request).use { response ->
                if (!response.isSuccessful) {
                    val errorBody = runCatching { response.body.string() }.getOrDefault("")
                    DebugLog.d(
                        "ROUTE_RESPONSE",
                        "req=$requestId http=${response.code} body=${errorBody.take(500).ifBlank { "<empty>" }}",
                    )
                    val message = when (response.code) {
                        400 -> "Routing request was rejected as invalid (HTTP 400)."
                        401, 403 -> "Google API key rejected or Routes API not enabled (HTTP ${response.code}). " +
                            "Check google.maps.apiKey and that the Routes API is enabled for the project."
                        429 -> "Routing service is rate limiting requests (HTTP 429). Try again shortly."
                        in 500..599 -> "Routing service error (HTTP ${response.code})."
                        else -> "Routing request failed (HTTP ${response.code})."
                    }
                    val retryable = response.code == 429 || response.code in 500..599
                    Attempt(AppResult.Failure(AppErrorKind.SERVER, message), retryable, httpCode = response.code)
                } else {
                    val responseBody = response.body.string()
                    if (responseBody.isBlank()) {
                        Attempt(AppResult.Failure(AppErrorKind.PARSE, "Empty response body"), retryable = false)
                    } else {
                        withContext(Dispatchers.Default) { parseAndValidate(responseBody, requestId) }
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

    private fun endpointUrl(): String = baseUrl.trimEnd('/') + "/directions/v2:computeRoutes"

    /**
     * Parses a success body and validates it before it may be shown as a route: a non-empty
     * routes array with at least one route carrying real overview geometry, finite
     * distance/duration and maneuver steps. Failures are never replaced with sample data.
     */
    private fun parseAndValidate(body: String, requestId: Int): Attempt {
        val dto = try {
            json.decodeFromString(GoogleRoutesResponseDto.serializer(), body)
        } catch (e: Exception) {
            DebugLog.d("ROUTE_PARSE_FAILURE", "req=$requestId stage=decode exception=${e::class.simpleName} message=${e.message}")
            return Attempt(AppResult.Failure(AppErrorKind.PARSE, "Malformed routing response", e), retryable = false)
        }
        if (dto.routes.isEmpty()) {
            DebugLog.d("ROUTE_PARSE_FAILURE", "req=$requestId stage=routes reason=empty")
            return Attempt(AppResult.Failure(AppErrorKind.NO_ROUTE, "No route found to this destination."), retryable = false)
        }

        val valid = dto.routes.filter { isValid(it) }.map { GoogleRoutesMappers.toRoute(it) }
        if (valid.isEmpty()) {
            // Routes array present but unusable: missing geometry/distance/steps — a malformed
            // navigation response, NOT a successful route and NOT sample data.
            DebugLog.d("ROUTE_PARSE_FAILURE", "req=$requestId stage=validation reason=no_usable_route")
            return Attempt(
                AppResult.Failure(AppErrorKind.PARSE, "Route response was missing navigation data."),
                retryable = false,
            )
        }
        return Attempt(AppResult.Success(valid), retryable = false)
    }

    /** A routable route entry must carry overview geometry, finite metrics and maneuver steps. */
    private fun isValid(dto: GoogleRouteDto): Boolean {
        val geometry = GoogleRoutesMappers.decodePolyline(dto.polyline?.encodedPolyline)
        val distance = dto.distanceMeters?.toDouble()
        val duration = GoogleRoutesMappers.toDurationSeconds(dto.duration)
        return geometry.size >= 2 &&
            distance != null && distance.isFinite() && distance >= 0.0 &&
            duration.isFinite() && duration >= 0.0 &&
            dto.legs.any { leg -> leg.steps.isNotEmpty() }
    }

    private companion object {
        /** Conservative app-wide policy: at most one routing request per second. */
        const val MIN_REQUEST_INTERVAL_MS = 1_000L
        /** 1 initial attempt + 2 bounded retries. */
        const val DEFAULT_MAX_ATTEMPTS = 3
        /** First retry waits this long; the second attempt waits 2× (exponential backoff). */
        const val RETRY_BASE_DELAY_MS = 700L

        val JSON_MEDIA = "application/json".toMediaType()

        /**
         * Requested response fields — only what the mapper consumes, keeping responses small
         * (Google bills per returned field beyond this mask).
         */
        const val FIELD_MASK = "routes.distanceMeters,routes.duration,routes.polyline," +
            "routes.legs.distanceMeters,routes.legs.duration," +
            "routes.legs.steps.distanceMeters,routes.legs.steps.duration,routes.legs.steps.polyline," +
            "routes.legs.steps.startLocation,routes.legs.steps.endLocation," +
            "routes.legs.steps.navigationInstruction,routes.legs.steps.staticInstruction"
    }
}
