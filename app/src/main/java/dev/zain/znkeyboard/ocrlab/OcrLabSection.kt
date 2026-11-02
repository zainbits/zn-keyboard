package dev.zain.znkeyboard.ocrlab

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.zain.znkeyboard.R
import dev.zain.znkeyboard.SettingsIconTextButtonContent
import dev.zain.znkeyboard.SettingsSectionCard
import dev.zain.znkeyboard.SettingsSectionHeader
import dev.zain.znkeyboard.ZnKeyboardColors

@Composable
internal fun OcrLabSection(onOpen: () -> Unit) {
    SettingsSectionCard {
        SettingsSectionHeader(
            title = "Camera OCR test",
            iconResId = R.drawable.ic_camera_24,
            trailingText = "Lab",
        )
        Text(
            text = "Compare ML Kit and PP-OCRv6 on the same photo, on this phone. Nothing is sent online.",
            color = ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
        ) {
            SettingsIconTextButtonContent(R.drawable.ic_camera_24, "Open camera test")
        }
    }
}
