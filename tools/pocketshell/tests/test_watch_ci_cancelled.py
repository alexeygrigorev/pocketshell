"""Regression tests for the watch-ci.py fabricated-infra-failure defects (#1650).

An on-call ran `scripts/watch-ci.py` against `main` run 29520839502 and got a
confident, specific, and ENTIRELY FICTIONAL report:

    FAILED — a required check failed
    signature: echo "::error title=sdkmanager still broken after repair::... (issue #771).
               Treat as EMULATOR INFRA UNAVAILABLE, not a test failure."

Neither part happened. The run was merely SUPERSEDED — `#1648` landed and
`main`'s push concurrency group cancelled the older in-flight run (the
documented, intended design; see process.md "the `main` push concurrency group
cancels older in-flight runs when newer merges land"). `Unit tests`, `Python
utility tests (pocketshell)` and `Integration tests (Docker)` were all green.

Two independent defects, reproduced separately below:

  D1. `cancelled` was in FAILING_CONCLUSIONS, so a routine concurrency-cancel
      became a guaranteed false `FAILED` on the exact path the on-call is told
      to watch (process.md "Never babysit CI").
  D2. The signature grep matched a CONDITIONAL `echo` in the workflow's script
      body — captured by GitHub's command echo (the `ESC[36;1m` prefix) — that
      NEVER FIRED. It could not tell "this error occurred" from "this string
      exists in the script text", so it attached a wrong diagnosis AND a wrong
      issue number.

The load-bearing NEGATIVE cases (G6) matter more than the false alarms: a fix
that makes the watcher blind to real failures is far worse than the bug, because
the on-call would stop noticing red CI. `test_genuine_*` below assert that a
real infra failure is still detected WITH its real signature, and a real test
failure is still reported as a real failure — never softened to
cancelled/superseded/unknown.

Class coverage (G2) — not just the one reported instance:

  | case                              | expected result |
  |-----------------------------------|-----------------|
  | superseded by concurrency         | superseded (4)  |
  | user/API cancelled, no newer run  | no_verdict (5)  |
  | genuine infra failure             | failed (1) + real signature, likely_infra |
  | genuine test failure              | failed (1) + real signature |
  | genuine failure THEN cancelled    | failed (1)      |
  | timeout / hang                    | hang (2)        |
  | no verdict available (probe down) | no_verdict (5)  |

Fixtures are captured from the REAL run/log (`gh run view --log`), not
hand-idealised, per the #847 happy-fixture-masks-reality lesson.
"""

from __future__ import annotations

import pytest

from tests.test_watch_ci import (  # the shared offline harness
    REQUIRED,
    FakeGh,
    _job,
    _make_watcher,
    wci,
)

# ── Real captured fixtures (run 29520839502, main @ ab3e0caf) ────────────────

# The EXACT job set from `gh run view 29520839502 --json status,conclusion,jobs`.
# The three cheap required checks passed; only the emulator shards + the
# aggregate were cancelled when #1648 (136fe702) superseded the run at 18:50:18.
def _superseded_main_jobs() -> list[dict]:
    return [
        _job("Python utility tests (pocketshell)", "completed", "success", 87697273282),
        _job("Integration tests (Docker)", "completed", "success", 87697273298),
        _job("Unit tests", "completed", "success", 87697273300),
        _job(
            "Emulator journey subset (load-bearing, Docker agents) (1)",
            "completed",
            "cancelled",
            87701705006,
        ),
        _job(
            "Emulator journey subset (load-bearing, Docker agents) (0)",
            "completed",
            "cancelled",
            87701705021,
        ),
        _job(
            "Emulator journey subset (load-bearing, Docker agents) (2)",
            "completed",
            "cancelled",
            87701705035,
        ),
        _job(
            "Emulator journey aggregate verdict (#1458)",
            "completed",
            "cancelled",
            87713160666,
        ),
    ]


# The EXACT line `gh run view --job 87701705006 --log` emitted at line 529 that
# the watcher turned into its fabricated signature. The `ESC[36;1m ... ESC[0m`
# wrapper is GitHub's COMMAND ECHO: it is the `run:` step's script body being
# printed, NOT output from an executed command. The `if` guarding this echo
# never fired — the sdkmanager was fine.
_REAL_ECHOED_SDKMANAGER_LINE = (
    "Emulator journey subset (load-bearing, Docker agents) (1)\t"
    "Repair Android cmdline-tools + accept licenses (issue\t"
    "2026-07-16T18:03:10.7886203Z \x1b[36;1m  "
    'echo "::error title=sdkmanager still broken after repair::The '
    "freshly-installed cmdline-tools sdkmanager still failed to run (issue "
    '#771). Treat as EMULATOR INFRA UNAVAILABLE, not a test failure."\x1b[0m'
)

# Real surrounding context from the same captured log.
_REAL_CANCELLED_JOB_LOG = "\n".join(
    [
        "Emulator journey subset (load-bearing, Docker agents) (1)\tSet up job\t"
        "2026-07-16T18:02:55.1000000Z Job is about to start running on the runner",
        _REAL_ECHOED_SDKMANAGER_LINE,
        "Emulator journey subset (load-bearing, Docker agents) (1)\t"
        "Repair Android cmdline-tools + accept licenses (issue\t"
        "2026-07-16T18:03:12.0000000Z sdkmanager ok",
        "Emulator journey subset (load-bearing, Docker agents) (1)\t"
        "Retry journey subset on a fresh cold-booted emulator\t"
        "2026-07-16T18:50:19.0573036Z ##[error]The operation was canceled.",
    ]
)


def _newer_run_on_main(newer: bool = True) -> list[dict]:
    """`gh run list --branch main` output; newest-first, as gh returns it."""
    runs = []
    if newer:
        runs.append(
            {
                "databaseId": 29521111111,
                "workflowName": "Tests",
                "headBranch": "main",
                "status": "in_progress",
                "createdAt": "2026-07-16T18:50:18Z",
            }
        )
    runs.append(
        {
            "databaseId": 29520839502,
            "workflowName": "Tests",
            "headBranch": "main",
            "status": "completed",
            "createdAt": "2026-07-16T18:02:40Z",
        }
    )
    return runs


def _superseded_run_state() -> dict:
    return {
        "status": "completed",
        "conclusion": "cancelled",
        "databaseId": 29520839502,
        "headBranch": "main",
        "workflowName": "Tests",
        "createdAt": "2026-07-16T18:02:40Z",
        "jobs": _superseded_main_jobs(),
    }


# ── D1: superseded-by-concurrency is NOT a failure ───────────────────────────


def test_superseded_by_concurrency_is_not_reported_as_failed():
    """THE reported instance. Run 29520839502 was superseded, not broken.

    Before the fix this returned RESULT_FAILED/exit 1 — a standing false-alarm
    generator, because `main`'s concurrency-cancel is routine and by design.
    """
    gh = FakeGh()
    gh.queue_run_state(**_superseded_run_state())
    gh.run_list_json = _newer_run_on_main(newer=True)
    gh.log_text = _REAL_CANCELLED_JOB_LOG
    watcher, _ = _make_watcher(gh)

    outcome = watcher.watch(run_id="29520839502")

    assert outcome.result != wci.RESULT_FAILED, (
        "a concurrency-superseded run must NOT be reported as a failure "
        f"(got {outcome.result}: {outcome.reason})"
    )
    assert outcome.result == wci.RESULT_SUPERSEDED
    assert outcome.exit_code == 4
    assert "supersed" in outcome.reason.lower()
    # The on-call's action is to re-watch the newest head — surface it.
    assert "29521111111" in outcome.reason


def test_superseded_run_never_fabricates_a_signature():
    """D1+D2 together: the fictional story is signature-free.

    A superseded run has no failure, so there is nothing to diagnose. Attaching
    ANY signature here is fabrication (G5: an infra claim needs a captured
    signature AND a clean re-run).
    """
    gh = FakeGh()
    gh.queue_run_state(**_superseded_run_state())
    gh.run_list_json = _newer_run_on_main(newer=True)
    gh.log_text = _REAL_CANCELLED_JOB_LOG
    watcher, _ = _make_watcher(gh)

    outcome = watcher.watch(run_id="29520839502")

    assert outcome.signature is None
    assert outcome.likely_infra is False
    assert "771" not in wci.render_human_summary(outcome), (
        "must never surface a guessed issue number for a run that did not fail"
    )


def test_cancelled_without_a_newer_run_is_no_verdict_not_failed():
    """User/API cancelled: no newer run on the branch, so it was not superseded.

    We STILL cannot claim a failure — a cancelled run produced no verdict. The
    watcher must say exactly that rather than invent one.
    """
    gh = FakeGh()
    gh.queue_run_state(**_superseded_run_state())
    gh.run_list_json = _newer_run_on_main(newer=False)  # only ourselves
    gh.log_text = _REAL_CANCELLED_JOB_LOG
    watcher, _ = _make_watcher(gh)

    outcome = watcher.watch(run_id="29520839502")

    assert outcome.result == wci.RESULT_NO_VERDICT
    assert outcome.exit_code == 5
    assert outcome.signature is None


def test_cancelled_with_unreachable_probe_is_no_verdict_not_failed():
    """The supersede probe itself failing must not resurrect the false FAILED.

    If we cannot tell WHY it was cancelled, "no verdict" is the honest answer.
    """
    gh = FakeGh()
    gh.queue_run_state(**_superseded_run_state())
    gh.run_list_json = None  # `gh run list` errors out
    gh.log_text = _REAL_CANCELLED_JOB_LOG
    watcher, _ = _make_watcher(gh)

    outcome = watcher.watch(run_id="29520839502")

    assert outcome.result == wci.RESULT_NO_VERDICT
    assert outcome.exit_code == 5
    assert outcome.signature is None


# ── D2: a signature must come from output that ACTUALLY EXECUTED ─────────────


def test_echoed_conditional_script_text_is_not_a_signature():
    """The exact fabrication vector, at the unit level.

    `extract_signature` used to return this echoed `run:` body verbatim, because
    the loose pattern `(Install|installing).*failed` happened to match
    "freshly-installed ... still failed to run" inside the ECHOED script text.
    A conditional echo that never fired is not evidence of anything.
    """
    sig = wci.extract_signature(_REAL_CANCELLED_JOB_LOG)
    if sig is not None:
        assert "sdkmanager" not in sig, (
            "matched a conditional echo that never executed — the #1650 "
            f"fabrication: {sig!r}"
        )
        assert "#771" not in sig
        assert "echo" not in sig


def test_command_echo_lines_are_ignored_even_when_they_look_like_errors():
    """Class coverage: ANY echoed script body, not just the sdkmanager one."""
    log = "\n".join(
        [
            "j\tstep\t2026-07-16T18:03:10.0000000Z \x1b[36;1mif ! foo; then\x1b[0m",
            "j\tstep\t2026-07-16T18:03:10.0000000Z \x1b[36;1m  "
            'echo "error: everything is on fire"\x1b[0m',
            "j\tstep\t2026-07-16T18:03:10.0000000Z \x1b[36;1mfi\x1b[0m",
            "j\tstep\t2026-07-16T18:03:11.0000000Z all good",
        ]
    )
    assert wci.extract_signature(log) is None


def test_echoed_annotation_is_ignored_even_without_the_ansi_marker():
    """Defence in depth: the vector must stay closed if ANSI is ever stripped.

    A real annotation is emitted by the runner as `##[error]...`; it is never the
    literal text `echo "::error`. So an echoed annotation is script text even
    when the `ESC[36;1m` command-echo marker is absent.
    """
    log = (
        "j\tRepair Android cmdline-tools\t2026-07-16T18:03:10.0000000Z   "
        'echo "::error title=sdkmanager still broken after repair::The '
        "freshly-installed cmdline-tools sdkmanager still failed to run (issue "
        '#771). Treat as EMULATOR INFRA UNAVAILABLE, not a test failure."'
    )
    sig = wci.extract_signature(log)
    assert sig is None or "sdkmanager" not in sig


def test_usage_help_text_mentioning_an_error_is_not_a_signature():
    """A `--help`/usage line that merely NAMES an error is not an occurrence."""
    log = "\n".join(
        [
            "j\tstep\t2026-07-16T18:03:10.0000000Z \x1b[36;1m"
            'echo "usage: gradlew [--fail-fast] # BUILD FAILED means a test broke"\x1b[0m',
            "j\tstep\t2026-07-16T18:03:11.0000000Z done",
        ]
    )
    assert wci.extract_signature(log) is None


# ── G6 NEGATIVE CASES: real failures MUST still be caught ────────────────────
# These are the load-bearing assertions. Going blind to real red CI would be far
# worse than the false alarms this issue is about.


def test_genuine_test_failure_is_still_reported_failed_with_its_signature():
    gh = FakeGh()
    jobs = [_job(n, "completed", "success", i) for i, n in enumerate(REQUIRED)]
    jobs[0] = _job("Unit tests", "completed", "failure", 99)
    gh.queue_run_state(
        status="completed",
        conclusion="failure",
        databaseId=1,
        headBranch="main",
        workflowName="Tests",
        createdAt="2026-07-16T18:02:40Z",
        jobs=jobs,
    )
    gh.log_text = (
        "Unit tests\tRun tests\t2026-07-16T10:00:05Z FooBarTest > checks FAILED\n"
        "Unit tests\tRun tests\t2026-07-16T10:00:06Z BUILD FAILED in 12s\n"
    )
    watcher, _ = _make_watcher(gh)

    outcome = watcher.watch(run_id="1")

    assert outcome.result == wci.RESULT_FAILED
    assert outcome.exit_code == 1
    assert "Unit tests" in outcome.failing_jobs
    assert outcome.signature is not None and "FAILED" in outcome.signature
    assert outcome.likely_infra is False


def test_genuine_infra_failure_is_still_detected_with_its_real_signature():
    """A real, EXECUTED infra error keeps its signature + likely_infra tag (G5)."""
    gh = FakeGh()
    jobs = [_job(n, "completed", "success", i) for i, n in enumerate(REQUIRED)]
    jobs[3] = _job(REQUIRED[3], "completed", "failure", 42)
    gh.queue_run_state(
        status="completed",
        conclusion="failure",
        databaseId=1,
        headBranch="main",
        workflowName="Tests",
        createdAt="2026-07-16T18:02:40Z",
        jobs=jobs,
    )
    # Note: a REAL runner-emitted annotation, not an echoed script body.
    gh.log_text = (
        "emu\tBoot emulator\t2026-07-16T18:10:00.0000000Z \x1b[36;1m"
        'echo "starting emulator"\x1b[0m\n'
        "emu\tBoot emulator\t2026-07-16T18:40:00.0000000Z "
        "##[error]Timed out waiting for emulator to boot after 1800s\n"
    )
    watcher, _ = _make_watcher(gh)

    outcome = watcher.watch(run_id="1")

    assert outcome.result == wci.RESULT_FAILED
    assert outcome.exit_code == 1
    assert outcome.signature is not None
    assert "Timed out waiting for emulator" in outcome.signature
    assert outcome.likely_infra is True, "a real infra signature must still tag infra"


def test_genuine_failure_that_is_later_cancelled_is_still_failed():
    """Precedence: a real failure outranks the cancel that followed it.

    The concurrency-cancel fix must not become a laundry service for real red.
    """
    gh = FakeGh()
    jobs = _superseded_main_jobs()
    jobs[2] = _job("Unit tests", "completed", "failure", 87697273300)
    gh.queue_run_state(
        status="completed",
        conclusion="cancelled",  # run cancelled AFTER Unit tests genuinely failed
        databaseId=29520839502,
        headBranch="main",
        workflowName="Tests",
        createdAt="2026-07-16T18:02:40Z",
        jobs=jobs,
    )
    gh.run_list_json = _newer_run_on_main(newer=True)  # a newer run DOES exist
    gh.log_text = (
        "Unit tests\tRun tests\t2026-07-16T18:30:00Z BUILD FAILED in 12s\n"
    )
    watcher, _ = _make_watcher(gh)

    outcome = watcher.watch(run_id="29520839502")

    assert outcome.result == wci.RESULT_FAILED, (
        "a genuinely failed required check must stay FAILED even though the run "
        "was later cancelled/superseded — otherwise real red CI goes unnoticed"
    )
    assert outcome.exit_code == 1
    assert "Unit tests" in outcome.failing_jobs


def test_timed_out_conclusion_is_still_a_real_failure():
    """`timed_out` is NOT cancelled — it stays a real failure."""
    gh = FakeGh()
    jobs = [_job(n, "completed", "success", i) for i, n in enumerate(REQUIRED)]
    jobs[0] = _job("Unit tests", "completed", "timed_out", 5)
    gh.queue_run_state(
        status="completed",
        conclusion="failure",
        databaseId=1,
        headBranch="main",
        workflowName="Tests",
        createdAt="2026-07-16T18:02:40Z",
        jobs=jobs,
    )
    gh.log_text = "Unit tests\tRun\t2026-07-16T10:00:06Z ##[error]The job running has exceeded the maximum execution time\n"
    watcher, _ = _make_watcher(gh)

    outcome = watcher.watch(run_id="1")

    assert outcome.result == wci.RESULT_FAILED
    assert outcome.exit_code == 1


def test_unclassified_failure_reports_honestly_without_guessing():
    """No confident signature ⇒ say 'unclassified', never guess (G5).

    A fabricated signature is worse than no signature: it sends the on-call to
    the wrong issue with false confidence.
    """
    gh = FakeGh()
    jobs = [_job(n, "completed", "success", i) for i, n in enumerate(REQUIRED)]
    jobs[0] = _job("Unit tests", "completed", "failure", 99)
    gh.queue_run_state(
        status="completed",
        conclusion="failure",
        databaseId=1,
        headBranch="main",
        workflowName="Tests",
        createdAt="2026-07-16T18:02:40Z",
        jobs=jobs,
    )
    gh.log_text = "Unit tests\tRun\t2026-07-16T10:00:00Z nothing conclusive here\n"
    watcher, _ = _make_watcher(gh)

    outcome = watcher.watch(run_id="1")

    assert outcome.result == wci.RESULT_FAILED
    assert outcome.signature is None
    assert outcome.likely_infra is False
    summary = wci.render_human_summary(outcome)
    assert "unclassified" in summary.lower()


# ── D3: a healthy long emulator shard must not trip the no-progress guard ────


def test_default_no_progress_timeout_exceeds_the_emulator_job_cap():
    """The emulator shards run 20-40 min (cap 95) with NO job-state change.

    The old 1200s (20 min) default meant every `main` emulator watch reported a
    bogus HANG while the shards were healthy and running.
    """
    job_cap_s = 95 * 60  # .github/workflows/tests.yml: timeout-minutes: 95
    assert wci.DEFAULT_NO_PROGRESS_TIMEOUT_S > job_cap_s, (
        "a healthy emulator shard would trip the no-progress guard"
    )
    # The wall-clock cap must leave room for the no-progress guard to be the
    # thing that fires, otherwise raising it changes nothing.
    assert wci.DEFAULT_MAX_WALL_CLOCK_S > wci.DEFAULT_NO_PROGRESS_TIMEOUT_S


def test_healthy_long_running_emulator_shard_is_not_a_hang():
    """A shard in_progress for 40 min with no state change is HEALTHY, not hung."""
    gh = FakeGh()
    jobs = [_job(n, "completed", "success", i) for i, n in enumerate(REQUIRED)]
    # The emulator shard grinds away, unchanged, for 40 minutes...
    for _ in range(200):
        shard = list(jobs)
        shard[3] = _job(REQUIRED[3], "in_progress", None, 42)
        gh.queue_run_state(
            status="in_progress",
            conclusion=None,
            databaseId=1,
            headBranch="main",
            workflowName="Tests",
            createdAt="2026-07-16T18:02:40Z",
            jobs=shard,
        )
    # ...then finishes green.
    gh.queue_run_state(
        status="completed",
        conclusion="success",
        databaseId=1,
        headBranch="main",
        workflowName="Tests",
        createdAt="2026-07-16T18:02:40Z",
        jobs=jobs,
    )
    gh.repeat_last_run_state = True
    watcher, _ = _make_watcher(
        gh,
        interval_s=12.0,  # 200 polls * 12s = 40 min of no job-state change
        no_progress_timeout_s=wci.DEFAULT_NO_PROGRESS_TIMEOUT_S,
        max_wall_clock_s=wci.DEFAULT_MAX_WALL_CLOCK_S,
    )

    outcome = watcher.watch(run_id="1")

    assert outcome.result == wci.RESULT_GREEN, (
        f"healthy long shard misreported as {outcome.result}: {outcome.reason}"
    )
    assert outcome.exit_code == 0


# ── The exit-code contract stays a single source of truth ────────────────────


def test_exit_code_contract_is_complete_and_distinct():
    codes = wci.EXIT_CODE
    assert codes[wci.RESULT_GREEN] == 0
    assert codes[wci.RESULT_FAILED] == 1
    assert codes[wci.RESULT_HANG] == 2
    assert codes[wci.RESULT_UNRESOLVED] == 3
    assert codes[wci.RESULT_SUPERSEDED] == 4
    assert codes[wci.RESULT_NO_VERDICT] == 5
    # Every result must render a headline (no KeyError on a new verdict).
    for result in codes:
        outcome = wci.WatchOutcome(
            result=result,
            exit_code=codes[result],
            required={},
            failing_jobs=[],
            signature=None,
            likely_infra=False,
            reason="x",
            run_id="1",
            polls=1,
            elapsed_s=1.0,
        )
        assert wci.render_human_summary(outcome)


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(pytest.main([__file__, "-v"]))
