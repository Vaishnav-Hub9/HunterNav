package com.hunternav.display.nav

import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.DisplayNavigationState
import com.hunternav.domain.model.ManeuverType
import com.hunternav.domain.model.NavPhase

/**
 * Plain-data mirror of a turn-by-turn feed (`NavInfo`/`StepInfo`-style). Feed payloads are
 * decoded into this plain shape immediately, so everything downstream — including the
 * reducer below and all unit tests — is SDK-free and JVM-testable.
 */
data class NavFeedSnapshot(
    /** NavState int from the feed: 0 unknown, 1 enroute, 2 rerouting, 3 stopped. */
    val navState: Int = 0,
    val maneuver: Int = 0,
    val roadName: String? = null,
    val instructionText: String? = null,
    val distanceToStepMeters: Int? = null,
    val timeToStepSeconds: Int? = null,
    val remainingMeters: Int? = null,
    val remainingSeconds: Int? = null,
    val routeChanged: Boolean = false,
    val timestampMs: Long = 0L,
) {
    companion object {
        const val NAV_STATE_UNKNOWN = 0
        const val NAV_STATE_ENROUTE = 1
        const val NAV_STATE_REROUTING = 2
        const val NAV_STATE_STOPPED = 3
    }
}

/**
 * Location fix for the reducer: the road-snapped fix when available, plus the raw fix as
 * fallback and a validity flag for the bearing.
 */
data class NavLocationFix(
    val latitude: Double,
    val longitude: Double,
    val bearingDeg: Double?,
    val timestampMs: Long,
    val fromRoadSnappedProvider: Boolean,
)

/**
 * Pure, deterministic reducer that assembles the stable [DisplayNavigationState] consumed by
 * the renderer/transport from three independent input streams:
 *  - location fixes (road-snapped preferred, raw accepted),
 *  - turn-by-turn feed snapshots (maneuver, distances, ETA components, rerouting),
 *  - route geometry updates (with a version counter).
 *
 * Rules encoded here (all covered by tests):
 *  - the pointer position prefers the road-snapped fix;
 *  - heading comes from the location fix only when present and finite; otherwise the previous
 *    heading is kept but flagged invalid so the renderer stops rotating;
 *  - rerouting from either the feed state or an off-route signal sets [NavPhase.REROUTING];
 *  - new route geometry always bumps the route version so stale geometry can never linger;
 *  - ETA is computed as now + remaining seconds; null when the feed has no remaining time;
 *  - a location fix older than [staleLocationMs] flips the phase to ERROR (GPS lost) while
 *    keeping the last geometry so the rider still sees the route.
 */
class NavStateReducer(
    private val staleLocationMs: Long = DEFAULT_STALE_LOCATION_MS,
) {

    private var location: NavLocationFix? = null
    private var snapped: NavLocationFix? = null
    private var feed = NavFeedSnapshot()
    private var geometry: List<Coordinate> = emptyList()
    private var routeVersion: Long = 0L
    private var phase: NavPhase = NavPhase.IDLE
    private var lastHeading: Double = 0.0
    private var headingValid: Boolean = false

    /** True once any input (location/feed/geometry) has ever arrived — distinguishes "never
     * navigated" (stays IDLE) from "navigation running but GPS went silent" (ERROR). */
    private var everUpdated: Boolean = false

    /** Applies a location fix. Road-snapped fixes update the pointer; raw fixes are fallback. */
    fun onLocation(fix: NavLocationFix) {
        everUpdated = true
        if (fix.fromRoadSnappedProvider) {
            snapped = fix
        } else {
            // Raw only fills in when we have no snapped fix yet; snapped wins once available.
            if (snapped == null) location = fix
        }
        // Heading validity is decided by the fix that actually drives the pointer: valid only
        // when that fix carries a usable bearing. The last good heading is always kept.
        val active = snapped ?: location
        val bearing = active?.bearingDeg
        if (bearing != null && bearing.isFinite() && bearing in 0.0..360.0) {
            lastHeading = bearing
            headingValid = true
        } else {
            headingValid = false
        }
    }

    /** Applies a turn-by-turn feed snapshot. */
    fun onFeed(snapshot: NavFeedSnapshot) {
        everUpdated = true
        val rerouteCleared = feed.navState == NavFeedSnapshot.NAV_STATE_REROUTING &&
            snapshot.navState == NavFeedSnapshot.NAV_STATE_ENROUTE
        feed = snapshot
        if (rerouteCleared || snapshot.routeChanged) {
            // The feed announced a route change (reroute, alternate, traffic reroute…):
            // geometry must be re-read from the navigator; until then we keep the old line
            // but the version only bumps when new geometry actually arrives (see onGeometry).
            phase = NavPhase.NAVIGATING
        }
    }

    /** Applies freshly-read route geometry (already flattened and deduplicated by the caller). */
    fun onGeometry(points: List<Coordinate>) {
        if (points.isEmpty()) return
        everUpdated = true
        if (points != geometry) {
            geometry = points
            routeVersion += 1
        }
    }

    /** Marks the session idle (navigation stopped). */
    fun reset() {
        location = null
        snapped = null
        feed = NavFeedSnapshot()
        geometry = emptyList()
        routeVersion = 0
        phase = NavPhase.IDLE
        headingValid = false
        everUpdated = false
    }

    /** Builds the current display state. [nowMs] is injected for determinism in tests. */
    fun reduce(nowMs: Long): DisplayNavigationState {
        val fix = snapped ?: location
        val stale = fix == null || nowMs - fix.timestampMs > staleLocationMs

        val feedPhase = when (feed.navState) {
            NavFeedSnapshot.NAV_STATE_REROUTING -> NavPhase.REROUTING
            NavFeedSnapshot.NAV_STATE_ENROUTE -> NavPhase.NAVIGATING
            NavFeedSnapshot.NAV_STATE_STOPPED -> if (phase == NavPhase.ARRIVED) NavPhase.ARRIVED else NavPhase.IDLE
            else -> phase
        }
        phase = when {
            stale && !everUpdated -> NavPhase.IDLE // nothing ever arrived: not a session yet
            stale -> NavPhase.ERROR // navigation running but GPS went silent
            else -> feedPhase
        }

        val remainingSec = feed.remainingSeconds
        val eta = if (remainingSec != null && remainingSec > 0 && phase == NavPhase.NAVIGATING) {
            nowMs + remainingSec * 1000L
        } else null

        return DisplayNavigationState(
            latitude = fix?.latitude ?: 0.0,
            longitude = fix?.longitude ?: 0.0,
            bearing = lastHeading,
            speedMps = 0.0,
            maneuver = if (feed.maneuver != 0) GoogleManeuverMapper.toNormalized(feed.maneuver) else null,
            distanceToManeuverMeters = feed.distanceToStepMeters?.toDouble() ?: -1.0,
            roadName = feed.roadName,
            remainingDistanceMeters = feed.remainingMeters?.toDouble() ?: -1.0,
            remainingDurationSeconds = remainingSec?.toDouble() ?: -1.0,
            routeGeometry = geometry,
            offRoute = feed.navState == NavFeedSnapshot.NAV_STATE_REROUTING,
            rerouting = feed.navState == NavFeedSnapshot.NAV_STATE_REROUTING,
            destinationReached = phase == NavPhase.ARRIVED,
            phase = phase,
            snapped = snapped?.let { Coordinate(it.latitude, it.longitude) },
            headingValid = headingValid && !stale,
            routeVersion = routeVersion,
            timestampMs = fix?.timestampMs ?: nowMs,
            etaEpochMs = eta,
            simulated = false,
        )
    }

    companion object {
        const val DEFAULT_STALE_LOCATION_MS = 10_000L
    }
}
