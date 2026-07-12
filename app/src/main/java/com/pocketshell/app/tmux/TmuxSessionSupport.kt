package com.pocketshell.app.tmux

import com.pocketshell.app.session.reconcileAgentEvents
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.connection.LivenessProbe
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.terminal.input.BracketedPaste
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin

internal fun boundedDistinctEvents(events: List<ConversationEvent>): List<ConversationEvent> =
    reconcileAgentEvents(events, maxEvents = MaxAgentEvents)

internal data class PrewarmedPaneRuntime(
    val panes: List<TmuxPaneState>,
    val paneRows: Map<String, TmuxPaneState>,
    val paneProducerJobs: Map<String, Job>,
    val paneInputQueues: Map<String, TmuxPaneInputQueue>,
    val paneInputJobs: Map<String, Job>,
    // Issue #1206: background seed-recovery jobs (bounded capture retry + one
    // deferred reseed on first live %output) for empty/wedged-capture panes.
    val paneSeedRecoveryJobs: Map<String, Job> = emptyMap(),
)

internal suspend fun PrewarmedPaneRuntime.closePartialPrewarm() {
    // Issue #1206: cancel background seed recovery first so no retry/deferred
    // reseed touches a pane whose producer we are tearing down.
    paneSeedRecoveryJobs.values.forEach { it.cancel() }
    paneProducerJobs.values.forEach { it.cancelAndJoin() }
    paneInputJobs.values.forEach { it.cancelAndJoin() }
    paneInputQueues.values.forEach { it.close() }
    panes.forEach { pane ->
        runCatching { pane.terminalState.detachExternalProducer() }
    }
}

/**
 * Issue #640: scrollback budget for the seed capture. The seed replays up to
 * this many lines of history so the freshly-attached emulator matches tmux's
 * current frame plus a little scrollback; widening it would only make the first
 * paint slower (per the #640 diagnosis), so it stays at the prior value.
 */
internal const val SEED_SCROLLBACK_LINES: Int = 200

/**
 * Issue #926: the SHORT ceiling (ms) for each attach/switch/reattach seed
 * `capture-pane` round-trip. The full per-command tmux ceiling is 10 s
 * (`DEFAULT_COMMAND_TIMEOUT_MS`); a seed against a wedged-but-alive `-CC`
 * channel parked there would be the user-visible freeze (#895). Bounding the
 * seed to ≈2.5 s means a degraded link surfaces a fast best-effort failure and
 * the caller falls through to the blank watchdog on the still-live transport
 * (the watchdog keeps re-seeding under a calm overlay) instead of a long stall.
 * Chosen ~2.5 s: long enough that a momentarily-busy healthy channel still
 * lands the snapshot, short enough that a wedge never reads as a freeze.
 */
internal const val SEED_CAPTURE_TIMEOUT_MS: Long = 2_500L

/**
 * Issue #1206: how many times a prewarmed pane whose FIRST seed `capture-pane`
 * came back empty/error/timeout retries the capture in the background before it
 * falls back to a deferred reseed on the first live %output. Bounded so a
 * genuinely-empty (brand-new, nothing-drawn-yet) pane isn't captured forever,
 * but generous enough to ride out a Claude startup flood wedging the shared
 * `-CC` capture acquire.
 */
internal const val PREWARM_SEED_RETRY_ATTEMPTS: Int = 3

/**
 * Issue #1206: backoff (ms) between prewarm seed-recovery capture retries. Three
 * attempts at ≈1.6 s spacing covers the ≈5 s window a startup flood typically
 * takes to drain enough for the capture acquire to succeed. The retry runs in
 * the background on `bridgeScope`, so this spacing never blocks the prewarm loop
 * or the UI thread.
 */
internal const val PREWARM_SEED_RETRY_BACKOFF_MS: Long = 1_600L

/**
 * Issue #662: how long the post-reveal black-pane safety net waits before
 * deciding a visible pane is genuinely blank and re-seeding it. The wait lets
 * the first post-reveal Compose layout report the phone grid and the
 * control-client resize round-trip settle, so the re-seed captures tmux's
 * REFLOWED frame rather than the pre-resize one (and so a pane that paints from
 * a late `%output` within this window is never needlessly re-captured). Kept
 * short so a genuinely-black pane heals within a blink on a live connection.
 */
internal const val BLANK_PANE_RESEED_DELAY_MS: Long = 350L

/**
 * EPIC #687 P1: a non-empty sentinel frame used to promote the reveal machine to
 * [com.pocketshell.core.connection.RevealState.Live] at the inline
 * "active pane revealed" moments (warm-cache / fast-switch reveal) where no fresh
 * `capture-pane` re-fires. The screen renders the VM's own panes, so the frame
 * content is irrelevant — only the non-emptiness (which flips the reveal gate from
 * Hold to Reveal for the current target) matters.
 */
internal const val REVEAL_LIVE_SENTINEL_FRAME: String = "reveal-live"

/**
 * Issue #693/#662: how many times [healActivePaneIfStaleRender] re-issues a
 * `capture-pane` when it comes back empty/error/null on a flaky link before
 * giving up for this pass. A transiently-empty capture on a degraded-but-
 * connected channel is the root of the green-dot-but-black-pane symptom; a few
 * cheap retries land the frame the next time the link recovers. The bound keeps
 * a genuinely-empty pane (a truly blank shell) from looping. Total seed attempts
 * = this value (the first attempt + this-minus-one retries).
 */
internal const val SEED_CAPTURE_EMPTY_RETRY_ATTEMPTS: Int = 4

/**
 * Issue #693/#662: backoff between empty-capture re-tries inside
 * [healActivePaneIfStaleRender]. Short so a flaky-link empty capture re-tries promptly
 * without stalling a genuinely-empty pane's reveal.
 */
internal const val SEED_CAPTURE_EMPTY_RETRY_DELAY_MS: Long = 120L

/**
 * Issue #1205: how many times a pane may be auto-recovered from a delivery
 * backlog / seed-gate overflow (reseed-and-reattach) inside
 * [OVERFLOW_RECOVERY_WINDOW_MS] before the recovery is abandoned and the pane
 * falls to the actionable `surfaceError` card as a last resort. Bounds a
 * saturated channel so a burst that keeps overflowing after each reseed cannot
 * loop into a reseed storm. Two attempts, then the card.
 */
internal const val OVERFLOW_RECOVERY_MAX_ATTEMPTS: Int = 2

/**
 * Issue #1205: sliding window over which [OVERFLOW_RECOVERY_MAX_ATTEMPTS] is
 * counted. A single transient burst costs one reseed; only a pane that keeps
 * overflowing inside this window exhausts the budget and lands on the card.
 */
internal const val OVERFLOW_RECOVERY_WINDOW_MS: Long = 60_000L

/**
 * Issue #989: the user-visible message shown when the manual Redraw kebab item is
 * tapped but Redraw cannot act — there is no live tmux client (dropped /
 * reconnecting), the client is disconnected, or there is no active target. Surfaced
 * as a Toast so Redraw never silently no-ops (the maintainer's "I clicked it three
 * times and nothing happened" report).
 */
internal const val REDRAW_UNAVAILABLE_MESSAGE: String = "Can't redraw — reconnecting…"

/**
 * Issue #693/#661: the never-reveal-black guard polls the active (visible) pane
 * for a non-blank seed before flipping to Connected. This is how many seed
 * retries it makes when the active pane is still blank at reveal time, so a
 * degraded switch shows the calm "Attaching…" loading state instead of a black
 * Connected pane.
 */
internal const val ACTIVE_PANE_REVEAL_SEED_ATTEMPTS: Int = 5

/**
 * Issue #1175 — the exportable `black_frame_observed` diagnostic event name and its
 * `class` discriminator values. The event fingerprints every class of degenerate
 * (black / partial-black) frame into the JSONL ring the Settings → Diagnostics
 * "Share log" export reads, riding the existing gated stale-render watchdog / reveal-
 * gate accounting (NO new poll). Exposed for the JVM unit tests that drive each class.
 */
internal const val BLACK_FRAME_OBSERVED_EVENT: String = "black_frame_observed"

/**
 * Issue #1294 — the three-state result of the stale-render heal oracle
 * ([TmuxSessionViewModel.healActivePaneIfStaleRender]). It replaces the old boolean that
 * conflated a capture FAILURE with a confirmed-healthy tick, which let the watchdog score
 * consecutive capture failures identically to consecutive healthy ticks and back off to 16s
 * exactly while the pane was black and the shared `-CC` capture mutex was wedged by a Claude
 * burst. Only [Healthy] earns the #1219 back-off; [Unverified] keeps the hot cadence.
 */
internal enum class HealOutcome {
    /** A real divergence was found and the model grid was re-seeded from tmux. Resets back-off. */
    Healed,

    /** The authoritative `capture-pane` CONFIRMED the render matches tmux. The ONLY outcome that backs off. */
    Healthy,

    /**
     * The `capture-pane` round-trip could NOT confirm the render's health: it timed out,
     * errored, came back empty on a live transport, or was starved out by a concurrent burst
     * holding the shared capture mutex. Keeps the HOT cadence — never throttles — so a black
     * pane whose heal captures are wedged keeps retrying at 4s instead of backing off to 16s.
     */
    Unverified,
}

/** Server-side black: capture-pane also empty/errored while the render is degenerate. */
internal const val BLACK_FRAME_CLASS_CAPTURE_EMPTY: String = "capture_empty"

/** No seed has ever landed for this pane (an empty capture with no prior seed stamp). */
internal const val BLACK_FRAME_CLASS_NEVER_SEEDED: String = "never_seeded"

/** Was painted, then the visible frame went fully degenerate while tmux still holds it. */
internal const val BLACK_FRAME_CLASS_LOST_AFTER_PAINT: String = "lost_after_paint"

/** The reveal gate exhausted its bounded reseed retries and the pane is STILL blank. */
internal const val BLACK_FRAME_CLASS_REVEAL_GATE_GAVE_UP: String = "reveal_gate_gave_up_still_blank"

/** Some live content survives but the majority of the viewport is black (partial/half-black). */
internal const val BLACK_FRAME_CLASS_PARTIAL_BLANK: String = "partial_blank"

/**
 * Issue #1192 — the SIXTH class: the MODEL grid matches tmux (so the heal oracle,
 * comparing model-vs-tmux, calls the pane HEALTHY) but the on-screen SURFACE is
 * confirmed black. Un-catchable by the oracle BY CONSTRUCTION (a surface-only black
 * never diverges from tmux), so it emits none of the five classes above. Fingerprinted
 * from the paint-confirmation seam ([TerminalSurfaceState.surfaceIsBlackWhileModelHasContent])
 * at the oracle's model-healthy short-circuit. DIAGNOSTICS ONLY (no reseed — #721
 * self-heals every known surface-blank trigger; this is the safety-net for an UNKNOWN one).
 */
internal const val BLACK_FRAME_CLASS_SURFACE_BLACK_MODEL_INTACT: String = "surface_black_model_intact"

/**
 * Issue #1443 — the pixel-truth class. Distinct from [BLACK_FRAME_CLASS_SURFACE_BLACK_MODEL_INTACT]:
 * that #1192 class is still MODEL-derived (the paint-confirmation seam reports
 * `paintedEmulatorContent` from `hasNonBlankVisibleRow()`, an emulator-model check with NO
 * pixel readback — the explicit #1296 non-goal). A GENUINE pixel/GPU-layer black — the
 * composited surface is black while the model still carries the frame (a lost HWUI hardware
 * layer, a RenderNode that stopped compositing, a Compose layer that dropped the child View's
 * buffer) — reads HEALTHY on every model-derived detector, so it is un-detected, un-healed,
 * AND un-fingerprinted (spike #874, the last code-confirmed blind spot). This class is
 * emitted ONLY when a bounded, rate-limited `PixelCopy` sample
 * ([TerminalSurfaceState.probePixelBlackWhileModelHasContent], off the render path) confirms
 * the surface pixels are (near-)uniformly black while the model carries content. DIAGNOSTICS
 * ONLY (maintainer decision 2026-07-10): it wires NO heal — if it fires in the wild the heal
 * decision is deferred to #1353 with the captured data; if it never fires it exonerates
 * pixel-black. Kept a SEPARATE class so the two are attributable and never conflated.
 */
internal const val BLACK_FRAME_CLASS_PIXEL_BLACK_MODEL_HAS_CONTENT: String = "pixel_black_model_has_content"

/**
 * Issue #1294 — a watchdog heal tick whose authoritative `capture-pane` could NOT confirm the
 * render (a [HealOutcome.Unverified] outcome: timeout / error / empty-on-live-transport /
 * mutex-starved). Carries an `unverifiedStreak` field (the run of consecutive such ticks) so a
 * shared-log export distinguishes a genuinely-idle pane that BACKED OFF (watchdog throttled)
 * from a pane whose heal captures are WEDGED while it is black (watchdog BLIND) — the exact
 * #1294 mechanism. Rides the watchdog tick that already paid the capture; no new poll.
 */
internal const val BLACK_FRAME_CLASS_HEAL_CAPTURE_UNVERIFIED: String = "heal_capture_unverified"

/**
 * Issue #1295 — the POSITIVE watchdog-liveness heartbeat. Emitted on each ARMED,
 * foregrounded, visible-pane steady stale-render watchdog tick over a live-attached
 * runtime (fields: `paneId`, `windowId`, `session`, `generation` + `clientHash` = the
 * runtime identity, `atMs` = monotonic timestamp, `tick`, `foreground`, `screenOn`,
 * `backedOff`). It is the diagnostics PREREQUISITE for #1295: every `black_frame_observed`
 * class is emitted only from INSIDE a watchdog tick / the reveal gate, so when the steady
 * watchdog is UNARMED (the #1295 bug) the export contains ZERO events — indistinguishable
 * from recording-off / ring-eviction / backgrounded. The ABSENCE of this heartbeat while an
 * export otherwise shows a foregrounded visible pane on a live-attached runtime is the
 * POSITIVE signature that convicts the unarmed-watchdog state. Rides the tick that already
 * runs; no new poll/timer/round-trip (it fires BEFORE the tick's capture, so it is present
 * even when the capture itself is UNVERIFIED). Redacted like the other terminal events (only
 * `session` is host-identifying, as it already is on `stale_render_heal`).
 */
internal const val WATCHDOG_LIVENESS_EVENT: String = "watchdog_liveness"

/**
 * Issue #693/#661: backoff between active-pane reveal-gate seed retries.
 */
internal const val ACTIVE_PANE_REVEAL_SEED_DELAY_MS: Long = 150L

/**
 * Issue #693: the never-black-while-connected watchdog re-checks the visible
 * pane this often after a reveal that had to fall through with a still-blank
 * active pane (e.g. the link stayed degraded past the reveal gate). Each tick
 * re-seeds any blank visible pane; once a frame lands the loading overlay is
 * cleared. Bounded by [CONNECTED_BLANK_WATCHDOG_MAX_TICKS].
 */
internal const val CONNECTED_BLANK_WATCHDOG_TICK_MS: Long = 500L

/**
 * Issue #693: upper bound on the never-black-while-connected watchdog ticks so
 * a genuinely-dead channel does not spin forever; the auto-reconnect path takes
 * over once the channel actually drops.
 */
internal const val CONNECTED_BLANK_WATCHDOG_MAX_TICKS: Int = 20

/**
 * Issue #966/#967: the steady-state stale-render watchdog re-checks the active
 * visible pane this often. SLOW relative to the blank watchdog (which spins fast
 * to clear a black reveal): a steady-state stale render is a rarer, residual
 * failure mode, and each tick costs one `capture-pane` round-trip, so a calm
 * cadence keeps the cost negligible while still healing a black-with-fragments
 * pane within a few seconds.
 */
internal const val STALE_RENDER_WATCHDOG_TICK_MS: Long = 4_000L

/**
 * Issue #1166 (battery/heat) + issue #1301 (reconciler cool ceiling): the continuous
 * full-frame reconciler's interval ceiling. A verified-clean (HEALTHY) foreground pane
 * cools 4s → 8s → 16s → 30s (each consecutive HEALTHY tick doubles the interval, capped
 * here) so an idle pane stops paying ~15 `capture-pane` round-trips/min; any divergence /
 * new streamed output / switch / UNVERIFIED tick snaps the cadence back to
 * [STALE_RENDER_WATCHDOG_TICK_MS] so a churning/agent/suspect pane reconciles as fast as
 * before.
 *
 * Issue #1301: raised 16s → 30s (with [STALE_RENDER_WATCHDOG_BACKOFF_MAX_DOUBLINGS] 2 → 3)
 * so a fully-idle HEALTHY pane's steady-state capture rate drops from one/16s (~3.75/min,
 * the v0.4.23 baseline) to one/30s (~2/min) — a ~47% reduction that is the #1164 battery
 * answer to promoting the event-only heal into a continuous reconciler. Per #1294 the
 * ceiling is reached ONLY by consecutive CONFIRMED-healthy ticks; a FAILED (UNVERIFIED)
 * verification never advances the back-off, so a wedged black pane can never cool to 30s.
 */
internal const val STALE_RENDER_WATCHDOG_MAX_INTERVAL_MS: Long = 30_000L

/**
 * Issue #1166 + #1301: the number of interval doublings from [STALE_RENDER_WATCHDOG_TICK_MS]
 * to reach [STALE_RENDER_WATCHDOG_MAX_INTERVAL_MS] (4s → 8s → 16s → 30s = 3 doublings, the
 * fourth doubling 4s<<3 = 32s coerced down to the 30s ceiling). Reached after 3 consecutive
 * HEALTHY ticks.
 */
internal const val STALE_RENDER_WATCHDOG_BACKOFF_MAX_DOUBLINGS: Int = 3

/**
 * Issue #966/#967: upper bound on stale-render watchdog ticks for the runtime's
 * lifetime. At [STALE_RENDER_WATCHDOG_TICK_MS] this covers a long dogfood
 * session; a superseded runtime (switch / reconnect) re-arms a fresh watchdog,
 * so this bound is a safety ceiling, not a hard session limit.
 */
internal const val STALE_RENDER_WATCHDOG_MAX_TICKS: Int = 10_000

/**
 * Issue #1494 — the per-tick `withTimeout` the stale-render watchdog wraps its heal call in, so
 * ONE hung capture can never freeze the watchdog LOOP (its own supervisor). Set slightly above
 * the cooperative `capture-pane` ceiling ([SEED_CAPTURE_TIMEOUT_MS] = 2.5 s) so a healthy or
 * slow-but-alive heal always completes within it, while a heal wedged on an uninterruptible
 * exec-lane read is abandoned (scored UNVERIFIED) at this bound and the loop advances to the
 * next tick. This is the LOOP-liveness half of #1494; the per-pane single-flight force-reset
 * ([RenderHealCoordinator.HELD_TOO_LONG_MS]) is the future-heal half.
 */
internal const val RENDER_HEAL_WATCHDOG_TICK_TIMEOUT_MS: Long = 4_000L

/**
 * Issue #941 (black-screen B1): after a send's submit Enter, wait this long for
 * the agent's `%output` overpaint to land before judging whether the active pane
 * settled partial-black/blank and needs the one-shot send-overpaint heal. Short
 * enough that the heal restores a black pane promptly, long enough that a normal
 * multi-line agent response has painted (so the heal correctly no-ops).
 */
internal const val SEND_OVERPAINT_HEAL_SETTLE_MS: Long = 350L

/**
 * Issue #1153: the post-send overpaint heal re-checks the active pane this many times, once
 * per [SEND_OVERPAINT_HEAL_SETTLE_MS] settle tick. The pre-#1153 heal was a SINGLE fixed
 * 350 ms one-shot, which lost the race against the bigger with-attachment (multi-line paste)
 * agent redraw — the pane could still be mid-clear at 350 ms, or the redraw could land AFTER
 * it. A short bounded poll (≈1.4 s over 4 ticks) catches a late/large redraw and re-heals a
 * re-overpaint after an early heal, while a dense normally-painted response pays only the cheap
 * local pre-check each tick (no capture). Bounded so a send never leaves a lingering poll.
 */
internal const val SEND_OVERPAINT_HEAL_MAX_TICKS: Int = 4

/**
 * Issue #1353 slice R4 — the EVENT-SUBMISSION reason for the render-heal reconciler
 * ([TmuxSessionViewModel.requestReconcile]). A trigger submits an immediate hot reconcile via a
 * reason instead of owning its own bespoke poll loop; the reconcile runs through the single
 * chokepoint [TmuxSessionViewModel.healActivePaneIfStaleRender] (with the R3 per-pane
 * single-flight). Each reason carries the settle cadence appropriate to it, so cadence is a
 * property of the event, not of six independent timers.
 *
 * This slice migrates ONLY [Send] (the #941/#1153 post-send agent-overpaint heal, formerly the
 * private `scheduleSendOverpaintHeal` poll). The rest of the spike's reasons
 * (Reveal|Reattach|Foreground|NetworkRestore|SeedLanded|StaleTick|SurfaceBlack) fold into this
 * entry in later slices (R5); they are intentionally NOT added yet to keep this slice's blast
 * radius scoped to the send path.
 */
internal enum class ReconcileReason(
    /** How many bounded settle ticks the reconcile re-checks the active pane over. */
    val settleTicks: Int,
    /** Delay before each settle tick, so a late/large redraw is still caught. */
    val settleDelayMs: Long,
) {
    /**
     * Post-send agent `%output` overpaint (#941 black-screen B1 / #1153 half-black). After a
     * send's submit Enter the agent TUI can `clear`+redraw and leave the active pane
     * partial/half-black on a LIVE transport; this reconcile re-checks on a fast bounded cadence
     * (≈1.4 s over 4 ticks), scoped to the pane the send targeted, and heals only when the
     * render is materially less than tmux's authoritative grid.
     */
    Send(
        settleTicks = SEND_OVERPAINT_HEAL_MAX_TICKS,
        settleDelayMs = SEND_OVERPAINT_HEAL_SETTLE_MS,
    ),
}

internal const val MaxAgentEvents: Int = 500

/**
 * Issue #215: ceiling on the synchronous `detach-client` round-trip the
 * non-suspending [TmuxSessionViewModel] teardown path runs during
 * `onCleared()` and the test-replacement seam. Bound the activity
 * destroy on Main thread so a wedged transport cannot stall the user's
 * back-press / app-finish journey.
 *
 * 600ms is well above the sub-50ms tmux takes on a healthy localhost /
 * Docker server (the only target where the detach can actually land
 * cleanly) and small enough that a SIGKILL on the sshd worker — the
 * pathological worst case — adds only a perceptible-but-bounded
 * teardown pause rather than an apparent app freeze.
 */
internal const val SYNC_DETACH_TIMEOUT_MS: Long = 600L
internal const val CODEX_AGENT_SUBMIT_DELAY_MS: Long = 250L
// Issue #1316: the OUTER attach-reveal ceiling. Was 30 s — the maintainer's
// "it took forever to attach / wouldn't let me touch" felt-freeze while the
// `list-panes` reconcile head-of-line-blocked behind a busy sibling's `-CC`
// burst. With the reconcile now on the dedicated exec lane it returns in ms, so
// this bound only ever fires on a genuinely stuck attach; a much shorter
// ceiling turns that into a fast user-visible "Tap Reconnect to retry" escape
// (→ evict lease → fresh-transport runConnect) instead of a tens-of-seconds
// input-gated overlay. The reconcile itself is separately bounded by
// [RECONCILE_LIST_PANES_EXEC_TIMEOUT_MS].
internal const val ATTACH_PANES_READY_TIMEOUT_MS: Long = 12_000L
internal const val ATTACH_PANES_READY_RETRY_MS: Long = 100L

// Issue #1316: per-reconcile exec ceiling for the attach/switch/refresh
// `list-panes` on the dedicated exec lane. Healthy reconciles return in
// milliseconds; this is the safety bound so a genuinely wedged/half-open
// transport surfaces a `Failed` fast (→ retryable attach error) rather than
// parking the reveal. Well under the outer [ATTACH_PANES_READY_TIMEOUT_MS] so
// the reconcile-level escape fires first.
internal const val RECONCILE_LIST_PANES_EXEC_TIMEOUT_MS: Long = 6_000L

/**
 * Issue #552 / #685 (Bug A): a passive tmux reader EOF during a brief foreground
 * network starvation is not enough proof that the user should see a disconnect
 * band. Hold the visible Connected frame while we try a silent same-SSH control
 * client reattach. A sustained outage still falls through to the existing
 * auto-reconnect path after this bounded foreground-only window.
 *
 * #685 ROOT CAUSE of the "lots of reconnects on stable Wi-Fi" complaint: there
 * used to be THREE disagreeing grace clocks — the SSH lease's 60s idle TTL
 * ([SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS]), sshj's 15s×4 keepalive (=60s),
 * and a stray 8s VM grace here. The 8s VM clock tore the held Connected frame
 * down at 8s, so a sub-minute background (or a brief blip on otherwise-stable
 * Wi-Fi) surfaced a needless reconnect even though the lease/keepalive would
 * have held the same transport live for a full 60s. We collapse to ONE
 * lease-anchored 60s window: the VM grace now DEFERS to the same 60s the lease
 * owns, so a background→foreground (or a transient blip) within 60s reattaches
 * with ZERO reconnect, and the death verdict for anything longer is left to the
 * single 60s lease/keepalive oracle. Keep this value in lockstep with
 * [SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS] — do NOT reintroduce a divergent
 * shorter VM clock.
 */
internal val PASSIVE_DISCONNECT_GRACE_MS: Long = SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS
internal const val PASSIVE_DISCONNECT_SILENT_REATTACH_TIMEOUT_MS: Long = 5_000L
internal const val PASSIVE_DISCONNECT_SILENT_REATTACH_RETRY_MS: Long = 250L

/**
 * Issue #451: how long [TmuxSessionViewModel.stagePromptAttachments] waits
 * for the terminal SSH session to come back live before failing the
 * attachment upload. Attach connects-on-action like Send: when the file
 * picker round-trip outran the #450 grace window (or the OS killed the
 * socket) it kicks [TmuxSessionViewModel.reconnect] and waits here for the
 * tmux `-CC` re-attach — including the SSH handshake — to land. The
 * auto-reconnect backoff chain spans several seconds of delays alone, so the
 * bound covers a full re-dial plus handshake headroom. Bounded and
 * foreground-only — no background work.
 */
internal const val ATTACH_SESSION_WAIT_TIMEOUT_MS: Long = 30_000L
internal const val ATTACH_SESSION_WAIT_POLL_MS: Long = 100L
internal const val SEND_SESSION_WAIT_TIMEOUT_MS: Long = 30_000L
internal const val SEND_SESSION_WAIT_POLL_MS: Long = 100L
internal const val RUNTIME_HEALTH_PROBE_TIMEOUT_MS: Long = 750L

/**
 * Issue #1042 (cause #1): the small bound on the single control-channel probe the
 * liveness-first network-restore arm issues when the transport keepalive has aged
 * out. A surviving link answers well within this; a genuinely dead socket times out
 * and falls through to the #997 fresh-lease redial. Kept short so a dead socket's
 * recovery is not noticeably delayed past the old unconditional redial.
 */
internal const val RESTORE_LIVENESS_PROBE_BUDGET_MS: Long = 2_000L

/**
 * Classification of a foreground tmux control-channel health probe.
 *
 * Issue #635 / #636 (Slice 1): the within-grace foreground resume must
 * distinguish a transient probe [TIMEOUT] (slow/recovering link — ride it
 * through, no reconnect) from a confirmed-dead transport ([DISCONNECTED] /
 * [NOT_CONNECTED]) or an explicit protocol/IO [ERROR] (genuine failure —
 * reconnect). The [failReason] tag is emitted on the `tmux_probe_result`
 * reconnect-cause trail so field logs can tell case-2 (timeout on a
 * recovering link) apart from case-3 (genuinely dead).
 */
internal enum class RuntimeHealthVerdict(val failReason: String) {
    HEALTHY("none"),
    DISCONNECTED("disconnected"),
    NOT_CONNECTED("not_connected"),
    TIMEOUT("timeout"),
    ERROR("error"),
}
internal const val CACHED_RUNTIME_REMOTE_REFRESH_DELAY_MS: Long = 1L
internal const val TMUX_SESSION_PREWARM_MAX_TARGETS: Int = 2
internal const val LIST_PANES_FIELD_SEPARATOR: String = "|PS|"
internal const val TMUX_PASTE_BODY_CHUNK_BYTES: Int = BracketedPaste.BODY_CHUNK_BYTES

/**
 * Issue #145: logcat tag used by the disconnect observer + connect-attempt
 * counter. Kept short enough to satisfy `Log.isLoggable`'s 23-character
 * tag limit on older Android versions while still being grep-able from a
 * dumped logcat. The connected reconnect test searches for this tag and
 * counts `tmux-connect-attempt` lines to assert no reconnect-loop
 * thrash.
 */
internal const val ISSUE_145_RECONNECT_TAG: String = "PsTmuxReconnect"

internal const val ISSUE_548_NETWORK_TAG: String = "PsTmuxNetwork"

/**
 * Issue #576 (Slice A of #792): logcat tag for the layout-change coalescer
 * (Codex `%layout-change` burst → ~1 off-main reconcile per frame). Within the
 * 23-character `Log.isLoggable` cap.
 */
internal const val ISSUE_576_COALESCER_TAG: String = "PsTmuxCoalescer"

/**
 * Issue #896: logcat tag for the scope-level CoroutineExceptionHandler safety
 * net on the SSH/tmux close/EOF cascade. A teardown-race throw that lands here
 * is swallowed (process kept alive) + recorded as a non-fatal report; greppable
 * to confirm the net fired instead of a process death. Within the 23-char
 * `Log.isLoggable` cap.
 */
internal const val ISSUE_896_BRIDGE_SAFETY_NET_TAG: String = "PsTmuxBridgeSafety"

/**
 * EPIC #792 Slice D (#822/V7a): logcat tag for the proactive mid-session
 * liveness probe — the silent-drop detector. Greppable by the silent-drop
 * journey to correlate the probe's drop-declaration with the surfaced indicator.
 * Within the 23-character `Log.isLoggable` cap.
 */
internal const val LIVENESS_PROBE_TAG: String = "PsTmuxLiveness"

/**
 * EPIC #792 Slice D: test-only override for the [LivenessProbe]'s timing knobs,
 * the analogue of [com.pocketshell.app.BackgroundGraceTestOverride]. A connected
 * / emulator journey shortens the probe window deterministically WITHOUT
 * weakening any assertion or self-skipping — production keeps the
 * [LivenessProbe.DEFAULT_INTERVAL_MS] / DEFAULT_PER_PROBE_TIMEOUT_MS /
 * DEFAULT_FAILURE_THRESHOLD defaults. The override is read once when the VM
 * constructs its probe, so a proof sets it BEFORE launching the activity.
 */
internal object LivenessProbeTestOverride {
    @Volatile
    private var intervalMsOverride: Long? = null

    @Volatile
    private var perProbeTimeoutMsOverride: Long? = null

    @Volatile
    private var failureThresholdOverride: Int? = null

    /**
     * Whether a freshly-constructed VM auto-starts its probe loop. Production +
     * the connected emulator proof keep this TRUE (the loop runs on the real Main
     * looper). JVM unit tests set it FALSE: the probe's infinite periodic `delay`
     * loop on the virtual-clock Main would otherwise hang `runTest`'s
     * `advanceUntilIdle()`. Those tests drive the probe via the explicit VM seams
     * instead.
     */
    @Volatile
    var autoStartEnabled: Boolean = true

    fun setAutoStartEnabledForTest(enabled: Boolean) {
        autoStartEnabled = enabled
    }

    fun setForTest(
        intervalMs: Long?,
        perProbeTimeoutMs: Long?,
        failureThreshold: Int?,
    ) {
        require(intervalMs == null || intervalMs > 0) { "intervalMs must be > 0" }
        require(perProbeTimeoutMs == null || perProbeTimeoutMs > 0) {
            "perProbeTimeoutMs must be > 0"
        }
        require(failureThreshold == null || failureThreshold >= 1) {
            "failureThreshold must be >= 1"
        }
        intervalMsOverride = intervalMs
        perProbeTimeoutMsOverride = perProbeTimeoutMs
        failureThresholdOverride = failureThreshold
    }

    fun clear() {
        setForTest(null, null, null)
        autoStartEnabled = true
    }

    fun intervalMs(): Long = intervalMsOverride ?: LivenessProbe.DEFAULT_INTERVAL_MS

    fun perProbeTimeoutMs(): Long =
        perProbeTimeoutMsOverride ?: LivenessProbe.DEFAULT_PER_PROBE_TIMEOUT_MS

    fun failureThreshold(): Int =
        failureThresholdOverride ?: LivenessProbe.DEFAULT_FAILURE_THRESHOLD
}

/**
 * Issue #1206: test-only synthetic-injection seam (the #780 model) for the
 * prewarm seed path. A fresh Claude pane's FIRST `capture-pane` can come back
 * empty/wedged on a busy shared `-CC` channel even though the pane HAS content
 * — the exact non-happy state the happy real-agent workbench structurally
 * cannot enter, which is why AC4 must inject it synthetically to prove the pane
 * still lands on a PAINTED grid via the retry/deferred-reseed recovery.
 *
 * A connected journey arms [setForcedEmptyFirstCaptures] BEFORE launching the
 * activity; the production seed path calls [consumeForcedEmpty] once per
 * capture and, while budget remains, TREATS that capture as empty (after the
 * real wire round-trip). [consumedCount] lets the journey hard-assert the
 * injected fault was actually hit by the prewarm seed path (no vacuous pass),
 * and the analogue of [LivenessProbeTestOverride]. Production keeps it at 0.
 */
internal object PrewarmSeedFaultTestOverride {
    @Volatile
    private var forcedEmptyFirstCaptures: Int = 0

    /** Number of forced-empty captures actually consumed by the seed path. */
    @Volatile
    var consumedCount: Int = 0
        private set

    /**
     * Diagnostic: how many times the prewarm seed path entered
     * [captureAndApplyPrewarmSeed]. A connected journey reads this to tell
     * "prewarm never seeded the target" (0) apart from "prewarm seeded but the
     * seam wasn't armed / consumed" (>0, consumed 0).
     */
    @Volatile
    var seedAttemptCount: Int = 0
        private set

    /**
     * Arm the seam so the next [count] prewarm seed captures are treated as
     * empty (simulating a wedged/empty first `capture-pane`). Resets the
     * consumed + attempt counters so a journey can assert the injection landed.
     */
    fun setForcedEmptyFirstCaptures(count: Int) {
        require(count >= 0) { "count must be >= 0" }
        forcedEmptyFirstCaptures = count
        consumedCount = 0
        seedAttemptCount = 0
    }

    fun clear() {
        forcedEmptyFirstCaptures = 0
        consumedCount = 0
        seedAttemptCount = 0
    }

    /** Record that the prewarm seed path ran once (diagnostic counter). */
    @Synchronized
    fun onSeedAttempt() {
        seedAttemptCount += 1
    }

    /**
     * Consume one unit of forced-empty budget for [paneId]. Returns true (and
     * decrements the budget) when this capture must be treated as empty. The
     * [paneId] is accepted for future per-pane targeting and diagnostic logging;
     * the budget itself is process-global and consumed in call order.
     */
    @Synchronized
    fun consumeForcedEmpty(paneId: String): Boolean {
        if (forcedEmptyFirstCaptures <= 0) return false
        forcedEmptyFirstCaptures -= 1
        consumedCount += 1
        return true
    }
}

/**
 * Issue #235: logcat tag for the auto-detach-on-background +
 * reattach-on-foreground lifecycle journey, plus the manual Detach
 * button. Same 23-character `Log.isLoggable` cap as the other
 * ViewModel tags. The connected `TmuxDetachOnBackgroundE2eTest` greps
 * for this so the assertion path can correlate the lifecycle event the
 * test drives with the actual ViewModel detach landing.
 */
internal const val ISSUE_235_LIFECYCLE_TAG: String = "PsTmuxLifecycle"

/**
 * Issue #464: logcat tag for the confirmed kill-session path on the
 * per-session screen. Mirrors the dashboard's `issue168-kill` so a triage
 * of "killed session lingered in the tree" can correlate the kill with the
 * lifecycle signal that drops the folder-tree row. Under the 23-character
 * `Log.isLoggable` cap.
 */
internal const val ISSUE_464_KILL_TAG: String = "issue464-killsession"

internal val DEFAULT_AUTO_RECONNECT_DELAYS_MS: List<Long> = listOf(0L, 1_000L, 2_000L, 5_000L)

/**
 * Issue #621 / #634 / #636 (Slice 4): how many consecutive transparent
 * stale-lease re-dials an open/switch attach may trigger before concluding the
 * host is genuinely dead and surfacing the manual Reconnect affordance. A
 * silently-dead warm lease normally heals on the FIRST fresh transport, so a
 * small cap is enough; it exists only to stop a permanently-broken host from
 * looping forever (each fresh transport also EOFing).
 */
internal const val STALE_LEASE_AUTO_RECOVER_MAX: Int = 2

/**
 * Issue #1185: how many times the selected session may transparently re-dial its
 * OWN connect after its lease acquire was woken with a coalesced-connect cancel
 * (the create-then-switch supersede). A re-dial becomes a fresh pool owner and
 * connects on the first retry in the normal case; the cap only bounds a
 * pathological repeat so the honest terminal error + working Retry eventually
 * surfaces instead of an unbounded re-dial loop.
 */
internal const val COALESCED_CANCEL_REDIAL_MAX: Int = 2

/**
 * Issue #423: a terminal surface that fails this many times within
 * [SURFACE_RECOVERY_WINDOW_MS] is treated as a recovery storm rather than
 * a transient hiccup. At that point we stop silently re-attaching the
 * broken surface (which thrashes the emulator and looks like a freeze) and
 * flip the pane to an actionable error state with a "Recreate terminal"
 * control. Three transparent recoveries inside the window are tolerated;
 * the fourth trips the error state.
 */
internal const val SURFACE_RECOVERY_STORM_THRESHOLD: Int = 4

/** Sliding window for [SURFACE_RECOVERY_STORM_THRESHOLD]. */
internal const val SURFACE_RECOVERY_WINDOW_MS: Long = 4_000L

/**
 * Issue #554: how many consecutive null agent detections a remembered agent
 * window must produce before its optimistic seed is reconciled away. The first
 * null right after a reattach is almost always a detection-not-yet-warm race,
 * so we require a confirming re-detection before dropping the agent UI.
 */
internal const val AGENT_EXIT_CONFIRMATIONS: Int = 2

/** Delay before the issue #554 agent-exit confirmation re-detection. */
internal const val AGENT_EXIT_RECHECK_DELAY_MS: Long = 1_200L

/**
 * Issue #942 (black-screen B2): how recently a pane must have produced `%output`
 * for its channel to be treated as WEDGED-but-alive (still streaming) when an
 * empty grep detection (`Resolved(null)`) lands. Within this window an empty
 * detection is the capture-behind-a-busy-agent race (#470/#835), not an agent
 * exit, so it is NOT counted toward [AGENT_EXIT_CONFIRMATIONS]. Sized to comfortably
 * span a busy agent's brief inter-chunk gaps plus the detection round-trip, while
 * staying short enough that a genuinely-exited agent (which stops emitting output)
 * clears the window before its empty grep arrives, so a real exit still tears the
 * Conversation row down. Two consecutive recheck cycles
 * ([AGENT_EXIT_RECHECK_DELAY_MS]) fit inside it.
 */
internal const val CHANNEL_WEDGED_RECENT_OUTPUT_MS: Long = 3_000L

/**
 * Issue #793: how long the Conversation tab spins on "Loading conversation…"
 * before the load watchdog flips it to a clear Failed terminal state. Sized to
 * comfortably cover a normal first-paint tail read over SSH while still
 * bounding a never-completing read (transport flap / unavailable log) so the
 * tab can never hang indefinitely.
 */
internal const val CONVERSATION_LOAD_TIMEOUT_MS: Long = 12_000L

public enum class TmuxConnectTrigger(public val logValue: String) {
    UserTap("user-tap"),
    // Issue #1155 (Part B): a NORMAL tap of a PERSISTED session row in the folder
    // tree. Distinct from [UserTap] (which also covers create-new) so a genuine
    // cold open of an existing-but-maybe-gone session preflights `tmux has-session`
    // and, when the session is CONFIRMED GONE, drops to the tree with the "create a
    // new session in this folder?" prompt instead of silently resurrecting it as a
    // fresh shell via `new-session -A`.
    OpenExisting("open-existing"),
    LifecycleReattach("lifecycle-reattach"),
    ColdRestore("cold-restore"),
    FastSwitch("fast-switch"),
    Reconnect("reconnect"),
    AutoReconnect("auto-reconnect"),
    NetworkReconnect("network-reconnect"),
}

internal val TmuxConnectTrigger.isReconnectTrigger: Boolean
    get() = when (this) {
        TmuxConnectTrigger.Reconnect,
        TmuxConnectTrigger.AutoReconnect,
        TmuxConnectTrigger.NetworkReconnect,
        TmuxConnectTrigger.LifecycleReattach,
        -> true
        TmuxConnectTrigger.UserTap,
        TmuxConnectTrigger.OpenExisting,
        TmuxConnectTrigger.ColdRestore,
        TmuxConnectTrigger.FastSwitch,
        -> false
    }

public data class TmuxRestoreIntentSnapshot(
    val hostId: Long,
    val hostName: String,
    val hostname: String,
    val port: Int,
    val username: String,
    val keyPath: String,
    val sessionName: String,
    val startDirectory: String?,
    val tmuxSessionId: String? = null,
    val sessionCreated: Long? = null,
    val trigger: TmuxConnectTrigger,
    val generation: Long,
)

/**
 * Issue #145: process-wide monotonic counter of `connect()` calls that
 * actually progress to a new attempt (the idempotent early-returns do
 * not increment). The connected reconnect test snapshots this value
 * before and after the test body to assert exactly one reconnect attempt
 * per disconnect, anchored to the test's lifetime rather than to a
 * shared on-device logcat buffer that can roll over.
 *
 * The counter is internal because callers should never key behaviour
 * off it; it is purely a test signal.
 */
internal val TMUX_CONNECT_ATTEMPTS: AtomicInteger = AtomicInteger(0)

/**
 * Issue #178: process-wide monotonic counter of **SSH handshakes** the
 * ViewModel actually performed (i.e. trips through
 * [com.pocketshell.core.ssh.SshConnection.connect]). Distinct from
 * [TMUX_CONNECT_ATTEMPTS], which counts logical connect() invocations:
 * a same-host session switch increments TMUX_CONNECT_ATTEMPTS but does
 * NOT increment this counter because no new SSH handshake fires — the
 * existing transport is reused.
 *
 * The connected `TmuxSessionSwitchSameHostReusesSshE2eTest` snapshots
 * this value before the second attach and asserts the delta is zero,
 * which is the structural assertion behind acceptance criterion "Same-
 * host session switching does NOT open a new SSH socket".
 *
 * Internal because callers should never key behaviour off it; it is
 * purely a test signal.
 */
internal val SSH_HANDSHAKE_ATTEMPTS: AtomicInteger = AtomicInteger(0)
