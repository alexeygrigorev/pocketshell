"""Unit tests for `pocketshell agent-log`.

The third-PR scope exercises:

- `pocketshell --help` lists the `agent-log` subcommand.
- `pocketshell agent-log --help` documents the engine + session +
  --tail + --json flags.
- `pocketshell agent-log --engine claude --session <id> --cwd <cwd>`
  reads from the canonical encoded-cwd directory.
- `pocketshell agent-log --engine claude --session <id>` (no --cwd)
  scans every `~/.claude/projects/*` directory and finds the first
  matching basename.
- `pocketshell agent-log --engine codex --session <id>` walks the
  date-partitioned `~/.codex/sessions/<YYYY>/<MM>/<DD>/` subtree.
- `pocketshell agent-log --engine opencode --session <id>` reads from
  `~/.local/share/opencode/<id>.jsonl`.
- `--tail N` returns only the last N rows.
- `--json` wraps the raw lines in a `{engine, session, path, count,
  lines}` envelope.
- `pocketshell agent-log handoff ...` emits a compact Markdown handoff
  prompt containing only user/assistant prose.
- The handoff path excludes tool calls/results and is bounded by
  `--max-turns` / `--max-chars`.
- A bare session id (no `.jsonl` suffix) and an explicit `<id>.jsonl`
  both resolve to the same file.
- Missing log file exits 66 (`EX_NOINPUT`) with a friendly stderr hint.
- Unknown engine is rejected by Click before any filesystem access.

The tests build the JSONL files inside a tmp_path fixture and
monkeypatch `Path.home` so each engine's resolver hits the fake
filesystem layout. No real `~/.claude` / `~/.codex` /
`~/.local/share/opencode` files are touched.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import List
from unittest.mock import patch

import pytest
from click.testing import CliRunner

from pocketshell import agent_log as agent_log_module
from pocketshell.agent_log import agent_log_command
from pocketshell.cli import cli


# ----- fixtures ------------------------------------------------------


def _write_jsonl(path: Path, events: List[dict]) -> None:
    """Write a list of dicts as one JSON object per line.

    Mirrors how Claude/Codex/OpenCode append events to disk: open the
    file, write `json.dumps(event) + "\\n"` per event. We use
    `sort_keys=True` so the byte content is deterministic for the
    `--json` envelope test.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for event in events:
            handle.write(json.dumps(event, sort_keys=True))
            handle.write("\n")


def _sample_events(prefix: str, count: int = 3) -> List[dict]:
    """Realistic-ish JSONL events; shape is intentionally generic.

    The tests assert on the raw byte content of the file, so any
    consistent shape works. Using a `prefix` lets us tell different
    fixtures apart inside a single test run.
    """
    return [{"role": "user", "text": f"{prefix}-{i}", "id": f"msg-{i}"} for i in range(count)]


@pytest.fixture
def fake_home(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """Redirect `Path.home()` to a per-test tmp directory.

    The agent-log resolvers call `Path.home()` once per request; pinning
    it here keeps the tests hermetic and avoids any chance of touching
    the developer's real `~/.claude` / `~/.codex` / `~/.local/share`.
    """
    monkeypatch.setattr(Path, "home", classmethod(lambda cls: tmp_path))
    return tmp_path


# ----- top-level CLI wiring ------------------------------------------


def test_top_level_help_lists_agent_log_subcommand() -> None:
    runner = CliRunner()
    result = runner.invoke(cli, ["--help"])
    assert result.exit_code == 0, result.output
    assert "agent-log" in result.output


def test_agent_log_help_lists_engine_session_tail_json_flags() -> None:
    runner = CliRunner()
    result = runner.invoke(agent_log_command, ["--help"])
    assert result.exit_code == 0, result.output
    lowered = result.output.lower()
    assert "--engine" in lowered
    assert "--session" in lowered
    assert "--tail" in lowered
    assert "--json" in lowered
    # The engine choices should appear in the help text so a user
    # running `--help` after a typo knows what is valid.
    assert "claude" in lowered
    assert "codex" in lowered
    assert "opencode" in lowered


def test_agent_log_handoff_help_lists_export_bounds() -> None:
    runner = CliRunner()
    result = runner.invoke(agent_log_command, ["handoff", "--help"])
    assert result.exit_code == 0, result.output
    lowered = result.output.lower()
    assert "--engine" in lowered
    assert "--session" in lowered
    assert "--max-turns" in lowered
    assert "--max-chars" in lowered
    assert "--out" in lowered


# ----- Claude resolution ---------------------------------------------


def test_claude_session_with_cwd_reads_encoded_directory(fake_home: Path) -> None:
    cwd = "/home/alexey/git/pocketshell"
    encoded = "-home-alexey-git-pocketshell"
    session = "abc123"
    events = _sample_events("claude")
    log_path = fake_home / ".claude" / "projects" / encoded / f"{session}.jsonl"
    _write_jsonl(log_path, events)

    runner = CliRunner()
    result = runner.invoke(
        agent_log_command,
        ["--engine", "claude", "--session", session, "--cwd", cwd],
    )

    assert result.exit_code == 0, result.output
    expected = "".join(json.dumps(e, sort_keys=True) + "\n" for e in events)
    assert result.output == expected


def test_claude_session_without_cwd_scans_projects_tree(fake_home: Path) -> None:
    """When `--cwd` is omitted, the resolver walks every
    `~/.claude/projects/*` directory and picks the first basename match.
    """
    session = "session-xyz"
    other_events = _sample_events("other")
    target_events = _sample_events("target", count=2)

    # Decoy directory whose basename does NOT match — must be skipped.
    _write_jsonl(
        fake_home / ".claude" / "projects" / "-some-other-project" / "different.jsonl",
        other_events,
    )
    # Real match.
    target_path = (
        fake_home / ".claude" / "projects" / "-home-alexey-git-pocketshell" / f"{session}.jsonl"
    )
    _write_jsonl(target_path, target_events)

    runner = CliRunner()
    result = runner.invoke(agent_log_command, ["--engine", "claude", "--session", session])

    assert result.exit_code == 0, result.output
    expected = "".join(json.dumps(e, sort_keys=True) + "\n" for e in target_events)
    assert result.output == expected


def test_claude_session_accepts_explicit_jsonl_suffix(fake_home: Path) -> None:
    """`--session foo.jsonl` and `--session foo` must resolve identically."""
    session_basename = "with-suffix"
    events = _sample_events("suffix")
    log_path = (
        fake_home / ".claude" / "projects" / "-home-x" / f"{session_basename}.jsonl"
    )
    _write_jsonl(log_path, events)

    runner = CliRunner()
    with_suffix = runner.invoke(
        agent_log_command,
        ["--engine", "claude", "--session", f"{session_basename}.jsonl", "--cwd", "/home/x"],
    )
    without_suffix = runner.invoke(
        agent_log_command,
        ["--engine", "claude", "--session", session_basename, "--cwd", "/home/x"],
    )
    assert with_suffix.exit_code == 0, with_suffix.output
    assert without_suffix.exit_code == 0, without_suffix.output
    assert with_suffix.output == without_suffix.output


# ----- Codex resolution ----------------------------------------------


def test_codex_session_walks_date_partitioned_tree(fake_home: Path) -> None:
    """Codex stores sessions under `<YYYY>/<MM>/<DD>/`; the resolver
    must walk the subtree rather than requiring the caller to know the
    partition.
    """
    session = "rollout-abc"
    events = _sample_events("codex")
    log_path = fake_home / ".codex" / "sessions" / "2026" / "05" / "22" / f"{session}.jsonl"
    _write_jsonl(log_path, events)

    runner = CliRunner()
    result = runner.invoke(agent_log_command, ["--engine", "codex", "--session", session])

    assert result.exit_code == 0, result.output
    expected = "".join(json.dumps(e, sort_keys=True) + "\n" for e in events)
    assert result.output == expected


def test_codex_ignores_cwd_argument(fake_home: Path) -> None:
    """`--cwd` is accepted for symmetry with claude but ignored for
    codex (whose tree is date-keyed, not cwd-keyed). The file must
    still be found even if `--cwd` is bogus.
    """
    session = "rollout-xyz"
    events = _sample_events("codex-cwd")
    log_path = fake_home / ".codex" / "sessions" / "2026" / "05" / "22" / f"{session}.jsonl"
    _write_jsonl(log_path, events)

    runner = CliRunner()
    result = runner.invoke(
        agent_log_command,
        ["--engine", "codex", "--session", session, "--cwd", "/nonexistent"],
    )

    assert result.exit_code == 0, result.output
    expected = "".join(json.dumps(e, sort_keys=True) + "\n" for e in events)
    assert result.output == expected


# ----- OpenCode resolution -------------------------------------------


def test_opencode_session_reads_local_share_directory(fake_home: Path) -> None:
    session = "opencode-session"
    events = _sample_events("opencode")
    log_path = fake_home / ".local" / "share" / "opencode" / f"{session}.jsonl"
    _write_jsonl(log_path, events)

    runner = CliRunner()
    result = runner.invoke(agent_log_command, ["--engine", "opencode", "--session", session])

    assert result.exit_code == 0, result.output
    expected = "".join(json.dumps(e, sort_keys=True) + "\n" for e in events)
    assert result.output == expected


# ----- --tail bound --------------------------------------------------


def test_tail_returns_only_last_n_rows(fake_home: Path) -> None:
    session = "tail-session"
    events = _sample_events("t", count=10)
    log_path = (
        fake_home / ".claude" / "projects" / "-home-y" / f"{session}.jsonl"
    )
    _write_jsonl(log_path, events)

    runner = CliRunner()
    result = runner.invoke(
        agent_log_command,
        [
            "--engine",
            "claude",
            "--session",
            session,
            "--cwd",
            "/home/y",
            "--tail",
            "3",
        ],
    )

    assert result.exit_code == 0, result.output
    expected_lines = [json.dumps(e, sort_keys=True) for e in events[-3:]]
    assert result.output == "\n".join(expected_lines) + "\n"


def test_tail_larger_than_file_returns_all_rows(fake_home: Path) -> None:
    """`--tail 50` on a 3-line file must emit all 3 rows, not error."""
    session = "small-session"
    events = _sample_events("s", count=3)
    log_path = (
        fake_home / ".claude" / "projects" / "-home-z" / f"{session}.jsonl"
    )
    _write_jsonl(log_path, events)

    runner = CliRunner()
    result = runner.invoke(
        agent_log_command,
        [
            "--engine",
            "claude",
            "--session",
            session,
            "--cwd",
            "/home/z",
            "--tail",
            "50",
        ],
    )

    assert result.exit_code == 0, result.output
    expected = "".join(json.dumps(e, sort_keys=True) + "\n" for e in events)
    assert result.output == expected


# ----- --json envelope -----------------------------------------------


def test_json_envelope_wraps_raw_lines(fake_home: Path) -> None:
    session = "json-session"
    events = _sample_events("j", count=4)
    log_path = (
        fake_home / ".claude" / "projects" / "-home-w" / f"{session}.jsonl"
    )
    _write_jsonl(log_path, events)

    runner = CliRunner()
    result = runner.invoke(
        agent_log_command,
        [
            "--engine",
            "claude",
            "--session",
            session,
            "--cwd",
            "/home/w",
            "--json",
        ],
    )

    assert result.exit_code == 0, result.output
    envelope = json.loads(result.output)
    assert envelope["engine"] == "claude"
    assert envelope["session"] == session  # `.jsonl` suffix stripped
    assert envelope["path"] == str(log_path)
    assert envelope["count"] == 4
    # `lines` carries the raw JSONL strings VERBATIM (not parsed).
    assert envelope["lines"] == [json.dumps(e, sort_keys=True) for e in events]


def test_json_envelope_with_tail(fake_home: Path) -> None:
    """`--json --tail N` produces an envelope whose `count` is bounded
    by N and whose `lines` is the last N raw rows.
    """
    session = "json-tail-session"
    events = _sample_events("jt", count=10)
    log_path = (
        fake_home / ".codex" / "sessions" / "2026" / "05" / "23" / f"{session}.jsonl"
    )
    _write_jsonl(log_path, events)

    runner = CliRunner()
    result = runner.invoke(
        agent_log_command,
        ["--engine", "codex", "--session", session, "--json", "--tail", "2"],
    )

    assert result.exit_code == 0, result.output
    envelope = json.loads(result.output)
    assert envelope["engine"] == "codex"
    assert envelope["count"] == 2
    assert envelope["lines"] == [json.dumps(e, sort_keys=True) for e in events[-2:]]


# ----- handoff export ------------------------------------------------


def test_handoff_claude_filters_tool_calls_results_and_system_notes(fake_home: Path) -> None:
    session = "handoff-claude"
    events = [
        {
            "type": "user",
            "uuid": "u1",
            "message": {
                "role": "user",
                "content": (
                    "<system-reminder>The date has changed.</system-reminder>\n"
                    "Please inspect the failing test."
                ),
            },
        },
        {
            "type": "assistant",
            "uuid": "a1",
            "message": {
                "role": "assistant",
                "content": [
                    {"type": "text", "text": "I'll inspect the focused test."},
                    {
                        "type": "tool_use",
                        "id": "toolu_1",
                        "name": "Bash",
                        "input": {"command": "pytest -q"},
                    },
                    {
                        "type": "tool_result",
                        "tool_use_id": "toolu_1",
                        "content": "FAILED test output",
                        "is_error": True,
                    },
                    {"type": "text", "text": "The likely fix is in the parser."},
                ],
            },
        },
    ]
    log_path = fake_home / ".claude" / "projects" / "-home-handoff" / f"{session}.jsonl"
    _write_jsonl(log_path, events)

    runner = CliRunner()
    result = runner.invoke(
        agent_log_command,
        ["handoff", "--engine", "claude", "--session", session, "--cwd", "/home/handoff"],
    )

    assert result.exit_code == 0, result.output
    assert "Read this previous agent conversation" in result.output
    assert "### User" in result.output
    assert "Please inspect the failing test." in result.output
    assert "### Assistant" in result.output
    assert "I'll inspect the focused test." in result.output
    assert "The likely fix is in the parser." in result.output
    assert "toolu_1" not in result.output
    assert "pytest -q" not in result.output
    assert "FAILED test output" not in result.output
    assert "system-reminder" not in result.output
    assert "The date has changed" not in result.output


def test_handoff_codex_filters_function_calls_and_bounds_turns(fake_home: Path) -> None:
    session = "handoff-codex"
    events = [
        {"type": "event_msg", "payload": {"type": "user_message", "message": "first user"}},
        {"type": "event_msg", "payload": {"type": "agent_message", "message": "first answer"}},
        {
            "type": "response_item",
            "payload": {
                "type": "function_call",
                "call_id": "call_1",
                "name": "shell",
                "arguments": "{\"cmd\":\"cat secrets.txt\"}",
            },
        },
        {
            "type": "response_item",
            "payload": {
                "type": "function_call_output",
                "call_id": "call_1",
                "output": "raw command output",
            },
        },
        {
            "type": "response_item",
            "payload": {
                "type": "message",
                "id": "m2",
                "role": "assistant",
                "content": [{"type": "output_text", "text": "second answer"}],
            },
        },
        {"type": "event_msg", "payload": {"type": "user_message", "message": "second user"}},
    ]
    log_path = fake_home / ".codex" / "sessions" / "2026" / "06" / "06" / f"{session}.jsonl"
    _write_jsonl(log_path, events)

    runner = CliRunner()
    result = runner.invoke(
        agent_log_command,
        ["handoff", "--engine", "codex", "--session", session, "--max-turns", "2"],
    )

    assert result.exit_code == 0, result.output
    assert "[2 earlier message(s) omitted by --max-turns.]" in result.output
    assert "second answer" in result.output
    assert "second user" in result.output
    assert "first user" not in result.output
    assert "first answer" not in result.output
    assert "cat secrets.txt" not in result.output
    assert "raw command output" not in result.output


def test_handoff_respects_max_chars(fake_home: Path) -> None:
    session = "handoff-chars"
    events = [
        {
            "type": "event_msg",
            "payload": {"type": "user_message", "message": "start " + "x" * 1200},
        },
        {
            "type": "event_msg",
            "payload": {"type": "agent_message", "message": "answer " + "y" * 1200},
        },
    ]
    log_path = fake_home / ".codex" / "sessions" / "2026" / "06" / "06" / f"{session}.jsonl"
    _write_jsonl(log_path, events)

    runner = CliRunner()
    result = runner.invoke(
        agent_log_command,
        ["handoff", "--engine", "codex", "--session", session, "--max-chars", "900"],
    )

    assert result.exit_code == 0, result.output
    assert len(result.output) <= 900
    assert "omitted to fit --max-chars" in result.output


def test_handoff_opencode_writes_out_file(fake_home: Path, tmp_path: Path) -> None:
    session = "handoff-opencode"
    events = [
        {
            "message_id": "m1",
            "message_role": "user",
            "part_id": "p1",
            "part_data": json.dumps({"type": "input_text", "text": "open the issue"}),
        },
        {
            "message_id": "m2",
            "message_role": "assistant",
            "part_id": "p2",
            "part_data": json.dumps({"type": "output_text", "text": "I read the issue."}),
        },
        {
            "message_id": "m3",
            "message_role": "assistant",
            "part_id": "p3",
            "part_data": json.dumps(
                {"type": "tool", "tool": "shell", "state": "UNIQUE_OPCODE_PAYLOAD"}
            ),
        },
    ]
    log_path = fake_home / ".local" / "share" / "opencode" / f"{session}.jsonl"
    _write_jsonl(log_path, events)
    out = tmp_path / "handoff.md"

    runner = CliRunner()
    result = runner.invoke(
        agent_log_command,
        ["handoff", "--engine", "opencode", "--session", session, "--out", str(out)],
    )

    assert result.exit_code == 0, result.output
    assert result.output == ""
    text = out.read_text(encoding="utf-8")
    assert "open the issue" in text
    assert "I read the issue." in text
    assert "shell" not in text
    assert "UNIQUE_OPCODE_PAYLOAD" not in text


# ----- missing-log handling ------------------------------------------


def test_missing_log_exits_66_with_stderr_hint(fake_home: Path) -> None:
    runner = CliRunner()
    result = runner.invoke(
        agent_log_command,
        ["--engine", "claude", "--session", "does-not-exist", "--cwd", "/nowhere"],
        catch_exceptions=False,
    )
    # 66 == EX_NOINPUT, distinct from 127 (binary missing) so a
    # consumer can tell "wrong session id" apart from "binary missing".
    assert result.exit_code == 66, result.output
    assert "no claude session log found" in result.output.lower()


def test_missing_log_for_codex_mentions_codex_root(fake_home: Path) -> None:
    """The error hint must name the engine searched so the user knows
    where to look.
    """
    runner = CliRunner()
    result = runner.invoke(
        agent_log_command,
        ["--engine", "codex", "--session", "missing-rollout"],
        catch_exceptions=False,
    )
    assert result.exit_code == 66, result.output
    assert "codex" in result.output.lower()


def test_missing_log_for_opencode_mentions_opencode_root(fake_home: Path) -> None:
    runner = CliRunner()
    result = runner.invoke(
        agent_log_command,
        ["--engine", "opencode", "--session", "nope"],
        catch_exceptions=False,
    )
    assert result.exit_code == 66, result.output
    assert "opencode" in result.output.lower()


# ----- bad invocation ------------------------------------------------


def test_unknown_engine_rejected_by_click(fake_home: Path) -> None:
    """Click's `Choice` validation must fire BEFORE we touch the
    filesystem; otherwise an unknown engine could cause a confusing
    `EX_NOINPUT` error instead of a clean usage error.
    """
    # If the resolver were reached, this monkeypatch would crash the
    # test (no real `_resolve_log_path` call should happen).
    with patch.object(agent_log_module, "_resolve_log_path") as resolve:
        runner = CliRunner()
        result = runner.invoke(
            agent_log_command,
            ["--engine", "bogus", "--session", "anything"],
        )
    assert result.exit_code != 0
    resolve.assert_not_called()


def test_missing_session_flag_rejected_by_click() -> None:
    runner = CliRunner()
    result = runner.invoke(agent_log_command, ["--engine", "claude"])
    assert result.exit_code != 0
    assert "session" in result.output.lower()


def test_missing_engine_flag_rejected_by_click() -> None:
    runner = CliRunner()
    result = runner.invoke(agent_log_command, ["--session", "abc"])
    assert result.exit_code != 0
    assert "engine" in result.output.lower()


# ----- engine name case insensitivity --------------------------------


def test_engine_choice_is_case_insensitive(fake_home: Path) -> None:
    """Click's `type=Choice(case_sensitive=False)` lets a user pass
    `--engine CLAUDE` and still hit the claude resolver.
    """
    session = "uppercase-engine"
    events = _sample_events("upper")
    log_path = (
        fake_home / ".claude" / "projects" / "-home-uc" / f"{session}.jsonl"
    )
    _write_jsonl(log_path, events)

    runner = CliRunner()
    result = runner.invoke(
        agent_log_command,
        ["--engine", "CLAUDE", "--session", session, "--cwd", "/home/uc"],
    )

    assert result.exit_code == 0, result.output
    expected = "".join(json.dumps(e, sort_keys=True) + "\n" for e in events)
    assert result.output == expected


# ----- path-traversal containment (issue #774 §2) --------------------
#
# `--session` / `--cwd` are app-supplied over SSH. Before #774 the raw
# value was joined straight into a Path, so a `..`-laden session or cwd
# escaped the per-engine log root and `agent-log` could `tail` any
# `*.jsonl`-suffixed file the host user could read. These tests pin the
# containment guard: a traversal MUST NOT resolve to a file outside the
# intended root, even when that file physically exists.
#
# Each test plants a REAL secret `.jsonl` at the exact location the
# unguarded resolver would escape to (outside the per-engine root but
# inside the hermetic `fake_home` tmp dir), so on the unfixed code the
# resolver genuinely returns it (red) and after the fix it is contained
# (green).


def _write_secret_jsonl(path: Path) -> Path:
    """Create a real `.jsonl` exfil target at ``path``."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text('{"role": "user", "text": "TOP-SECRET"}\n', encoding="utf-8")
    return path


def test_claude_session_traversal_with_cwd_is_rejected(fake_home: Path) -> None:
    """`--cwd /x --session ../../../escape` must not escape the root.

    From `<home>/.claude/projects/-x/`, three `../` segments climb back to
    `<home>` (== fake_home), where a real secret is planted. The unguarded
    resolver returns it; the guard must refuse.
    """
    # The encoded-cwd project dir must physically exist so the literal
    # (unresolved) `..` path is reachable by `is_file()`.
    proj = fake_home / ".claude" / "projects" / "-x"
    proj.mkdir(parents=True)
    secret = _write_secret_jsonl(fake_home / "escape.jsonl")
    traversal = "../../../escape"  # lands at <home>/escape.jsonl

    # Demonstrate the file is genuinely reachable by raw path-join (so the
    # guard, not a missing file, is what blocks it).
    raw = proj / f"{traversal}.jsonl"
    assert raw.resolve() == secret.resolve()
    assert raw.is_file()

    assert agent_log_module._resolve_claude_path(traversal, "/x") is None

    runner = CliRunner()
    result = runner.invoke(
        agent_log_command,
        ["--engine", "claude", "--session", traversal, "--cwd", "/x"],
    )
    assert result.exit_code == 66, result.output
    assert "TOP-SECRET" not in result.output


def test_claude_session_traversal_without_cwd_is_rejected(fake_home: Path) -> None:
    """The no-`--cwd` scan path joins the raw session under each project
    dir; a `..` chain must be refused there too.
    """
    # A real project dir must exist for the scan loop to iterate.
    proj = fake_home / ".claude" / "projects" / "-real"
    proj.mkdir(parents=True)
    secret = _write_secret_jsonl(fake_home / "escape2.jsonl")
    # From <home>/.claude/projects/-real/, three ../ reach <home>.
    traversal = "../../../escape2"
    raw = proj / f"{traversal}.jsonl"
    assert raw.resolve() == secret.resolve() and raw.is_file()

    assert agent_log_module._resolve_claude_path(traversal, None) is None


def test_codex_session_traversal_is_rejected(fake_home: Path) -> None:
    """Codex `rglob`s its tree by the session filename; a `..`-laden name
    still resolves to a traversal path, so it must be contained.
    """
    sessions = fake_home / ".codex" / "sessions"
    sessions.mkdir(parents=True)
    secret = _write_secret_jsonl(fake_home / "codexescape.jsonl")
    # From <home>/.codex/sessions/, two ../ reach <home>.
    traversal = "../../codexescape"
    # rglob genuinely surfaces the traversal target on the unfixed code.
    matches = list(sessions.rglob(f"{traversal}.jsonl"))
    assert matches and matches[0].resolve() == secret.resolve()

    assert agent_log_module._resolve_codex_path(traversal) is None


def test_opencode_session_traversal_is_rejected(fake_home: Path) -> None:
    """OpenCode joins `--session` directly under its root; a `..` chain
    must not escape `~/.local/share/opencode/`.
    """
    root = fake_home / ".local" / "share" / "opencode"
    root.mkdir(parents=True)
    secret = _write_secret_jsonl(fake_home / "ocescape.jsonl")
    # From <home>/.local/share/opencode/, three ../ reach <home>.
    traversal = "../../../ocescape"
    raw = root / f"{traversal}.jsonl"
    assert raw.resolve() == secret.resolve() and raw.is_file()

    assert agent_log_module._resolve_opencode_path(traversal) is None


def test_claude_cwd_traversal_is_rejected(fake_home: Path) -> None:
    """A `..`-laden `--cwd` must not escape the projects root.

    `_encode_claude_cwd` only rewrites `/` -> `-`, leaving `..` segments
    intact, so an unguarded `--cwd` chain `resolve()`s above the root.
    """
    # encoded cwd "-..-..-..-.." => projects/-..-..-..-../<session>.jsonl
    secret = _write_secret_jsonl(fake_home / ".claude" / "viacwd.jsonl")
    # cwd "/../../../.." encodes to "-..-..-..-..": from projects/ that dir
    # walks up to <home>/.claude where the secret sits.
    resolved = agent_log_module._resolve_claude_path("viacwd", "/../../../..")
    # On the unfixed code this could surface the planted secret; the guard
    # must return None.
    assert resolved is None
    assert secret.is_file()


def test_absolute_session_is_rejected(fake_home: Path, tmp_path: Path) -> None:
    """An absolute `--session` path must be refused for every engine.

    `Path(root) / "/etc/foo.jsonl"` discards `root` entirely in pathlib,
    so without a guard an absolute session reads straight off the host fs.
    """
    outside = tmp_path.parent / "abs_secret.jsonl"
    secret = _write_secret_jsonl(outside)
    abs_session = str(secret)  # absolute, ends in .jsonl, real file
    assert secret.is_file()
    assert agent_log_module._resolve_claude_path(abs_session, "/x") is None
    assert agent_log_module._resolve_codex_path(abs_session) is None
    assert agent_log_module._resolve_opencode_path(abs_session) is None


def test_legitimate_session_through_symlinked_home_still_resolves(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A real home reached via a symlink must still resolve (top risk a).

    The containment check `resolve()`s both sides, so a symlinked home
    does not falsely trip the guard for a legitimate in-root session.
    """
    real_home = tmp_path / "real_home"
    real_home.mkdir()
    link_home = tmp_path / "link_home"
    link_home.symlink_to(real_home, target_is_directory=True)
    monkeypatch.setattr(Path, "home", classmethod(lambda cls: link_home))

    session = "legit"
    events = _sample_events("legit")
    log_path = real_home / ".claude" / "projects" / "-home-uc" / f"{session}.jsonl"
    _write_jsonl(log_path, events)

    resolved = agent_log_module._resolve_claude_path(session, "/home/uc")
    assert resolved is not None
    assert resolved.resolve() == log_path.resolve()
