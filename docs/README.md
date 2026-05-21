# PocketShell Docs

Conceptual planning. Captured 2026-05-21, before any code is written.

| File | What it covers |
|---|---|
| [vision.md](vision.md) | Original product brief — the why and the desired UX |
| [architecture.md](architecture.md) | Composition layout, tech stack, three load-bearing decisions |
| [input-methods.md](input-methods.md) | Voice, key bar, chord palette, snippets — the alternative-to-typing strategy |
| [agent-awareness.md](agent-awareness.md) | Detecting Claude Code / Codex / OpenCode in a pane and showing a clean conversation view |
| [design-language.md](design-language.md) | Termius-inspired visual tokens |
| [roadmap.md](roadmap.md) | Phased build order with rough sizing |
| [decisions.md](decisions.md) | Log of what's locked, what's still open |
| [mockups/](mockups/) | Static HTML mockups, Pixel 7 viewport — open `mockups/index.html` in a browser |

## Related projects

| Path | Role |
|---|---|
| `../../ssh-auto-forward-android/` | Existing Kotlin/Compose app. Source of extractable SSH + port-forward modules. |
| `../../tmuxcli/` | Python CLI. PocketShell mirrors its semantics; recurring jobs run server-side via `tmuxctl jobs daemon` on each host. |
| `../../ssh-auto-forward/` | Python TUI. Reference for `ss -tlnp` parsing and reconnect/backoff logic. |
| `../../agent-log-explorer/` | Separate tool for browsing historical agent conversations. PocketShell does *current session* view directly; agent-log-explorer remains for *all history* search. |
