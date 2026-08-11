#!/usr/bin/env bash
# The library-only source flag and nested fixture runner are consumed through
# dynamic sourcing/calls that ShellCheck cannot resolve.
# shellcheck disable=SC2034
set -uo pipefail

# ---------------------------------------------------------------------------
# Fail-closed matrix for scripts/reuse-ci-unit-evidence.sh (issue #2064)
#
# The acceptor lets the pre-release confidence gate SKIP its local unit run when
# the required `Unit tests` check is green on the exact release commit. That is
# only safe if a missing/red/foreign/vacuous CI result can never read as a pass.
# This drives the REAL acceptor over a real git checkout with a real pushed
# origin; only `gh` is stubbed, and the stub replays raw API bodies so the real
# JSON projection, the real verification-root assembly and the real count check
# all execute. No network, no Gradle, no Android SDK — about three seconds.
#
# It ALSO pins the acceptor's in-script executed-count check against
# scripts/check-executed-test-counts.sh, the #1646 guard whose rules it
# re-implements. (The acceptor cannot shell out to that guard: it is reachable
# from the release chain, and scripts/check-release-gate-execution-profile.sh
# rejects chain-reachable scripts that are not themselves in
# RELEASE_CHAIN_SCRIPTS. This test script is NOT chain-reachable, so it can hold
# both implementations side by side and require identical verdicts — which is
# what stops them drifting apart.)
#
# Usage: scripts/test-reuse-ci-unit-evidence.sh
# ---------------------------------------------------------------------------

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The acceptor's helper functions are sourced for the pure-parser and
# cross-implementation cases below; every acceptance/decline case still runs it
# as a real child process.
POCKETSHELL_CI_EVIDENCE_LIB_ONLY=1
# shellcheck source=/dev/null
source "$ROOT_DIR/scripts/reuse-ci-unit-evidence.sh"

CHECKS=0

expect_case() {
  local expectation="$1"
  local label="$2"
  shift 2
  CHECKS=$((CHECKS + 1))
  local rc=0
  "$@" > "$CASE_LOG" 2>&1 || rc=$?
  if [[ "$expectation" == "accept" && "$rc" -ne 0 ]]; then
    printf 'FAIL: case %s should have ACCEPTED but exited %s\n' "$label" "$rc" >&2
    tail -n 40 "$CASE_LOG" >&2
    return 1
  fi
  if [[ "$expectation" == "decline" && "$rc" -eq 0 ]]; then
    printf 'FAIL: case %s should have DECLINED but exited 0\n' "$label" >&2
    tail -n 40 "$CASE_LOG" >&2
    return 1
  fi
  printf '  ok  %-52s (%s, rc=%s)\n' "$label" "$expectation" "$rc"
}

write_fixture_api() {
  local dir="$1"
  local sha="$2"
  local check_status="$3"
  local check_conclusion="$4"
  local check_head_sha="$5"
  local run_head_sha="$6"
  local debug_job_conclusion="$7"
  local release_job_conclusion="${8:-$7}"

  cat > "$dir/check-runs.json" <<JSON
{"total_count": 2, "check_runs": [
  {"name": "Python utility tests (pocketshell)", "status": "completed", "conclusion": "success",
   "head_sha": "$sha", "details_url": "https://github.com/o/r/actions/runs/900/job/1"},
  {"name": "Unit tests", "status": "$check_status", "conclusion": "$check_conclusion",
   "head_sha": "$check_head_sha", "details_url": "https://github.com/o/r/actions/runs/4242/job/7"}
]}
JSON
  cat > "$dir/workflow-run.json" <<JSON
{"head_sha": "$run_head_sha", "status": "completed", "conclusion": "success",
 "html_url": "https://github.com/o/r/actions/runs/4242"}
JSON
  cat > "$dir/workflow-jobs.json" <<JSON
{"total_count": 3, "jobs": [
  {"name": "Static guards", "status": "completed", "conclusion": "success", "completed_at": "2026-08-09T06:40:00Z"},
  {"name": "JVM unit tests (Debug)", "status": "completed", "conclusion": "$debug_job_conclusion", "completed_at": "2026-08-11T06:51:13Z"},
  {"name": "JVM unit tests (Release)", "status": "completed", "conclusion": "$release_job_conclusion", "completed_at": "2026-08-11T06:53:13Z"}
]}
JSON
}

write_stub_gh() {
  local stub="$1"
  local api_dir="$2"
  local artifact_src="$3"
  cat > "$stub" <<STUB
#!/usr/bin/env bash
set -uo pipefail
case "\$1" in
  api)
    case "\$2" in
      */check-runs*) cat "$api_dir/check-runs.json" ;;
      */jobs*) cat "$api_dir/workflow-jobs.json" ;;
      */actions/runs/*) cat "$api_dir/workflow-run.json" ;;
      *) printf 'stub gh: unexpected api path %s\n' "\$2" >&2; exit 1 ;;
    esac
    ;;
  run)
    dest=""
    name=""
    prev=""
    for arg in "\$@"; do
      if [ "\$prev" = "--dir" ]; then dest="\$arg"; fi
      if [ "\$prev" = "--name" ]; then name="\$arg"; fi
      prev="\$arg"
    done
    [ -n "\$dest" ] || { printf 'stub gh: no --dir\n' >&2; exit 1; }
    [ -n "\$name" ] || { printf 'stub gh: no --name\n' >&2; exit 1; }
    if [ ! -d "$artifact_src/\$name" ]; then
      printf 'stub gh: artifact %s not found\n' "\$name" >&2
      exit 1
    fi
    mkdir -p "\$dest"
    cp -a "$artifact_src/\$name/." "\$dest/"
    ;;
  *) printf 'stub gh: unexpected verb %s\n' "\$1" >&2; exit 1 ;;
esac
STUB
  chmod +x "$stub"
}

run_matrix() {
  local sandbox
  sandbox="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-reuse-ci-selftest.XXXXXX")"
  # shellcheck disable=SC2064
  trap "rm -rf '$sandbox'" RETURN
  CASE_LOG="$sandbox/case.log"

  printf 'reuse-ci-unit-evidence fail-closed matrix (issue #2064)\n'

  # --- the URL parser, on its own ------------------------------------------
  local parsed
  CHECKS=$((CHECKS + 1))
  parsed="$(pocketshell_ci_evidence_repo_slug_from_url 'git@github.com:alexeygrigorev/pocketshell.git')" || parsed=""
  [[ "$parsed" == "alexeygrigorev/pocketshell" ]] ||
    { printf 'FAIL: ssh remote parsed as %s\n' "$parsed" >&2; return 1; }
  printf '  ok  %-52s (%s)\n' "slug from ssh remote" "$parsed"

  CHECKS=$((CHECKS + 1))
  parsed="$(pocketshell_ci_evidence_repo_slug_from_url 'https://github.com/alexeygrigorev/pocketshell')" || parsed=""
  [[ "$parsed" == "alexeygrigorev/pocketshell" ]] ||
    { printf 'FAIL: https remote parsed as %s\n' "$parsed" >&2; return 1; }
  printf '  ok  %-52s (%s)\n' "slug from https remote" "$parsed"

  CHECKS=$((CHECKS + 1))
  if pocketshell_ci_evidence_repo_slug_from_url 'https://gitlab.com/o/r.git' >/dev/null 2>&1; then
    printf 'FAIL: a non-GitHub remote must not yield a slug\n' >&2
    return 1
  fi
  printf '  ok  %-52s\n' "non-GitHub remote yields no slug"

  CHECKS=$((CHECKS + 1))
  if pocketshell_ci_evidence_run_id_from_details_url 'https://github.com/o/r/checks' >/dev/null 2>&1; then
    printf 'FAIL: a details_url without /actions/runs/<id> must not yield a run id\n' >&2
    return 1
  fi
  printf '  ok  %-52s\n' "unparseable details_url yields no run id"

  # The runtime acceptor requires these rendered names from GitHub too. This
  # local pin makes workflow drift fail with a direct contract diagnostic.
  CHECKS=$((CHECKS + 1))
  pocketshell_ci_evidence_assert_workflow_contract "$ROOT_DIR/.github/workflows/tests.yml" ||
    { printf 'FAIL: current tests.yml does not match the #2069 Debug/Release contract\n' >&2; return 1; }
  sed 's/unit-test-reports-${{ matrix.variant }}/unit-test-results-${{ matrix.variant }}/' \
    "$ROOT_DIR/.github/workflows/tests.yml" > "$sandbox/workflow-drift.yml"
  if pocketshell_ci_evidence_assert_workflow_contract "$sandbox/workflow-drift.yml" >/dev/null 2>&1; then
    printf 'FAIL: workflow contract accepted a renamed artifact family\n' >&2
    return 1
  fi
  printf '  ok  %-52s\n' "workflow job/artifact contract is pinned and reddens"

  # --- a real git checkout at a real pushed commit --------------------------
  local origin="$sandbox/origin.git"
  local tree="$sandbox/tree"
  git init --quiet --bare "$origin"
  git init --quiet -b main "$tree"
  git -C "$tree" config user.email selftest@example.com
  git -C "$tree" config user.name selftest
  git -C "$tree" remote add origin "$origin"

  # A miniature release tree: one Android module and one JVM module, the real
  # floors file shape, the real guard scripts, and the real workflow.
  mkdir -p "$tree/scripts" "$tree/.github/workflows"
  mkdir -p "$tree/app/src/test/java" "$tree/shared/core-x/src/test/java"
  cat > "$tree/settings.gradle.kts" <<'SETTINGS'
include(":app")
include(":shared:core-x")
SETTINGS
  printf 'plugins { id("com.android.application") }\n' > "$tree/app/build.gradle.kts"
  # #2069's two shards cover Android Debug/Release tasks. A JVM-only `test`
  # task is intentionally rejected by the current coverage-invariant guard.
  printf 'plugins { id("com.android.library") }\n' > "$tree/shared/core-x/build.gradle.kts"
  printf 'class AppTest\n' > "$tree/app/src/test/java/AppTest.kt"
  printf 'class CoreXTest\n' > "$tree/shared/core-x/src/test/java/CoreXTest.kt"
  printf '# task floor\n:app:testDebugUnitTest 2\n' > "$tree/scripts/executed-test-count-floors.txt"
  cp "$ROOT_DIR/scripts/check-executed-test-counts.sh" "$tree/scripts/"
  cp "$ROOT_DIR/scripts/check-ci-unit-forced-execution.py" "$tree/scripts/"
  chmod +x "$tree/scripts/check-executed-test-counts.sh" "$tree/scripts/check-ci-unit-forced-execution.py"
  cp "$ROOT_DIR/.github/workflows/tests.yml" "$tree/.github/workflows/tests.yml"
  git -C "$tree" add -A
  git -C "$tree" commit --quiet -m "self-test tree"
  git -C "$tree" push --quiet origin main
  git -C "$tree" fetch --quiet origin main
  local sha
  sha="$(git -C "$tree" rev-parse HEAD)"

  # A green downloaded artifact: real result XML shapes, per module.
  local artifact="$sandbox/artifacts-green"
  local artifact_debug="$artifact/unit-test-reports-Debug"
  local artifact_release="$artifact/unit-test-reports-Release"
  mkdir -p "$artifact_debug/app/build/test-results/testDebugUnitTest"
  cat > "$artifact_debug/app/build/test-results/testDebugUnitTest/TEST-AppTest.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="AppTest" tests="4" skipped="0" failures="0" errors="0" time="0.5">
</testsuite>
XML
  mkdir -p "$artifact_release/app/build/test-results/testReleaseUnitTest"
  cat > "$artifact_release/app/build/test-results/testReleaseUnitTest/TEST-AppTest.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="AppTest" tests="4" skipped="0" failures="0" errors="0" time="0.5">
</testsuite>
XML
  mkdir -p "$artifact_debug/shared/core-x/build/test-results/testDebugUnitTest"
  cat > "$artifact_debug/shared/core-x/build/test-results/testDebugUnitTest/TEST-CoreXTest.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="CoreXTest" tests="3" skipped="0" failures="0" errors="0" time="0.2">
</testsuite>
XML
  mkdir -p "$artifact_release/shared/core-x/build/test-results/testReleaseUnitTest"
  cat > "$artifact_release/shared/core-x/build/test-results/testReleaseUnitTest/TEST-CoreXTest.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="CoreXTest" tests="3" skipped="0" failures="0" errors="0" time="0.2">
</testsuite>
XML

  # An artifact whose :app:testReleaseUnitTest results are absent — exactly the
  # "wrong task / task never ran" disguise the count guard exists to catch.
  local artifact_missing="$sandbox/artifact-missing-task"
  cp -a "$artifact" "$artifact_missing"
  rm -rf "$artifact_missing/unit-test-reports-Release/app/build/test-results/testReleaseUnitTest"

  # An artifact where every test was SKIPPED: real XML, real files, zero
  # executed. The G3 vacuous pass.
  local artifact_skipped="$sandbox/artifact-all-skipped"
  cp -a "$artifact" "$artifact_skipped"
  cat > "$artifact_skipped/unit-test-reports-Debug/app/build/test-results/testDebugUnitTest/TEST-AppTest.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="AppTest" tests="4" skipped="4" failures="0" errors="0" time="0.5">
</testsuite>
XML

  local api="$sandbox/api"
  mkdir -p "$api"
  local stub="$sandbox/gh-stub"

  local run_case
  run_case() {
    local out="$1"
    rm -rf "$out"
    env POCKETSHELL_CI_EVIDENCE_GH="$stub" \
        POCKETSHELL_CI_EVIDENCE_REPO="o/r" \
        RELEASE_GATE_REUSE_CI_UNIT="${RELEASE_GATE_REUSE_CI_UNIT:-1}" \
        bash "$ROOT_DIR/scripts/reuse-ci-unit-evidence.sh" \
          --source-root "$tree" --tree-root "$tree" --out-dir "$out" --sha "$sha"
  }

  # --- GREEN ----------------------------------------------------------------
  write_fixture_api "$api" "$sha" completed success "$sha" "$sha" success
  write_stub_gh "$stub" "$api" "$artifact"
  expect_case accept "clean tree + green Unit tests + real counts" \
    run_case "$sandbox/out-green" || return 1

  # --- RED: the check run is not green --------------------------------------
  write_fixture_api "$api" "$sha" completed failure "$sha" "$sha" success
  expect_case decline "check run conclusion=failure" \
    run_case "$sandbox/out-red-conclusion" || return 1

  write_fixture_api "$api" "$sha" in_progress "" "$sha" "$sha" success
  expect_case decline "check run still in_progress" \
    run_case "$sandbox/out-red-status" || return 1

  # --- RED: the green check belongs to a DIFFERENT commit -------------------
  write_fixture_api "$api" "$sha" completed success \
    "0000000000000000000000000000000000000000" "$sha" success
  expect_case decline "check run head_sha is another commit" \
    run_case "$sandbox/out-red-check-sha" || return 1

  write_fixture_api "$api" "$sha" completed success "$sha" \
    "1111111111111111111111111111111111111111" success
  expect_case decline "workflow run head_sha is another commit" \
    run_case "$sandbox/out-red-run-sha" || return 1

  # --- RED: the job that runs the tests failed ------------------------------
  write_fixture_api "$api" "$sha" completed success "$sha" "$sha" failure
  expect_case decline "both JVM unit shards conclusion=failure" \
    run_case "$sandbox/out-red-job" || return 1

  write_fixture_api "$api" "$sha" completed success "$sha" "$sha" success failure
  expect_case decline "Release JVM unit shard conclusion=failure" \
    run_case "$sandbox/out-red-release-job" || return 1

  write_fixture_api "$api" "$sha" completed success "$sha" "$sha" success
  python3 - "$api/workflow-jobs.json" <<'PYTHON'
import json
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as handle:
    payload = json.load(handle)
payload["jobs"] = [
    job for job in payload["jobs"] if job["name"] != "JVM unit tests (Release)"
]
with open(path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle)
PYTHON
  expect_case decline "Release JVM unit shard is missing" \
    run_case "$sandbox/out-red-missing-release-job" || return 1

  # --- RED: no `Unit tests` check run at all --------------------------------
  write_fixture_api "$api" "$sha" completed success "$sha" "$sha" success
  python3 - "$api/check-runs.json" <<'PYTHON'
import json
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as handle:
    payload = json.load(handle)
payload["check_runs"] = [
    run for run in payload["check_runs"] if run["name"] != "Unit tests"
]
with open(path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle)
PYTHON
  expect_case decline "no 'Unit tests' check run for the commit" \
    run_case "$sandbox/out-red-missing-check" || return 1

  # --- RED: the downloaded artifact does not satisfy the floors -------------
  write_fixture_api "$api" "$sha" completed success "$sha" "$sha" success
  write_stub_gh "$stub" "$api" "$artifact_missing"
  expect_case decline "downloaded artifact is missing a required task" \
    run_case "$sandbox/out-red-missing-task" || return 1

  write_stub_gh "$stub" "$api" "$artifact_skipped"
  expect_case decline "downloaded artifact executed 0 tests (all skipped)" \
    run_case "$sandbox/out-red-all-skipped" || return 1

  write_stub_gh "$stub" "$api" "$sandbox/artifact-does-not-exist"
  expect_case decline "artifact download fails" \
    run_case "$sandbox/out-red-no-artifact" || return 1

  local artifact_missing_release="$sandbox/artifact-missing-release"
  cp -a "$artifact" "$artifact_missing_release"
  rm -rf "$artifact_missing_release/unit-test-reports-Release"
  write_stub_gh "$stub" "$api" "$artifact_missing_release"
  expect_case decline "Release shard artifact is missing" \
    run_case "$sandbox/out-red-no-release-artifact" || return 1

  # --- RED: a dirty tree is not the commit ----------------------------------
  write_stub_gh "$stub" "$api" "$artifact"
  printf 'local edit\n' > "$tree/app/src/test/java/Scratch.kt"
  expect_case decline "untracked file in the release checkout" \
    run_case "$sandbox/out-red-untracked" || return 1
  rm -f "$tree/app/src/test/java/Scratch.kt"

  printf 'tracked edit\n' >> "$tree/app/src/test/java/AppTest.kt"
  expect_case decline "modified tracked file in the release checkout" \
    run_case "$sandbox/out-red-dirty" || return 1
  git -C "$tree" checkout --quiet -- app/src/test/java/AppTest.kt

  # --- RED: HEAD is ahead of origin/main ------------------------------------
  printf 'class Extra\n' > "$tree/app/src/test/java/Extra.kt"
  git -C "$tree" add -A
  git -C "$tree" commit --quiet -m "unpushed"
  expect_case decline "HEAD is not the pushed origin/main" \
    run_case "$sandbox/out-red-unpushed" || return 1
  git -C "$tree" reset --quiet --hard "$sha"

  # --- RED: the requested SHA is not HEAD -----------------------------------
  CHECKS=$((CHECKS + 1))
  local rc=0
  env POCKETSHELL_CI_EVIDENCE_GH="$stub" POCKETSHELL_CI_EVIDENCE_REPO="o/r" \
    bash "$ROOT_DIR/scripts/reuse-ci-unit-evidence.sh" \
      --source-root "$tree" --tree-root "$tree" --out-dir "$sandbox/out-red-sha" \
      --sha "2222222222222222222222222222222222222222" \
      > "$CASE_LOG" 2>&1 || rc=$?
  [[ "$rc" -ne 0 ]] ||
    { printf 'FAIL: a --sha that is not HEAD must decline\n' >&2; return 1; }
  printf '  ok  %-52s (decline, rc=%s)\n' "--sha differs from HEAD" "$rc"

  # --- the CI job's own forced-execution guard is the real defence ----------
  # The acceptor deliberately does NOT re-derive "the workflow still forces
  # execution" locally: that guard runs as a step of the `unit` job whose
  # `success` is already a precondition here, so a weakened command cannot
  # produce a green check to reuse. Pin that the guard exists, self-tests, and
  # actually reddens on the weakened command — i.e. the constraint the acceptor
  # leans on is real and not a story.
  CHECKS=$((CHECKS + 1))
  "$ROOT_DIR/scripts/check-ci-unit-forced-execution.py" --self-test >/dev/null 2>&1 ||
    { printf 'FAIL: the CI Unit forced-execution guard fails its own self-test\n' >&2; return 1; }
  "$ROOT_DIR/scripts/check-ci-unit-forced-execution.py" \
    "$ROOT_DIR/.github/workflows/tests.yml" >/dev/null 2>&1 ||
    { printf 'FAIL: the committed workflow does not force execution of the Unit test graph\n' >&2; return 1; }
  sed 's/ --rerun-tasks//' "$ROOT_DIR/.github/workflows/tests.yml" > "$sandbox/weakened-tests.yml"
  if "$ROOT_DIR/scripts/check-ci-unit-forced-execution.py" "$sandbox/weakened-tests.yml" >/dev/null 2>&1; then
    printf 'FAIL: the forced-execution guard accepted a workflow with --rerun-tasks removed, so the acceptor cannot lean on it\n' >&2
    return 1
  fi
  printf '  ok  %-52s\n' "the CI job's forced-execution guard is real and reddens"

  # --- the acceptor's count check agrees with the #1646 guard ---------------
  # The acceptor re-implements scripts/check-executed-test-counts.sh's coverage
  # + floor rules (it cannot invoke it — see that script's header). Pin the two
  # against each other on the same roots so they cannot drift: identical verdict
  # on a healthy artifact, on a missing task, and on an all-skipped one.
  local pin_case pin_artifact
  for pin_case in "green:$artifact:0" "missing-task:$artifact_missing:1" "all-skipped:$artifact_skipped:1"; do
    local pin_name="${pin_case%%:*}"
    local pin_rest="${pin_case#*:}"
    pin_artifact="${pin_rest%:*}"
    local pin_expected="${pin_rest##*:}"
    local pin_root="$sandbox/pin-$pin_name"
    rm -rf "$pin_root"
    pocketshell_ci_evidence_build_verify_root "$tree" "$pin_artifact" "$pin_root" ||
      { printf 'FAIL: could not assemble the pin root for %s\n' "$pin_name" >&2; return 1; }
    local mine=0 theirs=0
    pocketshell_ci_evidence_check_counts "$pin_root" >/dev/null 2>&1 || mine=1
    "$ROOT_DIR/scripts/check-executed-test-counts.sh" --root "$pin_root" >/dev/null 2>&1 || theirs=1
    CHECKS=$((CHECKS + 1))
    if [[ "$mine" != "$pin_expected" || "$theirs" != "$pin_expected" ]]; then
      printf 'FAIL: count-check disagreement on %s (acceptor=%s, #1646 guard=%s, expected=%s)\n' \
        "$pin_name" "$mine" "$theirs" "$pin_expected" >&2
      return 1
    fi
    printf '  ok  %-52s (both=%s)\n' "count check agrees with #1646 guard: $pin_name" "$mine"
  done

  # --- RED: the explicit opt-out ---------------------------------------------
  write_fixture_api "$api" "$sha" completed success "$sha" "$sha" success
  CHECKS=$((CHECKS + 1))
  rc=0
  env POCKETSHELL_CI_EVIDENCE_GH="$stub" POCKETSHELL_CI_EVIDENCE_REPO="o/r" \
    RELEASE_GATE_REUSE_CI_UNIT=0 \
    bash "$ROOT_DIR/scripts/reuse-ci-unit-evidence.sh" \
      --source-root "$tree" --tree-root "$tree" --out-dir "$sandbox/out-red-optout" \
      > "$CASE_LOG" 2>&1 || rc=$?
  [[ "$rc" -ne 0 ]] ||
    { printf 'FAIL: RELEASE_GATE_REUSE_CI_UNIT=0 must decline\n' >&2; return 1; }
  printf '  ok  %-52s (decline, rc=%s)\n' "RELEASE_GATE_REUSE_CI_UNIT=0 opt-out" "$rc"

  # --- GREEN again, to prove none of the reds are permanent -----------------
  expect_case accept "green again after every red case" \
    run_case "$sandbox/out-green-2" || return 1
  grep -q "ACCEPT: reusing the 'Unit tests' evidence" "$sandbox/out-green-2/../case.log" ||
    { printf 'FAIL: the accepting run did not print its ACCEPT provenance line\n' >&2; return 1; }
  [[ -s "$sandbox/out-green-2/evidence.txt" ]] ||
    { printf 'FAIL: the accepting run wrote no evidence.txt\n' >&2; return 1; }

  # This test asserts its OWN check count so it cannot itself pass vacuously
  # (the #1646 convention).
  if [[ "$CHECKS" -ne 28 ]]; then
    printf 'FAIL: ran %s checks, expected 28\n' "$CHECKS" >&2
    return 1
  fi
  printf 'PASS: reuse-ci-unit-evidence fail-closed matrix (%s checks)\n' "$CHECKS"
}


main() {
  run_matrix
}

main "$@"
