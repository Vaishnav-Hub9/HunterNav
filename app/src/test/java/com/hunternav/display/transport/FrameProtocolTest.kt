package com.hunternav.display.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wire-protocol contract tests: round-trips, malformed input, JPEG sanity check. */
class FrameProtocolTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x12, 0x34, 0xFF.toByte(), 0xD9.toByte())

    @Test
    fun `image frame round-trips byte-exact`() {
        val meta = FrameProtocol.NavMeta(
            navState = FrameProtocol.NAV_NAVIGATING,
            maneuver = FrameProtocol.MANEUVER_LEFT,
            flags = FrameProtocol.FLAG_HEADING_VALID or FrameProtocol.FLAG_SIMULATED,
            distanceToManeuverM = 320,
            remainingMeters = 12_450,
            etaEpochSec = 1_800_000_000L,
        )
        val encoded = FrameProtocol.encodeFrame(42, 480, 320, meta, jpeg)
        val decoded = FrameProtocol.decode(encoded)

        assertNotNull(decoded)
        requireNotNull(decoded)
        assertEquals(FrameProtocol.TYPE_IMAGE_FRAME, decoded.type)
        assertEquals(42, decoded.sequence)
        assertEquals(480, decoded.width)
        assertEquals(320, decoded.height)
        assertEquals(meta, decoded.meta)
        assertTrue(jpeg.contentEquals(decoded.jpeg))
    }

    @Test
    fun `meta-only message has no payload`() {
        val meta = FrameProtocol.NavMeta(
            navState = FrameProtocol.NAV_REROUTING,
            maneuver = FrameProtocol.MANEUVER_ROUNDABOUT,
            distanceToManeuverM = -1,
            remainingMeters = -1,
            etaEpochSec = 0L,
        )
        val decoded = FrameProtocol.decode(FrameProtocol.encodeMeta(7, 480, 320, meta))

        assertNotNull(decoded)
        requireNotNull(decoded)
        assertEquals(FrameProtocol.TYPE_META_ONLY, decoded.type)
        assertNull(decoded.jpeg)
        assertEquals(meta, decoded.meta)
        assertEquals(FrameProtocol.HEADER_SIZE, encodedSizeOf(decoded))
    }

    @Test
    fun `malformed inputs are rejected, never crash`() {
        assertNull(FrameProtocol.decode(null))
        assertNull(FrameProtocol.decode(ByteArray(0)))
        assertNull(FrameProtocol.decode(ByteArray(FrameProtocol.HEADER_SIZE - 1)))

        // Right size, wrong magic.
        val wrongMagic = FrameProtocol.encodeMeta(0, 480, 320, FrameProtocol.NavMeta())
        wrongMagic[0] = 0x00
        assertNull(FrameProtocol.decode(wrongMagic))

        // Wrong version.
        val wrongVersion = FrameProtocol.encodeMeta(0, 480, 320, FrameProtocol.NavMeta())
        wrongVersion[4] = 99
        assertNull(FrameProtocol.decode(wrongVersion))

        // Unknown message type (type is the u16 at offset 6..7).
        val wrongType = FrameProtocol.encodeMeta(0, 480, 320, FrameProtocol.NavMeta())
        wrongType[6] = 77
        assertNull(FrameProtocol.decode(wrongType))

        // Declared payload longer than the actual bytes (truncated frame).
        val truncated = FrameProtocol.encodeFrame(1, 480, 320, FrameProtocol.NavMeta(), jpeg)
        assertNull(FrameProtocol.decode(truncated.copyOf(truncated.size - 3)))
    }

    @Test
    fun `sequence gaps are visible to the receiver`() {
        // The receiver detects drops purely from the sequence field: two frames must differ.
        val a = FrameProtocol.decode(FrameProtocol.encodeMeta(10, 480, 320, FrameProtocol.NavMeta()))
        val b = FrameProtocol.decode(FrameProtocol.encodeMeta(11, 480, 320, FrameProtocol.NavMeta()))
        requireNotNull(a); requireNotNull(b)
        assertEquals(1, b!!.sequence - a!!.sequence)
    }

    @Test
    fun `looksLikeJpeg recognizes SOI and EOI markers only`() {
        assertTrue(FrameProtocol.looksLikeJpeg(jpeg))
        assertFalse(FrameProtocol.looksLikeJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4)))
        assertFalse(FrameProtocol.looksLikeJpeg(null))
    }

    private fun encodedSizeOf(message: FrameProtocol.Message): Int =
        FrameProtocol.HEADER_SIZE + (message.jpeg?.size ?: 0)
}
