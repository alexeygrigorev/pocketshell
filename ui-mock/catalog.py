"""Discover literal, single-test Roborazzi render cases; never evaluate Kotlin."""
from __future__ import annotations

from dataclasses import asdict, dataclass
import hashlib
from pathlib import Path
import re


@dataclass(frozen=True)
class Case:
    id: str
    module: str
    class_name: str
    method: str
    label: str
    source: str
    kind: str

    @property
    def test_filter(self) -> str:
        return f"{self.class_name}.{self.method}"

    def public(self) -> dict:
        return asdict(self)


def code_mask(text: str) -> str:
    """Blank comments/string contents, retaining length, newlines and code offsets.

    Kotlin nested block comments and triple-quoted strings are handled. Literal
    render labels are subsequently read from the original source at a code offset.
    """
    out = list(text)
    i = 0
    n = len(text)

    def blank(a: int, b: int) -> None:
        for j in range(a, b):
            if out[j] not in "\r\n":
                out[j] = " "

    while i < n:
        start = i
        if text.startswith("//", i):
            end = text.find("\n", i)
            i = n if end < 0 else end
        elif text.startswith("/*", i):
            i += 2
            depth = 1
            while i < n and depth:
                if text.startswith("/*", i):
                    depth += 1
                    i += 2
                elif text.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    i += 1
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            i = n if end < 0 else end + 3
        elif text[i] in "\"'":
            quote = text[i]
            i += 1
            while i < n:
                if text[i] == "\\":
                    i = min(n, i + 2)
                elif text[i] == quote:
                    i += 1
                    break
                else:
                    i += 1
        else:
            i += 1
            continue
        blank(start, i)
    return "".join(out)


TEST = re.compile(
    r"@(?:org\.junit\.)?Test\b(?:\([^)]*\))?\s*"
    r"(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:(?:public|internal)\s+)?fun\s+([A-Za-z_]\w*)\s*\(\s*\)"
)
LABEL = re.compile(r'\s*"([a-z0-9][a-z0-9_-]*)"\s*[,)]')


def parse_file(root: Path, path: Path, module: str, kind: str) -> tuple[list[Case], list[str]]:
    source = path.read_text(encoding="utf-8")
    mask = code_mask(source)
    package = re.search(r"(?m)^\s*package\s+([\w.]+)", mask)
    name = path.stem
    relative = path.relative_to(root).as_posix()
    if not package or not re.search(r"\bclass\s+" + re.escape(name) + r"\b", mask):
        return [], [f"{relative}: no matching top-level render class; not exposed"]
    cases, warnings = [], []
    for test in TEST.finditer(mask):
        method = test.group(1)
        invocation = re.match(r"\s*=\s*render\s*\(", mask[test.end():])
        label = LABEL.match(source, test.end() + invocation.end()) if invocation else None
        if not label:
            warnings.append(f"{relative}:{method}: unsupported/non-literal render; not exposed")
            continue
        key = f"{module}:{package.group(1)}.{name}.{method}"
        cases.append(Case(
            id=hashlib.sha256(key.encode()).hexdigest()[:20], module=module,
            class_name=f"{package.group(1)}.{name}", method=method,
            label=label.group(1), source=relative, kind=kind,
        ))
    if not cases and not warnings:
        warnings.append(f"{relative}: no render cases found; not exposed")
    return cases, warnings


def discover(root: Path, include_kit: bool = False) -> tuple[list[Case], list[str]]:
    roots = [("app2", "app2/src/test/java/com/pocketshell/next/render", "screen-fixture")]
    if include_kit:
        roots.append(("shared:ui-kit", "shared/ui-kit/src/test/java/com/pocketshell/uikit/render", "ui-kit-example"))
    cases, warnings = [], []
    for module, directory, kind in roots:
        folder = root / directory
        if not folder.is_dir():
            warnings.append(f"Missing render directory: {directory}")
            continue
        for path in sorted(folder.glob("*Renders.kt")):
            if path.is_symlink():
                warnings.append(f"Refusing symlink render source: {path.name}")
                continue
            found, notes = parse_file(root, path, module, kind)
            cases.extend(found)
            warnings.extend(notes)
    ids = [case.id for case in cases]
    if len(ids) != len(set(ids)):
        raise ValueError("Duplicate render case identifiers")
    return cases, warnings
