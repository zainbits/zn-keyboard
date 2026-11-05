@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.zain.znkeyboard

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zain.znkeyboard.KeyboardSettings.KeyboardRow
import dev.zain.znkeyboard.constants.SettingsUiDimensions

private val UPPER = KeyboardRow.Upper.id
private val SECOND = KeyboardRow.Second.id

private data class KeySelection(val rowId: String, val index: Int)

private data class MacroDraft(
    val macro: ShortcutMacro,
    val isNew: Boolean,
    val addToRowId: String? = null,
)

/** Keyboard-tab entry point: a live, read-only miniature of the shortcut rows. */
@Composable
internal fun ShortcutsSummaryCard(onOpen: () -> Unit) {
    val context = LocalContext.current
    val macros = remember { KeyboardSettings.readShortcutMacros(context) }
    val rows = remember {
        KeyboardSettings.readKeyboardRowOrder(context).map { rowId ->
            rowId to if (rowId == UPPER) {
                KeyboardSettings.readUpperRowKeyIds(context)
            } else {
                KeyboardSettings.readSecondRowButtonIds(context)
            }
        }
    }
    val keyCount = rows.sumOf { it.second.size }

    SettingsSectionCard(
        modifier = Modifier
            .clip(RoundedCornerShape(SettingsUiDimensions.SECTION_CORNER_RADIUS))
            .clickable(onClick = onOpen),
    ) {
        SettingsSectionHeader(
            title = "Shortcut rows & macros",
            iconResId = R.drawable.ic_keyboard_24,
            subtitle = "$keyCount keys · ${macros.size} ${if (macros.size == 1) "macro" else "macros"}",
            trailingContent = {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_left_24),
                    contentDescription = null,
                    tint = ZnKeyboardColors.Muted,
                    modifier = Modifier.rotate(180f),
                )
            },
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEach { (_, keyIds) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (keyIds.isEmpty()) {
                        MiniKey(label = "Empty row", muted = true, modifier = Modifier.weight(1f))
                    }
                    keyIds.forEach { keyId ->
                        MiniKey(label = shortcutKeyLabel(keyId, macros), modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniKey(label: String, modifier: Modifier = Modifier, muted: Boolean = false) {
    Surface(
        color = ZnKeyboardColors.Key,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.height(30.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = if (muted) ZnKeyboardColors.Muted else ZnKeyboardColors.OnSurface,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
internal fun ShortcutsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var upperKeys by remember { mutableStateOf(KeyboardSettings.readUpperRowKeyIds(context)) }
    var secondKeys by remember { mutableStateOf(KeyboardSettings.readSecondRowButtonIds(context)) }
    var rowOrder by remember { mutableStateOf(KeyboardSettings.readKeyboardRowOrder(context)) }
    var macros by remember { mutableStateOf(KeyboardSettings.readShortcutMacros(context)) }
    var selection by remember { mutableStateOf<KeySelection?>(null) }
    var paletteRowId by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<MacroDraft?>(null) }
    var confirmingResetRowId by remember { mutableStateOf<String?>(null) }

    fun keysFor(rowId: String) = if (rowId == UPPER) upperKeys else secondKeys
    fun maxKeysFor(rowId: String) =
        if (rowId == UPPER) KeyboardSettings.MAX_UPPER_ROW_KEYS else KeyboardSettings.MAX_SECOND_ROW_BUTTONS
    fun defaultKeysFor(rowId: String) =
        if (rowId == UPPER) KeyboardSettings.DEFAULT_UPPER_ROW_KEY_IDS else KeyboardSettings.DEFAULT_SECOND_ROW_BUTTON_IDS

    fun setKeys(rowId: String, keyIds: List<String>) {
        if (rowId == UPPER) {
            upperKeys = KeyboardSettings.normalizeUpperRowKeyIds(keyIds)
            KeyboardSettings.saveUpperRowKeyIds(context, upperKeys)
        } else {
            secondKeys = KeyboardSettings.normalizeSecondRowButtonIds(keyIds)
            KeyboardSettings.saveSecondRowButtonIds(context, secondKeys)
        }
    }

    fun toggleKey(rowId: String, keyId: String) {
        val keys = keysFor(rowId)
        if (keyId in keys) {
            setKeys(rowId, keys - keyId)
        } else if (keys.size < maxKeysFor(rowId)) {
            setKeys(rowId, keys + keyId)
        }
        selection = null
    }

    fun saveMacros(next: List<ShortcutMacro>) {
        macros = ShortcutMacros.normalize(next)
        KeyboardSettings.saveShortcutMacros(context, macros)
    }

    val currentDraft = draft
    if (currentDraft != null) {
        MacroEditorScreen(
            draft = currentDraft,
            onBack = { draft = null },
            onSave = { macro ->
                val exists = macros.any { it.id == macro.id }
                saveMacros(if (exists) macros.map { if (it.id == macro.id) macro else it } else macros + macro)
                currentDraft.addToRowId?.let { rowId ->
                    if (macro.keyId !in keysFor(rowId)) toggleKey(rowId, macro.keyId)
                }
                draft = null
            },
            onDelete = {
                val keyId = currentDraft.macro.keyId
                saveMacros(macros.filterNot { it.id == currentDraft.macro.id })
                setKeys(UPPER, upperKeys.filterNot { it == keyId })
                setKeys(SECOND, secondKeys.filterNot { it == keyId })
                selection = null
                draft = null
            },
        )
        return
    }

    BackHandler(onBack = onBack)

    fun newMacroDraft(addToRowId: String? = null) = MacroDraft(
        macro = ShortcutMacro(ShortcutMacros.newId(), "", emptyList()),
        isNew = true,
        addToRowId = addToRowId,
    )

    Scaffold(
        containerColor = ZnKeyboardColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("Shortcuts") },
                navigationIcon = { BackIconButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ZnKeyboardColors.Background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSectionCard(verticalSpacing = 12.dp) {
                SettingsSectionHeader(
                    title = "Shortcut rows",
                    iconResId = R.drawable.ic_keyboard_24,
                    subtitle = "Shown top to bottom, as on the keyboard. Tap a key to move or remove it.",
                )

                rowOrder.forEachIndexed { position, rowId ->
                    if (position > 0) {
                        TextButton(
                            onClick = {
                                rowOrder = KeyboardSettings.normalizeKeyboardRowOrder(rowOrder.reversed())
                                KeyboardSettings.saveKeyboardRowOrder(context, rowOrder)
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) {
                            Text("⇅  Swap rows")
                        }
                    }
                    val otherRowId = if (rowId == UPPER) SECOND else UPPER
                    ShortcutRowEditor(
                        title = KeyboardSettings.labelForKeyboardRow(rowId),
                        keyIds = keysFor(rowId),
                        maxKeys = maxKeysFor(rowId),
                        canReset = keysFor(rowId) != defaultKeysFor(rowId),
                        macros = macros,
                        selectedIndex = selection?.takeIf { it.rowId == rowId }?.index,
                        otherRowLabel = KeyboardSettings.labelForKeyboardRow(otherRowId),
                        canMoveToOtherRow = keysFor(otherRowId).size < maxKeysFor(otherRowId),
                        onSelect = { index ->
                            selection = if (selection == KeySelection(rowId, index)) null else KeySelection(rowId, index)
                        },
                        onAdd = {
                            selection = null
                            paletteRowId = rowId
                        },
                        onReset = {
                            selection = null
                            confirmingResetRowId = rowId
                        },
                        onMove = { from, to ->
                            val keys = keysFor(rowId)
                            if (to in keys.indices) {
                                setKeys(rowId, keys.toMutableList().apply { add(to, removeAt(from)) })
                                selection = KeySelection(rowId, to)
                            }
                        },
                        onMoveToOtherRow = { index ->
                            val keys = keysFor(rowId)
                            val keyId = keys[index]
                            setKeys(rowId, keys.toMutableList().apply { removeAt(index) })
                            setKeys(otherRowId, keysFor(otherRowId) + keyId)
                            selection = null
                        },
                        onRemove = { index ->
                            setKeys(rowId, keysFor(rowId).toMutableList().apply { removeAt(index) })
                            selection = null
                        },
                    )
                }
            }

            SettingsSectionCard(verticalSpacing = 12.dp) {
                SettingsSectionHeader(
                    title = "Macros",
                    iconResId = R.drawable.ic_tune_24,
                    subtitle = "Chain key combos and text into a single key.",
                    trailingText = "${macros.size}/${ShortcutMacros.MAX_MACROS}",
                )

                if (macros.isEmpty()) {
                    Text(
                        text = "No macros yet. Try Ctrl + B then n for tmux, or Esc then “:wq” then Enter for Vim.",
                        color = ZnKeyboardColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                macros.forEach { macro ->
                    val rowsUsing = listOf(UPPER, SECOND)
                        .filter { macro.keyId in keysFor(it) }
                        .map(KeyboardSettings::labelForKeyboardRow)
                    MacroCard(
                        macro = macro,
                        usage = rowsUsing.joinToString().ifEmpty { "Not on a row" },
                        onClick = { draft = MacroDraft(macro, isNew = false) },
                    )
                }

                Button(
                    onClick = { draft = newMacroDraft() },
                    enabled = macros.size < ShortcutMacros.MAX_MACROS,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SettingsIconTextButtonContent(R.drawable.ic_add_24, "New macro")
                }
            }
        }
    }

    paletteRowId?.let { rowId ->
        KeyPaletteSheet(
            rowLabel = KeyboardSettings.labelForKeyboardRow(rowId),
            keyIds = keysFor(rowId),
            maxKeys = maxKeysFor(rowId),
            macros = macros,
            canCreateMacro = macros.size < ShortcutMacros.MAX_MACROS,
            onToggle = { keyId -> toggleKey(rowId, keyId) },
            onNewMacro = {
                paletteRowId = null
                draft = newMacroDraft(addToRowId = rowId)
            },
            onDismiss = { paletteRowId = null },
        )
    }

    confirmingResetRowId?.let { rowId ->
        val rowLabel = KeyboardSettings.labelForKeyboardRow(rowId)
        AlertDialog(
            onDismissRequest = { confirmingResetRowId = null },
            title = { Text("Reset $rowLabel?") },
            text = { Text("Your keys and their order in this row will be replaced with the defaults. Macros themselves are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    setKeys(rowId, defaultKeysFor(rowId))
                    confirmingResetRowId = null
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmingResetRowId = null }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun BackIconButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            painter = painterResource(R.drawable.ic_chevron_left_24),
            contentDescription = "Back",
        )
    }
}

@Composable
private fun ShortcutRowEditor(
    title: String,
    keyIds: List<String>,
    maxKeys: Int,
    canReset: Boolean,
    macros: List<ShortcutMacro>,
    selectedIndex: Int?,
    otherRowLabel: String,
    canMoveToOtherRow: Boolean,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onReset: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onMoveToOtherRow: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${keyIds.size}/$maxKeys",
                color = ZnKeyboardColors.Muted,
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(onClick = onReset, enabled = canReset) {
                Text("Reset")
            }
        }

        Surface(
            color = ZnKeyboardColors.Background,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            FlowRow(
                modifier = Modifier.padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                keyIds.forEachIndexed { index, keyId ->
                    KeyChip(
                        label = shortcutKeyLabel(keyId, macros),
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                    )
                }
                if (keyIds.size < maxKeys) {
                    AddKeyChip(onClick = onAdd)
                }
            }
        }

        AnimatedVisibility(visible = selectedIndex != null && selectedIndex in keyIds.indices) {
            val index = selectedIndex ?: 0
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) {
                    Icon(painterResource(R.drawable.ic_chevron_left_24), contentDescription = "Move left")
                }
                IconButton(onClick = { onMove(index, index + 1) }, enabled = index < keyIds.lastIndex) {
                    Icon(
                        painterResource(R.drawable.ic_chevron_left_24),
                        contentDescription = "Move right",
                        modifier = Modifier.rotate(180f),
                    )
                }
                OutlinedButton(
                    onClick = { onMoveToOtherRow(index) },
                    enabled = canMoveToOtherRow,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
                ) {
                    Text("To $otherRowLabel", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onRemove(index) }) {
                    Icon(
                        painterResource(R.drawable.ic_delete_24),
                        contentDescription = "Remove key",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyChip(
    label: String,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    val color = if (selected) MaterialTheme.colorScheme.primaryContainer else ZnKeyboardColors.Key
    val content: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .heightIn(min = 40.dp)
                .widthIn(min = 44.dp)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else ZnKeyboardColors.OnSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
    val border = if (selected) BorderStroke(2.dp, ZnKeyboardColors.Accent) else null
    if (onClick != null) {
        Surface(onClick = onClick, color = color, shape = shape, border = border, modifier = modifier, content = content)
    } else {
        Surface(color = color, shape = shape, border = border, modifier = modifier, content = content)
    }
}

@Composable
private fun AddKeyChip(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = ZnKeyboardColors.Background,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, ZnKeyboardColors.Accent),
    ) {
        Box(
            modifier = Modifier
                .height(40.dp)
                .widthIn(min = 44.dp)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add_24),
                contentDescription = "Add key",
                tint = ZnKeyboardColors.Accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun MacroCard(macro: ShortcutMacro, usage: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = ZnKeyboardColors.Key,
        shape = RoundedCornerShape(SettingsUiDimensions.COMPACT_ITEM_CORNER_RADIUS),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = macro.displayLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(text = usage, color = ZnKeyboardColors.Muted, style = MaterialTheme.typography.labelMedium)
            }
            StepChips(macro.steps)
        }
    }
}

@Composable
private fun StepChips(steps: List<MacroStep>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { index, step ->
            if (index > 0) {
                Text("›", color = ZnKeyboardColors.Muted, style = MaterialTheme.typography.labelLarge)
            }
            Surface(color = ZnKeyboardColors.FunctionKey, shape = RoundedCornerShape(6.dp)) {
                Text(
                    text = ShortcutMacros.stepLabel(step),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun KeyPaletteSheet(
    rowLabel: String,
    keyIds: List<String>,
    maxKeys: Int,
    macros: List<ShortcutMacro>,
    canCreateMacro: Boolean,
    onToggle: (String) -> Unit,
    onNewMacro: () -> Unit,
    onDismiss: () -> Unit,
) {
    val full = keyIds.size >= maxKeys
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Add to $rowLabel", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = if (full) "Row is full. Tap a selected key to remove it." else "Tap to add, tap again to remove.",
                        color = ZnKeyboardColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("${keyIds.size}/$maxKeys", color = ZnKeyboardColors.Muted, style = MaterialTheme.typography.labelLarge)
            }

            PaletteSection("Actions", KeyboardSettings.ACTION_KEY_OPTIONS.map { it.id to it.label }, keyIds, full, onToggle)
            PaletteSection("Keys", KeyboardSettings.CONTROL_KEY_OPTIONS.map { it.id to it.label }, keyIds, full, onToggle)
            PaletteSection("Symbols", KeyboardSettings.SYMBOL_KEY_OPTIONS.map { it.id to it.label }, keyIds, full, onToggle)
            PaletteSection(
                title = "Macros",
                options = macros.map { it.keyId to it.displayLabel },
                keyIds = keyIds,
                full = full,
                onToggle = onToggle,
            ) {
                FilterChip(
                    selected = false,
                    onClick = onNewMacro,
                    enabled = canCreateMacro && !full,
                    label = { Text("New macro") },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_add_24), contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                )
            }
        }
    }
}

@Composable
private fun PaletteSection(
    title: String,
    options: List<Pair<String, String>>,
    keyIds: List<String>,
    full: Boolean,
    onToggle: (String) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = ZnKeyboardColors.Muted, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (id, label) ->
                val selected = id in keyIds
                FilterChip(
                    selected = selected,
                    onClick = { onToggle(id) },
                    enabled = selected || !full,
                    label = { Text(label, maxLines = 1) },
                )
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun MacroEditorScreen(
    draft: MacroDraft,
    onBack: () -> Unit,
    onSave: (ShortcutMacro) -> Unit,
    onDelete: () -> Unit,
) {
    var label by remember(draft) { mutableStateOf(draft.macro.label) }
    var steps by remember(draft) { mutableStateOf(draft.macro.steps) }
    // -1 appends a new step; any other value edits that step.
    var keyPickerIndex by remember { mutableStateOf<Int?>(null) }
    var textDialogIndex by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val canAddStep = steps.size < ShortcutMacros.MAX_STEPS

    BackHandler(onBack = onBack)

    fun putStep(index: Int, step: MacroStep) {
        steps = if (index in steps.indices) {
            steps.toMutableList().apply { set(index, step) }
        } else {
            steps + step
        }
    }

    Scaffold(
        containerColor = ZnKeyboardColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(if (draft.isNew) "New macro" else "Edit macro") },
                navigationIcon = { BackIconButton(onBack) },
                actions = {
                    TextButton(
                        onClick = {
                            onSave(
                                ShortcutMacro(
                                    id = draft.macro.id,
                                    label = ShortcutMacros.normalizeLabel(label),
                                    steps = ShortcutMacros.normalizeSteps(steps),
                                ),
                            )
                        },
                        enabled = steps.isNotEmpty(),
                    ) {
                        Text("Save")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ZnKeyboardColors.Background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSectionCard(verticalSpacing = 12.dp) {
                val autoLabel = ShortcutMacros.autoLabel(steps)
                Surface(
                    color = ZnKeyboardColors.Background,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                        KeyChip(label = label.trim().ifEmpty { autoLabel })
                    }
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(ShortcutMacros.MAX_LABEL_CHARS) },
                    label = { Text("Key label") },
                    placeholder = { Text(autoLabel) },
                    supportingText = { Text("Leave blank to show “$autoLabel”") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SettingsSectionCard(verticalSpacing = 10.dp) {
                SettingsSectionHeader(
                    title = "Steps",
                    subtitle = "Played in order when you tap the key.",
                    trailingText = "${steps.size}/${ShortcutMacros.MAX_STEPS}",
                )

                if (steps.isEmpty()) {
                    Text(
                        text = "Add a key combo such as Ctrl + B, or text to type.",
                        color = ZnKeyboardColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                steps.forEachIndexed { index, step ->
                    MacroStepRow(
                        index = index,
                        step = step,
                        canMoveUp = index > 0,
                        canMoveDown = index < steps.lastIndex,
                        onEdit = {
                            when (step) {
                                is MacroStep.Key -> keyPickerIndex = index
                                is MacroStep.Text -> textDialogIndex = index
                            }
                        },
                        onMove = { delta ->
                            steps = steps.toMutableList().apply { add(index + delta, removeAt(index)) }
                        },
                        onRemove = { steps = steps.toMutableList().apply { removeAt(index) } },
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { keyPickerIndex = -1 },
                        enabled = canAddStep,
                        modifier = Modifier.weight(1f),
                    ) {
                        SettingsIconTextButtonContent(R.drawable.ic_keyboard_24, "Key combo")
                    }
                    OutlinedButton(
                        onClick = { textDialogIndex = -1 },
                        enabled = canAddStep,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
                    ) {
                        SettingsIconTextButtonContent(R.drawable.ic_add_24, "Text")
                    }
                }
            }

            if (!draft.isNew) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    SettingsIconTextButtonContent(R.drawable.ic_delete_24, "Delete macro")
                }
            }
        }
    }

    keyPickerIndex?.let { index ->
        KeyComboSheet(
            initial = steps.getOrNull(index) as? MacroStep.Key,
            onPick = { step ->
                putStep(index, step)
                keyPickerIndex = null
            },
            onDismiss = { keyPickerIndex = null },
        )
    }

    textDialogIndex?.let { index ->
        TextStepDialog(
            initial = (steps.getOrNull(index) as? MacroStep.Text)?.text.orEmpty(),
            isNew = index !in steps.indices,
            onConfirm = { text ->
                putStep(index, MacroStep.Text(text))
                textDialogIndex = null
            },
            onDismiss = { textDialogIndex = null },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete macro?") },
            text = { Text("It will also be removed from your shortcut rows.") },
            confirmButton = {
                TextButton(onClick = onDelete) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun MacroStepRow(
    index: Int,
    step: MacroStep,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        onClick = onEdit,
        color = ZnKeyboardColors.Key,
        shape = RoundedCornerShape(SettingsUiDimensions.COMPACT_ITEM_CORNER_RADIUS),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${index + 1}",
                color = ZnKeyboardColors.Muted,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(24.dp),
            )
            Text(
                text = ShortcutMacros.stepLabel(step),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (step is MacroStep.Key) FontFamily.Monospace else FontFamily.Default,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                Icon(painterResource(R.drawable.ic_chevron_left_24), "Move up", Modifier.rotate(90f))
            }
            IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                Icon(painterResource(R.drawable.ic_chevron_left_24), "Move down", Modifier.rotate(-90f))
            }
            IconButton(onClick = onRemove) {
                Icon(painterResource(R.drawable.ic_close_24), "Remove step", tint = ZnKeyboardColors.Muted)
            }
        }
    }
}

@Composable
private fun KeyComboSheet(
    initial: MacroStep.Key?,
    onPick: (MacroStep.Key) -> Unit,
    onDismiss: () -> Unit,
) {
    var ctrl by remember { mutableStateOf(initial?.ctrl ?: false) }
    var alt by remember { mutableStateOf(initial?.alt ?: false) }
    var shift by remember { mutableStateOf(initial?.shift ?: false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Key combo", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Hold modifiers, then tap a key.",
                color = ZnKeyboardColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = ctrl, onClick = { ctrl = !ctrl }, label = { Text("Ctrl") })
                FilterChip(selected = alt, onClick = { alt = !alt }, label = { Text("Alt") })
                FilterChip(selected = shift, onClick = { shift = !shift }, label = { Text("Shift") })
            }

            MacroKeyGroup.entries.forEach { group ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(group.label, color = ZnKeyboardColors.Muted, style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ShortcutMacros.KEYS.filter { it.group == group }.forEach { key ->
                            val step = MacroStep.Key(key.id, ctrl = ctrl, alt = alt, shift = shift)
                            KeyChip(
                                label = if ((ctrl || alt || shift) && key.label.length == 1) {
                                    key.label.uppercase()
                                } else {
                                    key.label
                                },
                                selected = initial == step,
                                onClick = { onPick(step) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextStepDialog(
    initial: String,
    isNew: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add text" else "Edit text") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(ShortcutMacros.MAX_TEXT_CHARS) },
                label = { Text("Text to type") },
                supportingText = { Text("${text.length}/${ShortcutMacros.MAX_TEXT_CHARS}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotEmpty()) {
                Text(if (isNew) "Add" else "Update")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun shortcutKeyLabel(keyId: String, macros: List<ShortcutMacro>): String {
    ShortcutMacros.macroIdOf(keyId)?.let { macroId ->
        return macros.firstOrNull { it.id == macroId }?.displayLabel ?: "Macro"
    }
    return KeyboardSettings.labelForShortcutRowKey(keyId)
}
