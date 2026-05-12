package dev.zain.znkeyboard.ime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import dev.zain.znkeyboard.constants.ImeColors
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

internal class ClipboardHistoryListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ListView(context, attrs) {
    interface Callback {
        fun onClipboardHistoryItemSelected(text: String)
        fun onClipboardHistoryItemDeleted(text: String)
    }

    var callback: Callback? = null

    private val historyAdapter = HistoryAdapter(
        context = context,
        onSelected = { text -> callback?.onClipboardHistoryItemSelected(text) },
        onDeleted = { text -> callback?.onClipboardHistoryItemDeleted(text) },
    )

    init {
        adapter = historyAdapter
        divider = ColorDrawable(Color.TRANSPARENT)
        dividerHeight = dp(6)
        cacheColorHint = Color.TRANSPARENT
        setBackgroundColor(PALETTE.background)
        clipToPadding = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        selector = ColorDrawable(Color.TRANSPARENT)
        setPadding(0, 0, 0, dp(4))
    }

    fun submitHistory(entries: List<ClipboardHistoryStore.Entry>) {
        historyAdapter.submitList(entries.take(ClipboardHistoryStore.MAX_HISTORY))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private class HistoryAdapter(
        private val context: Context,
        private val onSelected: (String) -> Unit,
        private val onDeleted: (String) -> Unit,
    ) : BaseAdapter() {
        private val timestampFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        private var entries: List<ClipboardHistoryStore.Entry> = emptyList()

        fun submitList(newEntries: List<ClipboardHistoryStore.Entry>) {
            entries = newEntries
            notifyDataSetChanged()
        }

        override fun getCount(): Int = entries.size

        override fun getItem(position: Int): ClipboardHistoryStore.Entry? = entries.getOrNull(position)

        override fun getItemId(position: Int): Long = entries.getOrNull(position)?.timestampMillis ?: position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = if (convertView is SwipeRevealHistoryRow) {
                convertView
            } else {
                SwipeRevealHistoryRow(context)
            }

            val entry = entries[position]
            val timestamp = if (entry.timestampMillis > 0L) {
                timestampFormat.format(Date(entry.timestampMillis))
            } else {
                ""
            }
            row.bind(
                entry = entry,
                timestamp = timestamp,
                onSelected = onSelected,
                onDeleted = onDeleted,
            )
            return row
        }
    }

    private class SwipeRevealHistoryRow(context: Context) : FrameLayout(context) {
        private val revealWidthPx = dp(96)
        private val lockThresholdPx = revealWidthPx * 0.45f
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startTranslationX = 0f
        private var dragging = false

        private var boundEntry: ClipboardHistoryStore.Entry? = null
        private var onSelected: ((String) -> Unit)? = null
        private var onDeleted: ((String) -> Unit)? = null

        private val timestamp = TextView(context).apply {
            setTextColor(PALETTE.mutedText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            includeFontPadding = false
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        private val body = TextView(context).apply {
            setTextColor(PALETTE.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(dp(2).toFloat(), 1f)
            maxLines = 6
            ellipsize = TextUtils.TruncateAt.END
        }
        private val deleteAction = TextView(context).apply {
            text = "Delete"
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(PALETTE.deleteContent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = deleteBackground()
            isClickable = true
            isFocusable = false
            setOnClickListener {
                boundEntry?.text?.let { text ->
                    close(animated = false)
                    onDeleted?.invoke(text)
                }
            }
        }
        private val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = itemBackground()
            isClickable = true
            isFocusable = false
            addView(timestamp, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(
                body,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(5) },
            )
        }

        init {
            clipChildren = true
            background = deleteBackground()
            addView(
                deleteAction,
                LayoutParams(revealWidthPx, LayoutParams.MATCH_PARENT, Gravity.END),
            )
            addView(
                content,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
            content.setOnTouchListener(::onContentTouch)
        }

        fun bind(
            entry: ClipboardHistoryStore.Entry,
            timestamp: String,
            onSelected: (String) -> Unit,
            onDeleted: (String) -> Unit,
        ) {
            boundEntry = entry
            this.onSelected = onSelected
            this.onDeleted = onDeleted
            this.timestamp.text = timestamp
            body.text = entry.text
            close(animated = false)
        }

        private fun onContentTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    view.animate().cancel()
                    downX = event.rawX
                    downY = event.rawY
                    startTranslationX = view.translationX
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - downX
                    val deltaY = event.rawY - downY
                    if (!dragging) {
                        if (abs(deltaX) > touchSlop && abs(deltaX) > abs(deltaY)) {
                            dragging = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                        } else if (abs(deltaY) > touchSlop && abs(deltaY) > abs(deltaX)) {
                            return false
                        }
                    }
                    if (dragging) {
                        view.translationX = (startTranslationX + deltaX).coerceIn(-revealWidthPx.toFloat(), 0f)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (dragging) {
                        settle()
                    } else if (view.translationX < 0f) {
                        close(animated = true)
                    } else {
                        boundEntry?.text?.let { text -> onSelected?.invoke(text) }
                    }
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    settle()
                    dragging = false
                    return true
                }
            }
            return false
        }

        private fun settle() {
            if (content.translationX <= -lockThresholdPx) {
                open()
            } else {
                close(animated = true)
            }
        }

        private fun open() {
            content.animate()
                .translationX(-revealWidthPx.toFloat())
                .setDuration(160L)
                .start()
        }

        private fun close(animated: Boolean) {
            if (animated) {
                content.animate()
                    .translationX(0f)
                    .setDuration(160L)
                    .start()
            } else {
                content.animate().cancel()
                content.translationX = 0f
            }
        }

        private fun itemBackground(): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(PALETTE.key)
                setStroke(dp(1), PALETTE.cardStroke)
            }
        }

        private fun deleteBackground(): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(PALETTE.deleteBackground)
            }
        }

        private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
    }

    private object PALETTE {
        val background = ImeColors.BACKGROUND
        val key = ImeColors.KEY
        val cardStroke = ImeColors.DIVIDER
        const val text = ImeColors.TEXT
        val mutedText = ImeColors.SECONDARY_TEXT
        val deleteBackground = Color.rgb(58, 36, 36)
        val deleteContent = Color.rgb(224, 168, 168)
    }
}
