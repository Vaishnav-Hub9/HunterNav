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

        lastGeometryIndex = bestIndex

        val remainingDuration = if (steps.isNotEmpty() && stepEndIndices.isNotEmpty()) {
            val stepIdx = stepIndexForGeometryIndex(stepEndIndices, bestIndex, steps.size)
            var dur = 0.0
            for (k in stepIdx until steps.size) dur += steps[k].durationSeconds
            dur
        } else if (bestRemainingMeters > 0 && steps.isNotEmpty()) {
            // Proportional fallback when step indices are unavailable.
            val totalLen = approximateLength(geometry)
            val ratio = (bestRemainingMeters / totalLen).coerceIn(0.0, 1.0)
            steps.sumOf { it.durationSeconds } * ratio
        } else 0.0

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
        // First step whose end lies at/after the snapped vertex.
        for (i in stepEndIndices.indices) {
            if (geometryIndex <= stepEndIndices[i]) return i.coerceAtMost(stepCount - 1)
        }
        return stepCount - 1
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
    }

    private var lastGeometryIndex = 0
}
