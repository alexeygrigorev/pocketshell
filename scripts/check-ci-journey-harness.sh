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
# The current main branch still has known old-harness classes and some
# launch-owned classes with hand-rolled pre-launch seed rules. Those are kept in
# explicit baselines so this small guard does not rewrite or conflict with the
# open #911 migration PR. New listed proof journeys must use the durable shape
# or carry a local justification.

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
  "LongRunningSessionStabilityTest"
  "ComposerAlwaysPresentSwitchJourneyE2eTest"
)

# Known current-main launch-owned classes that pre-seed via a local hand-rolled
# TestRule instead of the shared SeedBeforeLaunchRule. Remove entries when those
# classes adopt SeedBeforeLaunchRule.
KNOWN_LAUNCH_OWNED_WITHOUT_SHARED_SEED=(
  "AttachmentNoReconnectE2eTest"
  "SendNoReconnectE2eTest"
  "StableWifiNoSpuriousReconnectE2eTest"
  "SilentDropSyntheticSeamJourneyE2eTest"
  "CleanOutageReattachResilienceE2eTest"
  "Issue895SwitchWhileBlackBandJourneyE2eTest"
  "AgentSubmitAckJourneyE2eTest"
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

declare -a JOURNEY_CLASSES=()
declare -A SEEN=()
while IFS= read -r line; do
  class_name="$(printf '%s\n' "$line" | sed -nE 's/.*"\$FQCN_PREFIX\.([A-Za-z0-9_]+)(#[^"]*)?".*/\1/p')"
  if [[ -z "${class_name:-}" ]]; then
    class_name="$(printf '%s\n' "$line" | sed -nE 's/.*"com\.pocketshell\.app\.proof\.([A-Za-z0-9_]+)(#[^"]*)?".*/\1/p')"
  fi
  [[ -z "${class_name:-}" ]] && continue
  if [[ -z "${SEEN[$class_name]:-}" ]]; then
    JOURNEY_CLASSES+=("$class_name")
    SEEN[$class_name]=1
  fi
done < "$SUITE"

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

if [[ "${#JOURNEY_CLASSES[@]}" -eq 0 ]]; then
  PARSER_FAILURE+=("NO_PROOF_CLASSES_PARSED")
fi

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
echo "=============================================================="

print_list "PASS - launch-owned MainActivity harness with SeedBeforeLaunchRule" "${COMPLIANT[@]:-}"
print_list "KNOWN - manual old harness baseline" "${MANUAL_KNOWN[@]:-}"
print_list "KNOWN - launch-owned but missing shared SeedBeforeLaunchRule baseline" "${MISSING_SHARED_SEED_KNOWN[@]:-}"
print_list "JUSTIFIED - local JOURNEY_HARNESS_JUSTIFIED exemption" "${MANUAL_JUSTIFIED[@]:-}" "${MISSING_SHARED_SEED_JUSTIFIED[@]:-}"
print_list "IGNORED - listed proof class does not launch MainActivity" "${NOT_MAINACTIVITY_LAUNCHERS[@]:-}"
print_list "STALE BASELINE - class no longer matches its baseline entry" "${STALE_BASELINE[@]:-}"
print_list "PARSER FAIL - proof allowlist parser" "${PARSER_FAILURE[@]:-}"
print_list "MISSING FILE - listed proof class has no source file" "${MISSING_FILES[@]:-}"
print_list "NEW FAIL - manual ActivityScenario/createEmptyComposeRule harness" "${MANUAL_NEW[@]:-}"
print_list "NEW FAIL - createAndroidComposeRule without SeedBeforeLaunchRule" "${MISSING_SHARED_SEED_NEW[@]:-}"

hard_fail=()
for item in "${PARSER_FAILURE[@]:-}" "${MISSING_FILES[@]:-}" "${MANUAL_NEW[@]:-}" "${MISSING_SHARED_SEED_NEW[@]:-}" "${STALE_BASELINE[@]:-}"; do
  [[ -n "$item" ]] && hard_fail+=("$item")
done

if [[ "$REPORT_MODE" -eq 1 ]]; then
  echo
  echo "Report mode (--report): findings printed; guard does not fail."
  exit 0
fi

if [[ "${#hard_fail[@]}" -gt 0 ]]; then
  echo
  echo "::error title=CI journey harness guard (#848/#788/#743)::A listed com.pocketshell.app.proof journey that launches MainActivity is not using createAndroidComposeRule<MainActivity>() plus SeedBeforeLaunchRule, the allowlist parser found no proof classes, or a known-baseline entry is stale. Migrate to the launch-owned harness, remove stale baselines, or add a local // JOURNEY_HARNESS_JUSTIFIED: comment naming why the manual/old pattern is required."
  echo
  echo "FAIL: ${#hard_fail[@]} CI journey harness issue(s)."
  exit 1
fi

echo
echo "PASS: no new unbaselined CI journey harness issues."
exit 0
