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

# Current-main androidTest E2e/Docker backlog that is intentionally not in the
# per-push journey allowlist. New *E2eTest/*DockerTest classes must either be
# wired into scripts/ci-journey-suite.sh or added here with an intentional
# follow-up. Keep full FQCNs so moves are visible.
KNOWN_UNWIRED_ANDROID_E2E_DOCKER_CLASSES=(
  "com.pocketshell.app.composer.AttachmentStagerRealUploadDockerTest"
  "com.pocketshell.app.composer.ComposerPartialExpandE2eTest"
  "com.pocketshell.app.costs.CostsScreenE2eTest"
  "com.pocketshell.app.crash.ShareAllReportsDockerTest"
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
  "com.pocketshell.app.proof.TmuxKeyBarCtrlComboE2eTest"
  "com.pocketshell.app.proof.TmuxOrphanClientCleanupE2eTest"
  "com.pocketshell.app.proof.TmuxSessionSwitchE2eTest"
  "com.pocketshell.app.proof.TmuxSessionSwitchSameHostReusesSshE2eTest"
  "com.pocketshell.app.proof.TmuxTerminalSurfaceFailureE2eTest"
  "com.pocketshell.app.proof.WarmLeaseReuseBatchCDockerTest"
  "com.pocketshell.app.proof.WarmLeaseReuseDockerTest"
  "com.pocketshell.app.proof.WithinGraceResumeRideThroughE2eTest"
  "com.pocketshell.app.release.UpdateCheckSchedulerE2eTest"
  "com.pocketshell.app.sessions.service.SessionConnectionServiceE2eTest"
  "com.pocketshell.app.session.ConversationToolResultPairingE2eTest"
  "com.pocketshell.app.session.ShowKeyboardChipE2eTest"
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
    -e 's/.*"\$FQCN_PREFIX\.([A-Za-z0-9_]+)(#[^"]*)?".*/\1/p' \
    -e 's/.*"com\.pocketshell\.app\.proof\.([A-Za-z0-9_]+)(#[^"]*)?".*/\1/p' \
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
    -e 's/.*"\$FQCN_PREFIX\.([A-Za-z0-9_]+)(#[^"]*)?".*/com.pocketshell.app.proof.\1/p' \
    -e 's/.*"(com\.pocketshell\.app\.[A-Za-z0-9_.]+)(#[^"]*)?".*/\1/p' \
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
declare -a UNWIRED_ANDROID_E2E_DOCKER_NEW=()
declare -a UNWIRED_ANDROID_E2E_DOCKER_KNOWN=()
declare -a STALE_UNWIRED_ANDROID_E2E_DOCKER_BASELINE=()

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
print_list "PASS - launch-owned MainActivity harness with SeedBeforeLaunchRule" "${COMPLIANT[@]:-}"
print_list "KNOWN - manual old harness baseline" "${MANUAL_KNOWN[@]:-}"
print_list "KNOWN - launch-owned but missing shared SeedBeforeLaunchRule baseline" "${MISSING_SHARED_SEED_KNOWN[@]:-}"
print_list "KNOWN - unwired androidTest E2e/Docker baseline" "${UNWIRED_ANDROID_E2E_DOCKER_KNOWN[@]:-}"
print_list "JUSTIFIED - local JOURNEY_HARNESS_JUSTIFIED exemption" "${MANUAL_JUSTIFIED[@]:-}" "${MISSING_SHARED_SEED_JUSTIFIED[@]:-}"
print_list "IGNORED - listed proof class does not launch MainActivity" "${NOT_MAINACTIVITY_LAUNCHERS[@]:-}"
print_list "STALE BASELINE - class no longer matches its baseline entry" "${STALE_BASELINE[@]:-}"
print_list "STALE BASELINE - unwired androidTest E2e/Docker class is now wired" "${STALE_UNWIRED_ANDROID_E2E_DOCKER_BASELINE[@]:-}"
print_list "PARSER FAIL - proof allowlist parser" "${PARSER_FAILURE[@]:-}"
print_list "MISSING FILE - listed proof class has no source file" "${MISSING_FILES[@]:-}"
print_list "MISSING REQUIRED - #848 per-push androidTest class not wired" "${MISSING_REQUIRED_PER_PUSH[@]:-}"
print_list "NEW FAIL - manual ActivityScenario/createEmptyComposeRule harness" "${MANUAL_NEW[@]:-}"
print_list "NEW FAIL - createAndroidComposeRule without SeedBeforeLaunchRule" "${MISSING_SHARED_SEED_NEW[@]:-}"
print_list "NEW FAIL - androidTest E2e/Docker class not wired into ci-journey-suite.sh" "${UNWIRED_ANDROID_E2E_DOCKER_NEW[@]:-}"

hard_fail=()
for item in \
  "${PARSER_FAILURE[@]:-}" \
  "${MISSING_FILES[@]:-}" \
  "${MISSING_REQUIRED_PER_PUSH[@]:-}" \
  "${MANUAL_NEW[@]:-}" \
  "${MISSING_SHARED_SEED_NEW[@]:-}" \
  "${UNWIRED_ANDROID_E2E_DOCKER_NEW[@]:-}" \
  "${STALE_BASELINE[@]:-}" \
  "${STALE_UNWIRED_ANDROID_E2E_DOCKER_BASELINE[@]:-}"; do
  [[ -n "$item" ]] && hard_fail+=("$item")
done

if [[ "$REPORT_MODE" -eq 1 ]]; then
  echo
  echo "Report mode (--report): findings printed; guard does not fail."
  exit 0
fi

if [[ "${#hard_fail[@]}" -gt 0 ]]; then
  echo
  echo "::error title=CI journey harness guard (#848/#788/#743)::A required #848 androidTest is missing from the per-push journey allowlist, a new androidTest E2e/Docker class is unwired, a listed com.pocketshell.app.proof journey that launches MainActivity is not using createAndroidComposeRule<MainActivity>() plus SeedBeforeLaunchRule, the allowlist parser failed, or a known-baseline entry is stale. Wire the test into scripts/ci-journey-suite.sh, add an intentional unwired baseline for backlog-only E2e/Docker classes, migrate to the launch-owned harness, remove stale baselines, or add a local // JOURNEY_HARNESS_JUSTIFIED: comment naming why the manual/old pattern is required."
  echo
  echo "FAIL: ${#hard_fail[@]} CI journey harness issue(s)."
  exit 1
fi

echo
echo "PASS: no new unbaselined CI journey harness issues."
exit 0
