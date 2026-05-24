#!/usr/bin/env bash
# Issue #104 keyboard-stress harness.
#
# Runs the `TerminalKeyboardStressTest` androidTest against the local
# emulator + agents Docker fixture. Pulls the typing-latency timings,
# keyboard-hide stable-layout timings, and `dumpsys gfxinfo` snapshots
# under `build/keyboard-stress/<run-id>/`.
#
# Usage:
#   scripts/keyboard-stress.sh
#   RUN_ID=issue-104-review scripts/keyboard-stress.sh
#
# Requires:
#   - local Android emulator AVD `test` (or override via `AVD_NAME=...`)
#   - Docker compose with the `agents` service from
#     `tests/docker/docker-compose.yml` (started automatically below)
#
# Prints the summary file path at the end. The reviewer should inspect
# `<run-dir>/artifacts/keyboard-stress/keyboard-stress-summary.txt` for
# typing-latency median/p90/max, hide-to-stable timing, gfxinfo jank %
# and high-input-latency counts.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
EMULATOR="${EMULATOR:-$ANDROID_SDK/emulator/emulator}"
AVD_NAME="${AVD_NAME:-test}"
COMPOSE_FILE="${COMPOSE_FILE:-tests/docker/docker-compose.yml}"
LOG_ROOT="${LOG_ROOT:-$ROOT_DIR/build/keyboard-stress}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RUN_DIR="$LOG_ROOT/$RUN_ID"
ARTIFACT_DIR="$RUN_DIR/artifacts/keyboard-stress"
BUILD_APKS="${BUILD_APKS:-1}"
TEST_SELECTOR="${TEST_SELECTOR:-com.pocketshell.app.terminal.TerminalKeyboardStressTest#typingAndKeyboardToggleStayResponsiveUnderLiveOutput}"
APP_APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
SSH_KEY="${SSH_KEY:-$ROOT_DIR/tests/docker/test_key}"
SSH_HOST="${SSH_HOST:-127.0.0.1}"
SSH_USER="${SSH_USER:-testuser}"
SSH_PORT="${SSH_PORT:-2222}"
DEVICE_OUTPUT_DIR="/sdcard/Android/media/com.pocketshell.app/additional_test_output"
DEVICE_ARTIFACT_DIR="$DEVICE_OUTPUT_DIR/terminal-lab/keyboard-stress"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  printf 'Artifacts: %s\n' "$RUN_DIR" >&2
  exit 1
}

run_logged() {
  local name="$1"
  shift
  local log_file="$RUN_DIR/$name.log"
  printf '\n[%s]\n' "$name"
  printf 'Command:'
  printf ' %q' "$@"
  printf '\nLog: %s\n' "$log_file"
  {
    printf '[%s] %s\n' "$(date -Is)" "$name"
    printf 'Command:'
    printf ' %q' "$@"
    printf '\n\n'
    "$@"
  } 2>&1 | tee "$log_file"
}

wait_for_emulator() {
  "$ADB" devices -l
  local state
  for _ in $(seq 1 90); do
    state="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    if [[ "$state" == "1" ]]; then
      return 0
    fi
    sleep 2
  done
  "$ADB" devices -l
  fail "emulator did not boot"
}

wait_for_ssh_fixture() {
  local log_file="$RUN_DIR/docker-ssh-readiness.log"
  : > "$log_file"
  local attempt
  for attempt in $(seq 1 60); do
    {
      printf '[%s] attempt=%s\n' "$(date -Is)" "$attempt"
      ssh \
        -i "$SSH_KEY" \
        -p "$SSH_PORT" \
        -o BatchMode=yes \
        -o ConnectTimeout=3 \
        -o ConnectionAttempts=1 \
        -o StrictHostKeyChecking=no \
        -o UserKnownHostsFile=/dev/null \
        "$SSH_USER@$SSH_HOST" \
        "printf 'keyboard stress ssh ready '; tmux -V"
    } >> "$log_file" 2>&1 && {
      tail -n 20 "$log_file"
      return 0
    }
    sleep 1
  done
  tail -n 80 "$log_file" || true
  fail "Docker SSH fixture did not become ready"
}

collect_diagnostics() {
  local exit_code=$?
  {
    printf 'exit_code=%s\n' "$exit_code"
    printf 'run_dir=%s\n' "$RUN_DIR"
    printf 'timestamp=%s\n' "$(date -Is)"
  } > "$RUN_DIR/exit-status.txt"
  docker compose -f "$COMPOSE_FILE" ps > "$RUN_DIR/docker-compose-ps.txt" 2>&1 || true
  docker compose -f "$COMPOSE_FILE" logs --no-color --timestamps agents > "$RUN_DIR/docker-agents.log" 2>&1 || true
  "$ADB" logcat -d -v threadtime -t 4000 > "$RUN_DIR/logcat.txt" 2>&1 || true
}

mkdir -p "$RUN_DIR"
trap collect_diagnostics EXIT

printf 'PocketShell keyboard stress harness (issue #104)\n'
printf 'Artifacts: %s\n' "$RUN_DIR"
printf 'Test selector: %s\n' "$TEST_SELECTOR"

[[ -x "$ADB" ]] || fail "adb is not executable at $ADB"
[[ -x "$EMULATOR" ]] || fail "emulator is not executable at $EMULATOR"
[[ -f "$SSH_KEY" ]] || fail "SSH key missing at $SSH_KEY"
command -v ssh >/dev/null 2>&1 || fail "ssh client is missing"

if ! "$ADB" get-state >/dev/null 2>&1; then
  run_logged "00-start-emulator" "$EMULATOR" -avd "$AVD_NAME" -no-snapshot -no-window -gpu swiftshader_indirect -no-audio -no-boot-anim &
fi
run_logged "01-wait-emulator" wait_for_emulator
run_logged "02-docker-agents-up" docker compose -f "$COMPOSE_FILE" up -d --build agents
run_logged "03-docker-ssh-readiness" wait_for_ssh_fixture

if [[ "$BUILD_APKS" == "1" ]]; then
  run_logged "04-build-apks" ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
else
  [[ -f "$APP_APK" ]] || fail "app APK missing at $APP_APK"
  [[ -f "$TEST_APK" ]] || fail "test APK missing at $TEST_APK"
fi

run_logged "05-install-apks" bash -lc "'$ADB' install -r -d -t '$APP_APK' && '$ADB' install -r -d -t '$TEST_APK'"
run_logged "06-reset-artifacts" "$ADB" shell rm -rf "$DEVICE_ARTIFACT_DIR"
INSTRUMENTATION_ARGS=(
  -e additionalTestOutputDir "$DEVICE_OUTPUT_DIR"
  -e class "$TEST_SELECTOR"
)
run_logged "07-run-keyboard-stress" \
  "$ADB" shell am instrument -w -r \
  "${INSTRUMENTATION_ARGS[@]}" \
  com.pocketshell.app.test/androidx.test.runner.AndroidJUnitRunner

mkdir -p "$RUN_DIR/artifacts"
rm -rf "$RUN_DIR/artifacts"
mkdir -p "$RUN_DIR/artifacts"
run_logged "08-pull-artifacts" "$ADB" pull "$DEVICE_ARTIFACT_DIR" "$RUN_DIR/artifacts/"

grep -q "OK (" "$RUN_DIR/07-run-keyboard-stress.log" &&
  grep -q "INSTRUMENTATION_CODE: -1" "$RUN_DIR/07-run-keyboard-stress.log" ||
  fail "keyboard stress instrumentation did not pass"

[[ -s "$ARTIFACT_DIR/keyboard-stress-summary.txt" ]] ||
  fail "keyboard-stress-summary.txt was not produced under $ARTIFACT_DIR"

printf '\nPASS: keyboard stress harness completed\n'
printf 'Artifacts: %s\n' "$ARTIFACT_DIR"
printf 'Summary: %s/keyboard-stress-summary.txt\n' "$ARTIFACT_DIR"
