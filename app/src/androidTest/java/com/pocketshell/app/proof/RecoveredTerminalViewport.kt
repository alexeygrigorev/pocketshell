package com.pocketshell.app.proof

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.proof.signals.describeViewCaptureState
import com.termux.view.TerminalView

/**
 * Issue #2389 — the shared POST-RECOVERY terminal-viewport gate for the
 * network-fault recovery proofs.
 *
 * ## What was actually broken (the `#2135` cohort, one root cause)
 *
 * `NatIdleMappingSurvivalE2eTest` and `PushResumeDeadSocketMainResponsiveE2eTest`
 * both died at the SAME place — the post-recovery `captureViewToBitmap(...)` with
 * `viewFound=false` — after writing their earlier viewports fine. Instrumented
 * emulator runs (issue #2389) found one shared cause, and it is a HARNESS defect,
 * not a connection-core defect:
 *
 *  - While the surface holds the recovery (`Reconnecting` / `Switching` →
 *    `SessionSurfaceState.Attaching`, `terminalHeld = true`) the screen
 *    DELIBERATELY unmounts the Termux `AndroidView`
 *    ([com.pocketshell.app.tmux.shouldKeepTerminalMounted]) and paints the
 *    centered "Attaching…" hold. There is no `TerminalView` in the decor tree at
 *    all during that window — by design.
 *  - The view model reaches `ConnectionStatus.Connected` FIRST. The re-mount only
 *    lands when the composition applies the resulting invalidation.
 *  - Both proofs then captured the authoritative viewport ~120–150 ms later, off
 *    the RAW view tree behind `Instrumentation.waitForIdleSync()`. That waits for
 *    the main LOOPER, and never drives the Compose frame clock. Measured on the
 *    emulator: the `Recomposer` sat at `state=PendingWork pendingWork=true
 *    changeCount=25` for 16 s with the update already queued; `Snapshot.
 *    sendApplyNotifications()` did NOT release it; the first Compose-synced wait
 *    flushed it instantly (`changeCount=29`, `state=Idle`, terminal attached).
 *    The emulator only produces the vsync the Recomposer is parked on when
 *    something drives a frame, so a raw-view poll can watch a stale surface for
 *    tens of seconds while the app is, as far as it is concerned, done.
 *
 * That is why every ATTACH-time wait in this suite (`waitForTerminalViewAttached`)
 * is Compose-synced and green, while these two POST-RECOVERY captures were not and
 * failed ~90% of the time (1P/9F and 1P/12F in the #2380 round-2 measurement).
 *
 * [awaitRestored] closes it the honest way: it waits through the SAME Compose-
 * synced path as the attach gate and keeps the `#2135` hard failure. Nothing is
 * weakened — a surface that genuinely never re-mounts the terminal after recovery
 * still reds, now with a diagnostic that names the observed view state instead of
 * a bare `viewFound=false`.
 *
 * The gate is a shared helper (not a per-test copy) because the failure is a
 * property of the whole recovery-proof CLASS: any proof that captures a terminal
 * viewport after a fault clears has the same race, so they all go through here.
 */
internal object RecoveredTerminalViewport {

    /**
     * Generous on purpose: the emulator restores in well under a second when the
     * box is idle, but this proof family runs on swiftshader alongside sibling
     * agents, where the same recomposition + `AndroidView` attach has been
     * observed to stretch into the tens of seconds. It is still a BOUND: a
     * surface that never re-mounts the terminal fails, which is the #2135 class
     * the proofs exist to catch. It mirrors the 30 s budget the ATTACH path
     * already uses (`NetworkFaultProofBase.waitForTerminalViewAttached`).
     */
    const val RESTORE_BUDGET_MS: Long = 30_000L

    /**
     * Block until the recovered session surface has re-attached a LIVE terminal
     * viewport (a measured `TerminalView` bound to a session + emulator), then
     * return how long that took. Throws [AssertionError] naming the observed view
     * state when the viewport never comes back inside [budgetMs].
     *
     * "Live" is deliberately stronger than "a view exists": a re-mounted but
     * unbound `TerminalView` would let a broken recovery capture an empty frame
     * and call it evidence.
     */
    fun awaitRestored(
        compose: ComposeTestRule,
        scenario: ActivityScenario<MainActivity>?,
        label: String,
        budgetMs: Long = RESTORE_BUDGET_MS,
        recordTiming: (String, Long) -> Unit = { _, _ -> },
    ): Long {
        val started = SystemClock.elapsedRealtime()
        var lastState = "<activity never available to inspect>"
        try {
            // compose.waitUntil (NOT a raw waitForIdleSync poll) is the load-bearing
            // part: it drives the Compose frame clock, so a recomposition parked in
            // `PendingWork` is applied instead of being watched forever.
            compose.waitUntil(timeoutMillis = budgetMs) {
                var restored = false
                scenario?.onActivity { activity ->
                    val view = activity.window.decorView.findTerminalViewInTree()
                    lastState = describeViewCaptureState(view) +
                        " session=${view?.currentSession != null}" +
                        " emulator=${view?.mEmulator != null}"
                    restored = view != null &&
                        view.width > 0 &&
                        view.height > 0 &&
                        view.currentSession != null &&
                        view.mEmulator != null
                }
                restored
            }
        } catch (timeout: Throwable) {
            recordTiming("${label}_viewport_restored_ms", -1L)
            throw AssertionError(
                "the terminal viewport never came back after $label: the connection " +
                    "recovered but no live TerminalView re-attached within ${budgetMs}ms, " +
                    "so the session surface is still holding (\"Attaching…\") or paneless. " +
                    "This is the #2135 class the proofs exist to catch — a missing " +
                    "post-recovery terminal viewport is a hard failure, never a silent " +
                    "skip. last observed: $lastState",
                timeout,
            )
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val elapsedMs = SystemClock.elapsedRealtime() - started
        recordTiming("${label}_viewport_restored_ms", elapsedMs)
        return elapsedMs
    }

    /** Depth-first search for the Termux view the proofs capture. */
    fun View.findTerminalViewInTree(): TerminalView? {
        if (this is TerminalView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            val match = getChildAt(index).findTerminalViewInTree()
            if (match != null) return match
        }
        return null
    }
}
