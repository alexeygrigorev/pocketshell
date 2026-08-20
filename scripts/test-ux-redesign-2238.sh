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

# Keep browser startup/load cost from consuming the result-reporting budget.
# These are deliberately separate so a slow hosted runner reports which phase
# exhausted its bounded wait instead of looking like a missing DOM result.
SERVER_START_TIMEOUT_SECONDS="${POCKETSHELL_BROWSER_SMOKE_SERVER_START_TIMEOUT_SECONDS:-30}"
BROWSER_LOAD_TIMEOUT_SECONDS="${POCKETSHELL_BROWSER_SMOKE_BROWSER_LOAD_TIMEOUT_SECONDS:-30}"
SMOKE_RESULT_TIMEOUT_SECONDS="${POCKETSHELL_BROWSER_SMOKE_RESULT_TIMEOUT_SECONDS:-30}"
for timeout_value in \
  "$SERVER_START_TIMEOUT_SECONDS" \
  "$BROWSER_LOAD_TIMEOUT_SECONDS" \
  "$SMOKE_RESULT_TIMEOUT_SECONDS"; do
  [[ "$timeout_value" =~ ^[1-9][0-9]*$ ]] \
    || { echo "FAIL: browser smoke timeouts must be positive integers" >&2; exit 1; }
done

BROWSER_MODE_FLAGS=()
if [[ "${POCKETSHELL_FORCE_COLORS:-0}" == "1" ]]; then
  BROWSER_MODE_FLAGS+=(--force-high-contrast)
fi

BROWSER_DATA_DIR="$(mktemp -d)"
SERVER_PORT_FILE="$(mktemp)"
SMOKE_RESULT_FILE="$(mktemp)"
PAGE_REQUEST_FILE="$(mktemp)"
SERVER_LOG="$(mktemp)"
BROWSER_LOG="$(mktemp)"
SMOKE_TOKEN="$(basename "$SMOKE_RESULT_FILE")"
BROWSER_PID=""
BROWSER_PGID=""
SERVER_PID=""
SERVER_PGID=""
stop_process_group() {
  local pid="$1"
  local pgid="$2"
  [[ "$pgid" =~ ^[1-9][0-9]*$ ]] || return
  kill -TERM -- "-$pgid" 2>/dev/null || true
  for _ in {1..20}; do
    kill -0 -- "-$pgid" 2>/dev/null || break
    sleep 0.1
  done
  kill -KILL -- "-$pgid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
}
stop_browser() {
  stop_process_group "$BROWSER_PID" "$BROWSER_PGID"
  BROWSER_PID=""
  BROWSER_PGID=""
}
stop_server() {
  stop_process_group "$SERVER_PID" "$SERVER_PGID"
  SERVER_PID=""
  SERVER_PGID=""
}
cleanup() {
  stop_browser
  stop_server
  rm -rf "$BROWSER_DATA_DIR"
  rm -f \
    "$SERVER_PORT_FILE" \
    "$SMOKE_RESULT_FILE" \
    "$SMOKE_RESULT_FILE.pending" \
    "$PAGE_REQUEST_FILE" \
    "$SERVER_LOG" \
    "$BROWSER_LOG"
}
trap cleanup EXIT

print_diagnostics() {
  echo "--- browser diagnostics ---" >&2
  if [[ -s "$BROWSER_LOG" ]]; then
    tail -n 80 "$BROWSER_LOG" >&2
  else
    echo "(browser produced no diagnostics)" >&2
  fi
  echo "--- server diagnostics ---" >&2
  if [[ -s "$SERVER_LOG" ]]; then
    tail -n 80 "$SERVER_LOG" >&2
  else
    echo "(server produced no diagnostics)" >&2
  fi
}

setsid python3 -u - \
  "$MOCKUP" \
  "$SERVER_PORT_FILE" \
  "$SMOKE_RESULT_FILE" \
  "$SMOKE_TOKEN" \
  "$PAGE_REQUEST_FILE" \
  >"$SERVER_LOG" 2>&1 <<'PY' &
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

mockup = Path(sys.argv[1])
port_file = Path(sys.argv[2])
result_file = Path(sys.argv[3])
token = sys.argv[4]
page_request_file = Path(sys.argv[5])

observer = f"""<script>
(() => {{
  const token = {json.dumps(token)};
  const report = () => {{
    const marker = document.querySelector('#smoke-result');
    const value = marker?.dataset.smoke;
    if (!value || value === 'pending') {{ window.setTimeout(report, 25); return; }}
    const query = new URLSearchParams({{ token, value, text: marker.textContent || '' }});
    fetch('/__pocketshell_smoke_result?' + query, {{ cache: 'no-store' }});
  }};
  report();
}})();
</script>"""


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        request = urlparse(self.path)
        if request.path == "/__pocketshell_smoke_result":
            query = parse_qs(request.query)
            if query.get("token") != [token]:
                self.send_error(403)
                return
            value = query.get("value", [""])[0]
            text = query.get("text", [""])[0]
            pending = result_file.with_name(result_file.name + ".pending")
            pending.write_text(f"{value}\n{text}\n", encoding="utf-8")
            os.replace(pending, result_file)
            self.send_response(204)
            self.end_headers()
            return

        if request.path != "/ux-redesign-2238.html":
            self.send_error(404)
            return
        source = mockup.read_text(encoding="utf-8")
        if "</body>" not in source:
            self.send_error(500, "mockup has no body")
            return
        body = source.replace("</body>", observer + "\n</body>", 1).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        self.wfile.flush()
        page_request_file.write_text("loaded\n", encoding="ascii")

    def log_message(self, message, *args):
        print(message % args, flush=True)


server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
port_file.write_text(str(server.server_port), encoding="ascii")
server.serve_forever()
PY
SERVER_PID=$!
SERVER_PGID=$SERVER_PID

server_deadline=$((SECONDS + SERVER_START_TIMEOUT_SECONDS))
while [[ ! -s "$SERVER_PORT_FILE" ]]; do
  kill -0 "$SERVER_PID" 2>/dev/null || break
  (( SECONDS < server_deadline )) || break
  sleep 0.1
done
[[ -s "$SERVER_PORT_FILE" ]] \
  || {
    echo "FAIL: browser smoke server did not start within ${SERVER_START_TIMEOUT_SECONDS}s" >&2
    print_diagnostics
    exit 1
  }

SERVER_PORT="$(<"$SERVER_PORT_FILE")"
[[ "$SERVER_PORT" =~ ^[1-9][0-9]*$ ]] \
  || {
    echo "FAIL: browser smoke server returned an invalid port" >&2
    print_diagnostics
    exit 1
  }
SMOKE_QUERY="smoke=1"
if [[ "${POCKETSHELL_FORCE_COLORS:-0}" == "1" ]]; then
  SMOKE_QUERY+="&forced-colors=1"
fi
MOCKUP_URI="http://127.0.0.1:$SERVER_PORT/ux-redesign-2238.html?$SMOKE_QUERY"
setsid env -u DBUS_SESSION_BUS_ADDRESS -u DBUS_SYSTEM_BUS_ADDRESS \
  "$BROWSER" \
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
  "${BROWSER_MODE_FLAGS[@]}" \
  --user-data-dir="$BROWSER_DATA_DIR" \
  --window-size=390,844 \
  --virtual-time-budget=5000 \
  "$MOCKUP_URI" \
  >"$BROWSER_LOG" 2>&1 &
BROWSER_PID=$!
BROWSER_PGID=$BROWSER_PID

browser_load_deadline=$((SECONDS + BROWSER_LOAD_TIMEOUT_SECONDS))
while [[ ! -s "$PAGE_REQUEST_FILE" ]]; do
  kill -0 "$BROWSER_PID" 2>/dev/null || break
  kill -0 "$SERVER_PID" 2>/dev/null || break
  (( SECONDS < browser_load_deadline )) || break
  sleep 0.1
done

if [[ ! -s "$PAGE_REQUEST_FILE" ]]; then
  stop_browser
  stop_server
  echo "FAIL: browser did not load the smoke page within ${BROWSER_LOAD_TIMEOUT_SECONDS}s" >&2
  print_diagnostics
  exit 1
fi

result_deadline=$((SECONDS + SMOKE_RESULT_TIMEOUT_SECONDS))
while [[ ! -s "$SMOKE_RESULT_FILE" ]]; do
  kill -0 "$BROWSER_PID" 2>/dev/null || break
  kill -0 "$SERVER_PID" 2>/dev/null || break
  (( SECONDS < result_deadline )) || break
  sleep 0.1
done

stop_browser
stop_server

[[ -s "$SMOKE_RESULT_FILE" ]] \
  || {
    echo "FAIL: browser interaction smoke test did not report a result within ${SMOKE_RESULT_TIMEOUT_SECONDS}s after page load" >&2
    print_diagnostics
    exit 1
  }
IFS= read -r SMOKE_RESULT <"$SMOKE_RESULT_FILE"
[[ "$SMOKE_RESULT" == "pass" ]] \
  || {
    echo "FAIL: browser interaction smoke test reported data-smoke=\"$SMOKE_RESULT\"" >&2
    tail -n +2 "$SMOKE_RESULT_FILE" >&2
    print_diagnostics
    exit 1
  }

echo "PASS: #2238 HTML prototype browser smoke test"
