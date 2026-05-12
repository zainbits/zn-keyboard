package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

internal fun Context.dp(value: Int): Int {
    return (value * resources.displayMetrics.density).roundToInt()
}

internal fun Context.dp(value: Float): Float {
    return value * resources.displayMetrics.density
}

internal fun Context.tintedDrawable(iconResId: Int, color: Int): Drawable? {
    return ContextCompat.getDrawable(this, iconResId)?.mutate()?.apply {
        setTint(color)
    }
}

internal fun LinearLayout.LayoutParams.withDpMargins(
    context: Context,
    horizontal: Int = 0,
    vertical: Int = 0,
    start: Int = horizontal,
    top: Int = vertical,
    end: Int = horizontal,
    bottom: Int = vertical,
): LinearLayout.LayoutParams {
    setMargins(context.dp(start), context.dp(top), context.dp(end), context.dp(bottom))
    return this
}
