# PocketShell rewrite — implementation playbook (for small-model implementers)

Companion to [simplification-plan-2026-09-02.md](simplification-plan-2026-09-02.md)
(the diagnosis and target design — read its §1–§3 only if you need the "why"; this
document deliberately repeats every decision you need, so you should not have to).
Written 2026-09-02 against `pocketshell` HEAD b7fa7713, `aplexer` v0.1.1,
`tools/pocketshell` as of the same commit.

**How to use this document.** Each task in Part D is sized for one implementer
session under this repo's normal process (process.md): one issue, one worktree,
edit + test + status comment, no commit/push. Every architectural decision is
already made here — if a task seems to require a design choice this document does
not state, STOP and report the gap in your status comment instead of choosing.
Tasks name exact files, exact signatures, and exact acceptance checks. "Cite" line
numbers refer to the current repo state at the commit above; if a file has drifted,
match on the quoted identifier, not the number.

**Global rules for every task** (repeat of process.md, binding):

- Work in your own worktree off `origin/main`. Never commit, push, or close issues.
- The new code must never import from `app/` (the old app module) or from
  `shared/core-ssh`, `shared/core-tmux`, `shared/core-connection`,
  `shared/core-agents`. Those are frozen for deletion. If you need something from
  them, the task says exactly what to copy and where.
- No `*ForTest` public seams in new code. Testability comes from constructor
  injection only. A task's tests use fakes passed through the constructor.
- No new `Dispatchers.IO`/`Dispatchers.Main` hardcoded in new classes — every
  class that launches coroutines takes a `CoroutineDispatcher` (or
  `CoroutineScope`) constructor parameter.
- Python work: run `scripts/full-jvm-gate.py` is NOT needed; run
  `cd tools/pocketshell && uv run pytest <named test files>` and report counts.
- Kotlin work: run the named Gradle test task for the touched module only, with
  `--rerun-tasks`, and report the executed-test count (must be > 0 — a
  "0 tests" green is a failure per docs/ci-pitfalls.md).

---

## Scope amendment (2026-09-03, maintainer directive)

The maintainer cut scope after H/T/K/M and U-1..U-3 had already landed on the
`stable` branch, to focus the rewrite on a fast, reliable, testable core before
adding anything back. **This section overrides the task entries below it where
they conflict.** Cut features are deferred, not deleted-forever — they return
once the core is working and polished, most likely re-homed in `aplexer`
("Muxer") rather than rebuilt client-side.

**Cut entirely — do not implement:**
- **U-9** (agent badges + state polling) — no agent-state UI at all for now.
- **U-10** (conversation view, Journey J09) — dropped; H-4's `sessions
  transcript` server command and K-2's `HostCliClient.transcriptCommand` are
  being removed from the codebase in the same round as this amendment.
- **P-3c** (file-review + annotation UI) — the maintainer confirmed he never
  uses it; drop from the file-viewer scope entirely.
- **P-5** (usage/quota panel) — no token/quota UI without agent sessions to
  meter.
- **P-8** (git history/diff/status viewer) — dropped, not used.
- **P-10's `assistant/`** (in-app AI assistant chat) — this is itself "an
  agent," in scope for the same reason U-6's engine picker is cut below.
- **P-10's `costs/`** (AI cost tracking) — same category as the usage panel.

**Trim, don't fully cut — the surrounding feature stays, the agent-specific
slice inside it goes:**
- **U-6** (create session, Journey J04) — drop the engine/profile picker
  entirely; the sheet is folder-path input only (plain shell session name +
  `--cwd`, no `--engine`/`--profile`). `HostCliClient.createSession`'s
  `engine`/`profile` params stay unused by app2 client code, harmlessly.
- **P-1** (composer) — drop `agentcommands/` (the engine-specific slash-command
  autocomplete). Keep the rest of the composer as-is: it is the send path for
  ANY session, not just agent-launched ones.
- **P-7** (thin host-CLI features) — drop `cards/` (agent-session
  checklists/notes) and `repos/` ("clone from GitHub" / repo management —
  not needed right now). Keep `env/` and `jobs/`, which are host utilities
  unrelated to agent launching.
- **P-10** (misc chrome) — before porting `messaging/`, read what it actually
  is; if it's agent-completion notifications, drop it with the rest of this
  list. Keep `crash/` and the trimmed `diagnostics/` core (both are
  infra, not agent surface). `systemsurfaces/` (widget/tile) is fine to keep
  if it only shows session list state, drop if it shows agent status.

**Explicitly still in scope, confirmed by the maintainer:**
- **P-3a/P-3b** (file explorer + viewer, minus P-3c's review/annotation) —
  "keep the file browser."
- **P-4** (port forwarding) — "it's useful."
- **P-2** (voice dictation) — orthogonal to agents, it's an input method, not
  a feature about launching/managing agent sessions.
- Everything already merged and not listed above: H-1/H-2/H-3 (server-side
  `--engine` routing in `sessions create` stays dormant/harmless), all of T
  and K (transport/host-CLI plumbing, engine/profile listing included — no
  UI consumes it, that's fine), M-1..M-3, U-1..U-3, and the terminal core
  (U-4, U-5, U-7, U-8) — the actual point of the app and the reliability half
  of the maintainer's stated goal.

**Consequences for §A.4's journey set:** J09 (`ConversationViewJourney`) and
J12 (`UsagePanelJourney`) are dropped along with U-10/P-5. J10
(`FilesBrowseEditJourney`) drops any review/annotation-specific assertions.
Ten journeys remain: J01–J08, J10, J11.

**Consequences for the task-graph summary:** the original count was 42 tasks
(6 H, 5 T, 2 K, 3 M, 10 U, 12 P counting P-3a/b/c, 4 X). With U-9/U-10 cut (10
→ 8 U) and P-3c/P-5/P-8 cut (12 → 9 P), the plan is now **37 tasks**: 6 H (2
blocked), 5 T, 2 K, 3 M, 8 U, 9 P, 4 X (1 blocked, 1 maintainer-gated).

---

## Part A — Target structure

### A.1 Where the new code lives

Same repository. Two new shared Gradle modules plus one new app module. The old
`app/` module stays untouched and buildable until the final cutover tasks (X-*);
the maintainer keeps using the old app daily throughout.

```
shared/core-transport/            NEW — one SSH connection per host, channels
  build.gradle.kts                android library, sshj + kotlinx-coroutines only
  src/main/java/com/pocketshell/core/transport/
    HostTarget.kt                 value types: host/port/user/auth material refs
    TransportState.kt             sealed interface: Connecting/Connected/Lost/Closed
    ExecResult.kt                 data class (exitCode, stdout, stderr, timedOut)
    HostConnection.kt             THE interface (exec/openPty/sftp/state/close/grace)
    PtyChannel.kt                 interface (output Flow, write, resize, exit)
    SftpChannel.kt                interface (list/read/write/stat/mkdir/rename/delete)
    TrustStore.kt                 interface + TrustDecision sealed class (TOFU)
    GraceHandle.kt                cancellable delayed-close handle
    RealHostConnection.kt         sshj implementation
    RealHostConnectionFactory.kt  dial + host-key verification + auth
  src/testFixtures/.../FakeHostConnection.kt   scripted fake for consumers' tests
  src/test/                      pure-JVM unit tests (fake transport)
  src/integrationTest/           Docker-sshd tests (reuses the existing
                                 core-ssh Docker fixture wiring — see task T-2)

shared/core-hostapi/              NEW — pure JVM (no Android), the host-CLI client
  build.gradle.kts                kotlin("jvm") + kotlinx-serialization-json
  src/main/java/com/pocketshell/core/hostapi/
    RemoteExec.kt                 fun interface RemoteExec (decouples from transport)
    Backend.kt                    enum TMUX/APLEXER (+ unknown-safe parse)
    AgentState.kt                 enum IDLE/WAITING/WORKING (+ source enum)
    SessionRow.kt                 the session model (Part B.1)
    SessionsJson.kt               parse `sessions list --json` incl. errors[]
    HostCliClient.kt              listSessions/createSession/attachCommand/
                                  transcriptCommand/listEngines/listProfiles
    EngineInfo.kt, ProfileInfo.kt models for engines/profiles listings
  src/test/                      pure-JVM tests against captured fixtures

app2/                             NEW app module — applicationId com.pocketshell.next
  build.gradle.kts
  src/main/AndroidManifest.xml
  src/main/java/com/pocketshell/next/
    App.kt                        @HiltAndroidApp, minimal
    MainActivity.kt               single activity, Compose nav host
    nav/Destinations.kt           routes: Hosts, Tree(hostId), Session(hostId,name),
                                  Files(hostId,path), Settings, Usage
    connect/
      ConnectionsRegistry.kt      hostId -> HostConnection (the ONE per-host map)
      RoomTrustStore.kt           TrustStore impl over existing core-storage tables
      TrustPromptState.kt         host-key prompt model surfaced to UI
    hosts/                        ported host list/add-edit (trimmed, task U-2)
    tree/
      SessionTreeViewModel.kt     listSessions -> grouped tree, refresh, create
      SessionTreeScreen.kt        the workspace/session tree UI
      CreateSessionSheet.kt       folder + engine/profile picker
    terminal/
      SessionViewModel.kt         attach lifecycle, ≤600 lines, budget enforced
      SessionScreen.kt            full-screen terminal + chrome
      TerminalPtyBridge.kt        PtyChannel <-> vendored TerminalSession glue
      ReconnectController.kt      pure backoff decision class
      GraceService.kt             D21 foreground service + 90s delayed close
      KeyBarHost.kt               key bar wiring (reuses ui-kit KeyBar)
    composer/                     ported composer minus outbound queue (task P-1)
    conversation/
      TranscriptClient.kt         `sessions transcript --follow` line stream
      TranscriptPane.kt           conversation UI (reuses ui-kit + MarkdownText)
    files/                        ported file viewer/explorer over SftpChannel
    ports/                        ported port-forward UI over core-portfwd
    usage/, settings/, snippets/, share/   ported per P-tasks
  src/test/                      JVM unit tests
  src/androidTest/               the SMALL journey set (see A.4) — target ≤15
                                 classes total, one per user journey, no
                                 Issue-numbered incident tests
```

### A.2 Existing modules: reused / trimmed / frozen

| Module | Disposition |
|---|---|
| `shared/ui-kit` | Reused unchanged (5,525 LOC main). app2 consumes the same primitives/theme. |
| `shared/core-voice` | Reused unchanged. |
| `shared/core-usage` | Reused unchanged. |
| `shared/core-storage` | Reused unchanged (Room: hosts/keys/snippets/etc.). app2 reads the same DB schema; no new entities until cutover. |
| `shared/core-portfwd` | Reused; ONE adaptation task (P-4) swaps its transport acquisition to `core-transport`. |
| `shared/core-terminal` | Split. KEEP: vendored `com.termux.*` Java (13,348 LOC, untouched per its build.gradle.kts:11-27 "do not refactor" rule), `selection/` (~3,338 LOC smart-selection feature), `input/BracketedPaste.kt`. NOT USED BY app2 (frozen with old app, deleted at cutover): `bridge/SshTerminalBridge.kt` (1,444), `bridge/MainThreadDrainScheduler.kt` (263), `bridge/MainThreadDrainBudget.kt` (119), `bridge/TerminalQueryResponseSanitizer.kt` (246), `ui/TerminalSurfaceState.kt` (1,812), `ui/TerminalSurface.kt` (1,132), `ui/RenderFrameCoalescer.kt` (182). app2's `TerminalPtyBridge` + a thin composable wrap `com.termux.view.TerminalView` directly. |
| `shared/core-assistant` | Reused unchanged (ported UI in a late P-task). |
| `shared/core-ssh`, `core-tmux`, `core-connection`, `core-agents` | FROZEN. No new code may depend on them. Deleted in X-3. |
| `app/` | FROZEN except maintainer-blocking bug fixes. Deleted in X-3. |
| `shared/test-support` | Reused where its helpers are transport-agnostic (`SettlePump.kt`); do not pull `TmuxClientOwnership.kt` into app2 tests. |

### A.3 Modularity as a design goal (test time, release time)

The maintainer's stated rationale: "if we make it more modular our tests are
faster and releases are faster." The boundaries above are drawn so that:

- **`core-hostapi` is pure JVM with zero Android and zero transport deps**
  (`RemoteExec` is a one-method fun interface). Its whole suite runs in seconds
  and is exercised against captured fixtures — a change to JSON parsing never
  needs an emulator or Docker.
- **`core-transport` is the only module that knows sshj.** Its Docker
  integration suite is the only lane a transport change must run. Nothing above
  it can break the handshake, and (single dial site) nothing above it can forget
  host-key trust — the #2453 class is structurally gone.
- **`core-terminal` (vendored emulator + selection) has no dependency on
  transport or hostapi** — render/selection tests are JVM-only and already are.
- **`app2` is thin glue**; its unit tests fake `HostConnection` (testFixtures)
  and `RemoteExec`, and its androidTest set is ≤15 journey classes.
- Genuinely independent (buildable/testable in isolation): `core-hostapi`,
  `core-terminal`, `ui-kit`, `core-voice`, `core-usage`, `core-storage`.
  Coupled by design: `core-transport` → sshj; `app2` → everything (it's the
  composition root); `core-portfwd` → `core-transport` after P-4.
- CI consequence (task M-2): per-module test jobs with `paths:` filters, so a
  PR touching only `core-hostapi` runs one JVM job (~2 min), not today's full
  `Unit tests` monolith. The heavy emulator lane runs only when
  `app2/**` or `core-transport/**`/`core-terminal/**` change.

### A.4 The journey set (the whole emulator surface for app2)

Exactly these, one class each, named now so no task invents more:
`J01ConnectAndTrustJourney`, `J02SessionTreeListJourney`,
`J03AttachAndTypeJourney`, `J04CreateSessionJourney`,
`J05ReconnectAfterDropJourney`, `J06BackgroundGraceReturnJourney`,
`J07ComposerSendJourney`, `J08VoiceDictationJourney`,
`J09ConversationViewJourney`, `J10FilesBrowseEditJourney`,
`J11PortForwardJourney`, `J12UsagePanelJourney`.
Each asserts the RENDERED viewport / user-visible outcome (the D29 lesson —
internal state green while the screen is broken is the failure mode this
project already paid for). No `Issue*` incident-test pattern in app2: a
regression fix adds/extends a journey or a class-level unit test, never a
one-incident class.

---

## Part B — Server-side contracts

### B.0 Ownership: aplexer vs `tools/pocketshell` (decided; matches diagnosis doc §2.4)

Rule applied: **aplexer owns session/agent lifecycle and identity** (what runs,
in which session, what state, how to reach it — its actual data model, spec §22);
**`pocketshell` (host CLI) is the single seam both clients call** — it routes
between backends and wraps ordinary host tools. **Clients call only
`pocketshell …`, never `a` or `tmuxctl` directly** (matches
pocketshell-electron's in-flight direction, its commit 53c04b3).

| Function | Owner | Why |
|---|---|---|
| Session enumeration | pocketshell CLI (`sessions list --json`, exists: `tools/pocketshell/src/pocketshell/sessions.py:241`, `session_enum.py:210`) | Only place that sees both backends; aplexer refuses to own tmux. Long-term degenerates to a `a snapshot` pass-through. |
| Session create | pocketshell CLI (`sessions create`) → delegates to `a start` or `tmuxctl create-detached` | Backend routing + folder `.env` merge + trust seed shim live here (docs/aplexer-integration.md A3). |
| Attach/join | pocketshell CLI (`sessions attach`, NEW — task H-2) | Needs backend routing; the client must not know sockets/UUIDs. |
| **Agent identity + state** | **aplexer — single source for BOTH backends (maintainer directive 2026-09-02)** | Native sessions: `a state-report`/`a watch` (shipped). tmux-backed sessions: **requires new aplexer capability APX-ADOPT (§B.5) — does not exist today.** Interim, `sessions list` keeps filling `engine` from launch metadata the helper itself wrote; that interim path is deleted in X-2. |
| Agent transcript | aplexer (`a transcript`) for aplexer-backed sessions; pocketshell CLI `agent_log.py` **fallback** for tmux-backed sessions until APX-ADOPT+transcript-binding covers them | Blocking the conversation view on APX-ADOPT would remove it for every current session; the fallback is server-side and speaks the same event shape (task H-4). Deletion tracked in X-2. |
| Engine/profile discovery | aplexer (`a engines/profiles --json`) with pocketshell CLI presentation overlay | Already shipped (Phase A, #2341); keep. |
| Backend routing policy (which backend a new session uses) | pocketshell CLI config (`~/.config/pocketshell/config.toml`) | Per-host deployment policy spanning both backends; neither backend can own "not me". |
| Usage/quota | pocketshell CLI (`usage --json`) | Out of aplexer scope by its own plan (docs/aplexer-integration.md:168). Unchanged. |
| git/repos/env/jobs/cards/logs | pocketshell CLI | Thin wrappers over host tools (D23/D24/D26/D27). Unchanged. |
| Hooks install | pocketshell CLI (`hooks install`) | Stays; its handlers RETARGET to `a state-report` under APX-ADOPT (task H-6, blocked). |
| Port-forward state | client-side over the one connection | No server piece. |
| Durable folder tree / watched folders | pocketshell CLI (`tree.py`) | Filesystem data, not session lifecycle. |

### B.1 `pocketshell sessions list --json` — schema 2 (task H-1)

Extends the EXISTING payload (`session_enum.py:245 json_payload`,
`LiveSession` at `session_enum.py:44-77`). Response — all keys always present
(no key-if-not-None omission; the Android parser must not need `containsKey`):

```json
{
  "schema": 2,
  "managers": ["tmux", "aplexer"],
  "sessions": [
    {
      "name": "git-pocketshell",
      "manager": "tmux",
      "id": null,
      "workspace": "/home/alexey/git/pocketshell",
      "tag": null,
      "engine": "claude",
      "profile": "zai",
      "agent_state": null,
      "agent_state_source": null,
      "attached": true,
      "created_epoch": 1756700000,
      "activity_epoch": 1756801234
    },
    {
      "name": "myproj:review",
      "manager": "aplexer",
      "id": "0192f3a8-7c1d-7e10-b1aa-3d2f9c001a22",
      "workspace": "/home/alexey/git/myproj",
      "tag": "review",
      "engine": "codex",
      "profile": null,
      "agent_state": "waiting",
      "agent_state_source": "reported",
      "attached": false,
      "created_epoch": 1756710000,
      "activity_epoch": 1756802000
    }
  ],
  "errors": []
}
```

Field rules:
- `name`: string. tmux session name, or `workspace_basename:tag` display name for
  aplexer rows (existing `aplexer_display_name`, `session_enum.py:120`).
- `manager`: `"tmux" | "aplexer"` (existing constants `session_enum.py:39-40`).
- `id`: aplexer UUID string or `null` for tmux rows.
- `workspace`: absolute path string or `null` (tmux rows: `session_path` from the
  enrichment sweep; aplexer rows: record field).
- `engine`: engine id string or `null` (= plain shell).
- `agent_state`: `"idle" | "waiting" | "working" | null`;
  `agent_state_source`: `"reported" | "heuristic" | null`. For `manager:"tmux"`
  both are `null` until APX-ADOPT (§B.5). For aplexer rows, map from the
  snapshot's reported/heuristic state exactly as `a watch` derives it.
- `attached`: boolean (tmux: `session_attached > 0`; aplexer: worker has ≥1
  attached client if the snapshot exposes it, else `false`).
- `errors`: list of `{"manager": "...", "message": "..."}` — **a backend that
  fails to enumerate MUST produce an entry here, never a silently shorter list**
  (this is the #2426 fix carried into the contract).

### B.2 `pocketshell sessions attach NAME [--hide-status]` — NEW (task H-2)

Resolution + exec, never returns on success (`os.execvp`):

1. Enumerate rows (same code path as B.1).
2. Match `NAME` against tmux `name` exactly; else against aplexer display name
   exactly; else as an aplexer `id` prefix (≥8 chars).
3. Not found → print `no session named 'NAME'` to stderr, exit 3.
   Ambiguous → list candidates to stderr, exit 4.
4. aplexer row → `os.execvp("a", ["a", "attach", row.id])`.
5. tmux row → find socket: first try
   `${TMUX_TMPDIR:-/tmp}/tmux-<uid>/tmuxctl-<name>` (the name-derived per-session
   socket, tmuxctl convention), else sweep `${TMUX_TMPDIR:-/tmp}/tmux-<uid>/*`
   with `tmux -S <sock> has-session -t '=<name>'` (the algorithm
   pocketshell-electron ships in `src/shared/attachCommand.ts`). If
   `--hide-status`: run `tmux -S <sock> set-option -t '=<name>' status off`
   first (decision: session-scoped, acceptable side effect — the phone client
   draws its own chrome per D6; desktop attaches show no status for that session
   too, revisit only if the maintainer complains). Then
   `os.execvp("tmux", ["tmux", "-S", sock, "attach-session", "-t", "="+name])`.
6. `tmux`/`a` binary missing → stderr message, exit 127.

The Android client runs this inside the PTY channel as:
`exec pocketshell sessions attach --hide-status '<name>'` (shell-quoted, `exec`
so the wrapper shell does not linger).

### B.3 `pocketshell sessions create --json` — schema 2 (task H-3)

Extends existing `sessions_create` (`sessions.py:469`, today tmux-only, human
output). New options: `--engine E`, `--profile P`, `--backend tmux|aplexer`,
`--json`. Routing decision (in order):

1. Explicit `--backend` wins.
2. Else if `--engine` given: backend = config `[backends].agent`
   (default **"tmux"** until the maintainer flips it post APX-VERIFY — the flip
   is a config change on the host, not a code change).
3. Else: backend = config `[backends].shell` (default "tmux").

tmux arm: existing `tmuxctl create-detached` path; if `--engine`, then
additionally `tmux -S <sock> send-keys -t '=<name>' "pocketshell agent <engine>
[--profile P]" Enter` server-side (the phone stops typing the launch line —
today the client types it, `docs/aplexer-integration.md:126-128`).
aplexer arm: `a start --workspace <cwd> --tag <tag> --engine <engine>
[--profile P] --json`; name/tag derivation reuses the tmux name derivation
(basename, collision suffix).

Response: `{"schema": 2, "name": "...", "manager": "...", "id": "...|null",
"created": true|false}` (`created:false` = already existed, idempotent success).
Failures: nonzero exit + `{"schema":2, "error": "..."}` on stdout.

### B.4 `pocketshell sessions transcript NAME [--follow] [--last N]` — NEW (task H-4)

Output: JSONL, one normalized event per line, aplexer's UnifiedEvent shape.
Resolution as B.2. aplexer row → exec `a transcript <id> [--follow] [--last N]`.
tmux row → serve the same event shape from the existing
`agent_log.py` locate+parse path (this fallback is the sanctioned interim —
§B.0; it dies in X-2). **First implementation step is fixture capture**: run
`a transcript --last 20` for a real claude and codex session on the dev box and
commit the captured lines as test fixtures; pin the pocketshell-side emitter to
byte-compatibility with them (the pocketshell-electron lesson: four parser bugs
shipped through an unpinned helper contract, its deleted docs/ANALYSIS.md §3).

### B.5 APX-ADOPT — required NEW aplexer capability (cross-repo dependency, NOT a task in this playbook)

Maintainer directive: agent identity/state has ONE source — aplexer — for both
backends. For tmux-backed sessions aplexer must be able to track sessions it did
not launch. **This does not exist today**: aplexer's spec §27 V1 verb list has no
adopt/observe verb; `a state-report` and `a whoami` self-identify via the
`APLEXER_SESSION_ID` env var that only aplexer-native workers export
(`aplexer/src/bin/a.rs:1852`, session records in `src/lib.rs:527-535`); nothing
in spec.md §22 or the README covers foreign tmux sessions.

What it needs to do (for the maintainer to schedule in the aplexer repo):

- `a adopt --tmux-session NAME --workspace DIR --engine E [--profile P] --json`
  → creates a session record (`hosted: false`, no PTY worker, no cgroup) with a
  UUID; idempotent per tmux name.
- `a forget` / `a prune` handle adopted records (prune when the tmux session is
  gone).
- `a state-report --session <id> <state>` accepted WITHOUT `APLEXER_SESSION_ID`
  for adopted sessions, persisting `reported_state`/`reported_state_at_ms` as for
  native ones.
- `a snapshot --json` includes adopted rows, distinguishable (`hosted: false`).
- Optional, enables retiring the B.4 fallback: adopted records may carry the
  transcript binding (cwd-derived) so `a transcript` serves them.

Pocketshell-side tasks blocked on this are marked **[blocked: APX-ADOPT]** in
Part D; everything else proceeds without it. Until it lands, tmux rows carry
`agent_state: null` and the tree shows engine badges without live state for
tmux-backed sessions (aplexer-backed ones get full state from day one).

### B.6 Python signatures to add/change (`tools/pocketshell/src/pocketshell/`)

- `session_enum.py`:
  - extend `LiveSession` (dataclass, line 44) with fields
    `profile: Optional[str] = None`, `agent_state: Optional[str] = None`,
    `agent_state_source: Optional[str] = None`, `attached: bool = False`,
    `activity_epoch: Optional[int] = None`.
  - `to_payload()` (line 58): emit ALL keys always (schema-2 rule), keep the old
    key-omission behavior behind `def to_payload(self, schema: int = 1)` so the
    schema-1 caller (`json_payload`) is unchanged until H-1 flips it.
  - `sessions_from_aplexer_snapshot(payload)` (line 173): map
    `reported_state`/heuristic fields into the new fields.
  - NEW `def enumeration_errors() -> list[dict]` plumbing: `enumerate_live_sessions`
    (line 210) returns `(rows, errors)`; every swallowed backend failure appends
    `{"manager": ..., "message": ...}`.
  - `json_payload(sessions, errors)` (line 245): `schema: 2`, always-present keys.
- `sessions.py`:
  - `sessions_list` (line 241) passes errors through; `_list_envelope`
    (line 135) carries them.
  - NEW `@sessions_group.command("attach")` per §B.2 (~80 lines incl. socket
    sweep helper `_find_tmux_socket(name) -> Optional[str]`).
  - `sessions_create` (line 469): add options + routing per §B.3; extract
    `def _route_backend(engine, backend_flag, config) -> str`.
  - NEW `@sessions_group.command("transcript")` per §B.4 (~60 lines + reuse of
    `agent_log.py`).
- NEW `config.py`: `def load_config() -> dict` reading
  `~/.config/pocketshell/config.toml` (`tomllib`), section `[backends]`,
  defaults `{"agent": "tmux", "shell": "tmux"}`. No other config keys yet.
- Tests: `tools/pocketshell/tests/test_session_enum.py`,
  `tests/test_sessions_attach.py`, `tests/test_sessions_create_routing.py`,
  `tests/test_sessions_transcript.py` — pytest, fixtures under
  `tests/fixtures/aplexer/` captured from the real host (never hand-typed —
  the ci-pitfalls "handwritten header drifts" rule).

---

## Part C — Kotlin interfaces (the shapes small models fill in)

### C.1 `shared/core-transport`

```kotlin
package com.pocketshell.core.transport

data class HostTarget(
    val hostId: Long,            // Room host row id (core-storage)
    val hostname: String,
    val port: Int,
    val username: String,
    val auth: AuthMaterial,      // sealed: KeyRef(keyId) / Password (existing store)
)

sealed interface TransportState {
    data object Connecting : TransportState
    data object Connected : TransportState
    data class Lost(val cause: String) : TransportState
    data object Closed : TransportState
}

data class ExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

interface HostConnection {
    val target: HostTarget
    val state: kotlinx.coroutines.flow.StateFlow<TransportState>
    suspend fun exec(command: String, timeoutMs: Long = 15_000): ExecResult
    suspend fun openPty(command: String, cols: Int, rows: Int, term: String = "xterm-256color"): PtyChannel
    suspend fun sftp(): SftpChannel                       // cached per connection
    fun scheduleGraceClose(graceMs: Long): GraceHandle    // D21: delayed close, cancellable
    suspend fun close()
}

interface PtyChannel {
    val output: kotlinx.coroutines.flow.Flow<ByteArray>   // completes on channel EOF
    suspend fun write(bytes: ByteArray)
    suspend fun resize(cols: Int, rows: Int)
    val exit: kotlinx.coroutines.Deferred<Int?>           // remote exit status if known
    suspend fun close()
}

interface GraceHandle { fun cancel(); val deadlineMs: Long }

sealed interface TrustDecision {
    data object Trusted : TrustDecision
    data class Unknown(val fingerprintSha256: String) : TrustDecision
    data class Mismatch(val storedSha256: String, val presentedSha256: String) : TrustDecision
}
interface TrustStore {
    suspend fun evaluate(target: HostTarget, presentedSha256: String): TrustDecision
    suspend fun recordTrusted(target: HostTarget, sha256: String)
}

sealed interface ConnectResult {
    data class Connected(val connection: HostConnection) : ConnectResult
    data class NeedsTrust(val decision: TrustDecision, val retry: suspend () -> ConnectResult) : ConnectResult
    data class Failed(val message: String, val cause: Throwable?) : ConnectResult
}
interface HostConnectionFactory {
    suspend fun connect(target: HostTarget, trust: TrustStore): ConnectResult
}
```

`RealHostConnection` implementation notes (task T-2): one `net.schmizz.sshj.SSHClient`
per instance; keep-alive 15 s (sshj `KeepAliveProvider.KEEP_ALIVE`); connect
timeout 30 s wall-clock on `Dispatchers.IO` (copy ONLY the bounded-dial idea from
`SshLeaseManager.kt:36-63` — a coroutine `withTimeout` cannot interrupt sshj's
blocking handshake read, so run the dial in a cancellable job whose cancellation
disconnects the half-open transport; do NOT copy the lease/refcount machinery).
`state` flips to `Lost` from sshj's disconnect listener; `Lost` is terminal for
this instance — reconnect means the factory dials a NEW `HostConnection`
(electron's model: `connectionId` survives in the UI, the transport object does
not self-heal).

### C.2 `shared/core-hostapi`

```kotlin
package com.pocketshell.core.hostapi

fun interface RemoteExec { suspend fun exec(command: String, timeoutMs: Long): ExecOutcome }
data class ExecOutcome(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

enum class Backend { TMUX, APLEXER, UNKNOWN }              // UNKNOWN = forward-compat parse
enum class AgentState { IDLE, WAITING, WORKING }
enum class AgentStateSource { REPORTED, HEURISTIC }

data class SessionRow(
    val name: String,
    val backend: Backend,
    val id: String?,
    val workspace: String?,
    val tag: String?,
    val engine: String?,
    val profile: String?,
    val agentState: AgentState?,
    val agentStateSource: AgentStateSource?,
    val attached: Boolean,
    val createdEpoch: Long?,
    val activityEpoch: Long?,
)

data class SessionsListing(
    val sessions: List<SessionRow>,
    val errors: List<BackendError>,   // NEVER dropped: UI must render a partial-list banner
)
data class BackendError(val manager: String, val message: String)

class HostCliClient(
    private val exec: RemoteExec,
    private val binary: String = "pocketshell",
) {
    suspend fun listSessions(): Result<SessionsListing>           // `sessions list --json`
    suspend fun createSession(
        name: String?, cwd: String, engine: String?, profile: String?,
    ): Result<CreatedSession>                                     // `sessions create --json ...`
    fun attachCommand(name: String, hideStatus: Boolean = true): String
        // returns: exec pocketshell sessions attach [--hide-status] '<shell-quoted name>'
    fun transcriptCommand(name: String, follow: Boolean, last: Int): String
    suspend fun listEngines(): Result<List<EngineInfo>>           // `engines list --json` (existing)
    suspend fun listProfiles(): Result<List<ProfileInfo>>         // `profiles list --json` (existing)
}
```

Parsing: kotlinx-serialization with `ignoreUnknownKeys = true`; `schema` must be
`>= 2` for the new fields, reject `schema: 1` with a versioned error telling the
user to update the host CLI (the bootstrap upgrade flow already exists —
`app/src/main/java/com/pocketshell/app/projects/HostPocketshellUpgrade.kt` is the
old-app reference for messaging).

### C.3 `app2` terminal core

```kotlin
package com.pocketshell.next.terminal

class ReconnectController(
    private val ladderMs: List<Long> = listOf(0L, 1_000L, 2_000L, 5_000L, 10_000L),
) {
    sealed interface Decision {
        data class RetryAfter(val delayMs: Long, val attempt: Int) : Decision
        data object GiveUp : Decision
    }
    fun decide(attempt: Int): Decision =
        if (attempt < ladderMs.size) Decision.RetryAfter(ladderMs[attempt], attempt)
        else Decision.GiveUp
}
// That is the WHOLE reconnect policy. No episode budgets, no jitter, no storm
// classes. Foreground-return and user "Retry" reset attempt to 0.

sealed interface SessionUiState {
    data object Connecting : SessionUiState
    data object Live : SessionUiState
    data class Reconnecting(val attempt: Int, val nextRetryAtMs: Long) : SessionUiState
    data class Failed(val message: String) : SessionUiState  // manual Retry stays available
}

class TerminalPtyBridge(
    private val pty: com.pocketshell.core.transport.PtyChannel,
    private val emulator: com.termux.terminal.TerminalSession, // vendored, unchanged
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    fun start()                       // pty.output -> emulator.write; emulator input -> pty.write
    suspend fun resize(cols: Int, rows: Int)  // emulator.updateSize + pty.resize (single path)
    fun stop()
}

class SessionViewModel /* @HiltViewModel */ (
    private val connections: com.pocketshell.next.connect.ConnectionsRegistry,
    private val hostCliFactory: (com.pocketshell.core.transport.HostConnection) -> com.pocketshell.core.hostapi.HostCliClient,
    private val reconnect: ReconnectController,
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher,
) : androidx.lifecycle.ViewModel() {
    // public surface — the ENTIRE surface; adding beyond this needs a doc change:
    val uiState: kotlinx.coroutines.flow.StateFlow<SessionUiState>
    fun open(hostId: Long, sessionName: String)
    fun sendBytes(bytes: ByteArray)       // composer/key-bar path; throws nothing —
                                          // failure flips uiState, draft is preserved upstream
    fun retryNow()
    fun onResized(cols: Int, rows: Int)
    override fun onCleared()
}
// HARD BUDGET: SessionViewModel.kt ≤ 600 lines. If a task cannot fit its change,
// the task is wrong — report, don't grow.
```

Attach flow inside `open()` (pseudocode a task fills in):
`connections.get(hostId)` → if absent/Lost, dial via factory (surfacing
`NeedsTrust` to the trust prompt) → `openPty(hostCli.attachCommand(name), cols, rows)`
→ `TerminalPtyBridge.start()` → `uiState = Live`. On `pty.output` completion or
`TransportState.Lost`: `uiState = Reconnecting`, loop `reconnect.decide(attempt)`
with `delay`, re-run the SAME attach flow (fresh connection object). tmux/aplexer
repaint the screen on reattach — there is deliberately NO client-side screen
preservation/reseed logic; the emulator keeps showing the last frame under the
reconnect banner until new bytes arrive.

### C.4 Grace (D21, mechanism only)

```kotlin
package com.pocketshell.next.terminal

class GraceCoordinator(
    private val connections: ConnectionsRegistry,
    private val clock: () -> Long,
    private val graceMs: Long = 90_000L,
) : androidx.lifecycle.DefaultLifecycleObserver {
    // ProcessLifecycleOwner-registered.
    // onStop: for each Connected host -> handle = conn.scheduleGraceClose(graceMs);
    //         start GraceService (foreground svc + PARTIAL_WAKE_LOCK + countdown notif).
    // onStart: cancel all handles; stop GraceService. No timers while backgrounded
    //          beyond the single delayed close (D21 stands unchanged).
}
```

Port the notification/countdown behavior from the old service (find it via
`grep -rn "setChronometerCountDown" app/src/main` — decisions.md:29 documents the
contract) but NOT the live-client registry machinery: app2 has at most one live
session screen, so the service tracks `ConnectionsRegistry`, not a client list.

---

## Part D — Task list

Prefixes: **H** host CLI (Python) · **T** core-transport · **K** core-hostapi ·
**M** app2 skeleton/CI · **U** core UI slices · **P** feature ports · **X**
cutover. Tasks marked **[blocked: APX-ADOPT]** wait on the aplexer capability in
§B.5 (cross-repo; the maintainer schedules it) — everything else proceeds.

**Buildability invariant:** after every task, `scripts/assemble-debug.sh` still
builds the OLD app unchanged, and (from M-1 on) `./gradlew :app2:assembleDebug`
builds the new one. No task touches `app/` except the X tasks.

**Test-retirement policy (applies to every U/P task):** app2 does NOT port the
old suite. For each ported feature the task lists the old test *areas* being
retired and writes fresh tests at class level. The old tests stay in place
(they still guard the frozen old app) until X-3 deletes them with `app/`.
Reason on record: 142 incident-named classes + 286 `ForTest` seams defend
implementation details of machinery the rewrite deletes; porting them would
re-import the calcification the diagnosis doc §1.5(6) documents.

### Phase H — host CLI contract

---

**H-1 — `sessions list --json` schema 2**
- Depends on: none.
- CREATE: `tools/pocketshell/tests/test_session_enum_schema2.py`.
- MODIFY: `tools/pocketshell/src/pocketshell/session_enum.py` — `LiveSession`
  dataclass (line 44): add the five fields from §B.6; `to_payload` (line 58):
  schema-2 always-present-keys mode; `sessions_from_aplexer_snapshot`
  (line 173): map `reported_state`/`reported_state_at_ms`/heuristic into
  `agent_state`/`agent_state_source`; `enumerate_live_sessions` (line 210):
  return `(rows, errors)` where every caught backend failure appends
  `{"manager","message"}` instead of being swallowed; `json_payload`
  (line 245): emit `{"schema":2, "managers", "sessions", "errors"}`.
  `tools/pocketshell/src/pocketshell/sessions.py` — `_list_envelope`
  (line 135) and `sessions_list` (line 241): thread errors through; also
  update `_try_daemon_sessions_list` (line 112) so the daemon path carries the
  same envelope.
- DELETE: nothing.
- Steps: (1) extend dataclass + payload; (2) extend aplexer mapping using a
  REAL captured `a snapshot --json` fixture — capture it on this dev box
  (`a snapshot --json > tests/fixtures/aplexer/snapshot.json`) with at least
  one agent session running; (3) wire errors; (4) update existing schema-1
  assertions in `tools/pocketshell/tests/` that H-1 breaks (expected — list
  them in your status comment).
- Tests: fixture-driven — tmux-only host, aplexer-only host, both, aplexer
  binary present-but-failing (asserts an `errors` entry AND that tmux rows
  still appear — the #2426 contract), snapshot row with `reported_state`,
  row without (heuristic/null).
- Accept: [ ] all new tests pass; [ ] `uv run pytest tests/` green overall;
  [ ] `pocketshell sessions list --json | python -m json.tool` on this dev box
  shows schema 2 with both managers; [ ] a failing backend produces an
  `errors` entry (prove by pointing `APLEXER_BIN` at `/bin/false`).
- Non-goals: do NOT touch `sessions create`/`resume`; do NOT add `agent_state`
  for tmux rows (stays `null` until APX-ADOPT); do NOT change the human table.

---

**H-2 — `sessions attach` command**
- Depends on: H-1.
- CREATE: `tools/pocketshell/tests/test_sessions_attach.py`.
- MODIFY: `tools/pocketshell/src/pocketshell/sessions.py` — new
  `@sessions_group.command("attach")` implementing §B.2 exactly; new module
  helper `_find_tmux_socket(name: str) -> Optional[str]` (name-derived path
  first, sweep with `has-session` second).
- Steps: implement resolution (reuse `enumerate_live_sessions`), the socket
  finder, `--hide-status`, and the four exit codes (0-via-exec, 3, 4, 127).
  Make exec-ing testable: route through `def _exec(argv)` that tests
  monkeypatch (the module already uses this pattern —
  `_resolve_tmuxctl_binary` docstring, `sessions.py:52-60`).
- Tests: monkeypatched `_exec` capture — aplexer row → `["a","attach",<id>]`;
  tmux row with derived socket present → tmux argv with `-S` + `=`-prefixed
  target; `--hide-status` prepends the `set-option` call; unknown name →
  exit 3; ambiguous prefix → exit 4.
- Accept: [ ] tests green; [ ] on this dev box
  `pocketshell sessions attach <a real tmuxctl session>` lands you in it
  (run inside a throwaway `tmux -L pocketshell-h2-test` outer session, never
  the default socket — AGENTS.md tmux-socket rule).
- Non-goals: no `--json` on attach (it execs); no create-if-missing (that is
  `sessions create`, H-3); do not touch tmuxctl.

---

**H-3 — `sessions create --json` + backend routing + `config.py`**
- Depends on: H-1.
- CREATE: `tools/pocketshell/src/pocketshell/config.py` (§B.6);
  `tools/pocketshell/tests/test_sessions_create_routing.py`.
- MODIFY: `tools/pocketshell/src/pocketshell/sessions.py` — `sessions_create`
  (line 469): add `--engine/--profile/--backend/--json` and the §B.3 routing
  via new `_route_backend`; tmux+engine arm adds the server-side
  `send-keys "pocketshell agent <engine> …"` step; aplexer arm builds
  `a start --workspace <cwd> --tag <tag> --engine <e> [--profile p] --json`
  reusing the tag/name derivation `_resume.tmuxctl_create_argv` relies on.
- Steps: config loader (tomllib, defaults `agent="tmux"`, `shell="tmux"`);
  routing function (pure, unit-tested); JSON envelope
  `{"schema":2,"name","manager","id","created"}`; idempotency (existing
  session → `created:false`, exit 0).
- Tests: routing matrix (explicit flag beats config beats default; engine vs
  no-engine); argv construction for both arms (monkeypatched `_exec`);
  `--json` envelope; existing-session idempotency.
- Accept: [ ] tests green; [ ] on the dev box a
  `pocketshell sessions create h3-scratch --cwd /tmp --json` round-trips and
  a second call returns `created:false`; clean up with
  `tmux -S <its socket> kill-session` or `t kill h3-scratch` (never
  `kill-server`).
- Non-goals: do not flip the aplexer default (config stays `tmux` — the
  maintainer flips it on the host after APX-VERIFY, diagnosis doc §6); no
  folder `.env` handling changes; do not touch `agents.py`.

---

**H-4 — `sessions transcript` command**
- Depends on: H-1.
- CREATE: `tools/pocketshell/tests/test_sessions_transcript.py`,
  `tools/pocketshell/tests/fixtures/aplexer/transcript-claude.jsonl`,
  `…/transcript-codex.jsonl` (CAPTURED from `a transcript` on this dev box,
  never hand-typed — ci-pitfalls.md "handwritten fixture drifts" rule).
- MODIFY: `tools/pocketshell/src/pocketshell/sessions.py` — new
  `@sessions_group.command("transcript")` per §B.4: aplexer row → exec
  `a transcript <id> [--follow] [--last N]`; tmux row → emit the SAME
  UnifiedEvent line shape from the existing `agent_log.py` reader (add a
  thin adapter function `unified_events_from_agent_log(...)` in
  `agent_log.py`; map its event model onto the captured fixture's field
  names — the fixtures are the contract, the adapter conforms to them).
- Tests: aplexer routing argv; tmux fallback produces lines whose JSON keys
  are a subset-match of the captured aplexer fixture keys (schema-compat
  assertion); `--last N` windows; unknown session → exit 3.
- Accept: [ ] tests green; [ ] `pocketshell sessions transcript <real aplexer
  session> --last 5` and `<real tmux claude session> --last 5` both emit
  parseable JSONL on the dev box.
- Non-goals: no `--json` flag (output IS JSONL); no OpenCode support in the
  fallback beyond what `agent_log.py` already does; no client work.

---

**H-5 — [blocked: APX-ADOPT] adopted-session state in `sessions list`**
- Depends on: H-1 + aplexer APX-ADOPT shipped (§B.5).
- MODIFY: `session_enum.py` — when a snapshot row is `hosted:false` and its
  tmux name matches a tmuxctl row, MERGE them (one row, `manager:"tmux"`,
  aplexer `id` + `agent_state` filled); `sessions create`/`agents.py` launch
  path — call `a adopt` after creating a tmux-backed agent session.
- Accept: [ ] tmux-backed agent session rows carry non-null `agent_state`
  sourced from aplexer; [ ] no duplicate rows.
- Non-goals: do not remove the `@ps_agent_kind` write yet (X-2 does).

---

**H-6 — [blocked: APX-ADOPT] hooks retarget to `a state-report`**
- Depends on: H-5.
- MODIFY: `tools/pocketshell/src/pocketshell/hooks.py` (1,263 LOC) — the
  generated per-engine handlers report state via
  `a state-report --session <adopted-or-native id> <state>` instead of (in
  addition to, until X-2) the tmux `@ps_agent_state` option write. D26's
  merge-don't-clobber install semantics unchanged.
- Accept: [ ] a Claude Stop hook firing in a tmux-backed session updates
  `agent_state` in `sessions list --json` within one poll.

### Phase T — `shared/core-transport`

---

**T-1 — module skeleton, interfaces, fake**
- Depends on: none.
- CREATE: `shared/core-transport/build.gradle.kts` (android library, java 17,
  deps: sshj (same coordinate as `shared/core-ssh/build.gradle.kts` uses),
  kotlinx-coroutines, `testFixtures` enabled); every interface/value file
  named in §A.1 with the §C.1 shapes verbatim;
  `src/testFixtures/java/com/pocketshell/core/transport/FakeHostConnection.kt`
  — scripted fake: enqueue `ExecResult`s per command-prefix, scripted PTY
  byte frames, controllable `state` flow;
  `src/test/java/...` `FakeHostConnectionTest.kt`.
- MODIFY: `settings.gradle.kts` — add `:shared:core-transport`.
- Steps: copy the §C.1 code as-is; implement the fake; NO sshj code yet.
- Tests: fake behaves per its own contract (exec scripting, PTY frame replay,
  state transitions).
- Accept: [ ] `./gradlew :shared:core-transport:test --rerun-tasks` green with
  count > 0; [ ] old app still assembles.
- Non-goals: no real transport; no consumers.

---

**T-2 — `RealHostConnection`: dial, auth, host-key trust, exec**
- Depends on: T-1.
- CREATE: `RealHostConnection.kt`, `RealHostConnectionFactory.kt`,
  `src/integrationTest/.../RealHostConnectionIntegrationTest.kt`.
- MODIFY: `shared/core-transport/build.gradle.kts` — add the
  `integrationTest` source set, copying the wiring from
  `shared/core-ssh/build.gradle.kts` (it already runs Docker-sshd suites —
  find the source-set + task block there and replicate; the Docker fixture on
  port 2222 is the existing `agents` fixture, docs/testing.md).
- Steps: (1) factory dials sshj with 30 s bound implemented as a
  manager-owned cancellable job (see §C.1 notes — read
  `shared/core-ssh/src/main/java/com/pocketshell/core/ssh/SshLeaseManager.kt:36-63`
  for WHY, copy the idea not the code); (2) host-key verifier calls
  `TrustStore.evaluate`; `Unknown`/`Mismatch` → `ConnectResult.NeedsTrust`
  with a `retry` lambda that re-dials after `recordTrusted`; (3) `exec` =
  one sshj session channel, capture stdout/stderr, wall-clock timeout →
  `ExecResult(timedOut=true)`, never throws for nonzero exit; (4) disconnect
  listener flips `state` to `Lost`.
- Tests (integration, against Docker sshd): connect+exec echo round-trip;
  nonzero exit surfaces in `exitCode` not an exception; unknown host key →
  `NeedsTrust` then trusted retry connects; wrong stored key → `Mismatch`;
  killing the container connection flips `state` to `Lost` (this is the
  D34-sanctioned headless real-transport proof class).
- Accept: [ ] integration suite green, count > 0, named tests present;
  [ ] no `Dispatchers.IO` literal outside the constructor default.
- Non-goals: no PTY, no SFTP, no grace (T-3/T-4/T-5); no lease/refcount
  anything.

---

**T-3 — PTY channel**
- Depends on: T-2.
- CREATE: `PtyChannelImpl.kt` (internal), integration test class.
- Steps: `openPty` allocates an sshj shell/exec channel with PTY
  (`term`, cols/rows), `output` = cold-to-hot Flow reading the channel
  stream in a scope-owned reader job, `write` serialized, `resize` →
  window-change, `exit` completes on channel close.
- Tests (integration): run `stty size` in a PTY at 91×41 and assert the
  output bytes contain `41 91`; resize mid-session then re-run `stty size`;
  EOF completes `output` and `exit`.
- Accept: [ ] green with counts; [ ] `output` backpressure is suspend-based
  (no unbounded buffer — use `Flow` with a bounded channel, capacity 64
  frames).
- Non-goals: no terminal emulator involvement; no reconnect.

---

**T-4 — SFTP channel**
- Depends on: T-2.
- CREATE: `SftpChannelImpl.kt`, integration tests.
- Steps: wrap sshj's SFTP client, cached per connection (`sftp()` returns the
  same instance), method set exactly: `list(path)`, `stat(path)`,
  `read(path, maxBytes): ByteArray` (over-size → typed error),
  `write(path, bytes)`, `mkdir`, `rename`, `delete`. Signatures mirror the
  subset `FileExplorerViewModel`/`FileViewerViewModel` actually use — grep
  their SFTP call sites first and list them in your status comment.
- Accept: [ ] integration round-trip on Docker sshd (write→list→read→rename→
  delete); [ ] size cap enforced.
- Non-goals: no resumable upload (old `ResumableUpload.kt` is share-upload
  scope, ported only if P-9 finds it necessary — note this in the comment).

---

**T-5 — grace handle + state surface**
- Depends on: T-2.
- CREATE: `GraceHandleImpl.kt`, unit + integration tests.
- Steps: `scheduleGraceClose(ms)` starts ONE delayed close job on the
  connection's scope (virtual-time-testable dispatcher via constructor);
  `cancel()` aborts it; a second call replaces the first; `close()` is
  idempotent and flips state `Closed`.
- Tests: virtual-clock unit tests (fires at deadline; cancel prevents;
  replace re-arms); integration: closed connection's channels fail fast.
- Accept: [ ] green; [ ] no timer runs after `cancel()` (assert via
  injected test scheduler — the D21 no-background-work contract).
- Non-goals: no Android service (that's U-8); no notification.

### Phase K — `shared/core-hostapi`

---

**K-1 — module, models, sessions parser**
- Depends on: H-1 (for a captured real fixture; the §B.1 example unblocks you
  meanwhile).
- CREATE: `shared/core-hostapi/build.gradle.kts` (kotlin("jvm") +
  kotlinx-serialization), all §A.1 model files with §C.2 shapes,
  `SessionsJson.kt`, tests + `src/test/resources/fixtures/sessions-list-*.json`.
- MODIFY: `settings.gradle.kts`.
- Steps: data classes per §C.2; parser: `ignoreUnknownKeys=true`, `schema<2`
  → typed `HostCliTooOld` error, unknown `manager` string → `Backend.UNKNOWN`
  row kept (forward-compat), `errors` array mapped, malformed row → listing-
  level error not an exception.
- Tests: fixture matrix mirroring H-1's (both managers, errors-present,
  unknown manager, schema 1 rejection).
- Accept: [ ] `:shared:core-hostapi:test --rerun-tasks` green, count > 0;
  [ ] module has zero Android deps (verify no `android` plugin, no
  `androidx` imports).
- Non-goals: no exec implementation (RemoteExec stays an interface); no
  engines/profiles yet.

---

**K-2 — `HostCliClient` verbs + engines/profiles models**
- Depends on: K-1, H-2, H-3, H-4.
- CREATE: `HostCliClient.kt`, `EngineInfo.kt`, `ProfileInfo.kt`, tests,
  fixtures for `engines list --json` and `profiles list --json` captured
  from the dev box (the existing shapes — see
  `docs/aplexer-integration.md:98-99` for the profiles envelope
  `{profiles:[{name,engine,config_dir,default}]}`).
- Steps: implement §C.2 methods over `RemoteExec`; `attachCommand` builds
  `exec pocketshell sessions attach --hide-status '<name>'` with
  single-quote shell escaping (escape embedded `'` as `'\''` — write the
  escaper as a pure function `shellSingleQuote(String)` with its own tests);
  `createSession` parses the §B.3 envelope; `transcriptCommand` per §B.4.
- Tests: command strings byte-asserted; parse paths per verb; quoting edge
  cases (`it's`, spaces, unicode).
- Accept: [ ] green with counts; [ ] every public method listed in §C.2
  exists with that exact signature.
- Non-goals: no polling/caching (callers own cadence); no retry logic (one
  call = one exec; the UI decides on failure).

### Phase M — app2 skeleton + CI

---

**M-1 — app2 module skeleton**
- Depends on: none (parallel with H/T/K).
- CREATE: `app2/build.gradle.kts` (applicationId `com.pocketshell.next`,
  Hilt, Compose, deps: ui-kit, core-storage, core-transport, core-hostapi,
  core-terminal), `app2/src/main/AndroidManifest.xml`, `App.kt`,
  `MainActivity.kt` (empty Compose scaffold with ui-kit theme),
  `nav/Destinations.kt` (§A.1 routes as a sealed class — copy the pattern,
  NOT the content, of `app/src/main/java/com/pocketshell/app/nav/AppDestination.kt`),
  one trivial `app2/src/test` smoke test.
- MODIFY: `settings.gradle.kts`.
- Accept: [ ] `./gradlew :app2:assembleDebug` builds an installable APK that
  opens to the scaffold; [ ] old app unaffected; [ ] both apps install
  side-by-side on one device/emulator.
- Non-goals: no screens, no DI beyond `@HiltAndroidApp`.

---

**M-2 — CI lanes for the new modules**
- Depends on: M-1 (+ T-1/K-1 existing).
- CREATE: `.github/workflows/app2.yml` — jobs: `hostapi-test`
  (`:shared:core-hostapi:test`), `transport-test` (`:shared:core-transport:test`),
  `transport-integration` (Docker, reuse the existing compose fixture steps
  from `tests.yml`'s `integration` job at `.github/workflows/tests.yml:1138`
  — copy the service-startup steps, not the whole job), `app2-unit`
  (`:app2:testDebugUnitTest`), each with `paths:` filters so only touched
  modules run.
- MODIFY: nothing in `tests.yml` (the old pipeline is frozen scope).
- Accept: [ ] a PR touching only `shared/core-hostapi/**` triggers exactly
  the `hostapi-test` job from this workflow; [ ] all jobs green on a branch
  touching everything.
- Non-goals: no emulator lane yet (U-4 adds it when there is a journey to
  run); do not mark jobs required (the maintainer flips branch protection
  when app2 becomes primary).

---

**M-3 — connections registry + Room trust store + trust prompt model**
- Depends on: T-2, M-1.
- CREATE: `app2/src/main/java/com/pocketshell/next/connect/ConnectionsRegistry.kt`
  (`hostId -> HostConnection`, `suspend fun getOrConnect(hostId): ConnectResult`,
  `fun current(hostId): HostConnection?`, `suspend fun closeAll()` — one
  mutex, no refcounts), `RoomTrustStore.kt` implementing
  `core-transport.TrustStore` over the EXISTING trusted-key storage — find
  the entity via `grep -rn "trustedHostKeySha256" shared/core-storage app/src/main`
  and reuse that table read/write, `TrustPromptState.kt`; unit tests with
  `FakeHostConnection` + in-memory Room.
- Accept: [ ] two `getOrConnect` for one host share one connection;
  [ ] `Lost` connection → next `getOrConnect` dials fresh; [ ] unknown key
  surfaces `NeedsTrust` and `recordTrusted`+retry connects.
- Non-goals: no UI (U-2); no grace (U-8).

### Phase U — core UI slices (each ends with a working, installable app2)

---

**U-1 — host list screen**
- Depends on: M-1.
- CREATE: `app2/.../hosts/HostListScreen.kt`, `HostListViewModel.kt`, tests.
- Steps: read hosts from the existing `core-storage` DAO (same DB the old app
  writes — app2 reads it read-only for now; find the DAO via
  `grep -rn "interface HostDao" shared/core-storage`); render ui-kit host
  cards (name, user@host, tap → `Tree(hostId)` route). NO status dots, NO
  bootstrap probes, NO update banners in this task.
- Old tests retired later with app/: the `app/src/test` HostListViewModel
  suites (they test the probe/status/update machinery app2 does not have).
- Accept: [ ] app2 shows the maintainer's real host list; [ ] VM unit test
  with in-memory Room; [ ] `HostListViewModel.kt` ≤ 200 lines.
- Non-goals: add/edit host UI (P-6 scope brings it over with settings); QR
  (P-9); delete/edit actions.

---

**U-2 — connect flow + trust prompt (Journey J01)**
- Depends on: M-3, U-1.
- CREATE: `connect/TrustPromptSheet.kt`, wiring in `MainActivity`/nav;
  `app2/src/androidTest/.../J01ConnectAndTrustJourney.kt`.
- Steps: tapping a host runs `ConnectionsRegistry.getOrConnect`; `NeedsTrust`
  → sheet showing SHA-256 fingerprint with Trust/Reject; `Failed` → error
  banner with retry; success → navigate to Tree route (empty screen stub).
- Journey J01 (Docker sshd fixture): fresh install → add fixture host row
  directly via Room test seed → tap → trust prompt visible with fingerprint
  → Trust → tree route shown. Asserts RENDERED text, not VM state.
- Accept: [ ] J01 green on a real emulator via
  `scripts/connected-test.sh --suffix iapp2 :app2:connectedDebugAndroidTest`;
  [ ] rejecting trust leaves no stored key (Room assert).
- Non-goals: no auto-connect-on-launch; no passphrase biometric flow yet
  (P-6 ports key handling; until then use an unencrypted test key).

---

**U-3 — session tree screen (Journey J02)**
- Depends on: K-2, U-2, H-1.
- CREATE: `tree/SessionTreeViewModel.kt`, `tree/SessionTreeScreen.kt`,
  `tree/TreeGrouping.kt` (pure: `List<SessionRow> -> List<WorkspaceGroup>`,
  grouped by `workspace` field, sorted by `activityEpoch` desc), unit tests,
  `J02SessionTreeListJourney.kt`.
- Steps: on enter + pull-to-refresh + `ON_START`, call
  `HostCliClient.listSessions()` (RemoteExec adapter =
  `connection.exec(cmd, timeout)`); render groups → session rows (name,
  engine badge via ui-kit `AgentKindBadge`, attached dot, relative time);
  `errors` non-empty → ui-kit `Banner` "some sessions may be missing:
  <managers>" (the #2426 contract made visible); row tap → Session route.
- Grouping is by the JSON `workspace` field ONLY — no name parsing (the old
  `SessionNamePolicy`/`HostTreeModel` inference is exactly what we deleted;
  if `workspace` is null, group under "other").
- Old tests retired later: `FolderList*` suites, `HostTreeModel` tests,
  `Issue2377*`/`Issue2426*` enumeration classes (their production machinery
  is gone; the errors-banner journey covers the user-visible class).
- Accept: [ ] real dev-box tree renders both tmux and aplexer sessions;
  [ ] J02 green (fixture: Docker host with the pocketshell CLI installed —
  extend the `agents` fixture image if the CLI is missing; if that image
  can't be extended in this task, J02 may target the dev box pattern used by
  existing Docker journey tests — copy the harness choice from one of the
  `*DockerTest` classes' setup and cite it); [ ] `TreeGrouping` pure tests
  incl. null-workspace and UNKNOWN-backend rows.
- Non-goals: no create (U-6); no agent STATE (U-9); no swipe actions.

---

**U-4 — terminal screen: attach, render, type (Journey J03) — THE crux slice**
- Depends on: T-3, K-2, U-3.
- CREATE: `terminal/SessionViewModel.kt` (§C.3 surface, ≤600 lines),
  `terminal/SessionScreen.kt`, `terminal/TerminalPtyBridge.kt`,
  `terminal/TerminalHostView.kt` (AndroidView wrapper around vendored
  `com.termux.view.TerminalView` + `com.termux.terminal.TerminalSession` —
  look at how the OLD app instantiates them:
  `grep -rn "TerminalSession(" shared/core-terminal app/src/main | head`, and
  copy ONLY the constructor/callback wiring, none of the surface-state
  machinery), unit tests with `FakeHostConnection`, `J03AttachAndTypeJourney.kt`.
- Steps: `open()` per §C.3 attach flow; bridge pumps `pty.output` →
  `TerminalSession.write` on the VM dispatcher and emulator keyboard input →
  `pty.write`; hardware/IME keys through the vendored view's existing
  `TerminalViewClient` hooks; on `pty.exit` → back to tree with a toast.
- Journey J03 (Docker): attach to a fixture session, assert the rendered
  viewport contains the fixture shell prompt, type `echo pocketshell-j03`,
  assert the string renders. Viewport assertion reads the EMULATOR's screen
  text (the vendored `TerminalBuffer` transcript API — find it via
  `grep -rn "getTranscriptText" shared/core-terminal`), not VM state (D29
  lesson).
- Old tests retired later: the entire `app/src/test` + androidTest tmux/
  reveal/seed/heal/watchdog surface (their machinery does not exist in app2).
- Accept: [ ] J03 green on emulator; [ ] maintainer-runnable: attach to a
  REAL dev-box session from app2 and interact (report a screen recording
  path in the status comment — this is the §6.1 D5/D6 evidence);
  [ ] `SessionViewModel.kt` ≤ 600 lines, zero `ForTest` members.
- Non-goals: NO reconnect (U-7), NO grace (U-8), NO resize polish (U-5), NO
  key bar (U-5), NO conversation (U-10). Failure of the transport here just
  shows Failed + back.

---

**U-5 — resize, key bar, IME insets**
- Depends on: U-4.
- CREATE: `terminal/KeyBarHost.kt`; extend `SessionScreen`; unit tests for
  the cols/rows computation (pure function of view size + cell metrics);
  extend J03 with a rotation/IME assertion.
- Steps: on layout/IME change compute cols/rows from the vendored renderer's
  cell size, call `vm.onResized` (single path: emulator resize + pty
  resize — never two owners); key bar = ui-kit `KeyBar` with D18's 8 slots
  (Esc/Tab/Ctrl/Alt/arrows) writing control bytes via
  `TmuxInputEncoding`-equivalent pure functions — copy the byte tables from
  `app/src/main/java/com/pocketshell/app/tmux/TmuxInputEncoding.kt` (162
  lines, pure, no tmux dependency despite the name) into
  `terminal/KeyBytes.kt` with its tests.
- Accept: [ ] `stty size` in a live session tracks rotation and IME
  open/close; [ ] Ctrl+C interrupts a running `sleep 100`; [ ] key-byte
  tables unit-tested against the copied cases.
- Non-goals: no chord palette (D18 stands); no font settings (P-6).

---

**U-6 — create session (Journey J04)**
- Depends on: U-3, H-3, K-2.
- CREATE: `tree/CreateSessionSheet.kt`, VM additions, `J04CreateSessionJourney.kt`.
- Steps: FAB on tree → sheet: folder text field with recent-folders
  suggestions (recents = distinct `workspace` values from the last listing —
  NO remote directory autocomplete in v1), optional engine/profile pickers
  fed by `listEngines`/`listProfiles` (hide disabled/unavailable — the
  #2439 class gets a unit test: an engine present-but-unavailable in the
  fixture must still render when `enabled:true`… copy the exact expected
  semantics from the issue title: enabled+available ⇒ shown; write the test
  so a dropped-row regression reddens); submit → `createSession` → refresh →
  navigate into the new session.
- Accept: [ ] J04: create a shell session in the Docker fixture, land
  attached; [ ] engine picker fixture test per above; [ ] creating a
  duplicate name surfaces `created:false` as "opened existing".
- Non-goals: no memory-cap UI (`--mem` stays a host-side policy); no
  aplexer-backend toggle in UI (config-driven, §B.3).

---

**U-7 — reconnect (Journey J05)**
- Depends on: U-4.
- CREATE: `terminal/ReconnectController.kt` (§C.3, verbatim), its unit test,
  banner UI in `SessionScreen`, `J05ReconnectAfterDropJourney.kt`.
- Steps: on `TransportState.Lost` or `pty.output` completion while
  `uiState==Live`: keep the last frame rendered, show the Reconnecting
  banner (attempt count + countdown), loop decide→delay→re-attach (FRESH
  connection via registry — never reuse a Lost one); `GiveUp` → Failed with
  manual Retry; foreground-return and Retry reset attempt=0. Terminal
  content is NOT cleared — tmux/aplexer repaint on reattach.
- Journey J05 (Docker + toxiproxy if available in the fixture — check
  `docs/testing.md` and reuse the network-fault fixture from `tests.yml`'s
  integration job; else kill the sshd container): live session → cut link →
  banner appears → restore link → session repaints and typing works.
  Asserts: banner text rendered; post-reconnect `echo j05-back` renders.
- Old tests retired later: all `core-connection` suites, reconnect-storm/
  ladder/liveness classes, `PushResumeDeadSocket*`, `ConnectionLog*` — their
  subject matter (ladders, storms, probes, journals) has no successor by
  design (diagnosis doc §3.4).
- Accept: [ ] J05 green; [ ] ReconnectController unit test pins the exact
  ladder and GiveUp at attempt 5; [ ] no reconnect attempt fires while
  backgrounded (unit test with lifecycle fake).
- Non-goals: no jitter/episode budgets/storm classes — if a reviewer asks
  for them, the answer is the diagnosis doc §3.4; no network-change
  listener (foreground-return + the ladder cover it in v1).

---

**U-8 — background grace + foreground service (Journey J06)**
- Depends on: U-7, T-5.
- CREATE: `terminal/GraceCoordinator.kt` (§C.4), `terminal/GraceService.kt`
  (foreground service + PARTIAL_WAKE_LOCK + countdown notification via
  `setChronometerCountDown` — port the notification block from the old
  service: locate with `grep -rn "setChronometerCountDown" app/src/main`),
  manifest entries, unit tests (virtual clock), `J06BackgroundGraceReturnJourney.kt`.
- Steps: §C.4 verbatim; 90 s default from a single constant in
  `GraceCoordinator`; notification tap → MainActivity.
- Journey J06: attach → home button → notification visible with countdown →
  reopen within grace → session usable WITHOUT a reconnect banner ever
  rendering (assert its absence — the D21/#1123 contract); separately, a
  unit test drives the virtual clock past 90 s and asserts `close()` ran and
  the service stopped.
- Accept: [ ] J06 green; [ ] no wakelock/service alive after grace expiry
  (assert via service state in the test); [ ] D21 policy text in
  `docs/reconnect-policy.md` still accurate (read it; report if not).
- Non-goals: no per-session grace settings UI; no changes to the OLD app's
  grace path.

---

**U-9 — agent badges + state polling**
- Depends on: U-3.
- CREATE: `tree/AgentStateRefresher.kt` (foreground-only poll: refresh
  listing every 20 s while the tree screen is resumed — `LifecycleResumeEffect`,
  no timer otherwise), badge/state-chip rendering on rows (ui-kit
  `AgentStateChip` + `AgentKindBadge` exist — see
  `shared/ui-kit/.../SessionAgentState.kt`), unit tests.
- Steps: aplexer-backed rows get live `agent_state` chips now; tmux-backed
  rows render the engine badge only, with state chips arriving via H-5
  [blocked: APX-ADOPT] — the UI code is IDENTICAL either way (it renders
  whatever the row carries), so nothing here blocks.
- Accept: [ ] state chip on a real aplexer session flips within one poll of
  the agent going idle→working; [ ] zero polling while backgrounded (test).
- Non-goals: no client-side detection of ANY kind — no `ps`, no log
  freshness, no tmux options (maintainer directive, §B.0); no push channel
  (a later `sessions watch` proxy is out of v1 scope).

---

**U-10 — conversation view (Journey J09)**
- Depends on: U-4, H-4, K-2.
- CREATE: `conversation/TranscriptClient.kt` (opens a PTY-less exec channel
  running `transcriptCommand(follow=true)`, parses JSONL lines into the
  ported event model, exposes `Flow<ConversationEvent>` + windowing state,
  ≤250 lines), `conversation/TranscriptPane.kt` + a Terminal/Conversation
  tab row in `SessionScreen`; PORT `app/src/main/java/com/pocketshell/app/conversation/`
  (13 files, 2,014 LOC — the audit found it renderer-pure; copy, fix
  imports, drop `ConversationDiagnostics.kt`); unit tests over the H-4
  fixtures; `J09ConversationViewJourney.kt`.
- Steps: tab visible when the row's `engine != null`; default tab
  Conversation for agent sessions (the #818 decision carries over,
  docs/agent-awareness.md:92-107 — including "never a mid-session yank");
  reply box writes to the session PTY via `vm.sendBytes`.
- Old tests retired later: `AgentConversationRepositoryTest*` (its 2,463-line
  subject is deleted — audit: 1,900 lines of pane→file guessing replaced by
  the server stream), `core-agents` parser suites (parsing moved server-side
  behind H-4's fixtures).
- Accept: [ ] J09: fixture agent session shows parsed turns and live-tails a
  new message; [ ] events render from BOTH an aplexer-native and a
  tmux-backed fixture session (the H-4 fallback proves the shape parity);
  [ ] scroll-back pagination via `--last N` re-invocation works.
- Non-goals: no optimistic-send bookkeeping (a sent reply renders when the
  transcript echoes it; the composer keeps the draft until then); no
  full-text search in v1; no OpenCode (H-4 non-goal).

### Phase P — feature ports (order = daily-use value; each is copy → trim → rewire → test)

Port mechanics common to all P-tasks: copy the named files into app2's
package, fix imports, replace every lease/exec acquisition with
`ConnectionsRegistry.current(hostId)?.exec(...)` (or the `RemoteExec`
adapter), delete the listed dead files, and do NOT copy any `*ForTest`
member — replace each with constructor injection or delete the test that
needed it.

---

**P-1 — composer (minus outbound queue) + send path (Journey J07)**
- Depends on: U-4.
- PORT (copy+trim) from `app/src/main/java/com/pocketshell/app/composer/`:
  `PromptComposerSheet.kt` (2,361), `MarkdownText.kt` (716),
  `ComposerDraftStore.kt` (361), `ComposerDraftPersistence.kt` (120),
  `PromptComposerRecordingSurfaces.kt` (511),
  `AndroidSpeechRecognitionDelegate.kt` (307), attachment staging set
  (`PromptComposerAttachments.kt` 295, `PromptAttachmentStager.kt` 289,
  `AttachmentTransferProgress.kt` 261, `AttachmentRetentionPolicy.kt` 233,
  `PromptComposerAttachmentStaging.kt` 122,
  `PromptComposerAttachmentProgressUi.kt` 119), `UnifiedComposer.kt` (202),
  `PromptComposerImeAnchorPolicy.kt` (149), `ComposerSheetChrome.kt` (140),
  `SlashCommandAutocomplete.kt` (106) + `app/.../agentcommands/` (353,
  audit: keep whole).
- DO NOT PORT (they die with the old core; leave in app/ until X-3):
  `OutboundQueueStore.kt` (2,148), `PromptComposerQueueBanners.kt` (1,260),
  `PromptComposerOutboundSend.kt` (879), `OutboundAttachmentSidecarStore.kt`
  (561), `PromptComposerOutboundQueueSelection.kt` (420),
  `ComposerQueueDiagnostics.kt` (287), `PromptComposerOutboundDrain.kt`
  (286), `OutboundQueueLifecycleCoordinator.kt` (252),
  `PromptComposerRetryGate.kt` (176), `OutboundDrainOwnership.kt` (169),
  `PromptComposerOutboundMapping.kt` (157).
- CREATE: `app2/.../composer/ComposerViewModel.kt` NEW (≤400 lines) —
  replaces `PromptComposerViewModel.kt` (3,585; 288 outbound/queue
  references make it a trim-in-place non-starter). Send contract: build
  text → `sessionVm.sendBytes(text + "\r")` → on `Live` clear draft, on
  anything else KEEP draft + show "not delivered — session offline" chip.
  That chip + preserved draft is the ENTIRE delivery story (diagnosis §3.6).
- CREATE: `J07ComposerSendJourney.kt` — compose, send, assert the text
  renders in the terminal viewport; kill the link first and assert the
  draft survives and the chip renders.
- Accept: [ ] J07 green; [ ] attachments stage to the host over SFTP (T-4)
  and inject the remote path like the old flow; [ ] no file in app2 matches
  `*Outbound*` or `*Queue*`.
- Non-goals: voice (P-2); offline-queued delivery (the voice offline
  transcription queue in P-2 is a DIFFERENT, kept feature).

---

**P-2 — voice stack lift (Journey J08)**
- Depends on: P-1.
- PORT VERBATIM (audit: zero connection imports, "lift, do not rewrite"):
  all of `app/src/main/java/com/pocketshell/app/voice/` (9 files, 3,363) +
  `app/src/main/java/com/pocketshell/app/session/InlineDictation.kt` (1,570
  — it was misfiled; it lands in `app2/.../voice/`), + `di/VoiceModule.kt`
  bindings (413).
- CREATE: `J08VoiceDictationJourney.kt` — mic tap → (fake recognition
  delegate injected) transcript lands in composer; offline: transcript
  queues in `PendingTranscriptionStore` and delivers on connectivity return
  (this store is a KEPT feature — the subway dictation case, diagnosis §6).
- Accept: [ ] J08 green; [ ] real dictation works on the maintainer's phone
  (report); [ ] no edits beyond imports/DI in the ported files (diff-stat
  proves the lift).
- Non-goals: no Whisper prompt-engineering changes; no coachmark redesign.

---

**P-3 — files: explorer + viewer (Journey J10)** *(split into 3 sessions)*
- **P-3a explorer**: port `fileexplorer/` (2 files, 1,538) onto
  `SftpChannel`; retire its `generation`/`reconcile` seams (audit bucket
  ~270). Accept: browse/upload/download against Docker sshd; J10 covers
  browse+open.
- **P-3b viewer core**: port `fileviewer/` MINUS review+annotation:
  `FileViewerScreen.kt` (split it — the 2,536-line screen becomes
  `ViewerScreen.kt` + per-type renderer files, each ≤600),
  `FileViewerViewModel.kt` trimmed of the dispatcher test seams
  (audit: the two `internal var` dispatcher fields with ~40-line KDocs
  become constructor params), `MarkdownParser/View/Model`, `FileViewerType`,
  `BoundedImageDecoder`, `PdfPageRenderer`, `AudioPlayerController`,
  `OpenFileTabStrip`, `RemotePathResolver`, `FileWorkspace*`.
- **P-3c review + annotation**: port `FileViewerReviewUi.kt` (752),
  `ReviewModel.kt` (253), `ImageAnnotationModel.kt` (334),
  `AnnotationRenderer.kt` (149) + the screen's draw handlers. These are
  real product surface electron lacks (audit) — keep whole.
- CREATE: `J10FilesBrowseEditJourney.kt` (browse → open text file → edit →
  save → re-read shows the edit).
- Accept: [ ] J10 green; [ ] review YAML still lands in
  `~/inbox/pocketshell/reviews/` per process.md's schema (byte-compare
  a submitted fixture against one produced by the old app).
- Non-goals: no new file types; no Monaco/CodeMirror-style editor upgrade.

---

**P-4 — port forwarding (Journey J11)**
- Depends on: T-2, M-3.
- PORT: `PortForwardPanelScreen.kt` (639), `PortTable.kt` (127),
  `InterestingPortFilter.kt` (88), `ForwardingGlyph.kt` (61),
  `ShowAllPortsStore.kt` (68), indicator VMs (198), ~400 lines of
  `PortForwardPanelViewModel.kt` (audit: `load`/`togglePort`/`startPort`/
  `toAvailableTunnels`); `shared/core-portfwd` tunnel engine reused.
- CREATE: `app2/.../ports/ForwardService.kt` NEW (~150 lines): foreground
  service, ONE notification channel, single serialized
  `updateNotification(snapshot)` on the main thread (the audit's finding #4:
  this replaces ~900 lines of `ForwardingNotificationMutationAuthority` +
  `StopAuthority` + `CloseBarrier` + observe-generation fencing — do not
  port ANY of those); resume-on-foreground = re-read enabled forwards from
  Room in `onStart`.
- DECISION (audit finding #3): forwards keep dialing their OWN connection via
  `core-portfwd` (as today — `PortForwardConnector.kt:37` never used the
  lease pool), because a forward must outlive the terminal connection's
  grace close. The "one connection per host" rule applies to the
  interactive surface; forwarding owns its carve-out exactly as D21 already
  grants it.
- DO NOT PORT: the session-port-attribution subsystem (`SessionPortMention*`,
  `SessionPorts*` — 1,150 LOC scraping pane bytes; no pane byte stream
  exists in app2). If the maintainer misses it, it returns as a host-CLI
  feature (`pocketshell ports --json` reading `ss -ltnp` — file an issue
  then, not now).
- CREATE: `J11PortForwardJourney.kt` — enable a forward to the Docker
  fixture, hit `localhost:<port>` from the test, see the notification.
- Accept: [ ] J11 green; [ ] notification updates never crash on rapid
  toggle (the old race class — cover with a rapid-toggle UI test);
  [ ] app2 contains no `*Authority*`/`*Barrier*` forwarding files.

---

**P-5 — usage panel (Journey J12)**
- Depends on: U-3.
- PORT: `UsageScreen.kt` (866), `UsageFormat.kt` (341), `UsageGlancePill.kt`
  (293), `UsageResetBanner.kt` (127), `UsageUiModels.kt` (136),
  notifications set (560); `shared/core-usage` unchanged.
- CREATE: `usage/UsageFetcher.kt` NEW (~120 lines): one
  `pocketshell usage --json` exec per visible host, foreground-only,
  stale-while-revalidate REMOVED (fetch-on-view + manual refresh only —
  audit bucket "accreted"); replaces `UsageScheduler.kt` (564) +
  `UsageViewModel.kt`'s lease fan-out (611 → new VM ≤200).
- CREATE: `J12UsagePanelJourney.kt` (fixture host serves a canned
  `usage --json`; panel renders provider rows).
- Accept: [ ] J12 green; [ ] glance pill renders in session screen.
- Non-goals: no notification-threshold changes; no new providers.

---

**P-6 — settings + hosts add/edit + keys/QR**
- Depends on: U-1.
- PORT: settings surface minus dead fields — from the audit, DROP:
  `tmuxOnAttachByDefault`, `outboundDeliveryAuthority` (+ its enum,
  `SettingsModels.kt:102`), `agentSubmitEnterDelayMs`,
  `DiagnosticsSection`'s connection-journal recorder rows; KEEP: terminal
  (font/theme), voice, assistant, usage, workspace roots, about. Port
  hosts add/edit (`AddEditHostScreen.kt` 817 + VM 397 — fixing the known
  #2456/F1 identity bug per the audit's `bind(Long?)` note), `SshKeys*` +
  biometric (818), QR stack (1,170 — codecs port verbatim).
- Accept: [ ] add/edit/delete host works (regression test for the F1
  edit-then-add overwrite bug — red on the old VM logic, green on new);
  [ ] key generate + biometric-gated passphrase + QR export/import round-
  trip on device; [ ] `backgroundGraceMillis` exposed as one setting row
  feeding `GraceCoordinator`.
- Non-goals: no settings redesign; no import-conflict dialog machinery
  unless add/edit hits it naturally.

---

**P-7 — thin host-CLI features: env, jobs, cards, repos**
- Depends on: U-2 (exec available).
- PORT with exec-rewire only (audit: "reference implementation of the target
  architecture", ~40-line change each): `env/` (1,674), `jobs/` (977),
  `cards/` (827), `repos/` (380).
- Accept: [ ] each screen loads real data from the dev box; [ ] combined
  diff shows no logic changes beyond exec acquisition + imports.

---

**P-8 — git history**
- Depends on: U-2. PORT `git/` (2,742) dropping the held-lease lifecycle
  (`GitHistoryViewModel.kt:167` lease field, `setLeaseForTest`:203,
  stale-lease reacquire:456-460 — replaced by per-call
  `connection.exec`). Accept: history/diff/status/issue list render for a
  real repo; VM has zero `ForTest`.

---

**P-9 — share target + snippets + bootstrap**
- Depends on: T-4, U-4.
- PORT: share upload half (`ShareUploader.kt` 480, `FilenameSanitiser.kt`
  195, `ShareActivity.kt` 272, picker ~500) — the pane-injection half
  (`stageIntoSession`/`pasteIntoSession`/`LeaseBackedShareSession`, ~1,300)
  is replaced by: "share to session" = stage file via SFTP + inject the
  path through `sendBytes` (~100 new lines). `snippets/` (2,186) ports
  verbatim (audit: Room+ui-kit only); picker inserts into composer or
  terminal via `sendBytes`. `bootstrap/` (1,817) ports with exec-rewire
  (audit: zero coupling) and gains ONE new probe: minimum host-CLI version
  for schema 2 (reuse `AppCliVersion.kt` + the K-1 `HostCliTooOld` error).
- Accept: [ ] Android share sheet → app2 → file lands in `~/inbox/pocketshell/`;
  [ ] snippet insert renders in terminal; [ ] connecting to a host with an
  old CLI shows the upgrade flow instead of a parse error.

---

**P-10 — assistant, crash, costs, messaging, widget, misc chrome**
- Depends on: U-4.
- PORT: `assistant/` (2,136; rewire `AppAssistantActions.kt`'s
  `LeaseSessionExec` per audit), `crash/` (1,152), `costs/` (906),
  `messaging/` (630), `notifications/` (363), `systemsurfaces/` widget +
  tile (rewired to app2's session store), generic `diagnostics/` core only
  (`DiagnosticEvents`, `DiagnosticEventJson`, `DiagnosticRecorder`,
  `DiagnosticLogStore` ≈ 940 — the connection-journal/mirror/part-store
  stack is NOT ported, audit finding #5).
- Accept: [ ] each feature smoke-checked on device; [ ] no
  `ConnectionLog*`/`ReconnectCauseTrail`/`MirroredDiagnostics` files in app2.

### Phase X — cutover (maintainer-gated)

---

**X-1 — parity trial gate (maintainer, not an implementer task)**
- Depends on: U-1..U-10, P-1..P-10 all landed.
- The maintainer lives on app2 for 2 weeks. Every gap → a normal small issue
  against app2. Exit: two weeks without reaching for the old app.

**X-2 — [blocked: APX-ADOPT] single-source detection completion**
- Depends on: H-5, H-6, X-1.
- MODIFY: `tools/pocketshell` — delete the `@ps_agent_kind`/`@ps_agent_state`
  option writes (`agents.py`, `hooks.py`) and the H-4 tmux transcript
  fallback once adopted sessions carry transcript bindings; `sessions list`
  reads identity/state exclusively from aplexer.
- Accept: [ ] `grep -rn "@ps_" tools/pocketshell/src` returns only
  historical docs; [ ] tmux-backed agent sessions still show state + conversation.

**X-3 — the hard cut (D22)**
- Depends on: X-1.
- DELETE: `app/` (all 156,678 lines), `shared/core-ssh`, `shared/core-tmux`,
  `shared/core-connection`, `shared/core-agents`, the frozen half of
  `shared/core-terminal` (§A.2 list), their test trees, and every
  `scripts/check-connection-vm-ratchet*`/`check-file-size-hygiene` VM entry;
  prune `tests.yml` jobs that only served them (do this as its own commit
  series: modules first, app last, CI last — each step green).
- MODIFY: `app2` → rename applicationId to `com.pocketshell.app`
  (one release where the OLD package is still installed side-by-side; Room
  data carried over via the D22 carve-out: an export/import screen or a
  documented `adb backup`-style migration — decide with the maintainer at
  X-1 exit, it needs his data).
- Check before every deletion: `grep -rn "<module package>" --include="*.kt"
  app2 shared tools` returns nothing.
- Accept: [ ] repo builds + full new CI green; [ ] `find app -type f` empty;
  [ ] release cut from the new pipeline ships an installable APK.

**X-4 — docs/process update**
- Depends on: X-3. Rewrite `docs/architecture.md` for the new design; mark
  D5/D6/D28/D29/D30/D34 superseded-with-rationale in `docs/decisions.md`;
  shrink process.md per diagnosis doc §5; update `docs/testing.md`,
  `docs/release.md` for the new lanes. (Orchestrator-lane docs work.)

---

## Task graph summary

Parallel-safe lanes from day one: {H-1→H-2/H-3/H-4}, {T-1→T-2→T-3/T-4/T-5},
{M-1→M-2}, then K-1/K-2 join H+T, then U-1..U-10 serially on the critical
path (U-4 is the go/no-go gate — diagnosis doc §6.1), with P-tasks fanning
out after their stated deps. 42 tasks total: 6 H (2 blocked), 5 T, 2 K, 3 M,
10 U, 12 P (counting P-3a/b/c), 4 X (1 blocked, 1 maintainer-gated).

