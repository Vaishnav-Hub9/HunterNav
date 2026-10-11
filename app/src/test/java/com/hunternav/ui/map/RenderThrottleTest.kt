package com.hunternav.ui.map

import com.hunternav.domain.model.Coordinate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Map publish dedupe (spec P1: severe map lag). Identical publishes on every 500 ms tick are
 * wasted main-thread work — these rules decide when a publish is actually necessary.
 */
class RenderThrottleTest {

    private val throttle = RenderThrottle()
    private val routeA = Any()
    private val routeB = Any()

    @Test
    fun `marker publishes once then only on real movement`() {
        assertTrue("first fix must publish", throttle.shouldPublishUserLocation(17.3850, 78.4867))
        assertFalse("identical fix must be skipped", throttle.shouldPublishUserLocation(17.3850, 78.4867))
        assertFalse("sub-centimeter jitter must be skipped", throttle.shouldPublishUserLocation(17.38500001, 78.48670001))
        assertTrue("a real move must publish", throttle.shouldPublishUserLocation(17.3859, 78.4867))
    }

    @Test
    fun `route line publishes only when the route object changes`() {
        assertTrue(throttle.shouldPublishRoute(routeA))
        assertFalse("same route instance must not republish", throttle.shouldPublishRoute(routeA))
        assertTrue("a replacement route must publish", throttle.shouldPublishRoute(routeB))
    }

    @Test
    fun `progress publishes only when the snapped vertex or route changes`() {
        throttle.shouldPublishRoute(routeA)
        assertTrue("first progress publish", throttle.shouldPublishProgress(routeA, 5))
        assertFalse("same vertex must not republish", throttle.shouldPublishProgress(routeA, 5))
        assertTrue("moved vertex must publish", throttle.shouldPublishProgress(routeA, 6))
        assertTrue("route change must publish even at the same vertex", throttle.shouldPublishProgress(routeB, 6))
    }

    @Test
    fun `a new route resets the progress dedupe key`() {
        throttle.shouldPublishRoute(routeA)
        throttle.shouldPublishProgress(routeA, 7)
        throttle.shouldPublishRoute(routeB)
        assertTrue("progress for the new route must publish", throttle.shouldPublishProgress(routeB, 7))
    }

    @Test
    fun `reset forces every publish again (style reload)`() {
        throttle.shouldPublishUserLocation(17.3850, 78.4867)
        throttle.shouldPublishRoute(routeA)
        throttle.shouldPublishProgress(routeA, 3)

        throttle.reset()

        assertTrue(throttle.shouldPublishUserLocation(17.3850, 78.4867))
        assertTrue(throttle.shouldPublishRoute(routeA))
        assertTrue(throttle.shouldPublishProgress(routeA, 3))
    }

    @Test
    fun `camera tick gates on movement and heading`() {
        val here = Coordinate(17.3850, 78.4867)
        assertTrue("first tick always runs", RenderThrottle.movedEnough(null, here))
        assertFalse("standing still must skip the tick", RenderThrottle.movedEnough(here, here))
        assertTrue("a 3 m move passes", RenderThrottle.movedEnough(here, Coordinate(17.38503, 78.4867)))
    }
}
