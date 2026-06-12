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
  dependency would break `uv tool install pocketshell` and
  `pipx install pocketshell` for any user.
- Subprocess delegation keeps `pocketshell` decoupled from
  `tmuxctl`'s internal module layout, so updates to `tmuxctl` do not
  break the wrapper.
- The PATH-discovery story for `tmuxctl` is solved by the Android
  bootstrap wrapper, which derives PATH from the user's shell rc before
  probing tools. Delegating to whatever `tmuxctl` is on PATH keeps this
  wrapper decoupled from that bootstrap plumbing.

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

import os
import shutil
import subprocess
import sys
from typing import Any, Optional, Sequence

import click

from . import resume as _resume


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


def _try_daemon_sessions_list(
    *,
    sort_by: Optional[str],
    extra_args: Sequence[str],
) -> Optional[dict[str, Any]]:
    """Dispatch ``sessions.list`` to the daemon; return ``None`` on miss."""
    from pocketshell import daemon as _daemon

    socket_path = _daemon.resolve_socket_path()
    if not socket_path.exists():
        return None

    params: dict[str, Any] = {"extra_args": list(extra_args)}
    if sort_by:
        params["sort_by"] = sort_by

    try:
        result = _daemon.call(
            "sessions.list",
            params=params,
            socket_path=socket_path,
            timeout=5.0,
        )
    except (_daemon.DaemonClientError, RuntimeError, OSError):
        return None
    if not isinstance(result, dict):
        return None
    return result


def daemon_handler_list(params: dict[str, Any]) -> dict[str, Any]:
    """JSON-RPC handler for ``sessions.list``.

    Returns the same raw stdout/stderr/returncode envelope as the
    one-shot subprocess path so the CLI can preserve byte-identical
    output while moving the process spawn into the daemon.
    """
    args: list[str] = ["list"]
    sort_by = params.get("sort_by")
    if isinstance(sort_by, str) and sort_by:
        args.extend(["--by", sort_by])
    extra_args = params.get("extra_args")
    if isinstance(extra_args, list):
        args.extend(str(item) for item in extra_args if isinstance(item, str))
    return _run_tmuxctl_capture(args)


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
    envelope = _try_daemon_sessions_list(sort_by=sort_by, extra_args=ctx.args)
    if envelope is None:
        envelope = _run_tmuxctl_capture(args)
    _emit_envelope(ctx, envelope)


# ---------------------------------------------------------------------------
# `sessions resumable` / `sessions resume` — AI-CLI conversation discovery (#725)
# ---------------------------------------------------------------------------
#
# Unlike `sessions list` (live tmux sessions, delegated to `tmuxctl`), these two
# commands enumerate *resumable* AI-CLI conversations (claude / codex /
# opencode) recorded on the host and resume a selected one inside a
# memory-capped tmux session. The discovery + builder logic is pure and lives in
# :mod:`pocketshell.resume`; this module owns only the Click wiring + presentation.
# Per D22 this is the canonical command; there is no legacy fallback path.

# Default memory cap applied to a resumed conversation. A conversation that
# OOM-killed once comes back capped under tmuxctl's cgroup scope; overridable
# with `--mem`.
_DEFAULT_RESUME_MEM = "24G"


def _discover_marked() -> list[_resume.ResumableSession]:
    """Discover every resumable conversation and flag the live ones.

    The ``running`` flag is computed over *all* discovered sessions (before any
    cwd/engine filtering) so a live session is never offered for resume even
    when it sits in a different project than the current directory.
    """
    discovered = _resume.discover_all()
    return _resume.mark_running(discovered, _resume.list_live_panes())


def _selected_sessions(
    *, all_projects: bool, engine: Optional[str], limit: Optional[int]
) -> list[_resume.ResumableSession]:
    """Discover, mark running, then filter/sort exactly as the list is printed.

    Both `resumable` and `resume` share this so the 1-based index a user sees in
    `resumable` resolves to the same session under `resume`.
    """
    cwd = None if all_projects else os.getcwd()
    return _resume.merge_sessions(
        sessions=_discover_marked(),
        cwd=cwd,
        engine=engine,
        limit=limit,
    )


def _format_resumable_table(sessions: Sequence[_resume.ResumableSession]) -> str:
    """Render the fixed-column ``IDX ENGINE PROJECT WHEN LABEL`` table.

    Newest-first order is the caller's responsibility (sessions arrive already
    sorted). A live conversation is tagged ``(running)`` after its label so the
    user can see it is not offered for resume.
    """
    header = f"{'IDX':<4}{'ENGINE':<10}{'PROJECT':<20}{'WHEN':<8}LABEL"
    lines = [header]
    for idx, session in enumerate(sessions, start=1):
        label = session.label or "(no prompt)"
        if session.running:
            label = f"{label} (running)"
        when = _resume.format_relative(session.last_activity)
        lines.append(
            f"{idx:<4}{session.engine:<10}{session.project:<20}{when:<8}{label}"
        )
    return "\n".join(lines)


@sessions_group.command(
    "resumable",
    context_settings={"help_option_names": ["-h", "--help"]},
)
@click.option(
    "--all",
    "all_projects",
    is_flag=True,
    default=False,
    help="List resumable conversations from every project (default: only the current directory).",
)
@click.option(
    "--engine",
    type=click.Choice(list(_resume.ENGINES), case_sensitive=False),
    default=None,
    help="Restrict to one engine (claude / codex / opencode).",
)
@click.option(
    "-n",
    "limit",
    type=click.IntRange(min=0),
    default=None,
    help="Show at most N conversations (newest first).",
)
def sessions_resumable(
    all_projects: bool, engine: Optional[str], limit: Optional[int]
) -> None:
    """List resumable AI-CLI conversations (claude / codex / opencode).

    Conversations are merged across engines and printed newest-first. A live
    conversation (matching a running tmux pane) is tagged ``(running)`` and is
    not offered for resume (respects #666).
    """
    sessions = _selected_sessions(
        all_projects=all_projects, engine=engine, limit=limit
    )
    click.echo(_format_resumable_table(sessions))


@sessions_group.command(
    "resume",
    context_settings={"help_option_names": ["-h", "--help"]},
)
@click.argument("selector")
@click.option(
    "--all",
    "all_projects",
    is_flag=True,
    default=False,
    help="Resolve the selector against conversations from every project (default: current directory only).",
)
@click.option(
    "--engine",
    type=click.Choice(list(_resume.ENGINES), case_sensitive=False),
    default=None,
    help="Restrict the candidate set to one engine before resolving the selector.",
)
@click.option(
    "--mem",
    default=_DEFAULT_RESUME_MEM,
    show_default=True,
    help="Memory cap for the resumed session's tmuxctl scope.",
)
@click.pass_context
def sessions_resume(
    ctx: click.Context,
    selector: str,
    all_projects: bool,
    engine: Optional[str],
    mem: str,
) -> None:
    """Resume a recorded AI-CLI conversation inside a memory-capped tmux session.

    SELECTOR is the 1-based index from `sessions resumable`, or an exact /
    unambiguous-prefix session id. The selected conversation is launched via
    `tmuxctl create-or-attach --mem`, cd-ing to its recorded cwd first. A
    conversation already running in tmux is refused (it is never double-attached).
    """
    sessions = _selected_sessions(
        all_projects=all_projects, engine=engine, limit=None
    )
    session = _resume.select_session(sessions, selector)
    if session is None:
        click.echo(f"pocketshell: no resumable session matches {selector!r}.", err=True)
        ctx.exit(2)
        return
    if session.running:
        click.echo(
            f"pocketshell: that conversation is already running in tmux "
            f"({session.engine} @ {session.project}); refusing to double-attach.",
            err=True,
        )
        ctx.exit(3)
        return
    tmuxctl_path = _resolve_tmuxctl_binary()
    if tmuxctl_path is None:
        click.echo(_tmuxctl_missing_message(), err=True)
        ctx.exit(127)
        return
    argv = _resume.tmuxctl_resume_argv(session, tmuxctl_path=tmuxctl_path, mem=mem)
    completed = subprocess.run(argv, check=False)
    if completed.returncode != 0:
        ctx.exit(completed.returncode)


# ---------------------------------------------------------------------------
# `sessions create` — capped, detached session create primitive (#726)
# ---------------------------------------------------------------------------
#
# The host-side primitive PocketShell's app calls instead of building raw
# `tmux new-session -d` strings. Delegates to `tmuxctl create-detached`
# (tmuxctl >= 0.3.0), which wraps the session shell in a memory-capped
# cgroup-v2 systemd `--user` scope under `robust.slice`, so sessions
# PocketShell starts can never trigger the OOM-kill cascade that wiped the
# agent team. `create-detached` is already idempotent (a no-op when the
# session exists) — that contract is tmuxctl's, not re-implemented here.


@sessions_group.command(
    "create",
    context_settings={"help_option_names": ["-h", "--help"]},
)
@click.argument("name")
@click.option(
    "--cwd",
    "-c",
    "cwd",
    default=None,
    help="Working directory for the new session (forwarded to `tmuxctl create-detached -c`).",
)
@click.option(
    "--mem",
    default=None,
    help=(
        "Memory cap for the session's tmuxctl scope, e.g. 24G. "
        "DEFAULT: unset — tmuxctl resolves the per-project cap from the repo's "
        "cgroups.toml (PocketShell's is 30G). Only pass this to override that policy."
    ),
)
@click.pass_context
def sessions_create(
    ctx: click.Context,
    name: str,
    cwd: Optional[str],
    mem: Optional[str],
) -> None:
    """Create a memory-capped, DETACHED tmux session (delegates to `tmuxctl create-detached`).

    NAME is the tmux session name. The session is created inside tmuxctl's
    cgroup-v2 systemd `--user` scope (capped under `robust.slice`) but NOT
    attached — consumers attach over their own transport (PocketShell uses
    tmux `-CC` control mode). The create is idempotent: a no-op if the session
    already exists (tmuxctl's contract).

    `--mem` is intentionally UNSET by default so tmuxctl resolves the
    per-project cap from the repo's `cgroups.toml` (PocketShell's is 30G);
    only pass `--mem` to override that committed policy.
    """
    tmuxctl_path = _resolve_tmuxctl_binary()
    if tmuxctl_path is None:
        click.echo(_tmuxctl_missing_message(), err=True)
        ctx.exit(127)
        return
    argv = _resume.tmuxctl_create_argv(
        name, tmuxctl_path=tmuxctl_path, cwd=cwd, mem=mem
    )
    completed = subprocess.run(argv, check=False)
    if completed.returncode != 0:
        ctx.exit(completed.returncode)
