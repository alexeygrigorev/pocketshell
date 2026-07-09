package com.pocketshell.app.composer

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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Issue #453 / #508: the Recording-state surface that replaces the editable
 * input — an amplitude-driven [Waveform] flanked by the elapsed mm:ss timer.
 * The animated waveform alone (plus the live ticking timer) conveys "we are
 * capturing"; there is no redundant "CAPTURING" text. The single in-surface
 * "Stop" label was removed in #508 — stopping is now done via the two
 * explicit bottom-row actions ("Insert" / "Send"), so a lone "Stop" here
 * would be ambiguous about where the transcript lands.
 */
@Composable
internal fun RecordingSurface(
    amplitude: Float,
    capturing: Boolean,
    elapsedLabel: String,
    liveTranscript: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Issue #870 (reopen): a MIN height (not a fixed one) so the dedicated
            // two-line live-transcript area is never vertically clipped — on a
            // large-font device the font-scaled two-line box grows the panel
            // instead of being cut off (the recording panel grows downward; the
            // sticky action row stays put).
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
            // Issue #1152: the elapsed timer is the primary "how long have I been
            // recording" signal, so bump it to 15sp SemiBold — it was the smallest
            // element next to the 32dp waveform (audit D6).
            Text(
                text = elapsedLabel,
                color = PocketShellColors.Accent,
                fontSize = ComposerRecordingStatusFontSize,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag(COMPOSER_TIMER_TAG),
            )
            // Issue #1152 / #1245: Discard moved OUT of this surface into the bottom
            // action row, and the hands-free Lock was removed entirely (#1245), so
            // the surface is now simply [timer · waveform]. A small end inset keeps
            // the amplitude bars from kissing the surface edge (audit C/D2).
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
            LiveTranscriptTwoLine(
                text = liveTranscript,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(COMPOSER_LIVE_TRANSCRIPT_TAG),
            )
        }
    }
}

/**
 * Issue #870 (reopen): the live partial-transcript area for the Android
 * recognizer. The maintainer's design direction is a DEDICATED TWO-LINE area
 * whose second line always holds the live, in-progress recognition — so the
 * newest words are always visible and the cut point is well-defined, instead of
 * clipping a single line.
 *
 * The previous fix anchored the tail with a width-INDEPENDENT character budget
 * ([liveTranscriptTail], 90 chars) and a trailing `TextOverflow.Ellipsis`. On a
 * real device the 90-char tail did not fit two lines at the panel width / font
 * scale, so the trailing ellipsis re-clipped the END — the newest words
 * scrolled out of view again (the exact reopen symptom). A character budget
 * cannot know the panel width, so it cannot guarantee the tail fits.
 *
 * This is width-AWARE: it measures the text with a [TextMeasurer] at the actual
 * available width + font scale, and if the full text needs more than
 * [LIVE_TRANSCRIPT_MAX_LINES] lines it drops leading characters (keeping the
 * TAIL), snaps to a word boundary, and prepends a leading `…`, iterating until
 * the kept tail fits the two-line box. The box is a fixed two-line height and
 * the text is bottom-anchored, so the most recent words always occupy the
 * visible lines.
 *
 * [onResolved] reports the text actually laid out (the trimmed tail, or the raw
 * text when it already fits) so a test can assert the on-screen visible content
 * keeps the newest words — not merely that the full string is present in the
 * semantics tree behind a trailing ellipsis.
 */
@Composable
private fun LiveTranscriptTwoLine(
    text: String,
    modifier: Modifier = Modifier,
    onResolved: (String) -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    val style = PocketShellType.bodyDense
    val density = LocalDensity.current
    val boxHeight = with(density) {
        (style.fontSize.toPx() * LIVE_TRANSCRIPT_LINE_HEIGHT_FACTOR * LIVE_TRANSCRIPT_MAX_LINES).toDp()
    }

    Box(
        modifier = modifier.height(boxHeight),
        contentAlignment = Alignment.BottomStart,
    ) {
        BoxWithConstraints {
            val widthPx = constraints.maxWidth
            // Recompute the visible tail whenever the text or the available
            // width / font scale changes — width-aware, so a tail that fits on a
            // wide panel but not a narrow one is trimmed correctly.
            val resolved = remember(text, widthPx, density.density, density.fontScale) {
                resolveLiveTranscriptTail(
                    text = text,
                    measurer = measurer,
                    style = style,
                    maxWidthPx = widthPx,
                    maxLines = LIVE_TRANSCRIPT_MAX_LINES,
                )
            }
            LaunchedEffect(resolved) { onResolved(resolved) }
            Text(
                text = resolved,
                color = PocketShellColors.Text,
                style = style,
                // The tail is pre-trimmed to fit MAX_LINES at this width, so the
                // newest words always render. maxLines is a belt-and-braces cap;
                // the leading ellipsis (not a trailing one) marks the dropped
                // head, so any residual overflow drops the OLDEST words, never
                // the newest.
                maxLines = LIVE_TRANSCRIPT_MAX_LINES,
                overflow = TextOverflow.Clip,
                modifier = Modifier.testTag(COMPOSER_LIVE_TRANSCRIPT_TEXT_TAG),
            )
        }
    }
}

/**
 * Issue #453: the Transcribing-state surface that replaces the editable
 * input — a "Transcribing…" label + spinner. The Cancel affordance lives in
 * the bottom controls row (so it stays inside the thumb arc, matching the
 * mockup), and cancels the in-flight transcription, restoring the composer.
 */
@Composable
internal fun TranscribingSurface(
    modifier: Modifier = Modifier,
) {
    // Issue #453: center the spinner + label inside the panel. The old
    // left-aligned 20dp dot rendered as a tiny smudge at the left edge; the
    // mockup centers a clear spinner. The Row wraps its content and is then
    // centered horizontally via the parent Box.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(
                color = PocketShellColors.SurfaceElev,
                shape = RoundedCornerShape(ComposerRecordingPanelRadius),
            )
            .border(
                width = 1.dp,
                color = PocketShellColors.Border,
                shape = RoundedCornerShape(ComposerRecordingPanelRadius),
            )
            .testTag(COMPOSER_TRANSCRIBING_SPINNER_TAG)
            .semantics { contentDescription = "Prompt composer transcribing" },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LoadingIndicator.Spinner(size = SpinnerSize.Medium)
            Text(
                text = "Transcribing…",
                color = PocketShellColors.Text,
                fontSize = ComposerRecordingStatusFontSize,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * Issue #453: amplitude-driven waveform shown in the Recording state. The
 * animated strip alone conveys "we are capturing" — there is no redundant
 * status text any more (the maintainer's declutter request).
 *
 * Two visual modes:
 *  - **Active (capturing)** — [active] true: bars animate from the live
 *    amplitude, multiplied by the per-bar envelope and a per-bar phase
 *    offset so the bars ripple outward from the centre instead of moving
 *    as a flat block.
 *  - **Pre-speech (waiting)** — [active] false: the bars subtly pulse
 *    between 4dp and 6dp on a 750ms loop so the strip reads as "alive and
 *    waiting" rather than dormant (the static-indicator bug the maintainer
 *    reported on v0.3.19).
 *
 * When transcription starts the whole recording surface is replaced by the
 * "Transcribing…" spinner (see [TranscribingSurface]) — that is the
 * freeze/settle the #461 §5 motion guidance calls for; the waveform is not
 * rendered at all during transcription.
 */
@Composable
private fun Waveform(
    amplitude: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    // Smooth amplitude transitions so a sudden spike doesn't jerk the
    // bars. 80ms is faster than the human eye's flicker fusion threshold
    // but slow enough to look organic.
    val smoothed by animateFloatAsState(
        targetValue = if (active) amplitude else 0f,
        animationSpec = tween(durationMillis = 80, easing = LinearEasing),
        label = "waveform-smooth",
    )

    // Continuously-running per-bar phase sweep that produces a flowing
    // wave across the 30 bars. Even at low amplitude the wave motion makes
    // the strip read as "alive and capturing" rather than a static block
    // of uniform bars — the maintainer's original complaint. The phase
    // completes one full cycle every ~1.4s (1400ms / 1000 ticks), which
    // is slow enough to feel organic but fast enough to be obviously
    // animated at a glance.
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

    // Pre-speech pulse: the bars rest at 4dp, which read as dormant. A
    // subtle 2dp pulse on a 750ms loop signals "the mic is open, speak"
    // without competing with the live-amplitude animation.
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

    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            for (i in 0 until WAVEFORM_BARS) {
                // Mockup-style envelope: a wide hump centred at index 15
                // with two smaller side lobes. Multiplied by the live
                // amplitude so a quiet user sees a flat strip and a loud
                // one sees full-height bars.
                val envelope = barEnvelopeHeightDp(i)
                // Per-bar phase offset: sine wave propagating outward
                // from the centre. The offset is added to the smoothed
                // amplitude so bars ripple rather than move in lockstep.
                val phaseOffset = waveformPhaseOffset(i, wavePhase)
                val h = when {
                    active -> (4f + (smoothed + phaseOffset).coerceIn(0f, 1f) * envelope)
                        .coerceIn(4f, envelope)
                    // Pre-speech: pulse 4..6dp so the strip reads as
                    // "alive and waiting".
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

/**
 * Per-bar phase offset for the waveform wave animation.
 *
 * Returns a sinusoidal offset in [-0.18, +0.18] for bar [index] at the
 * current [phase] tick. The sine argument wraps around via modulo so the
 * wave repeats smoothly. The offset is small enough that at high amplitude
 * the envelope shape dominates, but at low/zero amplitude it produces a
 * visible flowing ripple — the key to making the indicator read as "alive"
 * even when the mic is picking up only ambient noise.
 */
internal fun waveformPhaseOffset(index: Int, phase: Float): Float {
    val angle = ((index + phase) % WAVEFORM_BARS) / WAVEFORM_BARS * TWO_PI
    return (WAVEFORM_PHASE_AMPLITUDE * kotlin.math.sin(angle)).toFloat()
}

private const val WAVEFORM_BARS = 30
private const val WAVEFORM_WAVE_PERIOD_MS = 1400
private const val WAVEFORM_PHASE_AMPLITUDE = 0.18
private const val TWO_PI = 2.0 * kotlin.math.PI

// Component-specific geometry below the global token ladder; see docs/design-system.md.
private val ComposerRecordingPanelRadius = 12.dp
private val ComposerWaveformBarRadius = 2.dp
private val ComposerRecordingStatusFontSize = 15.sp

/**
 * Per-bar envelope, in dp, used by [Waveform] to vary heights across the
 * 30 bars. Re-creates the visual rhythm of the mockup's hand-tuned bar
 * heights (a wide hump with smaller side-lobes). Lives at file scope so
 * it can be exercised without composing the whole waveform.
 */
internal fun barEnvelopeHeightDp(index: Int): Float {
    // Cosine envelope: tallest in the middle of the strip, tapering to
    // the edges. The constants are chosen to land between 6dp (edge bars)
    // and 28dp (centre bars) — visually identical to the mockup's
    // hand-tuned heights.
    val n = 30
    val centred = (index - (n - 1) / 2f) / ((n - 1) / 2f) // [-1, 1]
    // 1 - centred^2 -> 1 at centre, 0 at edges.
    val envelope = 1f - centred * centred
    return 6f + envelope * 22f // [6dp .. 28dp]
}

/** Issue #453: elapsed mm:ss recording timer rendered next to the waveform. */
internal const val COMPOSER_TIMER_TAG = "prompt-composer-timer"

/**
 * Issue #870: the dedicated two-line live-transcript container (the fixed-height
 * box). [COMPOSER_LIVE_TRANSCRIPT_TEXT_TAG] tags the inner [Text] that holds the
 * resolved (tail-trimmed) content so a test can read what is actually laid out.
 */
internal const val COMPOSER_LIVE_TRANSCRIPT_TAG = "prompt-composer-live-transcript"
internal const val COMPOSER_LIVE_TRANSCRIPT_TEXT_TAG = "prompt-composer-live-transcript-text"

/**
 * Issue #870 (reopen): the dedicated live-transcript area holds exactly this
 * many lines — line two is the live, in-progress recognition (the maintainer's
 * design direction). A long partial keeps its TAIL within these lines.
 */
internal const val LIVE_TRANSCRIPT_MAX_LINES: Int = 2

/**
 * Issue #870: rough line-height multiple over the font size, used to size the
 * fixed two-line box. `bodyDense` does not set an explicit `lineHeight`, so the
 * platform default is ~1.2–1.4×; 1.5× gives the two-line panel a little breathing
 * room and matches the prior 112dp panel allowance without hard-coding a dp.
 */
internal const val LIVE_TRANSCRIPT_LINE_HEIGHT_FACTOR: Float = 1.5f

/**
 * Issue #870 (reopen): width-aware tail resolution for the live transcript.
 *
 * Returns the slice of [text] that fits within [maxLines] lines at [maxWidthPx]
 * for [style], measured with [measurer]. When the full text fits, it is returned
 * untouched. Otherwise the OLDEST words are dropped (keeping the TAIL/newest
 * words), snapped to a word boundary, with a leading `…` marking the cut — so the
 * latest recognized words are always the ones rendered, regardless of device
 * width or font scale. This is the property the width-independent character
 * budget in the superseded `liveTranscriptTail` could not guarantee.
 *
 * Pure (no Compose state) so it is unit-testable with a real [TextMeasurer].
 */
internal fun resolveLiveTranscriptTail(
    text: String,
    measurer: TextMeasurer,
    style: TextStyle,
    maxWidthPx: Int,
    maxLines: Int = LIVE_TRANSCRIPT_MAX_LINES,
): String {
    if (maxWidthPx <= 0 || maxLines <= 0 || text.isEmpty()) return text

    fun fits(candidate: String): Boolean {
        val result = measurer.measure(
            text = candidate,
            style = style,
            constraints = Constraints(maxWidth = maxWidthPx),
        )
        return result.lineCount <= maxLines
    }

    if (fits(text)) return text

    // Binary-search the smallest number of dropped LEADING characters such that
    // the remaining tail (with a leading "… ") fits maxLines. We drop from the
    // head, so the newest words at the tail are always kept.
    var lo = 1 // drop at least one char (full text already failed `fits`)
    var hi = text.length
    var bestStart = text.length
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        val start = mid
        val candidate = "… " + text.substring(start).trimStart()
        if (fits(candidate)) {
            // This tail fits; try keeping MORE of the text (drop fewer chars).
            bestStart = start
            hi = mid - 1
        } else {
            lo = mid + 1
        }
    }

    // Snap the kept window forward to the next word boundary so the first
    // visible word is whole, not a fragment of the dropped head.
    var start = bestStart.coerceIn(0, text.length)
    val nextSpace = text.indexOf(' ', start)
    if (nextSpace in start until text.length) {
        start = nextSpace + 1
    }
    val tail = text.substring(start).trimStart()
    return if (tail.isEmpty()) text.takeLast(1) else "… $tail"
}

/**
 * Issue #153 fix 2: test tag for the in-flight transcribing spinner
 * rendered over the centre of the (collapsed) waveform while the FSM is
 * in `Transcribing`. The spinner is the affordance that makes
 * Transcribing visually distinct from Listening / Capturing — connected
 * tests pin its presence so a future refactor cannot silently regress
 * the distinction.
 */
internal const val COMPOSER_TRANSCRIBING_SPINNER_TAG = "prompt-composer-transcribing-spinner"
