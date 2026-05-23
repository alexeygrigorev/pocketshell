# Process

PocketShell uses a three-actor process: orchestrator + implementer + reviewer. The orchestrator prepares issues, dispatches agents, verifies outcomes, and merges. Implementers write code. Reviewers review code. Agents communicate through GitHub issue comments, with the orchestrator as messenger.

## Non-Negotiable Loop

Every issue moves through this state machine:

```text
IMPLEMENTER -> REVIEWER -> IMPLEMENTER -> REVIEWER -> ... -> APPROVED -> ORCHESTRATOR VERIFY/MERGE
```

Reviewer findings are implementation work, and implementation work belongs to an implementer agent. The orchestrator does not fix reviewer findings directly.

Allowed orchestrator work between review rounds:

- Read and summarise reviewer findings
- Decide whether the issue scope needs clarification
- Update the issue body or process docs
- Launch a fresh implementer with the review comment included verbatim
- Run integration checks after reviewer approval

Not allowed between review rounds:

- Editing production code to satisfy a reviewer finding
- Quietly fixing tests, imports, or build failures from reviewer output
- Declaring a reviewer finding handled without an implementer follow-up and reviewer re-check

If the orchestrator accidentally edits scoped code during a review round, that edit must be called out explicitly in the next implementer brief. The implementer owns either adopting it, replacing it, or reverting it. The issue still needs another reviewer pass.

## Roles

### Orchestrator

Owns:

- Reading user asks and refining them into well-shaped issues
- Ensuring each issue has scope, acceptance criteria, file paths, doc links, and non-goals
- Launching implementer agents with self-contained briefs
- Launching reviewer agents after implementers report done
- Relaying review feedback to implementers through fresh implementer runs
- Running the pre-merge QA gate
- Committing, pushing, and closing issues only after reviewer approval
- Keeping this process document current

Never:

- Fixes reviewer findings directly
- Writes implementation code for an issue already inside the implementer/reviewer loop
- Commits or closes an issue without a reviewer `APPROVED` comment after the last implementation change

### Implementer

Does:

- Reads the issue, linked docs, and relevant existing code
- Writes code and tests in the local working tree
- Runs build and tests before reporting done
- Posts a status comment on the issue with changed files, test results, judgment calls, and open questions
- If a reviewer requested changes, reads the review first and addresses every item
- Owns all code changes required by reviewer feedback, even if the fix looks small

Does not:

- Commit, push, or close the issue
- Modify files outside the issue scope
- Argue with the reviewer in comments

### Reviewer

Does:

- Reads the implementer's latest status comment and the working-tree diff
- Runs the relevant build and tests
- Checks each acceptance criterion explicitly
- Looks for bugs, missing tests, dead code, scope creep, security issues, style drift, version mismatches, and ignored docs
- Posts exactly one of:
  - `APPROVED`
  - `CHANGES REQUESTED`, with specific actionable bullets
- Re-reviews after each implementer follow-up

Does not:

- Edit code
- Commit, push, or close the issue
- Approve without running build and tests

## Communication

GitHub Issues are the contract. Every artifact lives there:

- Issue body: scope, acceptance criteria, doc links, non-goals
- Implementer comments: status reports
- Reviewer comments: `APPROVED` or `CHANGES REQUESTED`
- Orchestrator comments: relays, decisions, commit links

Agents do not talk to each other directly. The orchestrator is always the messenger so the audit trail stays complete.

## Workflow Per Issue

1. Orchestrator refines the issue. Acceptance criteria must be specific and verifiable.
2. Orchestrator launches an implementer agent with a self-contained brief.
3. Implementer edits code/tests, runs verification, and posts a status comment.
4. Orchestrator launches a reviewer with the issue number and implementer status.
5. Reviewer runs verification and posts `APPROVED` or `CHANGES REQUESTED`.
6. If `CHANGES REQUESTED`:
   - Orchestrator launches a fresh implementer with the review comment verbatim.
   - Implementer, not orchestrator, edits code/tests.
   - Orchestrator launches a reviewer again after the implementer reports done.
   - Repeat until approval.
7. If `APPROVED`:
   - Orchestrator runs the verification checklist one last time.
   - Orchestrator commits with `Closes #N`, pushes, and lets GitHub close the issue.

## Parallel Work

Parallelism is issue-scoped, not role-skipping:

- Each active issue keeps its own implementer/reviewer loop.
- Reviewers may run in parallel for different issues.
- A reviewer finding for issue A goes back to an implementer assigned to issue A, even if issue B is also active.
- Do not mix fixes for multiple reviewed issues into one unreviewed coordinator patch.
- Launch agents asynchronously. The orchestrator must not start agents in a blocking mode when there is useful coordinator work available, such as refining issues, reading surrounding code, preparing reviewer briefs, or checking unrelated backlog status.
- Waiting on an agent is only appropriate when the next required process step depends on that specific agent result and there is no other useful non-overlapping work to do.

Parallel work is safe when issues touch different modules or paths and neither depends on another's unmerged work.

Parallel work is not safe when issues edit the same files or one issue's output is another issue's input. Without isolated worktrees, the orchestrator must track file ownership carefully and sequence overlapping issues.

## Briefing Rules

Implementer briefs include:

- Issue number and URL
- Project context and relevant docs
- Scope and acceptance criteria verbatim
- Exact files or areas likely to change
- Non-goals
- Required deliverable: an issue comment with files changed and verification results
- Hard rule: do not commit, push, or close

Reviewer briefs include:

- Issue number and URL
- Implementer's latest status comment
- Instruction to run build and tests
- Instruction to verify every acceptance criterion
- Required deliverable: one review comment with `APPROVED` or `CHANGES REQUESTED`
- Hard rule: do not edit code, commit, push, or close

Implementer briefs after `CHANGES REQUESTED` include:

- Previous implementer status
- Reviewer comment verbatim
- Clear instruction to address every finding or justify why it is out of scope
- Any accidental coordinator edits since the review, with file paths
- Required deliverable: a new issue comment explaining how each reviewer finding was handled

## Issue Quality

Each issue must have:

- Specific title
- Scope
- Acceptance criteria with checkboxes
- Non-goals
- Relevant doc links
- Reference code or examples when useful

If implementer or reviewer confusion reveals that an issue is underspecified, fix the issue first, then relaunch.

## Verification Checklist

After reviewer approval, the orchestrator runs:

- [ ] `git status` shows only expected files
- [ ] `git diff` reads sensibly
- [ ] Build succeeds, usually `./gradlew assembleDebug`
- [ ] Tests pass for touched code
- [ ] No secrets or generated build outputs are staged
- [ ] Acceptance criteria are demonstrably met
- [ ] UI changes are checked against the relevant mockup when practical
- [ ] SSH, tmux, agent, and usage changes run the relevant Docker/Testcontainers checks when practical

If any verification check fails, do not commit. Send the failure back to an implementer unless it is outside the reviewed implementation scope, such as rerunning a flaky command or fixing process docs.

## Quality Assurance

Two emulation surfaces are first-class:

- Android emulator for UI and visual validation
- Docker remote server for SSH, tmux, agent-detection, and usage tests

The orchestrator runs final QA. Sub-agents may write tests, but approval and merge still depend on orchestrator verification.

Full setup: [docs/testing.md](docs/testing.md)

## Release Builds

APK release builds are created by pushing a version tag, not by relying on an
ad-hoc workflow-dispatch build for the final artifact.

The Build workflow is intentionally packaging-only so releases are fast. It
assembles the APK, uploads the artifact, and creates the GitHub Release. It does
not run the full test suite. Unit, Docker integration, and emulator smoke checks
belong to the separate Tests workflow and the orchestrator's pre-tag
verification gate.

Release build steps:

1. Pick the next semantic version after the latest GitHub Release/tag.
2. Update Android metadata before tagging:
   - `versionName` must equal the tag without the leading `v`.
   - `versionCode` must increase monotonically.
3. Run the normal verification gate before committing the version bump, and
   confirm the Tests workflow is green for the commit being tagged.
4. Commit and push the version bump.
5. Create and push the matching tag, for example `v0.2.1`.
6. Watch the tag-triggered Build workflow and verify the uploaded APK artifact.

Never tag a release when the APK metadata still reports the previous release
version. That creates a self-update loop where the installed app offers the same
release as an update.

## Commit Conventions

- Imperative mood, scoped prefix when useful
- First line under 70 characters
- Body explains what changed and why
- Link the issue with `Closes #N`
- Prefer one issue per commit
- Commit only after reviewer `APPROVED` and orchestrator verification

## Direct Orchestrator Work

The orchestrator may do direct work for:

- Reading and summarising files
- One-shot CLI commands
- Reviewing agent output
- Process and documentation updates
- Repository hygiene outside an active issue implementation

Before an implementer/reviewer loop starts, very small code changes may be done directly. After the loop starts, code changes stay with implementers.

## Anti-Patterns

- Skipping review because an issue is small
- Implementer committing or pushing
- Reviewer approving without running build/tests
- Reviewer editing code
- Agents talking directly to each other outside orchestrator mediation
- Long agent chains on an underspecified issue
- Approving after acceptance criteria changed mid-flight without updating the issue and re-reviewing

## Process Evolution

This file is the playbook. When a pattern emerges, update it. The orchestrator owns the process as much as the code.
