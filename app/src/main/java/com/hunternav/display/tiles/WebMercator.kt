package com.hunternav.display.tiles

import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.cos
import kotlin.math.sin

/** One XYZ (Web Mercator / slippy-map) tile address. */
data class TileKey(val z: Int, val x: Int, val y: Int)

/**
 * Pure Web Mercator (EPSG:3857) projection and XYZ tile arithmetic for the display pipeline.
 *
 * Everything here is deterministic, allocation-free math with no Android dependencies, so the
 * renderer, the tile provider and the tests all share one implementation. Google's 2D Map Tiles
 * API serves standard 256 px XYZ tiles (z 0–22), which is exactly this tile system.
 *
 * Conventions:
 *  - World size at zoom z is `TILE_SIZE * 2^z` pixels.
 *  - World origin (0,0) is the north-west corner: x grows east, y grows south.
 *  - Latitude is clamped to the Web Mercator limit (±85.05112878°); longitude wraps at ±180°.
 */
object WebMercator {

    const val TILE_SIZE = 256

    /** Web Mercator latitude limit: the latitude whose projected y reaches the world edge. */
    const val MAX_LATITUDE = 85.05112878

    /** World size in pixels at [zoom]. */
    fun worldSize(zoom: Double): Double = TILE_SIZE.toDouble() * (1 shl zoom.toInt().coerceIn(0, 24))

    /** Clamps a latitude into the valid Web Mercator range. */
    fun clampLatitude(latitude: Double): Double =
        latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)

    /** Wraps a longitude into [-180, 180). */
    fun wrapLongitude(longitude: Double): Double {
        var lon = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        // % can still yield 180.0 for inputs like -540; normalize that edge to -180.
        if (lon == 180.0) lon = -180.0
        return lon
    }

    /** Latitude/longitude → absolute world-pixel position at [zoom]. Fractional result. */
    fun latLngToWorldPixel(latitude: Double, longitude: Double, zoom: Double): DoubleArray {
        val size = worldSize(zoom)
        val lat = clampLatitude(latitude)
        val lon = wrapLongitude(longitude)
        val x = (lon + 180.0) / 360.0 * size
        val sinLat = sin(Math.toRadians(lat))
        val y = (0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * Math.PI)) * size
        return doubleArrayOf(x, y)
    }

    /** Inverse of [latLngToWorldPixel]: world pixel → latitude/longitude. */
    fun worldPixelToLatLng(pixelX: Double, pixelY: Double, zoom: Double): DoubleArray {
        val size = worldSize(zoom)
        val lon = pixelX / size * 360.0 - 180.0
        val n = Math.PI - 2.0 * Math.PI * pixelY / size
        val lat = Math.toDegrees(atan(0.5 * (Math.exp(n) - Math.exp(-n))))
        return doubleArrayOf(lat, lon)
    }

    /** World pixel → tile index along one axis (may be out of [0, 2^z) — caller wraps/clamps). */
    fun worldPixelToTileIndex(pixel: Double): Int = floor(pixel / TILE_SIZE).toInt()

    /** Horizontal tile wrapping: maps any x into [0, 2^z). Vertical: clamps into [0, 2^z). */
    fun wrapTileX(x: Int, zoom: Int): Int {
        val n = 1 shl zoom.coerceIn(0, 24)
        return ((x % n) + n) % n
    }

    fun clampTileY(y: Int, zoom: Int): Int {
        val n = 1 shl zoom.coerceIn(0, 24)
        return y.coerceIn(0, n - 1)
    }

    /** Tile → north-west corner world pixel. */
    fun tileOriginWorldPixel(key: TileKey): DoubleArray =
        doubleArrayOf(key.x * TILE_SIZE.toDouble(), key.y * TILE_SIZE.toDouble())

    /**
     * Enumerates every tile that intersects the given *geographic* viewport at integer [zoom],
     * in a deterministic order (north→south, west→east), with x wrapped and y clamped so the
     * result is always renderable. The viewport is expanded from the camera by the caller so
     * rotation never produces missing corners: this method takes the axis-aligned world-pixel
     * rectangle that already covers the rotated viewport.
     */
    fun tilesForWorldRect(minX: Double, minY: Double, maxX: Double, maxY: Double, zoom: Int): List<TileKey> {
        val x0 = worldPixelToTileIndex(minX)
        val x1 = worldPixelToTileIndex(maxX)
        val y0 = clampTileY(worldPixelToTileIndex(minY), zoom)
        val y1 = clampTileY(worldPixelToTileIndex(maxY), zoom)
        val keys = ArrayList<TileKey>((x1 - x0 + 1) * (y1 - y0 + 1))
        for (x in x0..x1) {
            for (y in y0..y1) {
                keys.add(TileKey(zoom, wrapTileX(x, zoom), y))
            }
        }
        return keys
    }

    /** Approximate meters-per-pixel at a latitude and zoom (useful for zoom heuristics). */
    fun metersPerPixel(latitude: Double, zoom: Double): Double {
        val equatorialMeters = 40075016.686 * cos(Math.toRadians(clampLatitude(latitude))) /
            worldSize(zoom)
        return equatorialMeters
    }
}
