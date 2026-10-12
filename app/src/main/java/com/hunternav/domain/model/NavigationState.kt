package com.hunternav.domain.model

/** Camera modes for the navigation map. */
enum class CameraMode { FOLLOW, OVERVIEW, FREE }

/**
 * Coarse lifecycle of a navigation session, provider-independent. Produced by the display
 * pipeline adapters (Google Navigation SDK, legacy OSRM engine, simulator) into the single
 * [DisplayNavigationState] contract consumed by the handlebar display renderer.
 */
enum class NavPhase { IDLE, NAVIGATING, REROUTING, ARRIVED, ERROR }

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
    // ------------------------------------------------------------------
    // Display-pipeline extensions (Google Navigation SDK adapter path).
    // All default so existing producers stay source-compatible.
    // ------------------------------------------------------------------
    /** Coarse session phase for the external display (idle/navigating/rerouting/arrived/error). */
    val phase: NavPhase = NavPhase.IDLE,
    /** Road-snapped position when the provider offers one; the pointer should follow this. */
    val snapped: Coordinate? = null,
    /** False when heading is missing or unreliable — the renderer must not rotate the pointer. */
    val headingValid: Boolean = true,
    /** Monotonic route identity: +1 whenever new route geometry is installed. */
    val routeVersion: Long = 0L,
    /** Epoch millis of the underlying location/navigation update (staleness checks). */
    val timestampMs: Long = 0L,
    /** Absolute ETA in epoch millis when the provider can compute one; null otherwise. */
    val etaEpochMs: Long? = null,
    /** True when this state was produced by a simulator, never by live navigation. */
    val simulated: Boolean = false,
) {
    companion object {
        /** Neutral, idle snapshot used as the initial value of provider state flows. */
        fun idle(): DisplayNavigationState = DisplayNavigationState(
            latitude = 0.0,
            longitude = 0.0,
            bearing = 0.0,
            speedMps = 0.0,
            maneuver = null,
            distanceToManeuverMeters = -1.0,
            roadName = null,
            remainingDistanceMeters = -1.0,
            remainingDurationSeconds = -1.0,
            routeGeometry = emptyList(),
            offRoute = false,
            rerouting = false,
            destinationReached = false,
        )
    }
}

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
