"""Unit tests for `pocketshell hooks` (issue #267).

Coverage:

- Top-level CLI registration: ``pocketshell --help`` lists ``hooks`` and
  ``pocketshell hooks --help`` lists install/uninstall/status/events.
- Claude merge preserves unrelated keys + pre-existing hooks.
- Install is idempotent (no duplicate entries on a second run).
- Codex install does not clobber an existing/foreign ``notify`` and
  preserves the rest of the TOML.
- OpenCode plugin install does not disturb other plugins.
- install → uninstall round-trip leaves a pre-populated Claude settings
  file byte-equivalent for unrelated keys, and a Codex config with all
  unrelated content intact.
- Firing each handler appends a correctly-normalized record to the bus,
  and ``read_events`` / ``hooks events --json`` read it back.
- ``hooks status --json`` reports per-engine install state.

All tests parametrize the home/config paths via :func:`resolve_paths`
with a tmp ``home=`` so the real ``~/.claude`` / ``~/.codex`` /
``~/.config/opencode`` are never touched.
"""

from __future__ import annotations

import json
import subprocess
import sys
import tomllib
from pathlib import Path

from click.testing import CliRunner

from pocketshell import hooks as hooks_mod
from pocketshell.cli import cli
from pocketshell.hooks import (
    CLAUDE_HOOK_EVENTS,
    HooksPaths,
    claude_install,
    claude_uninstall,
    codex_install,
    codex_uninstall,
    engine_status,
    install_engines,
    read_events,
    resolve_paths,
    uninstall_engines,
)


def make_paths(home: Path) -> HooksPaths:
    """Resolve HooksPaths under a throwaway ``home`` (no env override)."""
    return resolve_paths(home=home, env={})


# ---------------------------------------------------------------------------
# CLI registration
# ---------------------------------------------------------------------------


def test_cli_lists_hooks_group():
    result = CliRunner().invoke(cli, ["--help"])
    assert result.exit_code == 0
    assert "hooks" in result.output


def test_hooks_help_lists_subcommands():
    result = CliRunner().invoke(cli, ["hooks", "--help"])
    assert result.exit_code == 0
    for sub in ("install", "uninstall", "status", "events"):
        assert sub in result.output


# ---------------------------------------------------------------------------
# Claude JSON merge (pure functions)
# ---------------------------------------------------------------------------


def test_claude_install_preserves_unrelated_keys_and_hooks():
    command = "python3 /h/claude_hook.py"
    existing = {
        "model": "claude-sonnet",
        "permissions": {"allow": ["Bash(ls:*)"]},
        "hooks": {
            "Stop": [
                {"hooks": [{"type": "command", "command": "echo user-stop"}]}
            ],
            "PreToolUse": [
                {"matcher": "Bash", "hooks": [{"type": "command", "command": "echo pre"}]}
            ],
        },
    }
    merged = claude_install(existing, command)

    # Unrelated top-level keys survive.
    assert merged["model"] == "claude-sonnet"
    assert merged["permissions"] == {"allow": ["Bash(ls:*)"]}
    # Pre-existing user PreToolUse hook untouched.
    assert merged["hooks"]["PreToolUse"] == existing["hooks"]["PreToolUse"]
    # Pre-existing user Stop hook preserved AND ours appended.
    stop_groups = merged["hooks"]["Stop"]
    commands = [
        h["command"]
        for g in stop_groups
        for h in g["hooks"]
    ]
    assert "echo user-stop" in commands
    assert command in commands
    # Our command added to every event we register.
    for event in CLAUDE_HOOK_EVENTS:
        evt_commands = [
            h["command"] for g in merged["hooks"][event] for h in g["hooks"]
        ]
        assert command in evt_commands


def test_claude_install_idempotent():
    command = "python3 /h/claude_hook.py"
    once = claude_install({}, command)
    twice = claude_install(once, command)
    assert once == twice
    for event in CLAUDE_HOOK_EVENTS:
        groups = twice["hooks"][event]
        ours = [g for g in groups if g["hooks"][0]["command"] == command]
        assert len(ours) == 1


def test_claude_uninstall_restores_prior_shape():
    command = "python3 /h/claude_hook.py"
    original = {
        "model": "claude-sonnet",
        "hooks": {
            "Stop": [
                {"hooks": [{"type": "command", "command": "echo user-stop"}]}
            ],
        },
    }
    merged = claude_install(original, command)
    restored = claude_uninstall(merged, command)
    assert restored == original


def test_claude_uninstall_drops_hooks_object_when_we_created_it():
    command = "python3 /h/claude_hook.py"
    merged = claude_install({"model": "x"}, command)
    restored = claude_uninstall(merged, command)
    assert restored == {"model": "x"}
    assert "hooks" not in restored


def test_claude_uninstall_idempotent():
    command = "python3 /h/claude_hook.py"
    merged = claude_install({"model": "x"}, command)
    once = claude_uninstall(merged, command)
    twice = claude_uninstall(once, command)
    assert once == twice


# ---------------------------------------------------------------------------
# Codex TOML merge (text-level, preserve the rest)
# ---------------------------------------------------------------------------


def test_codex_install_into_empty():
    new_text, status = codex_install("", ["python3", "/h/codex_notify.py"])
    assert status == "added"
    parsed = tomllib.loads(new_text)
    assert parsed["notify"] == ["python3", "/h/codex_notify.py"]


def test_codex_install_preserves_other_content():
    original = (
        "model = \"gpt-5\"\n"
        "\n"
        "[tui]\n"
        "theme = \"dark\"\n"
    )
    new_text, status = codex_install(original, ["python3", "/h/codex_notify.py"])
    assert status == "added"
    parsed = tomllib.loads(new_text)
    assert parsed["model"] == "gpt-5"
    assert parsed["tui"] == {"theme": "dark"}
    assert parsed["notify"] == ["python3", "/h/codex_notify.py"]


def test_codex_install_skips_foreign_notify():
    original = 'notify = ["my-own-notifier"]\nmodel = "gpt-5"\n'
    new_text, status = codex_install(original, ["python3", "/h/codex_notify.py"])
    assert status == "skipped"
    # File unchanged — never clobbered.
    assert new_text == original


def test_codex_install_present_is_noop():
    value = ["python3", "/h/codex_notify.py"]
    once, status1 = codex_install("model = \"gpt-5\"\n", value)
    assert status1 == "added"
    twice, status2 = codex_install(once, value)
    assert status2 == "present"
    assert twice == once


def test_codex_uninstall_roundtrip_restores_unrelated():
    value = ["python3", "/h/codex_notify.py"]
    original = "model = \"gpt-5\"\n\n[tui]\ntheme = \"dark\"\n"
    installed, _ = codex_install(original, value)
    restored, status = codex_uninstall(installed, value)
    assert status == "removed"
    assert tomllib.loads(restored) == tomllib.loads(original)


def test_codex_uninstall_leaves_foreign_notify():
    original = 'notify = ["my-own-notifier"]\n'
    restored, status = codex_uninstall(original, ["python3", "/h/codex_notify.py"])
    assert status == "skipped"
    assert restored == original


def test_codex_uninstall_idempotent():
    value = ["python3", "/h/codex_notify.py"]
    installed, _ = codex_install("model = \"x\"\n", value)
    once, _ = codex_uninstall(installed, value)
    twice, status = codex_uninstall(once, value)
    assert status == "absent"
    assert once == twice


def test_codex_install_ignores_notify_inside_table():
    # A ``notify`` key nested in a table is NOT the top-level Codex notify.
    original = '[mytool]\nnotify = ["x"]\n'
    new_text, status = codex_install(original, ["python3", "/h/codex_notify.py"])
    assert status == "added"
    parsed = tomllib.loads(new_text)
    assert parsed["notify"] == ["python3", "/h/codex_notify.py"]
    assert parsed["mytool"] == {"notify": ["x"]}


# ---------------------------------------------------------------------------
# Filesystem install drivers
# ---------------------------------------------------------------------------


def test_install_claude_merges_existing_file(tmp_path):
    paths = make_paths(tmp_path)
    paths.claude_settings.parent.mkdir(parents=True)
    paths.claude_settings.write_text(
        json.dumps(
            {
                "model": "claude-sonnet",
                "hooks": {
                    "Stop": [
                        {"hooks": [{"type": "command", "command": "echo user"}]}
                    ]
                },
            }
        )
    )
    results = install_engines(["claude"], paths)
    assert results[0].status == "installed"

    data = json.loads(paths.claude_settings.read_text())
    assert data["model"] == "claude-sonnet"
    stop_cmds = [h["command"] for g in data["hooks"]["Stop"] for h in g["hooks"]]
    assert "echo user" in stop_cmds
    assert paths.claude_handler.exists()


def test_install_idempotent_on_disk(tmp_path):
    paths = make_paths(tmp_path)
    install_engines(["claude", "codex", "opencode"], paths)
    first_claude = paths.claude_settings.read_text()
    first_codex = paths.codex_config.read_text()
    results = install_engines(["claude", "codex", "opencode"], paths)
    statuses = {r.engine: r.status for r in results}
    assert statuses["claude"] == "present"
    assert statuses["codex"] == "present"
    assert statuses["opencode"] == "present"
    assert paths.claude_settings.read_text() == first_claude
    assert paths.codex_config.read_text() == first_codex


def test_install_codex_does_not_clobber_foreign(tmp_path):
    paths = make_paths(tmp_path)
    paths.codex_config.parent.mkdir(parents=True)
    original = 'notify = ["my-notifier"]\nmodel = "gpt-5"\n'
    paths.codex_config.write_text(original)
    results = install_engines(["codex"], paths)
    assert results[0].status == "skipped"
    assert paths.codex_config.read_text() == original


def test_install_opencode_preserves_other_plugins(tmp_path):
    paths = make_paths(tmp_path)
    paths.opencode_plugin_dir.mkdir(parents=True)
    other = paths.opencode_plugin_dir / "user-plugin.js"
    other.write_text("export const X = () => ({});\n")
    install_engines(["opencode"], paths)
    assert other.read_text() == "export const X = () => ({});\n"
    assert paths.opencode_plugin.exists()


def test_full_roundtrip_restores_prepopulated_claude(tmp_path):
    paths = make_paths(tmp_path)
    paths.claude_settings.parent.mkdir(parents=True)
    original_obj = {
        "model": "claude-sonnet",
        "permissions": {"allow": ["Bash(ls:*)"]},
        "hooks": {
            "Stop": [{"hooks": [{"type": "command", "command": "echo user"}]}],
        },
    }
    paths.claude_settings.write_text(json.dumps(original_obj, indent=2) + "\n")

    install_engines(["claude"], paths)
    uninstall_engines(["claude"], paths)

    restored = json.loads(paths.claude_settings.read_text())
    assert restored == original_obj
    # Handler script cleaned up after full uninstall.
    assert not paths.claude_handler.exists()


def test_full_roundtrip_restores_prepopulated_codex(tmp_path):
    paths = make_paths(tmp_path)
    paths.codex_config.parent.mkdir(parents=True)
    original = "model = \"gpt-5\"\n\n[tui]\ntheme = \"dark\"\n"
    paths.codex_config.write_text(original)

    install_engines(["codex"], paths)
    assert paths.codex_config.read_text() != original  # notify added
    uninstall_engines(["codex"], paths)

    assert tomllib.loads(paths.codex_config.read_text()) == tomllib.loads(original)


def test_uninstall_idempotent_on_disk(tmp_path):
    paths = make_paths(tmp_path)
    install_engines(["claude", "codex", "opencode"], paths)
    uninstall_engines(["claude", "codex", "opencode"], paths)
    results = uninstall_engines(["claude", "codex", "opencode"], paths)
    statuses = {r.engine: r.status for r in results}
    assert statuses["claude"] == "absent"
    assert statuses["codex"] == "absent"
    assert statuses["opencode"] == "absent"


# ---------------------------------------------------------------------------
# Handler firing → bus
# ---------------------------------------------------------------------------


def _fire_claude_handler(paths: HooksPaths, payload: dict) -> None:
    subprocess.run(
        [sys.executable, str(paths.claude_handler)],
        input=json.dumps(payload),
        text=True,
        check=True,
    )


def _fire_codex_handler(paths: HooksPaths, payload: dict) -> None:
    subprocess.run(
        [sys.executable, str(paths.codex_handler), json.dumps(payload)],
        text=True,
        check=True,
    )


def test_claude_handler_appends_normalized_record(tmp_path):
    paths = make_paths(tmp_path)
    install_engines(["claude"], paths)
    _fire_claude_handler(
        paths,
        {
            "hook_event_name": "Stop",
            "session_id": "sess-1",
            "cwd": "/work/repo",
            "transcript_path": "/t.jsonl",
            "last_assistant_message": "done",
        },
    )
    events = read_events(paths)
    assert len(events) == 1
    rec = events[0]
    assert rec["engine"] == "claude-code"
    assert rec["state"] == "FINISHED"
    assert rec["source"] == "hook"
    assert rec["session_id"] == "sess-1"
    assert rec["cwd"] == "/work/repo"
    assert rec["hook_event_name"] == "Stop"
    assert "ts" in rec


def test_claude_handler_notification_is_waiting(tmp_path):
    paths = make_paths(tmp_path)
    install_engines(["claude"], paths)
    _fire_claude_handler(
        paths,
        {
            "hook_event_name": "Notification",
            "notification_type": "permission_prompt",
            "session_id": "sess-2",
        },
    )
    events = read_events(paths)
    assert events[-1]["state"] == "WAITING_FOR_INPUT"
    assert events[-1]["notification_type"] == "permission_prompt"


def test_codex_handler_appends_normalized_record(tmp_path):
    paths = make_paths(tmp_path)
    install_engines(["codex"], paths)
    _fire_codex_handler(
        paths,
        {
            "type": "agent-turn-complete",
            "thread-id": "th-9",
            "turn-id": "t-1",
            "cwd": "/work",
            "last-assistant-message": "ok",
        },
    )
    events = read_events(paths)
    assert len(events) == 1
    rec = events[0]
    assert rec["engine"] == "codex"
    assert rec["state"] == "FINISHED"
    assert rec["source"] == "notify"
    assert rec["session_id"] == "th-9"
    assert rec["cwd"] == "/work"
    assert rec["last_assistant_message"] == "ok"


def test_read_events_limit_and_since(tmp_path):
    paths = make_paths(tmp_path)
    paths.hooks_dir.mkdir(parents=True)
    lines = [
        {"ts": "2026-05-29T00:00:01Z", "engine": "codex", "state": "FINISHED"},
        {"ts": "2026-05-29T00:00:02Z", "engine": "codex", "state": "FINISHED"},
        {"ts": "2026-05-29T00:00:03Z", "engine": "codex", "state": "FINISHED"},
    ]
    paths.events_file.write_text("\n".join(json.dumps(line) for line in lines) + "\n")
    assert len(read_events(paths, limit=2)) == 2
    assert read_events(paths, limit=2)[0]["ts"] == "2026-05-29T00:00:02Z"
    since = read_events(paths, since="2026-05-29T00:00:01Z")
    assert len(since) == 2
    assert since[0]["ts"] == "2026-05-29T00:00:02Z"


def test_read_events_skips_malformed_lines(tmp_path):
    paths = make_paths(tmp_path)
    paths.hooks_dir.mkdir(parents=True)
    paths.events_file.write_text(
        "not json\n"
        + json.dumps({"ts": "2026-05-29T00:00:01Z", "engine": "codex", "state": "FINISHED"})
        + "\n"
        + "[1,2,3]\n"
    )
    events = read_events(paths)
    assert len(events) == 1
    assert events[0]["engine"] == "codex"


# ---------------------------------------------------------------------------
# Status
# ---------------------------------------------------------------------------


def test_engine_status_reports_install_state(tmp_path):
    paths = make_paths(tmp_path)
    before = {s["engine"]: s for s in (engine_status(e, paths) for e in ("claude", "codex", "opencode"))}
    assert before["claude"]["installed"] is False
    assert before["codex"]["installed"] is False
    assert before["opencode"]["installed"] is False

    install_engines(["claude", "codex", "opencode"], paths)
    after = {s["engine"]: s for s in (engine_status(e, paths) for e in ("claude", "codex", "opencode"))}
    assert after["claude"]["installed"] is True
    assert after["codex"]["installed"] is True
    assert after["opencode"]["installed"] is True


def test_status_json_command_reports_state(tmp_path, monkeypatch):
    paths = make_paths(tmp_path)
    install_engines(["claude", "codex", "opencode"], paths)
    monkeypatch.setattr(hooks_mod, "resolve_paths", lambda: paths)
    result = CliRunner().invoke(cli, ["hooks", "status", "--json"])
    assert result.exit_code == 0
    payload = json.loads(result.output)
    assert payload["bus_path"] == str(paths.events_file)
    installed = {e["engine"]: e["installed"] for e in payload["engines"]}
    assert installed == {"claude": True, "codex": True, "opencode": True}


def test_events_json_command_reads_bus(tmp_path, monkeypatch):
    paths = make_paths(tmp_path)
    install_engines(["codex"], paths)
    _fire_codex_handler(paths, {"type": "agent-turn-complete", "thread-id": "th-1"})
    monkeypatch.setattr(hooks_mod, "resolve_paths", lambda: paths)
    result = CliRunner().invoke(cli, ["hooks", "events", "--json"])
    assert result.exit_code == 0
    records = json.loads(result.output)
    assert len(records) == 1
    assert records[0]["engine"] == "codex"
    assert records[0]["session_id"] == "th-1"


def test_install_command_round_trip_via_cli(tmp_path, monkeypatch):
    paths = make_paths(tmp_path)
    monkeypatch.setattr(hooks_mod, "resolve_paths", lambda: paths)
    runner = CliRunner()

    install_res = runner.invoke(cli, ["hooks", "install", "--engine", "all"])
    assert install_res.exit_code == 0
    assert paths.claude_settings.exists()
    assert paths.codex_config.exists()
    assert paths.opencode_plugin.exists()

    uninstall_res = runner.invoke(cli, ["hooks", "uninstall", "--engine", "all"])
    assert uninstall_res.exit_code == 0
    assert not paths.opencode_plugin.exists()
