package com.hunternav.display.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.hunternav.domain.model.DisplayNavigationState
import com.hunternav.domain.model.ManeuverType
import com.hunternav.domain.model.NavPhase
import com.hunternav.display.tiles.WebMercator
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.sin

/**
 * Software renderer that turns a [DisplayNavigationState] into a small RGB bitmap for the
 * external handlebar display (JPEG frames over the wire protocol). No map SDK, no GPU —
 * plain android.graphics so it runs on any thread and produces deterministic output.
 *
 * Layout scales from the original 480×320 design to any resolution (the initial hardware
 * target is 240×240, configurable via `display.width`/`display.height` in local.properties):
 *  - top band: maneuver glyph + distance-to-maneuver (or phase text: REROUTING / ARRIVED / GPS LOST)
 *  - map: heading-up route view — route polyline in cobalt, traveled context dimmed, the
 *    rider position fixed at screen center as an up-pointing arrow (rotates with the road)
 *  - footer: remaining distance + ETA clock time, road name when known
 *
 * Zoom is chosen so roughly [VIEW_AHEAD_METERS] meters ahead fill the map height.
 */
class FrameRenderer(
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
) {

    private val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)

    // ------------------------------------------------------------------ scaled layout
    // Geometry/fonts are proportions of the 480×320 reference design (see [FrameLayout]) so
    // 240×240 — or any other panel — keeps the same visual hierarchy.
    private val layout = FrameLayout(width, height)
    private val scale: Float get() = layout.scale
    private val bandHeight: Float get() = layout.bandHeight
    private val footerHeight: Float get() = layout.footerHeight
    private val roadOffsetY: Float get() = layout.roadOffsetY
    private val mapCenterY: Float get() = layout.mapCenterY
    private fun sp(designPx: Float): Float = layout.sp(designPx)

    private val backgroundPaint = Paint().apply { color = COLOR_BACKGROUND }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_ROUTE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = sp(9f)
    }
    private val traveledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TRAVELED
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = sp(7f)
    }
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_POINTER }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT
        textAlign = Paint.Align.CENTER
    }
    private val bandPaint = Paint().apply { color = COLOR_BAND }

    /** Renders [state] and returns the shared bitmap (valid until the next [render] call). */
    fun render(state: DisplayNavigationState): Bitmap {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        drawMap(state)
        drawTopBand(state)
        drawFooter(state)
        return bitmap
    }

    // ------------------------------------------------------------------ map

    private fun drawMap(state: DisplayNavigationState) {
        val geometry = state.routeGeometry
        val posLat = if (state.snapped != null) state.snapped.latitude else state.latitude
        val posLon = if (state.snapped != null) state.snapped.longitude else state.longitude
        val hasPosition = posLat != 0.0 || posLon != 0.0
        if (geometry.size < 2 || !hasPosition) return

        val zoom = chooseZoom(posLat)
        val center = WebMercator.latLngToWorldPixel(posLat, posLon, zoom)
        val headingRad = Math.toRadians(if (state.headingValid) state.bearing else 0.0)
        val cosH = cos(headingRad).toFloat()
        val sinH = sin(headingRad).toFloat()
        val cx = width / 2f
        val cy = mapCenterY

        fun toScreen(index: Int): FloatArray {
            val world = WebMercator.latLngToWorldPixel(geometry[index].latitude, geometry[index].longitude, zoom)
            val dx = (world[0] - center[0]).toFloat()
            val dy = (world[1] - center[1]).toFloat()
            // Heading-up: rotate the world by -bearing so the rider's heading points up.
            return floatArrayOf(cx + dx * cosH + dy * sinH, cy - dx * sinH + dy * cosH)
        }

        // Split the polyline at the rider's nearest vertex: traveled part dimmed ahead.
        val nearest = nearestIndex(state, geometry, posLat, posLon)

        if (nearest > 0) {
            val traveled = Path()
            val first = toScreen(0)
            traveled.moveTo(first[0], first[1])
            for (i in 1..nearest) {
                val p = toScreen(i)
                traveled.lineTo(p[0], p[1])
            }
            canvas.drawPath(traveled, traveledPaint)
        }
        val route = Path()
        val start = toScreen(nearest)
        route.moveTo(start[0], start[1])
        for (i in nearest + 1 until geometry.size) {
            val p = toScreen(i)
            route.lineTo(p[0], p[1])
        }
        canvas.drawPath(route, routePaint)
        drawPointer(cx, cy)
    }

    private fun nearestIndex(state: DisplayNavigationState, geometry: List<com.hunternav.domain.model.Coordinate>, lat: Double, lon: Double): Int {
        // The provider keeps geometry in route order and the rider roughly on it; the last
        // rendered index is a good warm start, so scan around it before the full pass.
        var best = lastNearestIndex.coerceIn(0, geometry.lastIndex)
        var bestDist = Double.MAX_VALUE
        val from = (best - WINDOW).coerceAtLeast(0)
        val to = (best + WINDOW).coerceAtMost(geometry.lastIndex)
        for (i in from..to) {
            val d = squaredDistance(lat, lon, geometry[i].latitude, geometry[i].longitude)
            if (d < bestDist) { bestDist = d; best = i }
        }
        lastNearestIndex = best
        return best
    }

    private var lastNearestIndex = 0

    private fun squaredDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat1 - lat2) * METERS_PER_DEG_LAT
        val dLon = (lon1 - lon2) * METERS_PER_DEG_LAT * cos(Math.toRadians((lat1 + lat2) / 2.0))
        return dLat * dLat + dLon * dLon
    }

    /** Zoom such that ~[VIEW_AHEAD_METERS] meters fit vertically in the map area. */
    private fun chooseZoom(latitude: Double): Double {
        val mapHeight = layout.mapAreaHeight
        val desiredMpp = VIEW_AHEAD_METERS / mapHeight
        val world = 40075016.686 * cos(Math.toRadians(WebMercator.clampLatitude(latitude)))
        return log2(world / (desiredMpp * WebMercator.TILE_SIZE)).coerceIn(3.0, 19.0)
    }

    private fun drawPointer(cx: Float, cy: Float) {
        val size = sp(POINTER_SIZE)
        val path = Path()
        path.moveTo(cx, cy - size)
        path.lineTo(cx - size * 0.62f, cy + size * 0.55f)
        path.lineTo(cx, cy + size * 0.25f)
        path.lineTo(cx + size * 0.62f, cy + size * 0.55f)
        path.close()
        canvas.drawPath(path, pointerPaint)
    }

    // ------------------------------------------------------------------ chrome

    private fun drawTopBand(state: DisplayNavigationState) {
        canvas.drawRect(0f, 0f, width.toFloat(), bandHeight, bandPaint)
        val label: String = when (state.phase) {
            NavPhase.IDLE -> "READY"
            NavPhase.REROUTING -> "REROUTING…"
            NavPhase.ARRIVED -> "ARRIVED"
            NavPhase.ERROR -> "GPS LOST"
            NavPhase.NAVIGATING -> maneuverGlyph(state.maneuver)
        }
        textPaint.textSize = sp(GLYPH_SIZE)
        textPaint.color = if (state.phase == NavPhase.ERROR || state.phase == NavPhase.REROUTING) {
            COLOR_WARNING
        } else {
            COLOR_TEXT
        }
        val glyphX = width * 0.16f
        canvas.drawText(label, glyphX, bandHeight / 2f + sp(GLYPH_SIZE) * 0.35f, textPaint)

        if (state.phase == NavPhase.NAVIGATING && state.distanceToManeuverMeters >= 0) {
            textPaint.textSize = sp(DISTANCE_SIZE)
            textPaint.color = COLOR_TEXT
            canvas.drawText(
                formatMeters(state.distanceToManeuverMeters),
                width * 0.62f,
                bandHeight / 2f + sp(DISTANCE_SIZE) * 0.35f,
                textPaint,
            )
        }
    }

    private fun drawFooter(state: DisplayNavigationState) {
        val top = height - footerHeight
        canvas.drawRect(0f, top.toFloat(), width.toFloat(), height.toFloat(), bandPaint)
        textPaint.textSize = sp(FOOTER_SIZE)
        textPaint.color = COLOR_TEXT
        val remaining = if (state.remainingDistanceMeters >= 0) {
            formatMeters(state.remainingDistanceMeters)
        } else {
            "--"
        }
        val eta = state.etaEpochMs?.let { " · ${formatClock(it)}" } ?: ""
        canvas.drawText("$remaining$eta", width / 2f, top + footerHeight * 0.72f, textPaint)
        if (!state.roadName.isNullOrBlank()) {
            textPaint.textSize = sp(ROAD_SIZE)
            textPaint.color = COLOR_MUTED
            canvas.drawText(state.roadName, width / 2f, top + roadOffsetY, textPaint)
        }
    }

    // ------------------------------------------------------------------ helpers

    internal fun maneuverGlyph(maneuver: ManeuverType?): String = when (maneuver) {
        ManeuverType.START, ManeuverType.CONTINUE -> "⬆"
        ManeuverType.SLIGHT_LEFT -> "↖"
        ManeuverType.LEFT -> "⬅"
        ManeuverType.SHARP_LEFT -> "⬙"
        ManeuverType.SLIGHT_RIGHT -> "↗"
        ManeuverType.RIGHT -> "➡"
        ManeuverType.SHARP_RIGHT -> "⬘"
        ManeuverType.U_TURN -> "⬌"
        ManeuverType.ROUNDABOUT -> "↻"
        ManeuverType.ARRIVE -> "⌂"
        null -> "•"
    }

    internal fun formatMeters(meters: Double): String = when {
        meters >= 10000 -> "${(meters / 1000).toInt()} km"
        meters >= 1000 -> String.format("%.1f km", meters / 1000)
        else -> "${meters.toInt()} m"
    }

    internal fun formatClock(epochMs: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getDefault())
        cal.timeInMillis = epochMs
        return String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    }

    /** Stable size the encoder was built for — the wire protocol carries it per frame. */
    val frameWidth: Int get() = width
    val frameHeight: Int get() = height

    private companion object {
        /** Initial hardware target resolution (configurable via display.width/height). */
        const val DEFAULT_WIDTH = 240
        const val DEFAULT_HEIGHT = 240
        const val GLYPH_SIZE = 44f
        const val DISTANCE_SIZE = 40f
        const val FOOTER_SIZE = 26f
        const val ROAD_SIZE = 18f
        const val POINTER_SIZE = 22f
        const val VIEW_AHEAD_METERS = 260.0
        const val METERS_PER_DEG_LAT = 111_320.0
        const val WINDOW = 40

        const val COLOR_BACKGROUND = 0xFF0B0F14.toInt()
        const val COLOR_BAND = 0xFF121A23.toInt()
        const val COLOR_ROUTE = 0xFF3B82F6.toInt()
        const val COLOR_TRAVELED = 0xFF24344A.toInt()
        const val COLOR_POINTER = 0xFFF8FAFC.toInt()
        const val COLOR_TEXT = 0xFFF8FAFC.toInt()
        const val COLOR_MUTED = 0xFF8CA0B5.toInt()
        const val COLOR_WARNING = 0xFFFBBF24.toInt()
    }
}
