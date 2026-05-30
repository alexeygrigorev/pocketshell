from __future__ import annotations

import tomllib
from pathlib import Path

from click.testing import CliRunner

from pocketshell import __version__
from pocketshell.cli import cli


def _pyproject_version() -> str:
    pyproject = Path(__file__).resolve().parents[1] / "pyproject.toml"
    with pyproject.open("rb") as handle:
        return tomllib.load(handle)["project"]["version"]


def test_internal_version_matches_pyproject_metadata() -> None:
    assert __version__ == _pyproject_version()


def test_top_level_version_matches_pyproject_metadata() -> None:
    runner = CliRunner()
    result = runner.invoke(cli, ["--version"])

    assert result.exit_code == 0, result.output
    assert result.output == f"pocketshell, version {_pyproject_version()}\n"
