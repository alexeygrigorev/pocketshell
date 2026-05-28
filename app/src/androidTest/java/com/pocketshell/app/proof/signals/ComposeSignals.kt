package com.pocketshell.app.proof.signals

import android.os.SystemClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag

/**
 * Default upper-bound timeout for waiting on a Compose layout to settle.
 *
 * IME show/hide animations on a healthy emulator complete in ~250–500
 * ms. CI swiftshader emulators can stretch a single animation to ~2 s
 * and a chained recompose (IME hide -> drawer collapse -> bottom-toolbar
 * re-measure) to ~3 s. 4 s is a "this is hung, not slow" ceiling.
 */
internal const val LAYOUT_STABLE_DEFAULT_TIMEOUT_MS: Long = 4_000L

/**
 * Default "rect unchanged for this long" window used to declare
 * layout-stable. 250 ms catches a single frame of jitter on a 60 Hz
 * surface (~16 ms) plus the typical Compose recomposition burst that
 * follows an inset change, without prematurely declaring stability
 * during an animation's coast-to-zero phase.
 */
internal const val LAYOUT_STABLE_DEFAULT_WINDOW_MS: Long = 250L

/**
 * Polls the bounding rect of the node tagged [tag] in Compose's
 * semantics tree at 50 ms intervals; returns `true` the first time the
 * rect has been unchanged for [stableWindowMs], or `false` if
 * [timeoutMs] elapses with the rect still bouncing.
 *
 * Why this is the deterministic signal:
 *
 * `compose.waitForIdle()` returns when Compose has no pending work, but
 * a layout that is animating (IME inset translation, bottom-sheet
 * slide, snackbar transition) is not idle — each frame schedules the
 * next. A flat "sleep 1 s and hope" can't distinguish "still animating"
 * from "settled" either. Sampling the node's bounding rect every 50 ms
 * and watching for a `stableWindowMs` no-change window resolves the
 * moment the visible rect stops moving, which is the property the
 * caller actually cares about ("is this row safe to tap now?").
 *
 * The reference implementation lives in
 * `TerminalKeyboardStressTest.waitForTerminalLayoutStable` (which polls
 * the raw TerminalView's `width`/`height` because there is no semantics
 * tag on the vendored Termux view). This helper generalises that logic
 * via [tag], so any Compose surface that exposes a `testTag` can be
 * waited on. The original helper is intentionally left in place — its
 * migration to this signal is downstream work, not part of this issue.
 *
 * Notes:
 *
 *  - Returns `false` (not "throws") on timeout. Callers should decide
 *    whether timeout is a test failure (`assertTrue`) or a soft signal
 *    (e.g. a pre-condition that lets the test proceed anyway).
 *  - If no node with the given [tag] is present at the time of a poll,
 *    that poll is treated as "rect unknown" — the stability window
 *    resets and polling continues until the timeout. This makes the
 *    helper safe to call slightly before the tagged surface mounts.
 *
 * @param rule the Compose test rule the surface is hosted in.
 * @param tag the `testTag` semantics value to locate the node by.
 * @param stableWindowMs how long the rect must be unchanged before the
 *   helper declares stability. Defaults to [LAYOUT_STABLE_DEFAULT_WINDOW_MS].
 * @param timeoutMs upper bound; defaults to
 *   [LAYOUT_STABLE_DEFAULT_TIMEOUT_MS].
 * @return `true` once the rect has been unchanged for [stableWindowMs],
 *   `false` on timeout.
 */
fun waitForComposeLayoutStable(
    rule: ComposeTestRule,
    tag: String,
    stableWindowMs: Long = LAYOUT_STABLE_DEFAULT_WINDOW_MS,
    timeoutMs: Long = LAYOUT_STABLE_DEFAULT_TIMEOUT_MS,
): Boolean = waitForLayoutStable(
    readRect = { readRect(rule, tag) },
    stableWindowMs = stableWindowMs,
    timeoutMs = timeoutMs,
)

internal fun waitForLayoutStable(
    readRect: () -> Rect?,
    stableWindowMs: Long,
    timeoutMs: Long,
    pollIntervalMs: Long = 50L,
    nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    sleepMs: (Long) -> Unit = { SystemClock.sleep(it) },
): Boolean {
    val start = nowMs()
    val deadline = start + timeoutMs
    var lastRect: Rect? = readRect()
    var lastChangedAt = nowMs()
    while (nowMs() < deadline) {
        val now = nowMs()
        val current = readRect()
        when {
            current == null -> {
                // The node is not currently mounted/measured — reset the
                // stability window. We don't want to declare "stable" on a
                // node that hasn't even appeared yet.
                lastRect = null
                lastChangedAt = now
            }
            current != lastRect -> {
                lastRect = current
                lastChangedAt = now
            }
            now - lastChangedAt >= stableWindowMs -> return true
        }
        sleepMs(pollIntervalMs)
    }
    return false
}

/**
 * One-shot read of the bounding rect for the node tagged [tag], or
 * `null` if no such node exists / is laid out at this moment. The
 * polling helper treats `null` as "rect unknown" and resets the
 * stability window rather than treating it as "rect changed".
 *
 * Catches three failure modes:
 *
 *  - `AssertionError` — `fetchSemanticsNodes` is contract-checked, a
 *    transient mismatch (node removed mid-poll) surfaces here.
 *  - `IllegalStateException` — Compose can throw this when the
 *    semantics tree is being torn down or re-attached mid-call.
 *  - `androidx.compose.ui.test.junit4.android.ComposeNotIdleException`
 *    (a `RuntimeException`) — when Compose is busy recomposing for
 *    longer than its `IdlingPolicy` deadline. For a *bouncing* layout
 *    that genuinely never idles (e.g. an animation under load), this
 *    is the same "rect unknown right now" condition as an absent node,
 *    so we treat it the same way and let the next poll try again.
 *
 * In all cases the caller's next poll will retry, and the stability
 * window is reset so we don't accidentally declare stable on a node
 * we never managed to read.
 */
private fun readRect(rule: ComposeTestRule, tag: String): Rect? = try {
    val nodes = rule.onAllNodesWithTag(tag).fetchSemanticsNodes()
    if (nodes.isEmpty()) null else nodes.first().boundsInRoot
} catch (_: AssertionError) {
    null
} catch (_: IllegalStateException) {
    null
} catch (_: RuntimeException) {
    // Catches `ComposeNotIdleException` (and any future test-runtime
    // exception that derives from RuntimeException). The narrow type
    // is not visible from outside the test module so we use the
    // closest available supertype.
    null
}
