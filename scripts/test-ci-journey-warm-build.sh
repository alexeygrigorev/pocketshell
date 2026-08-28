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
BUILD_FAILURE="$SCRIPT_DIR/ci-journey-build-phase-failure.sh"
WRITER="$SCRIPT_DIR/ci-journey-write-shard-verdict.sh"
AGG="$SCRIPT_DIR/ci-journey-aggregate-verdict.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

for required in "$REAL_SUITE" "$CLASS_LOOP" "$WARM_HELPER" "$BUILD_PHASE" "$BUILD_FAILURE" \
  "$WRITER" "$AGG" "$WORKFLOW"; do
  [[ -f "$required" ]] || fail "missing required file: $required"
done

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

COLD_SECS=6
# Issue #2060: mirror the REAL matrix instead of a hardcoded 3. This fixture
# asserts the #1814 warm-build property once per shard, each with its own first
# class — so with a hardcoded total it would have stopped covering the shard the
# 3 -> 4 change added, and a per-shard regression there would be invisible.
SHARD_TOTAL="$(bash "$SCRIPT_DIR/ci-journey-shard-count.sh" "$WORKFLOW")" \
  || { echo "TEST FAIL: could not derive the shard count from the matrix" >&2; exit 1; }

# Issue #1862: shard membership is derived from the CLASS NAME, not the array
# index, so a fixture that needs "the class shard N runs first" must ASK the real
# selection instead of assuming array position. Drive the shipping selector so
# this stays true if the partitioning is ever changed again.
# shellcheck source=scripts/ci-journey-class-selection-functions.sh
source "$SCRIPT_DIR/ci-journey-class-selection-functions.sh"
journey_first_class_on_shard() {   # $1 = shard index, $2 = shard total
  local FQCN_PREFIX="com.pocketshell.app.proof"
  local -a JOURNEY_CLASSES=() EFFECTIVE_JOURNEY_CLASSES=()
  local i
  mapfile -t JOURNEY_CLASSES < <(
    awk '
      /^JOURNEY_CLASSES=\(/ { f = 1; next }
      /^\)/                 { f = 0 }
      f && match($0, /"[^"]+"/) { print substr($0, RSTART + 1, RLENGTH - 2) }
    ' "$REAL_SUITE"
  )
  (( ${#JOURNEY_CLASSES[@]} > 0 )) || return 1
  for i in "${!JOURNEY_CLASSES[@]}"; do
    JOURNEY_CLASSES[$i]="${JOURNEY_CLASSES[$i]/\$FQCN_PREFIX/$FQCN_PREFIX}"
  done
  POCKETSHELL_JOURNEY_CI_SHARD_TOTAL="$2" POCKETSHELL_JOURNEY_CI_SHARD_INDEX="$1" \
    select_effective_journey_classes > /dev/null
  printf '%s' "${EFFECTIVE_JOURNEY_CLASSES[0]:-}"
}

HELPERS=(
  ci-journey-suite.sh
  ci-journey-class-selection-functions.sh
  ci-journey-budget-functions.sh
  ci-journey-warm-build-functions.sh
  ci-journey-class-loop-functions.sh
  ci-journey-core-terminal-functions.sh
  ci-journey-summary-functions.sh
  ci-journey-enumeration-stall.sh
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

# Issue #2060: the loop bound is the SHIPPED total, not a literal 0 1 2. Deriving
# SHARD_TOTAL above while still enumerating three shards here would have made the
# `(ac4) verified on all $SHARD_TOTAL shards` line below assert something it had
# not done — it would print "all 6 shards" having exercised 0/1/2 only, and a
# regression on the upper legs would be invisible behind a truthful-looking pass.
for (( shard = 0; shard < SHARD_TOTAL; shard++ )); do
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
#
# Issue #1862: it is no longer the array's FIRST entry. Shard membership is now
# assigned by class-name HASH, not by array index, so `index 0 -> shard 0` no
# longer holds. Ask the REAL selection which class shard 0 runs first — that is
# what this fixture has always meant, and it stays correct under any future
# partitioning rather than encoding today's arithmetic.
first_entry="$(journey_first_class_on_shard 0 "$SHARD_TOTAL")"
WEDGE_CLASS="${first_entry##*.}"
[[ -n "$WEDGE_CLASS" ]] || fail "(ac3) could not resolve the first journey class of shard 0 from the allowlist"
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

# Issue #1840 class coverage: the #1814 phase field gained a sibling for
# NON-timeout failures. The two must never bleed into each other, so every case
# below asserts BOTH.
manifest_failure_phases() {
  find "$FIXED_REPO/artifacts/ci-journey/class-attempts" -type f -name manifest.txt \
    -exec grep -l "^class=.*$WEDGE_CLASS$" {} + 2>/dev/null \
    | tr '\n' '\0' \
    | xargs -r -0 sed -n 's/^attempt_failure_phase=//p' | sort -u | tr '\n' ' '
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
failure_phases="$(manifest_failure_phases)"
[[ "$failure_phases" == "not_applicable " ]] \
  || fail "(b1) an outer TIMEOUT leaked into attempt_failure_phase ('$failure_phases') — #1840's field must stay not_applicable here"
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
failure_phases="$(manifest_failure_phases)"
[[ "$failure_phases" == "not_applicable " ]] \
  || fail "(b2) an outer TIMEOUT leaked into attempt_failure_phase ('$failure_phases')"
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
# G6/#1840: a GENUINE journey failure ran instrumentation and left JUnit XML, so
# it must be attributed to instrumentation and never to the build.
failure_phases="$(manifest_failure_phases)"
[[ "$failure_phases" == "instrumentation " ]] \
  || fail "(b3) a genuine journey failure recorded attempt_failure_phase '$failure_phases', expected only 'instrumentation'"
grep -qx 'build_phase_failure_attempts=0' \
  <<<"$(bash "$REPO_ROOT/scripts/ci-journey-build-phase-failure.sh" "$FIXED_REPO/artifacts")" \
  || fail "(b3) the #1840 build-level scanner claimed evidence for a genuine journey failure"
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
# AGG_STEP_SUMMARY (optional): where the reducer should write its
# $GITHUB_STEP_SUMMARY block, for the cases that assert on it. Threaded through
# this ONE helper rather than a second inline invocation, so the sandbox keeps a
# single hardcoded shard total (scripts/check-journey-shard-literals.sh).
AGG_STEP_SUMMARY=""
run_agg() {
  AGG_OUT="$(EXPECTED_SHARDS=3 GITHUB_STEP_SUMMARY="$AGG_STEP_SUMMARY" GITHUB_RUN_ATTEMPT=1 \
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
  cp "$WRITER" "$BUILD_PHASE" "$BUILD_FAILURE" \
    "$SCRIPT_DIR/ci-journey-enumeration-stall.sh" \
    "$SCRIPT_DIR/ci-journey-retry-denial-notice.sh" \
    "$SCRIPT_DIR/ci-journey-build-attribution-notice.sh" \
    "$SCRIPT_DIR/ci-journey-genuine-journey-failure.sh" \
    "$SCRIPT_DIR/ci-journey-shard-signature-verdict.sh" \
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

# Issue #1840's sibling shape, needed by the #2374 precedence cases below: an
# attempt whose Gradle BUILD died outright (`attempt_failure_phase=build`) rather
# than being cut by the wall cap mid-build.
seed_build_failure_manifest() {
  local dir="$CLASSIFY_DIR/artifacts/ci-journey/class-attempts/app/Wedge--0123456789abcdef/attempt-2"
  mkdir -p "$dir"
  printf 'class=com.pocketshell.app.proof.MultiSessionSwitchJourneyE2eTest\nprimary_classification=failure\nraw_junit_count=0\nattempt_failure_phase=build\n' \
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
  # Issue #2374: this used to name ONE helper. The classify step's inline shell
  # keeps being extracted into scripts/ (that is check-file-size-hygiene.sh's
  # prescribed remedy for the workflow headroom), and a helper this sandbox does
  # not copy then fails with a bare "No such file or directory" that this runner
  # does NOT propagate — the body has no `-e`. So the guard now catches ANY
  # missing scripts/ helper, not a hardcoded list that goes stale on the next
  # extraction.
  if grep -Eq 'scripts/[A-Za-z0-9._-]+: No such file or directory' <<<"$CLASSIFY_OUT"; then
    printf '%s\n' "$CLASSIFY_OUT"
    fail "(x) the extracted classify body could not run a production scripts/ helper — add it to setup_classify_dir's copy list"
  fi
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
  'Suite step time budget exhausted — JOURNEY_STEP_TIMEOUT (issue #835 hard RED — exact cause required):'
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

# #1800's captured-signature branch must still fire for a positively identified
# foreign focus owner, and now name itself. Exercise the REAL workflow body for
# both sides of #1882's owner boundary: app-owned and unsafe/ambiguous evidence
# must remain RED rather than inheriting the foreign-owner relief valve.
SATURATED_CLASS="com.pocketshell.app.composer.PromptComposerSaturatedImeAnchorE2eTest"
SATURATED_KEY="$(
  bash -c 'source "$1"; journey_class_artifact_key "$2"' \
    _ "$SCRIPT_DIR/ci-journey-budget-functions.sh" "$SATURATED_CLASS"
)" || fail "(x/#1800) could not derive the production artifact key for $SATURATED_CLASS"
[[ "$SATURATED_KEY" == "$SATURATED_CLASS"--[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f] ]] \
  || fail "(x/#1800) malformed production artifact key: $SATURATED_KEY"
seed_real_ime_classify_fixture() {
  local owner_detail="$1"
  local signature_dir
  setup_classify_dir
  seed_summary \
    '# Per-push CI journey suite — summary' \
    'Failed BOTH attempts (`JOURNEY_FAILED` — job red):' \
    "- \`$SATURATED_CLASS\`"
  signature_dir="$CLASSIFY_DIR/artifacts/ci-journey/class-attempts/app/$SATURATED_KEY/attempt-1/android-test-outputs/app/build/outputs/androidTest-results/connected/debug"
  mkdir -p "$signature_dir"
  {
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo "<testsuite name=\"$SATURATED_CLASS\" tests=\"1\">"
    echo "  <testcase name=\"saturatedDraftAndAllActionsStayReachableWithRealImeThenRestoreAfterActualHide\" classname=\"${SATURATED_CLASS}[emulator-5554 - 15]\">"
    echo "    <failure message=\"java.lang.AssertionError: The real system input-method window never became visible. $owner_detail\">at org.junit.Assert.fail(Assert.java:89)</failure>"
    echo '  </testcase>'
    echo '</testsuite>'
  } > "$signature_dir/TEST-emulator-5554-15_Saturated-1.xml"
}

seed_real_ime_classify_fixture \
  'app_window_focused=false active_window_pkg=com.google.android.apps.nexuslauncher'
expect_classify "#1800 real-IME signature" INFRA real_ime_precondition 1 failure failure failure failure false true true
pass "(x1b) #1800's captured real-IME signature fires only with an explicit foreign owner"

seed_real_ime_classify_fixture \
  'app_window_focused=false active_window_pkg=com.pocketshell.app.i1882'
expect_classify "#1882 app-owned real-IME failure" RED first_attempt_journey_failure 1 failure failure failure failure false true true

for unsafe_owner_case in \
  "missing:" \
  "malformed:active_window_pkg=not/a/package" \
  "ambiguous:active_window_pkg=android"
do
  unsafe_owner_label="${unsafe_owner_case%%:*}"
  unsafe_owner_detail="${unsafe_owner_case#*:}"
  seed_real_ime_classify_fixture "$unsafe_owner_detail"
  expect_classify \
    "#1882 $unsafe_owner_label owner evidence" \
    RED first_attempt_journey_failure 1 \
    failure failure failure failure false true true
done
pass "(x1c) app-owned, missing, malformed, and ambiguous owner evidence stays RED through the real workflow body"

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

# (x3) ISSUE #2374 — a build attribution may only speak for the classes it
# NAMES. Every (x2) case above is the COHERENT shape: the one class listed under
# `Failed BOTH attempts` IS the build-phase victim, so `cold_build_timeout` is
# the whole truth and stays. The scheduled full-suite run 33157272170 shard 2 was
# the incoherent shape: EIGHT unrelated journey classes failed both attempts and
# the LAST class's attempts were cut mid-Gradle-build with 289s of budget left.
# The shard reported `cold_build_timeout` — "investigate the build, not the
# journey" — while eight genuine product failures sat in the same summary, and
# the batch was triaged as a recurrence of #1814.
BUILD_VICTIM='com.pocketshell.app.proof.MultiSessionSwitchJourneyE2eTest'
UNRELATED_FAILURE='com.pocketshell.app.proof.ColdRestoreGoneSessionNoResurrectE2eTest'
setup_classify_dir
seed_summary \
  '# Per-push CI journey suite — summary' \
  'Failed BOTH attempts (`JOURNEY_FAILED` — job red):' \
  "- \`$UNRELATED_FAILURE\`" \
  "- \`$BUILD_VICTIM\`"
seed_build_phase_manifest
expect_classify "#2374 build phase + unrelated genuine failure" \
  RED first_attempt_journey_failure 1 failure failure failure failure false true true
expect_classify "#2374 build phase + unrelated failed both" \
  RED journey_failure_both_attempts 1 failure failure failure failure false false true
# The build annotation must STILL fire — the fix changes who speaks for the
# shard's reason, never whether the build artefact is reported at all.
grep -q 'an attempt was cut during the Gradle BUILD phase' <<<"$CLASSIFY_OUT" \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(x3) the build-phase annotation stopped firing; #1814's evidence must survive the #2374 precedence change"; }
grep -q "genuine_journey_failure_classes=$UNRELATED_FAILURE" <<<"$CLASSIFY_OUT" \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(x3) the classify step does not name which failed-both class the build attribution failed to explain"; }
pass "(x3) #2374: a build-phase artefact no longer speaks for unrelated genuine failures, and its own annotation still fires"

# (x4) THE #1840 CONTROL, and the reason the test is set SUBTRACTION rather than
# "does the summary have a failed-both section". A class whose Gradle BUILD DIED
# is itself listed under `Failed BOTH attempts` — that IS #1840. If the shard's
# only failed-both class is the build victim, `build_level_failure` must survive.
setup_classify_dir
seed_summary \
  '# Per-push CI journey suite — summary' \
  'Failed BOTH attempts (`JOURNEY_FAILED` — job red):' \
  "- \`$BUILD_VICTIM\`"
seed_build_failure_manifest
expect_classify "#1840 preserved: only the build victim failed both" \
  RED build_level_failure 1 failure failure failure failure false false true
pass "(x4) #1840 preserved: a shard whose only failed-both class IS the build victim still reports build_level_failure"

# (x5) ...and the same shard PLUS one unrelated genuine failure must flip, or
# (x4) would be satisfied by simply never applying the #2374 subtraction.
setup_classify_dir
seed_summary \
  '# Per-push CI journey suite — summary' \
  'Failed BOTH attempts (`JOURNEY_FAILED` — job red):' \
  "- \`$BUILD_VICTIM\`" \
  "- \`$UNRELATED_FAILURE\`"
seed_build_failure_manifest
expect_classify "#2374 build level + unrelated genuine failure" \
  RED journey_failure_both_attempts 1 failure failure failure failure false false true
grep -q 'an attempt died at the Gradle BUILD level' <<<"$CLASSIFY_OUT" \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(x5) #1840's annotation stopped firing"; }
pass "(x5) the subtraction is live for #1840 too: one unrelated genuine failure flips the reason while the annotation stays"

# ---------------------------------------------------------------------------
# (x6)/(x7) ISSUE #2374, ROUND 2 — the discriminator must read the failed-both
# SECTION, not "every `- ` bullet to EOF".
#
# THE DEFECT THIS CLOSES. summary.md keeps writing after the failed-both
# section: #2355's `Quarantined failures (non-blocking …)` and #2143's
# `Shared SSH/tmux fixture was WEDGED during these classes …`, both with `- `
# bullets. An unterminated scan reads them as failed-both classes, so a
# QUARANTINED failure — which #2355 deliberately keeps OUT of the blocking
# section, and which is live on `main` today — subtracts to a "genuine journey
# failure" and flips `build_level_failure`/`cold_build_timeout` back to a
# product-defect reason. That is #1840's own bug returning through the very
# change that exists to preserve it, and it re-couples this classifier to the
# section #2355 decoupled it from.
#
# THE FIXTURES ARE PRODUCED BY THE REAL SUMMARY WRITER, not hand-typed: a
# handwritten section header drifts silently from
# ci-journey-summary-functions.sh and the guard then proves nothing. Same
# standalone-driver method as scripts/test-journey-quarantine-non-blocking.sh.
SUMMARY_FN="$SCRIPT_DIR/ci-journey-summary-functions.sh"
CORE_TERMINAL_FN="$SCRIPT_DIR/ci-journey-core-terminal-functions.sh"
for required in "$SUMMARY_FN" "$CORE_TERMINAL_FN" "$SCRIPT_DIR/lib/journey-quarantine.sh"; do
  [[ -f "$required" ]] || fail "(x6) missing required file: $required"
done

# real_summary <out.md> <quarantine-file-content|""> <wedged-csv|"">
#              <failed-class>...
real_summary() {
  local out="$1" quarantine="$2" wedged_csv="$3"; shift 3
  local -a failed=("$@") wedged=()
  [[ -z "$wedged_csv" ]] || IFS=',' read -r -a wedged <<<"$wedged_csv"
  local d; d="$(mktemp -d "$SANDBOX/realsummary-XXXXXX")"
  mkdir -p "$d/artifacts/ci-journey"
  local qfile="$d/journey-quarantine.txt"
  printf '%s' "$quarantine" > "$qfile"
  [[ -s "$qfile" ]] || rm -f "$qfile"
  {
    echo "source '$CORE_TERMINAL_FN'"
    echo "source '$SUMMARY_FN'"
    echo "REPO_ROOT='$REPO_ROOT'"
    echo "POCKETSHELL_JOURNEY_QUARANTINE_FILE='$qfile'"
    echo "SUITE_START=0; STEP_TIMEOUT_HIT=0"
    echo "RECOVERED_CLASSES=(); PASSED_FIRST_TRY=()"
    echo "BUDGET_TIMEOUT_CLASSES=(); BUILD_PHASE_TIMEOUT_ATTEMPTS=(); BUILD_PHASE_FAILURE_ATTEMPTS=()"
    printf 'FIXTURE_WEDGED_CLASSES=(%s)\n' "${wedged[*]@Q}"
    echo "EFFECTIVE_JOURNEY_CLASSES=(${failed[*]@Q})"
    printf 'FAILED_CLASSES=(%s)\n' "${failed[*]@Q}"
    echo "JOURNEY_CI_SHARD_INDEX=2; JOURNEY_CI_SHARD_TOTAL=$SHARD_TOTAL"
    echo "JOURNEY_WARM_BUILD_STATUS=ok; JOURNEY_WARM_BUILD_ELAPSED=1"
    echo "JOURNEY_STEP_BUDGET_SECS=4200"
    echo "SUMMARY='$d/artifacts/ci-journey/summary.md'"
    echo "ARTIFACT_DIR='$d/artifacts/ci-journey'"
    echo "finish_ci_journey_suite"
  } > "$d/driver.sh"
  bash "$d/driver.sh" > "$d/run.log" 2>&1
  [[ -f "$d/artifacts/ci-journey/summary.md" ]] \
    || { cat "$d/run.log"; fail "(x6) the real summary writer produced no summary.md"; }
  cp "$d/artifacts/ci-journey/summary.md" "$out"
}

QUARANTINED_CLASS='com.pocketshell.app.tmux.TmuxInSessionNewSessionCollisionDockerTest'
QUARANTINE_ROW="$(printf '%s\t#2391\t2026-08-28\t2099-01-01\ttimes out on the CI AVD\n' "$QUARANTINED_CLASS")"

# (x6) build victim failed both + a QUARANTINED class also failed both.
REAL_QUARANTINE_SUMMARY="$SANDBOX/real-summary-quarantine.md"
real_summary "$REAL_QUARANTINE_SUMMARY" "$QUARANTINE_ROW" "" \
  "$BUILD_VICTIM" "$QUARANTINED_CLASS"
# Non-vacuity: the fixture MUST actually contain both sections, or (x6) would
# pass by simply never exercising the bug.
grep -q '^Failed BOTH attempts' "$REAL_QUARANTINE_SUMMARY" \
  || { cat "$REAL_QUARANTINE_SUMMARY"; fail "(x6) fixture has no failed-both section"; }
grep -q '^Quarantined failures' "$REAL_QUARANTINE_SUMMARY" \
  || { cat "$REAL_QUARANTINE_SUMMARY"; fail "(x6) fixture has no #2355 quarantine section — it cannot reproduce the defect"; }
grep -q -- "- \`$QUARANTINED_CLASS\` (tracked:" "$REAL_QUARANTINE_SUMMARY" \
  || { cat "$REAL_QUARANTINE_SUMMARY"; fail "(x6) the quarantine bullet is not in the real writer's shape"; }

# The helper, driven directly: the quarantined bullet must not be read at all.
GENUINE_OUT="$(bash "$SCRIPT_DIR/ci-journey-genuine-journey-failure.sh" \
  "$REAL_QUARANTINE_SUMMARY" "" "$BUILD_VICTIM" 2>&1)"
grep -qx 'genuine_journey_failure=false' <<<"$GENUINE_OUT" \
  || { printf '%s\n' "$GENUINE_OUT"; fail "(x6) a QUARANTINED failure was read as a genuine journey failure — the scan is not terminated at the section end"; }
grep -qx 'genuine_journey_failure_classes=' <<<"$GENUINE_OUT" \
  || { printf '%s\n' "$GENUINE_OUT"; fail "(x6) the quarantine section leaked into genuine_journey_failure_classes"; }

# ...and through the REAL classify body: #1840's and #1814's reasons survive.
setup_classify_dir
cp "$REAL_QUARANTINE_SUMMARY" "$CLASSIFY_DIR/artifacts/ci-journey/summary.md"
seed_build_failure_manifest
expect_classify "#2374 build level + quarantined failure" \
  RED build_level_failure 1 failure failure failure failure false false true
# The failed-both branch's OWN `Failing class(es):` annotation reads the same
# section with the same awk. It must not name the quarantined class either —
# that would print a deliberately non-blocking class as this shard's cause.
grep -q 'genuine test failure' <<<"$CLASSIFY_OUT" \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "(x6) the failed-both annotation did not fire, so its class list is untested"; }
if grep -q "$QUARANTINED_CLASS" <<<"$CLASSIFY_OUT"; then
  printf '%s\n' "$CLASSIFY_OUT"
  fail "(x6) the classify step's 'Failing class(es)' annotation names a QUARANTINED class as the genuine cause"
fi
setup_classify_dir
cp "$REAL_QUARANTINE_SUMMARY" "$CLASSIFY_DIR/artifacts/ci-journey/summary.md"
seed_build_phase_manifest
expect_classify "#2374 build phase + quarantined failure" \
  RED cold_build_timeout 1 failure failure failure failure false true true
pass "(x6) #2374: a QUARANTINED failing class (live on main today) never counts as a genuine journey failure — #1814/#1840 attribution preserved"

# (x7) the same for #2143's fixture-wedged section, which also follows the
# failed-both section with `- ` bullets. The wedged class here is NOT the build
# victim, so an unterminated scan would leak it as an unrelated genuine failure.
WEDGED_ONLY_CLASS='com.pocketshell.app.tmux.TmuxWedgeVictimDockerTest'
REAL_WEDGED_SUMMARY="$SANDBOX/real-summary-wedged.md"
real_summary "$REAL_WEDGED_SUMMARY" "" "$WEDGED_ONLY_CLASS" "$BUILD_VICTIM"
grep -q '^Shared SSH/tmux fixture was WEDGED during these classes' "$REAL_WEDGED_SUMMARY" \
  || { cat "$REAL_WEDGED_SUMMARY"; fail "(x7) fixture has no #2143 wedged section — it cannot reproduce the defect"; }
GENUINE_OUT="$(bash "$SCRIPT_DIR/ci-journey-genuine-journey-failure.sh" \
  "$REAL_WEDGED_SUMMARY" "" "$BUILD_VICTIM" 2>&1)"
grep -qx 'genuine_journey_failure=false' <<<"$GENUINE_OUT" \
  || { printf '%s\n' "$GENUINE_OUT"; fail "(x7) #2143's wedged section was read as a genuine journey failure"; }
setup_classify_dir
cp "$REAL_WEDGED_SUMMARY" "$CLASSIFY_DIR/artifacts/ci-journey/summary.md"
seed_build_failure_manifest
expect_classify "#2374 build level + #2143 wedged section" \
  RED build_level_failure 1 failure failure failure failure false false true
pass "(x7) #2374: #2143's wedged section never counts as a genuine journey failure either — the class is closed, not the one instance"

# (x8) THE NON-VACUITY CONTROL for (x6)/(x7): the terminator must not have been
# implemented by simply never reading anything. A REAL summary whose failed-both
# section names an unrelated class the build attribution does not explain must
# still flip the reason — and the class it reports must be a bare FQCN, not a
# bullet's trailing metadata.
REAL_MIXED_SUMMARY="$SANDBOX/real-summary-mixed.md"
real_summary "$REAL_MIXED_SUMMARY" "$QUARANTINE_ROW" "" \
  "$BUILD_VICTIM" "$UNRELATED_FAILURE" "$QUARANTINED_CLASS"
GENUINE_OUT="$(bash "$SCRIPT_DIR/ci-journey-genuine-journey-failure.sh" \
  "$REAL_MIXED_SUMMARY" "" "$BUILD_VICTIM" 2>&1)"
grep -qx 'genuine_journey_failure=true' <<<"$GENUINE_OUT" \
  || { printf '%s\n' "$GENUINE_OUT"; fail "(x8) the terminator swallowed a real unrelated failure — the fix would be vacuous"; }
grep -qx "genuine_journey_failure_classes=$UNRELATED_FAILURE" <<<"$GENUINE_OUT" \
  || { printf '%s\n' "$GENUINE_OUT"; fail "(x8) genuine_journey_failure_classes is not exactly the one unrelated FQCN"; }
setup_classify_dir
cp "$REAL_MIXED_SUMMARY" "$CLASSIFY_DIR/artifacts/ci-journey/summary.md"
seed_build_failure_manifest
expect_classify "#2374 build level + quarantined + one unrelated failure" \
  RED journey_failure_both_attempts 1 failure failure failure failure false false true
pass "(x8) with a quarantined class AND an unrelated genuine failure in the same real summary, only the unrelated FQCN is reported and the reason still flips"

# (x9) #1827's core-terminal bullets carry `(<label> — status <X>)` after the
# class. They belong to the failed-both section and must still count, but what
# is REPORTED must be the FQCN alone — the set subtraction below is a name
# comparison and a bullet's metadata can never match a build attribution's CSV.
CORE_BULLET_SUMMARY="$SANDBOX/core-bullet-summary.md"
{
  echo '# Per-push CI journey suite — summary'
  echo
  echo 'Failed BOTH attempts (`JOURNEY_FAILED` — job red):'
  echo "- \`$BUILD_VICTIM\` (surface repaint proof — status FAIL)"
} > "$CORE_BULLET_SUMMARY"
GENUINE_OUT="$(bash "$SCRIPT_DIR/ci-journey-genuine-journey-failure.sh" \
  "$CORE_BULLET_SUMMARY" "" "$BUILD_VICTIM" 2>&1)"
grep -qx 'genuine_journey_failure=false' <<<"$GENUINE_OUT" \
  || { printf '%s\n' "$GENUINE_OUT"; fail "(x9) a bullet's trailing metadata defeated the #1814/#1840 name subtraction"; }
pass "(x9) a failed-both bullet is reduced to its FQCN, so the #1814/#1840 subtraction still matches"

# ---------------------------------------------------------------------------
# (c5) ISSUE #2374 — #1814's AGGREGATE rollup must survive the precedence change.
#
# `verdict_reason_for`'s output IS the token's `verdict_reason`, and (c1) shows
# the aggregate derives its cold-build rollup from that field. So the moment a
# build attribution stops outranking an unrelated genuine failure, the mixed
# shard writes `verdict_reason=first_attempt_journey_failure` and #1814's
# evidence VANISHES from the aggregate and the step summary — the artifact a
# release owner reads — surviving only in that one shard's job log. The
# attribution therefore rides its own `build_attribution` token field.
echo
echo "== #2374 the build attribution survives in the aggregate (issue #1814 rollup) =="

# End to end: the token used here is the one the REAL classify body just wrote
# for the mixed shape, not a hand-written one.
setup_classify_dir
seed_summary \
  '# Per-push CI journey suite — summary' \
  'Failed BOTH attempts (`JOURNEY_FAILED` — job red):' \
  "- \`$UNRELATED_FAILURE\`" \
  "- \`$BUILD_VICTIM\`"
seed_build_phase_manifest
expect_classify "#2374 mixed shard for the aggregate" \
  RED first_attempt_journey_failure 1 failure failure failure failure false true true
grep -qx 'build_attribution=cold_build_timeout' "$CLASSIFY_DIR/shard-verdict.txt" \
  || { cat "$CLASSIFY_DIR/shard-verdict.txt"; fail "(c5) the mixed shard's token does not carry the build attribution separately"; }
d="$SANDBOX/verdicts-mixed"; rm -rf "$d"; mkdir -p "$d/emulator-journey-verdict-shard-2"
cp "$CLASSIFY_DIR/shard-verdict.txt" "$d/emulator-journey-verdict-shard-2/shard-verdict.txt"
write_token "$d" 0 CLEAN passed_first_attempt
write_token "$d" 1 CLEAN passed_first_attempt
STEP_SUMMARY_FILE="$SANDBOX/step-summary-mixed.md"; : > "$STEP_SUMMARY_FILE"
AGG_STEP_SUMMARY="$STEP_SUMMARY_FILE"
run_agg "$d"
AGG_STEP_SUMMARY=""
[[ "$AGG_VERDICT" == "RED" && "$AGG_RC" -eq 1 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(c5) the mixed shape must stay RED/exit1, got $AGG_VERDICT/exit$AGG_RC"; }
COLD_NOTICES="$(grep -c 'includes a cold-BUILD-phase timeout' <<<"$AGG_OUT")"
[[ "$COLD_NOTICES" -eq 1 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(c5) build-phase evidence + an unrelated genuine failure produced $COLD_NOTICES #1814 rollup notice(s), expected 1 — the release owner's artifact lost the build-cost evidence"; }
grep -q 'Cold-build-phase timeout (issue #1814)' "$STEP_SUMMARY_FILE" \
  || { cat "$STEP_SUMMARY_FILE"; fail "(c5) the STEP SUMMARY lost the #1814 line for the mixed shape"; }
# The reason is still the genuine failure — the fix restores the evidence, it
# does not restore the misattribution.
grep -q 'shard 2: RED .*reason first_attempt_journey_failure' <<<"$AGG_OUT" \
  || { printf '%s\n' "$AGG_OUT"; fail "(c5) the aggregate no longer reports the genuine-failure reason"; }
pass "(c5) #2374: a mixed build-artefact + genuine-failure shard keeps BOTH — #1814's rollup notice and step-summary line survive, and the verdict reason still names the real failure"

# (c6) ...and the notice is still MEANINGFUL: a shard with no build evidence at
# all must not acquire one. (c2) pins the legacy/unstamped path; this pins the
# explicitly-stamped `none`.
d="$SANDBOX/verdicts-no-build"; rm -rf "$d"; mkdir -p "$d/emulator-journey-verdict-shard-2"
setup_classify_dir; seed_summary "${FAILED_BOTH_SUMMARY[@]}"
expect_classify "#2374 no build evidence at all" \
  RED first_attempt_journey_failure 1 failure failure failure failure false true true
grep -qx 'build_attribution=none' "$CLASSIFY_DIR/shard-verdict.txt" \
  || { cat "$CLASSIFY_DIR/shard-verdict.txt"; fail "(c6) a shard with no build evidence must stamp build_attribution=none"; }
cp "$CLASSIFY_DIR/shard-verdict.txt" "$d/emulator-journey-verdict-shard-2/shard-verdict.txt"
write_token "$d" 0 CLEAN passed_first_attempt
write_token "$d" 1 CLEAN passed_first_attempt
run_agg "$d"
if grep -q 'includes a cold-BUILD-phase timeout' <<<"$AGG_OUT"; then
  printf '%s\n' "$AGG_OUT"
  fail "(c6) the #1814 rollup fired for a shard with no build evidence — the label would be meaningless"
fi
[[ "$AGG_VERDICT" == "RED" && "$AGG_RC" -eq 1 ]] \
  || fail "(c6) severity changed: got $AGG_VERDICT/exit$AGG_RC"
pass "(c6) the rollup stays meaningful: an explicitly stamped build_attribution=none never produces the #1814 notice"

# (c7) the new field fails SOFT, exactly like the reason and the denial class: a
# malformed stamp degrades to `none` and never costs the shard its token.
d="$SANDBOX/verdicts-bad-attribution"; rm -rf "$d"; mkdir -p "$d/out"
SHARD_VERDICT_FILE="$d/out/shard-verdict.txt" POCKETSHELL_JOURNEY_CI_SHARD_INDEX=0 \
  GITHUB_RUN_ID=1 GITHUB_RUN_ATTEMPT=1 GITHUB_OUTPUT="$d/out/gh.txt" \
  SHARD_BUILD_ATTRIBUTION='Not A Class!' \
  bash "$WRITER" RED first_attempt_journey_failure > /dev/null \
  || fail "(c7) a malformed build attribution must not fail the write"
grep -qx 'build_attribution=none' "$d/out/shard-verdict.txt" \
  || fail "(c7) a malformed build attribution must degrade to 'none'"
grep -qx 'shard_verdict=RED' "$d/out/gh.txt" \
  || fail "(c7) the RED gate output must survive a malformed build attribution"
[[ "$(head -n1 "$d/out/shard-verdict.txt")" == "RED" ]] \
  || fail "(c7) line 1 must stay the bare verdict token"
pass "(c7) a malformed build_attribution degrades to 'none' and never costs the token"

# (c8) ISSUE #2374 — the rollup must not leak onto a GREEN run.
#
# `SHARD_BUILD_ATTRIBUTION` is computed and exported BEFORE every write_verdict
# branch, and scripts/ci-journey-build-phase-timeout.sh deliberately reads the
# PRESERVED first-attempt tree so a retry cannot hide what happened on attempt 1.
# Both are correct on their own — but together they mean a shard whose attempt 1
# was cut mid-Gradle-build and whose RETRY THEN PASSED writes
# `CLEAN` + `build_attribution=cold_build_timeout`. If the rollup keys on the
# attribution alone, that green shard puts an "#1814 … Investigate the build
# cost" heading into the aggregate and into the step summary a release owner
# reads before tagging `validated-rc` — a fresh instance of exactly the misread
# this issue exists to remove, on the good case.
#
# The attribution is DIAGNOSTIC, not a verdict: a shard that self-healed via its
# retry succeeded. So the rollup fires only where the build cost actually
# contributed to a RED outcome. The evidence is not lost — the shard's own
# `::warning … (#1814)` from ci-journey-build-attribution-notice.sh still fires
# on its own evidence, unconditionally, in that shard's job log.
d="$SANDBOX/verdicts-clean-recovered"; rm -rf "$d"
mkdir -p "$d/emulator-journey-verdict-shard-2"
setup_classify_dir
seed_summary "${CLEAN_SUMMARY[@]}"
seed_build_phase_manifest
expect_classify "#2374 build-phase evidence on attempt 1, retry PASSED" \
  CLEAN infra_flake_recovered 0 failure success failure success false false true
# Non-vacuity: this case is worthless unless the token really does carry the
# attribution on a CLEAN verdict. Assert the shape before asserting the rollup.
grep -qx 'build_attribution=cold_build_timeout' "$CLASSIFY_DIR/shard-verdict.txt" \
  || { cat "$CLASSIFY_DIR/shard-verdict.txt"; fail "(c8) the fixture does not reproduce the scenario — a recovered shard's token carries no build attribution, so this case proves nothing"; }
cp "$CLASSIFY_DIR/shard-verdict.txt" "$d/emulator-journey-verdict-shard-2/shard-verdict.txt"
write_token "$d" 0 CLEAN passed_first_attempt
write_token "$d" 1 CLEAN passed_first_attempt
STEP_SUMMARY_FILE="$SANDBOX/step-summary-clean-recovered.md"; : > "$STEP_SUMMARY_FILE"
AGG_STEP_SUMMARY="$STEP_SUMMARY_FILE"
run_agg "$d"
AGG_STEP_SUMMARY=""
[[ "$AGG_VERDICT" == "CLEAN" && "$AGG_RC" -eq 0 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(c8) a recovered shard must keep the aggregate CLEAN/exit0, got $AGG_VERDICT/exit$AGG_RC"; }
COLD_NOTICES="$(grep -c 'includes a cold-BUILD-phase timeout' <<<"$AGG_OUT")"
[[ "$COLD_NOTICES" -eq 0 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(c8) a CLEAN shard whose retry recovered fired $COLD_NOTICES #1814 rollup notice(s), expected 0 — a GREEN run's aggregate now carries an 'investigate the build cost' heading"; }
if grep -q 'Cold-build-phase timeout (issue #1814)' "$STEP_SUMMARY_FILE"; then
  cat "$STEP_SUMMARY_FILE"
  fail "(c8) the GREEN run's STEP SUMMARY — the artifact a release owner reads before tagging validated-rc — gained an #1814 build-cost heading"
fi
pass "(c8) #2374: a shard whose attempt-1 build hiccup was RECOVERED by its retry never puts an #1814 rollup on the GREEN aggregate"

# (c9) the (c8) gate must be the VERDICT, not the absence of evidence: an INFRA
# shard carrying the same attribution is also not a build-cost RED and must stay
# silent, while (c5)'s RED shape — re-asserted here from an independently written
# token — still fires. Without this pair, (c8) could be satisfied by deleting the
# rollup outright and (c5) alone would not notice the difference between "fires
# on RED" and "fires on anything not CLEAN".
d="$SANDBOX/verdicts-infra-attribution"; rm -rf "$d"; mkdir -p "$d/out"
SHARD_VERDICT_FILE="$d/out/shard-verdict.txt" POCKETSHELL_JOURNEY_CI_SHARD_INDEX=2 \
  GITHUB_RUN_ID=30323508796 GITHUB_RUN_ATTEMPT=1 GITHUB_OUTPUT="" \
  SHARD_BUILD_ATTRIBUTION=cold_build_timeout \
  bash "$WRITER" INFRA attempt_cancelled > /dev/null \
  || fail "(c9) the writer refused an INFRA token carrying a build attribution"
mkdir -p "$d/emulator-journey-verdict-shard-2"
cp "$d/out/shard-verdict.txt" "$d/emulator-journey-verdict-shard-2/shard-verdict.txt"
write_token "$d" 0 CLEAN passed_first_attempt
write_token "$d" 1 CLEAN passed_first_attempt
run_agg "$d"
[[ "$AGG_VERDICT" == "RE-RUN" && "$AGG_RC" -eq 0 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(c9) an INFRA shard must stay RE-RUN/exit0, got $AGG_VERDICT/exit$AGG_RC"; }
if grep -q 'includes a cold-BUILD-phase timeout' <<<"$AGG_OUT"; then
  printf '%s\n' "$AGG_OUT"
  fail "(c9) an environmental INFRA shard fired the #1814 build-cost rollup — that verdict is a re-run signal, not a build-cost failure"
fi
d="$SANDBOX/verdicts-red-attribution"; rm -rf "$d"; mkdir -p "$d/out"
SHARD_VERDICT_FILE="$d/out/shard-verdict.txt" POCKETSHELL_JOURNEY_CI_SHARD_INDEX=2 \
  GITHUB_RUN_ID=30323508796 GITHUB_RUN_ATTEMPT=1 GITHUB_OUTPUT="" \
  SHARD_BUILD_ATTRIBUTION=cold_build_timeout \
  bash "$WRITER" RED first_attempt_journey_failure > /dev/null \
  || fail "(c9) the writer refused a RED token carrying a build attribution"
mkdir -p "$d/emulator-journey-verdict-shard-2"
cp "$d/out/shard-verdict.txt" "$d/emulator-journey-verdict-shard-2/shard-verdict.txt"
write_token "$d" 0 CLEAN passed_first_attempt
write_token "$d" 1 CLEAN passed_first_attempt
run_agg "$d"
COLD_NOTICES="$(grep -c 'includes a cold-BUILD-phase timeout' <<<"$AGG_OUT")"
[[ "$COLD_NOTICES" -eq 1 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(c9) a RED shard carrying the attribution produced $COLD_NOTICES #1814 notice(s), expected 1 — (c8)'s gate deleted the rollup instead of scoping it"; }
pass "(c9) the #1814 rollup keys on a genuinely RED verdict: INFRA stays silent, RED still fires"

echo
echo "ALL TESTS PASSED: scripts/test-ci-journey-warm-build.sh"
