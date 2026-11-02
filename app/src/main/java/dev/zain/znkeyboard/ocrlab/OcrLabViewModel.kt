package dev.zain.znkeyboard.ocrlab

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litert.Accelerator
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.zain.znkeyboard.ocrlab.ppocr.OcrModelStore
import dev.zain.znkeyboard.ocrlab.ppocr.PpOcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Runs the same capture through ML Kit and PP-OCRv6 (CPU and GPU) sequentially, so timings don't contend. */
class OcrLabViewModel(application: Application) : AndroidViewModel(application) {
    enum class Engine(val label: String) {
        MlKit("ML Kit v2 (Latin)"),
        PpCpu("PP-OCRv6 Small · CPU"),
        PpGpu("PP-OCRv6 Small · GPU FP32"),
    }

    sealed interface EngineState {
        data object Loading : EngineState
        data class Downloading(val percent: Int) : EngineState
        data class Ready(val initMs: Double) : EngineState
        data class Failed(val message: String) : EngineState
    }

    sealed interface RunState {
        data object Pending : RunState
        data object Running : RunState
        data class Done(val text: String, val summary: String) : RunState
        data class Failed(val message: String) : RunState
    }

    val engineStates = mutableStateMapOf<Engine, EngineState>()
    val runStates = mutableStateMapOf<Engine, RunState>()
    var input by mutableStateOf<Bitmap?>(null)
        private set
    var running by mutableStateOf(false)
        private set
    var saveCaptures by mutableStateOf(true)
    var savedName by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    private val cpuDispatcher = singleThread("ocr-pp-cpu")
    private val gpuDispatcher = singleThread("ocr-pp-gpu")
    private var mlKit: TextRecognizer? = null
    private var ppCpu: PpOcrEngine? = null
    private var ppGpu: PpOcrEngine? = null

    init {
        Engine.entries.forEach { engineStates[it] = EngineState.Loading }
        viewModelScope.launch {
            engineStates[Engine.MlKit] = runCatching {
                val start = System.nanoTime()
                // The lab runs in its own process, where ML Kit's init provider does not run.
                MlKitContext.initializeIfNeeded(application)
                mlKit = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                EngineState.Ready((System.nanoTime() - start) / 1e6)
            }.getOrElse { EngineState.Failed(it.describe()) }

            if (!OcrModelStore.isReady(application)) {
                val downloaded = withContext(Dispatchers.IO) {
                    runCatching {
                        OcrModelStore.download(application) { progress ->
                            val state = EngineState.Downloading((progress * 100).toInt())
                            viewModelScope.launch {
                                engineStates[Engine.PpCpu] = state
                                engineStates[Engine.PpGpu] = state
                            }
                        }
                    }
                }
                downloaded.exceptionOrNull()?.let { error ->
                    val failed = EngineState.Failed("Model download failed: ${error.describe()}")
                    engineStates[Engine.PpCpu] = failed
                    engineStates[Engine.PpGpu] = failed
                    return@launch
                }
            }
            val modelDir = OcrModelStore.directory(application)
            engineStates[Engine.PpCpu] = withContext(cpuDispatcher) {
                runCatching { PpOcrEngine(modelDir, Accelerator.CPU).also { ppCpu = it } }
                    .fold({ EngineState.Ready(it.initMs) }, { EngineState.Failed(it.describe()) })
            }
            engineStates[Engine.PpGpu] = withContext(gpuDispatcher) {
                runCatching { PpOcrEngine(modelDir, Accelerator.GPU).also { ppGpu = it } }
                    .fold({ EngineState.Ready(it.initMs) }, { EngineState.Failed(it.describe()) })
            }
        }
    }

    fun process(bitmap: Bitmap, source: String) {
        if (running) return
        input = bitmap
        savedName = null
        running = true
        Engine.entries.forEach { runStates[it] = RunState.Pending }
        viewModelScope.launch {
            runStates[Engine.MlKit] = RunState.Running
            runStates[Engine.MlKit] = withContext(Dispatchers.Default) { runMlKit(bitmap) }

            runStates[Engine.PpCpu] = RunState.Running
            val cpu = withContext(cpuDispatcher) { runPp(ppCpu, Engine.PpCpu, bitmap) }
            runStates[Engine.PpCpu] = cpu

            runStates[Engine.PpGpu] = RunState.Running
            var gpu = withContext(gpuDispatcher) { runPp(ppGpu, Engine.PpGpu, bitmap) }
            if (gpu is RunState.Done && cpu is RunState.Done) {
                val parity = if (gpu.text == cpu.text) "same text as CPU" else "DIFFERS from CPU"
                gpu = gpu.copy(summary = "${gpu.summary} · $parity")
            }
            runStates[Engine.PpGpu] = gpu

            if (saveCaptures) {
                savedName = withContext(Dispatchers.IO) { runCatching { save(bitmap, source) }.getOrNull() }
            }
            running = false
        }
    }

    fun reset() {
        if (running) return
        input = null
        savedName = null
        runStates.clear()
    }

    private fun runMlKit(bitmap: Bitmap): RunState {
        val recognizer = mlKit ?: return RunState.Failed(stateMessage(Engine.MlKit))
        return runCatching {
            val start = System.nanoTime()
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
            val ms = (System.nanoTime() - start) / 1e6
            val lines = result.textBlocks.sumOf { it.lines.size }
            RunState.Done(result.text, "${ms.fmt()} ms · $lines lines")
        }.getOrElse { RunState.Failed(it.describe()) }
    }

    private fun runPp(engine: PpOcrEngine?, id: Engine, bitmap: Bitmap): RunState {
        engine ?: return RunState.Failed(stateMessage(id))
        return runCatching {
            val result = engine.recognize(bitmap)
            val squeezed = result.lines.count { it.compressed }
            val low = result.lines.count { (it.score ?: 0.0) < LOW_CONFIDENCE }
            val summary = buildString {
                append("${result.totalMs.fmt()} ms · det ${result.detectMs.fmt()} · boxes ${result.geometryMs.fmt()}")
                append(" · rec ${result.recognizeMs.fmt()} · ${result.lines.size} lines")
                if (low > 0) append(" · $low below ${(LOW_CONFIDENCE * 100).toInt()}% conf")
                if (squeezed > 0) append(" · $squeezed squeezed")
            }
            RunState.Done(result.text, summary)
        }.getOrElse { RunState.Failed(it.describe()) }
    }

    private fun stateMessage(engine: Engine): String = when (val state = engineStates[engine]) {
        is EngineState.Failed -> state.message
        else -> "Not ready yet"
    }

    private fun save(bitmap: Bitmap, source: String): String {
        val resolver = getApplication<Application>().contentResolver
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val base = "ocr-$stamp"
        fun insert(name: String, mime: String) = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, SAVE_DIR)
            },
        ) ?: error("MediaStore insert failed")

        resolver.openOutputStream(insert("$base.jpg", "image/jpeg"))?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
        }
        val report = buildString {
            appendLine("source: $source, ${bitmap.width}x${bitmap.height}")
            Engine.entries.forEach { engine ->
                appendLine()
                appendLine("=== ${engine.label}")
                when (val state = runStates[engine]) {
                    is RunState.Done -> {
                        appendLine(state.summary)
                        appendLine("---")
                        appendLine(state.text)
                    }
                    is RunState.Failed -> appendLine("FAILED: ${state.message}")
                    else -> appendLine("not run")
                }
            }
        }
        resolver.openOutputStream(insert("$base.txt", "text/plain"))?.use { it.write(report.toByteArray()) }
        return "$SAVE_DIR$base.{jpg,txt}"
    }

    override fun onCleared() {
        val cpu = ppCpu
        val gpu = ppGpu
        cpuDispatcher.executor.execute { runCatching { cpu?.close() } }
        gpuDispatcher.executor.execute { runCatching { gpu?.close() } }
        cpuDispatcher.close()
        gpuDispatcher.close()
        mlKit?.close()
    }

    private companion object {
        const val SAVE_DIR = "Download/ZnOcrLab/"
        const val LOW_CONFIDENCE = 0.8

        fun singleThread(name: String): ExecutorCoroutineDispatcher =
            Executors.newSingleThreadExecutor { Thread(it, name) }.asCoroutineDispatcher()

        fun Double.fmt() = "%.0f".format(Locale.US, this)

        fun Throwable.describe() = "${javaClass.simpleName}: ${message ?: "no message"}"
    }
}
