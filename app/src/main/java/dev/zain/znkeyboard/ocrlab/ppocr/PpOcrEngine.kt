// SPDX-License-Identifier: Apache-2.0
// Runtime glue adapted from litert-community/PP-OCRv6-Small-LiteRT examples/android/PpocrV6Gpu.kt.
package dev.zain.znkeyboard.ocrlab.ppocr

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import org.json.JSONArray
import kotlin.math.ceil

/**
 * PP-OCRv6 Small detection + recognition on LiteRT. Create, use, and close on one thread:
 * the GPU path requires its whole lifetime on the same thread.
 */
class PpOcrEngine(assets: AssetManager, val accelerator: Accelerator) : AutoCloseable {
    class Line(val text: String, val score: Double?, val compressed: Boolean)

    class Result(
        val lines: List<Line>,
        val detectMs: Double,
        val geometryMs: Double,
        val recognizeMs: Double,
        val totalMs: Double,
    ) {
        val text: String get() = lines.joinToString("\n") { it.text }
    }

    private val environment = Environment.create()
    private val graphs = LinkedHashMap<String, Graph>()
    private val vocabulary: List<String>
    private val allowed: BooleanArray
    val initMs: Double

    init {
        val start = System.nanoTime()
        try {
            vocabulary = assets.open("$ASSET_DIR/characters.json").use { stream ->
                val json = JSONArray(stream.readBytes().toString(Charsets.UTF_8))
                List(json.length()) { json.getString(it) }
            }
            require(vocabulary.size == VOCABULARY_SIZE && vocabulary.first() == "blank" && vocabulary.last() == " ") {
                "Unexpected PP-OCRv6 vocabulary"
            }
            allowed = BooleanArray(vocabulary.size) { it == 0 || isEnglishToken(vocabulary[it]) }
            val options = CompiledModel.Options(setOf(accelerator)).apply {
                when (accelerator) {
                    Accelerator.GPU -> gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP32)
                    else -> cpuOptions = CompiledModel.CpuOptions(numThreads = CPU_THREADS)
                }
            }
            for (name in listOf(DET, REC_320, REC_640, REC_960)) {
                graphs[name] = Graph(CompiledModel.create(assets, "$ASSET_DIR/$name", options, environment))
            }
        } catch (error: Throwable) {
            runCatching { close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        initMs = (System.nanoTime() - start) / 1e6
    }

    /** [square] is an upright, opaque, square image; boxes are mapped back to its full resolution. */
    fun recognize(square: Bitmap): Result {
        require(square.width == square.height) { "PP-OCR lab input must be square" }
        val start = System.nanoTime()

        val detInput = scaleDown(square, DET_SIDE)
        val probabilities = graphs.getValue(DET).run(OcrPreprocessing.det(rgbBytes(detInput)))
        if (detInput !== square) detInput.recycle()
        val detected = System.nanoTime()

        val quads = TextGeometry.detect(probabilities, DET_SIDE, DET_SIDE, square.width, square.height)
        val crops = quads.mapNotNull { cropQuad(square, it) }
        val geometry = System.nanoTime()

        val lines = crops.map { (crop, compressed) ->
            val width = crop.width
            val height = crop.height
            val natural = ceil(48.0 * width / height).toInt()
            val graph = when {
                natural <= 320 -> 320
                natural <= 640 -> 640
                else -> 960
            }
            val probs = graphs.getValue(recName(graph)).run(OcrPreprocessing.rec(rgbBytes(crop), width, height, graph))
            crop.recycle()
            decodeCtc(probs, compressed)
        }.filter(::isConfidentLine)
        val end = System.nanoTime()

        return Result(
            lines = lines,
            detectMs = (detected - start) / 1e6,
            geometryMs = (geometry - detected) / 1e6,
            recognizeMs = (end - geometry) / 1e6,
            totalMs = (end - start) / 1e6,
        )
    }

    /**
     * PaddleX min-area perspective crop. Uses bilinear filtering instead of OpenCV INTER_CUBIC, and
     * squeezes lines wider than the 960 graph (the default PaddleOCR policy) rather than rejecting them.
     */
    private fun cropQuad(source: Bitmap, quad: TextQuad): Pair<Bitmap, Boolean>? {
        val (rect, size) = TextGeometry.cropRect(quad)
        val (width, height) = size
        if (width < 1 || height < 1) return null
        val src = floatArrayOf(rect.xs[0], rect.ys[0], rect.xs[1], rect.ys[1], rect.xs[2], rect.ys[2], rect.xs[3], rect.ys[3])
        val dst = floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat())
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) return null
        var crop = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(crop).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(source, matrix, CROP_PAINT)
        }
        if (height.toFloat() / width >= 1.5f) {
            val rotated = Bitmap.createBitmap(crop, 0, 0, width, height, Matrix().apply { postRotate(-90f) }, true)
            crop.recycle()
            crop = rotated
        }
        val maxWidth = crop.height * MAX_REC_WIDTH / REC_HEIGHT
        if (ceil(REC_HEIGHT.toDouble() * crop.width / crop.height) > MAX_REC_WIDTH && maxWidth >= 1) {
            val squeezed = Bitmap.createScaledBitmap(crop, maxWidth, crop.height, true)
            crop.recycle()
            return squeezed to true
        }
        return crop to false
    }

    private fun decodeCtc(probabilities: FloatArray, compressed: Boolean): Line {
        val text = StringBuilder()
        var previous = -1
        var confidence = 0.0
        var emitted = 0
        for (t in 0 until probabilities.size / VOCABULARY_SIZE) {
            val offset = t * VOCABULARY_SIZE
            var best = 0
            for (c in 1 until VOCABULARY_SIZE) {
                if (allowed[c] && probabilities[offset + c] > probabilities[offset + best]) best = c
            }
            if (best != previous && best != 0) {
                text.append(vocabulary[best])
                confidence += probabilities[offset + best]
                emitted++
            }
            previous = best
        }
        return Line(text.toString(), if (emitted == 0) null else confidence / emitted, compressed)
    }

    override fun close() {
        graphs.values.reversed().forEach { runCatching { it.close() } }
        graphs.clear()
        runCatching { environment.close() }
    }

    private class Graph(private val model: CompiledModel) : AutoCloseable {
        private val inputs: List<TensorBuffer> = model.createInputBuffers()
        private val outputs: List<TensorBuffer> = model.createOutputBuffers()

        fun run(values: FloatArray): FloatArray {
            inputs.single().writeFloat(values)
            model.run(inputs, outputs)
            // run() may only enqueue on GPU; readFloat() completes the work.
            return outputs.single().readFloat()
        }

        override fun close() {
            (inputs + outputs).forEach { runCatching { it.close() } }
            model.close()
        }
    }

    companion object {
        const val ASSET_DIR = "ppocrv6"
        private const val DET = "ppocrv6_small_det_640_fp32.tflite"
        private const val REC_320 = "ppocrv6_small_rec_320_fp32.tflite"
        private const val REC_640 = "ppocrv6_small_rec_640_fp32.tflite"
        private const val REC_960 = "ppocrv6_small_rec_960_fp32.tflite"
        private const val DET_SIDE = 640
        private const val REC_HEIGHT = 48
        private const val MAX_REC_WIDTH = 960
        private const val VOCABULARY_SIZE = 18710
        private const val CPU_THREADS = 4

        // Non-Latin text forced into English comes out as low-confidence lines or short fragments.
        private const val MIN_LINE_CONFIDENCE = 0.5
        private const val MIN_SHORT_LINE_CONFIDENCE = 0.8
        private const val SHORT_LINE_CHARS = 4

        private fun isConfidentLine(line: Line): Boolean {
            val text = line.text.trim()
            val score = line.score ?: return false
            if (text.isEmpty() || score < MIN_LINE_CONFIDENCE) return false
            return text.length > SHORT_LINE_CHARS || score >= MIN_SHORT_LINE_CONFIDENCE
        }
        private const val ENGLISH_SYMBOLS = "₹€£°•–—‘’“”…×©®™"

        /** English only: printable ASCII plus a few common symbols. Other scripts can't be emitted at all. */
        private fun isEnglishToken(token: String): Boolean =
            token.isNotEmpty() && token.all { it in ' '..'~' || it in ENGLISH_SYMBOLS }
        private val CROP_PAINT = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        fun modelsPresent(assets: AssetManager): Boolean =
            runCatching { assets.list(ASSET_DIR)?.toSet().orEmpty() }.getOrDefault(emptySet())
                .containsAll(listOf(DET, REC_320, REC_640, REC_960, "characters.json"))

        private fun recName(width: Int) = when (width) {
            320 -> REC_320
            640 -> REC_640
            else -> REC_960
        }

        /** Halves with filtering until within 2x, then scales once, to avoid aliasing on large downscales. */
        private fun scaleDown(source: Bitmap, side: Int): Bitmap {
            var current = source
            while (current.width >= side * 2) {
                val next = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
                if (current !== source) current.recycle()
                current = next
            }
            if (current.width == side) return current
            val result = Bitmap.createScaledBitmap(current, side, side, true)
            if (current !== source) current.recycle()
            return result
        }

        private fun rgbBytes(bitmap: Bitmap): ByteArray {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val bytes = ByteArray(pixels.size * 3)
            for (i in pixels.indices) {
                val p = pixels[i]
                bytes[i * 3] = (p shr 16).toByte()
                bytes[i * 3 + 1] = (p shr 8).toByte()
                bytes[i * 3 + 2] = p.toByte()
            }
            return bytes
        }
    }
}
