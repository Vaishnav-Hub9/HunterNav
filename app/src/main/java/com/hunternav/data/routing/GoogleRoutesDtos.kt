package com.hunternav.data.routing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response DTOs for the Google Routes API v2 `directions/v2:computeRoutes` endpoint
 * (see docs/google-maps.md). Only the fields HunterNav consumes are declared; everything
 * else is ignored via `ignoreUnknownKeys`.
 *
 * Durations are protobuf `Duration` strings ("3456s" / "3456.500s"); geometries are
 * Google-encoded polylines (precision 5) under `polyline.encodedPolyline`.
 */
@Serializable
data class GoogleRoutesResponseDto(
    val routes: List<GoogleRouteDto> = emptyList(),
)

@Serializable
data class GoogleRouteDto(
    @SerialName("distanceMeters") val distanceMeters: Long? = null,
    val duration: String? = null,
    val polyline: GooglePolylineDto? = null,
    val legs: List<GoogleLegDto> = emptyList(),
)

@Serializable
data class GooglePolylineDto(
    val encodedPolyline: String? = null,
)

@Serializable
data class GoogleLegDto(
    @SerialName("distanceMeters") val distanceMeters: Long? = null,
    val duration: String? = null,
    val steps: List<GoogleStepDto> = emptyList(),
)

@Serializable
data class GoogleStepDto(
    @SerialName("distanceMeters") val distanceMeters: Long? = null,
    val duration: String? = null,
    val polyline: GooglePolylineDto? = null,
    val startLocation: GoogleLatLngContainerDto? = null,
    val endLocation: GoogleLatLngContainerDto? = null,
    val navigationInstruction: GoogleNavigationInstructionDto? = null,
    val staticInstruction: GoogleStaticInstructionDto? = null,
)

@Serializable
data class GoogleLatLngContainerDto(
    val location: GoogleLatLngDto? = null,
)

@Serializable
data class GoogleLatLngDto(
    val latitude: Double? = null,
    val longitude: Double? = null,
)

@Serializable
data class GoogleNavigationInstructionDto(
    /** Maneuver enum name, e.g. "TURN_LEFT" — absent on the final arrival step. */
    val maneuver: String? = null,
    val instructions: String? = null,
)

@Serializable
data class GoogleStaticInstructionDto(
    val maneuver: String? = null,
    val instruction: String? = null,
)
