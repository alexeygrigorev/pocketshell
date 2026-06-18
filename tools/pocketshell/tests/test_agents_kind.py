"""Tests for the ``pocketshell agents kind`` CLI subcommand (epic #821 A2-infra).

The subcommand is the missing CLI seam over the daemon RPC
``agents.kind_for_panes`` (``cgroup_agents.kind_for_panes``): the PocketShell
Android client only ever execs ``pocketshell <subcommand>`` over its warm SSH
session — it never speaks JSON-RPC — so the cgroup/scope agent-kind detection
needs a subcommand to reach it. ``pocketshell agents kind`` accepts a pane list
(matching the RPC's ``{pane_id, pane_pid}`` shape), classifies each pane, and
emits the RPC's ``{"results": [...]}`` envelope as stable JSON on stdout.

These tests point the classifier at a synthetic ``/proc`` + cgroup-v2 tree
(reusing the same fake-host shape as ``test_cgroup_agents``) via the injectable
``--proc-root`` / ``--cgroup-mount`` options, so they exercise the real
classifier with zero live sessions, covering the claude / codex / opencode /
none / unknown / empty cases.
"""

from __future__ import annotations

import json
import os
import signal
import subprocess
import sys
import time
from pathlib import Path
from typing import Iterator, Optional

import pytest
from click.testing import CliRunner

from pocketshell import daemon as daemon_mod
from pocketshell.cli import cli


# ---------------------------------------------------------------------------
# Synthetic-filesystem fixture builder (mirrors test_cgroup_agents._FakeHost)
# ---------------------------------------------------------------------------


class _FakeHost:
    """Builds a synthetic ``/proc`` + cgroup-v2 tree under ``tmp_path``."""

    ROBUST_PREFIX = "/user.slice/user-1000.slice/user@1000.service/robust.slice"

    def __init__(self, root: Path) -> None:
        self.proc_root = root / "proc"
        self.cgroup_mount = root / "cgroup"
        self.proc_root.mkdir(parents=True, exist_ok=True)
        self.cgroup_mount.mkdir(parents=True, exist_ok=True)

    def _scope_relpath(self, scope: str) -> str:
        return f"{self.ROBUST_PREFIX}/{scope}"

    def add_proc(
        self,
        pid: int,
        *,
        scope: Optional[str],
        comm: str,
        cmdline: str,
    ) -> None:
        proc_dir = self.proc_root / str(pid)
        proc_dir.mkdir(parents=True, exist_ok=True)
        if scope is None:
            rel = "/user.slice/user-1000.slice/session-3.scope"
        else:
            rel = self._scope_relpath(scope)
        (proc_dir / "cgroup").write_text(f"0::{rel}\n", encoding="utf-8")
        (proc_dir / "comm").write_text(comm + "\n", encoding="utf-8")
        (proc_dir / "cmdline").write_text(
            cmdline.replace(" ", "\0") + "\0", encoding="utf-8"
        )

    def add_scope(self, scope: str, pids: list[int]) -> None:
        scope_dir = self.cgroup_mount / self._scope_relpath(scope).lstrip("/")
        scope_dir.mkdir(parents=True, exist_ok=True)
        (scope_dir / "cgroup.procs").write_text(
            "".join(f"{p}\n" for p in pids), encoding="utf-8"
        )


def _seed_claude(host: _FakeHost) -> None:
    host.add_proc(
        1001, scope="tmuxctl-claude-main.scope", comm="bash", cmdline="-bash"
    )
    host.add_proc(
        1002,
        scope="tmuxctl-claude-main.scope",
        comm="claude",
        cmdline="claude",
    )
    host.add_scope("tmuxctl-claude-main.scope", [1001, 1002])


def _seed_codex(host: _FakeHost) -> None:
    host.add_proc(
        2001, scope="tmuxctl-codex.scope", comm="bash", cmdline="-bash"
    )
    host.add_proc(
        2002,
        scope="tmuxctl-codex.scope",
        comm="MainThread",
        cmdline="node /usr/lib/node_modules/codex/bin/codex.js",
    )
    host.add_scope("tmuxctl-codex.scope", [2001, 2002])


def _seed_opencode(host: _FakeHost) -> None:
    host.add_proc(
        3001, scope="tmuxctl-opencode-lab.scope", comm="bash", cmdline="-bash"
    )
    host.add_proc(
        3002,
        scope="tmuxctl-opencode-lab.scope",
        comm="opencode",
        cmdline="opencode",
    )
    host.add_scope("tmuxctl-opencode-lab.scope", [3001, 3002])


def _seed_plain_shell(host: _FakeHost) -> None:
    """A pane that resolves to a scope but runs no agent -> ``none``."""
    host.add_proc(
        4001, scope="tmuxctl-shell.scope", comm="bash", cmdline="-bash"
    )
    host.add_scope("tmuxctl-shell.scope", [4001])


def _invoke(panes: list[dict], host: _FakeHost) -> dict:
    runner = CliRunner()
    result = runner.invoke(
        cli,
        [
            "agents",
            "kind",
            "--proc-root",
            str(host.proc_root),
            "--cgroup-mount",
            str(host.cgroup_mount),
        ],
        input=json.dumps({"panes": panes}),
    )
    assert result.exit_code == 0, result.output
    return json.loads(result.output)


def test_help_lists_the_kind_subcommand() -> None:
    runner = CliRunner()
    result = runner.invoke(cli, ["agents", "kind", "--help"])
    assert result.exit_code == 0, result.output
    assert "kind" in result.output.lower()


def test_classifies_claude(tmp_path: Path) -> None:
    host = _FakeHost(tmp_path)
    _seed_claude(host)
    out = _invoke([{"pane_id": "%1", "pane_pid": 1001}], host)
    results = out["results"]
    assert len(results) == 1
    assert results[0]["pane_id"] == "%1"
    assert results[0]["agent_kind"] == "claude"
    assert results[0]["scope"] == "tmuxctl-claude-main.scope"
    assert results[0]["evidence_pid"] == 1002


def test_classifies_codex_node_wrapped(tmp_path: Path) -> None:
    host = _FakeHost(tmp_path)
    _seed_codex(host)
    out = _invoke([{"pane_id": "%2", "pane_pid": 2001}], host)
    assert out["results"][0]["agent_kind"] == "codex"
    assert out["results"][0]["scope"] == "tmuxctl-codex.scope"


def test_classifies_opencode(tmp_path: Path) -> None:
    host = _FakeHost(tmp_path)
    _seed_opencode(host)
    out = _invoke([{"pane_id": "%3", "pane_pid": 3001}], host)
    assert out["results"][0]["agent_kind"] == "opencode"


def test_plain_shell_resolves_to_none(tmp_path: Path) -> None:
    host = _FakeHost(tmp_path)
    _seed_plain_shell(host)
    out = _invoke([{"pane_id": "%4", "pane_pid": 4001}], host)
    assert out["results"][0]["agent_kind"] == "none"
    assert out["results"][0]["scope"] == "tmuxctl-shell.scope"


def test_unreadable_pane_resolves_to_unknown(tmp_path: Path) -> None:
    host = _FakeHost(tmp_path)
    # pid 9999 has no /proc entry -> cgroup unreadable -> unknown.
    out = _invoke([{"pane_id": "%9", "pane_pid": 9999}], host)
    assert out["results"][0]["agent_kind"] == "unknown"


def test_missing_pane_pid_is_unknown_not_error(tmp_path: Path) -> None:
    host = _FakeHost(tmp_path)
    out = _invoke([{"pane_id": "%5"}], host)
    assert out["results"][0]["agent_kind"] == "unknown"
    assert out["results"][0]["pane_id"] == "%5"


def test_empty_pane_list_returns_empty_results(tmp_path: Path) -> None:
    host = _FakeHost(tmp_path)
    out = _invoke([], host)
    assert out == {"results": []}


def test_no_stdin_no_panes_returns_empty_results() -> None:
    runner = CliRunner()
    result = runner.invoke(cli, ["agents", "kind"], input="")
    assert result.exit_code == 0, result.output
    assert json.loads(result.output) == {"results": []}


def test_mixed_batch_one_bad_pane_does_not_sink_others(tmp_path: Path) -> None:
    host = _FakeHost(tmp_path)
    _seed_claude(host)
    _seed_codex(host)
    out = _invoke(
        [
            {"pane_id": "%1", "pane_pid": 1001},
            {"pane_id": "%bad", "pane_pid": "not-an-int"},
            {"pane_id": "%2", "pane_pid": 2001},
        ],
        host,
    )
    kinds = {r["pane_id"]: r["agent_kind"] for r in out["results"]}
    assert kinds["%1"] == "claude"
    assert kinds["%bad"] == "unknown"
    assert kinds["%2"] == "codex"


# ---------------------------------------------------------------------------
# CLI -> daemon round-trip (proves the daemon RPC path, not just in-process)
# ---------------------------------------------------------------------------


def _spawn_daemon(socket_path: Path) -> subprocess.Popen:
    env = dict(os.environ)
    env["POCKETSHELL_DAEMON_SOCKET"] = str(socket_path)
    env["POCKETSHELL_DAEMON_IDLE_SECS"] = "120"
    proc = subprocess.Popen(
        [sys.executable, "-m", "pocketshell", "daemon", "_serve"],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
        start_new_session=True,
    )
    assert daemon_mod.wait_until_ready(socket_path=socket_path, deadline=10.0), (
        proc.stderr.read().decode(errors="replace") if proc.stderr else ""
    )
    return proc


@pytest.fixture()
def running_daemon(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> Iterator[Path]:
    socket_path = tmp_path / "ps-agents-kind.sock"
    monkeypatch.setenv("POCKETSHELL_DAEMON_SOCKET", str(socket_path))
    proc = _spawn_daemon(socket_path)
    try:
        yield socket_path
    finally:
        if proc.poll() is None:
            proc.send_signal(signal.SIGTERM)
            try:
                proc.wait(timeout=5.0)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=2.0)
        for path in (socket_path, socket_path.with_suffix(".pid")):
            try:
                path.unlink()
            except FileNotFoundError:
                pass


def test_cli_reaches_daemon_rpc(
    running_daemon: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """When a daemon is up on the default roots, the CLI dispatches to the
    ``agents.kind_for_panes`` RPC instead of computing in-process.

    Proven by sabotaging the in-process classifier *in the CLI process* so a
    well-formed envelope can only have come from the daemon subprocess (a
    separate process whose classifier is intact).
    """
    def _boom(*_a, **_k):
        raise AssertionError("in-process classifier must not run when daemon up")

    monkeypatch.setattr(
        "pocketshell.cgroup_agents.kind_for_panes", _boom
    )
    # The daemon classifies against the live host; pid 1 always exists, so the
    # envelope is well-formed regardless of what scope it resolves to.
    runner = CliRunner()
    result = runner.invoke(
        cli, ["agents", "kind", "--pane", "%live=1"]
    )
    assert result.exit_code == 0, result.output
    out = json.loads(result.output)
    assert out["results"][0]["pane_id"] == "%live"
    assert "agent_kind" in out["results"][0]


def test_pane_option_form(tmp_path: Path) -> None:
    """The ``--pane PANE_ID=PANE_PID`` repeatable form is an ergonomic
    alternative to stdin JSON for a one-shot SSH exec."""
    host = _FakeHost(tmp_path)
    _seed_claude(host)
    runner = CliRunner()
    result = runner.invoke(
        cli,
        [
            "agents",
            "kind",
            "--proc-root",
            str(host.proc_root),
            "--cgroup-mount",
            str(host.cgroup_mount),
            "--pane",
            "%1=1001",
        ],
    )
    assert result.exit_code == 0, result.output
    out = json.loads(result.output)
    assert out["results"][0]["pane_id"] == "%1"
    assert out["results"][0]["agent_kind"] == "claude"
