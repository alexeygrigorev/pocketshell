"""Tests for cgroup/scope-based agent classification (issue #809 / #811).

The classifier reads a cgroup-v2 ``/sys/fs/cgroup`` tree plus ``/proc`` to map
each pane's ``pane_pid`` to its ``tmuxctl-<session>.scope`` and classify the
agent process running inside. These tests build a **synthetic** ``/proc``-like
and cgroupfs-like tree on ``tmp_path`` and point the classifier at it via the
injectable ``proc_root`` / ``cgroup_mount`` roots, so nothing depends on a live
tmux/agent session — they reproduce the real on-box layout proven in the #811
design spike (claude as ``comm=claude``; codex node-wrapped as
``comm=MainThread`` + ``cmdline=node …/codex``; etc.).
"""

from __future__ import annotations

from pathlib import Path
from typing import Optional

import pytest

from pocketshell import cgroup_agents
from pocketshell.cgroup_agents import (
    AGENT_CLAUDE,
    AGENT_CODEX,
    AGENT_NONE,
    AGENT_OPENCODE,
    AGENT_UNKNOWN,
    classify_token,
)


# ---------------------------------------------------------------------------
# Synthetic-filesystem fixture builder
# ---------------------------------------------------------------------------


class _FakeHost:
    """Builds a synthetic ``/proc`` + cgroup-v2 tree under ``tmp_path``.

    Mirrors the real on-box layout: a pane shell pid lives directly inside its
    ``…/robust.slice/<scope>``; the scope's ``cgroup.procs`` lists every pid in
    the scope; each pid has a ``/proc/<pid>/comm`` and ``/proc/<pid>/cmdline``.
    """

    # The relative cgroup path prefix every scope lives under, matching the
    # real `/proc/<pid>/cgroup` `0::` line on this box.
    ROBUST_PREFIX = (
        "/user.slice/user-1000.slice/user@1000.service/robust.slice"
    )

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
        """Create a synthetic ``/proc/<pid>`` whose cgroup line names ``scope``.

        When ``scope`` is ``None`` the cgroup line points at a bare login path
        with no tmuxctl/spawn scope (the non-tmuxctl session case).
        """
        proc_dir = self.proc_root / str(pid)
        proc_dir.mkdir(parents=True, exist_ok=True)
        (proc_dir / "comm").write_text(comm + "\n", encoding="utf-8")
        # cmdline is NUL-separated with a trailing NUL on real /proc.
        cmdline_bytes = b"\x00".join(
            part.encode("utf-8") for part in cmdline.split(" ")
        )
        if cmdline_bytes:
            cmdline_bytes += b"\x00"
        (proc_dir / "cmdline").write_bytes(cmdline_bytes)

        if scope is None:
            relpath = f"{self.ROBUST_PREFIX}/../user-1000.slice/session-1.scope"
        else:
            relpath = self._scope_relpath(scope)
        (proc_dir / "cgroup").write_text(f"0::{relpath}\n", encoding="utf-8")

    def set_scope_procs(self, scope: str, pids: list[int]) -> None:
        """Write ``cgroup.procs`` for a scope listing the given pids."""
        scope_dir = (
            self.cgroup_mount
            / self.ROBUST_PREFIX.lstrip("/")
            / scope
        )
        scope_dir.mkdir(parents=True, exist_ok=True)
        body = "".join(f"{pid}\n" for pid in pids)
        (scope_dir / "cgroup.procs").write_text(body, encoding="utf-8")


@pytest.fixture()
def host(tmp_path: Path) -> _FakeHost:
    return _FakeHost(tmp_path)


def _classify(host: _FakeHost, pane_pid: int, pane_id: str = "%1"):
    return cgroup_agents.kind_for_pane(
        pane_pid,
        pane_id=pane_id,
        proc_root=str(host.proc_root),
        cgroup_mount=str(host.cgroup_mount),
    )


# ---------------------------------------------------------------------------
# classify_token — the pure token-rule mirror of AgentDetector.namesAgent
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("claude", AGENT_CLAUDE),
        ("claude --dangerously-skip-permissions", AGENT_CLAUDE),
        ("claude-code", AGENT_CLAUDE),
        ("codex", AGENT_CODEX),
        ("/home/alexey/.nvm/versions/node/v24/bin/codex --foo", AGENT_CODEX),
        ("node /home/alexey/.nvm/.../codex --bypass", AGENT_CODEX),
        ("opencode", AGENT_OPENCODE),
        ("open-code", AGENT_OPENCODE),
        ("opencode-tui", AGENT_OPENCODE),
        # Plain shells / unrelated processes name no agent.
        ("bash", None),
        ("/bin/bash -l", None),
        ("node /some/other/app.js", None),
        # `codex` as a whole command word (space-delimited) matches; the
        # `codexicon` substring case is covered separately below.
        ("git commit && codex", AGENT_CODEX),
        ("", None),
    ],
)
def test_classify_token(text: str, expected: Optional[str]) -> None:
    assert classify_token(text) == expected


def test_classify_token_substring_does_not_false_positive() -> None:
    # `codexedit` is one token, not a `codex` command word — the boundary
    # guard (mirroring containsCommandToken) must NOT match it.
    assert classify_token("codexedit") is None
    assert classify_token("myclaudeproxy") is None


# ---------------------------------------------------------------------------
# kind_for_pane — end-to-end pane_pid → scope → classify
# ---------------------------------------------------------------------------


def test_claude_scope(host: _FakeHost) -> None:
    """A scope with a bare `comm=claude` shell child classifies as claude."""
    scope = "tmuxctl-git-pocketshell.scope"
    host.add_proc(2647034, scope=scope, comm="bash", cmdline="/bin/bash -l")
    host.add_proc(
        2647069,
        scope=scope,
        comm="claude",
        cmdline="claude --dangerously-skip-permissions",
    )
    host.set_scope_procs(scope, [2647034, 2647069])

    result = _classify(host, 2647034)
    assert result.agent_kind == AGENT_CLAUDE
    assert result.scope == scope
    assert result.evidence_pid == 2647069


def test_codex_node_wrapped_scope(host: _FakeHost) -> None:
    """Node-wrapped codex: comm is `MainThread`, cmdline carries node …/codex.

    This is the exact live layout proven in the #811 spike for
    `tmuxctl-git-3d-models.scope`.
    """
    scope = "tmuxctl-git-3d-models.scope"
    host.add_proc(756261, scope=scope, comm="bash", cmdline="/bin/bash -l")
    host.add_proc(
        756501,
        scope=scope,
        comm="MainThread",
        cmdline=(
            "node /home/alexey/.nvm/versions/node/v24.13.1/bin/codex "
            "--dangerously-bypass-approvals-and-sandbox"
        ),
    )
    host.add_proc(
        756656,
        scope=scope,
        comm="codex",
        cmdline="/home/alexey/.../@openai/codex/node_modules/...",
    )
    host.set_scope_procs(scope, [756261, 756501, 756656])

    result = _classify(host, 756261)
    assert result.agent_kind == AGENT_CODEX
    assert result.scope == scope
    # The node-wrapper MainThread pid is enumerated before the bare codex pid,
    # so its cmdline is the evidence.
    assert result.evidence_pid == 756501


def test_opencode_scope(host: _FakeHost) -> None:
    scope = "tmuxctl-git-faq-assistant.scope"
    host.add_proc(900001, scope=scope, comm="bash", cmdline="/bin/bash -l")
    host.add_proc(
        900002, scope=scope, comm="opencode", cmdline="opencode --foo"
    )
    host.set_scope_procs(scope, [900001, 900002])

    result = _classify(host, 900001)
    assert result.agent_kind == AGENT_OPENCODE
    assert result.scope == scope
    assert result.evidence_pid == 900002


def test_plain_shell_scope_is_none(host: _FakeHost) -> None:
    """A scope with only a plain shell (no agent) classifies as `none`."""
    scope = "tmuxctl-git-datamailer.scope"
    host.add_proc(800001, scope=scope, comm="bash", cmdline="/bin/bash -l")
    host.add_proc(800002, scope=scope, comm="vim", cmdline="vim notes.txt")
    host.set_scope_procs(scope, [800001, 800002])

    result = _classify(host, 800001)
    assert result.agent_kind == AGENT_NONE
    assert result.scope == scope
    assert result.evidence_pid is None


def test_missing_pid_is_unknown(host: _FakeHost) -> None:
    """A pane_pid with no /proc entry (vanished) is `unknown`, not a crash."""
    result = _classify(host, 4242424)
    assert result.agent_kind == AGENT_UNKNOWN
    assert result.scope is None
    assert result.evidence_pid is None


def test_no_tmuxctl_scope_is_none_with_null_scope(host: _FakeHost) -> None:
    """A non-tmuxctl pane (login scope) reports scope=None.

    The pid resolves (cgroup readable) but there is no tmuxctl/spawn scope, so
    we cannot read a scope's cgroup.procs — degrade to `unknown` rather than
    pretending we proved "no agent".
    """
    host.add_proc(700001, scope=None, comm="bash", cmdline="/bin/bash -l")
    result = _classify(host, 700001)
    # No scope basename, and the login-scope cgroup.procs path does not exist
    # in our synthetic tree → unknown.
    assert result.scope is None
    assert result.agent_kind == AGENT_UNKNOWN


def test_scope_procs_missing_is_unknown(host: _FakeHost) -> None:
    """Pane cgroup resolves to a scope, but the scope's cgroup.procs is gone.

    Simulates the session ending between the pane read and the procs read:
    the scope basename is still reported, but agent_kind is `unknown`.
    """
    scope = "tmuxctl-git-ended.scope"
    host.add_proc(810001, scope=scope, comm="bash", cmdline="/bin/bash -l")
    # Deliberately do NOT call set_scope_procs — no cgroup.procs file.
    result = _classify(host, 810001)
    assert result.scope == scope
    assert result.agent_kind == AGENT_UNKNOWN


def test_proc_vanishes_between_enumeration_and_read(host: _FakeHost) -> None:
    """A pid listed in cgroup.procs but with no /proc entry is skipped.

    The classifier must not crash when a pid races away after the
    cgroup.procs read; it skips it and still classifies the survivors.
    """
    scope = "tmuxctl-git-race.scope"
    host.add_proc(820001, scope=scope, comm="bash", cmdline="/bin/bash -l")
    host.add_proc(
        820003, scope=scope, comm="claude", cmdline="claude --skip"
    )
    # 820002 is listed but never created → simulates a vanished pid.
    host.set_scope_procs(scope, [820001, 820002, 820003])

    result = _classify(host, 820001)
    assert result.agent_kind == AGENT_CLAUDE
    assert result.evidence_pid == 820003


# ---------------------------------------------------------------------------
# kind_for_panes — the batch entry the RPC calls
# ---------------------------------------------------------------------------


def test_batch_classifies_each_pane_and_isolates_failures(
    host: _FakeHost,
) -> None:
    """One bad pane (invalid pane_pid) must not sink the rest of the batch."""
    claude_scope = "tmuxctl-git-pocketshell.scope"
    host.add_proc(100, scope=claude_scope, comm="bash", cmdline="/bin/bash")
    host.add_proc(101, scope=claude_scope, comm="claude", cmdline="claude")
    host.set_scope_procs(claude_scope, [100, 101])

    shell_scope = "tmuxctl-git-plain.scope"
    host.add_proc(200, scope=shell_scope, comm="bash", cmdline="/bin/bash")
    host.set_scope_procs(shell_scope, [200])

    panes = [
        {"pane_id": "%1", "pane_pid": 100},
        {"pane_id": "%2", "pane_pid": 200},
        {"pane_id": "%3", "pane_pid": "not-an-int"},  # bad pane
        {"pane_id": "%4"},  # missing pane_pid entirely
    ]
    results = cgroup_agents.kind_for_panes(
        panes,
        proc_root=str(host.proc_root),
        cgroup_mount=str(host.cgroup_mount),
    )

    by_id = {r["pane_id"]: r for r in results}
    assert by_id["%1"]["agent_kind"] == AGENT_CLAUDE
    assert by_id["%1"]["scope"] == claude_scope
    assert by_id["%1"]["evidence_pid"] == 101
    assert by_id["%2"]["agent_kind"] == AGENT_NONE
    assert by_id["%2"]["scope"] == shell_scope
    # Bad / missing panes degrade to unknown, never raise.
    assert by_id["%3"]["agent_kind"] == AGENT_UNKNOWN
    assert by_id["%3"]["scope"] is None
    assert by_id["%4"]["agent_kind"] == AGENT_UNKNOWN


def test_batch_pane_id_passthrough_and_correlation(host: _FakeHost) -> None:
    """pane_id is passed through verbatim so the client can correlate."""
    scope = "tmuxctl-git-pocketshell.scope"
    host.add_proc(300, scope=scope, comm="bash", cmdline="/bin/bash")
    host.add_proc(301, scope=scope, comm="claude", cmdline="claude")
    host.set_scope_procs(scope, [300, 301])

    results = cgroup_agents.kind_for_panes(
        [{"pane_id": "%99", "pane_pid": 300}],
        proc_root=str(host.proc_root),
        cgroup_mount=str(host.cgroup_mount),
    )
    assert results[0]["pane_id"] == "%99"
    assert results[0]["agent_kind"] == AGENT_CLAUDE


def test_batch_empty_panes() -> None:
    assert cgroup_agents.kind_for_panes([]) == []


def test_result_json_omits_evidence_pid_when_absent(host: _FakeHost) -> None:
    """The `none` result envelope has no evidence_pid key (compact wire)."""
    scope = "tmuxctl-git-empty.scope"
    host.add_proc(400, scope=scope, comm="bash", cmdline="/bin/bash")
    host.set_scope_procs(scope, [400])
    results = cgroup_agents.kind_for_panes(
        [{"pane_id": "%1", "pane_pid": 400}],
        proc_root=str(host.proc_root),
        cgroup_mount=str(host.cgroup_mount),
    )
    assert "evidence_pid" not in results[0]
    assert results[0]["agent_kind"] == AGENT_NONE
