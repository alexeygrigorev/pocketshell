package com.pocketshell.app.tmux

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.pocketshell.app.composer.PromptComposerSheet
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.voice.ADD_COMMAND_CHIP_LABEL
import com.pocketshell.app.voice.BottomChipControls
import com.pocketshell.app.voice.ConversationComposerLauncherRow
import com.pocketshell.app.voice.HOTKEYS_CHIP_LABEL
import com.pocketshell.app.voice.HotkeysChipIcon
import com.pocketshell.app.voice.SnippetsChipIcon
import com.pocketshell.uikit.components.CommandChip
import com.pocketshell.uikit.components.HotkeySection
import com.pocketshell.uikit.model.KeyBinding
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing

/**
 * Bottom terminal controls for tmux panes.
 *
 * Issue #588: once the terminal keyboard is up, this area is strictly a
 * terminal-control accessory. Prompt text belongs in [PromptComposerSheet],
 * opened from the IME-hidden bottom band.
 *
 * Issue #784 (composer/hotkeys redesign — D22 hard-cut): the terminal hotkey
 * key bar no longer lives HERE or in the composer. #755 had relocated it into
 * the composer, where it ate the space above the keyboard, hid keys behind a
 * `…` expander, and squished the compose field. It is now the dedicated
 * [com.pocketshell.uikit.components.TerminalHotkeysPanel] in its OWN bottom
 * sheet ([TerminalHotkeysSheet]), opened from this surface's hotkeys launcher.
 *
 * Issue #789 (D22 hard-cut): the launcher is a COMPACT chip, not the deleted
 * full-width bar — its row of vertical space wasted too much room. With the
 * keyboard UP this control area renders a single right-pinned `hotkeys` chip
 * above the IME (one tap to open the panel); with the keyboard DOWN the same
 * compact chip lives inline in the [BottomChipControls] primary cluster.
 *
 * Issue #673: staged composer attachments are NOT rendered here. They are
 * visible only inside the Prompt Composer sheet; the staged-attachment STATE
 * still lives in the composer ViewModel (persisting across session switches),
 * so re-opening the composer shows them again. The session/terminal bottom
 * area never surfaces an attachment chip/grid.
 */
@Composable
internal fun TmuxSessionBottomControlsCallSite(
    selectedTab: SessionTab?,
    sessionLive: Boolean,
    // Issue #1672: true while the terminal surface is HELD behind the "Attaching…"
    // loader (any non-`Live` [com.pocketshell.core.connection.SessionSurfaceState]
    // — Connecting / Attaching / Reattaching / Reconnecting). While held, the
    // Terminal primary-control band is HIDDEN, not merely disabled, so the
    // bottom chrome reads the SAME connection state as the held terminal above
    // it (one coherent state, #1321/#1331). Distinct from [sessionLive] (=
    // input routable): the held
    // signal is derived from the surface authority, so the band tracks the
    // "Attaching…" hold exactly. Defaults to `false` (live band) so component
    // call sites that render the steady-state surface do not have to thread it.
    terminalHeld: Boolean = false,
    onDictateTap: (() -> Unit)?,
    // Issue #585: hold-the-launcher-and-swipe-up entry gesture — open the Prompt
    // Composer WITH recording already active + locked hands-free.
    onDictateHoldSwipeUp: (() -> Unit)? = null,
    onEnterTap: (() -> Unit)?,
    onShowKeyboardTap: (() -> Unit)?,
    onAddSnippetTap: (() -> Unit)?,
    onShowHotkeysTap: (() -> Unit)? = null,
    hotkeysLauncherTag: String = TERMINAL_HOTKEYS_LAUNCHER_TAG,
    leadingChipContent: (@Composable () -> Unit)? = null,
    // Issue #1531 (audit RC1): the unsent-queue badge shown on the docked composer
    // launcher so a queued / deferred / uploading / failed send is VISIBLE on the
    // session screen (not only inside the opened composer sheet).
    unsentCount: Int = 0,
    unsentHasFailure: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Issue #805 / #2191: bottom-bar chrome follows the Conversation TAB,
    // never the content-area surface. Detection-window placeholders and the
    // #2191 Terminal-surface fallthrough must still drop the Terminal chips.
    val onConversationTab = tmuxSessionBottomControlsShowsConversation(selectedTab)
    TmuxTerminalBottomControls(
        showConversation = onConversationTab,
        sessionLive = sessionLive,
        terminalHeld = terminalHeld,
        onDictateTap = onDictateTap,
        onDictateHoldSwipeUp = onDictateHoldSwipeUp,
        onEnterTap = onEnterTap,
        onShowKeyboardTap = onShowKeyboardTap,
        onAddSnippetTap = onAddSnippetTap,
        onShowHotkeysTap = onShowHotkeysTap,
        hotkeysLauncherTag = hotkeysLauncherTag,
        leadingChipContent = leadingChipContent,
        unsentCount = unsentCount,
        unsentHasFailure = unsentHasFailure,
        modifier = modifier,
    )
}

@Composable
internal fun TmuxTerminalBottomControls(
    showConversation: Boolean,
    sessionLive: Boolean,
    // Issue #1672: while true (terminal held behind the "Attaching…" loader), the
    // Terminal-tab primary-control band is HIDDEN — see
    // [tmuxTerminalHiddenImeSurface]. Defaults to `false` so the many component
    // call sites that render the live band unchanged do not have to thread it.
    terminalHeld: Boolean = false,
    onDictateTap: (() -> Unit)?,
    // Issue #585: hold-the-launcher-and-swipe-up entry gesture — open the Prompt
    // Composer WITH recording already active + locked hands-free.
    onDictateHoldSwipeUp: (() -> Unit)? = null,
    onEnterTap: (() -> Unit)?,
    onShowKeyboardTap: (() -> Unit)?,
    onAddSnippetTap: (() -> Unit)?,
    // Issue #784/#789: open the dedicated terminal-hotkeys panel. Reachable as a
    // COMPACT chip both with the keyboard down (inline in the chip cluster) and
    // with the keyboard UP (a right-pinned chip above the IME), so the user can
    // summon the full hotkey grid whenever they are interacting with the
    // terminal. Null on surfaces with no pane to receive control bytes (e.g. the
    // Conversation tab).
    onShowHotkeysTap: (() -> Unit)? = null,
    hotkeysLauncherTag: String = TERMINAL_HOTKEYS_LAUNCHER_TAG,
    leadingChipContent: (@Composable () -> Unit)? = null,
    // Issue #1531 (audit RC1): the unsent-queue badge for the docked composer
    // launcher (see [TmuxSessionBottomControlsCallSite]).
    unsentCount: Int = 0,
    unsentHasFailure: Boolean = false,
    modifier: Modifier = Modifier,
) {
    when (
        tmuxTerminalHiddenImeSurface(
            showConversation = showConversation,
            terminalHeld = terminalHeld,
        )
    ) {
                TmuxTerminalHiddenImeSurface.LauncherOnly -> {
                    // Issue #786 (Conversation tab, hard-cut D22) AND Issue #1672
                    // (Terminal tab while the terminal is HELD during connect): the
                    // bottom band is JUST the composer launcher — no #628
                    // previous-session toggle, snippets picker, or primary cluster.
                    //
                    // For #1672 this is the key coherence fix: while the terminal is
                    // held behind the "Attaching…" loader, input CANNOT reach the
                    // pane, so showing an operable-looking command band (even
                    // disabled) contradicts the held terminal above it. Hiding the
                    // band — not merely disabling it — makes the whole screen read
                    // ONE connection state (#1321/#1331). Everything the band offered
                    // stays reachable once Live: fast session-switch on the top
                    // breadcrumb, snippets in the composer's `{}`, slash commands via
                    // the composer. The launcher keeps its #810 unconditional
                    // presence (it can still open the composer to queue a message
                    // while reconnecting — the #1531 unsent queue).
                    if (onDictateTap != null) {
                        ConversationComposerLauncherRow(
                            onDictateTap = onDictateTap,
                            onDictateHoldSwipeUp = onDictateHoldSwipeUp,
                            // The composer is the durable offline-send entry point,
                            // so reconnecting must disable only pane-bound controls,
                            // never the launcher itself. Otherwise the first queued
                            // send dismisses the sheet and there is no way to compose
                            // another message until the transport heals (#1944).
                            inputEnabled = true,
                            unsentCount = unsentCount,
                            unsentHasFailure = unsentHasFailure,
                            modifier = modifier,
                        )
                    }
                }
                TmuxTerminalHiddenImeSurface.Controls -> {
                    // Issue #789 (hard-cut, D22): the full-width
                    // `TerminalHotkeysLauncherBar` (#784) is GONE. The launcher is now
                    // a COMPACT chip inline in the [BottomChipControls] primary
                    // cluster, so the dedicated bar row's vertical space is reclaimed.
                    // The chip opens the same dedicated [TerminalHotkeysSheet].
                    // Terminal tab, LIVE only — the panel writes control bytes to the
                    // raw pane (while held this branch is not reached, per #1672).
                    BottomChipControls(
                        onDictateTap = onDictateTap,
                        onDictateHoldSwipeUp = onDictateHoldSwipeUp,
                        onEnterTap = onEnterTap,
                        onShowKeyboardTap = onShowKeyboardTap,
                        onAddSnippetTap = onAddSnippetTap,
                        // Issue #789: the compact hotkeys launcher chip (terminal tab
                        // only). Reclaims the deleted full-width bar's row.
                        onShowHotkeysTap = onShowHotkeysTap,
                        hotkeysLauncherTag = hotkeysLauncherTag,
                        addSnippetLabel = ADD_COMMAND_CHIP_LABEL,
                        addSnippetIcon = SnippetsChipIcon,
                        leadingContent = leadingChipContent,
                        // Project navigation on tmux panes is a separate
                        // follow-up — see #123 notes on per-pane cwd /
                        // project-root wiring.
                        onProjectNavigationTap = null,
                        // Issue #2192: sessionLive gates pane-bound writes (Enter)
                        // only. The launcher is a local sheet-open (#1944) and
                        // must stay enabled through reconnect / empty-pane wedges.
                        inputEnabled = sessionLive,
                        unsentCount = unsentCount,
                        unsentHasFailure = unsentHasFailure,
                        modifier = modifier,
                    )
                }
    }
}

/**
 * Issue #887 recurrence: the IME-only compact launcher is a screen overlay, not
 * a member of the TerminalView-measuring column. Its host supplies
 * `imePadding()` so this one user-facing control remains reachable above the
 * real keyboard while the terminal keeps its keyboard-down constraints.
 */
@Composable
internal fun TmuxTerminalImeHotkeysLauncher(
    onShowHotkeysTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            .border(width = 1.dp, color = PocketShellColors.Border)
            .padding(
                horizontal = PocketShellSpacing.sm,
                vertical = PocketShellSpacing.sm,
            ),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CommandChip(
            label = HOTKEYS_CHIP_LABEL,
            onClick = onShowHotkeysTap,
            icon = HotkeysChipIcon,
            modifier = Modifier.testTag(TERMINAL_HOTKEYS_LAUNCHER_TAG),
        )
    }
}

/**
 * Issue #887 recurrence: one stable measuring slot for the keyboard-DOWN
 * production controls. The child remains composed and measured while the IME is
 * visible, so session/pane content, density, font scale and stable system-inset
 * changes naturally remeasure without a cached pixel height. It is deliberately
 * not placed in the IME state: its semantics and pointer input leave the
 * rendered tree, while this layout still reserves the exact measured height.
 *
 * The IME-only compact launcher is rendered separately by
 * [TmuxTerminalImeHotkeysLauncher] at screen-overlay level.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ReservedTmuxTerminalBottomBand(
    isImeVisible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box(
                modifier = if (isImeVisible) {
                    Modifier.clearAndSetSemantics {}
                } else {
                    Modifier
                },
            ) {
                content()
            }
        },
        modifier = modifier.windowInsetsPadding(
            WindowInsets.navigationBarsIgnoringVisibility,
        ),
    ) { measurables, constraints ->
        check(measurables.size == 1) {
            "ReservedTmuxTerminalBottomBand requires exactly one measurable child"
        }
        val placeable = measurables.single().measure(
            constraints.copy(minWidth = 0, minHeight = 0),
        )
        layout(
            width = maxOf(placeable.width, constraints.minWidth),
            height = maxOf(placeable.height, constraints.minHeight),
        ) {
            if (!isImeVisible) {
                placeable.placeRelative(0, 0)
            }
        }
    }
}

/**
 * Chooses the screen-level bottom-band geometry. Terminal keeps its measured
 * keyboard-down reservation through IME transitions; Conversation deliberately
 * has no IME accessory, so it reserves zero rows while the keyboard is up.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun TmuxSessionBottomBandPlacement(
    isImeVisible: Boolean,
    onConversationTab: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (onConversationTab) {
        if (!isImeVisible) {
            Box(
                modifier = modifier.windowInsetsPadding(
                    WindowInsets.navigationBarsIgnoringVisibility,
                ),
            ) {
                content()
            }
        }
    } else {
        ReservedTmuxTerminalBottomBand(
            isImeVisible = isImeVisible,
            modifier = modifier,
            content = content,
        )
    }
}

/**
 * Issue #789 (hard-cut, D22): the full-width `TerminalHotkeysLauncherBar` (#784)
 * was DELETED — the maintainer reported the dedicated bar row wasted vertical
 * space. The launcher is now a COMPACT chip ([com.pocketshell.app.voice.HOTKEYS_CHIP_LABEL])
 * rendered inline in the primary chip cluster ([com.pocketshell.app.voice.BottomChipControls])
 * with the keyboard down, and as a compact right-pinned chip above the IME with
 * the keyboard up — both opening the same dedicated [TerminalHotkeysSheet].
 *
 * The stable test tag is kept as an alias of
 * [com.pocketshell.app.voice.HOTKEYS_CHIP_TAG] (`"tmux:hotkeys-launcher"`) so the
 * existing connected tests that locate the launcher by this tag keep working
 * unchanged.
 */
internal const val TERMINAL_HOTKEYS_LAUNCHER_TAG: String =
    com.pocketshell.app.voice.HOTKEYS_CHIP_TAG

/**
 * Issue #1672: what the keyboard-DOWN bottom band renders. This is the SINGLE
 * decision the composable dispatches on, so
 * a JVM test of this function is load-bearing for the visible outcome.
 *
 *  - [LauncherOnly] — JUST the composer launcher (no primary-control band, no
 *    primary cluster). Reached on the Conversation tab (#786, always) OR while the
 *    terminal is HELD behind the "Attaching…" loader during connect (#1672 — any
 *    non-`Live` [com.pocketshell.core.connection.SessionSurfaceState]). Hiding the
 *    band while held — rather than showing it disabled — makes the bottom chrome a
 *    coherent projection of the SAME connection state as the held terminal above it
 *    (the maintainer's "this panel on connecting makes no sense" report).
 *  - [Controls] — the Terminal primary cluster + launcher, the steady-state
 *    LIVE surface.
 */
internal enum class TmuxTerminalHiddenImeSurface {
    LauncherOnly,
    Controls,
}

internal fun tmuxTerminalHiddenImeSurface(
    showConversation: Boolean,
    terminalHeld: Boolean,
): TmuxTerminalHiddenImeSurface = when {
    // The Conversation tab is launcher-only regardless of connection state (#786).
    showConversation -> TmuxTerminalHiddenImeSurface.LauncherOnly
    // Issue #1672: the terminal is held behind the "Attaching…" loader — hide the
    // primary-control band so nothing looks operable while input cannot be sent.
    terminalHeld -> TmuxTerminalHiddenImeSurface.LauncherOnly
    // Live Terminal tab: the retained terminal controls return.
    else -> TmuxTerminalHiddenImeSurface.Controls
}

/** Issue #1662: visible label for the dedicated Ctrl-picker action. */
internal const val TmuxHotkeyCtrlFlowLabel: String = "Ctrl+…"

internal const val TmuxHotkeyEnterLabel: String = "Enter"

// These wire-only labels are not rendered as tiles. The canonical ^C/^D keys
// invoke them on long press so the existing atomic two-byte path stays intact.
internal const val TmuxHotkeyInterruptX2Label: String = "^C×2"
internal const val TmuxHotkeyEofX2Label: String = "^D×2"

/**
 * Issue #1662 main page: a one-screenful catalog with only common controls.
 *
 * The old CTRL COMBOS, visible doubled tiles, sticky modifier, literal-letter
 * grid, and expander are deliberately gone. Arbitrary control chords live on
 * [TmuxHotkeyCtrlSections].
 */
internal val TmuxHotkeyMainSections: List<HotkeySection> = listOf(
    HotkeySection(
        title = "ARROWS",
        keys = listOf(
            KeyBinding(label = "←", kind = KeyKind.Arrow),
            KeyBinding(label = "↑", kind = KeyKind.Arrow),
            KeyBinding(label = "↓", kind = KeyKind.Arrow),
            KeyBinding(label = "→", kind = KeyKind.Arrow),
        ),
        columns = 4,
    ),
    HotkeySection(
        title = "KEYS",
        keys = listOf(
            KeyBinding(label = "Esc", kind = KeyKind.Regular),
            KeyBinding(label = "Tab", kind = KeyKind.Regular),
            KeyBinding(label = "⇧Tab", kind = KeyKind.Regular),
            KeyBinding(label = TmuxHotkeyEnterLabel, kind = KeyKind.Regular),
        ),
        columns = 4,
    ),
    HotkeySection(
        title = "CTRL",
        keys = listOf(
            KeyBinding(label = "^B", kind = KeyKind.Regular),
            KeyBinding(label = "^C", kind = KeyKind.Regular),
            KeyBinding(label = "^D", kind = KeyKind.Regular),
            KeyBinding(label = "^Q", kind = KeyKind.Regular),
            KeyBinding(label = "^X", kind = KeyKind.Regular),
        ),
        columns = 5,
    ),
)

/**
 * Issue #1662 Ctrl page: every control chord the old sheet exposed, arranged
 * by QWERTY muscle memory in five columns. Labels include the caret so the same
 * generic `^<char>` parser handles both pages.
 */
internal val TmuxHotkeyCtrlSections: List<HotkeySection> = listOf(
    HotkeySection(
        title = "CTRL + KEY",
        keys = listOf("QWERT", "YUIOP", "ASDFG", "HJKL", "ZXCVB", "NM\\")
            .flatMap(::controlBindings),
        columns = 5,
        rows = listOf("QWERT", "YUIOP", "ASDFG", "HJKL", "ZXCVB", "NM\\")
            .map(::controlBindings),
    ),
)

private fun controlBindings(keys: String): List<KeyBinding> =
    keys.map { key -> KeyBinding("^$key", KeyKind.Regular) }
