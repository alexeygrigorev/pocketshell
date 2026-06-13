#!/usr/bin/env bash
# Per-push CI journey suite — load-bearing subset (issue #691, epic #657).
#
# The #638 mandate: the load-bearing session-switch / reconnect JOURNEY proofs
# must run in REGULAR per-push CI, not only nightly + the release gate. Before
# this, `tests.yml` ran no `androidTest` at all, so a PR could merge green while
# silently regressing within-grace ride-through, beyond-grace reattach, the
# A->B->C->A no-bleed switch, no-resurrect, and reseed-on-reattach — exactly the
# #685 failure mode.
#
# This driver runs inside the `reactivecircus/android-emulator-runner`
# `script:` step, so the emulator is already booted and `adb` is on PATH. The
# deterministic Docker `agents` fixture (agents:2222 -> emulator 10.0.2.2:2222)
# was started + waited-healthy by the workflow before this script runs.
#
# It runs ONLY the load-bearing subset (NOT the full connected suite — that
# stays nightly). Every class below uses the plain deterministic `agents:2222`
# fixture; NONE need the opt-in toxiproxy network-fault proxies, so the job is
# deterministic and does not depend on the proxy family the per-push job
# deliberately leaves down. `pocketshellCi=true` selects the generous E2E
# timeout ceilings so a slow CI runner does not flake these out.
#
# Load-bearing subset and what each pins:
#   * DeepLinkSessionSwitchE2eTest (issue #470)
#       - picker-FREE programmatic attach via the production deep-link intent
#         (MainActivity.EXTRA_OPEN_SESSION_*): lands directly on
#         AppDestination.TmuxSession so TmuxSessionScreen auto-connects via the
#         real `tmux -CC` attach. Asserts per attach: terminal view attaches
#         (NEVER black/blank), the attached session's own seed marker is visible
#         (correct/non-stale/re-seeded), no Disconnected band/EOF, and the OTHER
#         session's marker does not bleed in. The A->B "switch" is a fresh
#         deep-link to B. This is the load-bearing terminal-attach/switch proof.
#
# PICKER-DRIVEN journeys (issue #705): the shared session-PICKER enumeration
# (FolderListGateway.listSessionsWithFolder over the warm SSH lease) was WEDGED
# on the AVD — every test that taps a host row and waited on
# `waitForSessionInPicker` stalled (picker never left Loading). That wedge is
# now FIXED (the lease-bound cold connect/handshake of #687 + the picker fix in
# #702), and the over-broad header assertion that collided with the #628
# previous-session toggle controls is corrected in #705. So three picker-driven
# journeys (MultiSessionSwitchJourneyE2eTest,
# ColdRestoreGoneSessionNoResurrectE2eTest, ReconnectRepaintE2eTest) re-join
# this per-push subset below — each was proven GREEN on a pooled AVD before
# re-adding (no flake, no CI email spam).
#
# RE-ADDED (issue #707): BackgroundGraceReconnectE2eTest now re-joins this subset.
# The triage (#707) established the within-grace `terminal_foreground_reattach`
# diagnostic was a BENIGN fan-out marker, not a real reconnect: since #548 /
# commit 1271a60e, App.kt fires dispatchTmuxForeground() unconditionally on every
# foreground (even within grace) so a stale transport is probed early, and that
# call records `terminal_foreground_reattach` purely as a dispatch label. The
# E2E `assertNoReconnectOrReattachDiagnostics` is now narrowed to forbid only
# the GENUINE reconnect signals (reconnect_start, network_reconnect_start, the
# VM-level foreground_reattach, foreground_runtime_probe_failed,
# terminal_background_teardown) — none of which fire within grace — so both its
# tests are green. No production code changed; the triage confirmed the #685
# grace path is correct.
#
# The heavy/long-running + toxiproxy network-fault + bootstrap-matrix suites
# stay in nightly-extensive.yml. This is intentionally the fast per-push subset.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

# Make the soft IME deterministic on the booted journey AVD (issue #736).
#
# PromptComposerImeSquishProofTest must RAISE the soft keyboard to validate the
# keyboard-up squish. On a fresh swiftshader AVD the soft IME can refuse to show
# when a hardware keyboard is assumed present, so we enable the IME-with-hard-
# keyboard secure setting on every connected device before running the suite.
# This is the device-side half of the determinism fix; the test itself also
# explicitly re-issues WindowInsetsControllerCompat.show(ime()) and HARD-FAILS
# (no silent skip) if the keyboard still cannot be raised. `adb` is on PATH and
# the emulator is already booted inside the android-emulator-runner `script:`
# step, so this runs against the live AVD. Best-effort: never abort the suite if
# a device momentarily rejects the setting (the test's own hard-fail guard is
# the real backstop).
for _serial in $(adb devices | awk 'NR>1 && $2=="device"{print $1}'); do
  adb -s "$_serial" shell settings put secure show_ime_with_hard_keyboard 1 \
    >/dev/null 2>&1 || true
done

ARTIFACT_DIR="$REPO_ROOT/artifacts/ci-journey"
mkdir -p "$ARTIFACT_DIR"
SUMMARY="$ARTIFACT_DIR/summary.md"

GRADLEW="$REPO_ROOT/gradlew"

FQCN_PREFIX="com.pocketshell.app.proof"

# The load-bearing journey classes. Keep this list aligned with the issue #691
# named subset; every class here MUST use only the deterministic agents:2222
# fixture (no toxiproxy) so the per-push job stays deterministic.
#
# Issue #705: three picker-driven journeys re-join the picker-FREE
# DeepLinkSessionSwitchE2eTest now that the picker enumeration wedge is fixed
# (#687 + #702) and the over-broad header assertion is scoped to the breadcrumb
# crumb (#705). Each was proven GREEN on a pooled AVD before re-adding.
# BackgroundGraceReconnectE2eTest re-joins this subset (#707): the within-grace
# `terminal_foreground_reattach` it forbade was a benign fan-out marker, not a
# reconnect; the assertion is narrowed to genuine reconnect signals only.
#
# Issue #710 (RE-ADDED): MultiSessionSwitchJourneyE2eTest is back in this
# per-push subset. It was quarantined under #691 (58d9957f) because it wedged
# (never finished) on the GitHub android-emulator-runner AVD while passing on
# the local pooled AVD — the rapid A->B->C->A switch backgrounded a just-switched
# runtime whose VM-clear park then stalled the main thread on an UNBOUNDED
# `cancelAndJoin()` over a pane job wedged in a non-cooperative `-CC` socket read
# (run 27368527630 burned ~67 min to the job cap). #710 bounds that teardown
# (`CachedTmuxRuntime.closeCachedRuntime` + `closeCachedRuntimesBlocking`) at
# SYNC_DETACH_TIMEOUT_MS so the main thread is guaranteed to return, removing the
# CI-AVD wedge. The per-test `timeout_msec` backstop below stays as defense in
# depth so ANY future wedge fails fast (~5 min) instead of hanging the job.
JOURNEY_CLASSES=(
  "$FQCN_PREFIX.DeepLinkSessionSwitchE2eTest"
  # RE-ADDED (#710): the CI-AVD wedge was the unbounded VM-clear park teardown,
  # now bounded at SYNC_DETACH_TIMEOUT_MS. See the block comment above.
  "$FQCN_PREFIX.MultiSessionSwitchJourneyE2eTest"
  # ADDED (#758): the maintainer's priority-#1 back→open-another-session
  # reconnect. Opens session A, taps BACK to the picker, triggers a discovery
  # reconcile over the shared SSH lease (with ONE deterministically-injected
  # poll-time stale-channel symptom via the inert
  # SshFolderListGateway.forcedStaleChannelSymptoms test hook — production
  # default 0), then opens session B and asserts the VISIBLE warm-reuse
  # invariants: ZERO fresh SSH_HANDSHAKE_ATTEMPTS delta, NO full-screen
  # Connecting overlay, NO Disconnected band. Uses ONLY the deterministic
  # agents:2222 fixture (no toxiproxy), so it belongs in this per-push subset.
  # Pre-fix this would go RED (the gateway's unconditional `disconnect` tore down
  # the lease A held → cold re-dial); the #758 refcount-aware `evictIdle`
  # (no-op while the session VM holds the lease) makes it GREEN. Does NOT
  # self-skip on CI.
  "$FQCN_PREFIX.BackThenOpenSecondSessionReusesWarmLeaseE2eTest"
  "$FQCN_PREFIX.ColdRestoreGoneSessionNoResurrectE2eTest"
  "$FQCN_PREFIX.ReconnectRepaintE2eTest"
  # Issue #754 (slice 1c-iv-c): this class is the per-PR-CI deterministic regression
  # catcher for the within-grace "Attaching…" reconnect bug. It now (a) forbids the
  # TMUX_SWITCHING_LOADING_TAG "Attaching…" overlay on every within-grace foreground
  # and (b) asserts the within-grace reattach is the NEW driver-owned reseed-only
  # effect (`foreground_reattach outcome=reseed_only`) with NO inline probe
  # (`tmux_probe_result`). It runs on the deterministic agents:2222 fixture and needs
  # NO toxiproxy, so it stays in this per-push subset. On `main` the within-grace
  # foreground runs the inline probe (records tmux_probe_result, never reseed_only),
  # so these assertions FAIL on `main` and PASS after the fix. The strongest
  # CONFIRMED-DEAD-within-grace reproduction lives in WithinGraceResumeRideThroughE2eTest
  # (toxiproxy clean-cut, `withinGraceForegroundConfirmedDeadDoesNotShowAttachingOverlayOrReconnect`),
  # which is opt-in (assumeNetworkFaultProofsEnabled self-skips on CI since tests.yml
  # keeps this job toxiproxy-free); it is the local/manual fault-injection proof.
  "$FQCN_PREFIX.BackgroundGraceReconnectE2eTest"
  # PROMOTED (#727, epic #657 Wave 1 / S1): the share-auth journey pair. The
  # maintainer's recurring share-auth breakages had nightly-only / advisory E2E
  # coverage, so a share-auth regression was NOT caught at PR time. Both classes
  # drive ONLY the default deterministic `agents` fixture via DEFAULT_HOST /
  # DEFAULT_PORT / DEFAULT_USER (AgentsFixtureTarget -> 10.0.2.2:2222, or the
  # pool-allocated port under `--pool`), so no new Docker fixture/port is needed.
  # Neither class self-skips on CI (no assumeFalse(isRunningOnCi())). These live
  # under com.pocketshell.app.share, not the com.pocketshell.app.proof prefix, so
  # they carry their fully-qualified names directly.
  "com.pocketshell.app.share.ShareTargetE2eTest"
  "com.pocketshell.app.share.SharePassphraseDialogE2eTest"
  # PROMOTED (#732, Finding B): the host server-PROFILE discovery journey. The
  # picker false-greens on ToolUnavailable if the host `pocketshell profiles
  # list --json` branch or the seeded `~/.zlaude` profile silently regresses, so
  # this discovery proof must run at PR time, not only in the release gate. It
  # drives ONLY the default deterministic `agents` fixture (DEFAULT_HOST /
  # DEFAULT_PORT / DEFAULT_USER -> 10.0.2.2:2222, or the pool-allocated port
  # under `--pool`) — the same fixture every class above uses — so no new Docker
  # service/port is needed. The emulator-journey workflow brings up `agents`
  # with `up -d --build` (tests.yml), so CI gets the new `profiles list` branch
  # + seed, not a stale cached image. It does NOT self-skip on CI. This class
  # lives under com.pocketshell.app.projects, not the com.pocketshell.app.proof
  # prefix, so it carries its fully-qualified name directly.
  "com.pocketshell.app.projects.ProfileDiscoveryPickerDockerTest"
  # PROMOTED (#736, follow-up to the #567 review): the composer keyboard-up
  # SQUISH regression proof. The maintainer's #1 process complaint area is the
  # composer being crushed when the soft keyboard is up (draft collapsed to one
  # line, header clipped off the top, controls jammed at the keyboard). This
  # proof reproduces that exact state (multi-line draft + 2 staged attachment
  # tiles, IME raised) and asserts the body is NOT squished. It ran only in the
  # unfiltered full connected suite before; per the "load-bearing journeys run
  # at PR time" principle (#638/#657) it must run per-push so the squish can't
  # silently regress. It uses NO Docker fixture (pure Compose-rule UI test, no
  # SSH/tmux). The IME is raised DETERMINISTICALLY (the test re-issues
  # WindowInsetsControllerCompat.show(ime()), and this script sets
  # `show_ime_with_hard_keyboard 1` on the booted AVD near the top) so the
  # keyboard reliably appears on the CI swiftshader pixel_7 AVD; if it still cannot be
  # raised after the bounded attempt the test FAILS LOUD (no silent assumeTrue
  # skip — that was the #736 review blocker: a skip would let this gate go green
  # with zero squish protection). Its geometry assertions are framed relative to
  # the measured room above the keyboard (body-fits-room, send/attach-above-IME),
  # so they hold on the CI pixel_7 AVD (the same profile the journey AVD uses).
  # It lives under com.pocketshell.app.composer, not the proof prefix, so it
  # carries its fully-qualified name directly.
  "com.pocketshell.app.composer.PromptComposerImeSquishProofTest"
)

echo "=========================================================="
echo "Per-push CI journey suite (issue #691) — load-bearing subset"
echo "Included classes:"
for c in "${JOURNEY_CLASSES[@]}"; do
  echo "  - $c"
done
echo "  (pocketshellCi=true; deterministic agents:2222 only, no toxiproxy)"
echo "  (per-class retry-once for CI-AVD infra flakes — issue #712)"
echo "=========================================================="

# Issue #691 (S2 defense-in-depth): pass AndroidJUnitRunner's per-test
# `timeout_msec` so a wedged test is interrupted and FAILS FAST (~5 min) instead
# of hanging the whole job to the runner cap. A hang is worse than a clean fail
# (the whole cap burned + a failure email every push); the timeout converts any
# future CI-AVD wedge into a fast, legible red. 300000 ms = 5 min/test is far
# above the generous `pocketshellCi=true` E2E ceilings, so it never trips a
# legitimately slow CI test — it only catches a genuine deadlock.
#
# run_class <FQCN> — runs ONE journey class as its own gradle connected-test
# invocation and returns gradle's exit code (0 == that class passed). Running
# one class per invocation (rather than all classes comma-joined into a single
# invocation) is what makes the per-class retry below clean: the gradle exit
# code IS the per-class verdict, with no XML parsing or fragile result-file
# scraping required.
run_class() {
  local fqcn="$1"
  "$GRADLEW" :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.pocketshellCi=true \
    -Pandroid.testInstrumentationRunnerArguments.timeout_msec=300000 \
    -Pandroid.testInstrumentationRunnerArguments.class="$fqcn" \
    --stacktrace
}

# ---------------------------------------------------------------------------
# Issue #724: optional cross-lane sharding.
#
# The DEFAULT path (below this block) is unchanged: a SINGLE serial loop over
# the load-bearing classes on one emulator + agents:2222, WITH the #712
# per-class retry-once. That is the clean fallback CI relies on — the GitHub
# android-emulator-runner gives one AVD and the workflow brings up one `agents`
# on 2222, so `POCKETSHELL_JOURNEY_SHARD` is never set there and this block is
# skipped entirely.
#
# When run on the multi-lane dev box, opt in with `POCKETSHELL_JOURNEY_SHARD=1`
# (requires scripts/avd-pool.sh + scripts/agents-pool.sh warmed). Each lane is a
# background `connected-test.sh --pool` invocation that self-allocates a free
# (emulator serial, agents port) pair, so the classes run across however many
# lanes are free instead of strictly serially. The number of concurrent lanes
# is bounded by POCKETSHELL_JOURNEY_LANES (default 2). connected-test's own
# per-lane flock means we never oversubscribe a serial/port; lanes that can't
# claim a pair simply wait, so this degrades gracefully to serial when only one
# lane is free. Each lane carries the SAME pocketshellCi + timeout_msec args as
# the serial run_class, and a failing lane is retried once (parity with #712)
# so a CI-AVD infra flake on a sharded run recovers exactly as in serial.
shard_class() {
  # Run ONE class on a self-allocated pool lane, returning the lane's exit code.
  local idx="$1"
  local fqcn="$2"
  "$REPO_ROOT/scripts/connected-test.sh" --pool --suffix "ij$idx" \
    -Pandroid.testInstrumentationRunnerArguments.pocketshellCi=true \
    -Pandroid.testInstrumentationRunnerArguments.timeout_msec=300000 \
    -Pandroid.testInstrumentationRunnerArguments.class="$fqcn" \
    --stacktrace
}

shard_run() {
  local lanes="${POCKETSHELL_JOURNEY_LANES:-2}"
  echo "=========================================================="
  echo ">>> SHARDED journey run (issue #724): up to ${lanes} concurrent lanes"
  echo "    each lane self-allocates (emulator serial, agents port) via"
  echo "    scripts/connected-test.sh --pool  (retry-once per class — #712)"
  echo "=========================================================="

  local rc=0
  local idx=0
  local -a pids=()
  local -a pid_classes=()
  local -a pid_idx=()
  for fqcn in "${JOURNEY_CLASSES[@]}"; do
    # Throttle to `lanes` concurrent background runs.
    while (( $(jobs -rp | wc -l) >= lanes )); do
      wait -n 2>/dev/null || true
    done
    idx=$((idx + 1))
    local logf
    logf="$ARTIFACT_DIR/shard-lane-$idx-$(basename "$fqcn").log"
    echo ">>> dispatch lane $idx: $fqcn (log: $logf)"
    (
      # Per-class retry-once (parity with the serial #712 path): a CI-AVD infra
      # flake clears on the next attempt; a real regression fails both.
      if shard_class "$idx" "$fqcn"; then
        exit 0
      fi
      echo "SHARD_LANE_RETRY: $fqcn failed attempt 1 — retrying once"
      if shard_class "$idx" "$fqcn"; then
        echo "JOURNEY_FLAKE_RECOVERED: $fqcn passed on retry (sharded lane $idx)"
        exit 0
      fi
      exit 1
    ) > "$logf" 2>&1 &
    pids+=("$!")
    pid_classes+=("$fqcn")
    pid_idx+=("$idx")
  done

  # Collect every lane's verdict.
  local i
  for i in "${!pids[@]}"; do
    if ! wait "${pids[$i]}"; then
      echo "SHARD_LANE_FAILED: ${pid_classes[$i]} (lane ${pid_idx[$i]}, log: $ARTIFACT_DIR/shard-lane-${pid_idx[$i]}-$(basename "${pid_classes[$i]}").log)"
      rc=1
    else
      echo "SHARD_LANE_PASS: ${pid_classes[$i]} (lane ${pid_idx[$i]})"
    fi
  done
  return "$rc"
}

if [[ "${POCKETSHELL_JOURNEY_SHARD:-0}" == "1" ]]; then
  if shard_run; then
    echo "Sharded journey run: PASS"
    exit 0
  else
    echo "Sharded journey run: FAIL (see shard-lane-*.log under $ARTIFACT_DIR)"
    exit 1
  fi
fi

# Issue #712: per-class retry-once for CI-AVD infra flakes.
#
# The per-push job runs on the GitHub `android-emulator-runner` AVD — a 2-core
# swiftshader VM that occasionally stalls the in-emulator SSH+tmux
# `list-sessions` enumeration past the 60s picker wait (the #470 enumeration
# stall). That stall is an infra limitation of the slow CI AVD, not a code
# regression: the SAME commit passes on the next run. Without a retry the job
# goes red and spams a failure email on every such flake.
#
# Strategy: run each journey CLASS on its own; if a class FAILS, re-run ONLY
# that class once (NOT the whole suite). The job is marked red only if a class
# fails BOTH the original run and the retry.
#
# Why this does NOT mask real regressions: a genuine behavior bug fails
# CONSISTENTLY — it fails the original run AND the retry — so it still turns the
# job red and is still caught. Only a true infra flake (passes on the very next
# attempt of the same class) recovers to green. When a recovery happens we print
# a LOUD, greppable `JOURNEY_FLAKE_RECOVERED:` line so a degrading flake trend
# stays visible in the logs and is never silently hidden.
#
# Note on `Process crashed` / signal-9 (sibling-install SIGKILL): on the CI
# emulator-runner this job is the only installer, so that collision class does
# not arise here. If it ever did it would surface as a non-zero exit and the
# retry-once below would recover it exactly as for any other transient failure.

RECOVERED_CLASSES=()  # classes that failed first then PASSED on retry
FAILED_CLASSES=()     # classes that failed BOTH attempts (real failures)
PASSED_FIRST_TRY=()   # classes that passed on the first attempt

SUITE_START=$SECONDS

for fqcn in "${JOURNEY_CLASSES[@]}"; do
  echo "=========================================================="
  echo ">>> JOURNEY CLASS: $fqcn (attempt 1)"
  echo "=========================================================="
  class_start=$SECONDS

  if run_class "$fqcn"; then
    echo "JOURNEY_PASS: $fqcn passed on attempt 1 (elapsed $((SECONDS - class_start))s)"
    PASSED_FIRST_TRY+=("$fqcn")
    continue
  fi

  # Attempt 1 failed. Re-run ONLY this class once — a CI-AVD infra flake
  # (e.g. the #470 enumeration stall) typically clears on the next attempt;
  # a real regression fails again and keeps the job red.
  echo "=========================================================="
  echo ">>> JOURNEY CLASS: $fqcn FAILED attempt 1 — retrying once (attempt 2)"
  echo "=========================================================="
  retry_start=$SECONDS

  if run_class "$fqcn"; then
    # Loud, greppable recovery marker so masked flakes stay visible and a
    # degrading trend is detectable in the CI logs.
    echo "JOURNEY_FLAKE_RECOVERED: $fqcn passed on retry (attempt 2) (retry elapsed $((SECONDS - retry_start))s)"
    RECOVERED_CLASSES+=("$fqcn")
  else
    echo "JOURNEY_FAILED: $fqcn failed twice"
    FAILED_CLASSES+=("$fqcn")
  fi
done

SUITE_ELAPSED=$((SECONDS - SUITE_START))

# The job is red iff at least one class failed BOTH attempts.
if [[ "${#FAILED_CLASSES[@]}" -eq 0 ]]; then
  JOURNEY_EXIT=0
  journey_status="PASS"
else
  JOURNEY_EXIT=1
  journey_status="FAIL"
fi

echo "=========================================================="
echo "Per-push CI journey suite — done (elapsed ${SUITE_ELAPSED}s, exit ${JOURNEY_EXIT})"
echo "  passed first try: ${#PASSED_FIRST_TRY[@]}"
echo "  recovered on retry: ${#RECOVERED_CLASSES[@]}"
echo "  failed twice: ${#FAILED_CLASSES[@]}"
echo "=========================================================="

# Build the markdown summary. Quote arrays defensively — an empty array under
# `set -u` must not abort the script during summary generation.
{
  echo "# Per-push CI journey suite — summary"
  echo
  echo "| Selection | Args | Exit | Elapsed | Result |"
  echo "| --- | --- | --- | --- | --- |"
  echo "| ${#JOURNEY_CLASSES[@]} load-bearing journey classes (per-class retry-once) | \`pocketshellCi=true\` | $JOURNEY_EXIT | ${SUITE_ELAPSED}s | **$journey_status** |"
  echo
  echo "Classes exercised:"
  for c in "${JOURNEY_CLASSES[@]}"; do
    echo "- \`$c\`"
  done
  if [[ "${#RECOVERED_CLASSES[@]}" -gt 0 ]]; then
    echo
    echo "Recovered on retry (CI-AVD flake — \`JOURNEY_FLAKE_RECOVERED\`):"
    for c in "${RECOVERED_CLASSES[@]}"; do
      echo "- \`$c\`"
    done
  fi
  if [[ "${#FAILED_CLASSES[@]}" -gt 0 ]]; then
    echo
    echo "Failed BOTH attempts (\`JOURNEY_FAILED\` — job red):"
    for c in "${FAILED_CLASSES[@]}"; do
      echo "- \`$c\`"
    done
  fi
} > "$SUMMARY"

echo "----------------------------------------------------------"
cat "$SUMMARY"
echo "----------------------------------------------------------"

exit "$JOURNEY_EXIT"
