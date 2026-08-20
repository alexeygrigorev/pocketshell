package com.pocketshell.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2033 — classify a host-CLI upgrade from requested vs resolved +
 * the installer output. Class coverage for the three installer paths the
 * banner quotes (`uv`, `pipx`, `pip`).
 *
 * G6 mutations (each must redden exactly the named assertion):
 *  - Treat "Nothing to upgrade" as Capped → unpublished-from-resolved reddens.
 *  - Drop the pip "No matching distribution" marker → pip unpublished reddens.
 *  - Drop the uv "no version of" marker → uv unpublished reddens.
 *  - Drop the pipx "no packages found matching" marker → pipx unpublished reddens.
 *  - Ignore `exclude-newer` in output → capped reddens.
 *  - Offer Retry on Capped → offerRetry assertion reddens.
 */
class HostCliUpgradeOutcomeTest {

    @Test
    fun exitZeroResolvedOlder_isUnpublished_notCapped() {
        // The exact reported state: `--upgrade` exits 0, host still on N−1,
        // output is uv's "Nothing to upgrade". The command already passed
        // `--exclude-newer 2099-12-31`, so this is unpublished, not a cap.
        val verdict = HostCliUpgradeOutcome.classify(
            requestedVersion = "0.4.40",
            resolvedVersion = "0.4.39",
            exitCode = 0,
            output = "Nothing to upgrade",
        )
        assertEquals(HostCliUpgradeOutcome.Kind.Unpublished, verdict.kind)
        assertTrue(verdict.offerRetry)
        assertTrue(verdict.message.contains("not on PyPI"))
        assertFalse(verdict.message.contains("capped", ignoreCase = true))
        assertFalse(verdict.message.contains("uv tool install"))
        assertFalse(verdict.message.contains("pipx"))
        assertFalse(verdict.message.contains("pip install"))
    }

    @Test
    fun uv_noVersionOfRequested_isUnpublished() {
        val output = """
            error: Because there is no version of pocketshell==0.4.40 and you
            require pocketshell==0.4.40, we can conclude that your requirements
            are unsatisfiable.
        """.trimIndent()
        val verdict = HostCliUpgradeOutcome.classify(
            requestedVersion = "0.4.40",
            resolvedVersion = "0.4.39",
            exitCode = 1,
            output = output,
        )
        assertEquals(
            "uv not-found for pocketshell==0.4.40 must be Unpublished — $verdict",
            HostCliUpgradeOutcome.Kind.Unpublished,
            verdict.kind,
        )
        assertTrue(verdict.offerRetry)
    }

    @Test
    fun uv_notFoundInRegistry_isUnpublished() {
        val verdict = HostCliUpgradeOutcome.classify(
            requestedVersion = "0.4.40",
            resolvedVersion = "0.4.39",
            exitCode = 1,
            output = "error: Package `pocketshell` was not found in the registry",
        )
        assertEquals(HostCliUpgradeOutcome.Kind.Unpublished, verdict.kind)
    }

    @Test
    fun pip_noMatchingDistribution_isUnpublished() {
        val output = """
            ERROR: Could not find a version that satisfies the requirement pocketshell==0.4.40 (from versions: 0.4.39)
            ERROR: No matching distribution found for pocketshell==0.4.40
        """.trimIndent()
        val verdict = HostCliUpgradeOutcome.classify(
            requestedVersion = "0.4.40",
            resolvedVersion = "0.4.39",
            exitCode = 1,
            output = output,
        )
        assertEquals(
            "pip not-found for pocketshell==0.4.40 must be Unpublished — $verdict",
            HostCliUpgradeOutcome.Kind.Unpublished,
            verdict.kind,
        )
        assertTrue(verdict.offerRetry)
        assertFalse(verdict.message.contains("capped", ignoreCase = true))
    }

    @Test
    fun pipx_noPackagesFoundMatching_isUnpublished() {
        val verdict = HostCliUpgradeOutcome.classify(
            requestedVersion = "0.4.40",
            resolvedVersion = "0.4.39",
            exitCode = 1,
            output = "No packages found matching specified spec pocketshell==0.4.40",
        )
        assertEquals(
            "pipx not-found for pocketshell==0.4.40 must be Unpublished — $verdict",
            HostCliUpgradeOutcome.Kind.Unpublished,
            verdict.kind,
        )
        assertTrue(verdict.offerRetry)
    }

    @Test
    fun excludeNewerInOutput_isCapped_noRetry() {
        val output = """
            No solution found when resolving dependencies:
            Because exclude-newer is set to 2026-07-05, pocketshell==0.4.40
            is not a candidate.
        """.trimIndent()
        val verdict = HostCliUpgradeOutcome.classify(
            requestedVersion = "0.4.40",
            resolvedVersion = "0.4.39",
            exitCode = 1,
            output = output,
        )
        assertEquals(HostCliUpgradeOutcome.Kind.Capped, verdict.kind)
        assertFalse(
            "Retry of the same already-uncapped command cannot lift a remaining cap",
            verdict.offerRetry,
        )
        assertTrue(verdict.message.contains("date-capped") || verdict.message.contains("capped"))
        assertFalse(verdict.message.contains("uv tool install"))
    }

    @Test
    fun networkError_isFailed_offersRetry() {
        val verdict = HostCliUpgradeOutcome.classify(
            requestedVersion = "0.4.40",
            resolvedVersion = "0.4.39",
            exitCode = 1,
            output = "error: network unreachable",
        )
        assertEquals(HostCliUpgradeOutcome.Kind.Failed, verdict.kind)
        assertTrue(verdict.offerRetry)
        assertTrue(verdict.message.contains("network unreachable"))
    }

    @Test
    fun exitZeroResolvedMatches_isSuccess() {
        val verdict = HostCliUpgradeOutcome.classify(
            requestedVersion = "0.4.40",
            resolvedVersion = "0.4.40",
            exitCode = 0,
            output = "Updated pocketshell 0.4.39 -> 0.4.40",
        )
        assertEquals(HostCliUpgradeOutcome.Kind.Success, verdict.kind)
        assertFalse(verdict.offerRetry)
    }

    @Test
    fun nothingToUpgrade_isNotACapSignal() {
        // G6: if looksLikeCapped treated "Nothing to upgrade" as a cap, the
        // reported N vs N−1 state would be misdiagnosed again.
        assertFalse(
            HostCliUpgradeOutcome.looksLikeCapped("Nothing to upgrade"),
        )
        assertFalse(
            HostCliUpgradeOutcome.looksLikeCapped("nothing newer to install"),
        )
    }
}
