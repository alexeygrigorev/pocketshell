package com.pocketshell.app.snippets

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.storage.entity.SnippetEntity
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #187: Compose-level coverage that the picker row exposes both
 * `Send` and `Send + ↵` affordances per row and that each chip dispatches
 * the right `(snippet, withEnter)` intent through the new callback.
 *
 * This test does NOT need Docker — it inspects only the picker's local
 * callback wiring, so it runs in the same connectedAndroidTest task as
 * the Docker-dependent [SnippetTerminalE2eTest] but skips entirely if
 * the emulator is the only thing missing.
 */
@RunWith(AndroidJUnit4::class)
class SnippetPickerSendButtonsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bothSendAndSendWithEnter_chipsAreRenderedPerRow() {
        val commandSnippet = SnippetEntity(
            id = 1,
            hostId = 1,
            label = "deploy api",
            body = "kubectl rollout restart deploy/api",
            kind = "command",
        )
        val promptSnippet = SnippetEntity(
            id = 2,
            hostId = 1,
            label = "summarise diff",
            body = "Please summarise the staged git diff.",
            kind = "prompt",
        )

        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                SnippetPickerContent(
                    snippets = listOf(commandSnippet, promptSnippet),
                    totalCount = 2,
                    query = "",
                    onQueryChange = {},
                    onSnippetTap = {},
                    onManageTap = {},
                    onClose = {},
                )
            }
        }

        // Each row must surface both chips so the user no longer has
        // to raise the IME just to press Enter on a prompt snippet
        // (issue #187 primary fix).
        compose.onNodeWithTag(snippetSendChipTag(commandSnippet.id, withEnter = false))
            .assertIsDisplayed()
        compose.onNodeWithTag(snippetSendChipTag(commandSnippet.id, withEnter = true))
            .assertIsDisplayed()
        compose.onNodeWithTag(snippetSendChipTag(promptSnippet.id, withEnter = false))
            .assertIsDisplayed()
        compose.onNodeWithTag(snippetSendChipTag(promptSnippet.id, withEnter = true))
            .assertIsDisplayed()
    }

    @Test
    fun tappingSendChip_dispatchesSendWithoutEnter() {
        val snippet = SnippetEntity(
            id = 11,
            hostId = 1,
            label = "list pods",
            body = "kubectl get pods -A",
            kind = "command",
        )
        val sends = mutableListOf<Pair<Long, Boolean>>()
        var rowTaps = 0

        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                SnippetPickerContent(
                    snippets = listOf(snippet),
                    totalCount = 1,
                    query = "",
                    onQueryChange = {},
                    onSnippetTap = { rowTaps++ },
                    onSnippetSend = { picked, withEnter -> sends += picked.id to withEnter },
                    onManageTap = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithTag(snippetSendChipTag(snippet.id, withEnter = false))
            .performClick()

        // Send (no Enter) chip must fire onSnippetSend with withEnter
        // = false and must NOT collapse to the legacy smart-default
        // onSnippetTap path.
        assertEquals(listOf(snippet.id to false), sends)
        assertEquals(0, rowTaps)
    }

    @Test
    fun tappingSendWithEnterChip_dispatchesSendWithEnter() {
        val snippet = SnippetEntity(
            id = 12,
            hostId = 1,
            label = "tail logs",
            body = "kubectl logs -f deploy/api",
            kind = "command",
        )
        val sends = mutableListOf<Pair<Long, Boolean>>()

        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                SnippetPickerContent(
                    snippets = listOf(snippet),
                    totalCount = 1,
                    query = "",
                    onQueryChange = {},
                    onSnippetTap = {},
                    onSnippetSend = { picked, withEnter -> sends += picked.id to withEnter },
                    onManageTap = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithTag(snippetSendChipTag(snippet.id, withEnter = true))
            .performClick()

        assertEquals(listOf(snippet.id to true), sends)
    }

    @Test
    fun rowBodyTap_stillRoutesThroughLegacyOnSnippetPickedCallback() {
        // Issue #187 must not regress the row-body smart-default tap
        // surface that pre-existing callers (TmuxSessionScreen,
        // PromptComposerSheet) depend on.
        val snippet = SnippetEntity(
            id = 13,
            hostId = 1,
            label = "restart svc",
            body = "sudo systemctl restart pocketshell",
            kind = "command",
        )
        var lastPicked: SnippetEntity? = null

        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                SnippetPickerContent(
                    snippets = listOf(snippet),
                    totalCount = 1,
                    query = "",
                    onQueryChange = {},
                    onSnippetTap = { lastPicked = it },
                    onManageTap = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithTag(snippetPickerRowTag(snippet.id))
            .performClick()

        assertEquals(snippet.id, lastPicked?.id)
    }

    @Test
    fun defaultDelegationContract_chipFallsBackToOnSnippetPicked() {
        // Safety-net contract for the picker's public API: when a
        // caller has NOT supplied an explicit [onSnippetSend] (e.g.
        // previews, future call sites, third-party Compose hosts), the
        // new Send / Send + ↵ chips must fall back to the row's
        // legacy [onSnippetPicked] path so the user is never stranded
        // on a chip that does nothing. All three production callers
        // (SessionScreen, TmuxSessionScreen, PromptComposerSheet) now
        // wire `onSnippetSend` explicitly — this test guards the
        // documented default-delegation behaviour of the component
        // itself, not the production wiring.
        val snippet = SnippetEntity(
            id = 14,
            hostId = 1,
            label = "tail syslog",
            body = "tail -F /var/log/syslog",
            kind = "command",
        )
        val tapped = mutableListOf<Long>()

        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                SnippetPickerContent(
                    snippets = listOf(snippet),
                    totalCount = 1,
                    query = "",
                    onQueryChange = {},
                    onSnippetTap = { tapped += it.id },
                    onManageTap = {},
                    onClose = {},
                )
            }
        }

        // Tap the Send + ↵ chip without supplying onSnippetSend.
        compose.onNodeWithTag(snippetSendChipTag(snippet.id, withEnter = true))
            .performClick()
        assertEquals(listOf(snippet.id), tapped)
    }

    @Test
    fun displayedChipsCarryHumanReadableLabels() {
        // Affordance consistency check (issue #187 mirrors the composer
        // action row): the picker chips must read "Send" and "Send + ↵"
        // verbatim so the user sees one consistent vocabulary across
        // the picker and the composer.
        val snippet = SnippetEntity(
            id = 15,
            hostId = 1,
            label = "kubectl version",
            body = "kubectl version --client --output=yaml",
            kind = "command",
        )

        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                SnippetPickerContent(
                    snippets = listOf(snippet),
                    totalCount = 1,
                    query = "",
                    onQueryChange = {},
                    onSnippetTap = {},
                    onManageTap = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Send").assertIsDisplayed()
        compose.onNodeWithText("Send + ↵").assertIsDisplayed()
    }
}
