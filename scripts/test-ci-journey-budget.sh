#!/usr/bin/env bash
# Focused test for the issue #835 suite-level time budget + classifier labelling
# in scripts/ci-journey-suite.sh.
#
# The recurring failure: the in-emulator #470 tmux `list-sessions` enumeration
# stall makes session/reconnect journeys burn their retry windows; with no
# suite-level deadline the 45-min workflow step cap SIGKILLs the suite mid-loop
# before summary.md is written, so the workflow classifier mis-routes the red to
# "EMULATOR INFRA UNAVAILABLE (#771)".
#
# This test drives the REAL ci-journey-suite.sh with a TINY budget and a STUBBED
# gradle that sleeps (modelling a stalling class), and proves:
#   (a) the suite stops launching new classes once the budget is spent
#       (it does NOT run all 30+ classes — it bails),
#   (b) it ALWAYS writes summary.md (the artifact the classifier needs),
#   (c) the summary carries the distinct `JOURNEY_STEP_TIMEOUT` marker
#       (NOT `JOURNEY_FAILED` and NOT a missing file),
#   (d) the suite still exits NON-ZERO (a stall is red, never silently green),
#   (e) the workflow classifier grep that distinguishes a #470 timeout from a
#       genuine failure / infra abort routes this summary to the timeout label.
#
# It runs entirely on the JVM-free shell layer — NO emulator, NO Docker, NO
# gradle — so it can run as a fast unit check on any box and in the Unit CI job.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REAL_SUITE="$SCRIPT_DIR/ci-journey-suite.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

[[ -f "$REAL_SUITE" ]] || fail "cannot find ci-journey-suite.sh at $REAL_SUITE"

# ---------------------------------------------------------------------------
# Build a sandbox "repo root": a copy of the suite script + a stub gradlew that
# SLEEPS (modelling a #470-stalling class) + stub scripts the suite shells out
# to. REPO_ROOT in the suite is derived from BASH_SOURCE, so we run the COPY
# from inside the sandbox and it treats the sandbox as the repo.
SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

mkdir -p "$SANDBOX/scripts"
cp "$REAL_SUITE" "$SANDBOX/scripts/ci-journey-suite.sh"
chmod +x "$SANDBOX/scripts/ci-journey-suite.sh"

# Stub gradlew: each invocation sleeps GRADLE_STUB_SLEEP seconds then "passes".
# The sleep models a class that takes real wall-clock time, so the tiny budget
# trips after the first class or two — exactly the #470-stall time-burn shape.
cat > "$SANDBOX/gradlew" <<'STUB'
#!/usr/bin/env bash
sleep "${GRADLE_STUB_SLEEP:-2}"
exit 0
STUB
chmod +x "$SANDBOX/gradlew"

# The suite shells out to `scripts/connected-test.sh` only on the --pool sharded
# path (not exercised here) and to `adb` near the top. Stub `adb` on PATH so the
# top-of-script `settings put` loop is a harmless no-op.
STUBBIN="$SANDBOX/stubbin"
mkdir -p "$STUBBIN"
cat > "$STUBBIN/adb" <<'STUB'
#!/usr/bin/env bash
# `adb devices` -> emit one fake device so the show_ime loop iterates once and
# `adb -s <serial> shell ...` -> no-op success.
case "$1" in
  devices) printf 'List of devices attached\nemulator-5554\tdevice\n' ;;
  *) exit 0 ;;
esac
STUB
chmod +x "$STUBBIN/adb"

echo "== Running ci-journey-suite.sh with a tiny budget + stalling gradle stub =="
out="$SANDBOX/run.log"
set +e
PATH="$STUBBIN:$PATH" \
  JOURNEY_STEP_BUDGET_SECS=3 \
  JOURNEY_CLASS_TIMEOUT_SECS=5 \
  GRADLE_STUB_SLEEP=2 \
  bash "$SANDBOX/scripts/ci-journey-suite.sh" > "$out" 2>&1
rc=$?
set -e

summary="$SANDBOX/artifacts/ci-journey/summary.md"

# (a) the suite bailed instead of running all classes — at least one class is
#     bucketed as a budget timeout (the loop stopped launching new classes).
grep -q 'JOURNEY_STEP_TIMEOUT' "$out" \
  || { sed -n '1,40p' "$out"; fail "(a) no JOURNEY_STEP_TIMEOUT log line — suite did not bail on the budget"; }
pass "(a) suite bailed on the budget (JOURNEY_STEP_TIMEOUT logged)"

# (b) summary.md was written despite the budget timeout.
[[ -f "$summary" ]] || fail "(b) summary.md was NOT written on a budget timeout — the classifier would mis-route to #771"
pass "(b) summary.md written even on budget timeout"

# (c) summary carries the distinct marker, NOT a genuine-failure marker.
grep -q 'JOURNEY_STEP_TIMEOUT' "$summary" \
  || { cat "$summary"; fail "(c) summary missing JOURNEY_STEP_TIMEOUT marker"; }
grep -q 'Suite step time budget exhausted' "$summary" \
  || fail "(c) summary missing 'Suite step time budget exhausted' line"
if grep -qE 'JOURNEY_FAILED|Failed BOTH attempts' "$summary"; then
  cat "$summary"
  fail "(c) summary wrongly contains a genuine-failure marker on a pure budget timeout"
fi
pass "(c) summary has the timeout marker and NOT a genuine-failure marker"

# (d) the suite exited NON-ZERO (a stall is red, never silently green).
[[ "$rc" -ne 0 ]] || fail "(d) suite exited 0 on a budget timeout — a #470 stall must be red"
pass "(d) suite exited non-zero (rc=$rc) on a budget timeout"

# (e) classifier routing: replicate the workflow's grep ladder against this
#     summary and assert it lands on the TIMEOUT branch, not genuine-failure,
#     not infra-abort.
classify() {
  local s="$1"
  if [[ -f "$s" ]] && grep -qE 'JOURNEY_FAILED|Failed BOTH attempts' "$s"; then
    echo "GENUINE_FAILURE"; return
  fi
  if [[ -f "$s" ]] && grep -qE 'JOURNEY_STEP_TIMEOUT|Suite step time budget exhausted' "$s"; then
    echo "JOURNEY_TIMEOUT"; return
  fi
  echo "INFRA_UNAVAILABLE"
}
verdict="$(classify "$summary")"
[[ "$verdict" == "JOURNEY_TIMEOUT" ]] \
  || fail "(e) classifier routed to '$verdict', expected JOURNEY_TIMEOUT"
pass "(e) workflow classifier grep ladder routes this summary to JOURNEY_TIMEOUT"

# ---------------------------------------------------------------------------
# Negative control: a clean PASS run (generous budget, fast gradle stub) must
# NOT trip the timeout path — proves the budget does not falsely flag healthy
# runs. We stub gradle to return instantly and give a large budget. (We do not
# need every real class to pass — we only assert NO JOURNEY_STEP_TIMEOUT marker
# is produced and summary.md is written.)
echo "== Negative control: generous budget + instant gradle stub =="
cat > "$SANDBOX/gradlew" <<'STUB'
#!/usr/bin/env bash
exit 0
STUB
chmod +x "$SANDBOX/gradlew"

out2="$SANDBOX/run-clean.log"
set +e
PATH="$STUBBIN:$PATH" \
  JOURNEY_STEP_BUDGET_SECS=3600 \
  JOURNEY_CLASS_TIMEOUT_SECS=420 \
  bash "$SANDBOX/scripts/ci-journey-suite.sh" > "$out2" 2>&1
rc2=$?
set -e

summary2="$SANDBOX/artifacts/ci-journey/summary.md"
[[ -f "$summary2" ]] || fail "(neg) summary.md not written on the clean run"
if grep -q 'JOURNEY_STEP_TIMEOUT' "$summary2"; then
  cat "$summary2"
  fail "(neg) clean run wrongly produced a JOURNEY_STEP_TIMEOUT marker"
fi
# NOTE: this standalone classify() does not model the workflow's earlier
# "first attempt outcome == success -> exit 0" short-circuit; in the real
# workflow a clean PASS never reaches the grep ladder at all. We only assert
# the summary itself does NOT carry the timeout marker (so it could not be
# mis-routed to JOURNEY_TIMEOUT if the ladder were reached).
neg_verdict="$(classify "$summary2")"
[[ "$neg_verdict" != "JOURNEY_TIMEOUT" ]] \
  || fail "(neg) clean run mis-classified as JOURNEY_TIMEOUT"
pass "(neg) clean run: no false timeout (summary has no timeout marker; rc=$rc2)"

echo
echo "ALL TESTS PASSED"
