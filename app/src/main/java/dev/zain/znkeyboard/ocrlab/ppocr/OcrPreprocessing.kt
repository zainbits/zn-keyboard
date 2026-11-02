/*
 * Narrow adaptation of the official PP-OCRv6 Small Transformers processors.
 * Copyright 2026 The PaddlePaddle Team and The HuggingFace Inc. team.
 * Licensed under the Apache License, Version 2.0:
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Sources pinned under references/upstream/source/transformers:
 * - image_processing_backends.py: _fuse_mean_std_and_rescale_factor
 * - pp_ocrv5_server_det/image_processing_pp_ocrv5_server_det.py (official v6 reuse)
 * - pp_ocrv6_small_rec/image_processing_pp_ocrv6_small_rec.py
 * Verification: scripts/verify_kotlin_preprocess.py and
 * results/kotlin_preprocess_verification.json.
 *
 * Distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for permissions and limitations.
 */

package dev.zain.znkeyboard.ocrlab.ppocr

import kotlin.math.ceil

/**
 * CPU preprocessing for this experiment's fixed model shapes.
 *
 * Inputs are interleaved RGB bytes; outputs are float32 NCHW with batch size one.
 * DET accepts only a 640x640 image. REC preserves aspect ratio at height 48,
 * rejects crops wider than the requested exported width, and pads on the right
 * with normalized zeros. No OCR thresholds, labels, or model outputs are changed.
 */
object OcrPreprocessing {
    private const val DET_SIDE = 640
    private const val REC_HEIGHT = 48
    private val meanRgb = floatArrayOf(0.406f, 0.456f, 0.485f)
    private val stdRgb = floatArrayOf(0.225f, 0.224f, 0.229f)

    // HF constructs float32 mean/std tensors first, then multiplies them by 255.
    // Decimal shortcuts such as 57.12f can differ by one ULP from 0.224f * 255f.
    private val scaledMeanRgb = FloatArray(3) { meanRgb[it] * 255f }
    private val scaledStdRgb = FloatArray(3) { stdRgb[it] * 255f }

    fun det(rgb: ByteArray): FloatArray {
        val pixels = DET_SIDE * DET_SIDE
        require(rgb.size == pixels * 3) { "DET requires RGB uint8 data for exactly 640x640 pixels" }
        val output = FloatArray(pixels * 3)
        for (channel in 0 until 3) {
            val outputOffset = (2 - channel) * pixels
            val mean = scaledMeanRgb[channel]
            val standardDeviation = scaledStdRgb[channel]
            for (pixel in 0 until pixels) {
                val value = (rgb[pixel * 3 + channel].toInt() and 255).toFloat()
                output[outputOffset + pixel] = (value - mean) / standardDeviation
            }
        }
        return output
    }

    fun rec(rgb: ByteArray, inputWidth: Int, inputHeight: Int, targetWidth: Int): FloatArray {
        require(inputWidth > 0 && inputHeight > 0) { "REC image dimensions must be positive" }
        require(targetWidth == 320 || targetWidth == 640 || targetWidth == 960) {
            "REC target width must be one of the exported widths 320, 640, 960"
        }
        val inputPixels = inputWidth.toLong() * inputHeight.toLong()
        require(inputPixels <= Int.MAX_VALUE / 3L && rgb.size.toLong() == inputPixels * 3L) {
            "REC byte count does not match RGB dimensions"
        }
        val resizedWidth = ceil(REC_HEIGHT.toDouble() * inputWidth.toDouble() / inputHeight.toDouble()).toInt()
        require(resizedWidth <= targetWidth) {
            "Natural REC width $resizedWidth exceeds exported width $targetWidth; compression is disabled"
        }
        val resized = TorchUint8Resize.resize(rgb, inputWidth, inputHeight, resizedWidth, REC_HEIGHT)
        val planeSize = REC_HEIGHT * targetWidth
        val output = FloatArray(planeSize * 3)
        for (channel in 0 until 3) {
            val outputOffset = (2 - channel) * planeSize
            for (y in 0 until REC_HEIGHT) {
                val sourceOffset = y * resizedWidth * 3
                val destinationOffset = outputOffset + y * targetWidth
                for (x in 0 until resizedWidth) {
                    val value = (resized[sourceOffset + x * 3 + channel].toInt() and 255).toFloat()
                    output[destinationOffset + x] = (value - 127.5f) / 127.5f
                }
            }
        }
        return output
    }
}
