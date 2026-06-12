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

import pytest
import yaml

from pocketshell import profiles
from pocketshell.cli import main


# ---------------------------------------------------------------------------
# Fixtures: a fake HOME with the maintainer-style config dirs.
# ---------------------------------------------------------------------------


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
