package dev.zain.znkeyboard

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
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

    private const val PREFS_NAME = "keyboard_settings"
    private const val SECRET_PREFS_NAME = "keyboard_agent_secrets"
    private const val KEY_HEIGHT_SCALE = "height_scale"
    private const val KEY_UPPER_ROW_KEYS = "upper_row_keys"
    private const val KEY_AGENT_MODE_ENABLED = "agent_mode_enabled"
    private const val KEY_AUTOCOMPLETE_PLUS_ENABLED = "autocomplete_plus_enabled"
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

    val UPPER_ROW_KEY_OPTIONS = listOf(
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

    private val upperRowKeyOptionIds = UPPER_ROW_KEY_OPTIONS.mapTo(mutableSetOf()) { it.id }

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

    fun readAutocompletePlusEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTOCOMPLETE_PLUS_ENABLED, false)
    }

    fun saveAutocompletePlusEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTOCOMPLETE_PLUS_ENABLED, enabled)
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

    private fun parseUpperRowKeyIds(stored: String): List<String>? {
        return try {
            val array = JSONArray(stored)
            buildList {
                for (index in 0 until array.length()) {
                    add(array.optString(index))
                }
            }.let(::normalizeUpperRowKeyIds)
        } catch (_: JSONException) {
            null
        }
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
            description = "Do not request reasoning. Safest for fast rewrite/autocomplete.",
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
}
