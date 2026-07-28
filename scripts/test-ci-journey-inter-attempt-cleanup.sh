#!/usr/bin/env bash
# Issue #1840: deterministic, JVM-free, emulator-free proof that the journey
# suite's post-timeout cleanup leaves the RETRY a build tree it can actually
# build in — and that a retry which dies at the BUILD level is distinguishable
# from a class that genuinely failed twice.
#
# THE REPRODUCTION (why this fixture is shaped this way)
# -----------------------------------------------------
# Run 30339688411, shard 0, `com.pocketshell.app.proof.TmuxSessionScreenArtVerifyE2eTest`:
#   13:10:41  attempt 1 was cut by the per-class wall cap while Gradle was still
#             BUILDING;
#   13:10:43  the suite ran `gradlew --stop`; Gradle answered
#             `Stopping Daemon(s)` / `1 Daemon stopped` and the suite logged
#             `GRADLE_TIMEOUT_CLEANUP: ... stop completed`;
#   13:11:30  the RETRY's `:app:compileDebugAndroidTestKotlin` FAILED:
#               Unable to delete directory
#                 '.../app/build/tmp/kotlin-classes/debugAndroidTest'
#               Failed to delete some children. ...
#               New files were found. This might happen because a process is
#               still writing to the target directory.
#             naming freshly-written `com/pocketshell/app/tmux/*.class` files.
# Forty-seven seconds after the daemon "stopped", something was still writing —
# `--stop` reaches the GRADLE daemon and not the Kotlin compile daemon it
# spawned. The retry never reached instrumentation, both attempts produced
# unusable summaries, and the shard went RED with no product defect in it.
#
# So the gradle stub below models exactly that shape:
#   attempt 1  spawns a DETACHED process that holds an open descriptor under
#              `app/build/tmp/kotlin-classes/debugAndroidTest` and keeps
#              (re)creating class files there, then hangs until the cap fires;
#   `--stop`   stops "the daemon" and deliberately does NOT touch that process,
#              exactly like the real `gradlew --stop`;
#   attempt 2  does what Gradle's `compileDebugAndroidTestKotlin` does — clear
#              its output directory and then verify nothing reappeared — and
#              emits the real Gradle failure text when it did.
#
# Against a MUTANT of the real suite with only the orphan reap removed, the
# retry's build dies and the class is reported as having failed twice (RED, the
# reported defect, observed). Against the real suite the retry's build RUNS, the
# test executes, and the class recovers (GREEN). Everything drives the REAL
# scripts — the real suite, its real helpers, the real build-phase scanner — with
# stubbed gradle/adb. No logic is re-implemented.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
REAL_SUITE="$SCRIPT_DIR/ci-journey-suite.sh"
BUDGET_HELPER="$SCRIPT_DIR/ci-journey-budget-functions.sh"
WARM_HELPER="$SCRIPT_DIR/ci-journey-warm-build-functions.sh"
CLASS_LOOP="$SCRIPT_DIR/ci-journey-class-loop-functions.sh"
BUILD_PHASE_FAILURE="$SCRIPT_DIR/ci-journey-build-phase-failure.sh"
BUILD_PHASE_TIMEOUT="$SCRIPT_DIR/ci-journey-build-phase-timeout.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

for required in "$REAL_SUITE" "$BUDGET_HELPER" "$WARM_HELPER" "$CLASS_LOOP" \
  "$BUILD_PHASE_FAILURE" "$BUILD_PHASE_TIMEOUT"; do
  [[ -f "$required" ]] || fail "missing required file: $required"
done

SANDBOX="$(mktemp -d)"
# The fixture deliberately spawns a detached orphan writer. Nothing may outlive
# this harness, so every recorded orphan pid is hard-killed on the way out —
# whatever the verdict, and however the run ends.
reap_orphans() {
  local pid_file pid
  while IFS= read -r pid_file; do
    pid="$(cat "$pid_file" 2>/dev/null || true)"
    [[ "$pid" =~ ^[0-9]+$ ]] || continue
    kill -KILL "$pid" 2>/dev/null || true
  done < <(find "$SANDBOX" -type f -name 'orphan-pid*' 2>/dev/null)
}
trap 'reap_orphans; rm -rf "$SANDBOX"' EXIT

HELPERS=(
  ci-journey-suite.sh
  ci-journey-class-selection-functions.sh
  ci-journey-budget-functions.sh
  ci-journey-warm-build-functions.sh
  ci-journey-class-loop-functions.sh
  ci-journey-core-terminal-functions.sh
  ci-journey-summary-functions.sh
)

# The class this fixture wedges: the first entry of the real allowlist, read from
# the real file so a reordering of JOURNEY_CLASSES cannot silently make this test
# exercise a different class.
first_entry="$(awk '
  /^JOURNEY_CLASSES=\(/ { f=1; next }
  /^\)/ { f=0 }
  f && /^[[:space:]]*"/ { print; exit }
' "$REAL_SUITE" | sed 's/^[[:space:]]*"\([^"]*\)".*/\1/')"
WEDGE_CLASS="${first_entry##*.}"
[[ -n "$WEDGE_CLASS" ]] || fail "(setup) could not resolve the first journey class from the allowlist"
FQCN_PREFIX="$(sed -n 's/^FQCN_PREFIX="\([^"]*\)"$/\1/p' "$REAL_SUITE" | head -n 1)"
[[ -n "$FQCN_PREFIX" ]] || fail "(setup) could not resolve FQCN_PREFIX from the suite"
WEDGE_FQCN="${first_entry/\$FQCN_PREFIX/$FQCN_PREFIX}"
command -v setsid >/dev/null 2>&1 \
  || fail "(setup) setsid is required: the fixture must detach its orphan writer into its OWN session, because GNU timeout signals the whole process GROUP and the real Gradle/Kotlin daemons are session-detached"
echo "   wedging the first class of shard 0: $WEDGE_FQCN"

STUBBIN="$SANDBOX/stubbin"
mkdir -p "$STUBBIN"
cat > "$STUBBIN/adb" <<'STUB'
#!/usr/bin/env bash
set -u
emit_valid_png() {
  printf '\211\120\116\107\015\012\032\012\000\000\000\015\111\110\104\122\000\000\000\001\000\000\000\001\010\006\000\000\000\037\025\304\211\000\000\000\015\111\104\101\124\170\234\143\140\140\140\370\017\000\001\004\001\000\137\345\303\113\000\000\000\000\111\105\116\104\256\102\140\202'
}
if [[ "${1:-}" == "devices" ]]; then
  printf 'List of devices attached\nemulator-5554\tdevice\n'
  exit 0
fi
if [[ "${1:-}" == "-s" ]]; then shift 2; fi
case "${1:-}" in
  logcat)  [[ "${2:-}" == "-c" ]] || printf 'stub-logcat\n' ;;
  exec-out) emit_valid_png ;;
  shell)
    case "${2:-}" in
      ps) printf 'PID NAME\n' ;;
      dumpsys) printf 'stub dumpsys\n' ;;
    esac
    ;;
esac
exit 0
STUB
chmod +x "$STUBBIN/adb"

# make_repo <dir> — a sandbox repo root holding the REAL suite + helpers.
make_repo() {
  local root="$1"
  rm -rf "$root"
  mkdir -p "$root/scripts"
  local helper
  for helper in "${HELPERS[@]}"; do
    cp "$SCRIPT_DIR/$helper" "$root/scripts/$helper"
  done
  chmod +x "$root/scripts/ci-journey-suite.sh"

  cat > "$root/gradlew" <<'STUB'
#!/usr/bin/env bash
set -u
state="${ORPHAN_STUB_DIR:?}"
mkdir -p "$state"
printf '%s\n' "$*" >> "$state/args.log"

kotlin_out="$PWD/app/build/tmp/kotlin-classes/debugAndroidTest/com/pocketshell/app"
results="$PWD/app/build/outputs/androidTest-results/connected/debug"

if [[ "${1:-}" == "--stop" ]]; then
  printf 'stop\n' >> "$state/stop.log"
  # THE POINT OF THE FIXTURE: the real `gradlew --stop` stops the GRADLE daemon
  # and nothing else. The orphan spawned below survives it, exactly as the real
  # Kotlin compile daemon did on run 30339688411.
  echo "Stopping Daemon(s)"
  echo "1 Daemon stopped"
  exit 0
fi

if [[ "$*" != *connectedDebugAndroidTest* ]]; then
  # Warm build and anything else: cheap and clean.
  echo "> Task :app:assembleDebug"
  exit 0
fi

echo "> Task :app:preBuild"

wedge="${ORPHAN_STUB_WEDGE_CLASS:-}"
if [[ -n "$wedge" && "$*" == *"$wedge"* ]]; then
  count="$(cat "$state/wedge-count" 2>/dev/null || printf '0')"
  count=$((count + 1))
  printf '%s' "$count" > "$state/wedge-count"

  if [[ "$count" -eq 1 ]]; then
    echo "> Task :app:kspDebugAndroidTestKotlin"
    mkdir -p "$kotlin_out"
    # The orphan: holds an open descriptor under the module build root AND keeps
    # (re)creating class files there — what a Kotlin compile daemon does when the
    # Gradle daemon that handed it the module is killed mid-compile. Detached
    # from this process's stdio so it cannot hold the suite's output pipe open.
    # setsid: its OWN session, like the real Gradle/Kotlin daemons. GNU `timeout`
    # signals its whole process GROUP, so a same-group child would be killed with
    # the build and the fixture would silently prove nothing. The child records
    # its own pid so the harness can hard-kill it however setsid forked.
    setsid bash -c '
      printf "%s" "$$" > "$2"
      out="$1"
      mkdir -p "$out"
      exec 9>>"$out/.orphan-compile.lock"
      i=0
      end=$((SECONDS + 90))
      while (( SECONDS < end )); do
        i=$((i + 1))
        mkdir -p "$out"
        printf "orphan" > "$out/Orphan$i.class"
        sleep 0.05
      done
    ' _ "$kotlin_out" "$state/orphan-pid" >/dev/null 2>&1 </dev/null &
    # Wait for the orphan to actually exist before hanging, so the reproduction
    # is decided by the cleanup and never by a startup race.
    for _ in $(seq 1 100); do
      [[ -s "$state/orphan-pid" ]] && break
      sleep 0.05
    done
    echo "> Task :app:compileDebugAndroidTestKotlin"
    # Cut by the suite's per-class bound; the orphan keeps running.
    sleep 120
    exit 0
  fi

  # Every later attempt models what `compileDebugAndroidTestKotlin` really does:
  # clear its output directory, then check nothing reappeared.
  echo "> Task :app:kspDebugAndroidTestKotlin"
  target="$PWD/app/build/tmp/kotlin-classes/debugAndroidTest"
  rm -rf "$target" 2>/dev/null || true
  sleep 0.5
  if [[ -d "$target" ]]; then
    echo "> Task :app:compileDebugAndroidTestKotlin FAILED"
    echo "FAILURE: Build failed with an exception." >&2
    echo "* What went wrong:" >&2
    echo "Execution failed for task ':app:compileDebugAndroidTestKotlin'." >&2
    echo "> Unable to delete directory '$target'" >&2
    echo "    Failed to delete some children. This might happen because a process has files open or has its working directory set in the target directory." >&2
    echo "    New files were found. This might happen because a process is still writing to the target directory." >&2
    exit 1
  fi
  echo "> Task :app:compileDebugAndroidTestKotlin"
fi

echo "> Task :app:connectedDebugAndroidTest"
echo "Starting 1 tests on emulator-5554"
mkdir -p "$results"
printf '<testsuite tests="1" failures="0"><testcase name="t"/></testsuite>\n' \
  > "$results/TEST-stub.xml"
echo "Finished 1 tests on emulator-5554"
exit 0
STUB
  chmod +x "$root/gradlew"

  cat > "$root/scripts/connected-test.sh" <<'STUB'
#!/usr/bin/env bash
root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "$root_dir/gradlew" :app:connectedDebugAndroidTest "$@"
STUB
  chmod +x "$root/scripts/connected-test.sh"
}

REAL_REPO="$SANDBOX/real"
MUTANT_REPO="$SANDBOX/mutant"
make_repo "$REAL_REPO"
make_repo "$MUTANT_REPO"

# THE MUTANT: the real suite with ONLY the orphan reap removed from the shared
# cleanup. Everything else — budgets, caps, retry, artifacts, the daemon stop —
# is byte-identical, so any measured difference is attributable to that one call.
REAP_CALL='cleanup_build_output_writers_after_abort "$fqcn"'
grep -Fq "$REAP_CALL" "$REAL_REPO/scripts/ci-journey-budget-functions.sh" \
  || fail "(setup) the real cleanup no longer reaps orphaned build writers — the fixture would prove nothing"
python3 - "$MUTANT_REPO/scripts/ci-journey-budget-functions.sh" <<'PY'
import sys
path = sys.argv[1]
text = open(path, encoding="utf-8").read()
needle = '  cleanup_build_output_writers_after_abort "$fqcn"\n  writers_rc=$?\n'
if needle not in text:
    sys.exit("mutant anchor not found — the red control would not be live")
open(path, "w", encoding="utf-8").write(text.replace(needle, "  writers_rc=0\n"))
PY
if grep -Fq "$REAP_CALL" "$MUTANT_REPO/scripts/ci-journey-budget-functions.sh"; then
  fail "(setup) the mutant still reaps orphaned build writers — the red control is not live"
fi
pass "(setup) mutant differs from the real suite by exactly the orphan-reap call"

# run_suite <repo> <log> <state-dir> [env...]
RUN_RC=0
run_suite() {
  local repo="$1" log="$2" state="$3"; shift 3
  rm -rf "$state"
  mkdir -p "$state"
  env PATH="$STUBBIN:$PATH" \
    ORPHAN_STUB_DIR="$state" \
    ORPHAN_STUB_WEDGE_CLASS="$WEDGE_CLASS" \
    JOURNEY_STEP_BUDGET_SECS=900 \
    JOURNEY_CLASS_TIMEOUT_SECS=8 \
    JOURNEY_NO_OUTPUT_TIMEOUT_SECS=4 \
    JOURNEY_CLASS_KILL_AFTER_SECS=1 \
    JOURNEY_GRADLE_STOP_TIMEOUT_SECS=5 \
    POCKETSHELL_JOURNEY_CI_SHARD_TOTAL=3 \
    POCKETSHELL_JOURNEY_CI_SHARD_INDEX=0 \
    "$@" \
    bash "$repo/scripts/ci-journey-suite.sh" > "$log" 2>&1
  RUN_RC=$?
}

# wedge_attempt_log <repo> <attempt> — the suite's own streamed log for one
# attempt of the wedged class.
wedge_attempt_log() {
  local repo="$1" attempt="$2"
  find "$repo/artifacts/ci-journey/class-attempts" -type d -name "attempt-$attempt" \
    -path "*$WEDGE_CLASS*" -exec printf '%s/attempt.log\n' {} \; 2>/dev/null | head -n 1
}

echo
echo "== AC1 RED CONTROL: without the reap, the retry's BUILD dies and never runs the test =="

mutant_log="$SANDBOX/mutant.log"
run_suite "$MUTANT_REPO" "$mutant_log" "$SANDBOX/state-mutant"
mutant_rc="$RUN_RC"

grep -q 'GRADLE_TIMEOUT_CLEANUP: .* stop completed' "$mutant_log" \
  || { sed -n '1,80p' "$mutant_log"; fail "(red) the daemon stop did not report success — the fixture is not reproducing the reported shape"; }
mutant_retry_log="$(wedge_attempt_log "$MUTANT_REPO" 2)"
[[ -n "$mutant_retry_log" && -f "$mutant_retry_log" ]] \
  || fail "(red) no attempt-2 log was captured for $WEDGE_CLASS"
grep -q 'Unable to delete directory' "$mutant_retry_log" \
  || { cat "$mutant_retry_log"; fail "(red) the retry's build did NOT die on the orphaned writer — the reported defect was not reproduced"; }
if grep -q 'Starting 1 tests on' "$mutant_retry_log"; then
  cat "$mutant_retry_log"
  fail "(red) the retry reached instrumentation despite the build failure — the fixture is wrong"
fi
grep -q "JOURNEY_FAILED: .*$WEDGE_CLASS failed twice" "$mutant_log" \
  || { sed -n '1,120p' "$mutant_log"; fail "(red) the mutant did not report the class as having failed twice"; }
[[ "$mutant_rc" -ne 0 ]] || fail "(red) the mutant suite exited 0"
pass "(red) reproduced: --stop reports success, the orphan survives, the retry's build dies clearing kotlin-classes, the test never runs, the class is reported as failed twice"

echo
echo "== AC1 GREEN: with the reap, the retry's build RUNS and the class recovers =="

real_log="$SANDBOX/real.log"
run_suite "$REAL_REPO" "$real_log" "$SANDBOX/state-real"
real_rc="$RUN_RC"

grep -q 'JOURNEY_ABORT_BUILD_CLEANUP:' "$real_log" \
  || { sed -n '1,120p' "$real_log"; fail "(green) the cleanup never observed the orphaned build writer — the fix did not run"; }
grep -q 'JOURNEY_ABORT_BUILD_CLEANUP_VERIFIED:' "$real_log" \
  || { sed -n '1,120p' "$real_log"; fail "(green) the cleanup did not verify the build outputs were released"; }
real_retry_log="$(wedge_attempt_log "$REAL_REPO" 2)"
[[ -n "$real_retry_log" && -f "$real_retry_log" ]] \
  || fail "(green) no attempt-2 log was captured for $WEDGE_CLASS"
if grep -q 'Unable to delete directory' "$real_retry_log"; then
  cat "$real_retry_log"
  fail "(green) the retry's build still died on a leftover writer"
fi
grep -q 'Starting 1 tests on' "$real_retry_log" \
  || { cat "$real_retry_log"; fail "(green) THE ACCEPTANCE: the retry's build did not run to instrumentation"; }
grep -q "JOURNEY_FLAKE_RECOVERED: .*$WEDGE_CLASS passed on retry" "$real_log" \
  || { sed -n '1,120p' "$real_log"; fail "(green) the timed-out class did not recover on the retry"; }
[[ "$real_rc" -eq 0 ]] \
  || { sed -n '1,120p' "$real_log"; fail "(green) the real suite exited $real_rc; expected a recovered success"; }
pass "(green) the orphan is reaped, the retry's build runs, instrumentation starts, and the class recovers (suite exit 0)"

# The daemon stop is NOT skipped — the #918 cleanup it exists for still happens.
[[ -s "$SANDBOX/state-real/stop.log" ]] \
  || fail '(green) the fix skipped `gradlew --stop`; the #918 wedged-daemon cleanup must be preserved, not replaced'
pass '(green) `gradlew --stop` still ran — the fix rescopes the cleanup, it does not skip it' 

echo
echo "== AC2: a build-level retry failure is distinguishable from a genuine twice-failure =="

# The mutant run above is the real artifact tree of the reported cascade. Read it
# with the real scanner the workflow classifier calls.
red_manifests="$(grep -rlFx 'attempt_failure_phase=build' \
  "$MUTANT_REPO/artifacts/ci-journey/class-attempts" 2>/dev/null | wc -l)"
[[ "$red_manifests" -ge 1 ]] \
  || fail "(ac2) no attempt manifest recorded attempt_failure_phase=build for the build-level retry failure"
grep -q "JOURNEY_BUILD_PHASE_FAILURE: .*$WEDGE_CLASS attempt 2" "$mutant_log" \
  || { sed -n '1,140p' "$mutant_log"; fail "(ac2) the build-level failure was not named at the moment it happened"; }
scan_out="$(bash "$BUILD_PHASE_FAILURE" "$MUTANT_REPO/artifacts")"
scan_attempts="$(sed -n 's/^build_phase_failure_attempts=//p' <<<"$scan_out")"
scan_classes="$(sed -n 's/^build_phase_failure_classes=//p' <<<"$scan_out")"
[[ "$scan_attempts" -ge 1 ]] \
  || { printf '%s\n' "$scan_out"; fail "(ac2) the build-phase-failure scanner found no evidence"; }
[[ "$scan_classes" == *"$WEDGE_FQCN"* ]] \
  || { printf '%s\n' "$scan_out"; fail "(ac2) the scanner did not name the class"; }
grep -q 'Attempts that died at the Gradle BUILD level' "$MUTANT_REPO/artifacts/ci-journey/summary.md" \
  || { cat "$MUTANT_REPO/artifacts/ci-journey/summary.md"; fail "(ac2) the summary has no build-level-failure section"; }
# Severity is UNCHANGED: naming it must not soften it (D31/D32).
grep -q 'Failed BOTH attempts' "$MUTANT_REPO/artifacts/ci-journey/summary.md" \
  || { cat "$MUTANT_REPO/artifacts/ci-journey/summary.md"; fail "(ac2) naming the build-level failure removed the failed-both section — that is masking, not attribution"; }
pass "(ac2) build-level retry failure -> loud marker + manifest phase + its own summary section + scanner evidence ($scan_attempts attempt(s)), with severity unchanged"

echo
echo "== AC2 CONTROL (G6): a GENUINE journey failure is never labelled build-level =="

# Same suite, same wedged class, but the attempt FAILS after instrumentation ran
# and wrote result XML — the shape a real regression has.
GENUINE_REPO="$SANDBOX/genuine"
make_repo "$GENUINE_REPO"
cat > "$GENUINE_REPO/gradlew" <<'STUB'
#!/usr/bin/env bash
set -u
state="${ORPHAN_STUB_DIR:?}"
mkdir -p "$state"
printf '%s\n' "$*" >> "$state/args.log"
if [[ "${1:-}" == "--stop" ]]; then
  printf 'stop\n' >> "$state/stop.log"
  exit 0
fi
if [[ "$*" != *connectedDebugAndroidTest* ]]; then
  echo "> Task :app:assembleDebug"
  exit 0
fi
results="$PWD/app/build/outputs/androidTest-results/connected/debug"
mkdir -p "$results"
echo "> Task :app:preBuild"
echo "> Task :app:connectedDebugAndroidTest"
echo "Starting 1 tests on emulator-5554"
wedge="${ORPHAN_STUB_WEDGE_CLASS:-}"
if [[ -n "$wedge" && "$*" == *"$wedge"* ]]; then
  printf '<testsuite tests="1" failures="1"><testcase name="t"><failure message="boom"/></testcase></testsuite>\n' \
    > "$results/TEST-stub.xml"
  echo "Finished 1 tests on emulator-5554"
  exit 1
fi
printf '<testsuite tests="1" failures="0"><testcase name="t"/></testsuite>\n' \
  > "$results/TEST-stub.xml"
echo "Finished 1 tests on emulator-5554"
exit 0
STUB
chmod +x "$GENUINE_REPO/gradlew"

genuine_log="$SANDBOX/genuine.log"
run_suite "$GENUINE_REPO" "$genuine_log" "$SANDBOX/state-genuine"
grep -q "JOURNEY_FAILED: .*$WEDGE_CLASS failed twice" "$genuine_log" \
  || { sed -n '1,120p' "$genuine_log"; fail "(control) the genuine-failure control did not fail twice"; }
if grep -q 'JOURNEY_BUILD_PHASE_FAILURE' "$genuine_log"; then
  sed -n '1,120p' "$genuine_log"
  fail "(control) a genuine journey failure was labelled a build-level failure"
fi
genuine_phases="$(find "$GENUINE_REPO/artifacts/ci-journey/class-attempts" -type f -name manifest.txt \
  -exec grep -l "^class=$WEDGE_FQCN\$" {} + 2>/dev/null \
  | tr '\n' '\0' | xargs -r -0 sed -n 's/^attempt_failure_phase=//p' | sort -u | tr '\n' ' ')"
[[ "$genuine_phases" == "instrumentation " ]] \
  || fail "(control) manifests recorded attempt_failure_phase '$genuine_phases', expected only 'instrumentation'"
scan_out="$(bash "$BUILD_PHASE_FAILURE" "$GENUINE_REPO/artifacts")"
grep -qx 'build_phase_failure_attempts=0' <<<"$scan_out" \
  || { printf '%s\n' "$scan_out"; fail "(control) the scanner claimed build-level evidence for a genuine failure"; }
grep -q 'Failed BOTH attempts' "$GENUINE_REPO/artifacts/ci-journey/summary.md" \
  || fail "(control) a genuine journey failure lost its Failed BOTH attempts section"
if grep -q 'Attempts that died at the Gradle BUILD level' "$GENUINE_REPO/artifacts/ci-journey/summary.md"; then
  fail "(control) the summary emitted a build-level section for a genuine failure"
fi
pass "(control) a genuine twice-failure keeps its Failed BOTH attempts verdict and is never attributed to the build"

# ...and the real GREEN run, which recovered, carries no build-level evidence.
scan_out="$(bash "$BUILD_PHASE_FAILURE" "$REAL_REPO/artifacts")"
grep -qx 'build_phase_failure_attempts=0' <<<"$scan_out" \
  || { printf '%s\n' "$scan_out"; fail "(control) the recovered run claimed build-level failure evidence"; }
pass "(control) a run whose retry built and passed reports no build-level failure evidence"

echo
echo "== G2: every inter-attempt Gradle cleanup site is covered by the same reap =="

# The reap lives INSIDE the one shared cleanup function, so every caller inherits
# it. Pin that: all three abort sites must go through cleanup_gradle_after_timeout,
# and the reap must be inside it — a future site that stops Gradle its own way
# would slip past this fix exactly as `--stop` slipped past the Kotlin daemon.
for site in "$BUDGET_HELPER" "$WARM_HELPER"; do
  grep -q 'cleanup_gradle_after_timeout' "$site" \
    || fail "(g2) $site no longer routes its abort cleanup through cleanup_gradle_after_timeout"
done
callers="$(grep -c '^\s*cleanup_gradle_after_timeout ' "$BUDGET_HELPER" "$WARM_HELPER" \
  | awk -F: '{ total += $2 } END { print total + 0 }')"
[[ "$callers" -eq 3 ]] \
  || fail "(g2) expected 3 cleanup_gradle_after_timeout call sites (run_class, run_ct_class, warm_journey_build); found $callers — a new site must be covered by the reap too"
awk '
  /^cleanup_gradle_after_timeout\(\)/ { inside = 1 }
  inside && /cleanup_build_output_writers_after_abort/ { found = 1 }
  inside && /^}/ { inside = 0 }
  END { exit(found ? 0 : 1) }
' "$BUDGET_HELPER" \
  || fail "(g2) the orphan reap is not inside cleanup_gradle_after_timeout, so the other call sites do not inherit it"
pass "(g2) all 3 abort-cleanup sites share one cleanup function and the reap is inside it"

# The device-side sibling must still run after the Gradle side (ordering).
awk '
  /^  if needs_gradle_cleanup_after_class_abort/ { armed = 1 }
  armed && /cleanup_gradle_after_timeout/ { gradle = NR }
  armed && /cleanup_device_after_class_abort/ { if (gradle && NR > gradle) ok = 1; armed = 0 }
  END { exit(ok ? 0 : 1) }
' "$BUDGET_HELPER" \
  || fail "(g2) the device cleanup no longer follows the Gradle cleanup on the abort path"
pass "(g2) the abort path still stops Gradle, reaps build writers, then force-stops the device packages"

# A failed daemon stop must no longer be swallowed. The old shape read `$?` after
# an `if` whose branch did not run, which bash defines as 0 — so a FAILED
# `gradlew --stop` was reported as a clean cleanup.
STOP_FAIL_REPO="$SANDBOX/stopfail"
make_repo "$STOP_FAIL_REPO"
python3 - "$STOP_FAIL_REPO/gradlew" <<'PY'
import sys
path = sys.argv[1]
text = open(path, encoding="utf-8").read()
needle = '  echo "1 Daemon stopped"\n  exit 0\n'
if needle not in text:
    sys.exit("could not make the stub daemon stop fail")
open(path, "w", encoding="utf-8").write(text.replace(needle, '  exit 9\n'))
PY
stopfail_log="$SANDBOX/stopfail.log"
run_suite "$STOP_FAIL_REPO" "$stopfail_log" "$SANDBOX/state-stopfail"
grep -q 'GRADLE_TIMEOUT_CLEANUP_FAILED: Gradle daemon stop exited 9' "$stopfail_log" \
  || { sed -n '1,120p' "$stopfail_log"; fail "(g2) a failing gradlew --stop was not reported"; }
stopfail_manifest="$(find "$STOP_FAIL_REPO/artifacts/ci-journey/class-attempts" -type f -name manifest.txt \
  -path "*$WEDGE_CLASS*" 2>/dev/null | head -n 1)"
[[ -n "$stopfail_manifest" ]] || fail "(g2) no manifest was written for the failing-stop run"
grep -Fqx 'gradle_cleanup_exit_code=9' "$stopfail_manifest" \
  || { cat "$stopfail_manifest"; fail "(g2) a failed daemon stop was still recorded as exit 0 — the \`\$?\`-after-\`if\` swallow regressed"; }
if grep -Fqx 'cleanup_status=verified_clean' "$stopfail_manifest"; then
  cat "$stopfail_manifest"
  fail "(g2) a failed daemon stop was recorded as verified_clean"
fi
pass "(g2) a failing gradlew --stop is now recorded honestly (exit 9, not verified_clean)"

echo
echo "== G6: no timeout and no budget was widened =="

# shellcheck disable=SC2016
class_cap="$(sed -n 's/^JOURNEY_CLASS_TIMEOUT_SECS="${JOURNEY_CLASS_TIMEOUT_SECS:-\([0-9][0-9]*\)}"$/\1/p' "$REAL_SUITE")"
# shellcheck disable=SC2016
suite_budget="$(sed -n 's/^JOURNEY_STEP_BUDGET_SECS="${JOURNEY_STEP_BUDGET_SECS:-\([0-9][0-9]*\)}"$/\1/p' "$REAL_SUITE")"
# shellcheck disable=SC2016
stop_cap="$(sed -n 's/^JOURNEY_GRADLE_STOP_TIMEOUT_SECS="${JOURNEY_GRADLE_STOP_TIMEOUT_SECS:-\([0-9][0-9]*\)}"$/\1/p' "$REAL_SUITE")"
# shellcheck disable=SC2016
no_output="$(sed -n 's/^JOURNEY_NO_OUTPUT_TIMEOUT_SECS="${JOURNEY_NO_OUTPUT_TIMEOUT_SECS:-\([0-9][0-9]*\)}"$/\1/p' "$REAL_SUITE")"
[[ "$class_cap" -eq 420 ]] || fail "(g6) the per-class cap is ${class_cap}s, not the unchanged 420s"
[[ "$suite_budget" -eq 4200 ]] || fail "(g6) the suite budget is ${suite_budget}s, not the unchanged 4200s"
[[ "$stop_cap" -eq 60 ]] || fail "(g6) the daemon-stop window is ${stop_cap}s, not the unchanged 60s"
[[ "$no_output" -eq 300 ]] || fail "(g6) the silence window is ${no_output}s, not the unchanged 300s"
# The reap's own bound is bounded and small — it must not become a new way to
# spend the suite budget.
reap_cap="$(sed -n 's/.*JOURNEY_BUILD_CLEANUP_TIMEOUT_SECS:-\([0-9][0-9]*\)}.*/\1/p' "$BUDGET_HELPER" | head -n 1)"
[[ "$reap_cap" =~ ^[0-9]+$ ]] || fail "(g6) the orphan reap has no bounded default"
[[ "$reap_cap" -le 60 ]] || fail "(g6) the orphan reap's ${reap_cap}s bound is not small"
pass "(g6) class cap ${class_cap}s, suite budget ${suite_budget}s, stop window ${stop_cap}s, silence ${no_output}s all unchanged; reap bounded at ${reap_cap}s"

echo
echo "== safety: the reap can only ever touch THIS checkout's build outputs =="

# A glob for "any directory named build under $REPO_ROOT" would nominate sibling
# agents' worktrees at $REPO_ROOT/.worktrees/issue-*/ and kill their builds. Pin
# the explicit root list, and prove a worktree path is not one of them.
probe="$SANDBOX/roots-probe"
rm -rf "$probe"
mkdir -p "$probe/app/build" "$probe/shared/core-ssh/build" "$probe/build" \
  "$probe/.worktrees/issue-9999/app/build"
roots="$(
  REPO_ROOT="$probe" bash -c '
    source "$1"
    journey_build_output_roots
  ' _ "$BUDGET_HELPER" | sort | tr '\n' ' '
)"
[[ "$roots" == "$probe/app/build $probe/build $probe/shared/core-ssh/build " ]] \
  || fail "(safety) unexpected build-output roots: '$roots'"
grep -q 'never a `find`/glob\|Deliberately an EXPLICIT list' "$BUDGET_HELPER" \
  || fail "(safety) the explicit-root-list rationale is undocumented"
pass "(safety) roots are the explicit module build dirs only; a sibling worktree under .worktrees/ is not one of them"

# ...and the nomination is by open descriptor, never cwd: the adb server inherits
# the suite's cwd ($REPO_ROOT) and must never be nominated.
cwd_probe="$SANDBOX/cwd-probe"
rm -rf "$cwd_probe"
mkdir -p "$cwd_probe/app/build/tmp"
( cd "$cwd_probe/app/build/tmp" && exec sleep 20 ) >/dev/null 2>&1 </dev/null &
cwd_pid=$!
sleep 0.3
nominated="$(
  REPO_ROOT="$cwd_probe" bash -c '
    source "$1"
    journey_build_output_writer_pids
  ' _ "$BUDGET_HELPER" | tr '\n' ' '
)"
kill -KILL "$cwd_pid" 2>/dev/null || true
wait "$cwd_pid" 2>/dev/null || true
[[ "$nominated" != *"$cwd_pid"* ]] \
  || fail "(safety) a process whose only tie to the build root is its CWD was nominated for a kill"
pass "(safety) a cwd-only process (the adb-server shape) is never nominated; only open descriptors count"

echo
echo "ALL TESTS PASSED: scripts/test-ci-journey-inter-attempt-cleanup.sh"
