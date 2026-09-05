package com.pocketshell.next.composer

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellType

/**
 * v0.4.47 recording surface (#453 / #508 / #2529): elapsed timer + amplitude
 * waveform. Live partials, when present, sit on two lines under the bars.
 */
@Composable
internal fun RecordingSurface(
    elapsedLabel: String,
    amplitude: Float,
    capturing: Boolean,
    liveTranscript: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (liveTranscript.isNullOrBlank()) 68.dp else 112.dp)
            .background(
                color = PocketShellColors.SurfaceElev,
                shape = RoundedCornerShape(ComposerRecordingPanelRadius),
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.Border,
                shape = RoundedCornerShape(ComposerRecordingPanelRadius),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = elapsedLabel,
                color = PocketShellColors.Accent,
                fontSize = ComposerRecordingStatusFontSize,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag(COMPOSER_TIMER_TAG),
            )
            Waveform(
                amplitude = amplitude,
                active = capturing,
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .padding(end = 4.dp)
                    .testTag(COMPOSER_WAVEFORM_TAG)
                    .semantics {
                        contentDescription = if (capturing) {
                            "Prompt composer capturing speech"
                        } else {
                            "Prompt composer waiting for speech"
                        }
                    },
            )
        }
        if (!liveTranscript.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = liveTranscript,
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun TranscribingSurface(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .background(
                color = PocketShellColors.SurfaceElev,
                shape = RoundedCornerShape(ComposerRecordingPanelRadius),
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.Border,
                shape = RoundedCornerShape(ComposerRecordingPanelRadius),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LoadingIndicator.Spinner(size = SpinnerSize.Small)
        Text(
            text = "Transcribing…",
            color = PocketShellColors.TextSecondary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * A 1 Hz pulse used to recompose the elapsed timer without a hanging
 * `delay()` loop (those never go idle under `waitForIdle`).
 */
@Composable
internal fun recordingElapsedLabel(): String {
    val startedAt = remember { SystemClock.elapsedRealtime() }
    val transition = rememberInfiniteTransition(label = "composer-timer")
    val tick by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "composer-timer-tick",
    )
    val seconds = remember(tick) {
        ((SystemClock.elapsedRealtime() - startedAt) / 1_000L).toInt()
    }
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
internal fun Waveform(
    amplitude: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val smoothed by animateFloatAsState(
        targetValue = if (active) amplitude else 0f,
        animationSpec = tween(durationMillis = 80, easing = LinearEasing),
        label = "waveform-smooth",
    )
    val waveTransition = rememberInfiniteTransition(label = "waveform-wave-phase")
    val wavePhase by waveTransition.animateFloat(
        initialValue = 0f,
        targetValue = WAVEFORM_BARS.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = WAVEFORM_WAVE_PERIOD_MS,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "waveform-wave-phase-value",
    )
    val idlePulse: Float = if (!active) {
        val transition = rememberInfiniteTransition(label = "waveform-idle-pulse")
        val v by transition.animateFloat(
            initialValue = 0f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 750, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "waveform-idle-pulse-value",
        )
        v
    } else {
        0f
    }

    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            for (i in 0 until WAVEFORM_BARS) {
                val envelope = barEnvelopeHeightDp(i)
                val phaseOffset = waveformPhaseOffset(i, wavePhase)
                val h = when {
                    active -> (4f + (smoothed + phaseOffset).coerceIn(0f, 1f) * envelope)
                        .coerceIn(4f, envelope)
                    else -> 4f + idlePulse
                }
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(h.dp)
                        .background(
                            color = PocketShellColors.Accent.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(ComposerWaveformBarRadius),
                        ),
                )
            }
        }
    }
}

internal fun waveformPhaseOffset(index: Int, phase: Float): Float {
    val angle = ((index + phase) % WAVEFORM_BARS) / WAVEFORM_BARS * TWO_PI
    return (WAVEFORM_PHASE_AMPLITUDE * kotlin.math.sin(angle)).toFloat()
}

internal fun barEnvelopeHeightDp(index: Int): Float {
    val n = 30
    val centred = (index - (n - 1) / 2f) / ((n - 1) / 2f)
    val envelope = 1f - centred * centred
    return 6f + envelope * 22f
}

private const val WAVEFORM_BARS = 30
private const val WAVEFORM_WAVE_PERIOD_MS = 1400
private const val WAVEFORM_PHASE_AMPLITUDE = 0.18
private const val TWO_PI = 2.0 * kotlin.math.PI
private val ComposerRecordingPanelRadius = 12.dp
private val ComposerWaveformBarRadius = 2.dp
private val ComposerRecordingStatusFontSize = 15.sp
