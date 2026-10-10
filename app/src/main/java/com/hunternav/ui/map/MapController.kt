package com.hunternav.ui.map

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.hunternav.BuildConfig
import com.hunternav.R
import com.hunternav.domain.model.CameraMode
import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.LocationData
import com.hunternav.domain.model.Route
import com.hunternav.ui.theme.Cobalt
import com.hunternav.ui.theme.DestinationPin
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Owns all MapLibre interaction: style loading (OpenFreeMap Liberty), route line rendering,
 * destination marker, camera modes and dynamic zoom. UI components never touch MapLibre APIs.
 */
class MapController(private val context: Context, private val mapView: MapView) {

    private var style: Style? = null
    private var routeSource: GeoJsonSource? = null
    private var traveledRouteSource: GeoJsonSource? = null
    private var destinationSource: GeoJsonSource? = null
    private var userSource: GeoJsonSource? = null
    private var cameraMode: CameraMode = CameraMode.FOLLOW
    private var styleReady = false
    private var navPadding: IntArray? = null

    // Last content the screen asked to draw. Sources only exist once the style is loaded, so
    // without this cache a route that arrives BEFORE the style finishes loading (fast fetch / slow
    // network) would be dropped and the preview would show an empty map until the next state change.
    private var lastRoute: Route? = null
    private var lastRouteProgress: Pair<Route, Int>? = null
    private var lastDestination: Coordinate? = null

    // Style-load failure reporting (spec §1: a broken map must show a user-visible error).
    private var onStyleReady: (() -> Unit)? = null
    private var onStyleError: ((String) -> Unit)? = null
    private var errorNotified = false
    private var failListenerRegistered = false
    private var cameraListenerRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var styleWatchdog: Runnable = Runnable {}

    /** Invoked when the user pans/zooms by gesture — screens switch to FREE mode (spec §14). */
    var onUserGesture: (() -> Unit)? = null

    /**
     * Camera padding for active navigation: reserving the top band (maneuver banner + route
     * ahead) makes the camera center the puck in the *lower-middle* of the screen (spec §14).
     */
    // setPadding(int,int,int,int) is soft-deprecated in MapLibre 13 but is still the only
    // padding API on MapLibreMap; kept deliberately for lower-middle puck placement.
    @Suppress("DEPRECATION")
    fun setNavPadding(left: Int, top: Int, right: Int, bottom: Int) {
        navPadding = intArrayOf(left, top, right, bottom)
        if (cameraMode == CameraMode.FOLLOW) {
            mapView.getMapAsync { it.setPadding(left, top, right, bottom) }
        }
    }

    /**
     * Loads the bright OpenFreeMap Liberty style and installs route layers.
     *
     * [onError] is invoked at most once per attempt when the map/style fails to load, so the
     * screen can show a real error instead of a blank map (spec §1). Failure sources:
     * MapLibre's map-load failure listener, and a watchdog for the case where the style
     * callback never fires at all. A late successful load clears the screen error via [onReady].
     */
    fun loadStyle(onReady: () -> Unit, onError: (String) -> Unit = {}) {
        onStyleReady = onReady
        onStyleError = onError
        startStyleLoad()
    }

    /** Re-attempts the style load after a failure (Retry button in the error overlay). */
    fun retryStyle() {
        if (onStyleReady == null) return
        styleReady = false
        startStyleLoad()
    }

    private fun startStyleLoad() {
        errorNotified = false
        if (!failListenerRegistered) {
            failListenerRegistered = true
            mapView.addOnDidFailLoadingMapListener { error ->
                notifyStyleError(
                    if (error.isNullOrBlank()) {
                        "The map failed to load. Check your internet connection and retry."
                    } else {
                        "The map failed to load ($error). Check your internet connection and retry."
                    },
                )
            }
        }
        mainHandler.removeCallbacks(styleWatchdog)
        styleWatchdog = Runnable {
            if (!styleReady) {
                notifyStyleError("The map is taking too long to load. Check your internet connection and retry.")
            }
        }
        mainHandler.postDelayed(styleWatchdog, STYLE_LOAD_TIMEOUT_MS)

        mapView.getMapAsync { map ->
            if (!cameraListenerRegistered) {
                cameraListenerRegistered = true
                map.addOnCameraMoveStartedListener(
                    object : MapLibreMap.OnCameraMoveStartedListener {
                        override fun onCameraMoveStarted(reason: Int) {
                            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                                onUserGesture?.invoke()
                            }
                        }
                    },
                )
            }
            map.setStyle(BuildConfig.MAP_STYLE_URL) { style ->
                this.style = style
                installRouteLayers(style)
                styleReady = true
                mainHandler.removeCallbacks(styleWatchdog)
                onStyleReady?.invoke()
            }
        }
    }

    /** Reports a style-load failure to the screen once per attempt. */
    private fun notifyStyleError(message: String) {
        if (styleReady || errorNotified) return
        errorNotified = true
        mainHandler.removeCallbacks(styleWatchdog)
        onStyleError?.invoke(message)
    }

    private fun installRouteLayers(style: Style) {
        routeSource = GeoJsonSource(ROUTE_SOURCE_ID).also { style.addSource(it) }
        traveledRouteSource = GeoJsonSource(TRAVELED_SOURCE_ID).also { style.addSource(it) }
        destinationSource = GeoJsonSource(DESTINATION_SOURCE_ID).also { style.addSource(it) }

        // White casing beneath the cobalt line for daylight contrast.
        style.addLayer(
            LineLayer(ROUTE_CASING_LAYER_ID, TRAVELED_SOURCE_ID).withProperties(
                PropertyFactory.lineWidth(13f),
                PropertyFactory.lineColor(Color.WHITE),
                PropertyFactory.lineOpacity(0.95f),
            ),
        )
        style.addLayer(
            LineLayer(TRAVELED_LAYER_ID, TRAVELED_SOURCE_ID).withProperties(
                PropertyFactory.lineWidth(8f),
                PropertyFactory.lineColor(Cobalt.copy(alpha = 0.35f).toArgbInt()),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                PropertyFactory.lineWidth(8f),
                PropertyFactory.lineColor(Cobalt.toArgbInt()),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            CircleLayer(DESTINATION_LAYER_ID, DESTINATION_SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleColor(DestinationPin.toArgbInt()),
                PropertyFactory.circleStrokeWidth(3f),
                PropertyFactory.circleStrokeColor(Color.WHITE),
            ),
        )

        // Current-location pointer: cobalt dot with white stroke, drawn above the route
        // so the rider's position is always obvious (spec §19, acceptance 5–6).
        userSource = GeoJsonSource(USER_SOURCE_ID).also { style.addSource(it) }
        style.addLayer(
            CircleLayer(USER_LAYER_ID, USER_SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleColor(Cobalt.toArgbInt()),
                PropertyFactory.circleStrokeWidth(4f),
                PropertyFactory.circleStrokeColor(Color.WHITE),
            ),
        )

        // (Re)apply anything drawn before the style was ready.
        applyDestination()
        if (lastRouteProgress != null) applyRouteProgress() else applyRoute()
    }

    /** Renders the current-location pointer. No-op until the style has loaded. */
    fun showUserLocation(location: LocationData) {
        userSource?.setGeoJson(
            Feature.fromGeometry(Point.fromLngLat(location.longitude, location.latitude)),
        )
    }

    /** Renders the full route (preview mode). Safe to call before the style has loaded. */
    fun showRoute(route: Route?) {
        lastRoute = route
        lastRouteProgress = null
        applyRoute()
    }

    private fun applyRoute() {
        val source = routeSource ?: return
        val route = lastRoute
        if (route == null || route.geometry.isEmpty()) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        } else {
            val line = LineString.fromLngLats(route.geometry.map { Point.fromLngLat(it.longitude, it.latitude) })
            source.setGeoJson(Feature.fromGeometry(line))
            traveledRouteSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        }
    }

    /**
     * Active-navigation rendering: the remaining route stays bright cobalt, the traveled part
     * dims. [snappedIndex] is the user's vertex index in the route geometry.
     */
    fun showRouteProgress(route: Route, snappedIndex: Int) {
        lastRoute = route
        lastRouteProgress = route to snappedIndex
        applyRouteProgress()
    }

    private fun applyRouteProgress() {
        val source = routeSource ?: return
        val (route, snappedIndex) = lastRouteProgress ?: return
        val geometry = route.geometry
        if (geometry.isEmpty()) return
        val toPoint: (Coordinate) -> Point = { Point.fromLngLat(it.longitude, it.latitude) }

        val remaining = if (snappedIndex in geometry.indices) geometry.subList(snappedIndex, geometry.size) else geometry
        if (remaining.size >= 2) {
            routeSource?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(remaining.map(toPoint))))
        } else {
            routeSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        }

        val traveled = if (snappedIndex in 1 until geometry.size) geometry.subList(0, snappedIndex + 1) else emptyList()
        if (traveled.size >= 2) {
            traveledRouteSource?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(traveled.map(toPoint))))
        } else {
            traveledRouteSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        }
    }

    fun showDestination(coordinate: Coordinate?) {
        lastDestination = coordinate
        applyDestination()
    }

    private fun applyDestination() {
        val source = destinationSource ?: return
        val coordinate = lastDestination
        if (coordinate == null) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        } else {
            source.setGeoJson(Feature.fromGeometry(Point.fromLngLat(coordinate.longitude, coordinate.latitude)))
        }
    }

    /** Fits the camera to the route bounds (OVERVIEW mode + route preview). */
    fun fitRoute(route: Route?) {
        val geometry = route?.geometry ?: return
        if (geometry.size < 2) return
        mapView.getMapAsync { map ->
            val bounds = LatLngBounds.Builder()
                .includes(geometry.map { LatLng(it.latitude, it.longitude) })
                .build()
            map.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, 140), 450)
        }
    }

    @Suppress("DEPRECATION")
    fun setCameraMode(mode: CameraMode) {
        cameraMode = mode
        val padding = navPadding
        if (mode == CameraMode.FOLLOW && padding != null) {
            mapView.getMapAsync { it.setPadding(padding[0], padding[1], padding[2], padding[3]) }
        } else if (mode == CameraMode.OVERVIEW) {
            mapView.getMapAsync { it.setPadding(0, 0, 0, 0) }
        }
    }

    fun cameraMode(): CameraMode = cameraMode

    /** Follows the user with bearing rotation (FOLLOW mode). Ease without animation for tight tracking. */
    fun follow(location: LocationData, bearing: Double, zoom: Double) {
        if (cameraMode != CameraMode.FOLLOW) return
        mapView.getMapAsync { map ->
            val builder = CameraPosition.Builder()
                .target(LatLng(location.latitude, location.longitude))
                .zoom(zoom)
                .tilt(TILT_DEGREES)
            if (bearing in 1.0..359.0) builder.bearing(bearing)
            map.moveCamera(CameraUpdateFactory.newCameraPosition(builder.build()))
        }
    }

    fun centerOn(coordinate: Coordinate, zoom: Double = BuildConfig.DEFAULT_ZOOM) {
        mapView.getMapAsync { map ->
            map.easeCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(coordinate.latitude, coordinate.longitude), zoom),
                500,
            )
        }
    }

    private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int =
        Color.argb(
            (alpha * 255).toInt(),
            (red * 255).toInt(),
            (green * 255).toInt(),
            (blue * 255).toInt(),
        )

    private companion object {
        const val ROUTE_SOURCE_ID = "hunter-route"
        const val TRAVELED_SOURCE_ID = "hunter-route-traveled"
        const val ROUTE_LAYER_ID = "hunter-route-line"
        const val TRAVELED_LAYER_ID = "hunter-route-traveled-line"
        const val ROUTE_CASING_LAYER_ID = "hunter-route-casing"
        const val DESTINATION_SOURCE_ID = "hunter-destination"
        const val DESTINATION_LAYER_ID = "hunter-destination-pin"
        const val USER_SOURCE_ID = "hunter-user-location"
        const val USER_LAYER_ID = "hunter-user-location-dot"
        const val TILT_DEGREES = 30.0

        /** Style-load watchdog: blank map longer than this ⇒ surface a user-visible error. */
        const val STYLE_LOAD_TIMEOUT_MS = 12_000L
    }
}
