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
#   (9) issue #2145 — two readings of "the fixture did not list sessions":
#       no server created yet (`error connecting` / No such file) is NOT a
#       wedge; a real wedge still is. `unavailable` is benign before any
#       class in this shard has created a server, and suspicious after.
#       G6: restoring the old unavailable=wedge mapping must redden (9a).
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
  no_socket)   printf 'error connecting to /tmp/tmux-1000/default (No such file or directory)\n' >&2; exit 1 ;;
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
# THE END-TO-END SUITE CASES (4)-(7), (9e), (9f) AND (9h) WERE HARD-CUT (D22).
#
# They drove the gate through the REAL per-class journey suite: a sandbox repo
# with a rewritten `JOURNEY_CLASSES=(...)` list, a stub gradle answering
# `-Pandroid.testInstrumentationRunnerArguments.class=<FQCN>`, per-class
# `JOURNEY_FIXTURE_SETUP_FAILURE` labelling, an `artifacts/ci-journey/summary.md`
# with a "Shared SSH/tmux fixture was WEDGED during these classes" section, and
# tests.yml's `verdict_reason_for()` mapping that section to
# `shared_fixture_setup_failure`.
#
# EVERY ONE of those is gone. `scripts/ci-journey-suite.sh` and the
# `emulator-journey` / `emulator-journey-verdict` jobs were deleted with the
# per-class registry (see the note at the bottom of .github/workflows/tests.yml),
# and their successor — `scripts/ci-app2-journey-suite.sh`, issue #2474 — is
# deliberately ONE unfiltered `:app2:connectedDebugAndroidTest` run with no class
# list, no per-class retry, no summary.md and no verdict classifier. There is no
# per-class boundary left for a fixture-health gate to sit on, so these cases had
# nothing to drive: the sandbox builder died on `JOURNEY_CLASSES=(` not existing.
#
# What survives is everything that exercises scripts/ci-journey-fixture-health.sh
# DIRECTLY — probe classification (1), both repair rungs (2), the timing TSV (3),
# the real-Docker case (8), and the whole #2145 no-server-vs-wedge reading
# (9a)-(9d) with its (9g) mutation. Those are the mechanism; the deleted cases
# were its wiring into a harness that no longer exists.
#
# KNOWN GAP, deliberately not papered over: with the per-class suite gone,
# `journey_fixture_health_gate` has no production caller at all. Re-wiring a
# fixture-health preflight into app2's single-run lane is a design call for that
# lane's owner (where does the boundary go when there is one attempt?), not a
# mechanical repoint, so it is NOT invented here.
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# (9) issue #2145 — no-server-yet is not a wedge; a real wedge still is
# ---------------------------------------------------------------------------
echo "== (9) no-server-yet vs wedge (issue #2145) =="
# Reset the shard-level "a server has been seen" bit so earlier cases that
# probed a healthy listing cannot leak into these readings.
JOURNEY_FIXTURE_HEALTH_SERVER_SEEN=0

# (9a) THE REPRODUCE-FIRST CHECK. On current main, was_wedged treats
# `unavailable` as wedge-class unconditionally, so a pre-server
# `error connecting / No such file` reading (the normal state before any
# class has created a tmux server) is classified as a wedge. That is the
# #2145 mislabel. This assertion is FALSE on the unfixed mapping.
JOURNEY_FIXTURE_HEALTH_STATUS="unavailable"
JOURNEY_FIXTURE_HEALTH_DETAIL="error connecting to /tmp/tmux-1000/default (No such file or directory)"
if journey_fixture_health_was_wedged; then
  fail "(9a) unavailable with no server created yet must NOT be wedge-class — that is the #2145 mislabel (a product failure would be attributed shared_fixture_setup_failure)"
fi
pass "(9a) unavailable before any server has been created is not a wedge"

# (9b) AC 2: the same status is suspicious AFTER a class in this shard
# has created a server — the fixture was answering and then became
# unreachable.
JOURNEY_FIXTURE_HEALTH_SERVER_SEEN=1
JOURNEY_FIXTURE_HEALTH_STATUS="unavailable"
journey_fixture_health_was_wedged \
  || fail "(9b) unavailable AFTER a server was created must stay wedge-class — a missed wedge is worse than a mislabel"
pass "(9b) unavailable after a server has been seen stays wedge-class"

# (9c) A real wedge is always wedge-class, even before any listing.
JOURNEY_FIXTURE_HEALTH_SERVER_SEEN=0
JOURNEY_FIXTURE_HEALTH_STATUS="wedged"
journey_fixture_health_was_wedged \
  || fail "(9c) status=wedged must stay wedge-class even before any server was listed"
JOURNEY_FIXTURE_HEALTH_STATUS="wedged_repaired"
journey_fixture_health_was_wedged \
  || fail "(9c) status=wedged_repaired must stay wedge-class even before any server was listed"
pass "(9c) a real wedge is always wedge-class"

# (9d) The observed CI phrasing — tmux answering immediately that the
# socket does not exist — is "no server created yet", not "SSH could not
# reach the fixture" and not a hang. The probe must not collapse it into
# unavailable (rc=2), and the gate must not report it as a wedge.
JOURNEY_FIXTURE_HEALTH_SERVER_SEEN=0
reset_logs
set_mode no_socket
journey_fixture_probe; rc=$?
[[ $rc -eq 0 ]] \
  || fail "(9d) 'error connecting ... (No such file or directory)' is idle-no-server and must probe healthy (rc=0), got rc=$rc — same reading as 'no server running'"
journey_fixture_health_gate preflight no_socket_preflight >/dev/null 2>&1
[[ "$JOURNEY_FIXTURE_HEALTH_STATUS" == "ok" ]] \
  || fail "(9d) idle-no-server must gate as ok, got status=$JOURNEY_FIXTURE_HEALTH_STATUS"
if journey_fixture_health_was_wedged; then
  fail "(9d) idle-no-server must not be classified as a wedge"
fi
[[ "${JOURNEY_FIXTURE_HEALTH_SERVER_SEEN:-0}" == "0" ]] \
  || fail "(9d) an idle-no-server probe must not mark the shard as having created a server"
pass "(9d) observed CI phrasing (error connecting / No such file) is idle-no-server, not unavailable, not a wedge"

# (9g) G6 MUTATION — restore the old unavailable=wedge mapping and
# require (9a) to go red. A green (9a) that would stay green with the
# bug present is the G6 wrong-cost trap.
echo "== (9g) mutation: restore unavailable=wedge and require (9a) to redden =="
MUTANT_MAP="$SANDBOX/mutant-unavailable-wedge.sh"
cp "$HEALTH" "$MUTANT_MAP"
python3 - "$MUTANT_MAP" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
old = '''journey_fixture_health_was_wedged() {
  [[ "$JOURNEY_FIXTURE_HEALTH_STATUS" == "wedged_repaired" \\
     || "$JOURNEY_FIXTURE_HEALTH_STATUS" == "wedged" \\
     || "$JOURNEY_FIXTURE_HEALTH_STATUS" == "unavailable" ]]
}'''
# Anchor on the function name so a rename fails closed rather than
# silently applying a no-op mutation.
needle = 'journey_fixture_health_was_wedged() {'
assert needle in s, "mutation anchor missing — the mutant would be a no-op"
# Replace from the function opener through its closing brace.
start = s.index(needle)
end = s.index('\n}', start) + 2
s = s[:start] + old + s[end:]
open(p, 'w').write(s)
PY
grep -q '|| "$JOURNEY_FIXTURE_HEALTH_STATUS" == "unavailable" ]]' "$MUTANT_MAP" \
  || fail "(9g) the old unavailable=wedge mapping was not restored — a mutation that never happened is not a passing mutation test"
# The mutant must redden ONLY the new check: (9a) becomes true-as-wedge,
# while a real wedge stays a wedge and a healthy status stays not-wedge.
(
  # shellcheck source=scripts/ci-journey-fixture-health.sh
  source "$MUTANT_MAP"
  JOURNEY_FIXTURE_HEALTH_SERVER_SEEN=0
  JOURNEY_FIXTURE_HEALTH_STATUS="unavailable"
  journey_fixture_health_was_wedged \
    || fail "(9g) restoring the old mapping did not classify unavailable-before-server as a wedge — (9a) is not load-bearing"
  JOURNEY_FIXTURE_HEALTH_STATUS="wedged"
  journey_fixture_health_was_wedged \
    || fail "(9g) the mutant must not break the real-wedge reading"
  JOURNEY_FIXTURE_HEALTH_STATUS="ok"
  if journey_fixture_health_was_wedged; then
    fail "(9g) the mutant must not start classifying a healthy fixture as a wedge"
  fi
)
pass "(9g) mutation confirmed: the old unavailable=wedge mapping reddens (9a) and only (9a)"

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
