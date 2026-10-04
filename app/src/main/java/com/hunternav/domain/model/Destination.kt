package com.hunternav.domain.model

/** A user-chosen navigation endpoint, from search or a map long-press. */
data class Destination(
    val coordinate: Coordinate,
    /** Display name, when known from geocoding. */
    val title: String,
    /** Secondary display line (locality / road), when known. */
    val subtitle: String? = null,
)
