"""Unit tests for `pocketshell.cards` — the generic per-session typed-card store
and the `pocketshell push checklist|get|status|check` CLI verbs (epic #859, P1).

Coverage (one test per acceptance criterion):

- `push checklist` from stdin markdown creates the card; `push get --json`
  returns it; `push check` toggles an item; `push status` reflects it.
- Session is auto-detected from `$TMUX` (via `tmux display-message -p '#S'`).
- State persists across separate CLI invocations (a real process restart is
  simulated by independent CliRunner.invoke calls reading the same store).
- State is per-session (one session's ticks don't leak into another).
- Generic registry proven: a STUB second card type round-trips through
  build → upsert → read → interact → status without any new transport.
- Empty-feed state (no cards) is valid.
"""

from __future__ import annotations

import json
import multiprocessing
import os
import stat
import time
from pathlib import Path
from typing import Any

import pytest
from click.testing import CliRunner

from pocketshell import cards as cards_mod
from pocketshell.cli import cli


def _paths(tmp_path: Path) -> cards_mod.CardPaths:
    return cards_mod.CardPaths(cards_dir=tmp_path / "pocketshell" / "cards")


def _env(tmp_path: Path, session: str = "demo") -> dict[str, str]:
    """CLI env that points the store at tmp_path and forces session detection.

    `POCKETSHELL_CARDS_DIR` relocates the store; `TMUX` + the monkeypatched
    `tmux display-message` give a deterministic session without a real tmux.
    """
    return {
        "POCKETSHELL_CARDS_DIR": str(tmp_path / "pocketshell" / "cards"),
        "TMUX": "/tmp/fake-tmux,1,0",
    }


# ----- resolve_paths -------------------------------------------------


def test_resolve_paths_env_override(tmp_path: Path) -> None:
    paths = cards_mod.resolve_paths(env={cards_mod.CARDS_DIR_ENV: str(tmp_path / "c")})
    assert paths.cards_dir == tmp_path / "c"


def test_resolve_paths_default_under_home() -> None:
    paths = cards_mod.resolve_paths(home=Path("/home/u"), env={})
    assert paths.cards_dir == Path("/home/u/.pocketshell/cards")


def test_session_file_identity_is_injective(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    aliases = ("a/b", "a_b", "...", "session", "привет")
    names = [paths.session_file(session).name for session in aliases]
    assert len(set(names)) == len(aliases)
    assert all("/" not in name for name in names)


def _upsert_in_process(
    cards_dir: str,
    session: str,
    card_id: str,
    ready: multiprocessing.synchronize.Event,
    start: multiprocessing.synchronize.Event,
    reads_complete: multiprocessing.synchronize.Barrier | None = None,
) -> None:
    paths = cards_mod.CardPaths(cards_dir=Path(cards_dir))
    card = cards_mod.build_card(
        card_type="note",
        card_id=card_id,
        title=None,
        build_kwargs={"text": card_id},
    )
    ready.set()
    start.wait(timeout=5)
    if reads_complete is not None:
        original_read = cards_mod.read_cards

        def read_then_rendezvous(*args: Any, **kwargs: Any) -> list[dict[str, Any]]:
            cards = original_read(*args, **kwargs)
            reads_complete.wait(timeout=5)
            return cards

        cards_mod.read_cards = read_then_rendezvous
    cards_mod.upsert_card(session, card, paths=paths)


def _interact_in_process(
    cards_dir: str,
    item_id: str,
    ready: multiprocessing.synchronize.Event,
    start: multiprocessing.synchronize.Event,
) -> None:
    paths = cards_mod.CardPaths(cards_dir=Path(cards_dir))
    ready.set()
    start.wait(timeout=5)
    cards_mod.apply_interaction(
        "shared", "checklist", {"item": item_id, "done": True}, paths=paths
    )


def test_concurrent_upserts_preserve_both_cards(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    ctx = multiprocessing.get_context("fork")
    start = ctx.Event()
    reads_complete = ctx.Barrier(2)
    ready = [ctx.Event(), ctx.Event()]
    processes = [
        ctx.Process(
            target=_upsert_in_process,
            args=(
                str(paths.cards_dir),
                "shared",
                card_id,
                ready[index],
                start,
                reads_complete,
            ),
        )
        for index, card_id in enumerate(("one", "two"))
    ]
    for process in processes:
        process.start()
    assert all(event.wait(timeout=5) for event in ready)
    start.set()
    for process in processes:
        process.join(timeout=5)
        assert process.exitcode == 0
    assert {card["id"] for card in cards_mod.read_cards("shared", paths=paths)} == {
        "one", "two",
    }


def test_private_write_orders_durability_barriers_around_replace(
    tmp_path: Path, monkeypatch: Any
) -> None:
    target = tmp_path / "cards" / "session.yaml"
    events: list[str] = []
    real_fsync = cards_mod.os.fsync
    real_replace = cards_mod.os.replace

    def record_fsync(fd: int) -> None:
        kind = "directory" if stat.S_ISDIR(os.fstat(fd).st_mode) else "file"
        events.append(f"fsync:{kind}")
        real_fsync(fd)

    def record_replace(source: Path, destination: Path) -> None:
        events.append("replace")
        real_replace(source, destination)

    monkeypatch.setattr(cards_mod.os, "fsync", record_fsync)
    monkeypatch.setattr(cards_mod.os, "replace", record_replace)

    cards_mod._write_private(target, "durable\n")

    assert events == ["fsync:file", "replace", "fsync:directory"]
    assert target.read_text(encoding="utf-8") == "durable\n"


def test_private_write_fails_before_replace_when_file_fsync_fails(
    tmp_path: Path, monkeypatch: Any
) -> None:
    target = tmp_path / "cards" / "session.yaml"
    target.parent.mkdir(parents=True)
    target.write_text("previous\n", encoding="utf-8")
    replace_called = False

    def fail_fsync(_fd: int) -> None:
        raise OSError(5, "simulated storage failure")

    def record_replace(_source: Path, _destination: Path) -> None:
        nonlocal replace_called
        replace_called = True

    monkeypatch.setattr(cards_mod.os, "fsync", fail_fsync)
    monkeypatch.setattr(cards_mod.os, "replace", record_replace)

    with pytest.raises(OSError, match="simulated storage failure"):
        cards_mod._write_private(target, "new\n")

    assert replace_called is False
    assert target.read_text(encoding="utf-8") == "previous\n"
    assert list(target.parent.glob(f".{target.name}.*")) == []


def test_card_mutation_obeys_cross_process_lock(tmp_path: Path) -> None:
    import fcntl

    paths = _paths(tmp_path)
    path = paths.session_file("shared")
    path.parent.mkdir(parents=True)
    lock_path = path.with_suffix(path.suffix + ".lock")
    with lock_path.open("a+") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        ctx = multiprocessing.get_context("fork")
        ready, start = ctx.Event(), ctx.Event()
        process = ctx.Process(
            target=_upsert_in_process,
            args=(str(paths.cards_dir), "shared", "blocked", ready, start),
        )
        process.start()
        assert ready.wait(timeout=5)
        start.set()
        time.sleep(0.15)
        assert process.is_alive(), "mutation bypassed the session lock"
        fcntl.flock(lock, fcntl.LOCK_UN)
    process.join(timeout=5)
    assert process.exitcode == 0


def test_concurrent_interactions_preserve_both_updates(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    items = cards_mod.parse_checklist_markdown("- [ ] first\n- [ ] second\n")
    card = cards_mod.build_card(
        card_type="checklist", card_id="checklist", title=None,
        build_kwargs={"items": items},
    )
    cards_mod.upsert_card("shared", card, paths=paths)
    ctx = multiprocessing.get_context("fork")
    start = ctx.Event()
    ready = [ctx.Event(), ctx.Event()]
    processes = [
        ctx.Process(
            target=_interact_in_process,
            args=(str(paths.cards_dir), item["id"], ready[index], start),
        )
        for index, item in enumerate(items)
    ]
    for process in processes:
        process.start()
    assert all(event.wait(timeout=5) for event in ready)
    start.set()
    for process in processes:
        process.join(timeout=5)
        assert process.exitcode == 0
    [stored] = cards_mod.read_cards("shared", paths=paths)
    assert set(stored["state"]["checked"]) == {item["id"] for item in items}


def test_read_rejects_document_with_wrong_session_identity(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    path = paths.session_file("expected")
    path.parent.mkdir(parents=True)
    path.write_text("schema: 1\nsession: foreign\ncards:\n  - id: leaked\n")
    assert cards_mod.read_cards("expected", paths=paths) == []


# ----- session auto-detection ($TMUX) --------------------------------


def test_detect_session_explicit_wins() -> None:
    assert cards_mod.detect_session(explicit="given", env={}) == "given"


def test_detect_session_none_outside_tmux() -> None:
    assert cards_mod.detect_session(env={}) is None


def test_detect_session_uses_tmux_display_message(monkeypatch: Any) -> None:
    monkeypatch.setattr(cards_mod, "_tmux_current_session", lambda: "live-sess")
    assert cards_mod.detect_session(env={"TMUX": "x"}) == "live-sess"


# ----- checklist markdown parsing ------------------------------------


def test_parse_checklist_markdown_unchecked_and_checked() -> None:
    md = "- [ ] first\n- [x] second\nignored line\n* [ ] third\n"
    items = cards_mod.parse_checklist_markdown(md)
    assert [it["text"] for it in items] == ["first", "second", "third"]
    assert items[1]["preset_done"] is True
    assert items[0]["preset_done"] is False
    # ids are unique
    assert len({it["id"] for it in items}) == 3


# ----- store round-trip (direct API) ---------------------------------


def test_build_upsert_read_checklist(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    items = cards_mod.parse_checklist_markdown("- [ ] deploy\n- [ ] verify\n")
    card = cards_mod.build_card(
        card_type="checklist",
        card_id="checklist",
        title="Release steps",
        build_kwargs={"items": items},
    )
    cards_mod.upsert_card("sessA", card, paths=paths)
    got = cards_mod.read_cards("sessA", paths=paths)
    assert len(got) == 1
    assert got[0]["title"] == "Release steps"
    assert [it["text"] for it in got[0]["body"]["items"]] == ["deploy", "verify"]
    assert got[0]["state"]["checked"] == []


def test_apply_interaction_ticks_and_unticks(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    items = cards_mod.parse_checklist_markdown("- [ ] one\n- [ ] two\n")
    card = cards_mod.build_card(
        card_type="checklist", card_id="checklist", title=None,
        build_kwargs={"items": items},
    )
    cards_mod.upsert_card("s", card, paths=paths)
    item_id = items[0]["id"]
    cards_mod.apply_interaction("s", "checklist", {"item": item_id, "done": True}, paths=paths)
    assert cards_mod.read_cards("s", paths=paths)[0]["state"]["checked"] == [item_id]
    cards_mod.apply_interaction("s", "checklist", {"item": item_id, "done": False}, paths=paths)
    assert cards_mod.read_cards("s", paths=paths)[0]["state"]["checked"] == []


def test_apply_interaction_unknown_item_raises(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    items = cards_mod.parse_checklist_markdown("- [ ] one\n")
    card = cards_mod.build_card(
        card_type="checklist", card_id="checklist", title=None,
        build_kwargs={"items": items},
    )
    cards_mod.upsert_card("s", card, paths=paths)
    try:
        cards_mod.apply_interaction("s", "checklist", {"item": "nope"}, paths=paths)
    except ValueError:
        return
    raise AssertionError("expected ValueError for unknown item id")


def test_checklist_presets_honoured(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    items = cards_mod.parse_checklist_markdown("- [x] already\n- [ ] todo\n")
    preset = [it["id"] for it in items if it["preset_done"]]
    card = cards_mod.build_card(
        card_type="checklist", card_id="checklist", title=None,
        build_kwargs={"items": items}, preset_checked=preset,
    )
    cards_mod.upsert_card("s", card, paths=paths)
    assert cards_mod.read_cards("s", paths=paths)[0]["state"]["checked"] == preset


def test_upsert_replaces_card_with_same_id(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    first = cards_mod.build_card(
        card_type="checklist", card_id="checklist", title="v1",
        build_kwargs={"items": cards_mod.parse_checklist_markdown("- [ ] a\n")},
    )
    cards_mod.upsert_card("s", first, paths=paths)
    second = cards_mod.build_card(
        card_type="checklist", card_id="checklist", title="v2",
        build_kwargs={"items": cards_mod.parse_checklist_markdown("- [ ] b\n- [ ] c\n")},
    )
    cards_mod.upsert_card("s", second, paths=paths)
    got = cards_mod.read_cards("s", paths=paths)
    assert len(got) == 1
    assert got[0]["title"] == "v2"
    assert [it["text"] for it in got[0]["body"]["items"]] == ["b", "c"]


def test_read_cards_empty_when_no_file(tmp_path: Path) -> None:
    assert cards_mod.read_cards("never", paths=_paths(tmp_path)) == []


# ----- generic registry: a stub SECOND card type round-trips ----------


def test_stub_second_card_type_round_trips(tmp_path: Path) -> None:
    """Adding a new card type = register one CardType — no new transport.

    A stub `note`-like type ("mark as read") proves the store, upsert, read,
    interaction, and status summary are all type-agnostic and dispatch through
    the registry. Restored after the test so the global REGISTRY stays clean.
    """
    paths = _paths(tmp_path)

    def build_body(*, text: str, **_: Any) -> dict[str, Any]:
        return {"text": text}

    def initial_state(_body: dict[str, Any]) -> dict[str, Any]:
        return {"read": False}

    def apply(_body: dict[str, Any], state: dict[str, Any], interaction: Any) -> dict[str, Any]:
        return {"read": bool(interaction.get("read", True)), "read_at": "now"}

    def summarise(_body: dict[str, Any], state: dict[str, Any]) -> str:
        return "note: read" if state.get("read") else "note: unread"

    stub = cards_mod.CardType(
        name="stubnote",
        build_body=build_body,
        initial_state=initial_state,
        apply_interaction=apply,
        summarise=summarise,
    )
    original = dict(cards_mod.REGISTRY)
    try:
        cards_mod.register_card_type(stub)
        card = cards_mod.build_card(
            card_type="stubnote", card_id="n1", title="Heads up",
            build_kwargs={"text": "deploy finished"},
        )
        cards_mod.upsert_card("s", card, paths=paths)
        got = cards_mod.read_cards("s", paths=paths)
        assert got[0]["type"] == "stubnote"
        assert got[0]["body"]["text"] == "deploy finished"
        assert got[0]["state"] == {"read": False}
        # Interaction dispatches to the stub type's handler.
        updated = cards_mod.apply_interaction("s", "n1", {"read": True}, paths=paths)
        assert updated["state"]["read"] is True
        # Summary dispatches through the registry too.
        handler = cards_mod.get_card_type("stubnote")
        assert handler is not None
        assert handler.summarise(card["body"], updated["state"]) == "note: read"
    finally:
        cards_mod.REGISTRY.clear()
        cards_mod.REGISTRY.update(original)


def test_build_card_unknown_type_raises() -> None:
    try:
        cards_mod.build_card(card_type="nope", card_id="x", title=None, build_kwargs={})
    except ValueError:
        return
    raise AssertionError("expected ValueError for unknown card type")


# ----- CLI verbs (end-to-end through the `push` group) -----------------


def test_cli_checklist_set_get_check_status_flow(tmp_path: Path) -> None:
    """AC: push checklist (stdin) → get --json → check → status reflects it."""
    runner = CliRunner()
    env = _env(tmp_path)

    # push checklist from stdin markdown
    res = runner.invoke(
        cli,
        ["push", "checklist", "--title", "Deploy", "--session", "demo"],
        input="- [ ] build\n- [ ] ship\n",
        env=env,
    )
    assert res.exit_code == 0, res.output
    assert "2 items" in res.output

    # push get --json returns the card
    res = runner.invoke(cli, ["push", "get", "--json", "--session", "demo"], env=env)
    assert res.exit_code == 0, res.output
    payload = json.loads(res.output)
    assert payload["session"] == "demo"
    assert len(payload["cards"]) == 1
    card = payload["cards"][0]
    assert card["title"] == "Deploy"
    item_id = card["body"]["items"][0]["id"]
    assert card["state"]["checked"] == []

    # push check ticks an item
    res = runner.invoke(
        cli,
        ["push", "check", "--id", "checklist", "--item", item_id, "--session", "demo"],
        env=env,
    )
    assert res.exit_code == 0, res.output

    # push status reflects the tick (JSON + human)
    res = runner.invoke(
        cli, ["push", "status", "--json", "--session", "demo"], env=env
    )
    assert res.exit_code == 0, res.output
    status = json.loads(res.output)
    assert status["status"][0]["state"]["checked"] == [item_id]

    res = runner.invoke(cli, ["push", "status", "--session", "demo"], env=env)
    assert res.exit_code == 0, res.output
    assert "1/2 checked" in res.output


def test_cli_checklist_items_via_flags(tmp_path: Path) -> None:
    runner = CliRunner()
    env = _env(tmp_path)
    res = runner.invoke(
        cli,
        ["push", "checklist", "--item", "alpha", "--item", "beta", "--session", "demo"],
        env=env,
    )
    assert res.exit_code == 0, res.output
    res = runner.invoke(cli, ["push", "get", "--json", "--session", "demo"], env=env)
    cards = json.loads(res.output)["cards"]
    assert [it["text"] for it in cards[0]["body"]["items"]] == ["alpha", "beta"]


def test_cli_state_persists_across_invocations(tmp_path: Path) -> None:
    """AC: state survives a process restart (independent CLI invocations)."""
    runner = CliRunner()
    env = _env(tmp_path)
    runner.invoke(
        cli, ["push", "checklist", "--item", "x", "--session", "demo"],
        env=env,
    )
    cards = json.loads(
        runner.invoke(cli, ["push", "get", "--json", "--session", "demo"], env=env).output
    )["cards"]
    item_id = cards[0]["body"]["items"][0]["id"]
    runner.invoke(
        cli, ["push", "check", "--id", "checklist", "--item", item_id, "--session", "demo"],
        env=env,
    )
    # Brand-new runner = brand-new process; reads the SAME on-disk store.
    fresh = CliRunner()
    out = fresh.invoke(cli, ["push", "status", "--json", "--session", "demo"], env=env).output
    assert json.loads(out)["status"][0]["state"]["checked"] == [item_id]


def test_cli_state_is_per_session(tmp_path: Path) -> None:
    """AC: state is per-session — a tick in A doesn't appear in B."""
    runner = CliRunner()
    env = _env(tmp_path)
    runner.invoke(cli, ["push", "checklist", "--item", "a-item", "--session", "A"], env=env)
    runner.invoke(cli, ["push", "checklist", "--item", "b-item", "--session", "B"], env=env)
    a_cards = json.loads(
        runner.invoke(cli, ["push", "get", "--json", "--session", "A"], env=env).output
    )["cards"]
    a_item = a_cards[0]["body"]["items"][0]["id"]
    runner.invoke(
        cli, ["push", "check", "--id", "checklist", "--item", a_item, "--session", "A"], env=env
    )
    b_status = json.loads(
        runner.invoke(cli, ["push", "status", "--json", "--session", "B"], env=env).output
    )["status"]
    assert b_status[0]["state"]["checked"] == []
    a_status = json.loads(
        runner.invoke(cli, ["push", "status", "--json", "--session", "A"], env=env).output
    )["status"]
    assert a_status[0]["state"]["checked"] == [a_item]


def test_cli_get_empty_feed(tmp_path: Path) -> None:
    """AC: empty-feed state is valid (no cards)."""
    runner = CliRunner()
    env = _env(tmp_path)
    res = runner.invoke(cli, ["push", "get", "--json", "--session", "empty"], env=env)
    assert res.exit_code == 0, res.output
    assert json.loads(res.output)["cards"] == []
    res = runner.invoke(cli, ["push", "get", "--session", "empty"], env=env)
    assert "no cards" in res.output


def test_cli_session_autodetected_from_tmux(tmp_path: Path, monkeypatch: Any) -> None:
    """AC: session auto-detected from $TMUX (no --session passed)."""
    monkeypatch.setattr(cards_mod, "_tmux_current_session", lambda: "auto-sess")
    runner = CliRunner()
    env = {"POCKETSHELL_CARDS_DIR": str(tmp_path / "pocketshell" / "cards"), "TMUX": "x"}
    res = runner.invoke(cli, ["push", "checklist", "--item", "z"], input="", env=env)
    assert res.exit_code == 0, res.output
    assert "auto-sess" in res.output
    # The card landed under the auto-detected session.
    res = runner.invoke(cli, ["push", "get", "--json", "--session", "auto-sess"], env=env)
    assert len(json.loads(res.output)["cards"]) == 1


def test_cli_no_session_errors(tmp_path: Path) -> None:
    runner = CliRunner()
    # Empty TMUX explicitly clears any leaked $TMUX from the test runner's own
    # tmux session; no --session means detection must fail.
    env = {"POCKETSHELL_CARDS_DIR": str(tmp_path / "c"), "TMUX": ""}
    res = runner.invoke(cli, ["push", "checklist", "--item", "z"], env=env)
    assert res.exit_code != 0
    assert "tmux session" in res.output


def test_cli_check_unknown_item_errors(tmp_path: Path) -> None:
    runner = CliRunner()
    env = _env(tmp_path)
    runner.invoke(cli, ["push", "checklist", "--item", "x", "--session", "demo"], env=env)
    res = runner.invoke(
        cli, ["push", "check", "--id", "checklist", "--item", "ghost", "--session", "demo"],
        env=env,
    )
    assert res.exit_code != 0
    assert "unknown item" in res.output


# ----- note card type (#859 Slice B — registry is genuinely generic) ----


def test_note_card_type_is_registered() -> None:
    """AC: the host registers a REAL `note` CardType (not just the stub test)."""
    handler = cards_mod.get_card_type("note")
    assert handler is not None
    assert handler.name == "note"


def test_build_upsert_read_note(tmp_path: Path) -> None:
    """AC: note `body={text}`, `state={read:false, read_at:None}` round-trips."""
    paths = _paths(tmp_path)
    card = cards_mod.build_card(
        card_type="note",
        card_id="note",
        title="Heads up",
        build_kwargs={"text": "deploy finished"},
    )
    cards_mod.upsert_card("demo", card, paths=paths)
    got = cards_mod.read_cards("demo", paths=paths)
    assert len(got) == 1
    assert got[0]["type"] == "note"
    assert got[0]["title"] == "Heads up"
    assert got[0]["body"] == {"text": "deploy finished"}
    assert got[0]["state"] == {"read": False, "read_at": None}


def test_note_apply_interaction_marks_read_and_unread(tmp_path: Path) -> None:
    """AC: mark-as-read interaction sets read + read_at; unread clears read_at."""
    paths = _paths(tmp_path)
    card = cards_mod.build_card(
        card_type="note", card_id="note", title=None,
        build_kwargs={"text": "hi"},
    )
    cards_mod.upsert_card("demo", card, paths=paths)

    read = cards_mod.apply_interaction("demo", "note", {"read": True}, paths=paths)
    assert read["state"]["read"] is True
    assert read["state"]["read_at"]  # a timestamp, not None

    unread = cards_mod.apply_interaction("demo", "note", {"read": False}, paths=paths)
    assert unread["state"]["read"] is False
    assert unread["state"]["read_at"] is None


def test_note_and_checklist_coexist_in_one_session(tmp_path: Path) -> None:
    """The store is type-agnostic: a note + a checklist live side by side."""
    paths = _paths(tmp_path)
    checklist = cards_mod.build_card(
        card_type="checklist", card_id="checklist", title="Deploy",
        build_kwargs={"items": [{"id": "build-0", "text": "build"}]},
    )
    note = cards_mod.build_card(
        card_type="note", card_id="note", title=None,
        build_kwargs={"text": "fyi"},
    )
    cards_mod.upsert_card("demo", checklist, paths=paths)
    cards_mod.upsert_card("demo", note, paths=paths)
    got = cards_mod.read_cards("demo", paths=paths)
    assert {c["type"] for c in got} == {"checklist", "note"}


def test_cli_note_set_get_read_status_flow(tmp_path: Path) -> None:
    """AC: push note (stdin) → get --json → read → status reflects read."""
    runner = CliRunner()
    env = _env(tmp_path)

    res = runner.invoke(
        cli,
        ["push", "note", "--title", "Heads up", "--session", "demo"],
        input="deploy finished\n",
        env=env,
    )
    assert res.exit_code == 0, res.output
    assert "note 'note'" in res.output

    res = runner.invoke(cli, ["push", "get", "--json", "--session", "demo"], env=env)
    assert res.exit_code == 0, res.output
    payload = json.loads(res.output)
    card = payload["cards"][0]
    assert card["type"] == "note"
    assert card["title"] == "Heads up"
    assert card["body"]["text"] == "deploy finished"
    assert card["state"]["read"] is False

    # Human marks it read (this is what the app's "mark read" calls).
    res = runner.invoke(
        cli, ["push", "read", "--id", "note", "--session", "demo"], env=env
    )
    assert res.exit_code == 0, res.output
    assert "note: read" in res.output

    # Agent reads the acknowledgement via status.
    res = runner.invoke(cli, ["push", "status", "--json", "--session", "demo"], env=env)
    assert res.exit_code == 0, res.output
    status = json.loads(res.output)
    assert status["status"][0]["state"]["read"] is True

    res = runner.invoke(cli, ["push", "status", "--session", "demo"], env=env)
    assert res.exit_code == 0, res.output
    assert "note: read" in res.output


def test_cli_note_text_via_flag(tmp_path: Path) -> None:
    runner = CliRunner()
    env = _env(tmp_path)
    res = runner.invoke(
        cli, ["push", "note", "--text", "found a flaky test", "--session", "demo"],
        env=env,
    )
    assert res.exit_code == 0, res.output
    got = cards_mod.read_cards("demo", paths=_paths(tmp_path))
    assert got[0]["body"]["text"] == "found a flaky test"


def test_cli_note_empty_errors(tmp_path: Path) -> None:
    runner = CliRunner()
    env = _env(tmp_path)
    res = runner.invoke(
        cli, ["push", "note", "--session", "demo"], input="", env=env
    )
    assert res.exit_code != 0
    assert "empty note" in res.output


def test_cli_read_unknown_card_errors(tmp_path: Path) -> None:
    runner = CliRunner()
    env = _env(tmp_path)
    res = runner.invoke(
        cli, ["push", "read", "--id", "ghost", "--session", "demo"], env=env
    )
    assert res.exit_code != 0
    assert "no card" in res.output
