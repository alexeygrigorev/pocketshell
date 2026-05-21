# Agent Orchestration

PocketShell uses a three-actor process: orchestrator + implementer + reviewer. The orchestrator (Claude in the main thread) prepares issues, dispatches agents, and ensures the process is followed. Agents never talk to each other directly — they communicate through GitHub issue comments, with the orchestrator as messenger.

## Named agent definitions

Canonical role definitions live in [.claude/agents/](.claude/agents/):

- [.claude/agents/implementer.md](.claude/agents/implementer.md) — the implementer's system prompt
- [.claude/agents/reviewer.md](.claude/agents/reviewer.md) — the reviewer's system prompt

When the harness recognises these names, the orchestrator launches them with `subagent_type: "implementer"` and `subagent_type: "reviewer"`. Until then, the orchestrator launches `general-purpose` agents with a brief that tells the agent to read its canonical role file first.

The body of this `agents.md` describes how the orchestrator dispatches and coordinates them. The per-role workflow, hard rules, and comment formats live in the agent files themselves.

## Actors

### Orchestrator (main thread)

Owns:

- Reading new asks from the user, refining them into well-shaped issues
- Ensuring each issue has scope, acceptance criteria, file paths, doc links, non-goals
- Launching the implementer sub-agent with a self-contained brief
- Launching the reviewer sub-agent once the implementer reports done
- Relaying review feedback to the implementer (via a fresh implementer run) if changes are requested
- Running the pre-merge QA gate (build, emulator, Docker)
- Committing to main and closing the issue after the reviewer approves
- Keeping this file (`agents.md`) up to date when the process needs to evolve

Never:

- Writes the bulk of implementation code directly (small fixes excepted — see the "When to skip delegation" section)

### Implementer (sub-agent)

Does:

- Reads the issue, linked docs, relevant existing code
- Writes code and tests in the local working tree
- Runs the build and tests itself before reporting done
- Posts a status comment on the issue (`gh issue comment N --body "..."`) with:
  - List of files changed
  - Build / test results (paste the last lines of output)
  - Judgment calls (version choices, naming, scope edges)
  - Open questions if any
- If the reviewer rejected a previous attempt: reads the rejection comment first, addresses every item, then posts a new status comment summarising the fixes

Does NOT:

- Commit, push, or close the issue
- Modify files outside the issue's scope
- Argue with the reviewer in comments — fix the code, then post a new status

### Reviewer (sub-agent)

Does:

- Reads the implementer's most recent status comment and the actual working-tree diff
- Runs the build (`./gradlew assembleDebug` or the relevant subcommand) and any tests
- Checks each acceptance-criteria checkbox on the issue body explicitly
- Looks for: bugs, missing tests, dead code, scope creep, security issues, style drift, version mismatches, ignored docs
- Posts a review comment on the issue using one of two clear shapes:
  - `APPROVED` — orchestrator may commit and close
  - `CHANGES REQUESTED` — bulleted, specific, each item actionable

Does NOT:

- Commit, push, or close the issue
- Edit code itself (suggest changes only)
- Approve without running the build and tests

## Communication

GitHub Issues are the contract. Every artifact lives there:

- Issue body: scope, acceptance criteria with `- [ ]` checkboxes, doc links, non-goals
- Implementer comments: status reports
- Reviewer comments: `APPROVED` or `CHANGES REQUESTED`
- Orchestrator comments: relays, decisions, links to the eventual commit

Agents never talk to each other directly. The orchestrator is always the messenger. This keeps the audit trail complete and observable from the issue page alone.

## Workflow per issue

1. Orchestrator refines the issue. Acceptance criteria must be specific and verifiable.
2. Orchestrator launches an implementer agent in the background with a self-contained brief.
3. The orchestrator returns control to the user immediately; the agent runs asynchronously.
4. When notified that the implementer finished, orchestrator launches a reviewer agent (also background) with the issue number and the implementer's comment URL.
5. Reviewer reads, runs build / tests, posts a review comment.
6. If `CHANGES REQUESTED`:
   - Orchestrator launches a fresh implementer (no memory between runs) with a brief that includes the rejection comment verbatim
   - Loop to step 3
7. If `APPROVED`:
   - Orchestrator runs the [verification checklist](#verification-checklist) one last time
   - Orchestrator commits to main with `Closes #N`, pushes, GitHub auto-closes the issue

## Launching agents — async by default

Every implementer and reviewer launch uses `run_in_background: true`. This is the default, not the exception. Reasons:

- The user must be able to ask questions and redirect work without waiting on any agent
- The orchestrator keeps a non-blocking loop: while one agent works, the orchestrator can be refining the next issue, answering questions, reviewing another agent's output, or running QA checks
- The harness notifies the orchestrator automatically when an async agent completes — no polling, no sleeping

Foreground launches are only justified when the orchestrator genuinely needs the agent's output to compose the very next message (rare).

## Parallelism in practice

Async + parallel are two different things. Async lets the orchestrator stay responsive to the user. Parallelism is about running multiple agents at once on different issues.

To run agents in parallel, send multiple `Agent` tool calls in one orchestrator message. Each launches in the background; the orchestrator gets one notification per completion.

### When parallel is safe

- Issues touch entirely different files (different modules, different paths)
- No issue depends on another's not-yet-merged work

Example from Phase 0 Round 3: `#4 core-ssh`, `#6 core-storage`, `#7 vendor terminal`, `#10 CI workflow`. Each touches a different module path. All four can run in parallel.

### When parallel is NOT safe

- Two issues edit the same file (e.g. both want to modify `settings.gradle.kts` or `gradle/libs.versions.toml`). They will clobber each other's edits in the same working tree.
- One issue's output is the next issue's input (sequential dependency).

Example: `#2 app module` and `#3 shared scaffolds` both edit `settings.gradle.kts` and `libs.versions.toml`. They run sequentially until git worktree isolation is wired up.

### Future: true isolation

The `Agent` tool supports `isolation: "worktree"` to run each agent in its own git worktree, which would let overlapping-file issues run in parallel safely. This requires `WorktreeCreate` / `WorktreeRemove` hooks in `~/.claude/settings.json` and is not currently configured for this repo. Set up later if Phase 0 wall-clock time becomes painful.

Until then: read the dependency graph, parallelise the non-overlapping issues, sequence the overlapping ones.

## Briefing rules

A bad brief is the top source of bad output. Always include in the implementer brief:

- Issue number and URL
- Project context — link `CLAUDE.md`, relevant `docs/*.md`
- Reference code — exact paths in other repos when adapting
- Scope and acceptance criteria, verbatim from the issue
- Exact file paths to create or modify
- Non-goals — what to NOT touch
- Required deliverable: `gh issue comment N --body "..."`
- Hard rule: do not commit, do not push, leave the working tree dirty

For the reviewer brief, always include:

- Issue number and URL
- The implementer's most recent comment URL
- Instruction to run the build and tests
- Instruction to verify each acceptance-criteria checkbox
- Required deliverable: a single review comment with `APPROVED` or `CHANGES REQUESTED`
- Hard rule: do not edit code, do not commit, do not close the issue

Terse command-style prompts produce shallow work. Brief like a smart colleague who hasn't seen the conversation.

## Issue quality (orchestrator's responsibility)

A bad issue produces a bad implementation. Every issue must have:

- Title: imperative, specific
- Scope: bulleted list of files or behaviours to add or change
- Acceptance criteria: `- [ ]` checklist, machine-verifiable where possible
- Non-goals: what's explicitly out of scope
- Doc links: relevant architecture / design / decisions docs
- Reference code: paths in other repos that show the pattern

If the implementer or reviewer comes back asking "what does this mean?" — fix the issue first, then re-launch.

## Dependency analysis (when to parallelise)

Study the issue dependency graph before launching. Many issues are independent and could run in parallel if isolation is available.

Phase 0 graph:

```
#1 Gradle scaffold (root)
+-- #2 App module
|   +-- #10 CI workflow
+-- #3 Shared module scaffolds
    +-- #4 core-ssh ---- #5 core-portfwd
    +-- #6 core-storage
    +-- #7 Vendor terminal ---- #8 Compose adapter
                                +-- (with #2, #4) #9 Proof-of-life
```

After #1 ships, #2 and #3 are independent except for both editing `settings.gradle.kts`. After they ship, #4 / #6 / #7 / #10 are four independent streams.

### Sequential vs parallel

Parallel work needs isolation. The Agent tool's built-in `isolation: "worktree"` is not currently configured for this repo, so we run sequentially. To unlock parallel runs later, configure `WorktreeCreate`/`WorktreeRemove` hooks in `~/.claude/settings.json`. Until then:

- One implementer at a time
- One reviewer at a time
- One issue end-to-end before starting the next

### Spotting parallelism (for when worktrees come online)

- Different modules: usually parallel
- Same module, different files: maybe (verify no Gradle / manifest conflicts)
- Same file: never parallel

## Verification checklist (orchestrator's pre-merge gate)

Even after the reviewer approves, the orchestrator runs:

- [ ] `git status` shows the expected file list — no surprises
- [ ] `git diff` reads sensibly — no hallucinated code, no scope creep, no commented-out junk
- [ ] Build succeeds: `./gradlew assembleDebug` (once #2 ships) or the relevant subcommand
- [ ] Tests pass for touched code
- [ ] No accidental commits of secrets or generated files (`build/`, `.gradle/`, `local.properties`)
- [ ] Issue's acceptance criteria are demonstrably met, not just claimed
- [ ] For UI changes: installed on the Android emulator and compared with the matching mockup at `docs/mockups/<screen>.html`
- [ ] For SSH / tmux / agent / usage changes: relevant Testcontainers integration test passes

If any check fails: do not commit. Re-engage the implementer with the specific failure, or do the fix directly if it is trivial.

## Quality assurance

Two emulation surfaces let the orchestrator verify changes without touching real devices or hosts:

- Android emulator for UI / visual validation
- Docker remote server (sshd + tmux + agent CLIs + helper tools) for SSH / tmux / agent-detection / usage tests

Both are first-class. Every PR that touches the relevant layer is validated before merge. The orchestrator runs this — it is not delegated. Sub-agents may write the test; the orchestrator runs it and judges the result.

Full setup: [docs/testing.md](docs/testing.md)

## Commit conventions

- Imperative mood, scoped prefix (`scaffold:`, `feat:`, `fix:`, `docs:`, `infra:`)
- First line under 70 chars
- Body explains what changed and why, links the issue with `Closes #N`
- One issue per commit when feasible
- Commit only after reviewer `APPROVED` and orchestrator's verification

## When to skip delegation

Some work is faster done directly than briefing an implementer:

- Trivial single-line edits
- Reading and summarising a file
- One-shot CLI commands
- Reviewing an agent's output (this is the orchestrator's job)
- Editing docs (the orchestrator wrote them; has context)
- Renaming files, fixing imports, dependency bumps without behavioural change

Rule of thumb: if briefing the agent would take longer than just doing it, just do it. Note the bypass in a one-line commit message.

## Anti-patterns

- Skipping the reviewer because "this issue is small." Run the reviewer on every issue — speed gains aren't worth the audit-trail loss.
- Implementer committing or pushing. Never. Orchestrator commits.
- Reviewer approving without running the build. Reject the review; ask reviewer to actually run things.
- Reviewer editing code. They suggest; implementer implements.
- Agents commenting on issues for each other to read directly without orchestrator mediation. Not allowed.
- Long agent chains. If an issue takes 5+ implementer rounds, the issue scope is wrong. Stop, re-scope, re-launch.
- Approving an issue whose acceptance criteria changed mid-flight. Update the issue body first, restart.

## Process evolution

This file is the playbook, not scripture. If a pattern emerges (a brief shape that consistently works, a failure mode that recurs, a verification step that catches bugs early), update this file. The orchestrator owns the process as much as the code.
