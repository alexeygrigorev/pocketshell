"""Helper tests default to the native path so a host with ``a`` on PATH
cannot change fixture results. Opt in per test with
``POCKETSHELL_APLEXER=1`` plus ``APLEXER_BIN``.
"""

from __future__ import annotations

import json
import stat
import textwrap

import pytest


@pytest.fixture(autouse=True)
def _isolate_process_environment(tmp_path, monkeypatch):
    """Keep every test away from the developer's live home/XDG state."""
    monkeypatch.setenv("HOME", str(tmp_path / "home"))
    for name in ("CONFIG", "CACHE", "DATA", "STATE"):
        monkeypatch.setenv(f"XDG_{name}_HOME", str(tmp_path / name.lower()))
    monkeypatch.setenv("XDG_RUNTIME_DIR", str(tmp_path / "runtime"))
    monkeypatch.setenv("POCKETSHELL_APLEXER", "0")


@pytest.fixture
def install_fake_a(tmp_path, monkeypatch):
    """Write a stub ``a`` and point ``APLEXER_BIN`` at it, enabling probes."""

    def _install(
        *,
        profiles=None,
        engines=None,
        launch=None,
        snapshot=None,
        listing=None,
        exit_code=0,
        sleep=0,
        stdout=None,
    ):
        script = tmp_path / "fake-a"
        if sleep:
            body = textwrap.dedent(
                f"""\
                #!/bin/sh
                sleep {sleep}
                """
            )
        elif exit_code:
            body = textwrap.dedent(
                f"""\
                #!/bin/sh
                echo fail >&2
                exit {exit_code}
                """
            )
        elif stdout is not None:
            body = textwrap.dedent(
                f"""\
                #!/bin/sh
                cat <<'EOF'
                {stdout}
                EOF
                """
            )
        else:
            table = {
                "profiles": profiles,
                "engines": engines,
                "launch-spec": launch,
                "snapshot": snapshot,
                "list": listing if listing is not None else snapshot,
            }
            encoded = json.dumps(table)
            body = textwrap.dedent(
                f"""\
                #!/usr/bin/env python3
                import json, sys
                TABLE = json.loads({encoded!r})
                args = [a for a in sys.argv[1:] if a != "--json"]
                cmd = args[0] if args else ""
                payload = TABLE.get(cmd)
                if payload is None:
                    sys.exit(2)
                print(json.dumps(payload))
                """
            )
        script.write_text(body, encoding="utf-8")
        script.chmod(script.stat().st_mode | stat.S_IEXEC)
        monkeypatch.setenv("APLEXER_BIN", str(script))
        monkeypatch.setenv("POCKETSHELL_APLEXER", "1")
        monkeypatch.delenv("POCKETSHELL_APLEXER_PROFILES", raising=False)
        monkeypatch.delenv("POCKETSHELL_APLEXER_ENGINES", raising=False)
        monkeypatch.delenv("POCKETSHELL_APLEXER_LAUNCH", raising=False)
        monkeypatch.delenv("POCKETSHELL_APLEXER_SESSIONS", raising=False)
        return script

    return _install
