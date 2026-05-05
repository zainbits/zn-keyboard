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
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

class AgentHistoryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    interface Callback {
        fun onAgentHistoryClosed()
    }

    var callback: Callback? = null

    private val adapter = HistoryAdapter(context)
    private val emptyText = TextView(context).apply {
        text = "No history yet"
        gravity = Gravity.CENTER
        setTextColor(PALETTE.mutedText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        visibility = GONE
    }
    private val historyList = ListView(context).apply {
        adapter = this@AgentHistoryView.adapter
        divider = ColorDrawable(PALETTE.divider)
        dividerHeight = dp(1)
        cacheColorHint = Color.TRANSPARENT
        setBackgroundColor(PALETTE.background)
        clipToPadding = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
    }
    private var heightScale = 1f

    private val bottomSystemControlGapPx by lazy(LazyThreadSafetyMode.NONE) {
        ImeLayout.bottomSystemControlGapPx(context)
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(PALETTE.background)
        setPadding(dp(8), dp(8), dp(8), dp(ImeLayout.BASE_BOTTOM_PADDING_DP) + bottomSystemControlGapPx.roundToInt())
        isClickable = true
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(context).apply {
                    text = "History"
                    setTextColor(PALETTE.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER_VERTICAL
                    includeFontPadding = false
                },
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
            )
            addView(
                Button(context).apply {
                    text = "Keyboard"
                    isAllCaps = false
                    minHeight = 0
                    minWidth = 0
                    isFocusable = false
                    setOnClickListener { callback?.onAgentHistoryClosed() }
                },
                LayoutParams(dp(104), LayoutParams.MATCH_PARENT),
            )
        }

        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(42)))
        addView(
            historyList,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).withMargins(top = 6),
        )
        addView(
            emptyText,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).withMargins(top = 6),
        )
    }

    fun setHeightScale(scale: Float) {
        if (heightScale != scale) {
            heightScale = scale
            requestLayout()
        }
    }

    fun submitHistory(entries: List<AgentRewriteHistoryStore.Entry>) {
        adapter.submitList(entries.take(AgentRewriteHistoryStore.MAX_HISTORY))
        val empty = entries.isEmpty()
        emptyText.visibility = if (empty) VISIBLE else GONE
        historyList.visibility = if (empty) GONE else VISIBLE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (ImeLayout.BASE_HEIGHT_DP * heightScale * resources.displayMetrics.density +
            ImeLayout.compactAgentRowHeightPx(context, heightScale) +
            bottomSystemControlGapPx).roundToInt()
        val exactHeightSpec = MeasureSpec.makeMeasureSpec(resolveSize(desiredHeight, heightMeasureSpec), MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, exactHeightSpec)
    }

    private fun LayoutParams.withMargins(
        start: Int = 0,
        top: Int = 0,
        end: Int = 0,
        bottom: Int = 0,
    ): LayoutParams {
        setMargins(dp(start), dp(top), dp(end), dp(bottom))
        return this
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private class HistoryAdapter(
        private val context: Context,
    ) : BaseAdapter() {
        private val timestampFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        private var entries: List<AgentRewriteHistoryStore.Entry> = emptyList()

        fun submitList(newEntries: List<AgentRewriteHistoryStore.Entry>) {
            entries = newEntries
            notifyDataSetChanged()
        }

        override fun getCount(): Int = entries.size

        override fun getItem(position: Int): AgentRewriteHistoryStore.Entry? = entries.getOrNull(position)

        override fun getItemId(position: Int): Long = entries.getOrNull(position)?.timestampMillis ?: position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val holder: ViewHolder
            val view = if (convertView is LinearLayout && convertView.tag is ViewHolder) {
                holder = convertView.tag as ViewHolder
                convertView
            } else {
                val timestamp = TextView(context).apply {
                    setTextColor(PALETTE.mutedText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    includeFontPadding = false
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                }
                val body = TextView(context).apply {
                    setTextColor(PALETTE.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setLineSpacing(dp(2).toFloat(), 1f)
                    maxLines = 6
                    ellipsize = TextUtils.TruncateAt.END
                }
                holder = ViewHolder(timestamp, body)
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(dp(10), dp(9), dp(10), dp(9))
                    background = itemBackground()
                    tag = holder
                    addView(timestamp, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                    addView(
                        body,
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                            .apply { topMargin = dp(5) },
                    )
                }
            }

            val entry = entries[position]
            holder.timestamp.text = if (entry.timestampMillis > 0L) {
                timestampFormat.format(Date(entry.timestampMillis))
            } else {
                ""
            }
            holder.body.text = entry.originalText
            return view
        }

        private fun itemBackground(): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(PALETTE.background)
            }
        }

        private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

        private data class ViewHolder(
            val timestamp: TextView,
            val body: TextView,
        )
    }

    private object PALETTE {
        val background = Color.rgb(34, 34, 34)
        val divider = Color.rgb(58, 58, 58)
        const val text = Color.WHITE
        val mutedText = Color.rgb(170, 170, 170)
    }

}
