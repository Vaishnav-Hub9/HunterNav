package com.hunternav.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hunternav.domain.model.ManeuverType

/**
 * Vector maneuver icon for normalized [ManeuverType] values — crisp at any size, no icon
 * dependency, tintable for day/night later.
 */
@Composable
fun ManeuverIcon(
    type: ManeuverType,
    tint: Color,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(size), contentDescription = type.name) {
        val stroke = Stroke(width = this.size.minDimension * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val color = tint
        when (type) {
            ManeuverType.START -> drawStart(color, stroke)
            ManeuverType.ARRIVE -> drawArrive(color, stroke)
            ManeuverType.ROUNDABOUT -> drawRoundabout(color, stroke)
            ManeuverType.U_TURN -> drawUTurn(color, stroke)
            ManeuverType.CONTINUE -> drawStraight(color, stroke)
            ManeuverType.SLIGHT_LEFT -> drawSlightTurn(color, stroke, left = true)
            ManeuverType.SLIGHT_RIGHT -> drawSlightTurn(color, stroke, left = false)
            ManeuverType.LEFT -> drawTurn(color, stroke, left = true)
            ManeuverType.RIGHT -> drawTurn(color, stroke, left = false)
            ManeuverType.SHARP_LEFT -> drawSharpTurn(color, stroke, left = true)
            ManeuverType.SHARP_RIGHT -> drawSharpTurn(color, stroke, left = false)
        }
    }
}

private fun DrawScope.drawStraight(color: Color, stroke: Stroke) {
    val cx = size.width / 2
    drawLine(color, Offset(cx, size.height * 0.85f), Offset(cx, size.height * 0.2f), stroke.width, StrokeCap.Round)
    arrowHead(color, Offset(cx, size.height * 0.2f), 0f, stroke)
}

private fun DrawScope.drawTurn(color: Color, stroke: Stroke, left: Boolean) {
    val w = size.width
    val h = size.height
    val startY = h * 0.85f
    val cornerY = h * 0.35f
    val endX = if (left) w * 0.25f else w * 0.75f
    val startX = if (left) w * 0.72f else w * 0.28f
    // Vertical stem then horizontal branch.
    drawLine(color, Offset(startX, startY), Offset(startX, cornerY), stroke.width, StrokeCap.Round)
    drawLine(color, Offset(startX, cornerY), Offset(endX, cornerY), stroke.width, StrokeCap.Round)
    arrowHead(color, Offset(endX, cornerY), if (left) -90f else 90f, stroke)
}

private fun DrawScope.drawSharpTurn(color: Color, stroke: Stroke, left: Boolean) {
    val w = size.width
    val h = size.height
    val startX = if (left) w * 0.78f else w * 0.22f
    val endX = if (left) w * 0.22f else w * 0.78f
    drawLine(color, Offset(startX, h * 0.85f), Offset(startX, h * 0.45f), stroke.width, StrokeCap.Round)
    drawLine(color, Offset(startX, h * 0.45f), Offset(endX, h * 0.30f), stroke.width, StrokeCap.Round)
    arrowHead(color, Offset(endX, h * 0.30f), if (left) -120f else 120f, stroke)
}

private fun DrawScope.drawSlightTurn(color: Color, stroke: Stroke, left: Boolean) {
    val w = size.width
    val h = size.height
    val startX = if (left) w * 0.68f else w * 0.32f
    val endX = if (left) w * 0.30f else w * 0.70f
    drawLine(color, Offset(startX, h * 0.85f), Offset(startX, h * 0.55f), stroke.width, StrokeCap.Round)
    drawLine(color, Offset(startX, h * 0.55f), Offset(endX, h * 0.22f), stroke.width, StrokeCap.Round)
    arrowHead(color, Offset(endX, h * 0.22f), if (left) -45f else 45f, stroke)
}

private fun DrawScope.drawUTurn(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    val radius = w * 0.22f
    val cx = w * 0.5f
    drawArc(
        color = color,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(cx - radius, h * 0.32f - radius),
        size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
        style = stroke,
    )
    drawLine(color, Offset(cx - radius, h * 0.32f), Offset(cx - radius, h * 0.85f), stroke.width, StrokeCap.Round)
    drawLine(color, Offset(cx + radius, h * 0.32f), Offset(cx + radius, h * 0.60f), stroke.width, StrokeCap.Round)
    arrowHead(color, Offset(cx + radius, h * 0.60f), 90f, stroke)
}

private fun DrawScope.drawRoundabout(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    val radius = w * 0.24f
    val cx = w * 0.46f
    val cy = h * 0.5f
    drawCircle(color = color, radius = radius, center = Offset(cx, cy), style = stroke)
    drawLine(color, Offset(cx + radius, cy), Offset(w * 0.9f, cy), stroke.width, StrokeCap.Round)
    arrowHead(color, Offset(w * 0.9f, cy), 90f, stroke)
    // Entry stem.
    drawLine(color, Offset(cx, h * 0.88f), Offset(cx, cy + radius), stroke.width, StrokeCap.Round)
}

private fun DrawScope.drawStart(color: Color, stroke: Stroke) {
    // Dot + short stem: "begin route".
    drawCircle(color = color, radius = stroke.width * 1.4f, center = Offset(size.width / 2, size.height * 0.82f))
    drawLine(color, Offset(size.width / 2, size.height * 0.74f), Offset(size.width / 2, size.height * 0.22f), stroke.width, StrokeCap.Round)
    arrowHead(color, Offset(size.width / 2, size.height * 0.22f), 0f, stroke)
}

private fun DrawScope.drawArrive(color: Color, stroke: Stroke) {
    val cx = size.width / 2
    val cy = size.height * 0.45f
    drawCircle(color = color, radius = size.minDimension * 0.28f, center = Offset(cx, cy), style = stroke)
    drawCircle(color = color, radius = size.minDimension * 0.10f, center = Offset(cx, cy))
    drawLine(color, Offset(cx, cy + size.minDimension * 0.28f), Offset(cx, size.height * 0.88f), stroke.width, StrokeCap.Round)
}

private fun DrawScope.arrowHead(color: Color, tip: Offset, rotationDegrees: Float, stroke: Stroke) {
    rotate(rotationDegrees, pivot = tip) {
        val head = size.minDimension * 0.14f
        val path = Path().apply {
            moveTo(tip.x, tip.y - head)
            lineTo(tip.x - head * 0.9f, tip.y + head * 0.7f)
            lineTo(tip.x + head * 0.9f, tip.y + head * 0.7f)
            close()
        }
        drawPath(path, color)
    }
}
