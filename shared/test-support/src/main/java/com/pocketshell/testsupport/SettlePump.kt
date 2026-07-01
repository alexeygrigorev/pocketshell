package com.pocketshell.testsupport

/**
 * The ONE audited settle-pump for the #1048 de-flake convention (Shape B —
 * wall-clock-bounded pump). It replaces 5+ hand-rolled, drifting near-duplicate
 * wall-clock pumps across the `:app` and `:shared:core-terminal` unit tests
 * (`awaitCondition`, two `drainMainLooperUntil`s, the SshTerminalBridge flood
 * pump). Consolidating the boundary in one reviewed place is what stops the
 * NEXT timing flake.
 *
 * ## What this owns (the load-bearing, audited invariants)
 * - **A GENEROUS wall-clock deadline** measured on [System.currentTimeMillis].
 *   The loop returns the instant [condition] holds (no slowdown on a healthy
 *   run) and returns `false` the moment the deadline passes.
 * - **A HARD boundary, never a silent swallow (#1102 lesson).** This function
 *   returns a `Boolean`; the deadline is the load-bearing assertion and the
 *   CALLER must `assertTrue(...)`/`throw` on `false`. It never weakens the
 *   caller's assertion — a genuine stall still exhausts the deadline and reds.
 * - **It NEVER touches a clock itself.** It does not advance the kotlinx
 *   `runTest` virtual scheduler and does not idle any Android looper. Advancing
 *   the virtual clock prematurely trips production watchdogs (e.g. the #793
 *   black-screen re-seed watchdog) — that is exactly the #1110 flake this
 *   consolidation must not reintroduce. All per-tick draining is delegated to
 *   [onTick].
 *
 * ## Why the per-tick drain is injected ([onTick]) rather than baked in
 * The migrated pumps have GENUINELY DIFFERENT, irreconcilable per-tick drains —
 * forcing them into one body would mask a real wait (the #1048 brief's explicit
 * "do NOT force them into one" rule):
 * - `awaitCondition` (cache-restore re-seed, #1110): must `runCurrent()` to
 *   drain Main continuations after a real `Dispatchers.IO` read, but must NOT
 *   idle the looper — idling would advance the looper clock and risk firing the
 *   #793 watchdog. Drain: `{ runCurrent() }`.
 * - The SshTerminalBridge-fed flood/codex pumps (#1042/#1050): the #803/#804
 *   `MainThreadDrainScheduler` `postDelayed`s its continuation one frame out, so
 *   they MUST idle the looper a frame (`idleFor(16ms)`) AND `runCurrent()`.
 *   Drain: `{ idleFor(16ms); runCurrent() }`.
 * - `SshTerminalBridgeTest` (#803): a direct-bridge test that is NOT inside a
 *   `runTest`, so it has no `TestScope` and cannot `runCurrent()` at all — it
 *   only idles the looper. Drain: `{ idleFor(16ms) }`.
 *
 * Each call site keeps its own thin, descriptively-named wrapper (so its call
 * sites are untouched) and passes the drain it genuinely needs; the bounded
 * loop and the hard deadline live here, once.
 *
 * The two pumps that intentionally advance the kotlinx VIRTUAL clock
 * (`PromptComposerViewModelTest.advanceSchedulerUntil`, which `advanceTimeBy`s
 * and yields to real `Dispatchers.IO`, and `TmuxSessionWarmOpenTest.pumpUntil`,
 * which `advanceUntilIdle`s) are deliberately NOT migrated onto this helper —
 * advancing the virtual clock is their whole point and is incompatible with the
 * "never touch a clock" invariant above. They stay separate, by design.
 *
 * @param deadlineMs generous wall-clock budget; the pump returns `false` once it
 *   elapses without [condition] holding.
 * @param sleepMs real-wall-clock yield per tick so off-Main / real-IO threads
 *   get scheduling time before the next drain.
 * @param onTick the per-tick drain (see above); defaults to a no-op pure poll.
 * @param condition the load-bearing exit predicate.
 * @return `true` if [condition] held within [deadlineMs]; `false` on timeout.
 */
fun drainMainLooperUntil(
    deadlineMs: Long,
    sleepMs: Long = 2L,
    onTick: () -> Unit = {},
    condition: () -> Boolean,
): Boolean {
    require(deadlineMs > 0) { "deadlineMs must be > 0, was $deadlineMs" }
    val deadline = System.currentTimeMillis() + deadlineMs
    do {
        onTick()
        if (condition()) return true
        Thread.sleep(sleepMs)
    } while (System.currentTimeMillis() < deadline)
    // One final drain so a continuation that became ready right at the boundary
    // still counts before we report a hard timeout.
    onTick()
    return condition()
}
