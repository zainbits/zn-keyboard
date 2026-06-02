package dev.zain.znkeyboard.ime

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object ClipboardHistoryStore {
    const val MAX_HISTORY = 100
    const val MAX_ENTRY_CHARS = 32_000
    const val HISTORY_FILE_NAME = "clipboard_history.enc"

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS = "znkeyboard_clipboard_history"
    private const val KEYSTORE_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val JSON_ENTRIES = "entries"
    private const val JSON_TEXT = "text"
    private const val JSON_TIMESTAMP_MILLIS = "timestampMillis"
    private const val JSON_SOURCE_PACKAGE_NAME = "sourcePackageName"
    private const val JSON_SOURCE_APP_LABEL = "sourceAppLabel"
    private const val JSON_CIPHERTEXT = "ciphertext"
    private const val JSON_IV = "iv"

    private const val LEGACY_REWRITE_HISTORY_FILE_NAME = "agent_rewrite_history.enc"
    private const val LEGACY_REWRITE_KEYSTORE_ALIAS = "znkeyboard_agent_rewrite_history"

    fun read(context: Context): List<Entry> {
        return runCatching {
            val encrypted = readEncryptedFile(context) ?: return emptyList()
            parseEntries(decrypt(encrypted))
        }.getOrElse {
            emptyList()
        }
    }

    fun recordText(
        context: Context,
        text: String,
        sourcePackageName: String? = null,
        sourceAppLabel: String? = null,
    ) {
        if (text.isBlank() || text.length > MAX_ENTRY_CHARS) return

        val now = System.currentTimeMillis()
        val entries = buildList {
            add(
                Entry(
                    text = text,
                    timestampMillis = now,
                    sourcePackageName = sourcePackageName?.takeIf { it.isNotBlank() },
                    sourceAppLabel = sourceAppLabel?.takeIf { it.isNotBlank() },
                ),
            )
            addAll(read(context).filterNot { it.text == text })
        }.take(MAX_HISTORY)

        write(context, entries)
    }

    fun clear(context: Context) {
        historyFile(context).delete()
    }

    fun deleteText(context: Context, text: String) {
        if (text.isBlank()) return
        val entries = read(context).filterNot { it.text == text }
        if (entries.isEmpty()) {
            clear(context)
        } else {
            write(context, entries)
        }
    }

    fun deleteLegacyRewriteHistory(context: Context) {
        context.noBackupFilesDir.resolve(LEGACY_REWRITE_HISTORY_FILE_NAME).delete()
        runCatching {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                .deleteEntry(LEGACY_REWRITE_KEYSTORE_ALIAS)
        }
    }

    private fun historyFile(context: Context): File {
        return context.noBackupFilesDir.resolve(HISTORY_FILE_NAME)
    }

    private fun readEncryptedFile(context: Context): JSONObject? {
        val historyFile = historyFile(context)
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
                        val item = JSONObject()
                            .put(JSON_TEXT, entry.text)
                            .put(JSON_TIMESTAMP_MILLIS, entry.timestampMillis)
                        entry.sourcePackageName?.let { item.put(JSON_SOURCE_PACKAGE_NAME, it) }
                        entry.sourceAppLabel?.let { item.put(JSON_SOURCE_APP_LABEL, it) }
                        put(item)
                    }
                },
            )
            .toString()

        val encrypted = encrypt(plainText)
        val historyFile = historyFile(context)
        val parent = historyFile.parentFile
        parent?.mkdirs()
        val tempFile = File(parent, "${historyFile.name}.tmp")

        FileOutputStream(tempFile).use { output ->
            output.write(encrypted.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        tempFile.setReadable(false, false)
        tempFile.setWritable(false, false)
        tempFile.setReadable(true, true)
        tempFile.setWritable(true, true)

        if (!tempFile.renameTo(historyFile)) {
            tempFile.copyTo(historyFile, overwrite = true)
            tempFile.delete()
        }
    }

    private fun parseEntries(plainText: String): List<Entry> {
        return try {
            val entries = JSONObject(plainText).optJSONArray(JSON_ENTRIES) ?: return emptyList()
            buildList {
                for (index in 0 until entries.length()) {
                    val item = entries.optJSONObject(index) ?: continue
                    val text = item.optString(JSON_TEXT).takeIf { it.isNotBlank() } ?: continue
                    if (text.length > MAX_ENTRY_CHARS) continue
                    add(
                        Entry(
                            text = text,
                            timestampMillis = item.optLong(JSON_TIMESTAMP_MILLIS, 0L),
                            sourcePackageName = item.optString(JSON_SOURCE_PACKAGE_NAME).takeIf { it.isNotBlank() },
                            sourceAppLabel = item.optString(JSON_SOURCE_APP_LABEL).takeIf { it.isNotBlank() },
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
        val text: String,
        val timestampMillis: Long,
        val sourcePackageName: String? = null,
        val sourceAppLabel: String? = null,
    )
}
