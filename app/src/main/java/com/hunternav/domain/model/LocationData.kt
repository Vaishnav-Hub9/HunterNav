package com.hunternav.domain.model

/** A smoothed GPS/location snapshot expressed in domain terms. */
data class LocationData(
    val latitude: Double,
    val longitude: Double,
    /** GPS accuracy radius in meters (68% confidence). */
    val accuracy: Float,
    /** Speed in m/s when available. */
    val speed: Float,
    /** Bearing in degrees clockwise from north, when reliable. */
    val bearing: Float,
    /** Epoch millis of the fix. */
    val timestamp: Long,
) {
    val coordinate: Coordinate get() = Coordinate(latitude, longitude)

    companion object {
        fun of(
            latitude: Double,
            longitude: Double,
            accuracy: Float = Float.MAX_VALUE,
            speed: Float = 0f,
            bearing: Float = 0f,
            timestamp: Long = 0L,
        ): LocationData = LocationData(latitude, longitude, accuracy, speed, bearing, timestamp)
    }
}
