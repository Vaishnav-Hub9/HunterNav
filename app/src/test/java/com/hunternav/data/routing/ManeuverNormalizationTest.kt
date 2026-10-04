package com.hunternav.data.routing

import com.hunternav.domain.model.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Test

class ManeuverNormalizationTest {

    private fun type(t: String, m: String? = null) = OsrmMappers.toManeuverType(t, m)

    @Test
    fun `depart and arrive map to start and arrive`() {
        assertEquals(ManeuverType.START, type("depart"))
        assertEquals(ManeuverType.ARRIVE, type("arrive"))
    }

    @Test
    fun `turn modifiers map to normalized directions`() {
        assertEquals(ManeuverType.LEFT, type("turn", "left"))
        assertEquals(ManeuverType.RIGHT, type("turn", "right"))
        assertEquals(ManeuverType.SLIGHT_LEFT, type("turn", "slight left"))
        assertEquals(ManeuverType.SLIGHT_RIGHT, type("turn", "slight right"))
        assertEquals(ManeuverType.SHARP_LEFT, type("turn", "sharp left"))
        assertEquals(ManeuverType.SHARP_RIGHT, type("turn", "sharp right"))
        assertEquals(ManeuverType.U_TURN, type("turn", "uturn"))
        assertEquals(ManeuverType.CONTINUE, type("turn", "straight"))
        assertEquals(ManeuverType.CONTINUE, type("turn", null))
    }

    @Test
    fun `roundabout maps regardless of modifier`() {
        assertEquals(ManeuverType.ROUNDABOUT, type("roundabout"))
        assertEquals(ManeuverType.ROUNDABOUT, type("rotary", "right"))
    }

    @Test
    fun `ramp and fork maneuvers normalize`() {
        assertEquals(ManeuverType.RIGHT, type("on ramp", "right"))
        assertEquals(ManeuverType.SLIGHT_LEFT, type("fork", "slight left"))
        assertEquals(ManeuverType.RIGHT, type("end of road", "right"))
    }

    @Test
    fun `continue and new name map to continue or u-turn`() {
        assertEquals(ManeuverType.CONTINUE, type("continue"))
        assertEquals(ManeuverType.CONTINUE, type("new name"))
        assertEquals(ManeuverType.U_TURN, type("continue", "uturn"))
    }

    @Test
    fun `unknown types degrade gracefully to continue`() {
        assertEquals(ManeuverType.CONTINUE, type("some future maneuver"))
    }
}
