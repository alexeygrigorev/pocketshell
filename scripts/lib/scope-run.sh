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
# GRACEFUL DEGRADE
# ----------------
# When `systemd-run` / a working user systemd is unavailable (e.g. CI), the
# command is run BARE — byte-for-byte the legacy behaviour — and a one-line
# warning is emitted. Mirrors robust.systemd_available().
#
# USAGE
# -----
#   source scripts/lib/scope-run.sh
#   pocketshell_scope_run <unit> <cmd...>
#
# The memory caps are env-tunable (so CI/dev can size them):
#   POCKETSHELL_TEST_MEM   hard MemoryMax for the scope         (default 8G)
#   POCKETSHELL_TEST_HIGH  soft MemoryHigh throttle threshold   (default ~85% of MEM)
#   POCKETSHELL_TEST_SWAP  MemorySwapMax cushion                (default 8G)
#   POCKETSHELL_SCOPE_SLICE parent slice                        (default robust.slice)
#
# The <unit> should be UNIQUE per invocation (include suffix/serial/pid) so
# parallel lanes get distinct sibling scopes that never collide.

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
# sibling scope when user systemd is available; otherwise runs <cmd...> bare.
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
    printf 'scope-run: user systemd unavailable; running %s BARE (uncapped fallback)\n' \
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
