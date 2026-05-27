"""Unified server-side Python utility for the PocketShell Android client.

This package is intended to replace the separately-installed `quse` and
`tmuxctl` utilities the PocketShell app currently probes for. The first PR
ships the skeleton plus the `pocketshell usage` subcommand only; later PRs
will add `jobs`, `agent-log`, `sessions`, `repos`, and daemon mode.

See https://github.com/alexeygrigorev/pocketshell/issues/170.
"""

from __future__ import annotations

__all__ = ["__version__"]
__version__ = "0.1.0"
