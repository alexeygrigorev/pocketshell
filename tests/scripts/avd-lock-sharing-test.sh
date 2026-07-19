#!/usr/bin/env bash
set -euo pipefail

# Cross-worktree AVD lock sharing harness (issue #1657).
#
# THE DEFECT this harness exists to stop coming back: the AVD lock file used to
# be derived from `$root_dir` — the *worktree* root — so every checkout got its
# OWN lock file. `flock` on distinct files serialises nothing, therefore two
# agents running `scripts/connected-test.sh` from `.worktrees/issue-A` and
# `.worktrees/issue-B` held DIFFERENT locks and ran on the ONE emulator
# concurrently. `process.md` has been telling agents since #672 that the flock
# "is what makes parallel agents safe"; it never did.
#
# The contended resource is the EMULATOR (a machine-wide singleton), not the
# checkout, so the lock must be anchored machine-wide. These tests pin that:
#
#   1. two "worktrees" -> the SAME default lock  (the bug; red before the fix)
#   2. two "worktrees" -> the SAME per-serial lock for the SAME serial
#      (the #724 pool must not co-locate two lanes on one emulator)
#   3. NEGATIVE (G6): DISTINCT serials must STILL run CONCURRENTLY. A "fix" that
#      globally serialises everything would queue every #724 pool lane behind
#      every other — a worse regression than the bug.
#   4. an explicit POCKETSHELL_AVD_LOCK_FILE (the shard token path, avd-lock.sh
#      :9/:21) still shards.
#   5. a crashed/-9'd holder must NOT wedge the machine forever.
#   6. the resolved lock path must not live under any worktree root.
#
# No emulator is touched: the lock semantics are provable with two processes and
# a lock file, and racing the real AVD would corrupt a sibling agent's run.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

# Build a fake "worktree": a private root dir carrying its own copy of the
# helper, exactly like `.worktrees/issue-N/` carries its own checkout of
# scripts/lib/avd-lock.sh. Sourcing THIS copy and passing THIS root is what an
# agent in that worktree does.
make_worktree() {
  local root="$1"
  mkdir -p "$root/scripts/lib" "$root/build"
  cp "$ROOT_DIR/scripts/lib/avd-lock.sh" "$root/scripts/lib/avd-lock.sh"
}

# Start a background acquirer for $root_dir. It acquires the lock, touches
# $ready, then holds it until $release appears. Echoes the acquirer's PID.
#
# $serial (optional, may be empty) selects the per-serial lock path the #724
# pool uses; empty means the DEFAULT single-lock path.
start_acquirer() {
  local root_dir="$1" ready="$2" release="$3" serial="${4:-}"
  setsid bash -c '
    set -euo pipefail
    root_dir="$1"; ready="$2"; release="$3"; serial="$4"
    source "$root_dir/scripts/lib/avd-lock.sh"
    if [[ -n "$serial" ]]; then
      POCKETSHELL_AVD_LOCK_FILE="$(pocketshell_avd_lock_file_for_serial "$root_dir" "$serial")"
      export POCKETSHELL_AVD_LOCK_FILE
    fi
    pocketshell_acquire_avd_lock "$root_dir" >/dev/null 2>&1 || exit 1
    printf "%s\n" "${POCKETSHELL_AVD_LOCK_FILE:-}" > "$ready"
    while [[ ! -e "$release" ]]; do sleep 0.05; done
    pocketshell_release_avd_lock
  ' bash "$root_dir" "$ready" "$release" "$serial" >/dev/null 2>&1 &
  printf '%s\n' "$!"
}

# Wait up to $2 seconds for file $1 to appear. 0 = appeared, 1 = timed out.
wait_for_file() {
  local target="$1" timeout="${2:-5}"
  local waited=0
  local limit=$(( timeout * 20 ))
  while [[ ! -e "$target" ]]; do
    (( waited++ >= limit )) && return 1
    sleep 0.05
  done
  return 0
}

# Kill a whole acquirer process group (the holder subshell is a child).
kill_group() {
  local pid="${1:-}"
  [[ -n "$pid" ]] || return 0
  kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
}

# --------------------------------------------------------------------------
# 1. THE BUG (red on base): two worktrees must contend for the default lock.
# --------------------------------------------------------------------------
two_worktrees_share_the_default_lock() {
  local tmp="$1"
  export POCKETSHELL_AVD_LOCK_DIR="$tmp/shared-locks"

  make_worktree "$tmp/wt-a"
  make_worktree "$tmp/wt-b"

  local a_pid b_pid
  a_pid="$(start_acquirer "$tmp/wt-a" "$tmp/a.ready" "$tmp/a.release")"
  wait_for_file "$tmp/a.ready" 10 || { kill_group "$a_pid"; fail "worktree A never acquired the default AVD lock"; }

  b_pid="$(start_acquirer "$tmp/wt-b" "$tmp/b.ready" "$tmp/b.release")"

  # THE LOAD-BEARING ASSERTION. On the buggy code B gets its own
  # wt-b/build/.avd-lock and acquires instantly while A still holds A's file:
  # two agents, one emulator, no serialisation.
  if wait_for_file "$tmp/b.ready" 3; then
    kill_group "$a_pid"; kill_group "$b_pid"
    fail "worktree B acquired the AVD lock while worktree A held it -- the lock is per-worktree, so it serialises NOTHING (issue #1657)"
  fi

  # ... and B must not block forever either: releasing A must hand it over.
  touch "$tmp/a.release"
  if ! wait_for_file "$tmp/b.ready" 10; then
    kill_group "$a_pid"; kill_group "$b_pid"
    fail "worktree B never acquired the lock after worktree A released it (the lock wedged instead of queuing)"
  fi

  touch "$tmp/b.release"
  kill_group "$a_pid"; kill_group "$b_pid"
  unset POCKETSHELL_AVD_LOCK_DIR
}

# --------------------------------------------------------------------------
# 2. Same defect on the #724 per-serial path: two worktrees claiming the SAME
#    emulator must contend (the pool's "NEVER co-locate" promise, avd-lock.sh
#    P4). Per-worktree lock files broke this too.
# --------------------------------------------------------------------------
two_worktrees_share_a_per_serial_lock() {
  local tmp="$1"
  export POCKETSHELL_AVD_LOCK_DIR="$tmp/shared-locks"

  make_worktree "$tmp/wt-a"
  make_worktree "$tmp/wt-b"

  local a_pid b_pid
  a_pid="$(start_acquirer "$tmp/wt-a" "$tmp/a.ready" "$tmp/a.release" "emulator-5554")"
  wait_for_file "$tmp/a.ready" 10 || { kill_group "$a_pid"; fail "worktree A never claimed emulator-5554"; }

  b_pid="$(start_acquirer "$tmp/wt-b" "$tmp/b.ready" "$tmp/b.release" "emulator-5554")"

  if wait_for_file "$tmp/b.ready" 3; then
    kill_group "$a_pid"; kill_group "$b_pid"
    fail "two worktrees both claimed emulator-5554 at once -- the per-serial lock is per-worktree (issue #1657)"
  fi

  touch "$tmp/a.release"
  wait_for_file "$tmp/b.ready" 10 \
    || { kill_group "$a_pid"; kill_group "$b_pid"; fail "worktree B never claimed emulator-5554 after A released it"; }

  touch "$tmp/b.release"
  kill_group "$a_pid"; kill_group "$b_pid"
  unset POCKETSHELL_AVD_LOCK_DIR
}

# --------------------------------------------------------------------------
# 3. THE NEGATIVE CASE (G6). DISTINCT emulators must STILL be concurrent, even
#    across worktrees. This is the assertion that stops the "fix" from becoming
#    a global serialisation that destroys #724's parallel journey lanes.
# --------------------------------------------------------------------------
distinct_serials_still_run_concurrently_across_worktrees() {
  local tmp="$1"
  export POCKETSHELL_AVD_LOCK_DIR="$tmp/shared-locks"

  make_worktree "$tmp/wt-a"
  make_worktree "$tmp/wt-b"

  local a_pid b_pid
  a_pid="$(start_acquirer "$tmp/wt-a" "$tmp/a.ready" "$tmp/a.release" "emulator-5554")"
  wait_for_file "$tmp/a.ready" 10 || { kill_group "$a_pid"; fail "pool lane A never claimed emulator-5554"; }

  b_pid="$(start_acquirer "$tmp/wt-b" "$tmp/b.ready" "$tmp/b.release" "emulator-5556")"

  # Lane B is on a DIFFERENT emulator: it must NOT queue behind lane A.
  if ! wait_for_file "$tmp/b.ready" 10; then
    touch "$tmp/a.release"; kill_group "$a_pid"; kill_group "$b_pid"
    fail "pool lane B (emulator-5556) blocked behind lane A (emulator-5554) -- distinct emulators were globally serialised, which would destroy #724's parallel lanes"
  fi

  local a_lock b_lock
  a_lock="$(<"$tmp/a.ready")"
  b_lock="$(<"$tmp/b.ready")"
  [[ -n "$a_lock" && -n "$b_lock" ]] || fail "per-serial lock paths were not exported"
  [[ "$a_lock" != "$b_lock" ]] \
    || fail "distinct serials resolved to the SAME lock file ($a_lock) -- pool lanes would serialise"

  touch "$tmp/a.release"; touch "$tmp/b.release"
  kill_group "$a_pid"; kill_group "$b_pid"
  unset POCKETSHELL_AVD_LOCK_DIR
}

# --------------------------------------------------------------------------
# 4. Shard-token concurrency (avd-lock.sh :9/:21): an explicit
#    POCKETSHELL_AVD_LOCK_FILE still wins, so shards on distinct emulators keep
#    their own locks.
# --------------------------------------------------------------------------
explicit_lock_file_override_still_shards() {
  local tmp="$1"
  export POCKETSHELL_AVD_LOCK_DIR="$tmp/shared-locks"
  make_worktree "$tmp/wt-a"

  local out_a="$tmp/shard-a.out" out_b="$tmp/shard-b.out"

  POCKETSHELL_AVD_LOCK_FILE="$tmp/shard-a.lock" setsid bash -c '
    set -euo pipefail
    source "$1/scripts/lib/avd-lock.sh"
    pocketshell_acquire_avd_lock "$1" >/dev/null 2>&1
    printf "%s\n" "$POCKETSHELL_AVD_LOCK_FILE" > "$2"
    while [[ ! -e "$3" ]]; do sleep 0.05; done
    pocketshell_release_avd_lock
  ' bash "$tmp/wt-a" "$out_a" "$tmp/shard-a.release" >/dev/null 2>&1 &
  local a_pid="$!"
  wait_for_file "$out_a" 10 || { kill_group "$a_pid"; fail "shard A never acquired its token lock"; }
  [[ "$(<"$out_a")" == "$tmp/shard-a.lock" ]] \
    || fail "explicit POCKETSHELL_AVD_LOCK_FILE was overridden (got $(<"$out_a"))"

  # A second shard with its OWN token must not block on shard A.
  POCKETSHELL_AVD_LOCK_FILE="$tmp/shard-b.lock" timeout 10s bash -c '
    set -euo pipefail
    source "$1/scripts/lib/avd-lock.sh"
    pocketshell_acquire_avd_lock "$1" >/dev/null 2>&1
    printf "%s\n" "$POCKETSHELL_AVD_LOCK_FILE" > "$2"
    pocketshell_release_avd_lock
  ' bash "$tmp/wt-a" "$out_b" \
    || { touch "$tmp/shard-a.release"; kill_group "$a_pid"; fail "shard B blocked on shard A despite a distinct POCKETSHELL_AVD_LOCK_FILE"; }
  [[ "$(<"$out_b")" == "$tmp/shard-b.lock" ]] || fail "shard B used the wrong lock file"

  touch "$tmp/shard-a.release"
  kill_group "$a_pid"
  unset POCKETSHELL_AVD_LOCK_DIR
}

# --------------------------------------------------------------------------
# 5. Stale lock: a holder killed with -9 (harness timeout, stray pkill, OOM)
#    must NOT wedge the machine. flock is fd-scoped, so the kernel drops the
#    lock when the process dies -- pin that, because a lock-file-content or
#    pidfile scheme would NOT have this property.
# --------------------------------------------------------------------------
crashed_holder_does_not_wedge_the_lock() {
  local tmp="$1"
  export POCKETSHELL_AVD_LOCK_DIR="$tmp/shared-locks"

  make_worktree "$tmp/wt-a"
  make_worktree "$tmp/wt-b"

  local a_pid
  a_pid="$(start_acquirer "$tmp/wt-a" "$tmp/a.ready" "$tmp/a.release")"
  wait_for_file "$tmp/a.ready" 10 || { kill_group "$a_pid"; fail "worktree A never acquired the lock"; }

  # SIGKILL the whole group: no traps, no graceful release -- a crash.
  kill -KILL -- "-$a_pid" 2>/dev/null || kill -KILL "$a_pid" 2>/dev/null || true
  wait "$a_pid" 2>/dev/null || true

  local b_pid
  b_pid="$(start_acquirer "$tmp/wt-b" "$tmp/b.ready" "$tmp/b.release")"
  if ! wait_for_file "$tmp/b.ready" 10; then
    kill_group "$b_pid"
    fail "a SIGKILLed holder wedged the AVD lock -- the next run could never acquire it"
  fi

  touch "$tmp/b.release"
  kill_group "$b_pid"
  unset POCKETSHELL_AVD_LOCK_DIR
}

# --------------------------------------------------------------------------
# 6. The anchor itself: the default lock path must not be derived from the
#    worktree root. This is the root cause stated as an assertion.
# --------------------------------------------------------------------------
default_lock_path_is_not_under_the_worktree() {
  local tmp="$1"
  unset POCKETSHELL_AVD_LOCK_DIR || true
  make_worktree "$tmp/wt-a"
  make_worktree "$tmp/wt-b"

  local path_a path_b
  path_a="$(bash -c 'source "$1/scripts/lib/avd-lock.sh"; pocketshell_avd_lock_file_for_serial "$1" emulator-5554' bash "$tmp/wt-a")"
  path_b="$(bash -c 'source "$1/scripts/lib/avd-lock.sh"; pocketshell_avd_lock_file_for_serial "$1" emulator-5554' bash "$tmp/wt-b")"

  [[ "$path_a" == "$path_b" ]] \
    || fail "the same serial resolved to different lock files from two worktrees ($path_a vs $path_b) -- still worktree-anchored (issue #1657)"
  [[ "$path_a" != "$tmp/wt-a"* ]] \
    || fail "lock path is still under the worktree root: $path_a"
}

with_tmpdir() {
  local tmpdir
  tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-avd-lock-sharing.XXXXXX")"
  "$@" "$tmpdir"
  rm -rf "$tmpdir"
  printf '  ok: %s\n' "$1"
}

CASES=(
  two_worktrees_share_the_default_lock
  two_worktrees_share_a_per_serial_lock
  distinct_serials_still_run_concurrently_across_worktrees
  explicit_lock_file_override_still_shards
  crashed_holder_does_not_wedge_the_lock
  default_lock_path_is_not_under_the_worktree
)

# Optional case filter, so a single scenario's red/green can be captured on
# demand (`avd-lock-sharing-test.sh two_worktrees_share_the_default_lock`).
# No arguments = run everything, which is what the gate does.
if [[ $# -gt 0 ]]; then
  CASES=("$@")
fi

for case_name in "${CASES[@]}"; do
  with_tmpdir "$case_name"
done

printf 'PASS: avd-lock cross-worktree sharing (issue #1657)\n'
