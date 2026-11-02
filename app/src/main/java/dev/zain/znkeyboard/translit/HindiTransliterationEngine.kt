package dev.zain.znkeyboard.translit

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.zain.znkeyboard.KeyboardSettings
import java.io.File
import java.util.concurrent.Executors

/**
 * Android glue for Hindi typing: loads the Lekhani model from assets, runs it on one background
 * thread, and keeps the user's learned picks. The model is loaded only while Hindi mode is on;
 * [unload] drops it (and every cached result) so its memory can be reclaimed.
 *
 * Everything here is main-thread only except the model itself, which lives on [worker]. Learned
 * picks live in [Context.getNoBackupFilesDir] and leave the device only inside ZnKeyboard's
 * password-encrypted settings export.
 */
class HindiTransliterationEngine(context: Context) {
    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "zn-translit").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val main = Handler(Looper.getMainLooper())

    // Worker thread only.
    private var model: LekhaniTransliterator? = null

    private var state = State.Unloaded
    private var loadToken = 0
    @Volatile private var activeLoadToken = 0
    @Volatile private var latestRequest = 0
    private var requestSeq = 0
    private val results = object : LinkedHashMap<String, List<String>>(RESULT_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) =
            size > RESULT_CACHE_SIZE
    }
    private val readyListeners = mutableListOf<() -> Unit>()

    private val picks = HindiPicks()
    private var picksGeneration = -1
    private var savedPickChanges = 0L

    /** False in password, incognito and similar fields: picks are then used but never recorded. */
    var learningEnabled = true

    val isReady: Boolean get() = state == State.Ready

    /** Starts loading the model (and learned picks) if it isn't loaded or loading already. */
    fun load(onReady: (() -> Unit)? = null) {
        resetPicksIfGenerationChanged()
        when (state) {
            State.Ready -> {
                onReady?.invoke()
                return
            }
            State.Loading -> {
                onReady?.let(readyListeners::add)
                return
            }
            State.Unloaded -> Unit
        }
        state = State.Loading
        onReady?.let(readyListeners::add)
        val token = ++loadToken
        activeLoadToken = token
        val generation = picksGeneration
        worker.execute {
            if (token != activeLoadToken) return@execute
            val loaded = runCatching {
                val assets = appContext.assets
                assets.open(MODEL_ASSET).use { modelStream ->
                    assets.open(MANIFEST_ASSET).use { manifestStream ->
                        assets.open(WORDS_ASSET).use { wordsStream ->
                            assets.open(RERANK_ASSET).use { rerankStream ->
                                LekhaniTransliterator.load(modelStream, manifestStream, wordsStream, rerankStream)
                            }
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "Couldn't load the Hindi transliteration model", it) }.getOrNull()
            val storedPicks = readPicksFile(appContext)?.let(HindiPicks::decode)
            if (token != activeLoadToken) return@execute
            model = loaded
            main.post {
                if (token != loadToken) return@post
                // Merge rather than overwrite so picks made while loading are kept.
                if (storedPicks != null && generation == picksGeneration && picks.size == 0) {
                    picks.copyFrom(storedPicks)
                    savedPickChanges = picks.changes
                }
                // A failed load is retried the next time Hindi mode is switched on.
                state = if (loaded != null) State.Ready else State.Unloaded
                val listeners = readyListeners.toList()
                readyListeners.clear()
                if (loaded != null) listeners.forEach { it() }
            }
        }
    }

    /** Saves learned picks, then frees the model, its word list and every cached result. */
    fun unload() {
        save()
        loadToken++
        activeLoadToken = loadToken
        latestRequest = ++requestSeq
        readyListeners.clear()
        results.clear()
        state = State.Unloaded
        worker.execute { model = null }
    }

    /** Ranked candidates (learned pick first) if the model already ran on [latin], else null. */
    fun cached(latin: String): List<String>? {
        val key = HindiPicks.key(latin)
        if (key.isEmpty()) return emptyList()
        return results[key]?.let { picks.rank(key, it) }
    }

    /**
     * Runs the model on [latin] off the main thread and hands the ranked candidates to [onResult] on
     * the main thread. A newer request makes an older one that hasn't started yet skip, unless the
     * older one is [urgent] (a word that was already committed and still waits for its conversion).
     */
    fun request(latin: String, urgent: Boolean = false, onResult: (List<String>) -> Unit) {
        val key = HindiPicks.key(latin)
        if (key.isEmpty() || state != State.Ready) return
        cached(key)?.let {
            onResult(it)
            return
        }
        val seq = ++requestSeq
        latestRequest = seq
        val token = loadToken
        worker.execute {
            if (!urgent && seq != latestRequest) return@execute
            if (token != activeLoadToken) return@execute
            val transliterator = model ?: return@execute
            // Ask for extra so dropping malformed spellings still leaves MAX_CANDIDATES.
            val candidates = runCatching { transliterator.candidates(key, CANDIDATE_POOL) }.getOrNull()
                ?.let { all -> all.filter(DevanagariText::isWellFormed).ifEmpty { all }.take(MAX_CANDIDATES) }
                ?: return@execute
            main.post {
                if (token != loadToken) return@post
                results[key] = candidates
                onResult(picks.rank(key, candidates))
            }
        }
    }

    /** Remembers [picked] as the spelling for [latin]; picking the model's top choice clears the override. */
    fun recordPick(latin: String, picked: String) {
        if (!learningEnabled) return
        resetPicksIfGenerationChanged()
        val key = HindiPicks.key(latin)
        picks.record(key, picked, modelBest = results[key]?.firstOrNull())
    }

    fun save() {
        resetPicksIfGenerationChanged()
        if (picks.changes == savedPickChanges) return
        savedPickChanges = picks.changes
        val bytes = picks.encode()
        val generation = picksGeneration
        worker.execute {
            if (generation == KeyboardSettings.readHindiPicksGeneration(appContext)) {
                writePicksFile(appContext, bytes)
            }
        }
    }

    fun shutdown() {
        unload()
        worker.shutdown()
    }

    /** Settings may reset or restore picks while the keyboard runs; drop the in-memory copy then. */
    fun resetPicksIfGenerationChanged() {
        val generation = KeyboardSettings.readHindiPicksGeneration(appContext)
        if (generation == picksGeneration) return
        val firstRead = picksGeneration == -1
        picksGeneration = generation
        if (firstRead && state == State.Unloaded) return
        picks.reset()
        savedPickChanges = picks.changes
        worker.execute {
            val stored = readPicksFile(appContext)?.let(HindiPicks::decode) ?: return@execute
            main.post {
                if (generation == picksGeneration && picks.size == 0) {
                    picks.copyFrom(stored)
                    savedPickChanges = picks.changes
                }
            }
        }
    }

    private enum class State { Unloaded, Loading, Ready }

    companion object {
        private const val TAG = "ZnKeyboardTranslit"
        // Not .gz: the Android build unpacks .gz assets and drops the extension. The APK compresses them anyway.
        private const val MODEL_ASSET = "translit/model.q4"
        private const val MANIFEST_ASSET = "translit/model.json"
        private const val WORDS_ASSET = "translit/words.bin"
        private const val RERANK_ASSET = "translit/rerank.json"
        private const val PICKS_FILE = "picks.tsv"
        private const val MAX_CANDIDATES = 4
        private const val CANDIDATE_POOL = 8
        private const val RESULT_CACHE_SIZE = 256

        private fun directory(context: Context) = File(context.noBackupFilesDir, "translit")

        private fun readPicksFile(context: Context): ByteArray? = runCatching {
            File(directory(context), PICKS_FILE).takeIf(File::isFile)?.readBytes()
        }.getOrNull()

        private fun writePicksFile(context: Context, bytes: ByteArray) {
            runCatching {
                val dir = directory(context).apply { mkdirs() }
                val temp = File(dir, "$PICKS_FILE.tmp")
                temp.writeBytes(bytes)
                temp.renameTo(File(dir, PICKS_FILE))
            }
        }

        fun readLearnedPicks(context: Context): Map<String, String> =
            readPicksFile(context)?.let(HindiPicks::decode)?.toMap().orEmpty()

        /** Replaces saved picks with [picks]; callers bump the picks generation. */
        fun replaceLearnedPicks(context: Context, picks: Map<String, String>) {
            deleteLearnedPicks(context)
            if (picks.isNotEmpty()) writePicksFile(context, HindiPicks.fromMap(picks).encode())
        }

        fun deleteLearnedPicks(context: Context) {
            directory(context).listFiles()?.forEach(File::delete)
        }
    }
}
