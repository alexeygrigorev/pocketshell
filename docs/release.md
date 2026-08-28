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
git show validated-rc --quiet   # SHA, run URL, and timestamp are in the tag message
```

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
does all of this — it never switches the root checkout's branch except for
the final merge and tag steps, which run from the root checkout by design
(see [Tag the release](#tag-the-release)).

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
- Visual-audit screenshots were inspected.

Don't fake a PASS. Don't treat a cancelled or in-progress Tests run as green.

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
