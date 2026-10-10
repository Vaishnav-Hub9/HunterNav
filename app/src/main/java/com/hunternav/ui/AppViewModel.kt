package com.hunternav.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hunternav.core.util.DebugLog
import com.hunternav.di.AppContainer
import com.hunternav.domain.model.CameraMode
import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.Destination
import com.hunternav.domain.model.LocationData
import com.hunternav.domain.model.Route
import com.hunternav.domain.navigation.NavigationEngine
import com.hunternav.domain.navigation.NavigationEvent
import com.hunternav.domain.repository.LocationProvider
import com.hunternav.domain.repository.LocationSourceStatus
import com.hunternav.ui.map.MapController
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Top-level UI phase; screens observe this to know what to render. */
enum class AppPhase { IDLE, ROUTE_PREVIEW, NAVIGATING, ARRIVED }

/**
 * Shared trip ViewModel. Owns destination selection, route fetching, the NavigationEngine,
 * location tracking, and the demo-mode location source. Screens stay thin.
 */
class AppViewModel(
    private val container: AppContainer,
) : ViewModel() {

    val engine: NavigationEngine = container.navigationEngine

    private val _destination = MutableStateFlow<Destination?>(null)
    val destination: StateFlow<Destination?> = _destination.asStateFlow()

    private val _routes = MutableStateFlow<List<Route>>(emptyList())
    val routes: StateFlow<List<Route>> = _routes.asStateFlow()

    private val _selectedRoute = MutableStateFlow<Route?>(null)
    val selectedRoute: StateFlow<Route?> = _selectedRoute.asStateFlow()

    private val _phase = MutableStateFlow(AppPhase.IDLE)
    val phase: StateFlow<AppPhase> = _phase.asStateFlow()

    private val _isLoadingRoutes = MutableStateFlow(false)
    val isLoadingRoutes: StateFlow<Boolean> = _isLoadingRoutes.asStateFlow()

    private val _routeError = MutableStateFlow<String?>(null)
    val routeError: StateFlow<String?> = _routeError.asStateFlow()

    /** The destination the current [_routes] were fetched for (null = none). */
    private val _routesForDestination = MutableStateFlow<Destination?>(null)

    private val _demoMode = MutableStateFlow(false)
    val demoMode: StateFlow<Boolean> = _demoMode.asStateFlow()

    private val _demoRunning = MutableStateFlow(false)
    val demoRunning: StateFlow<Boolean> = _demoRunning.asStateFlow()

    private val _locationStatus = MutableStateFlow<LocationSourceStatus?>(null)
    val locationStatus: StateFlow<LocationSourceStatus?> = _locationStatus.asStateFlow()

    private val _mapReady = MutableStateFlow(false)
    val mapReady: StateFlow<Boolean> = _mapReady.asStateFlow()

    /** Location provider currently in use (real GPS or demo simulator). */
    val locationProvider: LocationProvider get() = if (_demoMode.value) container.fakeLocationProvider else container.realLocationProvider

    /** Identifies this ViewModel instance in traces — there must be exactly one shared instance. */
    private val vmTag: String = Integer.toHexString(System.identityHashCode(this))

    private var routeJob: Job? = null
    private var locationStartJob: Job? = null
    private var locationUpdatesJob: Job? = null

    /**
     * Trip identity: +1 whenever the destination changes or the trip is cleared. Every async
     * operation captures the generation it started under and discards its result when the
     * generation has moved on — a late response from trip N can never restore state into trip
     * N+1 (spec §4/§6: stale responses must not overwrite a newer trip).
     */
    private var tripGeneration: Long = 0L
    private var tripId: String = "-"

    /** Destination of the in-flight route request (dedup: no duplicate concurrent fetches). */
    private var pendingRouteDestination: Destination? = null

    private fun bumpTrip(): Long {
        tripGeneration += 1
        tripId = tripGeneration.toString()
        return tripGeneration
    }

    init {
        viewModelScope.launch {
            engine.events.collect { event ->
                when (event) {
                    is NavigationEvent.ArrivedAtDestination -> {
                        if (_phase.value == AppPhase.NAVIGATING) _phase.value = AppPhase.ARRIVED
                    }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            container.realLocationProvider.status.collect { _locationStatus.value = it }
        }
    }

    // ---------------------------------------------------------------------
    // Location / home screen
    // ---------------------------------------------------------------------

    /** Low-frequency tracking while browsing the map (home). */
    fun startBrowseTracking() {
        if (locationUpdatesJob?.isActive == true) return
        if (_locationStatus.value == LocationSourceStatus.PermissionDenied) return
        locationStartJob = viewModelScope.launch {
            locationProvider.start(highFrequency = false)
        }
        locationUpdatesJob = viewModelScope.launch {
            locationProvider.updates.collect { engine.onLocationUpdate(it) }
        }
    }

    fun stopBrowseTracking() {
        // Keep last fix; stop updates. Navigation restarts tracking when needed.
        if (_phase.value != AppPhase.NAVIGATING) {
            // Cancel the pending start too — otherwise a start() still in flight could register
            // a listener AFTER this stop() and leave a zombie callback running.
            locationStartJob?.cancel()
            locationStartJob = null
            locationProvider.stop()
            locationUpdatesJob?.cancel()
            locationUpdatesJob = null
        }
    }

    fun onPermissionResult(granted: Boolean, coarseOnly: Boolean) {
        _locationStatus.value = when {
            granted && coarseOnly -> LocationSourceStatus.ApproximateOnly
            granted -> LocationSourceStatus.Available
            else -> LocationSourceStatus.PermissionDenied
        }
        if (granted) startBrowseTracking()
    }

    /** Called by screens once their MapLibre style has loaded. */
    fun onMapReady() {
        _mapReady.value = true
    }

    fun currentLocationOrNull(): LocationData? = engine.state.value.currentLocation

    fun statusMessage(status: LocationSourceStatus?): String? = when (status) {
        LocationSourceStatus.PermissionDenied -> "Location permission needed for navigation."
        LocationSourceStatus.GpsDisabled -> "GPS is disabled. Enable location to navigate."
        LocationSourceStatus.ApproximateOnly -> "Approximate location only — accuracy will be limited."
        is LocationSourceStatus.TemporarilyUnavailable -> "Location temporarily unavailable."
        else -> null
    }

    // ---------------------------------------------------------------------
    // Destination selection
    // ---------------------------------------------------------------------

    fun onDestinationSelected(destination: Destination) {
        DebugLog.d(
            "DESTINATION_SELECTED",
            "vm=$vmTag title=${destination.title} subtitle=${destination.subtitle}",
        )
        DebugLog.d(
            "DESTINATION_COORDINATES",
            "vm=$vmTag lat=${destination.coordinate.latitude} lon=${destination.coordinate.longitude}",
        )
        setDestination(destination)
    }

    /**
     * Swaps the trip destination. A *different* location discards every trace of the previous
     * trip — fetched routes, the selected route, errors, the in-flight request and any active
     * navigation — so a stale route can never be shown for the new destination (spec: changing
     * destination must discard the previous active route and navigation steps).
     */
    private fun setDestination(destination: Destination) {
        val previous = _destination.value
        _destination.value = destination
        if (previous != null && previous.coordinate == destination.coordinate) return

        // New trip identity: any in-flight request/label lookup from the old trip is now stale.
        bumpTrip()
        routeJob?.cancel()
        routeJob = null
        pendingRouteDestination = null
        _routes.value = emptyList()
        _selectedRoute.value = null
        _routeError.value = null
        _routesForDestination.value = null
        _isLoadingRoutes.value = false
        if (_phase.value == AppPhase.NAVIGATING || _phase.value == AppPhase.ARRIVED) {
            engine.stopNavigation()
            _phase.value = AppPhase.IDLE
        }
    }

    /** Long-press on map: pin immediately, upgrade the label if reverse geocoding succeeds. */
    fun onMapLongPress(coordinate: Coordinate) {
        val fallback = Destination(
            coordinate = coordinate,
            title = "Dropped pin",
            subtitle = "%.5f, %.5f".format(coordinate.latitude, coordinate.longitude),
        )
        DebugLog.d(
            "DESTINATION_SELECTED",
            "vm=$vmTag source=map_long_press title=${fallback.title}",
        )
        DebugLog.d(
            "DESTINATION_COORDINATES",
            "vm=$vmTag lat=${coordinate.latitude} lon=${coordinate.longitude}",
        )
        setDestination(fallback)
        val generationAtPress = tripGeneration
        viewModelScope.launch {
            val result = container.searchDestination.reverse(coordinate)
            // Stale guard: if the user picked another destination (or the trip was cleared)
            // while the reverse lookup was in flight, its label must not overwrite the new one.
            if (generationAtPress != tripGeneration) return@launch
            if (result is com.hunternav.core.result.AppResult.Success) {
                _destination.value = result.value.copy(
                    subtitle = result.value.subtitle ?: fallback.subtitle,
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // Routing
    // ---------------------------------------------------------------------

    /** Fetches routes from the current location to the chosen destination. */
    fun prepareRoute() {
        val dest = _destination.value
        if (dest == null) {
            // Was a silent `return`, which left Route Preview showing "—" with no explanation.
            _routeError.value = "Destination lookup failed. Choose a destination and try again."
            return
        }
        val origin = engine.state.value.currentLocation?.coordinate
        if (origin == null) {
            // No GPS fix yet (permission denied / GPS off): explain instead of routing nonsense.
            _routeError.value = "Your location isn't available yet. Enable GPS and try again."
            return
        }
        routeJob?.cancel()
        val generation = tripGeneration
        pendingRouteDestination = dest
        _isLoadingRoutes.value = true
        _routeError.value = null
        routeJob = viewModelScope.launch {
            val result = container.calculateRoutes(origin, dest.coordinate, alternatives = true)
            // Stale-response guard: a response for a destination the user has already
            // changed (or a cleared trip) must never overwrite the newer state.
            if (generation != tripGeneration) return@launch
            pendingRouteDestination = null
            _isLoadingRoutes.value = false
            when (result) {
                is com.hunternav.core.result.AppResult.Success -> {
                    _routes.value = result.value
                    _selectedRoute.value = result.value.firstOrNull()
                    _routesForDestination.value = dest
                }
                is com.hunternav.core.result.AppResult.Failure -> {
                    _routes.value = emptyList()
                    _selectedRoute.value = null
                    _routeError.value = humanMessage(result.kind, result.message)
                }
            }
        }
    }

    /**
     * Fetches routes for the current destination unless a successful result for that exact
     * destination already exists — used by Route Preview so returning to it with the same
     * destination keeps its routes while a *new* destination always refetches. Identity is
     * the COORDINATE (a late label upgrade must not trigger a needless refetch), and a request
     * already in flight for this destination is never duplicated (repeated button taps).
     */
    fun ensureRoute() {
        val dest = _destination.value
        if (dest != null) {
            if (_isLoadingRoutes.value && pendingRouteDestination?.coordinate == dest.coordinate) return
            if (_routes.value.isNotEmpty() && _routesForDestination.value?.coordinate == dest.coordinate) return
        }
        prepareRoute()
    }

    fun selectRoute(route: Route) {
        _selectedRoute.value = route
    }

    // ---------------------------------------------------------------------
    // Active navigation
    // ---------------------------------------------------------------------

    /** Begins active navigation with the selected route. */
    fun startNavigation() {
        // Double-tap guard: a second tap while already navigating must not restart the trip,
        // reset the arrival latch or spawn another location pipeline.
        if (_phase.value == AppPhase.NAVIGATING) return
        val route = _selectedRoute.value ?: return
        val initial = engine.state.value.currentLocation
        DebugLog.d("NAVIGATION_STARTED", "vm=$vmTag trip=$tripId")
        locationStartJob?.cancel()
        locationUpdatesJob?.cancel()
        locationStartJob = viewModelScope.launch {
            locationProvider.start(highFrequency = true)
        }
        locationUpdatesJob = viewModelScope.launch {
            locationProvider.updates.collect { engine.onLocationUpdate(it) }
        }
        // Arrival is evaluated against the selected destination, not merely the geometry end.
        engine.startNavigation(route, initial, arrivalDestination = _destination.value?.coordinate)
        _phase.value = AppPhase.NAVIGATING
    }

    /**
     * Ends the active trip exactly once (cancel button, system back, or arrival Done):
     * cancels route/location jobs, stops the engine, drops routes + destination + maneuver
     * progress, and returns to [AppPhase.IDLE]. A subsequent trip can never reuse the prior
     * destination, route, ETA, maneuver index or arrival state (spec §11).
     */
    private fun clearTrip(reason: String) {
        DebugLog.d("NAVIGATION_STOPPED", "vm=$vmTag trip=$tripId reason=$reason")
        bumpTrip()
        routeJob?.cancel()
        routeJob = null
        pendingRouteDestination = null
        _isLoadingRoutes.value = false
        engine.stopNavigation()
        stopDemoSimulation()
        locationStartJob?.cancel()
        locationStartJob = null
        locationUpdatesJob?.cancel()
        locationUpdatesJob = null
        locationProvider.stop()
        _routes.value = emptyList()
        _selectedRoute.value = null
        _routesForDestination.value = null
        _routeError.value = null
        _destination.value = null
        _phase.value = AppPhase.IDLE
    }

    fun cancelNavigation() = clearTrip(reason = "cancelled")

    /** Acknowledges arrival ("Done") and returns the app to idle. */
    fun finishArrival() = clearTrip(reason = "arrived")

    // ---------------------------------------------------------------------
    // Demo mode (developer switch)
    // ---------------------------------------------------------------------

    fun setDemoMode(enabled: Boolean) {
        if (_demoMode.value == enabled) return
        if (locationUpdatesJob?.isActive == true) locationProvider.stop()
        if (!enabled) stopDemoSimulation()
        _demoMode.value = enabled
    }

    /** Arms the demo simulator along the selected/active route. */
    fun startDemoSimulation() {
        val route = _selectedRoute.value ?: engine.state.value.activeRoute ?: return
        val fake = container.fakeLocationProvider
        fake.speedMps = DEMO_SPEED_MPS
        _demoRunning.value = true
        fake.startSimulation(container.appScope, route.geometry, startIndex = 0, tickMs = 1_000L)
    }

    fun stopDemoSimulation() {
        container.fakeLocationProvider.stopSimulation()
        _demoRunning.value = false
    }

    /** Injects a wrong turn in demo mode to exercise off-route + rerouting. */
    fun injectDemoWrongTurn() = container.fakeLocationProvider.injectWrongTurn()

    private fun humanMessage(kind: com.hunternav.core.result.AppErrorKind, detail: String?): String = when (kind) {
        com.hunternav.core.result.AppErrorKind.NETWORK -> "No internet connection. Check your network and try again."
        com.hunternav.core.result.AppErrorKind.TIMEOUT -> "The routing service took too long. Try again."
        com.hunternav.core.result.AppErrorKind.SERVER -> "The routing service is unavailable right now."
        com.hunternav.core.result.AppErrorKind.PARSE -> "Received an unexpected response from the routing service."
        com.hunternav.core.result.AppErrorKind.UNREACHABLE_DESTINATION -> "That destination can't be reached by road."
        com.hunternav.core.result.AppErrorKind.NO_ROUTE -> "No route found to this destination."
        com.hunternav.core.result.AppErrorKind.LOCATION_UNAVAILABLE -> "Your location isn't available yet. Enable GPS and try again."
        com.hunternav.core.result.AppErrorKind.UNKNOWN -> detail ?: "Something went wrong."
    }

    override fun onCleared() {
        locationProvider.stop()
        super.onCleared()
    }

    private companion object {
        const val DEMO_SPEED_MPS = 12.0
    }
}

/** Factory so screens can construct [AppViewModel] with the shared [AppContainer]. */
class AppViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(container) as T
}

/**
 * Returns the single trip-scoped [AppViewModel] shared by every screen.
 *
 * NavHost overrides `LocalViewModelStoreOwner` with the destination's `NavBackStackEntry`
 * (see `NavBackStackEntryProvider.LocalOwnersProvider`), so a plain `viewModel(factory = ...)`
 * inside a destination composable creates a **separate** AppViewModel per screen. The
 * destination chosen on Home/Search was then written to one instance while Route Preview
 * read another, which rendered "—" with no routes and a disabled Start button.
 *
 * Scoping to the Activity keeps one instance for the whole trip.
 */
@Composable
fun rememberAppViewModel(container: AppContainer): AppViewModel {
    val context = LocalContext.current
    val owner = context.unwrapToViewModelStoreOwner()
        ?: checkNotNull(LocalViewModelStoreOwner.current) {
            "No ViewModelStoreOwner available for the shared AppViewModel"
        }
    return viewModel(viewModelStoreOwner = owner, factory = AppViewModelFactory(container))
}

private fun Context.unwrapToViewModelStoreOwner(): ViewModelStoreOwner? {
    if (this is ViewModelStoreOwner) return this
    val base = (this as? ContextWrapper)?.baseContext ?: return null
    return base.unwrapToViewModelStoreOwner()
}
