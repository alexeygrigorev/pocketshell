#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

source "$ROOT_DIR/scripts/lib/avd-lock.sh"
pocketshell_acquire_avd_lock "$ROOT_DIR" "${1:-}"

ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
EMULATOR="${EMULATOR:-$ANDROID_SDK/emulator/emulator}"
PYTHON3="${PYTHON3:-python3}"
AVD_NAME="${AVD_NAME:-test}"
LOG_ROOT="${LOG_ROOT:-$ROOT_DIR/build/pre-release-confidence-gate}"
if [[ "$LOG_ROOT" != /* ]]; then
  LOG_ROOT="$ROOT_DIR/$LOG_ROOT"
fi
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RUN_DIR="$LOG_ROOT/$RUN_ID"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$LOG_ROOT/gradle-home}"
GRADLE_FLAGS="${GRADLE_FLAGS:---no-daemon --no-build-cache --no-parallel --max-workers=2}"
GATE_ISOLATED_WORKTREE="${GATE_ISOLATED_WORKTREE:-1}"
COMPOSE_FILE="${COMPOSE_FILE:-tests/docker/docker-compose.yml}"
SSH_KEY="${SSH_KEY:-tests/docker/test_key}"
APK_PATH="${APK_PATH:-app/build/outputs/apk/debug/app-debug.apk}"
TEST_APK_PATH="${TEST_APK_PATH:-app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"
APP_WALKTHROUGH_INSTRUMENTATION_ATTEMPTS="${APP_WALKTHROUGH_INSTRUMENTATION_ATTEMPTS:-3}"
APP_WALKTHROUGH_TRANSPORT_RECOVERY_ATTEMPTS="${APP_WALKTHROUGH_TRANSPORT_RECOVERY_ATTEMPTS:-3}"
ISSUE_261_STALE_DB_LAUNCH_ATTEMPTS="${ISSUE_261_STALE_DB_LAUNCH_ATTEMPTS:-3}"
CORE_TERMINAL_CONNECTED_ATTEMPTS="${CORE_TERMINAL_CONNECTED_ATTEMPTS:-2}"
PRE_RELEASE_MANAGE_EMULATOR="${PRE_RELEASE_MANAGE_EMULATOR:-0}"
PRE_RELEASE_EMULATOR_START_ARGS="${PRE_RELEASE_EMULATOR_START_ARGS:--no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-load -no-snapshot-save}"

APP_WALKTHROUGH_TESTS=(
  "com.pocketshell.app.composer.PromptComposerSmokeTest#recordingAndTranscribingStatesAreVisible"
  "com.pocketshell.app.composer.PromptComposerSmokeTest#typedDraftSendEnterReachesDockerShell"
  "com.pocketshell.app.snippets.SnippetTerminalE2eTest#tappingCommandSnippetSendsInputToDockerShell"
  "com.pocketshell.app.session.InlineDictationUiTest#recordingStateShowsAmplitudeWaveformOnInlineMicSlot"
  "com.pocketshell.app.session.InlineDictationUiTest#transcribingStateShowsSpinnerAndBlocksDuplicateTap"
  "com.pocketshell.app.proof.EmulatorDockerSshSmokeTest#debugAppConnectsToDockerAgentTargetViaEmulatorHostAlias"
  "com.pocketshell.app.proof.EmulatorDockerSshSmokeTest#walkthroughJourneyOpensAppSessionAndRunsShellAndTmuxCommands"
)

usage() {
  cat <<'USAGE'
Usage: scripts/pre-release-confidence-gate.sh

Runs the local APK pre-release-confidence gate:
  - compile/unit checks
  - deterministic Docker agent target
  - emulator readiness with explicit Android SDK paths
  - #261 stale Room DB launch sanity
  - focused connected walkthrough tests
  - debug APK build and emulator install sanity

Acquires an exclusive `flock` on `build/.avd-lock` (relative to the repo
root) before touching the emulator so that parallel-worktree gate runs
serialise on the shared local AVD. Released automatically on script exit.
See issue #182.

Environment overrides:
  ANDROID_SDK=/home/alexey/Android/Sdk
  ADB=$ANDROID_SDK/platform-tools/adb
  EMULATOR=$ANDROID_SDK/emulator/emulator
  PYTHON3=python3
  AVD_NAME=test
  LOG_ROOT=build/pre-release-confidence-gate
  GRADLE_USER_HOME=build/pre-release-confidence-gate/gradle-home
  GRADLE_FLAGS="--no-daemon --no-build-cache --no-parallel --max-workers=2"
  GATE_ISOLATED_WORKTREE=1
  COMPOSE_FILE=tests/docker/docker-compose.yml
  APK_PATH=app/build/outputs/apk/debug/app-debug.apk
  TEST_APK_PATH=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  APP_WALKTHROUGH_INSTRUMENTATION_ATTEMPTS=3
  APP_WALKTHROUGH_TRANSPORT_RECOVERY_ATTEMPTS=3
  ISSUE_261_STALE_DB_LAUNCH_ATTEMPTS=3
  CORE_TERMINAL_CONNECTED_ATTEMPTS=2
  PRE_RELEASE_MANAGE_EMULATOR=0
  PRE_RELEASE_EMULATOR_START_ARGS="-no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-load -no-snapshot-save"
USAGE
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

mkdir -p "$RUN_DIR"

if [[ "$GATE_ISOLATED_WORKTREE" != "0" && -z "${POCKETSHELL_GATE_ISOLATED_COPY:-}" ]]; then
  isolated_root="$RUN_DIR/worktree"
  printf 'Preparing isolated worktree copy: %s\n' "$isolated_root"
  rsync -a --delete \
    --exclude='.git/' \
    --exclude='.gradle/' \
    --exclude='build/' \
    "$ROOT_DIR/" "$isolated_root/"
  export POCKETSHELL_GATE_ISOLATED_COPY=1
  export POCKETSHELL_GATE_SOURCE_ROOT="$ROOT_DIR"
  export LOG_ROOT RUN_ID GRADLE_USER_HOME GRADLE_FLAGS
  exec "$isolated_root/scripts/pre-release-confidence-gate.sh" "$@"
fi

source "$ROOT_DIR/scripts/lib/app-version.sh"
APP_VERSION_NAME="$(pocketshell_app_version_name "$ROOT_DIR")"
export POCKETSHELL_AGENT_FIXTURE_VERSION="$APP_VERSION_NAME"

SUMMARY_PATH="$RUN_DIR/summary.txt"
SUMMARY_WRITTEN=0
GATE_RESULT="FAIL"
GATE_RESULT_MESSAGE="FAIL"
FAILURE_MESSAGE=""
FAILING_STEP=""
FAILING_LOG_PATH=""
FAILURE_DIAGNOSTICS_PATH=""
FAILURE_LOGCAT_PATH=""
EMULATOR_SERIAL="unknown"
APP_WALKTHROUGH_INSTALL_STATUS="not_run"
FINAL_INSTALL_STATUS="not_run"
ISSUE_261_STALE_DB_STATUS="not_run"
ISSUE_261_STALE_DB_LOGCAT="$RUN_DIR/issue-261-stale-db-launch-logcat.log"
STEP_NAMES=()
STEP_STATUSES=()
STEP_LOGS=()
STEP_COMMANDS=()
FOCUSED_SELECTORS=("${APP_WALKTHROUGH_TESTS[@]}")
FOCUSED_STATUSES=()
FOCUSED_LOGS=()
FOCUSED_DIAGNOSTICS=()
FOCUSED_LOGCATS=()

for _selector in "${FOCUSED_SELECTORS[@]}"; do
  FOCUSED_STATUSES+=("not_run")
  FOCUSED_LOGS+=("")
  FOCUSED_DIAGNOSTICS+=("")
  FOCUSED_LOGCATS+=("")
done
unset _selector

commit_sha() {
  local git_root="${POCKETSHELL_GATE_SOURCE_ROOT:-$ROOT_DIR}"
  git -C "$git_root" rev-parse HEAD 2>/dev/null || printf 'unknown'
}

update_emulator_serial() {
  if [[ -x "$ADB" ]]; then
    EMULATOR_SERIAL="$("$ADB" get-serialno 2>/dev/null | tr -d '\r' || true)"
    [[ -n "$EMULATOR_SERIAL" ]] || EMULATOR_SERIAL="unknown"
  fi
}

set_focused_status() {
  local selector="$1"
  local status="$2"
  local log_file="${3:-}"
  local diagnostics_file="${4:-}"
  local logcat_file="${5:-}"

  local i
  for i in "${!FOCUSED_SELECTORS[@]}"; do
    if [[ "${FOCUSED_SELECTORS[$i]}" == "$selector" ]]; then
      FOCUSED_STATUSES[$i]="$status"
      [[ -n "$log_file" ]] && FOCUSED_LOGS[$i]="$log_file"
      [[ -n "$diagnostics_file" ]] && FOCUSED_DIAGNOSTICS[$i]="$diagnostics_file"
      [[ -n "$logcat_file" ]] && FOCUSED_LOGCATS[$i]="$logcat_file"
      return 0
    fi
  done
}

write_summary() {
  local exit_status="${1:-0}"
  if [[ "$SUMMARY_WRITTEN" == "1" ]]; then
    return 0
  fi
  SUMMARY_WRITTEN=1

  update_emulator_serial
  mkdir -p "$RUN_DIR"

  if [[ "$exit_status" -eq 0 && "$GATE_RESULT" == "PASS" ]]; then
    GATE_RESULT_MESSAGE="PASS: pre-release confidence gate completed"
  elif [[ "$GATE_RESULT" != "PASS" ]]; then
    GATE_RESULT="FAIL"
    GATE_RESULT_MESSAGE="FAIL"
  fi

  {
    printf 'PocketShell pre-release confidence gate summary\n'
    printf 'Generated: %s\n' "$(date -Is)"
    printf 'Result: %s\n' "$GATE_RESULT_MESSAGE"
    printf 'Exit status: %s\n' "$exit_status"
    printf 'Commit SHA: %s\n' "$(commit_sha)"
    printf 'Run ID: %s\n' "$RUN_ID"
    printf 'Run directory: %s\n' "$RUN_DIR"
    if [[ -n "${POCKETSHELL_GATE_SOURCE_ROOT:-}" ]]; then
      printf 'Source workspace: %s\n' "$POCKETSHELL_GATE_SOURCE_ROOT"
      printf 'Isolated worktree: %s\n' "$ROOT_DIR"
    fi
    printf 'APK path: %s\n' "$APK_PATH"
    printf 'Test APK path: %s\n' "$TEST_APK_PATH"
    printf 'Emulator serial: %s\n' "$EMULATOR_SERIAL"
    printf 'Docker compose file: %s\n' "$COMPOSE_FILE"
    printf 'Docker profile/service: agents\n'
    printf 'Docker SSH target: 127.0.0.1:2222\n'
    printf 'Focused app APK install status: %s\n' "$APP_WALKTHROUGH_INSTALL_STATUS"
    printf 'Final install status: %s\n' "$FINAL_INSTALL_STATUS"
    printf 'Issue #261 stale DB launch status: %s\n' "$ISSUE_261_STALE_DB_STATUS"
    printf 'Issue #261 stale DB logcat: %s\n' "$ISSUE_261_STALE_DB_LOGCAT"
    if [[ "$GATE_RESULT" != "PASS" ]]; then
      printf 'Failing step: %s\n' "${FAILING_STEP:-unknown}"
      printf 'Failure message: %s\n' "${FAILURE_MESSAGE:-unknown}"
      printf 'Failing step log: %s\n' "${FAILING_LOG_PATH:-unknown}"
      printf 'Failure diagnostics: %s\n' "${FAILURE_DIAGNOSTICS_PATH:-unknown}"
      printf 'Failure logcat: %s\n' "${FAILURE_LOGCAT_PATH:-unknown}"
    fi

    printf '\nSteps:\n'
    local i
    for i in "${!STEP_NAMES[@]}"; do
      printf -- '- name: %s\n' "${STEP_NAMES[$i]}"
      printf '  status: %s\n' "${STEP_STATUSES[$i]}"
      printf '  log: %s\n' "${STEP_LOGS[$i]}"
      printf '  command: %s\n' "${STEP_COMMANDS[$i]}"
    done

    printf '\nFocused selectors:\n'
    for i in "${!FOCUSED_SELECTORS[@]}"; do
      printf -- '- selector: %s\n' "${FOCUSED_SELECTORS[$i]}"
      printf '  status: %s\n' "${FOCUSED_STATUSES[$i]}"
      [[ -n "${FOCUSED_LOGS[$i]}" ]] && printf '  log: %s\n' "${FOCUSED_LOGS[$i]}"
      [[ -n "${FOCUSED_DIAGNOSTICS[$i]}" ]] && printf '  diagnostics: %s\n' "${FOCUSED_DIAGNOSTICS[$i]}"
      [[ -n "${FOCUSED_LOGCATS[$i]}" ]] && printf '  logcat: %s\n' "${FOCUSED_LOGCATS[$i]}"
    done
  } > "$SUMMARY_PATH"
}

on_exit() {
  local exit_status="$?"
  set +e
  write_summary "$exit_status"
  pocketshell_release_avd_lock
}

trap on_exit EXIT

log_path_for() {
  local name="$1"
  printf '%s/%02d-%s.log' "$RUN_DIR" "$STEP_INDEX" "$name"
}

STEP_INDEX=0
run_step() {
  local name="$1"
  shift
  STEP_INDEX=$((STEP_INDEX + 1))
  local log_file
  log_file="$(log_path_for "$name")"
  local command_string=""
  local arg
  local quoted_arg
  for arg in "$@"; do
    printf -v quoted_arg '%q' "$arg"
    command_string+=" $quoted_arg"
  done
  command_string="${command_string# }"

  STEP_NAMES+=("$name")
  STEP_STATUSES+=("running")
  STEP_LOGS+=("$log_file")
  STEP_COMMANDS+=("$command_string")
  local step_array_index=$((${#STEP_NAMES[@]} - 1))

  printf '\n[%02d] %s\n' "$STEP_INDEX" "$name"
  printf 'Command:'
  printf ' %q' "$@"
  printf '\nLog: %s\n' "$log_file"

  set +e
  {
    printf '[%s] %s\n' "$(date -Is)" "$name"
    printf 'Command:'
    printf ' %q' "$@"
    printf '\n\n'
    "$@"
  } 2>&1 | tee "$log_file"
  local status=$?
  set -e

  if [[ "$status" -eq 0 ]]; then
    STEP_STATUSES[$step_array_index]="passed"
    case "$name" in
      install-app-walkthrough-apks)
        APP_WALKTHROUGH_INSTALL_STATUS="passed"
        ;;
      install-debug-apk)
        FINAL_INSTALL_STATUS="passed"
        ;;
      issue-261-stale-db-launch)
        ISSUE_261_STALE_DB_STATUS="passed"
        ;;
    esac
  else
    STEP_STATUSES[$step_array_index]="failed"
    FAILING_STEP="$name"
    FAILING_LOG_PATH="$log_file"
    FAILURE_MESSAGE="step '$name' failed with status $status"
    case "$name" in
      emulator-readiness)
        FAILURE_MESSAGE="infrastructure readiness failed before connected tests; see emulator-readiness diagnostics"
        FAILURE_DIAGNOSTICS_PATH="$RUN_DIR/emulator-readiness-diagnostics.log"
        ;;
      install-app-walkthrough-apks)
        APP_WALKTHROUGH_INSTALL_STATUS="failed"
        ;;
      install-debug-apk)
        FINAL_INSTALL_STATUS="failed"
        ;;
      issue-261-stale-db-launch)
        ISSUE_261_STALE_DB_STATUS="failed"
        FAILURE_LOGCAT_PATH="$ISSUE_261_STALE_DB_LOGCAT"
        ;;
    esac
  fi

  return "$status"
}

run_bash_step() {
  local name="$1"
  local script="$2"
  run_step "$name" bash -lc "$script"
}

core_terminal_connected_input_script() {
  cat <<CORE_TERMINAL_SCRIPT
set -euo pipefail

wait_for_android_media_storage() {
  for i in {1..60}; do
    if '$ADB' shell 'mkdir -p /sdcard/Android/media && test -d /sdcard/Android/media' >/dev/null 2>&1; then
      printf 'Android shared media storage is ready: /sdcard/Android/media\n'
      return 0
    fi
    sleep 1
  done
  printf 'Android shared media storage did not become ready: /sdcard/Android/media\n' >&2
  '$ADB' devices >&2 || true
  '$ADB' shell 'ls -ld /sdcard /sdcard/Android /sdcard/Android/media' >&2 || true
  return 1
}

core_terminal_connected_should_retry() {
  local attempt_log="\$1"
  local logcat_file="\$2"
  grep -q 'Starting 0 tests' "\$attempt_log" || return 1
  grep -q 'Test run failed to complete[.] No test results' "\$attempt_log" || return 1
  {
    grep -qi 'Connection refused' "\$attempt_log" ||
      grep -Eq 'adbd[[:space:]].*(connection terminated|offline|read failed)|host-[0-9]+: read failed' "\$logcat_file"
  }
}

wait_for_android_media_storage

for attempt in \$(seq 1 '$CORE_TERMINAL_CONNECTED_ATTEMPTS'); do
  attempt_log='$RUN_DIR/connected-terminal-input-attempt-'\$attempt'.log'
  attempt_logcat='$RUN_DIR/connected-terminal-input-attempt-'\$attempt'-logcat.log'
  printf 'Core-terminal connected test attempt %s/%s\n' "\$attempt" '$CORE_TERMINAL_CONNECTED_ATTEMPTS'
  '$ADB' logcat -c >/dev/null 2>&1 || true

  set +e
  ./gradlew $GRADLE_FLAGS :shared:core-terminal:connectedDebugAndroidTest --stacktrace 2>&1 | tee "\$attempt_log"
  status=\${PIPESTATUS[0]}
  set -e

  '$ADB' logcat -d -v time -t 5000 > "\$attempt_logcat" 2>&1 || true

  if [ "\$status" -eq 0 ]; then
    exit 0
  fi

  if [ "\$attempt" -lt '$CORE_TERMINAL_CONNECTED_ATTEMPTS' ] &&
    core_terminal_connected_should_retry "\$attempt_log" "\$attempt_logcat"; then
    printf 'Core-terminal connected test produced UTP no-results/transport cleanup failure on attempt %s; retrying.\n' "\$attempt" >&2
    '$ADB' reconnect >/dev/null 2>&1 || true
    '$ADB' wait-for-device >/dev/null 2>&1 || true
    for package in com.termux.view.test com.pocketshell.app.test com.pocketshell.app; do
      '$ADB' shell am force-stop "\$package" >/dev/null 2>&1 || true
    done
    '$ADB' shell cmd package wait-for-handler --timeout 60000 >/dev/null 2>&1 || true
    '$ADB' shell cmd package wait-for-background-handler --timeout 60000 >/dev/null 2>&1 || true
    wait_for_android_media_storage
    sleep 2
    continue
  fi

  exit "\$status"
done
CORE_TERMINAL_SCRIPT
}

emulator_readiness_script() {
  cat <<READINESS_SCRIPT
set -euo pipefail

diagnostics='$RUN_DIR/emulator-readiness-diagnostics.log'
managed_emulator_log='$RUN_DIR/emulator-readiness-managed-emulator.log'
manage_emulator='$PRE_RELEASE_MANAGE_EMULATOR'
avd_name='$AVD_NAME'

list_emulator_processes() {
  ps -eo pid=,comm=,args= |
    awk -v avd="\$avd_name" '
      {
        command_name = \$2
      }
      command_name == "emulator" {
        for (i = 3; i <= NF; i++) {
          if (\$i == "-avd" && (i + 1) <= NF && \$(i + 1) == avd) {
            print
            next
          }
          if (\$i == ("-avd=" avd)) {
            print
            next
          }
        }
      }
      command_name ~ /^qemu-system/ {
        for (i = 3; i <= NF; i++) {
          if (index(\$i, avd) > 0) {
            print
            next
          }
        }
      }
    '
}

record_diagnostics() {
  {
    printf 'timestamp=%s\n' "\$(date -Is)"
    printf 'adb=%s\n' '$ADB'
    printf 'emulator=%s\n' '$EMULATOR'
    printf 'avd=%s\n' "\$avd_name"
    printf 'manage_emulator=%s\n' "\$manage_emulator"
    printf 'managed_start_args=%s\n' '$PRE_RELEASE_EMULATOR_START_ARGS'
    printf '\n== adb devices ==\n'
    '$ADB' devices -l || true
    printf '\n== adb get-state ==\n'
    '$ADB' get-state || true
    printf '\n== adb get-serialno ==\n'
    '$ADB' get-serialno || true
    printf '\n== emulator processes ==\n'
    list_emulator_processes || true
    printf '\n== managed emulator log tail ==\n'
    if [ -f "\$managed_emulator_log" ]; then
      tail -n 120 "\$managed_emulator_log" || true
    else
      printf 'no managed emulator log at %s\n' "\$managed_emulator_log"
    fi
  } > "\$diagnostics" 2>&1
}

has_adb_device() {
  '$ADB' devices | awk 'NR > 1 && \$2 == "device" { found = 1 } END { exit found ? 0 : 1 }'
}

has_emulator_process() {
  [ -n "\$(list_emulator_processes)" ]
}

boot_completed() {
  state=\$('$ADB' shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  [ "\$state" = 1 ]
}

start_managed_emulator() {
  if [ "\$manage_emulator" != "1" ]; then
    return 1
  fi
  if has_adb_device || has_emulator_process; then
    return 0
  fi

  printf 'No ADB devices and no emulator process for AVD %s; starting managed emulator.\n' "\$avd_name" >&2
  printf '[%s] starting managed emulator for AVD %s\n' "\$(date -Is)" "\$avd_name" >> "\$managed_emulator_log"
  nohup '$EMULATOR' -avd "\$avd_name" $PRE_RELEASE_EMULATOR_START_ARGS >> "\$managed_emulator_log" 2>&1 &
}

'$ADB' devices -l || true
start_managed_emulator || true

for i in {1..90}; do
  if boot_completed; then
    record_diagnostics
    printf 'Emulator readiness confirmed: sys.boot_completed=1\n'
    exit 0
  fi
  if ! has_adb_device && ! has_emulator_process; then
    if ! start_managed_emulator; then
      record_diagnostics
      printf 'Infrastructure readiness failure: no ADB devices and no emulator process for AVD %s before connected tests.\n' "\$avd_name" >&2
      printf 'Diagnostics: %s\n' "\$diagnostics" >&2
      exit 2
    fi
  fi
  sleep 2
done

record_diagnostics
printf 'Infrastructure readiness failure: AVD %s did not report sys.boot_completed=1 before connected tests.\n' "\$avd_name" >&2
printf 'Diagnostics: %s\n' "\$diagnostics" >&2
exit 2
READINESS_SCRIPT
}

fail() {
  FAILURE_MESSAGE="$1"
  [[ -n "$FAILING_STEP" ]] || FAILING_STEP="preflight"
  printf '\nFAIL: %s\nLogs: %s\n' "$1" "$RUN_DIR" >&2
  exit 1
}

require_executable() {
  local path="$1"
  local label="$2"
  [[ -x "$path" ]] || fail "$label is not executable at $path"
}

require_command_or_executable() {
  local command_or_path="$1"
  local label="$2"
  if [[ "$command_or_path" == */* ]]; then
    require_executable "$command_or_path" "$label"
  elif ! command -v "$command_or_path" >/dev/null 2>&1; then
    fail "$label is not available on PATH as $command_or_path"
  fi
}

reset_app_packages_script() {
  cat <<RESET_SCRIPT
set -euo pipefail
package_installed() {
  '$ADB' shell pm path "\$1" >/dev/null 2>&1
}
wait_package_manager_idle() {
  '$ADB' shell cmd package wait-for-handler --timeout 60000 >/dev/null 2>&1 || true
  '$ADB' shell cmd package wait-for-background-handler --timeout 60000 >/dev/null 2>&1 || true
}
for package in com.pocketshell.app.test com.pocketshell.app; do
  '$ADB' shell am force-stop "\$package" >/dev/null 2>&1 || true
done
for package in com.pocketshell.app.test com.pocketshell.app; do
  if package_installed "\$package"; then
    printf 'Clearing package data without uninstalling: %s\n' "\$package"
    '$ADB' shell pm clear "\$package" || true
  else
    printf 'Package not installed: %s\n' "\$package"
  fi
done
wait_package_manager_idle
for package in com.pocketshell.app.test com.pocketshell.app; do
  '$ADB' shell am force-stop "\$package" >/dev/null 2>&1 || true
done
printf 'Focused app walkthrough package state reset without package deletion\n'
RESET_SCRIPT
}

install_app_walkthrough_apks_script() {
  cat <<INSTALL_SCRIPT
set -euo pipefail
wait_package_manager_idle() {
  '$ADB' shell cmd package wait-for-handler --timeout 60000 >/dev/null 2>&1 || true
  '$ADB' shell cmd package wait-for-background-handler --timeout 60000 >/dev/null 2>&1 || true
}
packages_present() {
  for package in com.pocketshell.app com.pocketshell.app.test; do
    '$ADB' shell pm path "\$package" >/dev/null || return 1
  done
}
post_install_removal_seen() {
  '$ADB' logcat -d -v time -t 1000 2>/dev/null |
    grep -E 'PACKAGE_FULLY_REMOVED|PACKAGE_REMOVED|deletePackageX' |
    grep -E 'com[.]pocketshell[.]app([.]test)?' >/dev/null
}
uninstall_with_idle_wait() {
  local package="\$1"
  if '$ADB' shell pm path "\$package" >/dev/null 2>&1; then
    printf 'Uninstall fallback for incompatible package: %s\n' "\$package"
    '$ADB' uninstall "\$package" || true
  fi
  wait_package_manager_idle
  for i in {1..60}; do
    if ! '$ADB' shell pm path "\$package" >/dev/null 2>&1; then
      wait_package_manager_idle
      printf 'Package removed after fallback uninstall: %s\n' "\$package"
      return 0
    fi
    sleep 1
  done
  printf 'Package still installed after fallback uninstall wait: %s\n' "\$package" >&2
  exit 1
}
install_or_fallback_uninstall() {
  local package="\$1"
  local apk="\$2"
  local output
  set +e
  output=\$('$ADB' install -r -d -t "\$apk" 2>&1)
  local status=\$?
  set -e
  printf '%s\n' "\$output"
  if [ "\$status" -eq 0 ]; then
    wait_package_manager_idle
    return 0
  fi
  if printf '%s\n' "\$output" | grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE'; then
    uninstall_with_idle_wait "\$package"
    '$ADB' install -r -d -t "\$apk"
    wait_package_manager_idle
    return 0
  fi
  exit "\$status"
}
install_pair() {
  install_or_fallback_uninstall com.pocketshell.app '$APK_PATH'
  install_or_fallback_uninstall com.pocketshell.app.test '$TEST_APK_PATH'
  wait_package_manager_idle
}
for attempt in {1..3}; do
  printf 'Focused app walkthrough APK install attempt %s\n' "\$attempt"
  '$ADB' logcat -c || true
  install_pair
  stable=true
  for i in {1..20}; do
    wait_package_manager_idle
    if ! packages_present; then
      printf 'Package disappeared during post-install stability window on attempt %s.\n' "\$attempt" >&2
      stable=false
      break
    fi
    if post_install_removal_seen; then
      printf 'Delayed package removal broadcast seen during post-install stability window on attempt %s.\n' "\$attempt" >&2
      stable=false
      break
    fi
    sleep 1
  done
  if [ "\$stable" = true ]; then
    printf 'Focused app walkthrough APK install is package-manager idle and stable\n'
    exit 0
  fi
  printf 'Recent package removal context before reinstall:\n' >&2
  '$ADB' logcat -d -v time -t 500 |
    grep -E 'PackageManager|PackageInstaller|PACKAGE_|deletePackageX|com[.]pocketshell[.]app' >&2 || true
  wait_package_manager_idle
  sleep 3
done
printf 'Focused app walkthrough APK install did not stabilize after retries.\n' >&2
exit 1
INSTALL_SCRIPT
}

quiesce_app_walkthrough_processes_script() {
  cat <<QUIESCE_SCRIPT
set -euo pipefail
packages_stopped() {
  for package in com.pocketshell.app.test com.pocketshell.app; do
    if ! '$ADB' shell dumpsys package "\$package" 2>/dev/null | grep -q 'stopped=true'; then
      return 1
    fi
  done
}
processes_stopped() {
  if ! '$ADB' shell ps -A | grep -E 'com[.]pocketshell[.]app(\$|:|[[:space:]])|com[.]pocketshell[.]app[.]test(\$|:|[[:space:]])' >/dev/null; then
    return 0
  fi
  return 1
}
dump_quiesce_context() {
    printf 'Visible PocketShell processes:\n' >&2
    '$ADB' shell ps -A | grep -E 'com[.]pocketshell[.]app(\$|:|[[:space:]])|com[.]pocketshell[.]app[.]test(\$|:|[[:space:]])' >&2 || true
    printf 'Package paths:\n' >&2
    for package in com.pocketshell.app.test com.pocketshell.app; do
      '$ADB' shell pm path "\$package" >&2 || true
    done
    printf 'Package stopped state:\n' >&2
    for package in com.pocketshell.app.test com.pocketshell.app; do
      '$ADB' shell dumpsys package "\$package" 2>/dev/null | grep 'stopped=' >&2 || true
    done
    printf 'Recent package manager and activity context:\n' >&2
    '$ADB' logcat -d -v time -t 500 | grep -E 'PackageManager|PackageInstaller|PACKAGE_|ActivityManager.*com[.]pocketshell[.]app|Force stopping|deletePackageX' >&2 || true
}
for attempt in 1 2 3; do
  for package in com.pocketshell.app.test com.pocketshell.app; do
    '$ADB' shell am force-stop "\$package" || true
  done
  '$ADB' shell cmd package wait-for-handler --timeout 60000 >/dev/null 2>&1 || true
  '$ADB' shell cmd package wait-for-background-handler --timeout 60000 >/dev/null 2>&1 || true
  for i in {1..30}; do
    if processes_stopped && packages_stopped; then
      printf 'PocketShell app/test processes are stopped before focused instrumentation\n'
      break
    fi
    if [ "\$i" -eq 30 ]; then
      printf 'PocketShell app/test processes or package stopped state did not settle before focused instrumentation on attempt %s.\n' "\$attempt" >&2
      dump_quiesce_context
      if [ "\$attempt" -eq 3 ]; then
        exit 1
      fi
      sleep 2
      continue 2
    fi
    sleep 1
  done

  for i in {1..5}; do
    if ! processes_stopped || ! packages_stopped; then
      printf 'PocketShell app/test quiesce state changed during settle window on attempt %s.\n' "\$attempt" >&2
      dump_quiesce_context
      if [ "\$attempt" -eq 3 ]; then
        exit 1
      fi
      sleep 2
      continue 2
    fi
    sleep 1
  done
  printf 'PocketShell app/test force-stop settle window completed before focused instrumentation\n'
  exit 0
done
printf 'PocketShell app/test force-stop settle window did not complete before focused instrumentation\n' >&2
exit 1
QUIESCE_SCRIPT
}

issue_261_stale_db_launch_script() {
  cat <<ISSUE261_SCRIPT
set -euo pipefail

stale_db_host='$RUN_DIR/issue-261-stale-pocketshell.db'
stale_db_device='/data/local/tmp/issue-261-stale-pocketshell.db'
logcat_file='$ISSUE_261_STALE_DB_LOGCAT'

wait_package_manager_idle() {
  '$ADB' shell cmd package wait-for-handler --timeout 60000 >/dev/null 2>&1 || true
  '$ADB' shell cmd package wait-for-background-handler --timeout 60000 >/dev/null 2>&1 || true
}

install_or_fallback_uninstall() {
  local output
  set +e
  output=\$('$ADB' install -r -d -t '$APK_PATH' 2>&1)
  local status=\$?
  set -e
  printf '%s\n' "\$output"
  if [ "\$status" -eq 0 ]; then
    wait_package_manager_idle
    return 0
  fi
  if printf '%s\n' "\$output" | grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE'; then
    '$ADB' uninstall com.pocketshell.app >/dev/null 2>&1 || true
    wait_package_manager_idle
    '$ADB' install -r -d -t '$APK_PATH'
    wait_package_manager_idle
    return 0
  fi
  exit "\$status"
}

adb_output_has_transport_drop_markers() {
  printf '%s\n' "\${1:-}" | grep -Eiq 'device offline|device still connecting|error: closed|error: device .+ not found|no devices/emulators found|connection reset|connection refused|protocol fault|failed to read|read failed|transport.*(offline|error|closed)|adb: failed to'
}

logcat_has_app_crash_signature() {
  [ -f "\$1" ] || return 1
  grep -Eiq 'Room cannot verify|Expected identity hash|Process: com[.]pocketshell[.]app|FATAL EXCEPTION.*com[.]pocketshell[.]app|AndroidRuntime.*com[.]pocketshell[.]app' "\$1"
}

logcat_has_adb_transport_drop_markers() {
  [ -f "\$1" ] || return 1
  grep -Eq 'adbd[[:space:]].*(connection terminated|offline|read failed)|host-[0-9]+: read failed|UiAutomation service owner died' "\$1"
}

should_retry_launch_attempt() {
  [ "\$attempt" -lt '$ISSUE_261_STALE_DB_LAUNCH_ATTEMPTS' ] || return 1
  ! logcat_has_app_crash_signature "\$attempt_logcat_file" || return 1
  if [ "\$start_status" -ne 0 ] && {
    adb_output_has_transport_drop_markers "\$start_output" || logcat_has_adb_transport_drop_markers "\$attempt_logcat_file"
  }; then
    return 0
  fi
  if [ "\$pid_status" -ne 0 ] && {
    adb_output_has_transport_drop_markers "\$pid_output" || logcat_has_adb_transport_drop_markers "\$attempt_logcat_file"
  }; then
    return 0
  fi
  return 1
}

'$PYTHON3' - "\$stale_db_host" <<'PY'
import sqlite3
import sys
from pathlib import Path

database_path = sys.argv[1]
if Path(database_path).exists():
    Path(database_path).unlink()
connection = sqlite3.connect(database_path)
try:
    connection.execute("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
    connection.execute(
        "INSERT INTO room_master_table (id, identity_hash) VALUES(42, ?)",
        ("4a479a15dfcab2d576e00c7ce10ac581",),
    )
    connection.execute("CREATE TABLE stale_issue_261_marker (id INTEGER PRIMARY KEY)")
    connection.execute("PRAGMA user_version = 1")
    connection.commit()
finally:
    connection.close()
PY

'$ADB' shell am force-stop com.pocketshell.app >/dev/null 2>&1 || true
install_or_fallback_uninstall
'$ADB' shell pm clear com.pocketshell.app
wait_package_manager_idle
'$ADB' push "\$stale_db_host" "\$stale_db_device"
'$ADB' shell run-as com.pocketshell.app sh -c "'mkdir -p databases && cp \$stale_db_device databases/pocketshell.db && chmod 600 databases/pocketshell.db && rm -f databases/pocketshell.db-wal databases/pocketshell.db-shm databases/pocketshell.db-journal'"
'$ADB' shell rm -f "\$stale_db_device" >/dev/null 2>&1 || true

for attempt in \$(seq 1 '$ISSUE_261_STALE_DB_LAUNCH_ATTEMPTS'); do
  attempt_logcat_file="\$logcat_file"
  if [ "\$attempt" -lt '$ISSUE_261_STALE_DB_LAUNCH_ATTEMPTS' ]; then
    attempt_logcat_file="\$logcat_file.attempt\$attempt"
  fi
  rm -f "\$attempt_logcat_file"
  '$ADB' logcat -c || true
  '$ADB' shell am force-stop com.pocketshell.app >/dev/null 2>&1 || true

  set +e
  start_output=\$('$ADB' shell am start -W -n com.pocketshell.app/.MainActivity 2>&1)
  start_status=\$?
  set -e
  printf '%s\n' "\$start_output"

  if [ "\$start_status" -eq 0 ]; then
    sleep 5
  else
    sleep 2
  fi

  set +e
  pid_output=\$('$ADB' shell pidof com.pocketshell.app 2>&1)
  pid_status=\$?
  set -e
  pid=""
  if [ "\$pid_status" -eq 0 ]; then
    pid=\$(printf '%s\n' "\$pid_output" | tr -d '\r' | awk '/^[[:space:]]*[0-9]+[[:space:]]*$/ { print \$1; exit }')
  fi
  '$ADB' logcat -d -v time -t 5000 > "\$attempt_logcat_file" 2>&1 || true

  if logcat_has_app_crash_signature "\$attempt_logcat_file"; then
    cp "\$attempt_logcat_file" "\$logcat_file" 2>/dev/null || true
    printf 'Crash signature found after launching with the #261 stale Room DB.\n' >&2
    grep -Ei -C 40 'Room cannot verify|Expected identity hash|AndroidRuntime|FATAL EXCEPTION|com[.]pocketshell' "\$logcat_file" >&2 || true
    exit 1
  fi

  if [ "\$start_status" -ne 0 ]; then
    if should_retry_launch_attempt; then
      printf 'Issue #261 stale DB launch was interrupted by adb transport on attempt %s; retrying.\n' "\$attempt" >&2
      '$ADB' reconnect >/dev/null 2>&1 || true
      '$ADB' wait-for-device >/dev/null 2>&1 || true
      sleep 2
      continue
    fi
    cp "\$attempt_logcat_file" "\$logcat_file" 2>/dev/null || true
    printf 'Launching PocketShell with the #261 stale Room DB failed with status %s.\n' "\$start_status" >&2
    printf '%s\n' "\$start_output" >&2
    grep -Ei -C 40 'Room cannot verify|Expected identity hash|AndroidRuntime|FATAL EXCEPTION|com[.]pocketshell|adbd|connection terminated|offline|read failed' "\$logcat_file" >&2 || true
    exit "\$start_status"
  fi

  if [ -z "\$pid" ]; then
    if should_retry_launch_attempt; then
      printf 'Issue #261 stale DB pid check was interrupted by adb transport on attempt %s; retrying.\n' "\$attempt" >&2
      '$ADB' reconnect >/dev/null 2>&1 || true
      '$ADB' wait-for-device >/dev/null 2>&1 || true
      sleep 2
      continue
    fi
    cp "\$attempt_logcat_file" "\$logcat_file" 2>/dev/null || true
    printf 'PocketShell process was not alive after launching with the #261 stale Room DB.\n' >&2
    if [ "\$pid_status" -ne 0 ]; then
      printf '%s\n' "\$pid_output" >&2
    fi
    grep -Ei -C 40 'Room cannot verify|Expected identity hash|AndroidRuntime|FATAL EXCEPTION|com[.]pocketshell|adbd|connection terminated|offline|read failed' "\$logcat_file" >&2 || true
    exit 1
  fi

  if [ "\$attempt_logcat_file" != "\$logcat_file" ]; then
    cp "\$attempt_logcat_file" "\$logcat_file"
  fi
  break
done

'$ADB' shell am force-stop com.pocketshell.app >/dev/null 2>&1 || true
printf 'Issue #261 stale Room DB launch survived with pid %s\n' "\$pid"
printf 'Logcat artifact: %s\n' "\$logcat_file"
ISSUE261_SCRIPT
}

safe_step_name() {
  printf '%s' "$1" | tr '#.' '--'
}

run_app_walkthrough_script() {
  local selector="$1"
  local diagnostics_file="$2"
  local full_logcat_file="$3"
  cat <<RUN_SCRIPT
set -euo pipefail

diagnostics_file='$diagnostics_file'
full_logcat_file='$full_logcat_file'

dump_instrumentation_diagnostics() {
  local reason="\$1"
  {
    printf 'Instrumentation failure reason: %s\n' "\$reason"
    printf 'Selector: %s\n\n' '$selector'
    printf '=== instrumentation output ===\n'
    printf '%s\n\n' "\${output:-<no instrumentation output captured>}"
    printf '=== filtered logcat crash context ===\n'
    if [ -f "\$full_logcat_file" ]; then
      grep -E -C 80 \
        'AndroidRuntime|FATAL EXCEPTION|FATAL SIGNAL|Process: com[.]pocketshell[.]app|ActivityManager.*(Crash|Killing|Force stopping).*com[.]pocketshell[.]app|am_crash|TestRunner|AndroidJUnitRunner|Instrumentation' \
        "\$full_logcat_file" || true
    else
      printf 'Full logcat artifact was not created: %s\n' "\$full_logcat_file"
    fi
    printf '\n=== latest dropbox app crash entries ===\n'
    '$ADB' shell dumpsys dropbox --print data_app_crash system_app_crash 2>/dev/null | tail -n 500 || true
    printf '\n=== tombstone listing ===\n'
    '$ADB' shell ls -lt /data/tombstones 2>/dev/null | head -20 || true
  } | tee "\$diagnostics_file"
  printf 'Instrumentation diagnostics: %s\n' "\$diagnostics_file"
  printf 'Full logcat: %s\n' "\$full_logcat_file"
}

instrumentation_output_has_failure_markers() {
  printf '%s\n' "\$output" | grep -Eq '(^FAILURES!!!$|^FAILURE: |^INSTRUMENTATION_STATUS_CODE: -[0-9]+$|^INSTRUMENTATION_STATUS: stack=|^[[:space:]]*at (com[.]pocketshell|androidx[.]test|org[.]junit|kotlin[.]|java[.]|android[.])|^[[:alnum:]_.]*(Exception|Error): |^Process crashed[.])'
}

logcat_has_app_or_test_failure_markers() {
  grep -Eq 'Process: com[.]pocketshell[.]app|FATAL EXCEPTION.*com[.]pocketshell[.]app|FATAL SIGNAL.*com[.]pocketshell[.]app|AndroidRuntime.*com[.]pocketshell[.]app|(^|[[:space:]])FAILURES!!!($|[[:space:]])|INSTRUMENTATION_STATUS: stack=|INSTRUMENTATION_RESULT: shortMsg=Process crashed' "\$full_logcat_file"
}

logcat_has_adb_transport_drop_markers() {
  grep -Eq 'adbd[[:space:]].*(connection terminated|offline|read failed)|host-[0-9]+: read failed|UiAutomation service owner died' "\$full_logcat_file"
}

should_retry_interrupted_instrumentation() {
  [ "\$instrument_status" -eq 255 ] || return 1
  printf '%s\n' "\$output" | grep -q 'INSTRUMENTATION_CODE: -1' && return 1
  instrumentation_output_has_failure_markers && return 1
  logcat_has_app_or_test_failure_markers && return 1
  logcat_has_adb_transport_drop_markers
}

'$ADB' logcat -c || true
attempt=1
transport_recovery_attempts=0
app_walkthrough_instrumentation_attempts='$APP_WALKTHROUGH_INSTRUMENTATION_ATTEMPTS'
max_transport_recovery_attempts='$APP_WALKTHROUGH_TRANSPORT_RECOVERY_ATTEMPTS'
max_instrumentation_runs=\$(( app_walkthrough_instrumentation_attempts + max_transport_recovery_attempts ))
while [ "\$attempt" -le "\$max_instrumentation_runs" ]; do
  '$ADB' logcat -c || true
  set +e
  output=\$('$ADB' shell am instrument -w -r -e class '$selector' com.pocketshell.app.test/androidx.test.runner.AndroidJUnitRunner 2>&1)
  instrument_status=\$?
  set -e
  if [ "\$instrument_status" -ne 0 ]; then
    sleep 2
  fi
  '$ADB' logcat -d -v time -t 5000 > "\$full_logcat_file" 2>&1 || true
  printf '%s\n' "\$output"
  if [ "\$instrument_status" -eq 0 ] &&
    printf '%s\n' "\$output" | grep -q 'INSTRUMENTATION_CODE: -1' &&
    ! instrumentation_output_has_failure_markers; then
    exit 0
  fi
  if [ "\$attempt" -eq 1 ] &&
    { printf '%s\n' "\$output" | grep -q 'Process crashed'; } &&
    grep -q 'Crash of app com[.]pocketshell[.]app running instrumentation' "\$full_logcat_file"; then
    cp "\$full_logcat_file" "\$full_logcat_file.attempt1" || true
    printf 'Focused instrumentation crashed after external app force-stop; retrying selector once.\n' >&2
    for package in com.pocketshell.app.test com.pocketshell.app; do
      '$ADB' shell am force-stop "\$package" >/dev/null 2>&1 || true
    done
    '$ADB' shell cmd package wait-for-handler --timeout 60000 >/dev/null 2>&1 || true
    '$ADB' shell cmd package wait-for-background-handler --timeout 60000 >/dev/null 2>&1 || true
    sleep 2
    attempt=\$((attempt + 1))
    continue
  fi
  if should_retry_interrupted_instrumentation &&
    [ "\$transport_recovery_attempts" -lt "\$max_transport_recovery_attempts" ]; then
    transport_recovery_attempts=\$((transport_recovery_attempts + 1))
    cp "\$full_logcat_file" "\$full_logcat_file.attempt\$attempt" || true
    printf '%s\n' "\$output" > "\$diagnostics_file.attempt\$attempt-output" || true
    printf 'Focused instrumentation interrupted by adb transport drop on attempt %s; recovery %s/%s; retrying selector without treating it as an app/test retry.\n' "\$attempt" "\$transport_recovery_attempts" "\$max_transport_recovery_attempts" >&2
    '$ADB' reconnect >/dev/null 2>&1 || true
    timeout 60s '$ADB' wait-for-device >/dev/null 2>&1 || true
    for package in com.pocketshell.app.test com.pocketshell.app; do
      '$ADB' shell am force-stop "\$package" >/dev/null 2>&1 || true
    done
    '$ADB' shell cmd package wait-for-handler --timeout 60000 >/dev/null 2>&1 || true
    '$ADB' shell cmd package wait-for-background-handler --timeout 60000 >/dev/null 2>&1 || true
    sleep 2
    attempt=\$((attempt + 1))
    continue
  fi
  if [ "\$instrument_status" -ne 0 ]; then
    if should_retry_interrupted_instrumentation; then
      dump_instrumentation_diagnostics "adb shell am instrument exited with status \$instrument_status after \$transport_recovery_attempts transport-only recoveries"
    else
      dump_instrumentation_diagnostics "adb shell am instrument exited with status \$instrument_status"
    fi
    exit "\$instrument_status"
  fi
  if printf '%s\n' "\$output" | grep -q 'INSTRUMENTATION_CODE: -1' &&
    instrumentation_output_has_failure_markers; then
    dump_instrumentation_diagnostics "instrumentation reported INSTRUMENTATION_CODE: -1 with failure markers"
    exit 1
  fi
  dump_instrumentation_diagnostics "instrumentation did not report INSTRUMENTATION_CODE: -1"
  exit 1
done

dump_instrumentation_diagnostics "instrumentation exhausted \$max_instrumentation_runs attempts including \$transport_recovery_attempts transport-only recoveries"
exit 1
RUN_SCRIPT
}

docker_agents_pocketshell_version_script() {
  local expected_version="$1"
  local expected_output
  local quoted_ssh_key
  local quoted_expected_output
  expected_output="$(pocketshell_agent_fixture_version_output "$expected_version")"
  printf -v quoted_ssh_key '%q' "$SSH_KEY"
  printf -v quoted_expected_output '%q' "$expected_output"

  cat <<SCRIPT
set -euo pipefail
chmod 600 $quoted_ssh_key
output=\$(ssh -i $quoted_ssh_key -p 2222 -o BatchMode=yes -o ConnectTimeout=3 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null testuser@127.0.0.1 'pocketshell --version')
printf '%s\n' "\$output"
expected_output=$quoted_expected_output
if [ "\$output" != "\$expected_output" ]; then
  printf 'expected Docker pocketshell fixture version output exactly: %s\n' "\$expected_output" >&2
  exit 1
fi
SCRIPT
}

printf 'PocketShell pre-release confidence gate\n'
printf 'Run directory: %s\n' "$RUN_DIR"
if [[ -n "${POCKETSHELL_GATE_SOURCE_ROOT:-}" ]]; then
  printf 'Source workspace: %s\n' "$POCKETSHELL_GATE_SOURCE_ROOT"
  printf 'Isolated worktree: %s\n' "$ROOT_DIR"
fi
printf 'Android SDK: %s\n' "$ANDROID_SDK"
printf 'ADB: %s\n' "$ADB"
printf 'Emulator: %s\n' "$EMULATOR"
printf 'AVD: %s\n' "$AVD_NAME"
printf 'App versionName: %s\n' "$APP_VERSION_NAME"
printf 'Gradle user home: %s\n' "$GRADLE_USER_HOME"
printf 'Gradle flags: %s\n' "$GRADLE_FLAGS"
printf 'Manage emulator during readiness: %s\n' "$PRE_RELEASE_MANAGE_EMULATOR"

require_executable "$ADB" "adb"
require_executable "$EMULATOR" "emulator"
require_command_or_executable "$PYTHON3" "python3"

if ! [[ "$APP_WALKTHROUGH_INSTRUMENTATION_ATTEMPTS" =~ ^[1-9][0-9]*$ ]]; then
  fail "APP_WALKTHROUGH_INSTRUMENTATION_ATTEMPTS must be a positive integer"
fi
if ! [[ "$APP_WALKTHROUGH_TRANSPORT_RECOVERY_ATTEMPTS" =~ ^[0-9]+$ ]]; then
  fail "APP_WALKTHROUGH_TRANSPORT_RECOVERY_ATTEMPTS must be a non-negative integer"
fi
if ! [[ "$ISSUE_261_STALE_DB_LAUNCH_ATTEMPTS" =~ ^[1-9][0-9]*$ ]]; then
  fail "ISSUE_261_STALE_DB_LAUNCH_ATTEMPTS must be a positive integer"
fi

run_step "android-sdk-paths" "$ADB" version
run_step "available-avds" "$EMULATOR" -list-avds
if ! "$EMULATOR" -list-avds | grep -Fxq "$AVD_NAME"; then
  fail "AVD '$AVD_NAME' was not listed by $EMULATOR -list-avds"
fi

run_bash_step "gradle-compile-unit" \
  "./gradlew $GRADLE_FLAGS :app:kspDebugKotlin :app:kspReleaseKotlin :app:kspDebugAndroidTestKotlin :app:kspDebugUnitTestKotlin :app:kspReleaseUnitTestKotlin :app:hiltJavaCompileDebug :app:hiltJavaCompileRelease :app:hiltJavaCompileDebugAndroidTest --stacktrace && ./gradlew $GRADLE_FLAGS assembleDebug check -x lint -x lintDebug --stacktrace"

run_step "docker-agents-up" docker compose -f "$COMPOSE_FILE" up -d --build agents
# Issue #150: wait on the compose `healthcheck:` block via
# `docker inspect`. The follow-up SSH sanity check still verifies the
# fixture's tool surface (`claude codex opencode quse tmuxctl uv`), but
# the readiness poll itself is event-based, not retry-sleep.
run_bash_step "docker-agents-health" \
  "source '$ROOT_DIR/tests/docker/lib/wait-for-healthy.sh' && wait_for_container_healthy '$COMPOSE_FILE' agents '$RUN_DIR/docker-agents-health.log' 60"
run_bash_step "docker-agents-pocketshell-version" \
  "$(docker_agents_pocketshell_version_script "$APP_VERSION_NAME")"
run_bash_step "docker-agents-ssh-sanity" \
  "chmod 600 '$SSH_KEY' && ssh -i '$SSH_KEY' -p 2222 -o BatchMode=yes -o ConnectTimeout=3 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null testuser@127.0.0.1 'for tool in claude codex opencode quse tmuxctl uv; do command -v \"\$tool\"; done && quse --json >/dev/null && tmuxctl jobs list --session codex >/dev/null'"

run_bash_step "emulator-readiness" \
  "$(emulator_readiness_script)"
update_emulator_serial

run_bash_step "connected-terminal-input" "$(core_terminal_connected_input_script)"

run_step "build-app-test-apks" \
  ./gradlew $GRADLE_FLAGS :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
[[ -f "$APK_PATH" ]] || fail "APK artifact was not created at $APK_PATH"
[[ -f "$TEST_APK_PATH" ]] || fail "Android test APK artifact was not created at $TEST_APK_PATH"

ISSUE_261_STALE_DB_STATUS="running"
run_bash_step "issue-261-stale-db-launch" "$(issue_261_stale_db_launch_script)"

run_bash_step "reset-app-packages-before-app-walkthrough" "$(reset_app_packages_script)"
run_bash_step "install-app-walkthrough-apks" "$(install_app_walkthrough_apks_script)"

for app_walkthrough_index in "${!APP_WALKTHROUGH_TESTS[@]}"; do
  app_walkthrough_selector="${APP_WALKTHROUGH_TESTS[$app_walkthrough_index]}"
  app_walkthrough_safe_name="$(safe_step_name "$app_walkthrough_selector")"
  set_focused_status "$app_walkthrough_selector" "pending"
  if ! run_bash_step "quiesce-app-walkthrough-processes-$app_walkthrough_safe_name" "$(quiesce_app_walkthrough_processes_script)"; then
    set_focused_status "$app_walkthrough_selector" "blocked"
    exit 1
  fi

  app_walkthrough_step_index=$((STEP_INDEX + 1))
  app_walkthrough_diagnostics_file="$(printf '%s/%02d-connected-app-walkthrough-%s-diagnostics.log' "$RUN_DIR" "$app_walkthrough_step_index" "$app_walkthrough_safe_name")"
  app_walkthrough_full_logcat_file="$(printf '%s/%02d-connected-app-walkthrough-%s-full-logcat.log' "$RUN_DIR" "$app_walkthrough_step_index" "$app_walkthrough_safe_name")"
  set_focused_status "$app_walkthrough_selector" "running" "" "$app_walkthrough_diagnostics_file" "$app_walkthrough_full_logcat_file"
  if run_bash_step "connected-app-walkthrough-$app_walkthrough_safe_name" "$(run_app_walkthrough_script "$app_walkthrough_selector" "$app_walkthrough_diagnostics_file" "$app_walkthrough_full_logcat_file")"; then
    set_focused_status "$app_walkthrough_selector" "passed" "$RUN_DIR/$(printf '%02d-connected-app-walkthrough-%s.log' "$app_walkthrough_step_index" "$app_walkthrough_safe_name")" "$app_walkthrough_diagnostics_file" "$app_walkthrough_full_logcat_file"
  else
    set_focused_status "$app_walkthrough_selector" "failed" "$RUN_DIR/$(printf '%02d-connected-app-walkthrough-%s.log' "$app_walkthrough_step_index" "$app_walkthrough_safe_name")" "$app_walkthrough_diagnostics_file" "$app_walkthrough_full_logcat_file"
    FAILURE_DIAGNOSTICS_PATH="$app_walkthrough_diagnostics_file"
    FAILURE_LOGCAT_PATH="$app_walkthrough_full_logcat_file"
    exit 1
  fi
done

run_step "build-debug-apk" ./gradlew $GRADLE_FLAGS :app:assembleDebug --stacktrace
[[ -f "$APK_PATH" ]] || fail "APK artifact was not created at $APK_PATH"

run_step "install-debug-apk" "$ADB" install -r "$APK_PATH"

GATE_RESULT="PASS"
GATE_RESULT_MESSAGE="PASS: pre-release confidence gate completed"
printf '\nPASS: pre-release confidence gate completed\n'
printf 'Logs: %s\n' "$RUN_DIR"
printf 'APK: %s\n' "$APK_PATH"
