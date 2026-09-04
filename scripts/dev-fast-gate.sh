#!/usr/bin/env bash
#
# dev-fast-gate.sh — DEVELOPER-ONLY pre-merge fast path.
#
# Maps the changed paths in this branch (vs the origin/main merge base) to a
# MINIMAL set of emulator validation stages and runs only those, by calling the
# EXISTING building blocks directly:
#   - scripts/pre-release-confidence-gate.sh
#   - scripts/capture-walkthrough-screenshots.sh   (the app2 journey visual pass)
#
# Issue #2481 shrank this list. It used to also call
# `scripts/phone-walkthrough.sh <scenarios>` (terminal-lab, tmux-existing-session,
# visual-audit, setup-detection[:<profile>]); that script was deleted with the
# `app` module androidTest classes every one of its scenarios drove. The
# confidence gate now runs app2's WHOLE instrumented set itself (issue #2474's
# unfiltered shape), so a "terminal" or "bootstrap" scoped run is just the gate,
# and the visual pass is the one block that adds something the gate does not
# hard-assert (a rendered frame per journey).
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
#   scripts/dev-fast-gate.sh [--dry-run]
#
#   --dry-run         Print the changed-area classification, the selected stage
#                     set, and the exact commands that WOULD run, then exit 0
#                     without touching the emulator/Docker.
#
# `--profile <name>` is GONE with the setup-detection matrix it scoped (issue
# #2481): the 7-profile bootstrap suite drove
# com.pocketshell.app.bootstrap.HostBootstrapScenarioSuiteTest, and the guided
# host-setup sheet it exercised is a cut feature — only the actionable "update
# the host CLI" error survives (docs/rewrite-implementation-plan.md, "Scope
# amendment").

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Safety invariant (enforced by construction, not by a flag): this script never
# runs scripts/release-emulator-validation.sh and never writes under
# build/release-emulator-validation/, so it can never produce a taggable summary.

DRY_RUN=0

usage() {
  sed -n '3,28p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --dry-run)
      DRY_RUN=1
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
#
# ISSUE #2063: the case arms that used to live here are now DATA, in
# scripts/test-areas.txt, read through scripts/lib/test-areas.sh. The same
# manifest drives CI area selection and the coverage guard, so the local fast
# path and CI can no longer disagree about what a path is.
#
# Two things are preserved exactly and one is deliberately tightened:
#   * the ORDER (force-full -> migration -> area allowlist -> force-full
#     default) is unchanged; only the last two steps read the manifest;
#   * every stage decision this script made before is unchanged for every
#     tracked file in the repo, EXCEPT paths that the manifest force-fulls more
#     aggressively than the old arms did (TmuxSessionViewModel.kt, the Docker
#     bootstrap fixtures, shared/test-support). Those move toward MORE
#     validation, never less.
# scripts/dev-fast-gate-parity-selftest.sh proves both statements over every
# tracked file, and pins the tightened set so it cannot grow unnoticed.
# ---------------------------------------------------------------------------
# shellcheck source=lib/test-areas.sh
source "$ROOT_DIR/scripts/lib/test-areas.sh"

if ! pocketshell_test_areas_load "${POCKETSHELL_TEST_AREAS_MANIFEST:-$ROOT_DIR/scripts/test-areas.txt}"; then
  echo "error: scripts/test-areas.txt failed to load:" >&2
  printf '  %s\n' "${POCKETSHELL_TA_LOAD_ERRORS[@]}" >&2
  echo "error: refusing to guess — run the FULL gate (scripts/release-emulator-validation.sh)" >&2
  exit 2
fi

classify_path() {
  local p="$1"

  pocketshell_test_area_classify "$p"

  # ---- FORCE FULL (fail-safe). Evaluated first; any match => full gate. ----
  # The manifest's `full` rows are a strict superset of the old inline arms
  # (build files, gradle, scripts/, .github/) and add the blast-radius escapes
  # the old arms reached only via the unmatched default.
  if [[ "$POCKETSHELL_TEST_AREA_KIND" == "full" ]]; then
    echo "force-full"; return
  fi

  # Room schema / migrations / install-update path -> needs pre-release gate.
  # Stays inline: `migration` is a release/DB concern that cuts across every
  # test area, so it is not expressible as an area and must keep its original
  # position — after force-full, before the allowlist.
  case "$p" in
    *[Mm]igration*|*/schemas/*|*schemas/*.json)
      echo "migration"; return ;;
  esac

  # ---- the allowlist, now read from the manifest's `devgate` column ----
  # `full` here is the old "not on the conservative allowlist" default.
  case "$POCKETSHELL_TEST_AREA_DEVGATE" in
    ui|bootstrap|terminal) echo "$POCKETSHELL_TEST_AREA_DEVGATE"; return ;;
    *) echo "force-full"; return ;;
  esac
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
    commands+=("scripts/capture-walkthrough-screenshots.sh")
  else
    # Exactly one area.
    local only="${real_areas[0]}"
    echo "== decision: SCOPED ($only) =="
    echo
    case "$only" in
      ui)
        # The one area where the visual pass adds a distinct assertion.
        commands+=("scripts/capture-walkthrough-screenshots.sh")
        ;;
      bootstrap|terminal|migration)
        # All three are covered by the gate now: it runs app2's whole
        # instrumented set (terminal + reconnect + tree journeys), the legacy-v1
        # Room migration proof, and the old-CLI mismatch fixture.
        commands+=("scripts/pre-release-confidence-gate.sh")
        ;;
      *)
        echo "  internal error: unexpected area '$only' — running full set" >&2
        commands=("scripts/pre-release-confidence-gate.sh" \
                  "scripts/capture-walkthrough-screenshots.sh")
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

main "$@"
