package dev.zain.znkeyboard.constants

object ImeDimensions {
    const val BASE_HEIGHT_DP = 282f
    const val EXTENDED_PANEL_EXTRA_HEIGHT_DP = 224f

    const val HORIZONTAL_PADDING_DP = 6
    const val TOP_PADDING_DP = 6
    const val BASE_BOTTOM_PADDING_DP = 8
    const val KEY_GAP_DP = 5
    const val ROW_GAP_DP = 10
    const val KEY_RADIUS_DP = 8

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

    const val KEY_LABEL_CHARACTER_TEXT_SIZE_SP = 24f
    const val KEY_LABEL_FUNCTION_TEXT_SIZE_SP = 14f
    const val KEY_LABEL_ACTION_TEXT_SIZE_SP = 14f
    const val KEY_LABEL_MIN_TEXT_SIZE_SP = 9f
    const val KEY_LABEL_SHRINK_STEP_SP = 0.5f
    const val KEY_LABEL_MAX_HEIGHT_FRACTION = 0.56f
    const val KEY_LABEL_HORIZONTAL_INSET_DP = 8f
    const val LONG_PRESS_HINT_TEXT_SIZE_SP = 8f
    const val LONG_PRESS_HINT_MAX_HEIGHT_FRACTION = 0.22f

    const val DEFAULT_KEY_WEIGHT = 1f
    const val EDGE_UPPER_ROW_KEY_WEIGHT = 1.4f
    const val SIDE_FUNCTION_KEY_WEIGHT = 1.35f
    const val MODE_SWITCH_KEY_WEIGHT = 1.25f
    const val SPACE_KEY_WEIGHT = 5.1f
    const val ENTER_KEY_WEIGHT = 1.55f
    const val EMOJI_KEY_WEIGHT = 0.9f

    const val BROWSE_HEADER_HEIGHT_DP = 42
    const val BACK_BUTTON_WIDTH_DP = 44
    const val SEARCH_BUTTON_WIDTH_DP = 124
    const val PANEL_SEARCH_TEXT_SIZE_SP = 15f
    const val CLIPBOARD_HISTORY_TITLE_START_GAP_DP = 10
}
