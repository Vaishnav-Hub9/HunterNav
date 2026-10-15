package com.hunternav.display.nav

import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.DisplayNavigationState
import kotlinx.coroutines.flow.StateFlow

/**
 * Source of navigation data for the handlebar display pipeline.
 *
 * The renderer, JPEG encoder and Wi-Fi transport only ever see this interface and the stable
 * [DisplayNavigationState] model, so the navigation engine behind it (HunterNav's own
 * engine or a simulator) can be swapped or tested independently.
 */
interface NavigationDataProvider {

    /** Latest navigation snapshot. Always emits, starting from an idle state. */
    val state: StateFlow<DisplayNavigationState>

    /** Human-readable provider name for diagnostics/UI ("HunterNav engine", "Simulator"). */
    val providerName: String

    /**
     * Starts (or restarts) navigation to [destination]. Returns false when the provider
     * cannot start (no key, no route, GPS unavailable) — the caller surfaces the reason.
     */
    suspend fun startNavigation(destination: Coordinate, destinationName: String?): Boolean

    /** Stops navigation and releases provider resources. Safe to call in any state. */
    fun stopNavigation()

    /**
     * True when the provider is usable on this device/build right now (e.g. an API key is
     * configured and the SDK initialized). When false the UI must not offer this provider.
     */
    fun isAvailable(): Boolean
}
