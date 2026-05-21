# Architecture

## Composition layout

PocketShell is a new app. It does **not** fork `ssh-auto-forward-android`. Instead, we extract the existing app's reusable parts into shared Gradle modules that both apps consume.

```
ssh-auto-forward-android/   (existing app, slimmed to shell + UI)
pocketshell/                (new app)
shared/                     (sibling repo or sibling folder, TBD)
  ├── core-ssh/             # sshj wrapper, session lifecycle, key mgmt
  ├── core-portfwd/         # AutoForwarder, PortScanner, SshTunnel
  ├── core-tmux/            # NEW: tmux -CC control-mode client
  ├── core-terminal/        # NEW: vendored Termux terminal-view + Compose adapter
  ├── core-agents/          # NEW: agent detection + JSONL/SQLite parsers (Claude Code, Codex, OpenCode)
  ├── core-storage/         # Room entities, DAOs (host, key, snippet, job)
  └── ui-kit/               # NEW: Termius-style components (cards, chips, breadcrumbs, host row)
```

Both apps depend on the shared modules. Neither app reaches into the other.

**Migration approach:** swap JSch → sshj *during* the extraction. Doing it later, after both apps depend on the old API, is much more expensive.

## App identity

- Display name: **PocketShell**
- Package: `com.pocketshell.app`

## Tech stack

| Layer | Choice | Why |
|---|---|---|
| Language | Kotlin | Native Android, best interop |
| UI | Jetpack Compose | Declarative, matches the dynamic per-pane rendering model |
| SSH | **sshj** (not JSch) | Actively maintained, ed25519, modern KEX. JSch is effectively abandoned. |
| Terminal emulator | Vendored Termux `terminal-emulator` + `terminal-view` | Battle-tested xterm-256color. Writing a VT emulator from scratch is a 6-month detour. |
| Tmux integration | `tmux -CC` control mode | Structured protocol — see "Three load-bearing decisions" below. |
| Storage | Room (SQLite) | Standard. Already in use in `ssh-auto-forward-android`. |
| DI | Hilt | Standard. Already in use. |
| Background | Foreground service | Required to keep SSH connections alive; existing app already has this. |
| Speech-to-text | **Whisper via OpenAI API** | Better than Android `SpeechRecognizer` on technical content. See [input-methods.md](input-methods.md). |
| Min SDK | API 26 (Android 8.0) | Required for foreground services + NotificationChannel |

## Three load-bearing decisions

These drive the rest of the design. Get them wrong and the app doesn't feel native.

### 1. Tmux **control mode** (`tmux -CC`), not screen scraping

This is the single most important call. iTerm2's tmux integration uses `tmux -CC` — a structured protocol where tmux emits `%output`, `%session-changed`, `%window-add`, `%layout-change`, etc. Benefits:

- Real-time session/window/pane state without polling `tmux ls`
- Render each pane as its own terminal view (critical on mobile — see #3 below)
- Detach without killing the local view; reattach without re-rendering

Alternative (run `tmux attach` inside a normal SSH PTY and screen-scrape) is what every other mobile SSH app does, and it's exactly why none feel tmux-native.

### 2. SSH library: sshj (not JSch)

JSch is unmaintained and has weak modern crypto defaults. sshj supports ed25519, modern KEX, agent forwarding, and is actively maintained. Alternative would be vendoring an OpenSSH native binary, which is overkill unless we want Mosh later.

### 3. Per-pane terminal rendering, not tiled tmux

Tmux's native split layout is unreadable on a phone. With control mode we know about panes individually — render *one pane at a time* in a real VT emulator, with swipes to move between panes/windows. The user never sees tmux's status bar. PocketShell *is* the status bar.

## Server-side scheduler (locked decision — see decisions.md)

Recurring tmux-send jobs run **on each host** via `tmuxctl jobs daemon`, not on the phone. Jobs survive phone offline / sleep / network drops.

**Consequence — host bootstrap is now a real UX concern:**

When you add a host, PocketShell should:

1. Detect whether `tmuxctl` is installed (`which tmuxctl` over SSH)
2. If absent, offer one-tap install (`uv tool install tmuxctl` or `pipx install tmuxctl`)
3. Detect whether `tmuxctl jobs daemon` is running (and ideally enabled via systemd user unit)
4. Offer to install the systemd unit if missing

This needs to be first-class onboarding, not a hidden prerequisite. If we skip this, the Jobs UI silently fails on unconfigured hosts.

## Screen surface (high level)

| Screen | Purpose |
|---|---|
| **Workspace dashboard** (home) | Hosts (favourites + recent), all tmux sessions across connected hosts sorted by activity, running jobs |
| **Session view** | Full-screen terminal for one pane. Swipe L/R = next/prev pane. Swipe up = window switcher. Swipe down = session switcher. Top breadcrumb = `host › session › window › pane`. |
| **Quick send panel** | Send snippet or schedule recurring (delegates to remote `tmuxctl jobs add`) |
| **Port panel** | Slide-in panel on a host — the existing `ssh-auto-forward-android` table, absorbed |
| **Connection setup** | SSH config import, key gen + push via paste/QR, biometric unlock for passphrases |

Detailed mockups live in `mockups/` (TBD — see roadmap).
