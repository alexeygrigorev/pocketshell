# Release

This is how PocketShell ships a version.

`main` keeps moving; other people merge there. We don't freeze `main` and
don't tag whatever `origin/main` happens to be after a long stabilize fight,
and the root checkout never switches off `main` to do this work (locked,
see `process.md`).

We copy a SHA of `main` onto a release-candidate branch, checked out in its
own worktree, make that branch stable, tag that SHA, then merge the
candidate back to `main`. A dispatched release-owner agent (see
`.claude/agents/release-owner.md`) does all of this — it never switches the
root checkout's branch.

Copy, not move: anything already on `main` (CI cadence, flake quarantine,
product fixes) stays on `main`. The candidate starts as a copy of that
`main`; extra fixes for this version are committed on the candidate and
merged back later.

Version numbers come from the git tag (`scripts/derive-version.sh`); there
is no version-bump PR.

## Process (do these in order, from inside the worktree)

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

All remaining commands run from inside `.worktrees/release-v0.4.45/`. The
root checkout stays on `main`, untouched, for the whole release.

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
not merged into the candidate until after the tag (step 5).

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

### 4. Make the release (tag the candidate SHA)

The GitHub Release APK comes from the tag-triggered Build workflow. From
inside the worktree, tag the candidate SHA `vX.Y.Z`:

```bash
cd .worktrees/release-v0.4.45
git fetch origin
test "$(git rev-parse HEAD)" = "$(git rev-parse origin/release/v0.4.45)"

scripts/push-release-tag.sh --visual-audit-inspected v0.4.45 \
  build/release-emulator-validation/<run-id>/summary.md
```

The summary must contain:

```
Commit SHA: <the candidate SHA>
Automated status: PASS
```

Watch Build. Confirm `gh release view v0.4.45` is not a draft and has a
downloadable APK. Don't retag an older version or publish a
`workflow_dispatch` APK as the release.

### 5. Merge the candidate back to `main`, then remove the worktree

Stabilizing commits must not stay stranded on the branch. This step runs
from the root checkout (the merge target), not the worktree:

```bash
git fetch origin
git merge origin/release/v0.4.45    # ff-only when main has not moved
git push origin main
git worktree remove .worktrees/release-v0.4.45
git branch -D release/v0.4.45       # already merged; safe to drop locally
```

Later `main` work was never blocked.

## What each place is for

| Place | Role |
|---|---|
| Root checkout, `main` | Daily development. Other engineers keep merging. Not the freeze line. Never switches branch for a release. |
| `.worktrees/release-vX.Y.Z/` on `release/vX.Y.Z` | The freeze line — a copy of a `main` SHA plus only this version's remaining fixes. Where the release-owner agent does all its work. |
| Tag `vX.Y.Z` | The published APK. Must point at the candidate SHA that passed Tests + emulator validation. |

## Tag helper

`scripts/push-release-tag.sh` may run from the `release/vX.Y.Z` worktree or
from root `main`. `HEAD` must match the remote of that branch, and the
summary `Commit SHA` must equal `HEAD`. If you tagged from the candidate
worktree, step 5 still merges that same SHA (or a merge commit containing
it) back to `main`.
