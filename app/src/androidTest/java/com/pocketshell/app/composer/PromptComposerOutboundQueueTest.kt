package com.pocketshell.app.composer

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #900: connected proof for the foreground outbound queue surface.
 * The store/VM tests own persistence and target filtering; this test pins the
 * actual composer UI affordances: collapsed count, expanded rows, delete, and
 * manual retry callbacks.
 */
@RunWith(AndroidJUnit4::class)
class PromptComposerOutboundQueueTest {

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
