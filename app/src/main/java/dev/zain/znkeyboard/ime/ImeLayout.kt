package dev.zain.znkeyboard.ime

import android.content.Context
import kotlin.math.roundToInt

internal object ImeLayout {
    const val BASE_HEIGHT_DP = 282f
    const val HORIZONTAL_PADDING_DP = 6
    const val TOP_PADDING_DP = 6
    const val BASE_BOTTOM_PADDING_DP = 8
    const val KEY_GAP_DP = 5
    const val ROW_GAP_DP = 6
    const val KEY_RADIUS_DP = 5
    const val MIN_BOTTOM_SYSTEM_CONTROL_GAP_DP = 16
    const val BOTTOM_SYSTEM_CONTROL_GAP_MULTIPLIER = 2.0f
    const val ICON_VIEWPORT = 24f

    const val COMPACT_ROW_WEIGHT = 0.78f
    const val STANDARD_ROW_WEIGHT = 1f
    const val BOTTOM_ROW_WEIGHT = 1.08f
    const val ROW_COUNT_WITH_UPPER_ROW = 5
    const val ROW_WEIGHT_SUM_WITH_UPPER_ROW = COMPACT_ROW_WEIGHT +
        STANDARD_ROW_WEIGHT +
        STANDARD_ROW_WEIGHT +
        STANDARD_ROW_WEIGHT +
        BOTTOM_ROW_WEIGHT

    fun bottomSystemControlGapPx(context: Context): Float {
        val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val navigationBarHeightPx = if (resourceId != 0) {
            context.resources.getDimension(resourceId)
        } else {
            0f
        }
        val minimumGapPx = MIN_BOTTOM_SYSTEM_CONTROL_GAP_DP * context.resources.displayMetrics.density
        return navigationBarHeightPx.coerceAtLeast(minimumGapPx) * BOTTOM_SYSTEM_CONTROL_GAP_MULTIPLIER
    }

    fun compactAgentRowHeightPx(context: Context, scale: Float): Int {
        val density = context.resources.displayMetrics.density
        val baseHeightPx = BASE_HEIGHT_DP * scale * density
        val reservedHeightPx = (TOP_PADDING_DP + BASE_BOTTOM_PADDING_DP + ROW_GAP_DP * (ROW_COUNT_WITH_UPPER_ROW - 1)) * density
        val unitHeight = (baseHeightPx - reservedHeightPx) / ROW_WEIGHT_SUM_WITH_UPPER_ROW
        return (TOP_PADDING_DP * density + COMPACT_ROW_WEIGHT * unitHeight).roundToInt()
    }
}
