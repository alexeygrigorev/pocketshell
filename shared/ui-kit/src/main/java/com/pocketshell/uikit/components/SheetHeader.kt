package com.pocketshell.uikit.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Shared bottom-sheet header (#756).
 *
 * A bottom sheet's top row should read the same whether the sheet is a picker,
 * an action tray, or a short success surface: title on the title rung, optional
 * muted subtitle, optional trailing action(s), and an optional close affordance.
 * Callers keep owning sheet-specific content padding and height constraints; this
 * component owns the title/subtitle/action grammar only.
 */
@Composable
fun SheetHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleMaxLines: Int = Int.MAX_VALUE,
    subtitleMaxLines: Int = Int.MAX_VALUE,
    subtitleStyle: TextStyle = PocketShellType.bodyDense,
    titleTestTag: String? = null,
    subtitleTestTag: String? = null,
    onClose: (() -> Unit)? = null,
    closeContentDescription: String = "Close",
    closeTestTag: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = PocketShellColors.Text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = titleTestTag?.let { Modifier.testTag(it) } ?: Modifier,
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.size(PocketShellSpacing.xs))
                Text(
                    text = subtitle,
                    color = PocketShellColors.TextSecondary,
                    style = subtitleStyle,
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = subtitleTestTag?.let { Modifier.testTag(it) } ?: Modifier,
                )
            }
        }

        if (trailing != null || onClose != null) {
            Spacer(modifier = Modifier.width(PocketShellSpacing.sm))
            Row(
                horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                trailing?.invoke(this)
                if (onClose != null) {
                    SheetCloseButton(
                        onClick = onClose,
                        contentDescription = closeContentDescription,
                        testTag = closeTestTag,
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetCloseButton(
    onClick: () -> Unit,
    contentDescription: String,
    testTag: String?,
) {
    Box(
        modifier = (testTag?.let { Modifier.testTag(it) } ?: Modifier)
            .size(PocketShellDensity.tapTargetMin)
            .semantics { this.contentDescription = contentDescription }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "×",
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = PocketShellSpacing.xs),
        )
    }
}
