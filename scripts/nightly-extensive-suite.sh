#!/usr/bin/env bash
# Nightly Extensive Tests — suite driver (issue #659, epic #657).
#
# Runs inside the `reactivecircus/android-emulator-runner` `script:` step, so
# the emulator is already booted and `adb` is on PATH. The Docker fixtures
# (agents:2222, flaky-agent:2226, tmux:2224, network-fault-proxy:2228,
# packet-loss-proxy:2229, toxiproxy API:8474) were started by the workflow
# before this script runs.
#
# Two gradle invocations, by design:
#
#   1) JOURNEY/E2E suite — the full connected suite with `pocketshellCi=true`
#      (so the E2E timeouts use the generous CI ceilings). The network-fault
#      proof classes are explicitly EXCLUDED here: they self-skip on CI via
#      `assumeFalse(isRunningOnCi())`, so excluding them avoids burning their
#      setup time for a guaranteed "assumption failed" no-op. The opt-in-only
#      long-running + real-agent gate classes are also excluded (they need
#      their own env/args and belong to the release gate, not this run).
#
#   2) NETWORK-FAULT proofs — ONLY the NetworkFaultProofBase subclasses, run
#      WITHOUT `pocketshellCi=true` (so `isRunningOnCi()` is false and the
#      `assumeFalse(isRunningOnCi())` guard passes) and WITH
#      `pocketshellNetworkFaultProofs=true` (so the `assumeTrue(...)` opt-in
#      guard passes). This is the un-gating the per-push/smoke jobs never do.
#
# The script never aborts on the first gradle failure: it runs both phases,
# records each exit code, writes a pass/fail summary, and exits non-zero if
# EITHER phase failed.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

ARTIFACT_DIR="$REPO_ROOT/artifacts/nightly-extensive"
mkdir -p "$ARTIFACT_DIR"
SUMMARY="$ARTIFACT_DIR/summary.md"

GRADLEW="$REPO_ROOT/gradlew"

# The seven NetworkFaultProofBase subclasses (toxiproxy proofs). Keep this list
# in sync with `grep -rl NetworkFaultProofBase app/src/androidTest/.../proof/`.
FQCN_PREFIX="com.pocketshell.app.proof"
NETWORK_FAULT_CLASSES=(
  "$FQCN_PREFIX.RideThroughInterruptionE2eTest"
  "$FQCN_PREFIX.WithinGraceResumeRideThroughE2eTest"
  "$FQCN_PREFIX.StaleLeaseSwitchRecoveryE2eTest"
  "$FQCN_PREFIX.DisconnectFlapSoakE2eTest"
  "$FQCN_PREFIX.DisconnectBlackholeE2eTest"
  "$FQCN_PREFIX.NetworkLatencyModelE2eTest"
  "$FQCN_PREFIX.PacketLossNetworkFaultE2eTest"
)

# Classes excluded from the journey/E2E phase: the network-fault proofs (run in
# their own un-gated phase) plus the opt-in-only release-gate classes that need
# extra env/args and would otherwise just self-skip.
JOURNEY_EXCLUDED_CLASSES=(
  "${NETWORK_FAULT_CLASSES[@]}"
  "$FQCN_PREFIX.LongRunningSessionStabilityTest"
  "$FQCN_PREFIX.LongRunningInstrumentationHeartbeatTest"
  "$FQCN_PREFIX.RealAgentReleaseGateTest"
)

join_by() {
  local IFS="$1"
  shift
  echo "$*"
}

NETWORK_FAULT_CLASS_ARG="$(join_by , "${NETWORK_FAULT_CLASSES[@]}")"
JOURNEY_NOTCLASS_ARG="$(join_by , "${JOURNEY_EXCLUDED_CLASSES[@]}")"

echo "=========================================================="
echo "Nightly Extensive Tests — phase 1: journey/E2E (pocketshellCi=true)"
echo "Excluded classes: $JOURNEY_NOTCLASS_ARG"
echo "=========================================================="

"$GRADLEW" :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.pocketshellCi=true \
  -Pandroid.testInstrumentationRunnerArguments.notClass="$JOURNEY_NOTCLASS_ARG" \
  --stacktrace
JOURNEY_EXIT=$?
echo "phase 1 (journey/E2E) exit code: $JOURNEY_EXIT"

echo "=========================================================="
echo "Nightly Extensive Tests — phase 2: network-fault proofs (un-gated)"
echo "Included classes: $NETWORK_FAULT_CLASS_ARG"
echo "  (pocketshellNetworkFaultProofs=true, pocketshellCi NOT set)"
echo "=========================================================="

# NOTE: pocketshellCi is intentionally NOT passed here so that
# `TerminalTestTimeouts.isRunningOnCi()` is false and the
# `assumeFalse(isRunningOnCi())` guard in NetworkFaultProofBase passes.
"$GRADLEW" :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.pocketshellNetworkFaultProofs=true \
  -Pandroid.testInstrumentationRunnerArguments.class="$NETWORK_FAULT_CLASS_ARG" \
  --stacktrace
NETWORK_FAULT_EXIT=$?
echo "phase 2 (network-fault proofs) exit code: $NETWORK_FAULT_EXIT"

journey_status="PASS"
[[ "$JOURNEY_EXIT" -ne 0 ]] && journey_status="FAIL"
nf_status="PASS"
[[ "$NETWORK_FAULT_EXIT" -ne 0 ]] && nf_status="FAIL"

overall_status="PASS"
if [[ "$JOURNEY_EXIT" -ne 0 || "$NETWORK_FAULT_EXIT" -ne 0 ]]; then
  overall_status="FAIL"
fi

{
  echo "# Nightly Extensive — suite summary"
  echo
  echo "| Phase | Selection | Args | Exit | Result |"
  echo "| --- | --- | --- | --- | --- |"
  echo "| Journey / E2E | full connected suite minus network-fault + opt-in classes | \`pocketshellCi=true\` | $JOURNEY_EXIT | **$journey_status** |"
  echo "| Network-fault proofs | ${#NETWORK_FAULT_CLASSES[@]} NetworkFaultProofBase classes | \`pocketshellNetworkFaultProofs=true\` (no pocketshellCi) | $NETWORK_FAULT_EXIT | **$nf_status** |"
  echo
  echo "**Overall: $overall_status**"
  echo
  echo "Network-fault classes exercised:"
  for c in "${NETWORK_FAULT_CLASSES[@]}"; do
    echo "- \`$c\`"
  done
} > "$SUMMARY"

echo "----------------------------------------------------------"
cat "$SUMMARY"
echo "----------------------------------------------------------"

if [[ "$overall_status" == "FAIL" ]]; then
  exit 1
fi
exit 0
