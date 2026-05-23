#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
EMULATOR="${EMULATOR:-$ANDROID_SDK/emulator/emulator}"
AVD_NAME="${AVD_NAME:-test}"
COMPOSE_FILE="${COMPOSE_FILE:-tests/docker/docker-compose.yml}"
LOG_ROOT="${LOG_ROOT:-$ROOT_DIR/build/terminal-lab}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RUN_DIR="$LOG_ROOT/$RUN_ID"
BUILD_APKS="${BUILD_APKS:-1}"
DEVICE_OUTPUT_DIR="/sdcard/Android/media/com.pocketshell.app/additional_test_output"
DEVICE_ARTIFACT_DIR="$DEVICE_OUTPUT_DIR/terminal-lab"
TEST_CLASS="com.pocketshell.app.terminal.TerminalLabDockerTest"
SSH_KEY="${SSH_KEY:-$ROOT_DIR/tests/docker/test_key}"
SSH_HOST="${SSH_HOST:-127.0.0.1}"
SSH_PORT="${SSH_PORT:-2222}"
SSH_USER="${SSH_USER:-testuser}"
APP_APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
SCREENSHOTS=(
  "01-connected-prompt.png"
  "02-pwd-ls.png"
  "03-long-path-git-status.png"
  "04-backspace-repeat.png"
)

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  printf 'Artifacts: %s\n' "$RUN_DIR" >&2
  exit 1
}

require_executable() {
  local path="$1"
  local label="$2"
  [[ -x "$path" ]] || fail "$label is not executable at $path"
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

mkdir -p "$RUN_DIR"

collect_diagnostics() {
  local exit_code=$?
  {
    printf 'exit_code=%s\n' "$exit_code"
    printf 'run_dir=%s\n' "$RUN_DIR"
    printf 'timestamp=%s\n' "$(date -Is)"
  } > "$RUN_DIR/exit-status.txt"
  docker compose -f "$COMPOSE_FILE" ps > "$RUN_DIR/docker-compose-ps.txt" 2>&1 || true
  docker compose -f "$COMPOSE_FILE" logs --no-color --timestamps agents > "$RUN_DIR/docker-agents.log" 2>&1 || true
  "$ADB" devices -l > "$RUN_DIR/adb-devices-final.txt" 2>&1 || true
  "$ADB" logcat -d -v threadtime > "$RUN_DIR/adb-logcat.txt" 2>&1 || true
  if [[ "$exit_code" -ne 0 ]]; then
    printf '\nDiagnostics collected in %s\n' "$RUN_DIR" >&2
  fi
}
trap collect_diagnostics EXIT

wait_for_host_ssh_fixture() {
  local attempt
  local log_file="$RUN_DIR/04-docker-ssh-readiness.log"
  : > "$log_file"
  for attempt in $(seq 1 60); do
    {
      printf '[%s] attempt %s\n' "$(date -Is)" "$attempt"
      ssh \
        -i "$SSH_KEY" \
        -p "$SSH_PORT" \
        -o BatchMode=yes \
        -o ConnectTimeout=3 \
        -o ConnectionAttempts=1 \
        -o StrictHostKeyChecking=no \
        -o UserKnownHostsFile=/dev/null \
        "$SSH_USER@$SSH_HOST" \
        "printf 'terminal lab ssh ready '; tmux -V"
    } >> "$log_file" 2>&1 && {
      printf '\n[04-docker-ssh-readiness]\nLog: %s\n' "$log_file"
      tail -n 20 "$log_file"
      return 0
    }
    sleep 1
  done
  printf '\n[04-docker-ssh-readiness]\nLog: %s\n' "$log_file"
  tail -n 80 "$log_file" || true
  fail "Docker SSH fixture did not become ready at $SSH_USER@$SSH_HOST:$SSH_PORT"
}

assert_artifacts_exist() {
  local artifact_dir="$RUN_DIR/artifacts/terminal-lab"
  local missing=()
  local file_name
  for file_name in "${SCREENSHOTS[@]}" "timings.txt"; do
    [[ -s "$artifact_dir/$file_name" ]] || missing+=("$file_name")
  done
  if [[ "${#missing[@]}" -ne 0 ]]; then
    printf '%s\n' "Missing terminal lab artifacts:" "${missing[@]}" >&2
    fail "Expected terminal lab artifacts were not pulled"
  fi
}

printf 'PocketShell terminal lab validation\n'
printf 'Artifacts: %s\n' "$RUN_DIR"
printf 'ADB: %s\n' "$ADB"
printf 'Emulator: %s\n' "$EMULATOR"
printf 'AVD: %s\n' "$AVD_NAME"

require_executable "$ADB" "adb"
require_executable "$EMULATOR" "emulator"
command -v ssh >/dev/null 2>&1 || fail "ssh client was not found on PATH"
[[ -f "$SSH_KEY" ]] || fail "SSH key was not found at $SSH_KEY"

run_logged "01-adb-version" "$ADB" version
run_logged "02-available-avds" "$EMULATOR" -list-avds
if ! "$EMULATOR" -list-avds | grep -Fxq "$AVD_NAME"; then
  fail "AVD '$AVD_NAME' was not listed by $EMULATOR -list-avds"
fi

run_logged "03-docker-agents-recreate" docker compose -f "$COMPOSE_FILE" up -d --build --force-recreate agents
wait_for_host_ssh_fixture
run_logged "05-emulator-readiness" bash -lc \
  "'$ADB' devices && for i in {1..90}; do state=\$('$ADB' shell getprop sys.boot_completed 2>/dev/null | tr -d '\r'); if [ \"\$state\" = 1 ]; then exit 0; fi; sleep 2; done; '$ADB' devices; exit 1"
run_logged "06-reset-emulator-app-state" bash -lc \
  "'$ADB' shell am force-stop com.pocketshell.app >/dev/null 2>&1 || true; '$ADB' shell am force-stop com.pocketshell.app.test >/dev/null 2>&1 || true; '$ADB' uninstall com.pocketshell.app >/dev/null 2>&1 || true; '$ADB' uninstall com.pocketshell.app.test >/dev/null 2>&1 || true"
run_logged "07-clear-logcat" "$ADB" logcat -c
run_logged "08-clear-device-artifacts" "$ADB" shell rm -rf "$DEVICE_ARTIFACT_DIR"
if [[ "$BUILD_APKS" = "1" ]]; then
  run_logged "09-build-terminal-lab-apks" ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
else
  [[ -f "$APP_APK" ]] || fail "BUILD_APKS=0 but app APK is missing at $APP_APK"
  [[ -f "$TEST_APK" ]] || fail "BUILD_APKS=0 but androidTest APK is missing at $TEST_APK"
  {
    printf '[%s] 09-build-terminal-lab-apks\n' "$(date -Is)"
    printf 'Skipped because BUILD_APKS=0\n'
    printf 'App APK: %s\n' "$APP_APK"
    printf 'Test APK: %s\n' "$TEST_APK"
  } | tee "$RUN_DIR/09-build-terminal-lab-apks.log"
fi
run_logged "10-install-terminal-lab-apks" bash -lc \
  "'$ADB' install -r '$APP_APK' && '$ADB' install -r '$TEST_APK'"
run_logged "10a-wait-for-package-manager" bash -lc \
  "'$ADB' shell cmd package wait-for-handler --timeout 120000 && '$ADB' shell cmd package wait-for-background-handler --timeout 120000 && sleep 2"
run_logged "10b-wait-for-terminal-lab-instrumentation" bash -lc \
  "for i in {1..30}; do '$ADB' shell pm list packages com.pocketshell.app | grep -q '^package:com.pocketshell.app$' && '$ADB' shell pm list packages com.pocketshell.app.test | grep -q '^package:com.pocketshell.app.test$' && '$ADB' shell pm list instrumentation | grep -q '^instrumentation:com.pocketshell.app.test/androidx.test.runner.AndroidJUnitRunner' && exit 0; sleep 1; done; '$ADB' shell pm list packages com.pocketshell.app; '$ADB' shell pm list packages com.pocketshell.app.test; '$ADB' shell pm list instrumentation; exit 1"
run_logged "10c-force-stop-before-instrumentation" bash -lc \
  "'$ADB' shell am force-stop com.pocketshell.app >/dev/null 2>&1 || true; '$ADB' shell am force-stop com.pocketshell.app.test >/dev/null 2>&1 || true; '$ADB' shell input keyevent HOME >/dev/null 2>&1 || true; sleep 2"
instrumentation_status=0
run_logged "11-run-terminal-lab-instrumentation" \
  "$ADB" shell am instrument -w -r \
  -e additionalTestOutputDir "$DEVICE_OUTPUT_DIR" \
  -e class "$TEST_CLASS" \
  com.pocketshell.app.test/androidx.test.runner.AndroidJUnitRunner ||
  instrumentation_status=$?
mkdir -p "$RUN_DIR/artifacts"
run_logged "12-pull-terminal-lab-artifacts" "$ADB" pull "$DEVICE_ARTIFACT_DIR" "$RUN_DIR/artifacts/" || true
if [[ -d "$RUN_DIR/artifacts/terminal-lab" ]]; then
  run_logged "13-terminal-lab-artifact-file-info" file "$RUN_DIR"/artifacts/terminal-lab/* || true
fi
[[ "$instrumentation_status" -eq 0 ]] || fail "$TEST_CLASS instrumentation command exited with $instrumentation_status"
grep -q "INSTRUMENTATION_CODE: -1" "$RUN_DIR/11-run-terminal-lab-instrumentation.log" &&
  grep -q "OK (" "$RUN_DIR/11-run-terminal-lab-instrumentation.log" &&
  ! grep -q "FAILURES!!!" "$RUN_DIR/11-run-terminal-lab-instrumentation.log" ||
  fail "$TEST_CLASS did not report instrumentation success"
assert_artifacts_exist

printf '\nPASS: terminal lab validation completed\n'
printf 'Artifacts: %s/artifacts/terminal-lab\n' "$RUN_DIR"
printf 'Timings:\n'
cat "$RUN_DIR/artifacts/terminal-lab/timings.txt"
