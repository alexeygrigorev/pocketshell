package com.pocketshell.app.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealAttemptResultTest {

    @Test
    fun reasonIsTheSingleOutcomeAuthority() {
        val healthyReasons = HealAttemptReason.entries.filter { it.outcome == HealOutcome.Healthy }
        val healedReasons = HealAttemptReason.entries.filter { it.outcome == HealOutcome.Healed }

        assertEquals(
            "Healthy is reserved for a successful nonempty capture whose predicate was false",
            listOf(HealAttemptReason.AuthoritativeCaptureMatched),
            healthyReasons,
        )
        assertEquals(
            listOf(HealAttemptReason.DivergenceApplied, HealAttemptReason.ForcedSnapshotApplied),
            healedReasons,
        )
        HealAttemptReason.entries
            .filterNot { it in healthyReasons || it in healedReasons }
            .forEach { reason ->
                assertEquals("$reason must project to Unverified", HealOutcome.Unverified, reason.outcome)
            }
    }

    @Test
    fun captureFailureReasonsNeverProjectToHealthy() {
        listOf(
            HealAttemptReason.CaptureException,
            HealAttemptReason.CaptureError,
            HealAttemptReason.CaptureEmpty,
        ).forEach { reason ->
            val result = HealAttemptResult.create(
                reason = reason,
                renderedNonBlankChars = 500,
                captureNonBlankChars = 500,
                captureLineCount = 40,
            )
            assertEquals("$reason must remain exact", reason, result.reason)
            assertEquals("$reason must stay hot/unverified", HealOutcome.Unverified, result.outcome)
        }
    }

    @Test
    fun retainedStatsAreNonnegativeAndBounded() {
        val result = HealAttemptResult.create(
            reason = HealAttemptReason.DivergenceApplied,
            renderedNonBlankChars = -1,
            captureNonBlankChars = Int.MAX_VALUE,
            captureLineCount = Int.MAX_VALUE,
        )

        assertEquals(0, result.stats.renderedNonBlankChars)
        assertEquals(HealAttemptStats.MAX_RETAINED_VALUE, result.stats.captureNonBlankChars)
        assertEquals(HealAttemptStats.MAX_RETAINED_VALUE, result.stats.captureLineCount)
        assertTrue(result.stats.toString().length < 180)
        assertEquals(HealOutcome.Healed, result.outcome)
    }
}
