package com.hunternav.domain.navigation

import com.hunternav.core.result.AppResult
import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.LocationData
import com.hunternav.domain.model.Maneuver
import com.hunternav.domain.model.ManeuverType
import com.hunternav.domain.model.Route
import com.hunternav.domain.model.RouteLeg
import com.hunternav.domain.model.RouteStep
import com.hunternav.domain.repository.RoutingProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // -----------------------------------------------------------------
    // Turn-by-turn step advancement + live metrics (spec: fix turn-by-turn/metrics)
    // -----------------------------------------------------------------

    /**
     * 3-step route over 6 vertices spaced ~100 m apart: START at v0, LEFT at v2,
     * ARRIVE at v5. [prepend] shifts everything by one vertex (used to put a rerouted
     * rider at the head of the replacement route).
     */
    private fun steppedRoute(
        durations: List<Double> = listOf(20.0, 30.0, 0.0),
        prepend: Coordinate? = null,
    ): Route {
        val base = (0..5).map { Coordinate(17.3850 + it * 0.0009, 78.4867) }
        val geometry = listOfNotNull(prepend) + base
        val offset = if (prepend != null) 1 else 0
        fun vertex(i: Int): Coordinate = geometry[i + offset]
        val steps = listOf(
            RouteStep(
                coordinate = vertex(0),
                maneuver = Maneuver(ManeuverType.START, vertex(0)),
                distanceMeters = 200.0,
                durationSeconds = durations[0],
                roadName = "Road A",
            ),
            RouteStep(
                coordinate = vertex(2),
                maneuver = Maneuver(ManeuverType.LEFT, vertex(2)),
                distanceMeters = 300.0,
                durationSeconds = durations[1],
                roadName = "Road B",
            ),
            RouteStep(
                coordinate = vertex(5),
                maneuver = Maneuver(ManeuverType.ARRIVE, vertex(5)),
                distanceMeters = 0.0,
                durationSeconds = durations[2],
                roadName = null,
            ),
        )
        val total = durations.sum()
        return Route(
            distanceMeters = 500.0,
            durationSeconds = total,
            geometry = geometry,
            legs = listOf(RouteLeg(distanceMeters = 500.0, durationSeconds = total, steps = steps)),
        )
    }

    @Test
    fun `maneuver instruction and metrics advance with position along the route`() = runTest {
        val routing = FakeRoutingProvider(steppedRoute())
        var now = 0L
        val engine = engine(routing, { now })
        val route = steppedRoute()

        // Start of the route: departing step active, LEFT ~200 m ahead, full metrics.
        engine.startNavigation(route, fix(17.3850, 78.4867, 0))
        var s = engine.state.value
        assertEquals(ManeuverType.START, s.currentStep?.maneuver?.type)
        assertEquals("Road A", s.currentStep?.roadName)
        assertEquals(ManeuverType.LEFT, s.nextStep?.maneuver?.type)
        assertEquals(200.0, s.distanceToNextManeuver, 15.0)
        assertEquals(500.0, s.remainingDistance, 15.0)
        assertEquals(50.0, s.remainingDuration, 4.0)

        // Halfway to the first turn: SAME instruction ahead, distance/duration decreased.
        now = 1_000
        engine.onLocationUpdate(fix(17.3859, 78.4867, now))
        s = engine.state.value
        assertEquals(ManeuverType.START, s.currentStep?.maneuver?.type)
        assertEquals(ManeuverType.LEFT, s.nextStep?.maneuver?.type)
        assertEquals(100.0, s.distanceToNextManeuver, 12.0)
        assertEquals(400.0, s.remainingDistance, 12.0)
        // Step 0 has 10 of 20 s left + full step 1 (30 s) + 0 s arrival = 40 s.
        assertEquals(40.0, s.remainingDuration, 4.0)

        // Past the turn: LEFT is the CURRENT step, ARRIVE is next, ~200 m out.
        now = 2_000
        engine.onLocationUpdate(fix(17.3877, 78.4867, now))
        s = engine.state.value
        assertEquals(ManeuverType.LEFT, s.currentStep?.maneuver?.type)
        assertEquals("Road B", s.currentStep?.roadName)
        assertEquals(ManeuverType.ARRIVE, s.nextStep?.maneuver?.type)
        assertEquals(200.0, s.distanceToNextManeuver, 12.0)
        assertEquals(200.0, s.remainingDistance, 12.0)
        // 2 of 3 segments of the 30 s step remain → 30 * 2/3 + 0 = 20 s.
        assertEquals(20.0, s.remainingDuration, 4.0)
    }

    // -----------------------------------------------------------------
    // Arrival vs the SELECTED destination (spec: verify destination changes)
    // -----------------------------------------------------------------

    @Test
    fun `arrival is checked against the selected destination`() = runTest {
        val routing = FakeRoutingProvider(baseRoute())
        var now = 0L
        val engine = engine(routing, { now })
        val selected = Coordinate(17.3950, 78.4867) // ~667 m BEYOND the route's geometry end

        engine.startNavigation(baseRoute(), fix(17.3850, 78.4867, 0), arrivalDestination = selected)

        // On the route but far from both the selected destination and its end: not arrived.
        now = 1_000
        engine.onLocationUpdate(fix(17.3850, 78.4867, now))
        assertFalse(engine.state.value.destinationReached)

        // At the selected destination (nowhere near the route's geometry): arrival triggers.
        now = 2_000
        engine.onLocationUpdate(fix(17.3950, 78.4867, now))
        assertTrue(engine.state.value.destinationReached)
        assertEquals("arrival must not need a reroute", 0, routing.callCount)
    }

    @Test
    fun `rerouting keeps the selected destination as the arrival target`() = runTest {
        val routing = FakeRoutingProvider(baseRoute())
        var now = 0L
        val engine = engine(routing, { now })
        val selected = Coordinate(17.3950, 78.4867)

        engine.startNavigation(baseRoute(), fix(17.3850, 78.4867, 0), arrivalDestination = selected)

        // Sustained deviation → exactly one reroute that replaces the route.
        now = 1_000
        engine.onLocationUpdate(fix(17.3855, 78.4877, now))
        now = 3_000
        engine.onLocationUpdate(fix(17.3857, 78.4879, now))
        now = 6_000
        engine.onLocationUpdate(fix(17.3860, 78.4881, now))
        advanceUntilIdle()
        assertEquals(1, routing.callCount)
        assertFalse(engine.state.value.rerouting)

        // After the reroute the arrival target is STILL the selected destination: reaching it
        // completes the trip (it would NOT if the target had reverted to the geometry end).
        now = 8_000
        engine.onLocationUpdate(fix(17.3950, 78.4867, now))
        assertTrue(engine.state.value.destinationReached)
        assertEquals("arrival must not trigger another reroute", 1, routing.callCount)
    }

    @Test
    fun `rerouting recomputes remaining metrics for the replacement route`() = runTest {
        val routing = FakeRoutingProvider(baseRoute())
        var now = 0L
        val engine = engine(routing, { now })

        // Replacement route: different step durations (500 s total) and a different head.
        routing.nextRoute = steppedRoute(
            durations = listOf(100.0, 400.0, 0.0),
            prepend = Coordinate(17.3860, 78.4881),
        )

        engine.startNavigation(baseRoute(), fix(17.3850, 78.4867, 0))
        val before = engine.state.value
        assertEquals(90.0, before.remainingDuration, 5.0)

        now = 1_000
        engine.onLocationUpdate(fix(17.3855, 78.4877, now))
        now = 3_000
        engine.onLocationUpdate(fix(17.3857, 78.4879, now))
        now = 6_000
        engine.onLocationUpdate(fix(17.3860, 78.4881, now))
        advanceUntilIdle()

        assertEquals(1, routing.callCount)
        val after = engine.state.value
        assertEquals(routing.nextRoute, after.activeRoute)
        // Metrics were rebuilt from the REPLACEMENT route, not carried over: rider is at its
        // head, so remaining duration equals the new route's full 500 s of step time.
        assertEquals(500.0, after.remainingDuration, 25.0)
        assertTrue("remaining distance must reflect the new geometry", after.remainingDistance > 600.0)
        assertTrue(after.remainingDistance < 760.0)
    }

    // -----------------------------------------------------------------
    // Trip lifecycle, arrival-once, GPS-noise clamps (reliability audit §9/§11)
    // -----------------------------------------------------------------

    @Test
    fun `arrival is announced exactly once for a trip`() = runTest {
        val routing = FakeRoutingProvider(baseRoute())
        var now = 0L
        val engine = engine(routing, { now })
        val arrivals = mutableListOf<NavigationEvent.ArrivedAtDestination>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            engine.events.collect { event ->
                if (event is NavigationEvent.ArrivedAtDestination) arrivals += event
            }
        }

        engine.startNavigation(baseRoute(), fix(17.3850, 78.4867, 0))
        // Several fixes inside the arrival radius — the event fires only on the first.
        now = 1_000
        engine.onLocationUpdate(fix(17.3890, 78.4867, now))
        now = 2_000
        engine.onLocationUpdate(fix(17.38901, 78.48671, now))
        now = 3_000
        engine.onLocationUpdate(fix(17.38899, 78.4867, now))

        assertEquals("arrival must be presented once", 1, arrivals.size)
        assertTrue(engine.state.value.destinationReached)
        collector.cancel()
    }

    @Test
    fun `stopping navigation during an in-flight reroute ignores the late response`() = runTest {
        val routing = FakeRoutingProvider(baseRoute())
        var now = 0L
        val engine = engine(routing, { now })
        routing.gate = CompletableDeferred()

        engine.startNavigation(baseRoute(), fix(17.3850, 78.4867, 0))
        now = 1_000
        engine.onLocationUpdate(fix(17.3855, 78.4877, now))
        now = 3_000
        engine.onLocationUpdate(fix(17.3857, 78.4879, now))
        now = 6_000
        engine.onLocationUpdate(fix(17.3860, 78.4881, now))
        assertEquals("reroute must have been requested", 1, routing.callCount)

        engine.stopNavigation()
        routing.gate!!.complete(Unit)
        advanceUntilIdle()

        assertNull("a late reroute response must not resurrect a stopped trip", engine.state.value.activeRoute)
        assertFalse(engine.state.value.rerouting)
        assertFalse(engine.state.value.destinationReached)
    }

    @Test
    fun `small backward gps noise does not increase remaining distance`() = runTest {
        val routing = FakeRoutingProvider(baseRoute())
        var now = 0L
        val engine = engine(routing, { now })

        engine.startNavigation(baseRoute(), fix(17.3850, 78.4867, 0))
        now = 1_000
        engine.onLocationUpdate(fix(17.3859, 78.4867, now)) // ~100 m along (vertex 1)
        val atVertex = engine.state.value.remainingDistance

        now = 2_000
        engine.onLocationUpdate(fix(17.38581, 78.4867, now)) // ~10 m backward (GPS noise)
        assertEquals(
            "remaining must not jump up on a tiny backtrack",
            atVertex,
            engine.state.value.remainingDistance,
            0.001,
        )

        // A genuine return to an earlier portion of the route IS accepted (> tolerance).
        now = 3_000
        engine.onLocationUpdate(fix(17.3850, 78.4867, now))
        assertTrue(engine.state.value.remainingDistance > atVertex)
    }

    @Test
    fun `route version increments on start and on reroute replacement`() = runTest {
        val routing = FakeRoutingProvider(baseRoute())
        var now = 0L
        val engine = engine(routing, { now })

        engine.startNavigation(baseRoute(), fix(17.3850, 78.4867, 0))
        assertEquals(1L, engine.state.value.routeVersion)

        now = 1_000
        engine.onLocationUpdate(fix(17.3855, 78.4877, now))
        now = 3_000
        engine.onLocationUpdate(fix(17.3857, 78.4879, now))
        now = 6_000
        engine.onLocationUpdate(fix(17.3860, 78.4881, now))
        advanceUntilIdle()

        assertEquals(1, routing.callCount)
        assertEquals("the replacement route must bump the version", 2L, engine.state.value.routeVersion)
    }

    private fun TestScope.engine(routing: RoutingProvider, clock: () -> Long) = NavigationEngine(
        routingProvider = routing,
        config = config,
        outputs = emptyList(),
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        nowMs = clock,
    )
}
