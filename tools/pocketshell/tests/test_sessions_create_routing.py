"""Unit tests for `pocketshell sessions create` backend routing (task H-3).

Covers, per the simplification plan §B.3:

- `pocketshell.config.load_config` — missing file -> defaults, `[backends]`
  parsing, and the deliberate fail-loud behaviour on a malformed file.
- `sessions._route_backend` — the pure routing matrix: explicit `--backend`
  beats config beats default, and `--engine` selects `[backends].agent` while
  a plain session selects `[backends].shell`.
- argv construction for both arms (`tmuxctl create-detached` + the
  server-side `tmux send-keys` launch line; `a --json start`).
- the schema-2 `--json` envelope, including the error envelope.
- idempotency: an existing session is `created: false`, exit 0, and never
  gets a second agent launched into it.

Every external process is stubbed at a module-level seam
(`_resolve_tmuxctl_binary`, `subprocess.run`, `_run_tmux`, `_aplexer_snapshot`,
`_run_aplexer`, `aplexer.which_a`), so no tmux server, tmuxctl or `a` binary
is ever touched.
"""

from __future__ import annotations

import json
import os
import subprocess
from pathlib import Path
from typing import Any, Optional, Sequence

import pytest
from click.testing import CliRunner

from pocketshell import config as psconfig
from pocketshell import sessions
from pocketshell.sessions import _route_backend, sessions_group


# ----- fixtures -------------------------------------------------------


@pytest.fixture(autouse=True)
def isolated_config(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """Point config + tmux socket discovery at a throwaway dir.

    Without this the suite would read the developer's real
    `~/.config/pocketshell/config.toml` and the real tmux socket dir — the
    routing answer would then depend on the host it runs on.
    """
    config_home = tmp_path / "xdg"
    monkeypatch.setenv("XDG_CONFIG_HOME", str(config_home))
    monkeypatch.setenv("TMUX_TMPDIR", str(tmp_path / "tmux-tmpdir"))
    return config_home


def write_config(config_home: Path, text: str) -> Path:
    path = config_home / "pocketshell" / "config.toml"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


def socket_dir(tmp_path: Path) -> Path:
    return tmp_path / "tmux-tmpdir" / f"tmux-{os.getuid()}"


def tmuxctl_socket(tmp_path: Path, name: str) -> str:
    return str(socket_dir(tmp_path) / f"tmuxctl-{name}")


def _fake_completed(returncode: int = 0) -> subprocess.CompletedProcess:
    return subprocess.CompletedProcess(
        args=[], returncode=returncode, stdout="", stderr=""
    )


class TmuxStub:
    """Stub for `sessions._run_tmux`: records argv, answers `has-session`."""

    def __init__(self, live_sockets: Sequence[str] = (), send_keys_code: int = 0):
        self.live_sockets = set(live_sockets)
        self.send_keys_code = send_keys_code
        self.calls: list[list[str]] = []

    def __call__(self, argv: Sequence[str]) -> tuple[int, str, str]:
        argv = list(argv)
        self.calls.append(argv)
        socket_path = argv[argv.index("-S") + 1]
        if "has-session" in argv:
            return (0 if socket_path in self.live_sockets else 1), "", ""
        if "send-keys" in argv:
            return self.send_keys_code, "", ("boom" if self.send_keys_code else "")
        return 0, "", ""

    @property
    def send_keys_calls(self) -> list[list[str]]:
        return [call for call in self.calls if "send-keys" in call]


class TmuxctlStub:
    """Stub for `sessions.subprocess.run` (the `tmuxctl create-detached` call)."""

    def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = ""):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr
        self.calls: list[list[str]] = []
        self.kwargs: list[dict] = []

    def __call__(self, argv: Sequence[str], **kwargs: Any) -> subprocess.CompletedProcess:
        self.calls.append(list(argv))
        self.kwargs.append(dict(kwargs))
        captured = bool(kwargs.get("capture_output"))
        return subprocess.CompletedProcess(
            args=list(argv),
            returncode=self.returncode,
            stdout=self.stdout if captured else None,
            stderr=self.stderr if captured else None,
        )


class AplexerStub:
    """Stub for `sessions._run_aplexer` (`a --json start`)."""

    def __init__(self, record: Optional[dict] = None, returncode: int = 0, stderr: str = ""):
        self.record = record
        self.returncode = returncode
        self.stderr = stderr
        self.calls: list[list[str]] = []

    def __call__(self, argv: Sequence[str]) -> tuple[int, str, str]:
        self.calls.append(list(argv))
        if self.returncode != 0:
            return self.returncode, "", self.stderr
        return 0, json.dumps(self.record or {}), ""


def use_tmux_backend(
    monkeypatch: pytest.MonkeyPatch,
    *,
    tmuxctl: TmuxctlStub,
    tmux: TmuxStub,
) -> None:
    monkeypatch.setattr(sessions, "_resolve_tmuxctl_binary", lambda: "/fake/tmuxctl")
    monkeypatch.setattr(sessions.subprocess, "run", tmuxctl)
    monkeypatch.setattr(sessions, "_run_tmux", tmux)


def use_aplexer_backend(
    monkeypatch: pytest.MonkeyPatch,
    *,
    start: AplexerStub,
    snapshot: Any = None,
) -> None:
    monkeypatch.setattr(sessions._aplexer, "which_a", lambda env=None: "/fake/a")
    monkeypatch.setattr(sessions, "_aplexer_snapshot", lambda: snapshot)
    monkeypatch.setattr(sessions, "_run_aplexer", start)


def envelope(result) -> dict:
    return json.loads(result.stdout)


# ----- config.py ------------------------------------------------------


def test_config_defaults_when_file_missing(isolated_config: Path) -> None:
    assert not (isolated_config / "pocketshell" / "config.toml").exists()
    assert psconfig.load_config() == {"backends": {"agent": "tmux", "shell": "tmux"}}


def test_config_path_honours_xdg_config_home(isolated_config: Path) -> None:
    assert psconfig.config_path() == (
        isolated_config / "pocketshell" / "config.toml"
    )


def test_config_path_falls_back_to_home_dot_config() -> None:
    env = {"HOME": "/home/someone"}
    assert psconfig.config_path(env) == Path(
        "/home/someone/.config/pocketshell/config.toml"
    )


def test_config_reads_backends_section(isolated_config: Path) -> None:
    write_config(
        isolated_config,
        '[backends]\nagent = "aplexer"\nshell = "tmux"\n',
    )
    assert psconfig.load_config()["backends"] == {
        "agent": "aplexer",
        "shell": "tmux",
    }


def test_config_partial_backends_section_keeps_defaults(isolated_config: Path) -> None:
    """A file that sets only `agent` still reports a full backends table."""
    write_config(isolated_config, '[backends]\nagent = "aplexer"\n')
    assert psconfig.load_config()["backends"] == {
        "agent": "aplexer",
        "shell": "tmux",
    }


def test_config_empty_file_is_defaults(isolated_config: Path) -> None:
    write_config(isolated_config, "\n")
    assert psconfig.load_config()["backends"] == psconfig.DEFAULT_BACKENDS


def test_config_malformed_toml_fails_loud(isolated_config: Path) -> None:
    write_config(isolated_config, "[backends\nagent = tmux\n")
    with pytest.raises(psconfig.ConfigError) as excinfo:
        psconfig.load_config()
    assert "not valid TOML" in str(excinfo.value)


def test_config_unknown_backend_value_fails_loud(isolated_config: Path) -> None:
    write_config(isolated_config, '[backends]\nagent = "banana"\n')
    with pytest.raises(psconfig.ConfigError) as excinfo:
        psconfig.load_config()
    assert "banana" in str(excinfo.value)


def test_config_unknown_backends_key_fails_loud(isolated_config: Path) -> None:
    write_config(isolated_config, '[backends]\nagentt = "tmux"\n')
    with pytest.raises(psconfig.ConfigError):
        psconfig.load_config()


def test_config_non_table_backends_fails_loud(isolated_config: Path) -> None:
    write_config(isolated_config, 'backends = "aplexer"\n')
    with pytest.raises(psconfig.ConfigError):
        psconfig.load_config()


def test_config_preserves_unrelated_sections(isolated_config: Path) -> None:
    write_config(isolated_config, '[future]\nkey = 1\n')
    loaded = psconfig.load_config()
    assert loaded["future"] == {"key": 1}
    assert loaded["backends"] == psconfig.DEFAULT_BACKENDS


# ----- routing matrix (pure) -----------------------------------------


APLEXER_EVERYWHERE = {"backends": {"agent": "aplexer", "shell": "aplexer"}}
AGENT_APLEXER = {"backends": {"agent": "aplexer", "shell": "tmux"}}


def test_route_default_is_tmux_with_no_config() -> None:
    assert _route_backend(None, None, {}) == "tmux"
    assert _route_backend("claude", None, {}) == "tmux"


def test_route_defaults_from_loaded_defaults() -> None:
    config = psconfig.default_config()
    assert _route_backend(None, None, config) == "tmux"
    assert _route_backend("claude", None, config) == "tmux"


def test_route_engine_reads_agent_key() -> None:
    assert _route_backend("claude", None, AGENT_APLEXER) == "aplexer"


def test_route_no_engine_reads_shell_key() -> None:
    """The agent key must NOT leak into a plain shell session."""
    assert _route_backend(None, None, AGENT_APLEXER) == "tmux"
    assert _route_backend(None, None, {"backends": {"shell": "aplexer"}}) == "aplexer"


def test_route_explicit_backend_flag_beats_config() -> None:
    assert _route_backend("claude", "tmux", APLEXER_EVERYWHERE) == "tmux"
    assert _route_backend(None, "aplexer", psconfig.default_config()) == "aplexer"
    assert _route_backend("claude", "tmux", AGENT_APLEXER) == "tmux"


def test_route_blank_values_fall_through_to_default() -> None:
    assert _route_backend("claude", "  ", {}) == "tmux"
    assert _route_backend("  ", None, {"backends": {"agent": "aplexer"}}) == "tmux"
    assert _route_backend("claude", None, {"backends": {"agent": ""}}) == "tmux"
    assert _route_backend("claude", None, {"backends": None}) == "tmux"


# ----- tmux arm -------------------------------------------------------


def test_tmux_arm_json_envelope_for_new_session(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    tmuxctl, tmux = TmuxctlStub(), TmuxStub(live_sockets=())
    use_tmux_backend(monkeypatch, tmuxctl=tmuxctl, tmux=tmux)

    result = CliRunner().invoke(sessions_group, ["create", "work", "--json"])

    assert result.exit_code == 0, result.output
    assert envelope(result) == {
        "schema": 2,
        "name": "work",
        "manager": "tmux",
        "id": None,
        "created": True,
    }
    assert tmuxctl.calls == [["/fake/tmuxctl", "create-detached", "work"]]


def test_tmux_arm_forwards_cwd_and_mem_with_json(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    tmuxctl, tmux = TmuxctlStub(), TmuxStub()
    use_tmux_backend(monkeypatch, tmuxctl=tmuxctl, tmux=tmux)

    result = CliRunner().invoke(
        sessions_group,
        ["create", "work", "--cwd", "/home/me/proj", "--mem", "16G", "--json"],
    )

    assert result.exit_code == 0, result.output
    assert tmuxctl.calls == [
        ["/fake/tmuxctl", "create-detached", "work", "-c", "/home/me/proj",
         "--mem", "16G"]
    ]


def test_tmux_arm_existing_session_reports_created_false(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """Idempotent success: the session already exists, exit 0, created=false."""
    tmuxctl = TmuxctlStub()
    tmux = TmuxStub(live_sockets=[tmuxctl_socket(tmp_path, "work")])
    use_tmux_backend(monkeypatch, tmuxctl=tmuxctl, tmux=tmux)

    result = CliRunner().invoke(sessions_group, ["create", "work", "--json"])

    assert result.exit_code == 0, result.output
    assert envelope(result)["created"] is False
    assert envelope(result)["name"] == "work"
    # tmuxctl create-detached is still called: it is the idempotent primitive.
    assert tmuxctl.calls == [["/fake/tmuxctl", "create-detached", "work"]]


def test_tmux_arm_probes_exact_session_name_on_its_own_socket(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    tmuxctl, tmux = TmuxctlStub(), TmuxStub()
    use_tmux_backend(monkeypatch, tmuxctl=tmuxctl, tmux=tmux)

    CliRunner().invoke(sessions_group, ["create", "work", "--json"])

    assert tmux.calls[0] == [
        "tmux",
        "-S",
        tmuxctl_socket(tmp_path, "work"),
        "has-session",
        "-t",
        "=work",
    ]
    # Falls back to the shared default socket for a session tmuxctl did not make.
    assert tmux.calls[1][:3] == [
        "tmux",
        "-S",
        str(socket_dir(tmp_path) / "default"),
    ]


def test_tmux_arm_with_engine_sends_launch_line(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    tmuxctl = TmuxctlStub()
    # Not live on the pre-create probe; live once tmuxctl has made it.
    tmux = TmuxStub()

    def staged(argv):
        if "has-session" in list(argv) and tmuxctl.calls:
            tmux.live_sockets.add(tmuxctl_socket(tmp_path, "work"))
        return tmux(argv)

    use_tmux_backend(monkeypatch, tmuxctl=tmuxctl, tmux=staged)

    result = CliRunner().invoke(
        sessions_group,
        ["create", "work", "--cwd", "/home/me/proj", "--engine", "claude", "--json"],
    )

    assert result.exit_code == 0, result.output
    assert envelope(result)["created"] is True
    assert tmux.send_keys_calls == [
        [
            "tmux",
            "-S",
            tmuxctl_socket(tmp_path, "work"),
            "send-keys",
            # target-PANE, so the exact-match session needs its trailing
            # colon: a bare "=work" is "can't find pane" (seen on the dev box).
            "-t",
            "=work:",
            "pocketshell agent claude --dir /home/me/proj",
            "Enter",
        ]
    ]


def test_tmux_arm_launch_line_includes_profile() -> None:
    assert sessions.agent_launch_command(
        "codex", directory="/home/me/proj", profile="work"
    ) == "pocketshell agent codex --dir /home/me/proj --profile work"


def test_tmux_arm_launch_line_shell_quotes_values() -> None:
    line = sessions.agent_launch_command(
        "claude", directory="/home/me/my proj", profile="a;rm -rf /"
    )
    assert line == (
        "pocketshell agent claude --dir '/home/me/my proj' --profile 'a;rm -rf /'"
    )


def test_tmux_arm_existing_session_does_not_relaunch_agent(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """A second create must not start a second agent in the same session."""
    tmuxctl = TmuxctlStub()
    tmux = TmuxStub(live_sockets=[tmuxctl_socket(tmp_path, "work")])
    use_tmux_backend(monkeypatch, tmuxctl=tmuxctl, tmux=tmux)

    result = CliRunner().invoke(
        sessions_group, ["create", "work", "--engine", "claude", "--json"]
    )

    assert result.exit_code == 0, result.output
    assert envelope(result)["created"] is False
    assert tmux.send_keys_calls == []


def test_tmux_arm_without_engine_or_json_skips_the_probe(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The human, shell-only path stays a single tmuxctl call (no tmux probe)."""
    tmuxctl, tmux = TmuxctlStub(), TmuxStub()
    use_tmux_backend(monkeypatch, tmuxctl=tmuxctl, tmux=tmux)

    result = CliRunner().invoke(sessions_group, ["create", "work"])

    assert result.exit_code == 0, result.output
    assert result.stdout == ""
    assert tmux.calls == []
    assert tmuxctl.calls == [["/fake/tmuxctl", "create-detached", "work"]]


def test_tmux_arm_json_stdout_carries_only_the_envelope(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """REGRESSION (observed on the dev box): `tmuxctl create-detached` echoes
    the session name. Inherited, that lands in front of the JSON envelope and
    a client's `json.loads(stdout)` fails. stdout must be the envelope only;
    tmuxctl's chatter is relayed to stderr.
    """
    tmuxctl = TmuxctlStub(stdout="h3-scratch\n", stderr="tmuxctl: note\n")
    use_tmux_backend(monkeypatch, tmuxctl=tmuxctl, tmux=TmuxStub())

    result = CliRunner().invoke(sessions_group, ["create", "h3-scratch", "--json"])

    assert result.exit_code == 0, result.output
    assert json.loads(result.stdout)["name"] == "h3-scratch"
    assert result.stdout.lstrip().startswith("{")
    assert "h3-scratch\n" in result.stderr
    assert "tmuxctl: note" in result.stderr
    assert tmuxctl.kwargs[0]["capture_output"] is True


def test_tmux_arm_human_path_still_inherits_child_output(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Without --json the wrapper stays a transparent pass-through."""
    tmuxctl = TmuxctlStub(stdout="work\n")
    use_tmux_backend(monkeypatch, tmuxctl=tmuxctl, tmux=TmuxStub())

    result = CliRunner().invoke(sessions_group, ["create", "work"])

    assert result.exit_code == 0, result.output
    assert "capture_output" not in tmuxctl.kwargs[0]


def test_tmux_arm_send_keys_failure_is_a_json_error(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    tmuxctl = TmuxctlStub()
    tmux = TmuxStub(
        live_sockets=[tmuxctl_socket(tmp_path, "work")], send_keys_code=1
    )
    # Session does not exist before create, but the socket answers afterwards:
    # emulate by making the probe live only after tmuxctl ran.
    seen: list[str] = []

    def staged(argv):
        argv = list(argv)
        if "has-session" in argv and not seen:
            seen.append("probed")
            return 1, "", ""
        return tmux(argv)

    use_tmux_backend(monkeypatch, tmuxctl=tmuxctl, tmux=staged)

    result = CliRunner().invoke(
        sessions_group, ["create", "work", "--engine", "claude", "--json"]
    )

    assert result.exit_code != 0
    payload = envelope(result)
    assert payload["schema"] == 2
    assert "could not start claude" in payload["error"]


# ----- failure envelopes ---------------------------------------------


def test_missing_tmuxctl_json_error_envelope(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(sessions, "_resolve_tmuxctl_binary", lambda: None)
    monkeypatch.setattr(sessions.subprocess, "run", TmuxctlStub())

    result = CliRunner().invoke(sessions_group, ["create", "work", "--json"])

    assert result.exit_code == 127
    payload = envelope(result)
    assert payload["schema"] == 2
    assert "tmuxctl" in payload["error"]
    assert "name" not in payload


def test_tmuxctl_nonzero_exit_json_error_envelope(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    use_tmux_backend(
        monkeypatch, tmuxctl=TmuxctlStub(returncode=5), tmux=TmuxStub()
    )

    result = CliRunner().invoke(sessions_group, ["create", "work", "--json"])

    assert result.exit_code == 5
    assert "exited 5" in envelope(result)["error"]


def test_malformed_config_json_error_envelope(
    monkeypatch: pytest.MonkeyPatch, isolated_config: Path
) -> None:
    write_config(isolated_config, "[backends\n")
    use_tmux_backend(monkeypatch, tmuxctl=TmuxctlStub(), tmux=TmuxStub())

    result = CliRunner().invoke(sessions_group, ["create", "work", "--json"])

    assert result.exit_code == 2
    assert "not valid TOML" in envelope(result)["error"]


def test_malformed_config_human_path_writes_stderr(
    monkeypatch: pytest.MonkeyPatch, isolated_config: Path
) -> None:
    write_config(isolated_config, "[backends\n")
    use_tmux_backend(monkeypatch, tmuxctl=TmuxctlStub(), tmux=TmuxStub())

    result = CliRunner().invoke(sessions_group, ["create", "work"])

    assert result.exit_code == 2
    assert result.stdout == ""
    assert "not valid TOML" in result.stderr


def test_missing_aplexer_binary_json_error_envelope(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(sessions._aplexer, "which_a", lambda env=None: None)

    result = CliRunner().invoke(
        sessions_group, ["create", "work", "--backend", "aplexer", "--json"]
    )

    assert result.exit_code == 127
    assert "aplexer" in envelope(result)["error"]


# ----- aplexer arm ----------------------------------------------------


def _record(ident: str = "11111111-2222-3333-4444-555555555555") -> dict:
    return {
        "id": ident,
        "workspace": "/home/me/proj",
        "tag": "work",
        "engine": "claude",
        "phase": "starting",
    }


def test_aplexer_arm_start_argv(monkeypatch: pytest.MonkeyPatch) -> None:
    start = AplexerStub(record=_record())
    use_aplexer_backend(monkeypatch, start=start, snapshot=[])

    result = CliRunner().invoke(
        sessions_group,
        [
            "create", "work",
            "--cwd", "/home/me/proj",
            "--engine", "claude",
            "--profile", "work",
            "--backend", "aplexer",
            "--json",
        ],
    )

    assert result.exit_code == 0, result.output
    assert start.calls == [
        [
            "/fake/a", "--json", "start",
            "--workspace", "/home/me/proj",
            "--tag", "work",
            "--engine", "claude",
            "--profile", "work",
        ]
    ]


def test_aplexer_arm_omits_engine_for_a_shell_session(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    start = AplexerStub(record=_record())
    use_aplexer_backend(monkeypatch, start=start, snapshot=[])

    result = CliRunner().invoke(
        sessions_group,
        ["create", "work", "--cwd", "/home/me/proj", "--backend", "aplexer", "--json"],
    )

    assert result.exit_code == 0, result.output
    assert start.calls == [
        ["/fake/a", "--json", "start", "--workspace", "/home/me/proj", "--tag", "work"]
    ]


def test_aplexer_arm_json_envelope(monkeypatch: pytest.MonkeyPatch) -> None:
    start = AplexerStub(record=_record())
    use_aplexer_backend(monkeypatch, start=start, snapshot=[])

    result = CliRunner().invoke(
        sessions_group,
        ["create", "work", "--cwd", "/home/me/proj", "--backend", "aplexer", "--json"],
    )

    assert envelope(result) == {
        "schema": 2,
        # `<workspace-basename>:<tag>` — the same name `sessions list --json`
        # reports for this row.
        "name": "proj:work",
        "manager": "aplexer",
        "id": "11111111-2222-3333-4444-555555555555",
        "created": True,
    }


def test_aplexer_arm_existing_session_is_created_false(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    start = AplexerStub(record=_record())
    use_aplexer_backend(monkeypatch, start=start, snapshot=[_record()])

    result = CliRunner().invoke(
        sessions_group,
        ["create", "work", "--cwd", "/home/me/proj", "--backend", "aplexer", "--json"],
    )

    assert result.exit_code == 0, result.output
    assert envelope(result) == {
        "schema": 2,
        "name": "proj:work",
        "manager": "aplexer",
        "id": "11111111-2222-3333-4444-555555555555",
        "created": False,
    }
    # No second `a start` for a workspace+tag aplexer already holds.
    assert start.calls == []


def test_aplexer_arm_finished_session_is_recreated(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """`a start` reclaims a workspace+tag whose session exited — so it created."""
    dead = dict(_record("dead"), phase="exited")
    start = AplexerStub(record=_record())
    use_aplexer_backend(monkeypatch, start=start, snapshot=[dead])

    result = CliRunner().invoke(
        sessions_group,
        ["create", "work", "--cwd", "/home/me/proj", "--backend", "aplexer", "--json"],
    )

    assert result.exit_code == 0, result.output
    assert envelope(result)["created"] is True
    assert len(start.calls) == 1


def test_aplexer_arm_other_workspace_same_tag_still_creates(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    other = dict(_record("other"), workspace="/home/me/elsewhere")
    start = AplexerStub(record=_record())
    use_aplexer_backend(monkeypatch, start=start, snapshot=[other])

    result = CliRunner().invoke(
        sessions_group,
        ["create", "work", "--cwd", "/home/me/proj", "--backend", "aplexer", "--json"],
    )

    assert result.exit_code == 0, result.output
    assert envelope(result)["created"] is True
    assert len(start.calls) == 1


def test_aplexer_arm_start_failure_json_error_envelope(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    start = AplexerStub(returncode=1, stderr="workspace+tag already belongs to …")
    use_aplexer_backend(monkeypatch, start=start, snapshot=[])

    result = CliRunner().invoke(
        sessions_group,
        ["create", "work", "--cwd", "/home/me/proj", "--backend", "aplexer", "--json"],
    )

    assert result.exit_code == 1
    assert "already belongs" in envelope(result)["error"]


# ----- routing wired end-to-end through the CLI ----------------------


def test_cli_engine_routes_via_backends_agent_config(
    monkeypatch: pytest.MonkeyPatch, isolated_config: Path
) -> None:
    write_config(isolated_config, '[backends]\nagent = "aplexer"\n')
    start = AplexerStub(record=_record())
    use_aplexer_backend(monkeypatch, start=start, snapshot=[])
    monkeypatch.setattr(sessions, "_resolve_tmuxctl_binary", lambda: "/fake/tmuxctl")
    tmuxctl = TmuxctlStub()
    monkeypatch.setattr(sessions.subprocess, "run", tmuxctl)

    result = CliRunner().invoke(
        sessions_group,
        ["create", "work", "--cwd", "/home/me/proj", "--engine", "claude", "--json"],
    )

    assert result.exit_code == 0, result.output
    assert envelope(result)["manager"] == "aplexer"
    assert len(start.calls) == 1
    assert tmuxctl.calls == []


def test_cli_shell_session_ignores_backends_agent_config(
    monkeypatch: pytest.MonkeyPatch, isolated_config: Path
) -> None:
    write_config(isolated_config, '[backends]\nagent = "aplexer"\n')
    tmuxctl, tmux = TmuxctlStub(), TmuxStub()
    use_tmux_backend(monkeypatch, tmuxctl=tmuxctl, tmux=tmux)
    start = AplexerStub(record=_record())
    monkeypatch.setattr(sessions, "_run_aplexer", start)

    result = CliRunner().invoke(sessions_group, ["create", "work", "--json"])

    assert result.exit_code == 0, result.output
    assert envelope(result)["manager"] == "tmux"
    assert start.calls == []
    assert tmuxctl.calls == [["/fake/tmuxctl", "create-detached", "work"]]


def test_cli_backend_flag_overrides_aplexer_config(
    monkeypatch: pytest.MonkeyPatch, isolated_config: Path
) -> None:
    write_config(isolated_config, '[backends]\nagent = "aplexer"\nshell = "aplexer"\n')
    tmuxctl, tmux = TmuxctlStub(), TmuxStub()
    use_tmux_backend(monkeypatch, tmuxctl=tmuxctl, tmux=tmux)
    start = AplexerStub(record=_record())
    monkeypatch.setattr(sessions, "_run_aplexer", start)

    result = CliRunner().invoke(
        sessions_group, ["create", "work", "--backend", "tmux", "--json"]
    )

    assert result.exit_code == 0, result.output
    assert envelope(result)["manager"] == "tmux"
    assert start.calls == []


def test_create_help_lists_the_new_options() -> None:
    result = CliRunner().invoke(sessions_group, ["create", "--help"])
    assert result.exit_code == 0, result.output
    for flag in ("--engine", "--profile", "--backend", "--json"):
        assert flag in result.output
