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
 * Issue #187 / #227: Compose-level coverage that the picker row exposes
 * both `Send` and `Send + ↵` affordances per row and that each chip
 * dispatches the right `(snippet, withEnter)` intent through the
 * single (post-#227) [SnippetPickerContent.onSnippetSend] callback.
 *
 * Per D22 (issue #227) the legacy dual contract was deleted: the picker
 * exposes a single `onSnippetSend` callback, and the row body is purely
 * informational — only the explicit chips dispatch intent. The tests
 * here guard that single-contract invariant.
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
                    onSnippetSend = { _, _ -> },
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

        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                SnippetPickerContent(
                    snippets = listOf(snippet),
                    totalCount = 1,
                    query = "",
                    onQueryChange = {},
                    onSnippetSend = { picked, withEnter -> sends += picked.id to withEnter },
                    onManageTap = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithTag(snippetSendChipTag(snippet.id, withEnter = false))
            .performClick()

        // Send (no Enter) chip must fire onSnippetSend with withEnter
        // = false.
        assertEquals(listOf(snippet.id to false), sends)
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
    fun onSnippetSendContract_isTheOnlyDispatchSurface() {
        // Issue #227 contract test: replaces the deleted
        // `defaultDelegationContract_chipFallsBackToOnSnippetPicked`
        // test. Per D22 the picker has a single send callback —
        // every chip tap routes through `onSnippetSend` with the
        // explicit `withEnter` flag, no fallback path. This test
        // pins that single-contract invariant: both chips dispatch
        // through the same callback and carry the chip's `withEnter`
        // flag verbatim, in order.
        val snippet = SnippetEntity(
            id = 14,
            hostId = 1,
            label = "tail syslog",
            body = "tail -F /var/log/syslog",
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
                    onSnippetSend = { picked, withEnter -> sends += picked.id to withEnter },
                    onManageTap = {},
                    onClose = {},
                )
            }
        }

        // Tap both chips in order. Each must route through
        // `onSnippetSend` with the matching `withEnter` flag — no
        // legacy row-body tap path, no default-delegation fallback.
        compose.onNodeWithTag(snippetSendChipTag(snippet.id, withEnter = false))
            .performClick()
        compose.onNodeWithTag(snippetSendChipTag(snippet.id, withEnter = true))
            .performClick()

        assertEquals(
            listOf(snippet.id to false, snippet.id to true),
            sends,
        )
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
                    onSnippetSend = { _, _ -> },
                    onManageTap = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Send").assertIsDisplayed()
        compose.onNodeWithText("Send + ↵").assertIsDisplayed()
    }
}
