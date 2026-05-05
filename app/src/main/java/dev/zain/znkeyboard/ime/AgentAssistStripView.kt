package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView
import dev.zain.znkeyboard.KeyboardSettings

class AgentAssistStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    interface Callback {
        fun onAgentRewriteRequested()
        fun onAgentHistoryRequested()
        fun onAgentRowAction(action: KeyboardAction, modifiers: ModifierState)
    }

    var callback: Callback? = null
    private var state = State(visible = false)
    private var agentRowKeyIds = KeyboardSettings.DEFAULT_AGENT_ROW_KEY_IDS
    private var ctrl = false
    private var alt = false
    private var heightScale = 1f

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = false
        isFocusableInTouchMode = false
        setPadding(dp(ImeLayout.HORIZONTAL_PADDING_DP), dp(ImeLayout.TOP_PADDING_DP), dp(ImeLayout.HORIZONTAL_PADDING_DP), 0)
        setBackgroundColor(PALETTE.background)
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        visibility = GONE
        rebuildRow()
    }

    fun setAgentRowKeyIds(keyIds: List<String>) {
        val normalized = KeyboardSettings.normalizeAgentRowKeyIds(keyIds)
        if (agentRowKeyIds != normalized) {
            agentRowKeyIds = normalized
            rebuildRow()
        }
    }

    fun setHeightScale(scale: Float) {
        if (heightScale != scale) {
            heightScale = scale
            requestLayout()
        }
    }

    fun render(nextState: State) {
        val loadingChanged = state.loading != nextState.loading
        val availabilityChanged = state.rewriteEnabled != nextState.rewriteEnabled ||
            state.historyEnabled != nextState.historyEnabled
        state = nextState
        visibility = if (state.visible) VISIBLE else GONE
        if (loadingChanged || availabilityChanged) {
            rebuildRow()
        }
    }

    private fun rebuildRow() {
        removeAllViews()
        addKey(
            label = "Rewrite",
            weight = END_KEY_WEIGHT,
            role = KeyRole.Action,
            status = if (state.loading) FieldStatus.Loading else FieldStatus.Neutral,
            enabled = state.rewriteEnabled && !state.loading,
            emphasizedWhenDisabled = state.loading,
            onClick = { callback?.onAgentRewriteRequested() },
        )

        agentRowKeyIds.forEach { keyId ->
            addAgentKey(keyId)
        }

        addKey(
            label = "History",
            weight = END_KEY_WEIGHT,
            role = KeyRole.Function,
            enabled = state.historyEnabled && !state.loading,
            onClick = { callback?.onAgentHistoryRequested() },
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = ImeLayout.compactAgentRowHeightPx(context, heightScale)
        val exactHeightSpec = MeasureSpec.makeMeasureSpec(resolveSize(desiredHeight, heightMeasureSpec), MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, exactHeightSpec)
    }

    private fun addAgentKey(keyId: String) {
        val label = KeyboardSettings.labelForUpperRowKey(keyId).ifBlank { keyId }
        val active = (keyId == "ctrl" && ctrl) || (keyId == "alt" && alt)
        addKey(
            label = label,
            weight = 1f,
            role = roleForKeyId(keyId),
            active = active,
            enabled = !state.loading,
            onClick = { handleAgentKey(keyId) },
        )
    }

    private fun addKey(
        label: String,
        weight: Float,
        role: KeyRole,
        status: FieldStatus = FieldStatus.Neutral,
        active: Boolean = false,
        enabled: Boolean = true,
        emphasizedWhenDisabled: Boolean = false,
        onClick: () -> Unit,
    ) {
        val view = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            includeFontPadding = false
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(if (role == KeyRole.Character || active) PALETTE.text else PALETTE.mutedText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (role == KeyRole.Character) 18f else 13f)
            setPadding(dp(4), 0, dp(4), 0)
            background = keyBackground(status = status, role = role, active = active)
            isClickable = enabled
            isFocusable = false
            alpha = if (enabled || emphasizedWhenDisabled) 1f else DISABLED_ALPHA
            if (enabled) {
                setOnClickListener { onClick() }
            }
        }

        addView(
            view,
            LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply {
                if (childCount > 0) marginStart = dp(ImeLayout.KEY_GAP_DP)
            },
        )
    }

    private fun handleAgentKey(keyId: String) {
        when (keyId) {
            "ctrl" -> {
                ctrl = !ctrl
                rebuildRow()
            }
            "alt" -> {
                alt = !alt
                rebuildRow()
            }
            else -> {
                actionForKeyId(keyId)?.let { action ->
                    callback?.onAgentRowAction(action, ModifierState(ctrl = ctrl, alt = alt))
                    if (ctrl || alt) {
                        ctrl = false
                        alt = false
                        rebuildRow()
                    }
                }
            }
        }
    }

    private fun actionForKeyId(keyId: String): KeyboardAction? {
        return when (keyId) {
            "tab" -> KeyboardAction.KeyCode(KeyEvent.KEYCODE_TAB)
            "esc" -> KeyboardAction.KeyCode(KeyEvent.KEYCODE_ESCAPE)
            "left" -> KeyboardAction.KeyCode(KeyEvent.KEYCODE_DPAD_LEFT)
            "up" -> KeyboardAction.KeyCode(KeyEvent.KEYCODE_DPAD_UP)
            "down" -> KeyboardAction.KeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
            "right" -> KeyboardAction.KeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
            "home" -> KeyboardAction.KeyCode(KeyEvent.KEYCODE_MOVE_HOME)
            "end" -> KeyboardAction.KeyCode(KeyEvent.KEYCODE_MOVE_END)
            "page_up" -> KeyboardAction.KeyCode(KeyEvent.KEYCODE_PAGE_UP)
            "page_down" -> KeyboardAction.KeyCode(KeyEvent.KEYCODE_PAGE_DOWN)
            "backspace" -> KeyboardAction.Backspace
            "pipe" -> KeyboardAction.Text("|")
            "slash" -> KeyboardAction.Text("/")
            "backslash" -> KeyboardAction.Text("\\")
            "minus" -> KeyboardAction.Text("-")
            "equals" -> KeyboardAction.Text("=")
            "underscore" -> KeyboardAction.Text("_")
            "plus" -> KeyboardAction.Text("+")
            "colon" -> KeyboardAction.Text(":")
            "semicolon" -> KeyboardAction.Text(";")
            "quote" -> KeyboardAction.Text("\"")
            "apostrophe" -> KeyboardAction.Text("'")
            "backtick" -> KeyboardAction.Text("`")
            "at" -> KeyboardAction.Text("@")
            "hash" -> KeyboardAction.Text("#")
            "dollar" -> KeyboardAction.Text("$")
            "ampersand" -> KeyboardAction.Text("&")
            "star" -> KeyboardAction.Text("*")
            "left_paren" -> KeyboardAction.Text("(")
            "right_paren" -> KeyboardAction.Text(")")
            "left_bracket" -> KeyboardAction.Text("[")
            "right_bracket" -> KeyboardAction.Text("]")
            "left_brace" -> KeyboardAction.Text("{")
            "right_brace" -> KeyboardAction.Text("}")
            "less_than" -> KeyboardAction.Text("<")
            "greater_than" -> KeyboardAction.Text(">")
            else -> null
        }
    }

    private fun roleForKeyId(keyId: String): KeyRole {
        return when (keyId) {
            "pipe",
            "slash",
            "backslash",
            "minus",
            "equals",
            "underscore",
            "plus",
            "colon",
            "semicolon",
            "quote",
            "apostrophe",
            "backtick",
            "at",
            "hash",
            "dollar",
            "ampersand",
            "star",
            "left_paren",
            "right_paren",
            "left_bracket",
            "right_bracket",
            "left_brace",
            "right_brace",
            "less_than",
            "greater_than",
            -> KeyRole.Character
            "esc" -> KeyRole.Action
            else -> KeyRole.Function
        }
    }

    private fun keyBackground(status: FieldStatus, role: KeyRole, active: Boolean): Drawable {
        val fillColor = when {
            active -> PALETTE.accent
            role == KeyRole.Action -> PALETTE.action
            role == KeyRole.Function -> PALETTE.function
            else -> PALETTE.key
        }
        return FieldBackgroundDrawable(
            fillColor = fillColor,
            radius = dp(ImeLayout.KEY_RADIUS_DP).toFloat(),
            glowWidth = dp(9).toFloat(),
            strokeWidth = dp(2).toFloat(),
            borderWidth = dp(1).toFloat(),
            borderColor = if (active) Color.TRANSPARENT else PALETTE.border,
            colors = status.colors,
            animated = status.animated,
        )
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    data class State(
        val visible: Boolean,
        val loading: Boolean = false,
        val rewriteEnabled: Boolean = false,
        val historyEnabled: Boolean = false,
    )

    private enum class KeyRole {
        Character,
        Function,
        Action,
    }

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
    }

    private class FieldBackgroundDrawable(
        private val fillColor: Int,
        private val radius: Float,
        private val glowWidth: Float,
        private val strokeWidth: Float,
        private val borderWidth: Float,
        private val borderColor: Int,
        private val colors: IntArray,
        private val animated: Boolean,
    ) : Drawable() {
        private val rect = RectF()
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillColor
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = borderColor
            strokeWidth = borderWidth
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private var phase = 0f
        private val animationTick = Runnable {
            phase = (phase + 0.025f) % 1f
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            rect.set(bounds)
            canvas.drawRoundRect(rect, radius, radius, fillPaint)

            if (colors.isEmpty()) {
                if (borderColor != Color.TRANSPARENT && borderWidth > 0f) {
                    rect.inset(borderWidth / 2f, borderWidth / 2f)
                    canvas.drawRoundRect(rect, radius, radius, borderPaint)
                }
                return
            }

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
            borderPaint.alpha = alpha
            glowPaint.alpha = alpha
            strokePaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            borderPaint.colorFilter = colorFilter
            glowPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int {
            return PixelFormat.TRANSLUCENT
        }
    }

    private object PALETTE {
        val background = Color.rgb(34, 34, 34)
        val key = Color.rgb(42, 42, 42)
        val function = Color.rgb(50, 50, 50)
        val action = Color.rgb(42, 42, 42)
        val accent = Color.rgb(48, 172, 226)
        val border = Color.rgb(62, 62, 62)
        const val text = Color.WHITE
        val mutedText = Color.rgb(230, 230, 230)
    }

    private companion object {
        const val END_KEY_WEIGHT = 1.4f
        const val DISABLED_ALPHA = 0.46f
        const val ANIMATION_FRAME_MS = 32L
    }
}
