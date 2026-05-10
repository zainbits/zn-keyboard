package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import dev.zain.znkeyboard.EmojiCatalog
import dev.zain.znkeyboard.EmojiCategory
import dev.zain.znkeyboard.EmojiEntry
import dev.zain.znkeyboard.EmojiSkinTone
import dev.zain.znkeyboard.constants.ImeColors
import kotlin.math.roundToInt

class EmojiSearchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    interface Callback {
        fun onEmojiSearchClosed()
        fun onEmojiSearchEmojiSelected(emoji: String, updatedRecentEmojis: List<String>)
    }

    var callback: Callback? = null

    private var recentEmojis = emptyList<String>()
    private var defaultSkinTone = EmojiSkinTone.Default
    private var customEmojiTags = emptyMap<String, List<String>>()
    private var query = ""
    private var variantPopup: PopupWindow? = null

    private val resultRows = LinearLayout(context).apply {
        orientation = VERTICAL
    }
    private val queryText = TextView(context)
    private val clearButton = textButton("x") {
        setQuery("")
    }.apply {
        contentDescription = "Clear emoji search"
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

        repeat(MAX_RESULT_ROWS) {
            resultRows.addView(
                emojiResultRow(),
                LayoutParams(LayoutParams.MATCH_PARENT, dp(42)).withMargins(top = 2),
            )
        }
        addView(resultRows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(searchRow(), LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).withMargins(top = 6))
        updateResults()
    }

    fun setRecentEmojis(emojis: List<String>) {
        val normalized = EmojiCatalog.normalizeRecentEmojis(emojis)
        if (recentEmojis != normalized) {
            recentEmojis = normalized
            updateResults()
        }
    }

    fun setDefaultSkinTone(skinTone: EmojiSkinTone) {
        if (defaultSkinTone != skinTone) {
            defaultSkinTone = skinTone
            updateResults()
        }
    }

    fun setCustomEmojiTags(tagsByEmoji: Map<String, List<String>>) {
        val normalized = tagsByEmoji
            .filterValues { it.isNotEmpty() }
        if (customEmojiTags != normalized) {
            customEmojiTags = normalized
            updateResults()
        }
    }

    fun setQuery(value: String) {
        val normalized = value.take(MAX_QUERY_LENGTH)
        if (query != normalized) {
            query = normalized
            updateResults()
        } else {
            updateQueryText()
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

    fun clearSearch() {
        dismissVariantPopup()
        setQuery("")
    }

    private fun searchRow(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ImePanelChrome.backButton(
                    context = context,
                    contentDescription = "Back to emoji",
                    backgroundColor = PALETTE.function,
                    textColor = PALETTE.mutedText,
                    onClick = { callback?.onEmojiSearchClosed() },
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
            }
            addView(
                queryText,
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).withMargins(horizontal = 2),
            )

            addView(
                clearButton,
                LayoutParams(dp(44), LayoutParams.MATCH_PARENT).withMargins(start = 4),
            )
        }
    }

    private fun emojiResultRow(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            repeat(EmojiCatalog.RECENT_COLUMN_COUNT) {
                addView(
                    TextView(context).apply {
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        typeface = Typeface.DEFAULT
                        setTextColor(PALETTE.text)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f)
                        background = ColorDrawable(Color.TRANSPARENT)
                    },
                    LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).withMargins(horizontal = 1),
                )
            }
        }
    }

    private fun updateResults() {
        updateQueryText()
        val entries = visibleEntries()
        for (rowIndex in 0 until MAX_RESULT_ROWS) {
            val row = resultRows.getChildAt(rowIndex) as LinearLayout
            for (columnIndex in 0 until EmojiCatalog.RECENT_COLUMN_COUNT) {
                val cell = row.getChildAt(columnIndex) as TextView
                val entry = entries.getOrNull(rowIndex * EmojiCatalog.RECENT_COLUMN_COUNT + columnIndex)
                if (entry == null) {
                    cell.text = ""
                    cell.visibility = View.INVISIBLE
                    cell.isClickable = false
                    cell.setOnClickListener(null)
                    cell.setOnLongClickListener(null)
                    cell.contentDescription = null
                } else {
                    val emoji = EmojiCatalog.displayEmoji(entry, defaultSkinTone)
                    cell.text = emoji
                    cell.visibility = View.VISIBLE
                    cell.isClickable = true
                    cell.contentDescription = EmojiCatalog.entryForEmoji(emoji)?.name ?: entry.name
                    cell.setOnClickListener { selectEmoji(emoji) }
                    cell.setOnLongClickListener { showVariantPopup(entry, cell) }
                }
            }
        }
    }

    private fun updateQueryText() {
        queryText.text = query.ifBlank { "Search emoji" }
        queryText.setTextColor(if (query.isBlank()) PALETTE.placeholderText else PALETTE.text)
        clearButton.alpha = if (query.isBlank()) 0.36f else 1f
        clearButton.isEnabled = query.isNotBlank()
        clearButton.isClickable = query.isNotBlank()
    }

    private fun visibleEntries(): List<EmojiEntry> {
        val source = if (query.isBlank()) {
            recentEntries().ifEmpty {
                EmojiCatalog.entriesByCategory[EmojiCategory.SmileysEmotion].orEmpty()
            }
        } else {
            EmojiCatalog.search(query, customEmojiTags)
        }
        return source.take(MAX_RESULT_ROWS * EmojiCatalog.RECENT_COLUMN_COUNT)
    }

    private fun recentEntries(): List<EmojiEntry> {
        return recentEmojis
            .take(MAX_RESULT_ROWS * EmojiCatalog.RECENT_COLUMN_COUNT)
            .mapNotNull(EmojiCatalog::entryForEmoji)
    }

    private fun selectEmoji(emoji: String) {
        dismissVariantPopup()
        val updatedRecentEmojis = EmojiCatalog.promoteRecentEmoji(emoji, recentEmojis)
        recentEmojis = updatedRecentEmojis
        updateResults()
        callback?.onEmojiSearchEmojiSelected(emoji, updatedRecentEmojis)
    }

    private fun showVariantPopup(entry: EmojiEntry, anchor: View): Boolean {
        val variants = EmojiCatalog.variantsFor(entry)
        if (variants.isEmpty()) return false

        dismissVariantPopup()
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = roundedBackground(PALETTE.function)
        }

        variants.forEach { variant ->
            row.addView(
                TextView(context).apply {
                    text = variant.emoji
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    typeface = Typeface.DEFAULT
                    setTextColor(PALETTE.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                    contentDescription = variant.name
                    setOnClickListener { selectEmoji(variant.emoji) }
                },
                LayoutParams(dp(42), dp(42)).withMargins(horizontal = 2),
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

    private fun roundedBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ImeLayout.KEY_RADIUS_DP).toFloat()
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

    private object PALETTE {
        const val background = ImeColors.BACKGROUND
        val function = ImeColors.KEY
        const val text = ImeColors.TEXT
        val mutedText = ImeColors.MUTED_TEXT
        val placeholderText = ImeColors.SECONDARY_TEXT
    }

    private companion object {
        const val MAX_RESULT_ROWS = 2
        const val MAX_QUERY_LENGTH = 64
    }
}
