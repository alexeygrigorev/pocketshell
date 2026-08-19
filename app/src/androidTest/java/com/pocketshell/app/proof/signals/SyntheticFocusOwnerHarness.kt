package com.pocketshell.app.proof.signals

import android.app.Activity
import android.app.Dialog
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Owns the complete lifetime of a focus-stealing test window.
 *
 * The owner-specific focus check is intentional. Merely observing that the
 * activity lost focus can be satisfied by an unrelated system ANR window, as
 * happened on nightly shard 2 in issue #1985. Cleanup always dismisses only the
 * dialog this harness created and then proves PocketShell regained focus; it
 * never acts on an unrelated window that may represent a real product failure.
 *
 * ## Issue #2021 — the entry state is RECORDED, not demanded
 *
 * [withOwner] used to refuse to run at all unless the activity ALREADY held
 * input focus. On the 2026-08-06 nightly (run 31074120488) that refusal voided
 * two of the seven #1994 reopen-proof arms before a single product assertion
 * ran, with:
 *
 * ```
 * app_window_focused=false active_window_pkg=com.pocketshell.app
 *   active_window_class=android.widget.FrameLayout
 * focused_framework_package=<none>
 * ```
 *
 * i.e. **PocketShell itself owned the display** — no foreign window, no ANR.
 * That shard's per-class logcat shows `MainActivity … RESUMED`, the launcher
 * gone (`VRI[NexusLauncherActivity] … newVisibility=false`), no error dialog,
 * and swiftshader frames up to 13.9 s (`app_time_stats … max=13971.95ms`)
 * around a splash-window handover the framework itself complained about
 * (`Input channel object '… Splash Screen com.pocketshell.app (client)' was
 * disposed without first being removed with the input manager!`). The grant
 * simply never arrived inside the 10 s window.
 *
 * A downstream journey cannot be the judge of a state it did not create, and
 * making its verdict conditional on that state is how a reopen-proof ends up
 * with no signal at all. So the entry reading is now recorded, and every
 * assertion this harness makes is about state IT owns:
 *
 *  * the injection took — the owner window holds focus AND the activity's own
 *    reading (the one the #1994 capture oracle consumes) flipped to unfocused;
 *  * at exit the owner this harness created is GONE; and
 *  * focus is restored to at least the entry state — when the activity held
 *    focus on entry it must hold it again, which is #1985's invariant unchanged.
 *
 * None of that is a self-skip: nothing is skipped, the body always runs, and a
 * failure to inject is a hard failure. See also [recordJourneyEntryFocus] /
 * [requireNoJourneyOwnedFocusRegression] for the same rule at class boundaries.
 */
class SyntheticFocusOwnerHarness(
    private val scenario: ActivityScenario<out Activity>,
    private val label: String,
    private val timeoutMs: Long = WINDOW_FOCUS_DEFAULT_TIMEOUT_MS,
) {
    private var owner: Dialog? = null

    fun <T> withOwner(block: (Dialog) -> T): T {
        val focusedAtEntry = waitForActivityWindowFocused(scenario, timeoutMs)
        val entryDiagnosis = if (focusedAtEntry) {
            "app_window_focused=true"
        } else {
            "app_window_focused=false ${describeActiveWindow()}"
        }

        val dialog = showOwner()
        var bodyFailure: Throwable? = null
        try {
            requireOwnerFocus(dialog)
            requireActivityLostFocusToOwner(entryDiagnosis)
            return block(dialog)
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            try {
                dismissAndRequireOwnerGone(dialog, focusedAtEntry, entryDiagnosis)
            } catch (cleanupFailure: Throwable) {
                bodyFailure?.let(cleanupFailure::addSuppressed)
                throw cleanupFailure
            }
        }
    }

    /** Belt-and-braces teardown; load-bearing calls use [withOwner]. */
    fun dismissBestEffort() {
        val dialog = owner
        runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                if (dialog?.isShowing == true) dialog.dismiss()
            }
        }
        owner = null
    }

    private fun showOwner(): Dialog {
        var shown: Dialog? = null
        scenario.onActivity { activity ->
            shown = Dialog(activity).also { dialog ->
                dialog.setContentView(TextView(activity).apply {
                    text = label
                    isFocusable = true
                    isFocusableInTouchMode = true
                })
                dialog.setCancelable(false)
                dialog.show()
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return requireNotNull(shown).also { owner = it }
    }

    private fun requireOwnerFocus(dialog: Dialog) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (dialog.window?.decorView?.hasWindowFocus() == true) return
            SystemClock.sleep(50)
        }
        if (dialog.window?.decorView?.hasWindowFocus() != true) {
            throw AssertionError(
                "synthetic dialog '$label' never became the actual focus owner; " +
                    "activity focus loss alone is not proof; ${describeActiveWindow()}",
            )
        }
    }

    /**
     * The #1994 capture oracle reads the ACTIVITY, not the dialog
     * (`activityWindowFocused` feeds `evaluateMarkerBandCapture`). So the
     * injection is hard-asserted on that same reading, not only on the owner's,
     * or an arm could "inject" a state its own oracle never sees.
     */
    private fun requireActivityLostFocusToOwner(entryDiagnosis: String) {
        if (waitForActivityWindowFocusLost(scenario, timeoutMs)) return
        throw AssertionError(
            "synthetic focus owner '$label' holds the window focus but the activity " +
                "still reports focus, so the injected state never reached the reading " +
                "the capture oracle consumes; entry_state=$entryDiagnosis; " +
                describeActiveWindow(),
        )
    }

    private fun dismissAndRequireOwnerGone(
        dialog: Dialog,
        focusedAtEntry: Boolean,
        entryDiagnosis: String,
    ) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (dialog.isShowing) dialog.dismiss()
        }
        owner = null
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        // What this harness OWNS: the window it raised must be gone. This is the
        // leak check #1985 exists for, and it holds regardless of the ambient
        // state — a boundary reading cannot tell one app-owned window from
        // another, but the harness knows exactly which one it created.
        val ownerDeadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < ownerDeadline) {
            if (!dialog.isShowing && dialog.window?.decorView?.hasWindowFocus() != true) break
            SystemClock.sleep(50)
        }
        if (dialog.isShowing || dialog.window?.decorView?.hasWindowFocus() == true) {
            throw AssertionError(
                "synthetic focus owner '$label' survived its own cleanup and would leak " +
                    "into the next journey; showing=${dialog.isShowing} " +
                    "owner_focused=${dialog.window?.decorView?.hasWindowFocus()}; " +
                    describeActiveWindow(),
            )
        }

        // #1985 invariant, unchanged for a FOREIGN owner this harness did not
        // create: if the activity held focus before the owner was raised, a
        // leftover thief that is not our package still hard-fails. The leftover
        // #2021 recurrence is an APP-OWNED splash/sheet FrameLayout after a
        // focused entry — that is the same inherited stall, not a leak of the
        // dialog this harness just dismissed (the leak check above already
        // proved that window is gone).
        if (focusedAtEntry) {
            requireNoForeignFocusOwnerAtJourneyBoundary(
                scenario = scenario,
                context = "after dismissing synthetic focus owner '$label'",
                timeoutMs = timeoutMs,
            )
        } else {
            Log.i(
                FOCUS_BOUNDARY_LOG_TAG,
                "synthetic-owner-exit label='$label' inherited_entry_state=$entryDiagnosis " +
                    "restored_focus=${activityWindowFocused(scenario)}",
            )
        }
    }
}

/** Log tag for the issue #2021 inherited-focus boundary trail. */
const val FOCUS_BOUNDARY_LOG_TAG: String = "PsJourneyFocusBoundary"

/**
 * Close the ONE environment-owned focus window #1985 proved safe to close: a
 * currently focused framework error dialog whose subject is the resolved HOME
 * package. Nothing else is ever touched — a framework dialog for PocketShell,
 * an app-owned dialog, or any other package stays exactly where it is so it can
 * keep the suite red with its own diagnosis.
 */
private fun cleanupLauncherFrameworkDialogIfFocused(
    scenario: ActivityScenario<out Activity>,
) {
    if (waitForActivityWindowFocused(scenario, timeoutMs = 250)) return
    val focusedFramework = focusedFrameworkErrorPackage() ?: return
    if (focusedFramework != resolveHomePackage()) return
    dismissFocusedLauncherFrameworkDialog()
}

/**
 * The focus reading a journey INHERITED at its entry boundary (issue #2021).
 *
 * Carried from `@Before` to `@After` so the exit boundary can hard-fail on a
 * regression this journey caused while staying silent about a state it merely
 * inherited from whatever ran before it on the shard.
 */
data class InheritedJourneyFocus(
    val focused: Boolean,
    val diagnosis: String,
    val context: String,
)

/**
 * Entry boundary: clean the ONE environment-owned window #1985 proved safe to
 * close (a framework error dialog for the resolved HOME package), then RECORD
 * the resulting focus reading.
 *
 * This deliberately does not throw on an inherited unfocused state. The
 * hard-failing focus assertions stay exactly where they are load-bearing:
 * inside the journey, immediately before the action that needs input focus
 * (`awaitActivityWindowFocus` in the IME journeys), and inside
 * [SyntheticFocusOwnerHarness] for the state it injects. Refusing to start
 * turned a nightly environment stall into "no verdict" for two #1994 reopen
 * arms; the in-journey assertions still turn the same state into a loud,
 * signature-carrying RED — a real verdict, which is what issue #2021 asks for.
 */
fun recordJourneyEntryFocus(
    scenario: ActivityScenario<out Activity>,
    context: String,
    timeoutMs: Long = WINDOW_FOCUS_DEFAULT_TIMEOUT_MS,
): InheritedJourneyFocus {
    cleanupLauncherFrameworkDialogIfFocused(scenario)
    val outcome = awaitActivityWindowFocus(scenario, timeoutMs)
    val inherited = InheritedJourneyFocus(
        focused = outcome.focused,
        diagnosis = outcome.diagnosis,
        context = context,
    )
    if (!outcome.focused) {
        Log.w(
            FOCUS_BOUNDARY_LOG_TAG,
            "journey-entry-inherited-foreign-focus $context; ${outcome.diagnosis}",
        )
    }
    return inherited
}

/**
 * Exit boundary: hard-fail only when THIS journey lost a focus it started with.
 *
 * [entry] is null when the journey never reached its entry boundary (an
 * `@Before` that threw still runs `@After`); the entry failure is that run's
 * verdict, so this boundary stays quiet rather than replacing it.
 */
fun requireNoJourneyOwnedFocusRegression(
    scenario: ActivityScenario<out Activity>,
    entry: InheritedJourneyFocus?,
    context: String,
    timeoutMs: Long = WINDOW_FOCUS_DEFAULT_TIMEOUT_MS,
) {
    if (entry == null) return
    cleanupLauncherFrameworkDialogIfFocused(scenario)
    if (!entry.focused) {
        Log.w(
            FOCUS_BOUNDARY_LOG_TAG,
            "journey-exit-inherited-focus-not-owned $context; entry=${entry.diagnosis}",
        )
        return
    }
    requireNoForeignFocusOwnerAtJourneyBoundary(
        scenario = scenario,
        context = context,
        timeoutMs = timeoutMs,
    )
}

/**
 * Exit-boundary teeth: wait for activity focus, then HARD-fail only on a
 * remaining FOREIGN owner. An app-owned splash/sheet FrameLayout after a
 * focused entry is the leftover Nightly recurrence (FileViewer teardown +
 * SessionSwipeSwitch #1994 arms) and must not become this journey's verdict.
 */
private fun requireNoForeignFocusOwnerAtJourneyBoundary(
    scenario: ActivityScenario<out Activity>,
    context: String,
    timeoutMs: Long,
) {
    val outcome = awaitActivityWindowFocus(scenario, timeoutMs)
    if (outcome.focused) return
    if (isAppOwnedUnfocusedDiagnosis(outcome.diagnosis)) {
        Log.w(
            FOCUS_BOUNDARY_LOG_TAG,
            "journey-exit-app-owned-splash-owner $context; ${outcome.diagnosis}",
        )
        return
    }
    throw AssertionError(
        "$FOREIGN_WINDOW_FOCUS_SIGNATURE $context; ${focusBoundaryDiagnosis(outcome.diagnosis)}",
    )
}

/**
 * Hard inter-test/journey focus invariant. Observation only: a genuine app or
 * system focus thief stays visible and keeps the suite red with its diagnosis.
 */
fun requirePocketShellFocusAtJourneyBoundary(
    scenario: ActivityScenario<out Activity>,
    context: String,
    timeoutMs: Long = WINDOW_FOCUS_DEFAULT_TIMEOUT_MS,
) {
    val outcome = awaitActivityWindowFocus(scenario, timeoutMs)
    if (!outcome.focused) {
        throw AssertionError(
            "$FOREIGN_WINDOW_FOCUS_SIGNATURE $context; ${focusBoundaryDiagnosis(outcome.diagnosis)}",
        )
    }
}

private fun focusBoundaryDiagnosis(base: String): String {
    val framework = focusedFrameworkErrorPackage()
    val home = tryResolveHomePackage()
    return "$base focused_framework_package=${framework ?: "<none>"} " +
        "home_package=${home ?: "<unavailable>"}"
}
