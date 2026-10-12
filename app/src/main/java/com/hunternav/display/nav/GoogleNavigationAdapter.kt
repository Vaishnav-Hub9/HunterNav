package com.hunternav.display.nav

import android.app.Application
import android.location.Location
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoadSnappedLocationProvider
import com.google.android.libraries.navigation.RouteSegment
import com.google.android.libraries.navigation.Waypoint
import com.hunternav.core.util.DebugLog
import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.DisplayNavigationState
import com.hunternav.domain.model.NavPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Primary navigation data adapter backed by the **Google Navigation SDK** (8.0.0).
 *
 * Uses only APIs verified with `javap` against the shipped AAR:
 *  - `NavigationApi.setApiKey` / `getNavigator(Application, NavigatorListener)`
 *  - `Navigator.setDestination(Waypoint)` → `ListenableResultFuture<RouteStatus>`
 *  - `Navigator.startGuidance()/stopGuidance()/clearDestinations()`
 *  - `Navigator.addRouteChangedListener/addReroutingListener/addArrivalListener`
 *  - `Navigator.getRouteSegments()` → `RouteSegment.getLatLngs()` (geometry, in route order)
 *  - `NavigationApi.getRoadSnappedLocationProvider(app)` → `addLocationListener`
 *  - `Navigator.registerServiceForNavUpdates(pkg, NavFeedService.CLASS_NAME, steps)` for the
 *    turn-by-turn feed (maneuver/distances/ETA), decoded by [NavFeedService].
 *
 * Responsibilities kept here (everything else lives in [NavStateReducer], which is JVM-testable):
 *  - owning SDK lifecycles (navigator, feed registration, location listener),
 *  - flattening route segments in order with duplicate-point removal,
 *  - bridging SDK callbacks into the reducer.
 */
class GoogleNavigationAdapter(
    private val app: Application,
    private val apiKey: String,
) : NavigationDataProvider {

    override val providerName: String = "Google Navigation SDK"

    private val reducer = NavStateReducer()
    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(DisplayNavigationState.idle())
    override val state: StateFlow<DisplayNavigationState> = _state.asStateFlow()

    private var navigator: Navigator? = null
    private var navigatorReady = false
    private var guidanceRunning = false
    private var pendingDestination: Coordinate? = null
    private var pendingDestinationName: String? = null

    private val locationListener = object : RoadSnappedLocationProvider.LocationListener {
        override fun onLocationChanged(location: Location) {
            reducer.onLocation(
                NavLocationFix(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
                    timestampMs = location.time,
                    fromRoadSnappedProvider = true,
                ),
            )
            publish()
        }

        override fun onRawLocationUpdate(location: Location) {
            reducer.onLocation(
                NavLocationFix(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
                    timestampMs = location.time,
                    fromRoadSnappedProvider = false,
                ),
            )
            publish()
        }
    }

    private val routeChangedListener = Navigator.RouteChangedListener {
        // Route geometry was replaced (initial route, reroute, alternate, traffic update).
        publishGeometryFromNavigator()
        publish()
    }

    private val reroutingListener = Navigator.ReroutingListener {
        DebugLog.d(TAG, "rerouting requested by off-route")
        publish()
    }

    private val arrivalListener = Navigator.ArrivalListener { event ->
        if (event.isFinalDestination) {
            DebugLog.d(TAG, "arrived at final destination")
            // Feed state will also report STOPPED; force ARRIVED so the reducer latches it.
            reducer.onFeed(
                NavFeedSnapshot(
                    navState = NavFeedSnapshot.NAV_STATE_STOPPED,
                    timestampMs = System.currentTimeMillis(),
                ),
            )
            markArrived()
        }
    }

    private val feedListener: (NavFeedSnapshot) -> Unit = { snapshot ->
        reducer.onFeed(snapshot)
        publish()
    }

    init {
        if (apiKey.isNotBlank()) {
            try {
                NavigationApi.setApiKey(apiKey)
            } catch (e: RuntimeException) {
                // setApiKey must be called once per process; a second adapter instance in the
                // same process lands here — the first key wins, which is fine.
                DebugLog.d(TAG, "setApiKey skipped: ${e.message}")
            }
        }
        NavFeedBus.subscribe(feedListener)
        NavigationApi.getNavigator(app, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(navigator: Navigator) {
                this@GoogleNavigationAdapter.navigator = navigator
                navigatorReady = true
                navigator.addRouteChangedListener(routeChangedListener)
                navigator.addReroutingListener(reroutingListener)
                navigator.addArrivalListener(arrivalListener)
                try {
                    navigator.registerServiceForNavUpdates(
                        app.packageName,
                        NavFeedService.CLASS_NAME,
                        2, // current + next step preview
                    )
                } catch (e: RuntimeException) {
                    DebugLog.d(TAG, "feed registration failed: ${e.message}")
                }
                // A destination may have been requested before the navigator was ready.
                pendingDestination?.let { dest ->
                    val name = pendingDestinationName
                    adapterScope.launch { startNavigation(dest, name) }
                }
            }

            override fun onError(errorCode: Int) {
                DebugLog.d(TAG, "navigator error code=$errorCode")
                navigatorReady = false
                setError("Navigation SDK error ($errorCode)")
            }
        })
        val provider = NavigationApi.getRoadSnappedLocationProvider(app)
        provider?.addLocationListener(locationListener)
    }

    override fun isAvailable(): Boolean = apiKey.isNotBlank()

    override suspend fun startNavigation(destination: Coordinate, destinationName: String?): Boolean {
        val nav = navigator
        if (nav == null || !navigatorReady) {
            // Navigator still initializing: remember and retry from onNavigatorReady.
            pendingDestination = destination
            pendingDestinationName = destinationName
            return true
        }
        val waypoint = Waypoint.builder()
            .setLatLng(destination.latitude, destination.longitude)
            .apply { if (destinationName != null) setTitle(destinationName) }
            .build()

        return suspendCoroutine { continuation ->
            var resumed = false
            nav.setDestination(waypoint).setOnResultListener { status ->
                if (resumed) return@setOnResultListener
                resumed = true
                if (status == Navigator.RouteStatus.OK) {
                    guidanceRunning = true
                    nav.startGuidance()
                    publishGeometryFromNavigator()
                    DebugLog.d(TAG, "guidance started to $destinationName")
                    continuation.resume(true)
                } else {
                    setError("Route failed: ${status.name}")
                    continuation.resume(false)
                }
            }
        }
    }

    override fun stopNavigation() {
        pendingDestination = null
        val nav = navigator
        if (nav != null && guidanceRunning) {
            try {
                nav.stopGuidance()
                nav.clearDestinations()
            } catch (e: RuntimeException) {
                DebugLog.d(TAG, "stop failed: ${e.message}")
            }
        }
        guidanceRunning = false
        reducer.reset()
        publish()
    }

    /** Releases SDK listeners. Call from the owning component's teardown. */
    fun destroy() {
        stopNavigation()
        adapterScope.cancel()
        NavFeedBus.unsubscribe()
        navigator?.let { nav ->
            nav.removeRouteChangedListener(routeChangedListener)
            nav.removeReroutingListener(reroutingListener)
            nav.removeArrivalListener(arrivalListener)
            try {
                nav.unregisterServiceForNavUpdates()
            } catch (e: RuntimeException) {
                DebugLog.d(TAG, "unregister feed failed: ${e.message}")
            }
        }
        NavigationApi.getRoadSnappedLocationProvider(app)?.removeLocationListener(locationListener)
        navigator = null
        navigatorReady = false
    }

    // ------------------------------------------------------------------ internals

    /**
     * Reads `Navigator.getRouteSegments()` and flattens every segment's `getLatLngs()` in
     * route order, dropping consecutive duplicates (segment joins repeat the shared vertex).
     * Segments are already in route order per the SDK reference; nothing is reordered.
     */
    private fun publishGeometryFromNavigator() {
        val nav = navigator ?: return
        val flattened = ArrayList<Coordinate>()
        try {
            val segments: List<RouteSegment> = nav.routeSegments
            for (segment in segments) {
                val points = segment.latLngs ?: continue
                for (p in points) {
                    val coord = Coordinate(p.latitude, p.longitude)
                    if (flattened.isEmpty() || flattened.last() != coord) {
                        flattened.add(coord)
                    }
                }
            }
        } catch (e: RuntimeException) {
            DebugLog.d(TAG, "geometry read failed: ${e.message}")
            return
        }
        if (flattened.size >= 2) {
            reducer.onGeometry(flattened)
        }
    }

    private fun markArrived() {
        _state.value = _state.value.copy(
            phase = NavPhase.ARRIVED,
            destinationReached = true,
        )
    }

    private fun setError(message: String) {
        DebugLog.d(TAG, "error: $message")
        _state.value = _state.value.copy(phase = NavPhase.ERROR, destinationReached = false)
    }

    /** Publishes the reduced state, throttled to ~10 Hz to keep recomposition cheap. */
    private fun publish() {
        _state.value = reducer.reduce(System.currentTimeMillis())
    }

    companion object {
        private const val TAG = "GoogleNavAdapter"
    }
}
