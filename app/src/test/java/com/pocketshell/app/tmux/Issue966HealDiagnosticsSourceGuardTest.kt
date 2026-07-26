package com.pocketshell.app.tmux

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source guard for the two connected journeys that own #966's manual one-pass evidence.
 *
 * The journeys are expensive emulator tests. These cheap checks stop a future cleanup from
 * silently restoring the exact ambiguity that reopened #966: an auto-watchdog race, ignored
 * latch timeout, Boolean outcome collapse, or artifact with no typed reason/stats.
 */
class Issue966HealDiagnosticsSourceGuardTest {

    @Test
    fun bothJourneysQuiesceBeforeInjectionAndRetainTypedEvidence() {
        listOf(
            "StaleRenderHealOnLiveTransportJourneyE2eTest.kt",
            "AgentAltScreenPartialBlackHealJourneyE2eTest.kt",
        ).forEach { name ->
            val source = locateJourney(name)
            val pause = source.indexOf("pauseAutomaticStaleRenderWatchdog()")
            val settlement = source.indexOf("waitForSettledActiveRenderOwner(")
            val injection = source.indexOf("feedFrameToActivePaneModel(")
            val attemptArtifact = source.indexOf("writeHealAttemptArtifact(")
            val exactAssertion = source.indexOf("HealAttemptReason.DivergenceApplied", attemptArtifact)

            assertTrue("$name must pause the automatic watchdog", pause >= 0)
            assertTrue("$name must pause before stale injection", injection >= 0 && pause < injection)
            assertTrue("$name must await positive attach/resize/reseed settlement before injection",
                settlement >= 0 && settlement < injection)
            assertTrue("$name must inject through the exact active VM-owned model",
                source.contains("appendToActivePaneRenderModelForTest(bytes, expectedOwner)"))
            assertTrue("$name must prove TerminalView and VM emulator identity equality",
                source.contains("viewEmulatorIdentity != expectedOwner.emulatorIdentity"))
            assertTrue("$name must retain the injected owner through the pre-call oracle",
                source.contains("snapshot.modelMutationEpoch != expectedOwner.modelMutationEpoch"))
            assertTrue("$name must cancel+join through the atomic VM seam",
                source.contains("pauseActivePaneStaleRenderWatchdogForTest()"))
            assertTrue("$name must assert the watchdog is quiescent",
                source.contains("staleRenderWatchdogJobForTest()?.isActive != true"))
            assertTrue("$name must hard-assert latch completion",
                source.contains("manual stale-render heal latch must complete within the bound"))
            assertFalse("$name must not collapse the typed attempt to Boolean",
                source.contains("private fun driveStaleRenderHeal(): Boolean"))
            assertTrue("$name must require the exact healed reason",
                source.contains("HealAttemptReason.DivergenceApplied"))
            assertTrue("$name must require the typed Healed projection",
                source.contains("HealOutcome.Healed"))
            assertTrue("$name must write the typed attempt before a failing exact assertion",
                attemptArtifact >= 0 && exactAssertion > attemptArtifact)
            assertTrue("$name must prove the remote frame before the call",
                source.contains("remoteCaptureChars >= MIN_AUTHORITATIVE_CAPTURE_CHARS"))
            assertTrue("$name must prove the intended local stale model before the call",
                source.contains("localStale.renderLooksSuspect"))
            assertTrue("$name must bind typed stats to the pre-call stale viewport",
                source.contains(
                    "localStale.renderedNonBlankChars,\n" +
                        "            healResult.stats.renderedNonBlankChars",
                ))
            assertTrue("$name must retain the exact reason per artifact",
                source.contains("heal_reason=${'$'}{healResult.reason}"))
            assertTrue("$name must retain bounded stats per artifact",
                source.contains("heal_capture_non_blank_chars=") &&
                    source.contains("heal_capture_line_count="))
        }
    }

    @Test
    fun vmPauseSeamDisablesFutureAutoArmAndJoinsCurrentJob() {
        val source = locateMain("TmuxSessionViewModel.kt")
        val seam = source.substringBetween(
            "internal suspend fun pauseActivePaneStaleRenderWatchdogForTest()",
            "// Issue #1166 (heal-latency fix)",
        )

        assertTrue(seam.contains("synchronized(staleRenderWatchdogArmLock)"))
        assertTrue(seam.contains("staleRenderWatchdogAutoArmEnabled = false"))
        assertTrue(seam.contains("current?.cancelAndJoin()"))
    }

    @Test
    fun vmAttemptStatsAreSampledBeforeCaptureAndNeverAfterApply() {
        val source = locateMain("TmuxSessionViewModel.kt")
        val heal = source.substringBetween(
            "private suspend fun healActivePaneIfStaleRenderLocked(",
            "private suspend fun captureAndApplyPaneSnapshot(",
        )
        val snapshot = heal.indexOf("val observedRenderedNonBlankChars =")
        val capture = heal.indexOf("val captureAttempt = runCatching")
        val resultFactory = heal.substringBetween(
            "fun result(",
            "if (refreshGuard != null && !isCurrentRuntime(refreshGuard))",
        )

        assertTrue("local render stats must be sampled before capture", snapshot >= 0 && snapshot < capture)
        assertTrue(
            "every attempt result must carry the immutable pre-capture observation",
            resultFactory.contains("renderedNonBlankChars = observedRenderedNonBlankChars"),
        )
        assertFalse(
            "result creation must not resample a viewport that an apply may already have healed",
            resultFactory.contains("pane.terminalState.renderedNonBlankCharCount()"),
        )
        val failedCaptureBranch = heal.substringBetween(
            "if (failedReason != null) {",
            "checkNotNull(combined)",
        )
        assertTrue(
            "capture failures must preserve their exact unverified reason",
            failedCaptureBranch.contains("reason = failedReason"),
        )
        assertFalse(
            "capture failures cannot claim an authoritative match",
            failedCaptureBranch.contains("HealAttemptReason.AuthoritativeCaptureMatched"),
        )
    }

    @Test
    fun vmOwnerSeamHardFailsUnequalOrUnsettledInjection() {
        val seam = locateMain("ActivePaneRenderOwnerSnapshotForTest.kt") +
            locateMain("TmuxSessionViewModel.kt")
        assertTrue(seam.contains("attachResizeSeedSettled"))
        assertTrue(seam.contains("sizeOperationsInFlight == 0"))
        assertTrue(seam.contains("automaticHealOperationsInFlight == 0"))
        assertTrue(seam.contains("automaticHealActivityEpoch == expectedOwner.automaticHealActivityEpoch"))
        assertTrue(seam.contains("appliedColumns == effectiveColumns"))
        assertTrue(seam.contains("appliedRows == effectiveRows"))
        assertTrue(seam.contains("lastSeedAtMs != null"))
        assertTrue(seam.contains("before.sameOwnerAs(expectedOwner)"))
        assertTrue(seam.contains("before.modelMutationEpoch == expectedOwner.modelMutationEpoch"))
        assertTrue(seam.contains("appendDirectlyToRenderModelForTesting(bytes)"))

        val regression = locateTest("PartialBlackPaneHealTest.kt")
        assertTrue(regression.contains("issue966ManualInjectionRejectsAnUnequalVisibleEmulatorOwner"))
        assertTrue(regression.contains("issue966ManualInjectionRejectsAnUnequalVisibleTerminalSessionOwner"))
        assertTrue(regression.contains("issue966ManualInjectionRejectsInjectionBeforeResizeSettlement"))
        assertTrue(regression.contains("issue966ManualProofRejectsQueuedNoOpResizeHealUntilCompletion"))
        assertTrue(regression.contains("issue966ManualOwnerRatchetRejectsCompletedAutomaticHealAba"))
        assertTrue(regression.contains("issue966InjectionRejectsCompletedAutomaticHealBeforePostInjectionSnapshot"))

        val postInjection = locateMain("TmuxSessionViewModel.kt").substringBetween(
            "val after = activePaneRenderOwnerSnapshotForTest()",
            "return after",
        )
        assertTrue(postInjection.contains("after.automaticHealOperationsInFlight == 0"))
        assertTrue(postInjection.contains(
            "after.automaticHealActivityEpoch ==\n            expectedOwner.automaticHealActivityEpoch",
        ))
    }

    @Test
    fun journeysBindVisibleTerminalSessionAndAutomaticHealSettlement() {
        listOf(
            "StaleRenderHealOnLiveTransportJourneyE2eTest.kt",
            "AgentAltScreenPartialBlackHealJourneyE2eTest.kt",
        ).forEach { name ->
            val source = locateJourney(name)
            assertTrue("$name must sample the visible TerminalSession identity",
                source.contains("viewTerminalSessionIdentity"))
            assertTrue("$name must compare it with the VM owner",
                source.contains("terminalSessionIdentity"))
            assertTrue("$name artifacts must retain it",
                source.contains("view_terminal_session_identity="))
            assertTrue("$name must reject pending automatic heals",
                source.contains("automaticHealOperationsInFlight != 0"))
            assertTrue("$name must reject automatic-heal ABA at every owner checkpoint",
                source.split("automaticHealActivityEpoch != expectedOwner.automaticHealActivityEpoch").size >= 4)
            assertTrue("$name artifacts must retain the automatic-heal epoch",
                source.contains("expected_automatic_heal_activity_epoch="))
        }

        val vm = locateMain("TmuxSessionViewModel.kt")
        val resizeHeal = vm.substringBetween(
            "private fun maybeHealActivePaneOnNoOpResize",
            "private fun resetControlClientSizeForAttach",
        )
        assertTrue(resizeHeal.indexOf("automaticRenderHealTracker.begin()") <
            resizeHeal.indexOf("bridgeScope.launch"))
        assertTrue(resizeHeal.contains("healJob.invokeOnCompletion"))
        assertTrue(resizeHeal.contains("automaticRenderHealTracker.complete(healOwner)"))

        val tracker = locateMain("AutomaticRenderHealTracker.kt")
        assertTrue(tracker.contains("Math.incrementExact(activityEpoch)"))
        assertTrue(tracker.contains("Activity(activeCount = activeTokens.size, activityEpoch = activityEpoch)"))
        assertFalse(tracker.substringAfter("fun complete(token: Long)").substringBefore("fun snapshot()")
            .contains("activityEpoch ="))
    }

    @Test
    fun denseLocalCaptureFailuresCannotProjectHealthy() {
        val source = locateTest("HealCaptureUnverifiedWatchdogTest.kt")
        mapOf(
            "denseHealthyLocalCaptureExceptionIsUnverified" to "HealAttemptReason.CaptureException",
            "denseHealthyLocalCaptureErrorIsUnverified" to "HealAttemptReason.CaptureError",
            "denseHealthyLocalEmptyCaptureIsUnverified" to "HealAttemptReason.CaptureEmpty",
        ).forEach { (testName, exactReason) ->
            val test = source.substringBetween("fun $testName()", "\n    @Test", allowEndOfText = true)
            assertTrue("$testName must use the dense non-suspect harness", test.contains("densePaneVm("))
            assertTrue("$testName must require $exactReason", test.contains(exactReason))
            assertTrue("$testName must remain Unverified", test.contains("HealOutcome.Unverified"))
        }
        assertTrue(
            "dense harness must prove the local viewport is not suspect",
            source.contains("pane.terminalState.renderLooksSuspect()"),
        )
    }

    private fun locateJourney(name: String): String =
        locate(
            "app/src/androidTest/java/com/pocketshell/app/proof/$name",
            "src/androidTest/java/com/pocketshell/app/proof/$name",
        )

    private fun locateMain(name: String): String =
        locate(
            "app/src/main/java/com/pocketshell/app/tmux/$name",
            "src/main/java/com/pocketshell/app/tmux/$name",
        )

    private fun locateTest(name: String): String =
        locate(
            "app/src/test/java/com/pocketshell/app/tmux/$name",
            "src/test/java/com/pocketshell/app/tmux/$name",
        )

    private fun locate(vararg candidates: String): String {
        val file = candidates.asSequence().map(::File).firstOrNull(File::isFile)
            ?: error("Could not locate ${candidates.joinToString()} from ${File(".").absolutePath}")
        return file.readText()
    }

    private fun String.substringBetween(
        start: String,
        end: String,
        allowEndOfText: Boolean = false,
    ): String {
        val startIndex = indexOf(start)
        check(startIndex >= 0) { "$start not found" }
        val endIndex = indexOf(end, startIndex)
        check(endIndex >= 0 || allowEndOfText) { "$end not found after $start" }
        return substring(startIndex, if (endIndex >= 0) endIndex else length)
    }
}
