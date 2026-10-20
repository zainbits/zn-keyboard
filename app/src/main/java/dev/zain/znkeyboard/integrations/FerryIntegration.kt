package dev.zain.znkeyboard.integrations

import android.content.ClipDescription
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Ferry (Mac ↔ phone clipboard): adds "Send to Mac" to clipboard history and labels clips that
 * came from the Mac. Contract: protocol/BRIDGE.md in the Ferry repo.
 *
 * Only active when Ferry is installed, signed with this app's key, and has granted its
 * signature-level bridge permission. Otherwise it contributes nothing.
 */
internal object FerryIntegration : KeyboardIntegration {
    private const val PACKAGE = "dev.zain.ferry"
    private const val AUTHORITY = "dev.zain.ferry.bridge"
    private const val PERMISSION = "dev.zain.ferry.permission.BRIDGE"
    private const val METHOD_SEND_TEXT = "sendText"
    private const val KEY_TEXT = "text"
    private const val KEY_OK = "ok"
    private const val KEY_ERROR = "error"
    private const val KEY_MAC_NAME = "macName"
    private const val EXTRA_SOURCE_DEVICE = "dev.zain.ferry.extra.SOURCE_DEVICE"
    private const val MAX_TEXT_CHARS = 200_000
    private const val MAX_SOURCE_LABEL_CHARS = 64

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ferry-bridge").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val sendToMac = object : ClipboardItemAction {
        override val label = "Send to Mac"
        override val contentDescription = "Send this clipboard item to your Mac with Ferry"
        override fun perform(context: Context, text: String) = send(context.applicationContext, text)
    }

    /** Ferry is present and is ours: same signing key, so the permission is real and the app is trusted. */
    private fun isTrusted(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED &&
            context.packageManager.checkSignatures(context.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH

    override fun clipboardItemActions(context: Context): List<ClipboardItemAction> =
        if (isTrusted(context)) listOf(sendToMac) else emptyList()

    override fun clipSource(context: Context, description: ClipDescription): ClipSource? {
        val device = description.extras?.getString(EXTRA_SOURCE_DEVICE)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!isTrusted(context)) return null
        return ClipSource(packageName = PACKAGE, label = device.take(MAX_SOURCE_LABEL_CHARS))
    }

    private fun send(context: Context, text: String) {
        if (text.length > MAX_TEXT_CHARS) {
            Toast.makeText(context, "Too long to send to Mac.", Toast.LENGTH_SHORT).show()
            return
        }
        worker.execute {
            val message = runCatching {
                // Unstable client: if Ferry crashes mid-call, the keyboard process survives.
                context.contentResolver.acquireUnstableContentProviderClient(AUTHORITY)?.use { client ->
                    client.call(METHOD_SEND_TEXT, null, Bundle().apply { putString(KEY_TEXT, text) })
                }
            }.fold(
                onSuccess = { result ->
                    when {
                        result == null -> "Ferry isn't available."
                        result.getBoolean(KEY_OK) -> "Sent to ${result.getString(KEY_MAC_NAME) ?: "Mac"}"
                        else -> "Couldn't send: ${result.getString(KEY_ERROR) ?: "unknown error"}"
                    }
                },
                onFailure = { "Couldn't reach Ferry." },
            )
            mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
        }
    }
}
