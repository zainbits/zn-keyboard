package dev.zain.znkeyboard.constants

object KeyboardDefaults {
    const val MIN_HEIGHT_SCALE = 0.82f
    const val MAX_HEIGHT_SCALE = 1.24f
    const val DEFAULT_HEIGHT_SCALE = 1.0f

    const val MAX_UPPER_ROW_KEYS = 9
    const val MAX_SECOND_ROW_BUTTONS = MAX_UPPER_ROW_KEYS

    const val MAX_TEXT_SNIPPETS = 60
    const val MAX_TEXT_SNIPPET_CHARS = 2_000
    const val MAX_TEXT_SNIPPET_TAGS = 8
    const val MAX_TEXT_SNIPPET_TAG_CHARS = 32

    const val MAX_CUSTOM_EMOJI_TAGGED_EMOJIS = 250
    const val MAX_CUSTOM_EMOJI_TAGS_PER_EMOJI = 12
    const val MAX_CUSTOM_EMOJI_TAG_CHARS = 32

    val DEFAULT_UPPER_ROW_KEY_IDS = listOf("ctrl", "tab", "pipe", "slash", "left", "up", "down", "right", "esc")
    val DEFAULT_SECOND_ROW_BUTTON_IDS = listOf("rewrite", "left", "right", "backspace")
    val DEFAULT_KEYBOARD_ROW_ORDER = listOf("second", "upper")
}
