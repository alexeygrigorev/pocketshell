package com.pocketshell.app.proof.signals

import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.pocketshell.app.projects.FOLDER_LIST_ERROR_TAG
import com.pocketshell.app.projects.FOLDER_LIST_RETRY_TAG
import org.junit.Assert.assertTrue

/**
 * Issue #470 (round 3): the test tag prefix every folder-header expand/collapse
 * toggle carries (`folderHeaderClickTestTag(folder.path)` →
 * `"folder-list:header-click:<path>"`). The session picker groups a host's tmux
 * sessions under collapsible folder headers; a seeded session whose cwd has no
 * configured watched root lands under the synthetic "Other folders" group whose
 * folder defaults to COLLAPSED, so the session-name row is never composed until
 * its folder header is expanded.
 *
 * Production deliberately does NOT auto-expand session-bearing folders (that
 * would fight a user's explicit collapse and conflict with the compact-tree
 * direction — tracked separately as #471). So the connected tmux tests expand
 * the folder headers THEMSELVES before asserting a seeded session row — exactly
 * the test-only reveal pattern #444's helper uses. We match every header by
 * this tag prefix so the helper needs only the session name, not the folder
 * path it happens to be grouped under.
 */
private const val FOLDER_HEADER_CLICK_TAG_PREFIX: String = "folder-list:header-click:"

/**
 * Issue #470 (blocker #2): generous default upper bound for the
 * folder-list session enumeration to surface a host's tmux sessions after
 * the host row is tapped.
 *
 * The folder/session picker is backed by a plain `tmux list-sessions`
 * SSH-exec probe (see `FolderListGateway.listSessionsWithFolder`), not a
 * `tmux -CC` control-channel attach — the `-CC` attach only happens later,
 * on the per-session screen, after the user taps a session. So the
 * "session-picker enumeration stall" reported on cold AVDs is the
 * in-emulator SSH connect + a single `tmux list-sessions` exec taking
 * longer than the previously-used bare ~20 s `waitUntil` allowed: the
 * first connect can blow its SSH connect timeout on a cold emulator, the
 * screen lands on the retryable `ConnectError` panel, and a bare
 * `waitUntil` that only watches for the session-name row never clicks
 * Retry — so it burns the full timeout and throws `ComposeTimeoutException`
 * before the test's real assertion.
 *
 * 45 s is "this is wedged, not slow": a healthy local connect+enumerate is
 * sub-second; a cold AVD's first attempt plus one production Retry settles
 * well inside this bound.
 */
const val SESSION_PICKER_DEFAULT_TIMEOUT_MS: Long = 45_000L

/**
 * Issue #470 (blocker #2): a deterministic readiness wait for the host's
 * tmux session list in the folder/session picker, replacing the per-test
 * bare `waitUntil(...)` polls that flaked when a healthy-but-cold AVD took
 * longer than the test's timeout to complete the in-emulator SSH connect +
 * `tmux list-sessions` enumeration.
 *
 * After the host row is tapped the picker reaches exactly one of three
 * terminal states:
 *  - the [sessionName] row appears (success),
 *  - the `ErrorPanel` (`FOLDER_LIST_ERROR_TAG` + `FOLDER_LIST_RETRY_TAG`)
 *    appears (connect failed / timed out), or
 *  - it stays in `Loading` past the bound (the AVD is wedged).
 *
 * This helper waits on those real UI signals (not a clock-racing sleep)
 * with a generous [timeoutMs] bound, and retries the connect exactly once
 * via the production Retry button if the picker lands on the error panel —
 * so a cold-but-healthy enumeration that blew the first connect window
 * still completes on the second attempt. It fails loudly only when neither
 * attempt surfaces the session within the bound, and the failure message
 * names blocker #2 / infra #470 so a future flake is attributed to the
 * emulator↔host enumeration timing rather than to the test's own
 * assertion path.
 *
 * This generalises the original per-test `waitForSessionInPicker` helper in
 * `TmuxAttachPrefillDockerTest` so every previously-flaky tmux `-CC`
 * connected test can share the same retry-once readiness gate.
 *
 * Round 3 (issue #470): the session is grouped under a collapsible folder
 * header, and production does NOT auto-expand session-bearing folders (that
 * UX decision lives in #471). So this gate also EXPANDS every collapsed
 * folder header (test-only) before declaring the row absent — mirroring
 * #444's reveal pattern — so a seeded session under the collapsed
 * "Other folders" group still surfaces its row without a production
 * auto-expand. Expanding is idempotent: a folder already showing its row is
 * left alone because the success check below fires first.
 *
 * @param rule the Compose test rule hosting the picker.
 * @param sessionName the tmux session name expected to list.
 * @param timeoutMs upper bound; defaults to [SESSION_PICKER_DEFAULT_TIMEOUT_MS].
 * @param onStateNote optional sink for the readiness transitions the
 *   helper observes (`session_list_ready`,
 *   `session_list_connect_error_retrying`,
 *   `session_list_expanding_folders`) so callers that record per-stage
 *   timing stamps can keep their artifact breadcrumbs.
 */
fun waitForSessionInPicker(
    rule: ComposeTestRule,
    sessionName: String,
    timeoutMs: Long = SESSION_PICKER_DEFAULT_TIMEOUT_MS,
    onStateNote: (String) -> Unit = {},
) {
    var retried = false
    var expandedFolders = false
    // Header tags toggled during THIS picker navigation, so a re-poll never
    // collapses a folder we already expanded. Kept call-local (not process-
    // global) so a second test method in the same process — whose seeded
    // session may reuse the same cwd/folder path — still expands its folder
    // from its own fresh launch.
    val toggledHeaderTags = mutableSetOf<String>()
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
        // 1. Session row visible — enumeration succeeded and the folder is
        //    expanded.
        if (rule.onAllNodesWithText(sessionName).fetchSemanticsNodes().isNotEmpty()) {
            onStateNote("session_list_ready")
            return
        }
        // 2. Connect-error panel — retry once via the production Retry
        //    button so a cold first attempt does not fail the run.
        val errorVisible = rule
            .onAllNodesWithTag(FOLDER_LIST_ERROR_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
        if (errorVisible && !retried) {
            retried = true
            onStateNote("session_list_connect_error_retrying")
            val retryNodes = rule
                .onAllNodesWithTag(FOLDER_LIST_RETRY_TAG, useUnmergedTree = true)
            if (retryNodes.fetchSemanticsNodes().isNotEmpty()) {
                retryNodes.onFirst().performClick()
            }
        } else if (!errorVisible) {
            // 3. Enumeration returned but the row isn't shown yet — the
            //    session's folder is collapsed (production no longer
            //    auto-expands; #471). Expand every folder header so the
            //    seeded session row composes. Re-tickable each iteration in
            //    case a later poll adds a freshly-enumerated folder.
            val expanded = expandAllFolderHeaders(rule, toggledHeaderTags)
            if (expanded > 0 && !expandedFolders) {
                expandedFolders = true
                onStateNote("session_list_expanding_folders")
            }
        }
        SystemClock.sleep(100)
    }
    val errorStillVisible = rule
        .onAllNodesWithTag(FOLDER_LIST_ERROR_TAG, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()
    assertTrue(
        "expected tmux session `$sessionName` to list in the picker within " +
            "${timeoutMs}ms (retried=$retried, expanded_folders=$expandedFolders, " +
            "error_panel_visible=$errorStillVisible). " +
            "This indicates the in-emulator SSH+tmux list-sessions enumeration stalled " +
            "(blocker #2 / infra #470), not the test's assertion path.",
        false,
    )
}

/**
 * Issue #470 (round 3): expand every collapsed folder header in the session
 * picker so seeded session rows compose. Test-only — production does NOT
 * auto-expand session-bearing folders (#471). Clicking a header that is
 * already expanded would COLLAPSE it, so we only click headers whose
 * disclosure is currently collapsed, detected via the row's session having
 * not yet composed. To keep it simple and robust we click a header at most
 * once per call by tracking the set of header tags we've already toggled.
 *
 * Returns the number of folder headers clicked this call (0 when none are
 * present yet, e.g. the enumeration hasn't produced a tree).
 */
private fun expandAllFolderHeaders(
    rule: ComposeTestRule,
    toggledHeaderTags: MutableSet<String>,
): Int {
    val headerTags = rule
        .onAllNodes(
            hasTestTagPrefix(FOLDER_HEADER_CLICK_TAG_PREFIX),
            useUnmergedTree = true,
        )
        .fetchSemanticsNodes()
        .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.TestTag) }
        .filter { tag -> tag !in toggledHeaderTags }
    headerTags.forEach { tag ->
        toggledHeaderTags += tag
        val nodes = rule.onAllNodesWithTag(tag, useUnmergedTree = true)
        if (nodes.fetchSemanticsNodes().isNotEmpty()) {
            nodes.onFirst().performClick()
        }
    }
    return headerTags.size
}

/**
 * [SemanticsMatcher] that matches any node whose test tag starts with
 * [prefix]. Used to find every folder-header expand toggle without knowing
 * the folder paths the seeded sessions are grouped under.
 */
private fun hasTestTagPrefix(prefix: String): SemanticsMatcher =
    SemanticsMatcher("TestTag starts with '$prefix'") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
