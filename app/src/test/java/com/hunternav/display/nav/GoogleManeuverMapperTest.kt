package com.hunternav.display.nav

import com.hunternav.domain.model.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Constant-value mapping tests against the javap-verified Navigation SDK 8.0.0 values. */
class GoogleManeuverMapperTest {

    @Test
    fun `core turn constants map to normalized types`() {
        assertEquals(ManeuverType.START, GoogleManeuverMapper.toNormalized(1)) // DEPART
        assertEquals(ManeuverType.ARRIVE, GoogleManeuverMapper.toNormalized(2)) // DESTINATION
        assertEquals(ManeuverType.ARRIVE, GoogleManeuverMapper.toNormalized(3)) // DESTINATION_LEFT
        assertEquals(ManeuverType.ARRIVE, GoogleManeuverMapper.toNormalized(4)) // DESTINATION_RIGHT
        assertEquals(ManeuverType.CONTINUE, GoogleManeuverMapper.toNormalized(5)) // STRAIGHT
        assertEquals(ManeuverType.LEFT, GoogleManeuverMapper.toNormalized(6)) // TURN_LEFT
        assertEquals(ManeuverType.RIGHT, GoogleManeuverMapper.toNormalized(7)) // TURN_RIGHT
        assertEquals(ManeuverType.SLIGHT_LEFT, GoogleManeuverMapper.toNormalized(8)) // KEEP_LEFT
        assertEquals(ManeuverType.SLIGHT_RIGHT, GoogleManeuverMapper.toNormalized(9)) // KEEP_RIGHT
        assertEquals(ManeuverType.SLIGHT_LEFT, GoogleManeuverMapper.toNormalized(10)) // SLIGHT_LEFT
        assertEquals(ManeuverType.SLIGHT_RIGHT, GoogleManeuverMapper.toNormalized(11)) // SLIGHT_RIGHT
        assertEquals(ManeuverType.SHARP_LEFT, GoogleManeuverMapper.toNormalized(12)) // SHARP_LEFT
        assertEquals(ManeuverType.SHARP_RIGHT, GoogleManeuverMapper.toNormalized(13)) // SHARP_RIGHT
        assertEquals(ManeuverType.U_TURN, GoogleManeuverMapper.toNormalized(14)) // U_TURN_CW
        assertEquals(ManeuverType.U_TURN, GoogleManeuverMapper.toNormalized(15)) // U_TURN_CCW
    }

    @Test
    fun `merge fork ferry and roundabout variants collapse correctly`() {
        assertEquals(ManeuverType.CONTINUE, GoogleManeuverMapper.toNormalized(16)) // MERGE_UNSPECIFIED
        assertEquals(ManeuverType.SLIGHT_LEFT, GoogleManeuverMapper.toNormalized(17)) // MERGE_LEFT
        assertEquals(ManeuverType.SLIGHT_RIGHT, GoogleManeuverMapper.toNormalized(18)) // MERGE_RIGHT
        assertEquals(ManeuverType.SLIGHT_LEFT, GoogleManeuverMapper.toNormalized(19)) // FORK_LEFT
        assertEquals(ManeuverType.SLIGHT_RIGHT, GoogleManeuverMapper.toNormalized(20)) // FORK_RIGHT
        assertEquals(ManeuverType.ROUNDABOUT, GoogleManeuverMapper.toNormalized(43)) // ROUNDABOUT_CW
        assertEquals(ManeuverType.ROUNDABOUT, GoogleManeuverMapper.toNormalized(44)) // ROUNDABOUT_CCW
        assertEquals(ManeuverType.CONTINUE, GoogleManeuverMapper.toNormalized(63)) // FERRY_BOAT
        assertEquals(ManeuverType.CONTINUE, GoogleManeuverMapper.toNormalized(64)) // FERRY_TRAIN
        assertEquals(ManeuverType.CONTINUE, GoogleManeuverMapper.toNormalized(65)) // NAME_CHANGE
    }

    @Test
    fun `unknown and future constants fall back to CONTINUE`() {
        assertEquals(ManeuverType.CONTINUE, GoogleManeuverMapper.toNormalized(0))
        assertEquals(ManeuverType.CONTINUE, GoogleManeuverMapper.toNormalized(999))
        assertEquals(ManeuverType.CONTINUE, GoogleManeuverMapper.toNormalized(-1))
    }
}
