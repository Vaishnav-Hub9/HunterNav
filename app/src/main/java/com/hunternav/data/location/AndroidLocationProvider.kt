package com.hunternav.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.hunternav.domain.model.LocationData
import com.hunternav.domain.repository.LocationProvider
import com.hunternav.domain.repository.LocationSourceStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlin.math.abs

/**
 * FusedLocationProvider-backed [LocationProvider].
 *
 * - Navigation mode requests high-frequency updates (2 s / minimal displacement).
 * - Bearing and speed are smoothed so one noisy fix cannot jog the camera or trip rerouting.
 * - Emits status changes so the UI can explain permission-denied / GPS-off states.
 */
class AndroidLocationProvider(
    private val context: Context,
) : LocationProvider {

    private val client: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    private val _location = MutableStateFlow<LocationData?>(null)
    override val location: StateFlow<LocationData?> = _location.asStateFlow()

    private val _updates = MutableSharedFlow<LocationData>(extraBufferCapacity = 64)
    override val updates: SharedFlow<LocationData> = _updates.asSharedFlow()

    private val _status = MutableStateFlow<LocationSourceStatus>(LocationSourceStatus.Available)
    override val status: StateFlow<LocationSourceStatus> = _status.asStateFlow()

    private var callback: LocationCallback? = null

    override suspend fun start(highFrequency: Boolean) {
        if (!hasPermission()) {
            _status.value = LocationSourceStatus.PermissionDenied
            return
        }
        if (!isGpsEnabled()) {
            _status.value = LocationSourceStatus.GpsDisabled
            return
        }

        @SuppressLint("MissingPermission")
        try {
            // Prime with the last known fix so the map centers immediately.
            val last = client.lastLocation.await()
            last?.let { onNewFix(it.latitude, it.longitude, it.accuracy, it.speed, it.bearing, it.time, smoothed = false) }

            val request = LocationRequest.Builder(
                if (highFrequency) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                if (highFrequency) NAV_INTERVAL_MS else BROWSE_INTERVAL_MS,
            ).apply {
                setMinUpdateIntervalMillis(if (highFrequency) NAV_MIN_INTERVAL_MS else BROWSE_INTERVAL_MS)
                setMinUpdateDistanceMeters(if (highFrequency) 3f else 20f)
                setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
                setWaitForAccurateLocation(false)
            }.build()

            val cb = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    for (loc in result.locations) {
                        onNewFix(loc.latitude, loc.longitude, loc.accuracy, loc.speed, loc.bearing, loc.time, smoothed = true)
                    }
                }
            }
            callback = cb
            client.requestLocationUpdates(request, cb, context.mainLooper)
            _status.value = if (isCoarseOnly()) LocationSourceStatus.ApproximateOnly else LocationSourceStatus.Available
        } catch (e: ApiException) {
            _status.value = LocationSourceStatus.TemporarilyUnavailable(e.message)
        } catch (e: IllegalStateException) {
            _status.value = LocationSourceStatus.TemporarilyUnavailable(e.message)
        }
    }

    override fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }

    private fun onNewFix(
        lat: Double,
        lon: Double,
        accuracy: Float,
        speed: Float,
        bearing: Float,
        timestamp: Long,
        smoothed: Boolean,
    ) {
        val previous = _location.value
        var outAccuracy = accuracy
        var outSpeed = speed
        var outBearing = bearing

        if (smoothed && previous != null) {
            // Simple exponential smoothing against the previous fix (no Kalman dependency).
            outAccuracy = (accuracy * 0.7f + previous.accuracy * 0.3f)
            outSpeed = if (speed > 0f) speed * 0.6f + previous.speed * 0.4f else previous.speed * 0.5f
            if (bearing == 0f && previous.bearing != 0f) outBearing = previous.bearing
            else if (bearing != 0f && previous.bearing != 0f) {
                // Shortest-arc blend.
                var delta = bearing - previous.bearing
                if (delta > 180) delta -= 360
                if (delta < -180) delta += 360
                outBearing = previous.bearing + delta * 0.4f
            }
        }
        if (outAccuracy <= 0f) outAccuracy = DEFAULT_ACCURACY

        val data = LocationData(
            latitude = lat,
            longitude = lon,
            accuracy = outAccuracy,
            speed = outSpeed.coerceAtLeast(0f),
            bearing = ((outBearing % 360f) + 360f) % 360f,
            timestamp = if (timestamp > 0) timestamp else System.currentTimeMillis(),
        )
        _location.value = data
        _updates.tryEmit(data)
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isCoarseOnly(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isGpsEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private companion object {
        const val NAV_INTERVAL_MS = 2_000L
        const val NAV_MIN_INTERVAL_MS = 1_000L
        const val BROWSE_INTERVAL_MS = 5_000L
        const val DEFAULT_ACCURACY = 25f
    }
}
