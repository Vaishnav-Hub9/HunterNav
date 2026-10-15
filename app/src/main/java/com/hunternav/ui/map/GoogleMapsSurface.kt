package com.hunternav.ui.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.hunternav.domain.model.CameraMode
import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.LocationData
import com.hunternav.domain.model.Route
import com.hunternav.ui.map.RenderThrottle
import com.hunternav.ui.theme.Cobalt

/**
 * Google Maps SDK implementation of [MapSurface] — the app's only map engine since the
 * Google-only migration (docs/google-maps.md). The API key arrives through the manifest
 * meta-data `com.google.android.geo.API_KEY` (from local.properties `google.maps.apiKey`).
 *
 * Maps SDK surface contract (verified against com.google.android.gms:play-services-maps):
 *  - `MapView.getMapAsync { googleMap -> … }` delivers the [GoogleMap] once the view is
 *    attached; there is no separate style-load step, so [loadStyle] completes when the map
 *    object is ready (tile failures surface as blank areas, reported through the SDK logs);
 *  - route/traveled polylines via [PolylineOptions], destination marker via [MarkerOptions],
 *    user position via the built-in my-location layer (permission-gated) with a fallback
 *    circle marker;
 *  - camera: [CameraUpdateFactory] ease/imate with bearing + [GoogleMap.setPadding] for the
 *    navigation puck offset; gestures are detected via [GoogleMap.setOnCameraMoveStartedListener].
 */
class GoogleMapsSurface(
    private val context: Context,
    private val mapView: MapView,
) : MapSurface {

    private var googleMap: GoogleMap? = null
    private var cameraMode: CameraMode = CameraMode.FOLLOW
    private var navPadding: IntArray? = null

    private var routeLine: Polyline? = null
    private var traveledLine: Polyline? = null
    private var destinationMarker: com.google.android.gms.maps.model.Marker? = null

    private var lastRoute: Route? = null
    private var lastProgress: Pair<Route, Int>? = null
    private var lastDestination: Coordinate? = null

    private val throttle = RenderThrottle()

    private var onStyleReady: (() -> Unit)? = null
    private var onStyleError: ((String) -> Unit)? = null

    override var onUserGesture: (() -> Unit)? = null

    override fun setNavPadding(left: Int, top: Int, right: Int, bottom: Int) {
        navPadding = intArrayOf(left, top, right, bottom)
        googleMap?.setPadding(left, top, right, bottom)
    }

    override fun loadStyle(onReady: () -> Unit, onError: (String) -> Unit) {
        onStyleReady = onReady
        onStyleError = onError
        startMapLoad()
    }

    override fun retryStyle() {
        if (onStyleReady == null) return
        startMapLoad()
    }

    private fun startMapLoad() {
        throttle.reset()
        mapView.getMapAsync { map ->
            googleMap = map
            configureMap(map)
            val ready = onStyleReady
            onStyleReady = null
            ready?.invoke()
        }
    }

    private fun configureMap(map: GoogleMap) {
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isCompassEnabled = false
        map.uiSettings.isMapToolbarEnabled = false
        navPadding?.let { map.setPadding(it[0], it[1], it[2], it[3]) }

        // Blue-dot layer when permission is granted; the engine still drives the camera.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            try {
                map.isMyLocationEnabled = true
                map.uiSettings.isMyLocationButtonEnabled = false
            } catch (_: SecurityException) {
                // Permission revoked mid-session — the engine's location stream still works.
            }
        }

        map.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                onUserGesture?.invoke()
            }
        }

        // Re-install the long-press listener on every (re)load; screens set theirs after
        // the surface is ready, so this is re-armed from [addOnMapLongClickListener].
        pendingLongPressListener?.let { listener -> installLongPress(listener) }

        // Redraw anything drawn before the map object existed.
        lastRoute?.let { drawRoute(it) }
        lastProgress?.let { drawProgress(it.first, it.second) }
        lastDestination?.let { drawDestination(it) }
    }

    override fun showUserLocation(location: LocationData) {
        // The Maps SDK my-location layer renders the puck; nothing engine-driven to draw
        // unless the layer is unavailable (permission denied) — then no-op is correct:
        // the camera still follows via [follow].
    }

    override fun showRoute(route: Route?) {
        lastRoute = route
        lastProgress = null
        if (googleMap == null) return
        drawRoute(route)
    }

    private fun drawRoute(route: Route?) {
        throttle.reset()
        traveledLine?.remove()
        traveledLine = null
        if (route == null || route.geometry.size < 2) {
            routeLine?.remove()
            routeLine = null
            return
        }
        val options = PolylineOptions()
            .color(Cobalt.toArgb())
        route.geometry.forEach { options.add(LatLng(it.latitude, it.longitude)) }
        routeLine?.remove()
        routeLine = googleMap?.addPolyline(options)
    }

    override fun showRouteProgress(route: Route, snappedIndex: Int) {
        lastProgress = route to snappedIndex
        if (googleMap == null) return
        drawProgress(route, snappedIndex)
    }

    private fun drawProgress(route: Route, snappedIndex: Int) {
        if (!throttle.shouldPublishProgress(route, snappedIndex)) return
        val map = googleMap ?: return
        if (route.geometry.size < 2) return
        val idx = snappedIndex.coerceIn(0, route.geometry.lastIndex)

        traveledLine?.remove()
        routeLine?.remove()

        if (idx > 0) {
            val traveled = PolylineOptions().color(Color.parseColor("#9BB7D6")).width(10f)
            for (i in 0..idx) traveled.add(LatLng(route.geometry[i].latitude, route.geometry[i].longitude))
            traveledLine = map.addPolyline(traveled)
        }
        val remaining = PolylineOptions().color(Cobalt.toArgb()).width(12f)
        for (i in idx until route.geometry.size) {
            remaining.add(LatLng(route.geometry[i].latitude, route.geometry[i].longitude))
        }
        routeLine = map.addPolyline(remaining)
    }

    override fun showDestination(coordinate: Coordinate?) {
        lastDestination = coordinate
        if (googleMap == null) return
        drawDestination(coordinate)
    }

    private fun drawDestination(coordinate: Coordinate?) {
        destinationMarker?.remove()
        destinationMarker = if (coordinate == null) {
            null
        } else {
            googleMap?.addMarker(
                MarkerOptions()
                    .position(LatLng(coordinate.latitude, coordinate.longitude))
                    .anchor(0.5f, 1.0f)
                    .flat(true)
                    .zIndex(1f),
            )
        }
    }

    override fun fitRoute(route: Route?) {
        val map = googleMap ?: return
        if (route == null || route.geometry.size < 2) return
        val bounds = LatLngBounds.builder()
        route.geometry.forEach { bounds.include(LatLng(it.latitude, it.longitude)) }
        try {
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), ROUTE_PADDING_PX))
        } catch (_: IllegalStateException) {
            // Map not laid out yet — camera will be corrected by the next follow/center call.
        }
    }

    override fun setCameraMode(mode: CameraMode) {
        cameraMode = mode
        if (mode == CameraMode.FOLLOW) {
            navPadding?.let { googleMap?.setPadding(it[0], it[1], it[2], it[3]) }
        }
    }

    override fun cameraMode(): CameraMode = cameraMode

    override fun follow(location: LocationData, bearing: Double, zoom: Double) {
        if (cameraMode != CameraMode.FOLLOW) return
        val map = googleMap ?: return
        // Skip camera ticks while the rider has barely moved (same policy as the previous engine).
        if (!throttle.shouldPublishUserLocation(location.latitude, location.longitude)) return
        val position = CameraPosition.builder()
            .target(LatLng(location.latitude, location.longitude))
            .bearing(bearing.toFloat())
            .zoom(zoom.toFloat())
            .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(position), CAMERA_EASE_MS, null)
    }

    override fun centerOn(coordinate: Coordinate, zoom: Double) {
        val map = googleMap ?: return
        val position = CameraPosition.builder()
            .target(LatLng(coordinate.latitude, coordinate.longitude))
            .zoom(zoom.toFloat())
            .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(position), CAMERA_ANIMATE_MS, null)
    }

    private var pendingLongPressListener: ((Coordinate) -> Boolean)? = null

    override fun addOnMapLongClickListener(listener: (Coordinate) -> Boolean) {
        pendingLongPressListener = listener
        googleMap?.let { installLongPress(listener) }
    }

    private fun installLongPress(listener: (Coordinate) -> Boolean) {
        googleMap?.setOnMapLongClickListener { latLng ->
            listener(Coordinate(latLng.latitude, latLng.longitude))
        }
    }

    private companion object {
        const val ROUTE_PADDING_PX = 120
        const val CAMERA_EASE_MS = 400
        const val CAMERA_ANIMATE_MS = 700
    }
}
