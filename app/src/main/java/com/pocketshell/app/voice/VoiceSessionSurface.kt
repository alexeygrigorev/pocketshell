package com.pocketshell.app.voice

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import com.pocketshell.app.assistant.AssistantAgentLoop
import com.pocketshell.app.assistant.AssistantUiState
import com.pocketshell.app.assistant.FolderCandidate
import com.pocketshell.app.session.InlineDictationViewModel
import com.pocketshell.uikit.components.Badge
import com.pocketshell.uikit.components.BadgeRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.CommandChip
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.MicButton
import com.pocketshell.uikit.components.MicGlyphIcon
import com.pocketshell.uikit.model.MicButtonState
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Shared voice-surface composables used by
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
 * ViewModel's `clearError()`. It renders as a dense full-width status row
 * using the shared error badge and semantic status colour.
 */
@Composable
internal fun InlineDictationErrorStrip(message: String, onDismiss: () -> Unit) {
    val semantic = LocalPocketShellSemantic.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PocketShellDensity.rowMinHeight)
            .background(color = PocketShellColors.Surface)
            .border(width = 1.dp, color = semantic.statusError)
            .clickable(onClick = onDismiss)
            .padding(
                horizontal = PocketShellDensity.rowPadH,
                vertical = PocketShellDensity.rowPadV,
            ),
        horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Badge(label = "voice", role = BadgeRole.Error, mono = false)
        Text(
            text = message,
            color = semantic.statusError,
            style = PocketShellType.bodyDense,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * In-app action-assistant strip (issue #266). Replaces the deleted
 * `VoiceCommandReviewStrip` (D22 hard cut). Renders the assistant's state:
 *
 *  - Thinking → a transient "Working..." line.
 *  - Confirming → the mutating candidate plus the **confirm-or-correct**
 *    affordances: a "Run" / "Confirm" button, a "No, do something else"
 *    button that reveals a correction field (typed; voice goes through the
 *    same mic and lands via [onCorrect]), and a Cancel button. This is the
 *    confirm-or-correct gate, not a bare yes/no.
 *  - Done / Error → a final message with Dismiss.
 */
@Composable
internal fun AssistantStrip(
    state: AssistantUiState,
    onConfirm: () -> Unit,
    onCorrect: (String) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit = {},
    onChoose: (FolderCandidate) -> Unit = {},
    onCancelChoice: () -> Unit = {},
    correctionDictation: AssistantCorrectionDictation? = null,
) {
    if (state is AssistantUiState.Idle) return

    var correcting by remember(state) { mutableStateOf(false) }
    var correctionText by remember(state) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface, shape = PocketShellShapes.medium)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft, shape = PocketShellShapes.medium)
            .padding(
                horizontal = PocketShellDensity.rowPadH,
                vertical = PocketShellDensity.rowPadV,
            )
            .testTag(ASSISTANT_STRIP_TAG),
        verticalArrangement = Arrangement.spacedBy(PocketShellDensity.sectionGap),
    ) {
        when (state) {
            is AssistantUiState.Thinking -> AssistantStatusRow(
                badge = "working",
                role = BadgeRole.Active,
                text = "Working...",
            )
            is AssistantUiState.Confirming -> {
                AssistantStatusRow(
                    badge = "confirm",
                    text = "Is this what you want me to execute?",
                    role = BadgeRole.Agent,
                )
                AssistantCommandPreview(
                    summary = state.candidate.summary,
                    modifier = Modifier.testTag(ASSISTANT_CANDIDATE_TAG),
                )
                if (correcting) {
                    LaunchedEffect(correctionDictation?.dictatedText?.id) {
                        val event = correctionDictation?.dictatedText ?: return@LaunchedEffect
                        correctionText = appendDictationText(correctionText, event.text)
                        correctionDictation.onDictatedTextConsumed()
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = correctionText,
                            onValueChange = { correctionText = it },
                            modifier = Modifier
                                .weight(1f)
                                .testTag(ASSISTANT_CORRECTION_FIELD_TAG),
                            shape = PocketShellShapes.small,
                            textStyle = PocketShellType.bodyDense,
                            keyboardActions = KeyboardActions(onDone = {
                                onCorrect(correctionText)
                                correcting = false
                                correctionText = ""
                            }),
                        )
                        correctionDictation?.let { dictation ->
                            MicButton(
                                state = dictation.recording.toMicButtonState(),
                                onClick = dictation.onMicTap,
                                modifier = Modifier.testTag(ASSISTANT_CORRECTION_MIC_TAG),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm)) {
                        PocketShellButton(
                            text = "Send correction",
                            onClick = {
                                onCorrect(correctionText)
                                correcting = false
                                correctionText = ""
                            },
                            variant = ButtonVariant.Primary,
                            modifier = Modifier.testTag(ASSISTANT_SEND_CORRECTION_TAG),
                        )
                        PocketShellButton(
                            text = "Back",
                            onClick = { correcting = false },
                            variant = ButtonVariant.Text,
                        )
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PocketShellButton(
                            text = "Run",
                            onClick = onConfirm,
                            variant = ButtonVariant.Primary,
                            modifier = Modifier.testTag(ASSISTANT_CONFIRM_TAG),
                        )
                        PocketShellButton(
                            text = "No, do something else",
                            onClick = { correcting = true },
                            variant = ButtonVariant.Text,
                            modifier = Modifier.testTag(ASSISTANT_CORRECT_TAG),
                        )
                        PocketShellButton(
                            text = "Cancel",
                            onClick = onCancel,
                            variant = ButtonVariant.Text,
                        )
                    }
                }
            }
            is AssistantUiState.Choosing -> {
                AssistantStatusRow(
                    badge = "choose",
                    text = "Which folder did you mean for \"${state.query}\"?",
                    role = BadgeRole.Agent,
                    modifier = Modifier.testTag(ASSISTANT_CHOOSING_TAG),
                )
                state.candidates.forEach { candidate ->
                    PocketShellButton(
                        onClick = { onChoose(candidate) },
                        variant = ButtonVariant.Text,
                        modifier = Modifier.testTag(assistantChoiceTag(candidate.path)),
                    ) {
                        val suffix = if (candidate.sessionCount > 0) " (${candidate.sessionCount} sessions)" else ""
                        Text("${candidate.label} — ${candidate.path}$suffix")
                    }
                }
                PocketShellButton(
                    text = "Cancel",
                    onClick = onCancelChoice,
                    variant = ButtonVariant.Text,
                )
            }
            is AssistantUiState.Done -> {
                AssistantStatusRow(
                    badge = "done",
                    role = BadgeRole.Active,
                    text = state.message,
                )
                PocketShellButton(
                    text = "Dismiss",
                    onClick = onDismiss,
                    variant = ButtonVariant.Text,
                )
            }
            is AssistantUiState.Error -> {
                AssistantStatusRow(
                    badge = "error",
                    role = BadgeRole.Error,
                    text = state.message,
                    color = LocalPocketShellSemantic.current.statusError,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm)) {
                    if (state.retryable) {
                        PocketShellButton(
                            text = "Retry",
                            onClick = onRetry,
                            variant = ButtonVariant.Text,
                            modifier = Modifier.testTag(ASSISTANT_RETRY_TAG),
                        )
                    }
                    PocketShellButton(
                        text = "Dismiss",
                        onClick = onDismiss,
                        variant = ButtonVariant.Text,
                    )
                }
            }
            AssistantUiState.Idle -> Unit
        }
    }
}

@Composable
private fun AssistantStatusRow(
    badge: String,
    role: BadgeRole,
    text: String,
    modifier: Modifier = Modifier,
    color: Color = PocketShellColors.Text,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PocketShellDensity.rowMinHeight),
        horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Badge(label = badge, role = role, mono = false)
        Text(
            text = text,
            color = color,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AssistantCommandPreview(summary: String, modifier: Modifier = Modifier) {
    Text(
        text = summary,
        color = PocketShellColors.Text,
        style = PocketShellType.bodyMono,
        modifier = modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.SurfaceElev, shape = PocketShellShapes.small)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft, shape = PocketShellShapes.small)
            .padding(
                horizontal = PocketShellDensity.rowPadH,
                vertical = PocketShellDensity.chipPadV,
            ),
    )
}

internal data class AssistantCorrectionDictation(
    val recording: InlineDictationViewModel.RecordingState,
    val dictatedText: AssistantDictationTextEvent?,
    val onDictatedTextConsumed: () -> Unit,
    val onMicTap: () -> Unit,
)

internal data class AssistantDictationTextEvent(
    val id: Long,
    val text: String,
)

internal fun appendDictationText(existing: String, dictated: String): String {
    val trimmed = dictated.trim()
    if (trimmed.isEmpty()) return existing
    val base = existing.trimEnd()
    if (base.isEmpty()) return trimmed
    return "$base $trimmed"
}

internal fun InlineDictationViewModel.RecordingState.toMicButtonState(): MicButtonState = when (this) {
    InlineDictationViewModel.RecordingState.Idle -> MicButtonState.Idle
    InlineDictationViewModel.RecordingState.Recording -> MicButtonState.Recording
    InlineDictationViewModel.RecordingState.Transcribing -> MicButtonState.Disabled
}

internal const val ASSISTANT_STRIP_TAG: String = "assistant:strip"
internal const val ASSISTANT_CANDIDATE_TAG: String = "assistant:candidate"
internal const val ASSISTANT_CONFIRM_TAG: String = "assistant:confirm"
internal const val ASSISTANT_CORRECT_TAG: String = "assistant:correct"
internal const val ASSISTANT_CORRECTION_FIELD_TAG: String = "assistant:correction-field"
internal const val ASSISTANT_CORRECTION_MIC_TAG: String = "assistant:correction-mic"
internal const val ASSISTANT_SEND_CORRECTION_TAG: String = "assistant:send-correction"
internal const val ASSISTANT_RETRY_TAG: String = "assistant:retry"
internal const val ASSISTANT_CHOOSING_TAG: String = "assistant:choosing"

internal fun assistantChoiceTag(path: String): String = "assistant:choice:$path"

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
 * adjacent to the composer launcher in a non-scrolling sticky cluster (see
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
    // Issue #628: toggle chip for switching back to the previous session.
    // Rendered at the START of the strip (before command chips) so it is
    // the first thing the user sees in the chip row.
    previousSessionName: String? = null,
    onTogglePreviousSession: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = modifier
            .horizontalScroll(scrollState)
            .padding(PocketShellSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Issue #628: toggle chip at the start of the strip. The "›"
        // prefix signals directionality ("go back to"). Uses the accent
        // style (icon chip) to visually distinguish from command chips.
        if (previousSessionName != null && onTogglePreviousSession != null) {
            CommandChip(
                label = "$SESSION_TOGGLE_CHIP_PREFIX$previousSessionName",
                onClick = onTogglePreviousSession,
                modifier = Modifier.testTag(SESSION_TOGGLE_CHIP_TAG),
            )
        }
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
        Spacer(modifier = Modifier.width(PocketShellSpacing.xs))
    }
}

/**
 * Non-scrolling sticky cluster of primary chips, rendered after the
 * scrollable [ScrollableChipStrip] in [BottomChipControls].
 *
 * The cluster pins `Enter` (#568), `show keyboard` (#131), and the picker chip
 * to the right edge of the bottom toolbar regardless of how many static command
 * chips [ScrollableChipStrip] is asked to render. The right-thumb ergonomics
 * goal of design-system §9 only holds if these primary affordances are
 * actually visible without horizontal-scrolling — see the
 * KDoc on [ScrollableChipStrip] for the round-1 regression that motivated
 * splitting them out.
 *
 * Order inside the cluster (left → right): `Enter` → `show keyboard` → picker.
 * All chips are optional; the cluster collapses to zero width when no callbacks
 * are supplied (currently the cluster is always non-empty on the tmux + raw-SSH
 * routes, but the optional API keeps the helper composable for callers that wire
 * fewer affordances). Issue #787: the former `/ commands` chip was hard-cut —
 * slash entry now lives only in the composer.
 */
@Composable
private fun PrimaryChipCluster(
    onEnterTap: (() -> Unit)?,
    onShowKeyboardTap: (() -> Unit)?,
    onAddSnippetTap: (() -> Unit)?,
    enterLabel: String = ENTER_CHIP_LABEL,
    addSnippetLabel: String = ADD_SNIPPET_CHIP_LABEL,
    addSnippetIcon: ImageVector? = SnippetsChipIcon,
    modifier: Modifier = Modifier,
) {
    if (
        onEnterTap == null &&
        onShowKeyboardTap == null &&
        onAddSnippetTap == null
    ) return
    Row(
        // Issue #641 (reopened): the primary cluster is rendered at its NATURAL
        // width with no horizontal scroll and no width cap. [BottomChipControls]
        // guarantees it (and the pinned launcher) reserve their full width before
        // the flexible static-chip strip, so the cluster never has to compress or
        // clip a chip — every primary affordance (`Enter` / `show keyboard` /
        // `snippets`) is fully visible and tappable. The round-1 internal scroll
        // caused the half-clipped `snippets` chip behind the launcher; removing
        // it (and the cap) is the fix. Issue #787: the `/ commands` chip was
        // hard-cut from this cluster — slash entry now lives only in the composer.
        modifier = modifier
            .padding(
                top = PocketShellSpacing.sm,
                bottom = PocketShellSpacing.sm,
                end = PocketShellSpacing.sm,
            ),
        horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onEnterTap != null) {
            CommandChip(
                label = enterLabel,
                onClick = onEnterTap,
                modifier = Modifier.testTag(SESSION_ENTER_CHIP_TAG),
            )
        }
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
                label = addSnippetLabel,
                onClick = onAddSnippetTap,
                icon = addSnippetIcon,
                modifier = Modifier.testTag(SESSION_ADD_SNIPPET_CHIP_TAG),
            )
        }
    }
}

/**
 * Bottom chip strip surfaced when the IME is hidden.
 * [TmuxSessionScreen][com.pocketshell.app.tmux.TmuxSessionScreen] mounts
 * this as the bottom band of the per-session input controls.
 *
 * Layout (left → right):
 *
 * 1. [ScrollableChipStrip] (`weight(1f)`) — scrollable, holds the
 *    low-frequency static command chips plus optional `dirs`.
 * 2. [PrimaryChipCluster] (sticky, non-scrolling) — `Enter`, `show keyboard`,
 *    and the picker chip pinned to the right side of the chip area so they sit
 *    inside the right-thumb arc on a Pixel-class viewport regardless of
 *    how many static chips precede them.
 * 3. Optional composer launcher (sticky, non-scrolling) — raw SSH still keeps
 *    the prompt composer affordance; tmux terminal chrome omits it per #283.
 *
 * The redundant `dictate` chip that used to lead the row was removed
 * per design-system §9 and the right-thumb ergonomics audit
 * (#208 → #221): with the launcher already anchored to the right-thumb arc,
 * the chip row need not duplicate it. Splitting the primary cluster out
 * of the scrolling region fixes the round-1 regression where the four
 * wide leading static chips pushed `show keyboard` / picker off-screen
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
    onDictateTap: (() -> Unit)?,
    onEnterTap: (() -> Unit)? = null,
    onShowKeyboardTap: (() -> Unit)? = null,
    onAddSnippetTap: (() -> Unit)? = null,
    enterLabel: String = ENTER_CHIP_LABEL,
    addSnippetLabel: String = ADD_SNIPPET_CHIP_LABEL,
    addSnippetIcon: ImageVector? = SnippetsChipIcon,
    onProjectNavigationTap: (() -> Unit)? = null,
    // Issue #628: one-tap toggle to switch back to the previous tmux
    // session. When non-null, a chip with the "› <name>" label is
    // rendered at the start of the scrollable chip strip. Tapping it
    // triggers the existing fast-switch path.
    previousSessionName: String? = null,
    onTogglePreviousSession: (() -> Unit)? = null,
    // Issue #249: gate the command chips and optional composer launcher on whether
    // the SSH/tmux session is live. While disconnected or reconnecting a
    // chip tap would `writeInputToPane` into a dead bridge — the bytes
    // would be silently dropped. We disable the chips, and when the launcher is
    // present render it in its disabled state so the user
    // sees that input is unavailable rather than losing a tap.
    inputEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // Issue #628: one-time hint logic. The hint appears the first time
    // `previousSessionName` becomes non-null and the user hasn't dismissed
    // it yet. It auto-dismisses after 5 seconds or on tap, and records the
    // dismissal in SharedPreferences so it never shows again.
    val context = LocalContext.current
    var hintDismissed by remember {
        mutableStateOf(
            context.getSharedPreferences(PREFS_SESSION_TOGGLE, Context.MODE_PRIVATE)
                .getBoolean(PREF_SESSION_TOGGLE_HINT_DISMISSED, false)
        )
    }
    val showHint = previousSessionName != null && !hintDismissed

    if (showHint) {
        LaunchedEffect(Unit) {
            delay(SESSION_TOGGLE_HINT_DURATION_MS)
            hintDismissed = true
            context.getSharedPreferences(PREFS_SESSION_TOGGLE, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_SESSION_TOGGLE_HINT_DISMISSED, true)
                .apply()
        }
    }

    Column(modifier = modifier) {
        // Issue #628: inline hint banner above the chip row.
        AnimatedVisibility(
            visible = showHint,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            val hintName = previousSessionName ?: ""
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color = PocketShellColors.SurfaceElev)
                    .clickable {
                        hintDismissed = true
                        context.getSharedPreferences(PREFS_SESSION_TOGGLE, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(PREF_SESSION_TOGGLE_HINT_DISMISSED, true)
                            .apply()
                    }
                    .testTag(SESSION_TOGGLE_HINT_TAG)
                    .padding(
                        horizontal = PocketShellSpacing.md,
                        vertical = PocketShellSpacing.sm,
                    ),
            ) {
                Text(
                    text = "Tap ‹ $hintName to flip back",
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.bodyDense,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SessionBottomControlsMinHeight)
                .background(color = PocketShellColors.Surface)
                .border(width = 1.dp, color = PocketShellColors.Border),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Issue #641 (reopened): priority of the bottom-control band, from
            // most to least important — the LAUNCHER and the PRIMARY CLUSTER
            // (`Enter` / `show keyboard` / `snippets`) must be FULLY visible and
            // tappable; the low-frequency static command chips (`git status`,
            // `tmux ls`, …) are the ones that yield + scroll.
            //
            // Round 1 capped the primary cluster at `rowWidth − launcher` and let
            // it scroll within that cap. That stopped the launcher being clipped,
            // but introduced the *reopened* symptom: in the multi-chip dogfood
            // state the rightmost cluster chip (`snippets`) was left
            // HALF-CLIPPED at the cap boundary — sitting partly behind/under the
            // launcher, so the maintainer saw an unidentifiable control "hidden
            // behind the compose button".
            //
            // The fix inverts which side yields. The launcher (pinned last,
            // unweighted) and the primary cluster (unweighted, natural width, NO
            // internal scroll, NO cap) both reserve their full width FIRST; the
            // static-chip strip is the only flexible child and absorbs all the
            // squeeze by scrolling. On a Pixel-class width the 4-chip cluster +
            // launcher fit with room to spare, so nothing in the cluster is ever
            // partially clipped. `fill = false` on the strip's weight lets it
            // shrink below its weighted share when its own content is narrow, so
            // it never forces the cluster to give up width.
            ScrollableChipStrip(
                chips = chips,
                onChipTap = if (inputEnabled) onChipTap else { _ -> },
                onProjectNavigationTap = onProjectNavigationTap,
                // Issue #628: pass the toggle chip through so it renders at the
                // START of the row, before the command chips.
                previousSessionName = previousSessionName,
                onTogglePreviousSession = onTogglePreviousSession,
                modifier = Modifier.weight(1f, fill = false),
            )
            PrimaryChipCluster(
                onEnterTap = onEnterTap?.let { callback ->
                    if (inputEnabled) callback else ({})
                },
                onShowKeyboardTap = onShowKeyboardTap,
                onAddSnippetTap = onAddSnippetTap,
                enterLabel = enterLabel,
                addSnippetLabel = addSnippetLabel,
                addSnippetIcon = addSnippetIcon,
            )
            if (onDictateTap != null) {
                Box(
                    modifier = Modifier
                        .padding(
                            top = PocketShellSpacing.sm,
                            bottom = PocketShellSpacing.sm,
                            end = PocketShellSpacing.sm,
                        ),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    ComposerLauncherButton(
                        enabled = inputEnabled,
                        onClick = onDictateTap,
                    )
                }
            }
        }
    }
}

internal val SessionBottomControlsMinHeight = PocketShellDensity.tapTargetMin + 8.dp

@Composable
private fun ComposerLauncherButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (enabled) PocketShellColors.SurfaceElev else PocketShellColors.Surface
    val borderColor = if (enabled) PocketShellColors.AccentDim else PocketShellColors.BorderSoft
    val glyphColor = if (enabled) PocketShellColors.Accent else PocketShellColors.TextMuted
    val shape = PocketShellShapes.small

    Box(
        modifier = modifier
            .size(PocketShellDensity.tapTargetMin)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = SESSION_COMPOSER_LAUNCHER_CONTENT_DESCRIPTION }
            .testTag(SESSION_COMPOSER_LAUNCHER_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(PocketShellDensity.tapTargetMin - PocketShellSpacing.md)
                .background(color = containerColor, shape = shape)
                .border(width = 1.dp, color = borderColor, shape = shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = ComposerLauncherIcon,
                contentDescription = null,
                tint = glyphColor,
                modifier = Modifier.size(PocketShellDensity.treeIndent),
            )
        }
    }
}

/**
 * v1 chip set — matches `docs/mockups/session.html`'s `.chip-row`
 * (without the composer launcher, which the screen renders separately).
 */
internal val DefaultSessionChips: List<String> = listOf(
    "git status",
    "tmux ls",
    "k logs",
    "clear",
)

internal const val SHOW_KEYBOARD_CHIP_LABEL: String = "show keyboard"
internal const val ENTER_CHIP_LABEL: String = "Enter"

// Issue #628: one-tap toggle chip for switching back to the previous
// tmux session. The "›" prefix signals directionality ("go back to").
internal const val SESSION_TOGGLE_CHIP_PREFIX: String = "› "
internal const val SESSION_TOGGLE_CHIP_TAG: String = "session:toggle-previous"

// Issue #628: one-time hint shown on first use of the toggle chip.
// After the user sees it once (or taps it), it is permanently dismissed
// via SharedPreferences so it never appears again.
internal const val SESSION_TOGGLE_HINT_TAG: String = "session:toggle-previous-hint"
internal const val PREF_SESSION_TOGGLE_HINT_DISMISSED: String = "pref_session_toggle_hint_dismissed"
private const val PREFS_SESSION_TOGGLE: String = "pocketshell_session_toggle"
private const val SESSION_TOGGLE_HINT_DURATION_MS: Long = 5_000L

// Issue #454: the saved-snippet picker chip. The old `+ snippet` / `+ prompt`
// / `+ command` labels were unclear — the leading `+` read as "add a NEW
// command/prompt" when the chip actually OPENS the saved-snippet picker to
// insert an existing one, and the command-vs-prompt split duplicated the
// confusion the maintainer flagged on the bottom bar. All three now render the
// single, legible `snippets` label with a list glyph so the affordance reads as
// "open my saved snippets". The three constants are kept as distinct identifiers
// only so callers/tests can still name the raw-SSH, agent-prompt, and
// shell-command picker entry points; their visible text is identical.
internal const val ADD_SNIPPET_CHIP_LABEL: String = "snippets"
internal const val ADD_PROMPT_CHIP_LABEL: String = "snippets"
internal const val ADD_COMMAND_CHIP_LABEL: String = "snippets"

/**
 * A 24x24 microphone glyph used by accent chips and as the
 * inline-dictation mic slot's idle/transcribing fallback, and by the
 * composer sheet's mic trigger.
 *
 * Issue #453: this is now an alias of the shared
 * [com.pocketshell.uikit.components.MicGlyphIcon] in `:shared:ui-kit`, so
 * the session-band [com.pocketshell.uikit.components.MicButton] and every
 * app-side mic affordance render the exact same microphone path — one
 * shared source of truth, no skew, and no leftover `●` dot anywhere.
 *
 * Renamed from a filled circle to a microphone in response to the UI/UX
 * audit (#108) finding that the cyan dot was ambiguous without the
 * adjacent "dictate" caption.
 */
internal val DictateDotIcon: ImageVector = MicGlyphIcon

/**
 * Issue #454: a 24x24 "list" glyph used as the leading icon on the saved-snippet
 * picker chip (`snippets`). The old snippet chip leaned on the mic
 * [DictateDotIcon], which read as "dictate" and reinforced the maintainer's
 * "what does this chip do?" confusion. A short stack of horizontal lines reads
 * as "a saved list", so the chip clearly means "open my saved snippets" rather
 * than "add a new command". Hand-traced for the same reason as
 * [KeyboardChipIcon] — one glyph, no `material-icons-extended` dependency.
 *
 * Geometry: three rounded horizontal bars at y ≈ 7, 11.5, 16, each 12 wide and
 * 2 tall, left-aligned at x = 6, with a small leading dot at x = 3 on each row
 * (the classic bulleted-list silhouette).
 */
internal val SnippetsChipIcon: ImageVector = ImageVector.Builder(
    name = "Snippets",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addSnippetsPath(
    fill = SolidColor(Color.White),
).build()

private fun ImageVector.Builder.addSnippetsPath(fill: SolidColor): ImageVector.Builder {
    val builder = PathBuilder()
    for (y in listOf(6f, 10.5f, 15f)) {
        // Leading bullet dot.
        builder.moveTo(3f, y)
        builder.lineToRelative(2f, 0f)
        builder.lineToRelative(0f, 2f)
        builder.lineToRelative(-2f, 0f)
        builder.close()
        // Row bar.
        builder.moveTo(7f, y)
        builder.lineToRelative(14f, 0f)
        builder.lineToRelative(0f, 2f)
        builder.lineToRelative(-14f, 0f)
        builder.close()
    }
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
 * Issue #568/#584: stable tag for the standalone hidden-keyboard Enter chip.
 * The full terminal key bar is keyboard-up only; this chip keeps Enter
 * reachable next to `show keyboard` and the mic when the IME is hidden.
 */
internal const val SESSION_ENTER_CHIP_TAG: String = "session:enter-chip"

/**
 * Issue #610: stable test tag on the bottom-toolbar Prompt Composer launcher
 * container. This entry opens the full composer, where dictation is one
 * available action, so its semantics deliberately avoid microphone wording.
 *
 * Placed on the wrapping [Box] rather than the button itself so the tag stays
 * attached even if the launcher visual is swapped for a different component.
 */
internal const val SESSION_COMPOSER_LAUNCHER_TAG: String = "session:composer-launcher"

internal const val SESSION_COMPOSER_LAUNCHER_CONTENT_DESCRIPTION: String = "Open prompt composer"

internal const val SESSION_MIC_FAB_TAG: String = SESSION_COMPOSER_LAUNCHER_TAG

/**
 * Issue #612: the bottom composer-launcher glyph. Tapping this control opens
 * the full Prompt Composer (text + voice + attachments). Per the maintainer's
 * explicit "use a **D-derived** composer/prompt-helper variant, not a
 * microphone" instruction, this glyph is now **brand-aligned with the app
 * icon**: it reuses the same `>_` shell-prompt motif (a bold chevron `>` plus
 * a cursor block `_`) that the launcher icon ("C1" mark) uses, so the composer
 * entry visibly rhymes with the brand mark.
 *
 * A short earlier round used a generic edit/compose pencil for legibility, but
 * that diverged from the chosen "Pocket Terminal" direction; this replaces it
 * outright (hard cut, D22 — the pencil is removed, not kept as an option).
 * The `>_` reads as "open a prompt at a terminal", which is exactly what the
 * composer is. Microphone glyphs ([DictateDotIcon]) stay reserved for real
 * recording controls inside the composer.
 *
 * Drawn for the small toolbar size: the chevron and cursor are kept bold,
 * widely spaced, and stroke-free (filled polygons) so they stay distinct and
 * never thin out when scaled down inside the launcher button. The artwork sits
 * inside roughly x=5.5..18.5 / y=5.5..18.5 of the 24dp viewport so the rounded
 * button leaves visible breathing room on all sides.
 */
internal val ComposerLauncherIcon: ImageVector = ImageVector.Builder(
    name = "ComposerLauncher",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addComposerLauncherPath(
    fill = SolidColor(Color.White),
).build()

private fun ImageVector.Builder.addComposerLauncherPath(fill: SolidColor): ImageVector.Builder {
    val builder = PathBuilder()

    // Prompt chevron `>` — the same bold filled polygon as the app icon's
    // "C1" mark, scaled into the toolbar viewport. Left-weighted so the
    // cursor block has room on the right.
    builder.moveTo(6f, 6.5f)
    builder.lineTo(8.6f, 6.5f)
    builder.lineTo(13.6f, 12f)
    builder.lineTo(8.6f, 17.5f)
    builder.lineTo(6f, 17.5f)
    builder.lineTo(11f, 12f)
    builder.close()

    // Cursor block `_` — a short, thick baseline bar at the lower-right,
    // echoing the launcher icon's cursor underscore so the mark reads as a
    // live shell prompt rather than a static arrow.
    builder.moveTo(13.5f, 15.5f)
    builder.lineTo(18.5f, 15.5f)
    builder.lineTo(18.5f, 17.5f)
    builder.lineTo(13.5f, 17.5f)
    builder.close()

    addPath(pathData = builder.nodes, fill = fill)
    return this
}

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
