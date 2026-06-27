#!/usr/bin/env bash
# Issue #303 — live tmux TerminalView proof for the inline
# Terminal/Conversation toolbar pill.
#
# Runs the real-app tmux instrumentation test against the deterministic Docker
# `agents` fixture, then pulls the authoritative TerminalView viewport PNGs,
# visible-terminal sidecars, full-device chrome screenshots, timing file,
# logcat, Docker logs, and SSH readiness log into:
#
#   build/terminal-workbench/<run-id>/
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
COMPOSE_FILE="${COMPOSE_FILE:-tests/docker/docker-compose.yml}"
AGENT_SERVICE="${AGENT_SERVICE:-agents}"
SSH_PORT="${SSH_PORT:-2222}"
LOG_ROOT="${LOG_ROOT:-$ROOT_DIR/build/terminal-workbench}"
RUN_ID="${RUN_ID:-issue-303-$(date +%Y%m%d-%H%M%S)}"
RUN_DIR="$LOG_ROOT/$RUN_ID"
ARTIFACT_DIR="$RUN_DIR/artifacts/terminal-lab"
BUILD_APKS="${BUILD_APKS:-1}"
APP_APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
SSH_KEY="${SSH_KEY:-$ROOT_DIR/tests/docker/test_key}"
HOST_SSH_KEY="$RUN_DIR/test_key"
SSH_HOST="${SSH_HOST:-127.0.0.1}"
SSH_USER="${SSH_USER:-testuser}"
DEVICE_OUTPUT_DIR="/sdcard/Android/media/com.pocketshell.app/additional_test_output"
DEVICE_ARTIFACT_DIR="$DEVICE_OUTPUT_DIR/terminal-lab"
TEST_SELECTOR="${TEST_SELECTOR:-com.pocketshell.app.tmux.TmuxSessionOpencodeInputDockerTest#issue303TerminalConversationPillStaysInToolbarRow}"

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

source "$ROOT_DIR/tests/docker/lib/wait-for-healthy.sh"

wait_for_ssh_fixture() {
  local log_file="$RUN_DIR/docker-ssh-readiness.log"
  if ! wait_for_container_healthy "$COMPOSE_FILE" "$AGENT_SERVICE" "$log_file" 60; then
    tail -n 80 "$log_file" || true
    fail "Docker SSH fixture did not become healthy"
  fi
  {
    printf '[%s] health=healthy; running issue303 SSH sanity probe\n' "$(date -Is)"
    ssh \
      -i "$HOST_SSH_KEY" \
      -p "$SSH_PORT" \
      -o BatchMode=yes \
      -o ConnectTimeout=3 \
      -o ConnectionAttempts=1 \
      -o StrictHostKeyChecking=no \
      -o UserKnownHostsFile=/dev/null \
      "$SSH_USER@$SSH_HOST" \
      "printf 'issue303 ssh ready '; tmux -V; command -v claude; test -f /home/testuser/.claude/projects/-workspace-pocketshell/pocketshell-claude.jsonl"
  } >> "$log_file" 2>&1 || {
    tail -n 80 "$log_file" || true
    fail "Docker SSH fixture reported healthy but follow-up SSH probe failed"
  }
  tail -n 20 "$log_file"
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
  "$ADB" logcat -d -v threadtime -t 6000 > "$RUN_DIR/logcat.txt" 2>&1 || true
}

require_file() {
  local file="$1"
  [[ -s "$file" ]] || fail "missing or empty artifact: $file"
}

validate_artifacts() {
  [[ -d "$ARTIFACT_DIR" ]] || fail "artifact directory missing at $ARTIFACT_DIR"
  require_file "$ARTIFACT_DIR/timings.txt"
  require_file "$ARTIFACT_DIR/issue303-live-toolbar-summary.txt"

  for stem in \
    issue303-01-agent-terminal \
    issue303-03-agent-returned-terminal \
    issue303-04-plain-terminal
  do
    require_file "$ARTIFACT_DIR/$stem-viewport.png"
    require_file "$ARTIFACT_DIR/$stem-visible-terminal.txt"
  done

  for image in \
    issue303-01-agent-terminal-full.png \
    issue303-02-agent-conversation-full.png \
    issue303-03-agent-returned-terminal-full.png \
    issue303-04-plain-terminal-full.png
  do
    require_file "$ARTIFACT_DIR/$image"
  done

  grep -q 'issue303-agent-terminal-ready' \
    "$ARTIFACT_DIR/issue303-01-agent-terminal-visible-terminal.txt" ||
    fail "agent terminal sidecar is missing the live agent marker"
  grep -q 'issue303-agent-terminal-ready' \
    "$ARTIFACT_DIR/issue303-03-agent-returned-terminal-visible-terminal.txt" ||
    fail "returned terminal sidecar is missing the live agent marker"
  grep -q 'issue303-plain-terminal-ready' \
    "$ARTIFACT_DIR/issue303-04-plain-terminal-visible-terminal.txt" ||
    fail "plain terminal sidecar is missing the live plain-shell marker"

  for key in \
    agent_terminal_conversation_pill_visible=true \
    pill_bounds_inside_56dp_toolbar=true \
    conversation_opened_with_one_tap=true \
    terminal_returned_with_one_tap=true \
    plain_shell_tabs_absent=true \
    plain_shell_reserved_tab_row_absent=true
  do
    grep -q "$key" "$ARTIFACT_DIR/issue303-live-toolbar-summary.txt" ||
      fail "issue303 summary missing acceptance key: $key"
  done

  for key in \
    issue303_agent_attach_to_terminal_visible_ms \
    issue303_agent_terminal_to_tabs_visible_ms \
    issue303_conversation_tap_to_conversation_visible_ms \
    issue303_terminal_tap_to_terminal_visible_ms
  do
    grep -q "^$key=" "$ARTIFACT_DIR/timings.txt" ||
      fail "timings.txt missing $key"
  done

  {
    printf 'PocketShell issue #303 live tmux toolbar proof artifact summary\n'
    printf 'run_dir=%s\n' "$RUN_DIR"
    printf 'test_selector=%s\n' "$TEST_SELECTOR"
    printf 'device_artifact_dir=%s\n' "$DEVICE_ARTIFACT_DIR"
    printf '\nPulled artifacts:\n'
    find "$ARTIFACT_DIR" -maxdepth 1 -type f -printf '%f\t%k KB\n' | sort
    printf '\nAuthoritative TerminalView viewport renders:\n'
    find "$ARTIFACT_DIR" -maxdepth 1 -type f -name '*-viewport.png' -printf '%f\n' | sort
    printf '\nVisible terminal sidecars:\n'
    find "$ARTIFACT_DIR" -maxdepth 1 -type f -name '*-visible-terminal.txt' -printf '%f\n' | sort
    printf '\nFull-device screenshots:\n'
    find "$ARTIFACT_DIR" -maxdepth 1 -type f -name '*-full.png' -printf '%f\n' | sort
    printf '\nIssue #303 summary excerpt:\n'
    sed -n '1,80p' "$ARTIFACT_DIR/issue303-live-toolbar-summary.txt"
    printf '\nTimings:\n'
    grep '^issue303' "$ARTIFACT_DIR/timings.txt" || true
    printf '\nLogs:\n'
    printf '  ssh_readiness_log=%s\n' "$RUN_DIR/docker-ssh-readiness.log"
    printf '  instrumentation_log=%s\n' "$RUN_DIR/07-run-issue303-proof.log"
    printf '  docker_log=%s\n' "$RUN_DIR/docker-agents.log"
    printf '  logcat=%s\n' "$RUN_DIR/logcat.txt"
  } > "$RUN_DIR/artifact-summary.txt"
}

mkdir -p "$RUN_DIR"
cp "$SSH_KEY" "$HOST_SSH_KEY"
chmod 600 "$HOST_SSH_KEY"
trap 'collect_diagnostics; pocketshell_release_all' EXIT

printf 'PocketShell issue #303 live tmux toolbar proof workbench\n'
printf 'Artifacts: %s\n' "$RUN_DIR"
printf 'Agent service: %s\n' "$AGENT_SERVICE"
printf 'Test selector: %s\n' "$TEST_SELECTOR"

[[ -x "$ADB" ]] || fail "adb is not executable at $ADB"
[[ -x "$EMULATOR" ]] || fail "emulator is not executable at $EMULATOR"
[[ -f "$SSH_KEY" ]] || fail "SSH key missing at $SSH_KEY"
command -v ssh >/dev/null 2>&1 || fail "ssh client is missing"

if ! "$ADB" get-state >/dev/null 2>&1; then
  declare -a emulator_cmd=()
  pocketshell_build_sg_kvm_command emulator_cmd \
    "$EMULATOR" -avd "$AVD_NAME" -no-snapshot -no-window -gpu swiftshader_indirect -no-audio -no-boot-anim
  pocketshell_scope_start_background \
    "pocketshell-issue303-avd-$(pocketshell_unit_token "$RUN_ID")" \
    "$RUN_DIR/00-start-emulator.log" \
    "$RUN_DIR/00-start-emulator.pid" \
    "${emulator_cmd[@]}"
fi
run_logged "01-wait-emulator" wait_for_emulator
run_logged "02-docker-agents-up" docker compose -f "$COMPOSE_FILE" up -d --build "$AGENT_SERVICE"
run_logged "03-docker-ssh-readiness" wait_for_ssh_fixture

if [[ "$BUILD_APKS" == "1" ]]; then
  run_logged "04-build-apks" \
    "$ROOT_DIR/scripts/cgroup-run.sh" --unit "pocketshell-issue303-$(pocketshell_unit_token "$RUN_ID")-build-apks" -- \
    ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
else
  [[ -f "$APP_APK" ]] || fail "app APK missing at $APP_APK"
  [[ -f "$TEST_APK" ]] || fail "test APK missing at $TEST_APK"
fi

run_logged "05-install-apks" bash -lc "'$ADB' install -r -d -t '$APP_APK' && '$ADB' install -r -d -t '$TEST_APK'"
run_logged "06-reset-artifacts" "$ADB" shell rm -rf "$DEVICE_ARTIFACT_DIR"
run_logged "06b-clear-logcat" "$ADB" logcat -c

INSTRUMENTATION_ARGS=(
  -e additionalTestOutputDir "$DEVICE_OUTPUT_DIR"
  -e terminalWorkbenchSshPort "$SSH_PORT"
  -e class "$TEST_SELECTOR"
)
run_logged "07-run-issue303-proof" \
  "$ADB" shell am instrument -w -r \
  "${INSTRUMENTATION_ARGS[@]}" \
  com.pocketshell.app.test/androidx.test.runner.AndroidJUnitRunner

rm -rf "$RUN_DIR/artifacts"
mkdir -p "$RUN_DIR/artifacts"
run_logged "08-pull-artifacts" "$ADB" pull "$DEVICE_ARTIFACT_DIR" "$RUN_DIR/artifacts/"
if [[ -d "$ARTIFACT_DIR" ]]; then
  run_logged "09-artifact-file-info" file "$RUN_DIR"/artifacts/terminal-lab/* || true
fi

grep -q "OK (" "$RUN_DIR/07-run-issue303-proof.log" &&
  grep -q "INSTRUMENTATION_CODE: -1" "$RUN_DIR/07-run-issue303-proof.log" ||
  fail "issue303 instrumentation did not pass"

validate_artifacts

printf '\nPASS: issue #303 live tmux toolbar proof completed\n'
printf 'Artifacts: %s\n' "$ARTIFACT_DIR"
printf 'Summary: %s/artifact-summary.txt\n' "$RUN_DIR"
