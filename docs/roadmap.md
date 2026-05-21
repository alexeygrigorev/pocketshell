# Roadmap

Rough sizing — wall-clock estimates assume one developer working steadily.

## Phase 0 — Foundation (1–2 weeks)

Goal: a scaffold both apps can build on.

- Set up the `shared/` repo (or folder) with Gradle module structure
- Extract `core-ssh` from `ssh-auto-forward-android`, **swap JSch → sshj** during the move
- Extract `core-portfwd`, `core-storage`
- Scaffold empty `core-tmux`, `core-terminal`, `ui-kit`
- Vendor Termux `terminal-emulator` + `terminal-view` into `core-terminal`
- New `pocketshell` Gradle project (`com.pocketshell.app`) consuming the shared modules
- Render a single PTY in a Compose screen — proof of life

## Phase 1 — Terminal that's pleasant on a phone (3–4 weeks)

Goal: even *without* tmux awareness, the terminal experience must clear the Termius bar — *and* the voice-first input story must work end-to-end.

- Build `ui-kit` core components (`HostCard`, `Breadcrumb`, `CommandChip`, `StatusDot`, `KeyBar`, `MicButton`)
- Termius design tokens applied throughout
- **Key bar** above keyboard (Esc/Tab/Ctrl/Alt/arrows + ⚡ chord trigger)
- **Chord palette** with default shell chords (`Ctrl+C`, `Ctrl+R`); tmux chord defaults added in Phase 2
- **Voice input**: Whisper integration, prompt composer bottom sheet, inline dictation via mic in key bar
- Command chips above keyboard, snippet library (per-host)
- Smart selection (paths, URLs, errors → tap to copy)
- Breadcrumb path bar (parses `pwd` from PTY)
- Host management screens (reuse / adapt from `ssh-auto-forward-android`)

**Checkpoint:** dogfood for a few days. Does it beat Termius for your workflow? If not, fix before continuing.

## Phase 2 — Tmux control mode (3–4 weeks)

Goal: the moment it stops being "another SSH client."

- `core-tmux`: `tmux -CC` client (parser for `%output`, `%session-changed`, `%window-add`, `%layout-change`, etc.)
- Per-pane rendering, swipes between panes/windows
- Cross-host session dashboard (sorted by recency, à la tmuxctl)
- Session create / attach / detach / rename / kill
- Host bootstrap flow: detect tmux, prompt to install if missing

## Phase 3 — Workflow features (3–4 weeks)

- Port forwarding panel (slide-over, reuses `core-portfwd` — the UI already exists in `ssh-auto-forward-android`)
- Recurring jobs (delegates to remote `tmuxctl jobs add/list/edit`)
- Host bootstrap: detect `tmuxctl`, offer one-tap install, offer systemd user unit
- Quick-send presets per session
- **Agent awareness**: `core-agents` module with Claude Code / Codex / OpenCode parsers; conversation tab on the session view; hint chip when an agent is detected. See [agent-awareness.md](agent-awareness.md).
- Agent monitoring chips (build/deploy/training status surfaced on dashboard)

## Phase 4 — Polish (ongoing)

- Biometric unlock for key passphrases
- QR host sharing
- Home-screen widget (active tunnels / sessions count)
- Quick Settings tile (toggle forwarding)
- Mosh support (deferred — requires shipping `mosh-server` binary or assuming remote install + UDP path)
- Auto-start on boot
- Crash reporting

## Out of scope (for now)

- Windows / desktop targets
- File transfer UI (SFTP) — can be added later if there's demand
- Multi-user / team sync of host configs
- Cloud-stored history
