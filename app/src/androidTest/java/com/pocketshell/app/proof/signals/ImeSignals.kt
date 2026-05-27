package com.pocketshell.app.proof.signals

import android.app.Activity
import android.os.SystemClock
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Default upper-bound timeout for IME visibility waits on CI emulators.
 *
 * Local Linux emulator with the host GPU normally surfaces the IME ack
 * inside ~500 ms. The GitHub Actions emulator (`reactivecircus/
 * android-emulator-runner@v2`, api-34, swiftshader, 2 cores) commonly
 * lags 5–15 s and can spike to ~25 s when other instrumentation tests
 * share the device. 30 s is a generous "this is broken, not slow"
 * ceiling that never trips on a healthy run.
 */
internal const val IME_VISIBILITY_DEFAULT_TIMEOUT_MS: Long = 30_000L

/**
 * Polls [ViewCompat.getRootWindowInsets] on the activity's decor view at
 * 50 ms intervals until `isVisible(WindowInsetsCompat.Type.ime())`
 * matches [expected], or [timeoutMs] elapses. Returns the final observed
 * state (so the caller can assert on the value rather than only on
 * "we timed out").
 *
 * Why this is the deterministic signal:
 *
 * `dumpsys input_method` (the historical poll target — see
 * `TerminalKeyboardStressTest.waitForImeVisibility`) reports the system
 * IME manager's notion of `mInputShown`, which the IME process updates
 * asynchronously after `showSoftInput`/`hideSoftInputFromWindow` returns.
 * On CI swiftshader emulators that lag visibly behind the actual ime
 * insets being attached to the window — the window already has the IME
 * up, but `dumpsys` still says it doesn't, so the test thinks it failed.
 *
 * `WindowInsetsCompat.Type.ime()` is propagated by the framework as
 * soon as the IME's window adds/removes its insets to the focused
 * window. That is the same signal app code uses for keyboard-aware
 * layouts, so polling it here mirrors what users see and avoids the
 * `dumpsys` lag entirely.
 *
 * @param scenario the running activity scenario whose decor view will be
 *   inspected. Must be in at least the `STARTED` state.
 * @param expected `true` to wait for the IME to become visible, `false`
 *   to wait for it to be hidden.
 * @param timeoutMs upper bound; defaults to [IME_VISIBILITY_DEFAULT_TIMEOUT_MS].
 *   The default is intentionally generous so this helper is not the thing
 *   that fails first on a slow CI emulator — callers should set tighter
 *   timeouts only when their test budget genuinely demands it.
 * @return the final observed IME visibility (matches [expected] on
 *   success; equals `!expected` on timeout).
 */
fun waitForInputMethodVisible(
    scenario: ActivityScenario<out Activity>,
    expected: Boolean,
    timeoutMs: Long = IME_VISIBILITY_DEFAULT_TIMEOUT_MS,
): Boolean {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    var lastSeen: Boolean = !expected
    while (SystemClock.elapsedRealtime() < deadline) {
        instrumentation.waitForIdleSync()
        val observed = readImeVisible(scenario)
        if (observed != null) {
            lastSeen = observed
            if (observed == expected) return observed
        }
        SystemClock.sleep(50)
    }
    return lastSeen
}

/**
 * One-shot read of the IME inset visibility on the scenario's activity
 * decor view, returning `null` if the insets are not yet attached (the
 * window has not laid out, the scenario has been destroyed, etc.). The
 * polling helper treats `null` as "not yet" and keeps polling.
 */
private fun readImeVisible(scenario: ActivityScenario<out Activity>): Boolean? {
    var result: Boolean? = null
    scenario.onActivity { activity ->
        val decor = activity.window?.decorView ?: return@onActivity
        val insets = ViewCompat.getRootWindowInsets(decor) ?: return@onActivity
        result = insets.isVisible(WindowInsetsCompat.Type.ime())
    }
    return result
}
