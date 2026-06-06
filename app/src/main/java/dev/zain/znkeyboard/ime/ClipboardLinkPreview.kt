package dev.zain.znkeyboard.ime

import android.os.Handler
import android.os.Looper
import android.text.Html
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale

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
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000
    private const val MAX_REDIRECTS = 3
    private const val MAX_HTML_BYTES = 256 * 1024
    private const val USER_AGENT = "ZnKeyboard/0.1 LinkPreview"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val states = mutableMapOf<String, ClipboardLinkPreviewState>()

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

    fun stateFor(url: String): ClipboardLinkPreviewState {
        return states[url] ?: ClipboardLinkPreviewState.Idle
    }

    fun request(url: String, onStateChanged: () -> Unit) {
        if (states[url] is ClipboardLinkPreviewState.Ready) {
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
                onStateChanged()
            }
        }.apply {
            name = "ZnKeyboardLinkPreview"
            start()
        }
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
}
