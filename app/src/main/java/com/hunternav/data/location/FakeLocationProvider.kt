package com.hunternav.data.location

import com.hunternav.domain.model.LocationData
import com.hunternav.domain.repository.LocationProvider
import com.hunternav.domain.repository.LocationSourceStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Deterministic fake location source for demo mode ("test without driving") and unit tests.
 * Simulates a moving vehicle; supports an injected wrong turn for reroute demos.
 */
class FakeLocationProvider : LocationProvider {

    private val _location = MutableStateFlow<LocationData?>(null)
    override val location: StateFlow<LocationData?> = _location.asStateFlow()

    private val _updates = MutableSharedFlow<LocationData>(replay = 0, extraBufferCapacity = 256)
    override val updates: SharedFlow<LocationData> = _updates.asSharedFlow()

    private val _status = MutableStateFlow<LocationSourceStatus>(LocationSourceStatus.Available)
    override val status: StateFlow<LocationSourceStatus> = _status.asStateFlow()

    /** Simulation speed in m/s (default ~36 km/h). */
    var speedMps: Double = 10.0

    /** When true, the next emitted fix applies the lateral wrong-turn offset. */
    private var pendingWrongTurn = false
    private var wrongTurnArmed = false

    private var simJob: kotlinx.coroutines.Job? = null

    /** Starts following [path] from [startIndex], emitting a fix every [tickMs]. */
    fun startSimulation(
        scope: kotlinx.coroutines.CoroutineScope,
        path: List<com.hunternav.domain.model.Coordinate>,
        startIndex: Int = 0,
        tickMs: Long = 1_000L,
    ) {
        stopSimulation()
        if (path.size < 2) return
        _status.value = LocationSourceStatus.Available
        simJob = scope.launch {
            var i = startIndex.coerceIn(0, path.size - 1)
            var fraction = 0.0
            var simulatedTime = System.currentTimeMillis()
            while (i < path.size - 1) {
                val a = path[i]
                val b = path[i + 1]
                val step = speedMps * tickMs / 1000.0
                val segLen = a.distanceTo(b).coerceAtLeast(0.001)
                fraction += step / segLen
                while (fraction >= 1.0 && i < path.size - 1) {
                    fraction -= 1.0
                    i++
                }
                val segIndex = minOf(i, path.size - 2)
                val p0 = path[segIndex]
                val p1 = path[segIndex + 1]
                val lat = p0.latitude + (p1.latitude - p0.latitude) * fraction
                val lon = p0.longitude + (p1.longitude - p0.longitude) * fraction
                simulatedTime += tickMs

                val (lat2, lon2) = if (pendingWrongTurn) {
                    pendingWrongTurn = false
                    lat + WRONG_TURN_OFFSET_DEG to lon + WRONG_TURN_OFFSET_DEG
                } else {
                    lat to lon
                }

                emit(
                    LocationData(
                        latitude = lat2,
                        longitude = lon2,
                        accuracy = 8f,
                        speed = speedMps.toFloat(),
                        bearing = p0.bearingTo(p1).toFloat(),
                        timestamp = simulatedTime,
                    ),
                )
                delay(tickMs)
            }
        }
    }

    fun stopSimulation() {
        simJob?.cancel()
        simJob = null
    }

    /** Arms a one-shot lateral offset on the next emitted fix (wrong-turn demo). */
    fun injectWrongTurn() {
        pendingWrongTurn = true
        wrongTurnArmed = true
    }

    private fun emit(data: LocationData) {
        _location.value = data
        _updates.tryEmit(data)
    }

    override suspend fun start(highFrequency: Boolean) {
        _status.value = LocationSourceStatus.Available
    }

    override fun stop() {
        stopSimulation()
    }

    private companion object {
        const val WRONG_TURN_OFFSET_DEG = 0.0009
    }
}
