package com.pocketshell.testsupport

/**
 * The ONE audited generous wall-clock budget every Shape-B settle pump spends
 * (issue #2017).
 *
 * A settle pump's deadline is a REAL-time budget being spent on a box whose real
 * time is contended: sibling gate lanes, a cold Robolectric class load, a GC
 * pause, or a CI runner that simply does not schedule the awaited thread for a
 * few seconds all consume it without the awaited work being stalled at all. A
 * budget sized for an idle box therefore reds only under load — the worst
 * possible failure mode, because it is indistinguishable from a real regression
 * at first glance and the "just re-run it" reflex manufactures a convincing
 * green streak (a PASSING Gradle test task is skipped on re-run while a FAILING
 * one always re-executes).
 *
 * That is exactly how `Issue1574DeadReconnectTest`'s hand-rolled 5 s pump
 * behaved: green 3/3 in isolation, red at load ~13 with four parallel lanes. The
 * cure is NOT nudging a local `5_000L` upward — it is that the audited helper
 * owns ONE generous budget, so there is a single reviewed place where the
 * project's contention headroom is decided and no per-file constant can drift
 * back down.
 *
 * 30 s matches the budget the already-migrated tmux pumps converged on
 * (`SLOW_FEED_DRAIN_TIMEOUT_MS`) and stays safely under `runTest`'s 60 s default
 * global timeout, so the pump's own HARD-FAIL message fires first instead of
 * `runTest` aborting the whole test with no diagnosis. It does NOT slow a
 * healthy run: [drainMainLooperUntil] returns the instant its condition holds.
 */
const val GENEROUS_SETTLE_DEADLINE_MS: Long = 30_000L

/**
 * The ONE audited settle-pump for the #1048 de-flake convention (Shape B —
 * wall-clock-bounded pump). It replaces 5+ hand-rolled, drifting near-duplicate
 * wall-clock pumps across the `:app` and `:shared:core-terminal` unit tests
 * (`awaitCondition`, two `drainMainLooperUntil`s, the SshTerminalBridge flood
 * pump). Consolidating the boundary in one reviewed place is what stops the
 * NEXT timing flake.
 *
 * ## What this owns (the load-bearing, audited invariants)
 * - **A GENEROUS wall-clock deadline** measured on [System.currentTimeMillis],
 *   defaulting to the ONE audited [GENEROUS_SETTLE_DEADLINE_MS] (#2017) so no
 *   call site has to pick — or drift — its own contention headroom. The loop
 *   returns the instant [condition] holds (no slowdown on a healthy run) and
 *   returns `false` the moment the deadline passes.
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
 * ## Clock-advancing drains ARE allowed — through [onTick] (revised by #2017)
 * The "never touch a clock" invariant above is a property of THIS FUNCTION'S
 * BODY, not a ban on callers. A call site whose genuine per-tick drain is
 * `advanceUntilIdle()` (bridging the virtual clock to a real
 * `Dispatchers.IO` continuation) passes it as [onTick] — that is precisely what
 * the injected drain exists for, and the helper still advances nothing itself.
 * #1048 originally read the invariant as a carve-out and left
 * `TmuxSessionWarmOpenTest.pumpUntil` (and its copy in
 * `Issue1574DeadReconnectTest`) hand-rolled; both then carried a 5 s real-time
 * budget that reds only under contention (#2017). They are migrated.
 *
 * The one pump that genuinely cannot use this helper is
 * `PromptComposerOutboundSendQueueViewModelTest.advanceSchedulerUntil`: its
 * predicate is `suspend` (incompatible with the plain `() -> Boolean`
 * [condition]) and each tick re-checks that predicate FOUR times between
 * distinct clock/dispatcher nudges, so its loop body is load-bearing rather than
 * a drain. It stays separate, by design, with its own generous 40 s budget.
 *
 * @param deadlineMs generous wall-clock budget; the pump returns `false` once it
 *   elapses without [condition] holding. Defaults to the ONE audited
 *   [GENEROUS_SETTLE_DEADLINE_MS] — prefer the default over a per-call constant.
 * @param sleepMs real-wall-clock yield per tick so off-Main / real-IO threads
 *   get scheduling time before the next drain.
 * @param onTick the per-tick drain (see above); defaults to a no-op pure poll.
 * @param condition the load-bearing exit predicate.
 * @return `true` if [condition] held within [deadlineMs]; `false` on timeout.
 */
fun drainMainLooperUntil(
    deadlineMs: Long = GENEROUS_SETTLE_DEADLINE_MS,
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
