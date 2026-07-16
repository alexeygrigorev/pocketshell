# Orchestrator handoff — 2026-07-16 (evening)

> **SUPERSEDED by `handoff-2026-07-17.md`. THE HEADLINE BELOW IS FALSE.**
> This document says the #1610 storm is "fixed and on `main`". It is not.
> The wave was found incomplete twice after this was written, and its gate is
> RED. #1653 is approved but must NOT be merged alone (it arms a strand).
> Do not tag a release from this document. Read `handoff-2026-07-17.md`.

Supersedes `handoff-2026-07-16.md`. Read `process.md` first, then
`docs/connection-storm-2026-07-16.md` — that is the root-cause record and the
most important document from this session.

## 1. The headline

**The #1610 mobile reconnect storm is fixed and on `main` — but NOT confirmed
on the maintainer's phone.** `#1610` is deliberately still OPEN.

The maintainer reported PocketShell reconnecting every ~5s on mobile while every
other app (and Termius, same link, same time) was fine. Six parallel read-only
investigations converged on one mechanism and **overturned #1610's own filed
diagnosis** — the wrong theory was plausible enough to have been implemented,
and implementing it would have made things worse.

Landed, in merge order:

| Commit | Slice | What it does |
|---|---|---|
| `0c55af4b` | #1633 r1 | Stability window, episode semantics, reachable give-up, jitter |
| `a69a9a8c` | #1633 r2 (#1645) | Jitter once per install — also fixed a real defect where one attempt re-rolled its own backoff **on the storm path** |
| `c66b940e` | #1632 (#1643) | Recovery's own teardown no longer echoes back as a passive drop |
| `ab3e0caf` | #1621 (#1644) | Composer send pipelining (unrelated to the storm) |
| `fb5746eb` | **#1539 (#1647)** | **The load-bearing slice** — per-stage budgets, reseed out of the readiness verdict, never kill a handshaken transport, feed the counter per failed cycle |
| `136fe702` | #1642 s1 (#1648) | Mirror `connection/*` to the host |

## 2. What the next orchestrator must do FIRST

1. **Ask the maintainer to dogfood on mobile and report.** That is the only
   remaining evidence. Every proof so far is JVM/virtual-clock; #1632's reviewer
   deliberately relocated the on-device symptom-gone bar to a hard condition on
   #1610, because the storm is a joint property of #1539 + #1632 + #1633 and no
   single slice can prove it. **Do not close #1610 without it (D33).** This class
   has been closed four times on exactly that premature confidence.
2. **Check the `main` batch-head run for `136fe702`** (`29525593971`). It carries
   the heavy `Integration tests (Docker)` + `Emulator journey subset` jobs the PRs
   skip. Docker was green (4m38s); Unit + emulator were still in flight at handoff.
   Per #1640, do **not** accept an emulator "green" that is actually a skip or an
   `AGGREGATE_VERDICT=RE-RUN`.

## 3. Awaiting the maintainer (do not start these unasked)

- **#1639 — CRITICAL security.** No SSH host-key verification anywhere
  (`PromiscuousVerifier`, 8 call sites); `debug.keystore` committed to the
  **public** repo while we ship a debug-signed, `debuggable` APK. Anyone can sign
  a build that installs over theirs keeping the data dir — where the plaintext SSH
  keys live. Fixing the keystore changes the signing identity ⇒ one-time
  uninstall/reinstall ⇒ **maintainer authorization + QR export first**.
- **D28 rewrite (S7 #766 + S8 #1330).** The Fable audit says REWRITE; the Fable
  design agent decided **GO**, sequenced *after* this wave, S8 last. Blocker is
  the maintainer's #766 on-device sign-off, **pending since 2026-06-14**. The
  design agent proposed replacing it with CI-journey evidence + post-merge dogfood
  acceptance — **that substitution needs maintainer ratification under D28.**
- **#1636 — silent content corruption**, rated P1 immediately after the storm: a
  mid-paste teardown + verified resend accretes partial payload copies. The prompt
  is submitted exactly once **with wrong content**. Our occurrence-count
  assertions are structurally blind to it.

## 4. Ready backlog (filed today, unworked)

Connection/session: **#1634** (fast-switch silently resurrects killed sessions via
`createIfMissing=true` → impostor sessions; also: the whole connect/switch body is
`NonCancellable` since #178, so `cancelConnect()` is cosmetic), **#1631**
(network-callback blindness + no `Network` binding), **#1641** (`AgentKindRemoteSource.execBounded`
silently closes the shared lease on a >3.5s exec — an uncredited storm entry
trigger, explains "some sessions, not all"), **#1576**, **#1539 follow-ups**.

Queue: **#1635** (storm exhausts the 6-attempt budget → queue parks and never
auto-sends; attachment rows restart from byte 0 forever), **#1636**.

UX: **#1637** (the green dot lies during Attaching — `TmuxSessionConnectionChrome.kt:889`;
the reconnect strobe blanks the terminal ~60×/hour; agent state invisible
in-session; voice-first has no visible mic).

Infra/process: **#1640** (test-suite reliability — *the test that would have caught
#1610 exists and has been red 8 consecutive nights*, self-skipping per-push, in a
lane waived by `NIGHTLY_FAULT_GATE_DISABLED=1` **baked into `process.md`'s own
recommended release command**; per-PR CI runs zero connected tests; 45 of the last
60 `main` runs cancelled), **#1646** (CI structurally cannot show the executed test
count on a green run), **#1650** (`watch-ci.py` fabricates infra failures — fix in
flight), **#1649** (`Issue926SeedIoOffMainTest` racy by construction, ~1-in-10 red
on a required check).

Design: **#1331** carries the target state machine + the Fable design decisions
(ladder `[0,1,2,5,10,20,30,30]s`, 30s stability window, **episode budget 120→180s
— NOT yet implemented**, Degraded input ON, keep-last-frame, Send-wake extended to
`Suspended(NetworkLost)`). **A P0 mini-slice (B2) is unimplemented and matters**:
`Unreachable` currently has **no exit** except a user Enter/Switch — `Foreground`
and `NetworkRestored` are no-op reducer arms. Now that give-up is reachable, any
outage >3min **strands the user**. Needs two reducer arms + the 180s constant.

## 5. Process rules added today (all earned the hard way)

`a8af5d2d` + `8dc16024` in `process.md`:

- **Gate on `./gradlew test`, NOT `:app:testDebugUnitTest`.** CI runs both
  variants. The orchestrator's debug-only gate was green at 3906/0 and merged a
  slice that was red on `:app:testReleaseUnitTest`. That put `main` red.
- **A green run that executed ZERO tests is the most dangerous artifact in this
  repo.** `--rerun-tasks` is mandatory AND the executed count must be asserted
  from the result XML **every run**. Gradle skips a *passing* test task as
  `UP-TO-DATE` while a *failing* one always re-executes — so a naive loop
  manufactures "1 fail, then a long green streak". This fooled an on-call
  ("20/20 in isolation" = four `BUILD SUCCESSFUL in 3s` runs, zero tests) and the
  orchestrator, into two confidently wrong conclusions.
- **Monotonic beats sampling.** If the assertion's outcome is monotonic in the
  varied quantity, pin it to **both extremes** — that covers the whole
  distribution and is strictly stronger than N random samples.
- **Sweep every module for the breakage class**, not just the file you edited.
  #1633's jitter broke assertions in two modules; only one was swept.

## 6. The lesson worth carrying

**Four times in one day, confident and specific information was wrong — and each
time an agent caught it by re-deriving from mechanism rather than trusting the
text:**

- **#1610's own issue body.** The -CC ride-through theory. Implementing it would
  have taught the app to ride through genuinely *dead* transports.
- **#1632's issue text** (orchestrator-authored). Asked for keepalive closes to be
  filtered; complying would have disabled the mobile silent-drop detector — the app
  would never reconnect at all. The implementer refused; the reviewer found **for
  the implementer**.
- **The orchestrator's own process fix.** Unsound within the hour.
- **`watch-ci.py`** (#1650). Returned `FAILED` with the signature *"sdkmanager
  still broken after repair, issue #771"* — both fabricated. The run had merely
  been superseded by concurrency-cancel.

Three merge-gate incidents shared one shape — **a confident green over zero
tests**: a wrong task, a Gradle `UP-TO-DATE` skip, and a killed process reporting
exit 0. Exit codes and build banners lied in all three. **Only the executed test
count exposed them.**

An issue body is a hypothesis, not a spec — including one the orchestrator wrote.
Re-derive before implementing, especially in a repeatedly-reopened class. The two
best outcomes today came from an implementer **refusing its brief** and an
implementer **deleting a test it could not make fail**.

## 7. State

- `main` clean and synced. Root checkout on `main`. No open PRs.
- In flight: **#1650** implementer (worktree `.worktrees/issue-1650`), on-call
  watching the `136fe702` batch-head run.
- Stale integration worktrees under `.worktrees/integrate-*` are safe to prune
  (their issues are closed). Preserve `.worktrees/issue-1650`. Never touch locked
  `.claude/worktrees/agent-*`.
