#!/usr/bin/env bash
# Focused test for the issue #835 suite-level time budget + classifier labelling
# in scripts/ci-journey-suite.sh.
#
# The recurring failure: the in-emulator #470 tmux `list-sessions` enumeration
# stall makes session/reconnect journeys burn their retry windows; with no
# suite-level deadline the workflow job cap SIGKILLs the suite mid-loop
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
#   (d) the suite still exits NON-ZERO so the first attempt outcome records the
#       timeout and the classifier can inspect the summary,
#   (e) the DURABLE GUARD (issue #835 REOPENED, D31): the workflow classifier
#       routes a budget-timeout summary to a HARD-RED timeout verdict — NOT
#       advisory-green (which used to MASK a cut-short load-bearing class) and
#       NOT the #771 infra branch. A budget timeout = a load-bearing class did
#       not reach a verdict = the gate FAILS.
#   (f) a cancelled retry is classified before any `Failed BOTH attempts`
#       summary, because summary.md can be stale from the first cold boot.
#   (g) first-attempt diagnostics are snapshotted before the workflow retry can
#       overwrite summary.md or connected-test outputs.
#
# It runs entirely on the JVM-free shell layer — NO emulator, NO Docker, NO
# gradle — so it can run as a fast unit check on any box and in the Unit CI job.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REAL_SUITE="$SCRIPT_DIR/ci-journey-suite.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

[[ -f "$REAL_SUITE" ]] || fail "cannot find ci-journey-suite.sh at $REAL_SUITE"

REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKFLOW="$REPO_ROOT/.github/workflows/tests.yml"
THIS_TEST="$SCRIPT_DIR/test-ci-journey-budget.sh"

# (pre) #835 right-sized budget/default/comment guard: prove the suite budget is
# large enough to run the full load-bearing selection to a verdict AND still
# leaves explicit slack under the workflow cap after a worst-case emulator boot.
job_cap_min="$(awk '
  /^  emulator-journey:/ { in_job=1; next }
  in_job && /^  [A-Za-z0-9_-]+:/ { in_job=0 }
  in_job && /timeout-minutes:/ { print $2; exit }
' "$WORKFLOW")"
[[ "$job_cap_min" =~ ^[0-9]+$ ]] \
  || fail "(pre) could not parse emulator-journey timeout-minutes from tests.yml"
job_cap_secs=$((job_cap_min * 60))
[[ "$job_cap_secs" -eq 5700 ]] \
  || fail "(pre) emulator-journey job cap must be 95 min / 5700s after the #835 right-size (got ${job_cap_secs}s)"

mapfile -t emulator_boot_timeout_values < <(awk '/emulator-boot-timeout:/ { print $2 }' "$WORKFLOW")
[[ "${#emulator_boot_timeout_values[@]}" -gt 0 ]] \
  || fail "(pre) could not parse emulator-boot-timeout from tests.yml"
for emulator_boot_timeout_secs in "${emulator_boot_timeout_values[@]}"; do
  [[ "$emulator_boot_timeout_secs" =~ ^[0-9]+$ ]] \
    || fail "(pre) emulator-boot-timeout must be numeric (got ${emulator_boot_timeout_secs})"
  [[ "$emulator_boot_timeout_secs" -eq 900 ]] \
    || fail "(pre) every emulator boot timeout must stay 900s (got ${emulator_boot_timeout_secs}s)"
done
emulator_boot_timeout_secs="${emulator_boot_timeout_values[0]}"

# Match the literal shell assignment in ci-journey-suite.sh.
# shellcheck disable=SC2016
default_suite_budget_secs="$(sed -n 's/^JOURNEY_STEP_BUDGET_SECS="${JOURNEY_STEP_BUDGET_SECS:-\([0-9][0-9]*\)}"$/\1/p' "$REAL_SUITE")"
[[ "$default_suite_budget_secs" =~ ^[0-9]+$ ]] \
  || fail "(pre) could not parse default JOURNEY_STEP_BUDGET_SECS from ci-journey-suite.sh"
[[ "$default_suite_budget_secs" -eq 4200 ]] \
  || fail "(pre) default JOURNEY_STEP_BUDGET_SECS must be 4200s / 70 min after the #835 right-size (got ${default_suite_budget_secs}s)"

remaining_slack_secs=$((job_cap_secs - emulator_boot_timeout_secs - default_suite_budget_secs))
[[ "$remaining_slack_secs" -ge 600 ]] \
  || fail "(pre) insufficient post-boot slack: ${job_cap_secs}s job cap - ${emulator_boot_timeout_secs}s boot - ${default_suite_budget_secs}s suite = ${remaining_slack_secs}s (< 600s)"

# (pre) #1056 no-output watchdog: pin the default silence window and require it to
# sit BELOW the per-class wall cap, so a silent wedge dies on silence (the tight,
# fast bound) rather than on the coarse wall cap — and never above it (which would
# make the read timeout a no-op). Both parse the literal shell assignments.
default_no_output_secs="$(sed -n 's/^JOURNEY_NO_OUTPUT_TIMEOUT_SECS="${JOURNEY_NO_OUTPUT_TIMEOUT_SECS:-\([0-9][0-9]*\)}"$/\1/p' "$REAL_SUITE")"
default_class_timeout_secs="$(sed -n 's/^JOURNEY_CLASS_TIMEOUT_SECS="${JOURNEY_CLASS_TIMEOUT_SECS:-\([0-9][0-9]*\)}"$/\1/p' "$REAL_SUITE")"
[[ "$default_no_output_secs" =~ ^[0-9]+$ ]] \
  || fail "(pre) could not parse default JOURNEY_NO_OUTPUT_TIMEOUT_SECS from ci-journey-suite.sh"
[[ "$default_class_timeout_secs" =~ ^[0-9]+$ ]] \
  || fail "(pre) could not parse default JOURNEY_CLASS_TIMEOUT_SECS from ci-journey-suite.sh"
[[ "$default_no_output_secs" -lt "$default_class_timeout_secs" ]] \
  || fail "(pre) default silence window (${default_no_output_secs}s) must be BELOW the per-class wall cap (${default_class_timeout_secs}s) so a silent wedge dies on silence, not the coarse wall cap"
grep -q 'NO-OUTPUT (silence) watchdog' "$REAL_SUITE" \
  || fail "(pre) ci-journey-suite.sh must document the #1056 no-output silence watchdog"
grep -q 'run_bounded' "$REAL_SUITE" \
  || fail "(pre) ci-journey-suite.sh must route class attempts through run_bounded (the #1056 silence watchdog wrapper)"

grep -q '95-min job cap (5700s) - worst-case emulator boot (900s) - default suite' "$REAL_SUITE" \
  || fail "(pre) ci-journey-suite.sh budget comment must show the right-sized arithmetic"
grep -q 'budget (4200s) = 600s' "$REAL_SUITE" \
  || fail "(pre) ci-journey-suite.sh budget comment must document the 4200s/600s arithmetic"
grep -q 'workflow job cap: 95 min' "$REAL_SUITE" \
  || fail "(pre) ci-journey-suite.sh log line must refer to the 95-min job cap"
grep -q '95-min cap (5700s) - 900s worst-case boot - 4200s' "$WORKFLOW" \
  || fail "(pre) tests.yml timeout-minutes comment must show the 5700s/900s/4200s arithmetic"
# The masking the #835 reopen is about: a budget timeout must NOT be downgraded
# to advisory-green. Pin DIRECTLY on the real workflow classifier text: the
# timeout verdict must be reported as a `::error` (red), and the old advisory
# `::warning title=Emulator journey TIMEOUT` (which paired with `exit 0`) must be
# GONE. A regression that re-downgrades it to a warning/green trips this.
grep -q 'HARD RED' "$WORKFLOW" \
  || fail "(pre) tests.yml classifier must document the budget timeout is now a HARD RED (#835 durable guard)"
grep -q '::error title=Emulator journey TIMEOUT' "$WORKFLOW" \
  || fail "(pre) tests.yml classifier must report the journey TIMEOUT as a ::error (red), not advisory"
if grep -q '::warning title=Emulator journey TIMEOUT' "$WORKFLOW"; then
  fail "(pre) tests.yml still has an advisory ::warning journey-TIMEOUT branch — the #835 masking regressed"
fi
# Each TIMEOUT ::error must be immediately followed by `exit 1` (red), never
# `exit 0`. Walk the file: after a line containing the timeout error title, the
# next `exit N` we see must be `exit 1` (set a `bad` flag otherwise; print the
# verdict ONCE at END so awk's `exit`-runs-END quirk can't mask it).
timeout_exit_verdict="$(awk '
  /::error title=Emulator journey TIMEOUT/ { armed=1; next }
  armed && /[[:space:]]exit 0([[:space:]]|$)/ { bad=1; armed=0 }
  armed && /[[:space:]]exit 1([[:space:]]|$)/ { armed=0 }
  END { print (bad ? "ADVISORY" : "OK") }
' "$WORKFLOW")"
[[ "$timeout_exit_verdict" == "OK" ]] \
  || fail "(pre) a journey-TIMEOUT ::error is followed by exit 0 (advisory-green) — the #835 masking regressed"
# Stale pre-#835 budget/cap wording must not survive in the suite or workflow.
# Scan ONLY $REAL_SUITE + $WORKFLOW (not $THIS_TEST) so the regex literal below
# can't match its own definition line. Tokens use a split ("12""00") so even
# this assertion's source text never contains the contiguous stale string.
stale_budget_re="JOURNEY_STEP_BUDGET_SECS:-12""00|default 2""0 min|45-min job cap \(27""00s\)|workflow job cap: 4""5 min"
if grep -qE "$stale_budget_re" "$REAL_SUITE" "$WORKFLOW"; then
  fail "(pre) stale pre-#835 budget/cap wording found; use the right-sized 4200s budget / 95-min cap"
fi
# #835 REOPENED tax-cut pin: the journey class invocation must REUSE the Gradle
# daemon (no `--no-daemon` on :app:connectedDebugAndroidTest). Scan $REAL_SUITE
# only so this regex literal can't match its own definition. The daemon reuse is
# what makes ~89 serial invocations fit the right-sized budget; a regression back
# to `--no-daemon` would blow the budget and re-introduce the STEP_TIMEOUT.
if grep -qE -- '--no-daemon[[:space:]]+:app:connectedDebugAndroidTest' "$REAL_SUITE"; then
  fail "(pre) journey :app:connectedDebugAndroidTest still passes --no-daemon; the #835 daemon-reuse tax cut regressed"
fi
stale_step_cap_re="workflow ste""p|ste""p cap|45-min ste""p|45 min ste""p"
if grep -qE "$stale_step_cap_re" \
  "$REAL_SUITE" "$WORKFLOW" "$THIS_TEST"; then
  fail "(pre) stale workflow-timeout wording found; refer to the job cap, not a workflow ste""p timeout"
fi

preserve_line="$(grep -n 'name: Preserve first journey attempt diagnostics' "$WORKFLOW" | cut -d: -f1)"
retry_line="$(grep -n 'name: Retry journey subset on a fresh cold-booted emulator' "$WORKFLOW" | cut -d: -f1)"
upload_line="$(grep -n 'artifacts/ci-journey-attempt-1/' "$WORKFLOW" | cut -d: -f1 | tail -n 1)"
[[ "$preserve_line" =~ ^[0-9]+$ ]] \
  || fail "(pre) workflow must preserve first-attempt diagnostics before retry"
[[ "$retry_line" =~ ^[0-9]+$ ]] \
  || fail "(pre) could not find emulator retry step in tests.yml"
[[ "$upload_line" =~ ^[0-9]+$ ]] \
  || fail "(pre) first-attempt diagnostics must be uploaded as an artifact"
[[ "$preserve_line" -lt "$retry_line" ]] \
  || fail "(pre) first-attempt diagnostics preservation must run before retry"
grep -q 'cp -a artifacts/ci-journey/.' "$WORKFLOW" \
  || fail "(pre) preservation step must snapshot artifacts/ci-journey before retry overwrites summary.md"
grep -q 'summary-missing.txt' "$WORKFLOW" \
  || fail "(pre) preservation step must record first-attempt missing-summary infra aborts"
pass "(pre) #835 right-sized budget arithmetic pinned (${job_cap_secs}s job - ${emulator_boot_timeout_secs}s boot - ${default_suite_budget_secs}s suite = ${remaining_slack_secs}s slack)"
pass "(pre) first-attempt diagnostics are preserved before emulator retry"

# ---------------------------------------------------------------------------
# Issue #1458 hard-cut + mutation guard: raw emulator-console warning counts are
# ordinary per-connected-invocation output, not an infra signal. The workflow
# must not count them or downgrade JOURNEY_FAILED, and the classifier-only
# capture plumbing must stay deleted. Reintroducing count>=25 -> INFRA trips
# these assertions before the behavioural fixture below can pass vacuously.
if grep -qE \
  'journey-console\.log|Failed to start Emulator console|console_(storm|warning)_(count|threshold)|EMULATOR INFRA UNAVAILABLE / degraded' \
  "$WORKFLOW"; then
  fail "(pre-1458) tests.yml reintroduced raw emulator-console warning classification"
fi
if grep -q 'JOURNEY_CONSOLE_LOG' \
  "$REAL_SUITE" "$SCRIPT_DIR/ci-journey-budget-functions.sh"; then
  fail "(pre-1458) obsolete classifier-only JOURNEY_CONSOLE_LOG plumbing was reintroduced"
fi
pass "(pre-1458) raw console-count classifier + classifier-only capture plumbing are absent"

# ---------------------------------------------------------------------------
# Issue #1839: the suite-budget clock seam must stay TEST-ONLY.
#
# `JOURNEY_BUDGET_ELAPSED_SECS_OVERRIDE` exists so a budget assertion is decided
# by the budget arithmetic instead of by whether an integer `$SECONDS` tick
# happened to land (the flaky-by-construction `(c2)` that reddened `main` at
# ae368467). A pinned clock that reached PRODUCTION would silently disable the
# whole #835 budget, so pin three things mechanically:
#   * the production default path still measures `$SECONDS - SUITE_START`;
#   * the override is validated (a malformed pin is a hard abort, never ignored);
#   * neither the workflow nor the suite itself ever SETS the override.
BUDGET_FUNCTIONS="$SCRIPT_DIR/ci-journey-budget-functions.sh"
[[ -f "$BUDGET_FUNCTIONS" ]] || fail "(pre-1839) cannot find ci-journey-budget-functions.sh"
grep -q 'printf .%s\\n. "\$((SECONDS - SUITE_START))"' "$BUDGET_FUNCTIONS" \
  || fail "(pre-1839) the production suite-budget clock must still measure \$SECONDS - SUITE_START"
grep -q 'JOURNEY_BUDGET_CLOCK_INVALID' "$BUDGET_FUNCTIONS" \
  || fail "(pre-1839) a malformed JOURNEY_BUDGET_ELAPSED_SECS_OVERRIDE must abort, not silently disable the #835 budget"
if grep -qE '^[^#]*JOURNEY_BUDGET_ELAPSED_SECS_OVERRIDE=' "$WORKFLOW" "$REAL_SUITE"; then
  fail "(pre-1839) the suite-budget clock override is TEST-ONLY — it must never be set by tests.yml or the suite"
fi
# The seam is load-bearing only if the override actually governs. Drive the real
# helper at both extremes of the exhaustion boundary, with no wall clock involved.
budget_clock_probe() {
  JOURNEY_STEP_BUDGET_SECS=900 JOURNEY_BUDGET_ELAPSED_SECS_OVERRIDE="$1" \
    bash -c 'source "$0"; SUITE_START=$SECONDS; budget_exhausted && echo exhausted || echo live' \
    "$BUDGET_FUNCTIONS"
}
[[ "$(budget_clock_probe 0)" == "live" ]] \
  || fail "(pre-1839) pinned elapsed=0 of 900s must NOT be exhausted"
[[ "$(budget_clock_probe 900)" == "exhausted" ]] \
  || fail "(pre-1839) pinned elapsed=900 of 900s must be exhausted (the boundary is <= 0 remaining)"
[[ "$(budget_clock_probe 901)" == "exhausted" ]] \
  || fail "(pre-1839) pinned elapsed beyond the budget must be exhausted"
# BEHAVIOURAL, not just a grep for the message: a malformed pin must ABORT the
# sourcing shell with exit 2. A structural grep alone survives a mutation that
# guts the validation while leaving its error string in place.
budget_clock_invalid_out="$(mktemp)"
budget_clock_probe not-a-number > "$budget_clock_invalid_out" 2>&1
invalid_rc=$?
[[ "$invalid_rc" -eq 2 ]] \
  || { cat "$budget_clock_invalid_out"; fail "(pre-1839) a malformed budget-clock pin must abort with exit 2, got $invalid_rc"; }
grep -q 'JOURNEY_BUDGET_CLOCK_INVALID' "$budget_clock_invalid_out" \
  || { cat "$budget_clock_invalid_out"; fail "(pre-1839) a malformed budget-clock pin must abort greppably"; }
rm -f "$budget_clock_invalid_out"
pass "(pre-1839) suite-budget clock: production reads \$SECONDS, the test-only pin governs at 0/900/901, a malformed pin aborts (exit 2), and it is never set in tests.yml or the suite"

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
if [[ "${1:-}" == "--stop" ]]; then
  exit 0
fi
sleep "${GRADLE_STUB_SLEEP:-2}"
exit 0
STUB
chmod +x "$SANDBOX/gradlew"

# The suite shells out to `scripts/connected-test.sh` for the --pool sharded
# path and for #1741's dedicated external-permission fixture in serial mode.
# Forward both sandbox paths into the same stub Gradle so the existing timeout /
# daemon-cleanup mutations still govern every selected class.
cat > "$SANDBOX/scripts/connected-test.sh" <<'STUB'
#!/usr/bin/env bash
root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "$root_dir/gradlew" :app:connectedDebugAndroidTest "$@"
STUB
chmod +x "$SANDBOX/scripts/connected-test.sh"

# Stub `adb` on PATH so the top-of-script `settings put` loop is a harmless
# no-op.
STUBBIN="$SANDBOX/stubbin"
mkdir -p "$STUBBIN"
cat > "$STUBBIN/adb" <<'STUB'
#!/usr/bin/env bash
set -u

emit_valid_png() {
  # Real 1x1 RGBA PNG: signature + CRC-valid IHDR/IDAT/IEND chunks with a
  # complete zlib stream. Keep positives decoder-valid so structural checks
  # cannot be masked by signature-only fixtures.
  printf '\211\120\116\107\015\012\032\012\000\000\000\015\111\110\104\122\000\000\000\001\000\000\000\001\010\006\000\000\000\037\025\304\211\000\000\000\015\111\104\101\124\170\234\143\140\140\140\370\017\000\001\004\001\000\137\345\303\113\000\000\000\000\111\105\116\104\256\102\140\202'
}

# `adb devices` -> emit one fake device so the show_ime loop iterates once.
if [[ "${1:-}" == "devices" ]]; then
  if [[ "${JOURNEY_ABORT_ADB_MODE:-}" == "unresolved-device" ]]; then
    printf 'List of devices attached\n'
    exit 0
  fi
  printf 'List of devices attached\nemulator-5554\tdevice\n'
  exit 0
fi

if [[ "${1:-}" == "-s" ]]; then
  serial="${2:-}"
  shift 2
  [[ "$serial" == "emulator-5554" ]] || exit 44
fi

# Most fixtures only need a harmless adb. The abort-isolation fixture below
# opts into a stateful fake device through JOURNEY_ABORT_STUB_DIR.
state_dir="${JOURNEY_ABORT_STUB_DIR:-}"
if [[ -z "$state_dir" ]]; then
  case "${1:-}" in
    logcat)
      [[ "${2:-}" == "-c" ]] || printf 'generic-device-logcat\n'
      ;;
    exec-out)
      emit_valid_png
      ;;
    shell)
      if [[ "${2:-}" == "ps" ]]; then
        printf 'PID NAME\n'
      elif [[ "${2:-}" == "dumpsys" ]]; then
        printf 'generic dumpsys diagnostic\n'
      fi
      ;;
  esac
  exit 0
fi
mkdir -p "$state_dir"

case "${1:-}" in
  logcat)
    if [[ "${2:-}" == "-c" ]]; then
      [[ "${JOURNEY_ABORT_ADB_MODE:-}" != "logcat-clear-fail" ]] || exit 47
      : > "$state_dir/logcat-cleared"
    else
      [[ "${JOURNEY_ABORT_ADB_MODE:-}" != "logcat-fail" ]] || exit 48
      [[ "${JOURNEY_ABORT_ADB_MODE:-}" != "logcat-empty" ]] || exit 0
      printf 'device-logcat-%s\n' "$(cat "$state_dir/app-count" 2>/dev/null || printf '0')"
    fi
    ;;
  exec-out)
    [[ "${2:-}" == "screencap" && "${3:-}" == "-p" ]] || exit 45
    [[ "${JOURNEY_ABORT_ADB_MODE:-}" != "screenshot-fail" ]] || exit 49
    [[ "${JOURNEY_ABORT_ADB_MODE:-}" != "screenshot-empty" ]] || exit 0
    if [[ "${JOURNEY_ABORT_ADB_MODE:-}" == "screenshot-invalid" ]]; then
      printf 'not-a-png\n'
      exit 0
    fi
    if [[ "${JOURNEY_ABORT_ADB_MODE:-}" == "screenshot-truncated" ]]; then
      # Correct signature and a declared IHDR chunk, but truncated before the
      # chunk payload/CRC. A magic-byte-only check incorrectly accepts this.
      printf '\211PNG\r\n\032\n\000\000\000\015IHDR\000\000\000\001\000\000\000\001'
      exit 0
    fi
    if [[ "${JOURNEY_ABORT_ADB_MODE:-}" == "screenshot-bad-crc" ]]; then
      # Full chunk layout and decodable IDAT, but the IHDR CRC's final byte is
      # deliberately changed from octal 211 to 210.
      printf '\211\120\116\107\015\012\032\012\000\000\000\015\111\110\104\122\000\000\000\001\000\000\000\001\010\006\000\000\000\037\025\304\210\000\000\000\015\111\104\101\124\170\234\143\140\140\140\370\017\000\001\004\001\000\137\345\303\113\000\000\000\000\111\105\116\104\256\102\140\202'
      exit 0
    fi
    emit_valid_png
    ;;
  shell)
    shift
    if [[ "${1:-}" == "am" && "${2:-}" == "force-stop" ]]; then
      package="${3:-}"
      printf '%s\n' "$package" >> "$state_dir/force-stop.log"
      if [[ "$package" == "${JOURNEY_ABORT_FORCE_STOP_FAIL_PACKAGE:-}" ]]; then
        exit 46
      fi
      if [[ -f "$state_dir/device-processes" ]]; then
        awk -v package="$package" \
          '$0 != package && index($0, package ":") != 1 { print }' \
          "$state_dir/device-processes" > "$state_dir/device-processes.next"
        mv "$state_dir/device-processes.next" "$state_dir/device-processes"
      fi
    elif [[ "${1:-}" == "ps" ]]; then
      [[ "${JOURNEY_ABORT_ADB_MODE:-}" != "ps-fail" ]] || exit 50
      [[ "${JOURNEY_ABORT_ADB_MODE:-}" != "ps-empty" ]] || exit 0
      pid=4100
      if [[ -f "$state_dir/device-processes" ]]; then
        while IFS= read -r process_name; do
          [[ -n "$process_name" ]] || continue
          printf '%s %s\n' "$pid" "$process_name"
          pid=$((pid + 1))
        done < "$state_dir/device-processes"
      fi
    elif [[ "${1:-}" == "dumpsys" ]]; then
      if [[ "${2:-}" == "activity" && "${3:-}" == "processes" ]]; then
        [[ "${JOURNEY_ABORT_ADB_MODE:-}" != "dumpsys-processes-fail" ]] || exit 51
        [[ "${JOURNEY_ABORT_ADB_MODE:-}" != "dumpsys-processes-empty" ]] || exit 0
      fi
      if [[ "${2:-}" == "activity" && "${3:-}" == "top" ]]; then
        [[ "${JOURNEY_ABORT_ADB_MODE:-}" != "dumpsys-top-fail" ]] || exit 52
        [[ "${JOURNEY_ABORT_ADB_MODE:-}" != "dumpsys-top-empty" ]] || exit 0
      fi
      printf 'simulated dumpsys %s\n' "$*"
    fi
    ;;
esac
exit 0
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

# (d) the suite exited NON-ZERO so the workflow sees a failed first attempt and
#     then lets the classifier decide whether the summary is genuine-failure red
#     or timeout red.
[[ "$rc" -ne 0 ]] || fail "(d) suite exited 0 on a budget timeout — classifier would never inspect the timeout summary"
pass "(d) suite exited non-zero (rc=$rc) on a budget timeout"

# (e) DURABLE GUARD (issue #835 REOPENED, D31): replicate the workflow's
#     classifier ladder against this summary and assert a budget timeout is now a
#     HARD-RED timeout verdict — NOT advisory-green (which used to mask a
#     cut-short load-bearing class), NOT genuine-failure, NOT infra-abort.
#
# classify() mirrors .github/workflows/tests.yml "Classify emulator-journey
# result"; verdict_is_red() mirrors its exit code (exit 0 = green, exit 1 = red).
# A regression that re-downgrades the timeout to green would flip
# verdict_is_red() and fail this test.
classify() {
  local s="$1"
  local first_outcome="${2:-failure}"
  local retry_outcome="${3:-failure}"
  local first_concl="${4:-failure}"
  local retry_concl="${5:-failure}"
  local first_timeout="${6:-}"
  local first_failure="${7:-false}"
  if [[ -z "$first_timeout" ]]; then
    first_timeout="false"
    if [[ -f "$s" ]] \
      && ! grep -qE 'JOURNEY_FAILED|Failed BOTH attempts' "$s" \
      && grep -qE 'JOURNEY_STEP_TIMEOUT|Suite step time budget exhausted' "$s"; then
      first_timeout="true"
    fi
  fi
  if [[ "$first_outcome" == "success" ]]; then
    echo "PASS_FIRST"; return
  fi
  if [[ "$retry_outcome" == "success" ]]; then
    echo "PASS_RETRY"; return
  fi
  if [[ "$first_failure" == "true" ]]; then
    echo "FIRST_GENUINE_FAILURE"; return
  fi
  if [[ "$first_timeout" == "true" ]]; then
    # #835 REOPENED: the first-attempt budget timeout is now a HARD RED. NOT gated
    # by the #1458 storm — #835 behavior is intentionally unchanged.
    echo "FIRST_TIMEOUT_RED"; return
  fi
  if [[ "$first_outcome" == "cancelled" || "$retry_outcome" == "cancelled" || "$first_concl" == "cancelled" || "$retry_concl" == "cancelled" ]]; then
    echo "STEP_CANCELLED"; return
  fi
  if [[ -f "$s" ]] && grep -qE 'JOURNEY_FAILED|Failed BOTH attempts' "$s"; then
    echo "GENUINE_FAILURE"; return
  fi
  if [[ -f "$s" ]] && grep -qE 'JOURNEY_STEP_TIMEOUT|Suite step time budget exhausted' "$s"; then
    # #835 REOPENED: the both-failed budget timeout is now a HARD RED.
    echo "JOURNEY_TIMEOUT_RED"; return
  fi
  echo "INFRA_UNAVAILABLE"
}

# verdict_is_red — exit 0 (true) iff this verdict turns the job RED, mirroring
# the workflow classifier's exit code. ONLY a clean first/retry pass is green.
verdict_is_red() {
  case "$1" in
    PASS_FIRST|PASS_RETRY) return 1 ;;
    *) return 0 ;;
  esac
}

verdict="$(classify "$summary")"
[[ "$verdict" == "FIRST_TIMEOUT_RED" ]] \
  || fail "(e) classifier routed to '$verdict', expected FIRST_TIMEOUT_RED (a budget timeout must NOT be advisory-green — #835 durable guard)"
verdict_is_red "$verdict" \
  || fail "(e) budget-timeout verdict '$verdict' was classified GREEN — the #835 masking regressed"
pass "(e) workflow classifier routes a budget timeout to a HARD-RED verdict (#835 durable guard)"

# (f) stale-summary guard: the two cold-boot attempts share the same summary.md
#     path. If the retry is cancelled, an old `Failed BOTH attempts` summary
#     from the first attempt must NOT be used to report the retry as a genuine
#     failed-on-both-cold-boots verdict.
stale_summary="$SANDBOX/stale-summary.md"
printf '%s\n' \
  '# Per-push CI journey suite — summary' \
  'Failed BOTH attempts (`JOURNEY_FAILED` — job red):' \
  '- `com.pocketshell.app.StaleFirstAttemptTest`' \
  > "$stale_summary"
stale_verdict="$(classify "$stale_summary" failure cancelled failure cancelled)"
[[ "$stale_verdict" == "STEP_CANCELLED" ]] \
  || fail "(f) cancelled retry with stale Failed BOTH summary routed to '$stale_verdict', expected STEP_CANCELLED"
pass "(f) cancelled retry takes precedence over stale Failed BOTH summary"

# (g) genuine journey failure still wins over timeout markers and remains red.
mixed_summary="$SANDBOX/mixed-summary.md"
printf '%s\n' \
  '# Per-push CI journey suite — summary' \
  'Suite step time budget exhausted — JOURNEY_STEP_TIMEOUT (issue #835 / #470 stall — job red):' \
  '- `com.pocketshell.app.TimeoutOnlyClass`' \
  'Failed BOTH attempts (`JOURNEY_FAILED` — job red):' \
  '- `com.pocketshell.app.RealRegressionTest`' \
  > "$mixed_summary"
mixed_verdict="$(classify "$mixed_summary" failure failure failure failure false)"
[[ "$mixed_verdict" == "GENUINE_FAILURE" ]] \
  || fail "(g) mixed genuine-failure+timeout summary routed to '$mixed_verdict', expected GENUINE_FAILURE"
pass "(g) genuine Failed BOTH summary remains red even with timeout markers present"

# (h) workflow-real shared-summary overwrite: first attempt wrote a genuine
#     failure, retry overwrote summary.md with timeout-only content and did not
#     pass. The captured first_failure output must prevent advisory-green.
retry_timeout_summary="$SANDBOX/retry-timeout-summary.md"
printf '%s\n' \
  '# Per-push CI journey suite — summary' \
  'Suite step time budget exhausted — JOURNEY_STEP_TIMEOUT (issue #835 / #470 stall — job red):' \
  '- `com.pocketshell.app.RetryTimedOutClass`' \
  > "$retry_timeout_summary"
overwrite_verdict="$(classify "$retry_timeout_summary" failure failure failure failure false true)"
[[ "$overwrite_verdict" == "FIRST_GENUINE_FAILURE" ]] \
  || fail "(h) first genuine failure + retry timeout overwrite routed to '$overwrite_verdict', expected FIRST_GENUINE_FAILURE"
pass "(h) first genuine failure remains red when retry overwrites summary with timeout-only content"

# ---------------------------------------------------------------------------
# Issue #1458: raw ddmlib warning cardinality cannot classify emulator health.
# Historical and current runs show one optional warning per separate connected
# Gradle invocation, including completed Starting -> Finished blocks. Genuine
# JOURNEY_FAILED evidence therefore stays RED at every warning count.
echo "== #1458 raw console-warning count never masks JOURNEY_FAILED =="
storm_summary="$SANDBOX/storm-summary.md"
printf '%s\n' \
  '# Per-push CI journey suite — summary' \
  'Failed BOTH attempts (`JOURNEY_FAILED` — job red):' \
  '- `com.pocketshell.app.proof.Issue1206PrewarmEmptyCaptureSeedRetryJourneyE2eTest`' \
  > "$storm_summary"

# (p0) REGRESSION FIXTURE: ddmlib may emit one optional emulator-console warning
# for each separate connected Gradle invocation. Thirty ordinary completed
# invocations therefore produce >=30 warnings without proving emulator
# degradation. A genuine JOURNEY_FAILED beside this warning -> Starting ->
# Finished transcript must remain a genuine RED; raw warning cardinality is not
# an infra classifier.
ordinary_invocations_log="$SANDBOX/ordinary-connected-invocations.log"
for i in {1..30}; do
  printf '%s\n' \
    "WARNING | Failed to start Emulator console for 5554" \
    "Starting 1 tests on emulator-5554 (invocation $i)" \
    "Finished 1 tests on emulator-5554 (invocation $i)"
done > "$ordinary_invocations_log"

awk '
  NR % 3 == 1 && $0 !~ /Failed to start Emulator console for 5554/ { bad=1 }
  NR % 3 == 2 && $0 !~ /^Starting / { bad=1 }
  NR % 3 == 0 && $0 !~ /^Finished / { bad=1 }
  END { exit(bad || NR != 90) }
' "$ordinary_invocations_log" \
  || fail "(p0) malformed regression fixture: expected 30 warning -> Starting -> Finished blocks"
ordinary_warning_count="$(grep -c 'Failed to start Emulator console' "$ordinary_invocations_log" || true)"
[[ "$ordinary_warning_count" -eq 30 ]] \
  || fail "(p0) regression fixture warning count is $ordinary_warning_count, expected 30"
ordinary_verdict="$(classify "$storm_summary" failure failure failure failure false false "$ordinary_warning_count")"
[[ "$ordinary_verdict" == "GENUINE_FAILURE" ]] \
  || fail "(p0) 30 ordinary completed Gradle invocations plus JOURNEY_FAILED routed to '$ordinary_verdict'; raw per-invocation console warnings must never downgrade a genuine failure to INFRA"
pass "(p0) 30 warning -> Starting -> Finished blocks + JOURNEY_FAILED stay GENUINE_FAILURE"

# (p1) Mutation-sensitive behavioural boundary: counts on both sides of the old
# threshold, and the historic 104-warning count, all produce the same genuine
# failure. Reintroducing >=25 -> INFRA changes at least three of these outcomes.
for warning_count in 0 24 25 30 104; do
  count_verdict="$(classify "$storm_summary" failure failure failure failure false false "$warning_count")"
  [[ "$count_verdict" == "GENUINE_FAILURE" ]] \
    || fail "(p1) warning count $warning_count routed JOURNEY_FAILED to '$count_verdict', expected GENUINE_FAILURE"
  verdict_is_red "$count_verdict" \
    || fail "(p1) warning count $warning_count made a genuine failure green"
done
pass "(p1) warning counts 0/24/25/30/104 all keep JOURNEY_FAILED genuinely red"

# (p2) Captured first-attempt failure evidence must likewise stay RED regardless
# of raw warning count; retry summary overwrite semantics remain unchanged.
for warning_count in 0 25 104; do
  first_failure_verdict="$(classify "$storm_summary" failure failure failure failure false true "$warning_count")"
  [[ "$first_failure_verdict" == "FIRST_GENUINE_FAILURE" ]] \
    || fail "(p2) warning count $warning_count routed first_failure to '$first_failure_verdict', expected FIRST_GENUINE_FAILURE"
done
pass "(p2) raw warning counts never mask captured first-attempt failure evidence"

# (p3) Pass, #835 timeout, cancellation, and #771 no-summary ordering is
# unchanged by removal of the raw-count classifier.
[[ "$(classify "$storm_summary" success failure)" == "PASS_FIRST" ]] \
  || fail "(p3) first-attempt pass no longer routes to PASS_FIRST"
[[ "$(classify "$storm_summary" failure success)" == "PASS_RETRY" ]] \
  || fail "(p3) retry pass no longer routes to PASS_RETRY"
timeout_summary="$SANDBOX/timeout-summary.md"
printf '%s\n' \
  '# Per-push CI journey suite — summary' \
  'Suite step time budget exhausted — JOURNEY_STEP_TIMEOUT (issue #835 / #470 stall — job red):' \
  '- `com.pocketshell.app.TimedOutClass`' \
  > "$timeout_summary"
[[ "$(classify "$timeout_summary" failure failure failure failure true)" == "FIRST_TIMEOUT_RED" ]] \
  || fail "(p3) first-attempt budget timeout must stay FIRST_TIMEOUT_RED (#835)"
[[ "$(classify "$timeout_summary" failure failure failure failure false)" == "JOURNEY_TIMEOUT_RED" ]] \
  || fail "(p3) both-failed budget timeout must stay JOURNEY_TIMEOUT_RED (#835)"
[[ "$(classify "$timeout_summary" failure failure cancelled failure false)" == "STEP_CANCELLED" ]] \
  || fail "(p3) cancelled attempt must stay STEP_CANCELLED"
missing_summary="$SANDBOX/does-not-exist-summary.md"
[[ "$(classify "$missing_summary" failure failure)" == "INFRA_UNAVAILABLE" ]] \
  || fail "(p3) no-summary run must stay INFRA_UNAVAILABLE (#771)"
pass "(p3) pass / #835 timeout / cancellation / #771 no-summary semantics preserved"

# ---------------------------------------------------------------------------
# Issue #918: if the per-class `timeout` kills a Gradle invocation, the next
# retry can be poisoned by the still-running Gradle daemon/file-hash cache lock:
# "Cannot lock file hash cache ... locked by this process". Model that class:
# first :app invocation times out and leaves a poison marker; `gradlew --stop`
# clears it; the retry fails unless cleanup ran before it.
echo "== Timeout cleanup: per-class Gradle timeout stops daemons before retry =="
cat > "$SANDBOX/gradlew" <<'STUB'
#!/usr/bin/env bash
set -u

state_dir="${GRADLE_LOCK_STUB_DIR:?}"
mkdir -p "$state_dir"
printf '%s\n' "$*" >> "$state_dir/args.log"

if [[ "${1:-}" == "--stop" ]]; then
  rm -f "$state_dir/poisoned-lock"
  printf 'stop\n' >> "$state_dir/stop.log"
  exit 0
fi

if [[ "$*" == *":app:connectedDebugAndroidTest"* ]]; then
  count="$(cat "$state_dir/app-count" 2>/dev/null || printf '0')"
  count=$((count + 1))
  printf '%s' "$count" > "$state_dir/app-count"

  if [[ "$count" -eq 1 ]]; then
    touch "$state_dir/poisoned-lock"
    sleep 30
    exit 0
  fi

  if [[ -e "$state_dir/poisoned-lock" ]]; then
    printf 'Cannot lock file hash cache (%s/caches/fileHashes) as it has already been locked by this process.\n' "$state_dir" >&2
    exit 77
  fi
fi

exit 0
STUB
chmod +x "$SANDBOX/gradlew"

lock_stub_dir="$SANDBOX/lock-stub"
rm -rf "$lock_stub_dir"
mkdir -p "$lock_stub_dir"
out_lock="$SANDBOX/run-timeout-cleanup.log"
set +e
PATH="$STUBBIN:$PATH" \
  GRADLE_LOCK_STUB_DIR="$lock_stub_dir" \
  JOURNEY_STEP_BUDGET_SECS=3600 \
  JOURNEY_CLASS_TIMEOUT_SECS=1 \
  JOURNEY_GRADLE_STOP_TIMEOUT_SECS=5 \
  bash "$SANDBOX/scripts/ci-journey-suite.sh" > "$out_lock" 2>&1
rc_lock=$?
set -e

[[ "$rc_lock" -eq 0 ]] \
  || { sed -n '1,120p' "$out_lock"; fail "(i) timeout cleanup run exited $rc_lock; expected recovered success"; }
[[ -s "$lock_stub_dir/stop.log" ]] \
  || fail "(i) per-class timeout did not run gradlew --stop before retry"
grep -q 'GRADLE_TIMEOUT_CLEANUP:' "$out_lock" \
  || fail "(i) cleanup marker was not logged after timeout"
grep -q 'JOURNEY_FLAKE_RECOVERED:' "$out_lock" \
  || fail "(i) timed-out class did not recover on retry"
# #835 REOPENED: the per-class journey invocation now REUSES the Gradle daemon
# (no `--no-daemon` flag) to cut the cold-Gradle tax, but it is STILL one targeted
# `class=` invocation (per-class isolation preserved). Assert both: the journey
# task ran AND it did NOT carry the old `--no-daemon` flag.
grep -q -- ':app:connectedDebugAndroidTest' "$lock_stub_dir/args.log" \
  || fail "(i) per-class journey task did not run"
grep -q -- 'class=' "$lock_stub_dir/args.log" \
  || fail "(i) per-class journey invocation lost its single-class targeting"
if grep -q -- '--no-daemon' "$lock_stub_dir/args.log"; then
  fail "(i) journey invocation still passes --no-daemon; the #835 daemon-reuse tax cut regressed"
fi
if grep -q 'Cannot lock file hash cache' "$out_lock"; then
  sed -n '1,160p' "$out_lock"
  fail "(i) retry still saw the simulated Gradle file-hash lock"
fi
pass "(i) daemon-reused per-class timeout stops Gradle and retry avoids the poisoned file-hash lock"

# The hard timeout path matters too: GNU `timeout --kill-after` returns 137 when
# the child ignores TERM and is killed by the SIGKILL backstop. That path must
# still stop Gradle before retry or the #918 lock poisoning can survive.
echo "== Timeout cleanup: SIGKILL backstop also stops daemons before retry =="
cat > "$SANDBOX/gradlew" <<'STUB'
#!/usr/bin/env bash
set -u

state_dir="${GRADLE_LOCK_STUB_DIR:?}"
mkdir -p "$state_dir"
printf '%s\n' "$*" >> "$state_dir/args.log"

if [[ "${1:-}" == "--stop" ]]; then
  rm -f "$state_dir/poisoned-lock"
  printf 'stop\n' >> "$state_dir/stop.log"
  exit 0
fi

if [[ "$*" == *":app:connectedDebugAndroidTest"* ]]; then
  count="$(cat "$state_dir/app-count" 2>/dev/null || printf '0')"
  count=$((count + 1))
  printf '%s' "$count" > "$state_dir/app-count"

  if [[ "$count" -eq 1 ]]; then
    trap '' TERM
    touch "$state_dir/poisoned-lock"
    # Emit keepalive output faster than the silence window so the #1056 no-output
    # watchdog does NOT preempt — this must exercise the WALL-cap SIGKILL backstop
    # (rc=137): TERM is trapped/ignored, so `timeout --kill-after` SIGKILLs us.
    end=$((SECONDS + 30))
    while (( SECONDS < end )); do echo "PS_KEEPALIVE_1"; sleep 0.2; done
    exit 0
  fi

  if [[ -e "$state_dir/poisoned-lock" ]]; then
    printf 'Cannot lock file hash cache (%s/caches/fileHashes) as it has already been locked by this process.\n' "$state_dir" >&2
    exit 77
  fi
fi

exit 0
STUB
chmod +x "$SANDBOX/gradlew"

kill_stub_dir="$SANDBOX/kill-lock-stub"
rm -rf "$kill_stub_dir"
mkdir -p "$kill_stub_dir"
out_kill="$SANDBOX/run-timeout-kill-cleanup.log"
set +e
PATH="$STUBBIN:$PATH" \
  GRADLE_LOCK_STUB_DIR="$kill_stub_dir" \
  JOURNEY_STEP_BUDGET_SECS=3600 \
  JOURNEY_CLASS_TIMEOUT_SECS=1 \
  JOURNEY_CLASS_KILL_AFTER_SECS=1 \
  JOURNEY_GRADLE_STOP_TIMEOUT_SECS=5 \
  bash "$SANDBOX/scripts/ci-journey-suite.sh" > "$out_kill" 2>&1
rc_kill=$?
set -e

[[ "$rc_kill" -eq 0 ]] \
  || { sed -n '1,140p' "$out_kill"; fail "(j) SIGKILL timeout cleanup run exited $rc_kill; expected recovered success"; }
[[ -s "$kill_stub_dir/stop.log" ]] \
  || fail "(j) SIGKILL timeout did not run gradlew --stop before retry"
grep -q 'GRADLE_TIMEOUT_CLEANUP:' "$out_kill" \
  || fail "(j) cleanup marker was not logged after SIGKILL timeout"
grep -q 'JOURNEY_FLAKE_RECOVERED:' "$out_kill" \
  || fail "(j) SIGKILL-timed-out class did not recover on retry"
if grep -q 'Cannot lock file hash cache' "$out_kill"; then
  sed -n '1,180p' "$out_kill"
  fail "(j) retry still saw the simulated Gradle file-hash lock after SIGKILL timeout"
fi
pass "(j) SIGKILL timeout stops Gradle and retry avoids the poisoned file-hash lock"

# ---------------------------------------------------------------------------
# Issue #1458 exact-main abort contamination + artifact preservation.
#
# Attempt 1 wedges after installing a suffixed app/test pair and leaves both
# processes alive on the simulated device. `gradlew --stop` deliberately does
# NOT clear them. Attempt 2 refuses to run unless the harness force-stopped and
# verified those exact packages first. A similarly prefixed neighbouring lane
# must survive. Every later class overwrites the shared Gradle report locations,
# so attempt 1/2 evidence can only survive in per-class, per-attempt snapshots.
echo "== #1458 abort isolation: stale instrumentation is verified dead and attempt artifacts survive =="
cat > "$SANDBOX/gradlew" <<'STUB'
#!/usr/bin/env bash
set -u

state_dir="${JOURNEY_ABORT_STUB_DIR:?}"
mkdir -p "$state_dir"
printf '%s\n' "$*" >> "$state_dir/args.log"

if [[ "${1:-}" == "--stop" ]]; then
  printf 'stop\n' >> "$state_dir/stop.log"
  exit 0
fi

if [[ "$*" == *":app:connectedDebugAndroidTest"* ]]; then
  count="$(cat "$state_dir/app-count" 2>/dev/null || printf '0')"
  count=$((count + 1))
  printf '%s' "$count" > "$state_dir/app-count"
  artifact_label="later-class-$count"
  app_suffix=""
  for arg in "$@"; do
    case "$arg" in
      -PpocketshellAppIdSuffix=*) app_suffix="${arg#*=}" ;;
    esac
  done
  deep_count=0
  if [[ "$*" == *"DeepLinkSessionSwitchE2eTest"* ]]; then
    deep_count="$(cat "$state_dir/deep-count" 2>/dev/null || printf '0')"
    deep_count=$((deep_count + 1))
    printf '%s' "$deep_count" > "$state_dir/deep-count"
    artifact_label="$deep_count"
  fi

  output_root="$PWD/app/build"
  mkdir -p \
    "$output_root/outputs/androidTest-results/connected/debug" \
    "$output_root/outputs/connected_android_test_additional_output/debugAndroidTest/connected/fake-device/attempt" \
    "$output_root/reports/androidTests/connected/debug"
  # The first deep attempt models a real outer kill before UTP materializes
  # JUnit XML. The retry and ordinary classes still emit raw instrumentation
  # XML, which the harness must preserve separately from its own verdict.
  if [[ "$deep_count" -ne 1 ]]; then
    printf 'xml-attempt-%s\n' "$artifact_label" \
      > "$output_root/outputs/androidTest-results/connected/debug/TEST-fake.xml"
  fi
  printf 'screenshot-attempt-%s\n' "$artifact_label" \
    > "$output_root/outputs/connected_android_test_additional_output/debugAndroidTest/connected/fake-device/attempt/frame.png"
  printf 'report-attempt-%s\n' "$artifact_label" \
    > "$output_root/reports/androidTests/connected/debug/index.html"

  if [[ "$deep_count" -eq 1 ]]; then
    app_package="com.pocketshell.app"
    if [[ -n "$app_suffix" ]]; then
      app_package+=".$app_suffix"
    fi
    printf '%s\n' \
      "$app_package.test" \
      "$app_package" \
      "$app_package"0.test \
      > "$state_dir/device-processes"
    # Silent outer-timeout wedge. The device processes are independent of this
    # host child and therefore survive until exact package cleanup removes them.
    sleep 30
    exit 0
  fi

  if [[ "$deep_count" -eq 2 ]]; then
    app_package="com.pocketshell.app"
    if [[ -n "$app_suffix" ]]; then
      app_package+=".$app_suffix"
    fi
    if grep -qxE "$app_package([.]test)?" \
        "$state_dir/device-processes" 2>/dev/null; then
      printf 'STALE_DEVICE_PROCESS_REACHED_RETRY\n' >&2
      exit 91
    fi
  fi
fi

exit 0
STUB
chmod +x "$SANDBOX/gradlew"

abort_stub_dir="$SANDBOX/abort-isolation-stub"
mkdir -p "$abort_stub_dir"
out_abort="$SANDBOX/run-abort-isolation.log"
set +e
PATH="$STUBBIN:$PATH" \
  JOURNEY_ABORT_STUB_DIR="$abort_stub_dir" \
  POCKETSHELL_APP_ID_SUFFIX=i1458 \
  JOURNEY_STEP_BUDGET_SECS=600 \
  JOURNEY_CLASS_TIMEOUT_SECS=30 \
  JOURNEY_NO_OUTPUT_TIMEOUT_SECS=1 \
  JOURNEY_CLASS_KILL_AFTER_SECS=1 \
  JOURNEY_GRADLE_STOP_TIMEOUT_SECS=5 \
  bash "$SANDBOX/scripts/ci-journey-suite.sh" > "$out_abort" 2>&1
rc_abort=$?
set -e

[[ "$rc_abort" -eq 0 ]] \
  || { sed -n '1,180p' "$out_abort"; fail "(abort) isolated retry run exited $rc_abort; expected recovered success"; }
grep -q -- '-PpocketshellAppIdSuffix=i1458' "$abort_stub_dir/args.log" \
  || fail "(abort) suffixed cleanup identity was not bound to the Gradle invocation"
grep -q 'JOURNEY_FLAKE_RECOVERED:.*DeepLinkSessionSwitchE2eTest' "$out_abort" \
  || { sed -n '1,220p' "$out_abort"; fail "(abort) wedged attempt did not recover after verified device cleanup"; }
if grep -q 'STALE_DEVICE_PROCESS_REACHED_RETRY' "$out_abort"; then
  fail "(abort) retry started while stale instrumentation/app processes were alive"
fi
for exact_package in com.pocketshell.app.i1458.test com.pocketshell.app.i1458; do
  grep -qx "$exact_package" "$abort_stub_dir/force-stop.log" \
    || fail "(abort) exact package $exact_package was not force-stopped"
done
if grep -qx 'com.pocketshell.app' "$abort_stub_dir/force-stop.log" \
    || grep -qx 'com.pocketshell.app.i14580.test' "$abort_stub_dir/force-stop.log"; then
  fail "(abort) cleanup escaped the exact suffixed lane package boundary"
fi
grep -qx 'com.pocketshell.app.i14580.test' "$abort_stub_dir/device-processes" \
  || fail "(abort) similarly prefixed neighbouring lane was killed"

deep_class="com.pocketshell.app.proof.DeepLinkSessionSwitchE2eTest"
deep_artifact_key="$(
  bash -c 'source "$1"; journey_class_artifact_key "$2"' _ \
    "$SCRIPT_DIR/ci-journey-budget-functions.sh" "$deep_class"
)"
attempt_root="$SANDBOX/artifacts/ci-journey/class-attempts/app/$deep_artifact_key"
for attempt in 1 2; do
  attempt_dir="$attempt_root/attempt-$attempt"
  [[ -f "$attempt_dir/manifest.txt" ]] \
    || fail "(artifacts) missing attempt-$attempt manifest"
  [[ -f "$attempt_dir/attempt.log" ]] \
    || fail "(artifacts) missing attempt-$attempt combined invocation log"
  [[ -s "$attempt_dir/journey-harness-verdict.xml" ]] \
    || fail "(artifacts) missing attempt-$attempt harness verdict XML"
  [[ -f "$attempt_dir/android-test-outputs/app/build/reports/androidTests/connected/debug/index.html" ]] \
    || fail "(artifacts) missing attempt-$attempt HTML report snapshot"
  [[ -f "$attempt_dir/android-test-outputs/app/build/outputs/connected_android_test_additional_output/debugAndroidTest/connected/fake-device/attempt/frame.png" ]] \
    || fail "(artifacts) missing attempt-$attempt screenshot/additional-output snapshot"
  grep -q "screenshot-attempt-$attempt" \
    "$attempt_dir/android-test-outputs/app/build/outputs/connected_android_test_additional_output/debugAndroidTest/connected/fake-device/attempt/frame.png" \
    || fail "(artifacts) attempt-$attempt screenshot was overwritten by a later invocation"
done
attempt_1_raw_xml="$attempt_root/attempt-1/android-test-outputs/app/build/outputs/androidTest-results/connected/debug/TEST-fake.xml"
attempt_2_raw_xml="$attempt_root/attempt-2/android-test-outputs/app/build/outputs/androidTest-results/connected/debug/TEST-fake.xml"
[[ ! -e "$attempt_1_raw_xml" ]] \
  || fail "(artifacts) timeout attempt unexpectedly impersonated missing raw instrumentation XML"
[[ -f "$attempt_2_raw_xml" ]] \
  || fail "(artifacts) retry raw JUnit XML was not preserved"
grep -q 'xml-attempt-2' "$attempt_2_raw_xml" \
  || fail "(artifacts) retry raw JUnit XML was overwritten by a later invocation"
python3 - "$attempt_root" <<'PY' \
  || fail "(artifacts) harness verdict XML type/content is invalid or attempts overwrote one another"
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(sys.argv[1])
expected = {
    1: ("124", "outer_timeout", "absent_by_outer_timeout", "0", "verified_clean"),
    2: ("0", "pass", "present", "1", "not_required"),
}
for attempt, values in expected.items():
    xml_root = ET.parse(root / f"attempt-{attempt}" / "journey-harness-verdict.xml").getroot()
    assert xml_root.tag == "journey-harness-result"
    assert xml_root.attrib == {"format-version": "1"}
    assert xml_root.findtext("selector") == "com.pocketshell.app.proof.DeepLinkSessionSwitchE2eTest"
    assert xml_root.findtext("module") == "app"
    assert xml_root.findtext("attempt") == str(attempt)
    assert xml_root.findtext("primary-exit-code") == values[0]
    assert xml_root.findtext("classification") == values[1]
    assert xml_root.find("raw-junit").attrib == {"status": values[2], "count": values[3]}
    assert xml_root.find("snapshot").attrib == {"status": "complete"}
    assert xml_root.find("cleanup").attrib == {"status": values[4]}
PY
[[ -f "$attempt_root/attempt-1/device-logcat.txt" ]] \
  || fail "(artifacts) timeout attempt is missing the stable device logcat snapshot"
[[ -f "$attempt_root/attempt-1/failure-screen.png" ]] \
  || fail "(artifacts) timeout attempt is missing the stable fallback screenshot"
for diagnostic in device-processes.txt activity-processes.txt activity-top.txt; do
  [[ -f "$attempt_root/attempt-1/$diagnostic" ]] \
    || fail "(artifacts) timeout attempt is missing $diagnostic"
done
grep -q '^primary_exit_code=124$' "$attempt_root/attempt-1/manifest.txt" \
  || fail "(artifacts) timeout manifest did not preserve the primary rc=124"
grep -q '^application_id_suffix=i1458$' "$attempt_root/attempt-1/manifest.txt" \
  || fail "(artifacts) timeout manifest did not preserve the bound invocation suffix"
grep -q '^cleanup_status=verified_clean$' "$attempt_root/attempt-1/manifest.txt" \
  || fail "(artifacts) timeout manifest did not record verified cleanup"
grep -q '^primary_classification=outer_timeout$' "$attempt_root/attempt-1/manifest.txt" \
  || fail "(artifacts) timeout manifest did not classify the outer timeout"
grep -q '^raw_junit_status=absent_by_outer_timeout$' "$attempt_root/attempt-1/manifest.txt" \
  || fail "(artifacts) timeout manifest silently waived absent raw JUnit"
grep -q '^raw_junit_count=0$' "$attempt_root/attempt-1/manifest.txt" \
  || fail "(artifacts) timeout manifest has the wrong raw JUnit count"
grep -q '^primary_exit_code=0$' "$attempt_root/attempt-2/manifest.txt" \
  || fail "(artifacts) retry manifest did not preserve primary rc=0"
grep -q '^raw_junit_status=present$' "$attempt_root/attempt-2/manifest.txt" \
  || fail "(artifacts) retry manifest did not distinguish preserved raw JUnit"
grep -q '^raw_junit_count=1$' "$attempt_root/attempt-2/manifest.txt" \
  || fail "(artifacts) retry manifest has the wrong raw JUnit count"
pass "(abort) exact suffixed instrumentation/app processes are killed + verified before retry; neighbouring lane survives"
pass "(artifacts) harness verdicts, raw JUnit distinction, reports, logcat, screenshots, diagnostics, logs and manifests survive later overwrites"

# Ordinary instrumentation pass/failure XML remains raw evidence; the harness
# result is a separately typed document and safely escapes hostile selectors.
(
  REPO_ROOT="$SANDBOX"
  ARTIFACT_DIR="$SANDBOX/artifacts/ordinary-raw"
  JOURNEY_ADB="$STUBBIN/adb"
  JOURNEY_ABORT_STUB_DIR="$SANDBOX/ordinary-raw-state"
  export JOURNEY_ABORT_STUB_DIR
  mkdir -p "$JOURNEY_ABORT_STUB_DIR"
  printf '%s\n' com.pocketshell.app.unrelated \
    > "$JOURNEY_ABORT_STUB_DIR/device-processes"
  source "$SCRIPT_DIR/ci-journey-budget-functions.sh"
  selector='com.example.A<&"Test'
  begin_class_attempt_artifacts app "$selector" "" || exit 120
  mkdir -p "$REPO_ROOT/app/build/outputs/androidTest-results/connected/debug"
  printf '<testsuite tests="1" failures="1"/>\n' \
    > "$REPO_ROOT/app/build/outputs/androidTest-results/connected/debug/TEST-ordinary.xml"
  LAST_RUN_CLASS_PRIMARY_RC=7
  snapshot_connected_test_outputs app "$selector" 7 "" || exit 121
  finalize_class_attempt_manifest not_required complete || exit 122
  python3 - "$LAST_RUN_CLASS_ATTEMPT_DIR" "$selector" <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

attempt = Path(sys.argv[1])
selector = sys.argv[2]
root = ET.parse(attempt / "journey-harness-verdict.xml").getroot()
assert root.tag == "journey-harness-result"
assert root.findtext("selector") == selector
assert root.findtext("classification") == "failure"
assert root.find("raw-junit").attrib == {"status": "present", "count": "1"}
assert (attempt / "android-test-outputs/app/build/outputs/androidTest-results/connected/debug/TEST-ordinary.xml").is_file()
PY
) || fail "(artifacts) ordinary raw-JUnit failure or safely escaped harness verdict contract failed"
pass "(artifacts) ordinary raw JUnit stays separate; failure classification and XML escaping are exact"

# The failure half is load-bearing: if exact process absence cannot be proven,
# the primary timeout remains in the manifest, cleanup is separately marked
# failed, and NO retry or next app class may start.
cleanup_fail_stub_dir="$SANDBOX/abort-cleanup-failure-stub"
mkdir -p "$cleanup_fail_stub_dir"
out_cleanup_fail="$SANDBOX/run-abort-cleanup-failure.log"
set +e
PATH="$STUBBIN:$PATH" \
  JOURNEY_ABORT_STUB_DIR="$cleanup_fail_stub_dir" \
  JOURNEY_ABORT_FORCE_STOP_FAIL_PACKAGE=com.pocketshell.app.test \
  JOURNEY_STEP_BUDGET_SECS=600 \
  JOURNEY_CLASS_TIMEOUT_SECS=30 \
  JOURNEY_NO_OUTPUT_TIMEOUT_SECS=1 \
  JOURNEY_CLASS_KILL_AFTER_SECS=1 \
  JOURNEY_GRADLE_STOP_TIMEOUT_SECS=5 \
  JOURNEY_DEVICE_CLEANUP_TIMEOUT_SECS=1 \
  bash "$SANDBOX/scripts/ci-journey-suite.sh" > "$out_cleanup_fail" 2>&1
rc_cleanup_fail=$?
set -e

[[ "$rc_cleanup_fail" -ne 0 ]] \
  || fail "(cleanup-fail) unproven isolation incorrectly exited green"
grep -q 'JOURNEY_ISOLATION_FAILURE:.*primary_rc=124 cleanup_failed=1' "$out_cleanup_fail" \
  || { sed -n '1,180p' "$out_cleanup_fail"; fail "(cleanup-fail) hard isolation failure did not preserve primary vs cleanup status"; }
[[ "$(cat "$cleanup_fail_stub_dir/deep-count")" -eq 1 ]] \
  || fail "(cleanup-fail) harness retried the wedged class despite unproven isolation"
[[ "$(cat "$cleanup_fail_stub_dir/app-count")" -eq 2 ]] \
  || fail "(cleanup-fail) harness launched another app class after unproven isolation"
if grep -q -- '-PpocketshellAppIdSuffix=' "$cleanup_fail_stub_dir/args.log"; then
  fail "(cleanup-fail) base-package invocation unexpectedly carried an application-id suffix"
fi
cleanup_fail_manifest="$SANDBOX/artifacts/ci-journey/class-attempts/app/$deep_artifact_key/attempt-1/manifest.txt"
grep -q '^primary_exit_code=124$' "$cleanup_fail_manifest" \
  || fail "(cleanup-fail) manifest lost the primary timeout rc"
grep -q '^cleanup_status=failed$' "$cleanup_fail_manifest" \
  || fail "(cleanup-fail) manifest did not separately record cleanup failure"
grep -q '^device_cleanup_exit_code=1$' "$cleanup_fail_manifest" \
  || fail "(cleanup-fail) manifest did not preserve the device cleanup rc"
pass "(cleanup-fail) unproven process isolation hard-fails and blocks retry/next class while preserving primary+cleanup outcomes"

# Package derivation itself is pinned at both boundaries. This prevents a
# future cleanup rewrite from broad-matching every com.pocketshell.app.i* lane.
mapfile -t base_packages < <(
  bash -c 'source "$1"; journey_app_package_names ""' _ \
    "$SCRIPT_DIR/ci-journey-budget-functions.sh"
)
[[ "${base_packages[*]}" == "com.pocketshell.app.test com.pocketshell.app" ]] \
  || fail "(packages) base package mapping is not exact: ${base_packages[*]}"
mapfile -t suffixed_packages < <(
  bash -c 'source "$1"; journey_app_package_names i1458' _ \
    "$SCRIPT_DIR/ci-journey-budget-functions.sh"
)
[[ "${suffixed_packages[*]}" == "com.pocketshell.app.i1458.test com.pocketshell.app.i1458" ]] \
  || fail "(packages) suffixed package mapping is not exact: ${suffixed_packages[*]}"
pass "(packages) base and suffixed lanes resolve only their exact test/app packages"

# A failed force-stop remains a cleanup failure even if a later process-list
# sample happens to be empty; absence alone cannot erase a failed command.
(
  JOURNEY_ADB="$STUBBIN/adb"
  JOURNEY_ABORT_STUB_DIR="$SANDBOX/force-stop-command-failure"
  JOURNEY_ABORT_FORCE_STOP_FAIL_PACKAGE=com.pocketshell.app.test
  export JOURNEY_ABORT_STUB_DIR JOURNEY_ABORT_FORCE_STOP_FAIL_PACKAGE
  mkdir -p "$JOURNEY_ABORT_STUB_DIR"
  source "$SCRIPT_DIR/ci-journey-budget-functions.sh"
  if cleanup_device_after_class_abort app "com.example.ForceStopFailureTest" ""; then
    exit 69
  fi
) || fail "(packages) failed exact force-stop was erased by an empty process-list sample"
pass "(packages) exact force-stop command failure stays fail-closed even when process absence is observed"

# Every required per-attempt device capture is fail-closed. A command error,
# unresolved device, empty output, or invalid screenshot must make the snapshot
# fail so run_class can latch the harness and refuse the next invocation.
echo "== #1458 artifact capture failures are never marked complete =="
artifact_capture_must_fail() {
  local mode="$1"
  local primary_rc="$2"
  local capture_state="$SANDBOX/capture-$mode"
  mkdir -p "$capture_state"
  printf '%s\n' com.pocketshell.app.test > "$capture_state/device-processes"

  set +e
  (
    REPO_ROOT="$SANDBOX"
    ARTIFACT_DIR="$SANDBOX/artifacts/capture-$mode"
    JOURNEY_ADB="$STUBBIN/adb"
    JOURNEY_ABORT_STUB_DIR="$capture_state"
    export JOURNEY_ABORT_STUB_DIR
    source "$SCRIPT_DIR/ci-journey-budget-functions.sh"
    begin_class_attempt_artifacts app "com.example.CaptureFailureTest" "" \
      || exit 70
    export JOURNEY_ABORT_ADB_MODE="$mode"
    if snapshot_connected_test_outputs \
        app "com.example.CaptureFailureTest" "$primary_rc" ""; then
      exit 71
    fi
    grep -q '^snapshot_status=failed$' \
      "$LAST_RUN_CLASS_ATTEMPT_DIR/manifest.txt" || exit 78
    if grep -q '^snapshot_status=complete$' \
        "$LAST_RUN_CLASS_ATTEMPT_DIR/manifest.txt"; then
      exit 79
    fi
  )
  capture_rc=$?
  set -e
  [[ "$capture_rc" -eq 0 ]] \
    || fail "(captures) mode=$mode was accepted or setup failed (rc=$capture_rc)"
}

for mode in logcat-fail logcat-empty; do
  artifact_capture_must_fail "$mode" 0
done
for mode in \
  ps-fail ps-empty \
  dumpsys-processes-fail dumpsys-processes-empty \
  dumpsys-top-fail dumpsys-top-empty \
  screenshot-fail screenshot-empty screenshot-invalid \
  screenshot-truncated screenshot-bad-crc; do
  artifact_capture_must_fail "$mode" 124
done

(
  REPO_ROOT="$SANDBOX"
  ARTIFACT_DIR="$SANDBOX/artifacts/capture-unresolved"
  JOURNEY_ADB="$STUBBIN/adb"
  JOURNEY_ABORT_STUB_DIR="$SANDBOX/capture-unresolved"
  JOURNEY_ABORT_ADB_MODE=unresolved-device
  export JOURNEY_ABORT_ADB_MODE
  mkdir -p "$JOURNEY_ABORT_STUB_DIR"
  source "$SCRIPT_DIR/ci-journey-budget-functions.sh"
  if begin_class_attempt_artifacts app "com.example.UnresolvedDeviceTest" ""; then
    exit 72
  fi
) || fail "(captures) unresolved device did not fail closed before invocation"

(
  REPO_ROOT="$SANDBOX"
  ARTIFACT_DIR="$SANDBOX/artifacts/capture-clear-fail"
  JOURNEY_ADB="$STUBBIN/adb"
  JOURNEY_ABORT_STUB_DIR="$SANDBOX/capture-clear-fail"
  JOURNEY_ABORT_ADB_MODE=logcat-clear-fail
  export JOURNEY_ABORT_ADB_MODE JOURNEY_ABORT_STUB_DIR
  mkdir -p "$JOURNEY_ABORT_STUB_DIR"
  source "$SCRIPT_DIR/ci-journey-budget-functions.sh"
  if begin_class_attempt_artifacts app "com.example.LogcatClearFailureTest" ""; then
    exit 73
  fi
) || fail "(captures) failed per-attempt logcat reset was not fail-closed"
pass "(captures) command errors, empty content, invalid PNG, unresolved device and logcat-reset failure all fail closed"

# run_class must propagate a manifest-finalization failure into the same hard
# harness latch; returning the primary success would silently lose metadata.
cat > "$SANDBOX/finalize-gradlew" <<'STUB'
#!/usr/bin/env bash
exit 0
STUB
chmod +x "$SANDBOX/finalize-gradlew"
(
  REPO_ROOT="$SANDBOX"
  ARTIFACT_DIR="$SANDBOX/artifacts/snapshot-failure"
  GRADLEW="$SANDBOX/finalize-gradlew"
  JOURNEY_ADB="$STUBBIN/adb"
  JOURNEY_ABORT_STUB_DIR="$SANDBOX/snapshot-state"
  JOURNEY_ABORT_ADB_MODE=logcat-fail
  JOURNEY_STEP_BUDGET_SECS=30
  JOURNEY_CLASS_TIMEOUT_SECS=10
  JOURNEY_NO_OUTPUT_TIMEOUT_SECS=5
  JOURNEY_CLASS_KILL_AFTER_SECS=1
  JOURNEY_GRADLE_STOP_TIMEOUT_SECS=1
  SUITE_START=$SECONDS
  export JOURNEY_ABORT_STUB_DIR JOURNEY_ABORT_ADB_MODE
  mkdir -p "$JOURNEY_ABORT_STUB_DIR"
  source "$SCRIPT_DIR/ci-journey-budget-functions.sh"
  set +e
  run_class "com.example.SnapshotFailureTest" >/dev/null 2>&1
  snapshot_rc=$?
  set -e
  [[ "$snapshot_rc" -eq "$JOURNEY_HARNESS_FAILURE_RC" ]] || exit 80
  [[ "$JOURNEY_ABORT_ISOLATION_FAILED" -eq 1 ]] || exit 81
  [[ "$LAST_RUN_CLASS_ARTIFACT_SNAPSHOT_FAILED" -eq 1 ]] || exit 82
) || fail "(captures) run_class ignored required snapshot-capture failure"
pass "(captures) required snapshot failure hard-latches the harness and cannot return primary success"

(
  REPO_ROOT="$SANDBOX"
  ARTIFACT_DIR="$SANDBOX/artifacts/finalize-failure"
  GRADLEW="$SANDBOX/finalize-gradlew"
  JOURNEY_ADB="$STUBBIN/adb"
  JOURNEY_ABORT_STUB_DIR="$SANDBOX/finalize-state"
  JOURNEY_STEP_BUDGET_SECS=30
  JOURNEY_CLASS_TIMEOUT_SECS=10
  JOURNEY_NO_OUTPUT_TIMEOUT_SECS=5
  JOURNEY_CLASS_KILL_AFTER_SECS=1
  JOURNEY_GRADLE_STOP_TIMEOUT_SECS=1
  SUITE_START=$SECONDS
  mkdir -p "$JOURNEY_ABORT_STUB_DIR"
  source "$SCRIPT_DIR/ci-journey-budget-functions.sh"
  finalize_class_attempt_manifest() { return 74; }
  set +e
  run_class "com.example.ManifestFinalizeFailureTest" >/dev/null 2>&1
  finalize_rc=$?
  set -e
  [[ "$finalize_rc" -eq "$JOURNEY_HARNESS_FAILURE_RC" ]] || exit 75
  [[ "$JOURNEY_ABORT_ISOLATION_FAILED" -eq 1 ]] || exit 76
  [[ "$LAST_RUN_CLASS_ARTIFACT_SNAPSHOT_FAILED" -eq 1 ]] || exit 77
) || fail "(manifest) run_class ignored manifest-finalization failure"
pass "(manifest) finalization failure hard-latches the harness and cannot return primary success"

for verdict_failure_mode in synthesis malformed; do
  (
    REPO_ROOT="$SANDBOX"
    ARTIFACT_DIR="$SANDBOX/artifacts/verdict-$verdict_failure_mode"
    GRADLEW="$SANDBOX/finalize-gradlew"
    JOURNEY_ADB="$STUBBIN/adb"
    JOURNEY_ABORT_STUB_DIR="$SANDBOX/verdict-$verdict_failure_mode-state"
    JOURNEY_STEP_BUDGET_SECS=30
    JOURNEY_CLASS_TIMEOUT_SECS=10
    JOURNEY_NO_OUTPUT_TIMEOUT_SECS=5
    JOURNEY_CLASS_KILL_AFTER_SECS=1
    JOURNEY_GRADLE_STOP_TIMEOUT_SECS=1
    SUITE_START=$SECONDS
    export JOURNEY_ABORT_STUB_DIR
    mkdir -p "$JOURNEY_ABORT_STUB_DIR"
    source "$SCRIPT_DIR/ci-journey-budget-functions.sh"
    if [[ "$verdict_failure_mode" == synthesis ]]; then
      write_harness_verdict_xml() { return 74; }
    else
      write_harness_verdict_xml() {
        printf '<journey-harness-result><broken>' > "$1"
      }
    fi
    set +e
    run_class "com.example.VerdictFailureTest" >/dev/null 2>&1
    verdict_rc=$?
    set -e
    [[ "$verdict_rc" -eq "$JOURNEY_HARNESS_FAILURE_RC" ]] || exit 123
    [[ "$JOURNEY_ABORT_ISOLATION_FAILED" -eq 1 ]] || exit 124
    [[ "$LAST_RUN_CLASS_ARTIFACT_SNAPSHOT_FAILED" -eq 1 ]] || exit 125
    if grep -q '^harness_verdict_status=complete$' \
        "$LAST_RUN_CLASS_ATTEMPT_DIR/manifest.txt"; then
      exit 126
    fi
  ) || fail "(verdict) $verdict_failure_mode verdict failure did not hard-latch the harness"
done
pass "(verdict) synthesis and parse-back validation failures hard-latch without false finalization"

# Mutation proof: removing the parse-back call must make this probe fail. This
# demonstrates that malformed-writer coverage is load-bearing rather than
# passing merely because synthesis happened to return nonzero.
mutated_budget="$SANDBOX/ci-journey-budget-no-verdict-validation.sh"
sed '/^[[:space:]]*validate_harness_verdict_xml "\$verdict"/,+1d' \
  "$SCRIPT_DIR/ci-journey-budget-functions.sh" > "$mutated_budget"
set +e
(
  source "$mutated_budget"
  LAST_RUN_CLASS_ATTEMPT_DIR="$SANDBOX/mutated-verdict-attempt"
  LAST_RUN_CLASS_SELECTOR="com.example.MutatedTest"
  LAST_RUN_CLASS_MODULE=app
  LAST_RUN_CLASS_ATTEMPT=1
  LAST_RUN_CLASS_PRIMARY_RC=124
  LAST_RUN_CLASS_PRIMARY_CLASSIFICATION=outer_timeout
  LAST_RUN_CLASS_RAW_JUNIT_STATUS=absent_by_outer_timeout
  LAST_RUN_CLASS_RAW_JUNIT_COUNT=0
  LAST_RUN_CLASS_SNAPSHOT_STATUS=complete
  mkdir -p "$LAST_RUN_CLASS_ATTEMPT_DIR"
  : > "$LAST_RUN_CLASS_ATTEMPT_DIR/manifest.txt"
  write_harness_verdict_xml() {
    printf '<journey-harness-result><broken>' > "$1"
  }
  finalize_class_attempt_manifest verified_clean complete
)
mutation_rc=$?
set -e
[[ "$mutation_rc" -eq 0 ]] \
  || fail "(verdict-mutation) validation-removal mutant did not demonstrate the malformed-XML escape"
pass "(verdict-mutation) removing parse-back validation is detected by the malformed-writer regression"

# Readable paths retain a stable hash of the full class selector. This separates
# names that sanitize identically and makes hostile traversal characters inert.
collision_a="$(
  bash -c 'source "$1"; journey_class_artifact_key "com.example.A#method"' _ \
    "$SCRIPT_DIR/ci-journey-budget-functions.sh"
)"
collision_b="$(
  bash -c 'source "$1"; journey_class_artifact_key "com.example.A_method"' _ \
    "$SCRIPT_DIR/ci-journey-budget-functions.sh"
)"
[[ "$collision_a" != "$collision_b" ]] \
  || fail "(paths) sanitized class collision produced one artifact key: $collision_a"
for hostile_class in \
  '../../com.example.A#method' \
  '/absolute/path/Test' \
  '.../../#'; do
  hostile_key="$(
    bash -c 'source "$1"; journey_class_artifact_key "$2"' _ \
      "$SCRIPT_DIR/ci-journey-budget-functions.sh" "$hostile_class"
  )"
  [[ -n "$hostile_key" && "$hostile_key" != "." && "$hostile_key" != ".." ]] \
    || fail "(paths) hostile class produced empty/dot artifact key"
  [[ "$hostile_key" != */* ]] \
    || fail "(paths) hostile class escaped into a nested path: $hostile_key"
  [[ "$hostile_key" =~ --[0-9a-f]{16}$ ]] \
    || fail "(paths) hostile class key lacks its stable collision-resistant hash: $hostile_key"
done
pass "(paths) colliding selectors separate; slash/absolute/traversal selectors stay one hashed safe component"

echo "== Timeout cleanup: repeated SIGKILL timeout stays classified as timeout =="
cat > "$SANDBOX/gradlew" <<'STUB'
#!/usr/bin/env bash
set -u

state_dir="${GRADLE_LOCK_STUB_DIR:?}"
mkdir -p "$state_dir"
printf '%s\n' "$*" >> "$state_dir/args.log"

if [[ "${1:-}" == "--stop" ]]; then
  rm -f "$state_dir/poisoned-lock"
  printf 'stop\n' >> "$state_dir/stop.log"
  exit 0
fi

if [[ "$*" == *":app:connectedDebugAndroidTest"* ]]; then
  count="$(cat "$state_dir/app-count" 2>/dev/null || printf '0')"
  count=$((count + 1))
  printf '%s' "$count" > "$state_dir/app-count"
  trap '' TERM
  touch "$state_dir/poisoned-lock"
  # Emit keepalive output faster than the silence window so the #1056 no-output
  # watchdog does NOT preempt — this must exercise the WALL-cap SIGKILL backstop
  # (rc=137) on EVERY attempt: TERM is trapped/ignored, so `timeout --kill-after`
  # SIGKILLs us each time.
  end=$((SECONDS + 30))
  while (( SECONDS < end )); do echo "PS_KEEPALIVE_$count"; sleep 0.2; done
  exit 0
fi

exit 0
STUB
chmod +x "$SANDBOX/gradlew"

repeat_kill_stub_dir="$SANDBOX/repeat-kill-lock-stub"
rm -rf "$repeat_kill_stub_dir"
mkdir -p "$repeat_kill_stub_dir"
out_repeat_kill="$SANDBOX/run-timeout-repeat-kill-cleanup.log"
set +e
PATH="$STUBBIN:$PATH" \
  GRADLE_LOCK_STUB_DIR="$repeat_kill_stub_dir" \
  JOURNEY_STEP_BUDGET_SECS=5 \
  JOURNEY_CLASS_TIMEOUT_SECS=1 \
  JOURNEY_CLASS_KILL_AFTER_SECS=1 \
  JOURNEY_GRADLE_STOP_TIMEOUT_SECS=5 \
  bash "$SANDBOX/scripts/ci-journey-suite.sh" > "$out_repeat_kill" 2>&1
rc_repeat_kill=$?
set -e

summary_repeat_kill="$SANDBOX/artifacts/ci-journey/summary.md"
[[ "$rc_repeat_kill" -ne 0 ]] \
  || fail "(k) repeated SIGKILL timeout exited 0; expected timeout-red"
[[ -f "$summary_repeat_kill" ]] \
  || fail "(k) repeated SIGKILL timeout did not write summary.md"
grep -q 'JOURNEY_STEP_TIMEOUT' "$summary_repeat_kill" \
  || { cat "$summary_repeat_kill"; fail "(k) repeated SIGKILL timeout summary missing JOURNEY_STEP_TIMEOUT"; }
grep -q 'JOURNEY_STEP_TIMEOUT: .*rc=137' "$out_repeat_kill" \
  || { sed -n '1,180p' "$out_repeat_kill"; fail "(k) repeated SIGKILL timeout did not exercise rc=137 classification"; }
if grep -qE 'JOURNEY_FAILED|Failed BOTH attempts' "$summary_repeat_kill"; then
  cat "$summary_repeat_kill"
  fail "(k) repeated SIGKILL timeout was misclassified as a genuine failed-both regression"
fi
stop_count="$(wc -l < "$repeat_kill_stub_dir/stop.log" 2>/dev/null || printf '0')"
[[ "$stop_count" -ge 2 ]] \
  || fail "(k) repeated SIGKILL timeout did not clean up after both attempts"
pass "(k) repeated SIGKILL timeout remains JOURNEY_STEP_TIMEOUT, not JOURNEY_FAILED"

echo "== Timeout cleanup: immediate SIGKILL remains a genuine failure =="
cat > "$SANDBOX/gradlew" <<'STUB'
#!/usr/bin/env bash
set -u

state_dir="${GRADLE_LOCK_STUB_DIR:?}"
mkdir -p "$state_dir"
printf '%s\n' "$*" >> "$state_dir/args.log"

if [[ "${1:-}" == "--stop" ]]; then
  sleep "${GRADLE_STOP_STUB_SLEEP_SECS:-0}"
  printf 'stop\n' >> "$state_dir/stop.log"
  exit 0
fi

if [[ "$*" == *":app:connectedDebugAndroidTest"* ]]; then
  count="$(cat "$state_dir/app-count" 2>/dev/null || printf '0')"
  count=$((count + 1))
  printf '%s' "$count" > "$state_dir/app-count"
  kill -9 "$$"
fi

exit 0
STUB
chmod +x "$SANDBOX/gradlew"

early_kill_stub_dir="$SANDBOX/early-kill-lock-stub"
rm -rf "$early_kill_stub_dir"
mkdir -p "$early_kill_stub_dir"
out_early_kill="$SANDBOX/run-timeout-early-kill-cleanup.log"
set +e
PATH="$STUBBIN:$PATH" \
  GRADLE_LOCK_STUB_DIR="$early_kill_stub_dir" \
  GRADLE_STOP_STUB_SLEEP_SECS=3 \
  JOURNEY_STEP_BUDGET_SECS=2 \
  JOURNEY_CLASS_TIMEOUT_SECS=30 \
  JOURNEY_CLASS_KILL_AFTER_SECS=1 \
  JOURNEY_GRADLE_STOP_TIMEOUT_SECS=5 \
  bash "$SANDBOX/scripts/ci-journey-suite.sh" > "$out_early_kill" 2>&1
rc_early_kill=$?
set -e

summary_early_kill="$SANDBOX/artifacts/ci-journey/summary.md"
[[ "$rc_early_kill" -ne 0 ]] \
  || fail "(l) immediate SIGKILL exited 0; expected genuine failure"
[[ -f "$summary_early_kill" ]] \
  || fail "(l) immediate SIGKILL did not write summary.md"
grep -qE 'JOURNEY_FAILED|Failed BOTH attempts' "$summary_early_kill" \
  || { cat "$summary_early_kill"; fail "(l) immediate SIGKILL summary missing genuine failed-both marker"; }
if grep -q 'JOURNEY_STEP_TIMEOUT: .*rc=137' "$out_early_kill"; then
  sed -n '1,180p' "$out_early_kill"
  fail "(l) immediate SIGKILL was misclassified as a timeout-owned rc=137"
fi
[[ -s "$early_kill_stub_dir/stop.log" ]] \
  || fail "(l) immediate SIGKILL still should run Gradle cleanup before retry"
pass "(l) immediate SIGKILL remains JOURNEY_FAILED even when cleanup spends the budget"

# ---------------------------------------------------------------------------
# Issue #1056: the NO-OUTPUT (silence) watchdog HARD-KILLS a wedged child.
#
# The reopen symptom: a `connectedDebugAndroidTest` child WEDGED and emitted zero
# output for ~24 min until the job-level 95-min wall CANCELLED the whole step,
# beating the #835 STEP_TIMEOUT classifier so the verdict was LOST. The watchdog
# must hard-kill a SILENT child at the small silence bound — WELL BELOW a large
# wall cap — and still route the class through the STEP_TIMEOUT classifier so a
# trustworthy timeout-only summary is always written.
#
# This models ONE class that wedges SILENTLY (emits NOTHING) on BOTH attempts,
# under a LARGE wall cap (30s) and generous budget, with a tiny 2s silence
# window. If the wall cap (not silence) were the only bound, each attempt would
# burn the full 30s; the watchdog must cut each attempt at ~2s. All OTHER
# classes pass instantly so the run stays fast.
#
# Issue #1802 — the "silence beat the wall cap" proof is STRUCTURAL, never a
# wall-clock bound on the machine running the test. The previous (o2) asserted
# that the WHOLE suite run finished in under 25s. But that run also spawns ~89
# instant-pass gradle/adb stub invocations plus per-attempt artifact
# snapshotting, so the number it measured was this host's process-spawn
# throughput, not the watchdog: on an idle box it read 22s against the 25s
# ceiling, and on a contended box (load 14-19) it read 49-59s while EVERY
# attempt was still being cut at 2s by the silence watchdog. It therefore failed
# on unmodified `main` for a reason unrelated to the behaviour under test. The
# bound is replaced by two signals no machine load can move:
#
#   CAUSE  — each attempt's OWN attempt.log carries the silence-watchdog marker
#            for the exact injected 2s window (the wall-cap path never logs it),
#            and its manifest records the attempt as an outer_timeout. Asserted
#            per attempt, so a watchdog that fired only on the first attempt and
#            let the retry run to the wall cap is caught too.
#   EFFECT — the wedge child is SELF-WITNESSING: it records on the FILESYSTEM
#            (not on stdout, which nobody reads once run_bounded returns) if it
#            SURVIVES JOURNEY_WEDGE_SURVIVAL_SECS. That milestone sits at HALF
#            the wall cap and 5x the silence bound, and the child only advances
#            through `sleep` (a kernel timer, not host throughput), so a
#            correctly cut attempt can never reach it and a wall-cap-bounded
#            attempt always does. Machine load can only slow the child down,
#            i.e. it can only push this assertion further from failing.
#
# (o2-live) below proves that milestone really is reachable, so the EFFECT
# assertion can never pass vacuously.
echo "== #1056 no-output watchdog: a SILENT wedge is hard-killed at the silence bound =="
wedge_survival_secs=15
wedge_witness_dir="$SANDBOX/wedge-witness"
rm -rf "$wedge_witness_dir"
cat > "$SANDBOX/gradlew" <<'STUB'
#!/usr/bin/env bash
set -u
[[ "${1:-}" == "--stop" ]] && exit 0
if [[ "$*" == *":app:connectedDebugAndroidTest"* && "$*" == *"DeepLinkSessionSwitchE2eTest"* ]]; then
  # SILENT wedge (emits NOTHING) that would run far past the 2s silence window
  # and on to the wall cap. It is SELF-WITNESSING (issue #1802): it records its
  # attempt number, and creates a survival witness FILE only if it is still
  # alive after JOURNEY_WEDGE_SURVIVAL_SECS. A filesystem witness is used
  # deliberately — stdout is unreadable once run_bounded has closed the fifo.
  witness_dir="${JOURNEY_WEDGE_WITNESS_DIR:?}"
  mkdir -p "$witness_dir"
  wedge_attempt="$(cat "$witness_dir/attempts" 2>/dev/null || printf '0')"
  wedge_attempt=$((wedge_attempt + 1))
  printf '%s' "$wedge_attempt" > "$witness_dir/attempts"
  # Sleep in 1s slices, not one long sleep, so the watchdog's TERM is honoured
  # within a second instead of being deferred behind the whole wedge.
  trap 'exit 143' TERM
  for _ in $(seq "${JOURNEY_WEDGE_SURVIVAL_SECS:?}"); do sleep 1; done
  : > "$witness_dir/survived-attempt-$wedge_attempt"
  # The (o2-live) liveness probe opts out of the trailing hang so the probe is
  # bounded by the child's OWN completion rather than by a wall cap, leaving no
  # host-throughput term anywhere in it.
  [[ "${JOURNEY_WEDGE_EXIT_AFTER_WITNESS:-0}" == "1" ]] && exit 0
  for _ in $(seq 60); do sleep 1; done
  exit 0
fi
exit 0
STUB
chmod +x "$SANDBOX/gradlew"

out_wedge="$SANDBOX/run-no-output-wedge.log"
wedge_start=$SECONDS
set +e
PATH="$STUBBIN:$PATH" \
  JOURNEY_STEP_BUDGET_SECS=600 \
  JOURNEY_CLASS_TIMEOUT_SECS=30 \
  JOURNEY_NO_OUTPUT_TIMEOUT_SECS=2 \
  JOURNEY_CLASS_KILL_AFTER_SECS=1 \
  JOURNEY_GRADLE_STOP_TIMEOUT_SECS=5 \
  JOURNEY_WEDGE_WITNESS_DIR="$wedge_witness_dir" \
  JOURNEY_WEDGE_SURVIVAL_SECS="$wedge_survival_secs" \
  bash "$SANDBOX/scripts/ci-journey-suite.sh" > "$out_wedge" 2>&1
rc_wedge=$?
set -e
wedge_elapsed=$((SECONDS - wedge_start))

summary_wedge="$SANDBOX/artifacts/ci-journey/summary.md"
# (o1) the watchdog fired with the exact silence-window marker (silence path, not
#      the coarse wall cap).
grep -q 'JOURNEY_NO_OUTPUT_WATCHDOG: no output for 2s' "$out_wedge" \
  || { sed -n '1,80p' "$out_wedge"; fail "(o) no-output watchdog did not fire on a silent wedge"; }
pass "(o1) silence watchdog fired (JOURNEY_NO_OUTPUT_WATCHDOG logged for the 2s window)"

# (o2) STRUCTURAL (issue #1802): the 2s SILENCE watchdog — NOT the 30s wall cap —
#      ended EVERY attempt of the wedged class. Asserted on per-attempt cause
#      markers plus a filesystem survival witness; NOT on how long this box took.
wedge_attempt_root="$SANDBOX/artifacts/ci-journey/class-attempts/app/$deep_artifact_key"
# Anti-vacuous first: the wedge really ran, on the attempt AND the retry. Without
# this, every assertion below would pass trivially if the stub never executed.
wedge_attempts_run="$(cat "$wedge_witness_dir/attempts" 2>/dev/null || printf '0')"
[[ "$wedge_attempts_run" -eq 2 ]] \
  || { sed -n '1,80p' "$out_wedge"; fail "(o) the wedged class ran $wedge_attempts_run attempt(s), expected 2 — the fixture never exercised the watchdog on both the attempt and the retry"; }
for wedge_attempt in 1 2; do
  wedge_attempt_log="$wedge_attempt_root/attempt-$wedge_attempt/attempt.log"
  wedge_attempt_manifest="$wedge_attempt_root/attempt-$wedge_attempt/manifest.txt"
  [[ -f "$wedge_attempt_log" && -f "$wedge_attempt_manifest" ]] \
    || fail "(o) attempt $wedge_attempt of the wedged class left no per-attempt artifacts under $wedge_attempt_root"
  grep -qxF 'JOURNEY_NO_OUTPUT_WATCHDOG: no output for 2s' "$wedge_attempt_log" \
    || { cat "$wedge_attempt_log"; fail "(o) attempt $wedge_attempt was NOT ended by the 2s silence watchdog — its own attempt.log carries no silence marker, so the coarse wall cap bounded it"; }
  grep -qxF 'primary_classification=outer_timeout' "$wedge_attempt_manifest" \
    || { cat "$wedge_attempt_manifest"; fail "(o) attempt $wedge_attempt was not recorded as an outer_timeout"; }
done
shopt -s nullglob
wedge_survivors=("$wedge_witness_dir"/survived-attempt-*)
shopt -u nullglob
[[ "${#wedge_survivors[@]}" -eq 0 ]] \
  || { sed -n '1,80p' "$out_wedge"; fail "(o) ${#wedge_survivors[@]} wedge attempt(s) SURVIVED ${wedge_survival_secs}s — half the 30s wall cap and 5x the 2s silence bound — so the watchdog logged but did not actually cut the attempt short"; }
pass "(o2) both attempts were cut by the 2s silence watchdog, not the 30s wall cap (per-attempt silence markers + outer_timeout manifests, and no attempt survived ${wedge_survival_secs}s) [diagnostic only, deliberately NOT asserted: whole-run wall time ${wedge_elapsed}s]"

# (o2-live) LIVENESS of the (o2) EFFECT assertion. "No survival witness" is only
# meaningful if the milestone is actually reachable — otherwise (o2) would pass
# for the wrong reason (dead stub, unreachable milestone, broken witness path).
# Drive the REAL run_bounded once with the silence window widened to the wall cap
# (run_bounded clamps it there), so the watchdog can no longer cut the wedge
# short: the SAME child with the SAME milestone must now reach it. The child opts
# out of its trailing hang and the bound is a deliberately loose 120s, so this
# probe is bounded by the child finishing its own 15s of `sleep` (a kernel timer)
# with 8x headroom — no host-throughput term, in either direction.
live_witness_dir="$SANDBOX/wedge-liveness-witness"
rm -rf "$live_witness_dir"
(
  set +e
  JOURNEY_NO_OUTPUT_TIMEOUT_SECS=120
  JOURNEY_CLASS_KILL_AFTER_SECS=1
  JOURNEY_WEDGE_WITNESS_DIR="$live_witness_dir"
  JOURNEY_WEDGE_SURVIVAL_SECS="$wedge_survival_secs"
  JOURNEY_WEDGE_EXIT_AFTER_WITNESS=1
  export JOURNEY_WEDGE_WITNESS_DIR JOURNEY_WEDGE_SURVIVAL_SECS JOURNEY_WEDGE_EXIT_AFTER_WITNESS
  source "$SCRIPT_DIR/ci-journey-budget-functions.sh"
  run_bounded 120 "$SANDBOX/gradlew" :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.proof.DeepLinkSessionSwitchE2eTest
) > /dev/null 2>&1 || true
[[ -f "$live_witness_dir/survived-attempt-1" ]] \
  || fail "(o2-live) the wedge never reached its ${wedge_survival_secs}s survival milestone even with the silence watchdog widened to the wall cap — (o2)'s no-survivor assertion is unreachable and would pass vacuously"
pass "(o2-live) the ${wedge_survival_secs}s survival witness IS reachable when the silence watchdog is not tight — (o2)'s no-survivor assertion is live, not vacuous"

# (o3) despite the wedge, summary.md was written (the verdict is NEVER lost to a
#      job-level wall) and carries the STEP_TIMEOUT marker for the wedged class.
[[ -f "$summary_wedge" ]] \
  || fail "(o) no-output wedge did not write summary.md — the verdict would be lost to the job wall"
grep -q 'JOURNEY_STEP_TIMEOUT' "$summary_wedge" \
  || { cat "$summary_wedge"; fail "(o) no-output wedge summary missing JOURNEY_STEP_TIMEOUT marker"; }
grep -q 'DeepLinkSessionSwitchE2eTest' "$summary_wedge" \
  || { cat "$summary_wedge"; fail "(o) no-output wedge summary did not name the wedged class"; }
# The wedge is a timeout casualty, NOT a genuine twice-failed regression.
if grep -qE 'Failed BOTH attempts' "$summary_wedge"; then
  cat "$summary_wedge"
  fail "(o) no-output wedge was misclassified as a genuine Failed BOTH regression"
fi
pass "(o3) summary.md written with a timeout-only STEP_TIMEOUT verdict (verdict never lost)"

# (o4) the suite exited non-zero AND the workflow classifier routes it to a HARD
#      RED timeout verdict — the whole point of AC1 (the STEP_TIMEOUT classifier
#      always fires a trustworthy summary, never a lost job-cancel).
[[ "$rc_wedge" -ne 0 ]] \
  || fail "(o) no-output wedge exited 0 — the classifier would never inspect the timeout summary"
wedge_verdict="$(classify "$summary_wedge")"
[[ "$wedge_verdict" == "FIRST_TIMEOUT_RED" ]] \
  || fail "(o) no-output wedge routed to '$wedge_verdict', expected FIRST_TIMEOUT_RED"
verdict_is_red "$wedge_verdict" \
  || fail "(o) no-output wedge verdict '$wedge_verdict' was classified GREEN"
pass "(o4) suite exited non-zero (rc=$rc_wedge); classifier routes the wedge to FIRST_TIMEOUT_RED"

# (n) Positive control: a class that STREAMS output steadily inside the silence
#     window must NOT be killed by the watchdog — every emitted line resets the
#     silence timer. This proves no false-positive: a slow-but-progressing class
#     runs to completion, it is only a SILENT wedge that dies.
echo "== #1056 no-output watchdog: a streaming class is NOT falsely killed =="
cat > "$SANDBOX/gradlew" <<'STUB'
#!/usr/bin/env bash
set -u
[[ "${1:-}" == "--stop" ]] && exit 0
if [[ "$*" == *":app:connectedDebugAndroidTest"* && "$*" == *"DeepLinkSessionSwitchE2eTest"* ]]; then
  # Stream for ~4s — longer than the 2s silence window, so the class only
  # survives because every emitted line resets the silence timer. Issue #1802:
  # emit every 0.4s rather than every 1s. The assertion is unchanged (the
  # watchdog must NOT fire); the smaller gap just puts 5x margin, instead of 2x,
  # between the fixture's silence gaps and the injected 2s window, so a
  # scheduling hiccup on a contended box cannot manufacture a false positive.
  for i in 1 2 3 4 5 6 7 8 9 10; do echo "PS_STREAM_LINE_$i"; sleep 0.4; done
  exit 0
fi
exit 0
STUB
chmod +x "$SANDBOX/gradlew"

out_stream="$SANDBOX/run-no-output-stream.log"
set +e
PATH="$STUBBIN:$PATH" \
  JOURNEY_STEP_BUDGET_SECS=600 \
  JOURNEY_CLASS_TIMEOUT_SECS=30 \
  JOURNEY_NO_OUTPUT_TIMEOUT_SECS=2 \
  JOURNEY_CLASS_KILL_AFTER_SECS=1 \
  bash "$SANDBOX/scripts/ci-journey-suite.sh" > "$out_stream" 2>&1
rc_stream=$?
set -e

summary_stream="$SANDBOX/artifacts/ci-journey/summary.md"
if grep -q 'JOURNEY_NO_OUTPUT_WATCHDOG' "$out_stream"; then
  sed -n '1,80p' "$out_stream"
  fail "(n) watchdog FALSELY killed a class that streamed output within the window"
fi
# The streamed lines were echoed live (proves run_bounded relays output).
grep -q 'PS_STREAM_LINE_10' "$out_stream" \
  || { sed -n '1,80p' "$out_stream"; fail "(n) streamed output was not relayed live by run_bounded"; }
[[ -f "$summary_stream" ]] || fail "(n) streaming control did not write summary.md"
if grep -q 'JOURNEY_STEP_TIMEOUT' "$summary_stream"; then
  cat "$summary_stream"
  fail "(n) streaming class wrongly produced a JOURNEY_STEP_TIMEOUT marker"
fi
[[ "$rc_stream" -eq 0 ]] \
  || { sed -n '1,80p' "$out_stream"; fail "(n) streaming control exited non-zero (rc=$rc_stream)"; }
pass "(n) a steadily-streaming class is NOT killed (no false positive; output relayed live)"

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
# mis-routed to JOURNEY_TIMEOUT_RED if the ladder were reached).
neg_verdict="$(classify "$summary2")"
[[ "$neg_verdict" != "JOURNEY_TIMEOUT_RED" && "$neg_verdict" != "FIRST_TIMEOUT_RED" ]] \
  || fail "(neg) clean run mis-classified as a timeout"
[[ "$rc2" -eq 0 ]] \
  || { cat "$out2"; fail "(neg) clean run (generous budget, every class verdicts) exited non-zero (rc=$rc2)"; }
pass "(neg) clean run: no false timeout (summary has no timeout marker; rc=$rc2)"

# (neg-2) ACCEPTANCE CRITERION 2 (#835 REOPENED): with a budget that fits, EVERY
# selected load-bearing class reaches a verdict — none is silently cut short.
# Assert the historically cut-short classes (BackgroundGrace,
# BoundedGraceSessionHold) and a share/composer/folder tail representative are
# present in the run AND that NO class is bucketed as a budget timeout (no "cut
# short / not run" section).
for cut_short_class in \
  com.pocketshell.app.proof.BackgroundGraceReconnectE2eTest \
  com.pocketshell.app.proof.BoundedGraceSessionHoldJourneyE2eTest; do
  grep -q "$cut_short_class" "$summary2" \
    || { cat "$summary2"; fail "(neg-2) $cut_short_class missing from a healthy full run — selection drifted"; }
done
# The "cut short / not run" budget bucket must be ABSENT on a healthy run.
if grep -q 'cut short / not run' "$summary2"; then
  cat "$summary2"
  fail "(neg-2) a healthy full run wrongly bucketed a class as cut-short / not-run"
fi
# Every selected class launched gradle (one daemon-reused invocation each) —
# count the per-class launches in the run log and assert it matches the FULL
# class count (all quoted JOURNEY_CLASSES entries, not just the $FQCN_PREFIX
# ones), so a healthy run is proven to reach a verdict for EVERY class.
class_count="$(awk '/^JOURNEY_CLASSES=\(/{f=1;next} /^\)/{f=0} f && /^[[:space:]]*"/{c++} END{print c+0}' "$SANDBOX/scripts/ci-journey-suite.sh")"
[[ "$class_count" -ge 80 ]] \
  || fail "(neg-2) parsed only $class_count journey classes — enumeration changed unexpectedly (expected the full ~83-class load-bearing set)"
launched="$(grep -c '>>> JOURNEY CLASS:.*(attempt 1)' "$out2" || true)"
[[ "$launched" -eq "$class_count" ]] \
  || { sed -n '1,40p' "$out2"; fail "(neg-2) launched $launched/$class_count journey classes on a healthy run — some class never reached a verdict"; }
pass "(neg-2) healthy run reaches a verdict for all $class_count load-bearing classes (none cut short)"

# ---------------------------------------------------------------------------
# (shard-stable) ACCEPTANCE — Issue #1862: shard membership is STABLE under
# edits to the JOURNEY_CLASSES array.
#
# THE DEFECT: the partition used to be round-robin by array INDEX, so a class's
# shard was a function of how many entries preceded it. On the #1845 merge
# (f2aa9a8e) registering ONE class at index 36 moved 111 of 147 classes to a
# different shard; two landed behind ~12 co-tenants they had never run behind,
# hit a latent timing failure, and `main` went RED from a commit whose diff could
# not reach either class. Worse than the red itself: the natural suspect is the
# commit that went in, so the investigation starts from a false premise.
#
# The fix partitions by `hash(class_name) % total`, so a class's shard depends
# only on its OWN name. This block drives the REAL helper over the REAL array and
# asserts the load-bearing property directly: after inserting / removing an entry
# ANYWHERE, ZERO other classes move.
#
# Every assertion below carries its RED CONTROL — the same fixture is re-run
# through the OLD index reducer, which MUST move a large fraction. Without that,
# "zero classes moved" would also pass against a hash that put everything on one
# shard, or against a fixture that never actually differed (G6/G3).
echo "== CI-matrix sharding: membership is stable under array insertion (#1862) =="

SELECTION_HELPER="$SCRIPT_DIR/ci-journey-class-selection-functions.sh"
[[ -f "$SELECTION_HELPER" ]] \
  || fail "(shard-stable) cannot find ci-journey-class-selection-functions.sh at $SELECTION_HELPER"
# shellcheck source=scripts/ci-journey-class-selection-functions.sh
source "$SELECTION_HELPER"

declare -F select_effective_journey_classes > /dev/null \
  || fail "(shard-stable) the selection helper does not define select_effective_journey_classes"

# The REAL array, parsed out of the REAL suite exactly as the harness guards do.
mapfile -t REAL_JOURNEY_CLASSES < <(
  awk '
    /^JOURNEY_CLASSES=\(/ { f = 1; next }
    /^\)/                 { f = 0 }
    f && match($0, /"[^"]+"/) {
      s = substr($0, RSTART + 1, RLENGTH - 2)
      gsub(/\$FQCN_PREFIX/, "com.pocketshell.app.proof", s)
      print s
    }
  ' "$REAL_SUITE"
)
real_class_count="${#REAL_JOURNEY_CLASSES[@]}"
[[ "$real_class_count" -ge 80 ]] \
  || fail "(shard-stable) parsed only $real_class_count journey classes from the real suite — enumeration changed unexpectedly"

# Membership maps: "class<TAB>shard" lines, directly comparable.
#
# The SHIPPING map drives the REAL production entry point
# `select_effective_journey_classes` once per shard index and records what it
# actually selected — NOT the hash helper it happens to use internally. That
# matters: the property under test is which classes a matrix leg RUNS, so the
# assertion has to be on the function the suite calls (G6). If the partition is
# ever reimplemented, this guard keeps testing the right thing.
shard_map_shipping() {   # $1 = shard total; classes on stdin
  local total="$1" idx
  mapfile -t JOURNEY_CLASSES
  for (( idx = 0; idx < total; idx++ )); do
    EFFECTIVE_JOURNEY_CLASSES=()
    POCKETSHELL_JOURNEY_CI_SHARD_TOTAL="$total" \
      POCKETSHELL_JOURNEY_CI_SHARD_INDEX="$idx" \
      select_effective_journey_classes > /dev/null
    (( ${#EFFECTIVE_JOURNEY_CLASSES[@]} > 0 )) || continue
    printf "%s\t$idx\n" "${EFFECTIVE_JOURNEY_CLASSES[@]}"
  done
}
shard_map_index() {      # RED CONTROL: the pre-#1862 `index % total` reducer
  local total="$1" c i=0
  while IFS= read -r c; do
    [[ -n "$c" ]] || continue
    printf '%s\t%s\n' "$c" "$(( i % total ))"
    i=$((i + 1))
  done
}

# How many of the ORIGINAL classes changed shard between two membership maps
# (the inserted/removed class itself is excluded — it is expected to differ).
moved_count() {       # $1 = before map file, $2 = after map file
  join -t $'\t' -j 1 -o 1.1,1.2,2.2 \
    <(sort -t $'\t' -k1,1 "$1") <(sort -t $'\t' -k1,1 "$2") \
    | awk -F '\t' '$2 != $3 { n++ } END { print n + 0 }'
}

shard_total_probe=3
NEW_ENTRY="com.pocketshell.app.proof.Issue1862SyntheticNewlyRegisteredJourneyE2eTest"
printf '%s\n' "${REAL_JOURNEY_CLASSES[@]}" > "$SANDBOX/classes-base.txt"

# The two baselines, computed ONCE.
shard_map_shipping  "$shard_total_probe" < "$SANDBOX/classes-base.txt" > "$SANDBOX/map-hash-base.tsv"
shard_map_index "$shard_total_probe" < "$SANDBOX/classes-base.txt" > "$SANDBOX/map-index-base.tsv"

# Sanity: the baseline itself must not be degenerate — every shard must be used,
# otherwise "nothing moved" below is trivially true.
distinct_shards="$(cut -f2 "$SANDBOX/map-hash-base.tsv" | sort -u | wc -l)"
[[ "$distinct_shards" -eq "$shard_total_probe" ]] \
  || fail "(shard-stable) the hash partition used only $distinct_shards of $shard_total_probe shards on the real list — degenerate partition"

# INSERTION at the head, at the #1845 position (36), and at the tail. Removal at
# 36 too: an entry being deleted is the same class of edit and must not re-roll
# the suite either.
for edit in "insert:0" "insert:36" "insert:$real_class_count" "remove:36"; do
  op="${edit%%:*}"
  pos="${edit##*:}"
  (( pos <= real_class_count )) || continue
  edited="$SANDBOX/classes-$op-$pos.txt"
  if [[ "$op" == "insert" ]]; then
    { head -n "$pos" "$SANDBOX/classes-base.txt"
      printf '%s\n' "$NEW_ENTRY"
      tail -n +"$((pos + 1))" "$SANDBOX/classes-base.txt"
    } > "$edited"
    expected_len=$((real_class_count + 1))
  else
    { head -n "$pos" "$SANDBOX/classes-base.txt"
      tail -n +"$((pos + 2))" "$SANDBOX/classes-base.txt"
    } > "$edited"
    expected_len=$((real_class_count - 1))
  fi
  [[ "$(wc -l < "$edited")" -eq "$expected_len" ]] \
    || fail "(shard-stable) fixture build failed for $op at $pos (expected $expected_len entries)"
  cmp -s "$edited" "$SANDBOX/classes-base.txt" \
    && fail "(shard-stable) fixture for $op at $pos is IDENTICAL to the base list — the edit never happened, so the comparison below is vacuous"

  shard_map_shipping  "$shard_total_probe" < "$edited" > "$SANDBOX/map-hash-$op-$pos.tsv"
  shard_map_index "$shard_total_probe" < "$edited" > "$SANDBOX/map-index-$op-$pos.tsv"

  moved_hash="$(moved_count "$SANDBOX/map-hash-base.tsv" "$SANDBOX/map-hash-$op-$pos.tsv")"
  moved_index="$(moved_count "$SANDBOX/map-index-base.tsv" "$SANDBOX/map-index-$op-$pos.tsv")"

  # The load-bearing assertion.
  [[ "$moved_hash" -eq 0 ]] \
    || fail "(shard-stable) $op at index $pos moved $moved_hash pre-existing classes to a different shard — membership must depend ONLY on the class name (#1862)"

  # RED CONTROL: the same fixture through the OLD reducer must move a large
  # fraction, proving the zero above is a real property and not a no-op fixture.
  # A tail edit legitimately moves nothing under index sharding, so only the
  # edits that precede other entries carry the control.
  if [[ "$pos" -lt "$real_class_count" ]]; then
    min_expected_moved=$(( (real_class_count - pos) / 2 ))
    [[ "$moved_index" -ge "$min_expected_moved" ]] \
      || fail "(shard-stable) red control is not live: the OLD index reducer moved only $moved_index classes for $op at $pos (expected >= $min_expected_moved) — the fixture cannot distinguish the two partitions"
    echo "  ok: $op at $pos — hash moved 0, old index reducer moved $moved_index of $real_class_count (control live)"
  else
    echo "  ok: $op at $pos — hash moved 0 (tail edit; index reducer is trivially stable here, no control claimed)"
  fi
done
pass "(shard-stable) inserting/removing a journey class anywhere moves ZERO other classes; the old index reducer re-rolls up to two thirds of the list"

# (shard-stable-det) The partition must be a pure function of the class NAMES —
# the same on every runner. Two runners disagreeing would break the disjoint/
# complete property across the matrix (a class run twice, or never). The two
# realistic ways that breaks are locale-dependent character semantics and a
# dependency on evaluation order, so pin both, through the production entry
# point rather than its internals.
for det_locale in C C.UTF-8 en_US.UTF-8 POSIX; do
  LC_ALL="$det_locale" shard_map_shipping "$shard_total_probe" \
    < "$SANDBOX/classes-base.txt" > "$SANDBOX/map-det-$det_locale.tsv"
  cmp -s <(sort "$SANDBOX/map-det-$det_locale.tsv") <(sort "$SANDBOX/map-hash-base.tsv") \
    || fail "(shard-stable-det) the partition differs under LC_ALL=$det_locale — two runners could disagree about which shard owns a class"
done
# Order independence: reversing the input array must not change any assignment.
tac "$SANDBOX/classes-base.txt" > "$SANDBOX/classes-reversed.txt"
shard_map_shipping "$shard_total_probe" < "$SANDBOX/classes-reversed.txt" > "$SANDBOX/map-reversed.tsv"
reversed_moved="$(moved_count "$SANDBOX/map-hash-base.tsv" "$SANDBOX/map-reversed.tsv")"
[[ "$reversed_moved" -eq 0 ]] \
  || fail "(shard-stable-det) reversing the array moved $reversed_moved classes — the partition still depends on position, not only on the class name"
reversed_moved_index="$(moved_count "$SANDBOX/map-index-base.tsv" \
  <(shard_map_index "$shard_total_probe" < "$SANDBOX/classes-reversed.txt"))"
[[ "$reversed_moved_index" -ge $(( real_class_count / 2 )) ]] \
  || fail "(shard-stable-det) red control is not live: reversing the array moved only $reversed_moved_index classes under the OLD index reducer"
pass "(shard-stable-det) the partition is identical under 4 locales and under a fully reversed array (the old index reducer moves $reversed_moved_index of $real_class_count when reversed)"

# (shard-balance) Hash partitioning trades EXACT balance (index round-robin was
# +/-1) for stability. That trade must stay bounded. TWO different properties are
# at stake and they need different bands, so both are asserted explicitly:
#
#   `uniform` — the hash behaves like a uniform partition at all. Band is 3
#     standard deviations of the binomial (sd = sqrt(n*(T-1))/T), which is the
#     right shape for a statistical partition and does NOT tighten as the list
#     shrinks. A guard tighter than this is flaky-by-construction: a legitimate
#     uniform draw would trip it on some future list. This is the control that
#     actually catches a broken/collapsing hash.
#
#   `budget` — additionally, an UPPER cap at 125% of the ideal share, applied
#     only to the total=3 configuration CI really runs. A shard 25% over its
#     share spends 25% more wall-clock against the #835 suite budget, so it is
#     worth a human look. Only the upper bound is capped: an UNDER-full shard
#     costs nothing, and capping it too would double the false-trip surface for
#     no benefit.
#
# Characterised over the REAL list at three totals and over synthetic 60/300-class
# lists, so the property is measured across sizes rather than sampled once.
assert_balanced() {   # $1 = label, $2 = total, $3 = budget|uniform, classes on stdin
  local label="$1" total="$2" mode="$3" n ideal tol low high cap s count
  local -a counts=()
  local tmp="$SANDBOX/balance-$label-$total.tsv"
  shard_map_shipping "$total" > "$tmp"
  n="$(wc -l < "$tmp")"
  ideal=$(( n / total ))
  # 3 * sd of Binomial(n, 1/total): sd = sqrt(n*(total-1))/total.
  tol="$(awk -v n="$n" -v t="$total" 'BEGIN { printf "%d", int(3 * sqrt(n * (t - 1)) / t) + 1 }')"
  low=$(( ideal - tol ));  (( low < 0 )) && low=0
  high=$(( ideal + tol ))
  for (( s = 0; s < total; s++ )); do
    count="$(awk -F '\t' -v s="$s" '$2 == s { n++ } END { print n + 0 }' "$tmp")"
    counts+=("$count")
    [[ "$count" -ge "$low" && "$count" -le "$high" ]] \
      || fail "(shard-balance) $label total=$total: shard $s has $count of $n classes, outside the 3-sigma uniform band [$low,$high] (ideal $ideal) — the class hash is not partitioning uniformly"
  done
  if [[ "$mode" == "budget" ]]; then
    cap=$(( (n * 125 + (total * 100) - 1) / (total * 100) ))
    for (( s = 0; s < total; s++ )); do
      [[ "${counts[$s]}" -le "$cap" ]] \
        || fail "(shard-balance) $label total=$total: shard $s carries ${counts[$s]} of $n classes, over the $cap budget cap (125% of the $ideal ideal) — that leg spends >25% extra wall-clock against the #835 suite budget"
    done
    echo "  ok: balance $label total=$total -> ${counts[*]} (ideal $ideal, uniform band [$low,$high], budget cap $cap)"
  else
    echo "  ok: balance $label total=$total -> ${counts[*]} (ideal $ideal, uniform band [$low,$high])"
  fi
}
assert_balanced "real" 3 budget  < "$SANDBOX/classes-base.txt"
assert_balanced "real" 2 uniform < "$SANDBOX/classes-base.txt"
assert_balanced "real" 4 uniform < "$SANDBOX/classes-base.txt"
for synth_n in 60 300; do
  seq 1 "$synth_n" \
    | awk '{ printf "com.pocketshell.app.proof.Synthetic%04dJourneyE2eTest\n", $1 }' \
      > "$SANDBOX/classes-synth-$synth_n.txt"
  assert_balanced "synth$synth_n" 3 uniform < "$SANDBOX/classes-synth-$synth_n.txt"
done
# RED CONTROLS for the two bands. Each must fail, and must fail for ITS OWN
# reason — a control that trips the other band would leave one of the two
# assertions unproven (G6). Both shadow inside a subshell only.
degen_out="$SANDBOX/balance-degenerate.log"
(
  journey_class_shard_hash() { printf '7'; }   # every class -> one shard
  assert_balanced "degenerate" 3 uniform < "$SANDBOX/classes-base.txt"
) > "$degen_out" 2>&1 \
  && fail "(shard-balance) red control is not live: a hash that puts EVERY class on one shard passed the 3-sigma uniform band"
grep -q 'uniform band' "$degen_out" \
  || { cat "$degen_out"; fail "(shard-balance) the degenerate control failed for the wrong reason — it must trip the 3-sigma UNIFORM band"; }

# A partition that is uniform-looking but ONE class over the 125% cap: it must
# pass the 3-sigma band and still be rejected by the budget cap, proving the cap
# is load-bearing on its own rather than shadowed by the wider uniform band.
tilt_cap=$(( (real_class_count * 125 + 299) / 300 ))
tilt_target=$(( tilt_cap + 1 ))
tilt_out="$SANDBOX/balance-tilted.log"
(
  TILT_TARGET="$tilt_target"
  shard_map_shipping() {
    local _total="${1:-3}" c i=0
    while IFS= read -r c; do
      [[ -n "$c" ]] || continue
      if (( i < TILT_TARGET )); then
        printf '%s\t0\n' "$c"
      else
        printf '%s\t%s\n' "$c" "$(( 1 + (i % 2) ))"
      fi
      i=$((i + 1))
    done
  }
  assert_balanced "tilted" 3 budget < "$SANDBOX/classes-base.txt"
) > "$tilt_out" 2>&1 \
  && fail "(shard-balance) red control is not live: a shard one class over the 125% budget cap passed the budget check"
grep -q 'budget cap' "$tilt_out" \
  || { cat "$tilt_out"; fail "(shard-balance) the tilted control ($tilt_target of $real_class_count on one shard) failed for the wrong reason — it must trip the 125% BUDGET CAP, not the uniform band"; }
pass "(shard-balance) the partition is uniform to 3 sigma on the real list (totals 2/3/4) and on synthetic 60/300-class lists, and stays under the 125% budget cap at total=3; degenerate and tilted partitions are both rejected"

# ---------------------------------------------------------------------------
# (shard) ACCEPTANCE — Issue #835 (REOPENED): CI-matrix sharding partitions
# JOURNEY_CLASSES so each leg runs a DISJOINT ~1/N slice and the UNION of all
# legs is the FULL set. This is the structural fix that lets the suite finish
# within the budget+cap (each leg ~1/N the wall-clock + a far healthier
# emulator). Drive the REAL suite once per shard (instant gradle stub, generous
# budget) and assert: (a) each shard launches ~class_count/N classes, (b) the
# slices are pairwise DISJOINT (no class on two shards), (c) the UNION is every
# class (none dropped), (d) the core-terminal proofs run on EVERY shard.
#
# Issue #1862 widened (a)'s tolerance from +/-2 to +/-25%: the partition is now
# by class-name hash, which is statistically rather than exactly balanced. The
# tight per-shard spread is pinned separately by (shard-balance) above; what THIS
# block owns is that the end-to-end suite really runs the partition it computes.
echo "== CI-matrix sharding: hash partition is disjoint + complete =="
cat > "$SANDBOX/gradlew" <<'STUB'
#!/usr/bin/env bash
exit 0
STUB
chmod +x "$SANDBOX/gradlew"

shard_total=3
declare -A seen_class_shard=()
shard_union_count=0
for shard_idx in 0 1 2; do
  shard_log="$SANDBOX/run-shard-$shard_idx.log"
  set +e
  PATH="$STUBBIN:$PATH" \
    JOURNEY_STEP_BUDGET_SECS=3600 \
    JOURNEY_CLASS_TIMEOUT_SECS=420 \
    POCKETSHELL_JOURNEY_CI_SHARD_TOTAL="$shard_total" \
    POCKETSHELL_JOURNEY_CI_SHARD_INDEX="$shard_idx" \
    bash "$SANDBOX/scripts/ci-journey-suite.sh" > "$shard_log" 2>&1
  rc_shard=$?
  set -e
  [[ "$rc_shard" -eq 0 ]] \
    || { sed -n '1,40p' "$shard_log"; fail "(shard) shard $shard_idx exited $rc_shard; expected clean pass on the instant stub"; }
  mapfile -t shard_classes < <(grep -E '>>> JOURNEY CLASS: [^ ]+ \(attempt 1\)' "$shard_log" | awk '{print $4}')
  shard_n="${#shard_classes[@]}"
  lo=$(( class_count * 75 / (shard_total * 100) ))
  hi=$(( (class_count * 125 + (shard_total * 100) - 1) / (shard_total * 100) ))
  [[ "$shard_n" -ge "$lo" && "$shard_n" -le "$hi" ]] \
    || fail "(shard) shard $shard_idx launched $shard_n classes; expected ~$((class_count / shard_total)) (band [$lo,$hi] — #1862 hash partition)"
  for sc in "${shard_classes[@]}"; do
    [[ -z "${seen_class_shard[$sc]:-}" ]] \
      || fail "(shard) class $sc ran on BOTH shard ${seen_class_shard[$sc]} and shard $shard_idx — partition not disjoint"
    seen_class_shard["$sc"]="$shard_idx"
    shard_union_count=$((shard_union_count + 1))
  done
  grep -q 'CORE-TERMINAL #796 OUTPUT-BURST-IME PROOF' "$shard_log" \
    || fail "(shard) shard $shard_idx did not run the core-terminal proofs (they must run on EVERY leg, not be sharded)"
done
[[ "$shard_union_count" -eq "$class_count" ]] \
  || fail "(shard) union of all shards = $shard_union_count classes, expected the full $class_count (a class ran on no shard or twice)"
pass "(shard) hash partition: 3 shards each ~1/3, disjoint, union = all $class_count classes; proofs on every shard"

# ---------------------------------------------------------------------------
# (m) ACCEPTANCE — Issue #835 (REOPENED): the six core-terminal proofs are now
# wrapped in the SAME budget-capped `timeout` as the journey classes
# (run_ct_class). Before, they ran UNBOUNDED — the #796 proof HUNG in run
# 28307686762 and ran until the JOB cap SIGKILLed the step, producing a
# "cancelled" with NO trustworthy summary.md (the exact reopen symptom). Model a
# proof that HANGS (the core-terminal task sleeps far longer than the per-class
# cap). With the bound, the suite must SELF-FINISH (write summary, exit red);
# WITHOUT the bound the hung proof would run to completion and the suite would
# never surface it as red — exactly the cancel we are eliminating.
#
# Issue #1802 G2 sweep: this was the ONE sibling of the old (o2) that carried the
# same bare wall-clock shape — the load-bearing assertion was `rc_ct != 124`,
# i.e. "the outer 150s guard did not fire", which measures this host's
# process-spawn throughput across ~89 instant-pass stub invocations rather than
# whether the proofs are bounded. The proof stub is now SELF-WITNESSING in the
# same way as the (o2) wedge: it records on the filesystem if it SURVIVES
# JOURNEY_CT_SURVIVAL_SECS (15s — 7.5x the injected 2s per-class cap, and reached
# only via `sleep`, a kernel timer). A bounded proof can never reach it; an
# unbounded one always does. The outer `timeout` is demoted to a pure runaway
# guard so this test cannot hang forever, and is sized so it cannot fire on
# correct behaviour.
echo "== Core-terminal proofs are bounded: a hung proof cannot hang the suite =="
ct_survival_secs=15
ct_witness_dir="$SANDBOX/ct-witness"
rm -rf "$ct_witness_dir"
cat > "$SANDBOX/gradlew" <<'STUB'
#!/usr/bin/env bash
set -u
if [[ "${1:-}" == "--stop" ]]; then exit 0; fi
if [[ "$*" == *":shared:core-terminal:connectedDebugAndroidTest"* ]]; then
  # Hung proof, SELF-WITNESSING (issue #1802): creates a survival witness FILE
  # only if it is still alive after JOURNEY_CT_SURVIVAL_SECS, which is far past
  # the injected per-class cap. Sleeps in 1s slices so the cap's TERM is honoured
  # within a second rather than deferred behind one long sleep.
  witness_dir="${JOURNEY_CT_WITNESS_DIR:?}"
  mkdir -p "$witness_dir"
  trap 'exit 143' TERM
  for _ in $(seq "${JOURNEY_CT_SURVIVAL_SECS:?}"); do sleep 1; done
  : > "$witness_dir/ct-survived-$$"
  exit 0
fi
exit 0
STUB
chmod +x "$SANDBOX/gradlew"

out_ct="$SANDBOX/run-ct-bound.log"
summary_ct="$SANDBOX/artifacts/ci-journey/summary.md"
# Force summary.md to be produced by THIS run, so a stale summary from an earlier
# fixture in this sandbox can never stand in for one this run failed to write.
rm -f "$summary_ct"
set +e
# Runaway guard ONLY — deliberately not a bound the assertions rely on. Correct
# behaviour finishes in well under a minute; a genuinely unbounded suite would
# run ~6 proofs x 2 attempts x the wedge, so this can only fire on a real hang.
timeout --signal=TERM --kill-after=10 600s \
  env PATH="$STUBBIN:$PATH" \
    JOURNEY_STEP_BUDGET_SECS=3600 \
    JOURNEY_CLASS_TIMEOUT_SECS=2 \
    JOURNEY_CLASS_KILL_AFTER_SECS=1 \
    JOURNEY_GRADLE_STOP_TIMEOUT_SECS=5 \
    JOURNEY_CT_WITNESS_DIR="$ct_witness_dir" \
    JOURNEY_CT_SURVIVAL_SECS="$ct_survival_secs" \
    bash "$SANDBOX/scripts/ci-journey-suite.sh" > "$out_ct" 2>&1
rc_ct=$?
set -e

[[ "$rc_ct" -ne 124 ]] \
  || { sed -n '1,60p' "$out_ct"; fail "(m) the runaway guard had to KILL the suite — it never self-finished (a real hang, not a slow box)"; }
shopt -s nullglob
ct_survivors=("$ct_witness_dir"/ct-survived-*)
shopt -u nullglob
[[ "${#ct_survivors[@]}" -eq 0 ]] \
  || { sed -n '1,60p' "$out_ct"; fail "(m) ${#ct_survivors[@]} core-terminal proof invocation(s) SURVIVED ${ct_survival_secs}s under a 2s per-class cap — the proofs are NOT timeout-bounded (the #835 unbounded-proof regression that caused the 95-min cancel)"; }
[[ -f "$summary_ct" ]] \
  || fail "(m) a bounded hung proof must still write summary.md (the artifact the classifier needs — no silent cancel)"
grep -qE 'output-burst-IME ANR proof.*\*\*FAIL\*\*' "$summary_ct" \
  || { cat "$summary_ct"; fail "(m) the hung #796 proof must surface as FAIL in the summary (classifiable red, not a cancel)"; }
pass "(m) core-terminal proofs are timeout-bounded — no proof invocation survived ${ct_survival_secs}s under the 2s cap, and the hung proof yields a classifiable summary"

# (m-live) LIVENESS of the (m) survival assertion: with the per-class cap widened
# past the milestone the SAME proof stub DOES reach it, so "no ct-survived
# witness" above cannot pass vacuously. Drives the real run_ct_class bound
# (run_bounded) once, directly. The stub exits as soon as it writes the witness
# and the bound is a deliberately loose 120s, so this probe is bounded by the
# child's own 15s of `sleep` with 8x headroom — no host-throughput term.
ct_live_witness_dir="$SANDBOX/ct-liveness-witness"
rm -rf "$ct_live_witness_dir"
(
  set +e
  JOURNEY_NO_OUTPUT_TIMEOUT_SECS=120
  JOURNEY_CLASS_KILL_AFTER_SECS=1
  JOURNEY_CT_WITNESS_DIR="$ct_live_witness_dir"
  JOURNEY_CT_SURVIVAL_SECS="$ct_survival_secs"
  export JOURNEY_CT_WITNESS_DIR JOURNEY_CT_SURVIVAL_SECS
  source "$SCRIPT_DIR/ci-journey-budget-functions.sh"
  run_bounded 120 "$SANDBOX/gradlew" :shared:core-terminal:connectedDebugAndroidTest
) > /dev/null 2>&1 || true
shopt -s nullglob
ct_live_survivors=("$ct_live_witness_dir"/ct-survived-*)
shopt -u nullglob
[[ "${#ct_live_survivors[@]}" -eq 1 ]] \
  || fail "(m-live) the core-terminal proof stub never reached its ${ct_survival_secs}s survival milestone even with a 30s bound — (m)'s no-survivor assertion is unreachable and would pass vacuously"
pass "(m-live) the ${ct_survival_secs}s survival witness IS reachable under a loose bound — (m)'s no-survivor assertion is live, not vacuous"

echo
echo "ALL TESTS PASSED"
