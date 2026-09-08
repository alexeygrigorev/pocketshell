package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing

/** Shared Quiet form sheet with an independently scrolling body. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    dismissLabel: String = "Cancel",
    confirmTestTag: String? = null,
    dismissTestTag: String? = null,
    extraAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
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
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PocketShellSpacing.xl)
                    .padding(bottom = PocketShellSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
                content = {
                    SheetHeader(title = title, onClose = onDismiss)
                    content()
                },
            )
            HorizontalDivider(color = PocketShellColors.BorderSoft)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PocketShellSpacing.xl, vertical = PocketShellSpacing.md),
                verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                extraAction?.invoke()
                PocketShellButton(
                    text = confirmLabel,
                    onClick = onConfirm,
                    variant = ButtonVariant.Primary,
                    enabled = confirmEnabled,
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
