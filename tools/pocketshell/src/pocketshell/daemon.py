"""`pocketshell daemon` — Unix-socket JSON-RPC 2.0 server.

First-PR scope (locked by the daemon-mode spike on issue #170 comment
4554814908): expose a single RPC method ``usage.fetch`` that delegates to
the existing ``quse`` subprocess flow in :mod:`pocketshell.usage`, with a
30-second per-method cache. No systemd, no other RPC methods, no Kotlin
changes — those land in follow-up PRs.

Why this exists
---------------

Each ``pocketshell usage`` call today re-imports Python and re-runs the
``quse`` provider scan, which costs ~150-400 ms of interpreter cold-start
before any real work happens. A long-lived daemon process eliminates the
interpreter import cost on every call, and the in-memory cache short-
circuits the second-and-later calls within a TTL window.

Design choices (verbatim from the spike)
----------------------------------------

- **Transport**: Unix domain socket carrying 4-byte big-endian
  length-prefixed UTF-8 JSON-RPC 2.0 frames. Filesystem ACL is the
  security boundary (``chmod 0600`` socket, ``chmod 0700`` parent dir).
  Reject :mod:`multiprocessing.connection` because its default authkey
  uses :mod:`pickle`, an RCE footgun.
- **Socket path**: ``$POCKETSHELL_DAEMON_SOCKET`` override (test/dev),
  then ``$XDG_RUNTIME_DIR/pocketshell/daemon.sock``, then
  ``~/.cache/pocketshell/daemon.sock`` fallback for hosts without XDG
  (macOS, minimal containers).
- **Lifecycle**: gpg-agent pattern. ``pocketshell daemon start`` forks
  once via :func:`subprocess.Popen` with ``start_new_session=True`` so
  the child is reparented to PID 1. No double-fork (PEP-3143 /
  ``python-daemon`` is over-engineered for this case).
- **Idle timeout**: 120 s default; configurable via
  ``POCKETSHELL_DAEMON_IDLE_SECS``. Setting it to 0 disables idle
  shutdown (used by the future systemd ``Type=simple`` always-on mode).
- **Cache**: in-memory ``{(method, frozen_args): (timestamp, value)}``
  with per-method TTL. ``usage.fetch`` TTL is 30 s. Failures (non-zero
  exit) are NOT cached so a transient ``quse`` hiccup does not pin a
  bad result for 30 s. ``--no-cache`` propagates as a JSON-RPC param.
- **Stale-socket recovery**: the daemon ``os.unlink``-s the socket path
  before ``bind()`` and again on shutdown via an atexit handler. The
  CLI probes the socket with ``connect()``; ``ECONNREFUSED`` /
  ``ENOENT`` falls through to either spawning a fresh daemon or running
  the one-shot subprocess path. A stale dead file therefore self-heals
  on the next CLI call.

The daemon is a pure optimisation; the CLI path falls through cleanly
when the daemon is absent or refuses (D22 hard-cut applies to the
direction of evolution, not to the no-daemon escape hatch).
"""

from __future__ import annotations

import json
import os
import signal
import socket
import struct
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Mapping, Optional

# Public so the CLI module and tests can reuse without duplicating the
# default. Override via env var ``POCKETSHELL_DAEMON_IDLE_SECS`` for
# tests that want to assert idle shutdown without waiting two minutes.
DEFAULT_IDLE_TIMEOUT_SECS = 120.0

# Per-method TTL table. Add new methods here; consumers should NOT
# special-case TTL outside this map so the cache policy stays auditable
# in one place.
METHOD_TTLS: Mapping[str, float] = {
    "usage.fetch": 30.0,
    # `repos.list_local` is cheap-to-recompute but the Android picker may
    # poll it repeatedly while the user types; a short cache keeps the
    # filesystem walk off the hot path without showing stale state for
    # more than a couple of seconds.
    "repos.list_local": 10.0,
}

# Length-prefix is a 4-byte unsigned big-endian integer. ``struct``
# format string lives once at module scope so the framing helpers do not
# drift apart.
_LENGTH_PREFIX_FORMAT = "!I"
_LENGTH_PREFIX_SIZE = struct.calcsize(_LENGTH_PREFIX_FORMAT)

# Cap each frame at 4 MiB. That is two orders of magnitude larger than
# the ``quse --json`` payload (~1.5 KB per provider) so we never trip in
# practice, but small enough that a malformed length prefix cannot trick
# us into allocating gigabytes.
_MAX_FRAME_BYTES = 4 * 1024 * 1024

# JSON-RPC 2.0 error codes (the standard ones we actually emit).
JSONRPC_PARSE_ERROR = -32700
JSONRPC_INVALID_REQUEST = -32600
JSONRPC_METHOD_NOT_FOUND = -32601
JSONRPC_INVALID_PARAMS = -32602
JSONRPC_INTERNAL_ERROR = -32603


# ---------------------------------------------------------------------------
# Socket-path resolution
# ---------------------------------------------------------------------------


def resolve_socket_path() -> Path:
    """Return the Unix socket path the daemon binds and the CLI connects to.

    Resolution order matches the spike:

    1. ``$POCKETSHELL_DAEMON_SOCKET`` if set (test/dev override).
    2. ``$XDG_RUNTIME_DIR/pocketshell/daemon.sock`` if XDG is defined.
    3. ``~/.cache/pocketshell/daemon.sock`` fallback for hosts without
       XDG (macOS user sessions, minimal Alpine, Docker containers).

    The parent directory is created with mode ``0700`` so a sibling user
    on a shared box cannot read or write the socket. The socket itself
    inherits mode ``0600`` via :func:`os.umask` set in :meth:`Daemon.serve`.
    """
    override = os.environ.get("POCKETSHELL_DAEMON_SOCKET")
    if override:
        return Path(override)

    xdg = os.environ.get("XDG_RUNTIME_DIR")
    if xdg:
        return Path(xdg) / "pocketshell" / "daemon.sock"

    return Path.home() / ".cache" / "pocketshell" / "daemon.sock"


def resolve_pid_path(socket_path: Optional[Path] = None) -> Path:
    """Return the PID file path sitting next to the socket.

    Kept next to the socket so a single ``rm -rf`` (or
    ``XDG_RUNTIME_DIR`` auto-clean on logout) takes both files out
    together. Tests can override via ``socket_path`` to keep their
    fixtures contained to a tmpdir.
    """
    if socket_path is None:
        socket_path = resolve_socket_path()
    return socket_path.with_suffix(".pid")


def _ensure_socket_dir(socket_path: Path) -> None:
    """Create the socket's parent dir with mode 0700 if missing."""
    parent = socket_path.parent
    parent.mkdir(parents=True, exist_ok=True)
    # ``mkdir(mode=)`` is masked by the process umask; chmod afterwards
    # so we land on 0700 regardless of the inherited umask.
    try:
        os.chmod(parent, 0o700)
    except PermissionError:
        # Shared-mount edge cases on macOS-over-NFS etc. We still want
        # to try to serve; the socket itself is the authoritative ACL.
        pass


# ---------------------------------------------------------------------------
# Length-prefixed JSON-RPC framing
# ---------------------------------------------------------------------------


class FramingError(RuntimeError):
    """Raised when a framed JSON-RPC message is malformed or truncated."""


def _recv_exact(sock: socket.socket, n: int) -> bytes:
    """Read exactly ``n`` bytes from ``sock`` or raise :class:`FramingError`.

    ``socket.recv`` is allowed to return fewer bytes than requested, so
    we loop. Returning a short read here is the most common framing-
    error footgun; making the helper explicit keeps callers safe.
    """
    chunks: list[bytes] = []
    remaining = n
    while remaining > 0:
        chunk = sock.recv(remaining)
        if not chunk:
            raise FramingError(
                f"socket closed after {n - remaining}/{n} bytes"
            )
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def send_frame(sock: socket.socket, payload: bytes) -> None:
    """Send a single length-prefixed frame on ``sock``.

    Raises :class:`FramingError` if the payload exceeds
    :data:`_MAX_FRAME_BYTES` so a misbehaving caller cannot wedge a
    16 MiB write into the socket before the peer notices.
    """
    if len(payload) > _MAX_FRAME_BYTES:
        raise FramingError(
            f"frame too large: {len(payload)} > {_MAX_FRAME_BYTES}"
        )
    header = struct.pack(_LENGTH_PREFIX_FORMAT, len(payload))
    sock.sendall(header + payload)


def recv_frame(sock: socket.socket) -> bytes:
    """Receive a single length-prefixed frame from ``sock``.

    Returns the raw payload bytes. Caller is responsible for JSON
    decoding so the framing layer is reusable for non-JSON future
    methods if any ever land.
    """
    header = _recv_exact(sock, _LENGTH_PREFIX_SIZE)
    (length,) = struct.unpack(_LENGTH_PREFIX_FORMAT, header)
    if length > _MAX_FRAME_BYTES:
        raise FramingError(
            f"frame too large: {length} > {_MAX_FRAME_BYTES}"
        )
    if length == 0:
        return b""
    return _recv_exact(sock, length)


def send_json(sock: socket.socket, obj: Any) -> None:
    """Convenience: encode ``obj`` as UTF-8 JSON and send one frame."""
    payload = json.dumps(obj, separators=(",", ":")).encode("utf-8")
    send_frame(sock, payload)


def recv_json(sock: socket.socket) -> Any:
    """Convenience: receive one frame and decode it as UTF-8 JSON."""
    raw = recv_frame(sock)
    return json.loads(raw.decode("utf-8"))


# ---------------------------------------------------------------------------
# RPC handler registry + cache
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class _CacheKey:
    """Hashable cache key derived from ``(method, params)``.

    JSON params are normalised by ``json.dumps(..., sort_keys=True)``
    so semantically-equal param dicts collapse to one entry even when
    the client sends keys in a different order.
    """

    method: str
    params_json: str

    @classmethod
    def of(cls, method: str, params: Optional[Mapping[str, Any]]) -> "_CacheKey":
        # ``no_cache`` is a control flag, not a parameter that affects
        # the upstream call. Strip it before keying so a no-cache miss
        # still populates the same slot a subsequent cached call uses.
        params_filtered = {
            k: v for k, v in (params or {}).items() if k != "no_cache"
        }
        return cls(
            method=method,
            params_json=json.dumps(params_filtered, sort_keys=True),
        )


class _Cache:
    """In-memory ``(method, params) -> (expires_at, value)`` cache.

    Per-method TTL is consulted via :data:`METHOD_TTLS`. Failures are
    never cached: only the handler decides what to put here, and the
    handler only stores success responses.
    """

    def __init__(self, clock: Callable[[], float] = time.monotonic) -> None:
        self._clock = clock
        self._lock = threading.Lock()
        self._entries: dict[_CacheKey, tuple[float, Any]] = {}

    def get(self, key: _CacheKey) -> Optional[Any]:
        with self._lock:
            entry = self._entries.get(key)
            if entry is None:
                return None
            expires_at, value = entry
            if self._clock() >= expires_at:
                # Lazy eviction: drop the stale entry so the dict does
                # not grow unbounded over the daemon's life.
                self._entries.pop(key, None)
                return None
            return value

    def put(self, key: _CacheKey, value: Any, ttl_secs: float) -> None:
        if ttl_secs <= 0:
            return
        expires_at = self._clock() + ttl_secs
        with self._lock:
            self._entries[key] = (expires_at, value)

    def clear(self) -> None:
        with self._lock:
            self._entries.clear()


# Handler signature: ``(params: Mapping[str, Any]) -> Any``.
RpcHandler = Callable[[Mapping[str, Any]], Any]


# ---------------------------------------------------------------------------
# Method: usage.fetch
# ---------------------------------------------------------------------------


def _usage_fetch_handler(params: Mapping[str, Any]) -> dict[str, Any]:
    """Run ``quse [provider] --json`` and return its result envelope.

    The envelope shape is:

    .. code-block:: json

       {
         "stdout": "...quse --json output...",
         "stderr": "",
         "returncode": 0,
         "provider": "codex"  // or null
       }

    Returning raw stdout (rather than parsed JSON) preserves byte-for-
    byte parity with ``quse --json``; the existing Kotlin
    ``QuseUsageJsonParser`` keeps working without modification when the
    CLI re-emits the daemon's response on stdout. ``quse --json``
    actually emits **NDJSON** (one provider per line, not a single
    document), so we cannot parse-then-re-serialise without changing
    the wire format.
    """
    # Lazy import to avoid a circular module load at startup: the
    # daemon module is imported from ``cli.py`` which also imports
    # ``usage`` directly.
    from pocketshell import usage as _usage

    provider = params.get("provider")
    if provider is not None and not isinstance(provider, str):
        raise _RpcError(
            JSONRPC_INVALID_PARAMS,
            "usage.fetch: `provider` must be a string or null",
        )

    quse_path = _usage._resolve_quse_binary()
    if quse_path is None:
        # Mirror the CLI's exit-127 behaviour. The daemon does NOT
        # cache this; a `quse` install during the daemon's lifetime
        # should be picked up on the next call.
        return {
            "stdout": "",
            "stderr": (
                "pocketshell: `quse` is not installed on this host. "
                "Install it via `uv tool install quse` or `pipx install quse` "
                "and re-run.\n"
            ),
            "returncode": 127,
            "provider": provider,
        }

    args: list[str] = [quse_path]
    if provider:
        args.append(provider)
    args.append("--json")
    completed = subprocess.run(
        args,
        check=False,
        capture_output=True,
        text=True,
    )
    return {
        "stdout": completed.stdout,
        "stderr": completed.stderr,
        "returncode": completed.returncode,
        "provider": provider,
    }


# ---------------------------------------------------------------------------
# Method: repos.list_local
# ---------------------------------------------------------------------------


def _repos_list_local_handler(params: Mapping[str, Any]) -> list[dict[str, Any]]:
    """Scan configured roots for cloned git repos and return them as JSON.

    Thin shim around :func:`pocketshell.repos.daemon_handler`. The shim
    exists so the daemon module does not need to import
    :mod:`pocketshell.repos` at module load time (which would create a
    circular dependency: ``repos`` imports ``daemon`` lazily for the
    client-side probe). Lazy import keeps the daemon's cold-start cost
    paid only when this method is actually invoked.
    """
    from pocketshell import repos as _repos

    return _repos.daemon_handler(dict(params))


# Single shared registry; tests can register additional methods via
# :meth:`Daemon.register_method` on a fresh instance without touching
# this dict.
DEFAULT_METHODS: Mapping[str, RpcHandler] = {
    "usage.fetch": _usage_fetch_handler,
    "repos.list_local": _repos_list_local_handler,
}


# ---------------------------------------------------------------------------
# Daemon server
# ---------------------------------------------------------------------------


class _RpcError(Exception):
    """Internal helper carrying JSON-RPC error code + message."""

    def __init__(self, code: int, message: str, data: Any = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.data = data


class Daemon:
    """Unix-socket JSON-RPC 2.0 server for ``pocketshell`` subcommands.

    Single-threaded for now: handler calls (especially ``usage.fetch``)
    are dominated by the ``quse`` subprocess, and serialising them
    keeps the cache logic simple. Concurrent reads of a cache hit are
    not a hot enough path to justify a thread pool — the cache hit
    returns in microseconds; queueing behind a single accept loop is
    fine. Tests for the "two concurrent clients" scenario rely on the
    accept loop draining quickly between handler invocations.
    """

    def __init__(
        self,
        socket_path: Path,
        *,
        idle_timeout: float = DEFAULT_IDLE_TIMEOUT_SECS,
        methods: Optional[Mapping[str, RpcHandler]] = None,
        pid_path: Optional[Path] = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.socket_path = socket_path
        self.pid_path = pid_path or resolve_pid_path(socket_path)
        self.idle_timeout = idle_timeout
        self._methods: dict[str, RpcHandler] = dict(methods or DEFAULT_METHODS)
        self._cache = _Cache(clock=clock)
        self._clock = clock
        self._server_sock: Optional[socket.socket] = None
        self._stop_event = threading.Event()
        self._last_activity = self._clock()

    # -- registration ----------------------------------------------------

    def register_method(self, name: str, handler: RpcHandler) -> None:
        self._methods[name] = handler

    # -- lifecycle -------------------------------------------------------

    def _bind(self) -> socket.socket:
        _ensure_socket_dir(self.socket_path)
        # Always unlink first. If a previous daemon crashed without
        # cleanup the path is a dead file; bind() would fail with
        # EADDRINUSE. Recreating cleanly is the documented self-heal.
        try:
            os.unlink(self.socket_path)
        except FileNotFoundError:
            pass

        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        # Restrict the socket file to user-only. We set umask before
        # bind() because Linux derives the socket file mode from the
        # process umask (no fchmod hook on AF_UNIX at bind time).
        old_umask = os.umask(0o077)
        try:
            sock.bind(str(self.socket_path))
        finally:
            os.umask(old_umask)
        sock.listen(8)
        # Defence in depth: chmod the socket explicitly in case some
        # platform ignored the umask.
        try:
            os.chmod(self.socket_path, 0o600)
        except FileNotFoundError:
            pass
        return sock

    def _write_pid_file(self) -> None:
        try:
            self.pid_path.write_text(f"{os.getpid()}\n")
        except OSError:
            # Best-effort. Missing PID file means ``daemon status``
            # falls back to socket-probe semantics.
            pass

    def _remove_pid_file(self) -> None:
        try:
            self.pid_path.unlink()
        except FileNotFoundError:
            pass

    def _remove_socket_file(self) -> None:
        try:
            os.unlink(self.socket_path)
        except FileNotFoundError:
            pass

    def serve(self) -> None:
        """Run the accept loop until idle timeout or :meth:`shutdown`.

        The loop:

        1. ``accept`` with a select-based timeout slice of 1 s.
        2. On accept, handle the one request frame, send the response,
           close the client socket. (Connections are short-lived; the
           CLI opens a fresh socket per call.)
        3. If no request arrived in ``idle_timeout`` seconds, exit.

        Idle timeout 0 disables the idle exit (used by future systemd
        ``Type=simple`` always-on mode).
        """
        self._server_sock = self._bind()
        self._write_pid_file()
        self._last_activity = self._clock()
        # Trap SIGTERM so `daemon stop` (and systemd Stop) shuts us
        # down cleanly with the atexit-equivalent path.
        signal.signal(signal.SIGTERM, self._on_signal)
        signal.signal(signal.SIGINT, self._on_signal)

        try:
            self._server_sock.settimeout(1.0)
            while not self._stop_event.is_set():
                # Check idle timeout BEFORE the accept call so a daemon
                # that has been idle past its limit exits without
                # blocking on the kernel for another full slice.
                if (
                    self.idle_timeout > 0
                    and self._clock() - self._last_activity >= self.idle_timeout
                ):
                    break
                try:
                    client_sock, _ = self._server_sock.accept()
                except socket.timeout:
                    continue
                except OSError:
                    # Server socket closed by `shutdown`.
                    break
                self._last_activity = self._clock()
                try:
                    self._handle_one(client_sock)
                finally:
                    try:
                        client_sock.close()
                    except OSError:
                        pass
        finally:
            self._cleanup()

    def shutdown(self) -> None:
        """Request a clean exit from the accept loop."""
        self._stop_event.set()
        sock = self._server_sock
        if sock is not None:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                sock.close()
            except OSError:
                pass

    def _on_signal(self, _signum: int, _frame: Any) -> None:
        self.shutdown()

    def _cleanup(self) -> None:
        if self._server_sock is not None:
            try:
                self._server_sock.close()
            except OSError:
                pass
            self._server_sock = None
        self._remove_socket_file()
        self._remove_pid_file()

    # -- request handling ------------------------------------------------

    def _handle_one(self, client_sock: socket.socket) -> None:
        """Read one JSON-RPC request, dispatch, write the response."""
        client_sock.settimeout(5.0)
        request_id: Any = None
        try:
            request = recv_json(client_sock)
        except (FramingError, json.JSONDecodeError) as exc:
            self._send_error(
                client_sock,
                request_id=None,
                code=JSONRPC_PARSE_ERROR,
                message=f"parse error: {exc}",
            )
            return

        if not isinstance(request, dict):
            self._send_error(
                client_sock,
                request_id=None,
                code=JSONRPC_INVALID_REQUEST,
                message="request must be a JSON object",
            )
            return

        request_id = request.get("id")
        method = request.get("method")
        params = request.get("params") or {}

        if not isinstance(method, str):
            self._send_error(
                client_sock,
                request_id=request_id,
                code=JSONRPC_INVALID_REQUEST,
                message="`method` must be a string",
            )
            return
        if not isinstance(params, Mapping):
            self._send_error(
                client_sock,
                request_id=request_id,
                code=JSONRPC_INVALID_PARAMS,
                message="`params` must be an object",
            )
            return

        # Built-in introspection methods. These do NOT touch the
        # registered handlers so a handler registry mutation in tests
        # cannot accidentally unbind ``daemon.ping``.
        if method == "daemon.ping":
            self._send_result(client_sock, request_id, {"ok": True, "pid": os.getpid()})
            return
        if method == "daemon.shutdown":
            self._send_result(client_sock, request_id, {"ok": True})
            # Defer the shutdown until after the response is on the wire.
            threading.Thread(target=self.shutdown, daemon=True).start()
            return

        handler = self._methods.get(method)
        if handler is None:
            self._send_error(
                client_sock,
                request_id=request_id,
                code=JSONRPC_METHOD_NOT_FOUND,
                message=f"unknown method: {method}",
            )
            return

        no_cache = bool(params.get("no_cache", False))
        cache_key = _CacheKey.of(method, params)
        ttl = METHOD_TTLS.get(method, 0.0)

        cached: Optional[Any] = None
        if not no_cache and ttl > 0:
            cached = self._cache.get(cache_key)
        if cached is not None:
            self._send_result(client_sock, request_id, cached, cached_hit=True)
            return

        try:
            result = handler(params)
        except _RpcError as exc:
            self._send_error(
                client_sock,
                request_id=request_id,
                code=exc.code,
                message=exc.message,
                data=exc.data,
            )
            return
        except Exception as exc:  # noqa: BLE001 — JSON-RPC envelope
            self._send_error(
                client_sock,
                request_id=request_id,
                code=JSONRPC_INTERNAL_ERROR,
                message=f"{type(exc).__name__}: {exc}",
            )
            return

        # Only cache successful results. ``usage.fetch`` carries its
        # own returncode inside the envelope; treat non-zero as a
        # failure so a transient quse error does not pin a bad result.
        success_for_cache = True
        if isinstance(result, dict) and "returncode" in result:
            success_for_cache = result.get("returncode") == 0
        if success_for_cache and not no_cache:
            self._cache.put(cache_key, result, ttl)

        self._send_result(client_sock, request_id, result, cached_hit=False)

    # -- wire helpers ----------------------------------------------------

    def _send_result(
        self,
        sock: socket.socket,
        request_id: Any,
        result: Any,
        *,
        cached_hit: bool = False,
    ) -> None:
        envelope = {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": result,
            # ``cached`` is a PocketShell extension; the JSON-RPC 2.0
            # spec allows extra top-level keys on responses. Used by
            # the test suite to assert the second of two clients sees a
            # cache hit.
            "cached": cached_hit,
        }
        try:
            send_json(sock, envelope)
        except (OSError, FramingError):
            # Peer hung up before we wrote — nothing to do; the client
            # will reconnect or fall through to the no-daemon path.
            pass

    def _send_error(
        self,
        sock: socket.socket,
        *,
        request_id: Any,
        code: int,
        message: str,
        data: Any = None,
    ) -> None:
        error: dict[str, Any] = {"code": code, "message": message}
        if data is not None:
            error["data"] = data
        envelope = {"jsonrpc": "2.0", "id": request_id, "error": error}
        try:
            send_json(sock, envelope)
        except (OSError, FramingError):
            pass


# ---------------------------------------------------------------------------
# Client-side helpers
# ---------------------------------------------------------------------------


class DaemonClientError(RuntimeError):
    """Raised when the client cannot reach a running daemon.

    Distinguishes "no daemon" (callable should fall through to the
    one-shot subprocess path) from "daemon errored" (callable should
    propagate the error to the user).
    """


def _connect(socket_path: Path, *, timeout: float = 1.0) -> socket.socket:
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.settimeout(timeout)
    sock.connect(str(socket_path))
    return sock


def call(
    method: str,
    params: Optional[Mapping[str, Any]] = None,
    *,
    socket_path: Optional[Path] = None,
    timeout: float = 5.0,
) -> Any:
    """Send one JSON-RPC request and return the ``result`` value.

    Raises :class:`DaemonClientError` if the socket does not exist or
    refuses the connection. Raises :class:`RuntimeError` with the
    JSON-RPC error message if the daemon returned a JSON-RPC error.
    """
    socket_path = socket_path or resolve_socket_path()
    if not socket_path.exists():
        raise DaemonClientError(f"socket missing: {socket_path}")
    try:
        sock = _connect(socket_path, timeout=timeout)
    except (ConnectionRefusedError, FileNotFoundError) as exc:
        raise DaemonClientError(f"daemon unreachable: {exc}") from exc
    except OSError as exc:
        raise DaemonClientError(f"daemon connect failed: {exc}") from exc
    try:
        sock.settimeout(timeout)
        send_json(sock, {
            "jsonrpc": "2.0",
            "id": 1,
            "method": method,
            "params": dict(params or {}),
        })
        response = recv_json(sock)
    finally:
        try:
            sock.close()
        except OSError:
            pass

    if not isinstance(response, dict):
        raise RuntimeError(f"daemon returned non-object response: {response!r}")
    if "error" in response and response["error"] is not None:
        err = response["error"]
        code = err.get("code") if isinstance(err, dict) else None
        message = err.get("message") if isinstance(err, dict) else str(err)
        raise RuntimeError(f"daemon error [{code}]: {message}")
    return response.get("result")


def is_daemon_running(socket_path: Optional[Path] = None) -> bool:
    """Return True if a daemon answers ``daemon.ping`` on the socket.

    Used by ``pocketshell daemon status`` and by the lazy-spawn logic
    in the CLI to decide whether to fall through to subprocess. A bare
    ``socket.exists()`` is not sufficient — a stale socket from a
    crashed daemon would falsely report "running".
    """
    socket_path = socket_path or resolve_socket_path()
    if not socket_path.exists():
        return False
    try:
        result = call("daemon.ping", socket_path=socket_path, timeout=1.0)
    except (DaemonClientError, RuntimeError, OSError):
        return False
    return bool(result and result.get("ok"))


# ---------------------------------------------------------------------------
# Lazy-spawn entrypoints
# ---------------------------------------------------------------------------


def spawn_detached(
    *,
    socket_path: Optional[Path] = None,
    idle_timeout: Optional[float] = None,
    python_executable: Optional[str] = None,
) -> int:
    """Spawn ``pocketshell daemon start`` as a detached child process.

    Implements the gpg-agent pattern: one ``subprocess.Popen`` with
    ``start_new_session=True`` so the child becomes a session leader
    and is reparented to PID 1 when this process exits. No double
    fork, no ``python-daemon`` PEP-3143 dance.

    Returns the spawned PID. Caller is responsible for polling
    :func:`is_daemon_running` to confirm readiness.
    """
    python_executable = python_executable or sys.executable
    cmd = [python_executable, "-m", "pocketshell", "daemon", "_serve"]
    env = dict(os.environ)
    if socket_path is not None:
        env["POCKETSHELL_DAEMON_SOCKET"] = str(socket_path)
    if idle_timeout is not None:
        env["POCKETSHELL_DAEMON_IDLE_SECS"] = str(idle_timeout)

    # ``DEVNULL`` for stdio so the child does not keep the parent's
    # terminal open. ``start_new_session=True`` is the key: it calls
    # ``setsid`` so the child is independent of the parent's
    # controlling terminal and the parent can exit immediately.
    proc = subprocess.Popen(
        cmd,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True,
        env=env,
        close_fds=True,
    )
    return proc.pid


def wait_until_ready(
    *,
    socket_path: Optional[Path] = None,
    deadline: float = 5.0,
    poll_interval: float = 0.05,
) -> bool:
    """Poll the socket until :func:`is_daemon_running` succeeds or timeout.

    50 ms polling matches the spike's "50 ms x 20 ≈ 1 s ceiling".
    Default deadline 5 s gives slow Python imports headroom on cold
    cache (the typical real-world worst case is ~1.5 s on the dev
    box).
    """
    socket_path = socket_path or resolve_socket_path()
    deadline_at = time.monotonic() + deadline
    while time.monotonic() < deadline_at:
        if is_daemon_running(socket_path):
            return True
        time.sleep(poll_interval)
    return False


def read_pid(pid_path: Optional[Path] = None) -> Optional[int]:
    """Read the daemon's PID file, returning ``None`` if absent/invalid."""
    pid_path = pid_path or resolve_pid_path()
    try:
        return int(pid_path.read_text().strip())
    except (FileNotFoundError, ValueError):
        return None


def stop_daemon(
    *,
    socket_path: Optional[Path] = None,
    timeout: float = 5.0,
) -> bool:
    """Stop a running daemon and remove its socket.

    Strategy:

    1. If a PID file exists, ``os.kill(pid, SIGTERM)`` — clean shutdown
       via the signal handler.
    2. Wait up to ``timeout`` seconds for the socket file to disappear.
    3. Fall through to the RPC ``daemon.shutdown`` method if the PID
       file is missing (legacy or test-spawned daemons).
    4. Always remove a stale socket file at the end so the next start
       has a clean slate.

    Returns ``True`` if a daemon was running and is now stopped,
    ``False`` if no daemon was running.
    """
    socket_path = socket_path or resolve_socket_path()
    pid_path = resolve_pid_path(socket_path)

    pid = read_pid(pid_path)
    was_running = is_daemon_running(socket_path)

    if pid is not None:
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            pid = None
        except PermissionError:
            pid = None

    if was_running and pid is None:
        # No PID file or kill failed; try the RPC shutdown instead.
        try:
            call("daemon.shutdown", socket_path=socket_path, timeout=timeout)
        except (DaemonClientError, RuntimeError, OSError):
            pass

    # Wait for the socket file to disappear.
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not socket_path.exists():
            break
        time.sleep(0.05)

    # Final cleanup: remove the socket if the daemon did not clean up
    # itself (SIGKILL recovery).
    try:
        os.unlink(socket_path)
    except FileNotFoundError:
        pass
    try:
        pid_path.unlink()
    except FileNotFoundError:
        pass

    return was_running


def serve_foreground(
    *,
    socket_path: Optional[Path] = None,
    idle_timeout: Optional[float] = None,
) -> int:
    """Run the daemon in the foreground until idle or shutdown.

    Used by both ``pocketshell daemon start`` (after the parent
    spawn-detaches) and by the hidden ``pocketshell daemon _serve``
    entrypoint that the lazy-spawn path execs. Returns the process
    exit code (0 = clean shutdown).
    """
    if socket_path is None:
        socket_path = resolve_socket_path()
    if idle_timeout is None:
        env_value = os.environ.get("POCKETSHELL_DAEMON_IDLE_SECS")
        if env_value is not None:
            try:
                idle_timeout = float(env_value)
            except ValueError:
                idle_timeout = DEFAULT_IDLE_TIMEOUT_SECS
        else:
            idle_timeout = DEFAULT_IDLE_TIMEOUT_SECS

    # Abort cleanly if another daemon is already serving the socket.
    # The lazy-spawn code path checks this too, but a manual
    # ``pocketshell daemon start`` race can still land here.
    if is_daemon_running(socket_path):
        return 0

    daemon = Daemon(socket_path=socket_path, idle_timeout=idle_timeout)
    daemon.serve()
    return 0
