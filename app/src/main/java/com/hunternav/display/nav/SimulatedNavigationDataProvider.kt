package com.hunternav.display.nav

import com.hunternav.core.util.DebugLog
import com.hunternav.core.util.distanceMeters
import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.DisplayNavigationState
import com.hunternav.domain.model.ManeuverType
import com.hunternav.domain.model.NavPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Simulator for the display pipeline: synthesizes a plausible navigation session along a
 * generated dog-leg route from a start point to the destination, so the renderer, JPEG encoder
 * and transport can be exercised end-to-end without a Google API key, network access or motion.
 *
 * Behavior is deterministic given the same destination and clock ticks:
 *  - route: start → east-bound leg → turn toward destination (two maneuvers: RIGHT then ARRIVE);
 *  - constant simulated speed (default ~12 m/s) advanced per tick;
 *  - distance-to-maneuver and remaining distance/ETA recomputed each tick;
 *  - phase flips to ARRIVED (latched) when the end of the geometry is reached.
 *
 * Marked `simulated = true` in every published state so downstream consumers can never mistake
 * it for live navigation.
 */
class SimulatedNavigationDataProvider(
    private val scope: CoroutineScope,
    private val originProvider: () -> Coordinate,
    private val tickMs: Long = DEFAULT_TICK_MS,
    private val speedMps: Double = DEFAULT_SPEED_MPS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : NavigationDataProvider {

    override val providerName: String = "Simulator"

    private val _state = MutableStateFlow(idleState())
    override val state: StateFlow<DisplayNavigationState> = _state.asStateFlow()

    private var tickJob: Job? = null
    private var route: List<Coordinate> = emptyList()
    private var maneuverDistancesMeters: DoubleArray = DoubleArray(0) // cumulative dist per vertex
    private var progressMeters: Double = 0.0

    override fun isAvailable(): Boolean = true

    override suspend fun startNavigation(destination: Coordinate, destinationName: String?): Boolean {
        stopNavigation()
        val origin = originProvider()
        route = synthesizeRoute(origin, destination)
        if (route.size < 2) return false
        maneuverDistancesMeters = cumulativeDistances(route)
        progressMeters = 0.0
        val total = maneuverDistancesMeters.last()
        _state.value = DisplayNavigationState(
            latitude = origin.latitude,
            longitude = origin.longitude,
            bearing = bearingOf(route[0], route[1]),
            speedMps = speedMps,
            maneuver = ManeuverType.START,
            distanceToManeuverMeters = maneuverDistancesMeters[1],
            roadName = null,
            remainingDistanceMeters = total,
            remainingDurationSeconds = total / speedMps,
            routeGeometry = route,
            offRoute = false,
            rerouting = false,
            destinationReached = false,
            phase = NavPhase.NAVIGATING,
            snapped = origin,
            headingValid = true,
            routeVersion = 1L,
            timestampMs = nowMs(),
            etaEpochMs = nowMs() + (total / speedMps * 1000).toLong(),
            simulated = true,
        )
        tickJob = scope.launch {
            while (isActive) {
                delay(tickMs)
                advance()
            }
        }
        DebugLog.d(TAG, "simulated navigation started to $destinationName")
        return true
    }

    override fun stopNavigation() {
        tickJob?.cancel()
        tickJob = null
        route = emptyList()
        maneuverDistancesMeters = DoubleArray(0)
        progressMeters = 0.0
        _state.value = idleState()
    }

    /** Advances the simulated position by one tick. Exposed internally for tests. */
    private fun advance() {
        if (route.size < 2) return
        if (_state.value.phase == NavPhase.ARRIVED) return
        val total = maneuverDistancesMeters.last()
        progressMeters = (progressMeters + speedMps * (tickMs / 1000.0)).coerceAtMost(total)
        val position = interpolate(route, maneuverDistancesMeters, progressMeters)
        val remaining = total - progressMeters
        val nextVertexIdx = maneuverDistancesMeters.indexOfFirst { it > progressMeters + 0.5 }
            .let { if (it == -1) route.lastIndex else it }
        val distToManeuver = (maneuverDistancesMeters[nextVertexIdx] - progressMeters).coerceAtLeast(0.0)
        val maneuver = when {
            remaining <= ARRIVE_THRESHOLD_M -> ManeuverType.ARRIVE
            nextVertexIdx == route.lastIndex -> ManeuverType.CONTINUE
            else -> ManeuverType.RIGHT // the synthetic route only turns right at the mid vertex
        }
        val heading = if (nextVertexIdx in 1 until route.size) {
            bearingOf(position, route[nextVertexIdx])
        } else _state.value.bearing
        val arrived = remaining <= ARRIVE_THRESHOLD_M
        _state.value = _state.value.copy(
            latitude = position.latitude,
            longitude = position.longitude,
            bearing = heading,
            speedMps = if (arrived) 0.0 else speedMps,
            maneuver = maneuver,
            distanceToManeuverMeters = distToManeuver,
            remainingDistanceMeters = remaining,
            remainingDurationSeconds = remaining / speedMps,
            offRoute = false,
            rerouting = false,
            destinationReached = arrived,
            phase = if (arrived) NavPhase.ARRIVED else NavPhase.NAVIGATING,
            snapped = position,
            headingValid = true,
            timestampMs = nowMs(),
            etaEpochMs = nowMs() + (remaining / speedMps * 1000).toLong(),
            simulated = true,
        )
    }

    // ------------------------------------------------------------------ route synthesis

    /**
     * Builds an L-shaped two-leg route: a straight east-bound leg of ~1/3 the great-circle
     * distance, then a direct leg to the destination. Guarantees ≥2 distinct points.
     */
    private fun synthesizeRoute(origin: Coordinate, destination: Coordinate): List<Coordinate> {
        val direct = distanceMeters(origin.latitude, origin.longitude, destination.latitude, destination.longitude)
        if (direct < MIN_ROUTE_METERS) return listOf(origin, destination)
        val cornerLat = origin.latitude
        val cornerLon = origin.longitude + (direct / 3.0) / (METERS_PER_DEG_LAT * cos(Math.toRadians(origin.latitude)))
        val corner = Coordinate(
            cornerLat.coerceIn(-85.0, 85.0),
            ((cornerLon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0,
        )
        return listOf(origin, corner, destination)
    }

    private fun cumulativeDistances(points: List<Coordinate>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) {
            out[i] = out[i - 1] + distanceMeters(
                points[i - 1].latitude, points[i - 1].longitude,
                points[i].latitude, points[i].longitude,
            )
        }
        return out
    }

    /** Linear interpolation along the polyline at [distanceMeters] from the start. */
    private fun interpolate(points: List<Coordinate>, cumulative: DoubleArray, distanceMeters: Double): Coordinate {
        val last = points.lastIndex
        if (distanceMeters <= 0.0) return points[0]
        for (i in 1..last) {
            if (cumulative[i] >= distanceMeters) {
                val segLen = cumulative[i] - cumulative[i - 1]
                val t = if (segLen <= 0.0) 0.0 else (distanceMeters - cumulative[i - 1]) / segLen
                val a = points[i - 1]
                val b = points[i]
                return Coordinate(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
            }
        }
        return points[last]
    }

    private fun bearingOf(from: Coordinate, to: Coordinate): Double {
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun idleState(): DisplayNavigationState = DisplayNavigationState(
        latitude = 0.0,
        longitude = 0.0,
        bearing = 0.0,
        speedMps = 0.0,
        maneuver = null,
        distanceToManeuverMeters = -1.0,
        roadName = null,
        remainingDistanceMeters = -1.0,
        remainingDurationSeconds = -1.0,
        routeGeometry = emptyList(),
        offRoute = false,
        rerouting = false,
        destinationReached = false,
        phase = NavPhase.IDLE,
        simulated = true,
    )

    companion object {
        private const val TAG = "SimNavProvider"
        const val DEFAULT_TICK_MS = 1000L
        const val DEFAULT_SPEED_MPS = 12.0 // ~43 km/h, matching the in-app demo drive speed
        private const val MIN_ROUTE_METERS = 30.0
        private const val ARRIVE_THRESHOLD_M = 8.0
        private const val METERS_PER_DEG_LAT = 111_320.0
    }
}
