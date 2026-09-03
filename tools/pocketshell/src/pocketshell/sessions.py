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

- `pocketshell sessions list` -> `tmuxctl list`

`tmuxctl list` currently emits its human table only. `pocketshell sessions
list --json` is implemented HERE (not forwarded): it emits the combined
tmuxctl + aplexer name set so the Android list matches the terminal
enumerator (`tmuxctl list` / `t`) instead of a default-socket
`tmux list-sessions` subset. Unknown extra flags still forward to tmuxctl.
"""

from __future__ import annotations

import json
import os
import shlex
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Mapping, Optional, Sequence

import click

from . import agent_log as _agent_log
from . import aplexer as _aplexer
from . import config as _config
from . import resume as _resume
from . import session_enum as _session_enum


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


def _is_schema2_list_envelope(value: Any) -> bool:
    """Validate a daemon ``sessions.list --json`` reply as schema 2.

    A daemon process started from an older PocketShell answers the same
    method with a schema-1 body. Per D22 there is no compatibility path for
    that: the reply is treated as malformed so the skew surfaces loudly
    instead of a schema-1 document reaching a schema-2 parser.
    """
    from pocketshell import daemon as _daemon

    if not _daemon.is_command_envelope(value):
        return False
    import json as _json

    try:
        payload = _json.loads(str(value.get("stdout") or ""))
    except ValueError:
        return False
    return (
        isinstance(payload, dict)
        and payload.get("schema") == _session_enum.SCHEMA_VERSION
        and isinstance(payload.get("sessions"), list)
        and isinstance(payload.get("errors"), list)
    )


def _try_daemon_sessions_list(
    *,
    sort_by: Optional[str],
    extra_args: Sequence[str],
    as_json: bool = False,
) -> Optional[dict[str, Any]]:
    """Dispatch ``sessions.list`` through the shared typed daemon boundary."""
    from pocketshell import daemon as _daemon

    socket_path = _daemon.resolve_socket_path()
    params: dict[str, Any] = {"extra_args": list(extra_args), "as_json": as_json}
    if sort_by:
        params["sort_by"] = sort_by

    return _daemon.try_call(
        "sessions.list",
        params=params,
        socket_path=socket_path,
        timeout=5.0,
        result_validator=(
            _is_schema2_list_envelope if as_json else _daemon.is_command_envelope
        ),
    )


def _list_envelope(
    *,
    sort_by: Optional[str],
    extra_args: Sequence[str],
    as_json: bool,
) -> dict[str, Any]:
    """Build the sessions.list stdout envelope (human table or JSON)."""
    args: list[str] = ["list"]
    if sort_by:
        args.extend(["--by", sort_by])
    args.extend(extra_args)
    tmuxctl = _run_tmuxctl_capture(args)
    tmux_stdout = str(tmuxctl.get("stdout") or "")
    tmux_returncode = int(tmuxctl.get("returncode", 0))
    tmux_ok = tmux_returncode == 0
    tmux_error = None
    if not tmux_ok:
        tmux_error = (
            str(tmuxctl.get("stderr") or "").strip()
            or f"`tmuxctl {' '.join(args)}` exited {tmux_returncode}"
        )
    sessions, errors = _session_enum.enumerate_live_sessions(
        tmuxctl_stdout=tmux_stdout if tmux_ok else None,
        tmuxctl_error=tmux_error,
        enrich_tmux=as_json,
    )
    if as_json:
        import json as _json

        return {
            "stdout": _json.dumps(
                _session_enum.json_payload(sessions, errors), indent=2
            )
            + "\n",
            "stderr": "" if tmux_ok else str(tmuxctl.get("stderr") or ""),
            "returncode": 0 if sessions or tmux_ok else tmux_returncode,
        }
    appendix = _session_enum.format_aplexer_table(sessions)
    stdout = tmux_stdout
    if appendix:
        stdout = tmux_stdout.rstrip("\n") + "\n" + appendix
    return {
        "stdout": stdout,
        "stderr": str(tmuxctl.get("stderr") or ""),
        "returncode": int(tmuxctl.get("returncode", 0)),
    }


def daemon_handler_list(params: dict[str, Any]) -> dict[str, Any]:
    """JSON-RPC handler for ``sessions.list``.

    Returns the same raw stdout/stderr/returncode envelope as the
    one-shot subprocess path so the CLI can preserve byte-identical
    output while moving the process spawn into the daemon.
    """
    sort_by = params.get("sort_by")
    extra_args = params.get("extra_args")
    extras = (
        [str(item) for item in extra_args if isinstance(item, str)]
        if isinstance(extra_args, list)
        else []
    )
    as_json = bool(params.get("as_json"))
    return _list_envelope(
        sort_by=sort_by if isinstance(sort_by, str) and sort_by else None,
        extra_args=extras,
        as_json=as_json,
    )


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
    # `ignore_unknown_options` + `allow_extra_args` preserve the wrapper's
    # transparent pass-through contract for flags owned by a newer tmuxctl.
    # This is not a claim that the current tmuxctl list command supports a
    # structured/JSON mode.
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
@click.option(
    "--json",
    "as_json",
    is_flag=True,
    default=False,
    help=(
        "Emit the combined tmuxctl + aplexer session list as JSON. "
        "This is owned by pocketshell (tmuxctl has no --json list mode)."
    ),
)
@click.pass_context
def sessions_list(
    ctx: click.Context, sort_by: Optional[str], as_json: bool
) -> None:
    """List live sessions on the host.

    Human output still starts as the `tmuxctl list` table so
    `HostTmuxSessionListParser` keeps working. Aplexer rows are appended
    under an APLEXER heading when that manager is present. `--json` is the
    structured form the Android list prefers: names match `tmuxctl list`
    (not a default-socket `tmux list-sessions` subset) and each row is
    tagged with its manager.
    """
    extras = [arg for arg in ctx.args if arg not in {"--json", "-json"}]
    envelope = _try_daemon_sessions_list(
        sort_by=sort_by, extra_args=extras, as_json=as_json
    )
    if envelope is None:
        envelope = _list_envelope(
            sort_by=sort_by, extra_args=extras, as_json=as_json
        )
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
#
# Since the aplexer adoption (simplification plan §B.3) the same command is
# also the ONE session-create entry point for both backends: which backend a
# new session lands on is host policy read from `~/.config/pocketshell/
# config.toml` (see `pocketshell.config`), not something the phone chooses.

#: Wire version of the `--json` envelope, deliberately the same number as
#: `sessions list --json` (`session_enum.SCHEMA_VERSION`) — one client-visible
#: schema generation across the `sessions` verbs.
CREATE_SCHEMA_VERSION = _session_enum.SCHEMA_VERSION

#: Timeout for the small `tmux` probes/`send-keys` this command runs. These
#: are local, single-session calls; anything slower is a wedged server, and
#: waiting on it would hang the phone's create.
_TMUX_TIMEOUT_S = 5.0

#: Timeout for `a start`. aplexer's own default `--startup-timeout-ms` is
#: 10s, so this is that plus room for process spawn.
_APLEXER_START_TIMEOUT_S = 20.0


class _CreateError(Exception):
    """A create that failed, carrying the exit code to propagate."""

    def __init__(self, message: str, *, exit_code: int = 1) -> None:
        super().__init__(message)
        self.message = message
        self.exit_code = exit_code


def _route_backend(
    engine: Optional[str],
    backend_flag: Optional[str],
    config: Mapping[str, Any],
) -> str:
    """Decide which backend a new session is created on. Pure.

    Order (simplification plan §B.3):

    1. an explicit ``--backend`` wins outright;
    2. else, when an ``--engine`` was asked for, ``[backends].agent``;
    3. else ``[backends].shell``.

    A key the config does not set resolves to ``tmux``
    (:data:`pocketshell.config.DEFAULT_BACKEND`).
    """
    if backend_flag and backend_flag.strip():
        return backend_flag.strip()
    key = (
        _config.BACKEND_KEY_AGENT
        if engine and engine.strip()
        else _config.BACKEND_KEY_SHELL
    )
    return _config.backend_for(config, key)


def _run_tmux(argv: Sequence[str]) -> tuple[int, str, str]:
    """Run one ``tmux`` command -> ``(returncode, stdout, stderr)``.

    A module-level seam so the unit suite can drive the socket probe and the
    ``send-keys`` step without a tmux server. A missing binary is reported as
    127 rather than raised, since every caller here treats "no tmux" as a
    create failure with a message, not a traceback.
    """
    try:
        completed = subprocess.run(
            list(argv),
            check=False,
            capture_output=True,
            text=True,
            timeout=_TMUX_TIMEOUT_S,
        )
    except subprocess.TimeoutExpired:
        return 124, "", f"`{' '.join(argv)}` timed out"
    except OSError as exc:
        return 127, "", str(exc)
    return completed.returncode, completed.stdout, completed.stderr


def _tmux_session_socket(
    name: str, *, env: Optional[Mapping[str, str]] = None
) -> Optional[str]:
    """Socket path of the live tmux session ``name``, or ``None``.

    tmuxctl runs one tmux server per session on ``tmuxctl-<name>`` under
    tmux's socket dir (``session_enum.TMUXCTL_SOCKET_PREFIX``), so that is
    probed first; a session created outside tmuxctl still lives on the shared
    ``default`` socket, so that is the fallback. ``-t '=<name>'`` is tmux's
    exact-match form — a prefix match would happily resolve ``work`` onto
    ``work-2``. ``has-session`` never starts a server, so probing a socket
    that does not exist is free.
    """
    socket_dir = _session_enum.tmux_socket_dir(env)
    candidates = (
        socket_dir / f"{_session_enum.TMUXCTL_SOCKET_PREFIX}{name}",
        socket_dir / _session_enum.TMUX_DEFAULT_SOCKET,
    )
    for candidate in candidates:
        code, _stdout, _stderr = _run_tmux(
            ["tmux", "-S", str(candidate), "has-session", "-t", f"={name}"]
        )
        if code == 0:
            return str(candidate)
    return None


def agent_launch_command(
    engine: str, *, directory: str, profile: Optional[str] = None
) -> str:
    """The launch line typed into a freshly created tmux session.

    Shape: ``pocketshell agent <engine> --dir <cwd> [--profile <p>]``. The
    phone used to type this itself (docs/aplexer-integration.md); doing it
    server-side means the client never has to build a shell string.

    ``--dir`` is included because `pocketshell agent <engine>` requires it
    (``agents.py``'s ``_make_agent_command``) — the plan's shorthand
    ``pocketshell agent <engine> [--profile P]`` would exit 2. Every
    interpolated value is shell-quoted: ``engine``/``profile`` are free-form
    strings from the caller and this text is executed by the session's shell.
    """
    parts = [
        "pocketshell",
        "agent",
        shlex.quote(engine),
        "--dir",
        shlex.quote(directory),
    ]
    if profile:
        parts.extend(["--profile", shlex.quote(profile)])
    return " ".join(parts)


def _create_on_tmux(
    *,
    name: str,
    cwd: Optional[str],
    mem: Optional[str],
    engine: Optional[str],
    profile: Optional[str],
    probe_existing: bool,
    quiet_stdout: bool,
) -> dict[str, Any]:
    """tmux arm: `tmuxctl create-detached`, plus the agent launch line.

    ``probe_existing`` asks whether the session already existed. That costs a
    ``has-session`` call, so it is only done when the answer is actually used:
    for the ``created`` field of the ``--json`` envelope, and to decide
    whether to send the launch line (re-sending it into a session that is
    already running an agent is exactly what idempotency must not do).

    ``quiet_stdout`` keeps tmuxctl's own chatter off OUR stdout. `tmuxctl
    create-detached` echoes the session name, which would otherwise land in
    front of the JSON envelope and make the machine-readable stream
    unparseable (observed on the dev box, not hypothetical). Its output is
    relayed to stderr instead, so nothing is lost.
    """
    tmuxctl_path = _resolve_tmuxctl_binary()
    if tmuxctl_path is None:
        raise _CreateError(_tmuxctl_missing_message(), exit_code=127)

    existing_socket = _tmux_session_socket(name) if probe_existing else None
    created = existing_socket is None

    argv = _resume.tmuxctl_create_argv(
        name, tmuxctl_path=tmuxctl_path, cwd=cwd, mem=mem
    )
    if quiet_stdout:
        completed = subprocess.run(argv, check=False, capture_output=True, text=True)
        for chunk in (completed.stdout, completed.stderr):
            if chunk:
                sys.stderr.write(str(chunk))
    else:
        completed = subprocess.run(argv, check=False)
    if completed.returncode != 0:
        detail = str(getattr(completed, "stderr", "") or "").strip()
        raise _CreateError(
            f"pocketshell: `tmuxctl create-detached {name}` exited "
            f"{completed.returncode}." + (f" {detail}" if detail else ""),
            exit_code=completed.returncode,
        )

    if engine and created:
        socket_path = _tmux_session_socket(name)
        if socket_path is None:
            raise _CreateError(
                f"pocketshell: tmux session {name!r} was not found after "
                "create; cannot start the agent in it."
            )
        launch = agent_launch_command(
            engine, directory=cwd or os.getcwd(), profile=profile
        )
        code, _stdout, stderr = _run_tmux(
            [
                "tmux",
                "-S",
                socket_path,
                "send-keys",
                # `send-keys` takes a target-PANE, not a target-session: the
                # bare `={name}` that `has-session` accepts resolves to
                # nothing here ("can't find pane", observed on the dev box).
                # `={name}:` is the exact-match session plus its current
                # window/pane — still exact, so `work` can never land in
                # `work-2`.
                "-t",
                f"={name}:",
                launch,
                "Enter",
            ]
        )
        if code != 0:
            raise _CreateError(
                f"pocketshell: could not start {engine} in tmux session "
                f"{name!r}: {stderr.strip() or f'send-keys exited {code}'}"
            )

    return {
        "name": name,
        "manager": _session_enum.MANAGER_TMUX,
        "id": None,
        "created": created,
    }


def _aplexer_snapshot() -> Any:
    """`a --json snapshot` (falling back to `a --json list`).

    Same two-step probe `session_enum` uses for the listing, kept as its own
    seam here so the create path can be unit-tested without an `a` binary.
    """
    payload = _aplexer.run_json(["snapshot"], feature="sessions")
    if payload is None:
        payload = _aplexer.run_json(["list"], feature="sessions")
    return payload


def _run_aplexer(argv: Sequence[str]) -> tuple[int, str, str]:
    """Run one ``a`` command -> ``(returncode, stdout, stderr)``.

    ``aplexer.run_json`` is deliberately not used for ``start``: it collapses
    every failure to ``None``, and this path must report *why* a start failed.
    """
    try:
        completed = subprocess.run(
            list(argv),
            check=False,
            capture_output=True,
            text=True,
            timeout=_APLEXER_START_TIMEOUT_S,
        )
    except subprocess.TimeoutExpired:
        return 124, "", f"`{' '.join(argv)}` timed out"
    except OSError as exc:
        return 127, "", str(exc)
    return completed.returncode, completed.stdout, completed.stderr


def aplexer_start_argv(
    *,
    aplexer_path: str,
    workspace: str,
    tag: str,
    engine: Optional[str],
    profile: Optional[str],
) -> list[str]:
    """Build ``a --json start --workspace <ws> --tag <tag> [--engine …]``.

    ``--json`` is a global flag on ``a``, so it is placed before the
    subcommand exactly like :func:`pocketshell.aplexer.run_json` does — one
    spelling across the codebase. ``--engine`` is omitted for a plain shell
    session so aplexer applies its own configured default.
    """
    argv = [aplexer_path, "--json", "start", "--workspace", workspace, "--tag", tag]
    if engine:
        argv.extend(["--engine", engine])
    if profile:
        argv.extend(["--profile", profile])
    return argv


def _aplexer_existing_record(
    payload: Any, *, workspace: str, tag: str
) -> Optional[Mapping[str, Any]]:
    """Find a live aplexer session already holding ``workspace`` + ``tag``.

    aplexer keys a session by exactly that pair (`a start` refuses a second
    one), so it is the identity to match on. A finished session does not
    count: `a start` reclaims that pair, i.e. it really does create.
    """
    if not isinstance(payload, list):
        return None
    target = os.path.realpath(workspace)
    for raw in payload:
        if not isinstance(raw, Mapping):
            continue
        if str(raw.get("tag") or "") != tag:
            continue
        raw_workspace = raw.get("workspace") or raw.get("cwd") or ""
        if os.path.realpath(str(raw_workspace)) != target:
            continue
        if str(raw.get("phase") or "").strip().lower() in {"exited", "failed"}:
            continue
        return raw
    return None


def _create_on_aplexer(
    *,
    name: str,
    cwd: Optional[str],
    engine: Optional[str],
    profile: Optional[str],
) -> dict[str, Any]:
    """aplexer arm: `a start` for the workspace+tag this NAME denotes.

    NAME is the tag (the tmux arm's session name is used verbatim as the
    aplexer tag, so one create call names the same session on either
    backend); ``--cwd`` — defaulting to the process cwd exactly like the tmux
    arm's ``tmuxctl create-detached`` — is the workspace. The reported
    ``name`` is the row's listing name (``<workspace-basename>:<tag>``, from
    ``session_enum.aplexer_display_name``) so it round-trips with what
    `sessions list --json` shows.
    """
    aplexer_path = _aplexer.which_a()
    if aplexer_path is None:
        raise _CreateError(
            "pocketshell: `a` (aplexer) is not installed on this host, but "
            "the backend routing selected it. Install aplexer or set "
            "[backends] in ~/.config/pocketshell/config.toml back to tmux.",
            exit_code=127,
        )
    workspace = cwd or os.getcwd()

    existing = _aplexer_existing_record(
        _aplexer_snapshot(), workspace=workspace, tag=name
    )
    if existing is not None:
        return {
            "name": _session_enum.aplexer_display_name(existing) or name,
            "manager": _session_enum.MANAGER_APLEXER,
            "id": str(existing.get("id") or "") or None,
            "created": False,
        }

    argv = aplexer_start_argv(
        aplexer_path=aplexer_path,
        workspace=workspace,
        tag=name,
        engine=engine,
        profile=profile,
    )
    code, stdout, stderr = _run_aplexer(argv)
    if code != 0:
        raise _CreateError(
            f"pocketshell: `a start --tag {name}` exited {code}: "
            f"{stderr.strip() or stdout.strip() or 'no output'}",
            exit_code=code,
        )
    try:
        record = json.loads(stdout)
    except ValueError as exc:
        raise _CreateError(
            f"pocketshell: `a --json start` returned unreadable JSON: {exc}"
        ) from exc
    if not isinstance(record, Mapping):
        raise _CreateError(
            "pocketshell: `a --json start` returned "
            f"{type(record).__name__}, expected a session record"
        )
    return {
        "name": _session_enum.aplexer_display_name(record) or name,
        "manager": _session_enum.MANAGER_APLEXER,
        "id": str(record.get("id") or "") or None,
        "created": True,
    }


def _emit_create_failure(
    ctx: click.Context, message: str, *, exit_code: int, as_json: bool
) -> None:
    """Report a failed create: JSON error envelope on stdout, else stderr."""
    if as_json:
        click.echo(
            json.dumps({"schema": CREATE_SCHEMA_VERSION, "error": message}, indent=2)
        )
    else:
        click.echo(message, err=True)
    ctx.exit(exit_code if exit_code else 1)


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
    help=(
        "Working directory for the new session (tmux: `tmuxctl create-detached -c`; "
        "aplexer: `a start --workspace`)."
    ),
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
@click.option(
    "--engine",
    "engine",
    default=None,
    help=(
        "Start a coding agent in the new session (claude / codex / opencode / "
        "grok / …). Also selects the `[backends].agent` routing policy."
    ),
)
@click.option(
    "--profile",
    "profile",
    default=None,
    help="Named host profile for --engine (see `pocketshell profiles list`).",
)
@click.option(
    "--backend",
    "backend",
    type=click.Choice(list(_config.BACKENDS), case_sensitive=False),
    default=None,
    help=(
        "Force the session backend, overriding the host's "
        "~/.config/pocketshell/config.toml [backends] policy."
    ),
)
@click.option(
    "--json",
    "as_json",
    is_flag=True,
    default=False,
    help=(
        "Emit the schema-2 create envelope "
        '{"schema","name","manager","id","created"} on stdout.'
    ),
)
@click.pass_context
def sessions_create(
    ctx: click.Context,
    name: str,
    cwd: Optional[str],
    mem: Optional[str],
    engine: Optional[str],
    profile: Optional[str],
    backend: Optional[str],
    as_json: bool,
) -> None:
    """Create a DETACHED session on the host's configured backend.

    NAME is the session name (the tmux session name; the aplexer tag). The
    session is created but NOT attached — consumers attach over their own
    transport (`pocketshell sessions attach`, or tmux `-CC` control mode).

    Backend routing: `--backend` wins; otherwise `--engine` selects
    `[backends].agent` and a plain session `[backends].shell` from
    `~/.config/pocketshell/config.toml` (both default to tmux).

    On tmux the session is created inside tmuxctl's cgroup-v2 systemd `--user`
    scope (capped under `robust.slice`); with `--engine` the agent launch line
    is then sent into it server-side. `--mem` is intentionally UNSET by
    default so tmuxctl resolves the per-project cap from the repo's
    `cgroups.toml` (PocketShell's is 30G).

    The create is idempotent: an existing session is a success that reports
    `"created": false` and starts no second agent in it.
    """
    try:
        config = _config.load_config()
        resolved = _route_backend(engine, backend, config)
        if resolved == _config.BACKEND_APLEXER:
            result = _create_on_aplexer(
                name=name, cwd=cwd, engine=engine, profile=profile
            )
        elif resolved == _config.BACKEND_TMUX:
            result = _create_on_tmux(
                name=name,
                cwd=cwd,
                mem=mem,
                engine=engine,
                profile=profile,
                # Only pay for the probe when its answer is used.
                probe_existing=as_json or bool(engine),
                quiet_stdout=as_json,
            )
        else:
            raise _CreateError(
                f"pocketshell: unknown session backend {resolved!r} "
                f"(known: {', '.join(_config.BACKENDS)}).",
                exit_code=2,
            )
    except _config.ConfigError as exc:
        _emit_create_failure(ctx, str(exc), exit_code=2, as_json=as_json)
        return
    except _CreateError as exc:
        _emit_create_failure(
            ctx, exc.message, exit_code=exc.exit_code, as_json=as_json
        )
        return

    if as_json:
        click.echo(json.dumps({"schema": CREATE_SCHEMA_VERSION, **result}, indent=2))


# ---------------------------------------------------------------------------
# `sessions attach` — resolve a name, then BECOME the attached session
# ---------------------------------------------------------------------------
#
# The client (`exec pocketshell sessions attach --hide-status '<name>'`) runs
# this over its SSH channel and expects the process to turn INTO the session,
# so the happy path never returns: it resolves the name against exactly the
# same enumeration `sessions list` uses, then `execvp`s either `a attach` or
# `tmux attach-session`. Everything that can go wrong has to be decided
# BEFORE the exec, hence the up-front binary + socket resolution.
#
# Exit codes (stable contract for the client):
#   3   no session by that name
#   4   ambiguous — several sessions match
#   5   matched a tmux session but its server socket could not be located
#   127 the `tmux` / `a` binary needed to attach is not installed

ATTACH_EXIT_NOT_FOUND = 3
ATTACH_EXIT_AMBIGUOUS = 4
ATTACH_EXIT_NO_SOCKET = 5
ATTACH_EXIT_NO_BINARY = 127

#: Shortest aplexer-id prefix accepted as a selector. Shorter than this and a
#: "prefix" is really a guess, so it is rejected as not-found rather than
#: silently attaching to whichever session happened to sort first.
APLEXER_ID_PREFIX_MIN = 8

#: Per-probe timeout for the `tmux has-session` socket sweep.
TMUX_PROBE_TIMEOUT_S = 2.0

#: Whole-sweep budget. The socket directory accumulates hundreds of dead
#: sockets from past runs (~4 ms each to reject); newest-first ordering means
#: the live one is normally hit in the first handful, and the budget keeps a
#: pathological directory from stalling an interactive attach.
TMUX_SOCKET_SWEEP_BUDGET_S = 5.0


def _resolve_aplexer_binary() -> Optional[str]:
    """Locate the ``a`` (aplexer) CLI, honouring ``APLEXER_BIN``.

    Wrapped rather than calling :func:`pocketshell.aplexer.which_a` inline so
    the attach-time availability check has its own monkeypatch seam: patching
    ``which_a`` itself would also silence the enumeration probe that produced
    the aplexer rows in the first place.
    """
    return _aplexer.which_a()


def _exec(argv: list[str]) -> None:
    """Replace this process with ``argv`` — does not return on success.

    Isolated as a module-level seam so the unit suite can capture the argv a
    resolution produced instead of actually exec'ing the test runner away.
    """
    os.execvp(argv[0], argv)


def _tmux_has_session(socket_path: str, name: str) -> bool:
    """Whether the tmux server on ``socket_path`` owns session ``name``.

    ``-t '=name'`` is tmux's exact-match form; without the ``=`` tmux would
    accept a prefix and happily report a different session.
    """
    try:
        completed = subprocess.run(
            ["tmux", "-S", socket_path, "has-session", "-t", f"={name}"],
            check=False,
            capture_output=True,
            text=True,
            timeout=TMUX_PROBE_TIMEOUT_S,
        )
    except (OSError, subprocess.TimeoutExpired):
        return False
    return completed.returncode == 0


def _find_tmux_socket(name: str) -> Optional[str]:
    """Locate the tmux socket serving session ``name``.

    tmuxctl runs one server per session on ``tmuxctl-<name>`` under
    ``${TMUX_TMPDIR:-/tmp}/tmux-<uid>``, so the name-derived path is tried
    first and confirmed with ``has-session`` (the file can outlive its
    server). Anything else — a session on the shared ``default`` socket, or
    one created by a different tool — is found by sweeping the socket
    directory newest-first and asking each server whether it owns the name.
    """
    socket_dir = _session_enum.tmux_socket_dir()
    derived = socket_dir / f"{_session_enum.TMUXCTL_SOCKET_PREFIX}{name}"
    if derived.exists() and _tmux_has_session(str(derived), name):
        return str(derived)
    if not socket_dir.is_dir():
        return None
    deadline = time.monotonic() + TMUX_SOCKET_SWEEP_BUDGET_S
    for candidate in _sweep_candidates(socket_dir, derived):
        if time.monotonic() > deadline:
            break
        if _tmux_has_session(str(candidate), name):
            return str(candidate)
    return None


def _sweep_candidates(socket_dir: Path, derived: Path) -> list[Path]:
    """Socket-directory entries to probe, newest first (``derived`` excluded)."""
    entries: list[tuple[float, Path]] = []
    try:
        listing = list(socket_dir.iterdir())
    except OSError:
        return []
    for entry in listing:
        if entry == derived:
            continue
        try:
            if entry.is_dir():
                continue
            mtime = entry.stat().st_mtime
        except OSError:
            continue
        entries.append((mtime, entry))
    entries.sort(key=lambda item: (-item[0], str(item[1])))
    return [entry for _mtime, entry in entries]


def _match_attach_target(
    rows: Sequence[_session_enum.LiveSession], name: str
) -> list[_session_enum.LiveSession]:
    """Resolve ``name`` to candidate rows, most-specific rule first.

    tmux name (exact) beats aplexer display name (exact) beats aplexer id
    (prefix). Returning a list rather than a single row is deliberate: the
    caller distinguishes "nothing matched" from "several matched" and must
    never pick one arbitrarily.
    """
    exact_tmux = [
        row
        for row in rows
        if row.manager == _session_enum.MANAGER_TMUX and row.name == name
    ]
    if exact_tmux:
        return exact_tmux
    exact_aplexer = [
        row
        for row in rows
        if row.manager == _session_enum.MANAGER_APLEXER and row.name == name
    ]
    if exact_aplexer:
        return exact_aplexer
    if len(name) < APLEXER_ID_PREFIX_MIN:
        return []
    return [
        row
        for row in rows
        if row.aplexer_id and row.aplexer_id.startswith(name)
    ]


def _attach_live_rows() -> tuple[list[_session_enum.LiveSession], list[dict[str, str]]]:
    """Enumerate exactly like `sessions list --json` does.

    Attach must resolve against the same name set the user just listed, so
    this reuses the `tmuxctl list` capture + `enumerate_live_sessions` path
    rather than a second, subtly-different enumeration.
    """
    tmuxctl = _run_tmuxctl_capture(["list"])
    returncode = int(tmuxctl.get("returncode", 0))
    ok = returncode == 0
    error = None
    if not ok:
        error = (
            str(tmuxctl.get("stderr") or "").strip()
            or f"`tmuxctl list` exited {returncode}"
        )
    return _session_enum.enumerate_live_sessions(
        tmuxctl_stdout=str(tmuxctl.get("stdout") or "") if ok else None,
        tmuxctl_error=error,
        enrich_tmux=True,
    )


def _describe_candidate(row: _session_enum.LiveSession) -> str:
    if row.manager == _session_enum.MANAGER_APLEXER and row.aplexer_id:
        return f"  {row.name}  ({row.manager} {row.aplexer_id})"
    return f"  {row.name}  ({row.manager})"


@sessions_group.command(
    "attach",
    context_settings={"help_option_names": ["-h", "--help"]},
)
@click.argument("name")
@click.option(
    "--hide-status",
    is_flag=True,
    default=False,
    help=(
        "Turn the tmux status bar off for this session before attaching "
        "(session-scoped `set-option status off`). Ignored for aplexer "
        "sessions, which draw no tmux status bar."
    ),
)
@click.pass_context
def sessions_attach(ctx: click.Context, name: str, hide_status: bool) -> None:
    """Attach to a live session, replacing this process with it.

    NAME is a tmux session name, an aplexer display name
    (`<workspace>:<tag>`), or an aplexer id prefix of at least 8 characters.
    Resolution uses the same enumeration as `sessions list`, so any name that
    listing shows can be attached.

    On success this process is REPLACED by `tmux attach-session` (or
    `a attach`) and never returns. Exit 3 = no such session, 4 = ambiguous,
    5 = tmux session found but its socket could not be located, 127 = the
    attach binary is missing.
    """
    rows, _errors = _attach_live_rows()
    matches = _match_attach_target(rows, name)
    if not matches:
        click.echo(f"no session named {name!r}", err=True)
        ctx.exit(ATTACH_EXIT_NOT_FOUND)
        return
    if len(matches) > 1:
        click.echo(f"ambiguous session name {name!r}; candidates:", err=True)
        for row in matches:
            click.echo(_describe_candidate(row), err=True)
        ctx.exit(ATTACH_EXIT_AMBIGUOUS)
        return

    row = matches[0]
    if row.manager == _session_enum.MANAGER_APLEXER:
        if _resolve_aplexer_binary() is None:
            click.echo(
                "pocketshell: `a` (aplexer) is not installed on this host; "
                f"cannot attach to {row.name!r}.",
                err=True,
            )
            ctx.exit(ATTACH_EXIT_NO_BINARY)
            return
        _exec(["a", "attach", str(row.aplexer_id)])
        return

    if shutil.which("tmux") is None:
        click.echo(
            "pocketshell: `tmux` is not installed on this host; "
            f"cannot attach to {row.name!r}.",
            err=True,
        )
        ctx.exit(ATTACH_EXIT_NO_BINARY)
        return
    socket_path = _find_tmux_socket(row.name)
    if socket_path is None:
        click.echo(
            f"pocketshell: found tmux session {row.name!r} in the listing but "
            "no tmux server socket serves it; it may have just exited.",
            err=True,
        )
        ctx.exit(ATTACH_EXIT_NO_SOCKET)
        return
    target = f"={row.name}"
    if hide_status:
        # Session-scoped and deliberately not restored on detach: the client
        # asking for --hide-status owns its own chrome for that session.
        #
        # The target here is `=name:` (trailing colon), NOT the `=name` used
        # for attach-session: `set-option -t` takes a *pane* target, and
        # tmux 3.4 rejects a bare `=name` there with "no such session"
        # (verified live) — silently leaving the status bar on. The trailing
        # colon makes it "current pane of the window of session =name" while
        # keeping the `=` exact-match on the session name.
        completed = subprocess.run(
            [
                "tmux",
                "-S",
                socket_path,
                "set-option",
                "-t",
                f"={row.name}:",
                "status",
                "off",
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        if completed.returncode != 0:
            # Not fatal — the user asked to attach, and a visible status bar
            # beats refusing to connect. But never fail silently.
            detail = str(completed.stderr or "").strip() or "unknown error"
            click.echo(
                f"pocketshell: could not hide the tmux status bar: {detail}",
                err=True,
            )
    _exec(["tmux", "-S", socket_path, "attach-session", "-t", target])


# ---------------------------------------------------------------------------
# `sessions transcript` — one normalized conversation stream per session
# ---------------------------------------------------------------------------
#
# Output IS JSONL (one aplexer `UnifiedEvent` per line); there is deliberately
# no `--json` flag because there is no other format to switch away from.
#
# Two backends, one wire shape:
#
# - **aplexer row** -> `exec a transcript --json <id>`. aplexer owns the
#   locate/bind/parse for its own sessions; we hand the process over so
#   `--follow` streams and Ctrl-C lands on `a`, not on a middleman.
# - **tmux row** -> `agent_log`'s locate+parse path, re-shaped to the same
#   `UnifiedEvent` envelope by `agent_log.iter_unified_events`. A plain tmux
#   session has no aplexer record, so the conversation is found by the same
#   cwd heuristic `sessions resumable` already uses: the newest AI-CLI
#   conversation recorded in that session's working directory.
#
# The tmux branch is a SANCTIONED INTERIM (D22's hard-cut rule applies to the
# thing it is interim for, not to it): it is deleted outright once every
# session is aplexer-managed. It is not a "legacy fallback" kept for
# compatibility — it is the only reader that exists for a non-aplexer session.

#: NAME matched nothing in the combined tmux + aplexer listing.
_EXIT_SESSION_NOT_FOUND = 3
#: NAME resolved, but no conversation log backs it (mirrors `agent-log`'s 66).
_EXIT_NO_TRANSCRIPT = 66
#: Shortest aplexer-id prefix accepted, matching `a`'s own "unambiguous UUID
#: prefix" contract. Below this a prefix is more likely a typo'd session name.
_APLEXER_ID_PREFIX_MIN = 8


def _live_sessions_for_resolution() -> list[_session_enum.LiveSession]:
    """The combined tmux + aplexer row set `transcript` resolves NAME against.

    ``enrich_tmux=True`` because the tmux branch needs each session's
    ``workspace`` (its tmux server is the only thing that knows it) to find the
    conversation log. Enumeration ``errors`` are dropped here: a NAME that does
    not resolve is reported as not-found, and the caller has ``sessions list
    --json`` if it wants the per-backend error detail.
    """
    tmuxctl = _run_tmuxctl_capture(["list"])
    ok = int(tmuxctl.get("returncode", 0)) == 0
    sessions, _errors = _session_enum.enumerate_live_sessions(
        tmuxctl_stdout=str(tmuxctl.get("stdout") or "") if ok else None,
        enrich_tmux=True,
    )
    return sessions


def resolve_live_session(
    sessions: Sequence[_session_enum.LiveSession], name: str
) -> tuple[Optional[_session_enum.LiveSession], Optional[str]]:
    """Resolve NAME to one row. Returns ``(row, error_message)``.

    Match order, most-specific first:

    1. exact tmux session name,
    2. exact aplexer display name (``<workspace-basename>:<tag>``),
    3. unambiguous aplexer-id prefix of at least
       :data:`_APLEXER_ID_PREFIX_MIN` characters.

    An ambiguous prefix is an error rather than a silent first-match: picking
    one of two real sessions for the user is worse than telling them to be
    specific.
    """
    for row in sessions:
        if row.manager == _session_enum.MANAGER_TMUX and row.name == name:
            return row, None
    for row in sessions:
        if row.manager == _session_enum.MANAGER_APLEXER and row.name == name:
            return row, None
    if len(name) >= _APLEXER_ID_PREFIX_MIN:
        matches = [
            row
            for row in sessions
            if row.aplexer_id and row.aplexer_id.startswith(name)
        ]
        if len(matches) == 1:
            return matches[0], None
        if len(matches) > 1:
            ids = ", ".join(sorted(str(row.aplexer_id) for row in matches))
            return None, (
                f"pocketshell: `{name}` is an ambiguous aplexer session prefix "
                f"({ids}); use more characters."
            )
    return None, (
        f"pocketshell: no live session matches `{name}`. "
        "Run `pocketshell sessions list` to see what is running."
    )


def aplexer_transcript_argv(
    binary: str,
    aplexer_id: str,
    *,
    follow: bool = False,
    last: Optional[int] = None,
) -> list[str]:
    """Build ``a transcript --json [--last N] [--follow] <id>``.

    ``--json`` is mandatory: `a transcript`'s default is a human digest, and
    this command's contract is UnifiedEvent JSONL. Flag names verified against
    `a transcript --help` (aplexer 0.1.x) on the dev box.
    """
    argv = [binary, "transcript", "--json"]
    if last is not None:
        argv.extend(["--last", str(last)])
    if follow:
        argv.append("--follow")
    argv.append(aplexer_id)
    return argv


def _exec_argv(argv: Sequence[str]) -> None:
    """Replace this process with ``argv``. Seam for the unit suite."""
    os.execvp(argv[0], list(argv))


def _emit_unified_events(events: Any) -> None:
    """Write UnifiedEvents as JSONL, one per line, flushed as they arrive.

    Compact separators + ``ensure_ascii=False`` match `a transcript --json`'s
    own encoding byte-for-byte. The flush matters for ``--follow``: an
    unflushed pipe would buffer a live tail into silence.
    """
    import json as _json

    for event in events:
        sys.stdout.write(_json.dumps(event, separators=(",", ":"), ensure_ascii=False))
        sys.stdout.write("\n")
        sys.stdout.flush()


def _tmux_row_conversation(
    row: _session_enum.LiveSession,
) -> Optional[_resume.ResumableSession]:
    """Newest AI-CLI conversation recorded in this tmux session's workspace.

    Same discovery pass `sessions resumable` runs, filtered to the session's
    own cwd and sorted newest-first — the tmux equivalent of aplexer's
    cwd+recency locate heuristic. ``None`` when the workspace is unknown (the
    session's tmux server did not answer) or nothing was recorded there.
    """
    if not row.workspace:
        return None
    candidates = _resume.merge_sessions(
        sessions=_resume.discover_all(), cwd=row.workspace
    )
    return candidates[0] if candidates else None


@sessions_group.command(
    "transcript",
    context_settings={"help_option_names": ["-h", "--help"]},
)
@click.argument("name")
@click.option(
    "--follow",
    "follow",
    is_flag=True,
    default=False,
    help="After the initial page, keep streaming events as the agent writes them.",
)
@click.option(
    "--last",
    "last",
    type=click.IntRange(min=0),
    default=None,
    help="Emit only the last N events (sequence numbers stay absolute).",
)
@click.pass_context
def sessions_transcript(
    ctx: click.Context,
    name: str,
    follow: bool,
    last: Optional[int],
) -> None:
    """Print a session's conversation as UnifiedEvent JSONL, one event per line.

    NAME is an exact tmux session name, an exact aplexer display name
    (`<project>:<tag>`), or an unambiguous aplexer session-id prefix.

    An aplexer-managed session is served by `a transcript --json`; a plain tmux
    session is served from the agent CLI's own conversation log in that
    session's working directory, re-shaped to the identical event envelope.

    Exit codes:

    - 0   -> success.
    - 3   -> NAME matched no live session (or matched ambiguously).
    - 66  -> the session resolved but no conversation log backs it.
    - 127 -> the backend binary the matched row needs is missing.
    """
    sessions = _live_sessions_for_resolution()
    row, error = resolve_live_session(sessions, name)
    if row is None:
        click.echo(error, err=True)
        ctx.exit(_EXIT_SESSION_NOT_FOUND)
        return

    if row.manager == _session_enum.MANAGER_APLEXER and row.aplexer_id:
        binary = _aplexer.which_a()
        if binary is None:
            click.echo(
                "pocketshell: `a` (aplexer) is not on PATH, but "
                f"`{name}` is an aplexer-managed session.",
                err=True,
            )
            ctx.exit(127)
            return
        _exec_argv(
            aplexer_transcript_argv(
                binary, row.aplexer_id, follow=follow, last=last
            )
        )
        return

    conversation = _tmux_row_conversation(row)
    if conversation is None:
        click.echo(
            f"pocketshell: no agent conversation log found for tmux session "
            f"`{row.name}`"
            + (f" (workspace {row.workspace})." if row.workspace else "."),
            err=True,
        )
        ctx.exit(_EXIT_NO_TRANSCRIPT)
        return

    metadata = {
        "session_id": conversation.session_id,
        "workspace": conversation.cwd,
        "tag": row.name,
    }
    try:
        _emit_unified_events(
            _agent_log.iter_unified_events(
                engine=conversation.engine,
                session=conversation.session_id,
                cwd=conversation.cwd,
                last=last,
                metadata=metadata,
                follow=follow,
            )
        )
    except _agent_log.AgentLogNotFoundError as exc:
        click.echo(f"pocketshell: {exc}", err=True)
        ctx.exit(_EXIT_NO_TRANSCRIPT)
    except KeyboardInterrupt:  # `--follow` is a long-lived stream; Ctrl-C ends it
        return
