package dev.zain.znkeyboard.ocrlab

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.zain.znkeyboard.R
import dev.zain.znkeyboard.SettingsSectionCard
import dev.zain.znkeyboard.ZnKeyboardColors
import dev.zain.znkeyboard.ZnKeyboardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Side-by-side camera OCR comparison for choosing the keyboard's OCR engine. Runs in its own
 * process (see the manifest) so model memory and native crashes never touch the keyboard.
 */
class OcrLabActivity : ComponentActivity() {
    private val viewModel: OcrLabViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZnKeyboardTheme {
                OcrLabScreen(viewModel, onBack = ::finish)
            }
        }
    }
}

private const val GUIDE_FRACTION = 0.86f
private const val VIEWFINDER_ASPECT = 3f / 4f
private const val MAX_INPUT_SIDE = 2400

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OcrLabScreen(viewModel: OcrLabViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { decodePicked(context, uri) }.getOrNull() }
            if (bitmap == null) snackbar.showSnackbar("Couldn't open that image") else viewModel.process(bitmap, "photo picker")
        }
    }
    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            snackbar.showSnackbar(it)
            viewModel.message = null
        }
    }

    Scaffold(
        containerColor = ZnKeyboardColors.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Camera OCR test") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (viewModel.input != null && !viewModel.running) viewModel.reset() else onBack()
                    }) {
                        Icon(painterResource(R.drawable.ic_chevron_left_24), contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ZnKeyboardColors.Background),
            )
        },
    ) { padding ->
        val input = viewModel.input
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { EngineStatusCard(viewModel) }
            if (input == null) {
                item {
                    if (hasCamera) {
                        CameraCapture(
                            onCaptured = { viewModel.process(it, "camera") },
                            onError = { viewModel.message = it },
                        )
                    } else {
                        SettingsSectionCard {
                            Text("Camera access is needed to capture text.", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = {
                            pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Text("Use a photo") }
                        Text("Save to Downloads", modifier = Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = viewModel.saveCaptures, onCheckedChange = { viewModel.saveCaptures = it })
                    }
                }
            } else {
                item {
                    Image(
                        bitmap = input.asImageBitmap(),
                        contentDescription = "Captured text area",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp)),
                    )
                }
                items(OcrLabViewModel.Engine.entries.toList()) { engine ->
                    ResultCard(engine, viewModel.runStates[engine])
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = viewModel::reset,
                            enabled = !viewModel.running,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Capture another") }
                        viewModel.savedName?.let {
                            Text("Saved to $it", color = ZnKeyboardColors.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineStatusCard(viewModel: OcrLabViewModel) {
    SettingsSectionCard(verticalSpacing = 4.dp) {
        OcrLabViewModel.Engine.entries.forEach { engine ->
            val status = when (val state = viewModel.engineStates[engine]) {
                is OcrLabViewModel.EngineState.Ready -> "ready · init ${state.initMs.roundToInt()} ms"
                is OcrLabViewModel.EngineState.Failed -> "unavailable · ${state.message}"
                else -> "loading…"
            }
            Text("${engine.label}: $status", style = MaterialTheme.typography.bodySmall, color = ZnKeyboardColors.Muted)
        }
    }
}

@Composable
private fun ResultCard(engine: OcrLabViewModel.Engine, state: OcrLabViewModel.RunState?) {
    val context = LocalContext.current
    SettingsSectionCard(verticalSpacing = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                engine.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (state is OcrLabViewModel.RunState.Done) {
                TextButton(onClick = { copy(context, state.text) }) { Text("Copy") }
            }
        }
        when (state) {
            is OcrLabViewModel.RunState.Done -> {
                Text(state.summary, style = MaterialTheme.typography.labelMedium, color = ZnKeyboardColors.Accent)
                SelectionContainer {
                    Text(
                        state.text.ifEmpty { "(no text found)" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            is OcrLabViewModel.RunState.Failed ->
                Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            OcrLabViewModel.RunState.Running -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            else -> Text("Waiting…", style = MaterialTheme.typography.bodySmall, color = ZnKeyboardColors.Muted)
        }
    }
}

@Composable
private fun CameraCapture(onCaptured: (Bitmap) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { captureExecutor.shutdown() } }
    val resolution = remember {
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(resolution)
            .build()
    }

    LaunchedEffect(lifecycleOwner) {
        val provider = awaitCameraProvider(context)
        val preview = Preview.Builder().setResolutionSelector(resolution).build().apply {
            setSurfaceProvider { surfaceRequest = it }
        }
        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            awaitCancellation()
        } finally {
            provider.unbindAll()
            camera = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(VIEWFINDER_ASPECT)
                .clip(RoundedCornerShape(20.dp)),
        ) {
            surfaceRequest?.let {
                CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Canvas(Modifier.fillMaxSize()) {
                val side = size.width * GUIDE_FRACTION
                val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
                drawRoundRect(
                    color = Color.White,
                    topLeft = topLeft,
                    size = Size(side, side),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
        Text(
            "Fit the text inside the square. Only the square is read.",
            style = MaterialTheme.typography.bodySmall,
            color = ZnKeyboardColors.Muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = camera != null && !capturing,
                modifier = Modifier.weight(1f),
                onClick = {
                    capturing = true
                    imageCapture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val square = runCatching { guideCrop(image) }
                            image.close()
                            capturing = false
                            square.onSuccess(onCaptured).onFailure { onError("Capture failed: ${it.message}") }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            capturing = false
                            onError("Capture failed: ${exception.message}")
                        }
                    })
                },
            ) { Text(if (capturing) "Capturing…" else "Capture") }
            FilledTonalButton(
                enabled = camera?.cameraInfo?.hasFlashUnit() == true,
                onClick = {
                    torchOn = !torchOn
                    camera?.cameraControl?.enableTorch(torchOn)
                },
            ) { Text(if (torchOn) "Light off" else "Light on") }
        }
    }
}

private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            { runCatching { future.get() }.fold(continuation::resume, continuation::resumeWithException) },
            ContextCompat.getMainExecutor(context),
        )
    }

/** Rotates the still upright and cuts out the on-screen guide square, assuming a center-cropped 3:4 viewfinder. */
private fun guideCrop(image: ImageProxy): Bitmap {
    val raw = image.toBitmap()
    val rotation = image.imageInfo.rotationDegrees
    val upright = if (rotation == 0) raw else {
        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            .also { raw.recycle() }
    }
    val scale = max(VIEWFINDER_ASPECT / upright.width, 1f / upright.height)
    val side = min(min(upright.width, upright.height), (GUIDE_FRACTION * VIEWFINDER_ASPECT / scale).roundToInt())
    val left = (upright.width - side) / 2
    val top = (upright.height - side) / 2
    val square = Bitmap.createBitmap(upright, left, top, side, side)
    if (square !== upright) upright.recycle()
    return capSide(square)
}

/** Decodes a picked photo upright (EXIF-aware) and letterboxes it onto a white square. */
private fun decodePicked(context: Context, uri: Uri): Bitmap {
    val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = max(info.size.width, info.size.height)
        if (longest > MAX_INPUT_SIDE) {
            val ratio = MAX_INPUT_SIDE.toFloat() / longest
            decoder.setTargetSize((info.size.width * ratio).roundToInt(), (info.size.height * ratio).roundToInt())
        }
    }
    val side = max(decoded.width, decoded.height)
    val square = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    Canvas(square).apply {
        drawColor(android.graphics.Color.WHITE)
        drawBitmap(decoded, ((side - decoded.width) / 2).toFloat(), ((side - decoded.height) / 2).toFloat(), null)
    }
    decoded.recycle()
    return square
}

private fun capSide(bitmap: Bitmap): Bitmap {
    if (bitmap.width <= MAX_INPUT_SIDE) return bitmap
    return Bitmap.createScaledBitmap(bitmap, MAX_INPUT_SIDE, MAX_INPUT_SIDE, true).also { bitmap.recycle() }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("OCR", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
