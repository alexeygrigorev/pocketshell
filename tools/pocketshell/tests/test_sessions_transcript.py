"""`pocketshell sessions transcript NAME [--follow] [--last N]`.

The command emits aplexer's ``UnifiedEvent`` JSONL — one event per line, no
``--json`` flag because JSONL *is* the format. Two backends must produce the
same wire shape:

- an **aplexer** row execs ``a transcript --json`` (aplexer owns its own
  locate/parse), and
- a plain **tmux** row is served from the agent CLI's own conversation log via
  ``agent_log.iter_unified_events``.

The fixtures under ``tests/fixtures/aplexer/`` are REAL captures of
``a transcript --json --last 20 <id>`` taken on the dev box (aplexer 0.1.x)
against a live claude session and a live codex session — not hand-typed
records. They are the schema contract: the tmux fallback is asserted to be a
key-subset of what aplexer really emits, which is what stops the interim
fallback silently drifting away from the real shape.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Optional

import pytest
from click.testing import CliRunner

from pocketshell import agent_log as agent_log_module
from pocketshell import session_enum
from pocketshell import sessions as sessions_module
from pocketshell.sessions import sessions_group

FIXTURES = Path(__file__).parent / "fixtures" / "aplexer"

APLEXER_ID = "a9e4cb9b-2293-4cf4-ac66-7ccc759c5909"


# ----- fixture loading ------------------------------------------------


def _fixture_events(name: str) -> list[dict[str, Any]]:
    text = (FIXTURES / name).read_text(encoding="utf-8")
    return [json.loads(line) for line in text.splitlines() if line.strip()]


def _real_transcript_events() -> list[dict[str, Any]]:
    return _fixture_events("transcript-claude.jsonl") + _fixture_events(
        "transcript-codex.jsonl"
    )


def test_captured_fixtures_are_real_unified_event_jsonl() -> None:
    """Guard the contract source itself: an empty/blank fixture proves nothing.

    Every downstream schema assertion derives its key vocabulary from these
    two files, so a truncated or accidentally-emptied capture would silently
    turn the compat test vacuous (G3/G6). Pin the shape here instead.
    """
    for name in ("transcript-claude.jsonl", "transcript-codex.jsonl"):
        events = _fixture_events(name)
        assert events, f"{name} is empty — re-capture `a transcript --json`"
        for event in events:
            for key in agent_log_module.UNIFIED_EVENT_REQUIRED_KEYS:
                assert key in event, f"{name}: real aplexer event lacks {key!r}"
            assert isinstance(event["sequence"], int)
            assert isinstance(event["metadata"], dict)
    # Both engines really are represented, so the vocabulary spans more than
    # one native log format.
    assert {e["engine"] for e in _real_transcript_events()} == {"claude", "codex"}
    # The captures contain tool traffic, not just prose — i.e. the "subset"
    # claim below is a real subset, not an equality dressed up as one.
    assert {"tool_call", "tool_result"} & {
        e["kind"] for e in _real_transcript_events()
    }


# ----- resolution scaffolding -----------------------------------------


def _tmux_row(name: str, workspace: Optional[str] = None) -> session_enum.LiveSession:
    return session_enum.LiveSession(
        name=name,
        manager=session_enum.MANAGER_TMUX,
        workspace=workspace,
        attach=f"tmux attach -t {name}",
    )


def _aplexer_row(
    name: str, aplexer_id: str = APLEXER_ID
) -> session_enum.LiveSession:
    return session_enum.LiveSession(
        name=name,
        manager=session_enum.MANAGER_APLEXER,
        aplexer_id=aplexer_id,
        workspace="/tmp/aplexer-follow",
        tag=name.partition(":")[2] or name,
        engine="claude",
        attach=f"a attach {aplexer_id}",
    )


@pytest.fixture
def rows(monkeypatch):
    """Inject the resolution row set; returns a setter."""

    def _set(*sessions: session_enum.LiveSession) -> None:
        monkeypatch.setattr(
            sessions_module,
            "_live_sessions_for_resolution",
            lambda: list(sessions),
        )

    _set()
    return _set


@pytest.fixture
def recorded_exec(monkeypatch):
    """Capture the argv `sessions transcript` would exec into."""
    calls: list[list[str]] = []
    monkeypatch.setattr(
        sessions_module, "_exec_argv", lambda argv: calls.append(list(argv))
    )
    return calls


def _run(*args: str):
    return CliRunner().invoke(sessions_group, ["transcript", *args])


# ----- aplexer routing -------------------------------------------------


def test_aplexer_row_execs_a_transcript_json(rows, recorded_exec, monkeypatch):
    monkeypatch.setenv("APLEXER_BIN", "/usr/local/bin/a")
    rows(_aplexer_row("aplexer-follow:zsp"))

    result = _run("aplexer-follow:zsp")

    assert result.exit_code == 0, result.output
    assert recorded_exec == [
        ["/usr/local/bin/a", "transcript", "--json", APLEXER_ID]
    ]


def test_aplexer_row_forwards_last_and_follow(rows, recorded_exec, monkeypatch):
    monkeypatch.setenv("APLEXER_BIN", "/usr/local/bin/a")
    rows(_aplexer_row("aplexer-follow:zsp"))

    result = _run("aplexer-follow:zsp", "--last", "5", "--follow")

    assert result.exit_code == 0, result.output
    assert recorded_exec == [
        [
            "/usr/local/bin/a",
            "transcript",
            "--json",
            "--last",
            "5",
            "--follow",
            APLEXER_ID,
        ]
    ]


def test_aplexer_row_matched_by_id_prefix(rows, recorded_exec, monkeypatch):
    monkeypatch.setenv("APLEXER_BIN", "/usr/local/bin/a")
    rows(_aplexer_row("aplexer-follow:zsp"))

    result = _run(APLEXER_ID[:8])

    assert result.exit_code == 0, result.output
    assert recorded_exec[0][-1] == APLEXER_ID


def test_short_prefix_is_not_an_aplexer_match(rows, recorded_exec, monkeypatch):
    """Below 8 chars a token is treated as a (missing) name, never a prefix."""
    monkeypatch.setenv("APLEXER_BIN", "/usr/local/bin/a")
    rows(_aplexer_row("aplexer-follow:zsp"))

    result = _run(APLEXER_ID[:6])

    assert result.exit_code == 3
    assert recorded_exec == []


def test_ambiguous_prefix_is_refused(rows, recorded_exec, monkeypatch):
    monkeypatch.setenv("APLEXER_BIN", "/usr/local/bin/a")
    rows(
        _aplexer_row("a:one", "abcdef12-1111-4000-8000-000000000001"),
        _aplexer_row("a:two", "abcdef12-2222-4000-8000-000000000002"),
    )

    result = _run("abcdef12")

    assert result.exit_code == 3
    assert "ambiguous" in result.output
    assert recorded_exec == []


def test_tmux_name_wins_over_aplexer_display_name(rows, recorded_exec, tmp_path):
    """Exact tmux name is matched first, so it never execs into aplexer."""
    rows(_tmux_row("shared", workspace=str(tmp_path)), _aplexer_row("shared"))

    result = _run("shared")

    # No conversation recorded under the empty workspace -> 66, but crucially
    # it took the tmux branch rather than aplexer's exec.
    assert recorded_exec == []
    assert result.exit_code == 66


def test_missing_a_binary_for_aplexer_row_exits_127(rows, recorded_exec, monkeypatch):
    monkeypatch.delenv("APLEXER_BIN", raising=False)
    monkeypatch.setenv("PATH", "/nonexistent")
    rows(_aplexer_row("aplexer-follow:zsp"))

    result = _run("aplexer-follow:zsp")

    assert result.exit_code == 127
    assert recorded_exec == []


# ----- unknown name ----------------------------------------------------


def test_unknown_session_name_exits_3(rows, recorded_exec):
    rows(_tmux_row("alpha"), _aplexer_row("beta:tag"))

    result = _run("does-not-exist")

    assert result.exit_code == 3
    assert "no live session matches" in result.output
    assert recorded_exec == []


def test_unknown_session_name_with_no_sessions_at_all_exits_3(rows):
    result = _run("anything")

    assert result.exit_code == 3


# ----- tmux fallback: real claude log ----------------------------------


def _write_claude_log(home: Path, cwd: Path, session_id: str, rows_json) -> Path:
    encoded = str(cwd).replace("/", "-")
    project = home / ".claude" / "projects" / encoded
    project.mkdir(parents=True, exist_ok=True)
    path = project / f"{session_id}.jsonl"
    path.write_text(
        "".join(json.dumps(row) + "\n" for row in rows_json), encoding="utf-8"
    )
    return path


def _claude_row(seq: int, role: str, text: str, cwd: str = "/workspace") -> dict[str, Any]:
    """A native Claude Code JSONL row, shaped like the real logs.

    Field names copied from the ``raw`` payloads inside the captured
    ``transcript-claude.jsonl`` fixture, so the fallback is parsing the same
    thing aplexer parses. ``cwd`` matters: it is what
    ``resume.discover_claude`` binds the conversation to a tmux workspace by.
    """
    return {
        "type": role,
        "uuid": f"uuid-{seq}",
        "sessionId": "sess-under-test",
        "cwd": cwd,
        "timestamp": f"2026-08-26T17:0{seq}:00.000Z",
        "message": {"role": role, "content": [{"type": "text", "text": text}]},
    }


@pytest.fixture
def tmux_claude_session(tmp_path, monkeypatch, rows):
    """A tmux row whose workspace holds a real-shaped claude conversation."""
    home = Path(tmp_path / "home")
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    session_id = "11111111-2222-3333-4444-555555555555"
    log = _write_claude_log(
        home,
        workspace,
        session_id,
        [
            _claude_row(
                n,
                "user" if n % 2 == 0 else "assistant",
                f"turn {n}",
                cwd=str(workspace),
            )
            for n in range(6)
        ],
    )
    rows(_tmux_row("work", workspace=str(workspace)))
    return {"log": log, "session_id": session_id, "workspace": workspace}


def _events_from_output(output: str) -> list[dict[str, Any]]:
    return [json.loads(line) for line in output.splitlines() if line.strip()]


def test_tmux_fallback_emits_parseable_jsonl(tmux_claude_session):
    result = _run("work")

    assert result.exit_code == 0, result.output
    events = _events_from_output(result.output)
    assert len(events) == 6
    assert [e["content"] for e in events] == [f"turn {n}" for n in range(6)]
    assert [e["sequence"] for e in events] == list(range(6))
    assert {e["engine"] for e in events} == {"claude"}
    assert [e["role"] for e in events] == [
        "user",
        "assistant",
        "user",
        "assistant",
        "user",
        "assistant",
    ]
    assert [e["timestamp"] for e in events] == [
        f"2026-08-26T17:0{n}:00.000Z" for n in range(6)
    ]


def test_tmux_fallback_keys_are_a_subset_of_real_aplexer_keys(tmux_claude_session):
    """THE schema-compat assertion.

    Every key the fallback emits must exist in genuinely-captured
    ``a transcript --json`` output, and every key aplexer emits on *every*
    real event must be present on the fallback's events. Both directions
    matter: the first stops the fallback inventing fields the client has never
    seen, the second stops it degrading into a stub that would trivially
    satisfy a one-way subset check.
    """
    real = _real_transcript_events()
    real_key_union = set().union(*(set(e) for e in real))
    real_key_always = set(real[0]).intersection(*(set(e) for e in real))
    real_kinds = {e["kind"] for e in real}
    real_metadata_keys = set().union(*(set(e["metadata"]) for e in real))

    result = _run("work")
    assert result.exit_code == 0, result.output
    events = _events_from_output(result.output)
    assert events, "no events emitted — the subset check would be vacuous"

    for event in events:
        keys = set(event)
        assert keys <= real_key_union, (
            f"fallback emits keys aplexer never does: {sorted(keys - real_key_union)}"
        )
        assert real_key_always <= keys, (
            "fallback omits keys every real aplexer event carries: "
            f"{sorted(real_key_always - keys)}"
        )
        assert event["kind"] in real_kinds
        assert set(event["metadata"]) <= real_metadata_keys, (
            "fallback metadata uses keys aplexer never does: "
            f"{sorted(set(event['metadata']) - real_metadata_keys)}"
        )
        # Types must match the real stream too, not just the key names.
        assert isinstance(event["sequence"], int)
        assert isinstance(event["timestamp"], str)
        assert isinstance(event["raw"], dict)
        assert isinstance(event["metadata"], dict)


def test_tmux_fallback_metadata_identifies_the_source(tmux_claude_session):
    result = _run("work")
    events = _events_from_output(result.output)

    assert events[0]["metadata"] == {
        "session_id": tmux_claude_session["session_id"],
        "workspace": str(tmux_claude_session["workspace"]),
        "tag": "work",
    }


def test_tmux_fallback_raw_carries_the_native_row(tmux_claude_session):
    """``raw`` is the engine's own payload, exactly as aplexer defines it."""
    result = _run("work")
    events = _events_from_output(result.output)

    assert events[0]["raw"]["uuid"] == "uuid-0"
    assert events[0]["raw"]["message"]["content"][0]["text"] == "turn 0"


# ----- --last windowing ------------------------------------------------


def test_last_n_windows_the_tail(tmux_claude_session):
    result = _run("work", "--last", "2")

    assert result.exit_code == 0, result.output
    events = _events_from_output(result.output)
    assert [e["content"] for e in events] == ["turn 4", "turn 5"]


def test_last_n_keeps_absolute_sequence_numbers(tmux_claude_session):
    """``a transcript --last 3`` preserves whole-file sequence numbers.

    Verified live against aplexer: ``--last 3`` on a 10-event session emits
    sequences 7, 8, 9 — not a renumbered 0, 1, 2. The fallback must match, or
    a client using the last-rendered sequence as an ``--after`` cursor would
    silently re-request events it already has.
    """
    result = _run("work", "--last", "3")

    events = _events_from_output(result.output)
    assert [e["sequence"] for e in events] == [3, 4, 5]


def test_last_larger_than_the_log_emits_everything(tmux_claude_session):
    result = _run("work", "--last", "500")

    assert len(_events_from_output(result.output)) == 6


def test_last_zero_emits_everything(tmux_claude_session):
    """``--last 0`` means "no window", matching ``agent-log --tail 0``."""
    result = _run("work", "--last", "0")

    assert len(_events_from_output(result.output)) == 6


# ----- tmux fallback: no conversation ----------------------------------


def test_tmux_row_without_a_conversation_exits_66(rows, tmp_path):
    empty = tmp_path / "empty"
    empty.mkdir()
    rows(_tmux_row("bare", workspace=str(empty)))

    result = _run("bare")

    assert result.exit_code == 66
    assert "no agent conversation log" in result.output


def test_tmux_row_without_a_workspace_exits_66(rows):
    """An unenriched tmux row (its server never answered) cannot be located."""
    rows(_tmux_row("headless", workspace=None))

    result = _run("headless")

    assert result.exit_code == 66


def test_discovered_conversation_with_no_readable_log_exits_66(
    rows, monkeypatch, tmp_path
):
    """Discovery and locate are separate steps and CAN disagree.

    ``resume.discover_opencode`` reads OpenCode's SQLite store while
    ``agent_log._resolve_opencode_path`` wants a ``<id>.jsonl`` beside it — a
    real host can have the former without the latter. The generator raises
    lazily, on first consumption, so this also pins that the CLI catches it
    around the emit loop rather than around the call that builds it.
    """
    from pocketshell import resume as resume_module

    monkeypatch.setattr(
        sessions_module,
        "_tmux_row_conversation",
        lambda row: resume_module.ResumableSession(
            engine="opencode",
            session_id="ghost",
            cwd=str(tmp_path),
            last_activity=0.0,
            label="x",
        ),
    )
    rows(_tmux_row("oc", workspace=str(tmp_path)))

    result = _run("oc")

    assert result.exit_code == 66
    assert "no opencode session log found" in result.output


# ----- codex fallback --------------------------------------------------


def _codex_row(seq: int, role: str, text: str) -> dict[str, Any]:
    """A native Codex rollout row, shaped like the captured fixture's ``raw``."""
    return {
        "ordinal": seq,
        "timestamp": f"2026-08-26T17:3{seq}:00.000Z",
        "type": "response_item",
        "payload": {
            "type": "message",
            "role": role,
            "content": [
                {"type": "input_text" if role == "user" else "output_text", "text": text}
            ],
        },
    }


def test_codex_tmux_fallback_matches_the_codex_fixture_shape(
    tmp_path, monkeypatch, rows
):
    home = Path(tmp_path / "home")
    workspace = tmp_path / "codexspace"
    workspace.mkdir()
    session_dir = home / ".codex" / "sessions" / "2026" / "08" / "26"
    session_dir.mkdir(parents=True)
    session_id = "rollout-2026-08-26T19-31-42-01a03f20"
    (session_dir / f"{session_id}.jsonl").write_text(
        "".join(
            json.dumps(row) + "\n"
            for row in (
                {
                    "type": "session_meta",
                    "timestamp": "2026-08-26T17:30:00.000Z",
                    "payload": {"cwd": str(workspace), "id": session_id},
                },
                _codex_row(1, "user", "count the files"),
                _codex_row(2, "assistant", "there are three"),
            )
        ),
        encoding="utf-8",
    )
    rows(_tmux_row("codexwork", workspace=str(workspace)))

    result = _run("codexwork")

    assert result.exit_code == 0, result.output
    events = _events_from_output(result.output)
    assert [e["content"] for e in events] == ["count the files", "there are three"]
    assert {e["engine"] for e in events} == {"codex"}

    real = _fixture_events("transcript-codex.jsonl")
    real_key_union = set().union(*(set(e) for e in real))
    for event in events:
        assert set(event) <= real_key_union


# ----- --follow --------------------------------------------------------


def test_follow_streams_rows_appended_after_the_initial_page(
    tmp_path, tmux_claude_session
):
    """The follow loop must pick up appended rows and keep numbering monotonic."""
    log = tmux_claude_session["log"]
    appended = {"done": False}

    def fake_sleep(_interval: float) -> None:
        if not appended["done"]:
            with log.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(_claude_row(6, "assistant", "turn 6")) + "\n")
            appended["done"] = True

    events = list(
        agent_log_module.iter_unified_events(
            engine="claude",
            session=tmux_claude_session["session_id"],
            cwd=str(tmux_claude_session["workspace"]),
            metadata={"tag": "work"},
            follow=True,
            max_polls=2,
            sleep=fake_sleep,
        )
    )

    assert [e["content"] for e in events] == [f"turn {n}" for n in range(7)]
    assert [e["sequence"] for e in events] == list(range(7))


def test_follow_holds_back_a_partially_written_row(tmp_path, tmux_claude_session):
    """A half-flushed JSON line is buffered, not discarded as malformed."""
    log = tmux_claude_session["log"]
    complete = json.dumps(_claude_row(6, "assistant", "turn 6"))
    state = {"step": 0}

    def fake_sleep(_interval: float) -> None:
        state["step"] += 1
        with log.open("a", encoding="utf-8") as handle:
            if state["step"] == 1:
                handle.write(complete[:20])  # torn mid-object, no newline
            elif state["step"] == 2:
                handle.write(complete[20:] + "\n")

    events = list(
        agent_log_module.iter_unified_events(
            engine="claude",
            session=tmux_claude_session["session_id"],
            cwd=str(tmux_claude_session["workspace"]),
            follow=True,
            max_polls=3,
            sleep=fake_sleep,
        )
    )

    assert [e["content"] for e in events] == [f"turn {n}" for n in range(7)]


def test_follow_initial_page_respects_last(tmux_claude_session):
    events = list(
        agent_log_module.iter_unified_events(
            engine="claude",
            session=tmux_claude_session["session_id"],
            cwd=str(tmux_claude_session["workspace"]),
            last=2,
            follow=True,
            max_polls=1,
            sleep=lambda _i: None,
        )
    )

    assert [e["content"] for e in events] == ["turn 4", "turn 5"]


# ----- argv builder (pure) --------------------------------------------


@pytest.mark.parametrize(
    "kwargs,expected",
    [
        ({}, ["a", "transcript", "--json", "ID"]),
        ({"last": 5}, ["a", "transcript", "--json", "--last", "5", "ID"]),
        ({"follow": True}, ["a", "transcript", "--json", "--follow", "ID"]),
        (
            {"last": 0, "follow": True},
            ["a", "transcript", "--json", "--last", "0", "--follow", "ID"],
        ),
    ],
)
def test_aplexer_transcript_argv(kwargs, expected):
    assert sessions_module.aplexer_transcript_argv("a", "ID", **kwargs) == expected


# ----- resolution helper (pure) ---------------------------------------


def test_resolve_prefers_exact_tmux_then_aplexer_name_then_prefix():
    tmux = _tmux_row("alpha")
    ap = _aplexer_row("beta:tag", "cafebabe-0000-4000-8000-000000000000")
    pool = [tmux, ap]

    assert sessions_module.resolve_live_session(pool, "alpha")[0] is tmux
    assert sessions_module.resolve_live_session(pool, "beta:tag")[0] is ap
    assert sessions_module.resolve_live_session(pool, "cafebabe")[0] is ap
    assert sessions_module.resolve_live_session(pool, "cafebab")[0] is None
    assert sessions_module.resolve_live_session(pool, "nope")[0] is None
