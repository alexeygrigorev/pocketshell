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
WRITER="$SCRIPT_DIR/ci-journey-write-shard-verdict.sh"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKFLOW="$REPO_ROOT/.github/workflows/tests.yml"
BUDGET_FN="$SCRIPT_DIR/ci-journey-budget-functions.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

[[ -f "$AGG" ]] || fail "cannot find ci-journey-aggregate-verdict.sh at $AGG"
chmod +x "$AGG"
[[ -f "$WRITER" ]] || fail "cannot find ci-journey-write-shard-verdict.sh at $WRITER (issue #1809)"
chmod +x "$WRITER"

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
  local dir="$1" expected="$2" attempt="${3:-}"
  set +e
  AGG_OUT="$(EXPECTED_SHARDS="$expected" GITHUB_STEP_SUMMARY="" GITHUB_RUN_ATTEMPT="$attempt" \
    bash "$AGG" "$dir" 2>&1)"
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

# (b) all-infra -> RE-RUN (exit 0, NOT red). Environmental aborts must not fire
#     a false red-CI email when no shard carries genuine RED evidence.
d="$(make_dir all-infra)"
write_shard "$d" 0 INFRA; write_shard "$d" 1 INFRA; write_shard "$d" 2 INFRA
run_agg "$d" 3
[[ "$AGG_VERDICT" == "RE-RUN" && "$AGG_RC" -eq 0 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(b) all-infra expected RE-RUN/exit0, got $AGG_VERDICT/exit$AGG_RC"; }
grep -q '::warning' <<<"$AGG_OUT" \
  || fail "(b) an all-infra RE-RUN must emit a ::warning (re-run signal), not silence"
grep -q '::error' <<<"$AGG_OUT" \
  && fail "(b) an all-infra RE-RUN must NOT emit a ::error (that would fire a false red-CI email)"
pass "(b) all shards INFRA -> RE-RUN (neutral green, exit 0, no false red)"

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
echo "== #1809 single-shard re-run: verdict provenance =="

# write_stamped <dir> <shard> <token> <run-attempt> — drive the REAL writer
# (scripts/ci-journey-write-shard-verdict.sh) into the per-shard subdir layout
# download-artifact produces, so these cases exercise the production token
# format rather than a hand-rolled imitation of it.
write_stamped() {
  local dir="$1" idx="$2" token="$3" attempt="$4"
  local sub="$dir/emulator-journey-verdict-shard-$idx"
  mkdir -p "$sub"
  SHARD_VERDICT_FILE="$sub/shard-verdict.txt" \
    POCKETSHELL_JOURNEY_CI_SHARD_INDEX="$idx" \
    GITHUB_RUN_ID=30318408377 \
    GITHUB_RUN_ATTEMPT="$attempt" \
    GITHUB_OUTPUT="" \
    bash "$WRITER" "$token" > /dev/null \
    || fail "writer refused to write a $token token for shard $idx"
}

# (i) THE REPRODUCTION. The stamped token the shard now writes is MULTI-LINE.
#     The pre-#1809 aggregator read the WHOLE file, stripped whitespace and
#     upper-cased it, so a stamped CLEAN token collapsed to
#     `CLEANSHARD=0RUN_ID=...` -> UNKNOWN -> fail-closed RED. Running this case
#     against the base aggregator yields RED/exit1; it must yield CLEAN/exit0.
d="$(make_dir stamped-clean)"
write_stamped "$d" 0 CLEAN 1; write_stamped "$d" 1 CLEAN 1; write_stamped "$d" 2 CLEAN 1
run_agg "$d" 3 1
[[ "$AGG_VERDICT" == "CLEAN" && "$AGG_RC" -eq 0 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(i) stamped CLEAN tokens expected CLEAN/exit0, got $AGG_VERDICT/exit$AGG_RC — the aggregator must read the verdict from line 1, not the whole file"; }
pass "(i) provenance-stamped tokens parse to their real verdict (not UNKNOWN -> false RED)"

# (i') the same control in the RED direction: stamping must not swallow a real
#      regression either.
d="$(make_dir stamped-red)"
write_stamped "$d" 0 CLEAN 1; write_stamped "$d" 1 RED 1; write_stamped "$d" 2 CLEAN 1
run_agg "$d" 3 1
[[ "$AGG_VERDICT" == "RED" && "$AGG_RC" -eq 1 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(i') a stamped RED must still resolve RED/exit1, got $AGG_VERDICT/exit$AGG_RC"; }
pass "(i') a stamped RED token still turns the run red — stamping never masks a regression"

# (j) THE G5 CLEAN RE-RUN, end to end. Attempt 1 had shard 0 RED; the on-call
#     re-runs ONLY shard 0 and it comes back CLEAN. Shards 1/2 legitimately keep
#     their attempt-1 tokens (they were not re-run). The aggregate must consume
#     the RE-RUN's shard-0 value -> CLEAN/exit0, and must SAY which verdicts are
#     carried over, so "I produced a clean re-run" is an artifact, not a claim.
d="$(make_dir clean-rerun)"
write_stamped "$d" 0 CLEAN 2      # the re-run's fresh token
write_stamped "$d" 1 CLEAN 1
write_stamped "$d" 2 CLEAN 1
run_agg "$d" 3 2
[[ "$AGG_VERDICT" == "CLEAN" && "$AGG_RC" -eq 0 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(j) a clean single-shard re-run expected CLEAN/exit0, got $AGG_VERDICT/exit$AGG_RC"; }
grep -q 'shard 0: CLEAN .*attempt 2 (this attempt)' <<<"$AGG_OUT" \
  || { printf '%s\n' "$AGG_OUT"; fail "(j) the aggregate must report shard 0's verdict as coming from THIS attempt (the re-run)"; }
grep -q 'shard 1: CLEAN .*attempt 1 (carried over' <<<"$AGG_OUT" \
  || { printf '%s\n' "$AGG_OUT"; fail "(j) the aggregate must report shard 1's verdict as carried over from the earlier attempt"; }
grep -q 'some shard verdicts carried over' <<<"$AGG_OUT" \
  || { printf '%s\n' "$AGG_OUT"; fail "(j) a partial re-run must emit the carried-over ::notice so provenance is auditable"; }
pass "(j) single-shard re-run -> CLEAN, and each verdict's originating attempt is reported"

# (j') the load-bearing control for (j): the ONLY thing that made it CLEAN is the
#      re-run shard's fresh token. Put attempt 2's shard-0 token back to RED and
#      the aggregate must go RED — proving it read the re-run's value, not the
#      other shards' carried-over CLEANs.
d="$(make_dir clean-rerun-control)"
write_stamped "$d" 0 RED 2
write_stamped "$d" 1 CLEAN 1
write_stamped "$d" 2 CLEAN 1
run_agg "$d" 3 2
[[ "$AGG_VERDICT" == "RED" && "$AGG_RC" -eq 1 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(j') flipping the re-run shard's token to RED must yield RED/exit1, got $AGG_VERDICT/exit$AGG_RC"; }
pass "(j') control: the re-run shard's OWN token drives the verdict"

# (k) THE REPLAY BACKSTOP. On attempt 2 with every token still from attempt 1,
#     the aggregation job was re-run WITHOUT re-running any shard — the
#     "confusing second red" the on-call hit. The verdict is unchanged (it is the
#     honest rollup of the data) but the run must SAY it is a replay.
d="$(make_dir replay)"
write_stamped "$d" 0 RED 1; write_stamped "$d" 1 CLEAN 1; write_stamped "$d" 2 CLEAN 1
run_agg "$d" 3 2
[[ "$AGG_VERDICT" == "RED" && "$AGG_RC" -eq 1 ]] \
  || { printf '%s\n' "$AGG_OUT"; fail "(k) a replay of a RED token set must still be RED/exit1, got $AGG_VERDICT/exit$AGG_RC"; }
grep -q 'REPLAY, no shard was re-run' <<<"$AGG_OUT" \
  || { printf '%s\n' "$AGG_OUT"; fail "(k) re-running ONLY the aggregation job must be reported as a replay, not presented as a re-run result"; }
pass "(k) aggregation-only re-run is flagged REPLAY (no shard re-run) instead of reading as a fresh second red"

# (k') control: on attempt 1 there is nothing to replay, so the warning must NOT
#      fire — the signal has to mean something.
d="$(make_dir replay-control)"
write_stamped "$d" 0 RED 1; write_stamped "$d" 1 CLEAN 1; write_stamped "$d" 2 CLEAN 1
run_agg "$d" 3 1
grep -q 'REPLAY, no shard was re-run' <<<"$AGG_OUT" \
  && { printf '%s\n' "$AGG_OUT"; fail "(k') the REPLAY warning must not fire on a first attempt"; }
grep -q 'some shard verdicts carried over' <<<"$AGG_OUT" \
  && { printf '%s\n' "$AGG_OUT"; fail "(k') nothing is carried over on a first attempt"; }
pass "(k') control: no REPLAY / carried-over noise on a first attempt"

# (l) the writer refuses an unknown verdict (fail closed at the source, so a
#     typo can never become an UNKNOWN token the aggregator has to fail on).
set +e
"$WRITER" MAYBE > /dev/null 2>&1
writer_rc=$?
set -e
[[ "$writer_rc" -ne 0 ]] || fail "(l) the writer must reject a token outside CLEAN|INFRA|RED"
pass "(l) the verdict writer rejects an unrecognised token"

# (m) the writer stamps run/attempt/shard and exports the step output the
#     shard's RED gate reads.
d="$(make_dir writer-output)"
mkdir -p "$d/out"
SHARD_VERDICT_FILE="$d/out/shard-verdict.txt" \
  POCKETSHELL_JOURNEY_CI_SHARD_INDEX=2 \
  GITHUB_RUN_ID=42 GITHUB_RUN_ATTEMPT=3 \
  GITHUB_OUTPUT="$d/out/gh-output.txt" \
  bash "$WRITER" RED > /dev/null || fail "(m) writer failed"
[[ "$(head -n1 "$d/out/shard-verdict.txt")" == "RED" ]] \
  || fail "(m) line 1 of the token must be the bare verdict"
grep -qx 'shard=2' "$d/out/shard-verdict.txt" || fail "(m) token must stamp shard="
grep -qx 'run_id=42' "$d/out/shard-verdict.txt" || fail "(m) token must stamp run_id="
grep -qx 'run_attempt=3' "$d/out/shard-verdict.txt" || fail "(m) token must stamp run_attempt="
grep -qx 'shard_verdict=RED' "$d/out/gh-output.txt" \
  || fail "(m) writer must export shard_verdict to \$GITHUB_OUTPUT for the RED gate step"
pass "(m) writer stamps shard/run/attempt and exports shard_verdict"

echo
echo "== #1809 workflow-parity pins =="

# The shard job must PRE-SEED a token right after checkout, so an attempt that
# dies before the classifier cannot silently inherit the previous attempt's
# verdict.
grep -q 'name: "Pre-seed shard verdict token (issue #1809)"' "$WORKFLOW" \
  || fail "(x1) the emulator-journey job must pre-seed a shard verdict token so every attempt reports its own"
awk '
  /Pre-seed shard verdict token \(issue #1809\)/ { armed=1 }
  armed && /ci-journey-write-shard-verdict\.sh INFRA/ { found=1 }
  armed && /^      - name: Set up JDK/ { armed=0 }
  END { exit(found ? 0 : 1) }
' "$WORKFLOW" \
  || fail "(x2) the pre-seed must write INFRA (a re-run signal), never CLEAN or RED"
# ...and it must run BEFORE the journey/classify steps (a seed written after the
# suite would not survive a job that died during the suite).
preseed_line="$(grep -n 'Pre-seed shard verdict token (issue #1809)' "$WORKFLOW" | head -n1 | cut -d: -f1)"
classify_line="$(grep -n 'name: Classify emulator-journey result' "$WORKFLOW" | head -n1 | cut -d: -f1)"
[[ -n "$preseed_line" && -n "$classify_line" && "$preseed_line" -lt "$classify_line" ]] \
  || fail "(x3) the pre-seed step must come BEFORE the classify step (got preseed=$preseed_line classify=$classify_line)"
pass "(x1-x3) shard verdict token is pre-seeded INFRA before the suite runs"

# The classifier writes through the ONE stamped writer (no second, unstamped
# token format can drift back in).
grep -q 'write_verdict() { bash scripts/ci-journey-write-shard-verdict.sh' "$WORKFLOW" \
  || fail "(x4) the classify step must write its verdict through scripts/ci-journey-write-shard-verdict.sh"
grep -q 'verdict_file' "$WORKFLOW" \
  && fail "(x4) the old inline, unstamped \$verdict_file writer must be gone (D22 hard cut) — one writer owns the format"
pass "(x4) one stamped writer owns the token format"

# A RED shard must fail its OWN job, so GitHub's 'Re-run failed jobs' re-runs the
# offending shard (plus the aggregation job that needs it) instead of re-running
# the aggregation job alone and replaying the same tokens.
grep -q 'name: "Fail this shard on a genuine RED verdict (issue #1809)"' "$WORKFLOW" \
  || fail "(x5) a RED shard must fail its own job so the standard re-run button re-runs that shard"
grep -q "steps.classify.outputs.shard_verdict == 'RED'" "$WORKFLOW" \
  || fail "(x6) the shard RED gate must trigger on the classifier's RED verdict only"
# ...and ONLY on RED: an INFRA shard staying green is the #1458 no-false-red
# property, so guard against the condition being widened.
for tok in INFRA CLEAN; do
  grep -q "steps.classify.outputs.shard_verdict == '$tok'" "$WORKFLOW" \
    && fail "(x7) the shard job must NOT fail on a '$tok' verdict — that would reintroduce the #1458 false red-CI email"
done
pass "(x5-x7) RED (and only RED) fails the shard job — re-run button reaches the failing shard, #1458 stays intact"

# Every artifact this job uploads must be re-runnable (overwrite within the
# attempt). Checked across ALL uploads in the workflow, not just the verdict
# token — fixing only the named one would be the proxy fix (G2).
uploads="$(grep -c 'uses: actions/upload-artifact@v7' "$WORKFLOW" || true)"
overwrites="$(grep -cE '^[[:space:]]*overwrite: true' "$WORKFLOW" || true)"
[[ "$uploads" -gt 0 ]] || fail "(x8) expected upload-artifact steps in tests.yml"
[[ "$overwrites" -eq "$uploads" ]] \
  || fail "(x8) every upload-artifact step in tests.yml must set 'overwrite: true' so a re-run replaces its own attempt's artifact (found $overwrites overwrite(s) for $uploads upload(s))"
pass "(x8) all $uploads upload-artifact steps in tests.yml are re-run safe (overwrite: true)"

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
# Issue #2060: this used to pin the LITERAL `EXPECTED_SHARDS: 3`, so changing
# the matrix meant editing the pin to a new literal — which re-arms the same
# drift every time. Derive the matrix length instead and CROSS-CHECK the
# aggregation job's expectation against it, so the assertion keeps its force at
# any shard count. An EXPECTED_SHARDS below the matrix length would let a
# missing shard's verdict pass unnoticed (the #1458 rollup's whole job).
workflow_shards="$(bash "$SCRIPT_DIR/ci-journey-shard-count.sh" "$WORKFLOW")" \
  || fail "(w9) could not derive the emulator-journey shard count from the matrix"
grep -q "EXPECTED_SHARDS: ${workflow_shards}\$" "$WORKFLOW" \
  || fail "(w9) aggregation job must pass EXPECTED_SHARDS: ${workflow_shards} to match the ${workflow_shards}-shard matrix (found: $(grep -n 'EXPECTED_SHARDS:' "$WORKFLOW" | tr '\n' ' '))"
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
