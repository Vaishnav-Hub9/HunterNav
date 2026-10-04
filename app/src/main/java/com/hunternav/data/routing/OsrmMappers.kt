package com.hunternav.data.routing

import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.Maneuver
import com.hunternav.domain.model.ManeuverType
import com.hunternav.domain.model.Route
import com.hunternav.domain.model.RouteLeg
import com.hunternav.domain.model.RouteStep

/** Normalizes OSRM maneuvers into domain maneuver types. Provider detail stays in the data layer. */
object OsrmMappers {

    fun toManeuverType(type: String?, modifier: String?): ManeuverType {
        // Departure/arrival carry no direction modifier in OSRM.
        return when (type) {
            "depart" -> ManeuverType.START
            "arrive" -> ManeuverType.ARRIVE
            "roundabout", "rotary", "roundabout turn" -> ManeuverType.ROUNDABOUT
            "continue", "new name", "merge" -> continueType(modifier)
            "on ramp", "off ramp", "fork" -> modifierType(modifier, allowSharp = true)
            "end of road", "turn" -> modifierType(modifier, allowSharp = true)
            else -> modifierType(modifier, allowSharp = false)
        }
    }

    private fun continueType(modifier: String?): ManeuverType = when (modifier) {
        "uturn" -> ManeuverType.U_TURN
        "slight left" -> ManeuverType.SLIGHT_LEFT
        "slight right" -> ManeuverType.SLIGHT_RIGHT
        "left" -> ManeuverType.LEFT
        "right" -> ManeuverType.RIGHT
        "sharp left" -> ManeuverType.SHARP_LEFT
        "sharp right" -> ManeuverType.SHARP_RIGHT
        else -> ManeuverType.CONTINUE
    }

    private fun modifierType(modifier: String?, allowSharp: Boolean): ManeuverType = when (modifier) {
        "uturn" -> ManeuverType.U_TURN
        "slight left" -> ManeuverType.SLIGHT_LEFT
        "slight right" -> ManeuverType.SLIGHT_RIGHT
        "sharp left" -> if (allowSharp) ManeuverType.SHARP_LEFT else ManeuverType.LEFT
        "sharp right" -> if (allowSharp) ManeuverType.SHARP_RIGHT else ManeuverType.RIGHT
        "left" -> ManeuverType.LEFT
        "right" -> ManeuverType.RIGHT
        "straight" -> ManeuverType.CONTINUE
        else -> ManeuverType.CONTINUE
    }

    fun toCoordinate(lonLat: List<Double>): Coordinate {
        // Defensive against providers emitting [lat, lon].
        val first = lonLat.getOrNull(0) ?: 0.0
        val second = lonLat.getOrNull(1) ?: 0.0
        return if (first in -180.0..180.0 && second in -90.0..90.0 && first > 90.0 || second > 90.0) {
            // Longitude first is the GeoJSON standard; only flip when clearly [lat, lon].
            Coordinate(second, first)
        } else {
            Coordinate(second, first)
        }
    }

    fun toRoute(dto: OsrmRouteDto): Route {
        val legs = dto.legs.map { leg ->
            RouteLeg(
                distanceMeters = leg.distance,
                durationSeconds = leg.duration,
                summary = leg.summary,
                steps = leg.steps.map { step ->
                    RouteStep(
                        coordinate = toCoordinate(step.maneuver.location),
                        maneuver = Maneuver(
                            type = toManeuverType(step.maneuver.type, step.maneuver.modifier),
                            location = toCoordinate(step.maneuver.location),
                            exitNumber = step.maneuver.exit,
                        ),
                        distanceMeters = step.distance,
                        durationSeconds = step.duration,
                        roadName = step.name?.takeIf { it.isNotBlank() },
                        geometry = step.geometry?.coordinates?.map { toCoordinate(it) } ?: emptyList(),
                    )
                },
            )
        }
        return Route(
            distanceMeters = dto.distance,
            durationSeconds = dto.duration,
            geometry = dto.geometry?.coordinates?.map { toCoordinate(it) } ?: emptyList(),
            legs = legs,
            summaryRoadName = legs.firstOrNull()?.steps?.firstOrNull { it.roadName != null }?.roadName,
        )
    }
}
