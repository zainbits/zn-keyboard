package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

internal object ImeModeToolbar {
    enum class ActiveTab {
        Emoji,
        Gif,
    }

    data class Colors(
        val function: Int,
        val selected: Int,
        val text: Int,
        val mutedText: Int,
        val disabledText: Int,
    )

    fun populate(
        toolbar: LinearLayout,
        colors: Colors,
        activeTab: ActiveTab,
        onKeyboard: () -> Unit,
        onEmoji: (() -> Unit)?,
        onGif: (() -> Unit)?,
        onBackspace: () -> Unit,
    ) {
        val context = toolbar.context
        toolbar.removeAllViews()
        toolbar.addView(
            flatToolbarKey(context, "ABC", enabled = true, colors = colors, onClick = onKeyboard),
            LinearLayout.LayoutParams(context.dp(62), LinearLayout.LayoutParams.MATCH_PARENT)
                .withDpMargins(context, end = 4),
        )
        toolbar.addView(
            toolbarTab(
                context = context,
                label = "☺",
                active = activeTab == ActiveTab.Emoji,
                enabled = true,
                colors = colors,
                onClick = onEmoji,
            ),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                .withDpMargins(context, horizontal = 3),
        )
        toolbar.addView(
            toolbarTab(
                context = context,
                label = "GIF",
                active = activeTab == ActiveTab.Gif,
                enabled = true,
                colors = colors,
                onClick = onGif,
            ),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                .withDpMargins(context, horizontal = 3),
        )
        toolbar.addView(
            toolbarTab(context, "▣", active = false, enabled = false, colors = colors, onClick = null),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                .withDpMargins(context, horizontal = 3),
        )
        toolbar.addView(
            toolbarTab(context, ":-)", active = false, enabled = false, colors = colors, onClick = null),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                .withDpMargins(context, horizontal = 3),
        )
        toolbar.addView(
            flatToolbarKey(context, "⌫", enabled = true, colors = colors, onClick = onBackspace).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                contentDescription = "Delete"
            },
            LinearLayout.LayoutParams(context.dp(58), LinearLayout.LayoutParams.MATCH_PARENT)
                .withDpMargins(context, start = 4),
        )
    }

    private fun toolbarTab(
        context: Context,
        label: String,
        active: Boolean,
        enabled: Boolean,
        colors: Colors,
        onClick: (() -> Unit)?,
    ): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            includeFontPadding = false
            isEnabled = enabled
            isClickable = enabled && onClick != null
            isFocusable = false
            typeface = if (label == "GIF") Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(if (enabled) colors.text else colors.disabledText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label == "GIF") 14f else 22f)
            background = ImePressFeedback.roundedBackground(
                context = context,
                containerColor = if (active) colors.selected else colors.function,
                contentColor = if (enabled) colors.text else colors.disabledText,
            )
            onClick?.let { click -> setOnClickListener { click() } }
        }
    }

    private fun flatToolbarKey(
        context: Context,
        label: String,
        enabled: Boolean,
        colors: Colors,
        onClick: () -> Unit,
    ): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            includeFontPadding = false
            isEnabled = enabled
            isClickable = enabled
            isFocusable = false
            setTextColor(if (enabled) colors.mutedText else colors.disabledText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = ImePressFeedback.roundedBackground(context, Color.TRANSPARENT, colors.mutedText)
            setOnClickListener { onClick() }
        }
    }
}
