#!/usr/bin/env bash
# Live Android durability mutation proof for issue #2265.
#
# The control and mutant both use the host-owned two-phase process-restart
# harness. The mutant changes only LastSessionStore's acknowledged commit into
# apply(); true. The device proof drops apply() deterministically at its
# external-kill boundary, so phase 2 must fail when a new process reads the
# real preferences file.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HARNESS="$ROOT_DIR/scripts/two-phase-android-instrumentation.sh"
PROOF_SOURCE="$ROOT_DIR/app/src/androidTest/java/com/pocketshell/app/proof/LastSessionProcessRestartProofTest.kt"
WRITE_SOURCE_REL="app/src/main/java/com/pocketshell/app/session/LastSessionStore.kt"
WRITE_SOURCE="$ROOT_DIR/$WRITE_SOURCE_REL"
ANDROID_SDK="${ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/alexey/Android/Sdk}}}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
CACHE_BASE="${XDG_CACHE_HOME:-${HOME:?HOME is required}/.cache}"
RUN_ROOT="${RUN_ROOT:-$CACHE_BASE/pocketshell/evidence/android-process-restart/issue2265-durability-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
CONTROL_NAMESPACE="${CONTROL_NAMESPACE:-issue2265-control-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
MUTANT_NAMESPACE="${MUTANT_NAMESPACE:-issue2265-async-apply-mutant-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
CONTROL_SUFFIX="${CONTROL_SUFFIX:-i2265control}"
MUTANT_SUFFIX="${MUTANT_SUFFIX:-i2265asyncmutant}"
POOL_WAIT_SECONDS="${POCKETSHELL_POOL_WAIT_SECONDS:-600}"
MUTANT_ROOT=""

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  printf 'Evidence: %s\n' "$RUN_ROOT" >&2
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
Usage: scripts/two-phase-android-durability-mutation.sh

Runs a fresh current-source control journey, then a fresh APK built from a live
commit-to-apply mutation. The control must survive the external force-stop and
new-process read; the async-apply mutant must fail only at phase 2.

Environment:
  RUN_ROOT=<dir>                 new durable evidence directory
  CONTROL_NAMESPACE=<name>       control run namespace
  MUTANT_NAMESPACE=<name>        mutant run namespace
  CONTROL_SUFFIX=i2265control    control application-id suffix
  MUTANT_SUFFIX=i2265asyncmutant mutant application-id suffix
  POCKETSHELL_POOL_WAIT_SECONDS   emulator-lock wait (default 600)
USAGE
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

[[ -x "$ADB" ]] || fail "adb is not executable: $ADB"
[[ -x "$HARNESS" ]] || fail "two-phase harness is not executable: $HARNESS"
[[ -f "$PROOF_SOURCE" ]] || fail "proof source is absent: $PROOF_SOURCE"
[[ -f "$WRITE_SOURCE" ]] || fail "durability source is absent: $WRITE_SOURCE"
[[ "$POOL_WAIT_SECONDS" =~ ^[0-9]+$ ]] || fail "POCKETSHELL_POOL_WAIT_SECONDS must be numeric"
[[ ! -e "$RUN_ROOT" ]] || fail "RUN_ROOT already exists; refusing stale/mixed evidence: $RUN_ROOT"
mkdir -p "$RUN_ROOT"
chmod 700 "$RUN_ROOT"

WRITE_ANCHOR='            prefs.edit().also(edit).commit()'
MUTANT_WRITE='            prefs.edit().also(edit).apply().let { true }'
[[ "$(grep -Fc "$WRITE_ANCHOR" "$WRITE_SOURCE")" == "1" ]] \
  || fail "durability commit mutation anchor is not unique"

PHASE_ONE_SOURCE="$(sed -n \
  '/fun phaseOnePersistsExactSuccessorGeneration()/,/^    @Test$/p' \
  "$PROOF_SOURCE")"
if grep -Eq '\.read\(' <<<"$PHASE_ONE_SOURCE"; then
  fail "phase 1 contains a same-process LastSessionStore read oracle"
fi

{
  printf 'mutation_target=%s\n' "$WRITE_SOURCE_REL"
  printf 'mutation_anchor=%s\n' "$WRITE_ANCHOR"
  printf 'mutant_replacement=%s\n' "$MUTANT_WRITE"
  printf 'control_source_sha256=%s\n' "$(sha256sum "$WRITE_SOURCE" | awk '{print $1}')"
  printf 'control_suffix=%s\ncontrol_namespace=%s\n' "$CONTROL_SUFFIX" "$CONTROL_NAMESPACE"
  printf 'phase_one_external_boundary=true\nphase_two_oracle=new_process_LastSessionStore.read\n'
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

# Control: the real commit path must survive the host force-stop and the
# phase-2 read must run in the second target process.
run_harness "$ROOT_DIR" "$CONTROL_SUFFIX" "$CONTROL_NAMESPACE" \
  "$RUN_ROOT/control" "$RUN_ROOT/control-run.log"
grep -Fqx 'result=PASS' "$RUN_ROOT/control/summary.txt" \
  || fail "current-source control journey did not pass"
grep -Fqx 'persistence_origin=LastSessionStore.read' "$RUN_ROOT/control/phase-2.txt" \
  || fail "control phase 2 did not publish a production read artifact"
CONTROL_PHASE1_PID="$(sed -n 's/^phase1_pid=//p' "$RUN_ROOT/control/summary.txt")"
CONTROL_PHASE2_STARTED_PID="$(sed -n 's/^phase2_started_pid=//p' "$RUN_ROOT/control/summary.txt")"
CONTROL_PHASE2_PID="$(sed -n 's/^phase2_pid=//p' "$RUN_ROOT/control/summary.txt")"
[[ -n "$CONTROL_PHASE1_PID" && -n "$CONTROL_PHASE2_STARTED_PID" && -n "$CONTROL_PHASE2_PID" ]] \
  || fail "control summary lacks phase PID evidence"
[[ "$CONTROL_PHASE1_PID" != "$CONTROL_PHASE2_STARTED_PID" ]] \
  || fail "control phase 2 did not enter a new target process"
[[ "$CONTROL_PHASE2_STARTED_PID" == "$CONTROL_PHASE2_PID" ]] \
  || fail "control phase-2 start marker disagrees with its production artifact"
[[ "$(grep -c 'event=external_force_stop_complete' "$RUN_ROOT/control/package-mutations.log")" == "1" ]] \
  || fail "control lacks exactly one completed external force-stop boundary"

# Make a private, non-symlinked source copy for the live APK mutation.
MUTANT_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-issue2265-mutant.XXXXXX")"
if command -v rsync >/dev/null 2>&1; then
  rsync -a \
    --exclude='.git/' \
    --exclude='.gradle/' \
    --exclude='build/' \
    --exclude='*/build/' \
    "$ROOT_DIR/" "$MUTANT_ROOT/"
else
  cp -a "$ROOT_DIR/." "$MUTANT_ROOT/"
  rm -rf "$MUTANT_ROOT/build" "$MUTANT_ROOT/.gradle" "$MUTANT_ROOT/.git"
fi

MUTANT_SOURCE="$MUTANT_ROOT/$WRITE_SOURCE_REL"
[[ -f "$MUTANT_SOURCE" ]] || fail "mutant source copy is absent: $MUTANT_SOURCE"
[[ "$(grep -Fc "$WRITE_ANCHOR" "$MUTANT_SOURCE")" == "1" ]] \
  || fail "mutant source lost the durability mutation anchor"

WRITE_ANCHOR="$WRITE_ANCHOR" MUTANT_WRITE="$MUTANT_WRITE" \
  perl -0pi -e 's/\Q$ENV{WRITE_ANCHOR}\E/$ENV{MUTANT_WRITE}/' "$MUTANT_SOURCE"
[[ "$(grep -Fc "$WRITE_ANCHOR" "$MUTANT_SOURCE")" == "0" ]] \
  || fail "mutant source still contains the synchronous commit"
[[ "$(grep -Fc "$MUTANT_WRITE" "$MUTANT_SOURCE")" == "1" ]] \
  || fail "mutant source did not contain exactly one async-apply replacement"
[[ "$(sha256sum "$WRITE_SOURCE" | awk '{print $1}')" != \
   "$(sha256sum "$MUTANT_SOURCE" | awk '{print $1}')" ]] \
  || fail "durability mutation did not change the production source"

cp "$MUTANT_SOURCE" "$RUN_ROOT/mutant-LastSessionStore.kt"
{
  printf 'mutant_source_sha256=%s\n' "$(sha256sum "$MUTANT_SOURCE" | awk '{print $1}')"
  printf 'mutant_suffix=%s\nmutant_namespace=%s\n' "$MUTANT_SUFFIX" "$MUTANT_NAMESPACE"
  printf 'mutant_write=apply_then_true\nexpected_control=PASS\nexpected_mutant=RED\n'
} >> "$RUN_ROOT/mutation-manifest.txt"

# Mutant: phase 1 must still complete and the host must still prove the real
# external boundary. Only the new-process persistence assertion may turn red.
set +e
run_harness "$MUTANT_ROOT" "$MUTANT_SUFFIX" "$MUTANT_NAMESPACE" \
  "$RUN_ROOT/mutant" "$RUN_ROOT/mutant-run.log"
MUTANT_RC=$?
set -e
printf '%s\n' "$MUTANT_RC" > "$RUN_ROOT/mutant-exit-code.txt"
[[ "$MUTANT_RC" -ne 0 ]] || fail "live async-apply mutant stayed green"
[[ -s "$RUN_ROOT/mutant/phase-1.txt" ]] \
  || fail "async-apply mutant failed before phase 1 artifact publication"
[[ -s "$RUN_ROOT/mutant/force-stop-evidence.txt" ]] \
  || fail "async-apply mutant failed before external force-stop evidence"
[[ -s "$RUN_ROOT/mutant/phase-2-started.txt" ]] \
  || fail "async-apply mutant did not enter phase 2"
MUTANT_PHASE1_PID="$(sed -n 's/^pid=//p' "$RUN_ROOT/mutant/phase-1.txt")"
MUTANT_PHASE2_STARTED_PID="$(sed -n 's/^pid=//p' "$RUN_ROOT/mutant/phase-2-started.txt")"
[[ "$MUTANT_PHASE1_PID" != "$MUTANT_PHASE2_STARTED_PID" ]] \
  || fail "async-apply mutant phase 2 reused phase-1 PID"
grep -Fq 'production LastSessionStore must survive external process death' \
  "$RUN_ROOT/mutant/phase-2-instrumentation.log" \
  || fail "async-apply mutant did not fail at the phase-2 persistence assertion"
grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Tests run: 1,  Failures: 1' \
  "$RUN_ROOT/mutant/phase-2-instrumentation.log" \
  || fail "async-apply mutant phase 2 lacks a one-test failure result"
[[ ! -e "$RUN_ROOT/mutant/summary.txt" ]] \
  || fail "red async-apply mutant incorrectly published a passing summary"

{
  printf 'result=PASS\ncontrol_result=PASS\nmutant_result=RED\n'
  printf 'mutant_exit_code=%s\n' "$MUTANT_RC"
  printf 'mutation_target=%s\n' "$WRITE_SOURCE_REL"
  printf 'mutant_apk_built=true\nmutant_apk_executed=true\n'
  printf 'phase_one_external_boundary=true\nphase_two_oracle=new_process_LastSessionStore.read\n'
  printf 'control_phase1_pid=%s\ncontrol_phase2_started_pid=%s\ncontrol_phase2_pid=%s\n' \
    "$CONTROL_PHASE1_PID" "$CONTROL_PHASE2_STARTED_PID" "$CONTROL_PHASE2_PID"
  printf 'mutant_phase1_pid=%s\nmutant_phase2_started_pid=%s\n' \
    "$MUTANT_PHASE1_PID" "$MUTANT_PHASE2_STARTED_PID"
} > "$RUN_ROOT/summary.txt"
find "$RUN_ROOT" -type f ! -name SHA256SUMS -print0 \
  | sort -z | xargs -0 sha256sum > "$RUN_ROOT/SHA256SUMS"

printf 'Two-phase Android async-apply durability mutation proof passed.\n'
printf 'Evidence: %s\n' "$RUN_ROOT"
