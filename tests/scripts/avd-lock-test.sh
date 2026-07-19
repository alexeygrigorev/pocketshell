#!/usr/bin/env bash
set -euo pipefail

# Hermetic harness (issue #1702). A driving shell that already holds the machine
# AVD lock -- e.g. pre-release-confidence-gate.sh, which calls
# pocketshell_acquire_avd_lock BEFORE running `assembleDebug check` -> this suite
# -- EXPORTS the acquire STATE (POCKETSHELL_AVD_LOCK_ACQUIRED and friends). When
# these cases fork a fresh gate, that inherited state short-circuits
# pocketshell_acquire_avd_lock (it early-returns "already acquired"), so the lock
# is never actually taken and the ownership assertions fail against the gate's
# own lock -- self-contention, not a product bug. Scrub the process-internal
# acquire state so the harness is correct whether or not a gate holds the real
# lock. The real #1663 machine-anchoring behaviour is untouched (only avd-lock.sh
# defines it; this only clears inherited runtime state).
unset POCKETSHELL_AVD_LOCK_ACQUIRED \
      POCKETSHELL_AVD_LOCK_FILE \
      POCKETSHELL_AVD_LOCK_HOLDER_PID \
      POCKETSHELL_AVD_LOCK_OWNER_PID \
      POCKETSHELL_POOL_HOLDER_PID \
      POCKETSHELL_POOL_OWNER_PID \
      POCKETSHELL_POOL_SERIAL \
      POCKETSHELL_TOXIPROXY_LOCK_HOLDER_PID \
      POCKETSHELL_TOXIPROXY_LOCK_OWNER_PID

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

with_tmpdir() {
  local tmpdir
  tmpdir="$(mktemp -d)"
  "$@" "$tmpdir"
  rm -rf "$tmpdir"
}

child_processes_do_not_hold_lock() {
  local tmpdir="$1"
  local lock_file="$tmpdir/avd.lock"

  POCKETSHELL_AVD_LOCK_FILE="$lock_file" bash -c '
    set -euo pipefail
    source "$1/scripts/lib/avd-lock.sh"
    pocketshell_acquire_avd_lock "$1"
    bash -c "sleep 10" &
    child_pid="$!"
    pocketshell_release_avd_lock
    flock -n "$POCKETSHELL_AVD_LOCK_FILE" true
    kill "$child_pid" 2>/dev/null || true
    wait "$child_pid" 2>/dev/null || true
  ' bash "$ROOT_DIR" || fail "child process inherited the AVD lock"
}

nested_gates_do_not_reacquire_or_release() {
  local tmpdir="$1"
  local lock_file="$tmpdir/avd.lock"

  POCKETSHELL_AVD_LOCK_FILE="$lock_file" bash -c '
    set -euo pipefail
    source "$1/scripts/lib/avd-lock.sh"
    pocketshell_acquire_avd_lock "$1"

    timeout 1s bash -c "
      set -euo pipefail
      source \"\$1/scripts/lib/avd-lock.sh\"
      pocketshell_acquire_avd_lock \"\$1\"
      pocketshell_release_avd_lock
    " bash "$1"

    if flock -n "$POCKETSHELL_AVD_LOCK_FILE" true; then
      echo "nested gate released the outer AVD lock" >&2
      exit 1
    fi

    pocketshell_release_avd_lock
    flock -n "$POCKETSHELL_AVD_LOCK_FILE" true
  ' bash "$ROOT_DIR" || fail "nested gate lock ownership was not preserved"
}

help_mode_does_not_wait_for_lock() {
  local tmpdir="$1"
  local lock_file="$tmpdir/avd.lock"

  ( flock "$lock_file" sleep 2 ) &
  local holder_pid="$!"
  sleep 0.1

  POCKETSHELL_AVD_LOCK_FILE="$lock_file" timeout 1s bash -c '
    set -euo pipefail
    source "$1/scripts/lib/avd-lock.sh"
    pocketshell_acquire_avd_lock "$1" --help
  ' bash "$ROOT_DIR" || {
    kill "$holder_pid" 2>/dev/null || true
    wait "$holder_pid" 2>/dev/null || true
    fail "help mode waited for the AVD lock"
  }

  kill "$holder_pid" 2>/dev/null || true
  wait "$holder_pid" 2>/dev/null || true
}

phone_walkthrough_late_help_releases_lock() {
  local tmpdir="$1"
  local lock_file="$tmpdir/avd.lock"

  POCKETSHELL_AVD_LOCK_FILE="$lock_file" \
    LOG_ROOT="$tmpdir/phone-walkthrough" \
    RUN_ID="late-help" \
    timeout 5s "$ROOT_DIR/scripts/phone-walkthrough.sh" terminal-lab --help \
    > "$tmpdir/late-help.out" 2> "$tmpdir/late-help.err" ||
    fail "phone walkthrough late help did not exit successfully"

  if ! flock -n "$lock_file" true; then
    fuser -k "$lock_file" >/dev/null 2>&1 || true
    fail "phone walkthrough late help leaked the AVD lock holder"
  fi
}

with_tmpdir child_processes_do_not_hold_lock
with_tmpdir nested_gates_do_not_reacquire_or_release
with_tmpdir help_mode_does_not_wait_for_lock
with_tmpdir phone_walkthrough_late_help_releases_lock

printf 'PASS: avd-lock helper\n'
