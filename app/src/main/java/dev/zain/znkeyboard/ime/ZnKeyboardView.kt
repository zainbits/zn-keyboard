package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.SparseArray
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.zain.znkeyboard.KeyboardSettings
import kotlin.math.min
import kotlin.math.roundToInt

class ZnKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    interface Callback {
        fun onKeyboardAction(action: KeyboardAction, modifiers: ModifierState)
        fun onEmojiPanelRequested()
        fun onSnippetPanelRequested()
    }

    var callback: Callback? = null

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PALETTE.text
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var layoutMode = LayoutMode.Letters
    private var shiftState = ShiftState.Off
    private var ctrl = false
    private var alt = false
    private var enterLabel = "Enter"
    private var heightScale = 1f
    private var upperRowKeyIds = KeyboardSettings.DEFAULT_UPPER_ROW_KEY_IDS
    // The system can draw close-keyboard and IME-switch controls inside the IME window.
    private val bottomSystemControlGapPx by lazy(LazyThreadSafetyMode.NONE) {
        ImeLayout.bottomSystemControlGapPx(context)
    }
    private val activeTouches = SparseArray<ActiveTouch>()
    private val pointerQueue = mutableListOf<Int>()
    private var hitTargets: List<KeyHit> = emptyList()
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var pendingLongPressPointerId: Int? = null
    private var pendingLongPressKeyId: String? = null
    private var pendingLongPressRunnable: Runnable? = null
    private val backspaceRepeatRunnable = Runnable { repeatBackspace() }
    private var repeatingBackspacePointerId: Int? = null
    private var repeatingBackspaceKeyId: String? = null
    private var backspaceRepeatCount = 0

    init {
        isHapticFeedbackEnabled = false
        isSoundEffectsEnabled = false
        isClickable = true
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
    }

    fun setHeightScale(scale: Float) {
        if (heightScale != scale) {
            heightScale = scale
            requestLayout()
            invalidate()
        }
    }

    fun setUpperRowKeyIds(keyIds: List<String>) {
        val normalizedKeyIds = KeyboardSettings.normalizeUpperRowKeyIds(keyIds)
        if (upperRowKeyIds != normalizedKeyIds) {
            upperRowKeyIds = normalizedKeyIds
            refreshHitTargets()
            invalidate()
        }
    }

    fun setEnterLabel(label: String) {
        if (enterLabel != label) {
            enterLabel = label
            refreshHitTargets()
            invalidate()
        }
    }

    fun clearLatchedModifiers() {
        if (ctrl || alt) {
            ctrl = false
            alt = false
            refreshHitTargets()
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (ImeLayout.BASE_HEIGHT_DP * heightScale * resources.displayMetrics.density + bottomSystemControlGapPx)
            .roundToInt()
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(PALETTE.background)
        if (hitTargets.isEmpty()) {
            refreshHitTargets()
        }

        hitTargets.forEach { hit ->
            drawKey(canvas, hit)
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        refreshHitTargets()
    }

    override fun onDetachedFromWindow() {
        cancelActiveTouches()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelActiveTouches()
                handlePointerDown(event, event.actionIndex)
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                updateActiveTouches(event)
                handlePointerDown(event, event.actionIndex)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (updateActiveTouches(event)) {
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                updateActiveTouches(event)
                releasePointersThrough(event.getPointerId(event.actionIndex))
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                updateActiveTouches(event)
                releasePointersThrough(event.getPointerId(event.actionIndex))
                activeTouches.clear()
                pointerQueue.clear()
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelActiveTouches()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handlePointerDown(event: MotionEvent, pointerIndex: Int) {
        val pointerId = event.getPointerId(pointerIndex)
        val hit = findHit(event.getX(pointerIndex), event.getY(pointerIndex))
        activeTouches.put(pointerId, ActiveTouch(hit?.key))
        pointerQueue.remove(pointerId)
        pointerQueue.add(pointerId)
        when (hit?.key?.id) {
            "comma" -> scheduleLongPress(pointerId, "comma") {
                callback?.onEmojiPanelRequested()
            }
            "period" -> scheduleLongPress(pointerId, "period") {
                callback?.onSnippetPanelRequested()
            }
        }
        hit?.key?.takeIf(::isBackspaceKey)?.let { key ->
            scheduleBackspaceRepeat(pointerId, key)
        }
    }

    private fun updateActiveTouches(event: MotionEvent): Boolean {
        var changed = false
        for (pointerIndex in 0 until event.pointerCount) {
            val pointerId = event.getPointerId(pointerIndex)
            val touch = activeTouches.get(pointerId) ?: continue
            val key = findHit(event.getX(pointerIndex), event.getY(pointerIndex))?.key
            if (touch.currentKey?.id != key?.id) {
                touch.currentKey = key
                if (pendingLongPressPointerId == pointerId && key?.id != pendingLongPressKeyId) {
                    cancelPendingLongPress()
                }
                when {
                    repeatingBackspacePointerId == pointerId && key?.id != repeatingBackspaceKeyId -> {
                        cancelBackspaceRepeat()
                    }
                    repeatingBackspacePointerId != pointerId && key?.let(::isBackspaceKey) == true -> {
                        scheduleBackspaceRepeat(pointerId, key)
                    }
                }
                changed = true
            }
        }
        return changed
    }

    private fun releasePointersThrough(pointerId: Int) {
        while (pointerQueue.isNotEmpty()) {
            val nextPointerId = pointerQueue.removeAt(0)
            val touch = activeTouches.get(nextPointerId)
            activeTouches.remove(nextPointerId)
            cancelLongPressFor(nextPointerId)
            cancelBackspaceRepeatFor(nextPointerId)
            if (touch?.longPressConsumed != true && touch?.repeatConsumed != true) {
                touch?.currentKey?.let(::handleKey)
            }
            if (nextPointerId == pointerId) {
                return
            }
        }
        activeTouches.remove(pointerId)
        cancelLongPressFor(pointerId)
        cancelBackspaceRepeatFor(pointerId)
    }

    private fun cancelActiveTouches() {
        cancelPendingLongPress()
        cancelBackspaceRepeat()
        activeTouches.clear()
        pointerQueue.clear()
    }

    private fun scheduleLongPress(
        pointerId: Int,
        keyId: String,
        onLongPress: () -> Unit,
    ) {
        cancelPendingLongPress()
        pendingLongPressPointerId = pointerId
        pendingLongPressKeyId = keyId
        pendingLongPressRunnable = Runnable {
            val touch = activeTouches.get(pointerId) ?: return@Runnable
            if (touch.currentKey?.id != keyId) return@Runnable

            touch.longPressConsumed = true
            touch.currentKey = null
            pointerQueue.remove(pointerId)
            pendingLongPressPointerId = null
            pendingLongPressKeyId = null
            pendingLongPressRunnable = null
            onLongPress()
            invalidate()
        }.also { runnable ->
            longPressHandler.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
        }
    }

    private fun cancelLongPressFor(pointerId: Int) {
        if (pendingLongPressPointerId == pointerId) {
            cancelPendingLongPress()
        }
    }

    private fun cancelPendingLongPress() {
        pendingLongPressRunnable?.let(longPressHandler::removeCallbacks)
        pendingLongPressRunnable = null
        pendingLongPressPointerId = null
        pendingLongPressKeyId = null
    }

    private fun scheduleBackspaceRepeat(pointerId: Int, key: KeySpec) {
        cancelBackspaceRepeat()
        repeatingBackspacePointerId = pointerId
        repeatingBackspaceKeyId = key.id
        backspaceRepeatCount = 0
        longPressHandler.postDelayed(
            backspaceRepeatRunnable,
            BackspaceRepeatTiming.startDelayMillis(),
        )
    }

    private fun repeatBackspace() {
        val pointerId = repeatingBackspacePointerId ?: return
        val keyId = repeatingBackspaceKeyId ?: return
        val touch = activeTouches.get(pointerId) ?: run {
            cancelBackspaceRepeat()
            return
        }
        val key = touch.currentKey
        if (key?.id != keyId || !isBackspaceKey(key)) {
            cancelBackspaceRepeat()
            return
        }

        touch.repeatConsumed = true
        backspaceRepeatCount += 1
        handleKey(key)
        invalidate()
        longPressHandler.postDelayed(
            backspaceRepeatRunnable,
            BackspaceRepeatTiming.repeatDelayMillis(backspaceRepeatCount),
        )
    }

    private fun cancelBackspaceRepeatFor(pointerId: Int) {
        if (repeatingBackspacePointerId == pointerId) {
            cancelBackspaceRepeat()
        }
    }

    private fun cancelBackspaceRepeat() {
        longPressHandler.removeCallbacks(backspaceRepeatRunnable)
        repeatingBackspacePointerId = null
        repeatingBackspaceKeyId = null
        backspaceRepeatCount = 0
    }

    private fun isBackspaceKey(key: KeySpec): Boolean {
        return (key.intent as? KeyIntent.Dispatch)?.action == KeyboardAction.Backspace
    }

    private fun drawKey(canvas: Canvas, hit: KeyHit) {
        val key = hit.key
        val bounds = hit.visualBounds
        val active = key.active || isKeyPressed(key.id)
        keyPaint.style = Paint.Style.FILL
        keyPaint.color = when {
            active -> PALETTE.accent
            key.role == KeyRole.Action -> PALETTE.action
            key.role == KeyRole.Function -> PALETTE.function
            else -> PALETTE.key
        }

        val radius = dp(ImeLayout.KEY_RADIUS_DP.toFloat())
        canvas.drawRoundRect(bounds, radius, radius, keyPaint)

        val contentColor = when {
            active -> PALETTE.text
            key.role == KeyRole.Character -> PALETTE.text
            else -> PALETTE.mutedText
        }

        key.icon?.let {
            drawIcon(canvas, it, bounds, contentColor)
        } ?: run {
            val textSize = fitTextSize(key.label, bounds, key.role)
            textPaint.textSize = textSize
            textPaint.color = contentColor
            val metrics = textPaint.fontMetrics
            val baseline = bounds.centerY() - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(key.label, bounds.centerX(), baseline, textPaint)
        }

        if (key.id == "shift" && shiftState == ShiftState.Locked) {
            drawShiftLockIndicator(canvas, bounds, contentColor)
        }
    }

    private fun drawIcon(canvas: Canvas, icon: KeyIcon, bounds: RectF, color: Int) {
        val iconSize = min(bounds.width(), bounds.height()) * 0.5f
        val left = bounds.centerX() - iconSize / 2f
        val top = bounds.centerY() - iconSize / 2f

        iconPaint.color = color
        iconPaint.strokeWidth = 2.2f
        iconPaint.style = Paint.Style.STROKE

        canvas.save()
        canvas.translate(left, top)
        canvas.scale(iconSize / ImeLayout.ICON_VIEWPORT, iconSize / ImeLayout.ICON_VIEWPORT)
        when (icon) {
            KeyIcon.ArrowLeft -> drawArrowIcon(canvas, ArrowDirection.Left)
            KeyIcon.ArrowUp -> drawArrowIcon(canvas, ArrowDirection.Up)
            KeyIcon.ArrowDown -> drawArrowIcon(canvas, ArrowDirection.Down)
            KeyIcon.ArrowRight -> drawArrowIcon(canvas, ArrowDirection.Right)
            KeyIcon.Shift -> drawShiftIcon(canvas)
            KeyIcon.Delete -> drawDeleteIcon(canvas)
            KeyIcon.Search -> drawSearchIcon(canvas)
            KeyIcon.Enter -> drawEnterIcon(canvas)
        }
        canvas.restore()
    }

    private fun drawArrowIcon(canvas: Canvas, direction: ArrowDirection) {
        val path = Path()
        when (direction) {
            ArrowDirection.Left -> {
                path.moveTo(19f, 12f)
                path.lineTo(5f, 12f)
                path.moveTo(11f, 6f)
                path.lineTo(5f, 12f)
                path.lineTo(11f, 18f)
            }
            ArrowDirection.Up -> {
                path.moveTo(12f, 19f)
                path.lineTo(12f, 5f)
                path.moveTo(6f, 11f)
                path.lineTo(12f, 5f)
                path.lineTo(18f, 11f)
            }
            ArrowDirection.Down -> {
                path.moveTo(12f, 5f)
                path.lineTo(12f, 19f)
                path.moveTo(6f, 13f)
                path.lineTo(12f, 19f)
                path.lineTo(18f, 13f)
            }
            ArrowDirection.Right -> {
                path.moveTo(5f, 12f)
                path.lineTo(19f, 12f)
                path.moveTo(13f, 6f)
                path.lineTo(19f, 12f)
                path.lineTo(13f, 18f)
            }
        }
        canvas.drawPath(path, iconPaint)
    }

    private fun drawShiftIcon(canvas: Canvas) {
        val path = Path().apply {
            moveTo(12f, 4f)
            lineTo(5f, 11f)
            lineTo(9f, 11f)
            lineTo(9f, 20f)
            lineTo(15f, 20f)
            lineTo(15f, 11f)
            lineTo(19f, 11f)
            close()
        }
        canvas.drawPath(path, iconPaint)
    }

    private fun drawShiftLockIndicator(canvas: Canvas, bounds: RectF, color: Int) {
        val dashWidth = min(bounds.width() * 0.32f, dp(18f))
        val centerX = bounds.centerX()
        val y = bounds.bottom - dp(7f)
        iconPaint.color = color
        iconPaint.strokeWidth = dp(2f)
        iconPaint.style = Paint.Style.STROKE
        canvas.drawLine(centerX - dashWidth / 2f, y, centerX + dashWidth / 2f, y, iconPaint)
    }

    private fun drawDeleteIcon(canvas: Canvas) {
        val outline = Path().apply {
            moveTo(21f, 6f)
            lineTo(8.5f, 6f)
            lineTo(3f, 12f)
            lineTo(8.5f, 18f)
            lineTo(21f, 18f)
            close()
        }
        val cross = Path().apply {
            moveTo(10f, 9f)
            lineTo(16f, 15f)
            moveTo(16f, 9f)
            lineTo(10f, 15f)
        }
        canvas.drawPath(outline, iconPaint)
        canvas.drawPath(cross, iconPaint)
    }

    private fun drawSearchIcon(canvas: Canvas) {
        canvas.drawCircle(10.5f, 10.5f, 5.5f, iconPaint)
        val handle = Path().apply {
            moveTo(15f, 15f)
            lineTo(20f, 20f)
        }
        canvas.drawPath(handle, iconPaint)
    }

    private fun drawEnterIcon(canvas: Canvas) {
        val path = Path().apply {
            moveTo(19f, 5f)
            lineTo(19f, 12f)
            lineTo(6f, 12f)
            moveTo(12f, 6f)
            lineTo(6f, 12f)
            lineTo(12f, 18f)
        }
        canvas.drawPath(path, iconPaint)
    }

    private fun isKeyPressed(keyId: String): Boolean {
        for (index in 0 until activeTouches.size()) {
            if (activeTouches.valueAt(index).currentKey?.id == keyId) {
                return true
            }
        }
        return false
    }

    private fun findHit(x: Float, y: Float): KeyHit? {
        if (hitTargets.isEmpty()) {
            refreshHitTargets()
        }
        return hitTargets.firstOrNull { it.hitBounds.contains(x, y) }
    }

    private fun fitTextSize(label: String, bounds: RectF, role: KeyRole): Float {
        val base = when (role) {
            KeyRole.Character -> sp(20f)
            KeyRole.Function -> sp(12.5f)
            KeyRole.Action -> sp(13.5f)
        }
        var size = base.coerceAtMost(bounds.height() * 0.48f)
        val min = sp(9f)
        val maxWidth = bounds.width() - dp(8f)

        textPaint.textSize = size
        while (textPaint.measureText(label) > maxWidth && size > min) {
            size -= sp(0.5f)
            textPaint.textSize = size
        }
        return size
    }

    private fun handleKey(key: KeySpec) {
        when (val intent = key.intent) {
            KeyIntent.Shift -> shiftState = when (shiftState) {
                ShiftState.Off -> ShiftState.OneShot
                ShiftState.OneShot -> ShiftState.Locked
                ShiftState.Locked -> ShiftState.Off
            }
            KeyIntent.SwitchMode -> {
                layoutMode = if (layoutMode == LayoutMode.Letters) LayoutMode.Symbols else LayoutMode.Letters
                shiftState = ShiftState.Off
            }
            KeyIntent.ToggleAlt -> alt = !alt
            KeyIntent.ToggleCtrl -> ctrl = !ctrl
            is KeyIntent.Dispatch -> {
                callback?.onKeyboardAction(intent.action, ModifierState(ctrl = ctrl, alt = alt))
                if (shiftState == ShiftState.OneShot && key.consumesOneShotShift) {
                    shiftState = ShiftState.Off
                }
                if (ctrl || alt) {
                    ctrl = false
                    alt = false
                }
            }
        }
        refreshHitTargets()
    }

    private fun refreshHitTargets() {
        if (width > 0 && height > 0) {
            hitTargets = layoutKeys(width.toFloat(), height.toFloat())
        }
    }

    private fun layoutKeys(totalWidth: Float, totalHeight: Float): List<KeyHit> {
        val rows = rows()
        val horizontalPadding = dp(ImeLayout.HORIZONTAL_PADDING_DP.toFloat())
        val topPadding = dp(ImeLayout.TOP_PADDING_DP.toFloat())
        val bottomPadding = dp(ImeLayout.BASE_BOTTOM_PADDING_DP.toFloat()) + bottomSystemControlGapPx
        val keyGap = dp(ImeLayout.KEY_GAP_DP.toFloat())
        val rowGap = dp(ImeLayout.ROW_GAP_DP.toFloat())
        val rowWeightSum = rows.fold(0f) { total, row -> total + row.heightWeight }
        val usableHeight = totalHeight - topPadding - bottomPadding - rowGap * (rows.size - 1)
        val unitHeight = usableHeight / rowWeightSum

        val visualRows = mutableListOf<List<VisualKey>>()
        var top = topPadding
        rows.forEach { row ->
            val rowHeight = row.heightWeight * unitHeight
            val keys = row.keys
            val keyWeightSum = keys.fold(0f) { total, key -> total + key.weight }
            val layoutKeyCount = row.layoutKeyCount ?: keys.size
            val layoutWeightSum = row.layoutKeyCount?.toFloat() ?: keyWeightSum
            val usableWidth = totalWidth - horizontalPadding * 2f - keyGap * (layoutKeyCount - 1)
            val rowWidth = usableWidth * (keyWeightSum / layoutWeightSum) + keyGap * (keys.size - 1)
            var left = horizontalPadding + (totalWidth - horizontalPadding * 2f - rowWidth) / 2f

            val visualKeys = mutableListOf<VisualKey>()
            keys.forEach { key ->
                val keyWidth = usableWidth * (key.weight / layoutWeightSum)
                val bounds = RectF(left, top, left + keyWidth, top + rowHeight)
                visualKeys += VisualKey(key, bounds)
                left += keyWidth + keyGap
            }
            visualRows += visualKeys

            top += rowHeight + rowGap
        }

        val keyboardBottom = (totalHeight - bottomSystemControlGapPx).coerceAtLeast(0f)
        return visualRows.flatMapIndexed { rowIndex, row ->
            val rowTop = if (rowIndex == 0) {
                0f
            } else {
                val previousRow = visualRows[rowIndex - 1]
                midpoint(previousRow.first().visualBounds.bottom, row.first().visualBounds.top)
            }
            val rowBottom = if (rowIndex == visualRows.lastIndex) {
                keyboardBottom
            } else {
                val nextRow = visualRows[rowIndex + 1]
                midpoint(row.first().visualBounds.bottom, nextRow.first().visualBounds.top)
            }

            row.mapIndexed { keyIndex, visualKey ->
                val hitLeft = if (keyIndex == 0) {
                    0f
                } else {
                    midpoint(row[keyIndex - 1].visualBounds.right, visualKey.visualBounds.left)
                }
                val hitRight = if (keyIndex == row.lastIndex) {
                    totalWidth
                } else {
                    midpoint(visualKey.visualBounds.right, row[keyIndex + 1].visualBounds.left)
                }

                KeyHit(
                    key = visualKey.key,
                    hitBounds = RectF(hitLeft, rowTop, hitRight, rowBottom),
                    visualBounds = visualKey.visualBounds,
                )
            }
        }
    }

    private fun midpoint(start: Float, end: Float): Float = start + (end - start) / 2f

    private fun rows(): List<RowSpec> {
        return buildList {
            terminalRow()?.let(::add)
            if (layoutMode == LayoutMode.Letters) {
                add(RowSpec(chars("qwertyuiop"), 1f))
                add(RowSpec(chars("asdfghjkl"), 1f, layoutKeyCount = 10))
                add(letterBottomRow())
            } else {
                add(RowSpec(chars("1234567890"), 1f))
                add(RowSpec(symbols("@#\$_&-+()"), 1f))
                add(symbolBottomRow())
            }
            add(bottomRow())
        }
    }

    private fun terminalRow(): RowSpec? {
        val keys = upperRowKeyIds.mapIndexedNotNull { index, keyId ->
            upperRowKeySpec(
                keyId = keyId,
                index = index,
                weight = upperRowKeyWeight(index, upperRowKeyIds.lastIndex),
            )
        }
        return keys.takeIf { it.isNotEmpty() }?.let { RowSpec(it, heightWeight = ImeLayout.COMPACT_ROW_WEIGHT) }
    }

    private fun upperRowKeyWeight(index: Int, lastIndex: Int): Float {
        return if (index == 0 || index == lastIndex) 1.4f else 1f
    }

    private fun upperRowKeySpec(keyId: String, index: Int, weight: Float): KeySpec? {
        val id = "upper_${index}_$keyId"
        return when (keyId) {
            "ctrl" -> KeySpec(id, "Ctrl", KeyIntent.ToggleCtrl, weight, KeyRole.Function, ctrl)
            "alt" -> KeySpec(id, "Alt", KeyIntent.ToggleAlt, weight, KeyRole.Function, alt)
            "tab" -> KeySpec(id, "Tab", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_TAB)), weight, KeyRole.Function)
            "esc" -> KeySpec(id, "Esc", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_ESCAPE)), weight, KeyRole.Action)
            "left" -> KeySpec(id, "Left", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_DPAD_LEFT)), weight, KeyRole.Function, icon = KeyIcon.ArrowLeft)
            "up" -> KeySpec(id, "Up", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_DPAD_UP)), weight, KeyRole.Function, icon = KeyIcon.ArrowUp)
            "down" -> KeySpec(id, "Down", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_DPAD_DOWN)), weight, KeyRole.Function, icon = KeyIcon.ArrowDown)
            "right" -> KeySpec(id, "Right", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)), weight, KeyRole.Function, icon = KeyIcon.ArrowRight)
            "home" -> KeySpec(id, "Home", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_MOVE_HOME)), weight, KeyRole.Function)
            "end" -> KeySpec(id, "End", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_MOVE_END)), weight, KeyRole.Function)
            "page_up" -> KeySpec(id, "PgUp", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_PAGE_UP)), weight, KeyRole.Function)
            "page_down" -> KeySpec(id, "PgDn", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_PAGE_DOWN)), weight, KeyRole.Function)
            "backspace" -> KeySpec(id, "Del", KeyIntent.Dispatch(KeyboardAction.Backspace), weight, KeyRole.Function, icon = KeyIcon.Delete)
            "pipe" -> textUpperRowKey(id, "|", weight)
            "slash" -> textUpperRowKey(id, "/", weight)
            "backslash" -> textUpperRowKey(id, "\\", weight)
            "minus" -> textUpperRowKey(id, "-", weight)
            "equals" -> textUpperRowKey(id, "=", weight)
            "underscore" -> textUpperRowKey(id, "_", weight)
            "plus" -> textUpperRowKey(id, "+", weight)
            "colon" -> textUpperRowKey(id, ":", weight)
            "semicolon" -> textUpperRowKey(id, ";", weight)
            "quote" -> textUpperRowKey(id, "\"", weight)
            "apostrophe" -> textUpperRowKey(id, "'", weight)
            "backtick" -> textUpperRowKey(id, "`", weight)
            "at" -> textUpperRowKey(id, "@", weight)
            "hash" -> textUpperRowKey(id, "#", weight)
            "dollar" -> textUpperRowKey(id, "\$", weight)
            "ampersand" -> textUpperRowKey(id, "&", weight)
            "star" -> textUpperRowKey(id, "*", weight)
            "left_paren" -> textUpperRowKey(id, "(", weight)
            "right_paren" -> textUpperRowKey(id, ")", weight)
            "left_bracket" -> textUpperRowKey(id, "[", weight)
            "right_bracket" -> textUpperRowKey(id, "]", weight)
            "left_brace" -> textUpperRowKey(id, "{", weight)
            "right_brace" -> textUpperRowKey(id, "}", weight)
            "less_than" -> textUpperRowKey(id, "<", weight)
            "greater_than" -> textUpperRowKey(id, ">", weight)
            else -> null
        }
    }

    private fun textUpperRowKey(id: String, value: String, weight: Float): KeySpec {
        return KeySpec(
            id = id,
            label = value,
            intent = KeyIntent.Dispatch(KeyboardAction.Text(value)),
            weight = weight,
            role = KeyRole.Character,
        )
    }

    private fun letterBottomRow(): RowSpec {
        val keys = mutableListOf<KeySpec>()
        keys += KeySpec("shift", "Shift", KeyIntent.Shift, 1.35f, KeyRole.Function, shiftState != ShiftState.Off, KeyIcon.Shift)
        keys += chars("zxcvbnm")
        keys += KeySpec("backspace", "Del", KeyIntent.Dispatch(KeyboardAction.Backspace), 1.35f, KeyRole.Function, icon = KeyIcon.Delete)
        return RowSpec(keys, 1f)
    }

    private fun symbolBottomRow(): RowSpec {
        return RowSpec(
            listOf(
                KeySpec("star", "*", KeyIntent.Dispatch(KeyboardAction.Text("*")), role = KeyRole.Character),
                KeySpec("quote", "\"", KeyIntent.Dispatch(KeyboardAction.Text("\"")), role = KeyRole.Character),
                KeySpec("apostrophe", "'", KeyIntent.Dispatch(KeyboardAction.Text("'")), role = KeyRole.Character),
                KeySpec("colon", ":", KeyIntent.Dispatch(KeyboardAction.Text(":")), role = KeyRole.Character),
                KeySpec("semicolon", ";", KeyIntent.Dispatch(KeyboardAction.Text(";")), role = KeyRole.Character),
                KeySpec("bang", "!", KeyIntent.Dispatch(KeyboardAction.Text("!")), role = KeyRole.Character),
                KeySpec("question", "?", KeyIntent.Dispatch(KeyboardAction.Text("?")), role = KeyRole.Character),
                KeySpec("backspace", "Del", KeyIntent.Dispatch(KeyboardAction.Backspace), 1.35f, KeyRole.Function, icon = KeyIcon.Delete),
            ),
            heightWeight = 1f,
        )
    }

    private fun bottomRow(): RowSpec {
        val switchLabel = if (layoutMode == LayoutMode.Letters) "123" else "ABC"
        return RowSpec(
            listOf(
                KeySpec("switch", switchLabel, KeyIntent.SwitchMode, 1.25f, KeyRole.Function),
                KeySpec("comma", ",", KeyIntent.Dispatch(KeyboardAction.Text(",")), 0.9f, KeyRole.Character),
                KeySpec("space", "space", KeyIntent.Dispatch(KeyboardAction.Text(" ")), 4.2f, KeyRole.Function),
                KeySpec("period", ".", KeyIntent.Dispatch(KeyboardAction.Text(".")), 0.9f, KeyRole.Character),
                KeySpec("enter", enterLabel, KeyIntent.Dispatch(KeyboardAction.Enter), 1.55f, KeyRole.Action, icon = iconForEnterLabel(enterLabel)),
            ),
            heightWeight = 1.08f,
        )
    }

    private fun chars(source: String): List<KeySpec> {
        return source.map { char ->
            val label = if (shiftState != ShiftState.Off) char.uppercaseChar().toString() else char.toString()
            KeySpec(
                id = "char_$char",
                label = label,
                intent = KeyIntent.Dispatch(KeyboardAction.Text(label)),
                role = KeyRole.Character,
                consumesOneShotShift = true,
            )
        }
    }

    private fun symbols(source: String): List<KeySpec> {
        return source.mapIndexed { index, char ->
            KeySpec(
                id = "symbol_$index",
                label = char.toString(),
                intent = KeyIntent.Dispatch(KeyboardAction.Text(char.toString())),
                role = KeyRole.Character,
            )
        }
    }

    private fun iconForEnterLabel(label: String): KeyIcon? {
        return when (label) {
            "Enter" -> KeyIcon.Enter
            "Search" -> KeyIcon.Search
            else -> null
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    }

    private enum class LayoutMode {
        Letters,
        Symbols,
    }

    private enum class ShiftState {
        Off,
        OneShot,
        Locked,
    }

    private enum class KeyRole {
        Character,
        Function,
        Action,
    }

    private enum class KeyIcon {
        ArrowLeft,
        ArrowUp,
        ArrowDown,
        ArrowRight,
        Shift,
        Delete,
        Search,
        Enter,
    }

    private enum class ArrowDirection {
        Left,
        Up,
        Down,
        Right,
    }

    private data class RowSpec(
        val keys: List<KeySpec>,
        val heightWeight: Float,
        val layoutKeyCount: Int? = null,
    )

    private data class KeySpec(
        val id: String,
        val label: String,
        val intent: KeyIntent,
        val weight: Float = 1f,
        val role: KeyRole = KeyRole.Character,
        val active: Boolean = false,
        val icon: KeyIcon? = null,
        val consumesOneShotShift: Boolean = false,
    )

    private data class KeyHit(
        val key: KeySpec,
        val hitBounds: RectF,
        val visualBounds: RectF,
    )

    private data class VisualKey(
        val key: KeySpec,
        val visualBounds: RectF,
    )

    private data class ActiveTouch(
        var currentKey: KeySpec?,
        var longPressConsumed: Boolean = false,
        var repeatConsumed: Boolean = false,
    )

    private sealed class KeyIntent {
        data object Shift : KeyIntent()
        data object SwitchMode : KeyIntent()
        data object ToggleAlt : KeyIntent()
        data object ToggleCtrl : KeyIntent()
        data class Dispatch(val action: KeyboardAction) : KeyIntent()
    }

    private object PALETTE {
        val background = Color.BLACK
        val key = Color.rgb(42, 42, 42)
        val function = Color.rgb(50, 50, 50)
        val action = Color.rgb(42, 42, 42)
        val accent = Color.rgb(48, 172, 226)
        const val text = Color.WHITE
        val mutedText = Color.rgb(230, 230, 230)
    }
}

data class ModifierState(
    val ctrl: Boolean,
    val alt: Boolean,
)

sealed class KeyboardAction {
    data class Text(val value: String) : KeyboardAction()
    data class KeyCode(val keyCode: Int) : KeyboardAction()
    data object Backspace : KeyboardAction()
    data object Enter : KeyboardAction()
}
