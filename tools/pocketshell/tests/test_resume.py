"""Unit tests for `pocketshell.resume` + the `sessions resumable|resume` CLI.

Covers issue #725 acceptance criteria with synthetic fixtures (claude/codex
JSONL written to a tmp dir, a tiny on-disk sqlite db for opencode):

- per-engine discovery + label extraction (skipping `<…>` wrappers)
- codex sub-agent (`thread_source: "subagent"`) filtering
- opencode child-session (`parent_id`) filtering + read-only open
- merge/sort newest-first with stable tie-breaking
- cwd / engine / -n filtering
- live-dedupe (`(running)`) against fake tmux panes
- the resume-command builder per engine (claude/codex/opencode, incl. the
  off-PATH opencode binary fallback)
- the `resumable` / `resume` Click commands (routing to tmuxctl)
- empty stores => empty list, no crash
"""

from __future__ import annotations

import json
import sqlite3
from pathlib import Path
from unittest.mock import patch

from click.testing import CliRunner

from pocketshell import resume
from pocketshell.resume import ResumableSession
from pocketshell.sessions import sessions_group


# ---------------------------------------------------------------------------
# fixture builders
# ---------------------------------------------------------------------------


def _write_claude(projects_dir: Path, sanitized: str, uuid: str, records: list[dict]) -> Path:
    proj = projects_dir / sanitized
    proj.mkdir(parents=True, exist_ok=True)
    path = proj / f"{uuid}.jsonl"
    path.write_text("\n".join(json.dumps(r) for r in records) + "\n", encoding="utf-8")
    return path


def _write_codex(sessions_dir: Path, date_parts: tuple[str, str, str], name: str, records: list[dict]) -> Path:
    day = sessions_dir.joinpath(*date_parts)
    day.mkdir(parents=True, exist_ok=True)
    path = day / name
    path.write_text("\n".join(json.dumps(r) for r in records) + "\n", encoding="utf-8")
    return path


def _make_opencode_db(path: Path, rows: list[dict]) -> None:
    con = sqlite3.connect(path)
    con.execute(
        "CREATE TABLE session ("
        "id TEXT PRIMARY KEY, project_id TEXT, parent_id TEXT, slug TEXT, "
        "directory TEXT, title TEXT, time_created INTEGER, time_updated INTEGER)"
    )
    for row in rows:
        con.execute(
            "INSERT INTO session (id, parent_id, directory, title, time_created, time_updated) "
            "VALUES (?, ?, ?, ?, ?, ?)",
            (
                row["id"],
                row.get("parent_id"),
                row["directory"],
                row.get("title", ""),
                row.get("time_created", 0),
                row["time_updated"],
            ),
        )
    con.commit()
    con.close()


# ---------------------------------------------------------------------------
# claude discovery
# ---------------------------------------------------------------------------


def test_discover_claude_extracts_cwd_label_and_skips_wrappers(tmp_path: Path) -> None:
    projects = tmp_path / "projects"
    _write_claude(
        projects,
        "-home-alexey-git-foo",
        "11111111-aaaa-bbbb-cccc-000000000001",
        [
            {"type": "mode", "mode": "normal"},
            # wrapper user turn (starts with `<`) must be skipped for the label
            {"type": "user", "cwd": "/home/alexey/git/foo",
             "message": {"content": "<command-name>/clear</command-name>"}},
            {"type": "user", "cwd": "/home/alexey/git/foo",
             "message": {"content": "fix the failing build in foo"}},
        ],
    )
    sessions = resume.discover_claude(projects)
    assert len(sessions) == 1
    s = sessions[0]
    assert s.engine == "claude"
    assert s.session_id == "11111111-aaaa-bbbb-cccc-000000000001"
    assert s.cwd == "/home/alexey/git/foo"
    assert s.project == "foo"
    assert s.label == "fix the failing build in foo"


def test_discover_claude_handles_list_content_blocks(tmp_path: Path) -> None:
    projects = tmp_path / "projects"
    _write_claude(
        projects,
        "-p",
        "22222222-aaaa-bbbb-cccc-000000000002",
        [
            {"type": "user", "cwd": "/p",
             "message": {"content": [{"type": "text", "text": "hello block label"}]}},
        ],
    )
    sessions = resume.discover_claude(projects)
    assert sessions[0].label == "hello block label"


def test_discover_claude_empty_store_returns_empty(tmp_path: Path) -> None:
    assert resume.discover_claude(tmp_path / "missing") == []


# ---------------------------------------------------------------------------
# codex discovery + sub-agent filtering
# ---------------------------------------------------------------------------


def _codex_records(session_id: str, cwd: str, thread_source: str, prompt: str) -> list[dict]:
    return [
        {"type": "session_meta", "payload": {
            "id": session_id, "cwd": cwd, "thread_source": thread_source}},
        # wrapper turns first
        {"type": "response_item", "payload": {
            "type": "message", "role": "user",
            "content": [{"type": "input_text", "text": "<environment_context>x</environment_context>"}]}},
        {"type": "response_item", "payload": {
            "type": "message", "role": "user",
            "content": [{"type": "input_text", "text": prompt}]}},
    ]


def test_discover_codex_extracts_and_filters_subagents(tmp_path: Path) -> None:
    base = tmp_path / "sessions"
    _write_codex(
        base, ("2026", "05", "16"),
        "rollout-2026-05-16T23-18-02-aaa.jsonl",
        _codex_records("019e32a7-user", "/home/alexey/git/bar", "user", "analyze the code"),
    )
    _write_codex(
        base, ("2026", "05", "16"),
        "rollout-2026-05-16T23-30-00-bbb.jsonl",
        _codex_records("019e32a7-sub", "/home/alexey/git/bar", "subagent", "worker task"),
    )
    sessions = resume.discover_codex(base)
    assert len(sessions) == 1
    s = sessions[0]
    assert s.engine == "codex"
    assert s.session_id == "019e32a7-user"
    assert s.cwd == "/home/alexey/git/bar"
    assert s.label == "analyze the code"


def test_discover_codex_skips_agents_md_instruction_preamble(tmp_path: Path) -> None:
    """codex replays AGENTS.md as a role=user msg; it is not the real prompt."""
    base = tmp_path / "sessions"
    _write_codex(
        base, ("2026", "05", "02"),
        "rollout-2026-05-02T07-43-56-ccc.jsonl",
        [
            {"type": "session_meta", "payload": {
                "id": "019de737-real", "cwd": "/home/alexey/git/cmp", "thread_source": "user"}},
            {"type": "response_item", "payload": {
                "type": "message", "role": "user",
                "content": [{"type": "input_text",
                             "text": "# AGENTS.md instructions for /home/alexey/git/cmp\n\n<INSTRUCTIONS>\nuse uv\n</INSTRUCTIONS>"}]}},
            {"type": "response_item", "payload": {
                "type": "message", "role": "user",
                "content": [{"type": "input_text", "text": "<environment_context>x</environment_context>"}]}},
            {"type": "response_item", "payload": {
                "type": "message", "role": "user",
                "content": [{"type": "input_text", "text": "add a button to the homework view"}]}},
        ],
    )
    sessions = resume.discover_codex(base)
    assert len(sessions) == 1
    assert sessions[0].label == "add a button to the homework view"


def test_discover_codex_empty_store_returns_empty(tmp_path: Path) -> None:
    assert resume.discover_codex(tmp_path / "missing") == []


# ---------------------------------------------------------------------------
# opencode discovery (read-only) + child filtering
# ---------------------------------------------------------------------------


def test_discover_opencode_reads_db_and_filters_children(tmp_path: Path) -> None:
    db = tmp_path / "opencode.db"
    _make_opencode_db(db, [
        {"id": "ses_top", "directory": "/home/alexey/git/zoo",
         "title": "Top conversation", "time_updated": 1780502651550},
        {"id": "ses_child", "parent_id": "ses_top", "directory": "/home/alexey/git/zoo",
         "title": "subagent run", "time_updated": 1780502237734},
    ])
    sessions = resume.discover_opencode(db)
    assert len(sessions) == 1
    s = sessions[0]
    assert s.engine == "opencode"
    assert s.session_id == "ses_top"
    assert s.cwd == "/home/alexey/git/zoo"
    assert s.label == "Top conversation"
    # ms -> s conversion
    assert abs(s.last_activity - 1780502651.550) < 0.01


def test_discover_opencode_missing_db_returns_empty(tmp_path: Path) -> None:
    assert resume.discover_opencode(tmp_path / "nope.db") == []


def test_discover_opencode_opens_read_only(tmp_path: Path) -> None:
    """Opening read-only/immutable must not create a WAL or modify the file."""
    db = tmp_path / "opencode.db"
    _make_opencode_db(db, [
        {"id": "ses_x", "directory": "/d", "title": "t", "time_updated": 1},
    ])
    before = db.stat().st_mtime_ns
    resume.discover_opencode(db)
    # No write side-effects: mtime unchanged, no -wal/-shm files created.
    assert db.stat().st_mtime_ns == before
    assert not (tmp_path / "opencode.db-wal").exists()


# ---------------------------------------------------------------------------
# merge / sort / filter
# ---------------------------------------------------------------------------


def _s(engine: str, sid: str, cwd: str, ts: float, label: str = "x") -> ResumableSession:
    return ResumableSession(engine=engine, session_id=sid, cwd=cwd, last_activity=ts, label=label)


def test_merge_sorts_newest_first_with_stable_tiebreak() -> None:
    a = _s("opencode", "o1", "/a", 100.0)
    b = _s("claude", "c1", "/a", 200.0)
    c = _s("codex", "x1", "/a", 200.0)  # tie with b on ts
    out = resume.merge_sessions(sessions=[a, b, c])
    # 200 ahead of 100; within the tie claude(0) precedes codex(1)
    assert [s.session_id for s in out] == ["c1", "x1", "o1"]


def test_merge_filters_by_cwd() -> None:
    a = _s("claude", "a", "/proj/one", 10.0)
    b = _s("claude", "b", "/proj/two", 20.0)
    out = resume.merge_sessions(sessions=[a, b], cwd="/proj/two")
    assert [s.session_id for s in out] == ["b"]
    # trailing slash normalised
    out2 = resume.merge_sessions(sessions=[a, b], cwd="/proj/two/")
    assert [s.session_id for s in out2] == ["b"]


def test_merge_filters_by_engine_and_limit() -> None:
    a = _s("claude", "a", "/p", 30.0)
    b = _s("codex", "b", "/p", 20.0)
    c = _s("codex", "c", "/p", 10.0)
    assert [s.session_id for s in resume.merge_sessions(sessions=[a, b, c], engine="codex")] == ["b", "c"]
    assert [s.session_id for s in resume.merge_sessions(sessions=[a, b, c], limit=2)] == ["a", "b"]


# ---------------------------------------------------------------------------
# live-dedupe
# ---------------------------------------------------------------------------


def test_mark_running_flags_live_pane_match() -> None:
    live = _s("claude", "live", "/home/alexey/git/foo", 10.0)
    idle = _s("claude", "idle", "/home/alexey/git/bar", 20.0)
    panes = [("/home/alexey/git/foo", "claude")]
    out = resume.mark_running([live, idle], panes)
    by_id = {s.session_id: s for s in out}
    assert by_id["live"].running is True
    assert by_id["idle"].running is False


def test_mark_running_requires_command_hint_match() -> None:
    # same cwd but the pane is just a bare shell -> not the engine running
    s = _s("codex", "s", "/work", 10.0)
    out = resume.mark_running([s], [("/work", "bash")])
    assert out[0].running is False
    out2 = resume.mark_running([s], [("/work", "codex")])
    assert out2[0].running is True


# ---------------------------------------------------------------------------
# resume-command builder (per engine)
# ---------------------------------------------------------------------------


def test_resume_argv_claude() -> None:
    s = _s("claude", "uuid-1", "/home/me/proj", 1.0)
    assert resume.resume_argv(s) == ["claude", "--resume", "uuid-1"]


def test_resume_argv_codex() -> None:
    s = _s("codex", "uuid-2", "/home/me/proj", 1.0)
    assert resume.resume_argv(s) == ["codex", "resume", "uuid-2"]


def test_resume_argv_opencode_uses_resolved_binary() -> None:
    s = _s("opencode", "ses_9", "/home/me/proj", 1.0)
    with patch("pocketshell.resume.resolve_opencode_binary", return_value="/opt/opencode"):
        assert resume.resume_argv(s) == ["/opt/opencode", "--session", "ses_9"]


def test_resume_argv_opencode_raises_when_binary_missing() -> None:
    s = _s("opencode", "ses_9", "/home/me/proj", 1.0)
    with patch("pocketshell.resume.resolve_opencode_binary", return_value=None):
        try:
            resume.resume_argv(s)
        except RuntimeError as exc:
            assert "opencode" in str(exc)
        else:
            raise AssertionError("expected RuntimeError")


def test_resolve_opencode_binary_npm_fallback(tmp_path: Path) -> None:
    fake_home = tmp_path
    bin_dir = (
        fake_home / ".nvm" / "versions" / "node" / "v24.13.1"
        / "lib" / "node_modules" / "opencode-ai" / "bin"
    )
    bin_dir.mkdir(parents=True)
    binary = bin_dir / "opencode"
    binary.write_text("#!/bin/sh\n")
    with patch("pocketshell.resume.shutil.which", return_value=None), \
         patch("pocketshell.resume.Path.home", return_value=fake_home):
        assert resume.resolve_opencode_binary() == str(binary)


def test_resume_shell_command_cds_and_execs() -> None:
    s = _s("claude", "uuid-1", "/home/me/my proj", 1.0)
    cmd = resume.resume_shell_command(s)
    assert cmd == "cd '/home/me/my proj' && exec 'claude' '--resume' 'uuid-1'"


def test_tmux_session_name_is_sanitized() -> None:
    s = _s("codex", "019e32a7-42a6-7cd3", "/home/alexey/git/prose-style-lint", 1.0)
    name = resume.tmux_session_name(s)
    assert name == "resume-codex-prose-style-lint-019e32a7"
    # no tmux-hostile characters
    assert all(ch.isalnum() or ch in "_-" for ch in name)


def test_tmuxctl_resume_argv_shape() -> None:
    s = _s("claude", "uuid-1", "/home/me/proj", 1.0)
    argv = resume.tmuxctl_resume_argv(s, tmuxctl_path="/bin/tmuxctl", mem="24G")
    assert argv[:5] == [
        "/bin/tmuxctl", "create-or-attach", "resume-claude-proj-uuid-1", "--mem", "24G",
    ]
    assert argv[5] == "--"
    assert argv[6:8] == ["bash", "-lc"]
    assert argv[8] == "cd '/home/me/proj' && exec 'claude' '--resume' 'uuid-1'"


# ---------------------------------------------------------------------------
# capped detached-create builder (#726)
# ---------------------------------------------------------------------------


def test_tmuxctl_create_argv_omits_mem_by_default() -> None:
    """CRITICAL (#726): with no explicit --mem, the argv must NOT carry one.

    Omitting --mem lets tmuxctl resolve the per-project cap from the repo's
    cgroups.toml (PocketShell's is 30G). A hard-coded 24G here would override
    that committed policy — that's the bug this test guards against.
    """
    argv = resume.tmuxctl_create_argv("my-session", tmuxctl_path="/bin/tmuxctl")
    assert argv == ["/bin/tmuxctl", "create-detached", "my-session"]
    assert "--mem" not in argv
    assert "24G" not in argv


def test_tmuxctl_create_argv_with_cwd() -> None:
    argv = resume.tmuxctl_create_argv(
        "my-session", tmuxctl_path="/bin/tmuxctl", cwd="/home/me/proj"
    )
    assert argv == [
        "/bin/tmuxctl", "create-detached", "my-session", "-c", "/home/me/proj",
    ]
    assert "--mem" not in argv


def test_tmuxctl_create_argv_passes_mem_through_when_given() -> None:
    argv = resume.tmuxctl_create_argv(
        "my-session", tmuxctl_path="/bin/tmuxctl", mem="16G"
    )
    assert argv == ["/bin/tmuxctl", "create-detached", "my-session", "--mem", "16G"]


def test_tmuxctl_create_argv_with_cwd_and_mem() -> None:
    argv = resume.tmuxctl_create_argv(
        "my-session", tmuxctl_path="/bin/tmuxctl", cwd="/home/me/proj", mem="16G"
    )
    assert argv == [
        "/bin/tmuxctl", "create-detached", "my-session",
        "-c", "/home/me/proj", "--mem", "16G",
    ]


# ---------------------------------------------------------------------------
# selector resolution
# ---------------------------------------------------------------------------


def test_select_session_by_index_and_id() -> None:
    sessions = [
        _s("claude", "alpha", "/a", 30.0),
        _s("codex", "beta", "/a", 20.0),
    ]
    assert resume.select_session(sessions, "1").session_id == "alpha"
    assert resume.select_session(sessions, "beta").session_id == "beta"
    assert resume.select_session(sessions, "al").session_id == "alpha"  # prefix
    assert resume.select_session(sessions, "99") is None
    assert resume.select_session(sessions, "zzz") is None


def test_format_relative() -> None:
    now = 1_000_000.0
    assert resume.format_relative(now - 10, now=now) == "just now"
    assert resume.format_relative(now - 120, now=now) == "2m"
    assert resume.format_relative(now - 3 * 3600, now=now) == "3h"
    assert resume.format_relative(now - 2 * 86400, now=now) == "2d"


# ---------------------------------------------------------------------------
# CLI: `pocketshell sessions resumable` / `resume`
# ---------------------------------------------------------------------------


def test_resumable_command_lists_merged_sorted(tmp_path: Path) -> None:
    sessions = [
        _s("claude", "c1", "/home/me/proj", 200.0, "newer claude"),
        _s("codex", "x1", "/home/me/proj", 100.0, "older codex"),
        _s("opencode", "o1", "/home/me/other", 300.0, "other project"),
    ]
    runner = CliRunner()
    with patch("pocketshell.sessions._resume.discover_all", return_value=sessions), \
         patch("pocketshell.sessions._resume.list_live_panes", return_value=[]), \
         patch("pocketshell.sessions.os.getcwd", return_value="/x"):
        # --all spans every project; newest-first
        result = runner.invoke(sessions_group, ["resumable", "--all"])
    assert result.exit_code == 0, result.output
    lines = [ln for ln in result.output.splitlines() if ln.strip()]
    # header + 3 rows
    assert lines[0].startswith("IDX")
    assert "other project" in lines[1]   # o1 @ 300 newest
    assert "newer claude" in lines[2]    # c1 @ 200
    assert "older codex" in lines[3]     # x1 @ 100


def test_resumable_command_filters_to_cwd_by_default() -> None:
    sessions = [
        _s("claude", "here", "/home/me/proj", 200.0, "in cwd"),
        _s("claude", "elsewhere", "/home/me/other", 300.0, "not cwd"),
    ]
    runner = CliRunner()
    with patch("pocketshell.sessions._resume.discover_all", return_value=sessions), \
         patch("pocketshell.sessions._resume.list_live_panes", return_value=[]), \
         patch("pocketshell.sessions.os.getcwd", return_value="/home/me/proj"):
        result = runner.invoke(sessions_group, ["resumable"])
    assert result.exit_code == 0, result.output
    assert "in cwd" in result.output
    assert "not cwd" not in result.output


def test_resumable_command_marks_running(tmp_path: Path) -> None:
    sessions = [_s("claude", "live", "/home/me/proj", 200.0, "live one")]
    runner = CliRunner()
    with patch("pocketshell.sessions._resume.discover_all", return_value=sessions), \
         patch("pocketshell.sessions._resume.list_live_panes",
               return_value=[("/home/me/proj", "claude")]), \
         patch("pocketshell.sessions.os.getcwd", return_value="/x"):
        result = runner.invoke(sessions_group, ["resumable", "--all"])
    assert result.exit_code == 0, result.output
    assert "(running)" in result.output


def test_resume_command_routes_to_tmuxctl_capped() -> None:
    sessions = [_s("claude", "uuid-1", "/home/me/proj", 200.0, "label")]
    runner = CliRunner()
    with patch("pocketshell.sessions._resume.discover_all", return_value=sessions), \
         patch("pocketshell.sessions._resume.list_live_panes", return_value=[]), \
         patch("pocketshell.sessions.os.getcwd", return_value="/x"), \
         patch("pocketshell.sessions._resolve_tmuxctl_binary", return_value="/bin/tmuxctl"), \
         patch("pocketshell.sessions.subprocess.run") as run:
        run.return_value.returncode = 0
        result = runner.invoke(sessions_group, ["resume", "1", "--all", "--mem", "8G"])
    assert result.exit_code == 0, result.output
    argv = run.call_args.args[0]
    assert argv[:5] == [
        "/bin/tmuxctl", "create-or-attach", "resume-claude-proj-uuid-1", "--mem", "8G",
    ]
    assert "cd '/home/me/proj' && exec 'claude' '--resume' 'uuid-1'" in argv


def test_resume_command_refuses_running_session() -> None:
    sessions = [_s("claude", "uuid-1", "/home/me/proj", 200.0, "label")]
    runner = CliRunner()
    with patch("pocketshell.sessions._resume.discover_all", return_value=sessions), \
         patch("pocketshell.sessions._resume.list_live_panes",
               return_value=[("/home/me/proj", "claude")]), \
         patch("pocketshell.sessions.os.getcwd", return_value="/x"), \
         patch("pocketshell.sessions._resolve_tmuxctl_binary", return_value="/bin/tmuxctl"), \
         patch("pocketshell.sessions.subprocess.run") as run:
        result = runner.invoke(sessions_group, ["resume", "uuid-1", "--all"])
    assert result.exit_code == 3
    assert "already running" in result.output
    run.assert_not_called()


def test_resume_command_unknown_selector_exits_2() -> None:
    runner = CliRunner()
    with patch("pocketshell.sessions._resume.discover_all", return_value=[]), \
         patch("pocketshell.sessions._resume.list_live_panes", return_value=[]), \
         patch("pocketshell.sessions.os.getcwd", return_value="/x"):
        result = runner.invoke(sessions_group, ["resume", "nope", "--all"])
    assert result.exit_code == 2
    assert "no resumable session" in result.output


def test_resume_command_missing_tmuxctl_exits_127() -> None:
    sessions = [_s("claude", "uuid-1", "/home/me/proj", 200.0, "label")]
    runner = CliRunner()
    with patch("pocketshell.sessions._resume.discover_all", return_value=sessions), \
         patch("pocketshell.sessions._resume.list_live_panes", return_value=[]), \
         patch("pocketshell.sessions.os.getcwd", return_value="/x"), \
         patch("pocketshell.sessions._resolve_tmuxctl_binary", return_value=None):
        result = runner.invoke(sessions_group, ["resume", "1", "--all"])
    assert result.exit_code == 127
    assert "tmuxctl" in result.output.lower()


def test_resumable_no_stores_is_empty_no_crash(tmp_path: Path) -> None:
    """End-to-end through real discovery with all stores pointed at empties."""
    runner = CliRunner()
    with patch("pocketshell.resume.claude_projects_dir", return_value=tmp_path / "c"), \
         patch("pocketshell.resume.codex_sessions_dir", return_value=tmp_path / "x"), \
         patch("pocketshell.resume.opencode_db_path", return_value=tmp_path / "db"), \
         patch("pocketshell.resume.list_live_panes", return_value=[]), \
         patch("pocketshell.sessions.os.getcwd", return_value="/x"):
        result = runner.invoke(sessions_group, ["resumable", "--all"])
    assert result.exit_code == 0, result.output
    assert result.output.startswith("IDX")
    # header only, no rows
    assert len([ln for ln in result.output.splitlines() if ln.strip()]) == 1
