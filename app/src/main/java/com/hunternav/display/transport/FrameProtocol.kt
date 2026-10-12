package com.hunternav.display.transport

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Versioned binary wire protocol between the Android renderer and the ESP32-S3 receiver.
 *
 * Transport: one WebSocket **binary** message = one protocol message (JPEG is never base64'd).
 *
 * Layout (little-endian):
 *
 * ```
 * offset size field
 * 0      4    magic        u32  = 0x484E5631 ("HNV1")
 * 4      2    version      u16  = 1
 * 6      2    type         u16  (1 = IMAGE_FRAME, 2 = META_ONLY)
 * 8      4    sequence     u32  (monotonic per connection; receiver detects drops/gaps)
 * 12     2    width        u16  px
 * 14     2    height       u16  px
 * 16     1    navState     u8   (NavStateCode)
 * 17     1    maneuver     u8   (ManeuverCode)
 * 18     2    flags        u16  (bit0: simulated, bit1: degradedTiles, bit2: headingValid)
 * 20     4    distToManeuverM  i32  (-1 = unknown)
 * 24     4    remainingM       i32  (-1 = unknown)
 * 28     4    etaEpochSec      u32  (0 = unknown)
 * 32     4    payloadLen       u32  bytes following the header (JPEG for IMAGE_FRAME)
 * 36     ..   payload
 * ```
 *
 * The header is fixed-size so a receiver can validate length and reject malformed messages
 * cheaply before touching the JPEG payload. A failed decode never kills the socket: the
 * receiver counts and skips the message.
 */
object FrameProtocol {

    const val MAGIC = 0x484E5631.toInt() // "HNV1"
    const val VERSION = 1
    const val HEADER_SIZE = 36

    const val TYPE_IMAGE_FRAME = 1
    const val TYPE_META_ONLY = 2

    // NavStateCode — mirrors domain NavPhase for the wire.
    const val NAV_IDLE = 0
    const val NAV_NAVIGATING = 1
    const val NAV_REROUTING = 2
    const val NAV_ARRIVED = 3
    const val NAV_ERROR = 4

    // ManeuverCode — compact turn codes the firmware draws as arrows.
    const val MANEUVER_UNKNOWN = 0
    const val MANEUVER_DEPART = 1
    const val MANEUVER_STRAIGHT = 2
    const val MANEUVER_SLIGHT_LEFT = 3
    const val MANEUVER_SLIGHT_RIGHT = 4
    const val MANEUVER_LEFT = 5
    const val MANEUVER_RIGHT = 6
    const val MANEUVER_SHARP_LEFT = 7
    const val MANEUVER_SHARP_RIGHT = 8
    const val MANEUVER_UTURN = 9
    const val MANEUVER_ROUNDABOUT = 10
    const val MANEUVER_ARRIVE = 11

    const val FLAG_SIMULATED = 1 shl 0
    const val FLAG_DEGRADED_TILES = 1 shl 1
    const val FLAG_HEADING_VALID = 1 shl 2

    /** Metadata carried with every message (and standalone for high-rate guidance updates). */
    data class NavMeta(
        val navState: Int = NAV_IDLE,
        val maneuver: Int = MANEUVER_UNKNOWN,
        val flags: Int = 0,
        val distanceToManeuverM: Int = -1,
        val remainingMeters: Int = -1,
        val etaEpochSec: Long = 0L,
    )

    /** A fully decoded protocol message. [jpeg] is null for META_ONLY. */
    data class Message(
        val type: Int,
        val sequence: Int,
        val width: Int,
        val height: Int,
        val meta: NavMeta,
        val jpeg: ByteArray?,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Message && other.type == type && other.sequence == sequence &&
                other.width == width && other.height == height && other.meta == meta &&
                ((other.jpeg == null && jpeg == null) || (other.jpeg != null && jpeg != null && other.jpeg.contentEquals(jpeg)))

        override fun hashCode(): Int {
            var r = type * 31 + sequence
            r = r * 31 + width
            r = r * 31 + height
            r = r * 31 + meta.hashCode()
            r = r * 31 + (jpeg?.contentHashCode() ?: 0)
            return r
        }
    }

    /** Encodes an IMAGE_FRAME message. */
    fun encodeFrame(sequence: Int, width: Int, height: Int, meta: NavMeta, jpeg: ByteArray): ByteArray =
        encode(TYPE_IMAGE_FRAME, sequence, width, height, meta, jpeg)

    /** Encodes a META_ONLY message (guidance may refresh faster than image frames). */
    fun encodeMeta(sequence: Int, width: Int, height: Int, meta: NavMeta): ByteArray =
        encode(TYPE_META_ONLY, sequence, width, height, meta, null)

    private fun encode(
        type: Int,
        sequence: Int,
        width: Int,
        height: Int,
        meta: NavMeta,
        payload: ByteArray?,
    ): ByteArray {
        val payloadBytes = payload ?: EMPTY
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payloadBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.putShort(VERSION.toShort())
        buffer.putShort(type.toShort())
        buffer.putInt(sequence)
        buffer.putShort(width.toShort())
        buffer.putShort(height.toShort())
        buffer.put(meta.navState.toByte())
        buffer.put(meta.maneuver.toByte())
        buffer.putShort(meta.flags.toShort())
        buffer.putInt(meta.distanceToManeuverM)
        buffer.putInt(meta.remainingMeters)
        buffer.putInt(meta.etaEpochSec.toInt())
        buffer.putInt(payloadBytes.size)
        buffer.put(payloadBytes)
        return buffer.array()
    }

    /**
     * Decodes one message. Returns null for malformed input (wrong magic/version, truncated
     * header, payload length mismatch) — callers must skip and continue, never crash.
     */
    fun decode(bytes: ByteArray?): Message? {
        if (bytes == null || bytes.size < HEADER_SIZE) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.int != MAGIC) return null
        val version = buffer.short.toInt() and 0xFFFF
        if (version != VERSION) return null
        val type = buffer.short.toInt() and 0xFFFF
        if (type != TYPE_IMAGE_FRAME && type != TYPE_META_ONLY) return null
        val sequence = buffer.int
        val width = buffer.short.toInt() and 0xFFFF
        val height = buffer.short.toInt() and 0xFFFF
        val navState = buffer.get().toInt() and 0xFF
        val maneuver = buffer.get().toInt() and 0xFF
        val flags = buffer.short.toInt() and 0xFFFF
        val dist = buffer.int
        val remaining = buffer.int
        val eta = buffer.int.toLong() and 0xFFFFFFFFL
        val payloadLen = buffer.int
        if (payloadLen < 0 || payloadLen != bytes.size - HEADER_SIZE) return null
        val payload = if (payloadLen == 0) null else bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLen)
        return Message(type, sequence, width, height, NavMeta(navState, maneuver, flags, dist, remaining, eta), payload)
    }

    /** True when [bytes] begin with a JPEG SOI marker — cheap sanity check for the receiver. */
    fun looksLikeJpeg(bytes: ByteArray?): Boolean =
        bytes != null && bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[bytes.size - 2] == 0xFF.toByte() && bytes[bytes.size - 1] == 0xD9.toByte()

    private val EMPTY = ByteArray(0)

    /** Convenience for building a ByteArrayOutputStream-backed payload (tests/firmware docs). */
    fun jpegOf(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        chunks.forEach { out.write(it) }
        return out.toByteArray()
    }
}
