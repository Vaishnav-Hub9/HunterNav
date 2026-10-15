package com.hunternav.di

import android.content.ContextWrapper
import com.hunternav.core.result.AppErrorKind
import com.hunternav.core.result.AppResult
import com.hunternav.domain.model.Coordinate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Mappls migration decision: HunterNav has NO OSRM/other-provider fallback.
 * When `mappls.accessToken` is unset (always the case in unit-test builds — the token is
 * never committed), the container must surface a clear configuration error instead of
 * silently routing through another engine whose free-flow, car-profile numbers produced
 * wrong distance/ETA on the road.
 */
class AppContainerRoutingTest {

    @Test
    fun `missing token yields clear configuration error, not another provider`() = runTest {
        val container = AppContainer(ContextWrapper(null))
        assertEquals("test builds never carry a token", "mappls-unconfigured", container.routingProviderTag)

        val result = container.routingProvider.getRoutes(
            origin = Coordinate(17.3850, 78.4867),
            destination = Coordinate(17.4326, 78.3470),
            alternatives = false,
        )

        assertTrue("expected a failure, got: $result", result is AppResult.Failure)
        result as AppResult.Failure
        assertEquals(AppErrorKind.UNKNOWN, result.kind)
        val message = result.message.orEmpty()
        assertTrue("error must name the missing key: $message", message.contains("mappls.accessToken"))
        assertTrue("error must point at the docs: $message", message.contains("docs/mappls.md"))
    }
}
