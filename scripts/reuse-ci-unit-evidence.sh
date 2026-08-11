#!/usr/bin/env bash
set -uo pipefail

# ---------------------------------------------------------------------------
# SHA-matched reuse of the required CI `Unit tests` evidence (issue #2064)
#
# WHY THIS IS A RIGOUR INCREASE, NOT A REDUCTION
# ----------------------------------------------
# The pre-release confidence gate's `[gradle-compile-unit]` step ran
# `assembleDebug check -x lint -x lintDebug`. Task extraction from the v0.4.42
# r3 console shows `check` contains no custom guard tasks in this repo: it is
# exactly `assembleDebug` plus the complete Gradle `test` graph. Measured, that
# step was 1083s of a 2516s release chain, and 890s of it (82%) was unit-test
# compile + execution —
# 12,362 tests re-run against source that the required `Unit tests` check had
# ALREADY tested green on the very same commit.
#
# And CI's run is STRICTER than the local one. `.github/workflows/tests.yml`'s
# `unit` job runs the whole test graph with `--rerun-tasks --no-build-cache`,
# then the #1646 executed-count guard with a freshness marker and the console
# scan — the per-task floors, the UP-TO-DATE rule and the FROM-CACHE defence.
# The local `check` applies none of those. So consuming the CI result GAINS the
# release those guards.
#
# The saving is real only because the duplication was real. Nothing that gates
# the tag stops running: the emulator/Docker journeys, the migration, the
# old-CLI fixture, the walkthroughs and the visual audit are untouched.
#
# FAIL CLOSED — A MISSING CI RESULT IS NEVER A PASS
# -------------------------------------------------
# Exit 0 means "accepted, the gate may skip its local `check`". ANY other exit
# means "declined" and the caller MUST run the tests locally. Every one of the
# following declines:
#
#   * RELEASE_GATE_REUSE_CI_UNIT=0 (the explicit opt-out);
#   * the source checkout is dirty, INCLUDING untracked files — the gate rsyncs
#     the working TREE, so a dirty tree is not the commit;
#   * HEAD != origin/main after a fetch, or != the requested --sha;
#   * no check run named exactly `Unit tests` for that exact commit SHA;
#   * that check run is not `completed` + `success`, or its head_sha differs;
#   * the workflow run's head_sha differs, or the `JVM unit tests` job is not
#     success (that job is where the tests, the forced-execution wiring guard,
#     the freshness marker and the FROM-CACHE console scan all run — so a
#     weakened or vacuous Unit run cannot conclude success);
#   * the `unit-test-reports` artifact will not download, or is empty;
#   * the DOWNLOADED result XML does not independently satisfy the
#     executed-test-count coverage + per-task floors, re-checked here.
#
# A branch match, a "latest run" match, or a green-looking label with no
# downloadable counts behind it is never accepted. The last bullet is the
# load-bearing one: the reused artefact must satisfy the floors ITSELF, not
# merely be tagged green.
#
# Usage:
#   scripts/reuse-ci-unit-evidence.sh --source-root DIR --tree-root DIR \
#                                     --out-dir DIR [--sha SHA]
#
#   --source-root  the real git checkout (has .git); cleanliness + SHA come
#                  from here.
#   --tree-root    the tree whose settings.gradle.kts / modules / floors /
#                  workflow define the expectations (the gate's isolated
#                  worktree copy). Defaults to --source-root.
#   --out-dir      where the downloaded artifact and evidence.txt are written.
#   --sha          require this exact commit (defaults to the source HEAD).
#
# Injection points (test-only): POCKETSHELL_CI_EVIDENCE_GH overrides the `gh`
# command; POCKETSHELL_CI_EVIDENCE_REPO overrides the owner/name slug.
# ---------------------------------------------------------------------------

GH_CMD="${POCKETSHELL_CI_EVIDENCE_GH:-gh}"
REQUIRED_CHECK_NAME="Unit tests"
readonly -a REQUIRED_JOB_NAMES=(
  "JVM unit tests (Debug)"
  "JVM unit tests (Release)"
)
readonly -a ARTIFACT_NAMES=(
  "unit-test-reports-Debug"
  "unit-test-reports-Release"
)

decline() {
  printf 'DECLINE: %s\n' "$1" >&2
  printf 'DECLINE: the pre-release gate must run the unit suite locally.\n' >&2
  return 1
}

note() {
  printf '%s\n' "$1"
}

# Owner/name slug. Explicit override wins; otherwise parse the `origin` remote.
# A remote this parser cannot recognise as GitHub yields nothing, and the caller
# declines — it does not guess.
pocketshell_ci_evidence_repo_slug_from_url() {
  local url="$1"
  local slug=""
  case "$url" in
    git@github.com:*) slug="${url#git@github.com:}" ;;
    ssh://git@github.com/*) slug="${url#ssh://git@github.com/}" ;;
    https://github.com/*) slug="${url#https://github.com/}" ;;
    http://github.com/*) slug="${url#http://github.com/}" ;;
    *) return 1 ;;
  esac
  slug="${slug%.git}"
  slug="${slug%/}"
  # Exactly owner/name, nothing deeper.
  case "$slug" in
    */*/*) return 1 ;;
    */*) printf '%s' "$slug" ;;
    *) return 1 ;;
  esac
}

# ---------------------------------------------------------------------------
# JSON projection. `gh api` emits raw JSON here on purpose: the projection runs
# in THIS script, so the test's stub `gh` (which only replays a captured API
# body) still exercises the real parsing rather than a re-spelling of it.
# ---------------------------------------------------------------------------
pocketshell_ci_evidence_project() {
  local mode="$1"
  local json_file="$2"
  local wanted="${3:-}"
  python3 - "$mode" "$json_file" "$wanted" <<'PYTHON'
import json
import sys

mode, path, wanted = sys.argv[1], sys.argv[2], sys.argv[3]
try:
    with open(path, encoding="utf-8") as handle:
        payload = json.load(handle)
except (OSError, ValueError) as error:
    sys.stderr.write(f"unreadable JSON from the GitHub API ({path}): {error}\n")
    raise SystemExit(2)

if mode == "check-runs":
    runs = payload.get("check_runs")
    if not isinstance(runs, list):
        sys.stderr.write("check-runs payload has no check_runs array\n")
        raise SystemExit(2)
    matches = [run for run in runs if run.get("name") == wanted]
    if len(matches) != 1:
        sys.stderr.write(
            f"expected exactly one check run named {wanted!r}, found {len(matches)}\n"
        )
        raise SystemExit(2)
    run = matches[0]
    print(run.get("status", ""))
    print(run.get("conclusion", ""))
    print(run.get("head_sha", ""))
    print(run.get("details_url", ""))
elif mode == "workflow-run":
    print(payload.get("head_sha", ""))
    print(payload.get("status", ""))
    print(payload.get("conclusion", ""))
    print(payload.get("html_url", ""))
elif mode == "jobs":
    jobs = payload.get("jobs")
    if not isinstance(jobs, list):
        sys.stderr.write("jobs payload has no jobs array\n")
        raise SystemExit(2)
    matches = [job for job in jobs if job.get("name") == wanted]
    if len(matches) != 1:
        sys.stderr.write(
            f"expected exactly one job named {wanted!r}, found {len(matches)}\n"
        )
        raise SystemExit(2)
    job = matches[0]
    print(job.get("status", ""))
    print(job.get("conclusion", ""))
    print(job.get("completed_at", ""))
else:
    sys.stderr.write(f"unknown projection mode {mode!r}\n")
    raise SystemExit(2)
PYTHON
}

# `.../actions/runs/<id>/job/<job-id>` -> `<id>`; anything else fails.
pocketshell_ci_evidence_run_id_from_details_url() {
  local url="$1"
  local tail="${url#*/actions/runs/}"
  [[ "$tail" != "$url" ]] || return 1
  local run_id="${tail%%/*}"
  [[ "$run_id" =~ ^[0-9]+$ ]] || return 1
  printf '%s' "$run_id"
}

# Pin the rendered #2069 two-shard contract at the release commit. Runtime API
# checks below independently require both rendered job names and both artifact
# names; this local check makes a workflow rename fail with a direct diagnostic
# instead of looking like missing historical evidence.
pocketshell_ci_evidence_assert_workflow_contract() {
  local workflow="$1"
  [[ -f "$workflow" ]] || {
    printf 'workflow is missing: %s\n' "$workflow" >&2
    return 1
  }
  grep -qF '    name: JVM unit tests' "$workflow" || {
    printf 'workflow no longer declares the JVM unit job base name\n' >&2
    return 1
  }
  grep -qF '        variant: [Debug, Release]' "$workflow" || {
    printf 'workflow no longer declares the exact Debug/Release unit matrix\n' >&2
    return 1
  }
  # shellcheck disable=SC2016 # literal GitHub expression, not a shell expansion
  grep -qF '          name: unit-test-reports-${{ matrix.variant }}' "$workflow" || {
    printf 'workflow no longer publishes the expected per-variant unit artifacts\n' >&2
    return 1
  }
}

# ---------------------------------------------------------------------------
# Verification root: deciding whether the DOWNLOADED result XML is a real run
# needs a tree that carries BOTH the module structure (settings, build files,
# src/test) and the results. The CI artifact carries only the results, so
# assemble a private root from the release tree's structural files plus the
# downloaded results. Nothing is written into the release tree itself.
#
# WHY THE COUNT CHECK IS RE-IMPLEMENTED HERE and not delegated to the sibling
# guard: `scripts/check-release-gate-execution-profile.sh` (issue #2054) walks
# the closure of every script the release chain invokes and REJECTS any of them
# that is not itself in RELEASE_CHAIN_SCRIPTS or that its bash block scanner
# cannot model. Shelling out to the sibling guards from this chain-reachable
# script therefore fails that guard, and the "remedy" it names — adding a static
# guard to RELEASE_CHAIN_SCRIPTS — would force a pure text checker to apply a
# 24 GiB build-scope ceiling it has no business applying. Neither weakening that
# guard nor splitting the path string to slip past it is acceptable, so the rule
# is applied here directly, over the SAME floors manifest and the SAME derivation
# (a module with test sources under src/test must produce results for every
# expected variant task, at or above its declared floor; a module with no test
# sources is legitimately exempt).
#
# The duplication is pinned rather than trusted:
# `scripts/test-reuse-ci-unit-evidence.sh` runs BOTH this implementation and
# `scripts/check-executed-test-counts.sh --root` over the same synthetic roots
# and requires identical verdicts, so the two cannot drift apart silently. That
# test script is deliberately NOT reachable from the release chain, which is why
# it may reference the sibling guard.
# ---------------------------------------------------------------------------
pocketshell_ci_evidence_modules() {
  local tree_root="$1"
  local settings="$tree_root/settings.gradle.kts"
  [[ -f "$settings" ]] || return 1
  grep -oE '^[[:space:]]*include\("[^"]+"\)' "$settings" |
    sed -E 's/.*include\("//; s/"\)//' |
    sed -E 's/^://; s/:/\//g'
}

pocketshell_ci_evidence_build_verify_root() {
  local tree_root="$1"
  local artifact_dir="$2"
  local verify_root="$3"

  mkdir -p "$verify_root/scripts"
  cp "$tree_root/settings.gradle.kts" "$verify_root/settings.gradle.kts" || return 1
  if [[ -f "$tree_root/scripts/executed-test-count-floors.txt" ]]; then
    cp "$tree_root/scripts/executed-test-count-floors.txt" "$verify_root/scripts/" || return 1
  fi

  local module
  while IFS= read -r module; do
    [[ -n "$module" ]] || continue
    [[ -d "$tree_root/$module" ]] || continue
    mkdir -p "$verify_root/$module"
    if [[ -f "$tree_root/$module/build.gradle.kts" ]]; then
      cp "$tree_root/$module/build.gradle.kts" "$verify_root/$module/build.gradle.kts" || return 1
    fi
    if [[ -d "$tree_root/$module/src/test" ]]; then
      mkdir -p "$verify_root/$module/src"
      cp -a "$tree_root/$module/src/test" "$verify_root/$module/src/test" || return 1
    fi
    # Locate this module's downloaded results wherever upload-artifact rooted
    # them. A module with no results simply gets none, and the count guard then
    # reports MISSING -> decline. Fail closed by omission, never by assumption.
    local found
    while IFS= read -r found; do
      [[ -n "$found" ]] || continue
      mkdir -p "$verify_root/$module/build/test-results"
      # Debug and Release are separate #2069 artifacts. Merge their disjoint
      # task directories into one private verification root before applying the
      # unchanged complete-graph floors.
      cp -a "$found/." "$verify_root/$module/build/test-results/" || return 1
    done < <(find "$artifact_dir" -type d -path "*$module/build/test-results" -print 2>/dev/null | sort)
  done < <(pocketshell_ci_evidence_modules "$tree_root")
}

# pocketshell_ci_evidence_check_counts <verify-root>
#
# executed = tests - skipped, summed per task result directory. Every module
# with test sources must produce results for every expected variant task, at or
# above its floor (default 1). Prints a per-task table; non-zero on any
# violation. The freshness (`--newer-than`) and console (`--gradle-log`) halves
# of the #1646 guard are deliberately absent here because they are meaningless
# for a downloaded artifact — and they were already applied, in the CI job whose
# success is a precondition for even reaching this point.
pocketshell_ci_evidence_check_counts() {
  local verify_root="$1"
  python3 - "$verify_root" <<'PYTHON'
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
settings = root / "settings.gradle.kts"
if not settings.is_file():
    sys.stderr.write(f"no settings.gradle.kts under {root}\n")
    raise SystemExit(1)

modules = [
    match.replace(":", "/").lstrip("/")
    for match in re.findall(r'^\s*include\("([^"]+)"\)', settings.read_text(), re.M)
]
if not modules:
    sys.stderr.write("no Gradle modules discovered\n")
    raise SystemExit(1)

floors = {}
floors_file = root / "scripts" / "executed-test-count-floors.txt"
if floors_file.is_file():
    for line in floors_file.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1].isdigit():
            floors[parts[0]] = int(parts[1])

ANDROID = re.compile(r"plugins\.android\.(application|library)|com\.android\.(application|library)")

violations = []
rows = []
total = 0
checked = 0

for module in modules:
    module_dir = root / module
    if not module_dir.is_dir():
        continue
    prefix = ":" + module.replace("/", ":")
    test_src = module_dir / "src" / "test"
    has_sources = test_src.is_dir() and any(
        path.suffix in (".kt", ".java") for path in test_src.rglob("*") if path.is_file()
    )
    build_file = module_dir / "build.gradle.kts"
    android = build_file.is_file() and bool(ANDROID.search(build_file.read_text()))
    tasks = ["testDebugUnitTest", "testReleaseUnitTest"] if android else ["test"]

    for task in tasks:
        task_path = f"{prefix}:{task}"
        declared = floors.get(task_path)
        if not has_sources and declared is None:
            # Legitimately test-free (e.g. a testImplementation-only support
            # module). Derived from the absence of sources, not allowlisted.
            continue
        floor = declared if declared is not None else 1
        checked += 1
        if not has_sources:
            violations.append(
                f"{task_path} is declared in the floors manifest but the module has no "
                f"test sources; a declared task is never granted the test-free exemption"
            )
            rows.append((task_path, "-", "SOURCES GONE"))
            continue
        results = module_dir / "build" / "test-results" / task
        if not results.is_dir():
            violations.append(
                f"{task_path} produced NO result XML in the reused CI artifact — the task "
                f"did not run in that job, so the artifact does not cover this release"
            )
            rows.append((task_path, "-", "MISSING"))
            continue
        executed = 0
        for xml in results.glob("TEST-*.xml"):
            match = re.search(r"<testsuite [^>]*>", xml.read_text(errors="replace"))
            if not match:
                continue
            head = match.group(0)
            tests = re.search(r'tests="(\d+)"', head)
            skipped = re.search(r'skipped="(\d+)"', head)
            executed += int(tests.group(1) if tests else 0) - int(
                skipped.group(1) if skipped else 0
            )
        total += executed
        if executed < floor:
            violations.append(
                f"{task_path} executed {executed} tests in the reused CI artifact, below its "
                f"floor of {floor}. A green label over zero executed tests is not evidence (G3)."
            )
            rows.append((task_path, executed, f"BELOW FLOOR (min {floor})"))
            continue
        rows.append((task_path, executed, "ok"))

print("Executed test counts in the reused CI artifact (executed = tests - skipped)")
print()
print(f"  {'TASK':<46}{'EXECUTED':>10}  STATUS")
for task_path, executed, status in rows:
    print(f"  {task_path:<46}{str(executed):>10}  {status}")
print()
print(f"  tasks checked: {checked}   total executed: {total}")

if checked == 0:
    sys.stderr.write(
        "no test task was checked at all; an artifact that constrains nothing is not evidence\n"
    )
    raise SystemExit(1)
if violations:
    sys.stderr.write("\n")
    for violation in violations:
        sys.stderr.write(f"FAIL: {violation}\n")
    raise SystemExit(1)
PYTHON
}

# ---------------------------------------------------------------------------
# The acceptor.
# ---------------------------------------------------------------------------
pocketshell_reuse_ci_unit_evidence() {
  local source_root="$1"
  local tree_root="$2"
  local out_dir="$3"
  local requested_sha="$4"

  if [[ "${RELEASE_GATE_REUSE_CI_UNIT:-1}" == "0" ]]; then
    decline "RELEASE_GATE_REUSE_CI_UNIT=0 — SHA-matched CI unit reuse is switched off"
    return 1
  fi

  command -v python3 >/dev/null 2>&1 ||
    { decline "python3 is required to parse the GitHub API responses"; return 1; }
  command -v "$GH_CMD" >/dev/null 2>&1 || [[ -x "$GH_CMD" ]] ||
    { decline "the GitHub CLI ($GH_CMD) is not available"; return 1; }

  mkdir -p "$out_dir"

  # ---- 1. the tree must BE the commit -------------------------------------
  local status_output
  status_output="$(git -C "$source_root" status --porcelain 2>/dev/null)" ||
    { decline "$source_root is not a git checkout"; return 1; }
  if [[ -n "$status_output" ]]; then
    decline "the release checkout is dirty (the gate rsyncs the working TREE, so a dirty tree is NOT the commit CI tested):
$status_output"
    return 1
  fi

  local head_sha
  head_sha="$(git -C "$source_root" rev-parse HEAD 2>/dev/null)" ||
    { decline "could not read HEAD from $source_root"; return 1; }
  if [[ -n "$requested_sha" && "$requested_sha" != "$head_sha" ]]; then
    decline "requested SHA $requested_sha does not match HEAD $head_sha"
    return 1
  fi

  git -C "$source_root" fetch --quiet origin main 2>/dev/null ||
    { decline "could not fetch origin/main to confirm the release commit is pushed"; return 1; }
  local origin_sha
  origin_sha="$(git -C "$source_root" rev-parse origin/main 2>/dev/null)" ||
    { decline "could not resolve origin/main"; return 1; }
  if [[ "$head_sha" != "$origin_sha" ]]; then
    decline "HEAD ($head_sha) is not origin/main ($origin_sha); CI evidence is only reusable for a pushed release commit"
    return 1
  fi

  pocketshell_ci_evidence_assert_workflow_contract "$source_root/.github/workflows/tests.yml" ||
    { decline "the release commit's unit workflow no longer matches the pinned #2069 Debug/Release jobs and artifacts"; return 1; }

  # ---- 2. the slug ---------------------------------------------------------
  #
  # (There is deliberately no local re-run of the CI Unit forced-execution
  # wiring guard here. That guard runs as a STEP of the `unit` job — together
  # with its own self-test — and this acceptor requires that job to have
  # concluded `success`. A weakened Unit command therefore cannot produce a
  # green `Unit tests` check in the first place, so re-deriving the same
  # conclusion locally would add no constraint. It would, however, drag a
  # Python-implemented guard into the release chain's script closure, which
  # scripts/check-release-gate-execution-profile.sh correctly rejects.)
  local slug="${POCKETSHELL_CI_EVIDENCE_REPO:-}"
  if [[ -z "$slug" ]]; then
    local origin_url
    origin_url="$(git -C "$source_root" remote get-url origin 2>/dev/null)" || origin_url=""
    slug="$(pocketshell_ci_evidence_repo_slug_from_url "$origin_url")" || slug=""
  fi
  if [[ -z "$slug" ]]; then
    decline "could not determine the GitHub owner/name for $source_root"
    return 1
  fi

  # ---- 4. an exact-SHA, green, required `Unit tests` check run -------------
  local check_json="$out_dir/check-runs.json"
  "$GH_CMD" api "repos/$slug/commits/$head_sha/check-runs?per_page=100" \
    > "$check_json" 2> "$out_dir/check-runs.err" ||
    { decline "could not read check runs for $head_sha; see $out_dir/check-runs.err"; return 1; }

  local projected
  projected="$(pocketshell_ci_evidence_project check-runs "$check_json" "$REQUIRED_CHECK_NAME" 2> "$out_dir/check-runs-parse.err")" ||
    { decline "no single '$REQUIRED_CHECK_NAME' check run for $head_sha: $(cat "$out_dir/check-runs-parse.err")"; return 1; }

  local check_status check_conclusion check_head_sha details_url
  { read -r check_status; read -r check_conclusion; read -r check_head_sha; read -r details_url; } <<< "$projected"

  [[ "$check_status" == "completed" ]] ||
    { decline "'$REQUIRED_CHECK_NAME' for $head_sha is '$check_status', not completed"; return 1; }
  [[ "$check_conclusion" == "success" ]] ||
    { decline "'$REQUIRED_CHECK_NAME' for $head_sha concluded '$check_conclusion', not success"; return 1; }
  [[ "$check_head_sha" == "$head_sha" ]] ||
    { decline "'$REQUIRED_CHECK_NAME' reports head_sha $check_head_sha, not the release commit $head_sha"; return 1; }

  local run_id
  run_id="$(pocketshell_ci_evidence_run_id_from_details_url "$details_url")" ||
    { decline "could not derive a workflow run id from details_url '$details_url'"; return 1; }

  # ---- 5. the workflow run + the job that actually ran the tests -----------
  local run_json="$out_dir/workflow-run.json"
  "$GH_CMD" api "repos/$slug/actions/runs/$run_id" \
    > "$run_json" 2> "$out_dir/workflow-run.err" ||
    { decline "could not read workflow run $run_id; see $out_dir/workflow-run.err"; return 1; }

  projected="$(pocketshell_ci_evidence_project workflow-run "$run_json" 2> "$out_dir/workflow-run-parse.err")" ||
    { decline "unreadable workflow run $run_id: $(cat "$out_dir/workflow-run-parse.err")"; return 1; }
  local run_head_sha run_status run_conclusion run_url
  { read -r run_head_sha; read -r run_status; read -r run_conclusion; read -r run_url; } <<< "$projected"

  [[ "$run_head_sha" == "$head_sha" ]] ||
    { decline "workflow run $run_id tested $run_head_sha, not the release commit $head_sha"; return 1; }
  [[ "$run_status" == "completed" ]] ||
    { decline "workflow run $run_id is '$run_status', not completed"; return 1; }
  [[ "$run_conclusion" == "success" ]] ||
    { decline "workflow run $run_id concluded '$run_conclusion', not success"; return 1; }

  local jobs_json="$out_dir/workflow-jobs.json"
  "$GH_CMD" api "repos/$slug/actions/runs/$run_id/jobs?per_page=100" \
    > "$jobs_json" 2> "$out_dir/workflow-jobs.err" ||
    { decline "could not read the jobs of workflow run $run_id; see $out_dir/workflow-jobs.err"; return 1; }

  local -a job_evidence=()
  local required_job job_status job_conclusion job_completed_at
  for required_job in "${REQUIRED_JOB_NAMES[@]}"; do
    projected="$(pocketshell_ci_evidence_project jobs "$jobs_json" "$required_job" 2> "$out_dir/workflow-jobs-parse.err")" ||
      { decline "no single '$required_job' job in run $run_id: $(cat "$out_dir/workflow-jobs-parse.err")"; return 1; }
    { read -r job_status; read -r job_conclusion; read -r job_completed_at; } <<< "$projected"

    [[ "$job_status" == "completed" ]] ||
      { decline "'$required_job' in run $run_id is '$job_status', not completed"; return 1; }
    [[ "$job_conclusion" == "success" ]] ||
      { decline "'$required_job' in run $run_id concluded '$job_conclusion', not success"; return 1; }
    job_evidence+=("$required_job|$job_status|$job_conclusion|$job_completed_at")
  done

  # ---- 6. download and INDEPENDENTLY re-check the counts -------------------
  local artifact_dir="$out_dir/artifacts"
  rm -rf "$artifact_dir"
  mkdir -p "$artifact_dir"
  local artifact_name artifact_variant_dir
  for artifact_name in "${ARTIFACT_NAMES[@]}"; do
    artifact_variant_dir="$artifact_dir/$artifact_name"
    mkdir -p "$artifact_variant_dir"
    "$GH_CMD" run download "$run_id" --repo "$slug" --name "$artifact_name" --dir "$artifact_variant_dir" \
      >> "$out_dir/artifact-download.log" 2>&1 ||
      { decline "could not download the '$artifact_name' artifact from run $run_id; see $out_dir/artifact-download.log"; return 1; }
    if [[ -z "$(find "$artifact_variant_dir" -name 'TEST-*.xml' -type f -print -quit 2>/dev/null)" ]]; then
      decline "the '$artifact_name' artifact from run $run_id contains no TEST-*.xml result files"
      return 1
    fi
  done

  local verify_root="$out_dir/verify-root"
  rm -rf "$verify_root"
  pocketshell_ci_evidence_build_verify_root "$tree_root" "$artifact_dir" "$verify_root" ||
    { decline "could not assemble the verification root from $tree_root + the downloaded artifact"; return 1; }

  pocketshell_ci_evidence_check_counts "$verify_root" > "$out_dir/executed-test-counts.log" 2>&1 ||
    { decline "the DOWNLOADED CI result XML does not satisfy the executed-test-count floors; see $out_dir/executed-test-counts.log"; return 1; }

  # ---- 7. record the provenance -------------------------------------------
  {
    printf 'PocketShell reused CI unit evidence (issue #2064)\n'
    printf 'Accepted at: %s\n' "$(date -Is)"
    printf 'Commit SHA: %s\n' "$head_sha"
    printf 'Repository: %s\n' "$slug"
    printf 'Required check: %s (%s/%s)\n' "$REQUIRED_CHECK_NAME" "$check_status" "$check_conclusion"
    printf 'Workflow run: %s\n' "$run_id"
    printf 'Workflow run URL: %s\n' "$run_url"
    local job_row
    for job_row in "${job_evidence[@]}"; do
      IFS='|' read -r required_job job_status job_conclusion job_completed_at <<< "$job_row"
      printf 'Unit job: %s (%s/%s) completed_at=%s\n' \
        "$required_job" "$job_status" "$job_conclusion" "$job_completed_at"
    done
    printf 'Artifacts: %s, %s under %s\n' \
      "${ARTIFACT_NAMES[0]}" "${ARTIFACT_NAMES[1]}" "$artifact_dir"
    printf '\nIndependently re-checked executed test counts:\n'
    cat "$out_dir/executed-test-counts.log"
  } > "$out_dir/evidence.txt"

  cat "$out_dir/evidence.txt"
  note "ACCEPT: reusing the '$REQUIRED_CHECK_NAME' evidence from run $run_id for $head_sha"
}

usage() {
  sed -n '/^# Usage:/,/^# ----/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' | sed '$d'
}

main() {
  local source_root=""
  local tree_root=""
  local out_dir=""
  local sha=""

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --help|-h) usage; return 0 ;;
      --source-root) source_root="$2"; shift 2 ;;
      --tree-root) tree_root="$2"; shift 2 ;;
      --out-dir) out_dir="$2"; shift 2 ;;
      --sha) sha="$2"; shift 2 ;;
      *) printf 'FAIL: unknown argument %s\n' "$1" >&2; usage >&2; return 2 ;;
    esac
  done

  [[ -n "$source_root" ]] || { printf 'FAIL: --source-root is required\n' >&2; return 2; }
  [[ -n "$out_dir" ]] || { printf 'FAIL: --out-dir is required\n' >&2; return 2; }
  [[ -n "$tree_root" ]] || tree_root="$source_root"

  pocketshell_reuse_ci_unit_evidence "$source_root" "$tree_root" "$out_dir" "$sha"
}

# POCKETSHELL_CI_EVIDENCE_LIB_ONLY lets scripts/test-reuse-ci-unit-evidence.sh
# source this file to exercise its pure helpers directly. Every acceptance /
# decline case in that test still runs this script as a real child process.
if [[ -z "${POCKETSHELL_CI_EVIDENCE_LIB_ONLY:-}" ]]; then
  main "$@"
fi
