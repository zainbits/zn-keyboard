package dev.zain.znkeyboard

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zain.znkeyboard.KeyboardSettings.AgentProfile
import dev.zain.znkeyboard.KeyboardSettings.AgentProviderType
import dev.zain.znkeyboard.KeyboardSettings.AgentReasoningMode
import dev.zain.znkeyboard.KeyboardSettings.ScanEngine
import dev.zain.znkeyboard.ai.AiModelClient
import dev.zain.znkeyboard.ai.AiModelClient.isReady
import dev.zain.znkeyboard.constants.SettingsUiDimensions
import dev.zain.znkeyboard.ocrlab.ppocr.OcrModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun AgentProfile.host(): String = when (providerType) {
    AgentProviderType.OpenRouter -> "openrouter.ai"
    else -> baseUrl.substringAfter("://").substringBefore('/').ifBlank { "no URL" }
}

private fun AgentProfile.summary(): String = "${model.ifBlank { "no model" }} · ${host()}"

private fun AgentProviderType.blurb(): String = when (this) {
    AgentProviderType.OpenRouter -> "One API key for hundreds of models. The address is filled in for you."
    AgentProviderType.OpenAiCompatible -> "OpenAI, your own gateway, vLLM or LM Studio. You'll paste its HTTPS URL."
}

// ---------------------------------------------------------------------------------------------
// Tools tab
// ---------------------------------------------------------------------------------------------

/** Rewrite, Scan text and the AI models entry, as one block on the Tools tab. */
@Composable
internal fun AiToolsSections(
    refreshKey: Int,
    onOpenAiModels: () -> Unit,
    onOpenOcrLab: () -> Unit,
) {
    val context = LocalContext.current
    var models by remember(refreshKey) { mutableStateOf(KeyboardSettings.readAiModels(context)) }
    var rewriteEnabled by remember(refreshKey) { mutableStateOf(KeyboardSettings.readAgentModeEnabled(context)) }
    var rewriteId by remember(refreshKey) { mutableStateOf(KeyboardSettings.readActiveAgentProfileId(context)) }
    var scanEngine by remember(refreshKey) { mutableStateOf(KeyboardSettings.readScanEngine(context)) }
    var scanId by remember(refreshKey) { mutableStateOf(KeyboardSettings.readScanProfileId(context)) }
    var scanFallback by remember(refreshKey) { mutableStateOf(KeyboardSettings.readScanFallback(context)) }
    var picker by remember { mutableStateOf<String?>(null) }

    fun reload() {
        models = KeyboardSettings.readAiModels(context)
        rewriteId = KeyboardSettings.readActiveAgentProfileId(context)
        scanId = KeyboardSettings.readScanProfileId(context)
    }

    // Rewrite
    SettingsSectionCard {
        SettingsSectionHeader(
            title = "Rewrite",
            iconResId = R.drawable.ic_tune_24,
            subtitle = "The Rewrite button in the tools row.",
            trailingContent = {
                Switch(checked = rewriteEnabled, onCheckedChange = {
                    rewriteEnabled = it
                    KeyboardSettings.saveAgentModeEnabled(context, it)
                })
            },
        )
        ModelLauncher(model = models.firstOrNull { it.id == rewriteId }, onClick = { picker = "rewrite" })
        MutedText("Text goes straight from the keyboard to the model's server. Rewrite is off in password fields.")
    }

    // Scan text
    val ocrReady = remember(refreshKey) { mutableStateOf(OcrModelStore.isReady(context)) }
    val scanModel = models.firstOrNull { it.id == scanId }
    SettingsSectionCard {
        SettingsSectionHeader(
            title = "Scan text",
            iconResId = R.drawable.ic_camera_24,
            subtitle = "Add the Scan text key to a shortcut row.",
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf(ScanEngine.Device to "On device", ScanEngine.Ai to "AI model").forEachIndexed { index, (engine, label) ->
                SegmentedButton(
                    selected = scanEngine == engine,
                    onClick = {
                        scanEngine = engine
                        KeyboardSettings.saveScanEngine(context, engine)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                ) { Text(label) }
            }
        }
        if (scanEngine == ScanEngine.Device) {
            val sizeMb = (OcrModelStore.totalBytes + 500_000) / 1_000_000
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("PP-OCRv6 Small", fontWeight = FontWeight.SemiBold)
                    MutedText(if (ocrReady.value) "$sizeMb MB on this phone" else "Downloads $sizeMb MB the first time you scan")
                }
                StatusChip(if (ocrReady.value) "Ready" else "Not downloaded", good = ocrReady.value)
            }
            MutedText("Nothing you scan leaves the phone.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onOpenOcrLab,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
                ) { SettingsIconTextButtonContent(R.drawable.ic_camera_24, "Compare OCR engines") }
                if (ocrReady.value) {
                    TextButton(onClick = {
                        OcrModelStore.delete(context)
                        ocrReady.value = false
                        Toast.makeText(context, "Scanner model deleted", Toast.LENGTH_SHORT).show()
                    }) { Text("Delete model", color = MaterialTheme.colorScheme.error) }
                }
            }
        } else {
            ModelLauncher(model = scanModel, onClick = { picker = "scan" })
            when {
                scanModel == null -> Note("Pick a saved model that reads images.", warn = true)
                scanModel.imageInput == false -> Note("${scanModel.name} is text only and can't read photos. Pick a model that reads images.", warn = true)
                scanModel.imageInput == null -> Note("We haven't checked whether ${scanModel.model} reads images. Open it in AI models and tap Test connection.", warn = true)
            }
            if (scanModel != null) Note("Photos are sent to ${scanModel.host()}. They're resized to 1568 px on the long side first.", warn = false)
            SettingsSwitchRow(
                title = "Fall back to on device",
                description = "If the request fails or you're offline, read the photo with PP-OCRv6.",
                checked = scanFallback,
                onCheckedChange = {
                    scanFallback = it
                    KeyboardSettings.saveScanFallback(context, it)
                },
            )
        }
    }

    // AI models entry
    SettingsSectionCard(
        modifier = Modifier
            .clip(RoundedCornerShape(SettingsUiDimensions.SECTION_CORNER_RADIUS))
            .clickable(onClick = onOpenAiModels),
    ) {
        val missingKeys = models.count { !it.isReady() }
        SettingsSectionHeader(
            title = "AI models",
            iconResId = R.drawable.ic_tune_24,
            subtitle = if (models.isEmpty()) {
                "Add a model to use Rewrite or AI scanning."
            } else {
                "${models.size} saved · Rewrite: ${models.firstOrNull { it.id == rewriteId }?.name ?: "none"} · " +
                    "Scan: ${scanModel?.name ?: "none"}"
            },
            trailingContent = { Chevron() },
        )
        if (missingKeys > 0) StatusChip("$missingKeys not set up", good = false)
    }

    picker?.let { feature ->
        ModelPickerSheet(
            title = if (feature == "rewrite") "AI model for Rewrite" else "AI model for Scan text",
            subtitle = if (feature == "scan") "Scan text needs a model that reads images." else "Any saved model works.",
            models = models,
            selectedId = if (feature == "rewrite") rewriteId else scanId,
            forScan = feature == "scan",
            onPick = { id ->
                if (feature == "rewrite") KeyboardSettings.activateAgentProfile(context, id) else KeyboardSettings.saveScanProfileId(context, id)
                reload()
                picker = null
            },
            onManage = {
                picker = null
                onOpenAiModels()
            },
            onDismiss = { picker = null },
        )
    }
}

@Composable
private fun ModelLauncher(model: AgentProfile?, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("AI model", color = ZnKeyboardColors.Muted, style = MaterialTheme.typography.labelSmall)
            Text(model?.name ?: "Choose a model", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (model != null) {
                Text(model.summary(), color = ZnKeyboardColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("Change", color = ZnKeyboardColors.Accent, style = MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    title: String,
    subtitle: String,
    models: List<AgentProfile>,
    selectedId: String,
    forScan: Boolean,
    onPick: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 8.dp))
            MutedText(subtitle, Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            if (models.isEmpty()) {
                MutedText("No saved models yet.", Modifier.padding(8.dp))
            }
            models.forEach { model ->
                val blocked = forScan && model.imageInput == false
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = !blocked) { onPick(model.id) }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = model.id == selectedId, onClick = null, enabled = !blocked)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(model.name, fontWeight = FontWeight.SemiBold, color = if (blocked) ZnKeyboardColors.Muted else ZnKeyboardColors.OnSurface)
                        Text(model.summary(), color = ZnKeyboardColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    when {
                        forScan -> ImageChip(model.imageInput)
                        !model.isReady() -> StatusChip("Not set up", good = false)
                    }
                }
            }
            TextButton(onClick = onManage, modifier = Modifier.align(Alignment.End)) { Text("Manage AI models") }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// AI models screen (list + editor)
// ---------------------------------------------------------------------------------------------

private data class EditorTarget(val model: AgentProfile, val isNew: Boolean, val startDirty: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiModelsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var models by remember { mutableStateOf(KeyboardSettings.readAiModels(context)) }
    var editing by remember { mutableStateOf<EditorTarget?>(null) }

    val target = editing
    if (target != null) {
        AiModelEditor(
            target = target,
            onClose = {
                models = KeyboardSettings.readAiModels(context)
                editing = null
            },
            onDuplicate = { source ->
                editing = EditorTarget(
                    model = source.copy(id = "", name = KeyboardSettings.normalizedUniqueModelName(context, "${source.name} copy")),
                    isNew = true,
                    startDirty = true,
                )
            },
        )
        return
    }

    BackHandler(onBack = onBack)
    val rewriteId = KeyboardSettings.readActiveAgentProfileId(context)
    val scanId = KeyboardSettings.readScanProfileId(context)
    Scaffold(
        containerColor = ZnKeyboardColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("AI models") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Saved models", fontWeight = FontWeight.SemiBold)
                Text("${models.size} of ${KeyboardSettings.MAX_AGENT_PROFILES}", color = ZnKeyboardColors.Muted, style = MaterialTheme.typography.labelMedium)
            }
            if (models.isNotEmpty()) {
                Surface(color = ZnKeyboardColors.Surface, shape = RoundedCornerShape(12.dp)) {
                    Column {
                        models.forEachIndexed { index, model ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 64.dp))
                            ModelRow(
                                model = model,
                                usedBy = listOfNotNull("Rewrite".takeIf { model.id == rewriteId }, "Scan".takeIf { model.id == scanId }),
                                onClick = { editing = EditorTarget(model, isNew = false) },
                            )
                        }
                    }
                }
            }
            Button(
                onClick = {
                    editing = EditorTarget(
                        AgentProfile(
                            id = "", name = "", providerType = AgentProviderType.OpenRouter, baseUrl = "", model = "",
                            openRouterProviderSlug = "", reasoningMode = AgentReasoningMode.Off, reasoningTextEnabled = false,
                            apiKey = "", apiKeyLocked = false,
                        ),
                        isNew = true,
                    )
                },
                enabled = models.size < KeyboardSettings.MAX_AGENT_PROFILES,
            ) {
                Icon(painterResource(R.drawable.ic_add_24), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add model")
            }
            MutedText(
                "A saved model is a server, its API key and a model name. Rewrite and Scan text each use one. " +
                    "Keys are stored encrypted on this phone and only sent to that server.",
                Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** One compact line per model: initials, name, model · host · used by, and a status icon. */
@Composable
private fun ModelRow(model: AgentProfile, usedBy: List<String>, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                model.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() },
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                usedBy.forEach { use ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        use,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Text(model.summary(), color = ZnKeyboardColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!model.isReady()) {
            Spacer(Modifier.width(8.dp))
            Text("Set up", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        } else if (model.imageInput == true) {
            Spacer(Modifier.width(8.dp))
            Icon(painterResource(R.drawable.ic_camera_24), contentDescription = "Reads images", tint = ZnKeyboardColors.Muted, modifier = Modifier.size(18.dp))
        }
    }
}

private sealed interface TestState {
    data object Running : TestState
    data class Done(val result: AiModelClient.TestResult) : TestState
    data class Failed(val message: String) : TestState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiModelEditor(
    target: EditorTarget,
    onClose: () -> Unit,
    onDuplicate: (AgentProfile) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var savedId by remember(target) { mutableStateOf(target.model.id) }
    var draft by remember(target) { mutableStateOf(target.model) }
    var dirty by remember(target) { mutableStateOf(target.startDirty) }
    var pickingProvider by remember(target) { mutableStateOf(target.isNew && !target.startDirty) }
    var test by remember(target) { mutableStateOf<TestState?>(null) }
    var assignRewrite by remember(target) { mutableStateOf(false) }
    var assignScan by remember(target) { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val isNew = savedId.isBlank()

    fun edit(next: AgentProfile) {
        draft = next
        dirty = true
    }

    fun leave() {
        if (dirty) confirmDiscard = true else onClose()
    }

    BackHandler {
        if (pickingProvider && isNew && !dirty) onClose() else leave()
    }

    val rewriteId = KeyboardSettings.readActiveAgentProfileId(context)
    val scanId = KeyboardSettings.readScanProfileId(context)
    val suggestedName = draft.model.substringAfterLast('/')
    val effectiveName = draft.name.trim().ifBlank { suggestedName }
    val nameClash = KeyboardSettings.readAgentProfiles(context).any { it.id != savedId && it.name.equals(effectiveName, ignoreCase = true) }
    val saveHint = when {
        draft.providerType != AgentProviderType.OpenRouter && draft.baseUrl.isBlank() -> "Add the server URL, then choose a model."
        draft.model.isBlank() -> "Choose a model to save."
        nameClash -> "Pick a different name."
        !dirty -> if (isNew) "" else "All changes saved"
        else -> "Unsaved changes"
    }
    val canSave = dirty && draft.model.isNotBlank() && !nameClash &&
        (draft.providerType == AgentProviderType.OpenRouter || draft.baseUrl.isNotBlank())

    fun save() {
        val saved = KeyboardSettings.saveAiModel(context, draft.copy(id = savedId))
        if (saved == null) {
            Toast.makeText(context, "Couldn't save this model.", Toast.LENGTH_SHORT).show()
            return
        }
        if (assignRewrite) KeyboardSettings.activateAgentProfile(context, saved.id)
        if (assignScan && saved.imageInput != false) KeyboardSettings.saveScanProfileId(context, saved.id)
        // A Scan model needs Scan to be in AI mode to matter; the first one switches it on.
        if (assignScan && KeyboardSettings.readScanEngine(context) == ScanEngine.Device) {
            KeyboardSettings.saveScanEngine(context, ScanEngine.Ai)
        }
        assignRewrite = false
        assignScan = false
        savedId = saved.id
        draft = saved
        dirty = false
        Toast.makeText(context, "Saved ${saved.name}", Toast.LENGTH_SHORT).show()
    }

    if (pickingProvider && isNew) {
        ProviderStep(
            onBack = onClose,
            onPick = { type ->
                draft = draft.copy(providerType = type, baseUrl = if (type == AgentProviderType.OpenRouter) "" else draft.baseUrl)
                pickingProvider = false
            },
        )
        return
    }

    Scaffold(
        containerColor = ZnKeyboardColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New AI model" else draft.name.ifBlank { suggestedName }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackIconButton { if (isNew && !dirty) pickingProvider = true else leave() } },
                actions = {
                    if (!isNew) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Text("⋮", style = MaterialTheme.typography.titleLarge)
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("Duplicate") }, onClick = {
                                    menuOpen = false
                                    onDuplicate(draft)
                                })
                                DropdownMenuItem(
                                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        menuOpen = false
                                        confirmDelete = true
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ZnKeyboardColors.Background),
            )
        },
        bottomBar = {
            Surface(color = ZnKeyboardColors.Background, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(saveHint, color = ZnKeyboardColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Button(onClick = ::save, enabled = canSave) { Text("Save") }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (isNew) MutedText("Step 2 of 2 · Connect and choose a model")
            SectionLabel("Connection")
            ProviderTypeSelector(providerType = draft.providerType, onProviderTypeChange = { type ->
                edit(draft.copy(providerType = type, baseUrl = if (type == AgentProviderType.OpenRouter) "" else draft.baseUrl))
                test = null
            })
            if (draft.providerType != AgentProviderType.OpenRouter) {
                OutlinedTextField(
                    value = draft.baseUrl,
                    onValueChange = {
                        edit(draft.copy(baseUrl = it))
                        test = null
                    },
                    label = { Text("Server URL") },
                    placeholder = { Text("https://host/v1") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                MutedText("OpenRouter's address is filled in for you.")
            }
            LockableSecretOutlinedTextField(
                value = draft.apiKey,
                onValueChange = {
                    edit(draft.copy(apiKey = it))
                    test = null
                },
                label = "API key",
                locked = draft.apiKeyLocked,
                onLockedChange = { edit(draft.copy(apiKeyLocked = it)) },
                emptySupportingText = "Stored encrypted on this phone.",
                lockContentDescription = "Lock API key",
                unlockContentDescription = "Unlock API key",
            )
            if (draft.apiKey.isBlank()) {
                MutedText("Add the API key to load the list of models.")
            }
            ModelSelectorField(
                providerType = draft.providerType,
                apiBaseUrl = if (draft.providerType == AgentProviderType.OpenRouter) KeyboardSettings.OPENROUTER_API_BASE_URL else draft.baseUrl,
                apiKey = draft.apiKey,
                value = draft.model,
                onValueChange = {
                    if (it != draft.model) {
                        edit(draft.copy(model = it, imageInput = null))
                        test = null
                    }
                },
            )
            if (draft.providerType == AgentProviderType.OpenRouter) {
                OpenRouterProviderSelector(
                    modelId = draft.model,
                    apiKey = draft.apiKey,
                    value = draft.openRouterProviderSlug,
                    onValueChange = { if (it != draft.openRouterProviderSlug) edit(draft.copy(openRouterProviderSlug = it)) },
                )
            }
            ReasoningControls(
                providerType = draft.providerType,
                reasoningMode = draft.reasoningMode,
                reasoningTextEnabled = draft.reasoningTextEnabled,
                onReasoningModeChange = { edit(draft.copy(reasoningMode = it)) },
                onReasoningTextEnabledChange = { edit(draft.copy(reasoningTextEnabled = it)) },
            )
            FilledTonalButton(
                onClick = {
                    test = TestState.Running
                    val settings = draft.toProviderSettings()
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { AiModelClient.testConnection(settings) } }
                        test = result.fold(
                            onSuccess = { r ->
                                if (draft.imageInput != r.imageInput) edit(draft.copy(imageInput = r.imageInput))
                                TestState.Done(r)
                            },
                            onFailure = { TestState.Failed(it.message ?: "Couldn't reach the server.") },
                        )
                    }
                },
                enabled = test != TestState.Running && draft.model.isNotBlank(),
            ) { Text("Test connection") }
            when (val t = test) {
                TestState.Running -> MutedText("Sending a test request and a small image…")
                is TestState.Done -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Connected · reply in ${t.result.latencyMs} ms", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    MutedText(if (t.result.imageInput) "Reads images, so Scan text can use it." else "Text only. Scan text can't use this model.")
                }
                is TestState.Failed -> Text(t.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                null -> Unit
            }

            SectionLabel("Name")
            OutlinedTextField(
                value = draft.name,
                onValueChange = { edit(draft.copy(name = it)) },
                label = { Text("Name (optional)") },
                placeholder = { Text(suggestedName.ifBlank { "e.g. Fast rewrite" }) },
                singleLine = true,
                isError = nameClash,
                supportingText = {
                    Text(if (nameClash) "You already have a model with this name." else "Left empty, it's called ${suggestedName.ifBlank { "after the model" }}.")
                },
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel("Use it for")
            UseForRow(
                label = "Rewrite",
                lockedOn = !isNew && rewriteId == savedId,
                blockedReason = null,
                currentName = KeyboardSettings.readAgentProfiles(context).firstOrNull { it.id == rewriteId }?.name,
                checked = assignRewrite,
                onCheckedChange = {
                    assignRewrite = it
                    dirty = true
                },
            )
            UseForRow(
                label = "Scan text",
                lockedOn = !isNew && scanId == savedId,
                blockedReason = "Text only, so Scan text can't use it.".takeIf { draft.imageInput == false },
                currentName = KeyboardSettings.readAgentProfiles(context).firstOrNull { it.id == scanId }?.name,
                checked = assignScan,
                onCheckedChange = {
                    assignScan = it
                    dirty = true
                },
            )
            if (!isNew) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Delete this model", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }

    if (confirmDelete) {
        val usedBy = listOfNotNull("Rewrite".takeIf { rewriteId == savedId }, "Scan text".takeIf { scanId == savedId })
        val next = KeyboardSettings.readAgentProfiles(context).firstOrNull { it.id != savedId }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${draft.name}?") },
            text = {
                Text(
                    buildString {
                        if (usedBy.isNotEmpty()) {
                            append(usedBy.joinToString(" and "))
                            append(if (next != null) " will switch to ${next.name}. " else " will have no model. ")
                        }
                        append(if (draft.apiKey.isNotBlank()) "Its stored API key is removed too." else "This can't be undone.")
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    KeyboardSettings.deleteAgentProfile(context, savedId)
                    onClose()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits to ${effectiveName.ifBlank { "this model" }} haven't been saved.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onClose()
                }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderStep(onBack: () -> Unit, onPick: (AgentProviderType) -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = ZnKeyboardColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("New AI model") },
                navigationIcon = { BackIconButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ZnKeyboardColors.Background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MutedText("Step 1 of 2 · Where does the model run?")
            listOf(AgentProviderType.OpenRouter, AgentProviderType.OpenAiCompatible).forEach { type ->
                Surface(
                    onClick = { onPick(type) },
                    shape = RoundedCornerShape(16.dp),
                    color = ZnKeyboardColors.Surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(if (type == AgentProviderType.OpenAiCompatible) "Any OpenAI-compatible server" else type.label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                        MutedText(type.blurb())
                    }
                }
            }
            MutedText("You can change this later.", Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun UseForRow(
    label: String,
    lockedOn: Boolean,
    blockedReason: String?,
    currentName: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val description = when {
        lockedOn -> "$label uses this model. To switch, pick another one on the Tools tab."
        blockedReason != null -> blockedReason
        currentName != null -> "$label uses $currentName now."
        else -> "$label has no model yet."
    }
    SettingsSwitchRow(
        title = "Use for $label",
        description = description,
        checked = lockedOn || (checked && blockedReason == null),
        onCheckedChange = onCheckedChange,
        enabled = !lockedOn && blockedReason == null,
    )
}

// ---------------------------------------------------------------------------------------------
// Small pieces
// ---------------------------------------------------------------------------------------------

@Composable
private fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(text, color = ZnKeyboardColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = modifier)
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun StatusChip(text: String, good: Boolean) {
    val bg = if (good) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
    val fg = if (good) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Text(
        text,
        color = fg,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun ImageChip(imageInput: Boolean?) {
    when (imageInput) {
        true -> StatusChip("Reads images", good = true)
        false -> Text("Text only", color = ZnKeyboardColors.Muted, style = MaterialTheme.typography.labelMedium)
        null -> Text("Not checked", color = ZnKeyboardColors.Muted, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Note(text: String, warn: Boolean) {
    val bg = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    val fg = if (warn) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
    Text(
        text,
        color = fg,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().background(bg, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun Chevron() {
    Icon(
        painter = painterResource(R.drawable.ic_chevron_left_24),
        contentDescription = null,
        tint = ZnKeyboardColors.Muted,
        modifier = Modifier.size(24.dp).rotate(180f),
    )
}
