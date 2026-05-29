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
| D16 | Agent detection: tmux pane cwd + recent JSONL modification + ps confirmation | Runtime-enabled only for Claude Code (`~/.claude/projects/<encoded-cwd>/`). Codex (`~/.codex/sessions/`) and OpenCode (`~/.local/share/opencode/opencode.db`) parsers exist, but runtime detection stays disabled until PocketShell has safe session/pane correlation. Silent fallback when nothing matches. |
| D17 | New shared module `core-agents` for parsers + detection | Unit-testable without SSH. Each agent has its own parser producing a normalized `ConversationEvent` stream consumed by the UI. |
| D18 | Drop chord palette and ⚡ lightning key from v1. Key bar shrinks to 8 slots (Esc, Tab, Ctrl, Alt, ←↑↓→) | User feedback: detach + session switch are the only chords they actually want, and both are better served by native UI (back arrow = detach, tap session name = switcher). Power-user chord palette may return as opt-in post-v1 if demand appears. |
| D19 | Usage panel data is fetched server-side over SSH. Default source: `heru usage --json`. Pluggable per host (user can override command). | PocketShell holds zero provider credentials (no OAuth, no API keys for Anthropic/OpenAI/GitHub). If a host doesn't have the tool installed, that host is omitted from the usage panel — no error, no setup nag. |
| D20 | New shared module `core-usage` to parse normalized JSON from server-side usage tools | Pluggable parsers per provider. Same compose-the-server-tool pattern as `core-agents`. |
| D21 | PocketShell does not run in the background, with one carve-out for auto-forward (locked 2026-05-27, issue #161; carve-out 2026-05-27, issue #203) | App must not consume battery while the user is not interacting with it. No `WorkManager`, no `AlarmManager`, no `setRepeating`, no `Timer`, no `ScheduledExecutorService`. Periodic schedulers (today: `UsageScheduler`) hook `ProcessLifecycleOwner` and pause on `Lifecycle.State.STOPPED`; they resume on `STARTED`. Tmux on the remote keeps the long-running state; the client reconnects on next foreground. The Active Sessions home-screen widget is allowed (system-driven, not the app holding wakelocks). **Carve-out (issue #203 expanded scope):** the auto-forward feature MAY run a foreground service while ≥1 tunnel is active, because the tunnels are bound to the device-side SSH transport and die the moment the app process backgrounds — without a service, the user loses the entire feature's value (port forwards to `localhost:N` go dark). The carve-out is scoped: ONLY the auto-forward path, ONLY while the user has at least one active or auto-forward-enabled host, with a visible persistent notification, and torn down the moment the user disables auto-forward across all hosts. Everything else (usage poll, agent detection, voice composer, ...) still falls under the strict rule. |
| D22 | No backwards-compatibility: hard cuts only (locked 2026-05-27) | The maintainer is the only user of PocketShell today. There is no install base to migrate, no third-party API to honor, no SDK consumer to warn. Every implementer and reviewer must assume hard-cut semantics. When a feature replaces an older one, delete the older in the same PR — no legacy detection path, no deprecation shim, no settings flag for "use the old behaviour", no fallback branch. When a schema changes, either ship the migration OR nuke the DB — but never both a migration AND a code-level compatibility branch. `fallbackToDestructiveMigration(dropAllTables = true)` is acceptable when a feature requires it; the maintainer explicitly accepted: "if it means nuking the current settings, it's fine. I also want eventually to test configuring it with QR code, so it should be relatively fast for me to restore." When porting from upstream (e.g. `ssh-auto-forward-android`, the unified `pocketshell` daemon, etc.), DO NOT port legacy-detection shims that the upstream kept for its own install-base reasons. The orchestrator removes legacy code proactively as part of every round, not as a separate cleanup task. |
| D23 | GitHub-aware navigation goes through the dev box's `gh` CLI, not phone-side OAuth (locked 2026-05-27, issue #205) | Mirrors D19's "zero provider credentials on the phone" stance. `pocketshell repos list --remote` and any future clone-on-tap operation run server-side via the maintainer's existing `gh auth` setup on the dev box; the phone is a viewer. Three forcing reasons: (1) precedent — `UsageRemoteSource.kt` already says "never asks for provider OAuth/API keys"; (2) the clone target IS the dev box, so listing on the phone but cloning on the dev box is a split-actor dead-end; (3) zero new auth surface on the phone. The `gh` token scope can be narrowed to `repo:read` for browse-only flows; `gh repo clone` uses SSH keys (no PAT write scope). Audit token scope as part of `HostBootstrapper`. |
| D24 | Env files (.env/.envrc) edited via server-side `pocketshell env` (locked 2026-05-29, issue #262) | Values written via stdin JSON never argv; both files managed when present (each key tagged with its file); new keys default to `.env`; plain reveal, no biometric. |

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
