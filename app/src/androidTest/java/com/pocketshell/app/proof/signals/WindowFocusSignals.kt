package com.pocketshell.app.proof.signals

import android.app.Activity
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Issue #1879: window-focus pre-condition signals for journeys that assert on
 * the **real** system soft keyboard.
 *
 * ## Why this exists
 *
 * `InputMethodManager.showSoftInput(view)` is not merely "slow" when the app
 * window does not hold input focus — the framework **refuses it outright**:
 *
 * ```
 * W InputMethodManager: Ignoring showSoftInput() as view=com.termux.view.TerminalView{…} is not served.
 * I ImeTracker: com.pocketshell.app:…: onFailed at PHASE_CLIENT_VIEW_SERVED
 * ```
 *
 * The "served view" is established by `ViewRootImpl` only for the window that
 * currently holds input focus. So while ANY other window owns focus — most
 * commonly a framework error dialog such as `AppNotRespondingDialog`
 * ("<app> isn't responding" / Close app / Wait) raised for an unrelated system
 * app — the IME is unreachable **in both directions**: neither `showSoftInput`
 * nor `hideSoftInputFromWindow` does anything at all.
 *
 * That is exactly what reddened `ShowKeyboardChipE2eTest` on shard 2 of run
 * 30454838433: an ANR dialog raised for `com.google.android.apps.nexuslauncher`
 * stood over the session screen for the whole class (visible in both the
 * before-tap and after-tap artifacts of both attempts), so the chip tap could
 * never raise the keyboard. The test then reported
 * `ime_visible_after_tap=false raisedMs=45052` — a message that blames the chip
 * for a state the chip was never given.
 *
 * Note the package there is the app the dialog was ABOUT, read from logcat. The
 * dialog's own **window** belongs to `android`, which is what
 * [describeActiveWindow] reports and why that reading cannot be used to decide
 * whose fault a failure is — see [FOREIGN_WINDOW_FOCUS_SIGNATURE].
 *
 * ## What this does, and what it deliberately does NOT do
 *
 * It **observes and reports**. It never acts on the UI.
 *
 * A keyboard-down pre-condition is satisfied by two very different states:
 *
 *  * (A) the reported user state — the app window is focused and the keyboard
 *    is simply down, so a chip tap MUST raise it; and
 *  * (B) a degenerate state — no app window holds focus at all, so the IME is
 *    unreachable and nothing about the chip can be observed.
 *
 * Only (A) is the maintainer's scenario. [awaitActivityWindowFocus] pins the
 * journey to (A): it waits, bounded, for focus, and when focus does not arrive
 * it hands the caller an accurate diagnosis naming the window that owns it so
 * the caller can HARD-FAIL with the real cause. There is no `assumeTrue` here
 * and callers must not add one (F3): a self-skip would mean only a healthy
 * dev-box AVD ever asserts.
 *
 * ## Why there is no recovery arm (round-2 review of #1879)
 *
 * The first cut of this file tried to *clear* a foreign focus owner — click a
 * framework ANR-dialog button (an id owned by package `android`), else send a
 * global BACK. The global BACK had no owner check, and
 * `TmuxSessionAuxiliaryModals` renders BACK-dismissible `AlertDialog`s on the
 * very screen under test. So a product regression that popped a spurious modal
 * over the live session — which the framework refuses `showSoftInput` for in
 * exactly the same way — would have been dismissed after a wait and the journey
 * would have gone **green**. That is a masking channel added to a load-bearing
 * journey, and the artifacts proved the focus owner can be the app itself
 * (`active_window_pkg=com.pocketshell.app.i1879`).
 *
 * Gating the recovery on "the owner is genuinely foreign" closes that specific
 * hole, but only by adding a branch that decides, on a load-bearing journey,
 * when a failure is allowed to disappear. A recovery arm is a masking channel
 * whatever it is gated on, and this one would be reached rarely enough that a
 * mistake in the gate could sit unnoticed for releases. So the recovery is
 * **deleted** (D22 hard cut), not shimmed, and this file observes only.
 *
 * (An earlier revision of this comment justified the deletion by claiming a
 * real system error dialog "cannot be manufactured on demand". That is false —
 * `am crash <pkg>` raises a genuine framework error dialog, package `android`,
 * with `android:id/aerr_*` nodes. The deletion is right, but for the reason
 * above, not that one.)
 *
 * The environment case is handled where it belongs: the failure is loud and
 * carries [FOREIGN_WINDOW_FOCUS_SIGNATURE] plus `active_window_pkg=`, so a
 * human reading the red shard can see immediately that no app window held
 * focus and who did. It is **not** auto-classified as infrastructure — see the
 * note on [FOREIGN_WINDOW_FOCUS_SIGNATURE].
 *
 * ## No shared-state mutation (round-2 review of #1879)
 *
 * Nothing here reads or writes the shared automation service's configuration.
 * The first cut OR-ed `FLAG_REPORT_VIEW_IDS | FLAG_RETRIEVE_INTERACTIVE_WINDOWS`
 * into it — a process-wide accessibility reconfiguration that was never
 * restored, so every later class on the shard inherited it. On an issue whose
 * subject is one class's environment leaking into another's verdict, that was
 * the wrong direction. [describeActiveWindow] reads `rootInActiveWindow` only,
 * which needs no flags, and it is called **only on a failure path** —
 * [describeActiveWindowCallCount] exists so a test can prove that.
 */

/**
 * Stable prefix emitted by callers when the app window never held focus. Kept
 * as a constant so a CI failure carries one greppable phrase distinguishing
 * "the environment never let the IME be reachable" from "the control under
 * test did not work".
 *
 * ## Deliberately NOT an auto-INFRA signature (round-3 review of #1879)
 *
 * This is a **label**, not a classifier key. `scripts/ci-journey-infra-signature.py`
 * does not recognise it and must not be taught to, because the only value that
 * could gate it does not discriminate.
 *
 * [describeActiveWindow] reports the package of the **window** that owns focus.
 * A framework error dialog's window belongs to `android` — never to the app the
 * dialog is about. Observed on a live API-35 AVD by crashing an unrelated app:
 *
 * ```
 * mCurrentFocus=Window{f0c7097 u0 Application Error: com.android.settings}
 * package="android"  resource-id="android:id/aerr_close"
 * ```
 *
 * So `active_window_pkg=android` is emitted identically when the launcher ANRs
 * (environment) and when **PocketShell** ANRs — issue #796, the recurring Codex
 * terminal freeze, this project's worst recurring product defect. A downgrade
 * keyed on that value would silently re-run #796 away. Naming the faulting app
 * instead does not rescue it: the dialog's node tree carries only the localised
 * app label ("Settings keeps stopping"), never the package, and logcat's
 * `am_anr`/`am_crash` records are device-global and retain hours, so they say
 * nothing about who owns focus at the moment of this failure.
 *
 * The relief valve is the human one G5 already specifies — the signature is
 * captured in the failure text and the summary, and the on-call re-runs the
 * shard deliberately. `scripts/test-ci-journey-infra-signature.sh` pins every
 * producible owner reading, `android` included, to RED.
 */
const val FOREIGN_WINDOW_FOCUS_SIGNATURE: String =
    "The app window never held input focus, so the system refused every " +
        "showSoftInput() call (\"is not served\")."

/** Default ceiling for the app window to (re)gain input focus. */
internal const val WINDOW_FOCUS_DEFAULT_TIMEOUT_MS: Long = 10_000L

/** Emitted when the active window cannot be read at all. */
const val ACTIVE_WINDOW_UNAVAILABLE: String = "<unavailable>"

@Volatile
private var describeCalls: Int = 0

/**
 * How many times [describeActiveWindow] has run in this process. The diagnosis
 * costs a full accessibility tree fetch, so a journey asserts this stays 0
 * across its happy path — that is the mechanical proof the diagnosis is lazy
 * rather than interpolated into an eagerly-evaluated assertion message.
 */
fun describeActiveWindowCallCount(): Int = describeCalls

/** Reset the [describeActiveWindowCallCount] baseline for one test. */
fun resetDescribeActiveWindowCallCount() {
    describeCalls = 0
}

/**
 * Result of [awaitActivityWindowFocus]. [diagnosis] is cheap and constant on
 * the success path; the expensive active-window description is computed only
 * when focus was NOT obtained.
 */
data class WindowFocusOutcome(
    val focused: Boolean,
    val diagnosis: String,
)

/** One-shot read of `Activity.hasWindowFocus()` on the scenario's activity. */
fun activityWindowFocused(scenario: ActivityScenario<out Activity>): Boolean {
    var focused = false
    runCatching { scenario.onActivity { focused = it.hasWindowFocus() } }
    return focused
}

/**
 * Polls [activityWindowFocused] at 50 ms intervals until the activity's window
 * holds input focus or [timeoutMs] elapses. Returns the final observed state.
 * A [timeoutMs] of 0 degenerates to a single immediate read.
 */
fun waitForActivityWindowFocused(
    scenario: ActivityScenario<out Activity>,
    timeoutMs: Long = WINDOW_FOCUS_DEFAULT_TIMEOUT_MS,
): Boolean {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
        if (activityWindowFocused(scenario)) return true
        SystemClock.sleep(50)
    }
    return activityWindowFocused(scenario)
}

/**
 * The mirror of [waitForActivityWindowFocused]: polls until the activity's
 * window has LOST input focus, or [timeoutMs] elapses.
 *
 * A test that injects a focus-stealing window needs this, not
 * `!waitForActivityWindowFocused(...)`: that call returns `true` at the FIRST
 * poll where focus is still held, so it reports "not stolen" before the
 * stealer's window has even been attached. Getting this backwards makes the
 * injection silently fail to reproduce anything.
 */
fun waitForActivityWindowFocusLost(
    scenario: ActivityScenario<out Activity>,
    timeoutMs: Long = WINDOW_FOCUS_DEFAULT_TIMEOUT_MS,
): Boolean {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
        if (!activityWindowFocused(scenario)) return true
        SystemClock.sleep(50)
    }
    return !activityWindowFocused(scenario)
}

/**
 * Establish the "app window holds input focus" pre-condition — the bounded,
 * hard-failing wait the issue asks for on the pre-condition side.
 *
 * Observes only. Never throws, never skips, never touches the UI: the caller
 * asserts on [WindowFocusOutcome.focused] and reports
 * [WindowFocusOutcome.diagnosis].
 */
fun awaitActivityWindowFocus(
    scenario: ActivityScenario<out Activity>,
    timeoutMs: Long = WINDOW_FOCUS_DEFAULT_TIMEOUT_MS,
): WindowFocusOutcome {
    if (waitForActivityWindowFocused(scenario, timeoutMs)) {
        return WindowFocusOutcome(focused = true, diagnosis = "app_window_focused=true")
    }
    // Failure path only — see the laziness note in the file header.
    return WindowFocusOutcome(
        focused = false,
        diagnosis = "app_window_focused=false ${describeActiveWindow()}",
    )
}

/**
 * Issue #2021 leftover: an unfocused activity while OUR package still owns
 * the accessibility window is the hosted splash-owner / in-app sheet stall
 * (`active_window_pkg=<app> active_window_class=android.widget.FrameLayout`),
 * not a foreign thief. Fail closed on `<unavailable>` or any other package.
 *
 * A framework Error/ANR is never app-owned: its window belongs to `android`
 * (#1879) and [focusedFrameworkErrorPackage] names the subject.
 */
fun isAppOwnedUnfocusedDiagnosis(diagnosis: String): Boolean {
    val appPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName
    val pkg = diagnosis.substringAfter("active_window_pkg=", missingDelimiterValue = "")
        .substringBefore(' ')
    if (pkg.isBlank() || pkg == ACTIVE_WINDOW_UNAVAILABLE || pkg != appPackage) {
        return false
    }
    return focusedFrameworkErrorPackage() == null
}

/**
 * Describe the window that currently owns the active (focused) accessibility
 * window: its owning package and root class. Used to name the real cause in a
 * failure message, and parsed by the CI classifier's foreignness gate.
 *
 * Reads `rootInActiveWindow` only — no automation-service flag is read, set, or
 * restored, so nothing about the shared automation service changes.
 */
fun describeActiveWindow(): String {
    describeCalls++
    val root = runCatching {
        InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
    }.getOrNull()
        ?: return "active_window_pkg=$ACTIVE_WINDOW_UNAVAILABLE " +
            "active_window_class=$ACTIVE_WINDOW_UNAVAILABLE"
    return try {
        val pkg = root.packageName?.toString()?.takeIf { it.isNotBlank() }
            ?: ACTIVE_WINDOW_UNAVAILABLE
        val cls = root.className?.toString()?.takeIf { it.isNotBlank() }
            ?: ACTIVE_WINDOW_UNAVAILABLE
        "active_window_pkg=$pkg active_window_class=$cls"
    } finally {
        @Suppress("DEPRECATION")
        runCatching { root.recycle() }
    }
}
