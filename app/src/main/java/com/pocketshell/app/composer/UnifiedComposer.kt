package com.pocketshell.app.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Issue #196: single source of truth for PocketShell's composer surface.
 *
 * The app has two places where the user composes text to send into a
 * remote:
 *
 *  - **Terminal shell** ([com.pocketshell.app.composer.PromptComposerSheet]):
 *    the bottom-sheet dictation composer that writes bytes straight into
 *    the focused tmux/SSH pane.
 *  - **Agent pane** ([com.pocketshell.app.tmux.TmuxConversationPane] /
 *    [com.pocketshell.app.session.ConversationPane]): the inline
 *    "type back into the agent" composer added by #160.
 *
 * Before #196 these surfaces drew their own controls — the terminal sheet
 * had a styled draft box with an accent cursor and tier-differentiated
 * Insert / Send buttons, while the agent pane used a bare Material
 * `OutlinedTextField` + a plain `TextButton("Send")`. They looked and
 * behaved differently, which is exactly the parity gap the maintainer
 * reported.
 *
 * This file holds the shared building blocks both surfaces now consume:
 *
 *  - [ComposerTarget] — the per-call-site context flag.
 *  - [ComposerDraftField] — the styled draft entry box (surface-elev
 *    fill, accent cursor, muted placeholder) that replaces the bare
 *    `OutlinedTextField` on the agent pane and the inline draft box in
 *    the terminal sheet.
 *  - [ComposerSendButton] (outline secondary) and
 *    [ComposerSendEnterButton] (filled primary) — the tier-differentiated
 *    terminal action buttons, with the long-press tooltip behaviour from #153.
 *
 * Per-target adjustments are intentional and minimal:
 *
 *  - The terminal shell exposes both **Insert** (write bytes, no Enter) and
 *    **Send** (write bytes + Enter) because typing a partial command
 *    fragment is a first-class use case there.
 *  - The agent pane only exposes a single primary **Send** because sending
 *    a message to an agent always submits it (`sendToAgent` always uses
 *    `withEnter = true`), so a separate "Send without Enter" affordance
 *    would be meaningless.
 */
public enum class ComposerTarget {
    /** Bytes go straight into the focused tmux/SSH pane (raw shell stdin). */
    TerminalShell,

    /** The message is echoed into the conversation list and submitted to the agent. */
    AgentPane,
}

/**
 * The styled draft-entry box shared by both composer surfaces.
 *
 * Visual recipe (lifted from the mature terminal composer so the agent
 * pane now matches it exactly):
 *  - `SurfaceElev` fill, 1dp `Border` stroke, 12dp radius.
 *  - A [BasicTextField] with the accent-coloured cursor and the muted
 *    placeholder rendered inside the same box (no floating label, matching
 *    `docs/mockups/composer.html`).
 *
 * The terminal sheet uses [minHeight] = 110dp (a multi-line draft area);
 * the agent pane uses a compact single-row height so the conversation
 * feed keeps the vertical space. Both share identical typography, colour
 * tokens, cursor, and placeholder treatment.
 */
@Composable
internal fun ComposerDraftField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    // The test tag goes on the inner editable [BasicTextField] (not the
    // styled wrapper) so connected tests can `performTextInput` /
    // `assertTextContains` directly against the focusable editable node —
    // the same node identity both composer surfaces had before #196.
    fieldTag: String? = null,
    minHeight: androidx.compose.ui.unit.Dp = 110.dp,
    singleLine: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .background(
                color = PocketShellColors.SurfaceElev,
                shape = RoundedCornerShape(12.dp),
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.Border,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val fieldModifier = if (fieldTag != null) {
            Modifier
                .fillMaxWidth()
                .testTag(fieldTag)
        } else {
            Modifier.fillMaxWidth()
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            modifier = fieldModifier,
            textStyle = TextStyle(
                color = PocketShellColors.Text,
                fontSize = 14.sp,
            ),
            cursorBrush = SolidColor(PocketShellColors.Accent),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = PocketShellColors.TextMuted,
                        fontSize = 14.sp,
                    )
                }
                inner()
            },
        )
    }
}

/**
 * Issue #196 / #153: secondary (outline) Send button shared by both
 * composer surfaces. Transparent fill, 1dp accent stroke, accent text —
 * the deliberate visual downgrade from the primary so the two Send
 * affordances don't read as one grouped pair (the #153 mis-tap fix).
 */
@Composable
internal fun ComposerSendButton(
    label: String,
    tooltipLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ComposerTooltipButton(
        label = label,
        tooltipLabel = tooltipLabel,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        containerColor = Color.Transparent,
        contentColor = PocketShellColors.Accent,
        disabledContentColor = PocketShellColors.TextMuted,
        borderColor = PocketShellColors.Accent,
        disabledBorderColor = PocketShellColors.Border,
    )
}

/**
 * Issue #196 / #153: primary (filled accent) Send button shared by both
 * composer surfaces. Solid accent fill + on-accent text — the single
 * "do the thing" coloured affordance per row (matches the mic FAB token).
 */
@Composable
internal fun ComposerSendEnterButton(
    label: String,
    tooltipLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ComposerTooltipButton(
        label = label,
        tooltipLabel = tooltipLabel,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        containerColor = PocketShellColors.Accent,
        contentColor = PocketShellColors.OnAccent,
        disabledContentColor = PocketShellColors.TextMuted,
        borderColor = PocketShellColors.Accent,
        disabledBorderColor = PocketShellColors.Border,
    )
}

/**
 * Issue #196 / #153: hand-rolled Send button used by both the outline
 * (secondary) and the filled (primary) variants across both composer
 * surfaces.
 *
 * Combines a styled [Box], a [Modifier.combinedClickable] (so
 * `performClick()` in connected tests triggers the same path the user's
 * finger does), and a long-press [Popup] showing [tooltipLabel].
 *
 * Why not Material 3's `TooltipBox`: in Compose BOM 2025.05 (Material 3
 * 1.3.2), wrapping a clickable child in `TooltipBox` swallows the
 * synthesised tap events emitted by `performClick`. `combinedClickable`
 * does not have that interaction.
 */
@Composable
internal fun ComposerTooltipButton(
    label: String,
    tooltipLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = PocketShellColors.Accent,
    contentColor: Color = PocketShellColors.OnAccent,
    disabledContentColor: Color = PocketShellColors.TextMuted,
    borderColor: Color = PocketShellColors.Accent,
    disabledBorderColor: Color = PocketShellColors.Border,
) {
    var showTooltip by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .height(44.dp)
            .background(
                color = if (enabled) containerColor else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .border(
                width = 1.dp,
                color = if (enabled) borderColor else disabledBorderColor,
                shape = RoundedCornerShape(10.dp),
            )
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                onLongClick = { showTooltip = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) contentColor else disabledContentColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            // Horizontal breathing room so a wrap-content Send button
            // (the agent pane's single primary) never clips its label.
            // The terminal Send buttons use `weight(1f)`, so the centred
            // label there is unaffected.
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        if (showTooltip) {
            Popup(
                popupPositionProvider = ComposerAboveAnchorPopupPositionProvider,
                onDismissRequest = { showTooltip = false },
                properties = PopupProperties(
                    focusable = false,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                ),
            ) {
                Surface(
                    color = PocketShellColors.SurfaceElev,
                    contentColor = PocketShellColors.Text,
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = PocketShellColors.Border,
                    ),
                    modifier = Modifier.testTag(composerSendTooltipTestTag(tooltipLabel)),
                ) {
                    Text(
                        text = tooltipLabel,
                        color = PocketShellColors.Text,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * Issue #196 / #153: position provider for the long-press tooltip popup.
 * Anchors the tooltip directly above the source button, horizontally
 * centred, clamped to the window (falls back below the anchor if there is
 * no room above).
 */
internal object ComposerAboveAnchorPopupPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val gapPx = 24 // ~8dp at xhdpi; close enough for a tooltip offset
        val centreX = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val x = centreX.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val yAbove = anchorBounds.top - popupContentSize.height - gapPx
        val y = if (yAbove >= 0) {
            yAbove
        } else {
            (anchorBounds.bottom + gapPx).coerceAtMost(
                (windowSize.height - popupContentSize.height).coerceAtLeast(0),
            )
        }
        return IntOffset(x, y)
    }
}

/**
 * Issue #196: the shared agent-pane send row.
 *
 * Stacks the [ComposerDraftField] above a single primary
 * [ComposerSendEnterButton] so the agent composer mirrors the terminal
 * composer's draft-box-then-action-row rhythm. The agent target uses one
 * Send button (sending a message to an agent always submits — see
 * [ComposerTarget]) rather than the terminal's two-button Insert / Send
 * pair.
 *
 * @param value current draft text.
 * @param onValueChange draft edits.
 * @param onSend tapped Send with a non-blank draft. Callers trim + clear.
 * @param sendEnabled gate on top of the non-blank check (e.g. session
 *   liveness for #249) — when false the button is disabled and a tap
 *   cannot deliver-then-clear the draft.
 * @param placeholder hint text for the destination-specific agent name.
 * @param inputFieldTag / sendButtonTag the surface-specific test tags so
 *   the existing tmux / raw-SSH connected tests keep resolving the same
 *   nodes.
 */
@Composable
internal fun AgentComposerSurface(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    inputFieldTag: String,
    sendButtonTag: String,
    modifier: Modifier = Modifier,
    sendEnabled: Boolean = true,
    placeholder: String = "Message agent",
) {
    val canSend = sendEnabled && value.isNotBlank()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ComposerDraftField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            fieldTag = inputFieldTag,
            minHeight = 48.dp,
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        ComposerSendEnterButton(
            label = "Send",
            tooltipLabel = AGENT_SEND_TOOLTIP_LABEL,
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier.testTag(sendButtonTag),
        )
    }
}

@Composable
internal fun UnsentPromptBanner(
    visible: Boolean,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(
                color = PocketShellColors.AccentSoft,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = "Prompt saved. Reconnect, then send it when ready.",
            color = PocketShellColors.Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onDiscard,
                modifier = Modifier.testTag(UNSENT_PROMPT_DISCARD_TAG),
            ) {
                Text("Discard")
            }
            TextButton(
                onClick = onRetry,
                enabled = canRetry,
                modifier = Modifier.testTag(UNSENT_PROMPT_RETRY_TAG),
            ) {
                Text("Send")
            }
        }
    }
}

/**
 * Issue #196: long-press tooltip copy for the agent-pane Send button.
 * Kept here so the agent send affordance reuses the same long-press
 * "explain this control" behaviour the terminal Send buttons have.
 */
internal const val AGENT_SEND_TOOLTIP_LABEL: String =
    "Send the message to the agent"

internal const val UNSENT_PROMPT_RETRY_TAG: String = "unsent-prompt-retry"
internal const val UNSENT_PROMPT_DISCARD_TAG: String = "unsent-prompt-discard"
