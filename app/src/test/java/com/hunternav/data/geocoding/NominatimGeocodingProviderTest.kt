package com.hunternav.data.geocoding

import com.hunternav.core.result.AppErrorKind
import com.hunternav.core.result.AppResult
import com.hunternav.data.network.HttpTransport
import com.hunternav.data.network.RequestRateLimiter
import com.hunternav.domain.model.Coordinate
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Public-Nominatim usage policy and failure handling (spec §5/§12):
 * ≤1 request/second app-wide, repeated queries cached, deliberate-failure states instead of
 * indefinite loading, and NO live GPS coordinates in search URLs.
 */
class NominatimGeocodingProviderTest {

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
        .request(Request.Builder().url("https://nominatim.example.org/").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private val searchBody = """
        [{"lat":"17.3616","lon":"78.4747","name":"Charminar",
          "display_name":"Charminar, Char Kaman, Hyderabad",
          "address":{"road":"Char Kaman","city":"Hyderabad"}}]
    """.trimIndent()

    private fun TestScope.provider(transport: FakeTransport): NominatimGeocodingProvider {
        transport.nowProvider = { testScheduler.currentTime }
        return NominatimGeocodingProvider(
            baseUrl = "https://nominatim.example.org/",
            networkClient = transport,
            rateLimiter = RequestRateLimiter(minIntervalMs = 1_000) { testScheduler.currentTime },
            nowMs = { testScheduler.currentTime },
        )
    }

    @Test
    fun `repeated queries are served from the cache without a second request`() = runTest {
        val transport = FakeTransport(listOf({ response(200, searchBody) }))
        val provider = provider(transport)

        val first = provider.search("Charminar", near = null)
        val second = provider.search("charminar ", near = null) // case/whitespace-insensitive

        assertEquals(1, transport.calls)
        val a = (first as AppResult.Success).value.single()
        val b = (second as AppResult.Success).value.single()
        assertEquals("Charminar", a.title)
        assertEquals(a, b)
        assertEquals(78.4747, b.coordinate.longitude, 1e-9)
    }

    @Test
    fun `distinct queries are rate limited to one per second`() = runTest {
        val transport = FakeTransport(listOf({ response(200, searchBody) }, { response(200, searchBody) }))
        val provider = provider(transport)

        provider.search("Charminar", near = null)
        provider.search("Gachibowli", near = null)

        assertEquals(2, transport.calls)
        val gap = transport.timestamps[1] - transport.timestamps[0]
        assertTrue("searches only ${gap}ms apart", gap >= 1_000)
    }

    @Test
    fun `reverse lookups share the same one-per-second limit`() = runTest {
        val transport = FakeTransport(listOf({ response(200, searchBody) }, { response(200, searchBody) }))
        val provider = provider(transport)

        provider.search("Charminar", near = null)
        provider.reverse(Coordinate(17.4000, 78.5000))

        assertEquals(2, transport.calls)
        val gap = transport.timestamps[1] - transport.timestamps[0]
        assertTrue("search+reverse only ${gap}ms apart", gap >= 1_000)
    }

    @Test
    fun `search without a hint sends no gps coordinates to the geocoder`() = runTest {
        val transport = FakeTransport(listOf({ response(200, searchBody) }))
        val provider = provider(transport)

        provider.search("Charminar", near = null)

        val url = transport.urls.single()
        assertTrue("query must be sent: $url", url.contains("q=Charminar"))
        assertTrue("no viewbox hint without explicit proximity: $url", !url.contains("viewbox"))
        assertTrue("no latitude leak: $url", !url.contains("lat="))
        assertTrue("no longitude leak: $url", !url.contains("lon="))
    }

    @Test
    fun `http 500 surfaces a server failure instead of an indefinite spinner`() = runTest {
        val transport = FakeTransport(listOf({ response(500, "boom") }))
        val provider = provider(transport)

        val result = provider.search("Charminar", near = null)

        val failure = result as AppResult.Failure
        assertEquals(AppErrorKind.SERVER, failure.kind)
        assertEquals(1, transport.calls) // bounded: the geocoder never retries on its own
    }

    @Test
    fun `malformed response maps to a parse failure`() = runTest {
        val transport = FakeTransport(listOf({ response(200, "<html>not json</html>") }))
        val provider = provider(transport)

        val result = provider.search("Charminar", near = null)

        assertEquals(AppErrorKind.PARSE, (result as AppResult.Failure).kind)
    }

    @Test
    fun `empty result list is a success so the screen can show no-results`() = runTest {
        val transport = FakeTransport(listOf({ response(200, "[]") }))
        val provider = provider(transport)

        val result = provider.search("zzz nowhere", near = null)

        assertTrue(result is AppResult.Success)
        assertTrue((result as AppResult.Success).value.isEmpty())
    }
}
