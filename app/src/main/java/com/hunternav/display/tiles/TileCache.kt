package com.hunternav.display.tiles

/**
 * Bounded, in-memory tile cache with explicit expiration and LRU eviction.
 *
 * Caching policy (Google Map Tiles API policies): tile Content may not be stored permanently;
 * HTTP `Cache-Control` directives must be honored. This cache therefore:
 *  - lives in memory only (never on disk);
 *  - expires every entry no later than [hardTtlMs] after insertion, and earlier when the
 *    server's `Cache-Control: max-age` says so;
 *  - evicts least-recently-used entries beyond [maxEntries].
 *
 * Not thread-safe by itself — the owning provider synchronizes access.
 */
class TileCache(
    private val maxEntries: Int,
    private val hardTtlMs: Long,
    private val nowMs: () -> Long,
) {

    private val entries = LinkedHashMap<TileKey, Entry>(maxEntries, 0.75f, /* accessOrder = */ true)

    private class Entry(val bytes: ByteArray, val expiresAtMs: Long)

    /** Returns fresh bytes for [key] or null on miss/expiry (expired entries are dropped). */
    fun get(key: TileKey): ByteArray? {
        val e = entries[key] ?: return null
        if (nowMs() >= e.expiresAtMs) {
            entries.remove(key)
            return null
        }
        return e.bytes
    }

    /**
     * Stores [bytes] for [key]. [maxAgeSeconds] is the server-provided `Cache-Control` max-age
     * (null when absent); the effective TTL is min(server max-age, [hardTtlMs]).
     */
    fun put(key: TileKey, bytes: ByteArray, maxAgeSeconds: Long?) {
        val serverTtl = if (maxAgeSeconds != null && maxAgeSeconds > 0) maxAgeSeconds * 1000 else hardTtlMs
        val ttl = minOf(serverTtl, hardTtlMs)
        entries[key] = Entry(bytes, nowMs() + ttl)
        while (entries.size > maxEntries) {
            val eldest = entries.entries.iterator()
            eldest.next()
            eldest.remove()
        }
    }

    fun size(): Int = entries.size

    fun clear() = entries.clear()
}
