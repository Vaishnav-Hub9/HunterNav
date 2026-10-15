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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mappls geocoder contracts (docs/mappls.md):
 *  - coordinates come from the autosuggest response's latitude/longitude fields (present per
 *    the official SDK response model even though the public docs omit them), and results
 *    WITHOUT coordinates are dropped — never routed to as a fake (0,0);
 *  - reverse geocoding is label-only: the pressed coordinate stays authoritative;
 *  - the same usage policy as the Nominatim provider: ≤1 request/second app-wide, cached
 *    repeated queries, no GPS in search URLs unless explicitly hinted, failures surface as
 *    finite states (401/403 name the credential problem).
 */
class MapplsGeocodingProviderTest {

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
        .request(Request.Builder().url("https://search.mappls.example.org/").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    /** Shape matches the official SDK response model (ELocation): mapplsPin + lat/lng. */
    private val searchBody = """
        {
          "suggestedLocations": [
            {"mapplsPin":"MMI000","placeName":"MapmyIndia Head Office New Delhi",
             "placeAddress":"237, Okhla Industrial Estate Phase 3, New Delhi, Delhi, 110020",
             "type":"POI","orderIndex":1,"latitude":28.524318,"longitude":77.283225},
            {"mapplsPin":"NOCO01","placeName":"No Coordinates Place",
             "placeAddress":"Somewhere without coordinates"}
          ],
          "userAddedLocations": [],
          "suggestedSearches": []
        }
    """.trimIndent()

    private val reverseBody = """
        {
          "responseCode": 200,
          "results": [
            {"poi":"Charminar","street":"Char Kaman","formatted_address":
             "Char Kaman, Hyderabad, Telangana, 500002 (India)","lat":"17.3616","lng":"78.4747"}
          ]
        }
    """.trimIndent()

    private fun TestScope.provider(transport: FakeTransport, accessToken: String = "test-token"): MapplsGeocodingProvider {
        transport.nowProvider = { testScheduler.currentTime }
        return MapplsGeocodingProvider(
            baseUrl = "https://search.mappls.example.org/",
            accessToken = accessToken,
            networkClient = transport,
            rateLimiter = RequestRateLimiter(minIntervalMs = 1_000) { testScheduler.currentTime },
            nowMs = { testScheduler.currentTime },
        )
    }

    @Test
    fun `search parses suggestions into destinations with coordinates`() = runTest {
        val transport = FakeTransport(listOf({ response(200, searchBody) }))
        val provider = provider(transport)

        val result = provider.search("mapmyindia", near = null)

        // The coordinate-less suggestion must be dropped, never faked.
        val dest = (result as AppResult.Success).value.single()
        assertEquals("MapmyIndia Head Office New Delhi", dest.title)
        assertEquals("237, Okhla Industrial Estate Phase 3, New Delhi, Delhi, 110020", dest.subtitle)
        assertEquals(28.524318, dest.coordinate.latitude, 1e-6)
        assertEquals(77.283225, dest.coordinate.longitude, 1e-6)
    }

    @Test
    fun `request carries query and access token and no gps coordinates by default`() = runTest {
        val transport = FakeTransport(listOf({ response(200, searchBody) }))
        val provider = provider(transport)

        provider.search("mapmyindia", near = null)

        val url = transport.urls.single()
        assertTrue("query must be sent: $url", url.contains("query=mapmyindia"))
        assertTrue("access token must be sent: $url", url.contains("access_token=test-token"))
        assertTrue("no location bias without explicit proximity: $url", !url.contains("location="))
        assertTrue("path must be the autosuggest endpoint: $url", url.contains("/search/places/autosuggest/json"))
    }

    @Test
    fun `explicit proximity hint becomes a location bias`() = runTest {
        val transport = FakeTransport(listOf({ response(200, searchBody) }))
        val provider = provider(transport)

        provider.search("charminar", near = Coordinate(17.3850, 78.4867))

        val url = transport.urls.single()
        assertTrue("location bias must be sent when hinted: $url", url.contains("location=17.385%2C78.4867") || url.contains("location=17.385,78.4867"))
    }

    @Test
    fun `results without coordinates are dropped rather than becoming zero zero`() = runTest {
        val body = """
            {"suggestedLocations":[
              {"placeName":"Nope","placeAddress":"No coords here"},
              {"placeName":"BadRange","placeAddress":"Out of range","latitude":91.0,"longitude":200.0},
              {"placeName":"Yes","placeAddress":"Has coords","latitude":17.4,"longitude":78.3}
            ]}
        """.trimIndent()
        val transport = FakeTransport(listOf({ response(200, body) }))
        val provider = provider(transport)

        val result = provider.search("test", near = null)

        val destinations = (result as AppResult.Success).value
        assertEquals("only coordinate-bearing results survive", 1, destinations.size)
        assertEquals("Yes", destinations.single().title)
    }

    @Test
    fun `repeated queries are served from the cache without a second request`() = runTest {
        val transport = FakeTransport(listOf({ response(200, searchBody) }))
        val provider = provider(transport)

        val first = provider.search("Charminar", near = null)
        val second = provider.search("charminar ", near = null) // case/whitespace-insensitive

        assertEquals(1, transport.calls)
        assertEquals((first as AppResult.Success).value, (second as AppResult.Success).value)
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
    fun `reverse shares the same one-per-second limit and keeps the pressed coordinate`() = runTest {
        val transport = FakeTransport(listOf({ response(200, searchBody) }, { response(200, reverseBody) }))
        val provider = provider(transport)
        val pressed = Coordinate(17.3971, 78.4903) // deliberately ≠ the response's own lat/lng

        provider.search("Charminar", near = null)
        val result = provider.reverse(pressed)

        assertEquals(2, transport.calls)
        val gap = transport.timestamps[1] - transport.timestamps[0]
        assertTrue("search+reverse only ${gap}ms apart", gap >= 1_000)

        // Label-only upgrade: the response's nearest-entity coordinates must never move the pin.
        val dest = (result as AppResult.Success).value
        assertEquals("Charminar", dest.title)
        assertEquals("Char Kaman, Hyderabad, Telangana, 500002 (India)", dest.subtitle)
        assertEquals(pressed, dest.coordinate)
    }

    @Test
    fun `reverse with no results falls back to a dropped pin at the pressed coordinate`() = runTest {
        val transport = FakeTransport(listOf({ response(200, """{"responseCode":200,"results":[]}""") }))
        val provider = provider(transport)
        val pressed = Coordinate(17.4000, 78.5000)

        val dest = (provider.reverse(pressed) as AppResult.Success).value

        assertEquals("Dropped pin", dest.title)
        assertEquals(pressed, dest.coordinate)
    }

    @Test
    fun `missing access token fails fast without a request`() = runTest {
        val transport = FakeTransport(listOf({ response(200, searchBody) }))
        val provider = provider(transport, accessToken = "")

        val result = provider.search("Charminar", near = null)

        val failure = result as AppResult.Failure
        assertEquals(AppErrorKind.SERVER, failure.kind)
        assertTrue("message must name the missing config: ${failure.message}", failure.message!!.contains("mappls.accessToken"))
        assertEquals("no request may fire without credentials", 0, transport.calls)
    }

    @Test
    fun `http 401 names the rejected credential`() = runTest {
        val transport = FakeTransport(listOf({ response(401, """{"message":"invalid token"}""") }))
        val provider = provider(transport)

        val result = provider.search("Charminar", near = null)

        val failure = result as AppResult.Failure
        assertEquals(AppErrorKind.SERVER, failure.kind)
        assertTrue("message must name the token problem: ${failure.message}", failure.message!!.contains("access token"))
        assertEquals("401 must never be retried", 1, transport.calls)
    }

    @Test
    fun `http 500 surfaces a server failure without retries`() = runTest {
        val transport = FakeTransport(listOf({ response(500, "boom") }))
        val provider = provider(transport)

        val result = provider.search("Charminar", near = null)

        assertEquals(AppErrorKind.SERVER, (result as AppResult.Failure).kind)
        assertEquals(1, transport.calls)
    }

    @Test
    fun `malformed response maps to a parse failure`() = runTest {
        val transport = FakeTransport(listOf({ response(200, "<html>not json</html>") }))
        val provider = provider(transport)

        val result = provider.search("Charminar", near = null)

        assertEquals(AppErrorKind.PARSE, (result as AppResult.Failure).kind)
    }

    @Test
    fun `empty suggestion list is a success so the screen can show no-results`() = runTest {
        val transport = FakeTransport(listOf({ response(200, """{"suggestedLocations":[]}""") }))
        val provider = provider(transport)

        val result = provider.search("zzz nowhere", near = null)

        assertTrue(result is AppResult.Success)
        assertTrue((result as AppResult.Success).value.isEmpty())
    }

    @Test
    fun `parseSearch drops suggestions with blank or string-null coordinates`() = runTest {
        val transport = FakeTransport(listOf({ response(200, searchBody) }))
        val provider = provider(transport)

        val body = """
            {"suggestedLocations":[
              {"placeName":"Nulls","latitude":null,"longitude":null},
              {"placeName":"Blank","latitude":"","longitude":""},
              {"placeName":"Lone","latitude":17.4},
              {"placeName":"Good","latitude":17.4,"longitude":78.3}
            ]}
        """.trimIndent()

        val result = provider.parseSearch(body, limit = 8)

        val destinations = (result as AppResult.Success).value
        assertEquals(1, destinations.size)
        assertEquals("Good", destinations.single().title)
    }

    @Test
    fun `parseReverse tolerates missing address fields and never fails on empty object`() = runTest {
        val transport = FakeTransport(listOf({ response(200, reverseBody) }))
        val provider = provider(transport)
        val pressed = Coordinate(17.3616, 78.4747)

        val bare = (provider.parseReverse("""{"results":[{"poi":""}]}""", pressed) as AppResult.Success).value
        assertEquals("Dropped pin", bare.title)
        assertNull(bare.subtitle)
        assertEquals(pressed, bare.coordinate)

        val malformed = provider.parseReverse("""{"results":"not-an-array"}""", pressed)
        assertEquals(AppErrorKind.PARSE, (malformed as AppResult.Failure).kind)
    }
}
