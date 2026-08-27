#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR/tools/pocketshell"
rm -f "${RUNNER_TEMP}/pocketshell-python-test-results.xml"
exec uv run pytest -v --junitxml="${RUNNER_TEMP}/pocketshell-python-test-results.xml"
