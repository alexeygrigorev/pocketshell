#!/usr/bin/env bash

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
  trap pocketshell_release_avd_lock EXIT
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
