"""Aplexer-only lifecycle contract tests for ``pocketshell sessions``."""

from __future__ import annotations

import json
from pathlib import Path

from click.testing import CliRunner

from pocketshell import aplexer, sessions


def _resolution(path: str | None) -> aplexer.AplexerResolution:
    return aplexer.AplexerResolution(
        path=path,
        source="test" if path else None,
        worker="/fake/aplexer" if path else None,
        tried=(path or "missing",),
    )


def _record() -> dict[str, object]:
    return {
        "id": "b3feff71-4a78-4055-a2d3-6c99187ecffb",
        "workspace": "/work/project",
        "tag": "shell",
        "phase": "running",
        "worker_alive": True,
        "worker_pid": 1234,
    }


def test_create_uses_only_aplexer_and_emits_schema_three(monkeypatch, tmp_path: Path) -> None:
    calls: list[list[str]] = []
    monkeypatch.setattr(sessions, "_resolve_aplexer", lambda: _resolution("/fake/a"))
    monkeypatch.setattr(sessions, "_aplexer_snapshot", lambda: [])
    monkeypatch.setattr(sessions._memcap, "resolve_session_mem_bytes", lambda **_: 123)

    def start(argv):
        calls.append(list(argv))
        return 0, json.dumps(_record()), ""

    monkeypatch.setattr(sessions, "_run_aplexer", start)
    result = CliRunner().invoke(
        sessions.sessions_group,
        ["create", "shell", "--cwd", str(tmp_path), "--engine", "codex", "--json"],
    )

    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert payload == {
        "schema": 3,
        "name": "project:shell",
        "id": _record()["id"],
        "created": True,
    }
    assert calls == [[
        "/fake/a", "--json", "start", "--workspace", str(tmp_path),
        "--tag", "shell", "--engine", "codex", "--memory", "123",
    ]]
    assert "backend" not in result.output.lower()


def test_create_reuses_a_live_record_without_starting_again(monkeypatch, tmp_path: Path) -> None:
    starts: list[list[str]] = []
    monkeypatch.setattr(sessions, "_resolve_aplexer", lambda: _resolution("/fake/a"))
    monkeypatch.setattr(
        sessions,
        "_aplexer_snapshot",
        lambda: [{**_record(), "workspace": str(tmp_path)}],
    )
    monkeypatch.setattr(sessions._memcap, "resolve_session_mem_bytes", lambda **_: 123)
    monkeypatch.setattr(sessions, "_run_aplexer", lambda argv: starts.append(list(argv)))

    result = CliRunner().invoke(
        sessions.sessions_group,
        ["create", "shell", "--cwd", str(tmp_path), "--json"],
    )

    assert result.exit_code == 0, result.output
    assert json.loads(result.output)["created"] is False
    assert starts == []


def test_create_fails_loudly_when_aplexer_cannot_resolve(monkeypatch) -> None:
    monkeypatch.setattr(sessions, "_resolve_aplexer", lambda: _resolution(None))
    result = CliRunner().invoke(sessions.sessions_group, ["create", "shell", "--json"])

    assert result.exit_code == 127
    assert "could not resolve" in result.output
