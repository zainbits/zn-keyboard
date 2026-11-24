package dev.zain.znkeyboard.constants

object VoiceDefaults {
    /** Bark (Parakeet TDT) expects 16 kHz mono. */
    const val SAMPLE_RATE = 16_000
    const val MAX_RECORDING_SECONDS = 120
    const val MIN_RECORDING_MS = 300
    const val RECOGNIZER_THREADS = 4

    /**
     * How long the ~0.8 GB model stays loaded after the voice panel closes, for back-to-back
     * dictation. Chosen in Settings → Voice typing; [KEEP_LOADED_ALWAYS] keeps it until the keyboard
     * service is destroyed.
     */
    const val KEEP_LOADED_ALWAYS = -1
    const val DEFAULT_KEEP_LOADED_SECONDS = 60
    val KEEP_LOADED_OPTIONS: List<Pair<Int, String>> = listOf(
        0 to "Off",
        60 to "1 min",
        300 to "5 min",
        900 to "15 min",
        KEEP_LOADED_ALWAYS to "Always",
    )

    /** Level meter: RMS that fills the ring, and how often the UI hears about it. */
    const val FULL_SCALE_RMS = 0.12f
    const val LEVEL_UPDATE_MS = 50L

    /** Voice key tint: model in memory (warm) vs. needs loading (cold). */
    const val WARM_KEY_COLOR = 0xFF34A853.toInt()
    const val COLD_KEY_COLOR = 0xFF4285F4.toInt()

    const val MODEL_DISPLAY_NAME = "Bark 3.0 Clean"
    const val MODEL_ATTRIBUTION =
        "Bark 3.0 Clean by Conway Research, fine-tuned from NVIDIA Parakeet TDT 0.6B v3. Licensed CC BY 4.0."
}
