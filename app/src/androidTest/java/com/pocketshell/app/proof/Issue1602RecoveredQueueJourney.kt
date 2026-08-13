package com.pocketshell.app.proof

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.pocketshell.app.MainActivity
import com.pocketshell.app.composer.COMPOSER_OUTBOUND_QUEUE_TOGGLE_TAG
import com.pocketshell.app.composer.COMPOSER_STATUS_VIEWPORT_TAG
import com.pocketshell.app.composer.OUTBOUND_MAX_AUTO_ATTEMPTS
import com.pocketshell.app.composer.OutboundItem
import com.pocketshell.app.composer.OutboundQueueStore
import com.pocketshell.app.composer.OutboundState
import com.pocketshell.app.composer.PromptComposerOutboundDrainTestSeams
import com.pocketshell.app.composer.PromptComposerQueueStatusLayoutTestObserver
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.composer.composerOutboundQueueItemRowTestTag
import com.pocketshell.app.composer.composerOutboundQueueRetryTestTag
import com.pocketshell.app.composer.composerOutboundQueueStatusTestTag
import com.pocketshell.app.proof.signals.assertNodeFullyWithinOwningRoot
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_TAG
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

private const val ISSUE1602_GENUINE_NON_TRANSPORT_FAILURE_MESSAGE =
    "Send failed on a writable transport."

/** Deterministic real-UI oracle for reopened #1602's recovered clogged queue. */
internal class Issue1602RecoveredQueueJourney(
    private val compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>,
    private val composer: PromptComposerViewModel,
    private val waitForComposerReady: (expectQueue: Boolean) -> Unit,
    private val draftText: () -> String,
    private val readSubmitLedger: () -> List<Pair<Int, String>>,
    private val claimCount: (rowId: String) -> Int,
    private val captureViewport: (name: String) -> Unit,
    private val captureQueue: (name: String, rows: List<OutboundItem>) -> Unit,
    private val uiTimeoutMs: Long,
    private val connectedTimeoutMs: Long,
) : AutoCloseable {
    private val store: OutboundQueueStore = composer.outboundQueueStore
    private val releases = mutableListOf<CountDownLatch>()
    private val dispatchers = mutableListOf<ExecutorCoroutineDispatcher>()
    private val executors = mutableListOf<ExecutorService>()
    private var reconnectDrain: BlockedDispatcher? = null
    private val statusLayoutRegistration = PromptComposerQueueStatusLayoutTestObserver.install()
    private val draftPersistenceDispatcherBeforeJourney =
        composer.draftPersistence.dispatcherOverrideForTest

    init {
        // Production's Dispatchers.IO pool is not starved by one blocked queue
        // worker. Keep that topology while this journey deliberately blocks the
        // outbound drain on a single-thread dispatcher.
        composer.draftPersistence.dispatcherOverrideForTest = Dispatchers.IO
    }

    fun parkHeadAndProveOffline(
        fallbackKey: String,
        queuedIds: List<String>,
        firstPayload: String,
        secondPayload: String,
        replacementDraft: String,
    ) {
        requireNotNull(
            store.requeueForRetry(
                queuedIds.first(),
                attemptDelta = OUTBOUND_MAX_AUTO_ATTEMPTS,
            ),
        )
        requireNotNull(
            store.markFailed(
                queuedIds.first(),
                // Model a genuine row-specific failure, not the exact auto-park
                // marker that #1686/#2042 must re-arm on a recovered wire edge.
                // With the replacement draft already present, production keeps
                // this failed row as the single durable representation.
                ISSUE1602_GENUINE_NON_TRANSPORT_FAILURE_MESSAGE,
            ),
        )
        composer.refreshOutboundQueueItemsFor(fallbackKey)
        val rows = store.itemsFor(fallbackKey)
        assertEquals("parking must preserve both durable row identities", queuedIds, rows.map { it.id })
        assertEquals(listOf(firstPayload, secondPayload), rows.map { it.cleanText })
        assertEquals(OutboundState.Failed, rows.first().state)
        assertEquals(OUTBOUND_MAX_AUTO_ATTEMPTS, rows.first().attemptCount)
        assertEquals(ISSUE1602_GENUINE_NON_TRANSPORT_FAILURE_MESSAGE, rows.first().lastError)
        assertEquals(OutboundState.Queued, rows[1].state)
        assertEquals(replacementDraft, composer.uiState.value.draft)
        assertEquals(replacementDraft, composer.composerDraftStore.load(fallbackKey))
        compose.waitUntil(timeoutMillis = uiTimeoutMs) {
            statusLayoutRegistration.observedRowIds().containsAll(queuedIds)
        }
        assertEquals(
            "layout observer must bind exactly both preserved queue row identities",
            queuedIds.toSet(),
            statusLayoutRegistration.observedRowIds(),
        )

        val provenOfflineRowIds = buildList {
            rows.forEach { row ->
                val rowTag = composerOutboundQueueItemRowTestTag(row.id)
                val retryTag = composerOutboundQueueRetryTestTag(row.id)
                val statusTag = composerOutboundQueueStatusTestTag(row.id)
                val withinExactRow = hasAnyAncestor(hasTestTag(rowTag))
                val offlineStatusWrapper = hasTestTag(statusTag) and withinExactRow
                val offlineStatusLabel = hasText("Waiting — connection is offline") and
                    hasAnyAncestor(hasTestTag(statusTag)) and
                    withinExactRow
                val offlineRetryControl = hasTestTag(retryTag) and withinExactRow
                val offlineRetryLabel = hasText("Offline") and
                    hasAnyAncestor(hasTestTag(retryTag)) and
                    withinExactRow

                compose.onNodeWithTag(rowTag, useUnmergedTree = true)
                    .performScrollTo()
                    .assertIsDisplayed()
                compose.assertNodeFullyWithinOwningRoot(rowTag, useUnmergedTree = true)
                compose.waitForIdle()
                var latestGeometry = statusLayoutRegistration.currentWindowGeometry(row.id)
                val geometryWait = runCatching {
                    compose.waitUntil(timeoutMillis = uiTimeoutMs) {
                        statusLayoutRegistration.currentWindowGeometry(row.id).also {
                            latestGeometry = it
                        }.isCurrentAttachedNonEmptyContainedFullWidthPair()
                    }
                }
                if (geometryWait.isFailure) {
                    throw AssertionError(
                        "exact row/status raw-window LayoutCoordinates did not settle: " +
                            latestGeometry.diagnosticSummary(),
                        geometryWait.exceptionOrNull(),
                    )
                }
                assertTrue(
                    "offline status must have current attached, non-empty, fully-contained full-width " +
                        "raw-window LayoutCoordinates in its exact row: ${latestGeometry.diagnosticSummary()}",
                    latestGeometry.isCurrentAttachedNonEmptyContainedFullWidthPair(),
                )
                compose.onAllNodes(offlineStatusLabel, useUnmergedTree = true).assertCountEquals(1)
                compose.onAllNodes(offlineStatusWrapper, useUnmergedTree = true).assertCountEquals(1)
                val rowNode = compose.onNodeWithTag(rowTag, useUnmergedTree = true)
                    .fetchSemanticsNode()
                compose.onAllNodes(offlineRetryControl, useUnmergedTree = true).assertCountEquals(1)
                val offlineRetryControlNode = compose.onNode(
                    offlineRetryControl,
                    useUnmergedTree = true,
                )
                    // The status region is deliberately bounded and scrollable. A
                    // queue row can be taller than that viewport, so scrolling its
                    // ancestor into view does not imply that its trailing action is
                    // fully visible. Bring the exact physical control into view
                    // before asserting/tapping it (the r1 full-sheet failure).
                    .performScrollTo()
                    .assertIsDisplayed()
                    .assertIsNotEnabled()
                compose.assertNodeFullyWithinOwningRoot(retryTag, useUnmergedTree = true)
                val offlineRetryControlBounds = offlineRetryControlNode.fetchSemanticsNode().boundsInRoot
                val statusViewportBoundsInRoot = compose.onNodeWithTag(
                    COMPOSER_STATUS_VIEWPORT_TAG,
                    useUnmergedTree = true,
                ).fetchSemanticsNode().boundsInRoot
                val rowBoundsInRoot = rowNode.boundsInRoot
                assertTrue(
                    "offline Retry control must have non-empty bounds fully contained by exact row ${row.id}: " +
                        "control=$offlineRetryControlBounds row=$rowBoundsInRoot",
                    offlineRetryControlBounds.width > 0f &&
                        offlineRetryControlBounds.height > 0f &&
                        offlineRetryControlBounds.left >= rowBoundsInRoot.left &&
                        offlineRetryControlBounds.top >= rowBoundsInRoot.top &&
                        offlineRetryControlBounds.right <= rowBoundsInRoot.right &&
                        offlineRetryControlBounds.bottom <= rowBoundsInRoot.bottom,
                )
                assertTrue(
                    "offline Retry control must be non-empty and fully inside the bounded status viewport " +
                        "after exact-control scroll: control=$offlineRetryControlBounds " +
                        "viewport=$statusViewportBoundsInRoot",
                    offlineRetryControlBounds.width > 0f &&
                        offlineRetryControlBounds.height > 0f &&
                        offlineRetryControlBounds.left >= statusViewportBoundsInRoot.left &&
                        offlineRetryControlBounds.top >= statusViewportBoundsInRoot.top &&
                        offlineRetryControlBounds.right <= statusViewportBoundsInRoot.right &&
                        offlineRetryControlBounds.bottom <= statusViewportBoundsInRoot.bottom,
                )
                compose.onAllNodes(offlineRetryLabel, useUnmergedTree = true).assertCountEquals(1)
                add(row.id)
            }
        }
        assertEquals(
            "offline UI proof must bind both exact preserved row identities",
            queuedIds,
            provenOfflineRowIds,
        )
        val retryTag = composerOutboundQueueRetryTestTag(queuedIds.first())
        val firstRowTag = composerOutboundQueueItemRowTestTag(queuedIds.first())
        val offlineRetryControl = hasTestTag(retryTag) and hasAnyAncestor(hasTestTag(firstRowTag))
        val rowsBeforeTap = store.itemsFor(fallbackKey)
        compose.onNode(offlineRetryControl, useUnmergedTree = true)
            // The loop finishes with the second row in view. Bring the first
            // exact control back before the physical no-op tap; a pointer event
            // against an off-viewport semantics node is not a user journey.
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .performTouchInput { click() }
        compose.waitForIdle()
        assertEquals("disabled offline Retry touch must not mutate rows", rowsBeforeTap, store.itemsFor(fallbackKey))
        assertEquals("disabled offline Retry touch must not clear the live draft", replacementDraft, draftText())
        assertTrue("disabled offline Retry touch must never reach the server", readSubmitLedger().isEmpty())
        captureQueue("issue1602-offline-retry-visible", store.itemsFor(fallbackKey))
    }

    fun blockReconnectDrain() {
        check(reconnectDrain == null)
        reconnectDrain = blockedDispatcher("issue1602-retry-drain").also {
            composer.outboundQueueDispatcher = it.dispatcher
        }
    }

    fun provePromotionThenRetry(
        durableKey: String,
        fallbackKey: String,
        queuedIds: List<String>,
        firstPayload: String,
        secondPayload: String,
        replacementDraft: String,
    ) {
        val promoted = store.itemsFor(durableKey)
        assertEquals("promotion must preserve both exact row identities", queuedIds, promoted.map { it.id })
        assertEquals(listOf(firstPayload, secondPayload), promoted.map { it.cleanText })
        assertEquals(OutboundState.Failed, promoted.first().state)
        assertEquals(OUTBOUND_MAX_AUTO_ATTEMPTS, promoted.first().attemptCount)
        assertEquals(
            ISSUE1602_GENUINE_NON_TRANSPORT_FAILURE_MESSAGE,
            promoted.first().lastError,
        )
        assertEquals(OutboundState.Queued, promoted[1].state)
        assertEquals(replacementDraft, composer.uiState.value.draft)
        assertEquals(
            "promoted draft must be immediately visible through the ordered persistence view",
            replacementDraft,
            composer.loadComposerDraft(durableKey),
        )
        compose.waitUntil(timeoutMillis = uiTimeoutMs) {
            composer.composerDraftStore.load(durableKey) == replacementDraft &&
                composer.composerDraftStore.load(fallbackKey) == null
        }
        assertEquals(
            "promoted draft must eventually reach the durable backing slot",
            replacementDraft,
            composer.composerDraftStore.load(durableKey),
        )
        assertEquals(
            "obsolete fallback draft slot must eventually be removed",
            null,
            composer.composerDraftStore.load(fallbackKey),
        )

        val youngerRelease = CountDownLatch(1).also(releases::add)
        val youngerClaimed = CountDownLatch(1)
        PromptComposerOutboundDrainTestSeams.beforeEmit = { row ->
            if (row.id == queuedIds[1]) {
                youngerClaimed.countDown()
                check(youngerRelease.await(connectedTimeoutMs, TimeUnit.MILLISECONDS)) {
                    "issue1602 younger recovered send was never released"
                }
            }
        }
        requireNotNull(reconnectDrain).release.countDown()
        assertTrue(
            "the recovered drain must claim the younger row while leaving the head parked",
            youngerClaimed.await(connectedTimeoutMs, TimeUnit.MILLISECONDS),
        )
        assertEquals(queuedIds, store.itemsFor(durableKey).map { it.id })
        val parkedHead = requireNotNull(store.item(queuedIds.first()))
        assertEquals(OutboundState.Failed, parkedHead.state)
        assertEquals(OUTBOUND_MAX_AUTO_ATTEMPTS, parkedHead.attemptCount)
        assertEquals(ISSUE1602_GENUINE_NON_TRANSPORT_FAILURE_MESSAGE, parkedHead.lastError)
        assertEquals(OutboundState.InFlight, requireNotNull(store.item(queuedIds[1])).state)
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true).performClick()
        waitForComposerReady(true)
        compose.onNodeWithTag(COMPOSER_OUTBOUND_QUEUE_TOGGLE_TAG, useUnmergedTree = true).performClick()

        val parkedRowTag = composerOutboundQueueItemRowTestTag(queuedIds.first())
        val youngerRowTag = composerOutboundQueueItemRowTestTag(queuedIds[1])
        val parkedStatusTag = composerOutboundQueueStatusTestTag(queuedIds.first())
        val youngerStatusTag = composerOutboundQueueStatusTestTag(queuedIds[1])
        val retryTag = composerOutboundQueueRetryTestTag(queuedIds.first())
        val withinParkedRow = hasAnyAncestor(hasTestTag(parkedRowTag))
        val withinYoungerRow = hasAnyAncestor(hasTestTag(youngerRowTag))
        val waitingStatus = hasText("Waiting — another prompt is still sending") and
            hasAnyAncestor(hasTestTag(parkedStatusTag)) and
            withinParkedRow
        val waitingRetryControl = hasTestTag(retryTag) and withinParkedRow
        val waitingRetryLabel = hasText("Waiting…") and
            hasAnyAncestor(hasTestTag(retryTag)) and
            withinParkedRow
        val youngerSendingStatus = hasText("Sending") and
            hasAnyAncestor(hasTestTag(youngerStatusTag)) and
            withinYoungerRow
        compose.onAllNodes(waitingStatus, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodes(waitingRetryControl, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodes(waitingRetryLabel, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodes(youngerSendingStatus, useUnmergedTree = true).assertCountEquals(1)

        compose.onNode(waitingStatus, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        val waitingRetryNode = compose.onNode(waitingRetryControl, useUnmergedTree = true)
            // The status copy can be visible while its trailing action is clipped
            // by the bounded viewport. Scroll the exact row-owned physical
            // control before asserting its label or reachability.
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
        assertRetryControlFullyInStatusViewport(
            rowTag = parkedRowTag,
            retryTag = retryTag,
            retryNode = waitingRetryNode,
            stage = "waiting behind younger real send",
        )
        compose.onNode(waitingRetryLabel, useUnmergedTree = true).assertIsDisplayed()
        captureViewport("issue1602-waiting-control-behind-real-send")
        compose.onNode(youngerSendingStatus, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        captureViewport("issue1602-younger-real-send")

        youngerRelease.countDown()
        PromptComposerOutboundDrainTestSeams.beforeEmit = null
        compose.waitUntil(timeoutMillis = connectedTimeoutMs) {
            store.itemsFor(durableKey).let { rows ->
                rows.size == 1 && rows.single().id == queuedIds.first() &&
                    rows.single().state == OutboundState.Failed &&
                    rows.single().attemptCount == OUTBOUND_MAX_AUTO_ATTEMPTS &&
                    rows.single().lastError == ISSUE1602_GENUINE_NON_TRANSPORT_FAILURE_MESSAGE
            }
        }
        assertEquals(
            "the younger prompt must physically submit while the exhausted head stays parked",
            listOf(secondPayload),
            readSubmitLedger().map { it.second },
        )

        compose.onAllNodes(waitingRetryControl, useUnmergedTree = true).assertCountEquals(1)
        val enabledRetryNode = compose.onNode(waitingRetryControl, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
        assertRetryControlFullyInStatusViewport(
            rowTag = parkedRowTag,
            retryTag = retryTag,
            retryNode = enabledRetryNode,
            stage = "enabled explicit Retry",
        )
        val manual = blockedDispatcher("issue1602-manual-retry-drain")
        composer.outboundQueueDispatcher = manual.dispatcher
        val retriedRelease = CountDownLatch(1).also(releases::add)
        val retriedClaimed = CountDownLatch(1)
        PromptComposerOutboundDrainTestSeams.beforeEmit = { row ->
            if (row.id == queuedIds.first()) {
                retriedClaimed.countDown()
                check(retriedRelease.await(connectedTimeoutMs, TimeUnit.MILLISECONDS)) {
                    "issue1602 retried send was never released"
                }
            }
        }
        val claimsBeforeRetry = claimCount(queuedIds.first())
        enabledRetryNode.performTouchInput { click() }
        val retryingStatus = hasText("Retrying — starting delivery") and
            hasAnyAncestor(hasTestTag(parkedStatusTag)) and
            withinParkedRow
        val retryingLabel = hasText("Retrying…") and
            hasAnyAncestor(hasTestTag(retryTag)) and
            withinParkedRow
        compose.onAllNodes(retryingStatus, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodes(retryingLabel, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodes(waitingRetryControl, useUnmergedTree = true).assertCountEquals(1)
        compose.onNode(retryingStatus, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        val retryingControlNode = compose.onNode(waitingRetryControl, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
        assertRetryControlFullyInStatusViewport(
            rowTag = parkedRowTag,
            retryTag = retryTag,
            retryNode = retryingControlNode,
            stage = "Retrying after physical tap",
        )
        compose.onNode(retryingLabel, useUnmergedTree = true).assertIsDisplayed()
        captureViewport("issue1602-retrying-after-real-tap")
        assertEquals(listOf(secondPayload), readSubmitLedger().map { it.second })

        manual.release.countDown()
        assertTrue(
            "the real Retry must claim the exact parked row",
            retriedClaimed.await(connectedTimeoutMs, TimeUnit.MILLISECONDS),
        )
        assertEquals(OutboundState.InFlight, requireNotNull(store.item(queuedIds.first())).state)
        assertEquals("the real Retry must add exactly one new physical claim", claimsBeforeRetry + 1, claimCount(queuedIds.first()))
        val retriedSendingStatus = hasText("Sending") and
            hasAnyAncestor(hasTestTag(parkedStatusTag)) and
            withinParkedRow
        compose.onAllNodes(retriedSendingStatus, useUnmergedTree = true).assertCountEquals(1)
        compose.onNode(retriedSendingStatus, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        captureViewport("issue1602-sending-after-retry")
        assertEquals(listOf(secondPayload), readSubmitLedger().map { it.second })
        retriedRelease.countDown()
        PromptComposerOutboundDrainTestSeams.beforeEmit = null
        compose.waitUntil(timeoutMillis = connectedTimeoutMs) { store.itemsFor(durableKey).isEmpty() }
        assertEquals(
            "server ledger must record one younger Enter then one explicit-Retry Enter",
            listOf(secondPayload, firstPayload),
            readSubmitLedger().map { it.second },
        )
        restoreProductionDispatcher()
    }

    private fun assertRetryControlFullyInStatusViewport(
        rowTag: String,
        retryTag: String,
        retryNode: androidx.compose.ui.test.SemanticsNodeInteraction,
        stage: String,
    ) {
        compose.assertNodeFullyWithinOwningRoot(retryTag, useUnmergedTree = true)
        val retryBounds = retryNode.fetchSemanticsNode().boundsInRoot
        val rowBounds = compose.onNodeWithTag(rowTag, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val viewportBounds = compose.onNodeWithTag(
            COMPOSER_STATUS_VIEWPORT_TAG,
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$stage Retry must be non-empty and fully contained by exact row: " +
                "control=$retryBounds row=$rowBounds",
            retryBounds.width > 0f &&
                retryBounds.height > 0f &&
                retryBounds.left >= rowBounds.left &&
                retryBounds.top >= rowBounds.top &&
                retryBounds.right <= rowBounds.right &&
                retryBounds.bottom <= rowBounds.bottom,
        )
        assertTrue(
            "$stage Retry must be non-empty and fully contained by bounded status viewport: " +
                "control=$retryBounds viewport=$viewportBounds",
            retryBounds.width > 0f &&
                retryBounds.height > 0f &&
                retryBounds.left >= viewportBounds.left &&
                retryBounds.top >= viewportBounds.top &&
                retryBounds.right <= viewportBounds.right &&
                retryBounds.bottom <= viewportBounds.bottom,
        )
    }

    override fun close() {
        releases.forEach { it.countDown() }
        PromptComposerOutboundDrainTestSeams.beforeEmit = null
        statusLayoutRegistration.close()
        restoreProductionDispatcher()
    }

    private fun restoreProductionDispatcher() {
        composer.draftPersistence.dispatcherOverrideForTest = draftPersistenceDispatcherBeforeJourney
        composer.outboundQueueDispatcher = Dispatchers.IO
        dispatchers.forEach { it.close() }
        executors.forEach { it.shutdown() }
        dispatchers.clear()
        executors.clear()
    }

    private fun blockedDispatcher(name: String): BlockedDispatcher {
        val release = CountDownLatch(1).also(releases::add)
        val blocked = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, name) }
        val dispatcher = executor.asCoroutineDispatcher()
        executors += executor
        dispatchers += dispatcher
        executor.execute {
            blocked.countDown()
            check(release.await(connectedTimeoutMs, TimeUnit.MILLISECONDS)) { "$name was never released" }
        }
        assertTrue("$name must own its worker", blocked.await(uiTimeoutMs, TimeUnit.MILLISECONDS))
        return BlockedDispatcher(dispatcher, release)
    }

    private data class BlockedDispatcher(
        val dispatcher: ExecutorCoroutineDispatcher,
        val release: CountDownLatch,
    )
}
