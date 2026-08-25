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
    fun staleRenderOwnerPredicateAndProofOrderAreExecutable() {
        val first = settledOwner(modelMutationEpoch = 3)
        val sameOwner = first.copy(renderedNonBlankChars = 40, partiallyBlank = false)
        val mutatedOwner = first.copy(modelMutationEpoch = first.modelMutationEpoch + 1)

        assertTrue(
            "the executable owner predicate must accept two stable observations",
            first.isIdenticalSettledObservationAsForTest(sameOwner),
        )
        assertFalse(
            "the executable owner predicate must reject a mutation between observations",
            first.isIdenticalSettledObservationAsForTest(mutatedOwner),
        )

        val order = StaleRenderHealProofOrderForTest()
        listOf(
            StaleRenderHealProofStepForTest.INITIAL_SETTLED_OWNER,
            StaleRenderHealProofStepForTest.STALE_FRAME_INJECTED,
            StaleRenderHealProofStepForTest.POST_INJECTION_SETTLED_OWNER,
            StaleRenderHealProofStepForTest.REMOTE_LIVE_ASSERTIONS,
            StaleRenderHealProofStepForTest.PRE_HEAL_REACQUIRED_OWNER,
            StaleRenderHealProofStepForTest.PRE_CALL_LOCAL_ORACLE,
            StaleRenderHealProofStepForTest.MANUAL_HEAL,
        ).forEach(order::record)
        assertTrue(
            "the executable proof-order seam must accept the production journey order",
            order.isComplete(),
        )

        val wrongOrder = StaleRenderHealProofOrderForTest()
        val rejected = runCatching {
            wrongOrder.record(StaleRenderHealProofStepForTest.MANUAL_HEAL)
        }.isFailure
        assertTrue(
            "the executable proof-order seam must reject healing before stale/live evidence",
            rejected,
        )

        val recoveredOrder = StaleRenderHealProofOrderForTest()
        listOf(
            StaleRenderHealProofStepForTest.INITIAL_SETTLED_OWNER,
            StaleRenderHealProofStepForTest.STALE_FRAME_INJECTED,
            StaleRenderHealProofStepForTest.POST_INJECTION_SETTLED_OWNER,
            StaleRenderHealProofStepForTest.REMOTE_LIVE_ASSERTIONS,
            StaleRenderHealProofStepForTest.PRE_HEAL_REACQUIRED_OWNER,
            StaleRenderHealProofStepForTest.STALE_FRAME_RETAINED_AFTER_OWNER_MUTATION,
            StaleRenderHealProofStepForTest.PRE_HEAL_REACQUIRED_OWNER,
            StaleRenderHealProofStepForTest.PRE_CALL_LOCAL_ORACLE,
            StaleRenderHealProofStepForTest.MANUAL_HEAL,
        ).forEach(recoveredOrder::record)
        assertTrue(
            "the proof-order seam must allow a retained stale frame after owner mutation",
            recoveredOrder.isComplete(),
        )
    }

    @Test
    fun staleRenderJourneyExecutesTheDeclaredProofOrder() {
        val source = locateJourney("StaleRenderHealOnLiveTransportJourneyE2eTest.kt")
        val steps = Regex(
            """proofOrder\.record\s*\(\s*StaleRenderHealProofStepForTest\.([A-Z_]+)""",
        ).findAll(source).map { match ->
            StaleRenderHealProofStepForTest.valueOf(match.groupValues[1])
        }.toList()

        assertTrue(
            "the connected journey must execute its proof-order state machine",
            steps.isNotEmpty(),
        )
        val order = StaleRenderHealProofOrderForTest()
        steps.forEach { step ->
            order.record(step)
            // The source has one settleOwner lambda, but recovery invokes it again after the
            // stale frame is retained. Model that second execution so the guard checks the real
            // runtime order rather than treating a callback's single source occurrence as one call.
            if (step == StaleRenderHealProofStepForTest.STALE_FRAME_RETAINED_AFTER_OWNER_MUTATION) {
                order.record(StaleRenderHealProofStepForTest.PRE_HEAL_REACQUIRED_OWNER)
            }
        }
        assertTrue(
            "the connected journey's actual proof-order calls must form a complete proof",
            order.isComplete(),
        )
        assertTrue(
            "owner recovery must retain the same stale frame for re-injection",
            source.contains("feedFrameToActivePaneModel(\n                        staleFrame,") &&
                source.contains("StaleRenderHealOwnerRecoveryForTest().run("),
        )
    }

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
            val preCallOwnerGuard = if (name == "StaleRenderHealOnLiveTransportJourneyE2eTest.kt") {
                source.contains("isIdenticalSettledObservationAsForTest(expectedOwner)")
            } else {
                source.contains("snapshot.modelMutationEpoch != expectedOwner.modelMutationEpoch")
            }
            assertTrue("$name must retain its settled owner through the pre-call oracle",
                preCallOwnerGuard)
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
                ) || source.contains(
                    "localStale.renderedNonBlankChars,\n" +
                        "                result.stats.renderedNonBlankChars",
                ))
            assertTrue("$name must retain the exact reason per artifact",
                source.contains("heal_reason=${'$'}{healResult.reason}"))
            assertTrue("$name must retain bounded stats per artifact",
                source.contains("heal_capture_non_blank_chars=") &&
                    source.contains("heal_capture_line_count="))
        }
    }

    @Test
    fun staleRenderProofOracleRequiresHealSourceAndRestoredViewport() {
        val valid = StaleRenderHealProofForTest(
            localRenderLooksSuspect = true,
            remoteCaptureNonBlankChars = 40,
            minimumRemoteCaptureChars = 40,
            remoteCaptureHasBanner = true,
            transportConnected = true,
            clientDisconnected = false,
            reconnectSurfaceVisible = false,
            healOutcome = HealOutcome.Healed,
            healReason = HealAttemptReason.DivergenceApplied,
            restoredFrameHasBanner = true,
            restoredFrameRows = 20,
            minimumRestoredFrameRows = 20,
            restoredPaintedRows = 30,
            minimumRestoredPaintedRows = 30,
        )
        assertTrue("the complete connected heal evidence must be accepted", valid.restored)
        assertFalse(
            "a full-looking frame without a healed manual attempt must fail",
            valid.copy(healOutcome = HealOutcome.Healthy).restored,
        )
        assertFalse(
            "a healed attempt without the exact divergence reason must fail",
            valid.copy(healReason = HealAttemptReason.CaptureEmpty).restored,
        )
        assertFalse(
            "a healed attempt without restored banner rows must fail",
            valid.copy(restoredFrameRows = 19).restored,
        )
        assertFalse(
            "a healed attempt without painted viewport rows must fail",
            valid.copy(restoredPaintedRows = 29).restored,
        )
        assertFalse(
            "a healed-looking viewport from a non-suspect precondition must fail",
            valid.copy(localRenderLooksSuspect = false).restored,
        )
    }

    @Test
    fun issue2272KeepsSelectiveFullFrameOracleAndProductionShapedHealPath() {
        val journey = locateJourney("AgentAltScreenPartialBlackHealJourneyE2eTest.kt")
        val proof = locateMain("ActivePaneRenderOwnerSnapshotForTest.kt")

        assertTrue(
            "the already-healed branch must use the pure owner/frame predicate",
            journey.contains("isAlreadyHealedPostInjectionForTest("),
        )
        assertTrue(
            "the manual branch must use the production-shaped no-argument heal seam",
            journey.contains("result = vm.healActivePaneIfStaleRenderResultForTest()"),
        )
        assertFalse(
            "the follow-up must not reintroduce the expected-owner-only test seam",
            journey.contains("result = vm.healActivePaneIfStaleRenderResultForTest(expectedOwner)"),
        )
        assertTrue(
            "the journey must require an automatic or manual restoration source",
            journey.contains("FullFrameHealProofForTest(") &&
                journey.contains("automaticHealRestored = stablePostInjection.alreadyHealed") &&
                journey.contains("manualHealOutcome = healResult?.outcome") &&
                journey.contains(").restored"),
        )
        assertTrue(
            "the already-healed flag must come from the guarded predicate, not merely !partial",
            journey.contains("alreadyHealed = healedCandidate"),
        )
        assertTrue(
            "the pure proof must retain the rendered-content increase guard",
            proof.contains("renderedNonBlankChars > expected.renderedNonBlankChars"),
        )
        assertTrue(
            "the pure proof must reject a no-heal source even when the frame looks full",
            proof.contains("automaticHealRestored || manualHealOutcome == HealOutcome.Healed"),
        )
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
        assertTrue(seam.contains("before.sameOwnerAs(expectedOwner)"))
        assertTrue(seam.contains("before.modelMutationEpoch == expectedOwner.modelMutationEpoch"))
        assertTrue(seam.contains("appendDirectlyToRenderModelForTesting(bytes)"))

        // Exercise the settlement gate itself. A source substring cannot prove that either
        // applied dimension remains tied to its effective dimension after a refactor.
        val settled = settledOwner(modelMutationEpoch = 3)
        assertTrue("the executable settlement baseline must be positive", settled.attachResizeSeedSettled)
        assertFalse(
            "a zero effective column count must fail settlement",
            settled.copy(effectiveColumns = 0).attachResizeSeedSettled,
        )
        assertFalse(
            "a zero effective row count must fail settlement",
            settled.copy(effectiveRows = 0).attachResizeSeedSettled,
        )
        assertFalse(
            "an unapplied column count must fail settlement",
            settled.copy(appliedColumns = settled.appliedColumns + 1).attachResizeSeedSettled,
        )
        assertFalse(
            "an unapplied row count must fail settlement",
            settled.copy(appliedRows = settled.appliedRows + 1).attachResizeSeedSettled,
        )

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
                source.contains("automaticHealOperationsInFlight != 0") ||
                    source.contains("attachResizeSeedSettled"))
            assertTrue("$name must reject automatic-heal ABA at every owner checkpoint",
                source.contains("isIdenticalSettledObservationAsForTest(expectedOwner)") ||
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

    private fun settledOwner(modelMutationEpoch: Long) = ActivePaneRenderOwnerSnapshotForTest(
        paneId = "pane",
        windowId = "@0",
        sessionId = "session",
        targetSessionName = "issue966",
        connectGeneration = 1,
        clientIdentity = 1,
        stateIdentity = 1,
        terminalSessionIdentity = 1,
        emulatorIdentity = 1,
        modelMutationEpoch = modelMutationEpoch,
        modelDrainBacklogged = false,
        seedOperationInFlight = false,
        sizeOperationsInFlight = 0,
        automaticHealOperationsInFlight = 0,
        automaticHealActivityEpoch = 1,
        controlSizeGeneration = 4,
        effectiveColumns = 62,
        effectiveRows = 58,
        appliedColumns = 62,
        appliedRows = 58,
        lastSeedAtMs = 1,
        renderedNonBlankChars = 39,
        partiallyBlank = true,
        renderLooksSuspect = true,
        coherent = true,
    )

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
