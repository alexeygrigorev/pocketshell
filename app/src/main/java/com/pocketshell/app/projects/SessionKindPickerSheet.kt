package com.pocketshell.app.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Epic #821 Slice 1 — the "classify this session" picker.
 *
 * Two callers, ONE picker (maintainer's Option B + change-existing decision):
 *
 *  1. **Unknown → pick.** A foreign session with no recorded `@ps_agent_kind`
 *     ([SessionAgentKind.Unknown]) — we do NOT guess what it is. The sheet
 *     opens with the "we don't know this session — choose" prompt and lists the
 *     classifiable kinds (Claude / Codex / OpenCode / Shell). On pick, the
 *     caller writes `@ps_agent_kind` host-side via `ManualKindWriter` so the
 *     choice becomes the durable recorded kind.
 *  2. **Change kind.** The same picker, opened from a session's menu for ANY
 *     session (already-classified included). It rewrites `@ps_agent_kind`.
 *
 * ## Picker API (forward-compatible with Option A)
 *
 * [suggestedKind] is an OPTIONAL pre-selected/suggested kind. It is `null`
 * today (Option B — no guess). A follow-up (Option A) will pass a cgroup-based
 * guess and the sheet will simply PRE-HIGHLIGHT that option — zero rework: the
 * param already drives the initial selection, so wiring the guess in later only
 * means passing a non-null value here.
 *
 * [currentKind] is the session's current recorded kind for the change-kind
 * flow (so the user sees what it is now); `null`/[SessionAgentKind.Unknown] for
 * the unknown flow. It pre-selects when [suggestedKind] is absent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionKindPickerSheet(
    sessionName: String,
    onDismiss: () -> Unit,
    onPick: (SessionAgentKind) -> Unit,
    /** Foreign/unknown flow when true (changes the prompt copy). */
    isUnknown: Boolean,
    /** The session's current recorded kind (change-kind flow). */
    currentKind: SessionAgentKind? = null,
    /**
     * OPTIONAL suggested kind to pre-highlight. `null` today (Option B); a
     * follow-up (Option A) passes a cgroup guess here with no other change.
     */
    suggestedKind: SessionAgentKind? = null,
    /**
     * Issue #858: the session's recorded NON-default profile label (e.g.
     * `"Claude (Z.AI)"`), read back from `@ps_agent_profile`. When non-null,
     * the sheet shows a "Provider/profile" line so the user can tell a z.ai
     * Claude apart from a default Claude. `null` for a default / non-profiled
     * / legacy session — no line shown.
     */
    currentProfile: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        modifier = Modifier.testTag(SESSION_KIND_PICKER_SHEET_TAG),
    ) {
        SessionKindPickerContent(
            sessionName = sessionName,
            onCancel = onDismiss,
            onPick = onPick,
            isUnknown = isUnknown,
            currentKind = currentKind,
            suggestedKind = suggestedKind,
            currentProfile = currentProfile,
        )
    }
}

/**
 * Pure content of the picker — split out from the [ModalBottomSheet] wrapper so
 * Compose tests can drive the body without the sheet animation harness.
 */
@Composable
internal fun SessionKindPickerContent(
    sessionName: String,
    onCancel: () -> Unit,
    onPick: (SessionAgentKind) -> Unit,
    isUnknown: Boolean,
    currentKind: SessionAgentKind? = null,
    suggestedKind: SessionAgentKind? = null,
    currentProfile: String? = null,
) {
    // Initial selection precedence: an explicit suggestion (Option A, future)
    // wins; otherwise pre-select the session's current recorded kind so the
    // change-kind flow opens on the current value; the unknown flow with no
    // suggestion starts with nothing selected (the user must choose).
    val initial: SessionAgentKind? = suggestedKind
        ?: currentKind?.takeIf { it in SessionAgentKind.pickable }
    var selected by remember { mutableStateOf(initial) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .testTag(SESSION_KIND_PICKER_CONTENT_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PocketShellSpacing.lg)
                .padding(top = PocketShellSpacing.lg, bottom = PocketShellSpacing.md),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
        ) {
            Text(
                text = if (isUnknown) {
                    "We don't know what this session is"
                } else {
                    "Change session kind"
                },
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag(SESSION_KIND_PICKER_TITLE_TAG),
            )
            Text(
                text = if (isUnknown) {
                    "Choose what \"$sessionName\" is running. We'll remember it."
                } else {
                    "Set what \"$sessionName\" is running."
                },
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyMono,
            )

            // Issue #858: surface the recorded NON-default profile/provider so
            // the user can tell a z.ai Claude apart from a default Claude. Only
            // shown when a profile was recorded; a default / legacy session has
            // no line (the plain kind only).
            currentProfile?.trim()?.takeIf { it.isNotEmpty() }?.let { profile ->
                Text(
                    text = "Provider/profile: $profile",
                    color = PocketShellColors.Text,
                    style = PocketShellType.bodyMono,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag(SESSION_KIND_PICKER_PROFILE_TAG),
                )
            }

            SessionAgentKind.pickable.forEach { kind ->
                SessionKindRow(
                    kind = kind,
                    selected = selected == kind,
                    suggested = suggestedKind == kind,
                    onSelect = { selected = kind },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PocketShellColors.Surface)
                .border(width = 1.dp, color = PocketShellColors.BorderSoft)
                .padding(horizontal = PocketShellSpacing.lg, vertical = PocketShellSpacing.md),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PocketShellButton(
                text = "Cancel",
                onClick = onCancel,
                variant = ButtonVariant.Text,
                modifier = Modifier.testTag(SESSION_KIND_PICKER_CANCEL_TAG),
            )
            Spacer(modifier = Modifier.padding(end = 8.dp))
            PocketShellButton(
                text = "Save",
                onClick = { selected?.let(onPick) },
                variant = ButtonVariant.Primary,
                enabled = selected != null,
                modifier = Modifier.testTag(SESSION_KIND_PICKER_SAVE_TAG),
            )
        }
    }
}

@Composable
private fun SessionKindRow(
    kind: SessionAgentKind,
    selected: Boolean,
    suggested: Boolean,
    onSelect: () -> Unit,
) {
    ListRow(
        title = sessionKindPickerLabel(kind),
        subtitle = if (suggested) "Suggested" else null,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(sessionKindPickerOptionTag(kind)),
        leading = {
            RadioButton(
                selected = selected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(
                    selectedColor = PocketShellColors.Accent,
                    unselectedColor = PocketShellColors.TextSecondary,
                ),
            )
        },
        onClick = onSelect,
    )
}

/** Human-readable label for a pickable session kind. */
internal fun sessionKindPickerLabel(kind: SessionAgentKind): String = when (kind) {
    SessionAgentKind.Claude -> "Claude"
    SessionAgentKind.Codex -> "Codex"
    SessionAgentKind.OpenCode -> "OpenCode"
    SessionAgentKind.Shell -> "Shell"
    // Not user-pickable, but keep the `when` exhaustive.
    SessionAgentKind.Probing,
    SessionAgentKind.Exited,
    SessionAgentKind.Unknown,
    -> kind.name
}

// Test tags exposed for unit / connected tests.
const val SESSION_KIND_PICKER_SHEET_TAG: String = "session-kind-picker:sheet"
const val SESSION_KIND_PICKER_CONTENT_TAG: String = "session-kind-picker:content"
const val SESSION_KIND_PICKER_TITLE_TAG: String = "session-kind-picker:title"
/** Issue #858: tags the recorded provider/profile line in the picker. */
const val SESSION_KIND_PICKER_PROFILE_TAG: String = "session-kind-picker:profile"
const val SESSION_KIND_PICKER_CANCEL_TAG: String = "session-kind-picker:cancel"
const val SESSION_KIND_PICKER_SAVE_TAG: String = "session-kind-picker:save"

/** Stable per-option test tag, e.g. `session-kind-picker:option:Claude`. */
fun sessionKindPickerOptionTag(kind: SessionAgentKind): String =
    "session-kind-picker:option:${kind.name}"
