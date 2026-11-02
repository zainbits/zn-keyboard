package dev.zain.znkeyboard.ocrlab.ppocr

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the PP-OCRv6 Small LiteRT release on first use instead of shipping ~77 MB in the APK.
 * Files are pinned to one Hugging Face revision, SHA-256 verified, and kept in no-backup storage.
 */
object OcrModelStore {
    private const val REVISION = "63ecbbec38c1cf8338ff3f0e6bf967caf7d2d25d"
    private const val BASE_URL = "https://huggingface.co/litert-community/PP-OCRv6-Small-LiteRT/resolve/$REVISION/"
    private const val DIR_NAME = "ppocrv6"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val PROGRESS_STEP_BYTES = 512 * 1024

    private class ModelFile(val name: String, val sha256: String, val bytes: Long)

    private val FILES = listOf(
        ModelFile(PpOcrEngine.DET, "ec9430b6e592ffb28e83b6bab539262a30d267d2475c136b193926ad83835768", 12_380_664),
        ModelFile(PpOcrEngine.REC_320, "569bae77214a57476106ae66996f4ba4ef3ac66f81d150966c44e31a8fb0ae36", 21_477_068),
        ModelFile(PpOcrEngine.REC_640, "904c9a589763123d8a4c01d205c78669cfe3723b2a23b2221bdeb26fccc66a5d", 21_477_068),
        ModelFile(PpOcrEngine.REC_960, "cc72f0f41729e0e5d66194ff657d25cdd10646a2066a780e01b13357135e553e", 21_476_976),
        ModelFile(PpOcrEngine.VOCABULARY, "9c89db8aa95ad30c83ee305d53d366c6bba3f85ff2eab0375ef345fd50b0674a", 168_510),
    )

    val totalBytes: Long = FILES.sumOf { it.bytes }

    fun directory(context: Context): File = File(context.noBackupFilesDir, DIR_NAME)

    /** Cheap check for every launch; contents were hash-verified when downloaded. */
    fun isReady(context: Context): Boolean {
        val dir = directory(context)
        return FILES.all { File(dir, it.name).length() == it.bytes }
    }

    /**
     * Blocking; call off the main thread. Safe across the keyboard and lab processes: a file lock
     * serializes downloads, and files already present are skipped. [onProgress] gets 0..1.
     */
    fun download(context: Context, onProgress: (Float) -> Unit) {
        val dir = directory(context).apply { mkdirs() }
        RandomAccessFile(File(dir, ".lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                var done = 0L
                for (file in FILES) {
                    val target = File(dir, file.name)
                    if (target.length() != file.bytes) {
                        val base = done
                        fetch(file, target) { bytes -> onProgress((base + bytes).toFloat() / totalBytes) }
                    }
                    done += file.bytes
                    onProgress(done.toFloat() / totalBytes)
                }
            }
        }
    }

    fun delete(context: Context) {
        directory(context).listFiles()?.forEach { if (it.name != ".lock") it.delete() }
    }

    private fun fetch(file: ModelFile, target: File, onBytes: (Long) -> Unit) {
        val partial = File(target.parentFile, "${file.name}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = URL(BASE_URL + file.name).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode} for ${file.name}")
            }
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    var reported = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        received += read
                        if (received - reported >= PROGRESS_STEP_BYTES) {
                            reported = received
                            onBytes(received)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (actual != file.sha256 || partial.length() != file.bytes) {
            partial.delete()
            throw IOException("Checksum mismatch for ${file.name}")
        }
        if (!partial.renameTo(target)) throw IOException("Couldn't save ${file.name}")
    }
}
