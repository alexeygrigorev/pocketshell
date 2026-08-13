package com.pocketshell.app.composer

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.app.proof.signals.assertNodeFullyWithinOwningRoot
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reopened #1602 copy, verbatim from [outboundRetryActionState]: the LABEL the
 * blocked control carries, and the longer per-row explanation. The label is the
 * device-independent half of the anti-"tappable silent" contract — it travels
 * with the control, so it is readable wherever the control itself is reachable.
 */
private const val WAITING_BEHIND_OWNER_LABEL = "Waiting…"
private const val WAITING_BEHIND_OWNER_STATUS = "Waiting — another prompt is still sending"

/**
 * Issue #900: connected proof for the foreground outbound queue surface.
 * The store/VM tests own persistence and target filtering; this test pins the
 * actual composer UI affordances: collapsed count, expanded rows, delete, and
 * manual retry callbacks.
 */
@RunWith(AndroidJUnit4::class)
class PromptComposerOutboundQueueTest {

    @Test
    fun stableQueueRowSelectorDisambiguatesPayloadAlsoPresentInDraft() {
        val payload = "same payload in draft and queue"
        val item = OutboundItem(
            id = "stable-row-id",
            sessionKey = "1/a",
            cleanText = payload,
            state = OutboundState.Failed,
            createdAtMs = 1L,
        )
        val rowTag = composerOutboundQueueItemRowTestTag(item.id)
        compose.setContent {
            PocketShellTheme {
                SheetContent(
                    state = PromptComposerViewModel.UiState(draft = payload),
                    onClose = {}, onDraftChange = {}, onMicTap = {}, onSend = {},
                    outboundQueueItems = listOf(item),
                    outboundQueueExpanded = true,
                )
            }
        }

        assertTrue(
            "global payload lookup must remain ambiguous so the stable row selector is load-bearing",
            compose.onAllNodesWithText(payload, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().size > 1,
        )
        compose.onNode(
            hasText(payload, substring = true) and hasAnyAncestor(hasTestTag(rowTag)),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        val statusTag = composerOutboundQueueStatusTestTag(item.id)
        compose.onNode(
            hasTestTag(statusTag) and hasAnyAncestor(hasTestTag(rowTag)),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        compose.onAllNodes(
            hasText(outboundQueueStateLabel(item)) and
                hasAnyAncestor(hasTestTag(statusTag)) and
                hasAnyAncestor(hasTestTag(rowTag)),
            useUnmergedTree = true,
        ).assertCountEquals(1)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun offlineModalRowsOwnGeometryCopyAndScrollableDisabledRetryControls() {
        val rows = listOf(
            failedItem("offline-row-a", "first offline prompt", 1L),
            failedItem("offline-row-b", "second offline prompt", 2L),
        )
        val retried = mutableListOf<String>()
        val statusLayoutRegistration = PromptComposerQueueStatusLayoutTestObserver.install()
        try {
            compose.setContent {
                PocketShellTheme {
                    ComposerModalBottomSheet(
                        onDismissRequest = {},
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
                    ) {
                        SheetContent(
                            state = PromptComposerViewModel.UiState(),
                            onClose = {},
                            onDraftChange = {},
                            onMicTap = {},
                            onSend = {},
                            outboundQueueItems = rows,
                            outboundWireWritable = false,
                            outboundQueueExpanded = true,
                            onRetryOutboundItem = { retried += it },
                        )
                    }
                }
            }
            compose.waitUntil(timeoutMillis = 5_000L) {
                statusLayoutRegistration.observedRowIds().containsAll(rows.map { it.id })
            }
            assertEquals(
                "layout observer must bind exactly both modal row identities",
                rows.map { it.id }.toSet(),
                statusLayoutRegistration.observedRowIds(),
            )
            val retainedCoordinatePairsBeforeScrollByRowId = rows.associate { row ->
                row.id to requireNotNull(statusLayoutRegistration.currentCoordinates(row.id)) {
                    "missing retained pre-scroll row/status LayoutCoordinates pair for exact modal row ${row.id}"
                }
            }
            assertEquals(
                "delayed geometry proof must retain both exact row/status pairs before either row is scrolled",
                rows.map { it.id }.toSet(),
                retainedCoordinatePairsBeforeScrollByRowId.keys,
            )
            assertTrue(
                "each row/status pair must retain two independently-owned handles",
                retainedCoordinatePairsBeforeScrollByRowId.values.all { it.row !== it.status },
            )

            // The full MainActivity journey takes its reviewer screenshots in
            // FIFO order immediately before this proof. That real capture scrolls
            // row A and then row B, leaving the bounded status viewport at B.
            // Reproduce that production history through the same row semantics;
            // no synthetic offset or test-only layout seam is involved.
            //
            // Issue #2123: this seed is history, NOT a precondition. It exists so
            // the per-row assertions below run from an ARBITRARY prior scroll
            // offset (each row must still be reachable afterwards). Nothing below
            // may assert that a particular node is on/off screen AT this offset —
            // that is a viewport-size accident, not a product property, and it is
            // what made this proof red on CI.
            val seededViewportRowIds = buildList {
                rows.forEach { seedRow ->
                    compose.onNodeWithTag(
                        composerOutboundQueueItemRowTestTag(seedRow.id),
                        useUnmergedTree = true,
                    ).performScrollTo().assertIsDisplayed()
                    add(seedRow.id)
                }
            }
            assertEquals(
                "FIFO viewport seed must finish on the lower exact queue row",
                rows.map { it.id },
                seededViewportRowIds,
            )

            val provenRowIds = buildList {
                rows.forEach { row ->
                    val rowTag = composerOutboundQueueItemRowTestTag(row.id)
                    val statusTag = composerOutboundQueueStatusTestTag(row.id)
                    val retryTag = composerOutboundQueueRetryTestTag(row.id)
                    val withinExactRow = hasAnyAncestor(hasTestTag(rowTag))
                    val statusWrapper = hasTestTag(statusTag) and withinExactRow
                    val statusLabel = hasText("Waiting — connection is offline") and
                        hasAnyAncestor(hasTestTag(statusTag)) and
                        withinExactRow
                    val offlineRetryControl = hasTestTag(retryTag) and withinExactRow

                    compose.onAllNodes(statusLabel, useUnmergedTree = true).assertCountEquals(1)
                    compose.onAllNodes(statusWrapper, useUnmergedTree = true).assertCountEquals(1)
                    // Return from the lower-row capture position through the exact
                    // row-level action used by the MainActivity journey. A row can
                    // participate in the viewport while its trailing Retry remains
                    // clipped; only scrolling the physical action proves reachability.
                    compose.onNodeWithTag(rowTag, useUnmergedTree = true)
                        .performScrollTo()
                        .assertIsDisplayed()
                    compose.assertNodeFullyWithinOwningRoot(rowTag, useUnmergedTree = true)
                    compose.waitForIdle()
                    var latestGeometry = statusLayoutRegistration.currentWindowGeometry(row.id)
                    val geometryWait = runCatching {
                        compose.waitUntil(timeoutMillis = 5_000L) {
                            statusLayoutRegistration.currentWindowGeometry(row.id).also {
                                latestGeometry = it
                            }.isCurrentAttachedNonEmptyContainedFullWidthPair()
                        }
                    }
                    if (geometryWait.isFailure) {
                        throw AssertionError(
                            "exact modal row/status raw-window LayoutCoordinates did not settle: " +
                                latestGeometry.diagnosticSummary(),
                            geometryWait.exceptionOrNull(),
                        )
                    }
                    assertTrue(
                        "status wrapper must have current attached, non-empty, fully-contained full-width " +
                            "raw-window LayoutCoordinates in exact modal row: ${latestGeometry.diagnosticSummary()}",
                        latestGeometry.isCurrentAttachedNonEmptyContainedFullWidthPair(),
                    )
                    compose.onAllNodes(offlineRetryControl, useUnmergedTree = true).assertCountEquals(1)
                    val offlineRetryNode = compose.onNode(offlineRetryControl, useUnmergedTree = true)
                    val retryNodeBeforeExactScroll = offlineRetryNode.fetchSemanticsNode()
                    val retryRawBoundsBeforeExactScroll = retryNodeBeforeExactScroll.boundsInRoot
                    val rowRawBoundsBeforeExactScroll = compose.onNodeWithTag(
                        rowTag,
                        useUnmergedTree = true,
                    ).fetchSemanticsNode().boundsInRoot
                    assertTrue(
                        "precondition: exact offline Retry must be laid out inside its exact row before " +
                            "visibility scroll: control=$retryRawBoundsBeforeExactScroll " +
                            "row=$rowRawBoundsBeforeExactScroll",
                        retryRawBoundsBeforeExactScroll.isNonEmptyAndFullyContainedBy(
                            rowRawBoundsBeforeExactScroll,
                        ),
                    )
                    // Issue #2123: an `assertIsNotDisplayed()` on the first row's
                    // Retry used to stand here. It asserted a VIEWPORT-SIZE
                    // ACCIDENT — "this control happens to be off-screen at this
                    // scroll offset" — which is true only while a row is taller
                    // than the bounded status region. It is not a product property,
                    // it was already false on CI, and reproducing it locally showed
                    // it is false on the dev-box AVD too. Deleted, not relaxed: the
                    // control's reality is proven by assertCountEquals(1) plus the
                    // raw-bounds containment above, and its REACHABILITY by the
                    // scroll + bounded-viewport containment below.
                    offlineRetryNode
                        // The 96dp status viewport is intentionally bounded. Scroll
                        // the exact trailing action, not only its potentially taller
                        // row, to reproduce the full MainActivity sheet semantics.
                        .performScrollTo()
                    compose.waitForIdle()
                    val displayedAfterExactScroll = runCatching {
                        offlineRetryNode.assertIsDisplayed()
                    }
                    if (displayedAfterExactScroll.isFailure) {
                        throw AssertionError(
                            "postcondition: exact offline Retry must be displayed after its own scroll",
                            displayedAfterExactScroll.exceptionOrNull(),
                        )
                    }
                    offlineRetryNode.assertIsNotEnabled()
                    // Issue #2123: the anti-"tappable silent" property that does
                    // NOT depend on any geometry — the blocked reason travels ON
                    // the control. Whatever the viewport height or scroll offset,
                    // a user who reaches this action reads "Offline", never a bare
                    // "Retry" that silently does nothing.
                    compose.onAllNodes(
                        hasText("Offline") and
                            hasAnyAncestor(hasTestTag(retryTag)) and
                            withinExactRow,
                        useUnmergedTree = true,
                    ).assertCountEquals(1)
                    val retryBoundsAfterExactScroll = offlineRetryNode.fetchSemanticsNode().boundsInRoot
                    val statusViewportBoundsAfterExactScroll = compose.onNodeWithTag(
                        COMPOSER_STATUS_VIEWPORT_TAG,
                        useUnmergedTree = true,
                    ).fetchSemanticsNode().boundsInRoot
                    assertTrue(
                        "exact offline Retry must be fully inside the bounded status viewport after scroll: " +
                            "control=$retryBoundsAfterExactScroll " +
                            "viewport=$statusViewportBoundsAfterExactScroll",
                        retryBoundsAfterExactScroll.isNonEmptyAndFullyContainedBy(
                            statusViewportBoundsAfterExactScroll,
                        ),
                    )
                    compose.assertNodeFullyWithinOwningRoot(retryTag, useUnmergedTree = true)
                    offlineRetryNode.performTouchInput { click() }
                    compose.waitForIdle()
                    add(row.id)
                }
            }
            assertEquals(rows.map { it.id }, provenRowIds)
            assertTrue("disabled offline Retry pointer taps must remain no-ops", retried.isEmpty())
        } finally {
            statusLayoutRegistration.close()
        }
    }

    private fun Rect.isNonEmptyAndFullyContainedBy(
        outer: Rect,
    ): Boolean = width > 0f &&
        height > 0f &&
        left >= outer.left &&
        top >= outer.top &&
        right <= outer.right &&
        bottom <= outer.bottom

    @Test
    fun statusLedSingleRowsOwnCopyProgressAndResendPresentation() {
        val inFlight = OutboundItem(
            id = "active-a",
            sessionKey = "1/a",
            cleanText = "prompt A",
            state = OutboundState.InFlight,
            createdAtMs = 1L,
        )
        compose.setContent {
            PocketShellTheme {
                SheetContent(
                    state = PromptComposerViewModel.UiState(sendInFlight = true, draft = "draft B"),
                    onClose = {}, onDraftChange = {}, onMicTap = {}, onSend = {},
                    outboundQueueItems = listOf(inFlight),
                )
            }
        }
        compose.onNodeWithText("Sending").assertIsDisplayed()
        compose.onNodeWithText(" · “prompt A”").assertIsDisplayed()
        compose.onNodeWithText("Send").assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_SEND_IN_FLIGHT_TAG).assertDoesNotExist()
        compose.assertNodeFullyWithinRoot(COMPOSER_OUTBOUND_QUEUE_BANNER_TAG)
    }

    @Test
    fun failedAUsesAmberWordsAndRetriesExistingIdWhileDraftBStaysSend() {
        val failed = failedItem("failed-a", "prompt A", 1L)
        val retried = mutableListOf<String>()
        compose.setContent {
            PocketShellTheme {
                SheetContent(
                    state = PromptComposerViewModel.UiState(),
                    onClose = {}, onDraftChange = {}, onMicTap = {}, onSend = {},
                    outboundQueueItems = listOf(failed),
                    onRetryOutboundItem = { retried += it },
                )
            }
        }
        compose.onNodeWithText("Failed — tap Retry").assertIsDisplayed()
        compose.onNodeWithText(" · “prompt A”").assertIsDisplayed()
        assertNodeContainsAmberInk(COMPOSER_OUTBOUND_QUEUE_STATUS_TAG)
        compose.onNodeWithText("Resend").performClick()
        compose.waitForIdle()
        assertEquals(listOf("failed-a"), retried)

    }

    @Test
    fun failedAPreviewRemainsWhileDraftBPrimaryActionIsSend() {
        val failed = failedItem("failed-a", "prompt A", 1L)
        compose.setContent {
            PocketShellTheme {
                SheetContent(
                    state = PromptComposerViewModel.UiState(draft = "draft B"),
                    onClose = {}, onDraftChange = {}, onMicTap = {}, onSend = {},
                    outboundQueueItems = listOf(failed),
                )
            }
        }
        compose.onNodeWithText(" · “prompt A”").assertIsDisplayed()
        compose.onNodeWithText("Send").assertIsDisplayed()
        compose.onNodeWithText("Resend").assertDoesNotExist()
    }

    @Test
    fun multiFailureColorsOnlyFailureSegmentAmber() {
        val failed = failedItem("failed-a", "prompt A", 1L)
        val queued = OutboundItem(
            id = "queued-b",
            sessionKey = "1/a",
            cleanText = "prompt B",
            state = OutboundState.Queued,
            createdAtMs = 2L,
        )
        compose.setContent {
            PocketShellTheme {
                SheetContent(
                    state = PromptComposerViewModel.UiState(),
                    onClose = {},
                    onDraftChange = {},
                    onMicTap = {},
                    onSend = {},
                    outboundQueueItems = listOf(failed, queued),
                )
            }
        }

        compose.onNodeWithText("2 queued").assertIsDisplayed()
        compose.onNodeWithText(" · 1 failed").assertIsDisplayed()
        assertNodeDoesNotContainAmberInk(COMPOSER_OUTBOUND_QUEUE_STATUS_TAG)
        assertNodeContainsAmberInk(COMPOSER_OUTBOUND_QUEUE_FAILURE_SEGMENT_TAG)
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun outboundQueueRendersExpandedRowsAndDeleteRetryCallbacks() {
        val item = OutboundItem(
            id = "queued-1",
            sessionKey = "1/a",
            cleanText = "summarize the failing test output",
            attachments = listOf(DurableAttachmentRef("/tmp/log.txt", "log.txt", "text/plain")),
            state = OutboundState.Queued,
            createdAtMs = System.currentTimeMillis(),
        )
        val deletedIds = mutableListOf<String>()
        val retriedIds = mutableListOf<String>()

        compose.setContent {
            PocketShellTheme {
                var expanded by remember { mutableStateOf(false) }
                SheetContent(
                    state = PromptComposerViewModel.UiState(),
                    onClose = {},
                    onDraftChange = {},
                    onMicTap = {},
                    onSend = {},
                    outboundQueueItems = listOf(item),
                    outboundQueueExpanded = expanded,
                    onToggleOutboundQueue = { expanded = !expanded },
                    onDeleteOutboundItem = { deletedIds += it },
                    onRetryOutboundItem = { retriedIds += it },
                )
            }
        }

        compose.onNodeWithTag(COMPOSER_OUTBOUND_QUEUE_BANNER_TAG).assertIsDisplayed()
        compose.onNodeWithText("Queued — sending next").assertIsDisplayed()
        compose.onNodeWithTag(composerOutboundQueueItemRowTestTag(item.id)).assertDoesNotExist()

        compose.onNodeWithTag(COMPOSER_OUTBOUND_QUEUE_TOGGLE_TAG).performClick()
        compose.onNodeWithTag(composerOutboundQueueItemRowTestTag(item.id)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("summarize the failing test output").assertIsDisplayed()
        compose.onNodeWithText("1 attachment").assertIsDisplayed()

        compose.onNodeWithTag(composerOutboundQueueDeleteTestTag(item.id)).performClick()
        compose.onNodeWithTag(composerOutboundQueueRetryTestTag(item.id)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(item.id), deletedIds)
        assertEquals(listOf(item.id), retriedIds)
    }

    @Test
    fun outboundQueueFailedRowsExposeRetryAndDeleteCallbacks() {
        val item = OutboundItem(
            id = "failed-1",
            sessionKey = "1/a",
            cleanText = "send after reconnect",
            state = OutboundState.Failed,
            lastError = "connection lost",
            createdAtMs = System.currentTimeMillis(),
        )
        val deletedIds = mutableListOf<String>()
        val retriedIds = mutableListOf<String>()

        compose.setContent {
            PocketShellTheme {
                SheetContent(
                    state = PromptComposerViewModel.UiState(),
                    onClose = {},
                    onDraftChange = {},
                    onMicTap = {},
                    onSend = {},
                    outboundQueueItems = listOf(item),
                    outboundQueueExpanded = true,
                    onDeleteOutboundItem = { deletedIds += it },
                    onRetryOutboundItem = { retriedIds += it },
                )
            }
        }

        compose.onNodeWithTag(composerOutboundQueueItemRowTestTag(item.id)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Failed — connection lost").assertIsDisplayed()

        compose.onNodeWithTag(composerOutboundQueueDeleteTestTag(item.id)).performClick()
        compose.onNodeWithTag(composerOutboundQueueRetryTestTag(item.id)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(item.id), deletedIds)
        assertEquals(listOf(item.id), retriedIds)
    }

    @Test
    fun outboundQueueCollapsedRetryInvokesOldestQueuedOrFailedRow() {
        val failed = OutboundItem(
            id = "failed-collapsed",
            sessionKey = "1/a",
            cleanText = "send after reconnect",
            state = OutboundState.Failed,
            lastError = "connection lost",
            createdAtMs = 1L,
        )
        val queued = OutboundItem(
            id = "queued-collapsed",
            sessionKey = "1/a",
            cleanText = "queued behind it",
            state = OutboundState.Queued,
            createdAtMs = 2L,
        )
        val retriedIds = mutableListOf<String>()

        compose.setContent {
            PocketShellTheme {
                SheetContent(
                    state = PromptComposerViewModel.UiState(),
                    onClose = {},
                    onDraftChange = {},
                    onMicTap = {},
                    onSend = {},
                    outboundQueueItems = listOf(failed, queued),
                    outboundQueueExpanded = false,
                    onRetryOutboundItem = { retriedIds += it },
                )
            }
        }

        compose.onNodeWithTag(COMPOSER_OUTBOUND_QUEUE_BANNER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(composerOutboundQueueItemRowTestTag(failed.id)).assertDoesNotExist()
        compose.onNodeWithTag(composerOutboundQueueRetryTestTag(failed.id)).assertIsDisplayed()
        compose.onNodeWithTag(composerOutboundQueueRetryTestTag(queued.id)).assertDoesNotExist()

        compose.onNodeWithTag(composerOutboundQueueRetryTestTag(failed.id)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(failed.id), retriedIds)
    }

    @Test
    fun outboundQueueActiveRowsStayVisibleWithoutRetryOrDeleteControls() {
        val inFlight = OutboundItem(
            id = "in-flight-1",
            sessionKey = "1/a",
            cleanText = "already sending",
            state = OutboundState.InFlight,
            createdAtMs = System.currentTimeMillis(),
        )
        val uploading = OutboundItem(
            id = "uploading-1",
            sessionKey = "1/a",
            cleanText = "uploading first",
            state = OutboundState.Uploading,
            createdAtMs = System.currentTimeMillis(),
        )

        compose.setContent {
            PocketShellTheme {
                SheetContent(
                    state = PromptComposerViewModel.UiState(),
                    onClose = {},
                    onDraftChange = {},
                    onMicTap = {},
                    onSend = {},
                    outboundQueueItems = listOf(inFlight, uploading),
                    outboundQueueExpanded = true,
                )
            }
        }

        compose.onNodeWithTag(composerOutboundQueueItemRowTestTag(inFlight.id)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sending").assertIsDisplayed()
        compose.onNodeWithTag(composerOutboundQueueDeleteTestTag(inFlight.id)).assertDoesNotExist()
        compose.onNodeWithTag(composerOutboundQueueRetryTestTag(inFlight.id)).assertDoesNotExist()

        compose.onNodeWithTag(composerOutboundQueueItemRowTestTag(uploading.id)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Uploading attachments").assertIsDisplayed()
        compose.onNodeWithTag(composerOutboundQueueDeleteTestTag(uploading.id)).assertDoesNotExist()
        compose.onNodeWithTag(composerOutboundQueueRetryTestTag(uploading.id)).assertDoesNotExist()
    }

    @Test
    fun retryBehindHealthyOwnerIsDisabledAndExplainsWaitingInsteadOfTappableSilent() {
        val active = OutboundItem(
            id = "active-owner",
            sessionKey = "1/a",
            cleanText = "currently sending",
            state = OutboundState.InFlight,
            createdAtMs = 1L,
        )
        val waiting = OutboundItem(
            id = "waiting-retry",
            sessionKey = "1/a",
            cleanText = "must remain queued",
            state = OutboundState.Failed,
            lastError = "connection lost",
            createdAtMs = 2L,
        )
        val retried = mutableListOf<String>()
        // Issue #2123: both queue states are exercised from ONE composition — the
        // collapsed banner the user actually lands on, and the expanded row list.
        // The test owns the flag directly (rather than tapping the toggle) so the
        // state transition is deterministic and independent of whether a tap on a
        // disabled child bubbles to the header's clickable.
        var expanded by mutableStateOf(false)
        compose.setContent {
            PocketShellTheme {
                SheetContent(
                    state = PromptComposerViewModel.UiState(sendInFlight = true),
                    onClose = {}, onDraftChange = {}, onMicTap = {}, onSend = {},
                    outboundQueueItems = listOf(active, waiting),
                    outboundRetryBlockedByHealthyOwner = true,
                    outboundQueueExpanded = expanded,
                    onRetryOutboundItem = { retried += it },
                )
            }
        }

        val retryTag = composerOutboundQueueRetryTestTag(waiting.id)
        val rowTag = composerOutboundQueueItemRowTestTag(waiting.id)
        val statusTag = composerOutboundQueueStatusTestTag(waiting.id)

        // (1) COLLAPSED — the state the user lands in, and the only one that needs
        // no scrolling at all. The blocked reason travels ON the control: the
        // header action reads "Waiting…" instead of a bare "Retry", it is
        // disabled, and it is contained inside the bounded status viewport as-is.
        compose.onAllNodes(
            hasText(WAITING_BEHIND_OWNER_LABEL) and hasAnyAncestor(hasTestTag(retryTag)),
            useUnmergedTree = true,
        ).assertCountEquals(1)
        compose.onNodeWithTag(retryTag).assertIsNotEnabled()
        assertFullyWithinBoundedStatusViewport(
            compose.onNodeWithTag(retryTag),
            "collapsed blocked Retry",
        )
        // A real pointer tap (not a semantics action, which disabled nodes do not
        // expose) proves the apparently present control cannot silently dispatch.
        compose.onNodeWithTag(retryTag).performTouchInput { click() }
        compose.waitForIdle()
        assertTrue(
            "collapsed blocked Retry pointer taps must not invoke a silent retry callback",
            retried.isEmpty(),
        )

        // (2) EXPANDED — the per-row control plus its longer explanation.
        //
        // Issue #2123: this used to assert the explanation was `assertIsDisplayed()`
        // with no scroll. The composer's status region is a deliberately BOUNDED
        // 96dp strip above the editor (#1613/#1619/#1622), so a second queue row is
        // fully clipped at rest — measured on a Pixel 7 AVD, the row's clipped
        // bounds are Rect.Zero until it is scrolled to. That assertion was false on
        // every device, not only CI's. The user reaches this row by scrolling, so
        // the proof does too, and then asserts CONTAINMENT inside that bounded
        // viewport (F3) rather than absolute device-viewport visibility.
        compose.runOnUiThread { expanded = true }
        compose.waitForIdle()

        val explanation = hasText(WAITING_BEHIND_OWNER_STATUS) and
            hasAnyAncestor(hasTestTag(statusTag)) and
            hasAnyAncestor(hasTestTag(rowTag))
        compose.onAllNodes(explanation, useUnmergedTree = true).assertCountEquals(1)
        compose.onNode(explanation, useUnmergedTree = true).performScrollTo()
        compose.waitForIdle()
        assertFullyWithinBoundedStatusViewport(
            compose.onNode(explanation, useUnmergedTree = true),
            "blocked-row explanation",
        )

        val rowRetry = hasTestTag(retryTag) and hasAnyAncestor(hasTestTag(rowTag))
        compose.onAllNodes(rowRetry, useUnmergedTree = true).assertCountEquals(1)
        compose.onNode(rowRetry, useUnmergedTree = true).performScrollTo()
        compose.waitForIdle()
        assertFullyWithinBoundedStatusViewport(
            compose.onNode(rowRetry, useUnmergedTree = true),
            "expanded blocked Retry",
        )
        compose.onNode(rowRetry, useUnmergedTree = true).assertIsNotEnabled()
        compose.onAllNodes(
            hasText(WAITING_BEHIND_OWNER_LABEL) and
                hasAnyAncestor(hasTestTag(retryTag)) and
                hasAnyAncestor(hasTestTag(rowTag)),
            useUnmergedTree = true,
        ).assertCountEquals(1)
        compose.onNode(rowRetry, useUnmergedTree = true).performTouchInput { click() }
        compose.waitForIdle()
        assertTrue(
            "expanded blocked Retry pointer taps must not invoke a silent retry callback",
            retried.isEmpty(),
        )
    }

    /**
     * Containment inside the composer's BOUNDED status viewport at the CURRENT
     * scroll offset (issue #657 F3, issue #2123).
     *
     * `boundsInRoot` is clipped by ancestors, so a node scrolled out of the bounded
     * status region collapses to `Rect.Zero` and fails the non-empty half of this
     * check. That makes it a real "the user can see and reach this" assertion,
     * unlike `assertIsDisplayed()` (satisfied by mere layout participation) — and,
     * unlike an absolute device-viewport check, it does not encode how tall the
     * viewport happens to be or how many rows happen to fit in it.
     */
    private fun assertFullyWithinBoundedStatusViewport(
        node: SemanticsNodeInteraction,
        label: String,
    ) {
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag(COMPOSER_STATUS_VIEWPORT_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$label must be non-empty and fully inside the bounded composer status " +
                "viewport at the current scroll offset: node=$bounds viewport=$viewport",
            bounds.isNonEmptyAndFullyContainedBy(viewport),
        )
    }

    // ---------------------------------------------------------------------
    // Issue #1308: batch "Resend all"
    // ---------------------------------------------------------------------

    private fun failedItem(id: String, text: String, createdAtMs: Long) = OutboundItem(
        id = id,
        sessionKey = "1/a",
        cleanText = text,
        state = OutboundState.Failed,
        lastError = "connection lost",
        createdAtMs = createdAtMs,
    )

    private fun assertNodeContainsAmberInk(tag: String) {
        val bitmap = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .captureToImage().asAndroidBitmap()
        val expected = PocketShellColors.Amber.toArgb()
        try {
            val containsAmber = (0 until bitmap.height).any { y ->
                (0 until bitmap.width).any { x ->
                    val actual = bitmap.getPixel(x, y)
                    maxOf(
                        kotlin.math.abs(AndroidColor.red(actual) - AndroidColor.red(expected)),
                        kotlin.math.abs(AndroidColor.green(actual) - AndroidColor.green(expected)),
                        kotlin.math.abs(AndroidColor.blue(actual) - AndroidColor.blue(expected)),
                    ) <= 12
                }
            }
            assertTrue("Node tagged $tag must contain Amber ink", containsAmber)
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertNodeDoesNotContainAmberInk(tag: String) {
        val bitmap = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .captureToImage().asAndroidBitmap()
        val expected = PocketShellColors.Amber.toArgb()
        try {
            val containsAmber = (0 until bitmap.height).any { y ->
                (0 until bitmap.width).any { x ->
                    val actual = bitmap.getPixel(x, y)
                    maxOf(
                        kotlin.math.abs(AndroidColor.red(actual) - AndroidColor.red(expected)),
                        kotlin.math.abs(AndroidColor.green(actual) - AndroidColor.green(expected)),
                        kotlin.math.abs(AndroidColor.blue(actual) - AndroidColor.blue(expected)),
                    ) <= 12
                }
            }
            assertTrue("Node tagged $tag must not contain Amber ink", !containsAmber)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun resendAllButtonIsAbsentForASingleResendableRow() {
        // AC3: one unsent prompt already has its own tap-to-retry, so a batch
        // "Resend all" would be the #971/#987 double-affordance confusion — absent.
        compose.setContent {
            PocketShellTheme {
                SheetContent(
                    state = PromptComposerViewModel.UiState(),
                    onClose = {},
                    onDraftChange = {},
                    onMicTap = {},
                    onSend = {},
                    outboundQueueItems = listOf(failedItem("only-1", "just one", 1L)),
                    outboundQueueExpanded = true,
                )
            }
        }

        compose.onNodeWithTag(composerOutboundQueueRetryTestTag("only-1")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_OUTBOUND_QUEUE_RESEND_ALL_TAG).assertDoesNotExist()
    }

    @Test
    fun resendAllButtonIsAbsentWhenOnlyOneRowIsResendableAmongActiveRows() {
        // Two rows, but one is InFlight (not resendable) — only ONE is resendable,
        // so the batch button stays hidden (the resendable-count gate, not raw size).
        val failed = failedItem("failed-x", "retry me", 1L)
        val inFlight = OutboundItem(
            id = "in-flight-x",
            sessionKey = "1/a",
            cleanText = "on the wire",
            state = OutboundState.InFlight,
            createdAtMs = 2L,
        )
        compose.setContent {
            PocketShellTheme {
                SheetContent(
                    state = PromptComposerViewModel.UiState(),
                    onClose = {},
                    onDraftChange = {},
                    onMicTap = {},
                    onSend = {},
                    outboundQueueItems = listOf(failed, inFlight),
                    outboundQueueExpanded = true,
                )
            }
        }

        compose.onNodeWithTag(COMPOSER_OUTBOUND_QUEUE_RESEND_ALL_TAG).assertDoesNotExist()
    }

    @Test
    fun resendAllButtonIsVisibleContainedAndInvokesCallbackForTwoResendableRows() {
        // AC1: with >= 2 unsent prompts a "Resend all" button appears; tapping it
        // fires the batch re-arm exactly once. Containment (assertNodeFullyWithinRoot,
        // not a bare assertIsDisplayed) guards the F1/F3 "actually on-screen, not
        // clipped off an edge" property for this new bottom-chrome control.
        val first = failedItem("f-1", "first prompt", 1L)
        val second = failedItem("f-2", "second prompt", 2L)
        var resendAllCount = 0

        compose.setContent {
            PocketShellTheme {
                SheetContent(
                    state = PromptComposerViewModel.UiState(),
                    onClose = {},
                    onDraftChange = {},
                    onMicTap = {},
                    onSend = {},
                    outboundQueueItems = listOf(first, second),
                    outboundQueueExpanded = true,
                    onResendAllOutbound = { resendAllCount++ },
                )
            }
        }

        compose.onNodeWithTag(COMPOSER_OUTBOUND_QUEUE_RESEND_ALL_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Resend all (2)").assertIsDisplayed()
        compose.assertNodeFullyWithinRoot(COMPOSER_OUTBOUND_QUEUE_RESEND_ALL_TAG)
        // Per-row retry stays intact alongside the batch action.
        compose.onNodeWithTag(composerOutboundQueueRetryTestTag(first.id)).performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag(COMPOSER_OUTBOUND_QUEUE_RESEND_ALL_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, resendAllCount)
    }

}
