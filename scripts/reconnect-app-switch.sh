#!/usr/bin/env bash
# Issue #548/#450/#577/#392/#177 — reproduce the short app-switch reconnect
# regression against the real Android app and deterministic Docker SSH target.
#
# The default selector runs the production-grace six-second background/foreground
# proof in BackgroundGraceReconnectE2eTest. The instrumentation test owns the
# assertions: after foregrounding, the tmux session must stay Connected and the
# UI must not show Connecting, Reconnecting, Disconnected, Tap Reconnect, or
# reconnect/reattach diagnostics inside the short TTL.
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
SSH_HOST="${SSH_HOST:-127.0.0.1}"
SSH_USER="${SSH_USER:-testuser}"
SSH_KEY="${SSH_KEY:-$ROOT_DIR/tests/docker/test_key}"
LOG_ROOT="${LOG_ROOT:-$ROOT_DIR/build/reconnect-app-switch}"
RUN_ID="${RUN_ID:-issue-548-$(date +%Y%m%d-%H%M%S)}"
RUN_DIR="$LOG_ROOT/$RUN_ID"
RUN_SSH_KEY="$RUN_DIR/test_key"
BUILD_APKS="${BUILD_APKS:-1}"
APP_APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
DEVICE_OUTPUT_DIR="/sdcard/Android/media/com.pocketshell.app/additional_test_output"
DEVICE_ARTIFACT_DIR="$DEVICE_OUTPUT_DIR/issue548-background-grace-reconnect"
ARTIFACT_DIR="$RUN_DIR/artifacts/issue548-background-grace-reconnect"
TEST_SELECTOR="${TEST_SELECTOR:-com.pocketshell.app.proof.BackgroundGraceReconnectE2eTest#sixSecondAppSwitchWithProductionGraceDoesNotShowOrRecordReconnect}"

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
    printf '[%s] health=healthy; running follow-up SSH sanity probe\n' "$(date -Is)"
    ssh \
      -i "$RUN_SSH_KEY" \
      -p "$SSH_PORT" \
      -o BatchMode=yes \
      -o ConnectTimeout=3 \
      -o ConnectionAttempts=1 \
      -o StrictHostKeyChecking=no \
      -o UserKnownHostsFile=/dev/null \
      "$SSH_USER@$SSH_HOST" \
      "printf 'issue548 ssh ready '; tmux -V"
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
    printf 'test_selector=%s\n' "$TEST_SELECTOR"
    printf 'timestamp=%s\n' "$(date -Is)"
  } > "$RUN_DIR/exit-status.txt"
  docker compose -f "$COMPOSE_FILE" ps > "$RUN_DIR/docker-compose-ps.txt" 2>&1 || true
  docker compose -f "$COMPOSE_FILE" logs --no-color --timestamps "$AGENT_SERVICE" > "$RUN_DIR/docker-agents.log" 2>&1 || true
  "$ADB" exec-out screencap -p > "$RUN_DIR/final-screen.png" 2>/dev/null || true
  "$ADB" logcat -d -v threadtime -t 6000 > "$RUN_DIR/logcat.txt" 2>&1 || true
}

validate_artifacts() {
  local instrumentation_log="$RUN_DIR/07-run-reconnect-app-switch.log"
  grep -q "OK (" "$instrumentation_log" &&
    grep -q "INSTRUMENTATION_CODE: -1" "$instrumentation_log" ||
    fail "reconnect app-switch instrumentation did not pass"

  [[ -d "$ARTIFACT_DIR" ]] || fail "artifact directory missing at $ARTIFACT_DIR"
  [[ -s "$ARTIFACT_DIR/timings.txt" ]] || fail "timings.txt is missing or empty"
  grep -q '^six_second_production_grace_cycle_ms=' "$ARTIFACT_DIR/timings.txt" ||
    fail "timings.txt is missing six_second_production_grace_cycle_ms"

  local before_viewport="$ARTIFACT_DIR/issue548-sixsec-01-attached-viewport.png"
  local after_viewport="$ARTIFACT_DIR/issue548-sixsec-02-foreground-viewport.png"
  local before_sidecar="$ARTIFACT_DIR/issue548-sixsec-01-attached-visible-terminal.txt"
  local after_sidecar="$ARTIFACT_DIR/issue548-sixsec-02-foreground-visible-terminal.txt"
  [[ -s "$before_viewport" ]] || fail "attached viewport is missing or empty: $before_viewport"
  [[ -s "$after_viewport" ]] || fail "foreground viewport is missing or empty: $after_viewport"
  [[ -s "$before_sidecar" ]] || fail "attached visible terminal sidecar is missing or empty: $before_sidecar"
  [[ -s "$after_sidecar" ]] || fail "foreground visible terminal sidecar is missing or empty: $after_sidecar"
  grep -q 'ISSUE548-BG-GRACE-READY' "$after_sidecar" ||
    fail "foreground sidecar is missing the seeded ready marker"

  {
    printf 'PocketShell issue #548 short app-switch reconnect artifact summary\n'
    printf 'run_dir=%s\n' "$RUN_DIR"
    printf 'test_selector=%s\n' "$TEST_SELECTOR"
    printf 'device_artifact_dir=%s\n' "$DEVICE_ARTIFACT_DIR"
    printf 'assertion=foreground after six-second background hold never shows reconnect/disconnect UI\n'
    printf '\nTimings:\n'
    cat "$ARTIFACT_DIR/timings.txt"
    printf '\nPulled artifacts:\n'
    find "$ARTIFACT_DIR" -maxdepth 1 -type f -printf '%f\t%k KB\n' | sort
    printf '\nLogs:\n'
    printf '  ssh_readiness_log=%s\n' "$RUN_DIR/docker-ssh-readiness.log"
    printf '  instrumentation_log=%s\n' "$instrumentation_log"
    printf '  docker_log=%s\n' "$RUN_DIR/docker-agents.log"
    printf '  logcat=%s\n' "$RUN_DIR/logcat.txt"
  } > "$RUN_DIR/artifact-summary.txt"
}

mkdir -p "$RUN_DIR"
trap collect_diagnostics EXIT

printf 'PocketShell short app-switch reconnect harness\n'
printf 'Artifacts: %s\n' "$RUN_DIR"
printf 'Agent service: %s\n' "$AGENT_SERVICE"
printf 'Test selector: %s\n' "$TEST_SELECTOR"

[[ -x "$ADB" ]] || fail "adb is not executable at $ADB"
[[ -x "$EMULATOR" ]] || fail "emulator is not executable at $EMULATOR"
[[ -f "$SSH_KEY" ]] || fail "SSH key missing at $SSH_KEY"
command -v ssh >/dev/null 2>&1 || fail "ssh client is missing"
cp "$SSH_KEY" "$RUN_SSH_KEY"
chmod 600 "$RUN_SSH_KEY"

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
run_logged "07-run-reconnect-app-switch" \
  "$ADB" shell am instrument -w -r \
  "${INSTRUMENTATION_ARGS[@]}" \
  com.pocketshell.app.test/androidx.test.runner.AndroidJUnitRunner

mkdir -p "$RUN_DIR/artifacts"
rm -rf "$RUN_DIR/artifacts"
mkdir -p "$RUN_DIR/artifacts"
run_logged "08-pull-artifacts" "$ADB" pull "$DEVICE_ARTIFACT_DIR" "$RUN_DIR/artifacts/"
if [[ -d "$ARTIFACT_DIR" ]]; then
  run_logged "09-artifact-file-info" file "$ARTIFACT_DIR"/* || true
fi

validate_artifacts
