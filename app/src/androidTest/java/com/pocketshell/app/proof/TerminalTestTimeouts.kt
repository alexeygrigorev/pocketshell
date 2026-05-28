package com.pocketshell.app.proof

import androidx.test.platform.app.InstrumentationRegistry

/**
 * CI-aware timeout source for connected Android tests that wait on
 * visible terminal text round-trips (SSH input → remote PTY render →
 * Termux `TerminalEmulator` state).
 *
 * Why this exists:
 *
 *  - On local Linux dev emulators (host GPU, plenty of cores, dedicated
 *    Docker daemon), a backspace-induced redraw of the test TUI lands in
 *    well under 5 s.
 *  - On the GitHub Actions emulator (`reactivecircus/android-emulator-runner@v2`,
 *    api-34, Pixel 7 profile, 2 cores, swiftshader GPU, sharing the host
 *    with a parallel Docker `agents` container), the same round-trip can
 *    take 25–40 s under load. Adding more sibling instrumentation tests
 *    (e.g. the host-card setup-badge and tmux voice surface coverage that
 *    landed in #122/#123) further compresses the emulator's slack, so a
 *    flat 60 s deadline observed as "comfortably green" on the original
 *    CI fix branch (`f1186f8`) regressed back to red as soon as more
 *    tests joined the run.
 *
 * Rather than bump every test's local-friendly deadline to a worst-case
 * CI ceiling (which would slow down local feedback for failures), this
 * helper opts CI into a separate, generous deadline while keeping the
 * local deadline tight. The instrumentation-runner argument
 * `pocketshellCi=true` is set from the CI workflow only; running locally
 * (`./gradlew :app:connectedDebugAndroidTest`) leaves it unset and the
 * local deadline applies.
 *
 * Note on environment variables: tests run on the device under
 * `androidx.test.runner.AndroidJUnitRunner`, so reading the runner
 * host's `CI` / `GITHUB_ACTIONS` env vars directly from test code does
 * not work — those vars belong to the runner process on the workflow VM,
 * not the emulator. Instrumentation runner arguments are the supported
 * channel, propagated by gradle's
 * `-Pandroid.testInstrumentationRunnerArguments.<key>=<value>` to the
 * device's test harness.
 */
internal object TerminalTestTimeouts {

    private const val CI_ARG_KEY: String = "pocketshellCi"

    private const val LOCAL_TERMINAL_VISIBILITY_TIMEOUT_MS: Long = 60_000L
    private const val CI_TERMINAL_VISIBILITY_TIMEOUT_MS: Long = 180_000L

    private const val LOCAL_PER_CHARACTER_STALL_CEILING_MS: Long = 10_000L
    private const val CI_PER_CHARACTER_STALL_CEILING_MS: Long = 30_000L

    // --- Keyboard-hide ceilings (decoupled IME ack vs. layout settle) ----
    //
    // Issue #142 split the keyboard-hide responsiveness assertion into two
    // independent measurements so the system-framework delay (IME ack
    // propagating through WindowInsets) does not contaminate the
    // app-layout-responsiveness ceiling. Both numbers come from the
    // signal-helpers package (#140):
    //
    //  - IME visibility ack uses `waitForInputMethodVisible`, which polls
    //    WindowInsets directly and bypasses the historic `dumpsys
    //    input_method` lag observed on swiftshader CI emulators. The local
    //    surface routinely lands under ~500 ms; CI swiftshader has been
    //    observed to spike past 4 s when a busy emulator is also serving
    //    sibling instrumentation, hence the loose 5 s ceiling. The local
    //    ceiling stays tight at 2.5 s so a regression on a dev box still
    //    surfaces.
    //
    //  - Compose layout-stable uses `waitForComposeLayoutStable`, which
    //    samples the `TERMINAL_LAB_SCREEN_TAG` column's bounding rect.
    //    The column has `imePadding()`, so its rect changes as the IME
    //    inset is removed and settles within a frame or two of the IME
    //    ack landing. This is "our code's responsibility" — Compose
    //    recomposition and AndroidView interop — and is held to a tight
    //    3 s on CI / 1 s local ceiling. CI has produced valid passes
    //    around 2.6 s while the emulator is under full connected-test
    //    load; the local ceiling stays tight so a dev-box regression still
    //    surfaces.
    private const val LOCAL_KEYBOARD_HIDE_IME_ACK_CEILING_MS: Long = 2_500L
    private const val CI_KEYBOARD_HIDE_IME_ACK_CEILING_MS: Long = 5_000L

    private const val LOCAL_KEYBOARD_HIDE_LAYOUT_STABLE_CEILING_MS: Long = 1_000L
    private const val CI_KEYBOARD_HIDE_LAYOUT_STABLE_CEILING_MS: Long = 3_000L

    /**
     * Timeout for a single `waitForVisibleTerminalText` poll loop. The
     * loop early-exits as soon as its predicate matches, so a generous
     * CI deadline does not slow tests that pass quickly.
     */
    fun terminalVisibilityTimeoutMs(): Long =
        if (isRunningOnCi()) {
            CI_TERMINAL_VISIBILITY_TIMEOUT_MS
        } else {
            LOCAL_TERMINAL_VISIBILITY_TIMEOUT_MS
        }

    /**
     * Per-character stall ceiling used by stress tests that type a
     * burst of characters one at a time and expect each one to become
     * visible within a deadline. Used for the "no single character
     * stalls forever" assertion, NOT for the responsiveness gate (which
     * is a separate median-latency assertion in the test itself).
     */
    fun perCharacterStallCeilingMs(): Long =
        if (isRunningOnCi()) {
            CI_PER_CHARACTER_STALL_CEILING_MS
        } else {
            LOCAL_PER_CHARACTER_STALL_CEILING_MS
        }

    /**
     * Per-cycle ceiling for the IME visibility ack to flip from
     * "visible" to "hidden" after `hideSoftInputFromWindow`. The
     * measurement uses `waitForInputMethodVisible` from the signal-
     * helpers package, which polls `WindowInsetsCompat.Type.ime()` and
     * does NOT suffer from the historical `dumpsys input_method`
     * propagation lag on swiftshader emulators. Even so, the IME
     * process is system-framework code outside our control, hence
     * the looser CI ceiling than the layout-settle ceiling below.
     */
    fun keyboardHideImeAckCeilingMs(): Long =
        if (isRunningOnCi()) {
            CI_KEYBOARD_HIDE_IME_ACK_CEILING_MS
        } else {
            LOCAL_KEYBOARD_HIDE_IME_ACK_CEILING_MS
        }

    /**
     * Per-cycle ceiling for the terminal-lab Compose surface to reach
     * layout-stable after the IME ack has landed. The measurement uses
     * `waitForComposeLayoutStable` from the signal-helpers package,
     * sampling the bounding rect of the `TERMINAL_LAB_SCREEN_TAG`
     * column (which has `imePadding()` so its rect changes as the IME
     * inset is removed). This is our app's responsibility — Compose
     * recomposition + AndroidView interop — so the ceiling stays tight
     * to catch real regressions.
     */
    fun keyboardHideLayoutStableCeilingMs(): Long =
        if (isRunningOnCi()) {
            CI_KEYBOARD_HIDE_LAYOUT_STABLE_CEILING_MS
        } else {
            LOCAL_KEYBOARD_HIDE_LAYOUT_STABLE_CEILING_MS
        }

    /**
     * `true` when the instrumentation harness was told this is a CI
     * run. The arg is set from the workflow as
     * `-Pandroid.testInstrumentationRunnerArguments.pocketshellCi=true`.
     */
    fun isRunningOnCi(): Boolean =
        InstrumentationRegistry.getArguments()
            .getString(CI_ARG_KEY)
            ?.toBooleanStrictOrNull() == true
}
