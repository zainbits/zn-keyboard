package dev.zain.znkeyboard.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dev.zain.znkeyboard.constants.VoiceDefaults
import java.io.FileInputStream
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.nio.channels.FileChannel

/**
 * Bark 3.0 Clean speech-to-text, bundled in the APK under assets/bark/ (see tools/bark/setup.sh).
 *
 * preprocessor.onnx is the original model's own feature extractor (HF Parakeet), so features match
 * the reference exactly; sherpa-onnx's NeMo frontend differs and cost ~5% word errors. The encoder
 * is an exact 4-bit packing of Bark's weights (MatMulNBits), decoder/joiner are fp32.
 */
object BarkModelAssets {
    private const val DIR = "bark"
    const val PREPROCESSOR = "$DIR/preprocessor.onnx"
    const val ENCODER = "$DIR/encoder.onnx"
    const val DECODER = "$DIR/decoder.onnx"
    const val JOINER = "$DIR/joiner.onnx"
    const val TOKENS = "$DIR/tokens.txt"
    private val REQUIRED = listOf("preprocessor.onnx", "encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")

    /** True when this build carries the model; a build made without running setup.sh has none. */
    fun isBundled(context: Context): Boolean = runCatching {
        context.assets.list(DIR)?.toSet()?.containsAll(REQUIRED) == true
    }.getOrDefault(false)

    /** Size of the model files in bytes, or 0 when absent. ONNX assets are stored uncompressed, so openFd works. */
    fun bundledBytes(context: Context): Long = runCatching {
        listOf(PREPROCESSOR, ENCODER, DECODER, JOINER).sumOf { path ->
            context.assets.openFd(path).use { it.length }
        }
    }.getOrDefault(0L)
}

/**
 * One loaded recognizer (~1 GB of native memory). Not thread-safe: create, use and close it on a
 * single worker thread (BarkEngine does). Models are memory-mapped straight from the APK, so no
 * copy lands on the Java heap.
 */
internal class BarkRecognizer(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(VoiceDefaults.RECOGNIZER_THREADS)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val preprocessor = session(context, BarkModelAssets.PREPROCESSOR)
    private val encoder = session(context, BarkModelAssets.ENCODER)
    private val decoder = session(context, BarkModelAssets.DECODER)
    private val joiner = session(context, BarkModelAssets.JOINER)
    private val decoderInputs = decoder.inputNames.toList() // targets, target_length, h, c
    private val joinerInputs = joiner.inputNames.toList() // encoder_outputs, decoder_outputs
    private val tokens: Array<String> = loadTokens(context)
    private val blank = tokens.size - 1

    private fun session(context: Context, asset: String): OrtSession {
        val buffer = context.assets.openFd(asset).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
        return env.createSession(buffer, options)
    }

    /** Blocking. [samples] are mono floats in -1..1 at 16 kHz. */
    fun transcribe(samples: FloatArray, sampleRate: Int): String {
        require(sampleRate == VoiceDefaults.SAMPLE_RATE) { "Bark expects ${VoiceDefaults.SAMPLE_RATE} Hz audio" }
        val n = samples.size.toLong()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, n)).use { wave ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(n)), longArrayOf(1)).use { length ->
                preprocessor.run(mapOf("waveform" to wave, "length" to length)).use { features ->
                    encoder.run(
                        mapOf("audio_signal" to features.get(0) as OnnxTensor, "length" to features.get(1) as OnnxTensor),
                    ).use { encoded ->
                        val out = encoded.get(0) as OnnxTensor // (1, 1024, T')
                        val frames = (encoded.get(1) as OnnxTensor).longBuffer.get(0).toInt()
                        return decode(out.floatBuffer, out.info.shape[1].toInt(), out.info.shape[2].toInt(), frames)
                    }
                }
            }
        }
    }

    /** TDT greedy search, the same rules as sherpa-onnx (max 5 symbols per frame). */
    private fun decode(encoder: FloatBuffer, dim: Int, stride: Int, frames: Int): String {
        val vocab = blank + 1
        val frame = FloatArray(dim)
        var h = FloatArray(2 * PRED_DIM)
        var c = FloatArray(2 * PRED_DIM)
        var pred = FloatArray(PRED_DIM)

        fun step(token: Int) {
            OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(token)), longArrayOf(1, 1)).use { target ->
                OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(1)), longArrayOf(1)).use { targetLength ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(h), longArrayOf(2, 1, PRED_DIM.toLong())).use { hIn ->
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(c), longArrayOf(2, 1, PRED_DIM.toLong())).use { cIn ->
                            decoder.run(
                                mapOf(decoderInputs[0] to target, decoderInputs[1] to targetLength, decoderInputs[2] to hIn, decoderInputs[3] to cIn),
                            ).use { result ->
                                pred = (result.get(0) as OnnxTensor).floatBuffer.let { b -> FloatArray(PRED_DIM).also { b.get(it) } }
                                h = (result.get(2) as OnnxTensor).floatBuffer.let { b -> FloatArray(2 * PRED_DIM).also { b.get(it) } }
                                c = (result.get(3) as OnnxTensor).floatBuffer.let { b -> FloatArray(2 * PRED_DIM).also { b.get(it) } }
                            }
                        }
                    }
                }
            }
        }

        step(blank)
        val out = ArrayList<Int>()
        var t = 0
        var symbolsThisFrame = 0
        while (t < frames) {
            for (d in 0 until dim) frame[d] = encoder.get(d * stride + t)
            val logits = OnnxTensor.createTensor(env, FloatBuffer.wrap(frame), longArrayOf(1, dim.toLong(), 1)).use { enc ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(pred), longArrayOf(1, PRED_DIM.toLong(), 1)).use { dec ->
                    joiner.run(mapOf(joinerInputs[0] to enc, joinerInputs[1] to dec)).use { result ->
                        val buffer = (result.get(0) as OnnxTensor).floatBuffer
                        FloatArray(buffer.remaining()).also { buffer.get(it) }
                    }
                }
            }
            val token = argmax(logits, 0, vocab)
            var skip = argmax(logits, vocab, logits.size) - vocab // durations are 0..4
            if (token != blank) {
                out += token
                step(token)
                symbolsThisFrame++
            }
            if (skip > 0) symbolsThisFrame = 0
            if (symbolsThisFrame >= MAX_SYMBOLS_PER_FRAME) {
                symbolsThisFrame = 0
                skip = 1
            }
            if (token == blank && skip == 0) {
                symbolsThisFrame = 0
                skip = 1
            }
            t += skip
        }
        return buildString { out.forEach { append(tokens[it]) } }.replace('▁', ' ').trim()
    }

    private fun argmax(values: FloatArray, from: Int, until: Int): Int {
        var best = from
        for (i in from + 1 until until) if (values[i] > values[best]) best = i
        return best
    }

    private fun loadTokens(context: Context): Array<String> {
        val byId = HashMap<Int, String>()
        context.assets.open(BarkModelAssets.TOKENS).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val split = line.lastIndexOf(' ')
                if (split > 0) line.substring(split + 1).toIntOrNull()?.let { byId[it] = line.substring(0, split) }
            }
        }
        return Array((byId.keys.maxOrNull() ?: -1) + 1) { byId[it].orEmpty() }
    }

    override fun close() {
        listOf(preprocessor, encoder, decoder, joiner).forEach { runCatching { it.close() } }
        options.close()
    }

    private companion object {
        const val PRED_DIM = 640
        const val MAX_SYMBOLS_PER_FRAME = 5
    }
}
