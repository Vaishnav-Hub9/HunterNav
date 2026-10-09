package com.hunternav.data.geocoding

import com.hunternav.core.result.AppErrorKind
import com.hunternav.core.result.AppResult
import com.hunternav.domain.model.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geocoder result → Destination mapping (spec §4/§18): coordinates are mandatory, malformed
 * responses surface as parse failures, and reverse geocoding always yields the pressed pin's
 * coordinates — never an empty or (0,0) destination.
 */
class NominatimParsingTest {

    private val provider = NominatimGeocodingProvider(baseUrl = "https://example.invalid/")

    @Test
    fun `search result maps to destination with title subtitle and coordinates`() {
        val body = """
            [
              {
                "place_id": 1,
                "lat": "17.3616",
                "lon": "78.4747",
                "display_name": "Charminar, Char Kaman, Ghansi Bazaar, Hyderabad",
                "name": "Charminar",
                "address": {"road": "Char Kaman", "city": "Hyderabad"}
              }
            ]
        """.trimIndent()

        val result = provider.parseSearch(body)
        assertTrue("expected success, got $result", result is AppResult.Success)
        val destinations = (result as AppResult.Success).value
        assertEquals(1, destinations.size)

        val d = destinations.single()
        assertEquals("Charminar", d.title)
        assertEquals("Char Kaman, Hyderabad", d.subtitle)
        assertEquals(17.3616, d.coordinate.latitude, 1e-6)
        assertEquals(78.4747, d.coordinate.longitude, 1e-6)
    }

    @Test
    fun `search results without coordinates are dropped instead of becoming zero zero`() {
        val body = """
            [
              {"display_name": "Missing keys"},
              {"display_name": "Blank coords", "lat": "", "lon": ""},
              {"display_name": "Valid Place", "lat": "17.4", "lon": "78.5"}
            ]
        """.trimIndent()

        val result = provider.parseSearch(body)
        assertTrue("expected success, got $result", result is AppResult.Success)
        val destinations = (result as AppResult.Success).value

        assertEquals(1, destinations.size)
        assertEquals("Valid Place", destinations.single().title)
        // A coordinate-less result must never become a routable (0,0) destination.
        assertTrue(destinations.none { it.coordinate.latitude == 0.0 && it.coordinate.longitude == 0.0 })
    }

    @Test
    fun `malformed geocoder response maps to parse failure`() {
        val result = provider.parseSearch("this is not json {")
        assertTrue("expected failure, got $result", result is AppResult.Failure)
        assertEquals(AppErrorKind.PARSE, (result as AppResult.Failure).kind)
    }

    @Test
    fun `reverse without coordinates falls back to the pressed pin`() {
        val pressed = Coordinate(17.4000, 78.5000)
        val result = provider.parseReverse("""{"display_name": "Somewhere, Hyderabad"}""", pressed)

        assertTrue("expected success, got $result", result is AppResult.Success)
        val d = (result as AppResult.Success).value
        assertEquals(17.4000, d.coordinate.latitude, 1e-9)
        assertEquals(78.5000, d.coordinate.longitude, 1e-9)
        assertEquals("Somewhere", d.title)
    }

    @Test
    fun `reverse error payload becomes dropped pin at the pressed coordinate`() {
        val pressed = Coordinate(17.41, 78.48)
        val result = provider.parseReverse(
            """{"error": {"code": 404, "message": "Unable to geocode"}}""",
            pressed,
        )

        assertTrue("expected success, got $result", result is AppResult.Success)
        val d = (result as AppResult.Success).value
        assertEquals("Dropped pin", d.title)
        assertEquals(pressed, d.coordinate)
    }
}
