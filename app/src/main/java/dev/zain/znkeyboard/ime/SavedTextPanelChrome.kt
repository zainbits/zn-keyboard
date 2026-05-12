package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.zain.znkeyboard.R
import kotlin.math.roundToInt

internal object SavedTextPanelChrome {
    fun searchKey(
        context: Context,
        label: String,
        backgroundColor: Int,
        textColor: Int,
        onClick: () -> Unit,
    ): TextView {
        return TextView(context).apply {
            text = label
            contentDescription = label
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            isClickable = true
            isFocusable = false
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(context, 14), 0, dp(context, 14), 0)
            setCompoundDrawablesRelativeWithIntrinsicBounds(tintedIcon(context, R.drawable.ic_search_24, textColor), null, null, null)
            compoundDrawablePadding = dp(context, 8)
            background = ImePressFeedback.roundedBackground(context, backgroundColor, textColor)
            setOnClickListener { onClick() }
        }
    }

    fun queryText(
        context: Context,
        backgroundColor: Int,
        textColor: Int,
    ): TextView {
        return TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setSingleLine(true)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(context, 14), 0, dp(context, 14), 0)
            background = ImePressFeedback.roundedBackground(context, backgroundColor, textColor)
        }
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
            background = ImePressFeedback.roundedBackground(context, backgroundColor, textColor)
            isClickable = true
            isFocusable = false
            scaleType = ImageView.ScaleType.CENTER
            setPadding(0, 0, 0, 0)
            setImageDrawable(tintedIcon(context, iconResId, textColor))
            setOnClickListener { onClick() }
        }
    }

    fun tabButton(
        context: Context,
        label: String,
        selected: Boolean,
        selectedColor: Int,
        unselectedColor: Int,
        textColor: Int,
        mutedTextColor: Int,
        onClick: () -> Unit,
    ): TextView {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setOnClickListener { onClick() }
            updateTabButton(
                selected = selected,
                selectedColor = selectedColor,
                unselectedColor = unselectedColor,
                textColor = textColor,
                mutedTextColor = mutedTextColor,
            )
        }
    }

    fun TextView.updateTabButton(
        selected: Boolean,
        selectedColor: Int,
        unselectedColor: Int,
        textColor: Int,
        mutedTextColor: Int,
    ) {
        setTextColor(if (selected) textColor else mutedTextColor)
        typeface = Typeface.DEFAULT
        background = if (selected) {
            ImePressFeedback.roundedBackground(
                context = context,
                containerColor = selectedColor,
                contentColor = textColor,
                radiusDp = 18,
            )
        } else {
            ColorDrawable(Color.TRANSPARENT)
        }
    }

    fun LinearLayout.LayoutParams.withDpMargins(
        context: Context,
        horizontal: Int = 0,
        vertical: Int = 0,
        start: Int = horizontal,
        top: Int = vertical,
        end: Int = horizontal,
        bottom: Int = vertical,
    ): LinearLayout.LayoutParams {
        setMargins(dp(context, start), dp(context, top), dp(context, end), dp(context, bottom))
        return this
    }

    private fun tintedIcon(context: Context, iconResId: Int, color: Int) =
        ContextCompat.getDrawable(context, iconResId)?.mutate()?.apply {
            setTint(color)
        }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).roundToInt()
    }
}
