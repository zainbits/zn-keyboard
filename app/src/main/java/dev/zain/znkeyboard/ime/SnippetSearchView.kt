package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.zain.znkeyboard.KeyboardSettings
import dev.zain.znkeyboard.R
import java.util.Locale
import kotlin.math.roundToInt

class SnippetSearchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    interface Callback {
        fun onSnippetSearchClosed()
        fun onSnippetSearchSnippetSelected(snippet: String)
    }

    var callback: Callback? = null

    private var snippets = emptyList<KeyboardSettings.TextSnippet>()
    private var query = ""

    private val queryText = TextView(context)
    private val clearButton = iconButton(R.drawable.ic_close_24, "Clear snippet search") {
        setQuery("")
    }
    private val resultContent = LinearLayout(context).apply {
        orientation = VERTICAL
    }
    private val resultScroll = ScrollView(context).apply {
        clipToPadding = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        setPadding(0, dp(4), 0, dp(6))
        addView(
            resultContent,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
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

        addView(resultScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(156)))
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
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                textButton("‹") {
                    callback?.onSnippetSearchClosed()
                }.apply {
                    contentDescription = "Back to snippets"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                },
                LayoutParams(dp(44), LayoutParams.MATCH_PARENT).withMargins(end = 4),
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

    private fun updateResults() {
        updateQueryText()
        resultContent.removeAllViews()
        val visibleSnippets = visibleSnippets()
        if (visibleSnippets.isEmpty()) {
            resultContent.addView(
                TextView(context).apply {
                    text = if (query.isBlank()) "No snippets" else "No matching snippets"
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(PALETTE.mutedText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                },
                LayoutParams(LayoutParams.MATCH_PARENT, dp(64)),
            )
            return
        }

        visibleSnippets.forEach { snippet ->
            resultContent.addView(
                resultCard(snippet),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).withMargins(bottom = 6),
            )
        }
    }

    private fun updateQueryText() {
        queryText.text = query.ifBlank { "Search snippets" }
        queryText.setTextColor(if (query.isBlank()) PALETTE.placeholderText else PALETTE.text)
        clearButton.alpha = if (query.isBlank()) 0.36f else 1f
        clearButton.isEnabled = query.isNotBlank()
        clearButton.isClickable = query.isNotBlank()
    }

    private fun visibleSnippets(): List<KeyboardSettings.TextSnippet> {
        val needle = query.trim().lowercase(Locale.US)
        val source = snippets
        if (needle.isBlank()) return source.take(MAX_RESULTS)
        return source
            .filter { snippet ->
                snippet.text.lowercase(Locale.US).contains(needle) ||
                    snippet.tags.any { tag -> tag.lowercase(Locale.US).contains(needle) }
            }
            .take(MAX_RESULTS)
    }

    private fun resultCard(snippet: KeyboardSettings.TextSnippet): LinearLayout {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = roundedBackground(PALETTE.key)
            isClickable = true
            isFocusable = false
            contentDescription = snippet.text
            addView(
                TextView(context).apply {
                    text = snippet.text
                    setTextColor(PALETTE.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
            if (snippet.tags.isNotEmpty()) {
                addView(
                    TextView(context).apply {
                        text = snippet.tags.joinToString("  ") { "#$it" }
                        includeFontPadding = false
                        setSingleLine(true)
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(PALETTE.tagText)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    },
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).withMargins(top = 5),
                )
            }
            setOnClickListener {
                callback?.onSnippetSearchSnippetSelected(snippet.text)
            }
        }
    }

    private fun textButton(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = false
            setTextColor(PALETTE.mutedText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = roundedBackground(PALETTE.function)
            setOnClickListener { onClick() }
        }
    }

    private fun iconButton(iconResId: Int, description: String, onClick: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            contentDescription = description
            background = roundedBackground(PALETTE.function)
            isClickable = true
            isFocusable = false
            scaleType = ImageView.ScaleType.CENTER
            setPadding(0, 0, 0, 0)
            setImageDrawable(tintedIcon(iconResId))
            setOnClickListener { onClick() }
        }
    }

    private fun tintedIcon(iconResId: Int) = ContextCompat.getDrawable(context, iconResId)?.mutate()?.apply {
        setTint(PALETTE.mutedText)
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
        val background = Color.BLACK
        val key = Color.rgb(42, 42, 42)
        val function = Color.rgb(50, 50, 50)
        const val text = Color.WHITE
        val tagText = Color.rgb(170, 210, 255)
        val mutedText = Color.rgb(230, 230, 230)
        val placeholderText = Color.rgb(150, 150, 150)
    }

    private companion object {
        const val MAX_QUERY_LENGTH = 80
        const val MAX_RESULTS = 8
    }
}
