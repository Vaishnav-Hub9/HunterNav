package com.hunternav.ui.map

/**
 * Dynamic navigation zoom policy. Zoom depends on maneuver context so the rider sees more road
 * when cruising and tighter detail when a turn approaches. Thresholds are configurable and
 * centralized here — never hardcoded in UI components.
 */
data class NavigationZoomPolicy(
    /** Zoom when far from the next maneuver. */
    val cruiseZoom: Double = 15.2,
    /** Zoom when a maneuver is approaching. */
    val approachZoom: Double = 16.4,
    /** Zoom immediately before the maneuver. */
    val imminentZoom: Double = 17.2,
    /** Distance (m) at which "approaching" begins. */
    val approachThresholdMeters: Double = 400.0,
    /** Distance (m) at which "imminent" begins. */
    val imminentThresholdMeters: Double = 120.0,
) {
    fun zoomFor(distanceToManeuverMeters: Double): Double = when {
        distanceToManeuverMeters <= imminentThresholdMeters -> imminentZoom
        distanceToManeuverMeters <= approachThresholdMeters -> approachZoom
        else -> cruiseZoom
    }
}

/**
 * Bridges the navigation engine state to map camera updates: decides zoom via
 * [NavigationZoomPolicy] and delegates mode-aware camera movement to [MapController].
 */
class NavigationCameraController(
    private val mapController: MapController,
    var zoomPolicy: NavigationZoomPolicy = NavigationZoomPolicy(),
) {

    fun onNavigationTick(
        location: com.hunternav.domain.model.LocationData,
        bearing: Double,
        distanceToManeuverMeters: Double,
    ) {
        val zoom = zoomPolicy.zoomFor(distanceToManeuverMeters)
        mapController.follow(location, bearing, zoom)
    }

    fun overview(route: com.hunternav.domain.model.Route?) {
        mapController.setCameraMode(com.hunternav.domain.model.CameraMode.OVERVIEW)
        mapController.fitRoute(route)
    }

    fun followMode() = mapController.setCameraMode(com.hunternav.domain.model.CameraMode.FOLLOW)

    fun freeMode() = mapController.setCameraMode(com.hunternav.domain.model.CameraMode.FREE)
}
