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
#   2) NETWORK-FAULT proofs — ONLY the fixture-backed network-fault classes, run
#      WITHOUT `pocketshellCi=true` (so `isRunningOnCi()` is false and the
#      `assumeFalse(isRunningOnCi())` guard passes) and WITH
#      `pocketshellNetworkFaultProofs=true` (so the `assumeTrue(...)` opt-in
#      guard passes). This is the un-gating the per-push/smoke jobs never do.
#
#   3) BOOTSTRAP setup-scenario matrix (issue #667) — HostBootstrapScenarioSuiteTest,
#      run WITH `pocketshellBootstrapScenarios=true` so the `assumeTrue(...)`
#      opt-in guard passes. The suite otherwise self-skips, leaving the setup
#      journeys guarded only by the release gate (so they can regress silently
#      between releases). Methods are selected by name via `class=...#method`.
#      Issue #2111: this used to select FOUR of the class's TEN scenarios, so six
#      executed on no lane at all; ALL TEN now run. They drive the real host-list
#      tap path against the bootstrap Docker fixtures (bootstrap-ready:2230,
#      -uv-install:2231, -unsupported:2232, -daemon-disabled:2233,
#      -user-local-path:2234, -fish-user-local-path:2235, -uv-upgrade:2236 —
#      reused by uvUpgradeFailure and appUpdateRequired — and
#      -notifications:2241), which the workflow brings up alongside the journey
#      fixtures.
#
#   4) REAL-AGENT CLI gate (issue #2111) — RealAgentReleaseGateTest against the
#      separate `tests/docker/real-agent/compose.yml` fixture on port 2240, run
#      WITH `pocketshellRealAgentReleaseGate=1`. It drives the REAL claude/codex
#      binaries in a tmux pane through the app. It previously ran ONLY under
#      `TERMINAL_RELEASE_GATE=1 scripts/release-emulator-validation.sh`, so a
#      real-CLI rendering regression stayed invisible until someone cut a
#      release. Its exit code feeds `overall_status` but NOT the #1201
#      release-gating fault verdict.
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

# Issue #2090: extra-runner last-completed-class heartbeat. Nightly has no
# per-class bash loop, so phase-1 Gradle output is parsed via observe-stream.
CI_JOURNEY_PROGRESS_HELPER="${CI_JOURNEY_PROGRESS_HELPER:-$REPO_ROOT/scripts/ci-journey-progress-telemetry.sh}"
if [[ -f "$CI_JOURNEY_PROGRESS_HELPER" ]]; then
  bash "$CI_JOURNEY_PROGRESS_HELPER" start || true
fi

GRADLEW="$REPO_ROOT/gradlew"

# The Toxiproxy-backed proofs. This is primarily the NetworkFaultProofBase
# subclasses, plus explicitly documented fixture users such as issue #1733.
# Keep the subclasses in sync with
# `grep -rl NetworkFaultProofBase app/src/androidTest/.../proof/`.
FQCN_PREFIX="com.pocketshell.app.proof"
NETWORK_FAULT_CLASSES=(
  "$FQCN_PREFIX.RideThroughInterruptionE2eTest"
  "$FQCN_PREFIX.WithinGraceResumeRideThroughE2eTest"
  "$FQCN_PREFIX.StaleLeaseSwitchRecoveryE2eTest"
  "$FQCN_PREFIX.DisconnectFlapSoakE2eTest"
  "$FQCN_PREFIX.DisconnectBlackholeE2eTest"
  "$FQCN_PREFIX.NetworkLatencyModelE2eTest"
  "$FQCN_PREFIX.PacketLossNetworkFaultE2eTest"
  # Issue #1866 / #1733: durable queued-attachment correctness under real app
  # SSH-worker death. The class hard-asserts the explicit Toxiproxy opt-in, so
  # selecting it in phase 1 (which has no opt-in) is a guaranteed fixture red.
  # Deliberate decision: this IS release-GATING in phase 2 because checkpoint-N
  # resume, SHA-identical atomic promotion, and once-only prompt+Enter are
  # durable-queue safety invariants, not an expected-fail/TDD spec. It reuses
  # network-fault-proxy:2228 + toxiproxy API:8474; no fixture or unrelated
  # nightly scope is added.
  "$FQCN_PREFIX.OutboundAttachmentOffsetResumeJourneyE2eTest"
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
  # Issue #1681 (#1680 Track B / H1): the DETERMINISTIC mobile-network
  # self-inflicted lease-close storm reproduction. A toxiproxy symmetric-latency
  # profile (RTT ≈ 4.0s mobile pin, ≈ 150ms wifi pin) makes a confirmed-shell
  # `agents kind` classify overrun the real 3.5s bounded-exec budget over the
  # SHARED `-CC` lease, while an UN-PROXIED sentinel proves the host stayed healthy
  # (so any drop is self-inflicted by construction — G6). RED on the pre-#1641
  # close()-on-timeout shim (passive_disconnect classification=
  # real_tmux_control_channel_closed); GREEN on merged #1641 (bounded_exec_timeout
  # abandonment, status stays Connected). Self-skips per-push (needs
  # network-fault-proxy:2228 + toxiproxy API:8474 which tests.yml leaves down), so
  # it is enrolled here with its toxiproxy siblings. Reuses network-fault-proxy:2228
  # + toxiproxy API:8474 (no new fixture).
  "$FQCN_PREFIX.MobileLatencyStormSelfInflictedCloseE2eTest"
)

# ---------------------------------------------------------------------------
# EXPECTED-FAIL lane (issue #1201, de-gated from the fault verdict).
#
# Issue #2111 widened this lane's remit slightly: it is the home for any
# toxiproxy-fixture proof that must RUN nightly and have its artifacts collected
# while its exit code is not yet trustworthy as a release signal — whether that
# is a TDD spec for an unbuilt feature (#822 below) or a budget that the emulator
# cannot meet (#2111's Conversation-open latency proof below). The promotion rule
# is the same for both: move it into NETWORK_FAULT_CLASSES once it runs green.
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
  # Issue #2111 (audit §2.1 item 2): the #817/#828 network-realistic
  # Conversation-open latency proof. It is not a NetworkFaultProofBase subclass
  # (hence the fully-qualified name), but it is a toxiproxy fixture user with
  # identical preconditions: it drives the production open path through
  # network-fault-proxy:2228 with a symmetric-latency toxic and HARD-asserts the
  # two #828 gates in TWO SEPARATE @Test methods — (a)
  # `recordedClaudeColdOpenMeetsPhoneBudgetAtGoodRtt`: cold
  # `conversation_open_full` < 300 ms at 80 ms RTT, and (b)
  # `recordedClaudeFirstWindowIsPrefetchedUnderRealisticRtt`: the window-read leg
  # collapsed to ~0 (at BOTH 150 ms and 80 ms RTT) because the first window is
  # prefetched in the resolve exec. Two methods, not two asserts in one method,
  # because (a) is known-red here (below) and would otherwise abort the method
  # before (b) — the structural guard — ever ran, making a #828 regression
  # indistinguishable from the standing environmental failure. Split, a #828
  # regression changes this phase's FAILURE SET: 1 failing test becomes 2.
  # It guards exactly what AGENTS.md warns about
  # ("do not land #818 on localhost-only timings; localhost zero-RTT hides the
  # whole cost"). It reuses network-fault-proxy:2228 + toxiproxy API:8474 — no
  # new fixture.
  #
  # Before this enrolment it executed on NO lane at all: nightly phase 1 selected
  # it WITHOUT the `pocketshellNetworkFaultProofs` opt-in so its `assumeTrue`
  # skipped it, and its `assumeFalse(isRunningOnCi())` skipped it per-push. Both
  # self-skips are now gone (the opt-in is a HARD assert in the class), so the
  # only question was WHICH nightly phase it belongs in.
  #
  # It goes in the NON-GATING lane, and the reason is a measurement, not a
  # preference. Running it for the first time (2026-08-13, this issue, emulator +
  # the real toxiproxy fixture) shows assertion (b) is solidly satisfied —
  # window-read leg = 0 ms at both 150 ms and 80 ms RTT, i.e. #828's fold is
  # intact — while assertion (a) is NOT met on an emulator at ANY RTT: measured
  # `conversation_open_full` was 840 / 429 / 753 / 842 ms at 80 ms RTT across four
  # runs, and a control pass at 10 ms RTT still measured 406 ms. So the
  # device-side fixed cost on this AVD is ~400 ms and the <0.3 s budget — a
  # PHONE-class target — is unreachable there regardless of the network. Making
  # this phase GATING would therefore turn the nightly fault verdict (and with it
  # the release gate) permanently red for an environmental reason, while
  # weakening the 300 ms budget to make it pass is explicitly forbidden
  # (#2111 non-goal). Neither is acceptable, so it runs here: the proof EXECUTES
  # every night against the real fixture, its per-method timing artifacts
  # (`issue817-conversation-open-rtt-timing-<method>.txt`) are uploaded, and its
  # status is shown in the summary — but its exit code stays informational.
  #
  # READ THIS PHASE'S RESULT PER-METHOD, NOT AS ONE EXIT CODE. The expected
  # steady state is exactly ONE failing method here (the budget). If
  # `recordedClaudeFirstWindowIsPrefetchedUnderRealisticRtt` ever joins it, #828's
  # prefetch fold has regressed and the cold open grew a second serial SSH
  # round-trip — that is a real product regression hiding in an informational
  # lane, so the per-method report matters more than the phase's exit code.
  #
  # PROMOTE IT INTO `NETWORK_FAULT_CLASSES` the moment BOTH methods run green,
  # exactly as this lane's #1201 protocol says. That needs a follow-up decision
  # on the <0.3 s gate (make the budget device-class-aware, or cut the ~400 ms of
  # device-side open cost); it is out of scope for #2111, which is about making
  # the test run at all.
  "com.pocketshell.app.tmux.ConversationOpenLatencyRttDockerTest"
)

# The bootstrap setup-scenario class (opt-in via pocketshellBootstrapScenarios).
# Run as a trimmed slice in its own phase; excluded from the journey phase so it
# does not just self-skip there (the journey phase never passes the opt-in flag).
BOOTSTRAP_TEST_CLASS="com.pocketshell.app.bootstrap.HostBootstrapScenarioSuiteTest"
NOTIFICATION_PERMISSION_TEST_CLASS="com.pocketshell.app.notifications.NoNotificationPromptOnAppOpenE2eTest"

# Issue #2111 (audit §2.1 item 4 — the cadence gap): the real-agent release gate.
# It drives the REAL `claude` and `codex` binaries in a tmux pane through the app
# and asserts on visible terminal output + the on-disk JSONL each CLI writes, so
# it is the only guard against a real-CLI rendering/parsing regression. Until now
# it ran ONLY under `TERMINAL_RELEASE_GATE=1 scripts/release-emulator-validation.sh`
# — i.e. a regression stayed invisible until someone cut a release, which can be
# weeks. It is opt-in via `pocketshellRealAgentReleaseGate=1` and needs the
# separate `tests/docker/real-agent/compose.yml` fixture on port 2240 (which the
# nightly workflow now starts). That image ships deliberately WITHOUT API keys and
# the assertions are the credential-free deterministic strings ("Not logged in",
# the Codex banner), so no secret is required to run it here.
REAL_AGENT_TEST_CLASS="$FQCN_PREFIX.RealAgentReleaseGateTest"
REAL_AGENT_COMPOSE_FILE="$REPO_ROOT/tests/docker/real-agent/compose.yml"

# The bootstrap scenarios run nightly. Selected by JUnit method name via
# `class=<FQCN>#<method>,<FQCN>#<method>`.
#
# Issue #2111 (audit §2.1 item 3): this list used to hold FOUR of the class's TEN
# scenarios, so six scenarios executed on NO lane — the class is excluded from
# phase 1 (it would only self-skip there without the opt-in) and phase 3 selected
# by name. Breaking the uv-upgrade-failure recovery sheet or the fish-PATH
# bootstrap reddened nothing. All ten now run. Every fixture they need is already
# defined in `tests/docker/docker-compose.yml` and is started by
# `.github/workflows/nightly-extensive.yml`.
BOOTSTRAP_METHODS=(
  # Issue #667: the first-run `ready` profile, the `uvInstall` first-install
  # journey, and the `appUpdateRequired` (remote-newer) journey.
  "ready"
  "uvInstall"
  "appUpdateRequired"
  # Issue #1236 (D26): the silent-host notifications journey — CLI + tmux ready
  # but the agent stop/idle hooks off; asserts the one-tap enable folds in a
  # NON-DESTRUCTIVE `pocketshell hooks install` (pre-existing foreign hook
  # survives). Needs the bootstrap-notifications:2241 fixture (brought up below).
  "notifications"
  # --- Issue #2111: the six scenarios that previously ran nowhere. ------------
  # FAILURE PATHS — the ones the audit called out by name. A successful install
  # is the easy half; what a user actually hits is the install that FAILS, and
  # the recovery sheet is the only thing standing between them and a dead host.
  # `uvUpgradeFailure` asserts the failed-upgrade sheet names the path, the
  # remote/expected versions, the exact failing `uv tool install` command and the
  # fixture's stderr, and that the old CLI is left in place (bootstrap-uv-upgrade
  # :2236, already up for uvUpgrade/appUpdateRequired — no new fixture).
  "uvUpgradeFailure"
  # `unsupported` is the no-installer host: the sheet must offer the MANUAL
  # `uv tool install ... or pipx install pocketshell` instruction and the Install
  # attempt must fail closed with a reachable Close action (bootstrap-unsupported
  # :2232).
  "unsupported"
  # `daemonDisabled` proves the OPTIONAL jobs daemon is genuinely optional: the
  # host navigates normally and bootstrap must NOT enable it behind the user's
  # back (bootstrap-daemon-disabled:2233).
  "daemonDisabled"
  # SUCCESS PATH the failure path is meaningless without: `uvUpgrade` is the
  # working CLI-update journey AND the only proof of the #779 one-clear-action
  # rule (badge "Outdated" + a single "Update" button; the synonym verb "Upgrade"
  # must not appear as a control). Reuses bootstrap-uv-upgrade:2236.
  "uvUpgrade"
  # PATH DISCOVERY — `pocketshell` installed in `~/.local/bin` rather than on the
  # default non-login PATH, under bash (`userLocalPath`, bootstrap-user-local-path
  # :2234) and under fish, whose login-PATH handling is different again
  # (`fishUserLocalPath`, bootstrap-fish-user-local-path:2235). This is the exact
  # shape of the v0.4.10 connect break (AGENTS.md: the host `pocketshell` lives in
  # `~/.local/bin`, where `PocketshellCommand.wrap()` is multi-statement), so it
  # is real coverage, not a completeness box-tick.
  #
  # HONEST SCOPE, established by mutation (#2111): mutating the fish login-PATH
  # probe body ALONE kills 0 tests — `fishUserLocalPath` stays green — because
  # `HostBootstrapper.detectCommonToolPath` probes `$HOME/.local/bin/pocketshell`
  # by ABSOLUTE path, independent of PATH, so the tool is still found. So what
  # these two scenarios guard is "a bash/fish host whose pocketshell lives in
  # ~/.local/bin bootstraps end-to-end", carried by the `COMMON_TOOL_DIRS`
  # fallback — NOT the per-shell probe body in isolation. Mutating the whole
  # ~/.local/bin bootstrap (the three login-PATH probe bodies + the
  # `pathAwareCommand` fallback + `COMMON_TOOL_DIRS`) reddens exactly
  # userLocalPath + fishUserLocalPath + uvInstall and nothing else.
  "userLocalPath"
  "fishUserLocalPath"
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
  # Issue #2111: still excluded from phase 1 (which passes no opt-in, so it would
  # only self-skip there); it now runs in its OWN phase 4 with the opt-in + the
  # real-agent:2240 fixture.
  "$REAL_AGENT_TEST_CLASS"
  "$BOOTSTRAP_TEST_CLASS"
  "$NOTIFICATION_PERMISSION_TEST_CLASS"
)

join_by() {
  local IFS="$1"
  shift
  echo "$*"
}

NETWORK_FAULT_CLASS_ARG="$(join_by , "${NETWORK_FAULT_CLASSES[@]}")"
EXPECTED_FAIL_CLASS_ARG="$(join_by , "${EXPECTED_FAIL_CLASSES[@]}")"
JOURNEY_NOTCLASS_ARG="$(join_by , "${JOURNEY_EXCLUDED_CLASSES[@]}")"

# Issue #1751: a class-level phase can green vacuously if the sustained method is
# renamed/removed while the class's intentionally skipped brief method remains.
# Pin the exact successful release-gating method and its positive-band artifact
# before computing the fault verdict.
REQUIRED_SUSTAINED_FAULT_CLASS="$FQCN_PREFIX.RideThroughInterruptionE2eTest"
REQUIRED_SUSTAINED_FAULT_METHOD="sustainedLinkCutReconnectsCleanlyWithoutHang"
# shellcheck source=scripts/lib/nightly-exact-method-guard.sh
source "$REPO_ROOT/scripts/lib/nightly-exact-method-guard.sh"

# The machine-readable fault-verdict helper (issue #1201): pure PASS/FAIL from the
# network-fault + bootstrap phases ONLY (never the journey suite or the
# expected-fail lane). Written to a file the CI fault-verdict job reads.
# Issue #2141: skipped / unreached gating members are assessed from the
# preserved phase-2 report and refuse fault_verdict=PASS. #2141 owns
# visibility of that hole; #1678 owns deleting the brief-cut Assume.
# shellcheck source=scripts/lib/nightly-fault-verdict.sh
source "$REPO_ROOT/scripts/lib/nightly-fault-verdict.sh"
FAULT_VERDICT_FILE="$ARTIFACT_DIR/fault-verdict.txt"
FAULT_COVERAGE_FILE="$ARTIFACT_DIR/fault-gating-coverage.txt"

# Per-phase test-report preservation (issue #1293): each phase below is a
# SEPARATE `:app:connectedDebugAndroidTest` invocation that writes its JUnit
# XML + HTML report + pulled device output to the SAME default `app/build/...`
# paths, so a later phase CLOBBERS an earlier phase's report before the workflow
# uploads artifacts. `preserve_phase_reports <slug>` snapshots each phase's
# report into `$ARTIFACT_DIR/phase-reports/<slug>/` (already inside the uploaded
# `artifacts/nightly-extensive/` tree) IMMEDIATELY after the phase runs. This is
# OBSERVABILITY ONLY — it copies reports, never runs gradle and never touches
# any phase's pass/fail exit code (it is called AFTER each `$?` capture and
# always returns 0).
# shellcheck source=scripts/lib/nightly-phase-reports.sh
source "$REPO_ROOT/scripts/lib/nightly-phase-reports.sh"
PHASE_REPORTS_DIR="$ARTIFACT_DIR/phase-reports"
APP_BUILD_DIR="$REPO_ROOT/app/build"

# Issue #1991: distinguish a substantive assertion from UTP assigning an empty
# test failure because its APK-installer teardown lost an offline device. The
# classifier reads only preserved phase evidence and never retries/restarts.
# shellcheck source=scripts/lib/nightly-phase-classification.sh
source "$REPO_ROOT/scripts/lib/nightly-phase-classification.sh"
PHASE_CLASSIFICATIONS_DIR="$ARTIFACT_DIR/phase-classifications"

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

# Choose the observer outside the phase-1 block. select-test-areas.sh slices
# `/phase 1: journey\/E2E/,/^JOURNEY_EXIT=/` and requires wholesale-minus-notClass
# with JOURNEY_EXIT= at column 0; indenting the assignment widens the slice into
# later class= phases and falsely flags phase 1 as an allowlist.
if [[ -f "$CI_JOURNEY_PROGRESS_HELPER" ]]; then
  PHASE1_OBSERVER=(bash "$CI_JOURNEY_PROGRESS_HELPER" observe-stream)
else
  PHASE1_OBSERVER=(cat)
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
  --stacktrace 2>&1 | "${PHASE1_OBSERVER[@]}"
JOURNEY_EXIT=${PIPESTATUS[0]}
echo "phase 1 (journey/E2E) exit code: $JOURNEY_EXIT"

# Snapshot phase 1's report BEFORE the phase-2 gradle invocation overwrites it
# (issue #1293). Observability only — never affects JOURNEY_EXIT.
preserve_phase_reports "phase1-journey" "$APP_BUILD_DIR" "$PHASE_REPORTS_DIR"
write_nightly_phase_classification \
  "$PHASE_CLASSIFICATIONS_DIR/phase1-journey.txt" \
  "phase1-journey" "$JOURNEY_EXIT" "$PHASE_REPORTS_DIR/phase1-journey"
capture_nightly_device_boundary \
  "$PHASE_CLASSIFICATIONS_DIR/phase1-journey-device.txt" "phase1-journey"
JOURNEY_CLASSIFICATION="$(
  classify_nightly_phase "$JOURNEY_EXIT" "$PHASE_REPORTS_DIR/phase1-journey"
)"

# Default the dedicated/aux phases to SKIPPED; only shard 0 owns them.
NOTIFICATION_PERMISSION_EXIT=0
NETWORK_FAULT_EXIT=0
BOOTSTRAP_EXIT=0
EXPECTED_FAIL_EXIT=0
REAL_AGENT_EXIT=0
notification_permission_status="SKIP"
notification_permission_executed=0
nf_status="SKIP"
bootstrap_status="SKIP"
expectedfail_status="SKIP"
real_agent_status="SKIP"
notification_permission_classification="SKIP"
nf_classification="SKIP"
bootstrap_classification="SKIP"
expectedfail_classification="SKIP"
real_agent_classification="SKIP"

if [[ "$RUN_AUX_PHASES" == "yes" ]]; then
  echo "=========================================================="
  echo "Nightly Extensive Tests — phase 1b: notification permission (NON-GATING)"
  echo "Included class: $NOTIFICATION_PERMISSION_TEST_CLASS"
  echo "  (dedicated unsharded invocation; external denied-permission fixture)"
  echo "=========================================================="

  notification_permission_log="$ARTIFACT_DIR/phase1b-notification-permission.log"
  "$REPO_ROOT/scripts/connected-test.sh" --no-pool \
    --deny-notifications-before-instrumentation \
    -Pandroid.testInstrumentationRunnerArguments.pocketshellCi=true \
    -Pandroid.testInstrumentationRunnerArguments.timeout_msec=300000 \
    -Pandroid.testInstrumentationRunnerArguments.class="$NOTIFICATION_PERMISSION_TEST_CLASS" \
    --stacktrace 2>&1 | tee "$notification_permission_log"
  NOTIFICATION_PERMISSION_EXIT=${PIPESTATUS[0]}
  notification_permission_executed="$(
    sed -n 's/^NOTIFICATION_PERMISSION_TEST_RESULT executed=\([0-9][0-9]*\) .*/\1/p' \
      "$notification_permission_log" | tail -1
  )"
  notification_permission_executed="${notification_permission_executed:-0}"
  echo "phase 1b (notification permission) exit code: $NOTIFICATION_PERMISSION_EXIT"
  echo "phase 1b (notification permission) executed tests: $notification_permission_executed"

  # Snapshot before phase 2 overwrites the connected-test report. The wrapper
  # itself hard-fails zero tests, skips, failures, a missing named method, or a
  # runner crash, so this copy is always a non-vacuous report on green.
  preserve_phase_reports \
    "phase1b-notification-permission" \
    "$APP_BUILD_DIR" \
    "$PHASE_REPORTS_DIR"
  write_nightly_phase_classification \
    "$PHASE_CLASSIFICATIONS_DIR/phase1b-notification-permission.txt" \
    "phase1b-notification-permission" "$NOTIFICATION_PERMISSION_EXIT" \
    "$PHASE_REPORTS_DIR/phase1b-notification-permission"
  capture_nightly_device_boundary \
    "$PHASE_CLASSIFICATIONS_DIR/phase1b-notification-permission-device.txt" \
    "phase1b-notification-permission"
  notification_permission_classification="$(
    classify_nightly_phase \
      "$NOTIFICATION_PERMISSION_EXIT" \
      "$PHASE_REPORTS_DIR/phase1b-notification-permission"
  )"
  notification_permission_status="$(
    nightly_phase_status "$notification_permission_classification"
  )"

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

  if ! require_exact_junit_method \
    "$APP_BUILD_DIR/outputs/androidTest-results/connected" \
    "$APP_BUILD_DIR/outputs/connected_android_test_additional_output" \
    "$REQUIRED_SUSTAINED_FAULT_CLASS" \
    "$REQUIRED_SUSTAINED_FAULT_METHOD"; then
    NETWORK_FAULT_EXIT=1
    echo "phase 2 (network-fault proofs) forced RED by exact-method guard"
  fi

  # Snapshot phase 2's report BEFORE the phase-2b gradle invocation overwrites
  # it (issue #1293). THIS is the release-GATING report whose overwrite made the
  # DisconnectBlackhole / NatIdle failing assertions unrecoverable. Observability
  # only — never affects NETWORK_FAULT_EXIT.
  preserve_phase_reports "phase2-network-fault" "$APP_BUILD_DIR" "$PHASE_REPORTS_DIR"
  # Issue #2141: compare the preserved phase-2 JUnit XML to NETWORK_FAULT_CLASSES
  # AFTER the snapshot (later phases overwrite app/build). A skipped method or
  # a truncated class set is recorded here and refuses fault_verdict=PASS.
  # Read the snapshot, not the live report path — phase 2b/3/4 clobber it.
  assess_fault_gating_coverage \
    "$PHASE_REPORTS_DIR/phase2-network-fault" \
    "${NETWORK_FAULT_CLASSES[@]}" \
    > "$FAULT_COVERAGE_FILE" || true
  echo "phase 2 (network-fault proofs) gating coverage:"
  cat "$FAULT_COVERAGE_FILE"
  write_nightly_phase_classification \
    "$PHASE_CLASSIFICATIONS_DIR/phase2-network-fault.txt" \
    "phase2-network-fault" "$NETWORK_FAULT_EXIT" \
    "$PHASE_REPORTS_DIR/phase2-network-fault"
  capture_nightly_device_boundary \
    "$PHASE_CLASSIFICATIONS_DIR/phase2-network-fault-device.txt" \
    "phase2-network-fault"
  nf_classification="$(
    classify_nightly_phase \
      "$NETWORK_FAULT_EXIT" "$PHASE_REPORTS_DIR/phase2-network-fault"
  )"

  echo "=========================================================="
  echo "Nightly Extensive Tests — phase 2b: expected-fail lane (NON-GATING)"
  echo "Included classes: $EXPECTED_FAIL_CLASS_ARG"
  echo "  (pocketshellNetworkFaultProofs=true; result is INFORMATIONAL ONLY and"
  echo "   is DELIBERATELY excluded from the fault verdict — issue #1201. Holds"
  echo "   the #822 TDD specs for unbuilt Slice C/D features AND the #2111"
  echo "   Conversation-open latency proof, whose <0.3s budget an emulator cannot"
  echo "   meet; each is promoted to the GATING phase 2 once it runs green.)"
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
  echo "phase 2b (expected-fail lane) exit code: $EXPECTED_FAIL_EXIT (NON-GATING)"

  # Snapshot phase 2b's report BEFORE the phase-3 gradle invocation overwrites it
  # (issue #1293). Observability only — never affects EXPECTED_FAIL_EXIT.
  preserve_phase_reports "phase2b-expected-fail" "$APP_BUILD_DIR" "$PHASE_REPORTS_DIR"
  write_nightly_phase_classification \
    "$PHASE_CLASSIFICATIONS_DIR/phase2b-expected-fail.txt" \
    "phase2b-expected-fail" "$EXPECTED_FAIL_EXIT" \
    "$PHASE_REPORTS_DIR/phase2b-expected-fail"
  capture_nightly_device_boundary \
    "$PHASE_CLASSIFICATIONS_DIR/phase2b-expected-fail-device.txt" \
    "phase2b-expected-fail"
  expectedfail_classification="$(
    classify_nightly_phase \
      "$EXPECTED_FAIL_EXIT" "$PHASE_REPORTS_DIR/phase2b-expected-fail"
  )"

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

  # Snapshot phase 3's report too (issue #1293). It is the LAST aux phase so its
  # report currently survives at the default path, but snapshotting it keeps the
  # per-phase set complete + uniform (and future-proofs adding a phase 4).
  # Observability only — never affects BOOTSTRAP_EXIT.
  preserve_phase_reports "phase3-bootstrap" "$APP_BUILD_DIR" "$PHASE_REPORTS_DIR"
  write_nightly_phase_classification \
    "$PHASE_CLASSIFICATIONS_DIR/phase3-bootstrap.txt" \
    "phase3-bootstrap" "$BOOTSTRAP_EXIT" "$PHASE_REPORTS_DIR/phase3-bootstrap"
  capture_nightly_device_boundary \
    "$PHASE_CLASSIFICATIONS_DIR/phase3-bootstrap-device.txt" "phase3-bootstrap"
  bootstrap_classification="$(
    classify_nightly_phase "$BOOTSTRAP_EXIT" "$PHASE_REPORTS_DIR/phase3-bootstrap"
  )"

  echo "=========================================================="
  echo "Nightly Extensive Tests — phase 4: real-agent CLI gate (opt-in, issue #2111)"
  echo "Included class: $REAL_AGENT_TEST_CLASS"
  echo "  (pocketshellRealAgentReleaseGate=1, real-agents:2240 fixture)"
  echo "=========================================================="

  # Issue #2111 (audit item 4): close the cadence gap. This phase's exit code
  # feeds `overall_status` (so a real-CLI rendering regression makes the nightly
  # shard RED and is visible the next morning) but is DELIBERATELY NOT an input
  # to the machine-readable fault verdict: #1201 fixed that verdict's inputs to
  # the network-fault + bootstrap phases, and widening it here would change the
  # release-gating signal, which is out of this issue's scope. The release gate
  # (`TERMINAL_RELEASE_GATE=1 scripts/release-emulator-validation.sh`) remains
  # the authoritative pre-tag run; this is the nightly early-warning copy.
  #
  # The fixture lives in its own compose file, so it is only exercised when the
  # workflow actually started it. If port 2240 is not up the phase is recorded
  # SKIP rather than a phantom red — the tests themselves fail loudly on an
  # unreachable fixture, so this check only distinguishes "not provisioned on
  # this runner" from "provisioned and broken".
  if [[ -f "$REAL_AGENT_COMPOSE_FILE" ]] && \
     timeout 2 bash -c 'exec 3<>/dev/tcp/127.0.0.1/2240' 2>/dev/null; then
    "$GRADLEW" :app:connectedDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.pocketshellRealAgentReleaseGate=1 \
      -Pandroid.testInstrumentationRunnerArguments.class="$REAL_AGENT_TEST_CLASS" \
      --stacktrace
    REAL_AGENT_EXIT=$?
    echo "phase 4 (real-agent CLI gate) exit code: $REAL_AGENT_EXIT"

    preserve_phase_reports "phase4-real-agent" "$APP_BUILD_DIR" "$PHASE_REPORTS_DIR"
    write_nightly_phase_classification \
      "$PHASE_CLASSIFICATIONS_DIR/phase4-real-agent.txt" \
      "phase4-real-agent" "$REAL_AGENT_EXIT" "$PHASE_REPORTS_DIR/phase4-real-agent"
    capture_nightly_device_boundary \
      "$PHASE_CLASSIFICATIONS_DIR/phase4-real-agent-device.txt" "phase4-real-agent"
    real_agent_classification="$(
      classify_nightly_phase "$REAL_AGENT_EXIT" "$PHASE_REPORTS_DIR/phase4-real-agent"
    )"
  else
    echo "phase 4 (real-agent CLI gate) SKIPPED: real-agents:2240 fixture is not up on this runner"
    real_agent_classification="SKIP"
  fi

  nf_status="$(nightly_phase_status "$nf_classification")"
  bootstrap_status="$(nightly_phase_status "$bootstrap_classification")"
  expectedfail_status="$(nightly_phase_status "$expectedfail_classification")"
  if [[ "$real_agent_classification" == "SKIP" ]]; then
    real_agent_status="SKIP"
  else
    real_agent_status="$(nightly_phase_status "$real_agent_classification")"
  fi

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
    "$expectedfail_status" "$EXPECTED_FAIL_EXIT" \
    "$FAULT_COVERAGE_FILE"
  fault_verdict="$(grep -E '^fault_verdict=' "$FAULT_VERDICT_FILE" | head -1 | cut -d= -f2)"
  echo "----------------------------------------------------------"
  echo "Fault-injection safety verdict (issue #1201) -> $fault_verdict"
  cat "$FAULT_VERDICT_FILE"
  echo "----------------------------------------------------------"
else
  echo "=========================================================="
  echo "Nightly Extensive Tests — phases 1b, 2, 2b, 3 & 4 SKIPPED on shard ${SHARD_INDEX:-0}"
  echo "  (notification + network-fault + expected-fail + bootstrap + real-agent"
  echo "   run once, on shard 0)"
  echo "=========================================================="
fi

journey_status="$(nightly_phase_status "$JOURNEY_CLASSIFICATION")"

# `overall_status` is the human/summary verdict for the whole extensive shard. It
# includes the journey suite and both GATING fault phases, but NOT the #822
# expected-fail lane (phase 2b) — including an intentionally-red TDD lane would
# make the shard summary permanently red for a non-reason. Note: `overall_status`
# is NOT the release-gating signal; the machine-readable fault verdict above is.
#
# Issue #2111: phase 4 (real-agent CLI gate) IS included here — that is the whole
# point of closing the cadence gap: a real Claude/Codex rendering regression must
# make the nightly shard red the next morning instead of waiting for a release
# cut. It is still NOT an input to the release-gating fault verdict above.
overall_status="PASS"
if [[ "$JOURNEY_EXIT" -ne 0 \
      || "$NOTIFICATION_PERMISSION_EXIT" -ne 0 \
      || "$NETWORK_FAULT_EXIT" -ne 0 \
      || "$BOOTSTRAP_EXIT" -ne 0 \
      || "$REAL_AGENT_EXIT" -ne 0 ]]; then
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
  echo "| Journey / E2E (non-gating) | full connected suite minus network-fault + expected-fail + opt-in classes ($shard_label) | \`pocketshellCi=true\` | $JOURNEY_EXIT | **$journey_status** ($JOURNEY_CLASSIFICATION) |"
  echo "| Notification permission (NON-GATING) | dedicated unsharded $NOTIFICATION_PERMISSION_TEST_CLASS; executed=$notification_permission_executed | external revoke/verify before instrumentation; external grant/verify after | $NOTIFICATION_PERMISSION_EXIT | **$notification_permission_status** ($notification_permission_classification) |"
  echo "| Network-fault proofs (GATING) | ${#NETWORK_FAULT_CLASSES[@]} Toxiproxy-backed classes | \`pocketshellNetworkFaultProofs=true\` (no pocketshellCi) | $NETWORK_FAULT_EXIT | **$nf_status** ($nf_classification) |"
  echo "| Expected-fail lane (NON-GATING) | ${#EXPECTED_FAIL_CLASSES[@]} class(es): #822 Slice C/D TDD specs + the #2111 Conversation-open latency proof | \`pocketshellNetworkFaultProofs=true\` | $EXPECTED_FAIL_EXIT | **$expectedfail_status** ($expectedfail_classification) |"
  echo "| Bootstrap setup scenarios (GATING) | ALL ${#BOOTSTRAP_METHODS[@]} HostBootstrapScenarioSuiteTest methods (issue #2111) | \`pocketshellBootstrapScenarios=true\` | $BOOTSTRAP_EXIT | **$bootstrap_status** ($bootstrap_classification) |"
  echo "| Real-agent CLI gate (issue #2111; in overall_status, NOT in the fault verdict) | $REAL_AGENT_TEST_CLASS against real-agents:2240 | \`pocketshellRealAgentReleaseGate=1\` | $REAL_AGENT_EXIT | **$real_agent_status** ($real_agent_classification) |"
  echo
  echo "**Extensive-shard overall (non-gating summary): $overall_status**"
  echo
  echo "Per-phase test reports (issue #1293) are preserved under"
  echo "\`artifacts/nightly-extensive/phase-reports/<phase>/\` so a later phase no"
  echo "longer overwrites an earlier phase's JUnit XML / HTML report / device"
  echo "output before the artifact upload. Read the GATING phase-2 assertions at"
  echo "\`artifacts/nightly-extensive/phase-reports/phase2-network-fault/\`."
  echo "Phase classifications and read-only adb boundary evidence are under"
  echo "\`artifacts/nightly-extensive/phase-classifications/\`."
  echo
  echo "## Fault-injection safety verdict (issue #1201 — the RELEASE-GATING signal)"
  echo
  if [[ "$RUN_AUX_PHASES" == "yes" ]]; then
    echo "\`fault_verdict\` = network-fault ($nf_status) + bootstrap ($bootstrap_status) ONLY,"
    echo "then refuse PASS when the phase-2 gating set is skipped or truncated (#2141)."
    echo "The journey suite and the #822 expected-fail lane are DELIBERATELY excluded."
    echo "#1678 owns deleting the brief-cut Assume; #2141 only makes that hole visible."
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
  echo "Expected-fail lane (NON-GATING, tracked only — #822 TDD specs for unbuilt"
  echo "Slice C/D, plus the #2111 Conversation-open latency proof whose <0.3s budget"
  echo "an emulator cannot meet; each is promoted to the GATING phase once green):"
  for c in "${EXPECTED_FAIL_CLASSES[@]}"; do
    echo "- \`$c\`"
  done
  echo
  echo "Bootstrap setup scenarios exercised (\`$BOOTSTRAP_TEST_CLASS\`, GATING):"
  for m in "${BOOTSTRAP_METHODS[@]}"; do
    echo "- \`$m\`"
  done
  echo
  echo "Real-agent CLI gate (issue #2111 — nightly cadence for the real"
  echo "Claude/Codex rendering + JSONL parsing journeys; the release gate"
  echo "\`TERMINAL_RELEASE_GATE=1 scripts/release-emulator-validation.sh\` remains"
  echo "the authoritative pre-tag run):"
  echo "- \`$REAL_AGENT_TEST_CLASS\`"
} > "$SUMMARY"

echo "----------------------------------------------------------"
cat "$SUMMARY"
echo "----------------------------------------------------------"

if [[ -f "${CI_JOURNEY_PROGRESS_HELPER:-}" ]]; then
  bash "$CI_JOURNEY_PROGRESS_HELPER" suite-completed "$overall_status" || true
fi

if [[ "$overall_status" == "FAIL" ]]; then
  exit 1
fi
exit 0
