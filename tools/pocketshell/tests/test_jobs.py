"""Unit tests for `pocketshell jobs`.

The second-PR scope exercises:

- `pocketshell --help` lists the `jobs` subcommand.
- `pocketshell jobs --help` lists `list`, `show`, `trigger`, `daemon`.
- `pocketshell jobs list` (and `--session NAME`) forwards verbatim to
  `tmuxctl jobs list`.
- `pocketshell jobs show <id>` forwards verbatim.
- `pocketshell jobs trigger <id>` forwards verbatim.
- `pocketshell jobs daemon start` forwards to `tmuxctl jobs daemon`
  with optional `--poll-interval` / `--run-once`.
- `pocketshell jobs daemon status` returns 0/3 based on `pgrep`.
- `pocketshell jobs daemon stop` returns 0 for "no match" (idempotent)
  and 0 for "signalled at least one".
- Missing `tmuxctl` produces a friendly stderr message + exit 127.
- stdout/stderr/exit-code from the subprocess are proxied verbatim.

The tests stub `pocketshell.jobs._resolve_tmuxctl_binary` and
`subprocess.run` so they never invoke a real `tmuxctl` binary; the
contract under test is "pocketshell delegates correctly to whatever
tmuxctl exists on the host", not "the scheduler works".
"""

from __future__ import annotations

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


def test_jobs_daemon_stop_signals_running_process() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs.shutil.which", return_value="/fake/pkill"), patch(
        "pocketshell.jobs.subprocess.run",
        return_value=_fake_completed(stdout="", returncode=0),
    ) as run:
        result = runner.invoke(jobs_group, ["daemon", "stop"])
    assert result.exit_code == 0, result.output
    assert "stopped" in result.output.lower()
    invoked: Sequence[str] = run.call_args.args[0]
    # Same anchored pattern as `status`.
    assert invoked[0:3] == ["/fake/pkill", "-TERM", "-f"]
    assert "tmuxctl jobs daemon" in invoked[3]


def test_jobs_daemon_stop_is_idempotent_when_not_running() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs.shutil.which", return_value="/fake/pkill"), patch(
        "pocketshell.jobs.subprocess.run",
        # pkill exits 1 when no process matches; treat as no-op success.
        return_value=_fake_completed(stdout="", returncode=1),
    ):
        result = runner.invoke(jobs_group, ["daemon", "stop"])
    assert result.exit_code == 0, result.output
    assert "not running" in result.output.lower()


def test_jobs_daemon_stop_without_pkill_returns_127() -> None:
    runner = CliRunner()
    with patch("pocketshell.jobs.shutil.which", return_value=None), patch(
        "pocketshell.jobs.subprocess.run"
    ) as run:
        result = runner.invoke(jobs_group, ["daemon", "stop"])
    assert result.exit_code == 127
    assert "pkill" in result.output.lower()
    run.assert_not_called()


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
