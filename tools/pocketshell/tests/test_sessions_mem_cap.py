"""Project memory-cap tests for the aplexer create path."""

from __future__ import annotations

from pathlib import Path

import pytest

from pocketshell import memcap


def test_explicit_cap_wins() -> None:
    assert memcap.resolve_session_mem_bytes(flag="2G", workspace="/missing") == 2 * 1024**3


def test_cgroups_file_is_the_project_source(tmp_path: Path) -> None:
    (tmp_path / "cgroups.toml").write_text('mem = "3G"\n', encoding="utf-8")
    assert memcap.resolve_session_mem_bytes(flag=None, workspace=str(tmp_path)) == 3 * 1024**3


def test_pyproject_uses_pocketshell_section(tmp_path: Path) -> None:
    (tmp_path / "pyproject.toml").write_text(
        "[tool.pocketshell]\nmem = '4G'\n", encoding="utf-8"
    )
    assert memcap.resolve_session_mem_bytes(flag=None, workspace=str(tmp_path)) == 4 * 1024**3


def test_uncapped_requires_explicit_flag(tmp_path: Path) -> None:
    (tmp_path / "cgroups.toml").write_text('mem = "none"\n', encoding="utf-8")
    with pytest.raises(memcap.MemCapError, match="explicitly"):
        memcap.resolve_session_mem_bytes(flag=None, workspace=str(tmp_path))
    assert memcap.resolve_session_mem_bytes(flag="none", workspace=str(tmp_path)) is None


def test_malformed_project_policy_fails_loudly(tmp_path: Path) -> None:
    (tmp_path / "cgroups.toml").write_text("mem = [", encoding="utf-8")
    with pytest.raises(memcap.MemCapError):
        memcap.resolve_session_mem_bytes(flag=None, workspace=str(tmp_path))
