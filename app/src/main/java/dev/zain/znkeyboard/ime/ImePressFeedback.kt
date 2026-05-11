package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import kotlin.math.roundToInt

internal object ImePressFeedback {
    private const val PRESSED_DARKEN_OPACITY = 0.45f
    private val PRESSED_STATE = intArrayOf(android.R.attr.state_pressed)
    private val FOCUSED_STATE = intArrayOf(android.R.attr.state_focused)
    private val DEFAULT_STATE = intArrayOf()

    fun roundedBackground(
        context: Context,
        containerColor: Int,
        contentColor: Int,
        radiusDp: Int = ImeLayout.KEY_RADIUS_DP,
        strokeWidthDp: Int = 0,
        strokeColor: Int = Color.TRANSPARENT,
    ): Drawable {
        val pressedColor = stateLayerColor(
            containerColor = containerColor,
            contentColor = contentColor,
            opacity = PRESSED_DARKEN_OPACITY,
        )
        return StateListDrawable().apply {
            addState(PRESSED_STATE, roundedShape(context, pressedColor, radiusDp, strokeWidthDp, strokeColor))
            addState(FOCUSED_STATE, roundedShape(context, pressedColor, radiusDp, strokeWidthDp, strokeColor))
            addState(DEFAULT_STATE, roundedShape(context, containerColor, radiusDp, strokeWidthDp, strokeColor))
        }
    }

    fun roundedShape(
        context: Context,
        color: Int,
        radiusDp: Int = ImeLayout.KEY_RADIUS_DP,
        strokeWidthDp: Int = 0,
        strokeColor: Int = Color.TRANSPARENT,
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(color)
            if (strokeWidthDp > 0) {
                setStroke(dp(context, strokeWidthDp), strokeColor)
            }
        }
    }

    fun stateLayerColor(containerColor: Int, contentColor: Int, opacity: Float = PRESSED_DARKEN_OPACITY): Int {
        val layerAlpha = (Color.alpha(contentColor) * opacity).roundToInt().coerceIn(0, 255)
        val layerColor = Color.argb(
            layerAlpha,
            0,
            0,
            0,
        )
        return compositeColors(layerColor, containerColor)
    }

    private fun compositeColors(foreground: Int, background: Int): Int {
        val foregroundAlpha = Color.alpha(foreground)
        val backgroundAlpha = Color.alpha(background)
        val alpha = compositeAlpha(foregroundAlpha, backgroundAlpha)
        if (alpha == 0) return Color.TRANSPARENT

        return Color.argb(
            alpha,
            compositeComponent(Color.red(foreground), foregroundAlpha, Color.red(background), backgroundAlpha, alpha),
            compositeComponent(Color.green(foreground), foregroundAlpha, Color.green(background), backgroundAlpha, alpha),
            compositeComponent(Color.blue(foreground), foregroundAlpha, Color.blue(background), backgroundAlpha, alpha),
        )
    }

    private fun compositeAlpha(foregroundAlpha: Int, backgroundAlpha: Int): Int {
        return 255 - ((255 - backgroundAlpha) * (255 - foregroundAlpha) / 255)
    }

    private fun compositeComponent(
        foregroundComponent: Int,
        foregroundAlpha: Int,
        backgroundComponent: Int,
        backgroundAlpha: Int,
        alpha: Int,
    ): Int {
        return ((255 * foregroundComponent * foregroundAlpha) +
            (backgroundComponent * backgroundAlpha * (255 - foregroundAlpha))) / (alpha * 255)
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).roundToInt()
    }
}
