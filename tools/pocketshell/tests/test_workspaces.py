"""Tests for the durable Quiet workspace-membership contract."""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from click.testing import CliRunner

from pocketshell import workspaces as workspaces_mod
from pocketshell.cli import cli
from pocketshell.tree import TreePaths


def _paths(tmp_path: Path) -> TreePaths:
    return TreePaths(tree_dir=tmp_path / "state" / "pocketshell" / "tree")


def test_add_is_idempotent_and_canonicalizes_tilde(tmp_path: Path, monkeypatch) -> None:
    home = tmp_path / "home" / "alex"
    home.mkdir(parents=True)
    monkeypatch.setenv("HOME", str(home))
    paths = _paths(tmp_path)

    first = workspaces_mod.add_workspace(
        {"host": "devbox", "path": "~/git/project"}, paths=paths
    )
    second = workspaces_mod.add_workspace(
        {"host": "devbox", "path": str(home / "git" / "project")}, paths=paths
    )

    assert first["created"] is True
    assert second["created"] is False
    assert second["workspaces"] == [
        {"path": str(home / "git" / "project"), "display_path": "~/git/project"}
    ]


def test_empty_workspace_survives_list_and_is_scoped_by_host(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    workspaces_mod.add_workspace(
        {"host": "devbox", "path": "/srv/empty-project"}, paths=paths
    )

    assert workspaces_mod.list_workspaces({"host": "devbox"}, paths=paths)["workspaces"]
    assert workspaces_mod.list_workspaces({"host": "other"}, paths=paths)["workspaces"] == []


def test_remove_is_idempotent(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    params = {"host": "devbox", "path": "/srv/project"}
    workspaces_mod.add_workspace(params, paths=paths)

    removed = workspaces_mod.remove_workspace(params, paths=paths)
    again = workspaces_mod.remove_workspace(params, paths=paths)
    assert removed["removed"] is True
    assert again["removed"] is False
    assert again["workspaces"] == []


@pytest.mark.parametrize("path", ["project", "", None])
def test_relative_or_empty_paths_are_rejected(tmp_path: Path, path) -> None:
    with pytest.raises(ValueError, match="path"):
        workspaces_mod.add_workspace(
            {"host": "devbox", "path": path}, paths=_paths(tmp_path)
        )


def test_cli_emits_schema_one_json(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setenv("XDG_STATE_HOME", str(tmp_path / "state"))
    runner = CliRunner()
    added = runner.invoke(
        cli,
        ["workspaces", "add", "~/empty", "--host", "devbox", "--json"],
        env={"HOME": str(tmp_path / "home")},
    )
    assert added.exit_code == 0, added.output
    payload = json.loads(added.output)
    assert payload["schema"] == 1
    assert payload["created"] is True
    listed = runner.invoke(
        cli,
        ["workspaces", "list", "--host", "devbox", "--json"],
        env={"HOME": str(tmp_path / "home")},
    )
    assert listed.exit_code == 0, listed.output
    assert json.loads(listed.output)["workspaces"][0]["display_path"] == "~/empty"


def test_cli_invalid_path_is_a_structured_error(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setenv("XDG_STATE_HOME", str(tmp_path / "state"))
    result = CliRunner().invoke(
        cli,
        ["workspaces", "add", "relative", "--host", "devbox", "--json"],
    )
    assert result.exit_code == 2
    assert json.loads(result.stderr)["error"]["code"] == "invalid_request"
