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
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
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
        fun onEmojiPanelClosed()
        fun onEmojiBackspace()
        fun onEmojiSpace()
    }

    var callback: Callback? = null

    private lateinit var recentTabButton: TextView
    private val categoryButtons = mutableMapOf<EmojiCategory, TextView>()
    private val adapter = EmojiGridAdapter(context)
    private val searchHeader = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    private val searchText = TextView(context)
    private val categoryStrip = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }
    private val categoryRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val emojiGrid = GridView(context).apply {
        adapter = this@EmojiPanelView.adapter
        numColumns = EmojiCatalog.RECENT_COLUMN_COUNT
        stretchMode = GridView.STRETCH_COLUMN_WIDTH
        gravity = Gravity.CENTER
        horizontalSpacing = dp(4)
        verticalSpacing = dp(4)
        clipToPadding = false
        cacheColorHint = Color.TRANSPARENT
        selector = keyBackground(active = true, role = KeyRole.Character)
    }
    private val browseActionRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val searchKeyboard = LinearLayout(context).apply {
        orientation = VERTICAL
        visibility = GONE
    }

    private var selectedTab = EmojiTab.Recent
    private var selectedCategory = EmojiCategory.SmileysEmotion
    private var recentEmojis = emptyList<String>()
    private var recentRowCount = EmojiCatalog.DEFAULT_RECENT_ROW_COUNT
    private var defaultSkinTone = EmojiSkinTone.Default
    private var searchActive = false
    private var searchQuery = ""
    private var heightScale = 1f
    private var variantPopup: PopupWindow? = null

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

        buildSearchHeader()
        buildCategoryStrip()
        buildBrowseActions()
        buildSearchKeyboard()

        addView(searchHeader, LayoutParams(LayoutParams.MATCH_PARENT, dp(42)))
        addView(categoryStrip, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))
        addView(
            emojiGrid,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )
        addView(browseActionRow, LayoutParams(LayoutParams.MATCH_PARENT, dp(42)))
        addView(searchKeyboard)

        emojiGrid.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            adapter.displayEmojiAt(position)?.let(::selectEmoji)
        }
        emojiGrid.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, view, position, _ ->
            adapter.getItem(position)?.let { entry ->
                showVariantPopup(entry, view)
            } ?: false
        }

        updateMode()
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
            if ((!searchActive && selectedTab == EmojiTab.Recent) || (searchActive && searchQuery.isBlank())) {
                updateGridEntries()
            }
        }
    }

    fun setRecentRowCount(rowCount: Int) {
        val normalizedRowCount = EmojiCatalog.normalizeRecentRowCount(rowCount)
        if (recentRowCount != normalizedRowCount) {
            recentRowCount = normalizedRowCount
            if (!searchActive && selectedTab == EmojiTab.Recent) {
                updateGridEntries()
            }
        }
    }

    fun setDefaultSkinTone(skinTone: EmojiSkinTone) {
        if (defaultSkinTone != skinTone) {
            defaultSkinTone = skinTone
            adapter.defaultSkinTone = skinTone
            updateGridEntries()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (ImeLayout.BASE_HEIGHT_DP * heightScale * resources.displayMetrics.density + bottomSystemControlGapPx)
            .roundToInt()
        val exactHeightSpec = MeasureSpec.makeMeasureSpec(resolveSize(desiredHeight, heightMeasureSpec), MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, exactHeightSpec)
    }

    private fun buildSearchHeader() {
        searchHeader.addView(
            textKey("ABC", KeyRole.Function) { closePanel() },
            LayoutParams(dp(58), LayoutParams.MATCH_PARENT).withMargins(end = 4),
        )
        searchText.apply {
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(PALETTE.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            includeFontPadding = false
            setPadding(dp(12), 0, dp(12), 0)
            background = keyBackground(active = false, role = KeyRole.Function)
        }
        searchHeader.addView(
            searchText,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).withMargins(horizontal = 2),
        )
        searchHeader.addView(
            textKey("Del", KeyRole.Function) { deleteSearchCharacter() },
            LayoutParams(dp(58), LayoutParams.MATCH_PARENT).withMargins(start = 4),
        )
    }

    private fun buildCategoryStrip() {
        recentTabButton = textKey(RECENT_TAB_ICON, KeyRole.Character) {
            selectedTab = EmojiTab.Recent
            updateMode()
        }.apply {
            contentDescription = "Recently used"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        }
        categoryRow.addView(
            recentTabButton,
            LayoutParams(dp(46), LayoutParams.MATCH_PARENT).withMargins(horizontal = 2),
        )

        EmojiCatalog.categories.forEach { category ->
            val button = textKey(category.icon, KeyRole.Character) {
                selectedTab = EmojiTab.Category
                selectedCategory = category
                updateMode()
            }.apply {
                contentDescription = category.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            }
            categoryButtons[category] = button
            categoryRow.addView(
                button,
                LayoutParams(dp(46), LayoutParams.MATCH_PARENT).withMargins(horizontal = 2),
            )
        }
        categoryStrip.addView(categoryRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    private fun buildBrowseActions() {
        browseActionRow.addView(
            textKey("ABC", KeyRole.Function) { closePanel() },
            LayoutParams(dp(58), LayoutParams.MATCH_PARENT).withMargins(end = 4),
        )
        browseActionRow.addView(
            textKey("Search", KeyRole.Function) {
                searchActive = true
                searchQuery = ""
                updateMode()
            },
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).withMargins(horizontal = 2),
        )
        browseActionRow.addView(
            textKey("space", KeyRole.Function) { callback?.onEmojiSpace() },
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).withMargins(horizontal = 2),
        )
        browseActionRow.addView(
            textKey("Del", KeyRole.Function) { callback?.onEmojiBackspace() },
            LayoutParams(dp(58), LayoutParams.MATCH_PARENT).withMargins(start = 4),
        )
    }

    private fun buildSearchKeyboard() {
        addSearchKeyboardRow("qwertyuiop")
        addSearchKeyboardRow("asdfghjkl", sidePaddingWeight = 0.5f)
        addSearchKeyboardRow("zxcvbnm", sidePaddingWeight = 1.3f)

        val bottomRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bottomRow.addView(
            textKey("Browse", KeyRole.Function) {
                searchActive = false
                searchQuery = ""
                updateMode()
            },
            LayoutParams(dp(76), LayoutParams.MATCH_PARENT).withMargins(end = 4),
        )
        bottomRow.addView(
            textKey("space", KeyRole.Function) { appendSearchText(" ") },
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).withMargins(horizontal = 2),
        )
        bottomRow.addView(
            textKey("Clear", KeyRole.Function) {
                searchQuery = ""
                updateSearchText()
                updateGridEntries()
            },
            LayoutParams(dp(68), LayoutParams.MATCH_PARENT).withMargins(start = 4),
        )
        searchKeyboard.addView(
            bottomRow,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(36)).withMargins(top = 4),
        )
    }

    private fun addSearchKeyboardRow(chars: String, sidePaddingWeight: Float = 0f) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (sidePaddingWeight > 0f) {
            row.addView(View(context), LayoutParams(0, 1, sidePaddingWeight))
        }
        chars.forEach { char ->
            row.addView(
                textKey(char.toString(), KeyRole.Character) { appendSearchText(char.toString()) },
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).withMargins(horizontal = 2),
            )
        }
        if (sidePaddingWeight > 0f) {
            row.addView(View(context), LayoutParams(0, 1, sidePaddingWeight))
        }
        searchKeyboard.addView(
            row,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(36)).withMargins(top = 4),
        )
    }

    private fun updateMode() {
        searchHeader.visibility = if (searchActive) VISIBLE else GONE
        categoryStrip.visibility = if (searchActive) GONE else VISIBLE
        browseActionRow.visibility = if (searchActive) GONE else VISIBLE
        searchKeyboard.visibility = if (searchActive) VISIBLE else GONE

        updateSearchText()
        updateCategoryButtons()
        updateGridEntries()
    }

    private fun updateSearchText() {
        searchText.text = searchQuery.ifBlank { "Search emoji" }
    }

    private fun updateGridEntries() {
        val entries = if (searchActive) {
            if (searchQuery.isBlank()) {
                recentEntries()
            } else {
                EmojiCatalog.search(searchQuery)
            }
        } else {
            when (selectedTab) {
                EmojiTab.Recent -> recentEntries()
                EmojiTab.Category -> categoryEntries()
            }
        }
        adapter.submitList(entries)
        emojiGrid.setSelection(0)
    }

    private fun recentEntries(): List<EmojiEntry> {
        return recentEmojis
            .take(recentRowCount * EmojiCatalog.RECENT_COLUMN_COUNT)
            .mapNotNull(EmojiCatalog::entryForEmoji)
    }

    private fun categoryEntries(): List<EmojiEntry> {
        return EmojiCatalog.entriesByCategory[selectedCategory].orEmpty()
    }

    private fun updateCategoryButtons() {
        recentTabButton.background = keyBackground(active = selectedTab == EmojiTab.Recent, role = KeyRole.Character)
        categoryButtons.forEach { (category, button) ->
            button.background = keyBackground(
                active = selectedTab == EmojiTab.Category && category == selectedCategory,
                role = KeyRole.Character,
            )
        }
    }

    private fun appendSearchText(value: String) {
        searchQuery += value
        updateSearchText()
        updateGridEntries()
    }

    private fun deleteSearchCharacter() {
        if (searchQuery.isNotEmpty()) {
            searchQuery = searchQuery.dropLast(1)
            updateSearchText()
            updateGridEntries()
        }
    }

    private fun selectEmoji(emoji: String) {
        dismissVariantPopup()
        val updatedRecentEmojis = EmojiCatalog.promoteRecentEmoji(emoji, recentEmojis)
        recentEmojis = updatedRecentEmojis
        if ((!searchActive && selectedTab == EmojiTab.Recent) || (searchActive && searchQuery.isBlank())) {
            updateGridEntries()
        }
        callback?.onEmojiSelected(emoji, updatedRecentEmojis)
    }

    private fun closePanel() {
        dismissVariantPopup()
        searchActive = false
        searchQuery = ""
        updateMode()
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
            active -> PALETTE.accent
            role == KeyRole.Function -> PALETTE.function
            else -> PALETTE.key
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(5).toFloat()
            setColor(color)
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

    private enum class KeyRole {
        Character,
        Function,
    }

    private enum class EmojiTab {
        Recent,
        Category,
    }

    private class EmojiGridAdapter(
        private val context: Context,
    ) : BaseAdapter() {
        private var entries: List<EmojiEntry> = emptyList()
        var defaultSkinTone: EmojiSkinTone = EmojiSkinTone.Default
            set(value) {
                if (field != value) {
                    field = value
                    notifyDataSetChanged()
                }
            }

        fun submitList(newEntries: List<EmojiEntry>) {
            entries = newEntries
            notifyDataSetChanged()
        }

        override fun getCount(): Int = entries.size

        override fun getItem(position: Int): EmojiEntry? = entries.getOrNull(position)

        fun displayEmojiAt(position: Int): String? {
            val entry = entries.getOrNull(position) ?: return null
            return EmojiCatalog.displayEmoji(entry, defaultSkinTone)
        }

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
            val view = (convertView as? TextView) ?: TextView(context).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                typeface = Typeface.DEFAULT
                setTextColor(PALETTE.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f)
                minHeight = dp(42)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(5).toFloat()
                    setColor(PALETTE.key)
                }
            }
            val entry = entries[position]
            val emoji = EmojiCatalog.displayEmoji(entry, defaultSkinTone)
            view.text = emoji
            view.contentDescription = EmojiCatalog.entryForEmoji(emoji)?.name ?: entry.name
            return view
        }

        private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
    }

    private object PALETTE {
        val background = Color.BLACK
        val key = Color.rgb(42, 42, 42)
        val function = Color.rgb(50, 50, 50)
        val accent = Color.rgb(48, 172, 226)
        const val text = Color.WHITE
        val mutedText = Color.rgb(230, 230, 230)
    }

    private companion object {
        const val RECENT_TAB_ICON = "◷"
    }
}
