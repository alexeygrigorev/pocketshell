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
 * Issue #740: hard upper bound for the bounded enumeration-stall watchdog's
 * sub-deadline. The #470 first-open stall signature — the awaited session row
 * never materialises while the retryable `ConnectError` panel never appears
 * (`error_panel_visible=false`) — cannot be escaped by the error-panel-keyed
 * retry below, so the helper used to burn its full
 * [SESSION_PICKER_DEFAULT_TIMEOUT_MS]/`pickerWaitMs` before asserting false.
 * The watchdog instead re-pokes the caller's enumeration trigger once the
 * awaited row has been absent (no error panel) continuously past
 * `min(timeoutMs / 3, this ceiling)` — converting the silent-stall case into
 * a deterministic recovery on the SAME UI signals the helper already reads,
 * mirroring the error-panel retry. Capped at 20 s so a generous 60 s CI bound
 * still gets up to two re-pokes inside the window without the sub-deadline
 * swallowing the whole budget.
 */
const val SESSION_PICKER_STALL_REPOKE_CEILING_MS: Long = 20_000L

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
 * Round 4 (issue #740): the error-panel-keyed retry above CANNOT escape the
 * #470 first-open enumeration-stall signature — the awaited session row never
 * materialises while the `ConnectError` panel never appears
 * (`retried=false, error_panel_visible=false`), which double-flaked
 * `MultiSessionSwitchJourneyE2eTest`. That signature covers BOTH a pure
 * `Loading` stall (no folder tree composed) AND a stale/incomplete
 * enumeration (a folder tree composes but the seeded session row is absent
 * because `tmux list-sessions` ran before/around the seed — both observed
 * runs carried `expanded_folders=true, error_panel_visible=false`). In either
 * case the helper used to burn its FULL [timeoutMs] before asserting false,
 * and the coarse class-level retry then double-hit. To self-heal
 * deterministically, callers may pass an [onRepoke] recovery action (e.g.
 * Back to the host list + re-tap the host row) that re-triggers a fresh
 * enumeration. The watchdog calls it when the awaited row has been absent —
 * with no error panel — continuously past a bounded sub-deadline
 * `min(timeoutMs / 3, `[SESSION_PICKER_STALL_REPOKE_CEILING_MS]`)`,
 * re-triggering enumeration on the SAME signals the helper already reads
 * (mirroring the error-panel retry), then resets the window. It is bounded to
 * [maxRepokes] re-pokes so a genuinely absent/wedged session still fails the
 * run within [timeoutMs] — the watchdog never masks a real first-open
 * failure, it only converts a recoverable enumeration stall into a recovery
 * instead of a full-bound burn. When [onRepoke] is null (or [maxRepokes] is
 * 0) the behaviour is identical to round 3.
 *
 * @param rule the Compose test rule hosting the picker.
 * @param sessionName the tmux session name expected to list.
 * @param timeoutMs upper bound; defaults to [SESSION_PICKER_DEFAULT_TIMEOUT_MS].
 * @param onStateNote optional sink for the readiness transitions the
 *   helper observes (`session_list_ready`,
 *   `session_list_connect_error_retrying`,
 *   `session_list_expanding_folders`, `session_list_stall_repoke`) so
 *   callers that record per-stage timing stamps can keep their artifact
 *   breadcrumbs.
 * @param onRepoke optional test-only recovery that re-triggers the host's
 *   enumeration (e.g. Back to the host list + re-tap the host row). Invoked
 *   by the bounded enumeration-stall watchdog (#740) up to [maxRepokes] times.
 *   Null disables the watchdog (round-3 behaviour). The lambda must leave the
 *   picker in a state where this helper's success/error/expand polls remain
 *   valid (i.e. it re-opens the SAME host's picker).
 * @param maxRepokes upper bound on watchdog re-pokes; ignored when [onRepoke]
 *   is null. Default 2 keeps a 60 s CI bound recoverable without the
 *   sub-deadline swallowing the whole budget.
 */
fun waitForSessionInPicker(
    rule: ComposeTestRule,
    sessionName: String,
    timeoutMs: Long = SESSION_PICKER_DEFAULT_TIMEOUT_MS,
    onStateNote: (String) -> Unit = {},
    onRepoke: (() -> Unit)? = null,
    maxRepokes: Int = 2,
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
    // Issue #740: the bounded enumeration-stall watchdog sub-deadline. The
    // recoverable #470 first-open signature is "the awaited session row never
    // materialises and the error panel never appears" — covering BOTH a pure
    // `Loading` stall (no folder tree at all) AND a stale/incomplete
    // enumeration (a folder tree composed but the seeded session row is absent
    // because `tmux list-sessions` ran before/around the seed). Both observed
    // failures carried `error_panel_visible=false` and the error-panel-keyed
    // retry below can escape NEITHER. So we re-poke (re-trigger a fresh
    // enumeration) once the AWAITED ROW has been absent — with no error panel —
    // continuously past this sub-deadline. It is short enough to recover well
    // inside a 60 s CI bound, yet long enough (`min(timeoutMs/3, 20 s)`) not to
    // fire on a merely-slow-but-progressing cold enumeration. The window resets
    // only when the row appears (return), the error panel drives its retry, or
    // a re-poke is performed — so a genuinely absent session still exhausts
    // `maxRepokes` and fails within `timeoutMs` (the watchdog never masks a
    // real first-open failure).
    val stallRepokeThresholdMs = minOf(timeoutMs / 3, SESSION_PICKER_STALL_REPOKE_CEILING_MS)
    var repokesPerformed = 0
    var rowAbsentSince = SystemClock.elapsedRealtime()
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
        if (errorVisible) {
            // The picker surfaced the retryable error panel — the error-panel
            // retry owns this recovery, so the watchdog's row-absent window must
            // not accumulate across this state (don't double-recover).
            rowAbsentSince = SystemClock.elapsedRealtime()
            if (!retried) {
                retried = true
                onStateNote("session_list_connect_error_retrying")
                val retryNodes = rule
                    .onAllNodesWithTag(FOLDER_LIST_RETRY_TAG, useUnmergedTree = true)
                if (retryNodes.fetchSemanticsNodes().isNotEmpty()) {
                    retryNodes.onFirst().performClick()
                }
            }
        } else {
            // No error panel and the awaited row is not yet visible. Expand any
            // collapsed folder headers so a seeded session row under the
            // collapsed "Other folders" group composes (#470 round 3;
            // production no longer auto-expands — #471).
            val expanded = expandAllFolderHeaders(rule, toggledHeaderTags)
            if (expanded > 0 && !expandedFolders) {
                expandedFolders = true
                onStateNote("session_list_expanding_folders")
            }
            // Issue #740: the awaited row is still absent with no error panel.
            // If it has been absent past the sub-deadline, re-poke a fresh
            // enumeration — this self-heals BOTH the pure `Loading` stall and
            // the "tree composed but seeded row missing" stale-enumeration case
            // (the two observed `error_panel_visible=false` signatures the
            // error-panel-keyed retry cannot escape). The re-poke re-opens the
            // SAME host picker, so clear the per-navigation toggled-header set
            // (the fresh tree's headers must be eligible for expansion again)
            // and reset the window so the fresh enumeration gets its own full
            // sub-deadline before another re-poke. Bounded by [maxRepokes]: a
            // genuinely absent session still exhausts the budget and fails
            // within `timeoutMs`, so a real first-open failure is never masked.
            if (
                onRepoke != null &&
                repokesPerformed < maxRepokes &&
                SystemClock.elapsedRealtime() - rowAbsentSince >= stallRepokeThresholdMs
            ) {
                repokesPerformed += 1
                onStateNote("session_list_stall_repoke")
                toggledHeaderTags.clear()
                onRepoke()
                rowAbsentSince = SystemClock.elapsedRealtime()
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
            "error_panel_visible=$errorStillVisible, stall_repokes=$repokesPerformed). " +
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
