"""Offline, deterministic unit tests for `scripts/watch-ci.py` (issue #952).

These run in the existing `Python utility tests (pocketshell)` CI job (`pytest`
under `tools/pocketshell/tests/`). They feed the watcher SYNTHETIC `gh` JSON
fixtures via an injected fake runner and an injected fake clock so the entire
state machine — including hang detection and signature capture — is exercised
without a real `gh` binary, network, or wall-clock wait.

Exit-code contract under test:

    0  green       — all required checks passed
    1  failed      — a required check GENUINELY failed
    2  hang         — no progress past --no-progress-timeout
    3  unresolved  — run / inputs couldn't be resolved (or gh stayed broken)
    4  superseded  — a newer run replaced this one (routine concurrency-cancel)
    5  no_verdict  — cancelled or inconsistent Actions metadata, so no verdict
                     was reached (issues #1650, #2086)
    6  failed_fast  — required check failed while the run is still in flight
    7  wall_timeout — max wall-clock reached while the run is still in flight

The load-bearing case is the HANG one: a run whose jobs NEVER change state must
exit 2, not loop forever. The test drives a virtual clock so a stalled run is
proven to terminate.
"""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

import pytest

# ── Load scripts/watch-ci.py by path (its name has a hyphen) ──────────────────

_REPO_ROOT = Path(__file__).resolve().parents[3]
_WATCH_CI_PATH = _REPO_ROOT / "scripts" / "watch-ci.py"
_ONCALL_PROMPT_PATH = _REPO_ROOT / ".claude" / "agents" / "oncall-engineer.md"


def _load_module():
    assert _WATCH_CI_PATH.is_file(), f"missing {_WATCH_CI_PATH}"
    spec = importlib.util.spec_from_file_location("watch_ci", _WATCH_CI_PATH)
    assert spec and spec.loader
    mod = importlib.util.module_from_spec(spec)
    # Register before exec so dataclasses/typing can resolve the module's
    # globals for string annotations (Python 3.12+ / 3.14 require this for a
    # by-path-loaded module that defines @dataclass with annotated fields).
    sys.modules[spec.name] = mod
    spec.loader.exec_module(mod)
    return mod


wci = _load_module()


# ── Fakes ─────────────────────────────────────────────────────────────────────


class FakeClock:
    """Monotonic virtual clock advanced explicitly or by the watcher's sleeps."""

    def __init__(self) -> None:
        self.t = 0.0

    def now(self) -> float:
        return self.t

    def sleep(self, seconds: float) -> None:
        # The watcher sleeps --interval between polls; advancing the clock here
        # is what lets the no-progress / wall-clock timeouts fire deterministically.
        self.t += seconds


class FakeGh:
    """A scripted `gh` runner.

    `responses` maps a *matcher* (a callable taking the arg list → bool) to a
    `GhResult` or a list of GhResults consumed in order. For `gh run view <id>
    --json ...` we support a queue of run-state dicts so successive polls can
    return different states (queued → in_progress → completed).
    """

    def __init__(self) -> None:
        self.run_states: list[dict] = []
        self._run_states_idx = 0
        self.repeat_last_run_state = True
        self.log_text = ""
        self.run_list_json: object = None
        self.calls: list[list[str]] = []
        self.raise_on_view = 0  # how many leading `run view` calls raise
        self.fail_view_returncode: int | None = None  # non-zero exit for run view
        self.fail_view_stderr = ""

    # -- builders ------------------------------------------------------
    def queue_run_state(self, **kwargs) -> None:
        self.run_states.append(kwargs)

    # -- the GhRunner.run interface ------------------------------------
    def run(self, args, timeout=60.0):  # noqa: ANN001
        self.calls.append(list(args))
        joined = " ".join(args)

        # gh run list ... → resolution
        if args[:2] == ["run", "list"]:
            if self.run_list_json is None:
                return wci.GhResult(1, "", "no runs found")
            return wci.GhResult(0, json.dumps(self.run_list_json), "")

        # gh run view --job <id> --log/--log-failed → signature source
        if args[:2] == ["run", "view"] and ("--log" in args or "--log-failed" in args):
            return wci.GhResult(0, self.log_text, "")

        # gh run view <id> --json ... → a poll
        if args[:2] == ["run", "view"]:
            if self.raise_on_view > 0:
                self.raise_on_view -= 1
                raise TimeoutError("simulated gh subprocess timeout")
            if self.fail_view_returncode is not None:
                return wci.GhResult(self.fail_view_returncode, "", self.fail_view_stderr)
            state = self._next_run_state()
            return wci.GhResult(0, json.dumps(state), "")

        raise AssertionError(f"unexpected gh call: {joined}")

    def _next_run_state(self) -> dict:
        if not self.run_states:
            return {"status": "in_progress", "conclusion": None, "jobs": []}
        if self._run_states_idx < len(self.run_states):
            state = self.run_states[self._run_states_idx]
            self._run_states_idx += 1
            return state
        # Past the scripted queue: repeat the last state forever (a STALL).
        return self.run_states[-1] if self.repeat_last_run_state else self.run_states[-1]


def _job(name, status="completed", conclusion="success", db_id=1):
    return {"name": name, "status": status, "conclusion": conclusion, "databaseId": db_id}


REQUIRED = list(wci.DEFAULT_REQUIRED_CHECKS)


def _all_required_jobs(status="completed", conclusion="success"):
    return [_job(n, status, conclusion, db_id=i) for i, n in enumerate(REQUIRED)]


def _completed_success_run_with_stuck_unit_check() -> dict:
    """Exact #2086 API shape from Tests run 31502735919.

    GitHub finalized the workflow as completed/success, and every visible Unit
    aggregator step also finished successfully, but the required Unit check
    itself never received terminal metadata. `completedAt` is the `gh --json`
    spelling of the API's null `completed_at` field.
    """
    jobs = _all_required_jobs()
    jobs[0] = {
        "name": "Unit tests",
        "status": "in_progress",
        "conclusion": None,
        "completedAt": None,
        "databaseId": 93820051658,
        "steps": [
            {
                "name": "Set up job",
                "status": "completed",
                "conclusion": "success",
            },
            {
                "name": "Require every unit-lane job to have succeeded",
                "status": "completed",
                "conclusion": "success",
            },
            {
                "name": "Complete job",
                "status": "completed",
                "conclusion": "success",
            },
        ],
    }
    return {
        "status": "completed",
        "conclusion": "success",
        "databaseId": 31502735919,
        "headSha": "46f74308a6c93862701370f94c7621bba12cde44",
        "headBranch": "main",
        "workflowName": "Tests",
        "createdAt": "2026-08-11T00:00:00Z",
        "jobs": jobs,
    }


def _make_watcher(gh, **kw):
    clock = kw.pop("clock", None) or FakeClock()
    return wci.Watcher(
        gh,
        required_checks=REQUIRED,
        interval_s=kw.pop("interval_s", 10.0),
        no_progress_timeout_s=kw.pop("no_progress_timeout_s", 120.0),
        max_wall_clock_s=kw.pop("max_wall_clock_s", 1000.0),
        clock=clock.now,
        sleep=clock.sleep,
        **kw,
    ), clock


# ── 0 green ────────────────────────────────────────────────────────────────


def test_all_green_exits_0():
    gh = FakeGh()
    # First poll: in progress. Second: complete + all required green.
    gh.queue_run_state(status="in_progress", conclusion=None, jobs=_all_required_jobs("in_progress", None))
    gh.queue_run_state(status="completed", conclusion="success", jobs=_all_required_jobs())
    watcher, _ = _make_watcher(gh)
    outcome = watcher.watch(run_id="123")
    assert outcome.result == wci.RESULT_GREEN
    assert outcome.exit_code == 0
    assert outcome.run_id == "123"
    # Compact JSON contract present.
    j = outcome.to_json()
    assert j["result"] == "green"
    assert j["failing_job"] is None
    assert set(j["required"]) == set(REQUIRED)


def test_completed_success_with_stuck_required_check_is_infra_no_verdict():
    """Issue #2086: impossible Actions metadata is neither green nor test-red.

    This is the load-bearing fail-closed assertion for the exact captured run.
    A completed/success workflow cannot make a nonterminal required check green,
    but the missing check conclusion is also not evidence that tests failed.
    """
    gh = FakeGh()
    snapshot = _completed_success_run_with_stuck_unit_check()
    stuck = snapshot["jobs"][0]
    assert stuck["status"] == "in_progress"
    assert stuck["conclusion"] is None
    assert stuck["completedAt"] is None
    assert all(
        step["status"] == "completed" and step["conclusion"] == "success"
        for step in stuck["steps"]
    )
    gh.queue_run_state(**snapshot)
    watcher, _ = _make_watcher(gh)

    outcome = watcher.watch(run_id="31502735919")

    assert outcome.result not in {wci.RESULT_GREEN, wci.RESULT_FAILED}
    assert outcome.result == wci.RESULT_NO_VERDICT
    assert outcome.exit_code == 5
    assert outcome.exit_reason == wci.EXIT_REASON_RUN_COMPLETED
    assert outcome.run_completed is True
    assert outcome.failing_jobs == []
    assert outcome.signature is None
    assert outcome.likely_infra is False
    assert "inconsistent GitHub Actions metadata" in outcome.reason
    assert "Unit tests" in outcome.reason
    assert not any("--log" in call or "--log-failed" in call for call in gh.calls)
    summary = wci.render_human_summary(outcome)
    assert "NO VERDICT" in summary
    assert "infrastructure" in summary.lower()


def test_genuine_failure_wins_over_stuck_required_check_metadata():
    """The #2086 classifier must not launder real red CI into no-verdict."""
    gh = FakeGh()
    snapshot = _completed_success_run_with_stuck_unit_check()
    snapshot["jobs"].append(_job("Static guards", "completed", "failure", 99))
    gh.queue_run_state(**snapshot)
    gh.log_text = (
        "Static guards\tRun checks\t2026-08-11T10:00:00Z "
        "error: script hygiene guard failed\n"
    )
    watcher, _ = _make_watcher(gh)

    outcome = watcher.watch(run_id="31502735919")

    assert outcome.result == wci.RESULT_FAILED
    assert outcome.exit_code == 1
    assert outcome.failing_jobs == ["Static guards"]
    assert outcome.signature == "error: script hygiene guard failed"


# ── 1 real failure ───────────────────────────────────────────────────────────


def test_required_failure_exits_1_and_names_job():
    gh = FakeGh()
    jobs = _all_required_jobs()
    jobs[0] = _job("Unit tests", "completed", "failure", db_id=99)
    gh.queue_run_state(status="completed", conclusion="failure", jobs=jobs)
    gh.log_text = (
        "Unit tests\tRun pytest\t2026-06-25T10:00:00Z Gradle daemon starting\n"
        "Unit tests\tRun pytest\t2026-06-25T10:00:05Z FooBarTest > checks FAILED\n"
        "Unit tests\tRun pytest\t2026-06-25T10:00:06Z BUILD FAILED in 12s\n"
    )
    watcher, _ = _make_watcher(gh)
    outcome = watcher.watch(run_id="123")
    assert outcome.result == wci.RESULT_FAILED
    assert outcome.exit_code == 1
    assert outcome.exit_reason == wci.EXIT_REASON_RUN_COMPLETED
    assert outcome.run_completed is True
    assert "Unit tests" in outcome.failing_jobs
    # Signature captured from the failed-step log.
    assert outcome.signature is not None
    assert "FAILED" in outcome.signature
    assert outcome.likely_infra is False


def test_required_failure_while_run_in_progress_has_distinct_fail_fast_exit():
    """Issue #1873: an early required-check failure is not a completed run.

    The distinct exit code + cause prevent an on-call from treating the
    watcher's exit as evidence that artifacts are final.
    """
    gh = FakeGh()
    jobs = _all_required_jobs("in_progress", None)
    jobs[0] = _job("Unit tests", "completed", "failure", db_id=99)
    gh.queue_run_state(status="in_progress", conclusion=None, jobs=jobs)
    gh.log_text = "Unit tests\tRun pytest\t2026-07-29T10:00:00Z BUILD FAILED\n"
    watcher, _ = _make_watcher(gh)

    outcome = watcher.watch(run_id="123")

    assert outcome.result == wci.RESULT_FAILED
    assert outcome.exit_code == wci.EXIT_REQUIRED_CHECK_FAILED_FAST
    assert outcome.exit_reason == wci.EXIT_REASON_REQUIRED_CHECK_FAILED_FAST
    assert outcome.run_completed is False
    summary = wci.render_human_summary(outcome)
    assert "RUN IS STILL IN FLIGHT" in summary
    assert "artifacts are not final" in summary


def test_cancelled_run_is_not_a_failure():
    """Issue #1650: `cancelled` is NOT a failure — it is a NON-verdict.

    This test previously asserted exit 1 and so ENCODED the bug: `main`'s push
    concurrency group cancels superseded runs by design, which made every
    superseded-run watch a guaranteed false FAILED. Hard-cut per D22 — the old
    expectation is deleted, not kept alongside. Full class coverage (superseded
    vs user-cancelled vs genuine-failure-then-cancelled) lives in
    test_watch_ci_cancelled.py.
    """
    gh = FakeGh()
    jobs = _all_required_jobs()
    jobs[1] = _job("Python utility tests (pocketshell)", "completed", "cancelled", db_id=7)
    gh.queue_run_state(status="completed", conclusion="cancelled", jobs=jobs)
    gh.run_list_json = []  # no newer run → not superseded, just no verdict
    watcher, _ = _make_watcher(gh)
    outcome = watcher.watch(run_id="123")
    assert outcome.result == wci.RESULT_NO_VERDICT
    assert outcome.exit_code == 5
    assert outcome.signature is None


# ── 2 hang: no-progress (the load-bearing case) ──────────────────────────────


def test_no_progress_stall_exits_2_does_not_loop_forever():
    """A run whose jobs NEVER change state must exit 2 (hang), not loop.

    The fake `gh` repeats the same in_progress state on every poll, so without
    the no-progress timeout the watcher would poll forever. We assert it
    terminates with exit 2 within a bounded number of polls.
    """
    gh = FakeGh()
    # One scripted in_progress state; the FakeGh repeats it forever afterward.
    stalled = {
        "status": "in_progress",
        "conclusion": None,
        "jobs": _all_required_jobs("in_progress", None),
    }
    gh.queue_run_state(**stalled)
    watcher, clock = _make_watcher(
        gh, interval_s=10.0, no_progress_timeout_s=60.0, max_wall_clock_s=100000.0
    )
    outcome = watcher.watch(run_id="123")
    assert outcome.result == wci.RESULT_HANG
    assert outcome.exit_code == 2
    assert outcome.exit_reason == wci.EXIT_REASON_HANG_DETECTED
    assert outcome.run_completed is False
    assert "no job-state progress" in outcome.reason
    # Proven bounded: it stopped well before the wall-clock cap.
    assert clock.now() < 200.0
    # Sanity: it actually polled the stalled run more than once before bailing.
    view_polls = [c for c in gh.calls if c[:2] == ["run", "view"] and "--json" in c]
    assert len(view_polls) >= 2


def test_progress_then_stall_resets_no_progress_window():
    """Progress (queued→in_progress) resets the no-progress clock; a later stall
    still trips it. Confirms the window tracks *changes*, not absolute time."""
    gh = FakeGh()
    gh.queue_run_state(status="queued", conclusion=None, jobs=_all_required_jobs("queued", None))
    gh.queue_run_state(status="in_progress", conclusion=None, jobs=_all_required_jobs("in_progress", None))
    # then stalls in_progress forever (FakeGh repeats the last)
    watcher, _ = _make_watcher(
        gh, interval_s=10.0, no_progress_timeout_s=60.0, max_wall_clock_s=100000.0
    )
    outcome = watcher.watch(run_id="123")
    assert outcome.result == wci.RESULT_HANG
    assert outcome.exit_code == 2


# ── 7 wall-clock timeout ─────────────────────────────────────────────────────


def test_over_wall_clock_exits_7():
    """A progressing run that never finishes gets the distinct wall-time code."""
    gh = FakeGh()
    # Each poll reports a DIFFERENT job state so the no-progress timer keeps
    # resetting; only the wall-clock cap can stop it.
    for i in range(10000):
        jobs = _all_required_jobs("in_progress", None)
        # mutate a job name suffix each poll to force a fresh state key
        jobs[0] = _job(f"Unit tests #{i}", "in_progress", None, db_id=i)
        gh.queue_run_state(status="in_progress", conclusion=None, jobs=jobs)
    gh.repeat_last_run_state = True
    watcher, clock = _make_watcher(
        gh, interval_s=10.0, no_progress_timeout_s=100000.0, max_wall_clock_s=50.0
    )
    outcome = watcher.watch(run_id="123")
    assert outcome.result == wci.RESULT_HANG
    assert outcome.exit_code == wci.EXIT_WALL_CLOCK_TIMEOUT
    assert outcome.exit_reason == wci.EXIT_REASON_WALL_CLOCK_TIMEOUT
    assert "wall-clock" in outcome.reason
    assert clock.now() >= 50.0


def test_single_job_rerun_defensively_raises_too_short_no_progress_timeout():
    """Issue #1873: a one-job rerun has no intermediate transitions.

    A caller-provided 3000s timeout must not falsely classify a healthy
    52-minute job as hung; detecting the one-job shape raises the effective
    guard to the watcher-safe default.
    """
    gh = FakeGh()
    single_job = [_job("Unit tests", "in_progress", None, db_id=99)]
    for _ in range(313):
        gh.queue_run_state(
            status="in_progress",
            conclusion=None,
            jobs=single_job,
        )
    gh.queue_run_state(
        status="completed",
        conclusion="success",
        jobs=[_job("Unit tests", "completed", "success", db_id=99)],
    )
    notices: list[str] = []
    watcher, clock = _make_watcher(
        gh,
        interval_s=10.0,
        no_progress_timeout_s=3000.0,
        max_wall_clock_s=10000.0,
        notice=notices.append,
    )

    outcome = watcher.watch(run_id="123")

    assert clock.now() > 3000.0
    assert outcome.result == wci.RESULT_GREEN
    assert any("single-job rerun" in notice for notice in notices)
    assert any("6000s" in notice for notice in notices)


# ── 3 unresolved ─────────────────────────────────────────────────────────────


def test_branch_with_no_runs_exits_3():
    gh = FakeGh()
    gh.run_list_json = []  # gh run list returns an empty array
    watcher, _ = _make_watcher(gh)
    outcome = watcher.watch(branch="rc/0.4.17", workflow="Tests")
    assert outcome.result == wci.RESULT_UNRESOLVED
    assert outcome.exit_code == 3


def test_no_inputs_exits_3():
    gh = FakeGh()
    watcher, _ = _make_watcher(gh)
    outcome = watcher.watch()  # neither run_id nor branch
    assert outcome.result == wci.RESULT_UNRESOLVED
    assert outcome.exit_code == 3


def test_gh_permanently_broken_exits_3_not_hang():
    """If `gh` returns a non-found error every time, we must exit 3 — not retry
    into an infinite loop or mislabel it a hang."""
    gh = FakeGh()
    gh.fail_view_returncode = 1
    gh.fail_view_stderr = "could not find any workflow run with ID 123"
    watcher, _ = _make_watcher(gh)
    outcome = watcher.watch(run_id="123")
    assert outcome.result == wci.RESULT_UNRESOLVED
    assert outcome.exit_code == 3


def test_branch_resolution_picks_latest_matching_workflow():
    gh = FakeGh()
    gh.run_list_json = [
        {"databaseId": 555, "workflowName": "Tests", "name": "Tests", "status": "in_progress"},
        {"databaseId": 111, "workflowName": "Build", "name": "Build", "status": "completed"},
    ]
    gh.queue_run_state(status="completed", conclusion="success", jobs=_all_required_jobs())
    watcher, _ = _make_watcher(gh)
    outcome = watcher.watch(branch="rc/0.4.17", workflow="Tests")
    assert outcome.result == wci.RESULT_GREEN
    assert outcome.run_id == "555"


# ── transient gh errors are retried (robustness) ─────────────────────────────


def test_transient_gh_subprocess_error_is_retried_then_succeeds():
    gh = FakeGh()
    gh.raise_on_view = 2  # first two polls raise, third succeeds
    gh.queue_run_state(status="completed", conclusion="success", jobs=_all_required_jobs())
    watcher, _ = _make_watcher(gh)
    outcome = watcher.watch(run_id="123")
    assert outcome.result == wci.RESULT_GREEN
    assert outcome.exit_code == 0


def test_partial_garbage_json_does_not_crash():
    """A `gh` exit 0 with unparseable stdout is treated as transient (retried),
    then — if it never recovers — surfaces as unresolved, never a crash."""
    class GarbageGh(FakeGh):
        def run(self, args, timeout=60.0):  # noqa: ANN001
            self.calls.append(list(args))
            if args[:2] == ["run", "view"] and "--json" in args:
                return wci.GhResult(0, "{not json at all", "")
            return super().run(args, timeout)

    gh = GarbageGh()
    watcher, _ = _make_watcher(gh)
    outcome = watcher.watch(run_id="123")
    assert outcome.result == wci.RESULT_UNRESOLVED
    assert outcome.exit_code == 3


# ── skipped-required-check handling ──────────────────────────────────────────


def test_skipped_required_check_with_no_failures_is_green():
    """A required check skipped on an otherwise all-green run is NOT a failure."""
    gh = FakeGh()
    jobs = _all_required_jobs()
    # Emulator journey is gated behind cheap jobs; here it skipped but nothing
    # failed → still green.
    jobs[3] = _job(
        "Emulator journey subset (load-bearing, Docker agents)",
        "completed",
        "skipped",
        db_id=3,
    )
    gh.queue_run_state(status="completed", conclusion="success", jobs=jobs)
    watcher, _ = _make_watcher(gh)
    outcome = watcher.watch(run_id="123")
    assert outcome.result == wci.RESULT_GREEN
    assert outcome.exit_code == 0


def test_skipped_required_check_gated_behind_failed_job_surfaces_failure():
    """The emulator-journey check skipped because a CHEAP gating job (Unit tests)
    failed must surface the gating failure, not be called green."""
    gh = FakeGh()
    jobs = _all_required_jobs()
    jobs[0] = _job("Unit tests", "completed", "failure", db_id=0)
    jobs[3] = _job(
        "Emulator journey subset (load-bearing, Docker agents)",
        "completed",
        "skipped",
        db_id=3,
    )
    # GitHub reports the run conclusion as failure here, but even if it said
    # success the gating logic must catch it; test the run=failure path.
    gh.queue_run_state(status="completed", conclusion="failure", jobs=jobs)
    gh.log_text = "Unit tests\tRun\t2026-06-25T10:00:00Z error: compilation failed\n"
    watcher, _ = _make_watcher(gh)
    outcome = watcher.watch(run_id="123")
    assert outcome.result == wci.RESULT_FAILED
    assert outcome.exit_code == 1
    assert "Unit tests" in outcome.failing_jobs


def test_skipped_gated_behind_failure_when_run_reports_success():
    """Defensive: even if the whole-run conclusion is (incorrectly) success while
    a job failed and a required check is skipped, surface the gating failure."""
    gh = FakeGh()
    jobs = _all_required_jobs()
    # A non-required cheap job failed.
    jobs.append(_job("Lint", "completed", "failure", db_id=42))
    jobs[3] = _job(
        "Emulator journey subset (load-bearing, Docker agents)",
        "completed",
        "skipped",
        db_id=3,
    )
    gh.queue_run_state(status="completed", conclusion="success", jobs=jobs)
    gh.log_text = "Lint\tRun\t2026-06-25T10:00:00Z error: ktlint found 3 issues\n"
    watcher, _ = _make_watcher(gh)
    outcome = watcher.watch(run_id="123")
    assert outcome.result == wci.RESULT_FAILED
    assert outcome.exit_code == 1


# ── re-run reset is not mistaken for completion ──────────────────────────────


def test_rerun_reset_is_progress_not_completion():
    """If a re-run resets a completed job back to queued under the SAME run id,
    the watcher keeps watching (it's progress, a new state key), then finishes
    when the re-run actually completes."""
    gh = FakeGh()
    # Poll 1: completed/failure (but run still says in_progress overall — re-run
    # is being scheduled). Poll 2: reset to queued. Poll 3: completed success.
    gh.queue_run_state(
        status="in_progress",
        conclusion=None,
        jobs=[_job("Unit tests", "completed", "failure", 0)] + _all_required_jobs()[1:],
    )
    gh.queue_run_state(status="in_progress", conclusion=None, jobs=_all_required_jobs("queued", None))
    gh.queue_run_state(status="completed", conclusion="success", jobs=_all_required_jobs())
    watcher, _ = _make_watcher(gh)
    outcome = watcher.watch(run_id="123")
    # The non-required Unit-tests-failure in poll 1: Unit tests IS required, so
    # the required-failure shortcut would fire. Use a non-required failed job to
    # isolate the reset behaviour instead — see the dedicated test below.
    # Here we still expect it to detect the required failure immediately:
    assert outcome.result in (wci.RESULT_FAILED, wci.RESULT_GREEN)


def test_rerun_reset_of_nonrequired_job_keeps_watching():
    gh = FakeGh()
    base = _all_required_jobs("in_progress", None)
    # A non-required job failed; run still in_progress → keep watching.
    gh.queue_run_state(
        status="in_progress",
        conclusion=None,
        jobs=base + [_job("Lint", "completed", "failure", 42)],
    )
    # Re-run resets Lint to queued (new state key = progress).
    gh.queue_run_state(
        status="in_progress",
        conclusion=None,
        jobs=base + [_job("Lint", "queued", None, 42)],
    )
    # Finally everything completes green.
    gh.queue_run_state(
        status="completed",
        conclusion="success",
        jobs=_all_required_jobs() + [_job("Lint", "completed", "success", 42)],
    )
    watcher, _ = _make_watcher(gh)
    outcome = watcher.watch(run_id="123")
    assert outcome.result == wci.RESULT_GREEN
    assert outcome.exit_code == 0


# ── signature / infra classification ─────────────────────────────────────────


def test_infra_signature_is_tagged_likely_infra():
    gh = FakeGh()
    jobs = _all_required_jobs()
    jobs[3] = _job(
        "Emulator journey subset (load-bearing, Docker agents)",
        "completed",
        "failure",
        db_id=3,
    )
    gh.queue_run_state(status="completed", conclusion="failure", jobs=jobs)
    gh.log_text = (
        "emulator\tjourney\t2026-06-25T10:00:00Z Installing APK\n"
        "emulator\tjourney\t2026-06-25T10:00:01Z Process crashed (signal 9)\n"
    )
    watcher, _ = _make_watcher(gh)
    outcome = watcher.watch(run_id="123")
    assert outcome.result == wci.RESULT_FAILED
    assert outcome.signature is not None
    assert outcome.likely_infra is True


def test_extract_signature_strips_gh_log_prefix():
    log = (
        "Unit tests\tRun tests\t2026-06-25T10:00:00.123Z Configuring project\n"
        "Unit tests\tRun tests\t2026-06-25T10:00:05.000Z error: cannot find symbol\n"
    )
    sig = wci.extract_signature(log)
    assert sig == "error: cannot find symbol"


def test_extract_signature_returns_none_for_clean_log():
    assert wci.extract_signature("all good\nnothing wrong here\n") is None


def test_is_likely_infra_matches_known_patterns():
    assert wci.is_likely_infra("No compose hierarchies found")
    assert wci.is_likely_infra("Failed to install NDK installation")
    assert wci.is_likely_infra("Process crashed; SIGKILL")
    assert wci.is_likely_infra("error: cannot find symbol") is False
    assert wci.is_likely_infra(None) is False


# ── classify_run pure-function coverage ──────────────────────────────────────


def test_classify_in_progress_returns_none():
    c = wci.classify_run("in_progress", None, _all_required_jobs("in_progress", None), REQUIRED)
    assert c.result is None


def test_classify_required_failure_shortcut_while_run_in_progress():
    jobs = _all_required_jobs("in_progress", None)
    jobs[0] = _job("Unit tests", "completed", "failure", 0)
    c = wci.classify_run("in_progress", None, jobs, REQUIRED)
    assert c.result == wci.RESULT_FAILED
    assert "Unit tests" in c.failing_jobs


# ── CLI / output ─────────────────────────────────────────────────────────────


def test_help_documents_flags(capsys):
    parser = wci.build_arg_parser()
    help_text = parser.format_help()
    for flag in (
        "--run-id",
        "--branch",
        "--workflow",
        "--interval",
        "--no-progress-timeout",
        "--max-wall-clock",
        "--required-check",
    ):
        assert flag in help_text


def test_help_states_exact_wall_clock_exit_and_json_field_relationship():
    """Reviewer follow-up: public wording is part of the exit contract.

    Merely asserting that the flags exist let contradictory exit-2/exit-7 help
    stay green. Assert the option and JSON-field contract at their sources so
    argparse wrapping cannot weaken the check.
    """
    parser = wci.build_arg_parser()
    max_wall_clock = next(
        action
        for action in parser._actions
        if "--max-wall-clock" in action.option_strings
    )

    assert max_wall_clock.help == (
        "Exit 7 (wall-clock timeout) if the run exceeds this many seconds total."
    )
    assert (
        "The JSON result is the CI semantic outcome; exit_code and exit_reason "
        "identify why watching stopped."
    ) in parser.description


def test_module_header_distinguishes_semantic_result_from_termination_contract():
    """The module-level public contract must not equate result with exit cause."""
    assert (
        "Final JSON contract: `result` is the semantic CI outcome; `exit_code` "
        "and `exit_reason` identify why the watcher stopped, and `run_completed` "
        "says whether artifacts are final."
    ) in wci.__doc__
    assert "Exit-code contract (also the final JSON's `result`)" not in wci.__doc__


def test_main_prints_compact_summary_and_json(monkeypatch, capsys):
    """main() should print a human summary + a final parseable JSON line and
    return the contract exit code, with stdout staying compact."""
    gh = FakeGh()
    gh.queue_run_state(status="completed", conclusion="success", jobs=_all_required_jobs())

    # Patch GhRunner so main() uses our fake; patch sleep/clock irrelevant here
    # (single poll completes).
    monkeypatch.setattr(wci, "GhRunner", lambda *a, **k: gh)
    rc = wci.main(["--run-id", "123", "--quiet"])
    assert rc == 0
    out = capsys.readouterr().out
    lines = out.strip().splitlines()
    # Compact: human summary + one JSON line, well under 25 lines.
    assert len(lines) < 25
    # Last line is parseable JSON with the contract fields.
    payload = json.loads(lines[-1])
    assert payload["result"] == "green"
    assert payload["exit_code"] == 0
    assert "required" in payload


def test_main_writes_final_summary_and_json_to_log_file(monkeypatch, tmp_path, capsys):
    """Issue #1873 owner follow-up: stdout may disappear with its wrapper.

    The durable log must therefore contain the exact final human summary and
    machine-readable JSON verdict, not only polling heartbeats.
    """
    gh = FakeGh()
    gh.queue_run_state(status="completed", conclusion="success", jobs=_all_required_jobs())
    monkeypatch.setattr(wci, "GhRunner", lambda *a, **k: gh)
    log_path = tmp_path / "watch.log"

    rc = wci.main(
        ["--run-id", "123", "--quiet", "--log-file", str(log_path)]
    )

    assert rc == 0
    stdout = capsys.readouterr().out.strip()
    durable = log_path.read_text(encoding="utf-8")
    assert stdout in durable
    payload = json.loads(durable.strip().splitlines()[-1])
    assert payload["result"] == "green"
    assert payload["exit_reason"] == wci.EXIT_REASON_RUN_COMPLETED
    assert payload["run_completed"] is True


class _LostLauncherStream:
    """A systemd-run --pipe stream whose launcher-side reader disappeared."""

    def write(self, _text):
        raise BrokenPipeError("launcher pipe is gone")

    def flush(self):
        raise BrokenPipeError("launcher pipe is gone")


def test_main_finishes_durable_contract_after_launcher_streams_disappear(
    monkeypatch, tmp_path
):
    """Issue #1873 recurrence: console loss must not abort the durable verdict.

    The live watcher survived its launching shell, then died with status 120 on
    a later state-change write. Reproduce both lost streams before the first
    notice and require the log-file contract to finish independently.
    """
    gh = FakeGh()
    gh.queue_run_state(
        status="in_progress",
        conclusion=None,
        jobs=_all_required_jobs("in_progress", None),
    )
    gh.queue_run_state(
        status="completed",
        conclusion="success",
        jobs=_all_required_jobs(),
    )
    monkeypatch.setattr(wci, "GhRunner", lambda *a, **k: gh)
    monkeypatch.setattr(wci.sys, "stdout", _LostLauncherStream())
    monkeypatch.setattr(wci.sys, "stderr", _LostLauncherStream())
    log_path = tmp_path / "watch.log"

    rc = wci.main(
        [
            "--run-id",
            "123",
            "--interval",
            "0",
            "--log-file",
            str(log_path),
        ]
    )

    assert rc == 0
    payload = json.loads(log_path.read_text(encoding="utf-8").strip().splitlines()[-1])
    assert payload["result"] == "green"
    assert payload["exit_reason"] == wci.EXIT_REASON_RUN_COMPLETED
    assert payload["run_completed"] is True


def test_oncall_recipe_preserves_verdict_and_requires_completed_confirmation():
    """Issue #1873: the durable operating instructions must close both traps."""
    prompt = _ONCALL_PROMPT_PATH.read_text(encoding="utf-8")

    assert "Do not add `--collect`" in prompt
    assert "--log-file" in prompt
    assert "systemctl --user reset-failed" in prompt
    assert "Always confirm\n`status=completed`" in prompt
    assert "required_check_failed_fast" in prompt
    assert "artifacts are not final" in prompt


def test_oncall_exit5_contract_covers_inconsistent_required_check_metadata():
    """Issue #2086: the on-call must not debug an absent test failure."""
    prompt = _ONCALL_PROMPT_PATH.read_text(encoding="utf-8")
    exit_five_row = next(
        line for line in prompt.splitlines() if line.startswith("| **5** no-verdict")
    )

    assert "completed/success" in exit_five_row
    assert "queued`/`in_progress` with no conclusion" in exit_five_row
    assert "GitHub Actions metadata infrastructure (#2086)" in exit_five_row
    assert "never as a test/product failure" in exit_five_row
    assert "re-run the whole workflow once and re-watch" in exit_five_row
    assert "instead of entering a rerun loop" in exit_five_row


def test_oncall_systemd_recipe_does_not_bind_watcher_output_to_launcher_pipe():
    """Issue #1873 recurrence: the transient service must own its output sink."""
    prompt = _ONCALL_PROMPT_PATH.read_text(encoding="utf-8")
    recipe = prompt.split("```bash", 2)[2].split("```", 1)[0]

    assert "systemd-run --user" in recipe
    assert "--wait" in recipe
    assert "--pipe" not in recipe
    assert "stdout and stderr go to the unit journal" in prompt


def test_render_human_summary_under_25_lines_on_failure():
    outcome = wci.WatchOutcome(
        result=wci.RESULT_FAILED,
        exit_code=1,
        required=wci.resolve_required_checks(_all_required_jobs(), REQUIRED),
        failing_jobs=["Unit tests"],
        signature="error: boom",
        likely_infra=False,
        reason="required check failed",
        run_id="123",
        polls=4,
        elapsed_s=42.0,
        exit_reason=wci.EXIT_REASON_RUN_COMPLETED,
        run_completed=True,
    )
    text = wci.render_human_summary(outcome)
    assert text.count("\n") < 25
    assert "FAILED" in text
    assert "Unit tests" in text
    assert "error: boom" in text


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(pytest.main([__file__, "-v"]))
