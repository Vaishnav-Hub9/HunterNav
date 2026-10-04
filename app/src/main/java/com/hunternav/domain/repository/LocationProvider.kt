package com.hunternav.domain.repository

import com.hunternav.domain.model.LocationData
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Availability of the device location system. */
sealed class LocationSourceStatus {
    /** Permission granted and updates flowing. */
    data object Available : LocationSourceStatus()

    /** Runtime permission not granted. */
    data object PermissionDenied : LocationSourceStatus()

    /** Only approximate (coarse) location granted. */
    data object ApproximateOnly : LocationSourceStatus()

    /** Location services (GPS) disabled at system level. */
    data object GpsDisabled : LocationSourceStatus()

    /** Temporary failure; updates may resume. */
    data class TemporarilyUnavailable(val reason: String?) : LocationSourceStatus()
}

/**
 * Abstraction over the device location system. Implemented by AndroidLocationProvider
 * (FusedLocationProvider) and FakeLocationProvider (demo mode / tests).
 */
interface LocationProvider {
    /** Latest smoothed location; null before the first fix. */
    val location: StateFlow<LocationData?>

    /** Stream of location updates. */
    val updates: SharedFlow<LocationData>

    /** Current status of the location source. */
    val status: StateFlow<LocationSourceStatus>

    /**
     * Starts location updates.
     * @param highFrequency true for navigation-grade update interval, false for map browsing.
     */
    suspend fun start(highFrequency: Boolean)

    fun stop()
}
