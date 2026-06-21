#!/usr/bin/env bash
#
# check-component-drift.sh — raw-component drift guardrail (issue #865).
#
# Sibling of scripts/check-design-tokens.sh (which guards radius/fontSize
# literals). This one guards the design-consistency component migration from
# the #756 audit: the shared ui-kit now owns the canonical dialog/spinner/
# text-button look (ConfirmDialog, FormDialog, LoadingIndicator,
# PocketShellButton). Once a screen is migrated off the raw Material widget,
# nothing should silently re-grow the backlog by reaching for the raw widget
# again. This guard PINS a per-file baseline of the raw call-sites that exist
# on the current clean tree and FAILS when a file gains a NEW one (or a
# brand-new file ships with any).
#
# What counts as a raw call-site (in app/src/main + shared/ui-kit/src/main,
# *.kt only): a call to one of these Material widgets, i.e. the widget name
# immediately followed by `(`:
#   - AlertDialog(               -> use ConfirmDialog / FormDialog (ui-kit)
#   - CircularProgressIndicator( -> use LoadingIndicator.Spinner (ui-kit)
#   - TextButton(                -> use PocketShellButton.Text (ui-kit)
#
# Import lines are excluded (an `import androidx...AlertDialog` is not a use).
# The shared ui-kit wrapper components (ConfirmDialog.kt, FormDialog.kt,
# LoadingIndicator.kt, PocketShellButton.kt, HostCard.kt) are the canonical
# implementations — they are SUPPOSED to call the raw widget exactly once, so
# their call-sites live in the baseline like any other accepted site. Lowering
# a count (by migrating a screen) is encouraged and the script tells you to
# re-baseline when a count drops.
#
# Usage:
#   scripts/check-component-drift.sh            # check against the committed baseline
#   scripts/check-component-drift.sh --update   # rewrite the baseline to current counts
#
# Exit codes:
#   0  no NEW drift (counts <= baseline)            [also: --update succeeded]
#   1  NEW drift found (a file exceeds its baseline, or a new file has raw uses)
#
# Cheap: pure grep over two source roots, runs in well under a second. Wired
# into the Unit job of .github/workflows/tests.yml (a fast static grep — it does
# NOT need the emulator job).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

SCAN_DIRS=(app/src/main shared/ui-kit/src/main)
BASELINE_FILE="scripts/component-drift-baseline.txt"

# The raw Material call-sites we guard. `\b<name>[[:space:]]*\(` matches the
# widget name followed by an open paren (allowing a space), which is the call
# form; an `import ...AlertDialog` line has no `(` and is skipped anyway, but we
# also drop import lines explicitly for safety.
RAW_CALL='\b(AlertDialog|CircularProgressIndicator|TextButton)[[:space:]]*\('

# Emit "<file> <count>" for every file under SCAN_DIRS that has at least one
# raw call-site, sorted by file.
current_counts() {
  grep -rnoE "$RAW_CALL" "${SCAN_DIRS[@]}" --include=*.kt 2>/dev/null \
    | grep -vE ':[0-9]+:[[:space:]]*import ' \
    | cut -d: -f1 \
    | sort \
    | uniq -c \
    | awk '{ printf "%s %s\n", $2, $1 }' \
    | sort
}

if [[ "${1:-}" == "--update" ]]; then
  {
    echo "# raw-component drift baseline (issue #865)"
    echo "# format: <file> <accepted-raw-call-site-count>"
    echo "# guards: AlertDialog( / CircularProgressIndicator( / TextButton("
    echo "# use instead: ConfirmDialog|FormDialog / LoadingIndicator.Spinner /"
    echo "#   PocketShellButton.Text (shared ui-kit). The ui-kit wrapper files"
    echo "#   themselves legitimately call the raw widget once — they are listed"
    echo "#   here like any other accepted site."
    echo "# regenerate with: scripts/check-component-drift.sh --update"
    echo "# lower numbers are better — re-baseline after migrating a screen."
    current_counts
  } > "$BASELINE_FILE"
  echo "check-component-drift: baseline rewritten -> $BASELINE_FILE"
  exit 0
fi

if [[ ! -f "$BASELINE_FILE" ]]; then
  echo "check-component-drift: ERROR no baseline at $BASELINE_FILE" >&2
  echo "  run: scripts/check-component-drift.sh --update" >&2
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
    echo "DRIFT  $file: $count raw component call-sites (baseline $base, +$((count - base)) new)"
    regressions=$((regressions + 1))
  elif (( count < base )); then
    echo "better $file: $count (baseline $base) — migrate the rest, then --update"
    improvements=$((improvements + 1))
  fi
done < <(current_counts)

# Files whose raw call-sites dropped to zero won't appear in current_counts;
# detect those as improvements too so the baseline can shrink.
for file in "${!baseline[@]}"; do
  if ! grep -q "^$file " <(current_counts); then
    echo "better $file: 0 (baseline ${baseline[$file]}) — fully migrated, then --update"
    improvements=$((improvements + 1))
  fi
done

echo
if (( regressions > 0 )); then
  echo "check-component-drift: FAIL — $regressions file(s) gained NEW raw component call-sites."
  echo "  Use the shared ui-kit components instead of the raw Material widget:"
  echo "    AlertDialog               -> ConfirmDialog / FormDialog"
  echo "    CircularProgressIndicator -> LoadingIndicator.Spinner"
  echo "    TextButton                -> PocketShellButton.Text"
  echo "  If a NEW raw call-site is genuinely unavoidable, add it to the shared"
  echo "  ui-kit and re-baseline (--update); do NOT scatter a raw widget into a"
  echo "  screen."
  exit 1
fi

if (( improvements > 0 )); then
  echo "check-component-drift: OK — no new drift. $improvements file(s) improved;"
  echo "  run 'scripts/check-component-drift.sh --update' to lock in the lower baseline."
else
  echo "check-component-drift: OK — no new drift."
fi
exit 0
