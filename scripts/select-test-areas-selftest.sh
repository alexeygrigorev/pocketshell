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
class AlwaysE2eTest
KT
cat > "$SANDBOX/app/src/androidTest/java/com/pocketshell/app/proof/SharedFixture.kt" <<'KT'
package com.pocketshell.app.proof
object SharedFixture
KT
echo "# docs" > "$SANDBOX/docs/notes.md"

cat > "$SANDBOX/scripts/ci-journey-suite.sh" <<'SH'
#!/usr/bin/env bash
FQCN_PREFIX="com.pocketshell.app.proof"
JOURNEY_CLASSES=(
  "$FQCN_PREFIX.AlwaysE2eTest"
  "com.pocketshell.app.beta.BetaTest"
)
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
    POCKETSHELL_TEST_AREAS_JOURNEY_SUITE="$SANDBOX/scripts/ci-journey-suite.sh" \
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
  POCKETSHELL_TEST_AREAS_JOURNEY_SUITE="$SANDBOX/scripts/ci-journey-suite.sh" \
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
echo "class  com.pocketshell.app.beta.BetaTest  pcore" >> "$SANDBOX/mut-override.txt"
before="$(run_select "$BASE_MANIFEST" "docs/notes.md")"
after="$(run_select "$SANDBOX/mut-override.txt" "docs/notes.md")"
if [[ "$(field "$before" JOURNEY_CLASSES)" != *"com.pocketshell.app.beta.BetaTest"* ]] &&
   [[ "$(field "$after" JOURNEY_CLASSES)" == *"com.pocketshell.app.beta.BetaTest"* ]]; then
  ok "11 a class override moves BetaTest into the always tier (it now runs on a docs-only diff)"
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
grep -v '^src    shared/core-ssh/\*' "$SCRIPT_DIR/test-areas.txt" |
  grep -v '^test   shared/core-ssh/\*' > "$real_mut"
if POCKETSHELL_TEST_AREAS_MANIFEST="$real_mut" bash "$SELECT" --verify-manifest >/dev/null 2>&1; then
  bad "14 removing the core-ssh rules did NOT redden --verify-manifest — the guard is decorative"
else
  ok "14 removing the core-ssh rules reddens --verify-manifest (guard is live)"
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
cp "$SCRIPT_DIR/ci-journey-suite.sh" "$MUTDIR/ci-journey-suite.sh"
# The copies resolve their data relative to their own dir, so a copy that is not
# given these reads a MISSING exemption list / nightly suite and reddens for a
# reason that has nothing to do with the mutation under test (#2065).
cp "$SCRIPT_DIR/test-unconventional-test-files.txt" "$MUTDIR/test-unconventional-test-files.txt"
cp "$SCRIPT_DIR/nightly-extensive-suite.sh" "$MUTDIR/nightly-extensive-suite.sh"

run_mut() {  # run the MUTATED copy against the REAL tree
  POCKETSHELL_TEST_AREAS_REPO_ROOT="$SCRIPT_DIR/.." \
  POCKETSHELL_TEST_AREAS_MANIFEST="${MUT_MANIFEST:-$MUTDIR/test-areas.txt}" \
  POCKETSHELL_TEST_AREAS_JOURNEY_SUITE="$MUTDIR/ci-journey-suite.sh" \
  bash "$MUTDIR/select-test-areas.sh" "$@" 2>&1
}

# ---------------------------------------------------------------------------
# CASE 16 (B1 + B6 + B7a) — the host-CLI lockstep coupling is load-bearing, at
# BOTH ends of the wire, and cannot be quietly narrowed.
#
# Round 1: `tools/pocketshell/**` selected `host-cli` (0 Kotlin classes) plus
# the always tier and NOTHING else, so a host-CLI change ran zero of the
# journeys built to catch host-CLI/client mismatch — the #847 / v0.4.10 class.
#
# Round 2 fixed that with an INVOKER-only seam, which structurally could not
# reach `shared/*` (no shared module shells out). So `:shared:core-usage:test` —
# the STRICT, fail-loud reader of the NDJSON `tools/pocketshell/.../usage.py`
# emits — still did not run on a host-CLI change, with all 8 checks and all 10
# invariants green. 16a-2 is that exact symptom as an assertion; 16e/16f are its
# mutations.
#
# Round 2 also floored the seam at `>= 1`, so the reviewer cut it from 15
# packages / 569 classes to 9 / 210 without reddening anything. 16d replays that
# exact erosion.
# ---------------------------------------------------------------------------
hostcli_plan="$(printf 'tools/pocketshell/src/pocketshell/tree.py\n' |
  bash "$SELECT" --changed-stdin --print-plan-only 2>/dev/null)"
before_hostcli="$(sed -n 's/^JOURNEY_CLASSES=//p' <<<"$hostcli_plan")"
if [[ "$before_hostcli" == *"FolderListHostOutdatedTreeVersionDaemonDockerTest"* ]] &&
   [[ "$before_hostcli" == *"FolderListOldCliHydrateDockerTest"* ]]; then
  ok "16a a tools/pocketshell change runs the old-host-CLI journeys (#1509 G10, #847 class)"
else
  bad "16a a host-CLI change does NOT run the old-CLI journeys: $before_hostcli"
fi
hostcli_tasks="$(sed -n 's/^UNIT_SHARED_TASKS=//p' <<<"$hostcli_plan")"
if [[ " $hostcli_tasks " == *" :shared:core-usage:test "* ]] &&
   [[ " $hostcli_tasks " == *" :shared:core-storage:test "* ]] &&
   [[ " $hostcli_tasks " == *" :shared:ui-kit:test "* ]]; then
  ok "16a-2 a tools/pocketshell change runs the SHARED-module readers of the wire (core-usage / core-storage / ui-kit) — the round-2 B6 hole"
else
  bad "16a-2 a host-CLI change does NOT run the shared-module wire readers: $hostcli_tasks"
fi
out="$(POCKETSHELL_TA_HOSTCLI_MARKER='ZZ_NO_SUCH_HOST_CLI_MARKER_ZZ' bash "$SELECT" --verify-manifest 2>&1)"
if grep -q 'wire-seam PRODUCER packages = 0' <<<"$out" &&
   grep -q 'wire-seam CONSUMER packages = 0' <<<"$out"; then
  ok "16b breaking the invoke marker reddens the PRODUCER and CONSUMER floors (the consumer end is anchored on the invoker file list)"
else
  bad "16b a dead invoke marker did NOT redden the per-end floors:\n$out"
fi
out="$(POCKETSHELL_TA_HOSTCLI_MARKER='ZZ_NO_SUCH_HOST_CLI_MARKER_ZZ' \
       POCKETSHELL_TA_HOSTCLI_CLI_SOURCE='tools/pocketshell/no_such_cli.py' \
       bash "$SELECT" --coverage-invariant --only I9 2>&1)"
if grep -q 'FAIL I9' <<<"$out" && grep -q 'FolderListHostOutdatedTreeVersionDaemonDockerTest' <<<"$out"; then
  ok "16c killing every end of the seam reddens the #1509 lockstep pin by name"
else
  bad "16c the #1509 pin survived a completely dead wire seam:\n$(grep -E '^(OK|FAIL) I9' <<<"$out")"
fi
# 16d — the ROUND-2 REVIEWER'S EXACT EROSION. Dropping the two string-literal
# alternatives from the invoke marker took the seam from 15 packages / 569
# classes to 9 / 210 and NOTHING noticed, because both host-CLI floors were
# `>= 1`. It must be loud now.
#
# WHAT IS PINNED, AND WHY IT IS NOT AN EXACT COUNT (#2124 round 4).
# This case used to assert the literal `wire-seam PRODUCER packages = 9`. The
# eroded count is not a constant: it is "how many production packages still
# name `PocketshellCommand` after the two string-literal alternatives are
# dropped", and that GROWS whenever a new sender routes through the host CLI.
# #2124 added one (HostAckOutboundDelivery), the count became 10, and this case
# went red on a tree where the guard was working exactly as designed — a false
# alarm that costs a CI round and teaches the next author to bump the literal,
# which re-arms the identical trap. So the assertion is the RELATIONSHIP the
# case actually exists to prove, in two parts:
#
#   (i)  the narrowed marker trips the PRODUCER floor SPECIFICALLY — not merely
#        "some floor somewhere fired", which `the import-dependency index looks
#        broken` alone would accept from the consumer or shared-reach floor —
#        and the count it reports is under the floor production itself
#        enforces, read off production's own message rather than duplicated
#        here, so raising or lowering that floor cannot silently desync;
#   (ii) the erosion genuinely ERODES: the producer count under the narrowed
#        marker is strictly SMALLER than the count on the unmutated marker.
#
# The mutations that must redden it, and do (both run against a private copy
# of the tree):
#   * restore the round-2 `>= 1` producer floor in --verify-manifest check 7 —
#     precisely the B7a regression this case was written for. No PRODUCER line
#     is emitted under the narrowed marker, so (i) fails. It is the ONLY case
#     that reddens (56 pass / 1 fail), which is the selectivity this check
#     needs. Note this mutation does NOT disturb the `index looks broken`
#     grep: the consumer and shared-reach floors still fire under the narrowed
#     marker, so that grep alone accepts the regression and reading the
#     PRODUCER line by name is the load-bearing half.
#   * narrow production's own marker to `PocketshellCommand` so the erosion is
#     a no-op. The unmutated producer count is then unreadable (the clean run
#     is itself red), the `-n` guards below refuse to read a verdict out of it,
#     and (ii) fails closed rather than passing on a missing baseline.
# Neither number is written down here, so neither mutation can be absorbed by
# a stale literal, and legitimate growth of the seam cannot fake a failure.
clean_manifest="$(bash "$SELECT" --verify-manifest 2>&1)"
unmutated_producer="$(sed -n 's/.*wire-seam packages = \([0-9]\{1,\}\) producer .*/\1/p' <<<"$clean_manifest")"
out="$(POCKETSHELL_TA_HOSTCLI_MARKER='PocketshellCommand' bash "$SELECT" --verify-manifest 2>&1)"
producer_line="$(grep -E 'wire-seam PRODUCER packages = [0-9]+ \(< [0-9]+\)' <<<"$out" || true)"
eroded_producer="$(sed -n 's/.*wire-seam PRODUCER packages = \([0-9]\{1,\}\) (< [0-9]\{1,\}).*/\1/p' <<<"$producer_line")"
producer_floor="$(sed -n 's/.*wire-seam PRODUCER packages = [0-9]\{1,\} (< \([0-9]\{1,\}\)).*/\1/p' <<<"$producer_line")"
if grep -q 'the import-dependency index looks broken' <<<"$out" &&
   [[ -n "$unmutated_producer" && -n "$eroded_producer" && -n "$producer_floor" ]] &&
   [[ "$eroded_producer" -lt "$producer_floor" ]] &&
   [[ "$eroded_producer" -lt "$unmutated_producer" ]]; then
  ok "16d the reviewer's seam erosion now reddens --verify-manifest on the PRODUCER floor by name (round-2 B7a: it was silently green) — narrowed marker yields $eroded_producer producer packages, under the enforced floor of $producer_floor and under the unmutated $unmutated_producer"
else
  bad "16d the seam erosion is STILL green, or did not trip the PRODUCER floor, or did not erode at all (unmutated='$unmutated_producer' eroded='$eroded_producer' floor='$producer_floor'):\n$out"
fi

# 16e / 16f — CODE mutations, on their own private copies so case 17's mutation
# of MUTDIR cannot interact with them.
mut_copy() {  # $1 = dir name -> echoes a runner-ready dir
  local d="$SANDBOX/$1"
  mkdir -p "$d/lib"
  cp "$SCRIPT_DIR/select-test-areas.sh" "$d/select-test-areas.sh"
  cp "$SCRIPT_DIR/lib/test-areas.sh"    "$d/lib/test-areas.sh"
  cp "$SCRIPT_DIR/test-areas.txt"       "$d/test-areas.txt"
  cp "$SCRIPT_DIR/ci-journey-suite.sh"  "$d/ci-journey-suite.sh"
  # See the MUTDIR note: without these a copy fails on missing #2065 data
  # rather than on the mutation the case is about.
  cp "$SCRIPT_DIR/test-unconventional-test-files.txt" "$d/test-unconventional-test-files.txt"
  cp "$SCRIPT_DIR/nightly-extensive-suite.sh" "$d/nightly-extensive-suite.sh"
  printf '%s\n' "$d"
}
run_copy() {  # $1 = dir, rest = args
  local d="$1"; shift
  POCKETSHELL_TEST_AREAS_REPO_ROOT="$SCRIPT_DIR/.." \
  POCKETSHELL_TEST_AREAS_MANIFEST="$d/test-areas.txt" \
  POCKETSHELL_TEST_AREAS_JOURNEY_SUITE="$d/ci-journey-suite.sh" \
  bash "$d/select-test-areas.sh" "$@" 2>&1
}

# 16e (B6) — delete the CONSUMER end of the seam, i.e. reproduce round 2's
# invoker-only design exactly. The shared-reach floor is the check that exists
# for this and it must be the one that fires.
B6DIR="$(mut_copy mut-b6-consumer)"
sed -i 's|^      _pocketshell_hostcli_mark "${imp%.\*}" consumer$|      : # MUTANT 16e: consumer end deleted|' \
  "$B6DIR/lib/test-areas.sh"
if grep -q 'MUTANT 16e: consumer end deleted' "$B6DIR/lib/test-areas.sh"; then
  : # mutant confirmed live before its verdict is read (#1641)
else
  bad "16e MUTATION DID NOT APPLY — the consumer-end call is still present in the copy, so 16e's verdict would be meaningless"
fi
out="$(run_copy "$B6DIR" --verify-manifest)"
if grep -q 'wire-seam CONSUMER packages = 0' <<<"$out" &&
   grep -q 'wire-seam packages under shared/ = 3' <<<"$out"; then
  ok "16e deleting the CONSUMER end reddens the shared-reach floor — the round-2 seam (invoker-only, 0 shared packages) can no longer be green"
else
  bad "16e the round-2 invoker-only seam is still green:\n$out"
fi
out="$(printf 'tools/pocketshell/src/pocketshell/usage.py\n' |
  POCKETSHELL_TEST_AREAS_REPO_ROOT="$SCRIPT_DIR/.." \
  POCKETSHELL_TEST_AREAS_MANIFEST="$B6DIR/test-areas.txt" \
  POCKETSHELL_TEST_AREAS_JOURNEY_SUITE="$B6DIR/ci-journey-suite.sh" \
  bash "$B6DIR/select-test-areas.sh" --changed-stdin --print-plan-only 2>&1 |
  sed -n 's/^UNIT_SHARED_TASKS=//p')"
# Assert on the modules the CONSUMER end is the SOLE route to. core-usage,
# core-storage and ui-kit survive 16e because the vocabulary end also names
# them — the two ends deliberately overlap on the holes the reviewer found, and
# a check that ignored that would be measuring nothing.
if [[ " $out " != *" :shared:core-portfwd:test "* ]] &&
   [[ " $out " != *" :shared:core-assistant:test "* ]]; then
  ok "16e-2 with the consumer end deleted, a host-CLI change stops running the reply-side modules only that end reaches (:shared:core-portfwd:test, :shared:core-assistant:test) — the mutation moves the real plan"
else
  bad "16e-2 the consumer-end mutation changed nothing about the emitted plan, so 16e proves nothing: $out"
fi

# 16f (B6, selectivity) — drop exactly ONE package from the seam: the shared
# module whose parser IS the wire contract. Every floor stays above its bound
# (this is the round-2 shape: green totals, one real hole), so the UNIT pin has
# to be what catches it — including the "the plan actually runs that module"
# half, which is the literal symptom the reviewer measured.
B6BDIR="$(mut_copy mut-b6-onepkg)"
python3 - "$B6BDIR/lib/test-areas.sh" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
anchor = '  local p="$1" end="$2"\n  [[ -n "${POCKETSHELL_TA_PROD_PKG_AREA[$p]:-}" ]] || return 0'
assert s.count(anchor) == 1, "16f anchor not unique"
s = s.replace(anchor, anchor + '\n  [[ "$p" == com.pocketshell.core.usage ]] && return 0  # MUTANT 16f')
open(p, "w").write(s)
PY
if grep -q 'MUTANT 16f' "$B6BDIR/lib/test-areas.sh"; then
  : # live
else
  bad "16f MUTATION DID NOT APPLY"
fi
out="$(run_copy "$B6BDIR" --verify-manifest)"
if grep -q '^PASS: manifest verified' <<<"$out"; then
  ok "16f-a dropping one seam package leaves every --verify-manifest floor GREEN (this is exactly how the round-2 hole hid)"
else
  bad "16f-a the one-package mutation reddened a floor, so 16f-b would not prove the pin is load-bearing:\n$out"
fi
out="$(run_copy "$B6BDIR" --coverage-invariant)"
if grep -q 'FAIL I9' <<<"$out" &&
   grep -q 'does NOT select unit class com.pocketshell.core.usage.PocketshellUsageJsonParserTest' <<<"$out" &&
   [[ "$(grep -c '^FAIL I' <<<"$out")" -eq 1 ]]; then
  ok "16f-b ...and the I9 UNIT pin reddens by name, and ONLY I9 reddens (the round-2 hole is now caught by exactly one check)"
else
  bad "16f-b the usage-parser unit pin is not load-bearing / not selective:\n$(grep -E '^(OK|FAIL) I' <<<"$out")"
fi

# ---------------------------------------------------------------------------
# CASE 16g/16h — the VOCABULARY end asks the live Click Group for its
# command names. The old AST census was defeated by runtime command shapes; these
# cases now prove the live shape is observed and that import/interpreter failures
# stay loud instead of becoming a smaller vocabulary.
# ---------------------------------------------------------------------------
CLIDIR="$SANDBOX/cli"
mkdir -p "$CLIDIR"
REAL_CLI="$SCRIPT_DIR/../tools/pocketshell/src/pocketshell/cli.py"
if python3 - "$REAL_CLI" "$CLIDIR" <<'PY'
import pathlib
import sys

src = pathlib.Path(sys.argv[1]).read_text()
out = pathlib.Path(sys.argv[2])

# LIVE-IMPORT REGRESSION — a Click Group may synthesize a command from its
# runtime list_commands() implementation without storing a registration node
# in cli.py. The AST census cannot see this command, but a live Group can.
dynamic = src.replace(
    "@click.group(\n",
    "class DynGroup(click.Group):\n"
    "    def list_commands(self, ctx):\n"
    "        return [*super().list_commands(ctx), \"synthetic-live\"]\n\n"
    "@click.group(\n",
    1,
)
dynamic = dynamic.replace("@click.group(\n", "@click.group(\n    cls=DynGroup,\n", 1)
(out / "dynamic.py").write_text(dynamic)

# IMPORT-FAILURE MUTATION — a source file that cannot import must be loud.
broken = src.replace(
    "import click\n",
    "import click\nimport pocketshell_issue_2070_missing\n",
    1,
)
(out / "broken-import.py").write_text(broken)
PY
then
  # Every mutant is grep-verified LIVE before any verdict is read (#1641).
  for _m in 'dynamic:synthetic-live' 'broken-import:pocketshell_issue_2070_missing'; do
    if ! grep -qF "${_m#*:}" "$CLIDIR/${_m%%:*}.py"; then
      bad "16g MUTANT ${_m%%:*} DID NOT APPLY — its verdict would be meaningless"
    fi
  done

  # 16h — a live Click Group may synthesize a command from list_commands()
  # without a corresponding source registration. This is the red→green
  # regression for the AST reader replacement.
  live_vocab="$(POCKETSHELL_TA_HOSTCLI_PACKAGE_ROOT="${SCRIPT_DIR}/../tools/pocketshell/src" \
    POCKETSHELL_TA_HOSTCLI_CLI_SOURCE="${CLIDIR}/dynamic.py" bash -c '
    source "$1/lib/test-areas.sh"
    _pocketshell_hostcli_read_cli_vocabulary "$2"
  ' bash "${SCRIPT_DIR}" "${CLIDIR}/dynamic.py" 2>&1)"
  if grep -qx 'NAME synthetic-live' <<<"$live_vocab" &&
     grep -qx 'END' <<<"$live_vocab" &&
     ! grep -q '^UNREADABLE ' <<<"$live_vocab"; then
    ok "16h the live Click reader observes a command synthesized by list_commands()"
  else
    bad "16h the live reader missed synthetic-live or reported an error:
$live_vocab"
  fi

  # 16i — importing a CLI with a missing dependency must fail closed and name
  # the import failure; no partial NAME output may seed the vocabulary.
  broken_vocab="$(POCKETSHELL_TA_HOSTCLI_PACKAGE_ROOT="${SCRIPT_DIR}/../tools/pocketshell/src" \
    POCKETSHELL_TA_HOSTCLI_CLI_SOURCE="${CLIDIR}/broken-import.py" bash -c '
    source "$1/lib/test-areas.sh"
    _pocketshell_hostcli_read_cli_vocabulary "$2"
  ' bash "${SCRIPT_DIR}" "${CLIDIR}/broken-import.py" 2>&1)"
  if grep -q 'UNREADABLE cannot import .*ModuleNotFoundError' <<<"$broken_vocab" &&
     grep -qx 'END' <<<"$broken_vocab" &&
     ! grep -q '^NAME ' <<<"$broken_vocab"; then
    ok "16i a missing import dependency is explicit and fail-closed"
  else
    bad "16i a missing import dependency was not fail-closed:
$broken_vocab"
  fi

  # 16k — the live-reader mutants must also travel through the selector's
  # actual index build. Directly calling the helper above proves its protocol,
  # but would stay green if --verify-manifest stopped consuming that protocol.
  # Keep the package root explicit: the mutated source lives outside the
  # checkout while its normal pocketshell.* imports come from the checkout.
  real_guard="$(POCKETSHELL_TA_HOSTCLI_PACKAGE_ROOT="$SCRIPT_DIR/../tools/pocketshell/src" \
    POCKETSHELL_TA_HOSTCLI_CLI_SOURCE="$REAL_CLI" \
    bash "$SELECT" --verify-manifest 2>&1)"
  real_guard_rc=$?
  real_live_count="$(sed -n 's/.*over \([0-9][0-9]*\) live Click commands read.*/\1/p' <<<"$real_guard")"

  dynamic_guard="$(POCKETSHELL_TA_HOSTCLI_PACKAGE_ROOT="$SCRIPT_DIR/../tools/pocketshell/src" \
    POCKETSHELL_TA_HOSTCLI_CLI_SOURCE="$CLIDIR/dynamic.py" \
    bash "$SELECT" --verify-manifest 2>&1)"
  dynamic_guard_rc=$?
  dynamic_live_count="$(sed -n 's/.*over \([0-9][0-9]*\) live Click commands read.*/\1/p' <<<"$dynamic_guard")"
  if [[ "$real_guard_rc" -eq 0 && "$dynamic_guard_rc" -eq 0 ]] &&
     grep -q '^PASS: manifest verified' <<<"$dynamic_guard" &&
     [[ "$real_live_count" =~ ^[0-9]+$ && "$dynamic_live_count" =~ ^[0-9]+$ ]] &&
     (( dynamic_live_count == real_live_count + 1 )) &&
     ! grep -q 'UNREADABLE\|live vocabulary reader could not import/read' <<<"$dynamic_guard"; then
    ok "16k the real --verify-manifest index sees synthetic-live ($real_live_count -> $dynamic_live_count commands) with no unreadable diagnostic"
  else
    bad "16k the real --verify-manifest dynamic CLI run was not a clean baseline+1 verification (baseline rc=$real_guard_rc count='$real_live_count'; dynamic rc=$dynamic_guard_rc count='$dynamic_live_count'):
$dynamic_guard"
  fi

  broken_guard="$(POCKETSHELL_TA_HOSTCLI_PACKAGE_ROOT="$SCRIPT_DIR/../tools/pocketshell/src" \
    POCKETSHELL_TA_HOSTCLI_CLI_SOURCE="$CLIDIR/broken-import.py" \
    bash "$SELECT" --verify-manifest 2>&1)"
  broken_guard_rc=$?
  if [[ "$broken_guard_rc" -ne 0 ]] &&
     grep -qF 'host-CLI live vocabulary reader could not import/read' <<<"$broken_guard" &&
     grep -qF "ModuleNotFoundError: No module named 'pocketshell_issue_2070_missing'" <<<"$broken_guard" &&
     grep -qF 'host-CLI wire-seam VOCABULARY packages = 0 (< 14)' <<<"$broken_guard" &&
     grep -qF 'host-CLI live Click commands read = 0 (< 12)' <<<"$broken_guard" &&
     ! grep -q '^PASS: manifest verified' <<<"$broken_guard"; then
    ok "16k-b the real --verify-manifest broken-import run is nonzero and names the unreadable import plus both vocabulary under-floor diagnostics"
  else
    bad "16k-b the real --verify-manifest broken-import run did not fail with the named diagnostics (rc=$broken_guard_rc):
$broken_guard"
  fi

  # 16l — mutate the ACTUAL mapfile consumer, not the helper and not a direct
  # probe. An END-only stream is a readable but empty vocabulary, so this must
  # trip the live-command floor without laundering itself into an import error.
  LIVE_FLOOR_DIR="$(mut_copy mut-live-vocabulary-floor)"
  clean_live_floor="$(run_copy "$LIVE_FLOOR_DIR" --verify-manifest)"
  clean_live_floor_rc=$?
  if [[ "$clean_live_floor_rc" -eq 0 ]] &&
     grep -q '^PASS: manifest verified' <<<"$clean_live_floor"; then
    : # the private copy is a green control before the mapfile mutation
  else
    bad "16l the unmutated private selector/index copy is not a green control (rc=$clean_live_floor_rc):
$clean_live_floor"
  fi
  python3 - "$LIVE_FLOOR_DIR/lib/test-areas.sh" <<'PY'
import sys

p = sys.argv[1]
s = open(p).read()
anchor = '  mapfile -t vocab_lines < <(_pocketshell_hostcli_read_cli_vocabulary "$cli_src")'
assert s.count(anchor) == 1, "16l mapfile anchor not unique"
s = s.replace(anchor, """  mapfile -t vocab_lines < <(  # MUTANT 16l: actual consumer emits only END
    printf '%s\\n' END
  )""")
open(p, "w").write(s)
PY
  if ! grep -qF 'MUTANT 16l: actual consumer emits only END' "$LIVE_FLOOR_DIR/lib/test-areas.sh"; then
    bad "16l MUTATION DID NOT APPLY — the private copy still calls the live vocabulary reader"
  else
    if out="$(run_copy "$LIVE_FLOOR_DIR" --verify-manifest)"; then
      bad "16l the END-only actual mapfile consumer still passed --verify-manifest:
$out"
    elif grep -qF 'host-CLI live Click commands read = 0 (< 12)' <<<"$out" &&
         [[ "$(grep -Fc 'host-CLI live Click commands read =' <<<"$out")" -eq 1 ]] &&
         ! grep -qF 'live vocabulary reader could not import/read' <<<"$out" &&
         ! grep -q '^PASS: manifest verified' <<<"$out"; then
      ok "16l mutating the actual mapfile consumer to END-only reddens the named live-vocabulary floor (readable empty stream, selective failure)"
    else
      bad "16l the END-only actual mapfile mutation did not trip the named live-vocabulary floor selectively:
$out"
    fi
  fi
  # Restore the private copy before later cases, even though cleanup also
  # removes the sandbox. This keeps the mutation local to 16l if the script is
  # extended with more cases after it.
  cp "$SCRIPT_DIR/lib/test-areas.sh" "$LIVE_FLOOR_DIR/lib/test-areas.sh"
  # 16j — a missing/crashed interpreter must not leave a partial vocabulary
  # looking healthy. The missing END is the fail-closed boundary.
  mkdir -p "$SANDBOX/nopy"
  printf '#!/bin/sh\nexit 127\n' > "$SANDBOX/nopy/python3"
  chmod +x "$SANDBOX/nopy/python3"
  if ! "$SANDBOX/nopy/python3"; then : ; fi   # shim confirmed non-functional
  out="$(PATH="$SANDBOX/nopy:$PATH" bash "$SELECT" --verify-manifest 2>&1)"
  if grep -q 'the vocabulary reader did not finish' <<<"$out" &&
     grep -q 'live vocabulary reader could not import/read' <<<"$out"; then
    ok "16j a crashed/missing interpreter leaves no partial live vocabulary"
  else
    bad "16j a dead live reader did not redden the guard:
$out"
  fi
else
  bad "16g could not build the live cli.py mutants, so the live-reader cases did not run"
fi

# ---------------------------------------------------------------------------
# CASE 17 (B2) — the import-derived dependency edges are load-bearing.
#
# Round 1 relied on hand-written area couples, two of which were claimed in the
# write-up and absent from the manifest; 29 journeys importing connection-core
# production types were not selected by a connection-core change. Mutation:
# neuter the import scan so every class depends only on its own area. I8's
# independent re-scan and the D28 pins must both redden.
# ---------------------------------------------------------------------------
sed -i "s|import\[\[:space:\]\]+com\\\\.pocketshell\\\\.|import[[:space:]]+zz_no_such_package_zz\\\\.|" \
  "$MUTDIR/lib/test-areas.sh"
# PROVE THE MUTANT IS LIVE before reading anything into the result. #1641 spent a
# round on a mutation that had landed inside a KDoc block and killed nothing —
# "the mutation survived" and "the mutation never happened" look identical.
if grep -q 'zz_no_such_package_zz' "$MUTDIR/lib/test-areas.sh" &&
   [[ "$(POCKETSHELL_TEST_AREAS_REPO_ROOT="$SCRIPT_DIR/.." \
         POCKETSHELL_TEST_AREAS_MANIFEST="$MUTDIR/test-areas.txt" \
         POCKETSHELL_TEST_AREAS_JOURNEY_SUITE="$MUTDIR/ci-journey-suite.sh" \
         bash "$MUTDIR/select-test-areas.sh" --verify-manifest 2>&1 |
         grep -c 'import lines scanned = 0')" -eq 1 ]]; then
  : # mutant confirmed live: zero import lines reach the dependency index
else
  bad "17 MUTATION DID NOT APPLY — the import scan is still live in the copy, so 17's verdict would be meaningless"
fi
out="$(run_mut --coverage-invariant --only I8,I9)"
if grep -q 'FAIL I8' <<<"$out" && grep -qE 'ForwardingNetworkRideThroughE2eTest|ConnectionLogHostMirrorReconnectDockerTest|Issue1876FolderListMobileRttDockerTest' <<<"$out"; then
  ok "17 disabling the import-derived deps reddens I8, naming the escaping journeys"
else
  bad "17 the escape scan survived a disabled import derivation:\n$(grep -E '^(OK|FAIL) I[89]' <<<"$out")"
fi
cp "$SCRIPT_DIR/lib/test-areas.sh" "$MUTDIR/lib/test-areas.sh"   # restore

# ---------------------------------------------------------------------------
# CASE 18 (B3) — the emitted `--tests` filter must BE the plan.
#
# Round 1 emitted `com.pocketshell.app.*Test`; Gradle's `*` crosses package
# dots, so the command ran all 485 :app unit classes while the plan reported a
# fraction of them. Mutation: reinstate that wildcard. I10 must redden.
# ---------------------------------------------------------------------------
filters="$(printf 'app/src/main/java/com/pocketshell/app/usage/UsageScreen.kt\n' |
  bash "$SELECT" --changed-stdin --print-plan-only 2>/dev/null |
  sed -n 's/^UNIT_GRADLE_FILTERS=//p')"
if [[ -n "$filters" && "$filters" != *"*"* ]]; then
  ok "18a the emitted :app filter is exact class names, with no glob metacharacter"
else
  bad "18a emitted filter still contains a wildcard: $filters"
fi
sed -i 's|^      app_unit+=("$fqcn")$|      app_unit+=("com.pocketshell.app.*Test")|' \
  "$MUTDIR/select-test-areas.sh"
grep -q 'com.pocketshell.app.\*Test' "$MUTDIR/select-test-areas.sh" ||
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
# would only prove the parser parses. Only the exemption file (and, for 21f, the
# nightly suite) is swapped for a mutated copy; everything else is the shipped
# guard reading the shipped tree. The verdict is read from the specific FAIL
# line, not the exit code, since the real tree emits many other checks.
# 21k–21m (#2170) also plant a tracked non-source via a private index copy.
# ---------------------------------------------------------------------------
UNCONV_REAL="$SCRIPT_DIR/test-unconventional-test-files.txt"
UNCONVDIR="$SANDBOX/unconventional"
mkdir -p "$UNCONVDIR"

run_unconv() {  # $1 = exemption file, $2 = nightly suite (optional)
  POCKETSHELL_TEST_AREAS_UNCONVENTIONAL="$1" \
  POCKETSHELL_TEST_AREAS_NIGHTLY_SUITE="${2:-$SCRIPT_DIR/nightly-extensive-suite.sh}" \
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
  if grep -q 'DesignRenders' "$UNCONVDIR/no-designrenders.txt"; then
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

  # 21d — The executor claim must match the path. Claiming the unit source set
  # for an androidTest file would assert `./gradlew test` runs it, which is the
  # exact false "it executes somewhere" this guard is supposed to refuse.
  sed 's|\(TerminalHotkeysPanelScreenshotHarness.kt\t\)nightly-connected|\1unit-source-set|' \
    "$UNCONV_REAL" > "$UNCONVDIR/wrong-executor.txt"
  out="$(run_unconv "$UNCONVDIR/wrong-executor.txt")"
  if grep -q "executor 'unit-source-set' but the path is not under \*/src/test/" <<<"$out"; then
    ok "21d claiming the unit source set for an androidTest path reddens"
  else
    bad "21d a false unit-source-set executor claim survived:\n$(grep -E 'exemption' -A3 <<<"$out")"
  fi

  # 21e — An executor the guard cannot check is rejected, not believed. This is
  # the "I could not check" != "I checked and it is fine" rule.
  sed 's|\(TerminalHotkeysPanelScreenshotHarness.kt\t\)nightly-connected|\1runs-somewhere-trust-me|' \
    "$UNCONV_REAL" > "$UNCONVDIR/unknown-executor.txt"
  out="$(run_unconv "$UNCONVDIR/unknown-executor.txt")"
  if grep -q "unknown executor 'runs-somewhere-trust-me'" <<<"$out"; then
    ok "21e an unverifiable executor claim is rejected rather than believed"
  else
    bad "21e an unknown executor claim survived:\n$(grep -E 'exemption' -A3 <<<"$out")"
  fi

  # 21f — `nightly-connected` rests on nightly phase 1 running
  # :app:connectedDebugAndroidTest WHOLESALE. The moment a class is named in the
  # nightly suite (exclusion, shard pin, even a comment) that claim stops being
  # inherited and has to be re-argued. Fail-closed on the simple name.
  cp "$SCRIPT_DIR/nightly-extensive-suite.sh" "$UNCONVDIR/nightly.sh"
  printf '\n# MUTANT 21f\nJOURNEY_EXCLUDED_CLASSES+=("$FQCN_PREFIX.TerminalHotkeysPanelScreenshotHarness")\n' \
    >> "$UNCONVDIR/nightly.sh"
  if ! grep -q 'MUTANT 21f' "$UNCONVDIR/nightly.sh"; then
    bad "21f MUTATION DID NOT APPLY — the nightly exclusion is not live in the copy"
  fi
  out="$(run_unconv "$UNCONV_REAL" "$UNCONVDIR/nightly.sh")"
  if grep -q "executor 'nightly-connected' claims the wholesale nightly run reaches it" <<<"$out" &&
     grep -q 'TerminalHotkeysPanelScreenshotHarness' <<<"$out"; then
    ok "21f excluding an exempted harness from the nightly wholesale run reddens its executor claim"
  else
    bad "21f a harness excluded from nightly kept its 'nightly-connected' claim:\n$(grep -E 'exemption' -A3 <<<"$out")"
  fi

  # 21i / 21j — The PREMISE under `nightly-connected`, not just the per-row
  # check. "The class is not excluded" only implies "it runs" while phase 1 is a
  # WHOLESALE run minus a notClass list. Flip phase 1 to an allowlist, or drop
  # the subtraction, and every row would silently start lying with the per-row
  # check still green — the G6 shape where the assertion survives the bug.
  sed 's|\(-Pandroid.testInstrumentationRunnerArguments.notClass="\$JOURNEY_NOTCLASS_ARG" \\\)|\1\n  -Pandroid.testInstrumentationRunnerArguments.class="com.pocketshell.app.proof.Allowlisted" \\|' \
    "$SCRIPT_DIR/nightly-extensive-suite.sh" > "$UNCONVDIR/nightly-allowlist.sh"
  if [[ "$(sed -n '/phase 1: journey\/E2E/,/^JOURNEY_EXIT=/p' "$UNCONVDIR/nightly-allowlist.sh" |
           grep -c 'RunnerArguments.class=')" -ne 1 ]]; then
    bad "21i MUTATION DID NOT APPLY — phase 1 in the copy is not an allowlist, so 21i's verdict would be meaningless"
  fi
  out="$(run_unconv "$UNCONV_REAL" "$UNCONVDIR/nightly-allowlist.sh")"
  if grep -q 'phase 1 now restricts what it runs' <<<"$out"; then
    ok "21i turning nightly phase 1 into a class= allowlist reddens every nightly-connected row's premise"
  else
    bad "21i phase 1 became an allowlist and the nightly-connected rows stayed green:\n$(grep -E 'exemption' -A3 <<<"$out")"
  fi

  sed '/phase 1: journey\/E2E/,/^JOURNEY_EXIT=/ s|^  -Pandroid.testInstrumentationRunnerArguments.notClass=.*|  \\|' \
    "$SCRIPT_DIR/nightly-extensive-suite.sh" > "$UNCONVDIR/nightly-noexclude.sh"
  if [[ "$(sed -n '/phase 1: journey\/E2E/,/^JOURNEY_EXIT=/p' "$UNCONVDIR/nightly-noexclude.sh" |
           grep -c 'RunnerArguments.notClass=')" -ne 0 ]]; then
    bad "21j MUTATION DID NOT APPLY — phase 1 in the copy still subtracts a notClass list"
  fi
  out="$(run_unconv "$UNCONV_REAL" "$UNCONVDIR/nightly-noexclude.sh")"
  if grep -q 'phase 1 no longer runs :app:connectedDebugAndroidTest minus a notClass list' <<<"$out"; then
    ok "21j losing the wholesale-minus-notClass shape reddens the nightly-connected premise"
  else
    bad "21j the wholesale premise survived losing its shape:\n$(grep -E 'exemption' -A3 <<<"$out")"
  fi

  # 21g — The gate must be a class the registries can actually see. Resolved
  # through the SAME class index the area manifest and the ledger use, so
  # "the real assertion lives over there" cannot point at a name that does not
  # exist, or at another invisible file.
  sed 's|com.pocketshell.app.tmux.TerminalHotkeysPanelNoTruncationTest|com.pocketshell.app.tmux.TotallyFineGateTest|' \
    "$UNCONV_REAL" > "$UNCONVDIR/ghost-gate.txt"
  out="$(run_unconv "$UNCONVDIR/ghost-gate.txt")"
  if grep -q "gate 'com.pocketshell.app.tmux.TotallyFineGateTest' is not a known test class" <<<"$out"; then
    ok "21g a gate naming a class no registry knows reddens"
  else
    bad "21g a ghost gate class survived:\n$(grep -E 'exemption' -A3 <<<"$out")"
  fi

  # 21h — An `enumerated-by:` registry must actually enumerate THIS file.
  sed 's|enumerated-by:scripts/render.sh|enumerated-by:scripts/ci-journey-suite.sh|' \
    "$UNCONV_REAL" > "$UNCONVDIR/wrong-enumerator.txt"
  out="$(run_unconv "$UNCONVDIR/wrong-enumerator.txt")"
  if grep -q 'does not reference this path, so it cannot be enumerating it' <<<"$out"; then
    ok "21h an enumerated-by: script that never mentions the file reddens"
  else
    bad "21h a bogus enumerated-by: claim survived:\n$(grep -E 'exemption' -A3 <<<"$out")"
  fi

  # -------------------------------------------------------------------------
  # #2170 — a nightly-connected / unit-source-set row is a claim that the
  # file EXECUTES in that lane. Path-prefix matching accepts any file under
  # the source-set directory, including a parked `*.kt.txt` / `*.kt.turned-off`
  # that Gradle never compiles. The rows below are the currently-passing
  # lie: they are green on a prefix-only matcher and must be red once the
  # guard requires a compiling Kotlin/Java source.
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
package com.pocketshell.app.proof
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
    POCKETSHELL_TEST_AREAS_NIGHTLY_SUITE="$SCRIPT_DIR/nightly-extensive-suite.sh" \
    GIT_INDEX_FILE="$ISSUE2170_INDEX" \
    bash "${2:-$SELECT}" --verify-manifest 2>&1
  }

  ISSUE2170_NIGHTLY_PATH="app/src/androidTest/java/com/pocketshell/app/proof/Issue2170NonCompilingParked.kt.txt"
  ISSUE2170_UNIT_PATH="shared/ui-kit/src/test/java/com/pocketshell/uikit/render/Issue2170NonCompilingParked.kt.turned-off"
  ISSUE2170_GATE="com.pocketshell.app.composer.Issue1622ComposerSheetGeometryProofTest"
  plant_hidden_unconventional "$ISSUE2170_NIGHTLY_PATH"

  {
    cat "$UNCONV_REAL"
    printf '%s\tnightly-connected\t%s\tparked non-source — must be rejected (#2170)\n' \
      "$ISSUE2170_NIGHTLY_PATH" "$ISSUE2170_GATE"
  } > "$UNCONVDIR/parked-nightly.txt"
  if ! grep -Fq "$ISSUE2170_NIGHTLY_PATH" "$UNCONVDIR/parked-nightly.txt"; then
    bad "21k MUTATION DID NOT APPLY — the parked nightly-connected row is missing from the copy"
  fi
  out="$(run_unconv_planted "$UNCONVDIR/parked-nightly.txt")"
  if grep -Fq "$ISSUE2170_NIGHTLY_PATH" <<<"$out" &&
     grep -q "executor 'nightly-connected' but the file is not a compiling Kotlin/Java source" <<<"$out"; then
    ok "21k a nightly-connected row for a parked .kt.txt under app/src/androidTest/ reddens (prefix-only matching is the lie)"
  else
    bad "21k a nightly-connected row for a non-compiling androidTest path survived (the #2170 currently-passing case):\n$(grep -E 'convention|exemption' -A3 <<<"$out")"
  fi

  # 21m — G6: a prefix-only matcher must redden 21k. Restore the old
  # `is_compiling_test_source` (always-true ≡ path-prefix only) on a private
  # copy and assert the planted nightly-connected .kt.txt is ACCEPTED. If it
  # is still rejected, 21k is red for the wrong reason (stale / gate / hidden)
  # and the new assertion is decorative. Runs while the nightly plant is
  # still the only extra tracked file.
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
        POCKETSHELL_TEST_AREAS_JOURNEY_SUITE="$prefix_only/ci-journey-suite.sh" \
        POCKETSHELL_TEST_AREAS_UNCONVENTIONAL="$UNCONVDIR/parked-nightly.txt" \
        POCKETSHELL_TEST_AREAS_NIGHTLY_SUITE="$prefix_only/nightly-extensive-suite.sh" \
        GIT_INDEX_FILE="$ISSUE2170_INDEX" \
        bash "$prefix_only/select-test-areas.sh" --verify-manifest 2>&1
      )"
      if grep -q "executor 'nightly-connected' but the file is not a compiling Kotlin/Java source" <<<"$out"; then
        bad "21m prefix-only matcher still rejected the parked .kt.txt — 21k is red for another reason:\n$(grep -E 'convention|exemption' -A3 <<<"$out")"
      elif grep -q "OK: no new @Test-bearing file outside" <<<"$out"; then
        ok "21m a prefix-only matcher accepts the parked nightly-connected .kt.txt (the mutation that reddens 21k)"
      else
        bad "21m prefix-only matcher did not cleanly accept the parked row (verdict is not the shipped-list OK):\n$(grep -E 'convention|exemption|FAIL' <<<"$out")"
      fi
    fi
  fi

  # 21l — the mirror: unit-source-set + */src/test/ + a file Gradle will not
  # compile. Same shape, other executor. A prefix-only `*/src/test/*` matcher
  # accepts it. Swap the plant so the extra tracked file is this one only.
  unplant_hidden_unconventional "$ISSUE2170_NIGHTLY_PATH"
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
fi

echo
echo "select-test-areas selftest: $PASS passed, $FAIL failed"
[[ "$FAIL" -eq 0 ]] || exit 1
exit 0
