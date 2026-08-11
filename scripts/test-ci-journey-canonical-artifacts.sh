#!/usr/bin/env bash
# Issue #2093: a whole-shard cold-boot retry must replace the canonical journey
# artifact tree, while the workflow's preserved first-cold-boot tree remains.
#
# This fixture runs the REAL ci-journey-suite.sh twice with deterministic
# Gradle/adb stubs. Cold boot 1 fails PromptComposerSaturatedImeAnchorE2eTest on
# both per-class attempts. The production workflow snapshot path preserves that
# tree. Cold boot 2 passes the same selector on attempt 1. The final canonical
# tree must contain only cold boot 2; in particular, its highest attempt cannot
# be cold boot 1's stale failed attempt 2.
#
# A copied-tree mutant restores the pre-fix `mkdir -p` initialization. It must
# reproduce the stale attempt-2 coexistence while every preservation/reporting
# assertion remains green. That selectivity makes the canonical-tree assertion,
# not a structural grep, the load-bearing regression guard (D32/G6).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd -P)"
SUITE="$SCRIPT_DIR/ci-journey-suite.sh"
PRESERVE_HELPER="$SCRIPT_DIR/ci-journey-preserve-android-outputs.sh"
WORKFLOW="$REPO_ROOT/.github/workflows/tests.yml"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

for required in "$SUITE" "$PRESERVE_HELPER" "$WORKFLOW"; do
  [[ -f "$required" ]] || fail "missing required production file: $required"
done

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

TARGET_CLASS="com.pocketshell.app.composer.PromptComposerSaturatedImeAnchorE2eTest"
# shellcheck source=scripts/ci-journey-class-selection-functions.sh
source "$SCRIPT_DIR/ci-journey-class-selection-functions.sh"
# Use the full FNV-1a/32 hash domain as a fixture-only selector modulus. This
# makes the production selector isolate the target's hash without inventing a
# pretend CI shard count that can drift from the workflow-owned matrix total.
FIXTURE_SELECTOR_MODULUS=$((1 << 32))
FIXTURE_SELECTOR_INDEX="$(journey_class_shard_index "$TARGET_CLASS" "$FIXTURE_SELECTOR_MODULUS")"
[[ "$FIXTURE_SELECTOR_INDEX" =~ ^[0-9]+$ ]] \
  || fail "production selector returned an invalid shard for $TARGET_CLASS"

extract_workflow_snapshot_copy() {
  awk '
    /^[[:space:]]+if \[\[ -d artifacts\/ci-journey \]\]; then$/ { in_copy=1 }
    in_copy && /^[[:space:]]+scripts\/ci-journey-preserve-android-outputs\.sh / { exit }
    in_copy {
      sub(/^          /, "")
      print
    }
  ' "$WORKFLOW"
}

SNAPSHOT_COPY="$(extract_workflow_snapshot_copy)"
[[ -n "$SNAPSHOT_COPY" ]] \
  || fail "could not extract the production first-cold-boot snapshot copy"

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
  logcat)
    [[ "${2:-}" == "-c" ]] || printf '08-11 12:00:00.000 journey-stub logcat\n'
    ;;
  exec-out) emit_valid_png ;;
  shell)
    case "${2:-}" in
      ps) printf 'PID NAME\n123 com.pocketshell.app\n' ;;
      dumpsys) printf 'ACTIVITY MANAGER stub diagnostics\n' ;;
    esac
    ;;
esac
exit 0
STUB
chmod +x "$STUBBIN/adb"

make_workspace() {
  local root="$1"
  rm -rf "$root"
  mkdir -p "$root"
  cp -a "$REPO_ROOT/scripts" "$root/scripts"
  chmod +x "$root/scripts/ci-journey-suite.sh"
  mkdir -p "$root/app" "$root/shared/core-terminal"
  {
    echo 'rootProject.name = "issue-2093-fixture"'
    echo 'include(":app", ":shared:core-terminal")'
  } > "$root/settings.gradle.kts"

  cat > "$root/gradlew" <<'STUB'
#!/usr/bin/env bash
set -u

if [[ "${1:-}" == "--stop" ]]; then exit 0; fi

selector_class() {
  local arg value
  for arg in "$@"; do
    case "$arg" in
      -Pandroid.testInstrumentationRunnerArguments.class=*)
        value="${arg#*=}"
        printf '%s\n' "${value%%#*}"
        return 0
        ;;
    esac
  done
  return 1
}

module_root() {
  if [[ "$*" == *":shared:core-terminal:connectedDebugAndroidTest"* ]]; then
    printf '%s\n' "$PWD/shared/core-terminal/build"
  else
    printf '%s\n' "$PWD/app/build"
  fi
}

if [[ "$*" != *"connectedDebugAndroidTest"* ]]; then
  echo '> Task :app:assembleDebug'
  echo '> Task :app:assembleDebugAndroidTest'
  exit 0
fi

class="$(selector_class "$@")" || exit 2
build_root="$(module_root "$@")"
raw="$build_root/outputs/androidTest-results/connected/debug/TEST-issue-2093.xml"
report="$build_root/reports/androidTests/connected/debug/index.html"
additional="$build_root/outputs/connected_android_test_additional_output/debugAndroidTest/connected/emulator-5554 - 15/issue-2093"
mkdir -p "$(dirname "$raw")" "$(dirname "$report")" "$additional"
printf '<html>issue-2093 report for %s</html>\n' "$class" > "$report"
printf 'selector=%s\ninvocation=%s\n' "$class" "${JOURNEY_FIXTURE_INVOCATION:-unknown}" \
  > "$additional/diagnostics.txt"
printf '\211\120\116\107\015\012\032\012\000\000\000\015\111\110\104\122\000\000\000\001\000\000\000\001\010\006\000\000\000\037\025\304\211\000\000\000\015\111\104\101\124\170\234\143\140\140\140\370\017\000\001\004\001\000\137\345\303\113\000\000\000\000\111\105\116\104\256\102\140\202' \
  > "$additional/viewport.png"

echo '> Task :connectedDebugAndroidTest'
echo 'Starting 1 tests on emulator-5554'
if [[ -n "${JOURNEY_STUB_FAIL_CLASS:-}" && "$class" == "$JOURNEY_STUB_FAIL_CLASS" ]]; then
  cat > "$raw" <<XML
<testsuite name="$class" tests="1" failures="1">
  <testcase classname="$class" name="issue2093Fixture"><failure message="cold boot 1 fixture failure"/></testcase>
</testsuite>
XML
  echo 'Finished 1 tests on emulator-5554'
  exit 1
fi
cat > "$raw" <<XML
<testsuite name="$class" tests="1" failures="0">
  <testcase classname="$class" name="issue2093Fixture"/>
</testsuite>
XML
echo 'Finished 1 tests on emulator-5554'
exit 0
STUB
  chmod +x "$root/gradlew"

  cat > "$root/scripts/connected-test.sh" <<'STUB'
#!/usr/bin/env bash
root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
exec "$root_dir/gradlew" :app:connectedDebugAndroidTest "$@"
STUB
  chmod +x "$root/scripts/connected-test.sh"
}

run_suite() {
  local root="$1" invocation="$2" fail_class="${3:-}"
  local log="$root/cold-boot-$invocation.log"
  env \
    PATH="$STUBBIN:$PATH" \
    JOURNEY_FIXTURE_INVOCATION="$invocation" \
    JOURNEY_STUB_FAIL_CLASS="$fail_class" \
    JOURNEY_STEP_BUDGET_SECS=900 \
    JOURNEY_CLASS_TIMEOUT_SECS=30 \
    JOURNEY_WARM_BUILD_TIMEOUT_SECS=30 \
    JOURNEY_NO_OUTPUT_TIMEOUT_SECS=25 \
    JOURNEY_CLASS_KILL_AFTER_SECS=1 \
    JOURNEY_GRADLE_STOP_TIMEOUT_SECS=5 \
    POCKETSHELL_JOURNEY_CI_SHARD_TOTAL="$FIXTURE_SELECTOR_MODULUS" \
    POCKETSHELL_JOURNEY_CI_SHARD_INDEX="$FIXTURE_SELECTOR_INDEX" \
    bash "$root/scripts/ci-journey-suite.sh" > "$log" 2>&1
}

preserve_first_cold_boot() {
  local root="$1"
  local snapshot="artifacts/ci-journey-attempt-1"
  (
    cd "$root" || exit 1
    rm -rf "$snapshot"
    mkdir -p "$snapshot"
    {
      echo '# First emulator journey attempt diagnostics'
      echo
      echo '- first attempt outcome: `failure`'
      echo '- first attempt conclusion: `failure`'
      echo '- first summary timeout-only: `false`'
      echo '- first summary genuine failure: `true`'
      echo '- suite summary: `ci-journey/summary.md`'
    } > "$snapshot/attempt-metadata.md"
    # shellcheck disable=SC2294 # Trusted production workflow copy block.
    eval "$SNAPSHOT_COPY"
    CI_JOURNEY_REPO_ROOT="$root" \
      CI_JOURNEY_SETTINGS_FILE="$root/settings.gradle.kts" \
      "$root/scripts/ci-journey-preserve-android-outputs.sh" "$snapshot"
  )
}

write_recovered_token() {
  local root="$1"
  (
    cd "$root" || exit 1
    SHARD_VERDICT_FILE="artifacts/ci-journey-shard-verdict/shard-verdict.txt" \
      SHARD_RETRY_AFFORDABLE=true \
      SHARD_RETRY_DENIED_REASON=sufficient_remaining_budget \
      SHARD_RETRY_SHORTFALL_MS=0 \
      POCKETSHELL_JOURNEY_CI_SHARD_INDEX="$FIXTURE_SELECTOR_INDEX" \
      GITHUB_RUN_ID=31524896571 \
      GITHUB_RUN_ATTEMPT=1 \
      bash scripts/ci-journey-write-shard-verdict.sh CLEAN infra_flake_recovered \
      > recovered-token.log
  )
}

write_preseed_token() {
  local root="$1"
  (
    cd "$root" || exit 1
    SHARD_VERDICT_FILE="artifacts/ci-journey-shard-verdict/shard-verdict.txt" \
      POCKETSHELL_JOURNEY_CI_SHARD_INDEX="$FIXTURE_SELECTOR_INDEX" \
      GITHUB_RUN_ID=31524896571 \
      GITHUB_RUN_ATTEMPT=1 \
      bash scripts/ci-journey-write-shard-verdict.sh INFRA preseed_before_classify \
      > preseed-token.log
  )
}

class_attempt_root() {
  local root="$1" canonical="$2"
  find "$root/artifacts/$canonical/ci-journey/class-attempts/app" \
    -mindepth 1 -maxdepth 1 -type d -name "$TARGET_CLASS--*" -print -quit 2>/dev/null
}

canonical_class_attempt_root() {
  local root="$1"
  find "$root/artifacts/ci-journey/class-attempts/app" \
    -mindepth 1 -maxdepth 1 -type d -name "$TARGET_CLASS--*" -print -quit 2>/dev/null
}

canonical_target_terminal_attempt_is_recovered() {
  local root="$1" current terminal
  current="$(canonical_class_attempt_root "$root")"
  [[ -n "$current" ]] || return 1
  terminal="$(find "$current" -mindepth 1 -maxdepth 1 -type d -name 'attempt-*' \
    -printf '%f\n' | sort -V | tail -n 1)"
  [[ "$terminal" == attempt-1 ]] || return 1
  grep -Fqx 'primary_classification=pass' "$current/$terminal/manifest.txt"
}

assert_common_recovered_artifact() {
  local root="$1"
  local current first current_attempt first_a1 first_a2 raw harness token
  current="$(canonical_class_attempt_root "$root")"
  first="$(class_attempt_root "$root" ci-journey-attempt-1)"
  [[ -n "$current" && -n "$first" ]] \
    || fail "fixture did not produce both canonical and preserved target-class roots"
  current_attempt="$current/attempt-1"
  first_a1="$first/attempt-1"
  first_a2="$first/attempt-2"

  [[ -d "$current_attempt" && -d "$first_a1" && -d "$first_a2" ]] \
    || fail "fixture did not produce final attempt 1 plus both preserved failures"
  grep -Fqx 'primary_classification=pass' "$current_attempt/manifest.txt" \
    || fail "final attempt 1 manifest is not a passing invocation-2 manifest"
  for attempt in "$first_a1" "$first_a2"; do
    local first_raw first_harness
    grep -Fqx 'primary_classification=failure' "$attempt/manifest.txt" \
      || fail "preserved cold-boot-1 manifest is not failed: $attempt"
    [[ -s "$attempt/device-logcat.txt" \
       && -s "$attempt/device-processes.txt" \
       && -s "$attempt/activity-processes.txt" \
       && -s "$attempt/activity-top.txt" \
       && -s "$attempt/failure-screen.png" ]] \
      || fail "preserved failed attempt lost diagnostics or fallback screenshot: $attempt"
    first_raw="$(find "$attempt/android-test-outputs" -type f -name 'TEST-*.xml' -print -quit)"
    first_harness="$attempt/journey-harness-verdict.xml"
    [[ -s "$first_raw" && -s "$first_harness" && "$first_raw" != "$first_harness" ]] \
      || fail "preserved raw JUnit and harness verdict are not separate: $attempt"
    grep -q 'failures="1"' "$first_raw" \
      || fail "preserved raw JUnit lost the cold-boot-1 failure: $attempt"
    grep -q '<classification>failure</classification>' "$first_harness" \
      || fail "preserved harness verdict lost its failure classification: $attempt"
    grep -q '<final-status>complete</final-status>' "$first_harness" \
      || fail "preserved harness verdict is incomplete: $attempt"
  done

  raw="$(find "$current_attempt/android-test-outputs" -type f -name 'TEST-*.xml' -print -quit)"
  harness="$current_attempt/journey-harness-verdict.xml"
  [[ -s "$raw" && -s "$harness" && "$raw" != "$harness" ]] \
    || fail "final raw JUnit and harness-owned JUnit are not separate readable files"
  grep -q 'failures="0"' "$raw" \
    || fail "final raw JUnit does not prove the recovered selector passed"
  grep -q '<classification>pass</classification>' "$harness" \
    || fail "final harness verdict does not record the passing invocation"
  grep -q '<final-status>complete</final-status>' "$harness" \
    || fail "final harness verdict does not prove artifact/cleanup completion"
  [[ -n "$(find "$current_attempt/android-test-outputs" -type f -name diagnostics.txt -print -quit)" ]] \
    || fail "final attempt lost connected-test diagnostics"
  [[ -n "$(find "$current_attempt/android-test-outputs" -type f -name viewport.png -print -quit)" ]] \
    || fail "final attempt lost connected-test screenshot"

  grep -q '\*\*PASS\*\*' "$root/artifacts/ci-journey/summary.md" \
    || fail "final canonical summary is not PASS"
  grep -q 'Failed BOTH attempts' "$root/artifacts/ci-journey-attempt-1/ci-journey/summary.md" \
    || fail "preserved cold-boot-1 summary lost the failed-both verdict"
  [[ -s "$root/artifacts/ci-journey-attempt-1/attempt-metadata.md" ]] \
    || fail "first-cold-boot metadata was deleted by invocation 2"

  token="$root/artifacts/ci-journey-shard-verdict/shard-verdict.txt"
  [[ "$(head -n 1 "$token")" == CLEAN ]] \
    || fail "recovered retry did not retain a CLEAN shard token"
  grep -Fqx 'verdict_reason=infra_flake_recovered' "$token" \
    || fail "recovered retry token lost infra_flake_recovered semantics"
  grep -Fqx 'retry_affordable=true' "$token" \
    || fail "recovered retry token lost retry-affordability evidence"
}

run_recovered_scenario() {
  local root="$1"
  local preseed_token preseed_sha
  write_preseed_token "$root" \
    || fail "production pre-seed shard-token writer failed"
  preseed_token="$root/artifacts/ci-journey-shard-verdict/shard-verdict.txt"
  preseed_sha="$(sha256sum "$preseed_token" | awk '{ print $1 }')"
  run_suite "$root" 1 "$TARGET_CLASS"
  local first_rc=$?
  grep -Eq 'running 1 of [0-9]+ journey classes' "$root/cold-boot-1.log" \
    || fail "fixture shard no longer selects exactly the one target journey class"
  if [[ "$first_rc" -eq 0 ]]; then
    sed -n '1,160p' "$root/cold-boot-1.log"
    fail "cold boot 1 did not fail the target selector twice; fixture is vacuous"
  fi
  grep -q "JOURNEY_FAILED: $TARGET_CLASS failed twice" "$root/cold-boot-1.log" \
    || fail "cold boot 1 failure came from something other than the target selector"
  preserve_first_cold_boot "$root" \
    || fail "production first-cold-boot snapshot path failed"
  run_suite "$root" 2 ""
  local retry_rc=$?
  [[ "$retry_rc" -eq 0 ]] \
    || { sed -n '1,200p' "$root/cold-boot-2.log"; fail "cold boot 2 did not recover cleanly"; }
  grep -q "JOURNEY_PASS: $TARGET_CLASS passed on attempt 1" "$root/cold-boot-2.log" \
    || fail "cold boot 2 did not pass the target selector on attempt 1"
  [[ "$(sha256sum "$preseed_token" | awk '{ print $1 }')" == "$preseed_sha" ]] \
    || fail "canonical resets deleted or rewrote the sibling pre-seeded shard token"
  grep -Fqx 'verdict_reason=preseed_before_classify' "$preseed_token" \
    || fail "pre-seeded shard-token semantics changed before classification"
  write_recovered_token "$root" \
    || fail "production shard-token writer rejected the recovered verdict"
  assert_common_recovered_artifact "$root"
}

echo "== #2093 exact-main recovered cold-boot retry artifact =="
REAL_ROOT="$SANDBOX/real"
make_workspace "$REAL_ROOT"
run_recovered_scenario "$REAL_ROOT"
REAL_CURRENT="$(canonical_class_attempt_root "$REAL_ROOT")"
canonical_target_terminal_attempt_is_recovered "$REAL_ROOT" \
  || fail "final canonical target's terminal attempt is not recovered cold boot 2 attempt 1"
[[ "$(find "$REAL_CURRENT" -mindepth 1 -maxdepth 1 -type d -name 'attempt-*' | wc -l)" -eq 1 ]] \
  || fail "final target selector contains more than invocation-2 attempt 1"
[[ -z "$(find "$REAL_ROOT/artifacts/ci-journey/class-attempts" -type d -name 'attempt-2' -print -quit)" ]] \
  || fail "final canonical tree contains a stale attempt 2 outside the target selector"
final_diagnostics=0
while IFS= read -r diagnostic; do
  final_diagnostics=$((final_diagnostics + 1))
  grep -Fqx 'invocation=2' "$diagnostic" \
    || fail "final canonical tree contains non-invocation-2 diagnostics: $diagnostic"
done < <(find "$REAL_ROOT/artifacts/ci-journey/class-attempts" -type f -name diagnostics.txt -print)
(( final_diagnostics > 0 )) \
  || fail "final canonical tree contains no invocation-labelled diagnostics"
pass "final canonical selector contains only recovered cold boot 2 attempt 1"

if [[ -n "${CI_JOURNEY_CANONICAL_PROOF_DIR:-}" ]]; then
  proof_dir="$CI_JOURNEY_CANONICAL_PROOF_DIR"
  [[ "$proof_dir" == "$REPO_ROOT"/build/* && "$proof_dir" != "$REPO_ROOT/build/" ]] \
    || fail "CI_JOURNEY_CANONICAL_PROOF_DIR must be a child of $REPO_ROOT/build"
  rm -rf "$proof_dir"
  mkdir -p "$proof_dir"
  cp -a "$REAL_ROOT/artifacts/." "$proof_dir/"
  cp -a "$REAL_ROOT/cold-boot-1.log" "$REAL_ROOT/cold-boot-2.log" "$proof_dir/"
  pass "preserved exact-main proof artifact at $proof_dir"
fi

echo
echo "== #2093 live mkdir-only mutant (selective red control) =="
MUTANT_ROOT="$SANDBOX/mutant"
make_workspace "$MUTANT_ROOT"
python3 - "$MUTANT_ROOT/scripts/ci-journey-suite.sh" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
text = path.read_text()
needle = 'rm -rf -- "$ARTIFACT_DIR" || exit 1\nmkdir -p "$ARTIFACT_DIR" || exit 1'
replacement = 'mkdir -p "$ARTIFACT_DIR" || exit 1'
if text.count(needle) != 1:
    raise SystemExit("test mutant could not find the one canonical reset block")
path.write_text(text.replace(needle, replacement))
PY
grep -Fq 'rm -rf -- "$ARTIFACT_DIR"' "$MUTANT_ROOT/scripts/ci-journey-suite.sh" \
  && fail "mkdir-only mutant is not live"
run_recovered_scenario "$MUTANT_ROOT"
MUTANT_CURRENT="$(canonical_class_attempt_root "$MUTANT_ROOT")"
if canonical_target_terminal_attempt_is_recovered "$MUTANT_ROOT"; then
  fail "mkdir-only mutant survived the same terminal-attempt oracle as production"
fi
[[ -d "$MUTANT_CURRENT/attempt-2" ]] \
  || fail "mkdir-only mutant did not reproduce stale failed attempt 2; mutation proof is vacuous"
grep -Fqx 'primary_classification=failure' "$MUTANT_CURRENT/attempt-2/manifest.txt" \
  || fail "mutant's retained attempt 2 is not the stale cold-boot-1 failure"
grep -q 'failures="1"' "$(find "$MUTANT_CURRENT/attempt-2/android-test-outputs" -type f -name 'TEST-*.xml' -print -quit)" \
  || fail "mutant's retained highest-numbered raw JUnit is not failed"
pass "mutation is selective: reports/preservation/token stay valid, but mkdir-only retains stale failed attempt 2 beside recovered attempt 1"

echo
echo "ALL PASS: scripts/test-ci-journey-canonical-artifacts.sh (issue #2093)"
