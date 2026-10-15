package com.hunternav.di

import android.content.Context
import com.hunternav.BuildConfig
import com.hunternav.core.util.DebugLog
import com.hunternav.data.geocoding.GoogleGeocodingProvider
import com.hunternav.data.location.AndroidLocationProvider
import com.hunternav.data.location.FakeLocationProvider
import com.hunternav.data.network.NetworkClient
import com.hunternav.data.recent.InMemoryPreferenceBackend
import com.hunternav.data.recent.RecentDestinationsStore
import com.hunternav.data.recent.SharedPreferencesBackend
import com.hunternav.core.result.AppResult
import com.hunternav.data.routing.GoogleRoutesProvider
import com.hunternav.display.DisplayPipeline
import com.hunternav.display.nav.EngineNavigationDataProvider
import com.hunternav.display.nav.NavigationDataProvider
import com.hunternav.display.nav.SimulatedNavigationDataProvider
import com.hunternav.display.transport.DisplayTransport
import com.hunternav.display.transport.WebSocketDisplayTransport
import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.Route
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
    /** Injectable so tests can exercise the unconfigured branch even on machines whose
     *  local.properties carries a real key (BuildConfig would otherwise be non-blank). */
    private val googleMapsApiKey: String = BuildConfig.GOOGLE_MAPS_API_KEY,
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

    /**
     * Routing is Google-only (Routes API v2, traffic-aware TWO_WHEELER mode — see
     * docs/google-maps.md). There is deliberately NO fallback engine: without a Google
     * Maps key the app surfaces a clear configuration error through the normal error path
     * instead of silently routing with a different engine's free-flow numbers.
     */
    val routingProvider: RoutingProvider = routingProviderOverride ?: if (googleMapsApiKey.isNotBlank()) {
        DebugLog.d("ROUTING_PROVIDER", "selected=google_routes (google.maps.apiKey configured)")
        GoogleRoutesProvider(
            baseUrl = BuildConfig.GOOGLE_ROUTES_BASE_URL,
            apiKey = googleMapsApiKey,
            networkClient = routingNetworkClient,
        )
    } else {
        DebugLog.d("ROUTING_PROVIDER", "selected=google_routes (UNCONFIGURED: google.maps.apiKey missing)")
        UnconfiguredRoutingProvider
    }

    /** Diagnostic/attribution name of the active routing engine ("google_routes" or "google_routes-unconfigured"). */
    val routingProviderTag: String =
        if (routingProvider is GoogleRoutesProvider) "google_routes" else "google_routes-unconfigured"

    /**
     * Destination search + reverse geocoding through the Google Geocoding API
     * (docs/google-maps.md). [geocodingProviderTag] names the active one for diagnostics
     * and in-app attribution.
     */
    val geocodingProvider: GeocodingProvider = geocodingProviderOverride ?: if (googleMapsApiKey.isNotBlank()) {
        DebugLog.d("GEOCODING_PROVIDER", "selected=google (google.maps.apiKey configured)")
        GoogleGeocodingProvider(
            baseUrl = BuildConfig.GOOGLE_GEOCODING_BASE_URL,
            apiKey = googleMapsApiKey,
            networkClient = networkClient,
        )
    } else {
        DebugLog.d("GEOCODING_PROVIDER", "selected=google (UNCONFIGURED: google.maps.apiKey missing)")
        UnconfiguredGeocodingProvider
    }

    /** Diagnostic/attribution name of the active geocoder ("google" or "google-unconfigured"). */
    val geocodingProviderTag: String =
        if (geocodingProvider is GoogleGeocodingProvider) "google" else "google-unconfigured"

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
     *  1. HunterNav's own engine — the same Google-backed routing the phone UI uses;
     *  2. Simulator — dev builds without any backend (display can be exercised offline).
     */
    val displayProvider: NavigationDataProvider = when {
        BuildConfig.DISPLAY_SIMULATOR -> {
            DebugLog.d("DISPLAY_PROVIDER", "selected=simulated (display.simulator=true)")
            SimulatedNavigationDataProvider(
                scope = appScope,
                originProvider = { Coordinate(BuildConfig.DEFAULT_LATITUDE, BuildConfig.DEFAULT_LONGITUDE) },
            )
        }
        else -> {
            DebugLog.d("DISPLAY_PROVIDER", "selected=hunternav_engine (google-backed routing)")
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

/**
 * Stands in for [GoogleRoutesProvider] when `google.maps.apiKey` is not set. Returns the setup
 * error through the standard [AppResult] path so route preview/navigation show a clear message —
 * it never contacts any routing server. Missing auth must fail loudly, never silently switch
 * providers (different engines give different distance/ETA numbers).
 */
private object UnconfiguredRoutingProvider : RoutingProvider {
    override suspend fun getRoutes(
        origin: Coordinate,
        destination: Coordinate,
        alternatives: Boolean,
    ): AppResult<List<Route>> = AppResult.Failure(
        kind = com.hunternav.core.result.AppErrorKind.UNKNOWN,
        message = "Google routing is not configured. Add google.maps.apiKey=<your key> " +
            "to local.properties and rebuild (see docs/google-maps.md).",
    )
}

/**
 * Stands in for [GoogleGeocodingProvider] when `google.maps.apiKey` is not set — destination
 * search and reverse geocoding report a clear configuration error instead of silently using
 * another provider.
 */
private object UnconfiguredGeocodingProvider : GeocodingProvider {
    override suspend fun search(
        query: String,
        near: Coordinate?,
        limit: Int,
    ): AppResult<List<com.hunternav.domain.model.Destination>> = AppResult.Failure(
        kind = com.hunternav.core.result.AppErrorKind.SERVER,
        message = "Google geocoding is not configured. Add google.maps.apiKey=<your key> " +
            "to local.properties and rebuild (see docs/google-maps.md).",
    )

    override suspend fun reverse(
        coordinate: Coordinate,
    ): AppResult<com.hunternav.domain.model.Destination> = AppResult.Failure(
        kind = com.hunternav.core.result.AppErrorKind.SERVER,
        message = "Google geocoding is not configured. Add google.maps.apiKey=<your key> " +
            "to local.properties and rebuild (see docs/google-maps.md).",
    )
}
