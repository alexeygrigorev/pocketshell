from __future__ import annotations

import json

from click.testing import CliRunner

from pocketshell import agents, engines
from pocketshell.cli import cli


def _engine_config(tmp_path):
    config_dir = tmp_path / "config" / "pocketshell"
    config_dir.mkdir(parents=True)
    (config_dir / "engines.yaml").write_text(
        """
engines:
  - id: test-engine
    family: codex
    harness: test-agent
    label: Test Engine
    provider_mark: Test Provider
    usage_provider: test-provider
    enabled: true
    available: true
    launch:
      argv: [test-agent, --mode, interactive]
      skip_permissions_argv: [--skip]
      supports_skip_permissions: true
      env:
        unset: [TEST_SECRET]
        set:
          TEST_MODE: registry
      profile:
        env_var: TEST_HOME
        default_dirname: .test-agent
        markers: [config.toml]
        name_hints: [test-agent]
        default_label: Test Engine
  - id: disabled-engine
    family: codex
    harness: disabled-agent
    label: Disabled Engine
    provider_mark: Disabled Provider
    enabled: false
    available: true
    launch:
      argv: [disabled-agent]
""".lstrip(),
        encoding="utf-8",
    )
    return config_dir


def test_registry_loads_open_engine_ids_and_declarative_launch_data(tmp_path, monkeypatch):
    _engine_config(tmp_path)
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "config"))

    registry = engines.load_registry(probe=False)
    test_engine = next(item for item in registry if item.id == "test-engine")

    assert test_engine.family == "codex"
    assert test_engine.harness == "test-agent"
    assert test_engine.label == "Test Engine"
    assert test_engine.provider_mark == "Test Provider"
    assert test_engine.usage_provider == "test-provider"
    assert test_engine.launch.argv == ("test-agent", "--mode", "interactive")
    assert test_engine.launch.skip_permissions_argv == ("--skip",)
    assert "TEST_SECRET" in test_engine.launch.env_unset
    assert "OPENAI_API_KEY" in test_engine.launch.env_unset
    assert test_engine.launch.env_set == (("TEST_MODE", "registry"),)
    assert test_engine.launch.profile is not None
    assert test_engine.launch.profile.env_var == "TEST_HOME"

    payload = engines.registry_payload(registry)
    assert next(item for item in payload["engines"] if item["id"] == "test-engine")[
        "available_for_create"
    ] is True


def test_disabled_engine_is_listed_but_not_createable_or_wrappable(tmp_path, monkeypatch):
    _engine_config(tmp_path)
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "config"))

    registry = engines.load_registry(probe=False)
    disabled = next(item for item in registry if item.id == "disabled-engine")

    assert disabled.enabled is False
    assert disabled.available is True
    assert disabled.available_for_create is False

    result = CliRunner().invoke(cli, ["engines", "list", "--json"])
    assert result.exit_code == 0, result.output
    listed = json.loads(result.output)
    assert next(item for item in listed["engines"] if item["id"] == "disabled-engine")[
        "available_for_create"
    ] is False

    # The wrapper command is registry-driven, but a disabled engine must not
    # launch merely because its harness is configured.
    result = CliRunner().invoke(cli, ["agent", "disabled-engine", "--dir", str(tmp_path)])
    assert result.exit_code == 126
    assert "is disabled in the host registry" in result.output


def test_registry_engine_is_accepted_by_generic_agent_helpers(tmp_path, monkeypatch):
    _engine_config(tmp_path)
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "config"))

    assert agents.build_argv("test-engine", skip_permissions=True) == [
        "test-agent",
        "--mode",
        "interactive",
        "--skip",
    ]
    env = agents.build_env(
        "test-engine",
        {
            "TEST_SECRET": "remove",
            "OPENAI_API_KEY": "must-be-stripped",
            "PATH": "/bin",
        },
        {},
        extra_env={"TEST_HOME": "/tmp/test-profile"},
    )
    assert env["TEST_MODE"] == "registry"
    assert "TEST_SECRET" not in env
    assert "OPENAI_API_KEY" not in env
    assert env["TEST_HOME"] == "/tmp/test-profile"


def test_generic_click_wrapper_resolves_and_launches_custom_engine(
    tmp_path, monkeypatch
):
    _engine_config(tmp_path)
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "config"))
    monkeypatch.setenv("OPENAI_API_KEY", "must-not-reach-custom-engine")
    monkeypatch.chdir(tmp_path)

    launched: dict[str, object] = {}

    def fake_execvpe(file, argv, env):
        launched["file"] = file
        launched["argv"] = list(argv)
        launched["env"] = dict(env)

    monkeypatch.setattr(
        agents.shutil,
        "which",
        lambda _name, path=None: "/fixture/test-agent",
    )
    monkeypatch.setattr(agents.os, "execvpe", fake_execvpe)

    result = CliRunner().invoke(
        cli,
        ["agent", "test-engine", "--dir", str(tmp_path)],
    )

    assert result.exit_code == 0, result.output
    assert launched["file"] == "test-agent"
    assert launched["argv"] == [
        "test-agent",
        "--mode",
        "interactive",
        "--skip",
    ]
    assert launched["env"]["TEST_MODE"] == "registry"
    assert "OPENAI_API_KEY" not in launched["env"]


def test_aplexer_overlay_takes_command_env_unset_and_available(
    tmp_path, monkeypatch, install_fake_a
):
    install_fake_a(
        engines=[
            {
                "name": "claude",
                "command": ["claude", "--from-aplexer"],
                "available": False,
                "env_unset": ["OPENAI_API_KEY", "CUSTOM_UNSET"],
            },
            {"name": "shell", "command": ["/bin/bash", "-l"], "available": True},
            {"name": "gemini", "command": ["gemini"], "available": True},
        ]
    )
    registry = engines.load_registry(probe=False)
    ids = [item.id for item in registry]
    assert "shell" not in ids
    assert "gemini" not in ids
    claude = next(item for item in registry if item.id == "claude")
    assert claude.launch.argv == ("claude", "--from-aplexer")
    assert claude.label == "Claude"
    assert claude.family == "claude"
    assert "CUSTOM_UNSET" in claude.launch.env_unset
    assert "OPENAI_API_KEY" in claude.launch.env_unset
    assert claude.available is False
    assert claude.launch.skip_permissions_argv == (
        "--dangerously-skip-permissions",
    )


def test_yaml_override_still_wins_over_aplexer(
    tmp_path, monkeypatch, install_fake_a
):
    _engine_config(tmp_path)
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "config"))
    install_fake_a(
        engines=[
            {
                "name": "claude",
                "command": ["claude", "--from-aplexer"],
                "available": True,
                "env_unset": ["OPENAI_API_KEY"],
            }
        ]
    )
    registry = engines.load_registry(probe=False)
    assert any(item.id == "test-engine" for item in registry)
    claude = next(item for item in registry if item.id == "claude")
    # No yaml override for claude → aplexer argv sticks.
    assert claude.launch.argv == ("claude", "--from-aplexer")


def test_aplexer_engine_probe_failure_keeps_builtins(install_fake_a):
    install_fake_a(exit_code=2)
    registry = engines.load_registry(probe=False)
    assert [item.id for item in registry] == [
        "claude",
        "codex",
        "opencode",
        "grok",
    ]
