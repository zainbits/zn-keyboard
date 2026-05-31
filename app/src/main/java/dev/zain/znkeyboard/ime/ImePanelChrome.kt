package dev.zain.znkeyboard.ime

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import dev.zain.znkeyboard.R
import dev.zain.znkeyboard.constants.ImeDimensions

internal object ImePanelChrome {
    const val BROWSE_HEADER_HEIGHT_DP = ImeDimensions.BROWSE_HEADER_HEIGHT_DP
    const val BACK_BUTTON_WIDTH_DP = ImeDimensions.BACK_BUTTON_WIDTH_DP
    const val SEARCH_BUTTON_WIDTH_DP = ImeDimensions.SEARCH_BUTTON_WIDTH_DP

    fun backButton(
        context: Context,
        contentDescription: String,
        backgroundColor: Int,
        textColor: Int,
        onClick: () -> Unit,
    ): ImageButton {
        return iconButton(
            context = context,
            iconResId = R.drawable.ic_chevron_left_24,
            contentDescription = contentDescription,
            backgroundColor = backgroundColor,
            textColor = textColor,
            onClick = onClick,
        )
    }

    fun iconButton(
        context: Context,
        iconResId: Int,
        contentDescription: String,
        backgroundColor: Int,
        textColor: Int,
        onClick: () -> Unit,
    ): ImageButton {
        return ImageButton(context).apply {
            this.contentDescription = contentDescription
            isClickable = true
            isFocusable = false
            scaleType = ImageView.ScaleType.CENTER
            setPadding(0, 0, 0, 0)
            setImageDrawable(context.tintedDrawable(iconResId, textColor))
            background = ImePressFeedback.roundedBackground(context, backgroundColor, textColor)
            setOnClickListener { onClick() }
        }
    }

    fun searchButton(
        context: Context,
        contentDescription: String,
        backgroundColor: Int,
        textColor: Int,
        onClick: () -> Unit,
    ): TextView {
        return TextView(context).apply {
            text = SEARCH_LABEL
            this.contentDescription = contentDescription
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            isClickable = true
            isFocusable = false
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeDimensions.PANEL_SEARCH_TEXT_SIZE_SP)
            setPadding(context.dp(14), 0, context.dp(16), 0)
            background = ImePressFeedback.roundedBackground(context, backgroundColor, textColor)
            setOnClickListener { onClick() }
        }
    }

    private const val SEARCH_LABEL = "Search"
}
