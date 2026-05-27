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
  exec 9>"$LOCK_FILE"
  if ! flock -n 9; then
    echo "Another emulator-touching script holds the AVD lock ($LOCK_FILE); waiting..." >&2
    flock 9
  fi
  echo "Acquired AVD lock (fd 9): $LOCK_FILE" >&2
  export POCKETSHELL_AVD_LOCK_ACQUIRED=1
fi

ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
EMULATOR="${EMULATOR:-$ANDROID_SDK/emulator/emulator}"
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

APP_DOGFOOD_TESTS=(
  "com.pocketshell.app.composer.PromptComposerSmokeTest#recordingAndTranscribingStatesAreVisible"
  "com.pocketshell.app.composer.PromptComposerSmokeTest#typedDraftSendEnterReachesDockerShell"
  "com.pocketshell.app.snippets.SnippetTerminalE2eTest#tappingCommandSnippetSendsInputToDockerShell"
  "com.pocketshell.app.session.InlineDictationUiTest#recordingStateShowsAmplitudeWaveformOnInlineMicSlot"
  "com.pocketshell.app.session.InlineDictationUiTest#transcribingStateShowsSpinnerAndBlocksDuplicateTap"
  "com.pocketshell.app.session.VoiceCommandPlannerE2eTest#fakePlannerEndpointProducesReviewableCommandThatRunsThroughTerminalBridge"
  "com.pocketshell.app.proof.EmulatorDockerSshSmokeTest#debugAppConnectsToDockerAgentTargetViaEmulatorHostAlias"
  "com.pocketshell.app.proof.EmulatorDockerSshSmokeTest#dogfoodJourneyOpensAppSessionAndRunsShellAndTmuxCommands"
)

usage() {
  cat <<'USAGE'
Usage: scripts/pre-release-confidence-gate.sh

Runs the local APK dogfood release-confidence gate:
  - compile/unit checks
  - deterministic Docker agent target
  - emulator readiness with explicit Android SDK paths
  - focused connected dogfood tests
  - debug APK build and emulator install sanity

Acquires an exclusive `flock` on `build/.avd-lock` (relative to the repo
root) before touching the emulator so that parallel-worktree gate runs
serialise on the shared local AVD. Released automatically on script exit.
See issue #182.

Environment overrides:
  ANDROID_SDK=/home/alexey/Android/Sdk
  ADB=$ANDROID_SDK/platform-tools/adb
  EMULATOR=$ANDROID_SDK/emulator/emulator
  AVD_NAME=test
  LOG_ROOT=build/pre-release-confidence-gate
  GRADLE_USER_HOME=build/pre-release-confidence-gate/gradle-home
  GRADLE_FLAGS="--no-daemon --no-build-cache --no-parallel --max-workers=2"
  GATE_ISOLATED_WORKTREE=1
  COMPOSE_FILE=tests/docker/docker-compose.yml
  APK_PATH=app/build/outputs/apk/debug/app-debug.apk
  TEST_APK_PATH=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
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
APP_DOGFOOD_INSTALL_STATUS="not_run"
FINAL_INSTALL_STATUS="not_run"
STEP_NAMES=()
STEP_STATUSES=()
STEP_LOGS=()
STEP_COMMANDS=()
FOCUSED_SELECTORS=("${APP_DOGFOOD_TESTS[@]}")
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
    printf 'Focused app APK install status: %s\n' "$APP_DOGFOOD_INSTALL_STATUS"
    printf 'Final install status: %s\n' "$FINAL_INSTALL_STATUS"
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
      install-app-dogfood-apks)
        APP_DOGFOOD_INSTALL_STATUS="passed"
        ;;
      install-debug-apk)
        FINAL_INSTALL_STATUS="passed"
        ;;
    esac
  else
    STEP_STATUSES[$step_array_index]="failed"
    FAILING_STEP="$name"
    FAILING_LOG_PATH="$log_file"
    FAILURE_MESSAGE="step '$name' failed with status $status"
    case "$name" in
      install-app-dogfood-apks)
        APP_DOGFOOD_INSTALL_STATUS="failed"
        ;;
      install-debug-apk)
        FINAL_INSTALL_STATUS="failed"
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
printf 'Focused app dogfood package state reset without package deletion\n'
RESET_SCRIPT
}

install_app_dogfood_apks_script() {
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
  printf 'Focused app dogfood APK install attempt %s\n' "\$attempt"
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
    printf 'Focused app dogfood APK install is package-manager idle and stable\n'
    exit 0
  fi
  printf 'Recent package removal context before reinstall:\n' >&2
  '$ADB' logcat -d -v time -t 500 |
    grep -E 'PackageManager|PackageInstaller|PACKAGE_|deletePackageX|com[.]pocketshell[.]app' >&2 || true
  wait_package_manager_idle
  sleep 3
done
printf 'Focused app dogfood APK install did not stabilize after retries.\n' >&2
exit 1
INSTALL_SCRIPT
}

quiesce_app_dogfood_processes_script() {
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

safe_step_name() {
  printf '%s' "$1" | tr '#.' '--'
}

run_app_dogfood_script() {
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

'$ADB' logcat -c || true
for attempt in 1 2; do
  '$ADB' logcat -c || true
  set +e
  output=\$('$ADB' shell am instrument -w -r -e class '$selector' com.pocketshell.app.test/androidx.test.runner.AndroidJUnitRunner 2>&1)
  instrument_status=\$?
  set -e
  '$ADB' logcat -d -v time -t 5000 > "\$full_logcat_file" 2>&1 || true
  printf '%s\n' "\$output"
  if [ "\$instrument_status" -eq 0 ] && printf '%s\n' "\$output" | grep -q 'INSTRUMENTATION_CODE: -1'; then
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
    continue
  fi
  if [ "\$instrument_status" -ne 0 ]; then
    dump_instrumentation_diagnostics "adb shell am instrument exited with status \$instrument_status"
    exit "\$instrument_status"
  fi
  dump_instrumentation_diagnostics "instrumentation did not report INSTRUMENTATION_CODE: -1"
  exit 1
done
RUN_SCRIPT
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
printf 'Gradle user home: %s\n' "$GRADLE_USER_HOME"
printf 'Gradle flags: %s\n' "$GRADLE_FLAGS"

require_executable "$ADB" "adb"
require_executable "$EMULATOR" "emulator"

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
run_bash_step "docker-agents-ssh-sanity" \
  "chmod 600 '$SSH_KEY' && ssh -i '$SSH_KEY' -p 2222 -o BatchMode=yes -o ConnectTimeout=3 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null testuser@127.0.0.1 'for tool in claude codex opencode quse tmuxctl uv; do command -v \"\$tool\"; done && quse --json >/dev/null && tmuxctl jobs list --session codex >/dev/null'"

run_bash_step "emulator-readiness" \
  "'$ADB' devices && for i in {1..90}; do state=\$('$ADB' shell getprop sys.boot_completed 2>/dev/null | tr -d '\r'); if [ \"\$state\" = 1 ]; then exit 0; fi; sleep 2; done; '$ADB' devices; exit 1"
update_emulator_serial

run_step "connected-terminal-input" \
  ./gradlew $GRADLE_FLAGS :shared:core-terminal:connectedDebugAndroidTest --stacktrace

run_step "build-app-test-apks" \
  ./gradlew $GRADLE_FLAGS :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
[[ -f "$APK_PATH" ]] || fail "APK artifact was not created at $APK_PATH"
[[ -f "$TEST_APK_PATH" ]] || fail "Android test APK artifact was not created at $TEST_APK_PATH"

run_bash_step "reset-app-packages-before-app-dogfood" "$(reset_app_packages_script)"
run_bash_step "install-app-dogfood-apks" "$(install_app_dogfood_apks_script)"

for app_dogfood_index in "${!APP_DOGFOOD_TESTS[@]}"; do
  app_dogfood_selector="${APP_DOGFOOD_TESTS[$app_dogfood_index]}"
  app_dogfood_safe_name="$(safe_step_name "$app_dogfood_selector")"
  set_focused_status "$app_dogfood_selector" "pending"
  if ! run_bash_step "quiesce-app-dogfood-processes-$app_dogfood_safe_name" "$(quiesce_app_dogfood_processes_script)"; then
    set_focused_status "$app_dogfood_selector" "blocked"
    exit 1
  fi

  app_dogfood_step_index=$((STEP_INDEX + 1))
  app_dogfood_diagnostics_file="$(printf '%s/%02d-connected-app-dogfood-%s-diagnostics.log' "$RUN_DIR" "$app_dogfood_step_index" "$app_dogfood_safe_name")"
  app_dogfood_full_logcat_file="$(printf '%s/%02d-connected-app-dogfood-%s-full-logcat.log' "$RUN_DIR" "$app_dogfood_step_index" "$app_dogfood_safe_name")"
  set_focused_status "$app_dogfood_selector" "running" "" "$app_dogfood_diagnostics_file" "$app_dogfood_full_logcat_file"
  if run_bash_step "connected-app-dogfood-$app_dogfood_safe_name" "$(run_app_dogfood_script "$app_dogfood_selector" "$app_dogfood_diagnostics_file" "$app_dogfood_full_logcat_file")"; then
    set_focused_status "$app_dogfood_selector" "passed" "$RUN_DIR/$(printf '%02d-connected-app-dogfood-%s.log' "$app_dogfood_step_index" "$app_dogfood_safe_name")" "$app_dogfood_diagnostics_file" "$app_dogfood_full_logcat_file"
  else
    set_focused_status "$app_dogfood_selector" "failed" "$RUN_DIR/$(printf '%02d-connected-app-dogfood-%s.log' "$app_dogfood_step_index" "$app_dogfood_safe_name")" "$app_dogfood_diagnostics_file" "$app_dogfood_full_logcat_file"
    FAILURE_DIAGNOSTICS_PATH="$app_dogfood_diagnostics_file"
    FAILURE_LOGCAT_PATH="$app_dogfood_full_logcat_file"
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
