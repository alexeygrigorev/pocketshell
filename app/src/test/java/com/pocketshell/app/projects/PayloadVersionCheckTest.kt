package com.pocketshell.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #885: the passive payload-version mismatch detector.
 *
 * The host's `pocketshell` CLI version arrives in the `tree get` /
 * `tree reconcile` payload (`cli_version`); the client compares it against the
 * version the app expects. These tests pin the three verdicts and the
 * no-false-mismatch contract for missing/unparseable data.
 */
class PayloadVersionCheckTest {

    @Test
    fun hostOlderThanExpected_isHostOutdated() {
        // The maintainer's #885 scenario: the host CLI is behind the app build,
        // so the client must flag "update pocketshell on the host" — PASSIVELY,
        // from the payload, on any open.
        val verdict = PayloadVersionCheck.evaluate(hostVersion = "0.4.9", expectedVersion = "0.4.12")
        assertTrue(verdict is PayloadVersionCheck.Verdict.HostOutdated)
        verdict as PayloadVersionCheck.Verdict.HostOutdated
        assertEquals("0.4.9", verdict.hostVersion)
        assertEquals("0.4.12", verdict.expectedVersion)
    }

    @Test
    fun multiDigitComponentsOrderNumerically() {
        // "0.4.10" must be NEWER than "0.4.9" (string compare would get this
        // wrong) — host on 0.4.10, app expects 0.4.9 → app is behind, NOT a
        // host-update prompt.
        val verdict = PayloadVersionCheck.evaluate(hostVersion = "0.4.10", expectedVersion = "0.4.9")
        assertTrue(verdict is PayloadVersionCheck.Verdict.AppOutdated)
    }

    @Test
    fun equalVersions_isMatch() {
        assertEquals(
            PayloadVersionCheck.Verdict.Match,
            PayloadVersionCheck.evaluate("0.4.12", "0.4.12"),
        )
    }

    @Test
    fun hostNewerThanExpected_isAppOutdated_notHostPrompt() {
        val verdict = PayloadVersionCheck.evaluate(hostVersion = "0.5.0", expectedVersion = "0.4.12")
        assertTrue(verdict is PayloadVersionCheck.Verdict.AppOutdated)
    }

    @Test
    fun nullOrBlankHostVersion_isNoSignal() {
        // An OLD CLI omits cli_version → null/blank → never a false mismatch.
        assertEquals(PayloadVersionCheck.Verdict.Match, PayloadVersionCheck.evaluate(null, "0.4.12"))
        assertEquals(PayloadVersionCheck.Verdict.Match, PayloadVersionCheck.evaluate("", "0.4.12"))
        assertEquals(PayloadVersionCheck.Verdict.Match, PayloadVersionCheck.evaluate("   ", "0.4.12"))
    }

    @Test
    fun blankExpectedVersion_isNoSignal() {
        assertEquals(PayloadVersionCheck.Verdict.Match, PayloadVersionCheck.evaluate("0.4.9", null))
        assertEquals(PayloadVersionCheck.Verdict.Match, PayloadVersionCheck.evaluate("0.4.9", ""))
    }

    @Test
    fun unparseableVersion_degradesToNoSignal_notAGuess() {
        // A pre-release / non-dotted-numeric shape is not comparable → no signal
        // (never a false mismatch).
        assertEquals(
            PayloadVersionCheck.Verdict.Match,
            PayloadVersionCheck.evaluate("0.4.9-dev", "0.4.12"),
        )
        assertEquals(
            PayloadVersionCheck.Verdict.Match,
            PayloadVersionCheck.evaluate("garbage", "0.4.12"),
        )
    }

    @Test
    fun outdatedHostPrompt_namesVersionsAndUpdateCommand() {
        val verdict = PayloadVersionCheck.Verdict.HostOutdated(
            hostVersion = "0.4.9",
            expectedVersion = "0.4.12",
        )
        val prompt = PayloadVersionCheck.outdatedHostPrompt(verdict)
        assertTrue(prompt.contains("0.4.9"))
        assertTrue(prompt.contains("0.4.12"))
        assertTrue(prompt.contains(PayloadVersionCheck.UPDATE_COMMAND))
    }
}
