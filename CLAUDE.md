# PocketShell

Voice-first, tmux-native, agent-aware Android SSH client.

Conceptual planning is complete. Implementation in progress, tracked as GitHub issues across phases 0–4. Visual spec is HTML mockups in `docs/mockups/` (Pixel 7 viewport). Locked design decisions are in `docs/decisions.md`.

## Key docs

- [docs/README.md](docs/README.md) — full index of planning docs
- [docs/architecture.md](docs/architecture.md) — module layout, sshj, tmux -CC, per-pane rendering
- [docs/roadmap.md](docs/roadmap.md) — phased build (0–4) with sizing
- [docs/decisions.md](docs/decisions.md) — locked design decisions, open questions, rejected alternatives
- [docs/input-methods.md](docs/input-methods.md) — voice (Whisper) + key bar + snippets
- [docs/agent-awareness.md](docs/agent-awareness.md) — Claude Code / Codex / OpenCode detection + conversation view
- [docs/usage-panel.md](docs/usage-panel.md) — provider quota via server-side `heru`
- [docs/testing.md](docs/testing.md) — Android emulator + Docker remote-server test environment
- [docs/mockups/index.html](docs/mockups/index.html) — Pixel 7 mockups (open in a browser; serve at `python3 -m http.server --directory docs/mockups`)

## Issue tracker

https://github.com/alexeygrigorev/pocketshell/issues — organised by milestone (Phase 0–4).
https://github.com/alexeygrigorev/pocketshell/milestones — phase progress.

## Working model — orchestrator + sub-agents

Work is delegated to sub-agents via the `Agent` tool. The orchestrator (this main thread) plans, briefs, parallelises, verifies, and merges. Sub-agents do focused implementation work for one issue at a time and never see the orchestrator's conversation.

The full process — briefing, parallelisation rules, verification checklist, QA against emulator + Docker — is below.

@agents.md
