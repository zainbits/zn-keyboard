package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import dev.zain.znkeyboard.EmojiCatalog
import dev.zain.znkeyboard.EmojiCategory
import dev.zain.znkeyboard.EmojiEntry
import dev.zain.znkeyboard.EmojiSkinTone
import kotlin.math.roundToInt

class EmojiPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    interface Callback {
        fun onEmojiSelected(emoji: String, updatedRecentEmojis: List<String>)
        fun onEmojiSearchRequested()
        fun onEmojiPanelClosed()
        fun onEmojiBackspace()
        fun onEmojiSpace()
    }

    var callback: Callback? = null

    private var recentEmojis = emptyList<String>()
    private var recentRowCount = EmojiCatalog.DEFAULT_RECENT_ROW_COUNT
    private var defaultSkinTone = EmojiSkinTone.Default
    private var heightScale = 1f
    private var variantPopup: PopupWindow? = null
    private var selectedSection: EmojiSectionKey = EmojiSectionKey.Recent

    private lateinit var recentShortcutButton: TextView
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

        addView(browseHeaderStrip, LayoutParams(LayoutParams.MATCH_PARENT, dp(42)))
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
                adapter.sectionForPosition(firstVisibleItem)?.let { section ->
                    if (selectedSection != section) {
                        selectedSection = section
                        updateCategoryButtons()
                    }
                }
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
        val desiredHeight = ((ImeLayout.BASE_HEIGHT_DP + ImeLayout.EMOJI_PANEL_EXTRA_HEIGHT_DP) *
            heightScale *
            resources.displayMetrics.density +
            bottomSystemControlGapPx)
            .roundToInt()
        val exactHeightSpec = MeasureSpec.makeMeasureSpec(resolveSize(desiredHeight, heightMeasureSpec), MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, exactHeightSpec)
    }

    private fun buildBrowseHeader() {
        browseHeaderRow.addView(
            textKey("‹", KeyRole.Function) { closePanel() }.apply {
                contentDescription = "Back to keyboard"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            },
            LayoutParams(dp(44), LayoutParams.MATCH_PARENT).withMargins(end = 4),
        )

        browseHeaderRow.addView(
            textKey("Search", KeyRole.Function) {
                callback?.onEmojiSearchRequested()
            }.apply {
                gravity = Gravity.CENTER_VERTICAL
                contentDescription = "Search emoji"
                setPadding(dp(14), 0, dp(16), 0)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                background = pillBackground(active = false)
            },
            LayoutParams(dp(124), LayoutParams.MATCH_PARENT).withMargins(end = 6),
        )

        recentShortcutButton = categoryShortcut(RECENT_TAB_ICON, "Recently used") {
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
        bottomToolbar.addView(
            flatToolbarKey("ABC", enabled = true) { closePanel() },
            LayoutParams(dp(62), LayoutParams.MATCH_PARENT).withMargins(end = 4),
        )
        bottomToolbar.addView(
            toolbarTab("☺", active = true, enabled = true, onClick = null),
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).withMargins(horizontal = 3),
        )
        bottomToolbar.addView(
            toolbarTab("GIF", active = false, enabled = false, onClick = null),
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
            flatToolbarKey("⌫", enabled = true) { callback?.onEmojiBackspace() }.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                contentDescription = "Delete"
            },
            LayoutParams(dp(58), LayoutParams.MATCH_PARENT).withMargins(start = 4),
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
        val firstPosition = emojiList.firstVisiblePosition
        val firstTop = emojiList.getChildAt(0)?.top ?: emojiList.paddingTop
        val rows = buildListRows()

        adapter.submitRows(rows)

        if (keepScroll && rows.isNotEmpty()) {
            emojiList.setSelectionFromTop(firstPosition.coerceAtMost(rows.lastIndex), firstTop)
        }

        val nextSection = adapter.sectionForPosition(emojiList.firstVisiblePosition)
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
        selectedSection = section
        updateCategoryButtons()
        val position = sectionPositions[section] ?: 0
        emojiList.setSelectionFromTop(position, emojiList.paddingTop)
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
        val updatedRecentEmojis = EmojiCatalog.promoteRecentEmoji(emoji, recentEmojis)
        recentEmojis = updatedRecentEmojis
        updateListRows(keepScroll = true)
        callback?.onEmojiSelected(emoji, updatedRecentEmojis)
    }

    private fun closePanel() {
        dismissVariantPopup()
        updateMode(resetScroll = true)
        callback?.onEmojiPanelClosed()
    }

    private fun showVariantPopup(entry: EmojiEntry, anchor: View): Boolean {
        val variants = EmojiCatalog.variantsFor(entry)
        if (variants.isEmpty()) return false

        dismissVariantPopup()
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = keyBackground(active = false, role = KeyRole.Function)
        }

        variants.forEach { variant ->
            row.addView(
                emojiButton(variant.emoji).apply {
                    isEnabled = true
                    alpha = 1f
                    contentDescription = variant.name
                    setOnClickListener { selectEmoji(variant.emoji) }
                },
                LinearLayout.LayoutParams(dp(42), dp(42)).withMargins(horizontal = 2),
            )
        }

        variantPopup = PopupWindow(
            row,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false,
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
            showAsDropDown(anchor, 0, -anchor.height - dp(54))
        }
        return true
    }

    private fun dismissVariantPopup() {
        variantPopup?.dismiss()
        variantPopup = null
    }

    private fun emojiButton(label: String): TextView {
        return textKey(label, KeyRole.Character, onClick = null).apply {
            typeface = Typeface.DEFAULT
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        }
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
            background = if (active) pillBackground(active = true) else pillBackground(active = false)
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

    private fun textKey(label: String, role: KeyRole, onClick: (() -> Unit)?): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = onClick != null
            isFocusable = false
            setTextColor(if (role == KeyRole.Character) PALETTE.text else PALETTE.mutedText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (role == KeyRole.Character) 18f else 13f)
            background = keyBackground(active = false, role = role)
            onClick?.let { click -> setOnClickListener { click() } }
        }
    }

    private fun keyBackground(active: Boolean, role: KeyRole): GradientDrawable {
        val color = when {
            active -> PALETTE.selected
            role == KeyRole.Function -> PALETTE.function
            else -> PALETTE.key
        }
        return roundedBackground(color)
    }

    private fun pillBackground(active: Boolean): GradientDrawable {
        return roundedBackground(if (active) PALETTE.selected else PALETTE.function)
    }

    private fun categoryShortcutBackground(active: Boolean): GradientDrawable {
        return roundedBackground(if (active) PALETTE.selected else Color.TRANSPARENT)
    }

    private fun roundedBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ImeLayout.KEY_RADIUS_DP).toFloat()
            setColor(color)
        }
    }

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

    private enum class KeyRole {
        Character,
        Function,
    }

    private sealed class EmojiSectionKey {
        data object Recent : EmojiSectionKey()
        data class Category(val category: EmojiCategory) : EmojiSectionKey()
    }

    private sealed class EmojiListRow {
        data class Header(val section: EmojiSectionKey, val title: String) : EmojiListRow()
        data class Emojis(val section: EmojiSectionKey, val entries: List<EmojiEntry>) : EmojiListRow()
        data class Message(val text: String) : EmojiListRow()
    }

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
            return when (val row = rows.getOrNull(position)) {
                is EmojiListRow.Header -> row.section
                is EmojiListRow.Emojis -> row.section
                is EmojiListRow.Message,
                null,
                -> null
            }
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
                            background = ColorDrawable(Color.TRANSPARENT)
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

        private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

        private companion object {
            const val VIEW_TYPE_HEADER = 0
            const val VIEW_TYPE_EMOJIS = 1
            const val VIEW_TYPE_MESSAGE = 2
            const val VIEW_TYPE_COUNT = 3
        }
    }

    private object PALETTE {
        const val background = Color.BLACK
        const val key = Color.TRANSPARENT
        val function = Color.rgb(48, 48, 48)
        val selected = Color.rgb(78, 78, 78)
        const val text = Color.WHITE
        val mutedText = Color.rgb(230, 230, 230)
        val disabledText = Color.rgb(130, 130, 130)
        val sectionText = Color.rgb(145, 145, 145)
    }

    private companion object {
        const val RECENT_TAB_ICON = "◷"
    }
}
