#!/usr/bin/env bash

# ---------------------------------------------------------------------------
# Where AVD lock files live (issue #1657)
#
# The lock protects the EMULATOR — a machine-wide singleton — so the lock file
# must be machine-wide too. It used to be derived from `$root_dir`, i.e. the
# *worktree* root, which meant every checkout got its OWN lock file. `flock` on
# distinct files serialises NOTHING: two agents running connected-test.sh from
# `.worktrees/issue-A` and `.worktrees/issue-B` held different locks and ran on
# the one emulator concurrently. `process.md` has told agents since #672 that
# this flock "is what makes parallel agents safe"; it never did.
#
# Anchor choice — a fixed per-user path under $HOME, NOT the checkout:
#   * `$root_dir` / `$PWD`: the bug. Different per worktree, and the resource
#     being protected has nothing to do with which checkout is driving it.
#   * `git rev-parse --git-common-dir`: shared across `git worktree`s of ONE
#     clone, but the `.claude/worktrees/agent-*` runners are full-repo copies
#     and the maintainer may have a second clone — those would still collide.
#     Wrong axis: the emulator is a property of the machine, not of the clone.
#   * `$XDG_RUNTIME_DIR`: correct lifetime, but it is env-dependent — a process
#     started without it (cron, a container, a bare CI shell) would silently
#     fall back to a different directory and reintroduce the exact split-lock
#     bug this fixes. Rejected for that silent-divergence risk.
#   * `$HOME/.cache/pocketshell/avd-locks` (chosen): identical for every process
#     the same user runs on the box regardless of cwd, env, or systemd unit —
#     including `systemd-run --user` units, which do not get a private /tmp but
#     could in principle, so /tmp is only the fallback for a HOME-less shell.
#
# Stale locks are a non-issue by construction: `flock` is held on an open FD, so
# the kernel drops it the instant the holder dies (SIGKILL, OOM, harness
# timeout, stray pkill). The lock FILE persisting is harmless — it carries no
# state. Never replace this with a pidfile/lock-content scheme, which WOULD
# wedge on a crash. Pinned by `crashed_holder_does_not_wedge_the_lock` in
# tests/scripts/avd-lock-sharing-test.sh.
#
# `POCKETSHELL_AVD_LOCK_DIR` overrides the directory (tests sandbox it; an
# operator can point it elsewhere). `POCKETSHELL_AVD_LOCK_FILE` still overrides
# an individual lock file outright — that is the shard opt-out below.
# ---------------------------------------------------------------------------
pocketshell_avd_lock_dir() {
  if [[ -n "${POCKETSHELL_AVD_LOCK_DIR:-}" ]]; then
    printf '%s\n' "${POCKETSHELL_AVD_LOCK_DIR%/}"
    return 0
  fi
  if [[ -n "${HOME:-}" && -d "$HOME" && -w "$HOME" ]]; then
    printf '%s/.cache/pocketshell/avd-locks\n' "$HOME"
    return 0
  fi
  # HOME-less/read-only-HOME shell: still machine-wide, still per-user.
  printf '/tmp/pocketshell-avd-locks-%s\n' "$(id -u)"
}

# Resolve (and create) a machine-wide lock path by basename.
pocketshell_avd_lock_path() {
  local name="$1"
  local dir
  dir="$(pocketshell_avd_lock_dir)"
  mkdir -p "$dir" 2>/dev/null || true
  printf '%s/%s\n' "$dir" "$name"
}

# Derive a per-serial AVD lock file path so that independent shards and #724
# pool lanes, each targeting a *distinct* emulator (`ANDROID_SERIAL`), can hold
# their own lock concurrently instead of serialising on the single global
# `avd-lock`. That concurrency is deliberate and load-bearing: collapsing it
# into one global lock would queue every parallel journey lane behind every
# other. Pinned by `distinct_serials_still_run_concurrently_across_worktrees`.
#
# Conversely, the SAME serial from two different worktrees now resolves to the
# SAME file, so the pool's "never co-locate two lanes on one emulator" promise
# (P4 below) finally holds across checkouts.
#
# `$root_dir` is accepted for call-site compatibility and deliberately unused:
# the lock is anchored to the machine, not the checkout (issue #1657).
#
# Usage: lock_path="$(pocketshell_avd_lock_file_for_serial "$root_dir" "$serial")"
pocketshell_avd_lock_file_for_serial() {
  local _root_dir_unused="$1"
  local serial="$2"
  # Sanitise the serial into a filename-safe token (e.g. emulator-5556 ->
  # emulator-5556; host:port style serials lose the colon).
  local token="${serial//[^A-Za-z0-9._-]/_}"
  if [[ -z "$token" ]]; then
    token="default"
  fi
  pocketshell_avd_lock_path "avd-lock-$token"
}

# connected-test.sh needs a stronger lifetime than the general gate helper:
# the WRAPPER retains this flock FD continuously while each device-mutating
# child gets an explicitly closed copy. A small sentinel remains so unexpected
# ownership-helper death is observable, but killing it cannot release the real
# lock out from under the wrapper (issue #1737 review correction).
_pocketshell_start_continuous_avd_lock() {
  local lock_file="$1"
  local acquire_mode="${2:-blocking}"
  mkdir -p "$(dirname "$lock_file")"

  exec {POCKETSHELL_AVD_LOCK_FD}>"$lock_file"
  if [[ "$acquire_mode" == "nonblocking" ]]; then
    if ! flock -n "$POCKETSHELL_AVD_LOCK_FD"; then
      exec {POCKETSHELL_AVD_LOCK_FD}>&-
      unset POCKETSHELL_AVD_LOCK_FD
      return 1
    fi
  elif ! flock "$POCKETSHELL_AVD_LOCK_FD"; then
    exec {POCKETSHELL_AVD_LOCK_FD}>&-
    unset POCKETSHELL_AVD_LOCK_FD
    return 1
  fi

  (
    # The sentinel is deliberately NOT another flock owner. The calling
    # wrapper's FD above is the single continuous authority. The close
    # redirection is attached to this background process creation below, so
    # there is no post-fork interval in which the sentinel owns that FD.
    exec >/dev/null 2>/dev/null
    local sleep_pid=""
    trap '[[ -n "$sleep_pid" ]] && kill "$sleep_pid" 2>/dev/null || true; exit 0' HUP INT TERM
    while :; do
      sleep 3600 &
      sleep_pid="$!"
      wait "$sleep_pid" || true
    done
  ) {POCKETSHELL_AVD_LOCK_FD}>&- &

  export POCKETSHELL_AVD_LOCK_HOLDER_PID="$!"
  export POCKETSHELL_AVD_LOCK_OWNER_PID="$$"
  export POCKETSHELL_AVD_LOCK_ACQUIRED=1
  export POCKETSHELL_AVD_LOCK_CONTINUOUS_ACQUIRED=1
  export POCKETSHELL_AVD_LOCK_FILE="$lock_file"
  trap pocketshell_release_all EXIT
}

pocketshell_acquire_avd_lock() {
  # $1 (root_dir) is accepted for call-site compatibility and unused: the lock
  # path is machine-wide, not checkout-relative (issue #1657).
  local _root_dir_unused="$1"
  local help_arg="${2:-}"

  if [[ "$help_arg" == "--help" || "$help_arg" == "-h" ]]; then
    return 0
  fi

  if [[ -n "${POCKETSHELL_AVD_LOCK_ACQUIRED:-}" ]]; then
    return 0
  fi

  # The default is ONE machine-wide lock shared by every checkout on the box
  # (issue #1657). A caller that has pinned a specific emulator (a shard, a
  # #724 pool lane) exports POCKETSHELL_AVD_LOCK_FILE to opt into that
  # emulator's own lock instead, and keeps its concurrency.
  local lock_file="${POCKETSHELL_AVD_LOCK_FILE:-$(pocketshell_avd_lock_path avd-lock)}"
  mkdir -p "$(dirname "$lock_file")"

  # P6 (issue #776): a non-blocking probe purely to TELL the operator we are
  # about to block. If it succeeds the lock is free and we proceed instantly
  # (no log). If it fails the lock is genuinely held, so we log once that the
  # real blocking acquire below will queue — this is a queue, not a wedge. The
  # subshell holder below does the authoritative blocking flock; the "Acquired
  # AVD lock" line that follows is the proof we got through.
  if ! ( flock -n 9 ) 9>"$lock_file"; then
    echo "AVD lock ($lock_file) held by another emulator-touching run; queuing for it..." >&2
  fi

  if [[ "${POCKETSHELL_AVD_LOCK_CONTINUOUS:-}" == "1" ]]; then
    if ! _pocketshell_start_continuous_avd_lock "$lock_file" blocking; then
      echo "failed to acquire continuous AVD lock: $lock_file" >&2
      return 1
    fi
    echo "Acquired continuous AVD lock: $lock_file" >&2
    return 0
  fi

  local state_dir
  state_dir="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-avd-lock.XXXXXX")"
  local ready_file="$state_dir/ready"
  local error_file="$state_dir/error"

  (
    # The holder is deliberately a separate process so arbitrary child
    # commands do not inherit the flock FD (tests/scripts/avd-lock-test.sh).
    if ! exec 9>"$lock_file"; then
      printf 'failed to open lock file: %s\n' "$lock_file" > "$error_file"
      exit 1
    fi
    if ! flock 9; then
      printf 'failed to acquire lock file: %s\n' "$lock_file" > "$error_file"
      exit 1
    fi

    printf 'ready\n' > "$ready_file"
    local sleep_pid=""
    trap '[[ -n "$sleep_pid" ]] && kill "$sleep_pid" 2>/dev/null || true; exit 0' HUP INT TERM
    while :; do
      sleep 3600 9>&- &
      sleep_pid="$!"
      wait "$sleep_pid" || true
    done
  ) &

  local holder_pid="$!"
  while [[ ! -e "$ready_file" ]]; do
    if ! kill -0 "$holder_pid" 2>/dev/null; then
      local error_message="failed to acquire AVD lock: $lock_file"
      if [[ -s "$error_file" ]]; then
        error_message="$(<"$error_file")"
      fi
      rm -rf "$state_dir"
      echo "$error_message" >&2
      wait "$holder_pid" 2>/dev/null || true
      return 1
    fi
    sleep 0.05
  done

  rm -rf "$state_dir"
  export POCKETSHELL_AVD_LOCK_ACQUIRED=1
  export POCKETSHELL_AVD_LOCK_FILE="$lock_file"
  export POCKETSHELL_AVD_LOCK_HOLDER_PID="$holder_pid"
  export POCKETSHELL_AVD_LOCK_OWNER_PID="$$"
  echo "Acquired AVD lock: $lock_file" >&2
  trap pocketshell_release_all EXIT
}

# Verify that this process still owns the exact lock selected for its serial.
# A boolean environment marker is not proof: a killed holder, inherited stale
# state, or a later lock-path overwrite could otherwise let package cleanup or
# Gradle mutate an emulator after ownership was lost (issue #1737).
pocketshell_assert_avd_lock_owned() {
  local expected_lock_file="${1:-${POCKETSHELL_AVD_LOCK_FILE:-}}"
  if [[ -z "$expected_lock_file" || "${POCKETSHELL_AVD_LOCK_FILE:-}" != "$expected_lock_file" ]]; then
    echo "FAIL: emulator ownership lock path is missing or changed (expected: ${expected_lock_file:-unset}; actual: ${POCKETSHELL_AVD_LOCK_FILE:-unset}). Refusing device mutation." >&2
    return 1
  fi

  local holder_pid=""
  if [[ -n "${POCKETSHELL_POOL_HOLDER_PID:-}" ]]; then
    holder_pid="${POCKETSHELL_POOL_HOLDER_PID:-}"
  elif [[ -n "${POCKETSHELL_AVD_LOCK_HOLDER_PID:-}" ]]; then
    holder_pid="${POCKETSHELL_AVD_LOCK_HOLDER_PID:-}"
  fi
  if [[ -z "$holder_pid" || ! "$holder_pid" =~ ^[0-9]+$ ]] \
      || ! kill -0 "$holder_pid" 2>/dev/null; then
    echo "FAIL: lost emulator ownership for ${ANDROID_SERIAL:-unknown serial} ($expected_lock_file); lock holder is gone. Refusing device mutation." >&2
    return 1
  fi

  if [[ "${POCKETSHELL_AVD_LOCK_CONTINUOUS_ACQUIRED:-}" == "1" ]]; then
    local lock_fd="${POCKETSHELL_AVD_LOCK_FD:-}"
    if [[ ! "$lock_fd" =~ ^[0-9]+$ || ! -e "/proc/$BASHPID/fd/$lock_fd" ]]; then
      echo "FAIL: lost emulator ownership for ${ANDROID_SERIAL:-unknown serial} ($expected_lock_file); wrapper flock FD is gone. Refusing device mutation." >&2
      return 1
    fi
    if [[ ! "/proc/$BASHPID/fd/$lock_fd" -ef "$expected_lock_file" ]]; then
      echo "FAIL: lost emulator ownership for ${ANDROID_SERIAL:-unknown serial} ($expected_lock_file); wrapper flock FD targets another file. Refusing device mutation." >&2
      return 1
    fi
  fi

  # The live holder must actually own the flock. If a stale PID happens to be
  # reused but the lock is free, this non-blocking probe catches it. In
  # continuous mode the probe process closes the wrapper FD in its creation
  # redirection, so even a wrapper SIGKILL during this check cannot leak it.
  local lock_was_free=1
  if [[ "${POCKETSHELL_AVD_LOCK_CONTINUOUS_ACQUIRED:-}" == "1" ]]; then
    ( flock -n 9 ) {POCKETSHELL_AVD_LOCK_FD}>&- 9>"$expected_lock_file" \
      && lock_was_free=0
  elif ( flock -n 9 ) 9>"$expected_lock_file"; then
    lock_was_free=0
  fi
  if (( lock_was_free == 0 )); then
    echo "FAIL: lost emulator ownership for ${ANDROID_SERIAL:-unknown serial} ($expected_lock_file); lock is no longer held. Refusing device mutation." >&2
    return 1
  fi
}

# Run a child without leaking the wrapper-owned continuous flock FD into it.
# The wrapper keeps its own descriptor for the complete mutation window.
pocketshell_run_without_avd_lock_fd() {
  if [[ "${POCKETSHELL_AVD_LOCK_CONTINUOUS_ACQUIRED:-}" == "1" \
        && "${POCKETSHELL_AVD_LOCK_FD:-}" =~ ^[0-9]+$ ]]; then
    "$@" {POCKETSHELL_AVD_LOCK_FD}>&-
  else
    "$@"
  fi
}

# Start an asynchronous child with the continuous flock FD closed by the
# background-command redirection itself. Do NOT background
# pocketshell_run_without_avd_lock_fd: Bash would first fork an intermediate
# async function shell carrying the FD, then close it only in a grandchild.
# This shape closes the descriptor at process creation, before either a shell
# function body or its eventual external child can run (issue #1737 round 2).
pocketshell_start_without_avd_lock_fd() {
  if [[ "${POCKETSHELL_AVD_LOCK_CONTINUOUS_ACQUIRED:-}" == "1" \
        && "${POCKETSHELL_AVD_LOCK_FD:-}" =~ ^[0-9]+$ ]]; then
    "$@" {POCKETSHELL_AVD_LOCK_FD}>&- &
  else
    "$@" &
  fi
  # Consumed by the sourcing wrapper.
  # shellcheck disable=SC2034
  POCKETSHELL_AVD_CHILD_PID="$!"
}

# Combined EXIT handler so the single-lock release, the pool-claim release, and
# the agents-fixture-port release never clobber each other's trap. Every acquire
# path registers THIS handler; each inner release is a no-op when its own state
# is absent. The agents-port release (issue #724) is invoked defensively only
# when that helper has been sourced into the shell.
pocketshell_release_all() {
  pocketshell_release_pool_serial
  if declare -F pocketshell_release_agents_port >/dev/null 2>&1; then
    pocketshell_release_agents_port
  fi
  pocketshell_release_toxiproxy_lock
  pocketshell_release_avd_lock
}

# ---------------------------------------------------------------------------
# Toxiproxy serialization lock (issue #776 P3)
#
# The network-fault tests (NetworkFaultProofBase) all drive ONE global toxiproxy
# (hardcoded 10.0.2.2:2228 / API 8474, single shared proxy, @After reset()).
# --pool does NOT isolate that singleton, so two network-fault lanes corrupt
# each other's toxics. Until per-lane toxiproxy plumbing lands, serialize every
# network-fault lane on ONE shared, machine-wide flock so at most one touches the
# proxy at a time — even across distinct pool emulators. This is SEPARATE from
# the per-serial AVD lock (two lanes on different emulators each hold their own
# serial lock but must still NOT share the proxy). Released by
# pocketshell_release_all on exit.
#
# Issue #1657: this lock carried the SAME defect as the AVD lock — it promised
# "ONE shared, machine-wide flock" while living under `$root_dir/build/`, so two
# worktrees got two different files and two network-fault lanes could still
# corrupt each other's toxics. The proxy is ONE global singleton, so it takes the
# machine-wide anchor with NO per-serial token: every lane shares this one file.
_pocketshell_hold_toxiproxy_lock() {
  local lock_file="$1"
  local ready_file="$2"
  exec >/dev/null 2>/dev/null
  if ! exec 9>"$lock_file"; then
    exit 1
  fi
  if ! flock 9; then
    exit 1
  fi
  printf 'ready\n' > "$ready_file"
  local sleep_pid=""
  trap '[[ -n "$sleep_pid" ]] && kill "$sleep_pid" 2>/dev/null || true; exit 0' HUP INT TERM
  while :; do
    sleep 3600 9>&- &
    sleep_pid="$!"
    wait "$sleep_pid" || true
  done
}

pocketshell_acquire_toxiproxy_lock() {
  local _root_dir_unused="$1"
  if [[ -n "${POCKETSHELL_TOXIPROXY_LOCK_OWNER_PID:-}" ]]; then
    return 0
  fi
  local lock_file
  lock_file="$(pocketshell_avd_lock_path toxiproxy-serial-lock)"
  mkdir -p "$(dirname "$lock_file")"

  local state_dir
  state_dir="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-toxiproxy-lock.XXXXXX")"
  local ready_file="$state_dir/ready"

  if ! ( flock -n 9 ) 9>"$lock_file"; then
    echo "Toxiproxy serialization lock held by another network-fault lane; queuing..." >&2
  fi

  if [[ "${POCKETSHELL_AVD_LOCK_CONTINUOUS_ACQUIRED:-}" == "1" \
        && "${POCKETSHELL_AVD_LOCK_FD:-}" =~ ^[0-9]+$ ]]; then
    _pocketshell_hold_toxiproxy_lock "$lock_file" "$ready_file" \
      {POCKETSHELL_AVD_LOCK_FD}>&- &
  else
    _pocketshell_hold_toxiproxy_lock "$lock_file" "$ready_file" &
  fi
  local holder_pid="$!"

  while [[ ! -e "$ready_file" ]]; do
    if ! kill -0 "$holder_pid" 2>/dev/null; then
      rm -rf "$state_dir"
      wait "$holder_pid" 2>/dev/null || true
      echo "FAIL: could not acquire toxiproxy serialization lock: $lock_file" >&2
      return 1
    fi
    sleep 0.05
  done

  rm -rf "$state_dir"
  export POCKETSHELL_TOXIPROXY_LOCK_HOLDER_PID="$holder_pid"
  export POCKETSHELL_TOXIPROXY_LOCK_OWNER_PID="$$"
  echo "Acquired toxiproxy serialization lock: $lock_file" >&2
  trap pocketshell_release_all EXIT
}

pocketshell_release_toxiproxy_lock() {
  if [[ "${POCKETSHELL_TOXIPROXY_LOCK_OWNER_PID:-}" != "$$" ]]; then
    return 0
  fi
  local holder_pid="${POCKETSHELL_TOXIPROXY_LOCK_HOLDER_PID:-}"
  if [[ -n "$holder_pid" ]]; then
    kill "$holder_pid" 2>/dev/null || true
    wait "$holder_pid" 2>/dev/null || true
  fi
  unset POCKETSHELL_TOXIPROXY_LOCK_HOLDER_PID
  unset POCKETSHELL_TOXIPROXY_LOCK_OWNER_PID
}

pocketshell_release_avd_lock() {
  if [[ "${POCKETSHELL_AVD_LOCK_OWNER_PID:-}" != "$$" ]]; then
    return 0
  fi

  local holder_pid="${POCKETSHELL_AVD_LOCK_HOLDER_PID:-}"

  if [[ -n "$holder_pid" ]]; then
    kill "$holder_pid" 2>/dev/null || true
    wait "$holder_pid" 2>/dev/null || true
  fi
  if [[ "${POCKETSHELL_AVD_LOCK_CONTINUOUS_ACQUIRED:-}" == "1" \
        && "${POCKETSHELL_AVD_LOCK_FD:-}" =~ ^[0-9]+$ ]]; then
    flock -u "$POCKETSHELL_AVD_LOCK_FD" 2>/dev/null || true
    exec {POCKETSHELL_AVD_LOCK_FD}>&-
    unset POCKETSHELL_AVD_LOCK_FD
  fi
  unset POCKETSHELL_AVD_LOCK_HOLDER_PID
  unset POCKETSHELL_AVD_LOCK_OWNER_PID
  unset POCKETSHELL_AVD_LOCK_CONTINUOUS_ACQUIRED
  unset POCKETSHELL_AVD_LOCK_ACQUIRED
}

# ---------------------------------------------------------------------------
# Pool claim/release (issue #674)
#
# A pool agent wants to run on whatever emulator is currently free, not block
# on a single serial. `pocketshell_claim_pool_serial` scans the candidate
# serials (the live `adb devices` that look like emulators, intersected with
# any pool whitelist), non-blockingly try-locks each one's *per-serial* lock
# file, and claims the first it wins. The claim is held by a background holder
# process for the lifetime of the caller (same mechanism as the single lock),
# and `ANDROID_SERIAL` is exported so AGP / adb pin to that emulator.
#
# This is layered ON TOP of pocketshell_acquire_avd_lock: claiming the serial
# sets POCKETSHELL_AVD_LOCK_FILE to that serial's lock and then acquires it,
# so a subsequent connected-test run on the same serial still serialises
# politely against another agent that happened to claim the same one.
# ---------------------------------------------------------------------------

# Print emulator serials currently visible to adb, one per line, that are in
# the `device` state. ADB defaults to $ANDROID_SDK/platform-tools/adb but the
# caller can override ADB.
pocketshell_pool_online_serials() {
  local adb="${ADB:-${ANDROID_SDK:-/home/alexey/Android/Sdk}/platform-tools/adb}"
  "$adb" devices 2>/dev/null \
    | awk 'NR > 1 && $2 == "device" && $1 ~ /^emulator-/ { print $1 }'
}

# Try to acquire a *non-blocking* flock on a serial's per-serial lock file.
# Echoes the holder PID on success (caller must keep it to release later);
# returns non-zero if the lock is already held by a sibling.
#
# Internal helper for pocketshell_claim_pool_serial.
_pocketshell_pool_try_lock_serial() {
  local root_dir="$1"
  local serial="$2"
  local lock_file
  lock_file="$(pocketshell_avd_lock_file_for_serial "$root_dir" "$serial")"
  mkdir -p "$(dirname "$lock_file")"

  local state_dir
  state_dir="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-pool-claim.XXXXXX")"
  local ready_file="$state_dir/ready"

  # The holder runs forever to keep the flock held. It MUST NOT inherit the
  # stdout that a caller's command substitution ($(pocketshell_claim_pool_serial))
  # is reading, or `$(...)` would block until this process exits (it never
  # does). Redirect its stdio to /dev/null; it communicates only via the lock
  # FD and the ready file.
  (
    exec >/dev/null 2>/dev/null
    if ! exec 9>"$lock_file"; then
      exit 1
    fi
    # Non-blocking: if a sibling holds this serial, fail immediately so the
    # claimer moves on to the next candidate.
    if ! flock -n 9; then
      exit 1
    fi
    printf 'ready\n' > "$ready_file"
    sleep_pid=""
    trap '[[ -n "$sleep_pid" ]] && kill "$sleep_pid" 2>/dev/null || true; exit 0' HUP INT TERM
    while :; do
      sleep 3600 9>&- &
      sleep_pid="$!"
      wait "$sleep_pid" || true
    done
  ) &
  local holder_pid="$!"

  # Wait briefly for the child to either signal ready or exit (lock contended).
  local waited=0
  while [[ ! -e "$ready_file" ]]; do
    if ! kill -0 "$holder_pid" 2>/dev/null; then
      rm -rf "$state_dir"
      wait "$holder_pid" 2>/dev/null || true
      return 1
    fi
    sleep 0.05
    waited=$((waited + 1))
    if (( waited > 100 )); then  # ~5s safety
      kill "$holder_pid" 2>/dev/null || true
      wait "$holder_pid" 2>/dev/null || true
      rm -rf "$state_dir"
      return 1
    fi
  done

  rm -rf "$state_dir"
  printf '%s\n' "$holder_pid"
  return 0
}

# Claim the first free emulator serial from the live pool. On success:
#   * exports ANDROID_SERIAL=<claimed serial>
#   * exports POCKETSHELL_POOL_HOLDER_PID / POCKETSHELL_POOL_OWNER_PID
#   * installs an EXIT trap to release the claim
#   * echoes the claimed serial on stdout
# Returns non-zero if no serial could be claimed (all busy / none online).
#
# Optional env:
#   POCKETSHELL_POOL_SERIALS  whitespace-separated whitelist; if set, only
#                             these serials are considered (intersected with
#                             the online set).
#   POCKETSHELL_POOL_WAIT_SECONDS  how long to keep retrying when all are busy
#                             (default 600). Set 0 to try once (fail-fast: the
#                             lane errors immediately instead of blocking on the
#                             flock for minutes when the pool is over-subscribed,
#                             issue #776 P4).
pocketshell_claim_pool_serial() {
  local root_dir="$1"
  local wait_seconds="${POCKETSHELL_POOL_WAIT_SECONDS:-600}"
  local deadline=$((SECONDS + wait_seconds))
  local started=$SECONDS

  while :; do
    local candidates=()
    local online
    online="$(pocketshell_pool_online_serials)"

    if [[ -n "${POCKETSHELL_POOL_SERIALS:-}" ]]; then
      # Intersect the whitelist with the online set, preserving whitelist order.
      local want
      for want in $POCKETSHELL_POOL_SERIALS; do
        if grep -Fxq "$want" <<< "$online"; then
          candidates+=("$want")
        fi
      done
    else
      local s
      while IFS= read -r s; do
        [[ -n "$s" ]] && candidates+=("$s")
      done <<< "$online"
    fi

    local serial
    for serial in "${candidates[@]}"; do
      local holder_pid
      if [[ "${POCKETSHELL_AVD_LOCK_CONTINUOUS:-}" == "1" ]]; then
        local lock_file
        lock_file="$(pocketshell_avd_lock_file_for_serial "$root_dir" "$serial")"
        if ! _pocketshell_start_continuous_avd_lock "$lock_file" nonblocking; then
          continue
        fi
        holder_pid="$POCKETSHELL_AVD_LOCK_HOLDER_PID"
      elif ! holder_pid="$(_pocketshell_pool_try_lock_serial "$root_dir" "$serial")"; then
        continue
      fi
      if [[ -n "$holder_pid" ]]; then
        export ANDROID_SERIAL="$serial"
        export POCKETSHELL_POOL_HOLDER_PID="$holder_pid"
        export POCKETSHELL_POOL_OWNER_PID="$$"
        export POCKETSHELL_POOL_SERIAL="$serial"
        # The pool claim IS the per-serial AVD lock for this serial: it already
        # holds an exclusive flock on the same per-serial lock file that
        # pocketshell_acquire_avd_lock would use. Mark the AVD lock as already
        # acquired so a subsequent pocketshell_acquire_avd_lock early-returns
        # instead of dead-locking by trying to re-grab the lock WE hold.
        export POCKETSHELL_AVD_LOCK_ACQUIRED=1
        if [[ "${POCKETSHELL_AVD_LOCK_CONTINUOUS:-}" != "1" ]]; then
          POCKETSHELL_AVD_LOCK_FILE="$(pocketshell_avd_lock_file_for_serial "$root_dir" "$serial")"
        fi
        export POCKETSHELL_AVD_LOCK_FILE
        trap pocketshell_release_all EXIT
        echo "Claimed pool emulator: $serial" >&2
        printf '%s\n' "$serial"
        return 0
      fi
    done

    # P4 (issue #776): NEVER co-locate. We only ever claim a serial whose
    # per-serial flock is FREE; a busy emulator (a sibling lane mid-run) is
    # skipped, not piled onto. That bounds instrumentation runs per emulator to
    # 1 and starves the #743/#470 render-timeout family of its trigger. If every
    # candidate is busy we WAIT for one to free (or fail-fast when
    # POCKETSHELL_POOL_WAIT_SECONDS=0) — we do not fall back to sharing.
    if (( SECONDS >= deadline )); then
      echo "FAIL: no free emulator in the pool after ${wait_seconds}s (online/candidates: ${candidates[*]:-none}; all busy with sibling lanes). Boot more pool emulators (scripts/avd-pool.sh start) or reduce concurrent lanes to <= pool size." >&2
      return 1
    fi
    # Surface progress so an over-subscribed pool looks like a queue, not a
    # silent wedge (the misdiagnosed "lanes stalled for hours" symptom). Log the
    # candidate count + elapsed/total wait every cycle.
    echo "All ${#candidates[@]} pool emulator(s) busy; waiting for a free one ($((SECONDS - started))s/${wait_seconds}s elapsed)..." >&2
    sleep 3
  done
}

pocketshell_release_pool_serial() {
  if [[ "${POCKETSHELL_POOL_OWNER_PID:-}" != "$$" ]]; then
    return 0
  fi
  local holder_pid="${POCKETSHELL_POOL_HOLDER_PID:-}"
  if [[ -n "$holder_pid" && "${POCKETSHELL_AVD_LOCK_CONTINUOUS_ACQUIRED:-}" != "1" ]]; then
    kill "$holder_pid" 2>/dev/null || true
    wait "$holder_pid" 2>/dev/null || true
  fi
  unset POCKETSHELL_POOL_HOLDER_PID
  unset POCKETSHELL_POOL_OWNER_PID
  unset POCKETSHELL_POOL_SERIAL
  # The pool claim stood in for the per-serial AVD lock; clear that marker so a
  # later acquire in the same shell can take a fresh lock if needed.
  if [[ "${POCKETSHELL_AVD_LOCK_CONTINUOUS_ACQUIRED:-}" != "1" ]]; then
    unset POCKETSHELL_AVD_LOCK_ACQUIRED
  fi
}
