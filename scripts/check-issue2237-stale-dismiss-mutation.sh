#!/usr/bin/env bash
# Issue #2237: prove the stale-dialog onDismiss routing is live and selective.
#
# --check is the cheap per-push/static half. --run is deliberately explicit: it
# copies this worktree into private sibling roots, runs the focused JVM class on
# the clean copy, mutates exactly one callback line in the mutant copy, and
# checks the individual JUnit outcomes. The reviewed worktree is never mutated.

set -euo pipefail

SCRIPT_PATH="$(readlink -f "${BASH_SOURCE[0]}")"
ROOT_DIR="$(cd "$(dirname "$SCRIPT_PATH")/.." && pwd -P)"
SOURCE_REL="app/src/main/java/com/pocketshell/app/MainActivity.kt"
TEST_REL="app/src/test/java/com/pocketshell/app/MainActivityStaleSessionRecreateTest.kt"
SOURCE_PATH="$ROOT_DIR/$SOURCE_REL"
TEST_PATH="$ROOT_DIR/$TEST_REL"
TEST_CLASS="com.pocketshell.app.MainActivityStaleSessionRecreateTest"
ROUTING_TEST="staleDialogDismissCallbackRoutesToHostsOwnSessionTree"
FALLBACK_TEST="staleDialogDismissCallbackFallsBackToHostListWhenTreeIsUnavailable"
TWO_HOST_TEST="staleDialogSelectsTheStaleHostWhenRememberedDestinationIsAnotherHost"
# Keep the mutation location-specific: this is the complete effect sequence of
# the actual stale-session dismiss callback, not any other destination setter.
ANCHOR='    neutralizeOwner()
    clearPrompt()
    clearBackStack()
    setCurrentDestination(action.destination)'
MUTATION='    neutralizeOwner()
    clearPrompt()
    clearBackStack()
    setCurrentDestination(AppDestination.HostList)'
CALL_SITE='            onDismiss = staleSessionDismissCallback('
MODE=""
ARTIFACT_DIR=""

usage() {
    cat <<'USAGE'
Usage:
  scripts/check-issue2237-stale-dismiss-mutation.sh --check
  scripts/check-issue2237-stale-dismiss-mutation.sh --run [--artifacts PATH]

--check validates the unique production anchor and the two load-bearing JVM
test names without starting Gradle. --run is the explicit red/green lane.
USAGE
}

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

while (($# > 0)); do
    case "$1" in
        --check)
            [[ -z "$MODE" ]] || fail "choose only one of --check/--run"
            MODE="check"
            shift
            ;;
        --run)
            [[ -z "$MODE" ]] || fail "choose only one of --check/--run"
            MODE="run"
            shift
            ;;
        --artifacts)
            (($# >= 2)) || fail "--artifacts requires a path"
            ARTIFACT_DIR="$2"
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            usage >&2
            fail "unknown argument: $1"
            ;;
    esac
done

[[ -n "$MODE" ]] || { usage >&2; exit 2; }
[[ -f "$SOURCE_PATH" ]] || fail "missing source: $SOURCE_REL"
[[ -f "$TEST_PATH" ]] || fail "missing proof test: $TEST_REL"
[[ -x "$ROOT_DIR/gradlew" ]] || fail "missing executable gradlew"

check_static() {
    python3 - "$SOURCE_PATH" "$TEST_PATH" "$ANCHOR" "$MUTATION" \
        "$CALL_SITE" "$ROUTING_TEST" "$FALLBACK_TEST" "$TWO_HOST_TEST" <<'PY'
import sys
from pathlib import Path

source_path, test_path, anchor, mutation, call_site, routing_test, fallback_test, two_host_test = sys.argv[1:]
source = Path(source_path).read_text(encoding="utf-8")
tests = Path(test_path).read_text(encoding="utf-8")

if source.count(anchor) != 1:
    raise SystemExit(
        f"production anchor must occur exactly once; found {source.count(anchor)}"
    )
if anchor == mutation:
    raise SystemExit("mutation is identical to the production anchor")
if source.count(call_site) != 1:
    raise SystemExit(
        f"production ConfirmDialog onDismiss call site must occur exactly once; "
        f"found {source.count(call_site)}"
    )
if tests.count(f"fun {routing_test}(") != 1:
    raise SystemExit(f"load-bearing routing test is not unique: {routing_test}")
if tests.count(f"fun {fallback_test}(") != 1:
    raise SystemExit(f"load-bearing fallback test is not unique: {fallback_test}")
if tests.count(f"fun {two_host_test}(") != 1:
    raise SystemExit(f"load-bearing two-host test is not unique: {two_host_test}")
print("PASS: #2237 mutation anchor and selective JVM test names are present")
print(f"source={source_path}")
print(f"anchor={anchor}")
print(f"mutation={mutation}")
print(f"call_site={call_site}")
print(f"routing_test={routing_test}")
print(f"fallback_test={fallback_test}")
print(f"two_host_test={two_host_test}")
PY
}

check_static
[[ "$MODE" == "check" ]] && exit 0

if [[ -z "$ARTIFACT_DIR" ]]; then
    ARTIFACT_DIR="$ROOT_DIR/.mutation/issue2237-on-dismiss-mutation-$(date -u +%Y%m%dT%H%M%SZ)"
elif [[ "$ARTIFACT_DIR" != /* ]]; then
    ARTIFACT_DIR="$ROOT_DIR/$ARTIFACT_DIR"
fi
[[ ! -e "$ARTIFACT_DIR" ]] || fail "artifact directory already exists: $ARTIFACT_DIR"
mkdir -p "$ARTIFACT_DIR"

CLEAN_SHA256="$(sha256sum "$SOURCE_PATH" | awk '{print $1}')"
CLEAN_MD5="$(md5sum "$SOURCE_PATH" | awk '{print $1}')"
CLEAN_BYTES="$(wc -c < "$SOURCE_PATH" | tr -d ' ')"
GIT_HEAD="$(git -C "$ROOT_DIR" rev-parse HEAD)"
COMMAND="./gradlew :app:testDebugUnitTest --tests $TEST_CLASS --console=plain --no-daemon --stacktrace --rerun-tasks --no-build-cache --no-parallel --max-workers=1 -Dorg.gradle.jvmargs=-Xmx3072m -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.daemon.jvmargs=-Xmx3072m"

cat > "$ARTIFACT_DIR/metadata.txt" <<EOF
issue=2237
git_head=$GIT_HEAD
source=$SOURCE_REL
clean_source_sha256=$CLEAN_SHA256
clean_source_md5=$CLEAN_MD5
clean_source_bytes=$CLEAN_BYTES
anchor=$ANCHOR
mutation=$MUTATION
call_site=$CALL_SITE
proof_class=$TEST_CLASS
routing_test=$ROUTING_TEST
fallback_test=$FALLBACK_TEST
two_host_test=$TWO_HOST_TEST
gradle_command=$COMMAND
EOF

SANDBOX_PARENT="$(dirname "$ROOT_DIR")"
SANDBOX_DIR="$(mktemp -d "$SANDBOX_PARENT/.issue2237-mutation.XXXXXX")"
CLEAN_ROOT="$SANDBOX_DIR/clean"
MUTANT_ROOT="$SANDBOX_DIR/mutant"
mkdir "$CLEAN_ROOT" "$MUTANT_ROOT"

cleanup() {
    local after_sha256
    after_sha256="$(sha256sum "$SOURCE_PATH" | awk '{print $1}')"
    if [[ "$after_sha256" == "$CLEAN_SHA256" ]]; then
        printf 'reviewed_worktree_source_restored=true\n' >> "$ARTIFACT_DIR/metadata.txt"
    else
        printf 'reviewed_worktree_source_restored=false\n' >> "$ARTIFACT_DIR/metadata.txt"
        printf 'FAIL: reviewed worktree source changed during mutation lane\n' >&2
        rm -rf -- "$SANDBOX_DIR"
        exit 1
    fi
    rm -rf -- "$SANDBOX_DIR"
}
trap cleanup EXIT

# Private source snapshots are intentional: the mutant cannot survive a killed
# loop in the reviewed worktree, and both roots are attributable to the same
# pre-run source hash. Exclude generated build output and old evidence so a
# prior interrupted run cannot look like fresh test results or consume the
# memory/disk budget before the mutant phase.
snapshot_root() {
    local destination="$1"
    rsync -a \
        --exclude='.git/' \
        --exclude='.gradle/' \
        --exclude='build/' \
        --exclude='*/build/' \
        --exclude='.mutation/' \
        "$ROOT_DIR"/ "$destination"/
}
command -v rsync >/dev/null 2>&1 || fail "rsync is required for clean source snapshots"
snapshot_root "$CLEAN_ROOT"
snapshot_root "$MUTANT_ROOT"

run_proof() {
    local worktree="$1"
    local log_path="$2"
    if (
        cd "$worktree"
        ./gradlew :app:testDebugUnitTest \
            --tests "$TEST_CLASS" \
            --console=plain \
            --no-daemon \
            --stacktrace \
            --rerun-tasks \
            --no-build-cache \
            --no-parallel \
            --max-workers=1 \
            -Dorg.gradle.jvmargs=-Xmx3072m \
            -Pkotlin.compiler.execution.strategy=in-process \
            -Pkotlin.daemon.jvmargs=-Xmx3072m
    ) > "$log_path" 2>&1; then
        return 0
    else
        return "$?"
    fi
}

inspect_results() {
    local phase="$1"
    local worktree="$2"
    python3 - "$phase" "$worktree" "$TEST_CLASS" "$ROUTING_TEST" "$FALLBACK_TEST" "$TWO_HOST_TEST" <<'PY' \
        > "$ARTIFACT_DIR/${phase}-tests.txt"
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

phase, worktree, test_class, routing_test, fallback_test, two_host_test = sys.argv[1:]
result_dir = Path(worktree) / "app/build/test-results/testDebugUnitTest"
records = []
for path in sorted(result_dir.rglob("TEST-*.xml")) if result_dir.is_dir() else []:
    root = ET.parse(path).getroot()
    for case in root.findall(".//testcase"):
        if case.attrib.get("classname") == test_class:
            children = {child.tag.rsplit("}", 1)[-1] for child in case}
            status = "failed" if {"failure", "error"} & children else (
                "skipped" if "skipped" in children else "passed"
            )
            records.append((case.attrib.get("name", ""), status, path.name))

if not records:
    raise SystemExit("no fresh test results for the named proof class")
by_name = {name: status for name, status, _ in records}
for name, status, file_name in records:
    print(f"{name}={status} ({file_name})")
required = (routing_test, fallback_test, two_host_test)
if any(name not in by_name for name in required):
    raise SystemExit("the routing, fallback, and two-host tests were not all executed")

if phase == "clean":
    if any(status != "passed" for status in by_name.values()):
        raise SystemExit("clean proof class was not fully green")
elif phase == "mutant":
    if by_name[routing_test] != "failed":
        raise SystemExit("the onDismiss routing mutant did not redden the routing test")
    if by_name[fallback_test] != "passed":
        raise SystemExit("the host-list fallback was not selective/green under the mutant")
    if by_name[two_host_test] != "passed":
        raise SystemExit("the two-host identity proof was not selective/green under the mutant")
    unexpected = [
        name for name, status in by_name.items()
        if status == "failed" and name != routing_test
    ]
    if unexpected:
        raise SystemExit(f"mutant was not selective; unexpected failures: {unexpected}")
else:
    raise SystemExit(f"unknown result phase: {phase}")
print(f"tests_completed={len(records)}")
print(f"two_host_selectivity={by_name[two_host_test]}")
PY
}

set +e
run_proof "$CLEAN_ROOT" "$ARTIFACT_DIR/clean.log"
CLEAN_RC=$?
set -e
printf 'clean_rc=%s\n' "$CLEAN_RC" >> "$ARTIFACT_DIR/metadata.txt"
[[ "$CLEAN_RC" -eq 0 ]] || fail "clean focused proof failed; see $ARTIFACT_DIR/clean.log"
inspect_results clean "$CLEAN_ROOT"
printf 'clean_proof=green\n' >> "$ARTIFACT_DIR/metadata.txt"

MUTANT_SOURCE="$MUTANT_ROOT/$SOURCE_REL"
python3 - "$MUTANT_SOURCE" "$ANCHOR" "$MUTATION" <<'PY'
import sys
from pathlib import Path

path = Path(sys.argv[1])
anchor = sys.argv[2].encode()
mutation = sys.argv[3].encode()
data = path.read_bytes()
if data.count(anchor) != 1:
    raise SystemExit(f"mutant anchor must occur exactly once; found {data.count(anchor)}")
mutated = data.replace(anchor, mutation, 1)
if mutated == data:
    raise SystemExit("mutation was a no-op")
path.write_bytes(mutated)
PY

MUTANT_SHA256="$(sha256sum "$MUTANT_SOURCE" | awk '{print $1}')"
MUTANT_MD5="$(md5sum "$MUTANT_SOURCE" | awk '{print $1}')"
printf 'mutant_source_sha256=%s\nmutant_source_md5=%s\n' \
    "$MUTANT_SHA256" "$MUTANT_MD5" >> "$ARTIFACT_DIR/metadata.txt"
[[ "$MUTANT_SHA256" != "$CLEAN_SHA256" ]] || fail "mutant hash did not change"

python3 - "$MUTANT_SOURCE" "$CLEAN_ROOT/$SOURCE_REL" "$ANCHOR" "$MUTATION" <<'PY'
import sys
from pathlib import Path

mutant_data = Path(sys.argv[1]).read_bytes()
clean_data = Path(sys.argv[2]).read_bytes()
anchor = sys.argv[3].encode()
mutation = sys.argv[4].encode()
expected = clean_data.replace(anchor, mutation, 1)
if mutant_data != expected:
    raise SystemExit("mutant is not exactly clean_source.replace(anchor, mutation, 1)")
PY

printf 'mutant_started=true\n' >> "$ARTIFACT_DIR/metadata.txt"

set +e
run_proof "$MUTANT_ROOT" "$ARTIFACT_DIR/mutant.log"
MUTANT_RC=$?
set -e
printf 'mutant_rc=%s\n' "$MUTANT_RC" >> "$ARTIFACT_DIR/metadata.txt"
[[ "$MUTANT_RC" -ne 0 ]] || fail "mutant survived; the proof stayed green"
inspect_results mutant "$MUTANT_ROOT"

printf 'status=PASS\n' >> "$ARTIFACT_DIR/metadata.txt"
printf 'PASS: #2237 clean proof green; callback mutant red selectively; source restored\n'
