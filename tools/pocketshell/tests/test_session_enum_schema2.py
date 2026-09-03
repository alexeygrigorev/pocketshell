"""`pocketshell sessions list --json` schema 2.

Every row carries the full key set (no key-if-not-None omission — the
Android parser reads a fixed record and must never need a ``containsKey``
probe), and a backend that fails to enumerate lands in ``errors`` instead of
quietly shortening the session list (the #2426 contract).

The aplexer fixtures under ``tests/fixtures/aplexer/`` are REAL captures of
``a snapshot --json`` from the dev box, not hand-typed records:

- ``snapshot.json`` — the host-wide snapshot: mixed engines (codex/claude/
  grok), one ``exited`` row, one row with a ``profile``.
- ``snapshot-reported-state.json`` — an isolated aplexer instance
  (``XDG_STATE_HOME``/``XDG_RUNTIME_DIR`` redirected) with three sessions
  started for the capture, one of which really ran ``a state-report
  waiting`` inside itself, so ``reported_state``/``reported_state_at_ms``
  are genuine aplexer output rather than invented keys.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any, Optional, Sequence
from unittest.mock import patch

from click.testing import CliRunner

from pocketshell import session_enum
from pocketshell import sessions as sessions_module
from pocketshell.sessions import sessions_group

FIXTURES = Path(__file__).parent / "fixtures" / "aplexer"

# Full schema-2 row contract. Order is irrelevant; presence is not.
SCHEMA2_ROW_KEYS = {
    "name",
    "manager",
    "id",
    "workspace",
    "tag",
    "engine",
    "profile",
    "agent_state",
    "agent_state_source",
    "attached",
    "created_epoch",
    "activity_epoch",
}

# ids from the real captures
REPORTED_ID = "7813f1ca-891d-4b43-829f-aca6ee182f10"
HEURISTIC_ID = "b95a85e9-6f71-4b33-b34a-a2344da72910"
PLAIN_SHELL_ID = "97d6e5f9-3a92-41c5-bbe4-d367b5017415"
# Captured values, read back from the fixture rather than restated here.


def _load(name: str) -> Any:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def _row(payload: Any, ident: str) -> dict[str, Any]:
    return next(row for row in payload if row["id"] == ident)


def _tmuxctl_table() -> str:
    return (
        "IDX  SESSION               CREATED\n"
        "1    git-pocketshell       2026-08-31 08:56:12 \n"
        "2    git-aplexer           2026-08-26 13:09:26 \n"
        "\n"
        "Join a session: tmuxctl <id> or tmuxctl <session>\n"
    )


def _by_name(sessions: Sequence[session_enum.LiveSession], name: str):
    return next(row for row in sessions if row.name == name)


# ---------------------------------------------------------------------------
# host shapes: tmux-only, aplexer-only, both
# ---------------------------------------------------------------------------


def test_tmux_only_host_emits_schema_2_with_every_key_present() -> None:
    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        include_aplexer=False,
    )
    payload = session_enum.json_payload(sessions, errors)

    assert payload["schema"] == 2
    assert payload["managers"] == ["tmux"]
    assert payload["errors"] == []
    assert [row["name"] for row in payload["sessions"]] == [
        "git-pocketshell",
        "git-aplexer",
    ]
    for row in payload["sessions"]:
        assert set(row) == SCHEMA2_ROW_KEYS
        assert row["manager"] == "tmux"
        # A tmux row has no aplexer identity and — until aplexer can adopt
        # foreign tmux sessions — no agent state either. Explicit nulls, not
        # absent keys.
        assert row["id"] is None
        assert row["tag"] is None
        assert row["agent_state"] is None
        assert row["agent_state_source"] is None
        assert row["attached"] is False
        # CREATED is rendered in local time by tmuxctl; parsing it back must
        # round-trip through the same local zone.
        assert isinstance(row["created_epoch"], int)


def test_aplexer_only_host_emits_schema_2_rows() -> None:
    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=None,
        aplexer_payload=_load("snapshot-reported-state.json"),
        now_ms=1_788_409_006_000,
    )
    payload = session_enum.json_payload(sessions, errors)

    assert payload["schema"] == 2
    assert payload["managers"] == ["aplexer"]
    assert payload["errors"] == []
    assert {row["name"] for row in payload["sessions"]} == {
        "h1-fixture-ws:reported",
        "h1-fixture-ws:heuristic",
        "h1-fixture-ws:plain",
    }
    for row in payload["sessions"]:
        assert set(row) == SCHEMA2_ROW_KEYS
        assert row["manager"] == "aplexer"
        assert row["workspace"] == "/tmp/h1-fixture-ws"
        assert isinstance(row["id"], str) and row["id"]


def test_both_managers_are_listed_together() -> None:
    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        aplexer_payload=_load("snapshot.json"),
        now_ms=1_788_409_006_000,
    )
    payload = session_enum.json_payload(sessions, errors)

    assert payload["schema"] == 2
    assert payload["managers"] == ["tmux", "aplexer"]
    assert payload["errors"] == []
    managers = [row["manager"] for row in payload["sessions"]]
    assert managers.count("tmux") == 2
    assert managers.count("aplexer") == 4
    for row in payload["sessions"]:
        assert set(row) == SCHEMA2_ROW_KEYS


# ---------------------------------------------------------------------------
# errors[] — a failing backend is never a silently shorter list (#2426)
# ---------------------------------------------------------------------------


def test_failing_aplexer_binary_reports_an_error_and_keeps_tmux_rows(
    install_fake_a,
) -> None:
    install_fake_a(exit_code=1)

    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
    )

    # The load-bearing pair: the failure is visible AND tmux still lists.
    assert [error["manager"] for error in errors] == ["aplexer"]
    assert "failed" in errors[0]["message"]
    assert [row.name for row in sessions] == ["git-pocketshell", "git-aplexer"]

    payload = session_enum.json_payload(sessions, errors)
    assert payload["errors"] == errors
    assert payload["managers"] == ["tmux"]
    assert len(payload["sessions"]) == 2


def test_absent_aplexer_binary_is_not_an_error() -> None:
    """A tmux-only host is healthy, not broken — no error entry for it."""
    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        env={"POCKETSHELL_APLEXER": "1", "APLEXER_BIN": "", "PATH": "/nonexistent"},
    )
    assert errors == []
    assert len(sessions) == 2


def test_disabled_aplexer_kill_switch_is_not_an_error(install_fake_a) -> None:
    install_fake_a(snapshot=[])
    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        env={"POCKETSHELL_APLEXER_SESSIONS": "0"},
    )
    assert errors == []
    assert len(sessions) == 2


def test_non_list_aplexer_snapshot_reports_an_error(install_fake_a) -> None:
    install_fake_a(stdout='{"sessions": []}')
    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
    )
    assert [error["manager"] for error in errors] == ["aplexer"]
    assert "expected a list" in errors[0]["message"]
    assert len(sessions) == 2


def test_failing_tmuxctl_reports_an_error_and_keeps_aplexer_rows() -> None:
    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=None,
        tmuxctl_error="tmuxctl: tmux server unavailable",
        aplexer_payload=_load("snapshot-reported-state.json"),
        now_ms=1_788_409_006_000,
    )
    assert errors == [
        {"manager": "tmux", "message": "tmuxctl: tmux server unavailable"}
    ]
    assert {row.manager for row in sessions} == {"aplexer"}
    payload = session_enum.json_payload(sessions, errors)
    assert payload["errors"][0]["manager"] == "tmux"


def test_both_backends_failing_reports_both_errors(install_fake_a) -> None:
    install_fake_a(exit_code=1)
    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=None,
        tmuxctl_error="tmuxctl: not installed",
    )
    assert [error["manager"] for error in errors] == ["tmux", "aplexer"]
    assert sessions == []
    payload = session_enum.json_payload(sessions, errors)
    assert payload["sessions"] == []
    assert len(payload["errors"]) == 2


# ---------------------------------------------------------------------------
# aplexer agent state: reported push vs PTY-recency heuristic
# ---------------------------------------------------------------------------


def test_reported_state_row_is_authoritative_while_fresh() -> None:
    snapshot = _load("snapshot-reported-state.json")
    reported = _row(snapshot, REPORTED_ID)
    assert reported["reported_state"] == "waiting"  # real capture, not injected
    at_ms = reported["reported_state_at_ms"]

    sessions = session_enum.sessions_from_aplexer_snapshot(
        snapshot, now_ms=at_ms + 1_000
    )
    row = _by_name(sessions, "h1-fixture-ws:reported")
    assert row.agent_state == "waiting"
    assert row.agent_state_source == "reported"
    assert row.engine == "claude"
    assert row.profile == "zlaude"
    assert row.tag == "reported"
    assert row.activity_epoch == reported["last_activity_ms"] // 1000


def test_reported_state_falls_back_to_the_heuristic_once_stale() -> None:
    snapshot = _load("snapshot-reported-state.json")
    reported = _row(snapshot, REPORTED_ID)
    stale_now = (
        reported["reported_state_at_ms"]
        + session_enum.APLEXER_REPORTED_STATE_STALE_MS
        + 1
    )

    sessions = session_enum.sessions_from_aplexer_snapshot(snapshot, now_ms=stale_now)
    row = _by_name(sessions, "h1-fixture-ws:reported")
    # The push no longer wins; the source flips to the honest heuristic.
    assert row.agent_state_source == "heuristic"


def test_row_without_reported_state_uses_the_pty_recency_heuristic() -> None:
    snapshot = _load("snapshot-reported-state.json")
    heuristic = _row(snapshot, HEURISTIC_ID)
    assert "reported_state" not in heuristic  # no push was ever made here
    activity = heuristic["last_activity_ms"]

    busy = session_enum.sessions_from_aplexer_snapshot(snapshot, now_ms=activity + 500)
    row = _by_name(busy, "h1-fixture-ws:heuristic")
    assert (row.agent_state, row.agent_state_source) == ("working", "heuristic")

    quiet_now = activity + session_enum.APLEXER_ACTIVITY_THRESHOLD_MS + 1
    quiet = session_enum.sessions_from_aplexer_snapshot(snapshot, now_ms=quiet_now)
    row = _by_name(quiet, "h1-fixture-ws:heuristic")
    assert (row.agent_state, row.agent_state_source) == ("waiting", "heuristic")


def test_plain_shell_session_reports_a_null_engine() -> None:
    snapshot = _load("snapshot-reported-state.json")
    assert _row(snapshot, PLAIN_SHELL_ID)["engine"] == "shell"

    sessions = session_enum.sessions_from_aplexer_snapshot(
        snapshot, now_ms=1_788_409_006_000
    )
    row = _by_name(sessions, "h1-fixture-ws:plain")
    assert row.engine is None
    assert row.profile is None
    assert row.to_payload(schema=2)["engine"] is None


def test_exited_session_has_no_agent_state() -> None:
    snapshot = _load("snapshot.json")
    exited = [row for row in snapshot if row["phase"] == "exited"]
    assert exited, "fixture must contain a terminal-phase row"

    sessions = session_enum.sessions_from_aplexer_snapshot(
        snapshot, now_ms=1_788_409_006_000
    )
    row = _by_name(sessions, "zcode-acp:zcodex-test")
    assert row.agent_state is None
    assert row.agent_state_source is None


def test_aplexer_rows_are_not_attached_without_a_snapshot_field() -> None:
    """aplexer 0.1.1 exposes no attached-client count; the row says False."""
    snapshot = _load("snapshot.json")
    assert all("attached_clients" not in row for row in snapshot)
    sessions = session_enum.sessions_from_aplexer_snapshot(
        snapshot, now_ms=1_788_409_006_000
    )
    assert all(row.attached is False for row in sessions)

    # ...and starts telling the truth the moment aplexer grows the field.
    grown = [dict(snapshot[0], attached_clients=2)]
    assert session_enum.sessions_from_aplexer_snapshot(
        grown, now_ms=1_788_409_006_000
    )[0].attached is True


# ---------------------------------------------------------------------------
# tmux enrichment sweep
# ---------------------------------------------------------------------------


def _tmux_socket_dir() -> Path:
    return session_enum.tmux_socket_dir()


def _make_socket_dir() -> Path:
    directory = _tmux_socket_dir()
    directory.mkdir(parents=True, exist_ok=True)
    return directory


def _fake_tmux_runner(table: dict[str, str], missing_binary: bool = False):
    calls: list[list[str]] = []

    def run(argv: Sequence[str]) -> tuple[int, str, str]:
        calls.append(list(argv))
        if missing_binary:
            raise FileNotFoundError(2, "No such file or directory", "tmux")
        socket_path = argv[2]
        if socket_path in table:
            return 0, table[socket_path], ""
        return 1, "", f"no server running on {socket_path}\n"

    run.calls = calls  # type: ignore[attr-defined]
    return run


def test_tmux_rows_are_enriched_from_their_own_server() -> None:
    socket_dir = _make_socket_dir()
    runner = _fake_tmux_runner(
        {
            str(socket_dir / "tmuxctl-git-pocketshell"): (
                "git-pocketshell\t/home/alexey/git/pocketshell\t1"
                "\t1788159369\t1788381055\n"
            ),
            str(socket_dir / "tmuxctl-git-aplexer"): (
                "git-aplexer\t/home/alexey/git/aplexer\t0\t1787742566\t1788300000\n"
            ),
        }
    )

    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        include_aplexer=False,
        enrich_tmux=True,
        tmux_detail_runner=runner,
    )

    assert errors == []
    pocketshell = _by_name(sessions, "git-pocketshell")
    assert pocketshell.workspace == "/home/alexey/git/pocketshell"
    assert pocketshell.attached is True
    assert pocketshell.created_epoch == 1788159369
    assert pocketshell.activity_epoch == 1788381055

    aplexer_repo = _by_name(sessions, "git-aplexer")
    assert aplexer_repo.attached is False
    assert aplexer_repo.workspace == "/home/alexey/git/aplexer"

    payload = session_enum.json_payload(sessions, errors)
    assert payload["sessions"][0]["workspace"] == "/home/alexey/git/pocketshell"
    assert payload["sessions"][0]["attached"] is True


def test_tmux_row_without_a_reachable_server_still_lists() -> None:
    """A missed enrichment is a degraded field, never a dropped session."""
    _make_socket_dir()
    runner = _fake_tmux_runner({})

    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        include_aplexer=False,
        enrich_tmux=True,
        tmux_detail_runner=runner,
    )

    assert errors == []
    assert [row.name for row in sessions] == ["git-pocketshell", "git-aplexer"]
    row = _by_name(sessions, "git-pocketshell")
    assert row.workspace is None
    assert row.attached is False
    # The CREATED column is still parsed, so ordering data survives.
    assert row.created_epoch is not None


def test_tmux_sessions_on_the_default_socket_are_enriched_too() -> None:
    socket_dir = _make_socket_dir()
    runner = _fake_tmux_runner(
        {
            str(socket_dir / "default"): (
                "git-aplexer\t/home/alexey/git/aplexer\t2\t1787742566\t1788300000\n"
            )
        }
    )

    sessions, _errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        include_aplexer=False,
        enrich_tmux=True,
        tmux_detail_runner=runner,
    )
    row = _by_name(sessions, "git-aplexer")
    assert row.workspace == "/home/alexey/git/aplexer"
    assert row.attached is True


def test_missing_tmux_binary_is_an_enumeration_error() -> None:
    _make_socket_dir()
    runner = _fake_tmux_runner({}, missing_binary=True)

    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        include_aplexer=False,
        enrich_tmux=True,
        tmux_detail_runner=runner,
    )
    assert [error["manager"] for error in errors] == ["tmux"]
    assert "tmux session details unavailable" in errors[0]["message"]
    assert len(sessions) == 2


def test_enrichment_is_skipped_when_the_socket_directory_is_absent() -> None:
    """No tmux socket dir, no subprocess — and no phantom error either."""
    assert not _tmux_socket_dir().exists()
    runner = _fake_tmux_runner({})

    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        include_aplexer=False,
        enrich_tmux=True,
        tmux_detail_runner=runner,
    )
    assert errors == []
    assert runner.calls == []  # type: ignore[attr-defined]
    assert len(sessions) == 2


def test_tmux_socket_dir_follows_tmux_tmpdir(monkeypatch) -> None:
    monkeypatch.setenv("TMUX_TMPDIR", "/run/user/9999")
    assert session_enum.tmux_socket_dir() == Path(
        f"/run/user/9999/tmux-{os.getuid()}"
    )


def test_parse_tmux_detail_lines_ignores_malformed_rows() -> None:
    parsed = session_enum.parse_tmux_detail_lines(
        "ok\t/home/x\t0\t1\t2\n"
        "truncated\t/home/y\t0\n"
        "\n"
    )
    assert set(parsed) == {"ok"}


# ---------------------------------------------------------------------------
# schema-1 payload shape is untouched
# ---------------------------------------------------------------------------


def test_schema_1_payload_still_omits_null_keys() -> None:
    row = session_enum.LiveSession(name="s", manager="tmux")
    assert row.to_payload() == {"name": "s", "manager": "tmux"}
    assert row.to_payload(schema=1) == {"name": "s", "manager": "tmux"}
    assert set(row.to_payload(schema=2)) == SCHEMA2_ROW_KEYS


# ---------------------------------------------------------------------------
# end-to-end through the CLI
# ---------------------------------------------------------------------------


def _fake_completed(stdout: str = "", stderr: str = "", returncode: int = 0):
    class _Completed:
        def __init__(self) -> None:
            self.stdout = stdout
            self.stderr = stderr
            self.returncode = returncode

    return _Completed()


def _invoke_list_json(
    tmuxctl_stdout: str = "",
    tmuxctl_returncode: int = 0,
    tmuxctl_stderr: str = "",
    tmuxctl_binary: Optional[str] = "/fake/tmuxctl",
):
    runner = CliRunner()
    with patch(
        "pocketshell.sessions._resolve_tmuxctl_binary", return_value=tmuxctl_binary
    ), patch(
        "pocketshell.sessions.subprocess.run",
        return_value=_fake_completed(
            stdout=tmuxctl_stdout,
            stderr=tmuxctl_stderr,
            returncode=tmuxctl_returncode,
        ),
    ):
        return runner.invoke(sessions_group, ["list", "--json"])


def test_cli_list_json_emits_schema_2(install_fake_a) -> None:
    install_fake_a(snapshot=_load("snapshot-reported-state.json"))

    result = _invoke_list_json(tmuxctl_stdout=_tmuxctl_table())

    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert payload["schema"] == 2
    assert payload["managers"] == ["tmux", "aplexer"]
    assert payload["errors"] == []
    assert len(payload["sessions"]) == 5
    for row in payload["sessions"]:
        assert set(row) == SCHEMA2_ROW_KEYS


def test_cli_list_json_surfaces_a_failing_aplexer_probe(install_fake_a) -> None:
    """#2426: the phone must see the failure, not a shorter list."""
    install_fake_a(exit_code=1)

    result = _invoke_list_json(tmuxctl_stdout=_tmuxctl_table())

    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert [error["manager"] for error in payload["errors"]] == ["aplexer"]
    assert [row["name"] for row in payload["sessions"]] == [
        "git-pocketshell",
        "git-aplexer",
    ]


def test_cli_list_json_surfaces_a_failing_tmuxctl() -> None:
    result = _invoke_list_json(
        tmuxctl_returncode=4, tmuxctl_stderr="tmuxctl: tmux server unavailable\n"
    )

    payload = json.loads(result.stdout)
    assert payload["schema"] == 2
    assert [error["manager"] for error in payload["errors"]] == ["tmux"]
    assert "tmux server unavailable" in payload["errors"][0]["message"]
    assert payload["sessions"] == []


def test_cli_list_json_surfaces_a_missing_tmuxctl_binary() -> None:
    result = _invoke_list_json(tmuxctl_binary=None)

    payload = json.loads(result.stdout)
    assert [error["manager"] for error in payload["errors"]] == ["tmux"]
    assert "not installed" in payload["errors"][0]["message"]


# ---------------------------------------------------------------------------
# daemon path carries the same envelope
# ---------------------------------------------------------------------------


def _envelope(payload: dict[str, Any]) -> dict[str, Any]:
    return {"stdout": json.dumps(payload), "stderr": "", "returncode": 0}


def test_daemon_json_envelope_must_be_schema_2() -> None:
    schema2 = _envelope(
        session_enum.json_payload(
            [session_enum.LiveSession(name="s", manager="tmux")], []
        )
    )
    assert sessions_module._is_schema2_list_envelope(schema2) is True

    # A daemon still running an older PocketShell answers schema 1. Per D22
    # there is no compatibility path: the reply is malformed, so the skew
    # surfaces instead of a schema-1 body reaching a schema-2 parser.
    schema1 = _envelope({"managers": ["tmux"], "sessions": []})
    assert sessions_module._is_schema2_list_envelope(schema1) is False
    assert sessions_module._is_schema2_list_envelope(_envelope({"schema": 2})) is False
    assert (
        sessions_module._is_schema2_list_envelope(
            {"stdout": "not json", "stderr": "", "returncode": 0}
        )
        is False
    )
    assert sessions_module._is_schema2_list_envelope({"stdout": "{}"}) is False


def test_daemon_served_list_json_reaches_stdout() -> None:
    """The daemon path serves the same schema-2 document as the local one."""
    served = session_enum.json_payload(
        [
            session_enum.LiveSession(
                name="git-pocketshell",
                manager="tmux",
                workspace="/home/alexey/git/pocketshell",
                attached=True,
            )
        ],
        [session_enum.enumeration_error("aplexer", "probe failed")],
    )
    runner = CliRunner()
    with patch(
        "pocketshell.sessions._try_daemon_sessions_list",
        return_value=_envelope(served),
    ) as daemon_call, patch("pocketshell.sessions.subprocess.run") as run:
        result = runner.invoke(sessions_group, ["list", "--json"])

    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert payload["schema"] == 2
    assert payload["errors"] == [{"manager": "aplexer", "message": "probe failed"}]
    assert payload["sessions"][0]["attached"] is True
    daemon_call.assert_called_once_with(sort_by=None, extra_args=[], as_json=True)
    run.assert_not_called()


def test_daemon_handler_list_json_is_schema_2(install_fake_a) -> None:
    install_fake_a(snapshot=_load("snapshot-reported-state.json"))
    with patch(
        "pocketshell.sessions._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"
    ), patch(
        "pocketshell.sessions.subprocess.run",
        return_value=_fake_completed(stdout=_tmuxctl_table()),
    ):
        envelope = sessions_module.daemon_handler_list(
            {"extra_args": [], "as_json": True}
        )
    assert sessions_module._is_schema2_list_envelope(envelope) is True
    payload = json.loads(envelope["stdout"])
    assert payload["managers"] == ["tmux", "aplexer"]
