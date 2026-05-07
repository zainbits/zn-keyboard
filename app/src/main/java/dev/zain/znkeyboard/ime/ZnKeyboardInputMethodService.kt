package dev.zain.znkeyboard.ime

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import dev.zain.znkeyboard.EmojiCatalog
import dev.zain.znkeyboard.EmojiSkinTone
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
    EmojiPanelView.Callback,
    EmojiSearchView.Callback,
    SnippetPanelView.Callback,
    AgentAssistStripView.Callback,
    AgentReviewView.Callback,
    AgentHistoryView.Callback {
    private var keyboardView: ZnKeyboardView? = null
    private var keyboardContainer: FrameLayout? = null
    private var emojiPanelView: EmojiPanelView? = null
    private var emojiSearchView: EmojiSearchView? = null
    private var snippetPanelView: SnippetPanelView? = null
    private var agentReviewView: AgentReviewView? = null
    private var agentHistoryView: AgentHistoryView? = null
    private var agentStripView: AgentAssistStripView? = null
    private var currentEditorInfo: EditorInfo? = null
    private var recentEmojis: List<String> = emptyList()
    private var recentEmojiRows = EmojiCatalog.DEFAULT_RECENT_ROW_COUNT
    private var defaultEmojiSkinTone = EmojiSkinTone.Default
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeSurface = KeyboardSurface.Keyboard
    private var activeRequestTarget: AgentEditTarget? = null
    private var activeReview: AgentReview? = null
    private var agentError: String? = null
    private var agentLoading = false
    private var requestGeneration = 0

    override fun onCreateInputView(): View {
        recentEmojis = KeyboardSettings.readRecentEmojis(this)
        recentEmojiRows = KeyboardSettings.readRecentEmojiRows(this)
        defaultEmojiSkinTone = KeyboardSettings.readEmojiSkinTone(this)
        val agentStrip = AgentAssistStripView(this).also { view ->
            agentStripView = view
            view.callback = this
        }
        val keyboard = ZnKeyboardView(this).also { view ->
            keyboardView = view
            view.callback = this
        }
        val emojiSearch = EmojiSearchView(this).also { view ->
            emojiSearchView = view
            view.callback = this
        }
        val keyboardSlot = FrameLayout(this).also { container ->
            keyboardContainer = container
            container.addView(
                keyboard,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                agentStrip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                emojiSearch,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                keyboardSlot,
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
        resetAgentState(returnToKeyboard = true)
        applyKeyboardSettings()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentEditorInfo = info
        resetAgentState(returnToKeyboard = true)
        applyKeyboardSettings()
        keyboardView?.setEnterLabel(resolveEnterLabel(info))
        renderAgentStrip()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        keyboardView?.clearLatchedModifiers()
        resetAgentState(returnToKeyboard = true)
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        requestGeneration++
        super.onDestroy()
    }

    override fun onKeyboardAction(action: KeyboardAction, modifiers: ModifierState) {
        if (activeSurface == KeyboardSurface.EmojiSearch && handleEmojiSearchKeyboardAction(action, modifiers)) {
            return
        }
        when (action) {
            KeyboardAction.Backspace -> handleBackspace(modifiers)
            KeyboardAction.Enter -> handleEnter(modifiers)
            is KeyboardAction.KeyCode -> {
                cancelActiveAgentForEditorChange()
                sendKey(action.keyCode, modifiers)
            }
            is KeyboardAction.Text -> handleText(action.value, modifiers)
        }
    }

    override fun onEmojiPanelRequested() {
        showEmojiPanel()
    }

    override fun onSnippetPanelRequested() {
        showSnippetPanel()
    }

    override fun onEmojiSelected(emoji: String, updatedRecentEmojis: List<String>) {
        handleText(emoji, ModifierState(ctrl = false, alt = false))
        persistRecentEmojis(updatedRecentEmojis)
    }

    override fun onEmojiSearchRequested() {
        showEmojiSearchPanel()
    }

    override fun onEmojiPanelClosed() {
        showKeyboardPanel()
    }

    override fun onEmojiBackspace() {
        handleBackspace(ModifierState(ctrl = false, alt = false))
    }

    override fun onEmojiSpace() {
        handleText(" ", ModifierState(ctrl = false, alt = false))
    }

    override fun onEmojiSearchClosed() {
        showEmojiPanel()
    }

    override fun onEmojiSearchEmojiSelected(emoji: String, updatedRecentEmojis: List<String>) {
        handleText(emoji, ModifierState(ctrl = false, alt = false))
        persistRecentEmojis(updatedRecentEmojis)
    }

    override fun onSnippetSelected(snippet: String) {
        handleText(snippet, ModifierState(ctrl = false, alt = false))
        showKeyboardPanel()
    }

    override fun onSnippetPanelClosed() {
        showKeyboardPanel()
    }

    override fun onSnippetBackspace() {
        handleBackspace(ModifierState(ctrl = false, alt = false))
    }

    override fun onSnippetSpace() {
        handleText(" ", ModifierState(ctrl = false, alt = false))
    }

    override fun onAgentRewriteRequested() {
        startRewriteFromCurrentEditor()
    }

    override fun onAgentHistoryRequested() {
        showAgentHistoryPanel()
    }

    override fun onAgentRowAction(action: KeyboardAction, modifiers: ModifierState) {
        onKeyboardAction(action, modifiers)
    }

    override fun onAgentReviewApply() {
        applyAgentReview()
    }

    override fun onAgentReviewCancel() {
        cancelAgentReview()
    }

    override fun onAgentHistoryClosed() {
        showKeyboardPanel()
    }

    private fun applyKeyboardSettings() {
        val heightScale = KeyboardSettings.readHeightScale(this)
        recentEmojiRows = KeyboardSettings.readRecentEmojiRows(this)
        defaultEmojiSkinTone = KeyboardSettings.readEmojiSkinTone(this)
        keyboardView?.let { view ->
            view.setHeightScale(heightScale)
            view.setUpperRowKeyIds(KeyboardSettings.readUpperRowKeyIds(this))
        }
        agentStripView?.setHeightScale(heightScale)
        agentStripView?.setAgentRowKeyIds(KeyboardSettings.readAgentRowKeyIds(this))
        emojiPanelView?.let { view ->
            view.setHeightScale(heightScale)
            view.setRecentEmojis(recentEmojis)
            view.setRecentRowCount(recentEmojiRows)
            view.setDefaultSkinTone(defaultEmojiSkinTone)
        }
        emojiSearchView?.let { view ->
            view.setRecentEmojis(recentEmojis)
            view.setDefaultSkinTone(defaultEmojiSkinTone)
        }
        snippetPanelView?.let { view ->
            view.setHeightScale(heightScale)
            view.setSnippets(KeyboardSettings.readTextSnippets(this))
        }
        agentReviewView?.setHeightScale(heightScale)
        agentHistoryView?.setHeightScale(heightScale)
        renderAgentStrip()
    }

    private fun showEmojiPanel() {
        hideEmojiSearchView()
        val panel = emojiPanelView ?: EmojiPanelView(this).also { view ->
            emojiPanelView = view
            view.callback = this
        }
        recentEmojis = KeyboardSettings.readRecentEmojis(this)
        recentEmojiRows = KeyboardSettings.readRecentEmojiRows(this)
        defaultEmojiSkinTone = KeyboardSettings.readEmojiSkinTone(this)
        panel.setHeightScale(KeyboardSettings.readHeightScale(this))
        panel.setRecentEmojis(recentEmojis)
        panel.setRecentRowCount(recentEmojiRows)
        panel.setDefaultSkinTone(defaultEmojiSkinTone)
        activeSurface = KeyboardSurface.Emoji
        swapKeyboardSurface(panel)
        renderAgentStrip()
    }

    private fun showEmojiSearchPanel() {
        val keyboard = keyboardView ?: return
        val search = emojiSearchView ?: return
        recentEmojis = KeyboardSettings.readRecentEmojis(this)
        defaultEmojiSkinTone = KeyboardSettings.readEmojiSkinTone(this)
        search.setRecentEmojis(recentEmojis)
        search.setDefaultSkinTone(defaultEmojiSkinTone)
        search.clearSearch()
        search.visibility = View.VISIBLE
        keyboard.setEnterLabel("Search")
        activeSurface = KeyboardSurface.EmojiSearch
        swapKeyboardSurface(keyboard)
        renderAgentStrip()
    }

    private fun showSnippetPanel() {
        hideEmojiSearchView()
        val panel = snippetPanelView ?: SnippetPanelView(this).also { view ->
            snippetPanelView = view
            view.callback = this
        }
        panel.setHeightScale(KeyboardSettings.readHeightScale(this))
        panel.setSnippets(KeyboardSettings.readTextSnippets(this))
        activeSurface = KeyboardSurface.Snippets
        swapKeyboardSurface(panel)
        renderAgentStrip()
    }

    private fun showKeyboardPanel() {
        hideEmojiSearchView()
        activeSurface = KeyboardSurface.Keyboard
        val keyboard = keyboardView ?: run {
            renderAgentStrip()
            return
        }
        keyboard.setEnterLabel(resolveEnterLabel(currentEditorInfo))
        swapKeyboardSurface(keyboard)
        renderAgentStrip()
    }

    private fun showAgentReviewPanel(review: AgentReview) {
        hideEmojiSearchView()
        val reviewView = agentReviewView ?: AgentReviewView(this).also { view ->
            agentReviewView = view
            view.callback = this
        }
        reviewView.setHeightScale(KeyboardSettings.readHeightScale(this))
        reviewView.render(review.replacementText)
        activeSurface = KeyboardSurface.Review
        swapKeyboardSurface(reviewView)
        renderAgentStrip()
    }

    private fun showAgentHistoryPanel() {
        hideEmojiSearchView()
        val historyView = agentHistoryView ?: AgentHistoryView(this).also { view ->
            agentHistoryView = view
            view.callback = this
        }
        historyView.setHeightScale(KeyboardSettings.readHeightScale(this))
        historyView.submitHistory(AgentRewriteHistoryStore.read(this))
        activeSurface = KeyboardSurface.History
        swapKeyboardSurface(historyView)
        renderAgentStrip()
    }

    private fun swapKeyboardSurface(surface: View) {
        val container = keyboardContainer ?: return
        (surface.parent as? ViewGroup)?.removeView(surface)
        container.removeAllViews()
        container.addView(
            surface,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun hideEmojiSearchView() {
        emojiSearchView?.let { view ->
            if (view.visibility != View.GONE) {
                view.visibility = View.GONE
            }
            view.clearSearch()
        }
    }

    private fun persistRecentEmojis(emojis: List<String>) {
        val normalizedEmojis = EmojiCatalog.normalizeRecentEmojis(emojis)
        recentEmojis = normalizedEmojis
        emojiPanelView?.setRecentEmojis(normalizedEmojis)
        emojiSearchView?.setRecentEmojis(normalizedEmojis)
        mainHandler.post {
            KeyboardSettings.saveRecentEmojis(this, normalizedEmojis)
        }
    }

    private fun handleEmojiSearchKeyboardAction(action: KeyboardAction, modifiers: ModifierState): Boolean {
        if (modifiers.hasHardwareMeta) {
            return true
        }
        val searchView = emojiSearchView ?: return true
        when (action) {
            KeyboardAction.Backspace -> searchView.deleteQueryCharacter()
            KeyboardAction.Enter -> Unit
            is KeyboardAction.KeyCode -> {
                if (action.keyCode == KeyEvent.KEYCODE_DEL) {
                    searchView.deleteQueryCharacter()
                }
            }
            is KeyboardAction.Text -> searchView.appendQueryText(action.value)
        }
        return true
    }

    private fun handleText(value: String, modifiers: ModifierState) {
        val inputConnection = currentInputConnection ?: return
        cancelActiveAgentForEditorChange()
        if (modifiers.hasHardwareMeta && value.length == 1) {
            val keyCode = keyCodeFor(value[0])
            if (keyCode != null) {
                sendKey(keyCode, modifiers)
                return
            }
        }
        inputConnection.commitText(value, 1)
    }

    private fun handleBackspace(modifiers: ModifierState) {
        val inputConnection = currentInputConnection ?: return
        cancelActiveAgentForEditorChange()
        // TYPE_NULL editors such as terminals expect raw key events, not surrounding-text edits.
        if (isRawKeyEventEditor(currentEditorInfo)) {
            sendKey(KeyEvent.KEYCODE_DEL, modifiers)
            return
        }

        val selectedText = inputConnection.getSelectedText(0)
        if (!selectedText.isNullOrEmpty()) {
            inputConnection.commitText("", 1)
        } else {
            if (!inputConnection.deleteSurroundingTextInCodePoints(1, 0)) {
                if (!inputConnection.deleteSurroundingText(1, 0)) {
                    sendKey(KeyEvent.KEYCODE_DEL, modifiers)
                }
            }
        }
    }

    private fun handleEnter(modifiers: ModifierState) {
        val inputConnection = currentInputConnection ?: return
        cancelActiveAgentForEditorChange()
        val info = currentEditorInfo
        val imeOptions = info?.imeOptions ?: 0
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val forceEnter = imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0

        if (!forceEnter && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            inputConnection.performEditorAction(action)
        } else {
            sendKey(KeyEvent.KEYCODE_ENTER, modifiers)
        }
    }

    private fun sendKey(keyCode: Int, modifiers: ModifierState) {
        val inputConnection = currentInputConnection ?: return
        val downTime = SystemClock.uptimeMillis()
        val metaState = modifiers.toMetaState()
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        inputConnection.sendKeyEvent(
            KeyEvent(
                downTime,
                downTime,
                KeyEvent.ACTION_DOWN,
                keyCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                flags,
            ),
        )
        inputConnection.sendKeyEvent(
            KeyEvent(
                downTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                keyCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                flags,
            ),
        )
    }

    private fun startRewriteFromCurrentEditor() {
        val target = captureRewriteTarget() ?: return
        startLlmRequest(
            target = target,
            requestText = target.originalText,
            selectTargetForRequest = true,
        )
    }

    private fun applyAgentReview() {
        val review = activeReview ?: return
        val inputConnection = currentInputConnection ?: return

        if (!isTargetStillCurrent(review.target)) {
            activeReview = null
            showKeyboardPanel()
            showAgentError("Text changed. Run rewrite again.")
            return
        }

        var committed = false
        inputConnection.beginBatchEdit()
        try {
            if (!inputConnection.setSelection(review.target.replaceStart, review.target.replaceEnd)) {
                showAgentError("Couldn't select the original text.")
                return
            }
            committed = inputConnection.commitText(review.replacementText, 1)
        } finally {
            inputConnection.endBatchEdit()
        }

        if (!committed) {
            showAgentError("Couldn't apply the rewrite.")
            return
        }

        AgentRewriteHistoryStore.recordReplacement(this, review.target.originalText)
        requestGeneration++
        agentError = null
        agentLoading = false
        activeRequestTarget = null
        activeReview = null
        showKeyboardPanel()
    }

    private fun cancelAgentReview() {
        activeReview?.target?.let(::restoreSelectionIfNeeded)
        activeReview = null
        activeRequestTarget = null
        agentError = null
        agentLoading = false
        showKeyboardPanel()
    }

    private fun captureRewriteTarget(): AgentEditTarget? {
        val snapshot = captureEditorSnapshot(MAX_REWRITE_SOURCE_CHARS + 1) ?: run {
            showAgentError("Couldn't read the current field.")
            return null
        }
        if (snapshot.textStartOffset != 0) {
            showAgentError("Couldn't read the full field.")
            return null
        }
        if (snapshot.text.length > MAX_REWRITE_SOURCE_CHARS) {
            showAgentError("Text is too long to rewrite.")
            return null
        }
        if (snapshot.text.isBlank()) {
            showAgentError("No text to rewrite.")
            return null
        }

        return AgentEditTarget(
            originalText = snapshot.text,
            replaceStart = snapshot.textStartOffset,
            replaceEnd = snapshot.textStartOffset + snapshot.text.length,
            restoreSelectionStart = snapshot.selectionStart,
            restoreSelectionEnd = snapshot.selectionEnd,
            selectionWasChangedForRequest = true,
        )
    }

    private fun captureEditorSnapshot(maxChars: Int): EditorSnapshot? {
        val inputConnection = currentInputConnection ?: return null
        val extracted = inputConnection.getExtractedText(
            ExtractedTextRequest().apply {
                hintMaxChars = maxChars
            },
            0,
        ) ?: return null
        val text = extracted.text?.toString() ?: return null
        val textStartOffset = extracted.startOffset.coerceAtLeast(0)
        return EditorSnapshot(
            text = text,
            textStartOffset = textStartOffset,
            selectionStart = absoluteExtractedOffset(extracted.selectionStart, textStartOffset, text.length),
            selectionEnd = absoluteExtractedOffset(extracted.selectionEnd, textStartOffset, text.length),
        )
    }

    private fun absoluteExtractedOffset(offset: Int, textStartOffset: Int, textLength: Int): Int {
        if (offset < 0) return textStartOffset + textLength
        val textEndOffset = textStartOffset + textLength
        return when (offset) {
            in textStartOffset..textEndOffset -> offset
            else -> textStartOffset + offset.coerceIn(0, textLength)
        }
    }

    private fun selectTargetText(target: AgentEditTarget): Boolean {
        val inputConnection = currentInputConnection ?: return false
        return inputConnection.setSelection(target.replaceStart, target.replaceEnd)
    }

    private fun restoreSelectionIfNeeded(target: AgentEditTarget) {
        if (!target.selectionWasChangedForRequest) return
        val inputConnection = currentInputConnection ?: return
        val snapshot = captureEditorSnapshot(MAX_REWRITE_SOURCE_CHARS + 1)
        val maxOffset = snapshot?.let { it.textStartOffset + it.text.length } ?: target.replaceEnd
        val start = target.restoreSelectionStart.coerceIn(0, maxOffset)
        val end = target.restoreSelectionEnd.coerceIn(0, maxOffset)
        inputConnection.setSelection(start, end)
    }

    private fun isTargetStillCurrent(target: AgentEditTarget): Boolean {
        val snapshot = captureEditorSnapshot(MAX_REWRITE_SOURCE_CHARS + 1) ?: return true
        val localStart = target.replaceStart - snapshot.textStartOffset
        val localEnd = target.replaceEnd - snapshot.textStartOffset
        if (localStart < 0 || localEnd > snapshot.text.length || localStart > localEnd) {
            return false
        }
        return snapshot.text.substring(localStart, localEnd) == target.originalText
    }

    private fun cancelActiveAgentForEditorChange() {
        if (!agentLoading && activeRequestTarget == null && activeReview == null) {
            if (agentError != null) {
                agentError = null
                renderAgentStrip()
            }
            return
        }
        requestGeneration++
        activeRequestTarget = null
        activeReview = null
        agentError = null
        agentLoading = false
        if (activeSurface == KeyboardSurface.Review) {
            showKeyboardPanel()
        } else {
            renderAgentStrip()
        }
    }

    private fun startLlmRequest(
        target: AgentEditTarget,
        requestText: String,
        selectTargetForRequest: Boolean,
    ) {
        if (!isAgentStripAvailable()) return
        val providerSettings = KeyboardSettings.readAgentProviderSettings(this)
        if (!providerSettings.isConfigured) {
            showAgentError("Add API settings first.")
            return
        }
        if (selectTargetForRequest && !selectTargetText(target)) {
            showAgentError("Couldn't select the current text.")
            return
        }

        val generation = ++requestGeneration
        agentError = null
        agentLoading = true
        activeRequestTarget = target
        Log.d(TAG, "Starting rewrite request for ${requestText.length} chars")
        renderAgentStrip()

        Thread {
            val result = runCatching {
                requestChatCompletion(
                    providerSettings = providerSettings,
                    text = requestText,
                )
            }

            mainHandler.post {
                if (generation != requestGeneration || !isAgentStripAvailable()) return@post
                agentLoading = false
                activeRequestTarget = null
                result
                    .onSuccess { content ->
                        applyLlmResult(target, content)
                    }
                    .onFailure { error ->
                        restoreSelectionIfNeeded(target)
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
        text: String,
    ): String {
        val endpoint = buildChatCompletionsUrl(providerSettings)
        var strictSchemaError: IOException? = null

        for (outputMode in StructuredOutputMode.entries) {
            try {
                return executeChatCompletionRequest(
                    endpoint = endpoint,
                    providerSettings = providerSettings,
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
                    .put(JSONObject().put("role", "system").put("content", rewriteSystemPrompt()))
                    .put(JSONObject().put("role", "user").put("content", text)),
            )
            .put("temperature", 0.4)
            .put("max_tokens", maxTokensFor(text))
            .put("response_format", structuredResponseFormatFor(outputMode))
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

    private fun maxTokensFor(text: String): Int {
        val estimatedOutputTokens = (text.length / CHARS_PER_OUTPUT_TOKEN_ESTIMATE)
            .coerceAtLeast(MIN_REWRITE_LLM_TOKENS)
        return estimatedOutputTokens.coerceAtMost(MAX_REWRITE_LLM_TOKENS)
    }

    private fun structuredResponseFormatFor(outputMode: StructuredOutputMode): JSONObject {
        if (outputMode == StructuredOutputMode.JsonObject) {
            return JSONObject().put("type", "json_object")
        }

        return JSONObject()
            .put("type", "json_schema")
            .put(
                "json_schema",
                JSONObject()
                    .put("name", "znkeyboard_rewrite_response")
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
                                            .put(
                                                "description",
                                                "The final rewritten text only, without labels, explanations, or prefaces.",
                                            ),
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

    private fun applyLlmResult(target: AgentEditTarget, content: String) {
        val normalized = AsciiTextSanitizer.toAscii(content).trim()
        if (normalized.isBlank() || normalized.equals("null", ignoreCase = true)) {
            showAgentError("No rewrite returned.")
            return
        }

        if (normalized == target.originalText.trim()) {
            restoreSelectionIfNeeded(target)
            showAgentError("No changes returned.")
            return
        }

        agentError = null
        activeReview = AgentReview(
            target = target,
            replacementText = normalized,
        )
        activeReview?.let(::showAgentReviewPanel)
    }

    private fun showAgentError(message: String) {
        agentLoading = false
        agentError = message
        Log.w(TAG, message)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        renderAgentStrip()
    }

    private fun resetAgentState(returnToKeyboard: Boolean = false) {
        requestGeneration++
        activeRequestTarget = null
        activeReview = null
        agentError = null
        agentLoading = false
        if (returnToKeyboard) {
            showKeyboardPanel()
        } else {
            renderAgentStrip()
        }
    }

    private fun renderAgentStrip() {
        val visible = isAgentStripAvailable() &&
            activeSurface != KeyboardSurface.Emoji &&
            activeSurface != KeyboardSurface.EmojiSearch &&
            activeSurface != KeyboardSurface.Review &&
            activeSurface != KeyboardSurface.History
        val providerConfigured = KeyboardSettings.readAgentProviderSettings(this).isConfigured

        agentStripView?.render(
            AgentAssistStripView.State(
                visible = visible,
                loading = agentLoading,
                rewriteEnabled = visible && providerConfigured,
                historyEnabled = visible,
            ),
        )
    }

    private fun isAgentStripAvailable(): Boolean {
        return KeyboardSettings.readAgentModeEnabled(this) && !isSensitiveEditor(currentEditorInfo)
    }

    private fun isRawKeyEventEditor(info: EditorInfo?): Boolean {
        val inputType = info?.inputType ?: return false
        return (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_NULL
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

    private data class EditorSnapshot(
        val text: String,
        val textStartOffset: Int,
        val selectionStart: Int,
        val selectionEnd: Int,
    )

    private data class AgentEditTarget(
        val originalText: String,
        val replaceStart: Int,
        val replaceEnd: Int,
        val restoreSelectionStart: Int,
        val restoreSelectionEnd: Int,
        val selectionWasChangedForRequest: Boolean,
    )

    private data class AgentReview(
        val target: AgentEditTarget,
        val replacementText: String,
    )

    private enum class KeyboardSurface {
        Keyboard,
        Emoji,
        EmojiSearch,
        Snippets,
        Review,
        History,
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

    private companion object {
        const val TAG = "ZnKeyboardAgent"
        const val MAX_REWRITE_SOURCE_CHARS = 12_000
        const val REQUEST_TIMEOUT_MS = 30_000
        const val MIN_REWRITE_LLM_TOKENS = 2_000
        const val MAX_REWRITE_LLM_TOKENS = 6_000
        const val CHARS_PER_OUTPUT_TOKEN_ESTIMATE = 2
        const val ERROR_PREVIEW_CHARS = 160
        const val STRUCTURED_OUTPUT_TEXT_FIELD = "text"
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
    }
}
