package dev.zain.znkeyboard.ime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.zain.znkeyboard.constants.ImeColors
import dev.zain.znkeyboard.constants.VoiceDefaults
import dev.zain.znkeyboard.voice.BarkEngine
import dev.zain.znkeyboard.voice.BarkModelAssets
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Dictation panel that replaces the keys. Opening it starts recording and warms Bark (BarkEngine)
 * in parallel; Done transcribes on the phone and hands the text back. Audio is never stored or sent.
 */
class VoicePanelView(context: Context) : FrameLayout(context) {
    interface Callback {
        fun onVoiceTextCaptured(text: String)
        fun onVoicePanelClosed()
        fun onVoicePermissionRequested()
    }

    var callback: Callback? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recorderThread: Thread? = null
    @Volatile private var recording = false
    private var generation = 0
    private var fixedHeightPx = 0
    private var startedAtMs = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (!recording) return
            val seconds = ((System.currentTimeMillis() - startedAtMs) / 1000).toInt()
            statusText.text = "Listening  %d:%02d".format(seconds / 60, seconds % 60)
            mainHandler.postDelayed(this, 500L)
        }
    }

    private val levelRing = LevelRingView(context)
    private val statusText = label(16f, ImeColors.TEXT)
    private val hintText = label(13f, ImeColors.DISABLED_TEXT).apply {
        text = "${VoiceDefaults.MODEL_DISPLAY_NAME} runs on this phone"
    }
    private val doneButton = TextView(context).apply {
        text = "Done"
        contentDescription = "Stop and type what you said"
        setTextColor(ImeColors.ON_SELECTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = false
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ImeColors.SELECTED)
        }
        setOnClickListener { finishRecording() }
    }
    private val retryButton = pillButton("Try again") { start() }
    private val permissionButton = pillButton("Allow microphone access") { callback?.onVoicePermissionRequested() }
    private val closeButton = ImePanelChrome.backButton(
        context = context,
        contentDescription = "Back to keyboard",
        backgroundColor = ImeColors.FUNCTION,
        textColor = ImeColors.TEXT,
        onClick = { callback?.onVoicePanelClosed() },
    )

    init {
        setBackgroundColor(ImeColors.BACKGROUND)
        isClickable = true
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        val bottomGap = ImeLayout.bottomSystemControlGapPx(context).roundToInt()
        val ringSize = context.dp(RING_DP)
        val buttonSize = context.dp(BUTTON_DP)
        val content = FrameLayout(context).apply {
            addView(closeButton, LayoutParams(context.dp(44), context.dp(44), Gravity.TOP or Gravity.START).margins(8))
            addView(statusText, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(44), Gravity.TOP or Gravity.CENTER_HORIZONTAL).margins(8))
            addView(levelRing, LayoutParams(ringSize, ringSize, Gravity.CENTER))
            addView(doneButton, LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
            addView(retryButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(permissionButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(hintText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).margins(12))
        }
        addView(
            content,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                setMargins(context.dp(6), context.dp(6), context.dp(6), context.dp(6) + bottomGap)
            },
        )
    }

    /** Matches the keyboard it replaces so the editor above doesn't jump. */
    fun setFixedHeight(heightPx: Int) {
        if (fixedHeightPx != heightPx) {
            fixedHeightPx = heightPx
            requestLayout()
        }
    }

    fun start() {
        generation++
        stopRecorder()
        show(Mode.Idle)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            statusText.text = "Voice typing needs the microphone"
            show(Mode.Permission)
            return
        }
        if (!BarkModelAssets.isBundled(context)) {
            statusText.text = "This build has no speech model"
            hintText.text = "Run tools/bark/setup.sh, then rebuild the app."
            show(Mode.Idle)
            return
        }
        hintText.text = "${VoiceDefaults.MODEL_DISPLAY_NAME} runs on this phone"
        BarkEngine.warmUp(context)
        startRecorder()
    }

    /** Stops recording and drops the audio; the model is released after a short idle delay. */
    fun stop() {
        generation++
        stopRecorder()
        buffer = null
        mainHandler.removeCallbacks(tick)
        BarkEngine.scheduleRelease()
    }

    /** Call when the keyboard service is destroyed. */
    fun dispose() {
        generation++
        stopRecorder()
        mainHandler.removeCallbacksAndMessages(null)
        BarkEngine.scheduleRelease()
    }

    // Allocated per recording (up to 7.7 MB at the 120 s cap) and dropped right after.
    @Volatile private var buffer: FloatArray? = null
    @Volatile private var bufferedSamples = 0

    @SuppressLint("MissingPermission") // checked in start()
    private fun startRecorder() {
        val minBytes = AudioRecord.getMinBufferSize(VoiceDefaults.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                VoiceDefaults.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                max(minBytes, VoiceDefaults.SAMPLE_RATE * 4 / 2),
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        }.getOrNull()
        if (record == null) {
            showError("Couldn't open the microphone")
            return
        }
        val recordingBuffer = FloatArray(VoiceDefaults.SAMPLE_RATE * VoiceDefaults.MAX_RECORDING_SECONDS)
        buffer = recordingBuffer
        bufferedSamples = 0
        recording = true
        startedAtMs = System.currentTimeMillis()
        show(Mode.Listening)
        mainHandler.post(tick)
        val token = generation
        recorderThread = Thread({
            val chunk = FloatArray(VoiceDefaults.SAMPLE_RATE / 20) // 50 ms
            var lastLevelAt = 0L
            try {
                record.startRecording()
                while (recording) {
                    val read = record.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                    if (read <= 0) continue
                    val room = recordingBuffer.size - bufferedSamples
                    val copied = min(read, room)
                    System.arraycopy(chunk, 0, recordingBuffer, bufferedSamples, copied)
                    bufferedSamples += copied
                    val now = System.currentTimeMillis()
                    if (now - lastLevelAt >= VoiceDefaults.LEVEL_UPDATE_MS) {
                        lastLevelAt = now
                        var sum = 0f
                        for (i in 0 until read) sum += chunk[i] * chunk[i]
                        val level = min(1f, sqrt(sum / read) / VoiceDefaults.FULL_SCALE_RMS)
                        mainHandler.post { if (token == generation) levelRing.level = level }
                    }
                    if (copied < read) {
                        // Hit the length cap: transcribe what we have.
                        mainHandler.post { if (token == generation) finishRecording() }
                        break
                    }
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Recording failed", e)
                mainHandler.post { if (token == generation) showError("Recording stopped unexpectedly") }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }, "zn-voice-record").apply { start() }
    }

    private fun stopRecorder() {
        recording = false
        recorderThread?.join(500)
        recorderThread = null
        levelRing.level = 0f
    }

    private fun finishRecording() {
        if (!recording) return
        val elapsed = System.currentTimeMillis() - startedAtMs
        stopRecorder()
        mainHandler.removeCallbacks(tick)
        val recorded = buffer
        buffer = null
        if (elapsed < VoiceDefaults.MIN_RECORDING_MS || bufferedSamples == 0 || recorded == null) {
            showError("Didn't catch that")
            return
        }
        val samples = recorded.copyOf(bufferedSamples)
        statusText.text = "Typing it out…"
        show(Mode.Working)
        val token = generation
        BarkEngine.transcribe(context, samples) { result ->
            if (token != generation) return@transcribe
            result.fold(
                onSuccess = { text ->
                    if (text.isBlank()) showError("Didn't catch that") else callback?.onVoiceTextCaptured(text)
                },
                onFailure = { showError("Couldn't transcribe that") },
            )
        }
    }

    private fun showError(message: String) {
        statusText.text = message
        show(Mode.Retry)
    }

    private enum class Mode { Idle, Permission, Listening, Working, Retry }

    private fun show(mode: Mode) {
        doneButton.visibility = if (mode == Mode.Listening || mode == Mode.Working) VISIBLE else GONE
        doneButton.isEnabled = mode == Mode.Listening
        doneButton.alpha = if (mode == Mode.Working) 0.5f else 1f
        levelRing.visibility = doneButton.visibility
        retryButton.visibility = if (mode == Mode.Retry) VISIBLE else GONE
        permissionButton.visibility = if (mode == Mode.Permission) VISIBLE else GONE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = if (fixedHeightPx > 0) MeasureSpec.makeMeasureSpec(fixedHeightPx, MeasureSpec.EXACTLY) else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, spec)
    }

    private fun label(sizeSp: Float, color: Int) = TextView(context).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        gravity = Gravity.CENTER
    }

    private fun pillButton(text: String, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        setTextColor(ImeColors.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = false
        setPadding(context.dp(18), context.dp(12), context.dp(18), context.dp(12))
        background = ImePressFeedback.roundedBackground(context, ImeColors.FUNCTION, ImeColors.TEXT)
        setOnClickListener { onClick() }
        visibility = GONE
    }

    private fun LayoutParams.margins(dp: Int): LayoutParams = apply {
        val px = context.dp(dp)
        setMargins(px, px, px, px)
    }

    /** A soft ring behind Done that grows with the input level. */
    private class LevelRingView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ImeColors.ACCENT }
        private var shown = 0f
        var level = 0f
            set(value) {
                field = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            // Ease toward the latest level so the ring breathes instead of flickering.
            shown += (level - shown) * 0.35f
            val cx = width / 2f
            val cy = height / 2f
            val base = min(cx, cy) * BUTTON_DP / RING_DP
            val radius = base + (min(cx, cy) - base) * shown
            paint.alpha = 70
            canvas.drawCircle(cx, cy, radius, paint)
            if (kotlin.math.abs(level - shown) > 0.01f) postInvalidateOnAnimation()
        }
    }

    private companion object {
        const val TAG = "ZnVoicePanel"
        const val RING_DP = 168
        const val BUTTON_DP = 92
    }
}
