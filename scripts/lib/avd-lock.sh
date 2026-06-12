#!/usr/bin/env bash

# Derive a per-serial AVD lock file path so that independent shards, each
# targeting a *distinct* emulator (`ANDROID_SERIAL`), can hold their own lock
# concurrently instead of serialising on the single global `build/.avd-lock`.
#
# Callers that don't opt in keep the single-lock default: nothing here changes
# the behaviour of `pocketshell_acquire_avd_lock` unless the caller explicitly
# exports `POCKETSHELL_AVD_LOCK_FILE` (which a shard does, via this helper).
#
# Usage: lock_path="$(pocketshell_avd_lock_file_for_serial "$root_dir" "$serial")"
pocketshell_avd_lock_file_for_serial() {
  local root_dir="$1"
  local serial="$2"
  # Sanitise the serial into a filename-safe token (e.g. emulator-5556 ->
  # emulator-5556; host:port style serials lose the colon).
  local token="${serial//[^A-Za-z0-9._-]/_}"
  if [[ -z "$token" ]]; then
    token="default"
  fi
  printf '%s/build/.avd-lock-%s\n' "$root_dir" "$token"
}

pocketshell_acquire_avd_lock() {
  local root_dir="$1"
  local help_arg="${2:-}"

  if [[ "$help_arg" == "--help" || "$help_arg" == "-h" ]]; then
    return 0
  fi

  if [[ -n "${POCKETSHELL_AVD_LOCK_ACQUIRED:-}" ]]; then
    return 0
  fi

  local lock_file="${POCKETSHELL_AVD_LOCK_FILE:-$root_dir/build/.avd-lock}"
  mkdir -p "$(dirname "$lock_file")"

  if ! ( flock -n 9 ) 9>"$lock_file"; then
    echo "Another emulator-touching script holds the AVD lock ($lock_file); waiting..." >&2
  fi

  local state_dir
  state_dir="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-avd-lock.XXXXXX")"
  local ready_file="$state_dir/ready"
  local error_file="$state_dir/error"

  (
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
  pocketshell_release_avd_lock
}

pocketshell_release_avd_lock() {
  if [[ "${POCKETSHELL_AVD_LOCK_OWNER_PID:-}" != "$$" ]]; then
    return 0
  fi

  local holder_pid="${POCKETSHELL_AVD_LOCK_HOLDER_PID:-}"
  if [[ -z "$holder_pid" ]]; then
    return 0
  fi

  kill "$holder_pid" 2>/dev/null || true
  wait "$holder_pid" 2>/dev/null || true
  unset POCKETSHELL_AVD_LOCK_HOLDER_PID
  unset POCKETSHELL_AVD_LOCK_OWNER_PID
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
#                             (default 600). Set 0 to try once.
pocketshell_claim_pool_serial() {
  local root_dir="$1"
  local wait_seconds="${POCKETSHELL_POOL_WAIT_SECONDS:-600}"
  local deadline=$((SECONDS + wait_seconds))

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
      if holder_pid="$(_pocketshell_pool_try_lock_serial "$root_dir" "$serial")"; then
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
        POCKETSHELL_AVD_LOCK_FILE="$(pocketshell_avd_lock_file_for_serial "$root_dir" "$serial")"
        export POCKETSHELL_AVD_LOCK_FILE
        trap pocketshell_release_all EXIT
        echo "Claimed pool emulator: $serial" >&2
        printf '%s\n' "$serial"
        return 0
      fi
    done

    if (( SECONDS >= deadline )); then
      echo "FAIL: no free emulator in the pool (online: ${candidates[*]:-none})" >&2
      return 1
    fi
    echo "All pool emulators busy; waiting for a free one..." >&2
    sleep 3
  done
}

pocketshell_release_pool_serial() {
  if [[ "${POCKETSHELL_POOL_OWNER_PID:-}" != "$$" ]]; then
    return 0
  fi
  local holder_pid="${POCKETSHELL_POOL_HOLDER_PID:-}"
  if [[ -n "$holder_pid" ]]; then
    kill "$holder_pid" 2>/dev/null || true
    wait "$holder_pid" 2>/dev/null || true
  fi
  unset POCKETSHELL_POOL_HOLDER_PID
  unset POCKETSHELL_POOL_OWNER_PID
  unset POCKETSHELL_POOL_SERIAL
  # The pool claim stood in for the per-serial AVD lock; clear that marker so a
  # later acquire in the same shell can take a fresh lock if needed.
  unset POCKETSHELL_AVD_LOCK_ACQUIRED
}
