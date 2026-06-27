#!/usr/bin/env bash

# Issue #730: run a heavy command inside its OWN transient systemd --user scope
# so its memory is accounted in a *sibling* cgroup under `robust.slice`, NOT in
# the calling tmux session's per-session cgroup (#726).
#
# WHY this exists
# ---------------
# `scripts/connected-test.sh` used to run `./gradlew :app:connectedDebugAndroidTest`
# DIRECTLY in the calling session's bash. Gradle/Kotlin/AAPT2 daemons + test JVMs
# (+ node-based agents in parallel) therefore all accrued against the ONE 12 GiB
# session cgroup, and a heavy parallel-agent moment OOM-killed the WHOLE session
# (`OOMPolicy=stop` then tore the session down). The per-session cap from #726
# contained the blast radius (sibling sessions survived) but the session itself
# still died.
#
# The fix: each heavy invocation runs in its own `systemd-run --user --scope`
# *sibling* scope under `robust.slice`. A runaway then OOMs only its own scope;
# the wrapper observes the non-zero exit, reports it, and the parent interactive
# session survives.
#
# COMPOSITION with the per-session cap (#726)
# -------------------------------------------
# `systemd-run --user --scope --unit=<u> -p Slice=robust.slice` creates a
# transient scope as a SIBLING of the session's `tmuxctl-<session>.scope`, both
# direct children of `robust.slice` — they are NOT nested. So this per-INVOCATION
# cap does not consume the session's per-SESSION budget: the gradle build's RSS
# lands in `pocketshell-test-*.scope`, not in `tmuxctl-<session>.scope`. The
# parent `robust.slice MemoryMax` (the box-wide ceiling) still bounds the sum of
# all sibling scopes, exactly as before. Mirrors tmuxctl/robust.py:scope_wrap.
#
# This mirrors tmuxctl/robust.py:scope_wrap (tmuxctl v0.3.2): MemoryHigh (soft
# throttle ~85% of MemoryMax) lets the scope reclaim + spill to swap BEFORE the
# hard MemoryMax wall, so heavy work slows/waits under transient pressure instead
# of dying outright; we additionally pin `OOMPolicy=stop` so that when the hard
# wall IS hit, only this scope's process tree is stopped.
#
# FAIL-CLOSED LOCAL DEFAULT
# -------------------------
# When `systemd-run` / a working user systemd is unavailable, local runs fail
# instead of silently running heavy work in the caller's cgroup. CI still falls
# back to bare execution because many hosted runners do not expose user systemd.
# A local caller may opt into the old uncapped fallback for cgroup debugging with
# POCKETSHELL_SCOPE_ALLOW_BARE=1.
#
# USAGE
# -----
#   source scripts/lib/scope-run.sh
#   pocketshell_scope_run <unit> <cmd...>
#   pocketshell_scope_start_background <unit> <log-file> <pid-file> <cmd...>
#
# The memory caps are env-tunable (so CI/dev can size them):
#   POCKETSHELL_TEST_MEM   hard MemoryMax for the scope         (default 8G)
#   POCKETSHELL_TEST_HIGH  soft MemoryHigh throttle threshold   (default ~85% of MEM)
#   POCKETSHELL_TEST_SWAP  MemorySwapMax cushion                (default 8G)
#   POCKETSHELL_SCOPE_SLICE parent slice                        (default robust.slice)
#   POCKETSHELL_SCOPE_ALLOW_BARE=1 allow uncapped fallback outside CI
#
# The <unit> should be UNIQUE per invocation (include suffix/serial/pid) so
# parallel lanes get distinct sibling scopes that never collide.

pocketshell_unit_token() {
  local token="${1//[^A-Za-z0-9._-]/_}"
  [[ -n "$token" ]] || token="default"
  printf '%s' "$token"
}

pocketshell_shell_join() {
  local arg quoted joined=""
  for arg in "$@"; do
    printf -v quoted '%q' "$arg"
    joined+=" $quoted"
  done
  printf '%s' "${joined# }"
}

pocketshell_should_wrap_sg_kvm() {
  case "${POCKETSHELL_EMULATOR_SG_KVM:-auto}" in
    0 | false | FALSE | no | NO)
      return 1
      ;;
    1 | true | TRUE | yes | YES)
      command -v sg >/dev/null 2>&1 || return 1
      getent group kvm >/dev/null 2>&1 || return 1
      return 0
      ;;
  esac

  [[ -e /dev/kvm ]] || return 1
  [[ -r /dev/kvm && -w /dev/kvm ]] && return 1
  command -v sg >/dev/null 2>&1 || return 1
  local group_entry user
  group_entry="$(getent group kvm 2>/dev/null || true)"
  [[ -n "$group_entry" ]] || return 1
  user="$(id -un 2>/dev/null || true)"
  [[ -n "$user" ]] || return 1
  if id -nG 2>/dev/null | tr ' ' '\n' | grep -Fxq kvm; then
    return 0
  fi
  [[ ",${group_entry##*:}," == *",$user,"* ]] || return 1
  return 0
}

pocketshell_build_sg_kvm_command() {
  local -n _pocketshell_out="$1"
  shift
  _pocketshell_out=("$@")
  if pocketshell_should_wrap_sg_kvm; then
    _pocketshell_out=(sg kvm -c "$(pocketshell_shell_join "$@")")
  fi
}

# robust.systemd_available() equivalent: systemd-run on PATH AND a reachable
# user systemd manager. `systemctl --user is-system-running` returns one of
# running/degraded/starting/... with exit 0/1 when the bus is reachable, and
# fails hard (non-zero, no output / "Failed to connect to bus") when there is no
# user manager (common in CI containers). "degraded" still means the manager is
# up and `systemd-run --user` works, so we accept any reachable state.
pocketshell_scope_available() {
  command -v systemd-run >/dev/null 2>&1 || return 1
  command -v systemctl >/dev/null 2>&1 || return 1
  # A reachable user manager prints a state word and exits 0..4; an UNreachable
  # bus prints nothing useful and we treat that as unavailable.
  local state
  state="$(systemctl --user is-system-running 2>/dev/null || true)"
  case "$state" in
    running | degraded | starting | maintenance | stopping | initializing)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

pocketshell_scope_allow_bare() {
  case "${POCKETSHELL_SCOPE_ALLOW_BARE:-}" in
    1 | true | TRUE | yes | YES)
      return 0
      ;;
  esac
  case "${CI:-}" in
    "" | 0 | false | FALSE | no | NO)
      return 1
      ;;
    *)
      return 0
      ;;
  esac
}

_pocketshell_scope_unavailable_message() {
  local unit="$1"
  printf 'scope-run: user systemd unavailable; refusing to run %s uncapped.\n' "$unit" >&2
  printf 'scope-run: rerun inside a working systemd --user session, or set POCKETSHELL_SCOPE_ALLOW_BARE=1 only when debugging cgroup setup. CI may fall back automatically.\n' >&2
}

# Compute ~85% of a systemd size string (e.g. 8G -> 6963543080 bytes) for the
# MemoryHigh default. Falls back to the raw mem value if it can't parse.
_pocketshell_scope_high_default() {
  local mem="$1"
  local num unit factor high
  # Split "8G" / "512M" / "1024" into number + unit.
  if [[ "$mem" =~ ^([0-9]+)([A-Za-z]*)$ ]]; then
    num="${BASH_REMATCH[1]}"
    unit="${BASH_REMATCH[2]}"
  else
    printf '%s' "$mem"
    return
  fi
  case "${unit^^}" in
    "" | B) factor=1 ;;
    K | KB | KIB) factor=1024 ;;
    M | MB | MIB) factor=$((1024 * 1024)) ;;
    G | GB | GIB) factor=$((1024 * 1024 * 1024)) ;;
    T | TB | TIB) factor=$((1024 * 1024 * 1024 * 1024)) ;;
    *)
      printf '%s' "$mem"
      return
      ;;
  esac
  # 85% of mem in bytes (integer math).
  high=$(( num * factor * 85 / 100 ))
  printf '%s' "$high"
}

# pocketshell_scope_run <unit> <cmd...>
#
# Runs <cmd...> inside a transient memory-capped `systemd-run --user --scope`
# sibling scope when user systemd is available; otherwise fails closed locally.
# Returns the command's own exit code. On an OOM-kill of the scope, systemd-run
# surfaces a non-zero exit and this function returns it with a clear message.
pocketshell_scope_run() {
  local unit="$1"
  shift
  if [[ -z "$unit" || $# -eq 0 ]]; then
    printf 'pocketshell_scope_run: need <unit> and a command\n' >&2
    return 2
  fi

  local mem="${POCKETSHELL_TEST_MEM:-8G}"
  local swap="${POCKETSHELL_TEST_SWAP:-8G}"
  local slice="${POCKETSHELL_SCOPE_SLICE:-robust.slice}"
  local high="${POCKETSHELL_TEST_HIGH:-}"
  if [[ -z "$high" ]]; then
    high="$(_pocketshell_scope_high_default "$mem")"
  fi

  if ! pocketshell_scope_available; then
    if ! pocketshell_scope_allow_bare; then
      _pocketshell_scope_unavailable_message "$unit"
      return 125
    fi
    printf 'scope-run: user systemd unavailable; running %s BARE (explicit uncapped fallback)\n' \
      "$unit" >&2
    "$@"
    return $?
  fi

  printf 'scope-run: running in scope %s.scope (MemoryHigh=%s MemoryMax=%s MemorySwapMax=%s Slice=%s OOMPolicy=stop)\n' \
    "$unit" "$high" "$mem" "$swap" "$slice" >&2

  # --scope: sibling transient scope (NOT nested in the session cgroup).
  # OOMPolicy=stop: on hitting the hard MemoryMax wall, stop ONLY this scope's
  # process tree (the parent session is untouched).
  # We deliberately do NOT pass --quiet so a scope-level OOM/stop is visible in
  # the wrapper's stderr, and deliberately do NOT pass --collect so a FAILED
  # scope LINGERS in `systemctl --user status <unit>.scope` (Result=oom-kill) for
  # post-mortem inspection. A successful run's transient unit is auto-cleaned;
  # a failed one we reset-failed below so it never piles up either.
  local rc=0
  systemd-run \
    --user \
    --scope \
    --unit="$unit" \
    -p "MemoryHigh=$high" \
    -p "MemoryMax=$mem" \
    -p "MemorySwapMax=$swap" \
    -p "OOMPolicy=stop" \
    -p "Slice=$slice" \
    -- "$@" || rc=$?

  if [[ "$rc" -ne 0 ]]; then
    local result
    result="$(systemctl --user show "$unit.scope" -p Result --value 2>/dev/null || true)"
    printf 'scope-run: command in scope %s.scope exited non-zero (rc=%s, Result=%s).\n' \
      "$unit" "$rc" "${result:-unknown}" >&2
    if [[ "$result" == "oom-kill" ]]; then
      printf 'scope-run: scope %s.scope hit its MemoryMax and was OOM-killed in ISOLATION; the parent session is unaffected. Inspect: systemctl --user status %s.scope\n' \
        "$unit" "$unit" >&2
    fi
    # Clear the lingering failed transient unit so it does not accumulate across
    # runs (the diagnostic line above already captured Result for the caller).
    systemctl --user reset-failed "$unit.scope" >/dev/null 2>&1 || true
  fi
  return "$rc"
}

# pocketshell_scope_start_background <unit> <log-file> <pid-file> <cmd...>
#
# Starts <cmd...> under the same memory-capped transient scope as
# pocketshell_scope_run, but returns immediately after writing the launcher PID
# to <pid-file>. This is for long-lived local reproduction processes such as
# the Android emulator. The scoped process tree still has its own sibling
# cgroup, so an OOM stops that scope rather than the interactive session.
pocketshell_scope_start_background() {
  local unit="$1"
  local log_file="$2"
  local pid_file="$3"
  shift 3
  if [[ -z "$unit" || -z "$log_file" || -z "$pid_file" || $# -eq 0 ]]; then
    printf 'pocketshell_scope_start_background: need <unit> <log-file> <pid-file> and a command\n' >&2
    return 2
  fi

  mkdir -p "$(dirname "$log_file")" "$(dirname "$pid_file")"

  if ! pocketshell_scope_available; then
    if ! pocketshell_scope_allow_bare; then
      _pocketshell_scope_unavailable_message "$unit"
      return 125
    fi
    printf 'scope-run: user systemd unavailable; starting %s BARE (explicit uncapped fallback)\n' \
      "$unit" >&2
    nohup "$@" >> "$log_file" 2>&1 &
    printf '%s\n' "$!" > "$pid_file"
    return 0
  fi

  (
    exec >> "$log_file" 2>&1
    pocketshell_scope_run "$unit" "$@"
  ) &
  printf '%s\n' "$!" > "$pid_file"
  printf 'scope-run: background launcher for %s.scope pid=%s log=%s\n' \
    "$unit" "$!" "$log_file" >&2
}
