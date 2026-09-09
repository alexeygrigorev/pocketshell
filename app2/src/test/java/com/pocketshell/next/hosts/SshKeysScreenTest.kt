package com.pocketshell.next.hosts

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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

    @Test
    fun `generate flow is a page with persistent labels and local back`() {
        val generated = AtomicReference<SshKeyGenerationRequest?>()
        var routeBacks = 0
        composeRule.setContent {
            SshKeysScreen(
                state = SshKeysUiState(loaded = true),
                onBack = { routeBacks++ },
                onGenerate = { generated.set(it) },
                onImportPasted = { _, _ -> },
                onPickFile = {},
                onDelete = {},
                onDismissMessage = {},
            )
        }

        composeRule.onNodeWithTag(SSH_KEYS_GENERATE_TAG).performClick()
        composeRule.onNodeWithText("Key name").assertIsDisplayed()
        composeRule.onNodeWithTag(SSH_KEYS_GENERATE_CONFIRM_TAG)
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()

        assertEquals(SshKeyGenerationType.ED25519, generated.get()?.type)
        assertEquals(0, routeBacks)

        composeRule.onNodeWithTag(SSH_KEYS_GENERATE_TAG).performClick()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithTag(SSH_KEYS_LIST_TAG).assertIsDisplayed()
        assertEquals(0, routeBacks)
    }

    @Test
    fun `import review is a page and stays disabled until local parsing succeeds`() {
        val pem = SshKeyMaterial.generatePrivateKeyPem()
        val imported = AtomicReference<Pair<String, String>?>()
        composeRule.setContent {
            SshKeysScreen(
                state = SshKeysUiState(loaded = true),
                onBack = {},
                onGenerate = {},
                onImportPasted = { name, value -> imported.set(name to value) },
                onPickFile = {},
                onDelete = {},
                onDismissMessage = {},
            )
        }

        composeRule.onNodeWithTag(SSH_KEYS_IMPORT_TAG).performClick()
        composeRule.onNodeWithText("Private key").assertIsDisplayed()
        composeRule.onNodeWithTag(SSH_KEYS_IMPORT_CONFIRM_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(SSH_KEYS_IMPORT_NAME_TAG).performTextInput("Travel key")
        composeRule.onNodeWithTag(SSH_KEYS_PASTE_FIELD_TAG).performTextInput(pem)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SSH_KEYS_IMPORT_CONFIRM_TAG)
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithTag(SSH_KEYS_IMPORT_REVIEW_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SSH_KEYS_IMPORT_REVIEW_CONFIRM_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals("Travel key", imported.get()?.first)
        assertEquals(pem, imported.get()?.second)
    }

    @Test
    fun `selecting a key opens the detail page and keeps the complete public key action`() {
        val publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIfixture pocketshell"
        val copiedValue = AtomicReference<String?>()
        composeRule.setContent {
            SshKeysScreen(
                state = SshKeysUiState(
                    keys = listOf(SshKeyRow(7L, "work-key", "SHA256:fixture", publicKey = publicKey)),
                    loaded = true,
                ),
                onBack = {},
                onGenerate = {},
                onImportPasted = { _, _ -> },
                onPickFile = {},
                onDelete = {},
                onLoadPublicKey = { _, _ -> },
                onDismissMessage = {},
                onCopyPublicKey = { copiedValue.set(it) },
            )
        }

        composeRule.onNodeWithTag(sshKeyRowTag(7L)).performClick()
        composeRule.onNodeWithTag(SSH_KEYS_DETAIL_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SSH_KEYS_COPY_PUBLIC_KEY_TAG)
            .performScrollTo()
            .performClick()

        assertEquals(publicKey, copiedValue.get())
    }
}
