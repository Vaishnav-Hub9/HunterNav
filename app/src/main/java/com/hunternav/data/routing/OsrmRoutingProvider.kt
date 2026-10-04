package com.hunternav.data.routing

import com.hunternav.core.result.AppErrorKind
import com.hunternav.core.result.AppResult
import com.hunternav.core.result.toAppErrorKind
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

    override suspend fun getRoutes(
        origin: Coordinate,
        destination: Coordinate,
        alternatives: Boolean,
    ): AppResult<List<Route>> {
        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("route/v1/driving")
            // OSRM expects lon,lat order.
            .addPathSegment("${origin.longitude},${origin.latitude}")
            .addPathSegment("${destination.longitude},${destination.latitude}")
            .addQueryParameter("overview", "full")
            .addQueryParameter("geometries", "geojson")
            .addQueryParameter("steps", "true")
            .addQueryParameter("alternatives", if (alternatives) "true" else "false")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", NetworkClient.USER_AGENT)
            .get()
            .build()

        return try {
            networkClient.execute(request).use { response ->
                if (!response.isSuccessful) {
                    AppResult.Failure(AppErrorKind.SERVER, "HTTP ${response.code}")
                } else {
                    val body = response.body.string()
                    if (body.isBlank()) {
                        AppResult.Failure(AppErrorKind.PARSE, "Empty response body")
                    } else {
                        parse(body)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Failure(e.toAppErrorKind(), e.message, e)
        }
    }

    private fun parse(body: String): AppResult<List<Route>> {
        val dto = try {
            json.decodeFromString(OsrmResponseDto.serializer(), body)
        } catch (e: Exception) {
            return AppResult.Failure(AppErrorKind.PARSE, "Malformed routing response", e)
        }
        if (dto.code != "Ok") {
            val kind = when (dto.code) {
                "NoRoute" -> AppErrorKind.NO_ROUTE
                "NoSegment" -> AppErrorKind.UNREACHABLE_DESTINATION
                else -> AppErrorKind.SERVER
            }
            return AppResult.Failure(kind, dto.message ?: "Routing failed (${dto.code})")
        }
        if (dto.routes.isEmpty()) return AppResult.Failure(AppErrorKind.NO_ROUTE, "No route found")
        return AppResult.Success(dto.routes.map { OsrmMappers.toRoute(it) })
    }
}
