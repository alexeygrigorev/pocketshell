"""CLI-level tests for the aplexer-only sessions group."""

from __future__ import annotations

import json

from click.testing import CliRunner

from pocketshell import sessions, session_enum


def test_help_exposes_only_the_four_session_lifecycle_commands() -> None:
    result = CliRunner().invoke(sessions.sessions_group, ["--help"])
    assert result.exit_code == 0, result.output
    for command in ("list", "create", "attach", "kill"):
        assert command in result.output
    assert "resumable" not in result.output
    assert "resume" not in result.output


def test_list_json_is_schema_three_and_aplexer_only(monkeypatch) -> None:
    row = session_enum.LiveSession(name="project:shell", aplexer_id="id-1")
    monkeypatch.setattr(
        sessions,
        "_try_daemon_sessions_list",
        lambda **_: None,
    )
    monkeypatch.setattr(
        sessions._session_enum,
        "enumerate_live_sessions",
        lambda: ([row], []),
    )

    result = CliRunner().invoke(sessions.sessions_group, ["list", "--json"])

    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert payload["schema"] == 3
    assert "managers" not in payload
    assert "manager" not in payload["sessions"][0]


def test_list_returns_nonzero_when_aplexer_is_unavailable(monkeypatch) -> None:
    monkeypatch.setattr(
        sessions._session_enum,
        "enumerate_live_sessions",
        lambda: ([], [{"message": "aplexer is unavailable"}]),
    )
    monkeypatch.setattr(sessions, "_try_daemon_sessions_list", lambda **_: None)

    result = CliRunner().invoke(sessions.sessions_group, ["list", "--json"])

    assert result.exit_code == 127
    assert "aplexer is unavailable" in result.output
