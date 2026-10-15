package dev.zain.znkeyboard

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import dev.zain.znkeyboard.constants.LiquidNavDimensions
import dev.zain.znkeyboard.constants.SettingsBackupDefaults
import dev.zain.znkeyboard.constants.SettingsShellDimensions
import dev.zain.znkeyboard.constants.SettingsUiDimensions
import dev.zain.znkeyboard.constants.SettingsUiTimings
import dev.zain.znkeyboard.ime.ClipboardHistoryStore
import dev.zain.znkeyboard.liquidnav.LiquidNavBar
import dev.zain.znkeyboard.liquidnav.LiquidNavItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        // Content scrolls behind the floating pills, so no system scrim behind 3-button navigation either.
        window.isNavigationBarContrastEnforced = false

        setContent {
            ZnKeyboardTheme {
                val stateHolder = rememberSaveableStateHolder()
                var shortcutsOpen by rememberSaveable { mutableStateOf(false) }
                AnimatedContent(
                    targetState = shortcutsOpen,
                    transitionSpec = {
                        if (targetState) {
                            (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
                        } else {
                            (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
                        }
                    },
                    label = "settings-screen",
                ) { open ->
                    if (open) {
                        ShortcutsScreen(onBack = { shortcutsOpen = false })
                    } else {
                        stateHolder.SaveableStateProvider("settings") {
                            SettingsScreen(onOpenShortcuts = { shortcutsOpen = true })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onOpenShortcuts: () -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var heightScale by remember { mutableFloatStateOf(KeyboardSettings.readHeightScale(context)) }
    var bottomPaddingDp by remember { mutableStateOf(KeyboardSettings.readBottomPaddingDp(context)) }
    var functionKeyBackgroundsEnabled by remember {
        mutableStateOf(KeyboardSettings.readFunctionKeyBackgroundsEnabled(context))
    }
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
    var agentProfiles by remember { mutableStateOf(KeyboardSettings.readAgentProfiles(context)) }
    var activeAgentProfileId by remember { mutableStateOf(KeyboardSettings.readActiveAgentProfileId(context)) }
    var textSnippets by remember { mutableStateOf(KeyboardSettings.readTextSnippets(context)) }
    var clipboardHistoryCount by remember { mutableStateOf(ClipboardHistoryStore.read(context).size) }
    var backupPasswordDialogMode by remember { mutableStateOf<BackupPasswordDialogMode?>(null) }
    var pendingExportPassword by remember { mutableStateOf<String?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var selectedDestinationId by rememberSaveable { mutableStateOf(SettingsDestination.Typing.id) }
    val selectedDestination = SettingsDestination.entries.firstOrNull { it.id == selectedDestinationId }
        ?: SettingsDestination.Typing
    val destinationScrollState = rememberSaveable(selectedDestinationId, saver = ScrollState.Saver) { ScrollState(0) }
    val scope = rememberCoroutineScope()

    fun refreshSettingsFromStorage() {
        val nextProviderType = KeyboardSettings.readAgentProviderType(context)
        heightScale = KeyboardSettings.readHeightScale(context)
        bottomPaddingDp = KeyboardSettings.readBottomPaddingDp(context)
        functionKeyBackgroundsEnabled = KeyboardSettings.readFunctionKeyBackgroundsEnabled(context)
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
        agentProfiles = KeyboardSettings.readAgentProfiles(context)
        activeAgentProfileId = KeyboardSettings.readActiveAgentProfileId(context)
        textSnippets = KeyboardSettings.readTextSnippets(context)
        clipboardHistoryCount = ClipboardHistoryStore.read(context).size
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(SettingsBackupDefaults.MIME_TYPE),
    ) { uri ->
        val password = pendingExportPassword
        pendingExportPassword = null
        if (uri == null || password == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { writeEncryptedSettingsBackup(context, uri, password.toCharArray()) }
            }
            val message = result.fold(
                onSuccess = { "Encrypted backup exported" },
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
        pendingImportUri = uri
        backupPasswordDialogMode = BackupPasswordDialogMode.Import
    }

    when (backupPasswordDialogMode) {
        BackupPasswordDialogMode.Export -> BackupPasswordDialog(
            mode = BackupPasswordDialogMode.Export,
            onDismiss = { backupPasswordDialogMode = null },
            onConfirm = { password ->
                backupPasswordDialogMode = null
                pendingExportPassword = password
                exportLauncher.launch(suggestedBackupFileName())
            },
        )

        BackupPasswordDialogMode.Import -> BackupPasswordDialog(
            mode = BackupPasswordDialogMode.Import,
            onDismiss = {
                backupPasswordDialogMode = null
                pendingImportUri = null
            },
            onConfirm = { password ->
                val uri = pendingImportUri
                backupPasswordDialogMode = null
                pendingImportUri = null
                if (uri != null) {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { restoreEncryptedSettingsBackup(context, uri, password.toCharArray()) }
                        }
                        result
                            .onSuccess { restoreResult ->
                                refreshSettingsFromStorage()
                                Toast.makeText(
                                    context,
                                    "Backup restored (${restoreResult.snippetCount} snippets, ${restoreResult.clipboardEntryCount} clipboard items)",
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
            },
        )

        null -> Unit
    }

    DisposableEffect(context) {
        val listener = KeyboardSettings.registerTextSnippetsChangeListener(context) {
            textSnippets = KeyboardSettings.readTextSnippets(context)
        }
        onDispose {
            KeyboardSettings.unregisterTextSnippetsChangeListener(context, listener)
        }
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ZnKeyboardColors.Background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(destinationScrollState)
                .pointerInput(focusManager) {
                    detectTapGestures(onTap = { focusManager.clearFocus() })
                }
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                .padding(
                    start = SettingsShellDimensions.CONTENT_HORIZONTAL_PADDING,
                    end = SettingsShellDimensions.CONTENT_HORIZONTAL_PADDING,
                    top = SettingsShellDimensions.TITLE_PILL_TOP_GAP +
                        SettingsShellDimensions.TITLE_PILL_HEIGHT +
                        SettingsShellDimensions.CONTENT_CLEARANCE,
                    bottom = LiquidNavDimensions.BOTTOM_GAP +
                        LiquidNavDimensions.BAR_HEIGHT +
                        SettingsShellDimensions.CONTENT_CLEARANCE,
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (selectedDestination) {
                SettingsDestination.Typing -> {
                    SetupStatusCard()
                    TryTypingField()
                    AdaptiveTouchSection()
                    TypingFeelSection()
                }

                SettingsDestination.Keyboard -> {
                    SettingsSectionCard(verticalSpacing = 18.dp) {
                        SettingsSectionHeader(
                            title = "Look & size",
                            iconResId = R.drawable.ic_palette_24,
                        )

                        KeyboardThemeSelector()

                        LabeledSlider(
                            title = "Key height",
                            valueLabel = "${(heightScale * 100f).roundToInt()}%",
                            value = heightScale,
                            valueRange = KeyboardSettings.MIN_HEIGHT_SCALE..KeyboardSettings.MAX_HEIGHT_SCALE,
                            steps = 6,
                            onValueChange = {
                                heightScale = it
                                KeyboardSettings.saveHeightScale(context, it)
                            },
                            defaultLabel = "${(KeyboardSettings.DEFAULT_HEIGHT_SCALE * 100f).roundToInt()}%",
                            isDefault = (heightScale * 100f).roundToInt() == (KeyboardSettings.DEFAULT_HEIGHT_SCALE * 100f).roundToInt(),
                            onReset = {
                                heightScale = KeyboardSettings.DEFAULT_HEIGHT_SCALE
                                KeyboardSettings.saveHeightScale(context, KeyboardSettings.DEFAULT_HEIGHT_SCALE)
                            },
                        )

                        LabeledSlider(
                            title = "Space below keys",
                            valueLabel = "$bottomPaddingDp dp",
                            value = bottomPaddingDp.toFloat(),
                            valueRange = KeyboardSettings.MIN_BOTTOM_PADDING_DP.toFloat()..KeyboardSettings.MAX_BOTTOM_PADDING_DP.toFloat(),
                            steps = (KeyboardSettings.MAX_BOTTOM_PADDING_DP - KeyboardSettings.MIN_BOTTOM_PADDING_DP) / 4 - 1,
                            onValueChange = {
                                val value = KeyboardSettings.normalizeBottomPaddingDp((it / 4f).roundToInt() * 4)
                                bottomPaddingDp = value
                                KeyboardSettings.saveBottomPaddingDp(context, value)
                            },
                            defaultLabel = "${KeyboardSettings.DEFAULT_BOTTOM_PADDING_DP} dp",
                            isDefault = bottomPaddingDp == KeyboardSettings.DEFAULT_BOTTOM_PADDING_DP,
                            onReset = {
                                bottomPaddingDp = KeyboardSettings.DEFAULT_BOTTOM_PADDING_DP
                                KeyboardSettings.saveBottomPaddingDp(context, KeyboardSettings.DEFAULT_BOTTOM_PADDING_DP)
                            },
                        )

                        SettingsSwitchRow(
                            title = "Modifier key backgrounds",
                            description = "Fill Shift, Delete, 123 and emoji keys. Turn off for a lighter look.",
                            checked = functionKeyBackgroundsEnabled,
                            onCheckedChange = {
                                functionKeyBackgroundsEnabled = it
                                KeyboardSettings.saveFunctionKeyBackgroundsEnabled(context, it)
                            },
                        )
                    }

                    ShortcutsSummaryCard(onOpen = onOpenShortcuts)
                }

                SettingsDestination.Tools -> {
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
                        agentProfiles = agentProfiles,
                        activeAgentProfileId = activeAgentProfileId,
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
                        onCreateAgentProfile = { name ->
                            val created = KeyboardSettings.createAgentProfile(context, name)
                            agentProfiles = KeyboardSettings.readAgentProfiles(context)
                            activeAgentProfileId = KeyboardSettings.readActiveAgentProfileId(context)
                            created != null
                        },
                        onUpdateAgentProfile = {
                            KeyboardSettings.updateAgentProfileFromCurrent(context, activeAgentProfileId)
                            agentProfiles = KeyboardSettings.readAgentProfiles(context)
                            activeAgentProfileId = KeyboardSettings.readActiveAgentProfileId(context)
                        },
                        onActivateAgentProfile = { profileId ->
                            KeyboardSettings.activateAgentProfile(context, profileId)
                            refreshSettingsFromStorage()
                        },
                        onDeleteAgentProfile = { profileId ->
                            KeyboardSettings.deleteAgentProfile(context, profileId)
                            agentProfiles = KeyboardSettings.readAgentProfiles(context)
                            activeAgentProfileId = KeyboardSettings.readActiveAgentProfileId(context)
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
                }

                SettingsDestination.Saved -> {
                    TextSnippetsSection(
                        snippets = textSnippets,
                        onSnippetsChange = ::updateTextSnippets,
                    )

                    ClipboardHistorySettingsSection(
                        entryCount = clipboardHistoryCount,
                        onClearConfirmed = {
                            ClipboardHistoryStore.clear(context)
                            clipboardHistoryCount = 0
                            Toast.makeText(context, "Clipboard history cleared", Toast.LENGTH_SHORT).show()
                        },
                    )
                }

                SettingsDestination.Backup -> {
                    BackupRestoreSection(
                        onExport = { backupPasswordDialogMode = BackupPasswordDialogMode.Export },
                        onImport = { importLauncher.launch(BACKUP_IMPORT_MIME_TYPES) },
                    )
                }
            }
        }

        SettingsTitlePill(
            title = selectedDestination.title,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(
                    WindowInsets.statusBars.union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .padding(
                    start = SettingsShellDimensions.TITLE_PILL_START_MARGIN,
                    top = SettingsShellDimensions.TITLE_PILL_TOP_GAP,
                ),
        )

        LiquidNavBar(
            items = settingsNavItems,
            selectedIndex = selectedDestination.ordinal,
            onSelect = { index ->
                focusManager.clearFocus()
                selectedDestinationId = SettingsDestination.entries[index].id
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(
                    WindowInsets.navigationBars.union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                )
                .padding(
                    start = LiquidNavDimensions.SIDE_MARGIN,
                    end = LiquidNavDimensions.SIDE_MARGIN,
                    bottom = LiquidNavDimensions.BOTTOM_GAP,
                ),
        )
    }
}

@Composable
private fun SettingsTitlePill(title: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(SettingsShellDimensions.TITLE_PILL_BORDER_WIDTH, MaterialTheme.colorScheme.outlineVariant),
    ) {
        AnimatedContent(
            targetState = title,
            transitionSpec = {
                (fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) + slideInVertically { it / 3 }) togetherWith
                    fadeOut(spring(stiffness = Spring.StiffnessMedium)) using
                    SizeTransform(clip = false) { _, _ ->
                        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
                    }
            },
            contentAlignment = Alignment.CenterStart,
            label = "settings-title",
        ) { text ->
            Box(
                modifier = Modifier
                    .height(SettingsShellDimensions.TITLE_PILL_HEIGHT)
                    .padding(horizontal = SettingsShellDimensions.TITLE_PILL_HORIZONTAL_PADDING)
                    .semantics { heading() },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleLarge,
                    color = ZnKeyboardColors.OnSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

private val settingsNavItems = SettingsDestination.entries.map { LiquidNavItem(it.label, it.iconResId) }

@Composable
private fun TextSnippetsSection(
    snippets: List<KeyboardSettings.TextSnippet>,
    onSnippetsChange: (List<KeyboardSettings.TextSnippet>) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var confirmingClearUntagged by remember { mutableStateOf(false) }
    var lastClearedUntaggedSnippetCount by remember { mutableStateOf(0) }
    var observedSnippetCount by remember { mutableStateOf(snippets.size) }
    val untaggedSnippetCount = snippets.count { it.tags.isEmpty() }
    val snippetsWereAdded = snippets.size > observedSnippetCount
    val clearUntaggedButtonText = if (
        lastClearedUntaggedSnippetCount > 0 &&
        untaggedSnippetCount == 0 &&
        !snippetsWereAdded
    ) {
        "Untagged deleted ($lastClearedUntaggedSnippetCount)"
    } else {
        "Clear untagged ($untaggedSnippetCount)"
    }
    val normalizedDraft = KeyboardSettings.textSnippetFromText(
        text = draft,
    )
    val canAddSnippet = normalizedDraft.text.isNotBlank() &&
        snippets.none { it.text == normalizedDraft.text } &&
        snippets.size < KeyboardSettings.MAX_TEXT_SNIPPETS

    LaunchedEffect(snippets.size) {
        if (snippets.size > observedSnippetCount) {
            lastClearedUntaggedSnippetCount = 0
        }
        observedSnippetCount = snippets.size
    }

    SettingsSectionCard {
        SettingsSectionHeader(
            title = "Text snippets",
            subtitle = "Long-press space or use a Save snippet key. Snippets also autocomplete as you type.",
            trailingText = "${snippets.size}/${KeyboardSettings.MAX_TEXT_SNIPPETS}",
        )

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
                lastClearedUntaggedSnippetCount = 0
                onSnippetsChange(snippets + normalizedDraft)
                draft = ""
            },
            enabled = canAddSnippet,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add_24),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Add")
        }

        OutlinedButton(
            onClick = { confirmingClearUntagged = true },
            enabled = untaggedSnippetCount > 0,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.DeleteContent),
            border = BorderStroke(1.dp, ZnKeyboardColors.DeleteContent.copy(alpha = 0.55f)),
        ) {
            SettingsIconTextButtonContent(
                iconResId = R.drawable.ic_delete_24,
                text = clearUntaggedButtonText,
            )
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
                    key(snippet.text) {
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

    if (confirmingClearUntagged) {
        ClearUntaggedTextSnippetsDialog(
            untaggedSnippetCount = untaggedSnippetCount,
            onDismiss = { confirmingClearUntagged = false },
            onConfirm = {
                lastClearedUntaggedSnippetCount = untaggedSnippetCount
                confirmingClearUntagged = false
                onSnippetsChange(snippets.filter { it.tags.isNotEmpty() })
            },
        )
    }
}

@Composable
private fun ClearUntaggedTextSnippetsDialog(
    untaggedSnippetCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var confirmationText by remember { mutableStateOf("") }
    val confirmationMatches = confirmationText == CLEAR_UNTAGGED_SNIPPETS_CONFIRMATION
    val plural = if (untaggedSnippetCount == 1) "" else "s"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear untagged snippets?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "This removes $untaggedSnippetCount snippet$plural with no tags. Tagged snippets will stay.",
                )
                OutlinedTextField(
                    value = confirmationText,
                    onValueChange = { confirmationText = it.take(CLEAR_UNTAGGED_SNIPPETS_CONFIRMATION.length) },
                    label = { Text("Type CONFIRM") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (confirmationMatches && untaggedSnippetCount > 0) {
                                onConfirm()
                            }
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = confirmationMatches && untaggedSnippetCount > 0,
            ) {
                Text(
                    text = "Clear",
                    color = if (confirmationMatches && untaggedSnippetCount > 0) {
                        ZnKeyboardColors.DeleteContent
                    } else {
                        ZnKeyboardColors.Muted
                    },
                )
            }
        },
    )
}

@Composable
private fun ClipboardHistorySettingsSection(
    entryCount: Int,
    onClearConfirmed: () -> Unit,
) {
    var confirmingClear by remember { mutableStateOf(false) }

    SettingsSectionCard {
        SettingsSectionHeader(
            title = "Clipboard history",
            iconResId = R.drawable.ic_history_24,
            trailingText = "$entryCount/${ClipboardHistoryStore.MAX_HISTORY}",
        )

        Text(
            text = "Clipboard history is available from the spacebar long-press saved text panel. Clearing it requires confirmation here.",
            color = ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedButton(
            onClick = { confirmingClear = true },
            enabled = entryCount > 0,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.DeleteContent),
            border = BorderStroke(1.dp, ZnKeyboardColors.DeleteContent.copy(alpha = 0.55f)),
        ) {
            SettingsIconTextButtonContent(R.drawable.ic_delete_24, "Clear clipboard history")
        }
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("Clear clipboard history?") },
            text = {
                Text("This removes $entryCount saved clipboard item${if (entryCount == 1) "" else "s"} from this keyboard.")
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingClear = false
                        onClearConfirmed()
                    },
                ) {
                    Text(
                        text = "Clear",
                        color = ZnKeyboardColors.DeleteContent,
                    )
                }
            },
        )
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
            .size(26.dp)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_add_24),
                contentDescription = "Add tag",
                tint = ZnKeyboardColors.Muted,
                modifier = Modifier.size(14.dp),
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
                            text = "tag tag",
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
    SettingsSectionCard {
        SettingsSectionHeader(
            title = "Backup & restore",
            iconResId = R.drawable.ic_download_24,
            trailingText = "Encrypted",
        )

        Text(
            text = "Password-encrypted backups include settings, clipboard history, learned typing, and configured API keys.",
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
                SettingsIconTextButtonContent(R.drawable.ic_download_24, "Export")
            }

            OutlinedButton(
                onClick = onImport,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
            ) {
                SettingsIconTextButtonContent(R.drawable.ic_upload_24, "Import")
            }
        }
    }
}

private enum class SettingsDestination(
    val id: String,
    val label: String,
    val title: String,
    val iconResId: Int,
) {
    Typing("typing", "Typing", "Typing", R.drawable.ic_touch_24),
    Keyboard("keyboard", "Layout", "Layout & rows", R.drawable.ic_keyboard_24),
    Tools("tools", "Tools", "Tools", R.drawable.ic_tune_24),
    Saved("saved", "Snippets", "Snippets & clipboard", R.drawable.ic_save_24),
    Backup("backup", "Backup", "Backup", R.drawable.ic_download_24),
}

private enum class BackupPasswordDialogMode {
    Export,
    Import,
}

@Composable
private fun BackupPasswordDialog(
    mode: BackupPasswordDialogMode,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val isExport = mode == BackupPasswordDialogMode.Export
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val validationMessage = when {
        password.length < EncryptedBackupCodec.MIN_PASSWORD_LENGTH -> {
            "Use at least ${EncryptedBackupCodec.MIN_PASSWORD_LENGTH} characters."
        }

        isExport && password != confirmation -> "Passwords do not match."
        else -> null
    }

    fun dismiss() {
        password = ""
        confirmation = ""
        onDismiss()
    }

    fun submit() {
        if (validationMessage != null) return
        val submittedPassword = password
        password = ""
        confirmation = ""
        onConfirm(submittedPassword)
    }

    AlertDialog(
        onDismissRequest = ::dismiss,
        title = {
            Text(if (isExport) "Encrypt backup" else "Unlock backup")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = if (isExport) {
                        "This backup includes clipboard history and configured API keys. Enter a new password for this export."
                    } else {
                        "Enter the password used to encrypt this backup."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Backup password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (isExport) {
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Confirm password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
                Text(
                    text = "This password is not saved and cannot be recovered.",
                    color = ZnKeyboardColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                validationMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = ::submit,
                enabled = validationMessage == null,
            ) {
                Text(if (isExport) "Export" else "Restore")
            }
        },
        dismissButton = {
            TextButton(onClick = ::dismiss) {
                Text("Cancel")
            }
        },
    )
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
    SettingsSectionCard {
        SettingsSectionHeader(
            title = "GIF search",
            subtitle = "Uses KLIPY for searchable GIFs and meme-friendly results.",
            trailingText = if (gifAppKey.isBlank()) "Setup" else "Ready",
            trailingColor = if (gifAppKey.isBlank()) ZnKeyboardColors.Muted else ZnKeyboardColors.Accent,
        )

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

        LockableSecretOutlinedTextField(
            value = gifAppKey,
            onValueChange = onGifAppKeyChange,
            label = "KLIPY app key",
            locked = gifAppKeyLocked,
            onLockedChange = onGifAppKeyLockedChange,
            emptySupportingText = "Create a free key in the KLIPY partner panel.",
            lockContentDescription = "Lock KLIPY app key",
            unlockContentDescription = "Unlock KLIPY app key",
        )
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

    SettingsSectionCard(verticalSpacing = 12.dp) {
        SettingsSectionHeader(title = "Emoji")

        Box(modifier = Modifier.fillMaxWidth()) {
            SettingsPickerLauncherButton(
                label = "Default tone",
                value = skinTone.label,
                onClick = { expanded = true },
                leadingContent = {
                    Text(
                        text = skinTone.sample,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.width(12.dp))
                },
            )

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
    agentProfiles: List<KeyboardSettings.AgentProfile>,
    activeAgentProfileId: String,
    onAgentModeEnabledChange: (Boolean) -> Unit,
    onAgentProviderTypeChange: (KeyboardSettings.AgentProviderType) -> Unit,
    onAgentApiBaseUrlChange: (String) -> Unit,
    onAgentModelChange: (String) -> Unit,
    onOpenRouterProviderSlugChange: (String) -> Unit,
    onAgentReasoningModeChange: (KeyboardSettings.AgentReasoningMode) -> Unit,
    onAgentReasoningTextEnabledChange: (Boolean) -> Unit,
    onAgentApiKeyChange: (String) -> Unit,
    onAgentApiKeyLockedChange: (Boolean) -> Unit,
    onCreateAgentProfile: (String) -> Boolean,
    onUpdateAgentProfile: () -> Unit,
    onActivateAgentProfile: (String) -> Unit,
    onDeleteAgentProfile: (String) -> Unit,
) {
    SettingsSectionCard {
        SettingsSectionHeader(
            title = "Rewrite",
            subtitle = "Controls the Rewrite button in the tools row.",
            trailingContent = {
                Switch(
                    checked = agentModeEnabled,
                    onCheckedChange = onAgentModeEnabledChange,
                )
            },
        )

        Text(
            text = "Text is sent directly from this keyboard to your configured provider. Rewrite is disabled in password fields.",
            color = ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )

        AgentProfiles(
            profiles = agentProfiles,
            activeProfileId = activeAgentProfileId,
            onCreateProfile = onCreateAgentProfile,
            onUpdateProfile = onUpdateAgentProfile,
            onActivateProfile = onActivateAgentProfile,
            onDeleteProfile = onDeleteAgentProfile,
        )

        ProviderTypeSelector(
            providerType = agentProviderType,
            onProviderTypeChange = onAgentProviderTypeChange,
        )

        if (agentProviderType != KeyboardSettings.AgentProviderType.OpenRouter) {
            OutlinedTextField(
                value = agentApiBaseUrl,
                onValueChange = onAgentApiBaseUrlChange,
                label = { Text("API base URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            if (agentProviderType == KeyboardSettings.AgentProviderType.NanbeigeLlamaCpp) {
                Text(
                    text = "For the Nanbeige llama.cpp fork. Uses its compatible structured-output request format.",
                    color = ZnKeyboardColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            Text(
                text = "OpenRouter endpoint is managed automatically. Pick a model first, then optionally pin a provider for that model.",
                color = ZnKeyboardColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        ModelSelectorField(
            providerType = agentProviderType,
            apiBaseUrl = agentApiBaseUrl,
            apiKey = agentApiKey,
            value = agentModel,
            onValueChange = onAgentModelChange,
        )

        if (agentProviderType == KeyboardSettings.AgentProviderType.OpenRouter) {
            OpenRouterProviderSelector(
                modelId = agentModel,
                apiKey = agentApiKey,
                value = openRouterProviderSlug,
                onValueChange = onOpenRouterProviderSlugChange,
            )
        }

        ReasoningControls(
            providerType = agentProviderType,
            reasoningMode = agentReasoningMode,
            reasoningTextEnabled = agentReasoningTextEnabled,
            onReasoningModeChange = onAgentReasoningModeChange,
            onReasoningTextEnabledChange = onAgentReasoningTextEnabledChange,
        )

        LockableSecretOutlinedTextField(
            value = agentApiKey,
            onValueChange = onAgentApiKeyChange,
            label = "API key",
            locked = agentApiKeyLocked,
            onLockedChange = onAgentApiKeyLockedChange,
            emptySupportingText = "Enter your provider's API key.",
            lockContentDescription = "Lock API key",
            unlockContentDescription = "Unlock API key",
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentProfiles(
    profiles: List<KeyboardSettings.AgentProfile>,
    activeProfileId: String,
    onCreateProfile: (String) -> Boolean,
    onUpdateProfile: () -> Unit,
    onActivateProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
) {
    var showingCreateDialog by remember { mutableStateOf(false) }
    var pendingDeleteProfileId by remember { mutableStateOf<String?>(null) }
    val activeProfile = profiles.firstOrNull { it.id == activeProfileId }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Profiles",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${profiles.size}/${KeyboardSettings.MAX_AGENT_PROFILES}",
                color = ZnKeyboardColors.Muted,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        if (profiles.isEmpty()) {
            Text(
                text = "Save this configuration to switch providers and models with one tap.",
                color = ZnKeyboardColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                profiles.forEach { profile ->
                    InputChip(
                        selected = profile.id == activeProfileId,
                        onClick = { onActivateProfile(profile.id) },
                        label = {
                            Text(
                                text = profile.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Button(
                onClick = { showingCreateDialog = true },
                enabled = profiles.size < KeyboardSettings.MAX_AGENT_PROFILES,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add_24),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Save new")
            }

            OutlinedButton(
                onClick = onUpdateProfile,
                enabled = activeProfile != null,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
            ) {
                Text("Update")
            }

            TextButton(
                onClick = { pendingDeleteProfileId = activeProfile?.id },
                enabled = activeProfile != null,
            ) {
                Text(
                    text = "Delete",
                    color = if (activeProfile != null) {
                        ZnKeyboardColors.DeleteContent
                    } else {
                        ZnKeyboardColors.Muted
                    },
                )
            }
        }

        activeProfile?.let { profile ->
            Text(
                text = "${profile.providerType.label} · ${profile.model}",
                color = ZnKeyboardColors.Muted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (showingCreateDialog) {
        var name by remember { mutableStateOf("") }
        var saveFailed by remember { mutableStateOf(false) }
        val normalizedName = KeyboardSettings.normalizeAgentProfileName(name)
        val duplicateName = profiles.any { it.name.equals(normalizedName, ignoreCase = true) }
        val canSave = normalizedName.isNotBlank() && !duplicateName

        AlertDialog(
            onDismissRequest = { showingCreateDialog = false },
            title = { Text("Save LLM profile") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(KeyboardSettings.MAX_AGENT_PROFILE_NAME_CHARS)
                        saveFailed = false
                    },
                    label = { Text("Profile name") },
                    supportingText = {
                        val message = when {
                            duplicateName -> "A profile with this name already exists."
                            saveFailed -> "Could not save this profile."
                            else -> "Examples: Nanbeige, OpenRouter, Work"
                        }
                        Text(message)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (canSave) {
                                if (onCreateProfile(normalizedName)) {
                                    showingCreateDialog = false
                                } else {
                                    saveFailed = true
                                }
                            }
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            dismissButton = {
                TextButton(onClick = { showingCreateDialog = false }) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (onCreateProfile(normalizedName)) {
                            showingCreateDialog = false
                        } else {
                            saveFailed = true
                        }
                    },
                    enabled = canSave,
                ) {
                    Text("Save")
                }
            },
        )
    }

    pendingDeleteProfileId?.let { profileId ->
        val profile = profiles.firstOrNull { it.id == profileId }
        if (profile != null) {
            AlertDialog(
                onDismissRequest = { pendingDeleteProfileId = null },
                title = { Text("Delete ${profile.name}?") },
                text = { Text("This removes the saved profile and its stored API key.") },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteProfileId = null }) {
                        Text("Cancel")
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingDeleteProfileId = null
                            onDeleteProfile(profile.id)
                        },
                    ) {
                        Text("Delete", color = ZnKeyboardColors.DeleteContent)
                    }
                },
            )
        }
    }
}

@Composable
private fun OpenRouterProviderSelector(
    modelId: String,
    apiKey: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var providerOptions by remember { mutableStateOf<List<OpenRouterProviderOption>>(emptyList()) }
    var providerLoadStatus by remember { mutableStateOf("Pick a model to load OpenRouter providers.") }
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val normalizedModelId = modelId.trim()

    LaunchedEffect(normalizedModelId, apiKey) {
        providerOptions = emptyList()
        if (normalizedModelId.isBlank()) {
            providerLoadStatus = "Pick a model to load OpenRouter providers."
            return@LaunchedEffect
        }
        if (apiKey.isBlank()) {
            providerLoadStatus = "Enter your OpenRouter API key to load providers."
            return@LaunchedEffect
        }
        if (!normalizedModelId.contains("/")) {
            providerLoadStatus = "Use an OpenRouter model ID like openai/gpt-4o to load providers."
            return@LaunchedEffect
        }

        providerLoadStatus = "Loading providers..."
        delay(MODEL_LOAD_DEBOUNCE_MS)
        val result = withContext(Dispatchers.IO) {
            fetchOpenRouterProviderOptions(
                baseUrl = KeyboardSettings.OPENROUTER_API_BASE_URL,
                modelId = normalizedModelId,
                apiKey = apiKey,
            )
        }
        result
            .onSuccess { providers ->
                providerOptions = providers
                if (currentValue.isNotBlank() && providers.none { it.slug == currentValue }) {
                    currentOnValueChange("")
                }
                providerLoadStatus = if (providers.isEmpty()) {
                    "No provider pins found for this model. Automatic routing is still available."
                } else {
                    "Loaded ${providers.size} providers for this model."
                }
            }
            .onFailure { error ->
                providerLoadStatus = error.message?.takeIf { it.isNotBlank() }
                    ?: "Could not load providers. Automatic routing is still available."
            }
    }

    val selectedLabel = providerOptions.firstOrNull { it.slug == value }?.name
        ?: value.takeIf { it.isNotBlank() }
        ?: "Automatic routing"
    val pickerItems = remember(providerOptions) {
        listOf(
            PickerItem(
                id = "",
                title = "Automatic routing",
                subtitle = "Let OpenRouter choose the provider.",
            ),
        ) + providerOptions.map { option ->
            PickerItem(
                id = option.slug,
                title = option.name,
                subtitle = option.slug,
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.fillMaxWidth()) {
            SettingsPickerLauncherButton(
                label = "OpenRouter provider",
                value = selectedLabel,
                onClick = { expanded = true },
            )

            FloatingPickerMenu(
                expanded = expanded,
                items = pickerItems,
                onDismissRequest = { expanded = false },
                onItemSelected = { item ->
                    expanded = false
                    onValueChange(item.id)
                },
            )
        }

        Text(
            text = providerLoadStatus,
            color = ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
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
        KeyboardSettings.AgentProviderType.NanbeigeLlamaCpp,
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
        SettingsPickerLauncherButton(
            label = "Provider",
            value = providerType.label,
            onClick = { expanded = true },
        )

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
        KeyboardSettings.AgentProviderType.NanbeigeLlamaCpp ->
            "Nanbeige llama.cpp uses its fork-specific request format. Off disables template thinking for fast rewrites."
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

private const val MODEL_LOAD_DEBOUNCE_MS = SettingsUiTimings.MODEL_LOAD_DEBOUNCE_MS
private const val MODEL_QUERY_DEBOUNCE_MS = SettingsUiTimings.MODEL_QUERY_DEBOUNCE_MS
private val BACKUP_IMPORT_MIME_TYPES = SettingsBackupDefaults.IMPORT_MIME_TYPES
private val PICKER_MENU_WIDTH = SettingsUiDimensions.PICKER_MENU_WIDTH
private val PICKER_MAX_HEIGHT = SettingsUiDimensions.PICKER_MAX_HEIGHT
private val PROVIDER_PICKER_MAX_HEIGHT = SettingsUiDimensions.PROVIDER_PICKER_MAX_HEIGHT
private val REASONING_PICKER_MAX_HEIGHT = SettingsUiDimensions.REASONING_PICKER_MAX_HEIGHT
private val SKIN_TONE_PICKER_MAX_HEIGHT = SettingsUiDimensions.SKIN_TONE_PICKER_MAX_HEIGHT
private val SECTION_CORNER_RADIUS = SettingsUiDimensions.SECTION_CORNER_RADIUS
private val COMPACT_ITEM_CORNER_RADIUS = SettingsUiDimensions.COMPACT_ITEM_CORNER_RADIUS
private val PICKER_CORNER_RADIUS = SettingsUiDimensions.PICKER_CORNER_RADIUS
private val TEXT_SNIPPET_DELETE_REVEAL_WIDTH = 96.dp
private const val TEXT_SNIPPET_DELETE_LOCK_THRESHOLD = 0.45f
private const val CLEAR_UNTAGGED_SNIPPETS_CONFIRMATION = "CONFIRM"
