package com.pocketshell.next.connect

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
 * What the trust prompt actually PAINTS, as a real composition.
 *
 * The distinction this pins is the one thing the sheet exists for: a changed
 * host key must not look like a first connection. A state-level test can assert
 * `isMismatch == true` all day while the screen renders the identical
 * reassuring copy — which is the failure that matters, because the user only
 * ever sees the screen. So each case asserts BOTH that its own copy is on
 * screen and that the other case's copy is not; a branch collapse fails here
 * instead of shipping.
 */
@RunWith(AndroidJUnit4::class)
class TrustPromptSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val unknown = TrustPromptState(
        hostId = 1,
        fingerprintSha256 = "SHA256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        isMismatch = false,
        previousFingerprintSha256 = null,
    )

    private val mismatch = TrustPromptState(
        hostId = 1,
        fingerprintSha256 = "SHA256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        isMismatch = true,
        previousFingerprintSha256 = "SHA256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    )

    @Test
    fun `a first-contact prompt shows the presented fingerprint and both actions`() {
        setContent(unknown)

        composeRule.onNodeWithText(UNKNOWN_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(UNKNOWN_EXPLANATION).assertIsDisplayed()
        composeRule.onNodeWithTag(TRUST_SHEET_FINGERPRINT_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(unknown.fingerprintSha256).assertIsDisplayed()
        composeRule.onNodeWithText(HOST_LABEL).assertIsDisplayed()
        composeRule.onNodeWithText(UNKNOWN_TRUST_LABEL).assertIsDisplayed()
        composeRule.onNodeWithTag(TRUST_SHEET_TRUST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(TRUST_SHEET_REJECT_TAG).assertIsDisplayed()

        // Nothing to compare against on first contact, and none of the
        // key-changed alarm copy.
        composeRule.onNodeWithTag(TRUST_SHEET_PREVIOUS_FINGERPRINT_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(MISMATCH_TITLE).assertDoesNotExist()
        composeRule.onNodeWithText(MISMATCH_EXPLANATION).assertDoesNotExist()
        composeRule.onNodeWithText(MISMATCH_TRUST_LABEL).assertDoesNotExist()
    }

    @Test
    fun `a changed key renders both fingerprints and the alarming copy`() {
        setContent(mismatch)

        composeRule.onNodeWithText(MISMATCH_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(MISMATCH_EXPLANATION).assertIsDisplayed()
        composeRule.onNodeWithText(MISMATCH_TRUST_LABEL).assertIsDisplayed()

        // BOTH keys on screen: that comparison is the decision the user makes.
        composeRule.onNodeWithText(mismatch.fingerprintSha256).assertIsDisplayed()
        composeRule.onNodeWithTag(TRUST_SHEET_PREVIOUS_FINGERPRINT_TAG).assertIsDisplayed()
        composeRule
            .onNodeWithText(requireNotNull(mismatch.previousFingerprintSha256))
            .assertIsDisplayed()

        // And none of the routine first-contact copy.
        composeRule.onNodeWithText(UNKNOWN_TITLE).assertDoesNotExist()
        composeRule.onNodeWithText(UNKNOWN_EXPLANATION).assertDoesNotExist()
        composeRule.onNodeWithText(UNKNOWN_TRUST_LABEL).assertDoesNotExist()
    }

    @Test
    fun `trust and reject fire their own callbacks and nothing else`() {
        var trusted = 0
        var rejected = 0
        composeRule.setContent {
            PocketShellTheme {
                TrustPromptSheetContent(
                    prompt = unknown,
                    hostLabel = HOST_LABEL,
                    onTrust = { trusted += 1 },
                    onReject = { rejected += 1 },
                )
            }
        }

        composeRule.onNodeWithTag(TRUST_SHEET_TRUST_TAG).performClick()
        composeRule.waitForIdle()
        assertEquals(1, trusted)
        assertEquals(0, rejected)

        composeRule.onNodeWithTag(TRUST_SHEET_REJECT_TAG).performClick()
        composeRule.waitForIdle()
        assertEquals(1, trusted)
        assertEquals(1, rejected)
    }

    private fun setContent(prompt: TrustPromptState) {
        composeRule.setContent {
            PocketShellTheme {
                TrustPromptSheetContent(
                    prompt = prompt,
                    hostLabel = HOST_LABEL,
                    onTrust = {},
                    onReject = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val HOST_LABEL = "testuser@10.0.2.2:2222"
    }
}
