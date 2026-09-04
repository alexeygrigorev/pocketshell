#!/usr/bin/env bash
# Issue #2355 (D36 flake quarantine) — self-test for the CONSUMPTION half:
# a quarantined method must actually be non-blocking, and a non-quarantined one
# must still block.
#
# WHAT CHANGED, AND WHY THIS FILE LOOKS DIFFERENT NOW.
#
# The old mechanism was a RUNNER-level exclusion: the per-class journey loop
# read scripts/journey-quarantine.txt and, for a listed class, reported a
# both-attempts failure into summary.md without flipping the suite's exit code.
# This script drove that loop directly. Both the loop and the suite it belonged
# to were deleted with the old app module.
#
# app2's lane (issue #2474) runs `:app2:connectedDebugAndroidTest` ONCE,
# unfiltered, in a single instrumentation process, and deliberately forbids a
# `-Pandroid.testInstrumentationRunnerArguments.class=` filter — that filter is
# exactly what would hide the cross-journey state pollution #2477 was found by.
# So there is no runner-level seam left to exempt a class at, and inventing one
# would trade #2474's guarantee away for D36's.
#
# Quarantine therefore moved DOWN to the source: `@Ignore` on the method. That
# satisfies both — the whole suite still runs together in one process (nothing
# is excluded from it), while a known-bad assertion stops blocking.
#
# WHAT MUST BE PROVEN, THEN, IS THE PROPERTY OF THE ANNOTATION, and it splits in
# two. JUnit's own semantics supply half: an @Ignore'd method is reported
# `<skipped/>`, and a skipped test cannot fail a run. That half is not this
# script's to re-prove — asserting "JUnit skips @Ignore" would be testing JUnit.
# The half that IS ours, and the half that can actually rot, is that the
# annotation is present on exactly the methods the registry says and no others.
# That is what the cases below drive, against the REAL guard and REAL sources,
# with a mutation for each direction.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
GUARD="$SCRIPT_DIR/check-journey-quarantine-expiry.sh"
QUARANTINE_LIST="$SCRIPT_DIR/journey-quarantine.txt"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

[[ -f "$GUARD" ]] || fail "missing $GUARD"
[[ -f "$QUARANTINE_LIST" ]] || fail "missing $QUARANTINE_LIST"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

# The lane's androidTest root, copied so mutations never touch the worktree.
JOURNEY_ROOT="$(sed -nE 's/^JOURNEY_TASK="([^"]+)".*/\1/p' \
  "$SCRIPT_DIR/ci-app2-journey-suite.sh" | head -1)"
[[ -n "$JOURNEY_ROOT" ]] || fail "could not read JOURNEY_TASK from ci-app2-journey-suite.sh"
JOURNEY_MODULE="${JOURNEY_ROOT#:}"; JOURNEY_MODULE="${JOURNEY_MODULE%:*}"
REAL_ROOT="$REPO_ROOT/${JOURNEY_MODULE//://}/src/androidTest"
[[ -d "$REAL_ROOT" ]] || fail "journey androidTest root not found: $REAL_ROOT"

reset_root() {
  rm -rf "$SANDBOX/root"
  cp -r "$REAL_ROOT" "$SANDBOX/root"
}

run_guard() {  # against the sandbox root + the real list
  POCKETSHELL_JOURNEY_QUARANTINE_ROOT="$SANDBOX/root" bash "$GUARD" 2>&1
}

# ---------------------------------------------------------------------------
# (a) GREEN CONTROL — the shipped tree reconciles. Without this, every red
#     below could be a red the tree already had.
# ---------------------------------------------------------------------------
reset_root
if out="$(run_guard)"; then
  pass "(a) the shipped sources and the shipped quarantine list reconcile"
else
  fail "(a) the shipped tree is already RED, so no mutation below proves anything:
$out"
fi

# ---------------------------------------------------------------------------
# (b) A QUARANTINED METHOD IS ACTUALLY ANNOTATED. Strip the @Ignore from a
#     registered row's method: the guard must name it. This is the direction
#     that catches a quarantine that was recorded but never took effect — the
#     method would keep blocking while the list claimed it did not.
# ---------------------------------------------------------------------------
first_row="$(grep -vE '^[[:space:]]*(#|$)' "$QUARANTINE_LIST" | head -1 | cut -f1)"
if [[ -z "$first_row" ]]; then
  pass "(b) skipped: the quarantine list is empty, so there is no row to strip"
  pass "(c) skipped: the quarantine list is empty"
else
  q_class="${first_row%%#*}"
  q_method="${first_row#*#}"
  q_src="$SANDBOX/root/java/${q_class//./\/}.kt"
  reset_root
  [[ -f "$q_src" ]] || fail "(b) could not find the source for $q_class at $q_src"
  # Remove only the @Ignore that governs THIS method.
  python3 - "$q_src" "$q_method" <<'PY'
import re, sys
path, method = sys.argv[1], sys.argv[2]
src = open(path).read()
pat = re.compile(r'[ \t]*@Ignore\([^\n]*\)\n(?=(?:[ \t]*@[A-Za-z][^\n]*\n)*[ \t]*fun[ \t]+' + re.escape(method) + r'[ \t]*\()')
src, n = pat.subn('', src, count=1)
if n != 1:
    sys.exit("mutation did not apply: no @Ignore governing " + method)
open(path, 'w').write(src)
PY
  [[ $? -eq 0 ]] || fail "(b) MUTATION DID NOT APPLY"
  out="$(run_guard)" && fail "(b) stripping the @Ignore from $first_row did NOT redden the guard:
$out"
  grep -q 'carries NO @Ignore' <<<"$out" && grep -qF "$q_method" <<<"$out" ||
    fail "(b) the guard reddened for the wrong reason:
$out"
  pass "(b) a registered row whose method lost its @Ignore reddens, naming the method"

  # -------------------------------------------------------------------------
  # (c) SIBLINGS STILL BLOCK. Quarantine is per METHOD; a sibling @Test in the
  #     same class must not inherit the exemption. Assert the guard treats an
  #     @Ignore on an unregistered sibling as untracked.
  # -------------------------------------------------------------------------
  reset_root
  sibling_class="com.pocketshell.next.usage.J12UsagePanelJourney"
  sibling_src="$SANDBOX/root/java/${sibling_class//./\/}.kt"
  [[ -f "$sibling_src" ]] || fail "(c) sibling fixture source not found: $sibling_src"
  grep -qF "$sibling_class" "$QUARANTINE_LIST" &&
    fail "(c) fixture assumption broken: $sibling_class is itself quarantined"
  python3 - "$sibling_src" <<'PY'
import sys
path = sys.argv[1]
src = open(path).read()
needle = "    @Test\n"
i = src.index(needle)
src = src[:i] + "    @Test\n    @Ignore(\"silently parked\")\n" + src[i + len(needle):]
if "import org.junit.Ignore" not in src:
    src = src.replace("import org.junit.Test", "import org.junit.Ignore\nimport org.junit.Test", 1)
open(path, 'w').write(src)
PY
  [[ $? -eq 0 ]] || fail "(c) MUTATION DID NOT APPLY"
  out="$(run_guard)" && fail "(c) an @Ignore on an unregistered journey method did NOT redden the guard — a test can be parked forever with no issue and no expiry:
$out"
  grep -q 'NO row in' <<<"$out" ||
    fail "(c) the guard reddened for the wrong reason:
$out"
  pass "(c) an @Ignore with no registry row reddens (quarantine is a queue, not a graveyard)"
fi

# ---------------------------------------------------------------------------
# (d) FAIL-SAFE: a malformed list must not legitimise the annotations it fails
#     to parse. A broken list means NOTHING is tracked, so every live @Ignore
#     becomes untracked and the guard reddens — never the other way round.
# ---------------------------------------------------------------------------
reset_root
broken="$SANDBOX/broken-list.txt"
printf 'this-row-has-no-tabs-at-all\n' > "$broken"
out="$(POCKETSHELL_JOURNEY_QUARANTINE_ROOT="$SANDBOX/root" \
       POCKETSHELL_JOURNEY_QUARANTINE_FILE="$broken" bash "$GUARD" 2>&1)" && \
  fail "(d) a malformed quarantine list was treated as clean:
$out"
if grep -q 'parse error' <<<"$out" && grep -q 'NO row in' <<<"$out"; then
  pass "(d) a malformed list fails closed: it is a parse error AND its annotations become untracked"
else
  fail "(d) a malformed list did not fail closed in both ways:
$out"
fi

echo
echo "All journey-quarantine source-level cases passed."
