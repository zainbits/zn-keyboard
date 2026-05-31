package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.zain.znkeyboard.KeyboardSettings
import dev.zain.znkeyboard.R
import dev.zain.znkeyboard.constants.ImeColors
import kotlin.math.roundToInt

class SnippetPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs),
    ClipboardHistoryListView.Callback {
    interface Callback {
        fun onSnippetSelected(snippet: String)
        fun onSnippetSearchRequested(tab: KeyboardSettings.SavedTextPanelTab)
        fun onSnippetSaveCurrentInput()
        fun onSnippetPanelClosed()
        fun onSavedTextPanelTabChanged(tab: KeyboardSettings.SavedTextPanelTab)
        fun onClipboardHistoryItemSelected(text: String)
        fun onClipboardHistoryItemDeleted(text: String)
    }

    var callback: Callback? = null

    private val masonryLayout = SnippetMasonryLayout(context).apply {
        setPadding(0, 0, 0, dp(2))
    }
    private val snippetScrollView = ScrollView(context).apply {
        isFillViewport = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        clipToPadding = false
        setBackgroundColor(PALETTE.background)
        addView(
            masonryLayout,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }
    private val snippetEmptyText = emptyMessage("No snippets")
    private val snippetContent = FrameLayout(context).apply {
        addView(
            snippetScrollView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        addView(
            snippetEmptyText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }
    private val clipboardList = ClipboardHistoryListView(context).apply {
        callback = this@SnippetPanelView
    }
    private val clipboardEmptyText = emptyMessage("No clipboard history yet")
    private val clipboardContent = FrameLayout(context).apply {
        addView(
            clipboardList,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        addView(
            clipboardEmptyText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }
    private val contentFrame = FrameLayout(context).apply {
        addView(
            snippetContent,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        addView(
            clipboardContent,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }
    private val searchBar = SavedTextPanelChrome.searchKey(
        context = context,
        label = "Search snippets",
        backgroundColor = PALETTE.function,
        textColor = PALETTE.mutedText,
        onClick = { callback?.onSnippetSearchRequested(selectedTab) },
    )
    private val saveButton = SavedTextPanelChrome.iconButton(
        context = context,
        iconResId = R.drawable.ic_save_24,
        contentDescription = "Save current input as snippet",
        backgroundColor = PALETTE.function,
        textColor = PALETTE.mutedText,
        onClick = { callback?.onSnippetSaveCurrentInput() },
    )
    private val clipboardTabButton = SavedTextPanelChrome.tabButton(
        context = context,
        label = KeyboardSettings.SavedTextPanelTab.Clipboard.label,
        selected = false,
        selectedColor = PALETTE.selected,
        unselectedColor = PALETTE.background,
        textColor = PALETTE.text,
        mutedTextColor = PALETTE.mutedText,
        onClick = { selectTab(KeyboardSettings.SavedTextPanelTab.Clipboard, notify = true) },
    )
    private val snippetsTabButton = SavedTextPanelChrome.tabButton(
        context = context,
        label = KeyboardSettings.SavedTextPanelTab.Snippets.label,
        selected = true,
        selectedColor = PALETTE.selected,
        unselectedColor = PALETTE.background,
        textColor = PALETTE.text,
        mutedTextColor = PALETTE.mutedText,
        onClick = { selectTab(KeyboardSettings.SavedTextPanelTab.Snippets, notify = true) },
    )
    private val tabBar = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = ImePressFeedback.roundedBackground(
            context = context,
            containerColor = PALETTE.function,
            contentColor = PALETTE.mutedText,
            radiusDp = 24,
        )
        addView(
            clipboardTabButton,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).withMargins(end = 2),
        )
        addView(
            snippetsTabButton,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).withMargins(start = 2),
        )
    }

    private var snippets = emptyList<KeyboardSettings.TextSnippet>()
    private var clipboardEntries = emptyList<ClipboardHistoryStore.Entry>()
    private var selectedTab = KeyboardSettings.SavedTextPanelTab.Snippets
    private var clipboardEnabled = true
    private var heightScale = 1f

    private val bottomSystemControlGapPx by lazy(LazyThreadSafetyMode.NONE) {
        ImeLayout.bottomSystemControlGapPx(context)
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(PALETTE.background)
        setPadding(
            dp(ImeLayout.HORIZONTAL_PADDING_DP),
            dp(ImeLayout.TOP_PADDING_DP),
            dp(ImeLayout.HORIZONTAL_PADDING_DP),
            dp(ImeLayout.BASE_BOTTOM_PADDING_DP) + bottomSystemControlGapPx.roundToInt(),
        )
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        isClickable = true

        addView(
            buildHeaderRow(),
            LayoutParams(LayoutParams.MATCH_PARENT, dp(42)).withMargins(bottom = 6),
        )
        addView(
            contentFrame,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )
        addView(
            tabBar,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).withMargins(top = 6),
        )
        rebuildSnippetCards()
        updateClipboardContent()
        selectTab(selectedTab, notify = false)
    }

    fun setHeightScale(scale: Float) {
        if (heightScale != scale) {
            heightScale = scale
            requestLayout()
        }
    }

    fun setSnippets(nextSnippets: List<KeyboardSettings.TextSnippet>) {
        val normalized = KeyboardSettings.normalizeTextSnippets(nextSnippets)
        if (snippets == normalized) return

        snippets = normalized
        rebuildSnippetCards()
    }

    fun submitClipboardHistory(entries: List<ClipboardHistoryStore.Entry>) {
        val normalized = entries.take(ClipboardHistoryStore.MAX_HISTORY)
        if (clipboardEntries == normalized) return

        clipboardEntries = normalized
        updateClipboardContent()
    }

    fun setSelectedTab(tab: KeyboardSettings.SavedTextPanelTab) {
        selectTab(tab, notify = false)
    }

    fun setClipboardEnabled(enabled: Boolean) {
        if (clipboardEnabled == enabled) return
        clipboardEnabled = enabled
        clipboardTabButton.alpha = if (enabled) 1f else 0.36f
        clipboardTabButton.isEnabled = enabled
        clipboardTabButton.isClickable = enabled
        if (!enabled && selectedTab == KeyboardSettings.SavedTextPanelTab.Clipboard) {
            selectTab(KeyboardSettings.SavedTextPanelTab.Snippets, notify = true)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = ((ImeLayout.BASE_HEIGHT_DP + ImeLayout.EXTENDED_PANEL_EXTRA_HEIGHT_DP) *
            heightScale *
            resources.displayMetrics.density +
            bottomSystemControlGapPx)
            .roundToInt()
        val exactHeightSpec = MeasureSpec.makeMeasureSpec(resolveSize(desiredHeight, heightMeasureSpec), MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, exactHeightSpec)
    }

    override fun onClipboardHistoryItemSelected(text: String) {
        callback?.onClipboardHistoryItemSelected(text)
    }

    override fun onClipboardHistoryItemDeleted(text: String) {
        callback?.onClipboardHistoryItemDeleted(text)
    }

    private fun selectTab(tab: KeyboardSettings.SavedTextPanelTab, notify: Boolean) {
        val nextTab = if (tab == KeyboardSettings.SavedTextPanelTab.Clipboard && !clipboardEnabled) {
            KeyboardSettings.SavedTextPanelTab.Snippets
        } else {
            tab
        }
        val changed = selectedTab != nextTab
        selectedTab = nextTab

        snippetContent.visibility = if (nextTab == KeyboardSettings.SavedTextPanelTab.Snippets) VISIBLE else GONE
        clipboardContent.visibility = if (nextTab == KeyboardSettings.SavedTextPanelTab.Clipboard) VISIBLE else GONE
        searchBar.text = nextTab.searchLabel
        searchBar.contentDescription = nextTab.searchLabel
        saveButton.visibility = if (nextTab == KeyboardSettings.SavedTextPanelTab.Snippets) VISIBLE else GONE
        clipboardTabButton.updateForTab(selected = nextTab == KeyboardSettings.SavedTextPanelTab.Clipboard)
        snippetsTabButton.updateForTab(selected = nextTab == KeyboardSettings.SavedTextPanelTab.Snippets)

        if (notify && changed) {
            callback?.onSavedTextPanelTabChanged(nextTab)
        }
    }

    private fun TextView.updateForTab(selected: Boolean) {
        with(SavedTextPanelChrome) {
            updateTabButton(
                selected = selected,
                selectedColor = PALETTE.selected,
                unselectedColor = PALETTE.background,
                textColor = PALETTE.text,
                mutedTextColor = PALETTE.mutedText,
            )
        }
    }

    private fun rebuildSnippetCards() {
        masonryLayout.removeAllViews()
        snippets.forEach { snippet ->
            masonryLayout.addView(
                snippetCard(snippet),
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val empty = snippets.isEmpty()
        snippetEmptyText.visibility = if (empty) VISIBLE else GONE
        snippetScrollView.visibility = if (empty) GONE else VISIBLE
    }

    private fun updateClipboardContent() {
        clipboardList.submitHistory(clipboardEntries)
        val empty = clipboardEntries.isEmpty()
        clipboardEmptyText.visibility = if (empty) VISIBLE else GONE
        clipboardList.visibility = if (empty) GONE else VISIBLE
    }

    private fun buildHeaderRow(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ImePanelChrome.backButton(
                    context = context,
                    contentDescription = "Back to keyboard",
                    backgroundColor = PALETTE.function,
                    textColor = PALETTE.mutedText,
                    onClick = { callback?.onSnippetPanelClosed() },
                ),
                LayoutParams(dp(58), LayoutParams.MATCH_PARENT).withMargins(end = 4),
            )
            addView(
                searchBar,
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).withMargins(horizontal = 2),
            )
            addView(
                saveButton,
                LayoutParams(dp(58), LayoutParams.MATCH_PARENT).withMargins(start = 4),
            )
        }
    }

    private fun snippetCard(snippet: KeyboardSettings.TextSnippet): LinearLayout {
        return SavedTextSnippetCard.create(
            context = context,
            snippet = snippet,
            style = SavedTextSnippetCard.Style(
                backgroundColor = PALETTE.key,
                textColor = PALETTE.text,
                tagTextColor = PALETTE.tagText,
                radiusDp = 7,
                strokeWidthDp = 1,
                strokeColor = PALETTE.border,
                verticalPaddingDp = 10,
                minHeightDp = 50,
                textSizeSp = 14.5f,
                lineSpacingDp = 2,
                maxLines = SNIPPET_CARD_MAX_LINES,
                tagTopMarginDp = 6,
            ),
            onClick = { callback?.onSnippetSelected(it) },
        )
    }

    private fun emptyMessage(message: String): TextView {
        return TextView(context).apply {
            text = message
            gravity = Gravity.CENTER
            setTextColor(PALETTE.mutedText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            includeFontPadding = false
            visibility = GONE
        }
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

    private class SnippetMasonryLayout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : ViewGroup(context, attrs) {
        private val childBounds = mutableListOf<Rect>()
        private val columnHeights = IntArray(COLUMN_COUNT)
        private val horizontalGapPx = dp(CARD_GAP_DP)
        private val verticalGapPx = dp(CARD_GAP_DP)
        private val maxCardHeightPx = dp(MAX_CARD_HEIGHT_DP)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val widthMode = MeasureSpec.getMode(widthMeasureSpec)
            val widthSize = MeasureSpec.getSize(widthMeasureSpec)
            val measuredWidth = if (widthMode == MeasureSpec.UNSPECIFIED) {
                suggestedMinimumWidth
            } else {
                widthSize
            }
            val availableWidth = (measuredWidth - paddingLeft - paddingRight - horizontalGapPx * (COLUMN_COUNT - 1))
                .coerceAtLeast(0)
            val childWidth = availableWidth / COLUMN_COUNT
            val childWidthSpec = MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY)
            val childHeightSpec = MeasureSpec.makeMeasureSpec(maxCardHeightPx, MeasureSpec.AT_MOST)
            var visibleChildren = 0

            childBounds.clear()
            columnHeights.fill(paddingTop)

            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child.visibility == GONE) {
                    childBounds += Rect()
                    continue
                }

                child.measure(childWidthSpec, childHeightSpec)
                val column = shortestColumn()
                val left = paddingLeft + column * (childWidth + horizontalGapPx)
                val top = columnHeights[column]
                val right = left + childWidth
                val bottom = top + child.measuredHeight
                childBounds += Rect(left, top, right, bottom)
                columnHeights[column] = bottom + verticalGapPx
                visibleChildren += 1
            }

            val contentHeight = if (visibleChildren == 0) {
                paddingTop + paddingBottom
            } else {
                val tallestColumn = columnHeights.maxOrNull() ?: paddingTop
                tallestColumn - verticalGapPx + paddingBottom
            }
            setMeasuredDimension(
                resolveSize(measuredWidth, widthMeasureSpec),
                resolveSize(contentHeight.coerceAtLeast(suggestedMinimumHeight), heightMeasureSpec),
            )
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child.visibility == GONE) continue
                val bounds = childBounds.getOrNull(index) ?: continue
                child.layout(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
        }

        override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams {
            return ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        private fun shortestColumn(): Int {
            var shortestIndex = 0
            var shortestHeight = columnHeights[0]
            for (index in 1 until COLUMN_COUNT) {
                if (columnHeights[index] < shortestHeight) {
                    shortestIndex = index
                    shortestHeight = columnHeights[index]
                }
            }
            return shortestIndex
        }

        private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

        private companion object {
            const val COLUMN_COUNT = 2
            const val CARD_GAP_DP = 6
            const val MAX_CARD_HEIGHT_DP = 136
        }
    }

    private object PALETTE {
        val background = ImeColors.BACKGROUND
        val key = ImeColors.KEY_DARK
        val function = ImeColors.FUNCTION_DARK
        val selected = ImeColors.SELECTED
        val border = ImeColors.BORDER
        const val text = ImeColors.TEXT
        val tagText = ImeColors.TAG_TEXT
        val mutedText = ImeColors.MUTED_TEXT
    }

    private companion object {
        const val SNIPPET_CARD_MAX_LINES = 6
    }
}
