package com.hunternav.di

import android.content.Context
import com.hunternav.BuildConfig
import com.hunternav.data.geocoding.NominatimGeocodingProvider
import com.hunternav.data.location.AndroidLocationProvider
import com.hunternav.data.location.FakeLocationProvider
import com.hunternav.data.network.NetworkClient
import com.hunternav.data.routing.OsrmRoutingProvider
import com.hunternav.domain.navigation.NavigationEngine
import com.hunternav.domain.navigation.NavigationEngineConfig
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
) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val networkClient = NetworkClient()

    val routingProvider: RoutingProvider = routingProviderOverride ?: OsrmRoutingProvider(
        baseUrl = BuildConfig.OSRM_BASE_URL,
        networkClient = networkClient,
    )

    val geocodingProvider: GeocodingProvider = geocodingProviderOverride ?: NominatimGeocodingProvider(
        baseUrl = BuildConfig.GEOCODER_BASE_URL,
        networkClient = networkClient,
    )

    val realLocationProvider: LocationProvider = AndroidLocationProvider(context)

    /** Demo mode (dev builds): simulated GPS. Toggled at runtime from Settings. */
    val fakeLocationProvider = FakeLocationProvider()

    val calculateRoutes = CalculateRoutesUseCase(routingProvider)
    val searchDestination = SearchDestinationUseCase(geocodingProvider)

    val navigationEngine: NavigationEngine = NavigationEngine(
        routingProvider = routingProvider,
        config = NavigationEngineConfig(),
        scope = appScope,
    )
}
