#!/usr/bin/env bash
#
# render.sh — fast design-iteration render harness (issue #555).
#
# Renders real ui-kit composables under the actual PocketShellTheme to PNGs on
# the host JVM via Roborazzi/Robolectric. No emulator, no install — seconds, not
# minutes. This is the *iteration* loop, NOT the emulator release-validation
# gate (which stays as the acceptance check).
#
# ── Per-tweak workflow ───────────────────────────────────────────────────────
#   1. Edit a composable (shared/ui-kit/.../components/*.kt) or a render case in
#      shared/ui-kit/src/test/java/com/pocketshell/uikit/render/DesignRenders.kt.
#   2. Run this script:
#        scripts/render.sh                 # render every case
#        scripts/render.sh hostListScreen  # render just one case (test method)
#   3. Open the fresh PNG(s) printed at the end (build/renders/<name>.png).
#   Repeat. Each run overwrites the same paths, so a tweak yields a new image at
#   a stable location.
#
# Issue #1766 invariant: render PNGs are direct test-execution side effects, not
# declared Gradle task outputs. A cache hit or UP-TO-DATE task therefore cannot
# prove that this invocation produced its requested PNG. This harness parses the
# explicit `fun method() = render("label")` mappings, deletes only the requested
# target (all declared PNGs in all-mode), opts the render test task out of cache
# and up-to-date reuse, and accepts only non-empty artifacts recreated after a
# locked, same-filesystem freshness boundary.
set -euo pipefail

if (($# > 1)); then
  printf 'Usage: scripts/render.sh [DesignRenders test method]\n' >&2
  exit 2
fi

# Resolve repo root from this script's location so it works from any cwd.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

RENDER_CLASS="com.pocketshell.uikit.render.DesignRenders"
RENDER_SOURCE="$REPO_ROOT/shared/ui-kit/src/test/java/com/pocketshell/uikit/render/DesignRenders.kt"
# Robolectric writes the PNGs relative to the module dir during the test run.
OUTPUT_DIR="$REPO_ROOT/shared/ui-kit/build/renders"
PYTHON3="${PYTHON3:-python3}"
FILTER="${1:-}"

if ! command -v "$PYTHON3" >/dev/null 2>&1; then
  printf 'render.sh: required Python interpreter not found: %s\n' "$PYTHON3" >&2
  exit 1
fi
if ! command -v flock >/dev/null 2>&1; then
  printf 'render.sh: required render-output lock command not found: flock\n' >&2
  exit 1
fi
if [[ ! -f "$RENDER_SOURCE" ]]; then
  printf 'render.sh: render source not found: %s\n' "$RENDER_SOURCE" >&2
  exit 1
fi

RUN_TMP="$(mktemp -d)"
INVOCATION_MARKER=""
cleanup() {
  if [[ -n "$INVOCATION_MARKER" ]]; then
    rm -f -- "$INVOCATION_MARKER"
  fi
  rm -rf "$RUN_TMP"
}
trap cleanup EXIT

MAPPING_FILE="$RUN_TMP/render-mappings.tsv"
if ! "$PYTHON3" - "$RENDER_SOURCE" > "$MAPPING_FILE" <<'PY'
from collections import Counter
from pathlib import Path
import re
import sys

source_path = Path(sys.argv[1])
source = source_path.read_text(encoding="utf-8")

# `@Test` is the executable inventory. The single-line render call is the
# machine-readable method -> artifact-label mapping. Its grammar deliberately
# permits spaces/tabs but never a newline between `fun` and `render(...)`.
# Keeping the mapping in the real declaration avoids a second manifest that can
# drift independently.
test_methods = re.findall(
    r"(?m)^[ \t]*@Test[ \t]*\r?\n"
    r"[ \t]*fun[ \t]+([A-Za-z_][A-Za-z0-9_]*)[ \t]*\(",
    source,
)
mappings = re.findall(
    r'(?m)^[ \t]*fun[ \t]+([A-Za-z_][A-Za-z0-9_]*)[ \t]*'
    r'\([ \t]*\)[ \t]*=[ \t]*render[ \t]*\([ \t]*"([^"]+)"[ \t]*\)',
    source,
)

def duplicates(values):
    return sorted(value for value, count in Counter(values).items() if count > 1)

errors = []
duplicate_tests = duplicates(test_methods)
mapping_methods = [method for method, _ in mappings]
duplicate_methods = duplicates(mapping_methods)
labels = [label for _, label in mappings]
duplicate_labels = duplicates(labels)

if not test_methods:
    errors.append("no @Test render methods found")
if duplicate_tests:
    errors.append(f"duplicate @Test methods: {', '.join(duplicate_tests)}")
if duplicate_methods:
    errors.append(f"duplicate render method mappings: {', '.join(duplicate_methods)}")
if duplicate_labels:
    errors.append(f"duplicate render labels: {', '.join(duplicate_labels)}")

test_set = set(test_methods)
mapping_set = set(mapping_methods)
missing = sorted(test_set - mapping_set)
extra = sorted(mapping_set - test_set)
if missing:
    errors.append(
        "test methods missing `fun method() = render(\"label\")` mappings: "
        + ", ".join(missing)
    )
if extra:
    errors.append(
        "render mappings without matching @Test methods: " + ", ".join(extra)
    )

for method, label in mappings:
    if not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?", label):
        errors.append(f"{method} has unsafe render label {label!r}")

if errors:
    for error in errors:
        print(f"render.sh: invalid DesignRenders mapping: {error}", file=sys.stderr)
    raise SystemExit(1)

for method, label in mappings:
    print(f"{method}\t{label}")
PY
then
  exit 1
fi

declare -a RENDER_METHODS=()
declare -A LABEL_BY_METHOD=()
while IFS=$'\t' read -r method label; do
  [[ -n "$method" && -n "$label" ]] || continue
  RENDER_METHODS+=("$method")
  LABEL_BY_METHOD["$method"]="$label"
done < "$MAPPING_FILE"

if ((${#RENDER_METHODS[@]} == 0)); then
  printf 'render.sh: mapping parser returned zero render cases\n' >&2
  exit 1
fi
if [[ -n "$FILTER" && -z "${LABEL_BY_METHOD[$FILTER]+present}" ]]; then
  printf 'render.sh: unknown DesignRenders method: %s\n' "$FILTER" >&2
  printf 'render.sh: available methods:\n' >&2
  printf '  - %s\n' "${RENDER_METHODS[@]}" >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"

# Serialize the entire output-critical section. The lock lives beside the PNGs,
# so two worktree-local render.sh calls cannot delete or validate each other's
# in-flight artifacts. It is intentionally acquired before marker creation and
# held by this shell through deletion, Gradle execution, and validation.
RENDER_LOCK="$OUTPUT_DIR/.render.lock"
exec {RENDER_LOCK_FD}> "$RENDER_LOCK"
flock --exclusive "$RENDER_LOCK_FD"

# The marker is on the same filesystem as the PNGs. Back it off by a
# representable two-second guard band and verify that the filesystem preserved
# at least one second of separation. Combined with deleting the targets under
# the exclusive lock, this removes cross-filesystem and equal-timestamp-tick
# ambiguity: a surviving PNG must have been recreated by this invocation and
# must compare newer than a safely representable boundary.
INVOCATION_MARKER="$(mktemp "$OUTPUT_DIR/.render-invocation.XXXXXX")"
if ! "$PYTHON3" - "$INVOCATION_MARKER" <<'PY'
import os
from pathlib import Path
import sys
import time

marker = Path(sys.argv[1])
now_ns = time.time_ns()
requested_ns = now_ns - 2_000_000_000
os.utime(marker, ns=(requested_ns, requested_ns))
actual_ns = marker.stat().st_mtime_ns
if now_ns - actual_ns < 1_000_000_000:
    print(
        "render.sh: output filesystem cannot represent a safe freshness boundary",
        file=sys.stderr,
    )
    raise SystemExit(1)
PY
then
  exit 1
fi

declare -a EXPECTED_ARTIFACTS=()
if [[ -n "$FILTER" ]]; then
  TESTS_ARG="$RENDER_CLASS.$FILTER"
  EXPECTED_ARTIFACTS+=("$OUTPUT_DIR/${LABEL_BY_METHOD[$FILTER]}.png")
  # Preserve other renders for fast single-case iteration; only the selected
  # artifact must be recreated by this invocation.
  rm -f -- "${EXPECTED_ARTIFACTS[0]}"
else
  TESTS_ARG="$RENDER_CLASS"
  for method in "${RENDER_METHODS[@]}"; do
    EXPECTED_ARTIFACTS+=("$OUTPUT_DIR/${LABEL_BY_METHOD[$method]}.png")
  done
  # An all-renders run claims a complete fresh set, so no pre-existing PNG may
  # survive and masquerade as an output of this invocation.
  find "$OUTPUT_DIR" -maxdepth 1 -type f -name '*.png' -delete
fi

printf 'render.sh: recording Roborazzi renders (filter: %s)\n' "${FILTER:-<all>}"
START="$(date +%s)"

# The property is consumed only by :shared:ui-kit:testDebugUnitTest. It disables
# cache/up-to-date reuse for that one task while `--tests` still selects the one
# requested method. Compilation/resources and ordinary test invocations retain
# their normal incremental/cache behavior; this is deliberately not
# `--rerun-tasks`.
scripts/cgroup-run.sh --unit "pocketshell-render-$(date +%Y%m%d-%H%M%S)-$$" -- \
  ./gradlew --no-daemon -Ppocketshell.forceDesignRender=true \
  :shared:ui-kit:recordRoborazziDebug --tests "$TESTS_ARG"

declare -a VERIFIED_ARTIFACTS=()
artifact_error=0
for artifact in "${EXPECTED_ARTIFACTS[@]}"; do
  if [[ ! -f "$artifact" ]]; then
    printf 'render.sh: expected render artifact was not created: %s\n' "$artifact" >&2
    artifact_error=1
  elif [[ ! -s "$artifact" ]]; then
    printf 'render.sh: expected render artifact is empty: %s\n' "$artifact" >&2
    artifact_error=1
  elif [[ ! "$artifact" -nt "$INVOCATION_MARKER" ]]; then
    printf 'render.sh: expected render artifact is stale for this invocation: %s\n' \
      "$artifact" >&2
    artifact_error=1
  else
    VERIFIED_ARTIFACTS+=("$artifact")
  fi
done
if [[ "$artifact_error" -ne 0 ]]; then
  printf 'render.sh: render task exited successfully but fresh artifact validation failed\n' >&2
  exit 1
fi

END="$(date +%s)"
printf '\nrender.sh: done in %ss. Verified fresh PNG%s:\n' \
  "$((END - START))" \
  "$([[ ${#VERIFIED_ARTIFACTS[@]} -eq 1 ]] && printf '' || printf 's')"
printf '  - %s\n' "${VERIFIED_ARTIFACTS[@]}"
