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
# The optional first argument is a test-method-name filter passed straight to
# Gradle's --tests; it matches the @Test method names in DesignRenders
# (screenHeader, listRow, hostListScreen, ...). Output files are named after the
# render label (screen-header.png, list-row.png, host-list-screen.png).
set -euo pipefail

# Resolve repo root from this script's location so it works from any cwd.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

RENDER_CLASS="com.pocketshell.uikit.render.DesignRenders"
# Robolectric writes the PNGs relative to the module dir during the test run.
OUTPUT_DIR="$REPO_ROOT/shared/ui-kit/build/renders"

FILTER="${1:-}"
if [[ -n "$FILTER" ]]; then
  TESTS_ARG="$RENDER_CLASS.$FILTER"
else
  TESTS_ARG="$RENDER_CLASS"
fi

echo "render.sh: recording Roborazzi renders (filter: ${FILTER:-<all>})"
START="$(date +%s)"

# recordRoborazziDebug (re)writes the golden PNGs. --rerun-tasks isn't needed:
# editing a composable invalidates the test task, so a normal record re-renders.
./gradlew :shared:ui-kit:recordRoborazziDebug --tests "$TESTS_ARG"

END="$(date +%s)"
echo
echo "render.sh: done in $((END - START))s. PNGs in:"
echo "  $OUTPUT_DIR"
if [[ -d "$OUTPUT_DIR" ]]; then
  ls -1 "$OUTPUT_DIR"/*.png 2>/dev/null | sed 's/^/  - /' || true
fi
