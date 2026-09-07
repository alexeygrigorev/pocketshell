package com.pocketshell.next.hosts

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The public-key action is a real system-clipboard handoff, not display-only copy. */
@RunWith(RobolectricTestRunner::class)
class SshKeysScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `detail copy action writes the complete public key to the clipboard`() {
        val publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIfixture pocketshell"
        composeRule.setContent {
            SshKeysScreen(
                state = SshKeysUiState(
                    loaded = true,
                    keys = listOf(SshKeyRow(7L, "work-key", "SHA256:fixture", publicKey = publicKey)),
                ),
                onBack = {},
                onGenerate = {},
                onImportPasted = { _, _ -> },
                onPickFile = {},
                onDelete = {},
                onDismissMessage = {},
            )
        }

        composeRule.onNodeWithTag(sshKeyRowTag(7L)).performClick()
        composeRule.onNodeWithTag(SSH_KEYS_COPY_PUBLIC_KEY_TAG).performClick()

        assertEquals(publicKey, clipboardText())
    }

    private fun clipboardText(): String? {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
    }
}
