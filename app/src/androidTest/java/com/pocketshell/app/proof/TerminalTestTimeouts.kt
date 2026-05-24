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
     * `true` when the instrumentation harness was told this is a CI
     * run. The arg is set from the workflow as
     * `-Pandroid.testInstrumentationRunnerArguments.pocketshellCi=true`.
     */
    fun isRunningOnCi(): Boolean =
        InstrumentationRegistry.getArguments()
            .getString(CI_ARG_KEY)
            ?.toBooleanStrictOrNull() == true
}
