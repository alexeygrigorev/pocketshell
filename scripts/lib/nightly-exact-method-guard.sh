#!/usr/bin/env bash
# Exact Nightly phase-2 result guard for issue #1751.
#
# A class-level AndroidJUnitRunner selection can return green when the required
# sustained-cut method is renamed or skipped while another method in its class
# remains. The raw phase exit code also does not prove that the positive-band
# artifact was pulled. This helper makes all three conditions load-bearing.

require_exact_junit_method() {
  local results_root="$1" artifacts_root="$2" class_name="$3" method_name="$4"
  local matches successful_matches recovery_artifacts valid_recovery_artifacts

  matches="$(
    find "$results_root" -type f -name 'TEST-*.xml' -exec grep -hF "name=\"$method_name\"" {} + 2>/dev/null \
      | grep -Fc "classname=\"$class_name\"" \
      | tr -d '[:space:]'
  )"
  # The UTP JUnit producer emits a successful testcase as a self-closing tag.
  # Skips, failures, and errors have a non-self-closing testcase with a child
  # element, so they deliberately do not count as successful here.
  successful_matches="$(
    find "$results_root" -type f -name 'TEST-*.xml' -exec grep -hF "name=\"$method_name\"" {} + 2>/dev/null \
      | grep -F "classname=\"$class_name\"" \
      | grep -Ec '/>[[:space:]]*$' \
      | tr -d '[:space:]'
  )"
  recovery_artifacts="$(
    find "$artifacts_root" -type f -path '*/issue342-network-faults/recovery-band-longcut.txt' 2>/dev/null \
      | wc -l \
      | tr -d '[:space:]'
  )"
  valid_recovery_artifacts="$(
    find "$artifacts_root" -type f -path '*/issue342-network-faults/recovery-band-longcut.txt' \
      -exec sh -c \
        'grep -qx "reconnecting_band_appeared=true" "$1" && grep -qx "settled_failed_pre_empted=false" "$1"' \
        sh {} \; -print 2>/dev/null \
      | wc -l \
      | tr -d '[:space:]'
  )"

  if [[ "$matches" != "1" || "$successful_matches" != "1" ]]; then
    echo "FAIL: expected exact nightly fault method once and successful (not skipped/failed), found total=${matches:-0} successful=${successful_matches:-0}: $class_name#$method_name" >&2
    return 1
  fi
  if [[ "$recovery_artifacts" != "1" || "$valid_recovery_artifacts" != "1" ]]; then
    echo "FAIL: expected exactly one valid positive-band artifact, found total=${recovery_artifacts:-0} valid=${valid_recovery_artifacts:-0}: issue342-network-faults/recovery-band-longcut.txt" >&2
    return 1
  fi
  echo "PASS: exact nightly fault method executed once, unskipped, successful, with a valid positive-band artifact: $class_name#$method_name"
  return 0
}

nightly_exact_method_guard_self_test() {
  local fixture_root results_root artifacts_root class_name method_name
  fixture_root="$(mktemp -d)"
  results_root="$fixture_root/results"
  artifacts_root="$fixture_root/artifacts"
  class_name="com.pocketshell.app.proof.RideThroughInterruptionE2eTest"
  method_name="sustainedLinkCutReconnectsCleanlyWithoutHang"
  trap 'rm -rf "$fixture_root"' RETURN
  mkdir -p "$results_root" "$artifacts_root/device/issue342-network-faults"

  if require_exact_junit_method "$results_root" "$artifacts_root" "$class_name" "$method_name" >/dev/null 2>&1; then
    echo "SELF-TEST FAIL: an absent method passed" >&2
    return 1
  fi

  printf '<testsuite><testcase name="%s" classname="%s"><skipped /></testcase></testsuite>\n' \
    "$method_name" "$class_name" >"$results_root/TEST-guard.xml"
  if require_exact_junit_method "$results_root" "$artifacts_root" "$class_name" "$method_name" >/dev/null 2>&1; then
    echo "SELF-TEST FAIL: a skipped method passed" >&2
    return 1
  fi

  printf '<testsuite><testcase name="%s" classname="%s"><failure /></testcase></testsuite>\n' \
    "$method_name" "$class_name" >"$results_root/TEST-guard.xml"
  if require_exact_junit_method "$results_root" "$artifacts_root" "$class_name" "$method_name" >/dev/null 2>&1; then
    echo "SELF-TEST FAIL: a failed method passed" >&2
    return 1
  fi

  printf '<testsuite>\n  <testcase name="%s" classname="%s" />\n</testsuite>\n' \
    "$method_name" "$class_name" >"$results_root/TEST-guard.xml"
  if require_exact_junit_method "$results_root" "$artifacts_root" "$class_name" "$method_name" >/dev/null 2>&1; then
    echo "SELF-TEST FAIL: a missing positive-band artifact passed" >&2
    return 1
  fi

  printf 'reconnecting_band_appeared=false\nsettled_failed_pre_empted=false\n' \
    >"$artifacts_root/device/issue342-network-faults/recovery-band-longcut.txt"
  if require_exact_junit_method "$results_root" "$artifacts_root" "$class_name" "$method_name" >/dev/null 2>&1; then
    echo "SELF-TEST FAIL: an invalid positive-band artifact passed" >&2
    return 1
  fi

  printf 'reconnecting_band_appeared=true\nsettled_failed_pre_empted=false\n' \
    >"$artifacts_root/device/issue342-network-faults/recovery-band-longcut.txt"
  require_exact_junit_method "$results_root" "$artifacts_root" "$class_name" "$method_name" >/dev/null || {
    echo "SELF-TEST FAIL: a successful exact method with a valid artifact failed" >&2
    return 1
  }

  echo "nightly-exact-method-guard self-test: PASS"
}

if [[ "${BASH_SOURCE[0]}" == "$0" && "${1:-}" == "--self-test" ]]; then
  nightly_exact_method_guard_self_test
fi
