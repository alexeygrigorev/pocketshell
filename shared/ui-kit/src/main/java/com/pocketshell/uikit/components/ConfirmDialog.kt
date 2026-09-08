package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/** Shared Quiet confirmation sheet used by destructive and affirmative flows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    dismissLabel: String = "Cancel",
    confirmTestTag: String? = null,
    dismissTestTag: String? = null,
    titleTestTag: String? = null,
    messageTestTag: String? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PocketShellColors.Surface,
        shape = PocketShellShapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PocketShellSpacing.xl)
                    .padding(bottom = PocketShellSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
            ) {
                SheetHeader(
                    title = title,
                    onClose = onDismiss,
                    titleTestTag = titleTestTag,
                )
                Text(
                    text = message,
                    color = PocketShellColors.TextSecondary,
                    style = PocketShellType.body,
                    modifier = messageTestTag?.let { Modifier.testTag(it) } ?: Modifier,
                )
            }
            HorizontalDivider(color = PocketShellColors.BorderSoft)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PocketShellSpacing.xl, vertical = PocketShellSpacing.md),
                verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
            ) {
                PocketShellButton(
                    text = confirmLabel,
                    onClick = onConfirm,
                    variant = if (destructive) ButtonVariant.Destructive else ButtonVariant.Primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { base -> if (confirmTestTag == null) base else base.testTag(confirmTestTag) },
                )
                PocketShellButton(
                    text = dismissLabel,
                    onClick = onDismiss,
                    variant = ButtonVariant.Text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { base -> if (dismissTestTag == null) base else base.testTag(dismissTestTag) },
                )
            }
        }
    }
}
