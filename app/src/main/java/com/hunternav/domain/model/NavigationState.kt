package com.hunternav.domain.model

/** Camera modes for the navigation map. */
enum class CameraMode { FOLLOW, OVERVIEW, FREE }

/**
 * Rendering-ready navigation snapshot for map/UI layers and, in the future, for external
 * displays over Bluetooth (see docs/future-hardware.md). This is the contract the ESP32
 * display layer will consume; it deliberately contains no Android types.
 */
data class DisplayNavigationState(
    val latitude: Double,
    val longitude: Double,
    val bearing: Double,
    val speedMps: Double,
    val maneuver: ManeuverType?,
    val distanceToManeuverMeters: Double,
    val roadName: String?,
    val remainingDistanceMeters: Double,
    val remainingDurationSeconds: Double,
    val routeGeometry: List<Coordinate>,
    val offRoute: Boolean,
    val rerouting: Boolean,
    val destinationReached: Boolean,
)

/** Aggregated, UI-ready navigation state. */
data class NavigationState(
    val currentLocation: LocationData?,
    val currentBearing: Double,
    val currentSpeed: Double,
    val activeRoute: Route?,
    val currentStep: RouteStep?,
    val nextStep: RouteStep?,
    val distanceToNextManeuver: Double,
    val remainingDistance: Double,
    val remainingDuration: Double,
    val offRoute: Boolean,
    val rerouting: Boolean,
    val destinationReached: Boolean,
    val snappedCoordinate: Coordinate? = null,
    /** Index into [Route.geometry] of the user's snapped position; -1 when unknown. */
    val snappedGeometryIndex: Int = -1,
    /**
     * Monotonic identity of the active route: +1 every time a route is installed
     * (navigation start or reroute replacement). Lets tests and observers detect route
     * replacement instead of assuming the route object they saw is still active.
     */
    val routeVersion: Long = 0L,
) {
    fun toDisplayState(): DisplayNavigationState = DisplayNavigationState(
        latitude = currentLocation?.latitude ?: 0.0,
        longitude = currentLocation?.longitude ?: 0.0,
        bearing = currentBearing,
        speedMps = currentSpeed,
        maneuver = nextStep?.maneuver?.type,
        distanceToManeuverMeters = distanceToNextManeuver,
        roadName = currentStep?.roadName,
        remainingDistanceMeters = remainingDistance,
        remainingDurationSeconds = remainingDuration,
        routeGeometry = activeRoute?.geometry ?: emptyList(),
        offRoute = offRoute,
        rerouting = rerouting,
        destinationReached = destinationReached,
    )

    companion object {
        fun idle(): NavigationState = NavigationState(
            currentLocation = null,
            currentBearing = 0.0,
            currentSpeed = 0.0,
            activeRoute = null,
            currentStep = null,
            nextStep = null,
            distanceToNextManeuver = 0.0,
            remainingDistance = 0.0,
            remainingDuration = 0.0,
            offRoute = false,
            rerouting = false,
            destinationReached = false,
        )
    }
}
