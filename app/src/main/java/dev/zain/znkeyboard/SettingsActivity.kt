package dev.zain.znkeyboard

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
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
    var heightScale by remember { mutableFloatStateOf(KeyboardSettings.readHeightScale(context)) }
    var upperRowKeyIds by remember { mutableStateOf(KeyboardSettings.readUpperRowKeyIds(context)) }
    var agentModeEnabled by remember { mutableStateOf(KeyboardSettings.readAgentModeEnabled(context)) }
    var autocompletePlusEnabled by remember { mutableStateOf(KeyboardSettings.readAutocompletePlusEnabled(context)) }
    var agentProviderType by remember { mutableStateOf(KeyboardSettings.readAgentProviderType(context)) }
    var agentApiBaseUrl by remember { mutableStateOf(KeyboardSettings.readAgentApiBaseUrl(context)) }
    var agentModel by remember { mutableStateOf(KeyboardSettings.readAgentModel(context)) }
    var openRouterProviderSlug by remember { mutableStateOf(KeyboardSettings.readOpenRouterProviderSlug(context)) }
    var agentReasoningMode by remember { mutableStateOf(KeyboardSettings.readAgentReasoningMode(context)) }
    var agentReasoningTextEnabled by remember { mutableStateOf(KeyboardSettings.readAgentReasoningTextEnabled(context)) }
    var agentApiKey by remember { mutableStateOf(KeyboardSettings.readAgentApiKey(context, agentProviderType)) }
    var agentApiKeyLocked by remember { mutableStateOf(KeyboardSettings.readAgentApiKeyLocked(context, agentProviderType)) }

    fun updateUpperRowKeyIds(keyIds: List<String>) {
        val normalizedKeyIds = KeyboardSettings.normalizeUpperRowKeyIds(keyIds)
        upperRowKeyIds = normalizedKeyIds
        KeyboardSettings.saveUpperRowKeyIds(context, normalizedKeyIds)
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
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            SystemSetupActions()

            AgentModeSection(
                agentModeEnabled = agentModeEnabled,
                autocompletePlusEnabled = autocompletePlusEnabled,
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
                onAutocompletePlusEnabledChange = {
                    autocompletePlusEnabled = it
                    KeyboardSettings.saveAutocompletePlusEnabled(context, it)
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

            Surface(
                color = ZnKeyboardColors.Surface,
                shape = RoundedCornerShape(8.dp),
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

            UpperRowKeysSection(
                upperRowKeyIds = upperRowKeyIds,
                onUpperRowKeyIdsChange = ::updateUpperRowKeyIds,
            )
        }
    }
}

@Composable
private fun AgentModeSection(
    agentModeEnabled: Boolean,
    autocompletePlusEnabled: Boolean,
    agentProviderType: KeyboardSettings.AgentProviderType,
    agentApiBaseUrl: String,
    agentModel: String,
    openRouterProviderSlug: String,
    agentReasoningMode: KeyboardSettings.AgentReasoningMode,
    agentReasoningTextEnabled: Boolean,
    agentApiKey: String,
    agentApiKeyLocked: Boolean,
    onAgentModeEnabledChange: (Boolean) -> Unit,
    onAutocompletePlusEnabledChange: (Boolean) -> Unit,
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
        shape = RoundedCornerShape(8.dp),
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
                        text = "Agent mode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Shows an LLM rewrite and autocomplete strip above the keyboard.",
                        color = ZnKeyboardColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = agentModeEnabled,
                    onCheckedChange = onAgentModeEnabledChange,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Autocomplete+",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Suggests typo fixes after you pause typing. Suggestions are never auto-applied.",
                        color = ZnKeyboardColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = autocompletePlusEnabled,
                    onCheckedChange = onAutocompletePlusEnabledChange,
                    enabled = agentModeEnabled,
                )
            }

            Text(
                text = "Text is sent directly from this keyboard to your configured provider. Agent features are disabled in password fields.",
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
                shape = RoundedCornerShape(8.dp),
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
private fun UpperRowKeysSection(
    upperRowKeyIds: List<String>,
    onUpperRowKeyIdsChange: (List<String>) -> Unit,
) {
    Surface(
        color = ZnKeyboardColors.Surface,
        shape = RoundedCornerShape(8.dp),
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
                        text = "Upper row keys",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = "${upperRowKeyIds.size}/${KeyboardSettings.MAX_UPPER_ROW_KEYS}",
                    color = ZnKeyboardColors.Muted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            if (upperRowKeyIds.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(6.dp))
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    upperRowKeyIds.forEachIndexed { index, keyId ->
                        UpperRowKeyEditor(
                            index = index,
                            keyId = keyId,
                            onKeyChange = { nextKeyId ->
                                onUpperRowKeyIdsChange(
                                    upperRowKeyIds.toMutableList().apply {
                                        set(index, nextKeyId)
                                    },
                                )
                            },
                            onRemove = {
                                onUpperRowKeyIdsChange(
                                    upperRowKeyIds.toMutableList().apply {
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
                        onUpperRowKeyIdsChange(upperRowKeyIds + nextUpperRowKeyId(upperRowKeyIds))
                    },
                    enabled = upperRowKeyIds.size < KeyboardSettings.MAX_UPPER_ROW_KEYS,
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
                    onClick = { onUpperRowKeyIdsChange(KeyboardSettings.DEFAULT_UPPER_ROW_KEY_IDS) },
                    enabled = upperRowKeyIds != KeyboardSettings.DEFAULT_UPPER_ROW_KEY_IDS,
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
    onKeyChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember(index, keyId) { mutableStateOf(false) }
    val selectedLabel = KeyboardSettings.labelForUpperRowKey(keyId)

    Row(
        modifier = Modifier.fillMaxWidth(),
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
                items = KeyboardSettings.UPPER_ROW_KEY_OPTIONS.map { option ->
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

private fun nextUpperRowKeyId(currentKeyIds: List<String>): String {
    return KeyboardSettings.DEFAULT_UPPER_ROW_KEY_IDS.firstOrNull { it !in currentKeyIds }
        ?: KeyboardSettings.UPPER_ROW_KEY_OPTIONS.first().id
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

private data class ModelOption(
    val id: String,
    val name: String?,
)

private data class PickerItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
)

private const val MODEL_LOAD_DEBOUNCE_MS = 500L
private const val MODEL_QUERY_DEBOUNCE_MS = 1_000L
private const val MODEL_LOAD_TIMEOUT_MS = 10_000
private val PICKER_MENU_WIDTH = 320.dp
private val PICKER_MAX_HEIGHT = 280.dp
private val PROVIDER_PICKER_MAX_HEIGHT = 140.dp
private val REASONING_PICKER_MAX_HEIGHT = 260.dp
private val PICKER_CORNER_RADIUS = 10.dp

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
    val Background = Color(0xFF151515)
    val Surface = Color(0xFF222222)
    val Key = Color(0xFF2A2A2A)
    val FunctionKey = Color(0xFF323232)
    val Accent = Color(0xFF30ACE2)
    val OnSurface = Color(0xFFFFFFFF)
    val Muted = Color(0xFFB3B3B3)
}
