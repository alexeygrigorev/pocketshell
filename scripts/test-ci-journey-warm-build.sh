#!/usr/bin/env bash
# Issue #1814: deterministic, JVM-free, emulator-free proof for
#   (1) the first class on a shard is no longer charged for the cold
#       `:app:compileDebugKotlin` inside its own per-class budget, and
#   (2) a build-phase `outer_timeout` is distinguishable from a genuine journey
#       failure in the shard verdict and the suite summary.
#
# THE REPRODUCTION (why this test is shaped this way)
# --------------------------------------------------
# Run 30323508796, shard 2: `MultiSessionSwitchJourneyE2eTest` was FIRST on the
# shard and its attempt-1 verdict was `exit 124 / outer_timeout / raw-junit
# count=0`, with the log ending at `:app:createDebugApkListingFileRedirect` —
# still building. It was the only one of that shard's 47 classes whose attempt 1
# ran a cold `:app:compileDebugKotlin`, and it passes locally. So the fixture
# below models exactly that: a gradle stub where the FIRST invocation that has
# to produce the APKs pays a cold-build cost and every later invocation is
# up-to-date. Against a mutant of the real suite with the warm-build call
# removed, the first class visibly absorbs that cost (RED); against the real
# suite it does not (GREEN). Both numbers are MEASURED from the suite's own
# `JOURNEY_PASS: … (elapsed Ns)` lines and printed, so the comparison is an
# observation rather than an argument.
#
# Everything here drives the REAL scripts — the real ci-journey-suite.sh and its
# real helpers, the real verdict writer, the real aggregate reducer, the real
# build-phase scanner — with stubbed gradle/adb. No logic is re-implemented.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKFLOW="$REPO_ROOT/.github/workflows/tests.yml"
REAL_SUITE="$SCRIPT_DIR/ci-journey-suite.sh"
CLASS_LOOP="$SCRIPT_DIR/ci-journey-class-loop-functions.sh"
WARM_HELPER="$SCRIPT_DIR/ci-journey-warm-build-functions.sh"
BUILD_PHASE="$SCRIPT_DIR/ci-journey-build-phase-timeout.sh"
WRITER="$SCRIPT_DIR/ci-journey-write-shard-verdict.sh"
AGG="$SCRIPT_DIR/ci-journey-aggregate-verdict.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

for required in "$REAL_SUITE" "$CLASS_LOOP" "$WARM_HELPER" "$BUILD_PHASE" \
  "$WRITER" "$AGG" "$WORKFLOW"; do
  [[ -f "$required" ]] || fail "missing required file: $required"
done

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

COLD_SECS=6
SHARD_TOTAL=3

HELPERS=(
  ci-journey-suite.sh
  ci-journey-class-selection-functions.sh
  ci-journey-budget-functions.sh
  ci-journey-warm-build-functions.sh
  ci-journey-class-loop-functions.sh
  ci-journey-core-terminal-functions.sh
  ci-journey-summary-functions.sh
)

# ---------------------------------------------------------------------------
# Sandbox "repo root": the REAL suite + helpers, a gradle stub that models a
# cold build, and stub adb/connected-test. The suite derives REPO_ROOT from
# BASH_SOURCE, so running the copy makes the sandbox the repo.
# ---------------------------------------------------------------------------
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

# make_repo <dir> — a sandbox repo root with the real suite + helpers.
make_repo() {
  local root="$1"
  rm -rf "$root"
  mkdir -p "$root/scripts"
  local helper
  for helper in "${HELPERS[@]}"; do
    cp "$SCRIPT_DIR/$helper" "$root/scripts/$helper"
  done
  chmod +x "$root/scripts/ci-journey-suite.sh"

  # Gradle stub: models the COLD BUILD. The first invocation that has to produce
  # the APKs pays WARM_STUB_COLD_SECS; every later invocation is up-to-date —
  # exactly the asymmetry that charged the first class for the cold build.
  cat > "$root/gradlew" <<'STUB'
#!/usr/bin/env bash
set -u
state="${WARM_STUB_DIR:?}"
mkdir -p "$state"
printf '%s\n' "$*" >> "$state/args.log"
if [[ "${1:-}" == "--stop" ]]; then
  printf 'stop\n' >> "$state/stop.log"
  exit 0
fi

module_build_root() {
  if [[ "$*" == *":shared:core-terminal:"* ]]; then
    printf '%s\n' "$PWD/shared/core-terminal/build"
  else
    printf '%s\n' "$PWD/app/build"
  fi
}
write_result_xml() {
  local outcome="$1" root
  root="$(module_build_root "$@")/outputs/androidTest-results/connected/debug"
  mkdir -p "$root"
  if [[ "$outcome" == failure ]]; then
    printf '<testsuite tests="1" failures="1"><testcase name="t"><failure message="boom"/></testcase></testsuite>\n' \
      > "$root/TEST-stub.xml"
  else
    printf '<testsuite tests="1" failures="0"><testcase name="t"/></testsuite>\n' \
      > "$root/TEST-stub.xml"
  fi
}

cold="${WARM_STUB_COLD_SECS:-0}"
echo "> Task :app:preBuild"
if [[ ! -e "$state/built" ]]; then
  echo "> Task :app:compileDebugKotlin"
  if [[ "$cold" -gt 0 ]]; then sleep "$cold"; fi
  : > "$state/built"
else
  echo "> Task :app:compileDebugKotlin UP-TO-DATE"
fi
echo "> Task :app:createDebugApkListingFileRedirect"

wedge="${WARM_STUB_WEDGE_CLASS:-}"
mode="${WARM_STUB_WEDGE_MODE:-none}"
if [[ -n "$wedge" && "$*" == *"$wedge"* && "$*" == *connectedDebugAndroidTest* ]]; then
  case "$mode" in
    build)
      # Still BUILDING when the cap fires: NO instrumentation marker is ever
      # emitted. This is the run-30323508796 shape.
      echo "> Task :app:mergeDebugAssets"
      exec sleep 120
      ;;
    instrumentation)
      # Instrumentation DID start and then wedged — the same exit 124 /
      # count=0 verdict, a genuinely different cause.
      echo "> Task :app:connectedDebugAndroidTest"
      echo "Starting 1 tests on emulator-5554"
      exec sleep 120
      ;;
    failure)
      echo "> Task :app:connectedDebugAndroidTest"
      echo "Starting 1 tests on emulator-5554"
      write_result_xml failure "$@"
      echo "Finished 1 tests on emulator-5554"
      exit 1
      ;;
  esac
fi

case "$*" in
  *connectedDebugAndroidTest*)
    echo "> Task :app:connectedDebugAndroidTest"
    echo "Starting 1 tests on emulator-5554"
    write_result_xml pass "$@"
    echo "Finished 1 tests on emulator-5554"
    ;;
esac
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

FIXED_REPO="$SANDBOX/fixed"
MUTANT_REPO="$SANDBOX/mutant"
make_repo "$FIXED_REPO"
make_repo "$MUTANT_REPO"

# THE MUTANT: the real suite with ONLY the warm-build call removed. Everything
# else — budgets, caps, retry, artifacts — is byte-identical, so any measured
# difference is attributable to that one line and nothing else.
sed -i '/^  warm_journey_build$/d' "$MUTANT_REPO/scripts/ci-journey-class-loop-functions.sh"
grep -q '^  warm_journey_build$' "$FIXED_REPO/scripts/ci-journey-class-loop-functions.sh" \
  || fail "(setup) the real class loop no longer calls warm_journey_build — the fixture would prove nothing"
if grep -q '^  warm_journey_build$' "$MUTANT_REPO/scripts/ci-journey-class-loop-functions.sh"; then
  fail "(setup) the mutant still calls warm_journey_build — the red control is not live"
fi
pass "(setup) mutant differs from the real suite by exactly the warm-build call"

# run_suite <repo> <shard> <log> <state-dir> [env assignments...]
RUN_RC=0
run_suite() {
  local repo="$1" shard="$2" log="$3" state="$4"; shift 4
  rm -rf "$state"
  mkdir -p "$state"
  # NOTE: deliberately no `set -e`/`set +e` dance here. This harness runs with
  # errexit OFF and asserts explicitly with `|| fail`; a stray `set -e` made a
  # later `var="$(script-that-exits-1)"` abort the run with no message at all.
  env PATH="$STUBBIN:$PATH" \
    WARM_STUB_DIR="$state" \
    JOURNEY_STEP_BUDGET_SECS=900 \
    JOURNEY_CLASS_TIMEOUT_SECS=30 \
    JOURNEY_NO_OUTPUT_TIMEOUT_SECS=25 \
    JOURNEY_CLASS_KILL_AFTER_SECS=1 \
    JOURNEY_GRADLE_STOP_TIMEOUT_SECS=5 \
    POCKETSHELL_JOURNEY_CI_SHARD_TOTAL="$SHARD_TOTAL" \
    POCKETSHELL_JOURNEY_CI_SHARD_INDEX="$shard" \
    "$@" \
    bash "$repo/scripts/ci-journey-suite.sh" > "$log" 2>&1
  RUN_RC=$?
}

# passed_elapsed <log> — "class elapsed" pairs, in launch order.
passed_elapsed() {
  sed -n 's/^JOURNEY_PASS: \([^ ]*\) passed on attempt 1 (elapsed \([0-9]*\)s)$/\1 \2/p' "$1"
}

echo "== #1814 AC1/AC4: the first class on a shard is not charged for the cold build =="
echo "   (gradle stub: the first APK-producing invocation costs ${COLD_SECS}s, later ones are up-to-date)"

for shard in 0 1 2; do
  fixed_log="$SANDBOX/fixed-shard-$shard.log"
  mutant_log="$SANDBOX/mutant-shard-$shard.log"
  run_suite "$FIXED_REPO" "$shard" "$fixed_log" "$SANDBOX/state-fixed-$shard" \
    WARM_STUB_COLD_SECS="$COLD_SECS"
  fixed_rc="$RUN_RC"
  run_suite "$MUTANT_REPO" "$shard" "$mutant_log" "$SANDBOX/state-mutant-$shard" \
    WARM_STUB_COLD_SECS="$COLD_SECS"
  mutant_rc="$RUN_RC"

  [[ "$fixed_rc" -eq 0 ]] \
    || { sed -n '1,40p' "$fixed_log"; fail "(ac1/shard $shard) the healthy fixed run exited $fixed_rc"; }
  [[ "$mutant_rc" -eq 0 ]] \
    || { sed -n '1,40p' "$mutant_log"; fail "(ac1/shard $shard) the mutant run exited $mutant_rc (it must differ only in TIMING)"; }

  mapfile -t fixed_pairs < <(passed_elapsed "$fixed_log")
  mapfile -t mutant_pairs < <(passed_elapsed "$mutant_log")
  # G3: a vacuous run proves nothing — require a real, comparable class count.
  [[ "${#fixed_pairs[@]}" -ge 10 ]] \
    || fail "(ac1/shard $shard) only ${#fixed_pairs[@]} classes reached a verdict — nothing to compare"
  [[ "${#fixed_pairs[@]}" -eq "${#mutant_pairs[@]}" ]] \
    || fail "(ac1/shard $shard) fixed ran ${#fixed_pairs[@]} classes, mutant ran ${#mutant_pairs[@]} — not a like-for-like comparison"

  mid_index=$(( ${#fixed_pairs[@]} / 2 ))
  fixed_first_class="${fixed_pairs[0]%% *}";   fixed_first="${fixed_pairs[0]##* }"
  fixed_mid_class="${fixed_pairs[$mid_index]%% *}"; fixed_mid="${fixed_pairs[$mid_index]##* }"
  mutant_first="${mutant_pairs[0]##* }"
  mutant_mid="${mutant_pairs[$mid_index]##* }"

  echo "    shard $shard first class: $fixed_first_class"
  echo "      REAL   : first=${fixed_first}s  mid(#$mid_index $fixed_mid_class)=${fixed_mid}s"
  echo "      MUTANT : first=${mutant_first}s mid(#$mid_index)=${mutant_mid}s   (warm-build call removed)"

  # RED (mutant): the first class absorbs the whole cold build; a mid-shard class
  # does not. This is the reported defect, observed.
  (( mutant_first >= COLD_SECS )) \
    || fail "(ac1/shard $shard) RED control did not reproduce: mutant first-class elapsed ${mutant_first}s < cold build ${COLD_SECS}s"
  (( mutant_first - mutant_mid >= COLD_SECS - 1 )) \
    || fail "(ac1/shard $shard) RED control did not reproduce: mutant first=${mutant_first}s vs mid=${mutant_mid}s"

  # GREEN (real): the first class is measured on the same terms as a mid-shard
  # class, and cannot have paid the cold build.
  (( fixed_first < COLD_SECS )) \
    || fail "(ac1/shard $shard) the first class still paid the cold build (${fixed_first}s >= ${COLD_SECS}s)"
  (( fixed_first - fixed_mid <= 1 )) \
    || fail "(ac1/shard $shard) first class (${fixed_first}s) is still measured differently from mid-shard (${fixed_mid}s)"

  # The cold build did happen — it was paid by the warm step, before the loop.
  # NOTE: no `| head`/`| grep -m1` anywhere in this file — a reader that closes
  # the pipe early SIGPIPEs the writer, which under `set -o pipefail` made this
  # harness die silently mid-run (exit 141) instead of reporting a verdict.
  warm_elapsed="$(sed -n 's/^JOURNEY_WARM_BUILD: cold build paid up front in \([0-9]*\)s.*/\1/p' "$fixed_log")"
  warm_elapsed="${warm_elapsed%%$'\n'*}"
  [[ "$warm_elapsed" =~ ^[0-9]+$ ]] \
    || { sed -n '1,40p' "$fixed_log"; fail "(ac1/shard $shard) no JOURNEY_WARM_BUILD line — the cold build was not paid up front"; }
  (( warm_elapsed >= COLD_SECS )) \
    || fail "(ac1/shard $shard) warm build took ${warm_elapsed}s but the cold build costs ${COLD_SECS}s — it did not do the build"
  if grep -q 'JOURNEY_WARM_BUILD' "$mutant_log"; then
    fail "(ac1/shard $shard) the mutant emitted a warm-build line — the control is contaminated"
  fi

  # ...and it ran BEFORE the first class's per-class clock started.
  warm_line="$(grep -n 'WARM BUILD (issue #1814)' "$fixed_log" | cut -d: -f1)"
  warm_line="${warm_line%%$'\n'*}"
  first_class_line="$(grep -n '>>> JOURNEY CLASS: ' "$fixed_log" | cut -d: -f1)"
  first_class_line="${first_class_line%%$'\n'*}"
  [[ -n "$warm_line" && -n "$first_class_line" && "$warm_line" -lt "$first_class_line" ]] \
    || fail "(ac1/shard $shard) the warm build did not run before the first class (warm=$warm_line first=$first_class_line)"

  # ...and it is charged to the SUITE budget, not moved outside it: the first
  # class starts with the budget already reduced by the build. This is what
  # keeps the documented job-cap arithmetic (5700 - 900 - 4200 = 600s) intact.
  first_remaining="$(sed -n 's/.*>>> JOURNEY CLASS: .*\[budget remaining: \([0-9]*\)s\]$/\1/p' "$fixed_log")"
  first_remaining="${first_remaining%%$'\n'*}"
  [[ "$first_remaining" =~ ^[0-9]+$ ]] \
    || fail "(ac1/shard $shard) could not read the first class's remaining budget"
  (( first_remaining <= 900 - COLD_SECS )) \
    || fail "(ac1/shard $shard) the warm build was NOT charged to the suite budget (remaining ${first_remaining}s of 900s after a ${COLD_SECS}s build)"

  grep -q 'Warm build (issue #1814): \*\*ok\*\*' "$FIXED_REPO/artifacts/ci-journey/summary.md" \
    || { cat "$FIXED_REPO/artifacts/ci-journey/summary.md"; fail "(ac1/shard $shard) the summary does not report the warm build"; }

  pass "(ac1/shard $shard) first=${fixed_first}s vs mid=${fixed_mid}s (was ${mutant_first}s vs ${mutant_mid}s) — cold build paid up front in ${warm_elapsed}s"
done
pass "(ac4) verified on all $SHARD_TOTAL shards, each with its own first class"

echo
echo "== #1814 AC2: no per-class budget was raised =="
# shellcheck disable=SC2016
class_cap="$(sed -n 's/^JOURNEY_CLASS_TIMEOUT_SECS="${JOURNEY_CLASS_TIMEOUT_SECS:-\([0-9][0-9]*\)}"$/\1/p' "$REAL_SUITE")"
# shellcheck disable=SC2016
suite_budget="$(sed -n 's/^JOURNEY_STEP_BUDGET_SECS="${JOURNEY_STEP_BUDGET_SECS:-\([0-9][0-9]*\)}"$/\1/p' "$REAL_SUITE")"
# shellcheck disable=SC2016
warm_cap="$(sed -n 's/^JOURNEY_WARM_BUILD_TIMEOUT_SECS="${JOURNEY_WARM_BUILD_TIMEOUT_SECS:-\([0-9][0-9]*\)}"$/\1/p' "$REAL_SUITE")"
[[ "$class_cap" -eq 420 ]] \
  || fail "(ac2) the per-class cap is ${class_cap}s, not the unchanged 420s — the fix must not buy headroom by raising it (G6)"
[[ "$suite_budget" -eq 4200 ]] \
  || fail "(ac2) the suite budget is ${suite_budget}s, not the unchanged 4200s — the job-cap arithmetic would change"
[[ "$warm_cap" =~ ^[0-9]+$ ]] \
  || fail "(ac2) the warm build has no bounded default cap"
job_cap_min="$(awk '
  /^  emulator-journey:/ { in_job=1; next }
  in_job && /^  [A-Za-z0-9_-]+:/ { in_job=0 }
  in_job && /timeout-minutes:/ { print $2; exit }
' "$WORKFLOW")"
[[ "$job_cap_min" -eq 95 ]] || fail "(ac2) emulator-journey job cap changed to ${job_cap_min} min"
slack=$(( job_cap_min * 60 - 900 - suite_budget ))
[[ "$slack" -ge 600 ]] \
  || fail "(ac2) post-boot slack dropped to ${slack}s (< 600s)"
# The warm build must be charged INSIDE the suite budget (after SUITE_START), or
# it would silently eat that slack.
awk '
  /SUITE_START=\$SECONDS/ { seen_start=1 }
  /^  warm_journey_build$/ { if (seen_start) found=1; else early=1 }
  END { exit((found && !early) ? 0 : 1) }
' "$CLASS_LOOP" \
  || fail "(ac2) warm_journey_build must be called AFTER SUITE_START so its cost stays inside the suite budget"
pass "(ac2) per-class cap still ${class_cap}s, suite budget still ${suite_budget}s, slack ${slack}s; warm build bounded at ${warm_cap}s inside the suite budget"

echo
echo "== #1814 AC3: a build-phase outer timeout is distinguishable from a journey failure =="

# The first class of shard 0, read from the real allowlist so a reordering of
# JOURNEY_CLASSES cannot silently make this fixture test a different class.
first_entry="$(awk '
  /^JOURNEY_CLASSES=\(/ { f=1; next }
  /^\)/ { f=0 }
  f && /^[[:space:]]*"/ { print; exit }
' "$REAL_SUITE" | sed 's/^[[:space:]]*"\([^"]*\)".*/\1/')"
WEDGE_CLASS="${first_entry##*.}"
[[ -n "$WEDGE_CLASS" ]] || fail "(ac3) could not resolve the first journey class from the allowlist"
echo "    wedging the first class of shard 0: $WEDGE_CLASS"

# wedge_run <mode> <log> — one shard-0 run whose first class behaves per <mode>.
# The cold cost is 0 here: this fixture is about ATTRIBUTION, not accounting.
wedge_run() {
  local mode="$1" log="$2"
  run_suite "$FIXED_REPO" 0 "$log" "$SANDBOX/state-wedge-$mode" \
    WARM_STUB_COLD_SECS=0 \
    WARM_STUB_WEDGE_CLASS="$WEDGE_CLASS" \
    WARM_STUB_WEDGE_MODE="$mode" \
    JOURNEY_CLASS_TIMEOUT_SECS=4 \
    JOURNEY_NO_OUTPUT_TIMEOUT_SECS=2
}

manifest_phases() {
  # every recorded phase for the wedged class, across attempts
  find "$FIXED_REPO/artifacts/ci-journey/class-attempts" -type f -name manifest.txt \
    -exec grep -l "^class=.*$WEDGE_CLASS$" {} + 2>/dev/null \
    | tr '\n' '\0' \
    | xargs -r -0 sed -n 's/^outer_timeout_phase=//p' | sort -u | tr '\n' ' '
}

# (b1) THE REPORTED SHAPE: cut while Gradle was still building.
wedge_run build "$SANDBOX/wedge-build.log"
build_log="$SANDBOX/wedge-build.log"
summary="$FIXED_REPO/artifacts/ci-journey/summary.md"
grep -q "JOURNEY_BUILD_PHASE_TIMEOUT: .*$WEDGE_CLASS attempt 1" "$build_log" \
  || { sed -n '1,60p' "$build_log"; fail "(b1) a still-building outer timeout was not named at the moment it happened"; }
phases="$(manifest_phases)"
[[ "$phases" == "build " ]] \
  || fail "(b1) manifests recorded phases '$phases', expected only 'build'"
grep -q 'Attempts cut short while Gradle was still BUILDING' "$summary" \
  || { cat "$summary"; fail "(b1) the summary has no build-phase section"; }
grep -q "^- \`.*$WEDGE_CLASS (attempt 1)\`$" "$summary" \
  || { cat "$summary"; fail "(b1) the summary's build-phase section does not name the class/attempt"; }
# It is NOT reported as a genuine twice-failed journey regression.
if grep -q 'Failed BOTH attempts' "$summary"; then
  cat "$summary"
  fail "(b1) a build-phase timeout was reported as a genuine failed-both journey regression"
fi
# The scanner the workflow classifier calls sees the same evidence.
scan_out="$(bash "$BUILD_PHASE" "$FIXED_REPO/artifacts")"
scan_attempts="$(sed -n 's/^build_phase_timeout_attempts=//p' <<<"$scan_out")"
scan_classes="$(sed -n 's/^build_phase_timeout_classes=//p' <<<"$scan_out")"
[[ "$scan_attempts" -ge 1 ]] \
  || { printf '%s\n' "$scan_out"; fail "(b1) the build-phase scanner found no evidence"; }
[[ "$scan_classes" == *"$WEDGE_CLASS"* ]] \
  || { printf '%s\n' "$scan_out"; fail "(b1) the scanner did not name the class"; }
pass "(b1) still-building timeout -> loud marker + manifest phase + its own summary section + scanner evidence ($scan_attempts attempt(s))"

# (b2) CLASS COVERAGE: the SAME exit 124 / count=0 verdict, but instrumentation
#      HAD started. It must NOT be attributed to the build.
wedge_run instrumentation "$SANDBOX/wedge-instr.log"
instr_log="$SANDBOX/wedge-instr.log"
if grep -q 'JOURNEY_BUILD_PHASE_TIMEOUT' "$instr_log"; then
  sed -n '1,60p' "$instr_log"
  fail "(b2) an instrumentation-phase wedge was mislabelled as a build-phase timeout"
fi
phases="$(manifest_phases)"
[[ "$phases" == "instrumentation " ]] \
  || fail "(b2) manifests recorded phases '$phases', expected only 'instrumentation'"
scan_out="$(bash "$BUILD_PHASE" "$FIXED_REPO/artifacts")"
grep -qx 'build_phase_timeout_attempts=0' <<<"$scan_out" \
  || { printf '%s\n' "$scan_out"; fail "(b2) the scanner claimed build-phase evidence for an instrumentation wedge"; }
if grep -q 'Attempts cut short while Gradle was still BUILDING' "$FIXED_REPO/artifacts/ci-journey/summary.md"; then
  fail "(b2) the summary emitted a build-phase section for an instrumentation wedge"
fi
pass "(b2) an instrumentation-phase wedge with the same exit 124 / count=0 verdict is NOT attributed to the build"

# (b3) THE LOAD-BEARING CONTROL (G6): a genuine journey failure must stay a
#      genuine journey failure — the attribution must never swallow one.
wedge_run failure "$SANDBOX/wedge-failure.log"
failure_log="$SANDBOX/wedge-failure.log"
summary="$FIXED_REPO/artifacts/ci-journey/summary.md"
grep -q "JOURNEY_FAILED: .*$WEDGE_CLASS failed twice" "$failure_log" \
  || { sed -n '1,60p' "$failure_log"; fail "(b3) the genuine failure control did not fail twice"; }
grep -q 'Failed BOTH attempts' "$summary" \
  || { cat "$summary"; fail "(b3) a genuine journey failure lost its Failed BOTH attempts section"; }
if grep -q 'JOURNEY_BUILD_PHASE_TIMEOUT' "$failure_log"; then
  fail "(b3) a genuine journey failure was labelled a build-phase timeout"
fi
phases="$(manifest_phases)"
[[ "$phases" == "not_applicable " ]] \
  || fail "(b3) manifests recorded phases '$phases', expected only 'not_applicable'"
scan_out="$(bash "$BUILD_PHASE" "$FIXED_REPO/artifacts")"
grep -qx 'build_phase_timeout_attempts=0' <<<"$scan_out" \
  || { printf '%s\n' "$scan_out"; fail "(b3) the scanner claimed build-phase evidence for a genuine failure"; }
pass "(b3) a genuine journey failure keeps its Failed BOTH attempts verdict and is never labelled build-phase"

echo
echo "== #1814 AC3: the shard verdict token and the aggregate carry the reason =="

write_token() {
  local dir="$1" idx="$2" token="$3" reason="$4"
  local sub="$dir/emulator-journey-verdict-shard-$idx"
  mkdir -p "$sub"
  SHARD_VERDICT_FILE="$sub/shard-verdict.txt" \
    POCKETSHELL_JOURNEY_CI_SHARD_INDEX="$idx" \
    GITHUB_RUN_ID=30323508796 GITHUB_RUN_ATTEMPT=1 GITHUB_OUTPUT="" \
    bash "$WRITER" "$token" "$reason" > /dev/null \
    || fail "writer refused $token/$reason for shard $idx"
}
run_agg() {
  AGG_OUT="$(EXPECTED_SHARDS=3 GITHUB_STEP_SUMMARY="" GITHUB_RUN_ATTEMPT=1 \
    bash "$AGG" "$1" 2>&1)"
  AGG_RC=$?
  AGG_VERDICT="$(sed -n 's/^AGGREGATE_VERDICT=//p' <<<"$AGG_OUT" | tail -n 1)"
}

# (c1) a RED caused by the cold build is NAMED as such...
d="$SANDBOX/verdicts-cold"; rm -rf "$d"; mkdir -p "$d"
write_token "$d" 2 RED cold_build_timeout
write_token "$d" 0 CLEAN passed_first_attempt
write_token "$d" 1 CLEAN passed_first_attempt
grep -qx 'verdict_reason=cold_build_timeout' "$d/emulator-journey-verdict-shard-2/shard-verdict.txt" \
  || fail "(c1) the token does not stamp the verdict reason"
[[ "$(head -n1 "$d/emulator-journey-verdict-shard-2/shard-verdict.txt")" == "RED" ]] \
  || fail "(c1) line 1 must stay the bare verdict token"
run_agg "$d"
printf '%s\n' "$AGG_OUT" | sed 's/^/    /'
[[ "$AGG_VERDICT" == "RED" && "$AGG_RC" -eq 1 ]] \
  || fail "(c1) naming the cause must NOT soften the verdict; got $AGG_VERDICT/exit$AGG_RC"
grep -q 'includes a cold-BUILD-phase timeout' <<<"$AGG_OUT" \
  || fail "(c1) the aggregate does not distinguish the cold-build cause"
grep -q 'shard 2: RED .*reason cold_build_timeout' <<<"$AGG_OUT" \
  || fail "(c1) the aggregate does not report the per-shard reason"
pass "(c1) a cold-build RED is named in the aggregate AND still exits 1 (severity unchanged)"

# (c2) ...and an ordinary journey RED is NOT, so the label means something.
d="$SANDBOX/verdicts-journey"; rm -rf "$d"; mkdir -p "$d"
write_token "$d" 2 RED journey_failure_both_attempts
write_token "$d" 0 CLEAN passed_first_attempt
write_token "$d" 1 CLEAN passed_first_attempt
run_agg "$d"
[[ "$AGG_VERDICT" == "RED" && "$AGG_RC" -eq 1 ]] \
  || fail "(c2) a genuine journey RED must stay RED/exit1, got $AGG_VERDICT/exit$AGG_RC"
if grep -q 'includes a cold-BUILD-phase timeout' <<<"$AGG_OUT"; then
  printf '%s\n' "$AGG_OUT"
  fail "(c2) the cold-build notice fired for a genuine journey failure — the label would be meaningless"
fi
grep -q 'shard 2: RED .*reason journey_failure_both_attempts' <<<"$AGG_OUT" \
  || fail "(c2) the aggregate does not report the genuine-failure reason"
pass "(c2) a genuine journey RED is reported as such, with no cold-build notice"

# (c3) #1800 / #1809 properties are untouched by the added reason line: an
#      unstamped legacy token, an all-INFRA run and an all-CLEAN run must behave
#      exactly as before.
d="$SANDBOX/verdicts-legacy"; rm -rf "$d"; mkdir -p "$d"
for idx in 0 1 2; do
  mkdir -p "$d/emulator-journey-verdict-shard-$idx"
  printf 'INFRA\n' > "$d/emulator-journey-verdict-shard-$idx/shard-verdict.txt"
done
run_agg "$d"
[[ "$AGG_VERDICT" == "RE-RUN" && "$AGG_RC" -eq 0 ]] \
  || fail "(c3) all-INFRA (unstamped legacy tokens) must stay RE-RUN/exit0, got $AGG_VERDICT/exit$AGG_RC"
grep -q '::error' <<<"$AGG_OUT" \
  && fail "(c3) an all-INFRA run must not emit ::error (#1458 no-false-red)"
grep -q 'reason unspecified' <<<"$AGG_OUT" \
  || fail "(c3) a token without a reason must read as 'unspecified', never as corrupt"
d="$SANDBOX/verdicts-clean"; rm -rf "$d"; mkdir -p "$d"
for idx in 0 1 2; do write_token "$d" "$idx" CLEAN passed_first_attempt; done
run_agg "$d"
[[ "$AGG_VERDICT" == "CLEAN" && "$AGG_RC" -eq 0 ]] \
  || fail "(c3) an all-CLEAN run must stay CLEAN/exit0, got $AGG_VERDICT/exit$AGG_RC"
pass "(c3) unstamped legacy tokens, all-INFRA RE-RUN and all-CLEAN behaviour are unchanged"

# (c4) the reason fails SOFT: a malformed label must never cost the token.
d="$SANDBOX/verdicts-bad-reason"; rm -rf "$d"; mkdir -p "$d/out"
SHARD_VERDICT_FILE="$d/out/shard-verdict.txt" POCKETSHELL_JOURNEY_CI_SHARD_INDEX=0 \
  GITHUB_RUN_ID=1 GITHUB_RUN_ATTEMPT=1 GITHUB_OUTPUT="$d/out/gh.txt" \
  bash "$WRITER" RED 'Not A Reason!' > /dev/null \
  || fail "(c4) a malformed reason must not fail the write"
grep -qx 'verdict_reason=unspecified' "$d/out/shard-verdict.txt" \
  || fail "(c4) a malformed reason must degrade to 'unspecified'"
grep -qx 'shard_verdict=RED' "$d/out/gh.txt" \
  || fail "(c4) the RED gate output must survive a malformed reason"
grep -qx 'shard_verdict_reason=unspecified' "$d/out/gh.txt" \
  || fail "(c4) the reason must be exported to \$GITHUB_OUTPUT"
bash "$WRITER" MAYBE cold_build_timeout > /dev/null 2>&1
writer_rc=$?
[[ "$writer_rc" -ne 0 ]] || fail "(c4) the writer must still reject a verdict outside CLEAN|INFRA|RED"
pass "(c4) the reason fails soft to 'unspecified'; an unknown VERDICT is still rejected"

echo
echo "== #1814 workflow wiring =="
grep -q 'scripts/ci-journey-build-phase-timeout.sh artifacts' "$WORKFLOW" \
  || fail "(w1) the classify step does not read the build-phase evidence"
grep -q 'write_verdict() { bash scripts/ci-journey-write-shard-verdict.sh "\$1" "\${2:-unspecified}"; }' "$WORKFLOW" \
  || fail "(w2) the classify step does not pass a reason to the one stamped writer"
grep -q "verdict_reason_for" "$WORKFLOW" \
  || fail "(w3) the classify step has no cold-build reason resolution"
# Every verdict branch must now carry a reason: no bare `write_verdict X` may
# survive, or a branch would silently report 'unspecified'.
if grep -qE '^\s*write_verdict (CLEAN|INFRA|RED)\s*$' "$WORKFLOW"; then
  grep -nE '^\s*write_verdict (CLEAN|INFRA|RED)\s*$' "$WORKFLOW"
  fail "(w4) a classify branch still writes its verdict without a reason"
fi
grep -q 'name: CI journey warm-build + build-phase attribution guard (issue #1814)' "$WORKFLOW" \
  || fail "(w5) this guard is not wired into the Unit job — an unrun test is no test (G9)"
grep -q 'scripts/test-ci-journey-warm-build.sh' "$WORKFLOW" \
  || fail "(w6) the Unit job does not execute this guard"
# #1809's properties must be intact.
grep -q 'ci-journey-write-shard-verdict.sh INFRA' "$WORKFLOW" \
  || fail "(w7) the #1809 pre-seed must still write INFRA"
grep -q "steps.classify.outputs.shard_verdict == 'RED'" "$WORKFLOW" \
  || fail "(w8) the #1809 RED shard gate must be intact"
pass "(w) classify reads build-phase evidence, every branch stamps a reason, guard is wired, #1809 wiring intact"

echo
echo "== #1814 the REAL classify step, executed (the #1809 method) =="
#
# Grepping the workflow proves the text is there; it does not prove the step
# RUNS. The classify step is shell embedded in YAML, so a typo in it reaches
# only CI. Following #1809's precedent: EXTRACT the classify step's real `run:`
# body out of tests.yml, substitute the `${{ }}` expressions with fixture
# values, EXECUTE it against real scripts and real artifacts, and read back the
# token it wrote and the `$GITHUB_OUTPUT` the #1809 RED gate depends on.

CLASSIFY_BODY="$SANDBOX/classify-step.sh"
python3 - "$WORKFLOW" "$CLASSIFY_BODY" <<'PY' || fail "(x) could not extract the classify step body from tests.yml"
import re
import sys

workflow, out = sys.argv[1], sys.argv[2]
lines = open(workflow).read().split("\n")

start = next(
    (i for i, l in enumerate(lines)
     if l.strip() == "- name: Classify emulator-journey result (infra-abort vs test-failure)"),
    None,
)
assert start is not None, "classify step not found in the workflow"
run_at = next(
    (i for i in range(start, min(start + 40, len(lines))) if lines[i].strip() == "run: |"),
    None,
)
assert run_at is not None, "classify step has no `run: |` block"

indent = len(lines[run_at]) - len(lines[run_at].lstrip())
body = []
for line in lines[run_at + 1:]:
    if not line.strip():
        body.append("")
        continue
    if len(line) - len(line.lstrip()) <= indent:
        break
    body.append(line[indent + 2:])
assert body, "classify step body is empty"

# `${{ steps.a.b }}` -> `${CLASSIFY_steps_a_b:-}` so the harness can inject each
# workflow expression as an ordinary environment variable.
src = re.sub(
    r"\$\{\{([^}]*)\}\}",
    lambda m: "${CLASSIFY_" + m.group(1).strip().replace(".", "_") + ":-}",
    "\n".join(body),
)
assert "${{" not in src, "an unsubstituted workflow expression survived"
open(out, "w").write(src + "\n")
PY
bash -n "$CLASSIFY_BODY" \
  || fail "(x) the classify step's real run: body is not valid bash"

CLASSIFY_DIR="$SANDBOX/classify-run"
setup_classify_dir() {
  rm -rf "$CLASSIFY_DIR"
  mkdir -p "$CLASSIFY_DIR/scripts" "$CLASSIFY_DIR/artifacts/ci-journey"
  cp "$WRITER" "$BUILD_PHASE" "$SCRIPT_DIR/ci-journey-shard-signature-verdict.sh" \
    "$SCRIPT_DIR/ci-journey-infra-signature.sh" "$SCRIPT_DIR/ci-journey-infra-signature.py" \
    "$CLASSIFY_DIR/scripts/"
}

# seed_build_phase_manifest — one preserved attempt manifest carrying the
# build-phase evidence, in the canonical per-attempt layout.
seed_build_phase_manifest() {
  local dir="$CLASSIFY_DIR/artifacts/ci-journey/class-attempts/app/Wedge--0123456789abcdef/attempt-1"
  mkdir -p "$dir"
  printf 'class=com.pocketshell.app.proof.MultiSessionSwitchJourneyE2eTest\nprimary_classification=outer_timeout\nraw_junit_count=0\nouter_timeout_phase=build\n' \
    > "$dir/manifest.txt"
}

seed_summary() {
  printf '%s\n' "$@" > "$CLASSIFY_DIR/artifacts/ci-journey/summary.md"
}

# run_classify <first> <retry> <first_concl> <retry_concl> <first_timeout>
#              <first_failure> <retry_allowed>
CLASSIFY_TOKEN=""
CLASSIFY_REASON=""
CLASSIFY_RC=0
CLASSIFY_OUT=""
run_classify() {
  local gh="$CLASSIFY_DIR/gh-output.txt"
  local tok="$CLASSIFY_DIR/shard-verdict.txt"
  : > "$gh"
  rm -f "$tok"
  CLASSIFY_OUT="$(
    cd "$CLASSIFY_DIR" && env \
      CLASSIFY_steps_journey_outcome="$1" \
      CLASSIFY_steps_journey_retry_outcome="$2" \
      CLASSIFY_steps_journey_conclusion="$3" \
      CLASSIFY_steps_journey_retry_conclusion="$4" \
      CLASSIFY_steps_journey_summary_outputs_first_timeout="$5" \
      CLASSIFY_steps_journey_summary_outputs_first_failure="$6" \
      CLASSIFY_steps_journey_retry_budget_outputs_retry_allowed="$7" \
      CLASSIFY_steps_journey_retry_budget_outputs_retry_reason=fixture \
      CLASSIFY_steps_journey_retry_budget_outputs_retry_remaining_ms=1 \
      CLASSIFY_steps_journey_retry_budget_outputs_retry_required_ms=2 \
      CLASSIFY_steps_journey_retry_budget_outputs_retry_cost_model=measured_first_attempt \
      SHARD_VERDICT_FILE="$tok" \
      POCKETSHELL_JOURNEY_CI_SHARD_INDEX=2 \
      GITHUB_RUN_ID=30323508796 GITHUB_RUN_ATTEMPT=1 \
      GITHUB_OUTPUT="$gh" \
      bash "$CLASSIFY_BODY" 2>&1
  )"
  CLASSIFY_RC=$?
  CLASSIFY_TOKEN="$(sed -n 's/^shard_verdict=//p' "$gh")"
  CLASSIFY_REASON="$(sed -n 's/^shard_verdict_reason=//p' "$gh")"
}

# expect_classify <label> <token> <reason> <exit> — with the args after that
# forwarded to run_classify.
expect_classify() {
  local label="$1" want_token="$2" want_reason="$3" want_rc="$4"; shift 4
  run_classify "$@"
  [[ "$CLASSIFY_TOKEN" == "$want_token" ]] \
    || { printf '%s\n' "$CLASSIFY_OUT"; fail "(x/$label) token '$CLASSIFY_TOKEN', expected '$want_token'"; }
  [[ "$CLASSIFY_REASON" == "$want_reason" ]] \
    || { printf '%s\n' "$CLASSIFY_OUT"; fail "(x/$label) reason '$CLASSIFY_REASON', expected '$want_reason'"; }
  [[ "$CLASSIFY_RC" -eq "$want_rc" ]] \
    || { printf '%s\n' "$CLASSIFY_OUT"; fail "(x/$label) exit $CLASSIFY_RC, expected $want_rc"; }
  # The token FILE and the step OUTPUT must agree — the #1809 RED gate reads the
  # output, the aggregation job reads the file.
  [[ "$(head -n1 "$CLASSIFY_DIR/shard-verdict.txt")" == "$want_token" ]] \
    || fail "(x/$label) the written token file disagrees with the step output"
  grep -qx "verdict_reason=$want_reason" "$CLASSIFY_DIR/shard-verdict.txt" \
    || fail "(x/$label) the written token file lost the reason"
  echo "    $label -> $CLASSIFY_TOKEN / $CLASSIFY_REASON (exit $CLASSIFY_RC)"
}

FAILED_BOTH_SUMMARY=(
  '# Per-push CI journey suite — summary'
  'Failed BOTH attempts (`JOURNEY_FAILED` — job red):'
  '- `com.pocketshell.app.proof.MultiSessionSwitchJourneyE2eTest`'
)
TIMEOUT_SUMMARY=(
  '# Per-push CI journey suite — summary'
  'Suite step time budget exhausted — JOURNEY_STEP_TIMEOUT (issue #835 / #470 stall — job red):'
  '- `com.pocketshell.app.proof.MultiSessionSwitchJourneyE2eTest`'
)
CLEAN_SUMMARY=('# Per-push CI journey suite — summary' 'Classes exercised:')

# WITHOUT build-phase evidence: every branch keeps its own reason, and every
# token/exit code is exactly what #1458/#1809 established.
setup_classify_dir; seed_summary "${CLEAN_SUMMARY[@]}"
expect_classify "first-attempt pass"    CLEAN passed_first_attempt          0 success ''      success ''      false false true
expect_classify "retry recovered"       CLEAN infra_flake_recovered         0 failure success failure success false false true
setup_classify_dir; seed_summary "${FAILED_BOTH_SUMMARY[@]}"
expect_classify "first genuine failure" RED   first_attempt_journey_failure 1 failure failure failure failure false true  true
expect_classify "failed both attempts"  RED   journey_failure_both_attempts 1 failure failure failure failure false false true
setup_classify_dir; seed_summary "${TIMEOUT_SUMMARY[@]}"
expect_classify "first budget timeout"  RED   suite_budget_timeout          1 failure failure failure failure true  false true
expect_classify "both-failed timeout"   RED   suite_budget_timeout          1 failure failure failure failure false false true
setup_classify_dir; seed_summary "${CLEAN_SUMMARY[@]}"
expect_classify "retry wall exhausted"  INFRA retry_wall_exhausted          1 failure ''      failure ''      false false false
expect_classify "attempt cancelled"     INFRA attempt_cancelled             1 cancelled '' cancelled ''      false false true
setup_classify_dir
rm -f "$CLASSIFY_DIR/artifacts/ci-journey/summary.md"
expect_classify "no summary at all"     INFRA emulator_never_booted         1 failure failure failure failure false false true
pass "(x1) every classify branch writes the expected token + reason + exit code, executed from the real workflow body"

# #1800's captured-signature branch must still fire, and now name itself. This
# is the adjacency check: my edits sit in the same ladder.
SATURATED_CLASS="com.pocketshell.app.composer.PromptComposerSaturatedImeAnchorE2eTest"
setup_classify_dir
seed_summary \
  '# Per-push CI journey suite — summary' \
  'Failed BOTH attempts (`JOURNEY_FAILED` — job red):' \
  "- \`$SATURATED_CLASS\`"
signature_dir="$CLASSIFY_DIR/artifacts/ci-journey/class-attempts/app/Saturated--0123456789abcdef/attempt-1/android-test-outputs/androidTest-results/connected"
mkdir -p "$signature_dir"
{
  echo '<?xml version="1.0" encoding="UTF-8"?>'
  echo "<testsuite name=\"$SATURATED_CLASS\" tests=\"1\">"
  echo "  <testcase name=\"saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide\" classname=\"$SATURATED_CLASS[emulator-5554 - 15]\">"
  echo '    <failure message="java.lang.AssertionError: The real system input-method window never became visible.">at org.junit.Assert.fail(Assert.java:89)</failure>'
  echo '  </testcase>'
  echo '</testsuite>'
} > "$signature_dir/TEST-emulator-5554-15_Saturated-1.xml"
expect_classify "#1800 real-IME signature" INFRA real_ime_precondition 1 failure failure failure failure false true true
pass "(x1b) #1800's captured real-IME signature branch still fires (and now names itself)"

# WITH build-phase evidence: only the failure branches switch to the
# cold-build attribution — and their severity is UNCHANGED (still exit 1).
setup_classify_dir; seed_summary "${FAILED_BOTH_SUMMARY[@]}"; seed_build_phase_manifest
expect_classify "cold build + first failure" RED cold_build_timeout 1 failure failure failure failure false true  true
expect_classify "cold build + failed both"   RED cold_build_timeout 1 failure failure failure failure false false true
grep -q 'an attempt was cut during the Gradle BUILD phase' <<<"$CLASSIFY_OUT" \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(x2) the classify step does not announce the build-phase cause"; }
grep -q 'MultiSessionSwitchJourneyE2eTest' <<<"$CLASSIFY_OUT" \
  || fail "(x2) the build-phase annotation does not name the affected class"
setup_classify_dir; seed_summary "${TIMEOUT_SUMMARY[@]}"; seed_build_phase_manifest
expect_classify "cold build + budget timeout" RED cold_build_timeout 1 failure failure failure failure true false true
# ...while a CLEAN shard is never relabelled: the attribution explains a
# failure, it does not invent one.
setup_classify_dir; seed_summary "${CLEAN_SUMMARY[@]}"; seed_build_phase_manifest
expect_classify "cold build + clean pass"     CLEAN passed_first_attempt 0 success '' success '' false false true
pass "(x2) build-phase evidence renames the CAUSE of a red shard without changing its severity, and never touches a CLEAN one"

echo
echo "ALL TESTS PASSED: scripts/test-ci-journey-warm-build.sh"
