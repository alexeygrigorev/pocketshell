# Connection stability + black-screen — campaign findings (2026-06-20)

Consolidated record of the connection/black-screen stress-test campaign
(epic [#843]) and the review-rigor meta-audit ([#844]). The maintainer reported
the app still feels unstable (connection resets, stuck reconnect, black screens)
and asked for an exhaustive diagnosis + fix-it-once-and-for-all pass. This is the
durable synthesis; per-lane detail lives in the cited issue comments.

## TL;DR

1. **You are dogfooding a build that predates the fixes.** The canonical "still
   unstable" symptom (#822, stable-Wi-Fi reconnect wedge) is **already fixed on
   `main` but was unreleased** — fixes landed in `98a3dd21` (#792 slice C),
   `0fbfc9cb` (slice D liveness probe), `d03dd79f` (#833 sustained-outage
   re-dial), `d63b6a63` (slice E single active path), plus a durable test this
   session. → **A release is the immediate unlock.** (Lane L1)
2. **The remaining real instability is structural: the two-reducer seam (#766).**
   The new `ConnectionController` reducer is sound in isolation, but the old
   inline `reduceConnection` in `TmuxSessionViewModel` still *decides* what runs
   on every lifecycle edge, feeding the controller in parallel. Every CRITICAL
   latent bug lives at that seam. The cure is **finishing #766** (collapse to one
   decision-maker), not point-patches. (Lane L2)
3. **"Black screens" = 9 separable causes, mostly fixed or inherent.** The
   inherent ones (idle agent alt-screen void, #807) are cured by defaulting
   agents to the Conversation view (#818, merged), not by render patches. Real
   remaining render bugs: **#717, #651, #819**. (Lane L3)
4. **Approvals leak because rigor is reopen-only.** Bugs get APPROVED on a proxy
   as *first* fixes and only become reopens after shipping. Fixed by the new
   universal gates **D32 / G1–G6** (this session). (#844)

## Lane L1 — issue archaeology ([#843] comment)

Every connection/black-screen issue ever reported, grouped:
- **Reconnect/wedge:** #822 (FIXED-unreleased), #635 bg→fg, #833 sustained outage
  (fixed), #823 manual reconnect affordance (open).
- **Black/blank:** #794 flap (fixed `ca0b4b11`), #553 reseed (fixed), #721
  reattach-repaint (fixed), #807 tall-grid (inherent), #717 voice-send (open).
- **Stale-session/switch:** #686/#658/#636 (rewrite epics), largely addressed.
- **Terminal↔Conversation mismatch:** #819 (reopened — detection-layer, not
  connection), #820 (open).
- **Lease/transport:** covered by #687/#792.

Key: do not re-root-cause #822 against a pre-fix build; verify `main` fresh.

## Lane L2 — connection-core race & failure-mode audit ([#843] comment)

The pure `ConnectionController` reducer is sound; **all serious latent
instability is at the controller↔inline-VM seam (#766 half-migration)** — two
state machines on the same edges. 3 CRITICAL / 4 HIGH / 5 MED:

- **C1** — the `TmuxClient.disconnected` true-edge has two consumers (driver→
  controller ladder + inline `disconnectedJob`→`handlePassiveClientDisconnect`)
  with non-matching gates → terminal `Reconnecting` wedge (the #822 class via the
  skip-nav branch). `ConnectionEffectDriver.kt:231-256` + `TmuxSessionViewModel.kt:5748-5797`.
- **C2** — `Reconnecting`/`Reattaching` have no self-driven exit; attempt counter
  driven by two sources → status band disagrees with the reveal surface (stuck
  band over a terminal error → black). `ConnectionStatusProjection` exists to
  paper over this.
- **C3** — liveness-probe drop handler double-submits `TransportDropped` then the
  inline ladder re-fires per attempt → controller over-counts to `Unreachable` on
  a channel that actually recovered (spurious terminal error — the very bug the
  probe was added to fix). Violates single-reconnect-writer (D28).
- HIGH: `setConnectionState→driveControllerIntent` is the two-reducer engine
  (#766 root); synthetic `inline-reveal` SeedLanded promotes green over a blank
  pane (#717/#553 class); racy `isWarm` within-grace (#635 class); `markGone`
  doesn't cancel the inline ladder → zombie session (#666).

**Recommendation:** do not point-patch the seam — finish #766 as the clean cut
(umbrella for C1/C2/H1), with focused children for C3, H2 (#717), etc.

## Lane L3 — black/blank-screen taxonomy ([#843] comment)

9 separable causes:
- **Fixed on `main`:** #794 (11s flap, `ca0b4b11`), #553 (reseed-blank), #721
  (reattach-no-repaint), + a structural never-reveal-black gate in
  `RevealStateMachine` (empty seeds never reveal).
- **Inherent, not render-fixable:** #807 "partly black" + "idle agent is black" —
  an agent alt-screen TUI genuinely leaves ~48 of 56 phone-grid rows empty;
  painting them black is correct. Cure = **#818 (default agents to Conversation,
  merged)**. The "recurs every release" feeling was *masking churn* (#815
  auto-switch added then reverted), not the same bug returning.
- **Real, still-unfixed:** **#717** (HIGH — same-grid voice-send heal gap:
  early-return at `TmuxSessionViewModel.kt:10901` before the heal; the journey
  test bypasses the gate via a seam — a #657-class vacuous proxy), **#651** (MED —
  garbled at wrong size, no auto-recover), **#819** (MED — wrong conversation
  source, higher priority now Conversation is default).

## Lane L4 — lease / transport / liveness audit ([#843] comment)

7 findings:
- **S1 (HIGH)** — no proactive liveness detector while stuck in `Reconnecting`/
  `Reattaching` or right after a falsely-promoted attempt; falls back to ~60s
  sshj keepalive.
- **S2 (HIGH)** — a bare network LOSS (airplane / Wi-Fi-off / dead-zone, and
  same-SSID sleep/wake) emits NO signal because `TerminalNetworkChangeDetector`
  returns null on `NoValidatedNetwork`. Major "feels slow to recover" cause.
- **S3 (MED-HIGH)** — 2–3 competing grace owners coexist (controller 60s vs inline
  `passiveDisconnectGraceMs` vs `pausedAutoReconnect`) — the #766 tail; the #635
  dual-clock seam persists.
- **S4 (MED)** — idle-TTL reaper closes the warm lease on background → `isWarm`
  false on quick foreground → visible reconnect instead of silent reattach.
- **S5 (MED)** — probe ~10s post-attach blind spot; real worst-case detection
  ~26s. **S6 (MED)** — reconnect ladder has no aggregate wall-clock ceiling
  (~2min worst), manual Reconnect skipped while auto ladder active. **S7
  (LOW-MED)** — teardown correctness is convention-dependent, latent #822 risk.

## Lanes L5 / L6 — end-to-end adverse-condition stress (PENDING)

Fresh-build emulator+Docker stress (link cuts #552, bg/fg, rapid switching,
network on/off, long holds, voice-during-drop, reconnect storms, multi-session).
**These quantify how often C1–C3 / S1–S7 actually bite** → decides
ship-now-then-harden vs fix-then-ship. Results will be appended here.

## Review-rigor meta-audit ([#844]) → locked decision D32

Approvals leaked via 4 paths: red→green mandatory only for *known* reopens;
JVM-proxy accepted when on-device was the property; vacuous passes
(`Tests 0/1 completed` read as green, #635); no pre-merge CI-green (#816).
**Adopted (G1–G6, see `process.md` D32 + `.claude/agents/reviewer.md`):**
reviewer-run red→green for all defect fixes; class-coverage; ban 0-test passes;
**BLOCKED** verdict (no JVM-only for user-facing); captured-proof-for-flake;
wrong-cost guard. **Pending maintainer sign-off:** G7 (pre-merge CI / #816), G8
(second adversarial reviewer for connection/conversation/render).

## Prioritized fix plan

1. **Release `main` now** (ship-now-then-harden, maintainer-chosen) — delivers
   the already-landed connection slices + this session's 12 merges.
2. **Finish #766** (the cure for C1/C2/C3 + S3): Slice 1 migrate inline gate-sites
   (note the #685 non-byte-identical predicate trap — map to the inline-equivalent
   predicate, not the divergent display status); Slice 2 migrate `reduceConnection`
   (kills C1/C2/C3, highest-risk, full journey gate + per-event red→green); Slice 3
   delete the inline mirror. Gated on maintainer on-device sign-off (#766 DoD).
3. **S2 / S1** (network-loss signal + proactive liveness) — M each, high "feels
   unstable" payoff.
4. **#717** (voice-send heal gap — root-caused), **#651**, **#819** (structural
   identity fix, completes #821).

[#843]: https://github.com/alexeygrigorev/pocketshell/issues/843
[#844]: https://github.com/alexeygrigorev/pocketshell/issues/844
