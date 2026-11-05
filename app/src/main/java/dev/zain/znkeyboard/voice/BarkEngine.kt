package dev.zain.znkeyboard.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.zain.znkeyboard.constants.VoiceDefaults
import java.util.concurrent.Executors

/**
 * Process-wide owner of the Bark recognizer (~0.8 GB native memory). One worker thread loads,
 * transcribes and releases it, so those never race. Listeners hear warm/cold changes on the
 * main thread; the Voice key uses that to show whether the model is in memory.
 */
object BarkEngine {
    enum class State { Cold, Loading, Warm }

    private const val TAG = "ZnBarkEngine"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "zn-bark").apply { isDaemon = true } }
    private val listeners = mutableSetOf<() -> Unit>()
    private var recognizer: BarkRecognizer? = null // worker thread only

    @Volatile
    var state: State = State.Cold
        private set

    val isWarm: Boolean get() = state == State.Warm

    private val release = Runnable { worker.execute { closeRecognizer() } }

    /** Main thread. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    /** Main thread. */
    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** Starts loading in the background if needed, and keeps a loaded model from being released. */
    fun warmUp(context: Context) {
        mainHandler.removeCallbacks(release)
        val app = context.applicationContext
        worker.execute { ensureRecognizer(app) }
    }

    /** Releases the model after [VoiceDefaults.RECOGNIZER_IDLE_RELEASE_MS] unless used again. */
    fun scheduleRelease() {
        mainHandler.removeCallbacks(release)
        mainHandler.postDelayed(release, VoiceDefaults.RECOGNIZER_IDLE_RELEASE_MS)
    }

    fun releaseNow() {
        mainHandler.removeCallbacks(release)
        worker.execute { closeRecognizer() }
    }

    /** Transcribes on the worker (loading first if needed); [onResult] runs on the main thread. */
    fun transcribe(context: Context, samples: FloatArray, onResult: (Result<String>) -> Unit) {
        mainHandler.removeCallbacks(release)
        val app = context.applicationContext
        worker.execute {
            val result = runCatching {
                val model = ensureRecognizer(app) ?: error("Speech model unavailable")
                val started = System.currentTimeMillis()
                model.transcribe(samples, VoiceDefaults.SAMPLE_RATE).also { text ->
                    val ms = System.currentTimeMillis() - started
                    val audioMs = samples.size * 1000L / VoiceDefaults.SAMPLE_RATE
                    // Timings and lengths only; never the transcript.
                    Log.i(TAG, "Transcribed ${audioMs} ms of audio in ${ms} ms (${text.length} chars)")
                }
            }.onFailure { Log.w(TAG, "Transcription failed", it) }
            mainHandler.post { onResult(result) }
        }
    }

    private fun ensureRecognizer(context: Context): BarkRecognizer? {
        recognizer?.let { return it }
        if (!BarkModelAssets.isBundled(context)) return null
        setState(State.Loading)
        val started = System.currentTimeMillis()
        val loaded = runCatching { BarkRecognizer(context) }
            .onFailure { Log.w(TAG, "Bark load failed", it) }
            .getOrNull()
        if (loaded != null) Log.i(TAG, "Loaded Bark in ${System.currentTimeMillis() - started} ms")
        recognizer = loaded
        setState(if (loaded != null) State.Warm else State.Cold)
        return loaded
    }

    private fun closeRecognizer() {
        recognizer?.close()
        recognizer = null
        setState(State.Cold)
    }

    private fun setState(next: State) {
        if (state == next) return
        state = next
        mainHandler.post { listeners.toList().forEach { it() } }
    }
}
