package com.hunternav.display.render

/**
 * Pure layout geometry for [FrameRenderer], scaled from the original 480×320 reference design
 * to any panel resolution (the initial hardware target is 240×240). Kept free of
 * `android.graphics` so the math is unit-testable on the JVM.
 *
 * Fractions of the reference height: top band 84/320, footer 56/320, road-name baseline
 * (84+22)/320 below the band top, map center biased 20/320 below the vertical middle of the
 * map area. Fonts and stroke widths are multiplied by [scale] (min of the two axis ratios,
 * so a square panel never distorts type).
 */
data class FrameLayout(val width: Int, val height: Int) {

    /** Uniform type/stroke scale relative to the 480×320 reference design. */
    val scale: Float = minOf(width / REFERENCE_WIDTH, height / REFERENCE_HEIGHT)

    /** Height of the top maneuver band. */
    val bandHeight: Float = height * BAND_FRACTION

    /** Height of the bottom remaining/ETA band. */
    val footerHeight: Float = height * FOOTER_FRACTION

    /** Baseline distance from frame top to the road-name line. */
    val roadOffsetY: Float = bandHeight + height * ROAD_OFFSET_FRACTION

    /** Map area vertical center, biased slightly down so more road-ahead is visible. */
    val mapCenterY: Float = (height - footerHeight) / 2f + height * MAP_CENTER_BIAS_FRACTION

    /** Map area height (between band and footer); drives the meters-ahead zoom heuristic. */
    val mapAreaHeight: Double get() = (height - bandHeight - footerHeight).toDouble()

    /** Scales a design-space pixel size (defined at 480×320) to this panel. */
    fun sp(designPx: Float): Float = designPx * scale

    companion object {
        /** Reference design the fractions/sizes were authored against. */
        const val REFERENCE_WIDTH = 480f
        const val REFERENCE_HEIGHT = 320f

        const val BAND_FRACTION = 84f / 320f
        const val FOOTER_FRACTION = 56f / 320f
        const val MAP_CENTER_BIAS_FRACTION = 20f / 320f
        const val ROAD_OFFSET_FRACTION = (84f + 22f) / 320f
    }
}
