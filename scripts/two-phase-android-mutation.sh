#!/usr/bin/env bash
# Real APK mutation proof for the host-owned process-restart journey (#2264).
#
# The control run builds and executes the current source through the real
# two-phase harness. The mutant run copies that same source tree, changes the
# production LastSessionStore.read implementation, builds a different APK
# pair, and runs the identical journey. A green control plus a phase-2-only
# red mutant is the load-bearing selective mutation oracle.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HARNESS="$ROOT_DIR/scripts/two-phase-android-instrumentation.sh"
READ_SOURCE_REL="app/src/main/java/com/pocketshell/app/session/LastSessionStore.kt"
READ_SOURCE="$ROOT_DIR/$READ_SOURCE_REL"
ANDROID_SDK="${ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/alexey/Android/Sdk}}}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
CACHE_BASE="${XDG_CACHE_HOME:-${HOME:?HOME is required}/.cache}"
RUN_ROOT="${RUN_ROOT:-$CACHE_BASE/pocketshell/evidence/android-process-restart/issue2264-mutation-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
CONTROL_NAMESPACE="${CONTROL_NAMESPACE:-issue2264-control-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
MUTANT_NAMESPACE="${MUTANT_NAMESPACE:-issue2264-mutant-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
CONTROL_SUFFIX="${CONTROL_SUFFIX:-i2264control}"
MUTANT_SUFFIX="${MUTANT_SUFFIX:-i2264mutant}"
POOL_WAIT_SECONDS="${POCKETSHELL_POOL_WAIT_SECONDS:-600}"
MUTANT_ROOT=""

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

cleanup() {
  if [[ -n "$MUTANT_ROOT" && -d "$MUTANT_ROOT" ]]; then
    rm -rf "$MUTANT_ROOT"
  fi
}
trap cleanup EXIT

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/two-phase-android-mutation.sh

Runs a fresh current-source control journey, then a fresh mutant journey. Both
build and install their own suffixed target/test APK pair. The mutant changes
the production LastSessionStore.read implementation in a temporary copy and
must fail only when phase 2 reads the persisted generation.

Environment:
  RUN_ROOT=<dir>                 new durable evidence directory
  CONTROL_NAMESPACE=<name>       control run namespace
  MUTANT_NAMESPACE=<name>        mutant run namespace
  CONTROL_SUFFIX=i2264control    control application-id suffix
  MUTANT_SUFFIX=i2264mutant      mutant application-id suffix
  POCKETSHELL_POOL_WAIT_SECONDS   emulator-lock wait (default 600)
USAGE
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

[[ -x "$ADB" ]] || fail "adb is not executable: $ADB"
[[ -x "$HARNESS" ]] || fail "two-phase harness is not executable: $HARNESS"
[[ -f "$READ_SOURCE" ]] || fail "production read source is absent: $READ_SOURCE"
[[ "$POOL_WAIT_SECONDS" =~ ^[0-9]+$ ]] || fail "POCKETSHELL_POOL_WAIT_SECONDS must be numeric"
[[ ! -e "$RUN_ROOT" ]] || fail "RUN_ROOT already exists; refusing stale/mixed mutation evidence: $RUN_ROOT"
mkdir -p "$RUN_ROOT"
chmod 700 "$RUN_ROOT"

READ_ANCHOR='        parse(nowMillis, maxAgeMillis)?.also { session ->'
# The phase-one proof reads immediately after LastSessionStore.save, while the
# phase-two proof reads after the target process has been force-stopped and
# recreated. The mutant records the first read's real PID in the same prefs
# file, preserves the real parse for that first read, and returns a deliberately
# wrong record only when a later process reads the same production store. This
# keeps the mutant's phase-one save/read boundary green and makes the actual
# phase-two LastSessionStore.read oracle red.
MUTANT_READ="$(printf '%s\n' \
  '        (if (' \
  '            prefs.getLong("__issue2264_mutant_read_pid", -1L).let { previousPid ->' \
  '                prefs.edit()' \
  '                    .putLong("__issue2264_mutant_read_pid", android.os.Process.myPid().toLong())' \
  '                    .commit()' \
  '                previousPid > 0L && previousPid != android.os.Process.myPid().toLong()' \
  '            }' \
  '        ) {' \
  '            LastSession(' \
  '                hostId = 2264L,' \
  '                hostName = "fixed-read-mutant",' \
  '                hostname = "fixed-read-mutant.invalid",' \
  '                port = 2239,' \
  '                username = "mutant",' \
  '                keyPath = "/fixed-read-mutant",' \
  '                sessionName = "fixed-read-mutant",' \
  '                startDirectory = null,' \
  '                tmuxSessionId = "fixed-read-mutant",' \
  '                sessionCreated = 1900002264L,' \
  '                composerDraft = "fixed-read-mutant",' \
  '                savedAtMillis = 1L,' \
  '            )' \
  '        } else {' \
  '            parse(nowMillis, maxAgeMillis)' \
  '        })?.also { session ->')"

[[ "$(grep -Fc "$READ_ANCHOR" "$READ_SOURCE")" == "1" ]] \
  || fail "production LastSessionStore.read mutation anchor is not unique"

{
  printf 'mutation_target=%s\n' "$READ_SOURCE_REL"
  printf 'mutation_anchor=%s\n' "$READ_ANCHOR"
  printf 'control_source_sha256=%s\n' "$(sha256sum "$READ_SOURCE" | awk '{print $1}')"
  printf 'control_suffix=%s\ncontrol_namespace=%s\n' "$CONTROL_SUFFIX" "$CONTROL_NAMESPACE"
} > "$RUN_ROOT/mutation-manifest.txt"

run_harness() {
  local root="$1" suffix="$2" namespace="$3" run_dir="$4" log_file="$5"
  shift 5
  env \
    ADB="$ADB" \
    POCKETSHELL_POOL_WAIT_SECONDS="$POOL_WAIT_SECONDS" \
    BUILD_APKS=1 \
    SUFFIX="$suffix" \
    RUN_NAMESPACE="$namespace" \
    RUN_DIR="$run_dir" \
    "$@" \
    bash "$root/scripts/two-phase-android-instrumentation.sh" \
    > "$log_file" 2>&1
}

# Selective control: current source must complete the exact real journey
# before any source mutation is introduced.
run_harness "$ROOT_DIR" "$CONTROL_SUFFIX" "$CONTROL_NAMESPACE" \
  "$RUN_ROOT/control" "$RUN_ROOT/control-run.log"
grep -Fqx 'result=PASS' "$RUN_ROOT/control/summary.txt" \
  || fail "current-source control journey did not pass"
grep -Fqx 'persistence_origin=LastSessionStore.read' "$RUN_ROOT/control/phase-2.txt" \
  || fail "control phase 2 did not publish a LastSessionStore.read artifact"

MUTANT_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-issue2264-mutant.XXXXXX")"
if command -v rsync >/dev/null 2>&1; then
  rsync -a \
    --exclude='.git/' \
    --exclude='.gradle/' \
    --exclude='build/' \
    --exclude='*/build/' \
    "$ROOT_DIR/" "$MUTANT_ROOT/"
else
  cp -a "$ROOT_DIR/." "$MUTANT_ROOT/"
  find "$MUTANT_ROOT" -type d -name build -prune -exec rm -rf {} +
  rm -rf "$MUTANT_ROOT/.git" "$MUTANT_ROOT/.gradle"
fi

MUTANT_SOURCE="$MUTANT_ROOT/$READ_SOURCE_REL"
[[ -f "$MUTANT_SOURCE" ]] || fail "mutant source copy is absent: $MUTANT_SOURCE"
[[ "$(grep -Fc "$READ_ANCHOR" "$MUTANT_SOURCE")" == "1" ]] \
  || fail "mutant source lost the production read anchor before mutation"

READ_ANCHOR="$READ_ANCHOR" MUTANT_READ="$MUTANT_READ" \
  perl -0pi -e 's/\Q$ENV{READ_ANCHOR}\E/$ENV{MUTANT_READ}/' "$MUTANT_SOURCE"
[[ "$(grep -Fc "$READ_ANCHOR" "$MUTANT_SOURCE")" == "0" ]] \
  || fail "mutant source still contains the real read implementation"
grep -Fq 'fixed-read-mutant' "$MUTANT_SOURCE" \
  || fail "mutant source did not replace production LastSessionStore.read"

cp "$MUTANT_SOURCE" "$RUN_ROOT/mutant-LastSessionStore.kt"
{
  printf 'mutant_source_sha256=%s\n' "$(sha256sum "$MUTANT_SOURCE" | awk '{print $1}')"
  printf 'mutant_suffix=%s\nmutant_namespace=%s\n' "$MUTANT_SUFFIX" "$MUTANT_NAMESPACE"
  printf 'mutant_read_value=fixed-read-mutant\nexpected_control=PASS\nexpected_mutant=RED\n'
} >> "$RUN_ROOT/mutation-manifest.txt"

# The mutant uses the unchanged proof test and the unchanged host harness. Its
# only source change is inside the production LastSessionStore.read body.
set +e
run_harness "$MUTANT_ROOT" "$MUTANT_SUFFIX" "$MUTANT_NAMESPACE" \
  "$RUN_ROOT/mutant" "$RUN_ROOT/mutant-run.log"
MUTANT_RC=$?
set -e
printf '%s\n' "$MUTANT_RC" > "$RUN_ROOT/mutant-exit-code.txt"
[[ "$MUTANT_RC" -ne 0 ]] || fail "production LastSessionStore.read mutant stayed green"

# Selectivity: phase 1 and the external process-death boundary must have
# completed. Only the phase-2 production read/oracle is allowed to turn red.
[[ -s "$RUN_ROOT/mutant/phase-1.txt" ]] \
  || fail "mutant failed before phase 1 artifact publication"
[[ -s "$RUN_ROOT/mutant/force-stop-evidence.txt" ]] \
  || fail "mutant failed before external force-stop evidence"
grep -Fqx 'persistence_origin=LastSessionStore.save' "$RUN_ROOT/mutant/phase-1.txt" \
  || fail "mutant phase 1 did not execute production LastSessionStore.save"
grep -Fq 'BUILD SUCCESSFUL' "$RUN_ROOT/mutant/build.log" \
  || fail "mutant APK build did not complete successfully"
grep -Fq 'event=build_complete' "$RUN_ROOT/mutant/package-mutations.log" \
  || fail "mutant build completion was not recorded"
grep -Fq 'fixed-read-mutant' "$RUN_ROOT/mutant/phase-2-instrumentation.log" \
  || fail "mutant phase 2 log does not show the mutated production read value"
grep -Fq 'ComparisonFailure' "$RUN_ROOT/mutant/phase-2-instrumentation.log" \
  || fail "mutant phase 2 log lacks the expected production-read assertion failure"
grep -Fq 'Tests run: 1,  Failures: 1' "$RUN_ROOT/mutant/phase-2-instrumentation.log" \
  || fail "mutant phase 2 did not execute exactly one failing proof test"
grep -Fq 'phase 2 did not report exactly one passing test' "$RUN_ROOT/mutant-run.log" \
  || fail "mutant did not fail at the phase-2 instrumentation boundary"
[[ ! -e "$RUN_ROOT/mutant/summary.txt" ]] \
  || fail "red mutant incorrectly published a passing summary"

{
  printf 'result=PASS\ncontrol_result=PASS\nmutant_result=RED\n'
  printf 'mutant_exit_code=%s\n' "$MUTANT_RC"
  printf 'mutation_target=%s\n' "$READ_SOURCE_REL"
  printf 'mutant_apk_built=true\nmutant_apk_executed=true\n'
  printf 'phase_one_controlled=true\nphase_two_oracle_selective=true\n'
} > "$RUN_ROOT/summary.txt"
find "$RUN_ROOT" -maxdepth 1 -type f ! -name SHA256SUMS -print0 \
  | sort -z | xargs -0 sha256sum > "$RUN_ROOT/SHA256SUMS"

printf 'Two-phase Android production-read mutation proof passed.\n'
printf 'Evidence: %s\n' "$RUN_ROOT"
