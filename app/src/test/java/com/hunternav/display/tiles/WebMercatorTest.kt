package com.hunternav.display.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Projection and tile-arithmetic tests (all deterministic pure math). */
class WebMercatorTest {

    @Test
    fun `world size doubles with each zoom`() {
        assertEquals(256.0, WebMercator.worldSize(0.0), 0.0)
        assertEquals(512.0, WebMercator.worldSize(1.0), 0.0)
        assertEquals(256.0 * 1024, WebMercator.worldSize(10.0), 0.0)
    }

    @Test
    fun `null island sits at world center`() {
        val px = WebMercator.latLngToWorldPixel(0.0, 0.0, 0.0)
        assertEquals(128.0, px[0], 1e-9)
        assertEquals(128.0, px[1], 1e-9)
    }

    @Test
    fun `latLng round-trips through world pixels`() {
        val samples = doubleArrayOf(
            17.3850, 78.4867, // Hyderabad (default region)
            -33.8688, 151.2093, // Sydney (southern hemisphere)
            51.5074, -0.1278, // London (western hemisphere)
            64.1466, -21.9426, // Reykjavík (near the latitude limit)
        )
        for (i in samples.indices step 2) {
            val lat = samples[i]
            val lon = samples[i + 1]
            val px = WebMercator.latLngToWorldPixel(lat, lon, 15.0)
            val back = WebMercator.worldPixelToLatLng(px[0], px[1], 15.0)
            assertEquals("lat for $lat,$lon", lat, back[0], 1e-7)
            assertEquals("lon for $lat,$lon", lon, back[1], 1e-7)
        }
    }

    @Test
    fun `latitude is clamped to the mercator limit`() {
        val top = WebMercator.latLngToWorldPixel(WebMercator.MAX_LATITUDE + 10, 0.0, 4.0)
        val clamped = WebMercator.latLngToWorldPixel(WebMercator.MAX_LATITUDE, 0.0, 4.0)
        assertTrue(abs(top[1] - clamped[1]) < 1e-6)
    }

    @Test
    fun `longitude wraps and tiles wrap symmetrically`() {
        assertEquals(-179.0, WebMercator.wrapLongitude(181.0), 1e-9)
        assertEquals(-180.0, WebMercator.wrapLongitude(180.0), 1e-9)
        assertEquals(0, WebMercator.wrapTileX(1024, 10))
        assertEquals(1023, WebMercator.wrapTileX(-1, 10))
        assertEquals(1, WebMercator.wrapTileX(1025, 10))
    }

    @Test
    fun `tile y clamps into range`() {
        assertEquals(0, WebMercator.clampTileY(-7, 10))
        assertEquals(1023, WebMercator.clampTileY(5000, 10))
        assertEquals(512, WebMercator.clampTileY(512, 10))
    }

    @Test
    fun `tilesForWorldRect covers the rect deterministically`() {
        // A 3x2-tile window starting at a tile corner.
        val keys = WebMercator.tilesForWorldRect(256.0, 256.0, 768.0, 640.0, 4)
        assertEquals(6, keys.size)
        // Deterministic north→south, west→east order with x varying fastest? No: x outer, y inner.
        assertEquals(TileKey(4, 1, 1), keys[0])
        assertEquals(TileKey(4, 1, 2), keys[1])
        assertEquals(TileKey(4, 2, 1), keys[2])
        assertEquals(TileKey(4, 2, 2), keys[3])
        assertEquals(TileKey(4, 3, 1), keys[4])
        assertEquals(TileKey(4, 3, 2), keys[5])
        // Running twice must give the identical list (receiver dedup relies on this).
        assertEquals(keys, WebMercator.tilesForWorldRect(256.0, 256.0, 768.0, 640.0, 4))
    }

    @Test
    fun `tile origin matches tile index arithmetic`() {
        val origin = WebMercator.tileOriginWorldPixel(TileKey(10, 500, 300))
        assertEquals(500 * 256.0, origin[0], 0.0)
        assertEquals(300 * 256.0, origin[1], 0.0)
        assertEquals(500, WebMercator.worldPixelToTileIndex(origin[0]))
    }

    @Test
    fun `meters per pixel is the known equatorial value at zoom zero`() {
        assertEquals(40075016.686 / 256.0, WebMercator.metersPerPixel(0.0, 0.0), 1e-6)
        // Higher zoom ⇒ smaller ground resolution, strictly.
        assertTrue(WebMercator.metersPerPixel(0.0, 12.0) < WebMercator.metersPerPixel(0.0, 11.0))
    }
}
