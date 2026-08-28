#!/usr/bin/env bash
# Issue #2317: an in-emulator tmux list-sessions stall is typed as CI infra
# only when an attempt-local device log and completed harness manifest prove it.
# A generic timeout, a host-side attempt log, incomplete attempt evidence, or a
# passing attempt must not satisfy the classifier. The bottom half of this
# guard also extracts and executes the two changed workflow step bodies, so the
# CI wiring is tested rather than merely grepped.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLASSIFIER="$SCRIPT_DIR/ci-journey-enumeration-stall.sh"
SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

fail() {
  echo "TEST FAIL: $*" >&2
  exit 1
}

write_attempt_variant() {
  local root="$1" exit_code="$2" classification="$3" snapshot_status="$4"
  local harness_verdict_status="$5" logcat="$6"
  mkdir -p "$root"
  cat > "$root/manifest.txt" <<EOF
format_version=1
module=app
class=com.pocketshell.app.proof.Enumeration
attempt=1
primary_exit_code=$exit_code
primary_classification=$classification
raw_junit_status=absent_by_outer_timeout
raw_junit_count=0
snapshot_status=$snapshot_status
harness_verdict_status=$harness_verdict_status
device_logcat=ok
status=failed
EOF
  printf '%s\n' "$logcat" > "$root/device-logcat.txt"
}

write_attempt() {
  write_attempt_variant "$1" "$2" "$3" complete complete "$4"
}

SUMMARY_UNCLASSIFIED_LINE='No exact attempt-local enumeration proof was accepted, so the cause is intentionally'

summary_is_consistent() {
  local summary="$1"
  grep -Fq 'Emulator CI infrastructure evidence — JOURNEY_ENUMERATION_STALL' "$summary" \
    || return 1
  grep -Fq 'tmux_list_sessions_enumeration_stall' "$summary" \
    || return 1
  ! grep -Fq "$SUMMARY_UNCLASSIFIED_LINE" "$summary"
}

assert_classifier_none() {
  local label="$1" root="$2" output
  output="$(bash "$CLASSIFIER" "$root")"
  grep -Fxq 'enumeration_stall_verdict=NONE' <<<"$output" \
    || fail "$label was misclassified as enumeration infra: $output"
}

STALL_MARKER='08-24 12:00:00.000 W/HostTmuxSessions: JOURNEY_ENUMERATION_STALL: tmux list-sessions caller=host_tmux_sessions_list timeoutMs=3000'

positive="$SANDBOX/positive/class-attempts/app/proof/attempt-1"
write_attempt "$positive" 124 outer_timeout \
  "$STALL_MARKER"

[[ -f "$CLASSIFIER" ]] || fail "classifier is missing (red proof on the current base)"
positive_out="$(bash "$CLASSIFIER" "$SANDBOX/positive")"
grep -Fxq 'enumeration_stall_verdict=INFRA' <<<"$positive_out" \
  || fail "proven device marker was not typed INFRA: $positive_out"
grep -Fxq 'enumeration_stall_reason=tmux_list_sessions_enumeration_stall' <<<"$positive_out" \
  || fail "typed reason is missing: $positive_out"
grep -Fxq 'enumeration_stall_attempts=1' <<<"$positive_out" \
  || fail "exactly one attempt was not reported: $positive_out"
grep -Fq 'enumeration_stall_evidence=' <<<"$positive_out" \
  || fail "attempt-local evidence path is missing: $positive_out"

generic="$SANDBOX/generic/class-attempts/app/proof/attempt-1"
write_attempt "$generic" 124 outer_timeout 'generic JOURNEY_STEP_TIMEOUT only'
generic_out="$(bash "$CLASSIFIER" "$SANDBOX/generic")"
grep -Fxq 'enumeration_stall_verdict=NONE' <<<"$generic_out" \
  || fail "generic timeout was misclassified as enumeration infra: $generic_out"

passing="$SANDBOX/passing/class-attempts/app/proof/attempt-1"
write_attempt "$passing" 0 pass \
  'JOURNEY_ENUMERATION_STALL: tmux list-sessions caller=host_tmux_sessions_list timeoutMs=3000'
passing_out="$(bash "$CLASSIFIER" "$SANDBOX/passing")"
grep -Fxq 'enumeration_stall_verdict=NONE' <<<"$passing_out" \
  || fail "a passing attempt was misclassified as enumeration infra: $passing_out"

# The marker is valid only when it is in the Android device log for the same
# attempt, and only when the attempt's manifest is complete. Keep each control
# as a real artifact tree so deleting one classifier gate makes this guard fail.
host_marker="$SANDBOX/host-marker/class-attempts/app/proof/attempt-1"
write_attempt "$host_marker" 124 outer_timeout 'generic JOURNEY_STEP_TIMEOUT only'
printf '%s\n' "$STALL_MARKER" > "$host_marker/attempt.log"
assert_classifier_none "host-side attempt.log marker" "$SANDBOX/host-marker"

snapshot_incomplete="$SANDBOX/snapshot-incomplete/class-attempts/app/proof/attempt-1"
write_attempt_variant "$snapshot_incomplete" 124 outer_timeout missing complete "$STALL_MARKER"
assert_classifier_none "incomplete snapshot" "$SANDBOX/snapshot-incomplete"

harness_incomplete="$SANDBOX/harness-incomplete/class-attempts/app/proof/attempt-1"
write_attempt_variant "$harness_incomplete" 124 outer_timeout complete missing "$STALL_MARKER"
assert_classifier_none "incomplete harness verdict" "$SANDBOX/harness-incomplete"

malformed_marker="$SANDBOX/malformed-marker/class-attempts/app/proof/attempt-1"
write_attempt "$malformed_marker" 124 outer_timeout \
  'JOURNEY_ENUMERATION_STALL: tmux list-sessions'
assert_classifier_none "malformed device marker" "$SANDBOX/malformed-marker"

ambiguous_marker="$SANDBOX/ambiguous-marker/class-attempts/app/proof/attempt-1"
write_attempt "$ambiguous_marker" 124 outer_timeout \
  'JOURNEY_ENUMERATION_STALL: tmux list-windows caller=host_tmux_sessions_list timeoutMs=3000'
assert_classifier_none "ambiguous device marker" "$SANDBOX/ambiguous-marker"

mutate_once() {
  local source="$1" destination="$2" old_text="$3" new_text="$4"
  MUTATION_OLD="$old_text" MUTATION_NEW="$new_text" \
    python3 - "$source" "$destination" <<'PY'
import os
import sys

source, destination = sys.argv[1:]
text = open(source, encoding="utf-8").read()
old = os.environ["MUTATION_OLD"]
new = os.environ["MUTATION_NEW"]
count = text.count(old)
if count != 1:
    raise SystemExit(f"mutation anchor occurred {count} times, expected exactly once")
mutated = text.replace(old, new)
if mutated == text:
    raise SystemExit("mutation was a no-op")
open(destination, "w", encoding="utf-8").write(mutated)
PY
}

# These four live mutants prove the negative controls are selective. Each is a
# plausible classifier regression, and its corresponding control must become a
# false INFRA result if that guard is removed.
host_source_mutant="$SANDBOX/classifier-mutant-host-source.sh"
mutate_once "$CLASSIFIER" "$host_source_mutant" \
  "grep -Fq 'JOURNEY_ENUMERATION_STALL: tmux list-sessions ' \"\$logcat\" || continue" \
  "grep -Fq 'JOURNEY_ENUMERATION_STALL: tmux list-sessions ' \"\$attempt_dir/attempt.log\" || continue"
host_mutant_out="$(bash "$host_source_mutant" "$SANDBOX/host-marker")"
grep -Fxq 'enumeration_stall_verdict=INFRA' <<<"$host_mutant_out" \
  || fail "host-marker mutation did not make the host-side control live: $host_mutant_out"

snapshot_gate_mutant="$SANDBOX/classifier-mutant-snapshot.sh"
mutate_once "$CLASSIFIER" "$snapshot_gate_mutant" \
  "grep -Fxq 'snapshot_status=complete' \"\$manifest\" || continue" \
  ": # mutation: snapshot completeness gate removed"
snapshot_mutant_out="$(bash "$snapshot_gate_mutant" "$SANDBOX/snapshot-incomplete")"
grep -Fxq 'enumeration_stall_verdict=INFRA' <<<"$snapshot_mutant_out" \
  || fail "snapshot-gate mutation did not make the incomplete-snapshot control live: $snapshot_mutant_out"

harness_gate_mutant="$SANDBOX/classifier-mutant-harness.sh"
mutate_once "$CLASSIFIER" "$harness_gate_mutant" \
  "grep -Fxq 'harness_verdict_status=complete' \"\$manifest\" || continue" \
  ": # mutation: harness-verdict completeness gate removed"
harness_mutant_out="$(bash "$harness_gate_mutant" "$SANDBOX/harness-incomplete")"
grep -Fxq 'enumeration_stall_verdict=INFRA' <<<"$harness_mutant_out" \
  || fail "harness-gate mutation did not make the incomplete-harness control live: $harness_mutant_out"

marker_strictness_mutant="$SANDBOX/classifier-mutant-marker.sh"
mutate_once "$CLASSIFIER" "$marker_strictness_mutant" \
  "grep -Fq 'JOURNEY_ENUMERATION_STALL: tmux list-sessions ' \"\$logcat\" || continue" \
  "grep -Fq 'JOURNEY_ENUMERATION_STALL' \"\$logcat\" || continue"
marker_mutant_out="$(bash "$marker_strictness_mutant" "$SANDBOX/malformed-marker")"
grep -Fxq 'enumeration_stall_verdict=INFRA' <<<"$marker_mutant_out" \
  || fail "marker-strictness mutation did not make the malformed-marker control live: $marker_mutant_out"

echo "PASS: exact attempt-local marker is typed narrowly; host, incomplete, malformed, ambiguous, generic, and passing controls stay NONE"

# Drive the real summary writer as a cheap consumer proof. This catches the
# observationally-dead shape where the classifier exists but finish_ci_journey_suite
# never publishes its verdict into the artifact consumed by GitHub Actions.
summary_driver="$SANDBOX/summary-driver.sh"
cat > "$summary_driver" <<'DRIVER'
#!/usr/bin/env bash
set -u
root="$1"
step_timeout="$2"
repo_root="$3"
source "$repo_root/scripts/ci-journey-summary-functions.sh"
REPO_ROOT="$repo_root"
ARTIFACT_DIR="$root/artifacts/ci-journey"
SUMMARY="$ARTIFACT_DIR/summary.md"
mkdir -p "$ARTIFACT_DIR"
CORE_TERMINAL_PROOFS=("PROOF_STATUS|PROOF_CLASS|Core-terminal summary")
PROOF_STATUS=PASS
PROOF_CLASS=com.pocketshell.app.proof.Summary
EFFECTIVE_JOURNEY_CLASSES=("com.pocketshell.app.proof.Summary")
JOURNEY_CI_SHARD_INDEX=0
JOURNEY_CI_SHARD_TOTAL=1
JOURNEY_STEP_BUDGET_SECS=4200
JOURNEY_WARM_BUILD_STATUS=ok
JOURNEY_WARM_BUILD_ELAPSED=0
FAILED_CLASSES=()
RECOVERED_CLASSES=()
PASSED_FIRST_TRY=()
FIXTURE_WEDGED_CLASSES=()
BUDGET_TIMEOUT_CLASSES=("com.pocketshell.app.proof.Summary")
BUILD_PHASE_TIMEOUT_ATTEMPTS=()
BUILD_PHASE_FAILURE_ATTEMPTS=()
STEP_TIMEOUT_HIT="$step_timeout"
SUITE_START="$SECONDS"
finish_ci_journey_suite
DRIVER
chmod +x "$summary_driver"

positive_summary_root="$SANDBOX/summary-positive"
positive_summary_attempt="$positive_summary_root/artifacts/ci-journey/class-attempts/app/proof/attempt-1"
write_attempt "$positive_summary_attempt" 124 outer_timeout \
  'JOURNEY_ENUMERATION_STALL: tmux list-sessions caller=host_tmux_sessions_list timeoutMs=3000'
set +e
positive_summary_out="$(bash "$summary_driver" "$positive_summary_root" 1 "$SCRIPT_DIR/.." 2>&1)"
positive_summary_rc=$?
set -e
[[ "$positive_summary_rc" -eq 1 ]] || fail "positive summary did not stay non-zero: rc=$positive_summary_rc"
grep -Fq 'Emulator CI infrastructure evidence — JOURNEY_ENUMERATION_STALL' "$positive_summary_root/artifacts/ci-journey/summary.md" \
  || fail "positive proof was not surfaced in the suite summary"
grep -Fq 'tmux_list_sessions_enumeration_stall' "$positive_summary_root/artifacts/ci-journey/summary.md" \
  || fail "typed enumeration reason was not surfaced in the suite summary"
summary_is_consistent "$positive_summary_root/artifacts/ci-journey/summary.md" \
  || fail "typed enumeration evidence was paired with the contradictory unclassified-timeout sentence"

generic_summary_root="$SANDBOX/summary-generic"
generic_summary_attempt="$generic_summary_root/artifacts/ci-journey/class-attempts/app/proof/attempt-1"
write_attempt "$generic_summary_attempt" 124 outer_timeout 'generic JOURNEY_STEP_TIMEOUT only'
set +e
generic_summary_out="$(bash "$summary_driver" "$generic_summary_root" 1 "$SCRIPT_DIR/.." 2>&1)"
generic_summary_rc=$?
set -e
[[ "$generic_summary_rc" -eq 1 ]] || fail "generic timeout summary did not stay non-zero: rc=$generic_summary_rc"
if grep -Fq 'Emulator CI infrastructure evidence — JOURNEY_ENUMERATION_STALL' "$generic_summary_root/artifacts/ci-journey/summary.md"; then
  fail "generic timeout was surfaced as enumeration infra in the summary"
fi
grep -Fq "$SUMMARY_UNCLASSIFIED_LINE" "$generic_summary_root/artifacts/ci-journey/summary.md" \
  || fail "generic timeout lost the existing unclassified-timeout sentence"

passing_summary_root="$SANDBOX/summary-passing"
passing_summary_attempt="$passing_summary_root/artifacts/ci-journey/class-attempts/app/proof/attempt-1"
write_attempt "$passing_summary_attempt" 0 pass \
  'JOURNEY_ENUMERATION_STALL: tmux list-sessions caller=host_tmux_sessions_list timeoutMs=3000'
set +e
passing_summary_out="$(bash "$summary_driver" "$passing_summary_root" 0 "$SCRIPT_DIR/.." 2>&1)"
passing_summary_rc=$?
set -e
[[ "$passing_summary_rc" -eq 0 ]] || fail "passing summary changed exit status: rc=$passing_summary_rc"
if grep -Fq 'Emulator CI infrastructure evidence — JOURNEY_ENUMERATION_STALL' "$passing_summary_root/artifacts/ci-journey/summary.md"; then
  fail "passing attempt was surfaced as enumeration infra in the summary"
fi

# Live mutation proof for the summary contract: making the negative timeout line
# unconditional must make the same typed-positive assertion fail. The mutant is
# written to a private source tree, never into the repository under test.
summary_mutant_repo="$SANDBOX/summary-mutant-repo"
mkdir -p "$summary_mutant_repo/scripts"
cp "$CLASSIFIER" "$summary_mutant_repo/scripts/ci-journey-enumeration-stall.sh"
mutate_once \
  "$SCRIPT_DIR/ci-journey-summary-functions.sh" \
  "$summary_mutant_repo/scripts/ci-journey-summary-functions.sh" \
  'if [[ "$enumeration_stall_verdict" != "INFRA" ]]; then' \
  'if true; then # mutation: unclassified timeout line unconditional'
grep -Fq 'if true; then # mutation: unclassified timeout line unconditional' \
  "$summary_mutant_repo/scripts/ci-journey-summary-functions.sh" \
  || fail "summary-condition mutant was not live in its private source tree"

mutant_summary_root="$SANDBOX/summary-mutant"
mutant_summary_attempt="$mutant_summary_root/artifacts/ci-journey/class-attempts/app/proof/attempt-1"
write_attempt "$mutant_summary_attempt" 124 outer_timeout \
  'JOURNEY_ENUMERATION_STALL: tmux list-sessions caller=host_tmux_sessions_list timeoutMs=3000'
set +e
mutant_summary_out="$(bash "$summary_driver" "$mutant_summary_root" 1 "$summary_mutant_repo" 2>&1)"
mutant_summary_rc=$?
set -e
[[ "$mutant_summary_rc" -eq 1 ]] || fail "summary-condition mutant changed the RED exit contract: rc=$mutant_summary_rc"
grep -Fq "$SUMMARY_UNCLASSIFIED_LINE" "$mutant_summary_root/artifacts/ci-journey/summary.md" \
  || fail "summary-condition mutant did not emit the contradictory line"
set +e
summary_is_consistent "$mutant_summary_root/artifacts/ci-journey/summary.md"
mutant_summary_contract_rc=$?
set -e
[[ "$mutant_summary_contract_rc" -ne 0 ]] \
  || fail "summary-condition mutant did not redden the typed-positive consistency assertion"
echo "PASS: removing the negative-line condition reddens the typed enumeration summary proof"

extract_step_body() {
  local workflow="$1" step_name="$2" output_path="$3" expressions="$4"
  STEP_NAME="$step_name" STEP_EXPRESSIONS="$expressions" \
    python3 - "$workflow" "$output_path" <<'PY'
import json
import os
import re
import sys

workflow, output_path = sys.argv[1:]
mapping = json.loads(os.environ["STEP_EXPRESSIONS"])
step_name = os.environ["STEP_NAME"]
lines = open(workflow, encoding="utf-8").read().splitlines()

step_re = re.compile(r"^(\s*)- name: " + re.escape(step_name) + r"$")
start = indent = None
for index, line in enumerate(lines):
    match = step_re.match(line)
    if match:
        start, indent = index, len(match.group(1))
        break
if start is None:
    raise SystemExit(f"could not find workflow step {step_name!r}")

run_index = None
for index in range(start + 1, len(lines)):
    line = lines[index]
    if line.strip() and len(line) - len(line.lstrip()) <= indent:
        break
    if re.match(r"^\s*run: \|\s*$", line):
        run_index = index
        break
if run_index is None:
    raise SystemExit(f"workflow step {step_name!r} has no run block")

run_indent = len(lines[run_index]) - len(lines[run_index].lstrip())
body = []
for line in lines[run_index + 1:]:
    if not line.strip():
        body.append("")
        continue
    if len(line) - len(line.lstrip()) <= run_indent:
        break
    body.append(line)
if not any(line.strip() for line in body):
    raise SystemExit(f"workflow step {step_name!r} has an empty run block")

padding = min(len(line) - len(line.lstrip()) for line in body if line.strip())
text = "\n".join(line[padding:] if line.strip() else "" for line in body)
unknown = []

def substitute(match):
    expression = match.group(1).strip()
    if expression not in mapping:
        unknown.append(expression)
        return ""
    return mapping[expression]

text = re.sub(r"\$\{\{(.*?)\}\}", substitute, text)
if unknown:
    raise SystemExit(
        "unmapped workflow expression(s): " + ", ".join(sorted(set(unknown)))
    )

open(output_path, "w", encoding="utf-8").write(text + "\n")
PY
}

CLASSIFY_EXPRESSIONS='{
  "steps.journey.outcome": "${CLASSIFY_steps_journey_outcome:-}",
  "steps.journey_retry.outcome": "${CLASSIFY_steps_journey_retry_outcome:-}",
  "steps.journey.conclusion": "${CLASSIFY_steps_journey_conclusion:-}",
  "steps.journey_retry.conclusion": "${CLASSIFY_steps_journey_retry_conclusion:-}",
  "steps.journey_summary.outputs.first_timeout": "${CLASSIFY_steps_journey_summary_outputs_first_timeout:-}",
  "steps.journey_summary.outputs.first_failure": "${CLASSIFY_steps_journey_summary_outputs_first_failure:-}",
  "steps.journey_summary.outputs.first_enumeration_stall": "${CLASSIFY_steps_journey_summary_outputs_first_enumeration_stall:-}",
  "steps.journey_retry_budget.outputs.retry_allowed": "${CLASSIFY_steps_journey_retry_budget_outputs_retry_allowed:-}",
  "steps.journey_retry_budget.outputs.retry_reason": "${CLASSIFY_steps_journey_retry_budget_outputs_retry_reason:-}",
  "steps.journey_retry_budget.outputs.retry_remaining_ms": "${CLASSIFY_steps_journey_retry_budget_outputs_retry_remaining_ms:-}",
  "steps.journey_retry_budget.outputs.retry_required_ms": "${CLASSIFY_steps_journey_retry_budget_outputs_retry_required_ms:-}",
  "steps.journey_retry_budget.outputs.retry_cost_model": "${CLASSIFY_steps_journey_retry_budget_outputs_retry_cost_model:-}",
  "steps.journey_retry_budget.outputs.retry_shortfall_ms": "${CLASSIFY_steps_journey_retry_budget_outputs_retry_shortfall_ms:-}",
  "steps.journey_retry_budget.outputs.retry_warm_build_deducted_ms": "${CLASSIFY_steps_journey_retry_budget_outputs_retry_warm_build_deducted_ms:-}",
  "steps.journey_retry_budget.outputs.retry_denial_class": "${CLASSIFY_steps_journey_retry_budget_outputs_retry_denial_class:-unknown}"
}'

make_workflow_fixture() {
  local root="$1"
  rm -rf "$root"
  mkdir -p "$root/artifacts/ci-journey"
  cat > "$root/artifacts/ci-journey/summary.md" <<'EOF'
# Per-push CI journey suite — summary
Suite step time budget exhausted — JOURNEY_STEP_TIMEOUT (issue #835 hard RED — exact cause required):
- `com.pocketshell.app.proof.Enumeration`
EOF
  write_attempt \
    "$root/artifacts/ci-journey/class-attempts/app/proof/attempt-1" \
    124 outer_timeout "$STALL_MARKER"
}

make_genuine_failure_fixture() {
  local root="$1"
  rm -rf "$root"
  mkdir -p "$root/artifacts/ci-journey"
  cat > "$root/artifacts/ci-journey/summary.md" <<'EOF'
# Per-push CI journey suite — summary
Failed BOTH attempts (`JOURNEY_FAILED` — job red):
- `com.pocketshell.app.proof.Enumeration`
EOF
}

make_prejourney_setup_fixture() {
  local root="$1"
  rm -rf "$root"
  mkdir -p "$root/artifacts/ci-journey-setup"
  # A captured unknown setup failure is deliberately NOT an external-registry
  # INFRA signature. The real classifier must keep first=skipped RED.
  cat > "$root/artifacts/ci-journey-setup/failure.env" <<'EOF'
status=unknown_failure
reason=unknown
fixture=agents
attempts=1
EOF
}

make_honest_infra_fixture() {
  local root="$1"
  rm -rf "$root"
  mkdir -p "$root"
  # No summary means the journey never reached a test verdict. This is the
  # real EMULATOR INFRA UNAVAILABLE path, not a product failure.
}

prepare_workflow_scripts() {
  local root="$1"
  mkdir -p "$root/scripts"
  cp \
    "$SCRIPT_DIR/ci-journey-enumeration-stall.sh" \
    "$SCRIPT_DIR/ci-journey-suite-elapsed.sh" \
    "$SCRIPT_DIR/ci-journey-warm-build-elapsed.sh" \
    "$SCRIPT_DIR/ci-journey-build-phase-timeout.sh" \
    "$SCRIPT_DIR/ci-journey-build-phase-failure.sh" \
    "$SCRIPT_DIR/ci-journey-fixture-retry.sh" \
    "$SCRIPT_DIR/ci-journey-shard-signature-verdict.sh" \
    "$SCRIPT_DIR/ci-journey-infra-signature.sh" \
    "$SCRIPT_DIR/ci-journey-infra-signature.py" \
    "$SCRIPT_DIR/ci-journey-write-shard-verdict.sh" \
    "$SCRIPT_DIR/ci-journey-retry-denial-notice.sh" \
    "$SCRIPT_DIR/ci-journey-build-attribution-notice.sh" \
    "$SCRIPT_DIR/ci-journey-genuine-journey-failure.sh" \
    "$root/scripts/"
  chmod +x "$root/scripts/"*.sh
}

SUMMARY_FIRST_TIMEOUT=""
SUMMARY_FIRST_FAILURE=""
SUMMARY_FIRST_ENUMERATION_STALL=""
run_workflow_summary_step() {
  local workflow="$1" root="$2" body="$2/inspect-first-summary.sh"
  prepare_workflow_scripts "$root"
  extract_step_body "$workflow" "Inspect first journey summary" "$body" '{}' \
    || fail "could not extract the real Inspect first journey summary step"
  : > "$root/summary-output.txt"
  (
    cd "$root"
    GITHUB_OUTPUT="$root/summary-output.txt" \
      bash --noprofile --norc -eo pipefail "$body"
  ) > "$root/summary-step.log" 2>&1 \
    || { cat "$root/summary-step.log"; fail "the real summary step exited non-zero"; }
  SUMMARY_FIRST_TIMEOUT="$(sed -n 's/^first_timeout=//p' "$root/summary-output.txt" | tail -n 1)"
  SUMMARY_FIRST_FAILURE="$(sed -n 's/^first_failure=//p' "$root/summary-output.txt" | tail -n 1)"
  SUMMARY_FIRST_ENUMERATION_STALL="$(sed -n 's/^first_enumeration_stall=//p' "$root/summary-output.txt" | tail -n 1)"
}

CLASSIFY_TOKEN=""
CLASSIFY_REASON=""
CLASSIFY_RC=0
CLASSIFY_OUT=""
run_workflow_classify_step() {
  local workflow="$1" root="$2" body="$2/classify.sh"
  local first="${3:-failure}" retry="${4:-failure}"
  local first_conclusion="${5:-$first}" retry_conclusion="${6:-$retry}"
  local retry_allowed="${7:-true}"
  extract_step_body \
    "$workflow" \
    "Classify emulator-journey result (infra-abort vs test-failure)" \
    "$body" "$CLASSIFY_EXPRESSIONS" \
    || fail "could not extract the real Classify emulator-journey result step"
  : > "$root/classify-output.txt"
  rm -f "$root/shard-verdict.txt"
  set +e
  CLASSIFY_OUT="$(
    cd "$root" && env \
      CLASSIFY_steps_journey_outcome="$first" \
      CLASSIFY_steps_journey_retry_outcome="$retry" \
      CLASSIFY_steps_journey_conclusion="$first_conclusion" \
      CLASSIFY_steps_journey_retry_conclusion="$retry_conclusion" \
      CLASSIFY_steps_journey_summary_outputs_first_timeout="$SUMMARY_FIRST_TIMEOUT" \
      CLASSIFY_steps_journey_summary_outputs_first_failure="$SUMMARY_FIRST_FAILURE" \
      CLASSIFY_steps_journey_summary_outputs_first_enumeration_stall="$SUMMARY_FIRST_ENUMERATION_STALL" \
      CLASSIFY_steps_journey_retry_budget_outputs_retry_allowed="$retry_allowed" \
      CLASSIFY_steps_journey_retry_budget_outputs_retry_reason=fixture \
      CLASSIFY_steps_journey_retry_budget_outputs_retry_remaining_ms=2976238 \
      CLASSIFY_steps_journey_retry_budget_outputs_retry_required_ms=3240868 \
      CLASSIFY_steps_journey_retry_budget_outputs_retry_cost_model=measured_first_attempt \
      CLASSIFY_steps_journey_retry_budget_outputs_retry_shortfall_ms=0 \
      CLASSIFY_steps_journey_retry_budget_outputs_retry_warm_build_deducted_ms=0 \
      SHARD_VERDICT_FILE="$root/shard-verdict.txt" \
      POCKETSHELL_JOURNEY_CI_SHARD_INDEX=0 \
      GITHUB_RUN_ID=2317 GITHUB_RUN_ATTEMPT=1 \
      GITHUB_OUTPUT="$root/classify-output.txt" \
      bash --noprofile --norc -eo pipefail "$body" 2>&1
  )"
  CLASSIFY_RC=$?
  set -e
  CLASSIFY_TOKEN="$(sed -n 's/^shard_verdict=//p' "$root/classify-output.txt" | tail -n 1)"
  CLASSIFY_REASON="$(sed -n 's/^shard_verdict_reason=//p' "$root/classify-output.txt" | tail -n 1)"
}

assert_workflow_verdict() {
  local label="$1" root="$2" want_token="$3" want_reason="$4" want_rc="$5"
  [[ "$CLASSIFY_TOKEN" == "$want_token" ]] \
    || { printf '%s\n' "$CLASSIFY_OUT"; fail "$label wrote token '$CLASSIFY_TOKEN', expected '$want_token'"; }
  [[ "$CLASSIFY_REASON" == "$want_reason" ]] \
    || { printf '%s\n' "$CLASSIFY_OUT"; fail "$label wrote reason '$CLASSIFY_REASON', expected '$want_reason'"; }
  [[ "$CLASSIFY_RC" -eq "$want_rc" ]] \
    || { printf '%s\n' "$CLASSIFY_OUT"; fail "$label exited $CLASSIFY_RC, expected $want_rc"; }
  [[ "$(head -n 1 "$root/shard-verdict.txt")" == "$want_token" ]] \
    || fail "$label token file disagrees with GITHUB_OUTPUT"
  grep -qx "verdict_reason=$want_reason" "$root/shard-verdict.txt" \
    || fail "$label token file lost the reason"
}

echo
echo "== #2317 the REAL workflow steps are executed =="

fixed_workflow="$SANDBOX/workflow-fixed"
make_workflow_fixture "$fixed_workflow"
run_workflow_summary_step "$SCRIPT_DIR/../.github/workflows/tests.yml" "$fixed_workflow"
[[ "$SUMMARY_FIRST_TIMEOUT" == true ]] \
  || fail "the real summary step did not classify the fixture as a timeout"
[[ "$SUMMARY_FIRST_FAILURE" == false ]] \
  || fail "the real summary step invented a genuine first failure"
[[ "$SUMMARY_FIRST_ENUMERATION_STALL" == true ]] \
  || fail "the real summary step did not export first_enumeration_stall=true"
run_workflow_classify_step "$SCRIPT_DIR/../.github/workflows/tests.yml" "$fixed_workflow"
assert_workflow_verdict \
  "fixed workflow" "$fixed_workflow" INFRA \
  tmux_list_sessions_enumeration_stall 1

# Mutation 1: remove the changed GITHUB_OUTPUT assignment from the real summary
# step. The extracted classifier must then see an empty first_enumeration_stall
# input and retain the hard RED timeout branch.
workflow_without_output="$SANDBOX/workflow-without-enumeration-output.yml"
mutate_once \
  "$SCRIPT_DIR/../.github/workflows/tests.yml" "$workflow_without_output" \
  'echo "first_enumeration_stall=$first_enumeration_stall" >> "$GITHUB_OUTPUT"' \
  ': # mutation: first_enumeration_stall output removed'
without_output_workflow="$SANDBOX/workflow-without-output"
make_workflow_fixture "$without_output_workflow"
run_workflow_summary_step "$workflow_without_output" "$without_output_workflow"
[[ -z "$SUMMARY_FIRST_ENUMERATION_STALL" ]] \
  || fail "output-removal mutant was not live: summary still exported '$SUMMARY_FIRST_ENUMERATION_STALL'"
run_workflow_classify_step "$workflow_without_output" "$without_output_workflow"
assert_workflow_verdict \
  "workflow without summary output" "$without_output_workflow" RED \
  suite_budget_timeout 1

# Mutation 2: disable the new branch in the real classify step. The same
# attempt-local fixture and the real timeout branch must become RED; otherwise
# the self-test would not constrain the workflow branch at all.
workflow_without_branch="$SANDBOX/workflow-without-enumeration-branch.yml"
mutate_once \
  "$SCRIPT_DIR/../.github/workflows/tests.yml" "$workflow_without_branch" \
  'if [[ "${first_enumeration_stall:-false}" == "true" && "${first_timeout:-false}" == "true" ]]; then' \
  'if false; then # mutation: enumeration INFRA branch removed'
without_branch_workflow="$SANDBOX/workflow-without-branch"
make_workflow_fixture "$without_branch_workflow"
run_workflow_summary_step "$workflow_without_branch" "$without_branch_workflow"
[[ "$SUMMARY_FIRST_ENUMERATION_STALL" == true ]] \
  || fail "branch-removal mutant changed the summary proof instead of the classifier"
run_workflow_classify_step "$workflow_without_branch" "$without_branch_workflow"
assert_workflow_verdict \
  "workflow without classifier branch" "$without_branch_workflow" RED \
  suite_budget_timeout 1

# Exercise the genuine journey-failure branch with the actual first-summary and
# classifier bodies. Its exact reason is load-bearing: the later summary may be
# stale or overwritten by a retry, so falling through to journey_failure_both_attempts
# is not equivalent to honoring first_failure.
genuine_workflow="$SANDBOX/workflow-genuine-failure"
make_genuine_failure_fixture "$genuine_workflow"
run_workflow_summary_step "$SCRIPT_DIR/../.github/workflows/tests.yml" "$genuine_workflow"
[[ "$SUMMARY_FIRST_FAILURE" == true && "$SUMMARY_FIRST_TIMEOUT" == false ]] \
  || fail "the real summary step did not identify JOURNEY_FAILED as first_failure"
[[ "$SUMMARY_FIRST_ENUMERATION_STALL" == false ]] \
  || fail "a genuine JOURNEY_FAILED summary was misclassified as enumeration stall"
run_workflow_classify_step \
  "$SCRIPT_DIR/../.github/workflows/tests.yml" "$genuine_workflow" \
  failure failure failure failure true
assert_workflow_verdict \
  "genuine journey failure" "$genuine_workflow" RED \
  first_attempt_journey_failure 1

# Mutation 3: remove the existing first_failure branch. The same extracted
# workflow must fall through to a different RED reason, proving the exact branch
# and its first-attempt precedence are constrained by this guard.
workflow_without_failure_branch="$SANDBOX/workflow-without-genuine-failure-branch.yml"
mutate_once \
  "$SCRIPT_DIR/../.github/workflows/tests.yml" "$workflow_without_failure_branch" \
  'if [[ "${first_failure:-false}" == "true" ]]; then' \
  'if false; then # mutation: genuine-failure branch removed'
genuine_mutant_workflow="$SANDBOX/workflow-without-genuine-failure-branch"
make_genuine_failure_fixture "$genuine_mutant_workflow"
run_workflow_summary_step "$workflow_without_failure_branch" "$genuine_mutant_workflow"
run_workflow_classify_step \
  "$workflow_without_failure_branch" "$genuine_mutant_workflow" \
  failure failure failure failure true
assert_workflow_verdict \
  "workflow without genuine-failure branch" "$genuine_mutant_workflow" RED \
  journey_failure_both_attempts 1

# Exercise the pre-journey/setup branch with first=skipped. The captured
# unknown setup failure is intentionally not eligible for the external-registry
# INFRA exception, so the real workflow must write a RED setup reason.
prejourney_workflow="$SANDBOX/workflow-prejourney-setup"
make_prejourney_setup_fixture "$prejourney_workflow"
run_workflow_summary_step "$SCRIPT_DIR/../.github/workflows/tests.yml" "$prejourney_workflow"
[[ "$SUMMARY_FIRST_FAILURE" == false && "$SUMMARY_FIRST_TIMEOUT" == false ]] \
  || fail "the pre-journey fixture unexpectedly gained a suite summary classification"
run_workflow_classify_step \
  "$SCRIPT_DIR/../.github/workflows/tests.yml" "$prejourney_workflow" \
  skipped skipped skipped skipped true
assert_workflow_verdict \
  "pre-journey setup failure" "$prejourney_workflow" RED \
  pre_journey_setup_failure 1
grep -Fq 'PRE-JOURNEY SETUP FAILURE' <<<"$CLASSIFY_OUT" \
  || fail "pre-journey setup failure did not retain its hard-error diagnostic"

# Mutation 4: remove the existing first=skipped branch. The same setup fixture
# must not be laundered into the honest no-summary INFRA branch.
workflow_without_setup_branch="$SANDBOX/workflow-without-prejourney-branch.yml"
mutate_once \
  "$SCRIPT_DIR/../.github/workflows/tests.yml" "$workflow_without_setup_branch" \
  'if [[ "$first" == "skipped" ]]; then' \
  'if false; then # mutation: pre-journey/setup branch removed'
prejourney_mutant_workflow="$SANDBOX/workflow-without-prejourney-branch"
make_prejourney_setup_fixture "$prejourney_mutant_workflow"
run_workflow_summary_step "$workflow_without_setup_branch" "$prejourney_mutant_workflow"
run_workflow_classify_step \
  "$workflow_without_setup_branch" "$prejourney_mutant_workflow" \
  skipped skipped skipped skipped true
assert_workflow_verdict \
  "workflow without pre-journey branch" "$prejourney_mutant_workflow" INFRA \
  emulator_never_booted 1

# Honest infra control: a failed journey step with no summary is the real
# emulator-never-booted path and is allowed to be INFRA. This distinguishes it
# from first=skipped setup failure, which remains RED above.
honest_infra_workflow="$SANDBOX/workflow-honest-infra"
make_honest_infra_fixture "$honest_infra_workflow"
run_workflow_summary_step "$SCRIPT_DIR/../.github/workflows/tests.yml" "$honest_infra_workflow"
run_workflow_classify_step \
  "$SCRIPT_DIR/../.github/workflows/tests.yml" "$honest_infra_workflow" \
  failure failure failure failure true
assert_workflow_verdict \
  "honest emulator infra" "$honest_infra_workflow" INFRA \
  emulator_never_booted 1
grep -Fq 'EMULATOR INFRA UNAVAILABLE' <<<"$CLASSIFY_OUT" \
  || fail "honest no-summary infra path lost its diagnostic"

echo "PASS: extracted genuine-failure, pre-journey/setup, honest-INFRA, and enumeration paths remain selective; removing any guarded branch changes the verdict"
