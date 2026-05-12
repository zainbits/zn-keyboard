package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.bumptech.glide.Glide
import dev.zain.znkeyboard.KeyboardSettings
import dev.zain.znkeyboard.constants.GifDefaults
import dev.zain.znkeyboard.constants.ImeColors
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

private const val GIF_CARD_SPACING_DP = GifDefaults.CARD_SPACING_DP
private const val GIF_CARD_RADIUS_DP = GifDefaults.CARD_RADIUS_DP

class GifSearchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    interface Callback {
        fun onGifKeyboardRequested()
        fun onGifEmojiRequested()
        fun onGifSearchClosed()
        fun onGifSearchRequested()
        fun onGifSearchBackToBrowse()
        fun onGifBackspace()
        fun onGifSelected(gif: GifSearchResult)
    }

    var callback: Callback? = null

    private var providerSettings = KeyboardSettings.GifProviderSettings(
        baseUrl = KeyboardSettings.DEFAULT_GIF_API_BASE_URL,
        appKey = "",
    )
    private var query = ""
    private var mode = Mode.Browse
    private var heightScale = 1f
    private var requestGeneration = 0
    private var pendingRefresh: Runnable? = null
    private var loadedResults = emptyList<GifSearchResult>()
    private var activeResultQuery = ""
    private var nextPageToLoad = FIRST_PAGE
    private var hasNextPage = false
    private var pageLoading = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ZnKeyboardGifSearch")
    }
    private val suggestionButtons = mutableListOf<TextView>()

    private val queryField = ImeSearchField(context).configure(
        backgroundColor = PALETTE.function,
        textColor = PALETTE.text,
        placeholderColor = PALETTE.placeholderText,
        clearIconColor = PALETTE.mutedText,
        clearContentDescription = "Clear GIF search",
        onClear = { setQuery("") },
    ).apply {
        isClickable = true
        isFocusable = false
        contentDescription = "Edit GIF search"
        setOnClickListener {
            if (mode == Mode.Browse) {
                callback?.onGifSearchRequested()
            }
        }
    }
    private val browseHeader = browseHeaderRow()
    private val searchHeader = searchRow().apply {
        visibility = GONE
    }
    private val resultLayoutManager = StaggeredGridLayoutManager(2, RecyclerView.VERTICAL).apply {
        gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS
    }
    private val resultAdapter = GifResultAdapter(
        onGifSelected = { gif -> callback?.onGifSelected(gif) },
        cellHeightProvider = ::masonryCellHeightForCurrentWidth,
        dp = ::dp,
    )
    private val resultList = RecyclerView(context).apply {
        clipToPadding = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        setPadding(0, dp(4), 0, dp(8))
        itemAnimator = null
        layoutManager = resultLayoutManager
        adapter = resultAdapter
        addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy < 0) return
                    val lastVisible = resultLayoutManager
                        .findLastVisibleItemPositions(null)
                        .maxOrNull()
                        ?: RecyclerView.NO_POSITION
                    if (lastVisible >= resultAdapter.itemCount - LOAD_MORE_THRESHOLD_ITEMS) {
                        loadNextPageIfNeeded()
                    }
                }
            },
        )
    }
    private val resultMessage = messageTextView("").apply {
        minHeight = dp(140)
        visibility = GONE
    }
    private val resultFrame = FrameLayout(context).apply {
        clipToPadding = false
        addView(
            resultList,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        addView(
            resultMessage,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        )
    }
    private val suggestionStrip = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        visibility = GONE
    }
    private val suggestionRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val statusText = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(PALETTE.sectionText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        text = POWERED_BY_KLIPY
    }
    private val bottomToolbar = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val bottomSystemControlGapPx by lazy(LazyThreadSafetyMode.NONE) {
        ImeLayout.bottomSystemControlGapPx(context)
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(PALETTE.background)
        updatePanelPadding()
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        visibility = GONE

        addView(browseHeader, LayoutParams(LayoutParams.MATCH_PARENT, dp(ImePanelChrome.BROWSE_HEADER_HEIGHT_DP)))
        addView(searchHeader, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
        suggestionStrip.addView(
            suggestionRow,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
        )
        addView(suggestionStrip, LayoutParams(LayoutParams.MATCH_PARENT, dp(34)).withMargins(top = 6))
        addView(resultFrame, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).withMargins(top = 4))
        addView(statusText, LayoutParams(LayoutParams.MATCH_PARENT, dp(24)).withMargins(top = 4))
        buildBottomToolbar()
        addView(bottomToolbar, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).withMargins(top = 4))
        updateQueryText()
        updateModeChrome()
        renderSetupState()
    }

    fun setHeightScale(scale: Float) {
        if (heightScale != scale) {
            heightScale = scale
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (mode == Mode.Browse) {
            val desiredHeight = ((ImeLayout.BASE_HEIGHT_DP + ImeLayout.EXTENDED_PANEL_EXTRA_HEIGHT_DP) *
                heightScale *
                resources.displayMetrics.density +
                bottomSystemControlGapPx)
                .roundToInt()
            val exactHeightSpec = MeasureSpec.makeMeasureSpec(resolveSize(desiredHeight, heightMeasureSpec), MeasureSpec.EXACTLY)
            super.onMeasure(widthMeasureSpec, exactHeightSpec)
            return
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width != oldWidth) {
            updateResultGridSizing()
        }
    }

    fun setProviderSettings(settings: KeyboardSettings.GifProviderSettings) {
        if (providerSettings == settings) return
        providerSettings = settings
        if (settings.isConfigured) {
            if (mode == Mode.Browse) {
                scheduleRefresh(immediate = true)
            } else {
                renderMessage(SEARCH_PROMPT)
            }
        } else {
            requestGeneration++
            cancelPendingRefresh()
            resetPaging()
            renderSetupState()
        }
    }

    fun showBrowseMode() {
        if (mode != Mode.Browse) {
            mode = Mode.Browse
            requestGeneration++
            cancelPendingRefresh()
            resetPaging()
            query = ""
            updateQueryText()
            updateModeChrome()
        } else {
            updateModeChrome()
        }

        if (providerSettings.isConfigured) {
            scheduleRefresh(immediate = true)
        } else {
            renderSetupState()
        }
    }

    fun showCurrentResultsBrowseMode() {
        mode = Mode.Browse
        cancelPendingRefresh()
        updateModeChrome()
        if (loadedResults.isNotEmpty()) {
            submitResultRows(loadedResults)
            statusText.text = when {
                hasNextPage -> "Showing ${loadedResults.size} GIFs"
                activeResultQuery.isBlank() -> POWERED_BY_KLIPY
                else -> "Search results for $activeResultQuery"
            }
        } else if (providerSettings.isConfigured) {
            renderMessage(if (query.isBlank()) "No GIFs found" else "No GIFs found for $query")
        } else {
            renderSetupState()
        }
    }

    fun showSearchMode() {
        mode = Mode.Search
        requestGeneration++
        cancelPendingRefresh()
        updateQueryText()
        updateModeChrome()

        if (!providerSettings.isConfigured) {
            renderSetupState()
        } else if (query.isBlank()) {
            resetPaging()
            renderMessage(SEARCH_PROMPT)
        } else {
            if (loadedResults.isNotEmpty()) {
                submitResultRows(loadedResults)
            }
            statusText.text = "Tap Search to search GIFs"
        }
    }

    fun resetSearch() {
        setQuery("")
    }

    fun setQuery(value: String) {
        val normalized = value.take(MAX_QUERY_LENGTH)
        if (query == normalized) {
            updateQueryText()
            return
        }
        query = normalized
        updateQueryText()
        if (mode == Mode.Search) {
            requestGeneration++
            cancelPendingRefresh()
            pageLoading = false
            hasNextPage = false
            activeResultQuery = ""
            if (query.isBlank()) {
                renderMessage(SEARCH_PROMPT)
            } else {
                statusText.text = "Tap Search to search GIFs"
            }
        }
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

    fun searchNow() {
        if (mode != Mode.Search) {
            if (query.isNotBlank()) {
                scheduleRefresh(immediate = true)
                return
            }
            showSearchMode()
            return
        }
        scheduleRefresh(immediate = true)
    }

    fun dispose() {
        requestGeneration++
        cancelPendingRefresh()
        clearResultContent()
        requestExecutor.shutdownNow()
    }

    override fun onDetachedFromWindow() {
        requestGeneration++
        cancelPendingRefresh()
        clearResultContent()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView == this) {
            if (visibility == VISIBLE) {
                updateResultGridSizing()
                resultAdapter.reloadVisibleItems(resultList)
            } else {
                resultAdapter.clearVisibleRequests(resultList)
            }
        }
    }

    private fun browseHeaderRow(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ImePanelChrome.backButton(
                    context = context,
                    contentDescription = "Back to keyboard",
                    backgroundColor = PALETTE.function,
                    textColor = PALETTE.mutedText,
                    onClick = { callback?.onGifSearchClosed() },
                ),
                LayoutParams(dp(ImePanelChrome.BACK_BUTTON_WIDTH_DP), LayoutParams.MATCH_PARENT).withMargins(end = 4),
            )

            addView(
                ImePanelChrome.searchButton(
                    context = context,
                    contentDescription = "Search GIFs",
                    backgroundColor = PALETTE.function,
                    textColor = PALETTE.mutedText,
                    onClick = { callback?.onGifSearchRequested() },
                ),
                LayoutParams(dp(ImePanelChrome.SEARCH_BUTTON_WIDTH_DP), LayoutParams.MATCH_PARENT).withMargins(end = 6),
            )

            addView(
                TextView(context).apply {
                    text = "GIFs"
                    gravity = Gravity.CENTER_VERTICAL
                    includeFontPadding = false
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(PALETTE.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                },
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
            )
        }
    }

    private fun buildBottomToolbar() {
        bottomToolbar.addView(
            flatToolbarKey("ABC", enabled = true) { callback?.onGifKeyboardRequested() },
            LayoutParams(dp(62), LayoutParams.MATCH_PARENT).withMargins(end = 4),
        )
        bottomToolbar.addView(
            toolbarTab("☺", active = false, enabled = true, onClick = { callback?.onGifEmojiRequested() }),
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).withMargins(horizontal = 3),
        )
        bottomToolbar.addView(
            toolbarTab("GIF", active = true, enabled = true, onClick = null),
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).withMargins(horizontal = 3),
        )
        bottomToolbar.addView(
            toolbarTab("▣", active = false, enabled = false, onClick = null),
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).withMargins(horizontal = 3),
        )
        bottomToolbar.addView(
            toolbarTab(":-)", active = false, enabled = false, onClick = null),
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).withMargins(horizontal = 3),
        )
        bottomToolbar.addView(
            flatToolbarKey("⌫", enabled = true) { callback?.onGifBackspace() }.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                contentDescription = "Delete"
            },
            LayoutParams(dp(58), LayoutParams.MATCH_PARENT).withMargins(start = 4),
        )
    }

    private fun searchRow(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ImePanelChrome.backButton(
                    context = context,
                    contentDescription = "Back",
                    backgroundColor = PALETTE.function,
                    textColor = PALETTE.mutedText,
                    onClick = ::navigateBackFromSearchHeader,
                ),
                LayoutParams(dp(ImePanelChrome.BACK_BUTTON_WIDTH_DP), LayoutParams.MATCH_PARENT).withMargins(end = 4),
            )

            addView(
                queryField,
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).withMargins(horizontal = 2),
            )

            addView(
                textButton("Search") {
                    searchNow()
                }.apply {
                    contentDescription = "Search GIFs"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                },
                LayoutParams(dp(72), LayoutParams.MATCH_PARENT).withMargins(start = 4),
            )
        }
    }

    private fun navigateBackFromSearchHeader() {
        if (mode == Mode.Search) {
            callback?.onGifSearchBackToBrowse()
        } else {
            callback?.onGifSearchClosed()
        }
    }

    private fun updateQueryText() {
        queryField.setSearchText(query = query, placeholder = SEARCH_PLACEHOLDER)
    }

    private fun scheduleRefresh(immediate: Boolean = false) {
        cancelPendingRefresh()
        if (!providerSettings.isConfigured) {
            renderSetupState()
            return
        }
        if (mode == Mode.Search && query.trim().isBlank()) {
            requestGeneration++
            renderMessage(SEARCH_PROMPT)
            return
        }
        if (query.trim().length == 1) {
            requestGeneration++
            renderMessage("Keep typing to search GIFs")
            return
        }

        val refresh = Runnable {
            pendingRefresh = null
            refreshResults()
        }
        pendingRefresh = refresh
        if (immediate) {
            mainHandler.post(refresh)
        } else {
            mainHandler.postDelayed(refresh, SEARCH_DEBOUNCE_MS)
        }
    }

    private fun refreshResults() {
        val settings = providerSettings
        if (!settings.isConfigured) {
            renderSetupState()
            return
        }

        val normalizedQuery = query.trim()
        val generation = ++requestGeneration
        resetPaging()
        renderLoading(normalizedQuery)
        loadResultsPage(
            settings = settings,
            normalizedQuery = normalizedQuery,
            generation = generation,
            pageNumber = FIRST_PAGE,
            append = false,
        )
    }

    private fun loadResultsPage(
        settings: KeyboardSettings.GifProviderSettings,
        normalizedQuery: String,
        generation: Int,
        pageNumber: Int,
        append: Boolean,
    ) {
        pageLoading = true
        requestExecutor.execute {
            val result = runCatching {
                val client = KlipyGifClient(
                    settings = settings,
                    locale = deviceLocale(),
                )
                val page = if (normalizedQuery.isBlank()) {
                    client.trending(page = pageNumber, perPage = PAGE_SIZE)
                } else {
                    client.search(normalizedQuery, page = pageNumber, perPage = PAGE_SIZE)
                }
                val suggestions = if (normalizedQuery.length >= MIN_AUTOCOMPLETE_CHARS) {
                    client.autocomplete(normalizedQuery, limit = MAX_VISIBLE_SUGGESTIONS)
                } else {
                    emptyList()
                }
                GifSearchLoadResult(
                    results = page.results,
                    suggestions = suggestions,
                    query = normalizedQuery,
                    pageNumber = pageNumber,
                    hasNext = page.hasNext,
                    append = append,
                )
            }

            mainHandler.post {
                if (generation != requestGeneration) return@post
                pageLoading = false
                result
                    .onSuccess(::renderLoadedResult)
                    .onFailure { error ->
                        val message = error.message?.takeIf { it.isNotBlank() } ?: "GIF search failed"
                        if (append && loadedResults.isNotEmpty()) {
                            hasNextPage = false
                            submitResultRows(loadedResults)
                            statusText.text = message
                        } else {
                            renderMessage(message)
                        }
                    }
            }
        }
    }

    private fun loadNextPageIfNeeded() {
        if (pageLoading || !hasNextPage || !providerSettings.isConfigured || pendingRefresh != null) return
        if (loadedResults.isEmpty()) return
        val normalizedQuery = query.trim()
        if (normalizedQuery != activeResultQuery) return

        val generation = requestGeneration
        submitResultRows(loadedResults, footer = "Loading more GIFs...")
        loadResultsPage(
            settings = providerSettings,
            normalizedQuery = normalizedQuery,
            generation = generation,
            pageNumber = nextPageToLoad,
            append = true,
        )
    }

    private fun renderLoadedResult(result: GifSearchLoadResult) {
        renderSuggestions(result.suggestions)
        loadedResults = if (result.append) {
            (loadedResults + result.results).distinctBy { it.id }
        } else {
            result.results
        }
        activeResultQuery = result.query
        hasNextPage = result.hasNext && result.results.isNotEmpty()
        nextPageToLoad = result.pageNumber + 1
        submitResultRows(loadedResults, resetScroll = !result.append)
        statusText.text = when {
            loadedResults.isEmpty() && result.query.isBlank() -> "No trending GIFs found"
            loadedResults.isEmpty() -> "No GIFs found"
            hasNextPage -> "Showing ${loadedResults.size} GIFs"
            else -> POWERED_BY_KLIPY
        }
    }

    private fun submitResultRows(
        results: List<GifSearchResult>,
        footer: String? = null,
        resetScroll: Boolean = false,
    ) {
        if (results.isEmpty()) {
            renderResultMessage(if (mode == Mode.Browse) "No trending GIFs found" else "No GIFs found")
        } else {
            renderResultGrid(results, footer, resetScroll)
        }
    }

    private fun renderResultGrid(results: List<GifSearchResult>, footer: String?, resetScroll: Boolean) {
        updateResultGridSizing()
        resultMessage.visibility = GONE
        resultList.visibility = VISIBLE
        resultAdapter.submitList(
            buildList {
                results.forEach { gif -> add(GifListItem.Result(gif)) }
                footer?.let { add(GifListItem.Footer(it)) }
            },
        ) {
            if (resetScroll) {
                resultList.scrollToPosition(0)
            }
        }
    }

    private fun renderResultMessage(message: String) {
        clearResultContent()
        resultMessage.text = message
        resultMessage.visibility = VISIBLE
        resultList.visibility = GONE
    }

    private fun messageTextView(message: String): TextView {
        return TextView(context).apply {
            text = message
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(PALETTE.sectionText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(10), dp(28), dp(10), dp(28))
        }
    }

    private fun masonryColumnCount(): Int {
        val availableWidth = resultList.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        return if (availableWidth >= dp(460)) 3 else 2
    }

    private fun masonryColumnWidth(columnCount: Int = masonryColumnCount()): Int {
        val availableWidth = (
            resultList.width
                .takeIf { it > 0 }
                ?: (resources.displayMetrics.widthPixels - paddingLeft - paddingRight)
            )
            .minus(resultList.paddingLeft + resultList.paddingRight)
            .coerceAtLeast(dp(240))
        val gapPx = dp(GIF_CARD_SPACING_DP)
        return ((availableWidth - gapPx * (columnCount - 1)) / columnCount).coerceAtLeast(dp(96))
    }

    private fun updateResultGridSizing() {
        val columnCount = masonryColumnCount()
        if (resultLayoutManager.spanCount != columnCount) {
            resultLayoutManager.spanCount = columnCount
        }
        resultAdapter.setCellWidth(masonryColumnWidth(columnCount))
    }

    private fun masonryCellHeightForCurrentWidth(gif: GifSearchResult): Int {
        return masonryCellHeight(gif, masonryColumnWidth())
    }

    private fun masonryCellHeight(gif: GifSearchResult, columnWidth: Int): Int {
        val aspectHeight = if (gif.width > 0 && gif.height > 0) {
            columnWidth * (gif.height.toFloat() / gif.width.toFloat())
        } else {
            columnWidth * DEFAULT_GIF_ASPECT_HEIGHT
        }
        return aspectHeight.roundToInt().coerceIn(dp(MIN_GIF_CELL_HEIGHT_DP), dp(MAX_GIF_CELL_HEIGHT_DP))
    }

    private fun clearResultContent() {
        resultAdapter.clearVisibleRequests(resultList)
        resultAdapter.submitList(emptyList())
        resultList.recycledViewPool.clear()
    }

    private fun renderSuggestions(suggestions: List<String>) {
        suggestionRow.removeAllViews()
        suggestionButtons.clear()

        val visibleSuggestions = suggestions
            .filterNot { it.equals(query, ignoreCase = true) }
            .take(MAX_VISIBLE_SUGGESTIONS)
        suggestionStrip.visibility = if (visibleSuggestions.isEmpty()) GONE else VISIBLE

        visibleSuggestions.forEach { suggestion ->
            val button = suggestionButton(suggestion)
            suggestionButtons += button
            suggestionRow.addView(
                button,
                LayoutParams(LayoutParams.WRAP_CONTENT, dp(30)).withMargins(horizontal = 3),
            )
        }
    }

    private fun renderLoading(activeQuery: String) {
        renderSuggestions(emptyList())
        renderResultMessage(if (activeQuery.isBlank()) "Loading trending GIFs..." else "Searching GIFs...")
        statusText.text = if (activeQuery.isBlank()) "Loading trending GIFs" else "Searching GIFs"
    }

    private fun renderSetupState() {
        resetPaging()
        renderSuggestions(emptyList())
        renderResultMessage("Add a KLIPY app key in settings")
        statusText.text = "Add a KLIPY app key in settings"
    }

    private fun renderMessage(message: String) {
        resetPaging()
        renderSuggestions(emptyList())
        renderResultMessage(message)
        statusText.text = message
    }

    private fun cancelPendingRefresh() {
        pendingRefresh?.let(mainHandler::removeCallbacks)
        pendingRefresh = null
    }

    private fun resetPaging() {
        loadedResults = emptyList()
        activeResultQuery = ""
        nextPageToLoad = FIRST_PAGE
        hasNextPage = false
        pageLoading = false
    }

    private fun textButton(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = false
            setTextColor(PALETTE.mutedText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = ImePressFeedback.roundedBackground(context, PALETTE.function, PALETTE.mutedText)
            setOnClickListener { onClick() }
        }
    }

    private fun toolbarTab(
        label: String,
        active: Boolean,
        enabled: Boolean,
        onClick: (() -> Unit)?,
    ): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            includeFontPadding = false
            isEnabled = enabled
            isClickable = enabled && onClick != null
            isFocusable = false
            alpha = if (enabled) 1f else 0.38f
            typeface = if (label == "GIF") Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(if (enabled) PALETTE.text else PALETTE.disabledText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label == "GIF") 14f else 22f)
            background = ImePressFeedback.roundedBackground(
                context = context,
                containerColor = if (active) PALETTE.selected else PALETTE.function,
                contentColor = if (enabled) PALETTE.text else PALETTE.disabledText,
            )
            onClick?.let { click -> setOnClickListener { click() } }
        }
    }

    private fun flatToolbarKey(label: String, enabled: Boolean, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = enabled
            isEnabled = enabled
            isFocusable = false
            setTextColor(if (enabled) PALETTE.mutedText else PALETTE.disabledText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = ImePressFeedback.roundedBackground(context, Color.TRANSPARENT, PALETTE.mutedText)
            setOnClickListener { onClick() }
        }
    }

    private fun suggestionButton(label: String): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            includeFontPadding = false
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            isClickable = true
            isFocusable = false
            setTextColor(PALETTE.mutedText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12), 0, dp(12), 0)
            background = ImePressFeedback.roundedBackground(context, PALETTE.function, PALETTE.mutedText)
            setOnClickListener {
                setQuery(label)
                scheduleRefresh(immediate = true)
            }
        }
    }

    private fun roundedBackground(color: Int, radiusDp: Int = ImeLayout.KEY_RADIUS_DP): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
        }
    }

    private fun updateModeChrome() {
        val showSearchHeader = mode == Mode.Search || (mode == Mode.Browse && activeResultQuery.isNotBlank())
        browseHeader.visibility = if (mode == Mode.Browse && !showSearchHeader) VISIBLE else GONE
        searchHeader.visibility = if (showSearchHeader) VISIBLE else GONE
        suggestionStrip.visibility = GONE
        bottomToolbar.visibility = if (mode == Mode.Browse) VISIBLE else GONE
        updatePanelPadding()

        val listParams = resultFrame.layoutParams as? LayoutParams
        if (listParams != null) {
            if (mode == Mode.Browse) {
                listParams.height = 0
                listParams.weight = 1f
            } else {
                listParams.height = dp(SEARCH_RESULT_LIST_HEIGHT_DP)
                listParams.weight = 0f
            }
            resultFrame.layoutParams = listParams
        }
        requestLayout()
    }

    private fun updatePanelPadding() {
        setPadding(
            dp(ImeLayout.HORIZONTAL_PADDING_DP),
            dp(ImeLayout.TOP_PADDING_DP),
            dp(ImeLayout.HORIZONTAL_PADDING_DP),
            if (mode == Mode.Browse) {
                dp(ImeLayout.BASE_BOTTOM_PADDING_DP) + bottomSystemControlGapPx.roundToInt()
            } else {
                dp(6)
            },
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

    private fun deviceLocale(): String {
        val locale = resources.configuration.locales.get(0) ?: Locale.getDefault()
        return locale.country
            .lowercase(Locale.US)
            .takeIf { it.length == 2 }
            ?: "us"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private data class GifSearchLoadResult(
        val results: List<GifSearchResult>,
        val suggestions: List<String>,
        val query: String,
        val pageNumber: Int,
        val hasNext: Boolean,
        val append: Boolean,
    )

    private enum class Mode {
        Browse,
        Search,
    }

    private object PALETTE {
        const val background = ImeColors.BACKGROUND
        val key = ImeColors.KEY
        val function = ImeColors.FUNCTION
        val selected = ImeColors.SELECTED
        const val text = ImeColors.TEXT
        val mutedText = ImeColors.MUTED_TEXT
        val disabledText = ImeColors.DISABLED_TEXT
        val placeholderText = ImeColors.PLACEHOLDER_TEXT
        val sectionText = ImeColors.SECTION_TEXT
    }

    private companion object {
        const val FIRST_PAGE = GifDefaults.FIRST_PAGE
        const val PAGE_SIZE = GifDefaults.PAGE_SIZE
        const val LOAD_MORE_THRESHOLD_ITEMS = GifDefaults.LOAD_MORE_THRESHOLD_ITEMS
        const val MIN_GIF_CELL_HEIGHT_DP = GifDefaults.MIN_CELL_HEIGHT_DP
        const val MAX_GIF_CELL_HEIGHT_DP = GifDefaults.MAX_CELL_HEIGHT_DP
        const val DEFAULT_GIF_ASPECT_HEIGHT = GifDefaults.DEFAULT_ASPECT_HEIGHT
        const val SEARCH_RESULT_LIST_HEIGHT_DP = GifDefaults.SEARCH_RESULT_LIST_HEIGHT_DP
        const val MAX_QUERY_LENGTH = GifDefaults.SEARCH_MAX_QUERY_LENGTH
        const val MIN_AUTOCOMPLETE_CHARS = GifDefaults.SEARCH_MIN_AUTOCOMPLETE_CHARS
        const val MAX_VISIBLE_SUGGESTIONS = GifDefaults.MAX_VISIBLE_SUGGESTIONS
        const val SEARCH_DEBOUNCE_MS = GifDefaults.SEARCH_DEBOUNCE_MS
        const val SEARCH_PLACEHOLDER = GifDefaults.SEARCH_PLACEHOLDER
        const val SEARCH_PROMPT = GifDefaults.SEARCH_PROMPT
        const val POWERED_BY_KLIPY = GifDefaults.POWERED_BY_LABEL
    }
}

private sealed class GifListItem {
    abstract val stableId: String

    data class Result(val gif: GifSearchResult) : GifListItem() {
        override val stableId: String = gif.id
    }

    data class Footer(val message: String) : GifListItem() {
        override val stableId: String = "footer:$message"
    }
}

private class GifResultAdapter(
    private val onGifSelected: (GifSearchResult) -> Unit,
    private val cellHeightProvider: (GifSearchResult) -> Int,
    private val dp: (Int) -> Int,
) : ListAdapter<GifListItem, RecyclerView.ViewHolder>(DiffCallback) {
    private var cellWidthPx = 0

    init {
        setHasStableIds(true)
    }

    fun setCellWidth(widthPx: Int) {
        if (cellWidthPx == widthPx) return
        cellWidthPx = widthPx
        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount, PAYLOAD_SIZE_CHANGED)
        }
    }

    fun clearVisibleRequests(recyclerView: RecyclerView) {
        for (index in 0 until recyclerView.childCount) {
            (recyclerView.getChildViewHolder(recyclerView.getChildAt(index)) as? GifViewHolder)?.clear()
        }
    }

    fun reloadVisibleItems(recyclerView: RecyclerView) {
        for (index in 0 until recyclerView.childCount) {
            val position = recyclerView.getChildAdapterPosition(recyclerView.getChildAt(index))
            if (position != RecyclerView.NO_POSITION) {
                notifyItemChanged(position)
            }
        }
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).stableId.hashCode().toLong()
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is GifListItem.Result -> VIEW_TYPE_GIF
            is GifListItem.Footer -> VIEW_TYPE_FOOTER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_GIF -> GifViewHolder(createResultCell(parent.context), onGifSelected, dp)
            else -> FooterViewHolder(createFooterView(parent.context))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        bindHolder(holder, getItem(position))
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (payloads.contains(PAYLOAD_SIZE_CHANGED) && holder is GifViewHolder) {
            val item = getItem(position) as? GifListItem.Result ?: return
            holder.updateHeight(cellHeightProvider(item.gif))
            return
        }
        bindHolder(holder, getItem(position))
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        (holder as? GifViewHolder)?.clear()
        super.onViewRecycled(holder)
    }

    private fun bindHolder(holder: RecyclerView.ViewHolder, item: GifListItem) {
        when {
            holder is GifViewHolder && item is GifListItem.Result -> {
                holder.bind(
                    gif = item.gif,
                    heightPx = cellHeightProvider(item.gif),
                )
            }
            holder is FooterViewHolder && item is GifListItem.Footer -> holder.bind(item.message)
        }
    }

    private fun createResultCell(context: Context): GifResultCell {
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(ImeColors.KEY)
        }
        val badge = TextView(context).apply {
            text = "GIF"
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ImeColors.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            background = roundedBackground(Color.argb(176, 0, 0, 0), radiusDp = 5)
            setPadding(dp(5), 0, dp(5), 0)
        }
        val container = FrameLayout(context).apply {
            background = roundedBackground(ImeColors.KEY, radiusDp = GIF_CARD_RADIUS_DP)
            clipToOutline = true
            isFocusable = false
            addView(
                image,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                badge,
                FrameLayout.LayoutParams(dp(34), dp(20), Gravity.TOP or Gravity.START).apply {
                    setMargins(dp(5), dp(5), 0, 0)
                },
            )
        }
        return GifResultCell(container = container, image = image)
    }

    private fun createFooterView(context: Context): TextView {
        return TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(ImeColors.SECTION_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(10), dp(14), dp(10), dp(18))
        }
    }

    private fun roundedBackground(color: Int, radiusDp: Int = ImeLayout.KEY_RADIUS_DP): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
        }
    }

    private data class GifResultCell(
        val container: FrameLayout,
        val image: ImageView,
    )

    private class GifViewHolder(
        private val cell: GifResultCell,
        private val onGifSelected: (GifSearchResult) -> Unit,
        private val dp: (Int) -> Int,
    ) : RecyclerView.ViewHolder(cell.container) {
        private var boundGif: GifSearchResult? = null

        fun bind(gif: GifSearchResult, heightPx: Int) {
            boundGif = gif
            itemView.visibility = View.VISIBLE
            itemView.isClickable = true
            itemView.contentDescription = gif.title
            itemView.setOnClickListener { onGifSelected(gif) }
            updateHeight(heightPx)

            val fallbackUrl = gif.previewFallbackUrl
            val manager = Glide.with(cell.image)
            val request = manager
                .load(gif.previewUrl)
                .override(MAX_DECODE_DIMENSION_PX)
                .fitCenter()
                .placeholder(placeholderDrawable())
                .error(ColorDrawable(ImeColors.KEY))
            if (fallbackUrl != null && fallbackUrl != gif.previewUrl) {
                request.error(
                    manager
                        .load(fallbackUrl)
                        .override(MAX_DECODE_DIMENSION_PX)
                        .fitCenter()
                        .placeholder(placeholderDrawable())
                        .error(ColorDrawable(ImeColors.KEY)),
                )
            }
            request.into(cell.image)
        }

        fun updateHeight(heightPx: Int) {
            val params = (itemView.layoutParams as? RecyclerView.LayoutParams)
                ?: RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    heightPx,
                )
            params.height = heightPx
            val margin = dp(GIF_CARD_SPACING_DP / 2)
            params.setMargins(margin, margin, margin, margin)
            itemView.layoutParams = params
        }

        fun clear() {
            boundGif = null
            itemView.setOnClickListener(null)
            Glide.with(cell.image).clear(cell.image)
            cell.image.setImageDrawable(ColorDrawable(ImeColors.KEY))
        }

        private fun placeholderDrawable(): ColorDrawable {
            return ColorDrawable(ImeColors.KEY)
        }
    }

    private class FooterViewHolder(
        private val textView: TextView,
    ) : RecyclerView.ViewHolder(textView) {
        fun bind(message: String) {
            textView.text = message
            val params = (itemView.layoutParams as? StaggeredGridLayoutManager.LayoutParams)
                ?: StaggeredGridLayoutManager.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                )
            params.isFullSpan = true
            itemView.layoutParams = params
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<GifListItem>() {
        override fun areItemsTheSame(oldItem: GifListItem, newItem: GifListItem): Boolean {
            return oldItem.stableId == newItem.stableId
        }

        override fun areContentsTheSame(oldItem: GifListItem, newItem: GifListItem): Boolean {
            return oldItem == newItem
        }
    }

    private companion object {
        const val VIEW_TYPE_GIF = 1
        const val VIEW_TYPE_FOOTER = 2
        const val MAX_DECODE_DIMENSION_PX = 360
        const val PAYLOAD_SIZE_CHANGED = "size"
    }
}
