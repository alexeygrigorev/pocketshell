#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Acquire an exclusive AVD lock so parallel-worktree gate runs serialize on the
# shared local Android emulator. Sibling `connectedAndroidTest` invocations
# from individual implementer/reviewer worktrees are intentionally NOT held by
# this lock — only the release-gate scripts that drive long sequential
# emulator workflows (see issue #182). When invoked from a parent gate script
# that already holds the lock, the env-var guard makes this a no-op. Skipped
# when the caller is just asking for --help so help stays cheap.
LOCK_FILE="${POCKETSHELL_AVD_LOCK_FILE:-$ROOT_DIR/build/.avd-lock}"
if [[ "${1:-}" != "--help" && "${1:-}" != "-h" && -z "${POCKETSHELL_AVD_LOCK_ACQUIRED:-}" ]]; then
  mkdir -p "$(dirname "$LOCK_FILE")"
  if ! flock -n "$LOCK_FILE" -c true; then
    echo "Another emulator-touching script holds the AVD lock ($LOCK_FILE); waiting..." >&2
  fi
  export POCKETSHELL_AVD_LOCK_ACQUIRED=1
  exec flock -o "$LOCK_FILE" "$0" "$@"
fi

ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
EMULATOR="${EMULATOR:-$ANDROID_SDK/emulator/emulator}"
AVD_NAME="${AVD_NAME:-test}"
COMPOSE_FILE="${COMPOSE_FILE:-tests/docker/docker-compose.yml}"
LOG_ROOT="${LOG_ROOT:-$ROOT_DIR/build/phone-walkthrough}"
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
PHONE_WALKTHROUGH_CLEAN_GENERATED="${PHONE_WALKTHROUGH_CLEAN_GENERATED:-${PHONE_DOGFOOD_CLEAN_GENERATED:-1}}"
LOGCAT_LINES="${LOGCAT_LINES:-4000}"
PHONE_WALKTHROUGH_INSTRUMENTATION_ATTEMPTS="${PHONE_WALKTHROUGH_INSTRUMENTATION_ATTEMPTS:-3}"
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
VISUAL_AUDIT_MAIN_TEST_CLASS="com.pocketshell.app.proof.WalkthroughVisualScreenshotTest"
VISUAL_AUDIT_COMPOSER_TEST_CLASS="com.pocketshell.app.composer.PromptComposerVisualScreenshotTest"
VISUAL_AUDIT_DEVICE_DIR="$DEVICE_OUTPUT_DIR/walkthrough-visual-pass"
VISUAL_AUDIT_SCREENSHOTS=(
  "01-host-list.png"
  "02-host-setup-folder-list.png"
  "03-terminal-session-input-controls.png"
  "04-snippets.png"
  "05b-composer-idle-draft.png"
  "06-composer-recording.png"
  "07-composer-transcribing.png"
)
TMUX_EXISTING_SESSION_TEST_SELECTOR="com.pocketshell.app.proof.EmulatorDockerSshSmokeTest#walkthroughJourneyOpensAppSessionAndRunsShellAndTmuxCommands"
TMUX_EXISTING_SESSION_DEVICE_DIR="$DEVICE_OUTPUT_DIR/issue78-phone-walkthrough"
TMUX_EXISTING_SESSION_REMOTE_NAME="claude-main"
TMUX_EXISTING_SESSION_SCREENSHOT="existing-tmux-output.png"
TMUX_EXISTING_SESSION_TRANSCRIPT="existing-tmux-output-visible-terminal.txt"
TMUX_EXISTING_SESSION_TIMINGS="tmux-existing-session.txt"
TMUX_EXISTING_SESSION_MIN_FOREGROUND_PIXELS="${TMUX_EXISTING_SESSION_MIN_FOREGROUND_PIXELS:-2000}"

SETUP_DETECTION_TEST_CLASS="com.pocketshell.app.bootstrap.HostBootstrapScenarioSuiteTest"
SETUP_DETECTION_DEVICE_DIR="$DEVICE_OUTPUT_DIR/setup-detection"
SETUP_DETECTION_PROFILES=(
  "ready"
  "uv-install"
  "uv-upgrade"
  "unsupported"
  "daemon-disabled"
  "user-local-path"
  "fish-user-local-path"
)
declare -A SETUP_DETECTION_SERVICES=(
  ["ready"]="bootstrap-ready"
  ["uv-install"]="bootstrap-uv-install"
  ["uv-upgrade"]="bootstrap-uv-upgrade"
  ["unsupported"]="bootstrap-unsupported"
  ["daemon-disabled"]="bootstrap-daemon-disabled"
  ["user-local-path"]="bootstrap-user-local-path"
  ["fish-user-local-path"]="bootstrap-fish-user-local-path"
)
declare -A SETUP_DETECTION_PORTS=(
  ["ready"]="2230"
  ["uv-install"]="2231"
  ["unsupported"]="2232"
  ["daemon-disabled"]="2233"
  ["user-local-path"]="2234"
  ["fish-user-local-path"]="2235"
  ["uv-upgrade"]="2236"
)
declare -A SETUP_DETECTION_METHODS=(
  ["ready"]="ready"
  ["uv-install"]="uvInstall"
  ["uv-upgrade"]="uvUpgrade"
  ["unsupported"]="unsupported"
  ["daemon-disabled"]="daemonDisabled"
  ["user-local-path"]="userLocalPath"
  ["fish-user-local-path"]="fishUserLocalPath"
)

if [[ -n "${ADB_SERIAL:-}" && -z "${ANDROID_SERIAL:-}" ]]; then
  export ANDROID_SERIAL="$ADB_SERIAL"
fi

# Issue #150: shared health-status polling helper. Provides
# `wait_for_container_healthy` so verify_docker_* below can wait on the
# compose `healthcheck:` block via `docker inspect` instead of SSH
# retry loops.
source "$ROOT_DIR/tests/docker/lib/wait-for-healthy.sh"

usage() {
  cat <<'USAGE'
Usage: scripts/phone-walkthrough.sh [scenario...]

Runs fast local phone-walkthrough journeys on an already-booted Android emulator
against deterministic Docker SSH fixtures. Artifacts are written under:

  build/phone-walkthrough/<run-id>/

Acquires an exclusive `flock` on `build/.avd-lock` (relative to the repo
root) before touching the emulator so that parallel-worktree gate runs
serialise on the shared local AVD. Released automatically on script exit.
See issue #182.

Supported scenarios:
  terminal-lab               isolated terminal lab: connect, type, stress layout/input
  tmux-existing-session      host picker -> existing tmux session -> terminal command
  visual-audit               release visual audit screenshots for reviewer inspection
  setup-detection            full bootstrap setup/detection matrix
  setup-detection:<profile>  one bootstrap profile by name
  all                        all implemented scenarios

Setup-detection profiles:
  ready
  uv-install
  uv-upgrade
  unsupported
  daemon-disabled
  user-local-path
  fish-user-local-path

Environment overrides:
  ANDROID_SDK=/home/alexey/Android/Sdk
  ADB=$ANDROID_SDK/platform-tools/adb
  EMULATOR=$ANDROID_SDK/emulator/emulator
  AVD_NAME=test
  ANDROID_SERIAL=<adb serial> or ADB_SERIAL=<adb serial>
  COMPOSE_FILE=tests/docker/docker-compose.yml
  LOG_ROOT=build/phone-walkthrough
  GRADLE_USER_HOME=build/phone-walkthrough/gradle-home
  RUN_ID=<custom artifact directory name>
  BUILD_APKS=0            reuse existing debug APKs
  PHONE_WALKTHROUGH_CLEAN_GENERATED=0
                          skip generated-output cleanup before APK build
  PHONE_WALKTHROUGH_INSTRUMENTATION_ATTEMPTS=3
                          retry interrupted setup-detection instrumentation runs
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
  printf '\nFAIL %s\n' "${CURRENT_SCENARIO:-phone-walkthrough}" >&2
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
  docker compose -f "$COMPOSE_FILE" logs --no-color --timestamps \
    bootstrap-ready \
    bootstrap-uv-install \
    bootstrap-uv-upgrade \
    bootstrap-unsupported \
    bootstrap-daemon-disabled \
    bootstrap-user-local-path \
    bootstrap-fish-user-local-path > "$LOG_DIR/docker-bootstrap.txt" 2>&1 || true
  timeout 20s "$ADB" devices -l > "$LOG_DIR/adb-devices-final.txt" 2>&1 || true
  timeout 20s "$ADB" shell getprop sys.boot_completed > "$LOG_DIR/adb-boot-completed-final.txt" 2>&1 || true
  timeout 20s "$ADB" logcat -d -v threadtime -t "$LOGCAT_LINES" > "$LOG_DIR/logcat.txt" 2>&1 || true
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
        trap - EXIT
        usage
        exit 0
        ;;
      all)
        SCENARIOS+=("terminal-lab")
        SCENARIOS+=("tmux-existing-session")
        SCENARIOS+=("visual-audit")
        SCENARIOS+=("setup-detection")
        ;;
      terminal-lab|tmux-existing-session|setup-detection|visual-audit)
        SCENARIOS+=("$scenario")
        ;;
      setup-detection:*)
        local profile="${scenario#setup-detection:}"
        if [[ -z "${SETUP_DETECTION_SERVICES[$profile]:-}" ]]; then
          usage >&2
          fail "unknown setup-detection profile '$profile'"
        fi
        SCENARIOS+=("$scenario")
        ;;
      *)
        usage >&2
        fail "unknown scenario '$scenario'"
        ;;
    esac
  done
}

setup_detection_profiles_for_scenario() {
  local scenario="$1"
  if [[ "$scenario" == setup-detection:* ]]; then
    printf '%s\n' "${scenario#setup-detection:}"
  else
    printf '%s\n' "${SETUP_DETECTION_PROFILES[@]}"
  fi
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

# Issue #150: wait on the compose `healthcheck:` block via
# `docker inspect`, not a host-side SSH retry loop. We keep a single
# follow-up SSH probe so the readiness log still records the same
# tool-availability sanity-check evidence reviewers look for.
verify_docker_agents() {
  run_logged "05-docker-agents-up" docker compose -f "$COMPOSE_FILE" up -d --build agents
  local log_file="$LOG_DIR/06-docker-ssh-readiness.log"
  if ! wait_for_container_healthy "$COMPOSE_FILE" agents "$log_file" 60; then
    tail -n 100 "$log_file" || true
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
      'printf "docker ssh ready "; for tool in tmux tmuxctl heru agent-log-explorer uv; do command -v "$tool"; done; tmux -V'
  } >> "$log_file" 2>&1 || {
    tail -n 100 "$log_file" || true
    fail "Docker SSH fixture reported healthy but follow-up probe failed at $SSH_USER@$SSH_HOST:$SSH_PORT"
  }
  printf '\n[06-docker-ssh-readiness]\nLog: %s\n' "$(relpath "$log_file")"
  tail -n 20 "$log_file"
}

verify_docker_bootstrap_profiles() {
  local profiles=("$@")
  local services=()
  local profile
  for profile in "${profiles[@]}"; do
    services+=("${SETUP_DETECTION_SERVICES[$profile]}")
  done

  run_logged "05-docker-bootstrap-up" docker compose -f "$COMPOSE_FILE" up -d --build "${services[@]}"

  for profile in "${profiles[@]}"; do
    local port="${SETUP_DETECTION_PORTS[$profile]}"
    local service="${SETUP_DETECTION_SERVICES[$profile]}"
    local log_file="$LOG_DIR/06-docker-bootstrap-readiness-$profile.log"
    if ! wait_for_container_healthy "$COMPOSE_FILE" "$service" "$log_file" 60; then
      tail -n 100 "$log_file" || true
      fail "Docker bootstrap fixture '$profile' did not become healthy at $SSH_USER@$SSH_HOST:$port"
    fi
    {
      printf '[%s] profile=%s health=healthy; running follow-up SSH sanity probe\n' "$(date -Is)" "$profile"
      ssh \
        -i "$SSH_KEY" \
        -p "$port" \
        -o BatchMode=yes \
        -o ConnectTimeout=3 \
        -o ConnectionAttempts=1 \
        -o StrictHostKeyChecking=no \
        -o UserKnownHostsFile=/dev/null \
        "$SSH_USER@$SSH_HOST" \
        'printf "bootstrap ssh ready "; uname -a'
    } >> "$log_file" 2>&1 || {
      tail -n 100 "$log_file" || true
      fail "Docker bootstrap fixture '$profile' reported healthy but follow-up SSH probe failed at $SSH_USER@$SSH_HOST:$port"
    }
    printf '\n[06-docker-bootstrap-readiness-%s]\nLog: %s\n' "$profile" "$(relpath "$log_file")"
    tail -n 10 "$log_file"
  done
}

ensure_remote_tmux_existing_session() {
  local session_name="$1"
  local log_file="$LOG_DIR/06b-docker-tmux-existing-session.log"
  : > "$log_file"
  local attempt
  for attempt in $(seq 1 30); do
    {
      printf '[%s] attempt=%s session=%s\n' "$(date -Is)" "$attempt" "$session_name"
      ssh \
        -i "$SSH_KEY" \
        -p "$SSH_PORT" \
        -o BatchMode=yes \
        -o ConnectTimeout=3 \
        -o ConnectionAttempts=1 \
        -o StrictHostKeyChecking=no \
        -o UserKnownHostsFile=/dev/null \
        "$SSH_USER@$SSH_HOST" \
        "tmux has-session -t '$session_name' 2>/dev/null || tmux new-session -d -s '$session_name' 'printf \"PocketShell walkthrough tmux fixture ready\\\\n\"; exec sh'; tmux display-message -p -t '$session_name' '#S'; tmux list-sessions"
    } >> "$log_file" 2>&1 && {
      printf '\n[06b-docker-tmux-existing-session]\nLog: %s\n' "$(relpath "$log_file")"
      tail -n 30 "$log_file"
      return 0
    }
    sleep 1
  done
  tail -n 100 "$log_file" || true
  fail "Docker SSH fixture did not expose tmux session '$session_name'"
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
    if [[ "$PHONE_WALKTHROUGH_CLEAN_GENERATED" = "1" ]]; then
      run_logged "07-clean-app-generated-build-outputs" rm -rf "$ROOT_DIR/app/build"
    else
      {
        printf '[%s] 07-clean-app-generated-build-outputs\n' "$(date -Is)"
        printf 'Skipped because PHONE_WALKTHROUGH_CLEAN_GENERATED=0\n'
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

assert_instrumentation_success() {
  local log_file="$1"
  local selector="$2"
  grep -q "INSTRUMENTATION_CODE: -1" "$log_file" &&
    grep -q "OK (" "$log_file" &&
    ! grep -q "FAILURES!!!" "$log_file" ||
    fail "$selector did not report instrumentation success"
}

should_retry_interrupted_instrumentation() {
  local status="$1"
  local instrumentation_log="$2"
  local logcat_file="$3"

  [[ "$status" -eq 255 ]] || return 1
  ! grep -q "INSTRUMENTATION_CODE: -1" "$instrumentation_log" || return 1
  ! grep -q "OK (" "$instrumentation_log" || return 1
  ! grep -q "FAILURES!!!" "$instrumentation_log" || return 1
  ! grep -q "INSTRUMENTATION_STATUS: stack=" "$instrumentation_log" || return 1
  ! grep -q "INSTRUMENTATION_RESULT: shortMsg=Process crashed" "$instrumentation_log" || return 1
  rg -q 'adbd .*connection terminated|adbd .*offline|host-[0-9]+: read failed|UiAutomation service owner died' "$logcat_file" || return 1
  if rg -q 'FATAL EXCEPTION|Process: com[.]pocketshell[.]app|Crash of app com[.]pocketshell[.]app|ANR in com[.]pocketshell[.]app|INSTRUMENTATION_RESULT: shortMsg=Process crashed' \
    "$logcat_file" "$instrumentation_log"; then
    rg -q 'UiAutomation service owner died' "$logcat_file" &&
      rg -q 'Error while (connecting|disconnecting) UiAutomation|Cannot call disconnect[(][)] while connecting UiAutomation' "$logcat_file" ||
      return 1
  fi
  return 0
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

run_visual_audit() {
  CURRENT_SCENARIO="visual-audit"
  local scenario_start_ms scenario_end_ms scenario_elapsed_ms
  local main_status=0
  local composer_status=0
  local device_artifact_dir="$DEVICE_ARTIFACT_ROOT/walkthrough-visual-pass"
  local screenshot_dir="$SCREENSHOT_ROOT/visual-audit"
  local timing_file="$TIMING_DIR/visual-audit.txt"

  scenario_start_ms="$(date +%s%3N)"
  verify_static_tools
  verify_emulator_booted
  verify_docker_agents
  build_and_install_apks

  run_logged "12-reset-visual-audit-artifacts" bash -lc \
    "'$ADB' shell am force-stop com.pocketshell.app >/dev/null 2>&1 || true; '$ADB' shell am force-stop com.pocketshell.app.test >/dev/null 2>&1 || true; '$ADB' shell input keyevent HOME >/dev/null 2>&1 || true; '$ADB' shell rm -rf '$VISUAL_AUDIT_DEVICE_DIR'"
  run_logged "13-clear-logcat" "$ADB" logcat -c

  local main_start_ms main_end_ms composer_start_ms composer_end_ms
  main_start_ms="$(date +%s%3N)"
  run_logged "14-run-visual-audit-main-instrumentation" \
    "$ADB" shell am instrument -w -r \
    -e additionalTestOutputDir "$DEVICE_OUTPUT_DIR" \
    -e class "$VISUAL_AUDIT_MAIN_TEST_CLASS" \
    com.pocketshell.app.test/androidx.test.runner.AndroidJUnitRunner ||
    main_status=$?
  main_end_ms="$(date +%s%3N)"

  mkdir -p "$DEVICE_ARTIFACT_ROOT" "$screenshot_dir"
  run_logged "15-pull-visual-audit-main-artifacts" "$ADB" pull "$VISUAL_AUDIT_DEVICE_DIR" "$DEVICE_ARTIFACT_ROOT/" || true

  composer_start_ms="$(date +%s%3N)"
  run_logged "16-run-visual-audit-composer-instrumentation" \
    "$ADB" shell am instrument -w -r \
    -e additionalTestOutputDir "$DEVICE_OUTPUT_DIR" \
    -e class "$VISUAL_AUDIT_COMPOSER_TEST_CLASS" \
    com.pocketshell.app.test/androidx.test.runner.AndroidJUnitRunner ||
    composer_status=$?
  composer_end_ms="$(date +%s%3N)"

  run_logged "17-pull-visual-audit-composer-artifacts" "$ADB" pull "$VISUAL_AUDIT_DEVICE_DIR" "$DEVICE_ARTIFACT_ROOT/" || true
  if [[ -d "$device_artifact_dir" ]]; then
    run_logged "18-visual-audit-artifact-file-info" file "$device_artifact_dir"/* || true
  fi

  cp "$LOG_DIR/14-run-visual-audit-main-instrumentation.log" "$LOG_DIR/visual-audit-main-instrumentation.txt"
  cp "$LOG_DIR/16-run-visual-audit-composer-instrumentation.log" "$LOG_DIR/visual-audit-composer-instrumentation.txt"
  {
    printf 'main_instrumentation_ms=%s\n' "$((main_end_ms - main_start_ms))"
    printf 'composer_instrumentation_ms=%s\n' "$((composer_end_ms - composer_start_ms))"
  } > "$timing_file"
  "$ADB" logcat -d -v threadtime -t "$LOGCAT_LINES" > "$LOG_DIR/logcat.txt" 2>&1 || true
  rg -n 'WALKTHROUGH_SCREENSHOT|WalkthroughVisualScreenshotTest|PromptComposerVisualScreenshotTest' \
    "$LOG_DIR/logcat.txt" \
    "$LOG_DIR/visual-audit-main-instrumentation.txt" \
    "$LOG_DIR/visual-audit-composer-instrumentation.txt" > "$LOG_DIR/visual-audit-filtered-log.txt" 2>&1 || true
  rg -n 'FATAL EXCEPTION|Process: com[.]pocketshell[.]app|Crash of app com[.]pocketshell[.]app|ANR in com[.]pocketshell[.]app|INSTRUMENTATION_RESULT: shortMsg=Process crashed' \
    "$LOG_DIR/logcat.txt" \
    "$LOG_DIR/visual-audit-main-instrumentation.txt" \
    "$LOG_DIR/visual-audit-composer-instrumentation.txt" > "$LOG_DIR/crash-diagnostics.txt" 2>&1 || true

  if [[ "$main_status" -ne 0 ]]; then
    fail "$VISUAL_AUDIT_MAIN_TEST_CLASS instrumentation command exited with $main_status"
  fi
  if [[ "$composer_status" -ne 0 ]]; then
    fail "$VISUAL_AUDIT_COMPOSER_TEST_CLASS instrumentation command exited with $composer_status"
  fi
  assert_instrumentation_success "$LOG_DIR/visual-audit-main-instrumentation.txt" "$VISUAL_AUDIT_MAIN_TEST_CLASS"
  assert_instrumentation_success "$LOG_DIR/visual-audit-composer-instrumentation.txt" "$VISUAL_AUDIT_COMPOSER_TEST_CLASS"

  local missing=()
  local screenshot
  for screenshot in "${VISUAL_AUDIT_SCREENSHOTS[@]}"; do
    if [[ -s "$device_artifact_dir/$screenshot" ]]; then
      cp "$device_artifact_dir/$screenshot" "$screenshot_dir/$screenshot"
    else
      missing+=("$screenshot")
    fi
  done
  if [[ "${#missing[@]}" -ne 0 ]]; then
    printf '%s\n' "Missing visual-audit screenshots:" "${missing[@]}" >&2
    fail "expected visual-audit screenshots were not pulled"
  fi

  scenario_end_ms="$(date +%s%3N)"
  scenario_elapsed_ms=$((scenario_end_ms - scenario_start_ms))
  printf 'scenario_elapsed_ms=%s\n' "$scenario_elapsed_ms" >> "$timing_file"
  assert_no_crash_diagnostics

  printf '\nPASS visual-audit\n'
  printf 'artifacts: %s\n' "$(relpath "$RUN_DIR")"
  printf 'screenshots:\n'
  for screenshot in "${VISUAL_AUDIT_SCREENSHOTS[@]}"; do
    printf '  %s\n' "$(relpath "$screenshot_dir/$screenshot")"
  done
  printf 'device artifacts:\n'
  printf '  %s\n' "$(relpath "$device_artifact_dir")"
  printf 'timing:\n'
  sed 's/^/  /' "$timing_file"
  printf 'logs:\n'
  printf '  %s\n' "$(relpath "$LOG_DIR/logcat.txt")"
  printf '  %s\n' "$(relpath "$LOG_DIR/visual-audit-main-instrumentation.txt")"
  printf '  %s\n' "$(relpath "$LOG_DIR/visual-audit-composer-instrumentation.txt")"
  printf '  %s\n' "$(relpath "$LOG_DIR/visual-audit-filtered-log.txt")"
  printf '  %s\n' "$(relpath "$LOG_DIR/docker.txt")"
  printf '  %s\n' "$(relpath "$TIMING_DIR/commands.tsv")"
}

run_tmux_existing_session() {
  CURRENT_SCENARIO="tmux-existing-session"
  local scenario_start_ms scenario_end_ms scenario_elapsed_ms
  local instrumentation_status=0
  local pulled_artifact_dir="$DEVICE_ARTIFACT_ROOT/issue78-phone-walkthrough"
  local scenario_artifact_dir="$DEVICE_ARTIFACT_ROOT/tmux-existing-session"
  local screenshot_dir="$SCREENSHOT_ROOT/tmux-existing-session"
  local timing_file="$TIMING_DIR/$TMUX_EXISTING_SESSION_TIMINGS"

  scenario_start_ms="$(date +%s%3N)"
  verify_static_tools
  verify_emulator_booted
  verify_docker_agents
  ensure_remote_tmux_existing_session "$TMUX_EXISTING_SESSION_REMOTE_NAME"
  build_and_install_apks

  run_logged "12-reset-tmux-existing-session-artifacts" bash -lc \
    "'$ADB' shell am force-stop com.pocketshell.app >/dev/null 2>&1 || true; '$ADB' shell am force-stop com.pocketshell.app.test >/dev/null 2>&1 || true; '$ADB' shell input keyevent HOME >/dev/null 2>&1 || true; '$ADB' shell rm -rf '$TMUX_EXISTING_SESSION_DEVICE_DIR'"
  run_logged "13-clear-logcat" "$ADB" logcat -c

  run_logged "14-run-tmux-existing-session-instrumentation" \
    "$ADB" shell am instrument -w -r \
    -e additionalTestOutputDir "$DEVICE_OUTPUT_DIR" \
    -e class "$TMUX_EXISTING_SESSION_TEST_SELECTOR" \
    com.pocketshell.app.test/androidx.test.runner.AndroidJUnitRunner ||
    instrumentation_status=$?

  cp "$LOG_DIR/14-run-tmux-existing-session-instrumentation.log" "$LOG_DIR/instrumentation.txt"
  "$ADB" logcat -d -v threadtime -t "$LOGCAT_LINES" > "$LOG_DIR/logcat.txt" 2>&1 || true
  rg -n 'ISSUE78_|PocketShellWalkthrough' \
    "$LOG_DIR/logcat.txt" "$LOG_DIR/instrumentation.txt" > "$LOG_DIR/tmux-existing-session-filtered-log.txt" 2>&1 || true
  rg -n 'FATAL EXCEPTION|Process: com[.]pocketshell[.]app|Crash of app com[.]pocketshell[.]app|ANR in com[.]pocketshell[.]app|INSTRUMENTATION_RESULT: shortMsg=Process crashed' \
    "$LOG_DIR/logcat.txt" "$LOG_DIR/instrumentation.txt" > "$LOG_DIR/crash-diagnostics.txt" 2>&1 || true

  mkdir -p "$DEVICE_ARTIFACT_ROOT" "$scenario_artifact_dir" "$screenshot_dir"
  run_logged "15-pull-tmux-existing-session-artifacts" "$ADB" pull "$TMUX_EXISTING_SESSION_DEVICE_DIR" "$DEVICE_ARTIFACT_ROOT/" || true
  if [[ -d "$pulled_artifact_dir" ]]; then
    run_logged "16-tmux-existing-session-artifact-file-info" file "$pulled_artifact_dir"/* || true
  fi

  if [[ "$instrumentation_status" -ne 0 ]]; then
    fail "$TMUX_EXISTING_SESSION_TEST_SELECTOR instrumentation command exited with $instrumentation_status"
  fi
  grep -q "INSTRUMENTATION_CODE: -1" "$LOG_DIR/instrumentation.txt" &&
    grep -q "OK (" "$LOG_DIR/instrumentation.txt" &&
    ! grep -q "FAILURES!!!" "$LOG_DIR/instrumentation.txt" ||
    fail "$TMUX_EXISTING_SESSION_TEST_SELECTOR did not report instrumentation success"

  local source_screenshot="$pulled_artifact_dir/issue78-existing-tmux-output.png"
  local source_transcript="$pulled_artifact_dir/issue78-existing-tmux-transcript.txt"
  local source_timings="$pulled_artifact_dir/issue78-timings.txt"
  local missing=()
  [[ -s "$source_screenshot" ]] || missing+=("issue78-existing-tmux-output.png")
  [[ -s "$source_transcript" ]] || missing+=("issue78-existing-tmux-transcript.txt")
  [[ -s "$source_timings" ]] || missing+=("issue78-timings.txt")
  if [[ "${#missing[@]}" -ne 0 ]]; then
    printf '%s\n' "Missing tmux-existing-session artifacts:" "${missing[@]}" >&2
    fail "expected tmux-existing-session artifacts were not pulled"
  fi

  grep -q 'issue78-complete-' "$source_transcript" ||
    fail "terminal transcript did not include the completed tmux-existing-session marker"
  grep -q "tmux_session=$TMUX_EXISTING_SESSION_REMOTE_NAME" "$source_timings" ||
    fail "timing artifact did not verify tmux session '$TMUX_EXISTING_SESSION_REMOTE_NAME'"

  local foreground_pixels
  foreground_pixels="$(awk -F= '/^visible_terminal_foreground_pixels=/ { print $2 }' "$source_timings" | tail -n 1)"
  if [[ -z "$foreground_pixels" || ! "$foreground_pixels" =~ ^[0-9]+$ ]]; then
    fail "timing artifact did not include visible terminal foreground pixel evidence"
  fi
  if (( foreground_pixels < TMUX_EXISTING_SESSION_MIN_FOREGROUND_PIXELS )); then
    fail "terminal screenshot appears blank or header-only; foreground_pixels=$foreground_pixels"
  fi

  cp "$source_screenshot" "$scenario_artifact_dir/$TMUX_EXISTING_SESSION_SCREENSHOT"
  cp "$source_transcript" "$scenario_artifact_dir/$TMUX_EXISTING_SESSION_TRANSCRIPT"
  cp "$source_timings" "$scenario_artifact_dir/$TMUX_EXISTING_SESSION_TIMINGS"
  cp "$source_screenshot" "$screenshot_dir/$TMUX_EXISTING_SESSION_SCREENSHOT"
  cp "$source_transcript" "$screenshot_dir/$TMUX_EXISTING_SESSION_TRANSCRIPT"
  cp "$source_timings" "$timing_file"

  scenario_end_ms="$(date +%s%3N)"
  scenario_elapsed_ms=$((scenario_end_ms - scenario_start_ms))
  printf 'scenario_elapsed_ms=%s\n' "$scenario_elapsed_ms" >> "$timing_file"
  assert_no_crash_diagnostics

  printf '\nPASS tmux-existing-session\n'
  printf 'artifacts: %s\n' "$(relpath "$RUN_DIR")"
  printf 'screenshot:\n'
  printf '  %s\n' "$(relpath "$screenshot_dir/$TMUX_EXISTING_SESSION_SCREENSHOT")"
  printf 'transcript:\n'
  printf '  %s\n' "$(relpath "$screenshot_dir/$TMUX_EXISTING_SESSION_TRANSCRIPT")"
  printf 'timing:\n'
  sed 's/^/  /' "$timing_file"
  printf 'logs:\n'
  printf '  %s\n' "$(relpath "$LOG_DIR/logcat.txt")"
  printf '  %s\n' "$(relpath "$LOG_DIR/instrumentation.txt")"
  printf '  %s\n' "$(relpath "$LOG_DIR/tmux-existing-session-filtered-log.txt")"
  printf '  %s\n' "$(relpath "$LOG_DIR/docker.txt")"
  printf '  %s\n' "$(relpath "$TIMING_DIR/commands.tsv")"
}

run_setup_detection_profile() {
  local profile="$1"
  CURRENT_SCENARIO="setup-detection:$profile"

  local method="${SETUP_DETECTION_METHODS[$profile]}"
  local service="${SETUP_DETECTION_SERVICES[$profile]}"
  local scenario_start_ms scenario_end_ms scenario_elapsed_ms
  local instrumentation_status=0
  local device_artifact_parent="$DEVICE_ARTIFACT_ROOT/setup-detection"
  local device_artifact_dir="$device_artifact_parent/$profile"
  local screenshot_dir="$SCREENSHOT_ROOT/setup-detection/$profile"
  local timing_file="$TIMING_DIR/setup-detection-$profile.txt"
  local instrumentation_log="$LOG_DIR/instrumentation-setup-detection-$profile.txt"
  local logcat_file="$LOG_DIR/logcat-setup-detection-$profile.txt"
  local crash_file="$LOG_DIR/crash-diagnostics-setup-detection-$profile.txt"
  local docker_log="$LOG_DIR/docker-setup-detection-$profile.txt"
  local max_attempts="$PHONE_WALKTHROUGH_INSTRUMENTATION_ATTEMPTS"

  scenario_start_ms="$(date +%s%3N)"

  if [[ ! "$max_attempts" =~ ^[1-9][0-9]*$ ]]; then
    fail "PHONE_WALKTHROUGH_INSTRUMENTATION_ATTEMPTS must be a positive integer"
  fi

  local attempt
  for ((attempt = 1; attempt <= max_attempts; attempt++)); do
    instrumentation_status=0
    rm -rf "$device_artifact_dir"
    rm -rf "$screenshot_dir"
    run_logged "12-reset-setup-detection-$profile-artifacts" bash -lc \
      "'$ADB' shell am force-stop com.pocketshell.app >/dev/null 2>&1 || true; '$ADB' shell am force-stop com.pocketshell.app.test >/dev/null 2>&1 || true; '$ADB' shell input keyevent HOME >/dev/null 2>&1 || true; '$ADB' shell rm -rf '$SETUP_DETECTION_DEVICE_DIR/$profile'"
    run_logged "13-clear-logcat-setup-detection-$profile" "$ADB" logcat -c

    run_logged "14-run-setup-detection-$profile-instrumentation" \
      "$ADB" shell am instrument -w -r \
      -e additionalTestOutputDir "$DEVICE_OUTPUT_DIR" \
      -e pocketshellBootstrapScenarios true \
      -e class "$SETUP_DETECTION_TEST_CLASS#$method" \
      com.pocketshell.app.test/androidx.test.runner.AndroidJUnitRunner ||
      instrumentation_status=$?

    cp "$LOG_DIR/14-run-setup-detection-$profile-instrumentation.log" "$instrumentation_log"
    cp "$instrumentation_log" "$LOG_DIR/instrumentation.txt"
    if [[ "$instrumentation_status" -ne 0 ]]; then
      sleep 2
    fi
    "$ADB" logcat -d -v threadtime -t "$LOGCAT_LINES" > "$logcat_file" 2>&1 || true
    cp "$logcat_file" "$LOG_DIR/logcat.txt"
    docker compose -f "$COMPOSE_FILE" logs --no-color --timestamps "$service" > "$docker_log" 2>&1 || true
    rg -n 'FATAL EXCEPTION|Process: com[.]pocketshell[.]app|Crash of app com[.]pocketshell[.]app|ANR in com[.]pocketshell[.]app|INSTRUMENTATION_RESULT: shortMsg=Process crashed' \
      "$logcat_file" "$instrumentation_log" > "$crash_file" 2>&1 || true
    cp "$crash_file" "$LOG_DIR/crash-diagnostics.txt"

    mkdir -p "$device_artifact_parent" "$screenshot_dir"
    run_logged "15-pull-setup-detection-$profile-artifacts" "$ADB" pull "$SETUP_DETECTION_DEVICE_DIR/$profile" "$device_artifact_parent/" || true
    if [[ -d "$device_artifact_dir" ]]; then
      run_logged "16-setup-detection-$profile-artifact-file-info" file "$device_artifact_dir"/* || true
    fi

    if [[ "$instrumentation_status" -eq 0 || "$attempt" -eq "$max_attempts" ]]; then
      break
    fi
    if ! should_retry_interrupted_instrumentation "$instrumentation_status" "$instrumentation_log" "$logcat_file"; then
      break
    fi
    cp "$instrumentation_log" "$LOG_DIR/instrumentation-setup-detection-$profile-attempt-$attempt.txt"
    cp "$logcat_file" "$LOG_DIR/logcat-setup-detection-$profile-attempt-$attempt.txt"
    printf 'Retrying setup-detection:%s after interrupted instrumentation attempt %s/%s\n' \
      "$profile" "$attempt" "$max_attempts" | tee -a "$LOG_DIR/setup-detection-$profile-retries.txt"
    run_logged "17-cleanup-setup-detection-$profile-interrupted-attempt-$attempt" bash -lc \
      "'$ADB' reconnect >/dev/null 2>&1 || true; '$ADB' wait-for-device >/dev/null 2>&1 || true; '$ADB' shell am force-stop com.pocketshell.app >/dev/null 2>&1 || true; '$ADB' shell am force-stop com.pocketshell.app.test >/dev/null 2>&1 || true; '$ADB' shell input keyevent HOME >/dev/null 2>&1 || true; sleep 2"
  done

  if [[ "$instrumentation_status" -ne 0 ]]; then
    fail "$SETUP_DETECTION_TEST_CLASS#$method instrumentation command exited with $instrumentation_status"
  fi
  grep -q "INSTRUMENTATION_CODE: -1" "$instrumentation_log" &&
    grep -q "OK (" "$instrumentation_log" &&
    ! grep -q "FAILURES!!!" "$instrumentation_log" ||
    fail "$SETUP_DETECTION_TEST_CLASS#$method did not report instrumentation success"

  local screenshot_count=0
  if [[ -d "$device_artifact_dir" ]]; then
    find "$device_artifact_dir" -maxdepth 1 -type f -name '*.png' -exec cp {} "$screenshot_dir/" \;
    screenshot_count="$(find "$screenshot_dir" -maxdepth 1 -type f -name '*.png' | wc -l | tr -d ' ')"
  fi
  [[ "$screenshot_count" -gt 0 ]] || fail "expected setup-detection screenshots for $profile were not pulled"
  [[ -s "$device_artifact_dir/timings.txt" ]] || fail "expected setup-detection timings for $profile were not pulled"
  [[ -s "$device_artifact_dir/remote-probes.txt" ]] || fail "expected setup-detection remote probes for $profile were not pulled"

  cp "$device_artifact_dir/timings.txt" "$timing_file"
  scenario_end_ms="$(date +%s%3N)"
  scenario_elapsed_ms=$((scenario_end_ms - scenario_start_ms))
  printf 'scenario_elapsed_ms=%s\n' "$scenario_elapsed_ms" >> "$timing_file"
  assert_no_crash_diagnostics

  printf '\nPASS setup-detection:%s\n' "$profile"
  printf 'artifacts: %s\n' "$(relpath "$RUN_DIR")"
  printf 'screenshots: %s\n' "$(relpath "$screenshot_dir")"
  printf 'remote probes: %s\n' "$(relpath "$device_artifact_dir/remote-probes.txt")"
  printf 'timing:\n'
  sed 's/^/  /' "$timing_file"
  printf 'logs:\n'
  printf '  %s\n' "$(relpath "$logcat_file")"
  printf '  %s\n' "$(relpath "$instrumentation_log")"
  printf '  %s\n' "$(relpath "$docker_log")"
  printf '  %s\n' "$(relpath "$TIMING_DIR/commands.tsv")"
}

run_setup_detection() {
  local scenario="$1"
  CURRENT_SCENARIO="$scenario"
  local -a selected_profiles
  mapfile -t selected_profiles < <(setup_detection_profiles_for_scenario "$scenario")

  verify_static_tools
  verify_emulator_booted
  verify_docker_bootstrap_profiles "${selected_profiles[@]}"
  build_and_install_apks

  local profile
  for profile in "${selected_profiles[@]}"; do
    run_setup_detection_profile "$profile"
  done
}

run_unimplemented() {
  CURRENT_SCENARIO="$1"
  fail "scenario '$1' is planned for issue #80 but is not implemented yet; run terminal-lab"
}

select_scenarios "$@"

printf 'PocketShell phone walkthrough\n'
printf 'run-id: %s\n' "$RUN_ID"
printf 'artifacts: %s\n' "$(relpath "$RUN_DIR")"
printf 'scenarios: %s\n' "${SCENARIOS[*]}"
printf 'gradle-user-home: %s\n' "$(relpath "$GRADLE_USER_HOME")"

for scenario in "${SCENARIOS[@]}"; do
  case "$scenario" in
    terminal-lab)
      run_terminal_lab
      ;;
    tmux-existing-session)
      run_tmux_existing_session
      ;;
    setup-detection|setup-detection:*)
      run_setup_detection "$scenario"
      ;;
    visual-audit)
      run_visual_audit
      ;;
  esac
done
