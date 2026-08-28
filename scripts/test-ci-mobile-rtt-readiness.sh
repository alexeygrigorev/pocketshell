#!/usr/bin/env bash
# Issue #1876 (reopened): regression proof for the emulator job's mobile-RTT
# proxy readiness step. Git checks the committed test key out as 0644, while
# OpenSSH deliberately refuses a private key readable by group/other. Drive the
# workflow's real run block with command stubs so ordering is observed: the key
# must be owner-only before the first real-shaped SSH proof is attempted.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd -P)"
WORKFLOW="${CI_MOBILE_RTT_WORKFLOW:-$REPO_ROOT/.github/workflows/tests.yml}"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "PASS: $*"; }

[[ -f "$WORKFLOW" ]] || fail "workflow not found: $WORKFLOW"

# The readiness step must remain inside the SHARDED emulator job and before the
# action that launches ci-journey-suite.sh. One shared matrix job definition
# means this ordering is exercised independently by every shard.
#
# Issue #2060: this used to assert the literal `shard: [0, 1, 2]`, which the
# 3 -> 4 shard change would simply have re-pinned to a new literal. It now takes
# the matrix length from scripts/ci-journey-shard-count.sh (which itself rejects
# a gapped / non-zero-based / duplicated matrix) and CROSS-CHECKS the job's
# POCKETSHELL_JOURNEY_CI_SHARD_TOTAL against it. That pairing is the property
# that actually matters and nothing checked before: the suite selects classes
# with `hash % TOTAL == INDEX`, so a _TOTAL LARGER than the matrix leaves whole
# hash buckets SELECTED BY NO SHARD — every class in them silently stops running
# while the gate still reports green.
shard_total="$(bash "$SCRIPT_DIR/ci-journey-shard-count.sh" "$WORKFLOW")" \
  || fail "could not derive the emulator-journey shard count from the matrix"
python3 - "$WORKFLOW" "$shard_total" <<'PY' || fail "mobile-RTT readiness is not on the exact sharded journey path"
import sys
from pathlib import Path

text = Path(sys.argv[1]).read_text()
shard_total = int(sys.argv[2])
job_start = text.index("\n  emulator-journey:\n")
job_end = text.index("\n  emulator-journey-verdict:\n", job_start)
job = text[job_start:job_end]

assert shard_total >= 3, f"emulator-journey must stay sharded, got {shard_total}"
assert f"POCKETSHELL_JOURNEY_CI_SHARD_TOTAL: {shard_total}" in job, (
    "POCKETSHELL_JOURNEY_CI_SHARD_TOTAL must equal the matrix length "
    f"({shard_total}); a mismatch silently drops or duplicates journey classes"
)
readiness = job.index(
    "- name: Start Docker fixture (mobile RTT packet-loss proxy, issue #1876)"
)
journey = job.index("- name: Run load-bearing journey subset on the emulator")
assert readiness < journey
assert "script: scripts/ci-journey-suite.sh" in job[journey:]
PY

sandbox="$(mktemp -d)"
trap 'rm -rf "$sandbox"' EXIT
mkdir -p "$sandbox/repo/tests/docker" "$sandbox/repo/scripts/lib" "$sandbox/bin"
cp "$REPO_ROOT/tests/docker/test_key" "$sandbox/repo/tests/docker/test_key"
chmod 0644 "$sandbox/repo/tests/docker/test_key"

# Issue #2095: the extracted production block now calls the bounded fixture
# retry helper. Copy the real helper, not a no-op stub, so this sandbox exercises
# its command dispatch and success artifact as production does. The helper is
# self-contained; its external utilities continue to come from the normal PATH,
# except issue #2381's agents-fixture-version.sh, which it sources directly and
# which must be copied alongside it or the sandbox run fails on "No such file".
fixture_retry_source="$REPO_ROOT/scripts/ci-journey-fixture-retry.sh"
fixture_retry_sandbox="$sandbox/repo/scripts/ci-journey-fixture-retry.sh"
[[ -f "$fixture_retry_source" ]] \
  || fail "repository fixture-retry wrapper not found: $fixture_retry_source"
cp "$fixture_retry_source" "$fixture_retry_sandbox"
chmod +x "$fixture_retry_sandbox"
cp "$REPO_ROOT/scripts/lib/agents-fixture-version.sh" "$sandbox/repo/scripts/lib/agents-fixture-version.sh"
[[ -x "$fixture_retry_sandbox" ]] \
  || fail "mobile-RTT sandbox is missing the executable fixture-retry wrapper"
cmp -s "$fixture_retry_source" "$fixture_retry_sandbox" \
  || fail "mobile-RTT sandbox must exercise the real fixture-retry wrapper"

run_block="$sandbox/mobile-rtt-readiness.sh"
awk '
  /- name: Start Docker fixture \(mobile RTT packet-loss proxy, issue #1876\)/ {
    in_step = 1
    next
  }
  in_step && /^[[:space:]]+run: \|[[:space:]]*$/ {
    in_run = 1
    next
  }
  in_run && /^      - name:/ { exit }
  in_run {
    sub(/^          /, "")
    print
  }
' "$WORKFLOW" > "$run_block"
[[ -s "$run_block" ]] || fail "could not extract the mobile-RTT readiness run block"

cat > "$sandbox/bin/docker" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$CI_MOBILE_RTT_DOCKER_LOG"
exit 0
STUB

cat > "$sandbox/bin/ssh" <<'STUB'
#!/usr/bin/env bash
set -uo pipefail
printf '%s\n' "$*" >> "$CI_MOBILE_RTT_SSH_LOG"

key=""
previous=""
for argument in "$@"; do
  if [[ "$previous" == "-i" ]]; then key="$argument"; fi
  previous="$argument"
done
[[ -n "$key" && -f "$key" ]] || { echo "stub ssh: missing -i key" >&2; exit 2; }

mode="$(stat -c '%a' "$key")"
if [[ "$mode" != "600" ]]; then
  echo '@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@' >&2
  echo '@         WARNING: UNPROTECTED PRIVATE KEY FILE!          @' >&2
  echo "Permissions 0$mode for '$key' are too open." >&2
  echo "Load key \"$key\": bad permissions" >&2
  exit 255
fi
exit 0
STUB

cat > "$sandbox/bin/sleep" <<'STUB'
#!/usr/bin/env bash
exit 0
STUB
chmod +x "$sandbox/bin/docker" "$sandbox/bin/ssh" "$sandbox/bin/sleep"

export CI_MOBILE_RTT_DOCKER_LOG="$sandbox/docker.log"
export CI_MOBILE_RTT_SSH_LOG="$sandbox/ssh.log"

(
  cd "$sandbox/repo" || exit 1
  PATH="$sandbox/bin:$PATH" bash -eu "$run_block"
) || fail "mobile-RTT readiness block failed (check the real fixture-retry wrapper, key ordering, and SSH proof)"

[[ "$(stat -c '%a' "$sandbox/repo/tests/docker/test_key")" == "600" ]] \
  || fail "readiness block did not leave the checkout key owner-only"
grep -Fq 'compose -f tests/docker/docker-compose.yml up -d --build --no-deps packet-loss-proxy' \
  "$sandbox/docker.log" || fail "readiness no longer starts the packet-loss proxy"
[[ "$(wc -l < "$sandbox/docker.log")" -eq 1 ]] \
  || fail "successful packet-loss proxy setup must invoke Docker exactly once"
grep -Fxq 'status=success' \
  "$sandbox/repo/artifacts/ci-journey-setup/packet-loss-proxy/result.env" \
  || fail "real fixture-retry wrapper did not record successful setup"
grep -Fxq 'attempts=1' \
  "$sandbox/repo/artifacts/ci-journey-setup/packet-loss-proxy/result.env" \
  || fail "real fixture-retry wrapper did not preserve the one-attempt success bound"
grep -Fq -- '-i tests/docker/test_key -p 2229' "$sandbox/ssh.log" \
  || fail "readiness no longer probes the proxy with the repository test key on port 2229"
grep -Fq -- '-o BatchMode=yes -o ConnectTimeout=3' "$sandbox/ssh.log" \
  || fail "readiness no longer uses the bounded non-interactive OpenSSH proof"
grep -Fq -- '-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null testuser@127.0.0.1 true' \
  "$sandbox/ssh.log" || fail "readiness no longer proves SSH auth through the proxy"

pass "mobile-RTT readiness uses the real fixture-retry wrapper, secures the key, and preserves the proxy/SSH proof"
