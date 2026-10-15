package dev.zain.znkeyboard.constants

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter
import java.util.Locale

object SettingsUiTimings {
    const val MODEL_LOAD_DEBOUNCE_MS = 500L
    const val MODEL_QUERY_DEBOUNCE_MS = 1_000L
    const val MODEL_LOAD_TIMEOUT_MS = 10_000
}

object SettingsUiDimensions {
    val PICKER_MENU_WIDTH = 320.dp
    val PICKER_MAX_HEIGHT = 280.dp
    val PROVIDER_PICKER_MAX_HEIGHT = 140.dp
    val REASONING_PICKER_MAX_HEIGHT = 260.dp
    val SKIN_TONE_PICKER_MAX_HEIGHT = 260.dp
    val SECTION_CORNER_RADIUS = 12.dp
    val COMPACT_ITEM_CORNER_RADIUS = 8.dp
    val PICKER_CORNER_RADIUS = 16.dp
}

/** Floating title pill and content clearance for the edge-to-edge settings shell. */
object SettingsShellDimensions {
    val TITLE_PILL_HEIGHT = 48.dp
    val TITLE_PILL_HORIZONTAL_PADDING = 20.dp
    val TITLE_PILL_START_MARGIN = 12.dp
    val TITLE_PILL_TOP_GAP = 8.dp
    val TITLE_PILL_BORDER_WIDTH = 1.dp
    val CONTENT_HORIZONTAL_PADDING = 16.dp

    /** Extra space so the first and last items can scroll fully clear of the pills. */
    val CONTENT_CLEARANCE = 16.dp
}

object LiquidNavDimensions {
    val BAR_HEIGHT = 64.dp
    val SELECTED_PILL_WIDTH = 64.dp
    val GROUP_GAP = 10.dp
    val SIDE_MARGIN = 12.dp
    val BOTTOM_GAP = 8.dp
    val BORDER_WIDTH = 1.dp

    /** Outer bottom corner radius when the display reports no rounded corners (emulators, square screens). */
    val FALLBACK_OUTER_CORNER_RADIUS = 32.dp
    val ICON_SIZE = 24.dp
    val ICON_LABEL_GAP = 2.dp
    val RIPPLE_RADIUS = 28.dp
    const val LABEL_SELECTED_SCALE = 0.85f
}

object SettingsBackupDefaults {
    val FILE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
    const val MIME_TYPE = "application/octet-stream"
    val IMPORT_MIME_TYPES = arrayOf(MIME_TYPE, "application/json", "text/json", "text/plain")
}

object SettingsThemeColors {
    val Background = Color.Black
    val Surface = Color(0xFF222222)
    val Key = Color(0xFF2A2A2A)
    val FunctionKey = Color(0xFF323232)
    val Accent = Color(0xFF30ACE2)
    val OnSurface = Color(0xFFFFFFFF)
    val Muted = Color(0xFFB3B3B3)
}
