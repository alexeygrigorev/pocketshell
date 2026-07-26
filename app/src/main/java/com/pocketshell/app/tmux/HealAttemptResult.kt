package com.pocketshell.app.tmux

/**
 * Immutable diagnostic result for one oracle-gated stale-render heal attempt.
 *
 * [reason] is the single authority for the legacy [outcome] projection, so a caller cannot
 * construct a contradictory pair such as `Healthy` + `CaptureError`. The compact numeric
 * observations are capped before they leave the heal path: connected journeys can retain one
 * line per stale kind without writing an unbounded terminal capture into the artifact bundle.
 */
@ConsistentCopyVisibility
internal data class HealAttemptResult private constructor(
    val reason: HealAttemptReason,
    val stats: HealAttemptStats,
) {
    val outcome: HealOutcome
        get() = reason.outcome

    companion object {
        internal fun create(
            reason: HealAttemptReason,
            renderedNonBlankChars: Int,
            captureNonBlankChars: Int = 0,
            captureLineCount: Int = 0,
        ): HealAttemptResult =
            HealAttemptResult(
                reason = reason,
                stats = HealAttemptStats.bounded(
                    renderedNonBlankChars = renderedNonBlankChars,
                    captureNonBlankChars = captureNonBlankChars,
                    captureLineCount = captureLineCount,
                ),
            )
    }
}

/**
 * Exact terminal condition observed by one stale-render heal pass.
 *
 * [AuthoritativeCaptureMatched] is deliberately the only [HealOutcome.Healthy] reason: healthy
 * means a successful, non-empty authoritative capture reached the loss predicate and the
 * predicate returned false. A missing, thrown, error, or empty capture is always unverified.
 */
internal enum class HealAttemptReason(internal val outcome: HealOutcome) {
    MissingClient(HealOutcome.Unverified),
    MissingActivePane(HealOutcome.Unverified),
    MissingActiveTarget(HealOutcome.Unverified),
    RuntimeSupersededBeforeCapture(HealOutcome.Unverified),
    ClientDisconnectedBeforeCapture(HealOutcome.Unverified),
    RuntimeSupersededAfterCapture(HealOutcome.Unverified),
    CaptureException(HealOutcome.Unverified),
    CaptureError(HealOutcome.Unverified),
    CaptureEmpty(HealOutcome.Unverified),
    AuthoritativeCaptureMatched(HealOutcome.Healthy),
    DivergenceApplyFailed(HealOutcome.Unverified),
    DivergenceApplied(HealOutcome.Healed),
    ForcedSnapshotApplied(HealOutcome.Healed),
    ForcedSnapshotUnavailable(HealOutcome.Unverified),
}

internal class HealAttemptStats private constructor(
    val renderedNonBlankChars: Int,
    val captureNonBlankChars: Int,
    val captureLineCount: Int,
) {
    override fun toString(): String =
        "HealAttemptStats(renderedNonBlankChars=$renderedNonBlankChars, " +
            "captureNonBlankChars=$captureNonBlankChars, captureLineCount=$captureLineCount)"

    companion object {
        internal const val MAX_RETAINED_VALUE: Int = 100_000

        internal fun bounded(
            renderedNonBlankChars: Int,
            captureNonBlankChars: Int,
            captureLineCount: Int,
        ): HealAttemptStats =
            HealAttemptStats(
                renderedNonBlankChars = renderedNonBlankChars.coerceIn(0, MAX_RETAINED_VALUE),
                captureNonBlankChars = captureNonBlankChars.coerceIn(0, MAX_RETAINED_VALUE),
                captureLineCount = captureLineCount.coerceIn(0, MAX_RETAINED_VALUE),
            )
    }
}
