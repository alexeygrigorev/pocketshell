"""Top-level Click dispatcher for the unified `pocketshell` CLI.

Skeleton landed in the first PR of issue
[#170](https://github.com/alexeygrigorev/pocketshell/issues/170). Follow-up
PRs add subgroups: `jobs` (#170 second PR), `sessions` (#218),
`agent-log` (#217), `daemon` (#219), and `repos` (#220).

Per the D22 locked principle (no backwards compatibility, hard cuts only)
the PocketShell Android app probes for this single binary instead of
`quse` / `tmuxctl`: usage runs `pocketshell usage --json` (#231) and jobs
run `pocketshell jobs ...` (a direct namespace swap from `tmuxctl jobs`).
The cutover is complete; the app no longer probes the old binaries.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import Optional, Sequence

import click

from pocketshell import __version__
from pocketshell.agent_log import agent_log_command
from pocketshell.agents import agent_group
from pocketshell.agents_kind import agents_group
from pocketshell.env import env_group
from pocketshell.github import github_group
from pocketshell.hooks import hooks_group
from pocketshell.jobs import jobs_group
from pocketshell.logs import logs_group
from pocketshell.profiles import profiles_group
from pocketshell.prune_attachments import prune_attachments_command
from pocketshell.push import push_group
from pocketshell.qr_share import qr_share_command
from pocketshell.repos import repos_group
from pocketshell.sessions import sessions_group
from pocketshell.tree import tree_group
from pocketshell.usage import usage_command


@click.group(
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Unified server-side helper for the PocketShell Android client.\n\n"
        "Subcommands replace the separately-installed `quse`, `tmuxctl`, "
        "and `qr-share` CLIs. Today `usage`, `jobs`, `sessions`, "
        "`agent-log`, `repos`, `github`, `daemon`, and `qr-share` are wired "
        "up; more subcommands will land in follow-up rounds."
    ),
)
@click.version_option(__version__, "-V", "--version", prog_name="pocketshell")
def cli() -> None:
    """Top-level group. Each subcommand is registered below."""


cli.add_command(usage_command, name="usage")
cli.add_command(agent_group, name="agent")
cli.add_command(agents_group, name="agents")
cli.add_command(profiles_group, name="profiles")
cli.add_command(jobs_group, name="jobs")
cli.add_command(sessions_group, name="sessions")
cli.add_command(tree_group, name="tree")
cli.add_command(agent_log_command, name="agent-log")
cli.add_command(repos_group, name="repos")
cli.add_command(github_group, name="github")
cli.add_command(env_group, name="env")
cli.add_command(hooks_group, name="hooks")
cli.add_command(logs_group, name="logs")
cli.add_command(prune_attachments_command, name="prune-attachments")
cli.add_command(push_group, name="push")
cli.add_command(qr_share_command, name="qr-share")


# ---------------------------------------------------------------------------
# `pocketshell daemon` subgroup — IPC daemon lifecycle
# ---------------------------------------------------------------------------


@cli.group(
    name="daemon",
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Manage the PocketShell IPC daemon (Unix-socket JSON-RPC server).\n\n"
        "The daemon is a performance optimisation: subcommands probe for it "
        "and fall through to one-shot subprocess calls when it is absent. "
        "Use `start` to launch it explicitly, `stop` to shut it down, and "
        "`status` to inspect the live socket. See issue #219."
    ),
)
def daemon_group() -> None:
    """Daemon lifecycle subgroup."""


@daemon_group.command("start")
@click.option(
    "--foreground",
    "-f",
    is_flag=True,
    help="Run the daemon in the foreground (do not detach).",
)
@click.option(
    "--idle-timeout",
    type=float,
    default=None,
    help=(
        "Override the 120 s idle-shutdown window. Pass 0 to disable "
        "auto-shutdown (used by future systemd Type=simple mode)."
    ),
)
@click.pass_context
def daemon_start(
    ctx: click.Context,
    foreground: bool,
    idle_timeout: Optional[float],
) -> None:
    """Start the IPC daemon.

    Default behaviour spawns a detached child via the gpg-agent
    pattern (one fork + ``setsid``) and waits for the socket to come
    up before returning. ``--foreground`` skips the fork — useful for
    debugging or for a future systemd ``Type=simple`` unit.
    """
    # Lazy import to keep the CLI import cost low for callers that
    # never touch the daemon path.
    from pocketshell import daemon as _daemon

    socket_path = _daemon.resolve_socket_path()
    if _daemon.is_daemon_running(socket_path):
        click.echo(f"already running (socket: {socket_path})")
        return

    if foreground:
        exit_code = _daemon.serve_foreground(
            socket_path=socket_path,
            idle_timeout=idle_timeout,
        )
        if exit_code != 0:
            ctx.exit(exit_code)
        return

    pid = _daemon.spawn_detached(
        socket_path=socket_path,
        idle_timeout=idle_timeout,
    )
    if not _daemon.wait_until_ready(socket_path=socket_path):
        click.echo(
            f"daemon spawn ({pid}) did not become ready within 5 s",
            err=True,
        )
        ctx.exit(1)
    click.echo(f"started (pid: {pid}, socket: {socket_path})")


@daemon_group.command("stop")
@click.pass_context
def daemon_stop(ctx: click.Context) -> None:
    """Stop the IPC daemon and clean up its socket.

    Sends SIGTERM via the PID file when available, falls back to the
    ``daemon.shutdown`` RPC method otherwise. Idempotent: exits 0 even
    when no daemon was running, matching ``systemctl stop`` semantics.
    """
    from pocketshell import daemon as _daemon

    was_running = _daemon.stop_daemon()
    if was_running:
        click.echo("stopped")
    else:
        click.echo("not running")


@daemon_group.command("status")
@click.pass_context
def daemon_status(ctx: click.Context) -> None:
    """Report daemon liveness and socket path.

    Exit code semantics mirror ``systemctl is-active``:

    - 0 -> daemon is alive and answering ``daemon.ping``
    - 3 -> daemon is NOT running (no socket or stale socket)
    """
    from pocketshell import daemon as _daemon

    socket_path = _daemon.resolve_socket_path()
    pid = _daemon.read_pid()
    if _daemon.is_daemon_running(socket_path):
        if pid is not None:
            click.echo(f"running (pid: {pid}, socket: {socket_path})")
        else:
            click.echo(f"running (socket: {socket_path})")
        return
    click.echo(f"not running (socket: {socket_path})")
    ctx.exit(3)


@daemon_group.command(
    "_serve",
    hidden=True,
    help="Internal foreground entrypoint used by `daemon start` lazy-spawn.",
)
def daemon_serve_internal() -> None:
    """Foreground serve loop invoked by :func:`pocketshell.daemon.spawn_detached`.

    Hidden because it is not part of the user-facing surface; the
    public way to launch the daemon is ``pocketshell daemon start``.
    The internal entrypoint exists so the lazy-spawn ``Popen`` has a
    stable argv that does NOT re-trigger the detach logic (which
    would fork again).
    """
    from pocketshell import daemon as _daemon

    socket_env = os.environ.get("POCKETSHELL_DAEMON_SOCKET")
    socket_path = _daemon.resolve_socket_path() if socket_env is None else Path(socket_env)
    _daemon.serve_foreground(socket_path=socket_path)


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
