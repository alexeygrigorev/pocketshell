#!/usr/bin/env bash
# CI journey harness guard (issues #848 / #788 / #743).
#
# The per-push journey allowlist in scripts/ci-journey-suite.sh is the set that
# can block a PR before a broken on-device journey reaches main. For listed
# com.pocketshell.app.proof.* journeys that launch MainActivity, the durable
# harness shape is:
#
#   createAndroidComposeRule<MainActivity>() + SeedBeforeLaunchRule
#
# The old shape, createEmptyComposeRule() plus manual ActivityScenario.launch,
# was the #743/#788 interop-placement stall. A test may keep a manual launch
# only with a local inline exemption comment:
#
#   // JOURNEY_HARNESS_JUSTIFIED: <why manual ActivityScenario is required>
#
# Current main still has known old-harness classes and some launch-owned classes
# with hand-rolled pre-launch seed rules. Those are explicit baselines so this
# guard pins the harness contract without rewriting the #911 migration backlog.

set -uo pipefail

REPO_ROOT="${POCKETSHELL_JOURNEY_HARNESS_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$REPO_ROOT" || exit 1

SUITE="${POCKETSHELL_JOURNEY_HARNESS_SUITE:-scripts/ci-journey-suite.sh}"
ANDROID_TEST_ROOT="${POCKETSHELL_JOURNEY_HARNESS_ANDROID_TEST_ROOT:-app/src/androidTest/java}"
NIGHTLY_SUITE="${POCKETSHELL_JOURNEY_HARNESS_NIGHTLY_SUITE:-scripts/nightly-extensive-suite.sh}"
REPORT_MODE=0
if [[ "${1:-}" == "--report" ]]; then
  REPORT_MODE=1
fi

if [[ ! -f "$SUITE" ]]; then
  echo "::error title=CI journey harness guard::cannot find $SUITE"
  exit 1
fi

# Known current-main manual harnesses. Remove entries as the corresponding
# journey migrates to createAndroidComposeRule<MainActivity>() +
# SeedBeforeLaunchRule.
KNOWN_MANUAL_HARNESS=(
  "DeepLinkSessionSwitchE2eTest"
  "WithinGraceSocketDropForegroundJourneyE2eTest"
  "ReconnectPartialBlankReseedJourneyE2eTest"
  "RedrawFullViewportReseedJourneyE2eTest"
  "RedrawNonDestructiveNearBlankCaptureE2eTest"
  "StaleRenderHealOnLiveTransportJourneyE2eTest"
  "LongRunningSessionStabilityTest"
  "RealisticWifiStabilityNoSpuriousReconnectE2eTest"
  "ComposerAlwaysPresentSwitchJourneyE2eTest"
  "LaunchNoMainThreadRoomReadE2eTest"
)

# Known current-main launch-owned classes that do not use the shared
# SeedBeforeLaunchRule. Remove entries when those classes adopt it.
KNOWN_LAUNCH_OWNED_WITHOUT_SHARED_SEED=(
  "AttachmentNoReconnectE2eTest"
  "SendNoReconnectE2eTest"
  "StableWifiNoSpuriousReconnectE2eTest"
  "BareNetworkLossRestoreReconnectE2eTest"
  "SilentDropSyntheticSeamJourneyE2eTest"
  "CleanOutageReattachResilienceE2eTest"
  "Issue895SwitchWhileBlackBandJourneyE2eTest"
  "AgentSubmitAckJourneyE2eTest"
  "ReconnectKebabInPlaceJourneyE2eTest"
)

# Narrow #848 audit pins: connected androidTests that must stay in the per-push
# journey allowlist even though they do not necessarily use the proof package or
# E2e/Docker suffix convention.
REQUIRED_PER_PUSH_ANDROID_TEST_CLASSES=(
  "com.pocketshell.app.composer.PromptComposerOutboundQueueTest"
)

# Exact selectors whose method-level attendance is part of the acceptance
# contract. A bare class selector is not equivalent: it can silently broaden or
# stop naming the one method whose JUnit XML must prove attendance. The source
# method must exist as a JUnit test and must not self-skip on CI.
REQUIRED_PER_PUSH_ANDROID_TEST_SELECTORS=(
  "com.pocketshell.app.notifications.UpdateAvailableNotificationE2eTest#updateNotification_postsToStatusBar_andIsTappable"
)

# Current-main androidTest E2e/Docker backlog that is intentionally not in the
# per-push journey allowlist. New *E2eTest/*DockerTest classes must either be
# wired into scripts/ci-journey-suite.sh or added here with an intentional
# follow-up. Keep full FQCNs so moves are visible.
# Non-E2e/Docker concrete classes (Screenshot/Scaffold/Ui/Proof/component) are
# covered by scripts/androidtest-unwired-baseline.txt (issue #2188).
KNOWN_UNWIRED_ANDROID_E2E_DOCKER_CLASSES=(
  "com.pocketshell.app.composer.ComposerPartialExpandE2eTest"
  "com.pocketshell.app.costs.CostsScreenE2eTest"
  "com.pocketshell.app.crash.ShareAllReportsDockerTest"
  "com.pocketshell.app.fileexplorer.FileExplorerDockerTest"
  "com.pocketshell.app.fileviewer.LinkTapParsingDockerTest"
  "com.pocketshell.app.fileviewer.TerminalFilePathTapToViewerDockerTest"
  "com.pocketshell.app.git.GitHistoryDockerTest"
  "com.pocketshell.app.hosts.DefaultHostLaunchE2eTest"
  "com.pocketshell.app.hosts.HostAndFolderListScrollE2eTest"
  "com.pocketshell.app.hosts.HostEditFromKebabE2eTest"
  "com.pocketshell.app.portfwd.ForwardingIndicatorE2eTest"
  "com.pocketshell.app.projects.AgentLaunchCommandDockerTest"
  "com.pocketshell.app.projects.FolderListGatewayDockerTest"
  "com.pocketshell.app.projects.FolderListGatewayStaleChannelHealDockerTest"
  "com.pocketshell.app.projects.FolderListKillSessionDockerTest"
  "com.pocketshell.app.projects.FolderListOutOfBandSessionDockerTest"
  "com.pocketshell.app.projects.FolderListSessionResumeDockerTest"
  "com.pocketshell.app.projects.FolderListTreeStopSessionDockerTest"
  "com.pocketshell.app.projects.WatchedFoldersE2eTest"
  # Issue #2380: the END-TO-END attach-navigation guard for the nightly phase-2
  # network-fault lane's own shared setup (the selector that made phase 2 vacuous by
  # dying on the `::untracked::` row before any fault was injected). A
  # NetworkFaultProofBase toxiproxy proof: it self-skips on CI
  # (assumeNetworkFaultProofsEnabled -> tests.yml leaves network-fault-proxy:2228 +
  # toxiproxy:8474 down), so wiring it into the per-PR ci-journey-suite.sh would only
  # ALL-SKIP (the G3 vacuous-pass trap). It is enrolled in NETWORK_FAULT_CLASSES and
  # runs via nightly-extensive.yml / scripts/nightly-extensive-suite.sh (proxy up).
  # The per-push half of the same red->green is the Docker-free
  # com.pocketshell.app.proof.FolderSessionRowNavigatorTest, which IS wired into
  # scripts/ci-journey-suite.sh.
  "com.pocketshell.app.proof.AttachNavigationMultiFolderE2eTest"
  "com.pocketshell.app.proof.CodexOverflowNoReconnectE2eTest"
  "com.pocketshell.app.proof.CodexRedrawOverflowReconnectE2eTest"
  "com.pocketshell.app.proof.CodexWindowStartupControlSequenceE2eTest"
  # Nightly-extensive-only slow cold-dial robustness proof (#1064, R4). A
  # NetworkFaultProofBase toxiproxy proof: it self-skips on CI
  # (assumeNetworkFaultProofsEnabled -> tests.yml leaves network-fault-proxy:2228 +
  # toxiproxy:8474 down), so wiring it into the per-PR ci-journey-suite.sh would only
  # ALL-SKIP (the G3 vacuous-pass trap). It is enrolled in NETWORK_FAULT_CLASSES and
  # runs via nightly-extensive.yml / scripts/nightly-extensive-suite.sh (proxy up).
  "com.pocketshell.app.proof.ColdDialUnderBandwidthLimitE2eTest"
  "com.pocketshell.app.proof.ColdInstallE2eTest"
  "com.pocketshell.app.proof.DisconnectBlackholeE2eTest"
  "com.pocketshell.app.proof.DisconnectFlapSoakE2eTest"
  "com.pocketshell.app.proof.EmulatorWorkflowE2eTest"
  "com.pocketshell.app.proof.FastResumeReconnectE2eTest"
  # TEMPORARY (issue #1854) — REMOVE THIS ENTRY AND WIRE THE CLASS INTO
  # scripts/ci-journey-suite.sh AT INTEGRATION. It is the reproduction for the
  # `printf` -> `tf` typed-input corruption and belongs in the per-push gate; it
  # uses the default `agents:2222` fixture, so no tests.yml change is needed. It
  # is parked here only because #1854's implementer brief ruled
  # ci-journey-suite.sh out of scope while #1846 (approved, awaiting
  # integration) and #1851 (in flight) both edit that file's test array, and a
  # third concurrent edit would clobber one of them on merge.
  # Nightly-extensive-only mobile-network self-inflicted storm reproduction
  # (#1681 / #1680 Track B). A NetworkFaultProofBase toxiproxy proof: it self-skips
  # on CI (assumeNetworkFaultProofsEnabled -> tests.yml leaves network-fault-proxy:2228
  # + toxiproxy:8474 down), so wiring it into the per-PR ci-journey-suite.sh would only
  # ALL-SKIP (the G3 vacuous-pass trap). It is enrolled in NETWORK_FAULT_CLASSES and
  # runs via nightly-extensive.yml / scripts/nightly-extensive-suite.sh (proxy up).
  "com.pocketshell.app.proof.MobileLatencyStormSelfInflictedCloseE2eTest"
  "com.pocketshell.app.proof.MultiHostSessionE2eTest"
  # Nightly-extensive-only carrier-NAT idle-mapping RECOVERY proof (#1063, R3).
  # A NetworkFaultProofBase toxiproxy half-open proof: it self-skips on CI
  # (assumeNetworkFaultProofsEnabled -> tests.yml leaves network-fault-proxy:2228 +
  # toxiproxy:8474 down), so wiring it into the per-PR ci-journey-suite.sh would only
  # ALL-SKIP (the G3 vacuous-pass trap). It is enrolled in NETWORK_FAULT_CLASSES and
  # runs via nightly-extensive.yml / scripts/nightly-extensive-suite.sh (proxy up).
  # The load-bearing per-push red->green survival pin lives in the Unit gate
  # (shared/core-ssh NatIdleMappingSurvivalKeepAliveTest).
  "com.pocketshell.app.proof.NatIdleMappingSurvivalE2eTest"
  "com.pocketshell.app.proof.NavigatorBackForegroundNoSshE2eTest"
  "com.pocketshell.app.proof.NetworkLatencyModelE2eTest"
  "com.pocketshell.app.proof.NoBackgroundWorkE2eTest"
  "com.pocketshell.app.proof.PacketLossNetworkFaultE2eTest"
  "com.pocketshell.app.proof.ProjectSwitcherDropdownE2eTest"
  # Issue #1139: nightly toxiproxy proof (NetworkFaultProofBase subclass) — the
  # push-resume-onto-dead-socket Main-responsiveness freeze proof. Needs the
  # half-open blackhole to wedge the real close, so it runs in the nightly
  # network-fault lane (scripts/nightly-extensive-suite.sh), not the per-push
  # journey allowlist.
  "com.pocketshell.app.proof.PushResumeDeadSocketMainResponsiveE2eTest"
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
  "com.pocketshell.app.proof.TmuxOrphanClientCleanupE2eTest"
  "com.pocketshell.app.proof.TmuxSessionSwitchE2eTest"
  "com.pocketshell.app.proof.TmuxSessionSwitchSameHostReusesSshE2eTest"
  "com.pocketshell.app.proof.WarmLeaseReuseBatchCDockerTest"
  "com.pocketshell.app.proof.WarmLeaseReuseDockerTest"
  "com.pocketshell.app.proof.WithinGraceResumeRideThroughE2eTest"
  "com.pocketshell.app.sessions.service.SessionConnectionServiceE2eTest"
  "com.pocketshell.app.session.ConversationToolResultPairingE2eTest"
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
  "com.pocketshell.app.usage.UsageScreenE2eTest"
)

# Issue #2188: every OTHER concrete androidTest class (Screenshot / Scaffold /
# Ui / Proof / component) must be in scripts/ci-journey-suite.sh or explicitly
# exempted with a reason. A class that only compiles (dex job) and never runs
# is how TmuxConsolidatedChromeScreenshotTest sat red on main unnoticed.
# E2e/Docker backlog stays in KNOWN_UNWIRED_ANDROID_E2E_DOCKER_CLASSES above —
# do not duplicate it in the sidecar. Override the path in the self-test.
ANDROIDTEST_UNWIRED_BASELINE="${POCKETSHELL_ANDROIDTEST_UNWIRED_BASELINE:-scripts/androidtest-unwired-baseline.txt}"

in_list() {
  local item="$1"; shift
  local candidate
  for candidate in "$@"; do
    [[ "$item" == "$candidate" ]] && return 0
  done
  return 1
}

is_code_line() {
  ! printf '%s' "$1" | grep -Eq '^[[:space:]]*(\*|//|import |/\*)'
}

line_has_exemption() {
  local file="$1" lineno="$2"
  local start=$((lineno - 2))
  [[ "$start" -lt 1 ]] && start=1
  sed -n "${start},${lineno}p" "$file" |
    grep -Eq 'JOURNEY_HARNESS_JUSTIFIED:[[:space:]]*[^[:space:]]'
}

manual_harness_hits() {
  local file="$1"
  grep -nE '(^|[[:space:]])(val[[:space:]]+compose[[:space:]]*=[[:space:]]*)?createEmptyComposeRule[[:space:]]*\(|ActivityScenario\.launch[[:space:]]*\(' "$file" 2>/dev/null || true
}

has_shared_seed_rule_call() {
  local file="$1"
  local hit text
  while IFS= read -r hit; do
    [[ -z "$hit" ]] && continue
    text="${hit#*:}"
    is_code_line "$text" || continue
    text="${text%%//*}"
    printf '%s\n' "$text" | grep -Eq 'SeedBeforeLaunchRule[[:space:]]*(\(|\{)' && return 0
  done < <(grep -n 'SeedBeforeLaunchRule' "$file" 2>/dev/null || true)
  return 1
}

class_file_for() {
  local class_name="$1"
  printf '%s/com/pocketshell/app/proof/%s.kt\n' "$ANDROID_TEST_ROOT" "$class_name"
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

file_has_line_annotation() {
  local file="$1" name="$2"
  grep -Eq "^[[:space:]]*@(${name}|org[.]junit[.]${name})([[:space:]]|\$|[[:space:]]*\\()" "$file"
}

is_abstract_class_file() {
  grep -Eq '^[[:space:]]*abstract[[:space:]]+class[[:space:]]+' "$1"
}

is_e2e_or_docker_fqcn() {
  case "$1" in
    *E2eTest|*DockerTest) return 0 ;;
  esac
  return 1
}

file_has_androidtest_gate_justification() {
  grep -Eq 'ANDROIDTEST_GATE_JUSTIFIED:[[:space:]]*[^[:space:]]' "$1"
}

declare -a JOURNEY_CLASSES=()
declare -A SEEN=()
while IFS= read -r class_name; do
  [[ -z "${class_name:-}" ]] && continue
  if [[ -z "${SEEN[$class_name]:-}" ]]; then
    JOURNEY_CLASSES+=("$class_name")
    SEEN[$class_name]=1
  fi
done < <(
  sed -nE \
    -e 's/^[[:space:]]*"\$FQCN_PREFIX\.([A-Za-z0-9_]+)(#[^"]*)?".*/\1/p' \
    -e 's/^[[:space:]]*"com\.pocketshell\.app\.proof\.([A-Za-z0-9_]+)(#[^"]*)?".*/\1/p' \
    "$SUITE"
)

declare -a WIRED_ANDROID_TEST_CLASSES=()
declare -A WIRED_ANDROID_TEST_SEEN=()
while IFS= read -r fqcn; do
  [[ -z "${fqcn:-}" ]] && continue
  if [[ -z "${WIRED_ANDROID_TEST_SEEN[$fqcn]:-}" ]]; then
    WIRED_ANDROID_TEST_CLASSES+=("$fqcn")
    WIRED_ANDROID_TEST_SEEN[$fqcn]=1
  fi
done < <(
  sed -nE \
    -e 's/^[[:space:]]*"\$FQCN_PREFIX\.([A-Za-z0-9_]+)(#[^"]*)?".*/com.pocketshell.app.proof.\1/p' \
    -e 's/^[[:space:]]*"(com\.pocketshell\.app\.[A-Za-z0-9_.]+)(#[^"]*)?".*/\1/p' \
    "$SUITE"
)

declare -a WIRED_ANDROID_TEST_SELECTORS=()
declare -A WIRED_ANDROID_TEST_SELECTOR_SEEN=()
while IFS= read -r selector; do
  [[ -z "${selector:-}" ]] && continue
  if [[ -z "${WIRED_ANDROID_TEST_SELECTOR_SEEN[$selector]:-}" ]]; then
    WIRED_ANDROID_TEST_SELECTORS+=("$selector")
    WIRED_ANDROID_TEST_SELECTOR_SEEN[$selector]=1
  fi
done < <(
  sed -nE \
    -e 's/^[[:space:]]*"\$FQCN_PREFIX\.([A-Za-z0-9_]+)(#[A-Za-z0-9_]+)?".*/com.pocketshell.app.proof.\1\2/p' \
    -e 's/^[[:space:]]*"(com\.pocketshell\.app\.[A-Za-z0-9_.]+)(#[A-Za-z0-9_]+)?".*/\1\2/p' \
    "$SUITE"
)

declare -a MISSING_FILES=()
declare -a MANUAL_NEW=()
declare -a MANUAL_KNOWN=()
declare -a MANUAL_JUSTIFIED=()
declare -a MISSING_SHARED_SEED_NEW=()
declare -a MISSING_SHARED_SEED_KNOWN=()
declare -a MISSING_SHARED_SEED_JUSTIFIED=()
declare -a COMPLIANT=()
declare -a NOT_MAINACTIVITY_LAUNCHERS=()
declare -a STALE_BASELINE=()
declare -a PARSER_FAILURE=()
declare -a REQUIRED_PER_PUSH_WIRED=()
declare -a MISSING_REQUIRED_PER_PUSH=()
declare -a REQUIRED_PER_PUSH_SELECTOR_WIRED=()
declare -a MISSING_REQUIRED_PER_PUSH_SELECTOR=()
declare -a INVALID_REQUIRED_PER_PUSH_SELECTOR_SOURCE=()
declare -a REQUIRED_PER_PUSH_SELECTOR_CI_SELF_SKIP=()
declare -a UNWIRED_ANDROID_E2E_DOCKER_NEW=()
declare -a UNWIRED_ANDROID_E2E_DOCKER_KNOWN=()
declare -a STALE_UNWIRED_ANDROID_E2E_DOCKER_BASELINE=()
declare -a NIGHTLY_FIXTURE_ROUTING_FAILURE=()
declare -a BASELINE_UNWIRED_ANDROID_TEST_CLASSES=()
declare -A BASELINE_UNWIRED_ANDROID_TEST_SEEN=()
declare -a UNWIRED_ANDROID_TEST_NEW=()
declare -a UNWIRED_ANDROID_TEST_KNOWN=()
declare -a UNWIRED_ANDROID_TEST_JUSTIFIED=()
declare -a STALE_UNWIRED_ANDROID_TEST_BASELINE=()
declare -a ANDROIDTEST_BASELINE_PARSE_FAILURE=()

if [[ -f "$ANDROIDTEST_UNWIRED_BASELINE" ]]; then
  while IFS=$'\t' read -r fqcn reason || [[ -n "${fqcn:-}" ]]; do
    [[ -z "${fqcn:-}" || "$fqcn" == \#* ]] && continue
    if [[ "$fqcn" != com.pocketshell.app.* ]]; then
      ANDROIDTEST_BASELINE_PARSE_FAILURE+=("not an app androidTest FQCN: $fqcn")
      continue
    fi
    if is_e2e_or_docker_fqcn "$fqcn"; then
      ANDROIDTEST_BASELINE_PARSE_FAILURE+=("E2e/Docker belongs in KNOWN_UNWIRED_ANDROID_E2E_DOCKER_CLASSES: $fqcn")
      continue
    fi
    if [[ -z "${reason:-}" ]]; then
      ANDROIDTEST_BASELINE_PARSE_FAILURE+=("missing reason: $fqcn")
      continue
    fi
    if [[ -z "${BASELINE_UNWIRED_ANDROID_TEST_SEEN[$fqcn]:-}" ]]; then
      BASELINE_UNWIRED_ANDROID_TEST_CLASSES+=("$fqcn")
      BASELINE_UNWIRED_ANDROID_TEST_SEEN[$fqcn]=1
    fi
  done < "$ANDROIDTEST_UNWIRED_BASELINE"
fi

if [[ "${#JOURNEY_CLASSES[@]}" -eq 0 ]]; then
  PARSER_FAILURE+=("NO_PROOF_CLASSES_PARSED")
fi
if [[ "${#WIRED_ANDROID_TEST_CLASSES[@]}" -eq 0 ]]; then
  PARSER_FAILURE+=("NO_ANDROID_TEST_CLASSES_PARSED")
fi

for fqcn in "${REQUIRED_PER_PUSH_ANDROID_TEST_CLASSES[@]}"; do
  if ! in_list "$fqcn" "${WIRED_ANDROID_TEST_CLASSES[@]}"; then
    MISSING_REQUIRED_PER_PUSH+=("$fqcn")
  elif [[ ! -f "$(android_class_file_for "$fqcn")" ]]; then
    MISSING_REQUIRED_PER_PUSH+=("$fqcn -> $(android_class_file_for "$fqcn")")
  else
    REQUIRED_PER_PUSH_WIRED+=("$fqcn")
  fi
done


required_selector_has_ci_self_skip() {
  local file="$1"
  local lineno text joined
  while IFS= read -r lineno; do
    [[ -z "$lineno" ]] && continue
    text="$(sed -n "${lineno}p" "$file")"
    is_code_line "$text" || continue
    joined="$(sed -n "${lineno},$((lineno + 12))p" "$file" | tr '\n' ' ')"
    printf '%s' "$joined" | grep -q 'isRunningOnCi' && return 0
  done < <(
    grep -nE '(^|[^.[:alnum:]])(assumeTrue|assumeFalse|Assume\.assumeTrue|Assume\.assumeFalse)[[:space:]]*\(' \
      "$file" 2>/dev/null | cut -d: -f1
  )
  return 1
}

required_selector_is_test_method() {
  local file="$1"
  local method="$2"
  awk -v wanted="$method" '
    function code_only(line,    out, i, one, two, three) {
      out = ""
      i = 1
      while (i <= length(line)) {
        one = substr(line, i, 1)
        two = substr(line, i, 2)
        three = substr(line, i, 3)

        if (block_depth > 0) {
          if (two == "/*") {
            block_depth++
            i += 2
          } else if (two == "*/") {
            block_depth--
            i += 2
          } else {
            i++
          }
          continue
        }

        if (raw_string) {
          if (three == "\"\"\"") {
            raw_string = 0
            i += 3
          } else {
            i++
          }
          continue
        }

        if (string_literal) {
          if (one == "\\") {
            i += (i < length(line)) ? 2 : 1
          } else {
            if (one == "\"") string_literal = 0
            i++
          }
          continue
        }

        if (char_literal) {
          if (one == "\\") {
            i += (i < length(line)) ? 2 : 1
          } else {
            if (one == single_quote) char_literal = 0
            i++
          }
          continue
        }

        if (two == "//") {
          break
        } else if (two == "/*") {
          block_depth = 1
          out = out " "
          i += 2
        } else if (three == "\"\"\"") {
          raw_string = 1
          out = out " "
          i += 3
        } else if (one == "\"") {
          string_literal = 1
          out = out " "
          i++
        } else if (one == single_quote) {
          char_literal = 1
          out = out " "
          i++
        } else {
          out = out one
          i++
        }
      }

      # Ordinary Kotlin strings and chars cannot cross a physical line.
      string_literal = 0
      char_literal = 0
      return out
    }

    BEGIN {
      single_quote = sprintf("%c", 39)
      modifier = "(public|private|protected|internal|open|final|override|abstract|suspend|operator|infix|inline|tailrec|external)"
      target_method = "^(" modifier "[[:space:]]+)*fun[[:space:]]+" wanted "[[:space:]]*\\("
      test_annotation = "^@(org[.]junit[.])?Test([[:space:]]*\\([^)]*\\))?[[:space:]]*$"
      other_annotation = "^@[A-Za-z_][A-Za-z0-9_.:]*([[:space:]]*\\([^)]*\\))?[[:space:]]*$"
    }

    {
      code = code_only($0)
      sub(/^[[:space:]]+/, "", code)
      sub(/[[:space:]]+$/, "", code)
      if (code == "") next

      if (code ~ test_annotation) {
        pending_test = 1
        next
      }
      if (pending_test && code ~ other_annotation) next

      if (code ~ target_method) {
        if (pending_test) found = 1
        exit
      }
      pending_test = 0
    }

    END { exit(found ? 0 : 1) }
  ' "$file"
}

for selector in "${REQUIRED_PER_PUSH_ANDROID_TEST_SELECTORS[@]}"; do
  if ! in_list "$selector" "${WIRED_ANDROID_TEST_SELECTORS[@]}"; then
    MISSING_REQUIRED_PER_PUSH_SELECTOR+=("$selector")
    continue
  fi

  fqcn="${selector%%#*}"
  method="${selector#*#}"
  file="$(android_class_file_for "$fqcn")"
  if [[ ! -f "$file" ]]; then
    INVALID_REQUIRED_PER_PUSH_SELECTOR_SOURCE+=("$selector -> $file")
    continue
  fi

  if ! required_selector_is_test_method "$file" "$method"; then
    INVALID_REQUIRED_PER_PUSH_SELECTOR_SOURCE+=("$selector -> missing @Test method in $file")
    continue
  fi

  if required_selector_has_ci_self_skip "$file"; then
    REQUIRED_PER_PUSH_SELECTOR_CI_SELF_SKIP+=("$selector -> CI self-skip in $file")
    continue
  fi

  REQUIRED_PER_PUSH_SELECTOR_WIRED+=("$selector")
done

# Issue #1866: this class has a deliberate hard precondition on the Toxiproxy
# opt-in. Nightly phase 1 does not supply that fixture contract, so selecting it
# there creates a guaranteed red with zero product signal. Pin the deliberate
# routing decision: keep it hard-failing when the fixture is absent, include it
# in the release-GATING phase-2 network-fault set, and exclude that whole set
# from phase 1. This is intentionally specific to #1733 and does not broaden the
# unrelated nightly selection.
OUTBOUND_OFFSET_FQCN="com.pocketshell.app.proof.OutboundAttachmentOffsetResumeJourneyE2eTest"
OUTBOUND_OFFSET_SOURCE="$(android_class_file_for "$OUTBOUND_OFFSET_FQCN")"
if [[ -f "$OUTBOUND_OFFSET_SOURCE" ]]; then
  if [[ ! -f "$NIGHTLY_SUITE" ]]; then
    NIGHTLY_FIXTURE_ROUTING_FAILURE+=("missing nightly suite: $NIGHTLY_SUITE")
  else
    network_fault_block="$(sed -n '/^NETWORK_FAULT_CLASSES=(/,/^)$/p' "$NIGHTLY_SUITE")"
    journey_excluded_block="$(sed -n '/^JOURNEY_EXCLUDED_CLASSES=(/,/^)$/p' "$NIGHTLY_SUITE")"
    phase_two_block="$(sed -n '/phase 2: network-fault proofs/,/phase 2b:/p' "$NIGHTLY_SUITE")"

    if ! grep -Fq '"$FQCN_PREFIX.OutboundAttachmentOffsetResumeJourneyE2eTest"' <<<"$network_fault_block"; then
      NIGHTLY_FIXTURE_ROUTING_FAILURE+=("$OUTBOUND_OFFSET_FQCN is not in nightly NETWORK_FAULT_CLASSES")
    fi
    if ! grep -Fq '"${NETWORK_FAULT_CLASSES[@]}"' <<<"$journey_excluded_block"; then
      NIGHTLY_FIXTURE_ROUTING_FAILURE+=("nightly phase 1 no longer excludes NETWORK_FAULT_CLASSES")
    fi
    if ! grep -Fq 'pocketshellNetworkFaultProofs=true' <<<"$phase_two_block" ||
       ! grep -Fq 'class="$NETWORK_FAULT_CLASS_ARG"' <<<"$phase_two_block"; then
      NIGHTLY_FIXTURE_ROUTING_FAILURE+=("nightly phase 2 no longer selects NETWORK_FAULT_CLASSES with the Toxiproxy opt-in")
    fi
  fi

  outbound_precondition_block="$(grep -B 3 -A 6 -F 'issue #1733 requires the explicitly opted-in Toxiproxy fixture' "$OUTBOUND_OFFSET_SOURCE" || true)"
  if ! grep -Fq 'assertTrue(' <<<"$outbound_precondition_block" ||
     grep -Fq 'assumeTrue(' <<<"$outbound_precondition_block"; then
    NIGHTLY_FIXTURE_ROUTING_FAILURE+=("$OUTBOUND_OFFSET_FQCN no longer hard-fails its missing-Toxiproxy precondition")
  fi
fi

while IFS= read -r file; do
  [[ -z "${file:-}" ]] && continue
  fqcn="$(android_test_fqcn_for_file "$file")"
  if in_list "$fqcn" "${WIRED_ANDROID_TEST_CLASSES[@]}"; then
    continue
  fi
  if in_list "$fqcn" "${KNOWN_UNWIRED_ANDROID_E2E_DOCKER_CLASSES[@]}"; then
    UNWIRED_ANDROID_E2E_DOCKER_KNOWN+=("$fqcn")
  else
    UNWIRED_ANDROID_E2E_DOCKER_NEW+=("$fqcn")
  fi
done < <(
  find "$ANDROID_TEST_ROOT" -type f \
    \( -name '*E2eTest.kt' -o -name '*DockerTest.kt' \) \
    | sort
)

# Issue #2188: every concrete non-E2e/Docker androidTest class must be wired
# into the per-push suite, listed in the sidecar baseline with a reason, or
# carry a local ANDROIDTEST_GATE_JUSTIFIED comment. Helpers (no @Test) and
# abstract bases are skipped. E2e/Docker stay in the scan above.
if [[ -d "$ANDROID_TEST_ROOT" ]]; then
  while IFS= read -r file; do
    [[ -z "${file:-}" ]] && continue
    is_abstract_class_file "$file" && continue
    file_has_line_annotation "$file" "Test" || continue
    fqcn="$(android_test_fqcn_for_file "$file")"
    is_e2e_or_docker_fqcn "$fqcn" && continue
    if in_list "$fqcn" "${WIRED_ANDROID_TEST_CLASSES[@]}"; then
      if in_list "$fqcn" "${BASELINE_UNWIRED_ANDROID_TEST_CLASSES[@]}"; then
        STALE_UNWIRED_ANDROID_TEST_BASELINE+=("$fqcn")
      fi
      continue
    fi
    if file_has_androidtest_gate_justification "$file"; then
      UNWIRED_ANDROID_TEST_JUSTIFIED+=("$fqcn")
      continue
    fi
    if in_list "$fqcn" "${BASELINE_UNWIRED_ANDROID_TEST_CLASSES[@]}"; then
      UNWIRED_ANDROID_TEST_KNOWN+=("$fqcn")
    else
      UNWIRED_ANDROID_TEST_NEW+=("$fqcn")
    fi
  done < <(find "$ANDROID_TEST_ROOT" -type f -name '*.kt' | sort)
fi

for fqcn in "${BASELINE_UNWIRED_ANDROID_TEST_CLASSES[@]}"; do
  if [[ ! -f "$(android_class_file_for "$fqcn")" ]]; then
    STALE_UNWIRED_ANDROID_TEST_BASELINE+=("$fqcn")
  fi
done

for class_name in "${JOURNEY_CLASSES[@]}"; do
  file="$(class_file_for "$class_name")"
  if [[ ! -f "$file" ]]; then
    MISSING_FILES+=("$class_name -> $file")
    continue
  fi

  has_android_rule=0
  has_shared_seed=0
  launches_main=0
  grep -Eq 'createAndroidComposeRule[[:space:]]*<[[:space:]]*MainActivity[[:space:]]*>[[:space:]]*\(' "$file" && has_android_rule=1
  has_shared_seed_rule_call "$file" && has_shared_seed=1
  if [[ "$has_android_rule" -eq 1 ]] ||
     grep -Eq 'ActivityScenario[[:space:]]*<[[:space:]]*MainActivity[[:space:]]*>|ActivityScenario\.launch[[:space:]]*\([[:space:]]*MainActivity::class\.java[[:space:]]*\)|Intent[[:space:]]*\([^)]*MainActivity::class\.java' "$file"; then
    launches_main=1
  fi

  if [[ "$launches_main" -eq 0 ]]; then
    NOT_MAINACTIVITY_LAUNCHERS+=("$class_name")
    continue
  fi

  manual_unjustified=0
  manual_justified=0
  while IFS= read -r hit; do
    [[ -z "$hit" ]] && continue
    lineno="${hit%%:*}"
    text="${hit#*:}"
    is_code_line "$text" || continue
    if line_has_exemption "$file" "$lineno"; then
      manual_justified=1
    else
      manual_unjustified=1
    fi
  done < <(manual_harness_hits "$file")

  if [[ "$manual_unjustified" -eq 1 ]]; then
    if in_list "$class_name" "${KNOWN_MANUAL_HARNESS[@]}"; then
      MANUAL_KNOWN+=("$class_name")
    else
      MANUAL_NEW+=("$class_name")
    fi
    continue
  fi
  if [[ "$manual_justified" -eq 1 ]]; then
    MANUAL_JUSTIFIED+=("$class_name")
    continue
  fi

  if [[ "$has_android_rule" -eq 1 && "$has_shared_seed" -eq 1 ]]; then
    COMPLIANT+=("$class_name")
    continue
  fi

  if [[ "$has_android_rule" -eq 1 && "$has_shared_seed" -eq 0 ]]; then
    create_line="$(grep -nE 'createAndroidComposeRule[[:space:]]*<[[:space:]]*MainActivity[[:space:]]*>[[:space:]]*\(' "$file" | head -n 1 | cut -d: -f1)"
    if [[ -n "$create_line" ]] && line_has_exemption "$file" "$create_line"; then
      MISSING_SHARED_SEED_JUSTIFIED+=("$class_name")
    elif in_list "$class_name" "${KNOWN_LAUNCH_OWNED_WITHOUT_SHARED_SEED[@]}"; then
      MISSING_SHARED_SEED_KNOWN+=("$class_name")
    else
      MISSING_SHARED_SEED_NEW+=("$class_name")
    fi
  fi
done

for class_name in "${KNOWN_MANUAL_HARNESS[@]}"; do
  if in_list "$class_name" "${JOURNEY_CLASSES[@]}" && ! in_list "$class_name" "${MANUAL_KNOWN[@]}"; then
    STALE_BASELINE+=("KNOWN_MANUAL_HARNESS:$class_name")
  fi
done
for class_name in "${KNOWN_LAUNCH_OWNED_WITHOUT_SHARED_SEED[@]}"; do
  if in_list "$class_name" "${JOURNEY_CLASSES[@]}" && ! in_list "$class_name" "${MISSING_SHARED_SEED_KNOWN[@]}"; then
    STALE_BASELINE+=("KNOWN_LAUNCH_OWNED_WITHOUT_SHARED_SEED:$class_name")
  fi
done
for fqcn in "${KNOWN_UNWIRED_ANDROID_E2E_DOCKER_CLASSES[@]}"; do
  if [[ -f "$(android_class_file_for "$fqcn")" ]] && in_list "$fqcn" "${WIRED_ANDROID_TEST_CLASSES[@]}"; then
    STALE_UNWIRED_ANDROID_E2E_DOCKER_BASELINE+=("$fqcn")
  fi
done

print_list() {
  local label="$1"; shift
  local -a items=()
  local item
  for item in "$@"; do
    [[ -n "$item" ]] && items+=("$item")
  done
  echo
  echo "$label (${#items[@]}):"
  if [[ "${#items[@]}" -eq 0 ]]; then
    echo "  (none)"
  else
    for item in "${items[@]}"; do
      echo "  - $item"
    done
  fi
}

echo "=============================================================="
echo " CI journey harness guard (#848 / #788 / #743)"
echo " Suite: $SUITE"
echo " Proof journey classes listed: ${#JOURNEY_CLASSES[@]}"
echo " Android test classes wired: ${#WIRED_ANDROID_TEST_CLASSES[@]}"
echo "=============================================================="

print_list "PASS - required #848 per-push androidTest classes wired" "${REQUIRED_PER_PUSH_WIRED[@]:-}"
print_list "PASS - required exact per-push androidTest selectors are executable" "${REQUIRED_PER_PUSH_SELECTOR_WIRED[@]:-}"
print_list "PASS - launch-owned MainActivity harness with SeedBeforeLaunchRule" "${COMPLIANT[@]:-}"
print_list "KNOWN - manual old harness baseline" "${MANUAL_KNOWN[@]:-}"
print_list "KNOWN - launch-owned but missing shared SeedBeforeLaunchRule baseline" "${MISSING_SHARED_SEED_KNOWN[@]:-}"
print_list "KNOWN - unwired androidTest E2e/Docker baseline" "${UNWIRED_ANDROID_E2E_DOCKER_KNOWN[@]:-}"
print_list "KNOWN - unwired androidTest class baseline (#2188)" "${UNWIRED_ANDROID_TEST_KNOWN[@]:-}"
print_list "JUSTIFIED - local JOURNEY_HARNESS_JUSTIFIED exemption" "${MANUAL_JUSTIFIED[@]:-}" "${MISSING_SHARED_SEED_JUSTIFIED[@]:-}"
print_list "JUSTIFIED - local ANDROIDTEST_GATE_JUSTIFIED exemption" "${UNWIRED_ANDROID_TEST_JUSTIFIED[@]:-}"
print_list "IGNORED - listed proof class does not launch MainActivity" "${NOT_MAINACTIVITY_LAUNCHERS[@]:-}"
print_list "STALE BASELINE - class no longer matches its baseline entry" "${STALE_BASELINE[@]:-}"
print_list "STALE BASELINE - unwired androidTest E2e/Docker class is now wired" "${STALE_UNWIRED_ANDROID_E2E_DOCKER_BASELINE[@]:-}"
print_list "STALE BASELINE - unwired androidTest class is now wired or deleted" "${STALE_UNWIRED_ANDROID_TEST_BASELINE[@]:-}"
print_list "PARSER FAIL - proof allowlist parser" "${PARSER_FAILURE[@]:-}"
print_list "PARSER FAIL - androidTest unwired baseline" "${ANDROIDTEST_BASELINE_PARSE_FAILURE[@]:-}"
print_list "MISSING FILE - listed proof class has no source file" "${MISSING_FILES[@]:-}"
print_list "MISSING REQUIRED - #848 per-push androidTest class not wired" "${MISSING_REQUIRED_PER_PUSH[@]:-}"
print_list "MISSING REQUIRED SELECTOR - exact per-push androidTest method not wired" "${MISSING_REQUIRED_PER_PUSH_SELECTOR[@]:-}"
print_list "INVALID REQUIRED SELECTOR - exact method source is not a JUnit test" "${INVALID_REQUIRED_PER_PUSH_SELECTOR_SOURCE[@]:-}"
print_list "INVALID REQUIRED SELECTOR - exact method retains a CI self-skip" "${REQUIRED_PER_PUSH_SELECTOR_CI_SELF_SKIP[@]:-}"
print_list "NEW FAIL - manual ActivityScenario/createEmptyComposeRule harness" "${MANUAL_NEW[@]:-}"
print_list "NEW FAIL - createAndroidComposeRule without SeedBeforeLaunchRule" "${MISSING_SHARED_SEED_NEW[@]:-}"
print_list "NEW FAIL - androidTest E2e/Docker class not wired into ci-journey-suite.sh" "${UNWIRED_ANDROID_E2E_DOCKER_NEW[@]:-}"
print_list "NEW FAIL - androidTest class not wired into ci-journey-suite.sh" "${UNWIRED_ANDROID_TEST_NEW[@]:-}"
print_list "FIXTURE ROUTING FAIL - #1733 nightly selection" "${NIGHTLY_FIXTURE_ROUTING_FAILURE[@]:-}"

hard_fail=()
for item in \
  "${PARSER_FAILURE[@]:-}" \
  "${MISSING_FILES[@]:-}" \
  "${MISSING_REQUIRED_PER_PUSH[@]:-}" \
  "${MISSING_REQUIRED_PER_PUSH_SELECTOR[@]:-}" \
  "${INVALID_REQUIRED_PER_PUSH_SELECTOR_SOURCE[@]:-}" \
  "${REQUIRED_PER_PUSH_SELECTOR_CI_SELF_SKIP[@]:-}" \
  "${MANUAL_NEW[@]:-}" \
  "${MISSING_SHARED_SEED_NEW[@]:-}" \
  "${UNWIRED_ANDROID_E2E_DOCKER_NEW[@]:-}" \
  "${UNWIRED_ANDROID_TEST_NEW[@]:-}" \
  "${ANDROIDTEST_BASELINE_PARSE_FAILURE[@]:-}" \
  "${NIGHTLY_FIXTURE_ROUTING_FAILURE[@]:-}" \
  "${STALE_BASELINE[@]:-}" \
  "${STALE_UNWIRED_ANDROID_E2E_DOCKER_BASELINE[@]:-}" \
  "${STALE_UNWIRED_ANDROID_TEST_BASELINE[@]:-}"; do
  [[ -n "$item" ]] && hard_fail+=("$item")
done

if [[ "$REPORT_MODE" -eq 1 ]]; then
  echo
  echo "Report mode (--report): findings printed; guard does not fail."
  exit 0
fi

if [[ "${#hard_fail[@]}" -gt 0 ]]; then
  echo
  echo "::error title=CI journey harness guard (#848/#788/#743/#2188)::A required #848 androidTest is missing from the per-push journey allowlist, a new androidTest class is unwired, a listed com.pocketshell.app.proof journey that launches MainActivity is not using createAndroidComposeRule<MainActivity>() plus SeedBeforeLaunchRule, the allowlist parser failed, or a known-baseline entry is stale. Wire the test into scripts/ci-journey-suite.sh, add it to scripts/androidtest-unwired-baseline.txt (or KNOWN_UNWIRED_ANDROID_E2E_DOCKER_CLASSES for E2e/Docker) with a reason, migrate to the launch-owned harness, remove stale baselines, or add a local // JOURNEY_HARNESS_JUSTIFIED: / // ANDROIDTEST_GATE_JUSTIFIED: comment."
  echo
  echo "FAIL: ${#hard_fail[@]} CI journey harness issue(s)."
  exit 1
fi

echo
echo "PASS: no new unbaselined CI journey harness issues."
exit 0
