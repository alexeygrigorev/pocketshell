#!/usr/bin/env python3
"""PocketShell visual UI fixture server. Python 3.10+, Android SDK, JDK 17."""
from __future__ import annotations

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import secrets
import sys
from urllib.parse import parse_qs, urlsplit

from catalog import discover
from engine import Engine, RepositoryLock

WEB = Path(__file__).with_name("web")
ASSETS = {"/": ("index.html", "text/html; charset=utf-8"),
          "/app.js": ("app.js", "text/javascript; charset=utf-8"),
          "/style.css": ("style.css", "text/css; charset=utf-8")}


def allowed_host(value: str) -> bool:
    try:
        parsed = urlsplit("http://" + value)
        return (parsed.hostname in {"127.0.0.1", "localhost", "::1"}
                and parsed.username is None and parsed.password is None
                and not parsed.path and not parsed.query and not parsed.fragment
                and (parsed.port is None or 1 <= parsed.port <= 65535))
    except ValueError:
        return False


def make_handler(engine: Engine, token: str):
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(10)

        def log_message(self, *_args):
            pass  # Do not log tokens or user-supplied URLs.

        def reply(self, code, body, content_type="application/json; charset=utf-8"):
            if not isinstance(body, bytes):
                body = json.dumps(body, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("X-Frame-Options", "DENY")
            self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; "
                             "style-src 'self'; img-src 'self' blob:; connect-src 'self'; "
                             "object-src 'none'; base-uri 'none'; frame-ancestors 'none'")
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError, TimeoutError):
                pass

        def secure(self, auth=True):
            host = self.headers.get("Host", "")
            if not allowed_host(host):
                self.reply(403, {"error": "Use a loopback hostname via SSH port forwarding"})
                return False
            origin = self.headers.get("Origin")
            if origin is not None and origin != "http://" + host:
                self.reply(403, {"error": "Cross-origin requests are forbidden"})
                return False
            supplied = self.headers.get("X-UI-Mock-Token", "")
            if auth and not secrets.compare_digest(supplied.encode("utf-8"), token.encode("ascii")):
                self.reply(401, {"error": "Open the startup URL including its #token= fragment"})
                return False
            return True

        def do_GET(self):
            url = urlsplit(self.path)
            if url.path in ASSETS:
                if self.secure(auth=False):
                    name, media = ASSETS[url.path]
                    self.reply(200, (WEB / name).read_bytes(), media)
                return
            if not self.secure():
                return
            if url.path == "/api/catalog":
                self.reply(200, engine.catalog())
            elif url.path == "/api/state":
                self.reply(200, engine.status())
            elif url.path == "/api/log":
                with engine.cv:
                    self.reply(200, {"lines": list(engine.lines)})
            elif url.path == "/api/image":
                try:
                    query = parse_qs(url.query)
                    data = engine.image(int(query["index"][0]), int(query["revision"][0]))
                    self.reply(200, data, "image/png")
                except (ValueError, KeyError, IndexError):
                    self.reply(409, {"error": "Image changed; refresh state"})
            else:
                self.reply(404, {"error": "Not found"})

        def do_POST(self):
            if not self.secure():
                return
            try:
                if self.headers.get("Transfer-Encoding"):
                    raise ValueError("Chunked request bodies are not supported")
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 4096:
                    raise ValueError("Request body must be 1..4096 bytes")
                if self.headers.get("Content-Type", "").split(";")[0] != "application/json":
                    raise ValueError("Content-Type must be application/json")
                body = json.loads(self.rfile.read(length))
                if not isinstance(body, dict):
                    raise ValueError("Body must be an object")
                if self.path == "/api/select":
                    if set(body) != {"case_id"} or not isinstance(body["case_id"], str):
                        raise ValueError("Expected one string case_id")
                    engine.request(body["case_id"], "Fixture selected")
                elif self.path == "/api/rebuild":
                    if body:
                        raise ValueError("Rebuild body must be {}")
                    engine.request()
                else:
                    self.reply(404, {"error": "Not found"})
                    return
            except (ValueError, TypeError, TimeoutError) as exc:
                self.reply(400, {"error": str(exc)})
                return
            self.reply(202, engine.status())
    return Handler


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--port", type=int, default=4173)
    parser.add_argument("--case", help="Case ID from --list")
    parser.add_argument("--list", action="store_true", help="List supported fixtures without running Gradle")
    parser.add_argument("--include-ui-kit", action="store_true", help="Also show ui-kit examples, some of which mirror rather than call real screens")
    parser.add_argument("--no-watch", action="store_true")
    parser.add_argument("--build-timeout", type=float, default=600)
    args = parser.parse_args(argv)
    root = args.repo.resolve()
    if not (root / "app2/build.gradle.kts").is_file():
        parser.error("--repo must point to the PocketShell repository root")
    if not 1 <= args.port <= 65535 or args.build_timeout <= 0:
        parser.error("Invalid port or build timeout")
    if args.list:
        cases, warnings = discover(root, args.include_ui_kit)
        for c in cases:
            print(f"{c.id}  {c.class_name.rsplit('.', 1)[-1]}.{c.method}  [{c.kind}]")
        for warning in warnings:
            print("WARNING: " + warning, file=sys.stderr)
        return 0 if cases else 1
    lock, engine, server = None, None, None
    try:
        lock = RepositoryLock(root)
        engine = Engine(root, include_kit=args.include_ui_kit, watch=not args.no_watch,
                        timeout=args.build_timeout)
        token = secrets.token_urlsafe(32)
        server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(engine, token))
        server.daemon_threads = True
        engine.start(args.case)
        print(f"UI mock: http://127.0.0.1:{args.port}/#token={token}", flush=True)
        print(f"SSH tunnel on Windows/Linux client: ssh -N -L {args.port}:127.0.0.1:{args.port} USER@DEVBOX", flush=True)
        print("Visual fixture renderer, not an interactive emulator. Ctrl+C stops this server.", flush=True)
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        return 0
    except (OSError, ValueError, RuntimeError) as exc:
        print(f"ui-mock: {exc}", file=sys.stderr)
        return 1
    finally:
        if server:
            server.server_close()
        if engine:
            engine.close()
        if lock:
            lock.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
