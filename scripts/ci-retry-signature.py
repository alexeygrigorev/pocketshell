#!/usr/bin/env python3
"""scripts/ci-retry-signature.py — issue #2459.

Computes a bounded-retry failure signature for ONE workflow run: the sorted
set of top-level job NAMES that concluded `failure`, plus (for jobs whose
android-test-report artifact matches `--journey-artifact-prefix`) the sorted
set of failing `Class#method` test signatures inside it. This is the "which
jobs failed, and for the Emulator/Extensive journey jobs specifically, which
test classes failed" comparison scripts/ci-retry-classify.sh needs to tell a
G5 infra/flake retry apart from a confirmed regression.

Deliberately reuses `emit_from_xml` from scripts/nightly-failure-recurrence.py
(imported by file path, unmodified) for the JUnit-XML-to-`Class#method`
normalization, instead of re-implementing test-signature parsing a second
time — the exact vocabulary issue #2459 asks this to extend rather than
duplicate. The artifacts this reads are the raw
`emulator-journey-android-test-reports-shard-*` / `nightly-extensive-android-
test-reports-shard-*` artifacts both workflows already upload per shard, no
workflow YAML change required to produce them.

FAIL-OPEN, ALWAYS. Any `gh` failure (job listing, artifact listing, artifact
download, XML parse) degrades the affected portion of the signature rather
than crashing this script or the caller's job — a retry-signature capture
must never itself turn a red run into a workflow-execution failure. Overall
capture health is recorded on a trailing `# status=ok` or
`# status=degraded:<reason>` line so a downstream consumer (ci-retry-
classify.sh) can refuse to trust a degraded capture instead of silently
treating missing data as "no failures".

USAGE
  ci-retry-signature.py --repo OWNER/NAME --run-id ID
    [--journey-artifact-prefix PREFIX]  (default: no class-level lookup)
    [--gh PATH]                         (default: gh)
    [--workdir DIR]                     (default: a fresh temp dir)
    [--out FILE]                        (default: stdout only)

OUTPUT (stdout, and to --out FILE when given):
  job:<job name>            one line per failed top-level job, sorted
  class:<Class#method>      one line per failing journey test, sorted
  # status=ok|degraded:<reason>

Self-test: scripts/test-ci-retry-signature.sh
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent


def _load_nightly_failure_recurrence():
    spec = importlib.util.spec_from_file_location(
        "pocketshell_nightly_failure_recurrence",
        SCRIPT_DIR / "nightly-failure-recurrence.py",
    )
    if spec is None or spec.loader is None:  # pragma: no cover - defensive
        raise RuntimeError("cannot load scripts/nightly-failure-recurrence.py")
    module = importlib.util.module_from_spec(spec)
    # Registered in sys.modules under its own name BEFORE exec: the target
    # module uses @dataclass, whose machinery looks up
    # sys.modules[cls.__module__] while the class body executes, and raises
    # if the module is not registered yet.
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def gh_api(gh_bin: str, repo_path: str) -> tuple[dict | list | None, str]:
    result = subprocess.run(
        [gh_bin, "api", repo_path],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return None, (result.stderr.strip() or result.stdout.strip() or "gh api failed")
    try:
        return json.loads(result.stdout), ""
    except json.JSONDecodeError as error:
        return None, f"unparsable gh api response: {error}"


def failed_job_names(gh_bin: str, repo: str, run_id: str) -> tuple[list[str], str]:
    """Returns (sorted failed job names, '' on success | error reason)."""
    data, err = gh_api(gh_bin, f"repos/{repo}/actions/runs/{run_id}/jobs?per_page=100")
    if err:
        return [], err
    jobs = data.get("jobs", []) if isinstance(data, dict) else []
    names = sorted(
        {job.get("name", "") for job in jobs if job.get("conclusion") == "failure" and job.get("name")}
    )
    return names, ""


def journey_artifact_names(gh_bin: str, repo: str, run_id: str, prefix: str) -> tuple[list[str], str]:
    if not prefix:
        return [], ""
    data, err = gh_api(gh_bin, f"repos/{repo}/actions/runs/{run_id}/artifacts?per_page=100")
    if err:
        return [], err
    artifacts = data.get("artifacts", []) if isinstance(data, dict) else []
    names = sorted(
        {a.get("name", "") for a in artifacts if a.get("name", "").startswith(prefix)}
    )
    return names, ""


def download_artifact(gh_bin: str, repo: str, run_id: str, name: str, dest_dir: Path) -> bool:
    dest_dir.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(
        [gh_bin, "run", "download", str(run_id), "--repo", repo, "-n", name, "-D", str(dest_dir)],
        check=False,
        capture_output=True,
        text=True,
    )
    return result.returncode == 0


def failing_journey_classes(
    gh_bin: str,
    repo: str,
    run_id: str,
    prefix: str,
    workdir: Path,
) -> tuple[list[str], bool]:
    """Returns (sorted 'Class#method' signatures, degraded).

    `degraded` is True only when the ARTIFACT LISTING call itself failed —
    an individual shard artifact that fails to download, or an artifact with
    no XML inside it, best-effort-skips instead of degrading the whole
    capture (a shard with genuinely zero failing tests looks the same as a
    shard whose artifact could not be fetched, and biasing toward
    "signature differs" -> infra rather than a false regression confirmation
    is the intentional, safety-first asymmetry here).
    """
    names, err = journey_artifact_names(gh_bin, repo, run_id, prefix)
    if err:
        return [], True
    if not names:
        return [], False

    nfr = _load_nightly_failure_recurrence()
    signatures: set[str] = set()
    for name in names:
        dest = workdir / name
        if not download_artifact(gh_bin, repo, run_id, name, dest):
            continue
        try:
            found = nfr.emit_from_xml(dest)
        except nfr.RecurrenceError:
            continue
        signatures.update(found.keys())
    return sorted(signatures), False


def build_signature(
    gh_bin: str,
    repo: str,
    run_id: str,
    journey_artifact_prefix: str,
    workdir: Path,
) -> str:
    lines: list[str] = []
    jobs, job_err = failed_job_names(gh_bin, repo, run_id)
    for name in jobs:
        lines.append(f"job:{name}")

    class_degraded = False
    if journey_artifact_prefix:
        classes, class_degraded = failing_journey_classes(
            gh_bin, repo, run_id, journey_artifact_prefix, workdir
        )
        for sig in classes:
            lines.append(f"class:{sig}")

    if job_err:
        status = f"degraded:job listing failed ({job_err})"
    elif class_degraded:
        status = "degraded:journey artifact listing failed"
    else:
        status = "ok"
    lines.append(f"# status={status}")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--journey-artifact-prefix", default="")
    parser.add_argument("--gh", default="gh")
    parser.add_argument("--workdir", type=Path, default=None)
    parser.add_argument("--out", type=Path, default=None)
    args = parser.parse_args(argv)

    workdir = args.workdir
    cleanup = False
    if workdir is None:
        workdir = Path(tempfile.mkdtemp(prefix="ci-retry-signature-"))
        cleanup = True

    signature = build_signature(args.gh, args.repo, args.run_id, args.journey_artifact_prefix, workdir)

    sys.stdout.write(signature)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(signature, encoding="utf-8")

    if cleanup:
        import shutil

        shutil.rmtree(workdir, ignore_errors=True)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
