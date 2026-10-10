package com.hunternav.data.network

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The public Nominatim/OSRM demo services allow at most ~1 request/second; the limiter is
 * the single enforcement point for that policy (spec §5/§6).
 */
class RequestRateLimiterTest {

    @Test
    fun `permits are spaced at least minInterval apart`() = runTest {
        val limiter = RequestRateLimiter(minIntervalMs = 1_000) { testScheduler.currentTime }
        val stamps = mutableListOf<Long>()
        repeat(3) {
            limiter.withPermit { stamps += testScheduler.currentTime }
        }
        assertEquals(listOf(0L, 1_000L, 2_000L), stamps)
    }

    @Test
    fun `a slow operation does not block the next reservation`() = runTest {
        // Reservation happens up-front; the network call runs outside the lock. A hung first
        // request must not prevent the second caller from taking its (delayed) permit.
        val limiter = RequestRateLimiter(minIntervalMs = 1_000) { testScheduler.currentTime }
        val stamps = mutableListOf<Long>()
        limiter.withPermit { stamps += testScheduler.currentTime } // "hangs" after reserving
        launch { limiter.withPermit { stamps += testScheduler.currentTime } }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(0L, 1_000L), stamps)
    }

    @Test
    fun `first call is never delayed`() = runTest {
        val limiter = RequestRateLimiter(minIntervalMs = 5_000) { testScheduler.currentTime }
        var ranAt = -1L
        limiter.withPermit { ranAt = testScheduler.currentTime }
        assertTrue("first permit must be immediate, was $ranAt", ranAt == 0L)
    }
}
