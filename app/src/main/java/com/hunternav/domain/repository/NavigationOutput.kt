package com.hunternav.domain.repository

import com.hunternav.domain.model.DisplayNavigationState

/**
 * Sink for navigation state, prepared for the future ESP32/Bluetooth display layer
 * (see docs/future-hardware.md). V1 ships no implementation that transmits data;
 * a logging sink may be registered in debug builds.
 *
 * Future: NavigationOutput -> BluetoothNavigationOutput.
 */
fun interface NavigationOutput {
    fun onNavigationStateChanged(state: DisplayNavigationState)
}
