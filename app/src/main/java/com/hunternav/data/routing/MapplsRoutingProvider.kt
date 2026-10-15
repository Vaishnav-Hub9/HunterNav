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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mappls (MapmyIndia) routing provider — see docs/mappls.md. Selected in
 * [com.hunternav.di.AppContainer] when a Mappls access token is configured; without one the
 * app keeps OsrmRoutingProvider, so credential-free checkouts and CI are unchanged.
 *
 * Endpoint: `route.mappls.com/route/direction/{resource}/{profile}/{lon,lat;lon,lat}`
 * (official Mappls REST API, `access_token` query-parameter authentication).
 *
 * Why this beats OSRM for HunterNav:
 *  - `route_traffic` searches routes CONSIDERING LIVE TRAFFIC (India) — traffic-aware
 *    distance/ETA, which the OSRM public demo cannot provide. If the account/region rejects
 *    the traffic resource (HTTP 400/404/412), the provider falls back to the documented
 *    non-traffic `route_adv` resource once and logs `ROUTE_RESOURCE_FALLBACK` — the route is
 *    still real, only the traffic adjustment is missing.
 *  - `biking` profile is genuine two-wheeler routing (OSRM only offered its car profile —
 *    see README's motorcycle-routing note).
 *
 * The response schema is OSRM-compatible by design (Mappls documents the same
 * `code/routes/legs/steps/maneuver` fields, geometry formats and maneuver type/modifier
 * vocabulary), so [OsrmResponseDto]/[OsrmMappers] parse it unchanged — this class is the
 * provider-specific layer only (URL, auth, resource fallback, error mapping).
 *
 * Reliability (same guarantees as [OsrmRoutingProvider], spec §6):
 *  - app-level rate limit (≤1 request/second) via [RequestRateLimiter],
 *  - bounded retries with exponential backoff for transient failures only
 *    (network/timeout/5xx/429) — never for invalid requests (4xx other than 429),
 *  - every response is validated (routes array, geometry, distance, duration, steps)
 *    before it can be reported as a route; failures are never replaced with sample data.
 */
class MapplsRoutingProvider(
    private val baseUrl: String,
    private val accessToken: String,
    private val profile: String = DEFAULT_PROFILE,
    private val preferredResource: String = RESOURCE_TRAFFIC,
    private val fallbackResource: String = RESOURCE_ADV,
    private val networkClient: HttpTransport = NetworkClient(routing = true),
    private val rateLimiter: RequestRateLimiter = RequestRateLimiter(MIN_REQUEST_INTERVAL_MS),
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val retryBaseDelayMs: Long = RETRY_BASE_DELAY_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : RoutingProvider {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val requestSeq = AtomicInteger(0)

    /** The profile is embedded in the URL path — only documented values are ever sent. */
    private val safeProfile = profile.trim().lowercase().takeIf { it in PROFILES } ?: DEFAULT_PROFILE

    /**
     * Builds the Mappls route URL. Positions are `lon,lat` pairs **joined with `;` inside a
     * single path segment** — the same layout Mappls documents
     * (`route/direction/route_adv/driving/77.131123,28.552413;17ZUL7`). Internal so unit tests
     * can assert the exact URL shape.
     */
    internal fun buildRouteUrl(
        origin: Coordinate,
        destination: Coordinate,
        alternatives: Boolean,
        resource: String = preferredResource,
    ): String =
        baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("route/direction")
            .addPathSegment(resource)
            .addPathSegment(safeProfile)
            // Mappls expects lon,lat order, semicolon-joined within ONE path segment.
            .addPathSegment("${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}")
            .addQueryParameter("steps", "true")
            .addQueryParameter("geometries", "geojson")
            .addQueryParameter("overview", "full")
            // alternatives=n is the documented form ("up to n alternative routes");
            // when no alternatives are wanted the parameter is omitted entirely (the
            // documented sample request without it returns a single route).
            .apply { if (alternatives) addQueryParameter("alternatives", "2") }
            .addQueryParameter("access_token", accessToken)
            .build()
            .toString()

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
        if (accessToken.isBlank()) {
            // Fail fast with a diagnosable message instead of firing an unauthenticated request.
            return AppResult.Failure(
                AppErrorKind.SERVER,
                "Mappls access token not configured (set mappls.accessToken, see docs/mappls.md)",
            )
        }
        val requestId = requestSeq.incrementAndGet()
        val startedAt = nowMs()

        DebugLog.d(
            "ROUTE_REQUEST",
            "req=$requestId provider=mappls resource=$preferredResource profile=$safeProfile " +
                "origin=${origin.latitude},${origin.longitude} " +
                "destination=${destination.latitude},${destination.longitude} " +
                "alternatives=$alternatives",
        )

        val primary = requestWithRetries(origin, destination, alternatives, preferredResource, requestId, startedAt)
        val primaryFailure = primary.result as? AppResult.Failure
            ?: return logSuccess(primary.result, requestId, startedAt)

        // Traffic resource unsupported for this account/region ⇒ one documented fallback to
        // the non-traffic resource. NOT applied to auth (401), quota (429), server (5xx) or
        // transport failures — those are not resource-shape problems.
        val unsupported = primary.httpCode in RESOURCE_UNSUPPORTED_CODES
        if (preferredResource != fallbackResource && unsupported) {
            DebugLog.d(
                "ROUTE_RESOURCE_FALLBACK",
                "req=$requestId from=$preferredResource to=$fallbackResource http=${primary.httpCode} " +
                    "reason=resource_unsupported continuing_without_traffic_adjustment",
            )
            val fb = requestWithRetries(origin, destination, alternatives, fallbackResource, requestId, startedAt)
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
     * Runs the bounded retry loop against one resource. Returns the winning/last [Attempt] so
     * its HTTP status survives — the caller needs it to decide on resource fallback.
     */
    private suspend fun requestWithRetries(
        origin: Coordinate,
        destination: Coordinate,
        alternatives: Boolean,
        resource: String,
        requestId: Int,
        startedAt: Long,
    ): Attempt {
        val url = buildRouteUrl(origin, destination, alternatives, resource)
        var lastFailure = Attempt(AppResult.Failure(AppErrorKind.UNKNOWN, "Routing failed"), retryable = false)
        for (attempt in 1..maxAttempts) {
            val outcome = attemptRequest(url, requestId)
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
     * One HTTP round-trip: executes, classifies and parses/validates the response. Parsing and
     * validation run on [Dispatchers.Default] — JSON-decoding a multi-thousand-point geometry on
     * the caller's thread (the main thread for preview fetches) is a real jank source.
     */
    private suspend fun attemptRequest(url: String, requestId: Int): Attempt = try {
        rateLimiter.withPermit {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", NetworkClient.USER_AGENT)
                .get()
                .build()
            networkClient.execute(request).use { response ->
                if (!response.isSuccessful) {
                    // Retain the server's explanation in diagnostics (debug builds only).
                    val errorBody = runCatching { response.body.string() }.getOrDefault("")
                    DebugLog.d(
                        "ROUTE_RESPONSE",
                        "req=$requestId http=${response.code} body=${errorBody.take(500).ifBlank { "<empty>" }}",
                    )
                    val message = when (response.code) {
                        400 -> "Routing request was rejected as invalid (HTTP 400)."
                        401 -> "Mappls access token rejected (HTTP 401). Check mappls.accessToken."
                        403 -> "Mappls routing access denied or limit reached (HTTP 403)."
                        412 -> "Mappls routing precondition failed — a required parameter is missing (HTTP 412)."
                        429 -> "Routing service is rate limiting requests (HTTP 429). Try again shortly."
                        in 500..599 -> "Routing service error (HTTP ${response.code})."
                        else -> "Routing request failed (HTTP ${response.code})."
                    }
                    val retryable = response.code == 429 || response.code in 500..599
                    Attempt(AppResult.Failure(AppErrorKind.SERVER, message), retryable, httpCode = response.code)
                } else {
                    val body = response.body.string()
                    if (body.isBlank()) {
                        Attempt(AppResult.Failure(AppErrorKind.PARSE, "Empty response body"), retryable = false)
                    } else {
                        withContext(Dispatchers.Default) { parseAndValidate(body, requestId) }
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
     * Parses a Mappls success body (OSRM-compatible schema) and validates it before it may be
     * shown as a route: `code=Ok`, a non-empty routes array, and at least one route with real
     * geometry, finite distance/duration and maneuver steps. Failures are never replaced with
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
        return Attempt(AppResult.Success(valid), retryable = false)
    }

    /** A routable route entry must carry geometry, finite metrics and maneuver steps. */
    private fun isValid(dto: OsrmRouteDto): Boolean {
        val coords = dto.geometry?.coordinates
        return coords != null && coords.size >= 2 &&
            dto.distance.isFinite() && dto.distance >= 0.0 &&
            dto.duration.isFinite() && dto.duration >= 0.0 &&
            dto.legs.any { leg -> leg.steps.isNotEmpty() }
    }

    private companion object {
        /** Conservative app-wide policy: at most one routing request per second. */
        const val MIN_REQUEST_INTERVAL_MS = 1_000L
        /** 1 initial attempt + 2 bounded retries. */
        const val DEFAULT_MAX_ATTEMPTS = 3
        /** First retry waits this long; the second attempt waits 2× (exponential backoff). */
        const val RETRY_BASE_DELAY_MS = 700L

        /** Documented Mappls resources: traffic-aware first, plain second. */
        const val RESOURCE_TRAFFIC = "route_traffic"
        const val RESOURCE_ADV = "route_adv"
        /** HTTP statuses meaning "this resource/parameter set is not supported here". */
        val RESOURCE_UNSUPPORTED_CODES = setOf(400, 404, 412)

        /** Two-wheeler routing — the profile HunterNav is built for. Documented values only. */
        const val DEFAULT_PROFILE = "biking"
        val PROFILES = setOf("driving", "biking", "walking", "trucking")
    }
}
