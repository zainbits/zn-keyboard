package dev.zain.znkeyboard

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object KeyboardSettings {
    const val MIN_HEIGHT_SCALE = 0.82f
    const val MAX_HEIGHT_SCALE = 1.24f
    const val DEFAULT_HEIGHT_SCALE = 1.0f
    const val DEFAULT_AGENT_API_BASE_URL = "https://api.openai.com/v1"
    const val OPENROUTER_API_BASE_URL = "https://openrouter.ai/api/v1"
    const val DEFAULT_AGENT_MODEL = "gpt-4o-mini"
    const val MAX_UPPER_ROW_KEYS = 9
    private const val LEGACY_MAX_AGENT_ROW_KEYS = 5
    const val MAX_SECOND_ROW_BUTTONS = MAX_UPPER_ROW_KEYS
    const val MAX_TEXT_SNIPPETS = 60
    const val MAX_TEXT_SNIPPET_CHARS = 2_000
    const val MAX_CUSTOM_EMOJI_TAGGED_EMOJIS = 250
    const val MAX_CUSTOM_EMOJI_TAGS_PER_EMOJI = 12
    const val MAX_CUSTOM_EMOJI_TAG_CHARS = 32

    private const val BACKUP_FORMAT = "dev.zain.znkeyboard.settings-backup"
    private const val BACKUP_SCHEMA_VERSION = 1
    private const val BACKUP_JSON_INDENT = 2
    private const val PREFS_NAME = "keyboard_settings"
    private const val SECRET_PREFS_NAME = "keyboard_agent_secrets"
    private const val KEY_HEIGHT_SCALE = "height_scale"
    private const val KEY_UPPER_ROW_KEYS = "upper_row_keys"
    private const val KEY_AGENT_ROW_KEYS = "agent_row_keys"
    private const val KEY_SECOND_ROW_BUTTONS = "second_row_buttons"
    private const val KEY_KEYBOARD_ROW_ORDER = "keyboard_row_order"
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
    private const val KEY_AGENT_API_KEY_LEGACY = "agent_api_key"
    private const val KEY_AGENT_API_KEY_CIPHERTEXT = "agent_api_key_ciphertext"
    private const val KEY_AGENT_API_KEY_IV = "agent_api_key_iv"
    private const val KEY_AGENT_API_KEY_LOCKED = "agent_api_key_locked"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEYSTORE_AGENT_API_KEY_ALIAS = "znkeyboard_agent_api_key"
    private const val KEYSTORE_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128

    val DEFAULT_UPPER_ROW_KEY_IDS = listOf("ctrl", "tab", "pipe", "slash", "left", "up", "down", "right", "esc")
    val DEFAULT_SECOND_ROW_BUTTON_IDS = listOf("rewrite", "left", "right", "backspace", "history")
    val DEFAULT_KEYBOARD_ROW_ORDER = listOf(
        KeyboardRow.Second.id,
        KeyboardRow.Upper.id,
    )

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
    ) + FUNCTION_KEY_OPTIONS + listOf(
        UpperRowKeyOption("history", "History"),
    )
    val UPPER_ROW_KEY_OPTIONS = SHORTCUT_ROW_KEY_OPTIONS
    val SECOND_ROW_BUTTON_OPTIONS = SHORTCUT_ROW_KEY_OPTIONS
    private val shortcutRowKeyOptionIds = SHORTCUT_ROW_KEY_OPTIONS.mapTo(mutableSetOf()) { it.id }
    private val upperRowKeyOptionIds = shortcutRowKeyOptionIds
    private val secondRowButtonOptionIds = shortcutRowKeyOptionIds
    private val keyboardRowIds = KeyboardRow.entries.mapTo(mutableSetOf()) { it.id }
    private val whitespaceRegex = Regex("\\s+")

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
        return normalizeSecondRowButtonIds(listOf("rewrite") + legacyAgentRow + "history")
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

    fun readTextSnippets(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_TEXT_SNIPPETS, null) ?: return emptyList()
        return parseStringArray(stored)
            ?.let(::normalizeTextSnippets)
            ?: emptyList()
    }

    fun saveTextSnippets(context: Context, snippets: List<String>) {
        val encoded = JSONArray().apply {
            normalizeTextSnippets(snippets).forEach(::put)
        }.toString()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TEXT_SNIPPETS, encoded)
            .apply()
    }

    fun normalizeTextSnippets(snippets: List<String>): List<String> {
        return snippets
            .map { it.trim().take(MAX_TEXT_SNIPPET_CHARS) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_TEXT_SNIPPETS)
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
                AgentProviderType.OpenAiCompatible -> readAgentApiBaseUrl(context)
            },
            model = readAgentModel(context),
            apiKey = readAgentApiKey(context, providerType),
            openRouterProviderSlug = readOpenRouterProviderSlug(context),
            reasoningMode = readAgentReasoningMode(context),
            reasoningTextEnabled = readAgentReasoningTextEnabled(context),
        )
    }

    fun createBackupJson(context: Context): String {
        val backup = JSONObject()
            .put("format", BACKUP_FORMAT)
            .put("schemaVersion", BACKUP_SCHEMA_VERSION)
            .put("createdAtEpochMillis", System.currentTimeMillis())
            .put("appId", context.packageName)
            .put(
                "excluded",
                JSONArray().put("agentApiKey"),
            )
            .put(
                "settings",
                JSONObject()
                    .put(
                        "keyboard",
                        JSONObject()
                            .put("heightScale", readHeightScale(context))
                            .put("shortcutRowAKeys", stringArrayJson(readUpperRowKeyIds(context)))
                            .put("shortcutRowBKeys", stringArrayJson(readSecondRowButtonIds(context)))
                            .put("shortcutRowOrder", stringArrayJson(readKeyboardRowOrder(context))),
                    )
                    .put("snippets", stringArrayJson(readTextSnippets(context)))
                    .put(
                        "emoji",
                        JSONObject()
                            .put("skinTone", readEmojiSkinTone(context).id)
                            .put("recentRows", readRecentEmojiRows(context))
                            .put("recentEmojis", stringArrayJson(readRecentEmojis(context)))
                            .put("customTags", customEmojiTagsJson(readCustomEmojiTags(context))),
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
                            .put("reasoningTextEnabled", readAgentReasoningTextEnabled(context)),
                    ),
            )

        return backup.toString(BACKUP_JSON_INDENT)
    }

    fun restoreBackupJson(context: Context, backupJson: String): BackupRestoreResult {
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
        val rewrite = settings.optJSONObject("rewrite") ?: JSONObject()

        val heightScale = keyboard.optFiniteFloat("heightScale", readHeightScale(context))
            .coerceIn(MIN_HEIGHT_SCALE, MAX_HEIGHT_SCALE)
        val upperRowKeys = keyboard.optStringList("shortcutRowAKeys")
            ?.let(::normalizeUpperRowKeyIds)
            ?: readUpperRowKeyIds(context)
        val secondRowButtons = keyboard.optStringList("shortcutRowBKeys")
            ?.let(::normalizeSecondRowButtonIds)
            ?: readSecondRowButtonIds(context)
        val rowOrder = keyboard.optStringList("shortcutRowOrder")
            ?.let(::normalizeKeyboardRowOrder)
            ?: readKeyboardRowOrder(context)

        val snippets = settings.optStringList("snippets")
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
        saveUpperRowKeyIds(context, upperRowKeys)
        saveSecondRowButtonIds(context, secondRowButtons)
        saveKeyboardRowOrder(context, rowOrder)
        saveTextSnippets(context, snippets)
        saveEmojiSkinTone(context, skinTone)
        saveRecentEmojiRows(context, recentRows)
        saveRecentEmojis(context, recentEmojis)
        saveCustomEmojiTags(context, customEmojiTags)
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

        return BackupRestoreResult(
            schemaVersion = schemaVersion,
            snippetCount = snippets.size,
            customEmojiTagCount = customEmojiTags.size,
            recentEmojiCount = recentEmojis.size,
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

    private fun stringArrayJson(values: List<String>): JSONArray {
        return JSONArray().apply {
            values.forEach(::put)
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

    enum class KeyboardRow(
        val id: String,
        val label: String,
    ) {
        Upper("upper", "Shortcut row A"),
        Second("second", "Shortcut row B"),
    }

    enum class AgentProviderType(
        val id: String,
        val label: String,
    ) {
        OpenRouter("openrouter", "OpenRouter"),
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

    private data class EncryptedValue(
        val ciphertext: String,
        val iv: String,
    )

    data class BackupRestoreResult(
        val schemaVersion: Int,
        val snippetCount: Int,
        val customEmojiTagCount: Int,
        val recentEmojiCount: Int,
    )

}
