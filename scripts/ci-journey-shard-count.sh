#!/usr/bin/env bash
# Issue #2060 (CI Phase A): print the emulator-journey shard count, parsed from
# the workflow matrix itself.
#
# WHY THIS EXISTS. Three independent places must agree on how many emulator
# shards there are:
#
#   1. `.github/workflows/tests.yml` -> emulator-journey `matrix.shard`
#   2. the same job's `POCKETSHELL_JOURNEY_CI_SHARD_TOTAL` env (the suite
#      partitions JOURNEY_CLASSES by `hash % total`, so a _TOTAL that is LESS
#      than the matrix length means the extra shards duplicate work, and a
#      _TOTAL that is GREATER means some classes are SELECTED BY NOBODY and
#      silently never run — a vacuous green of the worst kind)
#   3. the aggregation job's `EXPECTED_SHARDS` (a count that is too low lets a
#      missing shard's verdict go unnoticed)
#
# Until #2060 all three were kept aligned by hand-written "keep in sync"
# comments, and the guards that checked them pinned the LITERAL 3. Going 3 -> 6
# therefore meant editing the pins to a new literal, which just re-arms the same
# drift for the next change. This helper makes the matrix the single source of
# truth: the guards ask for the count and cross-check the other two sites
# against it, so the assertion stays load-bearing at any N. It is also what
# scripts/test-ci-journey-budget.sh's 125% balance cap, its (shard) end-to-end
# acceptance block, and test-ci-journey-warm-build.sh's per-shard #1814 loop
# read, so each polices the SHIPPED configuration rather than a total CI no
# longer runs. scripts/check-journey-shard-literals.sh is the partial backstop:
# a downward-only ratchet that reddens when a literal reappears in one of three
# recognised shapes (a shard-total noun assigned a number, a `${VAR:-N}` default
# pin, a 0-based literal loop enumeration). It is a grep over named shapes, not
# a proof that no literal exists — its own header states what it cannot see.
#
# ---------------------------------------------------------------------------
# HOW THE SHIPPED COUNT (9) WAS CHOSEN — the full derivation, kept here rather
# than in the workflow because this file is what owns the number. The 6-shard
# derivation is kept below verbatim (it is still the reasoning FRAMEWORK); the
# #2377 section at the end is why the answer moved 6 -> 9.
#
# --- (1) the #2060 derivation that picked 6, kept as the METHOD ------------
# Historical in its numbers (registry of 173 entries, run 31292976490) but not in
# its method: every claim below is how the count is still chosen. Read it first,
# then section (2) for why the same method now answers 9.
#
# The critical path across a matrix is the MAX leg, not the mean, and the max is
# set by TIME, not class count: membership is by class-name hash (#1862) and
# per-class cost spans ~6s..~363s, so legs are far less equal in seconds than in
# count. Driving the PRODUCTION selector (select_effective_journey_classes) over
# the real JOURNEY_CLASSES array, with each entry weighted by its measured cost
# from `main` run 31292976490 (173 entries, 7206s of instrumentation total):
#
#   total  classes per leg               instrumentation per leg (s)        MAX
#     3    61/56/56                      2716/1913/2577                    2716s
#     4    39/41/40/53                   1957/1668/1822/1759               1957s
#     5    37/34/29/38/35                1455/1304/1449/1766/1232          1766s
#     6    26/27/24/35/29/32             1378/785/1273/1338/1128/1304      1378s
#     7    19/21/20/25/40/25/23          811/1111/800/947/1655/974/908     1655s
#     8    24/21/16/24/15/20/24/29       1295/860/1030/829/662/808/792/930 1295s
#     9    19/19/16/19/14/19/23/23/21    1240 max (760/655/514/716/370/    1240s
#                                        1015/1240/888/1048)
#
# The trap that made an earlier 4-shard proposal wrong: at total=4 the heaviest
# leg by COUNT (53 classes) is NOT the heaviest by TIME (the 39-class leg 0, at
# 1957s). A count-based estimate therefore picks the wrong leg AND the wrong
# number. The cost model validates: at total=3 it predicts 60.4/47.0/58.1 min
# against the baseline's actual 60.7/47.0/57.7, and the per-leg residual (fixed
# overhead) is 906s +/- 23s while instrumentation varies by 42% across legs.
#
# SIX IS A CHOSEN TRADE-OFF, NOT THE MEASURED OPTIMUM. The ladder is not
# monotonic — 7 is genuinely WORSE than 6 (hash clustering drops 40 classes and
# 1655s on one leg, and it fails #1850 AC2 below) — but it does not stop
# improving at 6 either: 8 (1295s max leg, tightest headroom 571s) and 9 (1240s,
# 626s) both beat 6 on max leg AND both still clear #1850's bar. Six is chosen
# because it is the FIRST point that clears every bar, and because what more
# buys is small and what it costs is not: 8 saves ~1.4 min of max-leg wall clock
# (36.7 vs 38.1 min, since the ~908s fixed per-leg overhead dominates from here)
# for +2 concurrent runners against an explicitly budgeted 20-job Actions
# ceiling. Do not read this as "adding shards no longer helps" — read it as
# "past 6 the wall-clock return per runner stops being worth it".
#
# Fixed per-leg overhead, measured: warm build ~480s + intra-suite ~200s + job
# setup ~230s ~= 15 min. So the max leg is 60.4 -> 47.8 -> 44.6 -> 38.1 min for
# total 3/4/5/6, and `main` (dex ~6.3 + max leg + aggregate verdict ~0.3) is
# ~67 -> ~54 -> ~51 -> ~44 min. The <=55 min target is missed by 3 shards, sat
# essentially ON by 4, and cleared by 5 and 6.
#
# #1850 (cold-boot retry affordability) is what picks 6 over 5, and it is worth
# being exact about WHY, because "every leg reports retry_affordable=true" is
# NOT #1850's bar. Its AC2 is:
#
#   "Every shard's post-change drift headroom is measured and is larger than the
#    cost of one twice-failing journey class on that shard"   (body scale ~420s)
#
# Driving the PRODUCTION scripts/ci-journey-retry-budget.sh — which reproduces
# all three baseline shards' published required/remaining byte-for-byte — with
# the WORST observed value of every fixed constant, and MEASURING headroom by
# adding instrumentation to a leg until the script flips the verdict:
#
#   total=3  all three legs affordable=false (as observed on real runs)
#   total=4  leg 0 needs 49.9 min vs 46.7 remaining -> STILL false
#   total=5  every leg affordable, but headroom 411/562/417/100/634 s —
#            THREE legs under the ~420s bar, and the 100s leg is the one hosting
#            the 330s ReconnectStormLivelockE2eTest, so a single in-suite flake
#            of that class puts it straight back to
#            insufficient_remaining_budget. #1850 documents exactly this
#            anti-correlation: the run that needs the retry is the run that
#            cannot afford it. Five is "affordable when nothing goes wrong",
#            which #1850 explicitly rejects as not-resilience.
#   total=6  headroom 488/1081/593/528/738/562 s — EVERY leg over the bar, and
#            every leg survives its own heaviest class running twice (leg 0
#            carries the 330s storm test and still has 488s).
#   total=7  headroom 211s on leg 4 — NOT MET, and that leg cannot afford its
#            own heaviest class twice. Seven fails #1850 outright.
#   total=8  571s   total=9  626s — both MET, see the trade-off note above.
#
# So 6, not 5, is what actually closes #1850 AC2. Growth headroom at 6 is ~488s
# on the tightest leg (~35% of that leg's instrumentation) before the retry
# becomes unaffordable again; that is the number to watch as the registry grows.
#
# HONEST CAVEAT, because #1850 AC2 says MEASURED. Everything above is arithmetic:
# the PRODUCTION selector and the PRODUCTION retry-budget script, but driven over
# one run's (31292976490) per-class costs plus worst-case fixed constants — a
# projection, not an observation of six shards. It is a well-grounded one (the
# model reproduces all three of that run's published required/remaining values to
# under 10 ms, and the constants used are at or beyond the worst observed), and
# it is the strongest evidence obtainable before six shards have ever run. But
# the genuinely measured per-shard `retry_remaining_ms` at total=6 only exists
# after the first 6-shard `main` run. Read those numbers before treating #1850's
# AC2 as observed rather than projected.
#
# --- (2) issue #2377: the same method now answers 9 -------------------------
# WHY IT MOVED. #2060's own closing line — "growth headroom at 6 is ~488s on the
# tightest leg; that is the number to watch as the registry grows" — is exactly
# what came due. The registry kept growing, and two classes added in the current
# cycle (FolderSessionRowNavigatorTest 39s from #2380 and
# Issue2377MultiSocketSessionListDockerTest 61s) pushed shard 3's #1850 margin to
# 350757ms — UNDER the ~420s twice-failing-class bar. The #1850 hole reopens one
# leg at a time as the list grows; it does not announce itself.
#
# THE ONLY LEVER IS THE TOTAL. Membership is by class-name hash (#1862), which
# forbids rebalancing by renaming or reordering classes precisely so a hot leg
# cannot be "fixed" into someone else's leg. Deferring or deleting a class is not
# a balance fix either. So the question is only which total clears the bar.
#
# TWO BARS, NOT ONE, and they disagree. This is the same "heaviest leg by COUNT
# is not the heaviest by TIME" trap section (1) records, now biting in the other
# direction — so BOTH shipped-configuration guards have to be satisfied:
#
#   #1850 TIME bar   — scripts/test-ci-journey-retry-budget.sh (aa2): every
#     leg's retry margin > 420000ms. Weighted by measured per-class SECONDS.
#   #835 COUNT bar   — scripts/test-ci-journey-budget.sh (shard-balance): no leg
#     over 125% of the ideal CLASS COUNT, plus a 3-sigma uniform band. Unweighted.
#
# MEASURED, by re-driving the PRODUCTION selector (and, for the time bar, the
# PRODUCTION scripts/ci-journey-retry-budget.sh) over the CURRENT 224-class
# registry and the current per-class fixture
# (scripts/fixtures/ci-journey-run-31961310072-class-seconds.tsv), i.e. exactly
# what those two cases assert, with the matrix swapped for each candidate total:
#
#   total  min #1850 margin (ms)  worst leg (s)  worst count / cap   verdict
#     3    -2767743               3396            79 / 94            FAIL time
#     5     -201543               2174            49 / 56            FAIL time
#     6      350757               1911            43 / 47            FAIL time  (shipped until now)
#     7      142857               2010            50 / 40            FAIL both
#     8     1022757               1591            38 / 35            FAIL count
#     9     1539357               1345            28 / 32            PASS
#
# SEVEN IS STILL WORSE THAN SIX, for the same reason #2060 recorded: the hash
# clusters differently at every total, and at 7 it lands a 2010s / 50-class leg
# that fails both bars. The ladder is not monotonic in EITHER metric — 10 and 12
# fail the count cap again while 11 passes — so "just add one shard" is not a
# valid move; each candidate has to be driven through the selector, which is
# what the table above is.
#
# EIGHT LOOKS RIGHT AND IS NOT. It clears #1850 comfortably (1022757ms) and has
# a lighter worst leg in SECONDS than 9 does in the tail, but its shard 7 draws
# 38 of 224 classes against a 35 cap. Shipping 8 would have been green on the
# retry-budget guard and red on the balance guard — the exact pair of numbers to
# check together, not one at a time.
#
# NINE, on #2060's own rule: the FIRST total that clears EVERY bar. It also has
# the better-conditioned count margin (28 vs a 32 cap, i.e. 4 classes of growth
# headroom, against 11's single class) and takes the heaviest leg from 1911s to
# 1345s. Peak `main`-push concurrency goes to ~13 of the budgeted 20 Actions
# jobs. Headroom to watch next: 1539357ms of time margin, and 4 classes of count
# margin on shard 8 — the COUNT bar is now the binding one, so it is what will
# trip first as the registry grows. When it does, re-run BOTH ladders.
#
# The SAME honest caveat as above applies: this table is arithmetic over the
# production selector and the production budget helper, not an observation of
# nine shards, and the per-class costs for the two newest rows are local-emulator
# measurements (see the TSV's own notes) rather than GitHub Actions attempt-1
# elapsed times. Refresh both once they have run there.
#
# It fails CLOSED. An unreadable, missing, empty, or non-contiguous matrix is an
# error, never a silent default — a helper that guessed would turn every caller
# into a vacuous check.
#
# Usage:  scripts/ci-journey-shard-count.sh [WORKFLOW]
#         scripts/ci-journey-shard-count.sh --self-test
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd -P)"
DEFAULT_WORKFLOW="$REPO_ROOT/.github/workflows/tests.yml"

die() { echo "ci-journey-shard-count: $*" >&2; exit 1; }

shard_count() {
  local workflow="$1"
  [[ -f "$workflow" ]] || die "workflow not found: $workflow"

  # Slice the emulator-journey job so a matrix belonging to some other job (now
  # or later) can never be read by mistake.
  local job
  job="$(
    awk '
      /^  emulator-journey:$/            { in_job = 1; next }
      in_job && /^  [A-Za-z0-9_-]+:$/    { in_job = 0 }
      in_job                             { print }
    ' "$workflow"
  )"
  [[ -n "$job" ]] || die "could not locate the emulator-journey job in $workflow"

  local raw
  raw="$(printf '%s\n' "$job" | sed -n 's/^ *shard: *\[\(.*\)\] *$/\1/p')"
  [[ -n "$raw" ]] \
    || die "emulator-journey has no inline 'shard: [...]' matrix in $workflow"
  [[ "$(printf '%s\n' "$raw" | wc -l)" -eq 1 ]] \
    || die "emulator-journey declares more than one 'shard: [...]' matrix"

  local -a shards=()
  local token
  IFS=',' read -r -a shards <<<"$raw"
  local i=0
  for token in "${shards[@]}"; do
    token="${token//[[:space:]]/}"
    [[ "$token" =~ ^[0-9]+$ ]] \
      || die "non-numeric shard id '$token' in matrix [$raw]"
    # The suite selects with `hash % total == index`, so the ids MUST be exactly
    # 0..N-1. A gap or a duplicate silently drops or doubles a slice.
    (( token == i )) \
      || die "shard matrix must be contiguous 0..N-1, got [$raw] (expected $i at position $i)"
    (( i++ ))
  done
  (( i > 0 )) || die "empty shard matrix in $workflow"
  printf '%s\n' "$i"
}

self_test() {
  local sandbox checks=0
  sandbox="$(mktemp -d)"
  # EXIT, not RETURN: `die` exits the whole script, so a RETURN trap would leak
  # the sandbox on exactly the paths the self-test is exercising.
  # shellcheck disable=SC2064
  trap "rm -rf '$sandbox'" EXIT

  write_wf() {
    printf 'jobs:\n  other-job:\n    strategy:\n      matrix:\n        shard: [0, 1, 2, 3, 4, 5, 6]\n  emulator-journey:\n    strategy:\n      matrix:\n        shard: [%s]\n  emulator-journey-verdict:\n    steps: []\n' \
      "$1" > "$sandbox/wf.yml"
  }

  expect_count() {   # $1 = matrix body, $2 = expected count
    write_wf "$1"
    local got
    got="$(shard_count "$sandbox/wf.yml")" \
      || { echo "FAIL: self-test could not parse matrix [$1]" >&2; exit 1; }
    [[ "$got" == "$2" ]] \
      || { echo "FAIL: matrix [$1] parsed as $got, expected $2" >&2; exit 1; }
    checks=$((checks + 1))
  }

  expect_reject() {  # $1 = matrix body
    write_wf "$1"
    # `die` calls `exit`, which would terminate this SCRIPT even from an `if`
    # condition — the subshell is what makes the rejection observable instead of
    # silently ending the self-test early (and reading as a pass-shaped exit).
    if ( shard_count "$sandbox/wf.yml" ) >/dev/null 2>&1; then
      echo "FAIL: self-test ACCEPTED an invalid matrix [$1]" >&2
      exit 1
    fi
    checks=$((checks + 1))
  }

  expect_count "0, 1, 2" 3
  expect_count "0, 1, 2, 3" 4
  expect_count "0" 1
  # Fail-closed cases. Each of these, if it silently returned a number, would
  # make every caller's cross-check vacuous.
  expect_reject "0, 1, 3"        # gap -> a hash bucket nobody runs
  expect_reject "1, 2, 3"        # does not start at 0
  expect_reject "0, 1, 1"        # duplicate
  expect_reject "0, x"           # non-numeric
  expect_reject ""               # empty

  # A missing emulator-journey job must be an error, not a default.
  printf 'jobs:\n  unit:\n    steps: []\n' > "$sandbox/wf.yml"
  if ( shard_count "$sandbox/wf.yml" ) >/dev/null 2>&1; then
    echo "FAIL: self-test ACCEPTED a workflow with no emulator-journey job" >&2
    exit 1
  fi
  checks=$((checks + 1))

  # The real workflow must parse.
  local real
  real="$(shard_count "$DEFAULT_WORKFLOW")" || exit 1
  (( real >= 3 )) || { echo "FAIL: real workflow reports $real shards" >&2; exit 1; }
  checks=$((checks + 1))

  (( checks == 10 )) \
    || { echo "FAIL: self-test ran $checks checks, expected 10" >&2; exit 1; }
  echo "PASS: ci-journey-shard-count self-test ($checks checks; real workflow = $real shards)"
}

case "${1:-}" in
  --self-test) self_test ;;
  --*)         die "usage: ci-journey-shard-count.sh [WORKFLOW | --self-test]" ;;
  *)           shard_count "${1:-$DEFAULT_WORKFLOW}" ;;
esac
