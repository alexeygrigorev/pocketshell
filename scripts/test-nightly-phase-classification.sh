#!/usr/bin/env bash
# Regression for issue #1991: a UTP device-offline teardown must not be
# presented as a product network-fault assertion, while any substantive JUnit
# failure in the same phase must remain product-red.
# shellcheck disable=SC2016 # literal source-wiring assertions below

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=scripts/lib/nightly-phase-classification.sh
source "$SCRIPT_DIR/lib/nightly-phase-classification.sh"
# shellcheck source=scripts/lib/nightly-fault-verdict.sh
source "$SCRIPT_DIR/lib/nightly-fault-verdict.sh"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

write_report() {
  local name="$1"
  local failure_body="$2"
  local system_err="$3"
  local root="$SANDBOX/$name"
  mkdir -p "$root"
  {
    printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>'
    printf '%s\n' '<testsuite tests="1" failures="1" errors="0" skipped="0">'
    printf '%s\n' '  <testcase name="loadBearing" classname="com.pocketshell.Proof">'
    printf '    <failure>%s</failure>\n' "$failure_body"
    printf '%s\n' '  </testcase>'
    printf '  <system-err>%s</system-err>\n' "$system_err"
    printf '%s\n' '</testsuite>'
  } > "$root/TEST-device.xml"
  printf '%s\n' "$root"
}

offline_signature='Exception thrown during onAfterAll invocation of plugin AndroidTestApkInstallerPlugin: device offline
Caused by: com.android.ddmlib.AdbCommandRejectedException: device offline'

offline_only="$(write_report offline-only '' "$offline_signature")"
product_only="$(write_report product-only 'java.lang.AssertionError: exact product invariant' '')"
mixed="$(write_report mixed 'java.lang.AssertionError: exact product invariant' "$offline_signature")"
empty_only="$(write_report empty-only '' '')"

failures=0
assert_classification() {
  local label="$1" expected="$2" exit_code="$3" report_root="$4"
  local actual
  actual="$(classify_nightly_phase "$exit_code" "$report_root")"
  if [[ "$actual" != "$expected" ]]; then
    printf 'FAIL [%s]: expected %s, got %s\n' "$label" "$expected" "$actual"
    failures=$((failures + 1))
  else
    printf 'ok   [%s] -> %s\n' "$label" "$actual"
  fi
}

assert_classification "green phase" PASS 0 "$empty_only"
assert_classification "UTP offline + empty failure" INFRA_DEVICE_OFFLINE 1 "$offline_only"
assert_classification "substantive assertion" PRODUCT_FAILURE 1 "$product_only"
assert_classification "product assertion dominates later offline" PRODUCT_AND_INFRA 1 "$mixed"
assert_classification "unclassified empty failure stays red" PRODUCT_FAILURE 1 "$empty_only"

evidence="$SANDBOX/classification.txt"
write_nightly_phase_classification "$evidence" phase2-network-fault 1 "$offline_only"
grep -Fqx 'phase=phase2-network-fault' "$evidence" || failures=$((failures + 1))
grep -Fqx 'classification=INFRA_DEVICE_OFFLINE' "$evidence" || failures=$((failures + 1))
grep -Fqx 'device_offline_signature=yes' "$evidence" || failures=$((failures + 1))
grep -Fqx 'substantive_junit_failure=no' "$evidence" || failures=$((failures + 1))

# Release-verdict integration: INFRA blocks distinctly; a real product failure
# still wins if the other gating phase is infrastructure-red.
verdict="$(compute_fault_verdict INFRA PASS)" && verdict_rc=0 || verdict_rc=$?
[[ "$verdict" == INFRA && "$verdict_rc" -eq 2 ]] || failures=$((failures + 1))
verdict="$(compute_fault_verdict FAIL INFRA)" && verdict_rc=0 || verdict_rc=$?
[[ "$verdict" == FAIL && "$verdict_rc" -eq 1 ]] || failures=$((failures + 1))

# Gate wiring: pin the phase-2 preserved report -> classification -> status
# chain and the dedicated workflow message. These assertions complement the
# behavioural fixture above; neither can green if the production call site is
# disconnected from the tested helper.
suite="$REPO_ROOT/scripts/nightly-extensive-suite.sh"
workflow="$REPO_ROOT/.github/workflows/nightly-extensive.yml"
# These are intentionally literal source-wiring needles, not shell expansions.
grep -Fq '"$PHASE_CLASSIFICATIONS_DIR/phase2-network-fault.txt"' "$suite" \
  || failures=$((failures + 1))
grep -Fq '"$NETWORK_FAULT_EXIT" "$PHASE_REPORTS_DIR/phase2-network-fault"' "$suite" \
  || failures=$((failures + 1))
grep -Fq 'nf_status="$(nightly_phase_status "$nf_classification")"' "$suite" \
  || failures=$((failures + 1))
grep -Fq 'if [[ "$verdict" == "INFRA" ]]' "$workflow" \
  || failures=$((failures + 1))
grep -Fq 'BLOCK-INFRA:' "$workflow" || failures=$((failures + 1))

if [[ "$failures" -ne 0 ]]; then
  printf 'NIGHTLY PHASE CLASSIFICATION SELF-TEST FAIL: %s failure(s)\n' "$failures"
  exit 1
fi
echo 'NIGHTLY PHASE CLASSIFICATION SELF-TEST PASS'
