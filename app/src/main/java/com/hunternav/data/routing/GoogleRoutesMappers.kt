package com.hunternav.data.routing

import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.Maneuver
import com.hunternav.domain.model.ManeuverType
import com.hunternav.domain.model.Route
import com.hunternav.domain.model.RouteLeg
import com.hunternav.domain.model.RouteStep

/**
 * Maps Google Routes API v2 responses into the provider-independent domain models.
 * Provider detail (protobuf duration strings, encoded polylines, maneuver enum names)
 * stays here — the UI and navigation engine only ever see [Route]/[Maneuver].
 */
object GoogleRoutesMappers {

    /**
     * Maps the documented Routes API v2 `Maneuver` enum names into domain maneuver types.
     * Unknown values degrade to [ManeuverType.CONTINUE] — an unmapped maneuver must never
     * crash navigation; it is simply announced as "continue".
     */
    fun toManeuverType(maneuver: String?): ManeuverType = when (maneuver) {
        "DEPART" -> ManeuverType.START
        "KEEP_LEFT" -> ManeuverType.SLIGHT_LEFT
        "KEEP_RIGHT" -> ManeuverType.SLIGHT_RIGHT
        "STRAIGHT" -> ManeuverType.CONTINUE
        "TURN_SLIGHT_LEFT" -> ManeuverType.SLIGHT_LEFT
        "TURN_LEFT" -> ManeuverType.LEFT
        "TURN_SHARP_LEFT" -> ManeuverType.SHARP_LEFT
        "UTURN_LEFT", "UTURN_RIGHT" -> ManeuverType.U_TURN
        "TURN_SHARP_RIGHT" -> ManeuverType.SHARP_RIGHT
        "TURN_RIGHT" -> ManeuverType.RIGHT
        "TURN_SLIGHT_RIGHT" -> ManeuverType.SLIGHT_RIGHT
        "RAMP_LEFT" -> ManeuverType.SLIGHT_LEFT
        "RAMP_RIGHT" -> ManeuverType.SLIGHT_RIGHT
        "FORK_LEFT" -> ManeuverType.SLIGHT_LEFT
        "FORK_RIGHT" -> ManeuverType.SLIGHT_RIGHT
        "ROUNDABOUT_LEFT", "ROUNDABOUT_RIGHT" -> ManeuverType.ROUNDABOUT
        "FERRY" -> ManeuverType.CONTINUE
        else -> ManeuverType.CONTINUE
    }

    /**
     * Parses a protobuf `Duration` string ("3456s" / "3456.500s") into seconds.
     * Returns 0.0 for missing/malformed values — callers validate finiteness downstream.
     */
    fun toDurationSeconds(duration: String?): Double =
        duration?.removeSuffix("s")?.toDoubleOrNull() ?: 0.0

    /**
     * Decodes a Google-encoded polyline (precision 5, the format returned by
     * `polyline.encodedPolyline`). Returns an empty list for blank input; callers
     * require ≥2 points before a route may be reported.
     */
    fun decodePolyline(encoded: String?): List<Coordinate> {
        if (encoded.isNullOrBlank()) return emptyList()
        val points = ArrayList<Coordinate>()
        var index = 0
        var lat = 0
        var lng = 0
        while (index < encoded.length) {
            var result = 0
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lat += if ((result and 1) != 0) (result shr 1).inv() else result shr 1

            result = 0
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lng += if ((result and 1) != 0) (result shr 1).inv() else result shr 1

            points.add(Coordinate(lat / 1E5, lng / 1E5))
        }
        return points
    }

    /** Maps one response route into the domain [Route]. Overview geometry comes from the
     *  route-level polyline; step geometry from each step's own polyline when present. */
    fun toRoute(dto: GoogleRouteDto): Route {
        val legs = dto.legs.mapIndexed { legIndex, leg ->
            RouteLeg(
                distanceMeters = leg.distanceMeters?.toDouble() ?: 0.0,
                durationSeconds = toDurationSeconds(leg.duration),
                summary = null,
                steps = leg.steps.mapIndexed { stepIndex, step ->
                    val start = step.startLocation?.location
                    val maneuver = step.navigationInstruction?.maneuver
                        ?: step.staticInstruction?.maneuver
                    // The final step of a leg is the arrival step; Google omits
                    // navigationInstruction there, so the last step is always ARRIVE.
                    val type = if (stepIndex == leg.steps.lastIndex && maneuver.isNullOrBlank()) {
                        ManeuverType.ARRIVE
                    } else {
                        toManeuverType(maneuver)
                    }
                    val location = Coordinate(
                        latitude = start?.latitude ?: 0.0,
                        longitude = start?.longitude ?: 0.0,
                    )
                    RouteStep(
                        coordinate = location,
                        maneuver = Maneuver(type = type, location = location),
                        distanceMeters = step.distanceMeters?.toDouble() ?: 0.0,
                        durationSeconds = toDurationSeconds(step.duration),
                        roadName = step.staticInstruction?.instruction
                            ?.split(" on ")?.getOrNull(1)
                            ?.takeIf { it.isNotBlank() },
                        geometry = decodePolyline(step.polyline?.encodedPolyline),
                    )
                },
            )
        }
        return Route(
            distanceMeters = dto.distanceMeters?.toDouble() ?: 0.0,
            durationSeconds = toDurationSeconds(dto.duration),
            geometry = decodePolyline(dto.polyline?.encodedPolyline),
            legs = legs,
            summaryRoadName = legs.firstOrNull()?.steps?.firstOrNull()?.roadName,
        )
    }
}
