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
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.zain.znkeyboard.KeyboardSettings
import dev.zain.znkeyboard.MacroStep
import dev.zain.znkeyboard.ShortcutMacro
import dev.zain.znkeyboard.ShortcutMacros
import dev.zain.znkeyboard.constants.ImeColors
import dev.zain.znkeyboard.constants.ImeDimensions
import dev.zain.znkeyboard.typing.AdaptiveTypingEngine
import dev.zain.znkeyboard.typing.CharLanguageModel
import dev.zain.znkeyboard.typing.TypingLearner
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.hypot
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
        fun onSpaceCursorDragStarted(extendSelection: Boolean): Boolean
        fun onSpaceCursorDragChanged(characterDelta: Int)
        fun onSpaceCursorDragFinished()
        fun onSpaceCursorDragCancelled()
        fun onEmojiPanelRequested()
        fun onSnippetPanelRequested()
        fun onEmojiKeySuggestionSelected(emoji: String)
        fun onAgentRewriteRequested()
        fun onSaveSnippetRequested()
        fun onComposerSuggestionSelected(text: String)
        fun onTransliterationSelected(text: String)
        fun onHindiModeToggled()
    }

    var callback: Callback? = null

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letterTypeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 400, false)
    private val labelTypeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 500, false)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PALETTE.text
        textAlign = Paint.Align.CENTER
        typeface = letterTypeface
    }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val previewBounds = RectF()
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
    private var lastNumericLayoutMode = LayoutMode.Symbols
    private var shiftState = ShiftState.Off
    private var ctrl = false
    private var alt = false
    private var enterLabel = "Enter"
    private var heightScale = 1f
    private var bottomPaddingDp = KeyboardSettings.DEFAULT_BOTTOM_PADDING_DP
    private var functionKeyBackgroundsEnabled = true
    private var upperRowKeyIds = KeyboardSettings.DEFAULT_UPPER_ROW_KEY_IDS
    private var secondRowButtonIds = KeyboardSettings.DEFAULT_SECOND_ROW_BUTTON_IDS
    private var keyboardRowOrder = KeyboardSettings.DEFAULT_KEYBOARD_ROW_ORDER
    private var shortcutMacrosByKeyId: Map<String, ShortcutMacro> = emptyMap()
    private var shortcutRowState = ShortcutRowState(secondRowVisible = true)
    private var emojiKeySuggestion: String? = null
    private var suggestionStrip: List<SuggestionStripItem?> = emptyList()
    private var keyHapticsEnabled = true
    private var keyPreviewEnabled = true
    private var autoShiftActive = false
    private var lastShiftTapAt = 0L

    /** Personal touch decoder + learner; null means plain geometric hit-testing. */
    var typingEngine: AdaptiveTypingEngine? = null

    // Letter geometry of the current layout, in dp, for the adaptive decoder. Fresh arrays per layout
    // so learning samples keep the geometry they were captured with.
    private var letterHits = arrayOfNulls<KeyHit>(26)
    private var letterCentersXDp = FloatArray(26)
    private var letterCentersYDp = FloatArray(26)
    private var letterPitchXDp = 0f
    private var letterPitchYDp = 0f
    private var letterGeometryValid = false
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
        isHapticFeedbackEnabled = true
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

    fun setKeyHapticsEnabled(enabled: Boolean) {
        keyHapticsEnabled = enabled
    }

    fun setKeyPreviewEnabled(enabled: Boolean) {
        keyPreviewEnabled = enabled
    }

    /**
     * Sentence-start capitalization requested by the editor's caps mode. Only drives Shift while the
     * user has not chosen a shift state themselves; caps lock is never overridden.
     */
    fun setAutoShift(enabled: Boolean) {
        if (layoutMode != LayoutMode.Letters) return
        val next = when {
            shiftState == ShiftState.Locked -> return
            enabled && shiftState == ShiftState.Off -> ShiftState.OneShot
            !enabled && shiftState == ShiftState.OneShot && autoShiftActive -> ShiftState.Off
            else -> shiftState
        }
        val nextAuto = enabled && next == ShiftState.OneShot && (autoShiftActive || shiftState == ShiftState.Off)
        if (next != shiftState || nextAuto != autoShiftActive) {
            shiftState = next
            autoShiftActive = nextAuto
            refreshHitTargets()
            invalidate()
        }
    }

    fun setFunctionKeyBackgroundsEnabled(enabled: Boolean) {
        if (functionKeyBackgroundsEnabled != enabled) {
            functionKeyBackgroundsEnabled = enabled
            invalidate()
        }
    }

    fun setUpperRowKeyIds(keyIds: List<String>) {
        val normalizedKeyIds = KeyboardSettings.normalizeUpperRowKeyIds(keyIds)
        if (upperRowKeyIds != normalizedKeyIds) {
            upperRowKeyIds = normalizedKeyIds
            requestLayout()
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

    fun setShortcutMacros(macros: List<ShortcutMacro>) {
        val byKeyId = macros.associateBy(ShortcutMacro::keyId)
        if (shortcutMacrosByKeyId != byKeyId) {
            shortcutMacrosByKeyId = byKeyId
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

    /** Up to [SUGGESTION_STRIP_SLOTS] items, left to right; null leaves a slot empty. */
    fun setSuggestionStrip(items: List<SuggestionStripItem?>) {
        val normalized = items.take(SUGGESTION_STRIP_SLOTS)
        if (suggestionStrip != normalized) {
            suggestionStrip = normalized
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

    fun setPhonePadMode(enabled: Boolean) {
        val targetMode = if (enabled) LayoutMode.PhonePad else LayoutMode.Letters
        lastNumericLayoutMode = if (enabled) LayoutMode.PhonePad else LayoutMode.Symbols
        if (layoutMode != targetMode) {
            layoutMode = targetMode
            shiftState = ShiftState.Off
            requestLayout()
            refreshHitTargets()
            invalidate()
        }
    }

    fun resetToLetters() {
        if (layoutMode != LayoutMode.Letters) {
            layoutMode = LayoutMode.Letters
            requestLayout()
            refreshHitTargets()
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Each visible row adds its own height, so showing or hiding a row never resizes the keys.
        val rows = layoutRows()
        val rowWeightSum = rows.fold(0f) { total, row -> total + row.heightWeight }
        val desiredHeight = (
            dp(ImeLayout.TOP_PADDING_DP.toFloat()) +
                rowWeightSum * ImeLayout.rowUnitHeightPx(context, heightScale) +
                dp(ImeLayout.ROW_GAP_DP.toFloat()) * (rows.size - 1) +
                dp(bottomPaddingDp.toFloat()) +
                bottomSystemControlGapPx
            ).roundToInt()
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
        if (keyPreviewEnabled) {
            drawKeyPreviews(canvas)
        }
        if (shortcutRowState.loading) {
            postInvalidateOnAnimation()
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        typingEngine?.setLandscape(
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
        )
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
        val downX = event.getX(pointerIndex)
        val downY = event.getY(pointerIndex)
        val hit = findHit(downX, downY)
        val touch = ActiveTouch(null, downX = downX, downY = downY)
        // Resolve the key at touch-down, where the user aimed, not where the finger rolls to on lift.
        val key = resolveDownKey(hit, downX, downY, touch)
        touch.currentKey = key
        activeTouches.put(pointerId, touch)
        if (key != null && keyHapticsEnabled) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
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
            "phone_star" -> scheduleLongPress(pointerId, "phone_star") {
                handleKey(KeySpec("phone_slash_long_press", "/", KeyIntent.Dispatch(KeyboardAction.Text("/"))))
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
                stepPx = max(dp(SPACE_CURSOR_DRAG_STEP_DP), (hit?.visualBounds?.width() ?: 0f) / SPACE_CURSOR_DRAG_VISIBLE_STEPS),
            )
        }
        key?.takeIf(::isBackspaceKey)?.let { key ->
            touch.backspaceGesture = BackspaceGestureTouch(
                key = key,
                startX = event.getX(pointerIndex),
                startY = event.getY(pointerIndex),
                wordStepPx = max(dp(GESTURE_DELETE_WORD_STEP_DP), (hit?.visualBounds?.width() ?: 0f) * 0.72f),
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

            if (!touch.slid) {
                // Contact centroids drift while a finger rolls through a tap. Keep the touch-down key
                // until the finger has clearly travelled, then allow deliberate slide-to-correct.
                if (hypot(x - touch.downX, y - touch.downY) < slideHysteresisPx()) continue
                touch.slid = true
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
                typingEngine?.learner?.discardPending()
            } else {
                gesture.consumedWithoutGesture = true
                touch.currentKey = null
                return true
            }
        }

        val wordCount = backspaceGestureWordCount(gesture, activationDistance, x)
        if (wordCount != gesture.lastWordCount) {
            gesture.lastWordCount = wordCount
            if (keyHapticsEnabled) performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
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
            val shiftTouch = heldShiftTouch(pointerId)
            if (callback?.onSpaceCursorDragStarted(extendSelection = shiftTouch != null) == true) {
                gesture.active = true
                shiftTouch?.heldAsModifier = true
                typingEngine?.learner?.onContextChanged(null)
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
            if (keyHapticsEnabled) performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
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
        val shiftHeld = activeTouches.get(pointerId)?.let { claimHeldShift(pointerId, it) } == true
        var queueIndex = 0
        while (queueIndex < pointerQueue.size) {
            val nextPointerId = pointerQueue[queueIndex]
            val touch = activeTouches.get(nextPointerId)
            // A Shift held for selecting stays down until its own finger lifts.
            if (nextPointerId != pointerId && touch?.heldAsModifier == true) {
                queueIndex++
                continue
            }
            pointerQueue.removeAt(queueIndex)
            activeTouches.remove(nextPointerId)
            finishBackspaceGesture(touch)
            finishSpaceCursorDrag(touch)
            cancelLongPressFor(nextPointerId)
            cancelBackspaceRepeatFor(nextPointerId)
            if (
                touch?.longPressConsumed != true &&
                touch?.repeatConsumed != true &&
                touch?.cursorDragConsumed != true &&
                touch?.heldAsModifier != true
            ) {
                touch?.currentKey?.let { key ->
                    handleKey(key, touch.learningSample(key), shiftHeld = shiftHeld && nextPointerId == pointerId)
                }
            }
            if (nextPointerId == pointerId) {
                return
            }
        }
        activeTouches.remove(pointerId)
        cancelLongPressFor(pointerId)
        cancelBackspaceRepeatFor(pointerId)
    }

    /** Key-code keys (arrows, Home, Tab…) tapped while Shift is physically held get Shift applied. */
    private fun claimHeldShift(pointerId: Int, touch: ActiveTouch): Boolean {
        val intent = touch.currentKey?.intent as? KeyIntent.Dispatch ?: return false
        if (intent.action !is KeyboardAction.KeyCode) return false
        if (touch.longPressConsumed || touch.repeatConsumed || touch.cursorDragConsumed) return false
        val shiftTouch = heldShiftTouch(pointerId) ?: return false
        shiftTouch.heldAsModifier = true
        return true
    }

    private fun heldShiftTouch(excludingPointerId: Int): ActiveTouch? {
        for (index in 0 until activeTouches.size()) {
            if (activeTouches.keyAt(index) == excludingPointerId) continue
            val touch = activeTouches.valueAt(index)
            if (touch.currentKey?.intent == KeyIntent.Shift) return touch
        }
        return null
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
        if (key.intent == KeyIntent.None) return
        val bounds = hit.visualBounds
        val rewriteLoading = key.intent == KeyIntent.AgentRewrite && shortcutRowState.loading
        val pressed = isKeyPressed(key.id)
        val selected = key.active && !rewriteLoading
        val toolbar = hit.rowStyle == RowStyle.Toolbar || hit.rowStyle == RowStyle.Suggestion
        val keyAlpha = if (key.enabled || key.emphasizedWhenDisabled) 255 else DISABLED_KEY_ALPHA
        val radius = keyRadius(bounds, toolbar)
        val isActionKey = key.role == KeyRole.Action && key.id == "enter"
        // Space is typed as often as letters; it shares their surface rather than looking like a modifier.
        val isSpace = key.id == "space" || key.id == "phone_space"
        val drawBackground = when {
            pressed || selected || rewriteLoading || isSpace -> true
            // Shortcut rows read as a quiet toolbar: content only, with a pill on press / latch.
            toolbar -> hit.rowStyle == RowStyle.Suggestion || key.intent == KeyIntent.AgentRewrite
            key.role == KeyRole.Function -> functionKeyBackgroundsEnabled
            else -> true
        }
        val containerColor = when {
            rewriteLoading -> PALETTE.aiLoadingSurface
            selected -> PALETTE.selected
            toolbar -> PALETTE.function
            isActionKey -> PALETTE.action
            isSpace -> PALETTE.key
            key.role == KeyRole.Function || key.role == KeyRole.Action -> PALETTE.function
            hit.rowStyle == RowStyle.Numbers -> PALETTE.keyQuiet
            else -> PALETTE.key
        }
        val contentColor = when {
            rewriteLoading -> PALETTE.text
            selected -> PALETTE.onSelected
            isActionKey -> PALETTE.onAction
            key.role == KeyRole.Character -> PALETTE.text
            else -> PALETTE.mutedText
        }

        keyPaint.style = Paint.Style.FILL
        keyPaint.color = if (pressed) {
            ImePressFeedback.contentStateLayer(containerColor, contentColor, PRESSED_LAYER_OPACITY)
        } else {
            containerColor
        }
        keyPaint.alpha = keyAlpha

        if (drawBackground) {
            canvas.drawRoundRect(bounds, radius, radius, keyPaint)
        }

        if (rewriteLoading) {
            drawRewriteLoadingWash(canvas, bounds, radius)
        }

        val contentAlpha = if (key.enabled || key.emphasizedWhenDisabled) 255 else DISABLED_CONTENT_ALPHA

        if (rewriteLoading) {
            drawRewriteLoadingContent(canvas, key.label, bounds, contentColor, contentAlpha)
        } else key.icon?.let {
            drawIcon(canvas, it, bounds, contentColor, contentAlpha)
        } ?: run {
            if (key.subLabel != null) {
                when (key.subLabelStyle) {
                    SubLabelStyle.Small -> drawStackedLabel(canvas, key.label, key.subLabel, bounds, contentColor, contentAlpha)
                    SubLabelStyle.Equal -> drawEqualStackedLabel(canvas, key.label, key.subLabel, bounds, contentColor, contentAlpha)
                }
            } else {
                val role = if (toolbar && key.role == KeyRole.Character && key.label.length > 1) KeyRole.Function else key.role
                val textSize = fitTextSize(key.label, bounds, role, hit.rowStyle)
                textPaint.typeface = if (role == KeyRole.Character) letterTypeface else labelTypeface
                textPaint.textSize = textSize
                textPaint.color = contentColor
                textPaint.alpha = contentAlpha
                val metrics = textPaint.fontMetrics
                val baseline = bounds.centerY() - (metrics.ascent + metrics.descent) / 2f
                canvas.drawText(key.label, bounds.centerX(), baseline, textPaint)
                textPaint.typeface = letterTypeface
            }
        }
        drawLongPressHint(canvas, key.longPressHint, bounds, contentColor, contentAlpha)

        if (key.id == "shift" && shiftState == ShiftState.Locked) {
            drawShiftLockIndicator(canvas, bounds, contentColor)
        }
    }

    private fun keyRadius(bounds: RectF, toolbar: Boolean): Float {
        val preferred = dp(ImeLayout.KEY_RADIUS_DP.toFloat())
        val cap = bounds.height() * if (toolbar) 0.5f else 0.3f
        return min(if (toolbar) bounds.height() / 2f else preferred, cap)
    }

    /**
     * Character preview above the finger. The finger occludes the very key it is pressing, so a
     * visible, offset confirmation is what lets users trust a tap without looking for the result.
     */
    private fun drawKeyPreviews(canvas: Canvas) {
        for (index in 0 until activeTouches.size()) {
            val touch = activeTouches.valueAt(index)
            val key = touch.currentKey ?: continue
            if (key.role != KeyRole.Character || key.icon != null || key.label.length > 2) continue
            val hit = hitTargets.firstOrNull { it.key.id == key.id } ?: continue
            if (hit.rowStyle == RowStyle.Toolbar || hit.rowStyle == RowStyle.Suggestion) continue
            val bounds = hit.visualBounds
            val bubbleWidth = max(bounds.width() * PREVIEW_WIDTH_FACTOR, dp(PREVIEW_MIN_WIDTH_DP))
            val bubbleHeight = bounds.height() * PREVIEW_HEIGHT_FACTOR
            var left = bounds.centerX() - bubbleWidth / 2f
            left = left.coerceIn(dp(2f), width - bubbleWidth - dp(2f))
            var top = bounds.top - bubbleHeight - dp(PREVIEW_GAP_DP)
            if (top < 0f) top = 0f
            previewBounds.set(left, top, left + bubbleWidth, top + bubbleHeight)
            val radius = min(dp(ImeLayout.KEY_RADIUS_DP.toFloat() + 4f), bubbleHeight * 0.3f)
            previewPaint.color = PALETTE.preview
            previewPaint.setShadowLayer(dp(6f), 0f, dp(2f), PREVIEW_SHADOW_COLOR)
            canvas.drawRoundRect(previewBounds, radius, radius, previewPaint)
            previewPaint.clearShadowLayer()

            textPaint.typeface = letterTypeface
            textPaint.color = PALETTE.text
            textPaint.alpha = 255
            textPaint.textSize = min(sp(PREVIEW_TEXT_SIZE_SP), bubbleHeight * 0.62f)
            val metrics = textPaint.fontMetrics
            val baseline = previewBounds.centerY() - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(key.label, previewBounds.centerX(), baseline, textPaint)
        }
    }

    private fun drawStackedLabel(
        canvas: Canvas,
        label: String,
        subLabel: String,
        bounds: RectF,
        color: Int,
        alpha: Int,
    ) {
        val maxWidth = bounds.width() - dp(ImeDimensions.KEY_LABEL_HORIZONTAL_INSET_DP)
        val primarySize = shrinkTextSizeToFit(
            label = label,
            initialSize = sp(ImeDimensions.KEY_LABEL_CHARACTER_TEXT_SIZE_SP)
                .coerceAtMost(bounds.height() * PHONE_PAD_PRIMARY_MAX_HEIGHT_FRACTION),
            maxWidth = maxWidth,
        )
        textPaint.textSize = primarySize
        val primaryMetrics = textPaint.fontMetrics
        val primaryHeight = primaryMetrics.descent - primaryMetrics.ascent

        val secondarySize = shrinkTextSizeToFit(
            label = subLabel,
            initialSize = sp(PHONE_PAD_SUB_LABEL_TEXT_SIZE_SP)
                .coerceAtMost(bounds.height() * PHONE_PAD_SUB_LABEL_MAX_HEIGHT_FRACTION),
            maxWidth = maxWidth,
        )
        textPaint.textSize = secondarySize
        val secondaryMetrics = textPaint.fontMetrics
        val secondaryHeight = secondaryMetrics.descent - secondaryMetrics.ascent
        val gap = dp(PHONE_PAD_LABEL_GAP_DP)
        val top = bounds.centerY() - (primaryHeight + gap + secondaryHeight) / 2f

        textPaint.color = color
        textPaint.alpha = alpha
        textPaint.textSize = primarySize
        canvas.drawText(label, bounds.centerX(), top - primaryMetrics.ascent, textPaint)

        textPaint.textSize = secondarySize
        textPaint.alpha = (alpha * PHONE_PAD_SUB_LABEL_ALPHA_FRACTION).roundToInt().coerceIn(0, alpha)
        canvas.drawText(subLabel, bounds.centerX(), top + primaryHeight + gap - secondaryMetrics.ascent, textPaint)
    }

    private fun drawEqualStackedLabel(
        canvas: Canvas,
        label: String,
        subLabel: String,
        bounds: RectF,
        color: Int,
        alpha: Int,
    ) {
        val maxWidth = bounds.width() - dp(ImeDimensions.KEY_LABEL_HORIZONTAL_INSET_DP)
        val initialSize = sp(NUMERIC_TOGGLE_STACKED_TEXT_SIZE_SP)
            .coerceAtMost(bounds.height() * NUMERIC_TOGGLE_STACKED_MAX_HEIGHT_FRACTION)
        val textSize = min(
            shrinkTextSizeToFit(label, initialSize, maxWidth),
            shrinkTextSizeToFit(subLabel, initialSize, maxWidth),
        )
        textPaint.textSize = textSize
        val metrics = textPaint.fontMetrics
        val lineHeight = metrics.descent - metrics.ascent
        val gap = dp(NUMERIC_TOGGLE_STACKED_GAP_DP)
        val top = bounds.centerY() - (lineHeight * 2f + gap) / 2f

        textPaint.color = color
        textPaint.alpha = alpha
        canvas.drawText(label, bounds.centerX(), top - metrics.ascent, textPaint)
        canvas.drawText(subLabel, bounds.centerX(), top + lineHeight + gap - metrics.ascent, textPaint)
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
        val textSize = fitTextSize(label, bounds, KeyRole.Function)
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
            KeyIcon.Space -> drawSpaceIcon(canvas)
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

    private fun drawSpaceIcon(canvas: Canvas) {
        val path = Path().apply {
            moveTo(6f, 10f)
            lineTo(6f, 15f)
            lineTo(18f, 15f)
            lineTo(18f, 10f)
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

    private fun fitTextSize(label: String, bounds: RectF, role: KeyRole, rowStyle: RowStyle = RowStyle.Standard): Float {
        val base = when {
            role == KeyRole.Character && rowStyle == RowStyle.Numbers -> sp(ImeDimensions.KEY_LABEL_NUMBER_TEXT_SIZE_SP)
            role == KeyRole.Character && rowStyle == RowStyle.Toolbar -> sp(ImeDimensions.KEY_LABEL_TOOLBAR_SYMBOL_TEXT_SIZE_SP)
            role == KeyRole.Character -> sp(ImeDimensions.KEY_LABEL_CHARACTER_TEXT_SIZE_SP)
            role == KeyRole.Function -> sp(ImeDimensions.KEY_LABEL_FUNCTION_TEXT_SIZE_SP)
            else -> sp(ImeDimensions.KEY_LABEL_ACTION_TEXT_SIZE_SP)
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

    private fun handleKey(key: KeySpec, sample: TypingLearner.Sample? = null, shiftHeld: Boolean = false) {
        when (val intent = key.intent) {
            KeyIntent.Shift -> {
                // Tap toggles one-shot Shift; a quick double tap locks caps (Gboard / iOS convention).
                // The old Off → OneShot → Locked cycle trapped users in caps lock when cancelling Shift.
                val now = SystemClock.uptimeMillis()
                val doubleTap = now - lastShiftTapAt <= ViewConfiguration.getDoubleTapTimeout()
                shiftState = when {
                    shiftState == ShiftState.Locked -> ShiftState.Off
                    doubleTap && shiftState == ShiftState.Off && !autoShiftActive -> ShiftState.Locked
                    doubleTap && shiftState == ShiftState.OneShot && !autoShiftActive -> ShiftState.Locked
                    shiftState == ShiftState.OneShot && autoShiftActive && doubleTap -> ShiftState.Locked
                    shiftState == ShiftState.OneShot -> ShiftState.Off
                    else -> ShiftState.OneShot
                }
                autoShiftActive = false
                lastShiftTapAt = if (shiftState == ShiftState.Locked) 0L else now
            }
            KeyIntent.SwitchMode -> {
                layoutMode = when (layoutMode) {
                    LayoutMode.Letters -> lastNumericLayoutMode
                    LayoutMode.Symbols,
                    LayoutMode.MoreSymbols,
                    LayoutMode.PhonePad -> LayoutMode.Letters
                }
                shiftState = ShiftState.Off
                requestLayout()
            }
            KeyIntent.ToggleMoreSymbols -> {
                layoutMode = if (layoutMode == LayoutMode.MoreSymbols) LayoutMode.Symbols else LayoutMode.MoreSymbols
                lastNumericLayoutMode = LayoutMode.Symbols
                shiftState = ShiftState.Off
                requestLayout()
            }
            KeyIntent.ToggleNumericLayout -> {
                layoutMode = if (layoutMode == LayoutMode.PhonePad) LayoutMode.Symbols else LayoutMode.PhonePad
                lastNumericLayoutMode = layoutMode
                shiftState = ShiftState.Off
                requestLayout()
            }
            KeyIntent.ToggleAlt -> alt = !alt
            KeyIntent.ToggleCtrl -> ctrl = !ctrl
            KeyIntent.OpenEmojiPanel -> callback?.onEmojiPanelRequested()
            KeyIntent.AgentRewrite -> callback?.onAgentRewriteRequested()
            KeyIntent.SaveSnippet -> callback?.onSaveSnippetRequested()
            is KeyIntent.AcceptComposerSuggestion -> callback?.onComposerSuggestionSelected(intent.text)
            is KeyIntent.AcceptTransliteration -> callback?.onTransliterationSelected(intent.text)
            KeyIntent.ToggleHindi -> callback?.onHindiModeToggled()
            KeyIntent.None -> Unit
            is KeyIntent.Dispatch -> {
                notifyLearner(intent.action, sample)
                if (shiftState == ShiftState.OneShot && key.consumesOneShotShift) {
                    shiftState = ShiftState.Off
                    autoShiftActive = false
                }
                callback?.onKeyboardAction(intent.action, ModifierState(ctrl = ctrl, alt = alt, shift = shiftHeld))
                if (ctrl || alt) {
                    ctrl = false
                    alt = false
                }
            }
        }
        refreshHitTargets()
    }

    private fun notifyLearner(action: KeyboardAction, sample: TypingLearner.Sample?) {
        val learner = typingEngine?.learner ?: return
        if (ctrl || alt) {
            learner.onContextChanged(null)
            return
        }
        when (action) {
            is KeyboardAction.Text -> if (action.value.length == 1) {
                learner.onCommitted(action.value[0], sample)
            } else {
                action.value.forEach { learner.onCommitted(it, null) }
            }
            KeyboardAction.Backspace -> learner.onBackspace()
            KeyboardAction.Enter -> learner.onCommitted('\n', null)
            is KeyboardAction.KeyCode,
            is KeyboardAction.Chord,
            is KeyboardAction.Sequence -> learner.onContextChanged(null)
        }
    }

    private fun resolveDownKey(hit: KeyHit?, x: Float, y: Float, touch: ActiveTouch): KeySpec? {
        val key = hit?.key?.takeIf { it.enabled } ?: return null
        val engine = typingEngine ?: return key
        val geometricLetter = letterIndexOf(key)
        if (!engine.adaptiveEnabled || geometricLetter < 0 || !letterGeometryValid || layoutMode != LayoutMode.Letters) {
            return key
        }
        var previous2 = engine.learner.previous2()
        var previous1 = engine.learner.previous1()
        // Rollover typing: letters still held by other fingers precede this one.
        for (pointerId in pointerQueue) {
            val pendingChar = (activeTouches.get(pointerId)?.currentKey?.intent as? KeyIntent.Dispatch)
                ?.let { (it.action as? KeyboardAction.Text)?.value?.singleOrNull() } ?: continue
            previous2 = previous1
            previous1 = CharLanguageModel.normalize(pendingChar)
        }
        val density = resources.displayMetrics.density
        val xDp = x / density
        val yDp = y / density
        val letter = engine.decoder.decode(
            xDp = xDp,
            yDp = yDp,
            geometricLetter = geometricLetter,
            centersXDp = letterCentersXDp,
            centersYDp = letterCentersYDp,
            pitchXDp = letterPitchXDp,
            pitchYDp = letterPitchYDp,
            previous2 = previous2,
            previous1 = previous1,
            useLanguageModel = engine.personalizationAllowed,
        )
        touch.sample = TypingLearner.Sample(
            letter = letter,
            xDp = xDp,
            yDp = yDp,
            centersXDp = letterCentersXDp,
            centersYDp = letterCentersYDp,
            pitchXDp = letterPitchXDp,
            pitchYDp = letterPitchYDp,
        )
        return letterHits[letter]?.key ?: key
    }

    private fun ActiveTouch.learningSample(key: KeySpec): TypingLearner.Sample? {
        val captured = sample ?: return null
        return captured.takeIf { !slid && it.letter == letterIndexOf(key) }
    }

    private fun letterIndexOf(key: KeySpec): Int {
        val id = key.id
        if (id.length != 6 || !id.startsWith("char_")) return -1
        val char = id[5]
        return if (char in 'a'..'z') char - 'a' else -1
    }

    private fun slideHysteresisPx(): Float {
        val pitchPx = letterPitchXDp * resources.displayMetrics.density
        return max(touchSlopPx, pitchPx * SLIDE_HYSTERESIS_PITCH_FRACTION)
    }

    private fun updateLetterGeometry(hits: List<KeyHit>) {
        val nextHits = arrayOfNulls<KeyHit>(26)
        val centersX = FloatArray(26)
        val centersY = FloatArray(26)
        val density = resources.displayMetrics.density
        var found = 0
        hits.forEach { hit ->
            val letter = letterIndexOf(hit.key)
            if (letter >= 0 && hit.key.enabled) {
                nextHits[letter] = hit
                centersX[letter] = hit.visualBounds.centerX() / density
                centersY[letter] = hit.visualBounds.centerY() / density
                found++
            }
        }
        letterHits = nextHits
        letterCentersXDp = centersX
        letterCentersYDp = centersY
        letterGeometryValid = found == 26
        if (letterGeometryValid) {
            letterPitchXDp = centersX['w' - 'a'] - centersX['q' - 'a']
            letterPitchYDp = centersY['a' - 'a'] - centersY['q' - 'a']
            letterGeometryValid = letterPitchXDp > 0f && letterPitchYDp > 0f
        }
    }

    private fun refreshHitTargets() {
        if (width > 0 && height > 0) {
            hitTargets = layoutKeys(width.toFloat(), height.toFloat())
            updateLetterGeometry(hitTargets)
        }
    }

    private fun layoutRows(): List<RowSpec> = listOf(suggestionStripRow()) + rows()

    private fun layoutKeys(totalWidth: Float, totalHeight: Float): List<KeyHit> {
        val rows = layoutRows()
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
                visualKeys += VisualKey(key, bounds, row.style)
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
                    rowStyle = visualKey.rowStyle,
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
                numberRow(),
                RowSpec(chars("qwertyuiop"), ImeLayout.STANDARD_ROW_WEIGHT),
                RowSpec(chars("asdfghjkl"), ImeLayout.STANDARD_ROW_WEIGHT, layoutKeyCount = 10),
                letterBottomRow(),
                bottomRow(),
            )
            LayoutMode.Symbols -> listOf(
                numberRow(),
                RowSpec(symbols("@#\$%&-+()/", rowPrefix = "symbol_top"), ImeLayout.STANDARD_ROW_WEIGHT),
                RowSpec(symbols("*\":;!?\\|=~", rowPrefix = "symbol_middle"), ImeLayout.STANDARD_ROW_WEIGHT),
                symbolBottomRow(),
                bottomRow(),
            )
            LayoutMode.MoreSymbols -> listOf(
                numberRow(),
                RowSpec(symbols("[]{}#%^*+=", rowPrefix = "more_symbol_top"), ImeLayout.STANDARD_ROW_WEIGHT),
                RowSpec(symbols("_\\|~<>€£¥•", rowPrefix = "more_symbol_middle"), ImeLayout.STANDARD_ROW_WEIGHT),
                moreSymbolBottomRow(),
                bottomRow(),
            )
            LayoutMode.PhonePad -> phonePadRows()
        }
        return buttonRows + mainRows
    }

    /**
     * Always present, so the keyboard never changes height as suggestions come and go. Slots keep
     * fixed positions: an empty slot is an invisible, untouchable placeholder.
     */
    private fun suggestionStripRow(): RowSpec {
        val keys = (0 until SUGGESTION_STRIP_SLOTS).map { index ->
            when (val item = suggestionStrip.getOrNull(index)) {
                null -> KeySpec("strip_$index", "", KeyIntent.None, role = KeyRole.Function, enabled = false)
                else -> KeySpec(
                    id = "strip_$index",
                    label = composerSuggestionDisplayLabel(item.text),
                    intent = when (item.kind) {
                        SuggestionStripItem.Kind.Transliteration -> KeyIntent.AcceptTransliteration(item.text)
                        SuggestionStripItem.Kind.Snippet -> KeyIntent.AcceptComposerSuggestion(item.text)
                    },
                    role = KeyRole.Function,
                    active = item.highlighted,
                )
            }
        }
        return RowSpec(keys, heightWeight = ImeLayout.COMPACT_ROW_WEIGHT, style = RowStyle.Suggestion)
    }

    private fun composerSuggestionDisplayLabel(text: String): String {
        val singleLine = text.replace(SUGGESTION_WHITESPACE_REGEX, " ").trim()
        if (singleLine.length <= MAX_SUGGESTION_LABEL_CHARS) return singleLine
        return singleLine.take(MAX_SUGGESTION_LABEL_CHARS - 1) + "…"
    }

    private fun numberRow(): RowSpec {
        return RowSpec(chars("1234567890"), ImeLayout.STANDARD_ROW_WEIGHT, style = RowStyle.Numbers)
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
        return keys.takeIf { it.isNotEmpty() }?.let { RowSpec(it, heightWeight = ImeLayout.COMPACT_ROW_WEIGHT, style = RowStyle.Toolbar) }
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
        return keys.takeIf { it.isNotEmpty() }?.let { RowSpec(it, heightWeight = ImeLayout.COMPACT_ROW_WEIGHT, style = RowStyle.Toolbar) }
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
                role = KeyRole.Function,
                active = shortcutRowState.loading,
                enabled = enabled && shortcutRowState.rewriteEnabled && !shortcutRowState.loading,
                emphasizedWhenDisabled = shortcutRowState.loading,
            )
            "save_text" -> KeySpec(
                id = id,
                label = "Save snippet",
                intent = KeyIntent.SaveSnippet,
                weight = weight,
                role = KeyRole.Function,
                enabled = enabled,
            )
            "hindi" -> KeySpec(
                id = id,
                label = HINDI_KEY_LABEL,
                intent = KeyIntent.ToggleHindi,
                weight = weight,
                role = KeyRole.Function,
                active = shortcutRowState.hindiMode,
                enabled = enabled && shortcutRowState.hindiAvailable,
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
            else -> KeyboardSettings.symbolForShortcutRowKey(keyId)?.let { textUpperRowKey(id, it, weight, enabled) }
                ?: macroKeySpec(id, keyId, weight, enabled)
        }
    }

    private fun macroKeySpec(id: String, keyId: String, weight: Float, enabled: Boolean): KeySpec? {
        val macro = shortcutMacrosByKeyId[keyId] ?: return null
        val steps = macro.steps.mapNotNull { step ->
            when (step) {
                is MacroStep.Key -> ShortcutMacros.key(step.keyId)?.let {
                    KeyboardAction.Chord(it.keyCode, ctrl = step.ctrl, alt = step.alt, shift = step.shift)
                }
                is MacroStep.Text -> KeyboardAction.Text(step.text)
            }
        }
        return KeySpec(
            id,
            macro.displayLabel,
            KeyIntent.Dispatch(KeyboardAction.Sequence(steps)),
            weight,
            KeyRole.Function,
            enabled = enabled,
        )
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
        // 7 letters + two 1.5-unit side keys = the same 10-unit grid as the top row, so z..m keep the
        // exact width (and touch statistics) of every other letter key.
        return RowSpec(keys, ImeLayout.STANDARD_ROW_WEIGHT, layoutKeyCount = 10)
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
                KeySpec("apostrophe", "'", KeyIntent.Dispatch(KeyboardAction.Text("'")), role = KeyRole.Character),
                KeySpec("backtick", "`", KeyIntent.Dispatch(KeyboardAction.Text("`")), role = KeyRole.Character),
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
                KeySpec("apostrophe", "'", KeyIntent.Dispatch(KeyboardAction.Text("'")), role = KeyRole.Character),
                KeySpec("backtick", "`", KeyIntent.Dispatch(KeyboardAction.Text("`")), role = KeyRole.Character),
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

    private fun phonePadRows(): List<RowSpec> {
        return listOf(
            RowSpec(
                listOf(
                    phonePadOperatorKey(idSuffix = "plus", label = "+"),
                    phonePadTextKey(idSuffix = "1", label = "1"),
                    phonePadTextKey(idSuffix = "2", label = "2"),
                    phonePadTextKey(idSuffix = "3", label = "3"),
                    phonePadOperatorKey(idSuffix = "percent", label = "%"),
                ),
                ImeLayout.STANDARD_ROW_WEIGHT,
            ),
            RowSpec(
                listOf(
                    phonePadOperatorKey(idSuffix = "minus", label = "-"),
                    phonePadTextKey(idSuffix = "4", label = "4"),
                    phonePadTextKey(idSuffix = "5", label = "5"),
                    phonePadTextKey(idSuffix = "6", label = "6"),
                    KeySpec(
                        "phone_space",
                        "space",
                        KeyIntent.Dispatch(KeyboardAction.Text(" ")),
                        role = KeyRole.Function,
                        icon = KeyIcon.Space,
                    ),
                ),
                ImeLayout.STANDARD_ROW_WEIGHT,
            ),
            RowSpec(
                listOf(
                    phonePadOperatorKey(idSuffix = "star", label = "*", subLabel = "/"),
                    phonePadTextKey(idSuffix = "7", label = "7"),
                    phonePadTextKey(idSuffix = "8", label = "8"),
                    phonePadTextKey(idSuffix = "9", label = "9"),
                    KeySpec(
                        "phone_backspace",
                        "Del",
                        KeyIntent.Dispatch(KeyboardAction.Backspace),
                        role = KeyRole.Function,
                        icon = KeyIcon.Delete,
                    ),
                ),
                ImeLayout.STANDARD_ROW_WEIGHT,
            ),
            numericBottomRow(toggleLabel = "!?#", phonePad = true),
        )
    }

    private fun phonePadTextKey(
        idSuffix: String,
        label: String,
        subLabel: String? = null,
    ): KeySpec {
        return KeySpec(
            id = "phone_$idSuffix",
            label = label,
            intent = KeyIntent.Dispatch(KeyboardAction.Text(label)),
            role = KeyRole.Character,
            subLabel = subLabel,
        )
    }

    private fun phonePadOperatorKey(
        idSuffix: String,
        label: String,
        subLabel: String? = null,
    ): KeySpec {
        return KeySpec(
            id = "phone_$idSuffix",
            label = label,
            intent = KeyIntent.Dispatch(KeyboardAction.Text(label)),
            role = KeyRole.Function,
            subLabel = subLabel,
        )
    }

    private fun numericBottomRow(toggleLabel: String, phonePad: Boolean): RowSpec {
        val keys = mutableListOf<KeySpec>()
        keys += KeySpec(
            "switch",
            "ABC",
            KeyIntent.SwitchMode,
            ImeDimensions.MODE_SWITCH_KEY_WEIGHT,
            KeyRole.Function,
        )
        keys += KeySpec(
            "comma",
            ",",
            KeyIntent.Dispatch(KeyboardAction.Text(",")),
            0.82f,
            KeyRole.Character,
        )
        keys += KeySpec(
            "numeric_layout_toggle",
            if (toggleLabel == "1234") "12" else toggleLabel,
            KeyIntent.ToggleNumericLayout,
            1.16f,
            KeyRole.Function,
            subLabel = if (toggleLabel == "1234") "34" else null,
            subLabelStyle = SubLabelStyle.Equal,
        )
        if (phonePad) {
            keys += KeySpec(
                "phone_0",
                "0",
                KeyIntent.Dispatch(KeyboardAction.Text("0")),
                1.9f,
                KeyRole.Character,
            )
            keys += KeySpec(
                "phone_equals",
                "=",
                KeyIntent.Dispatch(KeyboardAction.Text("=")),
                ImeDimensions.DEFAULT_KEY_WEIGHT,
                KeyRole.Character,
            )
        } else {
            keys += KeySpec(
                "space",
                "English",
                KeyIntent.Dispatch(KeyboardAction.Text(" ")),
                4.7f,
                KeyRole.Function,
                longPressHint = "snippets",
            )
        }
        keys += KeySpec(
            "period",
            ".",
            KeyIntent.Dispatch(KeyboardAction.Text(".")),
            0.82f,
            KeyRole.Character,
        )
        keys += KeySpec(
            "enter",
            enterLabel,
            KeyIntent.Dispatch(KeyboardAction.Enter),
            ImeDimensions.ENTER_KEY_WEIGHT,
            roleForEnterLabel(enterLabel),
            icon = iconForEnterLabel(enterLabel),
        )
        return RowSpec(
            keys,
            heightWeight = ImeLayout.BOTTOM_ROW_WEIGHT,
        )
    }

    private fun bottomRow(): RowSpec {
        val keys = mutableListOf(
            KeySpec(
                "switch",
                if (layoutMode == LayoutMode.Letters) "123" else "ABC",
                KeyIntent.SwitchMode,
                ImeDimensions.MODE_SWITCH_KEY_WEIGHT,
                KeyRole.Function,
            ),
            emojiKeySpec(),
        )
        if (layoutMode != LayoutMode.Letters) {
            keys += KeySpec(
                "space",
                spaceLabel(),
                KeyIntent.Dispatch(KeyboardAction.Text(" ")),
                ImeDimensions.SPACE_KEY_WEIGHT,
                KeyRole.Function,
                longPressHint = "snippets",
            )
            keys += KeySpec(
                "numeric_layout_toggle",
                "12",
                KeyIntent.ToggleNumericLayout,
                1.18f,
                KeyRole.Function,
                subLabel = "34",
                subLabelStyle = SubLabelStyle.Equal,
            )
        } else {
            keys += KeySpec(
                "space",
                spaceLabel(),
                KeyIntent.Dispatch(KeyboardAction.Text(" ")),
                ImeDimensions.SPACE_KEY_WEIGHT,
                KeyRole.Function,
                longPressHint = "snippets",
            )
        }
        keys += KeySpec(
            "enter",
            enterLabel,
            KeyIntent.Dispatch(KeyboardAction.Enter),
            ImeDimensions.ENTER_KEY_WEIGHT,
            roleForEnterLabel(enterLabel),
            icon = iconForEnterLabel(enterLabel),
        )
        return RowSpec(
            keys,
            heightWeight = ImeLayout.BOTTOM_ROW_WEIGHT,
        )
    }

    private fun spaceLabel(): String =
        if (shortcutRowState.hindiMode && shortcutRowState.hindiAvailable) HINDI_SPACE_LABEL else "space"

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

    private fun shrinkTextSizeToFit(label: String, initialSize: Float, maxWidth: Float): Float {
        var size = initialSize
        val min = sp(ImeDimensions.KEY_LABEL_MIN_TEXT_SIZE_SP)

        textPaint.textSize = size
        while (textPaint.measureText(label) > maxWidth && size > min) {
            size -= sp(ImeDimensions.KEY_LABEL_SHRINK_STEP_SP)
            textPaint.textSize = size
        }
        return size
    }

    private fun iconForEnterLabel(label: String): KeyIcon? {
        return when (label) {
            "Enter" -> KeyIcon.Enter
            "Search" -> KeyIcon.Search
            else -> null
        }
    }

    private fun roleForEnterLabel(label: String): KeyRole {
        return if (label == "Search") KeyRole.Function else KeyRole.Action
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    }

    private enum class LayoutMode {
        Letters,
        Symbols,
        MoreSymbols,
        PhonePad,
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

    private enum class SubLabelStyle {
        Small,
        Equal,
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
        Space,
    }

    private enum class ArrowDirection {
        Left,
        Up,
        Down,
        Right,
    }

    private enum class RowStyle {
        Standard,
        Numbers,
        Toolbar,
        Suggestion,
    }

    private data class RowSpec(
        val keys: List<KeySpec>,
        val heightWeight: Float,
        val layoutKeyCount: Int? = null,
        val style: RowStyle = RowStyle.Standard,
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
        val subLabel: String? = null,
        val subLabelStyle: SubLabelStyle = SubLabelStyle.Small,
    )

    private data class KeyHit(
        val key: KeySpec,
        val hitBounds: RectF,
        val visualBounds: RectF,
        val rowStyle: RowStyle,
    )

    private data class VisualKey(
        val key: KeySpec,
        val visualBounds: RectF,
        val rowStyle: RowStyle,
    )

    private class ActiveTouch(
        var currentKey: KeySpec?,
        val downX: Float = 0f,
        val downY: Float = 0f,
        var slid: Boolean = false,
        var sample: TypingLearner.Sample? = null,
        var longPressConsumed: Boolean = false,
        var repeatConsumed: Boolean = false,
        var cursorDragConsumed: Boolean = false,
        var heldAsModifier: Boolean = false,
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
        data object ToggleNumericLayout : KeyIntent()
        data object ToggleAlt : KeyIntent()
        data object ToggleCtrl : KeyIntent()
        data object OpenEmojiPanel : KeyIntent()
        data object AgentRewrite : KeyIntent()
        data object SaveSnippet : KeyIntent()
        data class AcceptComposerSuggestion(val text: String) : KeyIntent()
        data class AcceptTransliteration(val text: String) : KeyIntent()
        data object ToggleHindi : KeyIntent()
        data object None : KeyIntent()
        data class Dispatch(val action: KeyboardAction) : KeyIntent()
    }

    private object PALETTE {
        val background get() = ImeColors.BACKGROUND
        val key get() = ImeColors.KEY
        val keyQuiet get() = ImeColors.KEY_DARK
        val function get() = ImeColors.FUNCTION
        val action get() = ImeColors.ACTION
        val onAction get() = ImeColors.ON_ACTION
        val selected get() = ImeColors.SELECTED
        val onSelected get() = ImeColors.ON_SELECTED
        val preview get() = ImeColors.PREVIEW
        val aiLoadingSurface get() = ImeColors.AI_LOADING_SURFACE
        val text get() = ImeColors.TEXT
        val mutedText get() = ImeColors.MUTED_TEXT
    }

    data class ShortcutRowState(
        val secondRowVisible: Boolean,
        val loading: Boolean = false,
        val rewriteEnabled: Boolean = false,
        /** Hindi mode is switched on (the toggle key shows as active). */
        val hindiMode: Boolean = false,
        /** The current field accepts Hindi typing (not a password, URL, email or terminal field). */
        val hindiAvailable: Boolean = false,
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
        const val PHONE_PAD_PRIMARY_MAX_HEIGHT_FRACTION = 0.44f
        const val PHONE_PAD_SUB_LABEL_TEXT_SIZE_SP = 8f
        const val PHONE_PAD_SUB_LABEL_MAX_HEIGHT_FRACTION = 0.18f
        const val PHONE_PAD_LABEL_GAP_DP = 1f
        const val PHONE_PAD_SUB_LABEL_ALPHA_FRACTION = 0.78f
        const val MAX_SUGGESTION_LABEL_CHARS = 48
        const val SUGGESTION_STRIP_SLOTS = 3
        const val HINDI_KEY_LABEL = "अ"
        const val HINDI_SPACE_LABEL = "हिन्दी"
        const val SLIDE_HYSTERESIS_PITCH_FRACTION = 0.55f
        const val PRESSED_LAYER_OPACITY = 0.2f
        const val PREVIEW_WIDTH_FACTOR = 1.28f
        const val PREVIEW_HEIGHT_FACTOR = 1.18f
        const val PREVIEW_MIN_WIDTH_DP = 44f
        const val PREVIEW_GAP_DP = 6f
        const val PREVIEW_TEXT_SIZE_SP = 30f
        val PREVIEW_SHADOW_COLOR = Color.argb(70, 0, 0, 0)
        const val NUMERIC_TOGGLE_STACKED_TEXT_SIZE_SP = 12f
        const val NUMERIC_TOGGLE_STACKED_MAX_HEIGHT_FRACTION = 0.28f
        const val NUMERIC_TOGGLE_STACKED_GAP_DP = 1f
        val SUGGESTION_WHITESPACE_REGEX = Regex("\\s+")
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
    val shift: Boolean = false,
)

sealed class KeyboardAction {
    data class Text(val value: String) : KeyboardAction()
    data class KeyCode(val keyCode: Int) : KeyboardAction()
    /**
     * Single key with fixed modifiers. Unlike [KeyCode], latched Ctrl/Alt on the keyboard
     * are not applied — useful for one-tap chords such as tmux's prefix (Ctrl+B).
     */
    data class Chord(
        val keyCode: Int,
        val ctrl: Boolean = false,
        val alt: Boolean = false,
        val shift: Boolean = false,
    ) : KeyboardAction()
    /**
     * Ordered user macro: [Chord] and [Text] steps played back in order, e.g. Ctrl+B then n.
     */
    data class Sequence(val steps: List<KeyboardAction>) : KeyboardAction()
    data object Backspace : KeyboardAction()
    data object Enter : KeyboardAction()
}

/** One suggestion-strip slot: a Devanagari candidate for the word being typed, or a snippet completion. */
data class SuggestionStripItem(
    val text: String,
    val kind: Kind,
    /** What space will commit; drawn as the selected pill. */
    val highlighted: Boolean = false,
) {
    enum class Kind { Transliteration, Snippet }
}
