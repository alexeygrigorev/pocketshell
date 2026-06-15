#!/usr/bin/env bash
# Test-validity guard (issue #657 / F4).
#
# The maintainer's #1 process complaint is that issues get reviewer-APPROVED
# and closed while the real on-device behaviour is still broken, because the
# test exercises a NARROW PROXY of the bug rather than the user's actual state.
# The #657 audit catalogued the recurring anti-patterns; this grep-guard is the
# cheap, automated backstop for the two highest-signal smells so the rule does
# not rely solely on reviewer memory (F2/F3 are the human-facing rules in
# process.md; this is their machine sibling).
#
# It flags, in app/src/androidTest/**:
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
# A small BASELINE allowlist records the A5 offenders the #657 audit catalogued
# but that are intentionally NOT rewritten in the framework PR (the rewrites are
# per-issue follow-up work). Those are reported as KNOWN-baseline (advisory) so
# this guard does not redden CI for tests it is not this PR's job to fix, while
# any NEW unjustified A5 occurrence hard-fails. Removing a file from the baseline
# (because its test was converted to the synthetic-inset model) is the intended
# direction of travel; if a baselined offender is gone the guard simply prunes
# the stale entry and notes it.
#
# Usage:
#   scripts/check-test-validity.sh            # guard mode (CI): exit 1 on a NEW A5 smell
#   scripts/check-test-validity.sh --report   # report ALL findings incl. baseline; never fails
#
# This is intentionally a grep-guard, not a custom lint rule, for affordability
# (it runs in the cheap Unit job in .github/workflows/tests.yml before the
# Gradle test step, adding < 1 s).

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

ANDROID_TEST_ROOT="app/src/androidTest"

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
  "app/src/androidTest/java/com/pocketshell/app/composer/PromptComposerSlashAutocompleteImeTest.kt"   # issue #767
)

is_baselined() {
  local file="$1"
  local b
  for b in "${A5_BASELINE[@]}"; do
    [[ "$file" == "$b" ]] && return 0
  done
  return 1
}

# A file is in IME/keyboard/geometry territory if its content mentions any of
# these words. The A5 smell only applies to such files: an `assumeTrue` there is
# very likely gating a keyboard-up / geometry assertion on whether the real soft
# IME happened to raise (the environment-dependent skip the audit flags).
ime_geometry_words='IME|imeShown|soft keyboard|keyboard-up|readImeBottomPx|geometry|boundsInRoot'

# An `assumeTrue` is the A5 smell only if it is plausibly an IME-availability
# skip: the assume's own line / message mentions IME or keyboard availability.
# An SDK-version guard like `assumeTrue(Build.VERSION.SDK_INT >= ...)` is NOT
# the A5 smell and is excluded here.
assume_is_ime_skip() {
  local line="$1"
  # Exclude obvious non-IME guards (SDK version, build config, etc.).
  if printf '%s' "$line" | grep -Eq 'SDK_INT|VERSION_CODES|BuildConfig|Build\.VERSION'; then
    return 1
  fi
  # The smell: the assume gates on IME / keyboard availability.
  printf '%s' "$line" | grep -Eiq 'ime|keyboard|imeShown'
}

# --------------------------------------------------------------------------
# A5 scan
# --------------------------------------------------------------------------
declare -a A5_NEW=()        # NEW unjustified offenders -> hard fail
declare -a A5_KNOWN=()      # baselined offenders -> advisory
declare -a A5_JUSTIFIED=()  # opted out via // JUSTIFIED: -> advisory

scan_a5() {
  local file
  while IFS= read -r file; do
    [[ -z "$file" ]] && continue
    # Only files that are in IME/geometry territory can carry the A5 smell.
    if ! grep -Eq "$ime_geometry_words" "$file"; then
      continue
    fi
    # Find each assumeTrue / Assume.assumeTrue CALL site (the import line and
    # doc-comment mentions are ignored: a call has an open paren after it, and
    # we skip lines that are comments).
    local lineno
    while IFS= read -r lineno; do
      [[ -z "$lineno" ]] && continue
      local text
      text="$(sed -n "${lineno}p" "$file")"
      # Skip comment lines and the import statement.
      if printf '%s' "$text" | grep -Eq '^[[:space:]]*(\*|//|import )'; then
        continue
      fi
      # The assume call frequently spans lines (message on the next line); join
      # this line + the following two so the IME-skip heuristic can see the
      # message.
      local joined
      joined="$(sed -n "${lineno},$((lineno + 2))p" "$file" | tr '\n' ' ')"
      if ! assume_is_ime_skip "$joined"; then
        continue
      fi
      # Opt-out: a `// JUSTIFIED:` comment on this line or the line directly above.
      local prev
      prev="$(sed -n "$((lineno - 1))p" "$file")"
      if printf '%s\n%s' "$prev" "$text" | grep -q 'JUSTIFIED:'; then
        A5_JUSTIFIED+=("$file:$lineno")
        continue
      fi
      if is_baselined "$file"; then
        A5_KNOWN+=("$file:$lineno")
      else
        A5_NEW+=("$file:$lineno")
      fi
    done < <(grep -nE '(^|[^.[:alnum:]])(assumeTrue|Assume\.assumeTrue)[[:space:]]*\(' "$file" | cut -d: -f1)
  done < <(find "$ANDROID_TEST_ROOT" -type f -name '*.kt')
}

# --------------------------------------------------------------------------
# A4 / A2 scan (advisory) — *StandIn / *Proxy used in a smell-named file.
# --------------------------------------------------------------------------
declare -a A4_FINDINGS=()

scan_a4() {
  local file
  while IFS= read -r file; do
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
  done < <(find "$ANDROID_TEST_ROOT" -type f -name '*.kt')
}

# Validate the baseline: prune entries whose file no longer exists (its test was
# presumably deleted or its package moved), so the allowlist does not silently
# rot.
declare -a STALE_BASELINE=()
for b in "${A5_BASELINE[@]}"; do
  [[ -f "$b" ]] || STALE_BASELINE+=("$b")
done

scan_a5
scan_a4

echo "=============================================================="
echo " Test-validity guard (issue #657 / F4)"
echo " Scanned: $ANDROID_TEST_ROOT/**/*.kt"
echo "=============================================================="

print_list() {
  local label="$1"; shift
  # Filter out empty placeholders that `${arr[@]:-}` injects for an empty array
  # under `set -u`, so the count and listing reflect real findings only.
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

if [[ "${#STALE_BASELINE[@]}" -gt 0 ]]; then
  echo
  echo "NOTE: ${#STALE_BASELINE[@]} baseline entr(y/ies) no longer exist — prune from A5_BASELINE in this script:"
  for s in "${STALE_BASELINE[@]}"; do
    echo "  - $s"
  done
fi

echo
echo "--------------------------------------------------------------"
echo "Corrective model: app/src/androidTest/java/com/pocketshell/app/"
echo "composer/PromptComposerImeSquishProofTest.kt (#780) — synthetic"
echo "ime() inset + boundsInRoot containment + HARD assert, no skip."
echo "Containment helper: assertNodeFullyWithinRoot(...) /"
echo "assertNodeFullyAboveImeOrKeyboard(...) in"
echo "app/src/androidTest/java/com/pocketshell/app/proof/signals/ComposeSignals.kt"
echo "--------------------------------------------------------------"

# Filter empty placeholders that `${arr[@]:-}` may have produced.
real_a5_new=()
for x in "${A5_NEW[@]:-}"; do
  [[ -n "$x" ]] && real_a5_new+=("$x")
done

if [[ "$REPORT_MODE" -eq 1 ]]; then
  echo
  echo "Report mode (--report): findings printed; guard does not fail."
  exit 0
fi

if [[ "${#real_a5_new[@]}" -gt 0 ]]; then
  echo
  echo "::error title=Test-validity guard (issue #657)::A NEW IME/keyboard/geometry test gates its load-bearing assertion behind assumeTrue(...) — on CI the real soft IME often never raises, so the assertion silently does not run and the test passes vacuously. Convert it to the synthetic-inset model (PromptComposerImeSquishProofTest, #780) and HARD-assert, or add an inline // JUSTIFIED: comment if the assume is a legitimate non-IME guard."
  echo
  echo "FAIL: ${#real_a5_new[@]} new unjustified A5 occurrence(s)."
  exit 1
fi

echo
echo "PASS: no new unjustified IME-availability assumeTrue self-skips."
exit 0
