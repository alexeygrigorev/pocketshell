"""Unit tests for `pocketshell sessions kill`.

Kill reuses attach's name resolution (`_match_attach_target` +
`_find_tmux_socket`) and then runs `tmux -S <socket> kill-session -t '=NAME'`
or `a kill <id>`. The load-bearing contract is exact match: killing `api`
must not destroy `api-staging`. `tmux kill-server` is never on the argv.
"""

from __future__ import annotations

import json
import os
import subprocess
from pathlib import Path
from typing import Any, Callable, Optional, Sequence
from unittest.mock import patch

import pytest
from click.testing import CliRunner

from pocketshell.sessions import sessions_group

FAKE_TMUXCTL = "/fake/tmuxctl"


def _table(*names: str) -> str:
    """A `tmuxctl list` human table containing ``names``."""
    lines = ["IDX  SESSION               CREATED"]
    for index, name in enumerate(names, start=1):
        lines.append(f"{index:<5}{name:<22}2026-05-27 17:32:30 ")
    return "\n".join(lines) + "\n"


def _completed(returncode: int = 0, stdout: str = "", stderr: str = "") -> Any:
    return subprocess.CompletedProcess(
        args=[], returncode=returncode, stdout=stdout, stderr=stderr
    )


class Harness:
    """Records every stubbed subprocess call, in order."""

    def __init__(
        self,
        table: str,
        has_session: Callable[[str, str], bool],
        *,
        kill_fails: bool = False,
        a_kill_fails: bool = False,
    ):
        self.table = table
        self.has_session = has_session
        self.kill_fails = kill_fails
        self.a_kill_fails = a_kill_fails
        self.events: list[tuple[str, list[str]]] = []

    @property
    def kill_calls(self) -> list[list[str]]:
        return [argv for kind, argv in self.events if kind == "kill-session"]

    @property
    def a_kill_calls(self) -> list[list[str]]:
        return [argv for kind, argv in self.events if kind == "a-kill"]

    def run(self, argv: Sequence[str], **_kwargs: Any) -> Any:
        args = [str(item) for item in argv]
        if "kill-server" in args:
            raise AssertionError(f"sessions kill must never invoke kill-server: {args}")
        if args[0] == FAKE_TMUXCTL:
            self.events.append(("tmuxctl", args))
            return _completed(stdout=self.table)
        if args[0] == "tmux" and "has-session" in args:
            socket_path = args[args.index("-S") + 1]
            target = args[-1].lstrip("=")
            self.events.append(("has-session", args))
            return _completed(returncode=0 if self.has_session(socket_path, target) else 1)
        if args[0] == "tmux" and "kill-session" in args:
            self.events.append(("kill-session", args))
            if self.kill_fails:
                return _completed(returncode=1, stderr="can't find session\n")
            target = args[args.index("-t") + 1]
            if not target.startswith("="):
                return _completed(
                    returncode=1,
                    stderr=f"prefix match is not allowed: {target}\n",
                )
            return _completed()
        if args[0] == "a" and "kill" in args:
            self.events.append(("a-kill", args))
            if self.a_kill_fails:
                return _completed(returncode=1, stderr="no such session\n")
            return _completed()
        raise AssertionError(f"unexpected subprocess call: {args}")


@pytest.fixture
def socket_dir(monkeypatch, tmp_path) -> Path:
    """Create (and return) the tmux socket dir the conftest points TMUX_TMPDIR at."""
    base = Path(os.environ["TMUX_TMPDIR"]) / f"tmux-{os.getuid()}"
    base.mkdir(parents=True, exist_ok=True)
    monkeypatch.setattr(
        "pocketshell.session_enum._default_tmux_runner",
        lambda argv: (1, "", "no server"),
    )
    return base


def _invoke(
    harness: Harness,
    argv: list[str],
    *,
    which: Optional[Callable[..., Optional[str]]] = None,
):
    runner = CliRunner()
    with patch(
        "pocketshell.sessions._resolve_tmuxctl_binary", return_value=FAKE_TMUXCTL
    ), patch("pocketshell.sessions.subprocess.run", harness.run), patch(
        "pocketshell.sessions.shutil.which",
        which if which is not None else (lambda name, *a, **k: f"/usr/bin/{name}"),
    ):
        return runner.invoke(sessions_group, argv)


# ---------------------------------------------------------------------------
# tmux exact match
# ---------------------------------------------------------------------------


def test_kill_tmux_uses_exact_equals_target(socket_dir) -> None:
    derived = socket_dir / "tmuxctl-api"
    derived.touch()
    staging = socket_dir / "tmuxctl-api-staging"
    staging.touch()
    harness = Harness(
        _table("api", "api-staging"),
        lambda sock, name: (sock == str(derived) and name == "api")
        or (sock == str(staging) and name == "api-staging"),
    )

    result = _invoke(harness, ["kill", "api"])

    assert result.exit_code == 0, result.output
    assert harness.kill_calls == [
        ["tmux", "-S", str(derived), "kill-session", "-t", "=api"]
    ]
    assert all("kill-server" not in argv for _, argv in harness.events)
    assert all("=api-staging" not in argv for argv in harness.kill_calls)


def test_kill_api_does_not_kill_api_staging_when_only_staging_exists(socket_dir) -> None:
    """The dangerous case: `api` is gone, `api-staging` is alive.

    A bare `-t api` would destroy `api-staging` and report success. Exact
    name matching plus `=api` must fail closed instead.
    """
    staging = socket_dir / "tmuxctl-api-staging"
    staging.touch()
    harness = Harness(
        _table("api-staging"),
        lambda sock, name: sock == str(staging) and name == "api-staging",
    )

    result = _invoke(harness, ["kill", "api"])

    assert result.exit_code == 3, result.output
    assert "no session named 'api'" in result.output
    assert harness.kill_calls == []


def test_kill_tmux_sweeps_socket_dir_when_derived_socket_absent(socket_dir) -> None:
    default = socket_dir / "default"
    default.touch()
    harness = Harness(
        _table("legacy-session"),
        lambda sock, name: sock == str(default) and name == "legacy-session",
    )

    result = _invoke(harness, ["kill", "legacy-session"])

    assert result.exit_code == 0, result.output
    assert harness.kill_calls == [
        ["tmux", "-S", str(default), "kill-session", "-t", "=legacy-session"]
    ]


def test_kill_unknown_name_exits_3(socket_dir) -> None:
    harness = Harness(_table("git-tmuxcli"), lambda sock, name: True)

    result = _invoke(harness, ["kill", "nope-session"])

    assert result.exit_code == 3, result.output
    assert "no session named 'nope-session'" in result.output
    assert harness.kill_calls == []


def test_kill_tmux_session_without_socket_exits_5(socket_dir) -> None:
    harness = Harness(_table("git-tmuxcli"), lambda sock, name: False)

    result = _invoke(harness, ["kill", "git-tmuxcli"])

    assert result.exit_code == 5, result.output
    assert "no tmux server socket serves it" in result.output
    assert harness.kill_calls == []


def test_kill_missing_tmux_binary_exits_127(socket_dir) -> None:
    derived = socket_dir / "tmuxctl-git-tmuxcli"
    derived.touch()
    harness = Harness(_table("git-tmuxcli"), lambda sock, name: True)

    result = _invoke(
        harness,
        ["kill", "git-tmuxcli"],
        which=lambda name, *a, **k: None if name == "tmux" else f"/usr/bin/{name}",
    )

    assert result.exit_code == 127, result.output
    assert "`tmux` is not installed" in result.output
    assert harness.kill_calls == []


def test_kill_never_invokes_kill_server(socket_dir) -> None:
    derived = socket_dir / "tmuxctl-api"
    derived.touch()
    harness = Harness(
        _table("api"),
        lambda sock, name: sock == str(derived) and name == "api",
    )

    result = _invoke(harness, ["kill", "api"])

    assert result.exit_code == 0, result.output
    kinds = [kind for kind, _argv in harness.events]
    assert "kill-session" in kinds
    assert all("kill-server" not in argv for _kind, argv in harness.events)


def test_kill_json_reports_killed_true(socket_dir) -> None:
    derived = socket_dir / "tmuxctl-api"
    derived.touch()
    harness = Harness(
        _table("api"),
        lambda sock, name: sock == str(derived) and name == "api",
    )

    result = _invoke(harness, ["kill", "--json", "api"])

    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert payload["schema"] == 2
    assert payload["name"] == "api"
    assert payload["manager"] == "tmux"
    assert payload["killed"] is True


def test_kill_json_not_found_is_an_error_envelope(socket_dir) -> None:
    harness = Harness(_table("api"), lambda sock, name: True)

    result = _invoke(harness, ["kill", "--json", "missing"])

    assert result.exit_code == 3, result.output
    payload = json.loads(result.output)
    assert payload["schema"] == 2
    assert "no session named 'missing'" in payload["error"]


# ---------------------------------------------------------------------------
# aplexer
# ---------------------------------------------------------------------------


def test_kill_aplexer_display_name_runs_a_kill(install_fake_a, socket_dir) -> None:
    install_fake_a(
        snapshot=[
            {
                "id": "sess-abc12345",
                "tag": "codex",
                "engine": "codex",
                "workspace": "/home/alexey/git/toyaikit",
                "created_at_ms": 1_700_000_000_000,
            }
        ]
    )
    harness = Harness(_table("git-tmuxcli"), lambda sock, name: False)

    result = _invoke(harness, ["kill", "toyaikit:codex"])

    assert result.exit_code == 0, result.output
    assert harness.a_kill_calls == [["a", "kill", "sess-abc12345"]]
    assert harness.kill_calls == []


def test_kill_aplexer_id_prefix_runs_a_kill(install_fake_a, socket_dir) -> None:
    install_fake_a(
        snapshot=[
            {
                "id": "abcdef0123456789",
                "tag": "codex",
                "workspace": "/home/alexey/git/toyaikit",
            }
        ]
    )
    harness = Harness(_table("git-tmuxcli"), lambda sock, name: False)

    result = _invoke(harness, ["kill", "abcdef01"])

    assert result.exit_code == 0, result.output
    assert harness.a_kill_calls == [["a", "kill", "abcdef0123456789"]]


def test_kill_missing_a_binary_exits_127(install_fake_a, socket_dir) -> None:
    install_fake_a(
        snapshot=[
            {
                "id": "sess-abc12345",
                "tag": "codex",
                "workspace": "/home/alexey/git/toyaikit",
            }
        ]
    )
    harness = Harness(_table("git-tmuxcli"), lambda sock, name: False)

    with patch("pocketshell.sessions._resolve_aplexer_binary", return_value=None):
        result = _invoke(harness, ["kill", "toyaikit:codex"])

    assert result.exit_code == 127, result.output
    assert "`a` (aplexer) is not installed" in result.output
    assert harness.a_kill_calls == []


def test_kill_ambiguous_id_prefix_exits_4(install_fake_a, socket_dir) -> None:
    install_fake_a(
        snapshot=[
            {
                "id": "abcdef0100000001",
                "tag": "codex",
                "workspace": "/home/alexey/git/toyaikit",
            },
            {
                "id": "abcdef0100000002",
                "tag": "claude",
                "workspace": "/home/alexey/git/pocketshell",
            },
        ]
    )
    harness = Harness(_table("git-tmuxcli"), lambda sock, name: True)

    result = _invoke(harness, ["kill", "abcdef01"])

    assert result.exit_code == 4, result.output
    assert "ambiguous session name 'abcdef01'" in result.output
    assert harness.a_kill_calls == []
    assert harness.kill_calls == []
