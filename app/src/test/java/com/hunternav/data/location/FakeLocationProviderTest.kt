package com.hunternav.data.location

import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.LocationData
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Demo mode simulator tests: the fake source must walk the route geometry and support
 * wrong-turn injection — proving navigation can be demonstrated without physical movement.
 */
class FakeLocationProviderTest {

    private val path = listOf(
        Coordinate(17.3850, 78.4867),
        Coordinate(17.3859, 78.4867),
        Coordinate(17.3868, 78.4867),
    )

    @Test
    fun `simulation emits moving fixes along the path`() = runTest {
        val provider = FakeLocationProvider()
        val collected = mutableListOf<LocationData>()
        val job = launch { provider.updates.collect { collected.add(it) } }

        provider.startSimulation(backgroundScope, path, startIndex = 0, tickMs = 1_000L)
        advanceTimeBy(30_000L)
        runCurrent()
        job.cancel()

        assertTrue("expected multiple fixes, got ${collected.size}", collected.size >= 3)
        // Heading north along the meridian: latitude increases, longitude stays constant.
        assertTrue(collected.last().latitude > collected.first().latitude)
        assertEquals(78.4867, collected.first().longitude, 1e-9)
        assertEquals(10.0, collected.first().speed.toDouble(), 0.01)
        // Perfect simulated accuracy.
        assertEquals(8f, collected.first().accuracy, 0.01f)
    }

    @Test
    fun `wrong turn injects a lateral offset on the next fix`() = runTest {
        val provider = FakeLocationProvider()
        val collected = mutableListOf<LocationData>()
        val job = launch { provider.updates.collect { collected.add(it) } }

        provider.startSimulation(backgroundScope, path, startIndex = 0, tickMs = 1_000L)
        advanceTimeBy(3_000L)
        runCurrent()
        val beforeCount = collected.size
        val lastSmoothLat = collected.last().latitude

        provider.injectWrongTurn()
        advanceTimeBy(1_000L)
        runCurrent()
        job.cancel()

        assertTrue("simulation should keep emitting after the injected turn", collected.size > beforeCount)
        val afterTurn = collected.last()
        // Normal tick advances ~10 m (0.00009°); the wrong turn adds ~100 m (0.0009°) laterally
        // on top of that, so the jump must clearly exceed a smooth step.
        val jump = afterTurn.latitude - lastSmoothLat
        assertTrue("expected an injected offset jump, got $jump", jump > 0.0005 || jump < -0.0005)
    }
}
