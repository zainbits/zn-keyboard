package dev.zain.znkeyboard.ime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.ai.edge.litert.Accelerator
import android.widget.LinearLayout
import dev.zain.znkeyboard.KeyboardSettings
import dev.zain.znkeyboard.KeyboardSettings.ScanEngine
import dev.zain.znkeyboard.R
import dev.zain.znkeyboard.ai.AiModelClient
import dev.zain.znkeyboard.ai.AiModelClient.isReady
import dev.zain.znkeyboard.constants.ImeColors
import dev.zain.znkeyboard.ocrlab.ppocr.OcrModelStore
import dev.zain.znkeyboard.ocrlab.ppocr.PpOcrEngine
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Camera viewfinder that replaces the keys. Capture reads exactly what the viewfinder shows, either
 * with PP-OCRv6 on the CPU or with the saved AI model chosen for Scan text, and hands the text back.
 * The camera and on-device model live only while it is shown.
 */
class OcrCameraPanelView(context: Context) : FrameLayout(context), LifecycleOwner {
    interface Callback {
        fun onOcrTextCaptured(text: String)
        fun onOcrPanelClosed()
        fun onOcrPermissionRequested()
    }

    var callback: Callback? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val mainHandler = Handler(Looper.getMainLooper())
    // One long-lived thread owns the model: init, OCR, and close never race.
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "zn-ocr") }
    private var engine: PpOcrEngine? = null
    private var controller: LifecycleCameraController? = null
    private var generation = 0
    private var busy = false
    private var torchOn = false
    private var fixedHeightPx = 0
    private var downloading = false
    private var active = false
    private var engineMode = ScanEngine.Device
    private var aiModel: KeyboardSettings.AgentProfile? = null
    private var fallbackToDevice = true

    private val previewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
        setBackgroundColor(Color.BLACK)
    }
    // Shows the frame at the moment of the tap, so the phone can move away while OCR runs.
    private val frozenFrame = android.widget.ImageView(context).apply {
        scaleType = android.widget.ImageView.ScaleType.FIT_XY
        visibility = GONE
    }
    private val statusText = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setPadding(context.dp(14), context.dp(6), context.dp(14), context.dp(6))
        background = pill(Color.argb(170, 0, 0, 0))
        visibility = GONE
    }
    private val shutterButton = TextView(context).apply {
        contentDescription = "Capture text"
        isClickable = true
        isFocusable = false
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(context.dp(4), Color.argb(150, 0, 0, 0))
        }
        setOnClickListener { capture() }
    }
    private val closeButton = ImePanelChrome.backButton(
        context = context,
        contentDescription = "Back to keyboard",
        backgroundColor = Color.argb(150, 0, 0, 0),
        textColor = Color.WHITE,
        onClick = { callback?.onOcrPanelClosed() },
    )
    private val torchButton = TextView(context).apply {
        text = "Light"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = false
        setPadding(context.dp(14), 0, context.dp(14), 0)
        background = pill(Color.argb(150, 0, 0, 0))
        setOnClickListener { toggleTorch() }
    }
    private val deviceEngineButton = engineSegment("On device") { setEngine(ScanEngine.Device) }
    private val aiEngineButton = engineSegment("AI") { setEngine(ScanEngine.Ai) }
    private val engineToggle = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = pill(Color.argb(150, 0, 0, 0))
        setPadding(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
        addView(deviceEngineButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        addView(aiEngineButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        visibility = GONE
    }

    private val permissionButton = TextView(context).apply {
        text = "Allow camera access"
        setTextColor(ImeColors.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = false
        setPadding(context.dp(18), context.dp(12), context.dp(18), context.dp(12))
        background = ImePressFeedback.roundedBackground(context, ImeColors.FUNCTION, ImeColors.TEXT)
        setOnClickListener { callback?.onOcrPermissionRequested() }
        visibility = GONE
    }

    private val downloadButton = TextView(context).apply {
        text = "Download text scanner · ${(OcrModelStore.totalBytes + 500_000) / 1_000_000} MB"
        setTextColor(ImeColors.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = false
        setPadding(context.dp(18), context.dp(12), context.dp(18), context.dp(12))
        background = ImePressFeedback.roundedBackground(context, ImeColors.FUNCTION, ImeColors.TEXT)
        setOnClickListener { downloadModels() }
        visibility = GONE
    }

    init {
        setBackgroundColor(ImeColors.BACKGROUND)
        isClickable = true
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        val bottomGap = ImeLayout.bottomSystemControlGapPx(context).roundToInt()
        val viewfinderFrame = FrameLayout(context).apply {
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = GradientDrawable().apply {
                cornerRadius = context.dp(ImeLayout.KEY_RADIUS_DP * 2).toFloat()
                setColor(Color.BLACK)
            }
            addView(previewView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(frozenFrame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(closeButton, LayoutParams(context.dp(44), context.dp(44), Gravity.TOP or Gravity.START).margins(8))
            addView(torchButton, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(44), Gravity.TOP or Gravity.END).margins(8))
            addView(engineToggle, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(40), Gravity.TOP or Gravity.CENTER_HORIZONTAL).margins(10))
            addView(statusText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(permissionButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(downloadButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(shutterButton, LayoutParams(context.dp(64), context.dp(64), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).margins(12))
        }
        addView(
            viewfinderFrame,
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
        active = true
        generation++
        busy = false
        unfreeze()
        showStatus(null)
        permissionButton.visibility = GONE
        downloadButton.visibility = GONE
        aiModel = KeyboardSettings.readScanProfile(context)?.takeIf { it.isReady() && it.imageInput != false }
        engineMode = if (aiModel != null) KeyboardSettings.readScanEngine(context) else ScanEngine.Device
        fallbackToDevice = KeyboardSettings.readScanFallback(context)
        updateEngineToggle()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionButton.visibility = VISIBLE
            shutterButton.visibility = GONE
            torchButton.visibility = GONE
            engineToggle.visibility = GONE
            return
        }
        if (engineMode == ScanEngine.Device && !OcrModelStore.isReady(context)) {
            shutterButton.visibility = GONE
            torchButton.visibility = GONE
            if (downloading) showStatus("Downloading text scanner…") else downloadButton.visibility = VISIBLE
            return
        }
        shutterButton.visibility = VISIBLE
        torchButton.visibility = VISIBLE
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        val camera = controller ?: LifecycleCameraController(context).also { created ->
            created.setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            // Takes the frame already buffered at the tap (falls back to minimum latency where
            // unsupported), so moving the phone right after tapping doesn't blur the capture.
            created.imageCaptureMode = ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
            // ~3 MP is enough: the viewfinder crop of a 2048x1536 still is ~1536 px wide, about the
            // 1568 px AI models take, and fine for PP-OCR. Full sensor stills only cost time and memory.
            created.imageCaptureResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(CAPTURE_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                )
                .build()
            created.isPinchToZoomEnabled = true
            created.isTapToFocusEnabled = true
            controller = created
        }
        camera.bindToLifecycle(this)
        previewView.controller = camera
        torchOn = false
        updateTorchLabel()

        if (OcrModelStore.isReady(context)) worker.execute { ensureEngine() }
    }

    private fun setEngine(engine: ScanEngine) {
        if (busy || engine == engineMode) return
        KeyboardSettings.saveScanEngine(context, engine)
        // Re-runs the setup: on-device mode may still need its model downloaded.
        stop()
        start()
    }

    private fun updateEngineToggle() {
        val model = aiModel
        engineToggle.visibility = if (model == null) GONE else VISIBLE
        aiEngineButton.text = model?.name ?: "AI"
        listOf(deviceEngineButton to ScanEngine.Device, aiEngineButton to ScanEngine.Ai).forEach { (button, engine) ->
            val selected = engine == engineMode
            button.setTextColor(if (selected) Color.BLACK else Color.WHITE)
            button.background = if (selected) pill(Color.WHITE) else null
        }
    }

    private fun engineSegment(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        maxWidth = context.dp(140)
        isClickable = true
        isFocusable = false
        setPadding(context.dp(12), 0, context.dp(12), 0)
        setOnClickListener { onClick() }
    }

    /** One-time model download; keeps going if the panel closes, and resumes the camera if it is still open. */
    private fun downloadModels() {
        if (downloading) return
        downloading = true
        downloadButton.visibility = GONE
        showStatus("Downloading text scanner…")
        Thread({
            val result = runCatching {
                OcrModelStore.download(context) { progress ->
                    mainHandler.post {
                        if (active) showStatus("Downloading text scanner… ${(progress * 100).toInt()}%")
                    }
                }
            }.onFailure { Log.w(TAG, "Model download failed", it) }
            mainHandler.post {
                downloading = false
                if (!active) return@post
                if (result.isSuccess) {
                    start()
                } else {
                    showStatus("Download failed. Check your connection.")
                    downloadButton.visibility = VISIBLE
                }
            }
        }, "zn-ocr-download").start()
    }

    /** Releases the camera and the model; safe to call repeatedly. */
    fun stop() {
        active = false
        generation++
        busy = false
        unfreeze()
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
        previewView.controller = null
        controller?.unbind()
        controller = null
        if (!worker.isShutdown) {
            worker.execute {
                engine?.close()
                engine = null
            }
        }
    }

    /** Call when the keyboard service is destroyed. */
    fun dispose() {
        stop()
        worker.shutdown()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    /** Worker thread only. */
    private fun ensureEngine(): PpOcrEngine? = engine ?: runCatching { PpOcrEngine(OcrModelStore.directory(context), Accelerator.CPU) }
        .onFailure { Log.w(TAG, "PP-OCR init failed", it) }
        .getOrNull()
        .also { engine = it }

    private fun capture() {
        val camera = controller ?: return
        if (busy) return
        busy = true
        shutterButton.isEnabled = false
        freeze()
        showStatus("Hold still…")
        val token = generation
        camera.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureStarted() {
                mainHandler.post { if (token == generation) showStatus("Reading…") }
            }

            override fun onCaptureSuccess(image: ImageProxy) {
                val model = aiModel.takeIf { engineMode == ScanEngine.Ai }
                val text = runCatching {
                    val square = image.use { visibleSquare(it) }
                    try {
                        if (model != null) readWithAi(token, model, square) else readOnDevice(square)
                    } finally {
                        square.recycle()
                    }
                }.onFailure { Log.w(TAG, "Scan failed", it) }
                mainHandler.post { finishCapture(token, text) }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.w(TAG, "Capture failed", exception)
                mainHandler.post { finishCapture(token, Result.failure(exception)) }
            }
        })
    }

    /** Worker thread only. */
    private fun readOnDevice(square: Bitmap): String {
        val ocr = ensureEngine() ?: error("OCR model unavailable")
        return ocr.recognize(square).text.trim()
    }

    /** Worker thread only. Falls back to PP-OCRv6 when allowed and the AI request fails. */
    private fun readWithAi(token: Int, model: KeyboardSettings.AgentProfile, square: Bitmap): String {
        mainHandler.post { if (token == generation) showStatus("Reading with ${model.name}…") }
        return try {
            AiModelClient.readTextFromImage(model.toProviderSettings(), jpegForAi(square))
        } catch (error: Exception) {
            Log.w(TAG, "AI scan failed", error)
            if (!fallbackToDevice || !OcrModelStore.isReady(context)) throw AiScanException(model.name, error)
            mainHandler.post { if (token == generation) showStatus("${model.name} failed. Reading on this phone…") }
            readOnDevice(square)
        }
    }

    private class AiScanException(val modelName: String, cause: Throwable) : Exception(cause)

    private fun finishCapture(token: Int, result: Result<String>) {
        if (token != generation) return
        busy = false
        shutterButton.isEnabled = true
        result.fold(
            onSuccess = { text ->
                if (text.isEmpty()) {
                    unfreeze()
                    showStatus("No text found. Move closer and try again.")
                } else {
                    showStatus(null)
                    callback?.onOcrTextCaptured(text)
                }
            },
            onFailure = { error ->
                unfreeze()
                showStatus(
                    if (error is AiScanException) "Couldn't reach ${error.modelName}. Try again." else "Couldn't read that. Try again.",
                )
            },
        )
    }

    private fun freeze() {
        val frame = previewView.bitmap ?: return
        frozenFrame.setImageBitmap(frame)
        frozenFrame.visibility = VISIBLE
    }

    private fun unfreeze() {
        frozenFrame.visibility = GONE
        frozenFrame.setImageDrawable(null)
    }

    private fun toggleTorch() {
        val camera = controller ?: return
        torchOn = !torchOn
        camera.enableTorch(torchOn)
        updateTorchLabel()
    }

    private fun updateTorchLabel() {
        torchButton.text = if (torchOn) "Light off" else "Light"
    }

    private fun showStatus(message: String?) {
        statusText.text = message
        statusText.visibility = if (message == null) GONE else VISIBLE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = if (fixedHeightPx > 0) MeasureSpec.makeMeasureSpec(fixedHeightPx, MeasureSpec.EXACTLY) else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, spec)
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = context.dp(22).toFloat()
        setColor(color)
    }

    private fun LayoutParams.margins(dp: Int): LayoutParams = apply {
        val px = context.dp(dp)
        setMargins(px, px, px, px)
    }

    private companion object {
        const val TAG = "ZnOcrPanel"

        /**
         * The still, cropped to what the viewfinder showed (CameraController's viewport), upright,
         * and letterboxed onto a white square for the fixed-size PP-OCR detector.
         */
        fun visibleSquare(image: ImageProxy): Bitmap {
            var bitmap = image.toBitmap()
            val crop = image.cropRect
            if (bitmap.width == image.width && bitmap.height == image.height &&
                (crop.width() != image.width || crop.height() != image.height)
            ) {
                bitmap = replace(bitmap, Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width(), crop.height()))
            }
            val rotation = image.imageInfo.rotationDegrees
            if (rotation != 0) {
                bitmap = replace(
                    bitmap,
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true),
                )
            }
            val longest = max(bitmap.width, bitmap.height)
            val scale = if (longest > MAX_SIDE) MAX_SIDE.toFloat() / longest else 1f
            val side = (longest * scale).roundToInt()
            val square = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            Canvas(square).apply {
                drawColor(Color.WHITE)
                val width = bitmap.width * scale
                val height = bitmap.height * scale
                val left = (side - width) / 2f
                val top = (side - height) / 2f
                drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + width, top + height), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            }
            bitmap.recycle()
            return square
        }

        /** Frees [old] right away instead of waiting for GC; each full-size copy is several MB. */
        private fun replace(old: Bitmap, new: Bitmap): Bitmap {
            if (new !== old) old.recycle()
            return new
        }

        val CAPTURE_SIZE = Size(2048, 1536)
        const val MAX_SIDE = 2400
        const val AI_MAX_SIDE = 1568
        const val AI_JPEG_QUALITY = 85

        /** The capture downscaled for an AI model: long side 1568 px, JPEG. */
        fun jpegForAi(square: Bitmap): ByteArray {
            val scale = min(1f, AI_MAX_SIDE.toFloat() / max(square.width, square.height))
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(square, (square.width * scale).roundToInt(), (square.height * scale).roundToInt(), true)
            } else {
                square
            }
            return ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, AI_JPEG_QUALITY, out)
                if (scaled !== square) scaled.recycle()
                out.toByteArray()
            }
        }
    }
}
