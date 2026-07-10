"""Regression tests: EVERY card-creating `push <type>` verb fires the FCM push.

Issue #1446 (#859 Slice 2). `push checklist` fired ``notify_card_pushed`` but
`push note` did NOT — so an agent that pushed a *note* produced no heads-up
notification and the maintainer only saw it by opening the session. These tests
are the durable class fix:

- ``test_push_note_fires_notification`` reproduces the exact reported gap
  (RED on base: not called; GREEN after the fix) and asserts the payload the
  app's deep-link (``AgentCardPushPayload``) consumes is producible from the
  pushed card.
- ``test_every_card_push_verb_fires_notification_once`` is parametrized across
  EVERY card-creating verb and asserts each notifies exactly once (no
  double-notify). The companion coverage guard asserts the parametrize map
  covers the whole card-type ``REGISTRY``, so a future new card type whose push
  verb forgets the notify call is caught here rather than on the maintainer's
  phone.

The read/query/interaction verbs (``get``, ``status``, ``check``, ``read``) do
NOT notify by design — see the ``#1446 audit`` comments on each in
``pocketshell.cards`` — so they are intentionally excluded here.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest
from click.testing import CliRunner

from pocketshell import agent_card_push as push_mod
from pocketshell import cards as cards_mod
from pocketshell.cli import cli


def _env(tmp_path: Path) -> dict[str, str]:
    """CLI env pointing the card store at tmp_path with a deterministic session."""
    return {
        "POCKETSHELL_CARDS_DIR": str(tmp_path / "pocketshell" / "cards"),
        "TMUX": "/tmp/fake-tmux,1,0",
    }


@pytest.fixture
def notify_spy(monkeypatch: pytest.MonkeyPatch) -> list[dict[str, Any]]:
    """Spy replacing ``agent_card_push.notify_card_pushed``.

    The push verbs import it lazily (``from pocketshell.agent_card_push import
    notify_card_pushed``), so patching the module attribute intercepts the call
    at invocation time. Records each call so we can assert count + arguments.
    """
    calls: list[dict[str, Any]] = []

    def _spy(session: str, card: dict[str, Any], *, card_paths: Any, **kwargs: Any) -> None:
        calls.append({"session": session, "card": card, "card_paths": card_paths})
        return None

    monkeypatch.setattr(push_mod, "notify_card_pushed", _spy)
    return calls


# --- the reported gap: `push note` must notify (RED on base, GREEN with fix) ---


def test_push_note_fires_notification(
    tmp_path: Path, notify_spy: list[dict[str, Any]]
) -> None:
    """`push note` fires notify_card_pushed exactly once with the note card.

    RED on base (the omitted call) — GREEN after the fix. Also proves the pushed
    card maps to the app's ``agent_card`` deep-link payload shape.
    """
    runner = CliRunner()
    env = _env(tmp_path)

    res = runner.invoke(
        cli,
        ["push", "note", "--title", "Heads up", "--text", "deploy done", "--session", "demo"],
        env=env,
    )
    assert res.exit_code == 0, res.output

    # Fired exactly once — no double-notify.
    assert len(notify_spy) == 1, f"push note must notify exactly once, got {len(notify_spy)}"

    call = notify_spy[0]
    assert call["session"] == "demo"
    card = call["card"]
    assert card["type"] == "note"
    assert card["id"] == cards_mod.DEFAULT_NOTE_ID
    assert card["body"]["text"] == "deploy done"

    # The payload the app deep-link consumes (AgentCardPushPayload contract) is
    # producible from the pushed card via the SAME mapping the checklist path
    # uses — no invented shape.
    data = push_mod.card_to_data(call["session"], card, host="devbox")
    assert data["type"] == push_mod.AGENT_CARD_PUSH_TYPE
    assert data["session"] == "demo"
    assert data["host"] == "devbox"
    assert data["card_type"] == "note"
    assert data["card_id"] == cards_mod.DEFAULT_NOTE_ID
    assert data["title"] == "Heads up"
    assert data["card_key"]  # de-dup identity present


# --- class coverage: EVERY card-creating verb notifies exactly once -----------

# Minimal invocation for each card-CREATING push verb, keyed by card type name.
# A newly registered card type MUST add its verb here (the coverage guard below
# asserts this map covers the whole REGISTRY), which forces the author to wire
# up + prove the notify call for the new type.
_CARD_PUSH_INVOCATIONS: dict[str, list[str]] = {
    "checklist": ["push", "checklist", "--item", "alpha", "--session", "demo"],
    "note": ["push", "note", "--text", "hello", "--session", "demo"],
}


def test_card_push_invocation_map_covers_every_registered_type() -> None:
    """Future-proofing: the notify class-coverage map must cover every card type.

    If someone registers a new card type but omits its push verb here, this
    fails — a deliberate tripwire so the new verb's notify wiring is proven by
    ``test_every_card_push_verb_fires_notification_once`` rather than silently
    shipping un-notified (the exact #1446 gap).
    """
    assert set(_CARD_PUSH_INVOCATIONS) == set(cards_mod.REGISTRY), (
        "card-type REGISTRY and the notify coverage map diverged: "
        f"registry={sorted(cards_mod.REGISTRY)} map={sorted(_CARD_PUSH_INVOCATIONS)}. "
        "A new card type's `push <type>` verb must be added here (and must notify)."
    )


@pytest.mark.parametrize("card_type,argv", sorted(_CARD_PUSH_INVOCATIONS.items()))
def test_every_card_push_verb_fires_notification_once(
    tmp_path: Path,
    notify_spy: list[dict[str, Any]],
    card_type: str,
    argv: list[str],
) -> None:
    """Each card-creating push verb fires notify_card_pushed exactly once."""
    runner = CliRunner()
    env = _env(tmp_path)

    res = runner.invoke(cli, argv, env=env)
    assert res.exit_code == 0, res.output

    assert len(notify_spy) == 1, (
        f"`{' '.join(argv[:2])}` must notify exactly once, got {len(notify_spy)}"
    )
    assert notify_spy[0]["card"]["type"] == card_type
    assert isinstance(notify_spy[0]["card_paths"], cards_mod.CardPaths)
