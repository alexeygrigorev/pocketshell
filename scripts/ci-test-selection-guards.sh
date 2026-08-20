#!/usr/bin/env bash
# ci-test-selection-guards.sh — issue #2067
#
# The five #2063 test-area coverage guards, as ONE command. This is the body of
# the `guards-test-selection` job in `.github/workflows/tests.yml`, and the same
# thing to run locally when you touch the manifest, the classification engine,
# the journey registry, or `tools/pocketshell/src/pocketshell/cli.py`.
#
# WHY A DEDICATED JOB RATHER THAN A GRADLE TEST. #2063 first reached these
# guards through a JVM test (`SmartTestSelectionScriptTest`) so `./gradlew test`
# would run them — right instinct (a guard no lane runs is not a guard), wrong
# lane. `test` runs BOTH variants, so the ~165 s suite was charged twice per
# push, on the Unit critical path, for work that compiles nothing: +5 to +7 min
# on every Unit run, which over 104 `main` commits exactly cancelled the
# scheme's own saving. Here it is paid ONCE, in parallel, and is still blocking
# because `unit-gate` — the job that carries the required `Unit tests` check
# name — needs it. `scripts/check-unit-gate-wiring.sh` asserts both halves of
# that: the gate wiring (C1–C8) and that these guards have not crept back into a
# Gradle test task (C9).
#
# WHY IT IS CHEAP. Pure filesystem + `git ls-files` + awk/python3. No JDK, no
# Gradle, no Android SDK, no Docker, no emulator. `git ls-files` reads the index,
# so a depth-1 checkout is enough (the guards never diff against a base ref; the
# diff path is a different mode, and it fails safe to a full run anyway).
#
# BUDGET. ~165 s total, ~80% of it the selection self-test. Read the total as
# noise-dominated — four same-content observations spanned 154.2–205.3 s (33%).
# The meaningful figure is structural: ~11.6 s per index-rebuilding mutation
# case, ~2 cases of headroom before the dependency-index build must be shared.
# The per-guard seconds are REPORTED into the job summary, deliberately not
# asserted: a 33%-variance quantity makes a terrible assertion (G6). The job
# ceiling is 5 min wall clock, past which it becomes the critical path on a
# docs-only push. Detail: docs/testing.md, "Self-tests".

set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."

# The guards must read the COMMITTED manifest and the real journey registry.
# These variables are the self-tests' own sandbox knobs; `SmartTestSelectionScriptTest`
# scrubbed them before invoking, and that scrub moves here with the guards rather
# than being dropped on the floor. Nothing sets them on a hosted runner today, but
# a future workflow-level `env:` would silently point a guard at a sandbox.
for _v in $(compgen -e || true); do
  case "$_v" in
    POCKETSHELL_TEST_AREAS_*|POCKETSHELL_TA_*|POCKETSHELL_TEST_LEDGER) unset "$_v" ;;
  esac
done
unset _v

chmod +x scripts/select-test-areas.sh \
         scripts/select-test-areas-selftest.sh \
         scripts/check-test-execution-ledger.sh \
         scripts/check-test-execution-ledger-selftest.sh \
         scripts/check-test-execution-ledger-wiring.py \
         scripts/ci-record-test-execution-ledger.sh \
         scripts/ci-nightly-execution-ledger.sh \
         scripts/dev-fast-gate-parity-selftest.sh

TIMINGS="$(mktemp)"
trap 'rm -f "$TIMINGS"' EXIT

run_guard() {
  local label="$1"; shift
  local start end
  [ -n "${GITHUB_ACTIONS:-}" ] && echo "::group::$label" || echo "== $label"
  start=$(date +%s)
  "$@"
  end=$(date +%s)
  [ -n "${GITHUB_ACTIONS:-}" ] && echo "::endgroup::" || true
  printf '%-46s %4ss\n' "$label" "$((end - start))" >> "$TIMINGS"
}

suite_start=$(date +%s)

# The manifest is TOTAL over the tracked tree and internally consistent.
run_guard "select-test-areas --verify-manifest" \
  bash scripts/select-test-areas.sh --verify-manifest

# The coverage invariant the whole scheme rests on, including I8 (blast-radius
# escapes), I10 (the emitted command IS the plan) and I11 (every shared module is
# run by its own change).
run_guard "select-test-areas --coverage-invariant" \
  bash scripts/select-test-areas.sh --coverage-invariant

# Selection mechanics + the mutations proving each real-tree guard can go red.
run_guard "select-test-areas-selftest" \
  bash scripts/select-test-areas-selftest.sh

# Execution-ledger guard reds, synthetic AND real-tree.
run_guard "check-test-execution-ledger-selftest" \
  bash scripts/check-test-execution-ledger-selftest.sh

# Issue #2082: workflows actually invoke --record/--verify/attendance against
# real JUnit artifacts. A ledger nobody calls is decoration; the self-test
# mutates the YAML and demands a RED.
run_guard "check-test-execution-ledger-wiring-selftest" \
  scripts/check-test-execution-ledger-wiring.py --self-test
run_guard "check-test-execution-ledger-wiring" \
  scripts/check-test-execution-ledger-wiring.py

# dev-fast-gate's classifier still agrees with the shared manifest.
run_guard "dev-fast-gate-parity-selftest" \
  bash scripts/dev-fast-gate-parity-selftest.sh

suite_end=$(date +%s)
printf '%-46s %4ss\n' "TOTAL" "$((suite_end - suite_start))" >> "$TIMINGS"

echo
cat "$TIMINGS"

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### Test-selection coverage guards (#2063 / #2067)"
    echo
    echo '```'
    cat "$TIMINGS"
    echo '```'
    echo
    echo "Budget is marginal (~11.6 s per index-rebuilding case), not an absolute"
    echo "wall-clock ceiling — four same-content observations of this suite spanned"
    echo "33%. Read the trend, not one number. Job ceiling: 5 min."
  } >> "$GITHUB_STEP_SUMMARY"
fi
