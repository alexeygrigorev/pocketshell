package com.pocketshell.app.voice

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/** False while [ReservedTmuxTerminalBottomBand] is measuring an unplaced copy. */
internal val LocalVoiceGestureBandPlaced: ProvidableCompositionLocal<Boolean> =
    compositionLocalOf { true }

/** Callback installed by the coachmark host and fired by the real launcher. */
internal val LocalVoiceGestureLauncherPlaced: ProvidableCompositionLocal<() -> Unit> =
    compositionLocalOf { {} }

/** Callback fired when the launcher is activated through any user-facing path. */
internal val LocalVoiceGestureLauncherActivated: ProvidableCompositionLocal<() -> Unit> =
    compositionLocalOf { {} }

internal const val VOICE_GESTURE_COACHMARK_TAG: String =
    "session:voice-gesture-coachmark"
internal const val VOICE_GESTURE_COACHMARK_DISMISS_TAG: String =
    "session:voice-gesture-coachmark-dismiss"
internal const val VOICE_GESTURE_COACHMARK_COPY: String =
    "Tap to compose · swipe up to dictate"
internal const val VOICE_GESTURE_DICTATION_ACTION_LABEL: String = "Start dictation"
internal const val VOICE_GESTURE_COACHMARK_DISMISS_LABEL: String = "Dismiss voice hint"

private const val VOICE_GESTURE_COACHMARK_LOG_TAG = "Issue1753Coachmark"

/**
 * Adds the one-time lesson above an existing docked launcher band. The content
 * remains the source of truth for launcher geometry; this wrapper only adds a
 * separate vertical row, so it cannot squeeze the #813 horizontal reservation.
 * The coachmark's measured end is kept on the same end padding as the real
 * launcher slot, which makes the relationship explicit in both layout and
 * viewport proofs.
 */
@Composable
internal fun VoiceGestureCoachmarkHost(
    eligible: Boolean,
    launcherEnabled: Boolean,
    modifier: Modifier = Modifier,
    viewModelOverride: VoiceGestureCoachmarkViewModel? = null,
    content: @Composable () -> Unit,
) {
    val viewModel = rememberVoiceGestureCoachmarkViewModel(viewModelOverride)
    val controller = viewModel.controller
    val dismissLauncherCoachmark = remember(controller) { { controller.dismiss() } }
    val state by controller.uiState.collectAsState()
    val bandPlaced = LocalVoiceGestureBandPlaced.current
    val effectivelyEligible = eligible && launcherEnabled && bandPlaced

    var launcherPlaced by remember { mutableStateOf(false) }
    var coachmarkPlaced by remember { mutableStateOf(false) }
    var claimAttemptedForEligibility by remember { mutableStateOf(false) }

    LaunchedEffect(
        eligible,
        launcherEnabled,
        bandPlaced,
        effectivelyEligible,
        state,
        launcherPlaced,
        coachmarkPlaced,
        claimAttemptedForEligibility,
    ) {
        Log.i(
            VOICE_GESTURE_COACHMARK_LOG_TAG,
            "host eligible=$eligible launcherEnabled=$launcherEnabled " +
                "bandPlaced=$bandPlaced effectivelyEligible=$effectivelyEligible " +
                "state=${state::class.simpleName} launcherPlaced=$launcherPlaced " +
                "coachmarkPlaced=$coachmarkPlaced claimAttempted=$claimAttemptedForEligibility",
        )
    }

    LaunchedEffect(effectivelyEligible, state) {
        if (effectivelyEligible) {
            if (state is VoiceGestureCoachmarkUiState.Ready &&
                !claimAttemptedForEligibility
            ) {
                // A failed durable write returns the controller to Ready. Do
                // not immediately reclaim from that same mounted host: a
                // permanently unwritable prefs file would otherwise create a
                // frame/IO retry loop. A later eligibility edge (or a new
                // Activity/ViewModel) is the bounded retry point.
                claimAttemptedForEligibility = controller.tryClaim() != null
            }
        } else {
            when (val current = state) {
                is VoiceGestureCoachmarkUiState.Claimed ->
                    controller.release(current.claimId)
                is VoiceGestureCoachmarkUiState.Persisting ->
                    controller.release(current.claimId)
                is VoiceGestureCoachmarkUiState.Presented ->
                    controller.release(current.claimId)
                else -> Unit
            }
        }
    }

    LaunchedEffect(effectivelyEligible, bandPlaced) {
        if (!bandPlaced) {
            launcherPlaced = false
            coachmarkPlaced = false
            claimAttemptedForEligibility = false
        } else if (!effectivelyEligible) {
            // The launcher can remain physically placed while temporarily
            // disabled (for example during a reconnect). Preserve that
            // placement fact so re-enabling it can present the coachmark even
            // when its bounds did not change enough to re-fire
            // onGloballyPositioned. The coachmark itself is unmounted, so its
            // placement latch and claim attempt must be reset.
            coachmarkPlaced = false
            claimAttemptedForEligibility = false
        }
    }

    val claimId = when (val current = state) {
        is VoiceGestureCoachmarkUiState.Claimed -> current.claimId
        is VoiceGestureCoachmarkUiState.Persisting -> current.claimId
        is VoiceGestureCoachmarkUiState.Presented -> current.claimId
        else -> null
    }

    DisposableEffect(claimId) {
        onDispose {
            claimId?.let(controller::release)
        }
    }

    LaunchedEffect(claimId, state, effectivelyEligible, launcherPlaced, coachmarkPlaced) {
        if (claimId != null &&
            state is VoiceGestureCoachmarkUiState.Claimed &&
            effectivelyEligible &&
            launcherPlaced &&
            coachmarkPlaced
        ) {
            // Placement is observable before this effect runs. Waiting one
            // frame ensures the user-facing frame, not merely composition, was
            // presented. There is deliberately no wall-clock timer.
            withFrameNanos { }
            controller.markPresented(claimId)
        }
    }

    val showing = effectivelyEligible && when (val current = state) {
        is VoiceGestureCoachmarkUiState.Claimed -> true
        is VoiceGestureCoachmarkUiState.Persisting -> current.showWhenCommitted
        is VoiceGestureCoachmarkUiState.Presented -> true
        else -> false
    }

    Column(modifier = modifier) {
        if (showing) {
            VoiceGestureCoachmark(
                onDismiss = controller::dismiss,
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    coachmarkPlaced = bandPlaced &&
                        coordinates.isAttached &&
                        coordinates.size.width > 0 &&
                        coordinates.size.height > 0
                },
            )
        }
        CompositionLocalProvider(
            LocalVoiceGestureLauncherPlaced provides {
                if (bandPlaced) launcherPlaced = true
            },
            LocalVoiceGestureLauncherActivated provides dismissLauncherCoachmark,
        ) {
            content()
        }
    }
}

@Composable
internal fun VoiceGestureCoachmark(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalPocketShellSemantic.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            // The production launcher is end-padded by the same `sm` slot in
            // both docked rows. Applying that inset to this parent makes the
            // tagged surface's right edge the launcher's right edge.
            .padding(end = PocketShellSpacing.sm, bottom = PocketShellSpacing.xs),
    ) {
        Surface(
            shape = PocketShellShapes.small,
            color = semantic.accentSoft,
            border = BorderStroke(1.dp, semantic.accentDim),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = PocketShellSpacing.lg)
                .testTag(VOICE_GESTURE_COACHMARK_TAG)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = VOICE_GESTURE_COACHMARK_COPY
                },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = VOICE_GESTURE_COACHMARK_COPY,
                    style = PocketShellType.bodyDense,
                    color = semantic.accent,
                    modifier = Modifier
                        .weight(1f)
                        .padding(
                            start = PocketShellSpacing.sm,
                            top = PocketShellSpacing.sm,
                            bottom = PocketShellSpacing.sm,
                        ),
                )
                Box(
                    modifier = Modifier
                        .size(PocketShellDensity.tapTargetMin)
                        .testTag(VOICE_GESTURE_COACHMARK_DISMISS_TAG)
                        .semantics {
                            role = Role.Button
                            contentDescription = VOICE_GESTURE_COACHMARK_DISMISS_LABEL
                        }
                        .clickable(
                            role = Role.Button,
                            onClickLabel = VOICE_GESTURE_COACHMARK_DISMISS_LABEL,
                            onClick = onDismiss,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "×",
                        style = PocketShellType.bodyDense,
                        color = semantic.accent,
                    )
                }
            }
        }
    }
}
