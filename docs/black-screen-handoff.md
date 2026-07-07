# Black-screen elimination — handoff for the implementing agent

> **STATUS 2026-07-06: SHIPPED to `main`.** The full recovery layer + the
> continuous reconciler are merged: #1296, #1294, #1300, #1295, #1297, #1301,
> and the emulator-proven regression backstop #1302. The reconciler decision
> #1298 is implemented and closed. Any lost grid on a foregrounded pane now
> self-heals within one reconcile interval regardless of cause, at *lower* idle
> battery than v0.4.23. Campaign summary + honest boundary: issue #1208. The
> only remaining release gate is the nightly connection-core fault-verdict
> (#1288, a separate non-black-screen issue). The queue below is retained as the
> historical brief — every item in it is now DONE.

Written 2026-07-06 by the analysis orchestrator, on maintainer instruction:
the maintainer's #1 problem is persistent black terminal panes on Claude Code
sessions ("I can't work because of them"). This session's job was analysis +
documentation; the fixes were then implemented across #1294–#1302 (the
"different agent implements" plan below was superseded when the maintainer
directed this orchestrator to continue through implementation).

## TL;DR for the implementer

1. There is **no single regression commit**. Black screens are intrinsic to the
   day-one D5 architecture (tmux -CC, local emulator grid seeded once, patched
   only by incremental `%output`). Claude Code's incremental repaint style
   (idle it only touches the spinner line) makes ANY lost grid permanently
   black; full-repaint agents (Codex) mask the same loss. Stop hunting for the
   regression; build the missing guarantee instead.
2. All prior audit fixes (#1203–#1207, #1244) **shipped in v0.4.23 and work as
   scoped** — the remaining hole is the **recovery layer**: the steady heal
   watchdog can be unarmed, throttles itself on capture failure, gates the
   surface heal behind capture success, and (until #1296) could be silenced by
   a dishonest paint-confirmation seam.
3. The work queue, in order: **#1294 → #1295 → #1297 → #1298**, with #1296
   already merged (or in final merge when you start — check PR #1299). #1298
   (continuous authoritative full-frame reconciler) is the structural fix that
   ends the class; it is maintainer-approved, no further sign-off needed.
4. The maintainer's next diagnostics export decides emphasis (see
   "Diagnostics disambiguation" below). Don't block on it; the queue above is
   correct under every export outcome.

## Where the full evidence lives

Everything below is a summary. The complete evidence trail — with file:line
anchors, commit SHAs, event sequences, and falsifiable test ideas — is on
issue **#1208** as three research reports (2026-07-06) plus the consolidated
assessment comment:

- Report 1: regression archaeology (timeline; the `3893a976` lossless→drop
  trade; why #967 was right and only mitigated).
- Report 2: v0.4.23 fix-containment verification (what shipped, the fix table,
  the JSONL disambiguation table).
- Report 3: adversarial audit of the recovery layer (findings 1–4 behind
  issues #1294–#1297, plus what was cleared — #1286 drain-priority and the
  byte pipeline are NOT the bug).

The maintainer's incident screenshots are attached on #1203 (2026-07-03) and
#1208 (2026-07-06) via the `feedback-assets` release.

## The mental model

```
SSH read loop → ControlModeParser → TmuxClient.emitOutput
  → per-pane Channel(4096)/trySend  [drops on overflow since 3893a976]
  → MainThreadDrainScheduler (8ms/frame budget)
  → local emulator grid  [THE screen of record — never reconciled vs tmux]
  → TerminalView.onDraw  [black when its own mEmulator is null]
Recovery nets: one-shot seed (capture-pane) + reveal/switch heal (#1244)
  + steady stale-render watchdog + surface force-repaint (#1203)
  — ALL funnel through one capture-pane behind one per-host sendMutex
    with a 2.5s ceiling, which a Claude burst wedges. That funnel is the
    remaining defect cluster.
```

Two invariants every fix must preserve:

- **D21**: no background schedulers; everything foreground-lifecycle-gated.
- **#1164/#1219**: genuinely-healthy panes must keep backing off (battery).
  Only *unverified* (capture-failed) ticks may stay hot.

## The work queue

### #1294 — three-state heal oracle (centerpiece; was in flight at handoff)

`healActivePaneIfStaleRender` must return HEALED / HEALTHY / UNVERIFIED;
only HEALTHY increments the backoff counter; the surface-black heal must run
before/independent of the capture round-trip. Full spec + acceptance criteria
in the issue. **Check the issue's latest status comment first** — an
implementer run may have completed with review pending; finish its loop
rather than restarting.

### #1295 — watchdog arming/liveness (the never-filed audit gap)

Within-grace foreground reattach arms NO watchdog; superseded runtimes exit
their loop; disconnect-recovery can leave runtimes watchdog-less. Guarantee
exactly one live watchdog per active attached runtime + a liveness invariant
diagnostic. Sequenced after #1294 (same file: `TmuxSessionViewModel.kt`).

### #1297 — wedge-proof heal-capture lane + collector-gap fix

Give heal/reseed captures a lane a busy agent channel cannot wedge (sidecar
exec channel per the ack-gate precedent, reserved mutex slot, or leases), and
close the overflow-reseed `replay=0` collector gap. This is **connection-core
contract work**: run the Docker `:shared:core-ssh:integrationTest` suite
locally before merge (process.md rule), and mind D28 — if it turns into
architecture rework rather than a patch, flag the maintainer.

### #1298 — continuous authoritative full-frame reconciler (approved)

The structural fix: while foregrounded+attached, periodically capture-pane
the visible pane on the #1297 lane, diff vs the local grid, reseed + full
invalidate on divergence. Maintainer approved 2026-07-06 ("just resolve black
screens") — do not re-ask. A design-spike comment on #1298 covers: evolve the
watchdog vs new loop, the diff predicate, adaptive cadence vs #1164, capture
staleness races vs live deltas (capture-then-replay-tail), the test matrix,
and D22 deletions it unlocks. Read it before implementing; if it is missing,
re-derive from report 3 on #1208.

### Also in release scope

- **#1296 / PR #1299** (honest paint confirmation): MERGED (`15d0dffe`).
- **#1294 / PR #1303** (three-state heal watchdog): reviewer-APPROVED, in
  merge. Once it lands, #1295 and #1300 (line-hash oracle) become
  dispatchable — both edit the watchdog/oracle region #1294 owns.

### NOT a black-screen cause (ruled out 2026-07-06)

- **#1288** was investigated as suspect #3 (async-close seed failure →
  never-seeded black on reconnect). **Disproven at the code level** (see the
  #1288 comment 2026-07-06): `capture-pane` seeds ride the persistent `-CC`
  shell via `TmuxClient.captureWithCursor` and never call `ensureConnected()`,
  so the async-close predicate changes cannot black a pane that way. #1288
  remains a real fault-gate blocker for a green `fault_verdict`, but it is a
  separate D28 scoping call — **do NOT fold it into the black-screen patch, and
  do NOT open a seed-black issue on the async-close premise.** The real
  remaining on-device black-screen cause is the steady-watchdog gap **#1295**.

## Diagnostics disambiguation (ask the maintainer once per incident)

After a black screen: **Settings → Diagnostics → Share** (recording is on by
default). In the exported JSONL, during the black window:

| Signal | Meaning → emphasis |
|---|---|
| no `black_frame_observed` event at all | watchdog wasn't running → #1295 |
| `capture_empty` / `never_seeded` repeating | capture keeps failing on a live transport → #1297 / #1288 |
| `surface_black_model_intact` recurring | surface class → verify #1296/#1203 heals reach that runtime |
| `partial_blank` / `lost_after_paint` without `stale_render_heal` | oracle fired, heal died mid-flight |

Also check the `tmux_client_preregistration_output_drop` counter (#1204 bound
sizing).

## Process obligations (non-negotiable, see process.md)

- Every fix: **reproduce-first red→green on the real path** (D33/G10), class
  coverage not single-instance (G2), a test per acceptance criterion (G9),
  no `assumeTrue` self-skips on load-bearing assertions (F3/#780 model).
- This is the worst-reopen area in the project (#553→#662→#721→#879→#966→
  #1153→#1177→#1208…). The durable-fix gate (D31) applies to every issue in
  this doc: gate-wired class regression test or `CHANGES REQUESTED`, plus the
  adjacency sweep over the recently-closed siblings.
- Implementer/reviewer loop per issue; orchestrator merges via PR to protected
  `main`; one meaningful integration at a time.
- The fix that "makes the symptom disappear once" is not done. Done = the
  maintainer's exact scenario reproduced red, green with the fix, verified
  gone on the emulator+Docker journey.
