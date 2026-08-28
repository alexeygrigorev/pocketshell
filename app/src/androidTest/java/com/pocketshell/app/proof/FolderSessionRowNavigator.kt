package com.pocketshell.app.proof

import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import com.pocketshell.app.projects.FOLDER_LIST_BOTTOM_SPACER_TAG
import com.pocketshell.app.projects.FOLDER_LIST_CONTENT_TAG
import com.pocketshell.app.projects.FolderListViewModel
import com.pocketshell.app.projects.folderDetailRowTestTag
import com.pocketshell.app.projects.folderHeaderClickTestTag
import com.pocketshell.app.projects.folderRowTestTag

/**
 * Issue #2380 — deterministic host-detail navigation to ONE named session row.
 *
 * The previous implementation (inlined in [NetworkFaultProofBase.openSessionFromList])
 * resolved the target folder by taking the FIRST entry of a hard-coded candidate list
 * (`::untracked::`, `/home/testuser`, `~`) whose folder ROW MERELY EXISTED, and then
 * tapped that folder's header until the session's child row appeared. That is a
 * "first row that exists" selector, not a "folder that contains the session" selector:
 * once the fixture grew real project folders (sessions with a resolvable
 * `pane_current_path` group under their cwd, not under `::untracked::`), every
 * network-fault proof latched onto the still-rendered `::untracked::` row, tapped it
 * ~170 times, and died in shared setup BEFORE a single Toxiproxy fault was injected —
 * so the whole phase-2 gate was vacuous rather than merely red.
 *
 * This navigator never commits to a folder it has not VERIFIED contains the session:
 *
 *  - an already-visible `folder-list:detail:<path>:<session>` row wins immediately,
 *    whichever folder it lives in (covers an auto-expanded folder and the degraded
 *    `::untracked::` placement);
 *  - otherwise folders are expanded one at a time in a DETERMINISTIC priority order —
 *    the expected folder (the seeded session's real remote cwd) first, then the other
 *    real project folders in render order, and the `::untracked::` sentinel LAST —
 *    and each expansion is verified before it is accepted;
 *  - a folder that does not reveal the session is restored to its previous state so
 *    the list does not grow without bound, and the scan scrolls the lazy list so a
 *    folder below the fold is still reachable;
 *  - failure raises one AssertionError naming the expected folder, every folder seen,
 *    every folder tried and the tap count — instead of an opaque
 *    "expanded folder ::untracked:: … after 170 tap(s)".
 */
class FolderSessionRowNavigator(
    private val compose: ComposeTestRule,
    private val onTiming: (String, Long) -> Unit = { _, _ -> },
) {

    /**
     * Reveal the session child row for [sessionName] and return the folder path it
     * actually lives under. [expectedFolderPath] is the canonicalised remote cwd the
     * seeding step observed (null when the caller has no expectation); it only sets
     * the probe ORDER, it is never trusted without verifying the row appeared.
     */
    fun revealSessionRow(
        sessionName: String,
        expectedFolderPath: String?,
        timeoutMillis: Long,
    ): String {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        val tried = LinkedHashSet<String>()
        val seen = LinkedHashSet<String>()
        var taps = 0L

        while (SystemClock.elapsedRealtime() < deadline) {
            compose.waitForIdle()

            // 1. Whichever folder already shows the row wins — including an
            //    auto-expanded project folder and the degraded `::untracked::`
            //    placement. This is the only accept path: a folder is never
            //    selected because it merely exists.
            folderShowingSession(sessionName)?.let { path ->
                onTiming(EXPAND_TAPS_TIMING, taps)
                return path
            }

            // 2. The expected folder's row (and its child row) may simply be below
            //    the fold: a virtualised lazy list does not compose off-screen rows,
            //    so scroll it into composition before concluding it is absent.
            if (expectedFolderPath != null) {
                scrollTo(folderDetailRowTestTag(expectedFolderPath, sessionName))
                if (hasTag(folderDetailRowTestTag(expectedFolderPath, sessionName))) {
                    onTiming(EXPAND_TAPS_TIMING, taps)
                    return expectedFolderPath
                }
                if (!hasTag(folderRowTestTag(expectedFolderPath))) {
                    scrollTo(folderRowTestTag(expectedFolderPath))
                }
            }

            val candidates = orderedFolderCandidates(expectedFolderPath)
            seen += candidates
            val next = candidates.firstOrNull { it !in tried }
            if (next == null) {
                // Every folder on screen has been probed. Either the tree is still
                // hydrating (new folders will appear) or a folder is below the fold;
                // walk the lazy list forward and re-scan until the deadline.
                scrollForward()
                SystemClock.sleep(PROBE_INTERVAL_MS)
                continue
            }

            tried += next
            if (isExpanded(next)) {
                // Already expanded (folders holding sessions auto-expand): the row
                // would be rendered here, so scroll it into composition and decide.
                // Never tap — that would COLLAPSE a folder we did not open.
                scrollTo(folderDetailRowTestTag(next, sessionName))
                if (hasTag(folderDetailRowTestTag(next, sessionName))) {
                    onTiming(EXPAND_TAPS_TIMING, taps)
                    return next
                }
                continue
            }

            toggleFolder(next)
            taps += 1
            val revealed = awaitSessionRow(sessionName, next, deadline)
            if (revealed != null) {
                onTiming(EXPAND_TAPS_TIMING, taps)
                return revealed
            }
            // Wrong folder: collapse it again so the list stays short and later
            // candidates stay reachable without a long scroll.
            if (isExpanded(next)) {
                toggleFolder(next)
                taps += 1
            }
        }

        throw AssertionError(
            buildString {
                appendLine(
                    "Timed out after ${timeoutMillis}ms revealing session row $sessionName " +
                        "in the host-detail folder list after $taps tap(s).",
                )
                appendLine("  expected folder (seeded remote cwd) = ${expectedFolderPath ?: "<unknown>"}")
                appendLine("  folders seen                        = ${seen.toList()}")
                appendLine("  folders expanded and rejected       = ${tried.toList()}")
                appendLine(
                    screenDiagnostics(
                        textProbes = listOf(sessionName),
                        tagProbes = buildList {
                            expectedFolderPath?.let {
                                add(folderRowTestTag(it))
                                add(folderHeaderClickTestTag(it))
                                add(folderDetailRowTestTag(it, sessionName))
                            }
                            seen.forEach { add(folderRowTestTag(it)) }
                            seen.forEach { add(folderDetailRowTestTag(it, sessionName)) }
                        },
                    ),
                )
            },
        )
    }

    /** The folder path whose expanded child row for [sessionName] is on screen, if any. */
    private fun folderShowingSession(sessionName: String): String? {
        val suffix = ":$sessionName"
        return testTags()
            .asSequence()
            .filter { it.startsWith(DETAIL_TAG_PREFIX) && it.endsWith(suffix) }
            .map { it.removePrefix(DETAIL_TAG_PREFIX).removeSuffix(suffix) }
            // Reject `…:<session>:status` / `:tile` / `:badge` style sub-tags and any
            // path that is not itself a rendered folder row.
            .filter { it.isNotEmpty() && !it.contains(':') }
            .firstOrNull()
    }

    /**
     * Every folder row currently composed, in deterministic probe order: the expected
     * folder first, then real project folders in render order, then the `::untracked::`
     * sentinel last (it is the degraded bucket — never the intended target when a real
     * project folder exists).
     */
    private fun orderedFolderCandidates(expectedFolderPath: String?): List<String> {
        val rendered = testTags()
            .asSequence()
            .filter { it.startsWith(ROW_TAG_PREFIX) }
            .map { it.removePrefix(ROW_TAG_PREFIX) }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        val expected = rendered.filter { it == expectedFolderPath }
        val untracked = rendered.filter { it == FolderListViewModel.UNTRACKED_PATH }
        val rest = rendered.filter { it != expectedFolderPath && it != FolderListViewModel.UNTRACKED_PATH }
        return expected + rest + untracked
    }

    private fun awaitSessionRow(sessionName: String, folderPath: String, deadline: Long): String? {
        val settleDeadline = minOf(deadline, SystemClock.elapsedRealtime() + EXPAND_SETTLE_MS)
        while (SystemClock.elapsedRealtime() < settleDeadline) {
            compose.waitForIdle()
            if (hasTag(folderDetailRowTestTag(folderPath, sessionName))) return folderPath
            // A concurrent tree refresh may have auto-expanded the right folder.
            folderShowingSession(sessionName)?.let { return it }
            SystemClock.sleep(PROBE_INTERVAL_MS)
        }
        return null
    }

    private fun toggleFolder(folderPath: String) {
        val headerTag = folderHeaderClickTestTag(folderPath)
        val tag = if (hasTag(headerTag)) headerTag else folderRowTestTag(folderPath)
        runCatching { compose.onNodeWithTag(tag, useUnmergedTree = true).performClick() }
    }

    /**
     * True when [folderPath] currently renders at least one SESSION CHILD row.
     *
     * A collapsed folder header still emits `folder-list:detail:<path>:{disclosure,
     * status,actions,create}`, so the prefix alone does not mean "expanded" — only a
     * child tag whose first segment is not one of those header sub-tags does.
     */
    private fun isExpanded(folderPath: String): Boolean {
        val prefix = "$DETAIL_TAG_PREFIX$folderPath:"
        return testTags().any {
            it.startsWith(prefix) && it.removePrefix(prefix).substringBefore(':') !in HEADER_SUB_TAGS
        }
    }

    private fun scrollTo(tag: String) {
        runCatching {
            compose.onNodeWithTag(FOLDER_LIST_CONTENT_TAG, useUnmergedTree = true)
                .performScrollToNode(hasTestTag(tag))
        }
    }

    private fun scrollForward() {
        // Scrolling to the bottom spacer walks the lazy list forward, composing
        // folder rows that were below the fold; the next scan picks them up.
        runCatching {
            compose.onNodeWithTag(FOLDER_LIST_CONTENT_TAG, useUnmergedTree = true)
                .performScrollToNode(hasTestTag(FOLDER_LIST_BOTTOM_SPACER_TAG))
        }
    }

    private fun testTags(): List<String> =
        runCatching {
            compose.onAllNodes(HAS_TEST_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
        }.getOrDefault(emptyList())

    private fun hasTag(tag: String): Boolean =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun screenDiagnostics(textProbes: List<String>, tagProbes: List<String>): String = buildString {
        appendLine("Tag probe counts:")
        tagProbes.distinct().forEach { tag ->
            val count = runCatching {
                compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
            }.getOrDefault(-1)
            appendLine("  $tag=$count")
        }
        appendLine("Text probe counts:")
        textProbes.distinct().forEach { text ->
            val count = runCatching {
                compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size
            }.getOrDefault(-1)
            appendLine("  \"$text\"=$count")
        }
        appendLine("Compose semantics tree:")
        appendLine(
            runCatching {
                compose.waitForIdle()
                compose.onRoot(useUnmergedTree = true).printToString()
            }.getOrElse { error ->
                "  <failed to capture semantics tree: ${error.javaClass.simpleName}: " +
                    "${error.message.orEmpty()}>"
            },
        )
    }

    private companion object {
        const val PROBE_INTERVAL_MS: Long = 200L

        /**
         * How long ONE expanded folder gets to render its session child rows before
         * it is rejected and the next candidate is tried. Generous enough for a
         * recomposition on the slow swiftshader emulator, small enough that probing
         * every folder in a handful-of-folders tree stays far inside the caller's
         * budget.
         */
        const val EXPAND_SETTLE_MS: Long = 4_000L

        const val EXPAND_TAPS_TIMING: String = "attach_folder_expand_taps"
        const val ROW_TAG_PREFIX: String = "folder-list:row:"
        const val DETAIL_TAG_PREFIX: String = "folder-list:detail:"

        /** `folder-list:detail:<path>:<x>` tags a COLLAPSED folder header still emits. */
        val HEADER_SUB_TAGS: Set<String> = setOf("disclosure", "status", "actions", "create")

        val HAS_TEST_TAG: SemanticsMatcher = SemanticsMatcher("has a test tag") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag) != null
        }
    }
}
