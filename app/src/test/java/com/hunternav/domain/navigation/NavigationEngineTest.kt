package com.hunternav.domain.navigation

import com.hunternav.core.result.AppResult
import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.LocationData
import com.hunternav.domain.model.Route
import com.hunternav.domain.model.RouteLeg
import com.hunternav.domain.repository.RoutingProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the navigation brain: off-route hysteresis, sustained deviation, reroute cooldown,
 * single-flight rerouting, arrival detection and state transitions — all without a device.
 */
class NavigationEngineTest {

    /** Fake router: counts calls, can be gated to simulate an in-flight request. */
    private class FakeRoutingProvider(
        var nextRoute: Route,
    ) : RoutingProvider {
        var callCount = 0
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun getRoutes(
            origin: Coordinate,
            destination: Coordinate,
            alternatives: Boolean,
        ): AppResult<List<Route>> {
            callCount++
            gate?.await()
            return AppResult.Success(listOf(nextRoute))
        }
    }

    private val config = NavigationEngineConfig(
        offRouteEnterMeters = 40.0,
        offRouteExitMeters = 25.0,
        arrivalRadiusMeters = 20.0,
        maxAccuracyMeters = 50f,
        offRouteSustainMs = 4_000L,
        minDeviationMovementMeters = 30.0,
        rerouteCooldownMs = 15_000L,
    )

    /** Straight ~445 m route heading north along a meridian in Hyderabad. */
    private fun baseRoute() = Route(
        distanceMeters = 445.0,
        durationSeconds = 90.0,
        geometry = listOf(
            Coordinate(17.3850, 78.4867),
            Coordinate(17.3859, 78.4867),
            Coordinate(17.3868, 78.4867),
            Coordinate(17.3877, 78.4867),
            Coordinate(17.3886, 78.4867),
            Coordinate(17.3890, 78.4867),
        ),
        legs = listOf(
            RouteLeg(distanceMeters = 445.0, durationSeconds = 90.0, steps = emptyList()),
        ),
    )

    private fun fix(
        lat: Double,
        lon: Double,
        ts: Long,
        accuracy: Float = 8f,
    ) = LocationData(latitude = lat, longitude = lon, accuracy = accuracy, speed = 6f, bearing = 0f, timestamp = ts)

    // -----------------------------------------------------------------
    // Off-route detection
    // -----------------------------------------------------------------

    @Test
    fun `a single noisy fix far from route does not trigger reroute`() = runTest {
        val routing = FakeRoutingProvider(baseRoute())
        var now = 0L
        val engine = engine(routing, { now })

        engine.startNavigation(baseRoute(), fix(17.3850, 78.4867, 0))

        // One noisy fix 100 m east — accuracy is fine but deviation is not sustained.
        now = 1_000
        engine.onLocationUpdate(fix(17.3855, 78.4877, now))
        advanceUntilIdle()

        assertEquals(0, routing.callCount)
        assertFalse(engine.state.value.offRoute)
        assertFalse(engine.state.value.rerouting)
    }

    @Test
    fun `sustained deviation with meaningful movement triggers one reroute and replaces the route`() = runTest {
        val routing = FakeRoutingProvider(baseRoute())
        var now = 0L
        val engine = engine(routing, { now })

        val newRoute = baseRoute().let {
            it.copy(geometry = listOf(Coordinate(17.3860, 78.4881)) + it.geometry.drop(1))
        }
        routing.nextRoute = newRoute

        engine.startNavigation(baseRoute(), fix(17.3850, 78.4867, 0))

        // Deviated fixes: 106 m east of the route, moving while deviating, past sustain window.
        now = 1_000
        engine.onLocationUpdate(fix(17.3855, 78.4877, now)) // candidate starts
        now = 3_000
        engine.onLocationUpdate(fix(17.3857, 78.4879, now)) // still candidate
        now = 6_000
        engine.onLocationUpdate(fix(17.3860, 78.4881, now)) // sustained + moved -> latch & reroute
        advanceUntilIdle()

        // Exactly one reroute request; the new route replaces the old one and progress resets.
        assertEquals(1, routing.callCount)
        assertFalse(engine.state.value.rerouting)
        assertEquals(newRoute, engine.state.value.activeRoute)
        // Rider is at the head of the replacement route -> off-route latch cleared,
        // progress recomputed against the new geometry.
        assertFalse(engine.state.value.offRoute)
        assertTrue(engine.state.value.remainingDistance > 0.0)
    }

    @Test
    fun `poor accuracy fixes never trigger a reroute`() = runTest {
        val routing = FakeRoutingProvider(baseRoute())
        var now = 0L
        val engine = engine(routing, { now })

        engine.startNavigation(baseRoute(), fix(17.3850, 78.4867, 0))

        // Deviated but with 120 m accuracy: below required quality.
        for (t in listOf(1_000L, 3_000L, 6_000L, 9_000L)) {
            now = t
            engine.onLocationUpdate(fix(17.3860, 78.4890, now, accuracy = 120f))
        }
        advanceUntilIdle()

        assertEquals(0, routing.callCount)
        assertFalse(engine.state.value.rerouting)
    }

    @Test
    fun `only one reroute is in flight at a time`() = runTest {
        val routing = FakeRoutingProvider(baseRoute())
        var now = 0L
        val engine = engine(routing, { now })
        routing.gate = CompletableDeferred() // keeps the first request in flight

        engine.startNavigation(baseRoute(), fix(17.3850, 78.4867, 0))

        now = 1_000
        engine.onLocationUpdate(fix(17.3855, 78.4877, now))
        now = 6_000
        engine.onLocationUpdate(fix(17.3860, 78.4881, now))
        advanceUntilIdle()

        assertTrue(engine.state.value.rerouting)
        assertEquals(1, routing.callCount)

        // More deviated fixes while the first request is still in flight: no second request.
        now = 8_000
        engine.onLocationUpdate(fix(17.3865, 78.4886, now))
        now = 12_000
        engine.onLocationUpdate(fix(17.3870, 78.4891, now))
        advanceUntilIdle()
        assertEquals(1, routing.callCount)

        routing.gate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(engine.state.value.rerouting)
        assertEquals(1, routing.callCount)
    }

    @Test
    fun `reroute cooldown blocks a second request too soon`() = runTest {
        val routing = FakeRoutingProvider(baseRoute())
        var now = 0L
        val engine = engine(routing, { now })

        engine.startNavigation(baseRoute(), fix(17.3850, 78.4867, 0))

        // First reroute at t=6000 (sustain window satisfied).
        now = 1_000
        engine.onLocationUpdate(fix(17.3855, 78.4877, now))
        now = 6_000
        engine.onLocationUpdate(fix(17.3860, 78.4881, now))
        advanceUntilIdle()
        assertEquals(1, routing.callCount)

        // Same fake returns a route that still leaves the rider off -> new deviation candidate,
        // but cooldown (15 s from the first request) must block a new request.
        now = 7_000
        engine.onLocationUpdate(fix(17.3862, 78.4884, now))
        now = 12_000
        engine.onLocationUpdate(fix(17.3866, 78.4887, now))
        advanceUntilIdle()
        assertEquals(1, routing.callCount)

        // After the cooldown expires the engine may try again.
        now = 22_000
        engine.onLocationUpdate(fix(17.3870, 78.4891, now))
        now = 27_000
        engine.onLocationUpdate(fix(17.3875, 78.4895, now))
        advanceUntilIdle()
        assertEquals(2, routing.callCount)
    }

    // -----------------------------------------------------------------
    // Arrival
    // -----------------------------------------------------------------

    @Test
    fun `arrival within radius latches destination reached`() = runTest {
        val routing = FakeRoutingProvider(baseRoute())
        var now = 0L
        val engine = engine(routing, { now })

        val route = baseRoute()
        val destination = route.geometry.last()
        engine.startNavigation(route, fix(destination.latitude, destination.longitude - 0.003, 0))

        // Far away: not arrived.
        now = 1_000
        engine.onLocationUpdate(fix(destination.latitude - 0.002, destination.longitude, now))
        assertFalse(engine.state.value.destinationReached)

        // Within 20 m + accuracy credit.
        now = 2_000
        engine.onLocationUpdate(fix(destination.latitude - 0.00002, destination.longitude, now))
        assertTrue(engine.state.value.destinationReached)
        assertEquals(0.0, engine.state.value.remainingDistance, 5.0)

        // A jittery fix after arrival must NOT kick off rerouting (arrival latched).
        now = 4_000
        engine.onLocationUpdate(fix(destination.latitude - 0.001, destination.longitude, now))
        advanceUntilIdle()
        assertTrue(engine.state.value.destinationReached)
        assertEquals(0, routing.callCount)
        assertFalse(engine.state.value.rerouting)
    }

    // -----------------------------------------------------------------
    // Progress / state transitions
    // -----------------------------------------------------------------

    @Test
    fun `progress advances along the route as location moves`() = runTest {
        val routing = FakeRoutingProvider(baseRoute())
        var now = 0L
        val engine = engine(routing, { now })
        val route = baseRoute()

        engine.startNavigation(route, fix(17.3850, 78.4867, 0))
        val startRemaining = engine.state.value.remainingDistance

        now = 2_000
        engine.onLocationUpdate(fix(17.3868, 78.4867, now))
        val midRemaining = engine.state.value.remainingDistance

        assertTrue("remaining distance should decrease", midRemaining < startRemaining)
        assertTrue("remaining distance stays positive until arrival", midRemaining > 0)
        assertEquals(route, engine.state.value.activeRoute)
        assertFalse(engine.state.value.offRoute)
        assertFalse(engine.state.value.destinationReached)
    }

    @Test
    fun `state flips between on-route and off-route with hysteresis`() = runTest {
        val routing = FakeRoutingProvider(baseRoute())
        var now = 0L
        val engine = engine(routing, { now })

        routing.gate = CompletableDeferred() // keep reroute in flight; off-route latch must persist
        engine.startNavigation(baseRoute(), fix(17.3850, 78.4867, 0))

        // Enter deviation zone (between exit and enter thresholds) after sustain -> off.
        now = 1_000
        engine.onLocationUpdate(fix(17.3855, 78.4874, now)) // ~74 m off: > 40 enter
        now = 6_000
        engine.onLocationUpdate(fix(17.3860, 78.4877, now)) // sustained
        assertTrue(engine.state.value.offRoute)

        // Return into the hysteresis band (30 m: > exit 25 but < enter 40): stays off-route.
        now = 7_000
        engine.onLocationUpdate(fix(17.3862, 78.48697, now))
        assertTrue(engine.state.value.offRoute)

        // Back under the exit threshold: clears.
        now = 8_000
        engine.onLocationUpdate(fix(17.3863, 78.4867, now))
        assertFalse(engine.state.value.offRoute)
    }

    private fun TestScope.engine(routing: RoutingProvider, clock: () -> Long) = NavigationEngine(
        routingProvider = routing,
        config = config,
        outputs = emptyList(),
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        nowMs = clock,
    )
}
