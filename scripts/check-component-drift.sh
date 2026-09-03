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
#   scripts/check-component-drift.sh --self-test # prove raw-call mutations go red
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

# Mutation-sensitive proof for the guard itself. The production FileViewer
# source is copied into a temporary miniature tree, then each migrated form
# dialog is changed back to a raw AlertDialog in isolation. The real guard must
# reject both mutations; otherwise a green count check could be disconnected
# from the source boundary it is meant to protect.
self_test() (
  set -euo pipefail

  local sandbox clean_source scan_source mutant_file output
  sandbox="$(mktemp -d "${TMPDIR:-/tmp}/component-drift-selftest.XXXXXX")"
  trap 'rm -rf -- "$sandbox"' EXIT

  # The fixture is a REAL baselined file, so the self-test cannot drift away
  # from the thing it validates. It used to be the old app module's
  # FileViewerScreen.kt (mutating its two FormDialog call-sites); the rewrite
  # deleted that file and every other `app/` entry in the baseline, so the
  # fixture moved to a surviving ui-kit wrapper. ConfirmDialog.kt is baselined at
  # exactly 1 accepted raw AlertDialog( call — the wrapper's own — which is the
  # shape the assertion below needs.
  local fixture_rel="shared/ui-kit/src/main/java/com/pocketshell/uikit/components/ConfirmDialog.kt"
  scan_source="$sandbox/$fixture_rel"
  clean_source="$sandbox/clean/ConfirmDialog.kt"
  mkdir -p "$(dirname "$scan_source")" "$(dirname "$clean_source")" "$sandbox/scripts"
  cp "$REPO_ROOT/$fixture_rel" "$clean_source"
  cp "$clean_source" "$scan_source"
  cp "$BASELINE_FILE" "$sandbox/scripts/component-drift-baseline.txt"
  cp "$SCRIPT_DIR/check-component-drift.sh" "$sandbox/scripts/check-component-drift.sh"
  chmod +x "$sandbox/scripts/check-component-drift.sh"

  # GREEN CONTROL first: without it, every red below could be a red the fixture
  # already had.
  if ! output="$(cd "$sandbox" && scripts/check-component-drift.sh 2>&1)"; then
    printf '%s\n' "$output" >&2
    echo "FAIL: the clean baselined fixture is not accepted by the component-drift guard" >&2
    exit 1
  fi
  echo "PASS: the clean baselined fixture is accepted"

  # Each mutation adds ONE new raw call-site of a different guarded widget, so a
  # guard that only greps for AlertDialog cannot pass all three.
  mutate_and_require_red() {
    local label="$1" added_call="$2"
    mutant_file="$sandbox/$label/ConfirmDialog.kt"
    mkdir -p "$(dirname "$mutant_file")"
    cp "$clean_source" "$mutant_file"
    printf '\n@Composable\nprivate fun SelfTestDrift%s() {\n    %s\n    )\n}\n' \
      "$label" "$added_call" >> "$mutant_file"
    grep -Fq "$added_call" "$mutant_file" \
      || { echo "FAIL: $label mutation did not apply" >&2; exit 1; }

    cp "$mutant_file" "$scan_source"
    if output="$(cd "$sandbox" && scripts/check-component-drift.sh 2>&1)"; then
      printf '%s\n' "$output" >&2
      echo "FAIL: $label raw-component mutation was accepted" >&2
      exit 1
    fi
    grep -Fq \
      "DRIFT  $fixture_rel: 2 raw component call-sites (baseline 1, +1 new)" \
      <<<"$output" \
      || { printf '%s\n' "$output" >&2; echo "FAIL: $label mutation failed for an unexpected reason" >&2; exit 1; }
    echo "PASS: $label raw-component mutation is rejected"
    cp "$clean_source" "$scan_source"
  }

  mutate_and_require_red AlertDialog 'AlertDialog('
  mutate_and_require_red Spinner 'CircularProgressIndicator('
  mutate_and_require_red TextButton 'TextButton('

  # And the other direction: a NEW file with a raw call is drift even though it
  # has no baseline row at all (this is how a freshly-added screen is caught).
  local newfile="$sandbox/shared/ui-kit/src/main/java/com/pocketshell/uikit/components/SelfTestBrandNew.kt"
  printf 'package com.pocketshell.uikit.components\n\nfun x() {\n    TextButton(\n    )\n}\n' > "$newfile"
  if output="$(cd "$sandbox" && scripts/check-component-drift.sh 2>&1)"; then
    printf '%s\n' "$output" >&2
    echo "FAIL: a brand-new unbaselined file with a raw call was accepted" >&2
    exit 1
  fi
  grep -Fq "SelfTestBrandNew.kt" <<<"$output" \
    || { printf '%s\n' "$output" >&2; echo "FAIL: the new-file case reddened for another reason" >&2; exit 1; }
  rm -f "$newfile"
  echo "PASS: a new file carrying a raw component call is rejected"

  echo "PASS: component-drift guard accepts its baseline and rejects every guarded widget"
)

if [[ "${1:-}" == "--self-test" ]]; then
  self_test
  exit 0
fi

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
