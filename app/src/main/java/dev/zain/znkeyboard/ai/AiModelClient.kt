package dev.zain.znkeyboard.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Base64
import dev.zain.znkeyboard.KeyboardSettings
import dev.zain.znkeyboard.KeyboardSettings.AgentProviderSettings
import dev.zain.znkeyboard.KeyboardSettings.AgentProviderType
import dev.zain.znkeyboard.KeyboardSettings.AgentReasoningMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plain chat-completions calls for saved AI models outside Rewrite: Test connection in settings
 * and Scan text's AI engine. Blocking; call off the main thread.
 */
object AiModelClient {
    class HttpError(val code: Int, message: String) : IOException(message)

    data class TestResult(val latencyMs: Long, val imageInput: Boolean)

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 45_000
    private const val ERROR_PREVIEW_CHARS = 200
    private const val SCAN_MAX_TOKENS = 4_000
    private const val TEST_WORD = "KIWI"

    private const val SCAN_PROMPT =
        "You read text from photos for a phone keyboard. Output exactly the text visible in the image, " +
            "in reading order, keeping line breaks. Do not add commentary, labels, translations or Markdown " +
            "code fences. If there is no readable text, output nothing."

    /**
     * Sends a tiny image with a known word. A model that reads it back can do Scan text; a server
     * that rejects images but answers text is reachable but text only.
     */
    fun testConnection(settings: AgentProviderSettings): TestResult {
        val started = System.currentTimeMillis()
        val imagePart = imagePart("image/png", testImagePng())
        return try {
            val reply = complete(
                settings,
                JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "content",
                        JSONArray()
                            .put(JSONObject().put("type", "text").put("text", "What word is written in this image? Reply with only that word."))
                            .put(imagePart),
                    ),
                ),
                maxTokens = 20,
            )
            TestResult(System.currentTimeMillis() - started, reply.contains(TEST_WORD, ignoreCase = true))
        } catch (error: HttpError) {
            if (error.code !in 400..499 || error.code == 401 || error.code == 403) throw error
            // Some servers reject image content outright; check plain text still works.
            val textStarted = System.currentTimeMillis()
            complete(
                settings,
                JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with OK.")),
                maxTokens = 10,
            )
            TestResult(System.currentTimeMillis() - textStarted, imageInput = false)
        }
    }

    /** Scan text: returns the text in [jpeg], or "" when the model found none. */
    fun readTextFromImage(settings: AgentProviderSettings, jpeg: ByteArray): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SCAN_PROMPT))
            .put(
                JSONObject().put("role", "user").put(
                    "content",
                    JSONArray().put(imagePart("image/jpeg", jpeg)),
                ),
            )
        return complete(settings, messages, SCAN_MAX_TOKENS)
            .removeSurrounding("```")
            .lines()
            .dropWhile { it.trim().startsWith("```") }
            .dropLastWhile { it.trim().startsWith("```") }
            .joinToString("\n")
            .trim()
    }

    private fun complete(settings: AgentProviderSettings, messages: JSONArray, maxTokens: Int): String {
        val endpoint = URL("${settings.baseUrl.trim().trimEnd('/')}/chat/completions")
        if (endpoint.protocol != "https") throw IOException("Use an HTTPS server URL.")
        val body = JSONObject()
            .put("model", settings.model)
            .put("messages", messages)
            .put("max_tokens", maxTokens)
            .applyReasoning(settings)
            .applyOpenRouterProvider(settings)
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            if (settings.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.writer(Charsets.UTF_8).use { it.write(body.toString()) }
            val code = connection.responseCode
            val text = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            if (code !in 200..299) throw HttpError(code, "Server answered $code: ${errorMessage(text)}")
            val message = JSONObject(text).getJSONArray("choices").getJSONObject(0).optJSONObject("message")
                ?: throw IOException("The server's reply had no message.")
            return when (val content = message.opt("content")) {
                is String -> content
                is JSONArray -> buildString {
                    for (i in 0 until content.length()) content.optJSONObject(i)?.optString("text")?.let(::append)
                }
                else -> ""
            }.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        } finally {
            connection.disconnect()
        }
    }

    private fun errorMessage(body: String): String {
        val parsed = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
        return (parsed?.takeIf { it.isNotBlank() } ?: body).take(ERROR_PREVIEW_CHARS)
    }

    private fun JSONObject.applyReasoning(settings: AgentProviderSettings): JSONObject {
        val mode = settings.reasoningMode
        when (settings.providerType) {
            AgentProviderType.OpenRouter -> when {
                mode == AgentReasoningMode.Off -> put("reasoning", JSONObject().put("effort", "none").put("exclude", true))
                mode.effort != null -> put("reasoning", JSONObject().put("effort", mode.effort).put("exclude", true))
                else -> put("reasoning", JSONObject().put("enabled", true).put("exclude", true))
            }
            AgentProviderType.OpenAiCompatible -> mode.effort?.let { put("reasoning_effort", it) }
        }
        return this
    }

    private fun JSONObject.applyOpenRouterProvider(settings: AgentProviderSettings): JSONObject {
        val slug = settings.openRouterProviderSlug.trim()
        if (settings.providerType != AgentProviderType.OpenRouter || slug.isBlank()) return this
        return put("provider", JSONObject().put("only", JSONArray().put(slug)).put("allow_fallbacks", false))
    }

    private fun imagePart(mime: String, bytes: ByteArray): JSONObject = JSONObject()
        .put("type", "image_url")
        .put(
            "image_url",
            JSONObject().put("url", "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"),
        )

    /** A small, clearly printed word; large enough for models with a minimum image size. */
    private fun testImagePng(): ByteArray {
        val bitmap = Bitmap.createBitmap(256, 96, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 56f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            drawText(TEST_WORD, 128f, 68f, paint)
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    /** Convenience for callers holding a saved model. */
    fun KeyboardSettings.AgentProfile.isReady(): Boolean = model.isNotBlank() &&
        (providerType == AgentProviderType.OpenRouter || baseUrl.isNotBlank()) &&
        apiKey.isNotBlank()
}
