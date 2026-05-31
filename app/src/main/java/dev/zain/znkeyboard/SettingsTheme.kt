package dev.zain.znkeyboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.zain.znkeyboard.constants.SettingsThemeColors

@Composable
internal fun ZnKeyboardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ZnKeyboardColors.Accent,
            onPrimary = Color.White,
            background = ZnKeyboardColors.Background,
            onBackground = ZnKeyboardColors.OnSurface,
            surface = ZnKeyboardColors.Surface,
            onSurface = ZnKeyboardColors.OnSurface,
            secondary = ZnKeyboardColors.FunctionKey,
            onSecondary = ZnKeyboardColors.OnSurface,
        ),
        content = content,
    )
}

internal object ZnKeyboardColors {
    val Background = SettingsThemeColors.Background
    val Surface = SettingsThemeColors.Surface
    val Key = SettingsThemeColors.Key
    val FunctionKey = SettingsThemeColors.FunctionKey
    val Accent = SettingsThemeColors.Accent
    val OnSurface = SettingsThemeColors.OnSurface
    val Muted = SettingsThemeColors.Muted
    val DeleteBackground = Color(0xFF3A2424)
    val DeleteContent = Color(0xFFE0A8A8)
}
