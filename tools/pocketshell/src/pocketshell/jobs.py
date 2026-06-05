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
  dependency would break `uv tool install pocketshell` and
  `pipx install pocketshell` for any user.
- Subprocess delegation keeps `pocketshell` decoupled from
  `tmuxctl`'s internal module layout (`tmuxctl.cli` / `tmuxctl.storage`
  / `tmuxctl.scheduler`), so updates to `tmuxctl` do not break the
  wrapper.
- The PATH-discovery story for `tmuxctl` is solved by the Android
  bootstrap wrapper, which derives PATH from the user's shell rc before
  probing tools. Delegating to whatever `tmuxctl` is on PATH keeps this
  wrapper decoupled from that bootstrap plumbing.

Subcommand coverage:

- `pocketshell jobs list [--session <name>]` -> `tmuxctl jobs list`
- `pocketshell jobs show <id>`               -> `tmuxctl jobs show`
- `pocketshell jobs trigger <id>`            -> `tmuxctl jobs trigger`
  (delegates verbatim; if `tmuxctl` does not yet implement a
  per-job trigger subcommand the subprocess exit code + stderr are
  proxied through unchanged. The brief calls for the port today; the
  underlying tmuxctl coverage can grow without changes here.)
- `pocketshell jobs add <session> --every <e> --message <m> [--start-now]`
                                             -> `tmuxctl jobs add`
- `pocketshell jobs edit <id> [--session <s>] [--every <e>]`
  `[--message <m>] [--enable|--disable]`      -> `tmuxctl jobs edit`
- `pocketshell jobs remove <id>`             -> `tmuxctl jobs remove`
- `pocketshell jobs daemon start|stop|status`

The `add` / `edit` / `remove` mutation verbs exist so the Android app's
`PocketshellJobsRemoteSource` can manage recurring jobs end-to-end after
the #231 parity swap (it emits `pocketshell jobs add|edit|remove` instead
of the legacy `tmuxctl jobs ...` strings). Each flag the app sends maps
one-to-one onto a flag `tmuxctl jobs <verb>` already accepts:

- `add`:    `<session> --every <e> --message <m> [--start-now]`
- `edit`:   `<id> [--session <s>] [--every <e>] [--message <m>]`
            `[--enable | --disable]`
- `remove`: `<id>`

Like the read verbs, the mutation verbs delegate verbatim and proxy
stdout/stderr + exit code unchanged. They use `ignore_unknown_options`
+ `allow_extra_args` so any flag `tmuxctl` grows (e.g. `--message-file`,
`--enter-delay-ms`, `--no-enter`) forwards without a wrapper change.

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
from typing import Any, Optional, Sequence

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


def _run_tmuxctl_capture(args: Sequence[str]) -> dict[str, Any]:
    """Invoke ``tmuxctl`` and return a daemon-friendly raw envelope."""
    tmuxctl_path = _resolve_tmuxctl_binary()
    if tmuxctl_path is None:
        return {
            "stdout": "",
            "stderr": _tmuxctl_missing_message() + "\n",
            "returncode": 127,
        }

    completed = subprocess.run(
        [tmuxctl_path, *args],
        check=False,
        capture_output=True,
        text=True,
    )
    return {
        "stdout": completed.stdout,
        "stderr": completed.stderr,
        "returncode": completed.returncode,
    }


def _emit_envelope(ctx: click.Context, envelope: dict[str, Any]) -> None:
    """Proxy a daemon/subprocess envelope to stdout/stderr and exit code."""
    if envelope.get("stdout"):
        sys.stdout.write(str(envelope["stdout"]))
    if envelope.get("stderr"):
        sys.stderr.write(str(envelope["stderr"]))
    exit_code = int(envelope.get("returncode", 0))
    if exit_code != 0:
        ctx.exit(exit_code)


def _try_daemon_jobs_call(
    method: str,
    params: dict[str, Any],
    *,
    timeout: float = 5.0,
) -> Optional[dict[str, Any]]:
    """Dispatch a jobs RPC to the daemon; return ``None`` on miss/error."""
    from pocketshell import daemon as _daemon

    socket_path = _daemon.resolve_socket_path()
    if not socket_path.exists():
        return None

    try:
        result = _daemon.call(
            method,
            params=params,
            socket_path=socket_path,
            timeout=timeout,
        )
    except (_daemon.DaemonClientError, RuntimeError, OSError):
        return None
    if not isinstance(result, dict):
        return None
    return result


def _extra_args(params: dict[str, Any]) -> list[str]:
    raw = params.get("extra_args")
    if not isinstance(raw, list):
        return []
    return [item for item in raw if isinstance(item, str)]


def daemon_handler_list(params: dict[str, Any]) -> dict[str, Any]:
    """JSON-RPC handler for ``jobs.list``."""
    args: list[str] = ["jobs", "list"]
    session = params.get("session")
    if isinstance(session, str) and session:
        args.extend(["--session", session])
    args.extend(_extra_args(params))
    return _run_tmuxctl_capture(args)


def daemon_handler_show(params: dict[str, Any]) -> dict[str, Any]:
    """JSON-RPC handler for ``jobs.show``."""
    job_id = params.get("job_id")
    args: list[str] = ["jobs", "show", str(job_id)]
    args.extend(_extra_args(params))
    return _run_tmuxctl_capture(args)


def daemon_handler_trigger(params: dict[str, Any]) -> dict[str, Any]:
    """JSON-RPC handler for ``jobs.trigger``."""
    job_id = params.get("job_id")
    args: list[str] = ["jobs", "trigger", str(job_id)]
    args.extend(_extra_args(params))
    return _run_tmuxctl_capture(args)


def daemon_handler_add(params: dict[str, Any]) -> dict[str, Any]:
    """JSON-RPC handler for ``jobs.add``."""
    session_name = params.get("session_name")
    every = params.get("every")
    args: list[str] = ["jobs", "add", str(session_name), "--every", str(every)]
    message = params.get("message")
    if isinstance(message, str):
        args.extend(["--message", message])
    if bool(params.get("start_now")):
        args.append("--start-now")
    args.extend(_extra_args(params))
    return _run_tmuxctl_capture(args)


def daemon_handler_edit(params: dict[str, Any]) -> dict[str, Any]:
    """JSON-RPC handler for ``jobs.edit``."""
    job_id = params.get("job_id")
    args: list[str] = ["jobs", "edit", str(job_id)]
    session = params.get("session")
    if isinstance(session, str):
        args.extend(["--session", session])
    every = params.get("every")
    if isinstance(every, str):
        args.extend(["--every", every])
    message = params.get("message")
    if isinstance(message, str):
        args.extend(["--message", message])
    if bool(params.get("enable")):
        args.append("--enable")
    if bool(params.get("disable")):
        args.append("--disable")
    args.extend(_extra_args(params))
    return _run_tmuxctl_capture(args)


def daemon_handler_remove(params: dict[str, Any]) -> dict[str, Any]:
    """JSON-RPC handler for ``jobs.remove``."""
    job_id = params.get("job_id")
    args: list[str] = ["jobs", "remove", str(job_id)]
    args.extend(_extra_args(params))
    return _run_tmuxctl_capture(args)


def daemon_handler_status(_params: dict[str, Any]) -> dict[str, Any]:
    """JSON-RPC handler for ``jobs.status``."""
    if _is_daemon_running():
        return {"stdout": "running\n", "stderr": "", "returncode": 0}
    return {"stdout": "not running\n", "stderr": "", "returncode": 3}


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
    params: dict[str, Any] = {"extra_args": list(ctx.args)}
    if session:
        params["session"] = session
    envelope = _try_daemon_jobs_call("jobs.list", params)
    if envelope is None:
        envelope = _run_tmuxctl_capture(args)
    _emit_envelope(ctx, envelope)


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
    envelope = _try_daemon_jobs_call(
        "jobs.show",
        {"job_id": job_id, "extra_args": list(ctx.args)},
    )
    if envelope is None:
        envelope = _run_tmuxctl_capture(args)
    _emit_envelope(ctx, envelope)


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
    envelope = _try_daemon_jobs_call(
        "jobs.trigger",
        {"job_id": job_id, "extra_args": list(ctx.args)},
    )
    if envelope is None:
        envelope = _run_tmuxctl_capture(args)
    _emit_envelope(ctx, envelope)


# ----- jobs add / edit / remove --------------------------------------


@jobs_group.command(
    "add",
    context_settings={
        "help_option_names": ["-h", "--help"],
        "ignore_unknown_options": True,
        "allow_extra_args": True,
    },
)
@click.argument("session_name", type=str)
@click.option(
    "--every",
    "every",
    type=str,
    required=True,
    help="Recurring interval like 15m or 2h (forwarded to `tmuxctl jobs add`).",
)
@click.option(
    "--message",
    "message",
    type=str,
    default=None,
    help="Message text to send (forwarded to `tmuxctl jobs add`).",
)
@click.option(
    "--start-now",
    "start_now",
    is_flag=True,
    help="Run the job on the next daemon poll (forwarded to `tmuxctl jobs add`).",
)
@click.pass_context
def jobs_add(
    ctx: click.Context,
    session_name: str,
    every: str,
    message: Optional[str],
    start_now: bool,
) -> None:
    """Create a recurring message job (delegates to `tmuxctl jobs add`).

    Mirrors the command the Android app's `PocketshellJobsRemoteSource.add`
    emits: `pocketshell jobs add <session> --every <e> --message <m>
    [--start-now]`. The argument order and flags map one-to-one onto
    `tmuxctl jobs add`, so the wrapper just forwards them. Extra flags
    `tmuxctl` accepts (`--message-file`, `--enter-delay-ms`, `--no-enter`)
    pass through verbatim via `ctx.args`.
    """
    args: list[str] = ["jobs", "add", session_name, "--every", every]
    if message is not None:
        args.extend(["--message", message])
    if start_now:
        args.append("--start-now")
    args.extend(ctx.args)
    params: dict[str, Any] = {
        "session_name": session_name,
        "every": every,
        "message": message,
        "start_now": start_now,
        "extra_args": list(ctx.args),
    }
    envelope = _try_daemon_jobs_call("jobs.add", params)
    if envelope is None:
        envelope = _run_tmuxctl_capture(args)
    _emit_envelope(ctx, envelope)


@jobs_group.command(
    "edit",
    context_settings={
        "help_option_names": ["-h", "--help"],
        "ignore_unknown_options": True,
        "allow_extra_args": True,
    },
)
@click.argument("job_id", type=int)
@click.option(
    "--session",
    "session",
    type=str,
    default=None,
    help="Replace the tmux session name (forwarded to `tmuxctl jobs edit`).",
)
@click.option(
    "--every",
    "every",
    type=str,
    default=None,
    help="Replace the recurring interval (forwarded to `tmuxctl jobs edit`).",
)
@click.option(
    "--message",
    "message",
    type=str,
    default=None,
    help="Replace the stored message text (forwarded to `tmuxctl jobs edit`).",
)
@click.option(
    "--enable",
    "enable",
    is_flag=True,
    help="Enable the job (forwarded to `tmuxctl jobs edit`).",
)
@click.option(
    "--disable",
    "disable",
    is_flag=True,
    help="Disable the job (forwarded to `tmuxctl jobs edit`).",
)
@click.pass_context
def jobs_edit(
    ctx: click.Context,
    job_id: int,
    session: Optional[str],
    every: Optional[str],
    message: Optional[str],
    enable: bool,
    disable: bool,
) -> None:
    """Update an existing job (delegates to `tmuxctl jobs edit`).

    Mirrors the command the Android app's `PocketshellJobsRemoteSource.edit`
    emits: `pocketshell jobs edit <id> [--session <s>] [--every <e>]
    [--message <m>] [--enable | --disable]`. The app sends at most one of
    `--enable` / `--disable`; if both are passed we reject locally before
    invoking `tmuxctl` since they are contradictory. Every other flag maps
    one-to-one onto `tmuxctl jobs edit`; extras pass through via `ctx.args`.
    """
    if enable and disable:
        raise click.UsageError("--enable and --disable are mutually exclusive.")
    args: list[str] = ["jobs", "edit", str(job_id)]
    if session is not None:
        args.extend(["--session", session])
    if every is not None:
        args.extend(["--every", every])
    if message is not None:
        args.extend(["--message", message])
    if enable:
        args.append("--enable")
    if disable:
        args.append("--disable")
    args.extend(ctx.args)
    params = {
        "job_id": job_id,
        "session": session,
        "every": every,
        "message": message,
        "enable": enable,
        "disable": disable,
        "extra_args": list(ctx.args),
    }
    envelope = _try_daemon_jobs_call("jobs.edit", params)
    if envelope is None:
        envelope = _run_tmuxctl_capture(args)
    _emit_envelope(ctx, envelope)


@jobs_group.command(
    "remove",
    context_settings={
        "help_option_names": ["-h", "--help"],
        "ignore_unknown_options": True,
        "allow_extra_args": True,
    },
)
@click.argument("job_id", type=int)
@click.pass_context
def jobs_remove(ctx: click.Context, job_id: int) -> None:
    """Remove a scheduled job (delegates to `tmuxctl jobs remove`).

    Mirrors the command the Android app's `PocketshellJobsRemoteSource.remove`
    emits: `pocketshell jobs remove <id>`. The job id maps directly onto
    `tmuxctl jobs remove JOB_ID`.
    """
    args: list[str] = ["jobs", "remove", str(job_id), *ctx.args]
    envelope = _try_daemon_jobs_call(
        "jobs.remove",
        {"job_id": job_id, "extra_args": list(ctx.args)},
    )
    if envelope is None:
        envelope = _run_tmuxctl_capture(args)
    _emit_envelope(ctx, envelope)


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
    until SIGTERM (matching the `pocketshell-jobs.service` unit file
    generated by `HostBootstrapper`). The IPC daemon planned in the
    daemon-mode spike will eventually replace this entrypoint; that
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
    envelope = _try_daemon_jobs_call("jobs.status", {})
    if envelope is None:
        envelope = daemon_handler_status({})
    _emit_envelope(ctx, envelope)


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
