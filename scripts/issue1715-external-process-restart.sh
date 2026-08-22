#!/usr/bin/env bash
# Issue #1715: external target-process death around the real MainActivity file
# workspace journey. The shared harness performs the actual adb force-stop;
# this wrapper binds it to the daemon-backed MainActivity fixture.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  exec "$ROOT_DIR/scripts/two-phase-android-instrumentation.sh" --help
fi
[[ $# -eq 0 ]] || {
  printf 'Usage: ANDROID_SERIAL=emulator-5556 %s\n' "$0" >&2
  exit 2
}

: "${ANDROID_SERIAL:?ANDROID_SERIAL must name the isolated emulator for this proof}"
[[ "$ANDROID_SERIAL" != "emulator-5554" ]] \
  || { printf 'Refusing emulator-5554; it is reserved by another issue lane.\n' >&2; exit 2; }

CACHE_BASE="${XDG_CACHE_HOME:-${HOME:?HOME is required}/.cache}"
RUN_NAMESPACE="${RUN_NAMESPACE:-issue1715-process-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
EVIDENCE_ROOT="${EVIDENCE_ROOT:-$CACHE_BASE/pocketshell/evidence/android-process-restart}"
RUN_DIR="${RUN_DIR:-$EVIDENCE_ROOT/$RUN_NAMESPACE}"

export ANDROID_SERIAL
export PROOF_KIND="file-viewer-workspace"
export SUFFIX="${SUFFIX:-i1715process}"
export TEST_CLASS="com.pocketshell.app.fileviewer.FileViewerTabsMainActivityJourneyDockerTest"
export PHASE1_METHOD="externalProcessPhaseOnePersistsWorkspaceAndWaitsForForceStop"
export PHASE2_METHOD="externalProcessPhaseTwoRestoresWorkspaceAndActiveTab"
export RUN_NAMESPACE
export RUN_DIR
export EXPECTED_GENERATION_ORIGIN="agents-daemon-2239-folder-list-session-to-file-workspace-daemon-registry"
export EXPECTED_PRODUCER_FIXTURE_NAME="Issue1715 MainActivity"
export EXPECTED_PRODUCER_FIXTURE_HOST="10.0.2.2"
export EXPECTED_PRODUCER_FIXTURE_PORT="2239"
export EXPECTED_PRODUCER_FIXTURE_USER="testuser"
export EXPECTED_PRODUCER_SESSION_PREFIX="issue1715-files-"
export EXPECTED_PHASE1_PERSISTENCE_ORIGIN="FileWorkspaceRemoteSource.upsertWorkspace"
export EXPECTED_PHASE2_PERSISTENCE_ORIGIN="FileWorkspaceRemoteSource.getWorkspace"

exec "$ROOT_DIR/scripts/two-phase-android-instrumentation.sh"
