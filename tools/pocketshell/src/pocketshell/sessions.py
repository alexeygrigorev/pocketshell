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

import os
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Optional, Sequence

import click

from . import aplexer as _aplexer
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
