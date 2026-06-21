package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellTypography

/**
 * The canonical input / form dialog for PocketShell (#861).
 *
 * The #756 design-consistency audit found ~7 input-bearing `AlertDialog`s
 * (add/edit/rename snippet, the command-macro editor, the recurring-job editor,
 * the forwarding passphrase prompt, the session-rename dialog) that each
 * re-implement the same scaffold by hand: a `Text` title in
 * [PocketShellColors.Text], a [Column] of the caller's
 * `OutlinedTextField`(s)/helper text/toggles, a confirm [PocketShellButton]
 * ([ButtonVariant.Primary]), a Cancel [ButtonVariant.Text] button, and
 * `containerColor = Surface` with the [PocketShellShapes.large] (20dp) dialog
 * radius. [ConfirmDialog] can't cover them because it has no content slot for
 * the form fields. [FormDialog] folds that recipe into one shared surface so
 * every input dialog reads the same.
 *
 * Unlike [ConfirmDialog] (a fixed title + body message), [FormDialog] takes a
 * **content slot** ([content]) where the caller composes its own
 * `OutlinedTextField`(s), helper captions, kind toggles, etc. The content is
 * laid out in a [Column] with the same dense [Arrangement.spacedBy] rhythm the
 * call sites used by hand, so the caller only supplies the fields.
 *
 * Tokens (NO raw hex):
 * - Title is [PocketShellTypography] `titleMedium` (16sp) in
 *   [PocketShellColors.Text] (matching [ConfirmDialog]).
 * - Container is [PocketShellColors.Surface] with the
 *   [PocketShellShapes.large] dialog radius (20dp per `docs/design-system.md`).
 *
 * The action row is the canonical dialog pairing (`Text` Cancel + `Primary`
 * confirm, right-aligned) from the design system, both routed through the
 * shared [PocketShellButton] so colour/weight/shape/disabled treatment can't
 * drift per call site. An optional [extraAction] slot sits to the LEFT of
 * Cancel for the one editor (recurring-job) that pairs a destructive "Remove"
 * with the standard Cancel/Save row.
 *
 * @param title the dialog headline ("Add snippet", "Rename session").
 * @param confirmLabel the confirm button label ("Save", "Open", "Rename").
 * @param onConfirm invoked when the confirm button is tapped.
 * @param onDismiss invoked on Cancel, scrim tap, or system back.
 * @param modifier outer modifier forwarded to the [AlertDialog].
 * @param confirmEnabled when false the confirm button is disabled (form not yet
 *   valid — e.g. a blank required field); defaults to `true`.
 * @param dismissLabel the dismiss button label (defaults to "Cancel").
 * @param confirmTestTag optional `testTag` on the confirm button, so call sites
 *   that previously hand-rolled the dialog keep their existing instrumentation
 *   hooks after migrating onto [FormDialog].
 * @param dismissTestTag optional `testTag` on the dismiss (Cancel) button, same
 *   migration rationale as [confirmTestTag].
 * @param extraAction optional leading action (e.g. a destructive "Remove")
 *   placed to the left of Cancel; `null` for the common case.
 * @param content the form-field slot, composed in a [ColumnScope] with a dense
 *   `spacedBy(10.dp)` vertical rhythm.
 */
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
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(
                text = title,
                color = PocketShellColors.Text,
                style = PocketShellTypography.titleMedium,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        },
        confirmButton = {
            PocketShellButton(
                text = confirmLabel,
                onClick = onConfirm,
                variant = ButtonVariant.Primary,
                enabled = confirmEnabled,
                modifier = if (confirmTestTag != null) Modifier.testTag(confirmTestTag) else Modifier,
            )
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (extraAction != null) {
                    extraAction()
                    Spacer(modifier = Modifier.width(8.dp))
                }
                PocketShellButton(
                    text = dismissLabel,
                    onClick = onDismiss,
                    variant = ButtonVariant.Text,
                    modifier = if (dismissTestTag != null) Modifier.testTag(dismissTestTag) else Modifier,
                )
            }
        },
        containerColor = PocketShellColors.Surface,
        titleContentColor = PocketShellColors.Text,
        textContentColor = PocketShellColors.TextSecondary,
        shape = PocketShellShapes.large,
    )
}
