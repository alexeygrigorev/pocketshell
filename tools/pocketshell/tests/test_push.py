"""Unit tests for FCM push delivery of usage-reset events (issue #690).

Covers:
 - R1: `pocketshell push register-token <token>` persists atomically (0600)
 - R1: empty token is rejected (non-zero exit, nothing written)
 - R2: a new reset event POSTs an FCM HTTP v1 *data* message whose keys + auth
   header match the `ResetPushPayload` contract
 - R2: the once-per-reset_key server guard (a successful send is marked and
   NEVER re-POSTs; a failed send is NOT marked and RETRIES next call)
 - R2: fail-soft no-op when no token / no service-account credential is set
 - R2: the `usage --capture` hook fires the sender without breaking capture
"""

from __future__ import annotations

import json
import os
import stat
from pathlib import Path

from click.testing import CliRunner

from pocketshell import push
from pocketshell.cli import cli
from pocketshell.usage_capture import UsagePaths, write_capture


def _paths(tmp_path: Path) -> UsagePaths:
    return UsagePaths(usage_dir=tmp_path / "usage")


# ---------------------------------------------------------------------------
# Test doubles
# ---------------------------------------------------------------------------


class _RecordingSender(push.FcmSender):
    """An FcmSender that records sends without touching the network.

    ``outcomes`` maps a reset_key -> bool (the simulated send result). Any key
    not present defaults to a successful send.
    """

    def __init__(self, *, outcomes: dict[str, bool] | None = None) -> None:
        super().__init__(project_id="test-project", credentials=object())
        self.outcomes = outcomes or {}
        self.calls: list[dict] = []

    def send_data_message(self, *, token: str, data: dict[str, str]) -> bool:
        self.calls.append({"token": token, "data": dict(data)})
        return self.outcomes.get(data.get("reset_key", ""), True)


def _reset_event(reset_key: str = "codex|short_term|2026-06-11T15:00:00Z") -> dict:
    return {
        "type": "reset",
        "provider": "codex",
        "window": "short_term",
        "detected_at": "2026-06-11T14:30:00Z",
        "reset_key": reset_key,
    }


# ---------------------------------------------------------------------------
# R1 — token registration
# ---------------------------------------------------------------------------


def test_register_token_persists_atomically_with_0600(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    written = push.register_token("dev-token-abc", paths=paths)

    assert written == push.token_file(paths)
    assert push.read_token(paths) == "dev-token-abc"
    mode = stat.S_IMODE(os.stat(written).st_mode)
    assert mode == 0o600, f"token file must be 0600, got {oct(mode)}"


def test_register_token_rejects_empty(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    try:
        push.register_token("   ", paths=paths)
    except ValueError:
        pass
    else:
        raise AssertionError("empty token must raise ValueError")
    assert push.read_token(paths) is None


def test_cli_register_token_writes_token(tmp_path: Path) -> None:
    runner = CliRunner()
    env = {"XDG_STATE_HOME": str(tmp_path / "state")}
    result = runner.invoke(
        cli, ["push", "register-token", "cli-token-xyz"], env=env
    )
    assert result.exit_code == 0, result.output
    token_path = (
        tmp_path / "state" / "pocketshell" / "usage" / push.TOKEN_FILENAME
    )
    assert token_path.exists()
    assert json.loads(token_path.read_text())["token"] == "cli-token-xyz"


def test_cli_register_token_empty_is_rejected(tmp_path: Path) -> None:
    runner = CliRunner()
    env = {"XDG_STATE_HOME": str(tmp_path / "state")}
    # An explicit empty-string argument is accepted by argparse but rejected by
    # the store (a genuinely empty token is a caller bug).
    result = runner.invoke(cli, ["push", "register-token", ""], env=env)
    assert result.exit_code != 0


# ---------------------------------------------------------------------------
# R2 — FCM send: request shape + auth header
# ---------------------------------------------------------------------------


def test_post_request_shape_and_auth_header(tmp_path: Path) -> None:
    # Subclass only `_post` so the real send_data_message + bearer plumbing is
    # exercised, asserting the HTTP v1 envelope + Authorization header.
    captured: dict = {}

    class _CapturingSender(push.FcmSender):
        def _bearer(self) -> str:
            return "bearer-123"

        def _post(self, *, bearer: str, message: dict) -> bool:
            captured["bearer"] = bearer
            captured["message"] = message
            captured["endpoint"] = self.endpoint
            return True

    sender = _CapturingSender(project_id="my-fb-proj", credentials=object())
    data = push.reset_event_to_data(_reset_event())
    assert data is not None
    ok = sender.send_data_message(token="tok-1", data=data)

    assert ok is True
    assert captured["bearer"] == "bearer-123"
    assert (
        captured["endpoint"]
        == "https://fcm.googleapis.com/v1/projects/my-fb-proj/messages:send"
    )
    msg = captured["message"]
    assert msg["token"] == "tok-1"
    # A pure DATA message (no `notification` block) so the app builds it.
    assert "notification" not in msg
    assert msg["data"]["type"] == "usage_reset"
    assert msg["data"]["provider"] == "codex"
    assert msg["data"]["reset_key"] == _reset_event()["reset_key"]
    assert msg["data"]["title"] == "Codex limits reset"
    assert "Heavy work can resume" in msg["data"]["body"]


def test_reset_event_to_data_drops_event_without_reset_key() -> None:
    assert push.reset_event_to_data({"provider": "codex"}) is None
    assert push.reset_event_to_data({"reset_key": "  "}) is None


# ---------------------------------------------------------------------------
# R2 — once-per-reset_key server guard
# ---------------------------------------------------------------------------


def test_successful_send_is_marked_and_never_reposts(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    push.register_token("tok", paths=paths)
    sender = _RecordingSender()
    event = _reset_event()

    first = push.push_reset_events([event], paths=paths, sender=sender)
    assert first == [event["reset_key"]]
    assert len(sender.calls) == 1
    assert event["reset_key"] in push.sent_reset_keys(paths)

    # Second call with the SAME event must NOT re-POST (server-side guard).
    second = push.push_reset_events([event], paths=paths, sender=sender)
    assert second == []
    assert len(sender.calls) == 1, "a sent reset_key must never re-POST"


def test_failed_send_is_not_marked_and_retries(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    push.register_token("tok", paths=paths)
    key = _reset_event()["reset_key"]

    failing = _RecordingSender(outcomes={key: False})
    first = push.push_reset_events([_reset_event()], paths=paths, sender=failing)
    assert first == []
    assert key not in push.sent_reset_keys(paths), "a failed send must not mark"
    assert len(failing.calls) == 1

    # A later capture with a working sender RETRIES the same reset_key.
    working = _RecordingSender()
    retry = push.push_reset_events([_reset_event()], paths=paths, sender=working)
    assert retry == [key]
    assert len(working.calls) == 1
    assert key in push.sent_reset_keys(paths)


def test_multiple_events_each_sent_once(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    push.register_token("tok", paths=paths)
    sender = _RecordingSender()
    events = [
        _reset_event("codex|short_term|A"),
        _reset_event("claude|long_term|B"),
    ]
    pushed = push.push_reset_events(events, paths=paths, sender=sender)
    assert set(pushed) == {"codex|short_term|A", "claude|long_term|B"}
    assert len(sender.calls) == 2


# ---------------------------------------------------------------------------
# R2 — fail-soft no-op paths
# ---------------------------------------------------------------------------


def test_no_registered_token_is_a_noop(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    sender = _RecordingSender()
    # No token registered.
    pushed = push.push_reset_events([_reset_event()], paths=paths, sender=sender)
    assert pushed == []
    assert sender.calls == []


def test_no_service_account_credential_is_a_noop(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    push.register_token("tok", paths=paths)
    # No `sender` injected and no service-account JSON anywhere → fail-soft.
    pushed = push.push_reset_events(
        [_reset_event()], paths=paths, env={}
    )
    assert pushed == []


def test_from_service_account_missing_project_id_returns_none(tmp_path: Path) -> None:
    sa = tmp_path / "sa.json"
    sa.write_text(json.dumps({"type": "service_account"}))  # no project_id
    assert push.FcmSender.from_service_account(sa) is None


def test_empty_events_list_is_a_noop(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    push.register_token("tok", paths=paths)
    sender = _RecordingSender()
    assert push.push_reset_events([], paths=paths, sender=sender) == []
    assert sender.calls == []


# ---------------------------------------------------------------------------
# R2 — capture hook integration (fail-soft; never breaks capture)
# ---------------------------------------------------------------------------


def _usage_ndjson(percent: float, reset_at: str | None) -> str:
    record = {
        "provider": "codex",
        "status": "ok",
        "short_term": {"percent_remaining": percent, "reset_at": reset_at},
        "long_term": None,
        "block_reason": None,
        "error": None,
        "details": {},
    }
    return json.dumps(record) + "\n"


def test_capture_hook_pushes_new_reset_event(tmp_path: Path, monkeypatch) -> None:
    paths = _paths(tmp_path)
    push.register_token("tok", paths=paths)

    pushed_calls: list[list] = []

    def _fake_push(events, *, paths=None, **kwargs):
        pushed_calls.append(events)
        return [e["reset_key"] for e in events]

    monkeypatch.setattr(push, "push_reset_events", _fake_push)

    # First capture: low remaining, far reset_at → seeds the previous reading.
    write_capture(
        _usage_ndjson(8.0, "2026-06-11T15:00:00Z"),
        paths=paths,
        captured_at="2026-06-11T14:00:00Z",
    )
    assert pushed_calls == [], "first capture has no previous reading to compare"

    # Second capture: usage recovered to 100% → a reset is detected + pushed.
    write_capture(
        _usage_ndjson(100.0, "2026-06-11T19:00:00Z"),
        paths=paths,
        captured_at="2026-06-11T14:30:00Z",
    )
    assert len(pushed_calls) == 1
    assert pushed_calls[0], "a detected reset event must be handed to the sender"
    assert pushed_calls[0][0]["provider"] == "codex"


def test_capture_never_breaks_when_push_raises(tmp_path: Path, monkeypatch) -> None:
    paths = _paths(tmp_path)
    push.register_token("tok", paths=paths)

    def _boom(*args, **kwargs):
        raise RuntimeError("FCM exploded")

    monkeypatch.setattr(push, "push_reset_events", _boom)

    write_capture(
        _usage_ndjson(8.0, "2026-06-11T15:00:00Z"),
        paths=paths,
        captured_at="2026-06-11T14:00:00Z",
    )
    # Even if push delivery raises, the cache write must succeed.
    result = write_capture(
        _usage_ndjson(100.0, "2026-06-11T19:00:00Z"),
        paths=paths,
        captured_at="2026-06-11T14:30:00Z",
    )
    assert result["records"][0]["short_term"]["percent_remaining"] == 100.0
    assert paths.cache_file.exists()
