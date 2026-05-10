package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.AttributeSet
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.zain.znkeyboard.KeyboardSettings
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

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
    private val previewLoader = GifPreviewLoader(mainHandler)
    private val suggestionButtons = mutableListOf<TextView>()

    private val queryText = TextView(context)
    private val browseHeader = browseHeaderRow()
    private val searchHeader = searchRow().apply {
        visibility = GONE
    }
    private val resultContent = LinearLayout(context).apply {
        orientation = VERTICAL
        clipToPadding = false
    }
    private val resultScroll = ScrollView(context).apply {
        clipToPadding = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        setPadding(0, dp(4), 0, dp(8))
        setOnScrollChangeListener { view, _, scrollY, _, _ ->
            val child = (view as? ScrollView)?.getChildAt(0) ?: return@setOnScrollChangeListener
            if (scrollY + height >= child.height - dp(LOAD_MORE_THRESHOLD_DP)) {
                loadNextPageIfNeeded()
            }
        }
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
        resultScroll.addView(
            resultContent,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        addView(resultScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).withMargins(top = 4))
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
        stopAnimatedPreviews(resultContent)
        requestExecutor.shutdownNow()
        previewLoader.shutdown()
    }

    override fun onDetachedFromWindow() {
        requestGeneration++
        cancelPendingRefresh()
        stopAnimatedPreviews(resultContent)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView == this) {
            if (visibility == VISIBLE) {
                startAnimatedPreviews(resultContent)
            } else {
                pauseAnimatedPreviews(resultContent)
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
                    contentDescription = "Back to emoji",
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
                    contentDescription = "Back to GIFs",
                    backgroundColor = PALETTE.function,
                    textColor = PALETTE.mutedText,
                    onClick = {
                        if (mode == Mode.Search) {
                            callback?.onGifSearchBackToBrowse()
                        } else {
                            showBrowseMode()
                        }
                    },
                ),
                LayoutParams(dp(ImePanelChrome.BACK_BUTTON_WIDTH_DP), LayoutParams.MATCH_PARENT).withMargins(end = 4),
            )

            queryText.apply {
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(PALETTE.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(dp(14), 0, dp(14), 0)
                background = roundedBackground(PALETTE.function)
                isClickable = true
                isFocusable = false
                contentDescription = "Edit GIF search"
                setOnClickListener {
                    if (mode == Mode.Browse) {
                        callback?.onGifSearchRequested()
                    }
                }
            }
            addView(
                queryText,
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

    private fun updateQueryText() {
        queryText.text = query.ifBlank { SEARCH_PLACEHOLDER }
        queryText.setTextColor(if (query.isBlank()) PALETTE.placeholderText else PALETTE.text)
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
            renderResultMasonry(results, footer, resetScroll)
        }
    }

    private fun renderResultMasonry(results: List<GifSearchResult>, footer: String?, resetScroll: Boolean) {
        val previousScrollY = resultScroll.scrollY
        clearResultContent()

        val columnCount = masonryColumnCount()
        val gapPx = dp(MASONRY_GAP_DP)
        val availableWidth = (
            resultScroll.width
                .takeIf { it > 0 }
                ?: (resources.displayMetrics.widthPixels - paddingLeft - paddingRight)
            )
            .minus(resultScroll.paddingLeft + resultScroll.paddingRight)
            .coerceAtLeast(dp(240))
        val columnWidth = ((availableWidth - gapPx * (columnCount - 1)) / columnCount).coerceAtLeast(dp(96))
        val columns = List(columnCount) { mutableListOf<MasonryCell>() }
        val columnHeights = IntArray(columnCount)

        results.forEach { gif ->
            val targetColumn = columnHeights.indices.minBy { columnHeights[it] }
            val cellHeight = masonryCellHeight(gif, columnWidth)
            columns[targetColumn] += MasonryCell(gif = gif, heightPx = cellHeight)
            columnHeights[targetColumn] += cellHeight + gapPx
        }

        val grid = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.TOP
        }
        columns.forEachIndexed { index, columnCells ->
            val column = LinearLayout(context).apply {
                orientation = VERTICAL
            }
            columnCells.forEach { cell ->
                column.addView(
                    createResultCellView(cell.gif),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, cell.heightPx).apply {
                        bottomMargin = gapPx
                    },
                )
            }
            grid.addView(
                column,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index < columns.lastIndex) {
                        marginEnd = gapPx
                    }
                },
            )
        }

        resultContent.addView(
            grid,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        footer?.let(::addFooterMessage)
        resultScroll.post {
            if (resetScroll) {
                resultScroll.scrollTo(0, 0)
            } else {
                val maxScrollY = (resultContent.height - resultScroll.height).coerceAtLeast(0)
                resultScroll.scrollTo(0, previousScrollY.coerceAtMost(maxScrollY))
            }
        }
    }

    private fun createResultCellView(gif: GifSearchResult): View {
        val cell = createResultCell()
        return cell.container.apply {
            visibility = VISIBLE
            isClickable = true
            contentDescription = gif.title
            setOnClickListener { callback?.onGifSelected(gif) }
            previewLoader.load(gif.previewUrl, cell.image, gif.previewFallbackUrl)
        }
    }

    private fun createResultCell(): GifResultCell {
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(PALETTE.key)
        }
        val badge = TextView(context).apply {
            text = "GIF"
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(PALETTE.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            background = roundedBackground(Color.argb(176, 0, 0, 0), radiusDp = 5)
            setPadding(dp(5), 0, dp(5), 0)
        }
        val container = FrameLayout(context).apply {
            background = roundedBackground(PALETTE.key)
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

    private fun renderResultMessage(message: String) {
        clearResultContent()
        resultContent.addView(
            messageTextView(message).apply {
                minHeight = dp(140)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        resultScroll.post { resultScroll.scrollTo(0, 0) }
    }

    private fun addFooterMessage(message: String) {
        resultContent.addView(
            messageTextView(message).apply {
                setPadding(dp(10), dp(14), dp(10), dp(18))
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
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
        val availableWidth = resultScroll.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        return if (availableWidth >= dp(460)) 3 else 2
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
        stopAnimatedPreviews(resultContent)
        resultContent.removeAllViews()
    }

    private fun startAnimatedPreviews(view: View) {
        if (view is ImageView) {
            (view.drawable as? AnimatedImageDrawable)?.start()
            return
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                startAnimatedPreviews(view.getChildAt(index))
            }
        }
    }

    private fun pauseAnimatedPreviews(view: View) {
        if (view is ImageView) {
            (view.drawable as? AnimatedImageDrawable)?.stop()
            return
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                pauseAnimatedPreviews(view.getChildAt(index))
            }
        }
    }

    private fun stopAnimatedPreviews(view: View) {
        if (view is ImageView) {
            (view.drawable as? AnimatedImageDrawable)?.stop()
            view.setImageDrawable(null)
            view.tag = null
            return
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                stopAnimatedPreviews(view.getChildAt(index))
            }
        }
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
            background = roundedBackground(PALETTE.function)
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
            background = roundedBackground(if (active) PALETTE.selected else PALETTE.function)
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
            background = ColorDrawable(Color.TRANSPARENT)
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
            background = roundedBackground(PALETTE.function)
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

        val listParams = resultScroll.layoutParams as? LayoutParams
        if (listParams != null) {
            if (mode == Mode.Browse) {
                listParams.height = 0
                listParams.weight = 1f
            } else {
                listParams.height = dp(SEARCH_RESULT_LIST_HEIGHT_DP)
                listParams.weight = 0f
            }
            resultScroll.layoutParams = listParams
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

    private data class MasonryCell(
        val gif: GifSearchResult,
        val heightPx: Int,
    )

    private data class GifResultCell(
        val container: FrameLayout,
        val image: ImageView,
    )

    private enum class Mode {
        Browse,
        Search,
    }

    private object PALETTE {
        const val background = Color.BLACK
        val key = Color.rgb(48, 48, 48)
        val function = Color.rgb(56, 56, 56)
        val selected = Color.rgb(78, 78, 78)
        const val text = Color.WHITE
        val mutedText = Color.rgb(230, 230, 230)
        val disabledText = Color.rgb(130, 130, 130)
        val placeholderText = Color.rgb(150, 150, 150)
        val sectionText = Color.rgb(145, 145, 145)
    }

    private companion object {
        const val FIRST_PAGE = 1
        const val PAGE_SIZE = 24
        const val LOAD_MORE_THRESHOLD_DP = 180
        const val MASONRY_GAP_DP = 4
        const val MIN_GIF_CELL_HEIGHT_DP = 88
        const val MAX_GIF_CELL_HEIGHT_DP = 190
        const val DEFAULT_GIF_ASPECT_HEIGHT = 0.8f
        const val SEARCH_RESULT_LIST_HEIGHT_DP = 190
        const val MAX_QUERY_LENGTH = 80
        const val MIN_AUTOCOMPLETE_CHARS = 2
        const val MAX_VISIBLE_SUGGESTIONS = 6
        const val SEARCH_DEBOUNCE_MS = 450L
        const val SEARCH_PLACEHOLDER = "Search KLIPY"
        const val SEARCH_PROMPT = "Type a GIF search and tap Search"
        const val POWERED_BY_KLIPY = "Powered by KLIPY"
    }
}

private class GifPreviewLoader(
    private val mainHandler: Handler,
) {
    private val executor: ExecutorService = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "ZnKeyboardGifPreview")
    }
    private val cache = object : LruCache<String, ByteArray>(CACHE_SIZE_KB) {
        override fun sizeOf(key: String, value: ByteArray): Int {
            return (value.size / 1024).coerceAtLeast(1)
        }
    }

    fun load(url: String, imageView: ImageView, fallbackUrl: String? = null) {
        val request = PreviewRequest(primaryUrl = url, fallbackUrl = fallbackUrl)
        imageView.tag = request
        (imageView.drawable as? AnimatedImageDrawable)?.stop()
        imageView.setImageDrawable(ColorDrawable(PALETTE.key))

        cache.get(url)?.let { bytes ->
            decodeAndApply(request, bytes, imageView, isFallback = false)
            return
        }

        executor.execute {
            val bytes = runCatching { downloadPreviewBytes(url) }.getOrNull()
            if (bytes != null) {
                cache.put(url, bytes)
            }
            val drawable = bytes?.let { runCatching { decodePreviewDrawable(it) }.getOrNull() }
            if (drawable == null && fallbackUrl != null && fallbackUrl != url) {
                loadFallback(request, imageView, fallbackUrl)
            } else {
                apply(request, imageView, drawable)
            }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun decodeAndApply(
        request: PreviewRequest,
        bytes: ByteArray,
        imageView: ImageView,
        isFallback: Boolean,
    ) {
        executor.execute {
            val drawable = runCatching { decodePreviewDrawable(bytes) }.getOrNull()
            if (drawable == null && !isFallback && request.fallbackUrl != null) {
                loadFallback(request, imageView, request.fallbackUrl)
            } else {
                apply(request, imageView, drawable)
            }
        }
    }

    private fun loadFallback(request: PreviewRequest, imageView: ImageView, fallbackUrl: String) {
        cache.get(fallbackUrl)?.let { bytes ->
            decodeAndApply(request, bytes, imageView, isFallback = true)
            return
        }
        val bytes = runCatching { downloadPreviewBytes(fallbackUrl) }.getOrNull()
        if (bytes != null) {
            cache.put(fallbackUrl, bytes)
        }
        val drawable = bytes?.let { runCatching { decodePreviewDrawable(it) }.getOrNull() }
        apply(request, imageView, drawable)
    }

    private fun apply(request: PreviewRequest, imageView: ImageView, drawable: Drawable?) {
        mainHandler.post {
            if (imageView.tag != request) return@post
            if (drawable != null) {
                imageView.setImageDrawable(drawable)
                if (drawable is AnimatedImageDrawable) {
                    drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    drawable.start()
                }
            } else {
                imageView.setImageDrawable(ColorDrawable(PALETTE.key))
            }
        }
    }

    private fun decodePreviewDrawable(bytes: ByteArray): Drawable {
        val source = ImageDecoder.createSource(bytes)
        return ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val longestEdge = maxOf(width, height)
            if (longestEdge > MAX_DECODE_DIMENSION_PX && width > 0 && height > 0) {
                val scale = MAX_DECODE_DIMENSION_PX.toFloat() / longestEdge.toFloat()
                decoder.setTargetSize(
                    (width * scale).roundToInt().coerceAtLeast(1),
                    (height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }
    }

    private fun downloadPreviewBytes(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = PREVIEW_TIMEOUT_MS
            readTimeout = PREVIEW_TIMEOUT_MS
            setRequestProperty("Accept", "image/*,*/*")
        }

        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("Preview download failed ($responseCode).")
            }
            val output = ByteArrayOutputStream()
            var total = 0
            connection.inputStream.use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_PREVIEW_BYTES) {
                        throw IOException("Preview is too large.")
                    }
                    output.write(buffer, 0, read)
                }
            }
            output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private data class PreviewRequest(
        val primaryUrl: String,
        val fallbackUrl: String?,
    )

    private object PALETTE {
        val key = Color.rgb(48, 48, 48)
    }

    private companion object {
        const val CACHE_SIZE_KB = 12 * 1024
        const val PREVIEW_TIMEOUT_MS = 10_000
        const val MAX_PREVIEW_BYTES = 2_500_000
        const val MAX_DECODE_DIMENSION_PX = 360
        const val BUFFER_SIZE = 8 * 1024
    }
}
