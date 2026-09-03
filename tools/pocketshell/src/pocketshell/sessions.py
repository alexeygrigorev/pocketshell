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
from typing import Any, Optional, Sequence

import click

from . import agent_log as _agent_log
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
