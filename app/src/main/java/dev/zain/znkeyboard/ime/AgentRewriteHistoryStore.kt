package dev.zain.znkeyboard.ime

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AgentRewriteHistoryStore {
    const val MAX_HISTORY = 30
    const val HISTORY_FILE_NAME = "agent_rewrite_history.enc"

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS = "znkeyboard_agent_rewrite_history"
    private const val KEYSTORE_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val JSON_ENTRIES = "entries"
    private const val JSON_TEXT = "text"
    private const val JSON_TIMESTAMP_MILLIS = "timestampMillis"
    private const val JSON_CIPHERTEXT = "ciphertext"
    private const val JSON_IV = "iv"

    fun read(context: Context): List<Entry> {
        return runCatching {
            val encrypted = readEncryptedFile(context) ?: return emptyList()
            parseEntries(decrypt(encrypted))
        }.getOrElse {
            emptyList()
        }
    }

    fun recordReplacement(context: Context, originalText: String) {
        if (originalText.isBlank()) return

        val entries = buildList {
            add(Entry(originalText = originalText, timestampMillis = System.currentTimeMillis()))
            addAll(read(context))
        }.take(MAX_HISTORY)

        write(context, entries)
    }

    private fun readEncryptedFile(context: Context): JSONObject? {
        val historyFile = context.noBackupFilesDir.resolve(HISTORY_FILE_NAME)
        return try {
            historyFile.inputStream().use { input ->
                JSONObject(String(input.readBytes(), Charsets.UTF_8))
            }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    private fun write(context: Context, entries: List<Entry>) {
        val plainText = JSONObject()
            .put(
                JSON_ENTRIES,
                JSONArray().apply {
                    entries.take(MAX_HISTORY).forEach { entry ->
                        put(
                            JSONObject()
                                .put(JSON_TEXT, entry.originalText)
                                .put(JSON_TIMESTAMP_MILLIS, entry.timestampMillis),
                        )
                    }
                },
            )
            .toString()

        val encrypted = encrypt(plainText)
        val historyFile = context.noBackupFilesDir.resolve(HISTORY_FILE_NAME)
        historyFile.parentFile?.mkdirs()
        historyFile.outputStream().use { output ->
            output.write(encrypted.toString().toByteArray(Charsets.UTF_8))
        }
    }

    private fun parseEntries(plainText: String): List<Entry> {
        return try {
            val entries = JSONObject(plainText).optJSONArray(JSON_ENTRIES) ?: return emptyList()
            buildList {
                for (index in 0 until entries.length()) {
                    val item = entries.optJSONObject(index) ?: continue
                    val text = item.optString(JSON_TEXT).takeIf { it.isNotBlank() } ?: continue
                    add(
                        Entry(
                            originalText = text,
                            timestampMillis = item.optLong(JSON_TIMESTAMP_MILLIS, 0L),
                        ),
                    )
                }
            }.take(MAX_HISTORY)
        } catch (_: JSONException) {
            emptyList()
        }
    }

    private fun encrypt(plainText: String): JSONObject {
        val cipher = Cipher.getInstance(KEYSTORE_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        return JSONObject()
            .put(JSON_CIPHERTEXT, Base64.encodeToString(cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
            .put(JSON_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
    }

    private fun decrypt(encrypted: JSONObject): String {
        val ciphertext = encrypted.getString(JSON_CIPHERTEXT)
        val iv = encrypted.getString(JSON_IV)
        val cipher = Cipher.getInstance(KEYSTORE_TRANSFORMATION)
        val spec = GCMParameterSpec(
            GCM_TAG_LENGTH_BITS,
            Base64.decode(iv, Base64.NO_WRAP),
        )
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), spec)
        return String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val keySpec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
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

    data class Entry(
        val originalText: String,
        val timestampMillis: Long,
    )
}
