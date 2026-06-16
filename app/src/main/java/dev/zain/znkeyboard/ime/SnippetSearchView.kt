package dev.zain.znkeyboard.ime

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.zain.znkeyboard.KeyboardSettings
import dev.zain.znkeyboard.constants.ImeColors
import java.util.Locale
import kotlin.math.roundToInt

class SavedTextSearchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs),
    ClipboardHistoryListView.Callback {
    interface Callback {
        fun onSavedTextSearchClosed()
        fun onSavedTextSearchSnippetSelected(snippet: String)
        fun onSavedTextSearchClipboardSelected(text: String)
        fun onSavedTextSearchClipboardDeleted(text: String)
    }

    var callback: Callback? = null

    private var activeTab = KeyboardSettings.SavedTextPanelTab.Snippets
    private var snippets = emptyList<KeyboardSettings.TextSnippet>()
    private var clipboardEntries = emptyList<ClipboardHistoryStore.Entry>()
    private var query = ""

    private val queryField = ImeSearchField(context).configure(
        backgroundColor = PALETTE.function,
        textColor = PALETTE.text,
        placeholderColor = PALETTE.placeholderText,
        clearIconColor = PALETTE.mutedText,
        clearContentDescription = "Clear saved text search",
        onClear = { setQuery("") },
    )
    private val snippetResultContent = LinearLayout(context).apply {
        orientation = VERTICAL
    }
    private val snippetResultScroll = ScrollView(context).apply {
        clipToPadding = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        setPadding(0, dp(4), 0, dp(6))
        addView(
            snippetResultContent,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
    }
    private val clipboardResultList = ClipboardHistoryListView(context).apply {
        callback = this@SavedTextSearchView
    }
    private val emptyText = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(PALETTE.mutedText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        visibility = GONE
    }
    private val resultsFrame = FrameLayout(context).apply {
        addView(
            snippetResultScroll,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        addView(
            clipboardResultList,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        addView(
            emptyText,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(PALETTE.background)
        setPadding(
            dp(ImeLayout.HORIZONTAL_PADDING_DP),
            dp(ImeLayout.TOP_PADDING_DP),
            dp(ImeLayout.HORIZONTAL_PADDING_DP),
            dp(6),
        )
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        visibility = GONE

        addView(resultsFrame, LayoutParams(LayoutParams.MATCH_PARENT, dp(156)))
        addView(searchRow(), LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).withMargins(top = 6))
        updateResults()
    }

    fun setSnippets(nextSnippets: List<KeyboardSettings.TextSnippet>) {
        val normalized = KeyboardSettings.normalizeTextSnippets(nextSnippets)
        if (snippets != normalized) {
            snippets = normalized
            updateResults()
        }
    }

    fun setClipboardEntries(entries: List<ClipboardHistoryStore.Entry>) {
        val normalized = entries.take(ClipboardHistoryStore.MAX_HISTORY)
        if (clipboardEntries != normalized) {
            clipboardEntries = normalized
            updateResults()
        }
    }

    fun showForTab(tab: KeyboardSettings.SavedTextPanelTab) {
        activeTab = tab
        clearSearch()
        updateResults()
    }

    fun appendQueryText(value: String) {
        if (value.isEmpty()) return
        setQuery(query + value)
    }

    fun deleteQueryCharacter() {
        if (query.isNotEmpty()) {
            setQuery(query.dropLast(1))
        }
    }

    fun clearSearch() {
        setQuery("")
    }

    override fun onClipboardHistoryItemSelected(text: String) {
        callback?.onSavedTextSearchClipboardSelected(text)
    }

    override fun onClipboardHistoryItemDeleted(text: String) {
        callback?.onSavedTextSearchClipboardDeleted(text)
    }

    private fun setQuery(value: String) {
        val normalized = value.take(MAX_QUERY_LENGTH)
        if (query != normalized) {
            query = normalized
            updateResults()
        } else {
            updateQueryText()
        }
    }

    private fun searchRow(): LinearLayout {
        return ImeSearchHeader.create(
            context = context,
            backContentDescription = "Back to saved text",
            backgroundColor = PALETTE.function,
            textColor = PALETTE.mutedText,
            queryField = queryField,
            onBack = { callback?.onSavedTextSearchClosed() },
        )
    }

    private fun updateResults() {
        updateQueryText()
        if (activeTab == KeyboardSettings.SavedTextPanelTab.Clipboard) {
            updateClipboardResults()
        } else {
            updateSnippetResults()
        }
    }

    private fun updateSnippetResults() {
        clipboardResultList.visibility = GONE
        snippetResultContent.removeAllViews()
        val visibleSnippets = visibleSnippets()
        if (visibleSnippets.isEmpty()) {
            snippetResultScroll.visibility = GONE
            showEmpty(if (query.isBlank()) "No snippets" else "No matching snippets")
            return
        }

        emptyText.visibility = GONE
        snippetResultScroll.visibility = VISIBLE
        visibleSnippets.forEach { snippet ->
            snippetResultContent.addView(
                resultCard(snippet),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).withMargins(bottom = 6),
            )
        }
    }

    private fun updateClipboardResults() {
        snippetResultScroll.visibility = GONE
        val entries = visibleClipboardEntries()
        clipboardResultList.submitHistory(entries)
        if (entries.isEmpty()) {
            clipboardResultList.visibility = GONE
            showEmpty(if (query.isBlank()) "No clipboard history yet" else "No matching clipboard items")
            return
        }

        emptyText.visibility = GONE
        clipboardResultList.visibility = VISIBLE
    }

    private fun showEmpty(message: String) {
        emptyText.text = message
        emptyText.visibility = VISIBLE
    }

    private fun updateQueryText() {
        queryField.setSearchText(query = query, placeholder = activeTab.searchLabel)
    }

    private fun visibleSnippets(): List<KeyboardSettings.TextSnippet> {
        val terms = normalizedQueryTerms()
        if (terms.isEmpty()) return snippets.take(MAX_RESULTS)
        return snippets
            .filter { snippet -> snippet.matchesAll(terms) }
            .take(MAX_RESULTS)
    }

    private fun visibleClipboardEntries(): List<ClipboardHistoryStore.Entry> {
        val needle = normalizedQuery()
        if (needle.isBlank()) return clipboardEntries.take(MAX_RESULTS)
        return clipboardEntries
            .filter { entry -> entry.text.lowercase(Locale.US).contains(needle) }
            .take(MAX_RESULTS)
    }

    private fun normalizedQuery(): String {
        return query.trim().lowercase(Locale.US)
    }

    private fun normalizedQueryTerms(): List<String> {
        return query
            .trim()
            .split(searchTermSeparatorRegex)
            .map {
                it.trim()
                    .removePrefix("#")
                    .lowercase(Locale.US)
            }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun KeyboardSettings.TextSnippet.matchesAll(terms: List<String>): Boolean {
        val searchableText = buildString {
            append(text)
            tags.forEach { tag ->
                append(' ')
                append(tag)
            }
        }.lowercase(Locale.US)
        return terms.all { term -> searchableText.contains(term) }
    }

    private fun resultCard(snippet: KeyboardSettings.TextSnippet): LinearLayout {
        return SavedTextSnippetCard.create(
            context = context,
            snippet = snippet,
            style = SavedTextSnippetCard.Style(
                backgroundColor = PALETTE.key,
                textColor = PALETTE.text,
                tagTextColor = PALETTE.tagText,
                maxLines = 2,
            ),
            onClick = { callback?.onSavedTextSearchSnippetSelected(it) },
        )
    }

    private fun LayoutParams.withMargins(
        horizontal: Int = 0,
        vertical: Int = 0,
        start: Int = horizontal,
        top: Int = vertical,
        end: Int = horizontal,
        bottom: Int = vertical,
    ): LayoutParams {
        setMargins(dp(start), dp(top), dp(end), dp(bottom))
        return this
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private object PALETTE {
        val background = ImeColors.BACKGROUND
        val key = ImeColors.KEY_DARK
        val function = ImeColors.FUNCTION_DARK
        const val text = ImeColors.TEXT
        val tagText = ImeColors.TAG_TEXT
        val mutedText = ImeColors.MUTED_TEXT
        val placeholderText = ImeColors.PLACEHOLDER_TEXT
    }

    private companion object {
        const val MAX_QUERY_LENGTH = 80
        const val MAX_RESULTS = 24
        val searchTermSeparatorRegex = Regex("[,\\s]+")
    }
}
