package com.pocketshell.app.proof.signals

import android.app.Activity
import android.app.Dialog
import android.os.SystemClock
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
 */
class SyntheticFocusOwnerHarness(
    private val scenario: ActivityScenario<out Activity>,
    private val label: String,
    private val timeoutMs: Long = WINDOW_FOCUS_DEFAULT_TIMEOUT_MS,
) {
    private var owner: Dialog? = null

    fun <T> withOwner(block: (Dialog) -> T): T {
        requirePocketShellFocusAtJourneyBoundary(
            scenario = scenario,
            context = "before raising synthetic focus owner '$label'",
            timeoutMs = timeoutMs,
        )

        val dialog = showOwner()
        var bodyFailure: Throwable? = null
        try {
            requireOwnerFocus(dialog)
            return block(dialog)
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            try {
                dismissAndRequirePocketShellFocus()
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

    private fun dismissAndRequirePocketShellFocus() {
        val dialog = owner
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (dialog?.isShowing == true) dialog.dismiss()
        }
        owner = null
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        requirePocketShellFocusAtJourneyBoundary(
            scenario = scenario,
            context = "after dismissing synthetic focus owner '$label'",
            timeoutMs = timeoutMs,
        )
    }
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
            "$FOREIGN_WINDOW_FOCUS_SIGNATURE $context; ${outcome.diagnosis}",
        )
    }
}
