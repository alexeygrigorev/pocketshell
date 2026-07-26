#!/usr/bin/env python3
"""Emit authoritative Gradle project directories from settings.gradle.kts.

The supported repository contract is intentionally small and explicit:
  include(":a", ":nested:b")
  project(":a").projectDir = file("modules/a with spaces")

Whitespace, comments, multiple include arguments, and multiline calls are
supported. Any unsupported projectDir assignment fails closed.
"""

from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path


class ParseError(Exception):
    pass


@dataclass(frozen=True)
class Token:
    kind: str
    value: str
    offset: int


PUNCTUATION = {"(": "LPAREN", ")": "RPAREN", ",": "COMMA", ".": "DOT", "=": "EQUALS"}
PROJECT_PATH = re.compile(r"^:(?:[^:]+)(?::[^:]+)*$")


def reject_embedded_nul(value: str, label: str) -> None:
    if "\x00" in value:
        raise ParseError(f"{label} contains an embedded NUL")


def tokenize(source: str) -> list[Token]:
    tokens: list[Token] = []
    index = 0
    length = len(source)
    while index < length:
        char = source[index]
        if char.isspace():
            index += 1
            continue
        if source.startswith("//", index):
            newline = source.find("\n", index + 2)
            index = length if newline == -1 else newline + 1
            continue
        if source.startswith("/*", index):
            end = source.find("*/", index + 2)
            if end == -1:
                raise ParseError(f"unterminated block comment at offset {index}")
            index = end + 2
            continue
        if source.startswith('"""', index):
            raise ParseError(f"triple-quoted strings are unsupported at offset {index}")
        if char == '"':
            start = index
            index += 1
            escaped = False
            while index < length:
                current = source[index]
                if escaped:
                    escaped = False
                elif current == "\\":
                    escaped = True
                elif current == '"':
                    index += 1
                    literal = source[start:index]
                    try:
                        value = json.loads(literal)
                    except json.JSONDecodeError as exc:
                        raise ParseError(f"unsupported string literal at offset {start}: {exc}") from exc
                    tokens.append(Token("STRING", value, start))
                    break
                index += 1
            else:
                raise ParseError(f"unterminated string at offset {start}")
            continue
        if char.isalpha() or char == "_":
            start = index
            index += 1
            while index < length and (source[index].isalnum() or source[index] == "_"):
                index += 1
            tokens.append(Token("IDENT", source[start:index], start))
            continue
        kind = PUNCTUATION.get(char)
        if kind is not None:
            tokens.append(Token(kind, char, index))
        else:
            tokens.append(Token("OTHER", char, index))
        index += 1
    return tokens


def expect(tokens: list[Token], index: int, kind: str, value: str | None = None) -> Token:
    if index >= len(tokens):
        raise ParseError(f"expected {value or kind}, reached end of settings")
    token = tokens[index]
    if token.kind != kind or (value is not None and token.value != value):
        raise ParseError(
            f"expected {value or kind} at offset {token.offset}, got {token.value!r}"
        )
    return token


def parse_include(tokens: list[Token], index: int) -> tuple[list[str], int]:
    expect(tokens, index, "IDENT", "include")
    expect(tokens, index + 1, "LPAREN")
    cursor = index + 2
    paths: list[str] = []
    expect_value = True
    while cursor < len(tokens):
        token = tokens[cursor]
        if token.kind == "RPAREN":
            if expect_value and paths:
                # A trailing comma is valid Kotlin.
                return paths, cursor + 1
            if not paths:
                raise ParseError(f"empty include() at offset {tokens[index].offset}")
            return paths, cursor + 1
        if expect_value:
            if token.kind != "STRING":
                raise ParseError(
                    f"include() accepts only static string arguments; got {token.value!r} "
                    f"at offset {token.offset}"
                )
            reject_embedded_nul(token.value, "Gradle project path in include()")
            paths.append(token.value)
            expect_value = False
        else:
            if token.kind != "COMMA":
                raise ParseError(
                    f"expected comma in include() at offset {token.offset}, got {token.value!r}"
                )
            expect_value = True
        cursor += 1
    raise ParseError(f"unterminated include() at offset {tokens[index].offset}")


def parse_project_remap(tokens: list[Token], index: int) -> tuple[tuple[str, str] | None, int]:
    expect(tokens, index, "IDENT", "project")
    if index + 3 >= len(tokens) or tokens[index + 1].kind != "LPAREN":
        return None, index + 1
    if tokens[index + 2].kind != "STRING" or tokens[index + 3].kind != "RPAREN":
        return None, index + 1
    cursor = index + 4
    if cursor + 1 >= len(tokens) or tokens[cursor].kind != "DOT":
        return None, cursor
    if tokens[cursor + 1].kind != "IDENT" or tokens[cursor + 1].value != "projectDir":
        return None, cursor
    project_path = tokens[index + 2].value
    cursor += 2
    expect(tokens, cursor, "EQUALS")
    expect(tokens, cursor + 1, "IDENT", "file")
    expect(tokens, cursor + 2, "LPAREN")
    directory = expect(tokens, cursor + 3, "STRING").value
    reject_embedded_nul(project_path, "Gradle project path in projectDir remap")
    reject_embedded_nul(directory, "projectDir remap")
    expect(tokens, cursor + 4, "RPAREN")
    return (project_path, directory), cursor + 5


def derive_project_dirs(source: str) -> list[str]:
    tokens = tokenize(source)
    includes: list[str] = []
    remaps: dict[str, str] = {}
    consumed_project_dir_offsets: set[int] = set()
    index = 0
    while index < len(tokens):
        token = tokens[index]
        if token.kind == "IDENT" and token.value == "include":
            paths, index = parse_include(tokens, index)
            includes.extend(paths)
            continue
        if token.kind == "IDENT" and token.value == "project":
            remap, next_index = parse_project_remap(tokens, index)
            if remap is not None:
                project_path, directory = remap
                project_dir_token = tokens[index + 5]
                consumed_project_dir_offsets.add(project_dir_token.offset)
                if project_path in remaps:
                    raise ParseError(f"duplicate projectDir remap for {project_path}")
                remaps[project_path] = directory
                index = next_index
                continue
        index += 1

    for token in tokens:
        if (
            token.kind == "IDENT"
            and token.value == "projectDir"
            and token.offset not in consumed_project_dir_offsets
        ):
            raise ParseError(
                f"unsupported projectDir syntax at offset {token.offset}; "
                'use project(":path").projectDir = file("relative/path")'
            )

    seen: set[str] = set()
    directories = ["."]
    for project_path in includes:
        if not PROJECT_PATH.fullmatch(project_path):
            raise ParseError(f"invalid Gradle project path in include(): {project_path!r}")
        if "$" in project_path:
            raise ParseError(f"dynamic Gradle project path is unsupported: {project_path!r}")
        if project_path in seen:
            raise ParseError(f"duplicate included project: {project_path}")
        seen.add(project_path)
        default_directory = project_path[1:].replace(":", "/")
        directory = remaps.pop(project_path, default_directory)
        if "$" in directory:
            raise ParseError(f"dynamic projectDir is unsupported: {directory!r}")
        directories.append(directory)
    if remaps:
        unknown = ", ".join(sorted(remaps))
        raise ParseError(f"projectDir remap references project not included in settings: {unknown}")
    return directories


def main() -> int:
    if len(sys.argv) != 2:
        print(f"Usage: {Path(sys.argv[0]).name} <settings.gradle.kts>", file=sys.stderr)
        return 2
    settings_path = Path(sys.argv[1])
    try:
        source = settings_path.read_text(encoding="utf-8")
        directories = derive_project_dirs(source)
    except (OSError, UnicodeError, ParseError) as exc:
        print(f"CI journey settings parse failed: {exc}", file=sys.stderr)
        return 1
    for directory in directories:
        sys.stdout.buffer.write(directory.encode("utf-8") + b"\0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
