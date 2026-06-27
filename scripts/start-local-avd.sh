#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

source "$ROOT_DIR/scripts/lib/avd-lock.sh"
source "$ROOT_DIR/scripts/lib/scope-run.sh"
pocketshell_acquire_avd_lock "$ROOT_DIR" "${1:-}"

ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
EMULATOR="${EMULATOR:-$ANDROID_SDK/emulator/emulator}"
AVD_NAME="${AVD_NAME:-test}"
LOG_ROOT="${LOG_ROOT:-$ROOT_DIR/build/local-avd-start}"
if [[ "$LOG_ROOT" != /* ]]; then
  LOG_ROOT="$ROOT_DIR/$LOG_ROOT"
fi
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RUN_DIR="$LOG_ROOT/$RUN_ID"
SUMMARY_PATH="$RUN_DIR/summary.txt"
BOOT_TIMEOUT_SECONDS="${BOOT_TIMEOUT_SECONDS:-240}"
AVD_START_FLAGS="${AVD_START_FLAGS:--no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-load -no-snapshot-save}"
AVD_WIPE_DATA="${AVD_WIPE_DATA:-0}"
AVD_HOLD="${AVD_HOLD:-0}"
AVD_SCOPE="${AVD_SCOPE:-1}"

usage() {
  cat <<'USAGE'
Usage: scripts/start-local-avd.sh

Starts the local AVD used for PocketShell connected Android review evidence and
waits until adb reports sys.boot_completed=1. It writes diagnostics under
build/local-avd-start/<run-id>/ so an emulator that exits before adb discovery
leaves actionable logs instead of only a missing device.

New emulator processes are cgroup-scoped by default through
scripts/lib/scope-run.sh. Set AVD_SCOPE=0 only when debugging cgroup setup.

Environment overrides:
  ANDROID_SDK=/home/alexey/Android/Sdk
  ADB=$ANDROID_SDK/platform-tools/adb
  EMULATOR=$ANDROID_SDK/emulator/emulator
  AVD_NAME=test
  LOG_ROOT=build/local-avd-start
  RUN_ID=<timestamp>
  BOOT_TIMEOUT_SECONDS=240
  AVD_START_FLAGS="-no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-load -no-snapshot-save"
  AVD_WIPE_DATA=1
  AVD_HOLD=1
  AVD_SCOPE=0
  POCKETSHELL_TEST_MEM=8G

Set AVD_HOLD=1 in one terminal when collecting connectedDebugAndroidTest
evidence. The helper keeps monitoring adb/process state until interrupted, so
an emulator that exits after boot is reported in the same run directory.

On success, run focused evidence commands such as:
  ./gradlew --no-daemon :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.proof.EmulatorDockerSshSmokeTest
USAGE
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

mkdir -p "$RUN_DIR"

fail() {
  local message="$1"
  write_summary "FAIL" "$message"
  printf 'FAIL: %s\n' "$message" >&2
  printf 'Diagnostics: %s\n' "$RUN_DIR" >&2
  exit 1
}

require_executable() {
  local path="$1"
  local label="$2"
  [[ -x "$path" ]] || fail "$label is not executable at $path"
}

adb_devices() {
  "$ADB" devices -l > "$RUN_DIR/adb-devices.txt" 2>&1 || true
}

boot_completed() {
  local state
  state="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  [[ "$state" == "1" ]]
}

has_adb_device() {
  "$ADB" devices | awk 'NR > 1 && $2 == "device" { found = 1 } END { exit found ? 0 : 1 }'
}

list_emulator_processes() {
  ps -eo pid=,comm=,args= |
    awk -v avd="$AVD_NAME" '
      {
        command_name = $2
      }
      command_name == "emulator" {
        for (i = 3; i <= NF; i++) {
          if ($i == "-avd" && (i + 1) <= NF && $(i + 1) == avd) {
            print
            next
          }
          if ($i == ("-avd=" avd)) {
            print
            next
          }
        }
      }
      command_name ~ /^qemu-system/ {
        for (i = 3; i <= NF; i++) {
          if (index($i, avd) > 0) {
            print
            next
          }
        }
      }
    '
}

record_diagnostics() {
  {
    printf 'timestamp=%s\n' "$(date -Is)"
    printf 'android_sdk=%s\n' "$ANDROID_SDK"
    printf 'adb=%s\n' "$ADB"
    printf 'emulator=%s\n' "$EMULATOR"
    printf 'avd_name=%s\n' "$AVD_NAME"
    printf 'avd_start_flags=%s\n' "$AVD_START_FLAGS"
    printf 'avd_wipe_data=%s\n' "$AVD_WIPE_DATA"
    printf 'avd_hold=%s\n' "$AVD_HOLD"
    printf 'avd_scope=%s\n' "$AVD_SCOPE"
    printf 'pocketshell_test_mem=%s\n' "${POCKETSHELL_TEST_MEM:-8G}"
    printf 'boot_timeout_seconds=%s\n' "$BOOT_TIMEOUT_SECONDS"
    printf '\n== adb devices ==\n'
    "$ADB" devices -l || true
    printf '\n== adb get-state ==\n'
    "$ADB" get-state || true
    printf '\n== adb get-serialno ==\n'
    "$ADB" get-serialno || true
    printf '\n== emulator -accel-check ==\n'
    "$EMULATOR" -accel-check || true
    printf '\n== emulator -list-avds ==\n'
    "$EMULATOR" -list-avds || true
    printf '\n== emulator processes ==\n'
    list_emulator_processes || true
    printf '\n== avd config.ini ==\n'
    local config_path="$HOME/.android/avd/$AVD_NAME.avd/config.ini"
    if [[ -f "$config_path" ]]; then
      sed -n '1,220p' "$config_path" || true
    else
      printf 'missing: %s\n' "$config_path"
    fi
    printf '\n== emulator log tail ==\n'
    if [[ -f "$RUN_DIR/emulator.log" ]]; then
      tail -n 200 "$RUN_DIR/emulator.log" || true
    else
      printf 'missing: %s\n' "$RUN_DIR/emulator.log"
    fi
  } > "$RUN_DIR/diagnostics.txt" 2>&1
  adb_devices
  "$ADB" shell getprop > "$RUN_DIR/getprop.txt" 2>&1 || true
}

write_summary() {
  local status="$1"
  local message="$2"
  local serial="unknown"
  serial="$("$ADB" get-serialno 2>/dev/null | tr -d '\r' || true)"
  [[ -n "$serial" ]] || serial="unknown"
  record_diagnostics
  {
    printf 'PocketShell local AVD startup\n'
    printf 'Generated: %s\n' "$(date -Is)"
    printf 'Status: %s\n' "$status"
    printf 'Message: %s\n' "$message"
    printf 'Run directory: %s\n' "$RUN_DIR"
    printf 'AVD: %s\n' "$AVD_NAME"
    printf 'ADB serial: %s\n' "$serial"
    printf 'Start flags: %s\n' "$AVD_START_FLAGS"
    printf 'Hold mode: %s\n' "$AVD_HOLD"
    printf 'Cgroup scope: %s\n' "$AVD_SCOPE"
    printf 'Diagnostics: %s\n' "$RUN_DIR/diagnostics.txt"
    printf 'Emulator log: %s\n' "$RUN_DIR/emulator.log"
    printf 'ADB devices: %s\n' "$RUN_DIR/adb-devices.txt"
    printf 'Getprop: %s\n' "$RUN_DIR/getprop.txt"
  } > "$SUMMARY_PATH"
}

require_executable "$ADB" "adb"
require_executable "$EMULATOR" "emulator"

hold_if_requested() {
  if [[ "$AVD_HOLD" != "1" ]]; then
    return 0
  fi

  printf 'AVD_HOLD=1; keeping the startup helper alive. Press Ctrl-C after connected tests finish.\n'
  while :; do
    if boot_completed; then
      sleep 5
      continue
    fi
    if ! has_adb_device && [[ -z "$(list_emulator_processes)" ]]; then
      fail "AVD '$AVD_NAME' exited while AVD_HOLD=1 was monitoring it"
    fi
    sleep 2
  done
}

"$ADB" start-server >/dev/null
"$EMULATOR" -list-avds > "$RUN_DIR/available-avds.txt" 2>&1 || fail "could not list AVDs with $EMULATOR"
grep -Fxq "$AVD_NAME" "$RUN_DIR/available-avds.txt" ||
  fail "AVD '$AVD_NAME' was not listed by $EMULATOR -list-avds"

if has_adb_device && boot_completed; then
  write_summary "PASS" "existing adb device is already booted"
  printf 'Existing adb device is already booted.\n'
  printf 'Summary: %s\n' "$SUMMARY_PATH"
  hold_if_requested
  exit 0
fi

read -r -a start_args <<< "$AVD_START_FLAGS"
if [[ "$AVD_WIPE_DATA" == "1" ]]; then
  start_args+=("-wipe-data")
fi

if ! has_adb_device && [[ -z "$(list_emulator_processes)" ]]; then
  printf 'Starting AVD %s with flags:%s\n' "$AVD_NAME" " $AVD_START_FLAGS"
  {
    printf '[%s] command:' "$(date -Is)"
    printf ' %q' "$EMULATOR" -avd "$AVD_NAME" "${start_args[@]}"
    printf '\n\n'
  } > "$RUN_DIR/emulator.log"
  if [[ "$AVD_SCOPE" == "1" ]]; then
    scope_unit="pocketshell-avd-${AVD_NAME//[^A-Za-z0-9._-]/_}-${RUN_ID//[^A-Za-z0-9._-]/_}"
    pocketshell_scope_start_background \
      "$scope_unit" \
      "$RUN_DIR/emulator.log" \
      "$RUN_DIR/emulator.pid" \
      "$EMULATOR" -avd "$AVD_NAME" "${start_args[@]}"
    printf '%s\n' "$scope_unit.scope" > "$RUN_DIR/emulator.scope"
  else
    nohup "$EMULATOR" -avd "$AVD_NAME" "${start_args[@]}" >> "$RUN_DIR/emulator.log" 2>&1 &
    emulator_pid="$!"
    printf '%s\n' "$emulator_pid" > "$RUN_DIR/emulator.pid"
  fi
fi

deadline=$((SECONDS + BOOT_TIMEOUT_SECONDS))
while (( SECONDS < deadline )); do
  if boot_completed; then
    write_summary "PASS" "sys.boot_completed=1"
    printf 'Emulator readiness confirmed: sys.boot_completed=1\n'
    printf 'Summary: %s\n' "$SUMMARY_PATH"
    hold_if_requested
    exit 0
  fi
  if ! has_adb_device && [[ -z "$(list_emulator_processes)" ]]; then
    fail "AVD '$AVD_NAME' exited before adb reported a device"
  fi
  sleep 2
done

fail "AVD '$AVD_NAME' did not report sys.boot_completed=1 within ${BOOT_TIMEOUT_SECONDS}s"
