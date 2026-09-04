# Release

This is how PocketShell ships a version.

`main` keeps moving; other people merge there. We don't freeze `main` and
don't tag whatever `origin/main` happens to be after a long stabilize fight,
and the root checkout never switches off `main` to do this work (locked,
see `process.md`).

Version numbers come from the git tag (`scripts/derive-version.sh`); there
is no version-bump PR. `scripts/push-release-tag.sh` only runs from a
checkout on `main` whose `HEAD` already equals `origin/main` — it does not
accept a release-branch worktree, so tagging always happens after that
branch's SHA has reached `main`, never before.

## The nightly fault gate blocks the tag, full stop (D37)

`scripts/release-emulator-validation.sh` runs
`scripts/check-nightly-fault-run.sh` as its first required step. That guard
reads the `Fault-injection safety verdict` job of the latest
`Nightly Extensive Tests` run — the toxiproxy network-fault proofs plus the
bootstrap setup-scenario matrix, with the flaky journey suite and the #822
expected-fail lane excluded. It BLOCKS when that verdict is red, cancelled,
stale (the run tested a line that does not contain the release HEAD), or
missing.

**Nothing you can put in the environment changes that verdict.** No
environment variable, no `workflow_dispatch` input, no "deliberately waived
release" branch — they were removed by decision D37 (`docs/decisions.md`),
because waiving the gate had become routine: v0.4.31–v0.4.38 and v0.4.45 all
shipped with the fault verdict un-enforced, and #1671 traced the #1610
reconnect storm reaching the maintainer to exactly that. A gate the
release-owner can opt out of is not a gate.

Concretely, `scripts/check-nightly-fault-run.sh` reads *no* environment
variable. Its offline test knobs (`--fixture`, `--workflow`, `--job-needle`)
are command-line flags precisely so that an exported variable cannot reach
them, and `gh` is pinned to this checkout's origin repository with `--repo`
(with `GH_REPO`/`GH_HOST` scrubbed) so the query cannot be redirected at a
greener repository. A `--fixture` run prints `[FIXTURE DRY RUN]` on every
line and is never a release verdict; `scripts/release-emulator-validation.sh`
passes only `--release-head`, and the per-push guard fails if it ever passes
anything else.

Be precise about what that does and does not claim: it means no *environment*
can fabricate a verdict, and no flag exists to skip one. It does not mean the
script is tamper-proof against someone editing the working tree or
hand-writing a release summary — nothing enforceable would be. If you find
yourself wanting to, that is the signal to read the two options below instead.

When the guard blocks, there are exactly two ways forward:

1. **Fix the failing test or journey**, re-run
   `Nightly Extensive Tests` (`workflow_dispatch`, `force_run=true`) on the
   release commit, and re-run the release gate once the fault-verdict job is
   green.
2. **Quarantine the offending test/journey class** through the existing
   D36(4) flake mechanism — auto-filed issue, moved into the non-blocking
   lane, 2-week expiry — so the verdict covers a genuinely smaller but still
   real suite. Quarantine is a recorded, expiring narrowing of *what* the
   gate checks; it is never a skip of the whole verdict.

"Nightly Extensive is broken by infra" is not a third option: a missing
verdict means there is no safety signal to release on, so re-run the nightly
until there is one. If a release is blocked for longer than that is
tolerable, the fix belongs in #1671 (make the fault gate reliably green),
not in a new escape hatch.

`scripts/check-release-gate-bypass-absent.sh` (per push, in the
`guards-static` job) fails if either deleted bypass name reappears under
`scripts/`, `.github/workflows/` or `.github/actions/` (C1), if the fault
guard starts honouring them again (C2/C3), or if a fabricated verdict can be
injected through the environment or a test-only flag in the release path (C4).

## Fast path: tag the nightly validated-RC (preferred, check this first)

Issue #2356 (Phase 4 of epic #2350) added a nightly marker: a green
*scheduled* (not per-push) full-suite `Tests` run on `main` triggers
`release-emulator-validation.yml`, and on its own green run that workflow
force-moves an annotated tag, `validated-rc`, to that SHA — a candidate
that already has both the full suite and the release emulator gate green,
with no local re-validation needed.

Check it before cutting a new candidate branch:

```bash
git fetch origin --tags
git show validated-rc --quiet   # SHA, both run URLs, and timestamp are in the tag message
```

The marker labels the two provenance links separately: `Release validation run`
is the workflow containing the emulator summary/artifacts, while
`Triggering Tests run` is the upstream scheduled full-suite signal. Do not use
the Tests link to look for release-validation artifacts.

If a `validated-rc` tag exists, is recent (rule of thumb: less than 24h old
— check the timestamp in the tag message), and its SHA is at or ahead of
what you need released: fast-forward the root checkout's `main` to that SHA
if it isn't already there, download the matching
`release-emulator-validation` run's summary artifact from Actions (or
re-run `scripts/release-emulator-validation.sh --ref validated-rc` locally
if you want your own copy), then skip straight to
[Tag the release](#tag-the-release) below. There is no candidate branch to
create, stabilize, or merge back — the validated SHA already is `main`.

If no `validated-rc` tag exists yet, it's stale (main has since moved and
no fresh nightly run has landed), or `main`'s current journey lane is red
(check recent `Tests` workflow runs before trusting an old marker), fall
back to the full procedure below.

## Fallback: cut and stabilize a candidate branch

Use this when the fast path above isn't available. We copy a SHA of `main`
onto a release-candidate branch, checked out in its own worktree, make that
branch stable, merge it back to `main`, then tag the merged SHA from `main`.
A dispatched release-owner agent (see `.claude/agents/release-owner.md`)
does all of this without ever switching the root checkout off `main`; the final
fast-forward and tag commands run there by design (see
[Tag the release](#tag-the-release)).

Copy, not move: anything already on `main` (CI cadence, flake quarantine,
product fixes) stays on `main`. The candidate starts as a copy of that
`main`; extra fixes for this version are committed on the candidate and
merged back later.

### 1. Take a `main` that is more or less stable

```bash
git fetch origin main
git rev-parse origin/main
```

Record that SHA — it's the cut point. You can cut even if Tests on `main`
is messy; the remaining red gets fixed on the candidate, not by racing more
merges onto `main`.

### 2. Create the candidate branch in its own worktree

Name it `release/vX.Y.Z` (example: `release/v0.4.45`). Never `git checkout`
this branch in the repo root — create a worktree instead, the same
convention as an implementer's `.worktrees/issue-<N>/` (see `process.md` §
Agent Worktrees):

```bash
mkdir -p .worktrees
git worktree add .worktrees/release-v0.4.45 -b release/v0.4.45 origin/main
git -C .worktrees/release-v0.4.45 push -u origin release/v0.4.45
```

All remaining stabilization commands run from inside
`.worktrees/release-v0.4.45/`. The root checkout stays on `main`, untouched,
until the merge-back step.

If `release/v0.4.45` already exists (as a remote branch or a prior
worktree) and `main` has since gained commits you want in this cut, copy
them onto the candidate with a fast-forward from inside the worktree — don't
revert those commits on `main` to "move" them:

```bash
cd .worktrees/release-v0.4.45
git merge --ff-only origin/main    # copy; main is unchanged
git push origin release/v0.4.45
```

### 3. Stabilize the candidate until it is actually stable

All remaining product fixes for this version are committed inside the
worktree, on `release/vX.Y.Z` only. Unrelated work stays on `main` and is
not merged into the candidate until after the merge-back (step 4).

Green means, for this candidate SHA:

- Tests workflow required jobs completed `success` (not cancelled). Push to
  `main` does not run this branch. Start the full suite with:

  ```bash
  gh workflow run tests.yml --ref release/v0.4.45
  ```

- `scripts/release-emulator-validation.sh` (run from the worktree) wrote a
  summary whose `Commit SHA` is exactly this SHA and `Automated status: PASS`.
  Its first required step is the nightly fault gate — see
  [The nightly fault gate blocks the tag, full stop](#the-nightly-fault-gate-blocks-the-tag-full-stop-d37).
  If you run `scripts/check-nightly-fault-run.sh` by hand, run it with no
  arguments (or `--release-head <sha>`): a run carrying `[FIXTURE DRY RUN]`
  came from `--fixture` and is a test dry run, not a release verdict.
- Visual-audit screenshots were inspected. Since issue #2481 those are app2's
  journey screenshots, pulled by the pre-release confidence gate into
  `build/pre-release-confidence-gate/<run-id>-pre-release/journey-screenshots/`
  and named in the release summary. To regenerate them on their own — with the
  hard "every journey rendered a frame" assertion — run
  `scripts/capture-walkthrough-screenshots.sh` (see `docs/testing.md`).

The chain itself got shorter with the rewrite. `release-emulator-validation.sh`
is now nightly-fault guard -> pre-release confidence gate -> publish; the four
downstream walkthrough stages (terminal-lab, tmux-existing-session, the
setup-detection matrix, visual-audit) and the optional `TERMINAL_RELEASE_GATE=1`
/ `LONG_RUNNING_TEST=1` lanes were deleted with the `app` module androidTest
classes they drove. The journeys did not go away: the confidence gate installs
the one validated APK pair and runs app2's WHOLE instrumented set against those
exact bytes, unfiltered in a single instrumentation process (issue #2474).

Don't fake a PASS. Don't treat a cancelled or in-progress Tests run as green.
Don't look for a way around the nightly fault gate — there isn't one.

### 4. Merge the candidate back to `main`

`scripts/push-release-tag.sh` only tags from `main`, so the candidate SHA
has to become `main` before tagging, not after. This step runs from the
root checkout (never the worktree):

```bash
git fetch origin
git merge --ff-only origin/release/v0.4.45   # main has not moved since step 1
git push origin main
```

If this isn't a fast-forward (something else merged to `main` since step 1),
stop: merging non-fast-forward here changes the SHA, which invalidates the
stabilization evidence from step 3. Either re-run `release-emulator-validation.sh`
against the new merge commit before tagging, or rebase the candidate and
re-stabilize.

### 5. Tag the release

The GitHub Release APK comes from the tag-triggered Build workflow. From the
root checkout, now that `HEAD` equals `origin/main` at the merged candidate
SHA:

```bash
git fetch origin
test "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)"

scripts/push-release-tag.sh --visual-audit-inspected v0.4.45 \
  build/release-emulator-validation/<run-id>/summary.md
```

The summary must contain:

```
Commit SHA: <the now-merged candidate SHA, matching HEAD>
Automated status: PASS
```

Watch Build. Confirm `gh release view v0.4.45` is not a draft and has a
downloadable APK. Don't retag an older version or publish a
`workflow_dispatch` APK as the release.

### 6. Remove the worktree

```bash
git worktree remove .worktrees/release-v0.4.45
git branch -D release/v0.4.45       # already merged; safe to drop locally
```

Later `main` work was never blocked.

## What each place is for

| Place | Role |
|---|---|
| Root checkout, `main` | Daily development. Other engineers keep merging. Not the freeze line. Only switches for the final merge-and-tag steps of a release, never mid-stabilization. |
| `.worktrees/release-vX.Y.Z/` on `release/vX.Y.Z` | The freeze line — a copy of a `main` SHA plus only this version's remaining fixes. Where the release-owner agent does its stabilization work. |
| Tag `vX.Y.Z` | The published APK. Must point at an `origin/main` SHA that passed Tests + emulator validation (via either the fast path or the merged-back candidate). |
| Tag `validated-rc` | A moving marker, not a release — the newest `main` SHA with a green nightly full-suite + emulator gate. Force-updated nightly; check its timestamp before trusting it. |

## Tag helper

`scripts/push-release-tag.sh` only runs from the root checkout on `main`,
with `HEAD` equal to `origin/main` — it refuses any other branch, including
a release-candidate worktree. The summary's `Commit SHA` must equal `HEAD`.
This means tagging always happens after step 4 (merge to `main`) in the
fallback procedure, or after fast-forwarding to a `validated-rc` SHA in the
fast path — never before.
