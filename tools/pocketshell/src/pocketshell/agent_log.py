"""`pocketshell agent-log` subcommand.

Mirrors the per-engine JSONL conversation-log reads the Android app
currently runs over SSH (see ``AgentConversationRepository`` in
``app/src/main/java/com/pocketshell/app/session/``). The maintainer's
2026-05-27 note on issue #170 confirmed the strategy: no reimplementation
of the parsers themselves — this subcommand just reads the raw JSONL so
the Android client (and the planned IPC daemon, #219) can cache + serve
the same bytes the agent CLI wrote to disk.

Per-engine canonical paths (matching the Kotlin
``AgentConversationRepository.detectionCommand`` enumeration):

- **Claude Code**: ``~/.claude/projects/<encoded-cwd>/<session>.jsonl``.
  ``<encoded-cwd>`` is the working directory with ``/`` replaced by ``-``
  (see ``AgentDetector.encodeClaudeCwd``). When ``--cwd`` is omitted we
  walk every ``~/.claude/projects/*`` directory and pick the file whose
  basename matches ``<session>``.
- **Codex**: ``~/.codex/sessions/<YYYY>/<MM>/<DD>/<session>.jsonl``. The
  date partition is opaque to the caller, so we walk the subtree and
  match on basename.
- **OpenCode**: ``~/.local/share/opencode/<session>.jsonl``. OpenCode
  also persists state in ``opencode.db`` (SQLite), but the per-pane
  conversation feed the Android app consumes is the JSONL file (see
  ``OpenCodeReader`` for the row shape). The SQLite store is explicitly
  out of scope here — the brief calls for parity with the Kotlin reader,
  which only touches ``*.jsonl``.

Why direct file read instead of a subprocess delegation:

- There is no upstream CLI for these reads. The Android app reads them
  itself via ``ssh exec 'tail -n N <path>'``. There is no ``quse``- or
  ``tmuxctl``-shaped binary on the host to wrap; reimplementing the
  ``tail -n N`` step in Python is the smallest reasonable parity layer.
- The JSONL files are append-only, plain text, one event per line. No
  parsing logic from ``ClaudeCodeParser`` / ``CodexParser`` /
  ``OpenCodeReader`` is duplicated here — we emit raw lines verbatim.
  The Kotlin parsers keep owning the structural contract.
- ``--json`` wraps the same raw lines in a small envelope (``engine``,
  ``session``, ``path``, ``lines``, ``count``) so machine consumers
  (the planned daemon, integration tests) can pin to a stable shape
  without re-parsing the JSONL themselves.
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Iterable, List, Optional

import click

# Sentinel exit codes (mirrors the convention used by ``usage`` / ``jobs``):
#
# - 0   -> success; lines were read (possibly zero) and written out.
# - 2   -> bad invocation (e.g. unknown engine). Click handles this for
#          us via its ``BadParameter`` path; we only set it explicitly
#          when we need to bail out after Click validation.
# - 66  -> ``EX_NOINPUT`` from <sysexits.h>: the resolved log path does
#          not exist. Distinct from "command not found" (127) so a
#          daemon consumer can tell "session id is wrong" apart from
#          "binary is missing".
_EXIT_LOG_NOT_FOUND = 66


def _claude_projects_root() -> Path:
    """Root directory for Claude Code's per-cwd JSONL trees.

    Pulled out so the unit suite can monkeypatch HOME via the standard
    ``Path.home()`` mechanism without us hard-coding ``~`` expansion.
    """
    return Path.home() / ".claude" / "projects"


def _codex_sessions_root() -> Path:
    """Root directory for Codex's date-partitioned session JSONL tree."""
    return Path.home() / ".codex" / "sessions"


def _opencode_root() -> Path:
    """Root directory for OpenCode's JSONL conversation files.

    ``opencode.db`` (SQLite) also lives here but is not part of this
    reader — see module docstring.
    """
    return Path.home() / ".local" / "share" / "opencode"


def _encode_claude_cwd(cwd: str) -> str:
    """Mirror of ``AgentDetector.encodeClaudeCwd`` from core-agents.

    Replaces ``/`` with ``-`` and falls back to ``-`` for a blank input.
    Kept byte-identical so a ``--cwd /home/alexey/git/pocketshell``
    invocation here resolves to the same directory the Kotlin detector
    would pick on the same host.
    """
    trimmed = cwd.strip()
    if not trimmed:
        return "-"
    return trimmed.replace("/", "-")


def _ensure_jsonl_suffix(session: str) -> str:
    """Append ``.jsonl`` if the caller passed a bare session id.

    The Kotlin side stores ``sessionId`` as the path's basename without
    the ``.jsonl`` extension (see ``AgentConversationRepository.parseCandidate``),
    so users running ``pocketshell agent-log --session <id>`` after
    copy-pasting an id from the app will not have the extension. We
    accept both.
    """
    if session.endswith(".jsonl"):
        return session
    return f"{session}.jsonl"


def _resolve_claude_path(session: str, cwd: Optional[str]) -> Optional[Path]:
    """Resolve a Claude Code session to its JSONL file.

    When ``cwd`` is provided the lookup is direct:
    ``~/.claude/projects/<encoded-cwd>/<session>.jsonl``. When omitted
    we scan every ``~/.claude/projects/*/`` directory for a file whose
    basename matches; the first hit wins.

    Returns ``None`` if nothing matches.
    """
    filename = _ensure_jsonl_suffix(session)
    root = _claude_projects_root()
    if cwd is not None:
        candidate = root / _encode_claude_cwd(cwd) / filename
        return candidate if candidate.is_file() else None
    if not root.is_dir():
        return None
    for project_dir in sorted(root.iterdir()):
        if not project_dir.is_dir():
            continue
        candidate = project_dir / filename
        if candidate.is_file():
            return candidate
    return None


def _resolve_codex_path(session: str) -> Optional[Path]:
    """Resolve a Codex session to its JSONL file under ``~/.codex/sessions``.

    Codex partitions sessions by date (``<YYYY>/<MM>/<DD>/``), so we walk
    the tree rather than asking the user to supply the partition. The
    first basename match wins.
    """
    filename = _ensure_jsonl_suffix(session)
    root = _codex_sessions_root()
    if not root.is_dir():
        return None
    # ``Path.rglob`` returns matches in directory-walk order; the codex
    # tree is shallow (year/month/day/file) so this is cheap even with
    # months of history.
    for candidate in root.rglob(filename):
        if candidate.is_file():
            return candidate
    return None


def _resolve_opencode_path(session: str) -> Optional[Path]:
    """Resolve an OpenCode session to its JSONL file.

    OpenCode's conversation JSONLs live one level deep under
    ``~/.local/share/opencode/``. The SQLite ``opencode.db`` is ignored
    (see module docstring); only ``*.jsonl`` files are tailable.
    """
    filename = _ensure_jsonl_suffix(session)
    root = _opencode_root()
    if not root.is_dir():
        return None
    candidate = root / filename
    return candidate if candidate.is_file() else None


def _resolve_log_path(
    engine: str,
    session: str,
    cwd: Optional[str],
) -> Optional[Path]:
    """Dispatch to the per-engine resolver. Returns ``None`` on miss."""
    if engine == "claude":
        return _resolve_claude_path(session, cwd)
    if engine == "codex":
        # ``--cwd`` is accepted at the CLI for symmetry with claude but
        # ignored here — Codex partitions by date, not by working dir.
        return _resolve_codex_path(session)
    if engine == "opencode":
        return _resolve_opencode_path(session)
    # Click's ``type=Choice`` rejects unknown engines before we ever get
    # here; guard anyway so the function is total.
    return None


def _read_lines(path: Path) -> List[str]:
    """Read a JSONL file as a list of newline-stripped strings.

    Uses ``errors='replace'`` so a truncated last line (mid-write from a
    live agent) doesn't crash the read — the Android side already
    tolerates partial rows in its tail loop (``ConversationParser``
    swallows ``JsonReader`` exceptions per line).
    """
    with path.open("r", encoding="utf-8", errors="replace") as handle:
        # ``splitlines`` strips the trailing newline whether or not the
        # file ends with one; the JSONL contract is one event per line
        # so dropping the empty trailing element is correct.
        return handle.read().splitlines()


def _tail(lines: Iterable[str], n: Optional[int]) -> List[str]:
    """Return the last ``n`` items of ``lines`` (or all if ``n`` is None).

    Materialises ``lines`` if it is not already a list; the JSONL files
    are bounded in practice (a long Claude session is a few thousand
    lines, well under 100 MB) so loading into memory is acceptable and
    keeps the implementation small.
    """
    materialised = lines if isinstance(lines, list) else list(lines)
    if n is None or n <= 0 or n >= len(materialised):
        return list(materialised)
    return materialised[-n:]


def _emit_text(lines: List[str]) -> None:
    """Write each line followed by a newline to stdout.

    The output is byte-identical to ``tail -n N <path>`` for the same N
    (modulo a trailing newline on the last line, which ``tail``
    preserves and we re-add unconditionally). Downstream consumers that
    already parse JSONL line-by-line keep working.
    """
    for line in lines:
        sys.stdout.write(line)
        sys.stdout.write("\n")


def _emit_json(
    engine: str,
    session: str,
    path: Path,
    lines: List[str],
) -> None:
    """Emit a JSON envelope around the raw JSONL lines.

    Schema (pinned here so the daemon + the integration tests have a
    stable contract):

    ```json
    {
      "engine": "claude" | "codex" | "opencode",
      "session": "<basename without .jsonl>",
      "path": "/home/<user>/.claude/projects/.../foo.jsonl",
      "count": <int>,
      "lines": ["<raw json line 1>", "<raw json line 2>", ...]
    }
    ```

    ``lines`` holds the raw JSONL strings verbatim (NOT pre-parsed JSON
    objects). The Kotlin parsers stay authoritative for the structural
    contract; this envelope only proves provenance.
    """
    envelope = {
        "engine": engine,
        "session": session.removesuffix(".jsonl"),
        "path": str(path),
        "count": len(lines),
        "lines": lines,
    }
    # ``indent=None`` keeps the envelope on one line so a daemon
    # consumer can stream events without a multi-line parser. Sorted
    # keys make the output deterministic for golden-file tests.
    json.dump(envelope, sys.stdout, sort_keys=True)
    sys.stdout.write("\n")


@click.command(
    "agent-log",
    context_settings={"help_option_names": ["-h", "--help"]},
)
@click.option(
    "--session",
    "-s",
    "session",
    required=True,
    type=str,
    help=(
        "Session id — usually the JSONL file's basename. Accepts both "
        "`abc123` and `abc123.jsonl`."
    ),
)
@click.option(
    "--engine",
    "-e",
    "engine",
    required=True,
    type=click.Choice(["claude", "codex", "opencode"], case_sensitive=False),
    help="Which agent CLI's log to read.",
)
@click.option(
    "--cwd",
    "cwd",
    type=str,
    default=None,
    help=(
        "Working directory the agent was launched from. Only used for "
        "Claude Code (to pick the right `~/.claude/projects/<encoded>/` "
        "subdirectory). Ignored for codex and opencode."
    ),
)
@click.option(
    "--tail",
    "-n",
    "tail_count",
    type=click.IntRange(min=0),
    default=None,
    help="Emit only the last N events. Default: emit the whole file.",
)
@click.option(
    "--json",
    "json_output",
    is_flag=True,
    help="Emit a JSON envelope (`{engine, session, path, count, lines}`) instead of raw JSONL.",
)
@click.pass_context
def agent_log_command(
    ctx: click.Context,
    session: str,
    engine: str,
    cwd: Optional[str],
    tail_count: Optional[int],
    json_output: bool,
) -> None:
    """Print an agent JSONL conversation log.

    Reads the canonical per-engine path for the given session and emits
    the JSONL content either as raw lines (default) or wrapped in a JSON
    envelope (``--json``). ``--tail N`` bounds the output to the last N
    events.

    Exit codes:

    - 0  -> success.
    - 66 -> the resolved log path does not exist (wrong session id /
            wrong engine / agent has not written anything yet).
    - 2  -> bad invocation (handled by Click).
    """
    engine_normalised = engine.lower()
    path = _resolve_log_path(engine_normalised, session, cwd)
    if path is None:
        # Friendly stderr message so the user sees what was searched.
        # Mirrors the install-hint style used by ``usage`` / ``jobs``
        # for missing binaries.
        click.echo(
            (
                f"pocketshell: no {engine_normalised} session log found for "
                f"`{session}`. Looked under "
                f"{_search_root_for(engine_normalised)} "
                f"(use --cwd for Claude if the session was launched from a "
                f"specific project directory)."
            ),
            err=True,
        )
        ctx.exit(_EXIT_LOG_NOT_FOUND)

    lines = _tail(_read_lines(path), tail_count)
    if json_output:
        _emit_json(engine_normalised, session, path, lines)
    else:
        _emit_text(lines)


def _search_root_for(engine: str) -> str:
    """Human-friendly description of where ``engine`` logs live.

    Used only inside the error message when the resolver returns
    ``None``. Pulled out for readability and so the test suite can
    reuse it via the public ``_resolve_*_path`` helpers without
    re-deriving the paths.
    """
    if engine == "claude":
        return str(_claude_projects_root()) + os.sep + "<encoded-cwd>" + os.sep
    if engine == "codex":
        return str(_codex_sessions_root()) + os.sep + "<YYYY>/<MM>/<DD>" + os.sep
    if engine == "opencode":
        return str(_opencode_root()) + os.sep
    return "(unknown engine)"
