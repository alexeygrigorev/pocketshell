#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
EMULATOR="${EMULATOR:-$ANDROID_SDK/emulator/emulator}"
AVD_NAME="${AVD_NAME:-test}"
COMPOSE_FILE="${COMPOSE_FILE:-tests/docker/docker-compose.yml}"
LOG_ROOT="${LOG_ROOT:-$ROOT_DIR/build/phone-dogfood}"
if [[ "$LOG_ROOT" != /* ]]; then
  LOG_ROOT="$ROOT_DIR/$LOG_ROOT"
fi
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RUN_DIR="$LOG_ROOT/$RUN_ID"
LOG_DIR="$RUN_DIR/logs"
SCREENSHOT_ROOT="$RUN_DIR/screenshots"
TIMING_DIR="$RUN_DIR/timings"
DEVICE_ARTIFACT_ROOT="$RUN_DIR/device-artifacts"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$LOG_ROOT/gradle-home}"
BUILD_APKS="${BUILD_APKS:-1}"
PHONE_DOGFOOD_CLEAN_GENERATED="${PHONE_DOGFOOD_CLEAN_GENERATED:-1}"
LOGCAT_LINES="${LOGCAT_LINES:-4000}"
SSH_KEY="${SSH_KEY:-$ROOT_DIR/tests/docker/test_key}"
SSH_HOST="${SSH_HOST:-127.0.0.1}"
SSH_PORT="${SSH_PORT:-2222}"
SSH_USER="${SSH_USER:-testuser}"
DEVICE_OUTPUT_DIR="/sdcard/Android/media/com.pocketshell.app/additional_test_output"
APP_APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

TERMINAL_LAB_TEST_CLASS="com.pocketshell.app.terminal.TerminalLabDockerTest"
TERMINAL_LAB_DEVICE_DIR="$DEVICE_OUTPUT_DIR/terminal-lab"
TERMINAL_LAB_SCREENSHOTS=(
  "01-connected-prompt.png"
  "02-pwd-ls.png"
  "03-long-path-git-status.png"
  "04-backspace-repeat.png"
)

if [[ -n "${ADB_SERIAL:-}" && -z "${ANDROID_SERIAL:-}" ]]; then
  export ANDROID_SERIAL="$ADB_SERIAL"
fi

usage() {
  cat <<'USAGE'
Usage: scripts/phone-dogfood.sh [scenario...]

Runs fast local phone-dogfood journeys on an already-booted Android emulator
against deterministic Docker SSH fixtures. Artifacts are written under:

  build/phone-dogfood/<run-id>/

Supported scenarios:
  terminal-lab   isolated terminal lab: connect, type, stress layout/input
  all            currently aliases to terminal-lab

Planned scenarios are accepted by issue #80 but not implemented yet:
  tmux-existing-session
  setup-detection
  visual-audit

Environment overrides:
  ANDROID_SDK=/home/alexey/Android/Sdk
  ADB=$ANDROID_SDK/platform-tools/adb
  EMULATOR=$ANDROID_SDK/emulator/emulator
  AVD_NAME=test
  ANDROID_SERIAL=<adb serial> or ADB_SERIAL=<adb serial>
  COMPOSE_FILE=tests/docker/docker-compose.yml
  LOG_ROOT=build/phone-dogfood
  GRADLE_USER_HOME=build/phone-dogfood/gradle-home
  RUN_ID=<custom artifact directory name>
  BUILD_APKS=0            reuse existing debug APKs
  PHONE_DOGFOOD_CLEAN_GENERATED=0
                          skip generated-output cleanup before APK build
  LOGCAT_LINES=4000       bounded logcat lines collected at exit
  SSH_KEY=tests/docker/test_key
  SSH_HOST=127.0.0.1
  SSH_PORT=2222
  SSH_USER=testuser
USAGE
}

relpath() {
  local path="$1"
  if [[ "$path" == "$ROOT_DIR/"* ]]; then
    printf '%s\n' "${path#"$ROOT_DIR/"}"
  else
    printf '%s\n' "$path"
  fi
}

fail() {
  local message="$1"
  printf '\nFAIL %s\n' "${CURRENT_SCENARIO:-phone-dogfood}" >&2
  printf 'reason: %s\n' "$message" >&2
  printf 'artifacts: %s\n' "$(relpath "$RUN_DIR")" >&2
  exit 1
}

require_executable() {
  local path="$1"
  local label="$2"
  [[ -x "$path" ]] || fail "$label is not executable at $path"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 was not found on PATH"
}

run_logged() {
  local name="$1"
  shift
  local log_file="$LOG_DIR/$name.log"
  local start_ms end_ms elapsed_ms status
  start_ms="$(date +%s%3N)"
  printf '\n[%s]\n' "$name"
  printf 'Command:'
  printf ' %q' "$@"
  printf '\nLog: %s\n' "$(relpath "$log_file")"
  set +e
  {
    printf '[%s] %s\n' "$(date -Is)" "$name"
    printf 'Command:'
    printf ' %q' "$@"
    printf '\n\n'
    "$@"
  } 2>&1 | tee "$log_file"
  status="${PIPESTATUS[0]}"
  set -e
  end_ms="$(date +%s%3N)"
  elapsed_ms=$((end_ms - start_ms))
  printf '%s\tstatus=%s\telapsed_ms=%s\n' "$name" "$status" "$elapsed_ms" >> "$TIMING_DIR/commands.tsv"
  return "$status"
}

mkdir -p "$RUN_DIR" "$LOG_DIR" "$SCREENSHOT_ROOT" "$TIMING_DIR" "$DEVICE_ARTIFACT_ROOT"
: > "$TIMING_DIR/commands.tsv"

collect_diagnostics() {
  local exit_code=$?
  {
    printf 'exit_code=%s\n' "$exit_code"
    printf 'run_dir=%s\n' "$RUN_DIR"
    printf 'timestamp=%s\n' "$(date -Is)"
    printf 'scenarios=%s\n' "${SCENARIOS[*]:-}"
  } > "$RUN_DIR/exit-status.txt"

  docker compose -f "$COMPOSE_FILE" ps > "$LOG_DIR/docker-compose-ps.txt" 2>&1 || true
  docker compose -f "$COMPOSE_FILE" logs --no-color --timestamps agents > "$LOG_DIR/docker.txt" 2>&1 || true
  "$ADB" devices -l > "$LOG_DIR/adb-devices-final.txt" 2>&1 || true
  "$ADB" shell getprop sys.boot_completed > "$LOG_DIR/adb-boot-completed-final.txt" 2>&1 || true
  "$ADB" logcat -d -v threadtime -t "$LOGCAT_LINES" > "$LOG_DIR/logcat.txt" 2>&1 || true
  rg -n 'FATAL EXCEPTION|Process: com[.]pocketshell[.]app|Crash of app com[.]pocketshell[.]app|ANR in com[.]pocketshell[.]app|INSTRUMENTATION_RESULT: shortMsg=Process crashed' \
    "$LOG_DIR/logcat.txt" "$LOG_DIR"/*.log > "$LOG_DIR/crash-diagnostics.txt" 2>&1 || true

  if [[ "$exit_code" -ne 0 ]]; then
    printf '\nDiagnostics collected in %s\n' "$(relpath "$RUN_DIR")" >&2
  fi
}
trap collect_diagnostics EXIT

select_scenarios() {
  if [[ "$#" -eq 0 ]]; then
    SCENARIOS=("terminal-lab")
    return
  fi

  SCENARIOS=()
  local scenario
  for scenario in "$@"; do
    case "$scenario" in
      -h|--help)
        usage
        exit 0
        ;;
      all)
        SCENARIOS+=("terminal-lab")
        ;;
      terminal-lab|tmux-existing-session|setup-detection|visual-audit)
        SCENARIOS+=("$scenario")
        ;;
      *)
        usage >&2
        fail "unknown scenario '$scenario'"
        ;;
    esac
  done
}

verify_static_tools() {
  require_executable "$ADB" "adb"
  require_executable "$EMULATOR" "emulator"
  require_command docker
  require_command ssh
  require_command rg
  [[ -f "$SSH_KEY" ]] || fail "SSH key was not found at $SSH_KEY"
  chmod 600 "$SSH_KEY"

  run_logged "00-adb-version" "$ADB" version
  run_logged "01-available-avds" "$EMULATOR" -list-avds
  if ! "$EMULATOR" -list-avds | grep -Fxq "$AVD_NAME"; then
    fail "AVD '$AVD_NAME' was not listed by $EMULATOR -list-avds"
  fi
  run_logged "02-docker-version" docker version
  run_logged "03-docker-compose-config" docker compose -f "$COMPOSE_FILE" config --quiet
}

verify_emulator_booted() {
  local device_count boot_completed
  run_logged "04-adb-devices" "$ADB" devices -l
  device_count="$("$ADB" devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')"
  if [[ "$device_count" -eq 0 ]]; then
    fail "no booted adb device is connected; start the '$AVD_NAME' emulator first"
  fi
  if [[ "$device_count" -gt 1 && -z "${ANDROID_SERIAL:-}" ]]; then
    fail "multiple adb devices are connected; set ANDROID_SERIAL or ADB_SERIAL"
  fi
  boot_completed="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [[ "$boot_completed" != "1" ]]; then
    fail "adb device is connected but sys.boot_completed=$boot_completed; wait for the emulator to finish booting"
  fi
}

verify_docker_agents() {
  run_logged "05-docker-agents-up" docker compose -f "$COMPOSE_FILE" up -d --build agents
  local log_file="$LOG_DIR/06-docker-ssh-readiness.log"
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
        'printf "docker ssh ready "; for tool in tmux tmuxctl heru agent-log-explorer uv; do command -v "$tool"; done; tmux -V'
    } >> "$log_file" 2>&1 && {
      printf '\n[06-docker-ssh-readiness]\nLog: %s\n' "$(relpath "$log_file")"
      tail -n 20 "$log_file"
      return 0
    }
    sleep 1
  done
  tail -n 100 "$log_file" || true
  fail "Docker SSH fixture did not become ready at $SSH_USER@$SSH_HOST:$SSH_PORT"
}

install_apk() {
  local package="$1"
  local apk="$2"
  local output status
  set +e
  output="$("$ADB" install -r -d -t "$apk" 2>&1)"
  status=$?
  set -e
  printf '%s\n' "$output"
  if [[ "$status" -eq 0 ]]; then
    return 0
  fi
  if printf '%s\n' "$output" | grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE'; then
    "$ADB" uninstall "$package" >/dev/null 2>&1 || true
    "$ADB" install -r -d -t "$apk"
    return 0
  fi
  return "$status"
}

build_and_install_apks() {
  if [[ "$BUILD_APKS" = "1" ]]; then
    if [[ "$PHONE_DOGFOOD_CLEAN_GENERATED" = "1" ]]; then
      run_logged "07-clean-app-generated-build-outputs" rm -rf "$ROOT_DIR/app/build"
    else
      {
        printf '[%s] 07-clean-app-generated-build-outputs\n' "$(date -Is)"
        printf 'Skipped because PHONE_DOGFOOD_CLEAN_GENERATED=0\n'
      } | tee "$LOG_DIR/07-clean-app-generated-build-outputs.log"
    fi
    run_logged "08-build-apks" ./gradlew --no-daemon --no-build-cache --no-parallel :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
  else
    [[ -f "$APP_APK" ]] || fail "BUILD_APKS=0 but app APK is missing at $APP_APK"
    [[ -f "$TEST_APK" ]] || fail "BUILD_APKS=0 but androidTest APK is missing at $TEST_APK"
    {
      printf '[%s] 08-build-apks\n' "$(date -Is)"
      printf 'Skipped because BUILD_APKS=0\n'
      printf 'App APK: %s\n' "$APP_APK"
      printf 'Test APK: %s\n' "$TEST_APK"
    } | tee "$LOG_DIR/08-build-apks.log"
  fi

  run_logged "09-reset-app-state-before-install" bash -lc \
    "'$ADB' shell am force-stop com.pocketshell.app >/dev/null 2>&1 || true; '$ADB' shell am force-stop com.pocketshell.app.test >/dev/null 2>&1 || true; '$ADB' shell pm clear com.pocketshell.app >/dev/null 2>&1 || true; '$ADB' shell pm clear com.pocketshell.app.test >/dev/null 2>&1 || true"
  run_logged "10-install-apks" bash -lc "$(declare -f install_apk); ADB='$ADB'; install_apk com.pocketshell.app '$APP_APK'; install_apk com.pocketshell.app.test '$TEST_APK'"
  run_logged "11-wait-package-manager-idle" bash -lc \
    "'$ADB' shell cmd package wait-for-handler --timeout 60000 >/dev/null 2>&1 || true; '$ADB' shell cmd package wait-for-background-handler --timeout 60000 >/dev/null 2>&1 || true; for i in {1..30}; do '$ADB' shell pm path com.pocketshell.app >/dev/null && '$ADB' shell pm path com.pocketshell.app.test >/dev/null && '$ADB' shell pm list instrumentation | grep -q '^instrumentation:com.pocketshell.app.test/androidx.test.runner.AndroidJUnitRunner' && exit 0; sleep 1; done; '$ADB' shell pm path com.pocketshell.app; '$ADB' shell pm path com.pocketshell.app.test; '$ADB' shell pm list instrumentation; exit 1"
}

assert_no_crash_diagnostics() {
  if [[ -s "$LOG_DIR/crash-diagnostics.txt" ]]; then
    fail "crash diagnostics were found in $(relpath "$LOG_DIR/crash-diagnostics.txt")"
  fi
}

run_terminal_lab() {
  CURRENT_SCENARIO="terminal-lab"
  local scenario_start_ms scenario_end_ms scenario_elapsed_ms
  local instrumentation_status=0
  local device_artifact_dir="$DEVICE_ARTIFACT_ROOT/terminal-lab"
  local screenshot_dir="$SCREENSHOT_ROOT/terminal-lab"
  local timing_file="$TIMING_DIR/terminal-lab.txt"

  scenario_start_ms="$(date +%s%3N)"
  verify_static_tools
  verify_emulator_booted
  verify_docker_agents
  build_and_install_apks

  run_logged "12-reset-terminal-lab-artifacts" bash -lc \
    "'$ADB' shell am force-stop com.pocketshell.app >/dev/null 2>&1 || true; '$ADB' shell am force-stop com.pocketshell.app.test >/dev/null 2>&1 || true; '$ADB' shell input keyevent HOME >/dev/null 2>&1 || true; '$ADB' shell rm -rf '$TERMINAL_LAB_DEVICE_DIR'"
  run_logged "13-clear-logcat" "$ADB" logcat -c

  run_logged "14-run-terminal-lab-instrumentation" \
    "$ADB" shell am instrument -w -r \
    -e additionalTestOutputDir "$DEVICE_OUTPUT_DIR" \
    -e class "$TERMINAL_LAB_TEST_CLASS" \
    com.pocketshell.app.test/androidx.test.runner.AndroidJUnitRunner ||
    instrumentation_status=$?

  cp "$LOG_DIR/14-run-terminal-lab-instrumentation.log" "$LOG_DIR/instrumentation.txt"
  "$ADB" logcat -d -v threadtime -t "$LOGCAT_LINES" > "$LOG_DIR/logcat.txt" 2>&1 || true
  rg -n 'FATAL EXCEPTION|Process: com[.]pocketshell[.]app|Crash of app com[.]pocketshell[.]app|ANR in com[.]pocketshell[.]app|INSTRUMENTATION_RESULT: shortMsg=Process crashed' \
    "$LOG_DIR/logcat.txt" "$LOG_DIR/instrumentation.txt" > "$LOG_DIR/crash-diagnostics.txt" 2>&1 || true

  mkdir -p "$DEVICE_ARTIFACT_ROOT" "$screenshot_dir"
  run_logged "15-pull-terminal-lab-artifacts" "$ADB" pull "$TERMINAL_LAB_DEVICE_DIR" "$DEVICE_ARTIFACT_ROOT/" || true
  if [[ -d "$device_artifact_dir" ]]; then
    run_logged "16-terminal-lab-artifact-file-info" file "$device_artifact_dir"/* || true
  fi

  if [[ "$instrumentation_status" -ne 0 ]]; then
    fail "$TERMINAL_LAB_TEST_CLASS instrumentation command exited with $instrumentation_status"
  fi
  grep -q "INSTRUMENTATION_CODE: -1" "$LOG_DIR/instrumentation.txt" &&
    grep -q "OK (" "$LOG_DIR/instrumentation.txt" &&
    ! grep -q "FAILURES!!!" "$LOG_DIR/instrumentation.txt" ||
    fail "$TERMINAL_LAB_TEST_CLASS did not report instrumentation success"

  local missing=()
  local screenshot
  for screenshot in "${TERMINAL_LAB_SCREENSHOTS[@]}"; do
    if [[ -s "$device_artifact_dir/$screenshot" ]]; then
      cp "$device_artifact_dir/$screenshot" "$screenshot_dir/$screenshot"
    else
      missing+=("$screenshot")
    fi
  done
  [[ -s "$device_artifact_dir/timings.txt" ]] || missing+=("timings.txt")
  if [[ "${#missing[@]}" -ne 0 ]]; then
    printf '%s\n' "Missing terminal-lab artifacts:" "${missing[@]}" >&2
    fail "expected terminal-lab artifacts were not pulled"
  fi

  cp "$device_artifact_dir/timings.txt" "$timing_file"
  scenario_end_ms="$(date +%s%3N)"
  scenario_elapsed_ms=$((scenario_end_ms - scenario_start_ms))
  printf 'scenario_elapsed_ms=%s\n' "$scenario_elapsed_ms" >> "$timing_file"
  assert_no_crash_diagnostics

  printf '\nPASS terminal-lab\n'
  printf 'artifacts: %s\n' "$(relpath "$RUN_DIR")"
  printf 'screenshots:\n'
  for screenshot in "${TERMINAL_LAB_SCREENSHOTS[@]}"; do
    printf '  %s\n' "$(relpath "$screenshot_dir/$screenshot")"
  done
  printf 'timing:\n'
  sed 's/^/  /' "$timing_file"
  printf 'logs:\n'
  printf '  %s\n' "$(relpath "$LOG_DIR/logcat.txt")"
  printf '  %s\n' "$(relpath "$LOG_DIR/instrumentation.txt")"
  printf '  %s\n' "$(relpath "$LOG_DIR/docker.txt")"
  printf '  %s\n' "$(relpath "$TIMING_DIR/commands.tsv")"
}

run_unimplemented() {
  CURRENT_SCENARIO="$1"
  fail "scenario '$1' is planned for issue #80 but is not implemented yet; run terminal-lab"
}

select_scenarios "$@"

printf 'PocketShell phone dogfood\n'
printf 'run-id: %s\n' "$RUN_ID"
printf 'artifacts: %s\n' "$(relpath "$RUN_DIR")"
printf 'scenarios: %s\n' "${SCENARIOS[*]}"
printf 'gradle-user-home: %s\n' "$(relpath "$GRADLE_USER_HOME")"

for scenario in "${SCENARIOS[@]}"; do
  case "$scenario" in
    terminal-lab)
      run_terminal_lab
      ;;
    tmux-existing-session|setup-detection|visual-audit)
      run_unimplemented "$scenario"
      ;;
  esac
done
