package com.pocketshell.app.composer

import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.composer.PromptComposerViewModel.RecordingState
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.settings.VoiceTranscriptionProvider
import com.pocketshell.app.voice.PendingTranscriptionItem
import com.pocketshell.core.voice.SpeechAudioGuard
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.core.voice.WhisperException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PromptComposerPendingTranscriptionQueueTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val createdViewModels = mutableListOf<PromptComposerViewModel>()

    @After
    fun tearDownViewModels() {
        createdViewModels.forEach { it.clearForTest() }
        createdViewModels.clear()
    }

    @Test
    fun audioIsPersistedBeforeWhisperCall() = runTest {
        val (vm, queue) = newVmWithQueue(
            whisper = fakeWhisperClient { Result.success("hello") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        // Persisted exactly once before the Whisper round-trip.
        assertEquals(1, queue.enqueueCount)
        // Whisper success deleted the row.
        assertEquals(listOf("pending-1"), queue.succeededIds)
        // Draft has the transcript.
        assertEquals("hello", vm.uiState.value.draft)
    }

    @Test
    fun whisperFailureKeepsAudioOnDiskAndBumpsRetryCount() = runTest {
        val (vm, queue) = newVmWithQueue(
            whisper = fakeWhisperClient {
                Result.failure(WhisperException.Transport("offline"))
            },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(1, queue.enqueueCount)
        assertEquals(0, queue.succeededIds.size)
        assertEquals(1, queue.failureIds.size)
        assertEquals("pending-1", queue.failureIds[0].first)
        // Error banner mirrors the failure reason.
        assertNotNull(vm.uiState.value.error)
    }

    @Test
    fun networkTimeoutLeavesTranscribingBeforePendingFailureWriteCompletes() = runTest {
        val queue = FakePendingQueue()
        queue.markFailureStarted = CompletableDeferred()
        queue.markFailureRelease = CompletableDeferred()
        val whisperRelease = CompletableDeferred<Unit>()
        val attachmentPath = "~/.pocketshell/attachments/host-1/keep-this.png"
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                whisperRelease.await()
                return Result.failure(WhisperException.Transport("timeout"))
            }
        }
        val (vm, _) = newVmWithQueue(
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
        )

        vm.onDraftChange("keep this draft")
        vm.seedAttachment(attachmentPath)
        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        assertEquals(RecordingState.Transcribing, vm.uiState.value.recording)

        whisperRelease.complete(Unit)
        runCurrent()
        queue.markFailureStarted!!.await()

        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
        assertEquals("Network error: timeout", vm.uiState.value.error)
        assertEquals("keep this draft", vm.uiState.value.draft)
        assertEquals(
            listOf(
                PromptComposerViewModel.StagedAttachment(
                    remotePath = attachmentPath,
                    displayName = "keep-this.png",
                ),
            ),
            vm.uiState.value.attachments,
        )
        assertNotNull(queue.storedAudio("pending-1"))
        assertTrue(queue.failureIds.isEmpty())

        queue.markFailureRelease!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf("pending-1" to "Network error: timeout"),
            queue.failureIds,
        )
        assertNotNull(queue.storedAudio("pending-1"))
    }

    @Test
    fun offlineSkipsWhisperAndQueuesDirectly() = runTest {
        var whisperCalls = 0
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                whisperCalls++
                return Result.success("never")
            }
        }
        val (vm, queue) = newVmWithQueue(
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            connectivity = FakeConnectivity(initial = false),
        )
        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(1, queue.enqueueCount)
        assertEquals(0, whisperCalls)
        // Banner reads "waiting for network".
        assertEquals(
            PendingTranscriptionItem.NETWORK_WAITING_MESSAGE,
            vm.uiState.value.error,
        )
    }

    @Test
    fun marginalNonSilentGuardRejectionIsQueuedForRetryInsteadOfLost() = runTest {
        // Regression for #587: a real, long capture can land below the
        // conservative speech RMS floor. It must still skip Whisper on the
        // first pass (#452), but the audio should be preserved for retry/export
        // instead of being discarded under an unrecoverable no-speech banner.
        val marginalAudio = SpeechAudioGuard.speechWavForTesting(durationMs = 1_200, amplitude = 0.004f)
        assertFalse(SpeechAudioGuard.hasSpeechEnergy(marginalAudio))
        assertTrue(SpeechAudioGuard.isRecoverableNoSpeechRejection(marginalAudio))

        var whisperCalls = 0
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                whisperCalls++
                return Result.success("should not be called")
            }
        }
        val (vm, queue) = newVmWithQueue(
            mic = FakeMicCapture(audio = marginalAudio),
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(0, whisperCalls)
        assertEquals(1, queue.enqueueCount)
        assertTrue(queue.storedAudio("pending-1")!!.contentEquals(marginalAudio))
        assertEquals(
            PromptComposerViewModel.SUSPICIOUS_NO_SPEECH_RETRY_MESSAGE,
            queue.snapshot().single().lastErrorMessage,
        )
        assertEquals(
            PromptComposerViewModel.SUSPICIOUS_NO_SPEECH_RETRY_MESSAGE,
            vm.uiState.value.error,
        )
        assertEquals(RecordingState.Idle, vm.uiState.value.recording)
    }

    @Test
    fun pureSilenceStillSkipsWhisperAndIsNotQueued() = runTest {
        var whisperCalls = 0
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                whisperCalls++
                return Result.success("시청해주셔서 감사합니다!")
            }
        }
        val (vm, queue) = newVmWithQueue(
            mic = SilentCaptureMic(),
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )

        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(0, whisperCalls)
        assertEquals(0, queue.enqueueCount)
        assertEquals(
            PromptComposerViewModel.NO_SPEECH_DETECTED_MESSAGE,
            vm.uiState.value.error,
        )
    }

    @Test
    fun emptyWhisperSuccessKeepsRealRecordingQueuedForRetry() = runTest {
        val (vm, queue) = newVmWithQueue(
            mic = FakeMicCapture(audio = SpeechAudioGuard.speechWavForTesting()),
            whisper = fakeWhisperClient { Result.success("   \n") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        vm.onDraftChange("typed prompt")

        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(1, queue.enqueueCount)
        assertEquals(emptyList<String>(), queue.succeededIds)
        assertEquals(
            listOf("pending-1" to PromptComposerViewModel.EMPTY_TRANSCRIPTION_RETRY_MESSAGE),
            queue.failureIds,
        )
        assertNotNull(queue.storedAudio("pending-1"))
        assertEquals("typed prompt", vm.uiState.value.draft)
        assertEquals(
            PromptComposerViewModel.EMPTY_TRANSCRIPTION_RETRY_MESSAGE,
            vm.uiState.value.error,
        )
    }

    @Test
    fun hallucinationWhisperSuccessKeepsSpeechLikeRecordingQueuedForRetry() = runTest {
        val (vm, queue) = newVmWithQueue(
            mic = FakeMicCapture(audio = SpeechAudioGuard.speechWavForTesting()),
            whisper = fakeWhisperClient { Result.success("시청해주셔서 감사합니다!") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
        )
        vm.onDraftChange("typed prompt")

        vm.onMicTap()
        runCurrent()
        vm.onMicTap()
        advanceUntilIdle()

        assertEquals(1, queue.enqueueCount)
        assertEquals(emptyList<String>(), queue.succeededIds)
        assertEquals(
            listOf("pending-1" to PromptComposerViewModel.NO_SPEECH_DETECTED_MESSAGE),
            queue.failureIds,
        )
        assertNotNull(queue.storedAudio("pending-1"))
        assertEquals("typed prompt", vm.uiState.value.draft)
        assertEquals(
            PromptComposerViewModel.NO_SPEECH_DETECTED_MESSAGE,
            vm.uiState.value.error,
        )
    }

    @Test
    fun retryPendingSuccessAppendsToDraftAndClearsQueue() = runTest {
        // Seed a queued item without going through the recording path.
        val queue = FakePendingQueue()
        queue.nextId = "retryable"
        queue.enqueueAudio(ByteArray(8) { 1 }, "composer")
        // Bump it once to simulate a prior failure (so the retry path is
        // hit, not the initial-attempt path).
        queue.markFailure("retryable", "Whisper auth failed")

        val (vm, _) = newVmWithQueue(
            whisper = fakeWhisperClient { Result.success("retry transcript") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
        )
        vm.retryPending("retryable")
        advanceUntilIdle()

        assertEquals("retry transcript", vm.uiState.value.draft)
        assertTrue(queue.succeededIds.contains("retryable"))
    }

    @Test
    fun retryPendingFailureBumpsRetryCountAndSurfacesError() = runTest {
        val queue = FakePendingQueue()
        queue.nextId = "retryable"
        queue.enqueueAudio(ByteArray(8) { 1 }, "composer")

        val (vm, _) = newVmWithQueue(
            whisper = fakeWhisperClient { Result.failure(WhisperException.Server("oops", 500)) },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
        )
        vm.retryPending("retryable")
        advanceUntilIdle()

        assertEquals(1, queue.failureIds.size)
        assertEquals("retryable", queue.failureIds[0].first)
        assertNotNull(vm.uiState.value.error)
    }

    @Test
    fun retryPendingEmptyWhisperResponseKeepsAudioQueued() = runTest {
        val queue = FakePendingQueue()
        queue.nextId = "retryable-empty"
        queue.enqueueAudio(SpeechAudioGuard.speechWavForTesting(), "composer")

        val (vm, _) = newVmWithQueue(
            whisper = fakeWhisperClient { Result.success(" \n ") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
        )
        vm.retryPending("retryable-empty")
        advanceUntilIdle()

        assertEquals(
            listOf("retryable-empty" to PromptComposerViewModel.EMPTY_TRANSCRIPTION_RETRY_MESSAGE),
            queue.failureIds,
        )
        assertFalse(queue.succeededIds.contains("retryable-empty"))
        assertNotNull(queue.storedAudio("retryable-empty"))
        assertEquals("", vm.uiState.value.draft)
        assertEquals(
            PromptComposerViewModel.EMPTY_TRANSCRIPTION_RETRY_MESSAGE,
            vm.uiState.value.error,
        )
    }

    @Test
    fun retryPendingAtCapShortCircuits() = runTest {
        val queue = FakePendingQueue()
        queue.nextId = "capped"
        queue.enqueueAudio(ByteArray(8) { 1 }, "composer")
        // Bump to the cap.
        queue.markFailure("capped", "1")
        queue.markFailure("capped", "2")
        queue.markFailure("capped", "3")

        val (vm, _) = newVmWithQueue(
            whisper = fakeWhisperClient { Result.success("never") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
        )
        val priorFailures = queue.failureIds.size
        val priorSuccesses = queue.succeededIds.size
        vm.retryPending("capped")
        advanceUntilIdle()
        // Should not have called Whisper, so neither counter moved.
        assertEquals(priorFailures, queue.failureIds.size)
        assertEquals(priorSuccesses, queue.succeededIds.size)
    }

    @Test
    fun retryPendingWhileOfflineSkipsWhisperAndNudgesUser() = runTest {
        val queue = FakePendingQueue()
        queue.nextId = "offline-retry"
        queue.enqueueAudio(ByteArray(8) { 1 }, "composer")

        var whisperCalls = 0
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                whisperCalls++
                return Result.success("never")
            }
        }
        val (vm, _) = newVmWithQueue(
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
            connectivity = FakeConnectivity(initial = false),
        )
        vm.retryPending("offline-retry")
        advanceUntilIdle()

        assertEquals(0, whisperCalls)
        assertEquals(
            PendingTranscriptionItem.NETWORK_WAITING_MESSAGE,
            vm.uiState.value.error,
        )
    }

    @Test
    fun discardPendingClearsQueueEntry() = runTest {
        val queue = FakePendingQueue()
        queue.nextId = "to-discard"
        queue.enqueueAudio(ByteArray(8) { 1 }, "composer")
        val (vm, _) = newVmWithQueue(queue = queue)
        vm.discardPending("to-discard")
        advanceUntilIdle()
        assertTrue(queue.discardedIds.contains("to-discard"))
    }

    // -- Issue #688: retry idempotency + clear-pending state ----------------

    /**
     * Issue #688 regression: a flaky-network retry must insert the
     * transcript EXACTLY ONCE no matter how many retry triggers overlap.
     *
     * Reproduces the maintainer's dogfood report: a recording fails
     * (Network error: timeout) and lands in the pending queue marked
     * "waiting for network". An auto-retry (foreground-resume) and a manual
     * Retry tap then race the SAME row. Before the fix both round-trips ran
     * to completion and BOTH appended the transcript -> the draft was
     * duplicated. After the fix the second trigger is a no-op while the
     * first is in flight, so the transcript is inserted once.
     *
     * The [GatedWhisperClient] holds every transcribe call open until the
     * test releases it, so the two triggers are provably concurrent.
     */
    @Test
    fun overlappingAutoRetryAndManualRetryInsertTranscriptExactlyOnce() = runTest {
        val queue = FakePendingQueue()
        queue.nextId = "flaky-1"
        // Queued as "waiting for network" so onForegroundResume auto-retries it.
        queue.enqueueAudio(
            ByteArray(8) { 1 },
            "composer",
            initialError = PendingTranscriptionItem.NETWORK_WAITING_MESSAGE,
        )

        val whisper = GatedWhisperClient(result = Result.success("hello world"))
        val (vm, _) = newVmWithQueue(
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
            connectivity = FakeConnectivity(initial = true),
        )

        // Trigger 1: foreground-resume auto-retry for the waiting row.
        vm.onForegroundResume()
        runCurrent()
        // The first round-trip is now open (gated). Trigger 2: the user taps
        // Retry on the very same row while the auto-retry is still in flight.
        vm.retryPending("flaky-1")
        runCurrent()

        // Let every gated transcribe call resolve.
        whisper.releaseAll()
        advanceUntilIdle()

        // Exactly one insertion - the draft is NOT "hello world hello world".
        assertEquals("hello world", vm.uiState.value.draft)
        // The round-trip ran at most once for this row (the duplicate
        // trigger was deduped, not just deduped at insert time).
        assertEquals(1, whisper.callCount)
        assertTrue(queue.succeededIds.contains("flaky-1"))
        // The pending queue is fully cleared - no stale row remains.
        assertTrue(queue.snapshot().isEmpty())
    }

    /**
     * Issue #688: a late success for a row that another retry already
     * inserted (or the user discarded) must be DROPPED, not appended a
     * second time. Simulates the "timeout that actually succeeded
     * server-side" the report calls out: the row is gone by the time the
     * straggler round-trip returns, so its transcript must not land.
     */
    @Test
    fun lateSuccessForAlreadyResolvedRowIsDropped() = runTest {
        val queue = FakePendingQueue()
        queue.nextId = "straggler"
        queue.enqueueAudio(ByteArray(8) { 1 }, "composer")
        queue.markFailure("straggler", "Network error: timeout")

        val whisper = GatedWhisperClient(result = Result.success("late transcript"))
        val (vm, _) = newVmWithQueue(
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
            connectivity = FakeConnectivity(initial = true),
        )

        vm.retryPending("straggler")
        runCurrent()
        // While the retry round-trip is open, the user discards the row
        // (or a sibling resolved it). The store no longer has it.
        queue.markSucceeded("straggler")
        runCurrent()

        // Now the straggler round-trip returns success.
        whisper.releaseAll()
        advanceUntilIdle()

        // The transcript must NOT be inserted - the row was already gone.
        assertEquals("", vm.uiState.value.draft)
        assertTrue(queue.snapshot().isEmpty())
    }

    /**
     * Issue #688: while a retry round-trip is in flight, the row must
     * advertise a visible "retrying" state so the user gets immediate
     * feedback rather than a dead button. Once it resolves the flag clears.
     */
    @Test
    fun retryPublishesRetryingFeedbackWhileInFlight() = runTest {
        val queue = FakePendingQueue()
        queue.nextId = "feedback-1"
        queue.enqueueAudio(ByteArray(8) { 1 }, "composer")
        queue.markFailure("feedback-1", "Network error: timeout")

        val whisper = GatedWhisperClient(result = Result.success("done"))
        val (vm, _) = newVmWithQueue(
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
            connectivity = FakeConnectivity(initial = true),
        )

        vm.retryPending("feedback-1")
        runCurrent()
        // Mid-flight: the row is marked retrying.
        assertTrue(vm.uiState.value.retryingIds.contains("feedback-1"))

        whisper.releaseAll()
        advanceUntilIdle()
        // Resolved: the retrying flag is cleared.
        assertFalse(vm.uiState.value.retryingIds.contains("feedback-1"))
    }

    /**
     * Issue #688: a manual Retry tap while an auto-retry for the same row
     * is already in flight must NOT start a second round-trip. The duplicate
     * tap is a no-op (deduped by the in-flight guard).
     */
    @Test
    fun manualRetryWhileAutoRetryInFlightDoesNotStack() = runTest {
        val queue = FakePendingQueue()
        queue.nextId = "no-stack"
        queue.enqueueAudio(
            ByteArray(8) { 1 },
            "composer",
            initialError = PendingTranscriptionItem.NETWORK_WAITING_MESSAGE,
        )

        val whisper = GatedWhisperClient(result = Result.success("once"))
        val (vm, _) = newVmWithQueue(
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
            connectivity = FakeConnectivity(initial = true),
        )

        vm.onForegroundResume()
        runCurrent()
        repeat(5) {
            vm.retryPending("no-stack")
            runCurrent()
        }
        whisper.releaseAll()
        advanceUntilIdle()

        assertEquals("only one transcribe call may fire for a row", 1, whisper.callCount)
        assertEquals("once", vm.uiState.value.draft)
    }

    /**
     * Issue #688: once the retry cap is hit, nothing auto-inserts. The
     * foreground-resume sweep must not re-fire a capped row, and a manual
     * Retry is gated at the cap.
     */
    @Test
    fun cappedRowNeverAutoInsertsOnForegroundResume() = runTest {
        val queue = FakePendingQueue()
        queue.nextId = "capped-row"
        // Cap via three failures while flagged as a network-waiting row so
        // a naive auto-retry sweep would still try it.
        queue.enqueueAudio(
            ByteArray(8) { 1 },
            "composer",
            initialError = PendingTranscriptionItem.NETWORK_WAITING_MESSAGE,
        )
        queue.markFailure("capped-row", "Network error: timeout")
        queue.markFailure("capped-row", "Network error: timeout")
        queue.markFailure("capped-row", "Network error: timeout")

        var whisperCalls = 0
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                whisperCalls++
                return Result.success("should never insert")
            }
        }
        val (vm, _) = newVmWithQueue(
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
            connectivity = FakeConnectivity(initial = true),
        )

        vm.onForegroundResume()
        advanceUntilIdle()
        vm.retryPending("capped-row")
        advanceUntilIdle()

        assertEquals("a capped row must not call Whisper", 0, whisperCalls)
        assertEquals("", vm.uiState.value.draft)
        // Row is still present in the explicit failed state, audio preserved.
        assertTrue(queue.snapshot().any { it.id == "capped-row" && it.atRetryCap })
        assertNotNull(queue.storedAudio("capped-row"))
    }

    @Test
    fun savePendingAsAudioFileExportsAndSurfacesPath() = runTest {
        val queue = FakePendingQueue()
        queue.nextId = "to-save"
        queue.enqueueAudio(ByteArray(8) { 1 }, "composer")
        val (vm, _) = newVmWithQueue(queue = queue)
        vm.savePendingAsAudioFile("to-save")
        advanceUntilIdle()
        assertTrue(queue.savedIds.contains("to-save"))
        assertEquals(
            "/data/files/voice-exports/to-save.wav",
            vm.uiState.value.savedAudioPath,
        )
        vm.clearSavedAudioConfirmation()
        assertNull(vm.uiState.value.savedAudioPath)
    }

    @Test
    fun foregroundResumeAutoRetriesOnlyWaitingForNetworkItems() = runTest {
        val queue = FakePendingQueue()
        // Two queued rows: one waiting for network (auto-retry candidate),
        // one already failed (manual-only).
        queue.nextId = "offline-one"
        queue.enqueueAudio(
            ByteArray(8),
            "composer",
            initialError = PendingTranscriptionItem.NETWORK_WAITING_MESSAGE,
        )
        queue.nextId = "failed-one"
        queue.enqueueAudio(ByteArray(8), "composer")
        queue.markFailure("failed-one", "Whisper auth failed")

        val (vm, _) = newVmWithQueue(
            whisper = fakeWhisperClient { Result.success("auto-retry success") },
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
        )
        vm.onForegroundResume()
        advanceUntilIdle()

        // Only the offline row was retried successfully.
        assertTrue(queue.succeededIds.contains("offline-one"))
        assertFalse(queue.succeededIds.contains("failed-one"))
    }

    @Test
    fun foregroundResumeNoOpWhenOffline() = runTest {
        val queue = FakePendingQueue()
        queue.nextId = "offline-row"
        queue.enqueueAudio(
            ByteArray(8),
            "composer",
            initialError = PendingTranscriptionItem.NETWORK_WAITING_MESSAGE,
        )

        var whisperCalls = 0
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
                whisperCalls++
                return Result.success("never")
            }
        }
        val (vm, _) = newVmWithQueue(
            whisper = whisper,
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            queue = queue,
            connectivity = FakeConnectivity(initial = false),
        )
        vm.onForegroundResume()
        advanceUntilIdle()
        assertEquals("must not burn quota while offline", 0, whisperCalls)
    }

    private fun newVmWithQueue(
        mic: PromptComposerViewModel.MicCapture = FakeMicCapture(),
        whisper: WhisperClient? = fakeWhisperClient { Result.success("hello world") },
        storage: ApiKeyVault = newStorage().also { it.save("sk-test".toCharArray()) },
        samplerDispatcher: TestDispatcher? = null,
        clock: () -> Long = { System.currentTimeMillis() },
        voiceSettings: PromptComposerViewModel.VoiceSettingsSnapshot = FakeVoiceSettings(),
        queue: FakePendingQueue = FakePendingQueue(),
        connectivity: FakeConnectivity = FakeConnectivity(initial = true),
    ): Pair<PromptComposerViewModel, FakePendingQueue> {
        val factory = WhisperClientFactory { whisper }
        val vm = PromptComposerViewModel(
            audioRecorder = mic,
            whisperClientFactory = factory,
            apiKeyStorage = storage,
            voiceSettings = voiceSettings,
            pendingTranscriptionStore = queue,
            connectivity = connectivity,
        )
        if (samplerDispatcher != null) vm.samplerDispatcher = samplerDispatcher
        vm.clock = clock
        createdViewModels += vm
        vm.setSendWatchdogTimeoutForTest(null)
        return vm to queue
    }

    /**
     * Hand-rolled in-memory [PromptComposerViewModel.PendingTranscriptionQueue].
     * Counts every method call so the FSM tests can assert that the
     * persist-before-Whisper invariant holds and that the success /
     * failure paths route through the store as documented.
     */
    private class FakePendingQueue(
        private val initialAudioMap: MutableMap<String, ByteArray> = mutableMapOf(),
    ) : PromptComposerViewModel.PendingTranscriptionQueue {
        private val flow = MutableStateFlow<List<PendingTranscriptionItem>>(emptyList())
        override val items: Flow<List<PendingTranscriptionItem>> = flow

        var enqueueCount = 0
            private set
        var succeededIds: MutableList<String> = mutableListOf()
        var failureIds: MutableList<Pair<String, String>> = mutableListOf()
        var discardedIds: MutableList<String> = mutableListOf()
        var savedIds: MutableList<String> = mutableListOf()
        var reconcileCount = 0
            private set
        var nextId: String = "pending-1"
        var markFailureStarted: CompletableDeferred<Unit>? = null
        var markFailureRelease: CompletableDeferred<Unit>? = null

        override suspend fun enqueueAudio(
            audio: ByteArray,
            destinationContext: String,
            initialError: String?,
        ): PendingTranscriptionItem? {
            enqueueCount++
            val id = nextId
            initialAudioMap[id] = audio
            val item = PendingTranscriptionItem(
                id = id,
                recordingTimestampMs = 0,
                destinationContext = destinationContext,
                retryCount = 0,
                lastErrorMessage = initialError,
                audioByteSize = audio.size.toLong(),
            )
            flow.value = listOf(item) + flow.value
            return item
        }

        override suspend fun snapshot(): List<PendingTranscriptionItem> = flow.value

        override suspend fun loadAudio(id: String): ByteArray? = initialAudioMap[id]

        fun storedAudio(id: String): ByteArray? = initialAudioMap[id]

        override suspend fun markSucceeded(id: String) {
            succeededIds += id
            initialAudioMap.remove(id)
            flow.value = flow.value.filterNot { it.id == id }
        }

        override suspend fun markFailure(
            id: String,
            errorMessage: String,
        ): PendingTranscriptionItem? {
            markFailureStarted?.complete(Unit)
            markFailureRelease?.await()
            failureIds += id to errorMessage
            val existing = flow.value.firstOrNull { it.id == id } ?: return null
            val updated = existing.copy(
                retryCount = existing.retryCount + 1,
                lastErrorMessage = errorMessage,
            )
            flow.value = flow.value.map { if (it.id == id) updated else it }
            return updated
        }

        override suspend fun discard(id: String) {
            discardedIds += id
            initialAudioMap.remove(id)
            flow.value = flow.value.filterNot { it.id == id }
        }

        override suspend fun saveAsAudioFile(id: String): String? {
            savedIds += id
            initialAudioMap.remove(id)
            flow.value = flow.value.filterNot { it.id == id }
            return "/data/files/voice-exports/$id.wav"
        }

        override suspend fun reconcile() {
            reconcileCount++
        }
    }

    /**
     * Scriptable [PromptComposerViewModel.ConnectivityProbe]. The default
     * mirrors the legacy "always online" behaviour; tests flip the value
     * to exercise the offline-queue path.
     */
    private class FakeConnectivity(initial: Boolean = true) : PromptComposerViewModel.ConnectivityProbe {
        var online: Boolean = initial
        override fun refresh(): Boolean = online
    }

    /**
     * Issue #688: a [WhisperClient] that holds every transcribe call open
     * until [releaseAll] is invoked. Lets the test make two retry triggers
     * provably concurrent (both in-flight at the same time) before either
     * resolves. [callCount] records how many round-trips actually fired, so
     * the test can assert the in-flight guard deduped them.
     */
    private class GatedWhisperClient(
        private val result: Result<String>,
    ) : WhisperClient {
        private val gate = CompletableDeferred<Unit>()

        @Volatile
        var callCount: Int = 0
            private set

        override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
            callCount++
            gate.await()
            return result
        }

        fun releaseAll() {
            if (!gate.isCompleted) gate.complete(Unit)
        }
    }

    /**
     * Scriptable [PromptComposerViewModel.MicCapture]. Records each
     * `start` / `stop` call and serves a programmable amplitude queue
     * for the silence watchdog.
     */
    private class FakeMicCapture(
        private val amplitudes: List<Float> = listOf(0.5f, 0.5f, 0.5f),
        private val audio: ByteArray = SpeechAudioGuard.speechWavForTesting(),
    ) : PromptComposerViewModel.MicCapture {
        var startCount = 0
        var stopCount = 0
        private var ampIndex = 0
        private var running = false

        override fun start() {
            startCount++
            running = true
            ampIndex = 0
        }

        override fun stop(): ByteArray {
            stopCount++
            running = false
            return audio
        }

        override fun currentAmplitude(): Float {
            if (!running) return 0f
            val a = amplitudes.getOrElse(ampIndex) { amplitudes.lastOrNull() ?: 0f }
            ampIndex++
            return a
        }
    }

    /**
     * Mic that captures a silent WAV, passes the energy guard's WAV-shape
     * check, and has zero RMS.
     */
    private class SilentCaptureMic : PromptComposerViewModel.MicCapture {
        var stopCount = 0
        private var running = false
        override fun start() { running = true }
        override fun stop(): ByteArray {
            stopCount++
            running = false
            return SpeechAudioGuard.silentWavForTesting()
        }
        override fun currentAmplitude(): Float = if (running) 0.5f else 0f
    }

    private fun fakeWhisperClient(result: () -> Result<String>): WhisperClient = object : WhisperClient {
        override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> = result()
    }

    private class FakeVault(initial: CharArray? = null) : ApiKeyVault {
        private var key: CharArray? = initial?.copyOf()
        override fun save(key: CharArray) { this.key = key.copyOf() }
        override fun load(): CharArray? = this.key?.copyOf()
        override fun clear() { this.key = null }
    }

    private fun newStorage(): ApiKeyVault = FakeVault()

    private class FakeVoiceSettings(
        private var window: Long = PromptComposerViewModel.SILENCE_WINDOW_MS,
        private var language: String? = null,
        private var provider: VoiceTranscriptionProvider = VoiceTranscriptionProvider.OpenAiWhisper,
    ) : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = window
        override fun whisperLanguageHint(): String? = language
        override fun transcriptionProvider(): VoiceTranscriptionProvider = provider
    }
}
