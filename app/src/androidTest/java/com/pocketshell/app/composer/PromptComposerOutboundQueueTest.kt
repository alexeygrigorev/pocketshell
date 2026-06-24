package com.pocketshell.app.composer

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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #900: connected proof for the foreground outbound queue surface.
 * The store/VM tests own persistence and target filtering; this test pins the
 * actual composer UI affordances: collapsed count, expanded rows, and delete.
 */
@RunWith(AndroidJUnit4::class)
class PromptComposerOutboundQueueTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun outboundQueueRendersExpandedRowsAndDeleteCallback() {
        val item = OutboundItem(
            id = "queued-1",
            sessionKey = "1/a",
            cleanText = "summarize the failing test output",
            attachments = listOf(DurableAttachmentRef("/tmp/log.txt", "log.txt", "text/plain")),
            state = OutboundState.Queued,
            createdAtMs = System.currentTimeMillis(),
        )
        val deletedIds = mutableListOf<String>()

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
                )
            }
        }

        compose.onNodeWithTag(COMPOSER_OUTBOUND_QUEUE_BANNER_TAG).assertIsDisplayed()
        compose.onNodeWithText("1 unsent prompt").assertIsDisplayed()
        compose.onNodeWithTag(composerOutboundQueueItemRowTestTag(item.id)).assertDoesNotExist()

        compose.onNodeWithTag(COMPOSER_OUTBOUND_QUEUE_TOGGLE_TAG).performClick()
        compose.onNodeWithTag(composerOutboundQueueItemRowTestTag(item.id)).assertIsDisplayed()
        compose.onNodeWithText("summarize the failing test output").assertIsDisplayed()
        compose.onNodeWithText("1 attachment").assertIsDisplayed()

        compose.onNodeWithTag(composerOutboundQueueDeleteTestTag(item.id)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(item.id), deletedIds)
    }

    @Test
    fun outboundQueueActiveRowsStayVisibleWithoutDeleteControl() {
        val inFlight = OutboundItem(
            id = "in-flight-1",
            sessionKey = "1/a",
            cleanText = "already sending",
            state = OutboundState.InFlight,
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
                    outboundQueueItems = listOf(inFlight),
                    outboundQueueExpanded = true,
                )
            }
        }

        compose.onNodeWithTag(composerOutboundQueueItemRowTestTag(inFlight.id)).assertIsDisplayed()
        compose.onNodeWithText("Sending").assertIsDisplayed()
        compose.onNodeWithTag(composerOutboundQueueDeleteTestTag(inFlight.id)).assertDoesNotExist()
    }
}
