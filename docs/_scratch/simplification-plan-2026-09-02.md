# PocketShell simplification plan — deletion-and-rewrite, 2026-09-02

Scratch working doc, not committed process. Written after a read-only survey of
`pocketshell` (HEAD b7fa7713), `~/git/pocketshell-electron` (origin/main b171078),
`~/git/aplexer` (v0.1.1, HEAD ~3a816ba), and `~/git/tmuxctl` (HEAD 2026-08-27).

Triggering incident: the maintainer could not create a new session from the newest
release — a silent connect failure. His directive: "remove most of the code and have
a clear reimplementation of the core flows — now knowing all the things we have
learned." This document is that plan. It does not propose an incremental refactor.

---

## 1. Diagnosis — why PocketShell got large and fragile

### 1.1 The raw numbers

| Thing | Size |
|---|---|
| Kotlin production code (`app/src/main` + `shared/*/src/main`) | ~198,900 LOC (156,678 in `app/`, 42k in `shared/`) |
| Kotlin test code | **~460,000 LOC** (app test 404,423 + androidTest included; shared tests ~57k) — a 2.3:1 test:prod ratio |
| `app/src/main/java/com/pocketshell/app/tmux/` | 42,451 LOC across 98 files |
| `TmuxSessionViewModel.kt` | **17,621 lines, 489 functions, 286 `*ForTest` seams, 1,072 issue-number references in comments** |
| Host CLI `tools/pocketshell` | ~19,700 LOC production Python + ~23k tests |
| CI scripts | 196 files in `scripts/` (36 `check-*` guards, 47 `ci-*` harness pieces) |
| `.github/workflows/tests.yml` | 2,219 lines, 14 jobs |
| Test classes named after incidents | 142 `Issue*Test.kt` files |

Churn: 2,482 commits since project start (2026-05-21). Since 2026-06-01, **735 of
2,012 commits (37%) mention connection/session/reconnect/socket/lease/grace/attach/tmux**.
`TmuxSessionViewModel.kt` alone has been touched by **407 commits — 1 in 6 of every
commit ever made** — including 28 in the last week.

### 1.2 The god object the process already named

`scripts/check-connection-vm-ratchet.sh:4-8` describes its own target as "the single
connection-core god-object, app/.../tmux/TmuxSessionViewModel.kt" and exists solely
to stop it growing. The VM imports from `assistant`, `cards`, `composer`,
`conversation`, `diagnostics`, `share`, `repos`, `projects`, `session`, `voice` — it
is the app. Its public API is dominated by test seams: 286 `*ForTest`
functions/properties in this one file (604 across `app/src/main`), i.e. the test
apparatus has colonized production code. Function names in it read as a fossil
record of individually-patched incidents: `KeepaliveDeathRedialAmortizer`,
`ReseedEchoRaceGuard`, `StaleRenderWatchdog`, `ConnectedBlankWatchdog`,
`RenderHealCoordinator`, `DeadLeaseRecoveryAuthority`, `SelfInflictedClose`,
`OutboundDeliveryGuard`, `handleTerminalSeedGateOverflow`,
`shouldRedialAfterCoalescedCancel`, `isStaleChannelSymptom`.

### 1.3 The unfinished rewrite is itself the patches-on-patches condition

D28(4) (decisions.md:36) defines "done" as: the `core-connection`
`ConnectionController` is "the SOLE active path … and the old `TmuxSessionViewModel`
reconnect/grace path is DELETED — no shadow/old coexistence remains. The current
half-migrated shadow+old state is itself the patches-on-patches condition this
decision exists to end." That was locked **2026-06-13**. Eleven weeks later,
`docs/architecture.md:97-102` still says "`TmuxSessionViewModel` remains the Android
integration surface. #766 tracks the remaining hard-cut cleanup … documentation must
distinguish the controller authority from the ViewModel integration residue."

So today there are **three coexisting layers** for one job:

1. `shared/core-connection/` — a clean, pure reducer (3,624 LOC, 10 `ConnectionState`
   variants, a 45-line threading-contract essay in `ConnectionController.kt:34-56`).
2. `app/src/main/java/com/pocketshell/app/tmux/connection/` — 20 effect-driver files,
   3,958 LOC (`ConnectionEffectDriver`, `GraceEffects`, `NetworkLossBandEffects`,
   `ParkedRuntimeHealthEffects`, `PassiveTransportDropEffects`, `LivenessProbeGate`,
   `ReconnectUiDebounce`, …).
3. The 17.6k-line VM residue that D28 ordered deleted.

The clean-rewrite mandate produced a fourth subsystem *next to* the ones it was
meant to replace. That is the single clearest proof that the problem is structural,
not effort: the design being rewritten was too entangled to finish cutting over.

### 1.4 Decision clustering: what the scar tissue defends

Group D1–D37 by what they protect and one subsystem dominates:

- **Connection/session core:** D21 (grace — amended six times: #161, #203, #450,
  #977, #1123, #1159, a 90-line cell in decisions.md:29), D28 ("most critical
  subsystem", rewrite-over-patch), D29 (phased turn-on with a sanctioned
  old/new toggle), D30 (stop managing tmux windows — a feature amputated because it
  "confused the session model"), D34 (headless proof rules for
  "transport/storm/reconnect/lease fixes" — a taxonomy of storm signatures that
  exists because storms recur).
- **Process compensating for shipped regressions:** D31 (durable-fix gate — "142
  issues carried recurrence/regression language by 2026-06-19"), D32/G1–G10, D33
  ("verified gone"), D36 (stop-the-line after v0.4.45 sat red 11 days), D37 (the
  release fault-gate was silently waived for **eight consecutive releases**,
  v0.4.31–v0.4.38 and again v0.4.45).
- **CI truthfulness:** `docs/ci-pitfalls.md` — 295 lines cataloguing ~12 distinct
  ways a green run has lied here.

A team does not write G1–G10, a 296-line catalogue of vacuous greens, a flake
quarantine with expiry timers, and two file-size ratchets to defend a healthy core.
This apparatus is load-bearing precisely because the core keeps regressing, and the
core keeps regressing because the *client owns too much*.

### 1.5 Essential vs accidental complexity

**Essential** (a voice-first, tmux-native, agent-aware Android SSH client must do):
SSH transport that survives Android lifecycle (Doze, backgrounding, network
handoff — D21's *policy* is right); a real VT emulator (D4 — vendored Termux,
correctly never rewritten); host/key storage with biometric unlock; voice capture +
Whisper; a phone-shaped composer and key bar; rendering *something* per session.

**Accidental** (a design choice that stopped extending cleanly, then got patched):

1. **The client speaks `tmux -CC` itself** (D5, `shared/core-tmux/` 5,341 LOC,
   `TmuxClient.kt` 2,447). Control mode puts a stateful, line-oriented protocol
   parser, per-pane demux, command/response correlation, layout-change coalescing,
   and idle-deadline heuristics *on the phone side of a flaky cellular link*. Every
   network blip becomes a distributed-systems bug: the last three commits on `main`
   are "fix silent layout-change drop when reconcile's list-panes exec times out"
   (e192cb72) and a reconnect-journey test-scoping fix (8bd15c7e). P4 of D29 exists
   because the `-CC` command timeout **self-inflicted reconnects** under Codex
   output volume (architecture.md:115-119).
2. **The client infers session semantics.** Agent identity from pane-TTY `ps`
   scans + cwd-encoded log discovery + freshness windows
   (docs/agent-awareness.md:24-51, `core-agents` 2,459 LOC,
   `AgentConversationRepository.kt` 2,463); workspace from session-name
   conventions; engine/profile/state from `@ps_*` tmux user options; session
   enumeration by sweeping tmux sockets (`FolderListGateway.kt` 2,514,
   `HostSessionEnumerator` retry bounding in a91b01e9). aplexer's spec §22 lists
   exactly this stack as what PocketShell "should stop owning."
3. **Per-pane rendering + reveal/seed/heal.** Because the client re-renders panes
   from `%output` events, it needs seed capture, id-keyed reveal gating
   (`RevealStateMachine`, 450 LOC), reseed-on-reattach, blank-screen and
   stale-render watchdogs, and echo-race guards — an entire self-healing layer for
   a screen tmux would have simply repainted in attach mode.
4. **A lease pool for SSH connections** (`SshLeaseManager.kt`, 1,012 LOC: refcounts,
   warm windows, idle TTLs, dial-job cancellation to unpark blocked sshj reads).
   pocketshell-electron proves one connection per host + channels is enough — its
   registry is 87 lines.
5. **The delivery-guarantee layer** for sends (`OutboundQueueStore` 2,148,
   `OutboundDeliveryGuard` 961, `PromptComposerOutboundSend` 879, agent-submit ack
   timeouts) — insurance written against the connection core's own unreliability.
6. **Test scar tissue as calcification:** 142 incident-named test classes, 439
   androidTest files, journey shards with measured "fixture cost rows," and 286
   test seams in one file. The tests defend accreted behavior byte-for-byte, which
   is why every cleanup (the 2026-08-30 audit batch) breaks ~150 of them at once —
   see d5026197 "Fix remaining host-key-trust gap across 121 androidTest fixtures",
   b7c5f870, e7052947, efd42008, 341c1979, 5f1f746c: **six commits of fixture
   repair for one production change.**

### 1.6 The triggering incident fits the pattern

The 2026-08-30 audit batch enforced SSH host-key trust in production (4b5be0d8,
1a6342bd, dceabfd5). It merged without the full gate (the v0.4.47 postmortem in
process.md, "Batch-merge validation") and broke `main` five ways. Open issue
**#2453 — "3 LeaseSessionExec-based gateways missing host-key-trust wiring
(production gap)"** — is exactly the shape of a *silent* new-session connect
failure: a gateway that dials without trust wiring now fails closed, quietly.
Sibling #2426 ("FolderListGateway: tmuxctl/aplexer enumerator silently drops
session rows under lossy/high-RTT links") and #2439 ("New Session picker drops
Codex") are the same week, same subsystem. Whether the maintainer's exact failure
is #2453 or a sibling needs a device repro (open question §6), but the class is
established: a correctness sweep through a many-owners connection path regressed
untouched flows, and 460k lines of tests plus 14 CI jobs did not stop it reaching
his phone.

**Diagnosis in one sentence:** PocketShell put a distributed system's server-side
responsibilities (session state, protocol demux, identity inference, self-healing)
into an Android client on a cellular link, and five months of disciplined patching
plus a world-class verification apparatus have been compensating for that placement
instead of changing it.

---

## 2. What the sibling projects prove can be simpler

pocketshell-electron: 50,822 LOC of TS/Vue at origin/main — but ~45% of the
TypeScript is comment prose; real logic ≈ 15k in `.ts` plus Vue templates. One
runtime dependency (`ssh2`). It ships the same core capability set (minus voice,
minus conversation view — cut by the maintainer's own request) against **the same
dev box and the same host CLI**, and the maintainer says it "works way better."

### 2.1 Side-by-side, same feature

| Flow | PocketShell Android | pocketshell-electron | aplexer gives you |
|---|---|---|---|
| **Host connection** | `core-ssh` 7,636 LOC: `RealSshSession` 2,609, `SshLeaseManager` 1,012 (lease pool, refcounts, warm TTLs, dial-cancel tricks), keep-alive, transport dispatcher | `SshService.ts` 548 + `ConnectionRegistry.ts` 87 + `ShellTracker.ts` 59 ≈ **700 LOC**. One `ssh2.Client` per host; exec/PTY/SFTP/tail/forwards are channels on it; a second-connection design was built and **cut** for contradicting the model | n/a (client concern) |
| **Session discovery + attach** | `core-tmux` 5,341 (`-CC` parser, response correlation, `LayoutChangeCoalescer`, `PreRegistrationOutputBookkeeper`) + `FolderListGateway` 2,514 + reveal/seed machinery | `TmuxClientPool.ts` 836 + `attachCommand.ts` 216 + helper client/parsers ~2,100: list via `pocketshell sessions list` + one enrichment probe; attach = **write a `tmux attach-session` one-liner into a PTY**. An in-flight commit (53c04b3) collapses attach further to `pocketshell sessions attach '<name>'` | `a snapshot --json` (schema’d, UUID identity, workspace/tag/engine/profile first-class), `a attach` — no name inference, no socket sweep |
| **Terminal I/O** | `core-terminal` 9,306 (vendored emulator + `SshTerminalBridge` 1,444 + `TerminalSurfaceState` 1,812) + per-pane render + heal/watchdog layer in `app/tmux` | xterm.js + ~600 LOC of wiring (write buffer, fit-on-resize, geometry probe). tmux repaints its own screen | Attach sends a **vt100-rendered current screen** (geometry-first, few KB), not a 32KB raw tail — reconnect *is* a repaint (`worker.rs::handle_attach`, `screen.rs`) |
| **Reconnect** | `core-connection` 3,624 + `app/tmux/connection` 3,958 + VM residue; 10 states, episode budgets, jittered ladders, liveness probes, storm amortizers | **~600 LOC**: `reconnectBackoff.ts` 85 (pure, 5→60s, 10 attempts, give up) + `stores/connection.ts` 421 + a resume hook. Panes stay mounted under the banner; owners re-dial | Sessions survive by construction (one PTY worker per session, cgroup-isolated); client reconnect = re-run join + screen repaint |
| **Agent awareness** | `core-agents` 2,459 + `AgentConversationRepository` 2,463 + pane-TTY `ps` scans + per-engine JSONL/SQLite tailing + freshness windows | **121 LOC** (`agentBadge.ts`): reads `@ps_agent_kind`, set host-side at launch. "Not decided anywhere in this app." No sniffing, survives restart free | `a state-report` ingests engine hooks; vocabulary matches `SessionAgentState.kt` **one-for-one** (`a.rs:323-327`). `a watch --jsonl` pushes `agent.state`. `a transcript` parses Claude/Codex/Grok logs server-side into normalized events with cursors + `--follow` |

### 2.2 Why electron stayed small (its own record)

`ARCHITECTURE.md` §3: attach is "~70% less protocol code, no VT-escape un-decoding,
no `%output` demuxer" — control mode is "valuable on a phone, less so on desktop."
The deleted `ANALYSIS.md` said it flat: "we do **not** re-port the control-mode
parser … we lean on the server-side `pocketshell` helper and use a simpler attach
model." Its other habits are the lesson list: one connection per host; semantics
delegated to the helper; state lives on the remote (evicted tab loses nothing);
credentials never on the client; and *actual* hard cuts — conversation view,
keytar, ssh2-sftp-client, the switch-client design were deleted whole, with a test
(`packagedDependencies.test.ts`) that fails if the dependency list grows without a
written reason.

### 2.3 What adopting aplexer as session runtime deletes outright

aplexer is real: ~22.5k LOC Rust, 7.7k LOC destructive integration tests (OOM
isolation, crash recovery), the entire spec-§27 V1 CLI implemented, and the §22
client contract built for PocketShell by name (`launch-spec` is documented in
`a.rs:74-83` as "internal integration point for pocketshell's launcher shim").
spec §22 verbatim — PocketShell should stop owning:

> tmux session lifecycle, `tmuxctl` wrappers, agent engine registry, agent profile
> discovery, agent launch command construction, agent-specific environment
> preparation, tmux user-option agent identity, workspace inference from session
> names, agent identity inference required only because tmux lacks metadata.

Mapped to files that become deletable (fully or mostly) once both backends sit
behind a host-CLI seam:

- `shared/core-tmux/` `-CC` protocol layer (5,341 LOC) — the client never parses
  control mode again for either backend.
- `FolderListGateway.kt` (2,514) socket-sweep enumeration, `SessionNamePolicy`,
  name-derived workspace grouping across `app/projects/` (19,667 LOC package).
- Client-side agent detection: `core-agents` detector + pane-process confirmation
  (`AgentDetector`, TTY `ps` scoping), replaced by pushed state.
- Engine/profile plumbing duplicated in the app (`EnginesGateway`,
  `ClaudeProfile`/`CodexProfile`, picker availability logic — the #2439 bug class).
- The reveal/seed/heal layer — with attach-mode rendering there is no client-side
  pane reconstruction to heal.
- Most of `docs/aplexer-integration.md` Phase A is **already done** (#2341): the
  host CLI prefers `a profiles/engines --json` and `a launch-spec` today. The seam
  exists; the Android app just doesn't sit behind it yet.

Caveat: `docs/aplexer-integration.md:141-152` (dated 2026-08-26) lists Phase-B
blockers — reattach TUI corruption, no state push, identity mapping. aplexer had
~250 commits in the five days after that was written, and the first two blockers
appear addressed at HEAD (vt100 screen-repaint attach in `screen.rs`;
`a state-report` shipped 2cff767..3a816ba). This must be re-verified live (§6),
but the doc is almost certainly stale in the pessimistic direction.

---

## 3. Rewrite plan per core flow

The unifying design rule, taken from electron + spec §22: **the phone is a viewer
and an input device. Every piece of session semantics lives on the host, behind
`pocketshell`/`a`/`tmuxctl`; the client holds one SSH connection per host and
renders bytes.** The maintainer's new requirement — attach to tmuxctl-backed tmux
OR aplexer sessions through one runtime-agnostic layer — falls out of this for
free, because the client stops knowing what a backend is.

### 3.1 Connect to host — REWRITE FROM SCRATCH

Replace `core-ssh`'s lease pool with electron's model: one sshj `SSHClient` per
host, everything (exec, shell PTY, SFTP, forwards) as channels on it, a channel
budget constant instead of a lease manager. Keep sshj (D3 stands), keep the
newly-built host-key TOFU/known-hosts trust (the audit's one security keeper —
but wired **once**, at the single connect site, so a #2453-style "gateway missed
the trust wiring" gap becomes structurally impossible: there are no other dial
sites). Keep key storage/biometrics from `core-storage`/hosts as-is.
Target: ~1,000 LOC replacing 7,636. No refcounts, no warm-lease windows — "warm"
is simply "the one connection is still up," and the D21 grace window is one
delayed `disconnect()` cancelled on foreground return.

### 3.2 Discover / list / attach sessions (tmuxctl OR aplexer) — REWRITE, host-CLI seam

The runtime-agnostic contract, all server-side in `tools/pocketshell` (~20k LOC
Python that already probes both managers):

- `pocketshell sessions list --json` → rows
  `{name, workspace, backend: "tmux"|"aplexer", engine?, profile?, agent_state?,
  attached, created_ms, activity_ms, join_command}` — the union of per-session-socket
  tmux enumeration and `a snapshot --json`, exactly the Phase-B listing shape
  `docs/aplexer-integration.md:139-144` already specifies, plus electron's
  `joinCommand` field (commit 53c04b3 in the electron repo already models this).
- `pocketshell sessions attach '<name>'` → execs the right joiner
  (`tmux -S <socket> attach-session -t '=<name>'` vs `a attach <id>`). The client
  never builds a tmux or aplexer command line again.
- `pocketshell sessions create --folder <dir> [--engine E --profile P]` →
  `tmuxctl create-detached` or `a start` per configured default backend.

Client side shrinks to: run `sessions list --json` over exec, parse JSON, render
tree; to open, request a PTY channel and write `pocketshell sessions attach
'<name>'`. Grouping stays client-side but keys on the `workspace` field, not name
parsing. Target: ~800 LOC replacing `FolderListGateway` + enumerators + name
policy + most of `app/projects/`'s gateway half. Electron inherits the same
contract — one seam, two clients, as `docs/aplexer-integration.md:26-29` intends.

### 3.3 Terminal rendering + input — KEEP the emulator, REWRITE the bridge, DELETE `-CC`

Keep vendored Termux `terminal-emulator`/`terminal-view` (D4 was right; do not
write a VT engine). Delete the entire control-mode stack: `TmuxClient`,
`ControlEventStream`, layout coalescing, per-pane demux, seed capture,
`RevealStateMachine`, reseed, both watchdogs, `RenderHealCoordinator`,
`ReseedEchoRaceGuard`. New bridge: PTY channel ↔ emulator, `onResize` →
`channel.window-change`, exactly electron's three arrows (~400 LOC replacing
`SshTerminalBridge` 1,444 + `TerminalSurfaceState` 1,812 + the heal layer).

**This reverses D5/D6 — "the single most important call" — and is the plan's
crux.** The honest argument: D5 bought per-pane rendering because tmux's tiled
layout is unreadable on a phone (R3). But the actual daily corpus is
single-pane agent sessions created by `tmuxctl`/`pocketshell agent`; a
single-pane session in attach mode **is** a full-screen pane, with tmux's status
line turned off via the attach wrapper (`-f status=off` or a dedicated tmux
config for PocketShell clients). aplexer sessions have no panes at all — under
Phase C, control mode dies anyway; rewriting the app around attach now aligns
the tmux backend with where the aplexer backend already is. Multi-pane/multi-window
sessions degrade to "attach shows what tmux shows" (pinch/scroll), and the
existing `[wN]`-entry pattern (D30) already models windows as separate attach
targets. If real use surfaces a hard per-pane need, control mode can return as an
additive feature for tmux-backed sessions only — the electron doc makes the same
reservation. Maintainer sign-off required (§6.1).

### 3.4 Reconnect / background-foreground grace — REWRITE, ~1/15th the size

Keep D21's *policy* verbatim (no background work; 90s bounded grace; foreground
service only while live; countdown notification). Replace the mechanism: on
transport error/close → mark host `lost`, keep the terminal screen mounted under a
banner (electron's deliberate non-behavior — scrollback survives), run a fixed
5-value backoff ladder with a give-up, treat foreground-return as an immediate
retry signal. Reattach = re-run the join command; tmux (and aplexer, better)
repaint the screen. No liveness probes, no storm amortizers, no episode budgets,
no `Reattaching` vs `Reconnecting` vs `NetworkLossSuspended` distinctions — those
states exist to protect client-side pane state that no longer exists. Target:
~400 LOC replacing `core-connection` (3,624) + `app/tmux/connection` (3,958) + the
VM's reconnect half. `docs/reconnect-policy.md` stays accurate as written.

### 3.5 Agent detection + conversation view — DELETE detection, REWRITE source

**Detection: DELETE client-side entirely.** No pane-TTY `ps` scans, no cwd-encoded
log discovery, no freshness windows. Badges/state come from data written at launch
and pushed by hooks: `@ps_agent_kind`/`@ps_agent_state` for tmux-backed sessions
(already written by `pocketshell agent` + D26 hooks), `reported_state` via
`a state-report`/`a watch` for aplexer-backed ones — surfaced uniformly through
the `sessions list --json` rows and a lightweight event tail. Sessions started
outside the wrappers get no badge, as electron accepted deliberately.

**Conversation view: KEEP the feature** — unlike desktop (which cut it), the
scrollback problem is the phone's founding use case (D14). REWRITE the source:
`a transcript --follow` for aplexer sessions (server-side normalized events,
pagination cursors, the `@@PS_LINE_TRUNCATED@@` marker already speaks PocketShell);
for tmux-backed sessions, move transcript location/parsing into the host CLI
(`agent_log.py` already exists there) so the app consumes **one** normalized
JSONL stream over exec for both backends. `core-agents` parsers and
`AgentConversationRepository`'s 2,463 lines of retry/windowing/optimistic-send
bookkeeping collapse into a tail-and-render pane (~600 LOC). Reply-in-place =
write to the attached PTY.

### 3.6 Voice input + composer — KEEP core, TRIM the insurance

`core-voice` (1,807), Whisper integration, inline dictation, key bar, and the
composer sheet are mobile-unique, well-scoped, and not in the fragile cluster:
KEEP, ported onto the new session screen. DELETE the delivery-guarantee layer
(`OutboundQueueStore` 2,148, `OutboundDeliveryGuard` 961, agent-submit ack
machinery) — it insures against the old connection core's unreliability; in the
new model a send either writes to the live PTY or fails visibly with the draft
preserved in the composer (drafts persist locally; that part stays).

### 3.7 Usage panel / quota — KEEP AS-IS

`core-usage` (616 LOC) + `pocketshell usage --json` is already the target
architecture (D19: thin client, server-side fetch, zero credentials). Port
unchanged.

### 3.8 Everything else

Hosts/Room storage, port forwarding (`core-portfwd` — mature, pre-dates
PocketShell, owns its own carve-out), file viewer/SFTP, snippets, share-target,
ui-kit: KEEP, port onto the new shell with only the connection-acquisition call
sites changed (they ask the one host connection for a channel instead of taking a
lease). The `git`/`repos`/`env`/`assistant` surfaces are already thin wrappers
over host-CLI calls — KEEP. The maintainer's file-review inbox and jobs UI ride on
the host CLI unchanged.

### 3.9 Size arithmetic (honest rough-out)

Deleted or replaced: core-ssh 7.6k → ~1k; core-tmux 5.3k → 0; core-connection
3.6k + app/tmux/connection 4k + VM 17.6k → ~1.5k (new session screen VM ≤1k with
zero `ForTest` seams — inject dependencies, don't drill test hatches);
core-agents/conversation ~5k → ~1k; outbound insurance ~4k → ~0.3k; gateway/
enumeration ~5k → ~0.8k. **~52k of the hairiest production LOC becomes ~6k**, and
the 42k-line `app/tmux` package ceases to exist as a concept. Test volume shrinks
super-linearly: the 142 incident tests and 439 androidTest files mostly assert
behaviors of deleted machinery.

---

## 4. Sequencing — without breaking the daily driver

Hard constraint: this app is the maintainer's primary tool. Therefore: **never
rewrite in place.** Build the new app as a parallel installable and cut over only
when it wins.

**Step 0 — stop the bleeding (days, before any rewrite):** land the forward fix
for the silent new-session connect failure on the current release (start from
#2453's three unwired gateways; reproduce on device first per D33). One fix, no
sweep. The old app must stay usable throughout the rewrite.

**Step 1 — host-CLI contract (low risk, immediately useful):** implement
`pocketshell sessions list --json` / `attach` / `create` per §3.2 in
`tools/pocketshell`. Pure Python + fixtures, testable against the Docker `agents`
fixture, benefits electron immediately (its 53c04b3 work wants exactly this).
Nothing on the phone changes yet.

**Step 2 — walking skeleton (the proof-of-concept gate):** new Gradle module
`app2/` (own `applicationId`, e.g. `com.pocketshell.next`, so both apps coexist
on the phone) reusing kept shared modules (`ui-kit`, `core-terminal` emulator,
`core-storage`, `core-voice`, `core-usage`). Scope: host list → one SSH connection
→ `sessions list --json` tree → tap → PTY attach → type. **This is where §6.1
gets answered with a real phone in hand:** if attach-mode rendering of his actual
sessions isn't comfortable within ~a week of evenings, stop and reconvene — the
sunk cost is one module and a host-CLI improvement that was worth having anyway.

**Step 3 — reconnect/grace (§3.4), then daily-use trial.** The maintainer starts
using app2 for real work, old app one icon away. Every gap found is a small issue
against a small codebase.

**Step 4 — parity ports in value order:** voice/composer (§3.6) → agent badges +
conversation view (§3.5) → usage → files/ports/snippets/share (§3.8). aplexer
backend rides along from Step 2 for free (it's just different rows/join commands);
flip the default backend whenever Phase-B verification (§6.3) passes — that
decision is now decoupled from the app rewrite.

**Step 5 — the hard cut (D22):** when the maintainer has lived on app2 for ~2
weeks without reaching for the old app, delete `app/` wholesale, rename, migrate
Room data (D22's carve-out: one export/import or shared-database step for hosts/
keys/snippets), and retire the machinery in §5. No coexistence period beyond
this; no compatibility flag.

**Risk ranking:** genuinely risky = attach-mode phone UX (Step 2 gate) and
reconnect feel on cellular (Step 3 trial — this is where the old app's 90 s grace
polish must be matched in *feel*, not mechanism). Safe to gut immediately =
control-mode parser, lease pool, detection stack, outbound insurance — they are
only reachable from the old app, which is frozen except for Step-0-class fixes.
The fallback at every step is the untouched old app on the same phone.

---

## 5. Process fallout

**Dies with the old design** (delete when `app/` deletes, or before):

- `scripts/check-connection-vm-ratchet.sh` + `check-file-size-hygiene.sh`'s VM
  entry — the god object they ratchet is gone.
- D29 (phase/toggle machinery — completed history), #766, the "controller
  authority vs ViewModel residue" language in architecture.md.
- D30's rationale section (window management) — re-decide trivially under attach
  mode ("windows are separate attach targets" survives; the amputation story
  doesn't need retelling).
- D34's storm-signature taxonomy — with no client reconnect ladder, "storm"
  ceases to be a class. The *principle* (headless real-transport proof is valid
  evidence) survives as one line.
- Most of the 439-file androidTest journey fleet, the shard-budget/fixture-cost
  bookkeeping in `tests.yml` (2,219 lines), the quarantine backlog (#2437, #2458,
  #2465, #2466, #2467 describe the old suite's health, not the product's), and
  the majority of the 47 `ci-*` scripts. CI for app2 starts as: unit tests, one
  Docker integration lane against the host CLI, and a *small* emulator journey
  set (connect, attach, reconnect, voice send, conversation) — each journey
  asserting the rendered viewport per the D29 lesson, which absolutely carries
  over.
- The per-incident test pattern: do **not** port 142 `Issue*Test` classes. Port
  the *classes of failure* (G2's spirit) into a handful of property-style tests.

**Keeps, independent of the rewrite** (these are good practice, not scar tissue):

- D22 (hard cuts) — the rewrite is its largest application yet.
- D33 (reproduce → fix → verify → report) and G1/G6/G9/G10 — "verified gone" is
  how the rewrite itself must be validated.
- G3/G5 + `docs/ci-pitfalls.md` — vacuous-green detection is environment
  knowledge, not app knowledge; the catalogue stays true on any codebase.
- D36 stop-the-line/revert-first — but on a small suite it should almost never
  trigger; if it fires monthly on app2, that's a signal the rewrite reimported
  the disease.
- D37 (no waivable release gate), D19/D23 (zero credentials on the phone), D21's
  policy, D3/D4, issue-comment authority, worktree discipline, the
  implementer/reviewer loop itself.
- D31's default-reject stance — though with the incident-test corpus retired, its
  reopened-issue machinery should get dramatically less traffic. If it doesn't,
  same signal as D36.

**The meta-rule:** the apparatus's size tracked the core's fragility. As the core
shrinks ~10×, hold the process to the same ratchet the code had — every gate that
doesn't fire for a quarter gets deleted, per process.md's own "Process Evolution"
clause.

---

## 6. Open questions and risks (maintainer calls — D28 says these are his)

1. **Reversing D5/D6 (drop client-owned `-CC` + per-pane rendering).** The
   load-bearing assumption: his daily sessions are effectively single-pane, so
   attach mode renders them full-screen fine. I could not verify his live session
   shapes from static reading. If he regularly works multi-pane from the phone,
   §3.3 needs the per-pane story sooner than "later, additive." **Decision
   needed before Step 2; Step 2 is designed to answer it empirically.**
2. **Is the triggering bug #2453?** Needs a device repro against the release APK
   (Step 0). If it's something else, the diagnosis §1.6 stands anyway — but the
   Step-0 fix target changes.
3. **aplexer Phase-B freshness.** `docs/aplexer-integration.md` (2026-08-26) says
   reattach quality is "not shippable"; aplexer HEAD appears to have shipped
   screen-repaint attach and `a state-report` since. Someone must detach/reattach
   a real full-screen Codex TUI on aplexer HEAD and check `a watch` state flow
   before the aplexer backend leaves "experimental" in app2. Also confirmed gaps:
   no `watch --since` replay cursor (client must re-snapshot after SSH drops) and
   v4 (not v7) UUIDs.
4. **Conversation view scope.** Desktop cut it entirely; this plan keeps it on
   mobile (D14's founding problem). Confirm that's still wanted before porting —
   it's the single biggest piece of §3.5's remaining client code.
5. **Send-delivery guarantees.** §3.6 deletes the outbound queue/ack machinery in
   favor of "fail visibly, keep the draft." If he wants fire-and-forget voice
   prompts that survive a dead link (dictate in the subway, delivered later),
   that's a *host-side* jobs/queue feature (`t send` / `pocketshell send`
   already exist), not a client guarantee — but it's his call whether v1 of app2
   needs it.
6. **Where app2 lives.** Same repo as a module (shares `shared/` modules and CI
   trivially, but inherits the heavy repo) vs fresh repo consuming `shared/` as
   published artifacts (clean start, dependency friction). Plan assumes same
   repo, new module; cheap to change before Step 2.
7. **The old suite during transition.** Freezing `app/` means its red nightlies
   stop mattering except for Step-0-class fixes. Explicitly suspending the D36
   freeze semantics for the frozen app (while keeping required PR checks for the
   host CLI and app2) needs a one-paragraph process.md amendment — otherwise the
   orchestrator apparatus will keep spending days on #2465-class lane health for
   code that is scheduled for deletion.
8. **tmuxctl's non-contract.** It has zero `--json` anywhere; the plan keeps it
   behind the host CLI (which already parses it), never called from the phone.
   If he'd rather give tmuxctl a `--json` surface than route through
   `pocketshell`, the seam moves but the client design is unchanged.
