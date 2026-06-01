"""Tests for the `pocketshell daemon` IPC server and lazy-spawn path.

First-PR scope (issue #219):

- Socket creation + JSON-RPC ping
- Two concurrent clients (cache miss + cache hit; second is faster)
- Socket-collision recovery (stale socket from crashed daemon)
- Daemon-crash recovery (SIGKILL the daemon, restart works)
- ``--no-daemon`` falls through to subprocess
- Idle-timeout exits the daemon process
- Socket-path fallback resolution (XDG + ``~/.cache`` + override)

Tests run against a sandbox socket (``POCKETSHELL_DAEMON_SOCKET``
pointed at a tmpdir) so they never clobber the real user-runtime
socket on the host running the test suite.
"""

from __future__ import annotations

import os
import signal
import socket
import subprocess
import sys
import time
from pathlib import Path
from typing import Iterator

import pytest
from click.testing import CliRunner

from pocketshell import daemon as daemon_mod
from pocketshell.cli import cli
from pocketshell.usage import usage_command


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture()
def sandbox_socket(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """Isolate the test from the real user-runtime daemon socket.

    Sets ``POCKETSHELL_DAEMON_SOCKET`` (which
    :func:`daemon.resolve_socket_path` honours first) at a per-test
    path so concurrent test runs do not collide and the test suite
    never touches ``$XDG_RUNTIME_DIR/pocketshell/`` on the host.
    """
    socket_path = tmp_path / "pocketshell-test.sock"
    monkeypatch.setenv("POCKETSHELL_DAEMON_SOCKET", str(socket_path))
    yield socket_path
    # Best-effort cleanup; daemon's atexit handler should have removed
    # the file, but a SIGKILL test leaves stale residue.
    for path in (socket_path, socket_path.with_suffix(".pid")):
        try:
            path.unlink()
        except FileNotFoundError:
            pass


def _spawn_daemon(
    socket_path: Path,
    *,
    idle_timeout: float = 120.0,
    extra_env: dict[str, str] | None = None,
) -> subprocess.Popen:
    """Spawn a real daemon process via ``python -m pocketshell daemon _serve``.

    Returns the running Popen handle. The caller is responsible for
    terminating the process at the end of the test.
    """
    env = dict(os.environ)
    env["POCKETSHELL_DAEMON_SOCKET"] = str(socket_path)
    env["POCKETSHELL_DAEMON_IDLE_SECS"] = str(idle_timeout)
    if extra_env:
        env.update(extra_env)
    proc = subprocess.Popen(
        [sys.executable, "-m", "pocketshell", "daemon", "_serve"],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
        start_new_session=True,
    )
    assert daemon_mod.wait_until_ready(
        socket_path=socket_path, deadline=10.0
    ), f"daemon did not become ready: {proc.stderr.read().decode(errors='replace') if proc.stderr else ''}"
    return proc


def _terminate(proc: subprocess.Popen, timeout: float = 5.0) -> None:
    """Send SIGTERM and wait; fall back to SIGKILL after ``timeout``."""
    if proc.poll() is not None:
        return
    proc.send_signal(signal.SIGTERM)
    try:
        proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=2.0)


@pytest.fixture()
def running_daemon(sandbox_socket: Path) -> Iterator[subprocess.Popen]:
    """Start a daemon process and tear it down after the test."""
    proc = _spawn_daemon(sandbox_socket)
    try:
        yield proc
    finally:
        _terminate(proc)


# ---------------------------------------------------------------------------
# 1. Socket-path resolution + framing primitives
# ---------------------------------------------------------------------------


def test_resolve_socket_path_prefers_override(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("POCKETSHELL_DAEMON_SOCKET", "/tmp/explicit.sock")
    monkeypatch.setenv("XDG_RUNTIME_DIR", "/run/user/1000")
    assert daemon_mod.resolve_socket_path() == Path("/tmp/explicit.sock")


def test_resolve_socket_path_falls_back_to_cache_without_xdg(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("POCKETSHELL_DAEMON_SOCKET", raising=False)
    monkeypatch.delenv("XDG_RUNTIME_DIR", raising=False)
    monkeypatch.setenv("HOME", "/home/test")
    expected = Path("/home/test/.cache/pocketshell/daemon.sock")
    assert daemon_mod.resolve_socket_path() == expected


def test_resolve_socket_path_uses_xdg_when_available(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("POCKETSHELL_DAEMON_SOCKET", raising=False)
    monkeypatch.setenv("XDG_RUNTIME_DIR", "/run/user/9999")
    assert daemon_mod.resolve_socket_path() == Path(
        "/run/user/9999/pocketshell/daemon.sock"
    )


def test_framing_roundtrip_short_payload() -> None:
    """Length-prefix framing handles small payloads correctly."""
    a, b = socket.socketpair()
    try:
        daemon_mod.send_json(a, {"hello": "world"})
        assert daemon_mod.recv_json(b) == {"hello": "world"}
    finally:
        a.close()
        b.close()


def test_framing_roundtrip_large_payload() -> None:
    """Length-prefix framing handles payloads larger than one ``recv`` chunk."""
    a, b = socket.socketpair()
    payload = {"data": "x" * (200 * 1024)}  # ~200 KB
    try:
        daemon_mod.send_json(a, payload)
        assert daemon_mod.recv_json(b) == payload
    finally:
        a.close()
        b.close()


def test_framing_rejects_oversized_send() -> None:
    a, b = socket.socketpair()
    try:
        with pytest.raises(daemon_mod.FramingError):
            daemon_mod.send_frame(a, b"x" * (5 * 1024 * 1024))
    finally:
        a.close()
        b.close()


# ---------------------------------------------------------------------------
# 2. Socket creation + JSON-RPC ping
# ---------------------------------------------------------------------------


def test_daemon_creates_socket_and_responds_to_ping(
    running_daemon: subprocess.Popen,
    sandbox_socket: Path,
) -> None:
    """The daemon should bind the socket and answer ``daemon.ping``."""
    assert sandbox_socket.exists()
    # Mode must be 0600 (owner read/write only).
    mode = sandbox_socket.stat().st_mode & 0o777
    assert mode == 0o600, f"socket mode is {oct(mode)}, expected 0600"

    result = daemon_mod.call(
        "daemon.ping",
        socket_path=sandbox_socket,
        timeout=2.0,
    )
    assert result == {"ok": True, "pid": running_daemon.pid}


def test_is_daemon_running_true_when_socket_responds(
    running_daemon: subprocess.Popen,
    sandbox_socket: Path,
) -> None:
    assert daemon_mod.is_daemon_running(sandbox_socket) is True


def test_is_daemon_running_false_when_no_socket(sandbox_socket: Path) -> None:
    # sandbox_socket fixture sets the env var but does not start a
    # daemon; the path therefore does not exist.
    assert not sandbox_socket.exists()
    assert daemon_mod.is_daemon_running(sandbox_socket) is False


# ---------------------------------------------------------------------------
# 3. usage.fetch end-to-end + cache hit
# ---------------------------------------------------------------------------


def _write_fake_quse(target_dir: Path, *, payload: str, sleep_secs: float = 0.0) -> Path:
    """Write a fake `quse` shell script under ``target_dir``.

    Used by the cache-hit tests so the first call has measurable
    latency and the second is verifiably faster. The payload is written
    to a sibling file and ``cat``-ed verbatim so the script does not
    smuggle in an extra newline that would diverge from the real
    ``quse --json`` output.
    """
    script = target_dir / "quse"
    payload_file = target_dir / "_payload.txt"
    payload_file.write_text(payload)
    body_lines = ["#!/bin/sh"]
    if sleep_secs > 0:
        body_lines.append(f"sleep {sleep_secs}")
    body_lines.append(f"cat {payload_file}")
    script.write_text("\n".join(body_lines) + "\n")
    script.chmod(0o755)
    return script


def test_usage_fetch_round_trip(
    sandbox_socket: Path,
    tmp_path: Path,
) -> None:
    """Daemon's ``usage.fetch`` invokes the fake quse and returns its stdout."""
    fake_bin = tmp_path / "bin"
    fake_bin.mkdir()
    payload = '{"provider":"codex","status":"ok"}\n'
    _write_fake_quse(fake_bin, payload=payload)

    env = {"PATH": f"{fake_bin}:{os.environ.get('PATH', '')}"}
    proc = _spawn_daemon(sandbox_socket, extra_env=env)
    try:
        result = daemon_mod.call(
            "usage.fetch",
            params={},
            socket_path=sandbox_socket,
            timeout=10.0,
        )
        assert isinstance(result, dict)
        assert result["stdout"] == payload
        assert result["returncode"] == 0
    finally:
        _terminate(proc)


def test_two_concurrent_clients_cache_hit_is_faster(
    sandbox_socket: Path,
    tmp_path: Path,
) -> None:
    """Second call within TTL must reuse the cached envelope and be faster.

    The fake quse sleeps 300 ms so the first call's wall-clock is
    dominated by the subprocess. The cache hit returns from the
    daemon's in-memory dict and should land well under 50 ms.
    """
    fake_bin = tmp_path / "bin"
    fake_bin.mkdir()
    payload = '{"provider":"codex","status":"ok"}\n'
    _write_fake_quse(fake_bin, payload=payload, sleep_secs=0.3)

    env = {"PATH": f"{fake_bin}:{os.environ.get('PATH', '')}"}
    proc = _spawn_daemon(sandbox_socket, extra_env=env)
    try:
        # Cold call — runs the (slow) fake quse.
        cold_start = time.monotonic()
        socket_obj = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        socket_obj.settimeout(5.0)
        socket_obj.connect(str(sandbox_socket))
        try:
            daemon_mod.send_json(
                socket_obj,
                {"jsonrpc": "2.0", "id": 1, "method": "usage.fetch", "params": {}},
            )
            cold_response = daemon_mod.recv_json(socket_obj)
        finally:
            socket_obj.close()
        cold_elapsed = time.monotonic() - cold_start

        assert cold_response["result"]["stdout"] == payload
        assert cold_response.get("cached") is False
        # The fake quse sleeps 300 ms; allow generous slack but assert
        # the call did take real time.
        assert cold_elapsed >= 0.25

        # Warm call — cache hit, must skip the subprocess entirely.
        warm_start = time.monotonic()
        socket_obj2 = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        socket_obj2.settimeout(5.0)
        socket_obj2.connect(str(sandbox_socket))
        try:
            daemon_mod.send_json(
                socket_obj2,
                {"jsonrpc": "2.0", "id": 2, "method": "usage.fetch", "params": {}},
            )
            warm_response = daemon_mod.recv_json(socket_obj2)
        finally:
            socket_obj2.close()
        warm_elapsed = time.monotonic() - warm_start

        assert warm_response["result"]["stdout"] == payload
        assert warm_response.get("cached") is True
        # 100 ms ceiling for an in-memory cache hit is very generous
        # — typically lands well under 10 ms — but tolerates a busy CI.
        assert warm_elapsed < 0.1, (
            f"cache hit took {warm_elapsed * 1000:.1f} ms; expected <100 ms"
        )
        # Must be meaningfully faster than the cold call.
        assert warm_elapsed < cold_elapsed * 0.5
    finally:
        _terminate(proc)


def test_no_cache_param_bypasses_cache(
    sandbox_socket: Path,
    tmp_path: Path,
) -> None:
    """``no_cache=True`` forces the daemon to re-run the upstream call."""
    fake_bin = tmp_path / "bin"
    fake_bin.mkdir()
    counter_file = tmp_path / "counter"
    counter_file.write_text("0")
    # Each invocation increments the counter and echoes the new value.
    script = fake_bin / "quse"
    script.write_text(
        "#!/bin/sh\n"
        f"n=$(cat {counter_file})\n"
        "n=$((n+1))\n"
        f'echo "$n" > {counter_file}\n'
        'printf \'{"provider":"x","call":%s}\\n\' "$n"\n'
    )
    script.chmod(0o755)

    env = {"PATH": f"{fake_bin}:{os.environ.get('PATH', '')}"}
    proc = _spawn_daemon(sandbox_socket, extra_env=env)
    try:
        # First call populates the cache.
        first = daemon_mod.call("usage.fetch", socket_path=sandbox_socket)
        assert first["stdout"].strip() == '{"provider":"x","call":1}'

        # Second without no_cache must hit the cache and not increment.
        cached = daemon_mod.call("usage.fetch", socket_path=sandbox_socket)
        assert cached["stdout"].strip() == '{"provider":"x","call":1}'

        # Third with no_cache must re-run upstream — counter increments.
        fresh = daemon_mod.call(
            "usage.fetch",
            params={"no_cache": True},
            socket_path=sandbox_socket,
        )
        assert fresh["stdout"].strip() == '{"provider":"x","call":2}'
    finally:
        _terminate(proc)


# ---------------------------------------------------------------------------
# 4. Stale-socket recovery
# ---------------------------------------------------------------------------


def test_daemon_start_cleans_up_stale_socket(
    sandbox_socket: Path,
    tmp_path: Path,
) -> None:
    """A leftover socket file from a crashed daemon must not block startup.

    Simulates the post-SIGKILL state: a dead socket file lives on
    disk; ``bind()`` would normally fail with EADDRINUSE. The
    daemon's ``_bind`` unlinks-then-binds; this test asserts the
    recovery happens.
    """
    # Touch a stale socket file at the target path.
    sandbox_socket.parent.mkdir(parents=True, exist_ok=True)
    sandbox_socket.touch()
    assert sandbox_socket.exists()
    assert not sandbox_socket.is_socket() if hasattr(Path, "is_socket") else True

    # Daemon should clean it up and start successfully.
    fake_bin = tmp_path / "bin"
    fake_bin.mkdir()
    _write_fake_quse(fake_bin, payload='{"provider":"codex"}\n')
    env = {"PATH": f"{fake_bin}:{os.environ.get('PATH', '')}"}
    proc = _spawn_daemon(sandbox_socket, extra_env=env)
    try:
        assert daemon_mod.is_daemon_running(sandbox_socket)
    finally:
        _terminate(proc)


# ---------------------------------------------------------------------------
# 5. Daemon-crash recovery
# ---------------------------------------------------------------------------


def test_daemon_recovers_after_sigkill(
    sandbox_socket: Path,
    tmp_path: Path,
) -> None:
    """SIGKILL leaves a dead socket; the next ``daemon start`` must succeed."""
    fake_bin = tmp_path / "bin"
    fake_bin.mkdir()
    _write_fake_quse(fake_bin, payload='{"provider":"codex"}\n')
    env = {"PATH": f"{fake_bin}:{os.environ.get('PATH', '')}"}

    proc1 = _spawn_daemon(sandbox_socket, extra_env=env)
    try:
        assert daemon_mod.is_daemon_running(sandbox_socket)
        # SIGKILL — the daemon cannot run its atexit/signal handler.
        proc1.send_signal(signal.SIGKILL)
        proc1.wait(timeout=5.0)
    finally:
        if proc1.poll() is None:
            proc1.kill()

    # The socket file may still be present (or absent depending on
    # kernel cleanup); either way the daemon should NOT be running.
    assert not daemon_mod.is_daemon_running(sandbox_socket)

    # Start a fresh daemon over the stale file; must succeed.
    proc2 = _spawn_daemon(sandbox_socket, extra_env=env)
    try:
        assert daemon_mod.is_daemon_running(sandbox_socket)
        result = daemon_mod.call("daemon.ping", socket_path=sandbox_socket)
        assert result["ok"] is True
        assert result["pid"] == proc2.pid
    finally:
        _terminate(proc2)


# ---------------------------------------------------------------------------
# 6. --no-daemon falls through to subprocess
# ---------------------------------------------------------------------------


def test_no_daemon_flag_skips_daemon_probe(
    sandbox_socket: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """`pocketshell usage --json --no-daemon` must invoke quse directly even
    when a daemon would be available.
    """
    fake_bin = tmp_path / "bin"
    fake_bin.mkdir()
    daemon_payload = '{"from":"daemon"}\n'
    subprocess_payload = '{"from":"subprocess"}\n'
    _write_fake_quse(fake_bin, payload=subprocess_payload)

    # Spin up a daemon that returns a different payload so we can
    # distinguish "daemon answered" vs "subprocess answered".
    daemon_bin = tmp_path / "daemon_bin"
    daemon_bin.mkdir()
    _write_fake_quse(daemon_bin, payload=daemon_payload)
    daemon_env = {"PATH": f"{daemon_bin}:{os.environ.get('PATH', '')}"}
    proc = _spawn_daemon(sandbox_socket, extra_env=daemon_env)
    try:
        # Confirm the daemon is up and would answer the call.
        assert daemon_mod.is_daemon_running(sandbox_socket)

        # Point the CLI's quse lookup at the subprocess_payload script.
        monkeypatch.setattr(
            "pocketshell.usage._resolve_quse_binary",
            lambda: str(fake_bin / "quse"),
        )

        runner = CliRunner()
        result = runner.invoke(
            usage_command,
            ["--json", "--no-daemon"],
            catch_exceptions=False,
        )
        assert result.exit_code == 0, result.output
        # Must be the subprocess payload, NOT the daemon payload.
        assert "subprocess" in result.output
        assert "daemon" not in result.output
    finally:
        _terminate(proc)


def test_usage_uses_daemon_by_default(
    sandbox_socket: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Without ``--no-daemon`` the CLI must dispatch via the daemon."""
    fake_bin = tmp_path / "bin"
    fake_bin.mkdir()
    daemon_payload = '{"from":"daemon"}\n'
    subprocess_payload = '{"from":"subprocess"}\n'
    _write_fake_quse(fake_bin, payload=subprocess_payload)

    daemon_bin = tmp_path / "daemon_bin"
    daemon_bin.mkdir()
    _write_fake_quse(daemon_bin, payload=daemon_payload)
    daemon_env = {"PATH": f"{daemon_bin}:{os.environ.get('PATH', '')}"}
    proc = _spawn_daemon(sandbox_socket, extra_env=daemon_env)
    try:
        monkeypatch.setattr(
            "pocketshell.usage._resolve_quse_binary",
            lambda: str(fake_bin / "quse"),
        )
        runner = CliRunner()
        result = runner.invoke(
            usage_command,
            ["--json"],
            catch_exceptions=False,
        )
        assert result.exit_code == 0, result.output
        # Daemon path should have produced the daemon_payload.
        assert "daemon" in result.output
        assert "subprocess" not in result.output
    finally:
        _terminate(proc)


def test_usage_falls_through_when_daemon_absent(
    sandbox_socket: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """No daemon socket on disk => CLI must use the subprocess path."""
    fake_bin = tmp_path / "bin"
    fake_bin.mkdir()
    payload = '{"from":"subprocess"}\n'
    _write_fake_quse(fake_bin, payload=payload)
    monkeypatch.setattr(
        "pocketshell.usage._resolve_quse_binary",
        lambda: str(fake_bin / "quse"),
    )

    # sandbox_socket fixture sets the env var but never starts a
    # daemon, so the socket file does not exist.
    assert not sandbox_socket.exists()

    runner = CliRunner()
    result = runner.invoke(usage_command, ["--json"], catch_exceptions=False)
    assert result.exit_code == 0, result.output
    assert "subprocess" in result.output


# ---------------------------------------------------------------------------
# 7. Idle-timeout exits the daemon process
# ---------------------------------------------------------------------------


def test_daemon_exits_after_idle_timeout(sandbox_socket: Path) -> None:
    """Daemon with a 1.5 s idle timeout must exit on its own."""
    proc = _spawn_daemon(sandbox_socket, idle_timeout=1.5)
    try:
        # Initially up.
        assert daemon_mod.is_daemon_running(sandbox_socket)
        # Wait past the idle window; the daemon polls every 1 s for
        # the idle check, so we give it generous slack.
        deadline = time.monotonic() + 8.0
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                break
            time.sleep(0.2)
        assert proc.poll() is not None, "daemon did not exit on idle"
        # Socket should have been cleaned up.
        assert not sandbox_socket.exists()
    finally:
        _terminate(proc)


# ---------------------------------------------------------------------------
# 8. CLI daemon group: start / status / stop
# ---------------------------------------------------------------------------


def test_cli_daemon_status_reports_not_running_when_socket_missing(
    sandbox_socket: Path,
) -> None:
    """``pocketshell daemon status`` exits 3 when no daemon is up."""
    runner = CliRunner()
    result = runner.invoke(cli, ["daemon", "status"], catch_exceptions=False)
    assert result.exit_code == 3, result.output
    assert "not running" in result.output


def test_cli_daemon_status_reports_running_when_daemon_up(
    running_daemon: subprocess.Popen,
    sandbox_socket: Path,
) -> None:
    runner = CliRunner()
    result = runner.invoke(cli, ["daemon", "status"], catch_exceptions=False)
    assert result.exit_code == 0, result.output
    assert "running" in result.output
    assert str(sandbox_socket) in result.output


def test_cli_daemon_stop_terminates_running_daemon(
    sandbox_socket: Path,
    tmp_path: Path,
) -> None:
    """``pocketshell daemon stop`` shuts down a running daemon."""
    fake_bin = tmp_path / "bin"
    fake_bin.mkdir()
    _write_fake_quse(fake_bin, payload='{"provider":"codex"}\n')
    env = {"PATH": f"{fake_bin}:{os.environ.get('PATH', '')}"}
    proc = _spawn_daemon(sandbox_socket, extra_env=env)
    try:
        assert daemon_mod.is_daemon_running(sandbox_socket)

        runner = CliRunner()
        result = runner.invoke(cli, ["daemon", "stop"], catch_exceptions=False)
        assert result.exit_code == 0, result.output
        assert "stopped" in result.output

        # Process should exit; poll until it does.
        deadline = time.monotonic() + 5.0
        while time.monotonic() < deadline and proc.poll() is None:
            time.sleep(0.05)
        assert proc.poll() is not None, "daemon process did not exit after stop"
        assert not sandbox_socket.exists()
    finally:
        if proc.poll() is None:
            proc.kill()


def test_cli_daemon_stop_is_idempotent(sandbox_socket: Path) -> None:
    """Stop with no running daemon must succeed with ``not running``."""
    runner = CliRunner()
    result = runner.invoke(cli, ["daemon", "stop"], catch_exceptions=False)
    assert result.exit_code == 0, result.output
    assert "not running" in result.output


def test_cli_daemon_start_lazy_spawns_daemon(
    sandbox_socket: Path,
    tmp_path: Path,
) -> None:
    """``pocketshell daemon start`` forks a detached daemon and reports up."""
    # Use a short idle timeout so the test cleans up even if the
    # explicit stop step fails.
    runner = CliRunner()
    env = dict(os.environ)
    env["POCKETSHELL_DAEMON_SOCKET"] = str(sandbox_socket)
    env["POCKETSHELL_DAEMON_IDLE_SECS"] = "5"
    # Click's CliRunner does not pass env through to spawn_detached's
    # Popen by default (subprocess inherits os.environ). Use
    # monkeypatch-style direct env mutation via a context.
    saved_socket = os.environ.get("POCKETSHELL_DAEMON_SOCKET")
    saved_idle = os.environ.get("POCKETSHELL_DAEMON_IDLE_SECS")
    os.environ["POCKETSHELL_DAEMON_SOCKET"] = str(sandbox_socket)
    os.environ["POCKETSHELL_DAEMON_IDLE_SECS"] = "5"
    try:
        result = runner.invoke(cli, ["daemon", "start"], catch_exceptions=False)
        assert result.exit_code == 0, result.output
        assert "started" in result.output
        assert daemon_mod.is_daemon_running(sandbox_socket)
        # Clean up explicitly so the test does not leave a process.
        daemon_mod.stop_daemon(socket_path=sandbox_socket)
    finally:
        if saved_socket is None:
            os.environ.pop("POCKETSHELL_DAEMON_SOCKET", None)
        else:
            os.environ["POCKETSHELL_DAEMON_SOCKET"] = saved_socket
        if saved_idle is None:
            os.environ.pop("POCKETSHELL_DAEMON_IDLE_SECS", None)
        else:
            os.environ["POCKETSHELL_DAEMON_IDLE_SECS"] = saved_idle


def test_cli_daemon_start_is_idempotent_when_already_running(
    running_daemon: subprocess.Popen,
    sandbox_socket: Path,
) -> None:
    """A second ``daemon start`` must not spawn a duplicate process."""
    runner = CliRunner()
    result = runner.invoke(cli, ["daemon", "start"], catch_exceptions=False)
    assert result.exit_code == 0, result.output
    assert "already running" in result.output


# ---------------------------------------------------------------------------
# 9. JSON-RPC error handling
# ---------------------------------------------------------------------------


def test_unknown_method_returns_method_not_found(
    running_daemon: subprocess.Popen,
    sandbox_socket: Path,
) -> None:
    with pytest.raises(RuntimeError) as excinfo:
        daemon_mod.call("not.a.real.method", socket_path=sandbox_socket)
    assert "-32601" in str(excinfo.value) or "unknown method" in str(excinfo.value)


def test_cache_invalidate_method_drops_only_that_method() -> None:
    """`_Cache.invalidate_method` evicts every key for one method only."""
    cache = daemon_mod._Cache()
    key_a1 = daemon_mod._CacheKey.of("repos.list_local", {"roots": ["/a"]})
    key_a2 = daemon_mod._CacheKey.of("repos.list_local", {"roots": ["/b"]})
    key_b = daemon_mod._CacheKey.of("usage.fetch", {})
    cache.put(key_a1, ["a1"], ttl_secs=999)
    cache.put(key_a2, ["a2"], ttl_secs=999)
    cache.put(key_b, {"stdout": "x"}, ttl_secs=999)

    evicted = cache.invalidate_method("repos.list_local")
    assert evicted == 2
    # Both list_local entries gone; the unrelated method survives.
    assert cache.get(key_a1) is None
    assert cache.get(key_a2) is None
    assert cache.get(key_b) == {"stdout": "x"}
    # Idempotent: invalidating again evicts nothing.
    assert cache.invalidate_method("repos.list_local") == 0


def _dispatch_in_memory(daemon: daemon_mod.Daemon, method: str, params: dict) -> dict:
    """Send one request through ``Daemon._handle_one`` using a socketpair."""
    client, server = socket.socketpair()
    try:
        daemon_mod.send_json(
            client,
            {"jsonrpc": "2.0", "id": 1, "method": method, "params": params},
        )
        daemon._handle_one(server)
        response = daemon_mod.recv_json(client)
        assert isinstance(response, dict)
        return response
    finally:
        client.close()
        server.close()


def test_jobs_mutation_invalidates_jobs_list_cache(tmp_path: Path) -> None:
    """Successful job mutations evict the cached ``jobs.list`` envelope."""
    calls = {"list": 0}

    def list_handler(_params: dict) -> dict:
        calls["list"] += 1
        return {"stdout": f"list-{calls['list']}\n", "stderr": "", "returncode": 0}

    def add_handler(_params: dict) -> dict:
        return {"stdout": "created\n", "stderr": "", "returncode": 0}

    daemon = daemon_mod.Daemon(
        socket_path=tmp_path / "daemon.sock",
        methods={"jobs.list": list_handler, "jobs.add": add_handler},
    )

    first = _dispatch_in_memory(daemon, "jobs.list", {})
    assert first["result"]["stdout"] == "list-1\n"
    assert first["cached"] is False

    cached = _dispatch_in_memory(daemon, "jobs.list", {})
    assert cached["result"]["stdout"] == "list-1\n"
    assert cached["cached"] is True
    assert calls["list"] == 1

    mutation = _dispatch_in_memory(daemon, "jobs.add", {"session_name": "work"})
    assert mutation["result"]["returncode"] == 0

    after = _dispatch_in_memory(daemon, "jobs.list", {})
    assert after["result"]["stdout"] == "list-2\n"
    assert after["cached"] is False
    assert calls["list"] == 2


def test_daemon_registry_includes_sessions_and_jobs_methods() -> None:
    assert "sessions.list" in daemon_mod.DEFAULT_METHODS
    assert "jobs.list" in daemon_mod.DEFAULT_METHODS
    assert "jobs.show" in daemon_mod.DEFAULT_METHODS
    assert "jobs.trigger" in daemon_mod.DEFAULT_METHODS
    assert "jobs.add" in daemon_mod.DEFAULT_METHODS
    assert "jobs.edit" in daemon_mod.DEFAULT_METHODS
    assert "jobs.remove" in daemon_mod.DEFAULT_METHODS
    assert "jobs.status" in daemon_mod.DEFAULT_METHODS
    assert daemon_mod.METHOD_CACHE_INVALIDATIONS["jobs.add"] == ("jobs.list",)
    assert daemon_mod.METHOD_CACHE_INVALIDATIONS["jobs.edit"] == ("jobs.list",)
    assert daemon_mod.METHOD_CACHE_INVALIDATIONS["jobs.remove"] == ("jobs.list",)
    assert daemon_mod.METHOD_CACHE_INVALIDATIONS["jobs.trigger"] == ("jobs.list",)


def test_failed_usage_fetch_is_not_cached(
    sandbox_socket: Path,
    tmp_path: Path,
) -> None:
    """A non-zero return from ``quse`` must not pin a bad result in cache."""
    fake_bin = tmp_path / "bin"
    fake_bin.mkdir()
    counter_file = tmp_path / "counter"
    counter_file.write_text("0")
    script = fake_bin / "quse"
    script.write_text(
        "#!/bin/sh\n"
        f"n=$(cat {counter_file})\n"
        "n=$((n+1))\n"
        f'echo "$n" > {counter_file}\n'
        "echo error >&2\n"
        "exit 2\n"
    )
    script.chmod(0o755)
    env = {"PATH": f"{fake_bin}:{os.environ.get('PATH', '')}"}
    proc = _spawn_daemon(sandbox_socket, extra_env=env)
    try:
        first = daemon_mod.call("usage.fetch", socket_path=sandbox_socket)
        assert first["returncode"] == 2
        second = daemon_mod.call("usage.fetch", socket_path=sandbox_socket)
        # Counter must have incremented => no cache reuse.
        assert counter_file.read_text().strip() == "2"
        assert second["returncode"] == 2
    finally:
        _terminate(proc)
