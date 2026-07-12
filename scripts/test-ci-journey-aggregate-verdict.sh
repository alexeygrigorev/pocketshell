#!/usr/bin/env bash
# Issue #1458: focused, JVM-free fixture test for the emulator-journey aggregate
# verdict (scripts/ci-journey-aggregate-verdict.sh) plus workflow-parity pins for
# the per-shard token-writing + aggregation wiring in tests.yml.
#
# The load-bearing NEW behavior this issue adds is the aggregation: three
# independent per-shard classify outcomes must roll up into ONE overall verdict,
# and — critically — a GENUINE journey failure must still turn the run RED while
# an all-infra flake run turns RE-RUN (neutral) so no false red-CI email fires.
# This test drives the REAL aggregation script with fixture verdict files and
# proves each case (CLEAN / RE-RUN / RED) red→green WITHOUT a live emulator.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AGG="$SCRIPT_DIR/ci-journey-aggregate-verdict.sh"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKFLOW="$REPO_ROOT/.github/workflows/tests.yml"
BUDGET_FN="$SCRIPT_DIR/ci-journey-budget-functions.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

[[ -f "$AGG" ]] || fail "cannot find ci-journey-aggregate-verdict.sh at $AGG"
chmod +x "$AGG"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

# make_dir <name> — a fresh per-run verdict dir under the sandbox.
make_dir() {
  local d="$SANDBOX/$1"
  rm -rf "$d"; mkdir -p "$d"
  echo "$d"
}

# write_shard <dir> <shard-index> <token> — model download-artifact's per-shard
# subdir layout: artifacts/.../emulator-journey-verdict-shard-<N>/shard-verdict.txt
write_shard() {
  local dir="$1" idx="$2" token="$3"
  local sub="$dir/emulator-journey-verdict-shard-$idx"
  mkdir -p "$sub"
  printf '%s\n' "$token" > "$sub/shard-verdict.txt"
}

# run_agg <dir> <expected-shards> — run the real aggregation, capture verdict +
# exit code into globals AGG_OUT / AGG_RC / AGG_VERDICT.
run_agg() {
  local dir="$1" expected="$2"
  set +e
  AGG_OUT="$(EXPECTED_SHARDS="$expected" GITHUB_STEP_SUMMARY="" bash "$AGG" "$dir" 2>&1)"
  AGG_RC=$?
  set -e
  AGG_VERDICT="$(printf '%s\n' "$AGG_OUT" | sed -n 's/^AGGREGATE_VERDICT=//p' | tail -n 1)"
}

echo "== #1458 aggregation fixture cases =="

# (a) all-green -> CLEAN (exit 0). This is the trustworthy-run baseline.
d="$(make_dir all-green)"
write_shard "$d" 0 CLEAN; write_shard "$d" 1 CLEAN; write_shard "$d" 2 CLEAN
run_agg "$d" 3
[[ "$AGG_VERDICT" == "CLEAN" && "$AGG_RC" -eq 0 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(a) all-green expected CLEAN/exit0, got $AGG_VERDICT/exit$AGG_RC"; }
pass "(a) all shards CLEAN -> CLEAN (green, exit 0)"

# (b) all-infra-storm -> RE-RUN (exit 0, NOT red). This is the #1458 core: a
#     console-storm run must NOT fire a false red-CI email.
d="$(make_dir all-infra)"
write_shard "$d" 0 INFRA; write_shard "$d" 1 INFRA; write_shard "$d" 2 INFRA
run_agg "$d" 3
[[ "$AGG_VERDICT" == "RE-RUN" && "$AGG_RC" -eq 0 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(b) all-infra expected RE-RUN/exit0, got $AGG_VERDICT/exit$AGG_RC"; }
grep -q '::warning' <<<"$AGG_OUT" \
  || fail "(b) an all-infra RE-RUN must emit a ::warning (re-run signal), not silence"
grep -q '::error' <<<"$AGG_OUT" \
  && fail "(b) an all-infra RE-RUN must NOT emit a ::error (that would fire a false red-CI email)"
pass "(b) all shards INFRA-storm -> RE-RUN (neutral green, exit 0, no false red)"

# (c) one real journey failure + infra elsewhere -> RED (exit 1). The
#     load-bearing property: a genuine regression is NEVER masked by infra.
d="$(make_dir one-red)"
write_shard "$d" 0 INFRA; write_shard "$d" 1 RED; write_shard "$d" 2 CLEAN
run_agg "$d" 3
[[ "$AGG_VERDICT" == "RED" && "$AGG_RC" -eq 1 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(c) one RED + infra expected RED/exit1, got $AGG_VERDICT/exit$AGG_RC"; }
grep -q '::error' <<<"$AGG_OUT" \
  || fail "(c) a RED verdict must emit a ::error so the run turns red"
pass "(c) one genuine RED (+infra/clean siblings) -> RED (exit 1) — real regression NOT masked"

# (c') the red→green control: the SAME shape but with the RED shard flipped to
#     INFRA must drop to RE-RUN — proving it was the RED token, not the infra,
#     that drove the red (the G6 load-bearing-assertion guard).
d="$(make_dir one-red-control)"
write_shard "$d" 0 INFRA; write_shard "$d" 1 INFRA; write_shard "$d" 2 CLEAN
run_agg "$d" 3
[[ "$AGG_VERDICT" == "RE-RUN" && "$AGG_RC" -eq 0 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(c') flipping the RED shard to INFRA must yield RE-RUN/exit0, got $AGG_VERDICT/exit$AGG_RC"; }
pass "(c') control: RED->INFRA flip drops to RE-RUN — the RED token is the load-bearing signal"

# (d) CLEAN + INFRA mix (no RED) -> RE-RUN: one healthy shard does not clean a
#     run where another shard was environmental; a re-run is still needed.
d="$(make_dir clean-infra)"
write_shard "$d" 0 CLEAN; write_shard "$d" 1 INFRA; write_shard "$d" 2 CLEAN
run_agg "$d" 3
[[ "$AGG_VERDICT" == "RE-RUN" && "$AGG_RC" -eq 0 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(d) clean+infra expected RE-RUN/exit0, got $AGG_VERDICT/exit$AGG_RC"; }
pass "(d) CLEAN+INFRA (no RED) -> RE-RUN"

# (e) missing shard (fewer tokens than EXPECTED_SHARDS) -> RE-RUN, never a false
#     CLEAN: a shard that did not report is not a clean signal.
d="$(make_dir missing-shard)"
write_shard "$d" 0 CLEAN; write_shard "$d" 1 CLEAN   # shard 2 missing
run_agg "$d" 3
[[ "$AGG_VERDICT" == "RE-RUN" && "$AGG_RC" -eq 0 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(e) missing shard expected RE-RUN/exit0, got $AGG_VERDICT/exit$AGG_RC"; }
pass "(e) a missing shard downgrades CLEAN to RE-RUN (never a false clean)"

# (e') missing shard NEXT TO a genuine RED still resolves RED (RED wins over a
#      missing/re-run signal — a regression is never softened to RE-RUN).
d="$(make_dir missing-plus-red)"
write_shard "$d" 0 RED; write_shard "$d" 1 CLEAN     # shard 2 missing
run_agg "$d" 3
[[ "$AGG_VERDICT" == "RED" && "$AGG_RC" -eq 1 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(e') RED + missing expected RED/exit1, got $AGG_VERDICT/exit$AGG_RC"; }
pass "(e') RED beats a missing shard -> RED (exit 1)"

# (f) an unrecognised/corrupt token -> RED (fail closed): a malformed artifact
#     must not silently pass as clean.
d="$(make_dir corrupt-token)"
write_shard "$d" 0 CLEAN; write_shard "$d" 1 GARBAGE; write_shard "$d" 2 CLEAN
run_agg "$d" 3
[[ "$AGG_VERDICT" == "RED" && "$AGG_RC" -eq 1 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(f) corrupt token expected RED/exit1 (fail-closed), got $AGG_VERDICT/exit$AGG_RC"; }
pass "(f) an unrecognised token fails closed -> RED (exit 1)"

# (g) lower-case / whitespace-padded tokens normalise correctly.
d="$(make_dir normalise)"
mkdir -p "$d/emulator-journey-verdict-shard-0"; printf '  clean \n' > "$d/emulator-journey-verdict-shard-0/shard-verdict.txt"
mkdir -p "$d/emulator-journey-verdict-shard-1"; printf 'Infra' > "$d/emulator-journey-verdict-shard-1/shard-verdict.txt"
mkdir -p "$d/emulator-journey-verdict-shard-2"; printf 'CLEAN\n' > "$d/emulator-journey-verdict-shard-2/shard-verdict.txt"
run_agg "$d" 3
[[ "$AGG_VERDICT" == "RE-RUN" && "$AGG_RC" -eq 0 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(g) mixed-case/padded tokens expected RE-RUN/exit0, got $AGG_VERDICT/exit$AGG_RC"; }
pass "(g) tokens are whitespace/case normalised"

# (h) nothing ran at all (empty dir, EXPECTED_SHARDS=0) -> RE-RUN neutral, never
#     a false red on an inert run.
d="$(make_dir empty)"
run_agg "$d" 0
[[ "$AGG_VERDICT" == "RE-RUN" && "$AGG_RC" -eq 0 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(h) empty/no-expected expected RE-RUN/exit0, got $AGG_VERDICT/exit$AGG_RC"; }
pass "(h) no tokens + none expected -> RE-RUN (neutral, not a false red)"

echo
echo "== #1458 workflow-parity pins =="

# The per-shard classify step must be continue-on-error (so an infra shard can't
# fail the workflow on its own) and must write a verdict token in every branch.
grep -q 'name: Classify emulator-journey result' "$WORKFLOW" \
  || fail "(w1) tests.yml must still have the per-shard classify step"
grep -q 'write_verdict' "$WORKFLOW" \
  || fail "(w2) classify step must write per-shard verdict tokens (write_verdict)"
# Every RED/INFRA/CLEAN token value must appear as a write_verdict argument.
for tok in CLEAN INFRA RED; do
  grep -q "write_verdict $tok" "$WORKFLOW" \
    || fail "(w3) classify step must emit a '$tok' verdict token"
done
# The classify step must be continue-on-error so the AGGREGATION job — not the
# shard — is what turns the run red.
awk '
  /name: Classify emulator-journey result/ { armed=1 }
  armed && /continue-on-error: true/ { found=1 }
  armed && /run: \|/ { armed=0 }
  END { exit(found ? 0 : 1) }
' "$WORKFLOW" \
  || fail "(w4) classify step must be continue-on-error so an infra shard cannot fire a false red-CI email"
# The aggregation job + its verdict artifact wiring must exist.
grep -q 'emulator-journey-verdict:' "$WORKFLOW" \
  || fail "(w5) tests.yml must define the emulator-journey-verdict aggregation job"
grep -q 'ci-journey-aggregate-verdict.sh' "$WORKFLOW" \
  || fail "(w6) aggregation job must invoke scripts/ci-journey-aggregate-verdict.sh"
grep -q 'emulator-journey-verdict-shard-' "$WORKFLOW" \
  || fail "(w7) each shard must upload an emulator-journey-verdict-shard-<N> token artifact"
grep -q 'pattern: emulator-journey-verdict-shard-\*' "$WORKFLOW" \
  || fail "(w8) aggregation job must download the shard verdict artifacts by pattern"
grep -q 'EXPECTED_SHARDS: 3' "$WORKFLOW" \
  || fail "(w9) aggregation job must pass EXPECTED_SHARDS matching the 3-shard matrix"
pass "(w) classify continue-on-error + verdict tokens + aggregation job wired in tests.yml"

# The #1458 budget-neutral emulator/Gradle contention cuts. Count only the
# actual `emulator-options:` launch lines (not the explanatory comment) so both
# the first-attempt and retry emulator bringups raise the guest RAM to 6 GB.
mem_count="$(grep -cE '^\s*emulator-options:.*-memory 6144' "$WORKFLOW" || true)"
[[ "$mem_count" -eq 2 ]] \
  || fail "(w10) both emulator-options launches must set '-memory 6144' (first + retry), found $mem_count"
pass "(w10) guest -memory 6144 on both emulator launches (was the pixel_7 hwconfig default 2048)"

# Count only the actual Gradle-arg lines (leading whitespace then the flag), not
# the explanatory comments that also mention it.
mw_count="$(grep -cE '^[[:space:]]*--max-workers=2' "$BUDGET_FN" || true)"
[[ "$mw_count" -eq 2 ]] \
  || fail "(w11) both journey Gradle invocations (run_class + run_ct_class) must pass --max-workers=2, found $mw_count"
# Daemon reuse (#835 budget-fit) must be preserved — no --no-daemon crept into an
# actual Gradle-arg line (comments mentioning it are fine).
grep -qE '^[[:space:]]*--no-daemon' "$BUDGET_FN" \
  && fail "(w11) journey Gradle invocations must NOT use --no-daemon (would break the #835 budget-fit)"
pass "(w11) --max-workers=2 on both journey Gradle invocations, daemon reuse preserved"

echo
echo "ALL TESTS PASSED"
