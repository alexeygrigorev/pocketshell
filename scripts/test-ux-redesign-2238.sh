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
BROWSER_DATA_DIR="$(mktemp -d)"
cleanup() {
  rm -f "$DOM_FILE"
  rm -rf "$BROWSER_DATA_DIR"
}
trap cleanup EXIT
MOCKUP_URI="file://$MOCKUP?smoke=1"
env -u DBUS_SESSION_BUS_ADDRESS -u DBUS_SYSTEM_BUS_ADDRESS \
  timeout 90 "$BROWSER" \
  --headless \
  --no-sandbox \
  --disable-gpu \
  --disable-dev-shm-usage \
  --no-first-run \
  --no-default-browser-check \
  --disable-background-networking \
  --disable-component-update \
  --disable-sync \
  --disable-extensions \
  --disable-default-apps \
  --disable-metrics \
  --no-pings \
  --disable-domain-reliability \
  --disable-features=UseDBus \
  --user-data-dir="$BROWSER_DATA_DIR" \
  --window-size=390,844 \
  --virtual-time-budget=5000 \
  --dump-dom "$MOCKUP_URI" >"$DOM_FILE"

grep -q 'id="smoke-result" data-smoke="pass"' "$DOM_FILE" \
  || { echo "FAIL: browser interaction smoke test did not pass" >&2; grep 'smoke-result' "$DOM_FILE" >&2 || true; exit 1; }

echo "PASS: #2238 HTML prototype browser smoke test"
