package com.hunternav.di

import android.app.Application
import android.content.Context
import com.hunternav.BuildConfig
import com.hunternav.core.util.DebugLog
import com.hunternav.data.geocoding.NominatimGeocodingProvider
import com.hunternav.data.location.AndroidLocationProvider
import com.hunternav.data.location.FakeLocationProvider
import com.hunternav.data.network.NetworkClient
import com.hunternav.data.recent.InMemoryPreferenceBackend
import com.hunternav.data.recent.RecentDestinationsStore
import com.hunternav.data.recent.SharedPreferencesBackend
import com.hunternav.data.routing.OsrmRoutingProvider
import com.hunternav.display.DisplayPipeline
import com.hunternav.display.nav.EngineNavigationDataProvider
import com.hunternav.display.nav.GoogleNavigationAdapter
import com.hunternav.display.nav.NavigationDataProvider
import com.hunternav.display.nav.SimulatedNavigationDataProvider
import com.hunternav.display.transport.DisplayTransport
import com.hunternav.display.transport.WebSocketDisplayTransport
import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.navigation.NavigationEngine
import com.hunternav.domain.navigation.NavigationEngineConfig
import com.hunternav.domain.repository.DestinationHistory
import com.hunternav.domain.repository.GeocodingProvider
import com.hunternav.domain.repository.LocationProvider
import com.hunternav.domain.repository.RoutingProvider
import com.hunternav.domain.usecase.CalculateRoutesUseCase
import com.hunternav.domain.usecase.SearchDestinationUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled dependency container (V1 keeps DI minimal; swap for Hilt later if desired).
 * All configurable endpoints come from BuildConfig, which reads local.properties overrides.
 *
 * [routingProviderOverride] / [geocodingProviderOverride] exist so unit tests can exercise the
 * destination → route → preview flow with fakes (default null ⇒ production providers).
 */
class AppContainer(
    context: Context,
    routingProviderOverride: RoutingProvider? = null,
    geocodingProviderOverride: GeocodingProvider? = null,
    engineScopeOverride: CoroutineScope? = null,
    engineNowMsOverride: (() -> Long)? = null,
    destinationHistoryOverride: DestinationHistory? = null,
) {

    private val appContext: android.content.Context = context.applicationContext ?: context

    /**
     * Starts/stops the display foreground service (single owner of the display pipeline).
     * Guarded because unit tests construct this container with a null-backed ContextWrapper;
     * a failed service start must never break the navigation UI — the pipeline simply stays
     * off and the reason is logged.
     */
    fun startDisplayStreaming() {
        try {
            com.hunternav.display.DisplayForegroundService.start(appContext)
        } catch (e: RuntimeException) {
            DebugLog.d("AppContainer", "display service start failed: ${e.message}")
        }
    }

    fun stopDisplayStreaming() {
        try {
            com.hunternav.display.DisplayForegroundService.stop(appContext)
        } catch (e: RuntimeException) {
            DebugLog.d("AppContainer", "display service stop failed: ${e.message}")
        }
    }

    val appScope = engineScopeOverride ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val networkClient = NetworkClient()

    /** Tighter timeouts for routing (spec P2): hung reroutes must fail fast and retry. */
    private val routingNetworkClient = NetworkClient(routing = true)

    val routingProvider: RoutingProvider = routingProviderOverride ?: OsrmRoutingProvider(
        baseUrl = BuildConfig.OSRM_BASE_URL,
        networkClient = routingNetworkClient,
    )

    val geocodingProvider: GeocodingProvider = geocodingProviderOverride ?: NominatimGeocodingProvider(
        baseUrl = BuildConfig.GEOCODER_BASE_URL,
        networkClient = networkClient,
    )

    val realLocationProvider: LocationProvider = AndroidLocationProvider(context)

    /**
     * Real user selections only — backs the home screen's "Recent destinations". Never
     * fabricated: every entry was a place the user actually picked. Falls back to an
     * in-memory store when no Android preferences are available (unit tests).
     */
    val destinationHistory: DestinationHistory = destinationHistoryOverride ?: runCatching {
        RecentDestinationsStore(
            SharedPreferencesBackend(
                appContext.getSharedPreferences("hunter_nav_recents", Context.MODE_PRIVATE),
            ),
        )
    }.getOrElse { RecentDestinationsStore(InMemoryPreferenceBackend()) }

    /** Demo mode (dev builds): simulated GPS. Toggled at runtime from Settings. */
    val fakeLocationProvider = FakeLocationProvider()

    val calculateRoutes = CalculateRoutesUseCase(routingProvider)
    val searchDestination = SearchDestinationUseCase(geocodingProvider)

    val navigationEngine: NavigationEngine = NavigationEngine(
        routingProvider = routingProvider,
        config = NavigationEngineConfig(),
        scope = appScope,
        nowMs = engineNowMsOverride ?: System::currentTimeMillis,
    )

    // ---------------------------------------------------------------------
    // Handlebar display pipeline (ESP32 over Wi-Fi, see docs/display-pipeline.md)
    // ---------------------------------------------------------------------

    /** WebSocket link to the ESP32 display (URL configurable via local.properties). */
    val displayTransport: DisplayTransport = WebSocketDisplayTransport(
        wsUrl = BuildConfig.DISPLAY_WS_URL,
        scope = appScope,
    )

    /**
     * Navigation data source for the display, in order of preference:
     *  1. Google Navigation SDK — when a Google Maps key is configured;
     *  2. HunterNav's own OSRM engine — the open-source stack, works with no paid API;
     *  3. Simulator — dev builds without any backend (display can be exercised offline).
     */
    val displayProvider: NavigationDataProvider = when {
        BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank() && context.applicationContext is Application -> {
            DebugLog.d("DISPLAY_PROVIDER", "selected=google_navigation_sdk (key configured)")
            GoogleNavigationAdapter(context.applicationContext as Application, BuildConfig.GOOGLE_MAPS_API_KEY)
        }
        BuildConfig.DISPLAY_SIMULATOR -> {
            DebugLog.d("DISPLAY_PROVIDER", "selected=simulated (display.simulator=true)")
            SimulatedNavigationDataProvider(
                scope = appScope,
                originProvider = { Coordinate(BuildConfig.DEFAULT_LATITUDE, BuildConfig.DEFAULT_LONGITUDE) },
            )
        }
        else -> {
            DebugLog.d("DISPLAY_PROVIDER", "selected=osrm_engine (no Google key, simulator off)")
            EngineNavigationDataProvider(
                routingProvider = routingProvider,
                locationProvider = realLocationProvider,
                scope = appScope,
                nowMs = engineNowMsOverride ?: System::currentTimeMillis,
            )
        }
    }

    /** Wires provider → renderer → JPEG encoder → WebSocket transport. Lazy so JVM unit tests
     * (and app startup before the display is used) never touch android.graphics. */
    val displayPipeline: DisplayPipeline by lazy {
        DisplayPipeline(
            provider = displayProvider,
            transport = displayTransport,
            renderer = com.hunternav.display.render.FrameRenderer(
                width = BuildConfig.DISPLAY_WIDTH,
                height = BuildConfig.DISPLAY_HEIGHT,
            ),
            scope = appScope,
        )
    }
}
