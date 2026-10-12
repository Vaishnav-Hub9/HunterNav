package com.hunternav.display.tiles

/**
 * Abstraction over a raster tile source so the renderer never touches HTTP. The production
 * implementation is [GoogleMapTileProvider] (Google Map Tiles API, 2D roadmap tiles); tests
 * use fakes.
 *
 * Implementations must be safe to call from any thread and must not block the main thread.
 */
interface MapTileProvider {

    /**
     * Attribution text that MUST remain visible while tiles from this provider are shown.
     * See Google Map Tiles API policies (viewport `copyright` + "Google Maps" attribution).
     */
    val attribution: String

    /**
     * Returns the image bytes for [key], from cache when permitted and fresh, otherwise from
     * the network. Never throws for expected failures — failures are values in [TileResult].
     */
    suspend fun tile(key: TileKey): TileResult

    /** Clears in-memory state (sessions are re-created lazily on next use). */
    fun invalidate()
}

/** Outcome of a tile request. */
sealed class TileResult {
    /** Image bytes in the provider's configured format (PNG or JPEG). */
    data class Success(val bytes: ByteArray) : TileResult() {
        override fun equals(other: Any?): Boolean = other is Success && other.bytes.contentEquals(bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** No image available (HTTP error, quota, malformed response). Renderers draw fallback. */
    data class Failure(val reason: FailureReason, val message: String) : TileResult()
}

enum class FailureReason {
    /** Session token expired or was rejected — the provider will re-create it on next use. */
    SESSION_EXPIRED,
    /** Quota or rate limit exhausted — back off, do not hammer. */
    QUOTA_EXCEEDED,
    /** Any other HTTP/network failure. */
    NETWORK,
    /** Response was not a decodable image. */
    MALFORMED,
}
