"""`pocketshell hooks` subcommand group — agent stop/idle detection.

Installs **stop / idle-detection** hooks across the three agent engines
PocketShell cares about (Claude Code, Codex, OpenCode) and normalizes
their per-engine events into a single append-only JSONL bus the Android
app (and, later, an assistant supervisor) can read back.

This is **integration only** for now — the "check status and tell the
agent to continue" UX is a later issue. The empirical mechanism this
ports lives in the sibling ``stop-handle`` PoC repo; see issue #267 and
locked decision **D26** in ``docs/decisions.md``.

Design — merge, never clobber (D26)
-----------------------------------

``install`` is **non-destructive**. It reads each engine's existing
config and adds only our entries when they are absent, preserving every
other key and any pre-existing hooks:

- **Claude Code** — ``~/.claude/settings.json`` (JSON). We add a
  ``{type: "command", command: "python3 <handler>"}`` entry under the
  ``Stop``, ``SubagentStop`` and ``Notification`` hook events. Existing
  hook groups under those events are preserved; we only append our own
  group when it is not already present. All other top-level keys are
  left untouched.

- **Codex** — ``~/.codex/config.toml`` (TOML). We set the top-level
  ``notify`` program to our handler. Codex hooks do NOT fire under
  ``codex exec`` (proven in the PoC), so ``notify`` is the headless-safe
  signal. If ``notify`` is already set to *something else*, we **warn
  and skip** rather than clobber the user's program. The rest of the
  TOML is preserved byte-for-byte.

- **OpenCode** — a plugin file dropped into the OpenCode plugin dir
  (``~/.config/opencode/plugin/`` global, ``plugin/`` singular in
  1.15.12). Other plugins in that dir are untouched.

``install`` is **idempotent**: running it twice adds nothing new.

``uninstall`` removes **only** what we added and is idempotent. After an
``install`` → ``uninstall`` round-trip a pre-populated Claude settings
file is byte-equivalent for the unrelated parts, a Codex ``config.toml``
keeps all unrelated content, and other OpenCode plugins are untouched.

Per-engine uninstall procedure
------------------------------

- **Claude Code** — our command entry (and any hook group / event key we
  emptied) is removed from ``~/.claude/settings.json``. Event keys and
  the top-level ``hooks`` object are deleted only if *we* created them
  and they end up empty; a user's pre-existing hooks always survive.
- **Codex** — the top-level ``notify`` line is removed from
  ``~/.codex/config.toml`` **only** if it still points at our handler.
  A ``notify`` the user pointed elsewhere is left alone.
- **OpenCode** — our plugin file is deleted from the plugin dir. Other
  plugins, and the dir itself, are left in place.

The handler scripts and the event bus live under a stable
pocketshell-owned cache dir (``~/.cache/pocketshell/hooks/`` by default,
overridable with ``$POCKETSHELL_HOOKS_DIR``). The handlers resolve the
bus path relative to their own location so they keep working regardless
of the engine's cwd.
"""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional, Sequence

import click

# Engine identifiers. ``all`` is the CLI sentinel meaning "every engine".
ENGINES: tuple[str, ...] = ("claude", "codex", "opencode")

# Claude hook event names we register our command under. ``Stop`` /
# ``SubagentStop`` map to FINISHED; ``Notification`` maps to
# WAITING_FOR_INPUT (it cannot block and does not fire under ``-p``, but
# it is the only waiting-for-input signal Claude exposes).
CLAUDE_HOOK_EVENTS: tuple[str, ...] = ("Stop", "SubagentStop", "Notification")

# The OpenCode plugin filename we own. Stable so uninstall can find it.
OPENCODE_PLUGIN_FILENAME = "pocketshell-idle-signal.js"

# Handler script filenames written into the pocketshell hooks dir.
CLAUDE_HANDLER_NAME = "claude_hook.py"
CODEX_HANDLER_NAME = "codex_notify.py"

# The bus filename inside the hooks dir.
EVENTS_FILENAME = "events.jsonl"

# A stable marker string embedded in every generated handler + the
# OpenCode plugin so we can recognise our own entries even if the user's
# home moves. The handler paths always contain this marker because they
# live under the pocketshell hooks dir.
POCKETSHELL_MARKER = "pocketshell"


# ---------------------------------------------------------------------------
# Path resolution (parametrized for tests — never touch the real home)
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class HooksPaths:
    """Resolved filesystem locations for the hooks feature.

    All engine config roots and the pocketshell-owned cache dir are
    fields so the unit suite can point them at a tmp dir. Nothing in
    this module reads ``~`` directly — everything flows through here.
    """

    hooks_dir: Path
    claude_settings: Path
    codex_config: Path
    opencode_plugin_dir: Path

    @property
    def events_file(self) -> Path:
        return self.hooks_dir / EVENTS_FILENAME

    @property
    def claude_handler(self) -> Path:
        return self.hooks_dir / CLAUDE_HANDLER_NAME

    @property
    def codex_handler(self) -> Path:
        return self.hooks_dir / CODEX_HANDLER_NAME

    @property
    def opencode_plugin(self) -> Path:
        return self.opencode_plugin_dir / OPENCODE_PLUGIN_FILENAME


def resolve_paths(
    *,
    home: Optional[Path] = None,
    env: Optional[dict[str, str]] = None,
) -> HooksPaths:
    """Return the :class:`HooksPaths` for ``home`` (default ``~``).

    Precedence for the hooks dir:

    1. ``$POCKETSHELL_HOOKS_DIR`` (env), expanded.
    2. ``<home>/.cache/pocketshell/hooks``.

    Engine config roots always hang off ``home`` so a test can pass a
    throwaway home and be sure the real ``~/.claude`` etc. are never
    touched.
    """
    env_map = env if env is not None else os.environ
    base_home = home if home is not None else Path(os.path.expanduser("~"))

    hooks_env = env_map.get("POCKETSHELL_HOOKS_DIR")
    if hooks_env:
        hooks_dir = Path(os.path.expanduser(hooks_env))
    else:
        hooks_dir = base_home / ".cache" / "pocketshell" / "hooks"

    return HooksPaths(
        hooks_dir=hooks_dir,
        claude_settings=base_home / ".claude" / "settings.json",
        codex_config=base_home / ".codex" / "config.toml",
        opencode_plugin_dir=base_home / ".config" / "opencode" / "plugin",
    )


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


# ---------------------------------------------------------------------------
# Handler script bodies (written at install time)
# ---------------------------------------------------------------------------
#
# The handlers are tiny and self-contained: they resolve the bus path
# relative to their own location (``__file__``) so they keep working no
# matter what cwd the engine spawns them in. They MUST stay
# dependency-free (stdlib only) because they run under whatever Python
# the engine finds on PATH, not under pocketshell's venv.


_CLAUDE_HANDLER_SOURCE = '''\
#!/usr/bin/env python3
"""PocketShell Claude Code stop/idle hook handler (generated).

Registered for Stop / SubagentStop / Notification. Reads the hook
payload on stdin and appends a normalized record to the pocketshell
event bus next to this script. Side-effect only: it never blocks the
stop and never injects a follow-up (integration only; see D26).
"""
import json
import os
import sys
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
EVENTS_FILE = os.path.join(HERE, "events.jsonl")


def main():
    raw = sys.stdin.read()
    try:
        payload = json.loads(raw)
    except (json.JSONDecodeError, ValueError):
        payload = {}

    event_name = payload.get("hook_event_name", "")
    if event_name == "Notification":
        state = "WAITING_FOR_INPUT"
    elif event_name in ("Stop", "SubagentStop"):
        state = "FINISHED"
    else:
        state = "UNKNOWN"

    record = {
        "ts": datetime.now(timezone.utc).isoformat(),
        "engine": "claude-code",
        "state": state,
        "source": "hook",
        "session_id": payload.get("session_id"),
        "cwd": payload.get("cwd"),
        "hook_event_name": event_name or None,
        "notification_type": payload.get("notification_type"),
        "transcript_path": payload.get("transcript_path"),
        "last_assistant_message": payload.get("last_assistant_message"),
    }
    os.makedirs(os.path.dirname(EVENTS_FILE), exist_ok=True)
    with open(EVENTS_FILE, "a") as handle:
        handle.write(json.dumps(record) + "\\n")
    # Exit clean with no stdout so Claude proceeds with the stop.
    sys.exit(0)


if __name__ == "__main__":
    main()
'''


_CODEX_HANDLER_SOURCE = '''\
#!/usr/bin/env python3
"""PocketShell Codex notify handler (generated).

Codex invokes ``notify`` with ONE argv: a JSON string describing the
event (notably ``agent-turn-complete`` after every ``codex exec`` turn).
Fire-and-forget; we just append a normalized record to the pocketshell
event bus next to this script.
"""
import json
import os
import sys
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
EVENTS_FILE = os.path.join(HERE, "events.jsonl")


def main():
    raw = sys.argv[1] if len(sys.argv) > 1 else "{}"
    try:
        payload = json.loads(raw)
    except (json.JSONDecodeError, ValueError):
        payload = {}

    ntype = payload.get("type", "")
    state = "FINISHED" if ntype in ("agent-turn-complete", "turn-complete") else "NOTIFY"
    # Codex uses dashed keys in notify payloads.
    record = {
        "ts": datetime.now(timezone.utc).isoformat(),
        "engine": "codex",
        "state": state,
        "source": "notify",
        "session_id": payload.get("thread-id") or payload.get("session-id"),
        "cwd": payload.get("cwd"),
        "notify_type": ntype or None,
        "turn_id": payload.get("turn-id"),
        "last_assistant_message": payload.get("last-assistant-message"),
    }
    os.makedirs(os.path.dirname(EVENTS_FILE), exist_ok=True)
    with open(EVENTS_FILE, "a") as handle:
        handle.write(json.dumps(record) + "\\n")
    sys.exit(0)


if __name__ == "__main__":
    main()
'''


_OPENCODE_PLUGIN_SOURCE = '''\
// PocketShell OpenCode idle-signal plugin (generated).
//
// Auto-loaded from the OpenCode plugin dir. The `event` hook fires for
// every bus event; we emit a normalized record to the pocketshell event
// bus when a session goes idle (FINISHED), asks for permission
// (WAITING_FOR_INPUT), or errors (ERROR). Integration only — no
// continue/stop decision (see D26).
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

function busPath() {
  const override = process.env.POCKETSHELL_HOOKS_DIR;
  const dir = override
    ? override.replace(/^~(?=$|\\/)/, os.homedir())
    : path.join(os.homedir(), ".cache", "pocketshell", "hooks");
  return path.join(dir, "events.jsonl");
}

export const PocketShellIdleSignal = async () => {
  const EVENTS_FILE = busPath();
  function emit(state, event) {
    const rec = {
      ts: new Date().toISOString(),
      engine: "opencode",
      state,
      source: "plugin",
      session_id: event.properties?.sessionID,
      payload: event,
    };
    try {
      fs.mkdirSync(path.dirname(EVENTS_FILE), { recursive: true });
      fs.appendFileSync(EVENTS_FILE, JSON.stringify(rec) + "\\n");
    } catch (e) {
      // best-effort; never throw out of a plugin hook
    }
  }
  return {
    event: async ({ event }) => {
      if (event.type === "session.idle") {
        emit("FINISHED", event);
      } else if (event.type === "permission.asked") {
        emit("WAITING_FOR_INPUT", event);
      } else if (event.type === "session.error") {
        emit("ERROR", event);
      }
    },
  };
};
'''


# ---------------------------------------------------------------------------
# Handler script management
# ---------------------------------------------------------------------------


def _write_handlers(paths: HooksPaths) -> None:
    """Write the handler scripts into the hooks dir (idempotent)."""
    paths.hooks_dir.mkdir(parents=True, exist_ok=True)
    paths.claude_handler.write_text(_CLAUDE_HANDLER_SOURCE)
    paths.claude_handler.chmod(0o755)
    paths.codex_handler.write_text(_CODEX_HANDLER_SOURCE)
    paths.codex_handler.chmod(0o755)


def _claude_command(paths: HooksPaths) -> str:
    """The exact command string we register in Claude's settings."""
    return f"python3 {paths.claude_handler}"


def _codex_notify_value(paths: HooksPaths) -> list[str]:
    """The exact ``notify`` array we set in Codex config.toml."""
    return ["python3", str(paths.codex_handler)]


# ---------------------------------------------------------------------------
# Claude Code: JSON merge / unmerge
# ---------------------------------------------------------------------------


def _load_claude_settings(path: Path) -> dict[str, Any]:
    """Load Claude settings JSON, returning ``{}`` when absent/blank.

    A malformed settings file raises ``ValueError`` so install/uninstall
    refuse to silently clobber a file we cannot parse.
    """
    if not path.exists():
        return {}
    text = path.read_text()
    if not text.strip():
        return {}
    data = json.loads(text)
    if not isinstance(data, dict):
        raise ValueError(f"{path}: expected a JSON object at the top level")
    return data


def _our_claude_group(command: str) -> dict[str, Any]:
    """The hook *group* we add under each Claude event."""
    return {"hooks": [{"type": "command", "command": command}]}


def _group_is_ours(group: Any, command: str) -> bool:
    """True when ``group`` is a hook group containing only our command.

    We match on the command string so a user group with the same shape
    but a different command is never treated as ours.
    """
    if not isinstance(group, dict):
        return False
    inner = group.get("hooks")
    if not isinstance(inner, list):
        return False
    return any(
        isinstance(h, dict) and h.get("type") == "command" and h.get("command") == command
        for h in inner
    )


def claude_install(settings: dict[str, Any], command: str) -> dict[str, Any]:
    """Return ``settings`` with our hook command merged in (pure function).

    Adds ``command`` under each of :data:`CLAUDE_HOOK_EVENTS` only when
    no existing group already carries it. Existing keys, existing hook
    events, and existing groups are preserved. Idempotent.
    """
    result = json.loads(json.dumps(settings))  # deep copy
    hooks_obj = result.get("hooks")
    if not isinstance(hooks_obj, dict):
        hooks_obj = {}
        result["hooks"] = hooks_obj

    for event in CLAUDE_HOOK_EVENTS:
        groups = hooks_obj.get(event)
        if not isinstance(groups, list):
            groups = []
            hooks_obj[event] = groups
        already = any(_group_is_ours(group, command) for group in groups)
        if not already:
            groups.append(_our_claude_group(command))
    return result


def claude_uninstall(settings: dict[str, Any], command: str) -> dict[str, Any]:
    """Return ``settings`` with our hook command removed (pure function).

    Removes any group carrying ``command`` from each Claude event. When
    an event list becomes empty it is dropped; when the top-level
    ``hooks`` object becomes empty it is dropped too — but only if those
    containers no longer hold any user data. Idempotent.
    """
    result = json.loads(json.dumps(settings))  # deep copy
    hooks_obj = result.get("hooks")
    if not isinstance(hooks_obj, dict):
        return result

    for event in CLAUDE_HOOK_EVENTS:
        groups = hooks_obj.get(event)
        if not isinstance(groups, list):
            continue
        kept = [group for group in groups if not _group_is_ours(group, command)]
        if kept:
            hooks_obj[event] = kept
        else:
            del hooks_obj[event]

    if not hooks_obj:
        del result["hooks"]
    return result


def _claude_is_installed(settings: dict[str, Any], command: str) -> bool:
    hooks_obj = settings.get("hooks")
    if not isinstance(hooks_obj, dict):
        return False
    for event in CLAUDE_HOOK_EVENTS:
        groups = hooks_obj.get(event)
        if isinstance(groups, list) and any(_group_is_ours(g, command) for g in groups):
            return True
    return False


# ---------------------------------------------------------------------------
# Codex: top-level ``notify`` line in config.toml (text-level edit)
# ---------------------------------------------------------------------------
#
# Python 3.11 ships ``tomllib`` (read-only) but no TOML writer. To honour
# "preserve the rest of the TOML byte-for-byte" we do a targeted
# text-level edit of just the top-level ``notify`` line rather than
# round-tripping the whole document through a serializer.

# Match a top-level ``notify = [...]`` assignment. Top-level means it
# appears before the first ``[table]`` header. We only ever touch the
# first such line.
_NOTIFY_LINE_RE = re.compile(r"^\s*notify\s*=", re.MULTILINE)
_TABLE_HEADER_RE = re.compile(r"^\s*\[", re.MULTILINE)


def _toml_value(value: Sequence[str]) -> str:
    """Render a list-of-strings as a TOML inline array."""
    parts = ", ".join(json.dumps(item) for item in value)
    return f"[{parts}]"


def _find_top_level_notify(text: str) -> Optional[tuple[int, int]]:
    """Return ``(start, end)`` span of the top-level ``notify`` line.

    The span covers the whole physical line (without its trailing
    newline). Returns ``None`` if no top-level ``notify`` assignment
    exists. A ``notify`` that only appears inside a ``[table]`` is
    ignored (Codex's ``notify`` is a top-level key).
    """
    first_table = _TABLE_HEADER_RE.search(text)
    table_pos = first_table.start() if first_table else len(text)
    match = _NOTIFY_LINE_RE.search(text)
    if match is None or match.start() >= table_pos:
        return None
    line_start = text.rfind("\n", 0, match.start()) + 1
    line_end = text.find("\n", match.start())
    if line_end == -1:
        line_end = len(text)
    return line_start, line_end


def _extract_notify_command(line: str) -> Optional[list[str]]:
    """Best-effort parse of the array on a ``notify = [...]`` line.

    Uses ``tomllib`` on just that line. Returns ``None`` when it cannot
    be parsed as a list of strings.
    """
    import tomllib

    try:
        parsed = tomllib.loads(line)
    except tomllib.TOMLDecodeError:
        return None
    value = parsed.get("notify")
    if isinstance(value, list) and all(isinstance(item, str) for item in value):
        return list(value)
    return None


def codex_install(text: str, notify_value: Sequence[str]) -> tuple[str, str]:
    """Merge our ``notify`` into Codex config ``text``.

    Returns ``(new_text, status)`` where ``status`` is one of:

    - ``"added"`` — no ``notify`` existed; we added ours.
    - ``"present"`` — ``notify`` already points at our handler (idempotent
      no-op).
    - ``"skipped"`` — ``notify`` is set to *something else*; we warn and
      leave it alone (never clobber the user's program).

    All other TOML content is preserved.
    """
    span = _find_top_level_notify(text)
    notify_line = f"notify = {_toml_value(notify_value)}"

    if span is None:
        # Prepend our notify line, keeping the rest of the document
        # intact. A leading newline is fine for TOML; existing content
        # follows unchanged.
        return notify_line + "\n" + text, "added"

    start, end = span
    existing_line = text[start:end]
    existing = _extract_notify_command(existing_line)
    if existing == list(notify_value):
        return text, "present"
    return text, "skipped"


def codex_uninstall(text: str, notify_value: Sequence[str]) -> tuple[str, str]:
    """Remove our ``notify`` line from Codex config ``text``.

    Returns ``(new_text, status)`` where ``status`` is one of
    ``"removed"`` (our line was present and dropped), ``"absent"`` (no
    top-level ``notify``), or ``"skipped"`` (a ``notify`` set to
    something else — left untouched). Idempotent.
    """
    span = _find_top_level_notify(text)
    if span is None:
        return text, "absent"
    start, end = span
    existing = _extract_notify_command(text[start:end])
    if existing != list(notify_value):
        return text, "skipped"
    # Drop the whole line including its trailing newline (if any).
    drop_end = end + 1 if end < len(text) and text[end] == "\n" else end
    return text[:start] + text[drop_end:], "removed"


# ---------------------------------------------------------------------------
# Engine install/uninstall drivers (touch the filesystem)
# ---------------------------------------------------------------------------


@dataclass
class EngineResult:
    """Outcome of an install/uninstall action for one engine."""

    engine: str
    status: str
    message: str


def _install_claude(paths: HooksPaths) -> EngineResult:
    command = _claude_command(paths)
    settings = _load_claude_settings(paths.claude_settings)
    if _claude_is_installed(settings, command):
        return EngineResult("claude", "present", "hook already installed")
    merged = claude_install(settings, command)
    paths.claude_settings.parent.mkdir(parents=True, exist_ok=True)
    paths.claude_settings.write_text(json.dumps(merged, indent=2) + "\n")
    return EngineResult("claude", "installed", f"merged hook into {paths.claude_settings}")


def _uninstall_claude(paths: HooksPaths) -> EngineResult:
    command = _claude_command(paths)
    if not paths.claude_settings.exists():
        return EngineResult("claude", "absent", "no settings file")
    settings = _load_claude_settings(paths.claude_settings)
    if not _claude_is_installed(settings, command):
        return EngineResult("claude", "absent", "hook not present")
    cleaned = claude_uninstall(settings, command)
    paths.claude_settings.write_text(json.dumps(cleaned, indent=2) + "\n")
    return EngineResult("claude", "removed", f"removed hook from {paths.claude_settings}")


def _install_codex(paths: HooksPaths) -> EngineResult:
    text = paths.codex_config.read_text() if paths.codex_config.exists() else ""
    new_text, status = codex_install(text, _codex_notify_value(paths))
    if status == "skipped":
        return EngineResult(
            "codex",
            "skipped",
            "notify already set to another program; left untouched",
        )
    if status == "present":
        return EngineResult("codex", "present", "notify already installed")
    paths.codex_config.parent.mkdir(parents=True, exist_ok=True)
    paths.codex_config.write_text(new_text)
    return EngineResult("codex", "installed", f"set notify in {paths.codex_config}")


def _uninstall_codex(paths: HooksPaths) -> EngineResult:
    if not paths.codex_config.exists():
        return EngineResult("codex", "absent", "no config file")
    text = paths.codex_config.read_text()
    new_text, status = codex_uninstall(text, _codex_notify_value(paths))
    if status == "absent":
        return EngineResult("codex", "absent", "no notify entry")
    if status == "skipped":
        return EngineResult("codex", "skipped", "notify points elsewhere; left untouched")
    paths.codex_config.write_text(new_text)
    return EngineResult("codex", "removed", f"removed notify from {paths.codex_config}")


def _install_opencode(paths: HooksPaths) -> EngineResult:
    paths.opencode_plugin_dir.mkdir(parents=True, exist_ok=True)
    if paths.opencode_plugin.exists():
        # Rewrite to keep the source current; idempotent for status.
        existing = paths.opencode_plugin.read_text()
        if existing == _OPENCODE_PLUGIN_SOURCE:
            return EngineResult("opencode", "present", "plugin already installed")
    paths.opencode_plugin.write_text(_OPENCODE_PLUGIN_SOURCE)
    return EngineResult("opencode", "installed", f"wrote plugin {paths.opencode_plugin}")


def _uninstall_opencode(paths: HooksPaths) -> EngineResult:
    if not paths.opencode_plugin.exists():
        return EngineResult("opencode", "absent", "plugin not present")
    paths.opencode_plugin.unlink()
    return EngineResult("opencode", "removed", f"removed plugin {paths.opencode_plugin}")


_INSTALLERS = {
    "claude": _install_claude,
    "codex": _install_codex,
    "opencode": _install_opencode,
}

_UNINSTALLERS = {
    "claude": _uninstall_claude,
    "codex": _uninstall_codex,
    "opencode": _uninstall_opencode,
}


def install_engines(engines: Sequence[str], paths: HooksPaths) -> list[EngineResult]:
    """Install hooks for ``engines`` and return one result per engine."""
    _write_handlers(paths)
    return [_INSTALLERS[engine](paths) for engine in engines]


def uninstall_engines(engines: Sequence[str], paths: HooksPaths) -> list[EngineResult]:
    """Uninstall hooks for ``engines`` and return one result per engine.

    When *all* engines are uninstalled and the hooks dir holds nothing
    but our generated handlers + an (optionally empty) bus, the handler
    scripts are removed. The bus file is preserved so already-emitted
    events stay readable.
    """
    results = [_UNINSTALLERS[engine](paths) for engine in engines]
    _maybe_cleanup_handlers(set(engines), paths)
    return results


def _maybe_cleanup_handlers(engines: set[str], paths: HooksPaths) -> None:
    """Remove generated handler scripts for fully-uninstalled engines."""
    if "claude" in engines and paths.claude_handler.exists():
        paths.claude_handler.unlink()
    if "codex" in engines and paths.codex_handler.exists():
        paths.codex_handler.unlink()


# ---------------------------------------------------------------------------
# Status + events reading
# ---------------------------------------------------------------------------


def engine_status(engine: str, paths: HooksPaths) -> dict[str, Any]:
    """Return a status dict for one ``engine``."""
    if engine == "claude":
        command = _claude_command(paths)
        try:
            settings = _load_claude_settings(paths.claude_settings)
        except ValueError:
            settings = {}
        return {
            "engine": "claude",
            "installed": _claude_is_installed(settings, command),
            "config_path": str(paths.claude_settings),
            "config_exists": paths.claude_settings.exists(),
            "handler_path": str(paths.claude_handler),
            "handler_exists": paths.claude_handler.exists(),
        }
    if engine == "codex":
        installed = False
        if paths.codex_config.exists():
            span = _find_top_level_notify(paths.codex_config.read_text())
            if span is not None:
                line = paths.codex_config.read_text()[span[0]:span[1]]
                installed = _extract_notify_command(line) == _codex_notify_value(paths)
        return {
            "engine": "codex",
            "installed": installed,
            "config_path": str(paths.codex_config),
            "config_exists": paths.codex_config.exists(),
            "handler_path": str(paths.codex_handler),
            "handler_exists": paths.codex_handler.exists(),
        }
    # opencode
    return {
        "engine": "opencode",
        "installed": paths.opencode_plugin.exists(),
        "config_path": str(paths.opencode_plugin),
        "config_exists": paths.opencode_plugin.exists(),
        "handler_path": str(paths.opencode_plugin),
        "handler_exists": paths.opencode_plugin.exists(),
    }


def read_events(
    paths: HooksPaths,
    *,
    limit: Optional[int] = None,
    since: Optional[str] = None,
) -> list[dict[str, Any]]:
    """Read normalized records from the bus (most-recent-last order).

    ``limit`` keeps only the last N records. ``since`` keeps records
    whose ``ts`` is lexicographically greater than the value (ISO-8601
    timestamps sort correctly as strings). Malformed lines are skipped.
    """
    if not paths.events_file.exists():
        return []
    records: list[dict[str, Any]] = []
    for line in paths.events_file.read_text().splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except (json.JSONDecodeError, ValueError):
            continue
        if not isinstance(obj, dict):
            continue
        if since is not None:
            ts = obj.get("ts")
            if not isinstance(ts, str) or ts <= since:
                continue
        records.append(obj)
    if limit is not None and limit >= 0:
        records = records[-limit:]
    return records


# ---------------------------------------------------------------------------
# Click surface
# ---------------------------------------------------------------------------


def _resolve_engines(engine: str) -> list[str]:
    if engine == "all":
        return list(ENGINES)
    return [engine]


@click.group(
    name="hooks",
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Install / uninstall agent stop-idle detection hooks and read the "
        "normalized event bus.\n\n"
        "``install`` merges our Stop/idle hooks into each engine's config "
        "without clobbering existing settings (Claude ``settings.json`` "
        "hooks, Codex ``notify`` program, OpenCode plugin). ``uninstall`` "
        "removes only our entries. ``status`` reports per-engine install "
        "state; ``events`` reads the JSONL bus. Integration only — no "
        "continue/stop action. See issue #267 and D26."
    ),
)
def hooks_group() -> None:
    """Top-level group registered onto the root `pocketshell` CLI."""


_ENGINE_OPTION = click.option(
    "--engine",
    type=click.Choice([*ENGINES, "all"]),
    default="all",
    show_default=True,
    help="Which engine to act on (default: all).",
)


@hooks_group.command("install")
@_ENGINE_OPTION
def hooks_install(engine: str) -> None:
    """Install stop/idle hooks (merge into existing config, never clobber)."""
    paths = resolve_paths()
    results = install_engines(_resolve_engines(engine), paths)
    for result in results:
        stream = "err" if result.status == "skipped" else "out"
        line = f"{result.engine}: {result.status} — {result.message}"
        click.echo(line, err=(stream == "err"))


@hooks_group.command("uninstall")
@_ENGINE_OPTION
def hooks_uninstall(engine: str) -> None:
    """Remove only the entries pocketshell installed (idempotent)."""
    paths = resolve_paths()
    results = uninstall_engines(_resolve_engines(engine), paths)
    for result in results:
        click.echo(f"{result.engine}: {result.status} — {result.message}")


@hooks_group.command("status")
@_ENGINE_OPTION
@click.option(
    "--json",
    "json_output",
    is_flag=True,
    help="Emit JSON instead of a human-readable summary.",
)
@click.option(
    "--last",
    "last",
    type=int,
    default=5,
    show_default=True,
    help="Include the last N bus events in the report.",
)
def hooks_status(engine: str, json_output: bool, last: int) -> None:
    """Report per-engine install state + the tail of the event bus."""
    paths = resolve_paths()
    engines = _resolve_engines(engine)
    statuses = [engine_status(eng, paths) for eng in engines]
    events = read_events(paths, limit=last)
    if json_output:
        payload = {
            "bus_path": str(paths.events_file),
            "engines": statuses,
            "recent_events": events,
        }
        click.echo(json.dumps(payload, indent=2, sort_keys=True))
        return
    click.echo(f"bus: {paths.events_file}")
    for status in statuses:
        mark = "installed" if status["installed"] else "not installed"
        click.echo(f"  {status['engine']:<9} {mark}  ({status['config_path']})")
    if events:
        click.echo(f"recent events (last {len(events)}):")
        for event in events:
            click.echo(
                f"  {event.get('ts', '?')}  {event.get('engine', '?')}  "
                f"{event.get('state', '?')}"
            )


@hooks_group.command("events")
@click.option(
    "--json",
    "json_output",
    is_flag=True,
    help="Emit a JSON array of records instead of one JSON line per record.",
)
@click.option(
    "--since",
    "since",
    type=str,
    default=None,
    help="Only show records whose ts is strictly after this ISO-8601 value.",
)
@click.option(
    "--limit",
    "limit",
    type=int,
    default=None,
    help="Show only the last N records.",
)
def hooks_events(json_output: bool, since: Optional[str], limit: Optional[int]) -> None:
    """Read the normalized JSONL event bus."""
    paths = resolve_paths()
    records = read_events(paths, limit=limit, since=since)
    if json_output:
        click.echo(json.dumps(records, indent=2, sort_keys=True))
        return
    for record in records:
        click.echo(json.dumps(record, sort_keys=True))
