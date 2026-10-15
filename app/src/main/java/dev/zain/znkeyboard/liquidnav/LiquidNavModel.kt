package dev.zain.znkeyboard.liquidnav

import dev.zain.znkeyboard.constants.LiquidNavMotion
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Exact discrete-time propagator of a unit-mass damped spring over one fixed step:
 * [x', v'] = A · [x − target, v]. Precomputed once, so a step is four multiply-adds and the
 * trajectory does not depend on the display refresh rate.
 */
class SpringStep(stiffness: Float, dampingRatio: Float, stepSeconds: Double) {
    val a11: Float
    val a12: Float
    val a21: Float
    val a22: Float

    init {
        val w = sqrt(stiffness.toDouble())
        val z = dampingRatio.toDouble()
        val h = stepSeconds
        val b11: Double
        val b12: Double
        val b21: Double
        val b22: Double
        if (z < 1.0) {
            val wd = w * sqrt(1.0 - z * z)
            val e = exp(-z * w * h)
            val c = cos(wd * h)
            val s = sin(wd * h)
            b11 = e * (c + z * w * s / wd)
            b12 = e * s / wd
            b21 = -e * w * w * s / wd
            b22 = e * (c - z * w * s / wd)
        } else if (z == 1.0) {
            val e = exp(-w * h)
            b11 = e * (1.0 + w * h)
            b12 = e * h
            b21 = -e * w * w * h
            b22 = e * (1.0 - w * h)
        } else {
            val q = w * sqrt(z * z - 1.0)
            val r1 = -z * w + q
            val r2 = -z * w - q
            val e1 = exp(r1 * h)
            val e2 = exp(r2 * h)
            val dr = r1 - r2
            b11 = (r1 * e2 - r2 * e1) / dr
            b12 = (e1 - e2) / dr
            b21 = r1 * r2 * (e2 - e1) / dr
            b22 = (r1 * e1 - r2 * e2) / dr
        }
        a11 = b11.toFloat()
        a12 = b12.toFloat()
        a21 = b21.toFloat()
        a22 = b22.toFloat()
    }

    fun advance(x: FloatArray, v: FloatArray, i: Int, target: Float) {
        val d = x[i] - target
        val vi = v[i]
        x[i] = target + a11 * d + a12 * vi
        v[i] = a21 * d + a22 * vi
    }
}

/**
 * Pure-Kotlin model of the liquid nav bar, in visual (left-to-right) slot order and pixels.
 *
 * Every slot is a pill whose facing ends are semicircles of radius `height / 2`. Between adjacent
 * slots sits a capillary bridge described by its neck half-width: full height means fused, zero means
 * pinched off. Slot edges, neck widths and accent amount are springs; a per-boundary bond with
 * hysteresis decides when a stretched neck snaps and when approaching pills start a bridge.
 * Nothing here allocates after construction.
 */
class LiquidNavModel(val capacity: Int = LiquidNavMotion.MAX_SLOTS) {
    private val stepSeconds = LiquidNavMotion.SUBSTEP_NANOS / 1e9
    private val edgeSpring = SpringStep(LiquidNavMotion.EDGE_STIFFNESS, LiquidNavMotion.EDGE_DAMPING_RATIO, stepSeconds)
    private val neckSpring = SpringStep(LiquidNavMotion.NECK_STIFFNESS, LiquidNavMotion.NECK_DAMPING_RATIO, stepSeconds)
    private val pinchSpring = SpringStep(LiquidNavMotion.PINCH_STIFFNESS, LiquidNavMotion.PINCH_DAMPING_RATIO, stepSeconds)
    private val colorSpring = SpringStep(LiquidNavMotion.COLOR_STIFFNESS, LiquidNavMotion.COLOR_DAMPING_RATIO, stepSeconds)

    var count = 0
        private set
    var selected = 0
        private set
    var radius = 0f
        private set
    private var width = 0f
    private var gap = 0f
    private var selectedWidth = 0f

    private val targetLeft = FloatArray(capacity)
    private val targetRight = FloatArray(capacity)
    private val targetColor = FloatArray(capacity)
    private val left = FloatArray(capacity)
    private val leftV = FloatArray(capacity)
    private val right = FloatArray(capacity)
    private val rightV = FloatArray(capacity)
    private val color = FloatArray(capacity)
    private val colorV = FloatArray(capacity)

    private val boundaries = max(capacity - 1, 1)
    private val neck = FloatArray(boundaries)
    private val neckV = FloatArray(boundaries)
    private val bonded = BooleanArray(boundaries)
    private val open = BooleanArray(boundaries)

    private var pendingNanos = 0L

    var isAtRest = true
        private set

    /** Sets the geometry; any change snaps to the resting layout. Returns true if something changed. */
    fun configure(
        count: Int,
        widthPx: Float,
        heightPx: Float,
        gapPx: Float,
        selectedWidthPx: Float,
        selected: Int,
    ): Boolean {
        require(count in 1..capacity) { "count $count not in 1..$capacity" }
        val r = heightPx * 0.5f
        if (count == this.count && widthPx == width && r == radius && gapPx == gap && selectedWidthPx == selectedWidth) {
            return false
        }
        this.count = count
        width = widthPx
        radius = r
        gap = gapPx
        selectedWidth = selectedWidthPx
        this.selected = selected.coerceIn(0, count - 1)
        layoutTargets()
        snapToTarget()
        return true
    }

    /** Retargets toward [index] from the current state (positions and velocities stay continuous). */
    fun select(index: Int) {
        if (count == 0) return
        val next = index.coerceIn(0, count - 1)
        if (next == selected) return
        selected = next
        layoutTargets()
        isAtRest = false
    }

    fun snapToTarget() {
        for (i in 0 until count) {
            left[i] = targetLeft[i]
            right[i] = targetRight[i]
            color[i] = targetColor[i]
            leftV[i] = 0f
            rightV[i] = 0f
            colorV[i] = 0f
        }
        for (j in 0 until count - 1) {
            bonded[j] = !open[j]
            neck[j] = if (bonded[j]) radius else LiquidNavMotion.PINCH_TARGET_FRACTION * radius
            neckV[j] = 0f
        }
        pendingNanos = 0L
        isAtRest = true
    }

    /** Advances by one display frame. Returns true while still animating. */
    fun step(frameNanos: Long): Boolean {
        if (isAtRest) return false
        pendingNanos += frameNanos.coerceIn(0L, LiquidNavMotion.MAX_FRAME_NANOS)
        while (pendingNanos >= LiquidNavMotion.SUBSTEP_NANOS) {
            substep()
            pendingNanos -= LiquidNavMotion.SUBSTEP_NANOS
        }
        if (isSettled()) snapToTarget()
        return !isAtRest
    }

    fun targetLeft(i: Int): Float = targetLeft[i]
    fun targetRight(i: Int): Float = targetRight[i]
    fun left(i: Int): Float = left[i]
    fun right(i: Int): Float = right[i]
    fun slotCenter(i: Int): Float = (left[i] + right[i]) * 0.5f
    fun slotAccent(i: Int): Float = color[i].coerceIn(0f, 1f)
    fun neck(j: Int): Float = neck[j]
    fun isBonded(j: Int): Boolean = bonded[j]

    /**
     * Rendered slots, 4 floats each: left, right, accent amount, and the half-width over which accent
     * blends into the next slot (the neck, or half the gap once pinched). Pills never get narrower than a circle.
     */
    fun fillSlots(out: FloatArray) {
        for (i in 0 until count) {
            val o = i * 4
            out[o] = renderedLeft(i)
            out[o + 1] = renderedRight(i)
            out[o + 2] = slotAccent(i)
            out[o + 3] = 0f
        }
        for (j in 0 until count - 1) {
            val halfGap = (renderedLeft(j + 1) - renderedRight(j)) * 0.5f
            out[j * 4 + 3] = max(max(halfGap, neck[j].coerceIn(0f, radius)), MIN_BLEND_PX)
        }
    }

    /** Rendered bridges, 4 floats each: see [bridgeGeometry]. */
    fun fillBridges(out: FloatArray) {
        for (j in 0 until count - 1) {
            val n = neck[j]
            bridgeGeometry(
                capA = renderedRight(j) - radius,
                capB = renderedLeft(j + 1) + radius,
                r = radius,
                neck = if (n > 0f) min(n, radius) else -1f,
                out = out,
                offset = j * 4,
            )
        }
    }

    private fun renderedLeft(i: Int): Float {
        val c = (left[i] + right[i]) * 0.5f
        return min(left[i], c - radius)
    }

    private fun renderedRight(i: Int): Float {
        val c = (left[i] + right[i]) * 0.5f
        return max(right[i], c + radius)
    }

    private fun layoutTargets() {
        val n = count
        if (n == 1) {
            targetLeft[0] = 0f
            targetRight[0] = width
            targetColor[0] = 1f
            return
        }
        val unselected = unselectedWidth(n, width, gap, selectedWidth, selected)
        var x = 0f
        for (i in 0 until n) {
            if (i == selected) {
                if (i > 0) x += gap
                targetLeft[i] = x
                x += selectedWidth
                targetRight[i] = x
                if (i < n - 1) x += gap
                targetColor[i] = 1f
            } else {
                targetLeft[i] = x
                x += unselected
                targetRight[i] = x
                targetColor[i] = 0f
            }
        }
        for (j in 0 until n - 1) {
            open[j] = j == selected || j + 1 == selected
        }
    }

    private fun substep() {
        val pullHalf = LiquidNavMotion.PULL_GAP_FRACTION * gap * 0.5f
        val breakGap = LiquidNavMotion.BREAK_GAP_FRACTION * gap
        val joinGap = LiquidNavMotion.JOIN_GAP_FRACTION * gap
        val last = count - 1

        for (j in 0 until last) {
            val g = left[j + 1] - right[j]
            if (bonded[j] && open[j] && (g >= breakGap || neck[j] <= 0f)) {
                bonded[j] = false
            } else if (!bonded[j] && !open[j] && g <= joinGap) {
                bonded[j] = true
                neck[j] = max(neck[j], 0f)
                neckV[j] = max(neckV[j], LiquidNavMotion.JOIN_NECK_VELOCITY * radius)
            }
        }

        for (i in 0..last) {
            var tl = targetLeft[i]
            var tr = targetRight[i]
            // An intact neck holds the facing edges together, so a detaching pill stretches before it lets go.
            if (i > 0 && bonded[i - 1] && open[i - 1]) tl -= pullHalf
            if (i < last && bonded[i] && open[i]) tr += pullHalf
            edgeSpring.advance(left, leftV, i, tl)
            edgeSpring.advance(right, rightV, i, tr)
            colorSpring.advance(color, colorV, i, targetColor[i])
        }

        for (j in 0 until last) {
            if (bonded[j]) {
                val target = if (open[j]) {
                    val g = left[j + 1] - right[j]
                    radius * (1f - g / breakGap).coerceIn(0f, 1f)
                } else {
                    radius
                }
                neckSpring.advance(neck, neckV, j, target)
            } else {
                pinchSpring.advance(neck, neckV, j, LiquidNavMotion.PINCH_TARGET_FRACTION * radius)
            }
        }
    }

    private fun isSettled(): Boolean {
        val pe = LiquidNavMotion.REST_POSITION_PX
        val ve = LiquidNavMotion.REST_VELOCITY_PX
        for (i in 0 until count) {
            if (abs(left[i] - targetLeft[i]) > pe || abs(leftV[i]) > ve) return false
            if (abs(right[i] - targetRight[i]) > pe || abs(rightV[i]) > ve) return false
            if (abs(color[i] - targetColor[i]) > LiquidNavMotion.REST_COLOR) return false
        }
        for (j in 0 until count - 1) {
            if (bonded[j] == open[j]) return false
            if (bonded[j]) {
                if (abs(neck[j] - radius) > pe || abs(neckV[j]) > ve) return false
            } else if (neck[j] > 0f) {
                return false
            }
        }
        return true
    }

    companion object {
        private const val MIN_BLEND_PX = 0.5f

        fun unselectedWidth(count: Int, width: Float, gap: Float, selectedWidth: Float, selected: Int): Float {
            if (count <= 1) return width
            val gaps = (if (selected > 0) 1 else 0) + (if (selected < count - 1) 1 else 0)
            return (width - selectedWidth - gaps * gap) / (count - 1)
        }

        /**
         * Capillary bridge between two facing semicircular caps of radius [r] centred at x = [capA] and
         * x = [capB] on the same horizontal axis. The bridge outline is a pair of circular fillets (the
         * 2D constant-curvature meniscus) tangent to both caps, leaving a neck of half-width [neck] at
         * the midpoint. Writes (midX, halfWidth, filletRadius, neck); neck < 0 means no bridge, and
         * filletRadius == 0 with neck == r means a flat (fused) bridge.
         */
        fun bridgeGeometry(capA: Float, capB: Float, r: Float, neck: Float, out: FloatArray, offset: Int) {
            val half = (capB - capA) * 0.5f
            out[offset] = (capA + capB) * 0.5f
            if (neck <= 0f || half <= 0f) {
                out[offset + 1] = 0f
                out[offset + 2] = 0f
                out[offset + 3] = -1f
                return
            }
            if (r - neck <= LiquidNavMotion.FLAT_NECK_EPS_PX) {
                flat(half, r, out, offset)
                return
            }
            // Tangency: (half)² + (neck + k)² = (r + k)²  ⇒  k = (half² + neck² − r²) / (2 (r − neck)).
            val k = (half * half + neck * neck - r * r) / (2f * (r - neck))
            if (k <= 0f) {
                // The caps already overlap wider than the requested neck; no bridge needed.
                out[offset + 1] = 0f
                out[offset + 2] = 0f
                out[offset + 3] = -1f
                return
            }
            if (k > LiquidNavMotion.MAX_FILLET_RADIUS_PX) {
                flat(half, r, out, offset)
                return
            }
            out[offset + 1] = half * k / (r + k)
            out[offset + 2] = k
            out[offset + 3] = neck
        }

        private fun flat(half: Float, r: Float, out: FloatArray, offset: Int) {
            out[offset + 1] = half
            out[offset + 2] = 0f
            out[offset + 3] = r
        }
    }
}
