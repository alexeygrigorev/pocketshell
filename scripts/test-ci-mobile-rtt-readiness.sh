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

# The readiness step must remain inside the three-shard emulator job and before
# the action that launches ci-journey-suite.sh. One shared matrix job definition
# means this ordering is exercised independently by shards 0, 1, and 2.
python3 - "$WORKFLOW" <<'PY' || fail "mobile-RTT readiness is not on the exact three-shard journey path"
import sys
from pathlib import Path

text = Path(sys.argv[1]).read_text()
job_start = text.index("\n  emulator-journey:\n")
job_end = text.index("\n  emulator-journey-verdict:\n", job_start)
job = text[job_start:job_end]

assert "shard: [0, 1, 2]" in job
readiness = job.index(
    "- name: Start Docker fixture (mobile RTT packet-loss proxy, issue #1876)"
)
journey = job.index("- name: Run load-bearing journey subset on the emulator")
assert readiness < journey
assert "script: scripts/ci-journey-suite.sh" in job[journey:]
PY

sandbox="$(mktemp -d)"
trap 'rm -rf "$sandbox"' EXIT
mkdir -p "$sandbox/repo/tests/docker" "$sandbox/bin"
cp "$REPO_ROOT/tests/docker/test_key" "$sandbox/repo/tests/docker/test_key"
chmod 0644 "$sandbox/repo/tests/docker/test_key"

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
) || fail "mobile-RTT readiness invoked OpenSSH before securing the 0644 checkout key"

[[ "$(stat -c '%a' "$sandbox/repo/tests/docker/test_key")" == "600" ]] \
  || fail "readiness block did not leave the checkout key owner-only"
grep -Fq 'compose -f tests/docker/docker-compose.yml up -d --build --no-deps packet-loss-proxy' \
  "$sandbox/docker.log" || fail "readiness no longer starts the packet-loss proxy"
grep -Fq -- '-i tests/docker/test_key -p 2229' "$sandbox/ssh.log" \
  || fail "readiness no longer probes the proxy with the repository test key on port 2229"
grep -Fq -- '-o BatchMode=yes -o ConnectTimeout=3' "$sandbox/ssh.log" \
  || fail "readiness no longer uses the bounded non-interactive OpenSSH proof"
grep -Fq -- '-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null testuser@127.0.0.1 true' \
  "$sandbox/ssh.log" || fail "readiness no longer proves SSH auth through the proxy"

pass "mobile-RTT readiness secures a 0644 checkout key before the preserved proxy/SSH proof"
