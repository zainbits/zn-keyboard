package dev.zain.znkeyboard.ime

import android.content.Context
import android.net.Uri
import dev.zain.znkeyboard.KeyboardSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

data class GifSearchResult(
    val id: String,
    val title: String,
    val gifUrl: String,
    val previewUrl: String,
    val previewFallbackUrl: String?,
    val width: Int,
    val height: Int,
    val byteCount: Long?,
)

internal data class GifSearchPage(
    val results: List<GifSearchResult>,
    val hasNext: Boolean,
)

internal class KlipyGifClient(
    private val settings: KeyboardSettings.GifProviderSettings,
    private val locale: String,
) {
    fun search(query: String, page: Int = 1, perPage: Int = DEFAULT_RESULT_COUNT): GifSearchPage {
        val endpoint = buildEndpoint(
            "api",
            "v1",
            settings.appKey,
            "gifs",
            "search",
            query = mapOf(
                "q" to query.trim(),
                "page" to page.toString(),
                "per_page" to perPage.coerceIn(MIN_RESULT_COUNT, MAX_RESULT_COUNT).toString(),
                "locale" to locale,
                "content_filter" to CONTENT_FILTER,
                "format_filter" to "gif,webp,jpg",
            ),
        )
        return executePageRequest(endpoint)
    }

    fun trending(page: Int = 1, perPage: Int = DEFAULT_RESULT_COUNT): GifSearchPage {
        val endpoint = buildEndpoint(
            "api",
            "v1",
            settings.appKey,
            "gifs",
            "trending",
            query = mapOf(
                "page" to page.toString(),
                "per_page" to perPage.coerceIn(MIN_RESULT_COUNT, MAX_RESULT_COUNT).toString(),
                "locale" to locale,
                "format_filter" to "gif,webp,jpg",
            ),
        )
        return executePageRequest(endpoint)
    }

    fun autocomplete(query: String, limit: Int = DEFAULT_SUGGESTION_COUNT): List<String> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.length < MIN_AUTOCOMPLETE_CHARS) return emptyList()

        val endpoint = buildEndpoint(
            "api",
            "v1",
            settings.appKey,
            "autocomplete",
            normalizedQuery,
            query = mapOf("limit" to limit.coerceIn(1, MAX_SUGGESTION_COUNT).toString()),
        )
        val response = executeJsonRequest(endpoint)
        val data = response.optJSONArray("data") ?: JSONArray()
        return buildList {
            for (index in 0 until data.length()) {
                val suggestion = data.optString(index).trim()
                if (suggestion.isNotBlank()) add(suggestion)
            }
        }.distinctBy { it.lowercase(Locale.US) }
    }

    private fun executePageRequest(endpoint: URL): GifSearchPage {
        val response = executeJsonRequest(endpoint)
        val data = response.optJSONObject("data") ?: JSONObject()
        val items = data.optJSONArray("data") ?: JSONArray()
        val results = buildList {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)
                    ?.toGifSearchResult()
                    ?.let(::add)
            }
        }
        return GifSearchPage(
            results = results,
            hasNext = data.optBoolean("has_next", false),
        )
    }

    private fun executeJsonRequest(endpoint: URL): JSONObject {
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = REQUEST_TIMEOUT_MS
            readTimeout = REQUEST_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }

        return try {
            val responseCode = connection.responseCode
            val responseText = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }

            if (responseCode !in 200..299) {
                throw IOException("GIF search failed ($responseCode): ${responseText.take(ERROR_PREVIEW_CHARS)}")
            }
            JSONObject(responseText)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildEndpoint(
        vararg pathSegments: String,
        query: Map<String, String>,
    ): URL {
        val trimmedBaseUrl = settings.baseUrl.trim().trimEnd('/')
        val base = URL(trimmedBaseUrl)
        if (base.protocol != "https") {
            throw IOException("Use an HTTPS KLIPY endpoint.")
        }

        val builder = Uri.parse(trimmedBaseUrl).buildUpon()
        pathSegments.forEach { segment ->
            builder.appendPath(segment)
        }
        query.forEach { (key, value) ->
            if (value.isNotBlank()) {
                builder.appendQueryParameter(key, value)
            }
        }
        return URL(builder.build().toString())
    }

    private fun JSONObject.toGifSearchResult(): GifSearchResult? {
        val file = optJSONObject("file") ?: return null
        val share = file.pickMedia(
            sizes = listOf("md", "sm", "xs", "hd"),
            formats = listOf("gif"),
            maxBytes = MAX_SHARE_BYTES,
        ) ?: return null
        val animatedPreview = file.pickMedia(
            sizes = listOf("xs", "sm", "md"),
            formats = listOf("gif", "webp"),
            maxBytes = MAX_PREVIEW_BYTES,
            allowOversizeFallback = false,
        )
        val staticPreview = file.pickMedia(
            sizes = listOf("xs", "sm", "md"),
            formats = listOf("jpg", "webp", "gif"),
            maxBytes = MAX_PREVIEW_FALLBACK_BYTES,
            allowOversizeFallback = true,
        )
        val preview = animatedPreview ?: staticPreview ?: share
        val id = optString("id").trim().takeIf { it.isNotBlank() }
            ?: share.url.stableId()
        val title = optString("title").trim().takeIf { it.isNotBlank() } ?: "GIF"

        return GifSearchResult(
            id = id,
            title = title,
            gifUrl = share.url,
            previewUrl = preview.url,
            previewFallbackUrl = staticPreview
                ?.url
                ?.takeUnless { it == preview.url },
            width = share.width,
            height = share.height,
            byteCount = share.byteCount,
        )
    }

    private fun JSONObject.pickMedia(
        sizes: List<String>,
        formats: List<String>,
        maxBytes: Long,
        allowOversizeFallback: Boolean = true,
    ): GifMedia? {
        val candidates = buildList {
            sizes.forEach { size ->
                val sizeObject = optJSONObject(size) ?: return@forEach
                formats.forEach { format ->
                    sizeObject.optJSONObject(format)
                        ?.toGifMedia()
                        ?.let(::add)
                }
            }
        }
        return candidates.firstOrNull { media ->
            media.byteCount == null || media.byteCount <= maxBytes
        } ?: if (allowOversizeFallback) {
            candidates.firstOrNull()
        } else {
            null
        }
    }

    private fun JSONObject.toGifMedia(): GifMedia? {
        val url = optString("url").trim()
        if (!url.startsWith("https://", ignoreCase = true)) return null
        return GifMedia(
            url = url,
            width = optInt("width", 0).coerceAtLeast(0),
            height = optInt("height", 0).coerceAtLeast(0),
            byteCount = optLong("size", 0L).takeIf { it > 0L },
        )
    }

    private data class GifMedia(
        val url: String,
        val width: Int,
        val height: Int,
        val byteCount: Long?,
    )

    private companion object {
        const val CONTENT_FILTER = "medium"
        const val MIN_RESULT_COUNT = 8
        const val MAX_RESULT_COUNT = 50
        const val DEFAULT_RESULT_COUNT = 16
        const val DEFAULT_SUGGESTION_COUNT = 8
        const val MAX_SUGGESTION_COUNT = 20
        const val MIN_AUTOCOMPLETE_CHARS = 2
        const val REQUEST_TIMEOUT_MS = 12_000
        const val ERROR_PREVIEW_CHARS = 160
        const val MAX_SHARE_BYTES = 8_000_000L
        const val MAX_PREVIEW_BYTES = 2_500_000L
        const val MAX_PREVIEW_FALLBACK_BYTES = 1_500_000L
    }
}

internal object GifCacheStore {
    fun downloadGif(context: Context, result: GifSearchResult): File {
        val cacheDir = File(context.cacheDir, GIF_CACHE_DIR).apply {
            if (!exists() && !mkdirs()) {
                throw IOException("Could not create GIF cache.")
            }
        }
        pruneCache(cacheDir)

        val file = File(cacheDir, "${result.id.stableId()}.gif")
        if (file.isFile && file.length() > 0L) return file

        val tempFile = File(cacheDir, "${file.name}.tmp")
        val connection = (URL(result.gifUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = DOWNLOAD_TIMEOUT_MS
            readTimeout = DOWNLOAD_TIMEOUT_MS
            setRequestProperty("Accept", "image/gif,*/*")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("GIF download failed ($responseCode).")
            }

            var total = 0L
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_CACHED_GIF_BYTES) {
                            throw IOException("GIF is too large to send.")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (total == 0L) {
                throw IOException("GIF download was empty.")
            }
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
            return file
        } finally {
            connection.disconnect()
            if (tempFile.isFile) tempFile.delete()
        }
    }

    private fun pruneCache(cacheDir: File) {
        val files = cacheDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(MAX_CACHE_FILES).forEach { file ->
            file.delete()
        }
    }

    private const val GIF_CACHE_DIR = "gif_cache"
    private const val DOWNLOAD_TIMEOUT_MS = 20_000
    private const val MAX_CACHED_GIF_BYTES = 8_000_000L
    private const val MAX_CACHE_FILES = 40
    private const val BUFFER_SIZE = 16 * 1024
}

private fun String.stableId(): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
    return digest.take(12).joinToString(separator = "") { byte ->
        "%02x".format(byte)
    }
}
