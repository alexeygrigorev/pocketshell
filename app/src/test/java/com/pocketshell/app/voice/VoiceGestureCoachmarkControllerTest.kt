package com.pocketshell.app.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM lifecycle coverage for issue #1753's durable presentation contract. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceGestureCoachmarkControllerTest {

    @Test
    fun claimAloneDoesNotConsume_andUnpresentedClaimCanBeReleased() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val store = FakeStore()

        runTest(dispatcher) {
            val controller = controller(store, dispatcher, this)
            advanceUntilIdle()

            val claimId = controller.tryClaim()
            assertTrue(controller.uiState.value is VoiceGestureCoachmarkUiState.Claimed)
            assertEquals(0, store.commitCount)

            controller.release(checkNotNull(claimId))
            assertTrue(controller.uiState.value is VoiceGestureCoachmarkUiState.Ready)
            assertEquals(0, store.commitCount)
        }
    }

    @Test
    fun presentedClaimCommitsOnce_andNewControllerStartsHidden() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val store = FakeStore()

        runTest(dispatcher) {
            val controller = controller(store, dispatcher, this)
            advanceUntilIdle()
            val claimId = checkNotNull(controller.tryClaim())

            controller.markPresented(claimId)
            advanceUntilIdle()

            assertTrue(controller.uiState.value is VoiceGestureCoachmarkUiState.Presented)
            assertEquals(1, store.commitCount)

            val restarted = controller(store, dispatcher, this)
            advanceUntilIdle()
            assertTrue(restarted.uiState.value is VoiceGestureCoachmarkUiState.Hidden)
            assertNull(restarted.tryClaim())
            assertEquals(1, store.commitCount)
        }
    }

    @Test
    fun releaseBeforePresentation_invalidatesLateMark_andAllowsAReplacementClaim() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val store = FakeStore()

        runTest(dispatcher) {
            val controller = controller(store, dispatcher, this)
            advanceUntilIdle()
            val claimId = checkNotNull(controller.tryClaim())

            // Mutation target: if a stale markPresented is allowed to win after
            // release(), this test persists the invalidated claim and loses the
            // next eligible host's education.
            controller.release(claimId)
            controller.markPresented(claimId)
            advanceUntilIdle()

            assertEquals(VoiceGestureCoachmarkUiState.Ready, controller.uiState.value)
            assertEquals(0, store.commitCount)
            assertTrue("released claim must be replaceable", controller.tryClaim() != null)
        }
    }

    @Test
    fun markPresentedIsSingleFlight_whenCalledAgainDuringPersistence() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val store = BlockingStore()

        runTest(dispatcher) {
            val controller = controller(store, dispatcher, this)
            advanceUntilIdle()
            val claimId = checkNotNull(controller.tryClaim())

            controller.markPresented(claimId)
            runCurrent()
            assertTrue(store.commitStarted.isCompleted)

            // Mutation target: removing the Claimed -> Persisting transition
            // guard would enqueue a second durable write here.
            controller.markPresented(claimId)
            assertEquals(
                VoiceGestureCoachmarkUiState.Persisting(
                    claimId = claimId,
                    showWhenCommitted = true,
                ),
                controller.uiState.value,
            )

            store.commitGate.complete(true)
            advanceUntilIdle()
            assertEquals(VoiceGestureCoachmarkUiState.Presented(claimId), controller.uiState.value)
            assertEquals(1, store.commitCount)
        }
    }

    @Test
    fun releaseDuringInFlightCommit_keepsClaimReserved_untilSuccessfulCommitHidesIt() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val store = BlockingStore()

        runTest(dispatcher) {
            val controller = controller(store, dispatcher, this)
            advanceUntilIdle()
            val claimId = checkNotNull(controller.tryClaim())

            controller.markPresented(claimId)
            runCurrent()
            assertTrue(store.commitStarted.isCompleted)

            // Mutation target: changing release(Persisting) to Ready permits a
            // replacement claim while the old write can still consume the hint.
            controller.release(claimId)
            assertEquals(
                VoiceGestureCoachmarkUiState.Persisting(
                    claimId = claimId,
                    showWhenCommitted = false,
                ),
                controller.uiState.value,
            )
            assertNull("in-flight claim must remain reserved", controller.tryClaim())

            store.commitGate.complete(true)
            advanceUntilIdle()

            assertEquals(VoiceGestureCoachmarkUiState.Hidden, controller.uiState.value)
            assertEquals(1, store.commitCount)
            assertNull("successful in-flight release must not re-offer", controller.tryClaim())
        }
    }

    @Test
    fun releaseDuringInFlightCommit_reoffersOnlyAfterWriteFailure() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val store = BlockingStore()

        runTest(dispatcher) {
            val controller = controller(store, dispatcher, this)
            advanceUntilIdle()
            val claimId = checkNotNull(controller.tryClaim())

            controller.markPresented(claimId)
            runCurrent()
            controller.release(claimId)
            assertNull(controller.tryClaim())

            // Mutation target: treating any completed persistence attempt as a
            // success would permanently lose the lesson after an IO failure.
            store.commitGate.complete(false)
            advanceUntilIdle()

            assertEquals(VoiceGestureCoachmarkUiState.Ready, controller.uiState.value)
            assertEquals(1, store.commitCount)
            assertTrue("failed write must make the lesson claimable again", controller.tryClaim() != null)
        }
    }

    @Test
    fun dismissDuringInFlightCommit_staysHiddenAfterSuccessfulWrite() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val store = BlockingStore()

        runTest(dispatcher) {
            val controller = controller(store, dispatcher, this)
            advanceUntilIdle()
            val claimId = checkNotNull(controller.tryClaim())

            controller.markPresented(claimId)
            runCurrent()
            controller.dismiss()
            assertEquals(
                VoiceGestureCoachmarkUiState.Persisting(
                    claimId = claimId,
                    showWhenCommitted = false,
                ),
                controller.uiState.value,
            )

            // Mutation target: completing a dismissed claim as Presented would
            // resurrect the coachmark after its close action.
            store.commitGate.complete(true)
            advanceUntilIdle()

            assertEquals(VoiceGestureCoachmarkUiState.Hidden, controller.uiState.value)
            assertEquals(1, store.commitCount)
            assertNull(controller.tryClaim())
        }
    }

    @Test
    fun releaseAfterPresentation_hidesDurablyPresentedCoachmark() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val store = FakeStore()

        runTest(dispatcher) {
            val controller = controller(store, dispatcher, this)
            advanceUntilIdle()
            val claimId = checkNotNull(controller.tryClaim())
            controller.markPresented(claimId)
            advanceUntilIdle()
            assertEquals(VoiceGestureCoachmarkUiState.Presented(claimId), controller.uiState.value)

            // Eligibility can disappear after the first presented frame. The
            // host calls release() and must hide this already-durable claim.
            controller.release(claimId)
            assertEquals(VoiceGestureCoachmarkUiState.Hidden, controller.uiState.value)
            assertNull(controller.tryClaim())
        }
    }

    @Test
    fun dismissalBeforeNextFrameIsDurable() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val store = FakeStore()

        runTest(dispatcher) {
            val controller = controller(store, dispatcher, this)
            advanceUntilIdle()
            checkNotNull(controller.tryClaim())

            controller.dismiss()
            advanceUntilIdle()

            assertTrue(controller.uiState.value is VoiceGestureCoachmarkUiState.Hidden)
            assertEquals(1, store.commitCount)
        }
    }

    @Test
    fun failedPersistenceReturnsToReady_soEducationIsNotLost() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val store = FakeStore(commitResult = false)

        runTest(dispatcher) {
            val controller = controller(store, dispatcher, this)
            advanceUntilIdle()
            val claimId = checkNotNull(controller.tryClaim())

            controller.markPresented(claimId)
            advanceUntilIdle()

            assertTrue(controller.uiState.value is VoiceGestureCoachmarkUiState.Ready)
            assertEquals(1, store.commitCount)
        }
    }

    private fun controller(
        store: VoiceGestureHintStore,
        dispatcher: TestDispatcher,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = VoiceGestureCoachmarkController(
        store = store,
        ioDispatcher = dispatcher,
        mainDispatcher = dispatcher,
        scope = scope,
    )

    private class FakeStore(
        private val commitResult: Boolean = true,
    ) : VoiceGestureHintStore {
        var version: Int = 0
        var commitCount: Int = 0

        override suspend fun presentedVersion(): Int = version

        override suspend fun commitPresentedVersion(version: Int): Boolean {
            commitCount += 1
            if (commitResult) this.version = version
            return commitResult
        }
    }

    private class BlockingStore : VoiceGestureHintStore {
        var version: Int = 0
        var commitCount: Int = 0
        val commitStarted = CompletableDeferred<Unit>()
        val commitGate = CompletableDeferred<Boolean>()

        override suspend fun presentedVersion(): Int = version

        override suspend fun commitPresentedVersion(version: Int): Boolean {
            commitCount += 1
            commitStarted.complete(Unit)
            val result = commitGate.await()
            if (result) this.version = version
            return result
        }
    }
}
