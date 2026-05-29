"""`pocketshell env` subcommand group.

Read and write ``.env`` / ``.envrc`` files on the remote host so the
PocketShell Android app can manage a folder's environment without
launching an agent. **Server-side only** — the phone holds no secrets;
all file I/O happens on the dev box. This mirrors D19/D23's
"zero provider credentials on the phone" stance.

File formats (locked as D24)
----------------------------

- ``.env`` — ``KEY=value`` lines, no prefix.
- ``.envrc`` — ``export KEY=value`` lines (direnv style).

When both files exist in a folder they are *both* managed; every key is
tagged with the file it lives in. New keys default to ``.env`` unless the
caller picks ``--file .envrc``.

Subcommands
-----------

- ``env list --dir <path> [--json]`` — list key names + file +
  ``has_value`` (never values).
- ``env get --dir <path> --key FOO [--key BAR] [--json]`` — return
  value(s). Plain reveal; phone-side gating dropped.
- ``env set --dir <path> --file .env|.envrc`` — read ``{"KEY":"value"}``
  JSON from **stdin** and create/update keys. Surgical line rewrite:
  comments, ordering, and untouched keys are preserved.
- ``env unset --dir <path> --key FOO [--key BAR]`` — delete keys.
- ``env copy --from <src> --to <dst> --key FOO [...] [--file .env|.envrc]``
  — copy specific keys' values from the source folder into the
  destination.
- ``env export --dir <path>`` — emit a shell-eval-able block merging both
  files as ``export KEY=value`` lines, with shell-quoted values.

Why stdin for ``set`` (D24)
---------------------------

Secret values never appear in argv: ``ps``, shell history, and tmux
scrollback would otherwise leak them. The caller pipes a JSON object on
stdin instead. ``export`` shell-quotes values so the emitted block is
safe to ``eval`` in the launch hook (#263).

New files are created mode ``0600``; existing files keep their perms.
"""

from __future__ import annotations

import json
import os
import shlex
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import click


# Recognised env-file basenames and their write-time prefixes. ``.envrc``
# uses direnv's ``export `` prefix; ``.env`` is bare ``KEY=value``.
ENV_FILE = ".env"
ENVRC_FILE = ".envrc"
ENV_FILENAMES: tuple[str, ...] = (ENV_FILE, ENVRC_FILE)

# Prefix written in front of every key in ``.envrc``. ``.env`` has no
# prefix. Keyed by basename so the writer and the parser agree.
_FILE_PREFIX: dict[str, str] = {ENV_FILE: "", ENVRC_FILE: "export "}

# Permissions for a freshly-created env file. ``0600`` keeps secrets
# readable only by the owning user — these files hold credentials.
NEW_FILE_MODE = 0o600


# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class EnvKey:
    """One key as surfaced by ``env list``.

    ``value`` is deliberately *not* part of this shape — ``list`` is
    write-only by default (D24). ``has_value`` records whether the
    parsed assignment had a non-empty right-hand side.
    """

    key: str
    file: str
    has_value: bool


# ---------------------------------------------------------------------------
# Parsing
# ---------------------------------------------------------------------------


def _strip_export_prefix(line: str) -> str:
    """Return ``line`` with a leading ``export `` token removed.

    Only strips a single ``export`` token followed by whitespace; an
    identifier that merely starts with ``export`` (e.g. ``exportable``)
    is left alone because the token must be word-bounded by whitespace.
    """
    stripped = line.lstrip()
    if stripped.startswith("export") and len(stripped) > 6 and stripped[6].isspace():
        return stripped[6:].lstrip()
    return stripped


def parse_assignment(line: str) -> Optional[tuple[str, str]]:
    """Parse one ``KEY=value`` (or ``export KEY=value``) line.

    Returns ``(key, value)`` with the value unquoted, or ``None`` when
    the line is blank, a comment, or not a valid assignment.

    Quoting rules:

    - ``KEY="a b"`` / ``KEY='a b'`` — the matching outer quotes are
      stripped; inner content (including ``#`` and ``=``) is preserved
      verbatim.
    - ``KEY=a b # note`` — for *unquoted* values an inline ``#`` that is
      preceded by whitespace starts a trailing comment and is dropped;
      the value is then right-trimmed. This matches common ``.env``
      loaders. A ``#`` inside quotes is always literal.
    - Surrounding whitespace around the key and the unquoted value is
      trimmed.
    """
    raw = _strip_export_prefix(line)
    if not raw or raw.startswith("#"):
        return None
    eq = raw.find("=")
    if eq <= 0:
        return None
    key = raw[:eq].strip()
    if not key or not _is_valid_key(key):
        return None
    value_part = raw[eq + 1 :]
    return key, _parse_value(value_part)


def _is_valid_key(key: str) -> bool:
    """Return True when ``key`` is a POSIX-ish shell identifier.

    ``[A-Za-z_][A-Za-z0-9_]*``. Keeps us from mis-parsing arbitrary text
    (e.g. a wrapped continuation line) as an assignment.
    """
    if not key:
        return False
    first = key[0]
    if not (first.isalpha() or first == "_"):
        return False
    return all(c.isalnum() or c == "_" for c in key)


def _parse_value(value_part: str) -> str:
    """Unquote the right-hand side of an assignment.

    See :func:`parse_assignment` for the quoting rules this implements.
    """
    text = value_part.strip()
    if len(text) >= 2 and text[0] == "'" and text[-1] == "'":
        # Single quotes: literal, no escape processing (POSIX semantics).
        return text[1:-1]
    if len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        # Double quotes: undo the ``\"`` / ``\\`` escaping that
        # :func:`_format_value` emits so the value round-trips exactly.
        return _unescape_double_quoted(text[1:-1])
    # Unquoted: an inline ``#`` preceded by whitespace begins a comment.
    hash_idx = _find_inline_comment(text)
    if hash_idx is not None:
        text = text[:hash_idx]
    return text.rstrip()


def _unescape_double_quoted(inner: str) -> str:
    """Undo the ``\\\\`` / ``\\"`` escaping applied by :func:`_format_value`.

    Only backslash-escapes for ``\\`` and ``"`` are recognised (the only
    sequences the writer ever emits); any other backslash is left
    literal so a value like ``a\\nb`` survives unchanged.
    """
    out: list[str] = []
    i = 0
    while i < len(inner):
        ch = inner[i]
        if ch == "\\" and i + 1 < len(inner) and inner[i + 1] in ('"', "\\"):
            out.append(inner[i + 1])
            i += 2
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def _find_inline_comment(text: str) -> Optional[int]:
    """Return the index of an inline ``#`` comment, or ``None``.

    A ``#`` only starts a comment when it is at the start of the value
    or preceded by whitespace. ``KEY=a#b`` keeps ``a#b`` as the value;
    ``KEY=a #b`` keeps ``a``.
    """
    for i, ch in enumerate(text):
        if ch == "#" and (i == 0 or text[i - 1].isspace()):
            return i
    return None


def parse_env_file(path: Path) -> list[tuple[str, str]]:
    """Parse ``path`` into an ordered list of ``(key, value)`` pairs.

    Later assignments to the same key win (last-wins), matching shell
    sourcing semantics, but the returned list preserves *all* parsed
    pairs in file order so callers that need the canonical value can
    fold them. Non-assignment lines (blanks, comments) are skipped.

    A missing file yields an empty list — callers treat "no file" and
    "empty file" identically.
    """
    if not path.exists():
        return []
    pairs: list[tuple[str, str]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        parsed = parse_assignment(line)
        if parsed is not None:
            pairs.append(parsed)
    return pairs


def _folded_values(path: Path) -> dict[str, str]:
    """Return the last-wins ``{key: value}`` map for one file."""
    result: dict[str, str] = {}
    for key, value in parse_env_file(path):
        result[key] = value
    return result


# ---------------------------------------------------------------------------
# Folder-level helpers
# ---------------------------------------------------------------------------


def env_files_in(directory: Path) -> list[str]:
    """Return the basenames of env files that exist in ``directory``.

    Order is fixed (``.env`` before ``.envrc``) so merged output is
    deterministic regardless of filesystem listing order.
    """
    return [name for name in ENV_FILENAMES if (directory / name).exists()]


def list_keys(directory: Path) -> list[EnvKey]:
    """List keys across both env files in ``directory`` (no values).

    Each :class:`EnvKey` records the file it came from. The same key
    name appearing in both files yields two entries (one per file) so
    the caller can disambiguate. Sorted by ``(key, file)`` for a stable
    schema.
    """
    keys: list[EnvKey] = []
    seen: set[tuple[str, str]] = set()
    for name in env_files_in(directory):
        for key, value in parse_env_file(directory / name):
            ident = (key, name)
            if ident in seen:
                # Last-wins on has_value within a single file: update the
                # existing entry rather than emitting a duplicate.
                for idx, existing in enumerate(keys):
                    if existing.key == key and existing.file == name:
                        keys[idx] = EnvKey(key=key, file=name, has_value=bool(value))
                        break
                continue
            seen.add(ident)
            keys.append(EnvKey(key=key, file=name, has_value=bool(value)))
    keys.sort(key=lambda k: (k.key, k.file))
    return keys


def get_values(directory: Path, requested: list[str]) -> dict[str, str]:
    """Return ``{key: value}`` for each requested key found in ``directory``.

    Both files are consulted; ``.envrc`` wins over ``.env`` when a key
    is defined in both (direnv is sourced after a plain ``.env`` in most
    setups, so its value is the effective one). Missing keys are simply
    absent from the result — that is not an error.
    """
    merged: dict[str, str] = {}
    # ``.env`` first, then ``.envrc`` so the latter overrides.
    for name in env_files_in(directory):
        merged.update(_folded_values(directory / name))
    return {key: merged[key] for key in requested if key in merged}


def merged_exports(directory: Path) -> dict[str, str]:
    """Return the merged ``{key: value}`` map across both files.

    Used by ``export``. ``.envrc`` overrides ``.env`` on conflict, for
    the same reason as :func:`get_values`. Insertion order follows file
    order then in-file order, which keeps the emitted block stable.
    """
    merged: dict[str, str] = {}
    for name in env_files_in(directory):
        for key, value in parse_env_file(directory / name):
            merged[key] = value
    return merged


# ---------------------------------------------------------------------------
# Surgical writing
# ---------------------------------------------------------------------------


def _format_value(value: str) -> str:
    """Render ``value`` for the right-hand side of an assignment.

    Empty, or anything containing whitespace, ``#``, or a quote, is
    wrapped in double quotes with embedded double-quotes/backslashes
    escaped so the file round-trips through :func:`parse_assignment`.
    Plain tokens are written bare to keep human-edited files tidy.
    """
    if value == "":
        return '""'
    needs_quote = any(c.isspace() for c in value) or any(c in value for c in '#"\'=`$')
    if not needs_quote:
        return value
    escaped = value.replace("\\", "\\\\").replace('"', '\\"')
    return f'"{escaped}"'


def _assignment_line(file_name: str, key: str, value: str) -> str:
    """Build the full assignment line for ``key`` in ``file_name``."""
    return f"{_FILE_PREFIX[file_name]}{key}={_format_value(value)}"


def _line_matches_key(file_name: str, line: str, key: str) -> bool:
    """Return True when ``line`` is an assignment to ``key``.

    Used by the surgical rewriter to find lines to replace/delete while
    leaving comments and other keys untouched.
    """
    parsed = parse_assignment(line)
    return parsed is not None and parsed[0] == key


def write_keys(directory: Path, file_name: str, updates: dict[str, str]) -> None:
    """Create/update ``updates`` in ``directory/file_name`` surgically.

    Existing lines for an updated key are rewritten in place (preserving
    their position); brand-new keys are appended. Comments, blank lines,
    ordering, and untouched keys are preserved byte-for-byte aside from
    the rewritten assignment lines.

    A new file is created mode ``0600``. An existing file keeps its perms.
    """
    if file_name not in _FILE_PREFIX:
        raise ValueError(f"unsupported env file: {file_name}")
    path = directory / file_name
    existed = path.exists()

    original = path.read_text(encoding="utf-8") if existed else ""
    # Preserve a trailing-newline-or-not decision: most env files end in
    # a newline. We rebuild line-by-line and re-join with "\n".
    lines = original.splitlines()
    had_trailing_newline = original.endswith("\n") if original else True

    remaining = dict(updates)
    new_lines: list[str] = []
    for line in lines:
        replaced = False
        for key in list(remaining):
            if _line_matches_key(file_name, line, key):
                new_lines.append(_assignment_line(file_name, key, remaining.pop(key)))
                replaced = True
                break
        if not replaced:
            new_lines.append(line)

    # Append any keys not already present, in caller order.
    for key, value in updates.items():
        if key in remaining:
            new_lines.append(_assignment_line(file_name, key, value))
            remaining.pop(key, None)

    content = "\n".join(new_lines)
    if content and had_trailing_newline:
        content += "\n"
    elif not content:
        # An all-new file with content always ends in a newline; an
        # empty result stays empty.
        content = ""

    if not existed:
        # Create with restrictive perms before writing any secret bytes.
        fd = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, NEW_FILE_MODE)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(content)
    else:
        path.write_text(content, encoding="utf-8")


def unset_keys(directory: Path, keys: list[str]) -> int:
    """Delete ``keys`` from both env files in ``directory``.

    Returns the number of assignment lines removed. Comments and other
    keys are preserved. A key absent from every file is a no-op (not an
    error).
    """
    removed = 0
    targets = set(keys)
    for name in env_files_in(directory):
        path = directory / name
        original = path.read_text(encoding="utf-8")
        had_trailing_newline = original.endswith("\n") if original else True
        kept: list[str] = []
        for line in original.splitlines():
            parsed = parse_assignment(line)
            if parsed is not None and parsed[0] in targets:
                removed += 1
                continue
            kept.append(line)
        content = "\n".join(kept)
        if content and had_trailing_newline:
            content += "\n"
        path.write_text(content, encoding="utf-8")
    return removed


def copy_keys(
    src_dir: Path,
    dst_dir: Path,
    keys: list[str],
    *,
    dst_file: str = ENV_FILE,
) -> dict[str, str]:
    """Copy ``keys`` values from ``src_dir`` into ``dst_dir/dst_file``.

    Source values are read merged across both source files (``.envrc``
    wins, same as :func:`get_values`). Keys missing from the source are
    skipped. Returns the ``{key: value}`` map actually written.
    """
    available = get_values(src_dir, keys)
    if available:
        write_keys(dst_dir, dst_file, available)
    return available


def render_export(directory: Path) -> str:
    """Render the merged env as an ``eval``-safe ``export`` block.

    Every key is emitted as ``export KEY=<shell-quoted value>`` using
    :func:`shlex.quote` so values with spaces, quotes, ``#``, or ``$``
    survive a round-trip through ``eval``. Trailing newline included
    when non-empty.
    """
    merged = merged_exports(directory)
    if not merged:
        return ""
    lines = [f"export {key}={shlex.quote(value)}" for key, value in merged.items()]
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------
# Click surface
# ---------------------------------------------------------------------------


def _resolve_dir(ctx: click.Context, directory: str, *, must_exist: bool) -> Path:
    """Expand ``directory`` and validate it as needed.

    ``must_exist`` enforces that the folder is present (read paths);
    write paths only require the *parent* to exist so a fresh ``.env``
    can be created in an existing folder.
    """
    path = Path(os.path.expanduser(directory))
    if must_exist and not path.is_dir():
        click.echo(f"pocketshell env: directory does not exist: {path}", err=True)
        ctx.exit(2)
    if not must_exist and not path.is_dir():
        click.echo(f"pocketshell env: directory does not exist: {path}", err=True)
        ctx.exit(2)
    return path


@click.group(
    name="env",
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Read and write a folder's `.env` / `.envrc` files server-side.\n\n"
        "Values are written via stdin JSON (never argv) so secrets do not "
        "leak into `ps` or scrollback. `list` returns key names only; use "
        "`get` to reveal values. `.env` keys are bare `KEY=value`; `.envrc` "
        "keys get the `export ` prefix. See D24 in docs/decisions.md."
    ),
)
def env_group() -> None:
    """Top-level group registered onto the root `pocketshell` CLI."""


@env_group.command(
    "list",
    context_settings={"help_option_names": ["-h", "--help"]},
)
@click.option("--dir", "directory", required=True, type=str, help="Folder to inspect.")
@click.option(
    "--json",
    "json_output",
    is_flag=True,
    help="Emit a JSON array of {key, file, has_value} objects.",
)
@click.pass_context
def env_list(ctx: click.Context, directory: str, json_output: bool) -> None:
    """List key names + file + has_value across `.env` and `.envrc`.

    Never prints values (write-only default, D24).
    """
    path = _resolve_dir(ctx, directory, must_exist=True)
    keys = list_keys(path)
    if json_output:
        payload = [
            {"key": k.key, "file": k.file, "has_value": k.has_value} for k in keys
        ]
        click.echo(json.dumps(payload, indent=2, sort_keys=True))
        return
    for k in keys:
        flag = "set" if k.has_value else "empty"
        click.echo(f"{k.key}\t{k.file}\t{flag}")


@env_group.command(
    "get",
    context_settings={"help_option_names": ["-h", "--help"]},
)
@click.option("--dir", "directory", required=True, type=str, help="Folder to read.")
@click.option(
    "--key",
    "keys",
    multiple=True,
    required=True,
    type=str,
    help="Key to reveal (may be repeated).",
)
@click.option(
    "--json",
    "json_output",
    is_flag=True,
    help="Emit a JSON object mapping key -> value for keys that exist.",
)
@click.pass_context
def env_get(
    ctx: click.Context,
    directory: str,
    keys: tuple[str, ...],
    json_output: bool,
) -> None:
    """Reveal the value(s) of the requested key(s).

    Missing keys are simply absent from the output; only a hard error
    (e.g. missing directory) is non-zero.
    """
    path = _resolve_dir(ctx, directory, must_exist=True)
    values = get_values(path, list(keys))
    if json_output:
        click.echo(json.dumps(values, indent=2, sort_keys=True))
        return
    for key in keys:
        if key in values:
            click.echo(f"{key}={values[key]}")


@env_group.command(
    "set",
    context_settings={"help_option_names": ["-h", "--help"]},
)
@click.option("--dir", "directory", required=True, type=str, help="Folder to write into.")
@click.option(
    "--file",
    "file_name",
    type=click.Choice(ENV_FILENAMES),
    default=ENV_FILE,
    show_default=True,
    help="Which file to write (.env has no prefix, .envrc gets `export `).",
)
@click.pass_context
def env_set(ctx: click.Context, directory: str, file_name: str) -> None:
    """Create/update keys from a `{"KEY":"value"}` JSON object on stdin.

    Values come from stdin (never argv) so secrets do not leak into
    `ps`/scrollback. Comments, ordering, and untouched keys are
    preserved (surgical rewrite).
    """
    path = _resolve_dir(ctx, directory, must_exist=True)
    raw = sys.stdin.read()
    if not raw.strip():
        click.echo("pocketshell env set: no JSON on stdin", err=True)
        ctx.exit(2)
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as exc:
        click.echo(f"pocketshell env set: invalid JSON on stdin: {exc}", err=True)
        ctx.exit(2)
    if not isinstance(payload, dict):
        click.echo("pocketshell env set: stdin JSON must be an object", err=True)
        ctx.exit(2)
    updates: dict[str, str] = {}
    for key, value in payload.items():
        if not isinstance(key, str) or not _is_valid_key(key):
            click.echo(f"pocketshell env set: invalid key: {key!r}", err=True)
            ctx.exit(2)
        updates[key] = "" if value is None else str(value)
    write_keys(path, file_name, updates)


@env_group.command(
    "unset",
    context_settings={"help_option_names": ["-h", "--help"]},
)
@click.option("--dir", "directory", required=True, type=str, help="Folder to edit.")
@click.option(
    "--key",
    "keys",
    multiple=True,
    required=True,
    type=str,
    help="Key to delete (may be repeated).",
)
@click.pass_context
def env_unset(ctx: click.Context, directory: str, keys: tuple[str, ...]) -> None:
    """Delete the named key(s) from both files, leaving the rest intact."""
    path = _resolve_dir(ctx, directory, must_exist=True)
    unset_keys(path, list(keys))


@env_group.command(
    "copy",
    context_settings={"help_option_names": ["-h", "--help"]},
)
@click.option("--from", "src_dir", required=True, type=str, help="Source folder.")
@click.option("--to", "dst_dir", required=True, type=str, help="Destination folder.")
@click.option(
    "--key",
    "keys",
    multiple=True,
    required=True,
    type=str,
    help="Key to copy (may be repeated).",
)
@click.option(
    "--file",
    "file_name",
    type=click.Choice(ENV_FILENAMES),
    default=ENV_FILE,
    show_default=True,
    help="Which destination file to write the copied keys into.",
)
@click.pass_context
def env_copy(
    ctx: click.Context,
    src_dir: str,
    dst_dir: str,
    keys: tuple[str, ...],
    file_name: str,
) -> None:
    """Copy named keys' values from source folder into the destination."""
    src = _resolve_dir(ctx, src_dir, must_exist=True)
    dst = _resolve_dir(ctx, dst_dir, must_exist=True)
    copy_keys(src, dst, list(keys), dst_file=file_name)


@env_group.command(
    "export",
    context_settings={"help_option_names": ["-h", "--help"]},
)
@click.option("--dir", "directory", required=True, type=str, help="Folder to export.")
@click.pass_context
def env_export(ctx: click.Context, directory: str) -> None:
    """Emit an `eval`-safe `export KEY=value` block merging both files."""
    path = _resolve_dir(ctx, directory, must_exist=True)
    rendered = render_export(path)
    if rendered:
        sys.stdout.write(rendered)
