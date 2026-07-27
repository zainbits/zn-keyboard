package dev.zain.znkeyboard

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.zain.znkeyboard.constants.AgentDefaults
import dev.zain.znkeyboard.constants.GifDefaults
import dev.zain.znkeyboard.constants.KeyboardDefaults
import dev.zain.znkeyboard.ime.ClipboardHistoryStore
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object KeyboardSettings {
    const val MIN_HEIGHT_SCALE = KeyboardDefaults.MIN_HEIGHT_SCALE
    const val MAX_HEIGHT_SCALE = KeyboardDefaults.MAX_HEIGHT_SCALE
    const val DEFAULT_HEIGHT_SCALE = KeyboardDefaults.DEFAULT_HEIGHT_SCALE
    const val MIN_BOTTOM_PADDING_DP = KeyboardDefaults.MIN_BOTTOM_PADDING_DP
    const val MAX_BOTTOM_PADDING_DP = KeyboardDefaults.MAX_BOTTOM_PADDING_DP
    const val DEFAULT_BOTTOM_PADDING_DP = KeyboardDefaults.DEFAULT_BOTTOM_PADDING_DP
    const val DEFAULT_AGENT_API_BASE_URL = AgentDefaults.DEFAULT_API_BASE_URL
    const val OPENROUTER_API_BASE_URL = AgentDefaults.OPENROUTER_API_BASE_URL
    const val DEFAULT_AGENT_MODEL = AgentDefaults.DEFAULT_MODEL
    const val DEFAULT_GIF_API_BASE_URL = GifDefaults.DEFAULT_API_BASE_URL
    const val MAX_UPPER_ROW_KEYS = KeyboardDefaults.MAX_UPPER_ROW_KEYS
    private const val LEGACY_MAX_AGENT_ROW_KEYS = 5
    const val MAX_SECOND_ROW_BUTTONS = KeyboardDefaults.MAX_SECOND_ROW_BUTTONS
    const val MAX_TEXT_SNIPPETS = KeyboardDefaults.MAX_TEXT_SNIPPETS
    const val MAX_TEXT_SNIPPET_CHARS = KeyboardDefaults.MAX_TEXT_SNIPPET_CHARS
    const val MAX_TEXT_SNIPPET_TAGS = KeyboardDefaults.MAX_TEXT_SNIPPET_TAGS
    const val MAX_TEXT_SNIPPET_TAG_CHARS = KeyboardDefaults.MAX_TEXT_SNIPPET_TAG_CHARS
    const val MAX_CUSTOM_EMOJI_TAGGED_EMOJIS = KeyboardDefaults.MAX_CUSTOM_EMOJI_TAGGED_EMOJIS
    const val MAX_CUSTOM_EMOJI_TAGS_PER_EMOJI = KeyboardDefaults.MAX_CUSTOM_EMOJI_TAGS_PER_EMOJI
    const val MAX_CUSTOM_EMOJI_TAG_CHARS = KeyboardDefaults.MAX_CUSTOM_EMOJI_TAG_CHARS
    const val MAX_AGENT_PROFILES = 8
    const val MAX_AGENT_PROFILE_NAME_CHARS = 30

    private const val BACKUP_FORMAT = "dev.zain.znkeyboard.settings-backup"
    private const val BACKUP_SCHEMA_VERSION = 3
    private const val BACKUP_JSON_INDENT = 2
    private const val PREFS_NAME = "keyboard_settings"
    private const val SECRET_PREFS_NAME = "keyboard_agent_secrets"
    private const val KEY_HEIGHT_SCALE = "height_scale"
    private const val KEY_BOTTOM_PADDING_DP = "bottom_padding_dp"
    private const val KEY_FUNCTION_KEY_BACKGROUNDS_ENABLED = "function_key_backgrounds_enabled"
    private const val KEY_UPPER_ROW_KEYS = "upper_row_keys"
    private const val KEY_AGENT_ROW_KEYS = "agent_row_keys"
    private const val KEY_SECOND_ROW_BUTTONS = "second_row_buttons"
    private const val KEY_KEYBOARD_ROW_ORDER = "keyboard_row_order"
    private const val KEY_SAVED_TEXT_PANEL_TAB = "saved_text_panel_tab"
    private const val KEY_TEXT_SNIPPETS = "text_snippets"
    private const val KEY_RECENT_EMOJIS = "recent_emojis"
    private const val KEY_RECENT_EMOJI_ROWS = "recent_emoji_rows"
    private const val KEY_EMOJI_SKIN_TONE = "emoji_skin_tone"
    private const val KEY_CUSTOM_EMOJI_TAGS = "custom_emoji_tags"
    private const val KEY_AGENT_MODE_ENABLED = "agent_mode_enabled"
    private const val KEY_AGENT_PROVIDER_TYPE = "agent_provider_type"
    private const val KEY_AGENT_API_BASE_URL = "agent_api_base_url"
    private const val KEY_AGENT_MODEL = "agent_model"
    private const val KEY_OPENROUTER_PROVIDER_SLUG = "openrouter_provider_slug"
    private const val KEY_AGENT_REASONING_MODE = "agent_reasoning_mode"
    private const val KEY_AGENT_REASONING_TEXT_ENABLED = "agent_reasoning_text_enabled"
    private const val KEY_AGENT_PROFILES = "agent_profiles"
    private const val KEY_ACTIVE_AGENT_PROFILE_ID = "active_agent_profile_id"
    private const val KEY_AGENT_API_KEY_LEGACY = "agent_api_key"
    private const val KEY_AGENT_API_KEY_CIPHERTEXT = "agent_api_key_ciphertext"
    private const val KEY_AGENT_API_KEY_IV = "agent_api_key_iv"
    private const val KEY_AGENT_API_KEY_LOCKED = "agent_api_key_locked"
    private const val KEY_GIF_API_BASE_URL = "gif_api_base_url"
    private const val KEY_GIF_APP_KEY_CIPHERTEXT = "gif_app_key_ciphertext"
    private const val KEY_GIF_APP_KEY_IV = "gif_app_key_iv"
    private const val KEY_GIF_APP_KEY_LOCKED = "gif_app_key_locked"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEYSTORE_AGENT_API_KEY_ALIAS = "znkeyboard_agent_api_key"
    private const val KEYSTORE_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128

    val DEFAULT_UPPER_ROW_KEY_IDS = KeyboardDefaults.DEFAULT_UPPER_ROW_KEY_IDS
    val DEFAULT_SECOND_ROW_BUTTON_IDS = KeyboardDefaults.DEFAULT_SECOND_ROW_BUTTON_IDS
    val DEFAULT_KEYBOARD_ROW_ORDER = KeyboardDefaults.DEFAULT_KEYBOARD_ROW_ORDER

    private val FUNCTION_KEY_OPTIONS = listOf(
        UpperRowKeyOption("ctrl", "Ctrl"),
        UpperRowKeyOption("alt", "Alt"),
        UpperRowKeyOption("tab", "Tab"),
        UpperRowKeyOption("esc", "Esc"),
        UpperRowKeyOption("left", "Left"),
        UpperRowKeyOption("up", "Up"),
        UpperRowKeyOption("down", "Down"),
        UpperRowKeyOption("right", "Right"),
        UpperRowKeyOption("home", "Home"),
        UpperRowKeyOption("end", "End"),
        UpperRowKeyOption("page_up", "PgUp"),
        UpperRowKeyOption("page_down", "PgDn"),
        UpperRowKeyOption("backspace", "Del"),
        // One-tap tmux chords (prefix + next/prev window).
        UpperRowKeyOption("tmux_prefix", "C-b"),
        UpperRowKeyOption("tmux_next", "C-b n"),
        UpperRowKeyOption("tmux_prev", "C-b p"),
        UpperRowKeyOption("pipe", "|"),
        UpperRowKeyOption("slash", "/"),
        UpperRowKeyOption("backslash", "\\"),
        UpperRowKeyOption("minus", "-"),
        UpperRowKeyOption("equals", "="),
        UpperRowKeyOption("underscore", "_"),
        UpperRowKeyOption("plus", "+"),
        UpperRowKeyOption("colon", ":"),
        UpperRowKeyOption("semicolon", ";"),
        UpperRowKeyOption("quote", "\""),
        UpperRowKeyOption("apostrophe", "'"),
        UpperRowKeyOption("backtick", "`"),
        UpperRowKeyOption("at", "@"),
        UpperRowKeyOption("hash", "#"),
        UpperRowKeyOption("dollar", "\$"),
        UpperRowKeyOption("ampersand", "&"),
        UpperRowKeyOption("star", "*"),
        UpperRowKeyOption("left_paren", "("),
        UpperRowKeyOption("right_paren", ")"),
        UpperRowKeyOption("left_bracket", "["),
        UpperRowKeyOption("right_bracket", "]"),
        UpperRowKeyOption("left_brace", "{"),
        UpperRowKeyOption("right_brace", "}"),
        UpperRowKeyOption("less_than", "<"),
        UpperRowKeyOption("greater_than", ">"),
    )

    val SHORTCUT_ROW_KEY_OPTIONS = listOf(
        UpperRowKeyOption("rewrite", "Rewrite"),
    ) + FUNCTION_KEY_OPTIONS
    val UPPER_ROW_KEY_OPTIONS = SHORTCUT_ROW_KEY_OPTIONS
    val SECOND_ROW_BUTTON_OPTIONS = SHORTCUT_ROW_KEY_OPTIONS
    private val shortcutRowKeyOptionIds = SHORTCUT_ROW_KEY_OPTIONS.mapTo(mutableSetOf()) { it.id }
    private val upperRowKeyOptionIds = shortcutRowKeyOptionIds
    private val secondRowButtonOptionIds = shortcutRowKeyOptionIds
    private val keyboardRowIds = KeyboardRow.entries.mapTo(mutableSetOf()) { it.id }
    private val whitespaceRegex = Regex("\\s+")
    private val textSnippetTagSeparatorRegex = Regex("[,\\s]+")

    fun readHeightScale(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs
            .getFloat(KEY_HEIGHT_SCALE, DEFAULT_HEIGHT_SCALE)
            .coerceIn(MIN_HEIGHT_SCALE, MAX_HEIGHT_SCALE)
    }

    fun saveHeightScale(context: Context, scale: Float) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(KEY_HEIGHT_SCALE, scale.coerceIn(MIN_HEIGHT_SCALE, MAX_HEIGHT_SCALE)).apply()
    }

    fun readBottomPaddingDp(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return normalizeBottomPaddingDp(
            prefs.getInt(KEY_BOTTOM_PADDING_DP, DEFAULT_BOTTOM_PADDING_DP),
        )
    }

    fun saveBottomPaddingDp(context: Context, paddingDp: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_BOTTOM_PADDING_DP, normalizeBottomPaddingDp(paddingDp)).apply()
    }

    fun normalizeBottomPaddingDp(paddingDp: Int): Int {
        return paddingDp.coerceIn(MIN_BOTTOM_PADDING_DP, MAX_BOTTOM_PADDING_DP)
    }

    fun readFunctionKeyBackgroundsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FUNCTION_KEY_BACKGROUNDS_ENABLED, true)
    }

    fun saveFunctionKeyBackgroundsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FUNCTION_KEY_BACKGROUNDS_ENABLED, enabled)
            .apply()
    }

    fun readUpperRowKeyIds(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_UPPER_ROW_KEYS, null) ?: return DEFAULT_UPPER_ROW_KEY_IDS
        return parseUpperRowKeyIds(stored) ?: DEFAULT_UPPER_ROW_KEY_IDS
    }

    fun saveUpperRowKeyIds(context: Context, keyIds: List<String>) {
        val keys = normalizeUpperRowKeyIds(keyIds)
        val encoded = JSONArray().apply {
            keys.forEach(::put)
        }.toString()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_UPPER_ROW_KEYS, encoded).apply()
    }

    fun normalizeUpperRowKeyIds(keyIds: List<String>): List<String> {
        return keyIds
            .filter { it in upperRowKeyOptionIds }
            .take(MAX_UPPER_ROW_KEYS)
    }

    fun labelForUpperRowKey(keyId: String): String {
        return UPPER_ROW_KEY_OPTIONS.firstOrNull { it.id == keyId }?.label.orEmpty()
    }

    fun readSecondRowButtonIds(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_SECOND_ROW_BUTTONS, null)?.let { stored ->
            return parseSecondRowButtonIds(stored) ?: DEFAULT_SECOND_ROW_BUTTON_IDS
        }

        val legacyAgentRow = prefs.getString(KEY_AGENT_ROW_KEYS, null)
            ?.let(::parseAgentRowKeyIds)
            ?: return DEFAULT_SECOND_ROW_BUTTON_IDS
        return normalizeSecondRowButtonIds(listOf("rewrite") + legacyAgentRow)
    }

    fun saveSecondRowButtonIds(context: Context, buttonIds: List<String>) {
        val buttons = normalizeSecondRowButtonIds(buttonIds)
        val encoded = JSONArray().apply {
            buttons.forEach(::put)
        }.toString()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SECOND_ROW_BUTTONS, encoded).apply()
    }

    fun normalizeSecondRowButtonIds(buttonIds: List<String>): List<String> {
        return buttonIds
            .filter { it in secondRowButtonOptionIds }
            .distinct()
            .take(MAX_SECOND_ROW_BUTTONS)
    }

    fun labelForSecondRowButton(buttonId: String): String {
        return SECOND_ROW_BUTTON_OPTIONS.firstOrNull { it.id == buttonId }?.label.orEmpty()
    }

    fun labelForShortcutRowKey(keyId: String): String {
        return SHORTCUT_ROW_KEY_OPTIONS.firstOrNull { it.id == keyId }?.label.orEmpty()
    }

    fun readSavedTextPanelTab(context: Context): SavedTextPanelTab {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SAVED_TEXT_PANEL_TAB, null)
        return SavedTextPanelTab.entries.firstOrNull { it.id == stored } ?: SavedTextPanelTab.Snippets
    }

    fun saveSavedTextPanelTab(context: Context, tab: SavedTextPanelTab) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SAVED_TEXT_PANEL_TAB, tab.id)
            .apply()
    }

    fun readKeyboardRowOrder(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_KEYBOARD_ROW_ORDER, null) ?: return DEFAULT_KEYBOARD_ROW_ORDER
        return parseKeyboardRowOrder(stored) ?: DEFAULT_KEYBOARD_ROW_ORDER
    }

    fun saveKeyboardRowOrder(context: Context, rowIds: List<String>) {
        val rows = normalizeKeyboardRowOrder(rowIds)
        val encoded = JSONArray().apply {
            rows.forEach(::put)
        }.toString()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_KEYBOARD_ROW_ORDER, encoded).apply()
    }

    fun normalizeKeyboardRowOrder(rowIds: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return buildList {
            rowIds.forEach { rowId ->
                if (rowId in keyboardRowIds && seen.add(rowId)) {
                    add(rowId)
                }
            }
            DEFAULT_KEYBOARD_ROW_ORDER.forEach { rowId ->
                if (seen.add(rowId)) {
                    add(rowId)
                }
            }
        }
    }

    fun labelForKeyboardRow(rowId: String): String {
        return KeyboardRow.entries.firstOrNull { it.id == rowId }?.label.orEmpty()
    }

    fun readTextSnippets(context: Context): List<TextSnippet> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_TEXT_SNIPPETS, null) ?: return emptyList()
        return parseTextSnippetArray(stored)
            ?.let(::normalizeTextSnippets)
            ?: emptyList()
    }

    fun saveTextSnippets(context: Context, snippets: List<TextSnippet>) {
        val encoded = textSnippetsJson(normalizeTextSnippets(snippets)).toString()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TEXT_SNIPPETS, encoded)
            .apply()
    }

    fun registerTextSnippetsChangeListener(
        context: Context,
        onChanged: () -> Unit,
    ): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_TEXT_SNIPPETS) {
                onChanged()
            }
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun unregisterTextSnippetsChangeListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun normalizeTextSnippets(snippets: List<TextSnippet>): List<TextSnippet> {
        val seenTexts = mutableSetOf<String>()
        return snippets
            .mapNotNull { snippet ->
                val text = normalizeTextSnippetText(snippet.text)
                if (text.isBlank() || !seenTexts.add(text)) {
                    null
                } else {
                    TextSnippet(
                        text = text,
                        tags = normalizeTextSnippetTags(snippet.tags),
                    )
                }
            }
            .take(MAX_TEXT_SNIPPETS)
    }

    fun textSnippetFromText(text: String, tags: List<String> = emptyList()): TextSnippet {
        return TextSnippet(
            text = normalizeTextSnippetText(text),
            tags = normalizeTextSnippetTags(tags),
        )
    }

    fun parseTextSnippetTags(value: String): List<String> {
        return normalizeTextSnippetTags(value.split(textSnippetTagSeparatorRegex))
    }

    fun normalizeTextSnippetTags(tags: List<String>): List<String> {
        return tags
            .flatMap { it.split(textSnippetTagSeparatorRegex) }
            .map {
                it.trim()
                    .removePrefix("#")
                    .replace(whitespaceRegex, " ")
                    .take(MAX_TEXT_SNIPPET_TAG_CHARS)
                    .trim()
            }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(MAX_TEXT_SNIPPET_TAGS)
    }

    private fun normalizeTextSnippetText(text: String): String {
        return text.trim().take(MAX_TEXT_SNIPPET_CHARS)
    }

    fun readRecentEmojis(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_RECENT_EMOJIS, null) ?: return emptyList()
        return parseStringArray(stored)
            ?.let(EmojiCatalog::normalizeRecentEmojis)
            ?: emptyList()
    }

    fun saveRecentEmojis(context: Context, emojis: List<String>) {
        val encoded = JSONArray().apply {
            EmojiCatalog.normalizeRecentEmojis(emojis).forEach(::put)
        }.toString()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECENT_EMOJIS, encoded)
            .apply()
    }

    fun readRecentEmojiRows(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_RECENT_EMOJI_ROWS, EmojiCatalog.DEFAULT_RECENT_ROW_COUNT)
            .let(EmojiCatalog::normalizeRecentRowCount)
    }

    fun saveRecentEmojiRows(context: Context, rowCount: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_RECENT_EMOJI_ROWS, EmojiCatalog.normalizeRecentRowCount(rowCount))
            .apply()
    }

    fun readEmojiSkinTone(context: Context): EmojiSkinTone {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_EMOJI_SKIN_TONE, null)
        return EmojiSkinTone.entries.firstOrNull { it.id == stored } ?: EmojiSkinTone.Default
    }

    fun saveEmojiSkinTone(context: Context, skinTone: EmojiSkinTone) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_EMOJI_SKIN_TONE, skinTone.id)
            .apply()
    }

    fun readCustomEmojiTags(context: Context): Map<String, List<String>> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_CUSTOM_EMOJI_TAGS, null) ?: return emptyMap()
        return parseCustomEmojiTags(stored)
            ?.let(::normalizeCustomEmojiTags)
            ?: emptyMap()
    }

    fun saveCustomEmojiTags(context: Context, tagsByEmoji: Map<String, List<String>>) {
        val encoded = JSONArray().apply {
            normalizeCustomEmojiTags(tagsByEmoji).forEach { (emoji, tags) ->
                put(
                    JSONObject()
                        .put("emoji", emoji)
                        .put(
                            "tags",
                            JSONArray().apply {
                                tags.forEach(::put)
                            },
                        ),
                )
            }
        }.toString()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_EMOJI_TAGS, encoded)
            .apply()
    }

    fun normalizeCustomEmojiTags(tagsByEmoji: Map<String, List<String>>): Map<String, List<String>> {
        val normalized = linkedMapOf<String, List<String>>()
        tagsByEmoji.forEach { (rawEmoji, rawTags) ->
            val emoji = normalizeEmojiForCustomTags(rawEmoji) ?: return@forEach
            val tags = normalizeCustomEmojiTagList(rawTags)
            if (tags.isNotEmpty() && emoji !in normalized && normalized.size < MAX_CUSTOM_EMOJI_TAGGED_EMOJIS) {
                normalized[emoji] = tags
            }
        }
        return normalized
    }

    fun normalizeEmojiForCustomTags(emoji: String): String? {
        return EmojiCatalog.entryForEmoji(emoji.trim())?.emoji
    }

    fun normalizeCustomEmojiTagInput(input: String): List<String> {
        return normalizeCustomEmojiTagList(input.split(',', '\n'))
    }

    fun readAgentModeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AGENT_MODE_ENABLED, false)
    }

    fun saveAgentModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AGENT_MODE_ENABLED, enabled)
            .apply()
    }

    fun readAgentProviderType(context: Context): AgentProviderType {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_AGENT_PROVIDER_TYPE, null)
        return AgentProviderType.entries.firstOrNull { it.id == stored } ?: AgentProviderType.OpenAiCompatible
    }

    fun saveAgentProviderType(context: Context, providerType: AgentProviderType) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AGENT_PROVIDER_TYPE, providerType.id)
            .apply()
    }

    fun readAgentApiBaseUrl(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_AGENT_API_BASE_URL, DEFAULT_AGENT_API_BASE_URL)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_AGENT_API_BASE_URL
    }

    fun saveAgentApiBaseUrl(context: Context, baseUrl: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AGENT_API_BASE_URL, baseUrl.trim())
            .apply()
    }

    fun readAgentModel(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_AGENT_MODEL, DEFAULT_AGENT_MODEL)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_AGENT_MODEL
    }

    fun saveAgentModel(context: Context, model: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AGENT_MODEL, model.trim())
            .apply()
    }

    fun readOpenRouterProviderSlug(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_OPENROUTER_PROVIDER_SLUG, "")
            .orEmpty()
            .trim()
    }

    fun saveOpenRouterProviderSlug(context: Context, providerSlug: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_OPENROUTER_PROVIDER_SLUG, providerSlug.trim())
            .apply()
    }

    fun readAgentReasoningMode(context: Context): AgentReasoningMode {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_AGENT_REASONING_MODE, null)
        return AgentReasoningMode.entries.firstOrNull { it.id == stored } ?: AgentReasoningMode.Off
    }

    fun saveAgentReasoningMode(context: Context, reasoningMode: AgentReasoningMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AGENT_REASONING_MODE, reasoningMode.id)
            .apply()
    }

    fun readAgentReasoningTextEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AGENT_REASONING_TEXT_ENABLED, false)
    }

    fun saveAgentReasoningTextEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AGENT_REASONING_TEXT_ENABLED, enabled)
            .apply()
    }

    fun readAgentApiKey(
        context: Context,
        providerType: AgentProviderType = readAgentProviderType(context),
    ): String {
        val prefs = context.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE)
        val ciphertext = prefs.getString(providerSecretKey(providerType, KEY_AGENT_API_KEY_CIPHERTEXT), null)
        val iv = prefs.getString(providerSecretKey(providerType, KEY_AGENT_API_KEY_IV), null)
        if (!ciphertext.isNullOrBlank() && !iv.isNullOrBlank()) {
            return decryptAgentApiKey(ciphertext, iv).getOrElse { "" }
        }

        return migrateLegacyAgentApiKey(prefs, providerType)
    }

    fun saveAgentApiKey(
        context: Context,
        providerType: AgentProviderType = readAgentProviderType(context),
        apiKey: String,
    ) {
        val prefs = context.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE)
        val trimmedApiKey = apiKey.trim()
        val ciphertextKey = providerSecretKey(providerType, KEY_AGENT_API_KEY_CIPHERTEXT)
        val ivKey = providerSecretKey(providerType, KEY_AGENT_API_KEY_IV)
        val lockedKey = providerSecretKey(providerType, KEY_AGENT_API_KEY_LOCKED)
        if (trimmedApiKey.isBlank()) {
            prefs.edit()
                .remove(ciphertextKey)
                .remove(ivKey)
                .remove(lockedKey)
                .remove(KEY_AGENT_API_KEY_LEGACY)
                .remove(KEY_AGENT_API_KEY_CIPHERTEXT)
                .remove(KEY_AGENT_API_KEY_IV)
                .remove(KEY_AGENT_API_KEY_LOCKED)
                .apply()
            return
        }

        encryptAgentApiKey(trimmedApiKey)
            .onSuccess { encryptedValue ->
                prefs.edit()
                    .remove(KEY_AGENT_API_KEY_LEGACY)
                    .remove(KEY_AGENT_API_KEY_CIPHERTEXT)
                    .remove(KEY_AGENT_API_KEY_IV)
                    .remove(KEY_AGENT_API_KEY_LOCKED)
                    .putString(ciphertextKey, encryptedValue.ciphertext)
                    .putString(ivKey, encryptedValue.iv)
                    .apply()
            }
            .onFailure {
                prefs.edit()
                    .remove(KEY_AGENT_API_KEY_LEGACY)
                    .remove(KEY_AGENT_API_KEY_CIPHERTEXT)
                    .remove(KEY_AGENT_API_KEY_IV)
                    .remove(KEY_AGENT_API_KEY_LOCKED)
                    .remove(ciphertextKey)
                    .remove(ivKey)
                    .remove(lockedKey)
                    .apply()
            }
    }

    fun readAgentApiKeyLocked(
        context: Context,
        providerType: AgentProviderType = readAgentProviderType(context),
    ): Boolean {
        val prefs = context.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE)
        val lockedKey = providerSecretKey(providerType, KEY_AGENT_API_KEY_LOCKED)
        if (prefs.contains(lockedKey)) {
            return prefs.getBoolean(lockedKey, false)
        }
        if (prefs.contains(KEY_AGENT_API_KEY_LOCKED) && readLegacyAgentApiKey(prefs).isNotBlank()) {
            val legacyLocked = prefs.getBoolean(KEY_AGENT_API_KEY_LOCKED, false)
            migrateLegacyAgentApiKey(prefs, providerType)
            return legacyLocked
        }
        return false
    }

    fun saveAgentApiKeyLocked(
        context: Context,
        providerType: AgentProviderType = readAgentProviderType(context),
        locked: Boolean,
    ) {
        context.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(providerSecretKey(providerType, KEY_AGENT_API_KEY_LOCKED), locked)
            .remove(KEY_AGENT_API_KEY_LOCKED)
            .apply()
    }

    fun readAgentProviderSettings(context: Context): AgentProviderSettings {
        val providerType = readAgentProviderType(context)
        return AgentProviderSettings(
            providerType = providerType,
            baseUrl = when (providerType) {
                AgentProviderType.OpenRouter -> OPENROUTER_API_BASE_URL
                AgentProviderType.NanbeigeLlamaCpp,
                AgentProviderType.OpenAiCompatible -> readAgentApiBaseUrl(context)
            },
            model = readAgentModel(context),
            apiKey = readAgentApiKey(context, providerType),
            openRouterProviderSlug = readOpenRouterProviderSlug(context),
            reasoningMode = readAgentReasoningMode(context),
            reasoningTextEnabled = readAgentReasoningTextEnabled(context),
        )
    }

    fun readAgentProfiles(context: Context): List<AgentProfile> {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_AGENT_PROFILES, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(stored)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val name = normalizeAgentProfileName(item.optString("name"))
                    if (!isValidAgentProfileId(id) || name.isBlank()) continue
                    val providerType = AgentProviderType.entries.firstOrNull {
                        it.id == item.optString("providerType")
                    } ?: continue
                    val reasoningMode = AgentReasoningMode.entries.firstOrNull {
                        it.id == item.optString("reasoningMode")
                    } ?: AgentReasoningMode.Off
                    add(
                        AgentProfile(
                            id = id,
                            name = name,
                            providerType = providerType,
                            baseUrl = item.optString("baseUrl").trim(),
                            model = item.optString("model").trim(),
                            openRouterProviderSlug = item.optString("openRouterProviderSlug").trim(),
                            reasoningMode = reasoningMode,
                            reasoningTextEnabled = item.optBoolean("reasoningTextEnabled", false),
                            apiKey = readAgentProfileApiKey(context, id),
                            apiKeyLocked = readAgentProfileApiKeyLocked(context, id),
                        ),
                    )
                }
            }
                .distinctBy(AgentProfile::id)
                .take(MAX_AGENT_PROFILES)
        }.getOrDefault(emptyList())
    }

    fun readActiveAgentProfileId(context: Context): String {
        val activeId = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_AGENT_PROFILE_ID, "")
            .orEmpty()
            .trim()
        return activeId.takeIf { id -> readAgentProfiles(context).any { it.id == id } }.orEmpty()
    }

    fun createAgentProfile(context: Context, name: String): AgentProfile? {
        val normalizedName = normalizeAgentProfileName(name)
        val profiles = readAgentProfiles(context)
        if (
            normalizedName.isBlank() ||
            profiles.size >= MAX_AGENT_PROFILES ||
            profiles.any { it.name.equals(normalizedName, ignoreCase = true) }
        ) {
            return null
        }

        val profile = agentProfileFromCurrent(
            context = context,
            id = UUID.randomUUID().toString(),
            name = normalizedName,
        )
        saveAgentProfiles(context, profiles + profile)
        saveActiveAgentProfileId(context, profile.id)
        return profile
    }

    fun updateAgentProfileFromCurrent(context: Context, profileId: String): AgentProfile? {
        val profiles = readAgentProfiles(context)
        val existing = profiles.firstOrNull { it.id == profileId } ?: return null
        val updated = agentProfileFromCurrent(context, id = existing.id, name = existing.name)
        saveAgentProfiles(
            context,
            profiles.map { profile -> if (profile.id == profileId) updated else profile },
        )
        saveActiveAgentProfileId(context, updated.id)
        return updated
    }

    fun activateAgentProfile(context: Context, profileId: String): AgentProfile? {
        val profile = readAgentProfiles(context).firstOrNull { it.id == profileId } ?: return null
        saveAgentProviderType(context, profile.providerType)
        saveAgentApiBaseUrl(context, profile.baseUrl)
        saveAgentModel(context, profile.model)
        saveOpenRouterProviderSlug(context, profile.openRouterProviderSlug)
        saveAgentReasoningMode(context, profile.reasoningMode)
        saveAgentReasoningTextEnabled(context, profile.reasoningTextEnabled)
        saveAgentApiKey(context, profile.providerType, profile.apiKey)
        saveAgentApiKeyLocked(context, profile.providerType, profile.apiKeyLocked)
        saveActiveAgentProfileId(context, profile.id)
        return profile
    }

    fun deleteAgentProfile(context: Context, profileId: String) {
        val profiles = readAgentProfiles(context)
        if (profiles.none { it.id == profileId }) return
        val wasActive = readActiveAgentProfileId(context) == profileId
        saveAgentProfiles(context, profiles.filterNot { it.id == profileId })
        clearAgentProfileSecret(context, profileId)
        if (wasActive) {
            saveActiveAgentProfileId(context, "")
        }
    }

    fun normalizeAgentProfileName(name: String): String {
        return name.trim()
            .replace(Regex("\\s+"), " ")
            .take(MAX_AGENT_PROFILE_NAME_CHARS)
    }

    private fun agentProfileFromCurrent(
        context: Context,
        id: String,
        name: String,
    ): AgentProfile {
        val settings = readAgentProviderSettings(context)
        return AgentProfile(
            id = id,
            name = name,
            providerType = settings.providerType,
            baseUrl = settings.baseUrl,
            model = settings.model,
            openRouterProviderSlug = settings.openRouterProviderSlug,
            reasoningMode = settings.reasoningMode,
            reasoningTextEnabled = settings.reasoningTextEnabled,
            apiKey = settings.apiKey,
            apiKeyLocked = readAgentApiKeyLocked(context, settings.providerType),
        )
    }

    private fun saveAgentProfiles(context: Context, profiles: List<AgentProfile>) {
        val normalized = profiles
            .filter { isValidAgentProfileId(it.id) && normalizeAgentProfileName(it.name).isNotBlank() }
            .distinctBy(AgentProfile::id)
            .take(MAX_AGENT_PROFILES)
            .map { profile -> profile.copy(name = normalizeAgentProfileName(profile.name)) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AGENT_PROFILES, agentProfilesJson(normalized).toString())
            .apply()
        normalized.forEach { profile ->
            saveAgentProfileApiKey(context, profile.id, profile.apiKey)
            saveAgentProfileApiKeyLocked(context, profile.id, profile.apiKeyLocked)
        }
    }

    private fun replaceAgentProfiles(
        context: Context,
        profiles: List<AgentProfile>,
        activeProfileId: String,
    ) {
        readAgentProfiles(context).forEach { profile ->
            clearAgentProfileSecret(context, profile.id)
        }
        saveAgentProfiles(context, profiles)
        saveActiveAgentProfileId(
            context,
            activeProfileId.takeIf { id -> profiles.any { it.id == id } }.orEmpty(),
        )
    }

    private fun saveActiveAgentProfileId(context: Context, profileId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_AGENT_PROFILE_ID, profileId)
            .apply()
    }

    fun readGifApiBaseUrl(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GIF_API_BASE_URL, DEFAULT_GIF_API_BASE_URL)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_GIF_API_BASE_URL
    }

    fun saveGifApiBaseUrl(context: Context, baseUrl: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GIF_API_BASE_URL, baseUrl.trim())
            .apply()
    }

    fun readGifAppKey(context: Context): String {
        val prefs = context.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE)
        val ciphertext = prefs.getString(KEY_GIF_APP_KEY_CIPHERTEXT, null)
        val iv = prefs.getString(KEY_GIF_APP_KEY_IV, null)
        if (!ciphertext.isNullOrBlank() && !iv.isNullOrBlank()) {
            return decryptAgentApiKey(ciphertext, iv).getOrElse { "" }
        }
        return ""
    }

    fun saveGifAppKey(context: Context, appKey: String) {
        val prefs = context.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE)
        val trimmedAppKey = appKey.trim()
        if (trimmedAppKey.isBlank()) {
            prefs.edit()
                .remove(KEY_GIF_APP_KEY_CIPHERTEXT)
                .remove(KEY_GIF_APP_KEY_IV)
                .remove(KEY_GIF_APP_KEY_LOCKED)
                .apply()
            return
        }

        encryptAgentApiKey(trimmedAppKey)
            .onSuccess { encryptedValue ->
                prefs.edit()
                    .putString(KEY_GIF_APP_KEY_CIPHERTEXT, encryptedValue.ciphertext)
                    .putString(KEY_GIF_APP_KEY_IV, encryptedValue.iv)
                    .apply()
            }
            .onFailure {
                prefs.edit()
                    .remove(KEY_GIF_APP_KEY_CIPHERTEXT)
                    .remove(KEY_GIF_APP_KEY_IV)
                    .remove(KEY_GIF_APP_KEY_LOCKED)
                    .apply()
            }
    }

    fun readGifAppKeyLocked(context: Context): Boolean {
        return context.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_GIF_APP_KEY_LOCKED, false)
    }

    fun saveGifAppKeyLocked(context: Context, locked: Boolean) {
        context.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GIF_APP_KEY_LOCKED, locked)
            .apply()
    }

    fun readGifProviderSettings(context: Context): GifProviderSettings {
        return GifProviderSettings(
            baseUrl = readGifApiBaseUrl(context),
            appKey = readGifAppKey(context),
        )
    }

    /**
     * Creates the backup payload that is encrypted before it leaves the device.
     * Never write this JSON directly to user-selected storage.
     */
    fun createBackupPayloadJson(context: Context): String {
        val backup = JSONObject()
            .put("format", BACKUP_FORMAT)
            .put("schemaVersion", BACKUP_SCHEMA_VERSION)
            .put("createdAtEpochMillis", System.currentTimeMillis())
            .put("appId", context.packageName)
            .put(
                "settings",
                JSONObject()
                    .put(
                        "keyboard",
                        JSONObject()
                            .put("heightScale", readHeightScale(context))
                            .put("bottomPaddingDp", readBottomPaddingDp(context))
                            .put("functionKeyBackgroundsEnabled", readFunctionKeyBackgroundsEnabled(context))
                            .put("shortcutRowAKeys", stringArrayJson(readUpperRowKeyIds(context)))
                            .put("shortcutRowBKeys", stringArrayJson(readSecondRowButtonIds(context)))
                            .put("shortcutRowOrder", stringArrayJson(readKeyboardRowOrder(context))),
                    )
                    .put("snippets", textSnippetsJson(readTextSnippets(context)))
                    .put(
                        "emoji",
                        JSONObject()
                            .put("skinTone", readEmojiSkinTone(context).id)
                            .put("recentRows", readRecentEmojiRows(context))
                            .put("recentEmojis", stringArrayJson(readRecentEmojis(context)))
                            .put("customTags", customEmojiTagsJson(readCustomEmojiTags(context))),
                    )
                    .put(
                        "gif",
                        JSONObject()
                            .put("apiBaseUrl", readGifApiBaseUrl(context)),
                    )
                    .put(
                        "rewrite",
                        JSONObject()
                            .put("enabled", readAgentModeEnabled(context))
                            .put("providerType", readAgentProviderType(context).id)
                            .put("apiBaseUrl", readAgentApiBaseUrl(context))
                            .put("model", readAgentModel(context))
                            .put("openRouterProviderSlug", readOpenRouterProviderSlug(context))
                            .put("reasoningMode", readAgentReasoningMode(context).id)
                            .put("reasoningTextEnabled", readAgentReasoningTextEnabled(context))
                            .put("profiles", agentProfilesJson(readAgentProfiles(context)))
                            .put("activeProfileId", readActiveAgentProfileId(context)),
                    )
                    .put("clipboardHistory", clipboardHistoryJson(ClipboardHistoryStore.read(context))),
            )
            .put(
                "secrets",
                JSONObject()
                    .put(
                        "agentApiKeys",
                        JSONObject().apply {
                            AgentProviderType.entries.forEach { providerType ->
                                put(
                                    providerType.id,
                                    JSONObject()
                                        .put("value", readAgentApiKey(context, providerType))
                                        .put("locked", readAgentApiKeyLocked(context, providerType)),
                                )
                            }
                        },
                    )
                    .put("agentProfiles", agentProfileSecretsJson(readAgentProfiles(context)))
                    .put("gifAppKey", readGifAppKey(context))
                    .put("gifAppKeyLocked", readGifAppKeyLocked(context)),
            )

        return backup.toString(BACKUP_JSON_INDENT)
    }

    fun restoreBackupPayloadJson(context: Context, backupJson: String): BackupRestoreResult {
        val backup = JSONObject(backupJson)
        if (backup.optString("format") != BACKUP_FORMAT) {
            throw JSONException("Not a ZnKeyboard settings backup.")
        }

        val schemaVersion = backup.optInt("schemaVersion", -1)
        if (schemaVersion !in 1..BACKUP_SCHEMA_VERSION) {
            throw JSONException("Unsupported ZnKeyboard backup version.")
        }

        val settings = backup.optJSONObject("settings")
            ?: throw JSONException("Backup is missing settings.")
        val keyboard = settings.optJSONObject("keyboard") ?: JSONObject()
        val emoji = settings.optJSONObject("emoji") ?: JSONObject()
        val gif = settings.optJSONObject("gif") ?: JSONObject()
        val rewrite = settings.optJSONObject("rewrite") ?: JSONObject()
        val secrets = backup.optJSONObject("secrets")

        val heightScale = keyboard.optFiniteFloat("heightScale", readHeightScale(context))
            .coerceIn(MIN_HEIGHT_SCALE, MAX_HEIGHT_SCALE)
        val bottomPaddingDp = normalizeBottomPaddingDp(
            keyboard.optInt("bottomPaddingDp", readBottomPaddingDp(context)),
        )
        val functionKeyBackgroundsEnabled = keyboard.optBoolean(
            "functionKeyBackgroundsEnabled",
            readFunctionKeyBackgroundsEnabled(context),
        )
        val upperRowKeys = keyboard.optStringList("shortcutRowAKeys")
            ?.let(::normalizeUpperRowKeyIds)
            ?: readUpperRowKeyIds(context)
        val secondRowButtons = keyboard.optStringList("shortcutRowBKeys")
            ?.let(::normalizeSecondRowButtonIds)
            ?: readSecondRowButtonIds(context)
        val rowOrder = keyboard.optStringList("shortcutRowOrder")
            ?.let(::normalizeKeyboardRowOrder)
            ?: readKeyboardRowOrder(context)

        val snippets = settings.optTextSnippetList("snippets")
            ?.let(::normalizeTextSnippets)
            ?: readTextSnippets(context)
        val skinTone = emoji.optById(
            key = "skinTone",
            entries = EmojiSkinTone.entries,
            defaultValue = readEmojiSkinTone(context),
            idFor = EmojiSkinTone::id,
        )
        val recentRows = emoji.optInt("recentRows", readRecentEmojiRows(context))
            .let(EmojiCatalog::normalizeRecentRowCount)
        val recentEmojis = emoji.optStringList("recentEmojis")
            ?.let(EmojiCatalog::normalizeRecentEmojis)
            ?: readRecentEmojis(context)
        val customEmojiTags = emoji.optCustomEmojiTags("customTags")
            ?.let(::normalizeCustomEmojiTags)
            ?: readCustomEmojiTags(context)
        val clipboardHistory = settings.optClipboardHistory("clipboardHistory")
            ?.let(ClipboardHistoryStore::normalizeEntries)

        val agentSecrets = secrets?.optJSONObject("agentApiKeys")
        val restoredAgentSecrets = AgentProviderType.entries.mapNotNull { providerType ->
            val secret = agentSecrets?.optJSONObject(providerType.id) ?: return@mapNotNull null
            RestoredSecret(
                providerType = providerType,
                value = secret.optString("value"),
                locked = secret.optBoolean(
                    "locked",
                    readAgentApiKeyLocked(context, providerType),
                ),
            )
        }
        val restoredAgentProfiles = rewrite.optJSONArray("profiles")?.let { profiles ->
            val profileSecrets = secrets?.optJSONObject("agentProfiles")
            buildList {
                for (index in 0 until profiles.length()) {
                    val item = profiles.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val name = normalizeAgentProfileName(item.optString("name"))
                    if (!isValidAgentProfileId(id) || name.isBlank()) continue
                    val profileProviderType = AgentProviderType.entries.firstOrNull {
                        it.id == item.optString("providerType")
                    } ?: continue
                    val profileReasoningMode = AgentReasoningMode.entries.firstOrNull {
                        it.id == item.optString("reasoningMode")
                    } ?: AgentReasoningMode.Off
                    val profileSecret = profileSecrets?.optJSONObject(id)
                    add(
                        AgentProfile(
                            id = id,
                            name = name,
                            providerType = profileProviderType,
                            baseUrl = item.optString("baseUrl").trim(),
                            model = item.optString("model").trim(),
                            openRouterProviderSlug = item.optString("openRouterProviderSlug").trim(),
                            reasoningMode = profileReasoningMode,
                            reasoningTextEnabled = item.optBoolean("reasoningTextEnabled", false),
                            apiKey = profileSecret?.optString("value").orEmpty(),
                            apiKeyLocked = profileSecret?.optBoolean("locked", false) ?: false,
                        ),
                    )
                }
            }
                .distinctBy(AgentProfile::id)
                .take(MAX_AGENT_PROFILES)
        }
        val restoredActiveProfileId = rewrite.optString("activeProfileId").trim()
        val restoredGifAppKey = secrets
            ?.takeIf { it.has("gifAppKey") }
            ?.optString("gifAppKey")
        val restoredGifAppKeyLocked = secrets
            ?.takeIf { it.has("gifAppKeyLocked") }
            ?.optBoolean("gifAppKeyLocked", readGifAppKeyLocked(context))

        val providerType = rewrite.optById(
            key = "providerType",
            entries = AgentProviderType.entries,
            defaultValue = readAgentProviderType(context),
            idFor = AgentProviderType::id,
        )
        val reasoningMode = rewrite.optById(
            key = "reasoningMode",
            entries = AgentReasoningMode.entries,
            defaultValue = readAgentReasoningMode(context),
            idFor = AgentReasoningMode::id,
        )

        saveHeightScale(context, heightScale)
        saveBottomPaddingDp(context, bottomPaddingDp)
        saveFunctionKeyBackgroundsEnabled(context, functionKeyBackgroundsEnabled)
        saveUpperRowKeyIds(context, upperRowKeys)
        saveSecondRowButtonIds(context, secondRowButtons)
        saveKeyboardRowOrder(context, rowOrder)
        saveTextSnippets(context, snippets)
        saveEmojiSkinTone(context, skinTone)
        saveRecentEmojiRows(context, recentRows)
        saveRecentEmojis(context, recentEmojis)
        saveCustomEmojiTags(context, customEmojiTags)
        saveGifApiBaseUrl(context, gif.optString("apiBaseUrl", readGifApiBaseUrl(context)))
        saveAgentModeEnabled(context, rewrite.optBoolean("enabled", readAgentModeEnabled(context)))
        saveAgentProviderType(context, providerType)
        saveAgentApiBaseUrl(context, rewrite.optString("apiBaseUrl", readAgentApiBaseUrl(context)))
        saveAgentModel(context, rewrite.optString("model", readAgentModel(context)))
        saveOpenRouterProviderSlug(
            context,
            rewrite.optString("openRouterProviderSlug", readOpenRouterProviderSlug(context)),
        )
        saveAgentReasoningMode(context, reasoningMode)
        saveAgentReasoningTextEnabled(
            context,
            rewrite.optBoolean("reasoningTextEnabled", readAgentReasoningTextEnabled(context)),
        )
        clipboardHistory?.let { ClipboardHistoryStore.replace(context, it) }
        restoredAgentSecrets.forEach { secret ->
            saveAgentApiKey(context, secret.providerType, secret.value)
            saveAgentApiKeyLocked(context, secret.providerType, secret.locked)
        }
        restoredAgentProfiles?.let { profiles ->
            replaceAgentProfiles(context, profiles, restoredActiveProfileId)
        }
        restoredGifAppKey?.let { appKey ->
            saveGifAppKey(context, appKey)
            restoredGifAppKeyLocked?.let { locked -> saveGifAppKeyLocked(context, locked) }
        }

        return BackupRestoreResult(
            schemaVersion = schemaVersion,
            snippetCount = snippets.size,
            customEmojiTagCount = customEmojiTags.size,
            recentEmojiCount = recentEmojis.size,
            clipboardEntryCount = clipboardHistory?.size ?: ClipboardHistoryStore.read(context).size,
        )
    }

    private fun parseUpperRowKeyIds(stored: String): List<String>? {
        return parseStringArray(stored)?.let(::normalizeUpperRowKeyIds)
    }

    private fun parseAgentRowKeyIds(stored: String): List<String>? {
        return parseStringArray(stored)?.let(::normalizeLegacyAgentRowKeyIds)
    }

    private fun normalizeLegacyAgentRowKeyIds(keyIds: List<String>): List<String> {
        return keyIds
            .filter { it in upperRowKeyOptionIds }
            .take(LEGACY_MAX_AGENT_ROW_KEYS)
    }

    private fun parseSecondRowButtonIds(stored: String): List<String>? {
        return parseStringArray(stored)?.let(::normalizeSecondRowButtonIds)
    }

    private fun parseKeyboardRowOrder(stored: String): List<String>? {
        return parseStringArray(stored)?.let(::normalizeKeyboardRowOrder)
    }

    private fun parseStringArray(stored: String): List<String>? {
        return try {
            val array = JSONArray(stored)
            buildList {
                for (index in 0 until array.length()) {
                    add(array.optString(index))
                }
            }
        } catch (_: JSONException) {
            null
        }
    }

    private fun parseTextSnippetArray(stored: String): List<TextSnippet>? {
        return try {
            JSONArray(stored).toTextSnippetList()
        } catch (_: JSONException) {
            null
        }
    }

    private fun parseCustomEmojiTags(stored: String): Map<String, List<String>>? {
        return try {
            val array = JSONArray(stored)
            buildMap {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val emoji = item.optString("emoji")
                    val tags = item.optJSONArray("tags") ?: continue
                    put(
                        emoji,
                        buildList {
                            for (tagIndex in 0 until tags.length()) {
                                add(tags.optString(tagIndex))
                            }
                        },
                    )
                }
            }
        } catch (_: JSONException) {
            null
        }
    }

    private fun normalizeCustomEmojiTagList(tags: List<String>): List<String> {
        return tags
            .map {
                it.trim()
                    .replace(whitespaceRegex, " ")
                    .take(MAX_CUSTOM_EMOJI_TAG_CHARS)
                    .trim()
            }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(MAX_CUSTOM_EMOJI_TAGS_PER_EMOJI)
    }

    private fun encryptAgentApiKey(apiKey: String): Result<EncryptedValue> {
        return runCatching {
            val cipher = Cipher.getInstance(KEYSTORE_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAgentSecretKey())
            EncryptedValue(
                ciphertext = Base64.encodeToString(cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP),
                iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            )
        }
    }

    private fun decryptAgentApiKey(ciphertext: String, iv: String): Result<String> {
        return runCatching {
            val cipher = Cipher.getInstance(KEYSTORE_TRANSFORMATION)
            val spec = GCMParameterSpec(
                GCM_TAG_LENGTH_BITS,
                Base64.decode(iv, Base64.NO_WRAP),
            )
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateAgentSecretKey(), spec)
            val plaintext = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
            plaintext.toString(Charsets.UTF_8)
        }
    }

    private fun migrateLegacyAgentApiKey(
        prefs: SharedPreferences,
        providerType: AgentProviderType,
    ): String {
        val legacyApiKey = readLegacyAgentApiKey(prefs)
        if (legacyApiKey.isBlank()) return ""

        val locked = prefs.getBoolean(KEY_AGENT_API_KEY_LOCKED, false)
        val ciphertextKey = providerSecretKey(providerType, KEY_AGENT_API_KEY_CIPHERTEXT)
        val ivKey = providerSecretKey(providerType, KEY_AGENT_API_KEY_IV)
        val lockedKey = providerSecretKey(providerType, KEY_AGENT_API_KEY_LOCKED)
        encryptAgentApiKey(legacyApiKey)
            .onSuccess { encryptedValue ->
                prefs.edit()
                    .remove(KEY_AGENT_API_KEY_LEGACY)
                    .remove(KEY_AGENT_API_KEY_CIPHERTEXT)
                    .remove(KEY_AGENT_API_KEY_IV)
                    .remove(KEY_AGENT_API_KEY_LOCKED)
                    .putString(ciphertextKey, encryptedValue.ciphertext)
                    .putString(ivKey, encryptedValue.iv)
                    .putBoolean(lockedKey, locked)
                    .apply()
            }
            .onFailure {
                prefs.edit()
                    .remove(KEY_AGENT_API_KEY_LEGACY)
                    .remove(KEY_AGENT_API_KEY_CIPHERTEXT)
                    .remove(KEY_AGENT_API_KEY_IV)
                    .remove(KEY_AGENT_API_KEY_LOCKED)
                    .remove(ciphertextKey)
                    .remove(ivKey)
                    .remove(lockedKey)
                    .apply()
            }
        return legacyApiKey
    }

    private fun readLegacyAgentApiKey(prefs: SharedPreferences): String {
        val ciphertext = prefs.getString(KEY_AGENT_API_KEY_CIPHERTEXT, null)
        val iv = prefs.getString(KEY_AGENT_API_KEY_IV, null)
        if (!ciphertext.isNullOrBlank() && !iv.isNullOrBlank()) {
            return decryptAgentApiKey(ciphertext, iv).getOrElse { "" }
        }
        return prefs.getString(KEY_AGENT_API_KEY_LEGACY, "").orEmpty().trim()
    }

    private fun providerSecretKey(providerType: AgentProviderType, key: String): String {
        return "${providerType.id}_$key"
    }

    private fun profileSecretKey(profileId: String, key: String): String {
        return "profile_${profileId}_$key"
    }

    private fun readAgentProfileApiKey(context: Context, profileId: String): String {
        val prefs = context.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE)
        val ciphertext = prefs.getString(profileSecretKey(profileId, KEY_AGENT_API_KEY_CIPHERTEXT), null)
        val iv = prefs.getString(profileSecretKey(profileId, KEY_AGENT_API_KEY_IV), null)
        if (ciphertext.isNullOrBlank() || iv.isNullOrBlank()) return ""
        return decryptAgentApiKey(ciphertext, iv).getOrElse { "" }
    }

    private fun saveAgentProfileApiKey(context: Context, profileId: String, apiKey: String) {
        val prefs = context.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE)
        val ciphertextKey = profileSecretKey(profileId, KEY_AGENT_API_KEY_CIPHERTEXT)
        val ivKey = profileSecretKey(profileId, KEY_AGENT_API_KEY_IV)
        val trimmedApiKey = apiKey.trim()
        if (trimmedApiKey.isBlank()) {
            prefs.edit()
                .remove(ciphertextKey)
                .remove(ivKey)
                .apply()
            return
        }
        encryptAgentApiKey(trimmedApiKey)
            .onSuccess { encryptedValue ->
                prefs.edit()
                    .putString(ciphertextKey, encryptedValue.ciphertext)
                    .putString(ivKey, encryptedValue.iv)
                    .apply()
            }
            .onFailure {
                prefs.edit()
                    .remove(ciphertextKey)
                    .remove(ivKey)
                    .apply()
            }
    }

    private fun readAgentProfileApiKeyLocked(context: Context, profileId: String): Boolean {
        return context.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(profileSecretKey(profileId, KEY_AGENT_API_KEY_LOCKED), false)
    }

    private fun saveAgentProfileApiKeyLocked(context: Context, profileId: String, locked: Boolean) {
        context.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(profileSecretKey(profileId, KEY_AGENT_API_KEY_LOCKED), locked)
            .apply()
    }

    private fun clearAgentProfileSecret(context: Context, profileId: String) {
        context.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(profileSecretKey(profileId, KEY_AGENT_API_KEY_CIPHERTEXT))
            .remove(profileSecretKey(profileId, KEY_AGENT_API_KEY_IV))
            .remove(profileSecretKey(profileId, KEY_AGENT_API_KEY_LOCKED))
            .apply()
    }

    private fun isValidAgentProfileId(profileId: String): Boolean {
        return profileId.length in 1..64 && profileId.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    private fun agentProfilesJson(profiles: List<AgentProfile>): JSONArray {
        return JSONArray().apply {
            profiles.forEach { profile ->
                put(
                    JSONObject()
                        .put("id", profile.id)
                        .put("name", profile.name)
                        .put("providerType", profile.providerType.id)
                        .put("baseUrl", profile.baseUrl)
                        .put("model", profile.model)
                        .put("openRouterProviderSlug", profile.openRouterProviderSlug)
                        .put("reasoningMode", profile.reasoningMode.id)
                        .put("reasoningTextEnabled", profile.reasoningTextEnabled),
                )
            }
        }
    }

    private fun agentProfileSecretsJson(profiles: List<AgentProfile>): JSONObject {
        return JSONObject().apply {
            profiles.forEach { profile ->
                put(
                    profile.id,
                    JSONObject()
                        .put("value", profile.apiKey)
                        .put("locked", profile.apiKeyLocked),
                )
            }
        }
    }

    private fun stringArrayJson(values: List<String>): JSONArray {
        return JSONArray().apply {
            values.forEach(::put)
        }
    }

    private fun textSnippetsJson(snippets: List<TextSnippet>): JSONArray {
        return JSONArray().apply {
            normalizeTextSnippets(snippets).forEach { snippet ->
                put(
                    JSONObject()
                        .put("text", snippet.text)
                        .put("tags", stringArrayJson(snippet.tags)),
                )
            }
        }
    }

    private fun customEmojiTagsJson(tagsByEmoji: Map<String, List<String>>): JSONArray {
        return JSONArray().apply {
            tagsByEmoji.forEach { (emoji, tags) ->
                put(
                    JSONObject()
                        .put("emoji", emoji)
                        .put("tags", stringArrayJson(tags)),
                )
            }
        }
    }

    private fun clipboardHistoryJson(entries: List<ClipboardHistoryStore.Entry>): JSONArray {
        return JSONArray().apply {
            ClipboardHistoryStore.normalizeEntries(entries).forEach { entry ->
                put(
                    JSONObject()
                        .put("text", entry.text)
                        .put("timestampMillis", entry.timestampMillis)
                        .apply {
                            entry.sourcePackageName?.let { put("sourcePackageName", it) }
                            entry.sourceAppLabel?.let { put("sourceAppLabel", it) }
                        },
                )
            }
        }
    }

    private fun JSONObject.optTextSnippetList(key: String): List<TextSnippet>? {
        val array = optJSONArray(key) ?: return null
        return array.toTextSnippetList()
    }

    private fun JSONArray.toTextSnippetList(): List<TextSnippet> {
        return buildList {
            for (index in 0 until length()) {
                val item = opt(index)
                when (item) {
                    is JSONObject -> {
                        val tags = item.optJSONArray("tags")?.toStringList().orEmpty()
                        add(TextSnippet(text = item.optString("text"), tags = tags))
                    }
                    is String -> add(TextSnippet(text = item, tags = emptyList()))
                    else -> add(TextSnippet(text = optString(index), tags = emptyList()))
                }
            }
        }
    }

    private fun JSONArray.toStringList(): List<String> {
        return buildList {
            for (index in 0 until length()) {
                add(optString(index))
            }
        }
    }

    private fun JSONObject.optStringList(key: String): List<String>? {
        val array = optJSONArray(key) ?: return null
        return buildList {
            for (index in 0 until array.length()) {
                add(array.optString(index))
            }
        }
    }

    private fun JSONObject.optCustomEmojiTags(key: String): Map<String, List<String>>? {
        val array = optJSONArray(key) ?: return null
        return linkedMapOf<String, List<String>>().apply {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val tags = item.optJSONArray("tags") ?: continue
                put(
                    item.optString("emoji"),
                    buildList {
                        for (tagIndex in 0 until tags.length()) {
                            add(tags.optString(tagIndex))
                        }
                    },
                )
            }
        }
    }

    private fun JSONObject.optClipboardHistory(key: String): List<ClipboardHistoryStore.Entry>? {
        val array = optJSONArray(key) ?: return null
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    ClipboardHistoryStore.Entry(
                        text = item.optString("text"),
                        timestampMillis = item.optLong("timestampMillis", 0L),
                        sourcePackageName = item.optString("sourcePackageName").takeIf { it.isNotBlank() },
                        sourceAppLabel = item.optString("sourceAppLabel").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    private fun JSONObject.optFiniteFloat(key: String, defaultValue: Float): Float {
        if (!has(key)) return defaultValue
        val value = optDouble(key, defaultValue.toDouble()).toFloat()
        return if (value.isFinite()) value else defaultValue
    }

    private fun <T> JSONObject.optById(
        key: String,
        entries: List<T>,
        defaultValue: T,
        idFor: (T) -> String,
    ): T {
        val id = optString(key, "")
        return entries.firstOrNull { idFor(it) == id } ?: defaultValue
    }

    private fun getOrCreateAgentSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEYSTORE_AGENT_API_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val keySpec = KeyGenParameterSpec.Builder(
            KEYSTORE_AGENT_API_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    data class UpperRowKeyOption(
        val id: String,
        val label: String,
    )

    data class TextSnippet(
        val text: String,
        val tags: List<String> = emptyList(),
    )

    enum class KeyboardRow(
        val id: String,
        val label: String,
    ) {
        Upper("upper", "Shortcut row A"),
        Second("second", "Shortcut row B"),
    }

    enum class SavedTextPanelTab(
        val id: String,
        val label: String,
        val searchLabel: String,
    ) {
        Clipboard("clipboard", "Clipboard", "Search clipboard"),
        Snippets("snippets", "Snippets", "Search snippets"),
    }

    enum class AgentProviderType(
        val id: String,
        val label: String,
    ) {
        OpenRouter("openrouter", "OpenRouter"),
        NanbeigeLlamaCpp("nanbeige_llama_cpp", "Nanbeige llama.cpp"),
        OpenAiCompatible("openai_compatible", "OpenAI compatible"),
    }

    enum class AgentReasoningMode(
        val id: String,
        val label: String,
        val description: String,
        val effort: String?,
    ) {
        Off(
            id = "off",
            label = "Off",
            description = "Do not request reasoning. Safest for fast rewrites.",
            effort = null,
        ),
        Auto(
            id = "auto",
            label = "Provider default",
            description = "Let the provider decide. Useful when the selected model requires reasoning.",
            effort = null,
        ),
        Minimal(
            id = "minimal",
            label = "Minimal",
            description = "Small reasoning budget.",
            effort = "minimal",
        ),
        Low(
            id = "low",
            label = "Low",
            description = "Light reasoning with modest latency.",
            effort = "low",
        ),
        Medium(
            id = "medium",
            label = "Medium",
            description = "Balanced reasoning for harder rewrites.",
            effort = "medium",
        ),
        High(
            id = "high",
            label = "High",
            description = "More reasoning, slower and more token-heavy.",
            effort = "high",
        ),
        XHigh(
            id = "xhigh",
            label = "X-high",
            description = "Maximum reasoning for providers/models that support it.",
            effort = "xhigh",
        ),
    }

    data class AgentProviderSettings(
        val providerType: AgentProviderType,
        val baseUrl: String,
        val model: String,
        val apiKey: String,
        val openRouterProviderSlug: String,
        val reasoningMode: AgentReasoningMode,
        val reasoningTextEnabled: Boolean,
    ) {
        val isConfigured: Boolean
            get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
    }

    data class AgentProfile(
        val id: String,
        val name: String,
        val providerType: AgentProviderType,
        val baseUrl: String,
        val model: String,
        val openRouterProviderSlug: String,
        val reasoningMode: AgentReasoningMode,
        val reasoningTextEnabled: Boolean,
        val apiKey: String,
        val apiKeyLocked: Boolean,
    )

    data class GifProviderSettings(
        val baseUrl: String,
        val appKey: String,
    ) {
        val isConfigured: Boolean
            get() = baseUrl.isNotBlank() && appKey.isNotBlank()
    }

    private data class EncryptedValue(
        val ciphertext: String,
        val iv: String,
    )

    private data class RestoredSecret(
        val providerType: AgentProviderType,
        val value: String,
        val locked: Boolean,
    )

    data class BackupRestoreResult(
        val schemaVersion: Int,
        val snippetCount: Int,
        val customEmojiTagCount: Int,
        val recentEmojiCount: Int,
        val clipboardEntryCount: Int,
    )

}
