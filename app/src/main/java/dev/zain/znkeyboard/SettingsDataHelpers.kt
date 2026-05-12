package dev.zain.znkeyboard

import android.content.Context
import android.net.Uri
import dev.zain.znkeyboard.constants.SettingsBackupDefaults
import dev.zain.znkeyboard.constants.SettingsUiTimings
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.util.Locale

internal fun fetchModelOptions(
    baseUrl: String,
    apiKey: String,
): Result<List<ModelOption>> {
    return runCatching {
        val endpoint = buildModelsUrl(baseUrl)
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = SettingsUiTimings.MODEL_LOAD_TIMEOUT_MS
            readTimeout = SettingsUiTimings.MODEL_LOAD_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
        }

        try {
            val responseCode = connection.responseCode
            val responseText = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            if (responseCode !in 200..299) {
                throw IOException("Could not load models ($responseCode). You can still type a custom model ID.")
            }

            val data = JSONObject(responseText).getJSONArray("data")
            buildList {
                for (index in 0 until data.length()) {
                    val model = data.optJSONObject(index) ?: continue
                    val id = model.optString("id").trim()
                    if (id.isNotBlank()) {
                        add(
                            ModelOption(
                                id = id,
                                name = model.optString("name").trim().takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                }
            }.distinctBy { it.id }.sortedBy { it.id.lowercase(Locale.US) }
        } finally {
            connection.disconnect()
        }
    }
}

private fun buildModelsUrl(baseUrl: String): URL {
    val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
    val endpoint = URL("$normalizedBaseUrl/models")
    if (endpoint.protocol != "https") {
        throw IOException("Use an HTTPS endpoint to load models.")
    }
    return endpoint
}

internal fun List<ModelOption>.filterForQuery(query: String): List<ModelOption> {
    val normalizedQuery = query.trim().lowercase(Locale.US)
    if (normalizedQuery.isBlank()) return this

    return filter { option ->
        option.id.lowercase(Locale.US).contains(normalizedQuery) ||
            option.name?.lowercase(Locale.US)?.contains(normalizedQuery) == true
    }
}

internal fun writeSettingsBackup(context: Context, uri: Uri) {
    val output = context.contentResolver.openOutputStream(uri)
        ?: throw IOException("Could not open export file.")
    output.bufferedWriter(Charsets.UTF_8).use { writer ->
        writer.write(KeyboardSettings.createBackupJson(context))
    }
}

internal fun restoreSettingsBackup(
    context: Context,
    uri: Uri,
): KeyboardSettings.BackupRestoreResult {
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IOException("Could not open import file.")
    val backupJson = input.bufferedReader(Charsets.UTF_8).use { reader ->
        reader.readText()
    }
    return KeyboardSettings.restoreBackupJson(context, backupJson)
}

internal fun suggestedBackupFileName(): String {
    return "znkeyboard-backup-${SettingsBackupDefaults.FILE_TIMESTAMP_FORMAT.format(LocalDateTime.now())}.json"
}

internal data class ModelOption(
    val id: String,
    val name: String?,
)

internal data class PickerItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
)
