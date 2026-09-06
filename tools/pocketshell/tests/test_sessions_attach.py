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
import time
from pathlib import Path
from typing import Any, Callable, Optional, Sequence
from unittest.mock import patch

import pytest
from click.testing import CliRunner

from pocketshell import aplexer as _aplexer
from pocketshell import session_enum
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
    script = install_fake_a(
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
    # #2543: the RESOLVED binary is exec'd, not a bare "a" from PATH.
    assert harness.exec_calls == [[str(script), "attach", "sess-abc12345"]]


def test_attach_aplexer_id_prefix_execs_a_attach(install_fake_a, socket_dir) -> None:
    script = install_fake_a(
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
    assert harness.exec_calls == [[str(script), "attach", "abcdef0123456789"]]


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

    unresolved = _aplexer.AplexerResolution(
        tried=("APLEXER_BIN (unset)", "/opt/venv/bin/a (bundled, missing)")
    )
    with patch("pocketshell.sessions._resolve_aplexer", return_value=unresolved):
        result = _invoke(harness, ["attach", "toyaikit:codex"])

    assert result.exit_code == 127, result.output
    assert "could not resolve the `a` (aplexer) binary" in result.output
    assert "/opt/venv/bin/a (bundled, missing)" in result.output
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


# ---------------------------------------------------------------------------
# dead aplexer records (issue #2554)
# ---------------------------------------------------------------------------
#
# The listing no longer offers a dead record, so attach can only reach one
# through a stale name the caller kept. It must then SAY what happened
# instead of handing the name to `a attach`, which answers
# "session … has already exited" on stderr and exits 1 — the bare
# `Session "…" ended (exit 1).` the phone showed.


# Every entry carries a `worker_pid`, because every real dead record does:
# aplexer only reports `worker_alive: false` for a worker that HAD a pid, and
# a record with no pid yet is a session being created, not a corpse (#2554
# round 3). A pid-less zombie is not a shape `a --json list` can emit.
DEAD_STATES = [
    ("exited", False, "has already exited", "phase: exited"),
    ("failed", False, "failed to start", "phase: failed"),
    ("running", False, "has no live worker", "worker_alive: false"),
]


@pytest.mark.parametrize("phase,worker_alive,summary,detail", DEAD_STATES)
def test_attach_to_a_dead_record_names_the_state(
    install_fake_a, socket_dir, phase, worker_alive, summary, detail
) -> None:
    install_fake_a(
        snapshot=[
            {
                "id": "sess-abc12345",
                "tag": "codex",
                "engine": "codex",
                "workspace": "/home/alexey/git/toyaikit",
                "created_at_ms": 1_700_000_000_000,
                "phase": phase,
                "worker_pid": 4_151_890,
                "worker_alive": worker_alive,
            }
        ]
    )
    harness = Harness(_table("git-tmuxcli"), lambda sock, name: False)

    result = _invoke(harness, ["attach", "toyaikit:codex"])

    assert result.exit_code == 3, result.output
    assert summary in result.output
    assert detail in result.output
    assert "toyaikit:codex" in result.output
    # The load-bearing part: `a attach` is never handed a corpse.
    assert harness.exec_calls == []


def test_attach_to_a_dead_record_by_id_prefix_names_the_state(
    install_fake_a, socket_dir
) -> None:
    install_fake_a(
        snapshot=[
            {
                "id": "abcdef0123456789",
                "tag": "codex",
                "workspace": "/home/alexey/git/toyaikit",
                "phase": "exited",
                "worker_pid": 4_151_890,
                "worker_alive": False,
            }
        ]
    )
    harness = Harness(_table("git-tmuxcli"), lambda sock, name: False)

    result = _invoke(harness, ["attach", "abcdef01"])

    assert result.exit_code == 3, result.output
    assert "has already exited" in result.output
    assert harness.exec_calls == []


def test_attach_to_a_genuinely_unknown_name_keeps_the_plain_message(
    install_fake_a, socket_dir
) -> None:
    """The dead-row explanation must not swallow the ordinary not-found path."""
    install_fake_a(snapshot=[])
    harness = Harness(_table("git-tmuxcli"), lambda sock, name: False)

    result = _invoke(harness, ["attach", "nope-session"])

    assert result.exit_code == 3, result.output
    assert "no session named 'nope-session'" in result.output
    assert "exited" not in result.output
    assert harness.exec_calls == []


def test_attach_to_a_session_being_created_is_not_refused(
    install_fake_a, socket_dir
) -> None:
    """The real mid-create shape must still attach (#2547's class).

    aplexer writes the record before the worker registers its pid, so
    `a --json list` reports `phase: starting` with no `worker_pid` and
    therefore `worker_alive: false` for tens of milliseconds on EVERY
    create. Reading that as a corpse turns a session the user is watching
    appear into an exit-3 "it has no live worker".
    """
    script = install_fake_a(
        snapshot=[
            {
                "id": "sess-abc12345",
                "tag": "codex",
                "engine": "codex",
                "workspace": "/home/alexey/git/toyaikit",
                "created_at_ms": 1_700_000_000_000,
                # Verbatim shape of tests/fixtures/aplexer/snapshot-mid-create.json:
                # phase starting, `worker_pid` absent, worker_alive false —
                # and written just now, because a create in progress is by
                # definition happening now.
                "phase": "starting",
                "worker_alive": False,
                "updated_at_ms": int(time.time() * 1000),
            }
        ]
    )
    harness = Harness(_table("git-tmuxcli"), lambda sock, name: False)

    result = _invoke(harness, ["attach", "toyaikit:codex"])

    assert result.exit_code == 0, result.output
    assert harness.exec_calls == [[str(script), "attach", "sess-abc12345"]]


def test_attach_to_a_crashed_start_names_the_state(install_fake_a, socket_dir) -> None:
    """A pre-PID record that never progressed is a corpse, not a create.

    An `a start` SIGKILLed inside the pre-PID window leaves this shape
    forever (captured: tests/fixtures/aplexer/snapshot-crashed-start.json).
    Handing it to `a attach` is the reported symptom verbatim — aplexer
    answers "worker is not running (state: broken)" and exits 1.
    """
    install_fake_a(
        snapshot=[
            {
                "id": "sess-abc12345",
                "tag": "codex",
                "engine": "codex",
                "workspace": "/home/alexey/git/toyaikit",
                "created_at_ms": 1_700_000_000_000,
                "phase": "starting",
                "worker_alive": False,
                # Same shape as the mid-create record above; only older than
                # any create could still be running.
                "updated_at_ms": int(time.time() * 1000)
                - session_enum.APLEXER_STARTING_GRACE_MS
                - 1,
            }
        ]
    )
    harness = Harness(_table("git-tmuxcli"), lambda sock, name: False)

    result = _invoke(harness, ["attach", "toyaikit:codex"])

    assert result.exit_code == 3, result.output
    assert "has no live worker" in result.output
    assert "phase: starting" in result.output
    assert harness.exec_calls == []
