package com.hunternav.display.nav

import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.ManeuverType
import com.hunternav.domain.model.NavPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the SDK-free reducer that assembles [com.hunternav.domain.model.DisplayNavigationState]
 * from location fixes, feed snapshots and route geometry.
 */
class NavStateReducerTest {

    private val now = 1_000_000L
    private fun fix(lat: Double = 17.385, lon: Double = 78.487, ageMs: Long = 0, snapped: Boolean = true, bearing: Double? = 90.0) =
        NavLocationFix(lat, lon, bearing, now - ageMs, snapped)

    @Test
    fun `never-started reducer stays idle`() {
        val state = NavStateReducer().reduce(now)
        assertEquals(NavPhase.IDLE, state.phase)
        assertFalse(state.headingValid)
        assertFalse(state.offRoute)
    }

    @Test
    fun `road-snapped fix wins over raw fix`() {
        val reducer = NavStateReducer()
        reducer.onLocation(fix(lat = 17.0, lon = 78.0, snapped = false))
        reducer.onLocation(fix(lat = 17.5, lon = 78.5, snapped = true))
        reducer.onFeed(NavFeedSnapshot(navState = NavFeedSnapshot.NAV_STATE_ENROUTE))

        val state = reducer.reduce(now)
        assertEquals(17.5, state.latitude, 1e-9)
        assertEquals(78.5, state.longitude, 1e-9)
        assertEquals(Coordinate(17.5, 78.5), state.snapped)
    }

    @Test
    fun `raw fix alone is used when no snapped fix exists`() {
        val reducer = NavStateReducer()
        reducer.onLocation(fix(lat = 17.1, lon = 78.1, snapped = false))
        reducer.onFeed(NavFeedSnapshot(navState = NavFeedSnapshot.NAV_STATE_ENROUTE))

        val state = reducer.reduce(now)
        assertEquals(17.1, state.latitude, 1e-9)
        assertEquals(NavPhase.NAVIGATING, state.phase)
    }

    @Test
    fun `rerouting feed state sets rerouting phase and flags`() {
        val reducer = NavStateReducer()
        reducer.onLocation(fix())
        reducer.onFeed(NavFeedSnapshot(navState = NavFeedSnapshot.NAV_STATE_REROUTING))

        val state = reducer.reduce(now)
        assertEquals(NavPhase.REROUTING, state.phase)
        assertTrue(state.offRoute)
        assertTrue(state.rerouting)
    }

    @Test
    fun `new geometry bumps route version, identical geometry does not`() {
        val reducer = NavStateReducer()
        val routeA = listOf(Coordinate(17.0, 78.0), Coordinate(17.1, 78.1))
        val routeB = listOf(Coordinate(17.0, 78.0), Coordinate(17.2, 78.2))

        reducer.onGeometry(routeA)
        assertEquals(1L, reducer.reduce(now).routeVersion)
        reducer.onGeometry(routeA)
        assertEquals(1L, reducer.reduce(now).routeVersion)
        reducer.onGeometry(routeB)
        assertEquals(2L, reducer.reduce(now).routeVersion)
        assertEquals(routeB, reducer.reduce(now).routeGeometry)
    }

    @Test
    fun `empty geometry is ignored`() {
        val reducer = NavStateReducer()
        reducer.onGeometry(listOf(Coordinate(17.0, 78.0), Coordinate(17.1, 78.1)))
        reducer.onGeometry(emptyList())
        assertEquals(1L, reducer.reduce(now).routeVersion)
    }

    @Test
    fun `stale location during navigation flips to ERROR but keeps geometry`() {
        val reducer = NavStateReducer()
        val route = listOf(Coordinate(17.0, 78.0), Coordinate(17.1, 78.1))
        reducer.onLocation(fix())
        reducer.onFeed(NavFeedSnapshot(navState = NavFeedSnapshot.NAV_STATE_ENROUTE))
        reducer.onGeometry(route)

        val staleNow = now + NavStateReducer.DEFAULT_STALE_LOCATION_MS + 1
        val state = reducer.reduce(staleNow)
        assertEquals(NavPhase.ERROR, state.phase)
        assertEquals(route, state.routeGeometry)
        assertFalse(state.headingValid)
    }

    @Test
    fun `ETA is now plus remaining seconds while navigating`() {
        val reducer = NavStateReducer()
        reducer.onLocation(fix())
        reducer.onFeed(
            NavFeedSnapshot(
                navState = NavFeedSnapshot.NAV_STATE_ENROUTE,
                remainingSeconds = 120,
                remainingMeters = 1500,
                distanceToStepMeters = 250,
                maneuver = 6, // TURN_LEFT
                roadName = "Main St",
            ),
        )
        val state = reducer.reduce(now)
        assertEquals(now + 120_000L, state.etaEpochMs)
        assertEquals(1500.0, state.remainingDistanceMeters, 1e-9)
        assertEquals(250.0, state.distanceToManeuverMeters, 1e-9)
        assertEquals(ManeuverType.LEFT, state.maneuver)
        assertEquals("Main St", state.roadName)
    }

    @Test
    fun `no ETA without remaining time`() {
        val reducer = NavStateReducer()
        reducer.onLocation(fix())
        reducer.onFeed(NavFeedSnapshot(navState = NavFeedSnapshot.NAV_STATE_ENROUTE))
        assertNull(reducer.reduce(now).etaEpochMs)
    }

    @Test
    fun `non-finite bearing keeps previous heading and flags it invalid`() {
        val reducer = NavStateReducer()
        reducer.onLocation(fix(bearing = 90.0))
        reducer.onLocation(fix(bearing = Double.NaN))
        reducer.onFeed(NavFeedSnapshot(navState = NavFeedSnapshot.NAV_STATE_ENROUTE))

        val state = reducer.reduce(now)
        assertEquals(90.0, state.bearing, 1e-9)
        assertFalse(state.headingValid)
    }

    @Test
    fun `missing bearing marks heading invalid but keeps last heading`() {
        val reducer = NavStateReducer()
        reducer.onLocation(fix(bearing = 90.0))
        reducer.onLocation(fix(bearing = null))
        reducer.onFeed(NavFeedSnapshot(navState = NavFeedSnapshot.NAV_STATE_ENROUTE))

        val state = reducer.reduce(now)
        assertEquals(90.0, state.bearing, 1e-9)
        assertFalse(state.headingValid)
    }

    @Test
    fun `reset returns the reducer to idle`() {
        val reducer = NavStateReducer()
        reducer.onLocation(fix())
        reducer.onFeed(NavFeedSnapshot(navState = NavFeedSnapshot.NAV_STATE_ENROUTE, remainingSeconds = 60))
        reducer.onGeometry(listOf(Coordinate(17.0, 78.0), Coordinate(17.1, 78.1)))
        reducer.reset()

        val state = reducer.reduce(now)
        assertEquals(NavPhase.IDLE, state.phase)
        assertTrue(state.routeGeometry.isEmpty())
        assertNull(state.etaEpochMs)
    }
}
