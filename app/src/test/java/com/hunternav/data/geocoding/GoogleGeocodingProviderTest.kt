package com.hunternav.data.geocoding

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

/**
 * JVM tests for the Google Geocoding API provider: response parsing, the coordinate
 * contract (results without coordinates are dropped; the pressed reverse coordinate stays
 * authoritative), status mapping and the missing-key fail-fast path.
 */
class GoogleGeocodingProviderTest {

    private class FakeTransport : HttpTransport {
        val requests = mutableListOf<Request>()
        private val queue = ArrayDeque<Pair<Int, String>>()

        fun enqueue(code: Int, body: String) = queue.addLast(code to body)

        override suspend fun execute(request: Request): Response {
            requests += request
            val (code, body) = queue.removeFirst()
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "Error")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun provider(transport: HttpTransport) = GoogleGeocodingProvider(
        baseUrl = "https://maps.googleapis.com",
        apiKey = "test-key",
        networkClient = transport,
        rateLimiter = RequestRateLimiter(0L),
    )

    @Test
    fun `blank api key fails fast on search and reverse`() = runTest {
        val p = GoogleGeocodingProvider(
            baseUrl = "https://maps.googleapis.com",
            apiKey = "",
            networkClient = FakeTransport(),
        )
        val search = p.search("charminar", near = null)
        assertTrue(search is AppResult.Failure)
        assertTrue((search as AppResult.Failure).message.orEmpty().contains("google.maps.apiKey"))

        val reverse = p.reverse(Coordinate(17.385, 78.4867))
        assertTrue(reverse is AppResult.Failure)
    }

    @Test
    fun `ok search results map to destinations and drop coordinate-less entries`() {
        val p = provider(FakeTransport())
        val result = p.parseResults(
            """
            {"status":"OK","results":[
              {"formatted_address":"Charminar, Hyderabad, Telangana",
               "geometry":{"location":{"lat":17.3616,"lng":78.4744}}},
              {"formatted_address":"Nowhere",
               "geometry":{"location":{"lat":"bad","lng":"also-bad"}}}
            ]}
            """.trimIndent(),
        )
        assertTrue(result is AppResult.Success)
        val destinations = (result as AppResult.Success).value
        assertEquals("coordinate-less result must be dropped", 1, destinations.size)
        val d = destinations.single()
        assertEquals(17.3616, d.coordinate.latitude, 0.0001)
        assertEquals(78.4744, d.coordinate.longitude, 0.0001)
        assertEquals("Charminar", d.title)
        assertEquals("Hyderabad, Telangana", d.subtitle)
    }

    @Test
    fun `zero results is an empty success, not a failure`() {
        val p = provider(FakeTransport())
        val result = p.parseResults("""{"status":"ZERO_RESULTS","results":[]}""")
        assertTrue(result is AppResult.Success)
        assertTrue((result as AppResult.Success).value.isEmpty())
    }

    @Test
    fun `request denied names the credential problem`() {
        val p = provider(FakeTransport())
        val result = p.parseResults("""{"status":"REQUEST_DENIED","results":[],"errorMessage":"API key"}""")
        assertTrue(result is AppResult.Failure)
        val message = (result as AppResult.Failure).message.orEmpty()
        assertTrue("must name the key: $message", message.contains("google.maps.apiKey"))
    }

    @Test
    fun `reverse keeps the pressed coordinate authoritative`() {
        val p = provider(FakeTransport())
        val pressed = Coordinate(17.4000, 78.5000) // the pin the user dropped
        val result = p.parseReverse(
            """
            {"status":"OK","results":[
              {"formatted_address":"Near Charminar, Hyderabad",
               "geometry":{"location":{"lat":17.3616,"lng":78.4744}}}
            ]}
            """.trimIndent(),
            pressed,
        )
        assertTrue(result is AppResult.Success)
        val d = (result as AppResult.Success).value
        assertEquals(
            "response coordinates must never move the pin",
            pressed.latitude, d.coordinate.latitude, 0.0000001,
        )
        assertEquals(pressed.longitude, d.coordinate.longitude, 0.0000001)
        assertEquals("Near Charminar", d.title)
    }

    @Test
    fun `reverse with no results becomes a dropped pin, not a failure`() {
        val p = provider(FakeTransport())
        val pressed = Coordinate(17.4000, 78.5000)
        val result = p.parseReverse("""{"status":"ZERO_RESULTS","results":[]}""", pressed)
        assertTrue(result is AppResult.Success)
        val d = (result as AppResult.Success).value
        assertEquals("Dropped pin", d.title)
        assertEquals(pressed.latitude, d.coordinate.latitude, 0.0)
    }

    @Test
    fun `search request carries key and optional proximity bias only when supplied`() = runTest {
        val transport = FakeTransport().apply { enqueue(200, """{"status":"ZERO_RESULTS","results":[]}""") }
        provider(transport).search("charminar", near = null)
        val url = transport.requests.single().url
        assertEquals("test-key", url.queryParameter("key"))
        assertEquals(null, url.queryParameter("location"))

        val transport2 = FakeTransport().apply { enqueue(200, """{"status":"ZERO_RESULTS","results":[]}""") }
        provider(transport2).search("charminar", near = Coordinate(17.385, 78.4867))
        assertEquals("17.385,78.4867", transport2.requests.single().url.queryParameter("location"))
    }

    @Test
    fun `repeated searches are served from cache without a second request`() = runTest {
        val transport = FakeTransport().apply {
            enqueue(
                200,
                """{"status":"OK","results":[{"formatted_address":"Charminar, Hyderabad",
                    "geometry":{"location":{"lat":17.3616,"lng":78.4744}}}]}""",
            )
        }
        val p = provider(transport)
        val first = p.search("Charminar", near = null)
        val second = p.search("charminar", near = null) // case-insensitive cache hit
        assertTrue(first is AppResult.Success)
        assertTrue(second is AppResult.Success)
        assertEquals("only one network request", 1, transport.requests.size)
    }
}
