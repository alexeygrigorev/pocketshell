package com.pocketshell.app.tmux

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.app.session.ConversationLoadState
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes

/**
 * Issue #1684: an opaque, semantics-free mask for a terminal surface that must
 * not paint yet (stale non-target pager page, attach hold content, or the
 * one-frame terminal swap latch).
 *
 * This is deliberately NOT loading chrome. The one labelled primary loader is
 * mounted at the fixed screen-level overlay slot in [TmuxSessionScreen], so a
 * child measured in pager/scroll geometry cannot relocate that indicator.
 */
@Composable
internal fun SessionSurfaceMaskPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color = PocketShellColors.Background),
    )
}

/**
 * Issue #778: lightweight Conversation-tab placeholder shown when the user has
 * tapped Conversation on a presumed-agent pane (#716) before live agent
 * detection has landed (`detection == null`). It honours the tap — the view
 * switches to the Conversation surface immediately — while being honest that the
 * transcript is not ready yet. The real [TmuxConversationPane] replaces this the
 * instant detection seeds a conversation row. Deliberately does NOT mount
 * [TmuxConversationPane] (which assumes a real detection/transcript), so there
 * is no empty-state crash and no #605 swap-latch interaction.
 *
 * `internal` so the #778 rendered-UI regression test
 * ([com.pocketshell.app.conversation.ConversationDetectingPlaceholderRenderTest])
 * can mount the REAL production placeholder and assert it is displayed when the
 * Conversation tap lands before detection.
 */
@Composable
internal fun ConversationDetectingPlaceholder(
    // Issue #793: the full-screen state shown BEFORE the transcript renders.
    // Loading → "Loading conversation…" (NOT "Waiting for agent…", which is
    // reserved for a genuinely-pending live agent turn). Failed → a clear
    // terminal error with a Retry, never an infinite spinner. Empty → a clear
    // "no messages yet" terminal state.
    loadState: ConversationLoadState = ConversationLoadState.Loading,
    onRetry: () -> Unit = {},
    // Issue #1207: switch to the Terminal tab — the only surface that can show a
    // live agent's alt-screen TUI (a fresh session's picker / prompt). Offered on
    // the terminal Empty state so a stranded placeholder is never a dead end.
    onOpenTerminal: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color = PocketShellColors.Background)
            .testTag(TMUX_CONVERSATION_DETECTING_TAG),
        contentAlignment = Alignment.Center,
    ) {
        when (loadState) {
            ConversationLoadState.Failed -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .testTag(TMUX_CONVERSATION_LOAD_FAILED_TAG),
            ) {
                Text(
                    text = "Couldn't load this conversation.",
                    color = PocketShellColors.Text,
                    fontSize = 16.sp,
                )
                Spacer(modifier = Modifier.height(12.dp))
                PocketShellButton(
                    text = "Retry",
                    onClick = onRetry,
                    modifier = Modifier.testTag(TMUX_CONVERSATION_LOAD_RETRY_TAG),
                    variant = ButtonVariant.Text,
                )
            }
            // Issue #1207: the Empty terminal state is no longer a dead "No
            // conversation events yet." line. A fresh Claude/Codex session shows
            // nothing on the Conversation surface because the agent is live in its
            // alt-screen TUI (the picker / prompt) that writes NOTHING to the
            // transcript — so this state points the user at the Terminal tab, the
            // only surface that can show it, with a one-tap action. This is also
            // the terminal state a stranded placeholder (torn-down row, no
            // watchdog) now resolves to, instead of an eternal spinner.
            ConversationLoadState.Empty -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .testTag(TMUX_CONVERSATION_LOAD_EMPTY_TAG),
            ) {
                Text(
                    text = "No conversation yet — the agent is live in the Terminal tab.",
                    color = PocketShellColors.TextSecondary,
                    fontSize = 14.sp,
                )
                Spacer(modifier = Modifier.height(12.dp))
                PocketShellButton(
                    text = "Open in Terminal",
                    onClick = onOpenTerminal,
                    modifier = Modifier.testTag(TMUX_CONVERSATION_OPEN_TERMINAL_TAG),
                    variant = ButtonVariant.Text,
                )
            }
            else -> LoadingIndicator.Spinner(
                size = SpinnerSize.Medium,
                label = "Loading conversation…",
            )
        }
    }
}

/**
 * Issue #1207 / #1584: inline notice shown over the Conversation surface after a
 * genuine TUI-only slash-command (classified per-agent via [tmuxAgentConversationSend]
 * against [com.pocketshell.app.agentcommands.AgentCommandCatalog]'s allowlist) is
 * sent from the composer. Such
 * a command (`/model`, `/config`, a permission picker …) drives an alt-screen
 * picker in the covered Terminal pane and writes NOTHING to the transcript, so
 * the Conversation view can never show it. Rather than the old misleading
 * optimistic bubble + a silent nothing, this banner is honest about where the
 * interaction is and gives a one-tap jump to the only surface that can drive it.
 *
 * `internal` so the #1207 rendered-UI regression test can mount the REAL notice.
 */
@Composable
internal fun ConversationTuiCommandNotice(
    command: String,
    onOpenTerminal: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(PocketShellColors.Surface, PocketShellShapes.medium)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag(TMUX_CONVERSATION_TUI_NOTICE_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "“$command” opens an interactive picker in the Terminal — " +
                "it doesn't show here.",
            color = PocketShellColors.Text,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        PocketShellButton(
            onClick = onOpenTerminal,
            modifier = Modifier.testTag(TMUX_CONVERSATION_TUI_NOTICE_OPEN_TAG),
            variant = ButtonVariant.Text,
        ) {
            Text("Open in Terminal", color = PocketShellColors.Accent)
        }
        PocketShellButton(
            text = "Dismiss",
            onClick = onDismiss,
            variant = ButtonVariant.Text,
        )
    }
}

@Composable
internal fun EmptyPanesPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color = PocketShellColors.Surface),
        contentAlignment = Alignment.Center,
    ) {
        // Issue #757: the "waiting for tmux panes…" connecting state was static
        // text with no animated affordance, unlike the "Attaching…" placeholder.
        // Adopt the canonical centered [LoadingIndicator.Spinner] (#756) with the
        // label slot so this state shows the SAME spinner used everywhere else.
        LoadingIndicator.Spinner(
            size = SpinnerSize.Medium,
            label = "waiting for tmux panes…",
        )
    }
}

@Composable
internal fun PageIndicator(pageCount: Int, currentPage: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        for (i in 0 until pageCount) {
            val color = if (i == currentPage) {
                PocketShellColors.Accent
            } else {
                PocketShellColors.TextSecondary
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(6.dp)
                    .background(color = color),
            )
        }
    }
}
