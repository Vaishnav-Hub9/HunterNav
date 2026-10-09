package com.hunternav.domain.navigation

import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.LocationData
import com.hunternav.domain.model.Route
import com.hunternav.domain.model.RouteStep
import com.hunternav.core.util.distanceMeters
import com.hunternav.core.util.distanceToSegmentMeters
import kotlin.math.abs

/**
 * Computes progress along a route geometry: snapped position, remaining distance/duration,
 * and distance off the route. Pure function of (route, location) — fully unit testable.
 */
class RouteProgressTracker {

    /**
     * @param geometry route overview geometry (non-empty).
     * @param location current smoothed location.
     * @param stepEndIndices index in [geometry] where each step of the route ends, aligned with
     *        [steps]; used to map a snapped vertex to the current navigation step. May be empty
     *        when step geometry is unavailable, in which case progress falls back to distances.
     * @param steps full ordered step list; used for the step-boundary fallback.
     * @return snapped coordinate, snapped geometry index, remaining distance (m) and remaining
     *         duration (s) along the geometry.
     */
    fun snap(
        geometry: List<Coordinate>,
        location: LocationData,
        stepEndIndices: List<Int> = emptyList(),
        steps: List<RouteStep> = emptyList(),
        totalDurationSeconds: Double? = null,
    ): SnapResult {
        require(geometry.isNotEmpty()) { "Route geometry must not be empty" }

        // Local window around the previous snap keeps the O(n) scan small on long routes.
        val searchStart = maxOf(0, lastGeometryIndex - SEARCH_WINDOW)
        val searchEnd = minOf(geometry.size - 1, lastGeometryIndex + SEARCH_WINDOW)
        val candidates = if (searchEnd - searchStart < SEARCH_WINDOW) 0 until geometry.size else searchStart..searchEnd

        var bestIndex = searchStart
        var bestDistance = Double.MAX_VALUE
        var bestSnapLat = geometry[bestIndex].latitude
        var bestSnapLon = geometry[bestIndex].longitude
        var bestRemainingMeters = 0.0

        // Walk candidate segments (i, i+1), find the closest point on each.
        var i = candidates.first
        while (i < candidates.last) {
            val a = geometry[i]
            val b = geometry[i + 1]
            val (dist, snap) = distanceToSegmentMeters(
                location.latitude, location.longitude,
                a.latitude, a.longitude,
                b.latitude, b.longitude,
            )
            if (dist < bestDistance) {
                bestDistance = dist
                bestSnapLat = snap.first
                bestSnapLon = snap.second
                bestIndex = i
                // Remaining distance = distance to end of this segment + rest of the geometry.
                var remaining = distanceMeters(snap.first, snap.second, b.latitude, b.longitude)
                var j = i + 1
                while (j < geometry.size - 1) {
                    remaining += distanceMeters(
                        geometry[j].latitude, geometry[j].longitude,
                        geometry[j + 1].latitude, geometry[j + 1].longitude,
                    )
                    j++
                }
                bestRemainingMeters = remaining
            }
            i++
        }

        // Also consider snapping exactly onto the last vertex.
        val lastCoord = geometry[geometry.size - 1]
        val distToLast = distanceMeters(location.latitude, location.longitude, lastCoord.latitude, lastCoord.longitude)
        if (distToLast < bestDistance) {
            bestDistance = distToLast
            bestIndex = geometry.size - 1
            bestSnapLat = lastCoord.latitude
            bestSnapLon = lastCoord.longitude
            bestRemainingMeters = 0.0
        }

        // Exact-vertex tie: a projection landing on the END of segment i means the rider sits
        // ON vertex i+1 — index it as such (segment loop keeps the earlier segment on equal
        // distance), otherwise step advancement and distance-to-maneuver lag by one vertex.
        if (bestIndex + 1 < geometry.size) {
            val next = geometry[bestIndex + 1]
            if (distanceMeters(bestSnapLat, bestSnapLon, next.latitude, next.longitude) < VERTEX_TIE_EPSILON_METERS) {
                bestIndex += 1
            }
        }

        lastGeometryIndex = bestIndex

        // Remaining duration model (documented assumption): the current step's duration is
        // scaled by the fraction of that step still to travel, plus the full duration of every
        // later step — so duration decreases continuously with progress, matches OSRM's
        // per-step speeds, and is recomputed from scratch after a reroute. When step boundaries
        // are unavailable we fall back to a uniform-speed proportional estimate over the route.
        val remainingDuration = if (steps.isNotEmpty()) {
            val totalDuration = steps.sumOf { it.durationSeconds }
            if (stepEndIndices.isNotEmpty()) {
                // Step i spans [boundary(i), boundary(i+1)); the rider's current step is the
                // last one already started. Boundary = START vertex of that step.
                val stepIdx = stepIndexForGeometryIndex(stepEndIndices, bestIndex, steps.size)
                val endVertex = stepEndIndices.getOrElse(stepIdx + 1) { geometry.size - 1 }
                    .coerceIn(bestIndex, geometry.size - 1)
                val startVertex = stepEndIndices.getOrElse(stepIdx) { 0 }.coerceAtLeast(0).coerceIn(0, endVertex)
                val stepLength = forwardDistance(geometry, startVertex, endVertex).coerceAtLeast(1.0)
                val leftInStep = forwardDistance(geometry, bestIndex, endVertex)
                val fractionLeft = (leftInStep / stepLength).coerceIn(0.0, 1.0)
                var dur = steps[stepIdx].durationSeconds * fractionLeft
                for (k in stepIdx + 1 until steps.size) dur += steps[k].durationSeconds
                dur.coerceIn(0.0, totalDuration)
            } else {
                // Uniform average speed along the route.
                val ratio = (bestRemainingMeters / approximateLength(geometry)).coerceIn(0.0, 1.0)
                totalDuration * ratio
            }
        } else {
            // No step data: fall back to the route's own duration at uniform speed.
            val ratio = (bestRemainingMeters / approximateLength(geometry)).coerceIn(0.0, 1.0)
            (totalDurationSeconds ?: 0.0) * ratio
        }

        return SnapResult(
            snapped = Coordinate(bestSnapLat, bestSnapLon),
            geometryIndex = bestIndex,
            offRouteDistanceMeters = bestDistance,
            remainingDistanceMeters = bestRemainingMeters,
            remainingDurationSeconds = remainingDuration,
        )
    }

    /** Distance from a coordinate to the nearest segment of the geometry (meters). */
    fun distanceFromRouteMeters(geometry: List<Coordinate>, coordinate: Coordinate): Double {
        if (geometry.size < 2) {
            return if (geometry.isEmpty()) Double.MAX_VALUE
            else distanceMeters(coordinate.latitude, coordinate.longitude, geometry[0].latitude, geometry[0].longitude)
        }
        val searchStart = maxOf(0, lastGeometryIndex - SEARCH_WINDOW)
        val searchEnd = minOf(geometry.size - 2, lastGeometryIndex + SEARCH_WINDOW)
        val range = if (searchStart <= searchEnd) searchStart..searchEnd else 0 until geometry.size - 1

        var best = Double.MAX_VALUE
        for (i in range) {
            val a = geometry[i]
            val b = geometry[i + 1]
            val (dist, _) = distanceToSegmentMeters(
                coordinate.latitude, coordinate.longitude,
                a.latitude, a.longitude,
                b.latitude, b.longitude,
            )
            if (dist < best) best = dist
        }
        return best
    }

    fun reset() {
        lastGeometryIndex = 0
    }

    private fun stepIndexForGeometryIndex(stepEndIndices: List<Int>, geometryIndex: Int, stepCount: Int): Int {
        // Current step = the last step whose start (boundary) is at/before the snapped vertex;
        // mirrors NavigationEngine.stepIndexForGeometryIndex.
        var current = 0
        for (i in stepEndIndices.indices) {
            if (stepEndIndices[i] <= geometryIndex) current = i
        }
        return current.coerceAtMost((stepCount - 1).coerceAtLeast(0))
    }

    private fun forwardDistance(geometry: List<Coordinate>, fromIndex: Int, toIndex: Int): Double {
        var sum = 0.0
        var i = fromIndex.coerceAtLeast(0)
        val end = toIndex.coerceAtMost(geometry.size - 1)
        while (i < end) {
            sum += distanceMeters(
                geometry[i].latitude, geometry[i].longitude,
                geometry[i + 1].latitude, geometry[i + 1].longitude,
            )
            i++
        }
        return sum
    }

    private fun approximateLength(geometry: List<Coordinate>): Double {
        var len = 0.0
        for (i in 0 until geometry.size - 1) {
            len += distanceMeters(
                geometry[i].latitude, geometry[i].longitude,
                geometry[i + 1].latitude, geometry[i + 1].longitude,
            )
        }
        return len.coerceAtLeast(1.0)
    }

    data class SnapResult(
        val snapped: Coordinate,
        val geometryIndex: Int,
        val offRouteDistanceMeters: Double,
        val remainingDistanceMeters: Double,
        val remainingDurationSeconds: Double,
    )

    companion object {
        /** Local search window around the previous snap (vertices). */
        private const val SEARCH_WINDOW = 60

        /** Below this distance a snapped point counts as sitting on the next vertex. */
        private const val VERTEX_TIE_EPSILON_METERS = 0.01
    }

    private var lastGeometryIndex = 0
}
