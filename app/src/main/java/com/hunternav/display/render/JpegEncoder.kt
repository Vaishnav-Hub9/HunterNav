package com.hunternav.display.render

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Minimal JPEG encoder for display frames. Wraps the platform's `Bitmap.compress` and bounds
 * the output so a single WebSocket message always stays comfortably under the ESP32's receive
 * buffer assumptions: when a quality level produces too many bytes, quality is stepped down
 * (never the resolution — the receiver is told the true width/height per frame).
 */
class JpegEncoder(
    private val quality: Int = DEFAULT_QUALITY,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) {

    private val out = ByteArrayOutputStream(64 * 1024)

    /** Encodes [bitmap] to JPEG bytes; returns null only if the platform encoder fails. */
    fun encode(bitmap: Bitmap): ByteArray? {
        var q = quality.coerceIn(MIN_QUALITY, 100)
        while (true) {
            out.reset()
            val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, q, out)
            if (!ok) return null
            if (out.size() <= maxBytes || q <= MIN_QUALITY) return out.toByteArray()
            q -= STEP
        }
    }

    companion object {
        const val DEFAULT_QUALITY = 70
        const val MIN_QUALITY = 30
        const val STEP = 15
        /** ~96 KB keeps a frame under 100 KB including the 36-byte protocol header. */
        const val DEFAULT_MAX_BYTES = 96 * 1024
    }
}
