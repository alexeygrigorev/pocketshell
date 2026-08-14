#!/usr/bin/env bash
# test-ci-journey-fixture-health.sh — guard for the issue #2143 shared SSH/tmux
# fixture health gate.
#
# WHAT IT PROVES, and why each case exists.
#
# The defect: `ReconnectStormLivelockE2eTest` SIGSTOPs the shared `agents` tmux
# server as its fixture and restores it only from paths that run IN THE TEST
# PROCESS. The journey harness kills a class attempt at its 420s cap, so on
# `main` @ 23ed1b10 the server stayed frozen and every later class that touched
# it timed out in its own SETUP — three storm-test setup timeouts at the full
# 90s deadline plus two ProfileChipRelaunchDockerTest 30s timeouts — while the
# class targeting the SEPARATE `agents-daemon` container passed. The shard was
# typed "a genuine test failure (app-code regression)" on a commit whose entire
# diff was three version strings, and the release could only proceed by re-running.
#
# Cases:
#   (1) probe CLASSIFICATION — healthy, idle-no-server, wedged, unreachable are
#       four different states and must not collapse. An idle fixture with no tmux
#       server is HEALTHY (the next new-session starts one); a probe that cannot
#       reach the fixture at all is NOT healthy (the "I could not check must not
#       read like I checked" rule).
#   (2) REPAIR, both rungs — SIGCONT restores; if it does not, the escalation to
#       killing the tmux server does; if neither does, the status stays `wedged`
#       and is never laundered into ok.
#   (3) the TSV timing series exists with a numeric probe_ms per gate call.
#   (4) END-TO-END through the REAL suite: a class that fails while the fixture is
#       wedged is classified `JOURNEY_FIXTURE_SETUP_FAILURE`, appears in the
#       summary's own section, and — the part that actually fixes the cascade —
#       its RETRY runs against a REPAIRED fixture instead of the frozen one.
#   (5) SELECTIVITY: an ordinary assertion failure on a HEALTHY fixture must NOT
#       be classified as a setup failure. Without this, (4) would pass for a gate
#       that simply labels every failure a fixture problem.
#   (6) the workflow's verdict attribution maps that summary to a DISTINCT reason
#       while STILL writing RED — the fix must not mask anything.
#   (7) MUTATION: neuter the gate in a private sandbox copy and require (4) to go
#       RED. A green assertion that would stay green with the bug present is the
#       G6 wrong-cost trap; this is the mutation that must redden it.
#   (8) REAL DOCKER (opt-in, POCKETSHELL_FIXTURE_HEALTH_REAL_DOCKER=1): the
#       mechanism and the repair on a live `agents` container over real SSH.
#
# JVM-free, Docker-free and emulator-free by default (ssh/docker/gradle stubbed).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
HEALTH="$SCRIPT_DIR/ci-journey-fixture-health.sh"
WORKFLOW="$REPO_ROOT/.github/workflows/tests.yml"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

[[ -f "$HEALTH" ]] || fail "cannot find ci-journey-fixture-health.sh at $HEALTH"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

# ---------------------------------------------------------------------------
# Stub transports. State lives in files so a stub can change behaviour between
# calls — that is what lets a repair be OBSERVED rather than assumed.
# ---------------------------------------------------------------------------
STATE="$SANDBOX/state"
mkdir -p "$STATE"
printf 'healthy\n' > "$STATE/fixture-mode"

cat > "$SANDBOX/ssh-stub" <<'STUB'
#!/usr/bin/env bash
# Models the fixture's response to `tmux list-sessions` over SSH.
mode="$(cat "$FIXTURE_STATE/fixture-mode" 2>/dev/null || echo healthy)"
printf '%s\n' "$mode" >> "$FIXTURE_STATE/probe.log"
case "$mode" in
  healthy)     printf 'seed: 1 windows (created now)\n'; exit 0 ;;
  no_server)   printf 'no server running on /tmp/tmux-1000/default\n' >&2; exit 1 ;;
  wedged)      sleep 3600; exit 0 ;;
  unreachable) printf 'ssh: connect to host 127.0.0.1 port 2222: Connection refused\n' >&2; exit 255 ;;
esac
exit 0
STUB
chmod +x "$SANDBOX/ssh-stub"

cat > "$SANDBOX/docker-stub" <<'STUB'
#!/usr/bin/env bash
# Records what the repair actually did and applies the configured recovery.
printf '%s\n' "$*" >> "$FIXTURE_STATE/docker.log"
if [[ "$*" == *"kill -CONT"* ]]; then
  printf 'sigcont\n' >> "$FIXTURE_STATE/repair.log"
  [[ "$(cat "$FIXTURE_STATE/sigcont-restores" 2>/dev/null || echo 1)" == "1" ]] \
    && printf 'healthy\n' > "$FIXTURE_STATE/fixture-mode"
  exit 0
fi
if [[ "$*" == *"pkill -9 -f tmux"* ]]; then
  printf 'tmux-kill\n' >> "$FIXTURE_STATE/repair.log"
  [[ "$(cat "$FIXTURE_STATE/kill-restores" 2>/dev/null || echo 1)" == "1" ]] \
    && printf 'no_server\n' > "$FIXTURE_STATE/fixture-mode"
  exit 0
fi
# `ps -eo pid,state` enumeration used to REPORT what was stopped.
if [[ "$*" == *"ps -eo pid,state"* ]]; then
  printf '4242\n'
  exit 0
fi
exit 0
STUB
chmod +x "$SANDBOX/docker-stub"

export FIXTURE_STATE="$STATE"
export JOURNEY_FIXTURE_HEALTH_SSH="$SANDBOX/ssh-stub"
export JOURNEY_FIXTURE_HEALTH_DOCKER="$SANDBOX/docker-stub"
export JOURNEY_FIXTURE_HEALTH_CONTAINER="stub-agents"
export JOURNEY_FIXTURE_HEALTH_KEY="$SANDBOX/stub-key"
export JOURNEY_FIXTURE_HEALTH_PROBE_SECS=3
export JOURNEY_FIXTURE_HEALTH_REPAIR_SECS=3
printf 'stub-key\n' > "$SANDBOX/stub-key"

set_mode() { printf '%s\n' "$1" > "$STATE/fixture-mode"; }
reset_logs() { : > "$STATE/probe.log"; : > "$STATE/docker.log"; : > "$STATE/repair.log"; }

# ---------------------------------------------------------------------------
# (1) probe classification
# ---------------------------------------------------------------------------
echo "== (1) probe classification =="
# shellcheck source=scripts/ci-journey-fixture-health.sh
source "$HEALTH"

set_mode healthy
journey_fixture_probe; rc=$?
[[ $rc -eq 0 ]] || fail "(1) a fixture that lists sessions must probe healthy, got rc=$rc"
[[ "$JOURNEY_FIXTURE_HEALTH_PROBE_MS" =~ ^[0-9]+$ ]] \
  || fail "(1) probe must record a numeric elapsed time, got '$JOURNEY_FIXTURE_HEALTH_PROBE_MS'"
pass "(1a) sessions listed -> healthy, with a measured probe_ms"

set_mode no_server
journey_fixture_probe; rc=$?
[[ $rc -eq 0 ]] || fail "(1) an IDLE fixture ('no server running') must probe healthy, got rc=$rc — the next new-session starts a server"
pass "(1b) idle 'no server running' -> healthy (not mistaken for a wedge)"

set_mode wedged
journey_fixture_probe; rc=$?
[[ $rc -eq 1 ]] || fail "(1) a non-answering fixture must probe WEDGED (rc=1), got rc=$rc"
[[ "$JOURNEY_FIXTURE_HEALTH_DETAIL" == *"probe_timeout_after_${JOURNEY_FIXTURE_HEALTH_PROBE_SECS}s"* ]] \
  || fail "(1) a wedged probe must name the timeout in its detail, got '$JOURNEY_FIXTURE_HEALTH_DETAIL'"
pass "(1c) non-answering fixture -> wedged, with the timeout named"

set_mode unreachable
journey_fixture_probe; rc=$?
[[ $rc -eq 2 ]] || fail "(1) an UNREACHABLE fixture must be its own state (rc=2), got rc=$rc — 'I could not check' must never read like 'I checked and it is fine'"
pass "(1d) unreachable fixture -> unavailable (never laundered into healthy)"

# ---------------------------------------------------------------------------
# (2) repair, both rungs
# ---------------------------------------------------------------------------
echo "== (2) repair =="
reset_logs
set_mode wedged
printf '1\n' > "$STATE/sigcont-restores"
journey_fixture_health_gate post_attempt RungOne >/dev/null 2>&1
[[ "$JOURNEY_FIXTURE_HEALTH_STATUS" == "wedged_repaired" ]] \
  || fail "(2) SIGCONT must restore a SIGSTOPped fixture, got status=$JOURNEY_FIXTURE_HEALTH_STATUS"
grep -q '^sigcont$' "$STATE/repair.log" \
  || fail "(2) the repair must actually have SENT SIGCONT — repair.log: $(cat "$STATE/repair.log")"
grep -q 'kill -CONT' "$STATE/docker.log" \
  || fail "(2) the repair's docker invocation must carry kill -CONT"
pass "(2a) SIGCONT rung restores a stopped server, and the signal was really sent"

reset_logs
set_mode wedged
printf '0\n' > "$STATE/sigcont-restores"
printf '1\n' > "$STATE/kill-restores"
journey_fixture_health_gate post_attempt RungTwo >/dev/null 2>&1
[[ "$JOURNEY_FIXTURE_HEALTH_STATUS" == "wedged_repaired" ]] \
  || fail "(2) escalation to killing the tmux server must restore the fixture, got status=$JOURNEY_FIXTURE_HEALTH_STATUS"
grep -q '^tmux-kill$' "$STATE/repair.log" \
  || fail "(2) the escalation rung must have run — repair.log: $(cat "$STATE/repair.log")"
pass "(2b) escalation rung restores a server SIGCONT could not"

reset_logs
set_mode wedged
printf '0\n' > "$STATE/sigcont-restores"
printf '0\n' > "$STATE/kill-restores"
journey_fixture_health_gate post_attempt Unfixable >/dev/null 2>&1
[[ "$JOURNEY_FIXTURE_HEALTH_STATUS" == "wedged" ]] \
  || fail "(2) an unrepairable fixture must stay 'wedged', got status=$JOURNEY_FIXTURE_HEALTH_STATUS"
pass "(2c) an unrepairable fixture is reported wedged, never ok"
printf '1\n' > "$STATE/sigcont-restores"
printf '1\n' > "$STATE/kill-restores"

# ---------------------------------------------------------------------------
# (3) the timing series
# ---------------------------------------------------------------------------
echo "== (3) bring-up timing artifact =="
tsv="$SANDBOX/fixture-health.tsv"
JOURNEY_FIXTURE_HEALTH_TSV="$tsv"
set_mode healthy
journey_fixture_health_gate preflight suite_start >/dev/null 2>&1
journey_fixture_health_gate post_attempt SomeClass >/dev/null 2>&1
[[ -s "$tsv" ]] || fail "(3) no fixture-health.tsv was written"
head -1 "$tsv" | grep -q $'^timestamp_utc\tphase\tlabel\tfixture\tstatus\tprobe_ms\tdetail$' \
  || fail "(3) fixture-health.tsv header is wrong: $(head -1 "$tsv")"
rows="$(($(wc -l < "$tsv") - 1))"
[[ "$rows" -eq 2 ]] || fail "(3) expected one row per gate call, got $rows"
awk -F'\t' 'NR>1 && $6 !~ /^[0-9]+$/ { print; exit 1 }' "$tsv" \
  || fail "(3) every recorded row must carry a numeric probe_ms"
grep -qP '\tpreflight\tsuite_start\t' "$tsv" \
  || fail "(3) the baseline preflight row is missing — without a known-good first row a later slow probe has nothing to be compared against"
pass "(3) per-attempt bring-up timings are recorded with a preflight baseline"

# ---------------------------------------------------------------------------
# Sandbox repo for the end-to-end suite cases. `cp -a` (never symlinks — a
# symlinked mutation root writes through into the tree under review, #2054).
# ---------------------------------------------------------------------------
# build_suite_sandbox <root> <failing-class> <wedges-the-fixture: 1|0>
#
# The wedging stub models REALITY: the class does not merely fail, it leaves the
# SHARED fixture frozen behind it, exactly as the killed storm test left the tmux
# server SIGSTOPped. Flipping the fixture to `wedged` before the suite starts
# would instead be repaired by the preflight probe and never reach a class.
build_suite_sandbox() {
  local root="$1" failing_class="$2" wedges="${3:-0}"
  rm -rf "$root"
  mkdir -p "$root"
  cp -a "$REPO_ROOT/scripts" "$root/scripts"
  cat > "$root/gradlew" <<STUB
#!/usr/bin/env bash
if [[ "\${1:-}" == "--stop" ]]; then exit 0; fi
for arg in "\$@"; do
  case "\$arg" in
    *class=*$failing_class*)
      if [[ "$wedges" == "1" && ! -f "\$FIXTURE_STATE/already-wedged" ]]; then
        : > "\$FIXTURE_STATE/already-wedged"
        printf 'wedged\n' > "\$FIXTURE_STATE/fixture-mode"
      fi
      exit 1
      ;;
  esac
done
exit 0
STUB
  chmod +x "$root/gradlew"
  cat > "$root/scripts/connected-test.sh" <<'STUB'
#!/usr/bin/env bash
root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "$root_dir/gradlew" :app:connectedDebugAndroidTest "$@"
STUB
  chmod +x "$root/scripts/connected-test.sh"
  # Narrow the class list to two entries so the run is fast and deterministic.
  python3 - "$root/scripts/ci-journey-suite.sh" <<'PY'
import sys
p = sys.argv[1]
lines = open(p).read().split('\n')
start = next(i for i, l in enumerate(lines) if l.startswith('JOURNEY_CLASSES=('))
end = next(i for i in range(start + 1, len(lines)) if lines[i].rstrip() == ')')
lines[start:end + 1] = [
    'JOURNEY_CLASSES=(',
    '  "com.pocketshell.app.proof.SandboxWedgingClass"',
    '  "com.pocketshell.app.proof.SandboxHealthyClass"',
    ')',
]
# The core-terminal proofs are a separate cluster and irrelevant here.
lines = [':' if l.strip() == 'run_core_terminal_proofs' else l for l in lines]
open(p, 'w').write('\n'.join(lines))
PY
  # The harness refuses to continue when attempt-artifact capture comes back
  # empty (#1840 isolation guard), so the adb stub must produce non-empty
  # logcat / ps / dumpsys / screenshot exactly like the #835 guard's stub.
  mkdir -p "$root/stubbin"
  cat > "$root/stubbin/adb" <<'STUB'
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
  logcat)   [[ "${2:-}" == "-c" ]] || printf 'stub-device-logcat\n' ;;
  exec-out) emit_valid_png ;;
  shell)
    case "${2:-}" in
      ps)      printf 'PID NAME\n' ;;
      dumpsys) printf 'stub dumpsys %s\n' "$*" ;;
    esac
    ;;
esac
exit 0
STUB
  chmod +x "$root/stubbin/adb"
}

run_suite_sandbox() {
  local root="$1" out="$2"
  ( cd "$root" && PATH="$root/stubbin:$PATH" \
      JOURNEY_STEP_BUDGET_SECS=600 \
      JOURNEY_CLASS_TIMEOUT_SECS=30 \
      JOURNEY_NO_OUTPUT_TIMEOUT_SECS=25 \
      JOURNEY_FIXTURE_HEALTH_TSV="$root/artifacts/ci-journey/fixture-health.tsv" \
      bash "$root/scripts/ci-journey-suite.sh" ) > "$out" 2>&1
  return $?
}

# ---------------------------------------------------------------------------
# (4) end-to-end: a failure on a WEDGED fixture is classified as SETUP, and the
#     retry runs against a REPAIRED fixture.
# ---------------------------------------------------------------------------
echo "== (4) end-to-end classification + retry on a repaired fixture =="
WEDGE_ROOT="$SANDBOX/wedge"
build_suite_sandbox "$WEDGE_ROOT" SandboxWedgingClass 1
reset_logs
set_mode healthy
rm -f "$STATE/already-wedged"
printf '1\n' > "$STATE/sigcont-restores"
out4="$SANDBOX/wedge.log"
run_suite_sandbox "$WEDGE_ROOT" "$out4"; rc4=$?

[[ $rc4 -ne 0 ]] || { sed -n '1,40p' "$out4"; fail "(4) the suite must stay RED — the gate must never turn a failure green"; }
grep -q 'JOURNEY_FIXTURE_WEDGED' "$out4" \
  || { sed -n '1,60p' "$out4"; fail "(4) the wedged fixture was not detected"; }
grep -q 'JOURNEY_FIXTURE_SETUP_FAILURE: com.pocketshell.app.proof.SandboxWedgingClass' "$out4" \
  || { sed -n '1,60p' "$out4"; fail "(4) the failing class was not classified as a fixture SETUP failure"; }
summary4="$WEDGE_ROOT/artifacts/ci-journey/summary.md"
[[ -f "$summary4" ]] || fail "(4) no summary.md"
grep -q 'Shared SSH/tmux fixture was WEDGED during these classes' "$summary4" \
  || { cat "$summary4"; fail "(4) summary.md has no fixture-setup-failure section"; }
grep -q 'Failed BOTH attempts' "$summary4" \
  || { cat "$summary4"; fail "(4) the class must STILL be listed as failed — the classification is additive, not a downgrade"; }
# The load-bearing half: the RETRY must have run against a repaired fixture.
tsv4="$WEDGE_ROOT/artifacts/ci-journey/fixture-health.tsv"
[[ -s "$tsv4" ]] || fail "(4) no fixture-health.tsv from the suite run"
statuses4="$(awk -F'\t' 'NR>1 {print $5}' "$tsv4" | tr '\n' ',')"
[[ "$statuses4" == *"wedged_repaired,"* ]] \
  || fail "(4) expected a wedged_repaired row; got: $statuses4"
[[ "$statuses4" == *"wedged_repaired,ok"* ]] \
  || fail "(4) the attempt AFTER the repair must probe ok — that is what makes the existing retry meaningful instead of a second run against a frozen fixture. Statuses: $statuses4"
pass "(4) wedged fixture detected, repaired, classified as SETUP, still red, retry ran healthy"

# ---------------------------------------------------------------------------
# (5) SELECTIVITY — an ordinary failure on a HEALTHY fixture is NOT a setup failure
# ---------------------------------------------------------------------------
echo "== (5) selectivity on a healthy fixture =="
HEALTHY_ROOT="$SANDBOX/healthy"
build_suite_sandbox "$HEALTHY_ROOT" SandboxWedgingClass 0
reset_logs
set_mode healthy
rm -f "$STATE/already-wedged"
out5="$SANDBOX/healthy.log"
run_suite_sandbox "$HEALTHY_ROOT" "$out5"; rc5=$?
[[ $rc5 -ne 0 ]] || fail "(5) the failing class must still redden the suite"
if grep -q 'JOURNEY_FIXTURE_SETUP_FAILURE' "$out5"; then
  sed -n '1,60p' "$out5"
  fail "(5) an ordinary assertion failure on a HEALTHY fixture was mislabelled a fixture setup failure — the classification would be decorative"
fi
summary5="$HEALTHY_ROOT/artifacts/ci-journey/summary.md"
if grep -q 'Shared SSH/tmux fixture was WEDGED' "$summary5"; then
  cat "$summary5"
  fail "(5) the summary claimed a wedged fixture on a healthy run"
fi
grep -q 'Failed BOTH attempts' "$summary5" \
  || { cat "$summary5"; fail "(5) the genuine failure must still be reported"; }
pass "(5) a genuine failure on a healthy fixture is NOT reclassified"

# ---------------------------------------------------------------------------
# (6) the workflow's verdict attribution
# ---------------------------------------------------------------------------
echo "== (6) workflow verdict attribution =="
verdict_fn="$(awk '/^ *verdict_reason_for\(\) \{/{f=1} f{print} f && /^ *\}$/{exit}' "$WORKFLOW")"
[[ -n "$verdict_fn" ]] || fail "(6) could not extract verdict_reason_for from tests.yml"
probe_reason() {
  local summary_file="$1"
  bash -c "
    summary='$summary_file'
    build_fail_attempts=0
    build_phase_attempts=0
    $verdict_fn
    verdict_reason_for first_attempt_journey_failure
  "
}
[[ "$(probe_reason "$summary4")" == "shared_fixture_setup_failure" ]] \
  || fail "(6) a summary carrying JOURNEY_FIXTURE_SETUP_FAILURE must be attributed distinctly, got '$(probe_reason "$summary4")'"
[[ "$(probe_reason "$summary5")" == "first_attempt_journey_failure" ]] \
  || fail "(6) an ordinary failure must keep its ordinary reason, got '$(probe_reason "$summary5")'"
grep -q 'write_verdict RED "\$(verdict_reason_for first_attempt_journey_failure)"' "$WORKFLOW" \
  || fail "(6) the fixture-setup attribution must still write a RED verdict — it renames the reason, it does not downgrade the verdict"
pass "(6) verdict reason is distinct for a fixture setup failure, and the verdict stays RED"

# ---------------------------------------------------------------------------
# (7) MUTATION — the gate must be load-bearing
# ---------------------------------------------------------------------------
echo "== (7) mutation: neuter the gate and require (4) to redden =="
MUTANT_ROOT="$SANDBOX/mutant"
build_suite_sandbox "$MUTANT_ROOT" SandboxWedgingClass 1
# The mutation: the gate reports `ok` unconditionally and never repairs — i.e.
# exactly the pre-#2143 world, where a wedged fixture was invisible.
python3 - "$MUTANT_ROOT/scripts/ci-journey-fixture-health.sh" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
needle = 'journey_fixture_health_gate() {'
assert needle in s, "mutation anchor missing — the mutant would be a no-op"
s = s.replace(needle, needle + '\n  JOURNEY_FIXTURE_HEALTH_STATUS=ok; JOURNEY_FIXTURE_HEALTH_PROBE_MS=0; return 0', 1)
open(p, 'w').write(s)
PY
grep -q 'JOURNEY_FIXTURE_HEALTH_STATUS=ok; JOURNEY_FIXTURE_HEALTH_PROBE_MS=0; return 0' \
  "$MUTANT_ROOT/scripts/ci-journey-fixture-health.sh" \
  || fail "(7) the mutant was not applied — a mutation that never happened is not a passing mutation test"
reset_logs
set_mode healthy
rm -f "$STATE/already-wedged"
out7="$SANDBOX/mutant.log"
run_suite_sandbox "$MUTANT_ROOT" "$out7" || true
if grep -q 'JOURNEY_FIXTURE_SETUP_FAILURE' "$out7"; then
  fail "(7) the neutered gate STILL reported a fixture setup failure — case (4) does not depend on the gate and is decorative"
fi
grep -q 'Failed BOTH attempts' "$MUTANT_ROOT/artifacts/ci-journey/summary.md" \
  || fail "(7) the mutant run should still have failed the class (the mutation must redden only the new check)"
pass "(7) mutation confirmed: without the gate the setup failure is invisible, and only that check reddens"

# ---------------------------------------------------------------------------
# (8) REAL DOCKER (opt-in)
# ---------------------------------------------------------------------------
if [[ "${POCKETSHELL_FIXTURE_HEALTH_REAL_DOCKER:-0}" == "1" ]]; then
  echo "== (8) real Docker fixture: wedge -> detect -> repair =="
  real_container="${POCKETSHELL_FIXTURE_HEALTH_REAL_CONTAINER:-pocketshell-test-agents}"
  real_port="${POCKETSHELL_FIXTURE_HEALTH_REAL_PORT:-2222}"
  # Own the bring-up here rather than in the workflow, so the case is runnable
  # verbatim on any box with Docker and the workflow step stays one line.
  chmod 600 "$REPO_ROOT/tests/docker/test_key" 2>/dev/null || true
  if ! docker inspect -f '{{.State.Running}}' "$real_container" 2>/dev/null | grep -q true; then
    ( cd "$REPO_ROOT" \
      && docker compose -f tests/docker/docker-compose.yml up -d --build --no-deps agents \
      && source tests/docker/lib/wait-for-healthy.sh \
      && wait_for_container_healthy tests/docker/docker-compose.yml agents \
           /tmp/agents-fixture-health.log 60 ) \
      || fail "(8) could not bring up the real agents fixture"
  fi
  (
    unset JOURNEY_FIXTURE_HEALTH_SSH JOURNEY_FIXTURE_HEALTH_DOCKER
    export JOURNEY_FIXTURE_HEALTH_CONTAINER="$real_container"
    export JOURNEY_FIXTURE_HEALTH_PORT="$real_port"
    export JOURNEY_FIXTURE_HEALTH_KEY="$REPO_ROOT/tests/docker/test_key"
    export JOURNEY_FIXTURE_HEALTH_PROBE_SECS=20
    export JOURNEY_FIXTURE_HEALTH_TSV="$SANDBOX/real.tsv"
    unset JOURNEY_FIXTURE_HEALTH_STATUS
    # shellcheck source=scripts/ci-journey-fixture-health.sh
    source "$HEALTH"
    key="$(journey_fixture_health_private_key)"
    ssh_fixture() {
      ssh -i "$key" -p "$real_port" -o BatchMode=yes -o StrictHostKeyChecking=no \
        -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR testuser@127.0.0.1 "$@"
    }
    ssh_fixture "tmux kill-session -t fh-probe 2>/dev/null; tmux new-session -d -s fh-probe 'sleep 600'" \
      || { echo "TEST FAIL: (8) could not seed the real fixture" >&2; exit 1; }
    journey_fixture_health_gate preflight real >/dev/null
    [[ "$JOURNEY_FIXTURE_HEALTH_STATUS" == "ok" ]] \
      || { echo "TEST FAIL: (8) the real fixture did not start healthy ($JOURNEY_FIXTURE_HEALTH_STATUS)" >&2; exit 1; }
    pid="$(ssh_fixture "tmux display-message -p -t fh-probe '#{pid}'")"
    ssh_fixture "kill -STOP $pid"
    journey_fixture_health_gate post_attempt real >/dev/null
    [[ "$JOURNEY_FIXTURE_HEALTH_STATUS" == "wedged_repaired" ]] \
      || { echo "TEST FAIL: (8) a really SIGSTOPped tmux server was not detected+repaired ($JOURNEY_FIXTURE_HEALTH_STATUS)" >&2; exit 1; }
    ssh_fixture "tmux kill-session -t fh-probe 2>/dev/null" || true
    echo "  ok: (8) real fixture wedged by SIGSTOP was detected and repaired"
  ) || fail "(8) real-Docker case failed"
else
  echo "  skip: (8) real-Docker case (set POCKETSHELL_FIXTURE_HEALTH_REAL_DOCKER=1 to run it)"
fi

echo "ALL FIXTURE-HEALTH GUARD CASES PASSED"
