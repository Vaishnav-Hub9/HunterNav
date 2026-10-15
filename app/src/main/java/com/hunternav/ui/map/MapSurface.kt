package com.hunternav.ui.map

import com.hunternav.domain.model.CameraMode
import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.LocationData
import com.hunternav.domain.model.Route

/**
 * Engine-agnostic map surface used by every screen. The single implementation is
 * [GoogleMapsSurface] — Google Maps SDK (docs/google-maps.md).
 *
 * Screens only ever see this interface and the domain models, so swapping or testing the map
 * engine never touches UI code. The implementation owns its native view lifecycle
 * (onCreate/onStart/onResume/onPause/onStop/onDestroy) via the [GoogleMapView] wrapper.
 */
interface MapSurface {

    /**
     * Loads the base map style and installs route/location layers. [onReady] fires when the
     * surface can draw; [onError] fires at most once per attempt with a user-displayable cause.
     */
    fun loadStyle(onReady: () -> Unit, onError: (String) -> Unit = {})

    /** Re-attempts the style load after a failure (Retry button in the error overlay). */
    fun retryStyle()

    /** Renders the current-location pointer. Skipped when the position has not changed. */
    fun showUserLocation(location: LocationData)

    /** Renders the full route (preview mode). Safe to call before the style has loaded. */
    fun showRoute(route: Route?)

    /** Active navigation rendering: remaining route bright, traveled part dimmed. */
    fun showRouteProgress(route: Route, snappedIndex: Int)

    /** Shows or clears the destination marker. */
    fun showDestination(coordinate: Coordinate?)

    /** Fits the camera to the route bounds (OVERVIEW + preview). */
    fun fitRoute(route: Route?)

    /** FOLLOW / OVERVIEW / FREE. Map no-ops outside FOLLOW so gestures stay in FREE. */
    fun setCameraMode(mode: CameraMode)

    fun cameraMode(): CameraMode

    /** Follows the user with bearing rotation (FOLLOW mode only). */
    fun follow(location: LocationData, bearing: Double, zoom: Double)

    /** Centers the camera on [coordinate]. */
    fun centerOn(coordinate: Coordinate, zoom: Double = com.hunternav.BuildConfig.DEFAULT_ZOOM)

    /** Camera padding so the puck sits in the lower-middle during navigation. */
    fun setNavPadding(left: Int, top: Int, right: Int, bottom: Int)

    /** Invoked when the user pans/zooms by gesture — screens switch to FREE mode. */
    var onUserGesture: (() -> Unit)?

    /** Long-press on the map → latitude/longitude (destination selection). */
    fun addOnMapLongClickListener(listener: (Coordinate) -> Boolean)
}
