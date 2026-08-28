# Agent Worktrees

Implementer, reviewer, and release-owner agents never edit the orchestrator's
main checkout. Every agent runs inside its own isolated git worktree branched
from `main`, regardless of which tool runs it (Claude Code, Codex, opencode,
a human pair). This keeps the root checkout clean, makes parallel work safe
at the filesystem level, and means an abandoned agent run leaves no residue.

## Layout

- Main checkout: `~/git/pocketshell`, always on `main`. It never switches
  branches — not for a quick doc PR, not for a release. Push small
  docs/process fixes straight to `main`; do everything else in a worktree.
- Worktree root: `.worktrees/` (gitignored). Per-issue path:
  `.worktrees/issue-<N>/` on branch `issue-<N>`. Release worktrees use
  `.worktrees/release-vX.Y.Z/` on branch `release/vX.Y.Z` (see
  [release.md](release.md)).
- Pickup patches for in-flight draft work an agent should apply first:
  `.pickup/issue-<N>-starter.patch` (gitignored, kept in-repo so it's
  visible from any worktree).

Use the same `<N>` everywhere so worktree path, branch, patch file, and
GitHub issue line up. Reuse an existing worktree across review rounds on the
same issue rather than creating a parallel one.

## Creating one

```bash
mkdir -p .worktrees
git fetch origin main
git worktree add .worktrees/issue-<N> -b issue-<N> origin/main
```

Claude Code's Agent tool does this automatically with
`isolation: "worktree"` and returns the resulting path.

Before dispatch, make sure `main` is clean — stash or save unrelated WIP to
`.pickup/` first. Never let an agent inherit unrelated dirty state.

## Merging an approved worktree back to `main`

Only the orchestrator merges, and only after reviewer `APPROVED` + the
verification checklist ([process.md](../process.md)) passes, via a protected
PR. The mechanical parts that are easy to get wrong:

1. **Capture the diff correctly.** `git diff` alone shows only unstaged
   changes — staged edits/deletions vanish. Use `git diff HEAD`. That still
   **silently omits untracked new files** (commonly the new regression
   test):

   ```bash
   WT=.worktrees/issue-<N>
   git -C "$WT" diff --no-color HEAD > /tmp/issue-<N>.patch
   git -C "$WT" ls-files --others --exclude-standard   # new files git diff missed
   ```

   Copy every untracked file into `main` at the same relative path, or you
   merge a fix without its test. Verify the applied file set (modified, new,
   deleted) matches the implementer's reported list before running the gate.

   If the implementer committed inside the worktree, diff against `main`
   instead — a commit-based diff includes new files, so this caveat doesn't
   apply: `git -C "$WT" diff --no-color main..HEAD`.

2. **Apply plainly.** Use plain `git apply`, never `git apply --3way` — it
   stages files and can pull stale base content, producing phantom compile
   breaks that don't exist on `origin/main`.

3. **Diff against the merge-base**, not a `main` that has since moved, or
   the patch can silently include the inverse of an intervening commit.

4. **Commit on the issue branch**, push, open the PR (`Closes #N` only when
   fully complete), wait for the cheap required checks, merge, then:

   ```bash
   gh pr merge <PR> --squash --delete-branch
   git fetch origin main && git switch main && git merge --ff-only origin/main
   git worktree remove .worktrees/issue-<N>
   git branch -D issue-<N>
   ```

5. **Never `git worktree remove --force`** a worktree under an active agent
   or with uncommitted work — it discards the diff with no recovery. Before
   removing any worktree holding uncommitted work you intend to keep, push
   its branch or save `.pickup/issue-<N>-*.patch` (tracked diff + a copy of
   untracked files — `git diff` omits untracked files here too).

6. If two approved worktrees touch the same file, do not hand-merge in the
   orchestrator. Merge the first, then send the second back to a fresh
   implementer round to rebase onto the updated `main`.

## Batching ≥2 approved PRs through one integration worktree

For 2–3 already-`APPROVED`, independent PRs landing close together —
especially touching connection/terminal/session/composer — run one combined
pre-merge check before merging each individually:

1. `git worktree add .worktrees/batch-<date> origin/main`, then
   `git merge --no-ff <branch>` each approved PR into it in review order. A
   real conflict sends that PR back to rebase — never a manual 3-way
   resolution here.
2. Run the union of what each PR's own scoped test plan would select
   (`scripts/select-test-areas.sh --base <pre-batch-sha> --print-plan-only`),
   plus the load-bearing journey smoke set when the union crosses
   connection/terminal/session/composer.
3. On green, merge each PR individually through its normal flow — the batch
   worktree is validation-only and is never pushed itself.
4. On red, bisect by dropping PRs one at a time until green.

## Merged-but-open sweep

A merge only auto-closes an issue when the PR body carried `Closes #N`; a
batch PR whose commits merely reference `(#N)` in the subject leaves the
issue open. Before dispatching new work, sweep for issues already fixed on
`main`:

```bash
for n in $(gh issue list --state open --limit 200 --json number --jq '.[].number'); do
  c=$(git log --oneline origin/main --grep="(#$n)" --fixed-strings | head -1)
  [ -n "$c" ] && echo "#$n <- $c"
done
```

A hit is a candidate, not a verdict — confirm the commit is genuinely this
issue's fix (not a sibling commit that merely cites it), that the issue's
last comment is an `APPROVED` with no post-merge recurrence, and that an
umbrella/feature issue whose merged commit was only one slice stays open.
