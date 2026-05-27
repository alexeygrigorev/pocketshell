package com.pocketshell.app.snippets

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.storage.entity.SnippetEntity
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #198: Compose-level coverage that the picker row renders the
 * one-line body preview under the label, with the dedup + empty-body
 * suppression rules wired up.
 *
 * This is a non-Docker Compose test (mirrors
 * [SnippetPickerSendButtonsTest]'s setup) — it runs against the
 * in-process Compose rule without needing an SSH-ready remote.
 */
@RunWith(AndroidJUnit4::class)
class SnippetPickerBodyPreviewTest {

    @get:Rule
    val compose = createComposeRule()

    private fun snippet(
        id: Long,
        label: String?,
        body: String,
        kind: String = "command",
    ): SnippetEntity = SnippetEntity(
        id = id,
        hostId = 1L,
        label = label,
        body = body,
        kind = kind,
    )

    @Test
    fun explicitLabelRow_rendersBodyPreviewUnderLabel() {
        // The original audit complaint: explicit-label rows hide the
        // body. The preview must surface it.
        val s = snippet(id = 1, label = "list pods", body = "kubectl get pods -A")
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                SnippetPickerContent(
                    snippets = listOf(s),
                    totalCount = 1,
                    query = "",
                    onQueryChange = {},
                    onSnippetTap = {},
                    onManageTap = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithTag(snippetBodyPreviewTag(s.id), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextEquals("kubectl get pods -A")
    }

    @Test
    fun derivedLabelSingleLineRow_doesNotRenderPreview() {
        // Dedup: the body collapses to exactly the derived label, so
        // showing it twice would only add visual clutter.
        val s = snippet(id = 2, label = null, body = "kubectl get pods -A")
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                SnippetPickerContent(
                    snippets = listOf(s),
                    totalCount = 1,
                    query = "",
                    onQueryChange = {},
                    onSnippetTap = {},
                    onManageTap = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithTag(snippetBodyPreviewTag(s.id))
            .assertDoesNotExist()
    }

    @Test
    fun derivedLabelMultiLineRow_rendersBodyPreviewWithCollapsedNewlines() {
        // Multi-line derived-label rows are the main #198 win: the
        // user previously could not tell snippets apart because only
        // the first body line was rendered (as the derived label).
        val s = snippet(
            id = 3,
            label = null,
            body = "kubectl logs -f deploy/api\n  --since=10m --tail=200",
        )
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                SnippetPickerContent(
                    snippets = listOf(s),
                    totalCount = 1,
                    query = "",
                    onQueryChange = {},
                    onSnippetTap = {},
                    onManageTap = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithTag(snippetBodyPreviewTag(s.id), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextEquals("kubectl logs -f deploy/api   --since=10m --tail=200")
    }

    @Test
    fun emptyBodyRow_doesNotRenderPreview() {
        // Acceptance: empty bodies render no preview row (no blank
        // padding). The label-derivation path falls back to the
        // `(empty snippet)` placeholder; the preview row must NOT
        // appear at all.
        val s = snippet(id = 4, label = "labelled", body = "")
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                SnippetPickerContent(
                    snippets = listOf(s),
                    totalCount = 1,
                    query = "",
                    onQueryChange = {},
                    onSnippetTap = {},
                    onManageTap = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithTag(snippetBodyPreviewTag(s.id))
            .assertDoesNotExist()
    }

    @Test
    fun multipleRows_eachGetIndependentPreviewVisibility() {
        // Confirm rows are independent — a single deduped row in the
        // list does not suppress previews on its neighbours, and the
        // tags are keyed by snippet id so they don't collide.
        val deduped = snippet(id = 10, label = null, body = "ls -la")
        val explicit = snippet(id = 11, label = "summarise diff", body = "Please summarise.")
        val multiline = snippet(
            id = 12,
            label = null,
            body = "echo first\necho second",
        )
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                SnippetPickerContent(
                    snippets = listOf(deduped, explicit, multiline),
                    totalCount = 3,
                    query = "",
                    onQueryChange = {},
                    onSnippetTap = {},
                    onManageTap = {},
                    onClose = {},
                )
            }
        }

        // The clickable row wrapper merges semantics for its children, so
        // the preview Text nodes are only visible via the unmerged tree.
        // Suppression cases ("deduped") use the merged tree on purpose —
        // `assertDoesNotExist` is satisfied either way and exercises the
        // production path the user actually sees.
        compose.onNodeWithTag(snippetBodyPreviewTag(deduped.id))
            .assertDoesNotExist()
        compose.onNodeWithTag(snippetBodyPreviewTag(explicit.id), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextEquals("Please summarise.")
        compose.onNodeWithTag(snippetBodyPreviewTag(multiline.id), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextEquals("echo first echo second")
    }
}
