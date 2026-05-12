package dev.zain.znkeyboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zain.znkeyboard.constants.SettingsUiDimensions

@Composable
internal fun SettingsSectionCard(
    modifier: Modifier = Modifier,
    verticalSpacing: androidx.compose.ui.unit.Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = ZnKeyboardColors.Surface,
        shape = RoundedCornerShape(SettingsUiDimensions.SECTION_CORNER_RADIUS),
        tonalElevation = 0.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing),
            content = content,
        )
    }
}

@Composable
internal fun SettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    iconResId: Int? = null,
    subtitle: String? = null,
    trailingText: String? = null,
    trailingColor: Color = ZnKeyboardColors.Muted,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            iconResId?.let {
                Icon(
                    painter = painterResource(it),
                    contentDescription = null,
                    tint = ZnKeyboardColors.Accent,
                )
                Spacer(Modifier.width(10.dp))
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        color = ZnKeyboardColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        trailingContent?.invoke(this) ?: trailingText?.let {
            Text(
                text = it,
                color = trailingColor,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
internal fun SettingsIconTextButtonContent(
    iconResId: Int,
    text: String,
) {
    Icon(
        painter = painterResource(iconResId),
        contentDescription = null,
        modifier = Modifier.size(18.dp),
    )
    Spacer(Modifier.width(8.dp))
    Text(text)
}

@Composable
internal fun SettingsPickerLauncherButton(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
    ) {
        leadingContent?.invoke(this)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = label,
                color = ZnKeyboardColors.Muted,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = value,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Text(
            text = "Change",
            color = ZnKeyboardColors.Accent,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
internal fun LockableSecretOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    locked: Boolean,
    onLockedChange: (Boolean) -> Unit,
    emptySupportingText: String,
    lockContentDescription: String,
    unlockContentDescription: String,
    modifier: Modifier = Modifier,
) {
    val effectivelyLocked = locked && value.isNotBlank()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = !effectivelyLocked,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(
                onClick = {
                    if (effectivelyLocked) {
                        onLockedChange(false)
                    } else if (value.isNotBlank()) {
                        onLockedChange(true)
                    }
                },
                enabled = value.isNotBlank(),
            ) {
                Icon(
                    painter = painterResource(
                        if (effectivelyLocked) R.drawable.ic_lock_24 else R.drawable.ic_lock_open_24,
                    ),
                    contentDescription = if (effectivelyLocked) unlockContentDescription else lockContentDescription,
                    tint = if (effectivelyLocked) ZnKeyboardColors.Accent else ZnKeyboardColors.Muted,
                )
            }
        },
        supportingText = {
            Text(
                text = if (effectivelyLocked) {
                    "Locked. Tap lock to edit."
                } else if (value.isNotBlank()) {
                    "Tap lock to prevent edits."
                } else {
                    emptySupportingText
                },
                color = ZnKeyboardColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        modifier = modifier.fillMaxWidth(),
    )
}
