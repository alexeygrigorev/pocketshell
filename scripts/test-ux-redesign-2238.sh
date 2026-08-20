#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MOCKUP="$ROOT_DIR/docs/mockups/ux-redesign-2238.html"
[[ -f "$MOCKUP" ]] || { echo "FAIL: missing $MOCKUP" >&2; exit 1; }

BROWSER="${POCKETSHELL_BROWSER:-}"
if [[ -z "$BROWSER" ]]; then
  for candidate in chromium chromium-browser google-chrome /snap/bin/chromium; do
    if command -v "$candidate" >/dev/null 2>&1; then
      BROWSER="$(command -v "$candidate")"
      break
    fi
  done
fi
[[ -n "$BROWSER" ]] || { echo "FAIL: Chromium/Chrome is required for the #2238 smoke check" >&2; exit 1; }

DOM_FILE="$(mktemp)"
trap 'rm -f "$DOM_FILE"' EXIT
MOCKUP_URI="file://$MOCKUP?smoke=1"
timeout 30 "$BROWSER" \
  --headless \
  --no-sandbox \
  --disable-gpu \
  --disable-dev-shm-usage \
  --window-size=390,844 \
  --virtual-time-budget=5000 \
  --dump-dom "$MOCKUP_URI" >"$DOM_FILE"

grep -q 'id="smoke-result" data-smoke="pass"' "$DOM_FILE" \
  || { echo "FAIL: browser interaction smoke test did not pass" >&2; grep 'smoke-result' "$DOM_FILE" >&2 || true; exit 1; }

echo "PASS: #2238 HTML prototype browser smoke test"
