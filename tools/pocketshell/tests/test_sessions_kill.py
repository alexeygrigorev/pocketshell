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

from pocketshell import aplexer as _aplexer
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
        forget_refusals: int = 0,
        forget_always_refuses: bool = False,
        workload_may_survive: bool = False,
    ):
        self.table = table
        self.has_session = has_session
        self.kill_fails = kill_fails
        self.a_kill_fails = a_kill_fails
        # Issue #2554: aplexer refuses to forget a record whose worker is
        # still winding down. `forget_refusals` replays that window for N
        # attempts before the record becomes forgettable, exactly like the
        # real `a forget --force` does (message copied verbatim from
        # aplexer/src/api.rs::forget_session).
        self.forget_refusals = forget_refusals
        self.forget_always_refuses = forget_always_refuses
        # `a --json forget` says whether containment was proven empty; false
        # means processes may have outlived the record (issue #2554 round 3).
        self.workload_may_survive = workload_may_survive
        self.events: list[tuple[str, list[str]]] = []

    @property
    def kill_calls(self) -> list[list[str]]:
        return [argv for kind, argv in self.events if kind == "kill-session"]

    @property
    def a_kill_calls(self) -> list[list[str]]:
        return [argv for kind, argv in self.events if kind == "a-kill"]

    @property
    def a_forget_calls(self) -> list[list[str]]:
        return [argv for kind, argv in self.events if kind == "a-forget"]

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
        # #2543: argv[0] is the RESOLVED aplexer binary (the fixture's stub via
        # APLEXER_BIN), never a bare "a" that execvp would find on PATH.
        if Path(args[0]).name in {"a", "fake-a"} and "kill" in args:
            self.events.append(("a-kill", args))
            if self.a_kill_fails:
                return _completed(returncode=1, stderr="no such session\n")
            return _completed()
        if Path(args[0]).name in {"a", "fake-a"} and "forget" in args:
            self.events.append(("a-forget", args))
            attempt = len(self.a_forget_calls)
            if self.forget_always_refuses or attempt <= self.forget_refusals:
                session_id = args[-1]
                return _completed(
                    returncode=1,
                    stderr=(
                        f"a: session {session_id} still has a live worker; "
                        "refusing to forget it\n"
                    ),
                )
            return _completed(
                stdout=json.dumps(
                    {
                        "id": args[-1],
                        "forgotten": True,
                        "signalled": False,
                        "containment_proven_empty": not self.workload_may_survive,
                        "workload_may_survive": self.workload_may_survive,
                    }
                )
                + "\n"
            )
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
        # The reap's inter-attempt pause; stubbed so the retry window is
        # exercised at full speed instead of wall-clock.
        "pocketshell.sessions._reap_wait",
        lambda: None,
    ), patch(
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

    result = _invoke(harness, ["kill", "toyaikit:codex"])

    assert result.exit_code == 0, result.output
    assert harness.a_kill_calls == [[str(script), "kill", "sess-abc12345"]]
    assert harness.kill_calls == []


def test_kill_aplexer_id_prefix_runs_a_kill(install_fake_a, socket_dir) -> None:
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

    result = _invoke(harness, ["kill", "abcdef01"])

    assert result.exit_code == 0, result.output
    assert harness.a_kill_calls == [[str(script), "kill", "abcdef0123456789"]]


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

    unresolved = _aplexer.AplexerResolution(
        tried=("APLEXER_BIN (unset)", "/opt/venv/bin/a (bundled, missing)")
    )
    with patch("pocketshell.sessions._resolve_aplexer", return_value=unresolved):
        result = _invoke(harness, ["kill", "toyaikit:codex"])

    assert result.exit_code == 127, result.output
    assert "could not resolve the `a` (aplexer) binary" in result.output
    assert "/opt/venv/bin/a (bundled, missing)" in result.output
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


# ---------------------------------------------------------------------------
# the reap: Stop must remove the row, not leave a dead one (issue #2554)
# ---------------------------------------------------------------------------
#
# `a kill` only signals the workload; aplexer keeps the record at
# `phase: exited` forever, and `session_enum` used to list it as an ordinary
# attachable row. So a successful Stop left a tappable corpse in the tree
# that answered `a: session … has already exited` with exit 1.
#
# The reap is `a forget --force <id>`, not `a prune`, and it RETRIES:
# immediately after `a kill` the worker is still winding down, and aplexer
# refuses to forget a record with a live worker (verified on the dev box
# against aplexer 0.1.3).


def _aplexer_snapshot(session_id: str = "sess-abc12345") -> list[dict[str, Any]]:
    return [
        {
            "id": session_id,
            "tag": "codex",
            "engine": "codex",
            "workspace": "/home/alexey/git/toyaikit",
            "created_at_ms": 1_700_000_000_000,
            "phase": "running",
            "worker_alive": True,
        }
    ]


def test_kill_aplexer_reaps_the_record_with_forget(install_fake_a, socket_dir) -> None:
    script = install_fake_a(snapshot=_aplexer_snapshot())
    harness = Harness(_table("git-tmuxcli"), lambda sock, name: False)

    result = _invoke(harness, ["kill", "toyaikit:codex"])

    assert result.exit_code == 0, result.output
    assert harness.a_kill_calls == [[str(script), "kill", "sess-abc12345"]]
    # The reap is targeted at the id just killed, and unconditional —
    # `a prune` (0.1.3) cannot reap a record whose worker died without
    # recording an exit, and skips one whose worker is still winding down.
    assert harness.a_forget_calls == [
        # `--json` so the containment verdict comes back as data, not a
        # stderr line that has to be string-matched (#2554 round 3).
        [str(script), "--json", "forget", "--force", "sess-abc12345"]
    ]
    # ...and it happens AFTER the kill, never instead of it.
    kinds = [kind for kind, _argv in harness.events]
    assert kinds.index("a-kill") < kinds.index("a-forget")
    assert all("prune" not in argv for _kind, argv in harness.events)


def test_kill_reap_retries_while_the_worker_is_still_winding_down(
    install_fake_a, socket_dir
) -> None:
    """The window a fire-and-forget reap silently loses to.

    Right after `a kill` the record is terminal but `worker_alive` is still
    true, so aplexer answers "still has a live worker; refusing to forget
    it". A single attempt would leave the corpse behind and report success.
    """
    script = install_fake_a(snapshot=_aplexer_snapshot())
    harness = Harness(
        _table("git-tmuxcli"), lambda sock, name: False, forget_refusals=3
    )

    result = _invoke(harness, ["kill", "--json", "toyaikit:codex"])

    assert result.exit_code == 0, result.output
    assert len(harness.a_forget_calls) == 4
    assert all(
        argv == [str(script), "--json", "forget", "--force", "sess-abc12345"]
        for argv in harness.a_forget_calls
    )
    assert json.loads(result.output)["reaped"] is True


def test_kill_json_reports_the_reap(install_fake_a, socket_dir) -> None:
    install_fake_a(snapshot=_aplexer_snapshot())
    harness = Harness(_table("git-tmuxcli"), lambda sock, name: False)

    result = _invoke(harness, ["kill", "--json", "toyaikit:codex"])

    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert payload["manager"] == "aplexer"
    assert payload["killed"] is True
    assert payload["reaped"] is True


def test_kill_still_succeeds_when_the_reap_never_wins(
    install_fake_a, socket_dir
) -> None:
    """A stuck reap must not turn a successful kill into a failure.

    The workload IS dead; all that survives is a record. Reporting the kill
    as failed would make the app retry a kill that already worked.
    """
    install_fake_a(snapshot=_aplexer_snapshot())
    harness = Harness(
        _table("git-tmuxcli"), lambda sock, name: False, forget_always_refuses=True
    )

    result = _invoke(harness, ["kill", "--json", "toyaikit:codex"])

    assert result.exit_code == 0, result.output
    # The warning goes to stderr; the envelope on stdout stays parseable.
    payload = json.loads(result.stdout)
    assert payload["killed"] is True
    assert payload["reaped"] is False
    assert len(harness.a_forget_calls) > 1


def test_a_stuck_reap_is_said_out_loud_on_the_human_path(
    install_fake_a, socket_dir
) -> None:
    """A row that survives a Stop must be explained, not silent."""
    install_fake_a(snapshot=_aplexer_snapshot())
    harness = Harness(
        _table("git-tmuxcli"), lambda sock, name: False, forget_always_refuses=True
    )

    result = _invoke(harness, ["kill", "toyaikit:codex"])

    assert result.exit_code == 0, result.output
    assert "could not be reaped" in result.output
    assert "a forget --force sess-abc12345" in result.output


def test_kill_reap_treats_an_already_gone_record_as_reaped(
    install_fake_a, socket_dir
) -> None:
    """A newer aplexer may reap inside `a kill`; "no matching session" is done."""
    install_fake_a(snapshot=_aplexer_snapshot())

    class _AlreadyGone(Harness):
        def run(self, argv: Sequence[str], **kwargs: Any) -> Any:
            args = [str(item) for item in argv]
            if Path(args[0]).name in {"a", "fake-a"} and "forget" in args:
                self.events.append(("a-forget", args))
                return _completed(returncode=1, stderr="a: no matching session\n")
            return super().run(argv, **kwargs)

    harness = _AlreadyGone(_table("git-tmuxcli"), lambda sock, name: False)

    result = _invoke(harness, ["kill", "--json", "toyaikit:codex"])

    assert result.exit_code == 0, result.output
    assert json.loads(result.output)["reaped"] is True
    assert len(harness.a_forget_calls) == 1  # no pointless retry loop


def test_tmux_kill_never_runs_a_forget(socket_dir) -> None:
    derived = socket_dir / "tmuxctl-api"
    derived.touch()
    harness = Harness(
        _table("api"), lambda sock, name: sock == str(derived) and name == "api"
    )

    result = _invoke(harness, ["kill", "--json", "api"])

    assert result.exit_code == 0, result.output
    assert harness.a_forget_calls == []
    # tmux has no record to reap: killing the session IS the removal.
    assert json.loads(result.output)["reaped"] is True


def test_failed_a_kill_does_not_reap(install_fake_a, socket_dir) -> None:
    """No record is discarded for a session that may still be running."""
    install_fake_a(snapshot=_aplexer_snapshot())
    harness = Harness(
        _table("git-tmuxcli"), lambda sock, name: False, a_kill_fails=True
    )

    result = _invoke(harness, ["kill", "toyaikit:codex"])

    assert result.exit_code != 0
    assert harness.a_forget_calls == []


# ---------------------------------------------------------------------------
# end to end: kill, then list again — against a real, stateful `a` stub
# ---------------------------------------------------------------------------


STATEFUL_A = '''\
#!/usr/bin/env python3
"""A stub `a` that keeps real state, so `kill` is observable in a later `list`.

Mirrors aplexer 0.1.3 exactly where it matters:
  kill    -> phase becomes "exited" but worker_alive stays true (winding down)
  forget  -> refused while worker_alive is true; the SECOND call finds the
             worker gone and removes the record
  prune   -> only removes records aplexer considers reclaimable; a record
             whose worker is still winding down is retained (a no-op here)
"""
import json, sys
from pathlib import Path

STATE = Path(__file__).with_name("a-state.json")
args = [a for a in sys.argv[1:] if a != "--json"]
records = json.loads(STATE.read_text())
cmd = args[0] if args else ""

if cmd in ("snapshot", "list"):
    print(json.dumps(records))
elif cmd == "kill":
    for record in records:
        if record["id"] == args[1]:
            record["phase"] = "exited"
            record["worker_alive"] = True
    STATE.write_text(json.dumps(records))
elif cmd == "forget":
    ident = args[-1]
    match = [r for r in records if r["id"] == ident]
    if not match:
        sys.stderr.write("a: no matching session\\n")
        sys.exit(1)
    if match[0]["worker_alive"]:
        match[0]["worker_alive"] = False  # the worker finishes winding down
        STATE.write_text(json.dumps(records))
        sys.stderr.write(
            "a: session %s still has a live worker; refusing to forget it\\n" % ident
        )
        sys.exit(1)
    STATE.write_text(json.dumps([r for r in records if r["id"] != ident]))
    print(json.dumps({"id": ident, "forgotten": True}))
elif cmd == "prune":
    print(json.dumps({"removed": [], "retained_count": len(records)}))
else:
    sys.exit(2)
'''


@pytest.fixture
def stateful_a(tmp_path, monkeypatch):
    """Install the stateful `a` stub above and seed its record list."""

    def _install(records: list[dict[str, Any]]):
        script = tmp_path / "fake-a"
        script.write_text(STATEFUL_A, encoding="utf-8")
        script.chmod(0o755)
        (tmp_path / "a-state.json").write_text(json.dumps(records), encoding="utf-8")
        monkeypatch.setenv("APLEXER_BIN", str(script))
        monkeypatch.setenv("POCKETSHELL_APLEXER", "1")
        return script

    return _install


@pytest.fixture
def empty_tmuxctl(tmp_path):
    """A real `tmuxctl` stub printing an empty listing (no tmux rows)."""
    script = tmp_path / "fake-tmuxctl"
    script.write_text(
        "#!/bin/sh\necho 'IDX  SESSION               CREATED'\n", encoding="utf-8"
    )
    script.chmod(0o755)
    return str(script)


def test_kill_then_list_no_longer_shows_the_row(stateful_a, empty_tmuxctl) -> None:
    """The reported symptom, end to end: Stop removes the row.

    No `subprocess.run` stub here — `sessions kill` really executes the
    stateful `a`, and the second `sessions list --json` really re-enumerates
    from the state that kill left behind.
    """
    stateful_a(
        [
            {
                "id": "sess-abc12345",
                "tag": "codex",
                "engine": "codex",
                "workspace": "/home/alexey/git/toyaikit",
                "created_at_ms": 1_700_000_000_000,
                "phase": "running",
                "worker_alive": True,
            }
        ]
    )
    runner = CliRunner()
    with patch(
        "pocketshell.sessions._resolve_tmuxctl_binary", return_value=empty_tmuxctl
    ), patch("pocketshell.sessions._reap_wait", lambda: None):
        before = runner.invoke(sessions_group, ["list", "--json"])
        assert before.exit_code == 0, before.output
        assert [row["name"] for row in json.loads(before.output)["sessions"]] == [
            "toyaikit:codex"
        ]

        killed = runner.invoke(sessions_group, ["kill", "--json", "toyaikit:codex"])
        assert killed.exit_code == 0, killed.output

        after = runner.invoke(sessions_group, ["list", "--json"])

    # The load-bearing assertion, first: the row the user just stopped is
    # gone from the very next listing the tree would render.
    assert after.exit_code == 0, after.output
    payload = json.loads(after.output)
    assert payload["sessions"] == []
    # An empty list because the record is GONE, not because a probe broke.
    assert payload["errors"] == []
    assert json.loads(killed.output)["reaped"] is True


def test_kill_warns_when_the_reap_could_not_prove_containment(
    install_fake_a, socket_dir
) -> None:
    """`a forget` never claims the workload stopped; say so when it didn't.

    After a normal `a kill` aplexer reports `containment_proven_empty: true`
    (measured against real `a 0.1.3`), so this warning stays silent on the
    ordinary Stop and only fires when something really may have outlived
    the record.
    """
    install_fake_a(snapshot=_aplexer_snapshot())
    harness = Harness(
        _table("git-tmuxcli"),
        lambda sock, name: False,
        workload_may_survive=True,
    )

    result = _invoke(harness, ["kill", "--json", "toyaikit:codex"])

    assert result.exit_code == 0, result.output
    assert json.loads(result.stdout)["reaped"] is True
    assert "may still be running" in result.stderr
    assert "sess-abc12345" in result.stderr


def test_kill_is_quiet_when_containment_was_proven_empty(
    install_fake_a, socket_dir
) -> None:
    install_fake_a(snapshot=_aplexer_snapshot())
    harness = Harness(_table("git-tmuxcli"), lambda sock, name: False)

    result = _invoke(harness, ["kill", "--json", "toyaikit:codex"])

    assert result.exit_code == 0, result.output
    assert "may still be running" not in result.stderr
