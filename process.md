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
- For mobile, UI, terminal, SSH, tmux, agent, setup, and release-gate issues,
  runs the relevant emulator check too; code inspection alone is not enough for
  approval
- For user-facing journeys, reproduces the actual workflow and inspects the
  resulting screenshots/logs/timings. A passing assertion is not enough if the
  visible app state would still be unusable to the user.
- For terminal, SSH, tmux, and agent journeys, bases approval on authoritative
  terminal viewport artifacts, visible terminal text, timing files, and
  Docker/emulator logs from the same run. Full-device screenshots are advisory
  for terminal content unless the capture path is proven reliable for that run.
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
- Implementer comments: changed files, verification commands/results, artifact
  paths, judgment calls, and open questions
- Reviewer comments: `APPROVED` or `CHANGES REQUESTED`, with the command,
  artifact, and emulator evidence used for approval when the issue requires it
- Orchestrator comments: relays, decisions, commit links

Agents do not talk to each other directly. The orchestrator is always the messenger so the audit trail stays complete.

## Workflow Per Issue

1. Orchestrator refines the issue. Acceptance criteria must be specific and verifiable.
2. Orchestrator launches an implementer agent with a self-contained brief.
3. Implementer edits code/tests, runs verification, and posts a status comment.
4. Orchestrator launches a reviewer with the issue number, implementer status,
   and any artifact paths or logs the implementer produced.
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
- Instruction to run emulator validation for any user-facing Android flow,
  terminal/input behavior, SSH/tmux/agent workflow, screenshot/UI audit, or
  release-gate issue
- Instruction to inspect authoritative artifacts and reject stale, blank,
  missing, contradictory, or non-reproducible terminal viewport screenshots,
  visible terminal text, logs, or timing evidence
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
- [ ] UI changes are checked on the Android emulator against the relevant
  mockup, with screenshots when the issue is visual
- [ ] Terminal/input, SSH, tmux, agent, setup, and usage changes run the
  relevant emulator + Docker connected checks
- [ ] Interactive user journeys include artifact evidence: screenshots, logcat
  or app logs, and timing for the relevant transition when responsiveness is
  part of the issue
- [ ] Terminal reviews inspect authoritative terminal viewport screenshots,
  visible terminal transcript text, timing files, Docker logs, emulator logcat,
  and instrumentation output from the same run
- [ ] Terminal full-device screenshots are treated as advisory unless the
  artifact summary proves they agree with the authoritative terminal viewport
  capture for that run

If any verification check fails, do not commit. Send the failure back to an implementer unless it is outside the reviewed implementation scope, such as rerunning a flaky command or fixing process docs.

## Commit Cadence

After an issue is reviewer-approved and the orchestrator verification checklist
passes, commit and push that finished task before moving on to unrelated work.
Prefer one small commit per approved issue or tightly coupled issue group so
rollback remains practical.

Do not batch approved work together with unapproved in-flight work. If files
overlap between approved and unapproved issues, either wait for the overlapping
issue to finish review or split the staged hunks carefully so the commit
contains only reviewed changes.

## Quality Assurance

Two emulation surfaces are first-class:

- Android emulator for UI and visual validation
- Docker remote server for SSH, tmux, agent-detection, and usage tests

The orchestrator runs final QA. Sub-agents may write tests, but approval and merge still depend on orchestrator verification.

Reviewer approval for a user-facing Android flow must include emulator evidence:
the command run, whether Docker was involved, and the observed result. If the
emulator cannot be run, the reviewer must return `CHANGES REQUESTED` or clearly
mark the issue as blocked; it must not be approved as done.

Reviewer approval must be based on a reproduced user journey. Reject the change
when artifacts are stale, missing, from a different run, contradicted by the
visible screenshot, or do not prove the workflow is usable. For terminal/tmux
work, the reviewer must see input reach the terminal and output appear in the
app UI. For performance-sensitive work, the reviewer must include timing
evidence.

## Terminal Artifact Review

Terminal, SSH, tmux, and agent reviews are artifact-driven. The reviewer must
inspect the artifact bundle, not just the test result line, before approving.
The authoritative terminal evidence is:

- Direct terminal viewport screenshots named `*-viewport.png`
- Visible terminal text artifacts such as `*-visible-terminal.txt`
- Capture summaries such as `*-summary.txt` and `artifact-summary.txt`
- Timing files such as `timings.txt` or scenario-specific timing logs
- Instrumentation output, emulator logcat, Docker compose logs, and Docker SSH
  readiness logs from the same run directory

Full-device screenshots, final emulator screen captures, and window-level
captures are diagnostic only for terminal content unless the run's summary
shows that they agree with the direct terminal viewport render and visible
terminal text. A blank or contradictory full-device screenshot does not
invalidate a passing authoritative viewport capture by itself, but it must be
called out. A blank or contradictory authoritative viewport capture is a review
failure.

Reviewers must reject or request changes when any of these are true:

- The artifact bundle is missing authoritative `*-viewport.png` terminal
  screenshots for the exercised workflow.
- The authoritative viewport screenshots are blank, header-only, stale, or do
  not show the expected shell/tmux/agent output.
- The visible terminal text files are missing, empty, stale, or contradict the
  viewport screenshots.
- Timing files are missing for workflows that claim responsiveness,
  stabilization, or hold/debug timing behavior.
- Docker logs, Docker SSH readiness logs, emulator logcat, or instrumentation
  output are missing, from another run, or contradict the claimed result.
- Artifact names, timestamps, run IDs, command logs, or summaries show evidence
  from different runs mixed together.
- Full-device screenshots are used as the only proof of terminal content.
- A passing assertion is contradicted by visible terminal text, authoritative
  viewport screenshots, or logs.

Exact local terminal workbench commands:

```bash
scripts/terminal-workbench.sh
```

Use that deterministic workbench for normal reviewer checks. It starts or
verifies the local emulator, starts the deterministic Docker `agents` service on
host port `2222`, runs the terminal workbench instrumentation, pulls artifacts
under `build/terminal-workbench/<run-id>/artifacts/terminal-lab/`, and writes
`build/terminal-workbench/<run-id>/artifact-summary.txt`.

For a stable rerun ID that is easy to cite in an issue comment:

```bash
RUN_ID=issue-<number>-review scripts/terminal-workbench.sh
```

For real-agent CLI workbench evidence, run:

```bash
REAL_AGENTS=1 scripts/terminal-workbench.sh
```

This uses `tests/docker/real-agent/compose.yml`, the `real-agents` service, and
SSH port `2240`. Treat it as a reviewer workbench for real CLI rendering and
not as the default deterministic smoke path.

Full setup: [docs/testing.md](docs/testing.md)

Evaluator runbook for local Docker profiles, port conflicts, Android SDK paths,
emulator startup, and connected test commands:
[docs/docker-emulator-runbook.md](docs/docker-emulator-runbook.md).

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
3. Run the normal verification gate before committing the version bump.
4. Commit the version bump on `main` and push `main` first. Confirm the
   checkout is clean and `HEAD` equals `origin/main` before creating or pushing
   any tag.
5. From that stable pushed `main`, run the emulator-only release validation:
   - `scripts/pre-release-confidence-gate.sh`
   - `scripts/phone-dogfood.sh terminal-lab`
   - `scripts/phone-dogfood.sh tmux-existing-session`
   - `scripts/phone-dogfood.sh setup-detection`
   - visual-audit screenshot capture, then inspect the screenshots
6. Prefer the wrapper that runs that sequence and writes the required summary:
   `scripts/release-emulator-validation.sh`.
7. Push the matching tag with the guarded tag helper, for example
   `scripts/push-release-tag.sh --visual-audit-inspected v0.2.1 build/release-emulator-validation/<run-id>/summary.md`.
8. Watch the tag-triggered Build workflow and verify the uploaded APK artifact.

Manual Release Emulator Validation can also be run from GitHub Actions when a
local emulator is unavailable:

1. Open Actions -> Release Emulator Validation -> Run workflow.
2. Choose the release branch or `main`; optionally provide a `run_id`.
3. Wait for the workflow to finish, then read the job summary.
4. Download the `release-emulator-validation-<run-id>` artifact for logs,
   screenshots, and the release summary.
5. Confirm the tested debug APK is present inside the downloaded artifact at
   `release-emulator-validation/<run-id>/app-debug.apk`. The equivalent local
   wrapper path is
   `build/release-emulator-validation/<run-id>/app-debug.apk`.
6. Inspect the visual-audit screenshots before treating the run as release
   evidence.
7. Attach or link the summary and artifact directories in the release issue and
   tag notes.

The manual workflow is validation evidence only. It does not create or push the
release tag, does not replace the guarded tag helper, and does not weaken the
stable-main rule: the tag still must point at a reviewed commit already pushed
to `main`.

The release issue and tag notes must attach or link all emulator-only evidence
directories:

- `build/pre-release-confidence-gate/<run-id>-pre-release/`
- `build/phone-dogfood/<run-id>-terminal-lab/`
- `build/phone-dogfood/<run-id>-tmux-existing-session/`
- `build/phone-dogfood/<run-id>-setup-detection/`
- `build/dogfood-visual-pass/<run-id>-visual-audit/`

Release tags must come from stable `main`. Do not create release commits from a
detached HEAD, a tag checkout, or a temporary worktree that is not first pushed
back to `main`. Do not rebase local work from a tag or treat a tag as the
source branch for release work. Tags are labels on already-reviewed `main`
commits; they are not development branches. Before pushing a release tag,
verify `git status` is clean and `git rev-parse HEAD` matches
`git rev-parse origin/main`.

Physical phone testing is final user acceptance only. Do not use a phone pass
to discover or waive basic release blockers that the emulator/Docker validation
above is meant to catch before a tag exists.

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
