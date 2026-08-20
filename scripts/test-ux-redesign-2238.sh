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

BROWSER_DATA_DIR="$(mktemp -d)"
SERVER_PORT_FILE="$(mktemp)"
SMOKE_RESULT_FILE="$(mktemp)"
SERVER_LOG="$(mktemp)"
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
  rm -f "$SERVER_PORT_FILE" "$SMOKE_RESULT_FILE" "$SMOKE_RESULT_FILE.pending" "$SERVER_LOG"
}
trap cleanup EXIT

setsid python3 -u - "$MOCKUP" "$SERVER_PORT_FILE" "$SMOKE_RESULT_FILE" "$SMOKE_TOKEN" >"$SERVER_LOG" 2>&1 <<'PY' &
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

    def log_message(self, message, *args):
        print(message % args, flush=True)


server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
port_file.write_text(str(server.server_port), encoding="ascii")
server.serve_forever()
PY
SERVER_PID=$!
SERVER_PGID=$SERVER_PID

deadline=$((SECONDS + 30))
while [[ ! -s "$SERVER_PORT_FILE" ]]; do
  kill -0 "$SERVER_PID" 2>/dev/null || break
  (( SECONDS < deadline )) || break
  sleep 0.1
done
[[ -s "$SERVER_PORT_FILE" ]] \
  || { echo "FAIL: browser smoke server did not start" >&2; cat "$SERVER_LOG" >&2; exit 1; }

SERVER_PORT="$(<"$SERVER_PORT_FILE")"
[[ "$SERVER_PORT" =~ ^[1-9][0-9]*$ ]] \
  || { echo "FAIL: browser smoke server returned an invalid port" >&2; cat "$SERVER_LOG" >&2; exit 1; }
MOCKUP_URI="http://127.0.0.1:$SERVER_PORT/ux-redesign-2238.html?smoke=1"
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
  --user-data-dir="$BROWSER_DATA_DIR" \
  --window-size=390,844 \
  --virtual-time-budget=5000 \
  "$MOCKUP_URI" &
BROWSER_PID=$!
BROWSER_PGID=$BROWSER_PID

while [[ ! -s "$SMOKE_RESULT_FILE" ]]; do
  kill -0 "$BROWSER_PID" 2>/dev/null || break
  kill -0 "$SERVER_PID" 2>/dev/null || break
  (( SECONDS < deadline )) || break
  sleep 0.1
done

stop_browser
stop_server

[[ -s "$SMOKE_RESULT_FILE" ]] \
  || { echo "FAIL: browser interaction smoke test did not report a result" >&2; cat "$SERVER_LOG" >&2; exit 1; }
IFS= read -r SMOKE_RESULT <"$SMOKE_RESULT_FILE"
[[ "$SMOKE_RESULT" == "pass" ]] \
  || { echo "FAIL: browser interaction smoke test reported data-smoke=\"$SMOKE_RESULT\"" >&2; tail -n +2 "$SMOKE_RESULT_FILE" >&2; exit 1; }

echo "PASS: #2238 HTML prototype browser smoke test"
