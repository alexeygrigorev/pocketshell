#!/usr/bin/env bash
set -euo pipefail

# AVD pool launcher (issue #674).
#
# Boots N clones of the base `test` AVD as concurrent headless emulators so
# parallel agents run connected tests truly in parallel instead of queueing on
# a single AVD. Each clone boots on its own console port -> its own adb serial
# (emulator-5556, emulator-5558, ...), leaving the maintainer's / siblings'
# emulator-5554 untouched.
#
# Subcommands:
#   start    clone (if needed) + boot N pool emulators, wait for boot_completed
#   stop     tear down every pool emulator (does NOT touch emulator-5554)
#   status   list pool serials and their boot state
#   clone    just create the test-1..test-N clones (no boot)
#   purge    stop + delete the clone AVDs entirely
#
# Port scheme: clone test-K -> console port (5554 + 2K) -> serial
# emulator-<port>. K starts at 1, so the first pool emulator is emulator-5556.
# The base `test` AVD (emulator-5554) is deliberately NEVER in the pool, so a
# sibling agent already using emulator-5554 is never disturbed.
#
# KVM / hardware acceleration (issue #776):
#   x86_64 emulation REQUIRES /dev/kvm. On the Hetzner dev box a plain shell
#   often lacks ACTIVE kvm-group membership (the login session predates the
#   group add), so a bare `emulator -avd ...` dies with "x86_64 emulation
#   currently requires hardware acceleration" and the pool never boots — which
#   silently defeats every multi-emulator lane mitigation: online_emulator_count
#   stays 1, so connected-test's auto-pin / auto-pool branches never fire and
#   all lanes pile onto the one manually-started emulator (the foreground-steal
#   + sibling-SIGKILL flakes this issue is about). So we launch each pool
#   emulator via `sg kvm -c "..."` whenever the current shell can't read+write
#   /dev/kvm but the kvm group can. When /dev/kvm is already accessible (a CI
#   runner with active membership) OR absent (no KVM at all), we launch directly
#   — byte-for-byte the legacy behaviour. Override with POOL_KVM_WRAP.
#
# Config (env overrides):
#   POOL_SIZE=3              number of pool emulators
#   BASE_AVD=test            base AVD to clone
#   POOL_PORT_BASE=5554      port = POOL_PORT_BASE + 2*K
#   BOOT_TIMEOUT_SECONDS=300 per-emulator boot wait
#   POOL_KVM_WRAP=auto       sg|none|auto — wrap the emulator launch in
#                            `sg kvm -c` (auto = only when /dev/kvm is not
#                            directly accessible to this shell but the kvm group
#                            can reach it)
#   ANDROID_SDK / ADB / EMULATOR   tool paths
#   AVD_START_FLAGS          emulator launch flags

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

source "$ROOT_DIR/scripts/lib/avd-clone.sh"

ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
EMULATOR="${EMULATOR:-$ANDROID_SDK/emulator/emulator}"
BASE_AVD="${BASE_AVD:-test}"
POOL_SIZE="${POOL_SIZE:-3}"
POOL_PORT_BASE="${POOL_PORT_BASE:-5554}"
BOOT_TIMEOUT_SECONDS="${BOOT_TIMEOUT_SECONDS:-300}"
LOG_ROOT="${LOG_ROOT:-$ROOT_DIR/build/avd-pool}"
AVD_START_FLAGS="${AVD_START_FLAGS:--no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot}"
POOL_KVM_WRAP="${POOL_KVM_WRAP:-auto}"

mkdir -p "$LOG_ROOT"

# Decide whether the emulator launch must be wrapped in `sg kvm -c` to gain an
# ACTIVE kvm-group membership (issue #776). Returns 0 (wrap) / 1 (launch direct).
#   POOL_KVM_WRAP=sg    -> always wrap (force)
#   POOL_KVM_WRAP=none  -> never wrap  (force; CI / kvm-active shells)
#   POOL_KVM_WRAP=auto  -> wrap ONLY when this shell can't read+write /dev/kvm
#                          AND `sg kvm` can. If there's no /dev/kvm at all, or
#                          this shell already has access, don't wrap.
pool_needs_kvm_wrap() {
  case "$POOL_KVM_WRAP" in
    sg)   return 0 ;;
    none) return 1 ;;
  esac
  # auto: only meaningful when /dev/kvm exists.
  [[ -e /dev/kvm ]] || return 1
  # Already accessible to this shell -> no wrap needed.
  if [[ -r /dev/kvm && -w /dev/kvm ]]; then
    return 1
  fi
  # Not accessible here; can the kvm group reach it? (sg available + grants RW.)
  command -v sg >/dev/null 2>&1 || return 1
  if sg kvm -c 'test -r /dev/kvm && test -w /dev/kvm' >/dev/null 2>&1; then
    return 0
  fi
  return 1
}

# Launch the emulator, wrapping in `sg kvm -c` when pool_needs_kvm_wrap says so.
# The launched process is detached (nohup ... &) and its PID written by the
# caller. When wrapping, the `sg` process is the parent of the emulator; killing
# the process bound to the console port (emulator_pid_for_port) still tears the
# emulator down regardless, so teardown is unaffected.
pool_launch_emulator() {
  local clone_name="$1" port="$2" logf="$3"
  if pool_needs_kvm_wrap; then
    # shellcheck disable=SC2016  # backticks here are literal log text, not a subshell
    printf '  (launching via `sg kvm -c` for /dev/kvm access)\n' >&2
    # Build a single shell command string for sg -c. AVD_START_FLAGS is a
    # space-separated flag list (word-splitting is intentional, as in the
    # direct-launch path below).
    # shellcheck disable=SC2086
    nohup sg kvm -c "exec '$EMULATOR' -avd '$clone_name' -port '$port' $AVD_START_FLAGS" \
      > "$logf" 2>&1 &
  else
    # shellcheck disable=SC2086
    nohup "$EMULATOR" -avd "$clone_name" -port "$port" $AVD_START_FLAGS \
      > "$logf" 2>&1 &
  fi
  printf '%s\n' "$!"
}

usage() {
  cat <<'USAGE'
Usage: scripts/avd-pool.sh <start|stop|status|clone|purge>

Boots a pool of N concurrent emulators (clones of the base `test` AVD) so
parallel agents run connected tests without queueing on one AVD.

  start    create clones if missing, boot POOL_SIZE emulators, wait for boot
  stop     tear down every pool emulator (leaves emulator-5554 alone)
  status   list pool serials + boot state, plus load/RAM
  clone    create the test-1..test-N clone AVDs only (no boot)
  purge    stop + delete the clone AVDs from disk

Env: POOL_SIZE=3 BASE_AVD=test POOL_PORT_BASE=5554 BOOT_TIMEOUT_SECONDS=300
USAGE
}

pool_port_for_index() { printf '%s\n' "$((POOL_PORT_BASE + 2 * $1))"; }
pool_serial_for_index() { printf 'emulator-%s\n' "$(pool_port_for_index "$1")"; }
pool_clone_name_for_index() { pocketshell_avd_clone_name "$BASE_AVD" "$1"; }

boot_completed_for() {
  local serial="$1"
  local state
  state="$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  [[ "$state" == "1" ]]
}

serial_online() {
  local serial="$1"
  "$ADB" devices 2>/dev/null | awk -v s="$serial" 'NR>1 && $1==s && $2=="device" {f=1} END{exit f?0:1}'
}

# Find a running emulator process bound to a given console port.
emulator_pid_for_port() {
  local port="$1"
  pgrep -f -- "-port $port" 2>/dev/null || true
}

cmd_clone() {
  local k
  for ((k = 1; k <= POOL_SIZE; k++)); do
    pocketshell_avd_clone_one "$BASE_AVD" "$k"
  done
}

boot_one() {
  local k="$1"
  local clone_name port serial logf
  clone_name="$(pool_clone_name_for_index "$k")"
  port="$(pool_port_for_index "$k")"
  serial="$(pool_serial_for_index "$k")"
  logf="$LOG_ROOT/$clone_name.log"

  if serial_online "$serial" && boot_completed_for "$serial"; then
    printf 'Pool emulator %s already booted (%s).\n' "$clone_name" "$serial" >&2
    return 0
  fi

  if [[ -n "$(emulator_pid_for_port "$port")" ]]; then
    printf 'Pool emulator on port %s already starting; waiting for boot.\n' "$port" >&2
  else
    printf 'Booting %s on port %s (%s)...\n' "$clone_name" "$port" "$serial" >&2
    pool_launch_emulator "$clone_name" "$port" "$logf" > "$LOG_ROOT/$clone_name.pid"
  fi

  local deadline=$((SECONDS + BOOT_TIMEOUT_SECONDS))
  while (( SECONDS < deadline )); do
    if boot_completed_for "$serial"; then
      printf 'Booted: %s (%s)\n' "$clone_name" "$serial" >&2
      return 0
    fi
    # If the emulator process died and the device never came online, fail fast.
    if [[ -z "$(emulator_pid_for_port "$port")" ]] && ! serial_online "$serial"; then
      printf 'FAIL: %s (port %s) exited before boot. Log tail:\n' "$clone_name" "$port" >&2
      tail -n 25 "$logf" >&2 || true
      return 1
    fi
    sleep 3
  done
  printf 'FAIL: %s (%s) did not boot within %ss.\n' "$clone_name" "$serial" "$BOOT_TIMEOUT_SECONDS" >&2
  return 1
}

cmd_start() {
  cmd_clone
  "$ADB" start-server >/dev/null 2>&1 || true
  local k failures=0
  for ((k = 1; k <= POOL_SIZE; k++)); do
    if ! boot_one "$k"; then
      failures=$((failures + 1))
    fi
  done
  printf '\n'
  cmd_status
  if (( failures > 0 )); then
    printf '\n%s of %s pool emulator(s) failed to boot.\n' "$failures" "$POOL_SIZE" >&2
    return 1
  fi
  printf '\nPool ready: %s emulator(s).\n' "$POOL_SIZE" >&2
}

stop_one() {
  local k="$1"
  local port serial pidf
  port="$(pool_port_for_index "$k")"
  serial="$(pool_serial_for_index "$k")"
  pidf="$LOG_ROOT/$(pool_clone_name_for_index "$k").pid"

  if serial_online "$serial"; then
    printf 'Stopping %s via adb emu kill...\n' "$serial" >&2
    "$ADB" -s "$serial" emu kill >/dev/null 2>&1 || true
  fi
  # Backstop: kill the emulator process bound to this port if still alive.
  local pid
  pid="$(emulator_pid_for_port "$port")"
  if [[ -n "$pid" ]]; then
    sleep 1
    pid="$(emulator_pid_for_port "$port")"
    if [[ -n "$pid" ]]; then
      kill "$pid" 2>/dev/null || true
    fi
  fi
  rm -f "$pidf" 2>/dev/null || true
}

cmd_stop() {
  local k
  for ((k = 1; k <= POOL_SIZE; k++)); do
    stop_one "$k"
  done
  printf 'Pool stopped (emulator-5554 left untouched).\n' >&2
}

cmd_status() {
  printf 'AVD pool status (base=%s, size=%s, port_base=%s)\n' "$BASE_AVD" "$POOL_SIZE" "$POOL_PORT_BASE"
  printf '%-12s %-16s %-8s %s\n' "CLONE" "SERIAL" "PORT" "STATE"
  local k
  for ((k = 1; k <= POOL_SIZE; k++)); do
    local clone_name port serial state
    clone_name="$(pool_clone_name_for_index "$k")"
    port="$(pool_port_for_index "$k")"
    serial="$(pool_serial_for_index "$k")"
    if serial_online "$serial"; then
      if boot_completed_for "$serial"; then
        state="booted"
      else
        state="booting"
      fi
    elif [[ -n "$(emulator_pid_for_port "$port")" ]]; then
      state="starting"
    elif pocketshell_avd_clone_exists "$clone_name"; then
      state="stopped(cloned)"
    else
      state="absent"
    fi
    printf '%-12s %-16s %-8s %s\n' "$clone_name" "$serial" "$port" "$state"
  done
  printf '\n== host ==\n'
  uptime || true
  free -h | awk 'NR<=2' || true
}

cmd_purge() {
  cmd_stop
  local k
  for ((k = 1; k <= POOL_SIZE; k++)); do
    local clone_name
    clone_name="$(pool_clone_name_for_index "$k")"
    printf 'Removing clone AVD: %s\n' "$clone_name" >&2
    pocketshell_avd_clone_remove "$clone_name"
  done
}

case "${1:-}" in
  start)  cmd_start ;;
  stop)   cmd_stop ;;
  status) cmd_status ;;
  clone)  cmd_clone ;;
  purge)  cmd_purge ;;
  -h|--help|"") usage ;;
  *) printf 'Unknown subcommand: %s\n\n' "$1" >&2; usage; exit 2 ;;
esac
