"""`pocketshell usage` subcommand.

First-PR implementation: delegate to the existing `quse` CLI via
`subprocess.run`. The arguments and stdout are proxied through verbatim
so the JSON payload (and human-readable lines) are byte-identical to
`quse [provider] [--json]`. The existing Kotlin `QuseUsageJsonParser`
keeps working when the Android app eventually switches its probe over.

Daemon mode (issue #219, first PR scope)
----------------------------------------

When the IPC daemon is running, ``pocketshell usage --json`` sends a
``usage.fetch`` JSON-RPC request to ``$XDG_RUNTIME_DIR/pocketshell/daemon.sock``
instead of forking ``quse`` itself. The daemon caches the result for
30 s, so a polled usage row in the Android app returns the second-and-
later calls in microseconds.

The fall-through is intentional and matches the spike's Q6 parity rule:

- ``--no-daemon`` forces the one-shot subprocess path even when a
  daemon is up (debugging / belt-and-braces).
- ``--no-cache`` is honoured by the daemon (cache miss + populate)
  but is intentionally a no-op on the subprocess path: there is no
  cache to bypass when every call is a fresh subprocess. This keeps
  the CLI surface uniform without paying for client-side caching that
  duplicates the daemon's logic.
- The probe is best-effort: any error talking to the daemon
  (``ECONNREFUSED``, stale socket, version-skew RPC error, slow
  reply) falls through silently to the subprocess path. The daemon
  never blocks correctness.

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
from typing import Any, Optional, Sequence

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


def _try_daemon_usage_fetch(
    provider: Optional[str],
    *,
    no_cache: bool,
) -> Optional[dict[str, Any]]:
    """Probe the daemon and dispatch ``usage.fetch``; return None on miss.

    Returns the JSON-RPC ``result`` envelope (a dict with
    ``stdout``/``stderr``/``returncode`` keys) on success, or ``None``
    when the daemon is unreachable / errors out and the caller should
    fall through to the one-shot subprocess path.

    Lazy import keeps the cold-cache CLI start fast for users who
    never hit the daemon path.
    """
    from pocketshell import daemon as _daemon

    socket_path = _daemon.resolve_socket_path()
    if not socket_path.exists():
        return None

    params: dict[str, Any] = {}
    if provider is not None:
        params["provider"] = provider
    if no_cache:
        params["no_cache"] = True

    try:
        result = _daemon.call(
            "usage.fetch",
            params=params,
            socket_path=socket_path,
            timeout=5.0,
        )
    except (_daemon.DaemonClientError, RuntimeError, OSError):
        # Daemon unreachable, returned an error, or socket dance went
        # wrong. The contract is "daemon is an optimisation, never a
        # dependency" — fall through silently.
        return None
    if not isinstance(result, dict):
        return None
    return result


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
@click.option(
    "--no-daemon",
    "no_daemon",
    is_flag=True,
    help=(
        "Skip the IPC daemon and run `quse` as a one-shot subprocess "
        "even if a daemon is available. Useful for debugging."
    ),
)
@click.option(
    "--no-cache",
    "no_cache",
    is_flag=True,
    help=(
        "Bypass the daemon's per-method cache (30 s for `usage.fetch`). "
        "No effect on the one-shot subprocess path, which always runs fresh."
    ),
)
@click.pass_context
def usage_command(
    ctx: click.Context,
    provider: Optional[str] = None,
    json_output: bool = False,
    no_daemon: bool = False,
    no_cache: bool = False,
) -> None:
    """Report quota / usage for coding-agent providers on this host.

    By default the command probes the IPC daemon's Unix socket
    (``$XDG_RUNTIME_DIR/pocketshell/daemon.sock``) and dispatches a
    ``usage.fetch`` JSON-RPC call when the daemon is live. Otherwise
    it falls through to ``quse [provider] [--json]`` as a one-shot
    subprocess. Output shape (both human and JSON) is byte-identical
    to `quse [provider] [--json]` so any consumer that already parses
    `quse` output keeps working.
    """
    # JSON output is the format the daemon caches against (NDJSON
    # straight from `quse --json`). Human-readable output is rare and
    # not on the Android hot path, so it does not get the daemon
    # speed-up — falling through to subprocess is simpler than
    # teaching the daemon to render two formats.
    if json_output and not no_daemon:
        envelope = _try_daemon_usage_fetch(provider, no_cache=no_cache)
        if envelope is not None:
            if envelope.get("stdout"):
                sys.stdout.write(envelope["stdout"])
            if envelope.get("stderr"):
                sys.stderr.write(envelope["stderr"])
            exit_code = int(envelope.get("returncode", 0))
            if exit_code != 0:
                ctx.exit(exit_code)
            return

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
