package com.hunternav.data.routing

import com.hunternav.domain.model.Coordinate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mappls route URL construction (docs/mappls.md): `route/direction/{resource}/{profile}/`
 * with `lon,lat;lon,lat` inside ONE path segment and `access_token` as query parameter —
 * the same pair-encoding lesson as [OsrmUrlTest], now for the Mappls endpoint.
 */
class MapplsRoutingUrlTest {

    private val provider = MapplsRoutingProvider(
        baseUrl = "https://route.mappls.com/",
        accessToken = "test-token",
    )

    private val origin = Coordinate(latitude = 17.3735683, longitude = 78.5229411)
    private val destination = Coordinate(latitude = 17.3616, longitude = 78.4747)

    @Test
    fun `coordinate pairs are semicolon-separated within a single path segment`() {
        val url = provider.buildRouteUrl(origin, destination, alternatives = true)

        assertTrue(
            "expected lon,lat;lon,lat in one path segment, got $url",
            url.contains("78.5229411,17.3735683;78.4747,17.3616"),
        )
        // The original OSRM bug class: the second pair became its own slash-separated segment.
        assertFalse("slash between coordinate pairs: $url", url.contains("78.5229411,17.3735683/"))
        assertFalse(
            "no slash may appear between biking/ and the query string: $url",
            Regex("""biking/[^?]*/""").containsMatchIn(url),
        )
    }

    @Test
    fun `keeps lon-lat order resource profile and query parameters`() {
        val url = provider.buildRouteUrl(origin, destination, alternatives = true)

        assertTrue(
            "wrong prefix: $url",
            url.startsWith("https://route.mappls.com/route/direction/route_traffic/biking/"),
        )
        // lon,lat order (never lat,lon).
        assertTrue("origin must be lon,lat: $url", url.contains("78.5229411,17.3735683"))
        assertTrue("destination must be lon,lat: $url", url.contains("78.4747,17.3616"))
        // Documented request parameters.
        assertTrue("steps: $url", url.contains("steps=true"))
        assertTrue("geometries: $url", url.contains("geometries=geojson"))
        assertTrue("overview: $url", url.contains("overview=full"))
        assertTrue("alternatives: $url", url.contains("alternatives=2"))
        // Auth: the Console access token rides as a query parameter.
        assertTrue("access_token: $url", url.contains("access_token=test-token"))
    }

    @Test
    fun `alternatives false omits the parameter entirely`() {
        val url = provider.buildRouteUrl(origin, destination, alternatives = false)

        assertFalse("alternatives must be omitted: $url", url.contains("alternatives"))
        assertTrue(url.contains("78.5229411,17.3735683;78.4747,17.3616"))
    }

    @Test
    fun `documented resource override lands in the path`() {
        val url = provider.buildRouteUrl(origin, destination, alternatives = false, resource = "route_adv")

        assertTrue("route_adv must be the resource: $url", url.contains("/route/direction/route_adv/"))
        assertFalse(url.contains("route_traffic"))
    }

    @Test
    fun `undocumented profile falls back to the biking default`() {
        val hostile = MapplsRoutingProvider(
            baseUrl = "https://route.mappls.com/",
            accessToken = "test-token",
            profile = "flying;drop TABLE routes",
        )
        val url = hostile.buildRouteUrl(origin, destination, alternatives = false)

        assertTrue("profile must be sanitized to biking: $url", url.contains("/route_traffic/biking/"))
        assertFalse("hostile profile leaked into the URL: $url", url.contains("flying"))
        assertFalse("hostile profile leaked into the URL: $url", url.contains("drop"))
    }

    @Test
    fun `three distinct destinations produce distinct route requests`() {
        val charminar = Coordinate(latitude = 17.3616, longitude = 78.4747)
        val vasavi = Coordinate(latitude = 17.3969, longitude = 78.3208)
        val gachibowli = Coordinate(latitude = 17.4401, longitude = 78.3489)

        val a = provider.buildRouteUrl(origin, charminar, alternatives = true)
        val b = provider.buildRouteUrl(origin, vasavi, alternatives = true)
        val c = provider.buildRouteUrl(origin, gachibowli, alternatives = true)

        assertTrue(a.contains("78.5229411,17.3735683;78.4747,17.3616"))
        assertTrue(b.contains("78.5229411,17.3735683;78.3208,17.3969"))
        assertTrue(c.contains("78.5229411,17.3735683;78.3489,17.4401"))
        assertTrue("URLs must differ per destination", setOf(a, b, c).size == 3)
    }
}
