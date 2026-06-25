#!/usr/bin/env python3
"""watch-ci.py — token-free, hang-proof GitHub Actions run watcher (issue #952).

An agent (especially the on-call) runs this ONCE. It blocks while a CI run is in
flight — burning ZERO LLM tokens while waiting, because the harness wakes the
caller only when the process exits — then prints a compact summary the agent
reads to decide what to do next. It NEVER waits forever: it stops itself if a run
hangs (no job-state progress) or blows past a wall-clock cap.

Design goals (see issue #952):

1. Token-cheap. We poll `gh run view <id> --json status,conclusion,jobs` and keep
   our own state. Per-poll heartbeats go to an optional --log-file (or are
   suppressed by --quiet); stdout gets ONLY a one-line job-state-change notice
   plus, on exit, a compact (< ~25 line) human summary and a final
   machine-readable JSON line. The reading agent should never need a second,
   token-heavy log fetch.

2. Never stuck. A no-progress timeout (no job-state change for
   --no-progress-timeout — this also catches "queued forever / no runner"), a
   --max-wall-clock cap, and a `gh`-transient retry budget all guarantee
   termination. A re-run that resets jobs under the same run id is NOT mistaken
   for completion — we keep watching.

3. Informative. On a real failure we capture the SIGNATURE ourselves: pull the
   failed job's failed-step log (`gh run view --job <id> --log-failed`) and grep
   the first meaningful error line into the `signature` field, and tag known
   infra signatures with `likely_infra: true` so the on-call can classify
   flake-vs-real from this output alone.

Exit-code contract (also the final JSON's `result`):

    0  green       — all REQUIRED checks concluded `success` (or appropriately
                     skipped) and the run is complete.
    1  failed      — a required check concluded `failure`/`cancelled`/`timed_out`,
                     or the run was cancelled.
    2  hang         — no job-state progress for --no-progress-timeout, OR the run
                     exceeded --max-wall-clock. Never wait forever.
    3  unresolved  — the run / inputs couldn't be resolved, or `gh` stayed broken
                     past the retry budget.

This module is import-safe and dependency-injectable: tests construct a Watcher
with a fake `gh` runner and a fake clock, so the whole state machine (including
hang detection) is exercised offline and deterministically.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from typing import Callable, Optional

# ── Defaults ─────────────────────────────────────────────────────────────────

# The protected-`main` required checks (see process.md "Protected `main`
# checks"). Overridable with --required-check (repeatable).
DEFAULT_REQUIRED_CHECKS = (
    "Unit tests",
    "Python utility tests (pocketshell)",
    "Integration tests (Docker)",
    "Emulator journey subset (load-bearing, Docker agents)",
)

DEFAULT_INTERVAL_S = 25.0
DEFAULT_NO_PROGRESS_TIMEOUT_S = 20 * 60
DEFAULT_MAX_WALL_CLOCK_S = 60 * 60
GH_RETRY_BUDGET = 3
GH_RETRY_BACKOFF_S = 3.0

# Result strings <-> exit codes. Single source of truth for the contract.
RESULT_GREEN = "green"
RESULT_FAILED = "failed"
RESULT_HANG = "hang"
RESULT_UNRESOLVED = "unresolved"

EXIT_CODE = {
    RESULT_GREEN: 0,
    RESULT_FAILED: 1,
    RESULT_HANG: 2,
    RESULT_UNRESOLVED: 3,
}

# Terminal (completed) GitHub Actions conclusions that are NOT success.
FAILING_CONCLUSIONS = frozenset(
    {"failure", "cancelled", "timed_out", "startup_failure", "action_required"}
)

# Ordered (pattern) signatures. The first meaningful error line we grep from a
# failed job's log.
_SIGNATURE_PATTERNS = [
    re.compile(r"^.*\berror:.*$", re.IGNORECASE),
    re.compile(r"^.*\bBUILD FAILED\b.*$"),
    re.compile(r"^.*\bFAILED\b.*$"),
    re.compile(r"^.*\bFAIL\b\s+—.*$"),
    re.compile(r"^.*\b(Exception|Traceback)\b.*$"),
    re.compile(r"^.*\b(Install|installing).*\bfailed\b.*$", re.IGNORECASE),
    re.compile(r"^.*\bTests? (failed|FAILED)\b.*$"),
]

# Known infra/flake signatures. If the captured signature matches one of these,
# tag likely_infra so the on-call knows to re-run rather than debug.
_INFRA_SIGNATURE_PATTERNS = [
    re.compile(r"\bNDK\b.*\b(install|installation)\b", re.IGNORECASE),
    re.compile(r"No compose hierarchies", re.IGNORECASE),
    re.compile(r"UiAutomation.*while connecting", re.IGNORECASE),
    re.compile(r"Process crashed", re.IGNORECASE),
    re.compile(r"signal[ -]?9", re.IGNORECASE),
    re.compile(r"\bSIGKILL\b"),
    re.compile(r"emulator.*(boot|timeout)", re.IGNORECASE),
    re.compile(r"(Timed out|Timeout) waiting for (emulator|device)", re.IGNORECASE),
    re.compile(r"docker.*(fixture|compose).*(fail|error)", re.IGNORECASE),
    re.compile(r"Could not (connect|resolve) to (github|registry)", re.IGNORECASE),
    re.compile(r"swiftshader", re.IGNORECASE),
]


# ── gh runner abstraction (injectable for tests) ─────────────────────────────


@dataclass
class GhResult:
    """Outcome of one `gh` invocation."""

    returncode: int
    stdout: str
    stderr: str = ""


class GhRunner:
    """Runs `gh ...` via subprocess. Tests inject a fake with the same shape."""

    def __init__(self, gh_bin: str = "gh") -> None:
        self.gh_bin = gh_bin

    def run(self, args: list[str], timeout: float = 60.0) -> GhResult:
        proc = subprocess.run(  # noqa: S603 - args are constructed internally
            [self.gh_bin, *args],
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        return GhResult(proc.returncode, proc.stdout, proc.stderr)


class GhError(RuntimeError):
    """A `gh` call failed in a way the retry budget could not recover."""


# ── Pure helpers (the testable core) ─────────────────────────────────────────


def extract_signature(log_text: str) -> Optional[str]:
    """Return the first meaningful error line from a failed-step log, or None.

    Scans top-to-bottom; the first line matching any signature pattern wins.
    A known infra/flake line (e.g. `Process crashed (signal 9)`) also counts as
    a signature so the on-call still gets the flake hint even when the log has no
    conventional `error:`/`FAILED` line. Strips the `gh --log` timestamp/prefix
    columns (job\\tstep\\tISO-8601 ...) so the returned line is the bare message.
    """
    for raw in log_text.splitlines():
        line = _strip_gh_log_prefix(raw).strip()
        if not line:
            continue
        for pat in _SIGNATURE_PATTERNS:
            if pat.match(line):
                return line
        for pat in _INFRA_SIGNATURE_PATTERNS:
            if pat.search(line):
                return line
    return None


def _strip_gh_log_prefix(line: str) -> str:
    """`gh run view --log` prefixes each line with `job\\tstep\\t<ts> <msg>`.

    Drop the tab-delimited columns and a leading ISO-8601 timestamp if present;
    otherwise return the line unchanged.
    """
    if "\t" in line:
        tail = line.split("\t")[-1]
        return _strip_leading_timestamp(tail)
    return _strip_leading_timestamp(line)


_TS_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T[\d:.]+Z?\s+")


def _strip_leading_timestamp(line: str) -> str:
    return _TS_RE.sub("", line)


def is_likely_infra(signature: Optional[str]) -> bool:
    if not signature:
        return False
    return any(pat.search(signature) for pat in _INFRA_SIGNATURE_PATTERNS)


def job_state_key(jobs: list[dict]) -> tuple:
    """A hashable snapshot of (name, status, conclusion) per job.

    Two polls with the same key made no progress. A re-run that resets a job
    from completed→queued changes this key, so it correctly counts as progress
    (and is NOT mistaken for completion).
    """
    return tuple(
        sorted(
            (
                str(j.get("name", "")),
                str(j.get("status", "")),
                str(j.get("conclusion") or ""),
            )
            for j in jobs
        )
    )


@dataclass
class RequiredCheck:
    name: str
    status: str  # queued | in_progress | completed | missing
    conclusion: Optional[str]  # success | failure | skipped | cancelled | None


def resolve_required_checks(
    jobs: list[dict], required_names: list[str]
) -> dict[str, RequiredCheck]:
    """Map each required name to its current job state (or a 'missing' stub).

    GitHub may name the *workflow check* and the *job* differently; we match a
    required name if it equals a job name OR is a substring of one (covers
    matrix-suffixed job names). The first match wins.
    """
    out: dict[str, RequiredCheck] = {}
    for name in required_names:
        match = None
        for j in jobs:
            jname = str(j.get("name", ""))
            if jname == name or name in jname:
                match = j
                break
        if match is None:
            out[name] = RequiredCheck(name, "missing", None)
        else:
            out[name] = RequiredCheck(
                name,
                str(match.get("status", "")),
                (str(match.get("conclusion")) if match.get("conclusion") else None),
            )
    return out


@dataclass
class Classification:
    """The verdict for a single poll of a run."""

    result: Optional[str]  # one of RESULT_* once terminal, else None (keep going)
    required: dict[str, RequiredCheck]
    failing_jobs: list[str] = field(default_factory=list)
    reason: str = ""


def classify_run(
    run_status: str,
    run_conclusion: Optional[str],
    jobs: list[dict],
    required_names: list[str],
) -> Classification:
    """Decide whether this poll is terminal and, if so, with what result.

    Returns Classification.result == None while the run is still in flight (the
    caller keeps polling). When the run is complete (or a required check has
    already definitively failed), result is RESULT_GREEN / RESULT_FAILED.

    Skipped handling: a `skipped` required check is treated as a non-failure ON
    ITS OWN. But a required check that is skipped because its gating job FAILED
    must surface that gating failure — so if ANY job in the run failed, a skipped
    required check does not let the run be called green; the failing job is
    reported and the result is RESULT_FAILED.
    """
    required = resolve_required_checks(jobs, required_names)

    # Any job (required or not) that definitively failed — used both to fail the
    # run and to explain skipped required checks (gated-behind-a-failed-job).
    failed_jobs = [
        str(j.get("name", ""))
        for j in jobs
        if str(j.get("status", "")) == "completed"
        and (j.get("conclusion") or "") in FAILING_CONCLUSIONS
    ]

    # A required check that itself concluded a failing conclusion is a hard fail
    # the moment we see it — no need to wait for the whole run.
    required_failures = [
        rc.name
        for rc in required.values()
        if rc.status == "completed" and (rc.conclusion or "") in FAILING_CONCLUSIONS
    ]
    if required_failures:
        return Classification(
            result=RESULT_FAILED,
            required=required,
            failing_jobs=required_failures,
            reason="required check(s) concluded failing: " + ", ".join(required_failures),
        )

    run_complete = run_status == "completed"
    if not run_complete:
        # Still running; we only declare a verdict when the run is terminal,
        # except for the explicit required-failure shortcut above.
        return Classification(result=None, required=required, reason="in progress")

    # ── Run is complete. ─────────────────────────────────────────────────────
    if (run_conclusion or "") in FAILING_CONCLUSIONS:
        # Whole-run conclusion is failing (e.g. cancelled). Name any failed jobs.
        return Classification(
            result=RESULT_FAILED,
            required=required,
            failing_jobs=failed_jobs or [run_conclusion or "run"],
            reason=f"run concluded {run_conclusion}",
        )

    # Run reports success. Validate the required checks individually.
    not_passing = [
        rc
        for rc in required.values()
        if not _required_ok(rc, any_job_failed=bool(failed_jobs))
    ]
    if not_passing:
        # A required check is skipped-due-to-a-failed-gate, missing, or otherwise
        # not green even though the run says success — surface the gating failure.
        names = [rc.name for rc in not_passing]
        return Classification(
            result=RESULT_FAILED,
            required=required,
            failing_jobs=failed_jobs or names,
            reason=(
                "run complete but required check(s) not passing: "
                + ", ".join(names)
                + (
                    f" (gated behind failed job(s): {', '.join(failed_jobs)})"
                    if failed_jobs
                    else ""
                )
            ),
        )

    return Classification(
        result=RESULT_GREEN,
        required=required,
        reason="all required checks passed",
    )


def _required_ok(rc: RequiredCheck, any_job_failed: bool) -> bool:
    """Is a single required check acceptable for a green verdict?

    - completed/success → ok.
    - completed/skipped → ok ONLY if nothing else in the run failed. A skipped
      check whose gating job failed is NOT ok (surface the gate failure).
    - completed/<failing> → not ok (handled earlier, defensive here).
    - missing (no matching job) → ok ONLY if nothing else failed; a missing
      required check on an otherwise-green run usually means a renamed/optional
      job, but on a run with a failed job it likely means it was gated away.
    """
    if rc.status != "completed":
        if rc.status == "missing":
            return not any_job_failed
        return False
    conclusion = rc.conclusion or ""
    if conclusion == "success":
        return True
    if conclusion == "skipped":
        return not any_job_failed
    return False


# ── The watcher (drives the loop, injectable clock + gh + io) ────────────────


@dataclass
class WatchOutcome:
    result: str
    exit_code: int
    required: dict
    failing_jobs: list[str]
    signature: Optional[str]
    likely_infra: bool
    reason: str
    run_id: Optional[str]
    polls: int
    elapsed_s: float

    def to_json(self) -> dict:
        return {
            "result": self.result,
            "exit_code": self.exit_code,
            "run_id": self.run_id,
            "required": {
                name: {"status": rc.status, "conclusion": rc.conclusion}
                for name, rc in self.required.items()
            },
            "failing_job": (self.failing_jobs[0] if self.failing_jobs else None),
            "failing_jobs": self.failing_jobs,
            "signature": self.signature,
            "likely_infra": self.likely_infra,
            "reason": self.reason,
            "polls": self.polls,
            "elapsed_s": round(self.elapsed_s, 1),
        }


class Watcher:
    def __init__(
        self,
        gh: GhRunner,
        *,
        repo: Optional[str] = None,
        required_checks: Optional[list[str]] = None,
        interval_s: float = DEFAULT_INTERVAL_S,
        no_progress_timeout_s: float = DEFAULT_NO_PROGRESS_TIMEOUT_S,
        max_wall_clock_s: float = DEFAULT_MAX_WALL_CLOCK_S,
        clock: Callable[[], float] = time.monotonic,
        sleep: Callable[[float], None] = time.sleep,
        heartbeat: Optional[Callable[[str], None]] = None,
        notice: Optional[Callable[[str], None]] = None,
    ) -> None:
        self.gh = gh
        self.repo = repo
        self.required_checks = list(required_checks or DEFAULT_REQUIRED_CHECKS)
        self.interval_s = interval_s
        self.no_progress_timeout_s = no_progress_timeout_s
        self.max_wall_clock_s = max_wall_clock_s
        self._clock = clock
        self._sleep = sleep
        # heartbeat → log file / verbose; notice → terse stdout on state change.
        self._heartbeat = heartbeat or (lambda _msg: None)
        self._notice = notice or (lambda _msg: None)

    # -- gh helpers with retry --------------------------------------------

    def _gh_json(self, args: list[str]):
        """Run a `gh ... --json ...` call, retrying transient errors.

        Raises GhError after the retry budget is exhausted or on a definitive
        not-found. Never crashes on partial/garbage JSON — that counts as a
        transient and is retried, then surfaces as GhError.
        """
        last_err = ""
        repo_args = ["--repo", self.repo] if self.repo else []
        for attempt in range(1, GH_RETRY_BUDGET + 1):
            try:
                res = self.gh.run(args + repo_args)
            except Exception as exc:  # subprocess timeout / OSError
                last_err = f"{type(exc).__name__}: {exc}"
                self._heartbeat(f"gh call raised ({last_err}), attempt {attempt}")
                self._sleep(GH_RETRY_BACKOFF_S * attempt)
                continue
            if res.returncode != 0:
                last_err = (res.stderr or res.stdout or "").strip()
                # A definitive "not found" should not be retried into a hang.
                if _is_not_found(last_err):
                    raise GhError(f"not found: {last_err}")
                self._heartbeat(
                    f"gh exit {res.returncode} ({last_err[:120]}), attempt {attempt}"
                )
                self._sleep(GH_RETRY_BACKOFF_S * attempt)
                continue
            try:
                return json.loads(res.stdout)
            except (json.JSONDecodeError, ValueError) as exc:
                last_err = f"bad JSON: {exc}"
                self._heartbeat(f"gh returned unparseable JSON, attempt {attempt}")
                self._sleep(GH_RETRY_BACKOFF_S * attempt)
                continue
        raise GhError(last_err or "gh call failed after retries")

    def _gh_text(self, args: list[str]) -> str:
        """Run a `gh` call expected to return plain text (logs). Best-effort.

        Returns "" on any failure — signature capture is advisory and must never
        change the exit code.
        """
        repo_args = ["--repo", self.repo] if self.repo else []
        for attempt in range(1, GH_RETRY_BUDGET + 1):
            try:
                res = self.gh.run(args + repo_args)
            except Exception:
                self._sleep(GH_RETRY_BACKOFF_S * attempt)
                continue
            if res.returncode == 0:
                return res.stdout
            self._sleep(GH_RETRY_BACKOFF_S * attempt)
        return ""

    # -- resolution -------------------------------------------------------

    def resolve_run_id(self, branch: str, workflow: Optional[str]) -> Optional[str]:
        """Resolve the latest run id for a branch (+ optional workflow)."""
        args = [
            "run",
            "list",
            "--branch",
            branch,
            "--limit",
            "20",
            "--json",
            "databaseId,name,workflowName,headBranch,status,createdAt",
        ]
        try:
            runs = self._gh_json(args)
        except GhError as exc:
            self._heartbeat(f"resolve failed: {exc}")
            return None
        if not isinstance(runs, list):
            return None
        candidates = []
        for r in runs:
            if workflow:
                names = {str(r.get("workflowName", "")), str(r.get("name", ""))}
                if workflow not in names and not any(workflow in n for n in names):
                    continue
            candidates.append(r)
        if not candidates:
            return None
        # gh returns newest-first; take the first match.
        rid = candidates[0].get("databaseId")
        return str(rid) if rid is not None else None

    def _poll_run(self, run_id: str) -> dict:
        return self._gh_json(
            ["run", "view", run_id, "--json", "status,conclusion,jobs,databaseId"]
        )

    # -- signature capture ------------------------------------------------

    def capture_signature(self, jobs: list[dict]) -> Optional[str]:
        """Pull the first failed job's failed-step log and extract a signature."""
        for j in jobs:
            if str(j.get("status", "")) == "completed" and (
                (j.get("conclusion") or "") in FAILING_CONCLUSIONS
            ):
                job_id = j.get("databaseId") or j.get("id")
                if job_id is None:
                    continue
                log_text = self._gh_text(
                    ["run", "view", "--job", str(job_id), "--log-failed"]
                )
                if not log_text:
                    # Some runs don't expose --log-failed; fall back to full log.
                    log_text = self._gh_text(
                        ["run", "view", "--job", str(job_id), "--log"]
                    )
                sig = extract_signature(log_text)
                if sig:
                    return sig
        return None

    # -- the loop ---------------------------------------------------------

    def watch(
        self,
        *,
        run_id: Optional[str] = None,
        branch: Optional[str] = None,
        workflow: Optional[str] = None,
    ) -> WatchOutcome:
        start = self._clock()

        if run_id is None:
            if not branch:
                return self._unresolved(
                    "no --run-id and no --branch given", None, start, 0
                )
            run_id = self.resolve_run_id(branch, workflow)
            if run_id is None:
                return self._unresolved(
                    f"could not resolve a run for branch={branch!r} "
                    f"workflow={workflow!r}",
                    None,
                    start,
                    0,
                )

        last_state_key: Optional[tuple] = None
        last_progress_at = start
        polls = 0

        while True:
            now = self._clock()
            # Wall-clock cap first — even a steadily-progressing-but-endless run
            # must terminate.
            if now - start >= self.max_wall_clock_s:
                return self._hang(
                    f"exceeded --max-wall-clock ({self.max_wall_clock_s:.0f}s)",
                    run_id,
                    start,
                    polls,
                    last_required={},
                )

            try:
                data = self._poll_run(run_id)
            except GhError as exc:
                # gh stayed broken past the retry budget → unresolved, don't loop.
                return self._unresolved(f"gh unavailable: {exc}", run_id, start, polls)

            polls += 1
            jobs = data.get("jobs") or []
            run_status = str(data.get("status", ""))
            run_conclusion = data.get("conclusion") or None

            state_key = job_state_key(jobs)
            if state_key != last_state_key:
                last_state_key = state_key
                last_progress_at = now
                self._notice(_progress_line(run_status, jobs))
            self._heartbeat(_progress_line(run_status, jobs, poll=polls))

            verdict = classify_run(
                run_status, run_conclusion, jobs, self.required_checks
            )
            if verdict.result is not None:
                return self._finalize(verdict, jobs, run_id, start, polls)

            # No-progress timeout (catches queued-forever / no-runner too).
            if now - last_progress_at >= self.no_progress_timeout_s:
                return self._hang(
                    "no job-state progress for "
                    f"--no-progress-timeout ({self.no_progress_timeout_s:.0f}s); "
                    f"run still {run_status or 'unknown'}",
                    run_id,
                    start,
                    polls,
                    last_required=verdict.required,
                )

            self._sleep(self.interval_s)

    # -- outcome builders -------------------------------------------------

    def _finalize(self, verdict, jobs, run_id, start, polls) -> WatchOutcome:
        signature = None
        likely_infra = False
        if verdict.result == RESULT_FAILED:
            signature = self.capture_signature(jobs)
            likely_infra = is_likely_infra(signature)
        return WatchOutcome(
            result=verdict.result,
            exit_code=EXIT_CODE[verdict.result],
            required=verdict.required,
            failing_jobs=verdict.failing_jobs,
            signature=signature,
            likely_infra=likely_infra,
            reason=verdict.reason,
            run_id=run_id,
            polls=polls,
            elapsed_s=self._clock() - start,
        )

    def _hang(self, reason, run_id, start, polls, last_required) -> WatchOutcome:
        return WatchOutcome(
            result=RESULT_HANG,
            exit_code=EXIT_CODE[RESULT_HANG],
            required=last_required,
            failing_jobs=[],
            signature=None,
            likely_infra=False,
            reason=reason,
            run_id=run_id,
            polls=polls,
            elapsed_s=self._clock() - start,
        )

    def _unresolved(self, reason, run_id, start, polls) -> WatchOutcome:
        return WatchOutcome(
            result=RESULT_UNRESOLVED,
            exit_code=EXIT_CODE[RESULT_UNRESOLVED],
            required={},
            failing_jobs=[],
            signature=None,
            likely_infra=False,
            reason=reason,
            run_id=run_id,
            polls=polls,
            elapsed_s=self._clock() - start,
        )


def _is_not_found(stderr: str) -> bool:
    low = stderr.lower()
    return (
        "could not find any workflow run" in low
        or "no runs found" in low
        or "404" in low
        or "not found" in low
    )


def _progress_line(run_status: str, jobs: list[dict], poll: Optional[int] = None) -> str:
    counts: dict[str, int] = {}
    for j in jobs:
        st = str(j.get("status", "")) or "?"
        if st == "completed":
            st = f"completed/{j.get('conclusion') or '?'}"
        counts[st] = counts.get(st, 0) + 1
    summary = ", ".join(f"{n}×{st}" for st, n in sorted(counts.items())) or "no jobs yet"
    prefix = f"[poll {poll}] " if poll is not None else ""
    return f"{prefix}run={run_status or 'unknown'} | {summary}"


# ── Human summary rendering ──────────────────────────────────────────────────


def render_human_summary(outcome: WatchOutcome) -> str:
    lines: list[str] = []
    headline = {
        RESULT_GREEN: "GREEN — all required checks passed",
        RESULT_FAILED: "FAILED — a required check failed",
        RESULT_HANG: "HANG — watcher stopped itself (no progress / wall-clock)",
        RESULT_UNRESOLVED: "UNRESOLVED — could not watch the run",
    }[outcome.result]
    lines.append(f"watch-ci: {headline}")
    if outcome.run_id:
        lines.append(
            f"  run: {outcome.run_id}  ({outcome.polls} polls, {outcome.elapsed_s:.0f}s)"
        )
    lines.append(f"  reason: {outcome.reason}")
    if outcome.required:
        for name, rc in outcome.required.items():
            concl = rc.conclusion or "-"
            lines.append(f"  required: {name}: {rc.status}/{concl}")
    if outcome.failing_jobs:
        lines.append(f"  failing job(s): {', '.join(outcome.failing_jobs)}")
    if outcome.signature:
        tag = " [likely infra/flake — re-run]" if outcome.likely_infra else ""
        lines.append(f"  signature: {outcome.signature}{tag}")
    return "\n".join(lines)


# ── CLI ──────────────────────────────────────────────────────────────────────


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="watch-ci.py",
        description=(
            "Token-free, hang-proof GitHub Actions run watcher. Runs ONCE, blocks "
            "while the run is in flight (zero LLM tokens while waiting), then prints "
            "a compact summary + a final JSON line. Exit: 0 green / 1 real-fail / "
            "2 hang / 3 unresolved."
        ),
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    g = p.add_mutually_exclusive_group(required=True)
    g.add_argument("--run-id", help="GitHub Actions run id to watch directly.")
    g.add_argument(
        "--branch",
        help="Resolve the latest run on this branch (use with --workflow).",
    )
    p.add_argument(
        "--workflow",
        help="Workflow name to disambiguate when resolving by --branch (e.g. 'Tests').",
    )
    p.add_argument("--repo", help="owner/name; defaults to the cwd repo.")
    p.add_argument(
        "--interval",
        type=float,
        default=DEFAULT_INTERVAL_S,
        help="Seconds between polls.",
    )
    p.add_argument(
        "--no-progress-timeout",
        type=float,
        default=DEFAULT_NO_PROGRESS_TIMEOUT_S,
        help="Exit 2 (hang) if no job changes state for this many seconds "
        "(also catches queued-forever / no-runner).",
    )
    p.add_argument(
        "--max-wall-clock",
        type=float,
        default=DEFAULT_MAX_WALL_CLOCK_S,
        help="Exit 2 (hang) if the run exceeds this many seconds total.",
    )
    p.add_argument(
        "--required-check",
        action="append",
        dest="required_checks",
        metavar="NAME",
        help="Required check name (repeatable). Defaults to the protected-main "
        "four if omitted.",
    )
    p.add_argument(
        "--log-file",
        help="Write per-poll heartbeats here instead of suppressing them.",
    )
    p.add_argument(
        "--quiet",
        action="store_true",
        help="Suppress the per-state-change stderr notice; only print the final "
        "summary + JSON.",
    )
    return p


def main(argv: Optional[list[str]] = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)

    log_fh = None
    if args.log_file:
        log_fh = open(args.log_file, "a", encoding="utf-8")  # noqa: SIM115

    def heartbeat(msg: str) -> None:
        if log_fh is not None:
            log_fh.write(f"{time.strftime('%H:%M:%S')} {msg}\n")
            log_fh.flush()

    def notice(msg: str) -> None:
        if not args.quiet:
            print(msg, file=sys.stderr, flush=True)

    watcher = Watcher(
        GhRunner(),
        repo=args.repo,
        required_checks=args.required_checks,
        interval_s=args.interval,
        no_progress_timeout_s=args.no_progress_timeout,
        max_wall_clock_s=args.max_wall_clock,
        heartbeat=heartbeat,
        notice=notice,
    )

    try:
        outcome = watcher.watch(
            run_id=args.run_id,
            branch=args.branch,
            workflow=args.workflow,
        )
    finally:
        if log_fh is not None:
            log_fh.close()

    # Compact, token-cheap output: human summary then the final JSON line.
    print(render_human_summary(outcome))
    print(json.dumps(outcome.to_json(), sort_keys=True))
    return outcome.exit_code


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
