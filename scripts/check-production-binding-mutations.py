#!/usr/bin/python3 -I
"""Curated production-binding mutation harness (issue #1932).

Gates already prove many policies. They do not prove that a high-risk DI /
composition binding still connects that policy to the shipped entry point. A
constructor edit can keep the policy suite green while bypassing the intended
owner. This harness is the periodic (nightly) backstop for a *small reviewed
manifest* of those bindings.

It is not a generic source rewriter and it does not run the mutation universe.

What it checks
--------------
Each manifest entry names one production binding, one load-bearing proof, and
one deterministic mutation. ``--run`` (nightly):

1. fails closed if a required high-risk site is unregistered or an anchor is
   missing/ambiguous (stale);
2. runs every proof unmutated (baseline must stay green);
3. applies ONE mutant at a time, requires the mutant to be live (the file
   actually changed) and the named proof to fail (killed);
4. restores the file and fails if the mutant remains;
5. reports per-binding evidence, wall time, and artifact size, and fails if
   those bounds are exceeded or a binding is skipped (no silent truncate).

``--self-test`` (Unit) plants fixtures for every one of those properties,
including a copy of today's nightly workflow *without* this lane, so a
decorative or missing job cannot pass as protection.

``--check-sites`` (Unit) is the cheap per-push half: stale/unregistered
targets fail without applying mutants or launching Gradle.

Usage
-----
  scripts/check-production-binding-mutations.py --self-test
  scripts/check-production-binding-mutations.py --check-sites
  scripts/check-production-binding-mutations.py --run
  scripts/check-production-binding-mutations.py --prove <id>
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Mapping


SCRIPT_PATH = Path(__file__).resolve()
DEFAULT_ROOT = SCRIPT_PATH.parent.parent
DEFAULT_MANIFEST = SCRIPT_PATH.parent / "production-binding-manifest.json"
DEFAULT_WORKFLOW = DEFAULT_ROOT / ".github" / "workflows" / "nightly-extensive.yml"
DEFAULT_ARTIFACTS = DEFAULT_ROOT / "artifacts" / "production-binding-mutations"
HARNESS_REL = "scripts/check-production-binding-mutations.py"
RUN_INVOCATION = f"{HARNESS_REL} --run"
SELFTEST_INVOCATION = f"{HARNESS_REL} --self-test"
EXPECTED_SELFTEST_CHECKS = 21
PLACEHOLDER_RE = re.compile(r"\{([a-z_]+)\}")
JOB_KEY = re.compile(r"^  ([A-Za-z0-9_-]+):[ \t]*(#.*)?$")
COMMENT_LINE = re.compile(r"^[ \t]*#")


class HarnessFailure(ValueError):
    """The manifest, tree, proof, or nightly attendance contract is broken."""


@dataclass(frozen=True)
class ProofSpec:
    kind: str
    argv: tuple[str, ...] = ()
    task: str = ""
    tests: str = ""
    timeout_seconds: int = 0

    def describe(self) -> str:
        if self.kind == "gradle":
            return f"gradle {self.task} --tests {self.tests}"
        return "command " + " ".join(self.argv)


@dataclass(frozen=True)
class RequiredSite:
    ident: str
    path: str
    needle: str


@dataclass(frozen=True)
class Binding:
    ident: str
    binding: str
    path: str
    anchor: str
    mutation: str
    proof: ProofSpec


@dataclass(frozen=True)
class Manifest:
    issue: int
    epic: int
    max_runtime_seconds: int
    max_artifact_bytes: int
    required_sites: tuple[RequiredSite, ...]
    bindings: tuple[Binding, ...]

    def binding(self, ident: str) -> Binding:
        for item in self.bindings:
            if item.ident == ident:
                return item
        raise HarnessFailure(f"unregistered binding {ident!r}")


@dataclass
class ProofResult:
    returncode: int
    output: str
    timed_out: bool
    truncated: bool

    @property
    def completed(self) -> bool:
        return not self.timed_out and not self.truncated

    @property
    def passed(self) -> bool:
        return self.completed and self.returncode == 0


@dataclass
class BindingEvidence:
    ident: str
    live: bool
    killed: bool
    restored: bool
    baseline_passed: bool
    detail: str
    log_name: str


def fail(message: str) -> None:
    raise HarnessFailure(message)


def code_only(text: str) -> str:
    lines = []
    for line in text.splitlines():
        stripped = line.lstrip()
        if stripped.startswith("//"):
            continue
        lines.append(line)
    return "\n".join(lines)


def strip_yaml_comments(text: str) -> str:
    return "\n".join(line for line in text.splitlines() if not COMMENT_LINE.match(line))


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"manifest {path} is unreadable: {error}")


def require_str(payload: Mapping[str, Any], key: str, where: str) -> str:
    value = payload.get(key)
    if not isinstance(value, str) or not value.strip():
        fail(f"{where} must name {key}")
    return value


def require_int(payload: Mapping[str, Any], key: str, where: str, minimum: int) -> int:
    value = payload.get(key)
    if not isinstance(value, int) or isinstance(value, bool) or value < minimum:
        fail(f"{where} must set {key} to an integer >= {minimum}")
    return value


def parse_proof(payload: Mapping[str, Any], where: str) -> ProofSpec:
    if not isinstance(payload, Mapping):
        fail(f"{where} must name one existing load-bearing proof")
    kind = payload.get("type")
    timeout = payload.get("timeout_seconds", 0)
    if timeout is None:
        timeout = 0
    if not isinstance(timeout, int) or isinstance(timeout, bool) or timeout < 0:
        fail(f"{where}.proof.timeout_seconds must be a non-negative integer")
    if kind == "gradle":
        task = require_str(payload, "task", f"{where}.proof")
        tests = require_str(payload, "tests", f"{where}.proof")
        return ProofSpec(kind="gradle", task=task, tests=tests, timeout_seconds=timeout)
    if kind == "command":
        argv = payload.get("argv")
        if not isinstance(argv, list) or not argv or not all(isinstance(item, str) and item for item in argv):
            fail(f"{where}.proof.argv must be a non-empty argv list")
        return ProofSpec(kind="command", argv=tuple(argv), timeout_seconds=timeout)
    fail(f"{where} must name one existing load-bearing proof")


def parse_manifest(payload: Any) -> Manifest:
    if not isinstance(payload, Mapping):
        fail("manifest must be a JSON object")
    issue = require_int(payload, "issue", "manifest", 1)
    epic = require_int(payload, "epic", "manifest", 1)
    max_runtime = require_int(payload, "max_runtime_seconds", "manifest", 1)
    max_artifact = require_int(payload, "max_artifact_bytes", "manifest", 1)
    raw_sites = payload.get("required_sites")
    raw_bindings = payload.get("bindings")
    if not isinstance(raw_sites, list) or not raw_sites:
        fail("manifest must list required_sites")
    if not isinstance(raw_bindings, list) or not raw_bindings:
        fail("manifest must list bindings")
    sites: list[RequiredSite] = []
    for index, item in enumerate(raw_sites):
        where = f"required_sites[{index}]"
        if not isinstance(item, Mapping):
            fail(f"{where} must be an object")
        sites.append(
            RequiredSite(
                ident=require_str(item, "id", where),
                path=require_str(item, "path", where),
                needle=require_str(item, "needle", where),
            )
        )
    bindings: list[Binding] = []
    seen: set[str] = set()
    for index, item in enumerate(raw_bindings):
        where = f"bindings[{index}]"
        if not isinstance(item, Mapping):
            fail(f"{where} must be an object")
        ident = require_str(item, "id", where)
        if ident in seen:
            fail(f"duplicate binding id {ident!r}")
        seen.add(ident)
        bindings.append(
            Binding(
                ident=ident,
                binding=require_str(item, "binding", where),
                path=require_str(item, "path", where),
                anchor=require_str(item, "anchor", where),
                mutation=require_str(item, "mutation", where),
                proof=parse_proof(item.get("proof"), where),
            )
        )
    return Manifest(
        issue=issue,
        epic=epic,
        max_runtime_seconds=max_runtime,
        max_artifact_bytes=max_artifact,
        required_sites=tuple(sites),
        bindings=tuple(bindings),
    )


def load_manifest(path: Path) -> Manifest:
    return parse_manifest(load_json(path))


def expand_argv(argv: tuple[str, ...], mapping: Mapping[str, str]) -> list[str]:
    expanded: list[str] = []
    for item in argv:
        def replacer(match: re.Match[str], item: str = item) -> str:
            key = match.group(1)
            if key not in mapping:
                fail(f"proof argv {item!r} has unknown placeholder {{{key}}}")
            return mapping[key]

        expanded.append(PLACEHOLDER_RE.sub(replacer, item))
    return expanded


def count_anchor(text: str, anchor: str) -> int:
    if not anchor:
        return 0
    return text.count(anchor)


def resolve_source(root: Path, relative: str) -> Path:
    path = (root / relative).resolve()
    if root.resolve() not in path.parents and path != root.resolve():
        fail(f"refusing path outside root: {relative}")
    return path


def read_source(root: Path, relative: str) -> str:
    path = resolve_source(root, relative)
    if not path.is_file():
        fail(f"missing/stale manifest target: {relative} does not exist")
    return path.read_text(encoding="utf-8")


def validate_binding_target(root: Path, binding: Binding) -> None:
    text = read_source(root, binding.path)
    hits = count_anchor(text, binding.anchor)
    if hits == 0:
        fail(
            f"missing/stale manifest target {binding.ident}: "
            f"anchor not found in {binding.path}"
        )
    if hits != 1:
        fail(
            f"missing/stale manifest target {binding.ident}: "
            f"anchor matched {hits} times in {binding.path} (need exactly 1)"
        )
    if binding.anchor == binding.mutation:
        fail(f"{binding.ident} mutation is identical to its anchor (would be a no-op)")


def collect_needle_hits(root: Path, needle: str) -> list[str]:
    hits: list[str] = []
    search_roots = [
        root / "app" / "src" / "main",
        root / "shared",
        root / "src" / "main",
    ]
    seen: set[Path] = set()
    for base in search_roots:
        if not base.is_dir():
            continue
        for path in base.rglob("*.kt"):
            if path in seen or any(part in {"build", ".git", "artifacts"} for part in path.parts):
                continue
            seen.add(path)
            if "/src/main/" not in path.as_posix():
                continue
            try:
                relative = path.relative_to(root).as_posix()
            except ValueError:
                continue
            text = code_only(path.read_text(encoding="utf-8"))
            if needle in text:
                hits.append(relative)
    return hits


def validate_sites(root: Path, manifest: Manifest) -> None:
    if not manifest.bindings:
        fail("manifest must list bindings")
    for binding in manifest.bindings:
        validate_binding_target(root, binding)
    covered: set[str] = set()
    for site in manifest.required_sites:
        source = read_source(root, site.path)
        if site.needle not in code_only(source):
            fail(
                f"missing/stale manifest target {site.ident}: "
                f"needle not found in {site.path}"
            )
        owners = [
            binding
            for binding in manifest.bindings
            if binding.path == site.path and site.needle in binding.anchor
        ]
        if not owners:
            fail(
                f"unregistered binding {site.ident}: required site "
                f"{site.path} needle is not covered by any manifest entry"
            )
        covered.add(site.ident)
        extras = collect_needle_hits(root, site.needle)
        unexpected = [path for path in extras if path != site.path]
        if unexpected:
            fail(
                f"unregistered binding {site.ident}: extra production hits "
                f"at {', '.join(unexpected)}"
            )
    uncovered = [site.ident for site in manifest.required_sites if site.ident not in covered]
    if uncovered:
        fail(f"unregistered binding(s): {', '.join(uncovered)}")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def apply_mutation(path: Path, binding: Binding) -> bytes:
    original = path.read_bytes()
    text = original.decode("utf-8")
    hits = count_anchor(text, binding.anchor)
    if hits != 1:
        fail(
            f"{binding.ident}: mutation did not see exactly one anchor "
            f"(matched {hits})"
        )
    mutated = text.replace(binding.anchor, binding.mutation, 1)
    if mutated == text:
        fail(f"{binding.ident}: mutant was a no-op (file unchanged)")
    if binding.anchor in mutated:
        fail(f"{binding.ident}: mutant left the original anchor in place")
    if binding.mutation not in mutated:
        fail(f"{binding.ident}: mutant text is not present after apply")
    path.write_text(mutated, encoding="utf-8")
    return original


def restore_source(path: Path, original: bytes, ident: str) -> None:
    path.write_bytes(original)
    if path.read_bytes() != original:
        fail(f"{ident}: mutant remains in the tree after restore")


def gradle_argv(root: Path, proof: ProofSpec, *, rerun: bool) -> list[str]:
    gradlew = root / "gradlew"
    if not gradlew.is_file():
        fail(f"gradlew is missing under {root}")
    argv = [
        str(gradlew),
        proof.task,
        "--tests",
        proof.tests,
        "--console=plain",
        "--no-daemon",
        "--stacktrace",
    ]
    if rerun:
        argv.extend(["--rerun-tasks", "--no-build-cache"])
    return argv


def run_process(
    argv: list[str],
    *,
    cwd: Path,
    timeout: int | None,
    extra_env: Mapping[str, str] | None = None,
) -> ProofResult:
    env = os.environ.copy()
    if extra_env:
        env.update(extra_env)
    try:
        completed = subprocess.run(
            argv,
            cwd=str(cwd),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=timeout,
            env=env,
            check=False,
        )
    except subprocess.TimeoutExpired as error:
        output = error.stdout or ""
        if isinstance(output, bytes):
            output = output.decode("utf-8", errors="replace")
        return ProofResult(returncode=124, output=output, timed_out=True, truncated=False)
    output = completed.stdout or ""
    truncated = bool(output) and not output.endswith("\n") and len(output) > 0 and "\n" not in output[-200:]
    return ProofResult(
        returncode=completed.returncode,
        output=output,
        timed_out=False,
        truncated=False if completed.returncode in (0, 1) else truncated,
    )


def run_proof(
    root: Path,
    binding: Binding,
    *,
    harness: Path,
    manifest_path: Path,
    rerun: bool,
    timeout_override: int | None = None,
) -> ProofResult:
    timeout = timeout_override
    if timeout is None:
        timeout = binding.proof.timeout_seconds or None
    if binding.proof.kind == "gradle":
        argv = gradle_argv(root, binding.proof, rerun=rerun)
    else:
        argv = expand_argv(
            binding.proof.argv,
            {
                "python": sys.executable,
                "harness": str(harness),
                "root": str(root),
                "manifest": str(manifest_path),
            },
        )
        if argv[0] == "{python}":
            fail("proof argv placeholder was not expanded")
    return run_process(argv, cwd=root, timeout=timeout)


def directory_size(path: Path) -> int:
    if not path.exists():
        return 0
    total = 0
    for child in path.rglob("*"):
        if child.is_file():
            total += child.stat().st_size
    return total


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def format_summary(
    manifest: Manifest,
    evidence: list[BindingEvidence],
    *,
    runtime_seconds: float,
    artifact_bytes: int,
    status: str,
    expected: int,
) -> str:
    lines = [
        f"# Production-binding mutations (#{manifest.issue} / #{manifest.epic})",
        "",
        f"- status: `{status}`",
        f"- runtime_seconds: `{runtime_seconds:.1f}` / max `{manifest.max_runtime_seconds}`",
        f"- artifact_bytes: `{artifact_bytes}` / max `{manifest.max_artifact_bytes}`",
        f"- bindings: `{len(evidence)}/{expected}`",
        "",
        "| binding | baseline | live | killed | restored | detail |",
        "|---|---|---|---|---|---|",
    ]
    for item in evidence:
        lines.append(
            f"| `{item.ident}` | {'GREEN' if item.baseline_passed else 'RED'} | "
            f"{'yes' if item.live else 'no'} | {'yes' if item.killed else 'no'} | "
            f"{'yes' if item.restored else 'no'} | {item.detail} |"
        )
    lines.append("")
    return "\n".join(lines)


def extract_job(workflow: str, job_name: str) -> str:
    lines = workflow.splitlines()
    starts = [
        index
        for index, line in enumerate(lines)
        if (match := JOB_KEY.match(line)) and match.group(1) == job_name
    ]
    if len(starts) != 1:
        fail(f"expected exactly one {job_name!r} job, found {len(starts)}")
    start = starts[0]
    end = next(
        (
            index
            for index in range(start + 1, len(lines))
            if JOB_KEY.match(lines[index])
        ),
        len(lines),
    )
    return "\n".join(lines[start:end])


def validate_attendance(workflow: str) -> None:
    cleaned = strip_yaml_comments(workflow)
    if HARNESS_REL not in cleaned:
        fail(
            "nightly workflow does not invoke the production-binding mutation "
            "harness --run (the periodic lane is missing or decorative)"
        )
    if RUN_INVOCATION not in cleaned:
        if SELFTEST_INVOCATION in cleaned:
            fail(
                "nightly job only runs --self-test (decorative); the periodic "
                "lane must invoke --run"
            )
        fail(
            "nightly workflow does not invoke the production-binding mutation "
            "harness --run (the periodic lane is missing or decorative)"
        )
    if "production-binding-mutations" not in cleaned:
        fail("nightly workflow does not publish production-binding-mutations evidence")
    if "GITHUB_STEP_SUMMARY" not in cleaned:
        fail("nightly workflow does not surface binding-mutation attendance on the step summary")
    job_name = None
    job = ""
    for line in workflow.splitlines():
        match = JOB_KEY.match(line)
        if not match:
            continue
        block = extract_job(workflow, match.group(1))
        if RUN_INVOCATION in strip_yaml_comments(block):
            job_name = match.group(1)
            job = block
            break
    if job_name is None:
        fail("nightly workflow has no job that runs the binding-mutation harness --run")
    cleaned_job = strip_yaml_comments(job)
    if re.search(r"continue-on-error:\s*true", cleaned_job):
        fail(
            f"nightly job {job_name!r} is continue-on-error; a surviving mutant "
            "would not fail the lane"
        )
    if "--self-test" in cleaned_job and RUN_INVOCATION not in cleaned_job:
        fail(f"nightly job {job_name!r} only runs --self-test (decorative)")
    if "if: always()" not in job or "upload-artifact" not in job:
        fail(
            f"nightly job {job_name!r} must upload binding-mutation artifacts "
            "with if: always() so a red run cannot hide its evidence"
        )
    if "GITHUB_STEP_SUMMARY" not in job:
        fail(f"nightly job {job_name!r} must append its summary to GITHUB_STEP_SUMMARY")


def prove_binding(root: Path, manifest: Manifest, ident: str) -> int:
    binding = manifest.binding(ident)
    raw = read_source(root, binding.path)
    if binding.anchor in code_only(raw):
        print(f"PROOF PASS {ident}: production binding is present")
        return 0
    print(f"PROOF FAIL {ident}: production binding is absent", file=sys.stderr)
    return 1


def check_sites(root: Path, manifest: Manifest) -> None:
    validate_sites(root, manifest)
    print(f"PASS: {len(manifest.bindings)} production-binding targets are present and registered")


def run_lane(
    root: Path,
    manifest: Manifest,
    *,
    workflow: Path,
    artifacts: Path,
    harness: Path,
    manifest_path: Path,
    only: str | None,
    proof_timeout: int | None = None,
) -> int:
    if workflow.is_file():
        validate_attendance(workflow.read_text(encoding="utf-8"))
    validate_sites(root, manifest)
    selected = list(manifest.bindings)
    if only:
        selected = [manifest.binding(only)]
    artifacts.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    evidence: list[BindingEvidence] = []
    status = "PASS"
    fatal: str | None = None

    try:
        for binding in selected:
            elapsed = time.monotonic() - started
            if elapsed > manifest.max_runtime_seconds:
                fatal = (
                    f"runtime bound exceeded before {binding.ident}: "
                    f"{elapsed:.1f}s > {manifest.max_runtime_seconds}s "
                    f"(ran {len(evidence)} of {len(selected)}; refusing to truncate)"
                )
                break
            if directory_size(artifacts) > manifest.max_artifact_bytes:
                fatal = (
                    f"artifact bound exceeded before {binding.ident}: "
                    f"{directory_size(artifacts)} > {manifest.max_artifact_bytes} "
                    f"(ran {len(evidence)} of {len(selected)}; refusing to truncate)"
                )
                break
            source_path = resolve_source(root, binding.path)
            baseline = run_proof(
                root,
                binding,
                harness=harness,
                manifest_path=manifest_path,
                rerun=True,
                timeout_override=proof_timeout,
            )
            write_text(artifacts / f"{binding.ident}.baseline.log", baseline.output)
            if not baseline.completed:
                item = BindingEvidence(
                    ident=binding.ident,
                    live=False,
                    killed=False,
                    restored=True,
                    baseline_passed=False,
                    detail="baseline proof did not finish (timeout/truncate)",
                    log_name=f"{binding.ident}.baseline.log",
                )
                evidence.append(item)
                fatal = f"{binding.ident}: baseline proof did not finish"
                break
            if not baseline.passed:
                evidence.append(
                    BindingEvidence(
                        ident=binding.ident,
                        live=False,
                        killed=False,
                        restored=True,
                        baseline_passed=False,
                        detail=f"baseline RED (rc={baseline.returncode})",
                        log_name=f"{binding.ident}.baseline.log",
                    )
                )
                fatal = f"{binding.ident}: baseline is not green"
                break
            original = b""
            live = False
            killed = False
            restored = False
            detail = ""
            try:
                original = apply_mutation(source_path, binding)
                live = True
                mutant = run_proof(
                    root,
                    binding,
                    harness=harness,
                    manifest_path=manifest_path,
                    rerun=False,
                    timeout_override=proof_timeout,
                )
                write_text(artifacts / f"{binding.ident}.mutant.log", mutant.output)
                if not mutant.completed:
                    detail = "mutant proof did not finish (timeout/truncate is not a kill)"
                elif mutant.passed:
                    detail = "mutant SURVIVED (proof stayed green)"
                else:
                    killed = True
                    detail = f"LIVE+KILLED (proof rc={mutant.returncode})"
            finally:
                if original:
                    restore_source(source_path, original, binding.ident)
                    restored = True
                    if sha256_bytes(source_path.read_bytes()) != sha256_bytes(original):
                        restored = False
                        detail = "mutant remains in the tree after restore"
            evidence.append(
                BindingEvidence(
                    ident=binding.ident,
                    live=live,
                    killed=killed,
                    restored=restored,
                    baseline_passed=True,
                    detail=detail,
                    log_name=f"{binding.ident}.mutant.log",
                )
            )
            if not (live and killed and restored):
                fatal = f"{binding.ident}: {detail or 'mutant was not live and killed'}"
                break
    except HarnessFailure as error:
        fatal = str(error)
        status = "FAIL"

    runtime = time.monotonic() - started
    artifact_bytes = directory_size(artifacts)
    if fatal:
        status = "FAIL"
    if runtime > manifest.max_runtime_seconds and status == "PASS":
        fatal = (
            f"runtime bound exceeded: {runtime:.1f}s > {manifest.max_runtime_seconds}s"
        )
        status = "FAIL"
    if artifact_bytes > manifest.max_artifact_bytes:
        fatal = (
            f"artifact bound exceeded: {artifact_bytes} > {manifest.max_artifact_bytes}"
        )
        status = "FAIL"
    if len(evidence) != len(selected) and fatal is None:
        fatal = f"truncated: ran {len(evidence)} of {len(selected)} bindings"
        status = "FAIL"
    summary = format_summary(
        manifest,
        evidence,
        runtime_seconds=runtime,
        artifact_bytes=artifact_bytes,
        status=status if fatal is None else "FAIL",
        expected=len(selected),
    )
    if fatal:
        summary += f"\nFAIL: {fatal}\n"
    write_text(artifacts / "summary.md", summary)
    write_text(
        artifacts / "summary.json",
        json.dumps(
            {
                "issue": manifest.issue,
                "epic": manifest.epic,
                "status": "FAIL" if fatal else "PASS",
                "runtime_seconds": runtime,
                "max_runtime_seconds": manifest.max_runtime_seconds,
                "artifact_bytes": artifact_bytes,
                "max_artifact_bytes": manifest.max_artifact_bytes,
                "bindings_expected": [item.ident for item in selected],
                "bindings_ran": [item.ident for item in evidence],
                "fatal": fatal,
            },
            indent=2,
        )
        + "\n",
    )
    sys.stdout.write(summary)
    if fatal:
        print(f"FAIL: {fatal}", file=sys.stderr)
        return 1
    print(
        f"PASS: {len(evidence)} curated binding mutants were live and killed "
        f"({runtime:.1f}s, {artifact_bytes} bytes)"
    )
    return 0


def make_executable(path: Path) -> None:
    path.chmod(path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


def write_fixture_tree(root: Path) -> dict[str, Path]:
    sources = {
        "src/main/prod/Display.kt": (
            "class Display {\n"
            "    val bind = onControllerTransition { projectStatusFromController() }\n"
            "}\n"
        ),
        "src/main/prod/Network.kt": (
            "class Network {\n"
            "    fun tick() {\n"
            "        connectionManager.observeNetworkChanged(\n"
            "            validatedHandoff = true,\n"
            "        )\n"
            "    }\n"
            "}\n"
        ),
        "src/main/prod/Assistant.kt": (
            "class Assistant(\n"
            "    private val leaseManager: Any = SharedSshLeaseManager.get(),\n"
            ")\n"
        ),
        "src/main/prod/Outbound.kt": (
            "object Factory {\n"
            "    fun boundTo() = Controller(budget = composer.outboundAttemptBudget)\n"
            "}\n"
        ),
    }
    for relative, text in sources.items():
        write_text(root / relative, text)
    proof_dir = root / "proofs"
    proof_dir.mkdir(parents=True, exist_ok=True)
    healthy = proof_dir / "healthy.sh"
    healthy.write_text(
        "#!/bin/sh\n"
        "needle=$1\n"
        "file=$2\n"
        "if grep -F \"$needle\" \"$file\" >/dev/null; then echo PROOF_DONE; exit 0; fi\n"
        "echo PROOF_DONE; exit 1\n",
        encoding="utf-8",
    )
    make_executable(healthy)
    always_pass = proof_dir / "always-pass.sh"
    always_pass.write_text("#!/bin/sh\necho PROOF_DONE\nexit 0\n", encoding="utf-8")
    make_executable(always_pass)
    always_fail = proof_dir / "always-fail.sh"
    always_fail.write_text("#!/bin/sh\necho PROOF_DONE\nexit 1\n", encoding="utf-8")
    make_executable(always_fail)
    hang = proof_dir / "hang.sh"
    hang.write_text("#!/bin/sh\nsleep 30\necho PROOF_DONE\nexit 1\n", encoding="utf-8")
    make_executable(hang)
    return {
        "healthy": healthy,
        "always_pass": always_pass,
        "always_fail": always_fail,
        "hang": hang,
    }


def fixture_binding(
    ident: str,
    path: str,
    anchor: str,
    mutation: str,
    proof: Path,
    needle: str,
) -> dict[str, Any]:
    return {
        "id": ident,
        "binding": ident,
        "path": path,
        "anchor": anchor,
        "mutation": mutation,
        "proof": {
            "type": "command",
            "argv": ["sh", str(proof), needle, path],
            "timeout_seconds": 2,
        },
    }


def fixture_manifest(root: Path, proofs: Mapping[str, Path], **overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "issue": 1932,
        "epic": 1671,
        "max_runtime_seconds": 20,
        "max_artifact_bytes": 65536,
        "required_sites": [
            {
                "id": "controller-display-projection",
                "path": "src/main/prod/Display.kt",
                "needle": "onControllerTransition { projectStatusFromController() }",
            },
            {
                "id": "assistant-lease-capability",
                "path": "src/main/prod/Assistant.kt",
                "needle": "SharedSshLeaseManager.get()",
            },
        ],
        "bindings": [
            fixture_binding(
                "controller-display-projection",
                "src/main/prod/Display.kt",
                "onControllerTransition { projectStatusFromController() }",
                "onControllerTransition { }",
                proofs["healthy"],
                "onControllerTransition { projectStatusFromController() }",
            ),
            fixture_binding(
                "assistant-lease-capability",
                "src/main/prod/Assistant.kt",
                "SharedSshLeaseManager.get()",
                "SshLeaseManager()",
                proofs["healthy"],
                "SharedSshLeaseManager.get()",
            ),
        ],
    }
    payload.update(overrides)
    return payload


def write_manifest(path: Path, payload: Mapping[str, Any]) -> Path:
    write_text(path, json.dumps(payload, indent=2) + "\n")
    return path


NIGHTLY_WITHOUT_LANE = """name: Nightly Extensive Tests
jobs:
  guard:
    name: Guard
    steps:
      - name: Decide
        run: echo skip
  extensive:
    name: Extensive
    steps:
      - name: Suite
        run: scripts/nightly-extensive-suite.sh
"""

NIGHTLY_SELFTEST_ONLY = """name: Nightly Extensive Tests
jobs:
  binding-mutations:
    name: Decorative
    steps:
      - name: Self-test only
        run: scripts/check-production-binding-mutations.py --self-test
      - name: Upload
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: production-binding-mutations
          path: artifacts/production-binding-mutations/
      - name: Summary
        if: always()
        run: cat artifacts/production-binding-mutations/summary.md >> "$GITHUB_STEP_SUMMARY"
"""

NIGHTLY_CONTINUE_ON_ERROR = """name: Nightly Extensive Tests
jobs:
  binding-mutations:
    name: Soft fail
    continue-on-error: true
    steps:
      - name: Run mutants
        run: scripts/check-production-binding-mutations.py --run
      - name: Upload
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: production-binding-mutations
          path: artifacts/production-binding-mutations/
      - name: Summary
        if: always()
        run: cat artifacts/production-binding-mutations/summary.md >> "$GITHUB_STEP_SUMMARY"
"""

NIGHTLY_HEALTHY = """name: Nightly Extensive Tests
jobs:
  binding-mutations:
    name: Production-binding mutations (issue #1932)
    steps:
      - name: Run curated binding mutants
        run: scripts/check-production-binding-mutations.py --run
      - name: Publish summary
        if: always()
        run: cat artifacts/production-binding-mutations/summary.md >> "$GITHUB_STEP_SUMMARY"
      - name: Upload evidence
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: production-binding-mutations
          path: artifacts/production-binding-mutations/
"""


def self_test() -> None:
    checks = 0

    def accepted(name: str, action: Callable[[], object]) -> None:
        nonlocal checks
        try:
            action()
        except Exception as error:  # pragma: no cover - diagnostic
            raise HarnessFailure(f"self-test expected {name} to pass: {error}") from error
        checks += 1

    def rejected(name: str, expected: str, action: Callable[[], object]) -> None:
        nonlocal checks
        try:
            action()
        except HarnessFailure as error:
            if expected not in str(error):
                raise HarnessFailure(
                    f"self-test {name} failed for the wrong reason: {error}; "
                    f"expected {expected!r}"
                ) from error
        else:
            raise HarnessFailure(f"self-test accepted unsafe mutation: {name}")
        checks += 1

    def run_rc(root: Path, manifest_path: Path, *args: str, workflow: Path | None = None) -> int:
        argv = [
            sys.executable,
            str(SCRIPT_PATH),
            "--root",
            str(root),
            "--manifest",
            str(manifest_path),
            "--artifacts",
            str(root / "artifacts" / "production-binding-mutations"),
        ]
        if workflow is not None:
            argv.extend(["--workflow", str(workflow)])
        else:
            argv.extend(["--workflow", str(root / "missing-workflow.yml")])
        argv.extend(args)
        result = run_process(argv, cwd=root, timeout=20)
        if result.output:
            (root / "harness.out").write_text(result.output, encoding="utf-8")
        return result.returncode

    rejected(
        "missing nightly lane (current main)",
        "harness --run",
        lambda: validate_attendance(NIGHTLY_WITHOUT_LANE),
    )
    rejected(
        "self-test-only nightly job",
        "decorative",
        lambda: validate_attendance(NIGHTLY_SELFTEST_ONLY),
    )
    rejected(
        "continue-on-error nightly job",
        "continue-on-error",
        lambda: validate_attendance(NIGHTLY_CONTINUE_ON_ERROR),
    )
    accepted("healthy nightly attendance", lambda: validate_attendance(NIGHTLY_HEALTHY))

    with tempfile.TemporaryDirectory(prefix="issue-1932-selftest-") as temp:
        root = Path(temp)
        proofs = write_fixture_tree(root)
        manifest_path = write_manifest(root / "manifest.json", fixture_manifest(root, proofs))
        accepted("fixture sites", lambda: check_sites(root, load_manifest(manifest_path)))

        incomplete = fixture_manifest(root, proofs)
        del incomplete["bindings"][0]["proof"]
        rejected(
            "manifest missing proof",
            "load-bearing proof",
            lambda: parse_manifest(incomplete),
        )
        incomplete = fixture_manifest(root, proofs)
        del incomplete["bindings"][0]["mutation"]
        rejected(
            "manifest missing mutation",
            "mutation",
            lambda: parse_manifest(incomplete),
        )
        incomplete = fixture_manifest(root, proofs)
        del incomplete["bindings"][0]["binding"]
        rejected(
            "manifest missing binding prose",
            "binding",
            lambda: parse_manifest(incomplete),
        )

        stale = fixture_manifest(root, proofs)
        stale["bindings"][0]["anchor"] = "this-anchor-is-not-in-the-tree"
        rejected(
            "stale missing anchor",
            "missing/stale",
            lambda: validate_sites(root, parse_manifest(stale)),
        )
        ambiguous = fixture_manifest(root, proofs)
        display = (root / "src" / "main" / "prod" / "Display.kt")
        display.write_text(display.read_text(encoding="utf-8") * 2, encoding="utf-8")
        rejected(
            "stale ambiguous anchor",
            "matched 2 times",
            lambda: validate_sites(root, parse_manifest(ambiguous)),
        )
        display.write_text(
            "class Display {\n"
            "    val bind = onControllerTransition { projectStatusFromController() }\n"
            "}\n",
            encoding="utf-8",
        )

        extra = root / "src" / "main" / "prod" / "AssistantCopy.kt"
        extra.write_text(
            "class Other { val x = SharedSshLeaseManager.get() }\n",
            encoding="utf-8",
        )
        rejected(
            "unregistered extra production hit",
            "unregistered binding",
            lambda: validate_sites(root, load_manifest(manifest_path)),
        )
        extra.unlink()

        uncovered = fixture_manifest(root, proofs)
        uncovered["required_sites"].append(
            {
                "id": "orphan-site",
                "path": "src/main/prod/Display.kt",
                "needle": "class Display",
            }
        )
        rejected(
            "required site with no covering binding",
            "unregistered binding",
            lambda: validate_sites(root, parse_manifest(uncovered)),
        )
        rejected(
            "cli unregistered binding id",
            "unregistered binding",
            lambda: load_manifest(manifest_path).binding("not-in-manifest"),
        )

        noop = fixture_manifest(root, proofs)
        noop["bindings"][0]["mutation"] = noop["bindings"][0]["anchor"]
        rejected(
            "no-op mutant",
            "no-op",
            lambda: validate_sites(root, parse_manifest(noop)),
        )

        workflow = root / "nightly.yml"
        workflow.write_text(NIGHTLY_HEALTHY, encoding="utf-8")
        accepted(
            "baseline green and mutants killed",
            lambda: None
            if run_rc(root, manifest_path, "--run", workflow=workflow) == 0
            else (_ for _ in ()).throw(HarnessFailure((root / "harness.out").read_text())),
        )
        display_text = (root / "src" / "main" / "prod" / "Display.kt").read_text(encoding="utf-8")
        if "projectStatusFromController()" not in display_text:
            fail("healthy --run left a mutant in Display.kt")
        assistant_text = (root / "src" / "main" / "prod" / "Assistant.kt").read_text(encoding="utf-8")
        if "SharedSshLeaseManager.get()" not in assistant_text:
            fail("healthy --run left a mutant in Assistant.kt")
        summary = (root / "artifacts" / "production-binding-mutations" / "summary.md").read_text(
            encoding="utf-8"
        )
        if "runtime_seconds" not in summary or "artifact_bytes" not in summary:
            fail("summary did not report runtime and artifact bounds")
        if "controller-display-projection" not in summary or "LIVE+KILLED" not in summary:
            fail("summary did not report per-binding kill evidence")

        surviving = fixture_manifest(root, proofs)
        surviving["bindings"][0]["proof"]["argv"] = [
            "sh",
            str(proofs["always_pass"]),
            "unused",
            "src/main/prod/Display.kt",
        ]
        surviving_path = write_manifest(root / "surviving.json", surviving)
        shutil.rmtree(root / "artifacts", ignore_errors=True)
        rc = run_rc(root, surviving_path, "--run", workflow=workflow)
        if rc == 0:
            fail("self-test surviving mutant was accepted")
        out = (root / "harness.out").read_text(encoding="utf-8")
        if "SURVIVED" not in out:
            fail(f"surviving mutant did not report SURVIVED: {out}")
        checks += 1

        broken_baseline = fixture_manifest(root, proofs)
        broken_baseline["bindings"][0]["proof"]["argv"] = [
            "sh",
            str(proofs["always_fail"]),
            "unused",
            "src/main/prod/Display.kt",
        ]
        broken_path = write_manifest(root / "baseline-red.json", broken_baseline)
        shutil.rmtree(root / "artifacts", ignore_errors=True)
        rc = run_rc(root, broken_path, "--run", workflow=workflow)
        if rc == 0:
            fail("self-test red baseline was accepted")
        out = (root / "harness.out").read_text(encoding="utf-8")
        if "baseline is not green" not in out and "baseline RED" not in out:
            fail(f"red baseline did not fail closed: {out}")
        checks += 1

        hanging = fixture_manifest(root, proofs)
        hanging["bindings"][0]["proof"]["argv"] = [
            "sh",
            str(proofs["hang"]),
            "unused",
            "src/main/prod/Display.kt",
        ]
        hanging["bindings"][0]["proof"]["timeout_seconds"] = 1
        hang_path = write_manifest(root / "hang.json", hanging)
        shutil.rmtree(root / "artifacts", ignore_errors=True)
        rc = run_rc(root, hang_path, "--run", workflow=workflow)
        if rc == 0:
            fail("self-test timeout was treated as a kill")
        out = (root / "harness.out").read_text(encoding="utf-8")
        if "did not finish" not in out:
            fail(f"timeout was not reported as incomplete: {out}")
        checks += 1

        tiny = fixture_manifest(root, proofs)
        tiny["max_artifact_bytes"] = 40
        tiny_path = write_manifest(root / "tiny-art.json", tiny)
        shutil.rmtree(root / "artifacts", ignore_errors=True)
        rc = run_rc(root, tiny_path, "--run", workflow=workflow)
        if rc == 0:
            fail("self-test oversized artifacts were accepted")
        out = (root / "harness.out").read_text(encoding="utf-8")
        if "artifact bound exceeded" not in out:
            fail(f"artifact bound did not fail closed: {out}")
        checks += 1

        tiny_time = fixture_manifest(root, proofs)
        tiny_time["max_runtime_seconds"] = 1
        tiny_time["bindings"][0]["proof"]["argv"] = [
            "sh",
            str(proofs["hang"]),
            "unused",
            "src/main/prod/Display.kt",
        ]
        tiny_time["bindings"][0]["proof"]["timeout_seconds"] = 2
        time_path = write_manifest(root / "tiny-time.json", tiny_time)
        shutil.rmtree(root / "artifacts", ignore_errors=True)
        rc = run_rc(root, time_path, "--run", workflow=workflow)
        if rc == 0:
            fail("self-test runtime bound was accepted")
        out = (root / "harness.out").read_text(encoding="utf-8")
        if "runtime bound exceeded" not in out and "did not finish" not in out:
            fail(f"runtime bound did not fail closed: {out}")
        checks += 1

        accepted(
            "real nightly attendance",
            lambda: validate_attendance(DEFAULT_WORKFLOW.read_text(encoding="utf-8")),
        )

    if checks != EXPECTED_SELFTEST_CHECKS:
        raise HarnessFailure(
            f"self-test ran {checks} checks, expected {EXPECTED_SELFTEST_CHECKS}"
        )
    print(f"PASS: production-binding mutation harness self-test ({checks} checks)")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--workflow", type=Path, default=DEFAULT_WORKFLOW)
    parser.add_argument("--artifacts", type=Path, default=DEFAULT_ARTIFACTS)
    parser.add_argument("--binding", dest="only", default=None)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--check-sites", action="store_true")
    parser.add_argument("--run", action="store_true")
    parser.add_argument("--prove", metavar="ID")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.self_test:
        self_test()
        return 0
    root = args.root.resolve()

    def resolve_cli_path(value: Path) -> Path:
        if value.is_absolute():
            return value
        return (root / value).resolve()

    manifest_path = resolve_cli_path(args.manifest)
    try:
        manifest = load_manifest(manifest_path)
        if args.prove:
            return prove_binding(root, manifest, args.prove)
        if args.check_sites:
            check_sites(root, manifest)
            return 0
        if args.run:
            artifacts = resolve_cli_path(args.artifacts)
            if args.artifacts == DEFAULT_ARTIFACTS:
                artifacts = root / "artifacts" / "production-binding-mutations"
            workflow = resolve_cli_path(args.workflow)
            return run_lane(
                root,
                manifest,
                workflow=workflow,
                artifacts=artifacts,
                harness=SCRIPT_PATH,
                manifest_path=manifest_path,
                only=args.only,
            )
    except HarnessFailure as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    parser.error("one of --self-test, --check-sites, --run, --prove is required")
    return 2


if __name__ == "__main__":
    sys.exit(main())
