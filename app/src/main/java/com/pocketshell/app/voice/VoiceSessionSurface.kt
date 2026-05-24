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
 * Always-visible chip row (only when the IME is hidden, per
 * `docs/input-methods.md` §"Screen real estate"). The first chip is the
 * `dictate` icon chip — tapping it opens the prompt composer; the
 * rest write their literal text + `\n` into the terminal.
 */
@Composable
private fun ChipRow(
    chips: List<String>,
    onChipTap: (String) -> Unit,
    onDictateTap: () -> Unit,
    onAddSnippetTap: (() -> Unit)? = null,
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
        CommandChip(
            label = "dictate",
            onClick = onDictateTap,
            icon = DictateDotIcon,
        )
        if (onProjectNavigationTap != null) {
            CommandChip(
                label = "dirs",
                onClick = onProjectNavigationTap,
                icon = DictateDotIcon,
            )
        }
        if (onAddSnippetTap != null) {
            CommandChip(
                label = "+ snippet",
                onClick = onAddSnippetTap,
                icon = DictateDotIcon,
            )
        }
        chips.forEach { chip ->
            CommandChip(
                label = chip,
                onClick = { onChipTap(chip) },
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
    }
}

/**
 * Bottom chip + mic FAB strip surfaced when the IME is hidden. Both
 * [SessionScreen][com.pocketshell.app.session.SessionScreen] and
 * [TmuxSessionScreen][com.pocketshell.app.tmux.TmuxSessionScreen] mount
 * this as the bottom band of the per-session input controls. The mic FAB
 * itself routes to the same `onDictateTap` callback as the dictate chip
 * so a user dictating from a tmux pane gets the same prompt composer as
 * the raw-SSH route.
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
        ChipRow(
            chips = chips,
            onChipTap = onChipTap,
            onDictateTap = onDictateTap,
            onAddSnippetTap = onAddSnippetTap,
            onProjectNavigationTap = onProjectNavigationTap,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .width(80.dp)
                .padding(end = 12.dp),
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
 * icon chip).
 */
internal val DefaultSessionChips: List<String> = listOf(
    "git status",
    "tmux ls",
    "k logs",
    "clear",
)

/**
 * A 24x24 microphone glyph used as the dictate chip's leading icon and as
 * the inline-dictation mic slot's idle/transcribing fallback. We build
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
