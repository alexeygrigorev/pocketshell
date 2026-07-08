# Architecture

## Composition layout

PocketShell is a new app. It does not fork `ssh-auto-forward-android`. Instead, we extract the existing app's reusable parts into shared Gradle modules that both apps consume.

```
ssh-auto-forward-android/   (existing app, slimmed to shell + UI)
pocketshell/                (new app)
shared/                     (sibling repo or sibling folder, TBD)
  ├── core-ssh/             # sshj wrapper, session lifecycle, key mgmt
  ├── core-portfwd/         # AutoForwarder, PortScanner, SshTunnel
  ├── core-tmux/            # NEW: tmux -CC control-mode client
  ├── core-terminal/        # NEW: vendored Termux terminal-view + Compose adapter
  ├── core-agents/          # NEW: Claude/Codex/OpenCode detection + JSONL/SQLite parsers
  ├── core-usage/           # NEW: parses pocketshell usage --json over SSH
  ├── core-storage/         # Room entities, DAOs (host, key, snippet, job)
  └── ui-kit/               # NEW: Termius-style components (cards, chips, breadcrumbs, host row)
```

Both apps depend on the shared modules. Neither app reaches into the other.

Migration approach: swap JSch → sshj *during* the extraction. Doing it later, after both apps depend on the old API, is much more expensive.

## App identity

- Display name: PocketShell
- Package: `com.pocketshell.app`

## Tech stack

| Layer | Choice | Why |
|---|---|---|
| Language | Kotlin | Native Android, best interop |
| UI | Jetpack Compose | Declarative, matches the dynamic per-pane rendering model |
| SSH | sshj (not JSch) | Actively maintained, ed25519, modern KEX. JSch is effectively abandoned. |
| Terminal emulator | Vendored Termux `terminal-emulator` + `terminal-view` | Battle-tested xterm-256color. Writing a VT emulator from scratch is a 6-month detour. |
| Tmux integration | `tmux -CC` control mode | Structured protocol — see "Three load-bearing decisions" below. |
| Mosh | Deferred / unsupported | No fake Mosh mode. Real support requires UDP transport plus `mosh-server` on the remote host; current app capability surfaces mark it unavailable. |
| Storage | Room (SQLite) | Standard. Already in use in `ssh-auto-forward-android`. |
| DI | Hilt | Standard. Already in use. |
| Background | Foreground service | Required to keep SSH connections alive; existing app already has this. |
| Speech-to-text | Whisper via OpenAI API | Better than Android `SpeechRecognizer` on technical content. See [input-methods.md](input-methods.md). |
| Min SDK | API 26 (Android 8.0) | Required for foreground services + NotificationChannel |

## Three load-bearing decisions

These drive the rest of the design. Get them wrong and the app doesn't feel native.

### 1. Tmux control mode (`tmux -CC`), not screen scraping

This is the single most important call. iTerm2's tmux integration uses `tmux -CC` — a structured protocol where tmux emits `%output`, `%session-changed`, `%window-add`, `%layout-change`, etc. Benefits:

- Real-time session/window/pane state without polling `tmux ls`
- Render each pane as its own terminal view (critical on mobile — see #3 below)
- Detach without killing the local view; reattach without re-rendering

Alternative (run `tmux attach` inside a normal SSH PTY and screen-scrape) is what every other mobile SSH app does, and it's exactly why none feel tmux-native.

### 2. SSH library: sshj (not JSch)

JSch is unmaintained and has weak modern crypto defaults. sshj supports ed25519, modern KEX, agent forwarding, and is actively maintained. Alternative would be vendoring an OpenSSH native binary, which is overkill unless we want Mosh later.

Mosh is explicitly not implemented in the current transport stack. The bootstrap capability report exposes it as unsupported so the app does not imply that flaky-network Mosh behavior is available through sshj. When this moves out of deferment, it needs a real UDP path and a `mosh-server` strategy rather than wrapping SSH sessions with a different label.

### 3. Per-pane terminal rendering, not tiled tmux

Tmux's native split layout is unreadable on a phone. With control mode we know about panes individually — render *one pane at a time* in a real VT emulator, with swipes to move between panes. The user never sees tmux's status bar. PocketShell *is* the status bar. PocketShell does **not** manage tmux windows (D30, issue #782); windows created outside PocketShell are surfaced in the host tree as separate `[wN]` switcher entries (one per window), each attaching to that window's pane via the warm lease.

## Connection / reconnect / grace (the most critical subsystem — D28)

The SSH/tmux connection core (connect / attach / reattach / grace / lease /
reconnect) is the single most important subsystem and the #1 dogfood blocker
(D28). It is being moved off the old inline path onto a first-class
`core-connection` `ConnectionController`, turned on **phase by phase** behind a
temporary user-facing toggle (locked decision **D29** — see decisions.md).

**Two selectable paths during the turn-on, chosen by a Settings toggle:**

- **NEW (default) — `ConnectionController` + `RevealStateMachine`.** The
  `ConnectionController` owns connect / attach / reattach / grace / lease /
  reconnect as one state machine driven by exactly two inputs: app
  foreground/background lifecycle, and the transport health signals
  (`TmuxClient.disconnected` / `SshLeaseManager` state). The
  `RevealStateMachine` keys the screen to the target session id
  (`Navigating(targetId) → Seeding(targetId) → Live(targetId)`) and **drops any
  pane seed whose id ≠ the current target**, so a late seed from a superseded
  switch can never paint the wrong session. Grace is a single owner anchored on
  the lease's 60 s warm window (D21), not three disagreeing clocks.
- **OLD (selectable via the toggle) — inline `TmuxSessionViewModel`.** The
  legacy reconnect/grace machine welded into the `TmuxSessionViewModel`. It
  remains selectable as the on-device fallback **only** during the phased
  turn-on, and is **deleted together with the toggle in #766** once the
  maintainer confirms the new path on-device. After #766 there is a single
  active path (D28 rule 4): `ConnectionController` only, no toggle, no old path.

**Turn-on status — P1–P4 ALL MERGED (2026-06-14):**

- **P1 — screen-keyed reveal (#686): MERGED.** The `RevealStateMachine`
  id-tagged-seed / drop-non-target behaviour above.
- **P2 — single grace owner (#635): MERGED.** The within-grace foreground probe
  / ride-through owned by one lease-anchored grace window, now 90 s by default, collapsing the
  clocks that caused #685.
- **P3 — id-tagged reseed-on-reattach (#553): MERGED.** Within-grace reattach
  UNCONDITIONALLY re-captures the active pane (full clear+restore) keyed to the
  target session id, then the blank-net backstop — so "one live line, rest blank"
  is fully healed (the old heal skipped on `!visibleScreenIsBlank()`).
- **P4 — Codex backpressure (#576): MERGED.** The core-tmux command-timeout is now
  an IDLE deadline keyed on reader-side activity (fires on reader silence, re-arms
  while bytes flow), and read-only commands fail-open instead of `FatalClose` — so
  a busy-but-alive `-CC` link no longer self-inflicts a reconnect. No toggle
  (transport-layer; fixes both paths). Keep-alive dead-peer detection is untouched.

The full P1–P4 ConnectionController turn-on is therefore COMPLETE behind the
New/Old toggle (default New); only **#766** (delete the toggle + the OLD inline
path, gated on maintainer on-device verification of all four journeys) remains.

Each phase is gated by a device-truth journey (J1–J4) that asserts the **user's
rendered viewport** — the terminal text actually shown — NOT internal/shadow
state. A journey that only checks internal state can be green while the visible
app is broken (the #636/#638 lesson), so the gate must FAIL when the
user-visible output regresses. These load-bearing journeys run in per-PR CI
(#638/#691), not only the release gate.

## Server-side scheduler (locked decision — see decisions.md)

Recurring tmux-send jobs run on each host via `pocketshell jobs daemon`, not on the phone. Jobs survive phone offline / sleep / network drops.

Consequence — host bootstrap is now a real UX concern:

When you add a host, PocketShell should:

1. Detect whether `pocketshell` is installed (`command -v pocketshell` over SSH)
2. If absent or version-mismatched, offer one-tap install/upgrade (`uv tool install --exclude-newer-package pocketshell=2099-12-31 pocketshell`, `uv tool install --upgrade --exclude-newer-package pocketshell=2099-12-31 pocketshell`, or the matching `pipx` command)
3. Detect whether `pocketshell jobs daemon` is running (and ideally enabled via systemd user unit)
4. Offer to install the systemd unit if missing

This needs to be first-class onboarding, not a hidden prerequisite. If we skip this, the Jobs UI silently fails on unconfigured hosts.

## Screen surface (high level)

| Screen | Purpose |
|---|---|
| Workspace dashboard (home) | Hosts (favourites + recent), all tmux sessions across connected hosts sorted by activity, running jobs |
| Session view | Full-screen terminal for one pane. Swipe L/R = next/prev pane. Swipe down = session switcher. Terminal/Conversation is the only in-session tab dimension; PocketShell does not manage tmux windows (D30, #782) — externally-created windows appear as separate `[wN]` switcher entries in the host tree. |
| Quick send panel | Send snippet or schedule recurring (delegates to remote `pocketshell jobs add`) |
| Port panel | Slide-in panel on a host — the existing `ssh-auto-forward-android` table, absorbed |
| Connection setup | SSH config import, key gen + push via paste/QR, biometric unlock for passphrases |

Detailed mockups live in `mockups/` (TBD — see roadmap).
