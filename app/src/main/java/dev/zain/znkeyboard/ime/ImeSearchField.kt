package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.zain.znkeyboard.R
import kotlin.math.roundToInt

internal class ImeSearchField @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private var query = ""
    private var placeholder = ""
    private var textColor = Color.WHITE
    private var placeholderColor = Color.LTGRAY
    private var clearIconColor = Color.LTGRAY
    private var cursorVisible = true
    private var blinkRunning = false
    private var onClear: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val blinkRunnable = object : Runnable {
        override fun run() {
            cursorVisible = !cursorVisible
            renderText()
            mainHandler.postDelayed(this, CURSOR_BLINK_MS)
        }
    }

    private val queryText = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    }
    private val clearButton = ImageButton(context).apply {
        contentDescription = "Clear search"
        background = ColorDrawable(Color.TRANSPARENT)
        isFocusable = false
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setImageDrawable(tintedIcon(clearIconColor))
        setOnClickListener { onClear?.invoke() }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        setPadding(dp(14), 0, dp(4), 0)
        addView(
            queryText,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
        )
        addView(
            clearButton,
            LayoutParams(dp(34), LayoutParams.MATCH_PARENT),
        )
        renderText()
    }

    fun configure(
        backgroundColor: Int,
        textColor: Int,
        placeholderColor: Int,
        clearIconColor: Int,
        clearContentDescription: String,
        onClear: () -> Unit,
    ): ImeSearchField {
        this.textColor = textColor
        this.placeholderColor = placeholderColor
        this.clearIconColor = clearIconColor
        this.onClear = onClear
        clearButton.contentDescription = clearContentDescription
        clearButton.setImageDrawable(tintedIcon(clearIconColor))
        background = ImePressFeedback.roundedBackground(context, backgroundColor, textColor)
        renderText()
        return this
    }

    fun setSearchText(query: String, placeholder: String) {
        this.query = query
        this.placeholder = placeholder
        renderText()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isShown) {
            startBlinking()
        }
    }

    override fun onDetachedFromWindow() {
        stopBlinking()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            startBlinking()
        } else {
            stopBlinking()
        }
    }

    private fun startBlinking() {
        if (blinkRunning) return
        blinkRunning = true
        cursorVisible = true
        renderText()
        mainHandler.postDelayed(blinkRunnable, CURSOR_BLINK_MS)
    }

    private fun stopBlinking() {
        if (!blinkRunning) return
        blinkRunning = false
        mainHandler.removeCallbacks(blinkRunnable)
        cursorVisible = true
        renderText()
    }

    private fun renderText() {
        val cursorColor = if (cursorVisible) textColor else Color.TRANSPARENT
        val displayText = if (query.isBlank()) {
            "$CURSOR_TEXT $placeholder"
        } else {
            "$query$CURSOR_TEXT"
        }
        val spannable = SpannableString(displayText)
        if (query.isBlank()) {
            spannable.setSpan(
                CursorOffsetSpan(cursorColor, dp(CURSOR_SHIFT_LEFT_DP).toFloat()),
                0,
                CURSOR_TEXT.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            spannable.setSpan(
                ForegroundColorSpan(placeholderColor),
                CURSOR_TEXT.length + 1,
                displayText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        } else {
            spannable.setSpan(ForegroundColorSpan(textColor), 0, query.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(
                CursorOffsetSpan(cursorColor, dp(CURSOR_SHIFT_LEFT_DP).toFloat()),
                query.length,
                displayText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        queryText.text = spannable

        val hasQuery = query.isNotBlank()
        clearButton.alpha = if (hasQuery) 1f else 0f
        clearButton.isEnabled = hasQuery
        clearButton.isClickable = hasQuery
        contentDescription = if (hasQuery) query else placeholder
    }

    private fun tintedIcon(color: Int) =
        ContextCompat.getDrawable(context, R.drawable.ic_close_24)?.mutate()?.apply {
            setTint(color)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private class CursorOffsetSpan(
        private val color: Int,
        private val shiftLeftPx: Float,
    ) : ReplacementSpan() {
        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?,
        ): Int {
            return paint.measureText(text, start, end).roundToInt().coerceAtLeast(1)
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) {
            val originalColor = paint.color
            paint.color = color
            canvas.drawText(text, start, end, x - shiftLeftPx, y.toFloat(), paint)
            paint.color = originalColor
        }
    }

    private companion object {
        const val CURSOR_TEXT = "|"
        const val CURSOR_BLINK_MS = 530L
        const val CURSOR_SHIFT_LEFT_DP = 3
    }
}
