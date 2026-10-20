package dev.zain.znkeyboard.integrations

import android.content.ClipDescription
import android.content.Context

/**
 * Optional companion-app integrations. The keyboard must behave exactly the same when none are
 * installed, so every entry point here swallows integration failures and falls back to "nothing".
 *
 * Integrations are queried when panels refresh or the clipboard changes, never from typing hot
 * paths, and they do any IPC or network work off the main thread.
 */
internal object KeyboardIntegrations {
    private val integrations: List<KeyboardIntegration> = listOf(FerryIntegration)

    /** Extra actions for clipboard history rows, e.g. "Send to Mac". */
    fun clipboardItemActions(context: Context): List<ClipboardItemAction> =
        integrations.flatMap { runCatching { it.clipboardItemActions(context) }.getOrDefault(emptyList()) }

    /** Who put this clip on the clipboard, when an integration can tell (e.g. a clip from the Mac). */
    fun clipSource(context: Context, description: ClipDescription): ClipSource? =
        integrations.firstNotNullOfOrNull { runCatching { it.clipSource(context, description) }.getOrNull() }
}

internal interface KeyboardIntegration {
    fun clipboardItemActions(context: Context): List<ClipboardItemAction> = emptyList()
    fun clipSource(context: Context, description: ClipDescription): ClipSource? = null
}

/** An extra action shown when a clipboard history row is swiped open. */
internal interface ClipboardItemAction {
    val label: String
    val contentDescription: String
    fun perform(context: Context, text: String)
}

internal data class ClipSource(val packageName: String, val label: String)
