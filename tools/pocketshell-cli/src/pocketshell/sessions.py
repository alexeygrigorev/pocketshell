"""`pocketshell sessions` subcommand group.

Third-PR port of `tmuxctl list` into the unified `pocketshell` CLI.
Mirrors the design of `pocketshell.jobs` and `pocketshell.usage`: a thin
subprocess wrapper around the existing `tmuxctl` binary so the output
shape (the fixed-width table parsed by the Android-side
`HostTmuxSessionListParser`) stays byte-identical. Per D22 (no
backwards-compatibility shims, hard-cut only) the new utility is the
canonical command; the Android side will swap its probe from
`tmuxctl list` to `pocketshell sessions list` in a follow-up PR (#231)
once full parity is reached.

Why subprocess instead of `import tmuxctl`:

- `tmuxctl` is the maintainer's standalone library/CLI and is not
  published to PyPI. Declaring it as a normal `pyproject.toml`
  dependency would break `uv tool install pocketshell-cli` and
  `pipx install pocketshell-cli` for any user.
- Subprocess delegation keeps `pocketshell-cli` decoupled from
  `tmuxctl`'s internal module layout, so updates to `tmuxctl` do not
  break the wrapper.
- The PATH-discovery story for `tmuxctl` is already solved on the app
  side (the same `pathOverride` hatch as `quse`). Wrapping `tmuxctl`
  here means the app's PATH override mechanism keeps working without
  re-implementation.

Subcommand coverage:

- `pocketshell sessions list [--json]` -> `tmuxctl list`

`tmuxctl list` does not currently support a `--json` flag; the brief
calls it out as a future flag the underlying CLI may grow. The wrapper
uses `ignore_unknown_options=True` so `--json` (and any other future
flag) is forwarded verbatim without the wrapper needing a release of
its own. If `tmuxctl list` does not understand `--json` yet, the
subprocess exit code and stderr are proxied through unchanged.
"""

from __future__ import annotations

import shutil
import subprocess
import sys
from typing import Optional, Sequence

import click


def _resolve_tmuxctl_binary() -> Optional[str]:
    """Locate the `tmuxctl` CLI on PATH, or return ``None`` if absent.

    Pulled out as a function so the unit suite can monkeypatch it.
    `shutil.which` returns the same path the user would see from
    `command -v tmuxctl`, which is the probe the Android app already
    runs.
    """
    return shutil.which("tmuxctl")


def _tmuxctl_missing_message() -> str:
    """Friendly install hint shown when `tmuxctl` is not on PATH.

    The wording mirrors the `quse` missing-binary message in
    `pocketshell.usage` and the `tmuxctl` missing-binary message in
    `pocketshell.jobs` so the user sees consistent text whichever
    subcommand surfaces the failure first.
    """
    return (
        "pocketshell: `tmuxctl` is not installed on this host. "
        "Install it via `uv tool install tmuxctl` or `pipx install tmuxctl` "
        "and re-run."
    )


def _run_tmuxctl(args: Sequence[str]) -> int:
    """Invoke `tmuxctl` with [args]; proxy stdout/stderr and exit code.

    Identical contract to `pocketshell.jobs._run_tmuxctl` /
    `pocketshell.usage._run_quse` so a consumer that already parses
    `tmuxctl` output sees a byte-identical payload when routed through
    `pocketshell sessions ...`.
    """
    tmuxctl_path = _resolve_tmuxctl_binary()
    if tmuxctl_path is None:
        click.echo(_tmuxctl_missing_message(), err=True)
        return 127

    completed = subprocess.run(
        [tmuxctl_path, *args],
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.stdout:
        sys.stdout.write(completed.stdout)
    if completed.stderr:
        sys.stderr.write(completed.stderr)
    return completed.returncode


@click.group(
    name="sessions",
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Enumerate tmux sessions on the host.\n\n"
        "Thin wrapper around the existing `tmuxctl list` CLI: subcommands "
        "delegate to `tmuxctl` via subprocess and proxy stdout/stderr and "
        "exit codes verbatim. The output shape stays byte-identical to "
        "`tmuxctl list` so the Android-side `HostTmuxSessionListParser` "
        "keeps working when the app swaps its probe to `pocketshell "
        "sessions list` (issue #231)."
    ),
)
def sessions_group() -> None:
    """Top-level group registered onto the root `pocketshell` CLI."""


@sessions_group.command(
    "list",
    # `ignore_unknown_options` + `allow_extra_args` let callers pass
    # flags `tmuxctl` understands that this wrapper does not list
    # individually. The brief calls out `--json` as a future flag the
    # underlying `tmuxctl` may grow; forwarding extras unchanged keeps
    # the wrapper from blocking parity work upstream.
    context_settings={
        "help_option_names": ["-h", "--help"],
        "ignore_unknown_options": True,
        "allow_extra_args": True,
    },
)
@click.option(
    "--by",
    "sort_by",
    type=click.Choice(["created", "activity"], case_sensitive=False),
    default=None,
    help="Sort by session creation time or last activity (forwarded to `tmuxctl list --by`).",
)
@click.pass_context
def sessions_list(ctx: click.Context, sort_by: Optional[str]) -> None:
    """List tmux sessions on the host (delegates to `tmuxctl list`).

    Output is byte-identical to `tmuxctl list` so the Android-side
    `HostTmuxSessionListParser` (anchored on the trailing
    `YYYY-MM-DD HH:MM:SS` timestamp; see issue #200) keeps working.
    """
    args: list[str] = ["list"]
    if sort_by:
        args.extend(["--by", sort_by])
    # `ctx.args` holds any extras we ignored (e.g. `--json` once
    # tmuxctl supports it). Forward verbatim, position preserved.
    args.extend(ctx.args)
    exit_code = _run_tmuxctl(args)
    if exit_code != 0:
        ctx.exit(exit_code)
