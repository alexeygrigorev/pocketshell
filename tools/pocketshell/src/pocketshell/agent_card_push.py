"""Server-side FCM push when an agent posts a typed card (epic #859, Slice D).

This is the card sibling of :mod:`pocketshell.push` (which owns the usage-reset
FCM path, #690). When a running agent calls ``pocketshell push checklist`` the
host writes/updates a per-session card (:mod:`pocketshell.cards`); this module
fires a best-effort FCM **data** push so the phone surfaces a heads-up
notification that opens the session's card feed.

Why a separate module (not inside :mod:`pocketshell.cards`)
----------------------------------------------------------

The card store and the FCM device token live in **two different state homes**
(the #859 research spike's risk #1): cards under ``~/.pocketshell/cards/`` and
the registered device token under the usage state dir
(``$XDG_STATE_HOME/pocketshell/usage/push-token.json``). Rather than have
:mod:`pocketshell.cards` import the token storage directly, the bridge lives
here: it reads the token via :func:`pocketshell.push.read_token` and sends via
:class:`pocketshell.push.FcmSender` — the exact same sender/credential the
usage-reset path uses. Adding a second card type later (``note``) reuses this
verbatim.

Hard-cut (D22): the app receive path adds a NEW ``type=agent_card`` payload
sibling to ``usage_reset`` — this module never emits ``usage_reset``.

Fail-soft (matches :func:`pocketshell.push.push_reset_events`): no registered
token, no service-account credential, or no :mod:`google.auth` installed → the
trigger no-ops and returns ``None`` WITHOUT raising. ``pocketshell push
checklist`` must never break because Firebase isn't set up.

De-dup
------

A successful send records a deterministic **content key** under the cards dir
(``push-sent.jsonl``) so re-pushing the *same* card (an agent re-runs the same
``push checklist``) does not re-POST, while an *updated* card (different items /
title) produces a new key and DOES push. The app's ``PushDedupStore`` is a
second line of defence on the device; this is the server-side primary guard.
"""

from __future__ import annotations

import hashlib
import json
import socket
from pathlib import Path
from typing import Any, Optional

from pocketshell import push as push_mod
from pocketshell.cards import CardPaths
from pocketshell.usage_capture import _append_history, resolve_paths as resolve_usage_paths

# The app contract: an agent-card push is a `type=agent_card` FCM data message.
AGENT_CARD_PUSH_TYPE = "agent_card"

# Per-session content-key log so a re-push of an unchanged card never re-POSTs.
# Lives under the cards dir (NOT the usage dir) — co-located with the cards it
# de-dups. One small file is plenty: cards are rare and bounded.
SENT_LOG_FILENAME = "push-sent.jsonl"
DEFAULT_SENT_LOG_MAX_LINES = 1000


def sent_log_file(paths: CardPaths) -> Path:
    """Return the per-content-key already-pushed log path for ``paths``."""
    return paths.cards_dir / SENT_LOG_FILENAME


def sent_card_keys(paths: CardPaths) -> set[str]:
    """Return the set of card content keys already successfully pushed."""
    path = sent_log_file(paths)
    if not path.exists():
        return set()
    keys: set[str] = set()
    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        return set()
    for line in text.splitlines():
        if not line.strip():
            continue
        try:
            parsed = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(parsed, dict):
            key = parsed.get("card_key")
            if isinstance(key, str) and key:
                keys.add(key)
    return keys


def _mark_sent(
    card_key: str,
    *,
    paths: CardPaths,
    sent_log_max_lines: int = DEFAULT_SENT_LOG_MAX_LINES,
) -> None:
    """Record ``card_key`` as successfully pushed so it never re-POSTs."""
    _append_history(
        sent_log_file(paths),
        {"card_key": card_key},
        history_max_lines=sent_log_max_lines,
    )


def _summarise(card: dict[str, Any]) -> str:
    """One-line human summary of a card via its registered type, fail-soft.

    Reuses the same per-type ``summarise`` the CLI's ``push status`` uses so the
    notification body matches what the agent sees. Falls back to a generic line
    for an unknown type (forward-compatible).
    """
    from pocketshell.cards import get_card_type

    card_type = card.get("type", "")
    handler = get_card_type(card_type) if isinstance(card_type, str) else None
    body = card.get("body", {})
    state = card.get("state", {})
    if handler is not None and isinstance(body, dict) and isinstance(state, dict):
        try:
            return handler.summarise(dict(body), dict(state))
        except Exception:
            pass
    return f"{card_type or 'card'} updated"


def _host_alias() -> str:
    """Best-effort host identifier for the deep-link, never raising.

    The host CLI does NOT know the app's host alias (the #859 research spike's
    risk #2). The most reliable thing it can emit is its own resolvable
    hostname, which the app matches against ``HostEntity.hostname`` /
    ``HostEntity.name`` to route the tap to the right host's session feed. When
    the match is ambiguous or absent the app falls back to its home screen — it
    never opens the WRONG host.
    """
    try:
        fqdn = socket.getfqdn().strip()
        if fqdn and fqdn.lower() != "localhost":
            return fqdn
    except Exception:
        pass
    try:
        return socket.gethostname().strip()
    except Exception:
        return ""


def card_to_data(
    session: str,
    card: dict[str, Any],
    *,
    host: Optional[str] = None,
) -> dict[str, str]:
    """Map a card + its session to the app's ``agent_card`` data-message keys.

    Keys (mirrors the app's ``AgentCardPushPayload`` contract):

    - ``type`` = ``agent_card``
    - ``session`` = the tmux session the card belongs to (deep-link target)
    - ``host`` = best-effort host hostname for host resolution (may be empty)
    - ``card_id`` = the card id
    - ``card_type`` = e.g. ``checklist``
    - ``title`` = the card title (falls back to a type-derived label)
    - ``summary`` = the per-type one-line summary (e.g. ``checklist 1/3 checked``)
    - ``card_key`` = the content-hash de-dup identity (also the app dedup key)
    """
    card_type = str(card.get("type", "") or "")
    card_id = str(card.get("id", "") or "")
    title = card.get("title")
    title_str = title.strip() if isinstance(title, str) and title.strip() else _default_title(card_type)
    summary = _summarise(card)
    resolved_host = host if host is not None else _host_alias()
    card_key = content_key(session, card)
    return {
        "type": AGENT_CARD_PUSH_TYPE,
        "session": session,
        "host": resolved_host or "",
        "card_id": card_id,
        "card_type": card_type,
        "title": title_str,
        "summary": summary,
        "card_key": card_key,
    }


def _default_title(card_type: str) -> str:
    if card_type == "checklist":
        return "Checklist"
    if card_type:
        return card_type[:1].upper() + card_type[1:]
    return "Card"


def content_key(session: str, card: dict[str, Any]) -> str:
    """Deterministic content key for a card (drives server-side de-dup).

    Hashes the session + card id + type + title + body + state — NOT
    ``updated_at`` (which changes on every push) — so an identical re-push
    yields the SAME key (suppressed) while any content/state change yields a NEW
    key (pushed). Stable across processes (``sort_keys``).
    """
    payload = {
        "session": session,
        "id": card.get("id"),
        "type": card.get("type"),
        "title": card.get("title"),
        "body": card.get("body"),
        "state": card.get("state"),
    }
    blob = json.dumps(payload, sort_keys=True, ensure_ascii=False)
    digest = hashlib.sha256(blob.encode("utf-8")).hexdigest()[:16]
    return f"{session}|{card.get('id')}|{digest}"


def notify_card_pushed(
    session: str,
    card: dict[str, Any],
    *,
    card_paths: CardPaths,
    host: Optional[str] = None,
    sender: Optional["push_mod.FcmSender"] = None,
    env: Optional[dict[str, str]] = None,
    sent_log_max_lines: int = DEFAULT_SENT_LOG_MAX_LINES,
) -> Optional[str]:
    """Best-effort FCM push for a freshly upserted ``card``. Fail-soft.

    Returns the ``card_key`` actually pushed this call, or ``None`` when push
    isn't configured / nothing new was sent. NEVER raises — the ``push
    checklist`` CLI call must succeed even when Firebase isn't set up.

    Guards, in order (mirroring :func:`pocketshell.push.push_reset_events`):

    1. No registered device token -> ``None``.
    2. No service-account credential / no ``google.auth`` -> ``None`` (pre-S0).
    3. Already pushed this exact content (de-dup) -> ``None``.
    4. On a successful send: record the content key so it never re-POSTs.
       On a failed send: leave it un-recorded so the next push RETRIES.
    """
    try:
        usage_paths = resolve_usage_paths(env=env)
        token = push_mod.read_token(usage_paths)
        if token is None:
            return None

        if sender is None:
            sa_path = push_mod._resolve_service_account_path(usage_paths, env=env)
            if sa_path is None:
                return None
            sender = push_mod.FcmSender.from_service_account(sa_path)
            if sender is None:
                return None

        data = card_to_data(session, card, host=host)
        card_key = data["card_key"]
        if card_key in sent_card_keys(card_paths):
            return None
        if sender.send_data_message(token=token, data=data):
            _mark_sent(card_key, paths=card_paths, sent_log_max_lines=sent_log_max_lines)
            return card_key
        return None
    except Exception:
        # Absolute fail-soft backstop: a card push must never wedge the CLI.
        return None
