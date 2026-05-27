"""`pocketshell jobs` subcommand group.

Second-PR port of `tmuxctl jobs` into the unified `pocketshell` CLI.
Mirrors the design of `pocketshell.usage`: a thin subprocess wrapper
around the existing `tmuxctl` binary so the output shape (the
fixed-width table parsed by the Android-side `TmuxctlJobsParser`) stays
byte-identical. Per D22 (no backwards-compatibility shims, hard-cut
only) the new utility is the canonical command; the Android side will
swap its probe from `tmuxctl jobs ...` to `pocketshell jobs ...` in a
follow-up PR once full parity is reached.

Why subprocess instead of `import tmuxctl`:

- `tmuxctl` is the maintainer's standalone library/CLI and is not
  published to PyPI. Declaring it as a normal `pyproject.toml`
  dependency would break `uv tool install pocketshell-cli` and
  `pipx install pocketshell-cli` for any user.
- Subprocess delegation keeps `pocketshell-cli` decoupled from
  `tmuxctl`'s internal module layout (`tmuxctl.cli` / `tmuxctl.storage`
  / `tmuxctl.scheduler`), so updates to `tmuxctl` do not break the
  wrapper.
- The PATH-discovery story for `tmuxctl` is already solved on the app
  side (the same `pathOverride` hatch as `quse`). Wrapping `tmuxctl`
  here means the app's PATH override mechanism keeps working without
  re-implementation.

Subcommand coverage:

- `pocketshell jobs list [--session <name>]` -> `tmuxctl jobs list`
- `pocketshell jobs show <id>`               -> `tmuxctl jobs show`
- `pocketshell jobs trigger <id>`            -> `tmuxctl jobs trigger`
  (delegates verbatim; if `tmuxctl` does not yet implement a
  per-job trigger subcommand the subprocess exit code + stderr are
  proxied through unchanged. The brief calls for the port today; the
  underlying tmuxctl coverage can grow without changes here.)
- `pocketshell jobs daemon start|stop|status`

The `daemon` group is the only place where we do not literally
forward to `tmuxctl jobs daemon <verb>` because `tmuxctl jobs daemon`
itself is a foreground scheduler loop (no `start`/`stop`/`status`
verbs). Instead:

- `start`  invokes `tmuxctl jobs daemon` in the foreground (passes
           through `--poll-interval` and `--run-once`).
- `status` uses `pgrep -f` to detect a running daemon process.
- `stop`   uses `pkill -TERM -f` to signal the running daemon.

Per the daemon-mode spike (linked from the brief) `tmuxctl jobs
daemon` is a *scheduler loop*, not an IPC daemon. A separate
PocketShell IPC daemon (planned, not implemented today) will
eventually fold the scheduler loop into one process. Until then this
module deliberately keeps the lifecycle helpers simple and does not
refactor the scheduler.
"""

from __future__ import annotations

import shutil
import subprocess
import sys
from typing import Optional, Sequence

import click

# Regex passed to `pgrep -f` / `pkill -f`. We anchor on `tmuxctl` being
# preceded by either start-of-line or a path separator so the pattern
# matches a real `…/tmuxctl jobs daemon …` invocation but does NOT
# match an interactive shell whose argv contains the *substring*
# `tmuxctl jobs daemon` (e.g. an editor or another shell typing the
# command). The trailing `( |$)` keeps us from matching
# `tmuxctl jobs daemon-something-else` if such a verb ever appears.
_DAEMON_PROCESS_PATTERN = r"(^|/)tmuxctl jobs daemon( |$)"
# Plain substring used only for human-facing diagnostics; never passed
# to a process-matching tool.
_DAEMON_HUMAN_LABEL = "tmuxctl jobs daemon"


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
    `pocketshell.usage` so the user sees consistent text whichever
    subcommand surfaces the failure first.
    """
    return (
        "pocketshell: `tmuxctl` is not installed on this host. "
        "Install it via `uv tool install tmuxctl` or `pipx install tmuxctl` "
        "and re-run."
    )


def _run_tmuxctl(args: Sequence[str]) -> int:
    """Invoke `tmuxctl` with [args]; proxy stdout/stderr and exit code.

    Identical contract to `pocketshell.usage._run_quse` so a consumer
    that already parses `tmuxctl` output sees a byte-identical payload
    when routed through `pocketshell jobs ...`.
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
    name="jobs",
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Manage tmuxctl recurring jobs.\n\n"
        "Thin wrapper around the existing `tmuxctl jobs` CLI: subcommands "
        "delegate to `tmuxctl` via subprocess and proxy stdout/stderr and "
        "exit codes verbatim. The output shape stays byte-identical to "
        "`tmuxctl jobs ...` so the Android-side parser keeps working."
    ),
)
def jobs_group() -> None:
    """Top-level group registered onto the root `pocketshell` CLI."""


@jobs_group.command(
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
    "--session",
    "-s",
    "session",
    type=str,
    default=None,
    help="Only list jobs for the given tmux session.",
)
@click.pass_context
def jobs_list(ctx: click.Context, session: Optional[str]) -> None:
    """List scheduled jobs (delegates to `tmuxctl jobs list`)."""
    args: list[str] = ["jobs", "list"]
    if session:
        args.extend(["--session", session])
    # `ctx.args` holds any extras we ignored (e.g. `--json` once
    # tmuxctl supports it). Forward verbatim, position preserved.
    args.extend(ctx.args)
    exit_code = _run_tmuxctl(args)
    if exit_code != 0:
        ctx.exit(exit_code)


@jobs_group.command(
    "show",
    context_settings={
        "help_option_names": ["-h", "--help"],
        "ignore_unknown_options": True,
        "allow_extra_args": True,
    },
)
@click.argument("job_id", type=int)
@click.pass_context
def jobs_show(ctx: click.Context, job_id: int) -> None:
    """Show details for one job (delegates to `tmuxctl jobs show`)."""
    args: list[str] = ["jobs", "show", str(job_id), *ctx.args]
    exit_code = _run_tmuxctl(args)
    if exit_code != 0:
        ctx.exit(exit_code)


@jobs_group.command(
    "trigger",
    context_settings={
        "help_option_names": ["-h", "--help"],
        "ignore_unknown_options": True,
        "allow_extra_args": True,
    },
)
@click.argument("job_id", type=int)
@click.pass_context
def jobs_trigger(ctx: click.Context, job_id: int) -> None:
    """Trigger one job immediately (delegates to `tmuxctl jobs trigger`).

    `tmuxctl` may not implement a per-job `trigger` verb yet; in that
    case the subprocess exit code and stderr (typically a click/typer
    "no such command" error) are proxied through verbatim. The wrapper
    deliberately does not silently fall back to anything else — the
    brief calls for parity, not a reimplementation.
    """
    args: list[str] = ["jobs", "trigger", str(job_id), *ctx.args]
    exit_code = _run_tmuxctl(args)
    if exit_code != 0:
        ctx.exit(exit_code)


# ----- daemon subgroup ------------------------------------------------


def _is_daemon_running() -> bool:
    """Return ``True`` if a `tmuxctl jobs daemon` process is alive.

    Uses `pgrep -f` which matches against the full command line so the
    scheduler loop is detectable even when the binary path is absolute
    (`/home/alexey/.local/bin/tmuxctl jobs daemon`).
    """
    pgrep_path = shutil.which("pgrep")
    if pgrep_path is None:
        return False
    completed = subprocess.run(
        [pgrep_path, "-f", _DAEMON_PROCESS_PATTERN],
        check=False,
        capture_output=True,
        text=True,
    )
    # pgrep exit codes: 0 = match found, 1 = no match, others = error.
    return completed.returncode == 0 and bool(completed.stdout.strip())


@jobs_group.group(
    "daemon",
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Control the tmuxctl recurring-jobs scheduler.\n\n"
        "`start` runs `tmuxctl jobs daemon` in the foreground; `status` "
        "and `stop` query/signal the running process via pgrep/pkill. The "
        "scheduler loop and SQLite database remain owned by `tmuxctl`."
    ),
)
def daemon_group() -> None:
    """Daemon lifecycle subgroup."""


@daemon_group.command(
    "start",
    context_settings={
        "help_option_names": ["-h", "--help"],
        "ignore_unknown_options": True,
        "allow_extra_args": True,
    },
)
@click.option(
    "--poll-interval",
    type=click.IntRange(min=1),
    default=None,
    help="Seconds between job polls (forwarded to `tmuxctl jobs daemon`).",
)
@click.option(
    "--run-once",
    is_flag=True,
    help="Process due jobs once and exit (forwarded to `tmuxctl jobs daemon`).",
)
@click.pass_context
def daemon_start(
    ctx: click.Context,
    poll_interval: Optional[int],
    run_once: bool,
) -> None:
    """Run the scheduler loop in the foreground.

    This is the canonical `ExecStart=` shape: the process stays alive
    until SIGTERM (matching the existing `tmuxctl-jobs.service` unit
    file generated by `HostBootstrapper`). The IPC daemon planned in
    the daemon-mode spike will eventually replace this entrypoint; that
    refactor is explicitly out of scope here.
    """
    args: list[str] = ["jobs", "daemon"]
    if poll_interval is not None:
        args.extend(["--poll-interval", str(poll_interval)])
    if run_once:
        args.append("--run-once")
    args.extend(ctx.args)
    exit_code = _run_tmuxctl(args)
    if exit_code != 0:
        ctx.exit(exit_code)


@daemon_group.command("status")
@click.pass_context
def daemon_status(ctx: click.Context) -> None:
    """Report whether a `tmuxctl jobs daemon` is currently running.

    Exit code semantics mirror `systemctl is-active`:

    - 0  -> daemon process is alive
    - 3  -> daemon process is NOT running
    """
    if _is_daemon_running():
        click.echo("running")
        return
    click.echo("not running")
    # `systemctl is-active` returns 3 for `inactive`; matching that lets
    # shell-script consumers `&& echo up || echo down` consistently.
    ctx.exit(3)


@daemon_group.command("stop")
@click.pass_context
def daemon_stop(ctx: click.Context) -> None:
    """Signal a running `tmuxctl jobs daemon` to terminate.

    Uses `pkill -TERM -f` so SIGTERM is delivered to every matching
    scheduler process. If no daemon is running we exit 0 (idempotent
    stop), matching `systemctl stop` semantics for an already-stopped
    unit.
    """
    pkill_path = shutil.which("pkill")
    if pkill_path is None:
        click.echo(
            "pocketshell: `pkill` is not available on this host; cannot "
            "stop the scheduler.",
            err=True,
        )
        ctx.exit(127)
    completed = subprocess.run(
        [pkill_path, "-TERM", "-f", _DAEMON_PROCESS_PATTERN],
        check=False,
        capture_output=True,
        text=True,
    )
    # pkill exit codes: 0 = signalled at least one, 1 = no match,
    # others = error. Treat "no match" as a successful idempotent stop.
    if completed.returncode == 0:
        click.echo("stopped")
        return
    if completed.returncode == 1:
        click.echo("not running")
        return
    if completed.stderr:
        sys.stderr.write(completed.stderr)
    ctx.exit(completed.returncode)
