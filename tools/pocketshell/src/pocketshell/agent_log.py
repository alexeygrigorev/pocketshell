"""`pocketshell agent-log` subcommand.

Mirrors the per-engine JSONL conversation-log reads the Android app
currently runs over SSH (see ``AgentConversationRepository`` in
``app/src/main/java/com/pocketshell/app/session/``). The default command
still reads the raw JSONL so the Android client (and the planned IPC
daemon, #219) can cache + serve the same bytes the agent CLI wrote to
disk. The ``handoff`` subcommand adds a deliberately smaller export path:
it parses only user/assistant prose needed for cross-agent continuation
and skips tool calls/results by default.

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
- The JSONL files are append-only, plain text, one event per line. The
  default read path emits raw lines verbatim. The ``handoff`` path only
  extracts the portable message subset (human/user + assistant text) so
  it can produce a compact Markdown artifact without raw tool JSON.
- ``--json`` wraps the same raw lines in a small envelope (``engine``,
  ``session``, ``path``, ``lines``, ``count``) so machine consumers
  (the planned daemon, integration tests) can pin to a stable shape
  without re-parsing the JSONL themselves.
"""

from __future__ import annotations

import json
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, List, Optional

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
# Issue #1225/#1267: sentinel emitted in place of a transcript line that
# exceeded ``--max-line-bytes``, immediately followed by the line's original
# UTF-8 byte length. MUST stay byte-identical to ``LINE_TRUNCATION_SENTINEL``
# in the Kotlin ``AgentConversationRepository`` so the app recognises it and
# renders a VISIBLE truncation marker instead of feeding the (now absent)
# oversized JSON to the parser. This is the Codex/OpenCode counterpart of the
# ``awk`` per-line byte clamp #1225 applied to the Claude flat-JSONL tail: one
# multi-megabyte rollout line (an inline base64 image, a huge ``tool_result``)
# is degraded server-side so its bytes never cross SSH into the phone's heap.
_LINE_TRUNCATION_SENTINEL = "@@PS_LINE_TRUNCATED@@"
_DEFAULT_HANDOFF_MAX_TURNS = 30
_DEFAULT_HANDOFF_MAX_CHARS = 20_000
_HANDOFF_PROMPT = (
    "Read this previous agent conversation and continue from the current state. "
    "Use only the user and assistant messages below as context; tool calls and "
    "tool results were omitted. Ask for clarification only if critical context "
    "is missing."
)
_CLAUDE_SYSTEM_NOTE_TAGS = [
    "system-reminder",
    "command-name",
    "command-args",
    "command-message",
    "command-stdout",
    "local-command-stdout",
]


@dataclass(frozen=True)
class HandoffMessage:
    """A compact transcript message for cross-agent handoff output."""

    role: str
    text: str


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


def _is_within(path: Path, root: Path) -> bool:
    """True when ``path`` is ``root`` or a descendant, after resolving both.

    Mirrors ``prune_attachments._is_within`` / the ``repos.safe_clone_target``
    containment pattern used elsewhere in this package. Both sides are
    ``resolve()``-d first so a legitimately symlinked HOME (e.g. ``/home`` ->
    ``/data/home``) does not falsely trip the guard — only genuine traversal
    *out* of the per-engine root is rejected.
    """
    try:
        path.resolve().relative_to(root.resolve())
        return True
    except ValueError:
        return False


def _contained_candidate(candidate: Path, root: Path) -> Optional[Path]:
    """Return ``candidate`` only if it is a regular file inside ``root``.

    The single choke point that closes the ``--session`` / ``--cwd`` path
    traversal (#774 §2): an app-supplied name carrying ``..`` segments or an
    absolute component is rejected because the resolved candidate escapes the
    per-engine log root. ``.jsonl`` suffix + "is a regular file" remain
    necessary but are no longer the *only* fence.
    """
    if not _is_within(candidate, root):
        return None
    return candidate if candidate.is_file() else None


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
        return _contained_candidate(candidate, root)
    if not root.is_dir():
        return None
    for project_dir in sorted(root.iterdir()):
        if not project_dir.is_dir():
            continue
        candidate = project_dir / filename
        contained = _contained_candidate(candidate, root)
        if contained is not None:
            return contained
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
    # months of history. A ``..``-laden session name can make rglob surface
    # a traversal path, so each candidate is still containment-checked.
    for candidate in root.rglob(filename):
        contained = _contained_candidate(candidate, root)
        if contained is not None:
            return contained
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
    return _contained_candidate(candidate, root)


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


def _clamp_line_bytes(lines: List[str], max_line_bytes: Optional[int]) -> List[str]:
    """Byte-bound each line, server-side, before it is emitted.

    Any line whose UTF-8 byte length exceeds ``max_line_bytes`` is replaced by
    ``<_LINE_TRUNCATION_SENTINEL><byte-length>`` so a single multi-megabyte
    rollout line (an inline base64 image, a huge ``tool_result``) never crosses
    SSH into the phone's heap — the Codex/OpenCode counterpart of the Claude
    ``awk`` clamp (#1225/#1267). ``None`` or a non-positive cap disables the
    clamp (the whole-file default); normal lines pass through verbatim.
    """
    if not max_line_bytes or max_line_bytes <= 0:
        return lines
    clamped: List[str] = []
    for line in lines:
        byte_length = len(line.encode("utf-8"))
        if byte_length > max_line_bytes:
            clamped.append(f"{_LINE_TRUNCATION_SENTINEL}{byte_length}")
        else:
            clamped.append(line)
    return clamped


def _parse_json_line(line: str) -> Optional[dict[str, Any]]:
    """Parse one JSONL row, returning ``None`` for malformed/partial rows."""
    try:
        value = json.loads(line)
    except json.JSONDecodeError:
        return None
    return value if isinstance(value, dict) else None


def _normalise_text(text: str) -> str:
    """Trim surrounding whitespace and drop blank transcript fragments."""
    return text.strip()


def _text_from_scalar(value: Any) -> Optional[str]:
    if isinstance(value, str):
        text = _normalise_text(value)
        return text if text else None
    return None


def _text_parts_from_content(value: Any, allowed_types: set[str]) -> List[str]:
    """Extract plain text content blocks without tool-call/tool-result blocks."""
    if isinstance(value, str):
        text = _normalise_text(value)
        return [text] if text else []
    if isinstance(value, dict):
        block_type = value.get("type")
        if isinstance(block_type, str) and block_type not in allowed_types:
            return []
        text = _text_from_scalar(value.get("text"))
        if text is not None:
            return [text]
        return _text_parts_from_content(value.get("content"), allowed_types)
    if isinstance(value, list):
        parts: List[str] = []
        for item in value:
            parts.extend(_text_parts_from_content(item, allowed_types))
        return parts
    return []


def _strip_claude_system_notes(text: str) -> str:
    """Remove Claude XML-style system-note blocks from otherwise-human text."""
    cleaned = text
    for tag in _CLAUDE_SYSTEM_NOTE_TAGS:
        cleaned = re.sub(
            rf"(?is)<{re.escape(tag)}(?:\s[^>]*)?>.*?</{re.escape(tag)}>",
            "",
            cleaned,
        )
    return _normalise_text(cleaned)


def _claude_messages_from_row(row: dict[str, Any]) -> List[HandoffMessage]:
    message = row.get("message") if isinstance(row.get("message"), dict) else None
    role = row.get("role") or (message or {}).get("role") or row.get("type")
    if role not in {"user", "assistant"}:
        return []

    content = (message or {}).get("content") if message is not None else row.get("content")
    fragments = _text_parts_from_content(content, {"text"})
    messages: List[HandoffMessage] = []
    for fragment in fragments:
        text = _strip_claude_system_notes(fragment)
        if text:
            messages.append(HandoffMessage(role=role, text=text))
    return messages


def _codex_message_text(item: dict[str, Any]) -> Optional[str]:
    for key in ("message", "text"):
        text = _text_from_scalar(item.get(key))
        if text is not None:
            return text
    parts = _text_parts_from_content(item.get("content"), {"input_text", "output_text", "text"})
    return "\n\n".join(parts).strip() if parts else None


def _codex_messages_from_row(row: dict[str, Any]) -> List[HandoffMessage]:
    item = row.get("payload") if isinstance(row.get("payload"), dict) else None
    if item is None:
        item = row.get("item") if isinstance(row.get("item"), dict) else row

    item_type = item.get("type") or row.get("type")
    if item_type == "message":
        role = item.get("role")
        if role not in {"user", "assistant"}:
            return []
    elif item_type == "user_message":
        role = "user"
    elif item_type in {"assistant_message", "agent_message"}:
        role = "assistant"
    else:
        return []

    text = _codex_message_text(item)
    return [HandoffMessage(role=role, text=text)] if text else []


def _json_object_from_string(value: Any) -> Optional[dict[str, Any]]:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = json.loads(value)
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None


def _opencode_role(row: dict[str, Any], message_data: Optional[dict[str, Any]]) -> Optional[str]:
    role = row.get("message_role") or row.get("messageRole") or row.get("role")
    if role is None and message_data is not None:
        role = message_data.get("role")
    return role if role in {"user", "assistant"} else None


def _opencode_part_text(part: Optional[dict[str, Any]]) -> Optional[str]:
    if part is None:
        return None
    part_type = part.get("type")
    if part_type in {"tool_use", "tool", "tool_result", "function_call_output", "reasoning"}:
        return None
    if isinstance(part_type, str) and part_type not in {"input_text", "output_text", "text"}:
        return None
    parts = _text_parts_from_content(part, {"input_text", "output_text", "text"})
    return "\n\n".join(parts).strip() if parts else None


def _opencode_messages_from_row(row: dict[str, Any]) -> List[HandoffMessage]:
    message_data = _json_object_from_string(
        row.get("message_data") or row.get("messageData") or row.get("msg_data")
    )
    role = _opencode_role(row, message_data)
    if role is None:
        return []

    part_data = _json_object_from_string(row.get("part_data") or row.get("partData"))
    part_text = _opencode_part_text(part_data)
    if part_text:
        return [HandoffMessage(role=role, text=part_text)]

    fallback = (
        _text_from_scalar(row.get("message_content"))
        or _text_from_scalar(row.get("messageContent"))
        or _text_from_scalar(row.get("content"))
        or _text_from_scalar(row.get("text"))
        or _opencode_part_text(message_data)
    )
    return [HandoffMessage(role=role, text=fallback)] if fallback else []


def _handoff_messages_from_lines(engine: str, lines: Iterable[str]) -> List[HandoffMessage]:
    """Extract user/assistant prose from raw agent JSONL rows."""
    messages: List[HandoffMessage] = []
    for line in lines:
        row = _parse_json_line(line)
        if row is None:
            continue
        if engine == "claude":
            messages.extend(_claude_messages_from_row(row))
        elif engine == "codex":
            messages.extend(_codex_messages_from_row(row))
        elif engine == "opencode":
            messages.extend(_opencode_messages_from_row(row))
    return messages


def _bound_handoff_messages(
    messages: List[HandoffMessage],
    max_turns: int,
) -> tuple[List[HandoffMessage], int]:
    if max_turns <= 0 or max_turns >= len(messages):
        return list(messages), 0
    omitted = len(messages) - max_turns
    return messages[-max_turns:], omitted


def _render_message(message: HandoffMessage) -> str:
    title = "User" if message.role == "user" else "Assistant"
    return f"### {title}\n\n{message.text}\n"


def _render_handoff_markdown(
    *,
    engine: str,
    session: str,
    messages: List[HandoffMessage],
    omitted_for_turns: int,
    max_turns: int,
    max_chars: int,
) -> str:
    session_name = session.removesuffix(".jsonl")
    header_parts = [
        "# Agent Handoff",
        "",
        "## Ready Prompt",
        "",
        _HANDOFF_PROMPT,
        "",
        "## Source",
        "",
        f"- Engine: {engine}",
        f"- Session: {session_name}",
        f"- Messages included: {len(messages)}",
        f"- Max turns: {max_turns}",
        f"- Max chars: {max_chars}",
        "- Omitted by default: tool calls, tool results, command output, reasoning, and system notes",
        "",
        "## Conversation",
        "",
    ]
    if omitted_for_turns:
        header_parts.extend([f"[{omitted_for_turns} earlier message(s) omitted by --max-turns.]", ""])

    header = "\n".join(header_parts)
    body = "\n".join(_render_message(message) for message in messages)
    output = header + body
    if len(output) <= max_chars:
        return output

    marker = "[Earlier conversation text omitted to fit --max-chars.]\n\n"
    budget = max_chars - len(header) - len(marker)
    if budget > 0:
        trimmed_body = body[-budget:].lstrip()
        output = header + marker + trimmed_body
    if len(output) <= max_chars:
        return output

    # Extremely small limits cannot fit the complete header. Keep the ready
    # prompt at the front and hard-bound the artifact.
    truncated = output[:max_chars].rstrip()
    return truncated + "\n" if len(truncated) < max_chars else truncated


def _write_handoff_output(text: str, out: Optional[Path]) -> None:
    if out is None:
        sys.stdout.write(text)
        if not text.endswith("\n"):
            sys.stdout.write("\n")
        return
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text if text.endswith("\n") else text + "\n", encoding="utf-8")


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


@click.group(
    "agent-log",
    invoke_without_command=True,
    context_settings={"help_option_names": ["-h", "--help"]},
)
@click.option(
    "--session",
    "-s",
    "session",
    required=False,
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
    required=False,
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
@click.option(
    "--max-line-bytes",
    "max_line_bytes",
    type=click.IntRange(min=0),
    default=None,
    help=(
        "Replace any single log line longer than N bytes with a compact "
        "truncation marker, server-side, so one multi-megabyte line (a pasted "
        "image / huge tool result) cannot balloon the read into the client's "
        "heap. Default: no clamp (emit lines verbatim)."
    ),
)
@click.pass_context
def agent_log_command(
    ctx: click.Context,
    session: Optional[str],
    engine: Optional[str],
    cwd: Optional[str],
    tail_count: Optional[int],
    json_output: bool,
    max_line_bytes: Optional[int],
) -> None:
    """Print an agent JSONL conversation log.

    Reads the canonical per-engine path for the given session and emits
    the JSONL content either as raw lines (default) or wrapped in a JSON
    envelope (``--json``). ``--tail N`` bounds the output to the last N
    events. ``--max-line-bytes N`` degrades any single line longer than N
    bytes to a compact truncation marker so one pathological line cannot
    balloon the read (#1225/#1267).

    Exit codes:

    - 0  -> success.
    - 66 -> the resolved log path does not exist (wrong session id /
            wrong engine / agent has not written anything yet).
    - 2  -> bad invocation (handled by Click).
    """
    if ctx.invoked_subcommand is not None:
        return
    if session is None:
        raise click.UsageError("Missing option '--session' / '-s'.")
    if engine is None:
        raise click.UsageError("Missing option '--engine' / '-e'.")

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

    lines = _clamp_line_bytes(_tail(_read_lines(path), tail_count), max_line_bytes)
    if json_output:
        _emit_json(engine_normalised, session, path, lines)
    else:
        _emit_text(lines)


@agent_log_command.command(
    "handoff",
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
    help="Which agent CLI's log to export.",
)
@click.option(
    "--cwd",
    "cwd",
    type=str,
    default=None,
    help=(
        "Working directory the agent was launched from. Only used for "
        "Claude Code; ignored for codex and opencode."
    ),
)
@click.option(
    "--max-turns",
    "max_turns",
    type=click.IntRange(min=0),
    default=_DEFAULT_HANDOFF_MAX_TURNS,
    show_default=True,
    help=(
        "Include at most the last N user/assistant messages after filtering. "
        "Use 0 for all filtered messages."
    ),
)
@click.option(
    "--max-chars",
    "max_chars",
    type=click.IntRange(min=500),
    default=_DEFAULT_HANDOFF_MAX_CHARS,
    show_default=True,
    help="Hard cap for the rendered Markdown artifact.",
)
@click.option(
    "--out",
    "out",
    type=click.Path(dir_okay=False, path_type=Path),
    default=None,
    help="Write the Markdown artifact to this file. Default: stdout.",
)
def handoff_command(
    session: str,
    engine: str,
    cwd: Optional[str],
    max_turns: int,
    max_chars: int,
    out: Optional[Path],
) -> None:
    """Export a compact user/assistant transcript for another agent.

    The handoff artifact is Markdown/plain text with a ready-to-paste
    continuation prompt. Tool calls, tool results, reasoning, command
    output, and system-note records are excluded by default.
    """
    engine_normalised = engine.lower()
    path = _resolve_log_path(engine_normalised, session, cwd)
    if path is None:
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
        raise click.exceptions.Exit(_EXIT_LOG_NOT_FOUND)

    raw_lines = _read_lines(path)
    messages = _handoff_messages_from_lines(engine_normalised, raw_lines)
    bounded_messages, omitted_for_turns = _bound_handoff_messages(messages, max_turns)
    output = _render_handoff_markdown(
        engine=engine_normalised,
        session=session,
        messages=bounded_messages,
        omitted_for_turns=omitted_for_turns,
        max_turns=max_turns,
        max_chars=max_chars,
    )
    _write_handoff_output(output, out)


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
