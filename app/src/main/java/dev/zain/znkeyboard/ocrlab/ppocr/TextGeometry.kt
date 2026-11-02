package dev.zain.znkeyboard.ocrlab.ppocr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** Four corners in top-left, top-right, bottom-right, bottom-left order. */
class TextQuad(val xs: FloatArray, val ys: FloatArray, val score: Float)

/** Rotated rectangle as returned by [TextGeometry.minAreaRect]; corners are in no particular order. */
class RotatedRect(val xs: FloatArray, val ys: FloatArray, val width: Float, val height: Float)

/**
 * PP-OCR DB postprocessing (PaddleOCR `DBPostProcess.boxes_from_bitmap`, Apache-2.0) and the PaddleX
 * min-area crop/sort helpers, ported without OpenCV. Thresholds follow the PP-OCRv6 inference.yml.
 *
 * Differences from the OpenCV reference: components come from 8-connected flood fill instead of
 * `findContours(RETR_LIST)` (hole contours are not emitted), and unclip expands the min-area
 * rectangle analytically instead of offsetting with pyclipper.
 */
object TextGeometry {
    const val THRESHOLD = 0.2f
    const val BOX_THRESHOLD = 0.45f
    const val UNCLIP_RATIO = 1.4f
    const val MAX_CANDIDATES = 3000
    const val MIN_SIZE = 3

    fun detect(probabilities: FloatArray, width: Int, height: Int, destWidth: Int, destHeight: Int): List<TextQuad> {
        require(probabilities.size == width * height)
        val visited = BooleanArray(width * height)
        val stack = IntArray(width * height)
        val rowMin = IntArray(height) { Int.MAX_VALUE }
        val rowMax = IntArray(height) { -1 }
        val quads = ArrayList<TextQuad>()
        var candidates = 0

        for (start in probabilities.indices) {
            if (visited[start] || probabilities[start] <= THRESHOLD) continue
            if (candidates >= MAX_CANDIDATES) break
            candidates++

            var top = 0
            stack[top++] = start
            visited[start] = true
            var minY = height
            var maxY = -1
            while (top > 0) {
                val index = stack[--top]
                val x = index % width
                val y = index / width
                if (x < rowMin[y]) rowMin[y] = x
                if (x > rowMax[y]) rowMax[y] = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= width || (dx == 0 && dy == 0)) continue
                        val neighbor = ny * width + nx
                        if (!visited[neighbor] && probabilities[neighbor] > THRESHOLD) {
                            visited[neighbor] = true
                            stack[top++] = neighbor
                        }
                    }
                }
            }

            val pointCount = (maxY - minY + 1) * 2
            val px = FloatArray(pointCount)
            val py = FloatArray(pointCount)
            for (y in minY..maxY) {
                val i = (y - minY) * 2
                px[i] = rowMin[y].toFloat(); py[i] = y.toFloat()
                px[i + 1] = rowMax[y].toFloat(); py[i + 1] = y.toFloat()
                rowMin[y] = Int.MAX_VALUE
                rowMax[y] = -1
            }

            val rect = minAreaRect(px, py)
            if (min(rect.width, rect.height) < MIN_SIZE) continue
            val box = orderCorners(rect)
            val score = boxScoreFast(probabilities, width, height, box)
            if (score < BOX_THRESHOLD) continue

            val expanded = unclip(box)
            if (min(expanded.width, expanded.height) < MIN_SIZE + 2) continue
            val ordered = orderCorners(expanded)
            val xs = FloatArray(4) { (ordered.xs[it].toDouble() / width * destWidth).roundHalfEven().coerceIn(0f, destWidth.toFloat()) }
            val ys = FloatArray(4) { (ordered.ys[it].toDouble() / height * destHeight).roundHalfEven().coerceIn(0f, destHeight.toFloat()) }
            quads.add(TextQuad(xs, ys, score))
        }
        return sortReadingOrder(quads)
    }

    /** numpy-style round half to even, as kotlin.math.round does. */
    private fun Double.roundHalfEven(): Float = round(this).toFloat()

    /** PaddleX SortQuadBoxes: sort by first corner y/x, then swap neighbours less than 10 px apart vertically. */
    fun sortReadingOrder(quads: List<TextQuad>): List<TextQuad> {
        val values = quads.sortedWith(compareBy<TextQuad>({ it.ys[0] }, { it.xs[0] })).toMutableList()
        for (i in 0 until values.size - 1) {
            for (j in i downTo 0) {
                val current = values[j]
                val following = values[j + 1]
                if (abs(following.ys[0] - current.ys[0]) < 10f && following.xs[0] < current.xs[0]) {
                    values[j] = following
                    values[j + 1] = current
                } else {
                    break
                }
            }
        }
        return values
    }

    /** Crop geometry for a detected quad: ordered min-area corners plus PaddleX integer crop size. */
    fun cropRect(quad: TextQuad): Pair<RotatedRect, Pair<Int, Int>> {
        val rect = orderCorners(minAreaRect(FloatArray(4) { quad.xs[it].toInt().toFloat() }, FloatArray(4) { quad.ys[it].toInt().toFloat() }))
        val xs = rect.xs
        val ys = rect.ys
        val width = max(hypot(xs[0] - xs[1], ys[0] - ys[1]), hypot(xs[2] - xs[3], ys[2] - ys[3])).toInt()
        val height = max(hypot(xs[0] - xs[3], ys[0] - ys[3]), hypot(xs[1] - xs[2], ys[1] - ys[2])).toInt()
        return rect to (width to height)
    }

    fun minAreaRect(xs: FloatArray, ys: FloatArray): RotatedRect {
        val hull = convexHull(xs, ys)
        if (hull.size == 1) {
            val i = hull[0]
            return RotatedRect(FloatArray(4) { xs[i] }, FloatArray(4) { ys[i] }, 0f, 0f)
        }
        var bestArea = Float.MAX_VALUE
        var best = RotatedRect(FloatArray(4), FloatArray(4), 0f, 0f)
        for (edge in hull.indices) {
            val a = hull[edge]
            val b = hull[(edge + 1) % hull.size]
            val length = hypot(xs[b] - xs[a], ys[b] - ys[a])
            if (length == 0f) continue
            val ux = (xs[b] - xs[a]) / length
            val uy = (ys[b] - ys[a]) / length
            var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
            var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
            for (p in hull) {
                val u = xs[p] * ux + ys[p] * uy
                val v = -xs[p] * uy + ys[p] * ux
                minU = min(minU, u); maxU = max(maxU, u)
                minV = min(minV, v); maxV = max(maxV, v)
            }
            val area = (maxU - minU) * (maxV - minV)
            if (area < bestArea) {
                bestArea = area
                val us = floatArrayOf(minU, maxU, maxU, minU)
                val vs = floatArrayOf(minV, minV, maxV, maxV)
                best = RotatedRect(
                    FloatArray(4) { us[it] * ux - vs[it] * uy },
                    FloatArray(4) { us[it] * uy + vs[it] * ux },
                    maxU - minU,
                    maxV - minV,
                )
            }
        }
        return best
    }

    /** PaddleOCR `get_mini_boxes` ordering: sort by x, then pick top/bottom within each half. */
    fun orderCorners(rect: RotatedRect): RotatedRect {
        val order = (0 until 4).sortedBy { rect.xs[it] }
        val (i1, i4) = if (rect.ys[order[1]] > rect.ys[order[0]]) order[0] to order[1] else order[1] to order[0]
        val (i2, i3) = if (rect.ys[order[3]] > rect.ys[order[2]]) order[2] to order[3] else order[3] to order[2]
        val indices = intArrayOf(i1, i2, i3, i4)
        return RotatedRect(
            FloatArray(4) { rect.xs[indices[it]] },
            FloatArray(4) { rect.ys[indices[it]] },
            rect.width,
            rect.height,
        )
    }

    /** Mean probability inside the integer-truncated box, like PaddleOCR `box_score_fast`. */
    private fun boxScoreFast(probabilities: FloatArray, width: Int, height: Int, box: RotatedRect): Float {
        val xs = IntArray(4) { box.xs[it].toInt() }
        val ys = IntArray(4) { box.ys[it].toInt() }
        val xMin = floor(box.xs.min()).toInt().coerceIn(0, width - 1)
        val xMax = ceil(box.xs.max()).toInt().coerceIn(0, width - 1)
        val yMin = floor(box.ys.min()).toInt().coerceIn(0, height - 1)
        val yMax = ceil(box.ys.max()).toInt().coerceIn(0, height - 1)
        var sum = 0.0
        var count = 0
        for (y in yMin..yMax) {
            for (x in xMin..xMax) {
                if (insideConvex(xs, ys, x, y)) {
                    sum += probabilities[y * width + x]
                    count++
                }
            }
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }

    private fun insideConvex(xs: IntArray, ys: IntArray, x: Int, y: Int): Boolean {
        var positive = false
        var negative = false
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val cross = (xs[j] - xs[i]).toLong() * (y - ys[i]) - (ys[j] - ys[i]).toLong() * (x - xs[i])
            if (cross > 0) positive = true
            if (cross < 0) negative = true
            if (positive && negative) return false
        }
        return true
    }

    /** Expands an ordered rectangle by area * ratio / perimeter on every side. */
    private fun unclip(box: RotatedRect): RotatedRect {
        val xs = box.xs
        val ys = box.ys
        val sideW = hypot(xs[1] - xs[0], ys[1] - ys[0])
        val sideH = hypot(xs[3] - xs[0], ys[3] - ys[0])
        val distance = (sideW * sideH) * UNCLIP_RATIO / (2f * (sideW + sideH))
        val cx = xs.average().toFloat()
        val cy = ys.average().toFloat()
        val ux = (xs[1] - xs[0]) / sideW
        val uy = (ys[1] - ys[0]) / sideW
        val vx = (xs[3] - xs[0]) / sideH
        val vy = (ys[3] - ys[0]) / sideH
        val halfW = sideW / 2f + distance
        val halfH = sideH / 2f + distance
        val su = floatArrayOf(-1f, 1f, 1f, -1f)
        val sv = floatArrayOf(-1f, -1f, 1f, 1f)
        return RotatedRect(
            FloatArray(4) { cx + su[it] * halfW * ux + sv[it] * halfH * vx },
            FloatArray(4) { cy + su[it] * halfW * uy + sv[it] * halfH * vy },
            2f * halfW,
            2f * halfH,
        )
    }

    /** Andrew's monotone chain; returns point indices counter-clockwise without collinear points. */
    private fun convexHull(xs: FloatArray, ys: FloatArray): IntArray {
        val points = xs.indices.sortedWith(compareBy<Int>({ xs[it] }, { ys[it] }))
            .distinctBy { xs[it] to ys[it] }
        if (points.size < 3) return points.toIntArray()
        fun cross(o: Int, a: Int, b: Int) = (xs[a] - xs[o]) * (ys[b] - ys[o]) - (ys[a] - ys[o]) * (xs[b] - xs[o])
        val hull = IntArray(points.size * 2)
        var k = 0
        for (p in points) {
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], p) <= 0f) k--
            hull[k++] = p
        }
        val lowerSize = k + 1
        for (i in points.size - 2 downTo 0) {
            val p = points[i]
            while (k >= lowerSize && cross(hull[k - 2], hull[k - 1], p) <= 0f) k--
            hull[k++] = p
        }
        return hull.copyOf(k - 1)
    }
}
