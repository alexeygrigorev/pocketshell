#!/usr/bin/env bash
# Test-validity guard (issue #657 / F4, extended for #848 / #850).
#
# The maintainer's #1 process complaint is that issues get reviewer-APPROVED
# and closed while the real on-device behaviour is still broken, because the
# test exercises a NARROW PROXY of the bug rather than the user's actual state.
# The #657 audit catalogued the recurring anti-patterns; this grep-guard is the
# cheap, automated backstop for the highest-signal smells so the rule does not
# rely solely on reviewer memory (F2/F3 are the human-facing rules in
# process.md; this is their machine sibling).
#
# It flags:
#
#   A5 (HARD-FAIL on a NEW occurrence) — an `assumeTrue(...)` self-skip that
#       gates a keyboard-up / IME / geometry assertion. On the CI swiftshader
#       AVD the real soft IME frequently never raises within the timeout, so
#       the assertion silently does NOT run and the test reports green — only
#       the dev-box AVD (where the real IME raises) actually asserts. The
#       corrective model is PromptComposerImeSquishProofTest: dispatch a
#       SYNTHETIC `ime()` inset and HARD-assert it applied (no skip), making the
#       keyboard-up state a deterministic test input. New IME-geometry proofs
#       MUST follow that model. An offender may opt out with an inline
#       `// JUSTIFIED:` comment (on the same line or the line directly above),
#       e.g. an SDK-version `assumeTrue(Build.VERSION.SDK_INT >= ...)` guard.
#
#   A4 / A2 (ADVISORY warning) — a `*StandIn` / `*Proxy` class or composable used
#       in a file whose name implies it is proving an occlusion / layout /
#       attach-cost symptom (Latch / Squish / Reachability / Chrome / Occlusion).
#       A trivial stand-in cannot reproduce the heavy real view's attach cost or
#       the real screen's competing chrome, so the proof may pass vacuously.
#       This is advisory only (never fails the build) — substituting a stand-in
#       is sometimes legitimate, but it should be a conscious, reviewer-noted
#       choice.
#
#   --- #848 / #850 additions (the v0.4.10 #847 connect-break class) ---
#
#   C1 (HARD-FAIL on a NEW occurrence) — a load-bearing
#       `assumeFalse(isRunningOnCi())` self-skip OUTSIDE the designated fault /
#       Docker-fixture classes. The #848 audit found the suite's connect/journey
#       coverage skips on CI, so a connect/reconnect regression has NO per-PR
#       net. A skip is JUSTIFIED only when it is a genuine opt-in fault fixture
#       (toxiproxy / packet-loss / network-fault-proxy) that tests.yml does not
#       start — those carry a self-describing message and are baselined. Any NEW
#       `assumeFalse(isRunningOnCi())` without that justification hard-fails:
#       inject the state synthetically and HARD-assert (the #780 model) instead
#       of self-skipping, or add an inline `// JUSTIFIED:` comment naming the
#       opt-in fixture.
#
#   FAKE1 (ADVISORY warning) — a connect-path RPC test whose fake daemon /
#       SshSession / source routes the connect verbs (`tree get`, `agents kind`,
#       cold-start hydrate) ALWAYS through a success envelope, with NO
#       error / non-zero-exit / timeout / never-returns case. This is the exact
#       shape of FolderListViewModelTreeDurabilityTest, whose always-answering
#       FakeTreeDaemon hid #847: `Loading` always resolved because the fake
#       could never fail or hang. Advisory + baselined (the rewrite to add the
#       fault cases is per-issue follow-up, e.g. #849), so CI stays green while
#       any NEW always-answering connect-path fake is surfaced.
#
#   AWAIT1 (ADVISORY warning) — a connect-path production RPC (`*RemoteSource`
#       suspend fun that `session.exec(...)`s the warm session on the
#       cold-start / connect path) whose caller awaits it with NO `withTimeout`
#       bound, OR the source's own exec is unbounded AND its cold-start caller is
#       too. The v0.4.10 hang was exactly this: `hydrateTreeOnColdStart` awaited
#       `source.getTree(...)` with no timeout, so a host that accepts the exec
#       but never returns pinned the coroutine and `Loading` never resolved.
#       Advisory + baselined; the bound + regression test is the #847 hotfix.
#
#   J1 (HARD-FAIL on a NEW occurrence) — an androidTest `*E2eTest` /
#       `*DockerTest` class that is not wired into `scripts/ci-journey-suite.sh`
#       and has no local `// CI_JOURNEY_SUITE_JUSTIFIED:` reason. The per-push
#       journey suite is the load-bearing connected-test net; new journey-shaped
#       classes must either join it or say, next to the class, why they are
#       intentionally local/nightly/backlog-only. Current known unwired classes
#       are baselined, and stale J1 baseline entries hard-fail so the baseline
#       only shrinks as classes are promoted or removed.
#
#   --- #1048 addition (the runTest virtual-clock-vs-real-dispatcher flake class) ---
#
#   TIMING1 (ADVISORY warning, with ONE narrow HARD-FAIL) — scoped to the
#       connection/terminal test roots (core-ssh, core-tmux, core-connection, and
#       the app tmux/connectivity test dirs). The recurring "passes-locally /
#       flakes-on-CI" JVM failure is ONE class: a `runTest` virtual clock drives
#       code whose owned background work runs on a REAL dispatcher / Android
#       Handler/Looper / raw Thread not pinned to the test scheduler, so
#       runCurrent()/advanceUntilIdle() returns before the real thread finishes and
#       CI CPU contention loses the race. TIMING1 flags a `runTest` test that
#       touches a real dispatcher/thread (Dispatchers.IO/Dispatchers.Default/
#       Executors.new/Thread.sleep/Thread(/CountDownLatch on a code line) UNLESS the
#       file also (a) injects a StandardTestDispatcher(/UnconfinedTestDispatcher(
#       seam for its owned scopes, (b) shows the bounded-pump signature (idleFor(
#       together with a System.currentTimeMillis()/System.nanoTime() deadline loop),
#       or (c) carries an inline `// JUSTIFIED:` opt-out. The two corrective shapes
#       are Shape A (pinnable seam — SshLeaseAcquireBoundCharacterizationTest) and
#       Shape B (wall-clock-bounded pump — TmuxSessionWarmOpenTest.pumpUntil / the
#       codex pump). Current matches are baselined (advisory; the baseline only
#       shrinks as tests adopt a seam). The lone HARD-FAIL is the narrow,
#       high-signal NEW case: a `runTest` test with a bare small `Thread.sleep(<N>)`
#       immediately preceding its load-bearing assert and NO bounded-deadline loop
#       (the banned "fixed sleep as the only sync" shape).
#
# A BASELINE allowlist records the offenders the audits catalogued but that are
# intentionally NOT rewritten here (the rewrites are per-issue follow-up work).
# Those are reported as KNOWN-baseline (advisory) so this guard does not redden
# CI for tests it is not this PR's job to fix, while any NEW unjustified
# occurrence (A5, C1, J1) hard-fails. Removing a file from a baseline (because its
# test was converted to the deterministic / fault-covering model) is the
# intended direction of travel; a stale baseline entry is pruned + noted.
#
# Usage:
#   scripts/check-test-validity.sh            # guard mode (CI): exit 1 on a NEW A5/C1/J1/TIMING1 hard-fail smell
#   scripts/check-test-validity.sh --report   # report ALL findings incl. baseline; never fails
#
# This is intentionally a grep-guard, not a custom lint rule, for affordability
# (it runs in the cheap Unit job in .github/workflows/tests.yml before the
# Gradle test step, adding < 1 s).

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

# --------------------------------------------------------------------------
# #850: scan EVERY test source root, not just app/src/androidTest. The original
# guard saw only app/src/androidTest (:57), so app/src/test + all shared/*
# modules — including the FolderListViewModelTreeDurabilityTest seam class that
# hid #847 — were invisible to it.
# --------------------------------------------------------------------------
TEST_ROOTS=(app/src/androidTest app/src/test)
while IFS= read -r d; do
  [[ -d "$d" ]] && TEST_ROOTS+=("$d")
done < <(find shared -maxdepth 3 -type d -path 'shared/*/src/test' 2>/dev/null | sort)

# Connect-path production RPC sources (#850 AWAIT1). These are the warm-session
# RPC seams consumed on the connect / cold-start path.
RPC_SOURCE_ROOT="app/src/main/java/com/pocketshell/app"
ANDROID_TEST_ROOT="app/src/androidTest/java"
CI_JOURNEY_SUITE="scripts/ci-journey-suite.sh"

# Collect all test .kt files once.
collect_test_files() {
  local r
  for r in "${TEST_ROOTS[@]}"; do
    [[ -d "$r" ]] && find "$r" -type f -name '*.kt'
  done
}
mapfile -t ALL_TEST_FILES < <(collect_test_files)

REPORT_MODE=0
if [[ "${1:-}" == "--report" ]]; then
  REPORT_MODE=1
fi

# --------------------------------------------------------------------------
# BASELINE — A5 offenders the #657 audit catalogued (file:issue). These still
# use the real-IME-or-`assumeTrue`-skip pattern; converting them to the #780
# synthetic-inset model is per-issue follow-up, NOT this framework PR. They are
# reported as KNOWN-baseline (advisory) so CI stays green, while any NEW
# unjustified A5 occurrence outside this list hard-fails.
# --------------------------------------------------------------------------
A5_BASELINE=(
  "app/src/androidTest/java/com/pocketshell/app/composer/PromptComposerSheetImeReachabilityTest.kt"   # issue #615
  "app/src/androidTest/java/com/pocketshell/app/composer/PromptComposerImeLayoutRegressionTest.kt"    # issue #682
)

# --------------------------------------------------------------------------
# BASELINE — C1 (#848/#850): the pre-existing `assumeFalse(isRunningOnCi())`
# call sites. Each is a genuine opt-in fault / Docker-fixture skip (the fixture
# is not started by tests.yml). They are baselined as KNOWN (advisory); any NEW
# unjustified `assumeFalse(isRunningOnCi())` outside this list hard-fails.
# --------------------------------------------------------------------------
C1_BASELINE=(
  "app/src/androidTest/java/com/pocketshell/app/tmux/AgentConversationReconnectDockerTest.kt"         # issue #495 local-only reconnect evidence
  "app/src/androidTest/java/com/pocketshell/app/proof/EmulatorWorkflowE2eTest.kt"                     # issue #207/#470/#835 picker-enumeration CI stopgap
)

# --------------------------------------------------------------------------
# BASELINE — FAKE1 (#848/#850): connect-path tests with an always-answering
# fake catalogued by the #848 audit. The fault-covering rewrite is per-issue
# follow-up (#849). Reported as KNOWN (advisory).
# --------------------------------------------------------------------------
FAKE1_BASELINE=(
)

# --------------------------------------------------------------------------
# BASELINE — AWAIT1 (#848/#850): connect-path RPC callers/sources awaited with
# no timeout, catalogued by the #848 audit. The bound + regression test is the
# #847 hotfix. Reported as KNOWN (advisory).
# --------------------------------------------------------------------------
AWAIT1_BASELINE=(
)

# --------------------------------------------------------------------------
# BASELINE — J1 (#848 follow-up): current androidTest `*E2eTest` /
# `*DockerTest` classes that are intentionally not in the per-push
# ci-journey-suite yet. New journey-shaped classes must be wired into
# scripts/ci-journey-suite.sh or carry a local
# `// CI_JOURNEY_SUITE_JUSTIFIED:` reason in their source. Stale entries are a
# hard failure so this list is removed when a class is promoted or deleted.
# --------------------------------------------------------------------------
J1_UNWIRED_ANDROID_E2E_DOCKER_BASELINE=(
  "com.pocketshell.app.composer.AttachmentStagerRealUploadDockerTest"
  "com.pocketshell.app.composer.ComposerPartialExpandE2eTest"
  "com.pocketshell.app.costs.CostsScreenE2eTest"
  "com.pocketshell.app.crash.ShareAllReportsDockerTest"
  "com.pocketshell.app.env.EnvScreenE2eTest"
  "com.pocketshell.app.fileexplorer.FileExplorerDockerTest"
  "com.pocketshell.app.fileviewer.FileViewerDockerTest"
  "com.pocketshell.app.fileviewer.LinkTapParsingDockerTest"
  "com.pocketshell.app.fileviewer.TerminalFilePathTapToViewerDockerTest"
  "com.pocketshell.app.git.GitHistoryDockerTest"
  "com.pocketshell.app.hosts.DefaultHostLaunchE2eTest"
  "com.pocketshell.app.hosts.HostAndFolderListScrollE2eTest"
  "com.pocketshell.app.hosts.HostEditFromKebabE2eTest"
  "com.pocketshell.app.notifications.UpdateAvailableNotificationE2eTest"
  "com.pocketshell.app.portfwd.ForwardingIndicatorE2eTest"
  "com.pocketshell.app.portfwd.ForwardingNotificationE2eTest"
  "com.pocketshell.app.portfwd.ForwardingResumeOnLaunchE2eTest"
  "com.pocketshell.app.portfwd.PortForwardPanelLifecycleE2eTest"
  "com.pocketshell.app.projects.AgentLaunchCommandDockerTest"
  "com.pocketshell.app.projects.FolderListGatewayDockerTest"
  "com.pocketshell.app.projects.FolderListGatewayStaleChannelHealDockerTest"
  "com.pocketshell.app.projects.FolderListKillSessionDockerTest"
  "com.pocketshell.app.projects.FolderListOutOfBandSessionDockerTest"
  "com.pocketshell.app.projects.FolderListSessionResumeDockerTest"
  "com.pocketshell.app.projects.FolderListTreeStopSessionDockerTest"
  "com.pocketshell.app.projects.WatchedFoldersE2eTest"
  "com.pocketshell.app.proof.CodexOverflowNoReconnectE2eTest"
  "com.pocketshell.app.proof.CodexRedrawOverflowReconnectE2eTest"
  "com.pocketshell.app.proof.CodexWindowStartupControlSequenceE2eTest"
  "com.pocketshell.app.proof.ColdInstallE2eTest"
  "com.pocketshell.app.proof.DisconnectBlackholeE2eTest"
  "com.pocketshell.app.proof.DisconnectFlapSoakE2eTest"
  "com.pocketshell.app.proof.EmulatorWorkflowE2eTest"
  "com.pocketshell.app.proof.FastResumeReconnectE2eTest"
  "com.pocketshell.app.proof.MultiHostSessionE2eTest"
  "com.pocketshell.app.proof.NavigatorBackForegroundNoSshE2eTest"
  "com.pocketshell.app.proof.NetworkLatencyModelE2eTest"
  "com.pocketshell.app.proof.NoBackgroundWorkE2eTest"
  "com.pocketshell.app.proof.PacketLossNetworkFaultE2eTest"
  "com.pocketshell.app.proof.ProjectSwitcherDropdownE2eTest"
  "com.pocketshell.app.proof.RideThroughInterruptionE2eTest"
  "com.pocketshell.app.proof.SessionSwipeSwitchE2eTest"
  "com.pocketshell.app.proof.SilentMidSessionDropDetectionE2eTest"
  "com.pocketshell.app.proof.SshReconnectE2eTest"
  "com.pocketshell.app.proof.StaleLeaseSwitchRecoveryE2eTest"
  "com.pocketshell.app.proof.StrictModeNoNetworkOnMainE2eTest"
  "com.pocketshell.app.proof.SystemBackForegroundE2eTest"
  "com.pocketshell.app.proof.TmuxBracketedPasteDictationE2eTest"
  "com.pocketshell.app.proof.TmuxDetachOnBackgroundE2eTest"
  "com.pocketshell.app.proof.TmuxExternalUpdateDockerTest"
  "com.pocketshell.app.proof.TmuxKeyBarCtrlComboE2eTest"
  "com.pocketshell.app.proof.TmuxOrphanClientCleanupE2eTest"
  "com.pocketshell.app.proof.TmuxSessionSwitchE2eTest"
  "com.pocketshell.app.proof.TmuxSessionSwitchSameHostReusesSshE2eTest"
  "com.pocketshell.app.proof.TmuxTerminalSurfaceFailureE2eTest"
  "com.pocketshell.app.proof.WarmLeaseReuseBatchCDockerTest"
  "com.pocketshell.app.proof.WarmLeaseReuseDockerTest"
  "com.pocketshell.app.proof.WithinGraceResumeRideThroughE2eTest"
  "com.pocketshell.app.release.UpdateCheckSchedulerE2eTest"
  "com.pocketshell.app.session.ConversationToolResultPairingE2eTest"
  "com.pocketshell.app.session.ShowKeyboardChipE2eTest"
  "com.pocketshell.app.sessions.service.SessionConnectionServiceE2eTest"
  "com.pocketshell.app.settings.ConversationFontSizeSettingE2eTest"
  "com.pocketshell.app.settings.DiagnosticsRecordingIndicatorE2eTest"
  "com.pocketshell.app.settings.SettingsAboutFooterE2eTest"
  "com.pocketshell.app.settings.SettingsPersistenceE2eTest"
  "com.pocketshell.app.settings.SettingsSectionOrderE2eTest"
  "com.pocketshell.app.share.SharePasteIntoSessionE2eTest"
  "com.pocketshell.app.snippets.SnippetPickerTmuxZOrderDockerTest"
  "com.pocketshell.app.terminal.TerminalLabDockerTest"
  "com.pocketshell.app.tmux.ConversationOpenLatencyRttDockerTest"
  "com.pocketshell.app.tmux.Issue887TerminalFixedUnderImeE2eTest"
  "com.pocketshell.app.tmux.TmuxAttachPrefillDockerTest"
  "com.pocketshell.app.tmux.TmuxAttachTimeoutDockerTest"
  "com.pocketshell.app.tmux.TmuxDetectedPortForwardDockerTest"
  "com.pocketshell.app.tmux.TmuxResizeSessionE2eTest"
  "com.pocketshell.app.tmux.TmuxSessionOpencodeInputDockerTest"
  "com.pocketshell.app.tmux.TmuxShellComposerOcclusionE2eTest"
  "com.pocketshell.app.usage.UsageScreenE2eTest"
  "com.pocketshell.app.usage.UsageThresholdNotificationE2eTest"
)

# --------------------------------------------------------------------------
# BASELINE — TIMING1 (#1048): connection/terminal `runTest` tests that touch a
# real dispatcher/thread without a pinned seam or bounded pump, catalogued now.
# Each is advisory; converting it to Shape A (a StandardTestDispatcher seam) or
# Shape B (a bounded pump) is per-test follow-up. The baseline only shrinks as
# tests adopt a seam — a stale entry (file gone) is pruned + noted. The NEW
# narrow hard-fail (a bare Thread.sleep(N) immediately before a load-bearing
# assert with no bounded loop) is NEVER baselined; baselined files are advisory.
# --------------------------------------------------------------------------
TIMING1_BASELINE=(
  "app/src/test/java/com/pocketshell/app/tmux/TmuxSessionOpenFailedReconnectTest.kt"           # real-IO factoryScope
  "app/src/test/java/com/pocketshell/app/tmux/TmuxSessionViewModelVoiceTest.kt"                # real-IO factoryScope
  "shared/core-ssh/src/test/java/com/pocketshell/core/ssh/SshConnectionCancellationTest.kt"    # CountDownLatch cross-thread sync
  "shared/core-ssh/src/test/java/com/pocketshell/core/ssh/TransportDispatcherWedgeBoundTest.kt" # deliberate wall-clock wedge harness
  # Issue #1048: surfaced when the TIMING1 scope widened to app/hosts. This is the
  # #1110 fix's deliberate Shape-B real-await — the off-main close assertion needs
  # a REAL background thread, so it bounds completion with a generous wall-clock
  # CountDownLatch.await(10s) (not the idleFor+currentTimeMillis loop the lint can
  # auto-recognise). Legitimate convention shape, not a smell — same as
  # SshConnectionCancellationTest above.
  "app/src/test/java/com/pocketshell/app/hosts/HostListViewModelTest.kt"                        # CountDownLatch off-main close await (#1110 Shape-B)
)

# Connection/terminal test roots TIMING1 is scoped to (path-prefix match).
timing1_in_scope() {
  case "$1" in
    shared/core-ssh/src/test/*) return 0 ;;
    shared/core-tmux/src/test/*) return 0 ;;
    shared/core-connection/src/test/*) return 0 ;;
    app/src/test/java/com/pocketshell/app/tmux/*) return 0 ;;
    app/src/androidTest/java/com/pocketshell/app/tmux/*) return 0 ;;
    app/src/test/java/com/pocketshell/app/connectivity/*) return 0 ;;
    app/src/androidTest/java/com/pocketshell/app/connectivity/*) return 0 ;;
    # Issue #1048: widened to the areas that actually flaked this class —
    # composer (#1102, sidecar-store real-IO drain) and hosts (#1110, real
    # off-main close) — plus projects, the sibling source-binding area, so a
    # future virtual-clock-vs-real-dispatcher timing flake there gets linted.
    app/src/test/java/com/pocketshell/app/composer/*) return 0 ;;
    app/src/androidTest/java/com/pocketshell/app/composer/*) return 0 ;;
    app/src/test/java/com/pocketshell/app/hosts/*) return 0 ;;
    app/src/androidTest/java/com/pocketshell/app/hosts/*) return 0 ;;
    app/src/test/java/com/pocketshell/app/projects/*) return 0 ;;
    app/src/androidTest/java/com/pocketshell/app/projects/*) return 0 ;;
  esac
  return 1
}

in_list() {
  local file="$1"; shift
  local b
  for b in "$@"; do
    [[ "$file" == "$b" ]] && return 0
  done
  return 1
}

# --------------------------------------------------------------------------
# Helper: is a grepped line a real code line (not a comment / import / KDoc)?
# --------------------------------------------------------------------------
is_code_line() {
  ! printf '%s' "$1" | grep -Eq '^[[:space:]]*(\*|//|import |/\*)'
}

android_class_file_for() {
  local fqcn="$1"
  local rel="${fqcn//.//}"
  printf '%s/%s.kt\n' "$ANDROID_TEST_ROOT" "$rel"
}

android_test_fqcn_for_file() {
  local file="$1"
  local rel="${file#"$ANDROID_TEST_ROOT"/}"
  rel="${rel%.kt}"
  printf '%s\n' "${rel//\//.}"
}

has_ci_journey_suite_justification() {
  local file="$1"
  grep -Eq 'CI_JOURNEY_SUITE_JUSTIFIED:[[:space:]]*[^[:space:]]' "$file"
}

# --------------------------------------------------------------------------
# A5 scan (IME-availability assumeTrue self-skip) — unchanged behaviour, now
# over every test root.
# --------------------------------------------------------------------------
ime_geometry_words='IME|imeShown|soft keyboard|keyboard-up|readImeBottomPx|geometry|boundsInRoot'

assume_is_ime_skip() {
  local line="$1"
  if printf '%s' "$line" | grep -Eq 'SDK_INT|VERSION_CODES|BuildConfig|Build\.VERSION'; then
    return 1
  fi
  printf '%s' "$line" | grep -Eiq 'ime|keyboard|imeShown'
}

declare -a A5_NEW=()
declare -a A5_KNOWN=()
declare -a A5_JUSTIFIED=()

scan_a5() {
  local file
  for file in "${ALL_TEST_FILES[@]}"; do
    [[ -z "$file" ]] && continue
    if ! grep -Eq "$ime_geometry_words" "$file"; then
      continue
    fi
    local lineno
    while IFS= read -r lineno; do
      [[ -z "$lineno" ]] && continue
      local text
      text="$(sed -n "${lineno}p" "$file")"
      if ! is_code_line "$text"; then
        continue
      fi
      local joined
      joined="$(sed -n "${lineno},$((lineno + 2))p" "$file" | tr '\n' ' ')"
      if ! assume_is_ime_skip "$joined"; then
        continue
      fi
      local prev
      prev="$(sed -n "$((lineno - 1))p" "$file")"
      if printf '%s\n%s' "$prev" "$text" | grep -q 'JUSTIFIED:'; then
        A5_JUSTIFIED+=("$file:$lineno")
        continue
      fi
      if in_list "$file" "${A5_BASELINE[@]}"; then
        A5_KNOWN+=("$file:$lineno")
      else
        A5_NEW+=("$file:$lineno")
      fi
    done < <(grep -nE '(^|[^.[:alnum:]])(assumeTrue|Assume\.assumeTrue)[[:space:]]*\(' "$file" | cut -d: -f1)
  done
}

# --------------------------------------------------------------------------
# A4 / A2 scan (advisory) — *StandIn / *Proxy used in a smell-named file. Now
# over every test root.
# --------------------------------------------------------------------------
declare -a A4_FINDINGS=()

scan_a4() {
  local file
  for file in "${ALL_TEST_FILES[@]}"; do
    [[ -z "$file" ]] && continue
    local base
    base="$(basename "$file")"
    if ! printf '%s' "$base" | grep -Eq 'Latch|Squish|Reachability|Chrome|Occlusion'; then
      continue
    fi
    local hits
    hits="$(grep -nE '(class|object|fun|private fun|@Composable)[[:space:]].*([A-Za-z]+StandIn|[A-Za-z]+Proxy)\b' "$file" || true)"
    if [[ -n "$hits" ]]; then
      while IFS= read -r h; do
        [[ -z "$h" ]] && continue
        A4_FINDINGS+=("$file:${h%%:*}")
      done <<< "$hits"
    fi
  done
}

# --------------------------------------------------------------------------
# C1 scan (#850) — load-bearing `assumeFalse(isRunningOnCi())` self-skip
# OUTSIDE the designated fault / Docker-fixture classes.
#
# The call frequently spans lines (`Assume.assumeFalse(` then the message then
# `isRunningOnCi()`), so we join the call line + the following 3 lines and look
# for `isRunningOnCi` in the joined window. A finding is JUSTIFIED when (a) it is
# baselined, (b) it carries an inline `// JUSTIFIED:` opt-out, or (c) the joined
# call text names an opt-in fault / Docker fixture the workflow does not start
# (toxiproxy / packet-loss / network-fault / opt-in). NEW unjustified findings
# hard-fail.
# --------------------------------------------------------------------------
declare -a C1_NEW=()
declare -a C1_KNOWN=()
declare -a C1_JUSTIFIED=()

c1_is_fixture_justified() {
  # The joined call text self-describes a genuine opt-in fault/Docker fixture.
  printf '%s' "$1" | grep -Eiq 'toxiproxy|packet.?loss|network.?fault|opt.?in.*fixture|fixture.*opt.?in|does not start'
}

scan_c1() {
  local file
  for file in "${ALL_TEST_FILES[@]}"; do
    [[ -z "$file" ]] && continue
    grep -q 'assumeFalse' "$file" || continue
    local lineno
    while IFS= read -r lineno; do
      [[ -z "$lineno" ]] && continue
      local text
      text="$(sed -n "${lineno}p" "$file")"
      if ! is_code_line "$text"; then
        continue
      fi
      # The call-text window: the call line + the 3 following lines (the
      # `assumeFalse(...)` message argument frequently sits there).
      local joined
      joined="$(sed -n "${lineno},$((lineno + 3))p" "$file" | tr '\n' ' ')"
      # Only the isRunningOnCi() form is the C1 smell (an SDK / feature
      # assumeFalse is unrelated).
      if ! printf '%s' "$joined" | grep -q 'isRunningOnCi'; then
        continue
      fi
      # The justification window also includes the 3 lines ABOVE the call, where
      # an explanatory comment / KDoc naming the opt-in fixture (or a
      # `// JUSTIFIED:` opt-out) naturally lives.
      local context
      context="$(sed -n "$((lineno - 3)),$((lineno + 3))p" "$file" | tr '\n' ' ')"
      # Opt-out: a `// JUSTIFIED:` comment anywhere in the context window.
      if printf '%s' "$context" | grep -q 'JUSTIFIED:'; then
        C1_JUSTIFIED+=("$file:$lineno")
        continue
      fi
      if in_list "$file" "${C1_BASELINE[@]}"; then
        C1_KNOWN+=("$file:$lineno")
      elif c1_is_fixture_justified "$context"; then
        # Self-describing opt-in fault fixture skip — legitimate, advisory only.
        C1_JUSTIFIED+=("$file:$lineno")
      else
        C1_NEW+=("$file:$lineno")
      fi
    done < <(grep -nE '(^|[^.[:alnum:]])(assumeFalse|Assume\.assumeFalse)[[:space:]]*\(' "$file" | cut -d: -f1)
  done
}

# --------------------------------------------------------------------------
# FAKE1 scan (#850, advisory) — connect-path RPC test whose fake routes the
# connect verbs ALWAYS through a success envelope, with NO fault case.
#
# Heuristic (deliberately tight to avoid false positives):
#   (1) the file is a connect-path RPC test: it defines a fake `SshSession`
#       (or a *Daemon/*Source fake) AND references a connect verb
#       (`tree get` / `agents kind` / `tree reconcile` / `tree upsert` /
#       cold-start hydrate / Loading resolution), AND
#   (2) the file has NO connect-RPC FAULT case for that verb path: no
#       per-verb non-zero exit, no never-returns/hang, no thrown error, no
#       timeout, no `garbage`/`nonZero`/`degrade` test naming.
# A file matching (1) but not (2) is the always-answering shape.
# --------------------------------------------------------------------------
declare -a FAKE1_FINDINGS=()
declare -a FAKE1_KNOWN=()

# A fault signal in a CONNECT-path test (any one of these means the test does
# exercise the failure path, so it is NOT an always-answering fake).
#
# An UNREACHABLE route-default (`else -> ExecResult(..., 127)` for an unrouted
# command) or a config-param name like `connectTimeoutContext` is NOT a real
# connect-RPC fault case — the verb path the test asserts on still always
# succeeds. So we deliberately exclude those: the fault signal must be an actual
# failing test scenario (a fault-named @Test / assertThrows / an explicit
# never-returns / a non-zero exit injected for the verb under test, NOT a
# "no route" / unrouted default).
fake1_has_fault_signal() {
  local file="$1"
  # Drop COMMENT lines (a comment that happens to contain "CHANGE" must not read
  # as a "hang" fault signal — substring matches like that are why the named
  # seam class evaded the detector) and strip the unreachable route-default +
  # config-param-name lines so they do not masquerade as fault coverage. Then
  # look for a genuine fault signal with WORD-BOUNDARY anchoring.
  local body
  body="$(grep -vE '^[[:space:]]*(\*|//|/\*)' "$file" \
          | grep -vEi 'no route|unrouted|else *-> *return *ExecResult|connectTimeout|Timeout(Context|Ms|Millis)\b|TIMEOUT_')"
  printf '%s' "$body" | grep -Eiqw \
    'nonZero|non-zero|degrade|garbage|hang|withTimeout|assumeNever|assertThrows|awaitCancellation|suspendForever|throws' \
    && return 0
  # Tmux fake fault knobs: these explicitly make the fake hang or throw for a
  # command prefix, but the generic word-boundary scan above does not match
  # names such as `suspendForeverOnCommandPrefix`.
  grep -Eq \
    '(suspendForeverOnCommandPrefix|closeAndThrowOnCommandPrefix)[[:space:]]*=' <<< "$body" \
    && return 0
  # Non-zero exit injected for the verb under test, or an explicit never-returns.
  printf '%s' "$body" | grep -Eiq \
    'getResult\s*=\s*ExecResult\([^)]*,[^)]*,\s*[1-9]|exitCode\s*=\s*[1-9]|ExecResult\([^)]*,[^)]*,\s*(1[0-9]+|[2-9])[[:space:]]*\)|never[ _-]?returns|delay\(Long\.MAX'
}

scan_fake1() {
  local file
  for file in "${ALL_TEST_FILES[@]}"; do
    [[ -z "$file" ]] && continue
    # (1a) defines a fake session/daemon/source.
    if ! grep -Eq '(class|object)[[:space:]]+[A-Za-z]*(Fake|Routing|Stub|Fixture)[A-Za-z]*(SshSession|Daemon|Source|Gateway)' "$file"; then
      continue
    fi
    # (1b) references a connect RPC verb, a cold-start hydrate, or a load-STATE
    # resolution. Use case-sensitive `Loading` / `LoadState` here (not `-i`) so a
    # test named `loadingDifferentHost...` (panel host loading, NOT the connect
    # `Loading` UI state) does not match on the word "loading".
    if ! { grep -Eiq 'tree get|tree upsert|tree reconcile|agents kind|cold.?start|coldStart|hydrate' "$file" \
           || grep -Eq '\bLoading\b|LoadState' "$file"; }; then
      continue
    fi
    # (1c) the fake actually answers a connect RPC (exec returns a success
    # envelope: an ExecResult with a 0 exit somewhere). Without this the file is
    # probably not routing a real connect RPC.
    if ! grep -Eq 'ExecResult\(' "$file"; then
      continue
    fi
    # (2) no fault case anywhere -> always-answering.
    if fake1_has_fault_signal "$file"; then
      continue
    fi
    if in_list "$file" "${FAKE1_BASELINE[@]}"; then
      FAKE1_KNOWN+=("$file")
    else
      FAKE1_FINDINGS+=("$file")
    fi
  done
}

# --------------------------------------------------------------------------
# AWAIT1 scan (#850, advisory) — connect-path production RPC seam consumed on the
# cold-start path with the RPC `session.exec(...)` UNBOUNDED (no `withTimeout`
# in the source's RPC fun). The exact v0.4.10 #847 hang shape:
# `hydrateTreeOnColdStart` awaited `source.getTree(...)` (which `session.exec`s)
# with no timeout, so a non-returning exec pinned the coroutine forever.
#
# Heuristic: a `*RemoteSource.kt` connect-path seam whose `suspend fun ...`
# body contains `session.exec(` but the file has NO `withTimeout` anywhere — an
# unbounded warm-session RPC. Advisory + baselined.
# --------------------------------------------------------------------------
declare -a AWAIT1_FINDINGS=()
declare -a AWAIT1_KNOWN=()

scan_await1() {
  # (1) `*RemoteSource.kt` connect seams whose warm-session exec is unbounded.
  local file
  while IFS= read -r file; do
    [[ -z "$file" ]] && continue
    grep -Eq 'session\.exec\(|\.exec\(command\)' "$file" || continue
    if ! grep -Eiq 'tree get|tree upsert|tree reconcile|agents kind|cold.?start|coldStart|hydrate|warm session|warm SSH' "$file"; then
      continue
    fi
    # Bounded already? (withTimeout / withTimeoutOrNull anywhere in the seam).
    if grep -Eq 'withTimeout(OrNull)?[[:space:]]*\(' "$file"; then
      continue
    fi
    if in_list "$file" "${AWAIT1_BASELINE[@]}"; then
      AWAIT1_KNOWN+=("$file")
    else
      AWAIT1_FINDINGS+=("$file")
    fi
  done < <(find "$RPC_SOURCE_ROOT" -type f -name '*RemoteSource.kt' 2>/dev/null)

  # (2) The cold-start CALLER awaiting a connect-RPC source method on a line
  # that is NOT wrapped in `withTimeout(...)`. A file-level "any withTimeout"
  # check is too coarse here — the caller may bound the warm-session WAIT yet
  # still await the RPC itself unbounded (the exact v0.4.10 #847 shape:
  # `awaitWarmSession()` was bounded but `source.getTree(...)` was not).
  while IFS= read -r file; do
    [[ -z "$file" ]] && continue
    local hit_line=""
    while IFS= read -r ln; do
      [[ -z "$ln" ]] && continue
      local no="${ln%%:*}"
      local txt="${ln#*:}"
      is_code_line "$txt" || continue
      # Skip the await if THIS line (or the enclosing call) is inside a
      # withTimeout(...) — i.e. the same line mentions withTimeout, or the line
      # above opens one.
      local above
      above="$(sed -n "$((no - 1))p" "$file")"
      if printf '%s\n%s' "$above" "$txt" | grep -Eq 'withTimeout(OrNull)?[[:space:]]*\('; then
        continue
      fi
      hit_line="$no"
      break
    done < <(grep -nE 'source\.(getTree|classify|reconcileTree|upsertTree)[[:space:]]*\(' "$file")
    [[ -z "$hit_line" ]] && continue
    if in_list "$file" "${AWAIT1_BASELINE[@]}"; then
      AWAIT1_KNOWN+=("$file:$hit_line")
    else
      AWAIT1_FINDINGS+=("$file:$hit_line")
    fi
  done < <(find "$RPC_SOURCE_ROOT" -type f -name 'FolderListViewModel.kt' 2>/dev/null)
}

# --------------------------------------------------------------------------
# J1 scan (#848 follow-up) — androidTest `*E2eTest` / `*DockerTest` classes
# must be in the per-push ci-journey-suite, locally justified, or part of the
# current unwired baseline.
# --------------------------------------------------------------------------
declare -a J1_WIRED=()
declare -a J1_NEW=()
declare -a J1_KNOWN=()
declare -a J1_JUSTIFIED=()
declare -a J1_STALE_BASELINE=()
declare -a J1_PARSER_FAILURE=()
declare -a J1_WIRED_ANDROID_TEST_CLASSES=()
declare -A J1_WIRED_ANDROID_TEST_SEEN=()

parse_ci_journey_suite_classes() {
  if [[ ! -f "$CI_JOURNEY_SUITE" ]]; then
    J1_PARSER_FAILURE+=("missing $CI_JOURNEY_SUITE")
    return
  fi

  local fqcn
  while IFS= read -r fqcn; do
    [[ -z "${fqcn:-}" ]] && continue
    if [[ -z "${J1_WIRED_ANDROID_TEST_SEEN[$fqcn]:-}" ]]; then
      J1_WIRED_ANDROID_TEST_CLASSES+=("$fqcn")
      J1_WIRED_ANDROID_TEST_SEEN[$fqcn]=1
    fi
  done < <(
    sed -nE \
      -e 's/.*"\$FQCN_PREFIX\.([A-Za-z0-9_]+)(#[^"]*)?".*/com.pocketshell.app.proof.\1/p' \
      -e 's/.*"(com\.pocketshell\.app\.[A-Za-z0-9_.]+)(#[^"]*)?".*/\1/p' \
      "$CI_JOURNEY_SUITE"
  )

  if [[ "${#J1_WIRED_ANDROID_TEST_CLASSES[@]}" -eq 0 ]]; then
    J1_PARSER_FAILURE+=("no androidTest classes parsed from $CI_JOURNEY_SUITE")
  fi
}

scan_j1() {
  parse_ci_journey_suite_classes

  local file fqcn
  while IFS= read -r file; do
    [[ -z "${file:-}" ]] && continue
    fqcn="$(android_test_fqcn_for_file "$file")"
    if in_list "$fqcn" "${J1_WIRED_ANDROID_TEST_CLASSES[@]}"; then
      J1_WIRED+=("$fqcn")
    elif has_ci_journey_suite_justification "$file"; then
      J1_JUSTIFIED+=("$fqcn")
    elif in_list "$fqcn" "${J1_UNWIRED_ANDROID_E2E_DOCKER_BASELINE[@]}"; then
      J1_KNOWN+=("$fqcn")
    else
      J1_NEW+=("$fqcn")
    fi
  done < <(
    find "$ANDROID_TEST_ROOT" -type f \
      \( -name '*E2eTest.kt' -o -name '*DockerTest.kt' \) \
      2>/dev/null | sort
  )

  for fqcn in "${J1_UNWIRED_ANDROID_E2E_DOCKER_BASELINE[@]}"; do
    file="$(android_class_file_for "$fqcn")"
    if [[ ! -f "$file" ]]; then
      J1_STALE_BASELINE+=("$fqcn -> missing source file")
    elif in_list "$fqcn" "${J1_WIRED_ANDROID_TEST_CLASSES[@]}"; then
      J1_STALE_BASELINE+=("$fqcn -> now wired into $CI_JOURNEY_SUITE")
    elif has_ci_journey_suite_justification "$file"; then
      J1_STALE_BASELINE+=("$fqcn -> now has local CI_JOURNEY_SUITE_JUSTIFIED")
    fi
  done
}

# --------------------------------------------------------------------------
# TIMING1 scan (#1048) — connection/terminal `runTest` tests whose owned
# background work runs on a real dispatcher/thread not pinned to the test
# scheduler, with neither a TestDispatcher seam nor a bounded pump. Advisory,
# with ONE narrow hard-fail: a bare small `Thread.sleep(<N>)` immediately before
# a load-bearing assert and no bounded-deadline loop (the banned "fixed sleep as
# the only sync" shape).
# --------------------------------------------------------------------------
declare -a TIMING1_NEW_HARD=()
declare -a TIMING1_FINDINGS=()
declare -a TIMING1_KNOWN=()
declare -a TIMING1_JUSTIFIED=()

# The real-dispatcher/thread tokens that signal an owned background hop is not
# pinned to the virtual scheduler.
timing1_dispatcher_smell='Dispatchers\.IO|Dispatchers\.Default|Executors\.new|Thread\.sleep|Thread\(|CountDownLatch'

# (a) the file injects a TestDispatcher seam for its owned scopes.
timing1_has_test_dispatcher() {
  grep -Eq 'StandardTestDispatcher[[:space:]]*\(|UnconfinedTestDispatcher[[:space:]]*\(' "$1"
}

# (b) the file shows the bounded-pump signature: an `idleFor(` pump AND a
# `System.currentTimeMillis()` / `System.nanoTime()` deadline loop.
timing1_has_bounded_pump() {
  grep -Eq 'idleFor[[:space:]]*\(' "$1" \
    && grep -Eq 'System\.currentTimeMillis\(\)|System\.nanoTime\(\)' "$1"
}

# A `runTest` builder call in either the paren or trailing-lambda form.
timing1_has_run_test() {
  grep -Eq '(^|[^.[:alnum:]])runTest[[:space:]]*[({]' "$1"
}

# A real-dispatcher/thread smell on a genuine CODE line (not a comment/import).
timing1_has_code_smell() {
  local file="$1" lineno text
  while IFS= read -r lineno; do
    [[ -z "$lineno" ]] && continue
    text="$(sed -n "${lineno}p" "$file")"
    is_code_line "$text" && return 0
  done < <(grep -nE "$timing1_dispatcher_smell" "$file" | cut -d: -f1)
  return 1
}

# The narrow hard-fail: a bare numeric `Thread.sleep(<N>)` on a code line whose
# next up-to-2 non-blank, non-comment lines contain a load-bearing assert, while
# the file has NO bounded-deadline loop (the "fixed sleep as the only sync"
# shape). A `// JUSTIFIED:` on the sleep line or the line above opts out.
timing1_has_bare_sleep_before_assert() {
  local file="$1"
  # If the file already has a bounded-deadline loop, the sleep is part of a pump
  # (Shape B), not a "fixed sleep as the only sync" — not the hard-fail shape.
  if grep -Eq 'System\.currentTimeMillis\(\)|System\.nanoTime\(\)' "$file" \
     && grep -Eq '(^|[^[:alnum:]])(while|do)([^[:alnum:]]|$)' "$file"; then
    return 1
  fi
  local lineno text prev
  while IFS= read -r lineno; do
    [[ -z "$lineno" ]] && continue
    text="$(sed -n "${lineno}p" "$file")"
    is_code_line "$text" || continue
    prev="$(sed -n "$((lineno - 1))p" "$file")"
    if printf '%s\n%s' "$prev" "$text" | grep -q 'JUSTIFIED:'; then
      continue
    fi
    # Look at the next up-to-2 non-blank, non-comment lines for an assert call.
    local look n=0 seen=0
    while IFS= read -r look; do
      [[ "$look" =~ ^[[:space:]]*$ ]] && continue
      is_code_line "$look" || continue
      seen=$((seen + 1))
      if printf '%s' "$look" | grep -Eq '(^|[^.[:alnum:]])(assert[A-Za-z]*|fail)[[:space:]]*\('; then
        return 0
      fi
      n=$((n + 1))
      [[ "$n" -ge 2 ]] && break
    done < <(sed -n "$((lineno + 1)),$((lineno + 6))p" "$file")
  done < <(grep -nE 'Thread\.sleep[[:space:]]*\([[:space:]]*[0-9][0-9_]*L?[[:space:]]*\)' "$file" | cut -d: -f1)
  return 1
}

scan_timing1() {
  local file
  for file in "${ALL_TEST_FILES[@]}"; do
    [[ -z "$file" ]] && continue
    timing1_in_scope "$file" || continue
    timing1_has_run_test "$file" || continue
    timing1_has_code_smell "$file" || continue

    # The narrow NEW hard-fail (never baselined): a bare sleep-before-assert with
    # no bounded loop.
    if ! in_list "$file" "${TIMING1_BASELINE[@]}" \
       && timing1_has_bare_sleep_before_assert "$file"; then
      TIMING1_NEW_HARD+=("$file")
      continue
    fi

    # Spared (advisory clean): a TestDispatcher seam or a bounded pump.
    if timing1_has_test_dispatcher "$file" || timing1_has_bounded_pump "$file"; then
      continue
    fi
    # Opted out via an inline // JUSTIFIED: comment.
    if grep -Eq '//[[:space:]]*JUSTIFIED:' "$file"; then
      TIMING1_JUSTIFIED+=("$file")
      continue
    fi
    if in_list "$file" "${TIMING1_BASELINE[@]}"; then
      TIMING1_KNOWN+=("$file")
    else
      TIMING1_FINDINGS+=("$file")
    fi
  done
}

# --------------------------------------------------------------------------
# Validate baselines: prune entries whose file no longer exists.
# --------------------------------------------------------------------------
declare -a STALE_BASELINE=()
for b in "${A5_BASELINE[@]}" "${C1_BASELINE[@]}" "${FAKE1_BASELINE[@]}" "${AWAIT1_BASELINE[@]}" "${TIMING1_BASELINE[@]}"; do
  [[ -f "$b" ]] || STALE_BASELINE+=("$b")
done

scan_a5
scan_a4
scan_c1
scan_fake1
scan_await1
scan_j1
scan_timing1

echo "=============================================================="
echo " Test-validity guard (issue #657 / F4; extended #848 / #850 / #1048)"
echo " Scanned test roots:"
for r in "${TEST_ROOTS[@]}"; do echo "   - $r/**/*.kt"; done
echo " Connect-path RPC sources: $RPC_SOURCE_ROOT/**/*RemoteSource.kt (+ FolderListViewModel.kt)"
echo " CI journey suite: $CI_JOURNEY_SUITE (${#J1_WIRED_ANDROID_TEST_CLASSES[@]} androidTest class entr(y/ies) parsed)"
echo "=============================================================="

print_list() {
  local label="$1"; shift
  local -a items=()
  local arg
  for arg in "$@"; do
    [[ -n "$arg" ]] && items+=("$arg")
  done
  echo
  echo "$label (${#items[@]}):"
  if [[ "${#items[@]}" -eq 0 ]]; then
    echo "  (none)"
  else
    local i
    for i in "${items[@]}"; do
      echo "  - $i"
    done
  fi
}

print_list "A5 — NEW unjustified IME-availability assumeTrue self-skip [HARD FAIL]" "${A5_NEW[@]:-}"
print_list "A5 — KNOWN baseline (catalogued by #657; rewrite is per-issue follow-up) [advisory]" "${A5_KNOWN[@]:-}"
print_list "A5 — JUSTIFIED (opted out via // JUSTIFIED:) [advisory]" "${A5_JUSTIFIED[@]:-}"
print_list "A4/A2 — StandIn/Proxy in a smell-named proof file [advisory]" "${A4_FINDINGS[@]:-}"
print_list "C1 — NEW unjustified assumeFalse(isRunningOnCi()) self-skip outside fault classes [HARD FAIL]" "${C1_NEW[@]:-}"
print_list "C1 — KNOWN baseline (opt-in fault/Docker fixture skip; #848) [advisory]" "${C1_KNOWN[@]:-}"
print_list "C1 — JUSTIFIED (self-describing opt-in fixture or // JUSTIFIED:) [advisory]" "${C1_JUSTIFIED[@]:-}"
print_list "FAKE1 — NEW connect-path test with an always-answering fake (no fault case) [advisory]" "${FAKE1_FINDINGS[@]:-}"
print_list "FAKE1 — KNOWN baseline (always-answering connect fake; #847/#849) [advisory]" "${FAKE1_KNOWN[@]:-}"
print_list "AWAIT1 — NEW unbounded connect-path RPC await (no withTimeout) [advisory]" "${AWAIT1_FINDINGS[@]:-}"
print_list "AWAIT1 — KNOWN baseline (unbounded connect RPC; #847) [advisory]" "${AWAIT1_KNOWN[@]:-}"
print_list "J1 — WIRED androidTest E2e/Docker classes in ci-journey-suite.sh [advisory]" "${J1_WIRED[@]:-}"
print_list "J1 — NEW androidTest E2e/Docker class missing ci-journey-suite coverage or local justification [HARD FAIL]" "${J1_NEW[@]:-}"
print_list "J1 — KNOWN unwired androidTest E2e/Docker baseline (#848 follow-up) [advisory]" "${J1_KNOWN[@]:-}"
print_list "J1 — JUSTIFIED local CI_JOURNEY_SUITE_JUSTIFIED exemption [advisory]" "${J1_JUSTIFIED[@]:-}"
print_list "J1 — STALE unwired baseline entry [HARD FAIL]" "${J1_STALE_BASELINE[@]:-}"
print_list "J1 — PARSER failure reading ci-journey-suite.sh [HARD FAIL]" "${J1_PARSER_FAILURE[@]:-}"
print_list "TIMING1 — NEW bare Thread.sleep(N) before a load-bearing assert, no bounded loop [HARD FAIL]" "${TIMING1_NEW_HARD[@]:-}"
print_list "TIMING1 — NEW runTest over a real dispatcher/thread without a pinned seam or bounded pump [advisory]" "${TIMING1_FINDINGS[@]:-}"
print_list "TIMING1 — KNOWN baseline (real-dispatcher/thread runTest catalogued; seam adoption is per-test follow-up) [advisory]" "${TIMING1_KNOWN[@]:-}"
print_list "TIMING1 — JUSTIFIED (opted out via // JUSTIFIED:) [advisory]" "${TIMING1_JUSTIFIED[@]:-}"

if [[ "${#STALE_BASELINE[@]}" -gt 0 ]]; then
  echo
  echo "NOTE: ${#STALE_BASELINE[@]} baseline entr(y/ies) no longer exist — prune from the *_BASELINE arrays in this script:"
  for s in "${STALE_BASELINE[@]}"; do
    echo "  - $s"
  done
fi

echo
echo "--------------------------------------------------------------"
echo "Corrective models:"
echo " A5/C1  app/src/androidTest/java/com/pocketshell/app/composer/"
echo "        PromptComposerImeSquishProofTest.kt (#780) — synthetic"
echo "        inset + boundsInRoot containment + HARD assert, no skip."
echo " FAKE1  add a connect-RPC FAULT case (old/missing CLI -> non-zero,"
echo "        never-returns/hang, timeout) so Loading must still resolve"
echo "        (the v0.4.10 #847 gap; fixture work tracked in #849)."
echo " AWAIT1 bound the warm-session RPC with withTimeout so a"
echo "        non-returning exec cannot pin the cold-start coroutine (#847)."
echo " J1     wire the androidTest journey into scripts/ci-journey-suite.sh"
echo "        or add a local // CI_JOURNEY_SUITE_JUSTIFIED: reason."
echo " TIMING1 Shape A: inject StandardTestDispatcher(testScheduler) for every"
echo "        owned scope (SshLeaseAcquireBoundCharacterizationTest:191-219)."
echo "        Shape B: drive an Android Handler/Thread worker with a bounded"
echo "        advanceUntilIdle()+idleFor(16ms) pump to a currentTimeMillis/"
echo "        nanoTime deadline that HARD-FAILS (TmuxSessionWarmOpenTest.pumpUntil"
echo "        :131-150; codex pump TmuxSessionViewModelTest:5602-5657)."
echo "--------------------------------------------------------------"

# Collect the HARD-FAIL categories (A5 + C1 + J1 + TIMING1).
real_hard_fail=()
for x in \
  "${A5_NEW[@]:-}" \
  "${C1_NEW[@]:-}" \
  "${J1_NEW[@]:-}" \
  "${J1_STALE_BASELINE[@]:-}" \
  "${J1_PARSER_FAILURE[@]:-}" \
  "${TIMING1_NEW_HARD[@]:-}"; do
  [[ -n "$x" ]] && real_hard_fail+=("$x")
done

if [[ "$REPORT_MODE" -eq 1 ]]; then
  echo
  echo "Report mode (--report): findings printed; guard does not fail."
  exit 0
fi

if [[ "${#real_hard_fail[@]}" -gt 0 ]]; then
  echo
  echo "::error title=Test-validity guard (issue #657/#848/#1048)::A NEW load-bearing self-skip, ungated androidTest journey, or fixed-sleep-before-assert was found. An IME/keyboard/geometry test must not gate its assertion behind assumeTrue(...) (convert to the synthetic-inset model, #780), a connect/journey test must not gate behind assumeFalse(isRunningOnCi()) outside a genuine opt-in fault/Docker fixture (inject the state and HARD-assert, or add an inline // JUSTIFIED: comment naming the opt-in fixture), a new androidTest *E2eTest/*DockerTest class must be wired into scripts/ci-journey-suite.sh or carry a local // CI_JOURNEY_SUITE_JUSTIFIED: reason, and a connection/terminal runTest test must not use a bare Thread.sleep(N) as the only sync before a load-bearing assert (use a StandardTestDispatcher seam or a bounded advanceUntilIdle()+idleFor() deadline pump per #1048). Remove stale J1 baselines when a class is promoted or deleted."
  echo
  echo "FAIL: ${#real_hard_fail[@]} unjustified hard-fail occurrence(s) (A5 + C1 + J1 + TIMING1)."
  exit 1
fi

echo
echo "PASS: no new unjustified load-bearing self-skips, ungated androidTest journeys, or fixed-sleep-before-assert flakes (A5 + C1 + J1 + TIMING1)."
exit 0
