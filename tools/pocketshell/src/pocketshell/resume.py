"""Discovery + resume helpers for `pocketshell sessions resumable|resume`.

Slice 1 (host-side backbone) of issue
[#725](https://github.com/alexeygrigorev/pocketshell/issues/725): enumerate
resumable AI-CLI conversations across the three engines the maintainer uses —
Claude (`claude`), Codex (`codex`), and OpenCode (`opencode`) — and resume a
selected one inside a **memory-capped** tmux session (via `tmuxctl
create-or-attach --mem`, `cd`-ing to the session's recorded cwd first).

Design notes (kept pure + testable here; the Click wiring lives in
:mod:`pocketshell.sessions`):

- **claude / codex** store one JSONL file per session. The *active* file can
  be tens of MB, so we read only the **head** (a small line budget) to extract
  the label and never the whole file; last-activity comes from the file
  **mtime**, not from parsing the tail.
- **codex** spawns worker rollouts tagged ``thread_source: "subagent"`` in the
  ``session_meta`` record — those are filtered out so the list only shows the
  user-facing conversations.
- **opencode** keeps sessions in a SQLite DB whose live WAL must never be
  locked, so it is opened **read-only / immutable**
  (``file:...?mode=ro&immutable=1``). Its binary is off-PATH, so the resume
  builder resolves it via the npm-global fallback.
- **live-dedupe**: a discovered session whose ``(engine-command, cwd)`` matches
  a currently-running tmux pane is marked ``running`` and is not offered for
  resume (respects #666 — never resurrect / double-attach a live session).

Per D22 (hard-cut, no backwards-compat shim) this is the canonical discovery
path; there is no legacy fallback.
"""

from __future__ import annotations

import glob
import json
import os
import shutil
import sqlite3
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Optional

# How many leading JSONL lines we are willing to scan when hunting for the
# label / cwd in a claude or codex transcript. The session-establishing
# records (and the user's first real prompt) live at the very top; the active
# transcript can be ~38 MB, so this cap keeps discovery O(1) per file.
_HEAD_LINE_BUDGET = 200

# Engines, in the stable display order used for tie-breaking equal timestamps.
ENGINES = ("claude", "codex", "opencode")


@dataclass(frozen=True)
class ResumableSession:
    """A single resumable AI-CLI conversation discovered on the host.

    ``last_activity`` is a POSIX timestamp (float seconds). ``running`` is set
    by :func:`mark_running` once live tmux panes are known; freshly discovered
    sessions are ``running=False``.
    """

    engine: str
    session_id: str
    cwd: str
    last_activity: float
    label: str
    running: bool = False

    @property
    def project(self) -> str:
        """Project name shown in the list (basename of the recorded cwd)."""
        return os.path.basename(self.cwd.rstrip("/")) or self.cwd

    def with_running(self, running: bool) -> "ResumableSession":
        """Return a copy with the ``running`` flag toggled (frozen dataclass)."""
        if running == self.running:
            return self
        return ResumableSession(
            engine=self.engine,
            session_id=self.session_id,
            cwd=self.cwd,
            last_activity=self.last_activity,
            label=self.label,
            running=running,
        )


# ---------------------------------------------------------------------------
# Label / record helpers (pure)
# ---------------------------------------------------------------------------


def _clean_label(text: str, *, max_len: int = 80) -> str:
    """Collapse whitespace + truncate a label so the table stays one line."""
    collapsed = " ".join(text.split())
    if len(collapsed) > max_len:
        return collapsed[: max_len - 1].rstrip() + "…"
    return collapsed


def _is_wrapper_text(text: str) -> bool:
    """A first-message candidate is a wrapper if it is empty or starts ``<``.

    Both claude (``<command-name>`` etc.) and codex
    (``<environment_context>`` / ``<permissions instructions>``) inject XML-ish
    wrapper turns before the user's real first prompt; those are skipped.
    """
    stripped = text.strip()
    return not stripped or stripped.startswith("<")


def _is_codex_wrapper_text(text: str) -> bool:
    """codex injects more than the XML wrappers ``_is_wrapper_text`` catches.

    Project instructions (AGENTS.md) are replayed as a ``role: user`` message
    that begins ``# AGENTS.md instructions for`` and wraps the body in
    ``<INSTRUCTIONS>…</INSTRUCTIONS>``. That is not the user's real first
    prompt, so it is filtered here on top of the base XML-wrapper check.
    """
    if _is_wrapper_text(text):
        return True
    stripped = text.strip()
    if stripped.startswith("# AGENTS.md instructions for"):
        return True
    if "<INSTRUCTIONS>" in stripped or "<user_instructions>" in stripped:
        return True
    return False


def _iter_head_records(path: Path, *, line_budget: int = _HEAD_LINE_BUDGET) -> Iterable[dict]:
    """Yield up to ``line_budget`` parsed JSON objects from the file head.

    Malformed / non-object lines are skipped silently. Reads line-by-line so a
    multi-megabyte transcript never loads fully into memory.
    """
    count = 0
    try:
        with path.open("r", encoding="utf-8", errors="replace") as handle:
            for line in handle:
                if count >= line_budget:
                    return
                count += 1
                line = line.strip()
                if not line:
                    continue
                try:
                    record = json.loads(line)
                except (json.JSONDecodeError, ValueError):
                    continue
                if isinstance(record, dict):
                    yield record
    except OSError:
        return


# ---------------------------------------------------------------------------
# claude discovery
# ---------------------------------------------------------------------------


def claude_projects_dir() -> Path:
    return Path.home() / ".claude" / "projects"


def _claude_extract(path: Path) -> Optional[ResumableSession]:
    """Build a session from a single claude ``<uuid>.jsonl`` transcript head."""
    session_id = path.stem
    cwd: Optional[str] = None
    label = ""
    for record in _iter_head_records(path):
        if record.get("type") != "user":
            continue
        if cwd is None and isinstance(record.get("cwd"), str):
            cwd = record["cwd"]
        message = record.get("message")
        text: Optional[str] = None
        if isinstance(message, str):
            text = message
        elif isinstance(message, dict):
            content = message.get("content")
            if isinstance(content, str):
                text = content
            elif isinstance(content, list):
                for block in content:
                    if isinstance(block, dict) and isinstance(block.get("text"), str):
                        text = block["text"]
                        break
        if text is not None and not label and not _is_wrapper_text(text):
            label = _clean_label(text)
        if cwd is not None and label:
            break
    if cwd is None:
        return None
    try:
        mtime = path.stat().st_mtime
    except OSError:
        return None
    return ResumableSession(
        engine="claude",
        session_id=session_id,
        cwd=cwd,
        last_activity=mtime,
        label=label or "(no prompt)",
    )


def discover_claude(projects_dir: Optional[Path] = None) -> list[ResumableSession]:
    """Discover claude sessions across every project under ``projects_dir``.

    Returns an empty list when the store does not exist (no crash).
    """
    base = projects_dir or claude_projects_dir()
    if not base.is_dir():
        return []
    sessions: list[ResumableSession] = []
    for path in base.glob("*/*.jsonl"):
        session = _claude_extract(path)
        if session is not None:
            sessions.append(session)
    return sessions


# ---------------------------------------------------------------------------
# codex discovery
# ---------------------------------------------------------------------------


def codex_sessions_dir() -> Path:
    return Path.home() / ".codex" / "sessions"


def _codex_extract(path: Path) -> Optional[ResumableSession]:
    """Build a session from a codex ``rollout-*.jsonl`` head.

    Returns ``None`` for worker rollouts (``thread_source == "subagent"``) and
    for files without a usable ``session_meta`` record.
    """
    session_id: Optional[str] = None
    cwd: Optional[str] = None
    label = ""
    for record in _iter_head_records(path):
        rtype = record.get("type")
        if rtype == "session_meta":
            payload = record.get("payload")
            if not isinstance(payload, dict):
                continue
            if payload.get("thread_source") == "subagent":
                return None
            if isinstance(payload.get("id"), str):
                session_id = payload["id"]
            if isinstance(payload.get("cwd"), str):
                cwd = payload["cwd"]
            continue
        if label:
            continue
        # First real user prompt: a response_item message with role=user whose
        # text does not start with `<` (skips the env/permissions wrappers).
        if rtype == "response_item":
            payload = record.get("payload")
            if not isinstance(payload, dict):
                continue
            if payload.get("type") != "message" or payload.get("role") != "user":
                continue
            content = payload.get("content")
            text: Optional[str] = None
            if isinstance(content, list):
                for block in content:
                    if isinstance(block, dict) and isinstance(block.get("text"), str):
                        text = block["text"]
                        break
            elif isinstance(content, str):
                text = content
            if text is not None and not _is_codex_wrapper_text(text):
                label = _clean_label(text)
    if session_id is None or cwd is None:
        return None
    try:
        mtime = path.stat().st_mtime
    except OSError:
        return None
    return ResumableSession(
        engine="codex",
        session_id=session_id,
        cwd=cwd,
        last_activity=mtime,
        label=label or "(no prompt)",
    )


def discover_codex(sessions_dir: Optional[Path] = None) -> list[ResumableSession]:
    """Discover codex sessions under ``~/.codex/sessions/YYYY/MM/DD/``.

    Returns an empty list when the store does not exist (no crash).
    """
    base = sessions_dir or codex_sessions_dir()
    if not base.is_dir():
        return []
    sessions: list[ResumableSession] = []
    # rollout files live under YYYY/MM/DD; recurse so future layout tweaks
    # (extra nesting) still match.
    for path in base.rglob("rollout-*.jsonl"):
        session = _codex_extract(path)
        if session is not None:
            sessions.append(session)
    return sessions


# ---------------------------------------------------------------------------
# opencode discovery
# ---------------------------------------------------------------------------


def opencode_db_path() -> Path:
    return Path.home() / ".local" / "share" / "opencode" / "opencode.db"


def discover_opencode(db_path: Optional[Path] = None) -> list[ResumableSession]:
    """Discover opencode sessions from the SQLite DB, opened read-only.

    The live DB uses WAL; opening with ``mode=ro&immutable=1`` guarantees we
    never take a lock or touch the WAL of the running opencode process. Returns
    an empty list when the DB is absent or has no ``session`` table.

    Child sessions (subagent runs) carry a non-null ``parent_id``; they are
    filtered out so only top-level user conversations are offered.
    """
    path = db_path or opencode_db_path()
    if not path.is_file():
        return []
    uri = f"file:{path}?mode=ro&immutable=1"
    sessions: list[ResumableSession] = []
    try:
        con = sqlite3.connect(uri, uri=True)
    except sqlite3.Error:
        return []
    try:
        try:
            cursor = con.execute(
                "SELECT id, directory, title, time_updated, parent_id "
                "FROM session"
            )
        except sqlite3.Error:
            return []
        for row in cursor.fetchall():
            session_id, directory, title, time_updated, parent_id = row
            if parent_id:
                continue
            if not isinstance(session_id, str) or not isinstance(directory, str):
                continue
            # time_updated is stored in milliseconds since the epoch.
            try:
                last_activity = float(time_updated) / 1000.0
            except (TypeError, ValueError):
                continue
            sessions.append(
                ResumableSession(
                    engine="opencode",
                    session_id=session_id,
                    cwd=directory,
                    last_activity=last_activity,
                    label=_clean_label(title) if isinstance(title, str) else "",
                )
            )
    finally:
        con.close()
    return sessions


# ---------------------------------------------------------------------------
# merge / sort / filter
# ---------------------------------------------------------------------------


def _engine_order(engine: str) -> int:
    try:
        return ENGINES.index(engine)
    except ValueError:
        return len(ENGINES)


def merge_sessions(
    *,
    sessions: Iterable[ResumableSession],
    cwd: Optional[str] = None,
    engine: Optional[str] = None,
    limit: Optional[int] = None,
) -> list[ResumableSession]:
    """Filter (by cwd / engine) then sort newest-first, then apply ``limit``.

    ``cwd`` restricts to a single project directory (exact match after
    normalising trailing slashes); ``None`` / the ``--all`` case spans every
    project. ``engine`` restricts to one engine. Ties on ``last_activity`` are
    broken by engine display order then session id, so the order is stable.
    """
    norm_cwd = cwd.rstrip("/") if cwd else None
    filtered: list[ResumableSession] = []
    for session in sessions:
        if engine is not None and session.engine != engine:
            continue
        if norm_cwd is not None and session.cwd.rstrip("/") != norm_cwd:
            continue
        filtered.append(session)
    filtered.sort(
        key=lambda s: (-s.last_activity, _engine_order(s.engine), s.session_id)
    )
    if limit is not None and limit >= 0:
        return filtered[:limit]
    return filtered


def discover_all(
    *,
    claude_dir: Optional[Path] = None,
    codex_dir: Optional[Path] = None,
    opencode_db: Optional[Path] = None,
) -> list[ResumableSession]:
    """Run all three discovery passes and concatenate (unsorted, unfiltered)."""
    return [
        *discover_claude(claude_dir),
        *discover_codex(codex_dir),
        *discover_opencode(opencode_db),
    ]


# ---------------------------------------------------------------------------
# resume-command builders
# ---------------------------------------------------------------------------


def resolve_opencode_binary() -> Optional[str]:
    """Locate the opencode binary.

    On the maintainer's host opencode is installed as an npm global and is NOT
    on PATH, so PATH lookup is tried first and then the npm-global fallback
    ``~/.nvm/versions/node/*/lib/node_modules/opencode-ai/bin/opencode``
    (newest node version wins).
    """
    on_path = shutil.which("opencode")
    if on_path:
        return on_path
    pattern = str(
        Path.home()
        / ".nvm"
        / "versions"
        / "node"
        / "*"
        / "lib"
        / "node_modules"
        / "opencode-ai"
        / "bin"
        / "opencode"
    )
    matches = sorted(glob.glob(pattern))
    if matches:
        return matches[-1]
    return None


def resume_argv(session: ResumableSession) -> list[str]:
    """Build the engine-native resume argv (NOT yet wrapped for tmux/cd).

    Raises ``RuntimeError`` for opencode when its binary cannot be resolved.
    """
    if session.engine == "claude":
        return ["claude", "--resume", session.session_id]
    if session.engine == "codex":
        return ["codex", "resume", session.session_id]
    if session.engine == "opencode":
        binary = resolve_opencode_binary()
        if binary is None:
            raise RuntimeError(
                "opencode binary not found on PATH or under "
                "~/.nvm/.../opencode-ai/bin; cannot resume an opencode session."
            )
        return [binary, "--session", session.session_id]
    raise RuntimeError(f"unknown engine: {session.engine!r}")


def _shell_quote(value: str) -> str:
    """Single-quote a value for safe interpolation into a `bash -lc` string."""
    return "'" + value.replace("'", "'\\''") + "'"


def resume_shell_command(session: ResumableSession) -> str:
    """Build the `bash -lc` body that cd's to the cwd and execs the engine.

    ``exec`` replaces the shell so the engine owns the tmux pane directly; the
    string is handed to tmuxctl as the session's create command.
    """
    argv = resume_argv(session)
    quoted_engine = " ".join(_shell_quote(part) for part in argv)
    return f"cd {_shell_quote(session.cwd)} && exec {quoted_engine}"


def tmux_session_name(session: ResumableSession) -> str:
    """Derive a stable, tmux-safe session name for a resumed conversation.

    tmux session names cannot contain ``.`` or ``:`` and whitespace is
    awkward; sanitise to ``[A-Za-z0-9_-]``. Shape: ``resume-<engine>-<project>-<idfrag>``
    so repeated resumes of the same conversation reuse (attach) the same tmux
    session rather than spawning duplicates.
    """
    project = _sanitize_name(session.project) or "proj"
    id_frag = _sanitize_name(session.session_id)[:8] or "id"
    return f"resume-{session.engine}-{project}-{id_frag}"


def _sanitize_name(value: str) -> str:
    out = []
    for ch in value:
        if ch.isalnum() or ch in "_-":
            out.append(ch)
        else:
            out.append("-")
    # Collapse runs of '-' and trim.
    collapsed = "-".join(part for part in "".join(out).split("-") if part)
    return collapsed


def tmuxctl_resume_argv(
    session: ResumableSession,
    *,
    tmuxctl_path: str,
    mem: str,
) -> list[str]:
    """Full argv to launch a capped resume via ``tmuxctl create-or-attach``.

    Shape: ``<tmuxctl> create-or-attach <name> --mem <mem> -- bash -lc '<cd && exec>'``.
    The ``--`` separates tmuxctl's own options from the COMMAND it should run
    when creating the session.
    """
    return [
        tmuxctl_path,
        "create-or-attach",
        tmux_session_name(session),
        "--mem",
        mem,
        "--",
        "bash",
        "-lc",
        resume_shell_command(session),
    ]


def tmuxctl_create_argv(
    name: str,
    *,
    tmuxctl_path: str,
    cwd: Optional[str] = None,
    mem: Optional[str] = None,
) -> list[str]:
    """Build the argv for a memory-capped, DETACHED session create.

    Delegates to ``tmuxctl create-detached`` (tmuxctl >= 0.3.0), which wraps the
    session shell in tmuxctl's cgroup-v2 systemd ``--user`` scope under
    ``robust.slice`` and is idempotent (a no-op if the session already exists).
    This is the host-side primitive PocketShell calls instead of building raw
    ``tmux new-session -d`` strings, so sessions it starts can never trigger the
    OOM-kill cascade that wipes the agent team.

    Shape: ``<tmuxctl> create-detached <name> [-c <cwd>] [--mem <mem>]``.

    ``--mem`` is **omitted by default** so tmuxctl resolves the per-project cap
    from the repo's ``cgroups.toml`` (PocketShell's is 30G). Only when the caller
    explicitly passes ``mem`` is it forwarded; hard-coding a cap here would
    override the committed per-project policy. Unlike :func:`tmuxctl_resume_argv`,
    this builder does NOT default ``--mem``.
    """
    argv = [tmuxctl_path, "create-detached", name]
    if cwd is not None:
        argv.extend(["-c", cwd])
    if mem is not None:
        argv.extend(["--mem", mem])
    return argv


# ---------------------------------------------------------------------------
# live-pane dedupe
# ---------------------------------------------------------------------------


# The engine command as it appears in tmux's `#{pane_current_command}` for each
# engine. opencode's wrapper execs `node`/`bun`; claude and codex run under
# their own process names. We match conservatively on cwd + a per-engine
# command hint so a live conversation in the same directory is flagged.
_ENGINE_PANE_COMMANDS = {
    "claude": ("claude", "node"),
    "codex": ("codex",),
    "opencode": ("opencode", "node", "bun"),
}


def list_live_panes(tmux_path: Optional[str] = None) -> list[tuple[str, str]]:
    """Return ``(pane_current_path, pane_current_command)`` for every pane.

    Uses ``tmux list-panes -a``. Returns an empty list when tmux is absent or
    no server is running (no crash).
    """
    binary = tmux_path or shutil.which("tmux")
    if binary is None:
        return []
    try:
        completed = subprocess.run(
            [binary, "list-panes", "-a", "-F", "#{pane_current_path}\t#{pane_current_command}"],
            check=False,
            capture_output=True,
            text=True,
        )
    except OSError:
        return []
    if completed.returncode != 0:
        return []
    panes: list[tuple[str, str]] = []
    for line in completed.stdout.splitlines():
        if "\t" not in line:
            continue
        path, _, command = line.partition("\t")
        panes.append((path.rstrip("/"), command.strip()))
    return panes


def _session_is_live(
    session: ResumableSession, panes: Iterable[tuple[str, str]]
) -> bool:
    cwd = session.cwd.rstrip("/")
    hints = _ENGINE_PANE_COMMANDS.get(session.engine, (session.engine,))
    for pane_path, pane_command in panes:
        if pane_path != cwd:
            continue
        if any(hint in pane_command for hint in hints):
            return True
    return False


def mark_running(
    sessions: Iterable[ResumableSession],
    panes: Iterable[tuple[str, str]],
) -> list[ResumableSession]:
    """Flag sessions whose cwd+engine matches a live tmux pane as ``running``."""
    pane_list = list(panes)
    return [
        session.with_running(_session_is_live(session, pane_list))
        for session in sessions
    ]


# ---------------------------------------------------------------------------
# selector resolution + relative-time formatting (display helpers)
# ---------------------------------------------------------------------------


def select_session(
    sessions: list[ResumableSession], selector: str
) -> Optional[ResumableSession]:
    """Resolve a user selector to one session.

    Accepts a 1-based list index (matching the printed order) or a session-id
    (exact, or unambiguous prefix). Returns ``None`` if nothing matches or a
    prefix is ambiguous.
    """
    selector = selector.strip()
    if not selector:
        return None
    if selector.isdigit():
        idx = int(selector) - 1
        if 0 <= idx < len(sessions):
            return sessions[idx]
        return None
    exact = [s for s in sessions if s.session_id == selector]
    if exact:
        return exact[0]
    prefix = [s for s in sessions if s.session_id.startswith(selector)]
    if len(prefix) == 1:
        return prefix[0]
    return None


def format_relative(timestamp: float, *, now: Optional[float] = None) -> str:
    """Format a POSIX timestamp as a compact relative string (e.g. ``3h``)."""
    current = time.time() if now is None else now
    delta = max(0.0, current - timestamp)
    minutes = delta / 60.0
    if minutes < 1:
        return "just now"
    if minutes < 60:
        return f"{int(minutes)}m"
    hours = minutes / 60.0
    if hours < 24:
        return f"{int(hours)}h"
    days = hours / 24.0
    if days < 30:
        return f"{int(days)}d"
    months = days / 30.0
    if months < 12:
        return f"{int(months)}mo"
    return f"{int(days / 365.0)}y"
