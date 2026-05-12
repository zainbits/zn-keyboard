package dev.zain.znkeyboard.ime

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

internal object ImeSearchHeader {
    fun create(
        context: Context,
        backContentDescription: String,
        backgroundColor: Int,
        textColor: Int,
        queryField: ImeSearchField,
        onBack: () -> Unit,
        actionView: View? = null,
        actionWidthDp: Int = ImePanelChrome.SEARCH_BUTTON_WIDTH_DP,
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ImePanelChrome.backButton(
                    context = context,
                    contentDescription = backContentDescription,
                    backgroundColor = backgroundColor,
                    textColor = textColor,
                    onClick = onBack,
                ),
                LinearLayout.LayoutParams(
                    context.dp(ImePanelChrome.BACK_BUTTON_WIDTH_DP),
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ).withDpMargins(context, end = 4),
            )

            addView(
                queryField,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    .withDpMargins(context, horizontal = 2),
            )

            actionView?.let { view ->
                addView(
                    view,
                    LinearLayout.LayoutParams(context.dp(actionWidthDp), LinearLayout.LayoutParams.MATCH_PARENT)
                        .withDpMargins(context, start = 4),
                )
            }
        }
    }
}
