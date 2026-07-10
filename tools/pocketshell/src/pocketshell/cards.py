"""Generic per-session typed-card store — the agent→app "push feed" (epic #859).

A running agent (Claude/Codex on the host) pushes a **typed card** to the
PocketShell app, scoped to the **current tmux session**; the app renders each
card by its type and writes interaction state back. This module is the host
side of that channel: a **generic** typed-card store keyed by tmux session,
with a **type registry**. v1 registers ONE card type — ``checklist`` — but the
store + registry are deliberately type-agnostic so adding ``note`` (mark-as-read)
or ``choice``/``approval`` later is just a new schema + handler, no new
transport (issue #859, Phase 1; the maintainer's "generic channel, checklist
FIRST" refinement).

Card model (generic, unchanged across types)::

    {
      "id":         "<card id>",          # stable per card
      "type":       "checklist",          # registry key
      "created_at": "<iso8601 utc>",
      "updated_at": "<iso8601 utc>",
      "title":      "<human title>",      # optional, per type
      "body":       {...},                # type-specific payload
      "state":      {...},                # type-specific interaction state
    }

For the ``checklist`` type:

- ``body  = {"items": [{"id": "<item id>", "text": "<item text>"}, ...]}``
- ``state = {"checked": ["<item id>", ...]}``

Storage
-------

One YAML document per tmux session under ``~/.pocketshell/cards/<session>.yaml``
(mirrors the ``reviews/`` inbox convention #714 — but two-way), holding the
session's list of cards. The app reads it over the warm session (D21, no new
connection) via ``pocketshell push get --json`` and writes interaction state
back via ``pocketshell push check``. Persisted with the atomic temp-file +
``os.replace`` private-write pattern (mode 0600, dir 0700) copied from
:mod:`pocketshell.tree`, so a concurrent reader never sees a half-written file.

The state is durable (survives a CLI process restart and a reconnect) because
it lives host-side, keyed by the session the agent is running in.

Why a TYPE REGISTRY (not an ``if card["type"] == "checklist"`` ladder)
----------------------------------------------------------------------

Each card type is a :class:`CardType` registered in :data:`REGISTRY`. A type
owns: building its ``body`` from CLI input, its initial ``state``, applying an
interaction (e.g. tick an item), and summarising itself for the human/YAML
output. Adding ``note`` later = register one more :class:`CardType` — the store,
the CLI verbs, and the persistence are unchanged. The :func:`register_card_type`
seam is exercised by a unit test with a stub second type so this extensibility
is proven, not merely asserted in a docstring.
"""

from __future__ import annotations

import os
import re
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Mapping, Optional

import click

# Permissions for the per-session card file (owner-only, matches tree.py).
NEW_FILE_MODE = 0o600

# Default checklist card id used when ``--id`` is omitted: a session has exactly
# one "default" checklist unless the agent names them, which keeps the common
# ``push checklist`` → ``push check`` flow free of id juggling.
DEFAULT_CHECKLIST_ID = "checklist"


# ---------------------------------------------------------------------------
# Time / id helpers
# ---------------------------------------------------------------------------


def _now_iso() -> str:
    """Current UTC time as an ISO-8601 string (stable, second precision)."""
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _slug(text: str) -> str:
    """Lowercase ASCII slug of ``text`` for a stable, readable item id."""
    cleaned = re.sub(r"[^a-z0-9]+", "-", text.strip().lower()).strip("-")
    return cleaned[:48]


# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class CardPaths:
    """Resolved filesystem location for the per-session card store.

    The dir is a field so the unit suite can point it at a tmp dir; nothing in
    this module reads ``~`` directly — everything flows through
    :func:`resolve_paths`.
    """

    cards_dir: Path

    def session_file(self, session: str) -> Path:
        return self.cards_dir / f"{_sanitise_session(session)}.yaml"


# Env override for the cards root, so tests (and a non-default deployment) can
# relocate the store without touching ``~``.
CARDS_DIR_ENV = "POCKETSHELL_CARDS_DIR"


def resolve_paths(
    *,
    home: Optional[Path] = None,
    env: Optional[Mapping[str, str]] = None,
) -> CardPaths:
    """Return the :class:`CardPaths` for the current (or given) environment.

    Precedence for the cards dir:

    1. ``$POCKETSHELL_CARDS_DIR`` when set (tests / non-default deployments).
    2. ``<home>/.pocketshell/cards`` — the issue-specified default that mirrors
       the ``~/inbox/pocketshell/reviews/`` convention (#714).
    """
    env_map = env if env is not None else os.environ
    override = env_map.get(CARDS_DIR_ENV)
    if override:
        return CardPaths(cards_dir=Path(os.path.expanduser(override)))
    base_home = home if home is not None else Path(os.path.expanduser("~"))
    return CardPaths(cards_dir=base_home / ".pocketshell" / "cards")


def _sanitise_session(session: str) -> str:
    """Sanitise a tmux session name into a safe single-path-segment filename.

    tmux session names cannot contain whitespace but can contain ``/``, ``.``
    etc.; replace anything outside ``[A-Za-z0-9._-]`` so the name maps to one
    flat file (no traversal, no nested dirs).
    """
    safe = re.sub(r"[^A-Za-z0-9._-]+", "_", session.strip())
    safe = safe.strip(".") or "session"
    return safe


def _ensure_dir(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(path, 0o700)
    except (PermissionError, FileNotFoundError):
        pass


def _write_private(path: Path, text: str) -> None:
    """Write ``text`` to ``path`` atomically with mode 0600 (copied from tree.py)."""
    _ensure_dir(path.parent)
    tmp = path.with_name(path.name + ".tmp")
    fd = os.open(str(tmp), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, NEW_FILE_MODE)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(text)
    except BaseException:
        try:
            os.unlink(tmp)
        except FileNotFoundError:
            pass
        raise
    os.replace(tmp, path)
    try:
        os.chmod(path, NEW_FILE_MODE)
    except FileNotFoundError:
        pass


# ---------------------------------------------------------------------------
# Session auto-detection ($TMUX / `tmux display-message -p '#S'`)
# ---------------------------------------------------------------------------


def detect_session(
    *,
    explicit: Optional[str] = None,
    env: Optional[Mapping[str, str]] = None,
) -> Optional[str]:
    """Resolve the target tmux session.

    Precedence:

    1. ``explicit`` (the ``--session`` CLI override) when given.
    2. ``tmux display-message -p '#S'`` when ``$TMUX`` is set (we are inside a
       tmux client) — the authoritative current-session name.

    Returns ``None`` when neither yields a session (the CLI then errors with a
    clear message rather than guessing).
    """
    if explicit and explicit.strip():
        return explicit.strip()
    env_map = env if env is not None else os.environ
    if not env_map.get("TMUX"):
        return None
    return _tmux_current_session()


def _tmux_current_session() -> Optional[str]:
    """Return the current tmux session name via ``tmux display-message``.

    Pulled out so tests can monkeypatch it. Returns ``None`` if ``tmux`` is
    missing or the command fails.
    """
    import shutil
    import subprocess

    tmux = shutil.which("tmux")
    if tmux is None:
        return None
    try:
        completed = subprocess.run(
            [tmux, "display-message", "-p", "#S"],
            check=False,
            capture_output=True,
            text=True,
        )
    except OSError:
        return None
    if completed.returncode != 0:
        return None
    name = completed.stdout.strip()
    return name or None


# ---------------------------------------------------------------------------
# Type registry
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class CardType:
    """One registered card type and its behaviour.

    - ``name`` — the registry key (the card's ``type`` field).
    - ``build_body`` — turn type-specific CLI input (a kwargs dict) into the
      persisted ``body`` payload.
    - ``initial_state`` — the ``state`` payload for a freshly-built card.
    - ``apply_interaction`` — mutate ``state`` in place for an interaction (e.g.
      tick an item / mark a note read). Returns the (possibly new) state dict.
    - ``summarise`` — a short one-line human description for ``push get`` /
      ``push status`` text output.
    """

    name: str
    build_body: Callable[..., dict[str, Any]]
    initial_state: Callable[[dict[str, Any]], dict[str, Any]]
    apply_interaction: Callable[[dict[str, Any], dict[str, Any], Mapping[str, Any]], dict[str, Any]]
    summarise: Callable[[dict[str, Any], dict[str, Any]], str]


REGISTRY: dict[str, CardType] = {}


def register_card_type(card_type: CardType) -> None:
    """Register a :class:`CardType`. Adding a new type is exactly this call."""
    REGISTRY[card_type.name] = card_type


def get_card_type(name: str) -> Optional[CardType]:
    return REGISTRY.get(name)


# ---------------------------------------------------------------------------
# checklist card type (v1 — the only registered type)
# ---------------------------------------------------------------------------

# A markdown checklist item line: `- [ ] text` or `- [x] text` (also `* ` / `+ `).
_CHECKLIST_LINE = re.compile(r"^\s*[-*+]\s*\[(?P<mark>[ xX])\]\s*(?P<text>.+?)\s*$")


def parse_checklist_markdown(markdown: str) -> list[dict[str, Any]]:
    """Parse ``- [ ] item`` / ``- [x] item`` markdown lines into items.

    Returns ``[{"id", "text", "preset_done"}]`` in source order. Lines that do
    not match a checklist bullet are ignored, so an agent can pipe a section of
    a larger doc. Item ids are a readable slug of the text plus an index to
    keep them unique even when two items share text.
    """
    items: list[dict[str, Any]] = []
    for index, line in enumerate(markdown.splitlines()):
        match = _CHECKLIST_LINE.match(line)
        if match is None:
            continue
        text = match.group("text").strip()
        if not text:
            continue
        preset_done = match.group("mark").lower() == "x"
        slug = _slug(text) or "item"
        item_id = f"{slug}-{index}"
        items.append({"id": item_id, "text": text, "preset_done": preset_done})
    return items


def _checklist_build_body(*, items: list[dict[str, Any]], **_: Any) -> dict[str, Any]:
    """Build the checklist ``body`` — ``{"items": [{id, text}]}``."""
    return {"items": [{"id": it["id"], "text": it["text"]} for it in items]}


def _checklist_initial_state(body: dict[str, Any]) -> dict[str, Any]:
    """Initial checked set — honour any ``- [x]`` presets if carried on items."""
    # ``body`` only carries id+text; presets ride on the build-time item list,
    # so initial state starts empty and presets are applied by the builder via
    # ``preset_checked`` (see :func:`build_card`).
    return {"checked": []}


def _checklist_apply_interaction(
    body: dict[str, Any],
    state: dict[str, Any],
    interaction: Mapping[str, Any],
) -> dict[str, Any]:
    """Tick/untick one item.

    ``interaction = {"item": "<item id>", "done": bool}``. ``done`` defaults to
    True (tick). Raises :class:`ValueError` if the item id is unknown so the CLI
    surfaces a clear error rather than silently no-opping.
    """
    item_id = interaction.get("item")
    if not isinstance(item_id, str) or not item_id:
        raise ValueError("checklist: `item` (item id) is required")
    known = {it.get("id") for it in body.get("items", []) if isinstance(it, Mapping)}
    if item_id not in known:
        raise ValueError(f"checklist: unknown item id {item_id!r}")
    done = bool(interaction.get("done", True))
    checked = [c for c in state.get("checked", []) if isinstance(c, str)]
    if done and item_id not in checked:
        checked.append(item_id)
    elif not done and item_id in checked:
        checked = [c for c in checked if c != item_id]
    return {"checked": checked}


def _checklist_summarise(body: dict[str, Any], state: dict[str, Any]) -> str:
    items = body.get("items", [])
    checked = state.get("checked", [])
    total = len(items) if isinstance(items, list) else 0
    done = len(checked) if isinstance(checked, list) else 0
    return f"checklist {done}/{total} checked"


register_card_type(
    CardType(
        name="checklist",
        build_body=_checklist_build_body,
        initial_state=_checklist_initial_state,
        apply_interaction=_checklist_apply_interaction,
        summarise=_checklist_summarise,
    )
)


# ---------------------------------------------------------------------------
# note card type (#859 Slice B — proves the registry is genuinely generic)
# ---------------------------------------------------------------------------
#
# A ``note`` is a non-interactive message the agent hands the human ("deploy
# finished", "found a flaky test"). Its only interaction is mark-as-read, so the
# agent can tell from ``push status`` whether the human has seen it. Registering
# it is exactly one ``register_card_type`` call + a ``push note`` verb — no new
# transport, no change to the store/read/write/upsert path. That is the whole
# point of the registry: a second real type is additive.

# Default note card id used when ``--id`` is omitted (one default note per
# session unless the agent names them — mirrors DEFAULT_CHECKLIST_ID).
DEFAULT_NOTE_ID = "note"


def _note_build_body(*, text: str, **_: Any) -> dict[str, Any]:
    """Build the note ``body`` — ``{"text": <message>}``."""
    return {"text": text}


def _note_initial_state(_body: dict[str, Any]) -> dict[str, Any]:
    """A fresh note is unread."""
    return {"read": False, "read_at": None}


def _note_apply_interaction(
    body: dict[str, Any],
    state: dict[str, Any],
    interaction: Mapping[str, Any],
) -> dict[str, Any]:
    """Mark a note read/unread.

    ``interaction = {"read": bool}``. ``read`` defaults to True (mark read).
    ``read_at`` records when it was first read (cleared on unread) so the agent
    can see acknowledgement timing via ``push status``.
    """
    read = bool(interaction.get("read", True))
    return {"read": read, "read_at": _now_iso() if read else None}


def _note_summarise(body: dict[str, Any], state: dict[str, Any]) -> str:
    return "note: read" if state.get("read") else "note: unread"


register_card_type(
    CardType(
        name="note",
        build_body=_note_build_body,
        initial_state=_note_initial_state,
        apply_interaction=_note_apply_interaction,
        summarise=_note_summarise,
    )
)


# ---------------------------------------------------------------------------
# Store read/write
# ---------------------------------------------------------------------------


def _import_yaml() -> Any:
    import yaml  # PyYAML is a hard dependency (pyproject); import here for clarity.

    return yaml


def read_cards(session: str, *, paths: CardPaths) -> list[dict[str, Any]]:
    """Return the session's list of cards (empty when none / unreadable)."""
    path = paths.session_file(session)
    try:
        raw = path.read_text(encoding="utf-8")
    except (FileNotFoundError, IsADirectoryError, PermissionError):
        return []
    if not raw.strip():
        return []
    yaml = _import_yaml()
    try:
        doc = yaml.safe_load(raw)
    except yaml.YAMLError:
        return []
    if not isinstance(doc, Mapping):
        return []
    cards = doc.get("cards")
    if not isinstance(cards, list):
        return []
    return [dict(c) for c in cards if isinstance(c, Mapping)]


def write_cards(session: str, cards: list[dict[str, Any]], *, paths: CardPaths) -> Path:
    """Atomically persist the session's card list as YAML. Returns the path."""
    yaml = _import_yaml()
    doc = {
        "schema": 1,
        "session": session,
        "updated_at": _now_iso(),
        "cards": cards,
    }
    text = yaml.safe_dump(doc, sort_keys=False, default_flow_style=False)
    path = paths.session_file(session)
    _write_private(path, text)
    return path


# ---------------------------------------------------------------------------
# Card operations (build / upsert / interact)
# ---------------------------------------------------------------------------


def build_card(
    *,
    card_type: str,
    card_id: str,
    title: Optional[str],
    build_kwargs: dict[str, Any],
    preset_checked: Optional[list[str]] = None,
) -> dict[str, Any]:
    """Construct a fresh card of ``card_type`` via the registry.

    Raises :class:`ValueError` for an unregistered type. ``preset_checked``
    seeds initial interaction state (used by the checklist parser to honour
    ``- [x]`` lines).
    """
    handler = get_card_type(card_type)
    if handler is None:
        raise ValueError(f"unknown card type {card_type!r}")
    now = _now_iso()
    body = handler.build_body(**build_kwargs)
    state = handler.initial_state(body)
    if preset_checked:
        # Generic seam: a type whose state has a ``checked`` list honours
        # presets; other types ignore an irrelevant preset.
        if isinstance(state.get("checked"), list):
            state["checked"] = list(dict.fromkeys(preset_checked))
    card: dict[str, Any] = {
        "id": card_id,
        "type": card_type,
        "created_at": now,
        "updated_at": now,
        "body": body,
        "state": state,
    }
    if title:
        card["title"] = title
    return card


def upsert_card(session: str, card: dict[str, Any], *, paths: CardPaths) -> Path:
    """Insert or replace ``card`` (matched by ``id``) in the session's list.

    Replace preserves nothing of the old card — a re-push of a checklist by the
    agent is a full replace (hard-cut semantics; the new list is authoritative).
    """
    cards = read_cards(session, paths=paths)
    card_id = card["id"]
    cards = [c for c in cards if c.get("id") != card_id]
    cards.append(card)
    return write_cards(session, cards, paths=paths)


def apply_interaction(
    session: str,
    card_id: str,
    interaction: Mapping[str, Any],
    *,
    paths: CardPaths,
) -> dict[str, Any]:
    """Apply an interaction (e.g. tick) to one card; persist; return the card.

    Raises :class:`ValueError` when the card id is not found in the session, or
    when the type's :meth:`apply_interaction` rejects the interaction.
    """
    cards = read_cards(session, paths=paths)
    target: Optional[dict[str, Any]] = None
    for card in cards:
        if card.get("id") == card_id:
            target = card
            break
    if target is None:
        raise ValueError(f"no card with id {card_id!r} in session {session!r}")
    handler = get_card_type(target.get("type", ""))
    if handler is None:
        raise ValueError(f"card {card_id!r} has unknown type {target.get('type')!r}")
    body = target.get("body", {}) if isinstance(target.get("body"), Mapping) else {}
    state = target.get("state", {}) if isinstance(target.get("state"), Mapping) else {}
    new_state = handler.apply_interaction(dict(body), dict(state), interaction)
    target["state"] = new_state
    target["updated_at"] = _now_iso()
    write_cards(session, cards, paths=paths)
    return target


# ---------------------------------------------------------------------------
# CLI — `pocketshell push checklist|get|status|check`
#
# These verbs are registered onto the EXISTING `push` group (FCM lives there
# too) by `cli.py`. The app only ever execs `pocketshell push <verb>` over its
# warm SSH session (D21); the agent calls the same verbs from inside its tmux
# session.
# ---------------------------------------------------------------------------


def _require_session(explicit: Optional[str]) -> str:
    session = detect_session(explicit=explicit)
    if session is None:
        raise click.ClickException(
            "could not determine the tmux session: pass --session or run "
            "inside tmux ($TMUX unset / `tmux display-message -p '#S'` failed)."
        )
    return session


def _notify_card_pushed_best_effort(
    session: str, card: dict[str, Any], *, card_paths: CardPaths
) -> None:
    """Fire a best-effort FCM push for a freshly upserted card (#859 Slice D/2).

    The single notification path shared by EVERY card-creating ``push`` verb
    (``checklist``, ``note``, and any future card type). Centralising it here is
    the durable class fix for #1446: a new ``push <cardtype>`` verb that upserts
    a card just calls this one helper, so it can't silently forget to notify the
    phone the way ``push note`` originally did.

    Fail-soft — the push deps are imported lazily so a host without them still
    pushes the card, and any failure is swallowed so a card write never wedges
    the CLI. De-dup + configuration guards live in :func:`notify_card_pushed`.
    """
    try:
        from pocketshell.agent_card_push import notify_card_pushed

        notify_card_pushed(session, card, card_paths=card_paths)
    except Exception:
        pass


def register_push_card_commands(push_group: click.Group) -> None:
    """Register the typed-card verbs onto the existing ``push`` click group.

    Called from :mod:`pocketshell.cli`. Kept as a function (not module-level
    decorators) so the FCM ``push`` group stays the single owner of the group
    object and this module only *extends* it — minimal, additive (#859 scope:
    keep shared registration minimal).
    """

    @push_group.command("checklist")
    @click.option("--title", "title", default=None, help="Human title for the checklist card.")
    @click.option(
        "--id",
        "card_id",
        default=DEFAULT_CHECKLIST_ID,
        help=f"Card id (default {DEFAULT_CHECKLIST_ID!r}; one default checklist per session).",
    )
    @click.option(
        "--item",
        "items",
        multiple=True,
        help="A checklist item (repeatable). Alternative to piping markdown on stdin.",
    )
    @click.option("--session", "session", default=None, help="Override the auto-detected tmux session.")
    def push_checklist(
        title: Optional[str],
        card_id: str,
        items: tuple[str, ...],
        session: Optional[str],
    ) -> None:
        """Create/replace the session's checklist card from stdin markdown or --item.

        Reads ``- [ ] item`` markdown on stdin, OR takes repeated ``--item``
        flags. The session is auto-detected from ``$TMUX`` (override with
        ``--session``). A re-push fully replaces the card of that id.
        """
        target = _require_session(session)
        parsed: list[dict[str, Any]] = []
        if items:
            for index, raw in enumerate(items):
                text = raw.strip()
                if not text:
                    continue
                slug = _slug(text) or "item"
                parsed.append({"id": f"{slug}-{index}", "text": text, "preset_done": False})
        else:
            stdin_text = "" if sys.stdin is None or sys.stdin.isatty() else sys.stdin.read()
            parsed = parse_checklist_markdown(stdin_text)
        if not parsed:
            raise click.ClickException(
                "no checklist items: pipe `- [ ]` markdown on stdin or pass --item."
            )
        preset_checked = [it["id"] for it in parsed if it.get("preset_done")]
        card = build_card(
            card_type="checklist",
            card_id=card_id,
            title=title,
            build_kwargs={"items": parsed},
            preset_checked=preset_checked,
        )
        card_paths = resolve_paths()
        path = upsert_card(target, card, paths=card_paths)
        # Slice D (#859): fire a best-effort FCM push so the phone surfaces a
        # heads-up notification opening this session's card feed.
        _notify_card_pushed_best_effort(target, card, card_paths=card_paths)
        click.echo(
            f"checklist {card_id!r} ({len(parsed)} items) -> session {target!r} ({path})"
        )

    @push_group.command("get")
    @click.option("--json", "as_json", is_flag=True, help="Emit the cards as a JSON array (for the app).")
    @click.option("--session", "session", default=None, help="Override the auto-detected tmux session.")
    def push_get(as_json: bool, session: Optional[str]) -> None:
        """Return the session's cards (human/YAML by default, --json for the app)."""
        # No notify (#1446 audit): read-only query, upserts no card. The app is
        # the caller here; there is nothing new to surface.
        target = _require_session(session)
        cards = read_cards(target, paths=resolve_paths())
        if as_json:
            import json

            click.echo(json.dumps({"session": target, "cards": cards}))
            return
        if not cards:
            click.echo(f"(no cards for session {target!r})")
            return
        yaml = _import_yaml()
        click.echo(
            yaml.safe_dump(
                {"session": target, "cards": cards},
                sort_keys=False,
                default_flow_style=False,
            ).rstrip("\n")
        )

    @push_group.command("status")
    @click.option("--id", "card_id", default=None, help="Limit to one card id.")
    @click.option("--json", "as_json", is_flag=True, help="Emit interaction state as JSON.")
    @click.option("--session", "session", default=None, help="Override the auto-detected tmux session.")
    def push_status(card_id: Optional[str], as_json: bool, session: Optional[str]) -> None:
        """Report interaction state — which checklist items the human has ticked."""
        # No notify (#1446 audit): read-only query, upserts no card.
        target = _require_session(session)
        cards = read_cards(target, paths=resolve_paths())
        if card_id is not None:
            cards = [c for c in cards if c.get("id") == card_id]
        statuses: list[dict[str, Any]] = []
        for card in cards:
            statuses.append(
                {
                    "id": card.get("id"),
                    "type": card.get("type"),
                    "state": card.get("state", {}),
                }
            )
        if as_json:
            import json

            click.echo(json.dumps({"session": target, "status": statuses}))
            return
        if not statuses:
            click.echo(f"(no cards for session {target!r})")
            return
        for card in cards:
            handler = get_card_type(card.get("type", ""))
            body = card.get("body", {}) if isinstance(card.get("body"), Mapping) else {}
            state = card.get("state", {}) if isinstance(card.get("state"), Mapping) else {}
            summary = handler.summarise(dict(body), dict(state)) if handler else "(unknown type)"
            click.echo(f"{card.get('id')}: {summary}")

    @push_group.command("check")
    @click.option("--id", "card_id", required=True, help="The checklist card id.")
    @click.option("--item", "item_id", required=True, help="The item id to (un)check.")
    @click.option("--done/--undone", "done", default=True, help="Tick (default) or untick the item.")
    @click.option("--session", "session", default=None, help="Override the auto-detected tmux session.")
    def push_check(card_id: str, item_id: str, done: bool, session: Optional[str]) -> None:
        """Set a checklist item's checked state (this is what the app's tick calls)."""
        # No notify (#1446 audit): this is the APP writing back the human's tick,
        # not an agent pushing a new card. Notifying would push the phone about
        # its own action (and could loop). It mutates state, never upserts a card.
        target = _require_session(session)
        try:
            card = apply_interaction(
                target,
                card_id,
                {"item": item_id, "done": done},
                paths=resolve_paths(),
            )
        except ValueError as exc:
            raise click.ClickException(str(exc))
        handler = get_card_type(card.get("type", ""))
        body = card.get("body", {}) if isinstance(card.get("body"), Mapping) else {}
        state = card.get("state", {}) if isinstance(card.get("state"), Mapping) else {}
        summary = handler.summarise(dict(body), dict(state)) if handler else ""
        click.echo(f"{card_id}: {summary}")

    @push_group.command("note")
    @click.option("--title", "title", default=None, help="Human title for the note card.")
    @click.option(
        "--id",
        "card_id",
        default=DEFAULT_NOTE_ID,
        help=f"Card id (default {DEFAULT_NOTE_ID!r}; one default note per session).",
    )
    @click.option(
        "--text",
        "text",
        default=None,
        help="The note body. Alternative to piping the message on stdin.",
    )
    @click.option("--session", "session", default=None, help="Override the auto-detected tmux session.")
    def push_note(
        title: Optional[str],
        card_id: str,
        text: Optional[str],
        session: Optional[str],
    ) -> None:
        """Create/replace the session's note card from --text or piped stdin.

        A note is a non-interactive message the human marks read (``push read``).
        The session is auto-detected from ``$TMUX`` (override with ``--session``).
        A re-push fully replaces the card of that id (hard-cut, D22).
        """
        target = _require_session(session)
        if text is not None and text.strip():
            body_text = text.strip()
        else:
            stdin_text = "" if sys.stdin is None or sys.stdin.isatty() else sys.stdin.read()
            body_text = stdin_text.strip()
        if not body_text:
            raise click.ClickException(
                "empty note: pass --text or pipe the message on stdin."
            )
        card = build_card(
            card_type="note",
            card_id=card_id,
            title=title,
            build_kwargs={"text": body_text},
        )
        card_paths = resolve_paths()
        path = upsert_card(target, card, paths=card_paths)
        # #1446: a pushed note is a card the human must see, just like a
        # checklist — fire the same best-effort FCM push so the phone surfaces a
        # heads-up notification opening this session's card feed. (Slice D/#859
        # shipped this only on `checklist`; `push note` was silently omitted.)
        _notify_card_pushed_best_effort(target, card, card_paths=card_paths)
        click.echo(f"note {card_id!r} -> session {target!r} ({path})")

    @push_group.command("read")
    @click.option("--id", "card_id", required=True, help="The note card id.")
    @click.option("--read/--unread", "read", default=True, help="Mark read (default) or unread.")
    @click.option("--session", "session", default=None, help="Override the auto-detected tmux session.")
    def push_read(card_id: str, read: bool, session: Optional[str]) -> None:
        """Set a note's read state (this is what the app's "mark read" calls)."""
        # No notify (#1446 audit): this is the APP writing back "human read it",
        # not an agent pushing a new card. Same rationale as `push check`.
        target = _require_session(session)
        try:
            card = apply_interaction(
                target,
                card_id,
                {"read": read},
                paths=resolve_paths(),
            )
        except ValueError as exc:
            raise click.ClickException(str(exc))
        handler = get_card_type(card.get("type", ""))
        body = card.get("body", {}) if isinstance(card.get("body"), Mapping) else {}
        state = card.get("state", {}) if isinstance(card.get("state"), Mapping) else {}
        summary = handler.summarise(dict(body), dict(state)) if handler else ""
        click.echo(f"{card_id}: {summary}")
