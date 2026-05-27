"""`pocketshell usage` subcommand.

First-PR implementation: delegate to the existing `quse` CLI via
`subprocess.run`. The arguments and stdout are proxied through verbatim
so the JSON payload (and human-readable lines) are byte-identical to
`quse [provider] [--json]`. The existing Kotlin `QuseUsageJsonParser`
keeps working when the Android app eventually switches its probe over.

Why subprocess instead of `import quse`:

- `quse` is currently not published to PyPI; declaring it as a normal
  `pyproject.toml` dependency would break `uv tool install
  pocketshell-cli` for any user (including the maintainer's dev box).
- Subprocess delegation keeps `pocketshell-cli` decoupled from `quse`'s
  internal module layout, so updates to `quse` don't break the wrapper.
- The PATH-discovery story for `quse` is already solved on the app side
  (see issue #41 + the `pathOverride` column on `HostEntity`). Wrapping
  `quse` here means the app's existing PATH override mechanism keeps
  working without re-implementation.

Later PRs will fold the provider-detection logic in directly so
`pocketshell-cli` is the canonical implementation and the subprocess
hop disappears, but that is explicit non-scope here per the brief on
issue [#170](https://github.com/alexeygrigorev/pocketshell/issues/170).
"""

from __future__ import annotations

import shutil
import subprocess
import sys
from typing import Optional, Sequence

import click


def _resolve_quse_binary() -> Optional[str]:
    """Locate the `quse` CLI on PATH, or return ``None`` if absent.

    Pulled out as a function so the unit suite can monkeypatch it.
    `shutil.which` returns the same path the user would see from
    `command -v quse`, which is the probe the Android app already runs.
    """
    return shutil.which("quse")


def _run_quse(args: Sequence[str]) -> int:
    """Invoke `quse` with [args]; proxy stdout/stderr and exit code.

    Using `subprocess.run(..., check=False)` and forwarding the captured
    output rather than `os.execvp` keeps the call testable (the test
    suite can monkeypatch `subprocess.run`) and lets us decorate the
    failure mode with a friendly hint when `quse` is missing.
    """
    quse_path = _resolve_quse_binary()
    if quse_path is None:
        # Same wording the bootstrap sheet uses so the user sees a
        # consistent message whether they hit the bin via `pocketshell
        # usage` or the app's poll loop.
        click.echo(
            "pocketshell: `quse` is not installed on this host. "
            "Install it via `uv tool install quse` or `pipx install quse` "
            "and re-run.",
            err=True,
        )
        return 127

    completed = subprocess.run(
        [quse_path, *args],
        check=False,
        capture_output=True,
        text=True,
    )
    # Echo verbatim so the JSON output is byte-identical to `quse --json`.
    if completed.stdout:
        sys.stdout.write(completed.stdout)
    if completed.stderr:
        sys.stderr.write(completed.stderr)
    return completed.returncode


@click.command(
    context_settings={"help_option_names": ["-h", "--help"], "ignore_unknown_options": True},
)
@click.argument("provider", required=False)
@click.option(
    "--json",
    "json_output",
    is_flag=True,
    help="Emit machine-readable JSON output identical to `quse --json`.",
)
@click.pass_context
def usage_command(
    ctx: click.Context,
    provider: Optional[str] = None,
    json_output: bool = False,
) -> None:
    """Report quota / usage for coding-agent providers on this host.

    Delegates to the `quse` CLI via subprocess. Output shape (both human
    and JSON) is byte-identical to `quse [provider] [--json]` so any
    consumer that already parses `quse` output keeps working when the
    PocketShell app routes through `pocketshell usage` instead.
    """
    args: list[str] = []
    if provider:
        args.append(provider)
    if json_output:
        args.append("--json")
    exit_code = _run_quse(args)
    # Click ignores the return value of a callback by default; we need to
    # explicitly propagate non-zero exit codes through `ctx.exit` so the
    # outer `main()` (and the OS) sees the same exit code `quse` reported.
    if exit_code != 0:
        ctx.exit(exit_code)
