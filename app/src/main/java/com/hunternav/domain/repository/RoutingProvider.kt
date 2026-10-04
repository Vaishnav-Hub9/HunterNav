package com.hunternav.domain.repository

import com.hunternav.core.result.AppResult
import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.Destination
import com.hunternav.domain.model.Route

/**
 * Abstraction over routing engines (OSRM now; GraphHopper / offline later).
 * The navigation engine and UI depend only on this interface.
 */
interface RoutingProvider {
    /**
     * Requests routes from origin to destination.
     * @param alternatives include up to a small number of alternative routes when supported.
     */
    suspend fun getRoutes(
        origin: Coordinate,
        destination: Coordinate,
        alternatives: Boolean = true,
    ): AppResult<List<Route>>
}
