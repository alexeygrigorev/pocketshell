#!/usr/bin/env bash
# Functions are extracted/eval'd from production, so ShellCheck cannot see the
# indirect reads/calls that make the seeded state load-bearing.
# shellcheck disable=SC2030,SC2031,SC2034,SC2269,SC2317
set -uo pipefail

# ---------------------------------------------------------------------------
# The pre-release confidence gate's summary contract (issue #2064)
#
# WHAT THIS PINS
# --------------
#   1. The per-step WALL CLOCK is published. `run_step` has always measured
#      every step ("PASS: <name> (Ns)") and then thrown the number away, which
#      is the entire reason the local release chain was described as unmeasured
#      for a year: reconstructing the 41m56s / 1083s baseline for run
#      20260809-v0442-r3 needed archaeology across build-artefact mtimes. The
#      summary now carries a ranked table and a total, so before/after is a
#      `grep`.
#   2. WHERE the unit evidence came from — a reused CI run or a local run — is
#      recorded, because that is release evidence in its own right.
#   3. The validated APK's sha256 is recorded, so the summary names the one
#      binary the chain validated and ships.
#   4. `scripts/push-release-tag.sh`'s existing contract is untouched: the
#      summary still carries `Commit SHA:` and the result line.
#
# HOW (no copy, no emulator, no Gradle)
# -------------------------------------
# The functions under test are EXTRACTED FROM THE PRODUCTION FILE at run time
# and evaluated here with seeded state. Nothing is transcribed, so this cannot
# drift away from what the gate actually runs — deleting the table from
# scripts/pre-release-confidence-gate.sh reddens this immediately.
#
# Usage: scripts/test-release-gate-summary.sh
# ---------------------------------------------------------------------------

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GATE="$ROOT_DIR/scripts/pre-release-confidence-gate.sh"

CHECKS=0
SANDBOX="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-gate-summary-test.XXXXXX")"
trap 'rm -rf "$SANDBOX"' EXIT

ok() {
  CHECKS=$((CHECKS + 1))
  printf '  ok  %s\n' "$1"
}

die() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

step_seconds() {
  local summary="$1"
  local name="$2"
  awk -v wanted="$name" '
    $0 == "- name: " wanted { in_step = 1; next }
    in_step && /^- name: / { exit }
    in_step && /^  seconds: / { print $2; exit }
  ' "$summary"
}

# Extract one shell function verbatim from the production gate.
extract_function() {
  local name="$1"
  local text
  text="$(awk -v fn="$name" '
    $0 ~ "^" fn "\\(\\) \\{" { inside = 1 }
    inside { print }
    inside && /^\}$/ { exit }
  ' "$GATE")"
  [[ -n "$text" ]] || die "could not extract $name() from $GATE — the gate's summary writer moved or was renamed"
  printf '%s\n' "$text"
}

printf 'pre-release gate summary contract (issue #2064)\n'

# ---------------------------------------------------------------------------
# Seed exactly the state the real gate holds at exit, then run the real
# write_summary over it.
# ---------------------------------------------------------------------------
render_summary() {
  local out_dir="$1"
  local unit_mode="$2"
  local identity_file="$3"
  mkdir -p "$out_dir"

  (
    # shellcheck disable=SC1090
    source "$ROOT_DIR/scripts/lib/apk-identity.sh"
    eval "$(extract_function commit_sha)"
    eval "$(extract_function log_path_for)"
    eval "$(extract_function print_failure_log_tail)"
    eval "$(extract_function run_step)"
    eval "$(extract_function mark_step_declined)"
    eval "$(extract_function write_summary)"
    update_emulator_serial() { EMULATOR_SERIAL="emulator-5554"; }

    RUN_ID="summary-contract"
    RUN_DIR="$out_dir"
    SUMMARY_PATH="$out_dir/summary.txt"
    SUMMARY_WRITTEN=0
    GATE_RESULT="PASS"
    GATE_RESULT_MESSAGE="PASS: pre-release confidence gate completed"
    EMULATOR_SERIAL="unknown"
    ROOT_DIR="$ROOT_DIR"
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    TEST_APK_PATH="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    COMPOSE_FILE="tests/docker/docker-compose.yml"
    GRADLE_FLAGS="--no-daemon --no-build-cache --no-parallel --max-workers=1"
    POCKETSHELL_TEST_MEM="24G"
    UNIT_EVIDENCE_MODE="$unit_mode"
    UNIT_EVIDENCE_DETAIL="detail for $unit_mode"
    APK_IDENTITY_FILE="$identity_file"
    CONNECTED_TERMINAL_INPUT_STATUS="passed"
    APP_WALKTHROUGH_INSTALL_STATUS="passed"
    FINAL_INSTALL_STATUS="passed"
    LEGACY_V1_DB_MIGRATION_STATUS="passed"
    LEGACY_V1_DB_MIGRATION_LOGCAT="$out_dir/legacy.log"
    FAILING_STEP=""
    FAILURE_MESSAGE=""
    FAILING_LOG_PATH=""
    FAILURE_DIAGNOSTICS_PATH=""
    FAILURE_LOGCAT_PATH=""
    STEP_INDEX=0
    STEP_NAMES=()
    STEP_STATUSES=()
    STEP_LOGS=()
    STEP_COMMANDS=()
    STEP_SECONDS=()
    FOCUSED_SELECTORS=("com.example.Foo#bar")
    FOCUSED_STATUSES=("passed")
    FOCUSED_LOGS=("$out_dir/f.log")
    FOCUSED_DIAGNOSTICS=("")
    FOCUSED_LOGCATS=("")

    # Drive the REAL run_step, so the PRODUCER of the timings is under test too.
    # Seeding STEP_SECONDS by hand would leave "run_step stopped recording the
    # elapsed time" a surviving mutant — the table would still render, from
    # numbers nothing measured.
    run_step "fast-step" true > /dev/null 2>&1
    run_step "slow-step" sleep 2 > /dev/null 2>&1
    run_step "reuse-ci-unit-evidence" false > /dev/null 2>&1 || true
    mark_step_declined "reuse-ci-unit-evidence"

    # The real write_summary's last statement is a `[[ -n … ]] && printf`, so it
    # returns 1 whenever the final focused selector has no logcat path. In the
    # gate that is invisible (it runs from the EXIT trap, whose return value is
    # discarded), so this test asserts the summary's CONTENT and deliberately
    # does not treat the return code as the verdict.
    write_summary 0 || true
  )
}

# A real identity record, written by the real recorder.
APK_DIR="$SANDBOX/apks"
mkdir -p "$APK_DIR"
printf 'PK\x03\x04 app\n' > "$APK_DIR/app-debug.apk"
printf 'PK\x03\x04 test\n' > "$APK_DIR/app-debug-androidTest.apk"
# shellcheck disable=SC1090
source "$ROOT_DIR/scripts/lib/apk-identity.sh"
pocketshell_record_apk_identity "$APK_DIR/apk-identity.txt" \
  "$APK_DIR/app-debug.apk" "$APK_DIR/app-debug-androidTest.apk" >/dev/null
APP_SHA="$(pocketshell_apk_sha256 "$APK_DIR/app-debug.apk")"
TEST_SHA="$(pocketshell_apk_sha256 "$APK_DIR/app-debug-androidTest.apk")"

OUT="$SANDBOX/reused"
render_summary "$OUT" "reused-ci" "$APK_DIR/apk-identity.txt"
SUMMARY="$OUT/summary.txt"
[[ -s "$SUMMARY" ]] || die "write_summary produced no summary"

# 1. per-step timings, ranked, with a total. A real sleep(2) may span two or
# three integer-second boundaries because run_step deliberately uses the gate's
# existing whole-second wall clock. Assert the measured properties rather than
# one scheduler-sensitive phase of that clock.
FAST_SECONDS="$(step_seconds "$SUMMARY" "fast-step")"
SLOW_SECONDS="$(step_seconds "$SUMMARY" "slow-step")"
REUSE_SECONDS="$(step_seconds "$SUMMARY" "reuse-ci-unit-evidence")"
TOTAL_SECONDS="$(awk -F': ' '$1 == "Total step seconds" { print $2; exit }' "$SUMMARY")"
for timing in "$FAST_SECONDS" "$SLOW_SECONDS" "$REUSE_SECONDS" "$TOTAL_SECONDS"; do
  [[ "$timing" =~ ^[0-9]+$ ]] ||
    die "the summary contains a missing or non-numeric step duration: '$timing'"
done

# The lower bound proves the extracted production run_step really timed the
# sleep. The deliberately generous upper bound catches a bogus clock while
# tolerating ordinary hosted-runner scheduling noise.
((SLOW_SECONDS >= 2 && SLOW_SECONDS <= 10)) ||
  die "the real run_step sleep(2) duration is outside the bounded 2..10s contract: ${SLOW_SECONDS}s"
EXPECTED_TOTAL=$((FAST_SECONDS + SLOW_SECONDS + REUSE_SECONDS))
((TOTAL_SECONDS == EXPECTED_TOTAL)) ||
  die "the summary total (${TOTAL_SECONDS}s) is not the sum of its measured step records (${EXPECTED_TOTAL}s)"
ok "run_step's measured wall clock reaches the summary and is totalled"

grep -q '^Step timings (slowest first, seconds):$' "$SUMMARY" ||
  die "the summary has no ranked step-timing table"
EXPECTED_RANKED="$(printf '%s\n' "$FAST_SECONDS" "$SLOW_SECONDS" "$REUSE_SECONDS" | sort -rn)"
ACTUAL_RANKED="$(awk '$1 == "-" && $2 ~ /^[0-9]+s$/ && $3 ~ /^[0-9]+%$/ {
  seconds = $2
  sub(/s$/, "", seconds)
  print seconds
}' "$SUMMARY")"
[[ "$ACTUAL_RANKED" == "$EXPECTED_RANKED" ]] ||
  die "the timing table is not the measured durations sorted slowest-first (expected: ${EXPECTED_RANKED//$'\n'/, }; got: ${ACTUAL_RANKED//$'\n'/, })"
ok "the timing table is ranked slowest-first"

read -r RANKED_SLOW_SECONDS RANKED_SLOW_PERCENT RANKED_SLOW_STATUS < <(
  awk '$1 == "-" && $4 == "slow-step" {
    sub(/s$/, "", $2)
    sub(/%$/, "", $3)
    print $2, $3, $5
    exit
  }' "$SUMMARY"
)
EXPECTED_SLOW_PERCENT=$((SLOW_SECONDS * 100 / TOTAL_SECONDS))
[[ "$RANKED_SLOW_SECONDS" == "$SLOW_SECONDS" &&
   "$RANKED_SLOW_PERCENT" == "$EXPECTED_SLOW_PERCENT" &&
   "$RANKED_SLOW_STATUS" == "passed" ]] ||
  die "the ranked slow-step row does not propagate its measured duration/share/status"
ok "each step carries its percentage of the total"

grep -A2 '^- name: slow-step$' "$SUMMARY" | grep -q "^  seconds: ${SLOW_SECONDS}$" ||
  die "the slow-step record does not carry its own measured duration"
ok "each step record carries its duration"

# 2. unit-evidence provenance.
grep -q '^Unit evidence: reused-ci$' "$SUMMARY" ||
  die "the summary does not record WHERE the unit evidence came from"
ok "unit-evidence provenance is recorded (reused-ci)"

# 3. the validated binary.
grep -q "^Validated app APK sha256: $APP_SHA\$" "$SUMMARY" ||
  die "the summary does not name the validated app APK digest"
grep -q "^Validated androidTest APK sha256: $TEST_SHA\$" "$SUMMARY" ||
  die "the summary does not name the validated androidTest APK digest"
ok "the summary names the validated APK digests"

# 4. the tag helper's existing contract survives.
grep -q '^Commit SHA: ' "$SUMMARY" ||
  die "the summary lost its 'Commit SHA:' line — scripts/push-release-tag.sh requires it"
grep -q '^Result: PASS' "$SUMMARY" ||
  die "the summary lost its result line"
ok "the tag helper's Commit SHA + result contract is intact"

# The local-run mode records itself distinctly, so a release that could NOT
# reuse CI evidence is visible in its own summary rather than indistinguishable.
OUT_LOCAL="$SANDBOX/local"
render_summary "$OUT_LOCAL" "local" "$APK_DIR/apk-identity.txt"
grep -q '^Unit evidence: local$' "$OUT_LOCAL/summary.txt" ||
  die "a locally-run unit suite is not distinguishable in the summary"
ok "a local unit run is distinguishable from a reused one"

grep -A5 '^- name: reuse-ci-unit-evidence$' "$OUT_LOCAL/summary.txt" | grep -q '^  status: declined$' ||
  die "a fail-closed CI-evidence refusal is still recorded as a failed release step"
grep -q '^Failing step:' "$OUT_LOCAL/summary.txt" &&
  die "a declined CI-evidence reuse left stale failure state in the summary"
ok "declined CI reuse stays visible without poisoning the release verdict"

# A missing identity record must not crash the summary writer, and must not
# silently print a digest either.
OUT_NO_ID="$SANDBOX/no-identity"
render_summary "$OUT_NO_ID" "local" "$SANDBOX/absent/apk-identity.txt"
[[ -s "$OUT_NO_ID/summary.txt" ]] ||
  die "write_summary produced no summary when the identity record was absent"
grep -q '^Validated app APK sha256:' "$OUT_NO_ID/summary.txt" &&
  die "the summary printed an APK digest with no identity record behind it"
ok "an absent identity record yields no digest line and no crash"

if [[ "$CHECKS" -ne 10 ]]; then
  printf 'FAIL: ran %s checks, expected 10\n' "$CHECKS" >&2
  exit 1
fi
printf 'PASS: pre-release gate summary contract (%s checks)\n' "$CHECKS"
