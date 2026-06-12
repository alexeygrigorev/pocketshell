#!/usr/bin/env bash
#
# check-design-tokens.sh — design-token drift guardrail (issue #461, slice 2 / G7).
#
# Flags NEW off-ladder UI literals in app/src/main so the design-system token
# migration (Slice D) doesn't lose ground while it's in flight. It does NOT try
# to fix the existing backlog — that's the multi-PR screen sweep. Instead it
# pins a per-file BASELINE of the current offenders and fails only when a file
# gains new ones (or a brand-new file ships with any).
#
# What counts as an offender (in app/src/main/**.kt only):
#   - RoundedCornerShape(<N>.dp) where <N> is NOT an on-ladder radius
#     (8 / 14 / 20 / 28 — the PocketShellShapes rungs). Use PocketShellShapes.*
#     instead of a freehand radius.
#   - fontSize = <N>.sp where <N> is NOT an on-ladder size
#     (11 / 13 / 14 / 16 / 20 — the PocketShell type rungs). Use a
#     MaterialTheme.typography.* slot or a PocketShellType.* style instead.
#
# Genuine sub-ladder component geometry (e.g. a 6dp progress track, an icon
# glyph size) is real and not always "wrong" — that's exactly why this is a
# baseline guardrail and not a hard ban. The baseline file records the accepted
# count per file; lowering it (by migrating a screen) is encouraged and the
# script tells you to re-baseline when a count drops.
#
# Usage:
#   scripts/check-design-tokens.sh            # check against the committed baseline
#   scripts/check-design-tokens.sh --update   # rewrite the baseline to current counts
#
# Exit codes:
#   0  no NEW drift (counts <= baseline)            [also: --update succeeded]
#   1  NEW drift found (a file exceeds its baseline, or a new file has offenders)
#
# Cheap: pure grep over app/src/main, runs in well under a second. Intended for
# the reviewer to run from the worktree and (optionally) a future fast CI step;
# it deliberately does NOT add a slow Gradle/emulator job.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

SCAN_DIR="app/src/main"
BASELINE_FILE="scripts/design-token-baseline.txt"

# On-ladder allow-lists (kept in sync with shared/ui-kit theme tokens).
#   radii  -> PocketShellShapes: extraSmall/small 8, medium 14, large 20, extraLarge 28
#   sizes  -> headlineSmall 20, titleMedium 16, bodyMedium 14, bodyDense/bodyMono 13,
#             labelSmall/labelMono 11
RADIUS_ALLOWED='RoundedCornerShape\((8|14|20|28)\.dp\)'
FONTSIZE_ALLOWED='fontSize = (11|13|14|16|20)\.sp'

# Emit "<count> <file>" for every file under SCAN_DIR that has at least one
# off-ladder offender. Counts both offender families together.
current_counts() {
  # Per-file offender count = (radius offenders) + (fontSize offenders).
  # We list matching files+lines, strip the matched text, then tally per file.
  {
    grep -rnoE 'RoundedCornerShape\([0-9]+\.dp\)' "$SCAN_DIR" --include=*.kt 2>/dev/null \
      | grep -vE "$RADIUS_ALLOWED" || true
    grep -rnoE 'fontSize = [0-9]+\.sp' "$SCAN_DIR" --include=*.kt 2>/dev/null \
      | grep -vE "$FONTSIZE_ALLOWED" || true
  } \
    | cut -d: -f1 \
    | sort \
    | uniq -c \
    | awk '{ printf "%s %s\n", $2, $1 }' \
    | sort
}

if [[ "${1:-}" == "--update" ]]; then
  {
    echo "# design-token drift baseline (issue #461 / G7)"
    echo "# format: <file> <accepted-offender-count>"
    echo "# regenerate with: scripts/check-design-tokens.sh --update"
    echo "# lower numbers are better — re-baseline after migrating a screen."
    current_counts
  } > "$BASELINE_FILE"
  echo "check-design-tokens: baseline rewritten -> $BASELINE_FILE"
  exit 0
fi

if [[ ! -f "$BASELINE_FILE" ]]; then
  echo "check-design-tokens: ERROR no baseline at $BASELINE_FILE" >&2
  echo "  run: scripts/check-design-tokens.sh --update" >&2
  exit 1
fi

# Load baseline counts into an assoc array.
declare -A baseline
while read -r file count; do
  [[ -z "$file" || "$file" == \#* ]] && continue
  baseline["$file"]="$count"
done < "$BASELINE_FILE"

regressions=0
improvements=0

while read -r file count; do
  [[ -z "$file" ]] && continue
  base="${baseline[$file]:-0}"
  if (( count > base )); then
    echo "DRIFT  $file: $count off-ladder literals (baseline $base, +$((count - base)) new)"
    regressions=$((regressions + 1))
  elif (( count < base )); then
    echo "better $file: $count (baseline $base) — migrate the rest, then --update"
    improvements=$((improvements + 1))
  fi
done < <(current_counts)

# Files whose offenders dropped to zero won't appear in current_counts; detect
# those as improvements too so the reviewer knows the baseline can shrink.
for file in "${!baseline[@]}"; do
  if ! grep -q "^$file " <(current_counts); then
    echo "better $file: 0 (baseline ${baseline[$file]}) — fully migrated, then --update"
    improvements=$((improvements + 1))
  fi
done

echo
if (( regressions > 0 )); then
  echo "check-design-tokens: FAIL — $regressions file(s) gained NEW off-ladder literals."
  echo "  Use PocketShellShapes.* for radii and a type-ladder style for fontSize,"
  echo "  or, for genuine sub-ladder component geometry, name a private *Radius/*Size"
  echo "  constant with a cite to docs/design-system.md and re-baseline (--update)."
  exit 1
fi

if (( improvements > 0 )); then
  echo "check-design-tokens: OK — no new drift. $improvements file(s) improved;"
  echo "  run 'scripts/check-design-tokens.sh --update' to lock in the lower baseline."
else
  echo "check-design-tokens: OK — no new drift."
fi
exit 0
