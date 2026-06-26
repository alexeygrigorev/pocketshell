"""Unit tests for the agent-card FCM push trigger (epic #859, Slice D).

Covers, per the issue acceptance criteria:
 - `pocketshell push checklist` fires an `agent_card` FCM data push carrying
   session / card_type / title / summary / card_key (REPRODUCE-FIRST: this is
   the load-bearing success-path test — red before the trigger was wired).
 - Fail-soft: no registered token -> no push, the CLI call still succeeds.
 - Fail-soft: no service-account credential -> no push, CLI still succeeds.
 - Server-side de-dup: re-pushing the SAME card does not re-POST; an UPDATED
   card (new content key) DOES push.
 - The data-message keys match the app's `AgentCardPushPayload` contract.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from click.testing import CliRunner

from pocketshell import agent_card_push, cards as cards_mod, push as push_mod
from pocketshell.cli import cli


class _RecordingSender(push_mod.FcmSender):
    """An FcmSender that records sends without touching the network."""

    def __init__(self, *, ok: bool = True) -> None:
        super().__init__(project_id="test-project", credentials=object())
        self.ok = ok
        self.calls: list[dict[str, Any]] = []

    def send_data_message(self, *, token: str, data: dict[str, str]) -> bool:
        self.calls.append({"token": token, "data": dict(data)})
        return self.ok


def _card_paths(tmp_path: Path) -> cards_mod.CardPaths:
    return cards_mod.CardPaths(cards_dir=tmp_path / "cards")


def _checklist_card(title: str = "Deploy", checked: list[str] | None = None) -> dict[str, Any]:
    return cards_mod.build_card(
        card_type="checklist",
        card_id="checklist",
        title=title,
        build_kwargs={
            "items": [
                {"id": "build-0", "text": "build"},
                {"id": "ship-1", "text": "ship"},
            ]
        },
        preset_checked=checked or [],
    )


def _state_env(tmp_path: Path, *, session: str = "demo", with_token: bool = True) -> dict[str, str]:
    """CLI/trigger env: relocate BOTH the cards store and the usage state dir.

    `XDG_STATE_HOME` relocates the usage dir (where the device token lives);
    `POCKETSHELL_CARDS_DIR` relocates the cards store + its sent-log.
    """
    state_home = tmp_path / "state"
    env = {
        "XDG_STATE_HOME": str(state_home),
        "POCKETSHELL_CARDS_DIR": str(tmp_path / "cards"),
        "TMUX": f"/tmp/x,1,0 {session}",
    }
    if with_token:
        usage_paths = push_mod.resolve_paths(env=env)
        push_mod.register_token("device-token-abc", paths=usage_paths)
    return env


# ---------------------------------------------------------------------------
# notify_card_pushed — success path + payload contract (REPRODUCE-FIRST)
# ---------------------------------------------------------------------------


def test_notify_card_pushed_sends_agent_card_data_message(tmp_path: Path) -> None:
    env = _state_env(tmp_path)
    sender = _RecordingSender()
    card = _checklist_card(checked=["build-0"])

    card_key = agent_card_push.notify_card_pushed(
        "demo",
        card,
        card_paths=_card_paths(tmp_path),
        host="agent-box.example",
        sender=sender,
        env=env,
    )

    assert card_key is not None
    assert len(sender.calls) == 1
    data = sender.calls[0]["data"]
    # The app's AgentCardPushPayload contract.
    assert data["type"] == "agent_card"
    assert data["session"] == "demo"
    assert data["host"] == "agent-box.example"
    assert data["card_id"] == "checklist"
    assert data["card_type"] == "checklist"
    assert data["title"] == "Deploy"
    assert data["summary"] == "checklist 1/2 checked"
    assert data["card_key"] == card_key
    assert sender.calls[0]["token"] == "device-token-abc"


def test_notify_card_pushed_default_host_is_resolvable_hostname(tmp_path: Path) -> None:
    env = _state_env(tmp_path)
    sender = _RecordingSender()
    agent_card_push.notify_card_pushed(
        "demo", _checklist_card(), card_paths=_card_paths(tmp_path), sender=sender, env=env
    )
    # host best-effort: a non-None string (may be empty in an exotic sandbox,
    # but the KEY is always present so the app can parse it).
    assert "host" in sender.calls[0]["data"]


# ---------------------------------------------------------------------------
# Fail-soft guards
# ---------------------------------------------------------------------------


def test_notify_card_pushed_no_token_is_noop(tmp_path: Path) -> None:
    env = _state_env(tmp_path, with_token=False)
    sender = _RecordingSender()
    result = agent_card_push.notify_card_pushed(
        "demo", _checklist_card(), card_paths=_card_paths(tmp_path), sender=sender, env=env
    )
    assert result is None
    assert sender.calls == []


def test_notify_card_pushed_no_credential_is_noop(tmp_path: Path) -> None:
    # Token present but NO sender passed and NO service-account JSON on disk ->
    # FcmSender.from_service_account path can't resolve -> no-op (pre-S0 host).
    env = _state_env(tmp_path)
    result = agent_card_push.notify_card_pushed(
        "demo", _checklist_card(), card_paths=_card_paths(tmp_path), sender=None, env=env
    )
    assert result is None


# ---------------------------------------------------------------------------
# Server-side de-dup
# ---------------------------------------------------------------------------


def test_notify_card_pushed_dedups_identical_repush(tmp_path: Path) -> None:
    env = _state_env(tmp_path)
    paths = _card_paths(tmp_path)
    sender = _RecordingSender()
    card = _checklist_card(checked=["build-0"])

    first = agent_card_push.notify_card_pushed("demo", card, card_paths=paths, sender=sender, env=env)
    # An identical re-push (same content; build_card bumps updated_at but the
    # content key ignores it) must NOT re-POST.
    second = agent_card_push.notify_card_pushed("demo", card, card_paths=paths, sender=sender, env=env)

    assert first is not None
    assert second is None
    assert len(sender.calls) == 1


def test_notify_card_pushed_updated_card_pushes_again(tmp_path: Path) -> None:
    env = _state_env(tmp_path)
    paths = _card_paths(tmp_path)
    sender = _RecordingSender()

    agent_card_push.notify_card_pushed(
        "demo", _checklist_card(checked=[]), card_paths=paths, sender=sender, env=env
    )
    # A genuine state change (an item now checked) is a NEW content key -> push.
    updated = agent_card_push.notify_card_pushed(
        "demo", _checklist_card(checked=["build-0"]), card_paths=paths, sender=sender, env=env
    )

    assert updated is not None
    assert len(sender.calls) == 2


def test_notify_card_pushed_failed_send_not_marked_retries(tmp_path: Path) -> None:
    env = _state_env(tmp_path)
    paths = _card_paths(tmp_path)
    card = _checklist_card()

    failing = _RecordingSender(ok=False)
    assert (
        agent_card_push.notify_card_pushed("demo", card, card_paths=paths, sender=failing, env=env)
        is None
    )
    # A later attempt with a now-working sender must RETRY (the first failure
    # left the key un-marked).
    ok = _RecordingSender(ok=True)
    assert (
        agent_card_push.notify_card_pushed("demo", card, card_paths=paths, sender=ok, env=env)
        is not None
    )
    assert len(ok.calls) == 1


def test_content_key_ignores_updated_at(tmp_path: Path) -> None:
    a = _checklist_card()
    b = dict(a)
    b["updated_at"] = "2099-01-01T00:00:00Z"  # different timestamp, same content
    assert agent_card_push.content_key("demo", a) == agent_card_push.content_key("demo", b)


def test_content_key_changes_on_state_change(tmp_path: Path) -> None:
    a = _checklist_card(checked=[])
    b = _checklist_card(checked=["build-0"])
    assert agent_card_push.content_key("demo", a) != agent_card_push.content_key("demo", b)


# ---------------------------------------------------------------------------
# CLI integration — `push checklist` fires the trigger, fail-soft otherwise
# ---------------------------------------------------------------------------


def test_cli_push_checklist_fires_trigger_when_configured(tmp_path: Path, monkeypatch: Any) -> None:
    env = _state_env(tmp_path)
    sender = _RecordingSender()
    monkeypatch.setattr(cards_mod, "_tmux_current_session", lambda: "demo")
    # Force the lazy trigger to use our recording sender (no real Firebase).
    real_notify = agent_card_push.notify_card_pushed

    def _notify(session, card, **kwargs):  # type: ignore[no-untyped-def]
        kwargs.setdefault("sender", sender)
        kwargs.setdefault("env", env)
        return real_notify(session, card, **kwargs)

    monkeypatch.setattr(agent_card_push, "notify_card_pushed", _notify)

    runner = CliRunner()
    result = runner.invoke(
        cli,
        ["push", "checklist", "--title", "Release"],
        input="- [ ] build\n- [x] ship\n",
        env=env,
    )
    assert result.exit_code == 0, result.output
    assert len(sender.calls) == 1
    data = sender.calls[0]["data"]
    assert data["type"] == "agent_card"
    assert data["session"] == "demo"
    assert data["card_type"] == "checklist"


def test_cli_push_checklist_succeeds_when_push_unconfigured(tmp_path: Path, monkeypatch: Any) -> None:
    # No token registered -> the trigger no-ops, but the CLI call STILL succeeds
    # (the agent's `push checklist` must never break on an unconfigured host).
    env = _state_env(tmp_path, with_token=False)
    monkeypatch.setattr(cards_mod, "_tmux_current_session", lambda: "demo")
    runner = CliRunner()
    result = runner.invoke(
        cli,
        ["push", "checklist", "--title", "Release"],
        input="- [ ] build\n",
        env=env,
    )
    assert result.exit_code == 0, result.output
    assert "checklist" in result.output


def test_cli_push_checklist_succeeds_even_if_trigger_raises(tmp_path: Path, monkeypatch: Any) -> None:
    # The trigger is best-effort: even a bug that raises must not fail the CLI.
    env = _state_env(tmp_path, with_token=False)
    monkeypatch.setattr(cards_mod, "_tmux_current_session", lambda: "demo")

    def _boom(*_a: Any, **_k: Any) -> None:
        raise RuntimeError("boom")

    monkeypatch.setattr(agent_card_push, "notify_card_pushed", _boom)
    runner = CliRunner()
    result = runner.invoke(
        cli, ["push", "checklist"], input="- [ ] build\n", env=env
    )
    assert result.exit_code == 0, result.output
