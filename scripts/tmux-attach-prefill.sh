#!/usr/bin/env bash
# Issue #103 — Tmux attach should render full screen quickly.
#
# Runs the real-app tmux attach instrumentation
# (`TmuxAttachPrefillDockerTest#attachExistingTmuxSessionPrefillsFullScreenQuickly`)
# against the local Android emulator and the deterministic Docker `agents`
# fixture, then pulls the authoritative terminal viewport screenshots, visible
# terminal sidecars, timing breakdown, and capture summary into the standard
# terminal-workbench artifact location
# `build/terminal-workbench/<run-id>/artifacts/terminal-lab/`.
#
# Acceptance evidence emitted (verified by the validator below):
#   - issue103-01-before-attach-viewport.png
#   - issue103-01-before-attach-visible-terminal.txt
#   - issue103-02-after-attach-viewport.png
#   - issue103-02-after-attach-visible-terminal.txt
#   - issue103-summary.txt              (terminal grid, bounds, per-stage stamps)
#   - timings.txt                       (per-stage timing including
#                                        attach_tap_to_first_content_ms and the
#                                        500 ms target line)
#   - artifact-summary.txt              (top-level overview written by this
#                                        script)
#   - docker-agents.log / docker-ssh-readiness.log / logcat.txt
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
EMULATOR="${EMULATOR:-$ANDROID_SDK/emulator/emulator}"
AVD_NAME="${AVD_NAME:-test}"
COMPOSE_FILE="${COMPOSE_FILE:-tests/docker/docker-compose.yml}"
AGENT_SERVICE="${AGENT_SERVICE:-agents}"
SSH_PORT="${SSH_PORT:-2222}"
LOG_ROOT="${LOG_ROOT:-$ROOT_DIR/build/terminal-workbench}"
RUN_ID="${RUN_ID:-issue-103-$(date +%Y%m%d-%H%M%S)}"
RUN_DIR="$LOG_ROOT/$RUN_ID"
ARTIFACT_DIR="$RUN_DIR/artifacts/terminal-lab"
BUILD_APKS="${BUILD_APKS:-1}"
APP_APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
SSH_KEY="${SSH_KEY:-$ROOT_DIR/tests/docker/test_key}"
SSH_HOST="${SSH_HOST:-127.0.0.1}"
SSH_USER="${SSH_USER:-testuser}"
DEVICE_OUTPUT_DIR="/sdcard/Android/media/com.pocketshell.app/additional_test_output"
DEVICE_ARTIFACT_DIR="$DEVICE_OUTPUT_DIR/terminal-lab"
TEST_SELECTOR="${TEST_SELECTOR:-com.pocketshell.app.tmux.TmuxAttachPrefillDockerTest#attachExistingTmuxSessionPrefillsFullScreenQuickly}"

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

# Issue #150: wait on the compose `healthcheck:` block via
# `docker inspect`, not a host-side SSH retry loop. Keep one follow-up
# SSH probe so the readiness log still records the same "tmux ready"
# evidence reviewers look for.
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
      "printf 'issue103 ssh ready '; tmux -V"
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

validate_artifacts() {
  [[ -d "$ARTIFACT_DIR" ]] || fail "artifact directory missing at $ARTIFACT_DIR"
  [[ -s "$ARTIFACT_DIR/timings.txt" ]] || fail "timings.txt is missing or empty"
  grep -q '^attach_tap_to_first_content_ms=' "$ARTIFACT_DIR/timings.txt" ||
    fail "timings.txt is missing attach_tap_to_first_content_ms"
  grep -q '^attach_tap_to_first_content_target_ms=' "$ARTIFACT_DIR/timings.txt" ||
    fail "timings.txt is missing attach_tap_to_first_content_target_ms"

  local before_viewport="$ARTIFACT_DIR/issue103-01-before-attach-viewport.png"
  local after_viewport="$ARTIFACT_DIR/issue103-02-after-attach-viewport.png"
  local before_sidecar="$ARTIFACT_DIR/issue103-01-before-attach-visible-terminal.txt"
  local after_sidecar="$ARTIFACT_DIR/issue103-02-after-attach-visible-terminal.txt"
  [[ -s "$before_viewport" ]] || fail "before-attach viewport is missing or empty: $before_viewport"
  [[ -s "$after_viewport" ]] || fail "after-attach viewport is missing or empty: $after_viewport"
  [[ -f "$before_sidecar" ]] || fail "before-attach sidecar is missing: $before_sidecar"
  [[ -s "$after_sidecar" ]] || fail "after-attach sidecar is missing or empty: $after_sidecar"

  grep -q 'issue103-seed-line-001' "$after_sidecar" ||
    fail "after-attach sidecar is missing first seeded line marker"
  # The instrumentation chooses its seed line count to match the pane height
  # (currently 23 lines for a 24-row pane) so the visible region is filled
  # end-to-end. The summary file records the actual `last_seed_line` so the
  # validator can match it without hard-coding the line count here.
  local last_seed_line
  last_seed_line="$(awk -F= '/^last_seed_line=/ { print $2 }' "$ARTIFACT_DIR/issue103-summary.txt" | tail -n 1)"
  [[ -n "$last_seed_line" ]] || fail "issue103-summary.txt is missing last_seed_line"
  grep -q "$last_seed_line" "$after_sidecar" ||
    fail "after-attach sidecar is missing last seeded line marker $last_seed_line (full-screen prefill not visible)"

  [[ -s "$ARTIFACT_DIR/issue103-summary.txt" ]] || fail "issue103-summary.txt is missing or empty"
  grep -q '^capture_policy:' "$ARTIFACT_DIR/issue103-summary.txt" ||
    fail "issue103-summary.txt is missing capture_policy"

  local first_content_ms target_ms
  first_content_ms="$(awk -F= '/^attach_tap_to_first_content_ms=/ { print $2 }' "$ARTIFACT_DIR/timings.txt" | tail -n 1)"
  target_ms="$(awk -F= '/^attach_tap_to_first_content_target_ms=/ { print $2 }' "$ARTIFACT_DIR/timings.txt" | tail -n 1)"
  if [[ ! "$first_content_ms" =~ ^[0-9]+$ ]]; then
    fail "attach_tap_to_first_content_ms is missing or not numeric in timings.txt"
  fi
  if [[ ! "$target_ms" =~ ^[0-9]+$ ]]; then
    fail "attach_tap_to_first_content_target_ms is missing or not numeric in timings.txt"
  fi

  local within_target=true
  if (( first_content_ms > target_ms )); then
    within_target=false
  fi

  {
    printf 'PocketShell issue #103 tmux attach prefill artifact summary\n'
    printf 'run_dir=%s\n' "$RUN_DIR"
    printf 'test_selector=%s\n' "$TEST_SELECTOR"
    printf 'device_artifact_dir=%s\n' "$DEVICE_ARTIFACT_DIR"
    printf 'attach_tap_to_first_content_ms=%s\n' "$first_content_ms"
    printf 'attach_tap_to_first_content_target_ms=%s\n' "$target_ms"
    printf 'attach_tap_within_target=%s\n' "$within_target"
    printf '\nPulled artifacts:\n'
    find "$ARTIFACT_DIR" -maxdepth 1 -type f -printf '%f\t%k KB\n' | sort
    printf '\nAuthoritative terminal viewport renders:\n'
    find "$ARTIFACT_DIR" -maxdepth 1 -type f -name '*-viewport.png' -printf '%f\n' | sort
    printf '\nAdvisory captures (none for this scenario):\n'
    find "$ARTIFACT_DIR" -maxdepth 1 -type f -name '*.png' ! -name '*-viewport.png' -printf '%f\n' | sort
    printf '\nCapture summaries:\n'
    find "$ARTIFACT_DIR" -maxdepth 1 -type f -name '*-summary.txt' -print0 |
      sort -z |
      while IFS= read -r -d '' summary_file; do
        printf '\n--- %s ---\n' "$(basename "$summary_file")"
        sed -n '/^capture_policy:/,/^visible_terminal:/p' "$summary_file" | sed '$d'
      done
    printf '\nLogs:\n'
    printf '  ssh_readiness_log=%s\n' "$RUN_DIR/docker-ssh-readiness.log"
    printf '  instrumentation_log=%s\n' "$RUN_DIR/07-run-tmux-attach-prefill.log"
    printf '  docker_log=%s\n' "$RUN_DIR/docker-agents.log"
    printf '  logcat=%s\n' "$RUN_DIR/logcat.txt"
  } > "$RUN_DIR/artifact-summary.txt"
}

mkdir -p "$RUN_DIR"
trap collect_diagnostics EXIT

printf 'PocketShell issue #103 tmux attach prefill workbench\n'
printf 'Artifacts: %s\n' "$RUN_DIR"
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
  -e class "$TEST_SELECTOR"
)
run_logged "07-run-tmux-attach-prefill" \
  "$ADB" shell am instrument -w -r \
  "${INSTRUMENTATION_ARGS[@]}" \
  com.pocketshell.app.test/androidx.test.runner.AndroidJUnitRunner

mkdir -p "$RUN_DIR/artifacts"
rm -rf "$RUN_DIR/artifacts"
mkdir -p "$RUN_DIR/artifacts"
run_logged "08-pull-artifacts" "$ADB" pull "$DEVICE_ARTIFACT_DIR" "$RUN_DIR/artifacts/"
if [[ -d "$ARTIFACT_DIR" ]]; then
  run_logged "09-artifact-file-info" file "$RUN_DIR"/artifacts/terminal-lab/* || true
fi

grep -q "OK (" "$RUN_DIR/07-run-tmux-attach-prefill.log" &&
  grep -q "INSTRUMENTATION_CODE: -1" "$RUN_DIR/07-run-tmux-attach-prefill.log" ||
  fail "tmux-attach-prefill instrumentation did not pass"

validate_artifacts

printf '\nPASS: tmux attach prefill workbench completed\n'
printf 'Artifacts: %s\n' "$ARTIFACT_DIR"
printf 'Summary: %s/artifact-summary.txt\n' "$RUN_DIR"
