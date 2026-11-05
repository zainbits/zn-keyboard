package dev.zain.znkeyboard.constants

import android.graphics.Color

/** Scan text's crop rectangle. It sits on the camera image, so its colors don't follow the theme. */
object ScanAreaDefaults {
    const val MIN_WIDTH_DP = 64f
    const val MIN_HEIGHT_DP = 28f
    const val CORNER_RADIUS_DP = 12f
    const val BRACKET_LENGTH_DP = 20f
    const val BRACKET_STROKE_DP = 3f
    const val BORDER_STROKE_DP = 1f

    /** Touch reach around a corner (resizes both sides) and an edge (resizes one side). */
    const val CORNER_GRAB_DP = 32f
    const val EDGE_GRAB_DP = 22f

    /** The small remove button riding just above the area's top-right corner. */
    const val REMOVE_RADIUS_DP = 10f
    const val REMOVE_GAP_DP = 6f
    const val REMOVE_CROSS_ARM_DP = 3.5f
    const val REMOVE_STROKE_DP = 1.6f
    const val REMOVE_TOUCH_EXTRA_DP = 10f

    /** Max distance between the two taps of a double tap, which resets to the whole frame. */
    const val DOUBLE_TAP_SLOP_DP = 48f

    /** A rectangle covering at least this much of the frame on both axes counts as the whole frame. */
    const val SNAP_TO_FULL_FRACTION = 0.97f

    val DIM_COLOR = Color.argb(120, 0, 0, 0)
    val BORDER_COLOR = Color.argb(110, 255, 255, 255)
    val BORDER_ACTIVE_COLOR = Color.argb(220, 255, 255, 255)
    val BRACKET_COLOR = Color.WHITE
    val REMOVE_FILL_COLOR = Color.argb(170, 0, 0, 0)
    val REMOVE_BORDER_COLOR = Color.argb(140, 255, 255, 255)
    val REMOVE_CROSS_COLOR = Color.WHITE
}
