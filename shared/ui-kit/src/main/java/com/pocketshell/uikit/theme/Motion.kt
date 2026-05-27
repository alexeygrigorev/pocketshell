package com.pocketshell.uikit.theme

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Motion tokens for PocketShell — durations and easing curves codified from
 * `docs/design-system.md` §5. Mirrors [PocketShellColors], [PocketShellTypography],
 * and [PocketShellShapes]: a small, named set of values, no logic.
 *
 * Why durations are exposed as [kotlin.time.Duration] rather than raw `Int` ms:
 *
 * - The spec talks in human units (150 ms, 200 ms, 400 ms). [Duration] keeps
 *   that vocabulary at the call site (`MotionDurations.normal`) instead of a
 *   bare `200` that an unsuspecting reader might confuse for dp / sp / px.
 * - Compose's [androidx.compose.animation.core.tween] takes an `Int` millis,
 *   so call sites convert at the boundary:
 *
 *   ```kotlin
 *   tween(
 *       durationMillis = MotionDurations.normal.inWholeMilliseconds.toInt(),
 *       easing = MotionEasing.standard,
 *   )
 *   ```
 *
 *   The cast is safe — every token below is well under `Int.MAX_VALUE` ms.
 *
 * Locked principle from `docs/design-system.md` §10 ("Anti-design"):
 * **no purely decorative motion**. Every animation must serve a comprehension
 * goal — connection state, transcription progress, structural transitions.
 * Reach for these tokens, not ad-hoc magic numbers, so future audits can grep
 * `Motion` references and reason about every animation in the app.
 */
object MotionDurations {
    /**
     * Fast — 150 ms. Use for snappy entry/exit on small surfaces:
     *
     * - Chip dismiss / hint banner fade-in + fade-out.
     * - Tab highlight pulse (a single pulse is 75 ms in + 75 ms out).
     * - Key bar "pressed" feedback.
     *
     * Anything the user must perceive as instantaneous response to a tap.
     */
    val fast: Duration = 150.milliseconds

    /**
     * Normal — 200 ms. The default for standard UI transitions:
     *
     * - Bottom sheet open / close (composer sheet, snippet sheet).
     * - Tab change content swap.
     * - List item fade-in.
     *
     * If you have to pick a duration and there's no obvious reason to deviate,
     * pick this one.
     */
    val normal: Duration = 200.milliseconds

    /**
     * Slow — 400 ms. For emphasis transitions where the user is meant to
     * perceive motion as part of the message:
     *
     * - Progress bar fill (transcription progress, connection establishment).
     * - Waveform stabilisation (transitions to "transcribing" state).
     * - Status-dot state-change emphasis when colour alone would be too quiet.
     *
     * Avoid for anything triggered by direct user input — 400 ms feels laggy
     * when the user is poking buttons.
     */
    val slow: Duration = 400.milliseconds

    /**
     * Idle pulse — 1.5 s loop. Reserved for ambient indicators that have to
     * communicate "work is happening, but the user shouldn't wait on it":
     *
     * - Status dot in `Connecting` state.
     * - Composer idle waveform when listening but no audio yet.
     *
     * Documented here for reference; pair with [MotionEasing.stepsTwo] when
     * the motion should feel discrete rather than smooth (square-wave pulse).
     * Use sparingly — `docs/design-system.md` §5 limits idle motion to the
     * specific states above.
     */
    val idlePulse: Duration = 1_500.milliseconds

    /**
     * Cursor / caret blink — 1.05 s loop. Used for the terminal cursor and
     * the text-input caret. Pair with [MotionEasing.stepsTwo] so the cursor
     * is either fully on or fully off (no fading), matching xterm behaviour.
     */
    val blink: Duration = 1_050.milliseconds
}

/**
 * Easing curves for PocketShell motion. Maps the human names used in the spec
 * (`docs/design-system.md` §5) onto Compose's [Easing] type.
 *
 * Default for any new animation: [standard]. Reach for the others only when
 * the spec calls for them.
 */
object MotionEasing {
    /**
     * Standard ease-out — the default for entry / exit transitions.
     *
     * Maps to Compose's [androidx.compose.animation.core.EaseOut]. Matches
     * the spec's `easeOut` curve and the Material 3 standard-decelerate
     * recommendation: fast at the start, slow at the end, so the eye lands
     * gently on the final position.
     */
    val standard: Easing = EaseOut

    /**
     * Linear easing — uniform velocity from start to end.
     *
     * Use only for continuous repeating loops (status-dot pulse, waveform
     * idle pulse) where any acceleration would read as a stutter. **Not** a
     * good default for one-shot transitions; the eye reads linear one-shots
     * as mechanical.
     */
    val linear: Easing = LinearEasing

    /**
     * "Two-step" easing — snaps between two discrete values across the
     * animation's duration (off → on at the midpoint). Mirrors CSS's
     * `steps(2)` from the mockup palette.
     *
     * Pair with [MotionDurations.blink] for the terminal cursor and with
     * [MotionDurations.idlePulse] for status-dot connecting pulses. Returns
     * `0.0f` for the first half of the timeline and `1.0f` for the second
     * half so a `tween(easing = stepsTwo)` produces a square wave.
     *
     * Compose ships no built-in `steps()` easing, so this is provided as a
     * locked custom curve to keep call sites identical to the spec.
     */
    val stepsTwo: Easing = Easing { fraction -> if (fraction < 0.5f) 0f else 1f }
}
