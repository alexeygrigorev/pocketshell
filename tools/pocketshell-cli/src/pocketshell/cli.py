"""Top-level Click dispatcher for the unified `pocketshell` CLI.

This is the skeleton landed in the first PR of issue
[#170](https://github.com/alexeygrigorev/pocketshell/issues/170). The
second PR adds the `jobs` subgroup. Later PRs will add `agent-log`,
`sessions`, `repos`, and an IPC `daemon`.

Per the D22 locked principle (no backwards compatibility, hard cuts only)
the eventual goal is for the PocketShell Android app to probe for this
single binary instead of `quse` / `tmuxctl`. The first PR keeps the
existing probes in place — parallel detection, not legacy detection — so
the app keeps working while we ramp up `pocketshell`'s feature parity.
"""

from __future__ import annotations

import sys
from typing import Optional, Sequence

import click

from pocketshell import __version__
from pocketshell.jobs import jobs_group
from pocketshell.usage import usage_command


@click.group(
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Unified server-side helper for the PocketShell Android client.\n\n"
        "Subcommands replace the separately-installed `quse` and `tmuxctl` "
        "CLIs. Today `usage` and `jobs` are wired up; more subcommands will "
        "land in follow-up rounds."
    ),
)
@click.version_option(__version__, "-V", "--version", prog_name="pocketshell")
def cli() -> None:
    """Top-level group. Each subcommand is registered below."""


cli.add_command(usage_command, name="usage")
cli.add_command(jobs_group, name="jobs")


def main(argv: Optional[Sequence[str]] = None) -> int:
    """Entrypoint for both the console-script and `python -m pocketshell`.

    Returns an integer exit code rather than letting Click call
    `sys.exit` so the function is testable from the unit suite.
    """
    try:
        result = cli.main(args=list(argv) if argv is not None else None,
                          prog_name="pocketshell",
                          standalone_mode=False)
    except click.exceptions.Exit as exc:
        return int(exc.exit_code)
    except click.ClickException as exc:
        exc.show()
        return int(exc.exit_code)
    if result is None:
        return 0
    try:
        return int(result)
    except (TypeError, ValueError):
        return 0


if __name__ == "__main__":
    sys.exit(main())
