package com.hunternav.data.routing

import com.hunternav.domain.model.Coordinate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OSRM URL construction (real-device HTTP 400): waypoint pairs must be `lon,lat` joined by
 * `;` inside ONE path segment — a slash between pairs makes OSRM reject the request.
 */
class OsrmUrlTest {

    private val provider = OsrmRoutingProvider(baseUrl = "https://router.project-osrm.org/")

    // Exactly the origin/destination pair from the real-device Logcat capture that got HTTP 400.
    private val origin = Coordinate(latitude = 17.3735683, longitude = 78.5229411)
    private val destination = Coordinate(latitude = 17.3616, longitude = 78.4747)

    @Test
    fun `coordinate pairs are semicolon-separated within a single path segment`() {
        val url = provider.buildRouteUrl(origin, destination, alternatives = true)

        assertTrue(
            "expected lon,lat;lon,lat in one path segment, got $url",
            url.contains("78.5229411,17.3735683;78.4747,17.3616"),
        )
        // The original bug: the second pair became its own slash-separated path segment.
        assertFalse("slash between coordinate pairs: $url", url.contains("78.5229411,17.3735683/"))
        assertFalse(
            "no slash may appear between driving/ and the query string: $url",
            Regex("""driving/[^?]*/""").containsMatchIn(url),
        )
    }

    @Test
    fun `keeps lon-lat order base path and query parameters`() {
        val url = provider.buildRouteUrl(origin, destination, alternatives = true)

        assertTrue(
            "wrong prefix: $url",
            url.startsWith("https://router.project-osrm.org/route/v1/driving/"),
        )
        // lon,lat order (never lat,lon).
        assertTrue("origin must be lon,lat: $url", url.contains("78.5229411,17.3735683"))
        assertTrue("destination must be lon,lat: $url", url.contains("78.4747,17.3616"))
        // Existing query parameters preserved.
        assertTrue(url.contains("overview=full"))
        assertTrue(url.contains("geometries=geojson"))
        assertTrue(url.contains("steps=true"))
        assertTrue(url.contains("alternatives=true"))
    }

    @Test
    fun `alternatives false is reflected in the query string`() {
        val url = provider.buildRouteUrl(origin, destination, alternatives = false)

        assertTrue(url.contains("alternatives=false"))
        assertTrue(url.contains("78.5229411,17.3735683;78.4747,17.3616"))
    }
}
