package com.hunternav.domain.model

import com.hunternav.core.util.distanceMeters
import com.hunternav.core.util.distanceToSegmentMeters

/** A full navigation route in provider-independent form. */
data class Route(
    val distanceMeters: Double,
    val durationSeconds: Double,
    /** Ordered overview geometry. */
    val geometry: List<Coordinate>,
    val legs: List<RouteLeg>,
    /** Provider-reported label for the main road, when the first leg step has one. */
    val summaryRoadName: String? = null,
) {
    /** Quick nearest-vertex search used for bounding the off-route computation to a local window. */
    fun nearestGeometryIndex(coordinate: Coordinate): Int {
        var best = 0
        var bestDist = Double.MAX_VALUE
        for (i in geometry.indices) {
            val d = distanceMeters(coordinate.latitude, coordinate.longitude, geometry[i].latitude, geometry[i].longitude)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }
}

/** A leg of a route (OSRM returns one leg per waypoint pair). */
data class RouteLeg(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<RouteStep>,
    val summary: String? = null,
)

/** One turn-by-turn step of a route. */
data class RouteStep(
    val coordinate: Coordinate,
    val maneuver: Maneuver,
    /** Step length in meters (distance traveled until the NEXT maneuver). */
    val distanceMeters: Double,
    /** Step duration in seconds. */
    val durationSeconds: Double,
    val roadName: String?,
    /** Step-level geometry when the provider supplies it. */
    val geometry: List<Coordinate> = emptyList(),
)
