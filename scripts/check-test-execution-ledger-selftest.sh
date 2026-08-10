#!/usr/bin/env bash
# Self-test for scripts/check-test-execution-ledger.sh (#2063).
#
# The guard's whole value is its RED. Every case below drives it into a
# specific red and asserts that exact red — a stale class, an unmapped class, a
# never-executed class, an all-skipped class, an absent ledger — and each is
# paired with the green it is a mutation of, so "it always says FAIL" cannot
# masquerade as coverage either.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD="$SCRIPT_DIR/check-test-execution-ledger.sh"

SANDBOX="$(mktemp -d)"
cleanup() { rm -rf "$SANDBOX"; }
trap cleanup EXIT

PASS=0
FAIL=0
ok()  { PASS=$((PASS + 1)); printf 'ok   %s\n' "$*"; }
bad() { FAIL=$((FAIL + 1)); printf 'FAIL %s\n' "$*"; }

# ---------------------------------------------------------------------------
# Synthetic repo: two mapped test classes plus one that no rule covers.
# ---------------------------------------------------------------------------
mkdir -p \
  "$SANDBOX/scripts/lib" \
  "$SANDBOX/app/src/test/java/com/pocketshell/app/alpha" \
  "$SANDBOX/app/src/test/java/com/pocketshell/app/beta" \
  "$SANDBOX/results"

cp "$SCRIPT_DIR/lib/test-areas.sh" "$SANDBOX/scripts/lib/test-areas.sh"
cp "$GUARD" "$SANDBOX/scripts/check-test-execution-ledger.sh"

cat > "$SANDBOX/app/src/test/java/com/pocketshell/app/alpha/AlphaTest.kt" <<'KT'
package com.pocketshell.app.alpha
class AlphaTest
KT
cat > "$SANDBOX/app/src/test/java/com/pocketshell/app/beta/BetaTest.kt" <<'KT'
package com.pocketshell.app.beta
class BetaTest
KT

cat > "$SANDBOX/scripts/ci-journey-suite.sh" <<'SH'
#!/usr/bin/env bash
FQCN_PREFIX="com.pocketshell.app.proof"
JOURNEY_CLASSES=()
SH

MANIFEST="$SANDBOX/scripts/test-areas.txt"
cat > "$MANIFEST" <<'MF'
area   alpha   always   alpha area
area   beta    changed  beta area

full   scripts/*   harnesses

test   app/src/*/java/com/pocketshell/app/alpha/*   alpha   full
test   app/src/*/java/com/pocketshell/app/beta/*    beta    full
MF

git -C "$SANDBOX" init -q 2>/dev/null
git -C "$SANDBOX" add -A >/dev/null 2>&1
git -C "$SANDBOX" -c user.email=t@t -c user.name=t commit -qm init >/dev/null 2>&1

NOW=1800000000
DAY=86400

run_guard() {
  POCKETSHELL_TEST_AREAS_REPO_ROOT="$SANDBOX" \
  POCKETSHELL_TEST_AREAS_MANIFEST="$MANIFEST" \
  POCKETSHELL_TEST_AREAS_JOURNEY_SUITE="$SANDBOX/scripts/ci-journey-suite.sh" \
  bash "$SANDBOX/scripts/check-test-execution-ledger.sh" "$@"
}

write_ledger() {
  : > "$SANDBOX/ledger.tsv"
  local entry
  for entry in "$@"; do
    printf '%s\n' "$entry" >> "$SANDBOX/ledger.tsv"
  done
}

FRESH_ALPHA="com.pocketshell.app.alpha.AlphaTest	$NOW	unit"
FRESH_BETA="com.pocketshell.app.beta.BetaTest	$NOW	unit"

# ===========================================================================
# CASE 1 (the GREEN this suite mutates from): both classes executed today.
# ===========================================================================
write_ledger "$FRESH_ALPHA" "$FRESH_BETA"
if out="$(run_guard --verify --ledger "$SANDBOX/ledger.tsv" --now "$NOW" 2>&1)"; then
  ok "1 a fully fresh ledger passes"
else
  bad "1 fresh ledger should pass:\n$out"
fi

# ===========================================================================
# CASE 2 — a class STALE past the window reddens. (Explicitly required by the
# issue: "proven to redden when a class goes unexecuted past the threshold".)
# ===========================================================================
write_ledger "$FRESH_ALPHA" "com.pocketshell.app.beta.BetaTest	$((NOW - 8 * DAY))	unit"
if out="$(run_guard --verify --ledger "$SANDBOX/ledger.tsv" --now "$NOW" --max-age-days 7 2>&1)"; then
  bad "2 an 8-day-stale class did NOT redden the guard:\n$out"
elif grep -q 'have not executed within 7 day' <<<"$out" && grep -q 'BetaTest' <<<"$out"; then
  ok "2 a class last executed 8 days ago reddens the guard, naming the class"
else
  bad "2 guard failed for the wrong reason:\n$out"
fi

# ===========================================================================
# CASE 3 — the SAME ledger passes with a wider window. Proves case 2 is the
# age threshold doing the work, not some unrelated failure.
# ===========================================================================
if out="$(run_guard --verify --ledger "$SANDBOX/ledger.tsv" --now "$NOW" --max-age-days 30 2>&1)"; then
  ok "3 the same 8-day-stale ledger passes at --max-age-days 30 (the threshold is what reddened)"
else
  bad "3 widening the window should have passed:\n$out"
fi

# ===========================================================================
# CASE 4 — a class that has NEVER executed reddens (the #1851 shape).
# ===========================================================================
write_ledger "$FRESH_ALPHA"
if out="$(run_guard --verify --ledger "$SANDBOX/ledger.tsv" --now "$NOW" 2>&1)"; then
  bad "4 a never-executed class did NOT redden the guard:\n$out"
elif grep -q 'NEVER executed' <<<"$out" && grep -q 'BetaTest' <<<"$out"; then
  ok "4 a registered class with no ledger entry reddens the guard (#1851 class)"
else
  bad "4 guard failed for the wrong reason:\n$out"
fi

# ===========================================================================
# CASE 5 — a class in NO area reddens. (Explicitly required by the issue.)
# ===========================================================================
mkdir -p "$SANDBOX/app/src/test/java/com/pocketshell/app/orphan"
cat > "$SANDBOX/app/src/test/java/com/pocketshell/app/orphan/OrphanTest.kt" <<'KT'
package com.pocketshell.app.orphan
class OrphanTest
KT
git -C "$SANDBOX" add -A >/dev/null 2>&1
write_ledger "$FRESH_ALPHA" "$FRESH_BETA" "com.pocketshell.app.orphan.OrphanTest	$NOW	unit"
if out="$(run_guard --verify --ledger "$SANDBOX/ledger.tsv" --now "$NOW" 2>&1)"; then
  bad "5 a class belonging to no area did NOT redden the guard:\n$out"
elif grep -q 'belong to NO area' <<<"$out" && grep -q 'OrphanTest' <<<"$out"; then
  ok "5 a class in no area reddens the guard even though it executed today"
else
  bad "5 guard failed for the wrong reason:\n$out"
fi
git -C "$SANDBOX" rm -q --cached "app/src/test/java/com/pocketshell/app/orphan/OrphanTest.kt" >/dev/null 2>&1
rm -rf "$SANDBOX/app/src/test/java/com/pocketshell/app/orphan"

# ===========================================================================
# CASE 6 — a MISSING ledger reddens. A guard with no evidence must not pass.
# ===========================================================================
rm -f "$SANDBOX/ledger.tsv"
if out="$(run_guard --verify --ledger "$SANDBOX/ledger.tsv" --now "$NOW" 2>&1)"; then
  bad "6 a missing ledger did NOT redden the guard:\n$out"
elif grep -q 'ledger is missing or empty' <<<"$out"; then
  ok "6 a missing ledger reddens the guard (no evidence is not a pass)"
else
  bad "6 guard failed for the wrong reason:\n$out"
fi

# ===========================================================================
# CASE 7 — an ALL-SKIPPED class is not recorded as executed, so a later verify
# still reports it as never-executed. This is the G3 vacuous-pass trap applied
# to the ledger: "the class appeared in the XML" is not "the class ran".
# ===========================================================================
cat > "$SANDBOX/results/TEST-mixed.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="mixed" tests="3">
  <testcase name="a" classname="com.pocketshell.app.alpha.AlphaTest" time="0.01"/>
  <testcase name="b" classname="com.pocketshell.app.beta.BetaTest" time="0"><skipped/></testcase>
  <testcase name="c" classname="com.pocketshell.app.beta.BetaTest" time="0"><skipped message="assumption"/></testcase>
</testsuite>
XML
rm -f "$SANDBOX/ledger.tsv"
if ! out="$(run_guard --record "$SANDBOX/results" --ledger "$SANDBOX/ledger.tsv" --tier unit --now "$NOW" 2>&1)"; then
  bad "7 --record failed:\n$out"
elif grep -q 'AlphaTest' "$SANDBOX/ledger.tsv" && ! grep -q 'BetaTest' "$SANDBOX/ledger.tsv"; then
  ok "7 --record credits the class that ran and NOT the all-skipped class"
else
  bad "7 record credited the wrong classes:\n$(cat "$SANDBOX/ledger.tsv")"
fi
if out="$(run_guard --verify --ledger "$SANDBOX/ledger.tsv" --now "$NOW" 2>&1)"; then
  bad "8 an all-skipped class satisfied the ledger — the G3 hole is open:\n$out"
elif grep -q 'NEVER executed' <<<"$out" && grep -q 'BetaTest' <<<"$out"; then
  ok "8 the all-skipped class is still reported as never executed"
else
  bad "8 guard failed for the wrong reason:\n$out"
fi

# ===========================================================================
# CASE 9 — a --record over a result set with no testcases at all is refused,
# rather than silently leaving a stale ledger for verify to misread.
# ===========================================================================
mkdir -p "$SANDBOX/empty-results"
cat > "$SANDBOX/empty-results/TEST-none.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="none" tests="0"></testsuite>
XML
if out="$(run_guard --record "$SANDBOX/empty-results" --ledger "$SANDBOX/ledger.tsv" --now "$NOW" 2>&1)"; then
  bad "9 recording a zero-testcase result set was accepted:\n$out"
elif grep -q 'refusing to record an empty run' <<<"$out"; then
  ok "9 recording a zero-testcase result set is refused"
else
  bad "9 record failed for the wrong reason:\n$out"
fi

# ===========================================================================
# CASE 10 — --record MERGES: a later tier's results must not erase an earlier
# tier's entries. The real cadence records unit, emulator and nightly
# separately; a clobbering record would make every non-latest tier look stale.
# ===========================================================================
write_ledger "com.pocketshell.app.beta.BetaTest	$((NOW - DAY))	emulator"
mkdir -p "$SANDBOX/unit-results"
cat > "$SANDBOX/unit-results/TEST-unit.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="unit" tests="1">
  <testcase name="a" classname="com.pocketshell.app.alpha.AlphaTest" time="0.01"/>
</testsuite>
XML
run_guard --record "$SANDBOX/unit-results" --ledger "$SANDBOX/ledger.tsv" --tier unit --now "$NOW" >/dev/null 2>&1
if grep -q 'AlphaTest' "$SANDBOX/ledger.tsv" && grep -q 'BetaTest' "$SANDBOX/ledger.tsv"; then
  ok "10 --record merges tiers instead of clobbering the previous tier's entries"
else
  bad "10 record clobbered the ledger:\n$(cat "$SANDBOX/ledger.tsv")"
fi

# ===========================================================================
# CASES 11-13 — THE REAL TREE.
#
# Cases 1-10 all run against synthetic app-style temp trees, and that is exactly
# how round 1 shipped a guard that was RED on the real repository: the FQCN
# resolver probed a hardcoded list of source roots that contained no
# `shared/*/src/test/java`, so 183 of 1047 registered classes — every shared
# module unit test, including all 49 core-ssh and 22 core-connection D28
# classes — reported "belongs to NO area". The synthetic cases could not see it
# because every class in them lives under `app/src/test/java`.
#
# So the real tree is now a case, and it is paired with two mutations so it is
# not merely "a guard that says OK".
# ===========================================================================
SELECT="$SCRIPT_DIR/select-test-areas.sh"
REAL_NOW=1800000000
REAL_LEDGER="$SANDBOX/real-ledger.tsv"

bash "$SELECT" --list-classes 2>/dev/null |
  awk -v n="$REAL_NOW" -F'\t' '{print $1"\t"n"\tunit"}' > "$REAL_LEDGER"
real_count="$(wc -l < "$REAL_LEDGER" | tr -d ' ')"

if [[ "$real_count" -lt 500 ]]; then
  bad "11 could not build a real-tree ledger (only $real_count classes) — the class listing is broken"
elif out="$(bash "$GUARD" --verify --ledger "$REAL_LEDGER" --now "$REAL_NOW" 2>&1)"; then
  ok "11 a complete fresh ledger of all $real_count REAL registered classes passes --verify"
else
  bad "11 --verify is RED against a complete fresh real-tree ledger:\n$out"
fi

# CASE 12 — selectivity for case 11: drop ONE real class and the guard must name
# it. Without this, case 11 could pass with the guard doing nothing.
dropped="com.pocketshell.core.ssh.SshLeaseManagerTest"
grep -v "^${dropped}	" "$REAL_LEDGER" > "$SANDBOX/real-ledger-minus.tsv"
if out="$(bash "$GUARD" --verify --ledger "$SANDBOX/real-ledger-minus.tsv" --now "$REAL_NOW" 2>&1)"; then
  bad "12 dropping $dropped from the real ledger did NOT redden the guard:\n$out"
elif grep -q 'NEVER executed' <<<"$out" && grep -q "$dropped" <<<"$out"; then
  ok "12 dropping one real shared-module class reddens the guard, naming it"
else
  bad "12 guard failed for the wrong reason:\n$out"
fi

# CASE 13 — the EXACT round-1 defect, reproduced through the FQCN resolver on
# the real tree: with the core-ssh rules removed from the manifest, every
# core-ssh class must be reported as belonging to NO area. Round 1 produced this
# red from an intact manifest, because the resolver could not find the files.
real_mut="$SANDBOX/real-manifest-no-core-ssh.txt"
grep -v '^src    shared/core-ssh/\*' "$SCRIPT_DIR/test-areas.txt" |
  grep -v '^test   shared/core-ssh/\*' > "$real_mut"
if out="$(POCKETSHELL_TEST_AREAS_MANIFEST="$real_mut" \
          bash "$GUARD" --verify --ledger "$REAL_LEDGER" --now "$REAL_NOW" 2>&1)"; then
  bad "13 removing the core-ssh manifest rules did NOT redden the real-tree area check:\n$out"
elif grep -q 'belong to NO area' <<<"$out" && grep -q 'com.pocketshell.core.ssh.' <<<"$out"; then
  ok "13 removing the core-ssh rules reddens the real-tree 'no area' check, naming core-ssh classes"
else
  bad "13 guard failed for the wrong reason:\n$out"
fi

# CASE 14 — the round-1 defect ITSELF, replayed as a code mutation: a resolver
# that only knows about `app/src/**` roots. That is precisely what
# POCKETSHELL_TA_CLASS_SEARCH_ROOTS was, and it made this guard red for 183
# classes while every synthetic case stayed green. The mutation must reproduce
# that exact red, and it must be proven LIVE first.
MUTDIR="$SANDBOX/mut-scripts"
mkdir -p "$MUTDIR/lib"
cp "$GUARD" "$MUTDIR/check-test-execution-ledger.sh"
cp "$SCRIPT_DIR/lib/test-areas.sh" "$MUTDIR/lib/test-areas.sh"
cp "$SCRIPT_DIR/test-areas.txt" "$MUTDIR/test-areas.txt"
sed -i '/registry entries$/a\  if [[ "$fqcn" != com.pocketshell.app.* ]]; then printf ""; return 1; fi' \
  "$MUTDIR/lib/test-areas.sh"
if ! grep -q 'if \[\[ "\$fqcn" != com.pocketshell.app.\* \]\]; then printf ""; return 1; fi' "$MUTDIR/lib/test-areas.sh"; then
  bad "14 MUTATION DID NOT APPLY — the app-only resolver mutant is not live, so 14's verdict would be meaningless"
else
  out="$(POCKETSHELL_TEST_AREAS_REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)" \
         POCKETSHELL_TEST_AREAS_MANIFEST="$MUTDIR/test-areas.txt" \
         POCKETSHELL_TEST_AREAS_JOURNEY_SUITE="$SCRIPT_DIR/ci-journey-suite.sh" \
         bash "$MUTDIR/check-test-execution-ledger.sh" --verify --ledger "$REAL_LEDGER" --now "$REAL_NOW" 2>&1)"
  if grep -q 'belong to NO area' <<<"$out" &&
     grep -q 'com.pocketshell.core.ssh.' <<<"$out" &&
     grep -q 'com.pocketshell.core.connection.' <<<"$out"; then
    ok "14 an app-only class resolver (the round-1 shape) reddens the real-tree guard for core-ssh and core-connection"
  else
    bad "14 the app-only-roots resolver did NOT reproduce the round-1 red:\n$(grep -E 'OK:|FAIL:' <<<"$out")"
  fi
fi

# CASE 15 (round-2 finding B7b) — THE GUARD MUST READ THE SINGLE RESOLVER, NOT
# A COPY OF ITS CHAIN.
#
# Round 2 re-inlined `${CLASS_AREA:-${CLASS_RESOLVED:-${PKG_TEST_AREA:-}}}` at
# this guard's call site — at the exact spot of the round-1 B4 two-resolver
# defect. The reviewer correctly called it LATENT: the inline copy is equivalent
# to the resolver *today*, so no mutation of the tree as it stands can tell them
# apart. What distinguishes them is a FOURTH fallback added to the resolver, so
# this case builds exactly that and runs BOTH shapes against it:
#
#   15a  resolver gains a 4th fallback, guard reads the resolver's global  -> GREEN
#   15b  same 4th fallback, guard re-inlines the 3-term chain              -> RED
#
# 15b is the round-2 code. Without 15a the mutation would just look broken; with
# both, the pair proves the routing itself is load-bearing.
B7DIR="$SANDBOX/mut-b7b"
mkdir -p "$B7DIR/lib"
cp "$GUARD" "$B7DIR/check-test-execution-ledger.sh"
cp "$SCRIPT_DIR/lib/test-areas.sh" "$B7DIR/lib/test-areas.sh"
cp "$SCRIPT_DIR/test-areas.txt" "$B7DIR/test-areas.txt"

# The 4th fallback: force one real shared-module class out of all three existing
# maps, and answer it only from a new last-resort table.
python3 - "$B7DIR/lib/test-areas.sh" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
anchor = '  [[ -z "$POCKETSHELL_TEST_AREA_FOR_CLASS" ]] && POCKETSHELL_TEST_AREA_FOR_CLASS="${POCKETSHELL_TA_PKG_TEST_AREA[$pkg]:-}"'
assert s.count(anchor) == 1, "B7b anchor not unique"
inject = (
    '  # MUTANT 15: hide one class from all three existing maps ...\n'
    '  if [[ "$fqcn" == com.pocketshell.core.usage.PocketshellUsageJsonParserTest ]]; then\n'
    '    unset "POCKETSHELL_TA_CLASS_AREA[$fqcn]"\n'
    '    unset "POCKETSHELL_TA_CLASS_RESOLVED[$fqcn]"\n'
    '    unset "POCKETSHELL_TA_PKG_TEST_AREA[$pkg]"\n'
    '    # ... and answer it only from a FOURTH fallback the resolver alone knows.\n'
    '    POCKETSHELL_TEST_AREA_FOR_CLASS="usage-costs"\n'
    '  fi\n'
)
s = s.replace(anchor, anchor + '\n' + inject)
open(p, "w").write(s)
PY
grep -q 'MUTANT 15' "$B7DIR/lib/test-areas.sh" ||
  bad "15 MUTATION DID NOT APPLY — the fourth-fallback resolver is not live, so 15's verdict would be meaningless"

run_b7() {
  POCKETSHELL_TEST_AREAS_REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)" \
  POCKETSHELL_TEST_AREAS_MANIFEST="$B7DIR/test-areas.txt" \
  POCKETSHELL_TEST_AREAS_JOURNEY_SUITE="$SCRIPT_DIR/ci-journey-suite.sh" \
  bash "$B7DIR/check-test-execution-ledger.sh" --verify --ledger "$REAL_LEDGER" --now "$REAL_NOW" 2>&1
}

out="$(run_b7)"
if grep -q 'every registered class belongs to an area' <<<"$out"; then
  ok "15a the SHIPPED guard follows a fourth resolver fallback (it reads POCKETSHELL_TEST_AREA_FOR_CLASS, not a copy of the chain)"
else
  bad "15a the shipped guard did not follow the resolver's fourth fallback:\n$(grep -E 'OK:|FAIL:' <<<"$out")"
fi

# 15b — put the round-2 inlined chain back and watch the same tree go red.
python3 - "$B7DIR/check-test-execution-ledger.sh" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
anchor = '    pocketshell_test_area_for_class "$cls" >/dev/null && area="$POCKETSHELL_TEST_AREA_FOR_CLASS"'
assert s.count(anchor) == 1, "B7b guard anchor not unique"
s = s.replace(anchor,
  '    pocketshell_test_area_for_class "$cls" >/dev/null && \\\n'
  '      area="${POCKETSHELL_TA_CLASS_AREA[${cls%%#*}]:-${POCKETSHELL_TA_CLASS_RESOLVED[${cls%%#*}]:-${POCKETSHELL_TA_PKG_TEST_AREA[${cls%.*}]:-}}}"  # MUTANT 15b\n')
open(p, "w").write(s)
PY
grep -q 'MUTANT 15b' "$B7DIR/check-test-execution-ledger.sh" ||
  bad "15b MUTATION DID NOT APPLY — the re-inlined chain is not live in the copy"
out="$(run_b7)"
if grep -q 'belong to NO area' <<<"$out" &&
   grep -q 'com.pocketshell.core.usage.PocketshellUsageJsonParserTest' <<<"$out"; then
  ok "15b re-inlining the resolution chain (the round-2 shape) makes the SAME tree red — the single-resolver routing is load-bearing, not cosmetic"
else
  bad "15b the re-inlined chain behaved identically, so 15a proves nothing:\n$(grep -E 'OK:|FAIL:' <<<"$out")"
fi

echo
echo "check-test-execution-ledger selftest: $PASS passed, $FAIL failed"
[[ "$FAIL" -eq 0 ]] || exit 1
exit 0
