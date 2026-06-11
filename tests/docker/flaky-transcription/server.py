#!/usr/bin/env python3
"""Controllable-failure Whisper-style transcription endpoint — issue #688.

Models a flaky network for the voice-transcription retry path so the
duplicate-insert regression can be reproduced deterministically in Docker
(and in regular CI via :app:integrationTest).

It mimics the slice of OpenAI's audio-transcriptions API that
`OkHttpWhisperClient` actually drives:

    POST /v1/audio/transcriptions   (multipart/form-data, ignored body)
        -> 200 {"text": "<transcript>"}        on success
        -> 503 {"error": ...}                  on a forced server failure
        -> (hang past the client call timeout)  on a forced timeout

The per-request "attempt" is keyed by an `X-Retry-Id` header the test sets
so each logical recording gets its own independent failure schedule. The
schedule is chosen by query/header so one container serves several
scenarios:

    failures=N   first N attempts for a given retry-id fail (503), then 200.
    mode=timeout the failing attempts hang `hang_seconds` instead of 503ing
                 (so the client's call timeout fires) — models the
                 "timeout that may have actually succeeded server-side".
    text=...     the transcript returned on the success attempt.

Defaults: failures=2, mode=error, text="hello from flaky transcription".

Also exposes:
    GET /healthz                      -> 200 "ok"  (container readiness)
    GET /attempts/<retry-id>          -> 200 {"attempts": <int>}  (test introspection)
    POST /reset                       -> 200       (clear all counters)

Pure stdlib so the image is just `python:3.12-slim` with no pip install.
"""

import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

# attempts[retry_id] = how many transcribe calls have been seen for that id.
_attempts: dict[str, int] = {}
_lock = threading.Lock()

DEFAULT_FAILURES = int(os.environ.get("FLAKY_DEFAULT_FAILURES", "2"))
DEFAULT_TEXT = os.environ.get(
    "FLAKY_DEFAULT_TEXT", "hello from flaky transcription"
)
DEFAULT_HANG_SECONDS = float(os.environ.get("FLAKY_HANG_SECONDS", "30"))


def _bump(retry_id: str) -> int:
    with _lock:
        n = _attempts.get(retry_id, 0) + 1
        _attempts[retry_id] = n
        return n


class Handler(BaseHTTPRequestHandler):
    # Silence the default noisy per-request logging.
    def log_message(self, *args):  # noqa: D401, ANN001
        pass

    def _json(self, code: int, payload: dict):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802
        parsed = urlparse(self.path)
        if parsed.path == "/healthz":
            self.send_response(200)
            self.send_header("Content-Length", "2")
            self.end_headers()
            self.wfile.write(b"ok")
            return
        if parsed.path.startswith("/attempts/"):
            retry_id = parsed.path[len("/attempts/"):]
            with _lock:
                n = _attempts.get(retry_id, 0)
            self._json(200, {"attempts": n})
            return
        self._json(404, {"error": "not found"})

    def do_POST(self):  # noqa: N802
        parsed = urlparse(self.path)
        if parsed.path == "/reset":
            with _lock:
                _attempts.clear()
            self._json(200, {"ok": True})
            return
        if parsed.path != "/v1/audio/transcriptions":
            self._json(404, {"error": "not found"})
            return

        # Drain the request body so the client's write side completes even
        # when we are about to hang or 503 — otherwise a half-read socket can
        # mask the intended timeout/error with a connection-reset instead.
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length:
            try:
                self.rfile.read(length)
            except Exception:
                pass

        query = parse_qs(parsed.query)
        retry_id = self.headers.get("X-Retry-Id", "default")
        failures = int(
            query.get("failures", [self.headers.get("X-Failures", DEFAULT_FAILURES)])[0]
        )
        mode = query.get("mode", [self.headers.get("X-Mode", "error")])[0]
        text = query.get("text", [self.headers.get("X-Text", DEFAULT_TEXT)])[0]
        hang_seconds = float(
            query.get("hang", [self.headers.get("X-Hang", DEFAULT_HANG_SECONDS)])[0]
        )

        attempt = _bump(retry_id)
        if attempt <= failures:
            if mode == "timeout":
                # Hang past the client call timeout. The client gives up and
                # surfaces a Transport failure; the request may still have
                # "succeeded" server-side — exactly the race issue #688 is
                # about. We do NOT respond.
                time.sleep(hang_seconds)
                # If the client is still around after the hang, send a late
                # success so a buggy client that keeps the socket would get
                # a second insert.
                self._json(200, {"text": text})
                return
            self._json(503, {"error": {"message": "forced flaky failure"}})
            return

        self._json(200, {"text": text})


def main():
    port = int(os.environ.get("FLAKY_PORT", "8089"))
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(f"flaky-transcription listening on 0.0.0.0:{port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
