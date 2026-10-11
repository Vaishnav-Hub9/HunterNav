package com.hunternav.domain.model

/** Axis-aligned geographic bounds (inclusive). */
data class RegionBounds(
    val minLatitude: Double,
    val minLongitude: Double,
    val maxLatitude: Double,
    val maxLongitude: Double,
) {
    fun contains(coordinate: Coordinate): Boolean =
        coordinate.latitude in minLatitude..maxLatitude &&
            coordinate.longitude in minLongitude..maxLongitude

    /** Center point (camera target for the region). */
    val center: Coordinate
        get() = Coordinate((minLatitude + maxLatitude) / 2.0, (minLongitude + maxLongitude) / 2.0)

    /** Bounds expanded by [paddingDegrees] on every side (camera margins, offline packs). */
    fun padded(paddingDegrees: Double): RegionBounds = RegionBounds(
        minLatitude - paddingDegrees,
        minLongitude - paddingDegrees,
        maxLatitude + paddingDegrees,
        maxLongitude + paddingDegrees,
    )
}

/**
 * The supported operating area for this prototype (spec P3: Hyderabad/Telangana only).
 *
 * Hyderabad is the default map/camera area; Telangana is the maximum supported navigation
 * area. Destinations outside it are rejected with an explanation, and the camera is bounded
 * so a stray long-press cannot drop a pin on the other side of the country. Bounds are a
 * generous bounding box around Telangana (state spans roughly 15.8–20.2 °N, 76.7–81.5 °E).
 */
object SupportedRegion {

    /** Maximum supported navigation area: Telangana, India (padded bounding box). */
    val TELANGANA = RegionBounds(
        minLatitude = 15.80,
        minLongitude = 76.60,
        maxLatitude = 20.25,
        maxLongitude = 81.60,
    )

    /** Default map area / offline pack: Hyderabad city region. */
    val HYDERABAD = RegionBounds(
        minLatitude = 17.20,
        minLongitude = 78.20,
        maxLatitude = 17.60,
        maxLongitude = 78.75,
    )

    const val NAME = "Telangana"

    fun isSupported(coordinate: Coordinate): Boolean = TELANGANA.contains(coordinate)

    /** Clear, user-facing explanation when a destination falls outside the supported area. */
    fun unsupportedDestinationMessage(title: String?): String {
        val label = title?.takeIf { it.isNotBlank() } ?: "That location"
        return "$label is outside the supported area ($NAME, India). " +
            "Pick a destination inside $NAME — this prototype doesn't route worldwide yet."
    }
}
