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

# The class the untracked-@Ignore cases plant their fixture annotation into. It
# must be a real journey class (so the guard's registry accepts it) that the
# shipped list does NOT quarantine, so a planted annotation is genuinely
# untracked.
FIXTURE_CLASS="com.pocketshell.next.usage.J12UsagePanelJourney"

# Plants `@Ignore("silently parked")` on the first @Test method of
# $FIXTURE_CLASS in the SANDBOX copy and echoes the method name it annotated.
#
# Every case that needs "a live @Ignore the list does not account for" builds it
# HERE rather than relying on one happening to exist in the shipped tree. That
# incidental dependency is a real trap: it made case (d) pass only for as long
# as some unrelated journey happened to be quarantined, and turned the very
# first empty-list tree (issue #2478 removing the last two annotations) into a
# spurious red. Callers must reset_root first.
plant_untracked_ignore() {
  local src="$SANDBOX/root/java/${FIXTURE_CLASS//./\/}.kt"
  if [[ ! -f "$src" ]]; then
    echo "fixture source not found: $src" >&2
    return 1
  fi
  if grep -qF "$FIXTURE_CLASS" "$QUARANTINE_LIST"; then
    echo "fixture assumption broken: $FIXTURE_CLASS is itself quarantined" >&2
    return 1
  fi
  python3 - "$src" <<'PY'
import re, sys
path = sys.argv[1]
src = open(path).read()
needle = "    @Test\n"
i = src.index(needle)
rest = src[i + len(needle):]
m = re.search(r'\bfun[ \t]+([A-Za-z_][A-Za-z0-9_]*)[ \t]*\(', rest)
if not m:
    sys.exit("no @Test method found to annotate")
src = src[:i] + "    @Test\n    @Ignore(\"silently parked\")\n" + rest
if "import org.junit.Ignore" not in src:
    src = src.replace("import org.junit.Test", "import org.junit.Ignore\nimport org.junit.Test", 1)
open(path, 'w').write(src)
print(m.group(1))
PY
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
fi

# ---------------------------------------------------------------------------
# (c) AN @Ignore WITH NO ROW STILL BLOCKS THE BUILD. Quarantine is per METHOD
#     and per registry row; annotating a method nobody triaged must not be a
#     silent exemption. The fixture annotation is PLANTED here, so this case
#     proves the property whether or not the shipped list currently has rows.
# ---------------------------------------------------------------------------
reset_root
c_method="$(plant_untracked_ignore 2>&1)" || fail "(c) MUTATION DID NOT APPLY: $c_method"
out="$(run_guard)" && fail "(c) an @Ignore on an unregistered journey method did NOT redden the guard — a test can be parked forever with no issue and no expiry:
$out"
grep -qF "$FIXTURE_CLASS#$c_method: @Ignore on a journey @Test with NO row in" <<<"$out" ||
  fail "(c) the guard reddened for the wrong reason (expected the planted $FIXTURE_CLASS#$c_method to be reported untracked):
$out"
pass "(c) an @Ignore with no registry row reddens (quarantine is a queue, not a graveyard)"

# ---------------------------------------------------------------------------
# (d) FAIL-SAFE: a malformed list must not legitimise the annotations it fails
#     to parse. A broken list means NOTHING is tracked, so every live @Ignore
#     becomes untracked and the guard reddens — never the other way round.
#
#     The @Ignore this case needs is PLANTED, not borrowed from whatever the
#     shipped tree happens to contain. Borrowing made the case unprovable in
#     the steady state the policy is aiming for — an empty list and no live
#     annotations — where it reported a spurious red (issue #2478's PR) even
#     though the guard behaved correctly.
# ---------------------------------------------------------------------------
reset_root
d_method="$(plant_untracked_ignore 2>&1)" || fail "(d) MUTATION DID NOT APPLY: $d_method"
broken="$SANDBOX/broken-list.txt"
printf 'this-row-has-no-tabs-at-all\n' > "$broken"
out="$(POCKETSHELL_JOURNEY_QUARANTINE_ROOT="$SANDBOX/root" \
       POCKETSHELL_JOURNEY_QUARANTINE_FILE="$broken" bash "$GUARD" 2>&1)" && \
  fail "(d) a malformed quarantine list was treated as clean:
$out"
if grep -q 'parse error' <<<"$out" &&
   grep -qF "$FIXTURE_CLASS#$d_method: @Ignore on a journey @Test with NO row in" <<<"$out"; then
  pass "(d) a malformed list fails closed: it is a parse error AND its annotations become untracked"
else
  fail "(d) a malformed list did not fail closed in both ways (expected a parse error AND the planted $FIXTURE_CLASS#$d_method reported untracked):
$out"
fi

echo
echo "All journey-quarantine source-level cases passed."
