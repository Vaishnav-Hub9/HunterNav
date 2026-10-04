package com.hunternav.domain.model

import com.hunternav.core.util.bearingDegrees
import com.hunternav.core.util.distanceMeters

/** A geographic coordinate. */
data class Coordinate(val latitude: Double, val longitude: Double) {
    fun distanceTo(other: Coordinate): Double =
        distanceMeters(latitude, longitude, other.latitude, other.longitude)

    fun bearingTo(other: Coordinate): Double =
        bearingDegrees(latitude, longitude, other.latitude, other.longitude)
}
