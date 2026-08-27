"""Live session enumerator: tmuxctl names match the terminal, plus aplexer."""

from __future__ import annotations

import json

from pocketshell import session_enum


def _tmuxctl_table() -> str:
    # Includes the overflowed long-name row (single space before timestamp)
    # that HostTmuxSessionListParser was hardened against in issue #200, and
    # that a naive ``tokens[1]`` split still happens to keep — the load-bearing
    # check is equality with that parser's name set, including this row.
    return (
        "IDX  SESSION               CREATED\n"
        "1    git-pocketshell-release 2026-08-27 09:22:55 \n"
        "2    git-aplexer-2         2026-08-26 19:05:34 \n"
        "3    git-dataops-2         2026-08-26 16:14:33 \n"
        "4    git-pocketshell-2     2026-08-26 15:51:07 \n"
        "5    git-dtc-website-4     2026-08-26 15:23:22 \n"
        "6    git-dtc-website-3     2026-08-26 15:09:26 \n"
        "7    git-zoom-calls        2026-08-26 13:48:17 \n"
        "8    git-aplexer           2026-08-26 13:09:26 \n"
        "9    git-game-tester       2026-08-26 07:12:25 \n"
        "10   git-ai-shipping-labs  2026-08-25 17:28:03 \n"
        "11   git-dtc-website       2026-08-24 12:26:03 \n"
        "12   git-pocketshell       2026-08-24 12:26:02 \n"
        "\n"
        "Join a session: tmuxctl <id> or tmuxctl <session>\n"
        "Create a new one: tmuxctl :<session>\n"
        "Use current folder: tmuxctl - or tmuxctl -name\n"
        "Help: tmuxctl --help\n"
    )


def _default_socket_three() -> set[str]:
    # What a bare ``tmux list-sessions`` on the default socket reports —
    # the three-session subset the phone currently shows.
    return {"git-dtc-website", "git-game-tester", "git-pocketshell"}


def test_tmuxctl_table_name_set_equals_live_enumerator_including_long_names() -> None:
    table = _tmuxctl_table()
    names = session_enum.parse_tmuxctl_list_names(table)
    assert "git-pocketshell-release" in names
    assert "git-ai-shipping-labs" in names
    assert len(names) == 12
    assert set(names) == {
        "git-pocketshell-release",
        "git-aplexer-2",
        "git-dataops-2",
        "git-pocketshell-2",
        "git-dtc-website-4",
        "git-dtc-website-3",
        "git-zoom-calls",
        "git-aplexer",
        "git-game-tester",
        "git-ai-shipping-labs",
        "git-dtc-website",
        "git-pocketshell",
    }
    # The default-socket subset is strictly smaller; the enumerator must
    # not collapse to it.
    assert set(names) != _default_socket_three()
    assert _default_socket_three() < set(names)


def test_whitespace_split_would_not_define_the_enumerator() -> None:
    """A row whose SESSION column overflows must still be named in full.

    Token-split ``tokens[1]`` happens to work for space-free names; the
    timestamp-anchored parser is what Android uses and is the contract.
    """
    overflow = (
        "5    git-ai-shipping-labs-workshops-raw-guard 2026-05-20 17:41:29 \n"
    )
    assert session_enum.parse_tmuxctl_list_name(overflow) == (
        "git-ai-shipping-labs-workshops-raw-guard"
    )


def test_hints_and_header_are_not_sessions() -> None:
    assert session_enum.parse_tmuxctl_list_names(
        "IDX  SESSION               CREATED\n\nJoin a session: tmuxctl 1\n"
    ) == []


def test_union_includes_aplexer_rows_tagged_as_second_manager() -> None:
    snapshot = [
        {
            "id": "b3feff71-4a78-4055-a2d3-6c99187ecffb",
            "tag": "codex",
            "engine": "codex",
            "workspace": "/home/alexey/git/toyaikit",
            "cwd": "/home/alexey/git/toyaikit",
            "created_at_ms": 1787774700696,
        }
    ]
    sessions = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        aplexer_payload=snapshot,
    )
    names = [row.name for row in sessions]
    assert "git-pocketshell-release" in names
    assert "toyaikit:codex" in names
    aplexer_row = next(row for row in sessions if row.manager == "aplexer")
    assert aplexer_row.name == "toyaikit:codex"
    assert aplexer_row.aplexer_id == "b3feff71-4a78-4055-a2d3-6c99187ecffb"
    assert aplexer_row.attach == "a attach b3feff71-4a78-4055-a2d3-6c99187ecffb"
    assert aplexer_row.engine == "codex"


def test_tmux_only_host_lists_only_tmux() -> None:
    sessions = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        include_aplexer=False,
    )
    assert {row.manager for row in sessions} == {"tmux"}
    assert len(sessions) == 12


def test_json_payload_tags_managers() -> None:
    sessions = session_enum.enumerate_live_sessions(
        tmuxctl_stdout=_tmuxctl_table(),
        aplexer_payload=[
            {
                "id": "abc",
                "tag": "live",
                "engine": "grok",
                "workspace": "/tmp/aplexer-follow",
            }
        ],
    )
    payload = session_enum.json_payload(sessions)
    assert payload["managers"] == ["tmux", "aplexer"]
    names = {item["name"] for item in payload["sessions"]}
    assert "git-pocketshell" in names
    assert "aplexer-follow:live" in names
    encoded = json.dumps(payload)
    parsed = json.loads(encoded)
    assert parsed["managers"] == ["tmux", "aplexer"]
