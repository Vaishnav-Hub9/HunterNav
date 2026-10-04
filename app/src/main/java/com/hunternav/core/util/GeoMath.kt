package com.hunternav.core.util

/** Earth radius used for great-circle maths. */
private const val EARTH_RADIUS_METERS = 6_371_000.0

/** Great-circle distance between two coordinates in meters (haversine). */
fun distanceMeters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return 2 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

/** Distance from a point to a line segment (p, q) in meters, plus the closest point on the segment. */
fun distanceToSegmentMeters(
    pLat: Double,
    pLon: Double,
    aLat: Double,
    aLon: Double,
    bLat: Double,
    bLon: Double,
): Pair<Double, Pair<Double, Double>> {
    // Local flat projection is accurate enough at navigation scales (< ~10 km segments).
    val latScale = 111_320.0
    val lonScale = 111_320.0 * Math.cos(Math.toRadians(pLat))

    val ax = (aLon - pLon) * lonScale
    val ay = (aLat - pLat) * latScale
    val bx = (bLon - pLon) * lonScale
    val by = (bLat - pLat) * latScale

    val dx = bx - ax
    val dy = by - ay
    val lengthSquared = dx * dx + dy * dy

    val t = if (lengthSquared == 0.0) 0.0 else ((-ax * dx) + (-ay * dy)) / lengthSquared
    val clamped = t.coerceIn(0.0, 1.0)
    val cx = ax + clamped * dx
    val cy = ay + clamped * dy
    val dist = Math.hypot(cx, cy)

    val cLat = pLat + cy / latScale
    val cLon = pLon + cx / lonScale
    return dist to (cLat to cLon)
}

/** Initial bearing (degrees clockwise from north) from p1 to p2. */
fun bearingDegrees(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val lat1r = Math.toRadians(lat1)
    val lat2r = Math.toRadians(lat2)
    val dLon = Math.toRadians(lon2 - lon1)
    val y = Math.sin(dLon) * Math.cos(lat2r)
    val x = Math.cos(lat1r) * Math.sin(lat2r) - Math.sin(lat1r) * Math.cos(lat2r) * Math.cos(dLon)
    return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
}

/** Smallest signed difference between two bearings, in [-180, 180]. */
fun angleDelta(from: Double, to: Double): Double = ((to - from + 540.0) % 360.0) - 180.0
