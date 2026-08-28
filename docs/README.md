# PocketShell Docs

Product and engineering notes. Some planning docs preserve their original
context; the README and current feature docs track released behavior.

| File | What it covers |
|---|---|
| [vision.md](vision.md) | Original product brief — the why and the desired UX |
| [architecture.md](architecture.md) | Composition layout, tech stack, three load-bearing decisions |
| [input-methods.md](input-methods.md) | Voice, key bar, snippets, and composer behaviour — the alternative-to-typing strategy |
| [ssh-qr-import.md](ssh-qr-import.md) | Versioned SSH host import payload and helper commands for QR generation |
| [agent-awareness.md](agent-awareness.md) | Detecting Claude Code, Codex, OpenCode, and Grok Build in a tmux pane and showing a clean conversation view |
| [usage-panel.md](usage-panel.md) | Provider quota / usage tracking via server-side `pocketshell usage` over SSH — zero credentials on the phone |
| [diagnostics.md](diagnostics.md) | Shareable JSONL flight recorder for app, connection, network, and action events |
| [design-language.md](design-language.md) | Termius-inspired visual tokens |
| [design-system.md](design-system.md) | Codified dark dev-tool design tokens and shared UI primitives |
| [server-setup.md](server-setup.md) | Server-side `pocketshell` helper install, PATH, and troubleshooting |
| [ux-rules.md](ux-rules.md) | Placement + transition rules across journeys (codified from #163); cite from every UX-touching issue |
| [roadmap.md](roadmap.md) | Phased build order with rough sizing |
| [aplexer-integration.md](aplexer-integration.md) | Becoming an aplexer client: phases A–C; Phase A done, both managers listed |
| [decisions.md](decisions.md) | Log of what's locked, what's still open |
| [release.md](release.md) | How we cut, stabilize, tag, and merge back a release (release-owner agent) |
| [testing.md](testing.md) | Android emulator + Docker remote-server test environment |
| [docker-emulator-runbook.md](docker-emulator-runbook.md) | Docker profiles, ports, emulator commands, connected-test runbook |
| [walkthrough-visual-pass.md](walkthrough-visual-pass.md) | Real emulator screenshot capture workflow for visual review |
| [screenshots/](screenshots/) | Curated README screenshot assets captured from the visual-audit workflow |
| [tmux-socket-recovery.md](tmux-socket-recovery.md) | Default tmux socket split-brain detection, safe recovery, and automation namespace guardrails |
| [release-terminal-gate.md](release-terminal-gate.md) | Optional high-confidence terminal release gate (emulator + Docker chain) |
| [audit-2026-08-23-comprehensive-session-management.md](audit-2026-08-23-comprehensive-session-management.md) | Session-tree/connection audit and issue plan — delete once #2222, #2241–#2243, #2247, #2264, #2295 all close; no unique content will remain |
| [worktrees.md](worktrees.md) | Agent worktree layout, creation, and merge-back mechanics |
| [ci-pitfalls.md](ci-pitfalls.md) | Catalogue of ways a CI/gate run can look green while proving nothing — check before trusting a result |
| [review-standards.md](review-standards.md) | Reviewer acceptance bars for terminal/session/visual/journey work |
| [lessons-learned.md](lessons-learned.md) | Durable, recurring operational lessons not tied to any one epic |
| [documentation-guide.md](documentation-guide.md) | How these docs are organized and kept lazy-loaded; read before restructuring/adding/pruning any doc |
| [../AGENTS.md](../AGENTS.md) | Primary project + agent instructions (imported by [../CLAUDE.md](../CLAUDE.md)) |
| [../process.md](../process.md) | The implementer/reviewer/orchestrator process contract |
| [mockups/](mockups/) | Static HTML mockups, Pixel 7 viewport — open `mockups/index.html` in a browser |

## Related projects

| Path | Role |
|---|---|
| `../../ssh-auto-forward-android/` | Existing Kotlin/Compose app. Source of extractable SSH + port-forward modules. |
| `../../tmuxcli/` | Python CLI. PocketShell mirrors its job/session semantics through the server-side `pocketshell` helper. |
| `../../ssh-auto-forward/` | Python TUI. Reference for `ss -tlnp` parsing and reconnect/backoff logic. |
| `../../agent-log-explorer/` | Separate tool for browsing historical agent conversations. PocketShell does *current session* view directly; agent-log-explorer remains for *all history* search. |
| `../../heru/` | Historical provider quota reference; current app usage polling goes through `pocketshell usage --json`. |
| `../../litehive/` | Per-engine local invocation counters. Alternative data source for the usage panel. |
| `../../aplexer/` | Intended session runtime and engine/profile/launch registry. PocketShell becomes a client; see [aplexer-integration.md](aplexer-integration.md). |
