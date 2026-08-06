#!/usr/bin/env bash
set -uo pipefail

# ---------------------------------------------------------------------------
# Serialized, safe-list disk reclaim for the dev box (issue #1989)
#
# WHY THIS EXISTS. During #1963 validation the root filesystem hit 100%
# (436G, 24M available) and two gates died before a single test ran — Docker
# BuildKit could not write ~/.docker/buildx, and Gradle could not create
# ~/.gradle/caches/8.13. `docker image prune -f` recovered ~4–5 GiB and the
# gates ran clean. That recovery was performed by hand, from memory, on a box
# with four live agent lanes and a running emulator. This script is that
# recovery written down, serialized, and made refusable.
#
# THE ONE RULE. **A cleanup that destroys work is far worse than a full disk.**
# On 2026-07-19 a routine `agent-*` worktree sweep destroyed the entire #1487
# compileSdk-36 implementation, because it was held ONLY in a worktree with no
# branch and no patch. `git worktree remove --force` and `git diff` are both
# traps here: `--force` discards uncommitted work with no recovery, and
# `git diff` does not show untracked files, so a new regression test is
# invisible to it. This script therefore:
#
#   * defaults to DRY RUN. Nothing is deleted without --apply.
#   * checks worktrees with `git status --porcelain`, which reports BOTH
#     tracked modifications and `??` untracked files, and additionally refuses
#     any worktree with commits not reachable from origin/main.
#   * never removes a worktree at all without the separate --worktrees opt-in,
#     and never uses `--force`.
#   * carries an explicit DENY LIST that is checked immediately before every
#     deletion, not merely used to build the candidate list.
#
# WHAT IT WILL DELETE (the complete list — nothing else is reachable):
#   1. Docker BUILD CACHE            `docker builder prune -f`
#   2. DANGLING docker images        `docker image prune -f`   (never -a)
#   3. /tmp/pocketshell-* scratch older than --tmp-age-days (default 1)
#   4. <root>/build/pre-release-confidence-gate and <root>/build/phone-walkthrough
#   5. with --worktrees ONLY: .claude/worktrees/agent-* that are unlocked, have
#      an empty `git status --porcelain`, and have no commits ahead of
#      origin/main.
#
# WHAT IT WILL NEVER DELETE (asserted, not merely intended):
#   * running or stopped containers, and any TAGGED image — `docker image
#     prune` without -a only removes dangling layers, and stage 2 additionally
#     verifies every `pocketshell-test:*` image still exists afterwards.
#   * ~/.gradle/caches, ~/.android/avd, ~/.m2 — rebuilding those costs hours.
#   * .worktrees/issue-* (unmerged reviewed work) — excluded by pattern AND by
#     the deny list.
#   * any worktree with uncommitted, untracked, or unpushed work.
#   * the checkout this script is running from.
#   * other projects' Docker images. AGENTS.md says ask first; this script
#     therefore cannot touch them at all (no -a, no name filters).
#
# SERIALIZATION. The box runs several agent lanes at once. Two concurrent
# sweeps would race on the same worktree list and the same Docker daemon, and
# one could remove a directory the other is mid-`du`. A machine-wide per-user
# flock (same anchor convention as the #1657 AVD lock and the #2007 output
# lock: the PASSWD home, not $HOME) makes the sweep single-writer.
#
# Usage:
#   scripts/disk-cleanup.sh                  # dry run: report only
#   scripts/disk-cleanup.sh --apply          # reclaim stages 1-4
#   scripts/disk-cleanup.sh --apply --worktrees   # also sweep clean agent worktrees
# ---------------------------------------------------------------------------

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

APPLY=0
SWEEP_WORKTREES=0
TMP_AGE_DAYS=1
TMP_ROOT="/tmp"
LOCK_WAIT_SECONDS="${POCKETSHELL_DISK_CLEANUP_LOCK_WAIT_SECONDS:-900}"

usage() {
  cat >&2 <<'USAGE'
Serialized, safe-list disk reclaim for the dev box (issue #1989).

Usage:
  scripts/disk-cleanup.sh [--apply] [--worktrees] [--root DIR]
                          [--tmp-age-days N]

  (no flags)        DRY RUN. Reports every candidate and reclaims nothing.
  --apply           Actually reclaim. Without it nothing is ever deleted.
  --worktrees       Also sweep .claude/worktrees/agent-* worktrees that are
                    unlocked, have an empty `git status --porcelain` (tracked
                    AND untracked), and have no commits ahead of origin/main.
                    Separate opt-in on purpose: this is the stage that cost the
                    #1487 implementation when it was run casually.
  --root DIR        Repository root to sweep (default: this script's checkout).
  --tmp-age-days N  Only remove <tmp-root>/pocketshell-* older than N days
                    (default 1).
  --tmp-root DIR    Scratch root for stage 3 (default /tmp). Exists so the
                    regression harness can exercise the real deletion path
                    without pointing it at the shared /tmp that live sibling
                    lanes are using.
  --help            Print this and exit.

Never touches: active containers, tagged images, ~/.gradle/caches,
~/.android/avd, .worktrees/issue-*, or any worktree holding uncommitted,
untracked, or unpushed work.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help | -h) usage; exit 0 ;;
    --apply) APPLY=1; shift ;;
    --worktrees) SWEEP_WORKTREES=1; shift ;;
    --root)
      [[ $# -ge 2 ]] || { printf 'FAIL: --root needs a value\n' >&2; exit 2; }
      ROOT_DIR="$(cd -- "$2" 2>/dev/null && pwd -P)" || {
        printf 'FAIL: --root does not exist: %s\n' "$2" >&2; exit 2; }
      shift 2
      ;;
    --tmp-age-days)
      [[ $# -ge 2 ]] || { printf 'FAIL: --tmp-age-days needs a value\n' >&2; exit 2; }
      TMP_AGE_DAYS="$2"
      [[ "$TMP_AGE_DAYS" =~ ^[0-9]+$ ]] || {
        printf 'FAIL: --tmp-age-days must be a whole number (got: %s)\n' "$TMP_AGE_DAYS" >&2
        exit 2
      }
      shift 2
      ;;
    --tmp-root)
      [[ $# -ge 2 ]] || { printf 'FAIL: --tmp-root needs a value\n' >&2; exit 2; }
      TMP_ROOT="$(cd -- "$2" 2>/dev/null && pwd -P)" || {
        printf 'FAIL: --tmp-root does not exist: %s\n' "$2" >&2; exit 2; }
      shift 2
      ;;
    *)
      printf 'FAIL: unexpected argument %s\n' "$1" >&2
      usage
      exit 2
      ;;
  esac
done

source "$ROOT_DIR/scripts/lib/disk-preflight.sh"

# ---------------------------------------------------------------------------
# Deny list. Checked immediately before EVERY deletion rather than only while
# building the candidate list, so a future stage cannot smuggle a protected
# path past it by constructing the path a different way.
# ---------------------------------------------------------------------------
passwd_home="$(getent passwd "$(id -u)" 2>/dev/null | awk -F: 'NR==1 {print $6}')"
[[ -n "$passwd_home" ]] || passwd_home="$HOME"

DENY_SUBSTRINGS=(
  "/.gradle/caches"
  "/.android/avd"
  "/.android/adb"
  "/.m2/"
  "/.worktrees/issue-"
  "/.ssh"
)

deny_check() {
  local target="$1"
  local pattern
  if [[ -z "$target" || "$target" != /* ]]; then
    printf 'REFUSING: not an absolute path: %s\n' "$target" >&2
    return 1
  fi
  case "$target" in
    / | /home | /tmp | /var | "$passwd_home" | "$ROOT_DIR")
      printf 'REFUSING: %s is a protected root\n' "$target" >&2
      return 1
      ;;
  esac
  for pattern in "${DENY_SUBSTRINGS[@]}"; do
    if [[ "$target" == *"$pattern"* ]]; then
      printf 'REFUSING: %s matches the never-touch pattern %s\n' "$target" "$pattern" >&2
      return 1
    fi
  done
  return 0
}

size_mb() {
  local target="$1"
  du -sm -- "$target" 2>/dev/null | awk 'NR==1 {print $1}' || printf '0\n'
}

remove_path() {
  local target="$1"
  local reclaimed
  deny_check "$target" || return 1
  reclaimed="$(size_mb "$target")"
  if (( APPLY == 1 )); then
    if rm -rf -- "$target"; then
      printf '  [APPLY]   removed %s (%s MiB)\n' "$target" "${reclaimed:-0}"
    else
      printf '  [APPLY]   FAILED to remove %s\n' "$target" >&2
      return 1
    fi
  else
    printf '  [DRY-RUN] would remove %s (%s MiB)\n' "$target" "${reclaimed:-0}"
  fi
}

# ---------------------------------------------------------------------------
# Machine-wide per-user serialization (see the header). The PASSWD home, not
# $HOME, so a caller with an unusual exported HOME cannot get a different lock
# than its sibling -- the split-lock defect #1657 fixed for the AVD lock.
# ---------------------------------------------------------------------------
lock_dir="${POCKETSHELL_DISK_CLEANUP_LOCK_DIR:-$passwd_home/.cache/pocketshell}"
mkdir -p "$lock_dir" 2>/dev/null || true
lock_file="$lock_dir/disk-cleanup.lock"
exec 8>>"$lock_file" || {
  printf 'FAIL: cannot open the disk-cleanup lock: %s\n' "$lock_file" >&2
  exit 1
}
if ! flock -n 8; then
  printf 'Another disk cleanup is running; queuing up to %ss (lock: %s)\n' \
    "$LOCK_WAIT_SECONDS" "$lock_file" >&2
  if ! flock -w "$LOCK_WAIT_SECONDS" 8; then
    printf 'FAIL: gave up waiting %ss for the disk-cleanup lock: %s\n' \
      "$LOCK_WAIT_SECONDS" "$lock_file" >&2
    exit 1
  fi
fi
printf 'pid=%s started=%s apply=%s worktrees=%s\n' \
  "$$" "$(date -Is 2>/dev/null || date)" "$APPLY" "$SWEEP_WORKTREES" > "$lock_file"

free_before="$(pocketshell_disk_free_mb "$ROOT_DIR" || printf 'unknown\n')"
if (( APPLY == 1 )); then
  printf 'Disk cleanup (issue #1989) APPLY mode. Root: %s. Free before: %s MiB\n' \
    "$ROOT_DIR" "$free_before"
else
  printf 'Disk cleanup (issue #1989) DRY RUN -- nothing will be deleted. Root: %s. Free: %s MiB\n' \
    "$ROOT_DIR" "$free_before"
  printf 'Re-run with --apply to reclaim.\n'
fi

# ---------------------------------------------------------------------------
# Stage 1+2: Docker build cache and DANGLING images.
#
# `docker image prune` WITHOUT -a removes only dangling (untagged, unreferenced)
# layers: a tagged image such as pocketshell-test:* cannot be removed by it, and
# neither can any image a container is using. That is a property of the command,
# not of a filter we pass -- but the property is exactly what an operator has to
# take on faith at 100% disk, so stage 2 re-reads the pocketshell-test:* tag list
# afterwards and hard-fails if any disappeared. `-a` is never used: it would take
# other projects' images, which AGENTS.md says to ask about first.
# ---------------------------------------------------------------------------
printf '\nStage 1/2: Docker build cache + dangling images\n'
if ! command -v docker >/dev/null 2>&1; then
  printf '  skipped: docker is not on PATH\n'
elif ! docker info >/dev/null 2>&1; then
  printf '  skipped: docker daemon is not reachable\n'
else
  pocketshell_images_before="$(docker images --format '{{.Repository}}:{{.Tag}}' 2>/dev/null | grep '^pocketshell-test:' | sort || true)"
  if (( APPLY == 1 )); then
    printf '  [APPLY]   docker builder prune -f\n'
    docker builder prune -f 2>&1 | sed 's/^/    /'
    printf '  [APPLY]   docker image prune -f (dangling only, never -a)\n'
    docker image prune -f 2>&1 | sed 's/^/    /'
    pocketshell_images_after="$(docker images --format '{{.Repository}}:{{.Tag}}' 2>/dev/null | grep '^pocketshell-test:' | sort || true)"
    if [[ "$pocketshell_images_before" != "$pocketshell_images_after" ]]; then
      printf 'FAIL: pocketshell-test images changed across the prune. before=[%s] after=[%s]\n' \
        "$pocketshell_images_before" "$pocketshell_images_after" >&2
      exit 1
    fi
    printf '  verified: every pocketshell-test:* image survived the prune\n'
  else
    printf '  [DRY-RUN] would run: docker builder prune -f; docker image prune -f\n'
    printf '  reclaimable now:\n'
    docker system df 2>&1 | sed 's/^/    /'
  fi
fi

# ---------------------------------------------------------------------------
# Stage 3: /tmp/pocketshell-* scratch. Age-bounded because a LIVE run owns
# freshly created scratch (lane init scripts, evidence keys, lock state dirs);
# deleting those out from under a sibling lane is the cross-agent damage
# process.md warns about.
# ---------------------------------------------------------------------------
printf '\nStage 3: %s/pocketshell-* scratch older than %s day(s)\n' \
  "$TMP_ROOT" "$TMP_AGE_DAYS"
tmp_candidates=0
while IFS= read -r -d '' candidate; do
  tmp_candidates=$((tmp_candidates + 1))
  remove_path "$candidate" || true
done < <(find "$TMP_ROOT" -mindepth 1 -maxdepth 1 -name 'pocketshell-*' -mtime "+$TMP_AGE_DAYS" -print0 2>/dev/null)
(( tmp_candidates == 0 )) && printf '  nothing to reclaim\n'

# ---------------------------------------------------------------------------
# Stage 4: transient gate scratch. Both are regenerated by the next gate run;
# AGENTS.md names them as safe. Named EXPLICITLY rather than globbed under
# build/, because build/ also holds the test-result XML a gate verdict is read
# from, and deleting that mid-triage destroys the only artifact identifying a
# failing assertion (the #1969 lesson).
# ---------------------------------------------------------------------------
printf '\nStage 4: transient gate scratch under %s/build\n' "$ROOT_DIR"
gate_scratch=0
for relative in build/pre-release-confidence-gate build/phone-walkthrough; do
  target="$ROOT_DIR/$relative"
  [[ -d "$target" ]] || continue
  gate_scratch=$((gate_scratch + 1))
  remove_path "$target" || true
done
(( gate_scratch == 0 )) && printf '  nothing to reclaim\n'

# ---------------------------------------------------------------------------
# Stage 5: agent worktrees. The dangerous one -- see the header.
# ---------------------------------------------------------------------------
printf '\nStage 5: .claude/worktrees/agent-* worktrees\n'
if (( SWEEP_WORKTREES == 0 )); then
  printf '  reporting only (pass --worktrees to sweep; --apply --worktrees to remove)\n'
fi

git_common_dir="$(git -C "$ROOT_DIR" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)"
if [[ -z "$git_common_dir" ]]; then
  printf '  skipped: %s is not a git checkout\n' "$ROOT_DIR"
else
  main_checkout="$(dirname -- "$git_common_dir")"
  self_worktree="$(git -C "$ROOT_DIR" rev-parse --path-format=absolute --show-toplevel 2>/dev/null || true)"
  worktree_path=""
  worktree_locked=0
  worktree_candidates=0

  consider_worktree() {
    local path="$1"
    local locked="$2"
    [[ -n "$path" ]] || return 0
    # Pattern gate: ONLY agent-* scratch worktrees are ever eligible.
    # .worktrees/issue-* holds reviewed-but-unmerged work and is excluded here
    # as well as by the deny list.
    case "$path" in
      "$main_checkout"/.claude/worktrees/agent-*) ;;
      *) return 0 ;;
    esac
    worktree_candidates=$((worktree_candidates + 1))
    if [[ "$path" == "$self_worktree" ]]; then
      printf '  SKIP  %s -- this is the checkout running the cleanup\n' "$path"
      return 0
    fi
    if (( locked == 1 )); then
      printf '  SKIP  %s -- git marks it locked (an agent is in flight)\n' "$path"
      return 0
    fi
    local dirty
    # --porcelain reports BOTH tracked modifications and `??` untracked files.
    # `git diff` would silently omit the untracked ones, which is how a new
    # regression test gets destroyed by a sweep that believed it was clean.
    dirty="$(git -C "$path" status --porcelain 2>/dev/null)"
    if [[ -n "$dirty" ]]; then
      printf '  SKIP  %s -- %s uncommitted/untracked change(s)\n' \
        "$path" "$(printf '%s\n' "$dirty" | grep -c . )"
      return 0
    fi
    local ahead
    ahead="$(git -C "$path" rev-list --count origin/main..HEAD 2>/dev/null || printf 'unknown\n')"
    if [[ "$ahead" != "0" ]]; then
      printf '  SKIP  %s -- %s commit(s) not reachable from origin/main\n' "$path" "$ahead"
      return 0
    fi
    if (( SWEEP_WORKTREES == 0 )); then
      printf '  CLEAN %s (%s MiB) -- pass --worktrees to remove\n' "$path" "$(size_mb "$path")"
      return 0
    fi
    deny_check "$path" || return 0
    if (( APPLY == 1 )); then
      # Never --force: force discards uncommitted work, and the checks above
      # are what make this removal safe, not the flag.
      local reclaimed
      reclaimed="$(size_mb "$path")"
      if git -C "$main_checkout" worktree remove "$path" 2>/dev/null; then
        printf '  [APPLY]   removed worktree %s (%s MiB)\n' "$path" "$reclaimed"
      else
        printf '  [APPLY]   git refused to remove %s; left in place\n' "$path" >&2
      fi
    else
      printf '  [DRY-RUN] would remove worktree %s (%s MiB)\n' "$path" "$(size_mb "$path")"
    fi
  }

  while IFS= read -r line; do
    case "$line" in
      "worktree "*)
        consider_worktree "$worktree_path" "$worktree_locked"
        worktree_path="${line#worktree }"
        worktree_locked=0
        ;;
      "locked"|"locked "*)
        worktree_locked=1
        ;;
    esac
  done < <(git -C "$main_checkout" worktree list --porcelain 2>/dev/null)
  consider_worktree "$worktree_path" "$worktree_locked"

  (( worktree_candidates == 0 )) && printf '  no agent-* worktrees found\n'
fi

free_after="$(pocketshell_disk_free_mb "$ROOT_DIR" || printf 'unknown\n')"
printf '\nFree before: %s MiB\nFree after:  %s MiB\n' "$free_before" "$free_after"
if (( APPLY == 0 )); then
  printf 'DRY RUN: nothing was deleted.\n'
fi
exit 0
