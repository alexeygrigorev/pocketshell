# Decisions Log

Captured 2026-05-21 during conceptual planning, before any code.

## Locked

| # | Decision | Rationale |
|---|---|---|
| D1 | App name PocketShell, package `com.pocketshell.app` | Matches working directory; clean, available-looking. |
| D2 | Composition, not fork | Extract reusable parts of `ssh-auto-forward-android` into shared Gradle modules. Both apps consume them. Neither reaches into the other. |
| D3 | sshj, not JSch | JSch is unmaintained; sshj supports ed25519 and modern KEX. Do the swap during extraction, not after. |
| D4 | Vendored Termux `terminal-emulator` + `terminal-view` | Battle-tested xterm-256color. Writing a VT emulator from scratch is a 6-month detour. |
| D5 | `tmux -CC` control mode, not screen-scraping | Structured protocol gives us per-pane state. The single most important call for tmux-native UX. |
| D6 | Per-pane rendering with swipe navigation, not tiled tmux | Tmux's split layout is unreadable on a phone. PocketShell renders one pane at a time; PocketShell *is* the status bar. |
| D7 | Server-side recurring jobs via `tmuxctl jobs daemon` on each host | Jobs survive phone offline / sleep. Trade-off: host bootstrap becomes a first-class onboarding step (detect/install `tmuxctl`). |
| D8 | Termius-inspired design language, built in shared `ui-kit` module | Both apps converge on one visual language. Avoids per-screen redesign. |
| D9 | Voice input is first-class. Engine: Whisper via OpenAI Audio Transcriptions API. | Better than Android `SpeechRecognizer` for technical content. Per-request cost accepted. Existing `openai-transcribe` skill is the integration reference. |
| D10 | Mic trigger: tap-to-toggle, auto-stop after 5s silence | Best for long agent prompts. Hold-to-talk is fatiguing past 10s. |
| D11 | Prompt composer is a bottom sheet (modal over terminal) | Cleanest hierarchy for review-and-send. Terminal dims behind. |
| D12 | Key bar always visible above the keyboard (only while keyboard is up) | One-tap Esc/Tab/Ctrl/Alt/arrows. Matches "super smooth" requirement, zero discovery cost. |
| ~~D13~~ | ~~Chord palette opens on long-press of ⚡ in the key bar~~ | Superseded by D18. |
| D14 | Agent-aware conversation view — read THIS session's JSONL directly over SSH | Solves the "can't scroll back through agent output on mobile" problem. Scope is current pane / current session only — not historical search (that's agent-log-explorer's job). No host dependency. |
| D15 | Conversation surface: tab at top of session view `[Terminal] [Conversation]` | Tab hidden when no agent detected. Hint chip in terminal view appears once per detected session for discoverability. |
| D16 | Agent detection: cwd + recent JSONL modification + ps confirmation | Works for Claude Code (`~/.claude/projects/<encoded-cwd>/`), Codex (`~/.codex/sessions/`), OpenCode (`~/.local/share/opencode/opencode.db`). Silent fallback when nothing matches. |
| D17 | New shared module `core-agents` for parsers + detection | Unit-testable without SSH. Each agent has its own parser producing a normalized `ConversationEvent` stream consumed by the UI. |
| D18 | Drop chord palette and ⚡ lightning key from v1. Key bar shrinks to 8 slots (Esc, Tab, Ctrl, Alt, ←↑↓→) | User feedback: detach + session switch are the only chords they actually want, and both are better served by native UI (back arrow = detach, tap session name = switcher). Power-user chord palette may return as opt-in post-v1 if demand appears. |
| D19 | Usage panel data is fetched server-side over SSH. Default source: `heru usage --json`. Pluggable per host (user can override command). | PocketShell holds zero provider credentials (no OAuth, no API keys for Anthropic/OpenAI/GitHub). If a host doesn't have the tool installed, that host is omitted from the usage panel — no error, no setup nag. |
| D20 | New shared module `core-usage` to parse normalized JSON from server-side usage tools | Pluggable parsers per provider. Same compose-the-server-tool pattern as `core-agents`. |

## Open

| # | Question | Notes |
|---|---|---|
| O1 | What happens to `ssh-auto-forward-android` long-term? | Two options: (a) keeps existing as a focused port-forwarder, both apps coexist; (b) retired once PocketShell ships its port panel. Decide after Phase 3. |
| O2 | When does Mosh land? | Currently deferred to Phase 4. Requires shipping `mosh-server` binary or assuming remote install + UDP path. Real value for flaky mobile networks, but multi-week commitment. |
| O3 | UI prototyping medium before Compose? | We need a way to validate layouts without round-tripping APKs to a device. To be answered next. |
| O4 | Where does `shared/` live? | Sibling folder, separate repo, or git-submodule. Probably sibling folder until either app needs to release independently. |
| O5 | SFTP / file transfer surface? | Out of scope for v1. Revisit if demand emerges. |

## Superseded / rejected

| # | Considered | Why rejected |
|---|---|---|
| R1 | Fork `ssh-auto-forward-android` in place | Faster short-term, but locks branding (`com.sshautoforward`, Play listing) and makes the existing app harder to maintain separately. |
| R2 | Phone-side scheduler for recurring jobs | Simpler, but jobs would pause when phone sleeps / loses network — defeats the point of "supervise long-running agents from mobile." |
| R3 | Render tmux's tiled split layout | Unreadable at phone scale. |
| R4 | WebView + xterm.js for terminal | Possible, but feels less native; harder to integrate Compose gestures cleanly. |
