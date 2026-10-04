package com.hunternav.data.geocoding

import com.hunternav.core.result.AppErrorKind
import com.hunternav.core.result.AppResult
import com.hunternav.core.result.toAppErrorKind
import com.hunternav.core.util.DebugLog
import com.hunternav.data.network.NetworkClient
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

/**
 * Nominatim-based geocoder (low-volume OSM service, development use).
 * The base URL is configurable via BuildConfig for self-hosting later.
 * Usage policy: identify with a User-Agent, keep volume low, no bulk queries.
 */
class NominatimGeocodingProvider(
    private val baseUrl: String,
    private val networkClient: NetworkClient = NetworkClient(),
) : GeocodingProvider {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun search(query: String, near: Coordinate?, limit: Int): AppResult<List<Destination>> {
        if (query.isBlank()) return AppResult.Success(emptyList())

        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", query)
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("addressdetails", "1")
            .addQueryParameter("limit", limit.coerceIn(1, 20).toString())
            .apply {
                // Viewbox + bounded keeps results near the rider without excluding the rest of the world.
                if (near != null) {
                    val dLat = 0.7
                    val dLon = 0.7
                    addQueryParameter("viewbox", "${near.longitude - dLon},${near.latitude + dLat},${near.longitude + dLon},${near.latitude - dLat}")
                }
            }
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", NetworkClient.USER_AGENT)
            .get()
            .build()

        DebugLog.d(
            "GEOCODING_REQUEST",
            "source=search query=\"$query\" " +
                "near=${near?.let { "${it.latitude},${it.longitude}" } ?: "none"} limit=$limit",
        )

        return try {
            networkClient.execute(request).use { response ->
                if (!response.isSuccessful) {
                    DebugLog.d("GEOCODING_RESULT", "source=search failed http=${response.code}")
                    AppResult.Failure(AppErrorKind.SERVER, "HTTP ${response.code}")
                } else {
                    val body = response.body.string()
                    if (body.isBlank()) {
                        DebugLog.d("GEOCODING_RESULT", "source=search failed reason=empty_body")
                        AppResult.Failure(AppErrorKind.PARSE, "Empty response body")
                    } else {
                        parseSearch(body).also { r ->
                            DebugLog.d(
                                "GEOCODING_RESULT",
                                if (r is AppResult.Success) {
                                    "source=search ok count=${r.value.size} " +
                                        "titles=${r.value.take(3).joinToString(" | ") { it.title }}"
                                } else {
                                    val f = r as AppResult.Failure
                                    "source=search failed kind=${f.kind} message=${f.message}"
                                },
                            )
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.d(
                "GEOCODING_RESULT",
                "source=search exception=${e::class.simpleName} message=${e.message}",
            )
            AppResult.Failure(e.toAppErrorKind(), e.message, e)
        }
    }

    override suspend fun reverse(coordinate: Coordinate): AppResult<Destination> {
        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegment("reverse")
            .addQueryParameter("lat", coordinate.latitude.toString())
            .addQueryParameter("lon", coordinate.longitude.toString())
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("addressdetails", "1")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", NetworkClient.USER_AGENT)
            .get()
            .build()

        DebugLog.d(
            "GEOCODING_REQUEST",
            "source=reverse lat=${coordinate.latitude} lon=${coordinate.longitude}",
        )

        return try {
            networkClient.execute(request).use { response ->
                if (!response.isSuccessful) {
                    DebugLog.d("GEOCODING_RESULT", "source=reverse failed http=${response.code}")
                    AppResult.Failure(AppErrorKind.SERVER, "HTTP ${response.code}")
                } else {
                    val body = response.body.string()
                    if (body.isBlank()) {
                        DebugLog.d("GEOCODING_RESULT", "source=reverse failed reason=empty_body")
                        AppResult.Failure(AppErrorKind.PARSE, "Empty response body")
                    } else {
                        parseReverse(body, coordinate).also { r ->
                            DebugLog.d(
                                "GEOCODING_RESULT",
                                if (r is AppResult.Success) {
                                    "source=reverse ok title=${r.value.title} " +
                                        "coords=${r.value.coordinate.latitude},${r.value.coordinate.longitude}"
                                } else {
                                    val f = r as AppResult.Failure
                                    "source=reverse failed kind=${f.kind} message=${f.message}"
                                },
                            )
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.d(
                "GEOCODING_RESULT",
                "source=reverse exception=${e::class.simpleName} message=${e.message}",
            )
            AppResult.Failure(e.toAppErrorKind(), e.message, e)
        }
    }

    private fun parseSearch(body: String): AppResult<List<Destination>> {
        return try {
            val arr = json.parseToJsonElement(body).jsonArray
            AppResult.Success(arr.map { el -> destinationFrom(el.jsonObject, fallbackSubtitle = null) })
        } catch (e: Exception) {
            AppResult.Failure(AppErrorKind.PARSE, "Malformed geocoder response", e)
        }
    }

    private fun parseReverse(body: String, coordinate: Coordinate): AppResult<Destination> {
        return try {
            val obj = json.parseToJsonElement(body).jsonObject
            if (obj.containsKey("error")) {
                AppResult.Success(Destination(coordinate, title = "Dropped pin"))
            } else {
                AppResult.Success(destinationFrom(obj, fallbackSubtitle = null))
            }
        } catch (e: Exception) {
            AppResult.Failure(AppErrorKind.PARSE, "Malformed geocoder response", e)
        }
    }

    private fun destinationFrom(obj: JsonObject, fallbackSubtitle: String?): Destination {
        val name = obj["name"]?.jsonPrimitive?.takeIf { it.isString && it.content.isNotBlank() }
        val displayName = obj["display_name"]?.jsonPrimitive?.content ?: ""
        val address = obj["address"] as? JsonObject
        val subtitle = address?.let {
            val road = it["road"]?.let { v -> runCatching { v.jsonPrimitive.content }.getOrNull() }
            val city = it["city"]?.let { v -> runCatching { v.jsonPrimitive.content }.getOrNull() }
                ?: it["town"]?.let { v -> runCatching { v.jsonPrimitive.content }.getOrNull() }
                ?: it["village"]?.let { v -> runCatching { v.jsonPrimitive.content }.getOrNull() }
            listOfNotNull(road, city).joinToString(", ").ifBlank { null }
        } ?: fallbackSubtitle

        val lat = obj["lat"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val lon = obj["lon"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val coordinate = if (lat != null && lon != null) Coordinate(lat, lon) else Coordinate(0.0, 0.0)

        return Destination(
            coordinate = coordinate,
            title = name?.content ?: displayName.substringBefore(',').ifBlank { "Dropped pin" },
            subtitle = subtitle ?: displayName.substringAfter(',').trim().ifBlank { null },
        )
    }
}
