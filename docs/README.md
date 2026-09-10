# PocketShell Docs

Product and engineering notes. Some planning docs preserve their original
context; the README and current feature docs track released behavior.

| File | What it covers |
|---|---|
| [vision.md](vision.md) | Historical product brief — the original goals and UX vocabulary |
| [architecture.md](architecture.md) | Post-rewrite module map, tech stack, three load-bearing decisions, connect/session/terminal/grace design |
| [input-methods.md](input-methods.md) | Voice, key bar, snippets, and composer behaviour — the alternative-to-typing strategy |
| [ssh-qr-import.md](ssh-qr-import.md) | Versioned SSH host import payload and helper commands for QR generation |
| [agent-awareness.md](agent-awareness.md) | Live agent identity from an aplexer workload and host logs |
| [usage-panel.md](usage-panel.md) | Provider quota / usage tracking via server-side `pocketshell usage` over SSH — zero credentials on the phone |
| [settings-sync.md](settings-sync.md) | Optional Google sign-in + end-to-end-encrypted host sync, and the OAuth client registration it is blocked on |
| [diagnostics.md](diagnostics.md) | Shareable JSONL flight recorder for app, connection, network, and action events |
| [design-language.md](design-language.md) | Termius-inspired visual tokens |
| [design-system.md](design-system.md) | Codified dark dev-tool design tokens and shared UI primitives |
| [server-setup.md](server-setup.md) | Server-side `pocketshell` helper install, PATH, and troubleshooting |
| [ux-rules.md](ux-rules.md) | Placement + transition rules across journeys (codified from #163); cite from every UX-touching issue |
| [roadmap.md](roadmap.md) | Phased build order with rough sizing |
| [rewrite-diagnosis-and-design.md](rewrite-diagnosis-and-design.md) | app2 rewrite: diagnosis of the old app's complexity and the target architecture |
| [rewrite-implementation-plan.md](rewrite-implementation-plan.md) | Historical app2 rewrite playbook, superseded by the shipped app2/aplexer contract |
| [aplexer-integration.md](aplexer-integration.md) | Aplexer integration record: current runtime status and historical transition notes |
| [decisions.md](decisions.md) | Log of what's locked, what's still open |
| [release.md](release.md) | How we cut candidate, stabilize, fast-forward the exact SHA to main, push main, and tag from main (release-owner agent) |
| [testing.md](testing.md) | Android emulator + Docker remote-server test environment |
| [docker-emulator-runbook.md](docker-emulator-runbook.md) | Docker fixture targets, ports, emulator commands, connected-test runbook |
| [screenshots/](screenshots/) | Curated README screenshot assets captured from the visual-audit workflow |
| [tmux-socket-recovery.md](tmux-socket-recovery.md) | Operational runner-only tmux socket recovery and namespace guardrails |
| [audit-2026-08-23-comprehensive-session-management.md](audit-2026-08-23-comprehensive-session-management.md) | Session-tree/connection audit and issue plan — delete once #2222, #2241–#2243, #2247, #2264, #2295 all close; no unique content will remain |
| [audit-2026-08-30-code-quality.md](audit-2026-08-30-code-quality.md) | Five-reviewer whole-codebase audit of correctness, inefficiency, duplication, dead code, and simplification — delete when its retained findings are tracked and resolved or superseded |
| [worktrees.md](worktrees.md) | Agent worktree layout, creation, and merge-back mechanics |
| [ci-pitfalls.md](ci-pitfalls.md) | Catalogue of ways a CI/gate run can look green while proving nothing — check before trusting a result |
| [review-standards.md](review-standards.md) | Reviewer acceptance bars for terminal/session/visual/journey work |
| [lessons-learned.md](lessons-learned.md) | Durable, recurring operational lessons not tied to any one epic |
| [documentation-guide.md](documentation-guide.md) | How these docs are organized and kept lazy-loaded; read before restructuring/adding/pruning any doc |
| [../AGENTS.md](../AGENTS.md) | Primary project + agent instructions (imported by [../CLAUDE.md](../CLAUDE.md)) |
| [../process.md](../process.md) | The implementer/reviewer/orchestrator process contract |

## Related projects

| Project | Role |
|---|---|
| [ssh-auto-forward-android](https://github.com/alexeygrigorev/ssh-auto-forward-android) | Existing Kotlin/Compose app. Source of extractable SSH + port-forward modules. |
| [tmuxctl](https://github.com/alexeygrigorev/tmuxctl) | Historical session tool; it is not a PocketShell product runtime. |
| [ssh-auto-forward](https://github.com/alexeygrigorev/ssh-auto-forward) | Python TUI. Reference for `ss -tlnp` parsing and reconnect/backoff logic. |
| `agent-log-explorer` | Separate local tool with no published GitHub remote. PocketShell does *current session* view directly; agent-log-explorer remains for *all history* search. |
| [heru](https://github.com/alexeygrigorev/heru) | Historical provider quota reference; current app usage polling goes through `pocketshell usage --json`. |
| [aplexer](https://github.com/alexeygrigorev/aplexer) | The shipped session runtime and engine/profile/launch registry; see [aplexer-integration.md](aplexer-integration.md). |
