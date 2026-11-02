package dev.zain.znkeyboard.ocrlab

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.zain.znkeyboard.R
import dev.zain.znkeyboard.SettingsIconTextButtonContent
import dev.zain.znkeyboard.SettingsSectionCard
import dev.zain.znkeyboard.SettingsSectionHeader
import dev.zain.znkeyboard.ZnKeyboardColors
import dev.zain.znkeyboard.ocrlab.ppocr.OcrModelStore

@Composable
internal fun OcrLabSection(onOpen: () -> Unit) {
    val context = LocalContext.current
    var modelReady by remember { mutableStateOf(OcrModelStore.isReady(context)) }
    val sizeMb = (OcrModelStore.totalBytes + 500_000) / 1_000_000
    SettingsSectionCard {
        SettingsSectionHeader(
            title = "Scan text",
            iconResId = R.drawable.ic_camera_24,
            trailingText = if (modelReady) "$sizeMb MB" else "Not downloaded",
        )
        Text(
            text = "Add the Scan text key to a shortcut row to type text from the camera. The $sizeMb MB " +
                "on-device model downloads the first time you use it. Nothing you scan leaves the phone.",
            color = ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
        ) {
            SettingsIconTextButtonContent(R.drawable.ic_camera_24, "Compare OCR engines")
        }
        if (modelReady) {
            TextButton(
                onClick = {
                    OcrModelStore.delete(context)
                    modelReady = false
                    Toast.makeText(context, "Scanner model deleted", Toast.LENGTH_SHORT).show()
                },
            ) { Text("Delete model") }
        }
    }
}
