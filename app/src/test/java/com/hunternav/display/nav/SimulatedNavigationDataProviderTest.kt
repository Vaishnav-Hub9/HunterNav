package com.hunternav.display.nav

import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.NavPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Simulator tests (virtual time): the simulated provider must walk the route, keep publishing
 * a plausible state, latch ARRIVED, and always mark itself as simulated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedNavigationDataProviderTest {

    private val origin = Coordinate(17.3850, 78.4867)
    private val destination = Coordinate(17.3950, 78.4967) // ~1.5 km away

    @Test
    fun `simulator starts navigating along a generated route`() = runTest {
        val provider = SimulatedNavigationDataProvider(this, { origin })

        assertTrue(provider.startNavigation(destination, "Test Dest"))
        val state = provider.state.value

        assertTrue(state.simulated)
        assertEquals(NavPhase.NAVIGATING, state.phase)
        assertTrue(state.routeGeometry.size >= 2)
        assertEquals(origin.latitude, state.latitude, 1e-9)
        assertTrue(state.remainingDistanceMeters > 0)
        assertTrue(state.etaEpochMs != null)

        provider.stopNavigation()
    }

    @Test
    fun `simulator advances and reports decreasing remaining distance`() = runTest {
        val provider = SimulatedNavigationDataProvider(this, { origin })
        provider.startNavigation(destination, null)
        val startRemaining = provider.state.value.remainingDistanceMeters

        advanceTimeBy(30_000) // 30 virtual seconds
        val mid = provider.state.value
        assertTrue(mid.simulated)
        assertEquals(NavPhase.NAVIGATING, mid.phase)
        assertTrue(mid.remainingDistanceMeters < startRemaining)
        // 30 s at 12 m/s ≈ 360 m traveled ± segment interpolation.
        assertTrue(startRemaining - mid.remainingDistanceMeters in 200.0..520.0)

        provider.stopNavigation()
    }

    @Test
    fun `simulator latches ARRIVED at the end of the route`() = runTest {
        val provider = SimulatedNavigationDataProvider(this, { origin })
        provider.startNavigation(destination, null)

        advanceTimeBy(600_000) // 10 virtual minutes — far beyond the ~2-3 km route
        val arrived = provider.state.value
        assertEquals(NavPhase.ARRIVED, arrived.phase)
        assertTrue(arrived.destinationReached)
        assertEquals(0.0, arrived.speedMps, 1e-9)
        assertTrue(arrived.simulated)

        provider.stopNavigation()
    }

    @Test
    fun `stopNavigation resets to idle`() = runTest {
        val provider = SimulatedNavigationDataProvider(this, { origin })
        provider.startNavigation(destination, null)
        provider.stopNavigation()

        val state = provider.state.value
        assertEquals(NavPhase.IDLE, state.phase)
        assertTrue(state.routeGeometry.isEmpty())
        assertFalse(state.destinationReached)
    }

    @Test
    fun `simulator is always available`() = runTest {
        val provider = SimulatedNavigationDataProvider(this, { origin })
        assertTrue(provider.isAvailable())
        provider.stopNavigation()
    }
}
