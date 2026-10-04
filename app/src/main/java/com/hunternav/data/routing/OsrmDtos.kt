package com.hunternav.data.routing

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** OSRM top-level route response. */
@Serializable
data class OsrmResponseDto(
    val code: String,
    val routes: List<OsrmRouteDto> = emptyList(),
    val message: String? = null,
)

@Serializable
data class OsrmRouteDto(
    val distance: Double,
    val duration: Double,
    val geometry: GeometryDto? = null,
    val legs: List<OsrmLegDto> = emptyList(),
)

@Serializable
data class OsrmLegDto(
    val distance: Double,
    val duration: Double,
    val summary: String? = null,
    val steps: List<OsrmStepDto> = emptyList(),
)

@Serializable
data class OsrmStepDto(
    val distance: Double,
    val duration: Double,
    val name: String? = null,
    val geometry: GeometryDto? = null,
    val maneuver: OsrmManeuverDto,
    val mode: String? = null,
)

@Serializable
data class OsrmManeuverDto(
    val type: String,
    val modifier: String? = null,
    /** [lon, lat] per GeoJSON convention. */
    val location: List<Double> = emptyList(),
    val exit: Int? = null,
)

/**
 * OSRM geometry: a GeoJSON LineString object when geometries=geojson, or an encoded polyline5
 * string otherwise. This DTO accepts both so parsing is robust to provider configuration changes.
 */
@Serializable(with = GeometryDtoSerializer::class)
data class GeometryDto(
    val type: String? = null,
    /** Flat list of [lon, lat] pairs (GeoJSON order). */
    val coordinates: List<List<Double>> = emptyList(),
)

/** Serializes GeometryDto via a JsonElement, accepting polyline strings and GeoJSON shapes. */
object GeometryDtoSerializer : KSerializer<GeometryDto> {
    private val coordListSerializer = ListSerializer(ListSerializer(Double.serializer()))

    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): GeometryDto {
        val element = decoder.decodeSerializableValue(JsonElement.serializer())
        return when (element) {
            // Encoded polyline string (geometries=polyline default).
            is JsonPrimitive -> GeometryDto(coordinates = decodePolyline(element.jsonPrimitive.content))
            // GeoJSON LineString object (geometries=geojson).
            is JsonObject -> {
                val coords = element["coordinates"]
                if (coords is JsonArray) {
                    GeometryDto(
                        type = (element["type"] as? JsonPrimitive)?.content,
                        coordinates = Json.decodeFromJsonElement(coordListSerializer, coords),
                    )
                } else GeometryDto()
            }
            // Bare coordinate array.
            is JsonArray -> GeometryDto(coordinates = Json.decodeFromJsonElement(coordListSerializer, element))
            else -> GeometryDto()
        }
    }

    override fun serialize(encoder: Encoder, value: GeometryDto) {
        val element: JsonElement = buildJsonObject {
            value.type?.let { put("type", it) }
            put("coordinates", Json.encodeToJsonElement(coordListSerializer, value.coordinates))
        }
        encoder.encodeSerializableValue(JsonElement.serializer(), element)
    }
}

/** Decodes Google-style polyline5 into [lon, lat] pairs. */
internal fun decodePolyline(encoded: String, precision: Int = 5): List<List<Double>> {
    val result = mutableListOf<List<Double>>()
    var index = 0
    var lat = 0
    var lon = 0
    val factor = Math.pow(10.0, precision.toDouble())
    while (index < encoded.length) {
        var latDelta = 0
        var shift = 0
        var b: Int
        do {
            b = encoded[index++].code - 63
            latDelta = latDelta or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lat += if (latDelta and 1 != 0) (latDelta shr 1).inv() else (latDelta shr 1)

        var lonDelta = 0
        shift = 0
        do {
            b = encoded[index++].code - 63
            lonDelta = lonDelta or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lon += if (lonDelta and 1 != 0) (lonDelta shr 1).inv() else (lonDelta shr 1)

        result.add(listOf(lon / factor, lat / factor))
    }
    return result
}
