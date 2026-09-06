"""Live session name set shared by ``pocketshell sessions``, tree reconcile,
and the Android list parsers.

The phone used to enumerate with a bare ``tmux list-sessions``, which only
sees the *default* tmux socket. After tmuxctl moved to one server per
session, that default socket holds a handful of leftover sessions while
``tmuxctl list`` / ``t`` (the terminal enumerator) walks every
``tmuxctl-*`` socket. The name sets must match: parse the same
``tmuxctl list`` table the Android ``HostTmuxSessionListParser`` already
understands, then union aplexer snapshot rows tagged as a second manager.
"""

from __future__ import annotations

import os
import re
import subprocess
import time
from dataclasses import dataclass, field, replace
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Optional, Sequence

from pocketshell import aplexer

# Same trailing ``YYYY-MM-DD HH:MM:SS`` anchor as HostTmuxSessionListParser
# (issue #200): long names overflow tmuxctl's SESSION column padding and
# collapse the separator to a single space. Splitting on two-or-more
# spaces, or taking a fixed-width slice, silently drops those rows.
_TRAILING_TIMESTAMP = re.compile(r"(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})")
_LEADING_IDX = re.compile(r"^\s*\d+\s+")
_HINT_PREFIXES = (
    "IDX ",
    "Join a session:",
    "Create a new one:",
    "Use current folder:",
    "Help:",
    "APLEXER",
    "MANAGER",
)

MANAGER_TMUX = "tmux"
MANAGER_APLEXER = "aplexer"

#: Wire version of :func:`json_payload`. Schema 2 emits every documented key
#: on every row (``null``/``false`` instead of omission) so a client parser
#: never needs a ``containsKey`` probe, and adds a top-level ``errors`` list
#: so a backend that failed to enumerate is visible instead of showing up as
#: a silently shorter session list (issue #2426).
SCHEMA_VERSION = 2

# Schema-2 ``agent_state`` vocabulary. Deliberately the same three words
# aplexer's ``a state-report`` accepts (aplexer/src/bin/a.rs: "idle",
# "waiting", "working") so a reported push round-trips unchanged.
AGENT_STATE_IDLE = "idle"
AGENT_STATE_WAITING = "waiting"
AGENT_STATE_WORKING = "working"
AGENT_STATES = (AGENT_STATE_IDLE, AGENT_STATE_WAITING, AGENT_STATE_WORKING)

AGENT_STATE_SOURCE_REPORTED = "reported"
AGENT_STATE_SOURCE_HEURISTIC = "heuristic"

#: aplexer phases that mean the session is over (``Phase::Exited`` /
#: ``Phase::Failed`` in aplexer's ``src/lib.rs``). ``starting``/``running``/
#: ``exiting`` are all still-live phases.
APLEXER_TERMINAL_PHASES = frozenset({"exited", "failed"})

#: How long a record with no ``worker_pid`` yet is still credited as "being
#: created" (issue #2554). See :func:`aplexer_record_is_alive`: a session
#: mid-``a start`` and an ``a start`` that was killed inside the pre-PID
#: window are the SAME record shape and differ only in age.
#:
#: 60 s is chosen to be generous in the safe direction, because calling a
#: slow spawn dead is the #2547 regression while calling a corpse alive for
#: another minute is a stale row. Three independent anchors, all measured or
#: read off source rather than guessed:
#:
#: * the real pre-PID window on the dev box is 26-46 ms (polled through two
#:   separate ``a start`` runs) — 60 s is >1000x that;
#: * aplexer itself gives up on a worker after ``--startup-timeout-ms``,
#:   default 10_000 (``aplexer/src/bin/a.rs``) — 60 s is 6x its own patience;
#: * this CLI abandons ``a start`` after ``sessions._APLEXER_START_TIMEOUT_S``
#:   (20 s) — 60 s is 3x that.
#:
#: A record older than all three cannot still be legitimately starting.
APLEXER_STARTING_GRACE_MS = 60_000

# Both constants mirror aplexer's ``a watch`` (aplexer/src/watch.rs):
# ACTIVITY_THRESHOLD_MS is how long a PTY must stay quiet before the
# recency heuristic calls a session "waiting"; REPORTED_STATE_STALE_MS is
# how long an ``a state-report`` push stays authoritative over that
# heuristic. Kept in sync deliberately: the phone and ``a watch`` must not
# disagree about the same record.
APLEXER_ACTIVITY_THRESHOLD_MS = 3_000
APLEXER_REPORTED_STATE_STALE_MS = 8_000

# tmuxctl runs one tmux server per session on its own socket, named after
# the session (``tmuxctl-<session>`` under the tmux socket directory).
TMUXCTL_SOCKET_PREFIX = "tmuxctl-"
TMUX_DEFAULT_SOCKET = "default"
TMUX_DETAIL_FORMAT = (
    "#{session_name}\t#{session_path}\t#{session_attached}"
    "\t#{session_created}\t#{session_activity}"
)
TMUX_DETAIL_TIMEOUT_S = 2.0

#: ``runner(argv) -> (returncode, stdout, stderr)``; raises ``OSError`` when
#: the ``tmux`` binary is missing. Injected by the unit suite.
TmuxRunner = Callable[[Sequence[str]], "tuple[int, str, str]"]


@dataclass(frozen=True)
class LiveSession:
    """One row in the combined tmux + aplexer listing."""

    name: str
    manager: str
    created: Optional[str] = None
    created_epoch: Optional[int] = None
    workspace: Optional[str] = None
    tag: Optional[str] = None
    engine: Optional[str] = None
    profile: Optional[str] = None
    aplexer_id: Optional[str] = None
    attach: Optional[str] = None
    #: Which coding agent aplexer can SEE running inside the session right
    #: now (``claude``/``codex``/``opencode``/``grok``), or ``None``. Not the
    #: same question as ``engine``: every session PocketShell creates is
    #: ``engine: "shell"`` with the agent started by hand inside it, so
    #: ``engine`` is ``None`` for exactly the rows a user would call "my
    #: claude session". aplexer 0.1.4 derives this per query from the
    #: workload's descendant process tree and never persists it, so it
    #: cannot go stale; a tmux row has no such authority and stays ``None``.
    agent: Optional[str] = None
    agent_state: Optional[str] = None
    agent_state_source: Optional[str] = None
    attached: bool = False
    activity_epoch: Optional[int] = None
    #: aplexer's lifecycle phase for this record (``starting``/``running``/
    #: ``exiting``/``exited``/``failed``), or ``None`` for a tmux row, which
    #: has no such vocabulary. Descriptive only — ``alive`` is the decision.
    phase: Optional[str] = None
    #: Whether this session can actually be attached. A tmux row only exists
    #: because ``tmuxctl list`` just listed it, so it defaults to ``True``;
    #: an aplexer row gets :func:`aplexer_record_is_alive`'s verdict.
    alive: bool = True
    extra: Mapping[str, Any] = field(default_factory=dict)

    def to_payload(self, schema: int = 1) -> dict[str, Any]:
        """Serialize one row.

        ``schema=1`` keeps the historical key-if-not-None shape. ``schema=2``
        emits every key unconditionally — the Android parser reads a fixed
        record and must never have to branch on key presence.
        """
        if schema >= 2:
            return {
                "name": self.name,
                "manager": self.manager,
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
                # Issue #2554: liveness travels on the wire. The host already
                # filters dead records out of the listing, so a client seeing
                # ``alive: false`` means it asked for the dead projection on
                # purpose — but the field is emitted on EVERY row, dead or
                # live, tmux or aplexer, per schema 2's "every key, always".
                "phase": self.phase,
                "alive": bool(self.alive),
            }
        payload: dict[str, Any] = {
            "name": self.name,
            "manager": self.manager,
        }
        if self.created is not None:
            payload["created"] = self.created
        if self.created_epoch is not None:
            payload["created_epoch"] = self.created_epoch
        if self.workspace is not None:
            payload["workspace"] = self.workspace
        if self.tag is not None:
            payload["tag"] = self.tag
        if self.engine is not None:
            payload["engine"] = self.engine
        if self.aplexer_id is not None:
            payload["id"] = self.aplexer_id
        if self.attach is not None:
            payload["attach"] = self.attach
        return payload


def enumeration_error(manager: str, message: str) -> dict[str, str]:
    """One entry of the schema-2 ``errors`` list.

    A backend that fails to enumerate MUST produce one of these. Returning a
    silently shorter session list is the exact regression issue #2426 fixed:
    the phone cannot tell "aplexer has no sessions" from "the ``a`` probe
    blew up" without it.
    """
    return {"manager": manager, "message": message}


def parse_tmuxctl_list_names(stdout: str) -> list[str]:
    """Extract session names from a ``tmuxctl list`` human table, in order.

    Mirrors ``HostTmuxSessionListParser.parsePocketshellSessionsList``:
    skip header/hint rows, anchor on the trailing timestamp, take the
    trimmed text between the leading IDX and that timestamp as the name.
    Names with no spaces still round-trip; overflowed long names are kept
    instead of being dropped.
    """
    names: list[str] = []
    seen: set[str] = set()
    for line in stdout.splitlines():
        name = parse_tmuxctl_list_name(line)
        if name is None or name in seen:
            continue
        seen.add(name)
        names.append(name)
    return names


def parse_tmuxctl_list_name(line: str) -> Optional[str]:
    """Return the session name on one ``tmuxctl list`` data row, or None."""
    if not line or not line.strip():
        return None
    trimmed = line.strip()
    if any(trimmed.startswith(prefix) for prefix in _HINT_PREFIXES):
        return None
    timestamp_match = _TRAILING_TIMESTAMP.search(line)
    if timestamp_match is None:
        return None
    if line[timestamp_match.end() :].strip():
        return None
    before = line[: timestamp_match.start()]
    idx_match = _LEADING_IDX.match(before)
    if idx_match is None:
        return None
    name = before[idx_match.end() :].strip()
    return name or None


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
        ms = int(value)
    except (TypeError, ValueError):
        return None
    if ms <= 0:
        return None
    return ms // 1000


def _epoch_from_local_stamp(stamp: Optional[str]) -> Optional[int]:
    """Parse tmuxctl's ``YYYY-MM-DD HH:MM:SS`` CREATED column to an epoch.

    tmuxctl renders that column in the host's LOCAL time (verified against
    ``#{session_created}`` on the dev box), so the naive timestamp is parsed
    as local time, not UTC. Only used when the tmux enrichment sweep could
    not supply the authoritative ``#{session_created}`` epoch.
    """
    if not stamp:
        return None
    try:
        parsed = datetime.strptime(stamp.strip(), "%Y-%m-%d %H:%M:%S")
    except (TypeError, ValueError):
        return None
    try:
        return int(parsed.timestamp())
    except (OSError, OverflowError, ValueError):
        return None


def _epoch_from_seconds(value: Any) -> Optional[int]:
    try:
        seconds = int(str(value).strip())
    except (TypeError, ValueError):
        return None
    return seconds if seconds > 0 else None


def sessions_from_tmuxctl_table(stdout: str) -> list[LiveSession]:
    rows: list[LiveSession] = []
    for line in stdout.splitlines():
        name = parse_tmuxctl_list_name(line)
        if name is None:
            continue
        stamp = _TRAILING_TIMESTAMP.search(line)
        created = stamp.group(1) if stamp else None
        rows.append(
            LiveSession(
                name=name,
                manager=MANAGER_TMUX,
                created=created,
                created_epoch=_epoch_from_local_stamp(created),
                attach=f"tmux attach -t {name}",
            )
        )
    return rows


def _aplexer_engine(raw: Mapping[str, Any]) -> Optional[str]:
    """Engine id, or ``None`` for a plain shell.

    aplexer stores a plain shell session as the literal engine ``"shell"``
    (it has no nullable engine column); schema 2 says ``engine: null`` means
    "plain shell", so the two are the same statement and the null form wins
    on the wire.
    """
    engine = str(raw.get("engine") or "").strip()
    if not engine or engine == "shell":
        return None
    return engine


def _aplexer_agent(raw: Mapping[str, Any]) -> Optional[str]:
    """The agent aplexer detected in this session, or ``None``.

    ``agent`` is a QUERY-time field (aplexer 0.1.4, ``src/agent_kind.rs`` /
    spec.md section 18): aplexer walks the workload's descendant process tree
    and names the coding agent it finds, or reports ``null`` when there is
    none — including a shell session whose agent has just exited. It is never
    written to disk, so it cannot go stale.

    A missing key means an aplexer older than 0.1.4 answered the probe, which
    is a "cannot tell", not "no agent" — both land on ``None`` here, but it
    must never be a ``KeyError``: the pin is a floor, not a guarantee about
    the binary a given host happens to run.

    Only a non-empty string is a name. Anything else is read as ``None``
    rather than coerced with ``str()``, which would turn a malformed
    ``["claude"]`` into the literal agent name ``"['claude']"`` and put it on
    the wire.
    """
    agent = raw.get("agent")
    if not isinstance(agent, str):
        return None
    return agent.strip() or None


def _aplexer_attached(raw: Mapping[str, Any]) -> bool:
    """Whether the aplexer worker currently has an attached client.

    ``a snapshot --json`` (aplexer 0.1.1, ``SessionRecord``) exposes no
    attached-client count, so this is ``False`` for every real row today —
    the schema-2 contract says "if the snapshot exposes it, else false".
    Both plausible spellings are read so the field starts reporting the
    truth the moment aplexer grows one, without another schema bump.
    """
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
    """``(agent_state, agent_state_source)`` for one aplexer snapshot row.

    Mirrors ``a watch``'s ``derive_agent_state_with_source``
    (aplexer/src/watch.rs) one branch at a time:

    * a ``reported_state`` push younger than ``REPORTED_STATE_STALE_MS``
      wins outright, source ``reported``;
    * otherwise the PTY-recency heuristic runs: output within
      ``ACTIVITY_THRESHOLD_MS`` is ``working``, older is ``waiting``, and a
      record with no observed activity yet is ``working`` (the session just
      started; no evidence of it going quiet), source ``heuristic``;
    * ``starting`` maps onto ``working`` and the terminal phases
      (``exited``/``failed``) onto ``(None, None)``, because schema 2's
      vocabulary is exactly ``idle|waiting|working|null`` — a dead session
      has no agent state, its ``phase`` is the thing that describes it.

    The one deliberate difference from ``a watch``: a reported ``working``
    stays ``working`` here instead of folding onto watch's wire word
    ``running``, since schema 2 already has ``working`` as its own value.
    """
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
    if activity is None:
        return AGENT_STATE_WORKING, AGENT_STATE_SOURCE_HEURISTIC
    if now_ms - activity < APLEXER_ACTIVITY_THRESHOLD_MS:
        return AGENT_STATE_WORKING, AGENT_STATE_SOURCE_HEURISTIC
    return AGENT_STATE_WAITING, AGENT_STATE_SOURCE_HEURISTIC


def _coerce_ms(value: Any) -> Optional[int]:
    try:
        ms = int(value)
    except (TypeError, ValueError):
        return None
    return ms if ms > 0 else None


def aplexer_phase(raw: Mapping[str, Any]) -> Optional[str]:
    """Normalised aplexer lifecycle phase, or ``None`` when absent."""
    phase = str(raw.get("phase") or "").strip().lower()
    return phase or None


def aplexer_record_is_alive(
    raw: Mapping[str, Any], now_ms: Optional[int] = None
) -> bool:
    """Whether an ``a list --json`` record is still an attachable session.

    Issue #2554. aplexer keeps a record after its session dies, and nothing
    ever removed those: a killed session stays at ``phase: exited`` forever,
    and a worker that died without recording an exit leaves ``phase:
    running`` with ``worker_alive: false`` (``a prune`` in 0.1.3 cannot reap
    that shape at all — verified on the dev box, three such records were ten
    days old). Listing them made the tree offer rows whose attach answers
    "session ... has already exited" and exits 1.

    A record is DEAD when either:

    * its ``phase`` is terminal — ``exited`` or ``failed`` (aplexer's
      ``Phase::Exited``/``Phase::Failed``). This wins over everything else:
      aplexer's launcher writes ``failed`` with no ``worker_pid`` when a
      worker dies before completing startup
      (``api.rs::persist_independent_cleanup_proof``); or
    * it HAD a worker and lost it — ``worker_pid`` is set and
      ``worker_alive`` is false. That is the zombie: nothing serves its
      control socket, so ``a attach`` cannot succeed.

    Everything else is alive, including two shapes that are deliberately NOT
    dead:

    * **``exiting`` with a live worker** — the real window right after
      ``a kill``, where ``a attach`` still works.
    * **a RECENT non-terminal phase with NO ``worker_pid``** — the record
      aplexer writes at the very start of ``a start``, before the worker
      registers its pid. ``SessionRecord::worker_alive``
      (``aplexer/src/lib.rs``) returns false for a ``None`` pid, so EVERY
      create passes through ``phase: starting`` / ``worker_alive: false``
      for tens of milliseconds. Reading that as a corpse blanks the session
      the user is watching appear (the #2547 symptom), refuses to attach to
      it, and makes a retried create force-forget an in-flight worker. With
      no pid, ``worker_alive: false`` is not evidence of death — it is
      absence of evidence, which aplexer's own ``display_state`` calls
      "broken" (indeterminate), not "exited".

      That exemption is bounded by :data:`APLEXER_STARTING_GRACE_MS`,
      because an ``a start`` KILLED inside the pre-PID window leaves exactly
      this shape forever. The two are indistinguishable in one snapshot but
      not in time: a real create is tens of milliseconds old, while a
      crashed one keeps its original ``updated_at_ms`` and ages without
      bound. Without the bound, that permanent corpse is listed as an
      attachable row whose attach exits 1 and is handed back by
      ``sessions create`` as an existing session — this issue's two reported
      symptoms, through a different door.

    Fails OPEN on every indeterminate shape: a record carrying no liveness
    keys at all, and a pre-PID record whose ``updated_at_ms`` is missing or
    unreadable (no age, no verdict). The cost of a stale row is a confusing
    tap, while the cost of a wrong "dead" verdict is a live session the user
    cannot reach — and, on the create path, one that gets force-forgotten.
    """
    if aplexer_phase(raw) in APLEXER_TERMINAL_PHASES:
        return False
    if _aplexer_worker_alive_flag(raw):
        return True
    # Only a worker that registered a pid can have provably lost it.
    if aplexer_worker_pid(raw) is not None:
        return False
    return _aplexer_within_starting_grace(raw, now_ms)


def _aplexer_within_starting_grace(
    raw: Mapping[str, Any], now_ms: Optional[int]
) -> bool:
    """Whether a pre-PID record is young enough to still be starting."""
    updated = _coerce_ms(raw.get("updated_at_ms"))
    if updated is None:
        return True  # no age, no verdict
    if now_ms is None:
        now_ms = int(time.time() * 1000)
    # A clock skew that puts the record in the future reads as brand new,
    # which is the fail-open direction.
    return now_ms - updated <= APLEXER_STARTING_GRACE_MS


def aplexer_worker_pid(raw: Mapping[str, Any]) -> Optional[int]:
    """The record's worker pid, or ``None`` when it has not registered one.

    Absent and ``null`` mean the same thing here: aplexer omits the key
    entirely on a record whose worker has not started yet (verified on a
    real mid-``a start`` capture, ``snapshot-mid-create.json``).
    """
    try:
        pid = int(raw["worker_pid"])
    except (KeyError, TypeError, ValueError):
        return None
    return pid if pid > 0 else None


def _aplexer_worker_alive_flag(raw: Mapping[str, Any]) -> bool:
    """``worker_alive`` as a bool; a missing key reads as alive (fail open)."""
    worker_alive = raw.get("worker_alive")
    if isinstance(worker_alive, bool):
        return worker_alive
    if isinstance(worker_alive, int):
        return worker_alive > 0
    return True


def _aplexer_rows(payload: Any, now_ms: Optional[int]) -> list[LiveSession]:
    """Every snapshot record as a :class:`LiveSession`, liveness included."""
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
        attach = f"a attach {ident}" if ident else None
        state, state_source = aplexer_agent_state(raw, now_ms)
        rows.append(
            LiveSession(
                name=name,
                manager=MANAGER_APLEXER,
                created=_format_epoch(epoch),
                created_epoch=epoch,
                workspace=str(workspace) if workspace else None,
                tag=str(tag) if tag else None,
                engine=_aplexer_engine(raw),
                profile=str(profile) if profile else None,
                aplexer_id=ident or None,
                attach=attach,
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
    """The ATTACHABLE aplexer rows of a snapshot (issue #2554).

    A dead record is not a session with a flag on it — it is a leftover, and
    presenting it as an ordinary row is what put an un-attachable
    `pocketshell:pocketshell` in the maintainer's tree. Use
    :func:`dead_sessions_from_aplexer_snapshot` when the leftovers are the
    point (explaining a stale attach, say).
    """
    return [row for row in _aplexer_rows(payload, now_ms) if row.alive]


def dead_sessions_from_aplexer_snapshot(
    payload: Any, *, now_ms: Optional[int] = None
) -> list[LiveSession]:
    """The rows :func:`sessions_from_aplexer_snapshot` refuses to list.

    Each carries its ``phase`` and ``alive=False``, so a caller can say what
    happened to a name instead of handing it to ``a attach`` and relaying a
    bare exit 1.
    """
    return [row for row in _aplexer_rows(payload, now_ms) if not row.alive]


# ---------------------------------------------------------------------------
# tmux enrichment sweep
# ---------------------------------------------------------------------------
#
# ``tmuxctl list`` prints only NAME + CREATED. Schema 2 also promises
# ``workspace``/``attached``/``activity_epoch`` for tmux rows, which only the
# tmux server itself knows, so the JSON path asks each session's own server
# for them. tmuxctl runs one server per session on ``tmuxctl-<name>``, so
# this is one cheap targeted call per listed session (~5 ms each on the dev
# box) rather than a sweep of the socket directory, which holds hundreds of
# dead sockets from past test runs.


@dataclass(frozen=True)
class TmuxSessionDetail:
    """Per-session fields read straight off a tmux server."""

    name: str
    workspace: Optional[str] = None
    attached: bool = False
    created_epoch: Optional[int] = None
    activity_epoch: Optional[int] = None


def tmux_socket_dir(env: Optional[Mapping[str, str]] = None) -> Path:
    """``${TMUX_TMPDIR:-/tmp}/tmux-<uid>`` — tmux's own socket directory."""
    source = aplexer.env_map(env)
    base = source.get("TMUX_TMPDIR") or "/tmp"
    return Path(base) / f"tmux-{os.getuid()}"


def parse_tmux_detail_lines(stdout: str) -> dict[str, TmuxSessionDetail]:
    """Parse ``list-sessions -F TMUX_DETAIL_FORMAT`` output, keyed by name."""
    details: dict[str, TmuxSessionDetail] = {}
    for line in stdout.splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) < 5:
            continue
        name = parts[0].strip()
        if not name:
            continue
        workspace = parts[1].strip() or None
        attached = (_epoch_from_seconds(parts[2]) or 0) > 0
        details[name] = TmuxSessionDetail(
            name=name,
            workspace=workspace,
            attached=attached,
            created_epoch=_epoch_from_seconds(parts[3]),
            activity_epoch=_epoch_from_seconds(parts[4]),
        )
    return details


def _default_tmux_runner(argv: Sequence[str]) -> tuple[int, str, str]:
    try:
        completed = subprocess.run(
            list(argv),
            check=False,
            capture_output=True,
            text=True,
            timeout=TMUX_DETAIL_TIMEOUT_S,
        )
    except subprocess.TimeoutExpired:
        return 124, "", "timed out"
    return completed.returncode, completed.stdout, completed.stderr


def collect_tmux_details(
    names: Iterable[str],
    *,
    env: Optional[Mapping[str, str]] = None,
    runner: Optional[TmuxRunner] = None,
) -> tuple[dict[str, TmuxSessionDetail], list[dict[str, str]]]:
    """Ask each session's tmux server for workspace/attached/activity.

    A session whose server cannot be reached is simply not enriched (its row
    still lists, with null fields) — that is a degraded field, not a failed
    enumeration. A missing/unusable ``tmux`` binary IS an enumeration error
    and returns one ``errors`` entry.
    """
    details: dict[str, TmuxSessionDetail] = {}
    errors: list[dict[str, str]] = []
    pending = [name for name in dict.fromkeys(names) if name]
    if not pending:
        return details, errors
    run = runner or _default_tmux_runner
    socket_dir = tmux_socket_dir(env)
    if not socket_dir.is_dir():
        return details, errors
    try:
        for name in pending:
            socket_path = socket_dir / f"{TMUXCTL_SOCKET_PREFIX}{name}"
            code, stdout, _stderr = run(
                ["tmux", "-S", str(socket_path), "list-sessions", "-F", TMUX_DETAIL_FORMAT]
            )
            if code != 0:
                continue
            for parsed_name, detail in parse_tmux_detail_lines(stdout).items():
                details.setdefault(parsed_name, detail)
        if any(name not in details for name in pending):
            # Sessions not created by tmuxctl (or created before it owned the
            # name) still live on the shared default socket; one extra call
            # covers all of them.
            code, stdout, _stderr = run(
                [
                    "tmux",
                    "-S",
                    str(socket_dir / TMUX_DEFAULT_SOCKET),
                    "list-sessions",
                    "-F",
                    TMUX_DETAIL_FORMAT,
                ]
            )
            if code == 0:
                for parsed_name, detail in parse_tmux_detail_lines(stdout).items():
                    details.setdefault(parsed_name, detail)
    except OSError as exc:
        errors.append(
            enumeration_error(
                MANAGER_TMUX,
                f"tmux session details unavailable: {exc}",
            )
        )
    return details, errors


def _merge_tmux_detail(
    row: LiveSession, detail: Optional[TmuxSessionDetail]
) -> LiveSession:
    if detail is None:
        return row
    return replace(
        row,
        workspace=detail.workspace or row.workspace,
        attached=detail.attached,
        created_epoch=detail.created_epoch or row.created_epoch,
        activity_epoch=detail.activity_epoch or row.activity_epoch,
    )


def _probe_aplexer(
    env: Optional[Mapping[str, str]],
) -> tuple[Any, Optional[str]]:
    """Return ``(payload, error_message)`` for the aplexer backend.

    "aplexer is not on this host" and "aplexer is switched off" are not
    errors — they describe a tmux-only host. "``a`` is installed and its
    probe failed" IS one, and must reach the caller's ``errors`` list rather
    than degrade into an aplexer-shaped hole in the session list (#2426).
    """
    if not aplexer.enabled("sessions", env):
        return None, None
    binary = aplexer.which_a(env)
    if binary is None:
        return None, None
    payload = aplexer.run_json(["snapshot"], env=env, feature="sessions")
    if payload is None:
        payload = aplexer.run_json(["list"], env=env, feature="sessions")
    if payload is None:
        return None, (
            f"`{binary} --json snapshot` and `{binary} --json list` both "
            "failed or returned unreadable JSON; aplexer sessions are "
            "missing from this listing"
        )
    if not isinstance(payload, list):
        return None, (
            f"`{binary} --json snapshot` returned "
            f"{type(payload).__name__}, expected a list of session records"
        )
    return payload, None


def enumerate_live_sessions(
    *,
    tmuxctl_stdout: Optional[str] = None,
    tmuxctl_error: Optional[str] = None,
    aplexer_payload: Any = None,
    include_aplexer: bool = True,
    env: Optional[Mapping[str, str]] = None,
    enrich_tmux: bool = False,
    tmux_detail_runner: Optional[TmuxRunner] = None,
    now_ms: Optional[int] = None,
) -> tuple[list[LiveSession], list[dict[str, str]]]:
    """Union tmuxctl names with aplexer snapshot rows.

    Returns ``(rows, errors)``. ``tmuxctl_stdout`` is the already-captured
    ``tmuxctl list`` table (or None to skip tmux); ``tmuxctl_error`` is the
    message to report when that capture failed. ``aplexer_payload`` injects
    a snapshot for tests; production probes ``a snapshot`` / ``a list
    --json`` when enabled. A tmux-only host (no ``a`` or kill switch)
    returns only tmux rows and no errors — but a *failing* backend always
    appends an ``errors`` entry, never just a shorter list (#2426).

    ``enrich_tmux`` additionally asks each tmux session's own server for
    workspace/attached/activity (schema 2's tmux fields); the human-table
    path leaves it off so it costs nothing there.

    Only LIVE aplexer records are unioned in: aplexer keeps a record after
    its session dies, and listing those made the tree offer rows that attach
    with exit 1 (issue #2554, :func:`aplexer_record_is_alive`). Every row
    this returns is therefore ``alive``.
    """
    errors: list[dict[str, str]] = []
    tmux_rows = (
        sessions_from_tmuxctl_table(tmuxctl_stdout) if tmuxctl_stdout else []
    )
    if tmuxctl_error:
        errors.append(enumeration_error(MANAGER_TMUX, tmuxctl_error))
    if enrich_tmux and tmux_rows:
        details, detail_errors = collect_tmux_details(
            [row.name for row in tmux_rows],
            env=env,
            runner=tmux_detail_runner,
        )
        errors.extend(detail_errors)
        tmux_rows = [
            _merge_tmux_detail(row, details.get(row.name)) for row in tmux_rows
        ]

    aplexer_rows: list[LiveSession] = []
    if include_aplexer:
        payload = aplexer_payload
        if payload is None:
            payload, aplexer_error = _probe_aplexer(env)
            if aplexer_error:
                errors.append(enumeration_error(MANAGER_APLEXER, aplexer_error))
        aplexer_rows = sessions_from_aplexer_snapshot(payload, now_ms=now_ms)

    tmux_names = {row.name for row in tmux_rows}
    combined = list(tmux_rows)
    for row in aplexer_rows:
        if row.name in tmux_names:
            continue
        combined.append(row)
    return combined, errors


def json_payload(
    sessions: Sequence[LiveSession],
    errors: Sequence[Mapping[str, str]] = (),
) -> dict[str, Any]:
    """The schema-2 ``sessions list --json`` document.

    ``managers`` lists the managers that actually contributed rows; a
    backend that was tried and failed shows up in ``errors`` instead, so an
    empty-but-broken manager is never mistaken for an empty-and-healthy one.
    """
    managers = []
    for row in sessions:
        if row.manager not in managers:
            managers.append(row.manager)
    if not managers:
        managers = [MANAGER_TMUX]
    return {
        "schema": SCHEMA_VERSION,
        "managers": managers,
        "sessions": [row.to_payload(schema=SCHEMA_VERSION) for row in sessions],
        "errors": [dict(error) for error in errors],
    }


def format_aplexer_table(sessions: Sequence[LiveSession]) -> str:
    """Human appendix so ``pocketshell sessions list`` shows both managers."""
    aplexer_rows = [row for row in sessions if row.manager == MANAGER_APLEXER]
    if not aplexer_rows:
        return ""
    lines = ["", "APLEXER", "IDX  SESSION               CREATED"]
    for index, row in enumerate(aplexer_rows, start=1):
        created = row.created or ""
        lines.append(f"{index:<5}{row.name} {created}".rstrip())
    return "\n".join(lines) + "\n"
