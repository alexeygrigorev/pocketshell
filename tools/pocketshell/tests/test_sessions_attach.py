"""Aplexer-only attach contract tests."""

from __future__ import annotations

from click.testing import CliRunner

from pocketshell import aplexer, sessions, session_enum


def _row(identifier: str = "b3feff71-4a78-4055-a2d3-6c99187ecffb") -> session_enum.LiveSession:
    return session_enum.LiveSession(name="project:shell", aplexer_id=identifier)


def _resolution(path: str | None) -> aplexer.AplexerResolution:
    return aplexer.AplexerResolution(path=path, tried=(path or "missing",))


def test_attach_execs_the_resolved_aplexer_binary(monkeypatch) -> None:
    monkeypatch.setattr(sessions, "_attach_live_rows", lambda: ([_row()], []))
    monkeypatch.setattr(sessions, "_resolve_aplexer", lambda: _resolution("/fake/a"))
    executed: list[list[str]] = []
    monkeypatch.setattr(sessions, "_exec", lambda argv: executed.append(argv))

    result = CliRunner().invoke(sessions.sessions_group, ["attach", "project:shell"])

    assert result.exit_code == 0, result.output
    assert executed == [["/fake/a", "attach", "b3feff71-4a78-4055-a2d3-6c99187ecffb"]]


def test_attach_fails_loudly_when_listing_cannot_reach_aplexer(monkeypatch) -> None:
    monkeypatch.setattr(
        sessions,
        "_attach_live_rows",
        lambda: ([], [{"message": "aplexer is unavailable"}]),
    )

    result = CliRunner().invoke(sessions.sessions_group, ["attach", "project:shell"])

    assert result.exit_code == 127
    assert "aplexer is unavailable" in result.output


def test_attach_rejects_ambiguous_id_prefix(monkeypatch) -> None:
    monkeypatch.setattr(
        sessions,
        "_attach_live_rows",
        lambda: ([_row("12345678-a"), _row("12345678-b")], []),
    )

    result = CliRunner().invoke(sessions.sessions_group, ["attach", "12345678"])

    assert result.exit_code == sessions.ATTACH_EXIT_AMBIGUOUS
    assert "ambiguous" in result.output
