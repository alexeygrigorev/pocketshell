#!/usr/bin/env bash
#
# dev-fast-gate.sh — DEVELOPER-ONLY pre-merge fast path.
#
# Maps the changed paths in this branch (vs the origin/main merge base) to a
# MINIMAL set of emulator validation stages and runs only those, by calling the
# EXISTING building blocks directly:
#   - scripts/phone-walkthrough.sh <scenarios>   (incl. setup-detection:<profile>)
#   - scripts/pre-release-confidence-gate.sh
#
# It is NOT a release gate. It deliberately:
#   - never invokes scripts/release-emulator-validation.sh
#   - never writes any build/release-emulator-validation/*/summary.md
# so a release tag can never be pushed off a scoped run (push-release-tag.sh
# requires a full-gate summary bound to the tagged origin/main SHA).
#
# When in doubt, it runs the FULL set of building blocks (fail-safe default).
#
# Usage:
#   scripts/dev-fast-gate.sh [--dry-run] [--profile <name>]
#
#   --dry-run         Print the changed-area classification, the selected stage
#                     set, and the exact commands that WOULD run, then exit 0
#                     without touching the emulator/Docker.
#   --profile <name>  Scope the setup-detection matrix to a single profile when
#                     setup-detection is selected (passthrough to
#                     phone-walkthrough.sh's setup-detection:<name>).

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Safety invariant (enforced by construction, not by a flag): this script never
# runs scripts/release-emulator-validation.sh and never writes under
# build/release-emulator-validation/, so it can never produce a taggable summary.

DRY_RUN=0
PROFILE=""

usage() {
  sed -n '3,28p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --profile)
      [[ "$#" -ge 2 ]] || { echo "error: --profile requires a value" >&2; exit 2; }
      PROFILE="$2"
      shift 2
      ;;
    --profile=*)
      PROFILE="${1#--profile=}"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "error: unknown argument '$1'" >&2
      usage >&2
      exit 2
      ;;
  esac
done

banner() {
  cat >&2 <<'EOF'
============================================================================
  dev-fast-gate is NOT a release gate — release tags still require
  scripts/release-emulator-validation.sh
============================================================================
EOF
}

# ---------------------------------------------------------------------------
# 1. Compute changed paths vs the origin/main merge base.
# ---------------------------------------------------------------------------
compute_changed_paths() {
  if [[ -n "${DEV_FAST_GATE_CHANGED_PATHS:-}" ]]; then
    # Test/override hook: newline-separated list of changed paths.
    printf '%s\n' "${DEV_FAST_GATE_CHANGED_PATHS}"
    return 0
  fi
  local base
  if ! base="$(git merge-base origin/main HEAD 2>/dev/null)"; then
    echo "error: cannot compute merge-base with origin/main (run 'git fetch origin main')" >&2
    return 1
  fi
  git diff --name-only "${base}...HEAD"
}

# ---------------------------------------------------------------------------
# 2/3. Classify each changed path into an area, with conservative force-full.
#
# Areas (mutually exclusive per path, force-full wins):
#   force-full   build/scripts/CI/DB/migration/unmatched/anything risky
#   ui           UI-only surfaces (no SSH/tmux/bootstrap)
#   bootstrap    host setup-detection / bootstrap
#   terminal     terminal/SSH/tmux render path
#   migration    Room schema / migrations / install-update path
# ---------------------------------------------------------------------------
classify_path() {
  local p="$1"

  # ---- FORCE FULL (fail-safe). Evaluated first; any match => full gate. ----
  case "$p" in
    # build files
    *build.gradle.kts|*.gradle|*.gradle.kts|gradle.properties|settings.gradle|settings.gradle.kts)
      echo "force-full"; return ;;
    gradle/*|*/gradle/*)
      echo "force-full"; return ;;
    # tooling / CI / scripts
    scripts/*)
      echo "force-full"; return ;;
    .github/*)
      echo "force-full"; return ;;
  esac

  # Room schema / migrations / install-update path -> needs pre-release gate.
  case "$p" in
    *[Mm]igration*|*/schemas/*|*schemas/*.json)
      echo "migration"; return ;;
  esac

  # ---- bootstrap / setup-detection ----
  case "$p" in
    app/src/main/*/bootstrap/*|*/bootstrap/*)
      echo "bootstrap"; return ;;
    tests/docker/*bootstrap*|tests/docker/*[Bb]ootstrap*)
      echo "bootstrap"; return ;;
  esac

  # ---- terminal / SSH / tmux render path ----
  case "$p" in
    shared/core-terminal/*|shared/core-ssh/*|shared/core-tmux/*)
      echo "terminal"; return ;;
    app/src/main/*/terminal/*|app/src/main/*/ssh/*|app/src/main/*/tmux/*)
      echo "terminal"; return ;;
  esac

  # ---- UI-only (no SSH/tmux/bootstrap surface) ----
  case "$p" in
    shared/ui-kit/*)
      echo "ui"; return ;;
    app/src/main/*/projects/*)
      echo "ui"; return ;;
  esac

  # Anything not on the conservative allowlist -> full gate.
  echo "force-full"
}

# ---------------------------------------------------------------------------
# 4. Selection. Build a unique set of areas, then derive the stage plan.
# ---------------------------------------------------------------------------
main() {
  banner

  local changed_paths
  if ! changed_paths="$(compute_changed_paths)"; then
    exit 1
  fi
  # Drop blank lines.
  changed_paths="$(printf '%s\n' "$changed_paths" | sed '/^[[:space:]]*$/d')"

  echo "== dev-fast-gate: changed paths vs origin/main merge-base =="
  if [[ -z "$changed_paths" ]]; then
    echo "  (none — no diff vs merge-base)"
  else
    printf '  %s\n' $changed_paths
  fi
  echo

  # Classify.
  local -A area_seen=()
  local -a classification_lines=()
  local p area
  while IFS= read -r p; do
    [[ -z "$p" ]] && continue
    area="$(classify_path "$p")"
    area_seen["$area"]=1
    classification_lines+=("$(printf '  %-12s %s' "$area" "$p")")
  done <<< "$changed_paths"

  echo "== classification =="
  if [[ "${#classification_lines[@]}" -eq 0 ]]; then
    echo "  (no changed paths)"
  else
    printf '%s\n' "${classification_lines[@]}"
  fi
  echo

  # ---- Decide stages ----
  # force-full short-circuits everything. Also: an empty diff, or a multi-area
  # diff (more than one distinct non-force-full area), forces full.
  local -a real_areas=()
  for area in "${!area_seen[@]}"; do
    real_areas+=("$area")
  done

  local force_full=0
  local reason=""

  if [[ "${area_seen[force-full]:-0}" == "1" ]]; then
    force_full=1
    reason="a force-full path (build/scripts/CI/migration/unmatched) is present"
  elif [[ "${#real_areas[@]}" -eq 0 ]]; then
    force_full=1
    reason="no changed paths (empty diff) — running full set to be safe"
  elif [[ "${#real_areas[@]}" -gt 1 ]]; then
    force_full=1
    reason="multi-area diff (${real_areas[*]}) — running full set to be safe"
  fi

  local -a commands=()

  if [[ "$force_full" == "1" ]]; then
    echo "== decision: FULL =="
    echo "  reason: $reason"
    echo
    # The complete set of release-emulator-validation building blocks, run
    # directly (NOT via release-emulator-validation.sh, so no taggable summary).
    commands+=("scripts/pre-release-confidence-gate.sh")
    commands+=("scripts/phone-walkthrough.sh terminal-lab tmux-existing-session $(setup_detection_arg) visual-audit")
  else
    # Exactly one area.
    local only="${real_areas[0]}"
    echo "== decision: SCOPED ($only) =="
    echo
    case "$only" in
      ui)
        commands+=("scripts/phone-walkthrough.sh visual-audit terminal-lab")
        ;;
      bootstrap)
        commands+=("scripts/phone-walkthrough.sh $(setup_detection_arg)")
        ;;
      terminal)
        commands+=("scripts/phone-walkthrough.sh terminal-lab tmux-existing-session")
        ;;
      migration)
        commands+=("scripts/pre-release-confidence-gate.sh")
        ;;
      *)
        echo "  internal error: unexpected area '$only' — running full set" >&2
        commands=("scripts/pre-release-confidence-gate.sh" \
                  "scripts/phone-walkthrough.sh terminal-lab tmux-existing-session $(setup_detection_arg) visual-audit")
        ;;
    esac
  fi

  echo "== planned commands =="
  printf '  %s\n' "${commands[@]}"
  echo

  if [[ "$DRY_RUN" == "1" ]]; then
    echo "== --dry-run: not executing (emulator untouched) =="
    return 0
  fi

  # ---- Execute. Building blocks each acquire the AVD lock themselves and
  # queue politely; we run them serially in this process. ----
  echo "== executing planned commands =="
  local cmd
  for cmd in "${commands[@]}"; do
    echo ">> $cmd"
    # shellcheck disable=SC2086
    bash $cmd
  done
}

# setup-detection scenario arg, scoped to --profile when provided.
setup_detection_arg() {
  if [[ -n "$PROFILE" ]]; then
    printf 'setup-detection:%s' "$PROFILE"
  else
    printf 'setup-detection'
  fi
}

main "$@"
