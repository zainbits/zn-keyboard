package dev.zain.znkeyboard.voice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Where the Bark model lives. It is never bundled in the APK: the user downloads it and picks its
 * folder once with the system folder picker (Storage Access Framework). The app keeps read access
 * to that folder and memory-maps the files from there, so no second copy is made.
 */
object BarkModelSource {
    const val PREPROCESSOR = "preprocessor.onnx"
    const val ENCODER = "encoder.onnx"
    const val DECODER = "decoder.onnx"
    const val JOINER = "joiner.onnx"
    const val TOKENS = "tokens.txt"
    val REQUIRED = listOf(PREPROCESSOR, ENCODER, DECODER, JOINER, TOKENS)

    private const val TAG = "ZnBarkModel"
    private const val PREFS_NAME = "keyboard_settings"
    private const val KEY_TREE_URI = "bark_model_tree_uri"

    /** The model files found in the chosen folder. */
    class Files(private val byName: Map<String, Uri>, val totalBytes: Long) {
        fun uri(name: String): Uri = byName.getValue(name)
    }

    sealed interface Status {
        data object NotChosen : Status
        data object NoAccess : Status
        data class Missing(val names: List<String>) : Status
        data class Ready(val files: Files) : Status
    }

    fun folderUri(context: Context): Uri? = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_TREE_URI, null)
        ?.let(Uri::parse)

    /** Human-readable folder name for settings, e.g. "Documents/ZnKeyboard/bark-model". */
    fun folderLabel(context: Context): String? = folderUri(context)?.let { uri ->
        runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfter(':') }.getOrNull()?.ifBlank { null }
            ?: uri.lastPathSegment
    }

    /** Checks the chosen folder; cheap (one directory listing), safe to call from the main thread. */
    fun status(context: Context): Status {
        val tree = folderUri(context) ?: return Status.NotChosen
        val hasGrant = context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        if (!hasGrant) return Status.NoAccess
        val found = runCatching { listChildren(context, tree) }
            .onFailure { Log.w(TAG, "Couldn't list the model folder", it) }
            .getOrNull() ?: return Status.NoAccess
        val missing = REQUIRED.filter { it !in found }
        if (missing.isNotEmpty()) return Status.Missing(missing)
        val files = REQUIRED.associateWith { found.getValue(it).first }
        return Status.Ready(Files(files, REQUIRED.sumOf { found.getValue(it).second }))
    }

    fun isReady(context: Context): Boolean = status(context) is Status.Ready

    /** Keeps read access to a folder picked with ACTION_OPEN_DOCUMENT_TREE and remembers it. */
    fun choose(context: Context, tree: Uri) {
        val resolver = context.contentResolver
        folderUri(context)?.takeIf { it != tree }?.let { old ->
            runCatching { resolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_TREE_URI, tree.toString()).apply()
        BarkEngine.releaseNow()
    }

    /** Forgets the folder and gives up access to it. The files themselves are left alone. */
    fun forget(context: Context) {
        folderUri(context)?.let { old ->
            runCatching { context.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_TREE_URI).apply()
        BarkEngine.releaseNow()
    }

    /** Maps a model file read-only. The mapping stays valid after the descriptor is closed. */
    fun map(context: Context, uri: Uri): MappedByteBuffer {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Couldn't open $uri")
        return pfd.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            }
        }
    }

    /** name -> (document uri, size) for the folder's direct children. */
    private fun listChildren(context: Context, tree: Uri): Map<String, Pair<Uri, Long>> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val result = HashMap<String, Pair<Uri, Long>>()
        context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val size = if (cursor.isNull(2)) 0L else cursor.getLong(2)
                result[name] = DocumentsContract.buildDocumentUriUsingTree(tree, id) to size
            }
        }
        return result
    }
}
