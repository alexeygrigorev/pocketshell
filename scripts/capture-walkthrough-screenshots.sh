#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
EMULATOR="${EMULATOR:-$ANDROID_SDK/emulator/emulator}"
AVD_NAME="${AVD_NAME:-test}"
COMPOSE_FILE="${COMPOSE_FILE:-tests/docker/docker-compose.yml}"
LOG_ROOT="${LOG_ROOT:-$ROOT_DIR/build/walkthrough-visual-pass}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RUN_DIR="$LOG_ROOT/$RUN_ID"
DEVICE_OUTPUT_DIR="/sdcard/Android/media/com.pocketshell.app/additional_test_output"
DEVICE_SCREENSHOT_DIR="$DEVICE_OUTPUT_DIR/walkthrough-visual-pass"
MAIN_TEST_CLASS="com.pocketshell.app.proof.WalkthroughVisualScreenshotTest"
COMPOSER_TEST_CLASS="com.pocketshell.app.composer.PromptComposerVisualScreenshotTest"
MAIN_SCREENSHOTS=(
  "01-host-list.png"
  "02-host-setup-folder-list.png"
  "03-terminal-session-input-controls.png"
  "04-snippets.png"
)
COMPOSER_SCREENSHOTS=(
  "05b-composer-idle-draft.png"
  "06-composer-recording.png"
  "07-composer-transcribing.png"
)
INSTRUMENTATION_ATTEMPTS="${INSTRUMENTATION_ATTEMPTS:-3}"
SSH_KEY="${SSH_KEY:-$ROOT_DIR/tests/docker/test_key}"
SSH_HOST="${SSH_HOST:-127.0.0.1}"
SSH_PORT="${SSH_PORT:-2222}"
SSH_USER="${SSH_USER:-testuser}"
APP_APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

usage() {
  cat <<'USAGE'
Usage: scripts/capture-walkthrough-screenshots.sh

Captures emulator screenshots for the main PocketShell walkthrough visual pass.
Defaults use explicit Android SDK paths:

  adb      /home/alexey/Android/Sdk/platform-tools/adb
  emulator /home/alexey/Android/Sdk/emulator/emulator

Environment overrides:
  ANDROID_SDK=/home/alexey/Android/Sdk
  ADB=$ANDROID_SDK/platform-tools/adb
  EMULATOR=$ANDROID_SDK/emulator/emulator
  AVD_NAME=test
  COMPOSE_FILE=tests/docker/docker-compose.yml
  LOG_ROOT=build/walkthrough-visual-pass
  RUN_ID=<custom artifact directory name>
  SSH_KEY=tests/docker/test_key
  SSH_HOST=127.0.0.1
  SSH_PORT=2222
  SSH_USER=testuser
  INSTRUMENTATION_ATTEMPTS=3
USAGE
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

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
  docker inspect pocketshell-test-agents > "$RUN_DIR/docker-agents-inspect.json" 2>&1 || true
  "$ADB" devices -l > "$RUN_DIR/adb-devices-final.txt" 2>&1 || true
  "$ADB" shell getprop > "$RUN_DIR/adb-getprop.txt" 2>&1 || true
  "$ADB" logcat -d -v threadtime > "$RUN_DIR/adb-logcat.txt" 2>&1 || true

  if [[ "$exit_code" -ne 0 ]]; then
    printf '\nDiagnostics collected in %s\n' "$RUN_DIR" >&2
  fi
}
trap collect_diagnostics EXIT

# Issue #150: wait on the compose `healthcheck:` block via
# `docker inspect`, not a host-side SSH retry loop. Keep one follow-up
# SSH probe so the readiness log still records the same evidence.
source "$ROOT_DIR/tests/docker/lib/wait-for-healthy.sh"

wait_for_host_ssh_fixture() {
  local log_file="$RUN_DIR/04-docker-ssh-readiness.log"
  if ! wait_for_container_healthy "$COMPOSE_FILE" agents "$log_file" 60; then
    printf '\n[04-docker-ssh-readiness]\nLog: %s\n' "$log_file"
    tail -n 80 "$log_file" || true
    fail "Docker SSH fixture did not become healthy at $SSH_USER@$SSH_HOST:$SSH_PORT"
  fi
  {
    printf '[%s] health=healthy; running follow-up SSH sanity probe\n' "$(date -Is)"
    ssh \
      -i "$SSH_KEY" \
      -p "$SSH_PORT" \
      -o BatchMode=yes \
      -o ConnectTimeout=3 \
      -o ConnectionAttempts=1 \
      -o StrictHostKeyChecking=no \
      -o UserKnownHostsFile=/dev/null \
      "$SSH_USER@$SSH_HOST" \
      "printf 'ssh fixture ready '; tmux -V"
  } >> "$log_file" 2>&1 || {
    printf '\n[04-docker-ssh-readiness]\nLog: %s\n' "$log_file"
    tail -n 80 "$log_file" || true
    fail "Docker SSH fixture reported healthy but follow-up SSH probe failed at $SSH_USER@$SSH_HOST:$SSH_PORT"
  }
  printf '\n[04-docker-ssh-readiness]\nLog: %s\n' "$log_file"
  tail -n 20 "$log_file"
}

install_apks() {
  local step_name="$1"
  run_logged "$step_name" bash -lc \
    "'$ADB' install -r '$APP_APK' && '$ADB' install -r '$TEST_APK' && sleep 5"
  wait_for_instrumentation "$step_name"
}

wait_for_instrumentation() {
  local step_name="$1"
  local log_file="$RUN_DIR/$step_name-instrumentation-ready.log"
  local attempt
  : > "$log_file"
  for attempt in $(seq 1 30); do
    {
      printf '[%s] attempt %s\n' "$(date -Is)" "$attempt"
      "$ADB" shell pm list packages com.pocketshell.app
      "$ADB" shell pm list packages com.pocketshell.app.test
      "$ADB" shell pm list instrumentation
    } >> "$log_file" 2>&1
    if grep -q '^package:com.pocketshell.app$' "$log_file" &&
      grep -q '^package:com.pocketshell.app.test$' "$log_file" &&
      grep -q '^instrumentation:com.pocketshell.app.test/androidx.test.runner.AndroidJUnitRunner' "$log_file"; then
      printf '\n[%s-instrumentation-ready]\nLog: %s\n' "$step_name" "$log_file"
      tail -n 20 "$log_file"
      return 0
    fi
    sleep 1
  done
  printf '\n[%s-instrumentation-ready]\nLog: %s\n' "$step_name" "$log_file"
  tail -n 120 "$log_file" || true
  fail "Android test instrumentation was not registered after APK install"
}

visual_audit_instrumentation_log_has_success() {
  local log_file="$1"
  grep -q "INSTRUMENTATION_CODE: -1" "$log_file" &&
    grep -q "OK (" "$log_file" &&
    ! grep -q "FAILURES!!!" "$log_file"
}

visual_audit_instrumentation_log_has_failure_markers() {
  local log_file="$1"
  grep -Eq '(^FAILURES!!!$|^FAILURE: |^INSTRUMENTATION_STATUS_CODE: -[0-9]+$|^INSTRUMENTATION_STATUS: stack=|^INSTRUMENTATION_RESULT: shortMsg=Process crashed[.]|^[[:space:]]*at (com[.]pocketshell|androidx[.]test|org[.]junit|kotlin[.]|java[.]|android[.])|java[.]lang[.]AssertionError|junit[.]framework[.]AssertionFailedError|org[.]junit[.]ComparisonFailure|kotlin[.]AssertionError|androidx[.]test[.]espresso[.](AmbiguousViewMatcherException|NoMatchingRootException|NoMatchingViewException|PerformException)|^Process crashed[.])' "$log_file"
}

visual_audit_logcat_has_app_or_test_failure_markers() {
  local logcat_file="$1"
  grep -Eq 'Process: com[.]pocketshell[.]app|FATAL EXCEPTION.*com[.]pocketshell[.]app|FATAL SIGNAL.*com[.]pocketshell[.]app|AndroidRuntime.*com[.]pocketshell[.]app|(^|[[:space:]])FAILURES!!!($|[[:space:]])|INSTRUMENTATION_STATUS: stack=|INSTRUMENTATION_RESULT: shortMsg=Process crashed' "$logcat_file"
}

visual_audit_instrumentation_log_has_transport_drop_markers() {
  local log_file="$1"
  grep -Eiq 'adb:.*(closed|device|disconnected|no devices|offline|protocol fault)|error: (closed|device .* not found|device offline|failed to get feature set|more than one device/emulator|no devices/emulators found|protocol fault)|device offline|device .* not found|transport .* (closed|disconnected|error|not found|offline)|Connection reset by peer|Broken pipe|lost connection to device|UiAutomation.*(connection.*(died|lost)|died|disconnected|not connected)|java[.]lang[.]IllegalStateException: UiAutomation not connected|android[.]os[.]DeadObjectException' "$log_file"
}

visual_audit_logcat_has_transport_drop_markers() {
  local logcat_file="$1"
  grep -Eq 'adbd[[:space:]].*(connection terminated|offline|read failed)|host-[0-9]+: read failed|UiAutomation service owner died' "$logcat_file"
}

visual_audit_should_retry_interrupted_instrumentation() {
  local status="$1"
  local instrumentation_log="$2"
  local logcat_file="$3"
  [[ "$status" -ne 0 ]] || return 1
  visual_audit_instrumentation_log_has_success "$instrumentation_log" && return 1
  visual_audit_instrumentation_log_has_failure_markers "$instrumentation_log" && return 1
  visual_audit_logcat_has_app_or_test_failure_markers "$logcat_file" && return 1
  visual_audit_instrumentation_log_has_transport_drop_markers "$instrumentation_log" ||
    visual_audit_logcat_has_transport_drop_markers "$logcat_file"
}

run_instrumentation_class() {
  local step_name="$1"
  local test_class="$2"
  local attempt instrumentation_status attempt_logcat
  for attempt in $(seq 1 "$INSTRUMENTATION_ATTEMPTS"); do
    local attempt_step="$step_name-attempt-$attempt"
    run_logged "$attempt_step-pre-force-stop" bash -lc \
      "'$ADB' shell am force-stop com.pocketshell.app >/dev/null 2>&1 || true; '$ADB' shell am force-stop com.pocketshell.app.test >/dev/null 2>&1 || true; sleep 5"
    "$ADB" logcat -c >/dev/null 2>&1 || true
    instrumentation_status=0
    run_logged "$attempt_step" \
      "$ADB" shell am instrument -w -r \
      -e additionalTestOutputDir "$DEVICE_OUTPUT_DIR" \
      -e class "$test_class" \
      com.pocketshell.app.test/androidx.test.runner.AndroidJUnitRunner ||
      instrumentation_status=$?
    cp "$RUN_DIR/$attempt_step.log" "$RUN_DIR/$step_name.log"
    attempt_logcat="$RUN_DIR/$attempt_step-logcat.txt"
    "$ADB" logcat -d -v threadtime -t 4000 > "$attempt_logcat" 2>&1 || true
    if visual_audit_instrumentation_log_has_success "$RUN_DIR/$attempt_step.log"; then
      return 0
    fi
    if [[ "$attempt" -eq "$INSTRUMENTATION_ATTEMPTS" ]]; then
      fail "$test_class did not report instrumentation success"
    fi
    if ! visual_audit_should_retry_interrupted_instrumentation \
      "$instrumentation_status" "$RUN_DIR/$attempt_step.log" "$attempt_logcat"; then
      fail "$test_class did not report instrumentation success"
    fi
    printf 'Retrying %s after adb transport/UIAutomation interruption; see %s and %s\n' \
      "$test_class" "$RUN_DIR/$attempt_step.log" "$attempt_logcat" >&2
    "$ADB" reconnect >/dev/null 2>&1 || true
    "$ADB" wait-for-device >/dev/null 2>&1 || true
    "$ADB" shell cmd package wait-for-handler >/dev/null 2>&1 || true
    sleep 8
  done
}

pull_device_screenshots() {
  local step_name="$1"
  mkdir -p "$RUN_DIR/screenshots"
  run_logged "$step_name" "$ADB" pull "$DEVICE_SCREENSHOT_DIR" "$RUN_DIR/screenshots/"
}

assert_screenshots_exist() {
  local step_name="$1"
  shift
  local screenshot_dir="$RUN_DIR/screenshots/walkthrough-visual-pass"
  local missing=()
  local file_name
  for file_name in "$@"; do
    [[ -s "$screenshot_dir/$file_name" ]] || missing+=("$file_name")
  done
  if [[ "${#missing[@]}" -ne 0 ]]; then
    printf '%s\n' "Missing screenshots after $step_name:" "${missing[@]}" >&2
    fail "Expected walkthrough screenshots were not pulled"
  fi
}

printf 'PocketShell walkthrough visual screenshot pass\n'
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
run_logged "08-clear-device-screenshots" "$ADB" shell rm -rf "$DEVICE_OUTPUT_DIR"
run_logged "09-stop-gradle-daemons" ./gradlew --stop
run_logged "10-build-walkthrough-visual-apks" \
  ./gradlew --no-daemon --no-build-cache :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
install_apks "11-install-walkthrough-visual-apks"
run_instrumentation_class "12-run-main-walkthrough-visual-instrumentation" "$MAIN_TEST_CLASS"
pull_device_screenshots "13-collect-main-device-screenshots"
assert_screenshots_exist "main walkthrough visual pass" "${MAIN_SCREENSHOTS[@]}"

run_instrumentation_class "14-run-composer-visual-instrumentation" "$COMPOSER_TEST_CLASS"
pull_device_screenshots "15-collect-composer-device-screenshots"
assert_screenshots_exist "composer visual pass" "${MAIN_SCREENSHOTS[@]}" "${COMPOSER_SCREENSHOTS[@]}"

printf '\nPASS: walkthrough visual screenshots captured\n'
printf 'Screenshots: %s/screenshots/walkthrough-visual-pass\n' "$RUN_DIR"
