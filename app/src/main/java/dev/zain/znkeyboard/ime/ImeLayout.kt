package dev.zain.znkeyboard.ime

import android.content.Context
import dev.zain.znkeyboard.constants.ImeDimensions
import kotlin.math.roundToInt

internal object ImeLayout {
    const val BASE_HEIGHT_DP = ImeDimensions.BASE_HEIGHT_DP
    const val EXTENDED_PANEL_EXTRA_HEIGHT_DP = ImeDimensions.EXTENDED_PANEL_EXTRA_HEIGHT_DP
    const val HORIZONTAL_PADDING_DP = ImeDimensions.HORIZONTAL_PADDING_DP
    const val TOP_PADDING_DP = ImeDimensions.TOP_PADDING_DP
    const val BASE_BOTTOM_PADDING_DP = ImeDimensions.BASE_BOTTOM_PADDING_DP
    const val KEY_GAP_DP = ImeDimensions.KEY_GAP_DP
    const val ROW_GAP_DP = ImeDimensions.ROW_GAP_DP
    const val KEY_RADIUS_DP = ImeDimensions.KEY_RADIUS_DP
    const val MIN_BOTTOM_SYSTEM_CONTROL_GAP_DP = ImeDimensions.MIN_BOTTOM_SYSTEM_CONTROL_GAP_DP
    const val BOTTOM_SYSTEM_CONTROL_GAP_MULTIPLIER = ImeDimensions.BOTTOM_SYSTEM_CONTROL_GAP_MULTIPLIER
    const val ICON_VIEWPORT = ImeDimensions.ICON_VIEWPORT

    const val COMPACT_ROW_WEIGHT = ImeDimensions.COMPACT_ROW_WEIGHT
    const val STANDARD_ROW_WEIGHT = ImeDimensions.STANDARD_ROW_WEIGHT
    const val BOTTOM_ROW_WEIGHT = ImeDimensions.BOTTOM_ROW_WEIGHT
    const val ROW_COUNT_WITH_UPPER_ROW = ImeDimensions.ROW_COUNT_WITH_UPPER_ROW
    const val ROW_WEIGHT_SUM_WITH_UPPER_ROW = ImeDimensions.ROW_WEIGHT_SUM_WITH_UPPER_ROW

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
