package com.pocketshell.next.hosts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The camera-independent scan guidance stays visible while the preview waits. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class QrScannerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `idle scan shows instructions and waiting copy`() {
        composeRule.setContent {
            PocketShellTheme {
                QrScannerInstructions(scanned = 0, total = 0)
            }
        }

        composeRule.onNodeWithTag(QR_SCANNER_INSTRUCTION_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Point your camera at the PocketShell QR code on your computer.")
            .assertIsDisplayed()
        composeRule.onNodeWithTag(QR_SCANNER_WAITING_TAG).assertIsDisplayed()
    }

    @Test
    fun `multipart scan replaces waiting copy with progress`() {
        composeRule.setContent {
            PocketShellTheme {
                QrScannerInstructions(scanned = 1, total = 3)
            }
        }

        composeRule.onNodeWithTag(QR_SCANNER_INSTRUCTION_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(QR_SCANNER_PROGRESS_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Scanned 1 of 3").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithTag(QR_SCANNER_WAITING_TAG)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
    }
}
