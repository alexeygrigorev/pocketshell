#!/usr/bin/env bash
# Issue #2355 (D36 flake quarantine) — self-test for the CONSUMPTION half:
# a quarantined class's own failure must not block the per-push journey
# suite's exit code, while a NON-quarantined failure still must.
#
# Drives the REAL scripts/ci-journey-summary-functions.sh::finish_ci_journey_suite
# the same way scripts/test-ci-journey-summary-evidence.sh's "failsafe-driver"
# fixture does (a standalone bash driver that sources the two function files and
# calls the writer directly) — no Gradle, no emulator, no Docker.
#
# THE LOAD-BEARING CHECK (case c): the workflow classify step
# (.github/workflows/tests.yml "Classify emulator-journey result") re-derives
# red/green by grepping summary.md for the LITERAL strings
# `JOURNEY_FAILED|Failed BOTH attempts|JOURNEY_STEP_TIMEOUT|Suite step time
# budget exhausted|JOURNEY_ENUMERATION_STALL`, independent of this script's own
# exit code. A quarantine section worded carelessly (e.g. still saying "Failed
# BOTH attempts") would get re-reddened by that independent grep even though
# JOURNEY_EXIT=0 — the same "default flip silently re-points an oracle" class
# process.md's "Local Confidence Before CI" section warns about. Case (c) pins
# that the quarantine-only section matches NONE of those trigger strings.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/.."
SUMMARY_FN="$SCRIPT_DIR/ci-journey-summary-functions.sh"
CORE_TERMINAL_FN="$SCRIPT_DIR/ci-journey-core-terminal-functions.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

for required in "$SUMMARY_FN" "$CORE_TERMINAL_FN" "$SCRIPT_DIR/lib/journey-quarantine.sh"; do
  [[ -f "$required" ]] || fail "missing required file: $required"
done

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

# Runs finish_ci_journey_suite with the given FAILED_CLASSES and a quarantine
# file whose CONTENT is passed on stdin (empty stdin => file not created, i.e.
# the "no quarantine list" steady state). Writes summary.md + exit code + full
# log under $1 (a fresh subdir of SANDBOX).
run_case() {
  local dir="$1"; shift
  local -a failed=("$@")
  mkdir -p "$dir/artifacts/ci-journey"
  local qfile="$dir/journey-quarantine.txt"
  cat > "$qfile"
  if [[ ! -s "$qfile" ]]; then rm -f "$qfile"; fi

  {
    echo "source '$CORE_TERMINAL_FN'"
    echo "source '$SUMMARY_FN'"
    echo "REPO_ROOT='$REPO_ROOT'"
    echo "POCKETSHELL_JOURNEY_QUARANTINE_FILE='$qfile'"
    echo "SUITE_START=0; STEP_TIMEOUT_HIT=0"
    echo "RECOVERED_CLASSES=(); PASSED_FIRST_TRY=()"
    echo "BUDGET_TIMEOUT_CLASSES=(); BUILD_PHASE_TIMEOUT_ATTEMPTS=(); BUILD_PHASE_FAILURE_ATTEMPTS=()"
    echo "FIXTURE_WEDGED_CLASSES=()"
    echo "EFFECTIVE_JOURNEY_CLASSES=(${failed[*]@Q})"
    printf 'FAILED_CLASSES=(%s)\n' "${failed[*]@Q}"
    echo "JOURNEY_CI_SHARD_INDEX=1; JOURNEY_CI_SHARD_TOTAL=1"
    echo "JOURNEY_WARM_BUILD_STATUS=ok; JOURNEY_WARM_BUILD_ELAPSED=1"
    echo "JOURNEY_STEP_BUDGET_SECS=4200"
    echo "SUMMARY='$dir/artifacts/ci-journey/summary.md'"
    echo "ARTIFACT_DIR='$dir/artifacts/ci-journey'"
    echo "finish_ci_journey_suite"
  } > "$dir/driver.sh"
  bash "$dir/driver.sh" > "$dir/run.log" 2>&1
  echo $? > "$dir/rc"
}

# ---------------------------------------------------------------------------
# (a) a QUARANTINED failure alone does not block: exit 0, PASS-worded, and the
#     class appears ONLY under the quarantine section, never under
#     "Failed BOTH attempts".
# ---------------------------------------------------------------------------
echo "== (a) quarantined-only failure is non-blocking =="
d="$SANDBOX/a"
run_case "$d" "com.example.FlakyE2eTest" <<'EOF'
com.example.FlakyE2eTest	#9001	2026-08-01	2099-01-01	known CI-AVD flake, tracked for de-flake
EOF
rc="$(cat "$d/rc")"
summary="$d/artifacts/ci-journey/summary.md"
[[ "$rc" -eq 0 ]] || { cat "$d/run.log"; fail "(a) exit=$rc, expected 0"; }
grep -q 'exit 0, status PASS' "$d/run.log" \
  || { cat "$d/run.log"; fail "(a) run log did not report PASS"; }
grep -qE 'Failed BOTH attempts' "$summary" \
  && { cat "$summary"; fail "(a) a quarantined-only failure must not write a 'Failed BOTH attempts' section"; }
grep -q 'Quarantined failures' "$summary" \
  || { cat "$summary"; fail "(a) missing the quarantine section"; }
grep -q 'com.example.FlakyE2eTest' "$summary" \
  || { cat "$summary"; fail "(a) the quarantined class is not named anywhere in the summary"; }
grep -q '#9001' "$summary" \
  || { cat "$summary"; fail "(a) the tracking issue is not reported"; }
pass "(a) quarantined failure alone: exit 0, class still named, not under Failed-BOTH"

# ---------------------------------------------------------------------------
# (b) a NON-quarantined failure still blocks: exit 1, FAIL-worded, class under
#     "Failed BOTH attempts".
# ---------------------------------------------------------------------------
echo "== (b) non-quarantined failure still blocks =="
d="$SANDBOX/b"
run_case "$d" "com.example.RealRegressionTest" <<'EOF'
EOF
rc="$(cat "$d/rc")"
summary="$d/artifacts/ci-journey/summary.md"
[[ "$rc" -eq 1 ]] || { cat "$d/run.log"; fail "(b) exit=$rc, expected 1"; }
grep -qE 'Failed BOTH attempts' "$summary" \
  || { cat "$summary"; fail "(b) a real (non-quarantined) failure must still write the Failed-BOTH section"; }
grep -q 'com.example.RealRegressionTest' "$summary" \
  || { cat "$summary"; fail "(b) the real failure is not named"; }
pass "(b) non-quarantined failure: exit 1, Failed-BOTH section present"

# ---------------------------------------------------------------------------
# (c) MIXED: one quarantined + one real failure. The real one still blocks
#     (exit 1); the quarantined one is reported separately, and — the
#     load-bearing check — the quarantine section's own wording matches NONE
#     of the classifier's trigger strings even where the run IS red for an
#     unrelated reason.
# ---------------------------------------------------------------------------
echo "== (c) mixed: real failure blocks, quarantined failure named but non-blocking, and never mistaken for the trigger phrase =="
d="$SANDBOX/c"
run_case "$d" "com.example.FlakyE2eTest" "com.example.RealRegressionTest" <<'EOF'
com.example.FlakyE2eTest	#9001	2026-08-01	2099-01-01	known CI-AVD flake, tracked for de-flake
EOF
rc="$(cat "$d/rc")"
summary="$d/artifacts/ci-journey/summary.md"
[[ "$rc" -eq 1 ]] || { cat "$d/run.log"; fail "(c) exit=$rc, expected 1 (the real failure alone must still redden the suite)"; }
grep -qE 'Failed BOTH attempts' "$summary" \
  || { cat "$summary"; fail "(c) missing the Failed-BOTH section for the real failure"; }
# The failed-both bullets must name ONLY the real regression, never the
# quarantined class (it must not silently re-block via that section).
awk '/Failed BOTH attempts/{f=1; next} f && /^- /{print} f && /^$/{exit}' "$summary" \
  | grep -q 'com.example.FlakyE2eTest' \
  && { cat "$summary"; fail "(c) the quarantined class leaked into the blocking Failed-BOTH bullet list"; }
grep -q 'Quarantined failures' "$summary" \
  || { cat "$summary"; fail "(c) missing the quarantine section"; }
grep -q 'com.example.FlakyE2eTest' "$summary" \
  || { cat "$summary"; fail "(c) the quarantined class is not named anywhere"; }
pass "(c) mixed case: real failure blocks, quarantined class named separately"

# Now the load-bearing part of (c): isolate ONLY the quarantine section's own
# text and assert it independently matches none of the classifier's trigger
# strings (proving the section's WORDING, not just its position, is safe).
quarantine_block="$(awk '/^Quarantined failures/{f=1} f{print} f && /^scripts\/check-journey-quarantine-expiry/{exit}' "$summary")"
[[ -n "$quarantine_block" ]] || { cat "$summary"; fail "(c) could not isolate the quarantine block for the wording check"; }
if grep -qE 'JOURNEY_FAILED|Failed BOTH attempts|JOURNEY_STEP_TIMEOUT|Suite step time budget exhausted|JOURNEY_ENUMERATION_STALL' <<<"$quarantine_block"; then
  echo "$quarantine_block"
  fail "(c) the quarantine section's own wording matches a classifier trigger string — it would re-redden a PASS run"
fi
pass "(c) the quarantine section's wording matches none of the classifier's trigger strings"

# ---------------------------------------------------------------------------
# (d) FAIL-SAFE: a malformed quarantine row must not exempt its class — the
#     class stays blocking, exactly as if the list were empty.
# ---------------------------------------------------------------------------
echo "== (d) fail-safe: a malformed quarantine row does not exempt its class =="
d="$SANDBOX/d"
run_case "$d" "com.example.FlakyE2eTest" <<'EOF'
com.example.FlakyE2eTest	missing-fields-only-three
EOF
rc="$(cat "$d/rc")"
summary="$d/artifacts/ci-journey/summary.md"
[[ "$rc" -eq 1 ]] || { cat "$d/run.log"; fail "(d) exit=$rc, expected 1 — a malformed row must fail closed (not exempt)"; }
grep -qE 'Failed BOTH attempts' "$summary" \
  || { cat "$summary"; fail "(d) a class whose only quarantine row is malformed must still block"; }
pass "(d) malformed quarantine row: fails closed, class still blocks"

# ---------------------------------------------------------------------------
# (e) an EXPIRED-but-still-present quarantine entry still exempts the class
#     from blocking THIS run — expiry is enforced by the separate guard
#     (scripts/check-journey-quarantine-expiry.sh), not by the consumption
#     path, which only asks "is there a well-formed row for this class".
# ---------------------------------------------------------------------------
echo "== (e) an expired-but-present row still exempts (expiry is the guard's job, not the consumer's) =="
d="$SANDBOX/e"
run_case "$d" "com.example.FlakyE2eTest" <<'EOF'
com.example.FlakyE2eTest	#9001	2020-01-01	2020-01-15	long-expired, would be caught by check-journey-quarantine-expiry.sh
EOF
rc="$(cat "$d/rc")"
[[ "$rc" -eq 0 ]] || { cat "$d/run.log"; fail "(e) exit=$rc, expected 0 — the consumption path does not itself enforce expiry"; }
pass "(e) expiry is the separate guard's responsibility, not the consumption path's"

echo
echo "All journey-quarantine non-blocking cases passed."
