package dev.zain.znkeyboard

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.zain.znkeyboard.constants.ImeColors
import dev.zain.znkeyboard.typing.AdaptiveTypingEngine
import dev.zain.znkeyboard.typing.TouchModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

private data class ImeSetupStatus(val enabled: Boolean, val selected: Boolean)

private fun readImeSetupStatus(context: Context): ImeSetupStatus {
    val imm = context.getSystemService(InputMethodManager::class.java)
    val enabled = imm?.enabledInputMethodList?.any { it.packageName == context.packageName } == true
    val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    val selected = current?.substringBefore('/') == context.packageName
    return ImeSetupStatus(enabled, selected)
}

/**
 * Status-aware setup: shows only the next step that is actually needed, and refreshes whenever the
 * window regains focus (returning from system settings or closing the keyboard picker).
 */
@Composable
internal fun SetupStatusCard() {
    val context = LocalContext.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var status by remember { mutableStateOf(readImeSetupStatus(context)) }
    LaunchedEffect(windowFocused) {
        if (windowFocused) status = readImeSetupStatus(context)
    }
    val ready = status.enabled && status.selected
    Surface(
        color = if (ready) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (ready) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_keyboard_24),
                    contentDescription = null,
                    tint = if (ready) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(8.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = when {
                        !status.enabled -> "Turn on ZnKeyboard"
                        !status.selected -> "Switch to ZnKeyboard"
                        else -> "ZnKeyboard is active"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when {
                        !status.enabled -> "Step 1 of 2 · enable it in system keyboard settings."
                        !status.selected -> "Step 2 of 2 · pick it as your current keyboard."
                        else -> "Try it below. It adapts to your typing as you go."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            when {
                !status.enabled -> Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }) { Text("Enable") }
                !status.selected -> Button(onClick = {
                    context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
                }) { Text("Choose") }
                else -> TextButton(onClick = {
                    context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
                }) { Text("Switch") }
            }
        }
    }
}

@Composable
internal fun TryTypingField() {
    var text by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Try it") },
        placeholder = { Text("Type here to feel the difference") },
        minLines = 2,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
internal fun TypingFeelSection() {
    val context = LocalContext.current
    var haptics by remember { mutableStateOf(KeyboardSettings.readKeyHapticsEnabled(context)) }
    var preview by remember { mutableStateOf(KeyboardSettings.readKeyPreviewEnabled(context)) }
    var autoCaps by remember { mutableStateOf(KeyboardSettings.readAutoCapitalizeEnabled(context)) }
    var doubleSpace by remember { mutableStateOf(KeyboardSettings.readDoubleSpacePeriodEnabled(context)) }
    var suggestionDelayMs by remember { mutableIntStateOf(KeyboardSettings.readSuggestionDelayMs(context)) }
    SettingsSectionCard(verticalSpacing = 18.dp) {
        SettingsSectionHeader(title = "Feel", iconResId = R.drawable.ic_touch_24)
        SettingsSwitchRow(
            title = "Key haptics",
            description = "A crisp tick the moment a key registers. Follows the system keyboard-vibration setting.",
            checked = haptics,
            onCheckedChange = { haptics = it; KeyboardSettings.saveKeyHapticsEnabled(context, it) },
        )
        SettingsSwitchRow(
            title = "Key popup",
            description = "Shows the letter above your finger, since your finger hides the key it presses.",
            checked = preview,
            onCheckedChange = { preview = it; KeyboardSettings.saveKeyPreviewEnabled(context, it) },
        )
        SettingsSwitchRow(
            title = "Auto-capitalize",
            description = "Shift turns on at the start of sentences when the app asks for it.",
            checked = autoCaps,
            onCheckedChange = { autoCaps = it; KeyboardSettings.saveAutoCapitalizeEnabled(context, it) },
        )
        SettingsSwitchRow(
            title = "Double-space period",
            description = "Tap space twice quickly to end a sentence with \". \"",
            checked = doubleSpace,
            onCheckedChange = { doubleSpace = it; KeyboardSettings.saveDoubleSpacePeriodEnabled(context, it) },
        )
        LabeledSlider(
            title = "Suggestion delay",
            valueLabel = "$suggestionDelayMs ms",
            value = suggestionDelayMs.toFloat(),
            valueRange = KeyboardSettings.MIN_SUGGESTION_DELAY_MS.toFloat()..KeyboardSettings.MAX_SUGGESTION_DELAY_MS.toFloat(),
            steps = 0,
            onValueChange = {
                val value = KeyboardSettings.normalizeSuggestionDelayMs(it.roundToInt())
                suggestionDelayMs = value
                KeyboardSettings.saveSuggestionDelayMs(context, value)
            },
            defaultLabel = "${KeyboardSettings.DEFAULT_SUGGESTION_DELAY_MS} ms",
            isDefault = suggestionDelayMs == KeyboardSettings.DEFAULT_SUGGESTION_DELAY_MS,
            onReset = {
                suggestionDelayMs = KeyboardSettings.DEFAULT_SUGGESTION_DELAY_MS
                KeyboardSettings.saveSuggestionDelayMs(context, KeyboardSettings.DEFAULT_SUGGESTION_DELAY_MS)
            },
        )
    }
}

private class TouchSummary(
    val model: TouchModel?,
    val samples: Int,
    val meanXDp: Float,
    val meanYDp: Float,
)

private fun summarize(model: TouchModel?): TouchSummary {
    if (model == null) return TouchSummary(null, 0, 0f, 0f)
    val out = FloatArray(4)
    var weight = 0f
    var sumX = 0f
    var sumY = 0f
    for (letter in 0 until TouchModel.LETTER_COUNT) {
        val n = model.samplesFor(letter)
        if (n <= 0f) continue
        model.params(letter, HEATMAP_PITCH_X_DP, HEATMAP_PITCH_Y_DP, out)
        weight += n
        sumX += out[0] * n
        sumY += out[1] * n
    }
    return TouchSummary(
        model = model,
        samples = model.sampleCount.roundToInt(),
        meanXDp = if (weight > 0f) sumX / weight else 0f,
        meanYDp = if (weight > 0f) sumY / weight else 0f,
    )
}

@Composable
internal fun AdaptiveTouchSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var adaptive by remember { mutableStateOf(KeyboardSettings.readAdaptiveTouchEnabled(context)) }
    var summary by remember { mutableStateOf(TouchSummary(null, 0, 0f, 0f)) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var confirmingReset by remember { mutableStateOf(false) }

    LaunchedEffect(windowFocused, refreshTick) {
        summary = withContext(Dispatchers.IO) { summarize(AdaptiveTypingEngine.readPortraitModel(context)) }
    }

    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text("Reset learned typing?") },
            text = { Text("ZnKeyboard forgets how your taps land and the letter patterns it learned. It starts adapting again from your next keystroke.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingReset = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            KeyboardSettings.bumpTypingModelGeneration(context)
                            AdaptiveTypingEngine.deleteLearnedData(context)
                        }
                        refreshTick++
                    }
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmingReset = false }) { Text("Cancel") } },
        )
    }

    SettingsSectionCard(verticalSpacing = 16.dp) {
        SettingsSectionHeader(
            title = "Adaptive touch",
            iconResId = R.drawable.ic_tune_24,
            trailingText = if (summary.samples > 0) "${formatCount(summary.samples)} taps" else null,
        )
        SettingsSwitchRow(
            title = "Learn how I type",
            description = "Reads each tap where you aimed, learns where your thumbs actually land on every key, " +
                "and uses letter patterns only to settle taps that fall between keys. Learning stays on this " +
                "device (and in your encrypted backups) and is skipped in password and incognito fields.",
            checked = adaptive,
            onCheckedChange = { adaptive = it; KeyboardSettings.saveAdaptiveTouchEnabled(context, it) },
        )
        TouchHeatmap(summary.model, modifier = Modifier.fillMaxWidth())
        Text(
            text = touchInsight(summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { confirmingReset = true },
            enabled = summary.samples > 0,
        ) { Text("Reset learned typing") }
    }
}

private fun touchInsight(summary: TouchSummary): String {
    if (summary.samples < MIN_SAMPLES_FOR_INSIGHT) {
        return "Keep typing. After about $MIN_SAMPLES_FOR_INSIGHT taps this map shows where your touches really land; " +
            "each dot is a key's learned centre and the ring is your usual spread."
    }
    val mmY = summary.meanYDp * MM_PER_DP
    val mmX = summary.meanXDp * MM_PER_DP
    val vertical = when {
        abs(mmY) < 0.2f -> null
        mmY > 0 -> "%.1f mm below".format(mmY)
        else -> "%.1f mm above".format(-mmY)
    }
    val horizontal = when {
        abs(mmX) < 0.2f -> null
        mmX > 0 -> "%.1f mm right of".format(mmX)
        else -> "%.1f mm left of".format(-mmX)
    }
    val where = listOfNotNull(vertical, horizontal).joinToString(" and ")
    return if (where.isEmpty()) {
        "Your taps land close to key centres on average. ZnKeyboard still corrects per key."
    } else {
        "On average your taps land $where key centres. ZnKeyboard now compensates for that, key by key."
    }
}

private fun formatCount(count: Int): String = when {
    count >= 10_000 -> "${count / 1000}k"
    count >= 1_000 -> "%.1fk".format(count / 1000f)
    else -> count.toString()
}

/** Mini QWERTY showing each key's learned touch centre (dot) and 1σ spread (ring). */
@Composable
private fun TouchHeatmap(model: TouchModel?, modifier: Modifier = Modifier) {
    val keyColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val centerColor = MaterialTheme.colorScheme.outline
    val dotColor = MaterialTheme.colorScheme.primary
    val ringColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val params = remember { FloatArray(4) }
    Canvas(
        modifier = modifier
            .aspectRatio(2.6f)
            .semantics { contentDescription = "Map of where your taps land on each letter key" },
    ) {
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val offsets = listOf(0f, 0.5f, 1.5f)
        val gap = 4.dp.toPx()
        val pitchX = size.width / 10f
        val pitchY = size.height / 3f
        val keyW = pitchX - gap
        val keyH = pitchY - gap
        val scaleX = pitchX / HEATMAP_PITCH_X_DP
        val scaleY = pitchY / HEATMAP_PITCH_Y_DP
        rows.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { column, char ->
                val left = (offsets[rowIndex] + column) * pitchX + gap / 2f
                val top = rowIndex * pitchY + gap / 2f
                drawRoundRect(
                    color = keyColor,
                    topLeft = Offset(left, top),
                    size = Size(keyW, keyH),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                )
                val cx = left + keyW / 2f
                val cy = top + keyH / 2f
                drawCircle(centerColor, radius = 1.5.dp.toPx(), center = Offset(cx, cy))
                val letter = char - 'a'
                if (model != null && model.samplesFor(letter) >= 1f) {
                    model.params(letter, HEATMAP_PITCH_X_DP, HEATMAP_PITCH_Y_DP, params)
                    val center = Offset(cx + params[0] * scaleX, cy + params[1] * scaleY)
                    val rx = sqrt(params[2]) * scaleX
                    val ry = sqrt(params[3]) * scaleY
                    drawOval(
                        color = ringColor,
                        topLeft = Offset(center.x - rx, center.y - ry),
                        size = Size(rx * 2f, ry * 2f),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                    drawCircle(dotColor, radius = 3.dp.toPx(), center = center)
                }
            }
        }
    }
}

@Composable
internal fun KeyboardThemeSelector() {
    val context = LocalContext.current
    var style by remember { mutableStateOf(KeyboardSettings.readKeyboardTheme(context)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Keyboard theme", style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ImeColors.Style.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = style == option,
                    onClick = {
                        style = option
                        KeyboardSettings.saveKeyboardTheme(context, option)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, ImeColors.Style.entries.size),
                ) { Text(option.label) }
            }
        }
        Text(
            text = when (style) {
                ImeColors.Style.MaterialYou -> "Follows your wallpaper colors and light/dark mode."
                ImeColors.Style.ClassicBlack -> "True black for OLED, with your accent color on the action key."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun LabeledSlider(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    defaultLabel: String? = null,
    isDefault: Boolean = true,
    onReset: (() -> Unit)? = null,
) {
    var confirmingReset by remember { mutableStateOf(false) }
    if (confirmingReset && onReset != null) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text("Reset ${title.lowercase()}?") },
            text = { Text("Set it back to the default of $defaultLabel.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingReset = false
                    onReset()
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmingReset = false }) { Text("Cancel") } },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (onReset != null) {
                IconButton(
                    onClick = { confirmingReset = true },
                    enabled = !isDefault,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_restart_24),
                        contentDescription = "Reset $title to default",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDefault) 0.25f else 0.6f),
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps)
    }
}

private const val HEATMAP_PITCH_X_DP = 40f
private const val HEATMAP_PITCH_Y_DP = 54f
private const val MIN_SAMPLES_FOR_INSIGHT = 150
private const val MM_PER_DP = 0.15875f

