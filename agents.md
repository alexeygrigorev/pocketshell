# Agent Orchestration

How work on PocketShell is delegated, reviewed, and merged.

## Roles

**Orchestrator** (Claude in the main thread). Plans, delegates, reviews, integrates. Never writes the bulk of feature code directly — instead briefs sub-agents, verifies their output, and commits.

**Sub-agent** (Claude launched via the `Agent` tool). Does focused implementation work for one issue at a time. Doesn't see the orchestrator's conversation; needs a self-contained brief every time.

## Responsibility

**The orchestrator is responsible for what gets merged.** An agent's "I successfully implemented X" claim is not enough. The orchestrator must verify X actually works — build, test, read the diff — before commit. Bad merges are the orchestrator's fault, not the agent's.

## Default workflow per issue

1. **Pick an issue.** `gh issue view N` to read scope and acceptance criteria.
2. **Decide isolation.** Use `isolation: "worktree"` when running multiple agents in parallel, or when an agent's work might conflict with other in-flight work. Single-agent on a fresh branch otherwise.
3. **Brief the agent.** Self-contained prompt: scope, relevant code locations, acceptance criteria, exact file paths, docs to read, what to NOT touch. Always link the issue (`gh issue view N --json url`).
4. **Agent executes.** Foreground by default. Background only when the orchestrator has genuinely independent work to do meanwhile.
5. **Verify.** Don't trust the summary. Run the [verification checklist](#verification-checklist).
6. **Iterate if needed.** Continue the same agent via `SendMessage` for tight corrections; launch a fresh agent if the context is messy.
7. **Commit + PR.** Orchestrator creates the commit. Opens the PR via `gh pr create`, linking the issue with `Closes #N`.
8. **Self-merge after PR review,** then close the issue.

## Branch & PR conventions

- Branch name: `issue-N-short-kebab-slug` (e.g. `issue-1-gradle-scaffold`)
- Commit messages: imperative mood, scoped to the issue (`scaffold gradle multi-module project`)
- PR title: same as issue title
- PR body: `Closes #N` + 2-4 bullet test-plan items
- One issue per PR. If the agent grew scope, split.

## Briefing rules

A bad brief is the #1 source of bad agent output. Always include:

- **Issue number and title** — canonical scope
- **Relevant docs** — e.g. for Phase 0 issues: `docs/architecture.md`, `docs/decisions.md`
- **Exact file paths** when modifying existing code (not "edit the build file" — `app/build.gradle.kts`)
- **Acceptance criteria** verbatim from the issue
- **Non-goals** — "don't refactor anything outside this issue's scope"
- **What to commit vs leave** — usually: leave commit/PR for the orchestrator
- **Reference projects** when the work is an adaptation — e.g. for SSH extraction: `/home/alexey/git/ssh-auto-forward-android/`

Terse command-style prompts produce shallow work. Brief like a smart colleague who hasn't seen the conversation.

## Choosing the sub-agent type

| Task | `subagent_type` |
|---|---|
| Read-only research, "where is X", finding files | `Explore` |
| Multi-step implementation work | `general-purpose` |
| Designing implementation strategy before coding | `Plan` |
| Reviewing a completed change | `code-reviewer` (if available) |

Default for Phase 0–4 implementation: `general-purpose`.

## Dependency analysis (when to parallelize)

Before launching agents, study the issue dependency graph. Many issues are independent and can run in parallel using worktrees, cutting wall-clock time dramatically.

### Example: Phase 0 graph

```
#1 Gradle scaffold (root)
├── #2 App module
│   └── #10 CI workflow
└── #3 Shared module scaffolds
    ├── #4 core-ssh ──── #5 core-portfwd
    ├── #6 core-storage
    └── #7 Vendor terminal ── #8 Compose adapter
                                └── (with #2, #4) #9 Proof-of-life
```

After #1 ships, **#2 and #3 run in parallel** (one agent each, separate worktrees).

After #2 and #3 both ship: **#4, #6, #7, #10 all run in parallel** — four streams. Then #5 follows #4, #8 follows #7, and #9 closes the phase once #2, #4, #8 are merged.

That cuts a 10-step serial chain to ~4 sequential rounds of mostly parallel work.

### How to spot parallelism

- Different modules → usually parallel
- Same module, different files → maybe parallel (verify no Gradle / AndroidManifest conflicts)
- Same file → never parallel

### How to launch parallel agents

In **one orchestrator message**, multiple `Agent` tool calls:

```
Agent({description: "Issue #4 core-ssh", isolation: "worktree", prompt: ...})
Agent({description: "Issue #6 core-storage", isolation: "worktree", prompt: ...})
Agent({description: "Issue #7 Vendor terminal", isolation: "worktree", prompt: ...})
```

Each agent gets its own worktree, can't conflict with the others. Orchestrator collects results when they finish.

Verification still happens serially after merge — one PR at a time.

## Parallel agents — limits

**Don't parallelize:**

- Two issues touching the same module (race on Gradle files, etc.)
- Sequential dependencies (issue B depends on issue A's output)
- Anything where verification of one would block the other
- More than ~4 agents at once — verification queue gets unwieldy

## Verification checklist

After every agent run, before committing:

- [ ] `git status` shows the expected file list — no surprises
- [ ] `git diff` reads sensibly — no hallucinated code, no scope creep, no commented-out junk
- [ ] Build succeeds: `./gradlew assembleDebug` (once Phase 0 ships) or relevant subcommand
- [ ] Tests pass (if any exist for the touched code)
- [ ] No accidental commits of secrets, generated files (`build/`, `.gradle/`, `local.properties`)
- [ ] Issue's acceptance criteria are *demonstrably* met, not just claimed
- [ ] **For UI changes**: installed on the Android emulator and visually compared with the matching mockup at `docs/mockups/<screen>.html`
- [ ] **For SSH / tmux / agent / usage changes**: relevant integration test against the Docker remote server passes

If any check fails: **don't commit.** Re-engage the agent with the specific failure, or do the fix directly if it's trivial.

## Quality assurance

Two emulation surfaces let the orchestrator verify changes without touching real devices or hosts:

- **Android emulator** for UI / visual validation
- **Docker remote server** (sshd + tmux + agent CLIs + helper tools) for SSH / tmux / agent-detection / usage tests

Both are first-class. Every PR that touches the relevant layer is validated against the corresponding surface before merge. The orchestrator runs this — it is not delegated to the sub-agent. Sub-agents may write the test, but the orchestrator runs it and judges the result.

Full setup, image build instructions, fixture conventions: [docs/testing.md](docs/testing.md)

## When to skip delegation

Some work is faster done directly than briefing an agent:

- Trivial single-line edits
- Reading and summarising a file
- Running a one-shot CLI command
- Reviewing an agent's output
- Editing docs (the orchestrator wrote them; they have context)

Rule of thumb: if briefing the agent would take longer than just doing it, just do it.

## Communication style

When updating the user (the human, not the agent):

- State results, not narration
- "Issue #1 done, PR opened: <url>" — not "I'm going to delegate this to an agent and then verify..."
- If an agent failed: say what failed and what you'll do, briefly
- If you need a decision: ask one specific question, not three options

## Anti-patterns to avoid

- **Delegating without verification.** Always check before commit.
- **Delegating understanding.** Don't say "based on what the agent found, do X." Read the output yourself, synthesise, then act.
- **Long agent chains.** If issue work spans 5+ agent turns, the brief was wrong. Stop, regroup, re-brief.
- **Agent commits directly to main.** Never. Orchestrator commits.
- **Parallel agents on overlapping files.** Conflicts will eat the time savings.
- **Skipping the brief because "it's obvious".** The agent has zero context. Always brief.

## Process evolution

This file is the playbook, not scripture. If a pattern emerges (a kind of brief that works well, a failure mode that keeps recurring, a verification step that catches bugs) — update this file. The orchestrator owns the process as much as the code.
