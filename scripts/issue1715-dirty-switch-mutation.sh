#!/usr/bin/env bash
# G6 proof for #1715's dirty tab-switch guard.
#
# Baseline and restored runs execute the real current worktree. The mutant is
# made only in a disposable copied worktree and removes the production pending
# work guard. The focused class must be green, then exactly one dirty-switch
# assertion must turn red, then the untouched source must be green again.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_REL="app/src/main/java/com/pocketshell/app/fileviewer/FileViewerViewModel.kt"
TEST_CLASS="com.pocketshell.app.fileviewer.FileViewerWorkspaceTest"
CACHE_BASE="${XDG_CACHE_HOME:-${HOME:?HOME is required}/.cache}"
RUN_ROOT="${RUN_ROOT:-$CACHE_BASE/pocketshell/evidence/issue1715-dirty-switch-mutation-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
MUTANT_ROOT=""

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  printf 'Evidence: %s\n' "$RUN_ROOT" >&2
  exit 1
}

cleanup() {
  if [[ -n "$MUTANT_ROOT" && -d "$MUTANT_ROOT" ]]; then
    rm -rf "$MUTANT_ROOT"
  fi
}
trap cleanup EXIT

[[ ! -e "$RUN_ROOT" ]] || fail "RUN_ROOT already exists; refusing stale mutation evidence"
mkdir -p "$RUN_ROOT"
chmod 700 "$RUN_ROOT"

run_focused() {
  local root="$1" log="$2" status="$3" start_epoch rc
  start_epoch="$(date +%s)"
  if (
    cd "$root"
    ./gradlew :app:testDebugUnitTest --no-daemon --rerun-tasks --no-build-cache \
      --tests "$TEST_CLASS"
  ) > "$log" 2>&1; then
    rc=0
  else
    rc=$?
  fi
  {
    printf 'command=./gradlew :app:testDebugUnitTest --no-daemon --rerun-tasks --no-build-cache --tests %s\n' "$TEST_CLASS"
    printf 'exit=%s\n' "$rc"
    printf 'started_epoch=%s\n' "$start_epoch"
    printf 'finished_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } > "$status"
  printf '%s\n' "$start_epoch"
  return "$rc"
}

inspect_xml() {
  local root="$1" expected="$2" start_epoch="$3" report="$4"
  python3 - "$root" "$expected" "$start_epoch" "$report" <<'PY'
from pathlib import Path
import sys
import time
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
expected = sys.argv[2]
start = int(sys.argv[3])
report = Path(sys.argv[4])
files = sorted((root / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
if not files:
    raise SystemExit("no focused XML result exists")
tests = skipped = failures = errors = 0
failure_tests = []
fresh = []
for path in files:
    if path.stat().st_mtime + 1 < start:
        raise SystemExit(f"stale XML result: {path}")
    suite = ET.parse(path).getroot()
    tests += int(suite.attrib.get("tests", "0"))
    skipped += int(suite.attrib.get("skipped", "0"))
    failures += int(suite.attrib.get("failures", "0"))
    errors += int(suite.attrib.get("errors", "0"))
    for testcase in suite.findall(".//testcase"):
        if testcase.find("failure") is not None:
            failure_tests.append(testcase.attrib.get("name", ""))
    fresh.append(f"{path.name}:{suite.attrib}")
if expected == "green":
    if (tests, skipped, failures, errors) != (14, 0, 0, 0):
        raise SystemExit(f"baseline/restored was not 14/14 green: {tests=} {skipped=} {failures=} {errors=}")
elif expected == "dirty-mutant-red":
    if (tests, skipped, failures, errors) != (14, 0, 1, 0):
        raise SystemExit(f"mutant was not selectively one-test red: {tests=} {skipped=} {failures=} {errors=}")
    if failure_tests != ["pendingReviewBlocksSwitchUntilDiscard"]:
        raise SystemExit(f"unexpected mutant failure set: {failure_tests!r}")
else:
    raise SystemExit(f"unknown expected verdict: {expected}")
report.write_text(
    f"expected={expected}\nxml_files={len(files)}\ntests={tests}\n"
    f"skipped={skipped}\nfailures={failures}\nerrors={errors}\n"
    f"failure_tests={'|'.join(failure_tests)}\n" + "\n".join(fresh) + "\n"
)
PY
}

BASE_SHA="$(sha256sum "$ROOT_DIR/$TARGET_REL" | awk '{print $1}')"
BASE_START="$(run_focused "$ROOT_DIR" "$RUN_ROOT/baseline.log" "$RUN_ROOT/baseline.status")" \
  || fail "current-source baseline focused test failed"
inspect_xml "$ROOT_DIR" green "$BASE_START" "$RUN_ROOT/baseline.xml-report"

if command -v rsync >/dev/null 2>&1; then
  MUTANT_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-issue1715-dirty-mutant.XXXXXX")"
  rsync -a --exclude='.git/' --exclude='.gradle/' --exclude='build/' \
    --exclude='*/build/' "$ROOT_DIR/" "$MUTANT_ROOT/"
else
  MUTANT_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-issue1715-dirty-mutant.XXXXXX")"
  cp -a "$ROOT_DIR/." "$MUTANT_ROOT/"
  find "$MUTANT_ROOT" -type d -name build -prune -exec rm -rf {} +
  rm -rf "$MUTANT_ROOT/.git" "$MUTANT_ROOT/.gradle"
fi

MUTANT_SOURCE="$MUTANT_ROOT/$TARGET_REL"
ANCHOR='        if (tab.absolutePath != showing && hasPendingWork()) {'
MUTATION='        if (false) {'
[[ "$(grep -Fc "$ANCHOR" "$MUTANT_SOURCE")" == "1" ]] \
  || fail "dirty-switch mutation anchor is not unique in disposable copy"
ANCHOR="$ANCHOR" MUTATION="$MUTATION" perl -0pi -e \
  's/\Q$ENV{ANCHOR}\E/$ENV{MUTATION}/' "$MUTANT_SOURCE"
[[ "$(grep -Fc "$ANCHOR" "$MUTANT_SOURCE")" == "0" ]] \
  || fail "dirty-switch mutant did not replace its production guard"
grep -Fqx "$MUTATION" <(grep -F "$MUTATION" "$MUTANT_SOURCE") \
  || fail "dirty-switch mutant source does not contain the intended mutation"
printf 'target=%s\nanchor=%s\nmutant=%s\ncontrol_sha256=%s\nmutant_sha256=%s\n' \
  "$TARGET_REL" "$ANCHOR" "$MUTATION" "$BASE_SHA" \
  "$(sha256sum "$MUTANT_SOURCE" | awk '{print $1}')" > "$RUN_ROOT/mutation-manifest.txt"

if MUTANT_START="$(run_focused "$MUTANT_ROOT" "$RUN_ROOT/mutant.log" "$RUN_ROOT/mutant.status")"; then
  MUTANT_RC=0
else
  MUTANT_RC=$?
fi
printf 'mutant_exit=%s\n' "$MUTANT_RC" >> "$RUN_ROOT/mutant.status"
[[ "$MUTANT_RC" -ne 0 ]] || fail "dirty-switch mutant stayed green"
inspect_xml "$MUTANT_ROOT" dirty-mutant-red "$MUTANT_START" "$RUN_ROOT/mutant.xml-report"

RESTORED_START="$(run_focused "$ROOT_DIR" "$RUN_ROOT/restored.log" "$RUN_ROOT/restored.status")" \
  || fail "untouched source did not return green after disposable mutant"
inspect_xml "$ROOT_DIR" green "$RESTORED_START" "$RUN_ROOT/restored.xml-report"
RESTORED_SHA="$(sha256sum "$ROOT_DIR/$TARGET_REL" | awk '{print $1}')"
[[ "$RESTORED_SHA" == "$BASE_SHA" ]] || fail "real worktree source changed during mutation"

{
  printf 'result=PASS\n'
  printf 'baseline=PASS\nmutant=RED\nrestored=PASS\n'
  printf 'mutation_selective=true\n'
  printf 'mutation_target=%s\n' "$TARGET_REL"
  printf 'mutant_failure_test=pendingReviewBlocksSwitchUntilDiscard\n'
  printf 'baseline_source_sha256=%s\nrestored_source_sha256=%s\n' "$BASE_SHA" "$RESTORED_SHA"
} > "$RUN_ROOT/summary.txt"
find "$RUN_ROOT" -maxdepth 1 -type f ! -name SHA256SUMS -print0 \
  | sort -z | xargs -0 sha256sum > "$RUN_ROOT/SHA256SUMS"
printf 'Issue #1715 dirty-switch mutation proof passed.\nEvidence: %s\n' "$RUN_ROOT"
