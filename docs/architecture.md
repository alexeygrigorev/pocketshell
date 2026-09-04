# Architecture

Describes the app as it exists after the rewrite hard-cut (merge `4eca85a75`,
2026-09-03). The old `app/` module and `shared/core-ssh`, `shared/core-tmux`,
`shared/core-connection`, `shared/core-agents` are **deleted**; `app2` is the
only application module. The rationale for each choice below is in
[rewrite-diagnosis-and-design.md](rewrite-diagnosis-and-design.md); the
task-by-task plan and current scope are in
[rewrite-implementation-plan.md](rewrite-implementation-plan.md).

Where something is planned but not built, this doc says so — it describes the
tree, not the roadmap.

## Module layout

One Gradle build, one app module, ten shared library modules.

```
app2/                      Compose application module (the only app)
shared/
  ├── core-transport/      sshj: one connection per host; exec/PTY/SFTP/forward channels
  ├── core-hostapi/        pure-JVM client for the host `pocketshell` CLI (JSON contract)
  ├── core-terminal/       vendored Termux terminal-emulator + terminal-view, smart selection
  ├── core-portfwd/        AutoForwarder/PortScanner tunnel engine, on core-transport
  ├── core-storage/        Room entities + DAOs (hosts, keys, ports, snippets, …)
  ├── core-usage/          `pocketshell usage --json` models/parsing
  ├── core-voice/          Whisper + audio capture plumbing
  ├── core-assistant/      LLM client + key store (no app2 consumer today — see "Gaps")
  ├── ui-kit/              design-system primitives, theme, components
  └── test-support/        test-only settle pump (never in the APK)
```

| Module | main LOC | What it owns |
|---|---:|---|
| `app2` | ~23.9k | Every screen, ViewModel, service and DI binding. The composition root. |
| `shared/core-transport` | ~2.5k | The **only** production module that knows sshj (it is `api`, so app2's journey oracle can dial the fixture directly). `HostConnection` and its channels. |
| `shared/core-hostapi` | ~1.0k | `kotlin("jvm")`, zero Android, zero transport. Parses the host CLI's JSON; builds its command strings. |
| `shared/core-terminal` | ~14.0k | Vendored `com.termux.*` VT emulator + view (do not refactor). Also carries smart-selection overlays, bracketed paste and a keyboard controller under `com.pocketshell.core.terminal.*`, which **no app2 code imports yet**. |
| `shared/core-portfwd` | ~1.6k | Port discovery + tunnel supervision. `api`-depends on core-transport. |
| `shared/core-storage` | ~2.4k | Room `pocketshell.db`, schema v20. |
| `shared/core-usage` | ~0.6k | Provider quota records. |
| `shared/core-voice` | ~1.8k | Whisper transcription, audio guard. |
| `shared/core-assistant` | ~1.2k | LLM clients + encrypted config store. |
| `shared/ui-kit` | ~5.5k | The visual language ([design-system.md](design-system.md)). |

Dependency shape (`app2/build.gradle.kts`): app2 → ui-kit, core-storage,
core-terminal, core-transport, core-hostapi, core-portfwd, core-voice,
core-usage. Nothing shared depends on app2; only core-portfwd depends on
another shared module (core-transport).

Modularity is a test-time goal, not decoration: core-hostapi runs on the host
JVM in seconds against captured fixtures, and core-terminal's render/selection
tests need neither Docker nor an emulator. Two lanes dial a real sshd, not one —
a transport change must run **both** `:shared:core-transport:integrationTest`
**and** `:shared:core-portfwd:integrationTest`, because core-portfwd's tunnel
suite connects through the same `RealHostConnectionFactory` against the same
`tests/docker/Dockerfile.ssh` image. `scripts/ci-app2-changed-modules.sh`
encodes that fan-out and self-tests it (`transport pulls portfwd and app2 in`).
See [testing.md](testing.md).

## App identity

- Display name: PocketShell
- `applicationId` / namespace: **`com.pocketshell.next`**
- minSdk 26, targetSdk 35, compileSdk 36

`com.pocketshell.next` is the rewrite's side-by-side id, kept so the new client
could be installed next to the old one. Renaming it back to
`com.pocketshell.app` (with a Room data carry-over for the maintainer's
existing install) is task X-3 and **has not happened yet**.

Two activities (`MainActivity`, `ShareActivity`) and two foreground services
(`GraceService`, `ForwardService`). Everything else is Compose.

## Tech stack

| Layer | Choice | Why |
|---|---|---|
| Language | Kotlin | Native Android, best interop |
| UI | Jetpack Compose, single `NavHost` | Real `androidx.navigation`, not the old hand-rolled navigator |
| SSH | sshj | ed25519, modern KEX, actively maintained; confined to core-transport |
| Terminal emulator | Vendored Termux `terminal-emulator` + `terminal-view` | Battle-tested xterm-256color; writing a VT engine is a 6-month detour |
| Session multiplexing | **Server-side**: `pocketshell sessions attach` on a PTY channel | The client speaks no tmux and no aplexer protocol — see below |
| Storage | Room (SQLite), `pocketshell.db` schema v20 | Standard, migrated forward |
| DI | Hilt | Standard |
| Background | Foreground services (grace, forwarding) only | D21: no other background work |
| Speech-to-text | Whisper via OpenAI API, with an Android `SpeechRecognizer` route | [input-methods.md](input-methods.md) |
| Mosh | Not supported | Would need a real UDP path plus `mosh-server`; no fake Mosh mode |

## Three load-bearing decisions

### 1. Attach through the host CLI — no client-side tmux control mode

The client does **not** run `tmux -CC`, parse `%output`/`%layout-change`, demux
panes, or model tmux state. To open a session it opens one PTY channel and runs

```
exec pocketshell sessions attach --hide-status -- '<session name>'
```

built by `HostCliClient.attachCommand` (`shared/core-hostapi`). The host CLI
resolves the name against both backends and `execvp`s the right joiner —
`tmux -S <socket> attach-session -t '=<name>'` for a tmux session, `a attach
<id>` for an aplexer one. The phone never learns a socket path or a UUID. The
`exec ` prefix replaces the wrapping shell so signals and window-size changes
land on the session itself.

This **reverses the old D5/D6 control-mode design**, which is why it is first
here. The old design bought per-pane rendering because tmux's tiled layout is
unreadable on a phone; the actual daily corpus is single-pane agent sessions,
where an attach with the status bar off already *is* a full-screen pane, and
aplexer sessions have no panes at all. What went with control mode: `TmuxClient`,
the control-event stream, layout coalescing, per-pane demux, seed capture,
`RevealStateMachine`, reseed, both watchdogs and the render-heal layer. Multi-pane
sessions now degrade to "attach shows what tmux shows".

### 2. One connection per host, bounded by a channel budget — no lease pool

`core-transport`'s `HostConnection` is one sshj `SSHClient`. Everything is a
channel on it: `exec`, `openPty`, `sftp`, `openPortForward`. There are no
leases, no refcounts and no warm-lease windows; "warm" means the one connection
is still up.

A connection never self-heals. Once `state` reaches `TransportState.Lost` or
`Closed` the instance is spent and the caller dials a new one. That single rule
removes the entire class of "is this handle still good?" bookkeeping the old
core carried.

`ChannelBudget` (capacity 8, against OpenSSH's default `MaxSessions` of 10)
replaces the lease manager. Every channel-opening path takes a permit before the
open request goes out and holds it for the channel's life, so the client never
asks for channel 9. When the budget is full a caller waits up to 5 s and then
gets a typed `ChannelBudgetExhaustedException`; when the *host* refuses an open
(its own limit, or slots it has not yet retired), `openRetryingHostRefusal`
re-asks for a bounded 2 s window and then throws `HostChannelLimitException`. Two
bounded waits and an honest message — deliberately not a retry ladder.

### 3. Keep the vendored emulator, rewrite the bridge

`core-terminal` keeps the vendored Termux `terminal-emulator`/`terminal-view`
Java untouched (its `build.gradle.kts` carries a "do not refactor" rule).
Everything that used to sit between SSH and that emulator — `SshTerminalBridge`,
`TerminalSurfaceState`, the drain schedulers/budgets, the frame coalescer — is
gone. Its replacement is `app2`'s `TerminalPtyBridge`: PTY output → emulator,
emulator input → PTY, one resize path. `TerminalHostView` is a thin `AndroidView`
around `com.termux.view.TerminalView`.

## Connecting to a host

`RealHostConnectionFactory` in `core-transport` is the **single dial site**.
Because there is exactly one, host-key trust cannot be bypassed by an
alternative code path.

1. `ConnectionsRegistry.getOrConnect(hostId)` (`app2/connect/`) — one mutex, a
   `hostId -> HostConnection` map. A stored-but-dead entry counts as absent: it
   is closed best-effort, dropped, and a fresh connection dialled. Two concurrent
   calls for one host return the same instance.
2. The factory dials sshj on its own scope under a 30 s wall-clock bound.
   sshj's handshake parks in a blocking socket read that a coroutine
   `withTimeout` cannot interrupt, so a timeout **disconnects the half-open
   client** from another thread to unpark it.
3. The host-key verifier computes the presented `SHA256:` fingerprint and asks
   `TrustStore.evaluate`. Anything but `Trusted` fails the handshake and surfaces
   as `ConnectResult.NeedsTrust(decision, retry)`. `RoomTrustStore` (app2) backs
   this with core-storage.
4. `AuthSecretResolver` turns an `AuthMaterial.KeyRef(keyId)` /
   `Password(secretRef)` *reference* into actual material. `HostTarget` carries
   only references, so it can be logged and compared without holding a secret.
   `RoomAuthSecretResolver` is app2's implementation.
5. `ConnectGate` wraps the host list with the three outcomes: connected →
   navigate, `NeedsTrust` → `TrustPromptSheet` with the fingerprint, `Failed` →
   an error banner with Retry above a still-live host list.

`ConnectResult.NeedsTrust.retry` is re-wrapped by the registry so a post-trust
retry re-enters `getOrConnect` — otherwise the retry would produce a connection
the registry did not know about.

## Sessions: the host-CLI seam

The server-side `pocketshell` CLI (`tools/pocketshell`, installed on each host)
is the one contract both this client and pocketshell-electron speak. Clients call
`pocketshell …` only — never `tmux`, `tmuxctl` or `a` directly. Ownership split
and per-verb rationale: [aplexer-integration.md](aplexer-integration.md) and the
rewrite plan §B.

`core-hostapi`'s `HostCliClient` has two kinds of method, and the split matters:

- **Verbs that run** — `listSessions`, `createSession`, `listEngines`,
  `listProfiles`. One call is one `exec`. No retry, no polling, no caching; the
  caller owns cadence. Failures come back as `Result.failure(HostCliError)`,
  never a thrown exception: `TooOld` (host CLI below schema 2), `Malformed`,
  `Failed`.
- **Verbs that only build a command** — `attachCommand`. Attach *becomes* the
  session, so it belongs on a PTY channel the caller opens, not on the
  request/response `RemoteExec` seam.

Commands are strings (that is what an SSH channel takes), so every
user-controlled argument goes through `shellSingleQuote` and option lists are
terminated with `--`.

`sessions list --json` schema 2 rows carry `{name, manager, id, workspace, tag,
engine, profile, agent_state, agent_state_source, attached, created_epoch,
activity_epoch}` plus a first-class `errors[]` array. A backend that fails to
enumerate **must** appear in `errors` rather than silently shortening the list;
the tree renders that as a "some sessions may be missing" banner.

### Session tree

`app2/tree/` — `SessionTreeViewModel` lists on enter, on `ON_START` and on
pull-to-refresh; `TreeGrouping` buckets rows by the host's `workspace` string,
compared exactly. Nothing parses a session name, strips a prefix or splits an
aplexer `workspace:tag`: the host already knows the structure, so the phone
buckets rather than deduces. Null/blank workspace lands in an `other` bucket.
Both levels sort most-recently-active first.

`SessionTreeUiState` keeps `groups` and `failure` simultaneously, so a failed
refresh leaves the last known list on screen under an error banner instead of
blanking. `CreateSessionSheet` (folder path only) calls `sessions create --json`;
`created: false` means the session already existed and is reported as "opened
existing", not as an error.

## The terminal screen

`app2/terminal/SessionViewModel` owns the whole lifecycle in one place (the plan
budgets it at ≤600 lines; it is currently 721 and nothing enforces that number —
the repo's only size guard, `scripts/check-file-size-hygiene.sh`, is a 128 KiB
byte ratchet):

```
open(hostId, name)
  → ConnectionsRegistry.getOrConnect(hostId)
  → HostCliClient.attachCommand(name)
  → HostConnection.openPty(command, cols, rows)
  → TerminalPtyBridge.start()
  → SessionUiState.Live(terminal)
```

`attachOnce` is that whole sequence, and the reconnect loop re-runs **the same
function** — there is no second, subtly different attach path.

`SessionUiState` is `Connecting | Live(terminal) | Reconnecting(attempt,
retryInMs, terminal) | Failed(message)`. The live `TerminalSession` rides on the
state rather than being a second ViewModel property, so "there is a terminal to
draw" and "we are attached" cannot disagree.

**What counts as a drop.** A resolved remote exit *status* means the command
really ran and ended (you typed `exit`; `sessions attach` exited 3 for an unknown
name) — the session is over and the screen says so. A channel that ends with no
status, or a `TransportState.Lost`, is the link going away under a session that
is still alive on the host — that is the reconnect case. A deliberate
`HostConnection.close()` on the watched connection also ends with no status and
is explicitly *not* treated as a drop (issue #2477).

Screen chrome (`SessionScreen`): back button, session title with a status
subtitle, the usage glance pill, the terminal surface, the ui-kit `KeyBar`, and
the composer bar. `TerminalGeometry` computes cols/rows from view size and cell
metrics; `onResized` is the single path that resizes both the emulator and the
PTY. `KeyBytes` holds the pure control-byte tables.

## Reconnect and grace

Reconnect policy is one class:

```kotlin
ReconnectController(ladderMs = listOf(0, 1_000, 2_000, 5_000, 10_000))
```

Five rungs then `GiveUp`. No jitter, no episode budgets, no storm classes, no
`Reattaching` vs `NetworkLossSuspended` distinctions — those states existed to
protect client-side pane state that no longer exists. Foreground return and the
user's Retry reset the attempt count to 0.

The terminal is never cleared while reconnecting: tmux/aplexer repaint on
reattach, so the last frame simply stays on screen under the banner until new
bytes arrive. There is deliberately no client-side snapshot, seed or reseed.

Every rung — countdown as well as dial — is gated on `ForegroundSignal`. A
backgrounded app neither counts down nor dials.

Background grace (D21, see [reconnect-policy.md](reconnect-policy.md)) is
`GraceCoordinator` + `GraceService`:

- Leaving the app arms **one** bounded delayed close per live connection
  (`HostConnection.scheduleGraceClose(graceMs)`, a cancellable timer owned by the
  transport, 90 s by default) and starts `GraceService` — a foreground service
  with a `PARTIAL_WAKE_LOCK` and a count-down notification.
- Returning cancels every handle and stops the service.
- Exactly two timers run while backgrounded, both bounded by the same window:
  the transport's delayed close and the coordinator's expiry job, whose only
  purpose is to take the service down at the same instant so no wake lock
  outlives the connection it was held for.

`GraceCoordinator` registers on `ProcessLifecycleOwner` **and** on
`Application.ActivityLifecycleCallbacks`. That is not redundancy:
`ProcessLifecycleOwner` dispatches `ON_STOP` from a 700 ms-delayed runnable, past
the point where Android 12+ still permits `startForegroundService()` — the old
client shipped exactly that and the OS tore its socket down ~4.4 s after every
background (#1595). "Started-activity count reached zero" is the earlier signal;
`enterBackground` is idempotent so whichever arrives first wins.
`Activity.isChangingConfigurations` keeps a rotation from looking like a
background.

## Port forwarding

`app2/ports/ForwardingController` keeps a `hostId -> AutoForwarderSupervisor`
table over `shared/core-portfwd`, and is **the one place in app2 allowed a second
connection to a host**: it dials through `HostConnectionFactory` directly rather
than through `ConnectionsRegistry`. That is D21's forwarding carve-out kept
deliberately — a forward must outlive the interactive connection's grace close,
and sharing the registry's instance would mean backgrounding the terminal
silently killed every tunnel.

Durable intent is the `hosts.enabled` column, so `resumeEnabled` on process start
is a complete answer to "what should be running". Per-port opt-ins are
session-scoped. `ForwardService` is one foreground service with one notification
channel and one serialized `updateNotification(snapshot)` — replacing ~900 lines
of mutation-authority/stop-authority/close-barrier machinery.

Reconnect is uncapped for a *transient* failure — a phone in a tunnel has to
self-heal on its own — but a dial that can never succeed without the user
(`NeedsTrust` on an unconfirmed/rotated host key, a deleted host row) is thrown
as `PermanentConnectionFailure`. The supervisor then goes terminal at once
(`ConnectionState.Lost` + `Event.ConnectionLost`) instead of re-running the SSH
handshake every 5-60 s forever, and the row/notification carry what to do about
it rather than a permanent "Reconnecting" (#2491). `resumeEnabled` calls
`reconnectNow()` on an already-mounted host, so returning to the app after
confirming the key is what un-parks it.

That "what to do about it" reason belongs to the terminal state, so every
surface reads it through the single `HostForwarding.terminalAttention` gate
(`attention` only while `connection == Lost`). Without it the screen and the
notification described the same snapshot differently: a host that parked on an
unconfirmed key, was fixed, un-parked and then hit an ordinary network blip kept
telling a backgrounded user to confirm an already-confirmed key.

## Navigation and screen surface

`app2/nav/Destination` is a sealed class of route templates plus typed builders,
hosted by a real `NavHost`. Arguments are **ids and names only** — no destination
carries a connection, a key path or a passphrase; screens resolve those from
`hostId` through `ConnectionsRegistry`. That is the deliberate break from the old
graph, where a credential-carrying destination was the norm.

| Route | Screen | Purpose |
|---|---|---|
| `hosts` | `HostListScreen` (+ `ConnectGate`) | Saved hosts; tap connects |
| `tree/{hostId}` | `SessionTreeScreen` | Sessions grouped by workspace; create sheet |
| `session/{hostId}/{sessionName}` | `SessionScreen` | The attached terminal, key bar, composer |
| `files/{hostId}?path=` | `FileExplorerScreen` | SFTP browser |
| `file/{hostId}?path=` | `ViewerScreen` | File viewer/editor (text, markdown, image, binary) |
| `ports/{hostId}` | `PortForwardScreen` | Host-scoped port forwarding |
| `usage` | `UsageScreen` | Provider quotas ([usage-panel.md](usage-panel.md)) |
| `settings` | `SettingsScreen` | Terminal, voice, usage, workspace, diagnostics |
| `workspace-roots/{hostId}` | `WorkspaceRootsScreen` | Per-host workspace shortcuts |
| `host-form?hostId=` | `AddEditHostScreen` | Add/edit host (one screen, `-1` = add) |
| `ssh-keys` | `SshKeysScreen` | Generate/import/delete keys |
| `host-qr/{hostId}`, `qr-scan` | QR share/scan | [ssh-qr-import.md](ssh-qr-import.md) |
| `crash-reports` | `CrashReportsScreen` | Local crash reports |

`ShareActivity` is the Android share target. Upload half only: it writes the
shared item to one directory on the host over SFTP, reusing whatever connection
`ConnectionsRegistry` already has. The "inject the uploaded path into the
attached session" half is a separate, unbuilt feature.

`FileViewer` is a separate destination from `Files` on purpose, so the system
back gesture returns to the directory a file was opened from with its own path
argument intact.

## Storage

`shared/core-storage` — Room database `pocketshell.db`, `APP_DATABASE_SCHEMA_VERSION
= 20`, `exportSchema = true`. Entities: hosts, SSH keys, port remappings, port
usage, project roots, snippets, AI API call log, pending transcriptions, command
templates, sent messages. Schema changes ship a migration (D22); a destructive
reset is only an explicit user-confirmed recovery path.

`SnippetEntity` and `CommandTemplateEntity` currently have no app2 screen — the
snippets feature was cut from the rewrite's scope, the tables were not.

## Server-side (`tools/pocketshell`)

The host CLI is a real dependency, not a nicety: without it there is no session
list, no create and no attach. It also serves `usage --json` (the quota panel),
`engines`/`profiles` listings, the recurring-jobs daemon, hooks install, and
tree/env/repo helpers. Install and troubleshooting: [server-setup.md](server-setup.md).

The client uses a subset. `jobs`, `env`, `cards` and `repos` exist server-side
but have **no app2 UI** — those ports were cut from the rewrite's scope.

Agent identity/state belongs to the host for both backends, and app2 only
renders what the host reports. The wire path is live end to end:
`session_enum.aplexer_agent_state` derives `(agent_state, agent_state_source)`
for each aplexer row (a fresh `reported_state` push wins; otherwise a
PTY-recency heuristic picks `working`/`waiting`), `core-hostapi` parses it into
`AgentState?`, and `SessionTreeScreen` maps that to the ui-kit
`AgentStateChip` — a tinted hourglass/spinner/check in every session row's
trailing slot, beside the `AgentKindBadge` monogram. The chip draws **nothing**
for the unknown state, so a row with no host opinion is absent, never wrong.

Two things are absent, and they are not the chip:

- **tmux-backed rows have no state to show.** They carry an explicit
  `agent_state: null` until the cross-repo `APX-ADOPT` capability lets aplexer
  adopt a foreign tmux session, so on a tmux-only host the chip is wired but
  invisible everywhere.
- **There is no conversation/transcript view and no client-side detection.**
  Parsing agent output on the phone was cut with the rest of control mode; the
  chip is the entire agent-awareness surface.

A host CLI older than schema 2 is rejected with a typed `HostCliError.TooOld`
rather than a parse error.

## Testing and CI lanes

Full setup in [testing.md](testing.md) and
[docker-emulator-runbook.md](docker-emulator-runbook.md); the acceptance bars are
in [review-standards.md](review-standards.md).

Five workflows, and **two** independent scheduled cadences.

### `.github/workflows/app2.yml` — the rewrite's modules

Trigger-level `paths:` is the union of every job's interest; per-*job* selection
is the `changes` job's business, since GitHub's own `paths:` is workflow-level.

| Job | Runs |
|---|---|
| `changes` | `scripts/ci-app2-changed-modules.sh` picks the lanes, and self-tests both itself and the journey runner. Always runs — no JDK, no Docker, no emulator. Every uncertain case (unknown base, unreadable diff, dispatch, shared build infra) fails **open** and selects all four lanes. |
| `hostapi-test` | `:shared:core-hostapi:test` (pure JVM, captured fixtures) |
| `transport-test` / `transport-integration` | JVM unit; Docker sshd integration. Not the *only* real-sshd lane — `portfwd-integration` dials the same image through the same factory, so the selector fans a transport change out to both. |
| `portfwd-test` / `portfwd-integration` | tunnel engine: JVM unit against core-transport's `FakeHostConnection`, plus Docker integration that dials real sshd via `RealHostConnectionFactory` |
| `app2-unit` | `:app2:testDebugUnitTest`, **and compiles `androidTest`** so a journey cannot silently rot |
| `app2-journey` | The whole journey set on an emulator against the Docker `agents` + `network-fault-proxy` fixtures. Batched on push/dispatch, never per-PR. |
| `binding-mutations` | `scripts/check-production-binding-mutations.py --run` — curated production-binding mutants, schedule/dispatch **only**. JVM-only and independent of the emulator lane, so its attendance stays visible on a night nothing merged. |

**Cadence 1 — nightly, 02:17 UTC** (`schedule: cron "17 2 * * *"`). This is D37's
requirement that the fault/journey release gate run periodically with no off
switch, inherited from the deleted `nightly-extensive.yml` (same slot, kept
rather than moved). A scheduled run gets its own concurrency group and is never
cancelled; `schedule` carries no `paths:` filter and the selector fail-opens
without a diff base, so the *whole* suite runs rather than whatever a diff picked.

### `.github/workflows/tests.yml` — the repo-wide lanes that outlived the hard cut

`Unit tests` and `Python utility tests (pocketshell)` are the two required PR
checks. `Unit tests` does no testing itself — it is a cheap aggregating gate
that fails unless *all* of `JVM unit tests` (a Debug/Release matrix), `Static
guards`, `CI harness guards`, `Test-selection coverage guards` and `Dex
register-pressure ratchet` succeeded (#2060: those guards used to run serially
in front of the Gradle step). One required check, five real jobs behind it.

`Integration tests (Docker)` runs off-PR and is now only the real-Docker fixture
smokes: its old four-module Testcontainers graph and its per-class
emulator-journey jobs were deleted with the modules they drove, and the one
surviving JVM Docker suite lives in app2.yml's `transport-integration` rather
than being duplicated here. The job key and display name are kept verbatim
because `Integration tests (Docker)` is a named contract outside the file.

**Cadence 2 — three times a day, 00/08/16 UTC**
(`schedule: cron "0 0,8,16 * * *"`). A red run of *this* schedule — not
app2.yml's — is the D36 stop-the-line signal, and it is the "last known-green
scheduled run" both workflows below key off.

### `.github/workflows/full-suite-notify.yml` — D36's red-run tracker

Fires on `workflow_run` once Tests concludes, and only for a **scheduled** run
that **failed** (never a push/PR/dispatch run, never a green one). It files or
updates one tracking issue with the failure signature and the bounded list of
`main` commits since the last known-green scheduled run. Deliberately a separate
workflow: `tests.yml` is under a file-size ratchet with no headroom, and this
logic re-derives everything from the GitHub API rather than needing `needs:`
access into Tests' jobs.

### `.github/workflows/release-emulator-validation.yml` — the release gate

The workflow a release owner checks **first**. `workflow_dispatch` runs
emulator-only release validation on demand. The same workflow also reacts to
Tests' scheduled run concluding *green* and, in `record-validated-rc`, tags that
exact SHA `validated-rc` (#2356) — a commit already green on the full suite and
the emulator gate, which [release.md](release.md) says to tag directly instead of
stabilizing a candidate branch. That marker job is restricted to the
`workflow_run`/schedule path, so a manual dispatch against an arbitrary ref can
never move the release-readiness pointer. `notify-nightly-rc-red` files a
tracking issue on two *consecutive* failures, never a single blip.

Per D37 there is deliberately **no** input that waives the nightly-fault guard;
re-adding one fails `scripts/check-release-gate-bypass-absent.sh` in the Unit
lane.

### `.github/workflows/build.yml` — the release APK

`v*` tags and manual dispatch only. Checks out full history *and* tags, because
both its version step and `scripts/derive-version.sh` need every prior `v*` tag;
a shallow checkout would undercount them and emit a non-monotonic `versionCode`.

### The journey set

Journeys live in `app2/src/androidTest/`, one class per user journey, named
`J<NN><Name>Journey.kt`. The lane runs `:app2:connectedDebugAndroidTest` **once,
unfiltered**, so every class under that tree runs and nothing needs registering
(#2474 — a per-class filter is what would hide cross-journey state pollution).
Present today: J01 connect+trust, J02 session tree, J03 attach+type, J04 create
session, J05 reconnect after drop, J06 background grace return, J07 composer
send, J08 voice dictation, J10 files browse/edit, J11 share upload, J12 usage
panel.

Every journey asserts the **rendered viewport** — the terminal text actually on
screen — not internal state. A journey that only checks internal state can be
green while the visible app is broken; that lesson is already paid for.

Because the lane is unfiltered, the only way to sideline a broken or flaky
journey method is D36's quarantine: an `@Ignore("quarantined: #<issue>, expires
<date> — <reason>")` on the method **plus** a row in
`scripts/journey-quarantine.txt` giving it an owner and a deadline.
`scripts/check-journey-quarantine-expiry.sh` reconciles the two in both
directions — a row without its `@Ignore` and an `@Ignore` without a row both
fail CI — and fails once the expiry passes, so nothing can be silently parked.
The registry is **empty as of current `main`**: #2478's fix retired the last two
rows (J04's create-session methods), and no journey is quarantined today.

`app2.yml`'s jobs are deliberately **not** required checks: a workflow carrying
trigger-level `paths:` cannot be required without wedging every non-matching PR
at "Expected" forever (#2354). Known gap, not papered over — there is no per-PR
journey signal at all, because a subset run is impossible under the
one-unfiltered-run rule and the whole suite is too expensive per PR.

## Gaps and things deliberately absent

Stated plainly so nobody reads a plan as a description:

- **X-3 is unfinished.** `applicationId` is still `com.pocketshell.next`; the
  rename to `com.pocketshell.app` plus the Room data carry-over is pending.
- **X-1, the lean-core release gate, has not been declared passed.** The target
  is a tagged minor release once the confirmed-core feature set holds up in daily
  use.
- **No conversation/transcript view and no client-side agent detection.** Cut
  from scope (rewrite plan, "Scope amendment"); `docs/agent-awareness.md` still
  describes the old client's detection design. The host-driven `AgentStateChip`
  in the session rows *does* exist and is wired (see "Server-side") — it is just
  invisible on a tmux-only host, which reports no state.
- **`shared/core-assistant` has no app2 consumer.** It builds and is tested, but
  the in-app assistant chat was cut; the module is retained, not wired.
- **The lean session-menu chrome is designed but not built.** The planned kebab
  (Reconnect / Files / Port forwarding) and the tap-title quick-switch sheet are
  not in `SessionScreen` yet.
- **No snippets, jobs, env, repos or git screens**, and no home-screen
  widget/tile.
- **The Settings grace-window row is not wired.** `AppSettings.backgroundGraceMillis`
  is stored and rendered, but `AppModule.provideGraceCoordinator` constructs
  `GraceCoordinator` with its 90 s default and never reads it.
- **`docs/decisions.md` has not yet been reconciled with this rewrite** — D5,
  D6, D28, D29, D30 and D34 describe the deleted control-mode/connection-core
  design and still need superseded-with-rationale annotations (rewrite plan task
  X-4, second half).
