package com.hunternav.display.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tile cache policy tests: server TTL, hard TTL cap, LRU eviction. */
class TileCacheTest {

    private val key0 = TileKey(10, 1, 2)
    private val key1 = TileKey(10, 2, 2)
    private val key2 = TileKey(10, 3, 2)
    private val bytes = byteArrayOf(1, 2, 3)

    @Test
    fun `entry expires at the server max-age when shorter than the hard TTL`() {
        var now = 0L
        val cache = TileCache(maxEntries = 4, hardTtlMs = 60_000, nowMs = { now })
        cache.put(key0, bytes, maxAgeSeconds = 10)

        now = 9_999
        assertEquals(bytes.toList(), cache.get(key0)?.toList())
        now = 10_000
        assertNull(cache.get(key0))
        assertEquals(0, cache.size())
    }

    @Test
    fun `hard TTL caps a generous server max-age`() {
        var now = 0L
        val cache = TileCache(maxEntries = 4, hardTtlMs = 30_000, nowMs = { now })
        cache.put(key0, bytes, maxAgeSeconds = 86_400)

        now = 29_999
        assertEquals(bytes.toList(), cache.get(key0)?.toList())
        now = 30_000
        assertNull(cache.get(key0))
    }

    @Test
    fun `missing max-age falls back to the hard TTL`() {
        var now = 0L
        val cache = TileCache(maxEntries = 4, hardTtlMs = 30_000, nowMs = { now })
        cache.put(key0, bytes, maxAgeSeconds = null)
        now = 29_999
        assertEquals(bytes.toList(), cache.get(key0)?.toList())
        now = 30_000
        assertNull(cache.get(key0))
    }

    @Test
    fun `LRU eviction drops the least-recently-used entry`() {
        var now = 0L
        val cache = TileCache(maxEntries = 2, hardTtlMs = 60_000, nowMs = { now })
        cache.put(key0, bytes, maxAgeSeconds = null)
        cache.put(key1, bytes, maxAgeSeconds = null)
        // Touch key0 so key1 becomes least recently used.
        assertEquals(bytes.toList(), cache.get(key0)?.toList())
        cache.put(key2, bytes, maxAgeSeconds = null)

        assertEquals(bytes.toList(), cache.get(key0)?.toList())
        assertNull(cache.get(key1))
        assertEquals(bytes.toList(), cache.get(key2)?.toList())
        assertEquals(2, cache.size())
    }

    @Test
    fun `clear empties the cache`() {
        val cache = TileCache(maxEntries = 2, hardTtlMs = 60_000, nowMs = { 0L })
        cache.put(key0, bytes, maxAgeSeconds = null)
        cache.clear()
        assertEquals(0, cache.size())
        assertNull(cache.get(key0))
    }
}
