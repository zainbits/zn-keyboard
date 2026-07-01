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
    val SHORTCUT_KEY_ITEM_HEIGHT = 48.dp
    val SHORTCUT_KEY_ITEM_GAP = 8.dp
    val ROW_ORDER_ITEM_HEIGHT = 48.dp
    val ROW_ORDER_ITEM_GAP = 8.dp
    val SECTION_CORNER_RADIUS = 12.dp
    val COMPACT_ITEM_CORNER_RADIUS = 8.dp
    val PICKER_CORNER_RADIUS = 16.dp
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
