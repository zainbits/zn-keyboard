package dev.zain.znkeyboard.ime

import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import dev.zain.znkeyboard.KeyboardSettings

internal object SavedTextSnippetCard {
    data class Style(
        val backgroundColor: Int,
        val textColor: Int,
        val tagTextColor: Int,
        val radiusDp: Int = ImeLayout.KEY_RADIUS_DP,
        val strokeWidthDp: Int = 0,
        val strokeColor: Int = android.graphics.Color.TRANSPARENT,
        val horizontalPaddingDp: Int = 12,
        val verticalPaddingDp: Int = 9,
        val minHeightDp: Int? = null,
        val textSizeSp: Float = 14f,
        val lineSpacingDp: Int? = null,
        val maxLines: Int = 2,
        val tagTopMarginDp: Int = 5,
    )

    fun create(
        context: Context,
        snippet: KeyboardSettings.TextSnippet,
        style: Style,
        onClick: (String) -> Unit,
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                context.dp(style.horizontalPaddingDp),
                context.dp(style.verticalPaddingDp),
                context.dp(style.horizontalPaddingDp),
                context.dp(style.verticalPaddingDp),
            )
            style.minHeightDp?.let { minimumHeight = context.dp(it) }
            background = ImePressFeedback.roundedBackground(
                context = context,
                containerColor = style.backgroundColor,
                contentColor = style.textColor,
                radiusDp = style.radiusDp,
                strokeWidthDp = style.strokeWidthDp,
                strokeColor = style.strokeColor,
            )
            isClickable = true
            isFocusable = false
            contentDescription = snippet.text
            addView(
                TextView(context).apply {
                    text = snippet.text
                    includeFontPadding = true
                    setTextColor(style.textColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, style.textSizeSp)
                    style.lineSpacingDp?.let { setLineSpacing(context.dp(it).toFloat(), 1f) }
                    maxLines = style.maxLines
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            if (snippet.tags.isNotEmpty()) {
                addView(
                    TextView(context).apply {
                        text = snippet.tags.joinToString("  ") { "#$it" }
                        includeFontPadding = false
                        setSingleLine(true)
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(style.tagTextColor)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .withDpMargins(context, top = style.tagTopMarginDp),
                )
            }
            setOnClickListener { onClick(snippet.text) }
        }
    }
}
