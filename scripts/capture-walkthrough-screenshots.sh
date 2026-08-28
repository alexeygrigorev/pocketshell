#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

source "$ROOT_DIR/scripts/lib/avd-lock.sh"
source "$ROOT_DIR/scripts/lib/scope-run.sh"
source "$ROOT_DIR/scripts/lib/gradle-profile.sh"
source "$ROOT_DIR/scripts/lib/apk-identity.sh"

# Issue #2064: see scripts/phone-walkthrough.sh — same contract, same
# device-free verification mode, same reason it must not queue on the AVD lock.
POCKETSHELL_VERIFY_APK_IDENTITY_ONLY=0
if [[ "${1:-}" == "--verify-apk-identity" ]]; then
  POCKETSHELL_VERIFY_APK_IDENTITY_ONLY=1
  export POCKETSHELL_AVD_LOCK_ACQUIRED=1
fi

# Issue #2054: the visual-audit stage of the release gate builds the same APKs
# that OOMed the terminal-lab walkthrough. Same shared resource profile, same
# fail-fast assertion, before the shared AVD lock is taken. The profile is
# machine-appropriate: hosted keeps its pinned 1536m/8G pair, local gets
# 3072m/24G — never one half of one and one half of the other.
pocketshell_assert_gradle_execution_profile \
  "visual-audit APK build" \
  "${POCKETSHELL_GRADLE_RESOURCE_ARGS[*]}"
# Build-scope ceiling is asserted at the point of use, before step 10.

pocketshell_acquire_avd_lock "$ROOT_DIR" "${1:-}"

ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
EMULATOR="${EMULATOR:-$ANDROID_SDK/emulator/emulator}"
AVD_NAME="${AVD_NAME:-test}"
COMPOSE_FILE="${COMPOSE_FILE:-tests/docker/docker-compose.yml}"
LOG_ROOT="${LOG_ROOT:-$ROOT_DIR/build/walkthrough-visual-pass}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RUN_DIR="$LOG_ROOT/$RUN_ID"
DEVICE_OUTPUT_DIR="/sdcard/Android/media/com.pocketshell.app/additional_test_output"
DEVICE_SCREENSHOT_DIR="$DEVICE_OUTPUT_DIR/walkthrough-visual-pass"
MAIN_TEST_CLASS="com.pocketshell.app.proof.WalkthroughVisualScreenshotTest"
CONVERSATION_TEST_CLASS="com.pocketshell.app.proof.WalkthroughConversationScreenshotTest"
COMPOSER_TEST_CLASS="com.pocketshell.app.composer.PromptComposerVisualScreenshotTest"
MAIN_SCREENSHOTS=(
  "01-host-list.png"
  "02-host-setup-folder-list.png"
  "03-terminal-session-input-controls.png"
  "04-snippets.png"
  "05-settings.png"
)
CONVERSATION_SCREENSHOTS=(
  "06-conversation-view.png"
)
COMPOSER_SCREENSHOTS=(
  "05b-composer-idle-draft.png"
  "06-composer-recording.png"
  "07-composer-transcribing.png"
)
INSTRUMENTATION_ATTEMPTS="${INSTRUMENTATION_ATTEMPTS:-3}"
SSH_KEY="${SSH_KEY:-$ROOT_DIR/tests/docker/test_key}"
SSH_HOST="${SSH_HOST:-127.0.0.1}"
SSH_PORT="${SSH_PORT:-2222}"
SSH_USER="${SSH_USER:-testuser}"
# Issue #2064: the release chain hands this stage the pair the pre-release
# confidence gate built, validated and publishes, so the visual-audit
# screenshots are of the SHIPPED binary rather than of a byte-different rebuild.
APP_APK="${APP_APK:-$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk}"
TEST_APK="${TEST_APK:-$ROOT_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"
# 1 keeps the historical standalone behaviour (build the pair here). The release
# chain exports 0 together with the expected digests.
VISUAL_AUDIT_BUILD_APKS="${VISUAL_AUDIT_BUILD_APKS:-1}"

if [[ "$POCKETSHELL_VERIFY_APK_IDENTITY_ONLY" == "1" ]]; then
  pocketshell_require_walkthrough_apk_identity "visual audit" || exit 1
  printf 'PASS: visual-audit APK identity verified (issue #2064)\n'
  printf '  app  %s\n' "$APP_APK"
  printf '  test %s\n' "$TEST_APK"
  exit 0
fi
pocketshell_verify_walkthrough_apks "visual audit" || exit 1

usage() {
  cat <<'USAGE'
Usage: scripts/capture-walkthrough-screenshots.sh

Captures emulator screenshots for the main PocketShell walkthrough visual pass.
Defaults use explicit Android SDK paths:

  adb      /home/alexey/Android/Sdk/platform-tools/adb
  emulator /home/alexey/Android/Sdk/emulator/emulator

Environment overrides:
  ANDROID_SDK=/home/alexey/Android/Sdk
  ADB=$ANDROID_SDK/platform-tools/adb
  EMULATOR=$ANDROID_SDK/emulator/emulator
  AVD_NAME=test
  COMPOSE_FILE=tests/docker/docker-compose.yml
  LOG_ROOT=build/walkthrough-visual-pass
  RUN_ID=<custom artifact directory name>
  SSH_KEY=tests/docker/test_key
  SSH_HOST=127.0.0.1
  SSH_PORT=2222
  SSH_USER=testuser
  INSTRUMENTATION_ATTEMPTS=3
USAGE
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  printf 'Artifacts: %s\n' "$RUN_DIR" >&2
  exit 1
}

require_executable() {
  local path="$1"
  local label="$2"
  [[ -x "$path" ]] || fail "$label is not executable at $path"
}

run_logged() {
  local name="$1"
  shift
  local log_file="$RUN_DIR/$name.log"
  local start_seconds end_seconds elapsed_seconds status
  start_seconds="$(date +%s)"
  printf '\n[%s]\n' "$name"
  printf 'Log: %s\n' "$log_file"
  set +e
  {
    printf '[%s] %s\n' "$(date -Is)" "$name"
    printf 'Command:'
    printf ' %q' "$@"
    printf '\n\n'
    "$@"
  } > "$log_file" 2>&1
  status="$?"
  set -e
  end_seconds="$(date +%s)"
  elapsed_seconds=$((end_seconds - start_seconds))
  if [[ "$status" -eq 0 ]]; then
    printf 'PASS: %s (%ss)\n' "$name" "$elapsed_seconds"
  else
    printf 'FAIL: %s exited %s after %ss\n' "$name" "$status" "$elapsed_seconds" >&2
    if [[ -s "$log_file" ]]; then
      printf '\nLast 80 lines from %s:\n' "$log_file" >&2
      tail -n 80 "$log_file" >&2 || true
    else
      printf '\nNo output was captured in %s\n' "$log_file" >&2
    fi
  fi
  return "$status"
}

mkdir -p "$RUN_DIR"

collect_diagnostics() {
  local exit_code=$?
  {
    printf 'exit_code=%s\n' "$exit_code"
    printf 'run_dir=%s\n' "$RUN_DIR"
    printf 'timestamp=%s\n' "$(date -Is)"
  } > "$RUN_DIR/exit-status.txt"

  docker compose -f "$COMPOSE_FILE" ps > "$RUN_DIR/docker-compose-ps.txt" 2>&1 || true
  docker compose -f "$COMPOSE_FILE" logs --no-color --timestamps agents > "$RUN_DIR/docker-agents.log" 2>&1 || true
  docker inspect pocketshell-test-agents > "$RUN_DIR/docker-agents-inspect.json" 2>&1 || true
  "$ADB" devices -l > "$RUN_DIR/adb-devices-final.txt" 2>&1 || true
  "$ADB" shell getprop > "$RUN_DIR/adb-getprop.txt" 2>&1 || true
  "$ADB" logcat -d -v threadtime > "$RUN_DIR/adb-logcat.txt" 2>&1 || true

  if [[ "$exit_code" -ne 0 ]]; then
    printf '\nDiagnostics collected in %s\n' "$RUN_DIR" >&2
  fi
}
trap 'collect_diagnostics; pocketshell_release_all' EXIT

# Issue #150: wait on the compose `healthcheck:` block via
# `docker inspect`, not a host-side SSH retry loop. Keep one follow-up
# SSH probe so the readiness log still records the same evidence.
source "$ROOT_DIR/tests/docker/lib/wait-for-healthy.sh"

wait_for_host_ssh_fixture() {
  local log_file="$RUN_DIR/04-docker-ssh-readiness.log"
  if ! wait_for_container_healthy "$COMPOSE_FILE" agents "$log_file" 60; then
    printf '\n[04-docker-ssh-readiness]\nLog: %s\n' "$log_file"
    tail -n 80 "$log_file" || true
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
      "printf 'ssh fixture ready '; tmux -V"
  } >> "$log_file" 2>&1 || {
    printf '\n[04-docker-ssh-readiness]\nLog: %s\n' "$log_file"
    tail -n 80 "$log_file" || true
    fail "Docker SSH fixture reported healthy but follow-up SSH probe failed at $SSH_USER@$SSH_HOST:$SSH_PORT"
  }
  printf '\n[04-docker-ssh-readiness]\nLog: %s\nPASS: docker SSH fixture healthy\n' "$log_file"
}

install_apks() {
  local step_name="$1"
  run_logged "$step_name" bash -lc \
    "'$ADB' install -r '$APP_APK' && '$ADB' install -r '$TEST_APK' && sleep 5"
  wait_for_instrumentation "$step_name"
}

wait_for_instrumentation() {
  local step_name="$1"
  local log_file="$RUN_DIR/$step_name-instrumentation-ready.log"
  local attempt
  : > "$log_file"
  for attempt in $(seq 1 30); do
    {
      printf '[%s] attempt %s\n' "$(date -Is)" "$attempt"
      "$ADB" shell pm list packages com.pocketshell.app
      "$ADB" shell pm list packages com.pocketshell.app.test
      "$ADB" shell pm list instrumentation
    } >> "$log_file" 2>&1
    if grep -q '^package:com.pocketshell.app$' "$log_file" &&
      grep -q '^package:com.pocketshell.app.test$' "$log_file" &&
      grep -q '^instrumentation:com.pocketshell.app.test/androidx.test.runner.AndroidJUnitRunner' "$log_file"; then
      printf '\n[%s-instrumentation-ready]\nLog: %s\nPASS: Android test instrumentation registered\n' "$step_name" "$log_file"
      return 0
    fi
    sleep 1
  done
  printf '\n[%s-instrumentation-ready]\nLog: %s\n' "$step_name" "$log_file"
  tail -n 120 "$log_file" || true
  fail "Android test instrumentation was not registered after APK install"
}

visual_audit_instrumentation_log_has_success() {
  local log_file="$1"
  grep -q "INSTRUMENTATION_CODE: -1" "$log_file" &&
    grep -q "OK (" "$log_file" &&
    ! grep -q "FAILURES!!!" "$log_file"
}

visual_audit_instrumentation_log_has_failure_markers() {
  local log_file="$1"
  grep -Eq '(^FAILURES!!!$|^FAILURE: |^INSTRUMENTATION_STATUS_CODE: -[0-9]+$|^INSTRUMENTATION_STATUS: stack=|^INSTRUMENTATION_RESULT: shortMsg=Process crashed[.]|^[[:space:]]*at (com[.]pocketshell|androidx[.]test|org[.]junit|kotlin[.]|java[.]|android[.])|java[.]lang[.]AssertionError|junit[.]framework[.]AssertionFailedError|org[.]junit[.]ComparisonFailure|kotlin[.]AssertionError|androidx[.]test[.]espresso[.](AmbiguousViewMatcherException|NoMatchingRootException|NoMatchingViewException|PerformException)|^Process crashed[.])' "$log_file"
}

visual_audit_logcat_has_app_or_test_failure_markers() {
  local logcat_file="$1"
  grep -Eq 'Process: com[.]pocketshell[.]app|FATAL EXCEPTION.*com[.]pocketshell[.]app|FATAL SIGNAL.*com[.]pocketshell[.]app|AndroidRuntime.*com[.]pocketshell[.]app|(^|[[:space:]])FAILURES!!!($|[[:space:]])|INSTRUMENTATION_STATUS: stack=|INSTRUMENTATION_RESULT: shortMsg=Process crashed' "$logcat_file"
}

visual_audit_instrumentation_log_has_transport_drop_markers() {
  local log_file="$1"
  grep -Eiq 'adb:.*(closed|device|disconnected|no devices|offline|protocol fault)|error: (closed|device .* not found|device offline|failed to get feature set|more than one device/emulator|no devices/emulators found|protocol fault)|device offline|device .* not found|transport .* (closed|disconnected|error|not found|offline)|Connection reset by peer|Broken pipe|lost connection to device|UiAutomation.*(connection.*(died|lost)|died|disconnected|not connected)|java[.]lang[.]IllegalStateException: UiAutomation not connected|android[.]os[.]DeadObjectException' "$log_file"
}

visual_audit_logcat_has_transport_drop_markers() {
  local logcat_file="$1"
  grep -Eq 'adbd[[:space:]].*(connection terminated|offline|read failed)|host-[0-9]+: read failed|UiAutomation service owner died' "$logcat_file"
}

visual_audit_should_retry_interrupted_instrumentation() {
  local status="$1"
  local instrumentation_log="$2"
  local logcat_file="$3"
  [[ "$status" -ne 0 ]] || return 1
  visual_audit_instrumentation_log_has_success "$instrumentation_log" && return 1
  visual_audit_instrumentation_log_has_failure_markers "$instrumentation_log" && return 1
  visual_audit_logcat_has_app_or_test_failure_markers "$logcat_file" && return 1
  visual_audit_instrumentation_log_has_transport_drop_markers "$instrumentation_log" ||
    visual_audit_logcat_has_transport_drop_markers "$logcat_file"
}

run_instrumentation_class() {
  local step_name="$1"
  local test_class="$2"
  local attempt instrumentation_status attempt_logcat
  for attempt in $(seq 1 "$INSTRUMENTATION_ATTEMPTS"); do
    local attempt_step="$step_name-attempt-$attempt"
    run_logged "$attempt_step-pre-force-stop" bash -lc \
      "'$ADB' shell am force-stop com.pocketshell.app >/dev/null 2>&1 || true; '$ADB' shell am force-stop com.pocketshell.app.test >/dev/null 2>&1 || true; sleep 5"
    "$ADB" logcat -c >/dev/null 2>&1 || true
    instrumentation_status=0
    run_logged "$attempt_step" \
      "$ADB" shell am instrument -w -r \
      -e additionalTestOutputDir "$DEVICE_OUTPUT_DIR" \
      -e class "$test_class" \
      com.pocketshell.app.test/androidx.test.runner.AndroidJUnitRunner ||
      instrumentation_status=$?
    cp "$RUN_DIR/$attempt_step.log" "$RUN_DIR/$step_name.log"
    attempt_logcat="$RUN_DIR/$attempt_step-logcat.txt"
    "$ADB" logcat -d -v threadtime -t 4000 > "$attempt_logcat" 2>&1 || true
    if visual_audit_instrumentation_log_has_success "$RUN_DIR/$attempt_step.log"; then
      return 0
    fi
    if [[ "$attempt" -eq "$INSTRUMENTATION_ATTEMPTS" ]]; then
      fail "$test_class did not report instrumentation success"
    fi
    if ! visual_audit_should_retry_interrupted_instrumentation \
      "$instrumentation_status" "$RUN_DIR/$attempt_step.log" "$attempt_logcat"; then
      fail "$test_class did not report instrumentation success"
    fi
    printf 'Retrying %s after adb transport/UIAutomation interruption; see %s and %s\n' \
      "$test_class" "$RUN_DIR/$attempt_step.log" "$attempt_logcat" >&2
    "$ADB" reconnect >/dev/null 2>&1 || true
    "$ADB" wait-for-device >/dev/null 2>&1 || true
    "$ADB" shell cmd package wait-for-handler >/dev/null 2>&1 || true
    sleep 8
  done
}

pull_device_screenshots() {
  local step_name="$1"
  mkdir -p "$RUN_DIR/screenshots"
  run_logged "$step_name" "$ADB" pull "$DEVICE_SCREENSHOT_DIR" "$RUN_DIR/screenshots/"
}

assert_screenshots_exist() {
  local step_name="$1"
  shift
  local screenshot_dir="$RUN_DIR/screenshots/walkthrough-visual-pass"
  local missing=()
  local file_name
  for file_name in "$@"; do
    [[ -s "$screenshot_dir/$file_name" ]] || missing+=("$file_name")
  done
  if [[ "${#missing[@]}" -ne 0 ]]; then
    printf '%s\n' "Missing screenshots after $step_name:" "${missing[@]}" >&2
    fail "Expected walkthrough screenshots were not pulled"
  fi
}

printf 'PocketShell walkthrough visual screenshot pass\n'
printf 'Artifacts: %s\n' "$RUN_DIR"
printf 'ADB: %s\n' "$ADB"
printf 'Emulator: %s\n' "$EMULATOR"
printf 'AVD: %s\n' "$AVD_NAME"

require_executable "$ADB" "adb"
require_executable "$EMULATOR" "emulator"
command -v ssh >/dev/null 2>&1 || fail "ssh client was not found on PATH"
[[ -f "$SSH_KEY" ]] || fail "SSH key was not found at $SSH_KEY"

run_logged "01-adb-version" "$ADB" version
run_logged "02-available-avds" "$EMULATOR" -list-avds
if ! "$EMULATOR" -list-avds | grep -Fxq "$AVD_NAME"; then
  fail "AVD '$AVD_NAME' was not listed by $EMULATOR -list-avds"
fi

# Issue #2381: the fixture's `pocketshell --version` must equal the versionName
# of the APK this run installs, or every screen it captures sits under the
# bootstrap "Host setup needed" sheet.
# shellcheck source=scripts/lib/agents-fixture-version.sh
source "$ROOT_DIR/scripts/lib/agents-fixture-version.sh"
export_agents_fixture_version_for_run "$VISUAL_AUDIT_BUILD_APKS" "$APP_APK"
run_logged "03-docker-agents-recreate" docker compose -f "$COMPOSE_FILE" up -d --build --force-recreate agents
wait_for_host_ssh_fixture
run_logged "05-emulator-readiness" bash -lc \
  "'$ADB' devices && for i in {1..90}; do state=\$('$ADB' shell getprop sys.boot_completed 2>/dev/null | tr -d '\r'); if [ \"\$state\" = 1 ]; then exit 0; fi; sleep 2; done; '$ADB' devices; exit 1"

run_logged "06-cold-reset-emulator-app-state" bash -lc \
  'adb="$1"
  printf "COLD-RESET: uninstalling app/test packages for deterministic visual screenshots\n"
  "$adb" shell am force-stop com.pocketshell.app >/dev/null 2>&1 || true
  "$adb" shell am force-stop com.pocketshell.app.test >/dev/null 2>&1 || true
  "$adb" uninstall com.pocketshell.app >/dev/null 2>&1 || true
  "$adb" uninstall com.pocketshell.app.test >/dev/null 2>&1 || true' \
  _ "$ADB"
run_logged "07-clear-logcat" "$ADB" logcat -c
run_logged "08-clear-device-screenshots" "$ADB" shell rm -rf "$DEVICE_OUTPUT_DIR"
# Issue #2064: when the release chain supplies the pair the pre-release gate
# already built, validated and will publish, this stage must NOT rebuild — its
# screenshots would otherwise be of a different binary than the one shipped.
#
# The skip is a command PREFIX rather than an `if` wrapped around the build.
# That is deliberate: scripts/check-release-gate-execution-profile.sh (issue
# #2054) anchors roughly twenty reachability mutations on the exact text and
# column of the apply line and the `10-build-walkthrough-visual-apks` step
# below, and re-indenting them silently turns those mutations into no-ops — a
# stale anchor is the "mutation that never happened" failure this repo has
# already paid for. Keeping the build statement byte-stable keeps every one of
# those mutations live. The prefix swallows the build command and verifies the
# supplied pair instead.
visual_audit_reuse_validated_apks() {
  printf 'Skipped because VISUAL_AUDIT_BUILD_APKS=0 (issue #2064).\n'
  printf 'App APK: %s\n' "$APP_APK"
  printf 'Test APK: %s\n' "$TEST_APK"
  pocketshell_verify_walkthrough_apks "visual audit (install)"
}

VISUAL_AUDIT_BUILD_PREFIX=()
if [[ "$VISUAL_AUDIT_BUILD_APKS" != "1" ]]; then
  [[ -f "$APP_APK" ]] || fail "VISUAL_AUDIT_BUILD_APKS=0 but the app APK is missing at $APP_APK"
  [[ -f "$TEST_APK" ]] || fail "VISUAL_AUDIT_BUILD_APKS=0 but the androidTest APK is missing at $TEST_APK"
  VISUAL_AUDIT_BUILD_PREFIX=(visual_audit_reuse_validated_apks)
fi

# Nothing is built when reusing, so there is no daemon to stop either — and
# `gradlew --stop` is machine-wide, i.e. it kills SIBLING lanes' daemons (a
# documented cross-agent hazard). Skipping the build removes that too.
if [[ "$VISUAL_AUDIT_BUILD_APKS" = "1" ]]; then
  run_logged "09-stop-gradle-daemons" \
    "$ROOT_DIR/scripts/cgroup-run.sh" --unit "pocketshell-visual-audit-$(pocketshell_unit_token "$RUN_ID")-stop-gradle" -- \
    ./gradlew --stop
fi
pocketshell_apply_release_gate_scope_memory "visual-audit APK build"
run_logged "10-build-walkthrough-visual-apks" \
  "${VISUAL_AUDIT_BUILD_PREFIX[@]}" \
  "$ROOT_DIR/scripts/cgroup-run.sh" --unit "pocketshell-visual-audit-$(pocketshell_unit_token "$RUN_ID")-build-apks" -- \
  ./gradlew --no-daemon --no-build-cache "${POCKETSHELL_GRADLE_RESOURCE_ARGS[@]}" :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
install_apks "11-install-walkthrough-visual-apks"
run_instrumentation_class "12-run-main-walkthrough-visual-instrumentation" "$MAIN_TEST_CLASS"
pull_device_screenshots "13-collect-main-device-screenshots"
assert_screenshots_exist "main walkthrough visual pass" "${MAIN_SCREENSHOTS[@]}"

run_instrumentation_class "14-run-conversation-visual-instrumentation" "$CONVERSATION_TEST_CLASS"
pull_device_screenshots "15-collect-conversation-device-screenshots"
assert_screenshots_exist "conversation visual pass" "${MAIN_SCREENSHOTS[@]}" "${CONVERSATION_SCREENSHOTS[@]}"

run_instrumentation_class "16-run-composer-visual-instrumentation" "$COMPOSER_TEST_CLASS"
pull_device_screenshots "17-collect-composer-device-screenshots"
assert_screenshots_exist "composer visual pass" "${MAIN_SCREENSHOTS[@]}" "${CONVERSATION_SCREENSHOTS[@]}" "${COMPOSER_SCREENSHOTS[@]}"

printf '\nPASS: walkthrough visual screenshots captured\n'
printf 'Screenshots: %s/screenshots/walkthrough-visual-pass\n' "$RUN_DIR"
