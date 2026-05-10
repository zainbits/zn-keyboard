package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.zain.znkeyboard.R
import kotlin.math.roundToInt

internal object ImePanelChrome {
    const val BROWSE_HEADER_HEIGHT_DP = 42
    const val BACK_BUTTON_WIDTH_DP = 44
    const val SEARCH_BUTTON_WIDTH_DP = 124

    fun backButton(
        context: Context,
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
            setImageDrawable(tintedIcon(context, R.drawable.ic_chevron_left_24, textColor))
            background = roundedBackground(context, backgroundColor)
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, SEARCH_TEXT_SIZE_SP)
            setPadding(dp(context, 14), 0, dp(context, 16), 0)
            background = roundedBackground(context, backgroundColor)
            setOnClickListener { onClick() }
        }
    }

    private fun roundedBackground(context: Context, color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, ImeLayout.KEY_RADIUS_DP).toFloat()
            setColor(color)
        }
    }

    private fun tintedIcon(context: Context, iconResId: Int, color: Int) =
        ContextCompat.getDrawable(context, iconResId)?.mutate()?.apply {
            setTint(color)
        }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).roundToInt()
    }

    private const val SEARCH_LABEL = "Search"
    private const val SEARCH_TEXT_SIZE_SP = 15f
}
