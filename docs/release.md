# Release

How PocketShell ships a version. Other work keeps landing on `main`. A
release is not cut from a moving `main`; it is cut from a **release
candidate branch** that we freeze and stabilize.

Version numbers come from the git tag (`scripts/derive-version.sh`, issue
#2356). There is no separate version-bump commit.

## The loop

1. **Pick a `main` that is more or less stable.** Fetch `origin/main`.
   Record the SHA. It does not have to be the last green Tests run, but
   it should be a commit you are willing to stand behind if the remaining
   red is CI noise you will fix on the candidate.
2. **Branch from that SHA.** Name it `release/vX.Y.Z` (example:
   `release/v0.4.45`). Push it. This is the release candidate.
3. **Stabilize only on that branch.** Product fixes that must ship in
   this version land here. New unrelated work stays on `main` and is not
   merged into the candidate. Run Tests against the candidate SHA until
   required jobs are green. Run
   `scripts/release-emulator-validation.sh` so the summary has
   `Commit SHA: <candidate>` and `Automated status: PASS`. Inspect the
   visual-audit screenshots.
4. **Make the release.** Tag the **candidate SHA** `vX.Y.Z` with
   `scripts/push-release-tag.sh --visual-audit-inspected vX.Y.Z <summary.md>`.
   The Build workflow is tag-triggered; that SHA is the APK. Watch the
   tag-triggered Build and confirm the GitHub Release is not a draft and
   has a downloadable APK.
5. **Merge the candidate back to `main`.** Fast-forward when `main` has
   not moved. If `main` moved, merge the candidate so every stabilizing
   commit is on `main`. Do not leave release fixes stranded on the
   branch. After merge, `release/vX.Y.Z` can be deleted.

## Why not tag a live `main`

`main`'s Tests workflow cancels in-flight runs when a later push lands.
If other engineers keep merging while you wait for a full suite, you
never get a green SHA to tag, and any emulator summary you already have
is for the wrong commit. The candidate branch is the freeze; `main` is
not.

## Tag helper constraint

`scripts/push-release-tag.sh` currently requires:

- the current branch is `main`
- `HEAD == origin/main`
- the summary `Commit SHA` equals that `origin/main`

Until that helper is relaxed, do step 4 as: merge or fast-forward the
**exact candidate SHA** onto `main` (prefer `git merge --ff-only` so the
SHA does not change), then run the tag helper from that `main`. If the
merge creates a new SHA, re-run emulator validation on that SHA before
tagging — a passing run on the old candidate is not evidence for a
different commit (`docs/testing.md`).

The summary must still contain:

```
Commit SHA: <sha being tagged>
Automated status: PASS
```

Do not fabricate a PASS. Do not retag an older version. Do not publish a
`workflow_dispatch` APK as the GitHub Release.

## Commands

```bash
git fetch origin main
SHA=$(git rev-parse origin/main)
git checkout -B release/v0.4.45 "$SHA"
git push -u origin release/v0.4.45

# stabilize: commits only on this branch, Tests + emulator validation
# for this SHA

# when the candidate SHA is origin/main (ff-only merge) and the summary
# matches it:
scripts/push-release-tag.sh --visual-audit-inspected v0.4.45 \
  build/release-emulator-validation/<run-id>/summary.md

git checkout main
git fetch origin main
git merge --ff-only origin/release/v0.4.45   # if still fast-forwardable
git push origin main
```

Emulator validation details, walkthroughs, and the optional long-running
terminal hold: [process.md](../process.md) (Release Builds),
[testing.md](testing.md), [release-terminal-gate.md](release-terminal-gate.md).
