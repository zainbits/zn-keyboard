package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.SparseArray
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.zain.znkeyboard.KeyboardSettings
import dev.zain.znkeyboard.constants.ImeColors
import dev.zain.znkeyboard.constants.ImeDimensions
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class ZnKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    interface Callback {
        fun onKeyboardAction(action: KeyboardAction, modifiers: ModifierState)
        fun onBackspaceGestureDeleteStarted(): Boolean
        fun onBackspaceGestureDeleteChanged(wordCount: Int)
        fun onBackspaceGestureDeleteFinished()
        fun onBackspaceGestureDeleteCancelled()
        fun onSpaceCursorDragStarted(): Boolean
        fun onSpaceCursorDragChanged(characterDelta: Int)
        fun onSpaceCursorDragFinished()
        fun onSpaceCursorDragCancelled()
        fun onEmojiPanelRequested()
        fun onSnippetPanelRequested()
        fun onEmojiKeySuggestionSelected(emoji: String)
        fun onAgentRewriteRequested()
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
    private val loadingGradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val loadingGlassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(76, 10, 12, 18)
    }
    private val loadingSparklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = PALETTE.text
    }
    private val loadingSparklePath = Path().apply {
        moveTo(0f, -1f)
        lineTo(0.2f, -0.2f)
        lineTo(1f, 0f)
        lineTo(0.2f, 0.2f)
        lineTo(0f, 1f)
        lineTo(-0.2f, 0.2f)
        lineTo(-1f, 0f)
        lineTo(-0.2f, -0.2f)
        close()
    }
    private val loadingGradientMatrix = Matrix()
    private val loadingShaderBounds = RectF()
    private var loadingGradientShader: LinearGradient? = null

    private var layoutMode = LayoutMode.Letters
    private var shiftState = ShiftState.Off
    private var ctrl = false
    private var alt = false
    private var enterLabel = "Enter"
    private var heightScale = 1f
    private var bottomPaddingDp = KeyboardSettings.DEFAULT_BOTTOM_PADDING_DP
    private var upperRowKeyIds = KeyboardSettings.DEFAULT_UPPER_ROW_KEY_IDS
    private var secondRowButtonIds = KeyboardSettings.DEFAULT_SECOND_ROW_BUTTON_IDS
    private var keyboardRowOrder = KeyboardSettings.DEFAULT_KEYBOARD_ROW_ORDER
    private var shortcutRowState = ShortcutRowState(secondRowVisible = true)
    private var emojiKeySuggestion: String? = null
    // The system can draw close-keyboard and IME-switch controls inside the IME window.
    private val bottomSystemControlGapPx by lazy(LazyThreadSafetyMode.NONE) {
        ImeLayout.bottomSystemControlGapPx(context)
    }
    private val touchSlopPx by lazy(LazyThreadSafetyMode.NONE) {
        ViewConfiguration.get(context).scaledTouchSlop.toFloat()
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

    fun setBottomPaddingDp(paddingDp: Int) {
        val normalizedPaddingDp = KeyboardSettings.normalizeBottomPaddingDp(paddingDp)
        if (bottomPaddingDp != normalizedPaddingDp) {
            bottomPaddingDp = normalizedPaddingDp
            requestLayout()
            refreshHitTargets()
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

    fun setSecondRowButtonIds(buttonIds: List<String>) {
        val normalizedButtonIds = KeyboardSettings.normalizeSecondRowButtonIds(buttonIds)
        if (secondRowButtonIds != normalizedButtonIds) {
            secondRowButtonIds = normalizedButtonIds
            requestLayout()
            refreshHitTargets()
            invalidate()
        }
    }

    fun setKeyboardRowOrder(rowIds: List<String>) {
        val normalizedRowIds = KeyboardSettings.normalizeKeyboardRowOrder(rowIds)
        if (keyboardRowOrder != normalizedRowIds) {
            keyboardRowOrder = normalizedRowIds
            requestLayout()
            refreshHitTargets()
            invalidate()
        }
    }

    fun renderShortcutRows(state: ShortcutRowState) {
        if (shortcutRowState != state) {
            shortcutRowState = state
            requestLayout()
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

    fun setEmojiKeySuggestion(emoji: String?) {
        if (emojiKeySuggestion != emoji) {
            emojiKeySuggestion = emoji
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
        val secondRowHeight = if (hasVisibleSecondRow()) {
            ImeLayout.compactAgentRowHeightPx(context, heightScale)
        } else {
            0
        }
        val bottomPaddingDeltaPx = (bottomPaddingDp - ImeLayout.BASE_BOTTOM_PADDING_DP) *
            resources.displayMetrics.density
        val desiredHeight = (
            ImeLayout.BASE_HEIGHT_DP * heightScale * resources.displayMetrics.density +
                bottomSystemControlGapPx +
                bottomPaddingDeltaPx
            )
            .roundToInt()
            .plus(secondRowHeight)
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
        if (shortcutRowState.loading) {
            postInvalidateOnAnimation()
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
        val key = hit?.key?.takeIf { it.enabled }
        val touch = ActiveTouch(key)
        activeTouches.put(pointerId, touch)
        pointerQueue.remove(pointerId)
        pointerQueue.add(pointerId)
        when (key?.id) {
            "comma" -> scheduleLongPress(pointerId, "comma") {
                callback?.onEmojiPanelRequested()
            }
            "emoji" -> emojiKeySuggestion?.let { emoji ->
                scheduleLongPress(pointerId, "emoji") {
                    callback?.onEmojiKeySuggestionSelected(emoji)
                }
            }
            "apostrophe" -> scheduleLongPress(pointerId, "apostrophe") {
                handleKey(KeySpec("backtick_long_press", "`", KeyIntent.Dispatch(KeyboardAction.Text("`"))))
            }
            "space" -> scheduleLongPress(pointerId, "space") {
                callback?.onSnippetPanelRequested()
            }
        }
        if (key?.id == "space") {
            touch.spaceCursorDrag = SpaceCursorDragTouch(
                key = key,
                startX = event.getX(pointerIndex),
                startY = event.getY(pointerIndex),
                stepPx = max(dp(SPACE_CURSOR_DRAG_STEP_DP), hit.visualBounds.width() / SPACE_CURSOR_DRAG_VISIBLE_STEPS),
            )
        }
        key?.takeIf(::isBackspaceKey)?.let { key ->
            touch.backspaceGesture = BackspaceGestureTouch(
                key = key,
                startX = event.getX(pointerIndex),
                startY = event.getY(pointerIndex),
                wordStepPx = max(dp(GESTURE_DELETE_WORD_STEP_DP), hit.visualBounds.width() * 0.72f),
            )
            scheduleBackspaceRepeat(pointerId, key)
        }
    }

    private fun updateActiveTouches(event: MotionEvent): Boolean {
        var changed = false
        for (pointerIndex in 0 until event.pointerCount) {
            val pointerId = event.getPointerId(pointerIndex)
            val touch = activeTouches.get(pointerId) ?: continue
            val x = event.getX(pointerIndex)
            val y = event.getY(pointerIndex)
            if (updateBackspaceGesture(pointerId, touch, x, y)) {
                changed = true
                continue
            }
            if (updateSpaceCursorDrag(pointerId, touch, x, y)) {
                changed = true
                continue
            }

            val key = findHit(x, y)?.key?.takeIf { it.enabled }
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

    private fun updateBackspaceGesture(
        pointerId: Int,
        touch: ActiveTouch,
        x: Float,
        y: Float,
    ): Boolean {
        val gesture = touch.backspaceGesture ?: return false
        if (gesture.consumedWithoutGesture) {
            return true
        }
        if (touch.repeatConsumed && !gesture.active) {
            touch.backspaceGesture = null
            return false
        }

        val dx = x - gesture.startX
        val dy = y - gesture.startY
        val activationDistance = max(touchSlopPx, dp(GESTURE_DELETE_ACTIVATION_DP))
        if (!gesture.active) {
            val leftDistance = -dx
            if (leftDistance < activationDistance || leftDistance < abs(dy) * 1.2f) {
                return false
            }

            cancelBackspaceRepeatFor(pointerId)
            touch.repeatConsumed = true
            touch.currentKey = gesture.key
            if (callback?.onBackspaceGestureDeleteStarted() == true) {
                gesture.active = true
            } else {
                gesture.consumedWithoutGesture = true
                touch.currentKey = null
                return true
            }
        }

        val wordCount = backspaceGestureWordCount(gesture, activationDistance, x)
        if (wordCount != gesture.lastWordCount) {
            gesture.lastWordCount = wordCount
            callback?.onBackspaceGestureDeleteChanged(wordCount)
        }
        return true
    }

    private fun updateSpaceCursorDrag(
        pointerId: Int,
        touch: ActiveTouch,
        x: Float,
        y: Float,
    ): Boolean {
        val gesture = touch.spaceCursorDrag ?: return false
        if (gesture.consumedWithoutGesture) {
            return true
        }

        val dx = x - gesture.startX
        val dy = y - gesture.startY
        val activationDistance = max(touchSlopPx, dp(SPACE_CURSOR_DRAG_ACTIVATION_DP))
        if (!gesture.active) {
            if (abs(dx) < activationDistance || abs(dx) < abs(dy) * 1.2f) {
                return false
            }

            cancelLongPressFor(pointerId)
            touch.cursorDragConsumed = true
            touch.currentKey = gesture.key
            if (callback?.onSpaceCursorDragStarted() == true) {
                gesture.active = true
                clearLatchedModifiers()
            } else {
                gesture.consumedWithoutGesture = true
                touch.currentKey = null
                return true
            }
        }

        val characterDelta = spaceCursorDragCharacterDelta(gesture, x)
        if (characterDelta != gesture.lastCharacterDelta) {
            gesture.lastCharacterDelta = characterDelta
            callback?.onSpaceCursorDragChanged(characterDelta)
        }
        return true
    }

    private fun spaceCursorDragCharacterDelta(gesture: SpaceCursorDragTouch, x: Float): Int {
        val rawDelta = (x - gesture.startX) / gesture.stepPx
        return rawDelta.roundToInt().coerceIn(-SPACE_CURSOR_DRAG_MAX_CHARS, SPACE_CURSOR_DRAG_MAX_CHARS)
    }

    private fun backspaceGestureWordCount(
        gesture: BackspaceGestureTouch,
        activationDistance: Float,
        x: Float,
    ): Int {
        val leftDistance = (gesture.startX - x).coerceAtLeast(0f)
        if (leftDistance < activationDistance) return 0
        return 1 + ((leftDistance - activationDistance) / gesture.wordStepPx).toInt()
    }

    private fun releasePointersThrough(pointerId: Int) {
        while (pointerQueue.isNotEmpty()) {
            val nextPointerId = pointerQueue.removeAt(0)
            val touch = activeTouches.get(nextPointerId)
            activeTouches.remove(nextPointerId)
            finishBackspaceGesture(touch)
            finishSpaceCursorDrag(touch)
            cancelLongPressFor(nextPointerId)
            cancelBackspaceRepeatFor(nextPointerId)
            if (
                touch?.longPressConsumed != true &&
                touch?.repeatConsumed != true &&
                touch?.cursorDragConsumed != true
            ) {
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
        cancelBackspaceGestures()
        cancelSpaceCursorDrags()
        activeTouches.clear()
        pointerQueue.clear()
    }

    private fun finishBackspaceGesture(touch: ActiveTouch?) {
        val gesture = touch?.backspaceGesture ?: return
        if (gesture.active) {
            callback?.onBackspaceGestureDeleteFinished()
        }
        touch.backspaceGesture = null
    }

    private fun finishSpaceCursorDrag(touch: ActiveTouch?) {
        val gesture = touch?.spaceCursorDrag ?: return
        if (gesture.active) {
            callback?.onSpaceCursorDragFinished()
        }
        touch.spaceCursorDrag = null
    }

    private fun cancelBackspaceGestures() {
        for (index in 0 until activeTouches.size()) {
            val gesture = activeTouches.valueAt(index).backspaceGesture
            if (gesture?.active == true) {
                callback?.onBackspaceGestureDeleteCancelled()
            }
        }
    }

    private fun cancelSpaceCursorDrags() {
        for (index in 0 until activeTouches.size()) {
            val gesture = activeTouches.valueAt(index).spaceCursorDrag
            if (gesture?.active == true) {
                callback?.onSpaceCursorDragCancelled()
            }
        }
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
        touch.backspaceGesture = null
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
        val rewriteLoading = key.intent == KeyIntent.AgentRewrite && shortcutRowState.loading
        val pressed = isKeyPressed(key.id)
        val selected = key.active && !rewriteLoading
        val keyAlpha = if (key.enabled || key.emphasizedWhenDisabled) 255 else DISABLED_KEY_ALPHA
        val radius = dp(ImeLayout.KEY_RADIUS_DP.toFloat())
        val containerColor = when {
            rewriteLoading -> PALETTE.aiLoadingSurface
            selected -> PALETTE.selected
            key.role == KeyRole.Action -> PALETTE.action
            key.role == KeyRole.Function -> PALETTE.function
            else -> PALETTE.key
        }
        val contentColor = when {
            rewriteLoading -> PALETTE.text
            key.role == KeyRole.Character -> PALETTE.text
            else -> PALETTE.mutedText
        }

        keyPaint.style = Paint.Style.FILL
        keyPaint.color = if (pressed) {
            ImePressFeedback.stateLayerColor(containerColor, contentColor)
        } else {
            containerColor
        }
        keyPaint.alpha = keyAlpha

        canvas.drawRoundRect(bounds, radius, radius, keyPaint)

        if (rewriteLoading) {
            drawRewriteLoadingWash(canvas, bounds, radius)
        }

        val contentAlpha = if (key.enabled || key.emphasizedWhenDisabled) 255 else DISABLED_CONTENT_ALPHA

        if (rewriteLoading) {
            drawRewriteLoadingContent(canvas, key.label, bounds, contentColor, contentAlpha)
        } else key.icon?.let {
            drawIcon(canvas, it, bounds, contentColor, contentAlpha)
        } ?: run {
            val textSize = fitTextSize(key.label, bounds, key.role)
            textPaint.textSize = textSize
            textPaint.color = contentColor
            textPaint.alpha = contentAlpha
            val metrics = textPaint.fontMetrics
            val baseline = bounds.centerY() - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(key.label, bounds.centerX(), baseline, textPaint)
        }
        drawLongPressHint(canvas, key.longPressHint, bounds, contentColor, contentAlpha)

        if (key.id == "shift" && shiftState == ShiftState.Locked) {
            drawShiftLockIndicator(canvas, bounds, contentColor)
        }
    }

    private fun drawLongPressHint(
        canvas: Canvas,
        hint: String?,
        bounds: RectF,
        color: Int,
        alpha: Int,
    ) {
        if (hint == null) return

        textPaint.textSize = sp(ImeDimensions.LONG_PRESS_HINT_TEXT_SIZE_SP)
            .coerceAtMost(bounds.height() * ImeDimensions.LONG_PRESS_HINT_MAX_HEIGHT_FRACTION)
        textPaint.color = color
        textPaint.alpha = min(alpha, LONG_PRESS_HINT_ALPHA)
        textPaint.textAlign = Paint.Align.RIGHT
        val metrics = textPaint.fontMetrics
        val baseline = bounds.top + dp(5f) - metrics.ascent
        canvas.drawText(hint, bounds.right - dp(6f), baseline, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawRewriteLoadingWash(canvas: Canvas, bounds: RectF, radius: Float) {
        val shader = loadingGradientShader(bounds)
        val progress = loadingProgress()
        val offsetX = sin(TWO_PI * progress) * bounds.width() * 0.72f
        loadingGradientMatrix.setTranslate(offsetX, 0f)
        shader.setLocalMatrix(loadingGradientMatrix)

        loadingGradientPaint.shader = shader
        loadingGradientPaint.alpha = 255
        canvas.drawRoundRect(bounds, radius, radius, loadingGradientPaint)
        loadingGradientPaint.shader = null

        // A translucent surface layer keeps the moving gradient from competing with the label.
        canvas.drawRoundRect(bounds, radius, radius, loadingGlassPaint)
    }

    private fun drawRewriteLoadingContent(
        canvas: Canvas,
        label: String,
        bounds: RectF,
        color: Int,
        alpha: Int,
    ) {
        val textSize = fitTextSize(label, bounds, KeyRole.Action)
        textPaint.textSize = textSize
        textPaint.color = color
        textPaint.alpha = alpha

        val metrics = textPaint.fontMetrics
        val baseline = bounds.centerY() - (metrics.ascent + metrics.descent) / 2f
        val labelWidth = textPaint.measureText(label)
        val sparkleRadius = min(bounds.height() * 0.16f, dp(6.5f))
        val labelGap = dp(6f)
        val sparkleWidth = sparkleRadius * 2f
        val totalWidth = labelWidth + labelGap + sparkleWidth

        if (totalWidth > bounds.width() - dp(6f)) {
            canvas.drawText(label, bounds.centerX(), baseline, textPaint)
            return
        }

        val labelLeft = bounds.centerX() - totalWidth / 2f
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(label, labelLeft, baseline, textPaint)
        textPaint.textAlign = Paint.Align.CENTER

        val progress = loadingProgress()
        val pulse = ((sin(TWO_PI * (progress + 0.12f)) + 1f) / 2f)
        val scale = 0.78f + pulse * 0.28f
        val rotation = progress * 360f
        val sparkleCenterX = labelLeft + labelWidth + labelGap + sparkleRadius
        val sparkleCenterY = bounds.centerY()
        loadingSparklePaint.color = color
        loadingSparklePaint.alpha = (150 + pulse * 105).roundToInt().coerceAtMost(alpha)

        canvas.save()
        canvas.translate(sparkleCenterX, sparkleCenterY)
        canvas.rotate(rotation)
        canvas.scale(sparkleRadius * scale, sparkleRadius * scale)
        canvas.drawPath(loadingSparklePath, loadingSparklePaint)
        canvas.restore()

        val miniPulse = ((sin(TWO_PI * (progress + 0.58f)) + 1f) / 2f)
        loadingSparklePaint.alpha = (72 + miniPulse * 80).roundToInt().coerceAtMost(alpha)
        canvas.save()
        canvas.translate(
            sparkleCenterX + cos(TWO_PI * progress) * sparkleRadius * 1.1f,
            sparkleCenterY + sin(TWO_PI * progress) * sparkleRadius * 0.72f,
        )
        canvas.rotate(-rotation * 2f + 35f)
        canvas.scale(sparkleRadius * 0.36f, sparkleRadius * 0.36f)
        canvas.drawPath(loadingSparklePath, loadingSparklePaint)
        canvas.restore()
    }

    private fun loadingGradientShader(bounds: RectF): LinearGradient {
        val existingShader = loadingGradientShader
        if (
            existingShader != null &&
            loadingShaderBounds.left == bounds.left &&
            loadingShaderBounds.top == bounds.top &&
            loadingShaderBounds.right == bounds.right &&
            loadingShaderBounds.bottom == bounds.bottom
        ) {
            return existingShader
        }
        loadingShaderBounds.set(bounds)
        val gradientWidth = bounds.width() * 1.45f
        return LinearGradient(
            bounds.left - gradientWidth,
            bounds.top,
            bounds.right + gradientWidth,
            bounds.bottom,
            AI_LOADING_COLORS,
            AI_LOADING_POSITIONS,
            Shader.TileMode.CLAMP,
        ).also { shader ->
            loadingGradientShader = shader
        }
    }

    private fun loadingProgress(): Float {
        return (SystemClock.uptimeMillis() % AI_LOADING_CYCLE_MS) / AI_LOADING_CYCLE_MS.toFloat()
    }

    private fun drawIcon(canvas: Canvas, icon: KeyIcon, bounds: RectF, color: Int, alpha: Int) {
        val iconSize = min(bounds.width(), bounds.height()) * 0.5f
        val left = bounds.centerX() - iconSize / 2f
        val top = bounds.centerY() - iconSize / 2f

        iconPaint.color = color
        iconPaint.alpha = alpha
        iconPaint.strokeWidth = 2.2f
        iconPaint.style = Paint.Style.STROKE

        canvas.save()
        canvas.translate(left, top)
        canvas.scale(iconSize / ImeLayout.ICON_VIEWPORT, iconSize / ImeLayout.ICON_VIEWPORT)
        when (icon) {
            KeyIcon.Emoji -> drawEmojiIcon(canvas)
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

    private fun drawEmojiIcon(canvas: Canvas) {
        canvas.drawCircle(12f, 12f, 7f, iconPaint)
        iconPaint.style = Paint.Style.FILL
        canvas.drawCircle(9.2f, 10.4f, 0.9f, iconPaint)
        canvas.drawCircle(14.8f, 10.4f, 0.9f, iconPaint)
        iconPaint.style = Paint.Style.STROKE
        val smile = Path().apply {
            moveTo(8.6f, 14f)
            cubicTo(10.2f, 16f, 13.8f, 16f, 15.4f, 14f)
        }
        canvas.drawPath(smile, iconPaint)
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

    private fun Path.addRoundRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rx: Float,
        ry: Float,
        dir: Path.Direction,
    ) {
        addRoundRect(RectF(left, top, right, bottom), rx, ry, dir)
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
            KeyRole.Character -> sp(ImeDimensions.KEY_LABEL_CHARACTER_TEXT_SIZE_SP)
            KeyRole.Function -> sp(ImeDimensions.KEY_LABEL_FUNCTION_TEXT_SIZE_SP)
            KeyRole.Action -> sp(ImeDimensions.KEY_LABEL_ACTION_TEXT_SIZE_SP)
        }
        var size = base.coerceAtMost(bounds.height() * ImeDimensions.KEY_LABEL_MAX_HEIGHT_FRACTION)
        val min = sp(ImeDimensions.KEY_LABEL_MIN_TEXT_SIZE_SP)
        val maxWidth = bounds.width() - dp(ImeDimensions.KEY_LABEL_HORIZONTAL_INSET_DP)

        textPaint.textSize = size
        while (textPaint.measureText(label) > maxWidth && size > min) {
            size -= sp(ImeDimensions.KEY_LABEL_SHRINK_STEP_SP)
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
            KeyIntent.ToggleMoreSymbols -> {
                layoutMode = if (layoutMode == LayoutMode.MoreSymbols) LayoutMode.Symbols else LayoutMode.MoreSymbols
                shiftState = ShiftState.Off
            }
            KeyIntent.ToggleAlt -> alt = !alt
            KeyIntent.ToggleCtrl -> ctrl = !ctrl
            KeyIntent.OpenEmojiPanel -> callback?.onEmojiPanelRequested()
            KeyIntent.AgentRewrite -> callback?.onAgentRewriteRequested()
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
        val bottomPadding = dp(bottomPaddingDp.toFloat()) + bottomSystemControlGapPx
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
        val buttonRowSpecs = mapOf(
            KeyboardSettings.KeyboardRow.Upper.id to terminalRow(),
            KeyboardSettings.KeyboardRow.Second.id to secondRow(),
        )
        val buttonRows = keyboardRowOrder.mapNotNull { rowId -> buttonRowSpecs[rowId] }
        val mainRows = when (layoutMode) {
            LayoutMode.Letters -> listOf(
                RowSpec(chars("qwertyuiop"), ImeLayout.STANDARD_ROW_WEIGHT),
                RowSpec(chars("asdfghjkl"), ImeLayout.STANDARD_ROW_WEIGHT, layoutKeyCount = 10),
                letterBottomRow(),
                bottomRow(),
            )
            LayoutMode.Symbols -> listOf(
                RowSpec(chars("1234567890"), ImeLayout.STANDARD_ROW_WEIGHT),
                RowSpec(symbols("-/:;()\$&@\"", rowPrefix = "symbol_middle"), ImeLayout.STANDARD_ROW_WEIGHT),
                symbolBottomRow(),
                bottomRow(),
            )
            LayoutMode.MoreSymbols -> listOf(
                RowSpec(symbols("[]{}#%^*+=", rowPrefix = "more_symbol_top"), ImeLayout.STANDARD_ROW_WEIGHT),
                RowSpec(symbols("_\\|~<>€£¥•", rowPrefix = "more_symbol_middle"), ImeLayout.STANDARD_ROW_WEIGHT),
                moreSymbolBottomRow(),
                bottomRow(),
            )
        }
        return buttonRows + mainRows
    }

    private fun terminalRow(): RowSpec? {
        val keys = upperRowKeyIds.mapIndexedNotNull { index, keyId ->
            upperRowKeySpec(
                keyId = keyId,
                index = index,
                weight = upperRowKeyWeight(index, upperRowKeyIds.lastIndex),
                rowPrefix = "upper",
                enabled = true,
            )
        }
        return keys.takeIf { it.isNotEmpty() }?.let { RowSpec(it, heightWeight = ImeLayout.COMPACT_ROW_WEIGHT) }
    }

    private fun secondRow(): RowSpec? {
        if (!hasVisibleSecondRow()) return null
        val keys = secondRowButtonIds.mapIndexedNotNull { index, buttonId ->
            upperRowKeySpec(
                keyId = buttonId,
                index = index,
                weight = upperRowKeyWeight(index, secondRowButtonIds.lastIndex),
                rowPrefix = "second",
                enabled = true,
            )
        }
        return keys.takeIf { it.isNotEmpty() }?.let { RowSpec(it, heightWeight = ImeLayout.COMPACT_ROW_WEIGHT) }
    }

    private fun hasVisibleSecondRow(): Boolean {
        return shortcutRowState.secondRowVisible && secondRowButtonIds.isNotEmpty()
    }

    private fun upperRowKeyWeight(index: Int, lastIndex: Int): Float {
        return if (index == 0 || index == lastIndex) {
            ImeDimensions.EDGE_UPPER_ROW_KEY_WEIGHT
        } else {
            ImeDimensions.DEFAULT_KEY_WEIGHT
        }
    }

    private fun upperRowKeySpec(
        keyId: String,
        index: Int,
        weight: Float,
        rowPrefix: String,
        enabled: Boolean,
    ): KeySpec? {
        val id = "${rowPrefix}_${index}_$keyId"
        return when (keyId) {
            "rewrite" -> KeySpec(
                id = id,
                label = if (shortcutRowState.loading) "Rewriting" else "Rewrite",
                intent = KeyIntent.AgentRewrite,
                weight = weight,
                role = KeyRole.Action,
                active = shortcutRowState.loading,
                enabled = enabled && shortcutRowState.rewriteEnabled && !shortcutRowState.loading,
                emphasizedWhenDisabled = shortcutRowState.loading,
            )
            "ctrl" -> KeySpec(id, "Ctrl", KeyIntent.ToggleCtrl, weight, KeyRole.Function, ctrl, enabled = enabled)
            "alt" -> KeySpec(id, "Alt", KeyIntent.ToggleAlt, weight, KeyRole.Function, alt, enabled = enabled)
            "tab" -> KeySpec(id, "Tab", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_TAB)), weight, KeyRole.Function, enabled = enabled)
            "esc" -> KeySpec(id, "Esc", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_ESCAPE)), weight, KeyRole.Action, enabled = enabled)
            "left" -> KeySpec(id, "Left", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_DPAD_LEFT)), weight, KeyRole.Function, icon = KeyIcon.ArrowLeft, enabled = enabled)
            "up" -> KeySpec(id, "Up", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_DPAD_UP)), weight, KeyRole.Function, icon = KeyIcon.ArrowUp, enabled = enabled)
            "down" -> KeySpec(id, "Down", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_DPAD_DOWN)), weight, KeyRole.Function, icon = KeyIcon.ArrowDown, enabled = enabled)
            "right" -> KeySpec(id, "Right", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)), weight, KeyRole.Function, icon = KeyIcon.ArrowRight, enabled = enabled)
            "home" -> KeySpec(id, "Home", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_MOVE_HOME)), weight, KeyRole.Function, enabled = enabled)
            "end" -> KeySpec(id, "End", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_MOVE_END)), weight, KeyRole.Function, enabled = enabled)
            "page_up" -> KeySpec(id, "PgUp", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_PAGE_UP)), weight, KeyRole.Function, enabled = enabled)
            "page_down" -> KeySpec(id, "PgDn", KeyIntent.Dispatch(KeyboardAction.KeyCode(KeyEvent.KEYCODE_PAGE_DOWN)), weight, KeyRole.Function, enabled = enabled)
            "backspace" -> KeySpec(id, "Del", KeyIntent.Dispatch(KeyboardAction.Backspace), weight, KeyRole.Function, icon = KeyIcon.Delete, enabled = enabled)
            "pipe" -> textUpperRowKey(id, "|", weight, enabled)
            "slash" -> textUpperRowKey(id, "/", weight, enabled)
            "backslash" -> textUpperRowKey(id, "\\", weight, enabled)
            "minus" -> textUpperRowKey(id, "-", weight, enabled)
            "equals" -> textUpperRowKey(id, "=", weight, enabled)
            "underscore" -> textUpperRowKey(id, "_", weight, enabled)
            "plus" -> textUpperRowKey(id, "+", weight, enabled)
            "colon" -> textUpperRowKey(id, ":", weight, enabled)
            "semicolon" -> textUpperRowKey(id, ";", weight, enabled)
            "quote" -> textUpperRowKey(id, "\"", weight, enabled)
            "apostrophe" -> textUpperRowKey(id, "'", weight, enabled)
            "backtick" -> textUpperRowKey(id, "`", weight, enabled)
            "at" -> textUpperRowKey(id, "@", weight, enabled)
            "hash" -> textUpperRowKey(id, "#", weight, enabled)
            "dollar" -> textUpperRowKey(id, "\$", weight, enabled)
            "ampersand" -> textUpperRowKey(id, "&", weight, enabled)
            "star" -> textUpperRowKey(id, "*", weight, enabled)
            "left_paren" -> textUpperRowKey(id, "(", weight, enabled)
            "right_paren" -> textUpperRowKey(id, ")", weight, enabled)
            "left_bracket" -> textUpperRowKey(id, "[", weight, enabled)
            "right_bracket" -> textUpperRowKey(id, "]", weight, enabled)
            "left_brace" -> textUpperRowKey(id, "{", weight, enabled)
            "right_brace" -> textUpperRowKey(id, "}", weight, enabled)
            "less_than" -> textUpperRowKey(id, "<", weight, enabled)
            "greater_than" -> textUpperRowKey(id, ">", weight, enabled)
            else -> null
        }
    }

    private fun textUpperRowKey(id: String, value: String, weight: Float, enabled: Boolean): KeySpec {
        return KeySpec(
            id = id,
            label = value,
            intent = KeyIntent.Dispatch(KeyboardAction.Text(value)),
            weight = weight,
            role = KeyRole.Character,
            enabled = enabled,
        )
    }

    private fun letterBottomRow(): RowSpec {
        val keys = mutableListOf<KeySpec>()
        keys += KeySpec(
            "shift",
            "Shift",
            KeyIntent.Shift,
            ImeDimensions.SIDE_FUNCTION_KEY_WEIGHT,
            KeyRole.Function,
            shiftState != ShiftState.Off,
            KeyIcon.Shift,
        )
        keys += chars("zxcvbnm")
        keys += KeySpec(
            "backspace",
            "Del",
            KeyIntent.Dispatch(KeyboardAction.Backspace),
            ImeDimensions.SIDE_FUNCTION_KEY_WEIGHT,
            KeyRole.Function,
            icon = KeyIcon.Delete,
        )
        return RowSpec(keys, ImeLayout.STANDARD_ROW_WEIGHT)
    }

    private fun symbolBottomRow(): RowSpec {
        return RowSpec(
            listOf(
                KeySpec(
                    "more_symbols",
                    "#+=",
                    KeyIntent.ToggleMoreSymbols,
                    ImeDimensions.SIDE_FUNCTION_KEY_WEIGHT,
                    KeyRole.Function,
                ),
                KeySpec("period", ".", KeyIntent.Dispatch(KeyboardAction.Text(".")), role = KeyRole.Character),
                KeySpec("comma", ",", KeyIntent.Dispatch(KeyboardAction.Text(",")), role = KeyRole.Character),
                KeySpec("question", "?", KeyIntent.Dispatch(KeyboardAction.Text("?")), role = KeyRole.Character),
                KeySpec("bang", "!", KeyIntent.Dispatch(KeyboardAction.Text("!")), role = KeyRole.Character),
                KeySpec("apostrophe", "'", KeyIntent.Dispatch(KeyboardAction.Text("'")), role = KeyRole.Character, longPressHint = "`"),
                KeySpec(
                    "backspace",
                    "Del",
                    KeyIntent.Dispatch(KeyboardAction.Backspace),
                    ImeDimensions.SIDE_FUNCTION_KEY_WEIGHT,
                    KeyRole.Function,
                    icon = KeyIcon.Delete,
                ),
            ),
            heightWeight = ImeLayout.STANDARD_ROW_WEIGHT,
        )
    }

    private fun moreSymbolBottomRow(): RowSpec {
        return RowSpec(
            listOf(
                KeySpec(
                    "more_symbols",
                    "123",
                    KeyIntent.ToggleMoreSymbols,
                    ImeDimensions.SIDE_FUNCTION_KEY_WEIGHT,
                    KeyRole.Function,
                ),
                KeySpec("period", ".", KeyIntent.Dispatch(KeyboardAction.Text(".")), role = KeyRole.Character),
                KeySpec("comma", ",", KeyIntent.Dispatch(KeyboardAction.Text(",")), role = KeyRole.Character),
                KeySpec("question", "?", KeyIntent.Dispatch(KeyboardAction.Text("?")), role = KeyRole.Character),
                KeySpec("bang", "!", KeyIntent.Dispatch(KeyboardAction.Text("!")), role = KeyRole.Character),
                KeySpec("apostrophe", "'", KeyIntent.Dispatch(KeyboardAction.Text("'")), role = KeyRole.Character, longPressHint = "`"),
                KeySpec(
                    "backspace",
                    "Del",
                    KeyIntent.Dispatch(KeyboardAction.Backspace),
                    ImeDimensions.SIDE_FUNCTION_KEY_WEIGHT,
                    KeyRole.Function,
                    icon = KeyIcon.Delete,
                ),
            ),
            heightWeight = ImeLayout.STANDARD_ROW_WEIGHT,
        )
    }

    private fun bottomRow(): RowSpec {
        val switchLabel = if (layoutMode == LayoutMode.Letters) "123" else "ABC"
        if (layoutMode != LayoutMode.Letters) {
            return RowSpec(
                listOf(
                    KeySpec(
                        "switch",
                        switchLabel,
                        KeyIntent.SwitchMode,
                        ImeDimensions.MODE_SWITCH_KEY_WEIGHT,
                        KeyRole.Function,
                    ),
                    emojiKeySpec(),
                    KeySpec(
                        "space",
                        "space",
                        KeyIntent.Dispatch(KeyboardAction.Text(" ")),
                        ImeDimensions.SPACE_KEY_WEIGHT,
                        KeyRole.Function,
                        longPressHint = "Saved",
                    ),
                    KeySpec(
                        "enter",
                        enterLabel,
                        KeyIntent.Dispatch(KeyboardAction.Enter),
                        ImeDimensions.ENTER_KEY_WEIGHT,
                        KeyRole.Action,
                        icon = iconForEnterLabel(enterLabel),
                    ),
                ),
                heightWeight = ImeLayout.BOTTOM_ROW_WEIGHT,
            )
        }
        return RowSpec(
            listOf(
                KeySpec(
                    "switch",
                    switchLabel,
                    KeyIntent.SwitchMode,
                    ImeDimensions.MODE_SWITCH_KEY_WEIGHT,
                    KeyRole.Function,
                ),
                emojiKeySpec(),
                KeySpec(
                    "space",
                    "space",
                    KeyIntent.Dispatch(KeyboardAction.Text(" ")),
                    ImeDimensions.SPACE_KEY_WEIGHT,
                    KeyRole.Function,
                    longPressHint = "Saved",
                ),
                KeySpec(
                    "enter",
                    enterLabel,
                    KeyIntent.Dispatch(KeyboardAction.Enter),
                    ImeDimensions.ENTER_KEY_WEIGHT,
                    KeyRole.Action,
                    icon = iconForEnterLabel(enterLabel),
                ),
            ),
            heightWeight = ImeLayout.BOTTOM_ROW_WEIGHT,
        )
    }

    private fun emojiKeySpec(): KeySpec {
        val suggestion = emojiKeySuggestion
        return KeySpec(
            id = "emoji",
            label = suggestion ?: "Emoji",
            intent = KeyIntent.OpenEmojiPanel,
            weight = ImeDimensions.EMOJI_KEY_WEIGHT,
            role = KeyRole.Function,
            icon = if (suggestion == null) KeyIcon.Emoji else null,
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

    private fun symbols(source: String, rowPrefix: String = "symbol"): List<KeySpec> {
        return source.mapIndexed { index, char ->
            KeySpec(
                id = "${rowPrefix}_$index",
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
        MoreSymbols,
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
        Emoji,
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
        val weight: Float = ImeDimensions.DEFAULT_KEY_WEIGHT,
        val role: KeyRole = KeyRole.Character,
        val active: Boolean = false,
        val icon: KeyIcon? = null,
        val consumesOneShotShift: Boolean = false,
        val enabled: Boolean = true,
        val emphasizedWhenDisabled: Boolean = false,
        val longPressHint: String? = null,
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
        var cursorDragConsumed: Boolean = false,
        var backspaceGesture: BackspaceGestureTouch? = null,
        var spaceCursorDrag: SpaceCursorDragTouch? = null,
    )

    private data class BackspaceGestureTouch(
        val key: KeySpec,
        val startX: Float,
        val startY: Float,
        val wordStepPx: Float,
        var active: Boolean = false,
        var consumedWithoutGesture: Boolean = false,
        var lastWordCount: Int = 0,
    )

    private data class SpaceCursorDragTouch(
        val key: KeySpec,
        val startX: Float,
        val startY: Float,
        val stepPx: Float,
        var active: Boolean = false,
        var consumedWithoutGesture: Boolean = false,
        var lastCharacterDelta: Int = 0,
    )

    private sealed class KeyIntent {
        data object Shift : KeyIntent()
        data object SwitchMode : KeyIntent()
        data object ToggleMoreSymbols : KeyIntent()
        data object ToggleAlt : KeyIntent()
        data object ToggleCtrl : KeyIntent()
        data object OpenEmojiPanel : KeyIntent()
        data object AgentRewrite : KeyIntent()
        data class Dispatch(val action: KeyboardAction) : KeyIntent()
    }

    private object PALETTE {
        val background = ImeColors.BACKGROUND
        val key = ImeColors.KEY
        val function = ImeColors.FUNCTION
        val action = ImeColors.ACTION
        val selected = ImeColors.SELECTED
        val aiLoadingSurface = ImeColors.AI_LOADING_SURFACE
        const val text = ImeColors.TEXT
        val mutedText = ImeColors.MUTED_TEXT
    }

    data class ShortcutRowState(
        val secondRowVisible: Boolean,
        val loading: Boolean = false,
        val rewriteEnabled: Boolean = false,
    )

    private companion object {
        const val DISABLED_KEY_ALPHA = 118
        const val DISABLED_CONTENT_ALPHA = 130
        const val LONG_PRESS_HINT_ALPHA = 170
        const val GESTURE_DELETE_ACTIVATION_DP = 10f
        const val GESTURE_DELETE_WORD_STEP_DP = 42f
        const val SPACE_CURSOR_DRAG_ACTIVATION_DP = 8f
        const val SPACE_CURSOR_DRAG_STEP_DP = 14f
        const val SPACE_CURSOR_DRAG_VISIBLE_STEPS = 28f
        const val SPACE_CURSOR_DRAG_MAX_CHARS = 120
        const val AI_LOADING_CYCLE_MS = 2200L
        const val TWO_PI = 6.2831855f
        val AI_LOADING_COLORS = intArrayOf(
            Color.argb(0, 73, 216, 255),
            Color.argb(88, 73, 216, 255),
            Color.argb(128, 140, 108, 255),
            Color.argb(118, 255, 95, 189),
            Color.argb(104, 255, 204, 92),
            Color.argb(112, 88, 230, 161),
            Color.argb(0, 73, 216, 255),
        )
        val AI_LOADING_POSITIONS = floatArrayOf(0f, 0.18f, 0.34f, 0.5f, 0.66f, 0.82f, 1f)
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
