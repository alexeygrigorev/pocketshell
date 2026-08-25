package com.pocketshell.app.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2272/#2321 G6 mutation proof for the settled-owner and already-healed branches.
 *
 * Each rejected case names a one-line mutation that must make this test red. The negative values
 * are deliberately one-guard cases: resize/seed shape mutations are applied symmetrically to
 * both observations so equality/identity guards cannot launder the targeted failure.
 */
class ActivePaneRenderOwnerSnapshotForTestTest {

    @Test
    fun identicalSettledObservationHasOneFieldAtATimeMutationMatrix() {
        val settled = snapshot(epoch = 3, generation = 4, chars = 39, partial = true)

        // The observation window may ignore a visual-only change; settlement is about the
        // owner/size/model invariants, so the intentional stale frame is not normalized away.
        assertTrue(
            "the same settled owner remains valid when only rendered frame diagnostics change",
            settled.isIdenticalSettledObservationAsForTest(
                settled.copy(renderedNonBlankChars = 40, partiallyBlank = false),
            ),
        )

        val mutations = listOf(
            SettledObservationGuardForTest.MODEL_MUTATION_EPOCH to
                settled.copy(modelMutationEpoch = settled.modelMutationEpoch + 1),
            SettledObservationGuardForTest.CONTROL_SIZE_GENERATION to settled.copy(
                controlSizeGeneration = settled.controlSizeGeneration + 1,
            ),
            // Change each dimension independently. The comparator is intentionally fed already
            // settled observations by the journey; its field-by-field checks must still reject
            // either half of a resize mutation without hiding the failure behind paired fields.
            SettledObservationGuardForTest.EFFECTIVE_COLUMNS to
                settled.copy(effectiveColumns = settled.effectiveColumns + 1),
            SettledObservationGuardForTest.EFFECTIVE_ROWS to
                settled.copy(effectiveRows = settled.effectiveRows + 1),
            SettledObservationGuardForTest.APPLIED_COLUMNS to
                settled.copy(appliedColumns = settled.appliedColumns + 1),
            SettledObservationGuardForTest.APPLIED_ROWS to
                settled.copy(appliedRows = settled.appliedRows + 1),
            SettledObservationGuardForTest.AUTOMATIC_HEAL_ACTIVITY_EPOCH to settled.copy(
                automaticHealActivityEpoch = settled.automaticHealActivityEpoch + 1,
            ),
            SettledObservationGuardForTest.LAST_SEED_AT to
                settled.copy(lastSeedAtMs = settled.lastSeedAtMs!! + 1),
            SettledObservationGuardForTest.COHERENT to settled.copy(coherent = false),
            SettledObservationGuardForTest.MODEL_DRAIN_QUIET to
                settled.copy(modelDrainBacklogged = true),
            SettledObservationGuardForTest.SEED_IDLE to settled.copy(seedOperationInFlight = true),
            SettledObservationGuardForTest.SIZE_IDLE to settled.copy(sizeOperationsInFlight = 1),
            SettledObservationGuardForTest.AUTOMATIC_HEAL_IDLE to
                settled.copy(automaticHealOperationsInFlight = 1),
            SettledObservationGuardForTest.PANE_ID to settled.copy(paneId = "foreign-pane"),
            SettledObservationGuardForTest.WINDOW_ID to settled.copy(windowId = "@9"),
            SettledObservationGuardForTest.SESSION_ID to settled.copy(sessionId = "foreign-session"),
            SettledObservationGuardForTest.TARGET_SESSION_NAME to
                settled.copy(targetSessionName = "foreign-target"),
            SettledObservationGuardForTest.CONNECT_GENERATION to
                settled.copy(connectGeneration = settled.connectGeneration + 1),
            SettledObservationGuardForTest.CLIENT_IDENTITY to
                settled.copy(clientIdentity = settled.clientIdentity!! + 1),
            SettledObservationGuardForTest.STATE_IDENTITY to
                settled.copy(stateIdentity = settled.stateIdentity + 1),
            SettledObservationGuardForTest.TERMINAL_SESSION_IDENTITY to settled.copy(
                terminalSessionIdentity = settled.terminalSessionIdentity!! + 1,
            ),
            SettledObservationGuardForTest.EMULATOR_IDENTITY to
                settled.copy(emulatorIdentity = settled.emulatorIdentity!! + 1),
        )

        // Every entry changes exactly one data-class field. A surviving assertion after removing
        // that field's predicate would be a G6 false positive; the baseline above proves that
        // all other predicates are simultaneously satisfied.
        mutations.forEach { (field, mutated) ->
            assertEquals(
                "one-field mutation of $field must redden only its named observation guard",
                setOf(field),
                settled.identicalSettledObservationFailuresForTest(mutated),
            )
        }

        // These are the positive-shape guards. Mutate the same single field in both observations
        // so the pair remains identical and only the settlement-shape predicate can reject it.
        val shapeMutations = listOf(
            SettledObservationGuardForTest.EMULATOR_IDENTITY_PRESENT to
                settled.copy(emulatorIdentity = null),
            SettledObservationGuardForTest.EFFECTIVE_COLUMNS_POSITIVE to
                settled.copy(effectiveColumns = 0),
            SettledObservationGuardForTest.EFFECTIVE_ROWS_POSITIVE to
                settled.copy(effectiveRows = 0),
            SettledObservationGuardForTest.APPLIED_COLUMNS_POSITIVE to
                settled.copy(appliedColumns = 0),
            SettledObservationGuardForTest.APPLIED_ROWS_POSITIVE to
                settled.copy(appliedRows = 0),
            SettledObservationGuardForTest.LAST_SEED_PRESENT to
                settled.copy(lastSeedAtMs = null),
        )
        shapeMutations.forEach { (field, mutated) ->
            assertEquals(
                "one-field shape mutation of $field must redden only its named observation guard",
                setOf(field),
                mutated.identicalSettledObservationFailuresForTest(mutated),
            )
        }
    }

    @Test
    fun fullSettlementGateNamesDimensionAndActivityFailures() {
        val settled = snapshot(epoch = 3, generation = 4, chars = 39, partial = true)
        assertTrue(
            "the positive settlement gate must have no failures",
            settled.settlementGuardFailuresForTest().isEmpty(),
        )

        val directMutations = listOf(
            SettledObservationGuardForTest.COHERENT to settled.copy(coherent = false),
            SettledObservationGuardForTest.EMULATOR_IDENTITY_PRESENT to
                settled.copy(emulatorIdentity = null),
            SettledObservationGuardForTest.MODEL_DRAIN_QUIET to
                settled.copy(modelDrainBacklogged = true),
            SettledObservationGuardForTest.SEED_IDLE to settled.copy(seedOperationInFlight = true),
            SettledObservationGuardForTest.SIZE_IDLE to settled.copy(sizeOperationsInFlight = 1),
            SettledObservationGuardForTest.AUTOMATIC_HEAL_IDLE to
                settled.copy(automaticHealOperationsInFlight = 1),
            SettledObservationGuardForTest.LAST_SEED_PRESENT to settled.copy(lastSeedAtMs = null),
        )
        directMutations.forEach { (guard, mutated) ->
            assertTrue(
                "one-field settlement mutation of $guard must name its load-bearing guard",
                mutated.settlementGuardFailuresForTest().contains(guard),
            )
        }

        // The positive-dimension and applied==effective checks are intentionally both load
        // bearing. A zero effective/applied dimension necessarily violates its positivity and
        // its paired-size relation; test both names rather than allowing a broad false to hide
        // either predicate. The nonzero mismatch isolates the paired relation by itself.
        val dimensionMutations = listOf(
            settled.copy(effectiveColumns = 0) to setOf(
                SettledObservationGuardForTest.EFFECTIVE_COLUMNS_POSITIVE,
                SettledObservationGuardForTest.APPLIED_COLUMNS_MATCH_EFFECTIVE,
            ),
            settled.copy(effectiveRows = 0) to setOf(
                SettledObservationGuardForTest.EFFECTIVE_ROWS_POSITIVE,
                SettledObservationGuardForTest.APPLIED_ROWS_MATCH_EFFECTIVE,
            ),
            settled.copy(appliedColumns = 0) to setOf(
                SettledObservationGuardForTest.APPLIED_COLUMNS_POSITIVE,
                SettledObservationGuardForTest.APPLIED_COLUMNS_MATCH_EFFECTIVE,
            ),
            settled.copy(appliedRows = 0) to setOf(
                SettledObservationGuardForTest.APPLIED_ROWS_POSITIVE,
                SettledObservationGuardForTest.APPLIED_ROWS_MATCH_EFFECTIVE,
            ),
            settled.copy(appliedColumns = settled.effectiveColumns + 1) to setOf(
                SettledObservationGuardForTest.APPLIED_COLUMNS_MATCH_EFFECTIVE,
            ),
            settled.copy(appliedRows = settled.effectiveRows + 1) to setOf(
                SettledObservationGuardForTest.APPLIED_ROWS_MATCH_EFFECTIVE,
            ),
        )
        dimensionMutations.forEach { (mutated, expectedFailures) ->
            assertEquals(
                "dimension settlement mutation must report its exact guard set",
                expectedFailures,
                mutated.settlementGuardFailuresForTest(),
            )
        }
    }

    @Test
    fun twoObservationWindowRejectsMutationBetweenSamplesDeterministically() {
        val first = snapshot(epoch = 3, generation = 4, chars = 39, partial = true)
        val stableSecond = first.copy(renderedNonBlankChars = 40, partiallyBlank = false)
        val mutatedSecond = first.copy(modelMutationEpoch = first.modelMutationEpoch + 1)

        fun acceptsTwoObservationWindow(second: ActivePaneRenderOwnerSnapshotForTest): Boolean =
            first.isIdenticalSettledObservationAsForTest(second)

        assertTrue(
            "two identical settled observations must form a valid window",
            acceptsTwoObservationWindow(stableSecond),
        )
        assertFalse(
            "a model mutation between the first and second sample must reject the window",
            acceptsTwoObservationWindow(mutatedSecond),
        )

        // The rejection is directional too: the mutated sample cannot be used as the prior
        // observation to hide the change when the window is evaluated in the other direction.
        assertFalse(
            "the mutated observation must not become a valid prior sample",
            mutatedSecond.isIdenticalSettledObservationAsForTest(first),
        )
    }

    @Test
    fun ownerRecoveryReacquiresAndRetainsFrameAfterResizeMutationBeforeAttempt() {
        val initial = snapshot(epoch = 3, generation = 4, chars = 39, partial = true)
        var current = initial
        var mutateAtLiveRead = true
        var settleOwnerReadCount = 0
        var liveOwnerReadCount = 0
        var retainCount = 0
        var preCallOraclePassed = false
        var manualHealPassed = false

        val result = StaleRenderHealOwnerRecoveryForTest().run(
            initialOwner = initial,
            settleOwner = {
                settleOwnerReadCount++
                current
            },
            liveOwner = {
                liveOwnerReadCount++
                if (mutateAtLiveRead) {
                    mutateAtLiveRead = false
                    current = current.copy(
                        modelMutationEpoch = current.modelMutationEpoch + 1,
                        controlSizeGeneration = current.controlSizeGeneration + 1,
                        effectiveColumns = current.effectiveColumns + 1,
                        effectiveRows = current.effectiveRows + 1,
                        appliedColumns = current.appliedColumns + 1,
                        appliedRows = current.appliedRows + 1,
                        automaticHealActivityEpoch = current.automaticHealActivityEpoch + 1,
                        lastSeedAtMs = current.lastSeedAtMs!! + 1,
                    )
                }
                current
            },
            retainStaleFrame = { settledAfterResize ->
                retainCount++
                current = settledAfterResize.copy(
                    modelMutationEpoch = settledAfterResize.modelMutationEpoch + 1,
                    renderedNonBlankChars = 4,
                    partiallyBlank = true,
                    renderLooksSuspect = true,
                )
                current
            },
            attemptHeal = { expected ->
                // This is the exact pre-call local oracle: the manual attempt must receive the
                // same owner as the live render model after recovery, with the retained stale
                // viewport still present. A settlement-only proxy would let a resize mutation
                // turn the proof green while the intended stale frame was gone.
                assertTrue(
                    "the pre-call owner must be identical to the recovered live owner",
                    expected.isIdenticalSettledObservationAsForTest(current),
                )
                assertTrue(current.renderLooksSuspect)
                assertEquals(4, current.renderedNonBlankChars)
                preCallOraclePassed = true
                manualHealPassed = true
                "healed"
            },
        )

        assertEquals("healed", result)
        assertEquals(
            "the resize/model mutation must cause one fresh stale-frame retention",
            1,
            retainCount,
        )
        assertEquals(
            "the real live-owner barrier must be read once for each attempted owner",
            2,
            liveOwnerReadCount,
        )
        assertEquals(
            "a barrier mismatch must reacquire a settled owner before retrying",
            3,
            settleOwnerReadCount,
        )
        assertEquals(4, current.renderedNonBlankChars)
        assertTrue(current.renderLooksSuspect)
        assertTrue("the pre-call local owner oracle must execute", preCallOraclePassed)
        assertTrue("the manual heal callback must execute after that oracle", manualHealPassed)
    }

    @Test
    fun ownerRecoveryRetriesWhenProductionHealPreflightSeesMutationAfterLiveRead() {
        val initial = snapshot(epoch = 3, generation = 4, chars = 39, partial = true)
        var current = initial
        var firstAttempt = true
        var attemptCount = 0
        var retainCount = 0
        var preCallOracleCount = 0
        var manualHealPassed = false

        val result = StaleRenderHealOwnerRecoveryForTest().run(
            initialOwner = initial,
            settleOwner = { current },
            liveOwner = { current },
            retainStaleFrame = { settledAfterMutation ->
                retainCount++
                current = settledAfterMutation.copy(
                    modelMutationEpoch = settledAfterMutation.modelMutationEpoch + 1,
                    renderedNonBlankChars = 3,
                    partiallyBlank = true,
                    renderLooksSuspect = true,
                )
                current
            },
            attemptHeal = { expected ->
                attemptCount++
                if (firstAttempt) {
                    firstAttempt = false
                    current = current.copy(modelMutationEpoch = current.modelMutationEpoch + 1)
                    throw StaleRenderOwnerChangedForTest(
                        "synthetic resize/model mutation between live owner and manual heal",
                    )
                }
                preCallOracleCount++
                assertTrue(expected.isIdenticalSettledObservationAsForTest(current))
                assertTrue(current.renderLooksSuspect)
                assertEquals(3, current.renderedNonBlankChars)
                manualHealPassed = true
                "healed-after-retry"
            },
        )

        assertEquals("healed-after-retry", result)
        assertEquals(
            "a production preflight mutation must be recovered, not turned into a green skip",
            2,
            attemptCount,
        )
        assertEquals(1, retainCount)
        assertTrue(current.renderLooksSuspect)
        assertEquals(1, preCallOracleCount)
        assertTrue("the retry must reach the manual heal only after its pre-call oracle", manualHealPassed)
    }

    @Test
    fun executableMutantsRedWhenOwnerStabilizationOrProofOrderIsRemoved() {
        val initial = snapshot(epoch = 3, generation = 4, chars = 39, partial = true)
        var current = initial
        var manualHealCalled = false

        // Executable mutant: remove the live-owner stabilization predicate. The synthetic resize
        // is in the exact settlement-to-manual interval; the pre-call oracle must then redden.
        val ownerBarrierRemoved = StaleRenderHealOwnerRecoveryForTest(
            maxAttempts = 1,
            sameSettledOwner = { _, _ -> true },
        )
        val barrierFailure = assertThrows(AssertionError::class.java) {
            ownerBarrierRemoved.run(
                initialOwner = initial,
                settleOwner = { current },
                liveOwner = {
                    current = current.copy(
                        controlSizeGeneration = current.controlSizeGeneration + 1,
                        effectiveColumns = current.effectiveColumns + 1,
                        appliedColumns = current.appliedColumns + 1,
                    )
                    current
                },
                retainStaleFrame = { current },
                attemptHeal = { expected ->
                    manualHealCalled = true
                    assertTrue(
                        "pre-call owner stabilization must reject the resized live owner",
                        expected.isIdenticalSettledObservationAsForTest(current),
                    )
                },
            )
        }
        assertTrue(barrierFailure.message.orEmpty().contains("pre-call owner"))
        assertTrue("the mutated barrier must reach the pre-call oracle before reddening", manualHealCalled)

        // Executable mutant: remove the proof-order check. Healing before stale/live evidence must
        // be rejected by the same state machine that the connected journey records.
        val proofOrder = StaleRenderHealProofOrderForTest()
        val orderFailure = assertThrows(IllegalStateException::class.java) {
            proofOrder.record(StaleRenderHealProofStepForTest.MANUAL_HEAL)
        }
        assertTrue(orderFailure.message.orEmpty().contains("out of order"))
    }

    @Test
    fun alreadyHealedRequiresLaterStableOwnerAndVisibleFullFrame() {
        val expected = snapshot(epoch = 3, generation = 4, chars = 39, partial = true)
        val healed = snapshot(epoch = 4, generation = 4, chars = 137, partial = false)

        assertTrue(
            "a later stable same-owner rich frame with the visible marker is accepted",
            healed.isAlreadyHealedPostInjectionForTest(
                expected = expected,
                visibleFrameMarker = true,
                visibleFrameRows = 6,
                minimumFrameRows = 5,
            ),
        )

        // Mutation: remove `modelMutationEpoch > expected.modelMutationEpoch`.
        assertFalse(
            "a richer frame at the pre-injection epoch is not an intervening repaint",
            healed.copy(modelMutationEpoch = expected.modelMutationEpoch)
                .isAlreadyHealedPostInjectionForTest(expected, true, 6, 5),
        )

        // Mutation: remove `sameOwnerAs(expected)`.
        assertFalse(
            "a richer frame from another pane is not a valid healed outcome",
            healed.copy(paneId = "foreign-pane")
                .isAlreadyHealedPostInjectionForTest(expected, true, 6, 5),
        )

        // Mutation: remove `controlSizeGeneration >= expected.controlSizeGeneration`.
        assertFalse(
            "a frame from an older control-size generation is not a valid healed outcome",
            healed.copy(controlSizeGeneration = expected.controlSizeGeneration - 1)
                .isAlreadyHealedPostInjectionForTest(expected, true, 6, 5),
        )

        // Mutation: remove `automaticHealActivityEpoch == expected.automaticHealActivityEpoch`.
        assertFalse(
            "a frame after an automatic-heal activity change is not a valid intervening repaint",
            healed.copy(automaticHealActivityEpoch = expected.automaticHealActivityEpoch + 1)
                .isAlreadyHealedPostInjectionForTest(expected, true, 6, 5),
        )

        // Mutation: remove the `attachResizeSeedSettled` guard.
        assertFalse(
            "a resize still in flight cannot be accepted as stable",
            healed.copy(sizeOperationsInFlight = 1)
                .isAlreadyHealedPostInjectionForTest(expected, true, 6, 5),
        )

        // Mutation: remove `!partiallyBlank`.
        assertFalse(
            "a partial-black frame is not an already-healed outcome",
            healed.copy(partiallyBlank = true)
                .isAlreadyHealedPostInjectionForTest(expected, true, 6, 5),
        )

        // Mutation: remove `renderedNonBlankChars > expected.renderedNonBlankChars`.
        // This is selective: all other predicates, including the visible full-frame oracle, are
        // satisfied. Equality is the concrete reviewer mutation's surviving false-positive.
        assertFalse(
            "a later full-looking frame with no increase in rendered content is not healed",
            healed.copy(renderedNonBlankChars = expected.renderedNonBlankChars)
                .isAlreadyHealedPostInjectionForTest(expected, true, 6, 5),
        )

        // Mutation: remove `visibleFrameMarker`.
        assertFalse(
            "a model-only increase without the visible full frame is not healed",
            healed.isAlreadyHealedPostInjectionForTest(expected, false, 6, 5),
        )

        // Mutation: remove `visibleFrameRows >= minimumFrameRows`.
        assertFalse(
            "a visible marker with too few restored rows is not healed",
            healed.isAlreadyHealedPostInjectionForTest(expected, true, 4, 5),
        )
    }

    @Test
    fun fullFrameHealOutcomeRequiresAutomaticOrManualRestore() {
        val fullFrame = FullFrameHealProofForTest(
            automaticHealRestored = true,
            manualHealOutcome = null,
            visiblePartiallyBlank = false,
            visibleFrameMarker = true,
            visibleFrameRows = 6,
            minimumFrameRows = 5,
        )

        assertTrue(
            "an already-healed stable repaint is a valid full-frame outcome",
            fullFrame.restored,
        )
        assertTrue(
            "a manual Healed attempt is a valid full-frame outcome",
            fullFrame.copy(automaticHealRestored = false, manualHealOutcome = HealOutcome.Healed)
                .restored,
        )

        // Mutation: remove `(automaticHealRestored || manualHealOutcome == HealOutcome.Healed)`.
        // All visible full-frame predicates remain true, so neither source of restoration may be
        // omitted or replaced by the visual assertion alone.
        assertFalse(
            "a full-looking frame with neither automatic nor manual restoration must fail",
            fullFrame.copy(automaticHealRestored = false, manualHealOutcome = null).restored,
        )
        assertFalse(
            "a non-healing manual outcome must not count as restoration",
            fullFrame.copy(
                automaticHealRestored = false,
                manualHealOutcome = HealOutcome.Healthy,
            ).restored,
        )

        // Each of these changes only one visible full-frame guard from the valid automatic case.
        assertFalse(
            "an automatic restore that remains partial-black is not a full-frame outcome",
            fullFrame.copy(visiblePartiallyBlank = true).restored,
        )
        assertFalse(
            "an automatic restore without the frame marker is not a full-frame outcome",
            fullFrame.copy(visibleFrameMarker = false).restored,
        )
        assertFalse(
            "an automatic restore with too few frame rows is not a full-frame outcome",
            fullFrame.copy(visibleFrameRows = 4).restored,
        )
    }

    @Test
    fun staleRenderProofOracleHasSelectiveHealAndViewportMatrix() {
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
        assertTrue("the complete stale-render evidence must pass", valid.restored)

        val mutations = listOf(
            "localRenderLooksSuspect" to valid.copy(localRenderLooksSuspect = false),
            "remoteCaptureNonBlankChars" to valid.copy(remoteCaptureNonBlankChars = 39),
            "remoteCaptureHasBanner" to valid.copy(remoteCaptureHasBanner = false),
            "transportConnected" to valid.copy(transportConnected = false),
            "clientDisconnected" to valid.copy(clientDisconnected = true),
            "reconnectSurfaceVisible" to valid.copy(reconnectSurfaceVisible = true),
            "healOutcome" to valid.copy(healOutcome = HealOutcome.Healthy),
            "healReason" to valid.copy(healReason = HealAttemptReason.CaptureEmpty),
            "restoredFrameHasBanner" to valid.copy(restoredFrameHasBanner = false),
            "restoredFrameRows" to valid.copy(restoredFrameRows = 19),
            "restoredPaintedRows" to valid.copy(restoredPaintedRows = 29),
        )
        mutations.forEach { (field, mutated) ->
            assertFalse(
                "one-field stale-render proof mutation of $field must redden the load-bearing oracle",
                mutated.restored,
            )
        }
    }

    private fun snapshot(
        epoch: Long,
        generation: Long,
        chars: Int,
        partial: Boolean,
        paneId: String = "pane",
        sizeOperationsInFlight: Int = 0,
    ) = ActivePaneRenderOwnerSnapshotForTest(
        paneId = paneId,
        windowId = "@0",
        sessionId = "session",
        targetSessionName = "issue1138-alt-black",
        connectGeneration = 1,
        clientIdentity = 1,
        stateIdentity = 1,
        terminalSessionIdentity = 1,
        emulatorIdentity = 1,
        modelMutationEpoch = epoch,
        modelDrainBacklogged = false,
        seedOperationInFlight = false,
        sizeOperationsInFlight = sizeOperationsInFlight,
        automaticHealOperationsInFlight = 0,
        automaticHealActivityEpoch = 1,
        controlSizeGeneration = generation,
        effectiveColumns = 62,
        effectiveRows = 58,
        appliedColumns = 62,
        appliedRows = 58,
        lastSeedAtMs = 1,
        renderedNonBlankChars = chars,
        partiallyBlank = partial,
        renderLooksSuspect = partial,
        coherent = true,
    )
}
