# The #1610 reconnect storm — root cause, 2026-07-16

The maintainer reported PocketShell "reconnects every single second" on mobile
internet while every other app (and Termius, on the same link at the same time)
worked fine. Six independent read-only investigations ran in parallel and
converged on one mechanism — and **overturned the diagnosis this was filed
under**. This document is the durable record, because the wrong theory was
plausible enough to have been implemented.

Primary issue: [#1610](https://github.com/alexeygrigorev/pocketshell/issues/1610).
Full synthesis: [#1610 comment](https://github.com/alexeygrigorev/pocketshell/issues/1610#issuecomment-4991072605).

## The falsified theory

#1610 was filed on this diagnosis:

> a transient -CC control-channel read error tears down the shared lease
> transport on mobile jitter; make it ride through

Three independent falsifications:

1. **The -CC reader exceptions were already healing.** Forensics over 15,456 log
   lines / 169 rotated files: every `SSHException control_channel` event carries
   `outcome=silent_heal_within_grace`. They are not the teardown.
2. **A raw `SSHException` means the transport was ALREADY DEAD.** sshj 0.40.0
   bytecode: a channel-stream throw only happens *after* `TransportImpl.die()`.
   The redial on that chain is **justified**. Implementing "ride through the -CC
   error" would have taught the app to ride through genuinely dead transports.
3. **`IdleExpired` is not the ~1/min cadence.** It fired 3 times in 15 days. The
   LivenessProbe (7s) + keepalive (30s) keep the wire busy, so carrier-NAT idle
   drop is ruled out for foreground sessions.

Also falsified: "sshj is tuned for LAN" (the timeout/keepalive layer is already
mobile-tolerant — 30s×3 keepalive, 90s ride-through, no-progress stall budgets),
and the doze theory (the D21 grace clock correctly uses `elapsedRealtime()`;
that bug was already found and fixed by #1080/#1544).

**The link was never the problem.** The forensics found three full TCP+KEX+auth
handshakes completing in ~1s each inside a 13s window. Termius survives the same
link because it has *less connection lifecycle to get wrong*: no idle reaper, no
per-session -CC control channels, no shared lease to tear down.

## What actually happens

Every repeating teardown in the maintainer's real device log is:

```
explicit_close  disconnectSource=local  disconnectIntent=local_close  trigger=auto-reconnect
```

fired while `status=Reconnecting` (133/140), each cycle on a **brand-new
clientHash (138/138 distinct)**. **The app creates a fresh SSH client every cycle
and locally closes it ~5s later.** Measured period: min 2.06 / median 5.67 /
p95 7.30s, with a hard 5.01–5.07s floor.

The chain, end to end:

1. A mobile blip wakes the passive-grace loop
   (`TmuxSessionViewModel.kt:8154-8202`).
2. It dials a fresh transport under a **single all-inclusive 5s budget**
   (`withTimeoutOrNull` at `:8439`;
   `PASSIVE_DISCONNECT_SILENT_REATTACH_TIMEOUT_MS = 5_000L` at
   `TmuxSessionSupport.kt:469`, retry spacing `250L` at `:470`) covering
   lease-evict + dial + KEX/auth + tmux -CC attach + panes-ready + full reseed.
3. On mobile RTT the handshake **fits** (~1.5–3s — hence the new clientHash every
   cycle). The tail stages **do not**. Deterministic timeout, every cycle.
4. The `!ready` branch (`:8515-8546`) then closes a **fully-handshaken, healthy**
   transport — `replacement?.close()` (`:8522`) + `sshLeaseManager.disconnect()`
   (`:8524`). It cannot distinguish "the dial timed out" from "the attach ran
   slow on a proven-up link".
5. That self-inflicted teardown is re-ingested as a **fresh passive failure**
   (`ConnectionEffectDriver.kt:416-447`), because the #1568 self-inflicted filter
   covers only the -CC edge (`TmuxClient.close()`), never the lease or keepalive
   edge. Recovery re-arms itself.
6. The attempt counter never advances: the loop dials `sshLeaseManager.acquire`
   **directly**, never through `connect()`/`runConnect` (the sole
   `connect_invoked` emitter, `:2717-2728`), so it never submits
   `ReconnectFailed` (`ConnectionController.kt:332-341`). Each mid-cycle
   handshake success fires `TransportLive` → back to `Live`, wiping the walk.
   Backoff never engages; the give-up state is dead code.

Period = 5s + 250ms + dial time = the observed 5.0–5.7s.

**Why it never self-heals:** a constant budget against constant >5s latency fails
identically every iteration. Bursts end only via backgrounding, a user
fast-switch, or grace expiry. Each mobile blip buys a fresh 60s window of ~11
killed transports, chained by near-continuous entry events — subjectively
"forever".

**Why it looks mobile-specific but isn't.** The loop is link-independent and the
signature is present since **at least 2026-07-04** (13 identical bursts, largest
34 cycles / 193s). Mobile only multiplies the *entry* triggers.

## The three fix slices

Disjoint file ownership; all reproduce-first (D33/G10).

| Issue | Defect | Owns |
|---|---|---|
| [#1539](https://github.com/alexeygrigorev/pocketshell/issues/1539) | One all-inclusive 5s budget kills handshaken transports; loop failures never feed the counter | `TmuxSessionSupport.kt` + VM grace region |
| [#1632](https://github.com/alexeygrigorev/pocketshell/issues/1632) | Recovery's own close echoes back as a passive drop (filter covers only the -CC edge) | `ConnectionEffectDriver.kt`, `SshLeaseManager.kt` |
| [#1633](https://github.com/alexeygrigorev/pocketshell/issues/1633) | Counter resets on "a dial succeeded" not "a connection proved stable" | `ConnectionController.kt` |

Seam between #1539 and #1633: **#1539 feeds** the counter (the wiring lives in
the loop body it owns); **#1633 makes it escalate and terminate** (stability
window, episode semantics, reachable give-up, jitter) and owns the `TransportLive`
reset fix.

## Aggravating factors found along the way

- `SshLeaseManager.disconnect()` (`:475-486`) is **refCount-blind and
  liveness-blind** — it evicts the shared transport without checking whether it
  is alive or in use. Rung-2 per-channel recovery exists but self-disables when a
  failed rung-3 nulls `sessionRef`.
- **The message queue amplifies the storm it suffers from**: the keystroke lane
  silently drops a batch after 2 failed attempts and **fires a passive-disconnect
  into the ladder** (`OutboundDeliveryGuard.kt:635-652`). Circular. See
  [#1635](https://github.com/alexeygrigorev/pocketshell/issues/1635).
- **Network-callback blindness**: the #1042 reassoc suppression drops
  same-identity changes at `TerminalNetworkObserver.kt:160` *before any
  diagnostic record*, so "no repeated network callbacks in the log" was
  unverifiable by construction. The socket is never bound to a `Network`, so a
  handover strands it. See
  [#1631](https://github.com/alexeygrigorev/pocketshell/issues/1631).
- `ConnectionEffectDriver._observations` is an **unbounded production
  accumulator** with full-list-copy appends (`:220`, `:487-490`) — worst exactly
  during the storm. See
  [#1634](https://github.com/alexeygrigorev/pocketshell/issues/1634).

## Process lesson

**#1539 diagnosed the primary defect on 2026-07-13, log-proven, with complete
reproduce-first acceptance criteria — and sat unimplemented for three days while
the maintainer could not use the app.** Its recorded signature ("8
successfully-handshaken transports closed at ~5.5s cadence over 44s") is the same
one re-reported as new on 07-16. The diagnosis was never the bottleneck; dispatch
was.

Second lesson: **#1610's own issue body was wrong**, and confidently so. It had a
cited log trail, a plausible mechanism, and a ready fix direction. What broke it
was reading the *data* (forensics) and the *dependency bytecode* (sshj) rather
than reasoning forward from the existing theory. When an issue in a
repeatedly-reopened class already contains a diagnosis, re-derive it before
implementing against it.

## Landed (2026-07-16)

All four slices are on `main`. The wave, in merge order:

| Commit | Slice | What it fixes |
|---|---|---|
| `a69a9a8c` | #1633 r2 (#1645) | Jitter rolled once per install, not per rebuild — also fixed a real defect where one attempt re-rolled its own backoff **on the storm path** |
| `c66b940e` | #1632 (#1643) | Recovery's own teardown no longer echoes back as a passive drop |
| `ab3e0caf` | #1621 (#1644) | Composer send pipelining (unrelated to the storm) |
| `fb5746eb` | **#1539 (#1647)** | **Per-stage budgets; the reseed leaves the readiness verdict; never kill a handshaken transport; feed the counter per failed cycle** |
| `136fe702` | #1642 s1 (#1648) | Mirror `connection/*` to the host, so the log stops hiding what the device already knows |

Plus `0c55af4b` (#1633 r1: stability window, episode semantics, reachable
give-up, jitter).

**#1610 remains OPEN — deliberately.** Every proof is JVM/virtual-clock.
#1632's reviewer relocated the on-device symptom-gone bar to a hard condition
on #1610, because the storm is a joint property of #1539 + #1632 + #1633 and
no single slice can prove it. Do not close #1610 until the maintainer confirms
on the real path (D33). This class has been closed four times on exactly that
kind of premature confidence.

## What today cost, and why

The wave put `main` red once. #1633's jitter broke an exact-delay assertion in
`:app` that nobody swept for, because the implementer only fixed the two
assertions in the module it was editing. It then passed CI once and failed
every run after — `git diff` between the green PR head and the red `main` merge
is EMPTY. Same tree, different verdict.

Three incidents that day were the same shape — **a confident green over zero
tests**:

1. **Wrong task.** The integration gate ran `:app:testDebugUnitTest`; CI runs
   `test` (both variants) and `:app:testReleaseUnitTest` was red.
2. **UP-TO-DATE skip.** "20/20 in isolation" was four `BUILD SUCCESSFUL in 3s`
   runs with zero tests executed. Two actors reasoned from it; both were wrong.
3. **Killed process.** A `nohup`'d gate died at `generateDebugResources` and
   reported exit 0.

Exit codes and build banners said green in all three. Only the executed test
count exposed them. Rules recorded in `a8af5d2d` + `8dc16024`; the CI side is
filed as #1646 (CI structurally cannot show the count on a green run).

## The wider lesson

**Three times in one day the written guidance was wrong, and an agent caught it
by re-deriving from mechanism:**

- **#1610's own issue body** — the -CC ride-through theory. Implementing it
  would have taught the app to ride through genuinely dead transports.
- **#1632's issue text** (orchestrator-authored) — asked for keepalive closes to
  be filtered. Complying would have disabled the mobile silent-drop detector:
  the app would never reconnect at all. The implementer refused and the reviewer
  found for the implementer.
- **The orchestrator's own process fix** — "prove determinism with N>=20 runs",
  unsound because Gradle skips passing tasks. Amended within the hour.

An issue body is a hypothesis, not a spec — including one the orchestrator
wrote. Re-derive before implementing, especially in a repeatedly-reopened class.
