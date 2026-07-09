package com.pocketshell.app.composer

import androidx.lifecycle.SavedStateHandle
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.settings.VoiceTranscriptionProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PromptComposerAttachmentRecoveryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val createdViewModels = mutableListOf<PromptComposerViewModel>()

    @After
    fun tearDownViewModels() {
        createdViewModels.forEach { it.clearForTest() }
        createdViewModels.clear()
    }

    private class FakeVault(initial: CharArray? = null) : ApiKeyVault {
        private var key: CharArray? = initial?.copyOf()
        override fun save(key: CharArray) {
            this.key = key.copyOf()
        }
        override fun load(): CharArray? = key?.copyOf()
        override fun clear() {
            key = null
        }
    }

    private class FakeMicCapture : PromptComposerViewModel.MicCapture {
        override fun start() = Unit
        override fun stop(): ByteArray = byteArrayOf(1)
        override fun currentAmplitude(): Float = 0f
    }

    private class FakeVoiceSettings : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
        override fun whisperLanguageHint(): String? = null
        override fun transcriptionProvider(): VoiceTranscriptionProvider =
            VoiceTranscriptionProvider.OpenAiWhisper
    }

    private fun newVm(
        samplerDispatcher: TestDispatcher? = null,
        composerDraftStore: ComposerDraftStore = DisabledComposerDraftStore,
    ): PromptComposerViewModel {
        val vm = PromptComposerViewModel(
            audioRecorder = FakeMicCapture(),
            whisperClientFactory = WhisperClientFactory { null },
            apiKeyStorage = FakeVault().also { it.save("sk-test".toCharArray()) },
            voiceSettings = FakeVoiceSettings(),
            speechRecognitionProvider = UnavailableSpeechRecognitionProvider,
            composerDraftStore = composerDraftStore,
            outboundQueueStore = DisabledOutboundQueueStore,
            savedStateHandle = SavedStateHandle(),
        )
        if (samplerDispatcher != null) {
            vm.samplerDispatcher = samplerDispatcher
            vm.outboundQueueDispatcher = samplerDispatcher
        }
        createdViewModels += vm
        vm.setSendWatchdogTimeoutForTest(null)
        return vm
    }

    @Test
    fun failedAttachmentSendPreservesAttachmentPathsForResend() = runTest {
        // Issue #694: the maintainer's "I can't send attachments anymore"
        // turned out to be a degraded-connection "Not sent" failure. The
        // worry is that the attachments get SILENTLY DROPPED on the failed
        // send so the reconnect-then-resend goes out without them.
        //
        // The composer folds the staged attachment paths into the outgoing
        // prompt's "Attached files:" suffix at send time. When the host
        // reports the send failed, [restoreFailedSend] must put that EXACT
        // payload (text + attachment paths) back in the draft so a resend
        // after reconnect still carries the attachments — not a silent drop.
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        vm.onDraftChange("Review these")
        vm.attachFiles(count = 2) {
            Result.success(
                listOf(
                    "~/.pocketshell/attachments/host-1/20260611-120000-01-report.txt",
                    "~/.pocketshell/attachments/host-1/20260611-120000-02-data.csv",
                ),
            )
        }
        advanceUntilIdle()

        // First send: capture the composed prompt the sheet would route to
        // the host's `onSend`.
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        val job: Job = launch { vm.sendRequests.collect { sent += it } }
        vm.requestSend(withEnter = true)
        advanceUntilIdle()
        job.cancelAndJoin()

        assertEquals(1, sent.size)
        val composed = sent[0]
        // The composed prompt carries both files.
        assertTrue(composed.text.contains("20260611-120000-01-report.txt"))
        assertTrue(composed.text.contains("20260611-120000-02-data.csv"))
        // Issue #971: in-flight — the prompt + chips were HANDED OFF, so the
        // editor is cleared (single representation); `sendInFlight` is true while
        // awaiting the host. The clean draft + tiles still travel in the request.
        assertEquals("", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.attachments.isEmpty())
        assertTrue(vm.uiState.value.sendInFlight)

        // The host reports the write failed (degraded connection). The sheet
        // routes the exact request back into [restoreFailedSend].
        //
        // Issue #872 — close the #832 AC2 gap: restoreFailedSend now restores the
        // CLEAN draft + the actual attachment TILES (not the paths folded into
        // text). The user sees their prompt and the file tiles again — exactly
        // what they had before tapping Send.
        vm.restoreFailedSend(composed)
        assertFalse(vm.uiState.value.sendInFlight)

        // The restored draft is the clean text — NOT polluted with raw remote
        // paths — and the two attachment tiles are back on screen.
        assertEquals("Review these", vm.uiState.value.draft)
        assertEquals(2, vm.uiState.value.attachments.size)
        assertEquals(
            listOf(
                "~/.pocketshell/attachments/host-1/20260611-120000-01-report.txt",
                "~/.pocketshell/attachments/host-1/20260611-120000-02-data.csv",
            ),
            vm.uiState.value.attachments.map { it.remotePath },
        )
        assertEquals(
            "Not sent. Reconnect, then send again or discard the draft.",
            vm.uiState.value.error,
        )

        // Resend after reconnect: the tiles are still present, so
        // [dispatchSendNow] re-appends both attachment paths from the live tiles
        // at send time — the resend STILL includes both attachments, never a
        // silent drop, and the agent receives the exact same composed prompt.
        val resent = mutableListOf<PromptComposerViewModel.SendRequest>()
        val resendJob: Job = launch { vm.sendRequests.collect { resent += it } }
        vm.requestSend(withEnter = true)
        advanceUntilIdle()
        resendJob.cancelAndJoin()

        assertEquals(1, resent.size)
        assertEquals(composed.text, resent[0].text)
        assertTrue(resent[0].text.contains("20260611-120000-01-report.txt"))
        assertTrue(resent[0].text.contains("20260611-120000-02-data.csv"))
        assertEquals(2, resent[0].attachments.size)
    }

    // -- Issue #872: durable per-session STAGED ATTACHMENT refs ----------------

    @Test
    fun failedSendAttachmentSurvivesSwitchAwayAndBack() = runTest {
        // Issue #872 (the maintainer's exact dogfood scenario, attachment half):
        // attach a file, type a note, tap Send, the send fails (a spurious flap /
        // genuine drop) -> the composer keeps the draft AND the attachment tile.
        // The user switches to another session and returns — and the ATTACHMENT
        // (not just the text) MUST still be there. Before #872 the staged tiles
        // were explicitly dropped (`attachments = emptyList()`) on every switch,
        // so the attachment was unrecoverable through the UI even though the text
        // survived (#832 AC2 gap).
        //
        // RED without the fix: the switch back found ZERO attachment tiles (the
        // old onComposerTargetChanged dropped them). GREEN: the durable store
        // reloads session A's staged attachment.
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged("1/sessionA")
        vm.onDraftChange("please review")
        vm.attachFiles(count = 1) {
            Result.success(listOf("~/.pocketshell/attachments/host-1/shot.png"))
        }
        advanceUntilIdle()

        // Capture the composed SendRequest the sheet would route, then fail it.
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        val job: Job = launch { vm.sendRequests.collect { sent += it } }
        vm.requestSend(withEnter = true)
        advanceUntilIdle()
        job.cancelAndJoin()
        assertEquals(1, sent.size)

        vm.restoreFailedSend(sent[0])
        // The failed send kept the clean draft AND the attachment tile.
        assertEquals("please review", vm.uiState.value.draft)
        assertEquals(1, vm.uiState.value.attachments.size)
        assertNotNull(vm.uiState.value.error)
        // Both are durably persisted under session A.
        assertEquals("please review", store.load("1/sessionA"))
        assertEquals(
            listOf("~/.pocketshell/attachments/host-1/shot.png"),
            store.loadAttachments("1/sessionA").map { it.remotePath },
        )

        // Switch to session B: A's attachment must NOT bleed into B.
        vm.onComposerTargetChanged("1/sessionB")
        assertEquals("", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.attachments.isEmpty())

        // Return to session A: the kept draft AND the attachment tile are back.
        vm.onComposerTargetChanged("1/sessionA")
        assertEquals("please review", vm.uiState.value.draft)
        assertEquals(
            listOf("~/.pocketshell/attachments/host-1/shot.png"),
            vm.uiState.value.attachments.map { it.remotePath },
        )

        // And a resend after the round-trip still carries the attachment.
        val resent = mutableListOf<PromptComposerViewModel.SendRequest>()
        val resendJob: Job = launch { vm.sendRequests.collect { resent += it } }
        vm.requestSend(withEnter = true)
        advanceUntilIdle()
        resendJob.cancelAndJoin()
        assertEquals(1, resent.size)
        assertEquals(1, resent[0].attachments.size)
        assertTrue(resent[0].text.contains("shot.png"))
    }

    @Test
    fun deliveredSendClearsTheDurableAttachmentSlot() = runTest {
        // Issue #872: a confirmed delivery must drop the durable attachment refs
        // too (the sibling of clearing the text slot), so a later switch back
        // does not resurrect already-sent tiles.
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged("1/a")
        vm.onDraftChange("about to send")
        vm.attachFiles(count = 1) {
            Result.success(listOf("~/.pocketshell/attachments/host-1/sent.png"))
        }
        advanceUntilIdle()
        // Persist the staged attachment via a failed-then-... no: simulate the
        // durable persistence by failing once so the slot is written.
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        val job: Job = launch { vm.sendRequests.collect { sent += it } }
        vm.requestSend(withEnter = true)
        advanceUntilIdle()
        job.cancelAndJoin()
        vm.restoreFailedSend(sent[0])
        assertEquals(
            listOf("~/.pocketshell/attachments/host-1/sent.png"),
            store.loadAttachments("1/a").map { it.remotePath },
        )

        // Now the (re)send is delivered: the durable attachment slot is cleared.
        vm.requestSend(withEnter = true)
        advanceUntilIdle()
        vm.markSendDelivered()
        assertTrue(store.loadAttachments("1/a").isEmpty())

        vm.onComposerTargetChanged("1/b")
        vm.onComposerTargetChanged("1/a")
        assertTrue(vm.uiState.value.attachments.isEmpty())
    }

    @Test
    fun discardClearsTheDurableAttachmentSlot() = runTest {
        // Issue #872: Discard must drop the durable attachment slot too, else a
        // switch back resurrects the attachment the user just discarded.
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged("1/a")
        vm.onDraftChange("draft")
        vm.attachFiles(count = 1) {
            Result.success(listOf("~/.pocketshell/attachments/host-1/x.png"))
        }
        advanceUntilIdle()
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        val job: Job = launch { vm.sendRequests.collect { sent += it } }
        vm.requestSend(withEnter = true)
        advanceUntilIdle()
        job.cancelAndJoin()
        vm.restoreFailedSend(sent[0])
        assertFalse(store.loadAttachments("1/a").isEmpty())

        vm.discardDraft()
        assertTrue(store.loadAttachments("1/a").isEmpty())

        vm.onComposerTargetChanged("1/b")
        vm.onComposerTargetChanged("1/a")
        assertTrue(vm.uiState.value.attachments.isEmpty())
        assertEquals("", vm.uiState.value.draft)
    }

    // -- Issue #872 PART B reopen (v0.4.14): the ACTUAL on-device drop ----------
    //
    // The maintainer's exact reopen wording (2026-06-22): "attaching a
    // screenshot, tapping Send — something happens with the connection (a
    // reconnect), the text message goes through but the ATTACHMENT is lost."
    //
    // The text goes through => this is the DELIVERED path, NOT restoreFailedSend.
    // The 91510d0a fix only made restoreFailedSend durable — but the real drop is
    // in dispatchSendNow's upload-in-flight branch: when a reconnect/transport
    // flap slows the attachment SFTP upload so it is STILL in flight when Send
    // fires, the old code CANCELLED the upload and sent text-only, silently
    // eating the attachment (no tile carried, no durable ref, no error). That is
    // why the fix didn't hold on device — the durable path never ran for this
    // trigger.

    @Test
    fun sendWhileUploadStillInFlightDoesNotSilentlyDropTheAttachment() = runTest {
        // REPRODUCES THE REOPEN (RED on base): attach a screenshot whose upload is
        // still in flight (a reconnect/flap slowed the SFTP transfer), type a
        // note, tap Send. On base the upload is cancelled and a TEXT-ONLY request
        // goes out with ZERO attachments — the attachment is silently lost. The
        // fix must instead AWAIT the in-flight upload and dispatch the send WITH
        // the attachment once it stages — so the attachment is never dropped.
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged("1/sessionA")
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        val job: Job = launch { vm.sendRequests.collect { sent += it } }

        // The upload is gated so it is provably still in flight when Send fires
        // (the maintainer's race: reconnect slows the SFTP transfer).
        val uploadStarted = CompletableDeferred<Unit>()
        val uploadResult = CompletableDeferred<Result<List<String>>>()
        vm.attachFiles(count = 1) {
            uploadStarted.complete(Unit)
            uploadResult.await()
        }
        runCurrent()
        uploadStarted.await()
        vm.onDraftChange("look at this screenshot")

        // Tap Send WHILE the upload is still in flight.
        vm.requestSend(withEnter = true)
        runCurrent()

        // No request has gone out yet — the send waits for the upload, instead of
        // firing a text-only request and dropping the attachment.
        assertTrue(
            "Send must not fire a text-only request while the attachment upload " +
                "is still in flight (that is the on-device silent drop).",
            sent.isEmpty(),
        )

        // The upload now finishes (post-reconnect the SFTP transfer completes).
        uploadResult.complete(
            Result.success(listOf("~/.pocketshell/attachments/host-1/shot.png")),
        )
        advanceUntilIdle()
        job.cancelAndJoin()

        // EXACTLY ONE send went out, and it CARRIES the attachment — text AND the
        // attachment path. Base behaviour dropped it (0 attachments, no path).
        assertEquals(1, sent.size)
        assertEquals(1, sent[0].attachments.size)
        assertEquals(
            "~/.pocketshell/attachments/host-1/shot.png",
            sent[0].attachments.single().remotePath,
        )
        assertTrue(
            "The composed prompt must carry the attachment path.",
            sent[0].text.contains("shot.png"),
        )
        assertTrue(sent[0].text.startsWith("look at this screenshot"))
        // Issue #971: once the real dispatch fires (after the upload), the prompt
        // + tile are handed off to the queue row — the editor is cleared (single
        // representation). The attachment still travels in the request, and a
        // failed send restores the tile via [restoreFailedSend].
        assertTrue(vm.uiState.value.attachments.isEmpty())
        assertTrue(vm.uiState.value.sendInFlight)
    }

    @Test
    fun sendWhileUploadInFlightThatThenFailsKeepsAttachmentDurableForRetry() = runTest {
        // CLASS COVERAGE (G2): the SAME race, but the in-flight upload FAILS
        // (genuine drop). The attachment intent must NOT vanish silently — the
        // composer surfaces an error and keeps the typed draft so the user can
        // re-attach + retry, instead of a text-only send sailing through with the
        // attachment gone. On base the upload was cancelled and a text-only send
        // fired (RED: 1 send, 0 attachments, no error).
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged("1/sessionA")
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        val job: Job = launch { vm.sendRequests.collect { sent += it } }

        val uploadStarted = CompletableDeferred<Unit>()
        val uploadResult = CompletableDeferred<Result<List<String>>>()
        vm.attachFiles(count = 1) {
            uploadStarted.complete(Unit)
            uploadResult.await()
        }
        runCurrent()
        uploadStarted.await()
        vm.onDraftChange("look at this")

        vm.requestSend(withEnter = true)
        runCurrent()
        assertTrue("No text-only send may fire while the upload is pending.", sent.isEmpty())

        // The upload fails (the reconnect tore the SFTP transfer down).
        uploadResult.complete(Result.failure(IllegalStateException("transport dropped")))
        advanceUntilIdle()
        job.cancelAndJoin()

        // NO send sailed through silently without the attachment. The draft is
        // kept and an error is surfaced so the user can re-attach and retry.
        assertTrue(
            "A failed attachment upload must NOT let a text-only send sail " +
                "through silently (the on-device 'attachment lost' symptom).",
            sent.isEmpty(),
        )
        assertEquals("look at this", vm.uiState.value.draft)
        assertNotNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.sendInFlight)
    }

    @Test
    fun sendWhileTwoAttachmentsUploadingCarriesBothNotZero() = runTest {
        // CLASS COVERAGE (G2): MULTIPLE attachments uploading when Send fires.
        // Base dropped all of them (text-only). The fix awaits and carries both.
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        val job: Job = launch { vm.sendRequests.collect { sent += it } }

        val uploadStarted = CompletableDeferred<Unit>()
        val uploadResult = CompletableDeferred<Result<List<String>>>()
        vm.attachFiles(count = 2) {
            uploadStarted.complete(Unit)
            uploadResult.await()
        }
        runCurrent()
        uploadStarted.await()
        vm.onDraftChange("review both")

        vm.requestSend(withEnter = true)
        runCurrent()
        assertTrue(sent.isEmpty())

        uploadResult.complete(
            Result.success(
                listOf(
                    "~/.pocketshell/attachments/host-1/a.png",
                    "~/.pocketshell/attachments/host-1/b.png",
                ),
            ),
        )
        advanceUntilIdle()
        job.cancelAndJoin()

        assertEquals(1, sent.size)
        assertEquals(2, sent[0].attachments.size)
        assertTrue(sent[0].text.contains("a.png"))
        assertTrue(sent[0].text.contains("b.png"))
    }
}
