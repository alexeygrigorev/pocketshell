#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
COMPOSE_FILE="${COMPOSE_FILE:-tests/docker/docker-compose.yml}"
SSH_KEY="${SSH_KEY:-tests/docker/test_key}"
SSH_HOST="${SSH_HOST:-127.0.0.1}"
SSH_PORT="${SSH_PORT:-2222}"
SSH_USER="${SSH_USER:-testuser}"
LOG_ROOT="${LOG_ROOT:-$ROOT_DIR/build/issue78-phone-walkthrough}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RUN_DIR="$LOG_ROOT/$RUN_ID"
TEST_SELECTOR="com.pocketshell.app.proof.EmulatorDockerSshSmokeTest#walkthroughJourneyOpensAppSessionAndRunsShellAndTmuxCommands"
DEVICE_OUTPUT_DIR="/sdcard/Android/media/com.pocketshell.app/additional_test_output"
DEVICE_ISSUE78_DIR="$DEVICE_OUTPUT_DIR/issue78-phone-walkthrough"
VERIFY_ROOT=""

usage() {
  cat <<'USAGE'
Usage: scripts/issue78-phone-walkthrough.sh

Runs the focused issue #78 phone walkthrough loop:
  - starts/verifies the Docker agent SSH fixture
  - builds debug app + androidTest APKs
  - installs APKs on the emulator
  - runs only the host/session picker -> existing tmux session -> terminal test
  - pulls issue #78 screenshots and timing artifacts
  - writes full logcat, filtered timing logs, Docker logs, and command logs

Environment overrides:
  ANDROID_SDK=/home/alexey/Android/Sdk
  ADB=$ANDROID_SDK/platform-tools/adb
  COMPOSE_FILE=tests/docker/docker-compose.yml
  SSH_KEY=tests/docker/test_key
  SSH_HOST=127.0.0.1
  SSH_PORT=2222
  SSH_USER=testuser
  LOG_ROOT=build/issue78-phone-walkthrough
  RUN_ID=<custom artifact directory name>
USAGE
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

mkdir -p "$RUN_DIR"

fail() {
  printf '\nFAIL: %s\nArtifacts: %s\n' "$1" "$RUN_DIR" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 was not found on PATH"
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

collect_diagnostics() {
  local exit_code=$?
  {
    printf 'exit_code=%s\n' "$exit_code"
    printf 'run_dir=%s\n' "$RUN_DIR"
    printf 'verify_root=%s\n' "$VERIFY_ROOT"
    printf 'timestamp=%s\n' "$(date -Is)"
  } > "$RUN_DIR/exit-status.txt"
  docker compose -f "$ROOT_DIR/$COMPOSE_FILE" ps > "$RUN_DIR/docker-compose-ps.txt" 2>&1 || true
  docker compose -f "$ROOT_DIR/$COMPOSE_FILE" logs --no-color --timestamps agents > "$RUN_DIR/docker-agents.log" 2>&1 || true
  "$ADB" devices -l > "$RUN_DIR/adb-devices-final.txt" 2>&1 || true
  "$ADB" logcat -d -v threadtime > "$RUN_DIR/adb-logcat-final.txt" 2>&1 || true
}
trap collect_diagnostics EXIT

prepare_verify_root() {
  if [[ -e "$ROOT_DIR/app/src/main/java/com/pocketshell/app/terminal/TerminalLabActivity.kt" ]] &&
    ! git -C "$ROOT_DIR" ls-files --error-unmatch app/src/main/java/com/pocketshell/app/terminal/TerminalLabActivity.kt >/dev/null 2>&1; then
    VERIFY_ROOT="$(mktemp -d /tmp/pocketshell-issue78-verify-XXXXXX)"
    run_logged "00-prepare-isolated-worktree" rsync -a --delete \
      --exclude '.git' \
      --exclude 'app/build' \
      --exclude 'build' \
      --exclude 'app/src/main/java/com/pocketshell/app/terminal' \
      --exclude 'app/src/androidTest/java/com/pocketshell/app/terminal' \
      "$ROOT_DIR/" "$VERIFY_ROOT/"
    printf 'Using isolated verification copy to avoid unrelated untracked terminal-lab sources: %s\n' "$VERIFY_ROOT"
  else
    VERIFY_ROOT="$ROOT_DIR"
  fi
}

# Issue #150: wait on the compose `healthcheck:` block via
# `docker inspect`, not a host-side SSH retry loop. Keep a single
# follow-up SSH probe so the readiness log still records the
# tool-availability sanity check reviewers look for.
source "$ROOT_DIR/tests/docker/lib/wait-for-healthy.sh"

wait_for_docker_ssh() {
  local log_file="$RUN_DIR/02-docker-ssh-readiness.log"
  chmod 600 "$ROOT_DIR/$SSH_KEY"
  if ! wait_for_container_healthy "$ROOT_DIR/$COMPOSE_FILE" agents "$log_file" 60; then
    tail -n 120 "$log_file" || true
    fail "Docker SSH fixture did not become healthy at $SSH_USER@$SSH_HOST:$SSH_PORT"
  fi
  {
    printf '[%s] health=healthy; running follow-up SSH sanity probe\n' "$(date -Is)"
    ssh -i "$ROOT_DIR/$SSH_KEY" \
      -p "$SSH_PORT" \
      -o BatchMode=yes \
      -o ConnectTimeout=3 \
      -o ConnectionAttempts=1 \
      -o StrictHostKeyChecking=no \
      -o UserKnownHostsFile=/dev/null \
      "$SSH_USER@$SSH_HOST" \
      'for tool in claude codex opencode heru agent-log-explorer tmuxctl uv tmux; do command -v "$tool"; done && tmuxctl list --by activity'
  } >> "$log_file" 2>&1 || {
    tail -n 120 "$log_file" || true
    fail "Docker SSH fixture reported healthy but follow-up SSH probe failed at $SSH_USER@$SSH_HOST:$SSH_PORT"
  }
  printf '\n[02-docker-ssh-readiness]\nLog: %s\n' "$log_file"
  tail -n 30 "$log_file"
}

wait_for_emulator() {
  run_logged "03-emulator-readiness" bash -lc \
    "'$ADB' devices -l && for i in {1..90}; do state=\$('$ADB' shell getprop sys.boot_completed 2>/dev/null | tr -d '\r'); if [ \"\$state\" = 1 ]; then exit 0; fi; sleep 2; done; '$ADB' devices -l; exit 1"
}

wait_for_pocketshell_idle() {
  local step_name="$1"
  run_logged "$step_name" bash -lc \
    "'$ADB' shell am force-stop com.pocketshell.app >/dev/null 2>&1 || true; '$ADB' shell am force-stop com.pocketshell.app.test >/dev/null 2>&1 || true; for i in {1..30}; do if ! '$ADB' shell ps -A | grep -E 'com[.]pocketshell[.]app($|:|[[:space:]])|com[.]pocketshell[.]app[.]test($|:|[[:space:]])' >/dev/null; then exit 0; fi; sleep 1; done; '$ADB' shell ps -A | grep -E 'com[.]pocketshell[.]app($|:|[[:space:]])|com[.]pocketshell[.]app[.]test($|:|[[:space:]])' >&2 || true; exit 1"
}

install_apk() {
  local package="$1"
  local apk="$2"
  local output
  set +e
  output="$("$ADB" install -r -d -t "$apk" 2>&1)"
  local status=$?
  set -e
  printf '%s\n' "$output"
  if [[ "$status" -eq 0 ]]; then
    return 0
  fi
  if printf '%s\n' "$output" | grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE'; then
    printf 'COLD-RESET: uninstall fallback for incompatible issue #78 package: %s\n' "$package"
    "$ADB" uninstall "$package" >/dev/null 2>&1 || true
    "$ADB" install -r -d -t "$apk"
    return 0
  fi
  return "$status"
}

install_apks() {
  local app_apk="$VERIFY_ROOT/app/build/outputs/apk/debug/app-debug.apk"
  local test_apk="$VERIFY_ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
  [[ -f "$app_apk" ]] || fail "app APK not found at $app_apk"
  [[ -f "$test_apk" ]] || fail "androidTest APK not found at $test_apk"
  run_logged "06-cold-reset-install-apks" bash -lc "$(declare -f install_apk); ADB='$ADB'; printf 'COLD-RESET: installing app/test APKs for issue #78 walkthrough\n'; install_apk com.pocketshell.app '$app_apk'; install_apk com.pocketshell.app.test '$test_apk'"
  run_logged "06b-wait-package-manager-idle" bash -lc \
    "'$ADB' shell cmd package wait-for-handler --timeout 60000 >/dev/null 2>&1 || true; '$ADB' shell cmd package wait-for-background-handler --timeout 60000 >/dev/null 2>&1 || true; for i in {1..20}; do '$ADB' shell pm path com.pocketshell.app >/dev/null && '$ADB' shell pm path com.pocketshell.app.test >/dev/null; sleep 1; done"
}

run_issue78_test() {
  local instrumentation_log="$RUN_DIR/09-issue78-instrumentation.log"
  run_logged "07-cold-reset-app-and-artifacts" bash -lc \
    "printf 'COLD-RESET: clearing app data and issue #78 artifacts\n'; \
    "'$ADB' shell am force-stop com.pocketshell.app >/dev/null 2>&1 || true; '$ADB' shell am force-stop com.pocketshell.app.test >/dev/null 2>&1 || true; '$ADB' shell pm clear com.pocketshell.app >/dev/null 2>&1 || true; '$ADB' shell cmd package wait-for-handler --timeout 60000 >/dev/null 2>&1 || true; '$ADB' shell cmd package wait-for-background-handler --timeout 60000 >/dev/null 2>&1 || true; '$ADB' shell rm -rf '$DEVICE_ISSUE78_DIR'"
  run_logged "08-clear-logcat" "$ADB" logcat -c
  run_logged "09-issue78-instrumentation" \
    "$ADB" shell am instrument -w -r \
    -e class "$TEST_SELECTOR" \
    com.pocketshell.app.test/androidx.test.runner.AndroidJUnitRunner
  "$ADB" logcat -d -v threadtime > "$RUN_DIR/10-full-logcat.log" 2>&1 || true
  rg -n 'ISSUE78_|PocketShellWalkthrough|AndroidRuntime|FATAL|Process: com[.]pocketshell[.]app' \
    "$RUN_DIR/10-full-logcat.log" "$instrumentation_log" > "$RUN_DIR/11-issue78-filtered-log.txt" 2>&1 || true
  grep -q 'INSTRUMENTATION_CODE: -1' "$instrumentation_log" &&
    grep -q 'OK (' "$instrumentation_log" &&
    ! grep -q 'FAILURES!!!' "$instrumentation_log" ||
    fail "issue #78 instrumentation did not report success"
}

pull_artifacts() {
  mkdir -p "$RUN_DIR/device-artifacts"
  run_logged "12-pull-issue78-artifacts" "$ADB" pull "$DEVICE_ISSUE78_DIR" "$RUN_DIR/device-artifacts/"
  local artifact_dir="$RUN_DIR/device-artifacts/issue78-phone-walkthrough"
  [[ -s "$artifact_dir/issue78-existing-tmux-output.png" ]] ||
    fail "expected terminal screenshot was not pulled from $DEVICE_ISSUE78_DIR"
  [[ -s "$artifact_dir/issue78-existing-tmux-transcript.txt" ]] ||
    fail "expected terminal transcript was not pulled from $DEVICE_ISSUE78_DIR"
  [[ -s "$artifact_dir/issue78-timings.txt" ]] ||
    fail "expected timing artifact was not pulled from $DEVICE_ISSUE78_DIR"
  grep -q 'issue78-complete-' "$artifact_dir/issue78-existing-tmux-transcript.txt" ||
    fail "terminal transcript did not include the completed issue #78 marker"
}

printf 'PocketShell issue #78 phone walkthrough loop\n'
printf 'Artifacts: %s\n' "$RUN_DIR"
printf 'ADB: %s\n' "$ADB"

[[ -x "$ADB" ]] || fail "adb is not executable at $ADB"
require_command docker
require_command ssh
require_command rsync
require_command rg
[[ -f "$ROOT_DIR/$SSH_KEY" ]] || fail "SSH key was not found at $ROOT_DIR/$SSH_KEY"

prepare_verify_root
run_logged "01-docker-agents-up" docker compose -f "$ROOT_DIR/$COMPOSE_FILE" up -d --build agents
wait_for_docker_ssh
wait_for_emulator
wait_for_pocketshell_idle "03b-wait-for-shared-emulator-idle"
run_logged "04-build-apks" "$VERIFY_ROOT/gradlew" --no-daemon --no-build-cache -p "$VERIFY_ROOT" :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
wait_for_pocketshell_idle "05-wait-before-install"
install_apks
wait_for_pocketshell_idle "06c-wait-before-issue78-instrumentation"
run_issue78_test
pull_artifacts

printf '\nPASS: issue #78 phone walkthrough loop completed\n'
printf 'Artifacts: %s\n' "$RUN_DIR"
printf 'Screenshot: %s/device-artifacts/issue78-phone-walkthrough/issue78-existing-tmux-output.png\n' "$RUN_DIR"
printf 'Transcript: %s/device-artifacts/issue78-phone-walkthrough/issue78-existing-tmux-transcript.txt\n' "$RUN_DIR"
printf 'Timings:\n'
cat "$RUN_DIR/device-artifacts/issue78-phone-walkthrough/issue78-timings.txt"
printf 'Filtered log: %s/11-issue78-filtered-log.txt\n' "$RUN_DIR"
printf 'Full logcat: %s/10-full-logcat.log\n' "$RUN_DIR"
