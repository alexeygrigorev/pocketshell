package com.pocketshell.app.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
 * The static secondary command chips (`git status`, `tmux ls`, …) and the
 * optional `dirs` project-navigation chip — rendered inline WITHOUT their own
 * scroll container.
 *
 * Issue #813: this content is now hosted inside the single shared
 * horizontally-scrollable flexible region in [BottomChipControls] (alongside the
 * [PrimaryChipCluster]), so the static chips and the primary cluster scroll
 * TOGETHER when space is tight rather than each owning a separate scroll. The
 * launcher is pinned outside that scroll region (unweighted) so it reserves its
 * width first and is never clipped.
 */
@Composable
private fun StaticChipStripContent(
    chips: List<String>,
    onChipTap: (String) -> Unit,
    onProjectNavigationTap: (() -> Unit)? = null,
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
    // Issue #789: compact terminal-hotkeys launcher. The full-width
    // "⌨ Terminal hotkeys" bar (#784) wasted a whole row of vertical space;
    // the launcher is now a compact chip inline in this cluster. Null on
    // surfaces with no raw pane to receive control bytes (e.g. raw-SSH /
    // conversation), so it only renders on the tmux terminal route.
    onShowHotkeysTap: (() -> Unit)? = null,
    hotkeysLauncherTag: String = HOTKEYS_CHIP_TAG,
    enterLabel: String = ENTER_CHIP_LABEL,
    addSnippetLabel: String = ADD_SNIPPET_CHIP_LABEL,
    addSnippetIcon: ImageVector? = SnippetsChipIcon,
    modifier: Modifier = Modifier,
) {
    if (
        onEnterTap == null &&
        onShowKeyboardTap == null &&
        onAddSnippetTap == null &&
        onShowHotkeysTap == null
    ) return
    Row(
        // Issue #813: the primary cluster (`Enter` / `show keyboard` / `hotkeys` /
        // `snippets`) renders at its NATURAL width. It NO LONGER reserves that
        // width ahead of the launcher — [BottomChipControls] now hosts the cluster
        // inside a single horizontally-scrollable, weighted flexible region while
        // the launcher is pinned UNWEIGHTED (so the launcher reserves its width
        // FIRST and is never the element that overflows). When the row is wide
        // enough (Pixel-class width, default font) every cluster chip is fully
        // visible without scrolling; when it is tight (narrow / large system
        // font — the #813 07:53 clip) the cluster yields by scrolling within the
        // flexible region instead of pushing the launcher off the right edge.
        // Issue #787: the `/ commands` chip was hard-cut from this cluster —
        // slash entry now lives only in the composer.
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
        // Issue #789: the compact terminal-hotkeys launcher. Replaces the
        // deleted full-width `TerminalHotkeysLauncherBar` — a single tappable
        // chip opening the same dedicated `TerminalHotkeysSheet` panel. Carries
        // the stable `tmux:hotkeys-launcher` tag so existing tests still find it.
        if (onShowHotkeysTap != null) {
            CommandChip(
                label = HOTKEYS_CHIP_LABEL,
                onClick = onShowHotkeysTap,
                icon = HotkeysChipIcon,
                modifier = Modifier.testTag(hotkeysLauncherTag),
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
 * Layout (left → right), hosted in a [BoxWithConstraints] that reserves the
 * launcher's fixed width before laying out the chip area:
 *
 * 1. The chip area — width-capped at `rowWidth − launcherSlot` so the launcher's
 *    slot is always reserved. Inside it, [StaticChipStripContent] (low-frequency
 *    static command chips plus the optional previous-session toggle and `dirs`)
 *    is the flexible `weight(1f, fill = false)` scrolling strip that YIELDS
 *    first, followed by the [PrimaryChipCluster] (`Enter`, `show keyboard`,
 *    `hotkeys`, picker) at its natural width, pinned to the right of the chip
 *    area in the right-thumb arc (design-system §9 / #208). The whole chip area
 *    also scrolls, so in the extreme narrow / huge-font case the cluster yields
 *    by scrolling rather than clipping.
 * 2. The composer launcher — its fixed-width slot is reserved up front, so it
 *    RESERVES its width FIRST and is never the element pushed off the right edge
 *    (issue #813). Raw SSH keeps the prompt composer affordance; tmux terminal
 *    chrome omits it per #283.
 *
 * The redundant `dictate` chip that used to lead the row was removed
 * per design-system §9 and the right-thumb ergonomics audit
 * (#208 → #221): with the launcher already anchored to the right-thumb arc,
 * the chip row need not duplicate it.
 *
 * Issue #813 (clean rework, not a patch — D22): the prior #641 round-2 layout
 * had the primary cluster AND the launcher both unweighted, with the cluster
 * declared first. A Row measures unweighted children in declaration order, each
 * taking the remaining width, so on a narrow / large-system-font device the
 * cluster took its full (font-inflated) natural width and the LAUNCHER (declared
 * last) was squeezed to zero and clipped off the right edge — the maintainer's
 * 07:53 clip (`snippets`-wraps-to-two-lines tell). The fix reserves the
 * launcher's fixed width up front via the constraints math, so the launcher is
 * never the element that overflows; the chip area absorbs the squeeze by
 * scrolling and every chip stays reachable (scroll, never silently dropped).
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
    // Issue #789: compact terminal-hotkeys launcher chip, rendered inline in the
    // primary chip cluster (replacing the deleted full-width launcher bar).
    onShowHotkeysTap: (() -> Unit)? = null,
    hotkeysLauncherTag: String = HOTKEYS_CHIP_TAG,
    enterLabel: String = ENTER_CHIP_LABEL,
    addSnippetLabel: String = ADD_SNIPPET_CHIP_LABEL,
    addSnippetIcon: ImageVector? = SnippetsChipIcon,
    onProjectNavigationTap: (() -> Unit)? = null,
    // Issue #249: gate the command chips and optional composer launcher on whether
    // the SSH/tmux session is live. While disconnected or reconnecting a
    // chip tap would `writeInputToPane` into a dead bridge — the bytes
    // would be silently dropped. We disable the chips, and when the launcher is
    // present render it in its disabled state so the user
    // sees that input is unavailable rather than losing a tap.
    inputEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Issue #813 (clean rework — D22): the composer launcher RESERVES its
        // width FIRST and is NEVER the element that overflows. We compute the
        // available row width with [BoxWithConstraints] and hand the chip area
        // exactly `rowWidth − launcherWidth`. Priority of yielding, highest →
        // lowest: launcher (always full width) > primary cluster (`Enter` /
        // `show keyboard` / `hotkeys` / `snippets`, stays visible at normal
        // widths in the right-thumb arc) > static command chips (`git status`,
        // `tmux ls`, … — yield + scroll first).
        //
        // Why this is the least-blast-radius fix for the 07:53 clip: the #641
        // round-2 layout had BOTH the cluster and the launcher unweighted, with
        // the cluster declared first. A Compose Row measures unweighted children
        // in declaration order, each getting the remaining width — so the cluster
        // took its full (font-inflated) natural width and the launcher (declared
        // last) was squeezed to zero and clipped off the right edge. Reserving the
        // launcher's fixed width up front (via the constraints math here) keeps the
        // launcher fully on-screen; the chip area absorbs the squeeze by scrolling.
        // On a Pixel-class width with the default font the static chips + cluster
        // fit in the capped area with room to spare (nothing scrolls, every chip
        // is fully visible — the existing #641 assertions stay green). When tight
        // (narrow / large system font), the static strip scrolls first and, only
        // if even an empty static strip can't fit the cluster, the whole chip area
        // scrolls — the cluster yields by scrolling, never silently dropping a chip
        // and never pushing the launcher off-screen.
        val launcherSlotWidth = if (onDictateTap != null) {
            // Launcher tap target + its end padding (matches the Box padding below).
            PocketShellDensity.tapTargetMin + PocketShellSpacing.sm
        } else {
            0.dp
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SessionBottomControlsMinHeight)
                .background(color = PocketShellColors.Surface)
                .border(width = 1.dp, color = PocketShellColors.Border),
        ) {
            val chipAreaMaxWidth = (maxWidth - launcherSlotWidth).coerceAtLeast(0.dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val staticScrollState = rememberScrollState()
                val clusterScrollState = rememberScrollState()
                Row(
                    // The chip area is width-capped at `rowWidth − launcher`, so the
                    // launcher's slot is reserved no matter how wide the chips grow.
                    // No own scroll here — bounded so `weight` inside it works (a
                    // `weight` child needs a bounded width, which a scroll parent
                    // would NOT provide).
                    modifier = Modifier.width(chipAreaMaxWidth),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Static command chips: the low-priority, low-frequency chips.
                    // They yield + scroll FIRST (`weight(1f, fill = false)` lets the
                    // strip shrink below its share and scroll, so the primary cluster
                    // keeps its natural width and stays visible at normal widths).
                    Row(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .horizontalScroll(staticScrollState)
                            .padding(PocketShellSpacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StaticChipStripContent(
                            chips = chips,
                            onChipTap = if (inputEnabled) onChipTap else { _ -> },
                            onProjectNavigationTap = onProjectNavigationTap,
                        )
                    }
                    // Primary cluster: high-frequency, pinned to the right of the
                    // chip area (right-thumb arc, design-system §9 / #208). Natural
                    // width, but wrapped in its OWN scroll bounded to the chip area
                    // (`widthIn(max = chipAreaMaxWidth)`): measured before the
                    // weighted static strip, so it keeps its full width and the
                    // static strip yields to it; in the extreme narrow / huge-font
                    // case where the cluster alone exceeds the chip area it yields by
                    // scrolling WITHIN the cap instead of overflowing into (and
                    // clipping) the launcher's reserved slot. Every chip stays
                    // reachable (#813 AC) and the launcher is never clipped.
                    Row(
                        modifier = Modifier
                            .widthIn(max = chipAreaMaxWidth)
                            .horizontalScroll(clusterScrollState),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PrimaryChipCluster(
                            onEnterTap = onEnterTap?.let { callback ->
                                if (inputEnabled) callback else ({})
                            },
                            onShowKeyboardTap = onShowKeyboardTap,
                            onAddSnippetTap = onAddSnippetTap,
                            // Issue #789: the hotkeys launcher must stay tappable even
                            // while disconnected/reconnecting so the panel can still be
                            // opened (the panel itself gates control-byte writes on the
                            // live pane), matching how `show keyboard` / `snippets` are
                            // not gated here.
                            onShowHotkeysTap = onShowHotkeysTap,
                            hotkeysLauncherTag = hotkeysLauncherTag,
                            enterLabel = enterLabel,
                            addSnippetLabel = addSnippetLabel,
                            addSnippetIcon = addSnippetIcon,
                        )
                    }
                }
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
}

internal val SessionBottomControlsMinHeight = PocketShellDensity.tapTargetMin + 8.dp

/**
 * Issue #786 (Conversation view = transcript + composer only): the Conversation
 * tab's bottom band collapses to JUST the `>_` composer launcher. The maintainer
 * circled the full command bar (the #628 previous-session toggle chip + the
 * snippets `{}` chip) and asked to remove it — "I don't understand where it comes
 * from, what it is, how to use it." Everything the bar offered is reachable
 * elsewhere: the toggle chip's fast session-switch lives on the top breadcrumb,
 * snippets live in the composer's `{}` affordance, slash commands live in the
 * composer (`/` autocomplete, #767/#787). So on the Conversation tab only the
 * launcher remains — right-anchored, no bordered chip row, no static chip strip,
 * no primary cluster. The Terminal tab keeps the full [BottomChipControls].
 *
 * The launcher is identical to the one [BottomChipControls] renders and keeps the
 * same [SESSION_COMPOSER_LAUNCHER_TAG], so the #810 unconditional-launcher proof
 * tests still find it.
 */
@Composable
internal fun ConversationComposerLauncherRow(
    onDictateTap: () -> Unit,
    inputEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SessionBottomControlsMinHeight)
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

/**
 * Issue #789: visible label + stable test tag for the compact terminal-hotkeys
 * launcher chip. The launcher replaces the deleted full-width
 * `TerminalHotkeysLauncherBar` (#784) — it now lives inline in
 * [PrimaryChipCluster] as a single tappable chip that opens the same dedicated
 * `TerminalHotkeysSheet` panel. The tag is an alias of the original launcher tag
 * ([com.pocketshell.app.tmux.TERMINAL_HOTKEYS_LAUNCHER_TAG] = `"tmux:hotkeys-launcher"`)
 * so the existing connected tests that locate the launcher by that tag keep
 * working unchanged.
 */
internal const val HOTKEYS_CHIP_LABEL: String = "hotkeys"
internal const val HOTKEYS_CHIP_TAG: String = "tmux:hotkeys-launcher"

/**
 * Issue #789: a 24x24 "keycap" glyph used as the leading icon on the compact
 * `hotkeys` launcher chip. A rounded-square keycap outline with a small filled
 * inner mark reads as "a key / shortcut" and is visually distinct from the
 * adjacent multi-key [KeyboardChipIcon] on the `show keyboard` chip, so the two
 * neighbouring chips are not confused. Hand-traced for the same reason as
 * [KeyboardChipIcon] — one glyph, no `material-icons-extended` dependency.
 */
internal val HotkeysChipIcon: ImageVector = ImageVector.Builder(
    name = "TerminalHotkeys",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addHotkeysPath(
    fill = SolidColor(Color.White),
).build()

/**
 * Trace a single rounded-square keycap with an inner mark into this
 * [ImageVector.Builder], rendered as one even-odd filled path so the keycap
 * border is a hollow outline and the inner mark is a small filled square.
 *
 * Geometry:
 *  - Outer keycap: rounded rectangle, x in [4, 20], y in [4, 20], corner
 *    radius 3 (clockwise).
 *  - Inner cut: rounded rectangle, x in [6, 18], y in [6, 18], corner radius 2
 *    (counter-clockwise) to hollow the border.
 *  - Centre mark: a 4x4 filled rounded square centred on (12, 12).
 */
private fun ImageVector.Builder.addHotkeysPath(fill: SolidColor): ImageVector.Builder {
    val builder = PathBuilder()

    // Outer keycap (clockwise).
    builder.moveTo(7f, 4f)
    builder.lineToRelative(10f, 0f)
    builder.arcToRelative(3f, 3f, 0f, false, true, 3f, 3f)
    builder.lineToRelative(0f, 10f)
    builder.arcToRelative(3f, 3f, 0f, false, true, -3f, 3f)
    builder.lineToRelative(-10f, 0f)
    builder.arcToRelative(3f, 3f, 0f, false, true, -3f, -3f)
    builder.lineToRelative(0f, -10f)
    builder.arcToRelative(3f, 3f, 0f, false, true, 3f, -3f)
    builder.close()

    // Inner cut (counter-clockwise) → hollow border via even-odd fill.
    builder.moveTo(7f, 6f)
    builder.arcToRelative(1f, 1f, 0f, false, false, -1f, 1f)
    builder.lineToRelative(0f, 10f)
    builder.arcToRelative(1f, 1f, 0f, false, false, 1f, 1f)
    builder.lineToRelative(10f, 0f)
    builder.arcToRelative(1f, 1f, 0f, false, false, 1f, -1f)
    builder.lineToRelative(0f, -10f)
    builder.arcToRelative(1f, 1f, 0f, false, false, -1f, -1f)
    builder.close()

    // Centre mark (a small filled square inside the hollow keycap).
    builder.moveTo(10f, 10f)
    builder.lineToRelative(4f, 0f)
    builder.lineToRelative(0f, 4f)
    builder.lineToRelative(-4f, 0f)
    builder.close()

    addPath(
        pathData = builder.nodes,
        fill = fill,
        pathFillType = androidx.compose.ui.graphics.PathFillType.EvenOdd,
    )
    return this
}
