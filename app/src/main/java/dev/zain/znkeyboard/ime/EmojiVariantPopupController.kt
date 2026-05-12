package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import dev.zain.znkeyboard.EmojiCatalog
import dev.zain.znkeyboard.EmojiEntry

internal class EmojiVariantPopupController(
    private val context: Context,
    private val backgroundColor: Int,
    private val textColor: Int,
    private val pressedColor: Int,
    private val onEmojiSelected: (String) -> Unit,
) {
    private var popup: PopupWindow? = null

    fun show(entry: EmojiEntry, anchor: View): Boolean {
        val variants = EmojiCatalog.variantsFor(entry)
        if (variants.isEmpty()) return false

        dismiss()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
            background = ImePressFeedback.roundedShape(context, backgroundColor)
        }

        variants.forEach { variant ->
            row.addView(
                TextView(context).apply {
                    text = variant.emoji
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    typeface = Typeface.DEFAULT
                    isEnabled = true
                    isClickable = true
                    isFocusable = false
                    alpha = 1f
                    setTextColor(textColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                    background = ImePressFeedback.roundedTransientBackground(
                        context = context,
                        pressedColor = pressedColor,
                    )
                    contentDescription = variant.name
                    setOnClickListener { onEmojiSelected(variant.emoji) }
                },
                LinearLayout.LayoutParams(context.dp(42), context.dp(42)).withDpMargins(context, horizontal = 2),
            )
        }

        popup = PopupWindow(
            row,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false,
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = context.dp(8).toFloat()
            showAsDropDown(anchor, 0, -anchor.height - context.dp(54))
        }
        return true
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }
}
