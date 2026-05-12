package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageButton
import android.widget.TextView
import dev.zain.znkeyboard.R

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
            setPadding(context.dp(14), 0, context.dp(14), 0)
            setCompoundDrawablesRelativeWithIntrinsicBounds(context.tintedDrawable(R.drawable.ic_search_24, textColor), null, null, null)
            compoundDrawablePadding = context.dp(8)
            background = ImePressFeedback.roundedBackground(context, backgroundColor, textColor)
            setOnClickListener { onClick() }
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
        return ImePanelChrome.iconButton(
            context = context,
            iconResId = iconResId,
            contentDescription = contentDescription,
            backgroundColor = backgroundColor,
            textColor = textColor,
            onClick = onClick,
        )
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

}
