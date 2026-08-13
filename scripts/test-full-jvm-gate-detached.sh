#!/usr/bin/env bash
# Issue #1956: a full-JVM gate takes longer than some agent callers are kept
# alive. Exercise the real detached entrypoint against fake systemd and a fake
# canonical gate so the lifecycle contract is proven without Gradle, Docker, an
# emulator, or the maintainer's tmux server.

set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
RUNNER_SOURCE="${RUNNER_UNDER_TEST:-$ROOT_DIR/scripts/full-jvm-gate-detached.py}"
MUTATION_PROBE="${MUTATION_PROBE:-0}"
CHECKS=0

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

pass_case() {
  CHECKS=$((CHECKS + 1))
  printf 'PASS: %s\n' "$*"
}

[[ -x "$RUNNER_SOURCE" ]] || fail "detached full-JVM entrypoint is missing or not executable: $RUNNER_SOURCE"

TMP_ROOT="$(mktemp -d)"
FIXTURE_ROOT="$TMP_ROOT/repo with spaces"
FAKE_BIN="$TMP_ROOT/fake-bin"
FAKE_SYSTEMD_DIR="$TMP_ROOT/fake-systemd"
STATE_DIR="$TMP_ROOT/state"
UNRELATED_PID=""

cleanup() {
  if [[ -n "$UNRELATED_PID" ]] && kill -0 "$UNRELATED_PID" 2>/dev/null; then
    kill -TERM -- "-$UNRELATED_PID" 2>/dev/null || true
  fi
  if [[ -d "$FAKE_SYSTEMD_DIR" ]]; then
    local pid_file pid
    while IFS= read -r pid_file; do
      [[ -f "$pid_file" ]] || continue
      pid="$(cat "$pid_file" 2>/dev/null || true)"
      [[ "$pid" =~ ^[0-9]+$ ]] || continue
      kill -TERM -- "-$pid" 2>/dev/null || true
    done < <(find "$FAKE_SYSTEMD_DIR" -maxdepth 1 -name '*.pid' -type f 2>/dev/null || true)
  fi
  rm -rf -- "$TMP_ROOT"
}
trap cleanup EXIT

mkdir -p "$FIXTURE_ROOT/scripts" "$FAKE_BIN" "$FAKE_SYSTEMD_DIR" "$STATE_DIR"
cp -- "$RUNNER_SOURCE" "$FIXTURE_ROOT/scripts/full-jvm-gate-detached.py"
chmod +x "$FIXTURE_ROOT/scripts/full-jvm-gate-detached.py"

cat > "$FIXTURE_ROOT/scripts/full-jvm-gate.py" <<'FAKE_GATE'
#!/usr/bin/env bash
set -euo pipefail
printf 'fake_gate_args='
printf '%q ' "$@"
printf '\n'
printf '%s\n' "$$" > "$FAKE_GATE_PID_FILE"
scope_pid_file=""
if [[ "${1:-}" == "--unit" && -n "${2:-}" ]]; then
  scope_pid_file="$FAKE_SYSTEMD_DIR/$2.scope.pid"
  printf '%s\n' "$$" > "$scope_pid_file"
  trap 'rm -f -- "$scope_pid_file"' EXIT
fi
mode="$(cat "$FAKE_GATE_MODE_FILE")"
case "$mode" in
  pass)
    sleep 1.2
    printf 'real_gradle_verdict=PASS\n'
    exit 0
    ;;
  fail)
    printf 'real_gradle_verdict=FAIL\n' >&2
    exit 7
    ;;
  long)
    trap 'printf "fake_gate_interrupted=yes\\n"; exit 143' TERM INT HUP
    sleep 60 &
    wait "$!"
    ;;
  *)
    printf 'unknown fake gate mode: %s\n' "$mode" >&2
    exit 9
    ;;
esac
FAKE_GATE
chmod +x "$FIXTURE_ROOT/scripts/full-jvm-gate.py"

cat > "$FAKE_BIN/systemd-run" <<'FAKE_SYSTEMD_RUN'
#!/usr/bin/python3
import os
import pathlib
import sys

state = pathlib.Path(os.environ["FAKE_SYSTEMD_DIR"])
args = sys.argv[1:]
unit = ""
for index, argument in enumerate(args):
    if argument.startswith("--unit="):
        unit = argument.split("=", 1)[1]
    elif argument == "--unit" and index + 1 < len(args):
        unit = args[index + 1]
if not unit or "--" not in args:
    sys.stderr.write("fake systemd-run: missing unit or command\n")
    raise SystemExit(2)
command = args[args.index("--") + 1 :]
with (state / "calls.log").open("a") as calls:
    calls.write("systemd-run " + " ".join(args) + "\n")

pid = os.fork()
if pid == 0:
    os.setsid()
    devnull = os.open(os.devnull, os.O_RDWR)
    os.dup2(devnull, 0)
    os.dup2(devnull, 1)
    os.dup2(devnull, 2)
    os.execvpe(command[0], command, os.environ.copy())

(state / f"{unit}.service.pid").write_text(f"{pid}\n")
print(f"Running as unit: {unit}.service")
FAKE_SYSTEMD_RUN
chmod +x "$FAKE_BIN/systemd-run"

cat > "$FAKE_BIN/systemctl" <<'FAKE_SYSTEMCTL'
#!/usr/bin/python3
import os
import pathlib
import signal
import sys
import time

state = pathlib.Path(os.environ["FAKE_SYSTEMD_DIR"])
args = [argument for argument in sys.argv[1:] if argument != "--user"]
with (state / "calls.log").open("a") as calls:
    calls.write("systemctl " + " ".join(args) + "\n")

if not args:
    raise SystemExit(2)
action = args[0]
unit = next((argument for argument in reversed(args[1:]) if not argument.startswith("-")), "")
pid_file = state / f"{unit}.pid"
show_mode_file = state / f"{unit}.show-mode"
control_mode_file = state / f"{unit}.control-mode"

def mode(path):
    try:
        return path.read_text().strip()
    except OSError:
        return "normal"

def read_pid():
    try:
        return int(pid_file.read_text().strip())
    except (OSError, ValueError):
        return 0

def live(pid):
    if pid <= 0:
        return False
    try:
        fields = pathlib.Path(f"/proc/{pid}/stat").read_text().split()
        return len(fields) > 2 and fields[2] != "Z"
    except OSError:
        return False

pid = read_pid()
is_live = live(pid)
is_scope = unit.endswith(".scope")

def print_main_pid(value):
    if not is_scope:
        print(f"MainPID={value}")

if action == "show":
    show_mode = mode(show_mode_file)
    if show_mode == "fail":
        sys.stderr.write(f"injected systemctl show failure for {unit}\n")
        raise SystemExit(5)
    if show_mode == "empty":
        raise SystemExit(0)
    if show_mode == "malformed":
        print("LoadState")
        print("ActiveState=inactive")
        print("SubState=dead")
        raise SystemExit(0)
    if show_mode == "partial":
        print("LoadState=loaded")
        print("ActiveState=inactive")
        raise SystemExit(0)
    if show_mode == "no-mainpid":
        print("LoadState=loaded")
        print("ActiveState=inactive")
        print("SubState=dead")
        raise SystemExit(0)
    if show_mode == "active-orphan":
        print("LoadState=loaded")
        print("ActiveState=active")
        print("SubState=running")
        print_main_pid(424242)
        raise SystemExit(0)
    if show_mode == "active-once":
        show_mode_file.write_text("not-found\n")
        print("LoadState=loaded")
        print("ActiveState=active")
        print("SubState=running")
        print_main_pid(424242)
        raise SystemExit(0)
    if show_mode == "not-found":
        print("LoadState=not-found")
        print("ActiveState=inactive")
        print("SubState=dead")
        print_main_pid(0)
        raise SystemExit(0)
    if is_live:
        print("LoadState=loaded")
        print("ActiveState=active")
        print("SubState=running")
        print_main_pid(pid)
    else:
        print("LoadState=not-found")
        print("ActiveState=inactive")
        print("SubState=dead")
        print_main_pid(0)
    raise SystemExit(0)

if action == "is-active":
    if "--quiet" not in args:
        print("active" if is_live else "inactive")
    raise SystemExit(0 if is_live else 3)

if action in ("kill", "stop", "reset-failed"):
    control_mode = mode(control_mode_file)
    if control_mode in ("fail", action):
        sys.stderr.write(f"injected systemctl {action} failure for {unit}\n")
        raise SystemExit(6)

if action in ("kill", "stop"):
    if is_live:
        chosen = signal.SIGTERM
        if any("KILL" in argument for argument in args):
            chosen = signal.SIGKILL
        try:
            os.killpg(pid, chosen)
        except ProcessLookupError:
            pass
        deadline = time.monotonic() + (2.0 if action == "stop" else 0.2)
        while live(pid) and time.monotonic() < deadline:
            time.sleep(0.02)
        if action == "stop" and live(pid):
            try:
                os.killpg(pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
    if action == "stop":
        pid_file.unlink(missing_ok=True)
    raise SystemExit(0)

if action == "reset-failed":
    raise SystemExit(0)

raise SystemExit(2)
FAKE_SYSTEMCTL
chmod +x "$FAKE_BIN/systemctl"

git -C "$FIXTURE_ROOT" init -q
git -C "$FIXTURE_ROOT" config user.email issue1956@example.invalid
git -C "$FIXTURE_ROOT" config user.name issue1956
git -C "$FIXTURE_ROOT" add scripts
git -C "$FIXTURE_ROOT" commit -qm fixture
EXPECTED_SHA="$(git -C "$FIXTURE_ROOT" rev-parse HEAD)"

RUNNER="$FIXTURE_ROOT/scripts/full-jvm-gate-detached.py"
export PATH="$FAKE_BIN:/usr/bin:/bin"
export FAKE_SYSTEMD_DIR
export FAKE_GATE_MODE_FILE="$TMP_ROOT/gate-mode"
export FAKE_GATE_PID_FILE="$TMP_ROOT/gate-pid"
export POCKETSHELL_FULL_JVM_GATE_STATE_DIR="$STATE_DIR"

wait_for_file() {
  local file="$1"
  local deadline=$((SECONDS + 12))
  while [[ ! -s "$file" && $SECONDS -lt $deadline ]]; do
    sleep 0.05
  done
  [[ -s "$file" ]] || fail "timed out waiting for durable artifact: $file"
}

wait_for_terminal_status() {
  local run_id="$1"
  local expected="$2"
  local deadline=$((SECONDS + 12))
  local output=""
  while [[ $SECONDS -lt $deadline ]]; do
    output="$("$RUNNER" status --run-id "$run_id" 2>&1 || true)"
    if grep -Fqx "state=$expected" <<< "$output"; then
      printf '%s\n' "$output"
      return 0
    fi
    sleep 0.05
  done
  fail "run $run_id never reached $expected; last status: $output"
}

metadata_value() {
  local run_dir="$1"
  local key="$2"
  python3 - "$run_dir/metadata.json" "$key" <<'PY'
import json
import pathlib
import sys

print(json.loads(pathlib.Path(sys.argv[1]).read_text())[sys.argv[2]])
PY
}

require_status_inspection_failure() {
  local run_id="$1"
  local label="$2"
  local output=""
  local rc=0
  output="$("$RUNNER" status --run-id "$run_id" 2>&1)" || rc=$?
  [[ $rc -ne 0 ]] || fail "$label returned exit 0: $output"
  ! grep -Fqx 'state=PASS' <<< "$output" || fail "$label laundered failed inspection into PASS: $output"
  ! grep -Fqx 'orphan_units=none' <<< "$output" || fail "$label asserted orphan_units=none without inspection: $output"
  grep -Eq 'state=INSPECTION_FAILED|FAIL:.*inspect' <<< "$output" \
    || fail "$label did not expose actionable inspection evidence: $output"
}

start_run() {
  local mode="$1"
  local run_id="$2"
  printf '%s\n' "$mode" > "$FAKE_GATE_MODE_FILE"
  rm -f "$FAKE_GATE_PID_FILE"
  "$RUNNER" start --run-id "$run_id"
}

# C1: start must return while the canonical gate is still running. This is the
# reported regression: a caller with a shorter lifetime must not own the gate's
# lifetime or verdict.
started_at_ns="$(date +%s%N)"
start_output="$(start_run pass survives-caller)"
start_elapsed_ms=$(( (10#$(date +%s%N) - 10#$started_at_ns) / 1000000 ))
run_dir="$(sed -n 's/^run_dir=//p' <<< "$start_output")"
[[ -n "$run_dir" ]] || fail "start did not report its durable run directory: $start_output"
[[ $start_elapsed_ms -lt 1000 ]] || fail "start stayed attached for ${start_elapsed_ms}ms"
running_status=""
running_rc=0
running_status="$("$RUNNER" status --run-id survives-caller 2>&1)" || running_rc=$?
[[ $running_rc -eq 0 ]] || fail "real active scope shape without MainPID was rejected: $running_status"
grep -Fqx 'state=RUNNING' <<< "$running_status" || fail "detached run was not live after its launching shell returned: $running_status"
pass_case "launch returns before the longer gate and a real-shape scope without MainPID reports RUNNING"

pass_status="$(wait_for_terminal_status survives-caller PASS)"
grep -Fqx 'exit_code=0' <<< "$pass_status" || fail "PASS status lost the real gate exit code: $pass_status"
grep -Fqx 'orphan_units=none' <<< "$pass_status" || fail "PASS status reports a leftover exact unit: $pass_status"
grep -Fqx 'service_load_state=not-found' <<< "$pass_status" || fail "PASS lacked explicit service not-found evidence: $pass_status"
grep -Fqx 'scope_load_state=not-found' <<< "$pass_status" || fail "PASS lacked explicit scope not-found evidence: $pass_status"
grep -Fqx 'scope_state=inactive/dead' <<< "$pass_status" || fail "PASS lost the real completed scope state: $pass_status"
pass_case "a run longer than its caller reaches PASS with a real-shape not-found scope without MainPID"

# C2/C3: all requested durable provenance is written by the run, not inferred
# from a vanished transient unit after the fact.
python3 - "$run_dir/metadata.json" "$run_dir/result.json" "$EXPECTED_SHA" <<'PY'
import json
import pathlib
import sys

metadata = json.loads(pathlib.Path(sys.argv[1]).read_text())
result = json.loads(pathlib.Path(sys.argv[2]).read_text())
expected_sha = sys.argv[3]
required_metadata = {
    "run_id", "root", "git_sha", "started_at", "service_unit",
    "scope_unit", "canonical_command",
}
required_result = {"ended_at", "exit_code", "state", "scope_cleanup"}
missing = sorted(required_metadata - metadata.keys()) + sorted(required_result - result.keys())
if missing:
    raise SystemExit(f"missing durable metadata keys: {missing}")
if metadata["git_sha"] != expected_sha:
    raise SystemExit(f"wrong git SHA: {metadata['git_sha']} != {expected_sha}")
expected_command = [str(pathlib.Path(metadata["root"]) / "scripts/full-jvm-gate.py"), "--unit", metadata["scope_unit"]]
if metadata["canonical_command"] != expected_command:
    raise SystemExit(f"canonical command changed: {metadata['canonical_command']} != {expected_command}")
if result["state"] != "PASS" or result["exit_code"] != 0 or not result["scope_cleanup"]["inactive"]:
    raise SystemExit(f"bad durable result: {result}")
PY
pass_case "durable metadata records times, SHA, exact service/scope identities, command, exit, and cleanup"

tail_output="$("$RUNNER" tail --run-id survives-caller --lines 20)"
grep -Fq 'real_gradle_verdict=PASS' <<< "$tail_output" || fail "tail did not expose durable canonical output: $tail_output"
grep -Eq '^fake_gate_args=--unit[[:space:]]+pocketshell-full-jvm-' <<< "$tail_output" \
  || fail "canonical delegation dropped the exact --unit scope argument: $tail_output"
pass_case "tail exposes the canonical stdout/stderr and exact immutable delegation"

service_name="$(metadata_value "$run_dir" service_unit).service"
scope_name="$(metadata_value "$run_dir" scope_unit).scope"

# C4: every systemd-show failure shape must be different from an explicitly
# observed LoadState=not-found. None may preserve PASS or claim no orphans.
printf 'fail\n' > "$FAKE_SYSTEMD_DIR/$service_name.show-mode"
require_status_inspection_failure survives-caller "systemctl show command failure"
rm -f "$FAKE_SYSTEMD_DIR/$service_name.show-mode"
pass_case "systemctl show command failure is nonzero and never PASS/clean"

printf 'empty\n' > "$FAKE_SYSTEMD_DIR/$service_name.show-mode"
require_status_inspection_failure survives-caller "empty systemctl show output"
rm -f "$FAKE_SYSTEMD_DIR/$service_name.show-mode"
pass_case "empty systemctl show output is nonzero and never PASS/clean"

printf 'malformed\n' > "$FAKE_SYSTEMD_DIR/$service_name.show-mode"
require_status_inspection_failure survives-caller "malformed systemctl show output"
rm -f "$FAKE_SYSTEMD_DIR/$service_name.show-mode"
pass_case "malformed systemctl show output is nonzero and never PASS/clean"

printf 'no-mainpid\n' > "$FAKE_SYSTEMD_DIR/$service_name.show-mode"
require_status_inspection_failure survives-caller "service missing MainPID"
rm -f "$FAKE_SYSTEMD_DIR/$service_name.show-mode"
pass_case "service inspection remains strict when MainPID is missing"

printf 'malformed\n' > "$FAKE_SYSTEMD_DIR/$scope_name.show-mode"
require_status_inspection_failure survives-caller "malformed scope show output"
rm -f "$FAKE_SYSTEMD_DIR/$scope_name.show-mode"
pass_case "malformed real-shape scope output is nonzero and never PASS/clean"

printf 'partial\n' > "$FAKE_SYSTEMD_DIR/$scope_name.show-mode"
require_status_inspection_failure survives-caller "partial scope properties"
rm -f "$FAKE_SYSTEMD_DIR/$scope_name.show-mode"
pass_case "partial real-shape scope properties are nonzero and never PASS/clean"

printf 'fail\n' > "$FAKE_SYSTEMD_DIR/$scope_name.show-mode"
require_status_inspection_failure survives-caller "partial service/scope inspection"
rm -f "$FAKE_SYSTEMD_DIR/$scope_name.show-mode"
pass_case "partial service/scope inspection cannot certify a durable PASS"

printf 'active-orphan\n' > "$FAKE_SYSTEMD_DIR/$scope_name.show-mode"
orphan_status=""
orphan_rc=0
orphan_status="$("$RUNNER" status --run-id survives-caller 2>&1)" || orphan_rc=$?
[[ $orphan_rc -ne 0 ]] || fail "an active post-result scope returned exit 0: $orphan_status"
grep -Fqx 'state=PASS' <<< "$orphan_status" || fail "active-orphan check lost the durable canonical verdict: $orphan_status"
grep -Fqx 'scope_load_state=loaded' <<< "$orphan_status" || fail "active orphan lost its real scope load state: $orphan_status"
grep -Fqx 'scope_state=active/running' <<< "$orphan_status" || fail "active orphan lost its real scope active state: $orphan_status"
grep -Fqx "orphan_units=$scope_name" <<< "$orphan_status" || fail "active post-result scope was not identified: $orphan_status"
rm -f "$FAKE_SYSTEMD_DIR/$scope_name.show-mode"
pass_case "an active exact unit after a result is reported as an orphan with nonzero status"

# C5: the internal runner's own cleanup must persist CLEANUP_FAILURE when its
# wait/inspection cannot prove the canonical scope inactive. This is the exact
# reviewed bug: the old unknown-means-inactive branch persisted PASS here.
cleanup_output="$(start_run pass cleanup-inspection-failure)"
cleanup_dir="$(sed -n 's/^run_dir=//p' <<< "$cleanup_output")"
cleanup_scope="$(metadata_value "$cleanup_dir" scope_unit).scope"
printf 'fail\n' > "$FAKE_SYSTEMD_DIR/$cleanup_scope.show-mode"
wait_for_file "$cleanup_dir/result.json"
python3 - "$cleanup_dir/result.json" <<'PY'
import json
import pathlib
import sys

result = json.loads(pathlib.Path(sys.argv[1]).read_text())
if result.get("state") != "CLEANUP_FAILURE" or result.get("exit_code") == 0:
    raise SystemExit(f"cleanup inspection failure was laundered into a gate verdict: {result}")
cleanup = result.get("scope_cleanup", {})
if cleanup.get("inactive") is not False or not cleanup.get("inspection_errors"):
    raise SystemExit(f"cleanup inspection failure was not persisted fail-closed: {result}")
PY
rm -f "$FAKE_SYSTEMD_DIR/$cleanup_scope.show-mode"
pass_case "internal wait/cleanup inspection failure persists CLEANUP_FAILURE, never PASS"

# C5b: a required scope control failure must also poison the durable cleanup
# result even if a later force-kill makes the unit observably inactive.
cleanup_control_output="$(start_run pass cleanup-control-failure)"
cleanup_control_dir="$(sed -n 's/^run_dir=//p' <<< "$cleanup_control_output")"
cleanup_control_scope="$(metadata_value "$cleanup_control_dir" scope_unit).scope"
printf 'active-once\n' > "$FAKE_SYSTEMD_DIR/$cleanup_control_scope.show-mode"
printf 'stop\n' > "$FAKE_SYSTEMD_DIR/$cleanup_control_scope.control-mode"
wait_for_file "$cleanup_control_dir/result.json"
python3 - "$cleanup_control_dir/result.json" <<'PY'
import json
import pathlib
import sys

result = json.loads(pathlib.Path(sys.argv[1]).read_text())
if result.get("state") != "CLEANUP_FAILURE" or result.get("exit_code") == 0:
    raise SystemExit(f"cleanup control failure was laundered into a gate verdict: {result}")
cleanup = result.get("scope_cleanup", {})
if cleanup.get("inactive") is not False or not cleanup.get("control_errors"):
    raise SystemExit(f"cleanup control failure was not persisted fail-closed: {result}")
PY
rm -f "$FAKE_SYSTEMD_DIR/$cleanup_control_scope.show-mode"
rm -f "$FAKE_SYSTEMD_DIR/$cleanup_control_scope.control-mode"
pass_case "internal scope control failure persists CLEANUP_FAILURE and inactive=false"

# C6: a real non-zero gate verdict must remain FAIL, not be laundered into the
# interruption category merely because the systemd unit is already gone.
failure_output="$(start_run fail real-failure)"
failure_dir="$(sed -n 's/^run_dir=//p' <<< "$failure_output")"
failure_status="$(wait_for_terminal_status real-failure FAIL)"
grep -Fqx 'exit_code=7' <<< "$failure_status" || fail "FAIL status lost the real gate exit: $failure_status"
grep -Fq 'real_gradle_verdict=FAIL' "$failure_dir/gate.log" || fail "FAIL log lost canonical stderr"
! grep -Fq 'state=INTERRUPTED' <<< "$failure_status" || fail "a test/gate failure was misclassified as interruption"
pass_case "a canonical non-zero verdict is FAIL, never interruption"

# C7: an observed transient service which disappears without writing a result
# is interrupted. Explicit not-found is valid evidence of disappearance, but it
# is not a gate PASS.
vanished_output="$(start_run long vanished-without-result)"
vanished_dir="$(sed -n 's/^run_dir=//p' <<< "$vanished_output")"
vanished_service="$(metadata_value "$vanished_dir" service_unit).service"
vanished_pid="$(cat "$FAKE_SYSTEMD_DIR/$vanished_service.pid")"
kill -KILL -- "-$vanished_pid"
rm -f "$FAKE_SYSTEMD_DIR/$vanished_service.pid" "$vanished_dir/result.json"
vanished_status=""
vanished_rc=0
vanished_status="$("$RUNNER" status --run-id vanished-without-result 2>&1)" || vanished_rc=$?
[[ $vanished_rc -ne 0 ]] || fail "vanished observed service returned exit 0: $vanished_status"
grep -Fqx 'state=INTERRUPTED' <<< "$vanished_status" || fail "vanished observed service was not INTERRUPTED: $vanished_status"
! grep -Fqx 'state=PASS' <<< "$vanished_status" || fail "vanished service was laundered into PASS: $vanished_status"
pass_case "an observed service disappearing without a result is INTERRUPTED"

# C8: stop must fail closed for inspection and control failures. It can still
# target exact units for best-effort cleanup, but it cannot print complete/0.
stop_inspect_output="$(start_run long stop-inspection-failure)"
stop_inspect_dir="$(sed -n 's/^run_dir=//p' <<< "$stop_inspect_output")"
stop_inspect_scope="$(metadata_value "$stop_inspect_dir" scope_unit).scope"
printf 'fail\n' > "$FAKE_SYSTEMD_DIR/$stop_inspect_scope.show-mode"
stop_inspect_status=""
stop_inspect_rc=0
stop_inspect_status="$("$RUNNER" stop --run-id stop-inspection-failure 2>&1)" || stop_inspect_rc=$?
[[ $stop_inspect_rc -ne 0 ]] || fail "stop inspection failure returned exit 0: $stop_inspect_status"
! grep -Fqx 'stop=complete' <<< "$stop_inspect_status" || fail "stop inspection failure claimed complete: $stop_inspect_status"
grep -Eq 'stop=inspection-failed|inspection_error' <<< "$stop_inspect_status" \
  || fail "stop inspection failure lacked actionable evidence: $stop_inspect_status"
rm -f "$FAKE_SYSTEMD_DIR/$stop_inspect_scope.show-mode"
pass_case "stop inspection failure is nonzero and never complete"

stop_control_output="$(start_run long stop-control-failure)"
stop_control_dir="$(sed -n 's/^run_dir=//p' <<< "$stop_control_output")"
stop_control_service="$(metadata_value "$stop_control_dir" service_unit).service"
printf 'kill\n' > "$FAKE_SYSTEMD_DIR/$stop_control_service.control-mode"
stop_control_status=""
stop_control_rc=0
stop_control_status="$("$RUNNER" stop --run-id stop-control-failure 2>&1)" || stop_control_rc=$?
[[ $stop_control_rc -ne 0 ]] || fail "stop control failure returned exit 0: $stop_control_status"
! grep -Fqx 'stop=complete' <<< "$stop_control_status" || fail "stop control failure claimed complete: $stop_control_status"
grep -Eq 'stop=control-failed|control_error' <<< "$stop_control_status" \
  || fail "stop control failure lacked actionable evidence: $stop_control_status"
rm -f "$FAKE_SYSTEMD_DIR/$stop_control_service.control-mode"
pass_case "stop control failure is nonzero and never complete"

# C9: two real run IDs must remain isolated. Stopping one may not send a
# control command to the other's service or end its canonical process.
first_output="$(start_run long isolated-first)"
first_dir="$(sed -n 's/^run_dir=//p' <<< "$first_output")"
second_output="$(start_run long isolated-second)"
second_dir="$(sed -n 's/^run_dir=//p' <<< "$second_output")"
second_service="$(metadata_value "$second_dir" service_unit).service"
second_pid="$(cat "$FAKE_SYSTEMD_DIR/$second_service.pid")"
calls_before_stop="$(wc -l < "$FAKE_SYSTEMD_DIR/calls.log")"
"$RUNNER" stop --run-id isolated-first >/dev/null
second_status="$("$RUNNER" status --run-id isolated-second)"
grep -Fqx 'state=RUNNING' <<< "$second_status" || fail "stopping first run changed second run: $second_status"
kill -0 "$second_pid" 2>/dev/null || fail "stopping first run killed second run process"
tail -n "+$((calls_before_stop + 1))" "$FAKE_SYSTEMD_DIR/calls.log" \
  | grep -E 'systemctl (kill|stop).*' \
  | grep -Fq "$second_service" \
  && fail "stopping first run sent a control command to second run"
"$RUNNER" stop --run-id isolated-second >/dev/null
pass_case "distinct run IDs retain isolated units and cleanup"

# C10/C11: stopping one detached run is an interruption and may target only its
# exact service/scope. An unrelated similarly named process is left alive.
setsid sleep 60 >/dev/null 2>&1 &
UNRELATED_PID="$!"
printf '%s\n' "$UNRELATED_PID" > "$FAKE_SYSTEMD_DIR/pocketshell-unrelated.service.pid"
interrupt_output="$(start_run long interrupted)"
interrupt_dir="$(sed -n 's/^run_dir=//p' <<< "$interrupt_output")"
wait_for_file "$FAKE_GATE_PID_FILE"
gate_pid="$(cat "$FAKE_GATE_PID_FILE")"
"$RUNNER" stop --run-id interrupted >/dev/null
interrupted_status="$(wait_for_terminal_status interrupted INTERRUPTED)"
grep -Fqx 'orphan_units=none' <<< "$interrupted_status" || fail "interrupted run left an exact unit: $interrupted_status"
if kill -0 "$gate_pid" 2>/dev/null; then
  fail "interrupted run left its fake Gradle/gate process alive: $gate_pid"
fi
kill -0 "$UNRELATED_PID" 2>/dev/null || fail "exact cleanup killed an unrelated service/process"
grep -Fq 'fake_gate_interrupted=yes' "$interrupt_dir/gate.log" || fail "the interrupted child never observed termination"
! grep -Fq 'state=FAIL' <<< "$interrupted_status" || fail "interrupted run was not classified separately from gate failure: $interrupted_status"
pass_case "interruption is distinct and cleanup kills only the run's exact process tree"

if grep -Fqi 'tmux' "$FAKE_SYSTEMD_DIR/calls.log" || grep -Fqi 'tmux' "$FIXTURE_ROOT/scripts/full-jvm-gate-detached.py"; then
  fail "detached gate touched or referenced tmux; the maintainer default socket must stay out of scope"
fi
pass_case "the detached lifecycle never uses any tmux socket"

# G6 mutation proof. Each live mutant must be rejected by the named load-bearing
# assertion, not merely by a syntax error or an unrelated earlier check.
if [[ "$MUTATION_PROBE" != "1" ]]; then
  mutate_and_require_red() {
    local label="$1"
    local old="$2"
    local new="$3"
    local expected_failure="$4"
    local mutant="$TMP_ROOT/mutant-$label.py"
    python3 - "$RUNNER_SOURCE" "$mutant" "$old" "$new" <<'PY'
import pathlib
import sys

source_path, destination_path, old, new = sys.argv[1:]
source = pathlib.Path(source_path).read_text()
if source.count(old) != 1:
    raise SystemExit(f"mutation target count is {source.count(old)}, expected 1: {old!r}")
mutated = source.replace(old, new)
compile(mutated, destination_path, "exec")
destination = pathlib.Path(destination_path)
destination.write_text(mutated)
destination.chmod(0o755)
PY
    local mutation_log="$TMP_ROOT/mutation-$label.log"
    local mutation_rc=0
    MUTATION_PROBE=1 RUNNER_UNDER_TEST="$mutant" timeout 45 "$ROOT_DIR/scripts/test-full-jvm-gate-detached.sh" \
      >"$mutation_log" 2>&1 || mutation_rc=$?
    [[ $mutation_rc -ne 0 ]] || fail "mutation $label survived the regression harness"
    grep -Fq "$expected_failure" "$mutation_log" \
      || fail "mutation $label reddened the wrong check; expected '$expected_failure': $(tail -n 20 "$mutation_log")"
    pass_case "mutation $label is selectively killed by its load-bearing assertion"
  }

  mutate_and_require_red \
    delegation \
    'return [str(canonical), "--unit", scope_unit]' \
    'return [str(canonical)]' \
    'canonical command changed:'
  mutate_and_require_red \
    classification \
    'return "INTERRUPTED" if return_code < 0 or return_code >= 128 else "FAIL"' \
    'return "FAIL"' \
    'never reached INTERRUPTED'

  # Restore both halves of the reviewed fail-open behavior: failed `show`
  # becomes synthetic unknown properties, and unknown is treated as merely
  # not-active. The earlier lifecycle cases must stay green, then the first
  # failed-inspection case alone must reject the mutant.
  fail_closed_mutant="$TMP_ROOT/mutant-fail-closed-inspection.py"
  python3 - "$RUNNER_SOURCE" "$fail_closed_mutant" <<'PY'
import pathlib
import sys

source_path, destination_path = sys.argv[1:]
source = pathlib.Path(source_path).read_text()
replacements = (
    (
        'raise DetachedGateError(f"cannot inspect systemd unit {unit}: {detail}")',
        'return {"LoadState": "unknown", "ActiveState": "unknown", "SubState": "unknown", "MainPID": "0"}',
    ),
    (
        '''def active(properties: dict[str, str]) -> bool:
    load_state = properties.get("LoadState")
    active_state = properties.get("ActiveState")
    if load_state == "loaded" and active_state in {"active", "activating", "reloading"}:
        return True
    if load_state == "not-found" and active_state == "inactive":
        return False
    if load_state == "loaded" and active_state in {"inactive", "failed", "deactivating"}:
        return active_state == "deactivating"
    raise DetachedGateError(
        "cannot classify systemd unit state: "
        f"LoadState={load_state!r}, ActiveState={active_state!r}, "
        f"SubState={properties.get('SubState')!r}"
    )''',
        '''def active(properties: dict[str, str]) -> bool:
    return properties.get("ActiveState") in {"active", "activating", "reloading"}''',
    ),
)
for old, new in replacements:
    count = source.count(old)
    expected = 2 if old.startswith('raise DetachedGateError(f"cannot inspect systemd unit') else 1
    if count != expected:
        raise SystemExit(f"mutation target count is {count}, expected {expected}: {old!r}")
    source = source.replace(old, new, 1)
compile(source, destination_path, "exec")
destination = pathlib.Path(destination_path)
destination.write_text(source)
destination.chmod(0o755)
PY
  fail_closed_log="$TMP_ROOT/mutation-fail-closed-inspection.log"
  fail_closed_rc=0
  MUTATION_PROBE=1 RUNNER_UNDER_TEST="$fail_closed_mutant" timeout 45 \
    "$ROOT_DIR/scripts/test-full-jvm-gate-detached.sh" >"$fail_closed_log" 2>&1 || fail_closed_rc=$?
  [[ $fail_closed_rc -ne 0 ]] || fail "mutation fail_closed_inspection survived the regression harness"
  grep -Fq 'FAIL: systemctl show command failure returned exit 0' "$fail_closed_log" \
    || fail "mutation fail_closed_inspection reddened the wrong check: $(tail -n 20 "$fail_closed_log")"
  [[ "$(grep -c '^PASS:' "$fail_closed_log")" -eq 4 ]] \
    || fail "mutation fail_closed_inspection changed an earlier behavior: $(cat "$fail_closed_log")"
  pass_case "mutation fail_closed_inspection is selectively killed by the first fail-closed assertion"

  mutate_and_require_red \
    scope_schema \
    '        return UNIT_STATE_PROPERTY_NAMES' \
    '        return UNIT_SERVICE_PROPERTY_NAMES' \
    'real active scope shape without MainPID was rejected:'
fi

expected_checks=21
if [[ "$MUTATION_PROBE" != "1" ]]; then
  expected_checks=25
fi
[[ $CHECKS -eq $expected_checks ]] || fail "executed $CHECKS checks, expected $expected_checks"
printf 'PASS: full-JVM detached-runner regression harness (%s/%s checks)\n' "$CHECKS" "$expected_checks"
