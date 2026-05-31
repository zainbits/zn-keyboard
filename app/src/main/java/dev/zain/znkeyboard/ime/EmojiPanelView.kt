package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.zain.znkeyboard.EmojiCatalog
import dev.zain.znkeyboard.EmojiCategory
import dev.zain.znkeyboard.EmojiEntry
import dev.zain.znkeyboard.EmojiSkinTone
import dev.zain.znkeyboard.R
import dev.zain.znkeyboard.constants.ImeColors
import kotlin.math.roundToInt

class EmojiPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    interface Callback {
        fun onEmojiSelected(emoji: String, updatedRecentEmojis: List<String>)
        fun onEmojiSearchRequested()
        fun onGifPanelRequested()
        fun onEmojiPanelClosed()
        fun onEmojiBackspace()
        fun onEmojiSpace()
    }

    var callback: Callback? = null

    private var recentEmojis = emptyList<String>()
    private var pendingRecentEmojis: List<String>? = null
    private var recentRowCount = EmojiCatalog.DEFAULT_RECENT_ROW_COUNT
    private var defaultSkinTone = EmojiSkinTone.Default
    private var heightScale = 1f
    private val variantPopup = EmojiVariantPopupController(
        context = context,
        backgroundColor = PALETTE.function,
        textColor = PALETTE.text,
        pressedColor = ImeColors.KEY,
        onEmojiSelected = ::selectEmoji,
    )
    private var selectedSection: EmojiSectionKey = EmojiSectionKey.Recent
    private var pendingProgrammaticSection: EmojiSectionKey? = null

    private lateinit var recentShortcutButton: View
    private val categoryButtons = mutableMapOf<EmojiCategory, TextView>()
    private val sectionPositions = mutableMapOf<EmojiSectionKey, Int>()

    private val adapter = EmojiListAdapter(
        context = context,
        displayEmoji = ::displayEmoji,
        onEmojiClick = ::selectEmoji,
        onEmojiLongClick = ::showVariantPopup,
    )
    private val browseHeaderStrip = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = false
    }
    private val browseHeaderRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val emojiList = ListView(context).apply {
        adapter = this@EmojiPanelView.adapter
        divider = null
        cacheColorHint = Color.TRANSPARENT
        selector = ColorDrawable(Color.TRANSPARENT)
        clipToPadding = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        setPadding(0, dp(6), 0, dp(18))
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
        setPadding(
            dp(ImeLayout.HORIZONTAL_PADDING_DP),
            dp(ImeLayout.TOP_PADDING_DP),
            dp(ImeLayout.HORIZONTAL_PADDING_DP),
            dp(ImeLayout.BASE_BOTTOM_PADDING_DP) + bottomSystemControlGapPx.roundToInt(),
        )
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        isClickable = true

        buildBrowseHeader()
        buildBottomToolbar()

        addView(browseHeaderStrip, LayoutParams(LayoutParams.MATCH_PARENT, dp(ImePanelChrome.BROWSE_HEADER_HEIGHT_DP)))
        addView(
            emojiList,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).withMargins(top = 4, bottom = 4),
        )
        addView(bottomToolbar, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))

        emojiList.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit

            override fun onScroll(
                view: AbsListView?,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int,
            ) {
                if (totalItemCount == 0) return
                syncSelectedSectionWithScroll()
            }
        })

        updateMode(resetScroll = true)
    }

    fun setHeightScale(scale: Float) {
        if (heightScale != scale) {
            heightScale = scale
            requestLayout()
        }
    }

    fun setRecentEmojis(emojis: List<String>) {
        val normalized = EmojiCatalog.normalizeRecentEmojis(emojis)
        pendingRecentEmojis = null
        if (recentEmojis != normalized) {
            recentEmojis = normalized
            updateListRows(keepScroll = true)
        }
    }

    fun setRecentRowCount(rowCount: Int) {
        val normalizedRowCount = EmojiCatalog.normalizeRecentRowCount(rowCount)
        if (recentRowCount != normalizedRowCount) {
            recentRowCount = normalizedRowCount
            updateListRows(keepScroll = true)
        }
    }

    fun setDefaultSkinTone(skinTone: EmojiSkinTone) {
        if (defaultSkinTone != skinTone) {
            defaultSkinTone = skinTone
            adapter.notifyDataSetChanged()
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

    private fun buildBrowseHeader() {
        browseHeaderRow.addView(
            ImePanelChrome.backButton(
                context = context,
                contentDescription = "Back to keyboard",
                backgroundColor = PALETTE.function,
                textColor = PALETTE.mutedText,
                onClick = ::closePanel,
            ),
            LayoutParams(dp(ImePanelChrome.BACK_BUTTON_WIDTH_DP), LayoutParams.MATCH_PARENT).withMargins(end = 4),
        )

        browseHeaderRow.addView(
            ImePanelChrome.searchButton(
                context = context,
                contentDescription = "Search emoji",
                backgroundColor = PALETTE.function,
                textColor = PALETTE.mutedText,
                onClick = { callback?.onEmojiSearchRequested() },
            ),
            LayoutParams(dp(ImePanelChrome.SEARCH_BUTTON_WIDTH_DP), LayoutParams.MATCH_PARENT).withMargins(end = 6),
        )

        recentShortcutButton = recentShortcutButton {
            scrollToSection(EmojiSectionKey.Recent)
        }
        browseHeaderRow.addView(
            recentShortcutButton,
            LayoutParams(dp(40), LayoutParams.MATCH_PARENT).withMargins(horizontal = 1),
        )

        EmojiCatalog.categories.forEach { category ->
            val button = categoryShortcut(category.icon, category.title) {
                scrollToSection(EmojiSectionKey.Category(category))
            }
            categoryButtons[category] = button
            browseHeaderRow.addView(
                button,
                LayoutParams(dp(40), LayoutParams.MATCH_PARENT).withMargins(horizontal = 1),
            )
        }

        browseHeaderStrip.addView(
            browseHeaderRow,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun buildBottomToolbar() {
        ImeModeToolbar.populate(
            toolbar = bottomToolbar,
            colors = ImeModeToolbar.Colors(
                function = PALETTE.function,
                selected = PALETTE.selected,
                text = PALETTE.text,
                mutedText = PALETTE.mutedText,
                disabledText = PALETTE.disabledText,
            ),
            activeTab = ImeModeToolbar.ActiveTab.Emoji,
            onKeyboard = ::closePanel,
            onEmoji = null,
            onGif = { callback?.onGifPanelRequested() },
            onBackspace = { callback?.onEmojiBackspace() },
        )
    }

    private fun updateMode(resetScroll: Boolean) {
        updateListRows(keepScroll = !resetScroll)
        if (resetScroll) {
            emojiList.setSelection(0)
        }
        updateCategoryButtons()
    }

    private fun updateListRows(keepScroll: Boolean) {
        val scrollAnchor = if (keepScroll) {
            adapter.scrollAnchorForPosition(
                position = emojiList.firstVisiblePosition,
                top = emojiList.getChildAt(0)?.top ?: emojiList.paddingTop,
            )
        } else {
            null
        }
        val rows = buildListRows()

        adapter.submitRows(rows)

        var restoredPosition: Int? = null
        if (keepScroll && rows.isNotEmpty() && scrollAnchor != null) {
            val anchoredPosition = adapter.positionForScrollAnchor(scrollAnchor)
                ?: emojiList.firstVisiblePosition.coerceAtMost(rows.lastIndex)
            emojiList.setSelectionFromTop(anchoredPosition, scrollAnchor.top)
            restoredPosition = anchoredPosition
        }

        val nextSection = adapter.sectionForPosition(restoredPosition ?: emojiList.firstVisiblePosition)
            ?: sectionPositions.keys.firstOrNull()
            ?: EmojiSectionKey.Recent
        if (selectedSection != nextSection) {
            selectedSection = nextSection
        }
        updateCategoryButtons()
    }

    private fun buildListRows(): List<EmojiListRow> {
        sectionPositions.clear()
        return buildList {
            addSection(EmojiSectionKey.Recent, "Recent emoji", recentEntries())
            EmojiCatalog.categories.forEach { category ->
                addSection(
                    key = EmojiSectionKey.Category(category),
                    title = sectionTitle(category),
                    entries = EmojiCatalog.entriesByCategory[category].orEmpty(),
                )
            }
        }
    }

    private fun MutableList<EmojiListRow>.addSection(
        key: EmojiSectionKey,
        title: String,
        entries: List<EmojiEntry>,
    ) {
        if (entries.isEmpty()) return
        sectionPositions[key] = size
        add(EmojiListRow.Header(key, title))
        entries.chunked(EmojiCatalog.RECENT_COLUMN_COUNT).forEach { rowEntries ->
            add(EmojiListRow.Emojis(key, rowEntries))
        }
    }

    private fun recentEntries(): List<EmojiEntry> {
        return recentEmojis
            .take(recentRowCount * EmojiCatalog.RECENT_COLUMN_COUNT)
            .mapNotNull(EmojiCatalog::entryForEmoji)
    }

    private fun sectionTitle(category: EmojiCategory): String {
        return when (category) {
            EmojiCategory.SmileysEmotion -> "Smileys and emotions"
            EmojiCategory.PeopleBody -> "People and body"
            EmojiCategory.AnimalsNature -> "Animals and nature"
            EmojiCategory.FoodDrink -> "Food and drink"
            EmojiCategory.TravelPlaces -> "Travel and places"
            EmojiCategory.Activities -> "Activities"
            EmojiCategory.Objects -> "Objects"
            EmojiCategory.Symbols -> "Symbols"
            EmojiCategory.Flags -> "Flags"
        }
    }

    private fun scrollToSection(section: EmojiSectionKey) {
        pendingProgrammaticSection = section
        selectedSection = section
        updateCategoryButtons()
        val position = sectionPositions[section] ?: 0
        emojiList.setSelectionFromTop(position, emojiList.paddingTop)
        emojiList.post {
            if (pendingProgrammaticSection == section) {
                pendingProgrammaticSection = null
                selectedSection = section
                updateCategoryButtons()
            }
        }
    }

    private fun syncSelectedSectionWithScroll() {
        val section = sectionAtListContentTop() ?: return
        pendingProgrammaticSection?.let { pendingSection ->
            if (section != pendingSection) return
            pendingProgrammaticSection = null
        }
        if (selectedSection != section) {
            selectedSection = section
            updateCategoryButtons()
        }
    }

    private fun sectionAtListContentTop(): EmojiSectionKey? {
        // firstVisiblePosition can include a tiny sliver of the previous row near the top padding.
        val contentTop = emojiList.paddingTop + dp(4)
        for (childIndex in 0 until emojiList.childCount) {
            val child = emojiList.getChildAt(childIndex)
            if (child.bottom > contentTop) {
                return adapter.sectionForPosition(emojiList.firstVisiblePosition + childIndex)
            }
        }
        return adapter.sectionForPosition(emojiList.firstVisiblePosition)
    }

    private fun updateCategoryButtons() {
        recentShortcutButton.background = categoryShortcutBackground(selectedSection == EmojiSectionKey.Recent)
        recentShortcutButton.alpha = if (sectionPositions.containsKey(EmojiSectionKey.Recent)) 1f else 0.42f
        categoryButtons.forEach { (category, button) ->
            val section = EmojiSectionKey.Category(category)
            button.background = categoryShortcutBackground(selectedSection == section)
            button.alpha = if (sectionPositions.containsKey(section)) 1f else 0.42f
        }
    }

    private fun displayEmoji(entry: EmojiEntry): String {
        return EmojiCatalog.displayEmoji(entry, defaultSkinTone)
    }

    private fun selectEmoji(emoji: String) {
        dismissVariantPopup()
        val updatedRecentEmojis = EmojiCatalog.promoteRecentEmoji(
            emoji = emoji,
            current = pendingRecentEmojis ?: recentEmojis,
        )
        pendingRecentEmojis = updatedRecentEmojis
        callback?.onEmojiSelected(emoji, updatedRecentEmojis)
    }

    private fun closePanel() {
        dismissVariantPopup()
        updateMode(resetScroll = true)
        callback?.onEmojiPanelClosed()
    }

    private fun showVariantPopup(entry: EmojiEntry, anchor: View): Boolean {
        return variantPopup.show(entry, anchor)
    }

    private fun dismissVariantPopup() {
        variantPopup.dismiss()
    }

    private fun categoryShortcut(label: String, description: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            contentDescription = description
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = false
            typeface = Typeface.DEFAULT
            setTextColor(PALETTE.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            background = categoryShortcutBackground(active = false)
            setOnClickListener { onClick() }
        }
    }

    private fun recentShortcutButton(onClick: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            contentDescription = "Recently used"
            isClickable = true
            isFocusable = false
            scaleType = ImageView.ScaleType.CENTER
            setPadding(0, 0, 0, 0)
            setImageDrawable(tintedIcon(R.drawable.ic_history_24, PALETTE.text))
            background = categoryShortcutBackground(active = false)
            setOnClickListener { onClick() }
        }
    }

    private fun tintedIcon(iconResId: Int, color: Int) =
        ContextCompat.getDrawable(context, iconResId)?.mutate()?.apply {
            setTint(color)
        }

    private fun categoryShortcutBackground(active: Boolean) =
        ImePressFeedback.roundedBackground(
            context = context,
            containerColor = if (active) PALETTE.selected else Color.TRANSPARENT,
            contentColor = PALETTE.text,
        )

    private fun LinearLayout.LayoutParams.withMargins(
        horizontal: Int = 0,
        vertical: Int = 0,
        start: Int = horizontal,
        top: Int = vertical,
        end: Int = horizontal,
        bottom: Int = vertical,
    ): LinearLayout.LayoutParams {
        setMargins(dp(start), dp(top), dp(end), dp(bottom))
        return this
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private sealed class EmojiSectionKey {
        data object Recent : EmojiSectionKey()
        data class Category(val category: EmojiCategory) : EmojiSectionKey()
    }

    private sealed class EmojiListRow {
        data class Header(val section: EmojiSectionKey, val title: String) : EmojiListRow()
        data class Emojis(val section: EmojiSectionKey, val entries: List<EmojiEntry>) : EmojiListRow()
        data class Message(val text: String) : EmojiListRow()
    }

    private data class EmojiScrollAnchor(
        val section: EmojiSectionKey,
        val rowOffsetInSection: Int,
        val top: Int,
    )

    private class EmojiListAdapter(
        private val context: Context,
        private val displayEmoji: (EmojiEntry) -> String,
        private val onEmojiClick: (String) -> Unit,
        private val onEmojiLongClick: (EmojiEntry, View) -> Boolean,
    ) : BaseAdapter() {
        private var rows: List<EmojiListRow> = emptyList()

        fun submitRows(newRows: List<EmojiListRow>) {
            rows = newRows
            notifyDataSetChanged()
        }

        fun sectionForPosition(position: Int): EmojiSectionKey? {
            return rows.getOrNull(position)?.section
        }

        fun scrollAnchorForPosition(position: Int, top: Int): EmojiScrollAnchor? {
            val section = sectionForPosition(position) ?: return null
            val sectionStart = firstPositionForSection(section) ?: return null
            return EmojiScrollAnchor(
                section = section,
                rowOffsetInSection = position - sectionStart,
                top = top,
            )
        }

        fun positionForScrollAnchor(anchor: EmojiScrollAnchor): Int? {
            val sectionStart = firstPositionForSection(anchor.section) ?: return null
            val sectionEnd = firstPositionAfterSection(anchor.section, sectionStart)
            return (sectionStart + anchor.rowOffsetInSection).coerceAtMost(sectionEnd - 1)
        }

        override fun getCount(): Int = rows.size

        override fun getItem(position: Int): EmojiListRow? = rows.getOrNull(position)

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewTypeCount(): Int = VIEW_TYPE_COUNT

        override fun getItemViewType(position: Int): Int {
            return when (rows[position]) {
                is EmojiListRow.Header -> VIEW_TYPE_HEADER
                is EmojiListRow.Emojis -> VIEW_TYPE_EMOJIS
                is EmojiListRow.Message -> VIEW_TYPE_MESSAGE
            }
        }

        override fun isEnabled(position: Int): Boolean = rows.getOrNull(position) is EmojiListRow.Emojis

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            return when (val row = rows[position]) {
                is EmojiListRow.Header -> headerView(row, convertView)
                is EmojiListRow.Emojis -> emojiRowView(row, convertView)
                is EmojiListRow.Message -> messageView(row, convertView)
            }
        }

        private fun headerView(row: EmojiListRow.Header, convertView: View?): View {
            val view = (convertView as? TextView) ?: TextView(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(PALETTE.sectionText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(10), dp(12), dp(10), dp(6))
            }
            view.text = row.title
            return view
        }

        private fun messageView(row: EmojiListRow.Message, convertView: View?): View {
            val view = (convertView as? TextView) ?: TextView(context).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(PALETTE.sectionText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(10), dp(28), dp(10), dp(28))
            }
            view.text = row.text
            return view
        }

        private fun emojiRowView(row: EmojiListRow.Emojis, convertView: View?): View {
            val rowView = (convertView as? LinearLayout) ?: createEmojiRowView()
            for (index in 0 until EmojiCatalog.RECENT_COLUMN_COUNT) {
                val cell = rowView.getChildAt(index) as TextView
                val entry = row.entries.getOrNull(index)
                if (entry == null) {
                    cell.text = ""
                    cell.visibility = View.INVISIBLE
                    cell.isClickable = false
                    cell.setOnClickListener(null)
                    cell.setOnLongClickListener(null)
                    cell.contentDescription = null
                } else {
                    val emoji = displayEmoji(entry)
                    cell.visibility = View.VISIBLE
                    cell.text = emoji
                    cell.contentDescription = EmojiCatalog.entryForEmoji(emoji)?.name ?: entry.name
                    cell.isClickable = true
                    cell.setOnClickListener { onEmojiClick(emoji) }
                    cell.setOnLongClickListener { onEmojiLongClick(entry, cell) }
                }
            }
            return rowView
        }

        private fun createEmojiRowView(): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(1), 0, dp(1))
                repeat(EmojiCatalog.RECENT_COLUMN_COUNT) {
                    addView(
                        TextView(context).apply {
                            gravity = Gravity.CENTER
                            includeFontPadding = false
                            typeface = Typeface.DEFAULT
                            setTextColor(PALETTE.text)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                            minHeight = dp(42)
                            background = emojiPressBackground(context)
                        },
                        LinearLayout.LayoutParams(0, dp(44), 1f).withMargins(horizontal = 1),
                    )
                }
            }
        }

        private fun LinearLayout.LayoutParams.withMargins(horizontal: Int = 0): LinearLayout.LayoutParams {
            setMargins(dp(horizontal), 0, dp(horizontal), 0)
            return this
        }

        private fun firstPositionForSection(section: EmojiSectionKey): Int? {
            return rows.indexOfFirst { it.section == section }
                .takeIf { it >= 0 }
        }

        private fun firstPositionAfterSection(section: EmojiSectionKey, sectionStart: Int): Int {
            val nextSectionStart = rows
                .subList(sectionStart + 1, rows.size)
                .indexOfFirst { it.section != section }
            return if (nextSectionStart >= 0) {
                sectionStart + 1 + nextSectionStart
            } else {
                rows.size
            }
        }

        private val EmojiListRow.section: EmojiSectionKey?
            get() = when (this) {
                is EmojiListRow.Header -> section
                is EmojiListRow.Emojis -> section
                is EmojiListRow.Message -> null
            }

        private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

        private companion object {
            const val VIEW_TYPE_HEADER = 0
            const val VIEW_TYPE_EMOJIS = 1
            const val VIEW_TYPE_MESSAGE = 2
            const val VIEW_TYPE_COUNT = 3
        }
    }

    private object PALETTE {
        const val background = ImeColors.BACKGROUND
        const val key = Color.TRANSPARENT
        val function = ImeColors.KEY
        val selected = ImeColors.SELECTED
        const val text = ImeColors.TEXT
        val mutedText = ImeColors.MUTED_TEXT
        val disabledText = ImeColors.DISABLED_TEXT
        val sectionText = ImeColors.SECTION_TEXT
    }

    private companion object {
        fun emojiPressBackground(context: Context) =
            ImePressFeedback.roundedTransientBackground(
                context = context,
                pressedColor = ImeColors.KEY,
            )
    }
}
