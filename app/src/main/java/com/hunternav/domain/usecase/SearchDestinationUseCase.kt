package com.hunternav.domain.usecase

import com.hunternav.core.result.AppResult
import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.Destination
import com.hunternav.domain.repository.GeocodingProvider

/** Destination search over the geocoding abstraction (Nominatim in V1). */
class SearchDestinationUseCase(private val geocodingProvider: GeocodingProvider) {

    suspend operator fun invoke(query: String, near: Coordinate?, limit: Int = 8): AppResult<List<Destination>> =
        geocodingProvider.search(query, near, limit)

    suspend fun reverse(coordinate: Coordinate): AppResult<Destination> = geocodingProvider.reverse(coordinate)
}
