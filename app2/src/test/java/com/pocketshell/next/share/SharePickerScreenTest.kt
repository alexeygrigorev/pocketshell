package com.pocketshell.next.share

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rendered share screen on the host JVM (Robolectric).
 *
 * The states are asserted on the RENDERED tree because "still loading", "no
 * hosts configured", "uploading" and "it failed" are four different situations
 * that must not paint the same near-empty screen — the failure mode a state
 * class alone cannot rule out.
 */
@RunWith(AndroidJUnit4::class)
class SharePickerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var pickedHost: Long? = null
    private var retriedHost: Long? = null
    private var pickAnotherCount = 0
    private var finishedCount = 0

    @Test
    fun `the picker lists every host and reports the tapped one`() {
        setContent(
            ShareUiState(
                items = listOf("photo.png"),
                hosts = listOf(
                    ShareHostRow(1, "devbox", "alexey@rmthz", connected = true),
                    ShareHostRow(2, "laptop", "alexey@thinkpad", connected = false),
                ),
                hostsLoaded = true,
            ),
        )

        composeRule.onNodeWithText("devbox").assertIsDisplayed()
        composeRule.onNodeWithText("alexey@thinkpad").assertIsDisplayed()
        // The live host is marked so the user can tell where they already are
        // (the ui-kit Pill renders its label upper-cased).
        composeRule.onNodeWithText("CONNECTED").assertIsDisplayed()
        // What is being sent, and where it will land, are both stated up front
        // rather than discovered after the upload.
        composeRule.onNodeWithText("photo.png").assertIsDisplayed()
        composeRule.onNodeWithText("Send to ~/inbox/pocketshell on").assertIsDisplayed()

        composeRule.onNodeWithTag(shareHostRowTag(2)).performClick()

        assertEquals(2L, pickedHost)
    }

    @Test
    fun `a fresh install with no hosts is told what to do instead of shown a blank list`() {
        setContent(ShareUiState(items = listOf("photo.png"), hostsLoaded = true))

        composeRule.onNodeWithTag(SHARE_EMPTY_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("No hosts yet").assertIsDisplayed()
    }

    @Test
    fun `an upload in flight names the file and the destination`() {
        setContent(
            ShareUiState(
                items = listOf("a.txt", "b.txt"),
                hosts = listOf(ShareHostRow(1, "devbox", "alexey@rmthz", connected = true)),
                hostsLoaded = true,
                upload = ShareUploadState.Running("devbox", "Uploading a.txt (1 of 2)"),
            ),
        )

        composeRule.onNodeWithTag(SHARE_PROGRESS_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Uploading a.txt (1 of 2)").assertIsDisplayed()
        composeRule.onNodeWithText("to devbox:~/inbox/pocketshell").assertIsDisplayed()
        // No host rows while a transfer is running — a second tap would start a
        // second upload of the same files.
        composeRule.onNodeWithTag(shareHostRowTag(1)).assertDoesNotExist()
    }

    @Test
    fun `a successful share shows the exact remote path`() {
        val path = "/home/alexey/inbox/pocketshell/20240514-093012-photo.png"
        setContent(
            ShareUiState(
                items = listOf("photo.png"),
                hostsLoaded = true,
                upload = ShareUploadState.Success("devbox", listOf(path)),
            ),
        )

        composeRule.onNodeWithTag(SHARE_SUCCESS_TAG).assertIsDisplayed()
        // The path is what the user types (or dictates) at an agent next, so it
        // has to be on screen verbatim rather than summarised as "done".
        composeRule.onNodeWithText(path).assertIsDisplayed()
        composeRule.onNodeWithText("Sent to devbox").assertIsDisplayed()

        // Exactly ONE Done, in the header.
        composeRule.onAllNodesWithText("Done").assertCountEquals(1)
        composeRule.onNodeWithTag(SHARE_DONE_TAG).performClick()
        assertEquals(1, finishedCount)
    }

    @Test
    fun `a failure shows the reason and offers a retry and another host`() {
        setContent(
            ShareUiState(
                items = listOf("photo.png"),
                hosts = listOf(ShareHostRow(7, "devbox", "alexey@rmthz", connected = false)),
                hostsLoaded = true,
                upload = ShareUploadState.Failed("devbox", "Connection refused"),
            ),
        )

        composeRule.onNodeWithTag(SHARE_FAILURE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Connection refused").assertIsDisplayed()
        // A failure is not something to say "Done" to.
        composeRule.onNodeWithText("Done").assertDoesNotExist()

        composeRule.onNodeWithTag(SHARE_RETRY_TAG).performClick()
        assertEquals(7L, retriedHost)

        composeRule.onNodeWithTag(SHARE_PICK_ANOTHER_TAG).performClick()
        assertEquals(1, pickAnotherCount)
    }

    @Test
    fun `a partial failure still names what did land`() {
        val landed = "/home/alexey/inbox/pocketshell/20240514-093012-a.txt"
        setContent(
            ShareUiState(
                items = listOf("a.txt", "b.txt"),
                hostsLoaded = true,
                upload = ShareUploadState.Failed(
                    hostName = "devbox",
                    message = "Connection refused — 1 of 2 uploaded, failed: b.txt",
                    uploaded = listOf(landed),
                    failedNames = listOf("b.txt"),
                ),
            ),
        )

        composeRule.onNodeWithText(landed).assertIsDisplayed()
        // No host row matches the failed host name, so there is nothing to retry
        // onto — the screen must not offer a button that cannot work.
        composeRule.onNodeWithTag(SHARE_RETRY_TAG).assertDoesNotExist()
        assertNull(retriedHost)
    }

    private fun setContent(state: ShareUiState) {
        composeRule.setContent {
            PocketShellTheme {
                SharePickerScreen(
                    state = state,
                    onPickHost = { pickedHost = it },
                    onRetry = { retriedHost = it },
                    onPickAnother = { pickAnotherCount += 1 },
                    onFinished = { finishedCount += 1 },
                )
            }
        }
    }
}
