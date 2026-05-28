package com.pocketshell.app.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.app.session.VoiceCommandReviewUiState
import com.pocketshell.uikit.components.CommandChip
import com.pocketshell.uikit.components.MicButton
import com.pocketshell.uikit.model.MicButtonState
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Shared voice-surface composables used by both
 * [com.pocketshell.app.session.SessionScreen] (raw-SSH route) and
 * [com.pocketshell.app.tmux.TmuxSessionScreen] (tmux -CC route).
 *
 * Extracted from the original `SessionScreen.kt` (issue #123 follow-up) so
 * the primary user route (host tap → tmux picker → "Attach to session") gets
 * the same dictation / planner UI the raw-SSH route already had. Keeping the
 * helpers in one file means a future glyph or color tweak applies everywhere
 * without skew between the two screens.
 *
 * Everything here is `internal` so it stays an `:app`-module implementation
 * detail; ui-kit reusable components live under `:shared:ui-kit`.
 */

/**
 * Compact one-line error strip surfaced when the inline-dictation FSM
 * reports a permission / API-key / Whisper failure. Tapping the strip
 * dismisses the banner; the next mic tap also clears it via the
 * ViewModel's `clearError()`. Visually distinct from the armed-modifier
 * strip — accent-soft fill with an accent-dim top border, full-width.
 */
@Composable
internal fun InlineDictationErrorStrip(message: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.AccentSoft)
            .border(width = 1.dp, color = PocketShellColors.AccentDim)
            .clickable(onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = message,
            color = PocketShellColors.Accent,
            fontSize = 11.sp,
        )
    }
}

/**
 * Voice command planner review strip — surfaced after a Command-mode
 * dictation transcript is sent to the planner. Renders Insert / Run /
 * Dismiss affordances or the planning/error transient state.
 */
@Composable
internal fun VoiceCommandReviewStrip(
    state: VoiceCommandReviewUiState,
    onInsert: () -> Unit,
    onRun: () -> Unit,
    onDismiss: () -> Unit,
) {
    val plan = state.pendingPlan
    if (!state.isPlanning && state.error == null && plan == null) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            .border(width = 1.dp, color = PocketShellColors.AccentDim)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = when {
                state.isPlanning -> "Planning command..."
                state.error != null -> state.error
                else -> "Review planned command"
            },
            color = if (state.error != null) PocketShellColors.Accent else PocketShellColors.Text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        if (plan != null) {
            Text(
                text = plan.commands.joinToString("\n") { it.command },
                color = PocketShellColors.Text,
                fontSize = 12.sp,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onInsert) {
                    Text("Insert")
                }
                TextButton(onClick = onRun) {
                    Text("Run")
                }
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        } else if (state.error != null) {
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}

/**
 * Scrollable strip of secondary command chips. Only the chips here are
 * inside `horizontalScroll`; the primary right-cluster (`show keyboard`,
 * `+ snippet`) is rendered by [PrimaryChipCluster] outside this scroll
 * region so it stays visible without horizontal-scrolling.
 *
 * Left-to-right:
 * 1. Static command chips passed via [chips] (e.g. `git status`,
 *    `tmux ls`, `k logs`, `clear`) — quick-runs, low tap frequency.
 * 2. `dirs` project-navigation chip — secondary navigation, raw-SSH
 *    route only (rendered when [onProjectNavigationTap] is non-null).
 *
 * Per the #208 right-thumb ergonomics audit and design-system §9, the
 * high-frequency `show keyboard` (#131) and `+ snippet` chips are rendered
 * adjacent to the mic FAB in a non-scrolling sticky cluster (see
 * [PrimaryChipCluster] and [BottomChipControls]) so they sit inside the
 * right-thumb arc on a Pixel-class viewport even when there are enough
 * leading static chips to overflow the scrolling region. Round-1 of #221
 * left the primary chips inside this scrolling row and the connected
 * test caught that `show keyboard` / `+ snippet` were pushed off-screen by
 * the four wide static chips that lead the row — round-2 splits them
 * into the sticky cluster to keep AC2 actually true at the rendered
 * layout layer.
 */
@Composable
private fun ScrollableChipStrip(
    chips: List<String>,
    onChipTap: (String) -> Unit,
    onProjectNavigationTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = modifier
            .horizontalScroll(scrollState)
            .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEach { chip ->
            CommandChip(
                label = chip,
                onClick = { onChipTap(chip) },
            )
        }
        if (onProjectNavigationTap != null) {
            CommandChip(
                label = "dirs",
                onClick = onProjectNavigationTap,
                icon = DictateDotIcon,
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
    }
}

/**
 * Non-scrolling sticky cluster of primary chips, rendered between the
 * scrollable [ScrollableChipStrip] and the mic FAB in [BottomChipControls].
 *
 * The cluster pins `show keyboard` (#131) and `+ snippet` to the right edge of
 * the bottom toolbar regardless of how many static command chips
 * [ScrollableChipStrip] is asked to render. The right-thumb ergonomics
 * goal of design-system §9 only holds if these primary affordances are
 * actually visible next to the FAB without horizontal-scrolling — see the
 * KDoc on [ScrollableChipStrip] for the round-1 regression that motivated
 * splitting them out.
 *
 * Order inside the cluster (left → right): `show keyboard` → `+ snippet`, so
 * `+ snippet` is closest to the mic FAB (matches the §9 worked example
 * `[⌨ show keyboard] [+ snippet]    [🎤 mic FAB]`). Both chips are optional;
 * the cluster collapses to zero width when both callbacks are null
 * (currently the cluster is always non-empty on the tmux + raw-SSH
 * routes, but the optional API keeps the helper composable for callers
 * that wire fewer affordances).
 */
@Composable
private fun PrimaryChipCluster(
    onShowKeyboardTap: (() -> Unit)?,
    onAddSnippetTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (onShowKeyboardTap == null && onAddSnippetTap == null) return
    Row(
        modifier = modifier
            .padding(top = 8.dp, bottom = 8.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onShowKeyboardTap != null) {
            CommandChip(
                label = SHOW_KEYBOARD_CHIP_LABEL,
                onClick = onShowKeyboardTap,
                icon = KeyboardChipIcon,
                modifier = Modifier.testTag(SHOW_KEYBOARD_CHIP_TAG),
            )
        }
        if (onAddSnippetTap != null) {
            CommandChip(
                label = "+ snippet",
                onClick = onAddSnippetTap,
                icon = DictateDotIcon,
                modifier = Modifier.testTag(SESSION_ADD_SNIPPET_CHIP_TAG),
            )
        }
    }
}

/**
 * Bottom chip + mic FAB strip surfaced when the IME is hidden. Both
 * [SessionScreen][com.pocketshell.app.session.SessionScreen] and
 * [TmuxSessionScreen][com.pocketshell.app.tmux.TmuxSessionScreen] mount
 * this as the bottom band of the per-session input controls.
 *
 * Layout (left → right):
 *
 * 1. [ScrollableChipStrip] (`weight(1f)`) — scrollable, holds the
 *    low-frequency static command chips plus optional `dirs`.
 * 2. [PrimaryChipCluster] (sticky, non-scrolling) — `show keyboard` and
 *    `+ snippet` pinned to the right side of the chip area so they sit
 *    inside the right-thumb arc on a Pixel-class viewport regardless of
 *    how many static chips precede them.
 * 3. Mic FAB (sticky, non-scrolling) — single dictate affordance, fixed
 *    width slot on the right edge.
 *
 * The redundant `dictate` chip that used to lead the row was removed
 * per design-system §9 and the right-thumb ergonomics audit
 * (#208 → #221): with the FAB already anchored to the right-thumb arc,
 * the chip row need not duplicate it. Splitting the primary cluster out
 * of the scrolling region fixes the round-1 regression where the four
 * wide leading static chips pushed `show keyboard` / `+ snippet` off-screen
 * (AC2 of #221).
 *
 * `onProjectNavigationTap` is optional because the tmux route does not
 * surface project navigation yet (the raw-SSH `SessionViewModel` owns the
 * `ProjectRootDao`-backed nav state; per-pane navigation through tmux is
 * a follow-up).
 */
@Composable
internal fun BottomChipControls(
    chips: List<String>,
    onChipTap: (String) -> Unit,
    onDictateTap: () -> Unit,
    onShowKeyboardTap: (() -> Unit)? = null,
    onAddSnippetTap: (() -> Unit)? = null,
    onProjectNavigationTap: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface)
            .border(width = 1.dp, color = PocketShellColors.Border),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScrollableChipStrip(
            chips = chips,
            onChipTap = onChipTap,
            onProjectNavigationTap = onProjectNavigationTap,
            modifier = Modifier.weight(1f),
        )
        PrimaryChipCluster(
            onShowKeyboardTap = onShowKeyboardTap,
            onAddSnippetTap = onAddSnippetTap,
        )
        Box(
            modifier = Modifier
                .width(80.dp)
                .padding(end = 12.dp)
                .testTag(SESSION_MIC_FAB_TAG),
            contentAlignment = Alignment.CenterEnd,
        ) {
            MicButton(
                state = MicButtonState.Idle,
                onClick = onDictateTap,
            )
        }
    }
}

/**
 * v1 chip set — matches `docs/mockups/session.html`'s `.chip-row`
 * (without the dictate entry, which the screen renders separately as the
 * mic FAB).
 */
internal val DefaultSessionChips: List<String> = listOf(
    "git status",
    "tmux ls",
    "k logs",
    "clear",
)

internal const val SHOW_KEYBOARD_CHIP_LABEL: String = "show keyboard"

/**
 * A 24x24 microphone glyph used by accent chips and as the
 * inline-dictation mic slot's idle/transcribing fallback. We build
 * the [ImageVector] inline rather than pull in `material-icons-extended`
 * for one glyph — the icon set already in the classpath
 * (`material-icons-core`, transitively from material3) does not ship a
 * standalone `Filled.Mic`. The shape traces Material's filled-mic
 * silhouette: a rounded-rect capsule body (radius 3) centred at x=12, a
 * U-shaped stand cradle just below it, a vertical stem, and a horizontal
 * base bar — readable as a microphone at 14dp through 24dp.
 *
 * Renamed from `DictateDotIcon` (a filled circle) in response to the
 * UI/UX audit (#108) finding that the cyan dot was ambiguous without the
 * adjacent "dictate" caption.
 */
internal val DictateDotIcon: ImageVector = ImageVector.Builder(
    name = "DictateMic",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addMicPath(
    fill = SolidColor(Color.White),
).build()

/**
 * Trace a Material-style filled microphone into this [ImageVector.Builder].
 *
 * The path has three sub-shapes, all rendered as one filled even-odd path:
 * 1. Capsule body: a 6x9 rounded rectangle (corner radius 3) centred at
 *    x=12, spanning y=2..11.
 * 2. Stand cradle: an open arc from (5, 11) → (19, 11) curving downward
 *    through (12, 18), closed back along the top via a thinner inner arc
 *    so the cradle reads as a U rather than a filled bowl.
 * 3. Stem + base: a short vertical stem from (12, 18) to (12, 21) and a
 *    horizontal base bar from (8, 21) to (16, 21).
 *
 * Coordinates are absolute for readability — the icon is small enough
 * that a few extra moveTo / lineTo calls cost nothing at runtime.
 */
private fun ImageVector.Builder.addMicPath(fill: SolidColor): ImageVector.Builder {
    val builder = PathBuilder()

    // Mic body: rounded rectangle, x in [9, 15], y in [2, 11], radius 3.
    builder.moveTo(12f, 2f)
    builder.arcToRelative(3f, 3f, 0f, false, false, -3f, 3f)
    builder.lineToRelative(0f, 6f)
    builder.arcToRelative(3f, 3f, 0f, false, false, 6f, 0f)
    builder.lineToRelative(0f, -6f)
    builder.arcToRelative(3f, 3f, 0f, false, false, -3f, -3f)
    builder.close()

    // Stand cradle (U-shape) — outer arc down, inner arc back up so the
    // result is a 1.5-unit-thick curve, not a filled bowl.
    builder.moveTo(19f, 11f)
    builder.arcToRelative(7f, 7f, 0f, false, true, -14f, 0f)
    builder.lineToRelative(1.5f, 0f)
    builder.arcToRelative(5.5f, 5.5f, 0f, false, false, 11f, 0f)
    builder.close()

    // Stem from cradle bottom (12, 18) down to base bar at y=21.
    builder.moveTo(11.25f, 18f)
    builder.lineToRelative(1.5f, 0f)
    builder.lineToRelative(0f, 3f)
    builder.lineToRelative(-1.5f, 0f)
    builder.close()

    // Base bar centred on x=12.
    builder.moveTo(8f, 20.25f)
    builder.lineToRelative(8f, 0f)
    builder.lineToRelative(0f, 1.5f)
    builder.lineToRelative(-8f, 0f)
    builder.close()

    addPath(pathData = builder.nodes, fill = fill)
    return this
}

/**
 * Issue #131: stable test tag for the show-keyboard chip. Lives next to
 * `INLINE_DICTATION_*` tags so connected tests can reach the chip without
 * relying on its visible label.
 */
internal const val SHOW_KEYBOARD_CHIP_TAG: String = "session:show-keyboard-chip"

/**
 * Issue #221: stable test tag on the bottom-toolbar mic FAB container.
 * The mic FAB is now the only dictate entry point (the redundant
 * `dictate` chip was removed per design-system §9), so connected tests
 * that previously located the dictate affordance via
 * `onNodeWithText("dictate")` route through this tag instead.
 *
 * Placed on the wrapping [Box] rather than the [MicButton] itself so the
 * tag stays attached even if the FAB visual is swapped for a different
 * glyph component later.
 */
internal const val SESSION_MIC_FAB_TAG: String = "session:mic-fab"

/**
 * Issue #221 (round 2): stable test tag on the `+ snippet` chip inside
 * the sticky `PrimaryChipCluster`. The connected
 * `bottomChipControlsRendersMicFabAndPrimaryChips` test asserts the chip
 * is *visible* (not just present in the semantic tree), so it needs a
 * stable identifier independent of the visible caption — same pattern
 * as `SHOW_KEYBOARD_CHIP_TAG`.
 */
internal const val SESSION_ADD_SNIPPET_CHIP_TAG: String = "session:add-snippet-chip"

/**
 * A 24x24 keyboard glyph used as the leading icon on the "show keyboard" chip
 * (issue #131). We hand-trace rather than pull in `material-icons-extended`
 * for one glyph — same rationale as the [DictateDotIcon] mic above; the
 * `material-icons-core` set transitively present via material3 does not
 * ship a stand-alone `Filled.Keyboard`.
 *
 * The shape reads as the standard "keyboard" silhouette: a rounded outer
 * rectangle outline (the keyboard's body) plus seven small filled rounded
 * squares laid out as two key rows above a long bottom space-bar. Sized
 * so each key remains distinguishable at the 14dp render size used by
 * [CommandChip].
 */
internal val KeyboardChipIcon: ImageVector = ImageVector.Builder(
    name = "ShowKeyboard",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addKeyboardPath(
    fill = SolidColor(Color.White),
).build()

/**
 * Trace a Material-style filled keyboard into this [ImageVector.Builder].
 *
 * The path is rendered as one filled even-odd path so the outer-body
 * rectangle (drawn clockwise) and the inner rectangle (drawn
 * counter-clockwise) combine into a hollow outline — the keys then sit
 * inside that hollow as small filled rounded squares.
 *
 * Geometry:
 *
 * - Outer body: rounded rectangle, x in [2, 22], y in [5, 19], corner
 *   radius 2.
 * - Inner cut: rounded rectangle, x in [3.5, 20.5], y in [6.5, 17.5],
 *   corner radius 1. Drawn counter-clockwise to subtract from the outer
 *   rectangle.
 * - Key row 1 (y ≈ 8.5..10): three 2x1.5 keys at x = 5, 9.5, 14.
 * - Key row 2 (y ≈ 11..12.5): three 2x1.5 keys at x = 5, 9.5, 14, with
 *   the rightmost 1.5x1.5 "enter" key at x = 17.5.
 * - Space bar (y ≈ 13.5..15): a 10x1.5 wide rectangle centred on x = 12.
 */
private fun ImageVector.Builder.addKeyboardPath(fill: SolidColor): ImageVector.Builder {
    val builder = PathBuilder()

    // Outer body (clockwise).
    builder.moveTo(4f, 5f)
    builder.lineToRelative(16f, 0f)
    builder.arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
    builder.lineToRelative(0f, 10f)
    builder.arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
    builder.lineToRelative(-16f, 0f)
    builder.arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
    builder.lineToRelative(0f, -10f)
    builder.arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
    builder.close()

    // Inner cut (counter-clockwise) → produces a hollow border via the
    // even-odd fill rule applied when this path is added below.
    builder.moveTo(4.5f, 6.5f)
    builder.arcToRelative(1f, 1f, 0f, false, false, -1f, 1f)
    builder.lineToRelative(0f, 9f)
    builder.arcToRelative(1f, 1f, 0f, false, false, 1f, 1f)
    builder.lineToRelative(15f, 0f)
    builder.arcToRelative(1f, 1f, 0f, false, false, 1f, -1f)
    builder.lineToRelative(0f, -9f)
    builder.arcToRelative(1f, 1f, 0f, false, false, -1f, -1f)
    builder.close()

    // Key row 1.
    for (x in listOf(5f, 9.5f, 14f)) {
        builder.moveTo(x, 8.5f)
        builder.lineToRelative(2f, 0f)
        builder.lineToRelative(0f, 1.5f)
        builder.lineToRelative(-2f, 0f)
        builder.close()
    }

    // Key row 2: three regular keys plus a wider "enter" key.
    for (x in listOf(5f, 9.5f, 14f)) {
        builder.moveTo(x, 11f)
        builder.lineToRelative(2f, 0f)
        builder.lineToRelative(0f, 1.5f)
        builder.lineToRelative(-2f, 0f)
        builder.close()
    }
    // Wider key on the right of row 2.
    builder.moveTo(17.5f, 11f)
    builder.lineToRelative(1.5f, 0f)
    builder.lineToRelative(0f, 1.5f)
    builder.lineToRelative(-1.5f, 0f)
    builder.close()

    // Space bar.
    builder.moveTo(7f, 13.5f)
    builder.lineToRelative(10f, 0f)
    builder.lineToRelative(0f, 1.5f)
    builder.lineToRelative(-10f, 0f)
    builder.close()

    addPath(
        pathData = builder.nodes,
        fill = fill,
        pathFillType = androidx.compose.ui.graphics.PathFillType.EvenOdd,
    )
    return this
}
