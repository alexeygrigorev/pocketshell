"""The aplexer-only ``pocketshell sessions`` command group."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from dataclasses import dataclass
from typing import Any, Mapping, Optional, Sequence

import click

from . import aplexer as _aplexer
from . import memcap as _memcap
from . import session_enum as _session_enum


CREATE_SCHEMA_VERSION = _session_enum.SCHEMA_VERSION
KILL_SCHEMA_VERSION = CREATE_SCHEMA_VERSION

_APLEXER_START_TIMEOUT_S = 20.0
_SESSION_COMMAND_TIMEOUT_S = 5.0


def _emit_envelope(ctx: click.Context, envelope: Mapping[str, Any]) -> None:
    if envelope.get("stdout"):
        sys.stdout.write(str(envelope["stdout"]))
    if envelope.get("stderr"):
        sys.stderr.write(str(envelope["stderr"]))
    exit_code = int(envelope.get("returncode", 0))
    if exit_code:
        ctx.exit(exit_code)


def _is_schema3_list_envelope(value: Any) -> bool:
    """Validate a daemon ``sessions.list`` reply at the current schema."""
    from pocketshell import daemon as _daemon

    if not _daemon.is_command_envelope(value):
        return False
    try:
        payload = json.loads(str(value.get("stdout") or ""))
    except ValueError:
        return False
    return (
        isinstance(payload, dict)
        and payload.get("schema") == CREATE_SCHEMA_VERSION
        and isinstance(payload.get("sessions"), list)
        and isinstance(payload.get("errors"), list)
    )


def _try_daemon_sessions_list(*, as_json: bool = False) -> Optional[dict[str, Any]]:
    """Use the shared daemon boundary when one is already running."""
    from pocketshell import daemon as _daemon

    return _daemon.try_call(
        "sessions.list",
        params={"as_json": as_json},
        socket_path=_daemon.resolve_socket_path(),
        timeout=5.0,
        result_validator=_is_schema3_list_envelope if as_json else _daemon.is_command_envelope,
    )


def _list_envelope(*, as_json: bool) -> dict[str, Any]:
    sessions, errors = _session_enum.enumerate_live_sessions()
    if as_json:
        stdout = json.dumps(_session_enum.json_payload(sessions, errors), indent=2) + "\n"
    else:
        stdout = _session_enum.format_aplexer_table(sessions)

    if errors:
        detail = "; ".join(str(error.get("message") or "session enumeration failed") for error in errors)
        return {
            "stdout": stdout,
            "stderr": f"pocketshell: {detail}\n",
            "returncode": 127,
        }
    return {"stdout": stdout, "stderr": "", "returncode": 0}


def daemon_handler_list(params: dict[str, Any]) -> dict[str, Any]:
    """JSON-RPC handler for the aplexer-only session listing."""
    return _list_envelope(as_json=bool(params.get("as_json")))


@click.group(
    name="sessions",
    context_settings={"help_option_names": ["-h", "--help"]},
    help="List, create, attach to, and stop aplexer sessions on the host.",
)
def sessions_group() -> None:
    """Session lifecycle commands backed by aplexer."""


@sessions_group.command("list", context_settings={"help_option_names": ["-h", "--help"]})
@click.option(
    "--json",
    "as_json",
    is_flag=True,
    default=False,
    help="Emit the schema-3 session list contract.",
)
@click.pass_context
def sessions_list(ctx: click.Context, as_json: bool) -> None:
    """List live aplexer sessions."""
    envelope = _try_daemon_sessions_list(as_json=as_json)
    if envelope is None:
        envelope = _list_envelope(as_json=as_json)
    _emit_envelope(ctx, envelope)


class _CreateError(Exception):
    """A create failure with the exit code the CLI should return."""

    def __init__(self, message: str, *, exit_code: int = 1) -> None:
        super().__init__(message)
        self.message = message
        self.exit_code = exit_code


def _resolve_aplexer() -> "_aplexer.AplexerResolution":
    """Resolve the bundled ``a`` executable once for an operation."""
    return _aplexer.resolve_a()


def _aplexer_unresolved_message(
    resolution: "_aplexer.AplexerResolution", *, action: str
) -> str:
    return (
        "pocketshell: could not resolve the bundled `a` (aplexer) binary; "
        f"{action}. Reinstall the pocketshell CLI "
        "(`uv tool install --force pocketshell`) or set APLEXER_BIN. "
        "Tried: " + "; ".join(resolution.tried)
    )


def _run_aplexer(argv: Sequence[str]) -> tuple[int, str, str]:
    """Run one aplexer command and retain its diagnostic output."""
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


def _run_session_command(argv: Sequence[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        list(argv),
        check=False,
        capture_output=True,
        text=True,
        timeout=_SESSION_COMMAND_TIMEOUT_S,
    )


def _aplexer_snapshot() -> Any:
    payload = _aplexer.run_json(["snapshot"], feature="sessions")
    if payload is None:
        payload = _aplexer.run_json(["list"], feature="sessions")
    return payload


def aplexer_start_argv(
    *,
    aplexer_path: str,
    workspace: str,
    tag: str,
    engine: Optional[str],
    profile: Optional[str],
    memory_bytes: Optional[int],
) -> list[str]:
    """Build the detached ``a --json start`` invocation."""
    argv = [aplexer_path, "--json", "start", "--workspace", workspace, "--tag", tag]
    if engine:
        argv.extend(["--engine", engine])
    if profile:
        argv.extend(["--profile", profile])
    if memory_bytes is not None:
        argv.extend(["--memory", str(memory_bytes)])
    return argv


def _aplexer_records_holding(
    payload: Any, *, workspace: str, tag: str
) -> list[Mapping[str, Any]]:
    if not isinstance(payload, list):
        return []
    target = os.path.realpath(workspace)
    records: list[Mapping[str, Any]] = []
    for raw in payload:
        if not isinstance(raw, Mapping):
            continue
        if str(raw.get("tag") or "") != tag:
            continue
        raw_workspace = raw.get("workspace") or raw.get("cwd") or ""
        if os.path.realpath(str(raw_workspace)) == target:
            records.append(raw)
    return records


def _aplexer_existing_record(
    payload: Any, *, workspace: str, tag: str
) -> Optional[Mapping[str, Any]]:
    for raw in _aplexer_records_holding(payload, workspace=workspace, tag=tag):
        if _session_enum.aplexer_record_is_alive(raw):
            return raw
    return None


def _process_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return True
    return True


def _aplexer_live_workload_pid(raw: Mapping[str, Any]) -> Optional[int]:
    try:
        pid = int(raw["workload_pid"])
    except (KeyError, TypeError, ValueError):
        return None
    if pid <= 0 or not _process_alive(pid):
        return None
    return pid


@dataclass(frozen=True)
class _BlockerReap:
    workload_alive: tuple[tuple[str, int], ...] = ()
    may_survive: tuple[str, ...] = ()
    unreaped: tuple[str, ...] = ()


def _reap_aplexer_blockers(
    payload: Any, *, aplexer_path: str, workspace: str, tag: str
) -> _BlockerReap:
    workload_alive: list[tuple[str, int]] = []
    may_survive: list[str] = []
    unreaped: list[str] = []
    for raw in _aplexer_records_holding(payload, workspace=workspace, tag=tag):
        ident = str(raw.get("id") or "").strip()
        if not ident or _session_enum.aplexer_record_is_alive(raw):
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
    return _BlockerReap(tuple(workload_alive), tuple(may_survive), tuple(unreaped))


def _cap_unenforceable_hint(memory_bytes: Optional[int], detail: str) -> str:
    if memory_bytes is None:
        return ""
    lowered = detail.lower()
    if "fail closed" not in lowered and "delegate" not in lowered:
        return ""
    return (
        f" This host could not enforce the {memory_bytes}-byte session memory "
        "cap. Fix the host's systemd --user setup, or create the session "
        "explicitly uncapped with `--mem none`."
    )


def _create_on_aplexer(
    *,
    name: str,
    cwd: Optional[str],
    mem: Optional[str],
    engine: Optional[str],
    profile: Optional[str],
) -> dict[str, Any]:
    """Create or reuse the aplexer record for ``workspace + tag``."""
    resolution = _resolve_aplexer()
    aplexer_path = resolution.path
    if aplexer_path is None:
        raise _CreateError(
            _aplexer_unresolved_message(
                resolution, action="sessions create requires aplexer"
            ),
            exit_code=127,
        )

    workspace = cwd or os.getcwd()
    try:
        memory_bytes = _memcap.resolve_session_mem_bytes(flag=mem, workspace=workspace)
    except _memcap.MemCapError as exc:
        raise _CreateError(
            f"pocketshell: cannot create {name!r} in {workspace!r}: {exc}",
            exit_code=2,
        ) from exc

    snapshot = _aplexer_snapshot()
    existing = _aplexer_existing_record(snapshot, workspace=workspace, tag=name)
    if existing is not None:
        return {
            "name": _session_enum.aplexer_display_name(existing) or name,
            "id": str(existing.get("id") or "") or None,
            "created": False,
        }

    blockers = _reap_aplexer_blockers(
        snapshot, aplexer_path=aplexer_path, workspace=workspace, tag=name
    )
    if blockers.workload_alive:
        detail = "; ".join(
            f"record {ident} still has workload pid {pid} running"
            for ident, pid in blockers.workload_alive
        )
        raise _CreateError(
            f"pocketshell: cannot create {name!r} in {workspace!r}: a dead "
            f"aplexer record still holds that workspace+tag and its workload "
            f"is still running ({detail}); stop it before retrying."
        )

    argv = aplexer_start_argv(
        aplexer_path=aplexer_path,
        workspace=workspace,
        tag=name,
        engine=engine,
        profile=profile,
        memory_bytes=memory_bytes,
    )
    code, stdout, stderr = _run_aplexer(argv)
    if code != 0:
        detail = stderr.strip() or stdout.strip() or "no output"
        if blockers.unreaped:
            stuck = ", ".join(blockers.unreaped)
            raise _CreateError(
                f"pocketshell: dead aplexer record ({stuck}) still holds "
                f"{workspace!r}:{name}; it could not be reaped. "
                f"(`a start` exited {code}: {detail})",
                exit_code=code,
            )
        raise _CreateError(
            f"pocketshell: `a start --tag {name}` exited {code}: {detail}"
            + _cap_unenforceable_hint(memory_bytes, detail),
            exit_code=code,
        )

    try:
        record = json.loads(stdout)
    except ValueError as exc:
        raise _CreateError(f"pocketshell: `a --json start` returned unreadable JSON: {exc}") from exc
    if not isinstance(record, Mapping):
        raise _CreateError(
            "pocketshell: `a --json start` returned "
            f"{type(record).__name__}, expected a session record"
        )
    for ident in blockers.may_survive:
        click.echo(_workload_survivor_warning(name, ident), err=True)
    return {
        "name": _session_enum.aplexer_display_name(record) or name,
        "id": str(record.get("id") or "") or None,
        "created": True,
    }


def _emit_create_failure(
    ctx: click.Context, message: str, *, exit_code: int, as_json: bool
) -> None:
    if as_json:
        click.echo(json.dumps({"schema": CREATE_SCHEMA_VERSION, "error": message}, indent=2))
    else:
        click.echo(message, err=True)
    ctx.exit(exit_code or 1)


@sessions_group.command("create", context_settings={"help_option_names": ["-h", "--help"]})
@click.argument("name")
@click.option(
    "--cwd", "-c", default=None,
    help="Working directory for the new detached aplexer session.",
)
@click.option(
    "--mem", default=None,
    help="Memory cap override, for example 24G; use `none` only explicitly.",
)
@click.option(
    "--engine", default=None,
    help="Start a coding agent in the new session.",
)
@click.option(
    "--profile", default=None,
    help="Named host profile for --engine.",
)
@click.option(
    "--json", "as_json", is_flag=True, default=False,
    help="Emit the schema-3 create envelope.",
)
@click.pass_context
def sessions_create(
    ctx: click.Context,
    name: str,
    cwd: Optional[str],
    mem: Optional[str],
    engine: Optional[str],
    profile: Optional[str],
    as_json: bool,
) -> None:
    """Create a detached aplexer session, idempotently."""
    try:
        result = _create_on_aplexer(
            name=name, cwd=cwd, mem=mem, engine=engine, profile=profile
        )
    except _CreateError as exc:
        _emit_create_failure(ctx, exc.message, exit_code=exc.exit_code, as_json=as_json)
        return
    if as_json:
        click.echo(json.dumps({"schema": CREATE_SCHEMA_VERSION, **result}, indent=2))


ATTACH_EXIT_NOT_FOUND = 3
ATTACH_EXIT_AMBIGUOUS = 4
ATTACH_EXIT_NO_BINARY = 127
APLEXER_ID_PREFIX_MIN = 8


def _exec(argv: list[str]) -> None:
    """Replace this process with the already resolved executable."""
    os.execv(argv[0], argv)


def _match_attach_target(
    rows: Sequence[_session_enum.LiveSession], name: str
) -> list[_session_enum.LiveSession]:
    exact = [row for row in rows if row.name == name]
    if exact:
        return exact
    if len(name) < APLEXER_ID_PREFIX_MIN:
        return []
    return [row for row in rows if row.aplexer_id and row.aplexer_id.startswith(name)]


def _attach_live_rows() -> tuple[list[_session_enum.LiveSession], list[dict[str, str]]]:
    return _session_enum.enumerate_live_sessions()


def _dead_row_detail(row: _session_enum.LiveSession) -> str:
    phase = row.phase or "unknown"
    if phase == "exited":
        return f"it has already exited (aplexer phase: {phase})"
    if phase == "failed":
        return f"it failed to start (aplexer phase: {phase})"
    return f"it has no live worker (aplexer phase: {phase}, worker_alive: false)"


def _not_attachable_message(name: str) -> str:
    dead = _session_enum.dead_sessions_from_aplexer_snapshot(_aplexer_snapshot())
    for row in _match_attach_target(dead, name):
        return (
            f"pocketshell: aplexer session {row.name!r} is no longer running: "
            f"{_dead_row_detail(row)}. It cannot be attached; run "
            "`pocketshell sessions list` for the live ones."
        )
    return f"no session named {name!r}"


def _describe_candidate(row: _session_enum.LiveSession) -> str:
    if row.aplexer_id:
        return f"  {row.name}  (aplexer {row.aplexer_id})"
    return f"  {row.name}"


@sessions_group.command("attach", context_settings={"help_option_names": ["-h", "--help"]})
@click.argument("name")
@click.pass_context
def sessions_attach(ctx: click.Context, name: str) -> None:
    """Attach to a live session by display name or id prefix."""
    rows, errors = _attach_live_rows()
    if errors:
        click.echo(
            "pocketshell: " + "; ".join(error["message"] for error in errors),
            err=True,
        )
        ctx.exit(ATTACH_EXIT_NO_BINARY)
        return
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
    if not row.aplexer_id:
        click.echo(f"pocketshell: session {row.name!r} has no aplexer id.", err=True)
        ctx.exit(ATTACH_EXIT_NOT_FOUND)
        return
    _exec([resolution.path, "attach", str(row.aplexer_id)])


def _emit_kill_failure(
    ctx: click.Context, message: str, *, exit_code: int, as_json: bool
) -> None:
    if as_json:
        click.echo(json.dumps({"schema": KILL_SCHEMA_VERSION, "error": message}, indent=2))
    else:
        click.echo(message, err=True)
    ctx.exit(exit_code or 1)


# ``a kill`` can leave a terminal record behind while the worker winds down.
_REAP_WORKER_STILL_LIVE = "still has a live worker"
_REAP_ALREADY_GONE = "no matching session"
_REAP_MAX_ATTEMPTS = 30
_REAP_POLL_S = 0.1
_REAP_BUDGET_S = 3.0


def _reap_wait() -> None:
    time.sleep(_REAP_POLL_S)


@dataclass(frozen=True)
class _ReapOutcome:
    reaped: bool
    workload_may_survive: bool = False


def _reap_aplexer_record(aplexer_path: str, aplexer_id: str) -> _ReapOutcome:
    deadline = time.monotonic() + _REAP_BUDGET_S
    argv = [aplexer_path, "--json", "forget", "--force", str(aplexer_id)]
    for attempt in range(_REAP_MAX_ATTEMPTS):
        try:
            completed = _run_session_command(argv)
        except (subprocess.TimeoutExpired, OSError):
            return _ReapOutcome(False)
        if completed.returncode == 0:
            return _ReapOutcome(
                True, _reap_workload_may_survive(completed.stdout)
            )
        detail = f"{completed.stderr or ''}{completed.stdout or ''}"
        if _REAP_ALREADY_GONE in detail:
            return _ReapOutcome(True)
        if _REAP_WORKER_STILL_LIVE not in detail:
            return _ReapOutcome(False)
        if attempt + 1 >= _REAP_MAX_ATTEMPTS or time.monotonic() >= deadline:
            return _ReapOutcome(False)
        _reap_wait()
    return _ReapOutcome(False)


def _reap_workload_may_survive(stdout: Optional[str]) -> bool:
    try:
        payload = json.loads(stdout or "")
    except ValueError:
        return False
    return isinstance(payload, Mapping) and bool(payload.get("workload_may_survive"))


def _workload_survivor_warning(name: str, aplexer_id: str) -> str:
    return (
        f"pocketshell: reclaimed {name!r} from aplexer record {aplexer_id}, "
        "but aplexer could not prove its workload containment was empty; "
        "its processes may still be running and are no longer tracked."
    )


@sessions_group.command("kill", context_settings={"help_option_names": ["-h", "--help"]})
@click.argument("name")
@click.option("--json", "as_json", is_flag=True, default=False, help="Emit the schema-3 kill envelope.")
@click.pass_context
def sessions_kill(ctx: click.Context, name: str, as_json: bool) -> None:
    """Stop a live aplexer session and reap its record."""
    rows, errors = _attach_live_rows()
    if errors:
        _emit_kill_failure(
            ctx,
            "; ".join(error["message"] for error in errors),
            exit_code=ATTACH_EXIT_NO_BINARY,
            as_json=as_json,
        )
        return
    matches = _match_attach_target(rows, name)
    if not matches:
        _emit_kill_failure(
            ctx, f"no session named {name!r}",
            exit_code=ATTACH_EXIT_NOT_FOUND, as_json=as_json,
        )
        return
    if len(matches) > 1:
        _emit_kill_failure(
            ctx, f"ambiguous session name {name!r}",
            exit_code=ATTACH_EXIT_AMBIGUOUS, as_json=as_json,
        )
        return

    row = matches[0]
    resolution = _resolve_aplexer()
    if resolution.path is None:
        _emit_kill_failure(
            ctx,
            _aplexer_unresolved_message(
                resolution, action=f"cannot stop {row.name!r}"
            ),
            exit_code=ATTACH_EXIT_NO_BINARY,
            as_json=as_json,
        )
        return
    if not row.aplexer_id:
        _emit_kill_failure(
            ctx, f"pocketshell: session {row.name!r} has no aplexer id.",
            exit_code=ATTACH_EXIT_NOT_FOUND, as_json=as_json,
        )
        return

    try:
        completed = _run_session_command([resolution.path, "kill", str(row.aplexer_id)])
    except subprocess.TimeoutExpired:
        _emit_kill_failure(
            ctx, f"pocketshell: `a kill {row.aplexer_id}` timed out.",
            exit_code=1, as_json=as_json,
        )
        return
    except OSError as exc:
        _emit_kill_failure(
            ctx, f"pocketshell: could not stop {row.name!r}: {exc}",
            exit_code=ATTACH_EXIT_NO_BINARY, as_json=as_json,
        )
        return

    if completed.returncode != 0:
        detail = str(completed.stderr or "").strip() or f"exit {completed.returncode}"
        _emit_kill_failure(
            ctx, f"pocketshell: could not stop {row.name!r}: {detail}",
            exit_code=completed.returncode, as_json=as_json,
        )
        return

    outcome = _reap_aplexer_record(resolution.path, str(row.aplexer_id))
    if outcome.workload_may_survive:
        click.echo(_workload_survivor_warning(row.name, str(row.aplexer_id)), err=True)
    if not outcome.reaped:
        click.echo(
            f"pocketshell: stopped {row.name!r}, but its aplexer record could "
            f"not be reaped; run `a forget --force {row.aplexer_id}` if it "
            "keeps showing up.",
            err=True,
        )
    if as_json:
        click.echo(
            json.dumps(
                {
                    "schema": KILL_SCHEMA_VERSION,
                    "name": row.name,
                    "id": row.aplexer_id,
                    "killed": True,
                    "reaped": outcome.reaped,
                },
                indent=2,
            )
        )
