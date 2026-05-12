package dev.zain.znkeyboard.ime

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.zain.znkeyboard.EmojiCatalog
import dev.zain.znkeyboard.EmojiSkinTone
import dev.zain.znkeyboard.KeyboardSettings
import dev.zain.znkeyboard.constants.AgentDefaults
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
    GifSearchView.Callback,
    SnippetPanelView.Callback,
    SavedTextSearchView.Callback,
    AgentReviewView.Callback {
    private var keyboardView: ZnKeyboardView? = null
    private var inputRoot: LinearLayout? = null
    private var keyboardContainer: FrameLayout? = null
    private var emojiPanelView: EmojiPanelView? = null
    private var emojiSearchView: EmojiSearchView? = null
    private var gifSearchView: GifSearchView? = null
    private var snippetPanelView: SnippetPanelView? = null
    private var savedTextSearchView: SavedTextSearchView? = null
    private var agentReviewView: AgentReviewView? = null
    private var currentEditorInfo: EditorInfo? = null
    private var recentEmojis: List<String> = emptyList()
    private var recentEmojiRows = EmojiCatalog.DEFAULT_RECENT_ROW_COUNT
    private var defaultEmojiSkinTone = EmojiSkinTone.Default
    private var customEmojiTags: Map<String, List<String>> = emptyMap()
    private var emojiSuggestionTags: List<EmojiSuggestionTag> = emptyList()
    private var emojiKeySuggestion: String? = null
    private var pendingEmojiSuggestionRefresh: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeSurface = KeyboardSurface.Keyboard
    private var activeRequestTarget: AgentEditTarget? = null
    private var activeReview: AgentReview? = null
    private var agentError: String? = null
    private var agentLoading = false
    private var requestGeneration = 0
    private var gifShareGeneration = 0
    private var backspaceGestureDeleteState: BackspaceGestureDeleteState? = null
    private var lastCapturedClipboardSnapshot: ClipboardSnapshot? = null
    private val clipboardChangeListener = ClipboardManager.OnPrimaryClipChangedListener {
        capturePrimaryClipboardText()
    }
    private var clipboardListenerRegistered = false
    private val clipboardManager: ClipboardManager by lazy(LazyThreadSafetyMode.NONE) {
        getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    override fun onCreate() {
        super.onCreate()
        ClipboardHistoryStore.deleteLegacyRewriteHistory(this)
        registerClipboardListener()
        capturePrimaryClipboardText()
    }

    override fun onCreateInputView(): View {
        gifSearchView?.dispose()
        gifSearchView = null
        recentEmojis = KeyboardSettings.readRecentEmojis(this)
        recentEmojiRows = KeyboardSettings.readRecentEmojiRows(this)
        defaultEmojiSkinTone = KeyboardSettings.readEmojiSkinTone(this)
        customEmojiTags = KeyboardSettings.readCustomEmojiTags(this)
        rebuildEmojiSuggestionTags()
        val keyboard = ZnKeyboardView(this).also { view ->
            keyboardView = view
            view.callback = this
            view.setEmojiKeySuggestion(emojiKeySuggestion)
        }
        val emojiSearch = EmojiSearchView(this).also { view ->
            emojiSearchView = view
            view.callback = this
        }
        val gifSearch = GifSearchView(this).also { view ->
            gifSearchView = view
            view.callback = this
            view.setProviderSettings(KeyboardSettings.readGifProviderSettings(this))
        }
        val savedTextSearch = SavedTextSearchView(this).also { view ->
            savedTextSearchView = view
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
            inputRoot = this
            orientation = LinearLayout.VERTICAL
            addView(
                emojiSearch,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                gifSearch,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                savedTextSearch,
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
            renderSecondRow()
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        currentEditorInfo = attribute
        resetAgentState(returnToKeyboard = true)
        applyKeyboardSettings()
        scheduleEmojiSuggestionRefresh(delayMillis = 0L)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentEditorInfo = info
        resetAgentState(returnToKeyboard = true)
        applyKeyboardSettings()
        keyboardView?.setEnterLabel(resolveEnterLabel(info))
        renderSecondRow()
        capturePrimaryClipboardText()
        scheduleEmojiSuggestionRefresh(delayMillis = 0L)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        keyboardView?.clearLatchedModifiers()
        cancelEmojiSuggestionRefresh()
        setEmojiKeySuggestion(null)
        resetAgentState(returnToKeyboard = true)
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        requestGeneration++
        gifShareGeneration++
        unregisterClipboardListener()
        cancelEmojiSuggestionRefresh()
        gifSearchView?.dispose()
        gifSearchView = null
        super.onDestroy()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (oldSelStart != newSelStart || oldSelEnd != newSelEnd) {
            scheduleEmojiSuggestionRefresh()
        }
    }

    override fun onKeyboardAction(action: KeyboardAction, modifiers: ModifierState) {
        if (activeSurface == KeyboardSurface.EmojiSearch && handleEmojiSearchKeyboardAction(action, modifiers)) {
            return
        }
        if (activeSurface == KeyboardSurface.GifSearch && handleGifSearchKeyboardAction(action, modifiers)) {
            return
        }
        if (activeSurface == KeyboardSurface.SavedTextSearch && handleSavedTextSearchKeyboardAction(action, modifiers)) {
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

    override fun onBackspaceGestureDeleteStarted(): Boolean {
        return startBackspaceGestureDelete()
    }

    override fun onBackspaceGestureDeleteChanged(wordCount: Int) {
        updateBackspaceGestureDeleteSelection(wordCount)
    }

    override fun onBackspaceGestureDeleteFinished() {
        finishBackspaceGestureDelete()
    }

    override fun onBackspaceGestureDeleteCancelled() {
        cancelBackspaceGestureDelete()
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

    override fun onGifPanelRequested() {
        if (isSensitiveEditor(currentEditorInfo)) {
            Toast.makeText(this, "GIFs are disabled in password fields.", Toast.LENGTH_SHORT).show()
            return
        }
        showGifPanel()
    }

    override fun onGifKeyboardRequested() {
        showKeyboardPanel()
    }

    override fun onGifEmojiRequested() {
        showEmojiPanel()
    }

    override fun onGifSearchRequested() {
        showGifSearchPanel()
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

    override fun onGifSearchClosed() {
        showKeyboardPanel()
    }

    override fun onGifSearchBackToBrowse() {
        showGifPanel(keepCurrentResults = true)
    }

    override fun onGifBackspace() {
        handleBackspace(ModifierState(ctrl = false, alt = false))
    }

    override fun onGifSelected(gif: GifSearchResult) {
        sendGif(gif)
    }

    override fun onEmojiKeySuggestionSelected(emoji: String) {
        handleText(emoji, ModifierState(ctrl = false, alt = false))
        persistRecentEmojis(EmojiCatalog.promoteRecentEmoji(emoji, recentEmojis))
    }

    override fun onSnippetSelected(snippet: String) {
        handleText(snippet, ModifierState(ctrl = false, alt = false))
        showKeyboardPanel()
    }

    override fun onSnippetSearchRequested(tab: KeyboardSettings.SavedTextPanelTab) {
        showSavedTextSearchPanel(tab)
    }

    override fun onSnippetSaveCurrentInput() {
        saveCurrentInputAsSnippet()
    }

    override fun onSnippetPanelClosed() {
        showKeyboardPanel()
    }

    override fun onSavedTextPanelTabChanged(tab: KeyboardSettings.SavedTextPanelTab) {
        KeyboardSettings.saveSavedTextPanelTab(this, tab)
    }

    override fun onSavedTextSearchClosed() {
        showSnippetPanel()
    }

    override fun onSavedTextSearchSnippetSelected(snippet: String) {
        handleText(snippet, ModifierState(ctrl = false, alt = false))
        showKeyboardPanel()
    }

    override fun onSavedTextSearchClipboardSelected(text: String) {
        pasteClipboardHistoryText(text)
    }

    override fun onSavedTextSearchClipboardDeleted(text: String) {
        deleteClipboardHistoryText(text)
    }

    override fun onAgentRewriteRequested() {
        startRewriteFromCurrentEditor()
    }

    override fun onAgentReviewApply() {
        applyAgentReview()
    }

    override fun onAgentReviewCancel() {
        cancelAgentReview()
    }

    override fun onClipboardHistoryItemSelected(text: String) {
        pasteClipboardHistoryText(text)
    }

    override fun onClipboardHistoryItemDeleted(text: String) {
        deleteClipboardHistoryText(text)
    }

    private fun applyKeyboardSettings() {
        val heightScale = KeyboardSettings.readHeightScale(this)
        recentEmojiRows = KeyboardSettings.readRecentEmojiRows(this)
        val updatedDefaultEmojiSkinTone = KeyboardSettings.readEmojiSkinTone(this)
        val updatedCustomEmojiTags = KeyboardSettings.readCustomEmojiTags(this)
        val suggestionInputsChanged = defaultEmojiSkinTone != updatedDefaultEmojiSkinTone ||
            customEmojiTags != updatedCustomEmojiTags
        defaultEmojiSkinTone = updatedDefaultEmojiSkinTone
        customEmojiTags = updatedCustomEmojiTags
        if (suggestionInputsChanged) {
            rebuildEmojiSuggestionTags()
            scheduleEmojiSuggestionRefresh(delayMillis = 0L)
        }
        keyboardView?.let { view ->
            view.setHeightScale(heightScale)
            view.setUpperRowKeyIds(KeyboardSettings.readUpperRowKeyIds(this))
            view.setSecondRowButtonIds(KeyboardSettings.readSecondRowButtonIds(this))
            view.setKeyboardRowOrder(KeyboardSettings.readKeyboardRowOrder(this))
        }
        emojiPanelView?.let { view ->
            view.setHeightScale(heightScale)
            if (activeSurface != KeyboardSurface.Emoji) {
                view.setRecentEmojis(recentEmojis)
            }
            view.setRecentRowCount(recentEmojiRows)
            view.setDefaultSkinTone(defaultEmojiSkinTone)
        }
        emojiSearchView?.let { view ->
            view.setRecentEmojis(recentEmojis)
            view.setDefaultSkinTone(defaultEmojiSkinTone)
            view.setCustomEmojiTags(customEmojiTags)
        }
        gifSearchView?.let { view ->
            view.setHeightScale(heightScale)
            view.setProviderSettings(KeyboardSettings.readGifProviderSettings(this))
        }
        snippetPanelView?.let { view ->
            view.setHeightScale(heightScale)
            view.setSnippets(KeyboardSettings.readTextSnippets(this))
            view.setClipboardEnabled(!isSensitiveEditor(currentEditorInfo))
            view.submitClipboardHistory(ClipboardHistoryStore.read(this))
        }
        savedTextSearchView?.let { view ->
            view.setSnippets(KeyboardSettings.readTextSnippets(this))
            view.setClipboardEntries(ClipboardHistoryStore.read(this))
        }
        agentReviewView?.setHeightScale(heightScale)
        renderSecondRow()
    }

    private fun showEmojiPanel() {
        hideEmojiSearchView()
        hideGifSearchView()
        hideSavedTextSearchView()
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
        renderSecondRow()
    }

    private fun showEmojiSearchPanel() {
        hideGifSearchView()
        hideSavedTextSearchView()
        val keyboard = keyboardView ?: return
        val search = emojiSearchView ?: return
        recentEmojis = KeyboardSettings.readRecentEmojis(this)
        defaultEmojiSkinTone = KeyboardSettings.readEmojiSkinTone(this)
        customEmojiTags = KeyboardSettings.readCustomEmojiTags(this)
        search.setRecentEmojis(recentEmojis)
        search.setDefaultSkinTone(defaultEmojiSkinTone)
        search.setCustomEmojiTags(customEmojiTags)
        search.clearSearch()
        search.visibility = View.VISIBLE
        keyboard.setEnterLabel("Search")
        activeSurface = KeyboardSurface.EmojiSearch
        swapKeyboardSurface(keyboard)
        renderSecondRow()
    }

    private fun showGifPanel(keepCurrentResults: Boolean = false) {
        hideEmojiSearchView()
        hideSavedTextSearchView()
        val panel = gifSearchView ?: return
        panel.setHeightScale(KeyboardSettings.readHeightScale(this))
        panel.setProviderSettings(KeyboardSettings.readGifProviderSettings(this))
        panel.visibility = View.VISIBLE
        activeSurface = KeyboardSurface.Gif
        swapKeyboardSurface(panel)
        if (keepCurrentResults) {
            panel.showCurrentResultsBrowseMode()
        } else {
            panel.showBrowseMode()
        }
        renderSecondRow()
    }

    private fun showGifSearchPanel() {
        hideEmojiSearchView()
        hideSavedTextSearchView()
        val keyboard = keyboardView ?: return
        val search = gifSearchView ?: return
        search.setHeightScale(KeyboardSettings.readHeightScale(this))
        search.setProviderSettings(KeyboardSettings.readGifProviderSettings(this))
        attachAboveKeyboard(search)
        search.visibility = View.VISIBLE
        search.showSearchMode()
        keyboard.setEnterLabel("Search")
        activeSurface = KeyboardSurface.GifSearch
        swapKeyboardSurface(keyboard)
        renderSecondRow()
    }

    private fun showSnippetPanel() {
        hideEmojiSearchView()
        hideGifSearchView()
        hideSavedTextSearchView()
        capturePrimaryClipboardText()
        val panel = snippetPanelView ?: SnippetPanelView(this).also { view ->
            snippetPanelView = view
            view.callback = this
        }
        val sensitiveEditor = isSensitiveEditor(currentEditorInfo)
        val selectedTab = KeyboardSettings.readSavedTextPanelTab(this)
            .takeUnless { sensitiveEditor && it == KeyboardSettings.SavedTextPanelTab.Clipboard }
            ?: KeyboardSettings.SavedTextPanelTab.Snippets
        panel.setHeightScale(KeyboardSettings.readHeightScale(this))
        panel.setClipboardEnabled(!sensitiveEditor)
        panel.setSnippets(KeyboardSettings.readTextSnippets(this))
        panel.submitClipboardHistory(ClipboardHistoryStore.read(this))
        panel.setSelectedTab(selectedTab)
        activeSurface = KeyboardSurface.Snippets
        swapKeyboardSurface(panel)
        renderSecondRow()
    }

    private fun showSavedTextSearchPanel(tab: KeyboardSettings.SavedTextPanelTab) {
        if (tab == KeyboardSettings.SavedTextPanelTab.Clipboard && isSensitiveEditor(currentEditorInfo)) {
            return
        }
        hideEmojiSearchView()
        hideGifSearchView()
        val keyboard = keyboardView ?: return
        val search = savedTextSearchView ?: return
        capturePrimaryClipboardText()
        KeyboardSettings.saveSavedTextPanelTab(this, tab)
        search.setSnippets(KeyboardSettings.readTextSnippets(this))
        search.setClipboardEntries(ClipboardHistoryStore.read(this))
        search.showForTab(tab)
        search.visibility = View.VISIBLE
        keyboard.setEnterLabel("Search")
        activeSurface = KeyboardSurface.SavedTextSearch
        swapKeyboardSurface(keyboard)
        renderSecondRow()
    }

    private fun saveCurrentInputAsSnippet() {
        if (isSensitiveEditor(currentEditorInfo)) {
            Toast.makeText(this, "Snippets are disabled in password fields.", Toast.LENGTH_SHORT).show()
            return
        }

        val snapshot = captureEditorSnapshot(KeyboardSettings.MAX_TEXT_SNIPPET_CHARS + 1) ?: run {
            Toast.makeText(this, "Couldn't read the current field.", Toast.LENGTH_SHORT).show()
            return
        }
        if (snapshot.textStartOffset != 0) {
            Toast.makeText(this, "Couldn't read the full field.", Toast.LENGTH_SHORT).show()
            return
        }

        val snippet = KeyboardSettings.textSnippetFromText(snapshot.text)
        if (snippet.text.isBlank()) {
            Toast.makeText(this, "No text to save.", Toast.LENGTH_SHORT).show()
            return
        }

        val snippets = KeyboardSettings.readTextSnippets(this)
        if (snippets.any { it.text == snippet.text }) {
            Toast.makeText(this, "Snippet already saved.", Toast.LENGTH_SHORT).show()
            return
        }
        if (snippets.size >= KeyboardSettings.MAX_TEXT_SNIPPETS) {
            Toast.makeText(this, "Snippet limit reached.", Toast.LENGTH_SHORT).show()
            return
        }

        val updatedSnippets = snippets + snippet
        KeyboardSettings.saveTextSnippets(this, updatedSnippets)
        snippetPanelView?.setSnippets(updatedSnippets)
        savedTextSearchView?.setSnippets(updatedSnippets)
        Toast.makeText(this, "Snippet saved.", Toast.LENGTH_SHORT).show()
    }

    private fun showKeyboardPanel() {
        hideEmojiSearchView()
        hideGifSearchView()
        hideSavedTextSearchView()
        activeSurface = KeyboardSurface.Keyboard
        val keyboard = keyboardView ?: run {
            renderSecondRow()
            return
        }
        keyboard.setEnterLabel(resolveEnterLabel(currentEditorInfo))
        swapKeyboardSurface(keyboard)
        renderSecondRow()
    }

    private fun showAgentReviewPanel(review: AgentReview) {
        hideEmojiSearchView()
        hideGifSearchView()
        hideSavedTextSearchView()
        val reviewView = agentReviewView ?: AgentReviewView(this).also { view ->
            agentReviewView = view
            view.callback = this
        }
        reviewView.setHeightScale(KeyboardSettings.readHeightScale(this))
        reviewView.render(review.replacementText)
        activeSurface = KeyboardSurface.Review
        swapKeyboardSurface(reviewView)
        renderSecondRow()
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

    private fun attachAboveKeyboard(surface: View) {
        val root = inputRoot ?: return
        val container = keyboardContainer ?: return
        (surface.parent as? ViewGroup)?.removeView(surface)
        val keyboardIndex = root.indexOfChild(container).takeIf { it >= 0 } ?: root.childCount
        root.addView(
            surface,
            keyboardIndex,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
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

    private fun hideGifSearchView() {
        gifSearchView?.let { view ->
            if (view.visibility != View.GONE) {
                view.visibility = View.GONE
            }
        }
    }

    private fun hideSavedTextSearchView() {
        savedTextSearchView?.let { view ->
            if (view.visibility != View.GONE) {
                view.visibility = View.GONE
            }
            view.clearSearch()
        }
    }

    private fun persistRecentEmojis(emojis: List<String>) {
        val normalizedEmojis = EmojiCatalog.normalizeRecentEmojis(emojis)
        recentEmojis = normalizedEmojis
        if (activeSurface != KeyboardSurface.Emoji) {
            emojiPanelView?.setRecentEmojis(normalizedEmojis)
        }
        emojiSearchView?.setRecentEmojis(normalizedEmojis)
        mainHandler.post {
            KeyboardSettings.saveRecentEmojis(this, normalizedEmojis)
        }
    }

    private fun rebuildEmojiSuggestionTags() {
        emojiSuggestionTags = buildList {
            customEmojiTags.forEach { (emoji, tags) ->
                val entry = EmojiCatalog.entryForEmoji(emoji) ?: return@forEach
                val displayEmoji = EmojiCatalog.displayEmoji(entry, defaultEmojiSkinTone)
                tags.forEach { tag ->
                    val tokens = tokenizeEmojiSuggestionText(tag)
                    if (tokens.isNotEmpty()) {
                        add(EmojiSuggestionTag(emoji = displayEmoji, tokens = tokens))
                    }
                }
            }
        }
    }

    private fun scheduleEmojiSuggestionRefresh(delayMillis: Long = EMOJI_SUGGESTION_REFRESH_DELAY_MS) {
        cancelEmojiSuggestionRefresh()
        if (isSensitiveEditor(currentEditorInfo) || emojiSuggestionTags.isEmpty()) {
            setEmojiKeySuggestion(null)
            return
        }

        val refresh = Runnable {
            pendingEmojiSuggestionRefresh = null
            refreshEmojiSuggestion()
        }
        pendingEmojiSuggestionRefresh = refresh
        if (delayMillis <= 0L) {
            mainHandler.post(refresh)
        } else {
            mainHandler.postDelayed(refresh, delayMillis)
        }
    }

    private fun cancelEmojiSuggestionRefresh() {
        pendingEmojiSuggestionRefresh?.let(mainHandler::removeCallbacks)
        pendingEmojiSuggestionRefresh = null
    }

    private fun refreshEmojiSuggestion() {
        if (isSensitiveEditor(currentEditorInfo) || emojiSuggestionTags.isEmpty()) {
            setEmojiKeySuggestion(null)
            return
        }

        val beforeCursor = currentInputConnection
            ?.getTextBeforeCursor(EMOJI_SUGGESTION_CONTEXT_CHARS, 0)
            ?.toString()
            .orEmpty()
        setEmojiKeySuggestion(findEmojiSuggestionForContext(beforeCursor))
    }

    private fun setEmojiKeySuggestion(emoji: String?) {
        if (emojiKeySuggestion == emoji) return
        emojiKeySuggestion = emoji
        keyboardView?.setEmojiKeySuggestion(emoji)
    }

    private fun findEmojiSuggestionForContext(context: String): String? {
        val contextTokens = tokenizeEmojiSuggestionText(context)
        if (contextTokens.isEmpty()) return null

        var bestMatch: EmojiSuggestionMatch? = null
        emojiSuggestionTags.forEach { suggestion ->
            val startIndex = contextTokens.lastIndexOfSequence(suggestion.tokens)
            if (startIndex < 0) return@forEach

            val match = EmojiSuggestionMatch(
                emoji = suggestion.emoji,
                startIndex = startIndex,
                endIndex = startIndex + suggestion.tokens.lastIndex,
            )
            val currentBest = bestMatch
            if (
                currentBest == null ||
                match.endIndex > currentBest.endIndex ||
                (match.endIndex == currentBest.endIndex && match.startIndex < currentBest.startIndex)
            ) {
                bestMatch = match
            }
        }
        return bestMatch?.emoji
    }

    private fun tokenizeEmojiSuggestionText(value: String): List<String> {
        return value
            .lowercase(Locale.US)
            .split(EMOJI_SUGGESTION_TOKEN_SPLIT_REGEX)
            .filter { it.isNotBlank() }
    }

    private fun List<String>.lastIndexOfSequence(sequence: List<String>): Int {
        if (sequence.isEmpty() || sequence.size > size) return -1
        for (startIndex in size - sequence.size downTo 0) {
            var matches = true
            for (sequenceIndex in sequence.indices) {
                if (this[startIndex + sequenceIndex] != sequence[sequenceIndex]) {
                    matches = false
                    break
                }
            }
            if (matches) return startIndex
        }
        return -1
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

    private fun handleGifSearchKeyboardAction(action: KeyboardAction, modifiers: ModifierState): Boolean {
        if (modifiers.hasHardwareMeta) {
            return true
        }
        val searchView = gifSearchView ?: return true
        when (action) {
            KeyboardAction.Backspace -> searchView.deleteQueryCharacter()
            KeyboardAction.Enter -> searchView.searchNow()
            is KeyboardAction.KeyCode -> {
                if (action.keyCode == KeyEvent.KEYCODE_DEL) {
                    searchView.deleteQueryCharacter()
                }
            }
            is KeyboardAction.Text -> searchView.appendQueryText(action.value)
        }
        return true
    }

    private fun handleSavedTextSearchKeyboardAction(action: KeyboardAction, modifiers: ModifierState): Boolean {
        if (modifiers.hasHardwareMeta) {
            return true
        }
        val searchView = savedTextSearchView ?: return true
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
        if (wrapSelectedText(inputConnection, value)) {
            scheduleEmojiSuggestionRefresh()
            return
        }
        if (inputConnection.commitText(value, 1)) {
            scheduleEmojiSuggestionRefresh()
        }
    }

    private fun wrapSelectedText(inputConnection: InputConnection, value: String): Boolean {
        val pair = SELECTION_WRAP_SYMBOLS[value] ?: return false
        val selectedText = inputConnection.getSelectedText(0)?.toString()
        if (selectedText.isNullOrEmpty()) return false

        return inputConnection.commitText("${pair.open}$selectedText${pair.close}", 1)
    }

    private fun handleBackspace(modifiers: ModifierState) {
        val inputConnection = currentInputConnection ?: return
        cancelActiveAgentForEditorChange()
        // TYPE_NULL editors such as terminals expect raw key events, not surrounding-text edits.
        if (isRawKeyEventEditor(currentEditorInfo)) {
            sendKey(KeyEvent.KEYCODE_DEL, modifiers)
            scheduleEmojiSuggestionRefresh()
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
        scheduleEmojiSuggestionRefresh()
    }

    private fun startBackspaceGestureDelete(): Boolean {
        val inputConnection = currentInputConnection ?: return false
        val info = currentEditorInfo
        if (
            activeSurface != KeyboardSurface.Keyboard ||
            isRawKeyEventEditor(info) ||
            isSensitiveEditor(info)
        ) {
            return false
        }

        val snapshot = captureEditorSnapshot(GESTURE_DELETE_CONTEXT_CHARS + 1) ?: return false
        val cursorOffset = snapshot.selectionStart
        if (cursorOffset != snapshot.selectionEnd) return false

        val localCursorOffset = cursorOffset - snapshot.textStartOffset
        if (localCursorOffset !in 0..snapshot.text.length) return false

        val beforeCursor = snapshot.text
            .substring(0, localCursorOffset)
            .takeLast(GESTURE_DELETE_CONTEXT_CHARS)
        val wordSelectionStarts = previousGestureDeleteWordStarts(
            beforeCursor = beforeCursor,
            cursorOffset = cursorOffset,
        )
        if (wordSelectionStarts.isEmpty()) return false
        val wordSelectionTexts = wordSelectionStarts.map { selectionStart ->
            beforeCursor.takeLast(cursorOffset - selectionStart)
        }

        cancelActiveAgentForEditorChange()
        inputConnection.finishComposingText()
        backspaceGestureDeleteState = BackspaceGestureDeleteState(
            anchorOffset = cursorOffset,
            originalSelectionStart = snapshot.selectionStart,
            originalSelectionEnd = snapshot.selectionEnd,
            wordSelectionStarts = wordSelectionStarts,
            wordSelectionTexts = wordSelectionTexts,
        )
        return true
    }

    private fun updateBackspaceGestureDeleteSelection(wordCount: Int) {
        val state = backspaceGestureDeleteState ?: return
        val inputConnection = currentInputConnection ?: return
        val coercedWordCount = wordCount.coerceIn(0, state.wordSelectionStarts.size)
        if (coercedWordCount == state.currentWordCount) return

        val selectionStart = if (coercedWordCount == 0) {
            state.anchorOffset
        } else {
            state.wordSelectionStarts[coercedWordCount - 1]
        }
        var selectionUpdated = false
        inputConnection.beginBatchEdit()
        try {
            selectionUpdated = inputConnection.setSelection(selectionStart, state.anchorOffset)
        } finally {
            inputConnection.endBatchEdit()
        }
        if (selectionUpdated) {
            state.currentWordCount = coercedWordCount
        } else {
            cancelBackspaceGestureDelete()
        }
    }

    private fun finishBackspaceGestureDelete() {
        val state = backspaceGestureDeleteState ?: return
        backspaceGestureDeleteState = null
        val inputConnection = currentInputConnection ?: return
        val selectedWordCount = state.currentWordCount.coerceIn(0, state.wordSelectionStarts.size)
        if (selectedWordCount == 0) {
            restoreBackspaceGestureDeleteSelection(inputConnection, state)
            return
        }

        val selectionStart = state.wordSelectionStarts[selectedWordCount - 1]
        val expectedText = state.wordSelectionTexts[selectedWordCount - 1]
        if (!isBackspaceGestureDeleteTargetCurrent(state, selectionStart, expectedText)) {
            restoreBackspaceGestureDeleteSelection(inputConnection, state)
            return
        }

        var committed = false
        inputConnection.beginBatchEdit()
        try {
            if (inputConnection.setSelection(selectionStart, state.anchorOffset)) {
                committed = inputConnection.commitText("", 1)
            }
        } finally {
            inputConnection.endBatchEdit()
        }

        if (committed) {
            scheduleEmojiSuggestionRefresh()
        } else {
            restoreBackspaceGestureDeleteSelection(inputConnection, state)
        }
    }

    private fun cancelBackspaceGestureDelete() {
        val state = backspaceGestureDeleteState ?: return
        backspaceGestureDeleteState = null
        currentInputConnection?.let { inputConnection ->
            restoreBackspaceGestureDeleteSelection(inputConnection, state)
        }
    }

    private fun isBackspaceGestureDeleteTargetCurrent(
        state: BackspaceGestureDeleteState,
        selectionStart: Int,
        expectedText: String,
    ): Boolean {
        val snapshot = captureEditorSnapshot(GESTURE_DELETE_CONTEXT_CHARS + 1) ?: return false
        val localStart = selectionStart - snapshot.textStartOffset
        val localEnd = state.anchorOffset - snapshot.textStartOffset
        if (localStart < 0 || localEnd > snapshot.text.length || localStart > localEnd) {
            return false
        }
        return snapshot.text.substring(localStart, localEnd) == expectedText
    }

    private fun restoreBackspaceGestureDeleteSelection(
        inputConnection: InputConnection,
        state: BackspaceGestureDeleteState,
    ) {
        val maxOffset = captureEditorSnapshot(GESTURE_DELETE_CONTEXT_CHARS + 1)
            ?.let { snapshot -> snapshot.textStartOffset + snapshot.text.length }
            ?: state.originalSelectionEnd
        val selectionStart = state.originalSelectionStart.coerceIn(0, maxOffset)
        val selectionEnd = state.originalSelectionEnd.coerceIn(0, maxOffset)
        inputConnection.beginBatchEdit()
        try {
            inputConnection.setSelection(selectionStart, selectionEnd)
        } finally {
            inputConnection.endBatchEdit()
        }
    }

    private fun previousGestureDeleteWordStarts(
        beforeCursor: String,
        cursorOffset: Int,
    ): List<Int> {
        val starts = mutableListOf<Int>()
        var scanOffset = beforeCursor.length
        while (scanOffset > 0 && starts.size < MAX_GESTURE_DELETE_WORDS) {
            while (scanOffset > 0 && beforeCursor[scanOffset - 1].isWhitespace()) {
                scanOffset--
            }
            if (scanOffset == 0) break

            while (scanOffset > 0 && !beforeCursor[scanOffset - 1].isWhitespace()) {
                scanOffset--
            }

            val selectedCharCount = beforeCursor.length - scanOffset
            val absoluteStart = cursorOffset - selectedCharCount
            if (absoluteStart < 0) break
            starts += absoluteStart
        }
        return starts
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
            scheduleEmojiSuggestionRefresh()
        }
    }

    private fun sendGif(gif: GifSearchResult) {
        val info = currentEditorInfo
        if (isSensitiveEditor(info)) {
            Toast.makeText(this, "GIFs are disabled in password fields.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!supportsContentMimeType(info, GIF_MIME_TYPE)) {
            Toast.makeText(this, "This field does not accept GIFs from keyboards.", Toast.LENGTH_LONG).show()
            return
        }

        val targetPackage = info?.packageName
        val appContext = applicationContext
        val authority = "$packageName.gifprovider"
        val generation = ++gifShareGeneration
        Toast.makeText(this, "Preparing GIF...", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching {
                val file = GifCacheStore.downloadGif(appContext, gif)
                FileProvider.getUriForFile(appContext, authority, file)
            }

            mainHandler.post {
                if (generation != gifShareGeneration) {
                    return@post
                }
                if (targetPackage != currentEditorInfo?.packageName) {
                    return@post
                }
                result
                    .onSuccess { uri ->
                        runCatching { commitGifContent(gif, uri, targetPackage) }
                            .onFailure { error ->
                                Toast.makeText(
                                    this,
                                    error.message ?: "Could not send GIF.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                    }
                    .onFailure { error ->
                        Toast.makeText(
                            this,
                            error.message ?: "Could not prepare GIF.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
            }
        }.apply {
            name = "ZnKeyboardGifShare"
            start()
        }
    }

    private fun commitGifContent(gif: GifSearchResult, contentUri: Uri, targetPackage: String?) {
        val inputConnection = currentInputConnection ?: throw IOException("No active text field.")
        if (!supportsContentMimeType(currentEditorInfo, GIF_MIME_TYPE)) {
            throw IOException("This field no longer accepts GIFs.")
        }

        if (!targetPackage.isNullOrBlank()) {
            grantUriPermission(targetPackage, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val content = InputContentInfo(
            contentUri,
            ClipDescription(gif.title, arrayOf(GIF_MIME_TYPE)),
            runCatching { Uri.parse(gif.gifUrl) }.getOrNull(),
        )
        val committed = inputConnection.commitContent(
            content,
            InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
            null,
        )
        if (!committed) {
            if (!targetPackage.isNullOrBlank()) {
                revokeUriPermission(targetPackage, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            throw IOException("This field did not accept the GIF.")
        }
    }

    private fun supportsContentMimeType(info: EditorInfo?, mimeType: String): Boolean {
        return info?.contentMimeTypes
            ?.any { supportedMimeType ->
                ClipDescription.compareMimeTypes(mimeType, supportedMimeType)
            } == true
    }

    private fun registerClipboardListener() {
        if (clipboardListenerRegistered) return
        clipboardManager.addPrimaryClipChangedListener(clipboardChangeListener)
        clipboardListenerRegistered = true
    }

    private fun unregisterClipboardListener() {
        if (!clipboardListenerRegistered) return
        clipboardManager.removePrimaryClipChangedListener(clipboardChangeListener)
        clipboardListenerRegistered = false
    }

    private fun capturePrimaryClipboardText() {
        val snapshot = readPrimaryPlainClipboardText() ?: return
        if (snapshot == lastCapturedClipboardSnapshot) return
        lastCapturedClipboardSnapshot = snapshot

        ClipboardHistoryStore.recordText(this, snapshot.text)
        if (activeSurface == KeyboardSurface.Snippets || activeSurface == KeyboardSurface.SavedTextSearch) {
            refreshClipboardHistoryViews()
        }
    }

    private fun readPrimaryPlainClipboardText(): ClipboardSnapshot? {
        return runCatching {
            val description = clipboardManager.primaryClipDescription ?: return@runCatching null
            if (description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true) {
                return@runCatching null
            }
            val hasTextMimeType = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
            if (!hasTextMimeType) return@runCatching null

            val clip = clipboardManager.primaryClip ?: return@runCatching null
            if (clip.itemCount <= 0) return@runCatching null
            val text = clip.getItemAt(0)
                ?.text
                ?.toString()
                ?.takeIf { it.isNotBlank() && it.length <= ClipboardHistoryStore.MAX_ENTRY_CHARS }
                ?: return@runCatching null
            ClipboardSnapshot(
                text = text,
                timestampMillis = description.timestamp,
            )
        }.getOrNull()
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

    private fun pasteClipboardHistoryText(text: String) {
        if (isSensitiveEditor(currentEditorInfo)) return
        val inputConnection = currentInputConnection ?: return
        cancelActiveAgentForEditorChange()

        var committed = false
        inputConnection.beginBatchEdit()
        try {
            inputConnection.finishComposingText()
            committed = inputConnection.commitText(text, 1)
        } finally {
            inputConnection.endBatchEdit()
        }

        if (committed) {
            scheduleEmojiSuggestionRefresh()
            showKeyboardPanel()
        } else {
            Toast.makeText(this, "Couldn't paste clipboard item.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteClipboardHistoryText(text: String) {
        ClipboardHistoryStore.deleteText(this, text)
        refreshClipboardHistoryViews()
    }

    private fun refreshClipboardHistoryViews() {
        val entries = ClipboardHistoryStore.read(this)
        snippetPanelView?.submitClipboardHistory(entries)
        savedTextSearchView?.setClipboardEntries(entries)
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
                renderSecondRow()
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
            renderSecondRow()
        }
    }

    private fun startLlmRequest(
        target: AgentEditTarget,
        requestText: String,
        selectTargetForRequest: Boolean,
    ) {
        if (!isRewriteAvailable()) return
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
        renderSecondRow()

        Thread {
            val result = runCatching {
                requestChatCompletion(
                    providerSettings = providerSettings,
                    text = requestText,
                )
            }

            mainHandler.post {
                if (generation != requestGeneration || !isRewriteAvailable()) return@post
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
                renderSecondRow()
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
        renderSecondRow()
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
            renderSecondRow()
        }
    }

    private fun renderSecondRow() {
        val visible = activeSurface == KeyboardSurface.Keyboard
        val providerConfigured = KeyboardSettings.readAgentProviderSettings(this).isConfigured

        keyboardView?.renderShortcutRows(
            ZnKeyboardView.ShortcutRowState(
                secondRowVisible = visible,
                loading = agentLoading,
                rewriteEnabled = visible && isRewriteAvailable() && providerConfigured,
            ),
        )
    }

    private fun isRewriteAvailable(): Boolean {
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

    private data class EmojiSuggestionTag(
        val emoji: String,
        val tokens: List<String>,
    )

    private data class EmojiSuggestionMatch(
        val emoji: String,
        val startIndex: Int,
        val endIndex: Int,
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

    private data class BackspaceGestureDeleteState(
        val anchorOffset: Int,
        val originalSelectionStart: Int,
        val originalSelectionEnd: Int,
        val wordSelectionStarts: List<Int>,
        val wordSelectionTexts: List<String>,
        var currentWordCount: Int = 0,
    )

    private data class ClipboardSnapshot(
        val text: String,
        val timestampMillis: Long,
    )

    private data class SelectionWrapPair(
        val open: String,
        val close: String,
    )

    private enum class KeyboardSurface {
        Keyboard,
        Emoji,
        EmojiSearch,
        Gif,
        GifSearch,
        Snippets,
        SavedTextSearch,
        Review,
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
        const val MAX_REWRITE_SOURCE_CHARS = AgentDefaults.MAX_REWRITE_SOURCE_CHARS
        const val GESTURE_DELETE_CONTEXT_CHARS = 4_096
        const val MAX_GESTURE_DELETE_WORDS = 80
        const val EMOJI_SUGGESTION_CONTEXT_CHARS = 180
        const val EMOJI_SUGGESTION_REFRESH_DELAY_MS = 120L
        const val REQUEST_TIMEOUT_MS = AgentDefaults.REQUEST_TIMEOUT_MS
        const val MIN_REWRITE_LLM_TOKENS = AgentDefaults.MIN_REWRITE_LLM_TOKENS
        const val MAX_REWRITE_LLM_TOKENS = AgentDefaults.MAX_REWRITE_LLM_TOKENS
        const val CHARS_PER_OUTPUT_TOKEN_ESTIMATE = AgentDefaults.CHARS_PER_OUTPUT_TOKEN_ESTIMATE
        const val ERROR_PREVIEW_CHARS = AgentDefaults.ERROR_PREVIEW_CHARS
        const val GIF_MIME_TYPE = "image/gif"
        const val STRUCTURED_OUTPUT_TEXT_FIELD = AgentDefaults.STRUCTURED_OUTPUT_TEXT_FIELD
        const val REWRITE_BASE_PROMPT_ASSET = AgentDefaults.REWRITE_BASE_PROMPT_ASSET
        const val WHATSAPP_PROMPT_ASSET = AgentDefaults.WHATSAPP_PROMPT_ASSET
        const val WHATSAPP_PACKAGE_NAME = "com.whatsapp"
        const val WHATSAPP_BUSINESS_PACKAGE_NAME = "com.whatsapp.w4b"
        val SELECTION_WRAP_SYMBOLS = mapOf(
            "(" to SelectionWrapPair("(", ")"),
            ")" to SelectionWrapPair("(", ")"),
            "[" to SelectionWrapPair("[", "]"),
            "]" to SelectionWrapPair("[", "]"),
            "{" to SelectionWrapPair("{", "}"),
            "}" to SelectionWrapPair("{", "}"),
            "<" to SelectionWrapPair("<", ">"),
            ">" to SelectionWrapPair("<", ">"),
            "\"" to SelectionWrapPair("\"", "\""),
            "'" to SelectionWrapPair("'", "'"),
            "`" to SelectionWrapPair("`", "`"),
        )
        val EMOJI_SUGGESTION_TOKEN_SPLIT_REGEX = Regex("[^\\p{L}\\p{N}]+")
        val THINK_BLOCK_REGEX = Regex("(?is)<think>.*?</think>")
        val REASONING_BLOCK_REGEX = Regex("(?is)<reasoning>.*?</reasoning>")
        val FALLBACK_REWRITE_PROMPT = """
            Improve or rewrite the user's text while preserving the original meaning and tone.
            Return a valid JSON object with exactly one field, text, containing only the rewritten text. Do not include labels, explanations, or prefaces in text.
        """.trimIndent()
    }
}
