"""Unit tests for `pocketshell sessions attach`.

`attach` is the one `sessions` subcommand that never returns on success: it
resolves NAME against the same enumeration `sessions list` uses and then
`execvp`s the process into `tmux attach-session` / `a attach`. So every test
here monkeypatches the module-level `_exec` seam and asserts on the argv that
resolution produced, plus the exit code taken on each failure branch:

  3   no session by that name
  4   ambiguous
  5   tmux session listed but no server socket serves it
  127 attach binary missing

`subprocess.run` is stubbed with a dispatcher so `tmuxctl list`,
`tmux has-session` (socket resolution) and `tmux set-option`
(`--hide-status`) can each be answered independently, and the ordering of
"set-option, then exec" is observable on a single event log.
"""

from __future__ import annotations

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
    """Records `_exec` argv + every stubbed subprocess call, in order."""

    def __init__(
        self,
        table: str,
        has_session: Callable[[str, str], bool],
        *,
        set_option_fails: bool = False,
    ):
        self.table = table
        self.has_session = has_session
        self.set_option_fails = set_option_fails
        self.events: list[tuple[str, list[str]]] = []

    @property
    def exec_calls(self) -> list[list[str]]:
        return [argv for kind, argv in self.events if kind == "exec"]

    @property
    def set_option_calls(self) -> list[list[str]]:
        return [argv for kind, argv in self.events if kind == "set-option"]

    def exec(self, argv: list[str]) -> None:
        self.events.append(("exec", list(argv)))

    def run(self, argv: Sequence[str], **_kwargs: Any) -> Any:
        args = [str(item) for item in argv]
        if args[0] == FAKE_TMUXCTL:
            self.events.append(("tmuxctl", args))
            return _completed(stdout=self.table)
        if args[0] == "tmux" and "has-session" in args:
            socket_path = args[args.index("-S") + 1]
            target = args[-1].lstrip("=")
            self.events.append(("has-session", args))
            return _completed(returncode=0 if self.has_session(socket_path, target) else 1)
        if args[0] == "tmux" and "set-option" in args:
            self.events.append(("set-option", args))
            if self.set_option_fails:
                return _completed(returncode=1, stderr="server exited\n")
            target = args[args.index("-t") + 1]
            # Reproduces real tmux 3.4 grammar (verified live on the dev box):
            # `set-option -t` takes a PANE target, so a bare `=name` — the
            # form `attach-session -t` wants — is rejected outright. Getting
            # this wrong makes --hide-status a silent no-op.
            if target.startswith("=") and not target.endswith(":"):
                return _completed(
                    returncode=1, stderr=f"no such session: {target}\n"
                )
            return _completed()
        raise AssertionError(f"unexpected subprocess call: {args}")


@pytest.fixture
def socket_dir(monkeypatch, tmp_path) -> Path:
    """Create (and return) the tmux socket dir the conftest points TMUX_TMPDIR at."""
    base = Path(os.environ["TMUX_TMPDIR"]) / f"tmux-{os.getuid()}"
    base.mkdir(parents=True, exist_ok=True)
    # The schema-2 tmux enrichment sweep runs on the attach enumeration too;
    # keep it away from the developer's live tmux servers now that the socket
    # directory exists.
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
    ), patch("pocketshell.sessions._exec", harness.exec), patch(
        "pocketshell.sessions.subprocess.run", harness.run
    ), patch(
        "pocketshell.sessions.shutil.which",
        which if which is not None else (lambda name, *a, **k: f"/usr/bin/{name}"),
    ):
        return runner.invoke(sessions_group, argv)


# ---------------------------------------------------------------------------
# aplexer resolution
# ---------------------------------------------------------------------------


def test_attach_aplexer_display_name_execs_a_attach(install_fake_a, socket_dir) -> None:
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

    result = _invoke(harness, ["attach", "toyaikit:codex"])

    assert result.exit_code == 0, result.output
    assert harness.exec_calls == [["a", "attach", "sess-abc12345"]]


def test_attach_aplexer_id_prefix_execs_a_attach(install_fake_a, socket_dir) -> None:
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

    result = _invoke(harness, ["attach", "abcdef01"])

    assert result.exit_code == 0, result.output
    assert harness.exec_calls == [["a", "attach", "abcdef0123456789"]]


def test_attach_short_id_prefix_is_not_a_match(install_fake_a, socket_dir) -> None:
    """A prefix shorter than 8 chars is a guess, not a selector."""
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

    result = _invoke(harness, ["attach", "abcdef0"])

    assert result.exit_code == 3, result.output
    assert harness.exec_calls == []


def test_attach_missing_a_binary_exits_127(install_fake_a, socket_dir) -> None:
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
        result = _invoke(harness, ["attach", "toyaikit:codex"])

    assert result.exit_code == 127, result.output
    assert "`a` (aplexer) is not installed" in result.output
    assert harness.exec_calls == []


# ---------------------------------------------------------------------------
# tmux resolution
# ---------------------------------------------------------------------------


def test_attach_tmux_uses_name_derived_socket(socket_dir) -> None:
    derived = socket_dir / "tmuxctl-git-tmuxcli"
    derived.touch()
    harness = Harness(
        _table("git-tmuxcli"),
        lambda sock, name: sock == str(derived) and name == "git-tmuxcli",
    )

    result = _invoke(harness, ["attach", "git-tmuxcli"])

    assert result.exit_code == 0, result.output
    assert harness.exec_calls == [
        ["tmux", "-S", str(derived), "attach-session", "-t", "=git-tmuxcli"]
    ]


def test_attach_tmux_sweeps_socket_dir_when_derived_socket_absent(socket_dir) -> None:
    """A session on the shared `default` socket is still attachable."""
    stale = socket_dir / "tmuxctl-something-else"
    stale.touch()
    default = socket_dir / "default"
    default.touch()
    harness = Harness(
        _table("legacy-session"),
        lambda sock, name: sock == str(default) and name == "legacy-session",
    )

    result = _invoke(harness, ["attach", "legacy-session"])

    assert result.exit_code == 0, result.output
    assert harness.exec_calls == [
        ["tmux", "-S", str(default), "attach-session", "-t", "=legacy-session"]
    ]
    # The name-derived socket does not exist, so it is never probed; the
    # sweep is what found the session.
    probed = [
        argv[argv.index("-S") + 1]
        for kind, argv in harness.events
        if kind == "has-session"
    ]
    assert str(socket_dir / "tmuxctl-legacy-session") not in probed
    assert str(default) in probed


def test_attach_stale_derived_socket_falls_through_to_sweep(socket_dir) -> None:
    """A leftover socket file whose server is gone must not win the match."""
    derived = socket_dir / "tmuxctl-ghost"
    derived.touch()
    live = socket_dir / "default"
    live.touch()
    harness = Harness(
        _table("ghost"), lambda sock, name: sock == str(live) and name == "ghost"
    )

    result = _invoke(harness, ["attach", "ghost"])

    assert result.exit_code == 0, result.output
    assert harness.exec_calls == [
        ["tmux", "-S", str(live), "attach-session", "-t", "=ghost"]
    ]
    probed = [
        argv[argv.index("-S") + 1]
        for kind, argv in harness.events
        if kind == "has-session"
    ]
    # The derived socket is still tried first — it just failed has-session.
    assert probed[0] == str(derived)


def test_attach_hide_status_sets_option_before_exec(socket_dir) -> None:
    derived = socket_dir / "tmuxctl-git-tmuxcli"
    derived.touch()
    harness = Harness(
        _table("git-tmuxcli"),
        lambda sock, name: sock == str(derived),
    )

    result = _invoke(harness, ["attach", "--hide-status", "git-tmuxcli"])

    assert result.exit_code == 0, result.output
    assert harness.set_option_calls == [
        [
            "tmux",
            "-S",
            str(derived),
            "set-option",
            "-t",
            # `=name:` not `=name`: see Harness.run — a pane target, unlike
            # attach-session's session target.
            "=git-tmuxcli:",
            "status",
            "off",
        ]
    ]
    # The set-option must actually have succeeded; a rejected target would
    # leave the status bar on while the attach still looked fine.
    assert "could not hide the tmux status bar" not in result.output
    kinds = [kind for kind, _argv in harness.events]
    assert kinds.index("set-option") < kinds.index("exec")
    assert harness.exec_calls == [
        ["tmux", "-S", str(derived), "attach-session", "-t", "=git-tmuxcli"]
    ]


def test_attach_warns_but_still_attaches_when_hide_status_fails(socket_dir) -> None:
    derived = socket_dir / "tmuxctl-git-tmuxcli"
    derived.touch()
    harness = Harness(
        _table("git-tmuxcli"),
        lambda sock, name: sock == str(derived),
        set_option_fails=True,
    )

    result = _invoke(harness, ["attach", "--hide-status", "git-tmuxcli"])

    assert result.exit_code == 0, result.output
    assert "could not hide the tmux status bar: server exited" in result.output
    assert harness.exec_calls == [
        ["tmux", "-S", str(derived), "attach-session", "-t", "=git-tmuxcli"]
    ]


def test_attach_without_hide_status_leaves_status_bar_alone(socket_dir) -> None:
    derived = socket_dir / "tmuxctl-git-tmuxcli"
    derived.touch()
    harness = Harness(_table("git-tmuxcli"), lambda sock, name: sock == str(derived))

    result = _invoke(harness, ["attach", "git-tmuxcli"])

    assert result.exit_code == 0, result.output
    assert harness.set_option_calls == []


def test_attach_tmux_session_without_socket_exits_5(socket_dir) -> None:
    harness = Harness(_table("git-tmuxcli"), lambda sock, name: False)

    result = _invoke(harness, ["attach", "git-tmuxcli"])

    assert result.exit_code == 5, result.output
    assert "no tmux server socket serves it" in result.output
    assert harness.exec_calls == []


def test_attach_missing_tmux_binary_exits_127(socket_dir) -> None:
    derived = socket_dir / "tmuxctl-git-tmuxcli"
    derived.touch()
    harness = Harness(_table("git-tmuxcli"), lambda sock, name: True)

    result = _invoke(
        harness,
        ["attach", "git-tmuxcli"],
        which=lambda name, *a, **k: None if name == "tmux" else f"/usr/bin/{name}",
    )

    assert result.exit_code == 127, result.output
    assert "`tmux` is not installed" in result.output
    assert harness.exec_calls == []


# ---------------------------------------------------------------------------
# not-found / ambiguous
# ---------------------------------------------------------------------------


def test_attach_unknown_name_exits_3(socket_dir) -> None:
    harness = Harness(_table("git-tmuxcli"), lambda sock, name: True)

    result = _invoke(harness, ["attach", "nope-session"])

    assert result.exit_code == 3, result.output
    assert "no session named 'nope-session'" in result.output
    assert harness.exec_calls == []


def test_attach_ambiguous_id_prefix_exits_4(install_fake_a, socket_dir) -> None:
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

    result = _invoke(harness, ["attach", "abcdef01"])

    assert result.exit_code == 4, result.output
    assert "ambiguous session name 'abcdef01'" in result.output
    assert "abcdef0100000001" in result.output
    assert "abcdef0100000002" in result.output
    assert harness.exec_calls == []


def test_attach_ambiguous_display_name_exits_4(install_fake_a, socket_dir) -> None:
    """Two aplexer sessions can share a `<workspace>:<tag>` display name."""
    install_fake_a(
        snapshot=[
            {
                "id": "aaaa000000000001",
                "tag": "codex",
                "workspace": "/home/alexey/git/toyaikit",
            },
            {
                "id": "bbbb000000000002",
                "tag": "codex",
                "workspace": "/home/alexey/git/toyaikit",
            },
        ]
    )
    harness = Harness(_table("git-tmuxcli"), lambda sock, name: True)

    result = _invoke(harness, ["attach", "toyaikit:codex"])

    assert result.exit_code == 4, result.output
    assert harness.exec_calls == []


def test_attach_prefers_tmux_row_over_aplexer_id_prefix(
    install_fake_a, socket_dir
) -> None:
    """An exact tmux name wins over an aplexer id that happens to prefix-match."""
    install_fake_a(
        snapshot=[
            {
                "id": "git-tmuxcli-0001",
                "tag": "codex",
                "workspace": "/home/alexey/git/toyaikit",
            }
        ]
    )
    derived = socket_dir / "tmuxctl-git-tmuxcli"
    derived.touch()
    harness = Harness(_table("git-tmuxcli"), lambda sock, name: sock == str(derived))

    result = _invoke(harness, ["attach", "git-tmuxcli"])

    assert result.exit_code == 0, result.output
    assert harness.exec_calls == [
        ["tmux", "-S", str(derived), "attach-session", "-t", "=git-tmuxcli"]
    ]


def test_exec_seam_calls_execvp() -> None:
    """The seam the tests patch must really be an exec, not a subprocess call."""
    from pocketshell import sessions as _sessions

    with patch("pocketshell.sessions.os.execvp") as execvp:
        _sessions._exec(["tmux", "-S", "/tmp/sock", "attach-session"])
    execvp.assert_called_once_with(
        "tmux", ["tmux", "-S", "/tmp/sock", "attach-session"]
    )
