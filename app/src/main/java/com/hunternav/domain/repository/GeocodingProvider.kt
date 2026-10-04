package com.hunternav.domain.repository

import com.hunternav.core.result.AppResult
import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.Destination

/** Abstraction over geocoding/search backends. Implementations are isolated in the data layer. */
interface GeocodingProvider {
    /** Forward-geocodes a free-text query into candidate destinations. */
    suspend fun search(query: String, near: Coordinate?, limit: Int = 8): AppResult<List<Destination>>

    /** Reverse-geocodes a map long-press coordinate into a human-readable destination. */
    suspend fun reverse(coordinate: Coordinate): AppResult<Destination>
}
