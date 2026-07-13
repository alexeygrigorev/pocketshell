package com.pocketshell.app.tmux

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pocketshell.app.composer.PromptComposerSheet
import com.pocketshell.app.voice.ADD_COMMAND_CHIP_LABEL
import com.pocketshell.app.voice.BottomChipControls
import com.pocketshell.app.voice.ConversationComposerLauncherRow
import com.pocketshell.app.voice.DefaultSessionChips
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
    isImeVisible: Boolean,
    showConversationTranscript: Boolean,
    showConversationDetectingPlaceholder: Boolean,
    sessionLive: Boolean,
    isAgentPane: Boolean,
    onChipTap: (String) -> Unit,
    onDictateTap: (() -> Unit)?,
    // Issue #585: hold-the-launcher-and-swipe-up entry gesture — open the Prompt
    // Composer WITH recording already active + locked hands-free.
    onDictateHoldSwipeUp: (() -> Unit)? = null,
    onEnterTap: (() -> Unit)?,
    onShowKeyboardTap: (() -> Unit)?,
    onAddSnippetTap: (() -> Unit)?,
    onShowHotkeysTap: (() -> Unit)? = null,
    leadingChipContent: (@Composable () -> Unit)? = null,
    // Issue #1531 (audit RC1): the unsent-queue badge shown on the docked composer
    // launcher so a queued / deferred / uploading / failed send is VISIBLE on the
    // session screen (not only inside the opened composer sheet).
    unsentCount: Int = 0,
    unsentHasFailure: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Issue #805 (regression of #744/#716): bottom-bar chrome follows the
    // Conversation TAB, not only the detection-gated transcript. During
    // agent-engine detection the transcript is absent but the placeholder is
    // shown, so this must still drop the Terminal chips that can push the
    // composer launcher off-screen.
    val onConversationTab = tmuxSessionBottomControlsShowsConversation(
        showConversationTranscript = showConversationTranscript,
        showConversationDetectingPlaceholder = showConversationDetectingPlaceholder,
    )
    TmuxTerminalBottomControls(
        isImeVisible = isImeVisible,
        showConversation = onConversationTab,
        sessionLive = sessionLive,
        isAgentPane = isAgentPane,
        onChipTap = onChipTap,
        onDictateTap = onDictateTap,
        onDictateHoldSwipeUp = onDictateHoldSwipeUp,
        onEnterTap = onEnterTap,
        onShowKeyboardTap = onShowKeyboardTap,
        onAddSnippetTap = onAddSnippetTap,
        onShowHotkeysTap = onShowHotkeysTap,
        leadingChipContent = leadingChipContent,
        unsentCount = unsentCount,
        unsentHasFailure = unsentHasFailure,
        modifier = modifier,
    )
}

@Composable
internal fun TmuxTerminalBottomControls(
    isImeVisible: Boolean,
    showConversation: Boolean,
    sessionLive: Boolean,
    isAgentPane: Boolean,
    onChipTap: (String) -> Unit,
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
    leadingChipContent: (@Composable () -> Unit)? = null,
    // Issue #1531 (audit RC1): the unsent-queue badge for the docked composer
    // launcher (see [TmuxSessionBottomControlsCallSite]).
    unsentCount: Int = 0,
    unsentHasFailure: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val chromeMode = tmuxTerminalKeyboardChromeMode(
        isImeVisible = isImeVisible,
        showConversation = showConversation,
    )
    when (chromeMode) {
        // Issue #673: the conversation IME-open mode renders no accessory at
        // all. Staged attachments used to surface here; they now live only
        // inside the Prompt Composer sheet.
        TmuxTerminalKeyboardChromeMode.OpenImeConversationNoAccessory -> Unit
        // Issue #784: with the keyboard up on the Terminal tab, render a SLIM
        // launcher bar above the IME so the hotkeys panel is one tap away while
        // typing. The full hotkey grid lives in the dedicated
        // [TerminalHotkeysPanel] bottom sheet — never crammed above the keyboard
        // (the #755/#784 occlusion + cram complaints), just a single launcher.
        TmuxTerminalKeyboardChromeMode.OpenImeTerminalHotkeys -> {
            if (onShowHotkeysTap != null) {
                // Issue #789 (hard-cut, D22): above the soft keyboard the
                // launcher is a COMPACT chip pinned to the right edge — NOT the
                // deleted full-width bar — so it occupies a single short row and
                // reclaims the vertical space the bar wasted. It sits on the
                // surface band so it reads above the IME, and opens the same
                // dedicated [TerminalHotkeysSheet]. The host's layout modifier
                // (which carries `imePadding()` upstream) keeps it above the
                // keyboard. The chip carries the stable `tmux:hotkeys-launcher`
                // tag so existing tests still find it.
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
        }
        TmuxTerminalKeyboardChromeMode.HiddenImeControls -> {
            if (showConversation) {
                // Issue #786 (hard-cut, D22): the Conversation tab's bottom band
                // is JUST the composer launcher — no static command chips, no
                // #628 previous-session toggle chip (the `› <project>` pill the
                // maintainer circled and didn't recognise), no snippets `{}` chip,
                // no primary cluster. Everything the bar offered stays reachable:
                // fast session-switch on the top breadcrumb, snippets in the
                // composer's `{}`, slash commands via the composer (`/`
                // autocomplete). The launcher keeps its #810 unconditional
                // presence. The Terminal tab (the `else` below) is untouched and
                // keeps its full chip band / key chips.
                if (onDictateTap != null) {
                    ConversationComposerLauncherRow(
                        onDictateTap = onDictateTap,
                        onDictateHoldSwipeUp = onDictateHoldSwipeUp,
                        inputEnabled = sessionLive,
                        unsentCount = unsentCount,
                        unsentHasFailure = unsentHasFailure,
                        modifier = modifier,
                    )
                }
            } else {
                // Issue #789 (hard-cut, D22): the full-width
                // `TerminalHotkeysLauncherBar` (#784) is GONE. The launcher is now
                // a COMPACT chip inline in the [BottomChipControls] primary
                // cluster, so the dedicated bar row's vertical space is reclaimed.
                // The chip opens the same dedicated [TerminalHotkeysSheet].
                // Terminal tab only — the panel writes control bytes to the raw
                // pane.
                BottomChipControls(
                    chips = if (isAgentPane) AgentExitChips else DefaultSessionChips,
                    onChipTap = onChipTap,
                    onDictateTap = onDictateTap,
                    onDictateHoldSwipeUp = onDictateHoldSwipeUp,
                    onEnterTap = onEnterTap,
                    onShowKeyboardTap = onShowKeyboardTap,
                    onAddSnippetTap = onAddSnippetTap,
                    // Issue #789: the compact hotkeys launcher chip (terminal tab
                    // only). Reclaims the deleted full-width bar's row.
                    onShowHotkeysTap = onShowHotkeysTap,
                    addSnippetLabel = ADD_COMMAND_CHIP_LABEL,
                    addSnippetIcon = SnippetsChipIcon,
                    leadingContent = leadingChipContent,
                    // Project navigation on tmux panes is a separate
                    // follow-up — see #123 notes on per-pane cwd /
                    // project-root wiring.
                    onProjectNavigationTap = null,
                    inputEnabled = sessionLive,
                    unsentCount = unsentCount,
                    unsentHasFailure = unsentHasFailure,
                    modifier = modifier,
                )
            }
        }
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

internal enum class TmuxTerminalKeyboardChromeMode {
    HiddenImeControls,
    OpenImeTerminalHotkeys,
    OpenImeConversationNoAccessory,
}

internal fun tmuxTerminalKeyboardChromeMode(
    isImeVisible: Boolean,
    showConversation: Boolean,
): TmuxTerminalKeyboardChromeMode = when {
    !isImeVisible -> TmuxTerminalKeyboardChromeMode.HiddenImeControls
    showConversation -> TmuxTerminalKeyboardChromeMode.OpenImeConversationNoAccessory
    else -> TmuxTerminalKeyboardChromeMode.OpenImeTerminalHotkeys
}

/**
 * Issue #784: the dedicated terminal-hotkeys panel key set.
 *
 * This replaces the cramped, `…`-overflowing in-composer key bar (#458/#755,
 * hard-cut per D22). Issue #1332 adds progressive disclosure: the panel opens
 * COMPACT showing only the COMMON set (ARROWS first — the most-used keys — then
 * `Esc`/`Tab`/`Enter`/`^C`/`^D`), with a "Show more keys" expander revealing the
 * EXTENDED set (the full `CTRL COMBOS` grid, `⇧Tab`, doubled interrupt/EOF, the
 * `Ctrl` sticky modifier, and the a–z `LETTERS` grid). No horizontal scroll, no
 * lone `Ctrl` modifier, no duplicate `/`. Each label is audited so the visible
 * glyph equals the byte sent ([TmuxSessionViewModel.onKeyBarKey]) — #1332 only
 * reordered and split the catalog; no routing/byte changed:
 *
 *  - Keys section: `Esc` (0x1B), `Tab`, `Enter` (#527).
 *  - Ctrl combos section: the useful chords as DIRECT buttons (no modifier to
 *    arm first) — `^A`(0x01) `^B`(0x02, tmux prefix / Claude "ctrl-b ctrl-b",
 *    #677) `^C`(0x03) `^D`(0x04) `^E`(0x05) `^L`(0x0C) `^R`(0x12) `^Z`(0x1A).
 *    `^[` (0x1B) is intentionally omitted: it equals `Esc`, which is already in
 *    the Keys section — exposing both would re-introduce the duplicate the
 *    maintainer flagged.
 *  - Arrows section: `←` `↑` `↓` `→` with clean, legible glyphs (replacing the
 *    old hard-to-read `‹ ⌃ ⌄ ›`).
 *
 * Every key routes through [TmuxSessionViewModel.onKeyBarKey], which maps the
 * label to its control byte (`send-keys -H` overlay) or tmux named key — no
 * terminal resize/redraw.
 */
internal const val TmuxHotkeyEnterLabel: String = "Enter"

// Issue #1091: the sticky `Ctrl` modifier label. Tapping it cycles the
// modifier (Off -> OneShot -> Locked -> Off) via
// [TmuxSessionViewModel.onCtrlModifierTap]; the next key from the LETTERS
// section is then sent as its control char so `Ctrl+<any letter>` (and the
// caret-range symbols) is reachable — the general escape hatch beyond the
// curated direct buttons. The maintainer was trapped in `nano` because the
// old fixed subset could not send arbitrary control combos.
internal const val TmuxHotkeyCtrlModifierLabel: String = "Ctrl"

// Issue #787: the DOUBLED interrupt/EOF controls, re-homed into the hotkeys
// panel from the deleted `/ commands` palette (where they were the only home —
// originally #453/#543). The double-press is a DISTINCT sequence from the single
// `^C`/`^D` above: Claude Code (and many REPLs) treat the first `^C`/`^D` as
// "press again to interrupt / exit", so the doubled byte is what actually stops
// the running agent / sends EOF. `onKeyBarKey` maps these two labels to
// `sendControlInputToPane(..., repeatCount = 2)`.
internal const val TmuxHotkeyInterruptX2Label: String = "^C×2"
internal const val TmuxHotkeyEofX2Label: String = "^D×2"

internal val TmuxHotkeyPanelSections: List<HotkeySection> = listOf(
    // Issue #1332: ARROWS is now the FIRST/top section (was last). Arrows are the
    // most-used navigation keys, so they sit at the very top, immediately
    // reachable. Part of the COMMON set (always shown, `extended = false`).
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
    // Issue #1332: the everyday essentials, shown by default alongside the
    // arrows — `Esc`, `Tab`, `Enter`, and the single `^C` / `^D` (interrupt /
    // EOF). One compact row of five. `^C` / `^D` also remain in the full CTRL
    // COMBOS grid behind the expander; surfacing them here is intentional (the
    // most-reached-for control keys). Every label still routes through
    // [TmuxSessionViewModel.onKeyBarKey] exactly as before — order/disclosure
    // only, no byte change.
    HotkeySection(
        title = "COMMON",
        keys = listOf(
            KeyBinding(label = "Esc", kind = KeyKind.Regular),
            KeyBinding(label = "Tab", kind = KeyKind.Regular),
            KeyBinding(label = TmuxHotkeyEnterLabel, kind = KeyKind.Regular),
            KeyBinding(label = "^C", kind = KeyKind.Regular),
            KeyBinding(label = "^D", kind = KeyKind.Regular),
        ),
        columns = 5,
    ),
    // Issue #1091: the curated one-tap control combos — the existing 8 plus the
    // control keys nano (and many TUIs) need that were missing: `^G` Help, `^J`
    // Justify, `^K` Cut, `^O` Write Out, `^T` Execute, `^U` cut-to-start, `^W`
    // Where-Is, `^X` Exit, `^\` Replace. Ordered by control byte so the grid
    // reads predictably. Each routes to its byte via [onKeyBarKey].
    // Issue #1332: EXTENDED — behind the "Show more keys" expander.
    HotkeySection(
        title = "CTRL COMBOS",
        keys = listOf(
            KeyBinding(label = "^A", kind = KeyKind.Regular),
            KeyBinding(label = "^B", kind = KeyKind.Regular),
            KeyBinding(label = "^C", kind = KeyKind.Regular),
            KeyBinding(label = "^D", kind = KeyKind.Regular),
            KeyBinding(label = "^E", kind = KeyKind.Regular),
            KeyBinding(label = "^G", kind = KeyKind.Regular),
            KeyBinding(label = "^J", kind = KeyKind.Regular),
            KeyBinding(label = "^K", kind = KeyKind.Regular),
            KeyBinding(label = "^L", kind = KeyKind.Regular),
            KeyBinding(label = "^O", kind = KeyKind.Regular),
            KeyBinding(label = "^R", kind = KeyKind.Regular),
            KeyBinding(label = "^T", kind = KeyKind.Regular),
            KeyBinding(label = "^U", kind = KeyKind.Regular),
            KeyBinding(label = "^W", kind = KeyKind.Regular),
            KeyBinding(label = "^X", kind = KeyKind.Regular),
            KeyBinding(label = "^Z", kind = KeyKind.Regular),
            KeyBinding(label = "^\\", kind = KeyKind.Regular),
        ),
        columns = 4,
        extended = true,
    ),
    // Issue #893: ⇧Tab (back-tab / Shift+Tab) sends tmux's `BTab` named key
    // (ESC [ Z) so the maintainer can cycle Claude Code's permission/plan mode
    // from the phone. Issue #1332: EXTENDED — it moved out of the common KEYS
    // row into the expander (the common set is arrows + Esc/Tab/Enter/^C/^D).
    HotkeySection(
        title = "MORE KEYS",
        keys = listOf(
            KeyBinding(label = "⇧Tab", kind = KeyKind.Regular),
        ),
        columns = 4,
        extended = true,
    ),
    // Issue #787: interrupt / EOF doubled chords (re-homed from the deleted
    // palette). Distinct from the single `^C`/`^D` above — these send the byte
    // TWICE so they actually stop the running agent / exit the REPL.
    // Issue #1332: EXTENDED — behind the "Show more keys" expander.
    HotkeySection(
        title = "INTERRUPT / EOF",
        keys = listOf(
            KeyBinding(label = TmuxHotkeyInterruptX2Label, kind = KeyKind.Regular),
            KeyBinding(label = TmuxHotkeyEofX2Label, kind = KeyKind.Regular),
        ),
        columns = 2,
        extended = true,
    ),
    // Issue #1091: the general `Ctrl + <any letter>` escape hatch. Tap `Ctrl`
    // (a sticky modifier — single tap = one-shot, double tap = locked, accent
    // when active) then a letter to send that letter's control byte. With
    // `Ctrl` off a letter types literally. Covers every `Ctrl+<a–z>` combo, not
    // just the curated subset above — so no TUI key is ever unreachable.
    // Issue #1332: EXTENDED — behind the "Show more keys" expander.
    HotkeySection(
        title = "CTRL + LETTER",
        keys = listOf(
            KeyBinding(label = TmuxHotkeyCtrlModifierLabel, kind = KeyKind.Modifier),
        ),
        columns = 4,
        extended = true,
    ),
    HotkeySection(
        title = "LETTERS",
        keys = ('a'..'z').map { KeyBinding(label = it.toString(), kind = KeyKind.Regular) },
        columns = 7,
        extended = true,
    ),
)

// Issue #454: the agent-pane bottom band is decluttered to the composer
// launcher plus primary controls. Slash commands are no longer part of the
// scrollable chip list — and as of #787 the standalone slash palette + bottom
// `/ commands` chip are gone entirely (the only slash entry now lives in the
// composer: its `/` button + type-`/` autocomplete). The former `Ctrl-C ×2` /
// `Ctrl-D ×2` interrupt/EOF chips' function is preserved in the hotkeys panel's
// "INTERRUPT / EOF" section (see [TmuxHotkeyInterruptX2Label] /
// [TmuxHotkeyEofX2Label] and [onKeyBarKey]).
internal val AgentExitChips: List<String> = emptyList()
