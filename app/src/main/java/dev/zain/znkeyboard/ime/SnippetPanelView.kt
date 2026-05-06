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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.zain.znkeyboard.KeyboardSettings
import kotlin.math.roundToInt

class SnippetPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    interface Callback {
        fun onSnippetSelected(snippet: String)
        fun onSnippetPanelClosed()
        fun onSnippetBackspace()
        fun onSnippetSpace()
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

    private var snippets = emptyList<String>()
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
            contentFrame,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )
        addView(
            buildActionRow(),
            LayoutParams(LayoutParams.MATCH_PARENT, dp(42)).withMargins(top = 6),
        )
        rebuildSnippetCards()
    }

    fun setHeightScale(scale: Float) {
        if (heightScale != scale) {
            heightScale = scale
            requestLayout()
        }
    }

    fun setSnippets(nextSnippets: List<String>) {
        val normalized = KeyboardSettings.normalizeTextSnippets(nextSnippets)
        if (snippets == normalized) return

        snippets = normalized
        rebuildSnippetCards()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (ImeLayout.BASE_HEIGHT_DP * heightScale * resources.displayMetrics.density + bottomSystemControlGapPx)
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

    private fun buildActionRow(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                textKey("ABC", KeyRole.Function) { callback?.onSnippetPanelClosed() },
                LayoutParams(dp(58), LayoutParams.MATCH_PARENT).withMargins(end = 4),
            )
            addView(
                textKey("space", KeyRole.Function) { callback?.onSnippetSpace() },
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).withMargins(horizontal = 2),
            )
            addView(
                textKey("Del", KeyRole.Function) { callback?.onSnippetBackspace() },
                LayoutParams(dp(58), LayoutParams.MATCH_PARENT).withMargins(start = 4),
            )
        }
    }

    private fun snippetCard(snippet: String): TextView {
        return TextView(context).apply {
            text = snippet
            gravity = Gravity.START
            includeFontPadding = true
            setTextColor(PALETTE.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            maxLines = SNIPPET_CARD_MAX_LINES
            ellipsize = TextUtils.TruncateAt.END
            minHeight = dp(50)
            background = cardBackground()
            isClickable = true
            isFocusable = false
            contentDescription = snippet
            setOnClickListener {
                callback?.onSnippetSelected(snippet)
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
        val mutedText = Color.rgb(230, 230, 230)
    }

    private companion object {
        const val SNIPPET_CARD_MAX_LINES = 6
    }
}
