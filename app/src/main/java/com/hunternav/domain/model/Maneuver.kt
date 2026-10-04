package com.hunternav.domain.model

/**
 * Normalized maneuver types. Providers (OSRM, GraphHopper, ...) are mapped into this enum by
 * their respective data layers; the UI never sees raw provider structures.
 */
enum class ManeuverType {
    START,
    CONTINUE,
    SLIGHT_LEFT,
    SLIGHT_RIGHT,
    LEFT,
    RIGHT,
    SHARP_LEFT,
    SHARP_RIGHT,
    U_TURN,
    ROUNDABOUT,
    ARRIVE,
}

/** A normalized navigation maneuver. */
data class Maneuver(
    val type: ManeuverType,
    val location: Coordinate,
    /** True when the step should be announced with priority (entrance from START, upcoming exit, etc.). */
    val exitNumber: Int? = null,
)
