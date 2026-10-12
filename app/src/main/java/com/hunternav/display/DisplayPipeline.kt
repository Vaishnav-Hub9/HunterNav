package com.hunternav.display

import com.hunternav.core.util.DebugLog
import com.hunternav.display.nav.NavigationDataProvider
import com.hunternav.display.render.FrameRenderer
import com.hunternav.display.render.JpegEncoder
import com.hunternav.display.transport.DisplayTransport
import com.hunternav.display.transport.FrameProtocol
import com.hunternav.domain.model.DisplayNavigationState
import com.hunternav.domain.model.NavPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Orchestrates the handlebar display pipeline:
 *
 * ```
 * NavigationDataProvider ─▶ DisplayNavigationState ─▶ FrameRenderer (bitmap)
 *                                                        │ JpegEncoder
 *                                                        ▼
 *                                    FrameProtocol frames ─▶ DisplayTransport (WebSocket)
 * ```
 *
 * Cadence: guidance metadata (maneuver, distances, phase, ETA) is pushed as META_ONLY
 * messages at ~[META_HZ] Hz — these are a few dozen bytes, so the rider sees turn changes
 * immediately. Full JPEG IMAGE_FRAMEs are rendered and pushed at ~[IMAGE_HZ] Hz while
 * navigating, and much more sparsely when idle (or not at all if the transport is down),
 * keeping Wi-Fi airtime and encode cost bounded.
 *
 * Threading: one dedicated single-threaded dispatcher renders and encodes — the renderer
 * owns its bitmap and is not reentrant, and [collectLatest] on the state flow means a slow
 * render is superseded rather than queued.
 */
class DisplayPipeline(
    private val provider: NavigationDataProvider,
    private val transport: DisplayTransport,
    private val renderer: FrameRenderer = FrameRenderer(),
    private val encoder: JpegEncoder = JpegEncoder(),
    scope: CoroutineScope? = null,
) {

    private val pipelineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var metaJob: Job? = null
    private var imageJob: Job? = null
    private var connectionJob: Job? = null

    private var sequence = 0
    private var lastRenderedRouteVersion = -1L

    val activeProvider: NavigationDataProvider get() = provider

    fun start() {
        if (metaJob?.isActive == true) return
        DebugLog.d(TAG, "pipeline started (provider=${provider.providerName})")
        connectionJob = pipelineScope.launch {
            transport.connected.collect { connected ->
                if (connected) {
                    // Fresh connection: per-protocol contract, sequences restart per connection.
                    synchronized(this@DisplayPipeline) { sequence = 0 }
                    lastRenderedRouteVersion = -1L
                }
            }
        }
        transport.connect()
        metaJob = pipelineScope.launch {
            while (isActive) {
                sendMeta(provider.state.value)
                delay(META_PERIOD_MS)
            }
        }
        imageJob = pipelineScope.launch(Dispatchers.Default) {
            while (isActive) {
                val state = provider.state.value
                if (transport.connected.value && shouldRenderFrame(state)) {
                    renderAndSend(state)
                }
                delay(if (state.phase == NavPhase.NAVIGATING) IMAGE_PERIOD_MS else IDLE_IMAGE_PERIOD_MS)
            }
        }
    }

    fun stop() {
        metaJob?.cancel()
        imageJob?.cancel()
        connectionJob?.cancel()
        metaJob = null
        imageJob = null
        connectionJob = null
        transport.disconnect()
        DebugLog.d(TAG, "pipeline stopped")
    }

    /** Starts (or restarts) navigation on the active provider. Returns false if it cannot. */
    suspend fun startNavigation(destination: com.hunternav.domain.model.Coordinate, name: String?): Boolean =
        provider.startNavigation(destination, name)

    fun stopNavigation() = provider.stopNavigation()

    // ------------------------------------------------------------------ internals

    /**
     * Frame policy: always re-render when the route changed (new geometry), otherwise at the
     * image cadence while navigating; idle sessions only refresh occasionally so the last
     * picture stays fresh without burning battery.
     */
    private fun shouldRenderFrame(state: DisplayNavigationState): Boolean = when (state.phase) {
        NavPhase.IDLE -> state.routeVersion != lastRenderedRouteVersion
        NavPhase.ARRIVED -> state.routeVersion != lastRenderedRouteVersion
        NavPhase.NAVIGATING, NavPhase.REROUTING, NavPhase.ERROR -> true
    }

    private fun renderAndSend(state: DisplayNavigationState) {
        val bitmap = try {
            renderer.render(state)
        } catch (e: RuntimeException) {
            DebugLog.d(TAG, "render failed: ${e.message}")
            return
        }
        val jpeg = encoder.encode(bitmap) ?: return
        lastRenderedRouteVersion = state.routeVersion
        val bytes = synchronized(this) {
            FrameProtocol.encodeFrame(
                sequence = sequence++,
                width = renderer.frameWidth,
                height = renderer.frameHeight,
                meta = state.toWireMeta(),
                jpeg = jpeg,
            )
        }
        transport.send(bytes)
    }

    private fun sendMeta(state: DisplayNavigationState) {
        if (!transport.connected.value) return
        val bytes = synchronized(this) {
            FrameProtocol.encodeMeta(
                sequence = sequence++,
                width = renderer.frameWidth,
                height = renderer.frameHeight,
                meta = state.toWireMeta(),
            )
        }
        transport.send(bytes)
    }

    private fun DisplayNavigationState.toWireMeta(): FrameProtocol.NavMeta {
        var flags = 0
        if (simulated) flags = flags or FrameProtocol.FLAG_SIMULATED
        if (headingValid) flags = flags or FrameProtocol.FLAG_HEADING_VALID
        if (phase == NavPhase.ERROR) flags = flags or FrameProtocol.FLAG_DEGRADED_TILES
        return FrameProtocol.NavMeta(
            navState = when (phase) {
                NavPhase.IDLE -> FrameProtocol.NAV_IDLE
                NavPhase.NAVIGATING -> FrameProtocol.NAV_NAVIGATING
                NavPhase.REROUTING -> FrameProtocol.NAV_REROUTING
                NavPhase.ARRIVED -> FrameProtocol.NAV_ARRIVED
                NavPhase.ERROR -> FrameProtocol.NAV_ERROR
            },
            maneuver = maneuverToWire(maneuver),
            flags = flags,
            distanceToManeuverM = distanceToManeuverMeters.toInt(),
            remainingMeters = remainingDistanceMeters.toInt(),
            etaEpochSec = (etaEpochMs ?: 0L) / 1000L,
        )
    }

    companion object {
        private const val TAG = "DisplayPipeline"
        const val META_PERIOD_MS = 100L // 10 Hz guidance metadata
        const val IMAGE_PERIOD_MS = 250L // 4 Hz JPEG frames while navigating
        const val IDLE_IMAGE_PERIOD_MS = 2_000L // idle refresh

        /** Maps normalized maneuver types onto the compact wire codes the firmware draws. */
        fun maneuverToWire(maneuver: com.hunternav.domain.model.ManeuverType?): Int = when (maneuver) {
            null -> FrameProtocol.MANEUVER_UNKNOWN
            com.hunternav.domain.model.ManeuverType.START -> FrameProtocol.MANEUVER_DEPART
            com.hunternav.domain.model.ManeuverType.CONTINUE -> FrameProtocol.MANEUVER_STRAIGHT
            com.hunternav.domain.model.ManeuverType.SLIGHT_LEFT -> FrameProtocol.MANEUVER_SLIGHT_LEFT
            com.hunternav.domain.model.ManeuverType.SLIGHT_RIGHT -> FrameProtocol.MANEUVER_SLIGHT_RIGHT
            com.hunternav.domain.model.ManeuverType.LEFT -> FrameProtocol.MANEUVER_LEFT
            com.hunternav.domain.model.ManeuverType.RIGHT -> FrameProtocol.MANEUVER_RIGHT
            com.hunternav.domain.model.ManeuverType.SHARP_LEFT -> FrameProtocol.MANEUVER_SHARP_LEFT
            com.hunternav.domain.model.ManeuverType.SHARP_RIGHT -> FrameProtocol.MANEUVER_SHARP_RIGHT
            com.hunternav.domain.model.ManeuverType.U_TURN -> FrameProtocol.MANEUVER_UTURN
            com.hunternav.domain.model.ManeuverType.ROUNDABOUT -> FrameProtocol.MANEUVER_ROUNDABOUT
            com.hunternav.domain.model.ManeuverType.ARRIVE -> FrameProtocol.MANEUVER_ARRIVE
        }
    }
}
