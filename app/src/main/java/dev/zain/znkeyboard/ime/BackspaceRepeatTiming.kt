package dev.zain.znkeyboard.ime

import android.view.ViewConfiguration

internal object BackspaceRepeatTiming {
    private const val ACCELERATION_STEP_REPEATS = 3
    private const val ACCELERATION_STEP_MS = 4
    private const val MIN_REPEAT_INTERVAL_MS = 32

    fun startDelayMillis(): Long {
        return ViewConfiguration.getKeyRepeatTimeout().toLong()
    }

    fun repeatDelayMillis(repeatCount: Int): Long {
        val baseDelay = ViewConfiguration.getKeyRepeatDelay()
        val acceleration = (repeatCount / ACCELERATION_STEP_REPEATS) * ACCELERATION_STEP_MS
        return (baseDelay - acceleration)
            .coerceAtLeast(MIN_REPEAT_INTERVAL_MS)
            .toLong()
    }
}
