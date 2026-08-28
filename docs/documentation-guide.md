# Documentation Guide

How PocketShell's docs are organized, and how to keep them that way. Read this before restructuring, adding, pruning, or otherwise doing meaningful work ON the documentation itself (as opposed to just reading a doc to do product/process work) - it's the map for that specific task.

## The core idea: two always-loaded files, everything else lazy

`CLAUDE.md` is one line: `@AGENTS.md`. Every session loads it.

`AGENTS.md` is a short orientation - project summary, a docs index, one-line-per-rule process pointers, environment facts an agent needs on effectively every session, and maintainer working style. It ends with `@process.md`, so process.md loads on every session too. Together these two files are the only documentation every agent pays for regardless of task - keep both short on principle. Before adding anything to either, ask: does every agent need this on every single session? If not, it belongs in a doc that gets linked, not inlined.

Everything else lives under `docs/` (or `.claude/agents/` for role prompts) and loads only when an agent's task calls for it, via a link from AGENTS.md, process.md, an issue brief, or another doc. That's the lazy-loading discipline: an agent should be able to do most tasks having read only CLAUDE.md → AGENTS.md → process.md, and reach for a specific doc only when the task at hand actually needs it.

## Situation -> which doc to load

| If you're... | Load |
|---|---|
| Cutting, stabilizing, or tagging a release | [release.md](release.md), [.claude/agents/release-owner.md](../.claude/agents/release-owner.md) |
| Creating, merging, or cleaning up a worktree | [worktrees.md](worktrees.md) |
| About to trust a "green" build/test/gate run as evidence | [ci-pitfalls.md](ci-pitfalls.md) |
| Reviewing terminal, session-switch, reconnect, or visual/layout/keyboard work | [review-standards.md](review-standards.md) |
| Hitting an environment/tooling snag not covered by AGENTS.md's quick facts | [lessons-learned.md](lessons-learned.md) |
| Needing the full rationale behind a locked decision (a D-number) | [decisions.md](decisions.md) |
| Restructuring, adding, or pruning documentation itself | this file |
| Setting up or debugging the emulator/Docker test environment | [testing.md](testing.md), [docker-emulator-runbook.md](docker-emulator-runbook.md) |
| Doing UI/design work | [design-system.md](design-system.md), [design-language.md](design-language.md), [ux-rules.md](ux-rules.md) |
| Working on voice/composer/key-bar input | [input-methods.md](input-methods.md) |
| Working on agent detection or the conversation view | [agent-awareness.md](agent-awareness.md) |
| Working on the usage/quota panel | [usage-panel.md](usage-panel.md) |
| Working on SSH host import via QR | [ssh-qr-import.md](ssh-qr-import.md) |
| Working on the server-side `pocketshell` CLI setup | [server-setup.md](server-setup.md) |
| Recovering a split-brained tmux socket | [tmux-socket-recovery.md](tmux-socket-recovery.md) |
| Need the module/architecture map | [architecture.md](architecture.md) |
| Need the original product brief or phased roadmap | [vision.md](vision.md), [roadmap.md](roadmap.md) |

If a doc you need isn't in this table, check [README.md](README.md)'s full index - this table is a curated shortlist of the highest-traffic lookups, not a complete duplicate of that index.

## Writing/editing a doc

- No decorative bold. `**text**` for pure emphasis costs tokens and adds no information an agent can act on - use plain prose. Code spans (`` `like this` ``), headers, and links are structural, not decorative, and stay.
- Say it once, plainly. Prefer the shorter sentence that keeps every load-bearing fact - exact command, script path, flag, check name, gate ID (G1-G10), decision ID (D-number), numeric threshold - over a longer one that hedges or repeats the heading's topic in its first sentence.
- Don't narrate the incident. A rule earns its place by being durable and reusable; the specific date, issue number, and blow-by-blow postmortem that produced it usually doesn't need to survive in the doc a reader loads to follow the rule today. Keep just enough of the "why" that a future reader can judge an edge case. (git history and the closed issue still have the full story if anyone needs it.)
- One topic, one doc. If a section in AGENTS.md or process.md is growing into its own reference (a catalogue, a mechanics writeup, a checklist someone will search for), split it into its own doc under `docs/` and leave a one- or two-sentence pointer behind, per the pattern above.
- Update the index when you add or remove a doc: add/remove the row in [README.md](README.md), and add a row here if the doc answers a "when I'm doing X" lookup that belongs in the situation table above.

## Keeping docs from going stale

- A point-in-time doc (a handoff, an audit snapshot, a campaign writeup) is a good way to hand off mid-incident state, but it has a shelf life. When its cited issues are all closed, or the file itself says "superseded"/"shipped", delete it - don't archive it into a subdirectory. Git history is the archive.
- A doc that says "verify against current state before relying on this" (a point-in-time status section) is telling you it's already on its way to being wrong. If you're rewriting a doc and find one of these, either verify and update it or delete it - don't carry it forward unexamined.
- When in doubt about whether a doc/section is still needed, check whether its subject's issue numbers are still in the open range (compare against a recent commit's issue numbers) and whether anything else already links to it. An unreferenced, resolved-epic doc is a deletion candidate.
