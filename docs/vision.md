# Vision: Mobile-First SSH Client for Agents

A modern Android SSH client designed specifically for mobile workflows, AI agents, and fast terminal navigation.

The application should make remote terminal usage feel natural on a phone by reducing typing, simplifying navigation, and making persistent tmux sessions first-class citizens.

Instead of behaving like a desktop terminal squeezed onto a small screen, the app should be designed around touch interaction, quick actions, and session-oriented workflows.

---

## Core Principles

### 1. Tmux sessions are first-class citizens

The app should treat tmux sessions as the primary way users interact with remote machines. Users should be able to:

- See all active tmux sessions immediately after connecting
- Create, rename, attach, detach, and kill sessions easily
- Switch sessions with swipe or tap gestures
- Persist workflows across reconnects
- Resume work instantly from mobile

The app should feel like a "mobile tmux workspace manager," not just an SSH terminal.

Instead of typing `tmux ls` then `tmux attach -t agent`, the user should see a visual list of sessions with previews, last-activity timestamps, favourites/pins, and one-tap attach.

### 2. Fast mobile navigation

Directory navigation should minimize typing. Smart `cd` shortcuts (recent dirs, favourites, project roots, git repos, agent workspaces). Tappable breadcrumb path navigator. Swipe gestures between sessions and through directory history. Long-press for snippets. Edge swipe for quick actions.

### 3. Optimized for AI agent workflows

The app should assume users are running coding agents, automation agents, remote dev tools, and long-running CLI workflows — and optimize for *supervising* them from mobile.

- Persistent monitoring: logs, running tasks, streaming output, build/deploy status — without re-typing commands
- Session roles / tags: coding agent, deploy, monitoring, logs, shell, experiments
- Quick actions: restart agent, send predefined commands, open logs, reconnect, copy output, share session snippets
- **Agent-aware conversation view**: when Claude Code, Codex, or OpenCode is running in a pane, surface a clean conversation read of *this session* by tailing the agent's JSONL log — solves the "I can't scroll back to what the agent said" problem on mobile. See [agent-awareness.md](agent-awareness.md).
- **Usage panel**: per-provider quota tracking via server-side tools (e.g. `heru usage --json`) invoked over SSH. Zero credentials on the phone. See [usage-panel.md](usage-panel.md).

### 4. Mobile-friendly terminal interaction (voice-first)

Typing on phones is painful — reduce keyboard usage as much as possible. **Voice is a first-class input method**, not an afterthought: tap to navigate, voice to compose. See [input-methods.md](input-methods.md) for the full strategy.

- **Voice → text** for agent prompts (Whisper API, tap-to-toggle, bottom-sheet prompt composer with live transcription)
- **Key bar** above the keyboard for Esc / Tab / Ctrl / Alt / arrows (the keys phones don't have)
- **Chord palette** for tmux/shell sequences (`Ctrl+B D`, `Ctrl+C`, `Ctrl+R`) — one tap instead of fighting the keyboard
- **Command chips** above keyboard (ls, cd, git status, tmux ls, clear) — context-aware and customizable
- **Snippet library** (SSH commands, tmux workflows, deploy commands, agent startup scripts)
- **Touch selection**: smart text selection, block selection, code detection, tap-to-copy paths/errors

### 5. Session-centric home screen

Open into a dashboard, not a blank terminal.

- Recent hosts
- Active tmux sessions across all hosts
- Running tasks (deploy / build / training status)

### 6. Connection simplicity

SSH config import, identity management, GitHub/GitLab key import, QR-based host sharing, biometric unlock, Mosh support, auto-reconnect.

### 7. Modern mobile UX

Minimal typing. Thumb-friendly controls. Smooth gestures. Fast transitions. Offline-aware reconnect. Dark-mode optimized. Large touch targets. Haptic feedback.

### 8. Differentiation from existing SSH clients

Most SSH apps are desktop terminal emulators squeezed onto mobile — keyboard-heavy, session-unaware, not optimized for agents. PocketShell instead focuses on tmux-native workflows, touch-first navigation, persistent remote workspaces, supervising AI agents from anywhere, reducing terminal friction on mobile.

---

## Positioning

**Short:** A mobile-first SSH and tmux client designed for AI agent workflows.

**Long:** A modern Android SSH client that makes remote terminals usable on mobile through touch-first navigation, persistent tmux sessions, and optimized workflows for AI agents and remote development.

## Inspiration & inputs

- **tmuxctl** — the existing CLI workflow (recency-sorted sessions, attach-by-index, `:current`, recurring jobs). PocketShell should incorporate these patterns directly.
- **ssh-auto-forward** — port-forwarding semantics (sibling folder).
- **Termius** — the bar for "premium" terminal UX on Android. PocketShell should match its polish.
