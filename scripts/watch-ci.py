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

3. Informative, and NEVER inventive (issue #1650). On a real failure we capture
   the SIGNATURE ourselves from output that ACTUALLY EXECUTED, and tag known
   infra signatures with `likely_infra: true` so the on-call can classify
   flake-vs-real from this output alone. When we cannot classify confidently we
   say "unclassified failure" and hand over the raw evidence — a FABRICATED
   signature is worse than no signature, because it sends the on-call to the
   wrong issue with false confidence (G5: an infra claim needs a captured
   signature AND a clean re-run).

4. Honest about non-verdicts (issue #1650). A `cancelled` run is NOT a failure.
   `main`'s push concurrency group cancels older in-flight runs when newer
   merges land — routine and BY DESIGN (see process.md). A watcher that cannot
   reach a verdict reports one of the no-verdict codes below; it never invents
   one. This matches the workflow's own classifier, which already states a
   cancelled attempt "is NOT ... a genuine ... regression".

Exit-code contract (also the final JSON's `result`):

    0  green       — all REQUIRED checks concluded `success` (or appropriately
                     skipped) and the run is complete.
    1  failed      — a required check GENUINELY failed (`failure`/`timed_out`/
                     `startup_failure`/`action_required`). A real failure
                     outranks a later cancel: red CI is never softened.
    2  hang         — no job-state progress for --no-progress-timeout, OR the run
                     exceeded --max-wall-clock. Never wait forever.
    3  unresolved  — the run / inputs couldn't be resolved, or `gh` stayed broken
                     past the retry budget.
    4  superseded  — the run was cancelled because a NEWER run for the same
                     workflow+branch replaced it (the `main` concurrency group).
                     Nothing is broken; re-watch the newest head.
    5  no_verdict  — the run was cancelled (user/API, or we could not tell why),
                     so it produced NO trustworthy verdict. Not a pass, not a
                     failure — unknown. Re-run to get a verdict.

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

# Issue #1650: the no-progress guard must out-last the LONGEST single job, since
# a healthy job that runs for its whole duration produces NO job-state change.
# The emulator journey shards are capped at 95 min (`timeout-minutes: 95` in
# .github/workflows/tests.yml) and routinely run 20-40 min. The old 20-min
# default meant EVERY `main` emulator watch reported a bogus HANG while the
# shards were perfectly healthy. Keep this strictly above the job cap, and keep
# the wall-clock cap above it so the no-progress guard can actually fire.
EMULATOR_JOB_CAP_S = 95 * 60  # .github/workflows/tests.yml: timeout-minutes: 95
DEFAULT_NO_PROGRESS_TIMEOUT_S = 100 * 60
DEFAULT_MAX_WALL_CLOCK_S = 3 * 60 * 60
GH_RETRY_BUDGET = 3
GH_RETRY_BACKOFF_S = 3.0

# Result strings <-> exit codes. Single source of truth for the contract.
RESULT_GREEN = "green"
RESULT_FAILED = "failed"
RESULT_HANG = "hang"
RESULT_UNRESOLVED = "unresolved"
RESULT_SUPERSEDED = "superseded"
RESULT_NO_VERDICT = "no_verdict"

EXIT_CODE = {
    RESULT_GREEN: 0,
    RESULT_FAILED: 1,
    RESULT_HANG: 2,
    RESULT_UNRESOLVED: 3,
    RESULT_SUPERSEDED: 4,
    RESULT_NO_VERDICT: 5,
}

# Terminal (completed) conclusions that mean the job GENUINELY failed.
#
# Issue #1650: `cancelled` is deliberately NOT here. A cancel is not a failure —
# on `main` it is the routine, by-design outcome of the push concurrency group
# superseding an older in-flight run. Treating it as a failure made every
# superseded-run watch a guaranteed false FAILED, on the exact path the on-call
# is told to watch. It is handled separately (superseded vs no_verdict) below.
GENUINE_FAILING_CONCLUSIONS = frozenset(
    {"failure", "timed_out", "startup_failure", "action_required"}
)

CANCELLED = "cancelled"

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


# GitHub's "command echo": a `run:` step's own script body is printed back with
# this ANSI wrapper before/while it executes. Such a line is SOURCE TEXT, not
# output — an `echo "::error ..."` inside an `if` that never fired still appears
# here verbatim.
#
# Issue #1650: this is THE fabrication vector. The watcher reported
#   signature: echo "::error title=sdkmanager still broken after repair::...(#771)"
# for a run that was merely superseded — the loose `(Install|installing).*failed`
# pattern coincidentally matched "freshly-installed ... still failed to run"
# inside the ECHOED script text of a conditional that never ran. A grep cannot
# tell "this error occurred" from "this string exists in the script"; refusing
# to read command-echo lines is what makes the difference.
_COMMAND_ECHO_RE = re.compile(r"\x1b\[36;1m")

# Defence in depth for the same vector when the ANSI command-echo marker is not
# present (e.g. a log source that strips ANSI). A REAL annotation is emitted by
# the runner as `##[error]...` — it is NEVER the literal text `echo "::error`.
# So a line that contains an `echo` of an annotation is, unambiguously, a script
# body being printed rather than an error that occurred.
_ECHOED_ANNOTATION_RE = re.compile(r"""\becho\b\s*["']?\s*::(error|warning|notice)""")

# Runner-emitted annotations. Unlike an echoed `::error`, an `##[error]` line is
# written by the runner when something ACTUALLY failed.
_ANNOTATION_RE = re.compile(r"^##\[error\]\s*(?P<msg>.*)$")

# `##[error]` wrappers that carry no diagnostic value — they say "a step exited
# non-zero" without saying why. Never worth reporting as THE signature.
_GENERIC_ANNOTATION_PATTERNS = [
    re.compile(r"^The process '.*' failed with exit code \d+\.?$"),
    re.compile(r"^Process completed with exit code \d+\.?$"),
    re.compile(r"^The operation was canceled\.?$", re.IGNORECASE),
    re.compile(r"^The job running has exceeded", re.IGNORECASE),
]


def _is_command_echo(line: str) -> bool:
    """Is this line GitHub echoing a `run:` step's script body (not output)?"""
    return bool(_COMMAND_ECHO_RE.search(line) or _ECHOED_ANNOTATION_RE.search(line))


def _is_generic_annotation(msg: str) -> bool:
    return any(pat.match(msg) for pat in _GENERIC_ANNOTATION_PATTERNS)


def extract_signature(log_text: str, *, annotations_only: bool = False) -> Optional[str]:
    """Return a signature for a failure from output that ACTUALLY EXECUTED.

    Returns None when nothing can be attributed confidently — the caller then
    reports an "unclassified failure" and hands over the raw evidence. Guessing
    is not allowed (issue #1650): a wrong-but-specific signature is worse than
    none, because the on-call acts on it.

    Order of preference:

    1. A runner-emitted `##[error]` annotation with real diagnostic content.
       This is the strongest anchor: the runner only writes it when a step
       actually failed, so it cannot come from unexecuted script text.
    2. The heuristic error-line patterns, but ONLY over lines that are not
       GitHub command-echo (script body). Skipped entirely when
       `annotations_only` is set.

    `annotations_only=True` is used for the whole-run-log fallback: when we
    could not narrow the log to the FAILED STEP, a loose pattern would happily
    match a line from an unrelated, perfectly successful step (the real #1650
    log had `Error: No such command 'tree'.` printed by a step whose JOB was to
    assert that very mismatch). In that case only a real annotation counts.

    Strips the `gh --log` prefix columns (job\\tstep\\tISO-8601 ...) so the
    returned line is the bare message.
    """
    executed: list[str] = []
    for raw in log_text.splitlines():
        if _is_command_echo(raw):
            continue  # script text, not output — never evidence of anything
        line = _strip_gh_log_prefix(raw).strip()
        if line:
            executed.append(line)

    # 1. A real, runner-emitted annotation wins.
    for line in executed:
        m = _ANNOTATION_RE.match(line)
        if m:
            msg = m.group("msg").strip()
            if msg and not _is_generic_annotation(msg):
                return msg

    if annotations_only:
        return None

    # 2. Heuristic scan over executed output only.
    for line in executed:
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
    # Issue #1650: set when the run reached NO verdict because it was cancelled.
    # The Watcher then probes whether a newer run superseded it, and only then
    # can distinguish RESULT_SUPERSEDED from RESULT_NO_VERDICT. classify_run
    # stays pure (no `gh` calls), so it reports the honest default —
    # RESULT_NO_VERDICT — and lets the caller upgrade it.
    cancelled: bool = False


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

    Cancelled handling (issue #1650): `cancelled` is NOT a failure. A GENUINE
    failure is always checked FIRST and always wins, so a run that genuinely
    broke and was cancelled afterwards still reports RESULT_FAILED — the fix for
    the false-alarm bug must never launder real red CI into "superseded".
    Absent a genuine failure, a cancelled run yields NO verdict.
    """
    required = resolve_required_checks(jobs, required_names)

    # Any job (required or not) that GENUINELY failed — used both to fail the
    # run and to explain skipped required checks (gated-behind-a-failed-job).
    failed_jobs = [
        str(j.get("name", ""))
        for j in jobs
        if str(j.get("status", "")) == "completed"
        and (j.get("conclusion") or "") in GENUINE_FAILING_CONCLUSIONS
    ]

    # A required check that itself genuinely failed is a hard fail the moment we
    # see it — no need to wait for the whole run. Checked BEFORE any cancelled
    # handling so real failures always take precedence (G6).
    required_failures = [
        rc.name
        for rc in required.values()
        if rc.status == "completed"
        and (rc.conclusion or "") in GENUINE_FAILING_CONCLUSIONS
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
        # except for the explicit required-failure shortcut above. A required
        # check that is merely `cancelled` deliberately does NOT shortcut here:
        # we wait for the run so we can tell superseded from user-cancelled.
        return Classification(result=None, required=required, reason="in progress")

    # ── Run is complete. ─────────────────────────────────────────────────────
    if (run_conclusion or "") in GENUINE_FAILING_CONCLUSIONS:
        # Whole-run conclusion is a genuine failure. Name any failed jobs.
        return Classification(
            result=RESULT_FAILED,
            required=required,
            failing_jobs=failed_jobs or [run_conclusion or "run"],
            reason=f"run concluded {run_conclusion}",
        )

    # No genuine failure anywhere. Was anything cancelled? Then this run reached
    # no trustworthy verdict — the caller decides superseded vs no_verdict.
    cancelled_required = [
        rc.name
        for rc in required.values()
        if rc.status == "completed" and (rc.conclusion or "") == CANCELLED
    ]
    if (run_conclusion or "") == CANCELLED or cancelled_required:
        detail = (
            "required check(s) cancelled: " + ", ".join(cancelled_required)
            if cancelled_required
            else f"run concluded {run_conclusion}"
        )
        return Classification(
            result=RESULT_NO_VERDICT,
            required=required,
            failing_jobs=[],
            reason=f"run was cancelled, so it produced no verdict ({detail})",
            cancelled=True,
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
    - completed/<genuinely failing> → not ok (handled earlier, defensive here).
    - completed/cancelled → not ok, but handled earlier as a no-verdict rather
      than a failure (issue #1650).
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
        # headBranch/workflowName/createdAt feed the #1650 supersede probe.
        return self._gh_json(
            [
                "run",
                "view",
                run_id,
                "--json",
                "status,conclusion,jobs,databaseId,headBranch,workflowName,createdAt",
            ]
        )

    # -- signature capture ------------------------------------------------

    def capture_signature(self, jobs: list[dict]) -> Optional[str]:
        """Pull the first GENUINELY-failed job's failed-step log for a signature.

        Only ever called for RESULT_FAILED (issue #1650): a cancelled/superseded
        run has no failure, so producing a signature for it is pure fabrication.

        Anchors to the FAILED STEP's log (`--log-failed`). If that is
        unavailable we fall back to the whole job log but demand a real runner
        annotation (`annotations_only`), because a loose pattern over a full log
        will match text from unrelated, successful steps.
        """
        for j in jobs:
            if str(j.get("status", "")) == "completed" and (
                (j.get("conclusion") or "") in GENUINE_FAILING_CONCLUSIONS
            ):
                job_id = j.get("databaseId") or j.get("id")
                if job_id is None:
                    continue
                log_text = self._gh_text(
                    ["run", "view", "--job", str(job_id), "--log-failed"]
                )
                if log_text:
                    sig = extract_signature(log_text)
                else:
                    # Some runs don't expose --log-failed; fall back to the full
                    # log, but only trust real annotations from it.
                    log_text = self._gh_text(
                        ["run", "view", "--job", str(job_id), "--log"]
                    )
                    sig = extract_signature(log_text, annotations_only=True)
                if sig:
                    return sig
        return None

    # -- superseded probe (issue #1650) -----------------------------------

    def find_newer_run(self, run: dict) -> Optional[str]:
        """Return the id of a newer run for the same workflow+branch, or None.

        This is how we tell "superseded by the `main` push concurrency group"
        (routine, by design) from "somebody cancelled it" — GitHub does not
        expose the cancel REASON, so we ask the only question that matters: did
        a newer run replace this one?

        Returns None when we cannot tell (missing branch, `gh` down, no newer
        run). The caller then reports `no_verdict` rather than guessing.
        """
        branch = str(run.get("headBranch") or "")
        if not branch:
            return None
        workflow = str(run.get("workflowName") or "")
        created_at = str(run.get("createdAt") or "")
        try:
            run_id = int(run.get("databaseId") or 0)
        except (TypeError, ValueError):
            run_id = 0

        try:
            runs = self._gh_json(
                [
                    "run",
                    "list",
                    "--branch",
                    branch,
                    "--limit",
                    "20",
                    "--json",
                    "databaseId,workflowName,headBranch,status,createdAt",
                ]
            )
        except GhError as exc:
            self._heartbeat(f"supersede probe failed: {exc}")
            return None
        if not isinstance(runs, list):
            return None

        for r in runs:
            if workflow and str(r.get("workflowName") or "") != workflow:
                continue
            if str(r.get("headBranch") or "") != branch:
                continue
            try:
                other_id = int(r.get("databaseId") or 0)
            except (TypeError, ValueError):
                continue
            if other_id == run_id:
                continue
            other_created = str(r.get("createdAt") or "")
            # Prefer createdAt (authoritative ordering); fall back to the id,
            # which is monotonic per repo.
            newer = (
                other_created > created_at
                if (other_created and created_at)
                else other_id > run_id
            )
            if newer:
                return str(other_id)
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
                return self._finalize(verdict, jobs, run_id, start, polls, run=data)

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

    def _finalize(self, verdict, jobs, run_id, start, polls, run=None) -> WatchOutcome:
        result = verdict.result
        reason = verdict.reason
        signature = None
        likely_infra = False

        # A signature is ONLY ever captured for a genuine failure. A cancelled /
        # superseded run has no failure to diagnose, so any signature attached
        # to it is a fabrication (issue #1650).
        if result == RESULT_FAILED:
            signature = self.capture_signature(jobs)
            likely_infra = is_likely_infra(signature)
        elif verdict.cancelled:
            newer = self.find_newer_run(run or {})
            if newer:
                result = RESULT_SUPERSEDED
                reason = (
                    "superseded — a newer run for the same workflow+branch "
                    f"({newer}) replaced this one, so the concurrency group "
                    "cancelled it. This is routine and by design; nothing is "
                    f"broken. Re-watch the newest head: --run-id {newer}"
                )
            else:
                reason = (
                    f"{reason}. No newer run found on the branch, so this was "
                    "not superseded by the concurrency group (user/API cancel, "
                    "or the probe could not tell). No verdict is available — "
                    "re-run to get one. This is NOT a failure."
                )

        return WatchOutcome(
            result=result,
            exit_code=EXIT_CODE[result],
            required=verdict.required,
            failing_jobs=verdict.failing_jobs,
            signature=signature,
            likely_infra=likely_infra,
            reason=reason,
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
        RESULT_SUPERSEDED: (
            "SUPERSEDED — a newer run replaced this one (routine "
            "concurrency-cancel; NOT a failure). Re-watch the newest head."
        ),
        RESULT_NO_VERDICT: (
            "NO VERDICT — the run was cancelled, so it never reached a verdict "
            "(NOT a failure, NOT a pass). Re-run to get one."
        ),
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
    elif outcome.result == RESULT_FAILED:
        # Issue #1650: never guess. Say so and hand over the raw evidence — a
        # fabricated signature sends the on-call to the wrong issue with false
        # confidence, which is worse than admitting we could not classify it.
        lines.append(
            "  signature: none — unclassified failure (could not attribute it to "
            "executed error output; read the log yourself, do NOT assume infra)"
        )
        if outcome.failing_jobs:
            lines.append(
                "  evidence:  gh run view --log-failed --job <id>  # "
                f"job: {outcome.failing_jobs[0]}"
            )
    return "\n".join(lines)


# ── CLI ──────────────────────────────────────────────────────────────────────


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="watch-ci.py",
        description=(
            "Token-free, hang-proof GitHub Actions run watcher. Runs ONCE, blocks "
            "while the run is in flight (zero LLM tokens while waiting), then prints "
            "a compact summary + a final JSON line. Exit: 0 green / 1 real-fail / "
            "2 hang / 3 unresolved / 4 superseded (a newer run replaced this one — "
            "routine on main, NOT a failure) / 5 no-verdict (cancelled, so nothing "
            "was decided)."
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
