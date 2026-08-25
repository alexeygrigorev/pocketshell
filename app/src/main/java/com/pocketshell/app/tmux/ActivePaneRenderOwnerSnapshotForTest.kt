package com.pocketshell.app.tmux

/** Named settlement/owner predicates used by the #2321 mutation matrix. */
internal enum class SettledObservationGuardForTest {
    COHERENT,
    EMULATOR_IDENTITY_PRESENT,
    MODEL_DRAIN_QUIET,
    SEED_IDLE,
    SIZE_IDLE,
    AUTOMATIC_HEAL_IDLE,
    EFFECTIVE_COLUMNS_POSITIVE,
    EFFECTIVE_ROWS_POSITIVE,
    APPLIED_COLUMNS_POSITIVE,
    APPLIED_ROWS_POSITIVE,
    APPLIED_COLUMNS_MATCH_EFFECTIVE,
    APPLIED_ROWS_MATCH_EFFECTIVE,
    LAST_SEED_PRESENT,
    PANE_ID,
    WINDOW_ID,
    SESSION_ID,
    TARGET_SESSION_NAME,
    CONNECT_GENERATION,
    CLIENT_IDENTITY,
    STATE_IDENTITY,
    TERMINAL_SESSION_IDENTITY,
    EMULATOR_IDENTITY,
    MODEL_MUTATION_EPOCH,
    CONTROL_SIZE_GENERATION,
    EFFECTIVE_COLUMNS,
    EFFECTIVE_ROWS,
    APPLIED_COLUMNS,
    APPLIED_ROWS,
    AUTOMATIC_HEAL_ACTIVITY_EPOCH,
    LAST_SEED_AT,
}

/**
 * Issue #966 connected-proof owner snapshot. Identity binds the visible TerminalView session to
 * the exact active VM pane/model; settlement fields expose positive completion invariants for
 * attach seed and asynchronous refresh-client/reseed work, without a timing guess.
 */
internal data class ActivePaneRenderOwnerSnapshotForTest(
    val paneId: String,
    val windowId: String,
    val sessionId: String,
    val targetSessionName: String?,
    val connectGeneration: Long,
    val clientIdentity: Int?,
    val stateIdentity: Int,
    val terminalSessionIdentity: Int?,
    val emulatorIdentity: Int?,
    val modelMutationEpoch: Long,
    val modelDrainBacklogged: Boolean,
    val seedOperationInFlight: Boolean,
    val sizeOperationsInFlight: Int,
    val automaticHealOperationsInFlight: Int,
    val automaticHealActivityEpoch: Long,
    val controlSizeGeneration: Long,
    val effectiveColumns: Int,
    val effectiveRows: Int,
    val appliedColumns: Int,
    val appliedRows: Int,
    val lastSeedAtMs: Long?,
    val renderedNonBlankChars: Int,
    val partiallyBlank: Boolean,
    val renderLooksSuspect: Boolean,
    val coherent: Boolean,
) {
    val attachResizeSeedSettled: Boolean
        get() = settlementGuardFailuresForTest().isEmpty()

    fun sameOwnerAs(other: ActivePaneRenderOwnerSnapshotForTest): Boolean =
        paneId == other.paneId &&
            windowId == other.windowId &&
            sessionId == other.sessionId &&
            targetSessionName == other.targetSessionName &&
            connectGeneration == other.connectGeneration &&
            clientIdentity == other.clientIdentity &&
            stateIdentity == other.stateIdentity &&
            terminalSessionIdentity == other.terminalSessionIdentity &&
            emulatorIdentity == other.emulatorIdentity

    /**
     * Issue #2321: a journey settlement is an observation window, not one positive
     * [attachResizeSeedSettled] sample. Both observations must describe the same owner,
     * control/effective/applied dimensions, model/control generations, and mutation epoch, with
     * no resize, seed, or automatic-heal work in flight. This remains a pure test-only
     * predicate so a resize/model mutation between settlement and heal is a selective red case.
     */
    fun isIdenticalSettledObservationAsForTest(
        other: ActivePaneRenderOwnerSnapshotForTest,
    ): Boolean = identicalSettledObservationFailuresForTest(other).isEmpty()

    /**
     * Individual settlement predicates are exposed to the JVM proof so a one-field mutation can
     * name exactly which guard rejected it. This prevents a dimension mutation from being
     * laundered by a broad conjunction or by a second, accidental failure in the matrix.
     */
    internal fun settlementGuardFailuresForTest(): Set<SettledObservationGuardForTest> =
        buildSet {
            if (!coherent) add(SettledObservationGuardForTest.COHERENT)
            if (emulatorIdentity == null) {
                add(SettledObservationGuardForTest.EMULATOR_IDENTITY_PRESENT)
            }
            if (modelDrainBacklogged) add(SettledObservationGuardForTest.MODEL_DRAIN_QUIET)
            if (seedOperationInFlight) add(SettledObservationGuardForTest.SEED_IDLE)
            if (sizeOperationsInFlight != 0) add(SettledObservationGuardForTest.SIZE_IDLE)
            if (automaticHealOperationsInFlight != 0) {
                add(SettledObservationGuardForTest.AUTOMATIC_HEAL_IDLE)
            }
            if (effectiveColumns <= 0) {
                add(SettledObservationGuardForTest.EFFECTIVE_COLUMNS_POSITIVE)
            }
            if (effectiveRows <= 0) {
                add(SettledObservationGuardForTest.EFFECTIVE_ROWS_POSITIVE)
            }
            if (appliedColumns <= 0) {
                add(SettledObservationGuardForTest.APPLIED_COLUMNS_POSITIVE)
            }
            if (appliedRows <= 0) {
                add(SettledObservationGuardForTest.APPLIED_ROWS_POSITIVE)
            }
            if (appliedColumns != effectiveColumns) {
                add(SettledObservationGuardForTest.APPLIED_COLUMNS_MATCH_EFFECTIVE)
            }
            if (appliedRows != effectiveRows) {
                add(SettledObservationGuardForTest.APPLIED_ROWS_MATCH_EFFECTIVE)
            }
            if (lastSeedAtMs == null) add(SettledObservationGuardForTest.LAST_SEED_PRESENT)
        }

    internal fun identicalSettledObservationFailuresForTest(
        other: ActivePaneRenderOwnerSnapshotForTest,
    ): Set<SettledObservationGuardForTest> = buildSet {
        // The observation comparator intentionally checks positive settlement shape without the
        // applied==effective relation. The journey establishes the full relation on every sample
        // via attachResizeSeedSettled; this keeps each effective/applied equality dimension
        // independently load-bearing in the observation-window matrix.
        addAll(observationShapeFailuresForTest())
        addAll(other.observationShapeFailuresForTest())
        if (paneId != other.paneId) add(SettledObservationGuardForTest.PANE_ID)
        if (windowId != other.windowId) add(SettledObservationGuardForTest.WINDOW_ID)
        if (sessionId != other.sessionId) add(SettledObservationGuardForTest.SESSION_ID)
        if (targetSessionName != other.targetSessionName) {
            add(SettledObservationGuardForTest.TARGET_SESSION_NAME)
        }
        if (connectGeneration != other.connectGeneration) {
            add(SettledObservationGuardForTest.CONNECT_GENERATION)
        }
        if (clientIdentity != other.clientIdentity) {
            add(SettledObservationGuardForTest.CLIENT_IDENTITY)
        }
        if (stateIdentity != other.stateIdentity) {
            add(SettledObservationGuardForTest.STATE_IDENTITY)
        }
        if (terminalSessionIdentity != other.terminalSessionIdentity) {
            add(SettledObservationGuardForTest.TERMINAL_SESSION_IDENTITY)
        }
        if (emulatorIdentity != other.emulatorIdentity) {
            add(SettledObservationGuardForTest.EMULATOR_IDENTITY)
        }
        if (modelMutationEpoch != other.modelMutationEpoch) {
            add(SettledObservationGuardForTest.MODEL_MUTATION_EPOCH)
        }
        if (controlSizeGeneration != other.controlSizeGeneration) {
            add(SettledObservationGuardForTest.CONTROL_SIZE_GENERATION)
        }
        if (effectiveColumns != other.effectiveColumns) {
            add(SettledObservationGuardForTest.EFFECTIVE_COLUMNS)
        }
        if (effectiveRows != other.effectiveRows) {
            add(SettledObservationGuardForTest.EFFECTIVE_ROWS)
        }
        if (appliedColumns != other.appliedColumns) {
            add(SettledObservationGuardForTest.APPLIED_COLUMNS)
        }
        if (appliedRows != other.appliedRows) {
            add(SettledObservationGuardForTest.APPLIED_ROWS)
        }
        if (automaticHealActivityEpoch != other.automaticHealActivityEpoch) {
            add(SettledObservationGuardForTest.AUTOMATIC_HEAL_ACTIVITY_EPOCH)
        }
        if (lastSeedAtMs != other.lastSeedAtMs) {
            add(SettledObservationGuardForTest.LAST_SEED_AT)
        }
    }

    private fun observationShapeFailuresForTest(): Set<SettledObservationGuardForTest> =
        buildSet {
            if (!coherent) add(SettledObservationGuardForTest.COHERENT)
            if (emulatorIdentity == null) {
                add(SettledObservationGuardForTest.EMULATOR_IDENTITY_PRESENT)
            }
            if (modelDrainBacklogged) add(SettledObservationGuardForTest.MODEL_DRAIN_QUIET)
            if (seedOperationInFlight) add(SettledObservationGuardForTest.SEED_IDLE)
            if (sizeOperationsInFlight != 0) add(SettledObservationGuardForTest.SIZE_IDLE)
            if (automaticHealOperationsInFlight != 0) {
                add(SettledObservationGuardForTest.AUTOMATIC_HEAL_IDLE)
            }
            if (effectiveColumns <= 0) {
                add(SettledObservationGuardForTest.EFFECTIVE_COLUMNS_POSITIVE)
            }
            if (effectiveRows <= 0) {
                add(SettledObservationGuardForTest.EFFECTIVE_ROWS_POSITIVE)
            }
            if (appliedColumns <= 0) {
                add(SettledObservationGuardForTest.APPLIED_COLUMNS_POSITIVE)
            }
            if (appliedRows <= 0) {
                add(SettledObservationGuardForTest.APPLIED_ROWS_POSITIVE)
            }
            if (lastSeedAtMs == null) add(SettledObservationGuardForTest.LAST_SEED_PRESENT)
        }

    /**
     * Issue #2272: the post-resize repaint branch is valid only when it is a later, richer,
     * fully-restored observation of the same settled pane. This pure predicate is intentionally
     * unit-tested so removing any one guard is a live mutation, not a decorative assertion.
     */
    fun isAlreadyHealedPostInjectionForTest(
        expected: ActivePaneRenderOwnerSnapshotForTest,
        visibleFrameMarker: Boolean,
        visibleFrameRows: Int,
        minimumFrameRows: Int,
    ): Boolean =
        sameOwnerAs(expected) &&
            modelMutationEpoch > expected.modelMutationEpoch &&
            controlSizeGeneration >= expected.controlSizeGeneration &&
            automaticHealActivityEpoch == expected.automaticHealActivityEpoch &&
            attachResizeSeedSettled &&
            !partiallyBlank &&
            renderedNonBlankChars > expected.renderedNonBlankChars &&
            visibleFrameMarker &&
            visibleFrameRows >= minimumFrameRows
}

/**
 * Issue #2321: executable order gate for the connected stale-render proof. It is deliberately a
 * test-only seam: the production heal has no dependency on this state machine, while the journey
 * cannot silently run its pre-call oracle against the injected owner or heal before live evidence.
 */
internal enum class StaleRenderHealProofStepForTest {
    INITIAL_SETTLED_OWNER,
    STALE_FRAME_INJECTED,
    POST_INJECTION_SETTLED_OWNER,
    REMOTE_LIVE_ASSERTIONS,
    PRE_HEAL_REACQUIRED_OWNER,
    STALE_FRAME_RETAINED_AFTER_OWNER_MUTATION,
    PRE_CALL_LOCAL_ORACLE,
    MANUAL_HEAL,
}

internal class StaleRenderHealProofOrderForTest {
    private val steps = mutableListOf<StaleRenderHealProofStepForTest>()

    fun record(step: StaleRenderHealProofStepForTest) {
        check(isAllowedNext(step)) {
            "stale-render proof step out of order: previous=${steps.lastOrNull()} actual=$step"
        }
        steps += step
    }

    fun isComplete(): Boolean =
        steps.firstOrNull() == StaleRenderHealProofStepForTest.INITIAL_SETTLED_OWNER &&
            steps.contains(StaleRenderHealProofStepForTest.STALE_FRAME_INJECTED) &&
            steps.contains(StaleRenderHealProofStepForTest.POST_INJECTION_SETTLED_OWNER) &&
            steps.contains(StaleRenderHealProofStepForTest.REMOTE_LIVE_ASSERTIONS) &&
            steps.contains(StaleRenderHealProofStepForTest.PRE_HEAL_REACQUIRED_OWNER) &&
            steps.contains(StaleRenderHealProofStepForTest.PRE_CALL_LOCAL_ORACLE) &&
            steps.lastOrNull() == StaleRenderHealProofStepForTest.MANUAL_HEAL

    private fun isAllowedNext(step: StaleRenderHealProofStepForTest): Boolean = when (step) {
        StaleRenderHealProofStepForTest.INITIAL_SETTLED_OWNER -> steps.isEmpty()
        StaleRenderHealProofStepForTest.STALE_FRAME_INJECTED ->
            steps.lastOrNull() == StaleRenderHealProofStepForTest.INITIAL_SETTLED_OWNER
        StaleRenderHealProofStepForTest.POST_INJECTION_SETTLED_OWNER ->
            steps.lastOrNull() == StaleRenderHealProofStepForTest.STALE_FRAME_INJECTED
        StaleRenderHealProofStepForTest.REMOTE_LIVE_ASSERTIONS ->
            steps.lastOrNull() == StaleRenderHealProofStepForTest.POST_INJECTION_SETTLED_OWNER
        StaleRenderHealProofStepForTest.PRE_HEAL_REACQUIRED_OWNER ->
            steps.lastOrNull() == StaleRenderHealProofStepForTest.REMOTE_LIVE_ASSERTIONS ||
                steps.lastOrNull() == StaleRenderHealProofStepForTest.STALE_FRAME_RETAINED_AFTER_OWNER_MUTATION ||
                steps.lastOrNull() == StaleRenderHealProofStepForTest.PRE_HEAL_REACQUIRED_OWNER ||
                steps.lastOrNull() == StaleRenderHealProofStepForTest.PRE_CALL_LOCAL_ORACLE
        StaleRenderHealProofStepForTest.STALE_FRAME_RETAINED_AFTER_OWNER_MUTATION ->
            steps.lastOrNull() == StaleRenderHealProofStepForTest.PRE_HEAL_REACQUIRED_OWNER ||
                steps.lastOrNull() == StaleRenderHealProofStepForTest.PRE_CALL_LOCAL_ORACLE
        StaleRenderHealProofStepForTest.PRE_CALL_LOCAL_ORACLE ->
            steps.lastOrNull() == StaleRenderHealProofStepForTest.PRE_HEAL_REACQUIRED_OWNER
        StaleRenderHealProofStepForTest.MANUAL_HEAL ->
            steps.lastOrNull() == StaleRenderHealProofStepForTest.PRE_CALL_LOCAL_ORACLE
    }
}

/** Only owner/precondition failures are recoverable by the connected proof barrier. */
internal class StaleRenderOwnerChangedForTest(message: String) : IllegalStateException(message)

/**
 * Issue #2321: close the settlement-to-heal race without weakening the stale-frame oracle.
 *
 * A settled observation is not a lease. The active pane can resize, reseed, or complete an
 * automatic heal after the observation and before the manual call. The barrier therefore does
 * two checks: it compares the settled sample with an immediate live sample, then lets the
 * production-shaped attempt perform its own owner check. Either check can request recovery. The
 * recovery waits for a fresh settled owner and re-injects the same stale frame before retrying.
 *
 * This is test-only coordination; production heal behavior is unchanged. The callbacks are
 * intentionally explicit so the JVM test can place a resize/model mutation in each race window
 * and prove that the stale frame is retained rather than silently turning the assertion green.
 */
internal class StaleRenderHealOwnerRecoveryForTest(
    private val maxAttempts: Int = 3,
    private val sameSettledOwner: (
        ActivePaneRenderOwnerSnapshotForTest,
        ActivePaneRenderOwnerSnapshotForTest,
    ) -> Boolean = { expected, actual ->
        expected.isIdenticalSettledObservationAsForTest(actual)
    },
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    fun <T> run(
        initialOwner: ActivePaneRenderOwnerSnapshotForTest,
        settleOwner: () -> ActivePaneRenderOwnerSnapshotForTest,
        liveOwner: () -> ActivePaneRenderOwnerSnapshotForTest,
        retainStaleFrame: (ActivePaneRenderOwnerSnapshotForTest) -> ActivePaneRenderOwnerSnapshotForTest,
        attemptHeal: (ActivePaneRenderOwnerSnapshotForTest) -> T,
    ): T {
        var expectedOwner = initialOwner
        repeat(maxAttempts) { attemptNumber ->
            val settledOwner = settleOwner()
            if (!sameSettledOwner(expectedOwner, settledOwner)) {
                expectedOwner = retainStaleFrame(settledOwner)
                return@repeat
            }

            // This read is deliberately separate from settleOwner(). It is the deterministic
            // barrier for a resize/model mutation in the exact interval the bug reported.
            val ownerImmediatelyBeforeAttempt = liveOwner()
            if (!sameSettledOwner(settledOwner, ownerImmediatelyBeforeAttempt)) {
                expectedOwner = retainStaleFrame(settleOwner())
                return@repeat
            }

            try {
                return attemptHeal(settledOwner)
            } catch (changed: StaleRenderOwnerChangedForTest) {
                if (attemptNumber == maxAttempts - 1) throw changed
                // The production-shaped attempt caught a mutation after the live sample. Re-read
                // a settled owner and restore the same stale frame before trying again.
                expectedOwner = retainStaleFrame(settleOwner())
            }
        }
        throw StaleRenderOwnerChangedForTest(
            "stale-render owner did not remain stable for $maxAttempts attempts",
        )
    }
}

/**
 * Issue #2321: the connected journey's load-bearing oracle. A full-looking viewport is not a
 * healed result unless the same run first observed the stale local render, proved the remote
 * frame/transport discriminator, and the production-shaped manual attempt actually healed it.
 */
internal data class StaleRenderHealProofForTest(
    val localRenderLooksSuspect: Boolean,
    val remoteCaptureNonBlankChars: Int,
    val minimumRemoteCaptureChars: Int,
    val remoteCaptureHasBanner: Boolean,
    val transportConnected: Boolean,
    val clientDisconnected: Boolean,
    val reconnectSurfaceVisible: Boolean,
    val healOutcome: HealOutcome?,
    val healReason: HealAttemptReason?,
    val restoredFrameHasBanner: Boolean,
    val restoredFrameRows: Int,
    val minimumRestoredFrameRows: Int,
    val restoredPaintedRows: Int,
    val minimumRestoredPaintedRows: Int,
) {
    val restored: Boolean
        get() = localRenderLooksSuspect &&
            remoteCaptureNonBlankChars >= minimumRemoteCaptureChars &&
            remoteCaptureHasBanner &&
            transportConnected &&
            !clientDisconnected &&
            !reconnectSurfaceVisible &&
            healOutcome == HealOutcome.Healed &&
            healReason == HealAttemptReason.DivergenceApplied &&
            restoredFrameHasBanner &&
            restoredFrameRows >= minimumRestoredFrameRows &&
            restoredPaintedRows >= minimumRestoredPaintedRows
}

/**
 * Issue #2272: a connected journey can only claim that the full frame was restored when the
 * visible frame is backed by either the already-proven automatic repaint or a manual attempt
 * that actually returned [HealOutcome.Healed]. A visually plausible frame without either source
 * is still a no-heal outcome.
 */
internal data class FullFrameHealProofForTest(
    val automaticHealRestored: Boolean,
    val manualHealOutcome: HealOutcome?,
    val visiblePartiallyBlank: Boolean,
    val visibleFrameMarker: Boolean,
    val visibleFrameRows: Int,
    val minimumFrameRows: Int,
) {
    val restored: Boolean
        get() = (automaticHealRestored || manualHealOutcome == HealOutcome.Healed) &&
            !visiblePartiallyBlank &&
            visibleFrameMarker &&
            visibleFrameRows >= minimumFrameRows
}
