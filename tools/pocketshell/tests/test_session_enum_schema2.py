"""`pocketshell sessions list --json` schema 2.

Every row carries the full key set (no key-if-not-None omission — the
Android parser reads a fixed record and must never need a ``containsKey``
probe), and a backend that fails to enumerate lands in ``errors`` instead of
quietly shortening the session list (the #2426 contract).

The aplexer fixtures under ``tests/fixtures/aplexer/`` are REAL captures of
``a snapshot --json`` from the dev box, not hand-typed records:

- ``snapshot.json`` — the host-wide snapshot: mixed engines (codex/claude/
  grok), one ``exited`` row, one row with a ``profile``.
- ``snapshot-reported-state.json`` — an isolated aplexer instance
  (``XDG_STATE_HOME``/``XDG_RUNTIME_DIR`` redirected) with three sessions
  started for the capture, one of which really ran ``a state-report
  waiting`` inside itself, so ``reported_state``/``reported_state_at_ms``
  are genuine aplexer output rather than invented keys.
- ``snapshot-liveness-mix.json`` — a second isolated instance (issue #2554)
  holding one live session, one killed session (``phase: exited``) and one
  whose worker was ``SIGKILL``ed out from under it (``phase: running``,
  ``worker_alive: false`` — the zombie class that sat in the maintainer's
  tree for ten days). The fourth row, ``phase: failed``, is the ONE derived
  record in these fixtures: a failed worker startup leaves no record behind
  to capture (verified on the dev box), so it is the real ``exited`` capture
  with aplexer's other terminal phase (``Phase::Failed``,
  ``aplexer/src/lib.rs``) and its startup-failure ``error`` substituted in.
- ``snapshot-post-kill-window.json`` — the same instance captured in the
  window right after ``a kill``: the killed record is ``phase: exiting``
  with ``worker_alive: true`` (the worker is still winding down). This is
  the window that makes a fire-and-forget reap a no-op.
- ``snapshot-mid-create.json`` — a record captured DURING ``a start``, by
  polling ``a --json list`` through a real create: ``phase: starting`` with
  no ``worker_pid`` yet and therefore ``worker_alive: false``, because
  aplexer persists the record before the worker registers its pid
  (``SessionRecord::worker_alive`` returns false for a ``None`` pid). Every
  ``a start`` passes through this shape for tens of milliseconds. Calling it
  dead hides a session that is being created — the #2547 symptom.
- ``snapshot-agent.json`` — captured against the PINNED aplexer 0.1.4
  (issue #2581), the first release that derives an ``agent`` field. Three
  sessions in one isolated instance, all ``engine: "shell"`` (the production
  shape — PocketShell never sets an engine, the agent is launched by hand
  inside the session): one whose workload shell has a live child named
  ``claude``, one with a child named ``codex``, and one plain ``sleep``.
  aplexer reports ``agent: "claude"``, ``"codex"`` and ``null`` respectively,
  which is the whole point of the field: ``engine`` is ``"shell"`` on all
  three and therefore cannot tell them apart.
- ``snapshot-crashed-start.json`` — the OTHER side of that boundary,
  captured by SIGKILLing a real ``a start``'s process group inside the
  pre-PID window: byte-identical in shape (``starting`` / no ``worker_pid``
  / ``worker_alive: false``) and it never changes again. aplexer's own
  ``a list`` renders it ``✗ broken``. The two fixtures differ only in age,
  which is why age is the discriminator.

All but ``snapshot-agent.json`` were captured with the maintainer's
``a 0.1.3``; the key set was compared against the copy PINNED in this
package's venv and is identical, and none of them carries an ``agent`` or a
``state`` field, because 0.1.3 emits neither. That makes them the
older-aplexer fixtures for free: every assertion below that an ``agent``-less
row reads as ``agent: null`` (never a ``KeyError``) runs against real 0.1.3
output rather than a hand-deleted key.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any, Optional, Sequence
from unittest.mock import patch

from click.testing import CliRunner

from pocketshell import session_enum
from pocketshell import sessions as sessions_module
from pocketshell.sessions import sessions_group

FIXTURES = Path(__file__).parent / "fixtures" / "aplexer"

# Full schema-2 row contract. Order is irrelevant; presence is not.
SCHEMA2_ROW_KEYS = {
    "name",
    "manager",
    "id",
    "workspace",
    "tag",
    "engine",
    "profile",
    # Issue #2581: WHICH agent aplexer sees running inside the session. Not a
    # restatement of `engine` — see the agent tests below.
    "agent",
    "agent_state",
    "agent_state_source",
    "attached",
    "created_epoch",
    "activity_epoch",
    # Issue #2554: the wire carries liveness explicitly, so a client never has
    # to infer "is this row attachable" from the absence of a filter.
    "phase",
    "alive",
}

# ids from the real captures
REPORTED_ID = "7813f1ca-891d-4b43-829f-aca6ee182f10"
HEURISTIC_ID = "b95a85e9-6f71-4b33-b34a-a2344da72910"
PLAIN_SHELL_ID = "97d6e5f9-3a92-41c5-bbe4-d367b5017415"
# ids from snapshot-liveness-mix.json (issue #2554)
LIVE_ID = "b3351d63-dce8-49cd-aab3-32e200901863"
KILLED_ID = "dcef0eda-b4cf-4bde-9289-db45a11eea01"
FAILED_ID = "f1a11ed0-0000-4000-8000-000000000001"
ZOMBIE_ID = "c06513a5-ee94-4081-b4b1-6d4d39d22ac5"
# id from snapshot-mid-create.json (real capture during `a start`)
MID_CREATE_ID = "078e2bf5-8e37-44e6-b1cf-8f72e261495e"
# id from snapshot-crashed-start.json (real capture of a killed `a start`)
CRASHED_START_ID = "6b1ddabb-1b5c-4a76-b46b-1f7e70d78a9a"
# ids from snapshot-agent.json (real aplexer 0.1.4 capture, issue #2581)
AGENT_CLAUDE_ID = "f8514235-4a65-4139-b8fa-c8c917cb5d4b"
AGENT_CODEX_ID = "0ef96cac-7169-4e8d-90bc-3ff95d5be3fe"
AGENT_NONE_ID = "e4da25b0-22f2-4612-aa07-e3eb7be69d8e"
# `now` for snapshot-agent.json: a few seconds after the capture, so all
# three records are comfortably live.
AGENT_NOW_MS = 1_788_711_835_000
# Captured values, read back from the fixture rather than restated here.


def _load(name: str) -> Any:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def _row(payload: Any, ident: str) -> dict[str, Any]:
    return next(row for row in payload if row["id"] == ident)


def _tmuxctl_table() -> str:
    return (
        "IDX  SESSION               CREATED\n"
        "1    git-pocketshell       2026-08-31 08:56:12 \n"
        "2    git-aplexer           2026-08-26 13:09:26 \n"
        "\n"
        "Join a session: tmuxctl <id> or tmuxctl <session>\n"
    )


def _by_name(sessions: Sequence[session_enum.LiveSession], name: str):
    return next(row for row in sessions if row.name == name)


# ---------------------------------------------------------------------------
# host shapes: tmux-only, aplexer-only, both
# ---------------------------------------------------------------------------


def test_tmux_only_host_emits_schema_2_with_every_key_present() -> None:
    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        include_aplexer=False,
    )
    payload = session_enum.json_payload(sessions, errors)

    assert payload["schema"] == 2
    assert payload["managers"] == ["tmux"]
    assert payload["errors"] == []
    assert [row["name"] for row in payload["sessions"]] == [
        "git-pocketshell",
        "git-aplexer",
    ]
    for row in payload["sessions"]:
        assert set(row) == SCHEMA2_ROW_KEYS
        assert row["manager"] == "tmux"
        # A tmux row has no aplexer identity and — until aplexer can adopt
        # foreign tmux sessions — no agent state either. Explicit nulls, not
        # absent keys.
        assert row["id"] is None
        assert row["tag"] is None
        assert row["agent_state"] is None
        assert row["agent_state_source"] is None
        assert row["attached"] is False
        # CREATED is rendered in local time by tmuxctl; parsing it back must
        # round-trip through the same local zone.
        assert isinstance(row["created_epoch"], int)


def test_aplexer_only_host_emits_schema_2_rows() -> None:
    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=None,
        aplexer_payload=_load("snapshot-reported-state.json"),
        now_ms=1_788_409_006_000,
    )
    payload = session_enum.json_payload(sessions, errors)

    assert payload["schema"] == 2
    assert payload["managers"] == ["aplexer"]
    assert payload["errors"] == []
    assert {row["name"] for row in payload["sessions"]} == {
        "h1-fixture-ws:reported",
        "h1-fixture-ws:heuristic",
        "h1-fixture-ws:plain",
    }
    for row in payload["sessions"]:
        assert set(row) == SCHEMA2_ROW_KEYS
        assert row["manager"] == "aplexer"
        assert row["workspace"] == "/tmp/h1-fixture-ws"
        assert isinstance(row["id"], str) and row["id"]


def test_both_managers_are_listed_together() -> None:
    """Both managers contribute rows — from the LIVE records only (#2554).

    ``snapshot-liveness-mix.json`` holds four aplexer records and exactly one
    live one, so this also pins the union path against the dead-row leak.
    """
    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        aplexer_payload=_load("snapshot-liveness-mix.json"),
        now_ms=1_788_682_060_000,
    )
    payload = session_enum.json_payload(sessions, errors)

    assert payload["schema"] == 2
    assert payload["managers"] == ["tmux", "aplexer"]
    assert payload["errors"] == []
    managers = [row["manager"] for row in payload["sessions"]]
    assert managers.count("tmux") == 2
    assert managers.count("aplexer") == 1
    for row in payload["sessions"]:
        assert set(row) == SCHEMA2_ROW_KEYS


# ---------------------------------------------------------------------------
# errors[] — a failing backend is never a silently shorter list (#2426)
# ---------------------------------------------------------------------------


def test_failing_aplexer_binary_reports_an_error_and_keeps_tmux_rows(
    install_fake_a,
) -> None:
    install_fake_a(exit_code=1)

    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
    )

    # The load-bearing pair: the failure is visible AND tmux still lists.
    assert [error["manager"] for error in errors] == ["aplexer"]
    assert "failed" in errors[0]["message"]
    assert [row.name for row in sessions] == ["git-pocketshell", "git-aplexer"]

    payload = session_enum.json_payload(sessions, errors)
    assert payload["errors"] == errors
    assert payload["managers"] == ["tmux"]
    assert len(payload["sessions"]) == 2


def test_absent_aplexer_binary_is_not_an_error() -> None:
    """A tmux-only host is healthy, not broken — no error entry for it."""
    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        env={"POCKETSHELL_APLEXER": "1", "APLEXER_BIN": "", "PATH": "/nonexistent"},
    )
    assert errors == []
    assert len(sessions) == 2


def test_disabled_aplexer_kill_switch_is_not_an_error(install_fake_a) -> None:
    install_fake_a(snapshot=[])
    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        env={"POCKETSHELL_APLEXER_SESSIONS": "0"},
    )
    assert errors == []
    assert len(sessions) == 2


def test_non_list_aplexer_snapshot_reports_an_error(install_fake_a) -> None:
    install_fake_a(stdout='{"sessions": []}')
    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
    )
    assert [error["manager"] for error in errors] == ["aplexer"]
    assert "expected a list" in errors[0]["message"]
    assert len(sessions) == 2


def test_failing_tmuxctl_reports_an_error_and_keeps_aplexer_rows() -> None:
    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=None,
        tmuxctl_error="tmuxctl: tmux server unavailable",
        aplexer_payload=_load("snapshot-reported-state.json"),
        now_ms=1_788_409_006_000,
    )
    assert errors == [
        {"manager": "tmux", "message": "tmuxctl: tmux server unavailable"}
    ]
    assert {row.manager for row in sessions} == {"aplexer"}
    payload = session_enum.json_payload(sessions, errors)
    assert payload["errors"][0]["manager"] == "tmux"


def test_both_backends_failing_reports_both_errors(install_fake_a) -> None:
    install_fake_a(exit_code=1)
    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=None,
        tmuxctl_error="tmuxctl: not installed",
    )
    assert [error["manager"] for error in errors] == ["tmux", "aplexer"]
    assert sessions == []
    payload = session_enum.json_payload(sessions, errors)
    assert payload["sessions"] == []
    assert len(payload["errors"]) == 2


# ---------------------------------------------------------------------------
# aplexer agent state: reported push vs PTY-recency heuristic
# ---------------------------------------------------------------------------


def test_reported_state_row_is_authoritative_while_fresh() -> None:
    snapshot = _load("snapshot-reported-state.json")
    reported = _row(snapshot, REPORTED_ID)
    assert reported["reported_state"] == "waiting"  # real capture, not injected
    at_ms = reported["reported_state_at_ms"]

    sessions = session_enum.sessions_from_aplexer_snapshot(
        snapshot, now_ms=at_ms + 1_000
    )
    row = _by_name(sessions, "h1-fixture-ws:reported")
    assert row.agent_state == "waiting"
    assert row.agent_state_source == "reported"
    assert row.engine == "claude"
    assert row.profile == "zlaude"
    assert row.tag == "reported"
    assert row.activity_epoch == reported["last_activity_ms"] // 1000


def test_reported_state_falls_back_to_the_heuristic_once_stale() -> None:
    snapshot = _load("snapshot-reported-state.json")
    reported = _row(snapshot, REPORTED_ID)
    stale_now = (
        reported["reported_state_at_ms"]
        + session_enum.APLEXER_REPORTED_STATE_STALE_MS
        + 1
    )

    sessions = session_enum.sessions_from_aplexer_snapshot(snapshot, now_ms=stale_now)
    row = _by_name(sessions, "h1-fixture-ws:reported")
    # The push no longer wins; the source flips to the honest heuristic.
    assert row.agent_state_source == "heuristic"


def test_row_without_reported_state_uses_the_pty_recency_heuristic() -> None:
    snapshot = _load("snapshot-reported-state.json")
    heuristic = _row(snapshot, HEURISTIC_ID)
    assert "reported_state" not in heuristic  # no push was ever made here
    activity = heuristic["last_activity_ms"]

    busy = session_enum.sessions_from_aplexer_snapshot(snapshot, now_ms=activity + 500)
    row = _by_name(busy, "h1-fixture-ws:heuristic")
    assert (row.agent_state, row.agent_state_source) == ("working", "heuristic")

    quiet_now = activity + session_enum.APLEXER_ACTIVITY_THRESHOLD_MS + 1
    quiet = session_enum.sessions_from_aplexer_snapshot(snapshot, now_ms=quiet_now)
    row = _by_name(quiet, "h1-fixture-ws:heuristic")
    assert (row.agent_state, row.agent_state_source) == ("waiting", "heuristic")


def test_plain_shell_session_reports_a_null_engine() -> None:
    snapshot = _load("snapshot-reported-state.json")
    assert _row(snapshot, PLAIN_SHELL_ID)["engine"] == "shell"

    sessions = session_enum.sessions_from_aplexer_snapshot(
        snapshot, now_ms=1_788_409_006_000
    )
    row = _by_name(sessions, "h1-fixture-ws:plain")
    assert row.engine is None
    assert row.profile is None
    assert row.to_payload(schema=2)["engine"] is None


def test_exited_session_has_no_agent_state() -> None:
    """A terminal-phase record has no agent state — checked on the DEAD list.

    Since #2554 an exited record never reaches the live listing, so the
    assertion moved onto the dead projection, which is where such a row is
    now visible.
    """
    snapshot = _load("snapshot.json")
    exited = [row for row in snapshot if row["phase"] == "exited"]
    assert exited, "fixture must contain a terminal-phase row"

    dead = session_enum.dead_sessions_from_aplexer_snapshot(
        snapshot, now_ms=1_788_409_006_000
    )
    row = _by_name(dead, "zcode-acp:zcodex-test")
    assert row.agent_state is None
    assert row.agent_state_source is None
    assert row.phase == "exited"
    assert row.alive is False


# ---------------------------------------------------------------------------
# `agent` — WHICH agent is running in the session (issue #2581, aplexer 0.1.4)
# ---------------------------------------------------------------------------
#
# `engine` cannot answer this. Every session PocketShell creates is
# `engine: "shell"` with the agent started by hand inside it, so `engine` is
# `null` for exactly the rows a user would call "my claude session". aplexer
# 0.1.4 derives `agent` per query from the workload's descendant process tree
# and emits it on every `a list --json` / `a snapshot` row; the host passes it
# straight through on schema 2.


def test_aplexer_row_names_the_agent_running_inside_it() -> None:
    """The real 0.1.4 capture: claude, codex, and none — all `engine: shell`."""
    snapshot = _load("snapshot-agent.json")
    assert {row["engine"] for row in snapshot} == {"shell"}, (
        "fixture precondition: all three rows are the production shape, so "
        "`engine` cannot be what tells them apart"
    )

    sessions = session_enum.sessions_from_aplexer_snapshot(
        snapshot, now_ms=AGENT_NOW_MS
    )

    by_id = {row.aplexer_id: row for row in sessions}
    assert by_id[AGENT_CLAUDE_ID].agent == "claude"
    assert by_id[AGENT_CODEX_ID].agent == "codex"
    assert by_id[AGENT_NONE_ID].agent is None
    # ...and the field it is NOT a restatement of.
    assert {row.engine for row in sessions} == {None}


def test_schema_2_emits_the_agent_on_every_row() -> None:
    """"Every key, always" covers `agent`: named, null, and tmux alike."""
    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        aplexer_payload=_load("snapshot-agent.json"),
        now_ms=AGENT_NOW_MS,
    )
    payload = session_enum.json_payload(sessions, errors)

    for row in payload["sessions"]:
        assert set(row) == SCHEMA2_ROW_KEYS
        assert "agent" in row

    agents = {row["id"]: row["agent"] for row in payload["sessions"]}
    assert agents[AGENT_CLAUDE_ID] == "claude"
    assert agents[AGENT_CODEX_ID] == "codex"
    assert agents[AGENT_NONE_ID] is None


def test_tmux_rows_never_carry_an_agent() -> None:
    """A tmux row has no process-tree authority behind it: explicit null.

    tmuxctl exposes no workload pid to walk, and the host must not guess one
    from the session name, so the key is present and null rather than absent
    or invented (the same rule `agent_state` follows until APX-ADOPT).
    """
    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        include_aplexer=False,
    )
    payload = session_enum.json_payload(sessions, errors)

    assert payload["sessions"], "fixture must produce tmux rows"
    for row in payload["sessions"]:
        assert row["manager"] == "tmux"
        assert "agent" in row
        assert row["agent"] is None


def test_an_aplexer_without_the_agent_key_reads_as_null_not_a_keyerror() -> None:
    """An older `a` (0.1.3, the previous pin) simply omits the key.

    The pin is a floor, not a promise about the binary a given host runs — a
    stale bundled wheel, or the fixture image mid-rebuild, answers the probe
    with no `agent` field at all. That is "cannot tell", which serialises as
    null; it must never be an exception that blanks the whole session list.
    `snapshot-reported-state.json` is a REAL 0.1.3 capture, so this is the
    genuine older-aplexer wire shape rather than a hand-deleted key.
    """
    snapshot = _load("snapshot-reported-state.json")
    assert all("agent" not in row for row in snapshot), (
        "fixture precondition: these captures predate the `agent` field"
    )

    sessions = session_enum.sessions_from_aplexer_snapshot(
        snapshot, now_ms=1_788_409_006_000
    )

    assert sessions, "fixture must produce aplexer rows"
    for row in sessions:
        assert row.agent is None
        payload = row.to_payload(schema=2)
        assert set(payload) == SCHEMA2_ROW_KEYS
        assert payload["agent"] is None


def test_an_empty_or_non_string_agent_reads_as_null() -> None:
    """Defensive read: only a non-empty string is an agent name.

    ``["claude"]`` matters more than it looks: a ``str()`` coercion would put
    the literal name ``"['claude']"`` on the wire instead of admitting it
    could not tell.
    """
    live = _row(_load("snapshot-agent.json"), AGENT_CLAUDE_ID)
    for value in ("", "   ", None, 0, [], {}, ["claude"], 7, True):
        grown = [dict(live, agent=value)]
        row = session_enum.sessions_from_aplexer_snapshot(
            grown, now_ms=AGENT_NOW_MS
        )[0]
        assert row.agent is None, f"agent={value!r} should read as null"


def test_a_dead_row_still_serialises_its_agent_key() -> None:
    """The dead projection is schema 2 too — no key may go missing there.

    aplexer never probes the process tree of a terminal-phase record (its
    `workload_pid` names a process that is gone and a recycled pid must not
    resurrect an agent), so a dead row's `agent` is null in practice; what is
    pinned here is that the KEY is emitted regardless.
    """
    dead = session_enum.dead_sessions_from_aplexer_snapshot(
        _load("snapshot-liveness-mix.json"), now_ms=1_788_682_060_000
    )

    assert dead, "fixture must contain dead records"
    for row in dead:
        payload = row.to_payload(schema=2)
        assert set(payload) == SCHEMA2_ROW_KEYS
        assert payload["agent"] is None


def test_cli_list_json_carries_the_agent_end_to_end(install_fake_a) -> None:
    """Through the real CLI: `sessions list --json` reaches stdout with `agent`.

    The fake `a` replays the 0.1.4 capture verbatim, so this exercises the
    production probe -> `_aplexer_rows` -> schema-2 payload path the phone
    reads, not just the row builder.
    """
    install_fake_a(snapshot=_load("snapshot-agent.json"))

    result = _invoke_list_json(tmuxctl_stdout=_tmuxctl_table())

    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert payload["errors"] == []
    agents = {row["name"]: row["agent"] for row in payload["sessions"]}
    assert agents["ws:claude"] == "claude"
    assert agents["ws:codex"] == "codex"
    assert agents["ws:plain"] is None
    assert agents["git-pocketshell"] is None


def test_aplexer_rows_are_not_attached_without_a_snapshot_field() -> None:
    """aplexer 0.1.3 exposes no attached-client count; the row says False."""
    snapshot = _load("snapshot-liveness-mix.json")
    assert all("attached_clients" not in row for row in snapshot)
    sessions = session_enum.sessions_from_aplexer_snapshot(
        snapshot, now_ms=1_788_682_060_000
    )
    assert sessions, "fixture must contain a live row"
    assert all(row.attached is False for row in sessions)

    # ...and starts telling the truth the moment aplexer grows the field.
    live = _row(snapshot, LIVE_ID)
    grown = [dict(live, attached_clients=2)]
    assert session_enum.sessions_from_aplexer_snapshot(
        grown, now_ms=1_788_682_060_000
    )[0].attached is True


# ---------------------------------------------------------------------------
# liveness — a dead aplexer record is never an attachable row (issue #2554)
# ---------------------------------------------------------------------------
#
# Reported from the phone: the tree kept offering `pocketshell:pocketshell`,
# a record aplexer had already reaped the worker for. Tapping it ran
# `a attach <id>`, which answers "session … has already exited" and exits 1,
# so the app showed `Session "…" ended (exit 1)`.


def test_only_the_live_record_is_listed_from_a_mixed_snapshot() -> None:
    """exited + failed + zombie + live -> one row (the live one)."""
    snapshot = _load("snapshot-liveness-mix.json")
    assert {row["id"] for row in snapshot} == {
        LIVE_ID,
        KILLED_ID,
        FAILED_ID,
        ZOMBIE_ID,
    }
    # The four states this filter has to tell apart, straight off the capture.
    assert (_row(snapshot, KILLED_ID)["phase"], _row(snapshot, KILLED_ID)["worker_alive"]) == ("exited", False)
    assert (_row(snapshot, FAILED_ID)["phase"], _row(snapshot, FAILED_ID)["worker_alive"]) == ("failed", False)
    assert (_row(snapshot, ZOMBIE_ID)["phase"], _row(snapshot, ZOMBIE_ID)["worker_alive"]) == ("running", False)
    assert (_row(snapshot, LIVE_ID)["phase"], _row(snapshot, LIVE_ID)["worker_alive"]) == ("running", True)

    sessions = session_enum.sessions_from_aplexer_snapshot(
        snapshot, now_ms=1_788_682_060_000
    )

    assert [row.aplexer_id for row in sessions] == [LIVE_ID]
    assert [row.name for row in sessions] == ["ws:live"]
    assert sessions[0].alive is True
    assert sessions[0].phase == "running"


def test_a_snapshot_of_only_dead_records_renders_zero_aplexer_sessions() -> None:
    """The maintainer's actual tree state (issue #2554), replayed.

    ``snapshot.json`` is a REAL host-wide capture in which every one of the
    four records is dead: one killed session (``zcode-acp:zcodex-test``)
    plus three ~10-day-old zombies whose workers died without recording an
    exit (``aplexer-follow:{yolo,zsp,live}``). Before the fix all four
    listed as ordinary, tappable rows — the same class as the
    ``pocketshell:pocketshell`` row in the report's screenshot, which came
    from the same host at a different moment and is not in this capture.
    """
    snapshot = _load("snapshot.json")
    assert len(snapshot) == 4
    assert all(
        row["phase"] in {"exited", "failed"} or row["worker_alive"] is False
        for row in snapshot
    )

    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        aplexer_payload=snapshot,
        now_ms=1_788_409_006_000,
    )
    payload = session_enum.json_payload(sessions, errors)

    assert [row["manager"] for row in payload["sessions"]] == ["tmux", "tmux"]
    assert payload["managers"] == ["tmux"]
    # Not an enumeration failure — aplexer answered, it just has nothing live.
    assert payload["errors"] == []
    # Gone by NAME, not merely by count: every dead row in the capture, named.
    listed = {row["name"] for row in payload["sessions"]}
    for gone in (
        "zcode-acp:zcodex-test",
        "aplexer-follow:yolo",
        "aplexer-follow:zsp",
        "aplexer-follow:live",
    ):
        assert gone not in listed


def test_a_record_winding_down_after_a_kill_is_still_listed() -> None:
    """``phase: exiting`` + a live worker is a live session, not a corpse.

    Captured in the real window right after ``a kill``. Filtering it here
    would make Stop look instant while ``a attach`` still works, and would
    make the reap's retry loop untestable.
    """
    snapshot = _load("snapshot-post-kill-window.json")
    winding_down = [row for row in snapshot if row["phase"] == "exiting"]
    assert len(winding_down) == 1
    assert winding_down[0]["worker_alive"] is True

    sessions = session_enum.sessions_from_aplexer_snapshot(
        snapshot, now_ms=1_788_682_060_000
    )
    assert sorted(row.name for row in sessions) == ["ws:doomed", "ws:live"]


def test_a_session_being_created_is_listed_not_hidden() -> None:
    """The real mid-create shape must stay visible (#2547's symptom class).

    aplexer writes the record before the worker registers its pid, and
    ``worker_alive`` is derived from that pid — so ``a --json list`` really
    emits ``phase: starting`` / no ``worker_pid`` / ``worker_alive: false``
    for tens of milliseconds on EVERY create. A predicate that reads
    ``worker_alive: false`` as "dead" blanks the session the user is
    watching appear, refuses to attach to it, and — worse — makes it an
    `a forget --force` target for a retried create.

    ``worker_alive: false`` is only a corpse when the worker had a pid to
    lose. With no pid the record is INDETERMINATE, and this filter fails
    open on indeterminate by design.
    """
    snapshot = _load("snapshot-mid-create.json")
    record = _row(snapshot, MID_CREATE_ID)
    # The capture really is the shape the fix has to survive.
    assert record["phase"] == "starting"
    assert record.get("worker_pid") is None
    assert record["worker_alive"] is False

    # Replayed at the age it really had: the record was observed in this
    # shape from t+29 ms to t+46 ms of a real `a start` before the pid
    # appeared, so the whole measured window must read alive.
    for age_ms in (0, 29, 46, 100):
        now_ms = record["updated_at_ms"] + age_ms
        assert session_enum.aplexer_record_is_alive(record, now_ms) is True, age_ms
        sessions = session_enum.sessions_from_aplexer_snapshot(snapshot, now_ms=now_ms)
        assert [row.name for row in sessions] == ["ws:midcreate"]
        assert sessions[0].alive is True
        assert sessions[0].phase == "starting"
        # ...and it is never offered up as something to reap.
        assert (
            session_enum.dead_sessions_from_aplexer_snapshot(snapshot, now_ms=now_ms)
            == []
        )


def test_a_start_killed_in_the_pre_pid_window_does_not_stay_alive_forever() -> None:
    """The other side of the boundary — a REAL crashed start (issue #2554 r3).

    SIGKILLing an ``a start`` inside the pre-PID window leaves a record at
    ``phase: starting`` / no ``worker_pid`` / ``worker_alive: false``
    PERMANENTLY — byte-identical in shape to the mid-create record above,
    and aplexer's own ``a list`` renders it ``✗ broken``. Exempting the
    pre-PID shape unconditionally therefore re-opened both halves of this
    issue through a different door: the tree offered it as a tappable row
    whose attach exits 1, and ``sessions create`` handed its id back as an
    existing session.

    Age is the discriminator. It is the same record shape; it is not the
    same age.
    """
    snapshot = _load("snapshot-crashed-start.json")
    record = _row(snapshot, CRASHED_START_ID)
    assert record["phase"] == "starting"
    assert record.get("worker_pid") is None
    assert record["worker_alive"] is False
    # A crashed start never writes again, so created == updated forever.
    assert record["updated_at_ms"] == record["created_at_ms"]

    stale = record["updated_at_ms"] + session_enum.APLEXER_STARTING_GRACE_MS + 1
    assert session_enum.aplexer_record_is_alive(record, stale) is False
    assert session_enum.sessions_from_aplexer_snapshot(snapshot, now_ms=stale) == []
    dead = session_enum.dead_sessions_from_aplexer_snapshot(snapshot, now_ms=stale)
    assert [(row.name, row.phase, row.alive) for row in dead] == [
        ("ws:crashed1", "starting", False)
    ]


def test_the_starting_grace_is_the_only_thing_between_the_two_captures() -> None:
    """Both real fixtures, one predicate, one boundary.

    Nothing but ``updated_at_ms`` distinguishes them — asserted here rather
    than described, so a future change that finds some other discriminator
    has to update this test on purpose.
    """
    mid = _load("snapshot-mid-create.json")[0]
    crashed = _load("snapshot-crashed-start.json")[0]
    differing = {
        key
        for key in set(mid) | set(crashed)
        if mid.get(key) != crashed.get(key)
    }
    # Identity/paths/timestamps differ; the LIVENESS fields do not.
    assert differing.isdisjoint({"phase", "worker_pid", "worker_alive"})

    grace = session_enum.APLEXER_STARTING_GRACE_MS
    for record in (mid, crashed):
        base = record["updated_at_ms"]
        assert session_enum.aplexer_record_is_alive(record, base) is True
        assert session_enum.aplexer_record_is_alive(record, base + grace) is True
        assert session_enum.aplexer_record_is_alive(record, base + grace + 1) is False


def test_the_starting_grace_leaves_orders_of_magnitude_of_headroom() -> None:
    """The threshold must stay far above a real spawn and above aplexer's own patience.

    Measured pre-PID window on the dev box: 26-46 ms. aplexer gives up on a
    worker after ``--startup-timeout-ms`` (default 10 s, ``a.rs``), and this
    CLI abandons `a start` after ``_APLEXER_START_TIMEOUT_S``. A record older
    than both cannot still be legitimately starting.
    """
    from pocketshell import sessions as _sessions

    assert session_enum.APLEXER_STARTING_GRACE_MS >= 1_000 * 46  # >=1000x measured
    assert session_enum.APLEXER_STARTING_GRACE_MS >= 6 * 10_000  # >=6x aplexer's own
    assert (
        session_enum.APLEXER_STARTING_GRACE_MS
        >= 3 * 1000 * _sessions._APLEXER_START_TIMEOUT_S
    )


def test_a_pre_pid_record_without_a_usable_timestamp_fails_open() -> None:
    """No age, no verdict: an un-aged pre-PID record stays listed."""
    record = _load("snapshot-crashed-start.json")[0]
    ancient = record["updated_at_ms"] + session_enum.APLEXER_STARTING_GRACE_MS + 1

    missing = {k: v for k, v in record.items() if k != "updated_at_ms"}
    assert session_enum.aplexer_record_is_alive(missing, ancient) is True

    for junk in ("", None, "soon", 0, -1):
        assert (
            session_enum.aplexer_record_is_alive(
                dict(record, updated_at_ms=junk), ancient
            )
            is True
        ), junk


def test_a_worker_that_had_a_pid_and_lost_it_is_still_dead() -> None:
    """The correction must not swallow the zombie it was written to catch."""
    snapshot = _load("snapshot-liveness-mix.json")
    zombie = _row(snapshot, ZOMBIE_ID)
    assert zombie["phase"] == "running"
    assert zombie["worker_alive"] is False
    assert isinstance(zombie["worker_pid"], int)  # it HAD a worker

    assert session_enum.aplexer_record_is_alive(zombie) is False


def test_a_pre_pid_record_in_a_terminal_phase_is_still_dead() -> None:
    """A pid never arriving does not outrank an explicitly recorded ending.

    aplexer's own launcher writes ``Phase::Failed`` with no ``worker_pid``
    when a worker dies before completing startup
    (``api.rs::persist_independent_cleanup_proof``), so the terminal-phase
    test has to win over the pre-PID exemption.
    """
    record = _load("snapshot-mid-create.json")[0]
    failed = dict(record, phase="failed", error="worker did not complete startup")
    assert session_enum.aplexer_record_is_alive(failed) is False

    exited = dict(record, phase="exited")
    assert session_enum.aplexer_record_is_alive(exited) is False


def test_a_record_with_no_liveness_keys_is_kept() -> None:
    """Fail OPEN: an unknown/older record shape must never lose its row.

    Only an explicit terminal phase or an explicit ``worker_alive: false``
    means dead. A snapshot that carries neither key (a hand-rolled fixture, a
    future aplexer that renames them) still lists — the failure mode of this
    filter must be "shows a stale row", never "hides a live session".
    """
    minimal = [{"id": "abc123", "tag": "codex", "workspace": "/home/a/git/toy"}]
    sessions = session_enum.sessions_from_aplexer_snapshot(minimal)
    assert [row.name for row in sessions] == ["toy:codex"]
    assert sessions[0].alive is True
    assert sessions[0].phase is None


def test_dead_projection_reports_every_dead_record_with_its_state() -> None:
    """``sessions attach`` needs the dead rows to explain itself."""
    snapshot = _load("snapshot-liveness-mix.json")
    dead = session_enum.dead_sessions_from_aplexer_snapshot(
        snapshot, now_ms=1_788_682_060_000
    )
    assert {row.aplexer_id: row.phase for row in dead} == {
        KILLED_ID: "exited",
        FAILED_ID: "failed",
        ZOMBIE_ID: "running",
    }
    assert all(row.alive is False for row in dead)


def test_schema_2_carries_liveness_on_every_row() -> None:
    """Schema 2's "every key, always" contract covers the new pair too."""
    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        aplexer_payload=_load("snapshot-liveness-mix.json"),
        now_ms=1_788_682_060_000,
    )
    payload = session_enum.json_payload(sessions, errors)

    for row in payload["sessions"]:
        assert set(row) == SCHEMA2_ROW_KEYS
        assert row["alive"] is True

    tmux_row = next(row for row in payload["sessions"] if row["manager"] == "tmux")
    # tmux has no aplexer phase vocabulary; explicit null, not an absent key.
    assert tmux_row["phase"] is None

    aplexer_row = next(
        row for row in payload["sessions"] if row["manager"] == "aplexer"
    )
    assert aplexer_row["phase"] == "running"

    # A dead row still serialises the pair truthfully when one is asked for.
    dead = session_enum.dead_sessions_from_aplexer_snapshot(
        _load("snapshot-liveness-mix.json"), now_ms=1_788_682_060_000
    )
    dead_payload = _by_name(dead, "ws:doomed").to_payload(schema=2)
    assert set(dead_payload) == SCHEMA2_ROW_KEYS
    assert (dead_payload["phase"], dead_payload["alive"]) == ("exited", False)


def test_cli_list_json_hides_dead_aplexer_records(install_fake_a) -> None:
    """End to end through the CLI: the tree cannot be offered a dead row."""
    install_fake_a(snapshot=_load("snapshot.json"))

    result = _invoke_list_json(tmuxctl_stdout=_tmuxctl_table())

    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert payload["errors"] == []
    assert [row["name"] for row in payload["sessions"]] == [
        "git-pocketshell",
        "git-aplexer",
    ]
    assert all(row["manager"] == "tmux" for row in payload["sessions"])


# ---------------------------------------------------------------------------
# tmux enrichment sweep
# ---------------------------------------------------------------------------


def _tmux_socket_dir() -> Path:
    return session_enum.tmux_socket_dir()


def _make_socket_dir() -> Path:
    directory = _tmux_socket_dir()
    directory.mkdir(parents=True, exist_ok=True)
    return directory


def _fake_tmux_runner(table: dict[str, str], missing_binary: bool = False):
    calls: list[list[str]] = []

    def run(argv: Sequence[str]) -> tuple[int, str, str]:
        calls.append(list(argv))
        if missing_binary:
            raise FileNotFoundError(2, "No such file or directory", "tmux")
        socket_path = argv[2]
        if socket_path in table:
            return 0, table[socket_path], ""
        return 1, "", f"no server running on {socket_path}\n"

    run.calls = calls  # type: ignore[attr-defined]
    return run


def test_tmux_rows_are_enriched_from_their_own_server() -> None:
    socket_dir = _make_socket_dir()
    runner = _fake_tmux_runner(
        {
            str(socket_dir / "tmuxctl-git-pocketshell"): (
                "git-pocketshell\t/home/alexey/git/pocketshell\t1"
                "\t1788159369\t1788381055\n"
            ),
            str(socket_dir / "tmuxctl-git-aplexer"): (
                "git-aplexer\t/home/alexey/git/aplexer\t0\t1787742566\t1788300000\n"
            ),
        }
    )

    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        include_aplexer=False,
        enrich_tmux=True,
        tmux_detail_runner=runner,
    )

    assert errors == []
    pocketshell = _by_name(sessions, "git-pocketshell")
    assert pocketshell.workspace == "/home/alexey/git/pocketshell"
    assert pocketshell.attached is True
    assert pocketshell.created_epoch == 1788159369
    assert pocketshell.activity_epoch == 1788381055

    aplexer_repo = _by_name(sessions, "git-aplexer")
    assert aplexer_repo.attached is False
    assert aplexer_repo.workspace == "/home/alexey/git/aplexer"

    payload = session_enum.json_payload(sessions, errors)
    assert payload["sessions"][0]["workspace"] == "/home/alexey/git/pocketshell"
    assert payload["sessions"][0]["attached"] is True


def test_tmux_row_without_a_reachable_server_still_lists() -> None:
    """A missed enrichment is a degraded field, never a dropped session."""
    _make_socket_dir()
    runner = _fake_tmux_runner({})

    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        include_aplexer=False,
        enrich_tmux=True,
        tmux_detail_runner=runner,
    )

    assert errors == []
    assert [row.name for row in sessions] == ["git-pocketshell", "git-aplexer"]
    row = _by_name(sessions, "git-pocketshell")
    assert row.workspace is None
    assert row.attached is False
    # The CREATED column is still parsed, so ordering data survives.
    assert row.created_epoch is not None


def test_tmux_sessions_on_the_default_socket_are_enriched_too() -> None:
    socket_dir = _make_socket_dir()
    runner = _fake_tmux_runner(
        {
            str(socket_dir / "default"): (
                "git-aplexer\t/home/alexey/git/aplexer\t2\t1787742566\t1788300000\n"
            )
        }
    )

    sessions, _errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        include_aplexer=False,
        enrich_tmux=True,
        tmux_detail_runner=runner,
    )
    row = _by_name(sessions, "git-aplexer")
    assert row.workspace == "/home/alexey/git/aplexer"
    assert row.attached is True


def test_missing_tmux_binary_is_an_enumeration_error() -> None:
    _make_socket_dir()
    runner = _fake_tmux_runner({}, missing_binary=True)

    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        include_aplexer=False,
        enrich_tmux=True,
        tmux_detail_runner=runner,
    )
    assert [error["manager"] for error in errors] == ["tmux"]
    assert "tmux session details unavailable" in errors[0]["message"]
    assert len(sessions) == 2


def test_enrichment_is_skipped_when_the_socket_directory_is_absent() -> None:
    """No tmux socket dir, no subprocess — and no phantom error either."""
    assert not _tmux_socket_dir().exists()
    runner = _fake_tmux_runner({})

    sessions, errors = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        include_aplexer=False,
        enrich_tmux=True,
        tmux_detail_runner=runner,
    )
    assert errors == []
    assert runner.calls == []  # type: ignore[attr-defined]
    assert len(sessions) == 2


def test_tmux_socket_dir_follows_tmux_tmpdir(monkeypatch) -> None:
    monkeypatch.setenv("TMUX_TMPDIR", "/run/user/9999")
    assert session_enum.tmux_socket_dir() == Path(
        f"/run/user/9999/tmux-{os.getuid()}"
    )


def test_parse_tmux_detail_lines_ignores_malformed_rows() -> None:
    parsed = session_enum.parse_tmux_detail_lines(
        "ok\t/home/x\t0\t1\t2\n"
        "truncated\t/home/y\t0\n"
        "\n"
    )
    assert set(parsed) == {"ok"}


# ---------------------------------------------------------------------------
# schema-1 payload shape is untouched
# ---------------------------------------------------------------------------


def test_schema_1_payload_still_omits_null_keys() -> None:
    row = session_enum.LiveSession(name="s", manager="tmux")
    assert row.to_payload() == {"name": "s", "manager": "tmux"}
    assert row.to_payload(schema=1) == {"name": "s", "manager": "tmux"}
    assert set(row.to_payload(schema=2)) == SCHEMA2_ROW_KEYS


# ---------------------------------------------------------------------------
# end-to-end through the CLI
# ---------------------------------------------------------------------------


def _fake_completed(stdout: str = "", stderr: str = "", returncode: int = 0):
    class _Completed:
        def __init__(self) -> None:
            self.stdout = stdout
            self.stderr = stderr
            self.returncode = returncode

    return _Completed()


def _invoke_list_json(
    tmuxctl_stdout: str = "",
    tmuxctl_returncode: int = 0,
    tmuxctl_stderr: str = "",
    tmuxctl_binary: Optional[str] = "/fake/tmuxctl",
):
    runner = CliRunner()
    with patch(
        "pocketshell.sessions._resolve_tmuxctl_binary", return_value=tmuxctl_binary
    ), patch(
        "pocketshell.sessions.subprocess.run",
        return_value=_fake_completed(
            stdout=tmuxctl_stdout,
            stderr=tmuxctl_stderr,
            returncode=tmuxctl_returncode,
        ),
    ):
        return runner.invoke(sessions_group, ["list", "--json"])


def test_cli_list_json_emits_schema_2(install_fake_a) -> None:
    install_fake_a(snapshot=_load("snapshot-reported-state.json"))

    result = _invoke_list_json(tmuxctl_stdout=_tmuxctl_table())

    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert payload["schema"] == 2
    assert payload["managers"] == ["tmux", "aplexer"]
    assert payload["errors"] == []
    assert len(payload["sessions"]) == 5
    for row in payload["sessions"]:
        assert set(row) == SCHEMA2_ROW_KEYS


def test_cli_list_json_surfaces_a_failing_aplexer_probe(install_fake_a) -> None:
    """#2426: the phone must see the failure, not a shorter list."""
    install_fake_a(exit_code=1)

    result = _invoke_list_json(tmuxctl_stdout=_tmuxctl_table())

    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert [error["manager"] for error in payload["errors"]] == ["aplexer"]
    assert [row["name"] for row in payload["sessions"]] == [
        "git-pocketshell",
        "git-aplexer",
    ]


def test_cli_list_json_surfaces_a_failing_tmuxctl() -> None:
    result = _invoke_list_json(
        tmuxctl_returncode=4, tmuxctl_stderr="tmuxctl: tmux server unavailable\n"
    )

    payload = json.loads(result.stdout)
    assert payload["schema"] == 2
    assert [error["manager"] for error in payload["errors"]] == ["tmux"]
    assert "tmux server unavailable" in payload["errors"][0]["message"]
    assert payload["sessions"] == []


def test_cli_list_json_surfaces_a_missing_tmuxctl_binary() -> None:
    result = _invoke_list_json(tmuxctl_binary=None)

    payload = json.loads(result.stdout)
    assert [error["manager"] for error in payload["errors"]] == ["tmux"]
    assert "not installed" in payload["errors"][0]["message"]


# ---------------------------------------------------------------------------
# daemon path carries the same envelope
# ---------------------------------------------------------------------------


def _envelope(payload: dict[str, Any]) -> dict[str, Any]:
    return {"stdout": json.dumps(payload), "stderr": "", "returncode": 0}


def test_daemon_json_envelope_must_be_schema_2() -> None:
    schema2 = _envelope(
        session_enum.json_payload(
            [session_enum.LiveSession(name="s", manager="tmux")], []
        )
    )
    assert sessions_module._is_schema2_list_envelope(schema2) is True

    # A daemon still running an older PocketShell answers schema 1. Per D22
    # there is no compatibility path: the reply is malformed, so the skew
    # surfaces instead of a schema-1 body reaching a schema-2 parser.
    schema1 = _envelope({"managers": ["tmux"], "sessions": []})
    assert sessions_module._is_schema2_list_envelope(schema1) is False
    assert sessions_module._is_schema2_list_envelope(_envelope({"schema": 2})) is False
    assert (
        sessions_module._is_schema2_list_envelope(
            {"stdout": "not json", "stderr": "", "returncode": 0}
        )
        is False
    )
    assert sessions_module._is_schema2_list_envelope({"stdout": "{}"}) is False


def test_daemon_served_list_json_reaches_stdout() -> None:
    """The daemon path serves the same schema-2 document as the local one."""
    served = session_enum.json_payload(
        [
            session_enum.LiveSession(
                name="git-pocketshell",
                manager="tmux",
                workspace="/home/alexey/git/pocketshell",
                attached=True,
            )
        ],
        [session_enum.enumeration_error("aplexer", "probe failed")],
    )
    runner = CliRunner()
    with patch(
        "pocketshell.sessions._try_daemon_sessions_list",
        return_value=_envelope(served),
    ) as daemon_call, patch("pocketshell.sessions.subprocess.run") as run:
        result = runner.invoke(sessions_group, ["list", "--json"])

    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert payload["schema"] == 2
    assert payload["errors"] == [{"manager": "aplexer", "message": "probe failed"}]
    assert payload["sessions"][0]["attached"] is True
    daemon_call.assert_called_once_with(sort_by=None, extra_args=[], as_json=True)
    run.assert_not_called()


def test_daemon_handler_list_json_is_schema_2(install_fake_a) -> None:
    install_fake_a(snapshot=_load("snapshot-reported-state.json"))
    with patch(
        "pocketshell.sessions._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"
    ), patch(
        "pocketshell.sessions.subprocess.run",
        return_value=_fake_completed(stdout=_tmuxctl_table()),
    ):
        envelope = sessions_module.daemon_handler_list(
            {"extra_args": [], "as_json": True}
        )
    assert sessions_module._is_schema2_list_envelope(envelope) is True
    payload = json.loads(envelope["stdout"])
    assert payload["managers"] == ["tmux", "aplexer"]
