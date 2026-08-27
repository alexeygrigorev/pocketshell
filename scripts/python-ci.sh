#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR/tools/pocketshell"
exec uv run python ../../scripts/check-python-test-execution.py "$@"
