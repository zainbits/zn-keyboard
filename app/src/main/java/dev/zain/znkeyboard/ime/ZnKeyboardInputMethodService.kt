package dev.zain.znkeyboard.ime

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.Toast
import dev.zain.znkeyboard.KeyboardSettings
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class ZnKeyboardInputMethodService : InputMethodService(),
    ZnKeyboardView.Callback,
    AgentAssistStripView.Callback {
    private var keyboardView: ZnKeyboardView? = null
    private var agentStripView: AgentAssistStripView? = null
    private var currentEditorInfo: EditorInfo? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var draftState: DraftState? = null
    private var agentError: String? = null
    private var agentLoading = false
    private var requestGeneration = 0
    private var autocompleteRunnable: Runnable? = null
    private var lastAutocompleteInput: String? = null

    override fun onCreateInputView(): View {
        val agentStrip = AgentAssistStripView(this).also { view ->
            agentStripView = view
            view.callback = this
        }
        val keyboard = ZnKeyboardView(this).also { view ->
            keyboardView = view
            view.callback = this
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                agentStrip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(56),
                ),
            )
            addView(
                keyboard,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            applyKeyboardSettings()
            keyboard.setEnterLabel(resolveEnterLabel(currentEditorInfo))
            renderAgentStrip()
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        currentEditorInfo = attribute
        resetAgentDraft()
        applyKeyboardSettings()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentEditorInfo = info
        resetAgentDraft()
        applyKeyboardSettings()
        keyboardView?.setEnterLabel(resolveEnterLabel(info))
        renderAgentStrip()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        keyboardView?.clearLatchedModifiers()
        resetAgentDraft()
        cancelAutocomplete()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        cancelAutocomplete()
        requestGeneration++
        super.onDestroy()
    }

    override fun onKeyboardAction(action: KeyboardAction, modifiers: ModifierState) {
        if (handleAgentDraftKeyboardAction(action, modifiers)) {
            return
        }

        when (action) {
            KeyboardAction.Backspace -> handleBackspace()
            KeyboardAction.Enter -> handleEnter(modifiers)
            is KeyboardAction.KeyCode -> {
                sendKey(action.keyCode, modifiers)
                resetAgentDraft()
            }
            is KeyboardAction.Text -> handleText(action.value, modifiers)
        }
    }

    override fun onAgentDraftEdited(text: String) {
        cancelAutocomplete()
        requestGeneration++
        agentError = null
        agentLoading = false
        val previous = draftState
        draftState = text.takeIf { it.isNotEmpty() }?.let {
            when {
                previous == null -> DraftState(
                    originalText = it,
                    draftText = it,
                    source = DraftSource.Manual,
                    editorAnchorText = null,
                )
                previous.source == DraftSource.Manual &&
                    previous.editorAnchorText == null &&
                    previous.originalText == previous.draftText -> DraftState(
                        originalText = it,
                        draftText = it,
                        source = DraftSource.Manual,
                        editorAnchorText = null,
                    )
                else -> previous.copy(
                    draftText = it,
                    source = DraftSource.Manual,
                )
            }
        }
        renderAgentStrip()
    }

    override fun onAgentRewriteRequested() {
        val state = draftState ?: return
        startLlmRequest(
            kind = LlmRequestKind.Rewrite,
            sourceText = state.originalText,
            draftText = state.draftText,
        )
    }

    override fun onAgentApplyRequested() {
        applyAgentDraft()
    }

    private fun applyKeyboardSettings() {
        keyboardView?.let { view ->
            view.setHeightScale(KeyboardSettings.readHeightScale(this))
            view.setUpperRowKeyIds(KeyboardSettings.readUpperRowKeyIds(this))
        }
        renderAgentStrip()
    }

    private fun handleAgentDraftKeyboardAction(action: KeyboardAction, modifiers: ModifierState): Boolean {
        val strip = agentStripView ?: return false
        if (!strip.isDraftFieldFocused || modifiers.hasHardwareMeta) return false

        return when (action) {
            KeyboardAction.Backspace -> strip.deleteDraftTextBeforeCursor()
            KeyboardAction.Enter -> strip.commitDraftText("\n")
            is KeyboardAction.Text -> strip.commitDraftText(action.value)
            is KeyboardAction.KeyCode -> strip.handleDraftKeyCode(action.keyCode)
        }
    }

    private fun handleText(value: String, modifiers: ModifierState) {
        val inputConnection = currentInputConnection ?: return
        if (modifiers.hasHardwareMeta && value.length == 1) {
            val keyCode = keyCodeFor(value[0])
            if (keyCode != null) {
                sendKey(keyCode, modifiers)
                resetAgentDraft()
                return
            }
        }
        inputConnection.commitText(value, 1)
        refreshDraftFromEditor()
        scheduleAutocomplete()
    }

    private fun handleBackspace() {
        val inputConnection = currentInputConnection ?: return
        val selectedText = inputConnection.getSelectedText(0)
        if (!selectedText.isNullOrEmpty()) {
            inputConnection.commitText("", 1)
        } else {
            inputConnection.deleteSurroundingText(1, 0)
        }
        refreshDraftFromEditor()
        scheduleAutocomplete()
    }

    private fun handleEnter(modifiers: ModifierState) {
        val inputConnection = currentInputConnection ?: return
        val info = currentEditorInfo
        val imeOptions = info?.imeOptions ?: 0
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val forceEnter = imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0

        if (!forceEnter && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            inputConnection.performEditorAction(action)
        } else {
            sendKey(KeyEvent.KEYCODE_ENTER, modifiers)
        }
        resetAgentDraft()
    }

    private fun sendKey(keyCode: Int, modifiers: ModifierState) {
        val inputConnection = currentInputConnection ?: return
        val downTime = SystemClock.uptimeMillis()
        val metaState = modifiers.toMetaState()
        inputConnection.sendKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        inputConnection.sendKeyEvent(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0, metaState))
    }

    private fun refreshDraftFromEditor() {
        if (!isAgentStripAvailable()) {
            resetAgentDraft()
            return
        }
        if (agentStripView?.isDraftFieldFocused == true) {
            return
        }
        val inputConnection = currentInputConnection ?: return
        val beforeCursor = inputConnection.getTextBeforeCursor(MAX_CONTEXT_CHARS, 0)?.toString().orEmpty()
        val segment = extractCurrentSegment(beforeCursor)
        if (segment.isBlank()) {
            resetAgentDraft()
            return
        }

        val previous = draftState
        if (previous?.originalText != segment || previous.source == DraftSource.Mirror) {
            requestGeneration++
            agentError = null
            agentLoading = false
            draftState = DraftState(
                originalText = segment,
                draftText = segment,
                source = DraftSource.Mirror,
                editorAnchorText = segment,
            )
        }
        renderAgentStrip()
    }

    private fun extractCurrentSegment(beforeCursor: String): String {
        val currentLine = beforeCursor.substringAfterLast('\n')
        return if (currentLine.length > MAX_DRAFT_CHARS) {
            currentLine.takeLast(MAX_DRAFT_CHARS)
        } else {
            currentLine
        }
    }

    private fun applyAgentDraft() {
        val state = draftState ?: return
        if (state.draftText.isBlank() || state.draftText == state.originalText) return

        val inputConnection = currentInputConnection ?: return
        val editorAnchorText = state.editorAnchorText
        if (editorAnchorText != null) {
            val beforeCursor = inputConnection.getTextBeforeCursor(MAX_CONTEXT_CHARS, 0)?.toString().orEmpty()
            if (!beforeCursor.endsWith(editorAnchorText)) {
                showAgentError("Move the cursor back to the text to apply this.")
                return
            }
        }

        inputConnection.beginBatchEdit()
        try {
            if (editorAnchorText != null) {
                inputConnection.deleteSurroundingText(editorAnchorText.length, 0)
            }
            inputConnection.commitText(state.draftText, 1)
        } finally {
            inputConnection.endBatchEdit()
        }

        cancelAutocomplete()
        lastAutocompleteInput = state.draftText
        agentError = null
        agentLoading = false
        draftState = DraftState(
            originalText = state.draftText,
            draftText = state.draftText,
            source = DraftSource.Mirror,
            editorAnchorText = state.draftText,
        )
        agentStripView?.clearDraftFocus()
        renderAgentStrip()
    }

    private fun scheduleAutocomplete() {
        cancelAutocomplete()
        if (!isAgentStripAvailable() || !KeyboardSettings.readAutocompletePlusEnabled(this)) return
        if (!KeyboardSettings.readAgentProviderSettings(this).isConfigured) return

        val state = draftState ?: return
        val input = state.originalText.trim()
        if (input.length < MIN_AUTOCOMPLETE_CHARS || input == lastAutocompleteInput) return

        autocompleteRunnable = Runnable {
            lastAutocompleteInput = input
            startLlmRequest(
                kind = LlmRequestKind.Autocomplete,
                sourceText = state.originalText,
                draftText = state.originalText,
            )
        }.also { runnable ->
            mainHandler.postDelayed(runnable, AUTOCOMPLETE_DEBOUNCE_MS)
        }
    }

    private fun cancelAutocomplete() {
        autocompleteRunnable?.let(mainHandler::removeCallbacks)
        autocompleteRunnable = null
    }

    private fun startLlmRequest(
        kind: LlmRequestKind,
        sourceText: String,
        draftText: String,
    ) {
        if (!isAgentStripAvailable()) return
        val providerSettings = KeyboardSettings.readAgentProviderSettings(this)
        if (!providerSettings.isConfigured) {
            showAgentError("Add API settings first.")
            return
        }

        cancelAutocomplete()
        val generation = ++requestGeneration
        agentError = null
        agentLoading = true
        Log.d(TAG, "Starting $kind request for ${draftText.length} chars")
        renderAgentStrip()

        Thread {
            val result = runCatching {
                requestChatCompletion(
                    providerSettings = providerSettings,
                    kind = kind,
                    text = draftText,
                )
            }

            mainHandler.post {
                if (generation != requestGeneration || !isAgentStripAvailable()) return@post
                agentLoading = false
                result
                    .onSuccess { content ->
                        applyLlmResult(kind, sourceText, content)
                    }
                    .onFailure { error ->
                        showAgentError(error.message ?: "LLM request failed.")
                    }
                renderAgentStrip()
            }
        }.apply {
            name = "ZnKeyboardAgentRequest"
            start()
        }
    }

    private fun requestChatCompletion(
        providerSettings: KeyboardSettings.AgentProviderSettings,
        kind: LlmRequestKind,
        text: String,
    ): String {
        val endpoint = buildChatCompletionsUrl(providerSettings)
        var strictSchemaError: IOException? = null

        for (outputMode in StructuredOutputMode.entries) {
            try {
                return executeChatCompletionRequest(
                    endpoint = endpoint,
                    providerSettings = providerSettings,
                    kind = kind,
                    text = text,
                    outputMode = outputMode,
                )
            } catch (error: LlmHttpException) {
                if (outputMode == StructuredOutputMode.JsonSchema && error.isStrictSchemaUnsupported()) {
                    strictSchemaError = error
                    Log.w(TAG, "Strict JSON schema output unsupported; retrying JSON object output.")
                    continue
                }
                throw error
            }
        }

        throw strictSchemaError ?: IOException("LLM request failed before returning structured output.")
    }

    private fun executeChatCompletionRequest(
        endpoint: URL,
        providerSettings: KeyboardSettings.AgentProviderSettings,
        kind: LlmRequestKind,
        text: String,
        outputMode: StructuredOutputMode,
    ): String {
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = REQUEST_TIMEOUT_MS
            readTimeout = REQUEST_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${providerSettings.apiKey}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        val body = JSONObject()
            .put("model", providerSettings.model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPromptFor(kind)))
                    .put(JSONObject().put("role", "user").put("content", text)),
            )
            .put("temperature", if (kind == LlmRequestKind.Rewrite) 0.4 else 0.1)
            .put("max_tokens", maxTokensFor(kind, text))
            .put("response_format", structuredResponseFormatFor(kind, outputMode))
            .applyOpenRouterProviderPreferences(providerSettings, outputMode)
            .applyReasoningSettings(providerSettings)

        return try {
            connection.outputStream.writer(Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }

            val responseCode = connection.responseCode
            val responseText = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }

            if (responseCode !in 200..299) {
                throw LlmHttpException(
                    responseCode = responseCode,
                    responseText = responseText,
                    detailMessage = "LLM request failed ($responseCode): ${responseText.take(ERROR_PREVIEW_CHARS)}",
                )
            }

            extractChatCompletionContent(
                responseJson = JSONObject(responseText),
                includeReasoningText = providerSettings.reasoningTextEnabled,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun extractChatCompletionContent(
        responseJson: JSONObject,
        includeReasoningText: Boolean,
    ): String {
        val choice = responseJson
                .getJSONArray("choices")
                .getJSONObject(0)

        val message = choice.optJSONObject("message")
            ?: throw IOException("LLM response did not include a message.")

        val refusal = message.optNullableString("refusal")
        if (!refusal.isNullOrBlank()) {
            throw IOException("LLM refused the request: ${refusal.take(ERROR_PREVIEW_CHARS)}")
        }

        val structuredPayload = parseStructuredOutput(message.opt("content"), includeReasoningText)
        val text = structuredPayload
            .optString(STRUCTURED_OUTPUT_TEXT_FIELD)
            .let(AsciiTextSanitizer::toAscii)
            .trim()

        if (text.isBlank() || text.equals("null", ignoreCase = true)) {
            val finishReason = choice.optString("finish_reason", "unknown")
            throw IOException("No text returned by the model. Finish reason: $finishReason.")
        }

        return text
    }

    private fun parseStructuredOutput(content: Any?, includeReasoningText: Boolean): JSONObject {
        return when (content) {
            null, JSONObject.NULL -> throw IOException("LLM response did not include structured content.")
            is JSONObject -> content
            is String -> parseStructuredOutputJson(content, includeReasoningText)
            is JSONArray -> parseStructuredOutputJson(extractTextFromContentArray(content), includeReasoningText)
            else -> throw IOException("LLM response content was not a structured JSON object.")
        }
    }

    private fun JSONObject.optNullableString(name: String): String? {
        val value = opt(name)
        if (value == null || value == JSONObject.NULL) return null
        return value.toString()
            .trim()
            .takeUnless { it.equals("null", ignoreCase = true) }
    }

    private fun parseStructuredOutputJson(rawContent: String, includeReasoningText: Boolean): JSONObject {
        val jsonText = rawContent
            .stripReasoningBlocks(includeReasoningText)
            .trim()
        if (jsonText.isBlank()) {
            throw IOException("LLM response did not include structured JSON.")
        }

        try {
            return JSONObject(jsonText)
        } catch (error: JSONException) {
            throw IOException("LLM response was not valid structured JSON.", error)
        }
    }

    private fun String.stripReasoningBlocks(includeReasoningText: Boolean): String {
        if (includeReasoningText) return this

        return replace(THINK_BLOCK_REGEX, "")
            .replace(REASONING_BLOCK_REGEX, "")
    }

    private fun extractTextFromContentArray(content: JSONArray): String {
        return buildString {
            for (index in 0 until content.length()) {
                when (val part = content.opt(index)) {
                    is String -> append(part)
                    is JSONObject -> append(part.optString("text"))
                }
            }
        }
    }

    private fun maxTokensFor(kind: LlmRequestKind, text: String): Int {
        if (kind == LlmRequestKind.Autocomplete) return MAX_AUTOCOMPLETE_LLM_TOKENS

        val estimatedOutputTokens = (text.length / CHARS_PER_OUTPUT_TOKEN_ESTIMATE)
            .coerceAtLeast(MIN_REWRITE_LLM_TOKENS)
        return estimatedOutputTokens.coerceAtMost(MAX_REWRITE_LLM_TOKENS)
    }

    private fun structuredResponseFormatFor(kind: LlmRequestKind, outputMode: StructuredOutputMode): JSONObject {
        if (outputMode == StructuredOutputMode.JsonObject) {
            return JSONObject().put("type", "json_object")
        }

        val textDescription = when (kind) {
            LlmRequestKind.Rewrite -> "The final rewritten text only, without labels, explanations, or prefaces."
            LlmRequestKind.Autocomplete -> "The final corrected text only, without labels, explanations, or prefaces."
        }

        return JSONObject()
            .put("type", "json_schema")
            .put(
                "json_schema",
                JSONObject()
                    .put("name", "znkeyboard_${kind.schemaName}_response")
                    .put("strict", true)
                    .put(
                        "schema",
                        JSONObject()
                            .put("type", "object")
                            .put("additionalProperties", false)
                            .put(
                                "properties",
                                JSONObject()
                                    .put(
                                        STRUCTURED_OUTPUT_TEXT_FIELD,
                                        JSONObject()
                                            .put("type", "string")
                                            .put("description", textDescription),
                                    ),
                            )
                            .put("required", JSONArray().put(STRUCTURED_OUTPUT_TEXT_FIELD)),
                    ),
            )
    }

    private fun JSONObject.applyOpenRouterProviderPreferences(
        providerSettings: KeyboardSettings.AgentProviderSettings,
        outputMode: StructuredOutputMode,
    ): JSONObject {
        if (providerSettings.providerType != KeyboardSettings.AgentProviderType.OpenRouter) return this

        val provider = JSONObject()
        if (outputMode == StructuredOutputMode.JsonSchema) {
            provider.put("require_parameters", true)
        }
        val providerSlug = providerSettings.openRouterProviderSlug.trim()
        if (providerSlug.isNotBlank()) {
            provider
                .put("only", JSONArray().put(providerSlug))
                .put("allow_fallbacks", false)
        }

        if (provider.length() == 0) return this

        return put(
            "provider",
            provider,
        )
    }

    private fun JSONObject.applyReasoningSettings(
        providerSettings: KeyboardSettings.AgentProviderSettings,
    ): JSONObject {
        val reasoningMode = providerSettings.reasoningMode
        if (reasoningMode == KeyboardSettings.AgentReasoningMode.Off) {
            if (providerSettings.providerType == KeyboardSettings.AgentProviderType.OpenRouter) {
                put(
                    "reasoning",
                    JSONObject()
                        .put("effort", "none")
                        .put("exclude", true),
                )
            }
            return this
        }

        return when (providerSettings.providerType) {
            KeyboardSettings.AgentProviderType.OpenRouter -> applyOpenRouterReasoningSettings(providerSettings)
            KeyboardSettings.AgentProviderType.OpenAiCompatible -> applyOpenAiCompatibleReasoningSettings(providerSettings)
        }
    }

    private fun JSONObject.applyOpenRouterReasoningSettings(
        providerSettings: KeyboardSettings.AgentProviderSettings,
    ): JSONObject {
        val reasoning = JSONObject()
            .put("exclude", !providerSettings.reasoningTextEnabled)
        val effort = providerSettings.reasoningMode.effort
        if (effort != null) {
            reasoning.put("effort", effort)
        } else {
            reasoning.put("enabled", true)
        }

        return put("reasoning", reasoning)
    }

    private fun JSONObject.applyOpenAiCompatibleReasoningSettings(
        providerSettings: KeyboardSettings.AgentProviderSettings,
    ): JSONObject {
        providerSettings.reasoningMode.effort?.let { effort ->
            put("reasoning_effort", effort)
        }
        if (providerSettings.reasoningTextEnabled) {
            put("include_reasoning", true)
            put(
                "reasoning",
                JSONObject().put("exclude", false),
            )
        }
        return this
    }

    private fun buildChatCompletionsUrl(providerSettings: KeyboardSettings.AgentProviderSettings): URL {
        val normalizedBaseUrl = providerSettings.baseUrl.trim().trimEnd('/')
        val endpoint = URL("$normalizedBaseUrl/chat/completions")
        if (endpoint.protocol != "https") {
            throw IOException("Use an HTTPS OpenAI-compatible endpoint.")
        }
        return endpoint
    }

    private fun systemPromptFor(kind: LlmRequestKind): String {
        return when (kind) {
            LlmRequestKind.Rewrite -> rewriteSystemPrompt()
            LlmRequestKind.Autocomplete -> loadPromptAsset(
                AUTOCOMPLETE_PROMPT_ASSET,
                FALLBACK_AUTOCOMPLETE_PROMPT,
            )
        }
    }

    private fun rewriteSystemPrompt(): String {
        return buildList {
            add(loadPromptAsset(REWRITE_BASE_PROMPT_ASSET, FALLBACK_REWRITE_PROMPT))
            appPromptAssetFor(currentEditorInfo?.packageName)?.let { assetPath ->
                add(loadPromptAsset(assetPath, fallback = ""))
            }
        }
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n\n---\n\n")
    }

    private fun appPromptAssetFor(packageName: String?): String? {
        val normalizedPackageName = packageName?.lowercase(Locale.US) ?: return null
        return when {
            normalizedPackageName == WHATSAPP_PACKAGE_NAME ||
                normalizedPackageName == WHATSAPP_BUSINESS_PACKAGE_NAME -> WHATSAPP_PROMPT_ASSET
            else -> null
        }
    }

    private fun loadPromptAsset(path: String, fallback: String): String {
        return runCatching {
            assets.open(path).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText().trim()
            }
        }.getOrElse {
            fallback
        }.trim()
    }

    private fun applyLlmResult(kind: LlmRequestKind, sourceText: String, content: String) {
        val normalized = AsciiTextSanitizer.toAscii(content).trim()
        if (normalized.isBlank() || normalized.equals("null", ignoreCase = true)) {
            showAgentError("No suggestion returned.")
            return
        }

        val current = draftState ?: return
        if (current.originalText != sourceText) return

        if (kind == LlmRequestKind.Autocomplete && normalized == sourceText.trim()) {
            agentError = null
            return
        }

        agentError = null
        draftState = current.copy(
            draftText = normalized,
            source = when (kind) {
                LlmRequestKind.Rewrite -> DraftSource.Rewrite
                LlmRequestKind.Autocomplete -> DraftSource.Suggestion
            },
        )
    }

    private fun showAgentError(message: String) {
        agentLoading = false
        agentError = message
        Log.w(TAG, message)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        renderAgentStrip()
    }

    private fun resetAgentDraft() {
        requestGeneration++
        draftState = null
        agentError = null
        agentLoading = false
        renderAgentStrip()
    }

    private fun renderAgentStrip() {
        val visible = isAgentStripAvailable()
        val providerConfigured = KeyboardSettings.readAgentProviderSettings(this).isConfigured
        val state = draftState
        val placeholder = when {
            !providerConfigured -> "Add API settings to use agent mode"
            KeyboardSettings.readAutocompletePlusEnabled(this) -> "Type or paste text here for AI suggestions"
            else -> "Type or paste text here to rewrite"
        }

        agentStripView?.render(
            AgentAssistStripView.State(
                visible = visible,
                text = state?.draftText.orEmpty(),
                placeholder = placeholder,
                errorText = agentError,
                loading = agentLoading,
                rewriteEnabled = visible && providerConfigured && !state?.draftText.isNullOrBlank(),
                applyEnabled = visible && state != null && state.draftText.isNotBlank() && state.draftText != state.originalText,
            ),
        )
    }

    private fun isAgentStripAvailable(): Boolean {
        return KeyboardSettings.readAgentModeEnabled(this) && !isSensitiveEditor(currentEditorInfo)
    }

    private fun isSensitiveEditor(info: EditorInfo?): Boolean {
        val inputType = info?.inputType ?: return false
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun resolveEnterLabel(info: EditorInfo?): String {
        val imeOptions = info?.imeOptions ?: return "Enter"
        if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return "Enter"

        return when (imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_DONE -> "Done"
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_PREVIOUS -> "Prev"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> "Send"
            else -> "Enter"
        }
    }

    private fun keyCodeFor(char: Char): Int? {
        return when (char.lowercaseChar()) {
            in 'a'..'z' -> KeyEvent.KEYCODE_A + (char.lowercaseChar() - 'a')
            in '0'..'9' -> KeyEvent.KEYCODE_0 + (char - '0')
            ' ' -> KeyEvent.KEYCODE_SPACE
            '\t' -> KeyEvent.KEYCODE_TAB
            '\n' -> KeyEvent.KEYCODE_ENTER
            '/' -> KeyEvent.KEYCODE_SLASH
            '\\' -> KeyEvent.KEYCODE_BACKSLASH
            ',' -> KeyEvent.KEYCODE_COMMA
            '.' -> KeyEvent.KEYCODE_PERIOD
            '-' -> KeyEvent.KEYCODE_MINUS
            '=' -> KeyEvent.KEYCODE_EQUALS
            '\'' -> KeyEvent.KEYCODE_APOSTROPHE
            ';' -> KeyEvent.KEYCODE_SEMICOLON
            '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
            ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
            else -> null
        }
    }

    private fun ModifierState.toMetaState(): Int {
        var state = 0
        if (ctrl) state = state or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (alt) state = state or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        return state
    }

    private val ModifierState.hasHardwareMeta: Boolean
        get() = ctrl || alt

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private data class DraftState(
        val originalText: String,
        val draftText: String,
        val source: DraftSource,
        val editorAnchorText: String?,
    )

    private enum class DraftSource {
        Mirror,
        Manual,
        Suggestion,
        Rewrite,
    }

    private class LlmHttpException(
        val responseCode: Int,
        val responseText: String,
        detailMessage: String,
    ) : IOException(detailMessage)

    private fun LlmHttpException.isStrictSchemaUnsupported(): Boolean {
        if (responseCode !in setOf(400, 404, 422)) return false

        val normalized = responseText.lowercase(Locale.US)
        return normalized.contains("no endpoints found") ||
            normalized.contains("response_format") ||
            normalized.contains("json_schema") ||
            normalized.contains("structured output") ||
            normalized.contains("schema")
    }

    private enum class StructuredOutputMode {
        JsonSchema,
        JsonObject,
    }

    private enum class LlmRequestKind(
        val schemaName: String,
    ) {
        Rewrite("rewrite"),
        Autocomplete("autocomplete"),
    }

    private companion object {
        const val TAG = "ZnKeyboardAgent"
        const val MAX_CONTEXT_CHARS = 800
        const val MAX_DRAFT_CHARS = 600
        const val MIN_AUTOCOMPLETE_CHARS = 8
        const val AUTOCOMPLETE_DEBOUNCE_MS = 900L
        const val REQUEST_TIMEOUT_MS = 30_000
        const val MAX_AUTOCOMPLETE_LLM_TOKENS = 500
        const val MIN_REWRITE_LLM_TOKENS = 2_000
        const val MAX_REWRITE_LLM_TOKENS = 6_000
        const val CHARS_PER_OUTPUT_TOKEN_ESTIMATE = 2
        const val ERROR_PREVIEW_CHARS = 160
        const val STRUCTURED_OUTPUT_TEXT_FIELD = "text"
        const val AUTOCOMPLETE_PROMPT_ASSET = "prompts/autocomplete.md"
        const val REWRITE_BASE_PROMPT_ASSET = "prompts/rewrite_base.md"
        const val WHATSAPP_PROMPT_ASSET = "prompts/apps/whatsapp.md"
        const val WHATSAPP_PACKAGE_NAME = "com.whatsapp"
        const val WHATSAPP_BUSINESS_PACKAGE_NAME = "com.whatsapp.w4b"
        val THINK_BLOCK_REGEX = Regex("(?is)<think>.*?</think>")
        val REASONING_BLOCK_REGEX = Regex("(?is)<reasoning>.*?</reasoning>")
        val FALLBACK_REWRITE_PROMPT = """
            Improve or rewrite the user's text while preserving the original meaning and tone.
            Return a valid JSON object with exactly one field, text, containing only the rewritten text. Do not include labels, explanations, or prefaces in text.
        """.trimIndent()
        val FALLBACK_AUTOCOMPLETE_PROMPT = """
            Correct typos and light grammar in the user's current text while preserving meaning and style.
            If it is already good, return it unchanged.
            Return a valid JSON object with exactly one field, text, containing only the corrected text. Do not include labels, explanations, or prefaces in text.
        """.trimIndent()
    }
}
