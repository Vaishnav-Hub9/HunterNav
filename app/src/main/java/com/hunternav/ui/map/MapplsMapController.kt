package com.hunternav.ui.map

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import com.hunternav.domain.model.CameraMode
import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.LocationData
import com.hunternav.domain.model.Route
import com.hunternav.ui.theme.Cobalt
import com.hunternav.ui.theme.DestinationPin
import com.mappls.sdk.geojson.Feature
import com.mappls.sdk.geojson.LineString
import com.mappls.sdk.geojson.Point
import com.mappls.sdk.maps.MapView
import com.mappls.sdk.maps.MapplsMap
import com.mappls.sdk.maps.Style
import com.mappls.sdk.maps.camera.CameraPosition
import com.mappls.sdk.maps.camera.CameraUpdateFactory
import com.mappls.sdk.maps.geometry.LatLng
import com.mappls.sdk.maps.geometry.LatLngBounds
import com.mappls.sdk.maps.style.layers.CircleLayer
import com.mappls.sdk.maps.style.layers.LineLayer
import com.mappls.sdk.maps.style.layers.Property
import com.mappls.sdk.maps.style.layers.PropertyFactory
import com.mappls.sdk.maps.style.sources.GeoJsonSource

/**
 * Mappls Maps SDK implementation of [MapSurface].
 *
 * Every API used here was verified with `javap` against the shipped
 * `com.mappls.sdk:mappls-android-sdk:9.0.4` AAR (classes + `mappls-geojson` artifact):
 *  - `MapView.getMapAsync(OnMapReadyCallback)` → `onMapReady(MapplsMap)` / `onMapError(Int, String)`
 *  - `MapplsMap.getStyle(OnStyleLoaded)`, `easeCamera(CameraUpdate, Int)`,
 *    `setLatLngBoundsForCameraTarget(LatLngBounds)`, `setPadding(Int, Int, Int, Int)`,
 *    `addOnMapLongClickListener`, `addOnCameraMoveStartedListener`
 *  - `Style.addSource` / `addLayer`, `GeoJsonSource(id)`, `LineLayer` / `CircleLayer` + `PropertyFactory`
 *
 * Style loading: Mappls serves its default vector style through the authenticated tile
 * endpoint; `Style.Builder()` with no source loads that default, and [loadStyle] reports
 * failure through `OnMapReadyCallback.onMapError` plus a watchdog, exactly like the MapLibre
 * controller, so the screens' error/retry behavior is identical on both engines.
 */
class MapplsMapController(
    @Suppress("unused") private val context: Context,
    private val mapView: MapView,
) : MapSurface {

    private var style: Style? = null
    private var routeSource: GeoJsonSource? = null
    private var traveledRouteSource: GeoJsonSource? = null
    private var destinationSource: GeoJsonSource? = null
    private var userSource: GeoJsonSource? = null
    private var cameraMode: CameraMode = CameraMode.FOLLOW
    private var styleReady = false
    private var navPadding: IntArray? = null

    private var lastRoute: Route? = null
    private var lastRouteProgress: Pair<Route, Int>? = null
    private var lastDestination: Coordinate? = null

    private val throttle = RenderThrottle()

    private var onStyleReady: (() -> Unit)? = null
    private var onStyleError: ((String) -> Unit)? = null
    private var errorNotified = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var styleWatchdog: Runnable = Runnable {}

    override var onUserGesture: (() -> Unit)? = null

    override fun setNavPadding(left: Int, top: Int, right: Int, bottom: Int) {
        navPadding = intArrayOf(left, top, right, bottom)
        if (cameraMode == CameraMode.FOLLOW) {
            withMap { it.setPadding(left, top, right, bottom) }
        }
    }

    /**
     * `MapView.getMapAsync` takes `OnMapReadyCallback`, a TWO-method Java interface
     * (onMapReady + onMapError) — not a functional interface, so lambdas cannot SAM-convert;
     * every call site goes through this object-expression helper.
     */
    private fun withMap(action: (MapplsMap) -> Unit) {
        mapView.getMapAsync(object : com.mappls.sdk.maps.OnMapReadyCallback {
            override fun onMapReady(mapplsMap: MapplsMap) = action(mapplsMap)
            override fun onMapError(code: Int, message: String?) {
                notifyStyleError("The Mappls map failed to initialize (error $code).")
            }
        })
    }

    override fun loadStyle(onReady: () -> Unit, onError: (String) -> Unit) {
        onStyleReady = onReady
        onStyleError = onError
        startStyleLoad()
    }

    override fun retryStyle() {
        if (onStyleReady == null) return
        styleReady = false
        startStyleLoad()
    }

    private fun startStyleLoad() {
        errorNotified = false
        throttle.reset()
        mainHandler.removeCallbacks(styleWatchdog)
        styleWatchdog = Runnable {
            if (!styleReady) {
                notifyStyleError("The map is taking too long to load. Check your internet connection and retry.")
            }
        }
        mainHandler.postDelayed(styleWatchdog, STYLE_LOAD_TIMEOUT_MS)

        mapView.getMapAsync(object : com.mappls.sdk.maps.OnMapReadyCallback {
            override fun onMapReady(mapplsMap: MapplsMap) {
                // Keep the camera in the supported operating area (same policy as MapLibre).
                val tl = com.hunternav.domain.model.SupportedRegion.TELANGANA.padded(0.15)
                mapplsMap.setLatLngBoundsForCameraTarget(
                    LatLngBounds.Builder()
                        .include(LatLng(tl.minLatitude, tl.minLongitude))
                        .include(LatLng(tl.maxLatitude, tl.maxLongitude))
                        .build(),
                )
                mapplsMap.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapplsMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                        onUserGesture?.invoke()
                    }
                }
                // Default Mappls vector style (authenticated by the build-time auth files).
                mapplsMap.getStyle { loaded ->
                    this@MapplsMapController.style = loaded
                    installRouteLayers(loaded)
                    styleReady = true
                    mainHandler.removeCallbacks(styleWatchdog)
                    onStyleReady?.invoke()
                }
            }

            override fun onMapError(code: Int, message: String?) {
                notifyStyleError(
                    if (message.isNullOrBlank()) {
                        "The Mappls map failed to initialize (error $code). Check your internet connection and retry."
                    } else {
                        "The map failed to load ($message). Check your internet connection and retry."
                    },
                )
            }
        })
    }

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
                lineCapProperty(Property.LINE_CAP_ROUND),
                lineJoinProperty(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                PropertyFactory.lineWidth(8f),
                PropertyFactory.lineColor(Cobalt.toArgbInt()),
                lineCapProperty(Property.LINE_CAP_ROUND),
                lineJoinProperty(Property.LINE_JOIN_ROUND),
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
        userSource = GeoJsonSource(USER_SOURCE_ID).also { style.addSource(it) }
        style.addLayer(
            CircleLayer(USER_LAYER_ID, USER_SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleColor(Cobalt.toArgbInt()),
                PropertyFactory.circleStrokeWidth(4f),
                PropertyFactory.circleStrokeColor(Color.WHITE),
            ),
        )

        applyDestination()
        if (lastRouteProgress != null) applyRouteProgress() else applyRoute()
    }

    override fun showUserLocation(location: LocationData) {
        if (!throttle.shouldPublishUserLocation(location.latitude, location.longitude)) return
        userSource?.setGeoJson(
            Feature.fromGeometry(Point.fromLngLat(location.longitude, location.latitude)),
        )
    }

    override fun showRoute(route: Route?) {
        lastRoute = route
        lastRouteProgress = null
        applyRoute()
    }

    private fun applyRoute() {
        val source = routeSource ?: return
        val route = lastRoute
        if (!throttle.shouldPublishRoute(route)) return
        if (route == null || route.geometry.isEmpty()) {
            source.setGeoJson(FeatureCollectionEmpty())
        } else {
            val line = LineString.fromLngLats(route.geometry.map { Point.fromLngLat(it.longitude, it.latitude) })
            source.setGeoJson(Feature.fromGeometry(line))
            traveledRouteSource?.setGeoJson(FeatureCollectionEmpty())
        }
    }

    override fun showRouteProgress(route: Route, snappedIndex: Int) {
        lastRoute = route
        lastRouteProgress = route to snappedIndex
        applyRouteProgress()
    }

    private fun applyRouteProgress() {
        val source = routeSource ?: return
        val (route, snappedIndex) = lastRouteProgress ?: return
        val geometry = route.geometry
        if (geometry.isEmpty()) return
        if (!throttle.shouldPublishProgress(route, snappedIndex)) return
        val toPoint: (Coordinate) -> Point = { Point.fromLngLat(it.longitude, it.latitude) }

        val remaining = if (snappedIndex in geometry.indices) geometry.subList(snappedIndex, geometry.size) else geometry
        if (remaining.size >= 2) {
            routeSource?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(remaining.map(toPoint))))
        } else {
            routeSource?.setGeoJson(FeatureCollectionEmpty())
        }

        val traveled = if (snappedIndex in 1 until geometry.size) geometry.subList(0, snappedIndex + 1) else emptyList()
        if (traveled.size >= 2) {
            traveledRouteSource?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(traveled.map(toPoint))))
        } else {
            traveledRouteSource?.setGeoJson(FeatureCollectionEmpty())
        }
    }

    override fun showDestination(coordinate: Coordinate?) {
        lastDestination = coordinate
        applyDestination()
    }

    private fun applyDestination() {
        val source = destinationSource ?: return
        val coordinate = lastDestination
        if (coordinate == null) {
            source.setGeoJson(FeatureCollectionEmpty())
        } else {
            source.setGeoJson(Feature.fromGeometry(Point.fromLngLat(coordinate.longitude, coordinate.latitude)))
        }
    }

    override fun fitRoute(route: Route?) {
        val geometry = route?.geometry ?: return
        if (geometry.size < 2) return
        withMap { map ->
            val bounds = LatLngBounds.Builder()
                .includes(geometry.map { LatLng(it.latitude, it.longitude) })
                .build()
            map.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, 140), 450)
        }
    }

    override fun setCameraMode(mode: CameraMode) {
        cameraMode = mode
        val padding = navPadding
        if (mode == CameraMode.FOLLOW && padding != null) {
            withMap { it.setPadding(padding[0], padding[1], padding[2], padding[3]) }
        } else if (mode == CameraMode.OVERVIEW) {
            withMap { it.setPadding(0, 0, 0, 0) }
        }
    }

    override fun cameraMode(): CameraMode = cameraMode

    override fun follow(location: LocationData, bearing: Double, zoom: Double) {
        if (cameraMode != CameraMode.FOLLOW) return
        withMap { map ->
            val builder = CameraPosition.Builder()
                .target(LatLng(location.latitude, location.longitude))
                .zoom(zoom)
                .tilt(TILT_DEGREES)
            if (bearing in 1.0..359.0) builder.bearing(bearing)
            map.easeCamera(CameraUpdateFactory.newCameraPosition(builder.build()), FOLLOW_EASE_MS)
        }
    }

    override fun centerOn(coordinate: Coordinate, zoom: Double) {
        withMap { map ->
            val position = CameraPosition.Builder()
                .target(LatLng(coordinate.latitude, coordinate.longitude))
                .zoom(zoom)
                .build()
            map.easeCamera(CameraUpdateFactory.newCameraPosition(position), 500)
        }
    }

    override fun addOnMapLongClickListener(listener: (Coordinate) -> Boolean) {
        withMap { map ->
            map.addOnMapLongClickListener { latLng ->
                listener(Coordinate(latLng.latitude, latLng.longitude))
            }
        }
    }

    private fun lineCapProperty(value: String) = PropertyFactory.lineCap(value)
    private fun lineJoinProperty(value: String) = PropertyFactory.lineJoin(value)


    private fun FeatureCollectionEmpty() =
        com.mappls.sdk.geojson.FeatureCollection.fromFeatures(emptyList())

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
        const val STYLE_LOAD_TIMEOUT_MS = 12_000L
        const val FOLLOW_EASE_MS = 400
    }
}
