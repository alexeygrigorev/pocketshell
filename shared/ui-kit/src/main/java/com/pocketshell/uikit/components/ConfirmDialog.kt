package com.pocketshell.uikit.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellTypography

/**
 * The canonical confirm / destructive-confirm dialog for PocketShell (#756).
 *
 * The UI-consistency audit (#756) found 31 raw Material `AlertDialog`s, with the
 * confirm/destructive flow (delete host, delete key, reset costs, reveal env)
 * re-implemented per screen — each repeating the same recipe by hand: a `Text`
 * title in [PocketShellColors.Text], a `Text` body in
 * [PocketShellColors.TextSecondary], a confirm [PocketShellButton]
 * ([ButtonVariant.Primary] or [ButtonVariant.Destructive]), a Cancel
 * [ButtonVariant.Text] button, and `containerColor = Surface`. [ConfirmDialog]
 * folds that recipe into one shared surface so every confirm dialog reads the
 * same.
 *
 * Destructive vs affirmative is a single [destructive] flag, not a colour the
 * caller picks: when true the confirm action paints with
 * [ButtonVariant.Destructive] (red TEXT — per `docs/design-system.md`:
 * "destructive confirmation uses red text only on the confirm action"), when
 * false it paints with [ButtonVariant.Primary] (filled accent). The dismiss
 * action is always the muted [ButtonVariant.Text] Cancel.
 *
 * Tokens (NO raw hex):
 * - Title is [PocketShellTypography] `titleMedium` (16sp) in
 *   [PocketShellColors.Text].
 * - Body ([message]) is `bodyMedium` (14sp) in [PocketShellColors.TextSecondary].
 * - Container is [PocketShellColors.Surface] with the
 *   [PocketShellShapes.large] sheet/dialog radius.
 *
 * Both buttons route through the shared [PocketShellButton], so confirm/cancel
 * colour, weight, shape, and disabled treatment can't drift per call site.
 *
 * @param title the dialog headline ("Delete this key?", "Reset cost history?").
 * @param message the explanatory body line.
 * @param confirmLabel the confirm button label ("Delete", "Reset", "Overwrite").
 * @param onConfirm invoked when the confirm button is tapped.
 * @param onDismiss invoked on Cancel, scrim tap, or system back.
 * @param modifier outer modifier forwarded to the [AlertDialog].
 * @param destructive when true, the confirm action paints destructive (red
 *   text); when false it paints the affirmative filled-accent primary.
 * @param dismissLabel the dismiss button label (defaults to "Cancel").
 * @param confirmTestTag optional `testTag` on the confirm button, so call sites
 *   that previously hand-rolled the dialog keep their existing instrumentation
 *   hooks after migrating onto [ConfirmDialog] (e.g. a "Stop"/"Clear" confirm).
 * @param dismissTestTag optional `testTag` on the dismiss (Cancel) button, same
 *   migration rationale as [confirmTestTag].
 */
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
            Text(
                text = message,
                color = PocketShellColors.TextSecondary,
                style = PocketShellTypography.bodyMedium,
            )
        },
        confirmButton = {
            PocketShellButton(
                text = confirmLabel,
                onClick = onConfirm,
                variant = if (destructive) ButtonVariant.Destructive else ButtonVariant.Primary,
                modifier = if (confirmTestTag != null) Modifier.testTag(confirmTestTag) else Modifier,
            )
        },
        dismissButton = {
            PocketShellButton(
                text = dismissLabel,
                onClick = onDismiss,
                variant = ButtonVariant.Text,
                modifier = if (dismissTestTag != null) Modifier.testTag(dismissTestTag) else Modifier,
            )
        },
        containerColor = PocketShellColors.Surface,
        shape = PocketShellShapes.large,
    )
}
