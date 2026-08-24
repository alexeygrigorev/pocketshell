"""Unit tests for `pocketshell sessions`.

The third-PR scope (#218) exercises:

- `pocketshell --help` lists the `sessions` subcommand.
- `pocketshell sessions --help` lists the `list` subcommand.
- `pocketshell sessions list` forwards verbatim to `tmuxctl list`.
- `pocketshell sessions list --by activity` forwards `--by activity`.
- unsupported options are still forwarded verbatim, and the current
  `tmuxctl list --json` rejection is surfaced unchanged (there is no fake
  structured contract).
- Missing `tmuxctl` produces a friendly stderr message + exit 127.
- stdout / stderr / exit-code from the subprocess are proxied verbatim.
- The proxied output shape stays byte-identical so the Android-side
  `HostTmuxSessionListParser` (issue #200) keeps working.

The tests stub `pocketshell.sessions._resolve_tmuxctl_binary` and
`subprocess.run` so they never invoke a real `tmuxctl` binary; the
contract under test is "pocketshell delegates correctly to whatever
tmuxctl exists on the host", not "tmux session enumeration works".
"""

from __future__ import annotations

import subprocess
from typing import Sequence
from unittest.mock import patch

from click.testing import CliRunner

from pocketshell.cli import cli
from pocketshell.sessions import sessions_group


def _fake_completed(
    stdout: str = "",
    stderr: str = "",
    returncode: int = 0,
) -> subprocess.CompletedProcess:
    return subprocess.CompletedProcess(
        args=[],
        returncode=returncode,
        stdout=stdout,
        stderr=stderr,
    )


def _fake_sessions_table() -> str:
    """Realistic shape of `tmuxctl list` output.

    Pinned here (not in the source) so the test can pin the contract
    against a captured fixture without coupling the wrapper to the
    output shape. Includes a long-name row (single space before the
    timestamp) so the byte-identical assertion exercises the exact
    edge case `HostTmuxSessionListParser` was hardened against in
    issue #200.
    """
    return (
        "IDX  SESSION               CREATED\n"
        "1    git-tmuxcli           2026-05-27 17:32:30 \n"
        "2    git-ai-engineering-buildcamp 2026-05-27 15:55:44 \n"
        "8    git-ai-shipping-labs-workshops-raw-guard 2026-05-20 17:41:29 \n"
    )


# ----- top-level help wiring -----------------------------------------


def test_top_level_help_lists_sessions_subcommand() -> None:
    runner = CliRunner()
    result = runner.invoke(cli, ["--help"])
    assert result.exit_code == 0, result.output
    assert "sessions" in result.output


def test_sessions_help_lists_list_subcommand() -> None:
    runner = CliRunner()
    with patch("pocketshell.sessions.subprocess.run") as run, patch(
        "pocketshell.sessions._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"
    ):
        result = runner.invoke(sessions_group, ["--help"])
    assert result.exit_code == 0, result.output
    assert "list" in result.output
    run.assert_not_called()


# ----- sessions list -------------------------------------------------


def test_sessions_list_forwards_to_tmuxctl_and_proxies_stdout() -> None:
    payload = _fake_sessions_table()
    runner = CliRunner()
    with patch(
        "pocketshell.sessions._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"
    ), patch(
        "pocketshell.sessions.subprocess.run",
        return_value=_fake_completed(stdout=payload),
    ) as run:
        result = runner.invoke(sessions_group, ["list"])
    assert result.exit_code == 0, result.output
    # Byte-identical pass-through: the Android-side
    # `HostTmuxSessionListParser` already parses this exact shape and
    # must keep working when the app eventually routes through
    # `pocketshell sessions list` (issue #231).
    assert result.output == payload
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/tmuxctl", "list"]


def test_sessions_list_uses_daemon_when_available() -> None:
    payload = _fake_sessions_table()
    runner = CliRunner()
    with patch(
        "pocketshell.sessions._try_daemon_sessions_list",
        return_value={"stdout": payload, "stderr": "", "returncode": 0},
    ) as daemon_call, patch("pocketshell.sessions.subprocess.run") as run:
        result = runner.invoke(sessions_group, ["list", "--by", "activity"])
    assert result.exit_code == 0, result.output
    assert result.output == payload
    daemon_call.assert_called_once_with(sort_by="activity", extra_args=[])
    run.assert_not_called()


def test_sessions_list_falls_back_when_daemon_misses() -> None:
    payload = _fake_sessions_table()
    runner = CliRunner()
    with patch(
        "pocketshell.sessions._try_daemon_sessions_list",
        return_value=None,
    ), patch(
        "pocketshell.sessions._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"
    ), patch(
        "pocketshell.sessions.subprocess.run",
        return_value=_fake_completed(stdout=payload),
    ) as run:
        result = runner.invoke(sessions_group, ["list"])
    assert result.exit_code == 0, result.output
    assert result.output == payload
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/tmuxctl", "list"]


def test_sessions_list_forwards_by_activity() -> None:
    runner = CliRunner()
    with patch(
        "pocketshell.sessions._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"
    ), patch(
        "pocketshell.sessions.subprocess.run",
        return_value=_fake_completed(stdout=_fake_sessions_table()),
    ) as run:
        result = runner.invoke(sessions_group, ["list", "--by", "activity"])
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/tmuxctl", "list", "--by", "activity"]


def test_sessions_list_forwards_by_created() -> None:
    runner = CliRunner()
    with patch(
        "pocketshell.sessions._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"
    ), patch(
        "pocketshell.sessions.subprocess.run",
        return_value=_fake_completed(stdout=_fake_sessions_table()),
    ) as run:
        result = runner.invoke(sessions_group, ["list", "--by", "created"])
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/tmuxctl", "list", "--by", "created"]


def test_sessions_list_rejects_invalid_by_value() -> None:
    runner = CliRunner()
    with patch(
        "pocketshell.sessions._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"
    ), patch("pocketshell.sessions.subprocess.run") as run:
        result = runner.invoke(sessions_group, ["list", "--by", "bogus"])
    # Click's Choice() validation rejects the value before we ever
    # reach the subprocess, so the wrapper must not invoke tmuxctl.
    assert result.exit_code != 0
    run.assert_not_called()


def test_sessions_list_surfaces_current_tmuxctl_json_rejection() -> None:
    """The current tmuxctl has no structured list mode.

    The wrapper's pass-through behavior is intentional, but it must not turn
    an unsupported `--json` flag into a claimed success. This mirrors the
    current host command (`tmuxctl list --json` -> exit 2).
    """
    runner = CliRunner()
    with patch(
        "pocketshell.sessions._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"
    ), patch(
        "pocketshell.sessions.subprocess.run",
        return_value=_fake_completed(
            stderr="Error: No such option: --json\n",
            returncode=2,
        ),
    ) as run:
        result = runner.invoke(sessions_group, ["list", "--json"])
    assert result.exit_code == 2, result.output
    assert "No such option: --json" in result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/tmuxctl", "list", "--json"]


def test_sessions_list_forwards_by_and_unknown_options_together() -> None:
    """Known and unknown options both reach tmuxctl in typed order."""
    runner = CliRunner()
    with patch(
        "pocketshell.sessions._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"
    ), patch(
        "pocketshell.sessions.subprocess.run",
        return_value=_fake_completed(stdout=_fake_sessions_table()),
    ) as run:
        result = runner.invoke(
            sessions_group,
            ["list", "--by", "activity", "--future-option"],
        )
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    # `--by activity` is parsed by the wrapper; the unknown option falls
    # through via `ctx.args`. Both must end up in the final argv.
    assert invoked[0] == "/fake/tmuxctl"
    assert invoked[1] == "list"
    assert "--by" in invoked
    assert "activity" in invoked
    assert "--future-option" in invoked


# ----- missing-binary handling ---------------------------------------


def test_sessions_list_returns_127_when_tmuxctl_missing() -> None:
    runner = CliRunner()
    with patch(
        "pocketshell.sessions._resolve_tmuxctl_binary", return_value=None
    ), patch("pocketshell.sessions.subprocess.run") as run:
        result = runner.invoke(sessions_group, ["list"], catch_exceptions=False)
    # 127 is the POSIX exit code for "command not found"; matches the
    # `jobs` and `usage` subcommands so the Kotlin probe handler sees a
    # consistent missing-binary signal.
    assert result.exit_code == 127
    assert "tmuxctl" in result.output.lower()
    run.assert_not_called()


# ----- non-zero exit propagation -------------------------------------


def test_sessions_list_proxies_nonzero_exit_from_tmuxctl() -> None:
    runner = CliRunner()
    with patch(
        "pocketshell.sessions._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"
    ), patch(
        "pocketshell.sessions.subprocess.run",
        return_value=_fake_completed(
            stderr="tmuxctl: tmux server unavailable\n", returncode=4
        ),
    ):
        result = runner.invoke(sessions_group, ["list"])
    assert result.exit_code == 4
    # stderr from the subprocess must reach the user (otherwise
    # debugging a failing tmux probe would be opaque).
    assert "tmux server unavailable" in result.output


# ----- sessions create (#726) ----------------------------------------


def test_sessions_create_argv_omits_mem_by_default() -> None:
    """CRITICAL (#726): no `--mem` unless the caller asked for one.

    Omitting `--mem` lets tmuxctl resolve the per-project cap from
    cgroups.toml (PocketShell's is 30G); a hard-coded 24G would override
    that committed policy.
    """
    runner = CliRunner()
    with patch(
        "pocketshell.sessions._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"
    ), patch(
        "pocketshell.sessions.subprocess.run",
        return_value=_fake_completed(),
    ) as run:
        result = runner.invoke(sessions_group, ["create", "work"])
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/tmuxctl", "create-detached", "work"]
    assert "--mem" not in invoked
    assert "24G" not in invoked


def test_sessions_create_forwards_cwd() -> None:
    runner = CliRunner()
    with patch(
        "pocketshell.sessions._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"
    ), patch(
        "pocketshell.sessions.subprocess.run",
        return_value=_fake_completed(),
    ) as run:
        result = runner.invoke(
            sessions_group, ["create", "work", "--cwd", "/home/me/proj"]
        )
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == [
        "/fake/tmuxctl", "create-detached", "work", "-c", "/home/me/proj",
    ]
    assert "--mem" not in invoked


def test_sessions_create_passes_mem_through_when_given() -> None:
    runner = CliRunner()
    with patch(
        "pocketshell.sessions._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"
    ), patch(
        "pocketshell.sessions.subprocess.run",
        return_value=_fake_completed(),
    ) as run:
        result = runner.invoke(
            sessions_group, ["create", "work", "--mem", "16G"]
        )
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == [
        "/fake/tmuxctl", "create-detached", "work", "--mem", "16G",
    ]


def test_sessions_create_forwards_cwd_and_mem_together() -> None:
    runner = CliRunner()
    with patch(
        "pocketshell.sessions._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"
    ), patch(
        "pocketshell.sessions.subprocess.run",
        return_value=_fake_completed(),
    ) as run:
        result = runner.invoke(
            sessions_group,
            ["create", "work", "-c", "/home/me/proj", "--mem", "16G"],
        )
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == [
        "/fake/tmuxctl", "create-detached", "work",
        "-c", "/home/me/proj", "--mem", "16G",
    ]


def test_sessions_create_returns_127_when_tmuxctl_missing() -> None:
    runner = CliRunner()
    with patch(
        "pocketshell.sessions._resolve_tmuxctl_binary", return_value=None
    ), patch("pocketshell.sessions.subprocess.run") as run:
        result = runner.invoke(
            sessions_group, ["create", "work"], catch_exceptions=False
        )
    assert result.exit_code == 127
    assert "tmuxctl" in result.output.lower()
    run.assert_not_called()


def test_sessions_create_proxies_nonzero_exit_from_tmuxctl() -> None:
    runner = CliRunner()
    with patch(
        "pocketshell.sessions._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"
    ), patch(
        "pocketshell.sessions.subprocess.run",
        return_value=_fake_completed(returncode=5),
    ):
        result = runner.invoke(sessions_group, ["create", "work"])
    assert result.exit_code == 5


def test_sessions_help_lists_create_subcommand() -> None:
    runner = CliRunner()
    with patch("pocketshell.sessions.subprocess.run") as run, patch(
        "pocketshell.sessions._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"
    ):
        result = runner.invoke(sessions_group, ["--help"])
    assert result.exit_code == 0, result.output
    assert "create" in result.output
    run.assert_not_called()
