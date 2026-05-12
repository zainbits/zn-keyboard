package dev.zain.znkeyboard

import android.content.Context
import android.net.Uri
import dev.zain.znkeyboard.constants.SettingsBackupDefaults
import dev.zain.znkeyboard.constants.SettingsUiTimings
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.util.Locale

internal fun fetchModelOptions(
    baseUrl: String,
    apiKey: String,
): Result<List<ModelOption>> {
    return runCatching {
        val endpoint = buildModelsUrl(baseUrl)
        val data = getJsonObject(
            endpoint = endpoint,
            apiKey = apiKey,
            failureMessage = "Could not load models",
        ).getJSONArray("data")
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
    }
}

internal fun fetchOpenRouterProviderOptions(
    baseUrl: String,
    modelId: String,
    apiKey: String,
): Result<List<OpenRouterProviderOption>> {
    return runCatching {
        val providerDirectory = fetchOpenRouterProviderDirectory(baseUrl = baseUrl, apiKey = apiKey)
            .associateBy { normalizeProviderName(it.name) }
        val endpoints = getJsonObject(
            endpoint = buildOpenRouterModelEndpointsUrl(baseUrl = baseUrl, modelId = modelId),
            apiKey = apiKey,
            failureMessage = "Could not load providers for this model",
        )
            .getJSONObject("data")
            .getJSONArray("endpoints")

        buildList {
            for (index in 0 until endpoints.length()) {
                val endpoint = endpoints.optJSONObject(index) ?: continue
                val status = endpoint.optString("status").trim()
                if (status.isNotBlank() && status != "0") {
                    continue
                }
                val providerName = endpoint.optString("provider_name").trim()
                val provider = providerDirectory[normalizeProviderName(providerName)] ?: continue
                add(
                    OpenRouterProviderOption(
                        slug = provider.slug,
                        name = provider.name,
                    ),
                )
            }
        }
            .distinctBy { it.slug }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
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

private fun buildProvidersUrl(baseUrl: String): URL {
    val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
    val endpoint = URL("$normalizedBaseUrl/providers")
    if (endpoint.protocol != "https") {
        throw IOException("Use an HTTPS endpoint to load providers.")
    }
    return endpoint
}

private fun buildOpenRouterModelEndpointsUrl(baseUrl: String, modelId: String): URL {
    val normalizedModelId = modelId.trim()
    val parts = normalizedModelId.split("/", limit = 2)
    val author = parts.getOrNull(0)?.trim().orEmpty()
    val slug = parts.getOrNull(1)?.trim().orEmpty()
    if (author.isBlank() || slug.isBlank()) {
        throw IOException("Use an OpenRouter model ID like openai/gpt-4o to load providers.")
    }

    val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
    val endpoint = URL(
        "$normalizedBaseUrl/models/${author.urlEncodePathSegment()}/${slug.urlEncodePathSegment()}/endpoints",
    )
    if (endpoint.protocol != "https") {
        throw IOException("Use an HTTPS endpoint to load providers.")
    }
    return endpoint
}

private fun fetchOpenRouterProviderDirectory(
    baseUrl: String,
    apiKey: String,
): List<OpenRouterProviderDirectoryEntry> {
    val providers = getJsonObject(
        endpoint = buildProvidersUrl(baseUrl),
        apiKey = apiKey,
        failureMessage = "Could not load OpenRouter providers",
    ).getJSONArray("data")
    return providers.toObjects { provider ->
        val slug = provider.optString("slug").trim()
        val name = provider.optString("name").trim()
        if (slug.isBlank() || name.isBlank()) {
            null
        } else {
            OpenRouterProviderDirectoryEntry(slug = slug, name = name)
        }
    }
}

private fun getJsonObject(
    endpoint: URL,
    apiKey: String,
    failureMessage: String,
): JSONObject {
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
            throw IOException("$failureMessage ($responseCode).")
        }
        return JSONObject(responseText)
    } finally {
        connection.disconnect()
    }
}

private fun JSONArray.toObjects(
    transform: (JSONObject) -> OpenRouterProviderDirectoryEntry?,
): List<OpenRouterProviderDirectoryEntry> {
    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let(transform)?.let(::add)
        }
    }
}

private fun normalizeProviderName(name: String): String {
    return name.trim().lowercase(Locale.US)
}

private fun String.urlEncodePathSegment(): String {
    return URLEncoder
        .encode(this, StandardCharsets.UTF_8.toString())
        .replace("+", "%20")
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

internal data class OpenRouterProviderOption(
    val slug: String,
    val name: String,
)

private data class OpenRouterProviderDirectoryEntry(
    val slug: String,
    val name: String,
)

internal data class PickerItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
)
