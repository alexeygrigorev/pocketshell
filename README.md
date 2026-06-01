# PocketShell

PocketShell turns your Android phone into a voice-first cockpit for the
tmux sessions and AI coding agents running on your dev box.

It is built around persistent tmux sessions (via `tmux -CC` control mode),
touch-friendly per-pane rendering, voice/prompt composition, and AI-agent
awareness — instead of treating the phone as a small desktop terminal. The
product direction is captured in [docs/vision.md](docs/vision.md).

## Status

PocketShell is under active development; it is not production-ready and
there is no install base to migrate. The latest GitHub Release is
**v0.3.8** (2026-05-30). The release builds the Android debug APK and
publishes the server-side `pocketshell` Python helper from the same tag.

What works in v0.3.8 / current `main`:

- Multi-host SSH (sshj) with QR-code host import, key generation/import,
  and biometric unlock.
- Host-first navigation: each host opens into a workspace tree rooted at
  configured project roots, with active tmux sessions grouped by project.
- Persistent tmux sessions via `tmux -CC` control mode, with per-pane
  rendering (one pane at a time, swipe between panes/windows).
- Voice prompt composer (Whisper) + inline dictation, key bar for
  Esc/Tab/Ctrl/Alt/arrows, snippet library, command chips.
- Agent-aware conversation view for Claude Code, Codex, and OpenCode.
  Codex/OpenCode detection lights up after the agent's first
  turn-completion flush or SQLite session update, with pane-scoped
  process matching and a 2-hour freshness window for Codex/OpenCode.
  See [docs/agent-awareness.md](docs/agent-awareness.md).
- Usage dashboard surfaced from the server-side `pocketshell usage`
  helper. Zero provider credentials on the phone.
- Host setup detection for the server-side CLI: PocketShell derives the
  remote PATH from the user's shell rc, checks `pocketshell --version`,
  and offers install/upgrade via `uv` or `pipx` when needed.
- Share-target dispatch: paste from any app into the active session, or
  save into a file on the remote host.
- Port-forwarding (extracted from `ssh-auto-forward-android`) is
  default-off per host. When enabled, it uses the one carve-out from
  D21's no-background rule — a scoped foreground service while tunnels
  are active.

The deterministic Docker host used in CI does not use real provider
credentials. It ships local shims for `claude`, `codex`, `opencode`,
`heru`, `agent-log-explorer`, `tmuxctl`, `quse`, `pocketshell`, `uv`, and
`systemctl` so emulator tests can run repeatably.

PocketShell follows a **no backwards-compatibility** rule (decision **D22**
in [docs/decisions.md](docs/decisions.md)): hard cuts only, no compat
shims, no legacy paths. The maintainer is the only user.

## Install

### Android app

1. Grab the latest debug APK from the
   [GitHub Releases page](https://github.com/alexeygrigorev/pocketshell/releases).
2. Install it on the phone — either by opening the APK directly, or for
   power users via `adb`:

   ```bash
   adb install -r app-debug.apk
   ```

   The repo helper for this data-preserving update path is
   `scripts/install-update-apk.sh app-debug.apk`; it runs only
   `adb install -r` and never clears app data or uninstalls the package.

3. Open PocketShell. The first run will prompt for an SSH host; the
   canonical way to add one is QR-code import (see Quickstart below).
   Manual host entry is still available from the host list.

### Server-side `pocketshell` helper

PocketShell probes every remote host for a single Python helper called
`pocketshell` that wraps the usage / sessions / jobs / agent-log
subcommands the app needs. Install it on each dev box you plan to connect
to:

```bash
uv tool install pocketshell        # preferred (lands on PATH via ~/.local/bin)
pipx install pocketshell           # equivalent for pipx users
```

The helper is published on PyPI by the same release tag that builds the
APK, so app and helper versions stay in lockstep. See
[tools/pocketshell/README.md](tools/pocketshell/README.md) for the
helper's full subcommand reference, version-coupling rules, and PyPI
trusted-publishing setup.

The app's host-bootstrap probe derives the remote PATH from the user's
interactive shell rc, runs `command -v pocketshell`, checks
`pocketshell --version` against the app version, and offers an install or
upgrade path via `uv` / `pipx` when the helper is absent or mismatched.
When the app drives `uv`, it adds
`--exclude-newer-package pocketshell=2099-12-31` so a host-level uv
cutoff does not pin PocketShell behind the app version.
Per-host features that depend on the helper stay unavailable until that
host passes setup.

## Quickstart

Phone in hand, dev box reachable over SSH at `dev.example.com`:

1. **Install the server-side helper on the dev box.** From a desktop
   terminal:

   ```bash
   ssh dev.example.com 'uv tool install pocketshell --with "qrcode[pil]"'
   ```

   The `qrcode[pil]` extra is what renders the QR codes; without it
   `qr-share` exits with an install hint.

2. **Generate the QR code(s) for the host.** In an interactive SSH
   session on the dev box, run `pocketshell qr-share` against an
   ssh-config alias:

   ```bash
   pocketshell qr-share dev.example.com
   ```

   This resolves the hostname, port, user, and `IdentityFile` from
   `~/.ssh/config` (via `ssh -G`), builds the `pocketshell.ssh-import.v1`
   payload **with the private key inline**, and prints the QR as Unicode
   blocks in your terminal. If the alias can't be resolved, pass the
   connection details explicitly:

   ```bash
   pocketshell qr-share --host dev.example.com --user alex \
     --key ~/.ssh/id_ed25519 --name "Dev box"
   ```

   A large key spans multiple QR parts — press Enter to step through
   them (the phone reassembles parts automatically). To write PNGs for a
   second screen instead of terminal rendering, add `--png --out-dir ./qr`.
   Because the payload carries your private key, only render it on a
   private screen. Format details: [docs/ssh-qr-import.md](docs/ssh-qr-import.md).

   **Alternative — run from a repo clone (no install):** if you have this
   repo checked out, skip step 1 and run `qr-share` straight from source
   with `uv run` (the `--extra qr` flag pulls in the QR renderer):

   ```bash
   cd tools/pocketshell
   uv run --extra qr pocketshell qr-share dev.example.com
   ```

   Manual entry is the fallback if you don't want to go through QR: open
   PocketShell -> hosts list -> `+` -> fill in host/port/user, pick an
   imported key.

3. **Scan or import on the phone.** Open PocketShell, tap the host list's
   **Scan** tab, and point the camera at the QR code. Multi-part QR codes
   are reassembled automatically.

4. **Connect and attach to a tmux session.** Tap the new host. PocketShell
   runs the bootstrap probe (`command -v pocketshell`, `tmux ls`,
   CLI version checks, per-pane agent detection), then opens the workspace
   tree and session list. Tap an existing tmux session to attach via
   `tmux -CC`, or create one from the `+` button.

5. **Send your first voice prompt.** With a session attached, tap the
   mic FAB to open the prompt composer. Speak, watch the live
   transcription, tap **Send + ↵** to push the prompt with a newline into
   the pane. If Claude Code, Codex, or OpenCode is running in that pane,
   the **Conversation** tab lights up and tails the agent's conversation
   source.

Once the host is added, subsequent reconnects are tap-and-attach. tmux on
the remote keeps state across phone backgrounding (per D21 — no
background work on the phone).

## Features

- **Persistent tmux sessions via `tmux -CC` control mode.** Per-pane
  rendering: one pane at a time on a real VT emulator, swipe between
  panes and windows. Detach without killing the local view, reattach
  without re-rendering. See [docs/architecture.md](docs/architecture.md).
- **Voice-first composer.** Whisper (via OpenAI Audio Transcriptions)
  with live partial transcription, editable text area, inline
  dictation into the terminal, key bar for keys phones lack, snippet
  library, command chips. See
  [docs/input-methods.md](docs/input-methods.md).
- **Agent-aware conversation view.** Detects Claude Code, Codex, and
  OpenCode in the currently visible tmux pane and renders a clean
  conversation surface by tailing the agent log over SSH. Conversation
  replies send in place to that visible detected agent pane; panes
  without a detected agent show the terminal, with no cross-window
  target lock. Tool calls are collapsible, search works within the
  session, and long-press supports quote-reply. Codex and OpenCode
  detection fires once the agent has flushed at least one turn to its
  rollout JSONL (the per-pane detector uses a 2-hour freshness window
  so a mid-session Codex pane still registers after a pause between
  turns). OpenCode sessions persisted to `opencode.db` are read directly.
  See [docs/agent-awareness.md](docs/agent-awareness.md).
- **Usage dashboard.** Per-provider quota (Claude, Codex, Copilot, Z.AI)
  via the server-side `pocketshell usage` helper. Zero credentials on the
  phone — the helper polls each provider on the host and the phone is a
  viewer. See [docs/usage-panel.md](docs/usage-panel.md).
- **QR-code host pairing.** Single-frame or multi-part QR import of host
  + key in a versioned payload (`pocketshell.ssh-import.v1`). Manual
  entry remains available. See
  [docs/ssh-qr-import.md](docs/ssh-qr-import.md).
- **Background-free design (D21).** No `WorkManager`, no
  `AlarmManager`, no scheduled work on the phone. tmux on the remote
  keeps long-running state; the app reconnects on next foreground. The
  one scoped carve-out is the auto-forward foreground service when
  tunnels are active.
- **Unified `pocketshell` daemon mode.** The Python helper offers a
  lazy-spawned daemon (Unix socket, JSON-RPC, 30s TTL cache) so repeat
  `usage` calls return in sub-second time; ~92x speedup observed on
  cached `usage --json` invocations versus the cold-spawn path. The
  daemon currently covers `usage.fetch`; remaining subcommands (jobs,
  sessions, repos) extend onto the same IPC layer in follow-up rounds.
- **Share-target dispatch.** From any Android app, share text into
  PocketShell to either paste into the currently-attached session or
  save as a file on the remote host.
- **Port forwarding.** Auto-forward modules ported from
  `ssh-auto-forward-android`. Per-host port table, foreground-service
  network recovery, and visible reconnecting/stopped tunnel state while
  tunnels restart after a transport bounce.

## Architecture

```text
Android phone                 SSH (sshj)              Dev box
┌──────────────┐                                ┌──────────────────────┐
│ PocketShell  │  tmux -CC control mode ─────>  │ tmux server          │
│ Compose UI   │                                │  └─ sessions / panes │
│ per-pane     │  tail JSONL / SQLite ───────>  │ agent logs           │
│ VT rendering │                                │  └─ Claude / Codex / │
│              │                                │     OpenCode         │
│              │  pocketshell usage / sessions  │ pocketshell helper   │
│              │   / jobs / agent-log  ──────>  │  └─ daemon socket    │
└──────────────┘                                └──────────────────────┘
```

Modules live under `shared/` (`core-ssh`, `core-portfwd`, `core-tmux`,
`core-terminal`, `core-agents`, `core-usage`, `core-storage`, `core-voice`,
`ui-kit`) and are consumed by `app/`. The full module layout, the
three load-bearing decisions (tmux control mode, sshj, per-pane render),
and the server-side scheduler model are in
[docs/architecture.md](docs/architecture.md).

## Repository layout

- `app/` — Android application.
- `shared/core-ssh/` — sshj wrapper, session lifecycle, key management.
- `shared/core-portfwd/` — port-forwarding support.
- `shared/core-tmux/` — tmux `-CC` parsing and session behavior.
- `shared/core-terminal/` — Termux terminal emulator + Compose adapter.
- `shared/core-agents/` — Claude Code / Codex / OpenCode parsers + detection.
- `shared/core-usage/` — remote usage/quota parsing (`pocketshell usage`).
- `shared/core-storage/` — Room entities, DAOs (host, key, snippet, job).
- `shared/core-voice/` — voice input support (Whisper).
- `shared/ui-kit/` — shared visual components and tokens.
- `tools/pocketshell/` — the server-side Python helper (PyPI: `pocketshell`).
- `tests/docker/` — deterministic Docker SSH targets for local + CI tests.
- `docs/` — product docs, architecture notes, mockups, QA runbooks.

## Development

Prerequisites: JDK 17, Android SDK with platform tools + an emulator
image, Docker with Compose. `local.properties` must point at the Android
SDK (e.g. `sdk.dir=/home/alexey/Android/Sdk`).

Common commands:

```bash
./gradlew assembleDebug                    # build a debug APK
./gradlew test --stacktrace                # JVM unit tests
./gradlew check --stacktrace               # full Gradle check
./gradlew connectedDebugAndroidTest        # connected tests on a running emulator
scripts/terminal-workbench.sh              # deterministic terminal review workbench
```

Docker-backed SSH/port-forward integration tests:

```bash
./gradlew :shared:core-ssh:integrationTest \
  :shared:core-portfwd:integrationTest --stacktrace
```

Pre-release confidence gate (build + unit checks + connected smoke +
install on emulator):

```bash
scripts/pre-release-confidence-gate.sh
```

Full Docker + emulator setup, port mapping, focused connected-test recipes
are in
[docs/docker-emulator-runbook.md](docs/docker-emulator-runbook.md). The
project test matrix is in [docs/testing.md](docs/testing.md).

PocketShell uses an orchestrator + sub-agents working model (implementer,
reviewer, researcher). Contributors should read
[process.md](process.md) for the loop, agent worktrees, briefing rules,
and the release gate before opening issues or sending patches.

## Release flow

Release APKs are built by pushing a version tag, not by ad-hoc workflow
runs. The same tag triggers a PyPI publish of `tools/pocketshell/` so the
helper and the APK ship in lockstep. Full procedure (verification
gate, version coupling, guarded tag helper, manual GitHub Actions
fallback) is in [process.md](process.md) under **Release Builds**.

## Links

- [docs/vision.md](docs/vision.md) — product vision and positioning.
- [docs/architecture.md](docs/architecture.md) — module layout, sshj,
  tmux `-CC`, per-pane rendering.
- [docs/decisions.md](docs/decisions.md) — locked design decisions,
  open questions, rejected alternatives.
- [docs/roadmap.md](docs/roadmap.md) — phased build (0–4) with sizing.
- [docs/agent-awareness.md](docs/agent-awareness.md) — Claude / Codex /
  OpenCode parser status + conversation view.
- [docs/input-methods.md](docs/input-methods.md) — voice + key bar +
  snippets.
- [docs/usage-panel.md](docs/usage-panel.md) — provider quota via
  `pocketshell usage`.
- [docs/ssh-qr-import.md](docs/ssh-qr-import.md) — QR import payload
  format.
- [docs/testing.md](docs/testing.md) — testing matrix and QA expectations.
- [docs/docker-emulator-runbook.md](docs/docker-emulator-runbook.md) —
  Docker, emulator, focused connected tests.
- [docs/design-language.md](docs/design-language.md) — visual design.
- [process.md](process.md) — orchestrator/implementer/reviewer process
  and release flow.
- [agents.md](agents.md) — local Android tooling paths and agent role
  pointers.
- [Issue tracker](https://github.com/alexeygrigorev/pocketshell/issues)
  and [milestones](https://github.com/alexeygrigorev/pocketshell/milestones).
- [`tools/pocketshell/README.md`](tools/pocketshell/README.md) —
  server-side Python helper.
