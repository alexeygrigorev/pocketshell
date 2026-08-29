# Lessons Learned

Durable, recurring operational lessons that aren't tied to any one epic or
issue number — the kind of thing worth knowing before you hit it yourself.
CI/gate-trustworthiness lessons live in [ci-pitfalls.md](ci-pitfalls.md)
instead; this doc is everything else.

## The G6 wrong-cost trap — a test that would still pass with the bug present

A recurring reviewer failure shape: a green test stands in for a property it
doesn't actually constrain. Concrete disguises seen more than once:

- The guard presets the variable it exists to exercise — a test set an env
  var that makes the code under test skip the exact path it was written to
  guard, so it stayed green while the guarded behavior moved elsewhere.
- The oracle launders the real symptom into an excused category — a
  classifier would have called a genuine same-app resource leak "foreign"
  and excused it; fixed by adding a second classification axis so an
  app-owned instance is always fatal.
- The proof injects a step production never performs — a journey manually
  triggered a codepath production only reaches via a specific state
  transition, masking that the real state was "nothing happens at all."
- The fix silently no-ops while reading correctly — a guard read its value
  via `$(...)`, which forks a subshell, so it compared a constant against
  itself.

The check that catches all four: name the specific mutation that must
redden this assertion, then apply it. If you can't state one, the assertion
is decorative. Confirm selectivity too — the mutant should redden the new
check and only the new check.

## Contended dev-box environment facts

- Never run `tmux kill-server`. `tmuxctl`/`t` operate on the default socket
  by design; `TMUX_TMPDIR`/`-L` don't isolate them (bootstrap goes through
  `systemd-run --user`, which drops env). A bare `kill-server` has wiped
  every live session on the box more than once. Clean up one test session
  by name instead: `tmux kill-session -t <name>`. Never put `kill-server`
  in a script's cleanup/trap.
- Port 2222 is reserved for the Docker `agents` fixture. A sibling
  project's container can squat it — stopping that container is
  pre-authorized and reversible (`docker start` brings it back).
- `uv exclude-newer = "7 days"` is set globally on this box
  (`~/.config/uv/uv.toml`), so a local `uv lock`/`sync` can't see
  last-week's PyPI releases — override per-operation with
  `--exclude-newer <date>`. CI runners are unaffected.
- Disk fills up fast. Recurring safe-to-clean hot spots:
  `.claude/worktrees/agent-*` (unlocked ones only — git marks in-flight
  agent worktrees `locked`), `docker builder prune -f`,
  `/tmp/pocketshell-*` scratch worktrees, `build/pre-release-confidence-gate/`
  and `build/phone-walkthrough/` (transient gate scratch). Never touch
  `.gradle/caches`, `.android/avd`, `pocketshell-test:*` images, or
  `.worktrees/issue-*` holding unmerged work. A full disk mid-session
  shows up as Gradle "Could not receive a message from the daemon" — check
  `df -h /` first.
- AVD contention is real: parallel APK installs from sibling worktrees
  SIGKILL each other. Always go through
  `scripts/connected-test.sh --suffix i<issue> <gradle args>` — it holds the
  shared AVD lock and installs under a per-worktree `applicationIdSuffix` so
  APKs coexist. SIGKILLs mask logical failures (truncation before
  assertions run looks like a pass), so a contiguous N/N on an isolated AVD
  is the real bar. For parallel journey lanes, add `--pool` (a distinct
  emulator plus isolated `agents`-fixture port per lane).
- A journey/E2E test using `createEmptyComposeRule()` +
  `ActivityScenario.launch()` can wedge for hours under software-GL, because
  a Compose-interop child view (e.g. the terminal view) never gets placed
  into the window. The fix is `createAndroidComposeRule<MainActivity>()`
  with seed-before-launch, not a per-test timeout tweak.
- Research/read-only agents see a stale local checkout: the root checkout
  sits on the maintainer's dirty WIP, not synced to `origin/main`. Brief a
  read-only research agent to `git fetch origin main` and read `origin/main`
  explicitly, or hand it a throwaway worktree — otherwise it will falsely
  report shipped features as "missing." Implementers/reviewers in their own
  worktrees are unaffected.
- `check_destructive.py` (a user-level PreToolUse Bash hook) can brick every
  Bash call, including sub-agents', if `~/git/.claude` or the script goes
  missing. Read/Edit/Write aren't hook-gated, so they can recreate a
  fail-open stub (exit 0 on any internal error) at that path if it happens;
  then tell the maintainer to restore the original.
- Don't force-push. A blocked force-push almost always means a local
  branch diverged from a patch-equivalent remote commit (e.g. rebased/
  squashed remotely) — `git rebase <upstream-branch>` drops the duplicate
  and turns the push back into a normal fast-forward. Find a way around it
  rather than forcing.

## Process discipline

- File CI failures as issues, don't retry silently. Recurring classes (e.g.
  AVD contention) get one tracking issue, not one per occurrence.
- Reconcile completed agents against actual state, not memory — dispatched
  is not the same as running, and stale relay notifications can bury real
  completions. Verify via issue status/`APPROVED` comments and output-file
  mtimes, not the count you remember. Also run the merged-but-open sweep
  (see [worktrees.md](worktrees.md)) before dispatching new work, not after
  — it's cheap and catches duplicate dispatch.
- When the maintainer says "I already asked for this," search closed issues
  first (`gh issue list --state all --search ...`). It was often shipped
  but doesn't meet the bar — say so honestly and file an "extend, don't
  redo" follow-up rather than treating it as new.
- `needs-human-confirmation` usually means already shipped, awaiting a
  physical-device dogfood pass, not "still needs building." Audit
  `origin/main` before rebuilding.
- No-backwards-compatibility (hard cuts only) and no-background-work are
  locked project-wide decisions — see `docs/decisions.md` D21/D22 rather
  than re-deriving them here.
