"""Unified server-side Python utility for the PocketShell Android client.

This package is intended to replace the separately-installed `quse` and
`tmuxctl` utilities the PocketShell app currently probes for. The first PR
ships the skeleton plus the `pocketshell usage` subcommand only; later PRs
will add `jobs`, `agent-log`, `sessions`, `repos`, and daemon mode.

See https://github.com/alexeygrigorev/pocketshell/issues/170.
"""

from __future__ import annotations

import tomllib
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path

__all__ = ["__version__"]


def _metadata_version() -> str:
    try:
        return version("pocketshell")
    except PackageNotFoundError:
        pyproject = Path(__file__).resolve().parents[2] / "pyproject.toml"
        with pyproject.open("rb") as handle:
            return tomllib.load(handle)["project"]["version"]


__version__ = _metadata_version()
