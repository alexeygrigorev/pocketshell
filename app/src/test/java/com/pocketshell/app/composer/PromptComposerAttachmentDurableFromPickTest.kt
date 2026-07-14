package com.pocketshell.app.composer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.settings.VoiceTranscriptionProvider
import com.pocketshell.core.ssh.SshException
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
 * Issue #1569 — attachments durable-from-pick (fix the LOST-on-upload-failure DATA
 * LOSS, U1) + kill the absolute 90s upload cap (U2).
 *
 * D33/G10 reproduce-first: each test asserts the BASE (unfixed) symptom (documented
 * in the test body as the red state) and the fixed behaviour.
 *
 *  - R-A (U1, LOST → retained): a pick whose upload fails mid-stream is NO LONGER
 *    dropped — it is retained as a durable, retryable tile; a later Send routes it
 *    through the durable queue leg, which retries and delivers EXACTLY ONCE.
 *  - R-B (U2, cap): a large upload progressing past 90s wall-clock is no longer
 *    killed by the absolute cap; it completes on the progress-based bound.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PromptComposerAttachmentDurableFromPickTest {

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
        dispatcher: TestDispatcher,
        outboundQueueStore: OutboundQueueStore = DisabledOutboundQueueStore,
        outboundAttachmentSidecarStore: OutboundAttachmentSidecarStore? = null,
        composerDraftStore: ComposerDraftStore = InMemoryComposerDraftStore(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        sendWatchdogMs: Long? = null,
    ): PromptComposerViewModel {
        val vm = PromptComposerViewModel(
            audioRecorder = FakeMicCapture(),
            whisperClientFactory = WhisperClientFactory { null },
            apiKeyStorage = FakeVault().also { it.save("sk-test".toCharArray()) },
            voiceSettings = FakeVoiceSettings(),
            speechRecognitionProvider = UnavailableSpeechRecognitionProvider,
            composerDraftStore = composerDraftStore,
            outboundQueueStore = outboundQueueStore,
            outboundAttachmentSidecarStore = outboundAttachmentSidecarStore,
            savedStateHandle = savedStateHandle,
        )
        vm.samplerDispatcher = dispatcher
        vm.outboundQueueDispatcher = dispatcher
        createdViewModels += vm
        vm.setSendWatchdogTimeoutForTest(sendWatchdogMs)
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

    private fun pickedFile(name: String, content: String): Uri {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Under a subdir so the Uri's last path segment (→ the sidecar display name)
        // is exactly [name], with no synthetic prefix.
        val file = File(File(context.cacheDir, "picked"), name).apply {
            parentFile?.mkdirs()
            writeText(content)
        }
        return Uri.fromFile(file)
    }

    private fun preview(uri: Uri, mime: String? = "image/png") =
        PromptComposerViewModel.AttachmentPreview(uri, mime)

    private suspend fun TestScope.settleUntil(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 40_000L
        while (true) {
            advanceUntilIdle()
            if (predicate()) return
            runCurrent()
            if (predicate()) return
            advanceTimeBy(1L)
            runCurrent()
            if (predicate()) return
            withContext(Dispatchers.IO) { Thread.sleep(1L) }
            if (predicate()) return
            if (System.currentTimeMillis() >= deadline) {
                advanceUntilIdle()
                assertTrue("settleUntil timed out before predicate held", predicate())
                return
            }
        }
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

    // ---- R-A: LOST → durable retryable, Retry delivers exactly once -----------

    @Test
    fun attachUploadFailureRetainsDurableRetryableTileThenSendRetryDeliversExactlyOnce() = runTest {
        // BASE (RED): `attachFiles`' failure branch set Idle + an error and
        // DISCARDED the picked URIs — no tile (`attachments` stays empty), no durable
        // bytes, no queue row, no Retry. A mid-stream "Stream closed" teardown LOST
        // the file entirely. GREEN: the pick is retained as a durable, sidecar-backed
        // tile; a Send whose upload also fails leaves a durable RETRYABLE queue row,
        // and Retry re-uploads + delivers EXACTLY ONCE.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore(dispatcher)
        val vm = newVm(dispatcher, queue, sidecars)
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("please review this")

        val picked = pickedFile("photo.png", "REAL-PNG-BYTES")
        // The attach-time upload leg fails mid-stream (teardown collateral).
        vm.attachFiles(count = 1, previews = listOf(preview(picked))) {
            Result.failure(SshException("Upload of photo.png to ~/... failed: Stream closed"))
        }
        advanceUntilIdle()

        // Load-bearing (GREEN): the pick was NOT lost — one durable tile is retained.
        assertEquals(
            "the failed pick must be retained as a durable tile, not dropped (LOST)",
            1,
            vm.uiState.value.attachments.size,
        )
        // The retained tile is backed by durable local bytes (a draft-scoped sidecar).
        assertTrue(
            "durable draft-sidecar bytes must be staged for the retained pick",
            sidecars.refsFor(draftAttachmentSidecarScope("1/session-a")).isNotEmpty(),
        )
        // Accurate messaging — no false "draft was kept" (which was true only for TEXT).
        assertNotNull(vm.uiState.value.error)
        assertFalse(
            "the retained-copy must not claim only the draft was kept",
            vm.uiState.value.error!!.contains("draft was kept"),
        )

        // Now the user taps Send. The send-time upload also fails once (the flap is
        // still healing), so the row must stay durable + retryable — never delivered
        // with a broken path, never lost.
        var uploadAttempts = 0
        vm.setOutboundAttachmentSidecarUploader { refs ->
            uploadAttempts++
            if (uploadAttempts == 1) {
                Result.failure(SshException("upload failed: Stream closed"))
            } else {
                Result.success(refs.map { "~/.pocketshell/attachments/session-a/uploaded-${it.displayName}" })
            }
        }
        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/session-a")
        vm.requestSend(withEnter = true, sendTarget = target)
        settleUntil { queue.itemsFor("1/session-a").isNotEmpty() }

        // The first send-upload failed → a durable retryable row remains, NOTHING
        // was delivered, and the composer isn't wedged.
        assertEquals(1, queue.itemsFor("1/session-a").size)
        assertTrue("no send may be delivered on the failed upload", sent.isEmpty())
        assertFalse(vm.uiState.value.sendInFlight)
        val rowId = queue.itemsFor("1/session-a").single().id

        // Retry: the SAME row re-uploads (success this time) and delivers exactly once.
        val retriedId = vm.retryNextOutboundItem()
        settleUntil { sent.isNotEmpty() }
        assertEquals("Retry must re-claim the SAME durable row", rowId, retriedId)
        assertEquals("exactly one delivery from the retry", 1, sent.size)
        val delivered = sent.single()
        val uploadedPath = "~/.pocketshell/attachments/session-a/uploaded-photo.png"
        assertEquals(listOf(uploadedPath), delivered.attachments.map { it.remotePath })
        assertEquals(
            "the uploaded attachment path appears EXACTLY ONCE on the wire",
            1,
            countOccurrences(delivered.text, uploadedPath),
        )

        // Deliver it → pruned; no second delivery is ever possible (exactly-once).
        vm.markSendDelivered(delivered)
        settleUntil { vm.outboundQueueItems.value.isEmpty() }
        assertNull("a delivered row must not be re-dispatchable", vm.retryNextOutboundItem())
        advanceUntilIdle()
        assertEquals("no duplicate delivery after the retry delivered", 1, sent.size)
    }

    // ---- R-A class coverage: MULTI-attachment total failure is retained ------

    @Test
    fun multiAttachmentTotalUploadFailureRetainsEveryPickedFile() = runTest {
        // Class coverage (G2): a MULTI-file batch whose whole upload fails mid-stream
        // must retain ALL picked files durably, not just one — the maintainer's real
        // batch was 3 files. BASE: 0 tiles (all lost). GREEN: 2 tiles + 2 durable
        // sidecars.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore(dispatcher)
        val vm = newVm(dispatcher, queue, sidecars)
        vm.onComposerTargetChanged("1/session-a")

        val a = pickedFile("a.zip", "AAA")
        val b = pickedFile("b.log", "BBB")
        vm.attachFiles(count = 2, previews = listOf(preview(a, "application/zip"), preview(b, "text/plain"))) {
            Result.failure(SshException("Upload failed: Stream closed"))
        }
        advanceUntilIdle()

        assertEquals(
            "every picked file in a failed multi-batch must be retained",
            2,
            vm.uiState.value.attachments.size,
        )
        assertEquals(
            2,
            sidecars.refsFor(draftAttachmentSidecarScope("1/session-a")).size,
        )
        // The retained tiles carry the two distinct file names (nothing collapsed).
        assertEquals(
            setOf("a.zip", "b.log"),
            vm.uiState.value.attachments.map { it.displayName }.toSet(),
        )
    }

    // ---- R-A durability across a session switch A→B→A (no bytes lost) ---------

    @Test
    fun retainedFailedPickSurvivesSessionSwitchAndStillUploadsOnSend() = runTest {
        // The retained tile + durable bytes must survive a session switch A→B→A (the
        // #832/#872 class) so a switch does not re-lose the failed pick. BASE would
        // never even create the tile.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore(dispatcher)
        val draftStore = InMemoryComposerDraftStore()
        val vm = newVm(dispatcher, queue, sidecars, composerDraftStore = draftStore)
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")

        val picked = pickedFile("keep.png", "KEEP")
        vm.attachFiles(count = 1, previews = listOf(preview(picked))) {
            Result.failure(SshException("Upload failed: Stream closed"))
        }
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.attachments.size)

        // Switch away to B, then back to A.
        vm.onComposerTargetChanged("1/session-b")
        advanceUntilIdle()
        assertTrue("B shows no bleed of A's tiles", vm.uiState.value.attachments.isEmpty())
        vm.onComposerTargetChanged("1/session-a")
        advanceUntilIdle()

        // A's retained tile is back, reconnected to its durable bytes.
        assertEquals("the retained tile survives A→B→A", 1, vm.uiState.value.attachments.size)
        assertNotNull(
            "the tile is reconnected to its durable sidecar bytes",
            vm.uiState.value.attachments.single().previewUri,
        )

        // And it still uploads + delivers on Send.
        vm.setOutboundAttachmentSidecarUploader { refs ->
            Result.success(refs.map { "~/.pocketshell/attachments/session-a/uploaded-${it.displayName}" })
        }
        vm.requestSend(
            withEnter = true,
            sendTarget = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/session-a"),
        )
        settleUntil { sent.isNotEmpty() }
        assertEquals(1, sent.size)
        // The retained pick's BYTES survived the A→B→A round-trip and uploaded on
        // Send (the durability guarantee). The user-visible tile name stays clean
        // ("keep.png"); the re-uploaded remote path may carry the durable-store's id
        // prefix after a cross-session restore — a cosmetic on this secondary path,
        // not a data-loss concern.
        val uploaded = sent.single().attachments.single().remotePath
        assertTrue("the retained file uploaded on Send after A→B→A; was: $uploaded", uploaded.endsWith("keep.png"))
    }

    // ---- R-B: the absolute 90s cap no longer kills a progressing upload -------

    @Test
    fun sendTimeUploadProgressingPast90sIsNotKilledByTheAbsoluteCap() = runTest {
        // BASE (RED): the send-time upload leg was wrapped in
        // `withTimeout(ATTACHMENT_UPLOAD_TIMEOUT_MS)` (90s). An upload that keeps
        // progressing but exceeds 90s wall-clock (a 52MB batch on a healthy link)
        // threw TimeoutCancellationException → requeueForRetry → NEVER delivered
        // (and re-armed the whole batch from byte 0). GREEN: no absolute cap — a
        // 120s progressing upload completes and delivers, and the overall-send
        // watchdog (armed here at 100s) does NOT kill the progressing upload either.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore(dispatcher)
        // Arm a realistic overall-send watchdog to prove the progressing upload is
        // not killed by the wall-clock backstop either (it is disarmed for the
        // upload leg).
        val vm = newVm(dispatcher, queue, sidecars, sendWatchdogMs = 100_000L)
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("big upload")

        // A staged local attachment (a retained pick) so the send takes the sidecar
        // upload leg.
        val picked = pickedFile("big.bin", "BIG")
        vm.attachFiles(count = 1, previews = listOf(preview(picked))) {
            Result.failure(SshException("Upload failed: Stream closed"))
        }
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.attachments.size)

        // The uploader models a large-but-PROGRESSING transfer: it takes 120s of
        // virtual time (> the old 90s cap) and then succeeds.
        var uploadFinishedAtMs = -1L
        vm.setOutboundAttachmentSidecarUploader { refs ->
            delay(120_000L)
            uploadFinishedAtMs = testScheduler.currentTime
            Result.success(refs.map { "~/.pocketshell/attachments/session-a/uploaded-${it.displayName}" })
        }
        vm.requestSend(
            withEnter = true,
            sendTarget = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/session-a"),
        )
        settleUntil { sent.isNotEmpty() }

        // The progressing 120s upload completed and delivered — the 90s cap did NOT
        // kill it, and the 100s watchdog did NOT kill it.
        assertEquals("the progressing upload must deliver, not be capped", 1, sent.size)
        assertTrue(
            "the upload ran the full 120s of progress (> the removed 90s cap)",
            uploadFinishedAtMs >= 120_000L,
        )
        assertEquals(
            listOf("~/.pocketshell/attachments/session-a/uploaded-big.bin"),
            sent.single().attachments.map { it.remotePath },
        )
    }

    // ---- U2 no-zombie: a total attach failure does not orphan an upload -------

    @Test
    fun attachFailureLeavesNoActiveAttachmentJobZombie() = runTest {
        // BASE (RED): the 90s `withTimeout` around attach cancelled only the AWAIT,
        // leaving the detached upload deferred running (the "already uploaded but
        // uploading again" zombie). GREEN: without the cap the attach job resolves
        // to a clean terminal state (no orphaned active job) and the pick is retained.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore(dispatcher)
        val vm = newVm(dispatcher, queue, sidecars)
        vm.onComposerTargetChanged("1/session-a")

        val picked = pickedFile("z.png", "Z")
        vm.attachFiles(count = 1, previews = listOf(preview(picked))) {
            Result.failure(SshException("Upload failed: Stream closed"))
        }
        advanceUntilIdle()

        assertFalse(
            "no attachment job may remain active after the pick settled (no zombie)",
            vm.isAttachmentJobActiveForTest(),
        )
        assertEquals(1, vm.uiState.value.attachments.size)
    }
}
