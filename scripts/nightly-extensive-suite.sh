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
#      Because this phase runs the FULL connected suite (only `notClass`
#      exclusions), any new `*DockerTest` under `app/src/androidTest` is picked
#      up here automatically — e.g. `AttachmentStagerRealUploadDockerTest`
#      (issue #731), which stages a composer attachment through the production
#      `PromptAttachmentStager.uploadFile` path against `agents:2222` and reads
#      the bytes back to guard the #581 data-loss path. It is NOT in the
#      per-push allowlist (`scripts/ci-journey-suite.sh`), so it stays
#      nightly-only as that issue requires, and it reuses the `agents` fixture
#      this workflow already starts (no new fixture).
#
#   2) NETWORK-FAULT proofs — ONLY the NetworkFaultProofBase subclasses, run
#      WITHOUT `pocketshellCi=true` (so `isRunningOnCi()` is false and the
#      `assumeFalse(isRunningOnCi())` guard passes) and WITH
#      `pocketshellNetworkFaultProofs=true` (so the `assumeTrue(...)` opt-in
#      guard passes). This is the un-gating the per-push/smoke jobs never do.
#
#   3) BOOTSTRAP setup-scenario matrix (issue #667) — a TRIMMED but meaningful
#      slice of HostBootstrapScenarioSuiteTest, run WITH
#      `pocketshellBootstrapScenarios=true` so the `assumeTrue(...)` opt-in
#      guard passes. The suite otherwise self-skips, leaving the first-run
#      install / uv-install / app-update-required setup journeys guarded only
#      by the release gate (so they can regress silently between releases).
#      We select exactly three methods (ready + uvInstall + appUpdateRequired)
#      via `class=...#method` so the cost stays bounded. These drive the real
#      host-list tap path against the bootstrap Docker fixtures
#      (bootstrap-ready:2230, bootstrap-uv-install:2231, bootstrap-uv-upgrade:2236;
#      appUpdateRequired reuses the uv-upgrade container on 2236), which the
#      workflow brings up alongside the journey fixtures.
#
# The script never aborts on the first phase failure: it runs all phases,
# records each exit code, writes a pass/fail summary, and exits non-zero if
# ANY phase failed.

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

# The bootstrap setup-scenario class (opt-in via pocketshellBootstrapScenarios).
# Run as a trimmed slice in its own phase; excluded from the journey phase so it
# does not just self-skip there (the journey phase never passes the opt-in flag).
BOOTSTRAP_TEST_CLASS="com.pocketshell.app.bootstrap.HostBootstrapScenarioSuiteTest"

# Trimmed but meaningful set of bootstrap scenarios (issue #667): the first-run
# `ready` profile, the `uvInstall` first-install journey, and the
# `appUpdateRequired` (remote-newer) journey. Selected by JUnit method name via
# `class=<FQCN>#<method>,<FQCN>#<method>`.
BOOTSTRAP_METHODS=(
  "ready"
  "uvInstall"
  "appUpdateRequired"
)
BOOTSTRAP_CLASS_ARG="$(printf "%s\n" "${BOOTSTRAP_METHODS[@]}" \
  | sed "s|^|$BOOTSTRAP_TEST_CLASS#|" | paste -sd, -)"

# Classes excluded from the journey/E2E phase: the network-fault proofs (run in
# their own un-gated phase), the opt-in-only release-gate classes that need
# extra env/args, and the opt-in bootstrap scenario suite (run in its own phase
# with the pocketshellBootstrapScenarios flag) — all would otherwise self-skip.
JOURNEY_EXCLUDED_CLASSES=(
  "${NETWORK_FAULT_CLASSES[@]}"
  "$FQCN_PREFIX.LongRunningSessionStabilityTest"
  "$FQCN_PREFIX.LongRunningInstrumentationHeartbeatTest"
  "$FQCN_PREFIX.RealAgentReleaseGateTest"
  "$BOOTSTRAP_TEST_CLASS"
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

echo "=========================================================="
echo "Nightly Extensive Tests — phase 3: bootstrap setup scenarios (opt-in)"
echo "Selected methods: $BOOTSTRAP_CLASS_ARG"
echo "  (pocketshellBootstrapScenarios=true, pocketshellCi NOT set)"
echo "=========================================================="

# pocketshellCi is intentionally NOT passed: the bootstrap scenarios drive the
# real host-list tap path against the bootstrap Docker fixtures and use their
# own per-scenario timeouts, matching how the release gate / phone-walkthrough
# run them.
"$GRADLEW" :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.pocketshellBootstrapScenarios=true \
  -Pandroid.testInstrumentationRunnerArguments.class="$BOOTSTRAP_CLASS_ARG" \
  --stacktrace
BOOTSTRAP_EXIT=$?
echo "phase 3 (bootstrap setup scenarios) exit code: $BOOTSTRAP_EXIT"

journey_status="PASS"
[[ "$JOURNEY_EXIT" -ne 0 ]] && journey_status="FAIL"
nf_status="PASS"
[[ "$NETWORK_FAULT_EXIT" -ne 0 ]] && nf_status="FAIL"
bootstrap_status="PASS"
[[ "$BOOTSTRAP_EXIT" -ne 0 ]] && bootstrap_status="FAIL"

overall_status="PASS"
if [[ "$JOURNEY_EXIT" -ne 0 || "$NETWORK_FAULT_EXIT" -ne 0 || "$BOOTSTRAP_EXIT" -ne 0 ]]; then
  overall_status="FAIL"
fi

{
  echo "# Nightly Extensive — suite summary"
  echo
  echo "| Phase | Selection | Args | Exit | Result |"
  echo "| --- | --- | --- | --- | --- |"
  echo "| Journey / E2E | full connected suite minus network-fault + opt-in classes | \`pocketshellCi=true\` | $JOURNEY_EXIT | **$journey_status** |"
  echo "| Network-fault proofs | ${#NETWORK_FAULT_CLASSES[@]} NetworkFaultProofBase classes | \`pocketshellNetworkFaultProofs=true\` (no pocketshellCi) | $NETWORK_FAULT_EXIT | **$nf_status** |"
  echo "| Bootstrap setup scenarios | ${#BOOTSTRAP_METHODS[@]} HostBootstrapScenarioSuiteTest methods (trimmed) | \`pocketshellBootstrapScenarios=true\` | $BOOTSTRAP_EXIT | **$bootstrap_status** |"
  echo
  echo "**Overall: $overall_status**"
  echo
  echo "Network-fault classes exercised:"
  for c in "${NETWORK_FAULT_CLASSES[@]}"; do
    echo "- \`$c\`"
  done
  echo
  echo "Bootstrap setup scenarios exercised (\`$BOOTSTRAP_TEST_CLASS\`):"
  for m in "${BOOTSTRAP_METHODS[@]}"; do
    echo "- \`$m\`"
  done
} > "$SUMMARY"

echo "----------------------------------------------------------"
cat "$SUMMARY"
echo "----------------------------------------------------------"

if [[ "$overall_status" == "FAIL" ]]; then
  exit 1
fi
exit 0
