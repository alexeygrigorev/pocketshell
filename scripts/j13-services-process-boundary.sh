#!/usr/bin/env bash
set -euo pipefail

# J13's process-death proof is intentionally host-owned. Android reports an
# instrumented target as crashed when that same instrumentation invocation
# force-stops its target package, so the boundary must sit between separate
# `am instrument` invocations. The APK pair stays installed for all phases;
# unlike connectedDebugAndroidTest teardown, this preserves the Room database.
#
# Usage:
#   ANDROID_SERIAL=emulator-5554 \
#   POCKETSHELL_AGENTS_PORT=2243 \
#   scripts/j13-services-process-boundary.sh [suffix]

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
SERIAL="${ANDROID_SERIAL:?ANDROID_SERIAL must identify the emulator held for this evidence run}"
AGENTS_PORT="${POCKETSHELL_AGENTS_PORT:?POCKETSHELL_AGENTS_PORT must keep all phases on the same fixture}"
SUFFIX="${1:-i2611j13}"
APP_PACKAGE="com.pocketshell.app.${SUFFIX}"
TEST_PACKAGE="${APP_PACKAGE}.test"
MAIN_ACTIVITY="${APP_PACKAGE}/com.pocketshell.next.MainActivity"
RUN_DIR="${POCKETSHELL_J13_EVIDENCE_DIR:-$ROOT_DIR/build/j13-process-boundary/$(date +%Y%m%d-%H%M%S)}"
APP_APK="$ROOT_DIR/app2/build/outputs/apk/debug/app2-debug.apk"
TEST_APK="$ROOT_DIR/app2/build/outputs/apk/androidTest/debug/app2-debug-androidTest.apk"
JOURNEY_CLASS="com.pocketshell.next.ports.J13PortForwardOpenJourney"
RUNNER="$TEST_PACKAGE/com.pocketshell.next.HiltNextTestRunner"

# Hold the established per-serial AVD lock across build, install, and all
# three instrumentation phases so no sibling can mutate this emulator between
# the external process boundaries.
source "$ROOT_DIR/scripts/lib/avd-lock.sh"
export POCKETSHELL_AVD_LOCK_CONTINUOUS=1
export POCKETSHELL_AVD_LOCK_FILE="$(pocketshell_avd_lock_file_for_serial "$ROOT_DIR" "$SERIAL")"
pocketshell_acquire_avd_lock "$ROOT_DIR"

mkdir -p "$RUN_DIR"

printf '[J13 process-boundary] building suffixed APK pair for %s\n' "$APP_PACKAGE"
./gradlew --no-daemon :app2:assembleDebug :app2:assembleDebugAndroidTest \
  "-PpocketshellAppIdSuffix=$SUFFIX" \
  -Dorg.gradle.jvmargs=-Xmx1536m \
  -Pkotlin.daemon.jvmargs=-Xmx2048m \
  > "$RUN_DIR/build.log" 2>&1

test -f "$APP_APK"
test -f "$TEST_APK"
"$ADB" -s "$SERIAL" install -r "$APP_APK" > "$RUN_DIR/install-app.log" 2>&1
"$ADB" -s "$SERIAL" install -r "$TEST_APK" > "$RUN_DIR/install-test.log" 2>&1
"$ADB" -s "$SERIAL" shell cmd package wait-for-handler --timeout 60000

run_phase() {
  local phase="$1"
  local output="$RUN_DIR/${phase}-instrumentation.log"
  local xml="$RUN_DIR/${phase}.xml"
  local status=0
  printf '\n[J13 process-boundary] phase=%s serial=%s agents=%s suffix=%s\n' \
    "$phase" "$SERIAL" "$AGENTS_PORT" "$SUFFIX"
  "$ADB" -s "$SERIAL" logcat -c
  set +e
  "$ADB" -s "$SERIAL" shell am instrument -w -r \
    -e class "$JOURNEY_CLASS" \
    -e agentsPort "$AGENTS_PORT" \
    -e j13Phase "$phase" \
    "$RUNNER" > "$output" 2>&1
  status=$?
  set -e
  cat "$output"

  # Keep a red phase's JUnit XML as evidence too. The converter's
  # --require-class gate deliberately rejects an all-failed result, so run it
  # without that gate while diagnosing a failed phase and fail the wrapper from
  # the runner status/failure markers below.
  if (( status != 0 )) || grep -q '^FAILURES!!!$' "$output" || \
    grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[12]$' "$output"; then
    "$ROOT_DIR/scripts/instrumentation-log-to-junit-xml.sh" \
      --log "$output" \
      --out "$xml" \
      --suite "$JOURNEY_CLASS/$phase" || true
    printf '[J13 process-boundary] phase=%s failed: am instrument status=%s\n' \
      "$phase" "$status" >&2
    return 1
  fi
  "$ROOT_DIR/scripts/instrumentation-log-to-junit-xml.sh" \
    --log "$output" \
    --out "$xml" \
    --suite "$JOURNEY_CLASS/$phase" \
    --require-class "$JOURNEY_CLASS"
  if ! grep -q '^INSTRUMENTATION_CODE: -1$' "$output"; then
    printf '[J13 process-boundary] phase=%s failed: instrumentation did not finish cleanly\n' \
      "$phase" >&2
    return 1
  fi
  printf '[J13 process-boundary] phase=%s PASS xml=%s\n' "$phase" "$xml"
}

force_stop_and_relaunch() {
  printf '[J13 process-boundary] am force-stop %s\n' "$APP_PACKAGE"
  "$ADB" -s "$SERIAL" shell am force-stop "$APP_PACKAGE"
  printf '[J13 process-boundary] am start -W %s\n' "$MAIN_ACTIVITY"
  "$ADB" -s "$SERIAL" shell am start -W -n "$MAIN_ACTIVITY"
  "$ADB" -s "$SERIAL" shell cmd package wait-for-handler --timeout 60000
}

run_phase setup
force_stop_and_relaunch
run_phase resume
force_stop_and_relaunch
run_phase removed

printf '\n[J13 process-boundary] PASS: setup, external force-stop/relaunch, resume/remove, external force-stop/relaunch, and removed-state phases completed.\n'
