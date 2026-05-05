package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

class AgentReviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    interface Callback {
        fun onAgentReviewApply()
        fun onAgentReviewCancel()
    }

    var callback: Callback? = null

    private val suggestionText = TextView(context).apply {
        setTextColor(PALETTE.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setLineSpacing(dp(2).toFloat(), 1f)
        gravity = Gravity.START
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = fieldBackground()
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
    }
    private val applyButton = Button(context).apply {
        text = "Apply"
        isAllCaps = false
        minHeight = 0
        minWidth = 0
        isFocusable = false
        setOnClickListener { callback?.onAgentReviewApply() }
    }
    private val cancelButton = Button(context).apply {
        text = "Cancel"
        isAllCaps = false
        minHeight = 0
        minWidth = 0
        isFocusable = false
        setOnClickListener { callback?.onAgentReviewCancel() }
    }
    private var heightScale = 1f

    private val bottomSystemControlGapPx by lazy(LazyThreadSafetyMode.NONE) {
        ImeLayout.bottomSystemControlGapPx(context)
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(PALETTE.background)
        setPadding(dp(8), dp(8), dp(8), dp(ImeLayout.BASE_BOTTOM_PADDING_DP) + bottomSystemControlGapPx.roundToInt())
        isClickable = true
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO

        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                suggestionText,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }
        addView(
            scrollView,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )

        val actions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                cancelButton,
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).withMargins(end = 4),
            )
            addView(
                applyButton,
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).withMargins(start = 4),
            )
        }
        addView(
            actions,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(46)).withMargins(top = 8),
        )
    }

    fun setHeightScale(scale: Float) {
        if (heightScale != scale) {
            heightScale = scale
            requestLayout()
        }
    }

    fun render(text: String) {
        suggestionText.text = text
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (ImeLayout.BASE_HEIGHT_DP * heightScale * resources.displayMetrics.density +
            ImeLayout.compactAgentRowHeightPx(context, heightScale) +
            bottomSystemControlGapPx).roundToInt()
        val exactHeightSpec = MeasureSpec.makeMeasureSpec(resolveSize(desiredHeight, heightMeasureSpec), MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, exactHeightSpec)
    }

    private fun fieldBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(PALETTE.field)
            setStroke(dp(1), PALETTE.border)
        }
    }

    private fun LayoutParams.withMargins(
        start: Int = 0,
        top: Int = 0,
        end: Int = 0,
        bottom: Int = 0,
    ): LayoutParams {
        setMargins(dp(start), dp(top), dp(end), dp(bottom))
        return this
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private object PALETTE {
        val background = Color.rgb(34, 34, 34)
        val field = Color.rgb(42, 42, 42)
        val border = Color.rgb(62, 62, 62)
        const val text = Color.WHITE
    }

}
