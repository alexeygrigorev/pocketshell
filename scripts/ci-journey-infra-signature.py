#!/usr/bin/env python3
"""Issue #1800: classify an emulator-journey shard's failed-BOTH-attempts
evidence as either an environment-divergent capability precondition (INFRA) or a
genuine product failure (RED).

The original captured signature is the CI swiftshader AVD's inability to raise
a *real system input-method window while an explicitly identified foreign app
owns the active window*:

    The real system input-method window never became visible.
    ... active_window_pkg=com.example.foreign ...

The message alone is NOT enough. It is emitted by a load-bearing assertion in
`PromptComposerSaturatedImeAnchorE2eTest`, so an app-owned focus/serviceability
regression produces the same sentence. Issue #1882 therefore requires every
failure element carrying the sentence to also carry at least one resolvable
`active_window_pkg=...` reading, and requires EVERY such reading to be outside
the `com.pocketshell.app*` application-id family.

`active_window_pkg=android` is deliberately unresolved, not foreign. Framework
ANR/crash dialogs belong to package `android` whether the faulting process is a
foreign launcher or PocketShell itself (#1879/#796), so treating it as INFRA
would hide an app-owned ANR. Missing, malformed, `<unavailable>`, mixed
app/foreign, and app-owned readings all stay `product_failure` (RED). The
recurring residual-IME shape (`active=false focused=false` with non-empty
bounds, #1818) is diagnostic only; it never substitutes for a genuinely foreign
active-window owner.

Issue #1919 adds one separate, equally narrow signature for framework-owned
focus theft. `active_window_pkg=android` remains insufficient: every eligible
failure must live in a canonical class-attempt bundle whose sibling
`activity-processes.txt` proves exactly one valid non-PocketShell
`ProcessRecord` owns the sole current `mNotResponding=true
[AppNotRespondingDialog@id]`. Evidence from another class, attempt, summary,
screenshot, or shard-global copy is never consulted.

Narrowness is the whole point. The classifier reports an environmental value
ONLY when EVERY failing test case belonging to a class listed under the suite
summary's "Failed BOTH attempts" section satisfies one of those two complete
proofs. A single containment / anchor / chip / any other assertion failure — in
the same class, in the same run, in any attempt — forces `product_failure`,
which keeps the shard RED. Missing or unreadable evidence also stays RED
(fail-safe toward the red verdict, never toward green).

Usage:
    ci-journey-infra-signature.py SUMMARY_FILE ARTIFACT_ROOT [ARTIFACT_ROOT ...]

Output (GitHub step-output compatible key=value lines):
    journey_failure_classification=real_ime_precondition|foreign_framework_anr_focus|product_failure|unclassified
    journey_failed_classes=<space separated FQCNs from the summary>
    journey_failing_testcases=<count of failing test cases attributed to them>
    journey_signature_matches=<count of those carrying the captured signature>
    journey_offending_failures=<semicolon separated "class#method" that did not match>

Issue #1822: the suite registers several load-bearing journeys at `Class#method`
granularity, so the summary's failed-both bullets can be either
``- `com.example.Foo` `` or ``- `com.example.Foo#someMethod` ``. Both forms are
collected here, keyed on the class (the JUnit `classname` attribute the result
XML carries). A bullet the parser cannot read is MISSING EVIDENCE, not proof of
an environmental cause: it reports `unclassified` (RED) rather than silently
ending the section, which is how the original `[\\w.$]`-only pattern dropped the
first method-scoped entry AND truncated every entry after it.

Exit status is always 0: the caller decides the verdict from the classification.
"""

from __future__ import annotations

import hashlib
import os
import re
import stat
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

# The ONE captured environment signature (issue #1800 / run 30305256109).
REAL_IME_UNAVAILABLE_SIGNATURE = (
    "The real system input-method window never became visible."
)
APP_WINDOW_FOCUS_SIGNATURE = (
    "The app window never held input focus, so the system refused every "
    'showSoftInput() call ("is not served").'
)
ACTIVE_WINDOW_PACKAGE = re.compile(
    r"(?<![\w])active_window_pkg=([^\s,;]+)",
)
APP_WINDOW_FOCUS_STATE = re.compile(
    r"app_window_focused=(true|false)\s+active_window_pkg=([^\s,;.]+)",
)
# Issue #788 / exact-main run 30747057492: these are bounded focus-handoff
# preconditions, not the load-bearing IME/Copy assertions themselves.  They may
# join #1919's foreign-framework-ANR classification only when the failure also
# says the current window belongs to `android` AND the attempt-local process
# snapshot independently proves the sole ANR dialog belongs to a foreign app.
# Keep the strings exact and intentionally small: an arbitrary focus, Copy, or
# composer failure must never enter the environmental branch.
FOCUS_HANDOFF_PRECONDITION_SIGNATURES = (
    "the sent-snippet modal must release input focus before the "
    "keyboard-up shell-composer phase:",
    "file-viewer activity must regain focus after the synthetic owner is "
    "dismissed;",
)
ANDROID_PACKAGE = re.compile(
    r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+",
)
POCKETSHELL_APP_PACKAGE_PREFIX = "com.pocketshell.app"
ATTEMPT_DIRECTORY = re.compile(r"attempt-[1-9][0-9]*")
CAPTURE_TOKEN = re.compile(r"[0-9a-f]{64}")
# Run 30747057492 predates capture-time manifest digests.  Preserve only its
# four byte-exact process dumps, pinned to their full selector, attempt, and UTC
# interval.  New captures must carry the manifest binding written by
# ci-journey-budget-functions.sh; this immutable legacy set exists solely so
# the exact reviewed incident remains classifiable without making arbitrary
# unhashed artifacts eligible.
LEGACY_ISSUE788_ACTIVITY_PROCESS_BINDINGS = {
    (
        "com.pocketshell.app.tmux.TmuxShellComposerOcclusionE2eTest"
        "#shellComposerControlsAreVisibleAndReachableInBothKeyboardStates",
        "1",
        "2026-08-02T13:11:06Z",
        "2026-08-02T13:11:53Z",
    ): (
        "a993fbf8a7cd1c74b0c5674a38e325484f0d9a1e82c58d1fb2993bd6c24975af",
        268193,
    ),
    (
        "com.pocketshell.app.tmux.TmuxShellComposerOcclusionE2eTest"
        "#shellComposerControlsAreVisibleAndReachableInBothKeyboardStates",
        "2",
        "2026-08-02T13:11:53Z",
        "2026-08-02T13:12:41Z",
    ): (
        "f5f263d38f61a5beb1a5f4ca1b9a17fc132a0900f630ec845006783f2567f3ee",
        268681,
    ),
    (
        "com.pocketshell.app.fileviewer.FileViewerDockerTest"
        "#moduleOneArticleListsRenderIntactAndContinuedLinkOpensExactUrl",
        "1",
        "2026-08-02T13:19:45Z",
        "2026-08-02T13:20:17Z",
    ): (
        "9277216eab2f2da8b7a9c4450c956424f952d1a728db198d177e0476e23b95f8",
        268009,
    ),
    (
        "com.pocketshell.app.fileviewer.FileViewerDockerTest"
        "#moduleOneArticleListsRenderIntactAndContinuedLinkOpensExactUrl",
        "2",
        "2026-08-02T13:20:17Z",
        "2026-08-02T13:20:49Z",
    ): (
        "56bdc66954f6bfeafa39799a26997a165a9e5d62679b3f608e0b679833937bda",
        268262,
    ),
}
PROCESS_HEADER = re.compile(
    r"^\s*\*APP\*.*?\bProcessRecord\{[^{}\n]*?\s+[0-9]+:([^/\s}]+)/[^\s}]+\}",
)
ANY_PROCESS_HEADER = re.compile(r"^\s*\*APP\*.*?\bProcessRecord\{")
ANR_DIALOG = re.compile(
    r"mNotResponding=true\s+\[com\.android\.server\.am\."
    r"AppNotRespondingDialog@[0-9A-Fa-f]+\]",
)
ANY_ANR_DIALOG = re.compile(r"AppNotRespondingDialog@")

# `- \`com.example.Foo\`` and `- \`com.example.Foo#someMethod\`` bullets under
# the summary's failed-both header. The suite writes BOTH forms (issue #1822);
# trailing prose after the closing backtick — e.g. ``(#803 append-burst proof)``
# — is tolerated because the match is not anchored at the end of the line.
_FAILED_HEADER = re.compile(r"Failed BOTH attempts|JOURNEY_FAILED")
_BULLET = re.compile(r"^\s*-\s+`([A-Za-z_][\w.$]*)(?:#([^`\s]+))?`")
# Anything that is written as a markdown list item. A list item inside the
# failed-both section that `_BULLET` cannot read is an ENTRY WE FAILED TO
# UNDERSTAND, not the end of the section.
_ANY_BULLET = re.compile(r"^\s*[-*+]\s+\S")


def failed_both_section(summary_path: str) -> tuple[list[str], list[str]]:
    """Parse the suite summary's failed-BOTH-attempts section.

    Returns ``(classes, unreadable)``:

    * ``classes`` — deduplicated FQCNs (the class part of each bullet, which is
      what the JUnit result XML's ``classname`` attribute carries).
    * ``unreadable`` — verbatim list-item lines inside the section that could not
      be parsed. A non-empty list forces `unclassified`, i.e. RED. Fail-safe is
      toward the red verdict, NEVER toward green.
    """
    try:
        with open(summary_path, "r", encoding="utf-8", errors="replace") as handle:
            lines = handle.read().splitlines()
    except OSError:
        return [], []

    classes: list[str] = []
    unreadable: list[str] = []
    in_section = False
    for line in lines:
        if _FAILED_HEADER.search(line):
            in_section = True
            continue
        if not in_section:
            continue
        match = _BULLET.match(line)
        if match:
            name = match.group(1)
            if name not in classes:
                classes.append(name)
            continue
        if line.strip() == "":
            continue
        if _ANY_BULLET.match(line):
            # A list item we cannot read. Refusing to classify is the only safe
            # answer: treating it as "the section ended" would drop this entry
            # AND every entry after it, which is exactly how a genuine product
            # failure got laundered into an INFRA green (issue #1822).
            unreadable.append(line.strip())
            continue
        # Any other non-bullet content ends the section.
        in_section = False
    return classes, unreadable


def _iter_attempt_bundles(roots: list[str]):
    """Yield canonical class-attempt bundles, never shard-global XML copies.

    A result XML may license only the sibling ``activity-processes.txt`` under
    its own ``class-attempts/<module>/<key>/attempt-N`` directory. Restricting
    discovery to that shape also excludes the copied module-wide Android
    outputs that previously made the recursive scan see the same XML again.
    """
    seen: set[str] = set()
    for root in roots:
        if not os.path.isdir(root):
            continue
        for dirpath, dirnames, _filenames in os.walk(root):
            if not ATTEMPT_DIRECTORY.fullmatch(os.path.basename(dirpath)):
                continue
            parts = os.path.normpath(dirpath).split(os.sep)
            if len(parts) < 4 or parts[-4] != "class-attempts":
                continue
            real = os.path.realpath(dirpath)
            if real in seen:
                dirnames[:] = []
                continue
            seen.add(real)
            xml_paths: list[str] = []
            for nested, _nested_dirs, filenames in os.walk(dirpath):
                for name in filenames:
                    if name.startswith("TEST-") and name.endswith(".xml"):
                        xml_paths.append(os.path.join(nested, name))
            yield dirpath, parts[-2], sorted(xml_paths)
            dirnames[:] = []


def _artifact_key_may_hold_class(key: str, classname: str) -> bool:
    simple = classname.rsplit(".", 1)[-1]
    return (
        key == simple
        or key.startswith(f"{simple}_")
        or key == classname
        or key.startswith(f"{classname}--")
        or key.startswith(f"{classname}_")
    )


def _failure_text(element: ET.Element) -> str:
    """Full failure text: the `message` attribute plus the element body."""
    parts = [element.get("message") or "", element.text or ""]
    return "\n".join(part for part in parts if part)


def _is_readable_regular_file(path: str) -> bool:
    try:
        mode = os.stat(path, follow_symlinks=False).st_mode
    except OSError:
        return False
    return stat.S_ISREG(mode) and bool(mode & 0o444)


def _has_only_genuinely_foreign_active_window_owners(text: str) -> bool:
    """Whether the failure proves every observed active-window owner is foreign.

    The classifier is a one-way safety gate: absence, ambiguity, or a mixture of
    readings keeps the failure loud. In particular, `android` is not accepted
    even though it is outside PocketShell's package prefix: it is the framework
    package used by both foreign-app and PocketShell ANR dialogs.
    """
    owners = ACTIVE_WINDOW_PACKAGE.findall(text)
    if not owners:
        return False
    return all(
        ANDROID_PACKAGE.fullmatch(owner) is not None
        and owner != "android"
        and not owner.startswith(POCKETSHELL_APP_PACKAGE_PREFIX)
        for owner in owners
    )


def _is_real_ime_environment_failure(text: str) -> bool:
    return (
        REAL_IME_UNAVAILABLE_SIGNATURE in text
        and _has_only_genuinely_foreign_active_window_owners(text)
    )


def _is_framework_focus_precondition(text: str) -> bool:
    if (
        REAL_IME_UNAVAILABLE_SIGNATURE in text
        or APP_WINDOW_FOCUS_SIGNATURE in text
    ):
        states = APP_WINDOW_FOCUS_STATE.findall(text)
        return bool(states) and all(
            focused == "false" and package == "android"
            for focused, package in states
        )

    # The #1942/#1855 repaired journeys establish focus at a handoff boundary
    # before exercising their real IME/Copy oracles.  Run 30747057492 caught a
    # launcher ANR already standing above both classes in one boot.  The
    # composer reports the full app-window state while FileViewer reports the
    # active owner after dismissing only its own synthetic dialog.  Accept both
    # exact causal messages here; _foreign_anr_owner() remains the independent,
    # attempt-local ownership gate before either can affect the shard verdict.
    if not any(
        signature in text for signature in FOCUS_HANDOFF_PRECONDITION_SIGNATURES
    ):
        return False
    owners = ACTIVE_WINDOW_PACKAGE.findall(text)
    return bool(owners) and all(owner == "android" for owner in owners)


def _foreign_anr_owner(snapshot_path: str) -> str | None:
    """Return the sole proven foreign ANR owner, otherwise fail closed.

    Ownership comes only from an ``*APP* ... ProcessRecord`` block whose own
    body contains the exact ``mNotResponding=true [AppNotRespondingDialog@id]``
    state. A dialog outside a parsed block, a second not-responding process, or
    any package ambiguity returns ``None``.
    """
    if not _is_readable_regular_file(snapshot_path):
        return None
    try:
        with open(snapshot_path, "r", encoding="utf-8") as handle:
            text = handle.read()
    except (OSError, UnicodeError):
        return None
    if not text.strip():
        return None

    blocks: list[tuple[str | None, str]] = []
    current_package: str | None = None
    current_lines: list[str] = []
    outside_lines: list[str] = []
    for line in text.splitlines():
        header = PROCESS_HEADER.match(line)
        if ANY_PROCESS_HEADER.match(line):
            if current_lines:
                blocks.append((current_package, "\n".join(current_lines)))
            current_package = None
            current_lines = [line]
            if header:
                current_package = header.group(1).split(":", 1)[0]
            continue
        if current_lines and line and not line[0].isspace():
            blocks.append((current_package, "\n".join(current_lines)))
            current_package = None
            current_lines = []
        if current_lines:
            current_lines.append(line)
        else:
            outside_lines.append(line)
    if current_lines:
        blocks.append((current_package, "\n".join(current_lines)))

    if ANY_ANR_DIALOG.search("\n".join(outside_lines)):
        return None
    not_responding = [
        (package, block)
        for package, block in blocks
        if "mNotResponding=true" in block
    ]
    if len(not_responding) != 1:
        return None
    package, block = not_responding[0]
    if package is None or ANDROID_PACKAGE.fullmatch(package) is None:
        return None
    if package == "android" or package.startswith(POCKETSHELL_APP_PACKAGE_PREFIX):
        return None
    if len(ANR_DIALOG.findall(block)) != 1:
        return None
    if len(ANY_ANR_DIALOG.findall(text)) != 1:
        return None
    return package


def _attempt_provenance_matches(
    attempt_dir: str, classname: str, snapshot_path: str
) -> bool:
    """Require the snapshot's own completed class-attempt manifest.

    The suite deletes/recreates each attempt directory before execution, then
    writes the process snapshot and finalises this manifest in that directory.
    Binding the framework-ANR exception to its class, attempt number, and
    monotonic UTC interval prevents evidence from a sibling attempt/root from
    being copied or linked in to license a failure it did not accompany.
    """
    manifest_path = os.path.join(attempt_dir, "manifest.txt")
    if not _is_readable_regular_file(manifest_path):
        return False
    values: dict[str, list[str]] = {}
    try:
        with open(manifest_path, "r", encoding="utf-8") as handle:
            for raw in handle:
                line = raw.rstrip("\n")
                if "=" not in line:
                    return False
                key, value = line.split("=", 1)
                if not key:
                    return False
                values.setdefault(key, []).append(value)
    except (OSError, UnicodeError):
        return False

    def last(key: str) -> str | None:
        entries = values.get(key, [])
        return entries[-1] if entries else None

    attempt_match = ATTEMPT_DIRECTORY.fullmatch(os.path.basename(attempt_dir))
    if attempt_match is None:
        return False
    selector = last("class") or ""
    attempt = os.path.basename(attempt_dir).removeprefix("attempt-")
    started_text = last("started_at_utc") or ""
    finished_text = last("finished_at_utc") or ""
    if (
        last("format_version") != "1"
        or last("module") != "app"
        or selector.split("#", 1)[0] != classname
        or last("attempt") != attempt
        or last("snapshot_status") != "complete"
        or last("status") != "complete"
    ):
        return False

    try:
        started = datetime.strptime(
            started_text, "%Y-%m-%dT%H:%M:%SZ"
        ).replace(tzinfo=timezone.utc)
        finished = datetime.strptime(
            finished_text, "%Y-%m-%dT%H:%M:%SZ"
        ).replace(tzinfo=timezone.utc)
    except ValueError:
        return False
    if started > finished or not _is_readable_regular_file(snapshot_path):
        return False

    try:
        snapshot_size = os.stat(snapshot_path, follow_symlinks=False).st_size
        with open(snapshot_path, "rb") as handle:
            snapshot_bytes = handle.read()
        snapshot_sha256 = hashlib.sha256(snapshot_bytes).hexdigest()
    except OSError:
        return False

    bound_sha256 = last("activity_processes_sha256")
    bound_size = last("activity_processes_size_bytes")
    captured_text = last("activity_processes_captured_at_utc")
    capture_token = last("capture_token")
    binding_fields = (bound_sha256, bound_size, captured_text, capture_token)
    if all(field is None for field in binding_fields):
        return LEGACY_ISSUE788_ACTIVITY_PROCESS_BINDINGS.get(
            (selector, attempt, started_text, finished_text)
        ) == (snapshot_sha256, snapshot_size)
    if any(field is None for field in binding_fields):
        return False
    assert capture_token is not None
    if CAPTURE_TOKEN.fullmatch(capture_token) is None:
        return False
    expected_marker = (
        f"\nPOCKETSHELL_ATTEMPT_CAPTURE_TOKEN={capture_token}\n".encode("ascii")
    )
    if not snapshot_bytes.endswith(expected_marker):
        return False
    if snapshot_bytes.count(b"POCKETSHELL_ATTEMPT_CAPTURE_TOKEN=") != 1:
        return False
    if not re.fullmatch(r"[0-9a-f]{64}", bound_sha256):
        return False
    if not re.fullmatch(r"[1-9][0-9]*", bound_size):
        return False
    try:
        captured = datetime.strptime(
            captured_text, "%Y-%m-%dT%H:%M:%SZ"
        ).replace(tzinfo=timezone.utc)
    except ValueError:
        return False
    return (
        started <= captured <= finished
        and bound_sha256 == snapshot_sha256
        and int(bound_size) == snapshot_size
    )


def classify(summary_path: str, roots: list[str]) -> dict[str, object]:
    classes, unreadable = failed_both_section(summary_path)
    failing = 0
    matches = 0
    offenders: list[str] = [f"<unreadable-summary-entry>#{line}" for line in unreadable]
    covered: set[str] = set()
    framework_matches = 0

    if classes:
        wanted = set(classes)
        for attempt_dir, artifact_key, xml_paths in _iter_attempt_bundles(roots):
            possible = {
                name for name in wanted if _artifact_key_may_hold_class(artifact_key, name)
            }
            if not possible:
                continue
            if len(xml_paths) != 1:
                offenders.append(
                    f"<invalid-attempt-xml-count>#{artifact_key}/{os.path.basename(attempt_dir)}"
                )
                continue
            xml_path = xml_paths[0]
            if not _is_readable_regular_file(xml_path):
                offenders.append(f"<unreadable>#{os.path.basename(xml_path)}")
                continue
            try:
                tree = ET.parse(xml_path)
            except (ET.ParseError, OSError):
                # An unreadable result file is missing evidence, not proof of an
                # environmental cause. Record it as an offender so the shard
                # cannot be downgraded on incomplete data.
                offenders.append(f"<unreadable>#{os.path.basename(xml_path)}")
                continue
            for case in tree.iter("testcase"):
                classname = case.get("classname") or ""
                # Parameterised runners append the device label, e.g.
                # `com.example.Foo[emulator-5554 - 15]`.
                base = classname.split("[", 1)[0].strip()
                problems = list(case.findall("failure")) + list(case.findall("error"))
                if base not in possible:
                    offenders.append(
                        "<artifact-key-classname-mismatch>#"
                        f"{artifact_key}/{os.path.basename(attempt_dir)}:"
                        f"{base or '<missing-classname>'}#"
                        f"{case.get('name') or '<unknown>'}",
                    )
                    # Keep a mismatched wanted failure visible in the totals and
                    # out of `uncovered`: its XML exists, but its attempt-local
                    # ownership proof is invalid. It is therefore a definite
                    # product offender, not evidence that may borrow this
                    # bundle's sibling activity-processes snapshot.
                    if base in wanted and problems:
                        failing += 1
                        covered.add(base)
                    continue
                if not problems:
                    continue
                failing += 1
                covered.add(base)
                texts = [_failure_text(problem) for problem in problems]
                kinds: list[str] = []
                snapshot_owner: str | None = None
                for text in texts:
                    if _is_real_ime_environment_failure(text):
                        kinds.append("real_ime")
                    elif _is_framework_focus_precondition(text):
                        if snapshot_owner is None:
                            snapshot_path = os.path.join(
                                attempt_dir, "activity-processes.txt"
                            )
                            if _attempt_provenance_matches(
                                attempt_dir, base, snapshot_path
                            ):
                                snapshot_owner = _foreign_anr_owner(
                                    snapshot_path,
                                )
                        kinds.append("framework_anr" if snapshot_owner else "offender")
                    else:
                        kinds.append("offender")
                if all(kind != "offender" for kind in kinds):
                    matches += 1
                    if "framework_anr" in kinds:
                        framework_matches += 1
                else:
                    offenders.append(f"{base}#{case.get('name') or '<unknown>'}")

    # Every class the summary listed as failing BOTH attempts must be backed by
    # at least one failing test case in the preserved evidence. A listed class
    # with no failing evidence is missing data — downgrading the shard on the
    # remaining classes would mask whatever that one actually did (issue #1822).
    uncovered = [name for name in classes if name not in covered]
    for name in uncovered:
        offenders.append(f"{name}#<no-failing-testcase-in-evidence>")

    if unreadable or uncovered:
        classification = "unclassified"
    elif not classes or failing == 0:
        classification = "unclassified"
    elif offenders or matches != failing:
        classification = "product_failure"
    elif framework_matches:
        classification = "foreign_framework_anr_focus"
    else:
        classification = "real_ime_precondition"

    return {
        "journey_failure_classification": classification,
        "journey_failed_classes": " ".join(classes),
        "journey_failing_testcases": failing,
        "journey_signature_matches": matches,
        "journey_offending_failures": ";".join(offenders),
    }


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        sys.stderr.write(
            "Usage: ci-journey-infra-signature.py SUMMARY_FILE ARTIFACT_ROOT "
            "[ARTIFACT_ROOT ...]\n",
        )
        # Still emit a fail-safe classification so the caller keeps RED.
        print("journey_failure_classification=unclassified")
        print("journey_failed_classes=")
        print("journey_failing_testcases=0")
        print("journey_signature_matches=0")
        print("journey_offending_failures=")
        return 0

    result = classify(argv[1], argv[2:])
    for key in (
        "journey_failure_classification",
        "journey_failed_classes",
        "journey_failing_testcases",
        "journey_signature_matches",
        "journey_offending_failures",
    ):
        print(f"{key}={result[key]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
