package com.hunternav.data.routing

import com.hunternav.core.result.AppErrorKind
import com.hunternav.core.result.AppResult
import com.hunternav.data.network.HttpTransport
import com.hunternav.data.network.RequestRateLimiter
import com.hunternav.domain.model.Coordinate
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Mappls request handling against a fake transport (spec §6/§12), mirroring
 * [OsrmRoutingProviderTest]: the response schema is OSRM-compatible, so parsing/validation
 * contracts are identical — the Mappls-specific behaviour under test is auth fail-fast,
 * the traffic→non-traffic resource fallback, error mapping, bounded retries and the
 * ≤1 req/s rate limit. Production always uses the real provider — these tests fake the socket.
 */
class MapplsRoutingProviderTest {

    /** Records URLs and virtual-time timestamps; serves scripted responses/exceptions. */
    private class FakeTransport(private val handlers: List<suspend () -> Response>) : HttpTransport {
        var calls = 0
        val urls = mutableListOf<String>()
        val timestamps = mutableListOf<Long>()
        var nowProvider: () -> Long = { 0L }

        override suspend fun execute(request: Request): Response {
            urls += request.url.toString()
            timestamps += nowProvider()
            val handler = handlers[calls.coerceAtMost(handlers.size - 1)]
            calls++
            return handler()
        }
    }

    private fun response(code: Int, body: String = ""): Response = Response.Builder()
        .request(Request.Builder().url("https://route.mappls.example.org/").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    /** A minimal but structurally genuine success body (OSRM-compatible Mappls schema). */
    private val validBody = """
        {
          "code": "Ok",
          "routes": [{
            "distance": 300.0,
            "duration": 60.0,
            "geometry": {"type": "LineString", "coordinates": [[78.4867, 17.3850], [78.4877, 17.3860]]},
            "legs": [{
              "distance": 300.0,
              "duration": 60.0,
              "steps": [
                {"distance": 150.0, "duration": 30.0, "name": "Road A",
                 "maneuver": {"type": "depart", "location": [78.4867, 17.3850]},
                 "geometry": {"type": "LineString", "coordinates": [[78.4867, 17.3850], [78.4877, 17.3860]]}},
                {"distance": 150.0, "duration": 30.0, "name": "Road B",
                 "maneuver": {"type": "arrive", "location": [78.4877, 17.3860]},
                 "geometry": {"type": "LineString", "coordinates": [[78.4877, 17.3860]]}}
              ]
            }]
          }]
        }
    """.trimIndent()

    private val origin = Coordinate(17.3850, 78.4867)
    private val destination = Coordinate(17.4401, 78.3489)

    private fun kotlinx.coroutines.test.TestScope.provider(
        transport: HttpTransport,
        accessToken: String = "test-token",
    ) = MapplsRoutingProvider(
        baseUrl = "https://route.mappls.example.org/",
        accessToken = accessToken,
        networkClient = transport,
        rateLimiter = RequestRateLimiter(minIntervalMs = 1_000) { testScheduler.currentTime },
        nowMs = { testScheduler.currentTime },
    )

    @Test
    fun `valid response returns a validated route`() = runTest {
        val transport = FakeTransport(listOf({ response(200, validBody) }))
        transport.nowProvider = { testScheduler.currentTime }
        val provider = provider(transport)

        val result = provider.getRoutes(origin, destination, alternatives = false)

        val route = (result as AppResult.Success).value.single()
        assertEquals(300.0, route.distanceMeters, 0.01)
        assertEquals(60.0, route.durationSeconds, 0.01)
        assertEquals(2, route.geometry.size)
        // Lon,lat from the wire becomes domain (lat, lon) — never swapped.
        assertEquals(17.3850, route.geometry.first().latitude, 1e-6)
        assertEquals(78.4867, route.geometry.first().longitude, 1e-6)
        assertEquals(2, route.legs.sumOf { it.steps.size })
        // The request itself targets the traffic-aware resource with the biking profile.
        assertTrue("resource: ${transport.urls.single()}", transport.urls.single().contains("/route/direction/route_traffic/biking/"))
        assertTrue("auth: ${transport.urls.single()}", transport.urls.single().contains("access_token=test-token"))
    }

    @Test
    fun `blank access token fails fast naming the config key without any request`() = runTest {
        val transport = FakeTransport(listOf({ response(200, validBody) }))
        transport.nowProvider = { testScheduler.currentTime }
        val provider = provider(transport, accessToken = "")

        val result = provider.getRoutes(origin, destination, alternatives = false)

        val failure = result as AppResult.Failure
        assertEquals(AppErrorKind.SERVER, failure.kind)
        assertTrue("message must name the missing config: ${failure.message}", failure.message!!.contains("mappls.accessToken"))
        assertEquals("no unauthenticated request may fire", 0, transport.calls)
    }

    @Test
    fun `traffic resource rejected with 400 falls back to non-traffic resource once`() = runTest {
        val transport = FakeTransport(listOf({ response(400, "resource unsupported") }, { response(200, validBody) }))
        transport.nowProvider = { testScheduler.currentTime }
        val provider = provider(transport)

        val result = provider.getRoutes(origin, destination, alternatives = false)

        assertTrue("fallback must succeed: $result", result is AppResult.Success)
        assertEquals(2, transport.calls)
        assertTrue(
            "first attempt must target route_traffic: ${transport.urls[0]}",
            transport.urls[0].contains("/route_traffic/"),
        )
        assertTrue(
            "fallback must target route_adv: ${transport.urls[1]}",
            transport.urls[1].contains("/route_adv/"),
        )
    }

    @Test
    fun `auth rejection 401 is surfaced without retries or resource fallback`() = runTest {
        val transport = FakeTransport(listOf({ response(401, "Unauthorized") }))
        transport.nowProvider = { testScheduler.currentTime }
        val provider = provider(transport)

        val result = provider.getRoutes(origin, destination, alternatives = false)

        val failure = result as AppResult.Failure
        assertEquals(AppErrorKind.SERVER, failure.kind)
        assertTrue("message must name the 401: ${failure.message}", failure.message!!.contains("401"))
        assertEquals("auth failures must not retry or fall back", 1, transport.calls)
    }

    @Test
    fun `transient 5xx is retried with backoff and can succeed`() = runTest {
        val transport = FakeTransport(listOf({ response(500, "boom") }, { response(200, validBody) }))
        transport.nowProvider = { testScheduler.currentTime }
        val provider = provider(transport)

        val result = provider.getRoutes(origin, destination, alternatives = false)

        assertTrue(result is AppResult.Success)
        assertEquals(2, transport.calls)
        // Backoff (700 ms) + rate-limit spacing (1 s) ⇒ the retry starts well after the first.
        val gap = transport.timestamps[1] - transport.timestamps[0]
        assertTrue("retry came too early: ${gap}ms", gap >= 700)
    }

    @Test
    fun `network failures are retried a bounded number of times`() = runTest {
        val transport = FakeTransport(listOf({ throw IOException("no network") }))
        transport.nowProvider = { testScheduler.currentTime }
        val provider = provider(transport)

        val result = provider.getRoutes(origin, destination, alternatives = false)

        val failure = result as AppResult.Failure
        assertEquals(AppErrorKind.NETWORK, failure.kind)
        assertEquals("retries must be bounded", 3, transport.calls)
    }

    @Test
    fun `http 429 is reported as rate limiting after bounded retries`() = runTest {
        val transport = FakeTransport(listOf({ response(429, "Too Many Requests") }))
        transport.nowProvider = { testScheduler.currentTime }
        val provider = provider(transport)

        val result = provider.getRoutes(origin, destination, alternatives = false)

        val failure = result as AppResult.Failure
        assertTrue("message must name rate limiting: ${failure.message}", failure.message!!.contains("429"))
        assertEquals(3, transport.calls)
    }

    @Test
    fun `NoRoute maps to a no-route failure without retry`() = runTest {
        val transport = FakeTransport(listOf({ response(200, """{"code":"NoRoute","message":"No route found"}""") }))
        transport.nowProvider = { testScheduler.currentTime }
        val provider = provider(transport)

        val result = provider.getRoutes(origin, destination, alternatives = false)

        assertEquals(AppErrorKind.NO_ROUTE, (result as AppResult.Failure).kind)
        assertEquals(1, transport.calls)
    }

    @Test
    fun `empty routes array is a no-route failure, never sample data`() = runTest {
        val transport = FakeTransport(listOf({ response(200, """{"code":"Ok","routes":[]}""") }))
        transport.nowProvider = { testScheduler.currentTime }
        val provider = provider(transport)

        val result = provider.getRoutes(origin, destination, alternatives = false)

        assertEquals(AppErrorKind.NO_ROUTE, (result as AppResult.Failure).kind)
        assertEquals(1, transport.calls)
    }

    @Test
    fun `routes without maneuver steps are rejected as malformed navigation data`() = runTest {
        val noSteps = """
            {"code":"Ok","routes":[{"distance":100.0,"duration":20.0,
             "geometry":{"type":"LineString","coordinates":[[78.4867,17.3850],[78.4877,17.3860]]},
             "legs":[{"distance":100.0,"duration":20.0,"steps":[]}]}]}
        """.trimIndent()
        val transport = FakeTransport(listOf({ response(200, noSteps) }))
        transport.nowProvider = { testScheduler.currentTime }
        val provider = provider(transport)

        val result = provider.getRoutes(origin, destination, alternatives = false)

        val failure = result as AppResult.Failure
        assertEquals(AppErrorKind.PARSE, failure.kind)
        assertTrue(failure.message!!.contains("navigation data"))
        assertEquals(1, transport.calls)
    }

    @Test
    fun `requests to the same provider are rate limited to one per second`() = runTest {
        val transport = FakeTransport(listOf({ response(200, validBody) }))
        transport.nowProvider = { testScheduler.currentTime }
        val provider = provider(transport)

        provider.getRoutes(origin, destination, alternatives = false)
        provider.getRoutes(origin, Coordinate(17.3616, 78.4747), alternatives = false)

        assertEquals(2, transport.calls)
        val gap = transport.timestamps[1] - transport.timestamps[0]
        assertTrue("requests only ${gap}ms apart", gap >= 1_000)
    }
}
