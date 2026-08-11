#!/usr/bin/env bash
# Issue #1913: a deterministic failure before either emulator journey action
# runs must be RED, never guessed to be #771 emulator-never-booted INFRA. Drive
# the REAL workflow classifier body and the REAL aggregate reducer so workflow
# and fixture cannot drift into two implementations of the decision.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKFLOW="${CI_JOURNEY_WORKFLOW:-$REPO_ROOT/.github/workflows/tests.yml}"
AGG="$SCRIPT_DIR/ci-journey-aggregate-verdict.sh"
FIXTURE_RETRY="$SCRIPT_DIR/ci-journey-fixture-retry.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

# Extract a named workflow step's literal run body, substituting every GitHub
# expression from an explicit JSON map. An unmapped expression hard-fails so a
# workflow edit cannot silently turn this fixture into a different scenario.
extract_step_body() {
  local step_name="$1" out_path="$2" expressions="$3"
  STEP_NAME="$step_name" STEP_EXPRESSIONS="$expressions" \
    python3 - "$WORKFLOW" "$out_path" <<'PYEOF'
import json
import os
import re
import sys

workflow, out_path = sys.argv[1], sys.argv[2]
mapping = json.loads(os.environ["STEP_EXPRESSIONS"])
step_name = os.environ["STEP_NAME"]
lines = open(workflow, encoding="utf-8").read().splitlines()

step_re = re.compile(r"^(\s*)- name: " + re.escape(step_name) + r"\s*$")
start = indent = None
for index, line in enumerate(lines):
    match = step_re.match(line)
    if match:
        start, indent = index, len(match.group(1))
        break
if start is None:
    sys.exit("could not find step %r" % step_name)

run_index = None
for index in range(start + 1, len(lines)):
    line = lines[index]
    if line.strip() and len(line) - len(line.lstrip()) <= indent:
        break
    if re.match(r"^\s*run: \|\s*$", line):
        run_index = index
        break
if run_index is None:
    sys.exit("step %r has no run body" % step_name)

run_indent = len(lines[run_index]) - len(lines[run_index].lstrip())
body = []
for line in lines[run_index + 1:]:
    if line.strip() and len(line) - len(line.lstrip()) <= run_indent:
        break
    body.append(line)
pad = min(len(line) - len(line.lstrip()) for line in body if line.strip())
text = "\n".join(line[pad:] if line.strip() else "" for line in body)

unknown = []
def substitute(match):
    expression = match.group(1).strip()
    if expression not in mapping:
        unknown.append(expression)
        return ""
    return mapping[expression]

text = re.sub(r"\$\{\{(.*?)\}\}", substitute, text)
if unknown:
    sys.exit("unmapped workflow expression(s): %s" % ", ".join(sorted(set(unknown))))
open(out_path, "w", encoding="utf-8").write(text + "\n")
PYEOF
}

classify_expressions() {
  local first="$1" retry="$2"
  cat <<JSONEOF
{
  "steps.journey.outcome": "$first",
  "steps.journey_retry.outcome": "$retry",
  "steps.journey.conclusion": "$first",
  "steps.journey_retry.conclusion": "$retry",
  "steps.journey_summary.outputs.first_timeout": "false",
  "steps.journey_summary.outputs.first_failure": "false",
  "steps.journey_retry_budget.outputs.retry_allowed": "true",
  "steps.journey_retry_budget.outputs.retry_reason": "sufficient_remaining_budget",
  "steps.journey_retry_budget.outputs.retry_remaining_ms": "6000000",
  "steps.journey_retry_budget.outputs.retry_required_ms": "5400000",
  "steps.journey_retry_budget.outputs.retry_cost_model": "worst_case",
  "steps.journey_retry_budget.outputs.retry_shortfall_ms": "0",
  "steps.journey_retry_budget.outputs.retry_warm_build_deducted_ms": "0"
}
JSONEOF
}

CLASSIFY_OUT="" CLASSIFY_RC=0 CLASSIFY_TOKEN="" CLASSIFY_REASON=""
run_classify() {
  local name="$1" first="$2" retry="$3"
  local setup_source="${4:-}" journey_source="${5:-}"
  local ws="$SANDBOX/$name" body="$SANDBOX/$name-classifier.sh"
  mkdir -p "$ws/artifacts/ci-journey-shard-verdict"
  if [[ -n "$setup_source" ]]; then
    mkdir -p "$ws/artifacts/ci-journey-setup"
    cp -a "$setup_source/." "$ws/artifacts/ci-journey-setup/"
  fi
  if [[ -n "$journey_source" ]]; then
    mkdir -p "$ws/artifacts/ci-journey"
    cp -a "$journey_source/." "$ws/artifacts/ci-journey/"
  fi
  ln -s "$SCRIPT_DIR" "$ws/scripts"
  extract_step_body "Classify emulator-journey result (infra-abort vs test-failure)" \
    "$body" "$(classify_expressions "$first" "$retry")" \
    || fail "could not extract the real classifier body"

  # Model #1809's at-job-start seed. The classifier must replace it with a
  # current, reasoned token even on the pre-journey failure path.
  printf 'INFRA\nverdict_reason=preseed_before_classify\n' \
    > "$ws/artifacts/ci-journey-shard-verdict/shard-verdict.txt"
  : > "$ws/github-output.txt"
  set +e
  CLASSIFY_OUT="$(cd "$ws" && \
    GITHUB_RUN_ID=30659266867 GITHUB_RUN_ATTEMPT=4 \
    POCKETSHELL_JOURNEY_CI_SHARD_INDEX=2 \
    GITHUB_OUTPUT="$ws/github-output.txt" \
    bash --noprofile --norc -eo pipefail "$body" 2>&1)"
  CLASSIFY_RC=$?
  set -e
  CLASSIFY_TOKEN="$(head -n 1 "$ws/artifacts/ci-journey-shard-verdict/shard-verdict.txt")"
  CLASSIFY_REASON="$(sed -n 's/^verdict_reason=//p' "$ws/artifacts/ci-journey-shard-verdict/shard-verdict.txt")"
  CLASSIFY_FILE="$ws/artifacts/ci-journey-shard-verdict/shard-verdict.txt"
}

make_registry_stub() {
  local path="$1"
  cat > "$path" <<'STUB'
#!/usr/bin/env bash
set -uo pipefail
count=0
[[ ! -f "$STUB_COUNT" ]] || count="$(cat "$STUB_COUNT")"
count=$((count + 1))
printf '%s\n' "$count" > "$STUB_COUNT"
if [[ ( "$STUB_MODE" == "recover" || "$STUB_MODE" == "network-recover" ) && "$count" -ge 2 ]]; then
  echo "fixture build succeeded"
  exit 0
fi
if [[ "$STUB_MODE" == "unknown" ]]; then
  echo "failed open: unexpected status code"
  echo "https://product.example.test/api/build:"
  echo "502 Bad Gateway"
  exit 42
fi
if [[ "$STUB_MODE" == "mixed" ]]; then
  cat <<'LOG'
auth request https://auth.docker.io/token returned 200 OK
registry-1.docker.io/v2/ authentication and manifest lookup succeeded
pull setup completed normally
failed open: unexpected status code
https://product.example.test/api/build:
502 Bad Gateway
LOG
  exit 43
fi
if [[ "$STUB_MODE" == network-* ]]; then
  echo 'failed to do request: Head "https://registry-1.docker.io/v2/library/alpine/manifests/3.20": net/http: TLS handshake timeout'
  exit 38
fi
cat <<'LOG'
failed to copy: httpReadSeeker: failed open: unexpected status code
https://registry-1.docker.io/v2/library/alpine/blobs/sha256:bf8527eb54c3680e728d5b4b383a8ba730d72dae7236fbc8dff97ed6b224a731:
502 Bad Gateway
LOG
exit 37
STUB
  chmod +x "$path"
}

run_fixture_retry() {
  local name="$1" mode="$2"
  local root="$SANDBOX/$name-setup" count="$SANDBOX/$name-count"
  rm -rf "$root" "$count"
  set +e
  CI_JOURNEY_SETUP_ARTIFACT_ROOT="$root" \
    CI_JOURNEY_FIXTURE_RETRY_DELAY_SECONDS=0 \
    STUB_MODE="$mode" STUB_COUNT="$count" \
    "$FIXTURE_RETRY" packet-loss-proxy -- "$REGISTRY_STUB" \
    > "$SANDBOX/$name-retry.log" 2>&1
  FIXTURE_RC=$?
  set -e
  FIXTURE_CALLS=0
  [[ ! -f "$count" ]] || FIXTURE_CALLS="$(cat "$count")"
  FIXTURE_ROOT="$root"
}

write_token() {
  local dir="$1" shard="$2" token="$3"
  mkdir -p "$dir/emulator-journey-verdict-shard-$shard"
  printf '%s\n' "$token" > "$dir/emulator-journey-verdict-shard-$shard/shard-verdict.txt"
}

AGG_OUT="" AGG_RC=0 AGG_VERDICT=""
run_aggregate() {
  local dir="$1" upstream="$2"
  set +e
  AGG_OUT="$(EXPECTED_SHARDS=3 UPSTREAM_MATRIX_RESULT="$upstream" GITHUB_STEP_SUMMARY="" \
    bash "$AGG" "$dir" 2>&1)"
  AGG_RC=$?
  set -e
  AGG_VERDICT="$(sed -n 's/^AGGREGATE_VERDICT=//p' <<<"$AGG_OUT" | tail -n 1)"
}

assert_setup_upload_contract() {
  local step upload_line retry_line
  step="$(awk '
    /^      - name: Upload Docker logs$/ { capture=1 }
    capture && seen && /^      - name:/ { exit }
    capture { print; seen=1 }
  ' "$WORKFLOW")"
  [[ -n "$step" ]] || fail "could not find the named Upload Docker logs step"
  grep -Fxq '        if: always()' <<<"$step" \
    || fail "Upload Docker logs must run under if: always() after failed setup"
  grep -Fxq '        uses: actions/upload-artifact@v7' <<<"$step" \
    || fail "Upload Docker logs must use actions/upload-artifact@v7"
  grep -Fxq '            artifacts/ci-journey-setup/' <<<"$step" \
    || fail "Upload Docker logs must package artifacts/ci-journey-setup/"
  upload_line="$(grep -n '^      - name: Upload Docker logs$' "$WORKFLOW" | cut -d: -f1)"
  retry_line="$(grep -n 'Request failed-job retry for external setup INFRA' "$WORKFLOW" | cut -d: -f1)"
  [[ "$upload_line" =~ ^[0-9]+$ && "$retry_line" =~ ^[0-9]+$ && "$upload_line" -lt "$retry_line" ]] \
    || fail "setup logs must upload before the intentional failed-job trigger"
}

echo "== #1913 pre-journey phase classification =="
run_classify skipped skipped skipped

# Complete the exact run-30659266867 chain before asserting: on the buggy base,
# the real classifier writes INFRA/emulator_never_booted and the real aggregate
# ignores the failed upstream matrix, yielding green RE-RUN. Keeping both
# observations in one failure makes the red proof cover the whole blind spot.
d="$SANDBOX/skipped-token-plus-clean"; mkdir -p "$d"
cp "$CLASSIFY_FILE" "$d/shard-verdict-2.txt"
write_token "$d" 0 CLEAN; write_token "$d" 1 CLEAN
run_aggregate "$d" failure
[[ "$CLASSIFY_TOKEN" == "RED" && "$CLASSIFY_REASON" == "pre_journey_setup_failure" && "$CLASSIFY_RC" -ne 0 \
    && "$AGG_VERDICT" == "RED" && "$AGG_RC" -eq 1 ]] \
  || { printf '%s\n' "$CLASSIFY_OUT" "$AGG_OUT"; fail "skipped journey expected classifier RED/pre_journey_setup_failure and aggregate RED; got classifier $CLASSIFY_TOKEN/$CLASSIFY_REASON/exit$CLASSIFY_RC -> aggregate $AGG_VERDICT/exit$AGG_RC"; }
grep -qx 'shard=2' "$CLASSIFY_FILE" || fail "classifier token lost shard provenance"
grep -qx 'run_id=30659266867' "$CLASSIFY_FILE" || fail "classifier token lost run provenance"
grep -qx 'run_attempt=4' "$CLASSIFY_FILE" || fail "classifier token lost rerun-attempt provenance"
grep -qx 'verdict_reason=preseed_before_classify' "$CLASSIFY_FILE" \
  && fail "classifier left the #1809 pre-seed in place"
pass "skipped first/retry -> RED pre_journey_setup_failure -> aggregate RED, with current provenance"

echo
echo "== #2095 Docker Hub setup retry + exact signature classification =="
REGISTRY_STUB="$SANDBOX/registry-stub.sh"
make_registry_stub "$REGISTRY_STUB"

run_fixture_retry recovered recover
[[ "$FIXTURE_RC" -eq 0 && "$FIXTURE_CALLS" -eq 2 ]] \
  || { cat "$SANDBOX/recovered-retry.log"; fail "observed 502 then success must recover on exactly one retry (rc=$FIXTURE_RC calls=$FIXTURE_CALLS)"; }
grep -qx 'status=recovered' "$FIXTURE_ROOT/packet-loss-proxy/result.env" \
  || fail "recovered setup did not record status=recovered"
[[ ! -e "$FIXTURE_ROOT/failure.env" ]] \
  || fail "recovered setup left a failure manifest that could poison classification"
pass "observed Docker Hub 502 -> one in-shard retry -> success"

run_fixture_retry exhausted exhaust
EXHAUSTED_ROOT="$FIXTURE_ROOT"
[[ "$FIXTURE_RC" -eq 37 && "$FIXTURE_CALLS" -eq 2 ]] \
  || { cat "$SANDBOX/exhausted-retry.log"; fail "exhausted 502 must stop after two attempts (rc=$FIXTURE_RC calls=$FIXTURE_CALLS)"; }
run_classify exhausted-502 skipped skipped "$EXHAUSTED_ROOT"
[[ "$CLASSIFY_TOKEN" == "INFRA" \
    && "$CLASSIFY_REASON" == "docker_registry_http_5xx_exhausted" \
    && "$CLASSIFY_RC" -ne 0 ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "two captured 502 attempts with zero journeys must classify INFRA precisely; got $CLASSIFY_TOKEN/$CLASSIFY_REASON/exit$CLASSIFY_RC"; }
d="$SANDBOX/exhausted-token-plus-clean"; mkdir -p "$d"
cp "$CLASSIFY_FILE" "$d/shard-verdict-2.txt"
write_token "$d" 0 CLEAN; write_token "$d" 1 CLEAN
run_aggregate "$d" failure
[[ "$AGG_VERDICT" == "RE-RUN" && "$AGG_RC" -eq 0 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "typed external setup INFRA must not be laundered back to aggregate RED; got $AGG_VERDICT/exit$AGG_RC"; }
pass "exhausted captured 502 + zero journeys -> precise INFRA -> aggregate RE-RUN"

if [[ -z "${ISSUE2095_FOCUS:-}" || "${ISSUE2095_FOCUS:-}" == "mixed" ]]; then
  run_fixture_retry mixed-provider-product mixed
  MIXED_ROOT="$FIXTURE_ROOT"
  [[ "$FIXTURE_RC" -eq 43 && "$FIXTURE_CALLS" -eq 1 ]] \
    || { cat "$SANDBOX/mixed-provider-product-retry.log"; fail "earlier successful Docker Hub chatter must not bind a later product 502 (rc=$FIXTURE_RC calls=$FIXTURE_CALLS)"; }
  run_classify mixed-provider-product skipped skipped "$MIXED_ROOT"
  [[ "$CLASSIFY_TOKEN" == "RED" && "$CLASSIFY_REASON" == "pre_journey_setup_failure" ]] \
    || { printf '%s\n' "$CLASSIFY_OUT"; fail "mixed provider/product log must stay RED/pre_journey_setup_failure"; }
  pass "record-local binding: successful Docker Hub chatter cannot launder a later product 502"
fi

if [[ -z "${ISSUE2095_FOCUS:-}" || "${ISSUE2095_FOCUS:-}" == "network" ]]; then
  run_fixture_retry recovered-network network-recover
  [[ "$FIXTURE_RC" -eq 0 && "$FIXTURE_CALLS" -eq 2 ]] \
    || fail "captured Docker registry network failure must recover on exactly one retry"
  grep -qx 'status=recovered' "$FIXTURE_ROOT/packet-loss-proxy/result.env" \
    || fail "network recovery did not record status=recovered"

  run_fixture_retry exhausted-network network-exhaust
  NETWORK_ROOT="$FIXTURE_ROOT"
  [[ "$FIXTURE_RC" -eq 38 && "$FIXTURE_CALLS" -eq 2 ]] \
    || fail "exhausted network signature must stop after two attempts"
  run_classify exhausted-network skipped skipped "$NETWORK_ROOT"
  [[ "$CLASSIFY_TOKEN" == "INFRA" \
      && "$CLASSIFY_REASON" == "docker_registry_network_exhausted" \
      && "$CLASSIFY_RC" -ne 0 ]] \
    || { printf '%s\n' "$CLASSIFY_OUT"; fail "two captured network failures with zero journeys must classify INFRA precisely"; }
  d="$SANDBOX/exhausted-network-token-plus-clean"; mkdir -p "$d"
  cp "$CLASSIFY_FILE" "$d/shard-verdict-2.txt"
  write_token "$d" 0 CLEAN; write_token "$d" 1 CLEAN
  run_aggregate "$d" failure
  [[ "$AGG_VERDICT" == "RE-RUN" && "$AGG_RC" -eq 0 ]] \
    || fail "typed network setup INFRA must aggregate RE-RUN"
  grep -Fq "shard_verdict_reason == 'docker_registry_network_exhausted'" "$WORKFLOW" \
    || fail "failed-job retry gate must be bound to the precise network reason"
  pass "registry-network signature -> bounded recover/exhaust -> precise INFRA/aggregate/failed-job reason"
fi

if [[ -z "${ISSUE2095_FOCUS:-}" || "${ISSUE2095_FOCUS:-}" == "upload" ]]; then
  assert_setup_upload_contract
  pass "named setup-log artifact upload is always-run and precedes the failed-job trigger"
fi

if [[ -n "${ISSUE2095_FOCUS:-}" ]]; then
  echo "PASS: focused #2095 case ${ISSUE2095_FOCUS}"
  exit 0
fi

# Signature mutation: the same bounded failures without the provider/status
# signature are unknown. They get one attempt and stay fail-closed RED.
run_fixture_retry unknown-signature unknown
UNKNOWN_ROOT="$FIXTURE_ROOT"
[[ "$FIXTURE_RC" -eq 42 && "$FIXTURE_CALLS" -eq 1 ]] \
  || fail "unknown setup failure must not consume the external-infra retry"
run_classify unknown-signature skipped skipped "$UNKNOWN_ROOT"
[[ "$CLASSIFY_TOKEN" == "RED" && "$CLASSIFY_REASON" == "pre_journey_setup_failure" ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "signature mutation must stay RED/pre_journey_setup_failure"; }
pass "signature mutation is selective: unknown setup failure is not retried or typed INFRA"

# Zero-journey mutation: even valid setup logs cannot soften a shard once any
# journey-owned artifact exists.
JOURNEY_EVIDENCE="$SANDBOX/journey-evidence"
mkdir -p "$JOURNEY_EVIDENCE/class-attempts/real-red/attempt-1"
printf 'journey started\n' > "$JOURNEY_EVIDENCE/class-attempts/real-red/attempt-1/manifest.env"
run_classify has-journey-evidence skipped skipped "$EXHAUSTED_ROOT" "$JOURNEY_EVIDENCE"
[[ "$CLASSIFY_TOKEN" == "RED" && "$CLASSIFY_REASON" == "pre_journey_setup_failure" ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "zero-journey mutation must reject INFRA and remain RED"; }
pass "zero-journey condition is load-bearing: any journey evidence keeps the shard RED"

# Real-red control: a substantive suite failure dominates adjacent setup logs.
REAL_RED="$SANDBOX/real-red"
mkdir -p "$REAL_RED"
cat > "$REAL_RED/summary.md" <<'EOF'
JOURNEY_FAILED
Failed BOTH attempts
- `com.pocketshell.RealJourneyAssertionTest`
EOF
run_classify real-journey-red failure failure "$EXHAUSTED_ROOT" "$REAL_RED"
[[ "$CLASSIFY_TOKEN" == "RED" && "$CLASSIFY_REASON" == "journey_failure_both_attempts" ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "real journey assertion failure must remain RED beside setup signatures"; }
pass "real Failed-BOTH assertion remains RED; setup INFRA cannot launder product red"

echo
echo "== #1913 upstream-matrix aggregate backstop =="
d="$SANDBOX/upstream-failed-all-infra"; mkdir -p "$d"
write_token "$d" 0 INFRA; write_token "$d" 1 INFRA; write_token "$d" 2 INFRA
run_aggregate "$d" failure
[[ "$AGG_VERDICT" == "RED" && "$AGG_RC" -eq 1 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "failed upstream without any RED token must fail closed to aggregate RED"; }
grep -q 'upstream' <<<"$AGG_OUT" || fail "aggregate mismatch error must name the upstream result"
pass "failed upstream + no RED token -> aggregate RED mismatch (run 30659266867 shape)"

echo
echo "== #470/#771 environmental controls =="
run_classify never-booted failure failure
[[ "$CLASSIFY_TOKEN" == "INFRA" && "$CLASSIFY_REASON" == "emulator_never_booted" && "$CLASSIFY_RC" -ne 0 ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "attempted failure/no-summary must remain #771 INFRA"; }
d="$SANDBOX/genuine-infra"; mkdir -p "$d"
cp "$CLASSIFY_FILE" "$d/shard-verdict-2.txt"
write_token "$d" 0 CLEAN; write_token "$d" 1 CLEAN
run_aggregate "$d" success
[[ "$AGG_VERDICT" == "RE-RUN" && "$AGG_RC" -eq 0 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "successful upstream + #771 INFRA must remain green RE-RUN"; }
pass "attempted/no-summary #771 INFRA + successful upstream -> green RE-RUN"

run_classify cancelled cancelled cancelled
[[ "$CLASSIFY_TOKEN" == "INFRA" && "$CLASSIFY_REASON" == "attempt_cancelled" && "$CLASSIFY_RC" -ne 0 ]] \
  || { printf '%s\n' "$CLASSIFY_OUT"; fail "cancelled attempt must remain #470 INFRA"; }
pass "cancelled #470 attempt remains INFRA attempt_cancelled"

# Literal GitHub expression: shell expansion here would make the pin meaningless.
# shellcheck disable=SC2016
grep -Fq 'UPSTREAM_MATRIX_RESULT: ${{ needs.emulator-journey.result }}' "$WORKFLOW" \
  || fail "workflow aggregate step must pass needs.emulator-journey.result to the reducer"
grep -Fq 'scripts/test-ci-journey-pre-journey-classification.sh' "$WORKFLOW" \
  || fail "the #1913 regression must be wired into the Unit job"
pass "upstream matrix result and this cheap guard are wired in tests.yml"

echo
echo "== #2095 workflow retry/artifact wiring =="
for fixture in sshd agents packet-loss-proxy network-fault-proxy agents-old-cli agents-daemon; do
  count="$(grep -c "ci-journey-fixture-retry.sh $fixture -- docker compose" "$WORKFLOW" || true)"
  [[ "$count" -eq 1 ]] \
    || fail "fixture $fixture must have exactly one bounded pre-journey wrapper, found $count"
done
assert_setup_upload_contract
grep -Fq 'Request failed-job retry for external setup INFRA (issue #2095)' "$WORKFLOW" \
  || fail "exhausted external setup INFRA must fail only its shard for failed-job retry"
grep -Fq "shard_verdict_reason == 'docker_registry_http_5xx_exhausted'" "$WORKFLOW" \
  || fail "failed-job retry gate must be bound to the precise HTTP-5xx reason"
pass "all pull/build fixtures are wrapped once; setup logs + precise failed-shard retry are wired"

echo
echo "PASS: #1913 real classifier + aggregate phase-aware verdict guard"
