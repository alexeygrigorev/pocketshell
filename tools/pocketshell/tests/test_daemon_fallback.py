"""Load-bearing tests for typed daemon fallback classification (#2304).

The compatibility fixture below behaves like a long-lived older daemon: it
answers ``daemon.ping`` but does not know newer methods. The wrapper tests use
that real Unix-socket path so method-not-found cannot accidentally be treated
like a missing socket. The transport tests use a tiny socket double because a
real timeout would make the suite slow and timing-sensitive.
"""

from __future__ import annotations

import logging
import os
import socket
import threading
from pathlib import Path
from typing import Iterator

import pytest
from click.testing import CliRunner

from pocketshell import daemon as daemon_mod
from pocketshell.agents_kind import agents_group as _agents_group
from pocketshell.cli import cli
from pocketshell.cgroup_agents import DEFAULT_CGROUP_MOUNT, DEFAULT_PROC_ROOT


class _FakeSocket:
    """Enough of a socket for the framed client tests."""

    def settimeout(self, _timeout: float) -> None:
        return None

    def close(self) -> None:
        return None


@pytest.fixture()
def old_daemon_socket(tmp_path: Path) -> Iterator[Path]:
    """Serve an explicit old-daemon compatibility/skew fixture.

    Version ``0.3.9`` is intentionally older than the current package and
    supports only the lifecycle ping. Any application method receives the
    JSON-RPC method-not-found response that an old daemon would produce.
    """
    socket_path = tmp_path / "old-daemon.sock"
    server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    server.bind(str(socket_path))
    server.listen(4)
    socket_path.chmod(0o600)
    stopping = threading.Event()

    def serve() -> None:
        server.settimeout(0.05)
        while not stopping.is_set():
            try:
                client, _ = server.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            try:
                request = daemon_mod.recv_json(client)
                method = request.get("method") if isinstance(request, dict) else None
                request_id = request.get("id") if isinstance(request, dict) else None
                if method == "daemon.ping":
                    response = {
                        "jsonrpc": "2.0",
                        "id": request_id,
                        "result": {"ok": True, "pid": os.getpid()},
                    }
                else:
                    response = {
                        "jsonrpc": "2.0",
                        "id": request_id,
                        "error": {
                            "code": daemon_mod.JSONRPC_METHOD_NOT_FOUND,
                            "message": "unknown method",
                            "data": {
                                "failure_reason": "supported_skew",
                                "daemon_version": "0.3.9",
                                "client_version": (
                                    request.get("client_version")
                                    if isinstance(request, dict)
                                    else None
                                ),
                            },
                        },
                    }
                daemon_mod.send_json(client, response)
            finally:
                client.close()

    thread = threading.Thread(target=serve, name="old-daemon-fixture", daemon=True)
    thread.start()
    try:
        yield socket_path
    finally:
        stopping.set()
        server.close()
        thread.join(timeout=1.0)
        try:
            socket_path.unlink()
        except FileNotFoundError:
            pass


def test_absent_socket_is_the_only_normal_unavailable_fallback(
    tmp_path: Path, caplog: pytest.LogCaptureFixture
) -> None:
    missing = tmp_path / "does-not-exist.sock"
    with caplog.at_level(logging.INFO, logger="pocketshell.daemon"):
        outcome = daemon_mod.call_outcome(
            "tree.get",
            params={"host": "prompt=DO_NOT_LOG"},
            socket_path=missing,
        )

    assert outcome.failure is not None
    assert outcome.failure.reason is daemon_mod.DaemonFailureReason.ABSENT_OR_UNAVAILABLE
    assert outcome.failure.fallback_allowed
    assert daemon_mod.try_call("tree.get", socket_path=missing) is None
    assert "DO_NOT_LOG" not in caplog.text
    assert "absent_or_unavailable" in caplog.text


def test_old_daemon_method_not_found_is_supported_skew_with_versions(
    old_daemon_socket: Path, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    monkeypatch.setenv("POCKETSHELL_DAEMON_SOCKET", str(old_daemon_socket))
    with caplog.at_level(logging.INFO, logger="pocketshell.daemon"):
        outcome = daemon_mod.call_outcome(
            "tree.get",
            params={"host": "prompt=DO_NOT_LOG"},
            socket_path=old_daemon_socket,
        )

    assert outcome.failure is not None
    failure = outcome.failure
    assert failure.reason is daemon_mod.DaemonFailureReason.SUPPORTED_SKEW
    assert failure.fallback_allowed
    assert failure.daemon_version == "0.3.9"
    assert failure.cli_version == daemon_mod._installed_cli_version()
    assert daemon_mod.try_call("tree.get", socket_path=old_daemon_socket) is None
    assert "DO_NOT_LOG" not in caplog.text
    assert "supported_skew" in caplog.text
    assert "daemon_version=0.3.9" in str(failure.user_message())


@pytest.mark.parametrize(
    "wrapper", ["tree", "jobs", "sessions", "agent-kind", "usage", "repos"]
)
def test_affected_wrappers_share_supported_skew_fallback(
    wrapper: str,
    old_daemon_socket: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Every affected wrapper keeps the explicitly supported old-daemon path."""
    monkeypatch.setenv("POCKETSHELL_DAEMON_SOCKET", str(old_daemon_socket))

    if wrapper == "tree":
        from pocketshell import tree

        result = tree._try_daemon_call("tree.get", {"host": "h1"})
    elif wrapper == "jobs":
        from pocketshell import jobs

        result = jobs._try_daemon_jobs_call("jobs.list", {})
    elif wrapper == "sessions":
        from pocketshell import sessions

        result = sessions._try_daemon_sessions_list(sort_by=None, extra_args=[])
    elif wrapper == "agent-kind":
        from pocketshell import agents_kind

        result = agents_kind._try_daemon_call(
            [],
            proc_root=DEFAULT_PROC_ROOT,
            cgroup_mount=DEFAULT_CGROUP_MOUNT,
        )
    elif wrapper == "usage":
        from pocketshell import usage

        result = usage._try_daemon_usage_fetch(None, no_cache=False)
    else:
        from pocketshell import repos

        result = repos._try_daemon_call("repos.list_local", {})

    assert result is None


def test_tree_command_runs_local_fallback_for_old_daemon(
    old_daemon_socket: Path,
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    """The skew sentinel reaches the documented local tree implementation."""
    monkeypatch.setenv("POCKETSHELL_DAEMON_SOCKET", str(old_daemon_socket))
    monkeypatch.setenv("XDG_STATE_HOME", str(tmp_path / "state"))
    result = CliRunner().invoke(cli, ["tree", "get"], input='{"host":"h1"}')

    assert result.exit_code == 0, result.output
    assert '"nodes": []' in result.output


def test_transport_timeout_is_typed_and_never_falls_back(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    socket_path = tmp_path / "connected.sock"
    socket_path.touch()
    monkeypatch.setattr(
        daemon_mod,
        "_connect",
        lambda _path, timeout: _FakeSocket(),
    )
    monkeypatch.setattr(daemon_mod, "send_json", lambda *_args: None)
    monkeypatch.setattr(
        daemon_mod,
        "recv_json",
        lambda _sock: (_ for _ in ()).throw(socket.timeout()),
    )

    with caplog.at_level(logging.INFO, logger="pocketshell.daemon"):
        with pytest.raises(daemon_mod.DaemonClientError) as exc_info:
            daemon_mod.try_call(
                "jobs.add",
                params={"message": "prompt=DO_NOT_LOG"},
                socket_path=socket_path,
                timeout=0.01,
            )

    failure = exc_info.value.failure
    assert failure.reason is daemon_mod.DaemonFailureReason.TRANSPORT_TIMEOUT
    assert not failure.fallback_allowed
    assert "DO_NOT_LOG" not in str(exc_info.value)
    assert "DO_NOT_LOG" not in caplog.text
    assert "transport_timeout" in caplog.text


def test_daemon_internal_error_is_typed_and_does_not_echo_rpc_payload(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    socket_path = tmp_path / "internal.sock"
    socket_path.touch()
    monkeypatch.setattr(
        daemon_mod,
        "_connect",
        lambda _path, timeout: _FakeSocket(),
    )
    monkeypatch.setattr(daemon_mod, "send_json", lambda *_args: None)
    monkeypatch.setattr(
        daemon_mod,
        "recv_json",
        lambda _sock: {
            "jsonrpc": "2.0",
            "id": 1,
            "error": {
                "code": daemon_mod.JSONRPC_INTERNAL_ERROR,
                "message": "secret prompt=DO_NOT_LOG token=DO_NOT_LOG",
                "data": {"daemon_version": "0.4.44"},
            },
        },
    )

    with caplog.at_level(logging.INFO, logger="pocketshell.daemon"):
        with pytest.raises(daemon_mod.DaemonClientError) as exc_info:
            daemon_mod.try_call(
                "tree.upsert",
                params={"nodes": [{"session": "prompt=DO_NOT_LOG"}]},
                socket_path=socket_path,
            )

    failure = exc_info.value.failure
    assert failure.reason is daemon_mod.DaemonFailureReason.DAEMON_INTERNAL_ERROR
    assert not failure.fallback_allowed
    assert failure.daemon_version == "0.4.44"
    assert "DO_NOT_LOG" not in str(exc_info.value)
    assert "DO_NOT_LOG" not in caplog.text
    assert "daemon_internal_error" in caplog.text


def test_invalid_success_shape_is_daemon_internal_not_local_fallback(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    socket_path = tmp_path / "wrong-shape.sock"
    socket_path.touch()
    monkeypatch.setenv("POCKETSHELL_DAEMON_SOCKET", str(socket_path))
    monkeypatch.setattr(
        daemon_mod,
        "_connect",
        lambda _path, timeout: _FakeSocket(),
    )
    monkeypatch.setattr(daemon_mod, "send_json", lambda *_args: None)
    monkeypatch.setattr(
        daemon_mod,
        "recv_json",
        lambda _sock: {
            "jsonrpc": "2.0",
            "id": 1,
            "result": {"not": "a command envelope"},
        },
    )

    with pytest.raises(daemon_mod.DaemonClientError) as exc_info:
        from pocketshell import tree

        tree._try_daemon_call("tree.get", {})

    assert exc_info.value.failure.reason is daemon_mod.DaemonFailureReason.DAEMON_INTERNAL_ERROR
    assert exc_info.value.failure.phase == "validate"


def test_fallback_policy_has_exactly_the_two_safe_reasons() -> None:
    assert daemon_mod.LOCAL_FALLBACK_REASONS == frozenset(
        {
            daemon_mod.DaemonFailureReason.ABSENT_OR_UNAVAILABLE,
            daemon_mod.DaemonFailureReason.SUPPORTED_SKEW,
        }
    )


def test_imported_agents_group_is_still_the_registered_cli_group() -> None:
    # Keep this module's import explicit: the affected agent-kind wrapper must
    # remain on the same root CLI path while its daemon seam changes.
    assert _agents_group.name == "agents"
