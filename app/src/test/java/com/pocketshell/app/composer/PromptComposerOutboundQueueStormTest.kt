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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #1635: what a RECONNECT STORM (#1610 — transport teardown/redial every
 * ~1s–1min on mobile) does to the outbound queue's bounded auto-retry budget
 * (#1602). Two asymmetric defects, both reproduced RED on base here and proven
 * GREEN with the fix.
 *
 *  - **#1635-A — the queue parks and STAYS parked.** Every live window burns an
 *    attempt (the flip clears the per-row backoff, so the flip itself re-arms an
 *    immediate dispatch) and every attempt dies at the next teardown. Six failures
 *    take ~30s of storm; the row parks `Failed`, and a genuine reconnect resets the
 *    BACKOFF but never the ATTEMPT COUNT. So when the storm ends and the link is
 *    fully healthy, **nothing auto-sends** — the maintainer's "it got delivered long
 *    after, when I did something".
 *  - **#1635-B — attachment rows are the inverse and worse.** `markUploading` does
 *    not claim, so upload failures never bumped the budget: the row was IMMUNE to
 *    the park and looped at the 3s poll cadence FOREVER, re-transferring the file
 *    from byte 0 every time (no resume — #1604), starving every younger row behind
 *    it and burning mobile data without limit.
 *
 * **Why every reproduction here drives N >= 5 cycles.** A single-cycle test cannot
 * see either defect: one flap burns one attempt (budget intact, row still sends)
 * and one upload failure looks like a normal retry. The bug IS the regime — the
 * policy's behaviour ACROSS cycles — which is exactly the blindness that let the
 * #1610 livelock survive four fixes (#1640, #1652) and the gap the #1526 storm
 * audit named ("no CI gate exercises more than ONE flap cycle against the queue").
 *
 * Class coverage (G2):
 *  - text/prompt row, storm (window-closed) -> zero burn, auto-sends after recovery.
 *  - text/prompt row, window flipped and HEALED *under* one attempt -> zero burn
 *    (liveness at both ends reads `true`; only the epoch exposes it).
 *  - text/prompt row, STABLE live window, genuine failures -> charges and STILL
 *    parks (the load-bearing negative, G6 — the fix must not mint a new infinite
 *    retry loop in place of the old stuck head).
 *  - attachment/sidecar row, upload failures -> charges, parks, data bounded.
 *  - mixed queue (attachment head + text tail) -> head-of-line starvation cleared.
 *
 * Pure JVM/Robolectric ViewModel tests under virtual time, gate-wired in the
 * per-push `Unit tests` job via `./gradlew test`. No self-skip on any load-bearing
 * assertion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PromptComposerOutboundQueueStormTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val createdViewModels = mutableListOf<PromptComposerViewModel>()

    @After
    fun tearDownViewModels() {
        createdViewModels.forEach { it.clearForTest() }
        createdViewModels.clear()
    }

    /** The number of storm cycles each reproduction drives. Must exceed the budget. */
    private val stormCycles = 8

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

    /**
     * The delivery window the session screen owns in production
     * ([OutboundQueueAutoFlushController.deliveryWindow]): live/dead plus an epoch
     * that increments on EVERY flip. Driving it directly is what lets this test
     * reproduce the storm regime deterministically under virtual time.
     */
    private class FakeDeliveryWindow {
        private var live = true
        private var epoch = 0L

        fun current(): OutboundDeliveryWindow = OutboundDeliveryWindow(live = live, epoch = epoch)

        /** The transport tears down — the window closes. */
        fun teardown() {
            live = false
            epoch += 1L
        }

        /** The transport redials — a NEW live window (a different epoch). */
        fun restore() {
            live = true
            epoch += 1L
        }
    }

    private fun newVm(
        dispatcher: TestDispatcher,
        outboundQueueStore: OutboundQueueStore,
        outboundAttachmentSidecarStore: OutboundAttachmentSidecarStore? = null,
        window: FakeDeliveryWindow? = null,
    ): PromptComposerViewModel {
        val vm = PromptComposerViewModel(
            audioRecorder = FakeMicCapture(),
            whisperClientFactory = WhisperClientFactory { null },
            apiKeyStorage = FakeVault().also { it.save("sk-test".toCharArray()) },
            voiceSettings = FakeVoiceSettings(),
            speechRecognitionProvider = UnavailableSpeechRecognitionProvider,
            composerDraftStore = InMemoryComposerDraftStore(),
            outboundQueueStore = outboundQueueStore,
            outboundAttachmentSidecarStore = outboundAttachmentSidecarStore,
            savedStateHandle = SavedStateHandle(),
        )
        vm.samplerDispatcher = dispatcher
        vm.outboundQueueDispatcher = dispatcher
        vm.setSendWatchdogTimeoutForTest(null)
        // Unwired (null) keeps the production default: a STABLE LIVE window, which
        // charges every attempt exactly as base did. That default is what the G6
        // negative below relies on.
        window?.let { w -> vm.outboundAttemptBudget.window = { w.current() } }
        createdViewModels += vm
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
                assertTrue("settleUntil timed out before the predicate held (#1635)", predicate())
                return
            }
        }
    }

    private fun newSidecarStore(ioDispatcher: TestDispatcher): OutboundAttachmentSidecarStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
        val file = File(File(context.cacheDir, "picked"), name).apply {
            parentFile?.mkdirs()
            writeText(content)
        }
        return Uri.fromFile(file)
    }

    // ---- #1635-A: the storm parks the queue, and recovery never un-parks it ------

    /**
     * THE reported symptom. Drive [stormCycles] teardown/redial cycles with a prompt
     * queued, each attempt failing because the window died under it, then hand the
     * queue a STABLE, fully-healthy window.
     *
     * BASE (RED): every cycle charges an attempt; the row parks `Failed` at 6; the
     * stable window delivers NOTHING (`retryNextOutboundItem` returns null forever,
     * because `firstComposerAutoFlushable` excludes `attemptCount >= 6` regardless of
     * state). The prompt sits there until the user notices the amber badge and taps
     * Resend — "delivered long after, when I did something".
     *
     * GREEN: window-closed failures burn zero attempts, so the storm leaves the
     * budget intact and the first stable window delivers the row — exactly once.
     */
    @Test
    fun stormedPromptRowAutoSendsOnceTheWindowIsStableAgain() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = InMemoryOutboundQueueStore()
        val window = FakeDeliveryWindow()
        val vm = newVm(dispatcher, queue, window = window)
        val sent = collectSendRequests(vm)
        val session = "1/session-a"
        vm.onComposerTargetChanged(session)

        val row = queue.enqueue(sessionKey = session, cleanText = "ship the storm fix", createdAtMs = 1)
        vm.refreshOutboundQueueItemsFor(session)

        // ---- the storm: N cycles of "live window opens -> dispatch -> teardown" ----
        repeat(stormCycles) { cycle ->
            val dispatched = vm.retryNextOutboundItem()
            advanceUntilIdle()
            assertEquals(
                "cycle $cycle: the live window must re-arm the queued row (this is what " +
                    "burns the budget on base)",
                row.id,
                dispatched,
            )
            val request = requireNotNull(vm.inFlightSendRequest) {
                "cycle $cycle: the dispatch must have produced an in-flight request"
            }
            // The transport dies mid-send — the delivery window closes UNDER the
            // attempt. This is the storm's signature failure, and it is not the row's
            // fault in any sense.
            window.teardown()
            vm.markOutboundSendDeferred(request)
            advanceUntilIdle()
            window.restore()
            advanceUntilIdle()
        }

        // LOAD-BEARING (GREEN): the storm charged NOTHING. (BASE: attemptCount == 8,
        // state == Failed after the auto-flush parks it.)
        val afterStorm = requireNotNull(queue.item(row.id))
        assertEquals(
            "a failure because the connection was down must burn ZERO attempts — " +
                "$stormCycles storm cycles left the budget intact (#1635-A / D4)",
            0,
            afterStorm.attemptCount,
        )
        assertEquals(
            "the row must still be Queued after the storm, never parked Failed (#1635-A)",
            OutboundState.Queued,
            afterStorm.state,
        )

        // ---- the storm ends: a stable, healthy window ----
        val sentBefore = sent.size
        val delivered = vm.retryNextOutboundItem()
        settleUntil { sent.size > sentBefore }

        // LOAD-BEARING (GREEN): the recovered link auto-sends the row. On BASE this
        // is null — the whole point of the bug.
        assertEquals(
            "once the link is healthy again the queued prompt must AUTO-send — on base " +
                "the storm-exhausted budget parked it and nothing sent until the user " +
                "manually tapped Retry (#1635-A)",
            row.id,
            delivered,
        )
        val request = requireNotNull(sent.lastOrNull { it.outboundQueueItemId == row.id })

        // The host acks: delivered exactly once, the row is pruned, no duplicate left.
        vm.markSendDelivered(request)
        settleUntil { queue.item(row.id) == null }
        assertNull(
            "the delivered row must be pruned exactly once — no duplicate survives (#1529 ledger intact)",
            queue.item(row.id),
        )
        assertEquals(
            "exactly one row existed for this logical send throughout",
            0,
            queue.itemsFor(session).size,
        )
    }

    /**
     * Class coverage (G2): the window flip that HEALS *inside* one attempt. A ~50s
     * send timeout spans many cycles of a 1/s storm, so the claim and the failure
     * BOTH observe `live = true` across a window that was destroyed in between.
     * Liveness alone would charge this attempt; only the epoch exposes the flip.
     * This is the storm's most common shape and the reason the window is an epoch
     * rather than a boolean.
     */
    @Test
    fun windowTornDownAndHealedUnderTheAttemptBurnsZeroAttempts() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = InMemoryOutboundQueueStore()
        val window = FakeDeliveryWindow()
        val vm = newVm(dispatcher, queue, window = window)
        collectSendRequests(vm)
        val session = "1/session-a"
        vm.onComposerTargetChanged(session)
        val row = queue.enqueue(sessionKey = session, cleanText = "slow send", createdAtMs = 1)
        vm.refreshOutboundQueueItemsFor(session)

        repeat(stormCycles) { cycle ->
            assertEquals(row.id, vm.retryNextOutboundItem())
            advanceUntilIdle()
            val request = requireNotNull(vm.inFlightSendRequest) { "cycle $cycle: no in-flight request" }
            // The link dies AND heals while the attempt is still outstanding, so by
            // the time the send resolves the window reads LIVE again — just not the
            // same window the attempt was claimed in.
            window.teardown()
            window.restore()
            advanceUntilIdle()
            vm.markOutboundSendDeferred(request)
            advanceUntilIdle()
        }

        assertEquals(
            "a window torn down and healed UNDER the attempt is still a window-closed " +
                "failure — it must burn zero attempts even though liveness reads true " +
                "at both ends (#1635-A / D4)",
            0,
            requireNotNull(queue.item(row.id)).attemptCount,
        )
        assertEquals(
            "the row must remain Queued and auto-sendable",
            OutboundState.Queued,
            requireNotNull(queue.item(row.id)).state,
        )
        assertEquals(
            "and the next stable window must dispatch it",
            row.id,
            vm.retryNextOutboundItem(),
        )
    }

    // ---- The load-bearing NEGATIVE (G6) -----------------------------------------

    /**
     * **The load-bearing negative (G6).** A row that genuinely fails on its own
     * merits against a PROVEN-GOOD link — a bad payload, a real server rejection, a
     * wedged head — must STILL exhaust its budget and park. If "window-closed burns
     * zero" were applied too broadly (e.g. refunding every deferral, or treating any
     * failure as connection-classified), this row would retry FOREVER: a new
     * infinite loop replacing the old parked queue, and #1602's head-of-line clog
     * would be back with it.
     *
     * The window here NEVER flips: same epoch, live at claim and at failure, for
     * every attempt. That is the whole discriminator.
     */
    @Test
    fun rowThatGenuinelyFailsOnAStableLiveWindowStillExhaustsItsBudgetAndParks() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = InMemoryOutboundQueueStore()
        val window = FakeDeliveryWindow() // live, epoch 0, and never touched again.
        val vm = newVm(dispatcher, queue, window = window)
        collectSendRequests(vm)
        val session = "1/session-a"
        vm.onComposerTargetChanged(session)
        val bad = queue.enqueue(sessionKey = session, cleanText = "server rejects this", createdAtMs = 1)
        val tail = queue.enqueue(sessionKey = session, cleanText = "healthy tail", createdAtMs = 2)
        vm.refreshOutboundQueueItemsFor(session)

        var tailDispatched = false
        repeat(stormCycles * 3) {
            val dispatched = vm.retryNextOutboundItem()
            advanceUntilIdle()
            if (dispatched == tail.id) {
                tailDispatched = true
                return@repeat
            }
            if (dispatched == bad.id) {
                // Fails against a link that is live, stable, and the SAME window it was
                // claimed in — this failure is the row's own fault.
                val request = vm.inFlightSendRequest ?: return@repeat
                vm.markOutboundSendDeferred(request)
                advanceUntilIdle()
            }
        }

        val parked = requireNotNull(queue.item(bad.id))
        assertEquals(
            "a row that fails against a stable live window must STILL charge every " +
                "attempt — otherwise the #1635-A refund mints a new infinite retry " +
                "loop in place of #1602's stuck head (G6)",
            OUTBOUND_MAX_AUTO_ATTEMPTS,
            parked.attemptCount,
        )
        assertEquals(
            "and it must still be PARKED (Failed, surfaced) at the bound (#1602 preserved)",
            OutboundState.Failed,
            parked.state,
        )
        assertEquals(
            "with the surfaced retry label the user acts on",
            OUTBOUND_AUTO_RETRY_EXHAUSTED_MESSAGE,
            parked.lastError,
        )
        assertTrue(
            "and the healthy tail must drain past the parked head (#1602 head-of-line unclog preserved)",
            tailDispatched,
        )
    }

    /**
     * Refactor guard for [requeueDeferredSend]: #1635 routes the deferral through a
     * single helper so the budget correction has one home. The pre-#1635 `?:` chain
     * had a subtle property that MUST survive: when the request carries a row id but
     * that row is already gone (delivered/pruned out from under the deferral), the
     * lookup FALLS THROUGH to matching the request against the session's undelivered
     * rows — it does not give up. Losing that fall-through would silently strand the
     * prompt (the #971/#987 Option-A path drops to `restoreFailedSend`), so pin it.
     *
     * **Why the rows start `Failed`, and why there is a decoy.** The FIRST version of
     * this test was VACUOUS and the reviewer caught it: it enqueued one row (which
     * [OutboundQueueStore.enqueue] mints already `Queued`) and asserted only that the
     * row was `Queued` afterwards — a fact that was TRUE BEFORE the code under test
     * ever ran. Breaking the fall-through outright left all of it green. So:
     *
     *  - Both rows are driven to `Failed` FIRST (asserted as a precondition below), so
     *    `Queued` is a state ONLY the fall-through's `requeueForRetry` can produce.
     *    The assertion now measures a TRANSITION, not the initial state.
     *  - A DECOY row — older, non-matching text — sits ahead of the target. The
     *    candidate lookup is `firstOrNull { cleanText == cleanDraft } ?: firstOrNull()`,
     *    so without the decoy an id-blind `firstOrNull()` fallback would pick the same
     *    single row and the test could not tell "matched the right row" from "grabbed
     *    whatever was first". Requeueing the DECOY instead of the target would strand
     *    the real prompt and re-fire the wrong one — so the decoy must stay `Failed`.
     */
    @Test
    fun deferralWithAStaleRowIdStillFallsThroughToTheMatchingQueuedRow() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = InMemoryOutboundQueueStore()
        val vm = newVm(dispatcher, queue)
        val session = "1/session-a"
        vm.onComposerTargetChanged(session)
        // Older, non-matching: the row an id-blind `firstOrNull()` would wrongly grab.
        val decoy = queue.enqueue(sessionKey = session, cleanText = "decoy — do not pick me", createdAtMs = 1)
        val live = queue.enqueue(sessionKey = session, cleanText = "match me by text", createdAtMs = 2)
        // Drive both OUT of `Queued` so the post-state can only come from the fall-through.
        queue.markFailed(decoy.id, lastError = "boom")
        queue.markFailed(live.id, lastError = "boom")
        vm.refreshOutboundQueueItemsFor(session)

        // Guard against this test going vacuous again: if the target were already
        // `Queued` here, the assertion below would pass without the fall-through.
        assertEquals(
            "precondition: the target row must NOT already be Queued, or this test proves nothing",
            OutboundState.Failed,
            requireNotNull(queue.item(live.id)).state,
        )

        // A request whose own row id no longer resolves (pruned), but whose text still
        // matches a live undelivered row.
        val request = PromptComposerViewModel.SendRequest(
            text = "match me by text",
            withEnter = true,
            sendTarget = PromptComposerViewModel.SendTargetSnapshot(sessionKey = session),
            outboundQueueItemId = "row-that-no-longer-exists",
        )
        vm.markOutboundSendDeferred(request)
        advanceUntilIdle()

        val target = requireNotNull(queue.item(live.id))
        assertEquals(
            "a stale row id must fall through to the matching row and RE-ARM it (Failed -> Queued), not strand the prompt",
            OutboundState.Queued,
            target.state,
        )
        assertNull(
            "and the fall-through's requeue must clear the stale error so the auto-flush can re-claim it",
            target.lastError,
        )
        assertEquals(
            "the fall-through must match the row by TEXT — re-arming the older decoy would strand the real prompt and re-fire the wrong one",
            OutboundState.Failed,
            requireNotNull(queue.item(decoy.id)).state,
        )
    }

    // ---- #1635-B: the attachment row's budget-immune infinite loop ---------------

    /**
     * THE second reported symptom, and the expensive one. An attachment row whose
     * upload keeps failing under the storm.
     *
     * BASE (RED): `markUploading` does not claim and every upload failure exits via
     * `requeueForRetry` with the count preserved, so `attemptCount` stays 0 FOREVER.
     * The row is re-picked as the oldest retryable row on every cycle, re-transfers
     * the file from byte 0 every time (no resume — #1604), never parks, and the
     * younger text row behind it NEVER gets a delivery attempt. On base the upload
     * count here grows without bound (== the number of cycles driven) and
     * `tailDispatched` is false.
     *
     * GREEN: upload failures charge the budget; the row parks-and-surfaces within
     * `OUTBOUND_MAX_AUTO_ATTEMPTS` failures; the data burn is BOUNDED; the tail drains.
     */
    @Test
    fun uploadFailingAttachmentHeadParksWithinTheBudgetAndStopsStarvingTheTail() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore(dispatcher)
        val window = FakeDeliveryWindow()
        val vm = newVm(dispatcher, queue, sidecars, window = window)
        val sent = collectSendRequests(vm)
        val session = "1/session-a"
        vm.onComposerTargetChanged(session)
        vm.onDraftChange("here is the photo")

        // A retained-on-failure local pick -> the Send takes the sidecar upload leg.
        val picked = pickedFile("photo.png", "REAL-PNG-BYTES")
        vm.attachFiles(count = 1, previews = listOf(PromptComposerViewModel.AttachmentPreview(picked, "image/png"))) {
            Result.failure(SshException("Upload of photo.png failed: Stream closed"))
        }
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.attachments.size)

        // Every upload attempt dies at the next teardown — and each one re-transfers
        // the whole file from byte 0, which is what burns the maintainer's data.
        var uploadCalls = 0
        vm.setOutboundAttachmentSidecarUploader {
            uploadCalls += 1
            Result.failure(SshException("upload died mid-transfer: Stream closed"))
        }

        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = session)
        vm.requestSend(withEnter = true, sendTarget = target)
        settleUntil { queue.itemsFor(session).isNotEmpty() }
        val head = requireNotNull(queue.itemsFor(session).firstOrNull()) { "the attachment row must exist" }

        // A younger TEXT row queued behind the attachment head.
        val tail = queue.enqueue(sessionKey = session, cleanText = "and please review it", createdAtMs = Long.MAX_VALUE / 2)
        vm.refreshOutboundQueueItemsFor(session)

        var tailDispatched = false
        repeat(stormCycles * 3) {
            val dispatched = vm.retryNextOutboundItem()
            settleUntil { !vm.uiState.value.sendInFlight || vm.inFlightSendRequest != null }
            advanceUntilIdle()
            if (dispatched == tail.id) {
                tailDispatched = true
                return@repeat
            }
            // The storm keeps flapping around the failing upload.
            window.teardown()
            window.restore()
            advanceUntilIdle()
        }

        // LOAD-BEARING (GREEN) #1: the data burn is BOUNDED. On base this is
        // unbounded — one full re-transfer per cycle, forever, on mobile data.
        assertTrue(
            "an upload-failing row must NOT re-transfer the file without limit — on base " +
                "it looped from byte 0 every 3s forever (#1635-B). uploadCalls=$uploadCalls",
            uploadCalls in 1..OUTBOUND_MAX_AUTO_ATTEMPTS,
        )

        // LOAD-BEARING (GREEN) #2: the head is parked AND surfaced (never a silent drop).
        val parked = requireNotNull(queue.item(head.id))
        assertEquals(
            "the upload-failing head must PARK at the bound like the send leg does — " +
                "on base `markUploading` never claimed, so it was immune to the budget (#1635-B)",
            OutboundState.Failed,
            parked.state,
        )
        assertEquals(
            "and its budget must actually have been charged",
            OUTBOUND_MAX_AUTO_ATTEMPTS,
            parked.attemptCount,
        )

        // LOAD-BEARING (GREEN) #3: head-of-line starvation is cleared.
        assertTrue(
            "the younger TEXT row must get a delivery attempt once the upload-failing " +
                "head parks — on base it starved for the storm's entire duration (#1635-B)",
            tailDispatched,
        )
        settleUntil { sent.any { it.outboundQueueItemId == tail.id } }
        assertNotNull(
            "and the tail's SendRequest must actually have been emitted",
            sent.lastOrNull { it.outboundQueueItemId == tail.id },
        )
    }
}
