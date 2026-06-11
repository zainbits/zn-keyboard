package dev.zain.znkeyboard.ime

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.bumptech.glide.Glide
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

    private var allEntries: List<ClipboardHistoryStore.Entry> = emptyList()
    private var selectedSourcePackageName: String? = null

    private val sourcePillRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val sourceFilterScroller = HorizontalScrollView(context).apply {
        visibility = GONE
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        setPadding(0, 0, 0, dp(6))
        addView(
            sourcePillRow,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }
    private val historyAdapter = HistoryAdapter(
        context = context,
        onSelected = { text -> callback?.onClipboardHistoryItemSelected(text) },
        onDeleted = { text -> callback?.onClipboardHistoryItemDeleted(text) },
    )

    init {
        addHeaderView(sourceFilterScroller, null, false)
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
        allEntries = entries.take(ClipboardHistoryStore.MAX_HISTORY)
        updateSourceFilters()
        submitFilteredHistory()
    }

    private fun updateSourceFilters() {
        val options = allEntries
            .mapNotNull { entry ->
                val packageName = entry.sourcePackageName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SourceFilterOption(
                    packageName = packageName,
                    label = entry.sourceAppLabel?.takeIf { it.isNotBlank() } ?: packageName,
                )
            }
            .distinctBy { it.packageName }

        if (selectedSourcePackageName != null && options.none { it.packageName == selectedSourcePackageName }) {
            selectedSourcePackageName = null
        }

        sourcePillRow.removeAllViews()
        sourceFilterScroller.visibility = if (options.isEmpty()) GONE else VISIBLE
        options.forEachIndexed { index, option ->
            val selected = option.packageName == selectedSourcePackageName
            sourcePillRow.addView(
                sourcePill(option = option, selected = selected),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32))
                    .apply { if (index < options.lastIndex) marginEnd = dp(6) },
            )
        }
    }

    private fun submitFilteredHistory() {
        val selectedPackageName = selectedSourcePackageName
        val visibleEntries = if (selectedPackageName == null) {
            allEntries
        } else {
            allEntries.filter { it.sourcePackageName == selectedPackageName }
        }
        historyAdapter.submitList(visibleEntries)
    }

    private fun sourcePill(option: SourceFilterOption, selected: Boolean): TextView {
        return TextView(context).apply {
            text = option.label
            gravity = Gravity.CENTER
            includeFontPadding = false
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setMaxWidth(dp(148))
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(if (selected) PALETTE.text else PALETTE.mutedText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = sourcePillBackground(selected)
            isClickable = true
            isFocusable = false
            contentDescription = if (selected) {
                "Clear ${option.label} clipboard filter"
            } else {
                "Filter clipboard by ${option.label}"
            }
            setOnClickListener {
                selectedSourcePackageName = if (selectedSourcePackageName == option.packageName) {
                    null
                } else {
                    option.packageName
                }
                updateSourceFilters()
                submitFilteredHistory()
            }
        }
    }

    private fun sourcePillBackground(selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(if (selected) PALETTE.selected else PALETTE.function)
            setStroke(dp(1), if (selected) PALETTE.mutedText else PALETTE.cardStroke)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private data class SourceFilterOption(
        val packageName: String,
        val label: String,
    )

    private class HistoryAdapter(
        private val context: Context,
        private val onSelected: (String) -> Unit,
        private val onDeleted: (String) -> Unit,
    ) : BaseAdapter() {
        private val packageManager = context.packageManager
        private val appIconCache = mutableMapOf<String, Drawable?>()
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
            val previewUrl = ClipboardLinkPreviewRepository.extractFirstPreviewUrl(entry.text)
            val timestamp = if (entry.timestampMillis > 0L) {
                timestampFormat.format(Date(entry.timestampMillis))
            } else {
                ""
            }
            row.bind(
                entry = entry,
                timestamp = timestamp,
                sourceIconDrawable = appIconFor(entry.sourcePackageName),
                previewUrl = previewUrl,
                previewState = previewUrl?.let { ClipboardLinkPreviewRepository.stateFor(context, it) }
                    ?: ClipboardLinkPreviewState.Idle,
                onSelected = onSelected,
                onDeleted = onDeleted,
                onPreviewRequested = ::requestPreview,
            )
            return row
        }

        private fun requestPreview(url: String) {
            ClipboardLinkPreviewRepository.request(context, url) {
                notifyDataSetChanged()
            }
        }

        private fun appIconFor(packageName: String?): Drawable? {
            if (packageName.isNullOrBlank()) return null
            if (!appIconCache.containsKey(packageName)) {
                appIconCache[packageName] = runCatching {
                    packageManager
                        .getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                        .loadIcon(packageManager)
                }.getOrNull()
            }
            val cachedIcon = appIconCache[packageName] ?: return null
            return cachedIcon.constantState?.newDrawable(context.resources)?.mutate() ?: cachedIcon.mutate()
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
        private var boundPreviewUrl: String? = null
        private var onSelected: ((String) -> Unit)? = null
        private var onDeleted: ((String) -> Unit)? = null
        private var onPreviewRequested: ((String) -> Unit)? = null

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
        private val previewButton = TextView(context).apply {
            text = "Preview"
            gravity = Gravity.CENTER
            includeFontPadding = false
            setSingleLine(true)
            setTextColor(PALETTE.previewButtonText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), 0, dp(10), 0)
            minHeight = dp(26)
            background = previewButtonBackground()
            isClickable = true
            isFocusable = false
            contentDescription = "Preview clipboard link"
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            }
            setOnClickListener {
                boundPreviewUrl?.let { url -> onPreviewRequested?.invoke(url) }
            }
        }
        private val previewStatus = TextView(context).apply {
            setTextColor(PALETTE.mutedText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            includeFontPadding = false
            visibility = GONE
        }
        private val previewImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = GONE
            background = previewImageBackground()
        }
        private val previewTitle = TextView(context).apply {
            setTextColor(PALETTE.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        private val previewDescription = TextView(context).apply {
            setTextColor(PALETTE.mutedText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(1).toFloat(), 1f)
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        private val previewHost = TextView(context).apply {
            setTextColor(PALETTE.mutedText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            includeFontPadding = false
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        private val previewTextColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(previewTitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(
                previewDescription,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(4) },
            )
            addView(
                previewHost,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(5) },
            )
        }
        private val previewCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = previewCardBackground()
            visibility = GONE
            isClickable = true
            isFocusable = false
            addView(
                previewImage,
                LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(9) },
            )
            addView(
                previewTextColumn,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        private val sourceIcon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            visibility = GONE
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
        private val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(timestamp, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                previewButton,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(26)).apply { marginStart = dp(8) },
            )
        }
        private val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(headerRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(
                body,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(5) },
            )
            addView(
                previewStatus,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(7) },
            )
            addView(
                previewCard,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(8) },
            )
        }
        private val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = itemBackground()
            isClickable = true
            isFocusable = false
            addView(
                sourceIcon,
                LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) },
            )
            addView(
                textColumn,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
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
            sourceIconDrawable: Drawable?,
            previewUrl: String?,
            previewState: ClipboardLinkPreviewState,
            onSelected: (String) -> Unit,
            onDeleted: (String) -> Unit,
            onPreviewRequested: (String) -> Unit,
        ) {
            boundEntry = entry
            boundPreviewUrl = previewUrl
            this.onSelected = onSelected
            this.onDeleted = onDeleted
            this.onPreviewRequested = onPreviewRequested
            this.timestamp.text = timestamp
            body.text = entry.text
            renderPreview(previewUrl, previewState)
            if (sourceIconDrawable == null) {
                sourceIcon.setImageDrawable(null)
                sourceIcon.visibility = GONE
                sourceIcon.contentDescription = null
            } else {
                sourceIcon.setImageDrawable(sourceIconDrawable)
                sourceIcon.visibility = VISIBLE
                sourceIcon.contentDescription = entry.sourceAppLabel?.let { "Copied from $it" }
            }
            close(animated = false)
        }

        private fun renderPreview(previewUrl: String?, previewState: ClipboardLinkPreviewState) {
            if (previewUrl == null) {
                previewButton.visibility = GONE
                previewStatus.visibility = GONE
                previewCard.visibility = GONE
                Glide.with(previewImage).clear(previewImage)
                previewImage.setImageDrawable(null)
                return
            }

            when (previewState) {
                ClipboardLinkPreviewState.Idle -> {
                    previewButton.visibility = VISIBLE
                    previewStatus.visibility = GONE
                    previewCard.visibility = GONE
                    Glide.with(previewImage).clear(previewImage)
                    previewImage.setImageDrawable(null)
                }
                ClipboardLinkPreviewState.Loading -> {
                    previewButton.visibility = GONE
                    previewStatus.text = "Fetching preview..."
                    previewStatus.visibility = VISIBLE
                    previewCard.visibility = GONE
                    Glide.with(previewImage).clear(previewImage)
                    previewImage.setImageDrawable(null)
                }
                is ClipboardLinkPreviewState.Ready -> {
                    previewButton.visibility = GONE
                    previewStatus.visibility = GONE
                    bindPreviewCard(previewState.preview)
                }
            }
        }

        private fun bindPreviewCard(preview: ClipboardLinkPreview) {
            previewTitle.text = preview.title
            val description = preview.description?.takeIf { it.isNotBlank() }
            previewDescription.text = description.orEmpty()
            previewDescription.visibility = if (description == null) GONE else VISIBLE
            previewHost.text = preview.siteName
                ?.takeIf { it.isNotBlank() && it != preview.host }
                ?.let { siteName -> "$siteName - ${preview.host}" }
                ?: preview.host

            val imageUrl = preview.imageUrl?.takeIf { it.isNotBlank() }
            if (imageUrl == null) {
                Glide.with(previewImage).clear(previewImage)
                previewImage.setImageDrawable(null)
                previewImage.visibility = GONE
            } else {
                previewImage.visibility = VISIBLE
                Glide.with(previewImage)
                    .load(imageUrl)
                    .centerCrop()
                    .placeholder(previewImageBackground())
                    .error(previewImageBackground())
                    .into(previewImage)
            }
            previewCard.visibility = VISIBLE
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

        private fun previewButtonBackground(): Drawable {
            return ImePressFeedback.roundedBackground(
                context = context,
                containerColor = PALETTE.function,
                contentColor = PALETTE.previewButtonText,
                radiusDp = 13,
                strokeWidthDp = 1,
                strokeColor = PALETTE.cardStroke,
            )
        }

        private fun previewCardBackground(): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(PALETTE.previewCard)
                setStroke(dp(1), PALETTE.cardStroke)
            }
        }

        private fun previewImageBackground(): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(PALETTE.function)
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
        val function = ImeColors.FUNCTION_DARK
        val selected = ImeColors.SELECTED
        val cardStroke = ImeColors.DIVIDER
        const val text = ImeColors.TEXT
        val mutedText = ImeColors.SECONDARY_TEXT
        val previewButtonText = Color.rgb(198, 207, 220)
        val previewCard = Color.rgb(34, 38, 45)
        val deleteBackground = Color.rgb(58, 36, 36)
        val deleteContent = Color.rgb(224, 168, 168)
    }
}
