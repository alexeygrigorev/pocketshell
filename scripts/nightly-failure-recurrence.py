#!/usr/bin/env python3
"""Issue #1924: bounded nightly failure-signature recurrence.

Consumes only the small per-run ``failure-signatures.tsv`` artifact (never
historical Android report archives). Normalizes by stable ``Class#method``
and reports count, first_seen, last_seen, and current streak.

Missing pre-rollout artifacts are ``history unavailable``, never zero
occurrences. History/report failures stay off emulator phase execution and
off the #1201 release-fault verdict.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable
from xml.etree import ElementTree
from xml.sax.saxutils import unescape as xml_unescape

DEFAULT_WINDOW = 14
ARTIFACT_NAME = "nightly-failure-signatures"
TSV_FILENAME = "failure-signatures.tsv"
UNAVAILABLE_MARKER = "HISTORY_UNAVAILABLE"
FORBIDDEN_ARTIFACT_NEEDLES = ("android-test-reports", "docker-logs")
DEVICE_SUFFIX_RE = re.compile(r"\[[^\]]*\]\s*$")
WORKFLOW_FILE = "nightly-extensive.yml"


class RecurrenceError(RuntimeError):
    """Current-run evidence is unusable; fail closed."""


@dataclass
class RunRecord:
    run_id: str
    date: str
    sha: str
    status: str  # observed | unavailable | corrupt
    signatures: dict[str, str] = field(default_factory=dict)

    @property
    def observed(self) -> bool:
        return self.status == "observed"


@dataclass
class RecurrenceRow:
    signature: str
    count: int
    first_seen: str
    last_seen: str
    streak: int
    history: str


@dataclass
class RecurrenceReport:
    window: int
    runs: list[RunRecord]
    rows: list[RecurrenceRow]
    history_status: str
    unavailable_runs: int


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def strip_device_suffix(classname: str) -> str:
    return DEVICE_SUFFIX_RE.sub("", classname).strip()


def signature_of(classname: str, name: str) -> str | None:
    cls = strip_device_suffix(xml_unescape(classname or "")).strip()
    method = xml_unescape(name or "").strip()
    if not cls or not method:
        return None
    if method.startswith(cls + "#"):
        return method
    if "#" in method and method.rsplit("#", 1)[0] == cls:
        return method
    return f"{cls}#{method}"


def first_message(node: ElementTree.Element) -> str:
    text = "".join(node.itertext())
    text = xml_unescape(text).replace("\r\n", "\n").replace("\r", "\n")
    for line in text.splitlines():
        stripped = " ".join(line.split())
        if stripped:
            return stripped
    return ""


def iter_xml_files(root: Path) -> list[Path]:
    files: set[Path] = set()
    if root.is_file() and root.suffix == ".xml":
        files.add(root.resolve())
        return sorted(files)
    if not root.is_dir():
        raise RecurrenceError(f"xml root does not exist: {root}")
    for path in root.rglob("*.xml"):
        if not path.is_file():
            continue
        if path.name.startswith("TEST-") or "androidTest-results" in path.parts:
            files.add(path.resolve())
    return sorted(files)


def emit_from_xml(xml_root: Path) -> dict[str, str]:
    files = iter_xml_files(xml_root)
    if not files:
        raise RecurrenceError(f"no JUnit XML under {xml_root}")
    signatures: dict[str, str] = {}
    parsed = 0
    for path in files:
        try:
            tree_root = ElementTree.parse(path).getroot()
        except (OSError, ElementTree.ParseError) as error:
            raise RecurrenceError(f"could not parse {path}: {error}") from error
        parsed += 1
        for testcase in (node for node in tree_root.iter() if local_name(node.tag) == "testcase"):
            children = [child for child in list(testcase) if local_name(child.tag) in {"failure", "error"}]
            if not children:
                continue
            sig = signature_of(testcase.get("classname", ""), testcase.get("name", ""))
            if not sig:
                continue
            if sig not in signatures:
                signatures[sig] = first_message(children[0])
    if parsed == 0:
        raise RecurrenceError(f"parsed zero XML files under {xml_root}")
    return signatures


def parse_header_meta(lines: Iterable[str]) -> dict[str, str]:
    meta: dict[str, str] = {}
    for line in lines:
        if not line.startswith("#"):
            break
        body = line[1:].strip()
        if "=" not in body:
            continue
        key, _, value = body.partition("=")
        key = key.strip()
        if key in {"sha", "date", "run_id"}:
            meta[key] = value.strip()
    return meta


def parse_tsv(path: Path) -> RunRecord:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as error:
        raise RecurrenceError(f"cannot read {path}: {error}") from error
    lines = text.splitlines()
    meta = parse_header_meta(lines)
    signatures: dict[str, str] = {}
    body_started = False
    for line in lines:
        if line.startswith("#") and not body_started:
            continue
        if not line.strip():
            continue
        body_started = True
        if line.startswith("signature\t"):
            continue
        parts = line.split("\t", 1)
        sig = parts[0].strip()
        if not sig or sig.startswith("#"):
            continue
        if "#" not in sig:
            return RunRecord(
                run_id=meta.get("run_id", path.parent.name),
                date=meta.get("date", ""),
                sha=meta.get("sha", ""),
                status="corrupt",
            )
        if sig not in signatures:
            signatures[sig] = parts[1] if len(parts) > 1 else ""
    run_id = meta.get("run_id") or path.parent.name
    date = meta.get("date", "")
    if not date:
        return RunRecord(run_id=run_id, date="", sha=meta.get("sha", ""), status="corrupt")
    return RunRecord(
        run_id=run_id,
        date=date,
        sha=meta.get("sha", ""),
        status="observed",
        signatures=signatures,
    )


def write_tsv(record: RunRecord, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# pocketshell-failure-signatures v1",
        f"# sha={record.sha}",
        f"# date={record.date}",
        f"# run_id={record.run_id}",
        "signature\tmessage",
    ]
    for sig in sorted(record.signatures):
        message = record.signatures[sig].replace("\t", " ").replace("\n", " ")
        lines.append(f"{sig}\t{message}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def merge_records(records: list[RunRecord], meta: RunRecord) -> RunRecord:
    signatures: dict[str, str] = {}
    for record in records:
        if not record.observed:
            continue
        for sig, message in record.signatures.items():
            signatures.setdefault(sig, message)
    return RunRecord(
        run_id=meta.run_id,
        date=meta.date,
        sha=meta.sha,
        status="observed",
        signatures=signatures,
    )


def load_history_dir(history_dir: Path) -> list[RunRecord]:
    if not history_dir.is_dir():
        raise RecurrenceError(f"history dir does not exist: {history_dir}")
    records: list[RunRecord] = []
    for child in sorted(history_dir.iterdir()):
        if not child.is_dir():
            continue
        unavailable = child / UNAVAILABLE_MARKER
        tsv = child / TSV_FILENAME
        if unavailable.is_file() and not tsv.is_file():
            meta_text = unavailable.read_text(encoding="utf-8", errors="replace")
            meta = parse_header_meta(meta_text.splitlines())
            records.append(
                RunRecord(
                    run_id=meta.get("run_id", child.name),
                    date=meta.get("date", "1970-01-01"),
                    sha=meta.get("sha", ""),
                    status="unavailable",
                )
            )
            continue
        if tsv.is_file():
            records.append(parse_tsv(tsv))
            continue
        records.append(
            RunRecord(run_id=child.name, date="1970-01-01", sha="", status="unavailable")
        )
    return records


def sort_newest_first(runs: list[RunRecord]) -> list[RunRecord]:
    return sorted(runs, key=lambda run: (run.date, run.run_id), reverse=True)


def compute_recurrence(runs: list[RunRecord], window: int) -> RecurrenceReport:
    ordered = sort_newest_first(runs)
    # Load-bearing window slice. Self-test mutates this assignment to ignore
    # history (newest observed run only); that mutant must redden count=3.
    windowed = list(ordered[:window])
    unavailable = sum(1 for run in windowed if not run.observed)
    observed = [run for run in windowed if run.observed]
    if not windowed:
        history_status = "unavailable"
    elif unavailable == len(windowed):
        history_status = "unavailable"
    elif unavailable:
        history_status = "partial"
    else:
        history_status = "complete"

    signatures: dict[str, str] = {}
    for run in observed:
        for sig, message in run.signatures.items():
            signatures.setdefault(sig, message)

    rows: list[RecurrenceRow] = []
    history_flag = "history unavailable" if history_status != "complete" else "observed"
    for sig in sorted(signatures):
        hits = [run for run in observed if sig in run.signatures]
        if not hits:
            continue
        count = len(hits)
        first_seen = min(run.date for run in hits)
        last_seen = max(run.date for run in hits)
        streak = 0
        for run in windowed:
            if not run.observed:
                continue
            if sig in run.signatures:
                streak += 1
            else:
                break
        rows.append(
            RecurrenceRow(
                signature=sig,
                count=count,
                first_seen=first_seen,
                last_seen=last_seen,
                streak=streak,
                history=history_flag,
            )
        )
    return RecurrenceReport(
        window=window,
        runs=windowed,
        rows=rows,
        history_status=history_status,
        unavailable_runs=unavailable,
    )


def format_report_tsv(report: RecurrenceReport) -> str:
    lines = [
        f"# window={report.window}",
        f"# history_status={report.history_status}",
        f"# unavailable_runs={report.unavailable_runs}",
        f"# window_runs={len(report.runs)}",
        "signature\tcount\tfirst_seen\tlast_seen\tstreak\thistory",
    ]
    for row in report.rows:
        lines.append(
            f"{row.signature}\t{row.count}\t{row.first_seen}\t{row.last_seen}\t"
            f"{row.streak}\t{row.history}"
        )
    return "\n".join(lines) + "\n"


def format_report_markdown(report: RecurrenceReport) -> str:
    lines = [
        "# Nightly failure recurrence",
        "",
        f"- window: `{report.window}` completed nightly runs",
        f"- history_status: `{report.history_status}`",
        f"- unavailable_runs: `{report.unavailable_runs}`",
        f"- window_runs: `{len(report.runs)}`",
        "",
        "Missing pre-rollout artifacts are **history unavailable**, never zero "
        "occurrences. This job does not download historical Android report archives.",
        "",
    ]
    if not report.rows:
        lines.append("No failing `Class#method` signatures in the observed window.")
        lines.append("")
        return "\n".join(lines)
    lines.extend(
        [
            "| signature | count | first_seen | last_seen | streak | history |",
            "| --- | ---: | --- | --- | ---: | --- |",
        ]
    )
    for row in report.rows:
        lines.append(
            f"| `{row.signature}` | {row.count} | {row.first_seen} | "
            f"{row.last_seen} | {row.streak} | {row.history} |"
        )
    lines.append("")
    return "\n".join(lines)


def artifact_name_forbidden(name: str) -> bool:
    lowered = name.lower()
    return any(needle in lowered for needle in FORBIDDEN_ARTIFACT_NEEDLES)


class FixtureArtifactClient:
    """Deterministic stand-in for `gh` used by --self-test and --client-fixture."""

    def __init__(self, payload: dict) -> None:
        self.payload = payload
        self.listed: list[int] = []
        self.downloaded: list[tuple[int, str]] = []

    def list_completed_runs(self, workflow: str, per_page: int) -> list[dict]:
        del workflow, per_page
        return list(self.payload.get("runs", []))

    def list_artifacts(self, run_id: int) -> list[dict]:
        self.listed.append(run_id)
        for run in self.payload.get("runs", []):
            if int(run["id"]) == int(run_id):
                return list(run.get("artifacts", []))
        return []

    def download_artifact(self, run_id: int, name: str, dest_dir: Path) -> bool:
        if artifact_name_forbidden(name):
            raise RecurrenceError(
                f"refusing to download forbidden historical artifact {name!r}"
            )
        self.downloaded.append((int(run_id), name))
        for run in self.payload.get("runs", []):
            if int(run["id"]) != int(run_id):
                continue
            for artifact in run.get("artifacts", []):
                if artifact.get("name") != name:
                    continue
                dest_dir.mkdir(parents=True, exist_ok=True)
                tsv = dest_dir / TSV_FILENAME
                if "tsv" in artifact:
                    tsv.write_text(artifact["tsv"], encoding="utf-8")
                    return True
                if artifact.get("unavailable"):
                    return False
        return False


class GhArtifactClient:
    def __init__(self, repo: str, gh_bin: str = "gh") -> None:
        self.repo = repo
        self.gh_bin = gh_bin
        self.downloaded: list[tuple[int, str]] = []

    def _api(self, path: str) -> dict | list:
        result = subprocess.run(
            [self.gh_bin, "api", path],
            check=False,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            raise RecurrenceError(
                f"gh api {path} failed: {result.stderr.strip() or result.stdout.strip()}"
            )
        return json.loads(result.stdout)

    def list_completed_runs(self, workflow: str, per_page: int) -> list[dict]:
        data = self._api(
            f"repos/{self.repo}/actions/workflows/{workflow}/runs"
            f"?status=completed&per_page={per_page}"
        )
        if isinstance(data, dict):
            return list(data.get("workflow_runs", []))
        return []

    def list_artifacts(self, run_id: int) -> list[dict]:
        data = self._api(f"repos/{self.repo}/actions/runs/{run_id}/artifacts")
        if isinstance(data, dict):
            return list(data.get("artifacts", []))
        return []

    def download_artifact(self, run_id: int, name: str, dest_dir: Path) -> bool:
        if artifact_name_forbidden(name):
            raise RecurrenceError(
                f"refusing to download forbidden historical artifact {name!r}"
            )
        if name != ARTIFACT_NAME:
            raise RecurrenceError(
                f"historical lookup may only download {ARTIFACT_NAME!r}, not {name!r}"
            )
        self.downloaded.append((int(run_id), name))
        dest_dir.mkdir(parents=True, exist_ok=True)
        zip_path = dest_dir / "artifact.zip"
        result = subprocess.run(
            [
                self.gh_bin,
                "run",
                "download",
                str(run_id),
                "--repo",
                self.repo,
                "-n",
                name,
                "-D",
                str(dest_dir),
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            # Fallback: artifact zip via API id if `gh run download` cannot see it.
            artifacts = self.list_artifacts(run_id)
            match = next((item for item in artifacts if item.get("name") == name), None)
            if not match:
                return False
            api_result = subprocess.run(
                [
                    self.gh_bin,
                    "api",
                    f"repos/{self.repo}/actions/artifacts/{match['id']}/zip",
                ],
                check=False,
                capture_output=True,
            )
            if api_result.returncode != 0:
                return False
            zip_path.write_bytes(api_result.stdout)
            with zipfile.ZipFile(zip_path) as archive:
                archive.extractall(dest_dir)
            zip_path.unlink(missing_ok=True)
            return True
        return True


def fetch_history(
    client: FixtureArtifactClient | GhArtifactClient,
    out_dir: Path,
    window: int,
    workflow: str = WORKFLOW_FILE,
    exclude_run_id: str = "",
) -> list[RunRecord]:
    # Over-fetch so guard-skipped (zero-artifact) runs do not consume the window.
    per_page = max(window * 3, window + 5)
    runs = client.list_completed_runs(workflow, per_page=per_page)
    runs = sorted(
        runs,
        key=lambda run: str(run.get("created_at") or run.get("date") or ""),
        reverse=True,
    )
    records: list[RunRecord] = []
    for run in runs:
        run_id = str(run.get("id", ""))
        if not run_id:
            continue
        if exclude_run_id and str(run_id) == str(exclude_run_id):
            continue
        artifacts = client.list_artifacts(int(run_id))
        if not artifacts:
            # Guard-skipped nightly: no suite ran. Do not consume a window slot.
            continue
        created = str(run.get("created_at") or run.get("date") or "")
        date = created[:10] if created else "1970-01-01"
        sha = str(run.get("head_sha") or run.get("sha") or "")
        dest = out_dir / f"run-{run_id}"
        dest.mkdir(parents=True, exist_ok=True)
        has_small = any(item.get("name") == ARTIFACT_NAME for item in artifacts)
        if not has_small:
            marker = dest / UNAVAILABLE_MARKER
            marker.write_text(
                f"# sha={sha}\n# date={date}\n# run_id={run_id}\n",
                encoding="utf-8",
            )
            records.append(
                RunRecord(run_id=run_id, date=date, sha=sha, status="unavailable")
            )
            if len(records) >= window:
                break
            continue
        if client.download_artifact(int(run_id), ARTIFACT_NAME, dest):
            tsv = dest / TSV_FILENAME
            if not tsv.is_file():
                matches = list(dest.rglob(TSV_FILENAME))
                if matches:
                    shutil.copy2(matches[0], tsv)
            if tsv.is_file():
                parsed = parse_tsv(tsv)
                if not parsed.date:
                    parsed.date = date
                if not parsed.sha:
                    parsed.sha = sha
                records.append(parsed)
            else:
                (dest / UNAVAILABLE_MARKER).write_text(
                    f"# sha={sha}\n# date={date}\n# run_id={run_id}\n",
                    encoding="utf-8",
                )
                records.append(
                    RunRecord(run_id=run_id, date=date, sha=sha, status="unavailable")
                )
        else:
            (dest / UNAVAILABLE_MARKER).write_text(
                f"# sha={sha}\n# date={date}\n# run_id={run_id}\n",
                encoding="utf-8",
            )
            records.append(
                RunRecord(run_id=run_id, date=date, sha=sha, status="unavailable")
            )
        if len(records) >= window:
            break
    return records


# --- CLI --------------------------------------------------------------------

SIG_SCROLL = (
    "com.pocketshell.app.hosts.FolderListScrollE2eTest#"
    "folderSessionListScrollsToLastSessionInTreeAndFlatModes"
)
SIG_CLICK = (
    "com.pocketshell.app.projects.FolderListSessionClickTest#"
    "multiWindowAgentSessionDoesNotRepeatAgentTypeOrBadges"
)
SIG_SHELL = (
    "com.pocketshell.app.projects.RepoBrowserSessionPickerTest#"
    "shellOptionFromRepoCreatesPlainTmuxSession"
)
SIG_AGENT = (
    "com.pocketshell.app.projects.RepoBrowserSessionPickerTest#"
    "agentOptionFromRepoStillCarriesStartupCommand"
)
SIG_SHELL_RENAMED = (
    "com.pocketshell.app.projects.RepoBrowserSessionPickerTest#"
    "repoTap_picker_prefillsRepoPath_andShellRouteOpensPlainTerminal"
)
ISSUE1868 = (SIG_SCROLL, SIG_CLICK, SIG_SHELL, SIG_AGENT)


def _tsv_for(date: str, run_id: str, sha: str, signatures: Iterable[str]) -> str:
    record = RunRecord(
        run_id=run_id,
        date=date,
        sha=sha,
        status="observed",
        signatures={sig: f"failure {sig.rsplit('#', 1)[-1]}" for sig in signatures},
    )
    tmp = Path(tempfile.mkdtemp()) / TSV_FILENAME
    write_tsv(record, tmp)
    return tmp.read_text(encoding="utf-8")


def _write_run(root: Path, date: str, run_id: str, sha: str, signatures: Iterable[str] | None) -> Path:
    dest = root / f"run-{run_id}"
    dest.mkdir(parents=True, exist_ok=True)
    if signatures is None:
        (dest / UNAVAILABLE_MARKER).write_text(
            f"# sha={sha}\n# date={date}\n# run_id={run_id}\n",
            encoding="utf-8",
        )
        return dest
    write_tsv(
        RunRecord(
            run_id=run_id,
            date=date,
            sha=sha,
            status="observed",
            signatures={sig: "boom" for sig in signatures},
        ),
        dest / TSV_FILENAME,
    )
    return dest


def _row_map(report: RecurrenceReport) -> dict[str, RecurrenceRow]:
    return {row.signature: row for row in report.rows}


def run_self_test() -> None:
    failures = 0

    def check(label: str, cond: bool, detail: str = "") -> None:
        nonlocal failures
        if cond:
            print(f"  ok: {label}")
        else:
            failures += 1
            extra = f" — {detail}" if detail else ""
            print(f"  FAIL: {label}{extra}", file=sys.stderr)

    sandbox = Path(tempfile.mkdtemp(prefix="ps-1924-"))
    try:
        # 1. #1868 four signatures × three dated inputs → count=3, exact dates, streak=3
        hist = sandbox / "1868"
        hist.mkdir()
        _write_run(hist, "2026-07-29", "30425295643", "98fda832", ISSUE1868)
        _write_run(hist, "2026-07-30", "30516193460", "818b9ac1", ISSUE1868)
        _write_run(hist, "2026-07-31", "30607645296", "341ffc0f", ISSUE1868)
        report = compute_recurrence(load_history_dir(hist), window=14)
        rows = _row_map(report)
        check("1868 four signatures present", set(rows) == set(ISSUE1868), str(set(rows)))
        all_three = all(
            rows[sig].count == 3
            and rows[sig].first_seen == "2026-07-29"
            and rows[sig].last_seen == "2026-07-31"
            and rows[sig].streak == 3
            for sig in ISSUE1868
        ) if len(rows) == 4 else False
        check("1868 count=3 first=2026-07-29 last=2026-07-31 streak=3", all_three)
        check("1868 history complete/observed", report.history_status == "complete")

        # 2. Gaps: present D1+D3, absent D2 → count=2, streak=1
        gap = sandbox / "gap"
        gap.mkdir()
        _write_run(gap, "2026-07-29", "1", "a", (SIG_SCROLL,))
        _write_run(gap, "2026-07-30", "2", "b", ())
        _write_run(gap, "2026-07-31", "3", "c", (SIG_SCROLL,))
        gap_report = compute_recurrence(load_history_dir(gap), window=14)
        gap_row = _row_map(gap_report)[SIG_SCROLL]
        check(
            "gap count=2 first=29 last=31 streak=1",
            gap_row.count == 2
            and gap_row.first_seen == "2026-07-29"
            and gap_row.last_seen == "2026-07-31"
            and gap_row.streak == 1,
            f"count={gap_row.count} first={gap_row.first_seen} last={gap_row.last_seen} streak={gap_row.streak}",
        )

        # 3. Missing pre-rollout artifacts → history unavailable, never count=0
        miss = sandbox / "missing"
        miss.mkdir()
        _write_run(miss, "2026-07-28", "10", "x", None)
        _write_run(miss, "2026-07-29", "11", "y", None)
        _write_run(miss, "2026-07-31", "12", "z", ISSUE1868)
        miss_report = compute_recurrence(load_history_dir(miss), window=14)
        miss_rows = _row_map(miss_report)
        check("missing pre-rollout is history unavailable", miss_report.history_status == "unavailable" or miss_report.history_status == "partial")
        check("missing pre-rollout does not invent count=0 rows", all(row.count > 0 for row in miss_report.rows))
        check(
            "missing pre-rollout observed signatures keep count=1 + history unavailable",
            all(
                miss_rows[sig].count == 1 and miss_rows[sig].history == "history unavailable"
                for sig in ISSUE1868
            )
            if set(ISSUE1868) <= set(miss_rows)
            else False,
            str({sig: (miss_rows[sig].count, miss_rows[sig].history) for sig in miss_rows}),
        )

        # 4. Renamed methods are distinct signatures, never merged
        renamed = sandbox / "renamed"
        renamed.mkdir()
        _write_run(renamed, "2026-07-30", "20", "a", (SIG_SHELL,))
        _write_run(renamed, "2026-07-31", "21", "b", (SIG_SHELL_RENAMED,))
        renamed_report = compute_recurrence(load_history_dir(renamed), window=14)
        renamed_rows = _row_map(renamed_report)
        check("renamed old signature stays count=1", renamed_rows.get(SIG_SHELL) and renamed_rows[SIG_SHELL].count == 1)
        check(
            "renamed new signature is distinct count=1",
            renamed_rows.get(SIG_SHELL_RENAMED) and renamed_rows[SIG_SHELL_RENAMED].count == 1,
        )
        check("renamed methods are two rows", len(renamed_rows) == 2, str(set(renamed_rows)))

        # 5. XML escaping + parameterized names stay distinct; duplicates collapse
        xml_dir = sandbox / "xml"
        xml_dir.mkdir()
        xml_path = xml_dir / "TEST-escaped.xml"
        xml_path.write_text(
            """<?xml version="1.0" encoding="UTF-8"?>
<testsuite tests="5" failures="5">
  <testcase name="foo&amp;bar" classname="com.example.Escaped[emulator-5554 - 15]">
    <failure>expected:&lt;git-pocketshell&gt; but was:&lt;pocketshell&gt;</failure>
  </testcase>
  <testcase name="param[0]" classname="com.example.Param">
    <failure>first</failure>
  </testcase>
  <testcase name="param[1]" classname="com.example.Param">
    <failure>second</failure>
  </testcase>
  <testcase name="dup" classname="com.example.Dup">
    <failure>one</failure>
  </testcase>
  <testcase name="dup" classname="com.example.Dup">
    <failure>two</failure>
  </testcase>
</testsuite>
""",
            encoding="utf-8",
        )
        emitted = emit_from_xml(xml_dir)
        check(
            "xml unescape Class#method",
            "com.example.Escaped#foo&bar" in emitted,
            str(sorted(emitted)),
        )
        check("xml unescape failure text", "expected:<git-pocketshell>" in emitted.get("com.example.Escaped#foo&bar", ""))
        check("parameterized param[0] kept", "com.example.Param#param[0]" in emitted)
        check("parameterized param[1] distinct", "com.example.Param#param[1]" in emitted)
        check("duplicate Class#method collapses to one", sum(1 for sig in emitted if sig.endswith("#dup")) == 1)
        check("duplicate keeps first message", emitted.get("com.example.Dup#dup") == "one")

        # Merge two shard TSVs with overlap
        shard_a = sandbox / "shard-a" / TSV_FILENAME
        shard_b = sandbox / "shard-b" / TSV_FILENAME
        write_tsv(
            RunRecord("9", "2026-08-01", "s", "observed", {SIG_SCROLL: "a", SIG_CLICK: "b"}),
            shard_a,
        )
        write_tsv(
            RunRecord("9", "2026-08-01", "s", "observed", {SIG_SCROLL: "ignored-dup", SIG_SHELL: "c"}),
            shard_b,
        )
        merged = merge_records([parse_tsv(shard_a), parse_tsv(shard_b)], RunRecord("9", "2026-08-01", "s", "observed"))
        check("shard merge unions signatures", set(merged.signatures) == {SIG_SCROLL, SIG_CLICK, SIG_SHELL})
        check("shard merge keeps first duplicate message", merged.signatures[SIG_SCROLL] == "a")

        # 6. Window bound: 15 hits, window=14 → count=14, first is the 14th newest
        bound = sandbox / "bound"
        bound.mkdir()
        for day in range(1, 16):
            _write_run(bound, f"2026-07-{day:02d}", str(100 + day), "s", (SIG_SCROLL,))
        bound_report = compute_recurrence(load_history_dir(bound), window=14)
        bound_row = _row_map(bound_report)[SIG_SCROLL]
        check("window 14 of 15 → count=14", bound_row.count == 14, f"count={bound_row.count}")
        check("window 14 first_seen is 2026-07-02 not 07-01", bound_row.first_seen == "2026-07-02", bound_row.first_seen)
        check("window 14 last_seen is 2026-07-15", bound_row.last_seen == "2026-07-15", bound_row.last_seen)
        check("window 14 streak=14", bound_row.streak == 14, f"streak={bound_row.streak}")

        # 7. Fetch consumes only the small artifact name
        fixture = {
            "runs": [
                {
                    "id": 30425295643,
                    "created_at": "2026-07-29T05:30:58Z",
                    "head_sha": "98fda832",
                    "artifacts": [
                        {"name": "nightly-extensive-android-test-reports-shard-0"},
                        {
                            "name": ARTIFACT_NAME,
                            "tsv": _tsv_for("2026-07-29", "30425295643", "98fda832", ISSUE1868),
                        },
                    ],
                },
                {
                    "id": 30516193460,
                    "created_at": "2026-07-30T05:17:04Z",
                    "head_sha": "818b9ac1",
                    "artifacts": [
                        {"name": "nightly-extensive-docker-logs-shard-1"},
                        {
                            "name": ARTIFACT_NAME,
                            "tsv": _tsv_for("2026-07-30", "30516193460", "818b9ac1", ISSUE1868),
                        },
                    ],
                },
                {
                    "id": 30607645296,
                    "created_at": "2026-07-31T05:44:50Z",
                    "head_sha": "341ffc0f",
                    "artifacts": [
                        {
                            "name": ARTIFACT_NAME,
                            "tsv": _tsv_for("2026-07-31", "30607645296", "341ffc0f", ISSUE1868),
                        }
                    ],
                },
                {
                    "id": 1,
                    "created_at": "2026-07-28T05:00:00Z",
                    "head_sha": "skipped",
                    "artifacts": [],
                },
            ]
        }
        client = FixtureArtifactClient(fixture)
        fetched_dir = sandbox / "fetched"
        fetched = fetch_history(client, fetched_dir, window=14)
        check("fetch skips zero-artifact guard-skipped runs", all(run.run_id != "1" for run in fetched))
        check("fetch downloaded only the small artifact", client.downloaded == [
            (30607645296, ARTIFACT_NAME),
            (30516193460, ARTIFACT_NAME),
            (30425295643, ARTIFACT_NAME),
        ], str(client.downloaded))
        fetched_report = compute_recurrence(fetched, window=14)
        fetched_rows = _row_map(fetched_report)
        check(
            "fetch+summarize reproduces 1868 count=3",
            all(fetched_rows[sig].count == 3 for sig in ISSUE1868) if set(ISSUE1868) <= set(fetched_rows) else False,
        )

        # Forbidden name is refused even if a caller asks.
        refused = False
        try:
            client.download_artifact(30425295643, "nightly-extensive-android-test-reports-shard-0", sandbox / "nope")
        except RecurrenceError:
            refused = True
        check("forbidden full-report artifact name is refused", refused)

        # 8. G6: ignoring history (newest observed only) must redden count=3
        src = Path(__file__).read_text(encoding="utf-8")
        anchor = "    windowed = list(ordered[:window])\n"
        mutant_body = src.replace(
            anchor,
            "    windowed = [r for r in ordered if r.observed][:1] or list(ordered[:1])\n",
            1,
        )
        check("G6 mutant anchor present", mutant_body != src)
        mutant_path = sandbox / "ignore-history.py"
        mutant_path.write_text(mutant_body, encoding="utf-8")
        mutant_out = sandbox / "mutant-recurrence.tsv"
        spec = subprocess.run(
            [
                sys.executable,
                str(mutant_path),
                "summarize",
                "--history-dir",
                str(hist),
                "--window",
                "14",
                "--out",
                str(mutant_out),
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        mutant_tsv = mutant_out.read_text(encoding="utf-8") if mutant_out.is_file() else ""
        mutant_count = ""
        for line in mutant_tsv.splitlines():
            if line.startswith(SIG_SCROLL + "\t"):
                mutant_count = line.split("\t", 2)[1]
                break
        check("G6 ignore-history mutant runs", spec.returncode == 0, spec.stderr)
        check(
            "G6 ignore-history mutant does NOT report count=3",
            mutant_count != "3",
            f"mutant count={mutant_count!r}",
        )
        check("G6 production still reports count=3", rows[SIG_SCROLL].count == 3)

        # 9. G6: a fetch that pulled full Android archives would redden.
        check(
            "G6 production fetch never requested android-test-reports",
            all("android-test-reports" not in name for _, name in client.downloaded),
        )
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)

    if failures:
        raise SystemExit(f"self-test: {failures} failure(s)")
    print("nightly-failure-recurrence self-test: PASS")


def cmd_emit(args: argparse.Namespace) -> int:
    signatures = emit_from_xml(args.xml_root)
    record = RunRecord(
        run_id=str(args.run_id),
        date=args.date,
        sha=args.sha,
        status="observed",
        signatures=signatures,
    )
    write_tsv(record, args.out)
    print(f"wrote {len(signatures)} signature(s) to {args.out}")
    return 0


def cmd_emit_merge(args: argparse.Namespace) -> int:
    inputs = Path(args.inputs)
    if not inputs.exists():
        raise RecurrenceError(f"current-run signature inputs missing: {inputs}")
    tsvs = sorted(path for path in inputs.rglob(TSV_FILENAME) if path.is_file())
    if not tsvs:
        raise RecurrenceError(
            f"no {TSV_FILENAME} under {inputs} — current-run signatures are missing"
        )
    records = [parse_tsv(path) for path in tsvs]
    if any(record.status == "corrupt" for record in records):
        raise RecurrenceError("corrupt current-run failure-signatures.tsv")
    merged = merge_records(
        records,
        RunRecord(str(args.run_id), args.date, args.sha, "observed"),
    )
    write_tsv(merged, args.out)
    print(f"merged {len(tsvs)} shard TSV(s) → {len(merged.signatures)} signature(s)")
    return 0


def cmd_summarize(args: argparse.Namespace) -> int:
    runs: list[RunRecord] = []
    if args.current:
        current = parse_tsv(args.current)
        if current.status != "observed":
            raise RecurrenceError(f"current-run TSV is {current.status}")
        runs.append(current)
    if args.history_dir:
        runs.extend(load_history_dir(args.history_dir))
    report = compute_recurrence(runs, window=args.window)
    text = format_report_tsv(report)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(text, encoding="utf-8")
    else:
        sys.stdout.write(text)
    if args.markdown:
        md = format_report_markdown(report)
        if str(args.markdown) == "-":
            sys.stdout.write(md)
        else:
            path = Path(args.markdown)
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("a", encoding="utf-8") as handle:
                handle.write(md)
    return 0


def cmd_fetch(args: argparse.Namespace) -> int:
    if args.client_fixture:
        payload = json.loads(Path(args.client_fixture).read_text(encoding="utf-8"))
        client: FixtureArtifactClient | GhArtifactClient = FixtureArtifactClient(payload)
    else:
        repo = args.repo or os.environ.get("GITHUB_REPOSITORY")
        if not repo:
            raise RecurrenceError("GITHUB_REPOSITORY or --repo is required for fetch")
        client = GhArtifactClient(repo)
    records = fetch_history(
        client,
        args.out_dir,
        window=args.window,
        workflow=args.workflow,
        exclude_run_id=str(args.exclude_run_id or ""),
    )
    print(f"fetched {len(records)} completed nightly run(s) into {args.out_dir}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true", help="run emulator-free fixtures")
    sub = parser.add_subparsers(dest="cmd")

    emit = sub.add_parser("emit", help="write failure-signatures.tsv from JUnit XML")
    emit.add_argument("--xml-root", type=Path, required=True)
    emit.add_argument("--out", type=Path, required=True)
    emit.add_argument("--sha", default=os.environ.get("GITHUB_SHA", ""))
    emit.add_argument("--date", default="")
    emit.add_argument("--run-id", default=os.environ.get("GITHUB_RUN_ID", ""))

    merge = sub.add_parser("emit-merge", help="union shard TSVs into one current-run TSV")
    merge.add_argument("--inputs", type=Path, required=True)
    merge.add_argument("--out", type=Path, required=True)
    merge.add_argument("--sha", default=os.environ.get("GITHUB_SHA", ""))
    merge.add_argument("--date", default="")
    merge.add_argument("--run-id", default=os.environ.get("GITHUB_RUN_ID", ""))

    summarize = sub.add_parser("summarize", help="report count/age/streak from small artifacts")
    summarize.add_argument("--history-dir", type=Path)
    summarize.add_argument("--current", type=Path)
    summarize.add_argument("--window", type=int, default=DEFAULT_WINDOW)
    summarize.add_argument("--out", type=Path)
    summarize.add_argument("--markdown", default="")

    fetch = sub.add_parser("fetch", help="download small verdict artifacts for a bounded window")
    fetch.add_argument("--out-dir", type=Path, required=True)
    fetch.add_argument("--window", type=int, default=DEFAULT_WINDOW)
    fetch.add_argument("--workflow", default=WORKFLOW_FILE)
    fetch.add_argument("--repo", default="")
    fetch.add_argument("--exclude-run-id", default=os.environ.get("GITHUB_RUN_ID", ""))
    fetch.add_argument("--client-fixture", type=Path)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.self_test:
        run_self_test()
        return 0
    if args.cmd is None:
        parser.print_help()
        return 2
    if not getattr(args, "date", ""):
        from datetime import datetime, timezone

        args.date = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    try:
        if args.cmd == "emit":
            return cmd_emit(args)
        if args.cmd == "emit-merge":
            return cmd_emit_merge(args)
        if args.cmd == "summarize":
            return cmd_summarize(args)
        if args.cmd == "fetch":
            return cmd_fetch(args)
    except RecurrenceError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2
    parser.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
