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
    # #835 REOPENED: the first-attempt budget timeout is now a HARD RED.
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
  sleep 30
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
# Assert the historically cut-short classes (BackgroundGrace, LiveHold) and a
# share/composer/folder tail representative are present in the run AND that NO
# class is bucketed as a budget timeout (no "cut short / not run" section).
for cut_short_class in \
  com.pocketshell.app.proof.BackgroundGraceReconnectE2eTest \
  com.pocketshell.app.proof.SessionForegroundServiceLiveHoldJourneyE2eTest; do
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
# (shard) ACCEPTANCE — Issue #835 (REOPENED): CI-matrix sharding partitions
# JOURNEY_CLASSES round-robin so each leg runs a DISJOINT ~1/N slice and the
# UNION of all legs is the FULL set. This is the structural fix that lets the
# suite finish within the budget+cap (each leg ~1/N the wall-clock + a far
# healthier emulator). Drive the REAL suite once per shard (instant gradle stub,
# generous budget) and assert: (a) each shard launches ~class_count/N classes,
# (b) the slices are pairwise DISJOINT (no class on two shards), (c) the UNION is
# every class (none dropped), (d) the core-terminal proofs run on EVERY shard.
echo "== CI-matrix sharding: round-robin partition is disjoint + complete =="
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
  lo=$(( class_count / shard_total - 2 ))
  hi=$(( class_count / shard_total + 2 ))
  [[ "$shard_n" -ge "$lo" && "$shard_n" -le "$hi" ]] \
    || fail "(shard) shard $shard_idx launched $shard_n classes; expected ~$((class_count / shard_total)) (±2)"
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
pass "(shard) round-robin partition: 3 shards each ~1/3, disjoint, union = all $class_count classes; proofs on every shard"

# ---------------------------------------------------------------------------
# (m) ACCEPTANCE — Issue #835 (REOPENED): the six core-terminal proofs are now
# wrapped in the SAME budget-capped `timeout` as the journey classes
# (run_ct_class). Before, they ran UNBOUNDED — the #796 proof HUNG in run
# 28307686762 and ran until the JOB cap SIGKILLed the step, producing a
# "cancelled" with NO trustworthy summary.md (the exact reopen symptom). Model a
# proof that HANGS (the core-terminal task sleeps far longer than the per-class
# cap). With the bound, the suite must SELF-FINISH (write summary, exit red)
# inside an outer wall-clock guard; WITHOUT the bound the suite would hang and
# the outer `timeout` would have to KILL it (rc 124) — exactly the cancel we are
# eliminating.
echo "== Core-terminal proofs are bounded: a hung proof cannot hang the suite =="
cat > "$SANDBOX/gradlew" <<'STUB'
#!/usr/bin/env bash
if [[ "${1:-}" == "--stop" ]]; then exit 0; fi
if [[ "$*" == *":shared:core-terminal:connectedDebugAndroidTest"* ]]; then
  sleep 30
  exit 0
fi
exit 0
STUB
chmod +x "$SANDBOX/gradlew"

out_ct="$SANDBOX/run-ct-bound.log"
set +e
timeout --signal=TERM --kill-after=10 150s \
  env PATH="$STUBBIN:$PATH" \
    JOURNEY_STEP_BUDGET_SECS=3600 \
    JOURNEY_CLASS_TIMEOUT_SECS=2 \
    JOURNEY_CLASS_KILL_AFTER_SECS=1 \
    JOURNEY_GRADLE_STOP_TIMEOUT_SECS=5 \
    bash "$SANDBOX/scripts/ci-journey-suite.sh" > "$out_ct" 2>&1
rc_ct=$?
set -e

[[ "$rc_ct" -ne 124 ]] \
  || { sed -n '1,60p' "$out_ct"; fail "(m) the outer guard had to KILL the suite — a core-terminal proof was NOT bounded (the #835 unbounded-proof regression that caused the 95-min cancel)"; }
summary_ct="$SANDBOX/artifacts/ci-journey/summary.md"
[[ -f "$summary_ct" ]] \
  || fail "(m) a bounded hung proof must still write summary.md (the artifact the classifier needs — no silent cancel)"
grep -qE 'output-burst-IME ANR proof.*\*\*FAIL\*\*' "$summary_ct" \
  || { cat "$summary_ct"; fail "(m) the hung #796 proof must surface as FAIL in the summary (classifiable red, not a cancel)"; }
pass "(m) core-terminal proofs are timeout-bounded — a hung proof yields a classifiable summary, never a job-cap cancel"

echo
echo "ALL TESTS PASSED"
