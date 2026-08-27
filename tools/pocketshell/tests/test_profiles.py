"""Tests for `pocketshell profiles` — discovery, merge, and the CLI.

Issue #718 slice 1. Covers:

- conventional-dir discovery (fake HOME with ~/.claude + ~/.zlaude → two
  Claude profiles, the default flagged);
- codex discovery + the optional profiles.yaml merge (explicit wins);
- `profiles list` YAML + --json output shape and --engine filtering;
- `resolve_profile` for a named non-default profile and the unknown error;
- no secrets leak into the listed output.
"""

from __future__ import annotations

import json
import logging
import os

import pytest
import yaml

from pocketshell import profiles
from pocketshell.cli import main


# ---------------------------------------------------------------------------
# Fixtures: a fake HOME with the maintainer-style config dirs.
# ---------------------------------------------------------------------------


@pytest.fixture(autouse=True)
def disable_ambient_aplexer_probe(monkeypatch):
    """Keep native profile tests isolated; A1 tests explicitly opt in."""
    monkeypatch.setenv("POCKETSHELL_APLEXER_PROFILES", "0")


def _make_claude_dir(path):
    path.mkdir(parents=True, exist_ok=True)
    (path / "settings.json").write_text("{}", encoding="utf-8")
    (path / ".claude.json").write_text("{}", encoding="utf-8")
    # A secret-shaped file discovery must NEVER read or expose.
    (path / "auth.json").write_text('{"apiKey": "sk-SECRET"}', encoding="utf-8")


def _make_codex_dir(path):
    path.mkdir(parents=True, exist_ok=True)
    (path / "config.toml").write_text("", encoding="utf-8")
    (path / "auth.json").write_text('{"OPENAI_API_KEY": "sk-SECRET"}', encoding="utf-8")


@pytest.fixture
def fake_home(tmp_path, monkeypatch):
    """A HOME with ~/.claude, ~/.zlaude, ~/.codex (markers present)."""
    home = tmp_path / "home"
    home.mkdir()
    _make_claude_dir(home / ".claude")
    _make_claude_dir(home / ".zlaude")
    _make_codex_dir(home / ".codex")
    # An empty stray dir that must NOT become a phantom profile.
    (home / ".notclaude").mkdir()
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.delenv("XDG_CONFIG_HOME", raising=False)
    monkeypatch.setattr("os.path.expanduser", lambda p: p.replace("~", str(home)))
    return home


class _FakeAplexerClient:
    def __init__(self, *, payload=None, error=None, calls=None):
        self.payload = payload if payload is not None else {}
        self.error = error
        self.calls = calls if calls is not None else []

    def profiles(self):
        self.calls.append("profiles")
        if self.error is not None:
            raise self.error
        return self.payload


def _install_fake_client(monkeypatch, *, payload=None, error=None, calls=None):
    fake = _FakeAplexerClient(payload=payload, error=error, calls=calls)
    monkeypatch.setattr(profiles, "_aplexer_client", lambda: fake)
    monkeypatch.setenv("POCKETSHELL_APLEXER_PROFILES", "1")
    return fake


# ---------------------------------------------------------------------------
# Discovery
# ---------------------------------------------------------------------------


def test_discovers_default_and_sibling_claude_profiles(fake_home):
    discovered = profiles.discover_profiles({"HOME": str(fake_home)})
    claude = [p for p in discovered if p.engine == "claude"]
    names = {p.name for p in claude}
    assert "Claude" in names
    assert "Claude (Z.AI)" in names  # ~/.zlaude via the known-alias map
    assert len(claude) == 2


def test_default_claude_profile_is_flagged_and_has_no_config_dir(fake_home):
    discovered = profiles.discover_profiles({"HOME": str(fake_home)})
    default = next(p for p in discovered if p.name == "Claude")
    assert default.default is True
    assert default.config_dir is None


def test_zlaude_profile_resolves_to_its_dir(fake_home):
    discovered = profiles.discover_profiles({"HOME": str(fake_home)})
    zlaude = next(p for p in discovered if p.name == "Claude (Z.AI)")
    assert zlaude.default is False
    assert zlaude.config_dir == str(fake_home / ".zlaude")


def test_discovers_default_codex_profile(fake_home):
    discovered = profiles.discover_profiles({"HOME": str(fake_home)})
    codex = [p for p in discovered if p.engine == "codex"]
    assert len(codex) == 1
    assert codex[0].name == "Codex"
    assert codex[0].default is True
    assert codex[0].config_dir is None


def test_stray_dir_without_marker_is_not_a_profile(fake_home):
    discovered = profiles.discover_profiles({"HOME": str(fake_home)})
    assert not any(p.name == "Notclaude" for p in discovered)
    assert all(".notclaude" not in (p.config_dir or "") for p in discovered)


def test_missing_default_dir_yields_no_default_profile(tmp_path):
    home = tmp_path / "empty-home"
    home.mkdir()
    discovered = profiles.discover_profiles({"HOME": str(home)})
    assert discovered == []


def test_humanises_unknown_sibling_stem(tmp_path):
    home = tmp_path / "home"
    home.mkdir()
    _make_claude_dir(home / ".claude")
    _make_claude_dir(home / ".work-claude")
    discovered = profiles.discover_profiles({"HOME": str(home)})
    names = {p.name for p in discovered if p.engine == "claude"}
    assert "Work Claude" in names


# ---------------------------------------------------------------------------
# Explicit config-file merge
# ---------------------------------------------------------------------------


def _write_config(home, body):
    cfg = home / ".config" / "pocketshell"
    cfg.mkdir(parents=True, exist_ok=True)
    (cfg / "profiles.yaml").write_text(body, encoding="utf-8")


def test_config_file_profile_is_loaded(fake_home):
    _write_config(
        fake_home,
        "profiles:\n"
        "  - name: My Claude\n"
        "    engine: claude\n"
        f"    config_dir: {fake_home}/.zlaude\n",
    )
    loaded = profiles.load_config_profiles({"HOME": str(fake_home)})
    assert any(p.name == "My Claude" for p in loaded)


def test_explicit_config_wins_on_name_collision(fake_home):
    # Name a profile "Claude" but point it at ~/.zlaude — the explicit one
    # must win over the discovered default "Claude".
    _write_config(
        fake_home,
        "profiles:\n"
        "  - name: Claude\n"
        "    engine: claude\n"
        f"    config_dir: {fake_home}/.zlaude\n",
    )
    merged = profiles.load_profiles({"HOME": str(fake_home)}, engine="claude")
    claude_named = [p for p in merged if p.name == "Claude"]
    assert len(claude_named) == 1
    assert claude_named[0].config_dir == str(fake_home / ".zlaude")


def test_config_dir_claim_suppresses_duplicate_discovery(fake_home):
    # Explicitly naming ~/.zlaude something else means discovery must not
    # also add its own "Claude (Z.AI)" for the same dir.
    _write_config(
        fake_home,
        "profiles:\n"
        "  - name: ZAI\n"
        "    engine: claude\n"
        f"    config_dir: {fake_home}/.zlaude\n",
    )
    merged = profiles.load_profiles({"HOME": str(fake_home)}, engine="claude")
    dirs = [p.config_dir for p in merged]
    assert dirs.count(str(fake_home / ".zlaude")) == 1
    assert any(p.name == "ZAI" for p in merged)
    assert not any(p.name == "Claude (Z.AI)" for p in merged)


def test_config_file_tilde_expansion(fake_home):
    _write_config(
        fake_home,
        "profiles:\n"
        "  - name: Tilde\n"
        "    engine: claude\n"
        "    config_dir: ~/.zlaude\n",
    )
    loaded = profiles.load_config_profiles({"HOME": str(fake_home)})
    tilde = next(p for p in loaded if p.name == "Tilde")
    assert tilde.config_dir == str(fake_home / ".zlaude")


def test_malformed_config_entries_are_skipped(fake_home):
    _write_config(
        fake_home,
        "profiles:\n"
        "  - name: NoEngine\n"
        "  - engine: claude\n"  # no name
        "  - name: BadEngine\n"
        "    engine: opencode\n"  # unsupported engine
        "  - name: Good\n"
        "    engine: claude\n",
    )
    loaded = profiles.load_config_profiles({"HOME": str(fake_home)})
    names = {p.name for p in loaded}
    assert names == {"Good"}


def test_no_config_file_returns_empty(fake_home):
    assert profiles.load_config_profiles({"HOME": str(fake_home)}) == []


# ---------------------------------------------------------------------------
# load_profiles ordering + engine filter
# ---------------------------------------------------------------------------


def test_default_profile_sorts_first_within_engine(fake_home):
    merged = profiles.load_profiles({"HOME": str(fake_home)}, engine="claude")
    assert merged[0].name == "Claude"
    assert merged[0].default is True


def test_engine_filter(fake_home):
    codex = profiles.load_profiles({"HOME": str(fake_home)}, engine="codex")
    assert all(p.engine == "codex" for p in codex)
    assert {p.name for p in codex} == {"Codex"}


# ---------------------------------------------------------------------------
# resolve_profile
# ---------------------------------------------------------------------------


def test_resolve_named_profile(fake_home, monkeypatch):
    monkeypatch.setenv("HOME", str(fake_home))
    monkeypatch.delenv("XDG_CONFIG_HOME", raising=False)
    resolved = profiles.resolve_profile("Claude (Z.AI)", "claude")
    assert resolved.config_dir == str(fake_home / ".zlaude")


def test_resolve_default_profile_yields_none_config_dir(fake_home, monkeypatch):
    monkeypatch.setenv("HOME", str(fake_home))
    resolved = profiles.resolve_profile("Claude", "claude")
    assert resolved.config_dir is None


def test_resolve_unknown_profile_raises(fake_home, monkeypatch):
    monkeypatch.setenv("HOME", str(fake_home))
    with pytest.raises(KeyError):
        profiles.resolve_profile("Nope", "claude")


def test_resolve_is_case_insensitive_fallback(fake_home, monkeypatch):
    monkeypatch.setenv("HOME", str(fake_home))
    resolved = profiles.resolve_profile("claude (z.ai)", "claude")
    assert resolved.config_dir == str(fake_home / ".zlaude")


# ---------------------------------------------------------------------------
# CLI: profiles list
# ---------------------------------------------------------------------------


def test_cli_profiles_list_yaml(fake_home, monkeypatch, capsys):
    monkeypatch.setenv("HOME", str(fake_home))
    monkeypatch.delenv("XDG_CONFIG_HOME", raising=False)
    rc = main(["profiles", "list"])
    assert rc == 0
    out = capsys.readouterr().out
    parsed = yaml.safe_load(out)
    assert "profiles" in parsed
    names = {p["name"] for p in parsed["profiles"]}
    assert {"Claude", "Claude (Z.AI)", "Codex"} <= names
    # Shape check: every entry has exactly the four secret-free keys.
    for entry in parsed["profiles"]:
        assert set(entry.keys()) == {"name", "engine", "config_dir", "default"}


def test_cli_profiles_list_json(fake_home, monkeypatch, capsys):
    monkeypatch.setenv("HOME", str(fake_home))
    monkeypatch.delenv("XDG_CONFIG_HOME", raising=False)
    rc = main(["profiles", "list", "--json"])
    assert rc == 0
    out = capsys.readouterr().out
    parsed = json.loads(out)
    assert "profiles" in parsed
    names = {p["name"] for p in parsed["profiles"]}
    assert {"Claude", "Claude (Z.AI)", "Codex"} <= names


def test_cli_profiles_list_engine_filter(fake_home, monkeypatch, capsys):
    monkeypatch.setenv("HOME", str(fake_home))
    monkeypatch.delenv("XDG_CONFIG_HOME", raising=False)
    rc = main(["profiles", "list", "--engine", "claude"])
    assert rc == 0
    out = capsys.readouterr().out
    parsed = yaml.safe_load(out)
    engines = {p["engine"] for p in parsed["profiles"]}
    assert engines == {"claude"}


def test_cli_profiles_list_emits_no_secrets(fake_home, monkeypatch, capsys):
    monkeypatch.setenv("HOME", str(fake_home))
    monkeypatch.delenv("XDG_CONFIG_HOME", raising=False)
    main(["profiles", "list"])
    out = capsys.readouterr().out
    assert "SECRET" not in out
    assert "apiKey" not in out
    assert "OPENAI_API_KEY" not in out


# ---------------------------------------------------------------------------
# Aplexer Phase A1: shadow-mode `a profiles --json` (#2341)
# ---------------------------------------------------------------------------


def _zlaude_payload(fake_home):
    return {
        "zlaude": {
            "engine": "claude",
            "env": {
                "CLAUDE_CONFIG_DIR": str(fake_home / ".zlaude"),
                "ANTHROPIC_API_KEY": "sk-SECRET",
            },
        }
    }


def test_aplexer_mapping_uses_display_name_and_strips_env(fake_home):
    mapped = profiles._profiles_from_aplexer_json(_zlaude_payload(fake_home))
    assert mapped is not None
    assert len(mapped) == 1
    profile = mapped[0]
    assert profile.name == "Claude (Z.AI)"
    assert profile.engine == "claude"
    assert profile.config_dir == str(fake_home / ".zlaude")
    assert profile.default is False
    assert profile.env == {}


def test_aplexer_mapping_skips_non_profile_engines():
    mapped = profiles._profiles_from_aplexer_json(
        {
            "shell": {"engine": "shell", "env": {}},
            "gemini": {"engine": "gemini", "env": {}},
        }
    )
    assert mapped == []


def test_aplexer_mapping_rejects_non_object():
    assert profiles._profiles_from_aplexer_json(["not", "an", "object"]) is None


def test_kill_switch_skips_probe(fake_home, monkeypatch):
    _install_fake_client(monkeypatch, payload=_zlaude_payload(fake_home))
    monkeypatch.setenv("POCKETSHELL_APLEXER_PROFILES", "0")
    assert profiles._aplexer_profiles({"HOME": str(fake_home)}) is None


def test_shadow_mode_still_returns_native(
    fake_home, monkeypatch, caplog
):
    fake = _install_fake_client(monkeypatch, payload=_zlaude_payload(fake_home))
    caplog.set_level(logging.DEBUG, logger="pocketshell.profiles")
    monkeypatch.setenv("POCKETSHELL_APLEXER_PROFILES", "0")
    expected = profiles.discover_profiles({"HOME": str(fake_home)})
    monkeypatch.setenv("POCKETSHELL_APLEXER_PROFILES", "1")
    caplog.clear()
    discovered = profiles.discover_profiles({"HOME": str(fake_home)})
    assert fake.calls == ["profiles"]
    assert discovered == expected
    assert not [
        record
        for record in caplog.records
        if record.name == "pocketshell.profiles"
    ]


def test_shadow_mode_logs_missing_sibling_and_returns_native(
    fake_home, monkeypatch, caplog
):
    # A valid empty aplexer listing must not remove native siblings.
    _install_fake_client(monkeypatch, payload={})
    caplog.set_level(logging.WARNING, logger="pocketshell.profiles")
    monkeypatch.setenv("POCKETSHELL_APLEXER_PROFILES", "0")
    expected = profiles.discover_profiles({"HOME": str(fake_home)})
    monkeypatch.setenv("POCKETSHELL_APLEXER_PROFILES", "1")
    discovered = profiles.discover_profiles({"HOME": str(fake_home)})
    assert discovered == expected
    assert "aplexer profile shadow divergence" in caplog.text
    assert "Claude (Z.AI)" in caplog.text


def test_shadow_mode_logs_missing_extra_and_differing_siblings(
    fake_home, monkeypatch, caplog
):
    _make_claude_dir(fake_home / ".alt-claude")
    _install_fake_client(
        monkeypatch,
        payload={
            "renamed": {
                "engine": "claude",
                "env": {"CLAUDE_CONFIG_DIR": str(fake_home / ".zlaude")},
            },
            "remote-laude": {
                "engine": "claude",
                "env": {"CLAUDE_CONFIG_DIR": "/srv/remote-laude"},
            },
        },
    )
    caplog.set_level(logging.WARNING, logger="pocketshell.profiles")
    discovered = profiles.discover_profiles({"HOME": str(fake_home)})
    native = [
        profiles.Profile("Claude", "claude", None, True),
        profiles.Profile("Alt Claude", "claude", str(fake_home / ".alt-claude")),
        profiles.Profile("Claude (Z.AI)", "claude", str(fake_home / ".zlaude")),
        profiles.Profile("Codex", "codex", None, True),
    ]
    assert discovered == native
    assert "missing=['Alt Claude']" in caplog.text
    assert "extra=['Remote Laude']" in caplog.text
    assert "differing=['Claude (Z.AI)->Renamed']" in caplog.text


def test_probe_error_falls_back_to_native(fake_home, monkeypatch):
    _install_fake_client(monkeypatch, error=TimeoutError("timed out"))
    discovered = profiles.discover_profiles({"HOME": str(fake_home)})
    assert {p.name for p in discovered} == {"Claude", "Claude (Z.AI)", "Codex"}


def test_aplexer_profile_probe_uses_python_client(fake_home, monkeypatch):
    _install_fake_client(
        monkeypatch,
        payload={
            "remote-laude": {
                "engine": "claude",
                "env": {"CLAUDE_CONFIG_DIR": "/srv/remote-laude"},
            }
        },
    )
    mapped = profiles._aplexer_profiles({"HOME": str(fake_home)})
    assert mapped is not None
    assert [(item.name, item.config_dir) for item in mapped] == [
        ("Remote Laude", "/srv/remote-laude")
    ]


def test_probe_bad_payload_falls_back_to_native(fake_home, monkeypatch):
    _install_fake_client(monkeypatch, payload=["not", "an", "object"])
    discovered = profiles.discover_profiles({"HOME": str(fake_home)})
    assert {p.name for p in discovered} == {"Claude", "Claude (Z.AI)", "Codex"}


def test_probe_client_failure_falls_back_to_native(fake_home, monkeypatch):
    _install_fake_client(monkeypatch, error=RuntimeError("fail"))
    discovered = profiles.discover_profiles({"HOME": str(fake_home)})
    assert {p.name for p in discovered} == {"Claude", "Claude (Z.AI)", "Codex"}


def test_cli_shape_unchanged_with_shadow_probe(
    fake_home, monkeypatch, capsys
):
    _install_fake_client(monkeypatch, payload=_zlaude_payload(fake_home))
    monkeypatch.setenv("HOME", str(fake_home))
    monkeypatch.delenv("XDG_CONFIG_HOME", raising=False)
    rc = main(["profiles", "list", "--json"])
    assert rc == 0
    parsed = json.loads(capsys.readouterr().out)
    for entry in parsed["profiles"]:
        assert set(entry.keys()) == {"name", "engine", "config_dir", "default"}
        assert "env" not in entry
        assert "SECRET" not in json.dumps(entry)
    names = {p["name"] for p in parsed["profiles"]}
    assert {"Claude", "Claude (Z.AI)", "Codex"} <= names
