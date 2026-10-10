package com.hunternav.domain.navigation

import com.hunternav.core.result.AppErrorKind
import com.hunternav.core.util.DebugLog
import com.hunternav.core.util.angleDelta
import com.hunternav.core.util.distanceMeters
import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.LocationData
import com.hunternav.domain.model.NavigationState
import com.hunternav.domain.model.Route
import com.hunternav.domain.repository.NavigationOutput
import com.hunternav.domain.repository.RoutingProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Tunable navigation thresholds. All values have navigation-sensible defaults. */
data class NavigationEngineConfig(
    /** Deviation beyond this distance (m) starts an off-route candidate. */
    val offRouteEnterMeters: Double = 40.0,
    /** Deviation below this distance (m) clears the off-route latch (hysteresis). */
    val offRouteExitMeters: Double = 25.0,
    /** Arrival radius (m). */
    val arrivalRadiusMeters: Double = 20.0,
    /** Fixes with accuracy worse than this (m) do not trigger reroute evaluation. */
    val maxAccuracyMeters: Float = 50f,
    /** Deviation must persist at least this long (ms) before a reroute is requested. */
    val offRouteSustainMs: Long = 4_000L,
    /** Minimum movement (m) while deviating before a reroute is requested (guards GPS drift). */
    val minDeviationMovementMeters: Double = 30.0,
    /** Minimum interval (ms) between reroute requests. */
    val rerouteCooldownMs: Long = 15_000L,
    /**
     * Remaining distance is clamped against small increases: GPS noise can snap the rider a
     * few meters backward, which would make "remaining" jump up. Increases below this
     * tolerance are ignored; a genuine backtrack (larger than this) is accepted.
     */
    val remainingJitterToleranceMeters: Double = 40.0,
    /** Same noise clamp for the remaining-duration estimate (spec §9). */
    val durationJitterToleranceSeconds: Double = 30.0,
)

/** Domain-level navigation events surfaced to the UI (never raw exceptions). */
sealed class NavigationEvent {
    data class RerouteFailed(val kind: AppErrorKind) : NavigationEvent()
    data class RouteRequestFailed(val kind: AppErrorKind) : NavigationEvent()
    data object ArrivedAtDestination : NavigationEvent()
}

/**
 * Provider-independent navigation brain.
 *
 * Responsibilities (see docs/navigation-engine.md):
 *  - consume smoothed location updates,
 *  - snap the rider to the active route and compute progress,
 *  - determine current/next step and distance to the next maneuver,
 *  - detect off-route deviation with hysteresis + sustained-deviation rules,
 *  - request reroutes through the [RoutingProvider] abstraction (one at a time, with cooldown,
 *    stale-request cancellation),
 *  - detect arrival,
 *  - publish [NavigationState] and mirror it to [NavigationOutput] sinks (future ESP32 display).
 *
 * The engine is UI-free and Android-free: every input is a domain model.
 */
class NavigationEngine(
    private val routingProvider: RoutingProvider,
    private val config: NavigationEngineConfig = NavigationEngineConfig(),
    private val outputs: List<NavigationOutput> = emptyList(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val _state = MutableStateFlow(NavigationState.idle())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<NavigationEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<NavigationEvent> = _events.asSharedFlow()

    private val tracker = RouteProgressTracker()

    private var destination: Coordinate? = null

    /**
     * Selected destination for the current trip (arrival target). Set once per trip via
     * [startNavigation]'s `arrivalDestination` and preserved across reroutes so arrival is
     * always checked against what the user actually chose — not merely a route's geometry end.
     */
    private var tripDestination: Coordinate? = null
    private var geometrySuffixMeters: DoubleArray = DoubleArray(0)
    private var stepEndIndices: List<Int> = emptyList()

    // Off-route hysteresis state.
    private var offRouteLatched = false
    private var offRouteCandidateSinceMs: Long? = null
    private var offRouteCandidateOrigin: LocationData? = null

    // Arrival latches: once arrived, stay arrived until navigation restarts.
    private var arrivalLatched = false

    // Reroute state.
    private var lastRerouteRequestMs: Long? = null
    private var activeRerouteJob: Job? = null

    // Previous remaining metrics for the ACTIVE route (GPS-noise jitter clamp, spec §9).
    private var lastRemainingDistance: Double? = null
    private var lastRemainingDuration: Double? = null

    /** Begins navigating an already-fetched route. */
    @Synchronized
    fun startNavigation(route: Route, initialLocation: LocationData?, arrivalDestination: Coordinate? = null) {
        activeRerouteJob?.cancel()
        activeRerouteJob = null
        offRouteLatched = false
        offRouteCandidateSinceMs = null
        offRouteCandidateOrigin = null
        arrivalLatched = false

        if (arrivalDestination != null) tripDestination = arrivalDestination
        destination = tripDestination ?: route.geometry.lastOrNull()
        tracker.reset()
        lastRemainingDistance = null
        lastRemainingDuration = null
        geometrySuffixMeters = buildSuffixDistances(route.geometry)
        stepEndIndices = buildStepEndIndices(route)

        _state.value = NavigationState.idle().copy(
            activeRoute = route,
            routeVersion = _state.value.routeVersion + 1,
        )
        DebugLog.d(
            "NAVIGATION_STARTED",
            "route_v=${_state.value.routeVersion} distance_m=${route.distanceMeters} duration_s=${route.durationSeconds} " +
                "geometry_points=${route.geometry.size} steps=${stepEndIndices.size}",
        )
        if (initialLocation != null) onLocationUpdate(initialLocation)
    }

    /** Stops navigation and clears all route state (final route can remain visible via UI layer). */
    @Synchronized
    fun stopNavigation(keepRouteForDisplay: Boolean = false) {
        activeRerouteJob?.cancel()
        activeRerouteJob = null
        offRouteLatched = false
        offRouteCandidateSinceMs = null
        offRouteCandidateOrigin = null
        arrivalLatched = false
        destination = null
        tripDestination = null
        lastRerouteRequestMs = null
        lastRemainingDistance = null
        lastRemainingDuration = null
        val previousRoute = _state.value.activeRoute
        _state.value = NavigationState.idle().copy(
            currentLocation = _state.value.currentLocation,
            activeRoute = if (keepRouteForDisplay) previousRoute else null,
        )
    }

    /**
     * Main input: one smoothed location fix. Updates position, progress, step information,
     * off-route/arrival state; may trigger a reroute. Safe to call from any thread; reroute
     * results are applied under the same lock so route replacement is atomic w.r.t. fixes.
     */
    @Synchronized
    fun onLocationUpdate(location: LocationData) {
        val current = _state.value
        val route = current.activeRoute

        val bearing = smoothedBearing(current.currentLocation, current.currentBearing, location)
        val speed = if (location.speed > 0f) location.speed.toDouble() else current.currentSpeed

        if (route == null || route.geometry.size < 2) {
            _state.value = current.copy(
                currentLocation = location,
                currentBearing = bearing,
                currentSpeed = speed,
            )
            emitToOutputs()
            return
        }

        val snap = tracker.snap(
            route.geometry,
            location,
            stepEndIndices,
            allSteps(route),
            totalDurationSeconds = route.durationSeconds,
        )
        val stepIdx = stepIndexForGeometryIndex(snap.geometryIndex)
        val steps = allSteps(route)
        val currentStep = steps.getOrNull(stepIdx)
        val nextStep = steps.getOrNull(stepIdx + 1)

        // The next maneuver sits at the START of the following step (step boundaries are
        // maneuver/start vertices); for the final step it is the destination itself.
        val maneuverVertex = when {
            stepIdx + 1 < stepEndIndices.size -> stepEndIndices[stepIdx + 1]
            steps.isNotEmpty() -> route.geometry.lastIndex
            else -> -1
        }
        // Along-path distance from the actual snapped POINT to the maneuver vertex: the snap
        // point can be mid-segment, so counting from the segment's start vertex would overstate
        // the distance by up to one segment.
        val distanceToManeuver = if (maneuverVertex in geometrySuffixMeters.indices && maneuverVertex >= snap.geometryIndex) {
            val nextVertex = (snap.geometryIndex + 1).coerceAtMost(route.geometry.lastIndex)
            val toNextVertex = distanceMeters(
                snap.snapped.latitude, snap.snapped.longitude,
                route.geometry[nextVertex].latitude, route.geometry[nextVertex].longitude,
            )
            (toNextVertex + geometrySuffixMeters[nextVertex] - geometrySuffixMeters[maneuverVertex])
                .coerceAtLeast(0.0)
        } else 0.0

        val arrival = evaluateArrival(location, route)
        val offRouteNow = evaluateOffRoute(location, snap.offRouteDistanceMeters, arrival)

        // Spec §9: small backward GPS snaps must not make "remaining" jump upward — only a
        // genuine return to an earlier portion of the route (increase beyond the tolerance)
        // is allowed to show a larger value.
        val remainingDistance = clampJitter(
            snap.remainingDistanceMeters, lastRemainingDistance, config.remainingJitterToleranceMeters,
        )
        val remainingDuration = clampJitter(
            snap.remainingDurationSeconds, lastRemainingDuration, config.durationJitterToleranceSeconds,
        )
        lastRemainingDistance = remainingDistance
        lastRemainingDuration = remainingDuration

        _state.value = current.copy(
            currentLocation = location,
            currentBearing = bearing,
            currentSpeed = speed,
            currentStep = currentStep,
            nextStep = nextStep,
            distanceToNextManeuver = distanceToManeuver,
            remainingDistance = remainingDistance,
            remainingDuration = remainingDuration,
            offRoute = offRouteNow,
            destinationReached = arrival,
            snappedCoordinate = snap.snapped,
            snappedGeometryIndex = snap.geometryIndex,
        )

        if (arrival) {
            if (!current.destinationReached) _events.tryEmit(NavigationEvent.ArrivedAtDestination)
        } else if (offRouteNow) {
            maybeTriggerReroute(location)
        }
        emitToOutputs()
    }

    /** Called when the user picks a new route from the preview screen alternatives. */
    fun replaceRoute(route: Route, initialLocation: LocationData?) = startNavigation(route, initialLocation)

    private fun evaluateArrival(location: LocationData, route: Route): Boolean {
        if (arrivalLatched) return true
        val dest = destination ?: return false
        val radius = config.arrivalRadiusMeters + location.accuracy.coerceAtMost(30f).toDouble()
        val distance = distanceMeters(location.latitude, location.longitude, dest.latitude, dest.longitude)
        // Primary target: the destination the user selected (spec: arrival is checked against
        // the actual selected destination). Secondary: the active route's own endpoint — OSRM
        // snaps the destination onto the routable road network (real captures show 8–47 m
        // offsets), and reaching that endpoint completes the trip this route was built for.
        val reachedDestination = distance <= radius
        val reachedRouteEnd = route.geometry.lastOrNull()?.let {
            distanceMeters(location.latitude, location.longitude, it.latitude, it.longitude) <= radius
        } ?: false
        val arrived = reachedDestination || reachedRouteEnd
        if (arrived && !arrivalLatched) {
            arrivalLatched = true
            DebugLog.d(
                "ARRIVAL",
                "distance_m=%.1f radius_m=%.1f accuracy_m=%.1f"
                    .format(distance, config.arrivalRadiusMeters, location.accuracy),
            )
        }
        return arrived
    }

    private fun evaluateOffRoute(location: LocationData, deviationMeters: Double, arrived: Boolean): Boolean {
        if (arrived) {
            offRouteLatched = false
            offRouteCandidateSinceMs = null
            offRouteCandidateOrigin = null
            return false
        }

        val accuracyUsable = location.accuracy <= config.maxAccuracyMeters
        val now = nowMs()

        if (offRouteLatched) {
            if (deviationMeters < config.offRouteExitMeters) {
                // Back on the (old) route; hysteresis exit.
                offRouteLatched = false
                offRouteCandidateSinceMs = null
                offRouteCandidateOrigin = null
            }
            return offRouteLatched
        }

        if (deviationMeters > config.offRouteEnterMeters) {
            val since = offRouteCandidateSinceMs
            if (since == null) {
                offRouteCandidateSinceMs = now
                offRouteCandidateOrigin = location
            } else {
                val sustained = now - since >= config.offRouteSustainMs
                val moved = offRouteCandidateOrigin?.let {
                    distanceMeters(it.latitude, it.longitude, location.latitude, location.longitude)
                } ?: 0.0
                val movedMeaningfully = moved >= config.minDeviationMovementMeters || deviationMeters > config.offRouteEnterMeters * 2
                if (sustained && movedMeaningfully && accuracyUsable) {
                    offRouteLatched = true
                    offRouteCandidateSinceMs = null
                    offRouteCandidateOrigin = null
                    DebugLog.d(
                        "OFF_ROUTE",
                        "deviation_m=%.1f sustained_ms=%d accuracy_m=%.1f moved_m=%.1f"
                            .format(deviationMeters, now - since, location.accuracy, moved),
                    )
                }
            }
        } else {
            offRouteCandidateSinceMs = null
            offRouteCandidateOrigin = null
        }
        return offRouteLatched
    }

    private fun maybeTriggerReroute(location: LocationData) {
        val dest = destination ?: return
        val now = nowMs()
        val last = lastRerouteRequestMs
        if (last != null && now - last < config.rerouteCooldownMs) return
        if (activeRerouteJob?.isActive == true) return // never two concurrent reroutes

        lastRerouteRequestMs = now
        _state.value = _state.value.copy(rerouting = true)
        DebugLog.d(
            "REROUTING_STARTED",
            "from=${location.latitude},${location.longitude} dest=${dest.latitude},${dest.longitude}",
        )
        activeRerouteJob = scope.launch {
            val result = routingProvider.getRoutes(location.coordinate, dest, alternatives = false)
            // Apply the result atomically with respect to incoming location fixes.
            synchronized(this@NavigationEngine) {
                val stillActive = _state.value.activeRoute != null
                if (stillActive) {
                    _state.value = _state.value.copy(rerouting = false)
                    when (result) {
                        is com.hunternav.core.result.AppResult.Success -> {
                            val newRoute = result.value.firstOrNull()
                            if (newRoute != null) {
                                DebugLog.d(
                                    "REROUTING_SUCCESS",
                                    "distance_m=${newRoute.distanceMeters} duration_s=${newRoute.durationSeconds}",
                                )
                                startNavigation(newRoute, location)
                            } else {
                                DebugLog.d("REROUTING_FAILURE", "kind=NO_ROUTE message=no route in response")
                                _events.tryEmit(NavigationEvent.RerouteFailed(AppErrorKind.NO_ROUTE))
                            }
                        }
                        is com.hunternav.core.result.AppResult.Failure -> {
                            // Keep the old route and off-route state; the UI shows a readable message.
                            DebugLog.d("REROUTING_FAILURE", "kind=${result.kind} message=${result.message}")
                            _events.tryEmit(NavigationEvent.RerouteFailed(result.kind))
                        }
                    }
                }
            }
        }
    }

    private fun smoothedBearing(
        previous: LocationData?,
        previousBearing: Double,
        location: LocationData,
    ): Double {
        val gpsBearing = location.bearing.toDouble()
        val hasGpsBearing = gpsBearing != 0.0 && location.speed >= MIN_BEARING_SPEED_MPS
        val movementBearing = previous?.let { prev ->
            val moved = distanceMeters(prev.latitude, prev.longitude, location.latitude, location.longitude)
            if (moved > MIN_MOVEMENT_FOR_BEARING_METERS) {
                prev.coordinate.bearingTo(location.coordinate)
            } else null
        }
        val raw = when {
            hasGpsBearing -> gpsBearing
            movementBearing != null -> movementBearing
            else -> return previousBearing
        }
        // Exponential smoothing limits camera/arrow jitter.
        val delta = angleDelta(previousBearing, raw)
        return (previousBearing + delta * BEARING_SMOOTHING).let { (it + 360.0) % 360.0 }
    }

    private fun emitToOutputs() {
        val display = _state.value.toDisplayState()
        for (output in outputs) {
            runCatching { output.onNavigationStateChanged(display) }
        }
    }

    /** Keeps [previous] when [next] grew by less than [tolerance] (GPS-noise backtrack). */
    private fun clampJitter(next: Double, previous: Double?, tolerance: Double): Double =
        if (previous != null && next > previous && next - previous < tolerance) previous else next

    private fun allSteps(route: Route): List<com.hunternav.domain.model.RouteStep> = route.legs.flatMap { it.steps }

    /**
     * Current step = the step whose maneuver/start vertex is the last one at or before the
     * rider's position. Mapping "first boundary at/after the position" (the previous logic)
     * advanced the instruction one whole step too early — while approaching a turn the banner
     * already showed the maneuver AFTER it. Steps span [start(i), start(i+1)).
     */
    private fun stepIndexForGeometryIndex(geometryIndex: Int): Int {
        var current = 0
        for (i in stepEndIndices.indices) {
            if (stepEndIndices[i] <= geometryIndex) current = i
        }
        return current
    }

    private fun buildStepEndIndices(route: Route): List<Int> {
        // Boundary vertices: one per step, at the geometry vertex nearest the step's maneuver
        // location. Step i spans [boundary(i), boundary(i+1)) — i.e. each boundary is the START
        // of its step (OSRM maneuvers fire at the beginning of a step), and the last step ends
        // at the geometry's final vertex.
        val steps = allSteps(route)
        if (steps.isEmpty() || route.geometry.isEmpty()) return emptyList()
        return steps.map { step -> route.nearestGeometryIndex(step.maneuver.location) }
    }

    private fun buildSuffixDistances(geometry: List<Coordinate>): DoubleArray {
        if (geometry.isEmpty()) return DoubleArray(0)
        val suffix = DoubleArray(geometry.size)
        for (i in geometry.size - 2 downTo 0) {
            val seg = distanceMeters(
                geometry[i].latitude, geometry[i].longitude,
                geometry[i + 1].latitude, geometry[i + 1].longitude,
            )
            suffix[i] = suffix[i + 1] + seg
        }
        return suffix
    }

    private companion object {
        const val MIN_BEARING_SPEED_MPS = 1.0f
        const val MIN_MOVEMENT_FOR_BEARING_METERS = 5.0
        const val BEARING_SMOOTHING = 0.35
    }
}
