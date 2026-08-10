#!/usr/bin/env bash
# Parity self-test for the scripts/dev-fast-gate.sh classifier refactor (#2063).
#
# WHY THIS EXISTS
#
# #2063 replaced dev-fast-gate's inline `classify_path` case arms with the
# shared, manifest-driven engine in scripts/lib/test-areas.sh. That refactor is
# only safe if it cannot have QUIETLY LOOSENED the local gate: a path that used
# to demand the full emulator set and now selects one cheap stage is a hole, and
# it would be invisible — the gate would still pass, faster.
#
# So this is not a spot check. It re-implements the ORIGINAL case arms verbatim
# below (`legacy_classify_path`, copied from the pre-#2063 file), runs BOTH
# classifiers over EVERY tracked file in the repository, and asserts:
#
#   P1  no path became LESS conservative
#       (old != force-full  =>  new == old)
#   P2  the set of paths that became MORE conservative is exactly the pinned
#       expectation below — so the refactor's cost is enumerated, reviewable,
#       and cannot grow without this test going red
#   P3  the legacy classifier is actually live: mutating the manifest must
#       change the new classifier's answer. A parity test whose "new" side is
#       accidentally the old side would pass over any refactor at all.
#
# P3 is the mutation check process.md asks for: name the mutation that must
# redden this assertion, then apply it. Here it is applied in-process.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# shellcheck source=lib/test-areas.sh
source "$SCRIPT_DIR/lib/test-areas.sh"

# ---------------------------------------------------------------------------
# The ORIGINAL classifier, verbatim from scripts/dev-fast-gate.sh before #2063
# (commit 0248d0ee, lines 108-158). Do not "improve" it — its only job is to be
# the historical oracle.
# ---------------------------------------------------------------------------
legacy_classify_path() {
  local p="$1"

  case "$p" in
    *build.gradle.kts|*.gradle|*.gradle.kts|gradle.properties|settings.gradle|settings.gradle.kts)
      echo "force-full"; return ;;
    gradle/*|*/gradle/*)
      echo "force-full"; return ;;
    scripts/*)
      echo "force-full"; return ;;
    .github/*)
      echo "force-full"; return ;;
  esac

  case "$p" in
    *[Mm]igration*|*/schemas/*|*schemas/*.json)
      echo "migration"; return ;;
  esac

  case "$p" in
    app/src/main/*/bootstrap/*|*/bootstrap/*)
      echo "bootstrap"; return ;;
    tests/docker/*bootstrap*|tests/docker/*[Bb]ootstrap*)
      echo "bootstrap"; return ;;
  esac

  case "$p" in
    shared/core-terminal/*|shared/core-ssh/*|shared/core-tmux/*)
      echo "terminal"; return ;;
    app/src/main/*/terminal/*|app/src/main/*/ssh/*|app/src/main/*/tmux/*)
      echo "terminal"; return ;;
  esac

  case "$p" in
    shared/ui-kit/*)
      echo "ui"; return ;;
    app/src/main/*/projects/*)
      echo "ui"; return ;;
  esac

  echo "force-full"
}

# The NEW classifier, in the same shape dev-fast-gate.sh now uses.
new_classify_path() {
  local p="$1"
  pocketshell_test_area_classify "$p"
  if [[ "$POCKETSHELL_TEST_AREA_KIND" == "full" ]]; then
    echo "force-full"; return
  fi
  case "$p" in
    *[Mm]igration*|*/schemas/*|*schemas/*.json)
      echo "migration"; return ;;
  esac
  case "$POCKETSHELL_TEST_AREA_DEVGATE" in
    ui|bootstrap|terminal) echo "$POCKETSHELL_TEST_AREA_DEVGATE"; return ;;
    *) echo "force-full"; return ;;
  esac
}

# ---------------------------------------------------------------------------
# P2's pinned expectation: the paths where the manifest is deliberately MORE
# conservative than the old arms. Three groups, each with a stated reason.
# Anything outside them fails P2 — so the cost of the refactor stays bounded
# and reviewable instead of creeping.
# ---------------------------------------------------------------------------
declare -a TIGHTENED_GLOBS=(
  # The D28 god-object seam (#766 residue): old arms called it `terminal`
  # because it lives under app/.../tmux/. Cross-area wedge bugs hide here, so
  # the manifest force-fulls it by name.
  "app/src/main/java/com/pocketshell/app/tmux/TmuxSessionViewModel.kt"
  # Docker fixtures and shell harnesses every journey depends on. The old arms
  # sent tests/docker/*bootstrap* to the bootstrap stage alone.
  "tests/*"
  # The ONE audited settle pump; every runTest consumer depends on it.
  "shared/test-support/*"
  # Shared layout scaffolding used by every screen.
  "app/src/main/java/com/pocketshell/app/layout/*"
)

is_pinned_tightening() {
  local p="$1" g
  for g in "${TIGHTENED_GLOBS[@]}"; do
    # shellcheck disable=SC2053
    [[ "$p" == $g ]] && return 0
  done
  # GROUP 2 — test infrastructure. A fixture / helper / base class / resource
  # inside a test source set has an unknown blast radius (the whole point of
  # the manifest's rule 2). The old arms happened to classify the ones under
  # shared/core-terminal and shared/ui-kit by their MODULE prefix, which is
  # exactly the too-narrow behaviour this refactor removes.
  if pocketshell_test_areas_is_test_path "$p" &&
     ! pocketshell_test_areas_is_test_class_file "$p"; then
    return 0
  fi
  # GROUP 3 — prose. The manifest makes docs `noop`, and dev-fast-gate maps
  # noop to the full set (matching the old repo-root default). Only the two
  # markdown files that happened to sit under shared/core-terminal and
  # shared/ui-kit differ from the old behaviour.
  case "$p" in *.md) return 0 ;; esac
  return 1
}

fail_count=0
note() { printf '%s\n' "$*"; }

if ! pocketshell_test_areas_load "$ROOT_DIR/scripts/test-areas.txt"; then
  note "FAIL: manifest did not load:"
  printf '  %s\n' "${POCKETSHELL_TA_LOAD_ERRORS[@]}"
  exit 1
fi

declare -a LOOSENED=()
declare -a UNPINNED_TIGHTENING=()
declare -a PINNED_TIGHTENING=()
checked=0

while IFS= read -r path; do
  [[ -z "$path" ]] && continue
  checked=$((checked + 1))
  old="$(legacy_classify_path "$path")"
  new="$(new_classify_path "$path")"
  [[ "$old" == "$new" ]] && continue
  if [[ "$new" == "force-full" ]]; then
    if is_pinned_tightening "$path"; then
      PINNED_TIGHTENING+=("$path ($old -> force-full)")
    else
      UNPINNED_TIGHTENING+=("$path ($old -> force-full)")
    fi
  else
    LOOSENED+=("$path ($old -> $new)")
  fi
done < <(git -C "$ROOT_DIR" ls-files)

note "== dev-fast-gate classifier parity =="
note "checked: $checked tracked paths"
note

# P1 — nothing got looser.
if [[ "${#LOOSENED[@]}" -gt 0 ]]; then
  note "FAIL P1: ${#LOOSENED[@]} path(s) became LESS conservative than before #2063:"
  printf '  %s\n' "${LOOSENED[@]}"
  fail_count=$((fail_count + 1))
else
  note "OK P1: no path became less conservative (old != force-full => new == old)"
fi

# P2 — the tightened set is exactly what is pinned.
if [[ "${#UNPINNED_TIGHTENING[@]}" -gt 0 ]]; then
  note
  note "FAIL P2: ${#UNPINNED_TIGHTENING[@]} path(s) became MORE conservative without being pinned in TIGHTENED_GLOBS:"
  printf '  %s\n' "${UNPINNED_TIGHTENING[@]}"
  note "  (this is safe for correctness but slows the local fast path — pin it with a reason, or fix the manifest)"
  fail_count=$((fail_count + 1))
else
  note "OK P2: ${#PINNED_TIGHTENING[@]} tightened path(s), all pinned in TIGHTENED_GLOBS"
fi

# P3 — the new classifier is genuinely manifest-driven. Mutate the manifest so
#      shared/ui-kit stops being `ui` and assert the new answer MOVES. If it
#      does not, the "new" side is not reading the manifest and P1/P2 are
#      vacuous.
mutant="$(mktemp)"
sed 's|^src    shared/ui-kit/\*               ui-shell             ui|src    shared/ui-kit/*               ui-shell             full|' \
  "$ROOT_DIR/scripts/test-areas.txt" > "$mutant"
probe="shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Theme.kt"
before="$(new_classify_path "$probe")"
if pocketshell_test_areas_load "$mutant"; then
  after="$(new_classify_path "$probe")"
else
  after="LOAD_FAILED"
fi
rm -f "$mutant"
pocketshell_test_areas_load "$ROOT_DIR/scripts/test-areas.txt" >/dev/null
note
if [[ "$before" == "ui" && "$after" == "force-full" ]]; then
  note "OK P3: mutating the manifest moved the classifier ($probe: ui -> force-full), so the parity above is live"
else
  note "FAIL P3: the manifest mutation did not move the classifier (before='$before' after='$after')."
  note "        The 'new' side is not reading scripts/test-areas.txt — P1/P2 prove nothing."
  fail_count=$((fail_count + 1))
fi

note
if [[ "$fail_count" -gt 0 ]]; then
  note "FAIL: $fail_count parity check(s) failed."
  exit 1
fi
note "PASS: dev-fast-gate classifier parity holds."
exit 0
