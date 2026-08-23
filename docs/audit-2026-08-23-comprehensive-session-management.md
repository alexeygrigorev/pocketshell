# Comprehensive session-management audit - 2026-08-23

## Scope and provenance

This is the consolidation of the requested session-tree audit and the broader
read-only code audits. It updates the 2026-08-20 audit in issue #2247 and the
session-tree audit in #2222 against work that has since landed.

The dedicated session-tree, host/data-contract, test/release-gate, and
connection-concurrency audits ran against clean `main` at
`67c5c05b7d4b5875a181113f4d213040aed3e480`. The replacement UI/journey audit
completed against `d9565a5665021459ef4acbd22835ff04988da057`.
The original connection/session-manager evidence remains in #1634; its
bounded-observation child has since been implemented, while its higher-risk
lifecycle findings remain relevant.

After the initial audits, `main` gained the #2272 alt-screen proof hardening in
commit `d9565a56`. At consolidation time, the Tests workflow for that exact HEAD
was still running. Therefore this report does not claim that the newest head is
green; it treats the previous run's four genuine RED shards as still-unresolved
baseline failures unless their owners have since closed them.

Validation performed by the auditors included focused JVM session-tree suites,
39 host tree tests, 37 daemon tests, 143 isolated real-tmux send tests, 13
usage-capture tests, and cheap CI/release-gate self-tests. Static findings were
not treated as runtime proof.

## Executive verdict

Session-tree correctness has materially improved. Exact tmux generations,
stale-prompt host targeting, conversation rebinding, process-restart restore,
and safe outbound queue disposal have landed. The remaining top defects are
behavioral and architectural rather than the old wholesale rebuild problem:

1. Warm same-host switching can still silently resurrect an externally killed
   session (#1634 finding F1).
2. Durable tree storage still lacks the stable identity, latest-wins writer,
   remote CAS/locking, and empty-snapshot guarantees specified by #2243.
3. Cold restore intermittently loses exact tmux generation before kill-signal
   invalidation (#2294).
4. Freshness is still pull-based and invisible: externally created/killed
   sessions and agent-state changes can remain stale for minutes, and the UI
   does not distinguish fresh data from stale data.

Outside the tree, the most important newly exposed defect is that non-durable
outbound tokens use a process-local counter while the host delivery journal is
durable and global. After app restart or across clients, a reused token such as
`d1` can cause a genuinely new payload to be suppressed as `already-delivered`.
This is distinct from the acknowledged-delivery work already tracked in
#2121/#2124 and must not be fixed by treating `already-delivered` as success.

Release readiness is also structurally weak: tag-triggered packaging can create
a public GitHub release before heavy validation or PyPI lockstep succeeds, and
the strict local pre-tag guard is bypassable by a direct tag push.

## What has landed since the prior audit

- Exact `(tmux_session_id, session_created)` identity is now used by the client
  model, persisted tree nodes, runtime keys, delayed kill fencing, and same-name
  successor handling (#2239).
- Stale-session dismissal is bound to its originating host tree (#2237).
- Conversation rebind-on-generation-change is merged through PR #2228 (#2155).
- Process-restart session restore has a durable harness and behavior
  (#2264/#2265). #2294 covers the narrower same-process kill-signal race that
  remains visible in CI.
- Safe outbound queue disposal/orphan repair landed (#1589/#1700).
- The alt-screen heal journey now has hardened race/selectivity proofs, and
  #2272 is closed.
- `ConnectionEffectDriver` observations are bounded to the newest 256 entries;
  the former unbounded accumulator from #1634 finding F5 is fixed.

## Live session-management priorities

### P0: warm fast-switch resurrection

#1634 finding F1 remains the highest-impact user-visible tree defect.
`TmuxSessionViewModel.kt` derives `willFastSwitch` from the leaving transport,
then exempts that lane from the gone-session preflight. A cached target can
therefore reach attach-or-create even though the target's liveness was never
proved. On a box where orchestrators and other devices frequently kill sessions,
this turns a stale tree row into a fresh impostor shell and looks like data loss.

Owner: #1634. Do not reopen #2239 for this; exact identity is a precondition,
but the missing piece is the attach-only/open-existing boundary.

Required shape:

- Treat an existing-session open/switch as attach-only; explicit Create remains
  create-capable.
- Route target-not-found to the existing stale-session recreate prompt.
- Preserve fast switches when the target is alive.
- Prove the dominant journey: live A, externally killed B, Back, tap B.
- Mutate by restoring `createIfMissing=true` on the fast path or removing the
  not-found route; the journey must turn red selectively.

### P0: durable tree storage contract

#2243 remains fully open and is confirmed on the audited heads. Current risks
include sanitized display-name cache collisions, whole-document read-modify-write
races, no common daemon/CLI lock or expected-version CAS, fixed temporary files,
non-atomic local writes, skipped authoritative empty snapshots, ambiguous empty
versus unavailable responses, and wall-clock process-local timing.

Owner: #2243. Fold client revision retention and explicit `Unavailable` typing
into this issue rather than opening duplicate trackers.

The minimum durable contract remains:

- stable local and remote host identity independent of display names;
- one per-host latest-wins writer with monotonic revisions;
- remote expected-version/CAS plus shared daemon/CLI locking;
- unique temporary files, fsync, atomic rename, and directory fsync where
  supported;
- persist every authoritative full snapshot, including `nodes=[]`;
- distinguish valid empty state from timeout/nonzero/exec/parse failure.

### P0: exact-generation cold-restore recurrence

#2294 is the correct narrow owner for repeated loss of persisted tmux identity
in `ColdRestoreGoneSessionNoResurrectE2eTest`. Do not merge it into #2264, which
owns external force-stop/relaunch orchestration, or silently reopen #2239.

The fix must distinguish a production persistence race from journey timing,
require both exact-generation fields before invalidation, replace any fixed wait
with a production-visible readiness condition, and prove selective mutation of
either field reddens the guard.

### P1: freshness architecture

The tree is intentionally free of fast polling, but its practical result is a
slow pull model. External churn is covered only by control-mode events while a
`-CC` client happens to exist, resume/pull/manual refresh, or a five-minute
heartbeat. Agent-state changes have no event coverage. A gone-name delta forces
a full authoritative probe because the host-side delta carries names only.
Staleness itself is invisible.

The structural cure is a host-originated `pocketshell tree watch` event stream
over the foreground lease, with pull-to-refresh and a slow repair reconcile kept
as fallbacks. It should cover session creation/deletion/rename and the same hooks
that write agent state/kind. This also gives dashboard surfaces a shared feed and
reduces per-refresh exec-channel pressure noted in #2120.

Until the stream lands, expose sync age and failed-sync degradation instead of
presenting a stale snapshot as fresh.

### P1: glanceable waiting state and stable interaction order

The tree currently answers "is this an agent?" more clearly than "which agent
needs me?" Nearly every agent row can look uniformly active, unknown states can
render no signal, and a waiting agent in a collapsed folder has no top-level
attention path.

The improvement should keep intrinsic tree order stable. Prefer semantic state
coloring, a compact pinned strip of waiting sessions above the tree, and a muted
"synced N ago" indicator. Reordering rows during interaction conflicts with the
stability work from #639/#663; recency should be applied coherently at screen
entry rather than continuously moving rows under the user's finger.

### P2: extract tree synchronization ownership

`FolderListViewModel.kt` remains oversized and owns hydration, reconciliation,
heartbeat/subscriptions, persistence, profile discovery, CLI checks, and other
concerns. Before adding another trigger or the event stream, extract a
`TreeSyncCoordinator` that owns hydrate/delta/full reconcile, persistence, and
lifecycle scheduling. The ViewModel should retain UI projection and action
routing.

## Broader code-audit findings

### Outbound delivery

The acknowledged-send journal itself is strong: schema versioning, hashed token
filenames, unique/fsynced temp files, atomic rename plus directory fsync,
exclusive claim, process liveness identity, rollback preservation, and pruning
limited to resolved records.

Two known owners remain correct:

- #2240: exit 5 (`send-interrupted`) is a truthful unknown outcome and must not
  be presented as ordinary retryable failure.
- #2241: prompt bytes belong on SSH stdin, not inside remote shell text.

Newly exposed:

- Non-durable tokens are generated as `"d<process-local-counter>"`. The host
  journal is global and survives restart/reinstall/client replacement. Token
  reuse can therefore suppress a new send as `already-delivered`.
- CI/tests can share that global journal and manufacture the same collision.
  Production scoping and fixture isolation need separate fixes.

### Connection/lifecycle architecture

#1634 remains the owner for the broad non-cancellable connect body, immutable
attempt-publication guards, cancel-without-join between passive recovery and a
new connect, and scattered shared-lease disconnect decisions. Those overlap
#1576, #2242, and the broader D28 extraction work in #1330/#766; they should not
be duplicated.

A smaller unowned risk remains: expired parked runtimes are evicted lazily when
the next cache operation occurs. A long-lived active session can therefore let
an expired parked `-CC` runtime continue consuming output indefinitely. Expiry
sweeping and confirmation of background service teardown bounds deserve a small
focused tracker.

### Connection concurrency and forwarding

The dedicated concurrency audit cleared the principal single-writer paths:
`ConnectionController` confinement/overlap handling, mutex-guarded SSH dial
coalescing, per-session `TransportDispatcher` writes, terminal drain budgets,
and durable outbound delivery claims. It did find narrower lifecycle races.

The highest-risk finding is that `SshLeaseManager.disconnect()` removes only a
materialized lease; it neither cancels nor marks a pending coalesced connect as
stale. Another surface can explicitly disconnect while the first surface's dial
is still in flight, after which the successful late dial registers a live lease
and loses the explicit-close intent. This needs a per-key disconnect generation,
typed joiner failure, and stale-owner close-on-return behavior.

Foreground port-forward resume has a related compound check/connect/adopt race:
the aggregate-notification Stop fence is effective, but an ordinary per-host stop
between the resume's enabled-host check and its slow connect can be overridden by
late adoption. `RealTmuxClient.connect()/close()` also lacks an internal atomic
lifecycle state machine; production usually serializes callers, so this is API
hardening rather than a proven user regression.

Two observability/hardening items belong behind smaller trackers: critical SSH
lease state edges are silently dropped by a saturated shared buffer, and
`AutoForwarder`/drain-scheduler cancellation paths need explicit lifecycle
proofs. Runtime-cache comments and the architecture document's phased New/Old
toggle description are stale.

### Host data contracts

Beyond #2243, two lower-level gaps merit separate hardening:

1. Daemon fallback catches broad runtime/socket failures and silently runs
   locally. This preserves availability during version skew, but hides internal
   daemon failures and makes method-not-found indistinguishable from daemon-absent
   across tree, jobs, sessions, and kind APIs. It needs typed outcomes and skew
   telemetry.
2. Usage-history NDJSON uses a fixed temp file without fsync/directory fsync and
   rewrites during trimming. A single scheduled writer makes this low risk, but
   concurrent capture or power loss can lose recent quota history.

Workspace persistence parses malformed responses more safely than the tree, but
it shares the absence of cross-process CAS. Defer its stronger concurrency design
until #2243 establishes the common durable-store primitive.

### Current CI triage

At `67c5c05b`, Tests run [32621460195] had four genuine RED shards. Unit, static,
Python utility, and Docker integration checks were green. The current-head run at
`d9565a56` was still running during consolidation.

| Family | Owner/disposition |
|---|---|
| HostAck `already-delivered` journeys | New token-scoping and fixture-isolation trackers |
| Cold restore loses exact tmux generation | #2294 |
| Settings journey cannot find `settings:lazy-column` | New isolated investigation |
| Authoritative viewport `viewFound=false` | #788 |
| Alt-screen heal race | #2272, closed and hardened on current head |
| Shard budget prevents outbound exactly-once execution | #788 coverage hole |
| Outbound suite-budget stall signals | #1819/#788 |
| Recovered composer/background/attachment flakes | Preserve evidence; address through capture-contract work below |

Recovery/error journeys have a shared test-oracle problem: production replaces a
terminal surface with an error/recovery card, while helpers still demand
terminal-only capture or treat any transient non-Connected status as the desired
indicator. Semantic state must be captured before bitmaps, and recovery-card-aware
capture/assertion support is needed without weakening #2135's terminal-capture
contract.

### Release and CI gates

The documented local pre-tag guard checks clean synced `main`, matching versions,
summary SHA, PASS status, and visual inspection. That is not enough structurally:

- A directly pushed `v*` tag causes Build to assemble and create a public GitHub
  release without proving protected-main ancestry, green heavy checks, monotonic
  `versionCode`, or hosted validation provenance.
- APK release creation occurs in Build before PyPI build/publish succeeds, despite
  PocketShell's runtime client/host lockstep requirement.
- Required release selectors are checked for source existence but accepted even
  with `OK (0 tests)`; hosted ledgers prove rolling class freshness, not current
  attendance for every required selector.
- The Real LLM ledger tier is required through generic unit-ledger verification
  even though no automated lane produces it.
- The required Python job can pass an entirely skipped suite because it has no
  JUnit artifact, executed-count floor, or skip-ratio refusal.
- Release/nightly emulator action references float while journey lanes are pinned.
- The trailing testing CI-matrix documentation describes an older topology.

#1678 also correctly keeps the nightly fault lane incomplete until the skipped
brief ride-through method becomes real. Routine releases should either wait for
that fix or use an explicit governed waiver, not implicit structural incompleteness.

## Deduplicated issue plan

The following are candidates for new trackers after this report is committed.
Existing issues remain authoritative for everything already listed above.

| Priority | Candidate | Why new |
|---|---|---|
| High | Globally unique/scoped non-durable HostAck delivery tokens | Real payload-suppression hazard; not owned by #2121/#2124 |
| High | Run-scoped/reset-safe HostAck journal test isolation | Prevents tests from recreating the global-journal collision |
| High | Recovery/error-state-aware capture and assertion contract | Shared oracle defect across overflow/background/attachment journeys |
| High | Diagnose Settings from a live session | The wiring exists, but the authoritative journey still never reaches Settings |
| Critical | Server-side release gate, lockstep publication order, monotonic versionCode, durable provenance | Local guard is bypassable; publication ordering violates lockstep intent |
| High | Positive executed-test proof for every required release selector | Blocks `OK (0 tests)` and stale-selector acceptance |
| Medium | Explicit Real LLM ledger tier | Removes an unscheduled producer from automated release requirements |
| Medium-low | Python CI execution artifact and executed/skip floor | All-skipped suite is currently green |
| Medium | Generation-aware host tree reconcile deltas | Completes the #2239 identity contract on the wire |
| Medium | Typed daemon fallback and version-skew observability | Broad catch masks internal failure and method-not-found |
| Large | `pocketshell tree watch` event stream | Structural freshness cure; reduces trigger/exec accretion |
| Medium | Visible staleness, waiting strip, stable interaction order | Makes the primary glance question answerable without unstable rows |
| Medium | Extract `TreeSyncCoordinator` | Gives future freshness work a bounded home outside the god ViewModel |
| Low | Crash-durable usage history writer | Low-frequency but avoidable quota-history loss |
| Low | Active expiry sweep and background park teardown verification | Prevents indefinite parked-runtime work under D21 |
| Low | Pin hosted emulator actions and refresh CI matrix docs | Reproducibility/supply-chain/documentation hygiene |
| High | Disconnect-generation fencing for pending SSH leases | Explicit close can lose to a late coalesced dial |
| Medium-low | Per-host desired-state fence for foreground forwarding resume | Late adoption can defeat a user's explicit stop |
| Medium-low | Atomic tmux client lifecycle contract | Close racing connect can leak shell/reader state |
| Low-medium | Reliable critical SSH lease state transitions | Saturated buffer can silently drop up/down edges |
| Low | Forwarder and terminal drain cancellation hardening | Check-then-act and post-cancel continuation paths lack focused proofs |
| Low | Refresh connection architecture comments and phased-toggle docs | Source no longer matches documented lifecycle assumptions |

## Non-goals

- Do not add a daemon copy of agent kind; `@ps_agent_kind` remains the sole
  recorded-kind authority.
- Do not move tmux liveness truth into the presentation registry.
- Do not shorten polling intervals as a substitute for the event stream.
- Do not reorder the intrinsic tree continuously to highlight waiting agents.
- Do not weaken #2135 terminal-capture failures merely because recovery screens
  legitimately remove `TerminalView`.
- Do not reinterpret host `already-delivered` as success to hide token collisions.

[32621460195]: https://github.com/alexeygrigorev/pocketshell/actions/runs/32621460195
