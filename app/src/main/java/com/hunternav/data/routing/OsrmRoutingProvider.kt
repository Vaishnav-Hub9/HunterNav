package com.hunternav.data.routing

import com.hunternav.core.result.AppErrorKind
import com.hunternav.core.result.AppResult
import com.hunternav.core.result.toAppErrorKind
import com.hunternav.core.util.DebugLog
import com.hunternav.data.network.NetworkClient
import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.Route
import com.hunternav.domain.repository.RoutingProvider
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * OSRM HTTP routing provider.
 *
 * V1 uses the public demo server (development endpoint, NOT for production volume).
 * The base URL is injected from BuildConfig (see app/build.gradle.kts + local.properties).
 * The navigation engine and UI depend on [RoutingProvider] only — swap in
 * GraphHopperRoutingProvider later without touching navigation code.
 */
class OsrmRoutingProvider(
    private val baseUrl: String,
    private val networkClient: NetworkClient = NetworkClient(),
) : RoutingProvider {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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

    override suspend fun getRoutes(
        origin: Coordinate,
        destination: Coordinate,
        alternatives: Boolean,
    ): AppResult<List<Route>> {
        val url = buildRouteUrl(origin, destination, alternatives)

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", NetworkClient.USER_AGENT)
            .get()
            .build()

        DebugLog.d(
            "ROUTE_REQUEST",
            "origin=${origin.latitude},${origin.longitude} " +
                "destination=${destination.latitude},${destination.longitude} " +
                "alternatives=$alternatives url=$url",
        )

        return try {
            networkClient.execute(request).use { response ->
                if (!response.isSuccessful) {
                    // Surface the server's explanation (e.g. OSRM's 400 message) in Logcat.
                    val errorBody = runCatching { response.body.string() }.getOrDefault("")
                    DebugLog.d(
                        "ROUTE_RESPONSE",
                        "failed http=${response.code} body=${errorBody.take(500).ifBlank { "<empty>" }}",
                    )
                    AppResult.Failure(AppErrorKind.SERVER, "HTTP ${response.code}")
                } else {
                    val body = response.body.string()
                    val outcome = if (body.isBlank()) {
                        AppResult.Failure(AppErrorKind.PARSE, "Empty response body")
                    } else {
                        parse(body)
                    }
                    DebugLog.d(
                        "ROUTE_RESPONSE",
                        if (outcome is AppResult.Success) {
                            "ok routes=${outcome.value.size} " +
                                "distance_m=${outcome.value.firstOrNull()?.distanceMeters} " +
                                "duration_s=${outcome.value.firstOrNull()?.durationSeconds} " +
                                "summary=${outcome.value.firstOrNull()?.summaryRoadName ?: "null"}"
                        } else {
                            val f = outcome as AppResult.Failure
                            "failed kind=${f.kind} message=${f.message}"
                        },
                    )
                    outcome
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.d(
                "ROUTE_RESPONSE",
                "exception=${e::class.simpleName} message=${e.message}",
            )
            AppResult.Failure(e.toAppErrorKind(), e.message, e)
        }
    }

    private fun parse(body: String): AppResult<List<Route>> {
        val dto = try {
            json.decodeFromString(OsrmResponseDto.serializer(), body)
        } catch (e: Exception) {
            DebugLog.d("ROUTE_PARSE_FAILURE", "stage=decode exception=${e::class.simpleName} message=${e.message}")
            return AppResult.Failure(AppErrorKind.PARSE, "Malformed routing response", e)
        }
        if (dto.code != "Ok") {
            val kind = when (dto.code) {
                "NoRoute" -> AppErrorKind.NO_ROUTE
                "NoSegment" -> AppErrorKind.UNREACHABLE_DESTINATION
                else -> AppErrorKind.SERVER
            }
            DebugLog.d("ROUTE_PARSE_FAILURE", "stage=status code=${dto.code} kind=$kind message=${dto.message}")
            return AppResult.Failure(kind, dto.message ?: "Routing failed (${dto.code})")
        }
        if (dto.routes.isEmpty()) {
            DebugLog.d("ROUTE_PARSE_FAILURE", "stage=routes reason=empty")
            return AppResult.Failure(AppErrorKind.NO_ROUTE, "No route found")
        }
        DebugLog.d(
            "ROUTE_PARSE_SUCCESS",
            "routes=${dto.routes.size} " +
                "distance_m=${dto.routes.first().distance} " +
                "duration_s=${dto.routes.first().duration} " +
                "steps=${dto.routes.first().legs.sumOf { it.steps.size }}",
        )
        return AppResult.Success(dto.routes.map { OsrmMappers.toRoute(it) })
    }
}
