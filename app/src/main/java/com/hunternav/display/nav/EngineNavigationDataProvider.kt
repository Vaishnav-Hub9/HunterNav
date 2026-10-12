package com.hunternav.display.nav

import com.hunternav.core.result.AppResult
import com.hunternav.core.util.DebugLog
import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.DisplayNavigationState
import com.hunternav.domain.model.NavPhase
import com.hunternav.domain.navigation.NavigationEngine
import com.hunternav.domain.navigation.NavigationEngineConfig
import com.hunternav.domain.repository.LocationProvider
import com.hunternav.domain.repository.RoutingProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Fallback navigation data source for the display pipeline, backed by HunterNav's own
 * NavigationEngine (OSRM routing + own off-route/reroute/arrival logic). Used when no Google
 * API key is configured, so the renderer/transport pipeline works end-to-end with the same
 * open-source stack as the phone UI — no paid API required.
 *
 * It routes with [RoutingProvider], starts the engine with the first route, feeds it live
 * location updates from [LocationProvider], and maps the engine's `NavigationState` into the
 * stable [DisplayNavigationState] contract.
 */
class EngineNavigationDataProvider(
    private val routingProvider: RoutingProvider,
    private val locationProvider: LocationProvider,
    private val scope: CoroutineScope,
    private val engineConfig: NavigationEngineConfig = NavigationEngineConfig(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : NavigationDataProvider {

    override val providerName: String = "HunterNav engine (OSRM)"

    private val engine = NavigationEngine(
        routingProvider = routingProvider,
        config = engineConfig,
        scope = scope,
        nowMs = nowMs,
    )

    private val _state = MutableStateFlow(DisplayNavigationState.idle())
    override val state: StateFlow<DisplayNavigationState> = _state.asStateFlow()

    private var locationJob: Job? = null
    private var mappingJob: Job? = null
    private var started = false

    override fun isAvailable(): Boolean = true

    override suspend fun startNavigation(destination: Coordinate, destinationName: String?): Boolean {
        val origin = locationProvider.location.value?.coordinate
        if (origin == null) {
            DebugLog.d(TAG, "cannot start: no location fix yet")
            setError()
            return false
        }
        return when (val result = routingProvider.getRoutes(origin, destination, alternatives = false)) {
            is AppResult.Success -> {
                val route = result.value.firstOrNull()
                if (route == null) {
                    setError()
                    false
                } else {
                    beginSession()
                    engine.startNavigation(route, locationProvider.location.value)
                    true
                }
            }
            is AppResult.Failure -> {
                DebugLog.d(TAG, "routing failed (${result.kind}): ${result.message}")
                setError()
                false
            }
        }
    }

    override fun stopNavigation() {
        locationJob?.cancel()
        locationJob = null
        engine.stopNavigation(keepRouteForDisplay = false)
        _state.value = DisplayNavigationState.idle()
        started = false
    }

    // ------------------------------------------------------------------ internals

    private fun beginSession() {
        if (started) return
        started = true
        locationJob?.cancel()
        locationJob = scope.launch {
            locationProvider.start(highFrequency = true)
            locationProvider.updates.collect { location -> engine.onLocationUpdate(location) }
        }
        mappingJob?.cancel()
        mappingJob = scope.launch {
            engine.state.collect { navState -> _state.value = navState.toDisplayStateExt() }
        }
    }

    /** Maps the engine's NavigationState onto the display contract (phase, ETA, timestamps). */
    private fun com.hunternav.domain.model.NavigationState.toDisplayStateExt(): DisplayNavigationState {
        val base = toDisplayState()
        val phase = when {
            destinationReached -> NavPhase.ARRIVED
            rerouting -> NavPhase.REROUTING
            activeRoute != null -> NavPhase.NAVIGATING
            else -> NavPhase.IDLE
        }
        val eta = if (remainingDuration > 0 && phase == NavPhase.NAVIGATING) {
            nowMs() + (remainingDuration * 1000).toLong()
        } else null
        return base.copy(
            phase = phase,
            snapped = snappedCoordinate,
            headingValid = true,
            routeVersion = routeVersion,
            timestampMs = currentLocation?.timestamp ?: nowMs(),
            etaEpochMs = eta,
            simulated = false,
        )
    }

    private fun setError() {
        _state.value = _state.value.copy(phase = NavPhase.ERROR, destinationReached = false)
    }

    companion object {
        private const val TAG = "EngineNavProvider"
    }
}
