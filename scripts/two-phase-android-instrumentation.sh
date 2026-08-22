#!/usr/bin/env bash
# Reusable host-owned Android process-restart harness (issues #2264/#1715).
#
# Builds and installs one suffixed target/test APK pair, invokes two selected
# androidTest methods through separate direct `adb shell am instrument` calls,
# and externally force-stops both packages between them. Package state is never
# cleared, uninstalled, or reinstalled between phases.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# shellcheck source=scripts/lib/avd-lock.sh
# shellcheck disable=SC1091
source "$ROOT_DIR/scripts/lib/avd-lock.sh"

ANDROID_SDK="${ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/alexey/Android/Sdk}}}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
SUFFIX="${SUFFIX:-i2264}"
PROOF_KIND="${PROOF_KIND:-last-session}"
TEST_CLASS="${TEST_CLASS:-com.pocketshell.app.proof.LastSessionProcessRestartProofTest}"
PHASE1_METHOD="${PHASE1_METHOD:-phaseOnePersistsExactSuccessorGeneration}"
PHASE2_METHOD="${PHASE2_METHOD:-phaseTwoRestoresExactSuccessorGeneration}"
RUN_NAMESPACE="${RUN_NAMESPACE:-issue2264-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
BUILD_APKS="${BUILD_APKS:-1}"
TARGET_PACKAGE="com.pocketshell.app.$SUFFIX"
TEST_PACKAGE="$TARGET_PACKAGE.test"
RUNNER_COMPONENT="$TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner"
APP_APK="${APP_APK:-$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk}"
TEST_APK="${TEST_APK:-$ROOT_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"
CACHE_BASE="${XDG_CACHE_HOME:-${HOME:?HOME is required}/.cache}"
EVIDENCE_ROOT="${EVIDENCE_ROOT:-$CACHE_BASE/pocketshell/evidence/android-process-restart}"
RUN_DIR="${RUN_DIR:-$EVIDENCE_ROOT/$RUN_NAMESPACE}"
DEVICE_DIR="/sdcard/Android/media/$TARGET_PACKAGE/process-restart/$RUN_NAMESPACE"
FORCE_STOP_WAIT_SECONDS="${FORCE_STOP_WAIT_SECONDS:-10}"
PHASE1_READY_WAIT_SECONDS="${PHASE1_READY_WAIT_SECONDS:-60}"
PHASE1_REAP_WAIT_SECONDS="${PHASE1_REAP_WAIT_SECONDS:-10}"
PHASE1_KEEPALIVE_MILLIS="${PHASE1_KEEPALIVE_MILLIS:-120000}"
EXPECTED_GENERATION_ORIGIN="${EXPECTED_GENERATION_ORIGIN:-agents-daemon-2239-tmux-list-sessions-through-SshHostTmuxSessionsGateway-to-navigation-to-on-stop-to-last-session-store}"
EXPECTED_PRODUCER_FIXTURE_NAME="${EXPECTED_PRODUCER_FIXTURE_NAME:-agents-daemon-2239}"
EXPECTED_PRODUCER_FIXTURE_HOST="${EXPECTED_PRODUCER_FIXTURE_HOST:-10.0.2.2}"
EXPECTED_PRODUCER_FIXTURE_PORT="${EXPECTED_PRODUCER_FIXTURE_PORT:-2239}"
EXPECTED_PRODUCER_FIXTURE_USER="${EXPECTED_PRODUCER_FIXTURE_USER:-testuser}"
EXPECTED_PRODUCER_SESSION_PREFIX="${EXPECTED_PRODUCER_SESSION_PREFIX:-issue2264-}"
EXPECTED_PHASE1_PERSISTENCE_ORIGIN="${EXPECTED_PHASE1_PERSISTENCE_ORIGIN:-LastSessionStore.save}"
EXPECTED_PHASE2_PERSISTENCE_ORIGIN="${EXPECTED_PHASE2_PERSISTENCE_ORIGIN:-LastSessionStore.read}"

usage() {
  cat <<'USAGE'
Usage: scripts/two-phase-android-instrumentation.sh

Environment:
  SUFFIX=i2264                 required suffixed debug application id
  TEST_CLASS=<fqcn>            class containing the two phase methods
  PHASE1_METHOD=<method>       persistence phase selector
  PHASE2_METHOD=<method>       restore phase selector
  RUN_NAMESPACE=<token>        artifact namespace ([A-Za-z0-9._-]+)
  BUILD_APKS=1                 assemble suffixed app/test APKs before install
  APP_APK=/path/app.apk        override target APK (required with BUILD_APKS=0)
  TEST_APK=/path/test.apk      override test APK (required with BUILD_APKS=0)
  RUN_DIR=/durable/path        host evidence destination
  ANDROID_SERIAL=emulator-N    pin one emulator; otherwise claim a free one
  PHASE1_READY_WAIT_SECONDS=60 wait for the device ready marker
  PHASE1_REAP_WAIT_SECONDS=10 wait for interrupted phase-1 am instrument
  PHASE1_KEEPALIVE_MILLIS=120000 device-side phase-1 keepalive ceiling

The harness performs initial cleanup before the one install. Between phase 1
and phase 2 it performs only external force-stop operations: never uninstall,
pm clear, reinstall, or connected-test cleanup.
USAGE
}

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  printf 'Evidence: %s\n' "$RUN_DIR" >&2
  exit 1
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi
if [[ $# -ne 0 ]]; then
  usage >&2
  exit 2
fi

[[ "$SUFFIX" =~ ^[A-Za-z0-9._]+$ ]] || fail "SUFFIX must match [A-Za-z0-9._]+"
[[ -n "$SUFFIX" ]] || fail "a non-empty suffix is mandatory"
[[ "$PROOF_KIND" == "last-session" || "$PROOF_KIND" == "file-viewer-workspace" ]] \
  || fail "PROOF_KIND must be last-session or file-viewer-workspace"
[[ "$RUN_NAMESPACE" =~ ^[A-Za-z0-9._-]+$ ]] || fail "RUN_NAMESPACE must match [A-Za-z0-9._-]+"
[[ "$TEST_CLASS" =~ ^[A-Za-z0-9_.]+$ ]] || fail "invalid TEST_CLASS"
[[ "$PHASE1_METHOD" =~ ^[A-Za-z0-9_]+$ ]] || fail "invalid PHASE1_METHOD"
[[ "$PHASE2_METHOD" =~ ^[A-Za-z0-9_]+$ ]] || fail "invalid PHASE2_METHOD"
[[ "$BUILD_APKS" == "0" || "$BUILD_APKS" == "1" ]] || fail "BUILD_APKS must be 0 or 1"
[[ "$FORCE_STOP_WAIT_SECONDS" =~ ^[0-9]+$ ]] || fail "FORCE_STOP_WAIT_SECONDS must be numeric"
[[ "$PHASE1_READY_WAIT_SECONDS" =~ ^[0-9]+$ && "$PHASE1_READY_WAIT_SECONDS" -gt 0 ]] \
  || fail "PHASE1_READY_WAIT_SECONDS must be a positive integer"
[[ "$PHASE1_REAP_WAIT_SECONDS" =~ ^[0-9]+$ && "$PHASE1_REAP_WAIT_SECONDS" -gt 0 ]] \
  || fail "PHASE1_REAP_WAIT_SECONDS must be a positive integer"
[[ "$PHASE1_KEEPALIVE_MILLIS" =~ ^[0-9]+$ && "$PHASE1_KEEPALIVE_MILLIS" -gt 0 ]] \
  || fail "PHASE1_KEEPALIVE_MILLIS must be a positive integer"

[[ ! -e "$RUN_DIR" ]] || fail "RUN_DIR already exists; refusing stale/mixed evidence"
mkdir -p "$RUN_DIR"
chmod 700 "$RUN_DIR"

# One continuous machine-wide/per-serial lock covers initial cleanup, both
# installs, both instrumentation processes, the force-stop boundary, and every
# artifact pull. Children close their inherited lock descriptor.
export POCKETSHELL_AVD_LOCK_CONTINUOUS=1
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  export ADB ANDROID_SDK
  pocketshell_claim_pool_serial "$ROOT_DIR" >/dev/null \
    || fail "no unowned online emulator is available"
else
  POCKETSHELL_AVD_LOCK_FILE="$(pocketshell_avd_lock_file_for_serial "$ROOT_DIR" "$ANDROID_SERIAL")"
  export POCKETSHELL_AVD_LOCK_FILE
  pocketshell_acquire_avd_lock "$ROOT_DIR" || fail "cannot acquire serial ownership lock"
fi
pocketshell_assert_avd_lock_owned "$POCKETSHELL_AVD_LOCK_FILE" \
  || fail "serial ownership lock is not held"

adb_cmd() {
  pocketshell_run_without_avd_lock_fd "$ADB" -s "$ANDROID_SERIAL" "$@"
}

adb_mutate() {
  pocketshell_assert_avd_lock_owned "$POCKETSHELL_AVD_LOCK_FILE" \
    || fail "lost serial ownership before adb mutation"
  {
    printf '%s command=' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '%q ' "$@"
    printf '\n'
  } >> "$RUN_DIR/adb-mutations.log"
  adb_cmd "$@"
}

record_event() {
  local timestamp
  timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '%s event=%s\n' "$timestamp" "$1" >> "$RUN_DIR/package-mutations.log"
  printf '%s marker=%s\n' "$timestamp" "$1" >> "$RUN_DIR/adb-mutations.log"
}

write_fixture_identity() {
  {
    printf 'run_namespace=%s\n' "$RUN_NAMESPACE"
    printf 'android_serial=%s\n' "$ANDROID_SERIAL"
    printf 'avd_lock_file=%s\n' "$POCKETSHELL_AVD_LOCK_FILE"
    printf 'target_package=%s\n' "$TARGET_PACKAGE"
    printf 'test_package=%s\n' "$TEST_PACKAGE"
    printf 'runner_component=%s\n' "$RUNNER_COMPONENT"
    printf 'build_fingerprint=%s\n' "$(adb_cmd shell getprop ro.build.fingerprint | tr -d '\r')"
    printf 'avd_name=%s\n' "$(adb_cmd shell getprop ro.boot.qemu.avd_name | tr -d '\r')"
  } > "$RUN_DIR/device-fixture.txt"
}

artifact_value() {
  local artifact="$1" key="$2" count
  count="$(grep -c "^${key}=" "$artifact" || true)"
  [[ "$count" == "1" ]] || fail "$artifact must contain exactly one $key field (found $count)"
  sed -n "s/^${key}=//p" "$artifact"
}

validate_phase_artifact() {
  local phase="$1" artifact="$2"
  if [[ "$PROOF_KIND" == "file-viewer-workspace" ]]; then
    validate_file_viewer_workspace_artifact "$phase" "$artifact"
    return
  fi
  [[ -s "$artifact" ]] || fail "phase $phase artifact is absent or empty"
  [[ "$(artifact_value "$artifact" schema)" == "1" ]] || fail "phase $phase artifact schema mismatch"
  [[ "$(artifact_value "$artifact" run_namespace)" == "$RUN_NAMESPACE" ]] \
    || fail "phase $phase namespace mismatch"
  [[ "$(artifact_value "$artifact" phase)" == "$phase" ]] || fail "phase $phase marker mismatch"
  local pid process_name target_package test_package generation_origin persistence_origin
  local session_id session_created predecessor_id predecessor_created predecessor_reappeared
  local producer_fixture_name producer_fixture_host producer_fixture_port producer_fixture_user
  local producer_key_path producer_session_name
  pid="$(artifact_value "$artifact" pid)"
  process_name="$(artifact_value "$artifact" process_name)"
  target_package="$(artifact_value "$artifact" target_package)"
  test_package="$(artifact_value "$artifact" test_package)"
  generation_origin="$(artifact_value "$artifact" generation_origin)"
  persistence_origin="$(artifact_value "$artifact" persistence_origin)"
  session_id="$(artifact_value "$artifact" tmux_session_id)"
  session_created="$(artifact_value "$artifact" session_created)"
  predecessor_id="$(artifact_value "$artifact" predecessor_tmux_session_id)"
  predecessor_created="$(artifact_value "$artifact" predecessor_session_created)"
  predecessor_reappeared="$(artifact_value "$artifact" predecessor_reappeared)"
  producer_fixture_name="$(artifact_value "$artifact" producer_fixture_name)"
  producer_fixture_host="$(artifact_value "$artifact" producer_fixture_host)"
  producer_fixture_port="$(artifact_value "$artifact" producer_fixture_port)"
  producer_fixture_user="$(artifact_value "$artifact" producer_fixture_user)"
  producer_key_path="$(artifact_value "$artifact" producer_key_path)"
  producer_session_name="$(artifact_value "$artifact" producer_session_name)"
  [[ "$pid" =~ ^[0-9]+$ && "$pid" -gt 1 ]] || fail "phase $phase PID is not real: $pid"
  [[ "$process_name" == "$TARGET_PACKAGE" ]] || fail "phase $phase ran in $process_name, not $TARGET_PACKAGE"
  [[ "$target_package" == "$TARGET_PACKAGE" ]] || fail "phase $phase target package mismatch"
  [[ "$test_package" == "$TEST_PACKAGE" ]] || fail "phase $phase test package mismatch"
  [[ "$generation_origin" == "$EXPECTED_GENERATION_ORIGIN" ]] \
    || fail "phase $phase did not exercise the production generation/persistence boundary"
  if [[ "$phase" == "1" ]]; then
    [[ "$persistence_origin" == "$EXPECTED_PHASE1_PERSISTENCE_ORIGIN" ]] \
      || fail "phase 1 did not publish the expected $EXPECTED_PHASE1_PERSISTENCE_ORIGIN artifact"
  else
    [[ "$persistence_origin" == "$EXPECTED_PHASE2_PERSISTENCE_ORIGIN" ]] \
      || fail "phase 2 did not publish the expected $EXPECTED_PHASE2_PERSISTENCE_ORIGIN artifact"
  fi
  [[ "$producer_fixture_name" == "$EXPECTED_PRODUCER_FIXTURE_NAME" ]] \
    || fail "phase $phase producer fixture name is not $EXPECTED_PRODUCER_FIXTURE_NAME"
  [[ "$producer_fixture_host" == "$EXPECTED_PRODUCER_FIXTURE_HOST" ]] \
    || fail "phase $phase producer fixture host is not $EXPECTED_PRODUCER_FIXTURE_HOST"
  [[ "$producer_fixture_port" == "$EXPECTED_PRODUCER_FIXTURE_PORT" ]] \
    || fail "phase $phase producer fixture port is not $EXPECTED_PRODUCER_FIXTURE_PORT"
  [[ "$producer_fixture_user" == "$EXPECTED_PRODUCER_FIXTURE_USER" ]] \
    || fail "phase $phase producer fixture user is not $EXPECTED_PRODUCER_FIXTURE_USER"
  [[ -n "$producer_key_path" ]] || fail "phase $phase producer key path is empty"
  [[ "$producer_session_name" == "${EXPECTED_PRODUCER_SESSION_PREFIX}${RUN_NAMESPACE}" ]] \
    || fail "phase $phase producer session name does not bind this run namespace"
  [[ -n "$session_id" ]] || fail "phase $phase tmux_session_id is empty"
  [[ "$session_created" =~ ^[0-9]+$ && "$session_created" -gt 0 ]] \
    || fail "phase $phase session_created is absent/invalid"
  [[ -n "$predecessor_id" ]] || fail "phase $phase predecessor_tmux_session_id is empty"
  [[ "$predecessor_created" =~ ^[0-9]+$ && "$predecessor_created" -gt 0 ]] \
    || fail "phase $phase predecessor_session_created is absent/invalid"
  [[ "$session_id" != "$predecessor_id" || "$session_created" != "$predecessor_created" ]] \
    || fail "phase $phase successor aliases its predecessor generation"
  [[ "$predecessor_reappeared" == "false" ]] || fail "phase $phase restored predecessor state"
}

validate_file_viewer_workspace_artifact() {
  local phase="$1" artifact="$2"
  [[ -s "$artifact" ]] || fail "phase $phase artifact is absent or empty"
  [[ "$(artifact_value "$artifact" schema)" == "1" ]] \
    || fail "phase $phase workspace artifact schema mismatch"
  [[ "$(artifact_value "$artifact" run_namespace)" == "$RUN_NAMESPACE" ]] \
    || fail "phase $phase workspace artifact namespace mismatch"
  [[ "$(artifact_value "$artifact" phase)" == "$phase" ]] \
    || fail "phase $phase workspace artifact marker mismatch"

  local pid process_name target_package test_package fixture_name fixture_host
  local fixture_port fixture_user key_path session_name tab_count tabs active origin
  local registry_schema route restored boundary generation_origin
  pid="$(artifact_value "$artifact" pid)"
  process_name="$(artifact_value "$artifact" process_name)"
  target_package="$(artifact_value "$artifact" target_package)"
  test_package="$(artifact_value "$artifact" test_package)"
  fixture_name="$(artifact_value "$artifact" producer_fixture_name)"
  fixture_host="$(artifact_value "$artifact" producer_fixture_host)"
  fixture_port="$(artifact_value "$artifact" producer_fixture_port)"
  fixture_user="$(artifact_value "$artifact" producer_fixture_user)"
  key_path="$(artifact_value "$artifact" producer_key_path)"
  session_name="$(artifact_value "$artifact" producer_session_name)"
  tab_count="$(artifact_value "$artifact" workspace_tab_count)"
  tabs="$(artifact_value "$artifact" workspace_tabs)"
  active="$(artifact_value "$artifact" workspace_active)"
  registry_schema="$(artifact_value "$artifact" workspace_registry_schema)"
  route="$(artifact_value "$artifact" navigation_route)"
  origin="$(artifact_value "$artifact" persistence_origin)"
  restored="$(artifact_value "$artifact" restored_workspace)"
  boundary="$(artifact_value "$artifact" external_pid_boundary)"
  generation_origin="$(artifact_value "$artifact" generation_origin)"

  [[ "$pid" =~ ^[0-9]+$ && "$pid" -gt 1 ]] || fail "phase $phase workspace PID is not real: $pid"
  [[ "$process_name" == "$TARGET_PACKAGE" ]] \
    || fail "phase $phase workspace process is $process_name, not $TARGET_PACKAGE"
  [[ "$target_package" == "$TARGET_PACKAGE" ]] || fail "phase $phase workspace target mismatch"
  [[ "$test_package" == "$TEST_PACKAGE" ]] || fail "phase $phase workspace test package mismatch"
  [[ "$fixture_name" == "$EXPECTED_PRODUCER_FIXTURE_NAME" ]] \
    || fail "phase $phase workspace fixture name mismatch: $fixture_name"
  [[ "$fixture_host" == "$EXPECTED_PRODUCER_FIXTURE_HOST" ]] \
    || fail "phase $phase workspace fixture host mismatch: $fixture_host"
  [[ "$fixture_port" == "$EXPECTED_PRODUCER_FIXTURE_PORT" ]] \
    || fail "phase $phase workspace fixture port mismatch: $fixture_port"
  [[ "$fixture_user" == "$EXPECTED_PRODUCER_FIXTURE_USER" ]] \
    || fail "phase $phase workspace fixture user mismatch: $fixture_user"
  [[ -n "$key_path" ]] || fail "phase $phase workspace key provenance is empty"
  [[ "$session_name" == "${EXPECTED_PRODUCER_SESSION_PREFIX}${RUN_NAMESPACE}" ]] \
    || fail "phase $phase workspace session does not bind this run namespace"
  [[ "$tab_count" == "3" ]] || fail "phase $phase must publish exactly three file tabs"
  [[ "$tabs" == */* ]] || fail "phase $phase workspace tab list is empty"
  case "|$tabs|" in
    *"|$active|"*) ;;
    *) fail "phase $phase workspace active path is not one of its tabs" ;;
  esac
  [[ "$registry_schema" == "daemon-tree-registry.json:file_workspaces.default" ]] \
    || fail "phase $phase did not identify the daemon file_workspaces schema"
  [[ "$route" == "MainActivity>FolderList>Session>FileViewer" ]] \
    || fail "phase $phase did not use the production MainActivity route"
  if [[ "$phase" == "1" ]]; then
    [[ "$origin" == "$EXPECTED_PHASE1_PERSISTENCE_ORIGIN" ]] \
      || fail "phase 1 workspace origin mismatch: $origin"
    [[ "$restored" == "false" ]] || fail "phase 1 cannot claim restored workspace"
  else
    [[ "$origin" == "$EXPECTED_PHASE2_PERSISTENCE_ORIGIN" ]] \
      || fail "phase 2 workspace origin mismatch: $origin"
    [[ "$restored" == "true" ]] || fail "phase 2 did not claim restored workspace"
  fi
  [[ "$boundary" == "true" ]] || fail "phase $phase lacks external PID-boundary marker"
  [[ "$generation_origin" == "$EXPECTED_GENERATION_ORIGIN" ]] \
    || fail "phase $phase did not identify the real daemon/session-to-registry path"
}

validate_same_producer_fixture() {
  local first="$1" second="$2" key first_value second_value
  for key in producer_fixture_name producer_fixture_host producer_fixture_port \
    producer_fixture_user producer_key_path producer_session_name; do
    first_value="$(artifact_value "$first" "$key")"
    second_value="$(artifact_value "$second" "$key")"
    [[ "$first_value" == "$second_value" ]] \
      || fail "producer metadata $key changed between phase artifacts"
  done
}

validate_phase_one_ready_marker() {
  local marker="$1" artifact="$2"
  [[ -s "$marker" ]] || fail "phase 1 ready marker is absent or empty"
  [[ "$(artifact_value "$marker" schema)" == "1" ]] || fail "phase 1 ready marker schema mismatch"
  [[ "$(artifact_value "$marker" run_namespace)" == "$RUN_NAMESPACE" ]] \
    || fail "phase 1 ready marker namespace mismatch"
  [[ "$(artifact_value "$marker" phase)" == "1" ]] || fail "phase 1 ready marker phase mismatch"
  [[ "$(artifact_value "$marker" ready)" == "true" ]] || fail "phase 1 ready marker is not ready=true"
  [[ "$(artifact_value "$marker" artifact)" == "phase-1.txt" ]] \
    || fail "phase 1 ready marker names an unexpected artifact"
  [[ "$(artifact_value "$marker" artifact_complete)" == "true" ]] \
    || fail "phase 1 ready marker does not claim a complete artifact"

  local marker_pid marker_process marker_target marker_test marker_sha actual_sha artifact_bytes
  marker_pid="$(artifact_value "$marker" pid)"
  marker_process="$(artifact_value "$marker" process_name)"
  marker_target="$(artifact_value "$marker" target_package)"
  marker_test="$(artifact_value "$marker" test_package)"
  marker_sha="$(artifact_value "$marker" artifact_sha256)"
  artifact_bytes="$(artifact_value "$marker" artifact_bytes)"
  [[ "$marker_pid" =~ ^[0-9]+$ && "$marker_pid" -gt 1 ]] \
    || fail "phase 1 ready marker PID is not real: $marker_pid"
  [[ "$marker_process" == "$TARGET_PACKAGE" ]] \
    || fail "phase 1 ready marker ran in $marker_process, not $TARGET_PACKAGE"
  [[ "$marker_target" == "$TARGET_PACKAGE" ]] || fail "phase 1 ready marker target mismatch"
  [[ "$marker_test" == "$TEST_PACKAGE" ]] || fail "phase 1 ready marker test mismatch"
  [[ "$artifact_bytes" =~ ^[0-9]+$ && "$artifact_bytes" -gt 0 ]] \
    || fail "phase 1 ready marker artifact size is invalid"
  [[ "$artifact_bytes" == "$(wc -c < "$artifact" | tr -d ' ')" ]] \
    || fail "phase 1 ready marker artifact size does not match pulled artifact"
  actual_sha="$(sha256sum "$artifact" | awk '{print $1}')"
  [[ "$marker_sha" == "$actual_sha" ]] \
    || fail "phase 1 ready marker artifact digest does not match pulled artifact"
  [[ "$marker_pid" == "$(artifact_value "$artifact" pid)" ]] \
    || fail "phase 1 ready marker PID does not match phase 1 artifact"
}

validate_instrumentation_success() {
  local phase="$1" method="$2" log_file="$3" rc="$4"
  [[ "$rc" == "0" ]] || fail "phase $phase am instrument exited $rc"
  grep -Fqx "INSTRUMENTATION_CODE: -1" "$log_file" \
    || fail "phase $phase lacks instrumentation success code"
  grep -Fqx "OK (1 test)" "$log_file" || fail "phase $phase did not report exactly one passing test"
  grep -Fqx "INSTRUMENTATION_STATUS: class=$TEST_CLASS" "$log_file" \
    || fail "phase $phase did not execute $TEST_CLASS"
  grep -Fqx "INSTRUMENTATION_STATUS: test=$method" "$log_file" \
    || fail "phase $phase did not execute $method"
  [[ "$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0$' "$log_file" || true)" == "1" ]] \
    || fail "phase $phase lacks one coherent completed-test status"
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[12]|shortMsg=' "$log_file"; then
    fail "phase $phase instrumentation output contains a failure marker"
  fi
}

validate_interrupted_phase_one() {
  local rc="$1" log_file="$2"
  # The target process is deliberately killed while this command is still
  # waiting on the phase-1 test. A non-zero adb status is normal, but the
  # status alone is not proof: require Android's explicit interrupted-process
  # result and reject a complete success report.
  grep -Eq 'shortMsg=(Process crashed|Instrumentation run failed)|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[12]' "$log_file" \
    || fail "phase 1 instrumentation command was reaped without an expected external-interruption result"
  if grep -Fqx "INSTRUMENTATION_CODE: -1" "$log_file" || grep -Fqx "OK (1 test)" "$log_file"; then
    fail "phase 1 instrumentation reported natural success after the force-stop boundary"
  fi
  [[ "$rc" != "0" ]] || printf '%s\n' "phase 1 interruption was reported by Android with adb rc=0" \
    >> "$RUN_DIR/phase-1-instrumentation.log"
}

PHASE1_COMMAND_HOST_PID=""
PHASE1_COMMAND_REAPED=0

phase_one_command_is_running() {
  [[ -n "$PHASE1_COMMAND_HOST_PID" ]] || return 1
  kill -0 "$PHASE1_COMMAND_HOST_PID" 2>/dev/null || return 1
  local state
  state="$(ps -o stat= -p "$PHASE1_COMMAND_HOST_PID" 2>/dev/null | tr -d '[:space:]')"
  [[ -n "$state" && "$state" != Z* ]]
}

cleanup_phase_one_command() {
  if [[ "$PHASE1_COMMAND_REAPED" != "1" && -n "$PHASE1_COMMAND_HOST_PID" ]] && \
    kill -0 "$PHASE1_COMMAND_HOST_PID" 2>/dev/null; then
    kill "$PHASE1_COMMAND_HOST_PID" 2>/dev/null || true
    wait "$PHASE1_COMMAND_HOST_PID" 2>/dev/null || true
  fi
}

cleanup_on_exit() {
  cleanup_phase_one_command
  pocketshell_release_all
}

trap cleanup_on_exit EXIT

phase_one_ready_marker_is_present() {
  local output rc
  set +e
  output="$(adb_cmd shell test -s "$DEVICE_DIR/phase-1.ready" 2>&1)"
  rc=$?
  set -e
  output="${output//$'\r'/}"
  [[ -z "$output" ]] \
    || fail "cannot determine whether phase 1 ready marker exists: unexpected adb output: $output"
  case "$rc" in
    0) return 0 ;;
    1) return 1 ;;
    *) fail "cannot determine whether phase 1 ready marker exists: adb exited $rc" ;;
  esac
}

start_phase_one_instrumentation() {
  local log_file="$RUN_DIR/phase-1-instrumentation.log"
  : > "$log_file"
  record_event "phase_1_instrumentation_start"
  # Keep the host-side am instrument process live while the on-device test
  # publishes its completed artifact and waits. The host must reach the
  # marker/liveness boundary before either package is force-stopped.
  set +e
  adb_mutate shell am instrument -w -r \
    -e pocketshellRunNamespace "$RUN_NAMESPACE" \
    -e pocketshellPhase 1 \
    -e pocketshellPhaseOneKeepaliveMillis "$PHASE1_KEEPALIVE_MILLIS" \
    -e class "$TEST_CLASS#$PHASE1_METHOD" \
    "$RUNNER_COMPONENT" > "$log_file" 2>&1 &
  PHASE1_COMMAND_HOST_PID=$!
  set -e
  printf '%s\n' "$PHASE1_COMMAND_HOST_PID" > "$RUN_DIR/phase-1-host-command.pid"
}

wait_for_phase_one_ready() {
  local deadline=$((SECONDS + PHASE1_READY_WAIT_SECONDS))
  while :; do
    phase_one_command_is_running \
      || fail "phase 1 am instrument exited before publishing its ready marker"
    if phase_one_ready_marker_is_present; then
      # Re-check after the marker probe. A naturally completed instrumentation
      # process is not an acceptable liveness boundary even if its marker was
      # visible for a moment.
      phase_one_command_is_running \
        || fail "phase 1 am instrument exited before the ready boundary was observed"
      record_event "phase_1_ready_marker_observed"
      return 0
    fi
    (( SECONDS < deadline )) || fail \
      "phase 1 ready marker did not appear within ${PHASE1_READY_WAIT_SECONDS}s"
    sleep 0.2
  done
}

pull_and_validate_phase_one_ready() {
  adb_cmd pull "$DEVICE_DIR/phase-1.ready" "$RUN_DIR/phase-1.ready" \
    > "$RUN_DIR/phase-1-ready-marker-pull.log" 2>&1 \
    || fail "phase 1 ready marker pull failed"
  adb_cmd pull "$DEVICE_DIR/phase-1.txt" "$RUN_DIR/phase-1.txt" \
    > "$RUN_DIR/phase-1-artifact-pull.log" 2>&1 \
    || fail "phase 1 artifact pull failed"
  validate_phase_one_ready_marker "$RUN_DIR/phase-1.ready" "$RUN_DIR/phase-1.txt"
  validate_phase_artifact 1 "$RUN_DIR/phase-1.txt"
}

reap_interrupted_phase_one_instrumentation() {
  local log_file="$RUN_DIR/phase-1-instrumentation.log"
  local deadline=$((SECONDS + PHASE1_REAP_WAIT_SECONDS))
  while phase_one_command_is_running; do
    (( SECONDS < deadline )) || fail \
      "phase 1 am instrument did not terminate after the two external force-stops"
    sleep 0.2
  done
  local rc=0
  set +e
  wait "$PHASE1_COMMAND_HOST_PID"
  rc=$?
  set -e
  PHASE1_COMMAND_REAPED=1
  printf '%s\n' "$rc" > "$RUN_DIR/phase-1-instrumentation.rc"
  validate_interrupted_phase_one "$rc" "$log_file"
  record_event "phase_1_interrupted_command_reaped"
}

run_phase() {
  local phase="$1" method="$2"
  local log_file="$RUN_DIR/phase-$phase-instrumentation.log"
  local rc=0
  record_event "phase_${phase}_instrumentation_start"
  set +e
  adb_mutate shell am instrument -w -r \
    -e pocketshellRunNamespace "$RUN_NAMESPACE" \
    -e pocketshellPhase 2 \
    -e class "$TEST_CLASS#$method" \
    "$RUNNER_COMPONENT" 2>&1 | tr -d '\r' | tee "$log_file"
  rc=${PIPESTATUS[0]}
  set -e
  printf '%s\n' "$rc" > "$RUN_DIR/phase-$phase-instrumentation.rc"
  validate_instrumentation_success "$phase" "$method" "$log_file" "$rc"
  adb_cmd pull "$DEVICE_DIR/phase-$phase.txt" "$RUN_DIR/phase-$phase.txt" \
    > "$RUN_DIR/phase-$phase-artifact-pull.log" 2>&1 \
    || fail "phase $phase artifact pull failed"
  validate_phase_artifact "$phase" "$RUN_DIR/phase-$phase.txt"
  if [[ "$phase" == "2" ]]; then
    validate_same_producer_fixture "$RUN_DIR/phase-1.txt" "$RUN_DIR/phase-2.txt"
  fi
  adb_cmd logcat -d -v threadtime > "$RUN_DIR/phase-$phase-logcat.txt" 2>&1 \
    || fail "phase $phase logcat capture failed"
  record_event "phase_${phase}_complete"
}

old_pid_is_gone() {
  local old_pid="$1" output rc
  set +e
  output="$(adb_cmd shell test -e "/proc/$old_pid" 2>&1)"
  rc=$?
  set -e
  output="${output//$'\r'/}"
  [[ -z "$output" ]] \
    || fail "cannot determine whether phase-1 PID $old_pid exists: unexpected adb output: $output"
  case "$rc" in
    0) return 1 ;; # /proc entry exists: the old process is still alive.
    1) return 0 ;; # toybox test's exact absent-path result.
    *) fail "cannot determine whether phase-1 PID $old_pid exists: adb exited $rc" ;;
  esac
}

force_stop_between_phases() {
  local old_pid="$1"
  # A PID that has already disappeared is not evidence of an externally
  # killed process. Without this precondition, a naturally completed/cached
  # instrumentation process plus a later new PID would falsely satisfy the
  # process-restart claim.
  if old_pid_is_gone "$old_pid"; then
    fail "phase-1 PID $old_pid was not alive before external force-stop"
  fi
  local old_pid_cmdline output rc
  set +e
  output="$(adb_cmd shell cat "/proc/$old_pid/cmdline" 2>&1 | tr -d '\r\n\000')"
  rc=${PIPESTATUS[0]}
  set -e
  [[ "$rc" == "0" && -n "$output" ]] \
    || fail "cannot identify phase-1 PID $old_pid through /proc/$old_pid/cmdline"
  old_pid_cmdline="$output"
  [[ "$old_pid_cmdline" == "$TARGET_PACKAGE" ]] \
    || fail "phase-1 PID $old_pid belongs to $old_pid_cmdline, not $TARGET_PACKAGE"
  local existed_before="true"
  record_event "external_force_stop_start"
  adb_mutate shell am force-stop "$TARGET_PACKAGE" \
    > "$RUN_DIR/force-stop-target.log" 2>&1 \
    || fail "target package force-stop failed"
  adb_mutate shell am force-stop "$TEST_PACKAGE" \
    > "$RUN_DIR/force-stop-test.log" 2>&1 \
    || fail "test package force-stop failed"

  local deadline=$((SECONDS + FORCE_STOP_WAIT_SECONDS))
  until old_pid_is_gone "$old_pid"; do
    (( SECONDS < deadline )) || fail "phase-1 PID $old_pid survived external force-stop"
    sleep 0.2
  done
  {
    printf 'old_pid=%s\n' "$old_pid"
    printf 'old_pid_present_before_force_stop=%s\n' "$existed_before"
    printf 'old_pid_liveness_probe=real_proc_entry\n'
    printf 'old_pid_identity_probe=real_proc_cmdline\n'
    printf 'old_pid_cmdline_before_force_stop=%s\n' "$old_pid_cmdline"
    printf 'target_force_stop=completed\n'
    printf 'test_force_stop=completed\n'
    printf 'old_pid_gone_after_force_stop=true\n'
  } > "$RUN_DIR/force-stop-evidence.txt"
  record_event "external_force_stop_complete"
}

write_fixture_identity
: > "$RUN_DIR/package-mutations.log"
: > "$RUN_DIR/adb-mutations.log"

if [[ "$BUILD_APKS" == "1" ]]; then
  record_event "build_start"
  pocketshell_run_without_avd_lock_fd \
    "$ROOT_DIR/scripts/cgroup-run.sh" --unit "pocketshell-two-phase-${RUN_NAMESPACE//[^A-Za-z0-9._-]/_}" -- \
    ./gradlew --no-daemon "-PpocketshellAppIdSuffix=$SUFFIX" \
    :app:assembleDebug :app:assembleDebugAndroidTest \
    > "$RUN_DIR/build.log" 2>&1 \
    || fail "suffixed app/test APK build failed"
  record_event "build_complete"
fi
[[ -s "$APP_APK" ]] || fail "target APK not found: $APP_APK"
[[ -s "$TEST_APK" ]] || fail "test APK not found: $TEST_APK"

# Initial isolation cleanup happens before the one install. From phase 1 start
# onward there is deliberately no uninstall, pm clear, install, or Gradle/UTP
# connected-test cleanup.
record_event "initial_cleanup_start"
adb_mutate uninstall "$TEST_PACKAGE" >> "$RUN_DIR/initial-uninstall.log" 2>&1 || true
adb_mutate uninstall "$TARGET_PACKAGE" >> "$RUN_DIR/initial-uninstall.log" 2>&1 || true
adb_mutate shell rm -rf "$DEVICE_DIR" > "$RUN_DIR/initial-artifact-cleanup.log" 2>&1 \
  || fail "cannot clear this run's device artifact namespace"
record_event "initial_cleanup_complete"

adb_mutate install -r -t "$APP_APK" > "$RUN_DIR/install-target.log" 2>&1 \
  || fail "target APK install failed"
record_event "install_target_once"
adb_mutate install -r -t "$TEST_APK" > "$RUN_DIR/install-test.log" 2>&1 \
  || fail "test APK install failed"
record_event "install_test_once"
adb_cmd shell pm path "$TARGET_PACKAGE" > "$RUN_DIR/installed-target-path.txt" \
  || fail "target package absent after install"
adb_cmd shell pm path "$TEST_PACKAGE" > "$RUN_DIR/installed-test-path.txt" \
  || fail "test package absent after install"
grep -q '^package:' "$RUN_DIR/installed-target-path.txt" || fail "target package path missing"
grep -q '^package:' "$RUN_DIR/installed-test-path.txt" || fail "test package path missing"
adb_mutate logcat -c || fail "cannot clear logcat at run boundary"

start_phase_one_instrumentation
wait_for_phase_one_ready
pull_and_validate_phase_one_ready
PHASE1_PID="$(artifact_value "$RUN_DIR/phase-1.txt" pid)"

# Load-bearing external boundary: phase 1 has completed every production
# assertion and published its artifact, but its target/instrumentation process
# is still live. Only now may the host issue the two package force-stops.
force_stop_between_phases "$PHASE1_PID"
reap_interrupted_phase_one_instrumentation
adb_cmd logcat -d -v threadtime > "$RUN_DIR/phase-1-logcat.txt" 2>&1 \
  || fail "phase 1 logcat capture failed"
record_event "phase_1_complete"

run_phase 2 "$PHASE2_METHOD"
PHASE2_PID="$(artifact_value "$RUN_DIR/phase-2.txt" pid)"

[[ "$PHASE1_PID" != "$PHASE2_PID" ]] \
  || fail "phase 2 reused phase-1 PID $PHASE1_PID; no process restart occurred"
if [[ "$PROOF_KIND" == "file-viewer-workspace" ]]; then
  PHASE1_TABS="$(artifact_value "$RUN_DIR/phase-1.txt" workspace_tabs)"
  PHASE2_TABS="$(artifact_value "$RUN_DIR/phase-2.txt" workspace_tabs)"
  PHASE1_ACTIVE="$(artifact_value "$RUN_DIR/phase-1.txt" workspace_active)"
  PHASE2_ACTIVE="$(artifact_value "$RUN_DIR/phase-2.txt" workspace_active)"
  [[ "$PHASE1_TABS" == "$PHASE2_TABS" ]] \
    || fail "daemon workspace tabs changed across external process restart"
  [[ "$PHASE1_ACTIVE" == "$PHASE2_ACTIVE" ]] \
    || fail "daemon active tab changed across external process restart"
  [[ "$(artifact_value "$RUN_DIR/phase-2.txt" restored_workspace)" == "true" ]] \
    || fail "phase 2 did not restore the daemon workspace"
else
  PHASE1_ID="$(artifact_value "$RUN_DIR/phase-1.txt" tmux_session_id)"
  PHASE2_ID="$(artifact_value "$RUN_DIR/phase-2.txt" tmux_session_id)"
  PHASE1_CREATED="$(artifact_value "$RUN_DIR/phase-1.txt" session_created)"
  PHASE2_CREATED="$(artifact_value "$RUN_DIR/phase-2.txt" session_created)"
  PHASE1_PREDECESSOR_ID="$(artifact_value "$RUN_DIR/phase-1.txt" predecessor_tmux_session_id)"
  PHASE2_PREDECESSOR_ID="$(artifact_value "$RUN_DIR/phase-2.txt" predecessor_tmux_session_id)"
  PHASE1_PREDECESSOR_CREATED="$(artifact_value "$RUN_DIR/phase-1.txt" predecessor_session_created)"
  PHASE2_PREDECESSOR_CREATED="$(artifact_value "$RUN_DIR/phase-2.txt" predecessor_session_created)"
  [[ "$PHASE1_ID" == "$PHASE2_ID" ]] || fail "tmuxSessionId changed across process restart"
  [[ "$PHASE1_CREATED" == "$PHASE2_CREATED" ]] || fail "sessionCreated changed across process restart"
  [[ "$PHASE1_PREDECESSOR_ID" == "$PHASE2_PREDECESSOR_ID" ]] \
    || fail "predecessor tmuxSessionId marker changed across process restart"
  [[ "$PHASE1_PREDECESSOR_CREATED" == "$PHASE2_PREDECESSOR_CREATED" ]] \
    || fail "predecessor sessionCreated marker changed across process restart"
fi
[[ -s "$RUN_DIR/force-stop-evidence.txt" ]] || fail "external force-stop evidence is absent"
[[ "$(artifact_value "$RUN_DIR/force-stop-evidence.txt" old_pid)" == "$PHASE1_PID" ]] \
  || fail "force-stop evidence names the wrong old PID"
[[ "$(artifact_value "$RUN_DIR/force-stop-evidence.txt" old_pid_present_before_force_stop)" == "true" ]] \
  || fail "force-stop evidence does not prove the old PID was alive before the stop"
[[ "$(artifact_value "$RUN_DIR/force-stop-evidence.txt" old_pid_gone_after_force_stop)" == "true" ]] \
  || fail "force-stop evidence does not prove the old PID is gone"
[[ "$(artifact_value "$RUN_DIR/force-stop-evidence.txt" target_force_stop)" == "completed" ]] \
  || fail "target force-stop evidence is incomplete"
[[ "$(artifact_value "$RUN_DIR/force-stop-evidence.txt" test_force_stop)" == "completed" ]] \
  || fail "test force-stop evidence is incomplete"
INSTALL_COMMAND_COUNT="$(grep -Ec ' command=install ' "$RUN_DIR/adb-mutations.log" || true)"
[[ "$INSTALL_COMMAND_COUNT" == "2" ]] \
  || fail "expected one target + one test APK install, observed $INSTALL_COMMAND_COUNT install commands"
if awk '
    / marker=phase_1_instrumentation_start$/ { between_phases = 1 }
    between_phases { print }
  ' "$RUN_DIR/adb-mutations.log" \
  | grep -Eq ' command=(install|uninstall)([[:space:]]|$)|command=.*[[:space:]]pm([[:space:]]|\\ )+(clear|install|uninstall)([[:space:]]|\\ |$)|command=.*[[:space:]]cmd([[:space:]]|\\ )+package([[:space:]]|\\ )+(clear|install|uninstall)([[:space:]]|\\ |$)'; then
  fail "package install/uninstall/pm-clear mutation occurred after phase 1 began"
fi

FORCE_STOP_COMMAND_COUNT="$(awk '
    / marker=phase_1_instrumentation_start$/ { between_phases = 1 }
    between_phases { print }
  ' "$RUN_DIR/adb-mutations.log" \
  | grep -Ec ' command=shell am force-stop com\.pocketshell\.app\.[A-Za-z0-9._-]+([[:space:]]|$)' || true)"
[[ "$FORCE_STOP_COMMAND_COUNT" == "2" ]] \
  || fail "expected exactly two post-phase-1 am force-stop commands, observed $FORCE_STOP_COMMAND_COUNT"
grep -Eq ' command=shell am force-stop com\.pocketshell\.app\.[A-Za-z0-9._-]+([[:space:]]|$)' \
  "$RUN_DIR/adb-mutations.log" \
  || fail "external force-stop commands are absent from adb mutation evidence"

{
  printf 'result=PASS\n'
  printf 'proof_kind=%s\n' "$PROOF_KIND"
  printf 'run_namespace=%s\n' "$RUN_NAMESPACE"
  printf 'android_serial=%s\n' "$ANDROID_SERIAL"
  printf 'target_package=%s\n' "$TARGET_PACKAGE"
  printf 'test_package=%s\n' "$TEST_PACKAGE"
  printf 'install_target_count=1\n'
  printf 'install_test_count=1\n'
  printf 'phase1_pid=%s\n' "$PHASE1_PID"
  printf 'phase2_pid=%s\n' "$PHASE2_PID"
  printf 'pid_changed=true\n'
  printf 'producer_fixture_name=%s\n' "$(artifact_value "$RUN_DIR/phase-2.txt" producer_fixture_name)"
  printf 'producer_fixture_host=%s\n' "$(artifact_value "$RUN_DIR/phase-2.txt" producer_fixture_host)"
  printf 'producer_fixture_port=%s\n' "$(artifact_value "$RUN_DIR/phase-2.txt" producer_fixture_port)"
  printf 'producer_fixture_user=%s\n' "$(artifact_value "$RUN_DIR/phase-2.txt" producer_fixture_user)"
  printf 'producer_session_name=%s\n' "$(artifact_value "$RUN_DIR/phase-2.txt" producer_session_name)"
  if [[ "$PROOF_KIND" == "file-viewer-workspace" ]]; then
    printf 'workspace_tabs=%s\n' "$PHASE2_TABS"
    printf 'workspace_active=%s\n' "$PHASE2_ACTIVE"
    printf 'daemon_workspace_survived=true\n'
  else
    printf 'tmux_session_id=%s\n' "$PHASE2_ID"
    printf 'session_created=%s\n' "$PHASE2_CREATED"
    printf 'exact_generation_survived=true\n'
  fi
  printf 'state_reset_between_phases=false\n'
} > "$RUN_DIR/summary.txt"
find "$RUN_DIR" -maxdepth 1 -type f ! -name SHA256SUMS -print0 \
  | sort -z | xargs -0 sha256sum > "$RUN_DIR/SHA256SUMS"

printf 'Two-phase Android process-restart proof passed.\n'
printf 'Evidence: %s\n' "$RUN_DIR"
