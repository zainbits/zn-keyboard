package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout

class AgentAssistStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    interface Callback {
        fun onAgentDraftEdited(text: String)
        fun onAgentRewriteRequested()
        fun onAgentApplyRequested()
    }

    var callback: Callback? = null
    val isDraftFieldFocused: Boolean
        get() = draftText.hasFocus()

    private val rewriteButton = Button(context).apply {
        text = "Rewrite"
        isAllCaps = false
        minHeight = 0
        minWidth = 0
        isFocusable = false
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { callback?.onAgentRewriteRequested() }
    }
    private var updatingDraftText = false
    private val draftText = EditText(context).apply {
        setTextColor(TEXT_COLOR)
        setHintTextColor(HINT_COLOR)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setSingleLine(false)
        maxLines = 2
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isFocusableInTouchMode = true
        showSoftInputOnFocus = false
        setPadding(dp(12), 0, dp(12), 0)
        background = fieldBackground(FieldStatus.Neutral)
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!updatingDraftText) {
                        callback?.onAgentDraftEdited(s?.toString().orEmpty())
                    }
                }

                override fun afterTextChanged(s: Editable?) = Unit
            },
        )
    }
    private val applyButton = Button(context).apply {
        text = "Apply"
        isAllCaps = false
        minHeight = 0
        minWidth = 0
        isFocusable = false
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { callback?.onAgentApplyRequested() }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = false
        isFocusableInTouchMode = false
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setBackgroundColor(BACKGROUND_COLOR)
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        visibility = GONE

        addView(
            rewriteButton,
            LayoutParams(dp(82), LayoutParams.MATCH_PARENT),
        )
        addView(
            draftText,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            },
        )
        addView(
            applyButton,
            LayoutParams(dp(72), LayoutParams.MATCH_PARENT),
        )
    }

    fun commitDraftText(value: String): Boolean {
        val editable = draftText.text ?: return false
        val start = draftText.selectionStart.coerceIn(0, editable.length)
        val end = draftText.selectionEnd.coerceIn(0, editable.length)
        editable.replace(minOf(start, end), maxOf(start, end), value)
        return true
    }

    fun deleteDraftTextBeforeCursor(): Boolean {
        val editable = draftText.text ?: return false
        val start = draftText.selectionStart.coerceIn(0, editable.length)
        val end = draftText.selectionEnd.coerceIn(0, editable.length)
        return when {
            start != end -> {
                editable.delete(minOf(start, end), maxOf(start, end))
                true
            }
            start > 0 -> {
                editable.delete(start - 1, start)
                true
            }
            else -> false
        }
    }

    fun handleDraftKeyCode(keyCode: Int): Boolean {
        val editable = draftText.text ?: return false
        val start = draftText.selectionStart.coerceIn(0, editable.length)
        val end = draftText.selectionEnd.coerceIn(0, editable.length)
        val selectionStart = minOf(start, end)
        val selectionEnd = maxOf(start, end)
        return when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> {
                clearDraftFocus()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                draftText.setSelection(if (selectionStart != selectionEnd) selectionStart else (selectionStart - 1).coerceAtLeast(0))
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                draftText.setSelection(if (selectionStart != selectionEnd) selectionEnd else (selectionEnd + 1).coerceAtMost(editable.length))
                true
            }
            KeyEvent.KEYCODE_MOVE_HOME -> {
                draftText.setSelection(0)
                true
            }
            KeyEvent.KEYCODE_MOVE_END -> {
                draftText.setSelection(editable.length)
                true
            }
            KeyEvent.KEYCODE_TAB -> commitDraftText("\t")
            else -> false
        }
    }

    fun clearDraftFocus() {
        draftText.clearFocus()
    }

    fun render(state: State) {
        visibility = if (state.visible) VISIBLE else GONE
        draftText.hint = state.errorText ?: state.placeholder
        draftText.setHintTextColor(if (state.errorText != null) ERROR_COLOR else HINT_COLOR)
        draftText.background = fieldBackground(state.fieldStatus())
        draftText.setPadding(dp(12), 0, dp(12), 0)
        if (draftText.text?.toString() != state.text) {
            val selection = if (draftText.hasFocus()) draftText.selectionStart else state.text.length
            updatingDraftText = true
            draftText.setText(state.text)
            draftText.setSelection(selection.coerceIn(0, state.text.length))
            updatingDraftText = false
        }
        rewriteButton.isEnabled = state.rewriteEnabled && !state.loading
        applyButton.isEnabled = state.applyEnabled && !state.loading
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun fieldBackground(status: FieldStatus): Drawable {
        return FieldBackgroundDrawable(
            fillColor = FIELD_COLOR,
            radius = dp(10).toFloat(),
            glowWidth = dp(9).toFloat(),
            strokeWidth = dp(2).toFloat(),
            colors = status.colors,
            animated = status.animated,
        )
    }

    private fun State.fieldStatus(): FieldStatus {
        return when {
            loading -> FieldStatus.Loading
            errorText != null -> FieldStatus.Error
            applyEnabled -> FieldStatus.Success
            else -> FieldStatus.Neutral
        }
    }

    data class State(
        val visible: Boolean,
        val text: String = "",
        val placeholder: String = "Agent mode ready",
        val errorText: String? = null,
        val loading: Boolean = false,
        val rewriteEnabled: Boolean = false,
        val applyEnabled: Boolean = false,
    )

    private enum class FieldStatus(
        val colors: IntArray,
        val animated: Boolean = false,
    ) {
        Neutral(intArrayOf()),
        Loading(
            intArrayOf(
                Color.rgb(66, 133, 244),
                Color.rgb(168, 85, 247),
                Color.rgb(236, 72, 153),
                Color.rgb(251, 191, 36),
                Color.rgb(52, 211, 153),
                Color.rgb(66, 133, 244),
            ),
            animated = true,
        ),
        Success(
            intArrayOf(
                Color.rgb(52, 211, 153),
                Color.rgb(163, 230, 53),
                Color.rgb(34, 197, 94),
                Color.rgb(45, 212, 191),
                Color.rgb(52, 211, 153),
            ),
        ),
        Error(
            intArrayOf(
                Color.rgb(248, 113, 113),
                Color.rgb(236, 72, 153),
                Color.rgb(251, 146, 60),
                Color.rgb(239, 68, 68),
                Color.rgb(248, 113, 113),
            ),
        ),
    }

    private class FieldBackgroundDrawable(
        private val fillColor: Int,
        private val radius: Float,
        private val glowWidth: Float,
        private val strokeWidth: Float,
        private val colors: IntArray,
        private val animated: Boolean,
    ) : Drawable() {
        private val rect = RectF()
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillColor
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private var phase = 0f
        private val animationTick = Runnable {
            phase = (phase + 0.025f) % 1f
            invalidateSelf()
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun draw(canvas: android.graphics.Canvas) {
            rect.set(bounds)
            canvas.drawRoundRect(rect, radius, radius, fillPaint)

            if (colors.isEmpty()) return

            rect.inset(glowWidth / 2f, glowWidth / 2f)
            val offset = if (animated) rect.width() * phase else 0f

            val shader = LinearGradient(
                rect.left - rect.width() + offset,
                rect.centerY(),
                rect.right + offset,
                rect.centerY(),
                colors,
                null,
                Shader.TileMode.MIRROR,
            )

            glowPaint.shader = shader
            glowPaint.strokeWidth = glowWidth
            glowPaint.alpha = 80
            canvas.drawRoundRect(rect, radius, radius, glowPaint)

            glowPaint.strokeWidth = glowWidth * 0.55f
            glowPaint.alpha = 135
            canvas.drawRoundRect(rect, radius, radius, glowPaint)

            strokePaint.shader = shader
            strokePaint.strokeWidth = strokeWidth
            strokePaint.alpha = 255
            canvas.drawRoundRect(rect, radius, radius, strokePaint)

            if (animated) {
                scheduleSelf(animationTick, SystemClock.uptimeMillis() + ANIMATION_FRAME_MS)
            }
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            glowPaint.alpha = alpha
            strokePaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            glowPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int {
            return PixelFormat.TRANSLUCENT
        }
    }

    private companion object {
        val BACKGROUND_COLOR = Color.rgb(34, 34, 34)
        val FIELD_COLOR = Color.rgb(42, 42, 42)
        const val TEXT_COLOR = Color.WHITE
        val HINT_COLOR = Color.rgb(180, 180, 180)
        val ERROR_COLOR = Color.rgb(255, 183, 77)
        const val ANIMATION_FRAME_MS = 32L
    }
}
