package com.hunternav.domain.usecase

import com.hunternav.core.result.AppResult
import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.Route
import com.hunternav.domain.repository.RoutingProvider

/** Fetches candidate routes for a trip. Keeps provider selection out of UI/ViewModels. */
class CalculateRoutesUseCase(private val routingProvider: RoutingProvider) {

    suspend operator fun invoke(
        origin: Coordinate,
        destination: Coordinate,
        alternatives: Boolean = true,
    ): AppResult<List<Route>> = routingProvider.getRoutes(origin, destination, alternatives)
}
