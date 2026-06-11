package dev.zain.znkeyboard.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.Html
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.security.KeyStore
import java.util.Locale
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class ClipboardLinkPreview(
    val url: String,
    val host: String,
    val title: String,
    val description: String?,
    val imageUrl: String?,
    val siteName: String?,
    val isFallback: Boolean,
)

internal sealed class ClipboardLinkPreviewState {
    data object Idle : ClipboardLinkPreviewState()
    data object Loading : ClipboardLinkPreviewState()
    data class Ready(val preview: ClipboardLinkPreview) : ClipboardLinkPreviewState()
}

internal object ClipboardLinkPreviewRepository {
    private const val CACHE_FILE_NAME = "clipboard_link_previews.enc"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS = "znkeyboard_clipboard_link_previews"
    private const val KEYSTORE_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000
    private const val MAX_REDIRECTS = 3
    private const val MAX_HTML_BYTES = 256 * 1024
    private const val MAX_URL_CHARS = 8_192
    private const val MAX_TEXT_FIELD_CHARS = 2_048
    private const val USER_AGENT = "ZnKeyboard/0.1 LinkPreview"
    private const val JSON_PREVIEWS = "previews"
    private const val JSON_URL = "url"
    private const val JSON_HOST = "host"
    private const val JSON_TITLE = "title"
    private const val JSON_DESCRIPTION = "description"
    private const val JSON_IMAGE_URL = "imageUrl"
    private const val JSON_SITE_NAME = "siteName"
    private const val JSON_IS_FALLBACK = "isFallback"
    private const val JSON_CACHED_AT_MILLIS = "cachedAtMillis"
    private const val JSON_CIPHERTEXT = "ciphertext"
    private const val JSON_IV = "iv"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val persistenceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ZnKeyboardLinkPreviewCache").apply { isDaemon = true }
    }
    private val states = mutableMapOf<String, ClipboardLinkPreviewState>()
    private val cacheAccessTimes = mutableMapOf<String, Long>()
    private var cacheLoaded = false

    private val urlRegex = Regex("""https://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val metaTagRegex = Regex("""<meta\s+[^>]*>""", RegexOption.IGNORE_CASE)
    private val attrRegex = Regex(
        """([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*("([^"]*)"|'([^']*)'|([^\s"'>]+))""",
        RegexOption.IGNORE_CASE,
    )
    private val titleRegex = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val tagRegex = Regex("""<[^>]+>""")
    private val whitespaceRegex = Regex("""\s+""")
    private val charsetRegex = Regex("""charset=([^;\s]+)""", RegexOption.IGNORE_CASE)

    fun extractFirstPreviewUrl(text: String): String? {
        val rawUrl = urlRegex.find(text)?.value?.trimUrlPunctuation() ?: return null
        return normalizePreviewUrl(rawUrl)
    }

    fun loadPersistedPreviews(context: Context) {
        ensureLoaded(context.applicationContext)
    }

    fun stateFor(context: Context, url: String): ClipboardLinkPreviewState {
        ensureLoaded(context.applicationContext)
        return states[url] ?: ClipboardLinkPreviewState.Idle
    }

    fun request(context: Context, url: String, onStateChanged: () -> Unit) {
        val appContext = context.applicationContext
        ensureLoaded(appContext)
        if (states[url] is ClipboardLinkPreviewState.Ready) {
            cacheAccessTimes[url] = System.currentTimeMillis()
            onStateChanged()
            return
        }
        if (states[url] == ClipboardLinkPreviewState.Loading) return

        states[url] = ClipboardLinkPreviewState.Loading
        onStateChanged()

        Thread {
            val preview = runCatching { fetchPreview(url) }
                .getOrElse { fallbackPreview(url) }
            mainHandler.post {
                states[url] = ClipboardLinkPreviewState.Ready(preview)
                cacheAccessTimes[url] = System.currentTimeMillis()
                trimReadyCache()
                persistReadyPreviews(appContext)
                onStateChanged()
            }
        }.apply {
            name = "ZnKeyboardLinkPreview"
            start()
        }
    }

    private fun ensureLoaded(context: Context) {
        if (cacheLoaded) return
        cacheLoaded = true
        readPersistedPreviews(context).forEach { cached ->
            states.putIfAbsent(cached.preview.url, ClipboardLinkPreviewState.Ready(cached.preview))
            cacheAccessTimes[cached.preview.url] = cached.cachedAtMillis
        }
    }

    private fun readPersistedPreviews(context: Context): List<CachedPreview> {
        return runCatching {
            val encrypted = readEncryptedFile(context) ?: return emptyList()
            parsePersistedPreviews(decrypt(encrypted))
        }.getOrElse {
            emptyList()
        }
    }

    private fun persistReadyPreviews(context: Context) {
        val snapshot = states.mapNotNull { (_, state) ->
            val preview = (state as? ClipboardLinkPreviewState.Ready)?.preview ?: return@mapNotNull null
            CachedPreview(
                preview = preview,
                cachedAtMillis = cacheAccessTimes[preview.url] ?: 0L,
            )
        }
            .sortedByDescending { it.cachedAtMillis }
            .take(ClipboardHistoryStore.MAX_HISTORY)

        persistenceExecutor.execute {
            runCatching {
                writePersistedPreviews(context, snapshot)
            }
        }
    }

    private fun cacheFile(context: Context): File {
        return context.noBackupFilesDir.resolve(CACHE_FILE_NAME)
    }

    private fun readEncryptedFile(context: Context): JSONObject? {
        val cacheFile = cacheFile(context)
        return try {
            cacheFile.inputStream().use { input ->
                JSONObject(String(input.readBytes(), Charsets.UTF_8))
            }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    private fun writePersistedPreviews(context: Context, previews: List<CachedPreview>) {
        val plainText = JSONObject()
            .put(
                JSON_PREVIEWS,
                JSONArray().apply {
                    previews.forEach { cached ->
                        val item = JSONObject()
                            .put(JSON_URL, cached.preview.url)
                            .put(JSON_HOST, cached.preview.host)
                            .put(JSON_TITLE, cached.preview.title)
                            .put(JSON_IS_FALLBACK, cached.preview.isFallback)
                            .put(JSON_CACHED_AT_MILLIS, cached.cachedAtMillis)
                        cached.preview.description?.let { item.put(JSON_DESCRIPTION, it) }
                        cached.preview.imageUrl?.let { item.put(JSON_IMAGE_URL, it) }
                        cached.preview.siteName?.let { item.put(JSON_SITE_NAME, it) }
                        put(item)
                    }
                },
            )
            .toString()

        val encrypted = encrypt(plainText)
        val cacheFile = cacheFile(context)
        val parent = cacheFile.parentFile
        parent?.mkdirs()
        val tempFile = File(parent, "${cacheFile.name}.tmp")

        FileOutputStream(tempFile).use { output ->
            output.write(encrypted.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        tempFile.setReadable(false, false)
        tempFile.setWritable(false, false)
        tempFile.setReadable(true, true)
        tempFile.setWritable(true, true)

        if (!tempFile.renameTo(cacheFile)) {
            tempFile.copyTo(cacheFile, overwrite = true)
            tempFile.delete()
        }
    }

    private fun parsePersistedPreviews(plainText: String): List<CachedPreview> {
        return try {
            val previews = JSONObject(plainText).optJSONArray(JSON_PREVIEWS) ?: return emptyList()
            buildList {
                for (index in 0 until previews.length()) {
                    val item = previews.optJSONObject(index) ?: continue
                    val preview = ClipboardLinkPreview(
                        url = item.optStoredString(JSON_URL, MAX_URL_CHARS) ?: continue,
                        host = item.optStoredString(JSON_HOST, MAX_TEXT_FIELD_CHARS) ?: continue,
                        title = item.optStoredString(JSON_TITLE, MAX_TEXT_FIELD_CHARS, truncate = true) ?: continue,
                        description = item.optStoredString(JSON_DESCRIPTION, MAX_TEXT_FIELD_CHARS, truncate = true),
                        imageUrl = item.optStoredString(JSON_IMAGE_URL, MAX_URL_CHARS),
                        siteName = item.optStoredString(JSON_SITE_NAME, MAX_TEXT_FIELD_CHARS, truncate = true),
                        isFallback = item.optBoolean(JSON_IS_FALLBACK, false),
                    )
                    add(
                        CachedPreview(
                            preview = preview,
                            cachedAtMillis = item.optLong(JSON_CACHED_AT_MILLIS, 0L),
                        ),
                    )
                }
            }.take(ClipboardHistoryStore.MAX_HISTORY)
        } catch (_: JSONException) {
            emptyList()
        }
    }

    private fun trimReadyCache() {
        val oldReadyUrls = states.mapNotNull { (url, state) ->
            if (state is ClipboardLinkPreviewState.Ready) {
                url to (cacheAccessTimes[url] ?: 0L)
            } else {
                null
            }
        }
            .sortedByDescending { it.second }
            .drop(ClipboardHistoryStore.MAX_HISTORY)
            .map { it.first }
        oldReadyUrls.forEach { url ->
            states.remove(url)
            cacheAccessTimes.remove(url)
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

    private fun normalizePreviewUrl(rawUrl: String): String? {
        return runCatching {
            val uri = URI(rawUrl)
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            URI(
                "https",
                uri.userInfo,
                host.lowercase(Locale.US),
                uri.port,
                uri.path,
                uri.query,
                null,
            ).normalize().toString()
        }.getOrNull()
    }

    private fun fetchPreview(url: String): ClipboardLinkPreview {
        val finalUrl = resolveFinalUrl(URL(url))
        val host = displayHost(finalUrl.host)
        val connection = (finalUrl.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
        }

        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) throw IOException("Preview request failed: $responseCode")

            val contentType = connection.contentType.orEmpty()
            if (contentType.isNotBlank() && !contentType.contains("html", ignoreCase = true)) {
                throw IOException("Preview response was not HTML.")
            }

            val charset = charsetFor(contentType)
            val html = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    if (total > MAX_HTML_BYTES) {
                        output.write(buffer, 0, read - (total - MAX_HTML_BYTES))
                        break
                    }
                    output.write(buffer, 0, read)
                }
                output.toString(charset.name())
            }

            parsePreview(
                requestedUrl = url,
                finalUrl = finalUrl,
                html = html,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveFinalUrl(initialUrl: URL): URL {
        var current = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            validateNetworkUrl(current)
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "HEAD"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }
            try {
                val responseCode = connection.responseCode
                if (responseCode !in 300..399) return current
                if (redirectCount == MAX_REDIRECTS) throw IOException("Too many redirects.")
                val location = connection.getHeaderField("Location") ?: throw IOException("Redirect missing location.")
                current = current.toURI().resolve(location).toURL()
            } finally {
                connection.disconnect()
            }
        }
        return current
    }

    private fun parsePreview(requestedUrl: String, finalUrl: URL, html: String): ClipboardLinkPreview {
        val metadata = parseMetadata(html)
        val host = displayHost(finalUrl.host)
        val rawTitle = firstCleanValue(
            metadata["og:title"],
            metadata["twitter:title"],
            extractTitle(html),
        )
        val description = firstCleanValue(
            metadata["og:description"],
            metadata["twitter:description"],
            metadata["description"],
        )
        val siteName = firstCleanValue(metadata["og:site_name"], metadata["twitter:site"]) ?: siteNameForHost(host)
        val imageUrl = firstCleanValue(metadata["og:image:secure_url"], metadata["og:image"], metadata["twitter:image"])
            ?.let { resolveSafeImageUrl(finalUrl, it) }
        if (rawTitle == null && description == null && imageUrl == null) {
            return fallbackPreview(requestedUrl, hostOverride = host)
        }

        return ClipboardLinkPreview(
            url = requestedUrl,
            host = host,
            title = rawTitle ?: host,
            description = description,
            imageUrl = imageUrl,
            siteName = siteName,
            isFallback = false,
        )
    }

    private fun parseMetadata(html: String): Map<String, String> {
        val metadata = linkedMapOf<String, String>()
        metaTagRegex.findAll(html).forEach { tagMatch ->
            val attrs = parseAttributes(tagMatch.value)
            val key = attrs["property"] ?: attrs["name"] ?: return@forEach
            val content = attrs["content"]?.takeIf { it.isNotBlank() } ?: return@forEach
            metadata.putIfAbsent(key.lowercase(Locale.US), content)
        }
        return metadata
    }

    private fun parseAttributes(tag: String): Map<String, String> {
        return buildMap {
            attrRegex.findAll(tag).forEach { match ->
                val name = match.groupValues[1].lowercase(Locale.US)
                val value = match.groupValues[3]
                    .ifBlank { match.groupValues[4] }
                    .ifBlank { match.groupValues[5] }
                put(name, value)
            }
        }
    }

    private fun extractTitle(html: String): String? {
        return titleRegex.find(html)?.groupValues?.getOrNull(1)
    }

    private fun firstCleanValue(vararg values: String?): String? {
        return values.firstNotNullOfOrNull { value ->
            value?.cleanHtmlText()?.takeIf { it.isNotBlank() }
        }
    }

    private fun resolveSafeImageUrl(baseUrl: URL, rawImageUrl: String): String? {
        return runCatching {
            val imageUrl = baseUrl.toURI().resolve(rawImageUrl).toURL()
            validateNetworkUrl(imageUrl)
            imageUrl.toString()
        }.getOrNull()
    }

    private fun validateNetworkUrl(url: URL) {
        if (!url.protocol.equals("https", ignoreCase = true)) {
            throw IOException("Only HTTPS previews are supported.")
        }
        val host = url.host?.takeIf { it.isNotBlank() } ?: throw IOException("Preview URL missing host.")
        if (isBlockedHost(host)) {
            throw IOException("Private hosts are not previewed.")
        }
    }

    private fun isBlockedHost(host: String): Boolean {
        val normalized = host.trim().trimEnd('.').lowercase(Locale.US)
        if (normalized == "localhost" || normalized.endsWith(".localhost")) return true
        return InetAddress.getAllByName(normalized).any(::isPrivateAddress)
    }

    private fun isPrivateAddress(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        if (address is Inet4Address) {
            val bytes = address.address.map { it.toInt() and 0xff }
            return bytes[0] == 10 ||
                bytes[0] == 127 ||
                bytes[0] == 169 && bytes[1] == 254 ||
                bytes[0] == 172 && bytes[1] in 16..31 ||
                bytes[0] == 192 && bytes[1] == 168 ||
                bytes[0] == 100 && bytes[1] in 64..127
        }
        if (address is Inet6Address) {
            val firstByte = address.address[0].toInt() and 0xff
            return (firstByte and 0xfe) == 0xfc
        }
        return false
    }

    private fun charsetFor(contentType: String): Charset {
        val charsetName = charsetRegex.find(contentType)?.groupValues?.getOrNull(1)?.trim('"')
        return runCatching {
            if (charsetName.isNullOrBlank()) Charsets.UTF_8 else Charset.forName(charsetName)
        }.getOrDefault(Charsets.UTF_8)
    }

    private fun fallbackPreview(url: String, hostOverride: String? = null): ClipboardLinkPreview {
        val uri = runCatching { URI(url) }.getOrNull()
        val host = hostOverride ?: displayHost(uri?.host.orEmpty())
        val path = uri?.path.orEmpty().lowercase(Locale.US)
        val instagramTitle = when {
            host.endsWith("instagram.com") && path.startsWith("/reel/") -> "Instagram Reel"
            host.endsWith("instagram.com") && path.startsWith("/p/") -> "Instagram Post"
            host.endsWith("instagram.com") && path.startsWith("/stories/") -> "Instagram Story"
            host.endsWith("instagram.com") && path.count { it == '/' } <= 1 && path.length > 1 -> "Instagram Profile"
            host.endsWith("instagram.com") -> "Instagram Link"
            else -> null
        }
        return ClipboardLinkPreview(
            url = url,
            host = host.ifBlank { "Link" },
            title = instagramTitle ?: "Link preview unavailable",
            description = if (instagramTitle == null) {
                host.takeIf { it.isNotBlank() }
            } else {
                "Metadata was unavailable, but this looks like a ${instagramTitle.removePrefix("Instagram ").lowercase(Locale.US)}."
            },
            imageUrl = null,
            siteName = siteNameForHost(host),
            isFallback = true,
        )
    }

    private fun String.cleanHtmlText(): String {
        val withoutTags = replace(tagRegex, " ")
        return Html.fromHtml(withoutTags, Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace(whitespaceRegex, " ")
            .trim()
    }

    private fun String.trimUrlPunctuation(): String {
        return trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'')
    }

    private fun displayHost(host: String): String {
        return host.lowercase(Locale.US).removePrefix("www.")
    }

    private fun siteNameForHost(host: String): String? {
        return when {
            host.endsWith("instagram.com") -> "Instagram"
            host.isNotBlank() -> host
            else -> null
        }
    }

    private fun JSONObject.optStoredString(
        key: String,
        maxChars: Int,
        truncate: Boolean = false,
    ): String? {
        val value = optString(key).takeIf { it.isNotBlank() } ?: return null
        return when {
            value.length <= maxChars -> value
            truncate -> value.take(maxChars)
            else -> null
        }
    }

    private data class CachedPreview(
        val preview: ClipboardLinkPreview,
        val cachedAtMillis: Long,
    )
}
