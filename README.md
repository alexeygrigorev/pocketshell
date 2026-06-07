# PocketShell

PocketShell is a voice-first, tmux-native Android SSH client for the
developer workstation you already use. It is built around persistent tmux
sessions, per-pane mobile rendering, AI-agent awareness, and server-side
helpers that run on the dev box instead of moving provider credentials or
long-running work onto the phone.

The current release line is **v0.3.27** (2026-06-06). The Android APK and the
server-side `pocketshell` Python helper are released from the same tag and
must stay version-matched.

## Status

PocketShell is under active development for the maintainer's own use. There is
no install base to migrate and no compatibility promise beyond preserving the
maintainer's same-package app data across normal APK updates. Feature
replacement follows the hard-cut rule in
[docs/decisions.md](docs/decisions.md): remove obsolete paths instead of
shipping compatibility shims.

PocketShell is also intentionally foreground-first. It does not schedule phone
work with `WorkManager`, `AlarmManager`, repeating timers, or wakelocks. tmux
and the `pocketshell` helper keep durable state on the dev box; the Android app
reconnects when foregrounded. The scoped exception is port forwarding, which
uses an Android foreground service only while tunnels are active. The active
terminal connection also has a bounded 60 second app-switch grace window before
teardown.

## Screenshots

No real v0.3.27 README screenshot assets are committed in this checkout yet.
The only committed PNG is
`docs/mockups/feedback/folder-tree-target-20260604.png`, which is design
feedback/reference material, not a current app screenshot. For that reason this
README does **not** embed fabricated or mockup images.

The release screenshot process exists and should be used before adding README
images:

```bash
scripts/phone-walkthrough.sh visual-audit
```

That writes reviewer screenshots under
`build/phone-walkthrough/<run-id>/screenshots/visual-audit/` and raw device
artifacts under
`build/phone-walkthrough/<run-id>/device-artifacts/walkthrough-visual-pass/`.
When real current captures are available, commit a curated, reasonably sized
set under `docs/screenshots/` and embed the host list, tmux session,
conversation view, composer, and settings screens here. See
[docs/walkthrough-visual-pass.md](docs/walkthrough-visual-pass.md) and
[docs/testing.md](docs/testing.md) for the capture workflow.

## What Works Today

- **Host management.** Save SSH hosts, import keys, generate keys, unlock
  passphrases biometrically, scan QR host payloads, and add hosts manually.
  QR import uses the versioned `pocketshell.ssh-import.v1` payload and supports
  multi-part QR codes for large keys.
- **Host setup detection.** On connect, the app derives the remote PATH, checks
  `command -v pocketshell`, compares `pocketshell --version` to the Android app
  version, and offers install or upgrade commands through `uv` or `pipx`.
- **Project-first navigation.** Each host opens into watched folders and
  discovered tmux sessions grouped by project/root, with controls for browsing
  GitHub repos through the host's `gh` CLI and opening sessions in cloned
  repos.
- **tmux-native sessions.** PocketShell attaches via `tmux -CC` control mode,
  tracks sessions/windows/panes from structured tmux events, and renders one
  pane at a time in the vendored Termux terminal emulator. Swipe/navigation
  controls replace trying to read tiled tmux layouts on a phone.
- **Dense dark dev-tool UI.** The app uses the shared PocketShell design system
  across host, folder, session, settings, snippets, env, file, and port-forward
  screens: always-dark surfaces, restrained typography, dense rows, status dots,
  one overflow affordance per row, and shared headers/list primitives.
- **Voice-first composer.** The prompt composer supports OpenAI Whisper and the
  Android system speech recognizer, editable drafts, configurable language and
  silence threshold, Insert vs Send, per-session draft retention, and inline
  dictation straight into the terminal.
- **Keyboard support for terminals.** A key bar supplies Esc, Tab, Ctrl, Alt,
  and arrows above the system keyboard. The terminal defaults to a raw command
  keyboard mode to avoid autocorrect corrupting shell input, with an opt-in
  smart text mode in settings.
- **Snippets.** Per-host snippets and prompt templates can be sent into the
  terminal/composer, with snippet picker flows covered by the app tests.
- **Agent awareness.** PocketShell detects Claude Code, Codex, and OpenCode in
  the visible tmux pane using cwd/log candidates plus pane-scoped process
  evidence, then surfaces a Conversation tab for that pane. Claude JSONL,
  Codex rollout JSONL, and OpenCode SQLite sources are parsed into a normalized
  conversation model.
- **Conversation view.** The agent view renders dense message turns, timestamps,
  Markdown/code, collapsible tool calls, copy/quote affordances, search, and a
  reply composer that sends back into the currently detected agent pane.
- **Agent slash-command palette.** When an agent is detected, a dedicated `/`
  affordance opens a curated per-agent slash-command sheet for commands such as
  goal/compact/clear-style flows, plus related session-control rows.
- **Attachments.** The composer can stage files selected on Android, upload
  them to `~/.pocketshell/attachments/...` on the remote host, show compact
  attachment tiles, send attachment-only prompts, and prune old staged files.
  Shared text/files can also be routed into an active session.
- **Usage and quota.** The app shows provider usage/quota from the server-side
  `pocketshell usage --json` helper for Claude, Codex, Copilot, Z.AI, and
  compatible custom commands, without storing provider credentials on the
  phone.
- **Port forwarding.** Per-host forwarding tables and auto-forward controls are
  backed by the shared `core-portfwd` module. Active tunnels can survive panel
  disposal/backgrounding through the scoped foreground-service carve-out.
- **Remote file and env tools.** The app includes remote file browsing/viewing,
  file-path tap handling from terminal output, `.env` / `.envrc` key management
  through `pocketshell env`, watched-folder management, and recurring jobs
  through `pocketshell jobs`.
- **Diagnostics and cost tracking.** Settings include usage, AI cost tracking
  for app-side OpenAI calls, crash reports, terminal/conversation font controls,
  voice settings, assistant-provider settings, and diagnostics export.

The deterministic Docker host used in tests includes local shims for `claude`,
`codex`, `opencode`, `gh`, `pocketshell`, `uv`, and related tools so emulator
and JVM tests can exercise the flows without real provider credentials.

## Install

### Android App

1. Download the latest debug APK from
   [GitHub Releases](https://github.com/alexeygrigorev/pocketshell/releases).
2. Install it on the phone:

   ```bash
   adb install -r app-debug.apk
   ```

   The repo helper for the data-preserving update path is:

   ```bash
   scripts/install-update-apk.sh app-debug.apk
   ```

3. Open PocketShell and add a host through QR import or manual entry.

### Server-Side Helper

Install the matching `pocketshell` helper on every dev box you plan to use:

```bash
uv tool install pocketshell
# or
pipx install pocketshell
```

For QR generation support:

```bash
uv tool install pocketshell --with "qrcode[pil]"
```

The helper exposes the server-side commands the Android app drives:
`usage`, `sessions`, `jobs`, `repos`, `env`, `hooks`, `logs`, `agent-log`, and
`qr-share`. See [tools/pocketshell/README.md](tools/pocketshell/README.md) and
[docs/server-setup.md](docs/server-setup.md).

## Quickstart

Phone in hand, dev box reachable over SSH at `dev.example.com`:

1. Install the helper with QR support on the dev box:

   ```bash
   ssh dev.example.com 'uv tool install pocketshell --with "qrcode[pil]"'
   ```

2. Generate a QR payload from an SSH config alias:

   ```bash
   pocketshell qr-share dev.example.com
   ```

   If the alias cannot be resolved, pass details explicitly:

   ```bash
   pocketshell qr-share --host dev.example.com --user alex \
     --key ~/.ssh/id_ed25519 --name "Dev box"
   ```

   The QR payload can include the private key. Render it only on a private
   screen. Multi-part QR codes are reassembled by the Android scanner.

3. In PocketShell, use Scan to import the host. Manual host entry remains
   available.

4. Tap the host. PocketShell runs the bootstrap probe, checks the helper
   version, discovers watched folders and tmux sessions, and opens the
   host/project view.

5. Attach to or create a tmux session. Use the mic/composer, key bar, snippets,
   slash-command palette, conversation tab, file browser, env screen, or port
   forwarding panel as needed.

## Architecture

```text
Android phone                 SSH (sshj)              Dev box
PocketShell UI   tmux -CC control mode ----------->   tmux server
Compose + VT     tail JSONL / SQLite -------------->   agent logs
foreground app   pocketshell commands ------------>   pocketshell helper
```

Load-bearing choices:

- `tmux -CC` control mode instead of screen-scraping.
- sshj instead of JSch.
- One visible pane at a time instead of tiled tmux.
- Server-side helpers for usage, repos, env, hooks, logs, and jobs.
- Zero phone-side provider credentials for usage/repo/provider state.

More detail: [docs/architecture.md](docs/architecture.md),
[docs/decisions.md](docs/decisions.md),
[docs/agent-awareness.md](docs/agent-awareness.md), and
[docs/usage-panel.md](docs/usage-panel.md).

## Repository Layout

- `app/` - Android application.
- `shared/core-ssh/` - sshj wrapper, leases, key management, remote file APIs.
- `shared/core-portfwd/` - port-forwarding support.
- `shared/core-tmux/` - tmux control-mode parsing/client behavior.
- `shared/core-terminal/` - vendored Termux terminal emulator and Compose
  adapter.
- `shared/core-agents/` - Claude Code, Codex, and OpenCode parsers/detection.
- `shared/core-usage/` - normalized usage/quota parsing.
- `shared/core-storage/` - Room entities, DAOs, migrations.
- `shared/core-voice/` - Whisper and speech input support.
- `shared/ui-kit/` - shared dark design system components and tokens.
- `tools/pocketshell/` - server-side Python helper published to PyPI.
- `tests/docker/` - deterministic SSH/dev-box test fixtures.
- `docs/` - product docs, architecture notes, design notes, and QA runbooks.

## Development

Prerequisites: JDK 17, Android SDK/platform tools, an emulator image, Docker
with Compose, and `local.properties` pointing at the Android SDK, for example:

```properties
sdk.dir=/home/alexey/Android/Sdk
```

Common commands:

```bash
./gradlew assembleDebug
./gradlew test --stacktrace
./gradlew check --stacktrace
./gradlew connectedDebugAndroidTest
scripts/terminal-workbench.sh
```

Docker-backed SSH/port-forward integration tests:

```bash
./gradlew :shared:core-ssh:integrationTest \
  :shared:core-portfwd:integrationTest --stacktrace
```

Pre-release validation:

```bash
scripts/pre-release-confidence-gate.sh
scripts/release-emulator-validation.sh
```

Focused screenshot capture for reviewer/README evidence:

```bash
scripts/phone-walkthrough.sh visual-audit
```

The project test matrix is in [docs/testing.md](docs/testing.md). Docker and
emulator setup details are in
[docs/docker-emulator-runbook.md](docs/docker-emulator-runbook.md).

## Release Flow

Release APKs are built by pushing a version tag. The same tag publishes
`tools/pocketshell/` to PyPI so the Android app and helper stay in lockstep.
The guarded release process, version checks, validation artifacts, and fallback
GitHub Actions path are documented in [process.md](process.md).

## Links

- [docs/vision.md](docs/vision.md) - product vision and positioning.
- [docs/architecture.md](docs/architecture.md) - module layout, sshj, tmux
  control mode, per-pane rendering.
- [docs/decisions.md](docs/decisions.md) - locked decisions, foreground model,
  and hard-cut compatibility rule.
- [docs/design-language.md](docs/design-language.md) - visual design brief.
- [docs/design-system.md](docs/design-system.md) - codified design tokens.
- [docs/input-methods.md](docs/input-methods.md) - voice, key bar, snippets,
  and composer behavior.
- [docs/agent-awareness.md](docs/agent-awareness.md) - Claude/Codex/OpenCode
  detection and conversation view.
- [docs/usage-panel.md](docs/usage-panel.md) - provider quota through
  server-side `pocketshell usage`.
- [docs/ssh-qr-import.md](docs/ssh-qr-import.md) - QR import payload format.
- [docs/server-setup.md](docs/server-setup.md) - helper install/PATH
  troubleshooting.
- [docs/walkthrough-visual-pass.md](docs/walkthrough-visual-pass.md) -
  screenshot capture workflow.
- [docs/testing.md](docs/testing.md) - testing matrix and QA expectations.
- [docs/docker-emulator-runbook.md](docs/docker-emulator-runbook.md) -
  Docker, emulator, and connected-test runbook.
- [process.md](process.md) - orchestrator/reviewer process and release flow.
- [agents.md](agents.md) - local Android tooling paths and agent role pointers.
- [tools/pocketshell/README.md](tools/pocketshell/README.md) - server-side
  helper reference.
