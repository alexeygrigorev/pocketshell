#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
EMULATOR="${EMULATOR:-$ANDROID_SDK/emulator/emulator}"
AVD_NAME="${AVD_NAME:-test}"
COMPOSE_FILE_WAS_SET=0
if [[ -n "${COMPOSE_FILE:-}" ]]; then
  COMPOSE_FILE_WAS_SET=1
fi
COMPOSE_FILE="${COMPOSE_FILE:-tests/docker/docker-compose.yml}"
LOG_ROOT="${LOG_ROOT:-$ROOT_DIR/build/terminal-workbench}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RUN_DIR="$LOG_ROOT/$RUN_ID"
BUILD_APKS="${BUILD_APKS:-1}"
HOLD_MS="${HOLD_MS:-0}"
DEBUG_HOLD_MS="${DEBUG_HOLD_MS:-0}"
REAL_AGENTS="${REAL_AGENTS:-0}"
if [[ -n "${TEST_SELECTOR:-}" ]]; then
  TEST_SELECTOR_WAS_SET=1
else
  TEST_SELECTOR_WAS_SET=0
fi
if [[ "$REAL_AGENTS" == "1" ]]; then
  if [[ "$COMPOSE_FILE_WAS_SET" != "1" ]]; then
    COMPOSE_FILE="tests/docker/real-agent/compose.yml"
  fi
  AGENT_SERVICE="${AGENT_SERVICE:-real-agents}"
  SSH_PORT="${SSH_PORT:-2240}"
else
  AGENT_SERVICE="${AGENT_SERVICE:-agents}"
  SSH_PORT="${SSH_PORT:-2222}"
fi
DEVICE_OUTPUT_DIR="/sdcard/Android/media/com.pocketshell.app/additional_test_output"
DEVICE_ARTIFACT_DIR="$DEVICE_OUTPUT_DIR/terminal-lab"
TEST_SELECTOR="${TEST_SELECTOR:-com.pocketshell.app.terminal.TerminalLabDockerTest#terminalWorkbenchKeepsDockerShellOpenForVisualIteration}"
if [[ "$REAL_AGENTS" == "1" && "$TEST_SELECTOR_WAS_SET" != "1" ]]; then
  TEST_SELECTOR="com.pocketshell.app.terminal.TerminalLabDockerTest#terminalWorkbenchCapturesRealAgentCliScreens"
fi
APP_APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
SSH_KEY="${SSH_KEY:-$ROOT_DIR/tests/docker/test_key}"
SSH_HOST="${SSH_HOST:-127.0.0.1}"
SSH_USER="${SSH_USER:-testuser}"

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
        "printf 'terminal workbench ssh ready '; tmux -V"
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
  docker compose -f "$COMPOSE_FILE" logs --no-color --timestamps "$AGENT_SERVICE" > "$RUN_DIR/docker-agents.log" 2>&1 || true
  "$ADB" exec-out screencap -p > "$RUN_DIR/final-screen.png" 2>/dev/null || true
  "$ADB" logcat -d -v threadtime -t 4000 > "$RUN_DIR/logcat.txt" 2>&1 || true
}

mkdir -p "$RUN_DIR"
trap collect_diagnostics EXIT

printf 'PocketShell terminal workbench\n'
printf 'Artifacts: %s\n' "$RUN_DIR"
printf 'Hold ms: %s\n' "$HOLD_MS"
printf 'Debug hold ms: %s\n' "$DEBUG_HOLD_MS"
printf 'Agent service: %s\n' "$AGENT_SERVICE"
printf 'Test selector: %s\n' "$TEST_SELECTOR"

[[ -x "$ADB" ]] || fail "adb is not executable at $ADB"
[[ -x "$EMULATOR" ]] || fail "emulator is not executable at $EMULATOR"
[[ -f "$SSH_KEY" ]] || fail "SSH key missing at $SSH_KEY"
command -v ssh >/dev/null 2>&1 || fail "ssh client is missing"

if ! "$ADB" get-state >/dev/null 2>&1; then
  run_logged "00-start-emulator" "$EMULATOR" -avd "$AVD_NAME" -no-snapshot -no-window -gpu swiftshader_indirect -no-audio -no-boot-anim &
fi
run_logged "01-wait-emulator" wait_for_emulator
run_logged "02-docker-agents-up" docker compose -f "$COMPOSE_FILE" up -d --build "$AGENT_SERVICE"
run_logged "03-docker-ssh-readiness" wait_for_ssh_fixture

if [[ "$BUILD_APKS" == "1" ]]; then
  run_logged "04-build-apks" ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
else
  [[ -f "$APP_APK" ]] || fail "app APK missing at $APP_APK"
  [[ -f "$TEST_APK" ]] || fail "test APK missing at $TEST_APK"
fi

run_logged "05-install-apks" bash -lc "'$ADB' install -r -d -t '$APP_APK' && '$ADB' install -r -d -t '$TEST_APK'"
run_logged "06-reset-artifacts" "$ADB" shell rm -rf "$DEVICE_ARTIFACT_DIR"
run_logged "07-run-workbench" \
  "$ADB" shell am instrument -w -r \
  -e additionalTestOutputDir "$DEVICE_OUTPUT_DIR" \
  -e terminalWorkbenchHoldMs "$HOLD_MS" \
  -e terminalWorkbenchDebugHoldMs "$DEBUG_HOLD_MS" \
  -e terminalWorkbenchSshPort "$SSH_PORT" \
  -e class "$TEST_SELECTOR" \
  com.pocketshell.app.test/androidx.test.runner.AndroidJUnitRunner
mkdir -p "$RUN_DIR/artifacts"
run_logged "08-pull-artifacts" "$ADB" pull "$DEVICE_ARTIFACT_DIR" "$RUN_DIR/artifacts/" || true
if [[ -d "$RUN_DIR/artifacts/terminal-lab" ]]; then
  run_logged "09-artifact-file-info" file "$RUN_DIR"/artifacts/terminal-lab/* || true
  {
    printf 'run_dir=%s\n' "$RUN_DIR"
    printf 'real_agents=%s\n' "$REAL_AGENTS"
    printf 'test_selector=%s\n' "$TEST_SELECTOR"
    printf 'hold_ms=%s\n' "$HOLD_MS"
    printf 'debug_hold_ms=%s\n' "$DEBUG_HOLD_MS"
    printf 'device_artifact_dir=%s\n' "$DEVICE_ARTIFACT_DIR"
    printf '\nPulled artifacts:\n'
    find "$RUN_DIR/artifacts/terminal-lab" -maxdepth 1 -type f -printf '%f\t%k KB\n' | sort
    printf '\nScreenshots:\n'
    find "$RUN_DIR/artifacts/terminal-lab" -maxdepth 1 -type f -name '*.png' -printf '%f\n' | sort
  } > "$RUN_DIR/artifact-summary.txt"
fi

grep -q "OK (" "$RUN_DIR/07-run-workbench.log" &&
  grep -q "INSTRUMENTATION_CODE: -1" "$RUN_DIR/07-run-workbench.log" ||
  fail "terminal workbench instrumentation did not pass"

printf '\nPASS: terminal workbench completed\n'
printf 'Artifacts: %s/artifacts/terminal-lab\n' "$RUN_DIR"
