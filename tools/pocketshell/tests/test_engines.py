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
    listed_row = next(
        item for item in listed["engines"] if item["id"] == "disabled-engine"
    )
    assert listed_row["available_for_create"] is False
    # `disabled-agent` is installed nowhere either, so BOTH halves of the
    # state are reported (issue #2276 round 4: the disablement used to mask
    # the more actionable "not installed" half).
    assert listed_row["unavailable_reason"] == (
        "`disabled-agent` is not installed on this host (not on PATH) "
        "and is disabled in the host registry."
    )

    # The wrapper command is registry-driven, but a disabled engine must not
    # launch merely because its harness is configured.
    result = CliRunner().invoke(cli, ["agent", "disabled-engine", "--dir", str(tmp_path)])
    assert result.exit_code == 126
    assert "is disabled in the host registry" in result.output


def test_manifest_probe_overrides_stale_configured_availability_for_missing_harness(
    tmp_path,
):
    """The manifest must report the current PATH, not a stale config bit."""
    config_dir = tmp_path / "config" / "pocketshell"
    config_dir.mkdir(parents=True)
    (config_dir / "engines.yaml").write_text(
        """
engines:
  - id: missing-engine
    family: codex
    harness: definitely-missing-engine
    label: Missing Engine
    available: true
    enabled: true
""".lstrip(),
        encoding="utf-8",
    )

    registry = engines.load_registry(
        {
            "XDG_CONFIG_HOME": str(tmp_path / "config"),
            "PATH": str(tmp_path / "empty-bin"),
        }
    )
    missing = next(item for item in registry if item.id == "missing-engine")

    assert missing.available is False
    assert missing.available_for_create is False
    assert missing.unavailable_reason == (
        "`definitely-missing-engine` is not installed on this host (not on PATH)."
    )


def test_manifest_probe_checks_each_registered_harness_once(tmp_path, monkeypatch):
    """Availability is a single manifest-build observation, not polling."""
    _engine_config(tmp_path)
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "config"))
    probed: list[str] = []

    def record_probe(name, *, path=None):
        probed.append(name)
        return f"/fixture/{name}"

    monkeypatch.setattr(engines.shutil, "which", record_probe)
    registry = engines.load_registry()

    assert probed == [item.harness for item in registry]
    assert all(item.available for item in registry)


def test_manifest_records_distinct_disabled_reason_for_present_engine(tmp_path):
    """A configured disablement is not reported as a missing executable."""
    config_dir = tmp_path / "config" / "pocketshell"
    config_dir.mkdir(parents=True)
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    harness = bin_dir / "present-engine"
    harness.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    harness.chmod(0o755)
    (config_dir / "engines.yaml").write_text(
        """
engines:
  - id: disabled-engine
    family: codex
    harness: present-engine
    label: Disabled Engine
    enabled: false
""".lstrip(),
        encoding="utf-8",
    )

    registry = engines.load_registry(
        {
            "XDG_CONFIG_HOME": str(tmp_path / "config"),
            "PATH": str(bin_dir),
        }
    )
    disabled = next(item for item in registry if item.id == "disabled-engine")

    assert disabled.enabled is False
    assert disabled.available is True
    assert disabled.available_for_create is False
    assert disabled.unavailable_reason == "disabled in the host registry"

    payload = engines.registry_payload(registry)
    payload_row = next(
        item for item in payload["engines"] if item["id"] == "disabled-engine"
    )
    assert payload_row["available"] is True
    assert payload_row["available_for_create"] is False
    assert payload_row["unavailable_reason"] == "disabled in the host registry"


def test_installed_engine_stays_createable_despite_stale_aplexer_unavailable(
    tmp_path, monkeypatch, install_fake_a
):
    """#2276 reported symptom: an INSTALLED Codex never reached the picker.

    aplexer's ``a engines --json`` carries its own ``available`` bit. When that
    bit is stale/false for a harness that is genuinely on PATH, the manifest
    used to inherit it verbatim and the create picker hid the engine forever,
    so the already-shipped Codex profiles were unreachable. The final PATH
    observation must win in BOTH directions, not only when it is more
    restrictive.
    """
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    for harness in ("claude", "codex"):
        executable = bin_dir / harness
        executable.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        executable.chmod(0o755)
    install_fake_a(
        engines=[
            {"name": "claude", "command": ["claude"], "available": True},
            # Stale: the binary IS installed below, aplexer says otherwise.
            {
                "name": "codex",
                "command": ["codex", "--from-aplexer"],
                "available": False,
            },
        ]
    )
    # `bin_dir` first so the fixture harnesses win; the system dirs stay on
    # PATH only so the stub `a` interpreter resolves. Neither holds a real
    # `claude`/`codex`.
    monkeypatch.setenv("PATH", f"{bin_dir}:/usr/bin:/bin")

    registry = engines.load_registry()
    codex = next(item for item in registry if item.id == "codex")

    # Guards this test against passing vacuously with the overlay skipped:
    # the argv proves aplexer's rows really were read and applied.
    assert codex.launch.argv == ("codex", "--from-aplexer")
    assert codex.available is True
    assert codex.available_for_create is True
    assert codex.unavailable_reason is None

    payload_row = next(
        item
        for item in engines.registry_payload(registry)["engines"]
        if item["id"] == "codex"
    )
    assert payload_row["available"] is True
    assert payload_row["available_for_create"] is True
    assert payload_row["unavailable_reason"] is None


# ---------------------------------------------------------------------------
# Issue #2276 round 4: the harness is installed, launchable from the tmux
# pane's LOGIN shell, but invisible to the app's NON-interactive SSH exec
# channel (`PocketshellCommand.wrap` -> `pocketshell engines list --json`).
# On the maintainer's host that is `~/.nvm/versions/node/v24.13.1/bin/codex`.
# The reported state lives strictly between "on PATH" and "absent everywhere",
# and the probe must resolve harnesses the way they are actually LAUNCHED.
# ---------------------------------------------------------------------------


def _install_stub(directory, name: str):
    directory.mkdir(parents=True, exist_ok=True)
    executable = directory / name
    executable.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    executable.chmod(0o755)
    return executable


def _fake_login_shell(tmp_path, exported_path: str, calls_log, name="fake-login-shell"):
    """A stand-in `$SHELL` whose login PATH differs from the exec PATH."""
    shell = tmp_path / name
    shell.write_text(
        "#!/bin/sh\n"
        f'printf "%s\\n" "$*" >> {calls_log}\n'
        f'printf %s "{exported_path}"\n',
        encoding="utf-8",
    )
    shell.chmod(0o755)
    return shell


def _fake_fish_login_shell(tmp_path, path_entries, calls_log):
    """A `$SHELL` that expands `"$PATH"` the way fish does: space separated.

    Asking such a shell with the POSIX `printf %s "$PATH"` form yields one
    unusable blob, so the registry must ask in a shell-agnostic way.
    """
    shell = tmp_path / "fake-fish-login-shell"
    shell.write_text(
        "#!/bin/sh\n"
        f'printf "%s\\n" "$*" >> {calls_log}\n'
        'case "$*" in\n'
        f"  *printenv*) printf %s '{':'.join(path_entries)}' ;;\n"
        f"  *) printf %s '{' '.join(path_entries)}' ;;\n"
        "esac\n",
        encoding="utf-8",
    )
    shell.chmod(0o755)
    return shell


def test_manifest_reports_harness_installed_outside_the_exec_path(tmp_path):
    """#2276: nvm-installed codex/opencode are hidden by a bare PATH probe.

    The exec channel PATH holds only `claude`; `codex`/`opencode` live in an
    nvm node bin dir exactly as on the maintainer's host. The login-shell rung
    is switched OFF here so this test isolates the absolute-candidate rung.
    """
    home = tmp_path / "home"
    nvm_bin = home / ".nvm" / "versions" / "node" / "v24.13.1" / "bin"
    for name in ("codex", "opencode"):
        _install_stub(nvm_bin, name)
    exec_bin = tmp_path / "exec-bin"
    _install_stub(exec_bin, "claude")

    registry = engines.load_registry(
        {
            "HOME": str(home),
            "PATH": str(exec_bin),
            "XDG_CONFIG_HOME": str(tmp_path / "no-config"),
            engines.LOGIN_SHELL_PROBE_KILL: "0",
        }
    )
    by_id = {item.id: item for item in registry}

    assert by_id["codex"].available is True
    assert by_id["codex"].available_for_create is True
    assert by_id["codex"].unavailable_reason is None
    assert by_id["opencode"].available is True
    assert by_id["opencode"].available_for_create is True
    # The engine that IS on the exec PATH keeps working ...
    assert by_id["claude"].available is True
    # ... and the resolver stays honest: `grok` is installed nowhere here.
    assert by_id["grok"].available is False
    assert by_id["grok"].unavailable_reason == (
        "`grok` is not installed on this host (not on PATH)."
    )


def test_manifest_resolves_a_harness_through_the_login_shell_path(tmp_path):
    """The login shell is the environment the harness is really launched in.

    tmux runs the user's shell as a login shell and the create flow types the
    wrapper into that pane, so a harness only reachable there must count as
    available even when no known absolute install location holds it.
    """
    engines.clear_resolution_cache()
    login_only_bin = tmp_path / "opt" / "site-agents" / "bin"
    _install_stub(login_only_bin, "login-only-agent")
    exec_bin = tmp_path / "exec-bin"
    calls_log = tmp_path / "login-shell-calls.txt"
    shell = _fake_login_shell(tmp_path, f"{login_only_bin}:{exec_bin}", calls_log)

    config_dir = tmp_path / "config" / "pocketshell"
    config_dir.mkdir(parents=True)
    (config_dir / "engines.yaml").write_text(
        """
engines:
  - id: login-only-engine
    family: codex
    harness: login-only-agent
    label: Login Only Engine
""".lstrip(),
        encoding="utf-8",
    )

    registry = engines.load_registry(
        {
            "HOME": str(tmp_path / "home"),
            "PATH": str(exec_bin),
            "SHELL": str(shell),
            "XDG_CONFIG_HOME": str(tmp_path / "config"),
        }
    )
    engine = next(item for item in registry if item.id == "login-only-engine")

    assert engine.available is True
    assert engine.available_for_create is True
    assert engine.unavailable_reason is None
    # One cheap manifest-time observation, not a subprocess storm: the login
    # shell is consulted ONCE for the whole registry, however many harnesses
    # are unresolved.
    assert calls_log.read_text(encoding="utf-8").splitlines() == [
        "-lc printenv PATH"
    ]


def test_manifest_login_shell_path_read_is_shell_agnostic(tmp_path):
    """A fish login shell must not defeat the resolution (class coverage).

    fish expands `"$PATH"` to a space-separated list, so the POSIX
    `printf %s "$PATH"` form returns one unusable blob there. The registry has
    to ask in a form every common login shell answers the same way.
    """
    engines.clear_resolution_cache()
    fish_only_bin = tmp_path / "opt" / "fish-agents" / "bin"
    _install_stub(fish_only_bin, "fish-only-agent")
    exec_bin = tmp_path / "exec-bin"
    calls_log = tmp_path / "fish-shell-calls.txt"
    shell = _fake_fish_login_shell(
        tmp_path, [str(fish_only_bin), str(exec_bin)], calls_log
    )

    config_dir = tmp_path / "config" / "pocketshell"
    config_dir.mkdir(parents=True)
    (config_dir / "engines.yaml").write_text(
        """
engines:
  - id: fish-only-engine
    family: codex
    harness: fish-only-agent
    label: Fish Only Engine
""".lstrip(),
        encoding="utf-8",
    )

    registry = engines.load_registry(
        {
            "HOME": str(tmp_path / "home"),
            "PATH": str(exec_bin),
            "SHELL": str(shell),
            "XDG_CONFIG_HOME": str(tmp_path / "config"),
        }
    )
    engine = next(item for item in registry if item.id == "fish-only-engine")

    assert engine.available is True
    assert engine.available_for_create is True


def test_manifest_probe_skips_the_login_shell_when_every_harness_is_on_path(tmp_path):
    """The happy path stays subprocess-free (the issue's no-polling non-goal)."""
    engines.clear_resolution_cache()
    exec_bin = tmp_path / "exec-bin"
    for name in ("claude", "codex", "opencode", "grok"):
        _install_stub(exec_bin, name)
    calls_log = tmp_path / "login-shell-calls.txt"
    shell = _fake_login_shell(tmp_path, str(exec_bin), calls_log)

    registry = engines.load_registry(
        {
            "HOME": str(tmp_path / "home"),
            "PATH": str(exec_bin),
            "SHELL": str(shell),
            "XDG_CONFIG_HOME": str(tmp_path / "no-config"),
        }
    )

    assert all(item.available for item in registry)
    assert not calls_log.exists()


def test_force_available_pins_an_engine_the_probe_cannot_resolve(tmp_path):
    """The manual escape hatch for a host layout the resolver cannot anticipate."""
    config_dir = tmp_path / "config" / "pocketshell"
    config_dir.mkdir(parents=True)
    (config_dir / "engines.yaml").write_text(
        """
engines:
  - id: exotic-engine
    family: codex
    harness: exotic-agent
    label: Exotic Engine
    force_available: true
""".lstrip(),
        encoding="utf-8",
    )

    registry = engines.load_registry(
        {
            "XDG_CONFIG_HOME": str(tmp_path / "config"),
            "PATH": str(tmp_path / "empty-bin"),
            engines.LOGIN_SHELL_PROBE_KILL: "0",
        }
    )
    exotic = next(item for item in registry if item.id == "exotic-engine")

    assert exotic.available is True
    assert exotic.available_for_create is True
    assert exotic.unavailable_reason is None
    payload_row = next(
        item
        for item in engines.registry_payload(registry)["engines"]
        if item["id"] == "exotic-engine"
    )
    assert payload_row["available_for_create"] is True
    assert payload_row["force_available"] is True


def test_force_available_false_hides_an_installed_engine(tmp_path):
    """The escape hatch works in both directions, with a distinct reason."""
    bin_dir = tmp_path / "bin"
    _install_stub(bin_dir, "present-agent")
    config_dir = tmp_path / "config" / "pocketshell"
    config_dir.mkdir(parents=True)
    (config_dir / "engines.yaml").write_text(
        """
engines:
  - id: pinned-off-engine
    family: codex
    harness: present-agent
    label: Pinned Off Engine
    force_available: false
""".lstrip(),
        encoding="utf-8",
    )

    registry = engines.load_registry(
        {
            "XDG_CONFIG_HOME": str(tmp_path / "config"),
            "PATH": str(bin_dir),
        }
    )
    pinned = next(item for item in registry if item.id == "pinned-off-engine")

    assert pinned.available is False
    assert pinned.available_for_create is False
    assert pinned.unavailable_reason == engines.FORCED_UNAVAILABLE_REASON


def test_missing_and_disabled_engine_reports_both_halves_of_the_state(tmp_path):
    """Neither half of a doubly-unavailable engine is silently dropped."""
    config_dir = tmp_path / "config" / "pocketshell"
    config_dir.mkdir(parents=True)
    (config_dir / "engines.yaml").write_text(
        """
engines:
  - id: gone-and-disabled
    family: codex
    harness: gone-agent
    label: Gone Engine
    enabled: false
""".lstrip(),
        encoding="utf-8",
    )

    registry = engines.load_registry(
        {
            "XDG_CONFIG_HOME": str(tmp_path / "config"),
            "PATH": str(tmp_path / "empty-bin"),
            engines.LOGIN_SHELL_PROBE_KILL: "0",
        }
    )
    gone = next(item for item in registry if item.id == "gone-and-disabled")

    assert gone.available is False
    assert gone.available_for_create is False
    assert gone.unavailable_reason == (
        "`gone-agent` is not installed on this host (not on PATH) "
        "and is disabled in the host registry."
    )


def test_configured_unavailable_reason_survives_the_probe(tmp_path):
    """A host-authored explanation is not overwritten by the derived one."""
    config_dir = tmp_path / "config" / "pocketshell"
    config_dir.mkdir(parents=True)
    (config_dir / "engines.yaml").write_text(
        """
engines:
  - id: explained-engine
    family: codex
    harness: explained-agent
    label: Explained Engine
    unavailable_reason: retired by the ops team, see runbook 42
""".lstrip(),
        encoding="utf-8",
    )

    registry = engines.load_registry(
        {
            "XDG_CONFIG_HOME": str(tmp_path / "config"),
            "PATH": str(tmp_path / "empty-bin"),
            engines.LOGIN_SHELL_PROBE_KILL: "0",
        }
    )
    explained = next(item for item in registry if item.id == "explained-engine")

    assert explained.available is False
    assert explained.unavailable_reason == "retired by the ops team, see runbook 42"


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


def test_aplexer_overlay_takes_command_and_env_unset_but_not_availability(
    tmp_path, monkeypatch, install_fake_a
):
    """Issue #2276: availability is THIS host's own observation.

    aplexer ships its own cached ``available`` bit. Inheriting it made a second
    tool's stale answer authoritative for the picker, so the overlay now
    carries launch data only (D22 hard cut — there is no legacy path that
    still reads it).
    """
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
    # aplexer said `available: False`; the registry does not inherit it.
    assert claude.available is True
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


def test_builtin_usage_providers_are_names_the_pinned_quse_actually_reports():
    """#2293: every builtin `usage_provider` must exist in the PINNED quse.

    `usage_provider` names the quse provider whose quota belongs to an engine.
    quse has no provider literally called `opencode` — the OpenCode-on-Go
    backend is reported as `go` (`quse.opencode_go_quota`) — so the shipped
    `opencode` entry pointed at a provider name that `pocketshell usage
    <provider>` would reject with "Unknown provider". Validate against what
    the pinned wheel advertises rather than a second hardcoded list, so a
    future engine (or a future quse rename) cannot reintroduce the drift.
    """
    from quse.usage import USAGE_PROVIDER_CHOICES

    declared = {
        manifest.id: manifest.usage_provider
        for manifest in engines.builtin_manifests()
        if manifest.usage_provider is not None
    }
    assert declared, "the builtin registry must declare usage providers"
    unknown = {
        engine_id: provider
        for engine_id, provider in declared.items()
        if provider not in USAGE_PROVIDER_CHOICES
    }
    assert not unknown, (
        f"builtin engines name usage providers the pinned quse does not report: "
        f"{unknown}; quse advertises {USAGE_PROVIDER_CHOICES}"
    )
    # Load-bearing specific: OpenCode's quota is quse's `go` provider.
    assert declared["opencode"] == "go"
