package com.pocketshell.app.composer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.settings.VoiceTranscriptionProvider
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

/**
 * Issue #1531 — "Attachment silently dropped on send." This covers the
 * VISIBILITY + swallowed-error half of the audit (RC1 badge helper, RC2 staging
 * swallows, RC3 stranded-`Uploading` recovery). The #1532-fixed re-dispatch lane
 * is guarded here (must still deliver exactly once), not re-implemented.
 *
 * D33/G10 reproduce-first: each swallow test asserts the SILENT drop on base (no
 * error, no state change) and the VISIBLE state with the fix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PromptComposerAttachmentSilentDropTest {

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
        override fun save(key: CharArray) { this.key = key.copyOf() }
        override fun load(): CharArray? = key?.copyOf()
        override fun clear() { key = null }
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
        outboundQueueStore: OutboundQueueStore = DisabledOutboundQueueStore,
        outboundAttachmentSidecarStore: OutboundAttachmentSidecarStore? = null,
        clock: () -> Long = { System.currentTimeMillis() },
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): PromptComposerViewModel {
        val vm = PromptComposerViewModel(
            audioRecorder = FakeMicCapture(),
            whisperClientFactory = WhisperClientFactory { null },
            apiKeyStorage = FakeVault().also { it.save("sk-test".toCharArray()) },
            voiceSettings = FakeVoiceSettings(),
            speechRecognitionProvider = UnavailableSpeechRecognitionProvider,
            composerDraftStore = DisabledComposerDraftStore,
            outboundQueueStore = outboundQueueStore,
            outboundAttachmentSidecarStore = outboundAttachmentSidecarStore,
            savedStateHandle = savedStateHandle,
        )
        if (samplerDispatcher != null) {
            vm.samplerDispatcher = samplerDispatcher
            vm.outboundQueueDispatcher = samplerDispatcher
        }
        vm.clock = clock
        createdViewModels += vm
        vm.setSendWatchdogTimeoutForTest(null)
        return vm
    }

    private fun TestScope.collectSendRequests(
        vm: PromptComposerViewModel,
    ): MutableList<PromptComposerViewModel.SendRequest> {
        val collected = java.util.Collections.synchronizedList(
            mutableListOf<PromptComposerViewModel.SendRequest>(),
        )
        backgroundScope.launch { vm.sendRequests.collect { collected += it } }
        runCurrent()
        return collected
    }

    private fun newSidecarStore(
        ioDispatcher: TestDispatcher,
        context: Context = ApplicationProvider.getApplicationContext(),
    ): OutboundAttachmentSidecarStore {
        context.getSharedPreferences(OutboundAttachmentSidecarStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(context.filesDir, OutboundAttachmentSidecarStore.DIRECTORY_NAME).deleteRecursively()
        var nextId = 0
        return OutboundAttachmentSidecarStore(context).also { store ->
            store.idGenerator = { "sidecar-${++nextId}" }
            store.clock = { nextId.toLong() }
            store.ioDispatcher = ioDispatcher
        }
    }

    private fun localAttachmentFile(name: String, content: String): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return File(context.cacheDir, name).apply {
            parentFile?.mkdirs()
            writeText(content)
        }
    }

    private suspend fun TestScope.advanceSchedulerUntil(
        predicate: suspend () -> Boolean,
        timeoutMs: Long = 40_000L,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            advanceUntilIdle()
            if (predicate()) return true
            runCurrent()
            if (predicate()) return true
            advanceTimeBy(1L)
            runCurrent()
            if (predicate()) return true
            withContext(Dispatchers.IO) { Thread.sleep(1L) }
            if (predicate()) return true
            if (System.currentTimeMillis() >= deadline) {
                advanceUntilIdle()
                return predicate()
            }
        }
    }

    private suspend fun TestScope.settleUntil(predicate: () -> Boolean) {
        assertTrue(
            "settleUntil timed out before the predicate held",
            advanceSchedulerUntil(predicate = { predicate() }),
        )
    }

    // ---- RC2a: attachFiles busy-swallow -------------------------------------

    @Test
    fun attachFilesWhilePriorUploadActiveSurfacesVisibleErrorNotSilentDrop() = runTest {
        // RED on base: `attachFiles` did `if (attachmentJob?.isActive == true) return`
        // — a second pick during an in-flight (up to 90s) upload vanished with NO
        // error and NO state change. GREEN: the pick is rejected VISIBLY with a
        // reason, so the tap is never a no-op mystery.
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        vm.onComposerTargetChanged("1/session-a")

        // First pick: a stage that never resolves keeps `attachmentJob` active.
        val local1 = localAttachmentFile("first.txt", "first")
        vm.attachFiles(
            count = 1,
            previews = listOf(PromptComposerViewModel.AttachmentPreview(Uri.fromFile(local1), "text/plain")),
        ) {
            awaitCancellation()
        }
        runCurrent()
        assertEquals(
            "the first upload must be in flight for the busy path to be exercised",
            PromptComposerViewModel.AttachmentUploadState.Uploading(1),
            vm.uiState.value.attachmentUpload,
        )
        // The Uploading state also cleared any prior error; confirm the baseline.
        assertNull(vm.uiState.value.error)

        // Second pick arrives while the first upload is still active.
        val local2 = localAttachmentFile("second.txt", "second")
        vm.attachFiles(
            count = 1,
            previews = listOf(PromptComposerViewModel.AttachmentPreview(Uri.fromFile(local2), "text/plain")),
        ) {
            error("the busy path must NOT invoke the second stage lambda")
        }
        runCurrent()

        // The load-bearing assertion: the second pick surfaces a visible reason
        // instead of vanishing silently.
        assertEquals(
            PromptComposerViewModel.ATTACHMENT_UPLOAD_BUSY_MESSAGE,
            vm.uiState.value.error,
        )
    }

    // ---- RC2b: emitSendRequest busy-swallow ---------------------------------

    @Test
    fun sendWhileSidecarDispatchInFlightSurfacesVisibleErrorNotSilentNoOp() = runTest {
        // RED on base: `emitSendRequest` did `if (outboundSidecarDispatchInFlight)
        // return`. During a queued-row sidecar retry upload (up to 90s)
        // `sendInFlight` is FALSE, so the Send button is ENABLED and a tap did
        // nothing and said nothing. GREEN: the tap surfaces a visible reason; the
        // draft is untouched so the user can Send again once the upload settles.
        val queue = InMemoryOutboundQueueStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sidecars = newSidecarStore(ioDispatcher = dispatcher)
        // A queued row with a staged local sidecar whose upload never resolves —
        // holding `outboundSidecarDispatchInFlight` true through the send attempt.
        val row = OutboundItem(
            id = "queued-stuck-upload",
            sessionKey = "1/session-a",
            cleanText = "queued attachment",
            attachments = listOf(DurableAttachmentRef("stale-local", "stuck.txt", "text/plain")),
            createdAtMs = 1L,
        )
        queue.enqueueExisting(row)
        sidecars.stage(
            outboundItemId = row.id,
            uris = listOf(Uri.fromFile(localAttachmentFile("stuck.txt", "stuck bytes"))),
        )
        val vm = newVm(
            samplerDispatcher = dispatcher,
            outboundQueueStore = queue,
            outboundAttachmentSidecarStore = sidecars,
        )
        // Hold the upload leg in flight with a gate we never complete — driven by
        // `runCurrent()` (NOT `advanceUntilIdle`), so virtual time never advances
        // past the 90s `withTimeout` around the uploader. The
        // `outboundSidecarDispatchInFlight` latch stays true while the uploader
        // awaits the gate, which is the exact wedged-upload window the guard covers.
        val uploadGate = CompletableDeferred<Result<List<String>>>()
        var uploadStarted = false
        vm.setOutboundAttachmentSidecarUploader {
            uploadStarted = true
            uploadGate.await()
        }
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")

        assertEquals(row.id, vm.retryNextOutboundItem())
        runCurrent()
        assertTrue("the sidecar upload leg must be in flight for the guard to apply", uploadStarted)
        assertFalse("the flush lane does not set sendInFlight", vm.uiState.value.sendInFlight)

        // Now the user types a NEW prompt and taps Send while the upload is stuck.
        vm.onDraftChange("a brand new prompt")
        assertNull("baseline: no error before the send tap", vm.uiState.value.error)
        vm.requestSend(withEnter = true)
        runCurrent()

        // Load-bearing: the tap surfaced a visible reason, the draft is intact, and
        // NO new send went out silently.
        assertEquals(
            PromptComposerViewModel.SEND_BUSY_UPLOADING_MESSAGE,
            vm.uiState.value.error,
        )
        assertEquals("a brand new prompt", vm.uiState.value.draft)
        assertTrue("no new send may be emitted while the upload is wedged", sent.isEmpty())

        // Release the wedged upload so the VM teardown doesn't leak a live coroutine.
        uploadGate.cancel()
    }

    // ---- RC3: stranded-Uploading recovery -----------------------------------

    @Test
    fun requeueStaleOutboundInFlightReArmsStrandedUploadingRowWithoutWindowFlip() = runTest {
        // RC3 recovery primitive: a row stranded in `Uploading` (process death
        // mid-upload) past the stale cutoff is re-armed to `Queued` so the retry
        // lane can claim it — WITHOUT a connection-window flip. The poll lane wires
        // this in (covered end-to-end in TmuxSessionScreenTest); here we prove the
        // primitive re-arms the stranded row and leaves a genuinely-active (recent)
        // upload untouched (class coverage).
        val now = 1_000_000L
        val queue = InMemoryOutboundQueueStore()
        val stranded = OutboundItem(
            id = "stranded-uploading",
            sessionKey = "1/session-a",
            cleanText = "stranded",
            attachments = listOf(DurableAttachmentRef("local", "s.txt", "text/plain")),
            state = OutboundState.Uploading,
            createdAtMs = 1L,
            lastAttemptAtMs = now - PromptComposerViewModel.OUTBOUND_IN_FLIGHT_STALE_MS - 1L,
        )
        val fresh = OutboundItem(
            id = "fresh-uploading",
            sessionKey = "1/session-a",
            cleanText = "fresh",
            attachments = listOf(DurableAttachmentRef("local2", "f.txt", "text/plain")),
            state = OutboundState.Uploading,
            createdAtMs = 2L,
            lastAttemptAtMs = now, // active right now — must NOT be swept
        )
        queue.enqueueExisting(stranded)
        queue.enqueueExisting(fresh)
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
            clock = { now },
        )
        vm.onComposerTargetChanged("1/session-a")

        val requeued = vm.requeueStaleOutboundInFlight()

        assertEquals(listOf("stranded-uploading"), requeued.map { it.id })
        assertEquals(OutboundState.Queued, queue.item("stranded-uploading")!!.state)
        assertEquals(
            "a genuinely-active upload must stay Uploading, not be swept",
            OutboundState.Uploading,
            queue.item("fresh-uploading")!!.state,
        )
    }

    // ---- #1532 guard: re-dispatch still delivers exactly once ---------------

    @Test
    fun deferredRowReDispatchStillDeliversExactlyOnce() = runTest {
        // Guard (not regress) the merged #1532/#1526 re-dispatch: a deferred row
        // re-claimed by the flush must produce exactly ONE more delivery for the
        // same logical prompt — the verify-before-resend ledger prevents a double.
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
        )
        val sent = collectSendRequests(vm)
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/session-a")

        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("exactly once")
        vm.requestSend(withEnter = true, sendTarget = target)
        advanceUntilIdle()
        val first = sent.single()

        vm.markOutboundSendDeferred(first)
        assertEquals(OutboundState.Queued, queue.itemsFor("1/session-a").single().state)

        val flushedId = vm.retryNextOutboundItem()
        advanceUntilIdle()
        settleUntil { sent.size >= 2 }

        assertEquals(first.outboundQueueItemId, flushedId)
        assertEquals("the flush must re-claim the SAME row, not mint a second", 2, sent.size)
        assertEquals(1, queue.itemsFor("1/session-a").size)
        vm.markSendDelivered(sent.last())
        assertTrue(vm.outboundQueueItems.value.isEmpty())
    }

    // ---- Finding 2 (G10/AC1): ATTACHMENT lane exactly-once across a flap -----

    @Test
    fun deferredAttachmentRowReDispatchDeliversAttachmentExactlyOnce() = runTest {
        // The reported bug (AC1) is an ATTACHMENT lost across a transport
        // drop/flap. The text guard above does NOT drive an attachment through
        // the deferred/re-dispatch lane. This carries a REAL staged attachment
        // (a tile with a remote path) through: send -> defer (the flap, Option A)
        // -> reconnect auto-flush re-dispatch, and asserts the attachment is
        // delivered EXACTLY ONCE — never dropped, never doubled.
        //
        // Attachments ride the SAME probed agent/paste delivery lane as text
        // (they travel as remote paths appended to the payload — `appendAttachmentPaths`),
        // so the #1526 S1 verify-before-resend ledger that the text E2E
        // `OutboundExactlyOnceAcrossFlapE2eTest` proves at the WIRE level applies
        // identically (audit: "the paste leg: YES [covered by the ledger]"). This
        // JVM proof gives this issue's exact ATTACHMENT scenario a triggering,
        // per-PR-gated test on the deferred/re-dispatch lane.
        val queue = InMemoryOutboundQueueStore()
        val attachPath = "~/.pocketshell/attachments/session-a/20260713-photo.png"
        // The post-flap state: the initial attachment send hit an ambiguous
        // mid-send failure and DEFERRED (Option A) — the row is kept Queued (NOT
        // dropped), carrying the attachment ref. This is the exact point the
        // attachment used to silently vanish; here it survives as a durable row.
        val deferred = OutboundItem(
            id = "deferred-attachment",
            sessionKey = "1/session-a",
            cleanText = "please review this photo",
            attachments = listOf(DurableAttachmentRef(attachPath, "20260713-photo.png", "image/png")),
            state = OutboundState.Queued,
            withEnter = true,
            createdAtMs = 1L,
        )
        queue.enqueueExisting(deferred)
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
        )
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")

        // The reconnect auto-flush re-claims the SAME deferred row and
        // re-dispatches the attachment.
        val flushedId = vm.retryNextOutboundItem()
        advanceUntilIdle()
        settleUntil { sent.isNotEmpty() }
        assertEquals(deferred.id, flushedId)

        // Exactly-once: exactly ONE re-dispatch carrying the SAME single
        // attachment path once — no duplicate attachment on the wire.
        assertEquals(
            "the flush must re-claim the SAME attachment row, not mint a second",
            1,
            sent.size,
        )
        val redispatched = sent.single()
        assertEquals(listOf(attachPath), redispatched.attachments.map { it.remotePath })
        assertEquals(
            "the attachment path must appear EXACTLY ONCE in the re-dispatched payload",
            1,
            countOccurrences(redispatched.text, attachPath),
        )
        assertEquals(deferred.id, redispatched.outboundQueueItemId)
        assertEquals(1, queue.itemsFor("1/session-a").size)

        // Deliver it: the row is pruned, a further flush produces NO second send —
        // the attachment is delivered exactly once, never resurrected.
        vm.markSendDelivered(redispatched)
        assertTrue(vm.outboundQueueItems.value.isEmpty())
        assertNull(
            "a delivered attachment row must not be re-dispatchable (exactly-once)",
            vm.retryNextOutboundItem(),
        )
        advanceUntilIdle()
        assertEquals("no second delivery may occur after the row is delivered", 1, sent.size)
    }

    // ---- Finding 3b (G2/AC2): staging FAILURE surfaces a visible reason ------

    @Test
    fun attachStagingFailureResultSurfacesVisibleReasonNotSilentDrop() = runTest {
        // Class coverage (AC2): a staging FAILURE (`stage()` returns
        // `Result.failure`, e.g. the SFTP upload leg failed on a flaky link) must
        // produce a VISIBLE reason in the composer, never a silent drop. Assert
        // both the load-bearing property (a non-null error is surfaced) AND that
        // the error carries the failure detail + the "draft kept" guidance, and
        // that the upload progress is reset (not stuck "Uploading" forever).
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("keep my draft")
        assertNull("baseline: no error before the pick", vm.uiState.value.error)

        vm.attachFiles(count = 1) {
            Result.failure(com.pocketshell.core.ssh.SshException("upload leg failed"))
        }
        advanceUntilIdle()

        val error = vm.uiState.value.error
        assertNotNull("a staging failure must surface a VISIBLE error, not vanish", error)
        assertTrue(
            "the surfaced reason must describe the upload failure; was: $error",
            error!!.contains("Attachment upload failed"),
        )
        // The progress spinner must not be stranded — the pick settled to Idle.
        assertEquals(
            PromptComposerViewModel.AttachmentUploadState.Idle,
            vm.uiState.value.attachmentUpload,
        )
        // The draft is kept so the user can re-attach + retry (no lost work).
        assertEquals("keep my draft", vm.uiState.value.draft)
        // No attachment tile was silently added on the failed pick.
        assertTrue(vm.uiState.value.attachments.isEmpty())
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count += 1
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    // ---- RC1: docked-launcher badge helper ----------------------------------

    @Test
    fun outboundLauncherBadgeSummarisesUndeliveredRowsForSession() = runTest {
        val session = "1/session-a"
        val other = "1/session-b"
        val items = listOf(
            OutboundItem(id = "q", sessionKey = session, cleanText = "q", state = OutboundState.Queued, createdAtMs = 1L),
            OutboundItem(id = "u", sessionKey = session, cleanText = "u", state = OutboundState.Uploading, createdAtMs = 2L),
            OutboundItem(id = "d", sessionKey = session, cleanText = "d", state = OutboundState.Delivered, createdAtMs = 3L),
            OutboundItem(id = "other", sessionKey = other, cleanText = "x", state = OutboundState.Failed, createdAtMs = 4L),
        )

        val badge = items.outboundLauncherBadge(session)
        assertEquals(
            "delivered rows and other-session rows are excluded; 2 undelivered remain",
            OutboundLauncherBadge(count = 2, hasFailure = false),
            badge,
        )
    }

    @Test
    fun outboundLauncherBadgeFlagsFailureAndIsNullWhenEmpty() = runTest {
        val session = "1/session-a"
        assertNull(
            "an empty (or all-delivered) queue yields no badge",
            listOf(
                OutboundItem(id = "d", sessionKey = session, cleanText = "d", state = OutboundState.Delivered, createdAtMs = 1L),
            ).outboundLauncherBadge(session),
        )
        val failed = listOf(
            OutboundItem(id = "q", sessionKey = session, cleanText = "q", state = OutboundState.Queued, createdAtMs = 1L),
            OutboundItem(id = "f", sessionKey = session, cleanText = "f", state = OutboundState.Failed, createdAtMs = 2L),
        ).outboundLauncherBadge(session)
        assertEquals(OutboundLauncherBadge(count = 2, hasFailure = true), failed)
    }
}
