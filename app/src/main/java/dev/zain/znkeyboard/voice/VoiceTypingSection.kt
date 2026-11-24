package dev.zain.znkeyboard.voice

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.zain.znkeyboard.KeyboardSettings
import dev.zain.znkeyboard.R
import dev.zain.znkeyboard.SettingsIconTextButtonContent
import dev.zain.znkeyboard.SettingsSectionCard
import dev.zain.znkeyboard.SettingsSectionHeader
import dev.zain.znkeyboard.ZnKeyboardColors
import dev.zain.znkeyboard.constants.VoiceDefaults

@Composable
internal fun VoiceTypingSection() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(BarkModelSource.status(context)) }
    var folder by remember { mutableStateOf(BarkModelSource.folderLabel(context)) }
    var micGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micGranted = it }
    // The system's own folder picker; the app never browses storage itself.
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        runCatching { BarkModelSource.choose(context, tree) }
            .onFailure { Toast.makeText(context, "Couldn't keep access to that folder.", Toast.LENGTH_LONG).show() }
        status = BarkModelSource.status(context)
        folder = BarkModelSource.folderLabel(context)
        (status as? BarkModelSource.Status.Missing)?.let {
            Toast.makeText(context, "That folder is missing ${it.names.joinToString()}.", Toast.LENGTH_LONG).show()
        }
    }

    val ready = status as? BarkModelSource.Status.Ready
    SettingsSectionCard {
        SettingsSectionHeader(
            title = "Voice typing",
            iconResId = R.drawable.ic_mic_24,
            trailingText = ready?.let { "${(it.files.totalBytes + 500_000) / 1_000_000} MB" } ?: "Not set up",
        )
        Text(
            text = "Add the Voice key to a shortcut row, talk, then tap Done. ${VoiceDefaults.MODEL_DISPLAY_NAME} " +
                "turns speech into text on this phone, drops filler words like \"um\", and never sends audio anywhere. English only.",
            color = ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = when (val s = status) {
                BarkModelSource.Status.NotChosen ->
                    "The speech model (~700 MB) isn't part of the app. Download it, then pick its folder. " +
                        "It needs ${BarkModelSource.REQUIRED.joinToString()}."
                BarkModelSource.Status.NoAccess -> "The app lost access to ${folder ?: "the model folder"}. Pick it again."
                is BarkModelSource.Status.Missing -> "${folder ?: "The folder"} is missing ${s.names.joinToString()}."
                is BarkModelSource.Status.Ready -> "Model folder: ${folder ?: "chosen"}. Loaded straight from there; no copy is made."
            },
            color = if (ready != null) ZnKeyboardColors.Muted else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { folderPicker.launch(BarkModelSource.folderUri(context)) },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
            ) {
                SettingsIconTextButtonContent(R.drawable.ic_download_24, if (status == BarkModelSource.Status.NotChosen) "Choose model folder" else "Change folder")
            }
            if (status != BarkModelSource.Status.NotChosen) {
                TextButton(onClick = {
                    BarkModelSource.forget(context)
                    status = BarkModelSource.status(context)
                    folder = null
                }) { Text("Forget") }
            }
        }
        if (ready != null) KeepModelLoadedSelector()
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

@Composable
private fun KeepModelLoadedSelector() {
    val context = LocalContext.current
    var seconds by remember { mutableIntStateOf(KeyboardSettings.readVoiceKeepLoadedSeconds(context)) }
    val options = VoiceDefaults.KEEP_LOADED_OPTIONS
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Keep model loaded", style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = seconds == value,
                    onClick = {
                        seconds = value
                        KeyboardSettings.saveVoiceKeepLoadedSeconds(context, value)
                        BarkEngine.onKeepLoadedChanged(context)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(label, maxLines = 1) }
            }
        }
        Text(
            text = when (seconds) {
                0 -> "Frees its memory as soon as you finish. Every dictation loads it again (a few seconds)."
                VoiceDefaults.KEEP_LOADED_ALWAYS ->
                    "Stays in memory (~0.8 GB) until the keyboard closes, so the Voice key is always green. " +
                        "Android may still close the keyboard to free memory."
                else -> "Stays in memory (~0.8 GB) for ${options.first { it.first == seconds }.second} after you finish, " +
                    "so the next dictation starts instantly. The Voice key is green while it's loaded."
            },
            color = ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
