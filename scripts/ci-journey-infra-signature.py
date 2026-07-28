#!/usr/bin/env python3
"""Issue #1800: classify an emulator-journey shard's failed-BOTH-attempts
evidence as either an environment-divergent capability precondition (INFRA) or a
genuine product failure (RED).

The ONE captured signature this recognises is the CI swiftshader AVD's inability
to raise a *real system input-method window*:

    The real system input-method window never became visible.

That assertion is not a product assertion. It is the physical-IME precondition
of `PromptComposerSaturatedImeAnchorE2eTest`, i.e. exactly the
environment-divergent capability `process.md` F3 / #780 says must be injected
synthetically rather than required. The load-bearing anchor/containment
properties of that class are independently asserted on its synthetic
`Type.ime()` path with hard failures, so typing this precondition as INFRA loses
no protection on CI.

Narrowness is the whole point. The classifier reports `real_ime_precondition`
ONLY when EVERY failing test case belonging to a class listed under the suite
summary's "Failed BOTH attempts" section carries that exact message. A single
containment / anchor / any other assertion failure — in the same class, in the
same run, in any attempt — forces `product_failure`, which keeps the shard RED.
Missing or unreadable evidence reports `unclassified`, which also keeps the
shard RED (fail-safe toward the red verdict, never toward green).

Usage:
    ci-journey-infra-signature.py SUMMARY_FILE ARTIFACT_ROOT [ARTIFACT_ROOT ...]

Output (GitHub step-output compatible key=value lines):
    journey_failure_classification=real_ime_precondition|product_failure|unclassified
    journey_failed_classes=<space separated FQCNs from the summary>
    journey_failing_testcases=<count of failing test cases attributed to them>
    journey_signature_matches=<count of those carrying the captured signature>
    journey_offending_failures=<semicolon separated "class#method" that did not match>

Exit status is always 0: the caller decides the verdict from the classification.
"""

from __future__ import annotations

import os
import re
import sys
import xml.etree.ElementTree as ET

# The ONE captured environment signature (issue #1800 / run 30305256109).
REAL_IME_UNAVAILABLE_SIGNATURE = (
    "The real system input-method window never became visible."
)

# `- \`com.example.Foo\`` bullets under the summary's failed-both header.
_FAILED_HEADER = re.compile(r"Failed BOTH attempts|JOURNEY_FAILED")
_BULLET = re.compile(r"^\s*-\s+`([A-Za-z_][\w.$]*)`")


def failed_both_classes(summary_path: str) -> list[str]:
    """FQCNs listed under the suite summary's failed-BOTH-attempts section."""
    try:
        with open(summary_path, "r", encoding="utf-8", errors="replace") as handle:
            lines = handle.read().splitlines()
    except OSError:
        return []

    classes: list[str] = []
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
        # Any other non-bullet content ends the section.
        in_section = False
    return classes


def _iter_result_xml(roots: list[str]):
    for root in roots:
        if not os.path.isdir(root):
            continue
        for dirpath, _dirnames, filenames in os.walk(root):
            for name in filenames:
                if name.startswith("TEST-") and name.endswith(".xml"):
                    yield os.path.join(dirpath, name)


def _failure_text(element: ET.Element) -> str:
    """Full failure text: the `message` attribute plus the element body."""
    parts = [element.get("message") or "", element.text or ""]
    return "\n".join(part for part in parts if part)


def classify(summary_path: str, roots: list[str]) -> dict[str, object]:
    classes = failed_both_classes(summary_path)
    failing = 0
    matches = 0
    offenders: list[str] = []

    if classes:
        wanted = set(classes)
        for xml_path in _iter_result_xml(roots):
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
                if base not in wanted:
                    continue
                problems = list(case.findall("failure")) + list(case.findall("error"))
                if not problems:
                    continue
                failing += 1
                texts = [_failure_text(problem) for problem in problems]
                if all(REAL_IME_UNAVAILABLE_SIGNATURE in text for text in texts):
                    matches += 1
                else:
                    offenders.append(f"{base}#{case.get('name') or '<unknown>'}")

    if not classes or failing == 0:
        classification = "unclassified"
    elif offenders or matches != failing:
        classification = "product_failure"
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
