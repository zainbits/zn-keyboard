package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.zain.znkeyboard.KeyboardSettings
import dev.zain.znkeyboard.R
import kotlin.math.roundToInt

class SnippetPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    interface Callback {
        fun onSnippetSelected(snippet: String)
        fun onSnippetSearchRequested()
        fun onSnippetSaveCurrentInput()
        fun onSnippetPanelClosed()
    }

    var callback: Callback? = null

    private val masonryLayout = SnippetMasonryLayout(context).apply {
        setPadding(0, 0, 0, dp(2))
    }
    private val scrollView = ScrollView(context).apply {
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
    private val emptyText = TextView(context).apply {
        text = "No snippets"
        gravity = Gravity.CENTER
        setTextColor(PALETTE.mutedText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        includeFontPadding = false
        visibility = GONE
    }
    private val contentFrame = FrameLayout(context).apply {
        addView(
            scrollView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        addView(
            emptyText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }
    private val searchBar = iconTextKey("Search snippets", R.drawable.ic_search_24) {
        callback?.onSnippetSearchRequested()
    }.apply {
        gravity = Gravity.CENTER_VERTICAL
        contentDescription = "Search snippets"
        setPadding(dp(14), 0, dp(14), 0)
    }

    private var snippets = emptyList<KeyboardSettings.TextSnippet>()
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
        rebuildSnippetCards()
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = ((ImeLayout.BASE_HEIGHT_DP + ImeLayout.EXTENDED_PANEL_EXTRA_HEIGHT_DP) *
            heightScale *
            resources.displayMetrics.density +
            bottomSystemControlGapPx)
            .roundToInt()
        val exactHeightSpec = MeasureSpec.makeMeasureSpec(resolveSize(desiredHeight, heightMeasureSpec), MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, exactHeightSpec)
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
        emptyText.visibility = if (empty) VISIBLE else GONE
        scrollView.visibility = if (empty) GONE else VISIBLE
    }

    private fun buildHeaderRow(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                textKey("‹", KeyRole.Function) { callback?.onSnippetPanelClosed() }.apply {
                    contentDescription = "Back to keyboard"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                },
                LayoutParams(dp(58), LayoutParams.MATCH_PARENT).withMargins(end = 4),
            )
            addView(
                searchBar,
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).withMargins(horizontal = 2),
            )
            addView(
                iconKey(R.drawable.ic_save_24, "Save current input as snippet") {
                    callback?.onSnippetSaveCurrentInput()
                },
                LayoutParams(dp(58), LayoutParams.MATCH_PARENT).withMargins(start = 4),
            )
        }
    }

    private fun snippetCard(snippet: KeyboardSettings.TextSnippet): LinearLayout {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.START
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minimumHeight = dp(50)
            background = cardBackground()
            isClickable = true
            isFocusable = false
            contentDescription = snippet.text
            addView(
                TextView(context).apply {
                    text = snippet.text
                    includeFontPadding = true
                    setTextColor(PALETTE.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                    setLineSpacing(dp(2).toFloat(), 1f)
                    maxLines = SNIPPET_CARD_MAX_LINES
                    ellipsize = TextUtils.TruncateAt.END
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
            if (snippet.tags.isNotEmpty()) {
                addView(
                    TextView(context).apply {
                        text = snippet.tags.joinToString("  ") { "#$it" }
                        includeFontPadding = false
                        setTextColor(PALETTE.tagText)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                        setSingleLine(true)
                        ellipsize = TextUtils.TruncateAt.END
                    },
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).withMargins(top = 6),
                )
            }
            setOnClickListener {
                callback?.onSnippetSelected(snippet.text)
            }
        }
    }

    private fun textKey(label: String, role: KeyRole, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = false
            setTextColor(if (role == KeyRole.Character) PALETTE.text else PALETTE.mutedText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (role == KeyRole.Character) 18f else 13f)
            background = keyBackground(role)
            setOnClickListener { onClick() }
        }
    }

    private fun iconTextKey(label: String, iconResId: Int, onClick: () -> Unit): TextView {
        return textKey(label, KeyRole.Function, onClick).apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(tintedIcon(iconResId), null, null, null)
            compoundDrawablePadding = dp(8)
        }
    }

    private fun iconKey(iconResId: Int, description: String, onClick: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            contentDescription = description
            background = keyBackground(KeyRole.Function)
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

    private fun cardBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(7).toFloat()
            setColor(PALETTE.key)
            setStroke(dp(1), PALETTE.border)
        }
    }

    private fun keyBackground(role: KeyRole): GradientDrawable {
        val color = when (role) {
            KeyRole.Character -> PALETTE.key
            KeyRole.Function -> PALETTE.function
        }
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

    private enum class KeyRole {
        Character,
        Function,
    }

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
        val background = Color.BLACK
        val key = Color.rgb(42, 42, 42)
        val function = Color.rgb(50, 50, 50)
        val border = Color.rgb(62, 62, 62)
        const val text = Color.WHITE
        val tagText = Color.rgb(170, 210, 255)
        val mutedText = Color.rgb(230, 230, 230)
    }

    private companion object {
        const val SNIPPET_CARD_MAX_LINES = 6
    }
}
