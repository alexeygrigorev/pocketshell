#!/usr/bin/env bash
# Regression for issues #1991 / #2140: a UTP device-loss teardown or aborted
# run must not be presented as a product assertion, while any substantive
# JUnit failure in the same phase must remain product-red.
# shellcheck disable=SC2016 # literal source-wiring assertions below

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CLASSIFIER="$SCRIPT_DIR/lib/nightly-phase-classification.sh"
# shellcheck source=scripts/lib/nightly-phase-classification.sh
source "$CLASSIFIER"
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

plugin_ddmlib() {
  local phrase="$1"
  printf '%s\n' \
    "Exception thrown during onAfterAll invocation of plugin AndroidTestApkInstallerPlugin:" \
    "  ${phrase}" \
    "com.android.ddmlib.AdbCommandRejectedException: ${phrase}"
}

offline_signature='Exception thrown during onAfterAll invocation of plugin AndroidTestApkInstallerPlugin: device offline
Caused by: com.android.ddmlib.AdbCommandRejectedException: device offline'

# Exact nightly-31764362152 disguise (#2140): plugin and ddmlib cause are on
# separate lines, and the phrase is `device '<serial>' not found`, not offline.
not_found_signature="$(plugin_ddmlib "device 'emulator-5554' not found")"

# Exact nightly-31767474149 disguise (#2140): aborted UTP run, no device-loss
# phrase, empty <failure></failure> body.
truncated_signature='Test run failed to complete. Expected 2 tests, received 0'

incident_signature="$(plugin_ddmlib "device 'emulator-5554' not found")
Test run failed to complete. Expected 17 tests, received 1"

unauthorized_signature="$(plugin_ddmlib 'device unauthorized')"
still_connecting_signature="$(plugin_ddmlib 'device still connecting')"
unquoted_not_found_signature="$(plugin_ddmlib 'device emulator-5554 not found')"
bare_not_found_signature="$(plugin_ddmlib 'device not found')"
ddmlib_only_not_found="com.android.ddmlib.AdbCommandRejectedException: device 'emulator-5554' not found"
plugin_only='Exception thrown during onAfterAll invocation of plugin AndroidTestApkInstallerPlugin:'

offline_only="$(write_report offline-only '' "$offline_signature")"
not_found_only="$(write_report not-found-only '' "$not_found_signature")"
unquoted_not_found="$(write_report unquoted-not-found '' "$unquoted_not_found_signature")"
bare_not_found="$(write_report bare-not-found '' "$bare_not_found_signature")"
unauthorized_only="$(write_report unauthorized-only '' "$unauthorized_signature")"
still_connecting_only="$(write_report still-connecting-only '' "$still_connecting_signature")"
truncated_only="$(write_report truncated-only '' "$truncated_signature")"
truncated_in_body="$(write_report truncated-in-body "$truncated_signature" '')"
incident_both="$(write_report incident-both '' "$incident_signature")"
complete_counts="$(write_report complete-counts '' 'Test run failed to complete. Expected 2 tests, received 2')"
over_received="$(write_report over-received '' 'Test run failed to complete. Expected 2 tests, received 3')"
ddmlib_without_plugin="$(write_report ddmlib-without-plugin '' "$ddmlib_only_not_found")"
plugin_without_ddmlib="$(write_report plugin-without-ddmlib '' "$plugin_only")"
product_only="$(write_report product-only 'java.lang.AssertionError: exact product invariant' '')"
mixed="$(write_report mixed 'java.lang.AssertionError: exact product invariant' "$offline_signature")"
mixed_not_found="$(write_report mixed-not-found 'java.lang.AssertionError: exact product invariant' "$not_found_signature")"
mixed_truncated="$(write_report mixed-truncated 'java.lang.AssertionError: exact product invariant' "$truncated_signature")"
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

assert_eq() {
  local label="$1" expected="$2" actual="$3"
  if [[ "$actual" != "$expected" ]]; then
    printf 'FAIL [%s]: expected %s, got %s\n' "$label" "$expected" "$actual"
    failures=$((failures + 1))
  else
    printf 'ok   [%s] -> %s\n' "$label" "$actual"
  fi
}

assert_classification "green phase" PASS 0 "$empty_only"
assert_classification "UTP offline + empty failure" INFRA_DEVICE_OFFLINE 1 "$offline_only"
assert_classification "UTP device-not-found + empty failure" INFRA_DEVICE_OFFLINE 1 "$not_found_only"
assert_classification "unquoted device <serial> not found" INFRA_DEVICE_OFFLINE 1 "$unquoted_not_found"
assert_classification "bare device not found" INFRA_DEVICE_OFFLINE 1 "$bare_not_found"
assert_classification "UTP device unauthorized + empty failure" INFRA_DEVICE_OFFLINE 1 "$unauthorized_only"
assert_classification "UTP device still connecting + empty failure" INFRA_DEVICE_OFFLINE 1 "$still_connecting_only"
assert_classification "truncated UTP run + empty failure" INFRA_TRUNCATED_RUN 1 "$truncated_only"
assert_classification "truncated-run sentence is the only failure body" INFRA_TRUNCATED_RUN 1 "$truncated_in_body"
assert_classification "incident: not-found plus truncated run" INFRA_DEVICE_OFFLINE 1 "$incident_both"
assert_classification "Expected N received N is not truncated" PRODUCT_FAILURE 1 "$complete_counts"
assert_classification "Expected N received M>N is not truncated" PRODUCT_FAILURE 1 "$over_received"
assert_classification "ddmlib not-found without UTP plugin stays red" PRODUCT_FAILURE 1 "$ddmlib_without_plugin"
assert_classification "UTP plugin without ddmlib cause stays red" PRODUCT_FAILURE 1 "$plugin_without_ddmlib"
assert_classification "substantive assertion" PRODUCT_FAILURE 1 "$product_only"
assert_classification "product assertion dominates later offline" PRODUCT_AND_INFRA 1 "$mixed"
assert_classification "product assertion dominates later not-found" PRODUCT_AND_INFRA 1 "$mixed_not_found"
assert_classification "product assertion dominates later truncated run" PRODUCT_AND_INFRA 1 "$mixed_truncated"
assert_classification "unclassified empty failure stays red" PRODUCT_FAILURE 1 "$empty_only"

assert_eq "empty failure is non-substantive" no \
  "$(nightly_phase_has_substantive_junit_failure "$empty_only" && printf yes || printf no)"
assert_eq "truncated-run body is non-substantive" no \
  "$(nightly_phase_has_substantive_junit_failure "$truncated_in_body" && printf yes || printf no)"
assert_eq "product assertion is substantive" yes \
  "$(nightly_phase_has_substantive_junit_failure "$product_only" && printf yes || printf no)"

assert_eq "offline status maps to INFRA" INFRA "$(nightly_phase_status INFRA_DEVICE_OFFLINE)"
assert_eq "truncated-run status maps to INFRA" INFRA "$(nightly_phase_status INFRA_TRUNCATED_RUN)"
assert_eq "unknown classification stays FAIL" FAIL "$(nightly_phase_status SOMETHING_ELSE)"

evidence="$SANDBOX/classification.txt"
write_nightly_phase_classification "$evidence" phase2-network-fault 1 "$offline_only"
grep -Fqx 'phase=phase2-network-fault' "$evidence" || failures=$((failures + 1))
grep -Fqx 'classification=INFRA_DEVICE_OFFLINE' "$evidence" || failures=$((failures + 1))
grep -Fqx 'device_offline_signature=yes' "$evidence" || failures=$((failures + 1))
grep -Fqx 'truncated_run_signature=no' "$evidence" || failures=$((failures + 1))
grep -Fqx 'substantive_junit_failure=no' "$evidence" || failures=$((failures + 1))

truncated_evidence="$SANDBOX/truncated-classification.txt"
write_nightly_phase_classification "$truncated_evidence" phase2b-expected-fail 1 "$truncated_only"
grep -Fqx 'classification=INFRA_TRUNCATED_RUN' "$truncated_evidence" || failures=$((failures + 1))
grep -Fqx 'device_offline_signature=no' "$truncated_evidence" || failures=$((failures + 1))
grep -Fqx 'truncated_run_signature=yes' "$truncated_evidence" || failures=$((failures + 1))
grep -Fqx 'substantive_junit_failure=no' "$truncated_evidence" || failures=$((failures + 1))

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

# ---------------------------------------------------------------------------
# G6: removing one new signature reddens only that signature's fixture.
# ---------------------------------------------------------------------------
classify_with() {
  local lib="$1" exit_code="$2" report_root="$3"
  bash -c '
    set -uo pipefail
    # shellcheck disable=SC1090
    source "$1"
    classify_nightly_phase "$2" "$3"
  ' _ "$lib" "$exit_code" "$report_root"
}

mutate_classifier() {
  local dest="$1" old="$2" new="$3"
  python3 - "$CLASSIFIER" "$dest" "$old" "$new" <<'PY'
from pathlib import Path
import sys

src, dest, old, new = sys.argv[1:]
text = Path(src).read_text()
count = text.count(old)
if count != 1:
    raise SystemExit(f"G6 needle count is {count}, expected 1: {old!r}")
Path(dest).write_text(text.replace(old, new))
PY
}

assert_mutant_classifications() {
  local label="$1" lib="$2"
  shift 2
  local actual pair fixture want
  local flipped=() kept_wrong=()
  for pair in "$@"; do
    fixture="${pair%%=*}"
    want="${pair#*=}"
    actual="$(classify_with "$lib" 1 "$fixture")"
    if [[ "$actual" != "$want" ]]; then
      if [[ "$want" == PRODUCT_FAILURE ]]; then
        flipped+=("${fixture##*/}:$actual")
      else
        kept_wrong+=("${fixture##*/}: expected $want got $actual")
      fi
    fi
  done
  if (( ${#flipped[@]} + ${#kept_wrong[@]} > 0 )); then
    printf 'FAIL [G6 %s]: did not redden=%s over-reddened=%s\n' \
      "$label" "${flipped[*]:-none}" "${kept_wrong[*]:-none}"
    failures=$((failures + 1))
  else
    printf 'ok   [G6 %s] selective\n' "$label"
  fi
}

not_found_mutant="$SANDBOX/classifier-no-not-found.sh"
mutate_classifier "$not_found_mutant" \
  "_NIGHTLY_DEVICE_NOT_FOUND_PATTERN=\"device( '[^']+'| [^[:space:]]+)? not found\"" \
  '_NIGHTLY_DEVICE_NOT_FOUND_PATTERN="device not-found-signature-removed"'
assert_mutant_classifications "drop not-found phrase" "$not_found_mutant" \
  "$not_found_only=PRODUCT_FAILURE" \
  "$unquoted_not_found=PRODUCT_FAILURE" \
  "$bare_not_found=PRODUCT_FAILURE" \
  "$offline_only=INFRA_DEVICE_OFFLINE" \
  "$unauthorized_only=INFRA_DEVICE_OFFLINE" \
  "$still_connecting_only=INFRA_DEVICE_OFFLINE" \
  "$truncated_only=INFRA_TRUNCATED_RUN" \
  "$incident_both=INFRA_TRUNCATED_RUN" \
  "$empty_only=PRODUCT_FAILURE" \
  "$product_only=PRODUCT_FAILURE"

truncated_mutant="$SANDBOX/classifier-no-truncated.sh"
mutate_classifier "$truncated_mutant" \
  "_NIGHTLY_TRUNCATED_RUN_PATTERN='Expected[[:space:]]+([0-9]+)[[:space:]]+tests?,[[:space:]]+received[[:space:]]+([0-9]+)'" \
  "_NIGHTLY_TRUNCATED_RUN_PATTERN='truncated-run-signature-removed ([0-9]+) ([0-9]+)'"
assert_mutant_classifications "drop truncated-run pattern" "$truncated_mutant" \
  "$truncated_only=PRODUCT_FAILURE" \
  "$truncated_in_body=PRODUCT_FAILURE" \
  "$offline_only=INFRA_DEVICE_OFFLINE" \
  "$not_found_only=INFRA_DEVICE_OFFLINE" \
  "$unauthorized_only=INFRA_DEVICE_OFFLINE" \
  "$still_connecting_only=INFRA_DEVICE_OFFLINE" \
  "$incident_both=INFRA_DEVICE_OFFLINE" \
  "$empty_only=PRODUCT_FAILURE" \
  "$product_only=PRODUCT_FAILURE"

if [[ "$failures" -ne 0 ]]; then
  printf 'NIGHTLY PHASE CLASSIFICATION SELF-TEST FAIL: %s failure(s)\n' "$failures"
  exit 1
fi
echo 'NIGHTLY PHASE CLASSIFICATION SELF-TEST PASS'
