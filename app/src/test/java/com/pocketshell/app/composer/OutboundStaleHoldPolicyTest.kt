package com.pocketshell.app.composer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.voice.WhisperClient
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #1700 — stale outbound intent must not silently flush into a moved-on
 * context. The first tests compile against unmodified `origin/main` so the
 * reproduction is a genuine assertion RED (current main claims a 5-minute-old
 * row), not a compile error.
 *
 * Age is `max(0, nowEpochMs - createdAtMs)`. Retries / `lastAttemptAtMs` never
 * rejuvenate it. The product constant is five minutes from enqueue
 * ([OUTBOUND_STALE_HOLD_MS]).
 *
 * G6 mutation: delete the atomic age gate inside [OutboundQueueStore.claim] /
 * [OutboundQueueStore.claimNext] / [firstComposerAutoFlushable]. A stale
 * unapproved row then reaches InFlight / is selected as the auto-flush head,
 * and the load-bearing assertions fail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OutboundStaleHoldPolicyTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val createdViewModels = mutableListOf<PromptComposerViewModel>()

    @After
    fun tearDownViewModels() {
        createdViewModels.forEach { it.clearForTest() }
        createdViewModels.clear()
    }

    @Test
    fun claimNextSilentlyDeliversARowAgedExactlyToTheHoldThreshold() {
        val store = InMemoryOutboundQueueStore()
        val now = System.currentTimeMillis()
        val stale = store.enqueue(
            sessionKey = SESSION,
            cleanText = STALE_TEXT,
            createdAtMs = now - OUTBOUND_STALE_HOLD_MS,
        )

        val claimed = store.claimNext(SESSION)

        assertNull(
            "a row aged exactly to the 5-minute hold threshold must NOT be " +
                "auto-claimed — current main flushes it into whatever context is " +
                "live when the wire returns (#1700)",
            claimed,
        )
        val held = requireNotNull(store.item(stale.id))
        assertEquals(OutboundState.HeldForReview, held.state)
        assertEquals(STALE_TEXT, held.cleanText)
        assertEquals(stale.id, held.id)
        assertNull(held.staleApprovedAtMs)
    }

    @Test
    fun claimNextStillDeliversARowOneMillisecondUnderTheThreshold() {
        val store = InMemoryOutboundQueueStore()
        val now = 1_700_000_000_000L
        val fresh = store.enqueue(
            sessionKey = SESSION,
            cleanText = FRESH_TEXT,
            createdAtMs = now - OUTBOUND_STALE_HOLD_MS + 1L,
        )

        val claimed = store.claimNext(SESSION, nowMillis = now)

        assertEquals(fresh.id, claimed?.id)
        assertEquals(OutboundState.InFlight, claimed?.state)
    }

    @Test
    fun autoFlushSelectsTheStaleHeadOnCurrentMain() {
        val now = System.currentTimeMillis()
        val stale = OutboundItem(
            id = "stale-a",
            sessionKey = SESSION,
            cleanText = STALE_TEXT,
            state = OutboundState.Queued,
            createdAtMs = now - OUTBOUND_STALE_HOLD_MS,
        )
        val fresh = OutboundItem(
            id = "fresh-b",
            sessionKey = SESSION,
            cleanText = FRESH_TEXT,
            state = OutboundState.Queued,
            createdAtMs = now,
        )

        val selected = listOf(stale, fresh)
            .firstComposerAutoFlushable(SESSION, maxAutoAttempts = OUTBOUND_MAX_AUTO_ATTEMPTS)

        assertEquals(
            "a held/stale head must be skipped so a clearly-current tail still " +
                "auto-drains (#1700 / #1602 skip-head precedent)",
            fresh.id,
            selected?.id,
        )
    }

    @Test
    fun retryNextOutboundItemDoesNotFlushStaleAWhenFreshBIsQueued() = runTest {
        val store = InMemoryOutboundQueueStore()
        val vm = newVm(store)
        val now = System.currentTimeMillis()
        vm.onComposerTargetChanged(SESSION)

        val stale = store.enqueue(
            sessionKey = SESSION,
            cleanText = STALE_TEXT,
            createdAtMs = now - OUTBOUND_STALE_HOLD_MS - 1_000L,
        )
        val fresh = store.enqueue(
            sessionKey = SESSION,
            cleanText = FRESH_TEXT,
            createdAtMs = now,
        )
        vm.refreshOutboundQueueItemsFor(SESSION)

        val flushed = vm.retryNextOutboundItem()
        advanceUntilIdle()

        assertEquals(
            "auto-flush must deliver the fresh tail, never the minutes-old head",
            fresh.id,
            flushed,
        )
        assertEquals(OutboundState.HeldForReview, store.item(stale.id)?.state)
        assertTrue(
            "the fresh tail was accepted for dispatch (claim is async on the drain dispatcher)",
            flushed == fresh.id,
        )
    }

    @Test
    fun claimHoldsARetryableFailedRowAndIgnoresARecentLastAttempt() {
        val store = InMemoryOutboundQueueStore()
        val now = 1_700_000_300_000L
        val stale = store.enqueue(
            sessionKey = SESSION,
            cleanText = STALE_TEXT,
            createdAtMs = now - OUTBOUND_STALE_HOLD_MS,
        )
        store.claim(stale.id, nowMillis = now - OUTBOUND_STALE_HOLD_MS)
        store.markFailed(stale.id, lastError = "transport down", lastAttemptAtMs = now - 1_000L)

        val claimed = store.claim(stale.id, nowMillis = now)

        assertNull(
            "lastAttemptAtMs must not rejuvenate old intent — a Failed row " +
                "whose enqueue is at/over the threshold stays held",
            claimed,
        )
        assertEquals(OutboundState.HeldForReview, store.item(stale.id)?.state)
        assertEquals(now - 1_000L, store.item(stale.id)?.lastAttemptAtMs)
    }

    @Test
    fun negativeAndRollbackAgesNeverHoldAFreshRow() {
        val store = InMemoryOutboundQueueStore()
        val created = 1_700_000_000_000L
        val fresh = store.enqueue(SESSION, FRESH_TEXT, createdAtMs = created)

        assertEquals(
            "a clock rollback (now < createdAt) is age 0, not an overflow hold",
            fresh.id,
            store.claimNext(SESSION, nowMillis = created - 60_000L)?.id,
        )
    }

    @Test
    fun approveThenClaimReusesTheSameIdAndKeepsWireBaselines() {
        val store = InMemoryOutboundQueueStore()
        val now = 1_700_000_400_000L
        val stale = store.enqueue(
            sessionKey = SESSION,
            cleanText = STALE_TEXT,
            createdAtMs = now - OUTBOUND_STALE_HOLD_MS,
            sendKey = "sk-stale",
        )
        store.markWireAttempted(SESSION, stale.id, atMs = now - 60_000L, baselineCount = 3)
        assertNull(store.claimNext(SESSION, nowMillis = now))
        val held = requireNotNull(store.item(stale.id))
        assertEquals(OutboundState.HeldForReview, held.state)
        assertTrue(held.wireAttempted)
        assertEquals(3, held.wireNeedleBaselineCount)

        val approved = requireNotNull(store.approveStaleForSend(stale.id, nowMillis = now))
        assertEquals(stale.id, approved.id)
        assertEquals(OutboundState.Queued, approved.state)
        assertEquals(now, approved.staleApprovedAtMs)
        assertTrue(approved.wireAttempted)
        assertEquals(3, approved.wireNeedleBaselineCount)

        val claimed = store.claim(stale.id, nowMillis = now + OUTBOUND_STALE_HOLD_MS)
        assertEquals(stale.id, claimed?.id)
        assertEquals(OutboundState.InFlight, claimed?.state)
        assertTrue(requireNotNull(store.item(stale.id)).wireAttempted)
    }

    @Test
    fun heldAndApprovalRoundTripThroughThePrefsBlob() {
        val original = OutboundItem(
            id = "held-row",
            sessionKey = SESSION,
            cleanText = STALE_TEXT,
            state = OutboundState.HeldForReview,
            createdAtMs = 1_700_000_000_000L,
            sendKey = "sk-held",
            wireAttempted = true,
            wireAttemptedAtMs = 1_700_000_060_000L,
            wireNeedleBaselineCount = 4,
            staleApprovedAtMs = null,
        )
        val decoded = decodeOutboundItems(SESSION, encodeOutboundItems(listOf(original))).single()
        assertEquals(original, decoded)

        val approved = original.copy(
            state = OutboundState.Queued,
            staleApprovedAtMs = 1_700_000_400_000L,
        )
        assertEquals(approved, decodeOutboundItems(SESSION, encodeOutboundItems(listOf(approved))).single())
    }

    @Test
    fun retryOutboundItemOnAHeldRowApprovesTheSameId() = runTest {
        val store = InMemoryOutboundQueueStore()
        val vm = newVm(store)
        val now = System.currentTimeMillis()
        vm.onComposerTargetChanged(SESSION)
        val stale = store.enqueue(
            sessionKey = SESSION,
            cleanText = STALE_TEXT,
            createdAtMs = now - OUTBOUND_STALE_HOLD_MS - 1_000L,
        )
        assertNull(store.claimNext(SESSION))
        assertEquals(OutboundState.HeldForReview, store.item(stale.id)?.state)

        vm.retryOutboundItem(stale.id)
        advanceUntilIdle()

        val after = requireNotNull(store.item(stale.id))
        assertTrue(
            "Send now re-arms the same id (Queued or InFlight), never mints a sibling",
            after.id == stale.id &&
                (after.state == OutboundState.Queued || after.state == OutboundState.InFlight),
        )
        assertTrue(after.staleApprovedAtMs != null)
    }

    @Test
    fun sendNowHeldRowUsesTheNormalDrainAndDeliversTheSameId() = runTest {
        val store = InMemoryOutboundQueueStore()
        val vm = newVm(store)
        val physicalRequests = mutableListOf<PromptComposerViewModel.SendRequest>()
        val consumer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            collectPromptComposerSendRequests(vm, onSend = { request ->
                physicalRequests += request
                ComposerSendResult.Delivered
            })
        }
        runCurrent()
        vm.onComposerTargetChanged(SESSION)
        val now = System.currentTimeMillis()
        val stale = store.enqueue(
            sessionKey = SESSION,
            cleanText = STALE_TEXT,
            createdAtMs = now - OUTBOUND_STALE_HOLD_MS - 1_000L,
        )
        assertNull(store.claimNext(SESSION, nowMillis = now))
        assertEquals(OutboundState.HeldForReview, store.item(stale.id)?.state)
        vm.refreshOutboundQueueItemsFor(SESSION)

        vm.retryOutboundItem(stale.id)
        advanceUntilIdle()

        assertEquals(listOf(stale.id), physicalRequests.map { it.outboundQueueItemId })
        assertNull(
            "Send now must deliver and prune the approved durable row, not mint a sibling",
            store.item(stale.id),
        )
        consumer.cancel()
    }

    @Test
    fun sendNowAfterFreshTailDeliveryStillDrainsTheApprovedHeldId() = runTest {
        val store = InMemoryOutboundQueueStore()
        val vm = newVm(store)
        val physicalRequests = mutableListOf<PromptComposerViewModel.SendRequest>()
        val consumer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            collectPromptComposerSendRequests(vm, onSend = { request ->
                physicalRequests += request
                ComposerSendResult.Delivered
            })
        }
        runCurrent()
        vm.onComposerTargetChanged(SESSION)
        val now = System.currentTimeMillis()
        val stale = store.enqueue(
            sessionKey = SESSION,
            cleanText = STALE_TEXT,
            createdAtMs = now - OUTBOUND_STALE_HOLD_MS - 1_000L,
        )
        val fresh = store.enqueue(
            sessionKey = SESSION,
            cleanText = FRESH_TEXT,
            createdAtMs = now,
        )
        vm.refreshOutboundQueueItemsFor(SESSION)

        assertEquals(fresh.id, vm.retryNextOutboundItem())
        advanceUntilIdle()
        assertEquals(listOf(fresh.id), physicalRequests.map { it.outboundQueueItemId })
        assertNull(store.item(fresh.id))
        assertEquals(OutboundState.HeldForReview, store.item(stale.id)?.state)

        // This is the state reached by the connected journey before the user
        // clicks Send now: the current tail is delivered, while the original
        // head is still held. The click must approve and deliver that same id.
        vm.retryOutboundItem(stale.id)
        advanceUntilIdle()

        assertEquals(
            listOf(fresh.id, stale.id),
            physicalRequests.map { it.outboundQueueItemId },
        )
        assertNull(
            "Send now must drain the approved held row after the fresh tail, " +
                "without minting a sibling",
            store.item(stale.id),
        )
        consumer.cancel()
    }

    @Test
    fun sendNowRejectedByActiveOwnerIsWokenWithTheApprovedIdAfterOwnerFailure() = runTest {
        val store = InMemoryOutboundQueueStore()
        val vm = newVm(store)
        val physicalRequests = mutableListOf<PromptComposerViewModel.SendRequest>()
        val activeStarted = CompletableDeferred<Unit>()
        val resolveActive = CompletableDeferred<ComposerSendResult>()
        val consumer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            collectPromptComposerSendRequests(vm, onSend = { request ->
                physicalRequests += request
                if (request.cleanDraft == FRESH_TEXT) {
                    activeStarted.complete(Unit)
                    resolveActive.await()
                } else {
                    ComposerSendResult.Delivered
                }
            })
        }
        runCurrent()
        vm.onComposerTargetChanged(SESSION)
        val now = System.currentTimeMillis()
        val stale = store.enqueue(
            sessionKey = SESSION,
            cleanText = STALE_TEXT,
            createdAtMs = now - OUTBOUND_STALE_HOLD_MS - 1_000L,
        )
        val fresh = store.enqueue(
            sessionKey = SESSION,
            cleanText = FRESH_TEXT,
            createdAtMs = now,
        )
        vm.refreshOutboundQueueItemsFor(SESSION)

        assertEquals(fresh.id, vm.retryNextOutboundItem())
        advanceUntilIdle()
        activeStarted.await()
        assertEquals(listOf(fresh.id), physicalRequests.map { it.outboundQueueItemId })
        assertEquals(OutboundState.InFlight, store.item(fresh.id)?.state)
        assertEquals(fresh.id, vm.outboundDrainOwnership.activeRowId())
        assertEquals(OutboundState.HeldForReview, store.item(stale.id)?.state)
        assertNull(
            "the active fresh owner must consume the only claimable row; the held " +
                "stale row must not leave a second claim available",
            store.claimNext(SESSION, nowMillis = now),
        )

        // The exact stale id is approved while the fresh row owns delivery. The
        // direct dispatch is expected to reject, but the approval must survive
        // that rejection until the active owner's terminal callback.
        vm.retryOutboundItem(stale.id)
        advanceUntilIdle()
        assertEquals(listOf(fresh.id), physicalRequests.map { it.outboundQueueItemId })
        assertEquals(OutboundState.Queued, store.item(stale.id)?.state)
        assertTrue(vm.uiState.value.outboundRetryingIds.isEmpty())

        // The active row fails and is deferred. A terminal callback must wake the
        // normal drain for the SAME approved stale id; it must not re-send fresh
        // first or mint a sibling row.
        resolveActive.complete(ComposerSendResult.Failed)
        advanceUntilIdle()

        val deliveredOrder = physicalRequests.map { it.outboundQueueItemId }
        assertTrue("the owner must resolve before the approved row is drained", deliveredOrder.size >= 2)
        assertEquals(listOf(fresh.id, stale.id), deliveredOrder.take(2))
        assertEquals(1, deliveredOrder.count { it == stale.id })
        assertEquals(stale.id, physicalRequests[1].outboundQueueItemId)
        assertTrue(physicalRequests[1].outboundDrainLeaseToken != null)
        assertNull(store.item(stale.id))
        consumer.cancel()
    }

    @Test
    fun discardHeldForReviewRemovesTheRowAndItsSidecar() = runTest {
        val store = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore(StandardTestDispatcher(testScheduler))
        val coordinator = OutboundQueueLifecycleCoordinator(
            queueStore = store,
            sidecarStore = sidecars,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            autoRepairOnInit = false,
        )
        val now = System.currentTimeMillis()
        val queued = store.enqueue(
            sessionKey = SESSION,
            cleanText = STALE_TEXT,
            createdAtMs = now - OUTBOUND_STALE_HOLD_MS,
        )
        val held = store.holdStaleUnapproved(SESSION, now).single()
        assertEquals(queued.id, held.id)

        val localAttachment = File(
            ApplicationProvider.getApplicationContext<Context>().cacheDir,
            "issue1700-held-delete.txt",
        ).apply {
            parentFile?.mkdirs()
            writeText("held sidecar")
        }
        val staged = sidecars.stage(held.id, listOf(Uri.fromFile(localAttachment))).single()
        assertTrue(localAttachment.exists())
        val sidecarFile = File(staged.localPath)
        assertTrue(sidecarFile.exists())
        assertEquals(listOf(staged), sidecars.refsFor(held.id))

        val vm = newVm(store, sidecars, coordinator)
        vm.onComposerTargetChanged(SESSION)
        vm.discardOutboundItem(held.id)
        advanceUntilIdle()

        assertNull(
            "Delete must remove a HeldForReview row, not only Queued/Failed rows",
            store.item(held.id),
        )
        assertTrue(
            "Delete must remove the held row's local sidecar bytes",
            !sidecarFile.exists(),
        )
        assertTrue(
            "Delete must remove the held row's sidecar metadata",
            sidecars.refsFor(held.id).isEmpty(),
        )
        coordinator.close()
    }

    @Test
    fun fixtureOrderingTimestampsBelowTheEpochFloorAreNeverHeld() {
        val store = InMemoryOutboundQueueStore()
        val fixture = store.enqueue(SESSION, "order-key", createdAtMs = 1L)

        assertEquals(
            "createdAtMs=1 is a FIFO order key used by existing tests, not an " +
                "enqueue epoch; the hold gate must not rewrite those fixtures",
            fixture.id,
            store.claimNext(SESSION)?.id,
        )
    }

    private fun newVm(
        store: OutboundQueueStore,
        sidecars: OutboundAttachmentSidecarStore? = null,
        coordinator: OutboundQueueLifecycleCoordinator? = null,
    ): PromptComposerViewModel {
        val dispatcher = StandardTestDispatcher(mainDispatcherRule.dispatcher.scheduler)
        val vm = PromptComposerViewModel(
            audioRecorder = object : PromptComposerViewModel.MicCapture {
                override fun start() = Unit
                override fun stop(): ByteArray = ByteArray(0)
                override fun currentAmplitude(): Float = 0f
            },
            whisperClientFactory = WhisperClientFactory {
                object : WhisperClient {
                    override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> =
                        Result.success("hello")
                }
            },
            apiKeyStorage = object : ApiKeyVault {
                private var key: CharArray? = "sk-test".toCharArray()
                override fun save(key: CharArray) { this.key = key.copyOf() }
                override fun load(): CharArray? = key?.copyOf()
                override fun clear() { this.key = null }
            },
            voiceSettings = object : PromptComposerViewModel.VoiceSettingsSnapshot {
                override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
                override fun whisperLanguageHint(): String? = null
                override fun transcriptionProvider(): com.pocketshell.app.settings.VoiceTranscriptionProvider =
                    com.pocketshell.app.settings.VoiceTranscriptionProvider.OpenAiWhisper
            },
            outboundQueueStore = store,
            outboundAttachmentSidecarStore = sidecars,
            outboundQueueLifecycleCoordinator = coordinator,
            savedStateHandle = SavedStateHandle(),
        )
        vm.samplerDispatcher = dispatcher
        vm.outboundQueueDispatcher = dispatcher
        vm.setSendWatchdogTimeoutForTest(null)
        vm.setTransportWritableProbe { true }
        createdViewModels += vm
        return vm
    }

    private fun newSidecarStore(ioDispatcher: TestDispatcher): OutboundAttachmentSidecarStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(
            OutboundAttachmentSidecarStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
        File(context.filesDir, OutboundAttachmentSidecarStore.DIRECTORY_NAME).deleteRecursively()
        var nextId = 0
        return OutboundAttachmentSidecarStore(context).also { sidecars ->
            sidecars.idGenerator = { "issue1700-sidecar-${++nextId}" }
            sidecars.clock = { nextId.toLong() }
            sidecars.ioDispatcher = ioDispatcher
        }
    }

    private companion object {
        const val SESSION = "tmux:1:\$1700:1700000000"
        const val STALE_TEXT = "from the past — do not flush"
        const val FRESH_TEXT = "typed just now"
    }
}
