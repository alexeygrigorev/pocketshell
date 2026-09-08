package com.pocketshell.next.hosts

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicReference

/** The public-key action confirms that the complete value was handed off. */
@RunWith(RobolectricTestRunner::class)
class SshKeysScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `detail content copy action writes the complete public key to the clipboard`() {
        val publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIfixture pocketshell"
        val copiedValue = AtomicReference<String?>()
        composeRule.setContent {
            SshKeyDetailContent(
                key = SshKeyRow(7L, "work-key", "SHA256:fixture", publicKey = publicKey),
                onClose = {},
                onCopyPublicKey = { copiedValue.set(it) },
                onCopyFingerprint = {},
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SSH_KEYS_COPY_PUBLIC_KEY_TAG)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()

        assertEquals(publicKey, copiedValue.get())
    }
}
