package dev.zain.znkeyboard

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.zain.znkeyboard.constants.SettingsThemeColors

internal val ZainAppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

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
            onSurfaceVariant = ZnKeyboardColors.Muted,
            secondary = ZnKeyboardColors.FunctionKey,
            onSecondary = ZnKeyboardColors.OnSurface,
            // Selected nav pills, chips, etc. — keep content light on a dark container.
            secondaryContainer = ZnKeyboardColors.FunctionKey,
            onSecondaryContainer = ZnKeyboardColors.OnSurface,
        ),
        shapes = ZainAppShapes,
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
