package dev.zain.znkeyboard.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.zain.znkeyboard.R
import dev.zain.znkeyboard.SettingsIconTextButtonContent
import dev.zain.znkeyboard.SettingsSectionCard
import dev.zain.znkeyboard.SettingsSectionHeader
import dev.zain.znkeyboard.ZnKeyboardColors
import dev.zain.znkeyboard.constants.VoiceDefaults

@Composable
internal fun VoiceTypingSection() {
    val context = LocalContext.current
    val bundled = remember { BarkModelAssets.isBundled(context) }
    val sizeMb = remember { (BarkModelAssets.bundledBytes(context) + 500_000) / 1_000_000 }
    var micGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micGranted = it }

    SettingsSectionCard {
        SettingsSectionHeader(
            title = "Voice typing",
            iconResId = R.drawable.ic_mic_24,
            trailingText = if (bundled) "$sizeMb MB" else "Not in this build",
        )
        Text(
            text = if (bundled) {
                "Add the Voice key to a shortcut row, talk, then tap Done. ${VoiceDefaults.MODEL_DISPLAY_NAME} " +
                    "turns speech into text on this phone, drops filler words like \"um\", and never sends audio anywhere. English only."
            } else {
                "This build was made without the speech model. Run tools/bark/setup.sh and rebuild."
            },
            color = ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
        if (!micGranted) {
            OutlinedButton(
                onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
            ) {
                SettingsIconTextButtonContent(R.drawable.ic_mic_24, "Allow microphone")
            }
        }
        Text(
            text = VoiceDefaults.MODEL_ATTRIBUTION,
            color = ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
