/*
 * Narrow Kotlin adaptation of PyTorch 2.13.0 CPU uint8 bilinear interpolation.
 * Source: aten/src/ATen/native/cpu/UpSampleKernel.cpp, HelperInterpBase
 * _compute_indices_min_size_weights / _compute_index_ranges_int16_weights.
 * https://github.com/pytorch/pytorch/blob/v2.13.0/aten/src/ATen/native/cpu/UpSampleKernel.cpp
 * Source SHA256: 79f731e377ef52fcdd75e2cbe6541eae3d953844902314ee3f65cd83947e97d6
 * Local port and verification: scripts/resize_reference.py,
 * scripts/verify_kotlin_resize.py, results/kotlin_resize_verification.json.
 *
 * PyTorch BSD-3-Clause license notice:
 *
 * From PyTorch:
 * Copyright (c) 2016-     Facebook, Inc            (Adam Paszke)
 * Copyright (c) 2014-     Facebook, Inc            (Soumith Chintala)
 * Copyright (c) 2011-2014 Idiap Research Institute (Ronan Collobert)
 * Copyright (c) 2012-2014 Deepmind Technologies    (Koray Kavukcuoglu)
 * Copyright (c) 2011-2012 NEC Laboratories America (Koray Kavukcuoglu)
 * Copyright (c) 2011-2013 NYU                      (Clement Farabet)
 * Copyright (c) 2006-2010 NEC Laboratories America (Ronan Collobert, Leon Bottou, Iain Melvin, Jason Weston)
 * Copyright (c) 2006      Idiap Research Institute (Samy Bengio)
 * Copyright (c) 2001-2004 Idiap Research Institute (Ronan Collobert, Samy Bengio, Johnny Mariethoz)
 *
 * From Caffe2:
 * Copyright (c) 2016-present, Facebook Inc. All rights reserved.
 * All contributions by Facebook: Copyright (c) 2016 Facebook Inc.
 * All contributions by Google: Copyright (c) 2015 Google Inc. All rights reserved.
 * All contributions by Yangqing Jia: Copyright (c) 2015 Yangqing Jia. All rights reserved.
 * All contributions by Kakao Brain: Copyright 2019-2020 Kakao Brain.
 * All contributions by Cruise LLC: Copyright (c) 2022 Cruise LLC. All rights reserved.
 * All contributions by Tri Dao: Copyright (c) 2024 Tri Dao. All rights reserved.
 * All contributions by Arm: Copyright (c) 2021, 2023-2025 Arm Limited and/or its affiliates.
 * All contributions from Caffe: Copyright(c) 2013, 2014, 2015, the respective contributors.
 * All rights reserved.
 * All other contributions: Copyright(c) 2015, 2016 the respective contributors.
 * All rights reserved.
 *
 * Caffe2 uses a copyright model similar to Caffe: each contributor holds
 * copyright over their contributions to Caffe2. The project versioning records
 * all such contribution and copyright details. If a contributor wants to further
 * mark their specific copyright on a particular contribution, they should
 * indicate their copyright solely in the commit message of the change when it is
 * committed.
 *
 * All rights reserved.
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the names of Facebook, Deepmind Technologies, NYU, NEC Laboratories America
 *    and IDIAP Research Institute nor the names of its contributors may be
 *    used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package dev.zain.znkeyboard.ocrlab.ppocr

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pure JVM/Android RGB uint8 resize matching PyTorch's CPU bilinear uint8 path.
 *
 * Uses align_corners=false, antialias=false, double half-pixel coordinates and
 * dynamically scaled int16 weights. Horizontal interpolation rounds to uint8
 * before vertical interpolation, which also rounds to uint8. A floating-point
 * four-neighbor interpolation followed by one rounding is not equivalent.
 *
 * Input and output are interleaved RGB, three bytes per pixel; signed Kotlin
 * bytes are interpreted as unsigned 0..255. No color conversion, normalization,
 * padding, Android Bitmap processing, or OpenCV processing occurs here.
 */
object TorchUint8Resize {
    private data class AxisWeights(
        val first: IntArray,
        val second: IntArray,
        val firstWeight: IntArray,
        val secondWeight: IntArray,
        val precision: Int,
    )

    private fun byteCount(width: Int, height: Int): Int {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        val pixels = width.toLong() * height.toLong()
        require(pixels <= Int.MAX_VALUE / 3L) { "RGB image is too large for a ByteArray" }
        return (pixels * 3L).toInt()
    }

    private fun axisWeights(inputSize: Int, outputSize: Int): AxisWeights {
        val scale = inputSize.toDouble() / outputSize.toDouble()
        val first = IntArray(outputSize)
        val second = IntArray(outputSize)
        val firstFloat = DoubleArray(outputSize)
        val secondFloat = DoubleArray(outputSize)
        var maximum = 0.0
        for (index in 0 until outputSize) {
            val coordinate = max(0.0, scale * (index.toDouble() + 0.5) - 0.5)
            first[index] = min(floor(coordinate).toInt(), inputSize - 1)
            second[index] = min(first[index] + 1, inputSize - 1)
            val fraction = (coordinate - first[index].toDouble()).coerceIn(0.0, 1.0)
            // At the right edge both taps address one pixel: merge before rounding.
            firstFloat[index] = if (first[index] == second[index]) 1.0 else 1.0 - fraction
            secondFloat[index] = if (first[index] == second[index]) 0.0 else fraction
            maximum = max(maximum, max(firstFloat[index], secondFloat[index]))
        }
        var precision = 0
        while (precision < 22) {
            if ((0.5 + maximum * (1 shl (precision + 1))).toInt() >= (1 shl 15)) break
            precision += 1
        }
        check(precision > 0)
        val factor = (1 shl precision).toDouble()
        val firstWeight = IntArray(outputSize) { (0.5 + firstFloat[it] * factor).toInt() }
        val secondWeight = IntArray(outputSize) { (0.5 + secondFloat[it] * factor).toInt() }
        return AxisWeights(first, second, firstWeight, secondWeight, precision)
    }

    fun resize(
        rgb: ByteArray,
        inputWidth: Int,
        inputHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
    ): ByteArray {
        require(rgb.size == byteCount(inputWidth, inputHeight)) { "Input byte count does not match RGB dimensions" }
        byteCount(outputWidth, outputHeight)
        var current = rgb
        if (inputWidth != outputWidth) {
            val axis = axisWeights(inputWidth, outputWidth)
            val horizontal = ByteArray(byteCount(outputWidth, inputHeight))
            val rounding = 1 shl (axis.precision - 1)
            for (y in 0 until inputHeight) {
                val inputRow = y * inputWidth * 3
                val outputRow = y * outputWidth * 3
                for (x in 0 until outputWidth) {
                    val firstPixel = inputRow + axis.first[x] * 3
                    val secondPixel = inputRow + axis.second[x] * 3
                    for (channel in 0 until 3) {
                        val value = rounding +
                            (current[firstPixel + channel].toInt() and 255) * axis.firstWeight[x] +
                            (current[secondPixel + channel].toInt() and 255) * axis.secondWeight[x]
                        horizontal[outputRow + x * 3 + channel] =
                            (value shr axis.precision).coerceIn(0, 255).toByte()
                    }
                }
            }
            current = horizontal
        }
        if (inputHeight != outputHeight) {
            val axis = axisWeights(inputHeight, outputHeight)
            val vertical = ByteArray(byteCount(outputWidth, outputHeight))
            val rounding = 1 shl (axis.precision - 1)
            val rowBytes = outputWidth * 3
            for (y in 0 until outputHeight) {
                val firstRow = axis.first[y] * rowBytes
                val secondRow = axis.second[y] * rowBytes
                val outputRow = y * rowBytes
                for (offset in 0 until rowBytes) {
                    val value = rounding +
                        (current[firstRow + offset].toInt() and 255) * axis.firstWeight[y] +
                        (current[secondRow + offset].toInt() and 255) * axis.secondWeight[y]
                    vertical[outputRow + offset] =
                        (value shr axis.precision).coerceIn(0, 255).toByte()
                }
            }
            current = vertical
        }
        return if (current === rgb) rgb.copyOf() else current
    }
}
