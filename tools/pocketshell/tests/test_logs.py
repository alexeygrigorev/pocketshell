"""Unit tests for `pocketshell logs` (issue #270, decision D27).

Coverage:

- Top-level CLI registration: ``pocketshell --help`` lists ``logs`` and
  ``pocketshell logs --help`` lists ingest/tail/path/import-hooks.
- ``ingest`` reads stdin JSON, normalizes, stamps ``ts``/``schema``,
  appends to the dated JSONL, and routes by ``kind`` (agent vs app file).
- File perms are ``0600``.
- **Adversarial secret redaction**: env-set values, token-shaped
  strings, secret-named keys, and inline ``KEY=value`` secret
  assignments NEVER appear in the file (raw-bytes scan).
- ``install_id`` + ``target_host`` survive on every event.
- ``tail`` and ``path`` work for both families.
- Source 2 bridge: ``import-hooks`` mirrors the #267 hooks bus in as
  ``kind=engine_event`` and is idempotent (no dupes on re-run).

All tests parametrize the logs/hooks paths via :func:`resolve_paths`
with a tmp ``home=`` / ``env=`` so the real ``~/.local/state`` and
``~/.cache`` are never touched.
"""

from __future__ import annotations

import json
import os
import stat
from pathlib import Path

from click.testing import CliRunner

from pocketshell.cli import cli
from pocketshell.logs import (
    SCHEMA_VERSION,
    LogsPaths,
    import_hooks,
    ingest_event,
    normalize_event,
    read_records,
    redact,
    resolve_paths,
)


def make_paths(tmp_path: Path) -> LogsPaths:
    """Resolve LogsPaths under a throwaway home (no env override)."""
    return resolve_paths(home=tmp_path, env={})


def read_agent_bytes(paths: LogsPaths) -> bytes:
    """Concatenate raw bytes of every agent-* file (for secret scans)."""
    return b"".join(p.read_bytes() for p in paths.files_for_family("agent"))


def read_app_bytes(paths: LogsPaths) -> bytes:
    return b"".join(p.read_bytes() for p in paths.files_for_family("app"))


# ---------------------------------------------------------------------------
# CLI registration
# ---------------------------------------------------------------------------


def test_cli_lists_logs_group():
    result = CliRunner().invoke(cli, ["--help"])
    assert result.exit_code == 0
    assert "logs" in result.output


def test_logs_help_lists_subcommands():
    result = CliRunner().invoke(cli, ["logs", "--help"])
    assert result.exit_code == 0
    for sub in ("ingest", "tail", "path", "import-hooks"):
        assert sub in result.output


# ---------------------------------------------------------------------------
# ingest: normalization + routing + perms
# ---------------------------------------------------------------------------


def test_ingest_stamps_ts_and_schema(tmp_path):
    paths = make_paths(tmp_path)
    rec = ingest_event(paths, {"kind": "agent_action", "action": "run_command",
                               "target_host": "devbox"})
    assert isinstance(rec["ts"], str) and rec["ts"]
    assert rec["schema"] == SCHEMA_VERSION
    assert rec["source"] == "phone"  # default


def test_ingest_preserves_caller_ts():
    rec = normalize_event({"ts": "2026-05-29T12:00:00+00:00", "action": "x"})
    assert rec["ts"] == "2026-05-29T12:00:00+00:00"


def test_ingest_routes_agent_vs_app(tmp_path):
    paths = make_paths(tmp_path)
    ingest_event(paths, {"kind": "agent_action", "action": "a", "target_host": "h"})
    ingest_event(paths, {"kind": "engine_event", "action": "FINISHED", "target_host": "h"})
    ingest_event(paths, {"kind": "app_log", "action": "boot", "target_host": "h"})
    ingest_event(paths, {"kind": "crash", "action": "anr", "target_host": "h"})

    agent = read_records(paths, "agent")
    app = read_records(paths, "app")
    assert {r["kind"] for r in agent} == {"agent_action", "engine_event"}
    assert {r["kind"] for r in app} == {"app_log", "crash"}


def test_ingest_unknown_kind_defaults_to_agent_action(tmp_path):
    paths = make_paths(tmp_path)
    rec = ingest_event(paths, {"kind": "bogus", "action": "x", "target_host": "h"})
    assert rec["kind"] == "agent_action"


def test_log_file_is_0600(tmp_path):
    paths = make_paths(tmp_path)
    ingest_event(paths, {"kind": "agent_action", "action": "a", "target_host": "h"})
    f = paths.files_for_family("agent")[0]
    mode = stat.S_IMODE(os.stat(f).st_mode)
    assert mode == 0o600, oct(mode)


def test_install_id_and_target_host_preserved(tmp_path):
    paths = make_paths(tmp_path)
    rec = ingest_event(paths, {
        "kind": "agent_action", "action": "run_command",
        "install_id": "11111111-2222-3333-4444-555555555555",
        "target_host": "devbox",
    })
    assert rec["install_id"] == "11111111-2222-3333-4444-555555555555"
    assert rec["target_host"] == "devbox"
    # And on disk.
    on_disk = read_records(paths, "agent")[-1]
    assert on_disk["install_id"] == "11111111-2222-3333-4444-555555555555"
    assert on_disk["target_host"] == "devbox"


# ---------------------------------------------------------------------------
# Adversarial secret redaction
# ---------------------------------------------------------------------------


def test_redact_secret_named_key():
    out = redact({"OPENAI_API_KEY": "sk-secret123456789", "host": "devbox"})
    assert out["OPENAI_API_KEY"] == "<redacted>"
    assert out["host"] == "devbox"


def test_redact_token_shaped_string_under_innocent_key():
    out = redact({"note": "the key is sk-secret123456789 ok"})
    assert "sk-secret123456789" not in out["note"]
    assert "<redacted>" in out["note"]


def test_redact_inline_env_assignment_keeps_name_masks_value():
    out = redact({"command": "export OPENAI_API_KEY=supersecretvalue"})
    assert "OPENAI_API_KEY" in out["command"]  # name kept as evidence
    assert "supersecretvalue" not in out["command"]
    assert "<redacted>" in out["command"]


def test_redact_nested_structures():
    out = redact({"args": {"env": {"AWS_SECRET_ACCESS_KEY": "abc"}, "list": ["PASSWORD=hunter2"]}})
    assert out["args"]["env"]["AWS_SECRET_ACCESS_KEY"] == "<redacted>"
    assert "hunter2" not in out["args"]["list"][0]


def test_ingest_redacts_env_set_value_on_disk(tmp_path):
    paths = make_paths(tmp_path)
    ingest_event(paths, {
        "kind": "agent_action",
        "action": "run_command",
        "target_host": "devbox",
        "args": {"command": "export OPENAI_API_KEY=sk-secret123456789"},
    })
    raw = read_agent_bytes(paths)
    assert b"sk-secret123456789" not in raw
    assert b"supersecret" not in raw
    assert b"OPENAI_API_KEY" in raw  # the name (not the value) is preserved


def test_ingest_redacts_a_wide_range_of_token_shapes(tmp_path):
    paths = make_paths(tmp_path)
    secrets = [
        "sk-abcdefghijklmnop",
        "sk-ant-abcdefghijklmnop",
        "ghp_0123456789ABCDEFabcdef0123456789ABCD",
        "github_pat_11ABCDEFG0123456789_abcdefabcdefabcdefabcdef",
        "AKIAIOSFODNN7EXAMPLE",
        "glpat-abcdefghij0123456789",
        "xoxb-1234567890-abcdefghij",
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abc123def456",
    ]
    for i, secret in enumerate(secrets):
        ingest_event(paths, {
            "kind": "agent_action", "action": "run_command", "target_host": "h",
            "detail": f"value number {i} is {secret} embedded",
        })
    raw = read_agent_bytes(paths)
    for secret in secrets:
        assert secret.encode() not in raw, secret


def test_ingest_redacts_secret_named_key_value_on_disk(tmp_path):
    paths = make_paths(tmp_path)
    ingest_event(paths, {
        "kind": "app_log", "action": "config", "target_host": "h",
        "args": {"ANTHROPIC_API_KEY": "totally-secret-value-here-abc"},
    })
    raw = read_app_bytes(paths)
    assert b"totally-secret-value-here-abc" not in raw


# ---------------------------------------------------------------------------
# tail + path CLI
# ---------------------------------------------------------------------------


def test_tail_and_path_via_cli(tmp_path, monkeypatch):
    # Point resolve_paths at the tmp home via XDG/HOME env so the CLI
    # commands (which call resolve_paths() with no args) hit the tmp dir.
    state = tmp_path / "state"
    monkeypatch.setenv("XDG_STATE_HOME", str(state))
    monkeypatch.setenv("HOME", str(tmp_path))

    runner = CliRunner()
    ev = json.dumps({"kind": "agent_action", "action": "run_command", "target_host": "devbox"})
    res_ingest = runner.invoke(cli, ["logs", "ingest", "--json"], input=ev)
    assert res_ingest.exit_code == 0, res_ingest.output

    res_tail = runner.invoke(cli, ["logs", "tail", "--kind", "agent", "-n", "5"])
    assert res_tail.exit_code == 0
    assert "run_command" in res_tail.output

    res_path = runner.invoke(cli, ["logs", "path", "--kind", "agent"])
    assert res_path.exit_code == 0
    assert "pocketshell/logs/agent-" in res_path.output
    res_path_app = runner.invoke(cli, ["logs", "path", "--kind", "app"])
    assert "pocketshell/logs/app-" in res_path_app.output


def test_ingest_cli_redaction_proof(tmp_path, monkeypatch):
    """End-to-end: the secret value is in neither stdout nor the file."""
    state = tmp_path / "state"
    monkeypatch.setenv("XDG_STATE_HOME", str(state))
    monkeypatch.setenv("HOME", str(tmp_path))

    ev = json.dumps({
        "source": "phone", "kind": "agent_action", "action": "run_command",
        "args": {"command": "export OPENAI_API_KEY=sk-secret123"},
        "target_host": "devbox",
    })
    res = CliRunner().invoke(cli, ["logs", "ingest", "--json"], input=ev)
    assert res.exit_code == 0
    assert "sk-secret123" not in res.output

    paths = resolve_paths(home=tmp_path, env={"XDG_STATE_HOME": str(state)})
    raw = read_agent_bytes(paths)
    assert b"sk-secret123" not in raw


# ---------------------------------------------------------------------------
# Source 2 — import-hooks bridge + idempotency
# ---------------------------------------------------------------------------


def _seed_hooks_bus(paths: LogsPaths, records: list[dict]) -> None:
    paths.hooks_events_file.parent.mkdir(parents=True, exist_ok=True)
    with open(paths.hooks_events_file, "a", encoding="utf-8") as handle:
        for rec in records:
            handle.write(json.dumps(rec) + "\n")


def test_import_hooks_forwards_engine_events(tmp_path):
    paths = make_paths(tmp_path)
    _seed_hooks_bus(paths, [
        {"ts": "2026-05-29T10:00:00+00:00", "engine": "claude-code", "state": "FINISHED",
         "source": "hook", "session_id": "s1", "cwd": "/repo"},
        {"ts": "2026-05-29T10:01:00+00:00", "engine": "codex", "state": "WAITING_FOR_INPUT",
         "source": "notify", "session_id": "s2", "cwd": "/other"},
    ])
    forwarded = import_hooks(paths, target_host="devbox")
    assert forwarded == 2

    recs = read_records(paths, "agent")
    assert len(recs) == 2
    assert all(r["kind"] == "engine_event" for r in recs)
    assert {r["engine"] for r in recs} == {"claude-code", "codex"}
    assert all(r["target_host"] == "devbox" for r in recs)
    assert {r["state"] for r in recs} == {"FINISHED", "WAITING_FOR_INPUT"}
    assert {r["session_id"] for r in recs} == {"s1", "s2"}
    assert {r["cwd"] for r in recs} == {"/repo", "/other"}


def test_import_hooks_is_idempotent(tmp_path):
    paths = make_paths(tmp_path)
    _seed_hooks_bus(paths, [
        {"ts": "2026-05-29T10:00:00+00:00", "engine": "opencode", "state": "FINISHED",
         "source": "plugin", "session_id": "s1", "cwd": "/repo"},
    ])
    assert import_hooks(paths, target_host="devbox") == 1
    # Re-run with no new bus activity: nothing forwarded, no dupes.
    assert import_hooks(paths, target_host="devbox") == 0
    assert len(read_records(paths, "agent")) == 1

    # New bus activity is picked up incrementally.
    _seed_hooks_bus(paths, [
        {"ts": "2026-05-29T10:05:00+00:00", "engine": "claude-code", "state": "FINISHED",
         "source": "hook", "session_id": "s2", "cwd": "/repo"},
    ])
    assert import_hooks(paths, target_host="devbox") == 1
    assert len(read_records(paths, "agent")) == 2


def test_import_hooks_handles_partial_final_line(tmp_path):
    paths = make_paths(tmp_path)
    paths.hooks_events_file.parent.mkdir(parents=True, exist_ok=True)
    complete = json.dumps({"ts": "2026-05-29T10:00:00+00:00", "engine": "codex",
                           "state": "FINISHED", "session_id": "s1", "cwd": "/r"}) + "\n"
    partial = '{"ts": "2026-05-29T10:01:00+00:00", "engine": "claude-code"'  # no newline
    paths.hooks_events_file.write_text(complete + partial, encoding="utf-8")

    assert import_hooks(paths, target_host="devbox") == 1  # only the complete line

    # Now the partial line is completed; it gets picked up next run, once.
    with open(paths.hooks_events_file, "a", encoding="utf-8") as handle:
        handle.write(', "state": "FINISHED", "session_id": "s2", "cwd": "/r"}\n')
    assert import_hooks(paths, target_host="devbox") == 1
    assert len(read_records(paths, "agent")) == 2


def test_import_hooks_no_bus_is_noop(tmp_path):
    paths = make_paths(tmp_path)
    assert import_hooks(paths, target_host="devbox") == 0


def test_import_hooks_redacts_secrets_in_payload(tmp_path):
    paths = make_paths(tmp_path)
    _seed_hooks_bus(paths, [
        {"ts": "2026-05-29T10:00:00+00:00", "engine": "claude-code", "state": "FINISHED",
         "session_id": "s1", "cwd": "/r",
         "last_assistant_message": "I set OPENAI_API_KEY=sk-leakedsecret12345 in .env"},
    ])
    import_hooks(paths, target_host="devbox")
    raw = read_agent_bytes(paths)
    assert b"sk-leakedsecret12345" not in raw
