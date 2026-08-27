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

import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping, Optional, Sequence

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
    aplexer_id: Optional[str] = None
    attach: Optional[str] = None
    extra: Mapping[str, Any] = field(default_factory=dict)

    def to_payload(self) -> dict[str, Any]:
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
                attach=f"tmux attach -t {name}",
            )
        )
    return rows


def sessions_from_aplexer_snapshot(payload: Any) -> list[LiveSession]:
    if not isinstance(payload, list):
        return []
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
        engine = raw.get("engine")
        attach = f"a attach {ident}" if ident else None
        rows.append(
            LiveSession(
                name=name,
                manager=MANAGER_APLEXER,
                created=_format_epoch(epoch),
                created_epoch=epoch,
                workspace=str(workspace) if workspace else None,
                tag=str(tag) if tag else None,
                engine=str(engine) if engine else None,
                aplexer_id=ident or None,
                attach=attach,
            )
        )
    return rows


def enumerate_live_sessions(
    *,
    tmuxctl_stdout: Optional[str] = None,
    aplexer_payload: Any = None,
    include_aplexer: bool = True,
    env: Optional[Mapping[str, str]] = None,
) -> list[LiveSession]:
    """Union tmuxctl names with aplexer snapshot rows.

    ``tmuxctl_stdout`` is the already-captured ``tmuxctl list`` table (or
    None to skip tmux). ``aplexer_payload`` injects a snapshot for tests;
    production probes ``a snapshot`` / ``a list --json`` when enabled.
    A tmux-only host (no ``a``, kill switch, or probe failure) returns
    only tmux rows.
    """
    tmux_rows = (
        sessions_from_tmuxctl_table(tmuxctl_stdout) if tmuxctl_stdout else []
    )
    aplexer_rows: list[LiveSession] = []
    if include_aplexer:
        payload = aplexer_payload
        if payload is None:
            payload = aplexer.run_json(["snapshot"], env=env, feature="sessions")
            if payload is None:
                payload = aplexer.run_json(["list"], env=env, feature="sessions")
        aplexer_rows = sessions_from_aplexer_snapshot(payload)
    tmux_names = {row.name for row in tmux_rows}
    combined = list(tmux_rows)
    for row in aplexer_rows:
        if row.name in tmux_names:
            continue
        combined.append(row)
    return combined


def json_payload(sessions: Sequence[LiveSession]) -> dict[str, Any]:
    managers = []
    for row in sessions:
        if row.manager not in managers:
            managers.append(row.manager)
    if not managers:
        managers = [MANAGER_TMUX]
    return {
        "sessions": [row.to_payload() for row in sessions],
        "managers": managers,
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
