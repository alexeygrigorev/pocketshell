package com.pocketshell.next.sync

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Account & sync screen (issue #2633).
 *
 * The UX contract from `docs/SYNC.md`'s "What syncs: the selection": per-host
 * ticks, not one switch. Also pins the two states a user can actually be
 * stuck in — signed out, and "this build has no OAuth client yet" — because
 * the second one is what every build looks like until the maintainer registers
 * the Android client, and a screen that silently did nothing there would be
 * worse than one that says so.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h915dp")
class AccountSyncScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val hosts = listOf(
        SyncHostRow("hetzner", "alexey@135.181.114.209:22", checked = true),
        SyncHostRow("builder", "root@10.0.0.7:2022", checked = false),
    )

    @Test
    fun `signed out shows the sign-in entry point and no host picker`() {
        setContent(AccountSyncUiState(clientConfigured = true, signedIn = false, hosts = hosts))

        composeRule.onNodeWithTag(SYNC_SIGN_IN_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Sign in with Google").assertIsDisplayed()
        // Nothing about the user's hosts is on screen until they opt in.
        composeRule.onAllNodesWithTagCount(syncHostRowTag("hetzner"), 0)
        composeRule.onAllNodesWithTagCount(SYNC_PASSPHRASE_TAG, 0)
    }

    @Test
    fun `an unconfigured build says so and disables sign-in`() {
        setContent(AccountSyncUiState(clientConfigured = false, signedIn = false))

        composeRule.onNodeWithTag(SYNC_UNCONFIGURED_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SYNC_SIGN_IN_TAG).assertIsNotEnabled()
    }

    @Test
    fun `signed in shows the account, the passphrase field and one tick per host`() {
        setContent(
            AccountSyncUiState(
                clientConfigured = true,
                signedIn = true,
                email = "person@example.com",
                hosts = hosts,
            ),
        )

        composeRule.onNodeWithTag(SYNC_ACCOUNT_ROW_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("person@example.com").assertIsDisplayed()
        composeRule.onNodeWithTag(SYNC_PASSPHRASE_TAG).assertIsDisplayed()
        listOf("hetzner", "builder").forEach { alias ->
            composeRule.onNodeWithTag(SYNC_LIST_TAG).performScrollToNode(hasTestTag(syncHostRowTag(alias)))
            composeRule.onNodeWithTag(syncHostRowTag(alias)).assertIsDisplayed()
        }
    }

    @Test
    fun `ticking a host reports the alias and the new state`() {
        val toggles = mutableListOf<Pair<String, Boolean>>()
        setContent(
            AccountSyncUiState(clientConfigured = true, signedIn = true, hosts = hosts),
            onHostChecked = { alias, checked -> toggles += alias to checked },
        )

        composeRule.onNodeWithTag(SYNC_LIST_TAG).performScrollToNode(hasTestTag(syncHostRowTag("builder")))
        composeRule.onNodeWithTag(syncHostRowTag("builder")).performClick()
        // `hetzner` is already ticked, so tapping it must UNtick.
        composeRule.onNodeWithTag(SYNC_LIST_TAG).performScrollToNode(hasTestTag(syncHostRowTag("hetzner")))
        composeRule.onNodeWithTag(syncHostRowTag("hetzner")).performClick()

        assertEquals(listOf("builder" to true, "hetzner" to false), toggles)
    }

    @Test
    fun `Sync now hands the typed passphrase to the push`() {
        val pushed = mutableListOf<String>()
        setContent(
            AccountSyncUiState(clientConfigured = true, signedIn = true, hosts = hosts),
            onPush = { pushed += it },
        )

        composeRule.onNodeWithTag(SYNC_PASSPHRASE_TAG).performTextInput("hunter2")
        composeRule.onNodeWithTag(SYNC_LIST_TAG).performScrollToNode(hasTestTag(SYNC_PUSH_TAG))
        composeRule.onNodeWithTag(SYNC_PUSH_TAG).performClick()

        assertEquals(listOf("hunter2"), pushed)
    }

    @Test
    fun `Restore hands the typed passphrase to the pull`() {
        val pulled = mutableListOf<String>()
        setContent(
            AccountSyncUiState(clientConfigured = true, signedIn = true, hosts = hosts),
            onPull = { pulled += it },
        )

        composeRule.onNodeWithTag(SYNC_PASSPHRASE_TAG).performTextInput("hunter2")
        composeRule.onNodeWithTag(SYNC_LIST_TAG).performScrollToNode(hasTestTag(SYNC_PULL_TAG))
        composeRule.onNodeWithTag(SYNC_PULL_TAG).performClick()

        assertEquals(listOf("hunter2"), pulled)
    }

    @Test
    fun `an empty host list explains itself instead of showing an empty picker`() {
        setContent(AccountSyncUiState(clientConfigured = true, signedIn = true, hosts = emptyList()))
        composeRule.onNodeWithTag(SYNC_LIST_TAG).performScrollToNode(hasTestTag(SYNC_HOSTS_EMPTY_TAG))
        composeRule.onNodeWithTag(SYNC_HOSTS_EMPTY_TAG).assertIsDisplayed()
    }

    @Test
    fun `a failed sync shows the reason`() {
        setContent(
            AccountSyncUiState(
                clientConfigured = true,
                signedIn = true,
                hosts = hosts,
                outcome = SyncOutcome.Failed("decryption failed — wrong passphrase or corrupted blob"),
            ),
        )
        composeRule.onNodeWithTag(SYNC_LIST_TAG).performScrollToNode(hasTestTag(SYNC_STATUS_TAG))
        composeRule.onNodeWithText(
            "decryption failed — wrong passphrase or corrupted blob",
        ).assertIsDisplayed()
    }

    @Test
    fun `the passphrase field is masked and marked as a password`() {
        setContent(AccountSyncUiState(clientConfigured = true, signedIn = true, hosts = hosts))
        composeRule.onNodeWithTag(SYNC_PASSPHRASE_TAG).performTextInput("hunter2")

        val node = composeRule.onNodeWithTag(SYNC_PASSPHRASE_TAG).fetchSemanticsNode()
        // What is DISPLAYED (and what a screen reader announces, and what a
        // screenshot in the recents switcher would capture) is the mask.
        assertEquals("•••••••", node.config[SemanticsProperties.EditableText].text)
        // And the node carries the password role, so the platform keeps it out
        // of autofill suggestions and clipboard-style affordances.
        assertTrue(
            "the passphrase field must be marked as a password",
            node.config.contains(SemanticsProperties.Password),
        )
    }

    private fun setContent(
        state: AccountSyncUiState,
        onHostChecked: (String, Boolean) -> Unit = { _, _ -> },
        onPush: (String) -> Unit = {},
        onPull: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            AccountSyncScreen(
                state = state,
                onBack = {},
                onSignIn = {},
                onSignOut = {},
                onDismissSignInBanner = {},
                onHostChecked = onHostChecked,
                onPush = onPush,
                onPull = onPull,
            )
        }
    }
}

/** `onAllNodesWithTag(...).fetchSemanticsNodes().size` as a one-line assertion. */
private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(
    tag: String,
    expected: Int,
) {
    val found = onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
    org.junit.Assert.assertEquals("nodes tagged $tag", expected, found)
}
