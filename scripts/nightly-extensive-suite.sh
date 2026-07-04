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

# The NetworkFaultProofBase subclasses (toxiproxy proofs). Keep this list in
# sync with `grep -rl NetworkFaultProofBase app/src/androidTest/.../proof/`.
FQCN_PREFIX="com.pocketshell.app.proof"
NETWORK_FAULT_CLASSES=(
  "$FQCN_PREFIX.RideThroughInterruptionE2eTest"
  "$FQCN_PREFIX.WithinGraceResumeRideThroughE2eTest"
  "$FQCN_PREFIX.StaleLeaseSwitchRecoveryE2eTest"
  "$FQCN_PREFIX.DisconnectFlapSoakE2eTest"
  "$FQCN_PREFIX.DisconnectBlackholeE2eTest"
  "$FQCN_PREFIX.NetworkLatencyModelE2eTest"
  "$FQCN_PREFIX.PacketLossNetworkFaultE2eTest"
  # Issue #1064 (R4 / #843 round-2 T10/C4): slow COLD-DIAL robustness. A
  # NetworkFaultProofBase toxiproxy proof that applies jitter-latency +
  # bandwidth-limit (and, in the class-coverage variant, a half-open blackhole)
  # BEFORE the app's first connect, so the cold handshake itself rides the
  # degraded link. It self-skips on per-PR CI (assumeNetworkFaultProofsEnabled ->
  # tests.yml leaves network-fault-proxy:2228 + toxiproxy:8474 down), so wiring it
  # into ci-journey-suite.sh would only ALL-SKIP (the G3 vacuous-pass trap). The
  # durable gate is here, alongside its sibling NetworkFaultProofBase proofs.
  "$FQCN_PREFIX.ColdDialUnderBandwidthLimitE2eTest"
  # Issue #576 / J4: CodexRedrawOverflowReconnectE2eTest is a NetworkFaultProofBase
  # subclass (toxiproxy bandwidth toxic on 2228/8474). A heavy Codex alt-screen
  # redraw whose %output backlog can't drain in the 10 s tmux command-timeout
  # window USED to self-inflict a FatalClose -> reader EOF -> reconnect. The P4
  # connection-core fix (#687) makes the per-command timeout an IDLE deadline that
  # re-arms on reader-side progress and downgrades read-only commands (capture-pane
  # / list-* / display-message / refresh-client) to FailOpenDrain, so the busy link
  # no longer tears itself down. The test now passes GREEN; enrolled here as the
  # standing nightly regression guard.
  "$FQCN_PREFIX.CodexRedrawOverflowReconnectE2eTest"
  # Issue #1139 (maintainer's #1 freeze / top v0.4.20 release-gate item): the
  # push-notification → resume-an-idle-overnight-session UI freeze. A toxiproxy
  # `timeout=0` blackhole DEAD-HOLDS the `-CC` socket (half-open, no FIN — the
  # overnight NAT death) so the grace-loop teardown socket-write genuinely WEDGES,
  # + `forceLivenessProbeDeadForTest` makes the app DETECT the dead socket and run
  # its within-grace resume close/reconnect over it. The REAL
  # MainThreadResponsivenessProbe measures Main-thread latency DURING that
  # grace-loop close/reconnect and HARD-asserts Main stays responsive (< 750ms, no
  # 2-4s ANR). RED on the base blocking close(), GREEN with the #1139 non-blocking
  # RealSshShell/RealSshSession close. Needs the toxiproxy family (a happy or
  # kill-9'd socket cannot wedge the close), so it is nightly, not per-push. Reuses
  # network-fault-proxy:2228 + toxiproxy API:8474 (no new fixture).
  "$FQCN_PREFIX.PushResumeDeadSocketMainResponsiveE2eTest"
  # Issue #1063 (R3, #843 round-2 gap C2): the REAL-WIRE carrier-NAT idle-mapping
  # RECOVERY proof (Arm 2). A toxiproxy `timeout=0` half-open blackhole models the
  # carrier NAT reaping an idle TCP mapping mid-idle (no RST/FIN — all bytes,
  # `-CC` included, silently dropped); the always-on transport keepalive must
  # DETECT the dead half-open transport within its `countMax × interval` budget and
  # drive recovery, after which the session returns to Connected and a post-recovery
  # send round-trips. Self-skips per-push (needs network-fault-proxy:2228 +
  # toxiproxy API:8474 which tests.yml leaves down), so it is enrolled here with its
  # toxiproxy siblings. The LOAD-BEARING per-push red→green for Arm 1 (idle-mapping
  # SURVIVAL: keepalive interval < NAT window keeps the mapping warm) lives at the
  # keepalive layer in shared/core-ssh (NatIdleMappingSurvivalKeepAliveTest, the
  # Unit gate). Reuses network-fault-proxy:2228 + toxiproxy API:8474 (no new fixture).
  "$FQCN_PREFIX.NatIdleMappingSurvivalE2eTest"
)

# ---------------------------------------------------------------------------
# EXPECTED-FAIL lane (issue #1201, de-gated from the fault verdict).
#
# The #822 Slice C/D journeys (SilentMidSessionDropDetectionE2eTest) are TDD-style
# executable specs for UNBUILT connection-manager features — the two tests assert
# "Expected to FAIL until the LivenessProbe (Slice D) lands" / "until the
# controller-owned reconnect ladder (Slice C) lands". They are DESIGNED red until
# those slices land. They are NOT fault-suite regressions, so they must NOT poison
# the fault-injection safety verdict the release gate reads (that is exactly what
# forced every recent release to waive the gate with NIGHTLY_FAULT_GATE_DISABLED=1).
#
# They still RUN nightly (their tracking value — their artifacts/timings are still
# uploaded and their status is still shown in the summary), but in their OWN phase
# (2b) whose exit code is recorded as informational only and is DELIBERATELY
# excluded from both `overall_status` and the machine-readable fault verdict. When
# Slice C/D lands and they turn GREEN, promote them back into NETWORK_FAULT_CLASSES.
#
# They use the same toxiproxy harness (NetworkFaultProofBase → network-fault-proxy
# :2228 + toxiproxy API:8474) as the gating proofs, so they run WITH the same
# pocketshellNetworkFaultProofs=true opt-in flag.
EXPECTED_FAIL_CLASSES=(
  "$FQCN_PREFIX.SilentMidSessionDropDetectionE2eTest"
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
# their own un-gated phase), the #822 expected-fail lane (run in its own
# non-gating phase 2b), the opt-in-only release-gate classes that need extra
# env/args, and the opt-in bootstrap scenario suite (run in its own phase with
# the pocketshellBootstrapScenarios flag) — all would otherwise self-skip.
JOURNEY_EXCLUDED_CLASSES=(
  "${NETWORK_FAULT_CLASSES[@]}"
  "${EXPECTED_FAIL_CLASSES[@]}"
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
EXPECTED_FAIL_CLASS_ARG="$(join_by , "${EXPECTED_FAIL_CLASSES[@]}")"
JOURNEY_NOTCLASS_ARG="$(join_by , "${JOURNEY_EXCLUDED_CLASSES[@]}")"

# The machine-readable fault-verdict helper (issue #1201): pure PASS/FAIL from the
# network-fault + bootstrap phases ONLY (never the journey suite or the
# expected-fail lane). Written to a file the CI fault-verdict job reads.
# shellcheck source=scripts/lib/nightly-fault-verdict.sh
source "$REPO_ROOT/scripts/lib/nightly-fault-verdict.sh"
FAULT_VERDICT_FILE="$ARTIFACT_DIR/fault-verdict.txt"

# ---------------------------------------------------------------------------
# Sharding (issue #835 follow-up): the full connected journey/E2E suite is
# ~680 tests. Run serially on ONE swiftshader AVD it cannot finish inside the
# 150-min job ceiling (the #470 enumeration stall worsens the more the single
# AVD is churned, so 544/683 at the timeout). The nightly workflow now fans
# this job out across a matrix of runners — each its OWN cold-booted emulator +
# Docker fixtures — and sets POCKETSHELL_NIGHTLY_SHARD_INDEX / _TOTAL. We hand
# those to AndroidJUnitRunner's built-in numShards/shardIndex so each leg runs
# only its round-robin 1/N slice of phase 1, comfortably inside the ceiling.
#
# When the shard env is unset (a single-runner / local run) the suite behaves
# exactly as before: one leg runs the whole phase-1 suite plus phases 2 & 3.
SHARD_INDEX="${POCKETSHELL_NIGHTLY_SHARD_INDEX:-}"
SHARD_TOTAL="${POCKETSHELL_NIGHTLY_SHARD_TOTAL:-}"
JOURNEY_SHARD_ARGS=()
SHARDING="no"
if [[ -n "$SHARD_TOTAL" && "$SHARD_TOTAL" -gt 1 ]]; then
  SHARDING="yes"
  JOURNEY_SHARD_ARGS=(
    "-Pandroid.testInstrumentationRunnerArguments.numShards=$SHARD_TOTAL"
    "-Pandroid.testInstrumentationRunnerArguments.shardIndex=${SHARD_INDEX:-0}"
  )
fi

# Phases 2 (network-fault proofs) and 3 (bootstrap scenarios) are NOT sharded:
# they are a small fixed set and run only ONCE (on shard 0, or on every run
# when sharding is disabled). Running them on every shard would needlessly
# triple the slow toxiproxy soak proofs and bootstrap journeys, and would
# require the toxiproxy + bootstrap fixtures on every leg.
RUN_AUX_PHASES="yes"
if [[ "$SHARDING" == "yes" && "${SHARD_INDEX:-0}" -ne 0 ]]; then
  RUN_AUX_PHASES="no"
fi

echo "=========================================================="
echo "Nightly Extensive Tests — phase 1: journey/E2E (pocketshellCi=true)"
echo "Excluded classes: $JOURNEY_NOTCLASS_ARG"
if [[ "$SHARDING" == "yes" ]]; then
  echo "Sharding: shard ${SHARD_INDEX:-0} of $SHARD_TOTAL (numShards/shardIndex)"
else
  echo "Sharding: disabled (single runner runs the full phase-1 suite)"
fi
echo "=========================================================="

"$GRADLEW" :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.pocketshellCi=true \
  -Pandroid.testInstrumentationRunnerArguments.notClass="$JOURNEY_NOTCLASS_ARG" \
  "${JOURNEY_SHARD_ARGS[@]}" \
  --stacktrace
JOURNEY_EXIT=$?
echo "phase 1 (journey/E2E) exit code: $JOURNEY_EXIT"

# Default the aux phases to SKIPPED; only the shard that owns them flips these.
NETWORK_FAULT_EXIT=0
BOOTSTRAP_EXIT=0
EXPECTED_FAIL_EXIT=0
nf_status="SKIP"
bootstrap_status="SKIP"
expectedfail_status="SKIP"

if [[ "$RUN_AUX_PHASES" == "yes" ]]; then
  echo "=========================================================="
  echo "Nightly Extensive Tests — phase 2: network-fault proofs (un-gated, GATING)"
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
  echo "Nightly Extensive Tests — phase 2b: #822 expected-fail lane (NON-GATING)"
  echo "Included classes: $EXPECTED_FAIL_CLASS_ARG"
  echo "  (pocketshellNetworkFaultProofs=true; result is INFORMATIONAL ONLY —"
  echo "   these are TDD specs for unbuilt Slice C/D features, designed RED, and"
  echo "   are DELIBERATELY excluded from the fault verdict — issue #1201)"
  echo "=========================================================="

  # Issue #1201: the #822 Slice C/D journeys still RUN nightly (their tracking
  # value) but in their OWN phase whose exit code NEVER feeds `overall_status` or
  # the machine-readable fault verdict — so an intentional red here can no longer
  # poison the release-gating fault signal.
  "$GRADLEW" :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.pocketshellNetworkFaultProofs=true \
    -Pandroid.testInstrumentationRunnerArguments.class="$EXPECTED_FAIL_CLASS_ARG" \
    --stacktrace
  EXPECTED_FAIL_EXIT=$?
  echo "phase 2b (#822 expected-fail lane) exit code: $EXPECTED_FAIL_EXIT (NON-GATING)"

  echo "=========================================================="
  echo "Nightly Extensive Tests — phase 3: bootstrap setup scenarios (opt-in, GATING)"
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

  nf_status="PASS"
  [[ "$NETWORK_FAULT_EXIT" -ne 0 ]] && nf_status="FAIL"
  bootstrap_status="PASS"
  [[ "$BOOTSTRAP_EXIT" -ne 0 ]] && bootstrap_status="FAIL"
  expectedfail_status="PASS"
  [[ "$EXPECTED_FAIL_EXIT" -ne 0 ]] && expectedfail_status="FAIL"

  # Issue #1201: emit the authoritative, machine-readable fault-injection safety
  # verdict from the network-fault + bootstrap phases ONLY. The journey suite
  # (phase 1) and the #822 expected-fail lane (phase 2b) are DELIBERATELY not
  # inputs, so their chronic/intentional red can no longer flip this verdict. The
  # CI `Fault-injection safety verdict` job reads this file; the release-gate
  # guard reads THAT job's conclusion.
  write_fault_verdict_file \
    "$FAULT_VERDICT_FILE" \
    "$nf_status" "$NETWORK_FAULT_EXIT" \
    "$bootstrap_status" "$BOOTSTRAP_EXIT" \
    "$expectedfail_status" "$EXPECTED_FAIL_EXIT"
  fault_verdict="$(grep -E '^fault_verdict=' "$FAULT_VERDICT_FILE" | head -1 | cut -d= -f2)"
  echo "----------------------------------------------------------"
  echo "Fault-injection safety verdict (issue #1201) -> $fault_verdict"
  cat "$FAULT_VERDICT_FILE"
  echo "----------------------------------------------------------"
else
  echo "=========================================================="
  echo "Nightly Extensive Tests — phases 2, 2b & 3 SKIPPED on shard ${SHARD_INDEX:-0}"
  echo "  (network-fault + expected-fail + bootstrap run once, on shard 0)"
  echo "=========================================================="
fi

journey_status="PASS"
[[ "$JOURNEY_EXIT" -ne 0 ]] && journey_status="FAIL"

# `overall_status` is the human/summary verdict for the whole extensive shard. It
# includes the journey suite and both GATING fault phases, but NOT the #822
# expected-fail lane (phase 2b) — including an intentionally-red TDD lane would
# make the shard summary permanently red for a non-reason. Note: `overall_status`
# is NOT the release-gating signal; the machine-readable fault verdict above is.
overall_status="PASS"
if [[ "$JOURNEY_EXIT" -ne 0 || "$NETWORK_FAULT_EXIT" -ne 0 || "$BOOTSTRAP_EXIT" -ne 0 ]]; then
  overall_status="FAIL"
fi

if [[ "$SHARDING" == "yes" ]]; then
  shard_label="shard ${SHARD_INDEX:-0} of $SHARD_TOTAL (round-robin numShards/shardIndex)"
else
  shard_label="single runner (no sharding)"
fi

{
  echo "# Nightly Extensive — suite summary"
  echo
  echo "Phase-1 selection: $shard_label"
  echo
  echo "| Phase | Selection | Args | Exit | Result |"
  echo "| --- | --- | --- | --- | --- |"
  echo "| Journey / E2E (non-gating) | full connected suite minus network-fault + expected-fail + opt-in classes ($shard_label) | \`pocketshellCi=true\` | $JOURNEY_EXIT | **$journey_status** |"
  echo "| Network-fault proofs (GATING) | ${#NETWORK_FAULT_CLASSES[@]} NetworkFaultProofBase classes | \`pocketshellNetworkFaultProofs=true\` (no pocketshellCi) | $NETWORK_FAULT_EXIT | **$nf_status** |"
  echo "| #822 expected-fail lane (NON-GATING) | ${#EXPECTED_FAIL_CLASSES[@]} Slice C/D TDD spec class(es) | \`pocketshellNetworkFaultProofs=true\` | $EXPECTED_FAIL_EXIT | **$expectedfail_status** |"
  echo "| Bootstrap setup scenarios (GATING) | ${#BOOTSTRAP_METHODS[@]} HostBootstrapScenarioSuiteTest methods (trimmed) | \`pocketshellBootstrapScenarios=true\` | $BOOTSTRAP_EXIT | **$bootstrap_status** |"
  echo
  echo "**Extensive-shard overall (non-gating summary): $overall_status**"
  echo
  echo "## Fault-injection safety verdict (issue #1201 — the RELEASE-GATING signal)"
  echo
  if [[ "$RUN_AUX_PHASES" == "yes" ]]; then
    echo "\`fault_verdict\` = network-fault ($nf_status) + bootstrap ($bootstrap_status) ONLY."
    echo "The journey suite and the #822 expected-fail lane are DELIBERATELY excluded."
    echo
    echo '```'
    cat "$FAULT_VERDICT_FILE"
    echo '```'
  else
    echo "Not computed on this shard (aux phases run once, on shard 0)."
  fi
  echo
  echo "Network-fault classes exercised (GATING):"
  for c in "${NETWORK_FAULT_CLASSES[@]}"; do
    echo "- \`$c\`"
  done
  echo
  echo "#822 expected-fail lane (NON-GATING, tracked only — TDD specs for unbuilt Slice C/D):"
  for c in "${EXPECTED_FAIL_CLASSES[@]}"; do
    echo "- \`$c\`"
  done
  echo
  echo "Bootstrap setup scenarios exercised (\`$BOOTSTRAP_TEST_CLASS\`, GATING):"
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
