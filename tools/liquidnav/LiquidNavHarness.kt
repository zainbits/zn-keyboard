import dev.zain.znkeyboard.constants.LiquidNavMotion
import dev.zain.znkeyboard.liquidnav.LiquidNavModel
import dev.zain.znkeyboard.liquidnav.SpringStep
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.system.exitProcess

// JVM checks for the liquid nav engine (run via tools/liquidnav/verify_liquid_nav.sh).
// Geometry is a 360dp-wide bar at 3x density: the same numbers the prototype in previews/ uses.

private const val DENSITY = 3f
private const val COUNT = 5
private val W = 360f * DENSITY
private val H = 64f * DENSITY
private val GAP = 10f * DENSITY
private val SEL = 64f * DENSITY
private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    println("${if (ok) "PASS" else "FAIL"}  $name${if (detail.isNotEmpty()) "  ($detail)" else ""}")
    if (!ok) failures++
}

private fun newModel(selected: Int) = LiquidNavModel().apply { configure(COUNT, W, H, GAP, SEL, selected) }

private fun analytic(k: Double, z: Double, x0: Double, v0: Double, t: Double): Double {
    val w = sqrt(k)
    return when {
        z < 1 -> {
            val wd = w * sqrt(1 - z * z)
            exp(-z * w * t) * (x0 * cos(wd * t) + (v0 + z * w * x0) / wd * sin(wd * t))
        }
        z == 1.0 -> exp(-w * t) * (x0 + (v0 + w * x0) * t)
        else -> {
            val q = w * sqrt(z * z - 1)
            val r1 = -z * w + q
            val r2 = -z * w - q
            val c1 = (v0 - r2 * x0) / (r1 - r2)
            c1 * exp(r1 * t) + (x0 - c1) * exp(r2 * t)
        }
    }
}

private fun checkSpringPropagator() {
    for ((k, z) in listOf(520.0 to 0.52, 3200.0 to 1.0, 300.0 to 1.6)) {
        val step = SpringStep(k.toFloat(), z.toFloat(), LiquidNavMotion.SUBSTEP_NANOS / 1e9)
        val x = floatArrayOf(100f)
        val v = floatArrayOf(-40f)
        var worst = 0.0
        for (n in 1..500) {
            step.advance(x, v, 0, 0f)
            val t = n * LiquidNavMotion.SUBSTEP_NANOS / 1e9
            worst = max(worst, abs(x[0] - analytic(k, z, 100.0, -40.0, t)))
        }
        check("spring propagator matches closed form (k=$k, ζ=$z)", worst < 1e-2, "max err %.2e px".format(worst))
    }
}

private fun checkRestLayout() {
    for (sel in 0 until COUNT) {
        val m = newModel(sel)
        var ok = abs(m.targetLeft(0)) < 1e-3f && abs(m.targetRight(COUNT - 1) - W) < 0.01f
        ok = ok && abs(m.targetRight(sel) - m.targetLeft(sel) - SEL) < 0.01f
        for (j in 0 until COUNT - 1) {
            val g = m.targetLeft(j + 1) - m.targetRight(j)
            val expected = if (j == sel || j + 1 == sel) GAP else 0f
            ok = ok && abs(g - expected) < 0.01f
            ok = ok && m.isBonded(j) == (expected == 0f)
        }
        val bridges = FloatArray(4 * (LiquidNavMotion.MAX_SLOTS - 1))
        m.fillBridges(bridges)
        for (j in 0 until COUNT - 1) {
            val fused = m.isBonded(j)
            // Fused junctions are flat full-height boxes; open junctions have no bridge.
            ok = ok && if (fused) bridges[j * 4 + 2] == 0f && bridges[j * 4 + 3] == m.radius else bridges[j * 4 + 3] < 0f
        }
        check("rest layout for selected=$sel: widths, gaps, bonds, bridges", ok)
    }
}

private fun checkBridgeGeometry() {
    val out = FloatArray(4)
    val r = H / 2
    var worst = 0.0
    var ok = true
    for (gapStep in 0..20) {
        for (neckStep in 1..19) {
            val g = gapStep * GAP / 10f
            val half = r + g / 2
            val n = r * neckStep / 20f
            LiquidNavModel.bridgeGeometry(-half, half, r, n, out, 0)
            if (out[3] < 0f) {
                ok = ok && sqrt(max(r * r - half * half, 0f)) >= n
                continue
            }
            val k = out[2]
            if (k == 0f) continue
            // Fillet centre (0, n + k) must be tangent to the cap circle centred at (-half, 0).
            worst = max(worst, abs(hypot(half.toDouble(), (n + k).toDouble()) - (r + k)))
            ok = ok && out[1] in 0f..half && out[3] == n
        }
    }
    check("bridge fillets are tangent to the caps and leave the requested neck", ok && worst < 0.05, "max tangency err %.3f px".format(worst))
}

private class Trace(val breakSubstep: Int, val joinSubstep: Int, val settleNanos: Long, val maxEdgeJump: Float, val nan: Boolean)

/** Runs a transition at a fixed display rate, optionally retargeting at [interruptNanos]. */
private fun run(from: Int, to: Int, frameNanos: Long, interruptNanos: Long = -1, interruptTo: Int = to): Trace {
    val m = newModel(from)
    m.select(to)
    var t = 0L
    var breakAt = -1
    var joinAt = -1
    var maxJump = 0f
    var nan = false
    val wasBonded = BooleanArray(COUNT - 1) { m.isBonded(it) }
    val prevL = FloatArray(COUNT) { m.left(it) }
    val prevR = FloatArray(COUNT) { m.right(it) }
    while (m.step(frameNanos)) {
        t += frameNanos
        if (interruptNanos in (t - frameNanos + 1)..t) m.select(interruptTo)
        for (j in 0 until COUNT - 1) {
            if (wasBonded[j] && !m.isBonded(j) && breakAt < 0) breakAt = (t / LiquidNavMotion.SUBSTEP_NANOS).toInt()
            if (!wasBonded[j] && m.isBonded(j) && joinAt < 0) joinAt = (t / LiquidNavMotion.SUBSTEP_NANOS).toInt()
            wasBonded[j] = m.isBonded(j)
            if (m.neck(j).isNaN()) nan = true
        }
        for (i in 0 until COUNT) {
            maxJump = max(maxJump, max(abs(m.left(i) - prevL[i]), abs(m.right(i) - prevR[i])))
            prevL[i] = m.left(i)
            prevR[i] = m.right(i)
            if (m.left(i).isNaN() || m.right(i).isNaN()) nan = true
        }
        if (t > 3_000_000_000L) break
    }
    return Trace(breakAt, joinAt, t, maxJump, nan)
}

private fun checkFrameRateIndependence() {
    val rates = listOf(60, 90, 120)
    val traces = rates.map { run(4, 0, 1_000_000_000L / it) }
    val first = traces[0]
    val sameEvents = traces.all {
        abs(it.breakSubstep - first.breakSubstep) <= 9 && abs(it.joinSubstep - first.joinSubstep) <= 9
    }
    check(
        "pinch/join timing independent of 60/90/120 Hz",
        sameEvents,
        traces.zip(rates).joinToString { (tr, hz) -> "${hz}Hz break=${tr.breakSubstep * 2}ms join=${tr.joinSubstep * 2}ms settle=${tr.settleNanos / 1_000_000}ms" },
    )
    // Identical substep counts must give bit-identical states regardless of how frames sliced them.
    val a = newModel(4).apply { select(0) }
    val b = newModel(4).apply { select(0) }
    repeat(6) { a.step(16_666_666L) }
    repeat(12) { b.step(8_333_333L) }
    val identical = (0 until COUNT).all { a.left(it) == b.left(it) && a.right(it) == b.right(it) } &&
        (0 until COUNT - 1).all { a.neck(it) == b.neck(it) }
    check("same elapsed substeps give bit-identical state at 60 vs 120 Hz", identical)
}

private fun checkAllTransitionsSettle() {
    var worstSettle = 0L
    var ok = true
    for (from in 0 until COUNT) for (to in 0 until COUNT) {
        if (from == to) continue
        val tr = run(from, to, 8_333_333L)
        worstSettle = max(worstSettle, tr.settleNanos)
        ok = ok && !tr.nan && tr.settleNanos < 2_000_000_000L
    }
    check("every transition settles without NaN", ok, "slowest ${worstSettle / 1_000_000} ms")
}

private fun checkInterruption() {
    val plain = run(0, 4, 8_333_333L)
    var ok = true
    var worst = 0f
    for (at in listOf(30L, 60L, 90L, 150L, 220L)) {
        val tr = run(0, 4, 8_333_333L, interruptNanos = at * 1_000_000L, interruptTo = 2)
        worst = max(worst, tr.maxEdgeJump)
        ok = ok && !tr.nan && tr.settleNanos < 2_000_000_000L
    }
    // A retarget changes only spring targets, so per-frame motion must stay in the same range as an uninterrupted run.
    ok = ok && worst <= plain.maxEdgeJump * 1.6f + 1f
    check("retargeting mid-animation stays continuous", ok, "max edge step %.1f px vs %.1f px uninterrupted".format(worst, plain.maxEdgeJump))
}

private fun checkSnapToTarget() {
    val m = newModel(0)
    m.select(3)
    m.step(20_000_000L)
    m.snapToTarget()
    val ok = m.isAtRest && (0 until COUNT).all { m.left(it) == m.targetLeft(it) && m.right(it) == m.targetRight(it) }
    check("animation scale 0 path (snapToTarget) lands exactly on the layout", ok)
}

fun main() {
    checkSpringPropagator()
    checkRestLayout()
    checkBridgeGeometry()
    checkFrameRateIndependence()
    checkAllTransitionsSettle()
    checkInterruption()
    checkSnapToTarget()
    println(if (failures == 0) "\nAll liquid nav checks passed." else "\n$failures check(s) failed.")
    if (failures > 0) exitProcess(1)
}
