package dev.zain.znkeyboard.constants

/**
 * Tuning for the liquid nav bar engine. Plain numbers only (no Android/Compose imports) because
 * tools/liquidnav/verify_liquid_nav.sh compiles this file on the bare JVM together with the engine.
 * Spring stiffness is in 1/s² for unit mass, like Compose `spring()` and AOSP `SpringForce`.
 */
object LiquidNavMotion {
    /** Must match the uniform array sizes baked into the AGSL program. */
    const val MAX_SLOTS = 6

    /** Fixed integration step (500 Hz) so 60/90/120 Hz frames sample the same trajectory. */
    const val SUBSTEP_NANOS = 2_000_000L

    /** Longer frames (jank, resume) are clamped so the bar slows down instead of jumping. */
    const val MAX_FRAME_NANOS = 64_000_000L

    const val EDGE_STIFFNESS = 520f
    const val EDGE_DAMPING_RATIO = 0.52f

    /** Neck while the bridge holds: slow enough to visibly thin as the gap stretches. */
    const val NECK_STIFFNESS = 340f
    const val NECK_DAMPING_RATIO = 0.9f

    /** Neck after the bond breaks: a fast, critically damped collapse through zero (the pinch). */
    const val PINCH_STIFFNESS = 3_200f
    const val PINCH_DAMPING_RATIO = 1f
    const val PINCH_TARGET_FRACTION = -0.4f

    const val COLOR_STIFFNESS = 300f
    const val COLOR_DAMPING_RATIO = 1f

    /** Gap, as a fraction of the resting gap, at which a stretched neck snaps. */
    const val BREAK_GAP_FRACTION = 0.6f

    /** Gap at which two approaching pills start a bridge (hysteresis against [BREAK_GAP_FRACTION]). */
    const val JOIN_GAP_FRACTION = 0.7f

    /** How far an intact neck holds the facing edges together, as a fraction of the resting gap. */
    const val PULL_GAP_FRACTION = 0.35f

    /** Initial neck growth at coalescence, in cap radii per second (the fast early bridge growth). */
    const val JOIN_NECK_VELOCITY = 10f

    const val REST_POSITION_PX = 0.25f
    const val REST_VELOCITY_PX = 4f
    const val REST_COLOR = 0.002f

    /** Within this many px of a full-height neck the bridge is drawn as a flat box. */
    const val FLAT_NECK_EPS_PX = 0.5f
    const val MAX_FILLET_RADIUS_PX = 20_000f
}
