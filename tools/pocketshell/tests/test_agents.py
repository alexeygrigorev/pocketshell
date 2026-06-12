"""Tests for `pocketshell agent <kind> --dir <dir>` (issue #703).

Covers the wrapper that replaces the giant inline `env -u … <agent>` line
the app used to type into the pane:

- env-strip for ALL three agents (codex / claude / opencode all strip the
  71 provider vars — maintainer decision, issue #703, subscription billing
  across the board);
- per-agent first-run-prompt suppression flags (codex update-check off,
  claude trust pre-seeded);
- `--dir` handling and config-dir profile env vars;
- the `os.execvpe` call shape (injected, so the test process is not
  replaced).
"""

from __future__ import annotations

import json
import os

import click
import pytest

from pocketshell import agents
from pocketshell.cli import main


@pytest.fixture(autouse=True)
def _restore_cwd():
    """Restore the working directory after each test.

    ``launch_agent`` ``os.chdir``-es into the target folder (correct
    production behaviour — the agent must run there). In tests the target
    is a ``tmp_path`` that pytest deletes on teardown; leaving the process
    cwd inside a deleted dir breaks pytest's own end-of-session reporting.
    Restoring the cwd here keeps the suite clean.
    """
    original = os.getcwd()
    try:
        yield
    finally:
        os.chdir(original)


# ---------------------------------------------------------------------------
# Env-strip scope: ALL three agents strip the 71 provider vars (issue #703)
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("kind", agents.AGENT_KINDS)
def test_every_agent_strips_every_provider_var(kind):
    base = {name: "secret" for name in agents.PROVIDER_ENV_UNSET_VARS}
    base["PATH"] = "/usr/bin"
    env = agents.build_env(kind, base, {})
    for name in agents.PROVIDER_ENV_UNSET_VARS:
        assert name not in env, f"{kind} must unset {name}"
    # Non-provider vars survive.
    assert env["PATH"] == "/usr/bin"


def test_codex_strips_provider_vars():
    base = {"ANTHROPIC_API_KEY": "k", "OPENAI_API_KEY": "k2", "PATH": "/usr/bin"}
    env = agents.build_env("codex", base, {})
    assert "ANTHROPIC_API_KEY" not in env
    assert "OPENAI_API_KEY" not in env
    assert env["PATH"] == "/usr/bin"


def test_claude_strips_provider_vars():
    base = {"ANTHROPIC_API_KEY": "k", "OPENAI_API_KEY": "k2", "PATH": "/usr/bin"}
    env = agents.build_env("claude", base, {})
    assert "ANTHROPIC_API_KEY" not in env
    assert "OPENAI_API_KEY" not in env
    assert env["PATH"] == "/usr/bin"


def test_env_strip_list_is_seventy_one_unique_vars():
    assert len(agents.PROVIDER_ENV_UNSET_VARS) == 71
    assert len(set(agents.PROVIDER_ENV_UNSET_VARS)) == 71
    assert agents.PROVIDER_ENV_UNSET_VARS[0] == "AWS_ACCESS_KEY_ID"
    assert agents.PROVIDER_ENV_UNSET_VARS[-1] == "GEMINI_API_KEY"
    # The provider keys that cost real money are present.
    for v in (
        "ANTHROPIC_API_KEY",
        "OPENAI_API_KEY",
        "OPENCODE_API_KEY",
        "OPENCODE_ZEN_API_KEY",
        "GEMINI_API_KEY",
    ):
        assert v in agents.PROVIDER_ENV_UNSET_VARS


def test_folder_exports_layer_on_top_of_base_env():
    base = {"FOO": "old", "PATH": "/usr/bin"}
    env = agents.build_env("codex", base, {"FOO": "new", "BAR": "baz"})
    assert env["FOO"] == "new"
    assert env["BAR"] == "baz"
    assert env["PATH"] == "/usr/bin"


@pytest.mark.parametrize("kind", agents.AGENT_KINDS)
def test_provider_var_stripped_even_from_folder_exports(kind):
    # A provider key coming from the folder's .env must still be stripped
    # for every agent (billing), not just from the base env.
    env = agents.build_env(kind, {}, {"OPENAI_API_KEY": "from-dotenv"})
    assert "OPENAI_API_KEY" not in env


# ---------------------------------------------------------------------------
# Config-dir → profile env var
# ---------------------------------------------------------------------------


def test_codex_config_dir_sets_codex_home():
    env = agents.build_env("codex", {}, {}, config_dir="/home/u/.codex-work")
    assert env["CODEX_HOME"] == "/home/u/.codex-work"


def test_claude_config_dir_sets_claude_config_dir():
    env = agents.build_env("claude", {}, {}, config_dir="/home/u/.claude-work")
    assert env["CLAUDE_CONFIG_DIR"] == "/home/u/.claude-work"


def test_opencode_ignores_config_dir():
    env = agents.build_env("opencode", {}, {}, config_dir="/whatever")
    assert "CODEX_HOME" not in env
    assert "CLAUDE_CONFIG_DIR" not in env


def test_no_config_dir_leaves_profile_vars_unset():
    env = agents.build_env("codex", {}, {})
    assert "CODEX_HOME" not in env


# ---------------------------------------------------------------------------
# argv: prompt-suppression + skip-permissions flags
# ---------------------------------------------------------------------------


def test_codex_argv_suppresses_update_check():
    argv = agents.build_argv("codex", skip_permissions=True)
    # The update-check-off config is what fixes "agent doesn't start".
    assert "check_for_update_on_startup=false" in argv
    assert argv[:3] == ["codex", "-c", "check_for_update_on_startup=false"]
    assert "--dangerously-bypass-approvals-and-sandbox" in argv


def test_codex_argv_without_skip_permissions_still_suppresses_update():
    argv = agents.build_argv("codex", skip_permissions=False)
    assert "check_for_update_on_startup=false" in argv
    assert "--dangerously-bypass-approvals-and-sandbox" not in argv


def test_claude_argv_skip_permissions_flag():
    on = agents.build_argv("claude", skip_permissions=True)
    off = agents.build_argv("claude", skip_permissions=False)
    assert on == ["claude", "--dangerously-skip-permissions"]
    assert off == ["claude"]


def test_opencode_argv_is_bare_no_flags():
    on = agents.build_argv("opencode", skip_permissions=True)
    off = agents.build_argv("opencode", skip_permissions=False)
    assert on == ["opencode"]
    assert off == ["opencode"]


def test_unknown_kind_argv_raises():
    with pytest.raises(ValueError):
        agents.build_argv("nope", skip_permissions=True)


# ---------------------------------------------------------------------------
# claude trust seeding
# ---------------------------------------------------------------------------


def test_claude_config_path_default_home(tmp_path):
    env = {"HOME": str(tmp_path)}
    assert agents.claude_config_path(env) == tmp_path / ".claude.json"


def test_claude_config_path_honours_config_dir(tmp_path):
    env = {"HOME": "/ignored", "CLAUDE_CONFIG_DIR": str(tmp_path / "prof")}
    assert agents.claude_config_path(env) == tmp_path / "prof" / ".claude.json"


def test_seed_claude_trust_creates_entry(tmp_path):
    cfg = tmp_path / ".claude.json"
    cfg.write_text(json.dumps({"projects": {}}), encoding="utf-8")
    agents.seed_claude_trust(cfg, "/srv/app")
    data = json.loads(cfg.read_text(encoding="utf-8"))
    assert data["projects"]["/srv/app"]["hasTrustDialogAccepted"] is True


def test_seed_claude_trust_preserves_existing_keys(tmp_path):
    cfg = tmp_path / ".claude.json"
    cfg.write_text(
        json.dumps(
            {
                "numStartups": 5,
                "projects": {
                    "/other": {"hasTrustDialogAccepted": True, "x": 1},
                },
            }
        ),
        encoding="utf-8",
    )
    agents.seed_claude_trust(cfg, "/srv/app")
    data = json.loads(cfg.read_text(encoding="utf-8"))
    # Existing top-level + other-project state untouched.
    assert data["numStartups"] == 5
    assert data["projects"]["/other"] == {"hasTrustDialogAccepted": True, "x": 1}
    # New project trusted.
    assert data["projects"]["/srv/app"]["hasTrustDialogAccepted"] is True


def test_seed_claude_trust_missing_file_creates_it(tmp_path):
    cfg = tmp_path / "nested" / ".claude.json"
    agents.seed_claude_trust(cfg, "/srv/app")
    data = json.loads(cfg.read_text(encoding="utf-8"))
    assert data["projects"]["/srv/app"]["hasTrustDialogAccepted"] is True


def test_seed_claude_trust_corrupt_file_is_noop(tmp_path):
    cfg = tmp_path / ".claude.json"
    cfg.write_text("not json {{{", encoding="utf-8")
    # Must not raise — best-effort.
    agents.seed_claude_trust(cfg, "/srv/app")
    # Left the corrupt file alone (we don't clobber unknown state).
    assert cfg.read_text(encoding="utf-8") == "not json {{{"


def test_seed_claude_trust_already_trusted_is_noop(tmp_path):
    cfg = tmp_path / ".claude.json"
    original = json.dumps(
        {"projects": {"/srv/app": {"hasTrustDialogAccepted": True}}}
    )
    cfg.write_text(original, encoding="utf-8")
    agents.seed_claude_trust(cfg, "/srv/app")
    data = json.loads(cfg.read_text(encoding="utf-8"))
    assert data["projects"]["/srv/app"]["hasTrustDialogAccepted"] is True


# ---------------------------------------------------------------------------
# launch_agent: exec shape + --dir handling (exec injected)
# ---------------------------------------------------------------------------


class _FakeCtx:
    def exit(self, code=0):  # pragma: no cover - only hit on bad dir
        raise click.exceptions.Exit(code)


def test_launch_agent_codex_execs_with_suppressed_update(tmp_path, monkeypatch):
    captured = {}

    def fake_execvpe(file, argv, env):
        captured["file"] = file
        captured["argv"] = argv
        captured["env"] = env

    monkeypatch.setenv("ANTHROPIC_API_KEY", "live-key")
    agents.launch_agent(
        _FakeCtx(),
        "codex",
        str(tmp_path),
        skip_permissions=True,
        config_dir=None,
        execvpe=fake_execvpe,
    )
    assert captured["file"] == "codex"
    assert "check_for_update_on_startup=false" in captured["argv"]
    assert "--dangerously-bypass-approvals-and-sandbox" in captured["argv"]
    # codex strips the provider env too (issue #703 — all 3 strip).
    assert "ANTHROPIC_API_KEY" not in captured["env"]


def test_launch_agent_opencode_strips_env(tmp_path, monkeypatch):
    captured = {}

    def fake_execvpe(file, argv, env):
        captured["env"] = env
        captured["argv"] = argv

    monkeypatch.setenv("OPENAI_API_KEY", "live-key")
    agents.launch_agent(
        _FakeCtx(),
        "opencode",
        str(tmp_path),
        skip_permissions=True,
        config_dir=None,
        execvpe=fake_execvpe,
    )
    assert "OPENAI_API_KEY" not in captured["env"]
    assert captured["argv"] == ["opencode"]


def test_launch_agent_claude_strips_env(tmp_path, monkeypatch):
    captured = {}

    def fake_execvpe(file, argv, env):
        captured["env"] = env
        captured["argv"] = argv

    monkeypatch.setenv("HOME", str(tmp_path))
    monkeypatch.setenv("OPENAI_API_KEY", "live-key")
    agents.launch_agent(
        _FakeCtx(),
        "claude",
        str(tmp_path),
        skip_permissions=True,
        config_dir=None,
        execvpe=fake_execvpe,
    )
    # claude strips the provider env too (issue #703 — all 3 strip).
    assert "OPENAI_API_KEY" not in captured["env"]
    assert captured["argv"] == ["claude", "--dangerously-skip-permissions"]


def test_launch_agent_claude_seeds_trust(tmp_path, monkeypatch):
    home = tmp_path / "home"
    home.mkdir()
    workdir = tmp_path / "proj"
    workdir.mkdir()
    monkeypatch.setenv("HOME", str(home))

    def fake_execvpe(file, argv, env):
        pass

    agents.launch_agent(
        _FakeCtx(),
        "claude",
        str(workdir),
        skip_permissions=True,
        config_dir=None,
        execvpe=fake_execvpe,
    )
    data = json.loads((home / ".claude.json").read_text(encoding="utf-8"))
    assert data["projects"][str(workdir)]["hasTrustDialogAccepted"] is True


def test_launch_agent_merges_folder_env(tmp_path, monkeypatch):
    (tmp_path / ".env").write_text("MY_FOLDER_VAR=hello\n", encoding="utf-8")
    captured = {}

    def fake_execvpe(file, argv, env):
        captured["env"] = env

    agents.launch_agent(
        _FakeCtx(),
        "codex",
        str(tmp_path),
        skip_permissions=False,
        config_dir=None,
        execvpe=fake_execvpe,
    )
    assert captured["env"]["MY_FOLDER_VAR"] == "hello"


def test_launch_agent_missing_dir_exits_two():
    with pytest.raises(click.exceptions.Exit) as exc:
        agents.launch_agent(
            _FakeCtx(),
            "codex",
            "/no/such/dir/ps703",
            skip_permissions=True,
            config_dir=None,
            execvpe=lambda *a: None,
        )
    assert exc.value.exit_code == 2


# ---------------------------------------------------------------------------
# CLI wiring end-to-end (exec stubbed out)
# ---------------------------------------------------------------------------


def test_cli_agent_codex_invokes_execvpe(tmp_path, monkeypatch):
    captured = {}

    def fake_execvpe(file, argv, env):
        captured["file"] = file
        captured["argv"] = argv

    monkeypatch.setattr(agents.os, "execvpe", fake_execvpe)
    rc = main(["agent", "codex", "--dir", str(tmp_path)])
    assert rc == 0
    assert captured["file"] == "codex"
    assert "check_for_update_on_startup=false" in captured["argv"]


def test_cli_agent_no_skip_permissions_flag(tmp_path, monkeypatch):
    captured = {}
    monkeypatch.setattr(
        agents.os, "execvpe", lambda f, a, e: captured.update(argv=a)
    )
    main(["agent", "claude", "--dir", str(tmp_path), "--no-skip-permissions"])
    assert captured["argv"] == ["claude"]


def test_cli_agent_config_dir_sets_profile_env(tmp_path, monkeypatch):
    captured = {}
    monkeypatch.setattr(
        agents.os, "execvpe", lambda f, a, e: captured.update(env=e)
    )
    main(
        [
            "agent",
            "codex",
            "--dir",
            str(tmp_path),
            "--config-dir",
            "/home/u/.codex-work",
        ]
    )
    assert captured["env"]["CODEX_HOME"] == "/home/u/.codex-work"


def test_cli_agent_missing_dir_exits_two(monkeypatch):
    monkeypatch.setattr(agents.os, "execvpe", lambda *a: None)
    rc = main(["agent", "codex", "--dir", "/no/such/dir/ps703"])
    assert rc == 2


def test_cli_agent_help_lists_all_three_kinds():
    rc = main(["agent", "--help"])
    assert rc == 0


# ---------------------------------------------------------------------------
# `--profile` resolution (#718): name → config_dir via discovery
# ---------------------------------------------------------------------------


def _seed_zlaude_home(tmp_path, monkeypatch):
    """Build a fake HOME with ~/.claude + ~/.zlaude and point HOME at it."""
    home = tmp_path / "home"
    home.mkdir()
    for name in (".claude", ".zlaude"):
        d = home / name
        d.mkdir()
        (d / "settings.json").write_text("{}", encoding="utf-8")
        (d / ".claude.json").write_text("{}", encoding="utf-8")
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.delenv("XDG_CONFIG_HOME", raising=False)
    return home


def test_cli_agent_profile_resolves_to_claude_config_dir(tmp_path, monkeypatch):
    home = _seed_zlaude_home(tmp_path, monkeypatch)
    workdir = tmp_path / "work"
    workdir.mkdir()
    captured = {}
    monkeypatch.setattr(
        agents.os, "execvpe", lambda f, a, e: captured.update(env=e)
    )
    rc = main(
        [
            "agent",
            "claude",
            "--dir",
            str(workdir),
            "--profile",
            "Claude (Z.AI)",
        ]
    )
    assert rc == 0
    assert captured["env"]["CLAUDE_CONFIG_DIR"] == str(home / ".zlaude")


def test_cli_agent_default_profile_leaves_config_dir_unset(tmp_path, monkeypatch):
    _seed_zlaude_home(tmp_path, monkeypatch)
    workdir = tmp_path / "work"
    workdir.mkdir()
    captured = {}
    monkeypatch.setattr(
        agents.os, "execvpe", lambda f, a, e: captured.update(env=e)
    )
    rc = main(
        ["agent", "claude", "--dir", str(workdir), "--profile", "Claude"]
    )
    assert rc == 0
    assert "CLAUDE_CONFIG_DIR" not in captured["env"]


def test_cli_agent_unknown_profile_errors(tmp_path, monkeypatch):
    _seed_zlaude_home(tmp_path, monkeypatch)
    workdir = tmp_path / "work"
    workdir.mkdir()
    monkeypatch.setattr(agents.os, "execvpe", lambda *a: None)
    rc = main(
        ["agent", "claude", "--dir", str(workdir), "--profile", "Nope"]
    )
    assert rc == 2


def test_cli_agent_profile_and_config_dir_mutually_exclusive(tmp_path, monkeypatch):
    _seed_zlaude_home(tmp_path, monkeypatch)
    workdir = tmp_path / "work"
    workdir.mkdir()
    monkeypatch.setattr(agents.os, "execvpe", lambda *a: None)
    rc = main(
        [
            "agent",
            "claude",
            "--dir",
            str(workdir),
            "--profile",
            "Claude",
            "--config-dir",
            "/whatever",
        ]
    )
    assert rc == 2
