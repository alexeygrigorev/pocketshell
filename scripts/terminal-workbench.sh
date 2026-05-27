#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Acquire an exclusive AVD lock so parallel-worktree gate runs serialize on the
# shared local Android emulator. Sibling `connectedAndroidTest` invocations
# from individual implementer/reviewer worktrees are intentionally NOT held by
# this lock — only the release-gate scripts that drive long sequential
# emulator workflows (see issue #182). When invoked from a parent gate script
# that already holds the lock, the env-var guard makes this a no-op.
LOCK_FILE="${POCKETSHELL_AVD_LOCK_FILE:-$ROOT_DIR/build/.avd-lock}"
if [[ -z "${POCKETSHELL_AVD_LOCK_ACQUIRED:-}" ]]; then
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
COMPOSE_FILE_WAS_SET=0
if [[ -n "${COMPOSE_FILE:-}" ]]; then
  COMPOSE_FILE_WAS_SET=1
fi
COMPOSE_FILE="${COMPOSE_FILE:-tests/docker/docker-compose.yml}"
LOG_ROOT="${LOG_ROOT:-$ROOT_DIR/build/terminal-workbench}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RUN_DIR="$LOG_ROOT/$RUN_ID"
ARTIFACT_DIR="$RUN_DIR/artifacts/terminal-lab"
BUILD_APKS="${BUILD_APKS:-1}"
HOLD_MS="${HOLD_MS:-0}"
DEBUG_HOLD_MS="${DEBUG_HOLD_MS:-0}"
REAL_AGENTS="${REAL_AGENTS:-0}"
ALLOW_DUPLICATE_VIEWPORT_HASHES="${ALLOW_DUPLICATE_VIEWPORT_HASHES:-0}"
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

extract_field() {
  local line="$1"
  local key="$2"
  printf '%s\n' "$line" | sed -n "s/.* $key=\\([^ ]*\\).*/\\1/p"
}

require_positive_integer() {
  local value="$1"
  local label="$2"
  [[ "$value" =~ ^[0-9]+$ ]] || fail "$label is not a number: $value"
  (( value > 0 )) || fail "$label is not positive: $value"
}

validate_terminal_artifacts() {
  [[ -d "$ARTIFACT_DIR" ]] || fail "terminal artifact directory missing at $ARTIFACT_DIR"
  [[ -s "$ARTIFACT_DIR/timings.txt" ]] || fail "terminal timings artifact is missing or empty"
  grep -q 'send_to_output_.*_pty_size_ms=' "$ARTIFACT_DIR/timings.txt" ||
    fail "terminal timings are missing PTY sizing evidence"

  local summary_files=()
  while IFS= read -r -d '' summary_file; do
    summary_files+=("$summary_file")
  done < <(find "$ARTIFACT_DIR" -maxdepth 1 -type f -name '*-summary.txt' -print0 | sort -z)
  (( ${#summary_files[@]} > 0 )) || fail "no terminal capture summary artifacts were pulled"

  local viewport_count=0
  local visible_count=0
  local real_agent_cli_count=0
  local hash_index="$RUN_DIR/viewport-hashes.txt"
  : > "$hash_index"

  local summary_file
  for summary_file in "${summary_files[@]}"; do
    [[ -s "$summary_file" ]] || fail "terminal capture summary is empty: $summary_file"
    grep -q '^capture_policy:' "$summary_file" ||
      fail "terminal capture summary is missing capture_policy: $summary_file"
    grep -q '^authoritative=direct TerminalView viewport render plus terminal emulator visible text$' "$summary_file" ||
      fail "terminal capture summary is missing authoritative capture policy: $summary_file"

    local columns rows visible_chars
    columns="$(sed -n 's/^terminal_grid_columns=//p' "$summary_file" | head -n 1)"
    rows="$(sed -n 's/^terminal_grid_rows=//p' "$summary_file" | head -n 1)"
    visible_chars="$(sed -n 's/^visible_terminal_chars=//p' "$summary_file" | head -n 1)"
    require_positive_integer "$columns" "terminal grid columns in $(basename "$summary_file")"
    require_positive_integer "$rows" "terminal grid rows in $(basename "$summary_file")"
    require_positive_integer "$visible_chars" "visible terminal chars in $(basename "$summary_file")"

    local capture_lines=()
    while IFS= read -r capture_line; do
      [[ -n "$capture_line" ]] && capture_lines+=("$capture_line")
    done < <(
      awk '
        /^authoritative_captures:/ { in_section=1; next }
        in_section && /^$/ { exit }
        in_section && /-viewport[.]png/ { print }
      ' "$summary_file"
    )
    (( ${#capture_lines[@]} > 0 )) ||
      fail "terminal capture summary has no authoritative viewport captures: $summary_file"

    local capture_line
    for capture_line in "${capture_lines[@]}"; do
      local file_name viewport_file viewport_pixels viewport_sha visible_capture_chars actual_sha sidecar
      file_name="${capture_line%% *}"
      viewport_file="$ARTIFACT_DIR/$file_name"
      [[ -s "$viewport_file" ]] || fail "authoritative viewport artifact is missing or empty: $viewport_file"

      viewport_pixels="$(extract_field "$capture_line" "viewport_bright_pixels")"
      viewport_sha="$(extract_field "$capture_line" "viewport_sha256")"
      visible_capture_chars="$(extract_field "$capture_line" "visible_terminal_chars")"
      require_positive_integer "$viewport_pixels" "viewport bright pixels for $file_name"
      require_positive_integer "$visible_capture_chars" "visible terminal chars for $file_name"
      [[ "$viewport_sha" =~ ^[0-9a-f]{64}$ ]] ||
        fail "viewport summary hash is missing or invalid for $file_name: $viewport_sha"
      actual_sha="$(sha256sum "$viewport_file" | awk '{print $1}')"
      [[ "$actual_sha" == "$viewport_sha" ]] ||
        fail "viewport hash mismatch for $file_name; summary=$viewport_sha actual=$actual_sha"

      sidecar="${viewport_file%-viewport.png}-visible-terminal.txt"
      [[ -s "$sidecar" ]] || fail "visible terminal text sidecar is missing or empty: $sidecar"
      grep -q '[[:graph:]]' "$sidecar" ||
        fail "visible terminal text sidecar has no printable content: $sidecar"

      viewport_count=$((viewport_count + 1))
      visible_count=$((visible_count + 1))
      case "$file_name" in
        *held-open*|*debug-hold*)
          ;;
        *)
          printf '%s\t%s\n' "$viewport_sha" "$file_name" >> "$hash_index"
          ;;
      esac
      case "$file_name" in
        agents-0[2-9]-*-viewport.png|agents-[1-9][0-9]-*-viewport.png)
          real_agent_cli_count=$((real_agent_cli_count + 1))
          ;;
      esac
    done
  done

  if [[ "$ALLOW_DUPLICATE_VIEWPORT_HASHES" != "1" ]]; then
    local duplicate_hashes
    duplicate_hashes="$(awk -F '\t' '{ count[$1]++; names[$1]=names[$1] " " $2 } END { for (hash in count) if (count[hash] > 1) print hash names[hash] }' "$hash_index")"
    [[ -z "$duplicate_hashes" ]] ||
      fail "duplicate authoritative viewport hashes suggest stale captures: $duplicate_hashes"
  fi

  if [[ "$REAL_AGENTS" == "1" ]]; then
    (( real_agent_cli_count > 0 )) ||
      fail "real-agent workbench did not produce an interactive agent CLI viewport"
    find "$ARTIFACT_DIR" -maxdepth 1 -type f -name 'agents-*-visible-terminal.txt' -print0 |
      xargs -0 grep -E 'Ask anything|Welcome to Codex|Welcome to Claude Code' >/dev/null ||
      fail "real-agent visible terminal artifacts are missing expected interactive CLI screen text"
  fi

  {
    printf '\nValidation:\n'
    printf 'status=PASS\n'
    printf 'authoritative_viewport_count=%s\n' "$viewport_count"
    printf 'visible_terminal_sidecar_count=%s\n' "$visible_count"
    printf 'duplicate_viewport_hash_check=%s\n' "$([[ "$ALLOW_DUPLICATE_VIEWPORT_HASHES" == "1" ]] && printf skipped || printf passed)"
    printf 'real_agent_cli_viewport_count=%s\n' "$real_agent_cli_count"
    printf 'ssh_readiness_log=%s\n' "$RUN_DIR/docker-ssh-readiness.log"
    printf 'instrumentation_log=%s\n' "$RUN_DIR/07-run-workbench.log"
    printf 'docker_log=%s\n' "$RUN_DIR/docker-agents.log"
    printf 'logcat=%s\n' "$RUN_DIR/logcat.txt"
  } >> "$RUN_DIR/artifact-summary.txt"
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

# Issue #150: wait on the compose `healthcheck:` block via
# `docker inspect`, not a host-side SSH retry loop. Keep a single
# follow-up SSH probe so the readiness log still records the same
# "tmux ready" sanity-check evidence reviewers look for.
source "$ROOT_DIR/tests/docker/lib/wait-for-healthy.sh"

wait_for_ssh_fixture() {
  local log_file="$RUN_DIR/docker-ssh-readiness.log"
  if ! wait_for_container_healthy "$COMPOSE_FILE" "$AGENT_SERVICE" "$log_file" 60; then
    tail -n 80 "$log_file" || true
    fail "Docker SSH fixture did not become healthy"
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
      "printf 'terminal workbench ssh ready '; tmux -V"
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
  "$ADB" logcat -d -v threadtime -t 4000 > "$RUN_DIR/logcat.txt" 2>&1 || true
}

mkdir -p "$RUN_DIR"
trap collect_diagnostics EXIT

printf 'PocketShell terminal workbench\n'
printf 'Artifacts: %s\n' "$RUN_DIR"
printf 'Hold ms: %s\n' "$HOLD_MS"
printf 'Debug hold ms: %s\n' "$DEBUG_HOLD_MS"
printf 'Real agents: %s\n' "$REAL_AGENTS"
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
INSTRUMENTATION_ARGS=(
  -e additionalTestOutputDir "$DEVICE_OUTPUT_DIR"
  -e terminalWorkbenchHoldMs "$HOLD_MS"
  -e terminalWorkbenchDebugHoldMs "$DEBUG_HOLD_MS"
  -e terminalWorkbenchSshPort "$SSH_PORT"
  -e class "$TEST_SELECTOR"
)
if [[ "$REAL_AGENTS" == "1" ]]; then
  INSTRUMENTATION_ARGS+=(-e terminalWorkbenchRealAgents 1)
fi
run_logged "07-run-workbench" \
  "$ADB" shell am instrument -w -r \
  "${INSTRUMENTATION_ARGS[@]}" \
  com.pocketshell.app.test/androidx.test.runner.AndroidJUnitRunner
mkdir -p "$RUN_DIR/artifacts"
rm -rf "$RUN_DIR/artifacts"
mkdir -p "$RUN_DIR/artifacts"
run_logged "08-pull-artifacts" "$ADB" pull "$DEVICE_ARTIFACT_DIR" "$RUN_DIR/artifacts/"
if [[ -d "$ARTIFACT_DIR" ]]; then
  run_logged "09-artifact-file-info" file "$RUN_DIR"/artifacts/terminal-lab/* || true
  {
    printf 'PocketShell terminal workbench artifact summary\n'
    printf 'run_dir=%s\n' "$RUN_DIR"
    printf 'real_agents=%s\n' "$REAL_AGENTS"
    printf 'test_selector=%s\n' "$TEST_SELECTOR"
    printf 'hold_ms=%s\n' "$HOLD_MS"
    printf 'debug_hold_ms=%s\n' "$DEBUG_HOLD_MS"
    printf 'device_artifact_dir=%s\n' "$DEVICE_ARTIFACT_DIR"
    printf '\nPulled artifacts:\n'
    find "$ARTIFACT_DIR" -maxdepth 1 -type f -printf '%f\t%k KB\n' | sort
    printf '\nAuthoritative terminal viewport renders:\n'
    find "$ARTIFACT_DIR" -maxdepth 1 -type f -name '*-viewport.png' -printf '%f\n' | sort
    printf '\nAdvisory full-device/window screenshots:\n'
    find "$ARTIFACT_DIR" -maxdepth 1 -type f -name '*.png' ! -name '*-viewport.png' -printf '%f\n' | sort
    printf '\nCapture summaries:\n'
    find "$ARTIFACT_DIR" -maxdepth 1 -type f -name '*-summary.txt' -print0 |
      sort -z |
      while IFS= read -r -d '' summary_file; do
        printf '\n--- %s ---\n' "$(basename "$summary_file")"
        sed -n '/^capture_policy:/,/^visible_terminal:/p' "$summary_file" | sed '$d'
      done
  } > "$RUN_DIR/artifact-summary.txt"
fi

grep -q "OK (" "$RUN_DIR/07-run-workbench.log" &&
  grep -q "INSTRUMENTATION_CODE: -1" "$RUN_DIR/07-run-workbench.log" ||
  fail "terminal workbench instrumentation did not pass"

validate_terminal_artifacts

printf '\nPASS: terminal workbench completed\n'
printf 'Artifacts: %s\n' "$ARTIFACT_DIR"
printf 'Summary: %s/artifact-summary.txt\n' "$RUN_DIR"
