package dev.zain.znkeyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.zain.znkeyboard.constants.ScanAreaDefaults
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The part of the viewfinder Scan text reads, drawn over the camera preview.
 *
 * - With no area set, nothing is drawn and the whole frame is read.
 * - Dragging on empty space draws a new area. Dragging a corner or an edge resizes it, and
 *   dragging inside moves it.
 * - The small × above its top-right corner, or a double tap, resets to the whole frame.
 * - Taps and pinches go to [touchTarget], so tap-to-focus and pinch-to-zoom keep working.
 *
 * The area is kept as fractions of this view, so it survives size changes and maps directly
 * onto the captured frame.
 */
@SuppressLint("ViewConstructor")
internal class ScanAreaOverlayView(context: Context) : View(context) {
    /** Receives the touches this view doesn't use (the camera preview). */
    var touchTarget: View? = null

    /** Called when a gesture ends with the new area as fractions, or null for the whole frame. */
    var onAreaChanged: ((RectF?) -> Unit)? = null

    private enum class Mode { Idle, Pending, Forwarding, Drawing, Moving, Resizing, Removing }

    private val fraction = RectF()
    private var hasArea = false
    private val area = RectF()
    private val startArea = RectF()
    private var startHadArea = false

    private var mode = Mode.Idle
    private var grabEdges = 0
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val minWidth = context.dp(ScanAreaDefaults.MIN_WIDTH_DP)
    private val minHeight = context.dp(ScanAreaDefaults.MIN_HEIGHT_DP)
    private val cornerRadius = context.dp(ScanAreaDefaults.CORNER_RADIUS_DP)
    private val bracketLength = context.dp(ScanAreaDefaults.BRACKET_LENGTH_DP)
    private val cornerGrab = context.dp(ScanAreaDefaults.CORNER_GRAB_DP)
    private val edgeGrab = context.dp(ScanAreaDefaults.EDGE_GRAB_DP)
    private val doubleTapSlop = context.dp(ScanAreaDefaults.DOUBLE_TAP_SLOP_DP)

    private val removeRadius = context.dp(ScanAreaDefaults.REMOVE_RADIUS_DP)
    private val removeGap = context.dp(ScanAreaDefaults.REMOVE_GAP_DP)
    private val removeArm = context.dp(ScanAreaDefaults.REMOVE_CROSS_ARM_DP)
    private val removeTouchRadius = removeRadius + context.dp(ScanAreaDefaults.REMOVE_TOUCH_EXTRA_DP)
    private var removeX = 0f
    private var removeY = 0f

    private val dimPath = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val bracketPath = Path()
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ScanAreaDefaults.DIM_COLOR }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(ScanAreaDefaults.BORDER_STROKE_DP)
    }
    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(ScanAreaDefaults.BRACKET_STROKE_DP)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ScanAreaDefaults.BRACKET_COLOR
    }

    private val removeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ScanAreaDefaults.REMOVE_FILL_COLOR }
    private val removeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(ScanAreaDefaults.BORDER_STROKE_DP)
        color = ScanAreaDefaults.REMOVE_BORDER_COLOR
    }
    private val removeCrossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(ScanAreaDefaults.REMOVE_STROKE_DP)
        strokeCap = Paint.Cap.ROUND
        color = ScanAreaDefaults.REMOVE_CROSS_COLOR
    }

    /** Sets the area as fractions of the view, or null for the whole frame. */
    fun setArea(value: RectF?) {
        hasArea = value != null
        if (value != null) fraction.set(value)
        if (mode != Mode.Idle) mode = Mode.Idle
        syncAreaFromFraction()
        invalidate()
    }

    /** The current area as fractions, or null when the whole frame is read. */
    fun areaFraction(): RectF? = if (hasArea) RectF(fraction) else null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        syncAreaFromFraction()
    }

    private fun syncAreaFromFraction() {
        if (!hasArea) return
        area.set(fraction.left * width, fraction.top * height, fraction.right * width, fraction.bottom * height)
        rebuildPaths()
    }

    override fun onDraw(canvas: Canvas) {
        if (!hasArea || area.isEmpty) return
        canvas.drawPath(dimPath, dimPaint)
        val active = mode == Mode.Drawing || mode == Mode.Moving || mode == Mode.Resizing
        borderPaint.color = if (active) ScanAreaDefaults.BORDER_ACTIVE_COLOR else ScanAreaDefaults.BORDER_COLOR
        val radius = currentRadius()
        canvas.drawRoundRect(area, radius, radius, borderPaint)
        canvas.drawPath(bracketPath, bracketPaint)
        canvas.drawCircle(removeX, removeY, removeRadius, removeFillPaint)
        canvas.drawCircle(removeX, removeY, removeRadius, removeBorderPaint)
        canvas.drawLine(removeX - removeArm, removeY - removeArm, removeX + removeArm, removeY + removeArm, removeCrossPaint)
        canvas.drawLine(removeX + removeArm, removeY - removeArm, removeX - removeArm, removeY + removeArm, removeCrossPaint)
    }

    private fun currentRadius(): Float = min(cornerRadius, min(area.width(), area.height()) / 2f)

    private fun rebuildPaths() {
        dimPath.reset()
        bracketPath.reset()
        if (area.isEmpty) return
        val radius = currentRadius()
        dimPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        dimPath.addRoundRect(area, radius, radius, Path.Direction.CW)

        val length = max(radius, min(bracketLength, min(area.width(), area.height()) / 3f))
        val l = area.left
        val t = area.top
        val r = area.right
        val b = area.bottom
        val d = radius * 2f
        bracketPath.moveTo(l, t + length)
        bracketPath.lineTo(l, t + radius)
        bracketPath.arcTo(l, t, l + d, t + d, 180f, 90f, false)
        bracketPath.lineTo(l + length, t)
        bracketPath.moveTo(r - length, t)
        bracketPath.lineTo(r - radius, t)
        bracketPath.arcTo(r - d, t, r, t + d, 270f, 90f, false)
        bracketPath.lineTo(r, t + length)
        bracketPath.moveTo(r, b - length)
        bracketPath.lineTo(r, b - radius)
        bracketPath.arcTo(r - d, b - d, r, b, 0f, 90f, false)
        bracketPath.lineTo(r - length, b)
        bracketPath.moveTo(l + length, b)
        bracketPath.lineTo(l + radius, b)
        bracketPath.arcTo(l, b - d, l + d, b, 90f, 90f, false)
        bracketPath.lineTo(l, b - length)
        placeRemoveButton()
    }

    /**
     * Just above the top-right corner; below the area when it touches the top of the frame, and
     * inside its top-right corner when it fills the frame's height.
     */
    private fun placeRemoveButton() {
        val w = width.toFloat()
        val h = height.toFloat()
        val size = removeRadius * 2f + removeGap
        removeX = (area.right - removeRadius).coerceIn(removeRadius + removeGap, max(removeRadius + removeGap, w - removeRadius - removeGap))
        removeY = when {
            area.top >= size -> area.top - removeGap - removeRadius
            area.bottom + size <= h -> area.bottom + removeGap + removeRadius
            else -> {
                removeX = area.right - removeGap - removeRadius
                area.top + removeGap + removeRadius
            }
        }
    }

    private fun isOnRemoveButton(x: Float, y: Float): Boolean {
        val dx = x - removeX
        val dy = y - removeY
        return dx * dx + dy * dy <= removeTouchRadius * removeTouchRadius
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (hasArea && isOnRemoveButton(event.x, event.y)) {
                    mode = Mode.Removing
                    return true
                }
                forward(event)
                pointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                startArea.set(area)
                startHadArea = hasArea
                grabEdges = if (hasArea) edgesAt(downX, downY) else 0
                mode = Mode.Pending
            }
            MotionEvent.ACTION_POINTER_DOWN -> when (mode) {
                // A second finger before a drag started is a pinch: the preview zooms.
                Mode.Pending, Mode.Forwarding -> {
                    mode = Mode.Forwarding
                    forward(event)
                }
                else -> Unit
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId).takeIf { it >= 0 } ?: 0
                val x = event.getX(index)
                val y = event.getY(index)
                when (mode) {
                    Mode.Pending -> {
                        if (abs(x - downX) > touchSlop || abs(y - downY) > touchSlop) {
                            beginDrag(event)
                            updateDrag(x, y)
                        } else {
                            forward(event)
                        }
                    }
                    Mode.Drawing, Mode.Moving, Mode.Resizing -> updateDrag(x, y)
                    Mode.Forwarding -> forward(event)
                    Mode.Idle, Mode.Removing -> Unit
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (mode == Mode.Forwarding) forward(event)
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    Mode.Pending -> {
                        forward(event)
                        handleTap(event)
                    }
                    Mode.Forwarding -> forward(event)
                    Mode.Drawing, Mode.Moving, Mode.Resizing -> finishDrag()
                    Mode.Removing -> if (isOnRemoveButton(event.x, event.y)) removeArea()
                    Mode.Idle -> Unit
                }
                mode = Mode.Idle
            }
            MotionEvent.ACTION_CANCEL -> {
                when (mode) {
                    Mode.Pending, Mode.Forwarding -> forward(event)
                    Mode.Drawing, Mode.Moving, Mode.Resizing -> {
                        area.set(startArea)
                        hasArea = startHadArea
                        rebuildPaths()
                    }
                    Mode.Idle, Mode.Removing -> Unit
                }
                mode = Mode.Idle
                invalidate()
            }
        }
        return true
    }

    private fun forward(event: MotionEvent) {
        touchTarget?.dispatchTouchEvent(event)
    }

    /** Takes the gesture from the preview: it gets a cancel so it doesn't treat the lift as a tap. */
    private fun beginDrag(event: MotionEvent) {
        val action = event.action
        event.action = MotionEvent.ACTION_CANCEL
        forward(event)
        event.action = action
        mode = when {
            grabEdges != 0 -> Mode.Resizing
            hasArea && area.contains(downX, downY) -> Mode.Moving
            else -> Mode.Drawing
        }
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun updateDrag(x: Float, y: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        when (mode) {
            Mode.Drawing -> {
                hasArea = true
                area.set(
                    min(downX, x).coerceIn(0f, w),
                    min(downY, y).coerceIn(0f, h),
                    max(downX, x).coerceIn(0f, w),
                    max(downY, y).coerceIn(0f, h),
                )
            }
            Mode.Moving -> {
                val dx = (x - downX).coerceIn(-startArea.left, w - startArea.right)
                val dy = (y - downY).coerceIn(-startArea.top, h - startArea.bottom)
                area.set(startArea)
                area.offset(dx, dy)
            }
            Mode.Resizing -> {
                val dx = x - downX
                val dy = y - downY
                area.set(startArea)
                if (grabEdges and LEFT != 0) area.left = (startArea.left + dx).coerceIn(0f, startArea.right - minWidth)
                if (grabEdges and RIGHT != 0) area.right = (startArea.right + dx).coerceIn(startArea.left + minWidth, w)
                if (grabEdges and TOP != 0) area.top = (startArea.top + dy).coerceIn(0f, startArea.bottom - minHeight)
                if (grabEdges and BOTTOM != 0) area.bottom = (startArea.bottom + dy).coerceIn(startArea.top + minHeight, h)
            }
            else -> return
        }
        rebuildPaths()
        invalidate()
    }

    private fun finishDrag() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (mode == Mode.Drawing) {
            if (area.width() < minWidth && area.height() < minHeight) {
                // Too small to be meant: keep what was there.
                area.set(startArea)
                hasArea = startHadArea
            } else {
                growTo(minWidth, minHeight, w, h)
            }
        }
        if (hasArea && area.width() >= w * ScanAreaDefaults.SNAP_TO_FULL_FRACTION &&
            area.height() >= h * ScanAreaDefaults.SNAP_TO_FULL_FRACTION
        ) {
            hasArea = false
        }
        mode = Mode.Idle
        commit()
    }

    /** Widens a drawn strip to the minimum size around its centre, staying inside the frame. */
    private fun growTo(minW: Float, minH: Float, w: Float, h: Float) {
        if (area.width() < minW) {
            val left = (area.centerX() - minW / 2f).coerceIn(0f, max(0f, w - minW))
            area.left = left
            area.right = min(w, left + minW)
        }
        if (area.height() < minH) {
            val top = (area.centerY() - minH / 2f).coerceIn(0f, max(0f, h - minH))
            area.top = top
            area.bottom = min(h, top + minH)
        }
    }

    private fun handleTap(event: MotionEvent) {
        val now = event.eventTime
        val isDoubleTap = now - lastTapTime <= doubleTapTimeout &&
            abs(event.x - lastTapX) < doubleTapSlop && abs(event.y - lastTapY) < doubleTapSlop
        if (isDoubleTap && hasArea) {
            lastTapTime = 0L
            removeArea()
            return
        }
        lastTapTime = now
        lastTapX = event.x
        lastTapY = event.y
    }

    private fun removeArea() {
        hasArea = false
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        commit()
    }

    private fun commit() {
        if (hasArea && width > 0 && height > 0) {
            fraction.set(area.left / width, area.top / height, area.right / width, area.bottom / height)
        }
        rebuildPaths()
        invalidate()
        val listener = onAreaChanged ?: return
        val result = areaFraction()
        post { listener(result) }
    }

    /** Which sides a touch at ([x], [y]) grabs: a corner grabs two, an edge one, elsewhere none. */
    private fun edgesAt(x: Float, y: Float): Int {
        // On a thin strip the edge reach shrinks so its middle can still be dragged to move it.
        val reachX = min(edgeGrab, area.width() / 3f)
        val reachY = min(edgeGrab, area.height() / 3f)
        val cornerX = max(reachX, min(cornerGrab, area.width() / 2f))
        val cornerY = max(reachY, min(cornerGrab, area.height() / 2f))
        val nearLeft = abs(x - area.left)
        val nearRight = abs(x - area.right)
        val nearTop = abs(y - area.top)
        val nearBottom = abs(y - area.bottom)
        val side = if (nearLeft <= nearRight) LEFT else RIGHT
        val sideDistance = min(nearLeft, nearRight)
        val end = if (nearTop <= nearBottom) TOP else BOTTOM
        val endDistance = min(nearTop, nearBottom)
        if (sideDistance < cornerX && endDistance < cornerY) return side or end
        val withinX = x > area.left - reachX && x < area.right + reachX
        val withinY = y > area.top - reachY && y < area.bottom + reachY
        if (withinY && sideDistance < reachX) return side
        if (withinX && endDistance < reachY) return end
        return 0
    }

    private companion object {
        const val LEFT = 1
        const val TOP = 2
        const val RIGHT = 4
        const val BOTTOM = 8
    }
}
