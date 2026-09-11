#!/usr/bin/env bash
# Self-test for scripts/select-test-areas.sh and scripts/lib/test-areas.sh (#2063).
#
# WHAT THIS PROVES, AND WHY EACH CASE EXISTS
#
# The selection engine's one safety property is that its failure mode points at
# running MORE, never less. A guard whose negative case was never exercised is
# decoration (process.md, G6), so every case below MUTATES the input — deletes a
# rule, empties the manifest, invents an unmapped path — and asserts the answer
# moves the safe way. Nothing here asserts "it still says full" over an input
# that was already full for another reason.
#
# The sandbox is a synthetic mini-repo with its own manifest, so the cases stay
# readable and cannot be perturbed by real-tree churn. The real tree is covered
# by --verify-manifest / --coverage-invariant, which this script also runs last.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SELECT="$SCRIPT_DIR/select-test-areas.sh"

SANDBOX="$(mktemp -d)"
# #2170 plants a tracked non-source under a real test source set via a private
# index copy so the real worktree index is never dirtied. Working-tree files
# still have to exist (the guard greps them for @Test) and must be removed.
ISSUE2170_INDEX=""
ISSUE2170_PLANTS=()
cleanup() {
  local p
  for p in "${ISSUE2170_PLANTS[@]:-}"; do
    rm -f "$SCRIPT_DIR/../$p"
  done
  [[ -n "$ISSUE2170_INDEX" ]] && rm -f "$ISSUE2170_INDEX"
  rm -rf "$SANDBOX"
}
trap cleanup EXIT

PASS=0
FAIL=0

ok()   { PASS=$((PASS + 1)); printf 'ok   %s\n' "$*"; }
bad()  { FAIL=$((FAIL + 1)); printf 'FAIL %s\n' "$*"; }

# ---------------------------------------------------------------------------
# Synthetic repo
# ---------------------------------------------------------------------------
mkdir -p \
  "$SANDBOX/scripts/lib" \
  "$SANDBOX/app/src/main/java/com/pocketshell/app/alpha" \
  "$SANDBOX/app/src/main/java/com/pocketshell/app/beta" \
  "$SANDBOX/app/src/main/java/com/pocketshell/app/di" \
  "$SANDBOX/app/src/test/java/com/pocketshell/app/alpha" \
  "$SANDBOX/app/src/test/java/com/pocketshell/app/beta" \
  "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/proof" \
  "$SANDBOX/docs"

cp "$SCRIPT_DIR/lib/test-areas.sh" "$SANDBOX/scripts/lib/test-areas.sh"
cp "$SELECT" "$SANDBOX/scripts/select-test-areas.sh"

cat > "$SANDBOX/app/src/main/java/com/pocketshell/app/alpha/Alpha.kt" <<'KT'
package com.pocketshell.app.alpha
KT
cat > "$SANDBOX/app/src/main/java/com/pocketshell/app/beta/Beta.kt" <<'KT'
package com.pocketshell.app.beta
KT
cat > "$SANDBOX/app/src/main/java/com/pocketshell/app/di/Module.kt" <<'KT'
package com.pocketshell.app.di
KT
cat > "$SANDBOX/app/src/test/java/com/pocketshell/app/alpha/AlphaTest.kt" <<'KT'
package com.pocketshell.app.alpha
class AlphaTest
KT
cat > "$SANDBOX/app/src/test/java/com/pocketshell/app/beta/BetaTest.kt" <<'KT'
package com.pocketshell.app.beta
class BetaTest
KT
cat > "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/proof/AlwaysE2eTest.kt" <<'KT'
package com.pocketshell.app.proof
class AlwaysE2eTest {
  @Test fun always() {}
}
KT
mkdir -p "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/beta"
cat > "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/beta/BetaJourneyTest.kt" <<'KT'
package com.pocketshell.app.beta
class BetaJourneyTest {
  @Test fun beta() {}
}
KT
cat > "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/proof/SharedFixture.kt" <<'KT'
package com.pocketshell.app.proof
object SharedFixture
KT
echo "# docs" > "$SANDBOX/docs/notes.md"

# The journey registry is DERIVED from the module the suite runs wholesale
# (#2474), so the sandbox's suite carries the same shape the real one does: a
# JOURNEY_TASK and a gradle_args() with no class filter in it. The registry is
# then whatever @Test-bearing test classes live under that module's androidTest
# tree — here AlwaysE2eTest (pcore, always-tier) and BetaJourneyTest (beta).
cat > "$SANDBOX/scripts/ci-app2-journey-suite.sh" <<'SH'
#!/usr/bin/env bash
JOURNEY_TASK=":app:connectedDebugAndroidTest"
gradle_args() {
  printf '%s\n' "$JOURNEY_TASK" "--no-daemon"
}
SH

BASE_MANIFEST="$SANDBOX/scripts/test-areas.txt"
cat > "$BASE_MANIFEST" <<'MF'
area   pcore   always   always-on core area
area   alpha   changed  alpha area
area   beta    changed  beta area

full   scripts/*                                          harnesses
full   app/src/main/java/com/pocketshell/app/di/*          DI graph

noop   docs/*                                             prose
noop   *.md                                               prose

src    app/src/main/java/com/pocketshell/app/alpha/*       alpha   ui
src    app/src/main/java/com/pocketshell/app/beta/*        beta    full

test   app/src/*/java/com/pocketshell/app/proof/*          pcore   full
test   app/src/*/java/com/pocketshell/app/alpha/*          alpha   full
test   app/src/*/java/com/pocketshell/app/beta/*           beta    full

couple alpha  beta
MF

git -C "$SANDBOX" init -q 2>/dev/null
git -C "$SANDBOX" add -A >/dev/null 2>&1
git -C "$SANDBOX" -c user.email=t@t -c user.name=t commit -qm init >/dev/null 2>&1

run_select() {
  # $1 = manifest, rest = changed paths
  local manifest="$1"; shift
  printf '%s\n' "$@" |
    POCKETSHELL_TEST_AREAS_REPO_ROOT="$SANDBOX" \
    POCKETSHELL_TEST_AREAS_MANIFEST="$manifest" \
    POCKETSHELL_TEST_AREAS_JOURNEY_SUITE="$SANDBOX/scripts/ci-app2-journey-suite.sh" \
    POCKETSHELL_TEST_AREAS_APP_MODULE=":app" \
    bash "$SANDBOX/scripts/select-test-areas.sh" --changed-stdin --print-plan-only 2>&1
}

field() { sed -n "s/^$2=//p" <<<"$1" | head -1; }

# ===========================================================================
# CASE 1 (baseline, so later mutations are meaningful): a mapped path scopes.
# ===========================================================================
out="$(run_select "$BASE_MANIFEST" "app/src/main/java/com/pocketshell/app/alpha/Alpha.kt")"
if [[ "$(field "$out" MODE)" == "scoped" ]] &&
   [[ "$(field "$out" AREAS)" == "alpha beta pcore" ]]; then
  ok "1 mapped alpha path scopes to alpha + its couple + the always tier"
else
  bad "1 expected scoped 'alpha beta pcore', got MODE=$(field "$out" MODE) AREAS=$(field "$out" AREAS)"
fi

# ===========================================================================
# CASE 2 — THE fail-safe: an UNMAPPED path forces full AND is reported.
#
# Mutation: a path no rule mentions. The bug this catches is the one the issue
# names explicitly — "no area matched, run nothing".
# ===========================================================================
out="$(run_select "$BASE_MANIFEST" "app/src/main/java/com/pocketshell/app/brandnew/New.kt")"
if [[ "$(field "$out" MODE)" == "full" ]] && [[ "$(field "$out" UNMAPPED_COUNT)" == "1" ]]; then
  ok "2 unmapped path => MODE=full and UNMAPPED_COUNT=1 (loud, not silent)"
else
  bad "2 unmapped path did not fail safe: MODE=$(field "$out" MODE) UNMAPPED_COUNT=$(field "$out" UNMAPPED_COUNT)"
fi

# ===========================================================================
# CASE 3 — deleting the rule that made a path safe must UN-scope it.
#
# This is the selectivity check: case 1 must depend on the alpha src rule.
# ===========================================================================
mut="$SANDBOX/mut-no-alpha.txt"
grep -v 'app/pocketshell/app/alpha' "$BASE_MANIFEST" |
  grep -v 'src    app/src/main/java/com/pocketshell/app/alpha' > "$mut"
out="$(run_select "$mut" "app/src/main/java/com/pocketshell/app/alpha/Alpha.kt")"
if [[ "$(field "$out" MODE)" == "full" ]]; then
  ok "3 deleting the alpha src rule turns the same path into a full run"
else
  bad "3 alpha path still scoped with its rule deleted: MODE=$(field "$out" MODE)"
fi

# ===========================================================================
# CASE 4 — an EMPTY manifest forces full. A load failure must never read as
# "no rules, therefore nothing to run".
# ===========================================================================
: > "$SANDBOX/mut-empty.txt"
out="$(run_select "$SANDBOX/mut-empty.txt" "app/src/main/java/com/pocketshell/app/alpha/Alpha.kt")"
if [[ "$(field "$out" MODE)" == "full" ]]; then
  ok "4 empty manifest => MODE=full"
else
  bad "4 empty manifest did not force full: MODE=$(field "$out" MODE)"
fi

# ===========================================================================
# CASE 5 — a MISSING manifest forces full.
# ===========================================================================
out="$(run_select "$SANDBOX/does-not-exist.txt" "app/src/main/java/com/pocketshell/app/alpha/Alpha.kt")"
if [[ "$(field "$out" MODE)" == "full" ]]; then
  ok "5 missing manifest => MODE=full"
else
  bad "5 missing manifest did not force full: MODE=$(field "$out" MODE)"
fi

# ===========================================================================
# CASE 6 — an EMPTY diff forces full. "Nothing changed" is indistinguishable
# from "the diff could not be computed", and only one of those is safe to skip.
# ===========================================================================
out="$(printf '' |
  POCKETSHELL_TEST_AREAS_REPO_ROOT="$SANDBOX" \
  POCKETSHELL_TEST_AREAS_MANIFEST="$BASE_MANIFEST" \
  POCKETSHELL_TEST_AREAS_JOURNEY_SUITE="$SANDBOX/scripts/ci-app2-journey-suite.sh" \
    POCKETSHELL_TEST_AREAS_APP_MODULE=":app" \
  bash "$SANDBOX/scripts/select-test-areas.sh" --changed-stdin --print-plan-only 2>&1)"
if [[ "$(field "$out" MODE)" == "full" ]]; then
  ok "6 empty changed-path set => MODE=full"
else
  bad "6 empty diff did not force full: MODE=$(field "$out" MODE)"
fi

# ===========================================================================
# CASE 7 — the always tier is a FLOOR: a noop-only diff is scoped, non-empty,
# and contains the always-tier area and its journeys.
# ===========================================================================
out="$(run_select "$BASE_MANIFEST" "docs/notes.md")"
if [[ "$(field "$out" MODE)" == "scoped" ]] &&
   [[ "$(field "$out" AREAS)" == "pcore" ]] &&
   [[ "$(field "$out" JOURNEY_CLASSES)" == "com.pocketshell.app.proof.AlwaysE2eTest" ]]; then
  ok "7 docs-only diff still runs the always tier (floor is non-empty)"
else
  bad "7 docs-only floor wrong: MODE=$(field "$out" MODE) AREAS=$(field "$out" AREAS) J=$(field "$out" JOURNEY_CLASSES)"
fi

# ===========================================================================
# CASE 8 — removing the `always` tier from the floor area must SHRINK the
# docs-only selection to nothing-but-itself. Proves case 7's floor is produced
# by the tier, not by an accident of the couple graph.
# ===========================================================================
sed 's/^area   pcore   always/area   pcore   changed/' "$BASE_MANIFEST" > "$SANDBOX/mut-no-always.txt"
out="$(run_select "$SANDBOX/mut-no-always.txt" "docs/notes.md")"
if [[ "$(field "$out" AREAS)" == "" ]]; then
  ok "8 demoting the always tier empties the docs-only selection (the floor is the tier)"
else
  bad "8 expected an empty selection after demoting always, got AREAS=$(field "$out" AREAS)"
fi

# ===========================================================================
# CASE 9 — force-full rows win over area rows, and a force-full path selects
# EVERY area (not just its own).
# ===========================================================================
out="$(run_select "$BASE_MANIFEST" "app/src/main/java/com/pocketshell/app/di/Module.kt")"
if [[ "$(field "$out" MODE)" == "full" ]] &&
   [[ "$(field "$out" AREAS)" == "alpha beta pcore" ]] &&
   [[ "$(field "$out" UNIT_MODE)" == "full" ]] &&
   [[ "$(field "$out" UNIT_GRADLE_TASKS)" == "test" ]]; then
  ok "9 a DI change forces full: all areas, whole-graph 'test' task, no --tests filter"
else
  bad "9 DI force-full wrong: MODE=$(field "$out" MODE) AREAS=$(field "$out" AREAS) UNIT=$(field "$out" UNIT_MODE)/$(field "$out" UNIT_GRADLE_TASKS)"
fi

# ===========================================================================
# CASE 10 — test INFRASTRUCTURE (a non-*Test file inside a test source set)
# forces full, while a sibling *Test.kt in the same directory only scopes.
# Both directions, because either alone would pass with the rule inverted.
# ===========================================================================
out_fixture="$(run_select "$BASE_MANIFEST" "app/src/androidTest/java/com/pocketshell/app/proof/SharedFixture.kt")"
out_test="$(run_select "$BASE_MANIFEST" "app/src/androidTest/java/com/pocketshell/app/proof/AlwaysE2eTest.kt")"
if [[ "$(field "$out_fixture" MODE)" == "full" ]] && [[ "$(field "$out_test" MODE)" == "scoped" ]]; then
  ok "10 a shared fixture forces full; its sibling *Test.kt in the same dir does not"
else
  bad "10 infra rule wrong: fixture=$(field "$out_fixture" MODE) test=$(field "$out_test" MODE)"
fi

# ===========================================================================
# CASE 11 — a `class` override beats the package glob.
# ===========================================================================
cat "$BASE_MANIFEST" > "$SANDBOX/mut-override.txt"
echo "class  com.pocketshell.app.beta.BetaJourneyTest  pcore" >> "$SANDBOX/mut-override.txt"
before="$(run_select "$BASE_MANIFEST" "docs/notes.md")"
after="$(run_select "$SANDBOX/mut-override.txt" "docs/notes.md")"
if [[ "$(field "$before" JOURNEY_CLASSES)" != *"com.pocketshell.app.beta.BetaJourneyTest"* ]] &&
   [[ "$(field "$after" JOURNEY_CLASSES)" == *"com.pocketshell.app.beta.BetaJourneyTest"* ]]; then
  ok "11 a class override moves BetaJourneyTest into the always tier (it now runs on a docs-only diff)"
else
  bad "11 class override had no effect: before=$(field "$before" JOURNEY_CLASSES) after=$(field "$after" JOURNEY_CLASSES)"
fi

# ===========================================================================
# CASE 12 — an undeclared area name in a rule is a LOAD error, which forces
# full. A typo must not silently create a ghost area whose tests nothing picks.
# ===========================================================================
sed 's/^src    app\/src\/main\/java\/com\/pocketshell\/app\/alpha\/\*       alpha   ui/src    app\/src\/main\/java\/com\/pocketshell\/app\/alpha\/*       alhpa   ui/' \
  "$BASE_MANIFEST" > "$SANDBOX/mut-typo.txt"
out="$(run_select "$SANDBOX/mut-typo.txt" "app/src/main/java/com/pocketshell/app/alpha/Alpha.kt")"
if [[ "$(field "$out" MODE)" == "full" ]]; then
  ok "12 a typo'd area name fails the load and forces full"
else
  bad "12 typo'd area name did not force full: MODE=$(field "$out" MODE)"
fi

# ===========================================================================
# CASE 13 — the real tree's own guards. These are the ones wired into CI; the
# synthetic cases above only prove the mechanism.
# ===========================================================================
if bash "$SELECT" --verify-manifest >/dev/null 2>&1; then
  ok "13a --verify-manifest passes on the real tree"
else
  bad "13a --verify-manifest FAILED on the real tree (run it for detail)"
fi
# 13b is deliberately the I5 FLOOR only, not the whole invariant set: the full
# --coverage-invariant already runs as its own @Test in SmartTestSelectionScriptTest,
# and re-running all ten here cost ~7s of duplicated work per Unit variant for a
# verdict CI already has. What 13b must carry is the GREEN half of case 15's
# mutation, which is I5.
if bash "$SELECT" --coverage-invariant --only I5 >/dev/null 2>&1; then
  ok "13b the I5 always-tier floor passes on the real tree (the green half of case 15)"
else
  bad "13b the I5 floor FAILED on the real tree (run --coverage-invariant for detail)"
fi

# ===========================================================================
# CASE 14 — the real --verify-manifest must be capable of RED. Introduce an
# unmapped path into a copy of the real manifest and assert the guard reddens.
# Without this, 13a is just "a guard that says OK".
# ===========================================================================
real_mut="$SANDBOX/real-manifest-mutant.txt"
grep -v '^src    shared/core-transport/\*' "$SCRIPT_DIR/test-areas.txt" |
  grep -v '^test   shared/core-transport/\*' > "$real_mut"
# Prove the mutation landed before reading a verdict out of it (#1641): the old
# version of this case named shared/core-ssh, a module the rewrite deleted, so
# it removed NOTHING and asserted a red the guard was producing for its own
# reasons.
if [[ "$(wc -l < "$real_mut")" -ge "$(wc -l < "$SCRIPT_DIR/test-areas.txt")" ]]; then
  bad "14 MUTATION DID NOT APPLY — no core-transport rule was removed, so 14's verdict would be meaningless"
fi
if POCKETSHELL_TEST_AREAS_MANIFEST="$real_mut" bash "$SELECT" --verify-manifest >/dev/null 2>&1; then
  bad "14 removing the core-transport rules did NOT redden --verify-manifest — the guard is decorative"
else
  ok "14 removing the core-transport rules reddens --verify-manifest (guard is live)"
fi

# ===========================================================================
# CASE 15 — the real --coverage-invariant must be capable of RED. Demote every
# always-tier area and assert I5's floor check fires.
# ===========================================================================
cov_mut="$SANDBOX/real-coverage-mutant.txt"
sed 's/^\(area   [a-z-]*  *\)always/\1changed/' "$SCRIPT_DIR/test-areas.txt" > "$cov_mut"
if POCKETSHELL_TEST_AREAS_MANIFEST="$cov_mut" bash "$SELECT" --coverage-invariant --only I5 >/dev/null 2>&1; then
  bad "15 demoting every always-tier area did NOT redden --coverage-invariant"
else
  ok "15 demoting every always-tier area reddens --coverage-invariant (I5 floor is live)"
fi

# ===========================================================================
# CASES 16-20 — the round-1 review findings, each with the mutation that must
# redden the check that now covers it.
#
# The reviewer found all four blockers by building its own oracle, which is the
# definition of a gap in these self-tests. Every case below names the mutation,
# applies it to a COPY inside this sandbox (never the tree), and asserts the
# specific check reddens.
# ===========================================================================

# A private copy of the scripts, so a code mutation can never touch the tree.
MUTDIR="$SANDBOX/mut-scripts"
mkdir -p "$MUTDIR/lib"
cp "$SCRIPT_DIR/select-test-areas.sh" "$MUTDIR/select-test-areas.sh"
cp "$SCRIPT_DIR/lib/test-areas.sh" "$MUTDIR/lib/test-areas.sh"
cp "$SCRIPT_DIR/test-areas.txt" "$MUTDIR/test-areas.txt"
cp "$SCRIPT_DIR/ci-app2-journey-suite.sh" "$MUTDIR/ci-app2-journey-suite.sh"
# The copies resolve their data relative to their own dir, so a copy that is not
# given this reads a MISSING exemption list and reddens for a reason that has
# nothing to do with the mutation under test (#2065).
cp "$SCRIPT_DIR/test-unconventional-test-files.txt" "$MUTDIR/test-unconventional-test-files.txt"

run_mut() {  # run the MUTATED copy against the REAL tree
  POCKETSHELL_TEST_AREAS_REPO_ROOT="$SCRIPT_DIR/.." \
  POCKETSHELL_TEST_AREAS_MANIFEST="${MUT_MANIFEST:-$MUTDIR/test-areas.txt}" \
  POCKETSHELL_TEST_AREAS_JOURNEY_SUITE="$MUTDIR/ci-app2-journey-suite.sh" \
  bash "$MUTDIR/select-test-areas.sh" "$@" 2>&1
}

# A runner-ready private copy of the scripts, for cases that mutate a SECOND
# copy while MUTDIR carries another mutation (kept: issue #2170 still uses it).
mut_copy() {  # $1 = dir name -> echoes a runner-ready dir
  local d="$SANDBOX/$1"
  mkdir -p "$d/lib"
  cp "$SCRIPT_DIR/select-test-areas.sh" "$d/select-test-areas.sh"
  cp "$SCRIPT_DIR/lib/test-areas.sh"    "$d/lib/test-areas.sh"
  cp "$SCRIPT_DIR/test-areas.txt"       "$d/test-areas.txt"
  cp "$SCRIPT_DIR/ci-app2-journey-suite.sh"  "$d/ci-app2-journey-suite.sh"
  # See the MUTDIR note: without these a copy fails on missing #2065 data
  # rather than on the mutation the case is about.
  cp "$SCRIPT_DIR/test-unconventional-test-files.txt" "$d/test-unconventional-test-files.txt"
  printf '%s\n' "$d"
}
run_copy() {  # $1 = dir, rest = args
  local d="$1"; shift
  POCKETSHELL_TEST_AREAS_REPO_ROOT="$SCRIPT_DIR/.." \
  POCKETSHELL_TEST_AREAS_MANIFEST="$d/test-areas.txt" \
  POCKETSHELL_TEST_AREAS_JOURNEY_SUITE="$d/ci-app2-journey-suite.sh" \
  bash "$d/select-test-areas.sh" "$@" 2>&1
}

# ---------------------------------------------------------------------------
# CASE 16 — a pinned host-CLI producer change still runs the wire tests.
#
# HISTORY (issue #2643): the host CLI moved to PocketShell-io/pocketshell-cli,
# and the seam machinery this case used to exercise — invoker/consumer/vocabu-
# lary marking, the per-end floors (16b), the lockstep-pin kill (16c), the
# reviewer's erosion replay (16d), the consumer-end code mutation (16e/16e-2),
# the unit-pin selectivity proofs (16f/16f-b) and the live-Click reader cases
# (16g-16l) — was deleted with the producer it coupled to (D22: the guard's
# subject left the repo).
#
# What REMAINS load-bearing here: the producer-side trigger inside this repo is
# now tests/docker/fixture-pins.txt (the pinned wheel the Docker fixtures
# install). It classifies force-full via the manifest's `full tests/docker/*`
# row, so bumping the pin runs EVERYTHING — every journey and unit class that
# reads the wire, plus the rest. 16a/16a-2 pin exactly that; cross-repo
# contract drift itself is owned by the CLI repo's CI and the Docker fixture
# running the pinned released wheel.
# ---------------------------------------------------------------------------
hostcli_plan="$(printf 'tests/docker/fixture-pins.txt\n' |
  bash "$SELECT" --changed-stdin --print-plan-only 2>/dev/null)"
before_hostcli="$(sed -n 's/^JOURNEY_CLASSES=//p' <<<"$hostcli_plan")"
# The old FolderList* old-CLI journeys were deleted with the app module. Their
# successors are the two app2 journeys built on what the CLI emits: the session
# tree (`pocketshell sessions --json`) and the usage panel (`usage --json`).
if [[ "$before_hostcli" == *"J02SessionTreeListJourney"* ]] &&
   [[ "$before_hostcli" == *"J12UsagePanelJourney"* ]]; then
  ok "16a a pinned-producer bump (fixture-pins.txt) runs the host-CLI-reading journeys (#1509 G10, #847 class)"
else
  bad "16a a pinned-producer bump does NOT run the host-CLI-reading journeys: $before_hostcli"
fi
hostcli_tasks="$(sed -n 's/^UNIT_SHARED_TASKS=//p' <<<"$hostcli_plan")"
hostcli_unit_mode="$(sed -n 's/^UNIT_MODE=//p' <<<"$hostcli_plan")"
if [[ "$hostcli_unit_mode" == full ]] ||
   [[ " $hostcli_tasks " == *" :shared:core-usage:test "* &&
      " $hostcli_tasks " == *" :shared:core-storage:test "* &&
      " $hostcli_tasks " == *" :shared:core-hostapi:test "* ]]; then
  ok "16a-2 a pinned-producer bump runs the SHARED-module readers of the wire (full run covers core-usage / core-storage / core-hostapi) — the round-2 B6 hole"
else
  bad "16a-2 a pinned-producer bump does NOT run the shared-module wire readers: mode=$hostcli_unit_mode tasks=$hostcli_tasks"
fi

# ---------------------------------------------------------------------------
# CASE 17 (B2) — the import-derived dependency edges are load-bearing.
#
# Round 1 relied on hand-written area couples, two of which were claimed in the
# write-up and absent from the manifest; 29 journeys importing connection-core
# production types were not selected by a connection-core change. Mutation:
# neuter the import scan so every class depends only on its own area.
#
# POST-SPLIT WITNESS (issue #2643): this case used to assert FAIL I9 naming
# AppDatabaseTest — the unit pin whose ONLY route onto a host-CLI change was
# the import graph. The host-CLI pin probe is now tests/docker/fixture-pins.txt,
# which force-fulls, so that pin survives the mutation trivially and can no
# longer witness anything. The property is now asserted directly against the
# emitted plan: for a cross-area production probe, the import-derived deps
# select strictly MORE unit classes than the couples alone — kill the import
# scan and the scoped plan must measurably shrink.
# ---------------------------------------------------------------------------
sed -i "s|import\[\[:space:\]\]+com\\\\.pocketshell\\\\.|import[[:space:]]+zz_no_such_package_zz\\\\.|" \
  "$MUTDIR/lib/test-areas.sh"
# PROVE THE MUTANT IS LIVE before reading anything into the result. #1641 spent a
# round on a mutation that had landed inside a KDoc block and killed nothing —
# "the mutation survived" and "the mutation never happened" look identical.
if grep -q 'zz_no_such_package_zz' "$MUTDIR/lib/test-areas.sh" &&
   [[ "$(POCKETSHELL_TEST_AREAS_REPO_ROOT="$SCRIPT_DIR/.." \
         POCKETSHELL_TEST_AREAS_MANIFEST="$MUTDIR/test-areas.txt" \
         POCKETSHELL_TEST_AREAS_JOURNEY_SUITE="$MUTDIR/ci-app2-journey-suite.sh" \
         bash "$MUTDIR/select-test-areas.sh" --verify-manifest 2>&1 |
         grep -c 'import lines scanned = 0')" -eq 1 ]]; then
  : # mutant confirmed live: zero import lines reach the dependency index
else
  bad "17 MUTATION DID NOT APPLY — the import scan is still live in the copy, so 17's verdict would be meaningless"
fi
# The probe is production code in a shared module that app2 classes reach ONLY
# through the import graph (the usage NDJSON parser and its consumers), so the
# scoped selection is exactly where import-derived deps show up.
PROBE17="shared/core-usage/src/main/java/com/pocketshell/core/usage/UsageRemoteSource.kt"
baseline17="$(POCKETSHELL_TEST_AREAS_REPO_ROOT="$SCRIPT_DIR/.." \
  POCKETSHELL_TEST_AREAS_MANIFEST="$SCRIPT_DIR/test-areas.txt" \
  POCKETSHELL_TEST_AREAS_JOURNEY_SUITE="$SCRIPT_DIR/ci-app2-journey-suite.sh" \
  bash "$SCRIPT_DIR/select-test-areas.sh" --changed-stdin --print-plan-only <<<"$PROBE17" 2>&1 |
  sed -n 's/^UNIT_SELECTED_UNIT_CLASSES=//p')"
mutated17="$(run_mut --changed-stdin --print-plan-only <<<"$PROBE17" 2>&1 |
  sed -n 's/^UNIT_SELECTED_UNIT_CLASSES=//p')"
if [[ "$baseline17" =~ ^[0-9]+$ && "$mutated17" =~ ^[0-9]+$ ]] &&
   (( mutated17 < baseline17 )); then
  ok "17 disabling the import-derived deps shrinks the scoped plan ($baseline17 -> $mutated17 unit classes on the cross-area probe) — the edges are load-bearing"
else
  bad "17 the import-derived dependency edges are not load-bearing (baseline='$baseline17' mutated='$mutated17')"
fi
cp "$SCRIPT_DIR/lib/test-areas.sh" "$MUTDIR/lib/test-areas.sh"   # restore

# ---------------------------------------------------------------------------
# CASE 18 (B3) — the emitted `--tests` filter must BE the plan.
#
# Round 1 emitted `com.pocketshell.app.*Test`; Gradle's `*` crosses package
# dots, so the command ran all 485 :app unit classes while the plan reported a
# fraction of them. Mutation: reinstate that wildcard. I10 must redden.
# ---------------------------------------------------------------------------
filters="$(printf 'app2/src/main/java/com/pocketshell/next/usage/UsageScreen.kt\n' |
  bash "$SELECT" --changed-stdin --print-plan-only 2>/dev/null |
  sed -n 's/^UNIT_GRADLE_FILTERS=//p')"
if [[ -n "$filters" && "$filters" != *"*"* ]]; then
  ok "18a the emitted app-module filter is exact class names, with no glob metacharacter"
else
  bad "18a emitted filter is empty or still contains a wildcard: $filters"
fi
sed -i 's|^      app_unit+=("$fqcn")$|      app_unit+=("com.pocketshell.next.*Test")|' \
  "$MUTDIR/select-test-areas.sh"
grep -q 'com.pocketshell.next.\*Test' "$MUTDIR/select-test-areas.sh" ||
  bad "18b MUTATION DID NOT APPLY — the wildcard mutant is not live in the copy"
out="$(run_mut --coverage-invariant --only I10)"
if grep -q 'FAIL I10' <<<"$out" && grep -q 'glob metacharacter' <<<"$out"; then
  ok "18b reinstating the package wildcard reddens I10 (plan and command must agree)"
else
  bad "18b the wildcard filter survived I10:\n$(grep -E '^(OK|FAIL) I10' <<<"$out")"
fi
cp "$SCRIPT_DIR/select-test-areas.sh" "$MUTDIR/select-test-areas.sh"   # restore

# ---------------------------------------------------------------------------
# CASE 19 (B5) — the area -> Gradle-task map.
#
# Round 1's map was a hardcoded `case`; the reviewer renamed one area token
# (`usage-costs` -> `usage-panel`) and BOTH guards stayed green while
# `:shared:core-usage:test` silently dropped out. 19a replays that exact rename
# and asserts the task survives it (the map is derived from class paths now);
# 19b drops a module from the derivation and asserts I11 reddens, so 19a is not
# vacuous.
# ---------------------------------------------------------------------------
renamed="$SANDBOX/manifest-usage-renamed.txt"
sed 's/usage-costs/usage-panel/g' "$SCRIPT_DIR/test-areas.txt" > "$renamed"
tasks="$(printf 'shared/core-usage/src/main/java/com/pocketshell/core/usage/Probe.kt\n' |
  POCKETSHELL_TEST_AREAS_MANIFEST="$renamed" bash "$SELECT" --changed-stdin --print-plan-only 2>/dev/null |
  sed -n 's/^UNIT_SHARED_TASKS=//p')"
if [[ " $tasks " == *" :shared:core-usage:test "* ]]; then
  ok "19a renaming the usage area token no longer drops :shared:core-usage:test"
else
  bad "19a the reviewer's rename mutation still drops the module task: $tasks"
fi
sed -i 's|^      shared_tasks\["$mod:test"\]=1$|      case "$mod" in :shared:core-usage) : ;; *) shared_tasks["$mod:test"]=1 ;; esac|' \
  "$MUTDIR/select-test-areas.sh"
grep -q ':shared:core-usage) : ;;' "$MUTDIR/select-test-areas.sh" ||
  bad "19b MUTATION DID NOT APPLY — the dropped-module mutant is not live in the copy"
out="$(run_mut --coverage-invariant --only I9,I11)"
if grep -q 'FAIL I11' <<<"$out" && grep -q ':shared:core-usage:test' <<<"$out"; then
  ok "19b dropping one module from the task derivation reddens I11 by name"
else
  bad "19b a module silently missing from the task map survived I11:\n$(grep -E '^(OK|FAIL) I11' <<<"$out")"
fi
# The OTHER half of the I9 unit pin: the class is still SELECTED (its area is
# unchanged) but the emitted plan no longer runs its module. That is the exact
# shape of the B6 symptom the reviewer measured — a green selection over a
# command that does not include the task — so the pin must catch it too.
if grep -q 'FAIL I9' <<<"$out" &&
   grep -q 'does NOT run :shared:core-usage:test' <<<"$out"; then
  ok "19c ...and the I9 unit pin reddens on the same mutant because the emitted plan no longer runs :shared:core-usage:test (selected != executed)"
else
  bad "19c the unit pin did not notice that the plan stopped running the pinned class's module:\n$(grep -E '^(OK|FAIL) I9' <<<"$out")"
fi
cp "$SCRIPT_DIR/select-test-areas.sh" "$MUTDIR/select-test-areas.sh"   # restore

# ---------------------------------------------------------------------------
# CASE 20 (B4 sibling) — ONE resolver. The FQCN resolver and the path resolver
# must agree on the real tree, which is the property whose absence made the
# ledger guard red for 183 classes while --verify-manifest was green.
# ---------------------------------------------------------------------------
mismatch=0
while IFS=$'\t' read -r fq area _mod _ss _deps; do
  [[ -z "$fq" ]] && continue
  [[ "$area" == "__NONE__" ]] && { mismatch=$((mismatch + 1)); echo "  unresolved: $fq"; }
done < <(bash "$SELECT" --list-classes 2>/dev/null)
if [[ "$mismatch" -eq 0 ]]; then
  ok "20 every registered class resolves by FQCN on the real tree (one resolver, no shared-module blind spot)"
else
  bad "20 $mismatch registered class(es) resolve to no area by FQCN"
fi

# ---------------------------------------------------------------------------
# CASE 21 (#2065) — the unconventional-@Test-file guard, and the exemption rows
# that keep it honest.
#
# #2063 proved a NEW offender fails. #2065 added the other half: an exemption is
# only allowed when the guard can CHECK how the file executes and what accounts
# for it. Every row below mutates one of those claims and asserts the specific
# red — because a baseline whose justifications are prose is a baseline that can
# say anything (G6: name the mutation that must redden this assertion, then
# actually apply it).
#
# These run against the REAL tree deliberately: the property under test is about
# the real exemption list and the real files it pins, and a synthetic mini-repo
# would only prove the parser parses. Only the exemption file is swapped for a
# mutated copy; everything else is the shipped guard reading the shipped tree. The verdict is read from the specific FAIL
# line, not the exit code, since the real tree emits many other checks.
# 21k–21m (#2170) also plant a tracked non-source via a private index copy.
# ---------------------------------------------------------------------------
UNCONV_REAL="$SCRIPT_DIR/test-unconventional-test-files.txt"
UNCONVDIR="$SANDBOX/unconventional"
mkdir -p "$UNCONVDIR"

run_unconv() {  # $1 = exemption file
  POCKETSHELL_TEST_AREAS_UNCONVENTIONAL="$1" \
  bash "$SELECT" --verify-manifest 2>&1
}

if [[ ! -f "$UNCONV_REAL" ]]; then
  bad "21 the exemption file is missing: $UNCONV_REAL"
else
  out="$(run_unconv "$UNCONV_REAL")"
  if grep -q 'OK: no new @Test-bearing file outside' <<<"$out"; then
    ok "21 the shipped exemption list is green on the real tree (every row's executor and gate check out)"
  else
    bad "21 the shipped exemption list is RED on the real tree:\n$(grep -E 'convention|exemption' <<<"$out")"
  fi

  # 21a — A NEW offender. Dropping a row makes its file unpinned, which is the
  # SAME code path a newly added unconventional @Test file takes: this is the
  # #1851 shape, and it is the one red that must never be losable.
  grep -v '^shared/ui-kit' "$UNCONV_REAL" > "$UNCONVDIR/no-designrenders.txt"
  # Anchored at the start of the line: the app2 render rows NAME DesignRenders
  # in their justification prose, so an unanchored grep would report the
  # mutation dead while it was in fact applied.
  if grep -q '^shared/ui-kit.*DesignRenders' "$UNCONVDIR/no-designrenders.txt"; then
    bad "21a MUTATION DID NOT APPLY — the DesignRenders row is still in the copy, so 21a's verdict would be meaningless"
  fi
  out="$(run_unconv "$UNCONVDIR/no-designrenders.txt")"
  if grep -q 'FAIL: 1 @Test-bearing file(s) do not follow' <<<"$out" &&
     grep -q 'render/DesignRenders.kt' <<<"$out"; then
    ok "21a an unpinned @Test-bearing file outside the convention reddens BY NAME (the #1851 shape)"
  else
    bad "21a an unpinned unconventional @Test file did NOT redden:\n$(grep -E 'convention|exemption' <<<"$out")"
  fi

  # 21b — Anti-rot the other way: a row that no longer pins a hidden file (the
  # file was renamed to the convention, or deleted) must fail rather than sit
  # there forever pretending to justify something.
  { cat "$UNCONV_REAL"
    printf 'app/src/test/java/com/pocketshell/app/GhostHarness.kt\tunit-source-set\tenumerated-by:scripts/render.sh\tstale row\n'
  } > "$UNCONVDIR/stale.txt"
  out="$(run_unconv "$UNCONVDIR/stale.txt")"
  if grep -q 'FAIL: stale exemption' <<<"$out" && grep -q 'GhostHarness.kt' <<<"$out"; then
    ok "21b a row that no longer pins a hidden @Test file reddens as stale (the list cannot rot into a lie)"
  else
    bad "21b a stale exemption row survived:\n$(grep -E 'convention|exemption' <<<"$out")"
  fi

  # 21c — The reason is mandatory. "Recorded exemption" means recorded; an empty
  # justification is the baseline-by-default #2065 exists to stop.
  sed 's|\(render/DesignRenders.kt\tunit-source-set\tenumerated-by:scripts/render.sh\t\).*|\1|' \
    "$UNCONV_REAL" > "$UNCONVDIR/no-reason.txt"
  if grep -qP 'DesignRenders\.kt\tunit-source-set\tenumerated-by:scripts/render\.sh\t.' \
       "$UNCONVDIR/no-reason.txt"; then
    bad "21c MUTATION DID NOT APPLY — the DesignRenders reason is still present in the copy"
  fi
  out="$(run_unconv "$UNCONVDIR/no-reason.txt")"
  if grep -q 'FAIL: 1 unconventional-test-file exemption' <<<"$out" &&
     grep -q 'expected <path>TAB<executor>TAB<gate>TAB<reason>' <<<"$out"; then
    ok "21c an exemption with no recorded reason reddens"
  else
    bad "21c an exemption with no reason survived:\n$(grep -E 'convention|exemption' <<<"$out")"
  fi

  # 21e — An executor the guard cannot check is rejected, not believed. This is
  # the "I could not check" != "I checked and it is fine" rule.
  sed 's|\(ShareScreenRenders.kt\t\)unit-source-set|\1runs-somewhere-trust-me|' \
    "$UNCONV_REAL" > "$UNCONVDIR/unknown-executor.txt"
  if ! grep -qP 'ShareScreenRenders\.kt\truns-somewhere-trust-me' "$UNCONVDIR/unknown-executor.txt"; then
    bad "21e MUTATION DID NOT APPLY — the executor is unchanged in the copy"
  fi
  out="$(run_unconv "$UNCONVDIR/unknown-executor.txt")"
  if grep -q "unknown executor 'runs-somewhere-trust-me'" <<<"$out"; then
    ok "21e an unverifiable executor claim is rejected rather than believed"
  else
    bad "21e an unknown executor claim survived:\n$(grep -E 'exemption' -A3 <<<"$out")"
  fi

  # 21f — THE DELETED EXECUTOR KIND STAYS DELETED. `nightly-connected` rested on
  # nightly phase 1 running `:app:connectedDebugAndroidTest` WHOLESALE minus a
  # `notClass` list; the rewrite deleted that module and that lane, and app2's
  # suite runs unfiltered with no exclusion list to be absent from. A row that
  # still claims it must be REJECTED as unknown, not quietly honoured — a
  # resurrected kind whose premise nothing can check is the #2065 lie in its
  # purest form. (This replaces the old 21f/21i/21j trio, which mutated the
  # nightly suite's phase-1 shape; there is no phase 1 to mutate any more.)
  sed 's|\(ShareScreenRenders.kt\t\)unit-source-set|\1nightly-connected|' \
    "$UNCONV_REAL" > "$UNCONVDIR/resurrected-nightly.txt"
  if ! grep -qP 'ShareScreenRenders\.kt\tnightly-connected' "$UNCONVDIR/resurrected-nightly.txt"; then
    bad "21f MUTATION DID NOT APPLY — the resurrected nightly-connected row is not in the copy"
  fi
  out="$(run_unconv "$UNCONVDIR/resurrected-nightly.txt")"
  if grep -q "unknown executor 'nightly-connected'" <<<"$out"; then
    ok "21f a resurrected 'nightly-connected' executor claim is rejected — the deleted kind cannot come back by data alone"
  else
    bad "21f the deleted nightly-connected executor was still honoured:\n$(grep -E 'exemption' -A3 <<<"$out")"
  fi

  # 21g — The gate must be a class the registries can actually see. Resolved
  # through the SAME class index the area manifest and the ledger use, so
  # "the real assertion lives over there" cannot point at a name that does not
  # exist, or at another invisible file.
  sed 's|com.pocketshell.next.share.SharePickerScreenTest|com.pocketshell.next.share.TotallyFineGateTest|' \
    "$UNCONV_REAL" > "$UNCONVDIR/ghost-gate.txt"
  if ! grep -q 'TotallyFineGateTest' "$UNCONVDIR/ghost-gate.txt"; then
    bad "21g MUTATION DID NOT APPLY — the gate is unchanged in the copy"
  fi
  out="$(run_unconv "$UNCONVDIR/ghost-gate.txt")"
  if grep -q "gate 'com.pocketshell.next.share.TotallyFineGateTest' is not a known test class" <<<"$out"; then
    ok "21g a gate naming a class no registry knows reddens"
  else
    bad "21g a ghost gate class survived:\n$(grep -E 'exemption' -A3 <<<"$out")"
  fi

  # 21h — An `enumerated-by:` registry must actually enumerate THIS file.
  sed 's|enumerated-by:scripts/render.sh|enumerated-by:scripts/ci-app2-journey-suite.sh|' \
    "$UNCONV_REAL" > "$UNCONVDIR/wrong-enumerator.txt"
  out="$(run_unconv "$UNCONVDIR/wrong-enumerator.txt")"
  if grep -q 'does not reference this path, so it cannot be enumerating it' <<<"$out"; then
    ok "21h an enumerated-by: script that never mentions the file reddens"
  else
    bad "21h a bogus enumerated-by: claim survived:\n$(grep -E 'exemption' -A3 <<<"$out")"
  fi

  # -------------------------------------------------------------------------
  # #2170 — a `unit-source-set` row is a claim that the file EXECUTES in that
  # lane. Path-prefix matching accepts any file under the source-set directory,
  # including a parked `*.kt.turned-off` that Gradle never compiles, and any
  # file under a DIFFERENT source set entirely. 21d and 21l are the two halves
  # of that; 21m is the mutation that proves 21l is not decorative.
  #
  # The plant is tracked via a private GIT_INDEX_FILE copy so `git ls-files`
  # (what the guard inventories) sees it, without touching the real index.
  # A valid gate FQCN is reused so a red cannot be a stale-row or ghost-gate
  # miss in costume.
  # -------------------------------------------------------------------------
  ISSUE2170_INDEX="$(mktemp)"
  cp "$(git -C "$SCRIPT_DIR/.." rev-parse --absolute-git-dir)/index" "$ISSUE2170_INDEX"

  plant_hidden_unconventional() {  # $1 = repo-relative path
    local rel="$1" abs="$SCRIPT_DIR/../$1"
    mkdir -p "$(dirname "$abs")"
    cat > "$abs" <<'KT'
package com.pocketshell.next.parked
import org.junit.Test
class Issue2170NonCompilingParked {
    @Test
    fun neverRuns() {}
}
KT
    ISSUE2170_PLANTS+=("$rel")
    GIT_INDEX_FILE="$ISSUE2170_INDEX" git -C "$SCRIPT_DIR/.." update-index --add -- "$rel"
  }
  unplant_hidden_unconventional() {  # $1 = repo-relative path
    local rel="$1"
    rm -f "$SCRIPT_DIR/../$rel"
    GIT_INDEX_FILE="$ISSUE2170_INDEX" git -C "$SCRIPT_DIR/.." update-index --remove -- "$rel" 2>/dev/null || true
  }

  run_unconv_planted() {  # $1 = exemption file, $2 = select-test-areas.sh (optional)
    POCKETSHELL_TEST_AREAS_UNCONVENTIONAL="$1" \
    GIT_INDEX_FILE="$ISSUE2170_INDEX" \
    bash "${2:-$SELECT}" --verify-manifest 2>&1
  }

  # A COMPILING, conventionally-invisible androidTest source: the compiling
  # check cannot fire on it, so the only thing that can redden 21d is the
  # source-set check itself.
  ISSUE2170_ANDROID_PATH="app2/src/androidTest/java/com/pocketshell/next/connect/Issue2170ParkedHarness.kt"
  ISSUE2170_UNIT_PATH="shared/ui-kit/src/test/java/com/pocketshell/uikit/render/Issue2170NonCompilingParked.kt.turned-off"
  ISSUE2170_GATE="com.pocketshell.next.composer.ComposerBarTest"

  # 21d — The executor claim must match the path. `unit-source-set` asserts
  # `./gradlew test` runs the file; claiming it for an androidTest path is the
  # exact false "it executes somewhere" this guard refuses. With
  # `nightly-connected` deleted there is now NO executor that can cover an
  # instrumented path, which is the point: name it to the convention instead.
  plant_hidden_unconventional "$ISSUE2170_ANDROID_PATH"
  {
    cat "$UNCONV_REAL"
    printf '%s\tunit-source-set\t%s\tandroidTest path claiming the unit lane — must be rejected\n' \
      "$ISSUE2170_ANDROID_PATH" "$ISSUE2170_GATE"
  } > "$UNCONVDIR/wrong-executor.txt"
  if ! grep -Fq "$ISSUE2170_ANDROID_PATH" "$UNCONVDIR/wrong-executor.txt"; then
    bad "21d MUTATION DID NOT APPLY — the androidTest unit-source-set row is missing from the copy"
  fi
  out="$(run_unconv_planted "$UNCONVDIR/wrong-executor.txt")"
  if grep -Fq "$ISSUE2170_ANDROID_PATH" <<<"$out" &&
     grep -q "executor 'unit-source-set' but the path is not under \*/src/test/" <<<"$out" &&
     ! grep -q "$ISSUE2170_ANDROID_PATH: executor 'unit-source-set' but the file is not a compiling" <<<"$out"; then
    ok "21d claiming the unit source set for an androidTest path reddens on the source set, not on some other check"
  else
    bad "21d a false unit-source-set executor claim survived:\n$(grep -E 'exemption' -A3 <<<"$out")"
  fi
  unplant_hidden_unconventional "$ISSUE2170_ANDROID_PATH"

  # 21l — unit-source-set + */src/test/ + a file Gradle will not compile. A
  # prefix-only `*/src/test/*` matcher accepts it; the compiling-source check is
  # what must reject it.
  plant_hidden_unconventional "$ISSUE2170_UNIT_PATH"
  {
    cat "$UNCONV_REAL"
    printf '%s\tunit-source-set\t%s\tparked non-source — must be rejected (#2170)\n' \
      "$ISSUE2170_UNIT_PATH" "$ISSUE2170_GATE"
  } > "$UNCONVDIR/parked-unit.txt"
  if ! grep -Fq "$ISSUE2170_UNIT_PATH" "$UNCONVDIR/parked-unit.txt"; then
    bad "21l MUTATION DID NOT APPLY — the parked unit-source-set row is missing from the copy"
  fi
  out="$(run_unconv_planted "$UNCONVDIR/parked-unit.txt")"
  if grep -Fq "$ISSUE2170_UNIT_PATH" <<<"$out" &&
     grep -q "executor 'unit-source-set' but the file is not a compiling Kotlin/Java source" <<<"$out"; then
    ok "21l a unit-source-set row for a parked .kt.turned-off under */src/test/ reddens (the #2170 mirror)"
  else
    bad "21l a unit-source-set row for a non-compiling src/test path survived:\n$(grep -E 'convention|exemption' -A3 <<<"$out")"
  fi

  # 21m — G6: a prefix-only matcher must redden 21l. Restore the old
  # `is_compiling_test_source` (always-true == path-prefix only) on a private
  # copy and assert the planted `.kt.turned-off` is ACCEPTED. If it is still
  # rejected, 21l is red for the wrong reason (stale / gate / hidden) and its
  # assertion is decorative.
  prefix_only="$(mut_copy issue2170-prefix-only)"
  if ! grep -q 'is_compiling_test_source()' "$prefix_only/select-test-areas.sh"; then
    bad "21m MUTATION DID NOT APPLY — is_compiling_test_source() is missing from the copy, so a prefix-only revert cannot be shown"
  else
    awk '
      /^is_compiling_test_source\(\)/ { print "is_compiling_test_source() { return 0; }"; skip=1; next }
      skip && /^}/ { skip=0; next }
      !skip { print }
    ' "$prefix_only/select-test-areas.sh" > "$prefix_only/select-test-areas.sh.mut" &&
      mv "$prefix_only/select-test-areas.sh.mut" "$prefix_only/select-test-areas.sh"
    if ! grep -Fxq 'is_compiling_test_source() { return 0; }' "$prefix_only/select-test-areas.sh" ||
       grep -qE '\*\.kt\|\*\.java\) return 0' "$prefix_only/select-test-areas.sh"; then
      bad "21m MUTATION DID NOT APPLY — prefix-only stub not in place (compiling-extension matcher still live)"
    else
      out="$(
        POCKETSHELL_TEST_AREAS_REPO_ROOT="$SCRIPT_DIR/.." \
        POCKETSHELL_TEST_AREAS_MANIFEST="$prefix_only/test-areas.txt" \
        POCKETSHELL_TEST_AREAS_JOURNEY_SUITE="$prefix_only/ci-app2-journey-suite.sh" \
        POCKETSHELL_TEST_AREAS_UNCONVENTIONAL="$UNCONVDIR/parked-unit.txt" \
        GIT_INDEX_FILE="$ISSUE2170_INDEX" \
        bash "$prefix_only/select-test-areas.sh" --verify-manifest 2>&1
      )"
      if grep -q "executor 'unit-source-set' but the file is not a compiling Kotlin/Java source" <<<"$out"; then
        bad "21m prefix-only matcher still rejected the parked .kt.turned-off — 21l is red for another reason:\n$(grep -E 'convention|exemption' -A3 <<<"$out")"
      elif grep -q "OK: no new @Test-bearing file outside" <<<"$out"; then
        ok "21m a prefix-only matcher accepts the parked unit-source-set .kt.turned-off (the mutation that reddens 21l)"
      else
        bad "21m prefix-only matcher did not cleanly accept the parked row (verdict is not the shipped-list OK):\n$(grep -E 'convention|exemption|FAIL' <<<"$out")"
      fi
    fi
  fi
fi

echo
echo "select-test-areas selftest: $PASS passed, $FAIL failed"
[[ "$FAIL" -eq 0 ]] || exit 1
exit 0
