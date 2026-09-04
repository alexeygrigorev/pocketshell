#!/usr/bin/env bash
set -uo pipefail

# Release execution-ledger lane-coverage harness (#2435).
#
# WHY THIS IS ITS OWN HARNESS, AND WHY NO JVM TEST DRIVES IT
#
# These cases execute the REAL scripts/check-test-execution-ledger.sh against
# the real tree's class-registration rules. That is the #2063 selection-guard
# suite, and #2067's C9 (scripts/check-unit-gate-wiring.sh) exists precisely to
# keep it off the Gradle test graph: `./gradlew test` runs BOTH variants, so a
# JVM driver charges every push twice for work that compiles nothing. #2435
# round 2 reintroduced exactly that shape by adding these cases to
# tests/scripts/release-validation-storage-test.sh, which
# DiskPreflightScriptTest drives from the Unit critical path — measured at
# +12.5 s per variant locally, and the PR had to raise that test's timeout from
# 120 s to 300 s to pay for it.
#
# So they live here instead, invoked once, in parallel, by the
# `guards-test-selection` job (scripts/ci-test-selection-guards.sh) — still
# blocking, because `unit-gate` (the job carrying the required `Unit tests`
# check name) needs that job. Same gate strength, one variant's cost, and C9
# stays a bright line rather than acquiring an exemption.
#
# WHAT IT COVERS
#
# 1. The #2082 execution-ledger step vs. the generated-worktree cleanup that
#    runs ~15 s before it. `Release Emulator Validation` concluded `failure` on
#    a run whose own summary.md said `Automated status: PASS`, which made the
#    #2356 `Record validated-rc marker` job (gated on
#    `needs.emulator-release-validation.result == 'success'`) impossible to
#    reach. Two independent, each-sufficient defects:
#
#      a. ORDERING. pocketshell_release_validation_finish_run packaged the
#         small JUnit XML out of the isolated worktree only on `failure`. The
#         release wrapper finishes a green chain with `success`, so the
#         worktree — the only place the gate's `*/build/test-results/*` XML
#         exists — was removed with nothing copied out, and the workflow step
#         that runs AFTER it found zero XML.
#
#      b. PIPEFAIL/SIGPIPE INVERSION. The step's presence check was
#         `if ! find "$stage" … | grep -q .`. `grep -q` exits on its first
#         match, `find` dies of EPIPE, and `set -o pipefail` propagates find's
#         non-zero status — so the pipeline reported failure EXACTLY when XML
#         was present. Run 33337299691 logged `find: 'standard output': Broken
#         pipe` / `find: write error` immediately before `no JUnit XML`, in a
#         run that had just packaged 1473 XML files.
#
#    These cases execute the REAL `run:` body extracted from the committed
#    workflow YAML, not a re-spelling of it.
#
# 2. The third defect the first two hid: once `--verify` finally executed, it
#    reported SEVEN registered classes that had NEVER executed, so the job
#    still concluded `failure`. Those cases drive the REAL ledger script.
#
# No emulator, no Docker, no Gradle, no JVM.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-release-ledger-test.XXXXXX")"
trap 'chmod -R u+rwX "$WORK_DIR" 2>/dev/null || true; rm -rf "$WORK_DIR"' EXIT

unset CI \
  POCKETSHELL_AVD_LOCK_ACQUIRED \
  POCKETSHELL_AVD_LOCK_FILE \
  POCKETSHELL_GATE_ISOLATED_COPY \
  POCKETSHELL_GATE_SOURCE_ROOT \
  POCKETSHELL_RELEASE_RETENTION_OWNER_PID \
  POCKETSHELL_RELEASE_RETENTION_OWNER_START \
  POCKETSHELL_TEST_MEM \
  POCKETSHELL_TEST_LEDGER

CASES=0

# shellcheck disable=SC1091
source "$ROOT_DIR/scripts/lib/disk-preflight.sh"
# shellcheck disable=SC1091
source "$ROOT_DIR/scripts/lib/release-validation-storage.sh"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

pass_case() {
  CASES=$((CASES + 1))
  printf '  ok: %s\n' "$1"
}

run_case() {
  local name="$1"
  local function_name="$2"
  if [[ -n "${POCKETSHELL_RELEASE_LEDGER_TEST_CASE:-}" &&
    "$POCKETSHELL_RELEASE_LEDGER_TEST_CASE" != "$name" ]]; then
    return 0
  fi
  "$function_name"
}

LEDGER_STEP_NAME='- name: Record and verify execution ledger'

extract_ledger_step_body() {
  # Take the literal block scalar under the ledger step's `run: |`, dedented by
  # its fixed 10-space workflow indentation. Reading the committed YAML is the
  # point: a copy of the script in this harness would stay green while the
  # workflow regressed.
  awk -v marker="$LEDGER_STEP_NAME" '
    index($0, marker) { in_step = 1; next }
    in_step && !in_run && $0 ~ /^[[:space:]]*run: \|[[:space:]]*$/ { in_run = 1; next }
    in_run {
      # Hold blank lines until a body line follows them. Emitting them eagerly
      # appends the blank separator line BEFORE the next workflow step, so the
      # extraction stops being byte-identical to the YAML block scalar.
      if ($0 ~ /^[[:space:]]*$/) { pending++; next }
      if ($0 !~ /^          /) { exit }
      while (pending > 0) { print ""; pending-- }
      sub(/^          /, "")
      print
    }
  ' "$1"
}

write_junit_xml() {
  local destination="$1"
  local classname="$2"
  mkdir -p "$(dirname -- "$destination")"
  cat > "$destination" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="$classname" tests="1" skipped="0" failures="0" errors="0">
  <testcase name="proves_the_ledger_saw_real_content" classname="$classname" time="0.011"/>
</testsuite>
XML
}

# The pipefail/SIGPIPE inversion only fires once the writer still has data
# pending when the early-exiting reader goes away, i.e. once `find`'s output
# exceeds the 64 KiB pipe buffer. The release run that exposed this packaged
# 1473 XML files; this fixture reproduces the same regime with long FQCN-shaped
# names, measured deterministic at 20/20 reps with the GNU find/grep the hosted
# runner uses. Undersizing it would make the case pass on the unfixed body for
# the wrong reason (the G6 shape) — so the volume is load-bearing, not padding.
LEDGER_XML_FIXTURE_COUNT="${LEDGER_XML_FIXTURE_COUNT:-600}"
LEDGER_XML_LONG_NAME_PAD='ReleaseGateLedgerRegressionFixtureClassNamePaddingSegmentAlphaBravoCharlieDeltaEchoFoxtrotGolfHotelIndiaJulietKiloLimaMikeNovemberOscarPapaQuebecRomeo'

plant_release_junit_xml_volume() {
  # $1 = repository-or-worktree root the release gate produced results under.
  local root="$1"
  local unit_dir="$root/app/build/test-results/testDebugUnitTest"
  local connected_dir="$root/shared/core-terminal/build/outputs/androidTest-results/connected"
  mkdir -p "$unit_dir" "$connected_dir"
  write_junit_xml \
    "$unit_dir/TEST-com.pocketshell.app.AlphaTest.xml" \
    com.pocketshell.app.AlphaTest
  write_junit_xml \
    "$connected_dir/TEST-com.pocketshell.terminal.BetaTest.xml" \
    com.pocketshell.terminal.BetaTest
  local index target_dir classname
  for (( index = 0; index < LEDGER_XML_FIXTURE_COUNT; index++ )); do
    if (( index % 2 == 0 )); then
      target_dir="$unit_dir"
    else
      target_dir="$connected_dir"
    fi
    printf -v classname 'com.pocketshell.bulk.%s%04dTest' "$LEDGER_XML_LONG_NAME_PAD" "$index"
    printf '<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="%s" tests="1"><testcase name="bulk" classname="%s" time="0.001"/></testsuite>\n' \
      "$classname" "$classname" > "$target_dir/TEST-$classname.xml"
  done
}

# The stub stands in for scripts/check-test-execution-ledger.sh. It records the
# arguments it was called with AND independently re-derives how many
# `<testcase` elements are reachable from the staged root it was handed, so a
# "the directory exists but is empty" staging regression cannot masquerade as a
# fix (the real script's own zero-testcase refusal is the production gate; this
# mirrors it so the harness fails at the staging boundary).
make_ledger_stub() {
  local destination="$1"
  local calls_file="$2"
  local testcases_file="$3"
  mkdir -p "$(dirname -- "$destination")"
  cat > "$destination" <<STUB
#!/usr/bin/env bash
printf '%s\n' "\$*" >> '$calls_file'
if [[ "\${1:-}" == "--record" ]]; then
  staged_root="\${2:-}"
  if [[ ! -d "\$staged_root" ]]; then
    printf 'stub: staged root does not exist: %s\n' "\$staged_root" >&2
    exit 1
  fi
  testcases="\$(grep -rho '<testcase' "\$staged_root" 2>/dev/null | wc -l | tr -d ' ')"
  printf '%s\n' "\$testcases" > '$testcases_file'
  if [[ "\$testcases" -eq 0 ]]; then
    printf 'stub: no JUnit testcases reachable under %s\n' "\$staged_root" >&2
    exit 1
  fi
fi
exit 0
STUB
  chmod +x "$destination"
}

make_ledger_step_sandbox() {
  local sandbox="$1"
  local repository="$sandbox/repository"
  mkdir -p "$repository/scripts" "$sandbox/runner-temp"
  extract_ledger_step_body "$ROOT_DIR/.github/workflows/release-emulator-validation.yml" \
    > "$sandbox/ledger-step.sh"
  [[ -s "$sandbox/ledger-step.sh" ]] ||
    fail "could not extract the '$LEDGER_STEP_NAME' run: body from the release workflow"
  grep -q 'check-test-execution-ledger.sh --record' "$sandbox/ledger-step.sh" ||
    fail "extracted ledger step body does not --record; the extractor is reading the wrong block"
  grep -q 'check-test-execution-ledger.sh --verify' "$sandbox/ledger-step.sh" ||
    fail "extracted ledger step body does not --verify; the extractor is reading the wrong block"
  make_ledger_stub "$repository/scripts/check-test-execution-ledger.sh" \
    "$sandbox/ledger-calls" "$sandbox/staged-testcases"
  printf '%s\n' "$repository"
}

run_ledger_step() {
  local sandbox="$1"
  local repository="$2"
  (
    cd "$repository" || exit 90
    RUNNER_TEMP="$sandbox/runner-temp" bash "$sandbox/ledger-step.sh" 2>&1
  )
}

ledger_step_records_retained_release_xml() {
  local sandbox="$WORK_DIR/ledger-step-present"
  local repository retained
  repository="$(make_ledger_step_sandbox "$sandbox")"
  retained="$repository/build/pre-release-confidence-gate/gha-1-pre-release/retained-test-results"
  plant_release_junit_xml_volume "$retained"

  local output rc=0
  output="$(run_ledger_step "$sandbox" "$repository")" || rc=$?

  (( rc == 0 )) ||
    fail "ledger step failed although JUnit XML was present (rc=$rc): $output"
  [[ "$output" != *"no JUnit XML from the release run"* ]] ||
    fail "ledger step reported an empty run while XML was present: $output"
  [[ -s "$sandbox/ledger-calls" ]] ||
    fail "ledger step never invoked check-test-execution-ledger.sh: $output"
  grep -q -- '--record ' "$sandbox/ledger-calls" ||
    fail "ledger step did not --record this run's results: $(cat "$sandbox/ledger-calls")"
  grep -q -- '--verify ' "$sandbox/ledger-calls" ||
    fail "ledger step did not --verify the rolling ledger: $(cat "$sandbox/ledger-calls")"
  [[ "$(cat "$sandbox/staged-testcases" 2>/dev/null || printf 0)" -ge 2 ]] ||
    fail "ledger step staged a path with no real JUnit testcases in it (staged testcase count: $(cat "$sandbox/staged-testcases" 2>/dev/null))"
  pass_case "ledger step records real retained XML instead of failing a genuine PASS (#2435)"
}

ledger_step_still_refuses_a_run_with_no_junit_xml() {
  local sandbox="$WORK_DIR/ledger-step-absent"
  local repository
  repository="$(make_ledger_step_sandbox "$sandbox")"
  mkdir -p "$repository/build/release-emulator-validation/gha-1"
  printf 'Automated status: PASS\n' > "$repository/build/release-emulator-validation/gha-1/summary.md"

  local output rc=0
  output="$(run_ledger_step "$sandbox" "$repository")" || rc=$?

  (( rc == 1 )) ||
    fail "ledger step did not fail closed on a run with no JUnit XML (rc=$rc): $output"
  [[ "$output" == *"no JUnit XML from the release run — refusing to record or verify"* ]] ||
    fail "empty-run refusal lost its explicit #2082 message: $output"
  [[ ! -s "$sandbox/ledger-calls" ]] ||
    fail "ledger step recorded/verified over an empty result set: $(cat "$sandbox/ledger-calls")"
  pass_case "ledger step still fails loudly when the release run really produced no XML (#2435)"
}

successful_release_run_leaves_ledger_xml_for_the_post_cleanup_step() {
  local sandbox="$WORK_DIR/success-retention"
  local repository release_root run_dir worktree
  repository="$(make_ledger_step_sandbox "$sandbox")"
  release_root="$repository/build/pre-release-confidence-gate"
  run_dir="$release_root/gha-1-pre-release"
  worktree="$run_dir/worktree"
  pocketshell_release_validation_prepare_root "$repository" "$release_root" ||
    fail "could not authenticate the generated release root fixture"
  pocketshell_release_validation_mark_active "$release_root" gha-1-pre-release "$$" ||
    fail "could not mark the generated run active"
  plant_release_junit_xml_volume "$worktree"
  mkdir -p "$worktree/app/build/intermediates"
  printf 'bulk generated output\n' > "$worktree/app/build/intermediates/big.bin"

  local output rc=0
  output="$(pocketshell_release_validation_finish_run "$release_root" gha-1-pre-release success 2>&1)" || rc=$?
  (( rc == 0 )) || fail "successful finish_run failed (rc=$rc): $output"
  [[ ! -e "$worktree" ]] ||
    fail "successful finish_run kept the generated isolated worktree"
  [[ -s "$run_dir/retained-test-results/app/build/test-results/testDebugUnitTest/TEST-com.pocketshell.app.AlphaTest.xml" ]] ||
    fail "successful finish_run removed the worktree without retaining its unit JUnit XML: $output"
  [[ -s "$run_dir/retained-test-results/shared/core-terminal/build/outputs/androidTest-results/connected/TEST-com.pocketshell.terminal.BetaTest.xml" ]] ||
    fail "successful finish_run did not retain the connected androidTest JUnit XML: $output"
  [[ ! -e "$run_dir/retained-test-results/app/build/intermediates/big.bin" ]] ||
    fail "retention widened past JUnit XML into bulk generated build output"

  # The whole point of the issue: the workflow step that runs AFTER this
  # cleanup must find real results. Drive the real step body over the real
  # post-cleanup tree, not over a hand-built approximation of it.
  rc=0
  output="$(run_ledger_step "$sandbox" "$repository")" || rc=$?
  (( rc == 0 )) ||
    fail "the #2082 ledger step still failed after a SUCCESSFUL release run (rc=$rc): $output"
  grep -q -- '--record ' "$sandbox/ledger-calls" ||
    fail "post-success ledger step did not --record: $(cat "$sandbox/ledger-calls" 2>/dev/null)"
  [[ "$(cat "$sandbox/staged-testcases" 2>/dev/null || printf 0)" -ge 2 ]] ||
    fail "post-success ledger step staged no real JUnit testcases (count: $(cat "$sandbox/staged-testcases" 2>/dev/null))"
  pass_case "a green release run leaves the post-cleanup #2082 ledger step real XML to record (#2435)"
}

validated_rc_marker_still_requires_a_genuinely_successful_validation_job() {
  local workflow="$ROOT_DIR/.github/workflows/release-emulator-validation.yml"
  local marker_job
  marker_job="$(awk '/^  record-validated-rc:/{ found = 1 } found { print } found && /^  notify-nightly-rc-red:/{ exit }' "$workflow")"
  [[ -n "$marker_job" ]] ||
    fail "release workflow lost its record-validated-rc job"
  [[ "$marker_job" == *"needs.emulator-release-validation.result == 'success'"* ]] ||
    fail "record-validated-rc no longer requires a successful validation job — a red gate could publish a validated-rc tag"
  [[ "$marker_job" == *"github.event.workflow_run.event == 'schedule'"* ]] ||
    fail "record-validated-rc is no longer restricted to the nightly schedule path"
  # The ledger step must still be able to fail the job. `continue-on-error`
  # would turn this issue's fix into the opposite defect: a masked red.
  local ledger_step
  ledger_step="$(awk '
    index($0, "- name: Record and verify execution ledger") { found = 1 }
    found { print }
    found && index($0, "- name: Publish validation summary") { exit }
  ' "$workflow")"
  [[ "$ledger_step" != *"continue-on-error"* ]] ||
    fail "the #2082 ledger step became continue-on-error — a missing ledger would no longer redden the release job"
  [[ "$ledger_step" == *"if: always()"* ]] ||
    fail "the #2082 ledger step no longer runs on a failed validation run"
  pass_case "validated-rc still needs a genuinely successful job and the ledger step can still redden it (#2435)"
}

# ---------------------------------------------------------------------------
# Issue #2435, round 2: the REAL check-test-execution-ledger.sh, not a stub.
#
# The four cases above stub the ledger script, which is exactly why a THIRD
# defect stayed invisible through round 1: once the staging bug and the
# pipefail inversion were fixed, `--verify` finally executed for the first time
# in this workflow's history — and reported SEVEN registered classes that have
# NEVER executed. The job still concluded `failure`, so `record-validated-rc`
# was still unreachable and the issue's title symptom survived its own fix.
#
# The seven, and what each one actually was:
#
#   AssistantAgentLoopRealLlmTest        app/build.gradle.kts excludes
#                                        **/*RealLlmTest.class from BOTH unit
#                                        tasks and :app:realLlmTest needs real
#                                        provider credentials -> no CI lane can
#                                        EVER emit it, yet `--source-set unit`
#                                        demanded a 7-day cadence for it.
#   UiKitPrimitivesTest                  shared/ui-kit/src/androidTest; no lane
#                                        ran :shared:ui-kit:connectedDebugAndroidTest.
#   LongRunningSessionStabilityTest      excluded from nightly phase 1 by NAME
#   LongRunningInstrumentationHeartbeat  PREFIX and named by no later phase, so
#                                        both ran nowhere. (The #794 90 s
#                                        transport-flap method has no assume at
#                                        all; the heartbeat class is plain JVM
#                                        logic.)
#   HostCardStatusChipTest               selected by phase 1, which passes no
#   HostCardSetupBadgeTest               `pocketshellBootstrapScenarios` opt-in,
#                                        so every method came back <skipped/> —
#                                        attendance-green, ledger-uncredited.
#   LastSessionProcessRestartProofTest   runs every night in phase 5 through
#                                        direct `am instrument`, which produces
#                                        no host JUnit XML at all.
#
# These cases pin the three distinct shapes with the real scripts:
#   * lane completeness — every registered class is claimed by some lane;
#   * a real end-to-end --record/--verify over a realistically-shaped rolling
#     ledger, with a negative half proving the verify is live;
#   * opt-in-gated classes are not parked in a lane that only all-skips them,
#     and the real --record still refuses to credit an all-skipped class.
# ---------------------------------------------------------------------------
LEDGER_SCRIPT="$ROOT_DIR/scripts/check-test-execution-ledger.sh"
CONFIDENCE_GATE_SCRIPT="$ROOT_DIR/scripts/pre-release-confidence-gate.sh"

# The source-set the release job's ledger step actually verifies, read out of
# the committed workflow rather than restated here.
release_step_verify_source_set() {
  sed -nE 's/.*check-test-execution-ledger\.sh --verify .*--source-set ([A-Za-z,-]+).*/\1/p' \
    "$ROOT_DIR/.github/workflows/release-emulator-validation.yml" | head -n 1
}

# Every registered class the release step's --verify demands evidence for.
# Derived by asking the real guard itself (--report never fails, and with a
# one-row ledger every registered class lands in the never-executed list), so
# this harness has no second copy of the registration rules.
release_step_registered_classes() {
  local seed="$WORK_DIR/registered-seed.tsv"
  printf 'zz.HarnessSeed\t1\tseed\n' > "$seed"
  bash "$LEDGER_SCRIPT" --verify --report --max-age-days 7 \
    --source-set "$(release_step_verify_source_set)" \
    --ledger "$seed" --now 2000000000 2>&1 |
    sed -n '/have NEVER executed/,$p' | tail -n +2 | sed 's/^[[:space:]]*//' |
    grep -E '^[a-z][A-Za-z0-9_.]*\.[A-Z][A-Za-z0-9_]*$' | LC_ALL=C sort -u
}

# The androidTest classes the release gate itself can credit: every class in the
# shared modules whose :connectedDebugAndroidTest the confidence gate runs. The
# module list is grepped out of the gate script, so dropping a module from the
# gate makes this shrink and the lane-completeness case red — which is exactly
# how UiKitPrimitivesTest went unnoticed.
release_gate_shared_module_classes() {
  local module relative fqcn source_file
  while IFS= read -r module; do
    [[ -n "$module" ]] || continue
    [[ -d "$ROOT_DIR/$module/src/androidTest" ]] || continue
    while IFS= read -r source_file; do
      relative="${source_file#"$ROOT_DIR/$module"/src/androidTest/}"
      relative="${relative#java/}"
      relative="${relative#kotlin/}"
      relative="${relative%.kt}"
      relative="${relative%.java}"
      fqcn="${relative//\//.}"
      [[ "$fqcn" =~ ^[a-z][A-Za-z0-9_.]*\.[A-Z][A-Za-z0-9_]*$ ]] && printf '%s\n' "$fqcn"
    done < <(find "$ROOT_DIR/$module/src/androidTest" -type f \
      \( -name '*.kt' -o -name '*.java' \) 2>/dev/null)
    # Read the module list off the gate's actual `./gradlew …` invocation
    # lines only. Scanning the whole file would count a module named in a
    # COMMENT, so deleting the task while leaving the comment behind would keep
    # this harness green over a lane that no longer runs.
  done < <(grep -E '\./gradlew' "$CONFIDENCE_GATE_SCRIPT" |
    grep -oE ':shared:[a-z0-9-]+:connectedDebugAndroidTest' |
    sed 's/:connectedDebugAndroidTest//; s|^:||; s|:|/|g' | LC_ALL=C sort -u)
}

# The union of every lane that can put a class into the rolling ledger.
#
# The nightly lane is NOT one of them any more. It contributed two sources —
# `--selected-from nightly-phase1` and the suite's own `--print-later-phase-classes`
# — and both named `:app:connectedDebugAndroidTest` classes from a module the
# rewrite deleted, so every FQCN they produced was a phantom. The connected lane
# that actually runs today is app2's (`:app2:connectedDebugAndroidTest`, issue
# #2474), plus the two shared modules the release confidence gate drives
# directly.
ledger_creditable_classes() {
  {
    bash "$LEDGER_SCRIPT" --print-selected --selected-from unit-debug
    bash "$LEDGER_SCRIPT" --print-selected --selected-from unit-release
    bash "$LEDGER_SCRIPT" --print-selected --selected-from app2-journey
    release_gate_shared_module_classes
  } | LC_ALL=C sort -u
}

every_registered_class_is_claimed_by_a_lane_that_can_credit_it() {
  local registered="$WORK_DIR/lane-registered.txt"
  local creditable="$WORK_DIR/lane-creditable.txt"
  release_step_registered_classes > "$registered"
  ledger_creditable_classes > "$creditable"

  # Floors re-derived for the app2 tree (registered ~178, creditable ~180); the
  # old >500 described the pre-rewrite client. Low enough to catch a collapsed
  # derivation, high enough that half the suite disappearing still trips it.
  [[ "$(wc -l < "$registered")" -gt 120 ]] ||
    fail "registered class derivation collapsed ($(wc -l < "$registered") classes) — the harness would pass vacuously"
  [[ "$(wc -l < "$creditable")" -gt 120 ]] ||
    fail "lane-creditable derivation collapsed ($(wc -l < "$creditable") classes)"
  grep -qx 'com.pocketshell.uikit.components.UiKitPrimitivesTest' "$creditable" ||
    fail "no lane can credit UiKitPrimitivesTest — the release gate must run :shared:ui-kit:connectedDebugAndroidTest (#2435)"
  grep -qx 'com.pocketshell.next.terminal.J06BackgroundGraceReturnJourney' "$creditable" ||
    fail "no lane can credit J06BackgroundGraceReturnJourney — the bg/grace journey must run in the app2 journey lane (#2435)"

  local orphans
  orphans="$(LC_ALL=C comm -23 "$registered" "$creditable")"
  [[ -z "$orphans" ]] ||
    fail "$(printf '%s\n' "the release ledger --verify demands evidence for class(es) no CI lane can produce; they will make every release job conclude failure (#2435):" "$orphans")"
  pass_case "every class the release ledger --verify demands is claimed by a lane that can credit it (#2435)"
}

# Build a sandbox in which the REAL ledger script runs against the real tree's
# registration rules but a fixture rolling ledger and fixture results.
make_real_ledger_sandbox() {
  local sandbox="$1"
  local repository="$sandbox/repository"
  mkdir -p "$repository" "$sandbox/runner-temp"
  # `scripts` is a symlink so `scripts/check-test-execution-ledger.sh` in the
  # extracted step body is the REAL script. `find` does not descend symlinks,
  # so the real repo's build output cannot leak into the staged set.
  ln -sfn "$ROOT_DIR/scripts" "$repository/scripts"
  extract_ledger_step_body "$ROOT_DIR/.github/workflows/release-emulator-validation.yml" \
    > "$sandbox/ledger-step.sh"
  [[ -s "$sandbox/ledger-step.sh" ]] ||
    fail "could not extract the ledger step body for the real-ledger case"
  printf '%s\n' "$repository"
}

run_real_ledger_step() {
  local sandbox="$1" repository="$2"
  (
    cd "$repository" || exit 90
    RUNNER_TEMP="$sandbox/runner-temp" \
      POCKETSHELL_TEST_LEDGER="$sandbox/rolling-ledger.tsv" \
      POCKETSHELL_TEST_AREAS_REPO_ROOT="$ROOT_DIR" \
      POCKETSHELL_TEST_AREAS_MANIFEST="$ROOT_DIR/scripts/test-areas.txt" \
      POCKETSHELL_TEST_AREAS_JOURNEY_SUITE="$ROOT_DIR/scripts/ci-app2-journey-suite.sh" \
      POCKETSHELL_TEST_AREAS_UNCONVENTIONAL="$ROOT_DIR/scripts/test-unconventional-test-files.txt" \
      bash "$sandbox/ledger-step.sh" 2>&1
  )
}

# Write one real JUnit XML per class into a results tree the step's collector
# reaches (`*/build/outputs/androidTest-results/*`).
stage_release_lane_results() {
  local repository="$1" classes_file="$2"
  local destination="$repository/shared/core-terminal/build/outputs/androidTest-results/connected"
  mkdir -p "$destination"
  local fqcn
  while IFS= read -r fqcn; do
    [[ -n "$fqcn" ]] || continue
    write_junit_xml "$destination/TEST-$fqcn.xml" "$fqcn"
  done < "$classes_file"
}

the_real_ledger_verify_passes_after_a_green_release_run() {
  local sandbox="$WORK_DIR/real-ledger-verify"
  local repository
  repository="$(make_real_ledger_sandbox "$sandbox")"

  local creditable="$WORK_DIR/real-creditable.txt"
  local release_owned="$WORK_DIR/real-release-owned.txt"
  local seeded="$WORK_DIR/real-seeded.txt"
  ledger_creditable_classes > "$creditable"
  release_gate_shared_module_classes | LC_ALL=C sort -u > "$release_owned"
  # The rolling ledger arrives from the unit + nightly caches with everything
  # EXCEPT what this release run is about to record itself.
  LC_ALL=C comm -23 "$creditable" "$release_owned" > "$seeded"
  [[ "$(wc -l < "$seeded")" -gt 120 ]] ||
    fail "seeded rolling ledger collapsed ($(wc -l < "$seeded") classes)"
  [[ "$(wc -l < "$release_owned")" -ge 2 ]] ||
    fail "the release lane owns fewer than 2 classes; the fixture would not exercise --record"

  local now
  now="$(date +%s)"
  awk -v now="$now" '{ printf "%s\t%s\tunit\n", $0, now }' "$seeded" |
    LC_ALL=C sort > "$sandbox/rolling-ledger.tsv"
  stage_release_lane_results "$repository" "$release_owned"

  local output rc=0
  output="$(run_real_ledger_step "$sandbox" "$repository")" || rc=$?
  (( rc == 0 )) ||
    fail "$(printf '%s\n' "the REAL ledger step failed after a green release run (rc=$rc):" "$output")"
  [[ "$output" == *"PASS: every registered test class executed inside the cadence window."* ]] ||
    fail "$(printf '%s\n' "the real --verify did not report a clean cadence window:" "$output")"
  [[ "$output" == *"recorded "* ]] ||
    fail "$(printf '%s\n' "the real --record never credited the release run's own results:" "$output")"

  # Negative half: the verify must be LIVE. Drop one class the release run does
  # not record and the same step must go red — otherwise the green above proves
  # nothing about the guard, only about the plumbing.
  local victim
  victim="$(head -n 1 "$seeded")"
  grep -v -F -x "$victim	$now	unit" "$sandbox/rolling-ledger.tsv" > "$sandbox/rolling-ledger.tsv.tmp"
  mv "$sandbox/rolling-ledger.tsv.tmp" "$sandbox/rolling-ledger.tsv"
  rc=0
  output="$(run_real_ledger_step "$sandbox" "$repository")" || rc=$?
  (( rc == 1 )) ||
    fail "the real ledger step passed with $victim missing from the rolling ledger (rc=$rc) — --verify is not live"
  [[ "$output" == *"$victim"* ]] ||
    fail "$(printf '%s\n' "the real --verify did not name the missing class $victim:" "$output")"
  pass_case "the REAL check-test-execution-ledger.sh --record/--verify chain closes for a green release run (#2435)"
}

optin_gated_classes_run_where_the_optin_is_and_all_skipped_results_stay_uncredited() {
  # THE NIGHTLY HALF OF THIS CASE IS DELETED (D22). It compared the nightly
  # suite's phase-1 exclusion list against the `com.pocketshell.app.bootstrap|hosts`
  # classes phase 3 selected with `pocketshellBootstrapScenarios`, to catch an
  # opt-in-gated class parked in a phase that passes no opt-in (the
  # HostCardStatusChipTest / HostCardSetupBadgeTest shape). Every class on both
  # sides of that comparison was deleted with the app module, and app2's journey
  # lane has no opt-in-gated phases at all — it runs one unfiltered
  # instrumentation pass, so there is no phase for a gated class to be parked in.
  # Re-add this half if the journey lane ever grows opt-in phases.
  #
  # And prove the underlying rule with the real script: an all-skipped class is
  # NOT coverage, so parking an opt-in class in a lane without the opt-in can
  # never satisfy --verify.
  local results="$WORK_DIR/optin-results"
  local ledger="$WORK_DIR/optin-ledger.tsv"
  mkdir -p "$results"
  cat > "$results/TEST-com.example.OptInGatedTest.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.example.OptInGatedTest" tests="2" skipped="2" failures="0" errors="0">
  <testcase name="one" classname="com.example.OptInGatedTest" time="0"><skipped/></testcase>
  <testcase name="two" classname="com.example.OptInGatedTest" time="0"><skipped/></testcase>
</testsuite>
XML
  write_junit_xml "$results/TEST-com.example.RanTest.xml" com.example.RanTest
  bash "$LEDGER_SCRIPT" --record "$results" --ledger "$ledger" --tier harness --now 1700000000 \
    > /dev/null 2>&1 ||
    fail "the real --record refused a results tree that contained a genuinely executed class"
  grep -q '^com.example.RanTest	' "$ledger" ||
    fail "the real --record did not credit the executed class"
  ! grep -q '^com.example.OptInGatedTest	' "$ledger" ||
    fail "the real --record credited an ALL-SKIPPED class as executed"
  pass_case "opt-in-gated classes run in the phase that passes the opt-in; all-skipped results stay uncredited (#2435)"
}

detached_instrumentation_runs_produce_ledger_creditable_junit_xml() {
  local converter="$ROOT_DIR/scripts/instrumentation-log-to-junit-xml.sh"
  [[ -x "$converter" || -f "$converter" ]] ||
    fail "scripts/instrumentation-log-to-junit-xml.sh is missing (#2435)"
  local output rc=0
  output="$(bash "$converter" --self-test 2>&1)" || rc=$?
  (( rc == 0 )) ||
    fail "$(printf '%s\n' "instrumentation-log-to-junit-xml self-test failed (rc=$rc):" "$output")"
  [[ "$output" == *"--record credits the converted class"* ]] ||
    fail "$(printf '%s\n' "the converter self-test no longer proves the REAL ledger credits its XML:" "$output")"

  # The production caller must actually invoke it, with --require-class so a run
  # that selected nothing cannot be laundered into a ledger entry.
  #
  # The CALLER MOVED (issue #2481). It used to be
  # scripts/release-emulator-validation.sh, whose TERMINAL_RELEASE_GATE /
  # LONG_RUNNING_TEST branches ran RealAgentReleaseGateTest and
  # LongRunningSessionStabilityTest through direct `am instrument`; both classes
  # were deleted with the `app` module and both branches went with them (D22).
  # The surviving detached `am instrument` in the release chain is the
  # pre-release confidence gate's UNFILTERED run of app2's whole instrumented
  # set — same accounting gap, same fix. (The #2264 two-phase process-restart
  # harness was hard-cut earlier for the same reason; see the note in
  # .github/workflows/tests.yml's `guards-ci-harness` job.)
  local release_gate="$ROOT_DIR/scripts/pre-release-confidence-gate.sh"
  grep -q 'instrumentation-log-to-junit-xml.sh' "$release_gate" ||
    fail "the pre-release gate's detached am-instrument run no longer produces ledger JUnit XML (#2435/#2481)"
  # ...and the CALL (not the helper definition, which necessarily sits above)
  # must be wired AFTER the suite it credits: a converter call the suite never
  # reaches would encode a stale log, or nothing at all.
  local convert_line suite_line
  suite_line="$(grep -n 'run_app2_instrumented_suite_script "\$app2_suite_diagnostics_file"' "$release_gate" | head -1 | cut -d: -f1)"
  convert_line="$(grep -nE '^[[:space:]]+record_detached_instrumentation_junit_xml' "$release_gate" | tail -1 | cut -d: -f1)"
  [[ -n "$suite_line" && -n "$convert_line" && "$convert_line" -gt "$suite_line" ]] ||
    fail "the ledger XML conversion is not wired after the app2 instrumented suite run (#2481)"
  grep -q -- '--require-class' "$release_gate" ||
    fail "the release gate converts without --require-class (an empty run could be credited) (#2435)"
  pass_case "direct am instrument runs produce JUnit XML the real ledger credits (#2435)"
}

# ---------------------------------------------------------------------------
# The release chain's own detached-`am instrument` helper, end to end into the
# REAL ledger. Issue #2481 moved it from scripts/release-emulator-validation.sh
# (whose real-agent / long-running callers were deleted with the `app` module
# classes they drove) to scripts/pre-release-confidence-gate.sh, whose
# unfiltered app2 instrumented run is the surviving detached instrumentation.
# The WIRED-IN half is asserted statically by
# detached_instrumentation_runs_produce_ledger_creditable_junit_xml above
# (the converter is called, with --require-class, AFTER the suite it credits);
# this case drives the helper itself into the real ledger, both directions.
# ---------------------------------------------------------------------------
# A LABEL, not a lookup: every use below feeds this name into a synthetic
# instrumentation transcript and asserts the converter and the real ledger carry
# it through. Pointed at a live app2 journey so the fixture reads as something
# the release gate could actually run.
LONG_RUNNING_CLASS='com.pocketshell.next.connect.J01ConnectAndTrustJourney'

load_detached_instrumentation_recorder() {
  # Take the production helper out of the release wrapper rather than restating
  # it: a rewrite that stops writing ledger XML must redden here.
  local extracted="$WORK_DIR/detached-recorder.sh"
  awk '
    /^record_detached_instrumentation_junit_xml\(\) \{$/ { found = 1 }
    found { print }
    found && /^\}$/ { exit }
  ' "$ROOT_DIR/scripts/pre-release-confidence-gate.sh" > "$extracted"
  [[ -s "$extracted" ]] ||
    fail "could not extract record_detached_instrumentation_junit_xml from the pre-release gate"
  # shellcheck disable=SC1090
  source "$extracted"
}

the_real_ledger_credits_a_converted_detached_instrumentation_run() {
  load_detached_instrumentation_recorder

  local transcript="$WORK_DIR/detached-success.log"
  {
    printf 'INSTRUMENTATION_STATUS: class=%s\n' "$LONG_RUNNING_CLASS"
    printf 'INSTRUMENTATION_STATUS: test=tenMinuteForegroundHoldRetainsTmuxSessionWithoutReconnectsOrMemoryGrowth\n'
    printf 'INSTRUMENTATION_STATUS: current=1\n'
    printf 'INSTRUMENTATION_STATUS: numtests=1\n'
    printf 'INSTRUMENTATION_STATUS_CODE: 0\n'
    printf 'INSTRUMENTATION_CODE: -1\n'
    printf 'OK (1 test)\n'
  } > "$transcript"

  DETACHED_INSTRUMENTATION_RESULTS_DIR="$WORK_DIR/detached-results" \
    record_detached_instrumentation_junit_xml "$LONG_RUNNING_CLASS" "$transcript" ||
    fail "the release wrapper's detached recorder failed on a green transcript"
  local xml="$WORK_DIR/detached-results/TEST-$LONG_RUNNING_CLASS.xml"
  [[ -s "$xml" ]] ||
    fail "a green detached instrumentation run wrote no ledger JUnit XML at $xml (#2435)"
  grep -Fq "classname=\"$LONG_RUNNING_CLASS\"" "$xml" ||
    fail "the converted detached JUnit XML does not name the test class (#2435)"

  local ledger="$WORK_DIR/detached-ledger.tsv"
  bash "$LEDGER_SCRIPT" --record "$WORK_DIR/detached-results" \
    --ledger "$ledger" --tier release --now 1700000000 > /dev/null ||
    fail "the real execution ledger refused the converted detached results (#2435)"
  grep -Fq "$LONG_RUNNING_CLASS	1700000000	release" "$ledger" ||
    fail "the real execution ledger did not credit $LONG_RUNNING_CLASS (#2435)"

  # Negative half: an all-skipped transcript (the class without its opt-in arg)
  # must produce NO evidence at all, so a self-skip can never be laundered into
  # a ledger entry by --require-class.
  local skip_log="$WORK_DIR/detached-selfskip.log"
  {
    printf 'INSTRUMENTATION_STATUS: class=%s\n' "$LONG_RUNNING_CLASS"
    printf 'INSTRUMENTATION_STATUS: test=tenMinuteForegroundHoldRetainsTmuxSessionWithoutReconnectsOrMemoryGrowth\n'
    printf 'INSTRUMENTATION_STATUS_CODE: 1\n'
    printf 'INSTRUMENTATION_STATUS: class=%s\n' "$LONG_RUNNING_CLASS"
    printf 'INSTRUMENTATION_STATUS: stack=org.junit.AssumptionViolatedException: opt-in absent\n'
    printf 'INSTRUMENTATION_STATUS: test=tenMinuteForegroundHoldRetainsTmuxSessionWithoutReconnectsOrMemoryGrowth\n'
    printf 'INSTRUMENTATION_STATUS_CODE: -4\n'
    printf 'INSTRUMENTATION_CODE: -1\n'
  } > "$skip_log"
  DETACHED_INSTRUMENTATION_RESULTS_DIR="$WORK_DIR/detached-results-selfskip" \
    record_detached_instrumentation_junit_xml "$LONG_RUNNING_CLASS" "$skip_log" > /dev/null 2>&1
  [[ ! -e "$WORK_DIR/detached-results-selfskip/TEST-$LONG_RUNNING_CLASS.xml" ]] ||
    fail "an all-skipped detached run still produced ledger JUnit XML (#2435)"
  pass_case "the real ledger credits a converted detached instrumentation run, and never a self-skipped one (#2435)"
}

# Each case is independently selectable so a mutation run can prove that one
# assertion, rather than some adjacent case, bears the cost of each contract.
run_case ledger-step-present ledger_step_records_retained_release_xml
run_case ledger-step-absent ledger_step_still_refuses_a_run_with_no_junit_xml
run_case success-retention successful_release_run_leaves_ledger_xml_for_the_post_cleanup_step
run_case validated-rc-gate validated_rc_marker_still_requires_a_genuinely_successful_validation_job
run_case ledger-lane-coverage every_registered_class_is_claimed_by_a_lane_that_can_credit_it
run_case ledger-verify-real the_real_ledger_verify_passes_after_a_green_release_run
run_case ledger-optin-lane optin_gated_classes_run_where_the_optin_is_and_all_skipped_results_stay_uncredited
run_case ledger-instrumentation-xml detached_instrumentation_runs_produce_ledger_creditable_junit_xml
run_case ledger-detached-credit the_real_ledger_credits_a_converted_detached_instrumentation_run

# Issue #2113: a harness that exits 0 having run nothing is the vacuous green
# process.md catalogues, so the count is asserted rather than assumed.
expected_cases=9
if [[ -n "${POCKETSHELL_RELEASE_LEDGER_TEST_CASE:-}" ]]; then
  expected_cases=1
fi
if (( CASES != expected_cases )); then
  fail "expected $expected_cases cases to run, saw $CASES (a case was skipped or silently removed)"
fi
printf 'PASS: release execution-ledger lane coverage (%s cases)\n' "$CASES"
