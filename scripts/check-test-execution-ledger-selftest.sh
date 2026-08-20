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

# ===========================================================================
# CASES 16-22 — current-run attendance (#2082 / remaining #1859 contract).
# Selected vs executed unskipped vs asserted. A green that would still pass
# if the ledger never compared the selector to the XML is decorative.
# ===========================================================================
ATTEND="$SANDBOX/attendance.tsv"
SEL="$SANDBOX/selected.txt"
printf '%s\n' \
  "com.pocketshell.app.alpha.AlphaTest" \
  "com.pocketshell.app.beta.BetaTest" > "$SEL"

mkdir -p "$SANDBOX/att-results"
cat > "$SANDBOX/att-results/TEST-both.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="both" tests="2">
  <testcase name="a" classname="com.pocketshell.app.alpha.AlphaTest" time="0.20"/>
  <testcase name="b" classname="com.pocketshell.app.beta.BetaTest" time="1.50"/>
</testsuite>
XML

if out="$(run_guard --attendance --results-root "$SANDBOX/att-results" \
          --selected-file "$SEL" --out "$ATTEND" \
          --identity "workflow=selftest" --identity "shard=0" \
          --require-class com.pocketshell.app.alpha.AlphaTest 2>&1)"; then
  if grep -q 'selected_class_count	2' "$ATTEND" &&
     grep -q 'executed_unskipped_count	2' "$ATTEND" &&
     grep -q 'asserted_count	2' "$ATTEND" &&
     grep -q 'missing_class_count	0' "$ATTEND" &&
     grep -q 'identity.workflow	selftest' "$ATTEND"; then
    ok "16 full attendance (selected=executed=asserted) passes and writes the three counts plus identity"
  else
    bad "16 attendance passed but the report is missing load-bearing counts:\n$(cat "$ATTEND")"
  fi
else
  bad "16 full attendance should pass:\n$out"
fi

# CASE 17 — the #1859 truncation: selected 2, XML only has 1. Must RED and
# name the missing FQCN. Mutation: drop BetaTest from the XML.
cat > "$SANDBOX/att-results/TEST-both.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="trunc" tests="1">
  <testcase name="a" classname="com.pocketshell.app.alpha.AlphaTest" time="0.20"/>
</testsuite>
XML
if out="$(run_guard --attendance --results-root "$SANDBOX/att-results" \
          --selected-file "$SEL" --out "$ATTEND" \
          --require-class com.pocketshell.app.beta.BetaTest 2>&1)"; then
  bad "17 truncated XML (BetaTest missing) did NOT redden attendance:\n$out"
elif grep -q 'produced NO result' <<<"$out" && grep -q 'BetaTest' <<<"$out" &&
     grep -q 'required class' <<<"$out"; then
  ok "17 truncated shard reddens attendance, names the missing class, and fails the required-class pin"
else
  bad "17 truncated attendance failed for the wrong reason:\n$out"
fi

# CASE 18 — all-skipped is executed≠asserted: selected class is in the XML
# but does not satisfy --require-class. G3 applied to attendance.
cat > "$SANDBOX/att-results/TEST-both.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="skip" tests="2">
  <testcase name="a" classname="com.pocketshell.app.alpha.AlphaTest" time="0.20"/>
  <testcase name="b" classname="com.pocketshell.app.beta.BetaTest" time="0"><skipped/></testcase>
</testsuite>
XML
if out="$(run_guard --attendance --results-root "$SANDBOX/att-results" \
          --selected-file "$SEL" --require-class com.pocketshell.app.beta.BetaTest 2>&1)"; then
  bad "18 all-skipped required class satisfied attendance — the G3 hole is open:\n$out"
elif grep -q 'was not asserted/load-bearing' <<<"$out" && grep -q 'BetaTest' <<<"$out"; then
  ok "18 an all-skipped class does not satisfy --require-class (asserted ≠ executed-as-skipped)"
else
  bad "18 attendance failed for the wrong reason:\n$out"
fi

# CASE 19 — empty results root / no testcases is not a passing verify.
mkdir -p "$SANDBOX/att-empty"
cat > "$SANDBOX/att-empty/TEST-none.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="none" tests="0"></testsuite>
XML
if out="$(run_guard --attendance --results-root "$SANDBOX/att-empty" \
          --selected-file "$SEL" 2>&1)"; then
  bad "19 empty JUnit result set was accepted as attendance:\n$out"
elif grep -q 'no JUnit testcases found' <<<"$out"; then
  ok "19 empty JUnit result set reddens attendance (absent artifact ≠ completed shard)"
else
  bad "19 empty attendance failed for the wrong reason:\n$out"
fi

# CASE 20 — absent results root reddens (same family as absent ledger).
if out="$(run_guard --attendance --results-root "$SANDBOX/no-such-results" \
          --selected-file "$SEL" 2>&1)"; then
  bad "20 missing results root was accepted:\n$out"
elif grep -q 'results root not found' <<<"$out"; then
  ok "20 a missing results root reddens attendance (no evidence is not a pass)"
else
  bad "20 missing-root attendance failed for the wrong reason:\n$out"
fi

# CASE 21 — merge-attendance: two shards, union covers selected; drop one
# class from BOTH shards and the merge must name it. This is the #1859
# shard-0-truncated-at-50-classes shape against the wholesale selected set.
mkdir -p "$SANDBOX/merge/s0" "$SANDBOX/merge/s1"
cat > "$SANDBOX/att-results/TEST-both.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="s0" tests="1">
  <testcase name="a" classname="com.pocketshell.app.alpha.AlphaTest" time="0.20"/>
</testsuite>
XML
run_guard --attendance --results-root "$SANDBOX/att-results" --selected-file "$SEL" \
  --out "$SANDBOX/merge/s0/phase1-attendance.tsv" --report >/dev/null 2>&1 || true
cat > "$SANDBOX/att-results/TEST-both.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="s1" tests="1">
  <testcase name="b" classname="com.pocketshell.app.beta.BetaTest" time="0.40"/>
</testsuite>
XML
run_guard --attendance --results-root "$SANDBOX/att-results" --selected-file "$SEL" \
  --out "$SANDBOX/merge/s1/phase1-attendance.tsv" --report >/dev/null 2>&1 || true

if out="$(run_guard --merge-attendance "$SANDBOX/merge" --selected-file "$SEL" \
          --require-class com.pocketshell.app.alpha.AlphaTest \
          --require-class com.pocketshell.app.beta.BetaTest \
          --out "$SANDBOX/merged.tsv" 2>&1)"; then
  if grep -q 'missing_class_count	0' "$SANDBOX/merged.tsv"; then
    ok "21 merge-attendance unions shard results so a class present on any shard is not missing"
  else
    bad "21 merge should have covered both classes:\n$(cat "$SANDBOX/merged.tsv")"
  fi
else
  bad "21 union of two complementary shards should pass:\n$out"
fi

# CASE 22 — same merge, but BetaTest is on NO shard (the truncated-shard-0
# class that sibling shards never ran). Must RED.
rm -rf "$SANDBOX/merge-trunc"
mkdir -p "$SANDBOX/merge-trunc/s0" "$SANDBOX/merge-trunc/s1"
cat > "$SANDBOX/att-results/TEST-both.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="s0" tests="1">
  <testcase name="a" classname="com.pocketshell.app.alpha.AlphaTest" time="0.20"/>
</testsuite>
XML
run_guard --attendance --results-root "$SANDBOX/att-results" --selected-file "$SEL" \
  --out "$SANDBOX/merge-trunc/s0/phase1-attendance.tsv" --report >/dev/null 2>&1 || true
# shard 1 also only has AlphaTest — BetaTest is the ~70 missing classes.
cp "$SANDBOX/merge-trunc/s0/phase1-attendance.tsv" "$SANDBOX/merge-trunc/s1/phase1-attendance.tsv"
if out="$(run_guard --merge-attendance "$SANDBOX/merge-trunc" --selected-file "$SEL" \
          --require-class com.pocketshell.app.beta.BetaTest 2>&1)"; then
  bad "22 merge of two shards that both lack BetaTest did NOT redden:\n$out"
elif grep -q 'BetaTest' <<<"$out" && grep -q 'produced NO result' <<<"$out"; then
  ok "22 merge-attendance reddens when a selected class is missing from every shard (#1859)"
else
  bad "22 merge-truncation failed for the wrong reason:\n$out"
fi

# CASE 23 — --verify --source-set unit ignores an androidTest-only gap.
# Selectivity: the same ledger without --source-set is still RED (case 4).
write_ledger "$FRESH_ALPHA"
# BetaTest is unit too in the sandbox, so this is still RED for unit...
# Use the orphan-free sandbox: only Alpha+Beta are unit. Drop Beta, unit
# verify must still name it; that proves --source-set unit is not a no-op
# that always passes.
if out="$(run_guard --verify --ledger "$SANDBOX/ledger.tsv" --now "$NOW" --source-set unit 2>&1)"; then
  bad "23 --source-set unit did not report the missing unit class:\n$out"
elif grep -q 'NEVER executed' <<<"$out" && grep -q 'BetaTest' <<<"$out"; then
  ok "23 --source-set unit still reddens a missing unit class (not a silent pass)"
else
  bad "23 --source-set unit failed for the wrong reason:\n$out"
fi

# ===========================================================================
# CASES 24-28 — selected set must match the artifact the job can emit.
#
# Round-1 case 24 grepped the two pin FQCNs out of `--report`. `--report`
# never fails on the other selected classes, so a pin-only XML stayed green
# while nightly-phase1 selected 21 shared-module androidTest FQCNs that
# :app:connectedDebugAndroidTest cannot emit. That is G6: the green thing
# was not the load-bearing selected-vs-executed check.
# ===========================================================================
SELECT="${SELECT:-$SCRIPT_DIR/select-test-areas.sh}"
PIN_COLD="com.pocketshell.app.proof.ColdInstallE2eTest"
PIN_WORKFLOW="com.pocketshell.app.proof.EmulatorWorkflowE2eTest"
SHARED_ANDROIDTEST="com.pocketshell.uikit.components.UiKitPrimitivesTest"
RELEASE_ONLY="com.pocketshell.core.connection.ConnectionControllerConfinementDefaultReleaseTest"
DEBUG_ONLY="com.pocketshell.core.connection.ConnectionControllerConfinementDefaultDebugTest"
REAL_LLM_ONLY="com.pocketshell.app.assistant.AssistantAgentLoopRealLlmTest"

write_junit_from_list() {
  local dest="$1" list="$2" n
  n=$(grep -cve '^$' "$list" || true)
  {
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo "<testsuite name=\"union\" tests=\"$n\">"
    while IFS= read -r cls; do
      [[ -z "$cls" ]] && continue
      printf '  <testcase name="t" classname="%s" time="0.10"/>\n' "$cls"
    done < "$list"
    echo '</testsuite>'
  } > "$dest"
}

write_asserted_union_tsv() {
  local dest="$1" list="$2"
  {
    echo "# pocketshell-test-run-attendance v1"
    echo "identity.workflow	selftest-app-union"
    while IFS= read -r cls; do
      [[ -z "$cls" ]] && continue
      printf 'class\t%s\tasserted\n' "$cls"
    done < "$list"
  } > "$dest"
}

# Independent app-module union: taxonomy :app + androidTest, plus the
# documented nightly-connected unconventional rows (already
# app/src/androidTest/). NOT derived from --selected-from nightly-phase1.
APP_UNION="$SANDBOX/app-androidtest-union.txt"
{
  bash "$SELECT" --list-classes 2>/dev/null |
    awk -F'\t' '$4=="androidTest" && $3==":app" {print $1}'
  awk -F'\t' '
    $1 ~ /^#/ || $1 == "" { next }
    $2 == "nightly-connected" && $1 ~ /^app\/src\/androidTest\// {
      p=$1
      sub(/^app\/src\/androidTest\//, "", p)
      sub(/^java\//, "", p)
      sub(/^kotlin\//, "", p)
      sub(/\.kt$/, "", p)
      sub(/\.java$/, "", p)
      gsub(/\//, ".", p)
      print p
    }
  ' "$SCRIPT_DIR/test-unconventional-test-files.txt"
} | LC_ALL=C sort -u > "$APP_UNION"
app_union_n=$(grep -cve '^$' "$APP_UNION" || true)

# CASE 24 — complete app-module union PASSES merge-attendance against
# --selected-from nightly-phase1 WITHOUT --report. The two pins must be
# asserted. If nightly-phase1 still selected shared-module androidTest
# classes, those 21 would be missing and this would RED.
APP_MERGE="$SANDBOX/app-union-merge"
mkdir -p "$APP_MERGE"
write_asserted_union_tsv "$APP_MERGE/phase1-attendance.tsv" "$APP_UNION"
if [[ "$app_union_n" -lt 50 ]]; then
  bad "24 independent app androidTest union is implausibly small ($app_union_n)"
elif out="$(bash "$GUARD" --merge-attendance "$APP_MERGE" \
            --selected-from nightly-phase1 \
            --require-class "$PIN_COLD" \
            --require-class "$PIN_WORKFLOW" \
            --out "$SANDBOX/app-union-merged.tsv" 2>&1)"; then
  if grep -q 'missing_class_count	0' "$SANDBOX/app-union-merged.tsv" &&
     grep -q "OK: required class $PIN_COLD is asserted/load-bearing" <<<"$out" &&
     grep -q "OK: required class $PIN_WORKFLOW is asserted/load-bearing" <<<"$out"; then
    ok "24 complete app-module union passes merge-attendance without --report; pins are asserted"
  else
    bad "24 merge passed but the report is missing the zero-missing / pin proof:\n$out\n$(cat "$SANDBOX/app-union-merged.tsv")"
  fi
else
  bad "24 complete app-module union should pass merge-attendance (nightly-phase1 too wide?):\n$out"
fi

# CASE 24b — --print-selected nightly-phase1 contains no FQCN outside the
# independent app union. Fails without --report if selected-from still
# includes a shared-module androidTest class.
NIGHTLY_SEL="$SANDBOX/nightly-phase1-selected.txt"
if ! bash "$GUARD" --print-selected --selected-from nightly-phase1 \
      > "$NIGHTLY_SEL" 2>"$SANDBOX/nightly-sel.err"; then
  bad "24b --print-selected nightly-phase1 failed:\n$(cat "$SANDBOX/nightly-sel.err")"
else
  extra_n=0
  extra_sample=""
  while IFS= read -r cls; do
    [[ -z "$cls" ]] && continue
    if ! grep -qxF "$cls" "$APP_UNION"; then
      extra_n=$((extra_n + 1))
      extra_sample="${extra_sample}${cls}"$'\n'
    fi
  done < "$NIGHTLY_SEL"
  if [[ "$extra_n" -eq 0 ]] && grep -qxF "$PIN_COLD" "$NIGHTLY_SEL" &&
     grep -qxF "$PIN_WORKFLOW" "$NIGHTLY_SEL" &&
     ! grep -qxF "$SHARED_ANDROIDTEST" "$NIGHTLY_SEL"; then
    ok "24b nightly-phase1 selected set is a subset of the app-module union (no shared androidTest FQCN)"
  else
    bad "24b nightly-phase1 selected set contains $extra_n non-app class(es):\n${extra_sample}"
  fi
fi

# CASE 25 — adding a shared/*/src/androidTest FQCN to the selected set REDS
# merge-attendance WITHOUT --report. Proves case 24 is the comparison of
# selector vs artifact, not a pin grep.
WIDE_SEL="$SANDBOX/nightly-plus-shared.txt"
{ cat "$NIGHTLY_SEL"; echo "$SHARED_ANDROIDTEST"; } | LC_ALL=C sort -u > "$WIDE_SEL"
if out="$(bash "$GUARD" --merge-attendance "$APP_MERGE" \
          --selected-file "$WIDE_SEL" 2>&1)"; then
  bad "25 adding $SHARED_ANDROIDTEST to the selected set did NOT redden merge-attendance:\n$out"
elif grep -q 'produced NO result' <<<"$out" && grep -q "$SHARED_ANDROIDTEST" <<<"$out"; then
  ok "25 adding a shared androidTest FQCN reddens merge-attendance without --report, naming the class"
else
  bad "25 wide selected set failed for the wrong reason:\n$out"
fi

# CASE 26 — Debug-shaped full class list + unscoped --selected-from unit
# is NOT acceptance. testDebugUnitTest cannot emit the testRelease class.
DEBUG_LIST="$SANDBOX/unit-debug-classes.txt"
bash "$SELECT" --list-classes 2>/dev/null |
  awk -F'\t' -v opt_in="$REAL_LLM_ONLY" \
    '($4=="test" || $4=="testDebug") && $1 != opt_in {print $1}' |
  LC_ALL=C sort -u > "$DEBUG_LIST"
DEBUG_XML_DIR="$SANDBOX/unit-debug-xml"
mkdir -p "$DEBUG_XML_DIR"
write_junit_from_list "$DEBUG_XML_DIR/TEST-debug.xml" "$DEBUG_LIST"
if out="$(bash "$GUARD" --attendance --results-root "$DEBUG_XML_DIR" \
          --selected-from unit 2>&1)"; then
  bad "26 Debug-shaped XML + unscoped --selected-from unit passed — that is the round-1 hole:\n$out"
elif grep -q 'produced NO result' <<<"$out" && grep -q "$RELEASE_ONLY" <<<"$out"; then
  ok "26 Debug-shaped XML + unscoped --selected-from unit reddens, naming the testRelease class (not acceptance)"
else
  bad "26 unscoped unit vs Debug XML failed for the wrong reason:\n$out"
fi

# CASE 27 — an independently Gradle-shaped Debug artifact +
# --selected-from unit-debug PASSES. The artifact list above deliberately
# models app/build.gradle.kts's ordinary-task RealLlmTest exclusion instead of
# being copied from this guard's selected set. Both the Release-only and the
# opt-in real-LLM FQCN must therefore be absent from the selected set.
if ! bash "$GUARD" --print-selected --selected-from unit-debug \
      > "$SANDBOX/unit-debug-selected.txt" 2>"$SANDBOX/unit-debug-sel.err"; then
  bad "27 --print-selected unit-debug failed:\n$(cat "$SANDBOX/unit-debug-sel.err")"
elif grep -qxF "$RELEASE_ONLY" "$SANDBOX/unit-debug-selected.txt"; then
  bad "27 unit-debug selected set still contains $RELEASE_ONLY"
elif grep -qxF "$REAL_LLM_ONLY" "$SANDBOX/unit-debug-selected.txt"; then
  bad "27 unit-debug selected set still contains opt-in-only $REAL_LLM_ONLY"
elif ! grep -qxF "$DEBUG_ONLY" "$SANDBOX/unit-debug-selected.txt"; then
  bad "27 unit-debug selected set dropped $DEBUG_ONLY"
elif out="$(bash "$GUARD" --attendance --results-root "$DEBUG_XML_DIR" \
            --selected-from unit-debug 2>&1)"; then
  ok "27 unit-debug attendance matches an independent Gradle-shaped artifact (no Release-only or opt-in real-LLM FQCN)"
else
  bad "27 unit-debug attendance against a complete Debug-shaped list should pass:\n$out"
fi

# CASE 28 — Release-scoped selected set includes the testRelease class but not
# the opt-in real-LLM class; dropping the ordinary testRelease class from the
# independently Gradle-shaped artifact still REDS.
RELEASE_LIST="$SANDBOX/unit-release-classes.txt"
bash "$SELECT" --list-classes 2>/dev/null |
  awk -F'\t' -v opt_in="$REAL_LLM_ONLY" \
    '($4=="test" || $4=="testRelease") && $1 != opt_in {print $1}' |
  LC_ALL=C sort -u > "$RELEASE_LIST"
if ! bash "$GUARD" --print-selected --selected-from unit-release \
      > "$SANDBOX/unit-release-selected.txt" 2>"$SANDBOX/unit-release-sel.err"; then
  bad "28 --print-selected unit-release failed:\n$(cat "$SANDBOX/unit-release-sel.err")"
elif ! grep -qxF "$RELEASE_ONLY" "$SANDBOX/unit-release-selected.txt"; then
  bad "28 unit-release selected set dropped $RELEASE_ONLY"
elif grep -qxF "$DEBUG_ONLY" "$SANDBOX/unit-release-selected.txt"; then
  bad "28 unit-release selected set still contains $DEBUG_ONLY"
elif grep -qxF "$REAL_LLM_ONLY" "$SANDBOX/unit-release-selected.txt"; then
  bad "28 unit-release selected set still contains opt-in-only $REAL_LLM_ONLY"
else
  RELEASE_XML_DIR="$SANDBOX/unit-release-xml"
  mkdir -p "$RELEASE_XML_DIR"
  write_junit_from_list "$RELEASE_XML_DIR/TEST-release.xml" "$RELEASE_LIST"
  if ! out="$(bash "$GUARD" --attendance --results-root "$RELEASE_XML_DIR" \
              --selected-from unit-release 2>&1)"; then
    bad "28 complete Release-shaped XML + unit-release should pass:\n$out"
  else
    grep -vxF "$RELEASE_ONLY" "$RELEASE_LIST" > "$SANDBOX/unit-release-minus.txt"
    write_junit_from_list "$RELEASE_XML_DIR/TEST-release.xml" "$SANDBOX/unit-release-minus.txt"
    if out="$(bash "$GUARD" --attendance --results-root "$RELEASE_XML_DIR" \
              --selected-from unit-release 2>&1)"; then
      bad "28 dropping $RELEASE_ONLY from a Release-shaped artifact did NOT redden:\n$out"
    elif grep -q 'produced NO result' <<<"$out" && grep -q "$RELEASE_ONLY" <<<"$out"; then
      ok "28 Release-scoped attendance passes a complete Release list and reddens when the testRelease class is dropped"
    else
      bad "28 Release-scoped drop failed for the wrong reason:\n$out"
    fi
  fi
fi

# CASE 29 — preserving the opt-in lane means excluding RealLlmTest only from
# ordinary attendance, not deleting it from the taxonomy or its dedicated
# Gradle task. This pins both halves of that contract.
UNIT_SELECTED="$SANDBOX/unit-selected.txt"
if ! bash "$GUARD" --print-selected --selected-from unit > "$UNIT_SELECTED"; then
  bad "29 unscoped ordinary-unit selected set could not be produced"
elif ! grep -qF "${REAL_LLM_ONLY}"$'\t' "$REAL_LEDGER"; then
  bad "29 opt-in real-LLM class disappeared from the registered taxonomy"
elif ! grep -qF 'test.exclude("**/*RealLlmTest.class")' "$SCRIPT_DIR/../app/build.gradle.kts"; then
  bad "29 app ordinary Gradle tasks no longer exclude RealLlmTest"
elif ! grep -qF 'include("**/*RealLlmTest.class")' "$SCRIPT_DIR/../app/build.gradle.kts"; then
  bad "29 :app:realLlmTest no longer includes RealLlmTest"
elif grep -qxF "$REAL_LLM_ONLY" "$UNIT_SELECTED"; then
  bad "29 unscoped ordinary-unit selected set still contains $REAL_LLM_ONLY"
else
  grep -v "^${REAL_LLM_ONLY}"$'\t' "$REAL_LEDGER" > "$SANDBOX/real-ledger-minus-real-llm.tsv"
  if out="$(bash "$GUARD" --verify \
            --ledger "$SANDBOX/real-ledger-minus-real-llm.tsv" \
            --source-set unit --now "$REAL_NOW" 2>&1)"; then
    bad "29 generic unit ledger verification stopped requiring opt-in $REAL_LLM_ONLY:\n$out"
  elif grep -q 'NEVER executed' <<<"$out" && grep -qF "$REAL_LLM_ONLY" <<<"$out"; then
    ok "29 RealLlmTest remains registered in generic rolling verification and opt-in while ordinary variants exclude it"
  else
    bad "29 generic unit rolling-ledger registration failed for the wrong reason:\n$out"
  fi
fi

# CASE 30 — LIVE selector mutation: remove the shipped exclusion and run the
# independently Gradle-shaped Debug artifact. Attendance must RED on exactly
# the opt-in class. This is the defect from PR #2230's Debug/Release jobs, and
# proves case 27 would not stay green if the production selector regressed.
UNIT_MUT_DIR="$SANDBOX/mut-unit-selector"
mkdir -p "$UNIT_MUT_DIR/lib"
cp "$GUARD" "$UNIT_MUT_DIR/check-test-execution-ledger.sh"
cp "$SCRIPT_DIR/lib/test-areas.sh" "$UNIT_MUT_DIR/lib/test-areas.sh"
clean_guard_md5="$(md5sum "$UNIT_MUT_DIR/check-test-execution-ledger.sh" | cut -d' ' -f1)"
python3 - "$UNIT_MUT_DIR/check-test-execution-ledger.sh" <<'PY'
import sys

p = sys.argv[1]
s = open(p).read()
anchor = '    ordinary_unit_class_selected "$fqcn" || continue\n'
assert s.count(anchor) == 1, "ordinary RealLlmTest exclusion anchor not unique"
s = s.replace(anchor, '    : # MUTANT 30: ordinary selector re-includes RealLlmTest\n')
open(p, "w").write(s)
PY
mut_guard_md5="$(md5sum "$UNIT_MUT_DIR/check-test-execution-ledger.sh" | cut -d' ' -f1)"
if [[ "$clean_guard_md5" == "$mut_guard_md5" ]] ||
   ! grep -q 'MUTANT 30' "$UNIT_MUT_DIR/check-test-execution-ledger.sh"; then
  bad "30 MUTATION DID NOT APPLY — the ordinary-selector mutant is not live"
else
  out="$(POCKETSHELL_TEST_AREAS_REPO_ROOT="$SCRIPT_DIR/.." \
          POCKETSHELL_TEST_AREAS_MANIFEST="$SCRIPT_DIR/test-areas.txt" \
          bash "$UNIT_MUT_DIR/check-test-execution-ledger.sh" \
            --attendance --results-root "$DEBUG_XML_DIR" \
            --selected-from unit-debug 2>&1)"
  if grep -q 'FAIL: 1 selected class(es) produced NO result' <<<"$out" &&
     grep -qF "$REAL_LLM_ONLY" <<<"$out"; then
    ok "30 live re-inclusion mutant reddens Debug attendance on exactly $REAL_LLM_ONLY"
  else
    bad "30 live re-inclusion mutant failed for the wrong reason:\n$out"
  fi
fi

# CASE 31 — selectivity in the other direction: excluding the opt-in class
# must not make attendance tolerant of an ordinary selected class disappearing.
GENERAL_UNIT="com.pocketshell.core.ssh.SshLeaseManagerTest"
DEBUG_MINUS_GENERAL="$SANDBOX/unit-debug-minus-general.txt"
if ! grep -qxF "$GENERAL_UNIT" "$DEBUG_LIST"; then
  bad "31 independent Debug artifact does not contain $GENERAL_UNIT; missing-class mutation cannot run"
else
  grep -vxF "$GENERAL_UNIT" "$DEBUG_LIST" > "$DEBUG_MINUS_GENERAL"
  write_junit_from_list "$DEBUG_XML_DIR/TEST-debug.xml" "$DEBUG_MINUS_GENERAL"
  if out="$(bash "$GUARD" --attendance --results-root "$DEBUG_XML_DIR" \
            --selected-from unit-debug 2>&1)"; then
    bad "31 dropping ordinary selected class $GENERAL_UNIT did NOT redden attendance:\n$out"
  elif grep -q 'FAIL: 1 selected class(es) produced NO result' <<<"$out" &&
       grep -qF "$GENERAL_UNIT" <<<"$out"; then
    ok "31 a generally missing ordinary selected class still reddens attendance, naming only that class"
  else
    bad "31 ordinary missing-class mutation failed for the wrong reason:\n$out"
  fi
fi

run_unit_wrapper_sequence() {
  local guard="$1" results="$2" selected_from="$3" source_set="$4" ledger="$5"
  POCKETSHELL_TEST_AREAS_REPO_ROOT="$SCRIPT_DIR/.." \
    POCKETSHELL_TEST_AREAS_MANIFEST="$SCRIPT_DIR/test-areas.txt" \
    bash "$guard" --record "$results" --ledger "$ledger" \
      --tier unit --now "$REAL_NOW" &&
  POCKETSHELL_TEST_AREAS_REPO_ROOT="$SCRIPT_DIR/.." \
    POCKETSHELL_TEST_AREAS_MANIFEST="$SCRIPT_DIR/test-areas.txt" \
    bash "$guard" --attendance --results-root "$results" \
      --selected-from "$selected_from" &&
  POCKETSHELL_TEST_AREAS_REPO_ROOT="$SCRIPT_DIR/.." \
    POCKETSHELL_TEST_AREAS_MANIFEST="$SCRIPT_DIR/test-areas.txt" \
    bash "$guard" --verify --ledger "$ledger" \
      --source-set "$source_set" --now "$REAL_NOW"
}

# CASES 32-33 — artifact-shaped proof of the ENTIRE ordinary-unit wrapper
# sequence, not only attendance. Rebuild the complete independent artifacts
# because cases 28 and 31 deliberately removed one ordinary class from each.
# The final --verify is the command the round-4 review proved was still red.
write_junit_from_list "$DEBUG_XML_DIR/TEST-debug.xml" "$DEBUG_LIST"
DEBUG_WRAPPER_LEDGER="$SANDBOX/unit-debug-wrapper-ledger.tsv"
if out="$(run_unit_wrapper_sequence "$GUARD" "$DEBUG_XML_DIR" unit-debug \
          unit-debug "$DEBUG_WRAPPER_LEDGER" 2>&1)"; then
  ok "32 Debug artifact passes the real record -> attendance -> variant verify sequence"
else
  bad "32 complete Debug artifact failed the real wrapper sequence:\n$out"
fi

write_junit_from_list "$RELEASE_XML_DIR/TEST-release.xml" "$RELEASE_LIST"
RELEASE_WRAPPER_LEDGER="$SANDBOX/unit-release-wrapper-ledger.tsv"
if out="$(run_unit_wrapper_sequence "$GUARD" "$RELEASE_XML_DIR" unit-release \
          unit-release "$RELEASE_WRAPPER_LEDGER" 2>&1)"; then
  ok "33 Release artifact passes the real record -> attendance -> variant verify sequence"
else
  bad "33 complete Release artifact failed the real wrapper sequence:\n$out"
fi

# CASE 34 — LIVE verify-path mutation. Keep selected_from_unit() intact so
# attendance stays green, but bypass the ordinary-task exclusion only in the
# final --verify source-set filter. The whole sequence must then RED on exactly
# the opt-in class, proving cases 32-33 constrain the reviewer's actual seam.
VERIFY_MUT_DIR="$SANDBOX/mut-unit-verify"
mkdir -p "$VERIFY_MUT_DIR/lib"
cp "$GUARD" "$VERIFY_MUT_DIR/check-test-execution-ledger.sh"
cp "$SCRIPT_DIR/lib/test-areas.sh" "$VERIFY_MUT_DIR/lib/test-areas.sh"
clean_verify_md5="$(md5sum "$VERIFY_MUT_DIR/check-test-execution-ledger.sh" | cut -d' ' -f1)"
python3 - "$VERIFY_MUT_DIR/check-test-execution-ledger.sh" <<'PY'
import sys

p = sys.argv[1]
s = open(p).read()
anchor = '      unit-debug) is_unit_debug_source_set "$srcset" && ordinary_unit_class_selected "$fqcn" && return 0 ;;\n'
assert s.count(anchor) == 1, "variant verify exclusion anchor not unique"
s = s.replace(
    anchor,
    '      unit-debug) is_unit_debug_source_set "$srcset" && return 0 ;; # MUTANT 34: verify re-includes RealLlmTest\n',
)
open(p, "w").write(s)
PY
mut_verify_md5="$(md5sum "$VERIFY_MUT_DIR/check-test-execution-ledger.sh" | cut -d' ' -f1)"
VERIFY_MUT_LEDGER="$SANDBOX/unit-debug-verify-mutant-ledger.tsv"
if [[ "$clean_verify_md5" == "$mut_verify_md5" ]] ||
   ! grep -q 'MUTANT 34' "$VERIFY_MUT_DIR/check-test-execution-ledger.sh"; then
  bad "34 MUTATION DID NOT APPLY — the variant-verify mutant is not live"
else
  out="$(run_unit_wrapper_sequence \
          "$VERIFY_MUT_DIR/check-test-execution-ledger.sh" \
          "$DEBUG_XML_DIR" unit-debug unit-debug "$VERIFY_MUT_LEDGER" 2>&1)"
  if grep -q 'PASS: every selected class produced a result' <<<"$out" &&
     grep -q 'FAIL: 1 registered class(es) have NEVER executed' <<<"$out" &&
     grep -qF "$REAL_LLM_ONLY" <<<"$out"; then
    ok "34 live verify-only drift mutant reddens after green attendance on exactly $REAL_LLM_ONLY"
  else
    bad "34 live verify-only drift mutant failed for the wrong reason:\n$out"
  fi
fi

# CASE 35 — generic missing-class failure remains load-bearing for the full
# wrapper path. With the shipped exclusion intact, remove one ordinary class
# from the independent artifact and require the attendance command to stop the
# sequence on exactly that class.
write_junit_from_list "$DEBUG_XML_DIR/TEST-debug.xml" "$DEBUG_MINUS_GENERAL"
MISSING_WRAPPER_LEDGER="$SANDBOX/unit-debug-missing-wrapper-ledger.tsv"
if out="$(run_unit_wrapper_sequence "$GUARD" "$DEBUG_XML_DIR" unit-debug \
          unit-debug "$MISSING_WRAPPER_LEDGER" 2>&1)"; then
  bad "35 wrapper sequence tolerated missing ordinary class $GENERAL_UNIT:\n$out"
elif grep -q 'FAIL: 1 selected class(es) produced NO result' <<<"$out" &&
     grep -qF "$GENERAL_UNIT" <<<"$out" &&
     ! grep -qF "$REAL_LLM_ONLY" <<<"$out"; then
  ok "35 full wrapper sequence still reddens on exactly one missing ordinary selected class"
else
  bad "35 wrapper missing-class mutation failed for the wrong reason:\n$out"
fi

echo
echo "check-test-execution-ledger selftest: $PASS passed, $FAIL failed"
[[ "$FAIL" -eq 0 ]] || exit 1
exit 0
