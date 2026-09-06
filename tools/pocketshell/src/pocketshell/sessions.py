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
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Optional, Sequence

import click

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


def _aplexer_records_holding(
    payload: Any, *, workspace: str, tag: str
) -> list[Mapping[str, Any]]:
    """Every snapshot record holding the ``workspace`` + ``tag`` pair.

    aplexer keys a session by exactly that pair, so it is the identity to
    match on — and, critically, a DEAD record keeps holding it: `a start`
    answers "workspace+tag already belongs to session <id>" and exits 1
    (verified against aplexer 0.1.3). Liveness is the caller's split.
    """
    if not isinstance(payload, list):
        return []
    target = os.path.realpath(workspace)
    held: list[Mapping[str, Any]] = []
    for raw in payload:
        if not isinstance(raw, Mapping):
            continue
        if str(raw.get("tag") or "") != tag:
            continue
        raw_workspace = raw.get("workspace") or raw.get("cwd") or ""
        if os.path.realpath(str(raw_workspace)) != target:
            continue
        held.append(raw)
    return held


def _aplexer_existing_record(
    payload: Any, *, workspace: str, tag: str
) -> Optional[Mapping[str, Any]]:
    """Find a LIVE aplexer session already holding ``workspace`` + ``tag``.

    A finished session does not count: `a start` reclaims that pair once the
    record is gone, i.e. it really does create. Liveness is
    :func:`session_enum.aplexer_record_is_alive` — the one implementation of
    that rule (issue #2554). The local copy this replaced only skipped
    ``exited``/``failed``, so the ``phase: running`` / ``worker_alive:
    false`` zombie was reported as an already-existing session and the app
    attached to a corpse.
    """
    for raw in _aplexer_records_holding(payload, workspace=workspace, tag=tag):
        if _session_enum.aplexer_record_is_alive(raw):
            return raw
    return None


def _process_alive(pid: int) -> bool:
    """Whether ``pid`` names a live process.

    The same pessimistic check aplexer's ``process_alive`` makes: existence,
    not identity. A recycled pid reads as alive, which errs toward NOT
    destroying a record — the safe direction here.
    """
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        # Exists, owned by someone else.
        return True
    except OSError:
        return True
    return True


def _aplexer_live_workload_pid(raw: Mapping[str, Any]) -> Optional[int]:
    """The record's ``workload_pid`` if that process is still running."""
    try:
        pid = int(raw["workload_pid"])
    except (KeyError, TypeError, ValueError):
        return None
    if pid <= 0:
        return None
    return pid if _process_alive(pid) else None


@dataclass(frozen=True)
class _BlockerReap:
    """Outcome of clearing the dead records holding one workspace+tag."""

    #: ``(id, workload_pid)`` for records NOT reaped because their workload
    #: is still running. Reaping one destroys the only handle onto it.
    workload_alive: tuple[tuple[str, int], ...] = ()
    #: Records that were reaped but whose containment aplexer could not
    #: prove empty — something may have outlived them.
    may_survive: tuple[str, ...] = ()
    #: Dead records the reap could not clear at all.
    unreaped: tuple[str, ...] = ()


def _reap_aplexer_blockers(
    payload: Any, *, aplexer_path: str, workspace: str, tag: str
) -> _BlockerReap:
    """Reap the dead records holding ``workspace`` + ``tag``.

    Called only after :func:`_aplexer_existing_record` came back empty, and
    each record is re-checked with the same predicate here, so a live
    session is never a candidate however this is called.

    A record whose ``workload_pid`` is still ALIVE is deliberately left
    alone: ``a forget --force`` is documented as forgetting a record
    "without claiming its workloads stopped", so reaping one destroys the
    only handle onto a running process. aplexer's own ``a prune`` refuses
    exactly this case (``a.rs::cmd_prune`` retains any record with a live
    ``workload_pid``); this mirrors that rule rather than inventing a
    laxer one.

    Reaping still discards the record's durable history — the same trade
    `sessions kill` makes, and the user is explicitly asking for this exact
    pair back.
    """
    workload_alive: list[tuple[str, int]] = []
    may_survive: list[str] = []
    unreaped: list[str] = []
    for raw in _aplexer_records_holding(payload, workspace=workspace, tag=tag):
        ident = str(raw.get("id") or "").strip()
        if not ident:
            continue
        if _session_enum.aplexer_record_is_alive(raw):
            continue
        pid = _aplexer_live_workload_pid(raw)
        if pid is not None:
            workload_alive.append((ident, pid))
            continue
        outcome = _reap_aplexer_record(aplexer_path, ident)
        if not outcome.reaped:
            unreaped.append(ident)
        elif outcome.workload_may_survive:
            may_survive.append(ident)
    return _BlockerReap(
        workload_alive=tuple(workload_alive),
        may_survive=tuple(may_survive),
        unreaped=tuple(unreaped),
    )


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
    resolution = _aplexer.resolve_a()
    aplexer_path = resolution.path
    if aplexer_path is None:
        # #2543: this used to say "is not installed", which was actively
        # misleading — the real cause was a RESOLUTION failure (a correctly
        # installed `a` outside the app's non-interactive SSH PATH). aplexer
        # now ships WITH this CLI as a pinned dependency, so an unresolvable
        # `a` is a packaging-integrity problem, and the message names every
        # candidate that was checked.
        raise _CreateError(
            _aplexer_unresolved_message(
                resolution,
                action=(
                    "the backend routing selected it, so `sessions create` "
                    "cannot run (or set [backends] in "
                    "~/.config/pocketshell/config.toml back to tmux)"
                ),
            ),
            exit_code=127,
        )
    workspace = cwd or os.getcwd()

    snapshot = _aplexer_snapshot()
    existing = _aplexer_existing_record(snapshot, workspace=workspace, tag=name)
    if existing is not None:
        return {
            "name": _session_enum.aplexer_display_name(existing) or name,
            "manager": _session_enum.MANAGER_APLEXER,
            "id": str(existing.get("id") or "") or None,
            "created": False,
        }

    # Nothing LIVE holds the pair — but a dead record still holds it hostage:
    # aplexer refuses `a start` with "workspace+tag already belongs to session
    # <id>" for a corpse just as firmly as for a running session, and since
    # #2554 that corpse is not even in the listing for the user to see. Clear
    # it first (issue #2554: "can't create an agent session").
    blockers = _reap_aplexer_blockers(
        snapshot, aplexer_path=aplexer_path, workspace=workspace, tag=name
    )
    if blockers.workload_alive:
        # Refuse rather than orphan. `a forget --force` would reclaim the
        # pair and report a clean create while an untracked process kept
        # running with nothing left pointing at it. aplexer's own `a prune`
        # refuses this exact case; `a kill` cannot help either — on a record
        # whose worker died it answers "no authoritative containment
        # locator" and changes nothing (both verified against `a 0.1.3`).
        detail = "; ".join(
            f"record {ident} still has workload pid {pid} running"
            for ident, pid in blockers.workload_alive
        )
        manual = " ".join(
            f"`kill {pid}` then `a forget --force {ident}`"
            for ident, pid in blockers.workload_alive
        )
        raise _CreateError(
            f"pocketshell: cannot create {name!r} in {workspace!r}: a dead "
            f"aplexer record still holds that workspace+tag and its workload "
            f"is still running ({detail}); reclaiming the pair would leave "
            f"that process untracked. Stop it first — {manual} — and retry."
        )

    argv = aplexer_start_argv(
        aplexer_path=aplexer_path,
        workspace=workspace,
        tag=name,
        engine=engine,
        profile=profile,
    )
    code, stdout, stderr = _run_aplexer(argv)
    if code != 0:
        detail = stderr.strip() or stdout.strip() or "no output"
        if blockers.unreaped:
            # Never relay aplexer's bare "rename it or choose a different
            # tag": the session it names is a corpse the listing no longer
            # shows, so that advice sends the user hunting for something
            # invisible. Name the record and the command that clears it.
            stuck = ", ".join(blockers.unreaped)
            manual = " ".join(
                f"`a forget --force {ident}`" for ident in blockers.unreaped
            )
            raise _CreateError(
                f"pocketshell: cannot create {name!r} in {workspace!r}: a dead "
                f"aplexer record ({stuck}) still holds that workspace+tag "
                f"and could not be reaped; run {manual} and retry "
                f"(`a start` exited {code}: {detail})",
                exit_code=code,
            )
        raise _CreateError(
            f"pocketshell: `a start --tag {name}` exited {code}: {detail}",
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
    for ident in blockers.may_survive:
        # aplexer could not prove the reclaimed record's containment was
        # empty. The create succeeded — the pair IS ours now — but something
        # may have outlived it (a setsid'd grandchild survives the worker),
        # and this is the last moment anyone knows the id it belonged to.
        click.echo(_workload_survivor_warning(name, ident), err=True)
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


def _resolve_aplexer() -> "_aplexer.AplexerResolution":
    """Resolve the ``a`` CLI for attach/kill: ``APLEXER_BIN``, else bundled.

    Wrapped rather than calling :func:`pocketshell.aplexer.resolve_a` inline so
    the attach-time availability check has its own monkeypatch seam: patching
    ``resolve_a`` itself would also silence the enumeration probe that produced
    the aplexer rows in the first place. Returns the full report, because the
    failure message names every candidate it tried (#2543), and the callers
    exec/run the RESOLVED path — never a bare ``a`` that execvp would look up
    on PATH.
    """
    return _aplexer.resolve_a()


def _aplexer_unresolved_message(
    resolution: "_aplexer.AplexerResolution", *, action: str
) -> str:
    """Explain an unresolvable ``a`` by NAMING the candidates (issue #2543).

    The old wording — "`a` (aplexer) is not installed on this host" — was the
    inverse of the truth in the reported failure: aplexer WAS installed, just
    not where a bare PATH lookup could see it. aplexer now ships WITH this CLI
    as a pinned dependency, so an unresolvable `a` is a packaging-integrity
    problem with a concrete fix, and the message says which paths were checked.
    """
    return (
        "pocketshell: could not resolve the `a` (aplexer) binary; "
        f"{action}. aplexer ships with the pocketshell CLI, so reinstalling "
        "normally fixes this (`uv tool install --force pocketshell`); "
        "otherwise set APLEXER_BIN. Tried: " + "; ".join(resolution.tried)
    )


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


def _dead_row_detail(row: _session_enum.LiveSession) -> str:
    """One clause saying what actually happened to a dead aplexer record."""
    phase = row.phase or "unknown"
    if phase == "exited":
        return f"it has already exited (aplexer phase: {phase})"
    if phase == "failed":
        return f"it failed to start (aplexer phase: {phase})"
    # The zombie: the record still claims a live phase, but nothing serves
    # its control socket, so `a attach` cannot succeed.
    return (
        f"it has no live worker (aplexer phase: {phase}, worker_alive: false)"
    )


def _not_attachable_message(name: str) -> str:
    """Why NAME cannot be attached — naming the state when it is a corpse.

    Issue #2554: the listing no longer offers dead aplexer records, so the
    only way to reach one is a name the caller kept from an older listing.
    Passing it to `a attach` anyway produces "session ... has already
    exited" on aplexer's stderr and exit 1, which the phone rendered as a
    bare `Session "..." ended (exit 1).` — a crash-shaped message for an
    ordinary "this is gone". A genuinely unknown name keeps the plain
    not-found wording.
    """
    dead = _session_enum.dead_sessions_from_aplexer_snapshot(_aplexer_snapshot())
    for row in _match_attach_target(dead, name):
        return (
            f"pocketshell: aplexer session {row.name!r} is no longer running: "
            f"{_dead_row_detail(row)}. It cannot be attached; "
            "run `pocketshell sessions list` for the live ones."
        )
    return f"no session named {name!r}"


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
        click.echo(_not_attachable_message(name), err=True)
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
        # Resolve ONCE and exec that exact path (#2543). A bare "a" here would
        # be an execvp PATH lookup — the separate-install mode this CLI no
        # longer supports, and a way to run a different copy than the one the
        # availability check just approved.
        resolution = _resolve_aplexer()
        if resolution.path is None:
            click.echo(
                _aplexer_unresolved_message(
                    resolution, action=f"cannot attach to {row.name!r}"
                ),
                err=True,
            )
            ctx.exit(ATTACH_EXIT_NO_BINARY)
            return
        _exec([resolution.path, "attach", str(row.aplexer_id)])
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
# `sessions kill` — same name resolution as attach, then kill that one session
# ---------------------------------------------------------------------------
#
# Desktop kills with `tmux -S <socket> kill-session -t '=NAME'`. The `=` is
# tmux's exact-match form: without it, `-t api` will happily destroy
# `api-staging` once `api` itself is gone. `tmux kill-server` is never used
# — that would wipe every session on the socket, including ones the user did
# not name.
#
# Exit codes match attach so a client can branch the same way:
#   3   no session by that name
#   4   ambiguous — several sessions match
#   5   matched a tmux session but its server socket could not be located
#   127 the `tmux` / `a` binary needed to kill is not installed

KILL_SCHEMA_VERSION = CREATE_SCHEMA_VERSION


def _emit_kill_failure(
    ctx: click.Context, message: str, *, exit_code: int, as_json: bool
) -> None:
    """Report a failed kill: JSON error envelope on stdout, else stderr."""
    if as_json:
        click.echo(
            json.dumps({"schema": KILL_SCHEMA_VERSION, "error": message}, indent=2)
        )
    else:
        click.echo(message, err=True)
    ctx.exit(exit_code if exit_code else 1)


def _run_session_kill(argv: list[str]) -> subprocess.CompletedProcess[str]:
    """Run one kill/reap argv (``tmux kill-session``, ``a kill``, ``a forget``).

    Never ``tmux kill-server``.
    """
    return subprocess.run(
        argv,
        check=False,
        capture_output=True,
        text=True,
        timeout=_TMUX_TIMEOUT_S,
    )


# ---------------------------------------------------------------------------
# reaping an aplexer record after a kill (issue #2554)
# ---------------------------------------------------------------------------
#
# `a kill` signals the workload; it does NOT remove the session record.
# aplexer keeps it at `phase: exited` indefinitely, and the tree used to
# render that leftover as an ordinary, tappable row whose attach answered
# "session ... has already exited" and exited 1. Stop therefore left a corpse
# behind every single time.
#
# Why `a forget --force <id>` and not `a prune` (both measured on the dev box
# against the shipped aplexer 0.1.3):
#
#   * `a prune` only removes records aplexer considers reclaimable. A record
#     whose worker died without recording an exit (`phase: running`,
#     `worker_alive: false`) is NOT reclaimable: prune answered
#     `{"removed": [], "retained_count": 2}` and left it in place. That is
#     the same zombie class this fix filters out of the listing, so a
#     prune-based reap would be structurally unable to clean up after itself.
#   * `a prune` is host-wide. Reaping one stopped session must not also
#     delete the durable history of every other dead session on the box.
#   * `a forget --force <id>` is targeted and unconditional once the worker
#     is gone, and its refusal is precise enough to retry on.
#
# Both paths share one race: immediately after `a kill` the record is
# terminal but its worker is still winding down, and aplexer refuses
# ("session ... still has a live worker; refusing to forget it"). A single
# fire-and-forget attempt loses that window and silently leaves the row. So
# the reap retries until the worker is gone or the budget runs out.

#: Substring of aplexer's refusal while the worker is still winding down
#: (aplexer/src/api.rs::forget_session).
_REAP_WORKER_STILL_LIVE = "still has a live worker"
#: Substring of aplexer's answer when the record is already gone. The goal
#: state, so it counts as reaped rather than as a failure.
_REAP_ALREADY_GONE = "no matching session"
_REAP_MAX_ATTEMPTS = 30
_REAP_POLL_S = 0.1
_REAP_BUDGET_S = 3.0


def _reap_wait() -> None:
    """Pause between reap attempts.

    Its own seam so the unit suite can exercise the retry window at full
    speed instead of wall-clock.
    """
    time.sleep(_REAP_POLL_S)


@dataclass(frozen=True)
class _ReapOutcome:
    """What one ``a forget --force`` attempt actually achieved.

    ``workload_may_survive`` is aplexer's OWN verdict, read off
    ``a --json forget``'s payload rather than guessed: ``a forget`` never
    claims the workload stopped, and reports
    ``containment_proven_empty: false`` when processes may have outlived the
    record. Swallowing that (it goes to stderr in human mode) is how a reap
    can silently orphan a running process.
    """

    reaped: bool
    workload_may_survive: bool = False


def _reap_aplexer_record(aplexer_path: str, aplexer_id: str) -> _ReapOutcome:
    """Remove the aplexer record for a session that was just killed.

    Best effort by design: the workload is already dead, so a stuck reap must
    never turn a successful kill into a reported failure — it only means the
    row survives until the next `a prune`/manual `a forget`.

    ``--json`` is passed so the containment verdict comes back as data on
    stdout (the human warning goes to stderr) instead of having to be
    string-matched.
    """
    deadline = time.monotonic() + _REAP_BUDGET_S
    argv = [aplexer_path, "--json", "forget", "--force", str(aplexer_id)]
    for attempt in range(_REAP_MAX_ATTEMPTS):
        try:
            completed = _run_session_kill(argv)
        except (subprocess.TimeoutExpired, OSError):
            return _ReapOutcome(reaped=False)
        if completed.returncode == 0:
            return _ReapOutcome(
                reaped=True,
                workload_may_survive=_reap_workload_may_survive(completed.stdout),
            )
        detail = f"{completed.stderr or ''}{completed.stdout or ''}"
        if _REAP_ALREADY_GONE in detail:
            # A newer aplexer may reap inside `a kill`; nothing left to do.
            return _ReapOutcome(reaped=True)
        if _REAP_WORKER_STILL_LIVE not in detail:
            # Any other refusal is not something waiting will fix.
            return _ReapOutcome(reaped=False)
        if attempt + 1 >= _REAP_MAX_ATTEMPTS or time.monotonic() >= deadline:
            return _ReapOutcome(reaped=False)
        _reap_wait()
    return _ReapOutcome(reaped=False)


def _reap_workload_may_survive(stdout: Optional[str]) -> bool:
    """Read ``workload_may_survive`` off ``a --json forget``'s payload.

    Unreadable output means "no claim made", not "may survive": a warning
    nobody can act on, fired on every ordinary Stop, stops being read.
    """
    try:
        payload = json.loads(stdout or "")
    except ValueError:
        return False
    return isinstance(payload, Mapping) and bool(payload.get("workload_may_survive"))


def _workload_survivor_warning(name: str, aplexer_id: str) -> str:
    return (
        f"pocketshell: reclaimed {name!r} from aplexer record {aplexer_id}, but "
        "aplexer could not prove its containment was empty — its workload "
        "processes may still be running and are no longer tracked."
    )


@sessions_group.command(
    "kill",
    context_settings={"help_option_names": ["-h", "--help"]},
)
@click.argument("name")
@click.option(
    "--json",
    "as_json",
    is_flag=True,
    default=False,
    help=(
        "Emit the schema-2 kill envelope "
        '{"schema","name","manager","id","killed","reaped"} on stdout.'
    ),
)
@click.pass_context
def sessions_kill(ctx: click.Context, name: str, as_json: bool) -> None:
    """Kill a live session by exact name.

    NAME is a tmux session name, an aplexer display name
    (`<workspace>:<tag>`), or an aplexer id prefix of at least 8 characters.
    Resolution uses the same enumeration and matching rules as
    `sessions attach`, so any name that listing shows can be killed — and a
    prefix cannot silently destroy a neighbour.

    tmux sessions are killed with `tmux -S <socket> kill-session -t '=NAME'`
    on the socket `_find_tmux_socket` located. aplexer sessions are killed
    with `a kill <id>` and then REAPED with `a forget --force <id>`, because
    `a kill` alone leaves the record behind and the tree would keep offering
    a dead row (#2554). This never runs `tmux kill-server`.

    Exit 3 = no such session, 4 = ambiguous, 5 = tmux session found but its
    socket could not be located, 127 = the kill binary is missing.
    """
    rows, _errors = _attach_live_rows()
    matches = _match_attach_target(rows, name)
    if not matches:
        _emit_kill_failure(
            ctx,
            f"no session named {name!r}",
            exit_code=ATTACH_EXIT_NOT_FOUND,
            as_json=as_json,
        )
        return
    if len(matches) > 1:
        if as_json:
            _emit_kill_failure(
                ctx,
                f"ambiguous session name {name!r}",
                exit_code=ATTACH_EXIT_AMBIGUOUS,
                as_json=True,
            )
            return
        click.echo(f"ambiguous session name {name!r}; candidates:", err=True)
        for row in matches:
            click.echo(_describe_candidate(row), err=True)
        ctx.exit(ATTACH_EXIT_AMBIGUOUS)
        return

    row = matches[0]
    if row.manager == _session_enum.MANAGER_APLEXER:
        resolution = _resolve_aplexer()
        if resolution.path is None:
            _emit_kill_failure(
                ctx,
                _aplexer_unresolved_message(
                    resolution, action=f"cannot kill {row.name!r}"
                ),
                exit_code=ATTACH_EXIT_NO_BINARY,
                as_json=as_json,
            )
            return
        aplexer_id = row.aplexer_id
        if not aplexer_id:
            _emit_kill_failure(
                ctx,
                f"pocketshell: aplexer session {row.name!r} has no id; cannot kill it.",
                exit_code=ATTACH_EXIT_NOT_FOUND,
                as_json=as_json,
            )
            return
        # The resolved path, not a bare "a" on PATH (#2543).
        argv = [resolution.path, "kill", str(aplexer_id)]
        try:
            completed = _run_session_kill(argv)
        except subprocess.TimeoutExpired:
            _emit_kill_failure(
                ctx,
                f"pocketshell: `a kill {aplexer_id}` timed out.",
                exit_code=1,
                as_json=as_json,
            )
            return
        except OSError as exc:
            _emit_kill_failure(
                ctx,
                f"pocketshell: `{resolution.path} kill` could not run; "
                f"cannot kill {row.name!r}: {exc}",
                exit_code=ATTACH_EXIT_NO_BINARY,
                as_json=as_json,
            )
            return
        if completed.returncode != 0:
            detail = str(completed.stderr or "").strip() or f"exit {completed.returncode}"
            _emit_kill_failure(
                ctx,
                f"pocketshell: could not kill {row.name!r}: {detail}",
                exit_code=completed.returncode,
                as_json=as_json,
            )
            return
        # The kill only signalled the workload; the record still has to go,
        # or the very next listing hands the tree a dead, tappable row
        # (issue #2554).
        outcome = _reap_aplexer_record(resolution.path, str(aplexer_id))
        reaped = outcome.reaped
        if outcome.workload_may_survive:
            click.echo(
                _workload_survivor_warning(row.name, str(aplexer_id)), err=True
            )
        if not reaped:
            # Never fatal — the session IS dead, only its record survives.
            # Said out loud so a lingering row is explained rather than
            # looking like the kill silently failed.
            click.echo(
                f"pocketshell: killed {row.name!r}, but its aplexer record "
                f"could not be reaped; run `a forget --force {aplexer_id}` "
                "if it keeps showing up.",
                err=True,
            )
        if as_json:
            click.echo(
                json.dumps(
                    {
                        "schema": KILL_SCHEMA_VERSION,
                        "name": row.name,
                        "manager": row.manager,
                        "id": aplexer_id,
                        "killed": True,
                        "reaped": reaped,
                    },
                    indent=2,
                )
            )
        return

    if shutil.which("tmux") is None:
        _emit_kill_failure(
            ctx,
            "pocketshell: `tmux` is not installed on this host; "
            f"cannot kill {row.name!r}.",
            exit_code=ATTACH_EXIT_NO_BINARY,
            as_json=as_json,
        )
        return
    socket_path = _find_tmux_socket(row.name)
    if socket_path is None:
        _emit_kill_failure(
            ctx,
            f"pocketshell: found tmux session {row.name!r} in the listing but "
            "no tmux server socket serves it; it may have just exited.",
            exit_code=ATTACH_EXIT_NO_SOCKET,
            as_json=as_json,
        )
        return
    # Exact `=` match on the listing's socket. A bare `-t NAME` fails open
    # (kills a prefix neighbour and reports success); `=` fails closed.
    argv = ["tmux", "-S", socket_path, "kill-session", "-t", f"={row.name}"]
    try:
        completed = _run_session_kill(argv)
    except subprocess.TimeoutExpired:
        _emit_kill_failure(
            ctx,
            f"pocketshell: `tmux kill-session` for {row.name!r} timed out.",
            exit_code=1,
            as_json=as_json,
        )
        return
    except OSError as exc:
        _emit_kill_failure(
            ctx,
            "pocketshell: `tmux` is not installed on this host; "
            f"cannot kill {row.name!r}: {exc}",
            exit_code=ATTACH_EXIT_NO_BINARY,
            as_json=as_json,
        )
        return
    if completed.returncode != 0:
        detail = str(completed.stderr or "").strip() or f"exit {completed.returncode}"
        _emit_kill_failure(
            ctx,
            f"pocketshell: could not kill {row.name!r}: {detail}",
            exit_code=completed.returncode,
            as_json=as_json,
        )
        return
    if as_json:
        click.echo(
            json.dumps(
                {
                    "schema": KILL_SCHEMA_VERSION,
                    "name": row.name,
                    "manager": row.manager,
                    "id": row.aplexer_id,
                    "killed": True,
                    # tmux keeps no record of a killed session: kill-session
                    # IS the removal, so the row is always gone (#2554).
                    "reaped": True,
                },
                indent=2,
            )
        )
