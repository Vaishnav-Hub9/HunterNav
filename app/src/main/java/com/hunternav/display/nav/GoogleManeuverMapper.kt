package com.hunternav.display.nav

import com.hunternav.domain.model.ManeuverType

/**
 * Pure mapping from Google Navigation SDK `Maneuver` int constants to the app's normalized
 * [ManeuverType]. Kept free of SDK types so it is unit-testable on the JVM without the SDK.
 *
 * Constant values are the ones shipped in the Navigation SDK 8.0.0
 * (`com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver`), verified with
 * `javap -constants` against the released AAR — not guessed from declaration order.
 *
 * Unmapped sub-types (specific roundabout orientations, ramps, forks, ferries, name changes,
 * destination-side variants) collapse into the closest normalized type — the display draws
 * arrows and distances, not provider-specific minutiae.
 */
object GoogleManeuverMapper {

    // Exact values from Maneuver 8.0.0 (IntDef constants, javap-verified).
    private const val DEPART = 1
    private const val DESTINATION = 2
    private const val DESTINATION_LEFT = 3
    private const val DESTINATION_RIGHT = 4
    private const val STRAIGHT = 5
    private const val TURN_LEFT = 6
    private const val TURN_RIGHT = 7
    private const val TURN_KEEP_LEFT = 8
    private const val TURN_KEEP_RIGHT = 9
    private const val TURN_SLIGHT_LEFT = 10
    private const val TURN_SLIGHT_RIGHT = 11
    private const val TURN_SHARP_LEFT = 12
    private const val TURN_SHARP_RIGHT = 13
    private const val TURN_U_TURN_CLOCKWISE = 14
    private const val TURN_U_TURN_COUNTERCLOCKWISE = 15
    private const val MERGE_UNSPECIFIED = 16
    private const val MERGE_LEFT = 17
    private const val MERGE_RIGHT = 18
    private const val FORK_LEFT = 19
    private const val FORK_RIGHT = 20
    private const val ROUNDABOUT_CLOCKWISE = 43
    private const val ROUNDABOUT_COUNTERCLOCKWISE = 44
    private const val FERRY_BOAT = 63
    private const val FERRY_TRAIN = 64
    private const val NAME_CHANGE = 65

    /** Maps an SDK maneuver constant (any value) to a normalized type; unknown ⇒ [ManeuverType.CONTINUE]. */
    fun toNormalized(maneuver: Int): ManeuverType = when (maneuver) {
        DEPART -> ManeuverType.START
        DESTINATION, DESTINATION_LEFT, DESTINATION_RIGHT -> ManeuverType.ARRIVE
        STRAIGHT, NAME_CHANGE, MERGE_UNSPECIFIED, FERRY_BOAT, FERRY_TRAIN -> ManeuverType.CONTINUE
        TURN_LEFT -> ManeuverType.LEFT
        TURN_RIGHT -> ManeuverType.RIGHT
        TURN_KEEP_LEFT, MERGE_LEFT, FORK_LEFT -> ManeuverType.SLIGHT_LEFT
        TURN_KEEP_RIGHT, MERGE_RIGHT, FORK_RIGHT -> ManeuverType.SLIGHT_RIGHT
        TURN_SLIGHT_LEFT -> ManeuverType.SLIGHT_LEFT
        TURN_SLIGHT_RIGHT -> ManeuverType.SLIGHT_RIGHT
        TURN_SHARP_LEFT -> ManeuverType.SHARP_LEFT
        TURN_SHARP_RIGHT -> ManeuverType.SHARP_RIGHT
        TURN_U_TURN_CLOCKWISE, TURN_U_TURN_COUNTERCLOCKWISE -> ManeuverType.U_TURN
        ROUNDABOUT_CLOCKWISE, ROUNDABOUT_COUNTERCLOCKWISE -> ManeuverType.ROUNDABOUT
        else -> ManeuverType.CONTINUE // ramps, roundabout variants, future constants
    }
}
