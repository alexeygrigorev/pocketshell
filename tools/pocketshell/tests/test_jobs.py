"""Unit tests for `pocketshell jobs`.

The second-PR scope exercises:

- `pocketshell --help` lists the `jobs` subcommand.
- `pocketshell jobs --help` lists `list`, `show`, `trigger`, `add`,
  `edit`, `remove`, `daemon`.
- `pocketshell jobs list` (and `--session NAME`) forwards verbatim to
  `tmuxctl jobs list`.
- `pocketshell jobs show <id>` forwards verbatim.
- `pocketshell jobs trigger <id>` forwards verbatim.
- `pocketshell jobs add <session> --every <e> --message <m>
  [--start-now]` forwards verbatim to `tmuxctl jobs add` (matches the
  command the Android app's `PocketshellJobsRemoteSource.add` emits).
- `pocketshell jobs edit <id> [--session] [--every] [--message]
  [--enable | --disable]` forwards verbatim to `tmuxctl jobs edit`;
  `--enable` + `--disable` together is rejected locally.
- `pocketshell jobs remove <id>` forwards verbatim to
  `tmuxctl jobs remove`.
- `pocketshell jobs daemon start` forwards to `tmuxctl jobs daemon`
  with optional `--poll-interval` / `--run-once`.
- `pocketshell jobs daemon status` returns 0/3 based on `pgrep`.
- `pocketshell jobs daemon stop` resolves the daemon PID(s) via
  `pgrep -f` + argv re-validation, then SIGTERMs exactly those PIDs via
  `os.kill` (never a blunt `pkill -f`). Returns 0 for "no match"
  (idempotent) and 0 for "signalled at least one".
- Missing `tmuxctl` produces a friendly stderr message + exit 127.
- stdout/stderr/exit-code from the subprocess are proxied verbatim.

The tests stub `pocketshell.jobs._resolve_tmuxctl_binary` and
`subprocess.run` so they never invoke a real `tmuxctl` binary; the
contract under test is "pocketshell delegates correctly to whatever
tmuxctl exists on the host", not "the scheduler works".
"""

from __future__ import annotations

import os
import signal
import subprocess
from typing import Sequence
from unittest.mock import patch

from click.testing import CliRunner

from pocketshell.cli import cli
from pocketshell.jobs import jobs_group


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


def _fake_jobs_table() -> str:
    """Realistic shape of `tmuxctl jobs list` output.

    Kept here (not in the source) so the test can pin the contract
    against a captured fixture without coupling the wrapper to the
    output shape.
    """
    return (
        "ID  ENABLED  SESSION  EVERY  DELAY  SOURCE  NEXT RUN             DETAIL\n"
        "1   yes      work     15m    200    inline  2026-05-27 18:00:00  poke claude\n"
    )


# ----- top-level help wiring -----------------------------------------


def test_top_level_help_lists_jobs_subcommand() -> None:
    runner = CliRunner()
    result = runner.invoke(cli, ["--help"])
    assert result.exit_code == 0, result.output
    assert "jobs" in result.output


def test_jobs_help_lists_subcommands() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs.subprocess.run") as run, patch(
        "pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"
    ):
        result = runner.invoke(jobs_group, ["--help"])
    assert result.exit_code == 0, result.output
    # Each leaf-level subcommand listed in the brief must appear.
    assert "list" in result.output
    assert "show" in result.output
    assert "trigger" in result.output
    assert "add" in result.output
    assert "edit" in result.output
    assert "remove" in result.output
    assert "daemon" in result.output
    run.assert_not_called()


def test_jobs_daemon_help_lists_lifecycle_verbs() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs.subprocess.run") as run, patch(
        "pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"
    ):
        result = runner.invoke(jobs_group, ["daemon", "--help"])
    assert result.exit_code == 0, result.output
    assert "start" in result.output
    assert "stop" in result.output
    assert "status" in result.output
    run.assert_not_called()


# ----- jobs list / show / trigger ------------------------------------


def test_jobs_list_forwards_to_tmuxctl_and_proxies_stdout() -> None:
    payload = _fake_jobs_table()
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run",
        return_value=_fake_completed(stdout=payload),
    ) as run:
        result = runner.invoke(jobs_group, ["list"])
    assert result.exit_code == 0, result.output
    # Byte-identical pass-through: the Android-side `TmuxctlJobsParser`
    # already parses this exact shape and must keep working when the
    # app eventually routes through `pocketshell jobs list`.
    assert result.output == payload
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/tmuxctl", "jobs", "list"]


def test_jobs_list_uses_daemon_when_available() -> None:
    payload = _fake_jobs_table()
    runner = CliRunner()
    with patch(
        "pocketshell.jobs._try_daemon_jobs_call",
        return_value={"stdout": payload, "stderr": "", "returncode": 0},
    ) as daemon_call, patch("pocketshell.jobs.subprocess.run") as run:
        result = runner.invoke(jobs_group, ["list", "--session", "work"])
    assert result.exit_code == 0, result.output
    assert result.output == payload
    daemon_call.assert_called_once_with(
        "jobs.list",
        {"extra_args": [], "session": "work"},
    )
    run.assert_not_called()


def test_jobs_list_falls_back_when_daemon_misses() -> None:
    payload = _fake_jobs_table()
    runner = CliRunner()
    with patch("pocketshell.jobs._try_daemon_jobs_call", return_value=None), patch(
        "pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"
    ), patch(
        "pocketshell.jobs.subprocess.run",
        return_value=_fake_completed(stdout=payload),
    ) as run:
        result = runner.invoke(jobs_group, ["list"])
    assert result.exit_code == 0, result.output
    assert result.output == payload
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/tmuxctl", "jobs", "list"]


def test_jobs_list_forwards_unknown_options_verbatim() -> None:
    """The wrapper does NOT enumerate every flag `tmuxctl jobs list`
    grows. The brief calls out a future `--json` flag in particular —
    this test pins the contract that `pocketshell jobs list --json`
    forwards `--json` to the underlying CLI without rewriting it.
    """
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run",
        return_value=_fake_completed(stdout='{"jobs": []}\n'),
    ) as run:
        result = runner.invoke(jobs_group, ["list", "--json"])
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/tmuxctl", "jobs", "list", "--json"]


def test_jobs_list_forwards_session_option() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run",
        return_value=_fake_completed(stdout=_fake_jobs_table()),
    ) as run:
        result = runner.invoke(jobs_group, ["list", "--session", "work"])
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/tmuxctl", "jobs", "list", "--session", "work"]


def test_jobs_show_forwards_id() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run",
        return_value=_fake_completed(stdout="ID:       7\nSession:  work\n"),
    ) as run:
        result = runner.invoke(jobs_group, ["show", "7"])
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/tmuxctl", "jobs", "show", "7"]
    assert "Session:  work" in result.output


def test_jobs_show_rejects_non_integer_id() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run"
    ) as run:
        result = runner.invoke(jobs_group, ["show", "not-an-int"])
    # Click validates `job_id: int` before we ever reach the
    # subprocess, so the wrapper must not invoke tmuxctl in this case.
    assert result.exit_code != 0
    run.assert_not_called()


def test_jobs_trigger_forwards_id() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run",
        return_value=_fake_completed(stdout="Triggered job 7\n"),
    ) as run:
        result = runner.invoke(jobs_group, ["trigger", "7"])
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/tmuxctl", "jobs", "trigger", "7"]


def test_jobs_mutation_uses_daemon_when_available() -> None:
    runner = CliRunner()
    with patch(
        "pocketshell.jobs._try_daemon_jobs_call",
        return_value={"stdout": "Created job 7\n", "stderr": "", "returncode": 0},
    ) as daemon_call, patch("pocketshell.jobs.subprocess.run") as run:
        result = runner.invoke(
            jobs_group,
            ["add", "work", "--every", "15m", "--message", "poke claude"],
        )
    assert result.exit_code == 0, result.output
    assert result.output == "Created job 7\n"
    daemon_call.assert_called_once_with(
        "jobs.add",
        {
            "session_name": "work",
            "every": "15m",
            "message": "poke claude",
            "start_now": False,
            "extra_args": [],
        },
    )
    run.assert_not_called()


def test_jobs_trigger_proxies_unknown_subcommand_failure() -> None:
    """If `tmuxctl` has not yet implemented `jobs trigger`, the wrapper
    must surface the failure unchanged rather than silently swallowing
    it. The brief asks for parity with whatever `tmuxctl` exposes, not
    a reimplementation.
    """
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run",
        return_value=_fake_completed(stderr="No such command 'trigger'.\n", returncode=2),
    ):
        result = runner.invoke(jobs_group, ["trigger", "7"])
    assert result.exit_code == 2
    assert "trigger" in result.output.lower()


# ----- jobs add ------------------------------------------------------


def test_jobs_add_forwards_session_every_and_message() -> None:
    """Pins the contract the Android app's `PocketshellJobsRemoteSource.add`
    emits: `pocketshell jobs add <session> --every <e> --message <m>`.
    The session is a positional argument; `--every` and `--message`
    map one-to-one onto `tmuxctl jobs add`.
    """
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run",
        return_value=_fake_completed(stdout="Created job 7\n"),
    ) as run:
        result = runner.invoke(
            jobs_group,
            ["add", "work", "--every", "15m", "--message", "poke claude"],
        )
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == [
        "/fake/tmuxctl",
        "jobs",
        "add",
        "work",
        "--every",
        "15m",
        "--message",
        "poke claude",
    ]


def test_jobs_add_forwards_start_now_flag() -> None:
    """`--start-now` is the optional flag the app appends when the user
    asks to run the job on the next daemon poll.
    """
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run",
        return_value=_fake_completed(stdout="Created job 8\n"),
    ) as run:
        result = runner.invoke(
            jobs_group,
            ["add", "work", "--every", "2h", "--message", "status", "--start-now"],
        )
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == [
        "/fake/tmuxctl",
        "jobs",
        "add",
        "work",
        "--every",
        "2h",
        "--message",
        "status",
        "--start-now",
    ]


def test_jobs_add_requires_every() -> None:
    """`--every` is required (matching `tmuxctl jobs add`), so click
    rejects the invocation before reaching the subprocess.
    """
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run"
    ) as run:
        result = runner.invoke(jobs_group, ["add", "work", "--message", "hi"])
    assert result.exit_code != 0
    run.assert_not_called()


def test_jobs_add_forwards_unknown_options_verbatim() -> None:
    """Flags `tmuxctl jobs add` accepts but the wrapper does not enumerate
    (e.g. `--enter-delay-ms`, `--no-enter`, `--message-file`) forward
    unchanged so upstream parity work is never blocked here.
    """
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run",
        return_value=_fake_completed(stdout="Created job 9\n"),
    ) as run:
        result = runner.invoke(
            jobs_group,
            [
                "add",
                "work",
                "--every",
                "30m",
                "--message",
                "deploy",
                "--enter-delay-ms",
                "500",
                "--no-enter",
            ],
        )
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == [
        "/fake/tmuxctl",
        "jobs",
        "add",
        "work",
        "--every",
        "30m",
        "--message",
        "deploy",
        "--enter-delay-ms",
        "500",
        "--no-enter",
    ]


def test_jobs_add_proxies_nonzero_exit_from_tmuxctl() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run",
        return_value=_fake_completed(stderr="tmuxctl: no such session 'work'\n", returncode=4),
    ):
        result = runner.invoke(
            jobs_group,
            ["add", "work", "--every", "15m", "--message", "hi"],
        )
    assert result.exit_code == 4
    assert "no such session" in result.output


def test_jobs_add_returns_127_when_tmuxctl_missing() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value=None), patch(
        "pocketshell.jobs.subprocess.run"
    ) as run:
        result = runner.invoke(
            jobs_group,
            ["add", "work", "--every", "15m", "--message", "hi"],
            catch_exceptions=False,
        )
    assert result.exit_code == 127
    assert "tmuxctl" in result.output.lower()
    run.assert_not_called()


# ----- jobs edit -----------------------------------------------------


def test_jobs_edit_forwards_id_only() -> None:
    """An edit with only the id and no fields still forwards the id; the
    Android app may emit this when nothing changed but a save was issued.
    """
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run",
        return_value=_fake_completed(stdout="Updated job 7\n"),
    ) as run:
        result = runner.invoke(jobs_group, ["edit", "7"])
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/tmuxctl", "jobs", "edit", "7"]


def test_jobs_edit_forwards_session_every_and_message() -> None:
    """Pins the contract the app's `PocketshellJobsRemoteSource.edit`
    emits for field updates: `--session`, `--every`, `--message` each map
    one-to-one onto `tmuxctl jobs edit`.
    """
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run",
        return_value=_fake_completed(stdout="Updated job 7\n"),
    ) as run:
        result = runner.invoke(
            jobs_group,
            [
                "edit",
                "7",
                "--session",
                "ops",
                "--every",
                "45m",
                "--message",
                "rotate logs",
            ],
        )
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == [
        "/fake/tmuxctl",
        "jobs",
        "edit",
        "7",
        "--session",
        "ops",
        "--every",
        "45m",
        "--message",
        "rotate logs",
    ]


def test_jobs_edit_forwards_enable_flag() -> None:
    """The app sends `--enable` when the user toggles a disabled job on."""
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run",
        return_value=_fake_completed(stdout="Updated job 7\n"),
    ) as run:
        result = runner.invoke(jobs_group, ["edit", "7", "--enable"])
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/tmuxctl", "jobs", "edit", "7", "--enable"]


def test_jobs_edit_forwards_disable_flag() -> None:
    """The app sends `--disable` when the user toggles an enabled job off."""
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run",
        return_value=_fake_completed(stdout="Updated job 7\n"),
    ) as run:
        result = runner.invoke(jobs_group, ["edit", "7", "--disable"])
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/tmuxctl", "jobs", "edit", "7", "--disable"]


def test_jobs_edit_rejects_enable_and_disable_together() -> None:
    """`--enable` and `--disable` are contradictory; the wrapper rejects the
    pair locally before invoking `tmuxctl`. The app never sends both
    (its `enabled: Boolean?` maps to exactly one flag), so this guards
    against a malformed manual invocation.
    """
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run"
    ) as run:
        result = runner.invoke(jobs_group, ["edit", "7", "--enable", "--disable"])
    assert result.exit_code != 0
    assert "mutually exclusive" in result.output.lower()
    run.assert_not_called()


def test_jobs_edit_rejects_non_integer_id() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run"
    ) as run:
        result = runner.invoke(jobs_group, ["edit", "not-an-int"])
    assert result.exit_code != 0
    run.assert_not_called()


def test_jobs_edit_proxies_nonzero_exit_from_tmuxctl() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run",
        return_value=_fake_completed(stderr="tmuxctl: no job with id 99\n", returncode=4),
    ):
        result = runner.invoke(jobs_group, ["edit", "99", "--every", "1h"])
    assert result.exit_code == 4
    assert "no job with id 99" in result.output


def test_jobs_edit_returns_127_when_tmuxctl_missing() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value=None), patch(
        "pocketshell.jobs.subprocess.run"
    ) as run:
        result = runner.invoke(
            jobs_group, ["edit", "7", "--enable"], catch_exceptions=False
        )
    assert result.exit_code == 127
    assert "tmuxctl" in result.output.lower()
    run.assert_not_called()


# ----- jobs remove ---------------------------------------------------


def test_jobs_remove_forwards_id() -> None:
    """Pins the contract the app's `PocketshellJobsRemoteSource.remove`
    emits: `pocketshell jobs remove <id>`.
    """
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run",
        return_value=_fake_completed(stdout="Removed job 7\n"),
    ) as run:
        result = runner.invoke(jobs_group, ["remove", "7"])
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/tmuxctl", "jobs", "remove", "7"]


def test_jobs_remove_rejects_non_integer_id() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run"
    ) as run:
        result = runner.invoke(jobs_group, ["remove", "not-an-int"])
    assert result.exit_code != 0
    run.assert_not_called()


def test_jobs_remove_proxies_nonzero_exit_from_tmuxctl() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run",
        return_value=_fake_completed(stderr="tmuxctl: no job with id 99\n", returncode=4),
    ):
        result = runner.invoke(jobs_group, ["remove", "99"])
    assert result.exit_code == 4
    assert "no job with id 99" in result.output


def test_jobs_remove_returns_127_when_tmuxctl_missing() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value=None), patch(
        "pocketshell.jobs.subprocess.run"
    ) as run:
        result = runner.invoke(
            jobs_group, ["remove", "7"], catch_exceptions=False
        )
    assert result.exit_code == 127
    assert "tmuxctl" in result.output.lower()
    run.assert_not_called()


# ----- daemon start --------------------------------------------------


def test_jobs_daemon_start_invokes_foreground_loop() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run",
        return_value=_fake_completed(stdout=""),
    ) as run:
        result = runner.invoke(jobs_group, ["daemon", "start"])
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == ["/fake/tmuxctl", "jobs", "daemon"]


def test_jobs_daemon_start_forwards_poll_interval_and_run_once() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run",
        return_value=_fake_completed(stdout="Processed 0 due job(s)\n"),
    ) as run:
        result = runner.invoke(
            jobs_group,
            ["daemon", "start", "--poll-interval", "5", "--run-once"],
        )
    assert result.exit_code == 0, result.output
    invoked: Sequence[str] = run.call_args.args[0]
    assert invoked == [
        "/fake/tmuxctl",
        "jobs",
        "daemon",
        "--poll-interval",
        "5",
        "--run-once",
    ]


# ----- daemon status -------------------------------------------------


def test_jobs_daemon_status_running_reports_zero() -> None:
    runner = CliRunner()
    # _is_daemon_running shells out to pgrep, so stub both `shutil.which`
    # (to return a non-None pgrep path) and `subprocess.run`.
    with patch("pocketshell.jobs.shutil.which", return_value="/fake/pgrep"), patch(
        "pocketshell.jobs.subprocess.run",
        return_value=_fake_completed(stdout="1234\n", returncode=0),
    ) as run:
        result = runner.invoke(jobs_group, ["daemon", "status"])
    assert result.exit_code == 0, result.output
    assert "running" in result.output.lower()
    invoked: Sequence[str] = run.call_args.args[0]
    # Pattern is anchored so an interactive shell whose argv merely
    # contains the substring "tmuxctl jobs daemon" is not matched; we
    # only want a real `…/tmuxctl jobs daemon …` process.
    assert invoked[0:2] == ["/fake/pgrep", "-f"]
    assert "tmuxctl jobs daemon" in invoked[2]
    assert invoked[2].startswith("(^|/)") or invoked[2].startswith("(")


def test_jobs_daemon_status_uses_daemon_when_available() -> None:
    runner = CliRunner()
    with patch(
        "pocketshell.jobs._try_daemon_jobs_call",
        return_value={"stdout": "running\n", "stderr": "", "returncode": 0},
    ) as daemon_call, patch("pocketshell.jobs.subprocess.run") as run:
        result = runner.invoke(jobs_group, ["daemon", "status"])
    assert result.exit_code == 0, result.output
    assert result.output == "running\n"
    daemon_call.assert_called_once_with("jobs.status", {})
    run.assert_not_called()


def test_jobs_daemon_status_not_running_reports_three() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs.shutil.which", return_value="/fake/pgrep"), patch(
        "pocketshell.jobs.subprocess.run",
        # pgrep exits 1 when no process matches.
        return_value=_fake_completed(stdout="", returncode=1),
    ):
        result = runner.invoke(jobs_group, ["daemon", "status"])
    # `systemctl is-active` returns 3 for inactive; mirror that so
    # shell-script consumers can `&& echo up || echo down` consistently.
    assert result.exit_code == 3
    assert "not running" in result.output.lower()


def test_jobs_daemon_status_without_pgrep_returns_not_running() -> None:
    """If `pgrep` is not installed we cannot prove the daemon is running,
    so the safest answer is "not running" with the inactive exit code.
    """
    runner = CliRunner()
    with patch("pocketshell.jobs.shutil.which", return_value=None), patch(
        "pocketshell.jobs.subprocess.run"
    ) as run:
        result = runner.invoke(jobs_group, ["daemon", "status"])
    assert result.exit_code == 3
    assert "not running" in result.output.lower()
    run.assert_not_called()


# ----- daemon stop ---------------------------------------------------


def test_jobs_daemon_stop_signals_resolved_pids_only() -> None:
    """`stop` SIGTERMs exactly the resolved daemon PIDs via `os.kill`.

    It must NOT shell out to a blunt `pkill -TERM -f` (the old broad
    behaviour that signalled every matching command line on the host).
    """
    runner = CliRunner()
    killed: list[tuple[int, int]] = []
    with patch("pocketshell.jobs.shutil.which", return_value="/fake/pgrep"), patch(
        "pocketshell.jobs._resolve_daemon_pids", return_value=[4321]
    ), patch(
        "pocketshell.jobs.os.kill",
        side_effect=lambda pid, sig: killed.append((pid, sig)),
    ), patch(
        "pocketshell.jobs.subprocess.run"
    ) as run:
        result = runner.invoke(jobs_group, ["daemon", "stop"])
    assert result.exit_code == 0, result.output
    assert "stopped" in result.output.lower()
    # Exactly the resolved PID, with SIGTERM — no pkill, no broad match.
    assert killed == [(4321, signal.SIGTERM)]
    # No `pkill -TERM -f <pattern>` subprocess was ever launched.
    for call in run.call_args_list:
        argv = call.args[0]
        assert "pkill" not in (argv[0] if argv else "")


def test_jobs_daemon_stop_is_idempotent_when_not_running() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs.shutil.which", return_value="/fake/pgrep"), patch(
        "pocketshell.jobs._resolve_daemon_pids", return_value=[]
    ), patch("pocketshell.jobs.os.kill") as kill:
        result = runner.invoke(jobs_group, ["daemon", "stop"])
    assert result.exit_code == 0, result.output
    assert "not running" in result.output.lower()
    kill.assert_not_called()


def test_jobs_daemon_stop_treats_raced_exit_as_not_running() -> None:
    """A PID that vanished between resolve and kill is not an error."""
    runner = CliRunner()
    with patch("pocketshell.jobs.shutil.which", return_value="/fake/pgrep"), patch(
        "pocketshell.jobs._resolve_daemon_pids", return_value=[999]
    ), patch("pocketshell.jobs.os.kill", side_effect=ProcessLookupError):
        result = runner.invoke(jobs_group, ["daemon", "stop"])
    assert result.exit_code == 0, result.output
    assert "not running" in result.output.lower()


def test_jobs_daemon_stop_without_pgrep_returns_127() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs.shutil.which", return_value=None), patch(
        "pocketshell.jobs.os.kill"
    ) as kill:
        result = runner.invoke(jobs_group, ["daemon", "stop"])
    assert result.exit_code == 127
    assert "pgrep" in result.output.lower()
    kill.assert_not_called()


# ----- daemon stop: PID resolution + argv validation (narrowing) -----


def test_argv_is_daemon_accepts_real_invocations() -> None:
    from pocketshell.jobs import _argv_is_daemon

    assert _argv_is_daemon(["/home/u/.local/bin/tmuxctl", "jobs", "daemon"])
    assert _argv_is_daemon(["tmuxctl", "jobs", "daemon", "--poll-interval", "5"])
    # A `python …/tmuxctl jobs daemon` shim still matches on the tmuxctl token.
    assert _argv_is_daemon(["python3", "/usr/bin/tmuxctl", "jobs", "daemon"])


def test_argv_is_daemon_rejects_coincidental_command_lines() -> None:
    """The whole point of the narrowing: a command line that merely
    *contains* the substring `tmuxctl jobs daemon` (e.g. an editor
    opening a file with that name) must NOT be classed as the daemon,
    even though `pgrep -f`/`pkill -f` would regex-match it.
    """
    from pocketshell.jobs import _argv_is_daemon

    # Editor opening a note whose filename contains the phrase.
    assert not _argv_is_daemon(["vim", "notes/tmuxctl jobs daemon.md"])
    # Another shell literally typing the command as one argv element.
    assert not _argv_is_daemon(["bash", "-c", "tmuxctl jobs daemon"])
    # A different tmuxctl subcommand.
    assert not _argv_is_daemon(["tmuxctl", "jobs", "list"])
    # `tmuxctl jobs daemon-something-else` style verb.
    assert not _argv_is_daemon(["tmuxctl", "jobs", "daemon-foo"])
    assert not _argv_is_daemon([])
    assert not _argv_is_daemon(["tmuxctl"])


def test_resolve_daemon_pids_drops_coincidental_match_and_self() -> None:
    """`_resolve_daemon_pids` re-validates each `pgrep` candidate's real
    argv and excludes our own PID, so a coincidental command-line match
    (which `pgrep -f`/`pkill -f` would have signalled) is dropped.
    """
    own = os.getpid()

    def fake_argv(pid: int):
        return {
            111: ["/usr/bin/tmuxctl", "jobs", "daemon"],  # genuine daemon
            222: ["vim", "tmuxctl jobs daemon.md"],  # coincidental match
            own: ["python3", "/x/tmuxctl", "jobs", "daemon"],  # ourselves
        }.get(pid)

    with patch("pocketshell.jobs.shutil.which", return_value="/fake/pgrep"), patch(
        "pocketshell.jobs.subprocess.run",
        return_value=_fake_completed(stdout=f"111\n222\n{own}\n", returncode=0),
    ), patch("pocketshell.jobs._read_proc_argv", side_effect=fake_argv):
        from pocketshell.jobs import _resolve_daemon_pids

        pids = _resolve_daemon_pids()
    # Only the genuine daemon PID survives: coincidental 222 and our own
    # PID are both excluded.
    assert pids == [111]


def test_resolve_daemon_pids_empty_without_pgrep() -> None:
    with patch("pocketshell.jobs.shutil.which", return_value=None):
        from pocketshell.jobs import _resolve_daemon_pids

        assert _resolve_daemon_pids() == []


# ----- missing-binary handling ---------------------------------------


def test_jobs_list_returns_127_when_tmuxctl_missing() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value=None), patch(
        "pocketshell.jobs.subprocess.run"
    ) as run:
        result = runner.invoke(jobs_group, ["list"], catch_exceptions=False)
    # 127 is the POSIX exit code for "command not found"; matches
    # `RecurringJobsCommandResult.ToolMissing` on the Kotlin side.
    assert result.exit_code == 127
    assert "tmuxctl" in result.output.lower()
    run.assert_not_called()


def test_jobs_show_returns_127_when_tmuxctl_missing() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value=None), patch(
        "pocketshell.jobs.subprocess.run"
    ) as run:
        result = runner.invoke(jobs_group, ["show", "1"], catch_exceptions=False)
    assert result.exit_code == 127
    assert "tmuxctl" in result.output.lower()
    run.assert_not_called()


def test_jobs_trigger_returns_127_when_tmuxctl_missing() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value=None), patch(
        "pocketshell.jobs.subprocess.run"
    ) as run:
        result = runner.invoke(jobs_group, ["trigger", "1"], catch_exceptions=False)
    assert result.exit_code == 127
    assert "tmuxctl" in result.output.lower()
    run.assert_not_called()


def test_jobs_daemon_start_returns_127_when_tmuxctl_missing() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value=None), patch(
        "pocketshell.jobs.subprocess.run"
    ) as run:
        result = runner.invoke(jobs_group, ["daemon", "start"], catch_exceptions=False)
    assert result.exit_code == 127
    assert "tmuxctl" in result.output.lower()
    run.assert_not_called()


# ----- non-zero exit propagation -------------------------------------


def test_jobs_list_proxies_nonzero_exit_from_tmuxctl() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs._resolve_tmuxctl_binary", return_value="/fake/tmuxctl"), patch(
        "pocketshell.jobs.subprocess.run",
        return_value=_fake_completed(stderr="tmuxctl: db locked\n", returncode=5),
    ):
        result = runner.invoke(jobs_group, ["list"])
    assert result.exit_code == 5
    # stderr from the subprocess must reach the user (otherwise
    # debugging a failing scheduler would be opaque).
    assert "db locked" in result.output
