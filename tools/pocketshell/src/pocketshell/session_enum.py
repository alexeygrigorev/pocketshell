"""Live session enumeration for the PocketShell host CLI.

The host has one session backend: aplexer. This module converts aplexer's
JSON records to the stable list contract consumed by the CLI, the Android
client, and tree reconciliation helpers.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping, Optional, Sequence

from pocketshell import aplexer


# Wire version of :func:`json_payload`. Schema 3 removes the old backend
# discriminator and makes the aplexer-only contract explicit.
SCHEMA_VERSION = 3

AGENT_STATE_IDLE = "idle"
AGENT_STATE_WAITING = "waiting"
AGENT_STATE_WORKING = "working"
AGENT_STATES = (AGENT_STATE_IDLE, AGENT_STATE_WAITING, AGENT_STATE_WORKING)

AGENT_STATE_SOURCE_REPORTED = "reported"
AGENT_STATE_SOURCE_HEURISTIC = "heuristic"

APLEXER_TERMINAL_PHASES = frozenset({"exited", "failed"})

# A record without a worker pid is the shape written during the beginning of
# ``a start``. Keep it visible for a bounded period so a slow start cannot
# disappear from the list, while an abandoned record eventually stops being
# offered as an attach target.
APLEXER_STARTING_GRACE_MS = 60_000
APLEXER_ACTIVITY_THRESHOLD_MS = 3_000
APLEXER_REPORTED_STATE_STALE_MS = 8_000


@dataclass(frozen=True)
class LiveSession:
    """One live aplexer session row."""

    name: str
    created: Optional[str] = None
    created_epoch: Optional[int] = None
    workspace: Optional[str] = None
    tag: Optional[str] = None
    engine: Optional[str] = None
    profile: Optional[str] = None
    aplexer_id: Optional[str] = None
    agent: Optional[str] = None
    agent_state: Optional[str] = None
    agent_state_source: Optional[str] = None
    attached: bool = False
    activity_epoch: Optional[int] = None
    phase: Optional[str] = None
    alive: bool = True
    extra: Mapping[str, Any] = field(default_factory=dict)

    def to_payload(self, schema: int = SCHEMA_VERSION) -> dict[str, Any]:
        """Serialize the fixed schema-3 row.

        ``schema`` remains an optional argument for downstream helpers that
        pass the module's schema constant explicitly. Older shapes are not
        emitted.
        """
        del schema
        return {
            "name": self.name,
            "id": self.aplexer_id,
            "workspace": self.workspace,
            "tag": self.tag,
            "engine": self.engine,
            "profile": self.profile,
            "agent": self.agent,
            "agent_state": self.agent_state,
            "agent_state_source": self.agent_state_source,
            "attached": bool(self.attached),
            "created_epoch": self.created_epoch,
            "activity_epoch": self.activity_epoch,
            "phase": self.phase,
            "alive": bool(self.alive),
        }


def enumeration_error(message: str) -> dict[str, str]:
    """Return one visible session-list failure."""
    return {"message": message}


def aplexer_display_name(row: Mapping[str, Any]) -> str:
    """Stable listing name: ``<workspace-basename>:<tag>``, else tag or id."""
    workspace = row.get("workspace") or row.get("cwd") or ""
    tag = str(row.get("tag") or "").strip()
    base = Path(str(workspace)).name if workspace else ""
    if base and tag:
        return f"{base}:{tag}"
    if tag:
        return tag
    ident = row.get("id")
    return str(ident).strip() if ident else ""


def _format_epoch(epoch: Optional[int]) -> Optional[str]:
    if epoch is None:
        return None
    try:
        return datetime.fromtimestamp(int(epoch), tz=timezone.utc).strftime(
            "%Y-%m-%d %H:%M:%S"
        )
    except (OverflowError, OSError, ValueError, TypeError):
        return None


def _created_epoch_from_ms(value: Any) -> Optional[int]:
    try:
        milliseconds = int(value)
    except (TypeError, ValueError):
        return None
    if milliseconds <= 0:
        return None
    return milliseconds // 1000


def _coerce_ms(value: Any) -> Optional[int]:
    try:
        milliseconds = int(value)
    except (TypeError, ValueError):
        return None
    return milliseconds if milliseconds > 0 else None


def aplexer_phase(raw: Mapping[str, Any]) -> Optional[str]:
    """Normalised aplexer lifecycle phase, or ``None`` when absent."""
    phase = str(raw.get("phase") or "").strip().lower()
    return phase or None


def _aplexer_engine(raw: Mapping[str, Any]) -> Optional[str]:
    """Engine id, or ``None`` for a plain shell."""
    engine = str(raw.get("engine") or "").strip()
    if not engine or engine == "shell":
        return None
    return engine


def _aplexer_agent(raw: Mapping[str, Any]) -> Optional[str]:
    """The agent aplexer detected in this session, or ``None``."""
    agent = raw.get("agent")
    if not isinstance(agent, str):
        return None
    return agent.strip() or None


def _aplexer_attached(raw: Mapping[str, Any]) -> bool:
    """Whether aplexer reports an attached client for this session."""
    count = raw.get("attached_clients")
    if isinstance(count, bool):
        return count
    if isinstance(count, int):
        return count > 0
    attached = raw.get("attached")
    if isinstance(attached, bool):
        return attached
    if isinstance(attached, int):
        return attached > 0
    return False


def aplexer_agent_state(
    raw: Mapping[str, Any], now_ms: Optional[int] = None
) -> tuple[Optional[str], Optional[str]]:
    """Return ``(agent_state, agent_state_source)`` for one record."""
    if now_ms is None:
        now_ms = int(time.time() * 1000)
    phase = aplexer_phase(raw)
    if phase in APLEXER_TERMINAL_PHASES:
        return None, None
    if phase == "starting":
        return AGENT_STATE_WORKING, AGENT_STATE_SOURCE_HEURISTIC

    reported = str(raw.get("reported_state") or "").strip().lower()
    reported_at = _coerce_ms(raw.get("reported_state_at_ms"))
    if reported in AGENT_STATES and reported_at is not None:
        if now_ms - reported_at <= APLEXER_REPORTED_STATE_STALE_MS:
            return reported, AGENT_STATE_SOURCE_REPORTED

    activity = _coerce_ms(raw.get("last_activity_ms"))
    if activity is None or now_ms - activity < APLEXER_ACTIVITY_THRESHOLD_MS:
        return AGENT_STATE_WORKING, AGENT_STATE_SOURCE_HEURISTIC
    return AGENT_STATE_WAITING, AGENT_STATE_SOURCE_HEURISTIC


def aplexer_worker_pid(raw: Mapping[str, Any]) -> Optional[int]:
    """The record's worker pid, or ``None`` before worker registration."""
    try:
        pid = int(raw["worker_pid"])
    except (KeyError, TypeError, ValueError):
        return None
    return pid if pid > 0 else None


def _aplexer_worker_alive_flag(raw: Mapping[str, Any]) -> bool:
    """Read ``worker_alive`` while failing open for old records."""
    worker_alive = raw.get("worker_alive")
    if isinstance(worker_alive, bool):
        return worker_alive
    if isinstance(worker_alive, int):
        return worker_alive > 0
    return True


def _aplexer_within_starting_grace(
    raw: Mapping[str, Any], now_ms: Optional[int]
) -> bool:
    updated = _coerce_ms(raw.get("updated_at_ms"))
    if updated is None:
        return True
    if now_ms is None:
        now_ms = int(time.time() * 1000)
    return now_ms - updated <= APLEXER_STARTING_GRACE_MS


def aplexer_record_is_alive(
    raw: Mapping[str, Any], now_ms: Optional[int] = None
) -> bool:
    """Whether an aplexer record is still an attachable session."""
    if aplexer_phase(raw) in APLEXER_TERMINAL_PHASES:
        return False
    if _aplexer_worker_alive_flag(raw):
        return True
    if aplexer_worker_pid(raw) is not None:
        return False
    return _aplexer_within_starting_grace(raw, now_ms)


def _aplexer_rows(payload: Any, now_ms: Optional[int]) -> list[LiveSession]:
    """Convert every snapshot record to a :class:`LiveSession`."""
    if not isinstance(payload, list):
        return []
    if now_ms is None:
        now_ms = int(time.time() * 1000)

    rows: list[LiveSession] = []
    seen: set[str] = set()
    for raw in payload:
        if not isinstance(raw, Mapping):
            continue
        name = aplexer_display_name(raw)
        ident = str(raw.get("id") or "").strip()
        if not name:
            continue
        key = ident or name
        if key in seen:
            continue
        seen.add(key)
        epoch = _created_epoch_from_ms(raw.get("created_at_ms"))
        workspace = raw.get("workspace") or raw.get("cwd")
        tag = raw.get("tag")
        profile = raw.get("profile")
        state, state_source = aplexer_agent_state(raw, now_ms)
        rows.append(
            LiveSession(
                name=name,
                created=_format_epoch(epoch),
                created_epoch=epoch,
                workspace=str(workspace) if workspace else None,
                tag=str(tag) if tag else None,
                engine=_aplexer_engine(raw),
                profile=str(profile) if profile else None,
                aplexer_id=ident or None,
                agent=_aplexer_agent(raw),
                agent_state=state,
                agent_state_source=state_source,
                attached=_aplexer_attached(raw),
                activity_epoch=_created_epoch_from_ms(raw.get("last_activity_ms")),
                phase=aplexer_phase(raw),
                alive=aplexer_record_is_alive(raw, now_ms),
            )
        )
    return rows


def sessions_from_aplexer_snapshot(
    payload: Any, *, now_ms: Optional[int] = None
) -> list[LiveSession]:
    """Return only the attachable records from an aplexer snapshot."""
    return [row for row in _aplexer_rows(payload, now_ms) if row.alive]


def dead_sessions_from_aplexer_snapshot(
    payload: Any, *, now_ms: Optional[int] = None
) -> list[LiveSession]:
    """Return records that aplexer retained after their session ended."""
    return [row for row in _aplexer_rows(payload, now_ms) if not row.alive]


def _probe_aplexer(
    env: Optional[Mapping[str, str]],
) -> tuple[Any, Optional[str]]:
    """Return ``(payload, error_message)`` for the required backend."""
    if not aplexer.enabled("sessions", env):
        return None, "aplexer session support is disabled by configuration"
    binary = aplexer.which_a(env)
    if binary is None:
        return None, "aplexer is unavailable: the bundled `a` executable was not found"
    payload = aplexer.run_json(["snapshot"], env=env, feature="sessions")
    if payload is None:
        payload = aplexer.run_json(["list"], env=env, feature="sessions")
    if payload is None:
        return None, (
            f"`{binary} --json snapshot` and `{binary} --json list` both "
            "failed or returned unreadable JSON"
        )
    if not isinstance(payload, list):
        return None, (
            f"`{binary} --json snapshot` returned "
            f"{type(payload).__name__}, expected a list of session records"
        )
    return payload, None


def enumerate_live_sessions(
    *,
    aplexer_payload: Any = None,
    env: Optional[Mapping[str, str]] = None,
    now_ms: Optional[int] = None,
) -> tuple[list[LiveSession], list[dict[str, str]]]:
    """Enumerate aplexer sessions and return ``(rows, errors)``.

    A missing executable, disabled feature, or failed JSON probe is visible as
    an error. An empty successful payload is the only healthy empty result.
    ``aplexer_payload`` is an injection seam for deterministic tests and
    fixture journeys.
    """
    errors: list[dict[str, str]] = []
    payload = aplexer_payload
    if payload is None:
        payload, error = _probe_aplexer(env)
        if error:
            errors.append(enumeration_error(error))
    elif not isinstance(payload, list):
        errors.append(
            enumeration_error(
                f"aplexer returned {type(payload).__name__}, expected a list of session records"
            )
        )
    return sessions_from_aplexer_snapshot(payload, now_ms=now_ms), errors


def json_payload(
    sessions: Sequence[LiveSession],
    errors: Sequence[Mapping[str, str]] = (),
) -> dict[str, Any]:
    """Build the schema-3 ``sessions list --json`` document."""
    return {
        "schema": SCHEMA_VERSION,
        "sessions": [row.to_payload(schema=SCHEMA_VERSION) for row in sessions],
        "errors": [dict(error) for error in errors],
    }


def format_aplexer_table(sessions: Sequence[LiveSession]) -> str:
    """Format the human-readable session list."""
    if not sessions:
        return ""
    lines = ["IDX  SESSION               CREATED"]
    for index, row in enumerate(sessions, start=1):
        created = row.created or ""
        lines.append(f"{index:<5}{row.name} {created}".rstrip())
    return "\n".join(lines) + "\n"
