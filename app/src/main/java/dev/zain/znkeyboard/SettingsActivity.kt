package dev.zain.znkeyboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import dev.zain.znkeyboard.constants.SettingsBackupDefaults
import dev.zain.znkeyboard.constants.SettingsThemeColors
import dev.zain.znkeyboard.constants.SettingsUiDimensions
import dev.zain.znkeyboard.constants.SettingsUiTimings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ZnKeyboardTheme {
                SettingsScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var heightScale by remember { mutableFloatStateOf(KeyboardSettings.readHeightScale(context)) }
    var upperRowKeyIds by remember { mutableStateOf(KeyboardSettings.readUpperRowKeyIds(context)) }
    var secondRowButtonIds by remember { mutableStateOf(KeyboardSettings.readSecondRowButtonIds(context)) }
    var keyboardRowOrder by remember { mutableStateOf(KeyboardSettings.readKeyboardRowOrder(context)) }
    var emojiSkinTone by remember { mutableStateOf(KeyboardSettings.readEmojiSkinTone(context)) }
    var recentEmojiRows by remember { mutableStateOf(KeyboardSettings.readRecentEmojiRows(context)) }
    var customEmojiTags by remember { mutableStateOf(KeyboardSettings.readCustomEmojiTags(context)) }
    var gifApiBaseUrl by remember { mutableStateOf(KeyboardSettings.readGifApiBaseUrl(context)) }
    var gifAppKey by remember { mutableStateOf(KeyboardSettings.readGifAppKey(context)) }
    var gifAppKeyLocked by remember { mutableStateOf(KeyboardSettings.readGifAppKeyLocked(context)) }
    var agentModeEnabled by remember { mutableStateOf(KeyboardSettings.readAgentModeEnabled(context)) }
    var agentProviderType by remember { mutableStateOf(KeyboardSettings.readAgentProviderType(context)) }
    var agentApiBaseUrl by remember { mutableStateOf(KeyboardSettings.readAgentApiBaseUrl(context)) }
    var agentModel by remember { mutableStateOf(KeyboardSettings.readAgentModel(context)) }
    var openRouterProviderSlug by remember { mutableStateOf(KeyboardSettings.readOpenRouterProviderSlug(context)) }
    var agentReasoningMode by remember { mutableStateOf(KeyboardSettings.readAgentReasoningMode(context)) }
    var agentReasoningTextEnabled by remember { mutableStateOf(KeyboardSettings.readAgentReasoningTextEnabled(context)) }
    var agentApiKey by remember { mutableStateOf(KeyboardSettings.readAgentApiKey(context, agentProviderType)) }
    var agentApiKeyLocked by remember { mutableStateOf(KeyboardSettings.readAgentApiKeyLocked(context, agentProviderType)) }
    var textSnippets by remember { mutableStateOf(KeyboardSettings.readTextSnippets(context)) }

    fun refreshSettingsFromStorage() {
        val nextProviderType = KeyboardSettings.readAgentProviderType(context)
        heightScale = KeyboardSettings.readHeightScale(context)
        upperRowKeyIds = KeyboardSettings.readUpperRowKeyIds(context)
        secondRowButtonIds = KeyboardSettings.readSecondRowButtonIds(context)
        keyboardRowOrder = KeyboardSettings.readKeyboardRowOrder(context)
        emojiSkinTone = KeyboardSettings.readEmojiSkinTone(context)
        recentEmojiRows = KeyboardSettings.readRecentEmojiRows(context)
        customEmojiTags = KeyboardSettings.readCustomEmojiTags(context)
        gifApiBaseUrl = KeyboardSettings.readGifApiBaseUrl(context)
        gifAppKey = KeyboardSettings.readGifAppKey(context)
        gifAppKeyLocked = KeyboardSettings.readGifAppKeyLocked(context)
        agentModeEnabled = KeyboardSettings.readAgentModeEnabled(context)
        agentProviderType = nextProviderType
        agentApiBaseUrl = KeyboardSettings.readAgentApiBaseUrl(context)
        agentModel = KeyboardSettings.readAgentModel(context)
        openRouterProviderSlug = KeyboardSettings.readOpenRouterProviderSlug(context)
        agentReasoningMode = KeyboardSettings.readAgentReasoningMode(context)
        agentReasoningTextEnabled = KeyboardSettings.readAgentReasoningTextEnabled(context)
        agentApiKey = KeyboardSettings.readAgentApiKey(context, nextProviderType)
        agentApiKeyLocked = KeyboardSettings.readAgentApiKeyLocked(context, nextProviderType)
        textSnippets = KeyboardSettings.readTextSnippets(context)
    }

    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { writeSettingsBackup(context, uri) }
            }
            val message = result.fold(
                onSuccess = { "Settings exported" },
                onFailure = { error ->
                    error.message?.takeIf { it.isNotBlank() } ?: "Could not export settings"
                },
            )
            Toast.makeText(
                context,
                message,
                if (result.isSuccess) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
            ).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { restoreSettingsBackup(context, uri) }
            }
            result
                .onSuccess { restoreResult ->
                    refreshSettingsFromStorage()
                    Toast.makeText(
                        context,
                        "Settings restored (${restoreResult.snippetCount} snippets)",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                .onFailure { error ->
                    Toast.makeText(
                        context,
                        error.message?.takeIf { it.isNotBlank() } ?: "Could not import settings",
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    DisposableEffect(context) {
        val listener = KeyboardSettings.registerTextSnippetsChangeListener(context) {
            textSnippets = KeyboardSettings.readTextSnippets(context)
        }
        onDispose {
            KeyboardSettings.unregisterTextSnippetsChangeListener(context, listener)
        }
    }

    fun updateUpperRowKeyIds(keyIds: List<String>) {
        val normalizedKeyIds = KeyboardSettings.normalizeUpperRowKeyIds(keyIds)
        upperRowKeyIds = normalizedKeyIds
        KeyboardSettings.saveUpperRowKeyIds(context, normalizedKeyIds)
    }

    fun updateSecondRowButtonIds(buttonIds: List<String>) {
        val normalizedButtonIds = KeyboardSettings.normalizeSecondRowButtonIds(buttonIds)
        secondRowButtonIds = normalizedButtonIds
        KeyboardSettings.saveSecondRowButtonIds(context, normalizedButtonIds)
    }

    fun updateKeyboardRowOrder(rowIds: List<String>) {
        val normalizedRowIds = KeyboardSettings.normalizeKeyboardRowOrder(rowIds)
        keyboardRowOrder = normalizedRowIds
        KeyboardSettings.saveKeyboardRowOrder(context, normalizedRowIds)
    }

    fun updateTextSnippets(snippets: List<KeyboardSettings.TextSnippet>) {
        val normalizedSnippets = KeyboardSettings.normalizeTextSnippets(snippets)
        textSnippets = normalizedSnippets
        KeyboardSettings.saveTextSnippets(context, normalizedSnippets)
    }

    fun updateCustomEmojiTags(tagsByEmoji: Map<String, List<String>>) {
        val normalizedTags = KeyboardSettings.normalizeCustomEmojiTags(tagsByEmoji)
        customEmojiTags = normalizedTags
        KeyboardSettings.saveCustomEmojiTags(context, normalizedTags)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(ZnKeyboardColors.Background),
        containerColor = ZnKeyboardColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("ZnKeyboard") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ZnKeyboardColors.Background,
                    titleContentColor = ZnKeyboardColors.OnSurface,
                ),
                modifier = Modifier.statusBarsPadding(),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .pointerInput(focusManager) {
                    detectTapGestures(onTap = { focusManager.clearFocus() })
                }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            SystemSetupActions()

            BackupRestoreSection(
                onExport = { exportLauncher.launch(suggestedBackupFileName()) },
                onImport = { importLauncher.launch(BACKUP_IMPORT_MIME_TYPES) },
            )

            TextSnippetsSection(
                snippets = textSnippets,
                onSnippetsChange = ::updateTextSnippets,
            )

            AgentModeSection(
                agentModeEnabled = agentModeEnabled,
                agentProviderType = agentProviderType,
                agentApiBaseUrl = agentApiBaseUrl,
                agentModel = agentModel,
                openRouterProviderSlug = openRouterProviderSlug,
                agentReasoningMode = agentReasoningMode,
                agentReasoningTextEnabled = agentReasoningTextEnabled,
                agentApiKey = agentApiKey,
                agentApiKeyLocked = agentApiKeyLocked,
                onAgentModeEnabledChange = {
                    agentModeEnabled = it
                    KeyboardSettings.saveAgentModeEnabled(context, it)
                },
                onAgentProviderTypeChange = {
                    agentProviderType = it
                    KeyboardSettings.saveAgentProviderType(context, it)
                    agentApiKey = KeyboardSettings.readAgentApiKey(context, it)
                    agentApiKeyLocked = KeyboardSettings.readAgentApiKeyLocked(context, it)
                },
                onAgentApiBaseUrlChange = {
                    agentApiBaseUrl = it
                    KeyboardSettings.saveAgentApiBaseUrl(context, it)
                },
                onAgentModelChange = {
                    agentModel = it
                    KeyboardSettings.saveAgentModel(context, it)
                },
                onOpenRouterProviderSlugChange = {
                    openRouterProviderSlug = it
                    KeyboardSettings.saveOpenRouterProviderSlug(context, it)
                },
                onAgentReasoningModeChange = {
                    agentReasoningMode = it
                    KeyboardSettings.saveAgentReasoningMode(context, it)
                },
                onAgentReasoningTextEnabledChange = {
                    agentReasoningTextEnabled = it
                    KeyboardSettings.saveAgentReasoningTextEnabled(context, it)
                },
                onAgentApiKeyChange = {
                    agentApiKey = it
                    KeyboardSettings.saveAgentApiKey(context, agentProviderType, it)
                    if (it.isBlank() && agentApiKeyLocked) {
                        agentApiKeyLocked = false
                    }
                },
                onAgentApiKeyLockedChange = {
                    agentApiKeyLocked = it
                    KeyboardSettings.saveAgentApiKeyLocked(context, agentProviderType, it)
                },
            )

            GifSearchSection(
                gifApiBaseUrl = gifApiBaseUrl,
                gifAppKey = gifAppKey,
                gifAppKeyLocked = gifAppKeyLocked,
                onGifApiBaseUrlChange = {
                    gifApiBaseUrl = it
                    KeyboardSettings.saveGifApiBaseUrl(context, it)
                },
                onGifAppKeyChange = {
                    gifAppKey = it
                    KeyboardSettings.saveGifAppKey(context, it)
                    if (it.isBlank() && gifAppKeyLocked) {
                        gifAppKeyLocked = false
                    }
                },
                onGifAppKeyLockedChange = {
                    gifAppKeyLocked = it
                    KeyboardSettings.saveGifAppKeyLocked(context, it)
                },
            )

            Surface(
                color = ZnKeyboardColors.Surface,
                shape = RoundedCornerShape(SECTION_CORNER_RADIUS),
                tonalElevation = 0.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_tune_24),
                                contentDescription = null,
                                tint = ZnKeyboardColors.Accent,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Keyboard height",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            text = "${(heightScale * 100f).roundToInt()}%",
                            color = ZnKeyboardColors.Muted,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }

                    Slider(
                        value = heightScale,
                        onValueChange = {
                            heightScale = it
                            KeyboardSettings.saveHeightScale(context, it)
                        },
                        valueRange = KeyboardSettings.MIN_HEIGHT_SCALE..KeyboardSettings.MAX_HEIGHT_SCALE,
                        steps = 6,
                    )
                }
            }

            EmojiPreferencesSection(
                skinTone = emojiSkinTone,
                recentEmojiRows = recentEmojiRows,
                customEmojiTags = customEmojiTags,
                onSkinToneChange = {
                    emojiSkinTone = it
                    KeyboardSettings.saveEmojiSkinTone(context, it)
                },
                onRecentEmojiRowsChange = {
                    val rows = EmojiCatalog.normalizeRecentRowCount(it)
                    recentEmojiRows = rows
                    KeyboardSettings.saveRecentEmojiRows(context, rows)
                },
                onCustomEmojiTagsChange = ::updateCustomEmojiTags,
            )

            RowButtonsSection(
                title = "Shortcut row B keys",
                keyIds = secondRowButtonIds,
                maxKeys = KeyboardSettings.MAX_SECOND_ROW_BUTTONS,
                defaultKeyIds = KeyboardSettings.DEFAULT_SECOND_ROW_BUTTON_IDS,
                keyOptions = KeyboardSettings.SHORTCUT_ROW_KEY_OPTIONS,
                labelForKey = KeyboardSettings::labelForShortcutRowKey,
                onKeyIdsChange = ::updateSecondRowButtonIds,
            )

            RowButtonsSection(
                title = "Shortcut row A keys",
                keyIds = upperRowKeyIds,
                maxKeys = KeyboardSettings.MAX_UPPER_ROW_KEYS,
                defaultKeyIds = KeyboardSettings.DEFAULT_UPPER_ROW_KEY_IDS,
                keyOptions = KeyboardSettings.SHORTCUT_ROW_KEY_OPTIONS,
                labelForKey = KeyboardSettings::labelForShortcutRowKey,
                onKeyIdsChange = ::updateUpperRowKeyIds,
            )

            KeyboardRowOrderSection(
                rowOrder = keyboardRowOrder,
                onRowOrderChange = ::updateKeyboardRowOrder,
            )
        }
    }
}

@Composable
private fun TextSnippetsSection(
    snippets: List<KeyboardSettings.TextSnippet>,
    onSnippetsChange: (List<KeyboardSettings.TextSnippet>) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val normalizedDraft = KeyboardSettings.textSnippetFromText(
        text = draft,
    )
    val canAddSnippet = normalizedDraft.text.isNotBlank() &&
        snippets.none { it.text == normalizedDraft.text } &&
        snippets.size < KeyboardSettings.MAX_TEXT_SNIPPETS

    Surface(
        color = ZnKeyboardColors.Surface,
        shape = RoundedCornerShape(SECTION_CORNER_RADIUS),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Text snippets",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${snippets.size}/${KeyboardSettings.MAX_TEXT_SNIPPETS}",
                    color = ZnKeyboardColors.Muted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(KeyboardSettings.MAX_TEXT_SNIPPET_CHARS) },
                label = { Text("Snippet") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    onSnippetsChange(snippets + normalizedDraft)
                    draft = ""
                },
                enabled = canAddSnippet,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add_24),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Add")
            }

            if (snippets.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(COMPACT_ITEM_CORNER_RADIUS))
                        .background(ZnKeyboardColors.Key),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No snippets",
                        color = ZnKeyboardColors.Muted,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    snippets.forEachIndexed { index, snippet ->
                        TextSnippetEditorRow(
                            snippet = snippet,
                            onTagsChange = { tags ->
                                onSnippetsChange(
                                    snippets.toMutableList().apply {
                                        this[index] = snippet.copy(tags = tags)
                                    },
                                )
                            },
                            onRemove = {
                                onSnippetsChange(
                                    snippets.toMutableList().apply {
                                        removeAt(index)
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextSnippetEditorRow(
    snippet: KeyboardSettings.TextSnippet,
    onTagsChange: (List<String>) -> Unit,
    onRemove: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }

    SwipeRevealDeleteRow(
        onRemove = { confirmingDelete = true },
    ) {
        Surface(
            color = ZnKeyboardColors.Key,
            shape = RoundedCornerShape(COMPACT_ITEM_CORNER_RADIUS),
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = snippet.text,
                    color = ZnKeyboardColors.OnSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                TextSnippetTagsEditor(
                    tags = snippet.tags,
                    onTagsChange = onTagsChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete snippet?") },
            text = {
                Text(
                    text = snippet.text,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onRemove()
                    },
                ) {
                    Text(
                        text = "Delete",
                        color = ZnKeyboardColors.DeleteContent,
                    )
                }
            },
        )
    }
}

@Composable
private fun SwipeRevealDeleteRow(
    onRemove: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val revealWidthPx = with(density) { TEXT_SNIPPET_DELETE_REVEAL_WIDTH.toPx() }
    var offsetPx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val visualOffsetPx by animateFloatAsState(
        targetValue = offsetPx,
        animationSpec = if (dragging) {
            snap()
        } else {
            spring(stiffness = Spring.StiffnessMediumLow)
        },
        label = "textSnippetDeleteRevealOffset",
    )
    val draggableState = rememberDraggableState { delta ->
        offsetPx = (offsetPx + delta).coerceIn(-revealWidthPx, 0f)
    }
    val shape = RoundedCornerShape(COMPACT_ITEM_CORNER_RADIUS)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ZnKeyboardColors.DeleteBackground),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .clickable(onClick = onRemove)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_delete_24),
                contentDescription = null,
                tint = ZnKeyboardColors.DeleteContent,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Delete",
                color = ZnKeyboardColors.DeleteContent,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(visualOffsetPx.roundToInt(), 0) }
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Horizontal,
                    onDragStarted = {
                        dragging = true
                    },
                    onDragStopped = {
                        dragging = false
                        offsetPx = if (offsetPx <= -revealWidthPx * TEXT_SNIPPET_DELETE_LOCK_THRESHOLD) {
                            -revealWidthPx
                        } else {
                            0f
                        }
                    },
                ),
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextSnippetTagsEditor(
    tags: List<String>,
    onTagsChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    helperText: String? = null,
) {
    var addingTag by remember(tags) { mutableStateOf(false) }
    var tagDraft by remember(tags) { mutableStateOf("") }

    fun submitTagDraft() {
        val newTags = KeyboardSettings.parseTextSnippetTags(tagDraft)
        if (newTags.isNotEmpty()) {
            onTagsChange(KeyboardSettings.normalizeTextSnippetTags(tags + newTags))
        }
        tagDraft = ""
        addingTag = false
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Tags",
            color = ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.labelMedium,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tags.forEach { tag ->
                TextSnippetTagPill(
                    tag = tag,
                    onRemove = {
                        onTagsChange(tags.filterNot { it.equals(tag, ignoreCase = true) })
                    },
                )
            }

            if (tags.size < KeyboardSettings.MAX_TEXT_SNIPPET_TAGS) {
                if (addingTag) {
                    TextSnippetTagInputPill(
                        value = tagDraft,
                        onValueChange = { tagDraft = it },
                        onDone = ::submitTagDraft,
                    )
                } else {
                    AddTextSnippetTagButton(
                        onClick = { addingTag = true },
                    )
                }
            }
        }

        helperText?.let { text ->
            Text(
                text = text,
                color = ZnKeyboardColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TextSnippetTagPill(
    tag: String,
    onRemove: () -> Unit,
) {
    Surface(
        color = ZnKeyboardColors.FunctionKey,
        contentColor = ZnKeyboardColors.OnSurface,
        shape = CircleShape,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 5.dp, end = 7.dp, bottom = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "#$tag",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                painter = painterResource(R.drawable.ic_close_24),
                contentDescription = "Remove $tag tag",
                tint = ZnKeyboardColors.Muted,
                modifier = Modifier
                    .size(14.dp)
                    .clickable(onClick = onRemove),
            )
        }
    }
}

@Composable
private fun AddTextSnippetTagButton(
    onClick: () -> Unit,
) {
    Surface(
        color = Color.Transparent,
        contentColor = ZnKeyboardColors.Muted,
        shape = CircleShape,
        border = BorderStroke(1.dp, ZnKeyboardColors.Muted.copy(alpha = 0.7f)),
        tonalElevation = 0.dp,
        modifier = Modifier
            .size(30.dp)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_add_24),
                contentDescription = "Add tag",
                tint = ZnKeyboardColors.Muted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun TextSnippetTagInputPill(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var hasFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.labelMedium.copy(color = ZnKeyboardColors.OnSurface),
        cursorBrush = SolidColor(ZnKeyboardColors.Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = {
                onDone()
                focusManager.clearFocus()
            },
        ),
        modifier = Modifier
            .widthIn(min = 118.dp, max = 220.dp)
            .onFocusChanged { focusState ->
                if (hasFocused && !focusState.isFocused) {
                    onDone()
                }
                if (focusState.isFocused) {
                    hasFocused = true
                }
            }
            .focusRequester(focusRequester),
        decorationBox = { innerTextField ->
            Surface(
                color = Color.Transparent,
                contentColor = ZnKeyboardColors.OnSurface,
                shape = CircleShape,
                border = BorderStroke(1.dp, ZnKeyboardColors.Accent.copy(alpha = 0.75f)),
                tonalElevation = 0.dp,
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isBlank()) {
                        Text(
                            text = "tag, tag",
                            color = ZnKeyboardColors.Muted,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    innerTextField()
                }
            }
        },
    )
}

@Composable
private fun BackupRestoreSection(
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    Surface(
        color = ZnKeyboardColors.Surface,
        shape = RoundedCornerShape(SECTION_CORNER_RADIUS),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_download_24),
                        contentDescription = null,
                        tint = ZnKeyboardColors.Accent,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Backup & restore",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = "JSON",
                    color = ZnKeyboardColors.Muted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Text(
                text = "API keys stay out of backup files.",
                color = ZnKeyboardColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onExport,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_download_24),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Export")
                }

                OutlinedButton(
                    onClick = onImport,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_upload_24),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Import")
                }
            }
        }
    }
}

@Composable
private fun GifSearchSection(
    gifApiBaseUrl: String,
    gifAppKey: String,
    gifAppKeyLocked: Boolean,
    onGifApiBaseUrlChange: (String) -> Unit,
    onGifAppKeyChange: (String) -> Unit,
    onGifAppKeyLockedChange: (Boolean) -> Unit,
) {
    val effectivelyLocked = gifAppKeyLocked && gifAppKey.isNotBlank()

    Surface(
        color = ZnKeyboardColors.Surface,
        shape = RoundedCornerShape(SECTION_CORNER_RADIUS),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "GIF search",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Uses KLIPY for searchable GIFs and meme-friendly results.",
                        color = ZnKeyboardColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = if (gifAppKey.isBlank()) "Setup" else "Ready",
                    color = if (gifAppKey.isBlank()) ZnKeyboardColors.Muted else ZnKeyboardColors.Accent,
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Text(
                text = "GIF search queries go directly from this keyboard to KLIPY. The app key is stored on this device and stays out of backups.",
                color = ZnKeyboardColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = gifApiBaseUrl,
                onValueChange = onGifApiBaseUrlChange,
                label = { Text("API base URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = {
                    Text(
                        text = "Default: ${KeyboardSettings.DEFAULT_GIF_API_BASE_URL}",
                        color = ZnKeyboardColors.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = gifAppKey,
                onValueChange = onGifAppKeyChange,
                label = { Text("KLIPY app key") },
                singleLine = true,
                enabled = !effectivelyLocked,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            if (effectivelyLocked) {
                                onGifAppKeyLockedChange(false)
                            } else if (gifAppKey.isNotBlank()) {
                                onGifAppKeyLockedChange(true)
                            }
                        },
                        enabled = gifAppKey.isNotBlank(),
                    ) {
                        Icon(
                            painter = painterResource(
                                if (effectivelyLocked) R.drawable.ic_lock_24 else R.drawable.ic_lock_open_24,
                            ),
                            contentDescription = if (effectivelyLocked) "Unlock KLIPY app key" else "Lock KLIPY app key",
                            tint = if (effectivelyLocked) ZnKeyboardColors.Accent else ZnKeyboardColors.Muted,
                        )
                    }
                },
                supportingText = {
                    Text(
                        text = if (effectivelyLocked) {
                            "Locked. Tap lock to edit."
                        } else if (gifAppKey.isNotBlank()) {
                            "Tap lock to prevent edits."
                        } else {
                            "Create a free key in the KLIPY partner panel."
                        },
                        color = ZnKeyboardColors.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EmojiPreferencesSection(
    skinTone: EmojiSkinTone,
    recentEmojiRows: Int,
    customEmojiTags: Map<String, List<String>>,
    onSkinToneChange: (EmojiSkinTone) -> Unit,
    onRecentEmojiRowsChange: (Int) -> Unit,
    onCustomEmojiTagsChange: (Map<String, List<String>>) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        color = ZnKeyboardColors.Surface,
        shape = RoundedCornerShape(SECTION_CORNER_RADIUS),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Emoji",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
                ) {
                    Text(
                        text = skinTone.sample,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        Text(
                            text = "Default tone",
                            color = ZnKeyboardColors.Muted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            text = skinTone.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Text(
                        text = "Change",
                        color = ZnKeyboardColors.Accent,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                FloatingPickerMenu(
                    expanded = expanded,
                    items = EmojiSkinTone.entries.map { option ->
                        PickerItem(
                            id = option.id,
                            title = "${option.sample}  ${option.label}",
                        )
                    },
                    onDismissRequest = { expanded = false },
                    onItemSelected = { item ->
                        expanded = false
                        EmojiSkinTone.entries.firstOrNull { it.id == item.id }
                            ?.let(onSkinToneChange)
                    },
                    maxHeight = SKIN_TONE_PICKER_MAX_HEIGHT,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Recent rows",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "$recentEmojiRows",
                    color = ZnKeyboardColors.Muted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Slider(
                value = recentEmojiRows.toFloat(),
                onValueChange = { value ->
                    onRecentEmojiRowsChange(value.roundToInt())
                },
                valueRange = EmojiCatalog.MIN_RECENT_ROW_COUNT.toFloat()..EmojiCatalog.MAX_RECENT_ROW_COUNT.toFloat(),
                steps = EmojiCatalog.MAX_RECENT_ROW_COUNT - EmojiCatalog.MIN_RECENT_ROW_COUNT - 1,
            )

            CustomEmojiTagsEditor(
                tagsByEmoji = customEmojiTags,
                onTagsChange = onCustomEmojiTagsChange,
            )
        }
    }
}

@Composable
private fun CustomEmojiTagsEditor(
    tagsByEmoji: Map<String, List<String>>,
    onTagsChange: (Map<String, List<String>>) -> Unit,
) {
    var draftEmoji by remember { mutableStateOf("") }
    var draftTags by remember { mutableStateOf("") }
    val normalizedEmoji = remember(draftEmoji) {
        draftEmoji
            .takeIf { it.isNotBlank() }
            ?.let(KeyboardSettings::normalizeEmojiForCustomTags)
    }
    val normalizedTags = remember(draftTags) {
        KeyboardSettings.normalizeCustomEmojiTagInput(draftTags)
    }
    val canSave = normalizedEmoji != null && normalizedTags.isNotEmpty()
    val editingExistingEmoji = normalizedEmoji?.let { it in tagsByEmoji } == true
    val atLimit = normalizedEmoji != null &&
        !editingExistingEmoji &&
        tagsByEmoji.size >= KeyboardSettings.MAX_CUSTOM_EMOJI_TAGGED_EMOJIS

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Custom search tags",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${tagsByEmoji.size}/${KeyboardSettings.MAX_CUSTOM_EMOJI_TAGGED_EMOJIS}",
                color = ZnKeyboardColors.Muted,
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Text(
            text = "Add vocabulary that should find a specific emoji in keyboard search.",
            color = ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = draftEmoji,
                onValueChange = { draftEmoji = it },
                label = { Text("Emoji") },
                singleLine = true,
                modifier = Modifier.width(96.dp),
            )
            OutlinedTextField(
                value = draftTags,
                onValueChange = { draftTags = it },
                label = { Text("Tags") },
                placeholder = { Text("chai, duas") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        Button(
            onClick = {
                val emoji = normalizedEmoji ?: return@Button
                onTagsChange(tagsByEmoji + (emoji to normalizedTags))
                draftEmoji = ""
                draftTags = ""
            },
            enabled = canSave && (editingExistingEmoji || tagsByEmoji.size < KeyboardSettings.MAX_CUSTOM_EMOJI_TAGGED_EMOJIS),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add_24),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(if (editingExistingEmoji) "Update tags" else "Add tags")
        }

        val helperText = when {
            draftEmoji.isBlank() && draftTags.isBlank() -> "Paste one supported emoji and comma-separated tags."
            normalizedEmoji == null -> "Paste exactly one supported emoji."
            normalizedTags.isEmpty() -> "Enter at least one tag."
            atLimit -> "Remove an emoji before adding another."
            else -> "Search will match: ${normalizedTags.joinToString(", ")}"
        }
        Text(
            text = helperText,
            color = ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )

        if (tagsByEmoji.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(COMPACT_ITEM_CORNER_RADIUS))
                    .background(ZnKeyboardColors.Key),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No custom emoji tags",
                    color = ZnKeyboardColors.Muted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tagsByEmoji.forEach { (emoji, tags) ->
                    CustomEmojiTagRow(
                        emoji = emoji,
                        tags = tags,
                        onEdit = {
                            draftEmoji = emoji
                            draftTags = tags.joinToString(", ")
                        },
                        onRemove = {
                            onTagsChange(tagsByEmoji - emoji)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomEmojiTagRow(
    emoji: String,
    tags: List<String>,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = ZnKeyboardColors.Key,
            shape = RoundedCornerShape(COMPACT_ITEM_CORNER_RADIUS),
            tonalElevation = 0.dp,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onEdit),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = emoji,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = tags.joinToString(", "),
                    color = ZnKeyboardColors.OnSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(44.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_delete_24),
                contentDescription = "Remove custom emoji tags",
                tint = ZnKeyboardColors.Muted,
            )
        }
    }
}

@Composable
private fun AgentModeSection(
    agentModeEnabled: Boolean,
    agentProviderType: KeyboardSettings.AgentProviderType,
    agentApiBaseUrl: String,
    agentModel: String,
    openRouterProviderSlug: String,
    agentReasoningMode: KeyboardSettings.AgentReasoningMode,
    agentReasoningTextEnabled: Boolean,
    agentApiKey: String,
    agentApiKeyLocked: Boolean,
    onAgentModeEnabledChange: (Boolean) -> Unit,
    onAgentProviderTypeChange: (KeyboardSettings.AgentProviderType) -> Unit,
    onAgentApiBaseUrlChange: (String) -> Unit,
    onAgentModelChange: (String) -> Unit,
    onOpenRouterProviderSlugChange: (String) -> Unit,
    onAgentReasoningModeChange: (KeyboardSettings.AgentReasoningMode) -> Unit,
    onAgentReasoningTextEnabledChange: (Boolean) -> Unit,
    onAgentApiKeyChange: (String) -> Unit,
    onAgentApiKeyLockedChange: (Boolean) -> Unit,
) {
    Surface(
        color = ZnKeyboardColors.Surface,
        shape = RoundedCornerShape(SECTION_CORNER_RADIUS),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Rewrite",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Controls the Rewrite button in the tools row.",
                        color = ZnKeyboardColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = agentModeEnabled,
                    onCheckedChange = onAgentModeEnabledChange,
                )
            }

            Text(
                text = "Text is sent directly from this keyboard to your configured provider. Rewrite is disabled in password fields.",
                color = ZnKeyboardColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )

            ProviderTypeSelector(
                providerType = agentProviderType,
                onProviderTypeChange = onAgentProviderTypeChange,
            )

            if (agentProviderType == KeyboardSettings.AgentProviderType.OpenAiCompatible) {
                OutlinedTextField(
                    value = agentApiBaseUrl,
                    onValueChange = onAgentApiBaseUrlChange,
                    label = { Text("API base URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = "OpenRouter endpoint is managed automatically. Leave pin empty for automatic routing.",
                    color = ZnKeyboardColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = openRouterProviderSlug,
                    onValueChange = onOpenRouterProviderSlugChange,
                    label = { Text("Provider pin") },
                    placeholder = { Text("Example: deepinfra") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ModelSelectorField(
                providerType = agentProviderType,
                apiBaseUrl = agentApiBaseUrl,
                apiKey = agentApiKey,
                value = agentModel,
                onValueChange = onAgentModelChange,
            )

            ReasoningControls(
                providerType = agentProviderType,
                reasoningMode = agentReasoningMode,
                reasoningTextEnabled = agentReasoningTextEnabled,
                onReasoningModeChange = onAgentReasoningModeChange,
                onReasoningTextEnabledChange = onAgentReasoningTextEnabledChange,
            )

            val effectivelyLocked = agentApiKeyLocked && agentApiKey.isNotBlank()
            OutlinedTextField(
                value = agentApiKey,
                onValueChange = onAgentApiKeyChange,
                label = { Text("API key") },
                singleLine = true,
                enabled = !effectivelyLocked,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            if (effectivelyLocked) {
                                onAgentApiKeyLockedChange(false)
                            } else if (agentApiKey.isNotBlank()) {
                                onAgentApiKeyLockedChange(true)
                            }
                        },
                        enabled = agentApiKey.isNotBlank(),
                    ) {
                        Icon(
                            painter = painterResource(
                                if (effectivelyLocked) R.drawable.ic_lock_24 else R.drawable.ic_lock_open_24,
                            ),
                            contentDescription = if (effectivelyLocked) "Unlock API key" else "Lock API key",
                            tint = if (effectivelyLocked) ZnKeyboardColors.Accent else ZnKeyboardColors.Muted,
                        )
                    }
                },
                supportingText = {
                    Text(
                        text = if (effectivelyLocked) {
                            "Locked. Tap lock to edit."
                        } else if (agentApiKey.isNotBlank()) {
                            "Tap lock to prevent edits."
                        } else {
                            "Enter your provider's API key."
                        },
                        color = ZnKeyboardColors.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ModelSelectorField(
    providerType: KeyboardSettings.AgentProviderType,
    apiBaseUrl: String,
    apiKey: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var fieldFocused by remember { mutableStateOf(false) }
    var suggestionsExpanded by remember { mutableStateOf(false) }
    var modelOptions by remember { mutableStateOf<List<ModelOption>>(emptyList()) }
    var modelLoadStatus by remember { mutableStateOf("Loading models...") }
    var debouncedQuery by remember { mutableStateOf(value) }
    val effectiveBaseUrl = when (providerType) {
        KeyboardSettings.AgentProviderType.OpenRouter -> KeyboardSettings.OPENROUTER_API_BASE_URL
        KeyboardSettings.AgentProviderType.OpenAiCompatible -> apiBaseUrl
    }

    LaunchedEffect(providerType, effectiveBaseUrl, apiKey) {
        modelOptions = emptyList()
        if (effectiveBaseUrl.isBlank()) {
            modelLoadStatus = "Enter a base URL to load models."
            return@LaunchedEffect
        }
        if (providerType == KeyboardSettings.AgentProviderType.OpenRouter && apiKey.isBlank()) {
            modelLoadStatus = "Enter your OpenRouter API key to load models."
            return@LaunchedEffect
        }

        modelLoadStatus = "Loading models..."
        delay(MODEL_LOAD_DEBOUNCE_MS)
        val result = withContext(Dispatchers.IO) {
            fetchModelOptions(
                baseUrl = effectiveBaseUrl,
                apiKey = apiKey,
            )
        }
        result
            .onSuccess { models ->
                modelOptions = models
                modelLoadStatus = if (models.isEmpty()) {
                    "No models returned. You can still type a custom model ID."
                } else {
                    "Loaded ${models.size} models. Type to filter or pick one."
                }
            }
            .onFailure { error ->
                modelLoadStatus = error.message?.takeIf { it.isNotBlank() }
                    ?: "Could not load models. You can still type a custom model ID."
            }
    }

    LaunchedEffect(value, modelOptions) {
        delay(MODEL_QUERY_DEBOUNCE_MS)
        debouncedQuery = value
    }

    val filteredOptions = remember(modelOptions, debouncedQuery) {
        modelOptions.filterForQuery(debouncedQuery)
    }
    val showSuggestions = fieldFocused && suggestionsExpanded && filteredOptions.isNotEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                suggestionsExpanded = true
            },
            label = { Text("Model") },
            singleLine = true,
            trailingIcon = {
                Text(
                    text = if (modelOptions.isEmpty()) "Custom" else "${modelOptions.size}",
                    color = ZnKeyboardColors.Muted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(end = 12.dp),
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    fieldFocused = focusState.isFocused
                    if (focusState.isFocused) suggestionsExpanded = true
                },
        )

            FloatingPickerMenu(
                expanded = showSuggestions,
                focusable = false,
                items = filteredOptions.map { option ->
                    PickerItem(
                        id = option.id,
                        title = option.id,
                        subtitle = option.name?.takeIf { it != option.id },
                    )
                },
                onDismissRequest = { suggestionsExpanded = false },
                onItemSelected = { item ->
                    suggestionsExpanded = false
                    onValueChange(item.id)
                },
            )
        }

        if (fieldFocused && suggestionsExpanded && modelOptions.isNotEmpty() && debouncedQuery.isNotBlank() && filteredOptions.isEmpty()) {
            Surface(
                color = ZnKeyboardColors.Key,
                shape = RoundedCornerShape(SECTION_CORNER_RADIUS),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "No matching models. Keep typing to use a custom model ID.",
                    color = ZnKeyboardColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }

        Text(
            text = modelLoadStatus,
            color = ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun FloatingPickerMenu(
    expanded: Boolean,
    items: List<PickerItem>,
    onDismissRequest: () -> Unit,
    onItemSelected: (PickerItem) -> Unit,
    modifier: Modifier = Modifier,
    focusable: Boolean = true,
    maxHeight: Dp = PICKER_MAX_HEIGHT,
    menuWidth: Dp = PICKER_MENU_WIDTH,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = focusable),
        shape = RoundedCornerShape(PICKER_CORNER_RADIUS),
        containerColor = ZnKeyboardColors.Key,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        modifier = modifier
            .width(menuWidth)
            .background(Color.Transparent),
    ) {
        PickerListSurface(
            items = items,
            onItemSelected = onItemSelected,
            maxHeight = maxHeight,
        )
    }
}

@Composable
private fun PickerListSurface(
    items: List<PickerItem>,
    onItemSelected: (PickerItem) -> Unit,
    maxHeight: Dp,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .heightIn(max = maxHeight)
            .verticalScroll(scrollState)
            .padding(vertical = 4.dp),
    ) {
        items.forEachIndexed { index, item ->
            PickerListRow(item = item, onClick = { onItemSelected(item) })
            if (index != items.lastIndex) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(ZnKeyboardColors.Surface),
                )
            }
        }
    }
}

@Composable
private fun PickerListRow(
    item: PickerItem,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = item.title,
            color = ZnKeyboardColors.OnSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        item.subtitle?.let { subtitle ->
            Text(
                text = subtitle,
                color = ZnKeyboardColors.Muted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProviderTypeSelector(
    providerType: KeyboardSettings.AgentProviderType,
    onProviderTypeChange: (KeyboardSettings.AgentProviderType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = "Provider",
                    color = ZnKeyboardColors.Muted,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = providerType.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Text(
                text = "Change",
                color = ZnKeyboardColors.Accent,
                style = MaterialTheme.typography.labelLarge,
            )
        }

        FloatingPickerMenu(
            expanded = expanded,
            items = KeyboardSettings.AgentProviderType.entries.map { option ->
                PickerItem(
                    id = option.id,
                    title = option.label,
                )
            },
            onDismissRequest = { expanded = false },
            onItemSelected = { item ->
                expanded = false
                KeyboardSettings.AgentProviderType.entries.firstOrNull { it.id == item.id }
                    ?.let(onProviderTypeChange)
            },
            maxHeight = PROVIDER_PICKER_MAX_HEIGHT,
        )
    }
}

@Composable
private fun ReasoningControls(
    providerType: KeyboardSettings.AgentProviderType,
    reasoningMode: KeyboardSettings.AgentReasoningMode,
    reasoningTextEnabled: Boolean,
    onReasoningModeChange: (KeyboardSettings.AgentReasoningMode) -> Unit,
    onReasoningTextEnabledChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val showReasoningTextSwitch = reasoningMode != KeyboardSettings.AgentReasoningMode.Off
    val providerHint = when (providerType) {
        KeyboardSettings.AgentProviderType.OpenRouter ->
            "OpenRouter supports the normalized reasoning object and can include or exclude returned reasoning text."
        KeyboardSettings.AgentProviderType.OpenAiCompatible ->
            "OpenAI-compatible servers vary. Effort values are sent manually as reasoning_effort only when selected."
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Reasoning",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Model capability cannot be detected reliably from every provider, so this stays manual.",
            color = ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )

        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        text = "Reasoning mode",
                        color = ZnKeyboardColors.Muted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = reasoningMode.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Text(
                    text = "Change",
                    color = ZnKeyboardColors.Accent,
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            FloatingPickerMenu(
                expanded = expanded,
                items = KeyboardSettings.AgentReasoningMode.entries.map { option ->
                    PickerItem(
                        id = option.id,
                        title = option.label,
                        subtitle = option.description,
                    )
                },
                onDismissRequest = { expanded = false },
                onItemSelected = { item ->
                    expanded = false
                    KeyboardSettings.AgentReasoningMode.entries.firstOrNull { it.id == item.id }
                        ?.let(onReasoningModeChange)
                },
                maxHeight = REASONING_PICKER_MAX_HEIGHT,
            )
        }

        Text(
            text = providerHint,
            color = ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )

        if (showReasoningTextSwitch) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Return reasoning text",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Off hides reasoning tokens when the provider supports hiding them.",
                        color = ZnKeyboardColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = reasoningTextEnabled,
                    onCheckedChange = onReasoningTextEnabledChange,
                )
            }
        }
    }
}

@Composable
private fun RowButtonsSection(
    title: String,
    keyIds: List<String>,
    maxKeys: Int,
    defaultKeyIds: List<String>,
    keyOptions: List<KeyboardSettings.UpperRowKeyOption>,
    labelForKey: (String) -> String,
    onKeyIdsChange: (List<String>) -> Unit,
) {
    Surface(
        color = ZnKeyboardColors.Surface,
        shape = RoundedCornerShape(SECTION_CORNER_RADIUS),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_keyboard_24),
                        contentDescription = null,
                        tint = ZnKeyboardColors.Accent,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = "${keyIds.size}/$maxKeys",
                    color = ZnKeyboardColors.Muted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            if (keyIds.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(COMPACT_ITEM_CORNER_RADIUS))
                        .background(ZnKeyboardColors.Key),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No keys",
                        color = ZnKeyboardColors.Muted,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            } else {
                var draggingKeyIndex by remember(keyIds) { mutableStateOf<Int?>(null) }
                var keyDropIndex by remember(keyIds) { mutableStateOf<Int?>(null) }
                val activeDraggingKeyIndex = draggingKeyIndex?.takeIf { it in keyIds.indices }
                val activeKeyDropIndex = keyDropIndex?.takeIf { it in keyIds.indices }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(reorderListHeight(SHORTCUT_KEY_ITEM_HEIGHT, SHORTCUT_KEY_ITEM_GAP, keyIds.size)),
                ) {
                    if (activeDraggingKeyIndex != null && activeKeyDropIndex != null) {
                        ReorderDropShadow(
                            index = activeKeyDropIndex,
                            label = labelForKey(keyIds[activeDraggingKeyIndex]),
                            height = SHORTCUT_KEY_ITEM_HEIGHT,
                            modifier = Modifier
                                .offset(y = reorderItemY(SHORTCUT_KEY_ITEM_HEIGHT, SHORTCUT_KEY_ITEM_GAP, activeKeyDropIndex))
                                .zIndex(0f),
                        )
                    }

                    keyIds.forEachIndexed { index, keyId ->
                        val visualIndex = reorderVisualIndex(index, activeDraggingKeyIndex, activeKeyDropIndex)
                        UpperRowKeyEditor(
                            index = index,
                            keyId = keyId,
                            keyCount = keyIds.size,
                            keyOptions = keyOptions,
                            labelForKey = labelForKey,
                            modifier = Modifier
                                .offset(y = reorderItemY(SHORTCUT_KEY_ITEM_HEIGHT, SHORTCUT_KEY_ITEM_GAP, visualIndex))
                                .zIndex(if (activeDraggingKeyIndex == index) 2f else 1f),
                            onDragStart = {
                                draggingKeyIndex = index
                                keyDropIndex = index
                            },
                            onDragTargetChange = { targetIndex ->
                                keyDropIndex = targetIndex
                            },
                            onDragFinish = {
                                draggingKeyIndex = null
                                keyDropIndex = null
                            },
                            onKeyChange = { nextKeyId ->
                                onKeyIdsChange(
                                    keyIds.toMutableList().apply {
                                        set(index, nextKeyId)
                                    },
                                )
                            },
                            onMove = { fromIndex, toIndex ->
                                onKeyIdsChange(keyIds.moveItem(fromIndex, toIndex))
                            },
                            onRemove = {
                                onKeyIdsChange(
                                    keyIds.toMutableList().apply {
                                        removeAt(index)
                                    },
                                )
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        onKeyIdsChange(keyIds + nextRowButtonId(keyIds, defaultKeyIds, keyOptions))
                    },
                    enabled = keyIds.size < maxKeys,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add_24),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Add")
                }

                OutlinedButton(
                    onClick = { onKeyIdsChange(defaultKeyIds) },
                    enabled = keyIds != defaultKeyIds,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
                ) {
                    Text("Reset")
                }
            }
        }
    }
}

@Composable
private fun UpperRowKeyEditor(
    index: Int,
    keyId: String,
    keyCount: Int,
    keyOptions: List<KeyboardSettings.UpperRowKeyOption>,
    labelForKey: (String) -> String,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit,
    onDragTargetChange: (Int) -> Unit,
    onDragFinish: () -> Unit,
    onKeyChange: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember(index, keyId) { mutableStateOf(false) }
    val selectedLabel = labelForKey(keyId)
    val currentIndex by rememberUpdatedState(index)
    val density = LocalDensity.current
    val itemDistancePx = with(density) { (SHORTCUT_KEY_ITEM_HEIGHT + SHORTCUT_KEY_ITEM_GAP).toPx() }
    var dragging by remember(index, keyId) { mutableStateOf(false) }
    var dragOffset by remember(index, keyId) { mutableFloatStateOf(0f) }

    Surface(
        color = if (dragging) ZnKeyboardColors.FunctionKey else Color.Transparent,
        shape = RoundedCornerShape(COMPACT_ITEM_CORNER_RADIUS),
        tonalElevation = 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(SHORTCUT_KEY_ITEM_HEIGHT)
            .offset { IntOffset(0, dragOffset.roundToInt()) }
            .pointerInput(index, keyId, keyCount, itemDistancePx) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dragging = true
                        onDragStart()
                    },
                    onDragCancel = {
                        dragging = false
                        dragOffset = 0f
                        onDragFinish()
                    },
                    onDragEnd = {
                        val targetIndex = reorderTargetIndex(currentIndex, dragOffset, itemDistancePx, keyCount)
                        dragging = false
                        dragOffset = 0f
                        onDragFinish()
                        if (targetIndex != currentIndex) {
                            onMove(currentIndex, targetIndex)
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffset = (dragOffset + dragAmount.y)
                            .coerceIn(
                                -currentIndex * itemDistancePx,
                                (keyCount - 1 - currentIndex) * itemDistancePx,
                            )
                        onDragTargetChange(reorderTargetIndex(currentIndex, dragOffset, itemDistancePx, keyCount))
                    },
                )
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${index + 1}",
                color = ZnKeyboardColors.Muted,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(24.dp),
            )

            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
                ) {
                    Text(
                        text = selectedLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                FloatingPickerMenu(
                    expanded = expanded,
                    items = keyOptions.map { option ->
                        PickerItem(
                            id = option.id,
                            title = option.label,
                        )
                    },
                    onDismissRequest = { expanded = false },
                    onItemSelected = { item ->
                        expanded = false
                        onKeyChange(item.id)
                    },
                )
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete_24),
                    contentDescription = "Remove key",
                    tint = ZnKeyboardColors.Muted,
                )
            }
        }
    }
}

@Composable
private fun KeyboardRowOrderSection(
    rowOrder: List<String>,
    onRowOrderChange: (List<String>) -> Unit,
) {
    val normalizedOrder = KeyboardSettings.normalizeKeyboardRowOrder(rowOrder)

    Surface(
        color = ZnKeyboardColors.Surface,
        shape = RoundedCornerShape(SECTION_CORNER_RADIUS),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_tune_24),
                        contentDescription = null,
                        tint = ZnKeyboardColors.Accent,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Shortcut row order",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = "${normalizedOrder.size}",
                    color = ZnKeyboardColors.Muted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            var draggingRowIndex by remember(normalizedOrder) { mutableStateOf<Int?>(null) }
            var rowDropIndex by remember(normalizedOrder) { mutableStateOf<Int?>(null) }
            val activeDraggingRowIndex = draggingRowIndex?.takeIf { it in normalizedOrder.indices }
            val activeRowDropIndex = rowDropIndex?.takeIf { it in normalizedOrder.indices }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(reorderListHeight(ROW_ORDER_ITEM_HEIGHT, ROW_ORDER_ITEM_GAP, normalizedOrder.size)),
            ) {
                if (activeDraggingRowIndex != null && activeRowDropIndex != null) {
                    ReorderDropShadow(
                        index = activeRowDropIndex,
                        label = KeyboardSettings.labelForKeyboardRow(normalizedOrder[activeDraggingRowIndex]),
                        height = ROW_ORDER_ITEM_HEIGHT,
                        modifier = Modifier
                            .offset(y = reorderItemY(ROW_ORDER_ITEM_HEIGHT, ROW_ORDER_ITEM_GAP, activeRowDropIndex))
                            .zIndex(0f),
                    )
                }

                normalizedOrder.forEachIndexed { index, rowId ->
                    val visualIndex = reorderVisualIndex(index, activeDraggingRowIndex, activeRowDropIndex)
                    KeyboardRowOrderItem(
                        index = index,
                        rowId = rowId,
                        rowCount = normalizedOrder.size,
                        modifier = Modifier
                            .offset(y = reorderItemY(ROW_ORDER_ITEM_HEIGHT, ROW_ORDER_ITEM_GAP, visualIndex))
                            .zIndex(if (activeDraggingRowIndex == index) 2f else 1f),
                        onDragStart = {
                            draggingRowIndex = index
                            rowDropIndex = index
                        },
                        onDragTargetChange = { targetIndex ->
                            rowDropIndex = targetIndex
                        },
                        onDragFinish = {
                            draggingRowIndex = null
                            rowDropIndex = null
                        },
                        onMove = { fromIndex, toIndex ->
                            onRowOrderChange(normalizedOrder.moveItem(fromIndex, toIndex))
                        },
                    )
                }
            }

            OutlinedButton(
                onClick = { onRowOrderChange(KeyboardSettings.DEFAULT_KEYBOARD_ROW_ORDER) },
                enabled = normalizedOrder != KeyboardSettings.DEFAULT_KEYBOARD_ROW_ORDER,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
            ) {
                Text("Reset")
            }
        }
    }
}

@Composable
private fun KeyboardRowOrderItem(
    index: Int,
    rowId: String,
    rowCount: Int,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit,
    onDragTargetChange: (Int) -> Unit,
    onDragFinish: () -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val currentIndex by rememberUpdatedState(index)
    val density = LocalDensity.current
    val itemDistancePx = with(density) { (ROW_ORDER_ITEM_HEIGHT + ROW_ORDER_ITEM_GAP).toPx() }
    var dragging by remember(rowId) { mutableStateOf(false) }
    var dragOffset by remember(rowId) { mutableFloatStateOf(0f) }

    Surface(
        color = if (dragging) ZnKeyboardColors.FunctionKey else ZnKeyboardColors.Key,
        shape = RoundedCornerShape(COMPACT_ITEM_CORNER_RADIUS),
        tonalElevation = 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(ROW_ORDER_ITEM_HEIGHT)
            .offset { IntOffset(0, dragOffset.roundToInt()) }
            .pointerInput(rowId, rowCount, itemDistancePx) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dragging = true
                        onDragStart()
                    },
                    onDragCancel = {
                        dragging = false
                        dragOffset = 0f
                        onDragFinish()
                    },
                    onDragEnd = {
                        val targetIndex = reorderTargetIndex(currentIndex, dragOffset, itemDistancePx, rowCount)
                        dragging = false
                        dragOffset = 0f
                        onDragFinish()
                        if (targetIndex != currentIndex) {
                            onMove(currentIndex, targetIndex)
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffset = (dragOffset + dragAmount.y)
                            .coerceIn(
                                -currentIndex * itemDistancePx,
                                (rowCount - 1 - currentIndex) * itemDistancePx,
                            )
                        onDragTargetChange(reorderTargetIndex(currentIndex, dragOffset, itemDistancePx, rowCount))
                    },
                )
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${index + 1}",
                color = ZnKeyboardColors.Muted,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(24.dp),
            )
            Text(
                text = KeyboardSettings.labelForKeyboardRow(rowId),
                color = ZnKeyboardColors.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ReorderDropShadow(
    index: Int,
    label: String,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = ZnKeyboardColors.Accent.copy(alpha = 0.18f),
        shape = RoundedCornerShape(COMPACT_ITEM_CORNER_RADIUS),
        tonalElevation = 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${index + 1}",
                color = ZnKeyboardColors.Accent,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(24.dp),
            )
            Text(
                text = label,
                color = ZnKeyboardColors.OnSurface.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SystemSetupActions() {
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_keyboard_24),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Enable")
        }

        Button(
            onClick = {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            },
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_keyboard_24),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Choose")
        }
    }
}

private fun nextRowButtonId(
    currentKeyIds: List<String>,
    preferredKeyIds: List<String>,
    keyOptions: List<KeyboardSettings.UpperRowKeyOption>,
): String {
    return preferredKeyIds.firstOrNull { it !in currentKeyIds }
        ?: keyOptions.firstOrNull { it.id !in currentKeyIds }?.id
        ?: keyOptions.first().id
}

private fun reorderListHeight(itemHeight: Dp, itemGap: Dp, itemCount: Int): Dp {
    return itemHeight * itemCount.toFloat() + itemGap * (itemCount - 1).coerceAtLeast(0).toFloat()
}

private fun reorderItemY(itemHeight: Dp, itemGap: Dp, index: Int): Dp {
    return (itemHeight + itemGap) * index.toFloat()
}

private fun reorderTargetIndex(
    currentIndex: Int,
    dragOffset: Float,
    itemDistancePx: Float,
    itemCount: Int,
): Int {
    return (currentIndex + (dragOffset / itemDistancePx).roundToInt()).coerceIn(0, itemCount - 1)
}

private fun reorderVisualIndex(
    index: Int,
    draggingIndex: Int?,
    targetIndex: Int?,
): Int {
    if (draggingIndex == null || targetIndex == null || draggingIndex == targetIndex) return index

    return when {
        index == draggingIndex -> index
        draggingIndex < targetIndex && index in (draggingIndex + 1)..targetIndex -> index - 1
        draggingIndex > targetIndex && index in targetIndex until draggingIndex -> index + 1
        else -> index
    }
}

private fun <T> List<T>.moveItem(fromIndex: Int, toIndex: Int): List<T> {
    if (fromIndex !in indices || toIndex !in indices || fromIndex == toIndex) return this
    return toMutableList().apply {
        val item = removeAt(fromIndex)
        add(toIndex, item)
    }
}

private fun fetchModelOptions(
    baseUrl: String,
    apiKey: String,
): Result<List<ModelOption>> {
    return runCatching {
        val endpoint = buildModelsUrl(baseUrl)
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = MODEL_LOAD_TIMEOUT_MS
            readTimeout = MODEL_LOAD_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
        }

        try {
            val responseCode = connection.responseCode
            val responseText = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            if (responseCode !in 200..299) {
                throw IOException("Could not load models ($responseCode). You can still type a custom model ID.")
            }

            val data = JSONObject(responseText).getJSONArray("data")
            buildList {
                for (index in 0 until data.length()) {
                    val model = data.optJSONObject(index) ?: continue
                    val id = model.optString("id").trim()
                    if (id.isNotBlank()) {
                        add(
                            ModelOption(
                                id = id,
                                name = model.optString("name").trim().takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                }
            }.distinctBy { it.id }.sortedBy { it.id.lowercase(Locale.US) }
        } finally {
            connection.disconnect()
        }
    }
}

private fun buildModelsUrl(baseUrl: String): URL {
    val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
    val endpoint = URL("$normalizedBaseUrl/models")
    if (endpoint.protocol != "https") {
        throw IOException("Use an HTTPS endpoint to load models.")
    }
    return endpoint
}

private fun List<ModelOption>.filterForQuery(query: String): List<ModelOption> {
    val normalizedQuery = query.trim().lowercase(Locale.US)
    if (normalizedQuery.isBlank()) return this

    return filter { option ->
        option.id.lowercase(Locale.US).contains(normalizedQuery) ||
            option.name?.lowercase(Locale.US)?.contains(normalizedQuery) == true
    }
}

private fun writeSettingsBackup(context: Context, uri: Uri) {
    val output = context.contentResolver.openOutputStream(uri)
        ?: throw IOException("Could not open export file.")
    output.bufferedWriter(Charsets.UTF_8).use { writer ->
        writer.write(KeyboardSettings.createBackupJson(context))
    }
}

private fun restoreSettingsBackup(
    context: Context,
    uri: Uri,
): KeyboardSettings.BackupRestoreResult {
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IOException("Could not open import file.")
    val backupJson = input.bufferedReader(Charsets.UTF_8).use { reader ->
        reader.readText()
    }
    return KeyboardSettings.restoreBackupJson(context, backupJson)
}

private fun suggestedBackupFileName(): String {
    return "znkeyboard-backup-${BACKUP_FILE_TIMESTAMP_FORMAT.format(LocalDateTime.now())}.json"
}

private data class ModelOption(
    val id: String,
    val name: String?,
)

private data class PickerItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
)

private const val MODEL_LOAD_DEBOUNCE_MS = SettingsUiTimings.MODEL_LOAD_DEBOUNCE_MS
private const val MODEL_QUERY_DEBOUNCE_MS = SettingsUiTimings.MODEL_QUERY_DEBOUNCE_MS
private const val MODEL_LOAD_TIMEOUT_MS = SettingsUiTimings.MODEL_LOAD_TIMEOUT_MS
private val BACKUP_FILE_TIMESTAMP_FORMAT = SettingsBackupDefaults.FILE_TIMESTAMP_FORMAT
private val BACKUP_IMPORT_MIME_TYPES = SettingsBackupDefaults.IMPORT_MIME_TYPES
private val PICKER_MENU_WIDTH = SettingsUiDimensions.PICKER_MENU_WIDTH
private val PICKER_MAX_HEIGHT = SettingsUiDimensions.PICKER_MAX_HEIGHT
private val PROVIDER_PICKER_MAX_HEIGHT = SettingsUiDimensions.PROVIDER_PICKER_MAX_HEIGHT
private val REASONING_PICKER_MAX_HEIGHT = SettingsUiDimensions.REASONING_PICKER_MAX_HEIGHT
private val SKIN_TONE_PICKER_MAX_HEIGHT = SettingsUiDimensions.SKIN_TONE_PICKER_MAX_HEIGHT
private val SHORTCUT_KEY_ITEM_HEIGHT = SettingsUiDimensions.SHORTCUT_KEY_ITEM_HEIGHT
private val SHORTCUT_KEY_ITEM_GAP = SettingsUiDimensions.SHORTCUT_KEY_ITEM_GAP
private val ROW_ORDER_ITEM_HEIGHT = SettingsUiDimensions.ROW_ORDER_ITEM_HEIGHT
private val ROW_ORDER_ITEM_GAP = SettingsUiDimensions.ROW_ORDER_ITEM_GAP
private val SECTION_CORNER_RADIUS = SettingsUiDimensions.SECTION_CORNER_RADIUS
private val COMPACT_ITEM_CORNER_RADIUS = SettingsUiDimensions.COMPACT_ITEM_CORNER_RADIUS
private val PICKER_CORNER_RADIUS = SettingsUiDimensions.PICKER_CORNER_RADIUS
private val TEXT_SNIPPET_DELETE_REVEAL_WIDTH = 96.dp
private const val TEXT_SNIPPET_DELETE_LOCK_THRESHOLD = 0.45f

@Composable
private fun ZnKeyboardTheme(content: @Composable () -> Unit) {
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

private object ZnKeyboardColors {
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
