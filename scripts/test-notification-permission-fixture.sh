#!/usr/bin/env bash
# Issue #1741: fast, emulator-free contract test for the external
# POST_NOTIFICATIONS connected-test fixture and its per-push/nightly wiring.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
REAL_CONNECTED="$SCRIPT_DIR/connected-test.sh"
REAL_CONNECTED="${NOTIFICATION_FIXTURE_CONNECTED_SOURCE:-$REAL_CONNECTED}"
REAL_BUDGET_HELPER="$SCRIPT_DIR/ci-journey-budget-functions.sh"
REAL_NIGHTLY="$SCRIPT_DIR/nightly-extensive-suite.sh"
NOTIFICATION_CLASS="com.pocketshell.app.notifications.NoNotificationPromptOnAppOpenE2eTest"
NOTIFICATION_METHOD="appOpenDoesNotPopNotificationPermissionDialog"

fail() {
  echo "TEST FAIL: $*" >&2
  exit 1
}

pass() {
  echo "  ok: $*"
}

for required in "$REAL_CONNECTED" "$REAL_BUDGET_HELPER" "$REAL_NIGHTLY"; do
  [[ -f "$required" ]] || fail "missing required source: $required"
done
grep -q 'shell dumpsys package "$target_package"' "$REAL_CONNECTED" \
  || fail "permission-state verification must use Android's supported dumpsys package oracle"
if grep -q 'pm check-permission' "$REAL_CONNECTED"; then
  fail "unsupported pm check-permission command must not be used"
fi

FIXTURE_ROOT="$(mktemp -d)"
trap 'rm -rf "$FIXTURE_ROOT"' EXIT
mkdir -p "$FIXTURE_ROOT/repo/scripts/lib" "$FIXTURE_ROOT/stubbin" "$FIXTURE_ROOT/state"
cp "$REAL_CONNECTED" "$FIXTURE_ROOT/repo/scripts/connected-test.sh"
# Glob the WHOLE scripts/lib rather than enumerating it. An enumerated list rots
# silently the moment connected-test.sh sources a new library: the fixture then
# dies at `source: No such file or directory` and this suite reports a failure
# about the permission fixture that has nothing to do with permissions. It has
# already had to be extended twice by hand (#2007's gradle-output-lock.sh, and
# #1989's disk-preflight.sh); avd-pool-test.sh learned the same lesson in #1853.
cp "$SCRIPT_DIR"/lib/*.sh "$FIXTURE_ROOT/repo/scripts/lib/"
chmod +x "$FIXTURE_ROOT/repo/scripts/connected-test.sh"
# Issue #1989: this fixture is about the POST_NOTIFICATIONS permission dance,
# not about disk, and it runs on hosted runners whose free space is not its
# business. Pin the floor to 0 MiB -- a threshold, not a skip, so the preflight
# still runs on the real wrapper here.
export POCKETSHELL_DISK_MIN_FREE_MB=0
export POCKETSHELL_DISK_WARN_FREE_MB=0
# Keep this fixture's output lock inside its own temporary tree rather than the
# real per-user lock directory, so it can never queue behind (or hold up) a real
# build on the box.
export POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR="$FIXTURE_ROOT/gradle-output-locks"

# Keep the fixture out of the host's user systemd while exercising the real
# wrapper. scope-run will take its explicit test-only bare fallback.
printf '%s\n' '#!/usr/bin/env bash' 'exit 1' > "$FIXTURE_ROOT/stubbin/systemctl"
chmod +x "$FIXTURE_ROOT/stubbin/systemctl"

# Fake adb models exactly one API-35 emulator and records every permission
# transition. It deliberately preserves runtime grants across the fake
# installDebug, matching adb install -r.
cat > "$FIXTURE_ROOT/stubbin/adb" <<'ADB_STUB'
#!/usr/bin/env bash
set -u
state_dir="${NOTIFICATION_FIXTURE_STATE:?}"
if [[ "${1:-}" == "-s" ]]; then
  shift 2
fi
if [[ "${1:-}" == "install" && "${2:-}" == "-r" ]]; then
  package="$(cat "$state_dir/last-package" 2>/dev/null || true)"
  [[ -n "$package" ]] || exit 43
  printf 'adb:install:%s\n' "$package" >> "$state_dir/events.log"
  : > "$state_dir/installed-$package"
  exit 0
fi
[[ "${1:-}" == "shell" ]] && shift

case "${1:-} ${2:-} ${3:-}" in
  "pm list packages"*)
    exit 0
    ;;
  "getprop ro.build.version.sdk "*)
    printf '%s\n' "${ADB_FIXTURE_SDK:-35}"
    exit 0
    ;;
  "pm path "*)
    package="${3:-}"
    printf 'adb:pm-path:%s\n' "$package" >> "$state_dir/events.log"
    if [[ -f "$state_dir/installed-$package" ]]; then
      printf 'package:/data/app/%s/base.apk\n' "$package"
    fi
    exit 0
    ;;
  "pm revoke "*)
    package="${3:-}"
    permission="${4:-}"
    printf 'adb:revoke:%s:%s\n' "$package" "$permission" >> "$state_dir/events.log"
    [[ "${ADB_FIXTURE_FAIL_REVOKE:-0}" == "1" ]] && exit 41
    if [[ "${ADB_FIXTURE_REVOKE_DRIFT:-0}" != "1" ]]; then
      printf 'denied\n' > "$state_dir/permission-$package"
    fi
    exit 0
    ;;
  "pm grant "*)
    package="${3:-}"
    permission="${4:-}"
    printf 'adb:grant:%s:%s\n' "$package" "$permission" >> "$state_dir/events.log"
    [[ "${ADB_FIXTURE_FAIL_GRANT:-0}" == "1" ]] && exit 42
    # Drift models the real Android hazard the failing-exit case cannot: pm
    # grant reports success while the runtime state stays denied.
    if [[ "${ADB_FIXTURE_GRANT_DRIFT:-0}" != "1" ]]; then
      printf 'granted\n' > "$state_dir/permission-$package"
    fi
    exit 0
    ;;
  "dumpsys package "*)
    package="${3:-}"
    state="$(cat "$state_dir/permission-$package" 2>/dev/null || printf 'denied')"
    printf 'adb:state:%s:android.permission.POST_NOTIFICATIONS:%s\n' "$package" "$state" \
      >> "$state_dir/events.log"
    granted=false
    [[ "$state" == "granted" ]] && granted=true
    printf '      runtime permissions:\n'
    printf '        android.permission.POST_NOTIFICATIONS: granted=%s, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]\n' \
      "$granted"
    exit 0
    ;;
esac

if [[ "${1:-}" == "uninstall" ]]; then
  exit 0
fi
printf 'unexpected adb args: %s\n' "$*" >&2
exit 90
ADB_STUB
chmod +x "$FIXTURE_ROOT/stubbin/adb"

# Fake Gradle distinguishes the pre-instrumentation install from the runner.
# The connected leg refuses to run unless adb already recorded DENIED, then
# writes the same report shape the real wrapper validates.
cat > "$FIXTURE_ROOT/repo/gradlew" <<'GRADLE_STUB'
#!/usr/bin/env bash
set -u
state_dir="${NOTIFICATION_FIXTURE_STATE:?}"
package="com.pocketshell.app"
for arg in "$@"; do
  case "$arg" in
    -PpocketshellAppIdSuffix=*)
      package="com.pocketshell.app.${arg#*=}"
      ;;
  esac
done

if [[ "$*" == *":app:installDebug"* ]]; then
  permission="$(cat "$state_dir/permission-$package" 2>/dev/null || printf 'denied')"
  printf 'gradle:install:%s:permission=%s\n' "$package" "$permission" \
    >> "$state_dir/events.log"
  if [[ "${GRADLE_FIXTURE_BEHAVIOR:-pass}" != "missing-package" ]]; then
    : > "$state_dir/installed-$package"
    printf '%s\n' "$package" > "$state_dir/last-package"
    mkdir -p "$PWD/app/build/outputs/apk/debug"
    printf 'fixture apk for %s\n' "$package" > "$PWD/app/build/outputs/apk/debug/app-debug.apk"
  fi
  exit "${GRADLE_FIXTURE_INSTALL_RC:-0}"
fi

if [[ "$*" == *":app:connectedDebugAndroidTest"* ]]; then
  permission="$(cat "$state_dir/permission-$package" 2>/dev/null || printf 'missing')"
  printf 'gradle:connected:%s:permission=%s\n' "$package" "$permission" \
    >> "$state_dir/events.log"
  [[ "$permission" == "denied" ]] || exit 91
  # AGP removes the target APK when connectedDebugAndroidTest completes. Model
  # that real lifecycle so cleanup must reinstall before restoring the grant.
  rm -f "$state_dir/installed-$package"
  report_dir="$PWD/app/build/outputs/androidTest-results/connected/fixture"
  mkdir -p "$report_dir"
  case "${GRADLE_FIXTURE_BEHAVIOR:-pass}" in
    pass)
      cat > "$report_dir/TEST-notification.xml" <<XML
<testsuite name="$NOTIFICATION_FIXTURE_CLASS" tests="1" skipped="0" failures="0" errors="0">
  <testcase classname="$NOTIFICATION_FIXTURE_CLASS" name="$NOTIFICATION_FIXTURE_METHOD"/>
</testsuite>
XML
      exit 0
      ;;
    runner-kill)
      printf '<testsuite name="runner" tests="0" skipped="0" failures="1" errors="0"><failure/></testsuite>\n' \
        > "$report_dir/TEST-notification.xml"
      exit 137
      ;;
    zero-tests)
      printf '<testsuite name="empty" tests="0" skipped="0" failures="0" errors="0"/>\n' \
        > "$report_dir/TEST-notification.xml"
      exit 0
      ;;
    test-failure)
      cat > "$report_dir/TEST-notification.xml" <<XML
<testsuite name="$NOTIFICATION_FIXTURE_CLASS" tests="1" skipped="0" failures="1" errors="0">
  <testcase classname="$NOTIFICATION_FIXTURE_CLASS" name="$NOTIFICATION_FIXTURE_METHOD"><failure/></testcase>
</testsuite>
XML
      exit 1
      ;;
    self-skip)
      # One executed + one self-skipped test still yields executed=1. The
      # no-self-skip policy must reject it on the skip count alone.
      cat > "$report_dir/TEST-notification.xml" <<XML
<testsuite name="$NOTIFICATION_FIXTURE_CLASS" tests="2" skipped="1" failures="0" errors="0">
  <testcase classname="$NOTIFICATION_FIXTURE_CLASS" name="$NOTIFICATION_FIXTURE_METHOD"/>
  <testcase classname="$NOTIFICATION_FIXTURE_CLASS" name="someAssumptionSkippedCase"><skipped/></testcase>
</testsuite>
XML
      exit 0
      ;;
    no-report)
      # The runner produced NO report at all and still exited zero. Any earlier
      # invocation's XML left on disk must not be borrowed as this run's result.
      exit 0
      ;;
    wrong-class)
      # A foreign class's green report must never satisfy this dedicated
      # invocation, even with a perfectly non-vacuous executed count.
      cat > "$report_dir/TEST-notification.xml" <<XML
<testsuite name="com.pocketshell.app.proof.SomeOtherE2eTest" tests="1" skipped="0" failures="0" errors="0">
  <testcase classname="com.pocketshell.app.proof.SomeOtherE2eTest" name="someOtherJourney"/>
</testsuite>
XML
      exit 0
      ;;
  esac
fi

printf 'unexpected gradle args: %s\n' "$*" >&2
exit 92
GRADLE_STUB
chmod +x "$FIXTURE_ROOT/repo/gradlew"

line_number() {
  local needle="$1"
  local file="$2"
  grep -nF "$needle" "$file" | head -1 | cut -d: -f1
}

assert_order() {
  local file="$1"
  shift
  local previous=0 needle current
  for needle in "$@"; do
    current="$(line_number "$needle" "$file")"
    [[ "$current" =~ ^[0-9]+$ ]] \
      || fail "missing ordering event '$needle' in $file"
    (( current > previous )) \
      || fail "event '$needle' occurred out of order in $file"
    previous="$current"
  done
}

run_fixture() {
  local label="$1"
  local suffix="$2"
  local behavior="$3"
  local initial_permission="$4"
  local fail_revoke="${5:-0}"
  local fail_grant="${6:-0}"
  local revoke_drift="${7:-0}"
  local sdk="${8:-35}"
  local grant_drift="${9:-0}"
  local package="com.pocketshell.app${suffix:+.$suffix}"
  local output="$FIXTURE_ROOT/$label.log"
  local -a suffix_args=()
  [[ -n "$suffix" ]] && suffix_args=(--suffix "$suffix")

  rm -rf "$FIXTURE_ROOT/state"
  mkdir -p "$FIXTURE_ROOT/state"
  printf '%s\n' "$initial_permission" > "$FIXTURE_ROOT/state/permission-$package"

  set +e
  (
    cd "$FIXTURE_ROOT/repo" || exit 99
    PATH="$FIXTURE_ROOT/stubbin:$PATH" \
      ADB="$FIXTURE_ROOT/stubbin/adb" \
      ANDROID_SERIAL="emulator-1741" \
      POCKETSHELL_AVD_LOCK_DIR="$FIXTURE_ROOT/locks" \
      POCKETSHELL_SCOPE_ALLOW_BARE=1 \
      NOTIFICATION_FIXTURE_STATE="$FIXTURE_ROOT/state" \
      NOTIFICATION_FIXTURE_CLASS="$NOTIFICATION_CLASS" \
      NOTIFICATION_FIXTURE_METHOD="$NOTIFICATION_METHOD" \
      GRADLE_FIXTURE_BEHAVIOR="$behavior" \
      ADB_FIXTURE_FAIL_REVOKE="$fail_revoke" \
      ADB_FIXTURE_FAIL_GRANT="$fail_grant" \
      ADB_FIXTURE_REVOKE_DRIFT="$revoke_drift" \
      ADB_FIXTURE_GRANT_DRIFT="$grant_drift" \
      ADB_FIXTURE_SDK="$sdk" \
      scripts/connected-test.sh --no-pool "${suffix_args[@]}" \
        --deny-notifications-before-instrumentation \
        -Pandroid.testInstrumentationRunnerArguments.class="$NOTIFICATION_CLASS"
  ) > "$output" 2>&1
  FIXTURE_RC=$?
  set -e
  FIXTURE_PACKAGE="$package"
  FIXTURE_OUTPUT="$output"
}

# Argument-contract probe. These invocations are rejected during flag validation,
# before any lock/adb/Gradle work, so they need no device state.
run_fixture_args() {
  local label="$1"
  shift
  local output="$FIXTURE_ROOT/args-$label.log"
  rm -rf "$FIXTURE_ROOT/state"
  mkdir -p "$FIXTURE_ROOT/state"
  set +e
  (
    cd "$FIXTURE_ROOT/repo" || exit 99
    PATH="$FIXTURE_ROOT/stubbin:$PATH" \
      ADB="$FIXTURE_ROOT/stubbin/adb" \
      ANDROID_SERIAL="emulator-1741" \
      POCKETSHELL_AVD_LOCK_DIR="$FIXTURE_ROOT/locks" \
      POCKETSHELL_SCOPE_ALLOW_BARE=1 \
      NOTIFICATION_FIXTURE_STATE="$FIXTURE_ROOT/state" \
      NOTIFICATION_FIXTURE_CLASS="$NOTIFICATION_CLASS" \
      NOTIFICATION_FIXTURE_METHOD="$NOTIFICATION_METHOD" \
      scripts/connected-test.sh --no-pool "$@"
  ) > "$output" 2>&1
  FIXTURE_RC=$?
  set -e
  FIXTURE_OUTPUT="$output"
}

echo "== External fixture: granted base package -> denied runner -> restored grant =="
run_fixture base-pass "" pass granted
[[ "$FIXTURE_RC" -eq 0 ]] || { cat "$FIXTURE_OUTPUT"; fail "base fixture exited $FIXTURE_RC"; }
[[ "$(cat "$FIXTURE_ROOT/state/permission-$FIXTURE_PACKAGE")" == "granted" ]] \
  || fail "base package grant was not restored"
assert_order "$FIXTURE_ROOT/state/events.log" \
  "gradle:install:$FIXTURE_PACKAGE:permission=granted" \
  "adb:revoke:$FIXTURE_PACKAGE:android.permission.POST_NOTIFICATIONS" \
  "adb:state:$FIXTURE_PACKAGE:android.permission.POST_NOTIFICATIONS:denied" \
  "gradle:connected:$FIXTURE_PACKAGE:permission=denied" \
  "adb:install:$FIXTURE_PACKAGE" \
  "adb:grant:$FIXTURE_PACKAGE:android.permission.POST_NOTIFICATIONS" \
  "adb:state:$FIXTURE_PACKAGE:android.permission.POST_NOTIFICATIONS:granted"
grep -q '^NOTIFICATION_PERMISSION_TEST_RESULT executed=1 skipped=0 failures=0 errors=0 ' \
  "$FIXTURE_OUTPUT" || fail "base pass did not report one named executed test"
pass "grant-first -> external revoke -> alive runner -> named 1/1 result -> external restore"

echo "== External fixture: exact suffixed package and repeated order isolation =="
run_fixture suffix-pass i1741 pass granted
[[ "$FIXTURE_RC" -eq 0 ]] || { cat "$FIXTURE_OUTPUT"; fail "suffix fixture exited $FIXTURE_RC"; }
[[ "$FIXTURE_PACKAGE" == "com.pocketshell.app.i1741" ]] \
  || fail "suffix package resolution drifted: $FIXTURE_PACKAGE"
grep -q "gradle:connected:com.pocketshell.app.i1741:permission=denied" \
  "$FIXTURE_ROOT/state/events.log" || fail "suffixed runner did not see denied permission"
[[ "$(cat "$FIXTURE_ROOT/state/permission-$FIXTURE_PACKAGE")" == "granted" ]] \
  || fail "suffixed package grant was not restored for the following grant-first class"
run_fixture repeated i1741 pass granted
[[ "$FIXTURE_RC" -eq 0 ]] || { cat "$FIXTURE_OUTPUT"; fail "repeated fixture exited $FIXTURE_RC"; }
pass "base/suffix identity, notification -> grant-first cleanup, and repeated invocation are isolated"

echo "== External fixture: already-denied state remains non-vacuous =="
run_fixture already-denied "" pass denied
[[ "$FIXTURE_RC" -eq 0 ]] || { cat "$FIXTURE_OUTPUT"; fail "already-denied fixture exited $FIXTURE_RC"; }
grep -q "gradle:connected:$FIXTURE_PACKAGE:permission=denied" \
  "$FIXTURE_ROOT/state/events.log" || fail "already-denied case never ran instrumentation"
pass "already-denied package still executes the real named test and restores grant"

echo "== Failure controls: revoke/drift/API/package/runner/count/cleanup all hard-red =="
run_fixture revoke-fails "" pass granted 1
[[ "$FIXTURE_RC" -ne 0 ]] || fail "failed revoke became green"
if grep -q 'gradle:connected:' "$FIXTURE_ROOT/state/events.log"; then
  fail "runner started after failed external revoke"
fi
[[ "$(cat "$FIXTURE_ROOT/state/permission-$FIXTURE_PACKAGE")" == "granted" ]] \
  || fail "failed-revoke path did not run external cleanup"

run_fixture denied-drift "" pass granted 0 0 1
[[ "$FIXTURE_RC" -ne 0 ]] || fail "granted precondition drift became green"
if grep -q 'gradle:connected:' "$FIXTURE_ROOT/state/events.log"; then
  fail "runner started while permission verification still said granted"
fi

run_fixture api-too-old "" pass granted 0 0 0 32
[[ "$FIXTURE_RC" -ne 0 ]] || fail "API <33 became green"

run_fixture missing-package "" missing-package granted
[[ "$FIXTURE_RC" -ne 0 ]] || fail "missing installed target became green"
grep -q 'is not installed before permission revoke' "$FIXTURE_OUTPUT" \
  || { cat "$FIXTURE_OUTPUT"; fail "missing target was not rejected by the pre-revoke installed check"; }
if grep -q 'adb:revoke:' "$FIXTURE_ROOT/state/events.log"; then
  fail "permission revoke was attempted against a package that is not installed"
fi

run_fixture runner-kill "" runner-kill granted
[[ "$FIXTURE_RC" -eq 137 ]] \
  || { cat "$FIXTURE_OUTPUT"; fail "runner signal-9 rc was not preserved (got $FIXTURE_RC)"; }
[[ "$(cat "$FIXTURE_ROOT/state/permission-$FIXTURE_PACKAGE")" == "granted" ]] \
  || fail "runner-kill path did not restore grant"

run_fixture assertion-failure "" test-failure granted
[[ "$FIXTURE_RC" -ne 0 ]] || fail "instrumentation assertion failure became green"
[[ "$(cat "$FIXTURE_ROOT/state/permission-$FIXTURE_PACKAGE")" == "granted" ]] \
  || fail "ordinary failed-test path did not restore grant"

run_fixture zero-tests "" zero-tests granted
[[ "$FIXTURE_RC" -ne 0 ]] || fail "zero-test XML became green"
grep -q 'must execute exactly one test' "$FIXTURE_OUTPUT" \
  || fail "zero-test failure did not explain its non-vacuous verdict"

run_fixture cleanup-fails "" pass granted 0 1
[[ "$FIXTURE_RC" -ne 0 ]] || fail "cleanup failure masked a passing primary run"
grep -q 'NOTIFICATION_PERMISSION_PRIMARY_RC=0 CLEANUP_RC=' "$FIXTURE_OUTPUT" \
  || fail "cleanup failure did not preserve/report primary versus cleanup status"

# Restore drift: pm grant exits zero while the runtime state stays denied. A
# silently-unrestored grant would leave the next grant-first journey class on a
# revoked permission, so verification after restore must be load-bearing.
run_fixture restore-drift "" pass granted 0 0 0 35 1
[[ "$FIXTURE_RC" -ne 0 ]] || fail "silently unrestored grant became green"
grep -q 'was not restored for' "$FIXTURE_OUTPUT" \
  || { cat "$FIXTURE_OUTPUT"; fail "restore drift was not caught by post-grant verification"; }
pass "all wrong-state, killed-runner, zero-test, restore-drift, and cleanup mutations are hard-red"

# Report identity: a dedicated invocation may never be satisfied by a foreign
# class's green XML, nor borrow a previous invocation's report when this runner
# produced none. Both are the exact vacuous-pass shapes issue #1741 exists to
# stop, so they are proven against a real prior passing report on disk.
echo "== Report identity: no borrowed prior XML, no foreign-class XML =="
run_fixture stale-seed "" pass granted
[[ "$FIXTURE_RC" -eq 0 ]] || { cat "$FIXTURE_OUTPUT"; fail "stale-seed fixture exited $FIXTURE_RC"; }
run_fixture stale-reuse "" no-report granted
[[ "$FIXTURE_RC" -ne 0 ]] || fail "a reportless runner borrowed the previous invocation's XML"
grep -q 'must execute exactly one test (xml=0' "$FIXTURE_OUTPUT" \
  || { cat "$FIXTURE_OUTPUT"; fail "stale report directory was not cleared before instrumentation"; }

run_fixture self-skip "" self-skip granted
[[ "$FIXTURE_RC" -ne 0 ]] || fail "a self-skipped test rode along with the executed one"
grep -q 'skipped 1 test(s); no-self-skip policy requires zero' "$FIXTURE_OUTPUT" \
  || { cat "$FIXTURE_OUTPUT"; fail "no-self-skip policy was not enforced on the skip count"; }

run_fixture wrong-class "" wrong-class granted
[[ "$FIXTURE_RC" -ne 0 ]] || fail "a foreign class's green report satisfied the dedicated invocation"
grep -q "did not contain $NOTIFICATION_CLASS#$NOTIFICATION_METHOD" "$FIXTURE_OUTPUT" \
  || { cat "$FIXTURE_OUTPUT"; fail "foreign-class report was not rejected by named-method identity"; }
pass "reportless and foreign-class runs are hard-red; only the named test counts"

# The fixture is only ever legitimate as ONE dedicated, unsharded, app-module
# invocation of the notification class. Every other shape must be refused before
# any permission is touched, so a sharded caller can never silently reintroduce
# the grant-first ordering contamination this issue exists to remove.
#
# `run_fixture_args` resets the device-state dir per probe, so the
# "no permission was touched" property must be asserted after EACH probe. A
# single trailing check only ever saw the LAST probe's state, which let a
# rejection that had been relocated to AFTER the revoke survive on any earlier
# probe — the same "assertion whose text is broader than what it constrains"
# shape as the nightly pins below.
echo "== Argument contract: dedicated, unsharded, app-module, notification-only =="

assert_probe_touched_no_permission() {
  local label="$1"
  local events="$FIXTURE_ROOT/state/events.log"
  if grep -q 'adb:revoke:' "$events" 2>/dev/null; then
    tr '\n' ' ' < "$events" >&2
    echo >&2
    fail "rejected fixture invocation '$label' still mutated the notification permission"
  fi
}

run_fixture_args sharded --deny-notifications-before-instrumentation \
  -Pandroid.testInstrumentationRunnerArguments.class="$NOTIFICATION_CLASS" \
  -Pandroid.testInstrumentationRunnerArguments.numShards=3
[[ "$FIXTURE_RC" -ne 0 ]] || fail "sharded notification invocation was accepted"
grep -q 'must be a dedicated unsharded invocation' "$FIXTURE_OUTPUT" \
  || { cat "$FIXTURE_OUTPUT"; fail "numShards was not rejected by the dedicated-invocation contract"; }
assert_probe_touched_no_permission sharded

run_fixture_args shard-index --deny-notifications-before-instrumentation \
  -Pandroid.testInstrumentationRunnerArguments.class="$NOTIFICATION_CLASS" \
  -Pandroid.testInstrumentationRunnerArguments.shardIndex=1
[[ "$FIXTURE_RC" -ne 0 ]] || fail "shardIndex notification invocation was accepted"
grep -q 'must be a dedicated unsharded invocation' "$FIXTURE_OUTPUT" \
  || { cat "$FIXTURE_OUTPUT"; fail "shardIndex was not rejected by the dedicated-invocation contract"; }
assert_probe_touched_no_permission shard-index

run_fixture_args unfiltered --deny-notifications-before-instrumentation
[[ "$FIXTURE_RC" -ne 0 ]] || fail "unfiltered whole-suite notification fixture was accepted"
grep -q "requires dedicated class=$NOTIFICATION_CLASS" "$FIXTURE_OUTPUT" \
  || { cat "$FIXTURE_OUTPUT"; fail "fixture did not require the dedicated notification class"; }
assert_probe_touched_no_permission unfiltered

run_fixture_args wrong-module --module shared:core-ssh \
  --deny-notifications-before-instrumentation \
  -Pandroid.testInstrumentationRunnerArguments.class="$NOTIFICATION_CLASS"
[[ "$FIXTURE_RC" -ne 0 ]] || fail "non-app module notification fixture was accepted"
grep -q 'valid only for the app module' "$FIXTURE_OUTPUT" \
  || { cat "$FIXTURE_OUTPUT"; fail "fixture did not reject a non-app module"; }
assert_probe_touched_no_permission wrong-module
pass "sharded, unfiltered, and non-app invocations are refused before any permission changes"

# Per-push dynamic wiring: source the real helper, but replace its bounded
# executor with a direct call into tiny command stubs. This proves both serial
# run_class and suffixed shard_class select the canonical flag only for #1741.
echo "== Per-push wiring: run_class and shard_class use the canonical fixture =="
WIRE_ROOT="$FIXTURE_ROOT/wire"
mkdir -p "$WIRE_ROOT/scripts"
cat > "$WIRE_ROOT/scripts/connected-test.sh" <<'WIRE_CONNECTED'
#!/usr/bin/env bash
printf 'connected:%s\n' "$*" >> "${WIRE_LOG:?}"
exit 0
WIRE_CONNECTED
cat > "$WIRE_ROOT/gradlew" <<'WIRE_GRADLE'
#!/usr/bin/env bash
printf 'gradle:%s\n' "$*" >> "${WIRE_LOG:?}"
exit 0
WIRE_GRADLE
chmod +x "$WIRE_ROOT/scripts/connected-test.sh" "$WIRE_ROOT/gradlew"

# shellcheck source=scripts/ci-journey-budget-functions.sh
source "$REAL_BUDGET_HELPER"
run_bounded() {
  shift
  "$@"
}
cleanup_gradle_after_timeout() {
  return 0
}
# Issue #1458 added a fail-closed per-attempt artifact lifecycle around
# run_class. This focused #1741 wiring probe owns only command selection, so
# replace that orthogonal lifecycle with explicit no-op stubs; the complete
# journey-budget guard exercises the real artifact setup/snapshot/finalization.
begin_class_attempt_artifacts() {
  LAST_RUN_CLASS_ATTEMPT_DIR="$WIRE_ROOT/attempt"
  mkdir -p "$LAST_RUN_CLASS_ATTEMPT_DIR"
  : > "$LAST_RUN_CLASS_ATTEMPT_DIR/attempt.log"
}
snapshot_connected_test_outputs() {
  return 0
}
finalize_class_attempt_manifest() {
  return 0
}
REPO_ROOT="$WIRE_ROOT"
GRADLEW="$WIRE_ROOT/gradlew"
SUITE_START=$SECONDS
JOURNEY_STEP_BUDGET_SECS=600
JOURNEY_CLASS_TIMEOUT_SECS=60
JOURNEY_NO_OUTPUT_TIMEOUT_SECS=60
JOURNEY_CLASS_KILL_AFTER_SECS=1
JOURNEY_GRADLE_STOP_TIMEOUT_SECS=1
WIRE_LOG="$WIRE_ROOT/wire.log"
export WIRE_LOG
: > "$WIRE_LOG"
run_class "$NOTIFICATION_CLASS" || fail "notification run_class stub failed"
run_class "com.pocketshell.app.proof.SomeOtherE2eTest" || fail "ordinary run_class stub failed"
shard_class 7 "$NOTIFICATION_CLASS" || fail "notification shard_class stub failed"
shard_class 8 "com.pocketshell.app.proof.SomeOtherE2eTest" || fail "ordinary shard_class stub failed"

notification_flag_count="$(grep -c 'deny-notifications-before-instrumentation' "$WIRE_LOG" || true)"
[[ "$notification_flag_count" -eq 2 ]] \
  || { cat "$WIRE_LOG"; fail "expected fixture flag exactly twice (serial + shard), got $notification_flag_count"; }
grep -q "connected:--no-pool --deny-notifications-before-instrumentation .*class=$NOTIFICATION_CLASS" \
  "$WIRE_LOG" || { cat "$WIRE_LOG"; fail "serial notification class bypassed connected-test fixture"; }
grep -q "connected:--pool --suffix ij7 --deny-notifications-before-instrumentation .*class=$NOTIFICATION_CLASS" \
  "$WIRE_LOG" || { cat "$WIRE_LOG"; fail "suffixed shard notification class bypassed fixture"; }
grep -q 'gradle::app:connectedDebugAndroidTest .*class=com.pocketshell.app.proof.SomeOtherE2eTest' \
  "$WIRE_LOG" || { cat "$WIRE_LOG"; fail "ordinary serial class no longer uses daemon-reused Gradle path"; }
pass "serial and suffixed per-push paths select the fixture without changing ordinary classes"

# Nightly structural contract: class excluded from the bulk shard, dedicated on
# shard-0/aux only, unsharded, report-preserved, count-visible, human-red, and
# absent from the release fault-verdict inputs.
#
# ANCHORING RULE (issue #1741 review round 2). Unlike the sections above — which
# EXECUTE the real connected-test wrapper against a fixture and therefore assert
# behaviour — the nightly contract can only be asserted structurally, by reading
# `scripts/nightly-extensive-suite.sh`. A structural assertion is only worth its
# message if deleting the behaviour it names makes it RED, and a bare
# `grep -q '<token>' "$REAL_NIGHTLY"` does NOT satisfy that: the same token
# reliably occurs on some other line (a log filename carrying the report slug, a
# `-P...class=` argument carrying the class variable, an `echo` interpolating the
# count variable), so the behaviour can be deleted while the guard stays green.
# Every pin below is therefore anchored to the code construct that OWNS the
# behaviour, in one of two ways:
#   1. block-scoped  — `sed`-extract the array / phase / conditional that owns
#      it and assert inside that extract only, with a non-vacuous extraction
#      check so an empty extract can never satisfy a negative assertion; or
#   2. whole-statement — assert against the complete command, after joining
#      backslash continuations, so a fragment shared with an unrelated line
#      cannot match.
# Both forms anchor with `^[[:space:]]*` ... `$` or match a full statement, which
# also excludes comment lines that merely mention the construct.
echo "== Nightly phase-1b isolation and verdict wiring =="

# Join backslash-continued lines so a multi-line command can be pinned as ONE
# statement instead of as a fragment.
nightly_joined="$FIXTURE_ROOT/nightly-joined.sh"
awk '{
  line = $0
  while (sub(/[[:space:]]*\\$/, "", line) > 0) {
    if ((getline nextline) <= 0) break
    sub(/^[[:space:]]+/, "", nextline)
    line = line " " nextline
  }
  print line
}' "$REAL_NIGHTLY" > "$nightly_joined"
[[ -s "$nightly_joined" ]] \
  || fail "could not normalise line continuations in $REAL_NIGHTLY"

# Assert that ONE joined statement carries a required token, so the token can
# never be satisfied by a different line elsewhere in the same file.
assert_statement_contains() {
  local statement="$1" token="$2" message="$3"
  case "$statement" in
    *"$token"*) return 0 ;;
  esac
  printf '%s\n' "$statement" >&2
  fail "$message (missing: $token)"
}

# Bulk-phase isolation — the ordering contamination this issue exists to remove.
# Scoped to the JOURNEY_EXCLUDED_CLASSES array literal: the entry must be an
# array element, NOT the `-P...class="$NOTIFICATION_PERMISSION_TEST_CLASS"`
# argument of the dedicated phase-1b invocation, which carries the same token.
journey_excluded_block="$FIXTURE_ROOT/journey-excluded-classes.txt"
sed -n '/^JOURNEY_EXCLUDED_CLASSES=(/,/^)$/p' "$REAL_NIGHTLY" \
  > "$journey_excluded_block"
grep -q '^JOURNEY_EXCLUDED_CLASSES=($' "$journey_excluded_block" \
  || fail "could not extract the nightly JOURNEY_EXCLUDED_CLASSES array literal"
grep -q '^)$' "$journey_excluded_block" \
  || fail "nightly JOURNEY_EXCLUDED_CLASSES array literal is unterminated"
# shellcheck disable=SC2016
grep -q '^[[:space:]]*"\$NOTIFICATION_PERMISSION_TEST_CLASS"[[:space:]]*$' \
  "$journey_excluded_block" \
  || fail "nightly bulk exclusion does not include notification class"

# That array only isolates anything while it is still joined into notClass AND
# that argument still reaches the bulk phase-1 invocation, so pin both hops.
# shellcheck disable=SC2016
grep -q '^JOURNEY_NOTCLASS_ARG="\$(join_by , "\${JOURNEY_EXCLUDED_CLASSES\[@\]}")"$' \
  "$nightly_joined" \
  || fail "nightly notClass argument is no longer built from JOURNEY_EXCLUDED_CLASSES"
# shellcheck disable=SC2016
journey_invocation="$(grep '^"\$GRADLEW" :app:connectedDebugAndroidTest ' \
  "$nightly_joined" | head -1)"
[[ -n "$journey_invocation" ]] \
  || fail "could not locate the nightly bulk phase-1 journey invocation"
# shellcheck disable=SC2016
assert_statement_contains "$journey_invocation" \
  '-Pandroid.testInstrumentationRunnerArguments.notClass="$JOURNEY_NOTCLASS_ARG"' \
  "nightly bulk phase 1 no longer excludes the JOURNEY_EXCLUDED_CLASSES set"

# The dedicated phase-1b block. Every pin below reads this extract, so prove the
# extract is non-vacuous first — otherwise the negative shard-argument assertion
# further down would pass over an empty file.
nightly_phase1b="$FIXTURE_ROOT/nightly-phase1b.txt"
sed -n '/phase 1b: notification permission/,/phase 2: network-fault proofs/p' \
  "$nightly_joined" > "$nightly_phase1b"
[[ -s "$nightly_phase1b" ]] \
  || fail "could not extract the nightly phase 1b block"

notification_invocation="$(grep -F '/scripts/connected-test.sh' "$nightly_phase1b" \
  | head -1)"
[[ -n "$notification_invocation" ]] \
  || fail "nightly phase 1b does not run the class through the connected-test wrapper"
assert_statement_contains "$notification_invocation" '--no-pool' \
  "nightly phase 1b invocation no longer pins the single shared emulator lane"
assert_statement_contains "$notification_invocation" \
  '--deny-notifications-before-instrumentation' \
  "nightly phase 1b does not use the canonical external fixture"
# shellcheck disable=SC2016
assert_statement_contains "$notification_invocation" \
  '-Pandroid.testInstrumentationRunnerArguments.class="$NOTIFICATION_PERMISSION_TEST_CLASS"' \
  "nightly phase 1b is no longer a dedicated invocation of the notification class"
# shellcheck disable=SC2016
assert_statement_contains "$notification_invocation" \
  'tee "$notification_permission_log"' \
  "nightly phase 1b no longer tees the wrapper output into its own log"

# Report preservation: pin the two-line CALL, not the bare slug — the slug also
# appears in `notification_permission_log=...phase1b-notification-permission.log`
# inside this very block, so a slug grep survives deleting the snapshot and the
# phase-1b evidence is then silently clobbered by phase 2.
# shellcheck disable=SC2016
grep -q '^[[:space:]]*preserve_phase_reports "phase1b-notification-permission" "\$APP_BUILD_DIR" "\$PHASE_REPORTS_DIR"$' \
  "$nightly_phase1b" \
  || fail "nightly phase 1b does not snapshot its own report before phase 2 clobbers it"

# Executed-count capture: pin the literal capture expression, not the variable
# name — the name also appears on the initialiser, the default-fallback and two
# echoes, so a name grep survives deleting the capture and the summary then
# prints `executed=0` beside PASS, the exact vacuous-count shape this issue
# exists to eliminate.
grep -qF "sed -n 's/^NOTIFICATION_PERMISSION_TEST_RESULT executed=" "$nightly_phase1b" \
  || fail "nightly phase 1b does not capture the executed count from the wrapper result line"
grep -qF 'notification_permission_executed="$(' "$nightly_phase1b" \
  || fail "nightly phase 1b executed count is not assigned from that capture"
grep -qF 'executed=$notification_permission_executed' "$nightly_joined" \
  || fail "nightly summary row no longer surfaces the phase 1b executed count"

if grep -qE 'numShards|shardIndex|JOURNEY_SHARD_ARGS' "$nightly_phase1b"; then
  fail "nightly one-test phase 1b incorrectly retained shard arguments"
fi

# Exit propagation: pin the ASSIGNMENT that carries the real wrapper status.
# Pinning only the downstream `-ne 0` comparisons leaves
# `NOTIFICATION_PERMISSION_EXIT=0` hardcodable, which silently stops a phase-1b
# failure reddening anything a human reads.
# shellcheck disable=SC2016
grep -q '^[[:space:]]*NOTIFICATION_PERMISSION_EXIT=\${PIPESTATUS\[0\]}$' \
  "$nightly_phase1b" \
  || fail "nightly phase 1b does not propagate the wrapper's real exit code"
# Issue #1991: the summary status now comes from the preserved-report
# classifier, so a strong UTP device-offline signature is surfaced as INFRA
# while every other non-zero phase stays FAIL. Pin both the real exit/report
# inputs and the classification-to-status mapping instead of the superseded
# direct `-ne 0` assignment.
# shellcheck disable=SC2016
grep -q 'classify_nightly_phase.*"\$NOTIFICATION_PERMISSION_EXIT"' \
  "$nightly_phase1b" \
  || fail "nightly phase 1b classification no longer consumes the real exit code"
# shellcheck disable=SC2016
grep -q '"\$PHASE_REPORTS_DIR/phase1b-notification-permission"' \
  "$nightly_phase1b" \
  || fail "nightly phase 1b classification no longer consumes its preserved report"
# shellcheck disable=SC2016
grep -q 'nightly_phase_status "\$notification_permission_classification"' \
  "$nightly_phase1b" \
  || fail "nightly phase 1b summary no longer maps its fail-safe classification"

# Human extensive-shard verdict, scoped to the overall_status conditional.
overall_status_block="$FIXTURE_ROOT/nightly-overall-status.txt"
sed -n '/^overall_status="PASS"$/,/^fi$/p' "$REAL_NIGHTLY" > "$overall_status_block"
grep -q '^overall_status="PASS"$' "$overall_status_block" \
  || fail "could not extract the nightly overall_status conditional"
grep -q '^fi$' "$overall_status_block" \
  || fail "nightly overall_status conditional is unterminated"
# shellcheck disable=SC2016
grep -q '"\$NOTIFICATION_PERMISSION_EXIT" -ne 0' "$overall_status_block" \
  || fail "nightly human extensive-shard status ignores phase 1b failure"

# Release fault-verdict split: the notification phase is NON-GATING, so it must
# stay out of the machine-readable verdict while the three real inputs stay in.
# Asserted against the single joined call so a failed extraction can never
# satisfy the negative half by being empty.
fault_call="$(grep -F 'write_fault_verdict_file ' "$nightly_joined" | head -1)"
[[ -n "$fault_call" ]] \
  || fail "could not extract the nightly release fault-verdict call"
# shellcheck disable=SC2016
assert_statement_contains "$fault_call" '"$nf_status" "$NETWORK_FAULT_EXIT"' \
  "nightly release fault-verdict no longer carries the network-fault phase"
# shellcheck disable=SC2016
assert_statement_contains "$fault_call" '"$bootstrap_status" "$BOOTSTRAP_EXIT"' \
  "nightly release fault-verdict no longer carries the bootstrap phase"
# shellcheck disable=SC2016
assert_statement_contains "$fault_call" '"$expectedfail_status" "$EXPECTED_FAIL_EXIT"' \
  "nightly release fault-verdict no longer carries the expected-fail lane"
case "$fault_call" in
  *NOTIFICATION_PERMISSION*|*notification_permission*)
    printf '%s\n' "$fault_call" >&2
    fail "notification phase leaked into the release fault-verdict"
    ;;
esac
pass "nightly bulk/order isolation, non-vacuous report/count, and verdict split are pinned"

# Issue #1662 follow-up: the generic XML validator runs before the dedicated
# notification validator. Its failure must not suppress the notification
# validator's actionable zero-test diagnostic. Mutate only that handoff in a
# private wrapper copy and run this guard again; the guard must go red at its
# diagnostic assertion rather than accepting the generic message as a substitute.
if [[ "${NOTIFICATION_FIXTURE_MUTATION_RUN:-0}" != "1" ]]; then
  echo "== Mutation: generic XML failure must not bypass notification diagnostics =="
  mutation_source="$FIXTURE_ROOT/connected-test-mutant.sh"
  cp "$REAL_CONNECTED" "$mutation_source"
  mutation_anchor=' || "$connected_test_report_rc" != "0"'
  mutation_text="$(<"$mutation_source")"
  [[ "$mutation_text" == *"$mutation_anchor"* ]] \
    || fail "notification handoff mutation anchor was not found exactly"
  mutation_text="${mutation_text/"$mutation_anchor"/}"
  printf '%s\n' "$mutation_text" > "$mutation_source"
  [[ "$mutation_text" != *"$mutation_anchor"* ]] \
    || fail "notification handoff mutation did not remove its anchor"

  mutation_log="$FIXTURE_ROOT/notification-mutant.log"
  set +e
  NOTIFICATION_FIXTURE_CONNECTED_SOURCE="$mutation_source" \
    NOTIFICATION_FIXTURE_MUTATION_RUN=1 \
    bash "$SCRIPT_DIR/test-notification-permission-fixture.sh" \
    > "$mutation_log" 2>&1
  mutation_rc=$?
  set -e
  (( mutation_rc != 0 )) \
    || fail "bypassing notification diagnostics left the full guard green"
  grep -q 'zero-test failure did not explain its non-vacuous verdict' \
    "$mutation_log" \
    || { cat "$mutation_log"; fail "notification diagnostic mutation failed for an unrelated reason"; }
  ! grep -q 'ALL NOTIFICATION PERMISSION FIXTURE TESTS PASSED' "$mutation_log" \
    || fail "notification diagnostic mutation reported a false all-green verdict"
  pass "bypassing notification diagnostics is live and hard-red (rc=$mutation_rc)"
fi

echo
echo "ALL NOTIFICATION PERMISSION FIXTURE TESTS PASSED"
