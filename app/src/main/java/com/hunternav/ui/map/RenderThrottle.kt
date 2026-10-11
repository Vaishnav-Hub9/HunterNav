package com.hunternav.ui.map

import com.hunternav.domain.model.Coordinate
import kotlin.math.abs

/**
 * Pure change-detection rules for map publishes (spec P1: fix severe map lag).
 *
 * Every `GeoJsonSource.setGeoJson` call re-serializes geometry and makes MapLibre re-tessellate
 * the layer on the main thread. The navigation tick loop runs every 500 ms and location fixes
 * arrive at 1–2 Hz; publishing identical content on each tick was wasted main-thread work and a
 * dominant source of stutter on mid-range devices. These rules decide whether a publish is
 * actually necessary. Pure Kotlin — unit-testable without MapLibre.
 */
class RenderThrottle {

    private var lastUserLat = Double.NaN
    private var lastUserLon = Double.NaN
    private var lastRouteIdentity: Any? = null
    private var lastProgressIdentity: Any? = null
    private var lastSnappedIndex = Int.MIN_VALUE

    /** True when the marker should be re-published (moved at least [epsilonDegrees]). */
    fun shouldPublishUserLocation(latitude: Double, longitude: Double, epsilonDegrees: Double = POSITION_EPSILON_DEGREES): Boolean {
        val changed = lastUserLat.isNaN() ||
            abs(latitude - lastUserLat) > epsilonDegrees ||
            abs(longitude - lastUserLon) > epsilonDegrees
        if (changed) {
            lastUserLat = latitude
            lastUserLon = longitude
        }
        return changed
    }

    /** True when the full route line should be re-published (different route instance). */
    fun shouldPublishRoute(route: Any?): Boolean {
        if (route === lastRouteIdentity) return false
        lastRouteIdentity = route
        // A new route invalidates the progress key: the next progress call must publish.
        lastProgressIdentity = null
        lastSnappedIndex = Int.MIN_VALUE
        return true
    }

    /** True when remaining/traveled lines should be re-published (new route or moved vertex). */
    fun shouldPublishProgress(route: Any?, snappedIndex: Int): Boolean {
        if (route === lastProgressIdentity && snappedIndex == lastSnappedIndex) return false
        lastProgressIdentity = route
        lastSnappedIndex = snappedIndex
        return true
    }

    /** Clears all keys — call after a style (re)load so content is re-applied to new sources. */
    fun reset() {
        lastUserLat = Double.NaN
        lastUserLon = Double.NaN
        lastRouteIdentity = null
        lastProgressIdentity = null
        lastSnappedIndex = Int.MIN_VALUE
    }

    companion object {
        /** ~11 cm at the equator; below any real GPS movement between fixes. */
        const val POSITION_EPSILON_DEGREES = 1e-6

        /** Camera/marker tick: skip updates while the rider has barely moved. */
        const val CAMERA_MIN_MOVEMENT_METERS = 2.0

        /** Camera/marker tick: skip updates while the heading is nearly unchanged. */
        const val CAMERA_MIN_BEARING_DELTA_DEGREES = 6.0

        /** A [Coordinate] counts as moved when it is farther than [CAMERA_MIN_MOVEMENT_METERS]. */
        fun movedEnough(last: Coordinate?, current: Coordinate): Boolean =
            last == null || last.distanceTo(current) >= CAMERA_MIN_MOVEMENT_METERS
    }
}
