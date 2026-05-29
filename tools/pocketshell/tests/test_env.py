"""Unit tests for `pocketshell env` (issue #262).

Covers both file formats (`.env` / `.envrc`), quoting edge cases,
comment/order preservation on surgical rewrite, copy, export
eval-safety, and missing-dir / missing-file behaviour.
"""

from __future__ import annotations

import json
import os
import stat
import subprocess
import sys
from pathlib import Path

from click.testing import CliRunner

from pocketshell.cli import cli
from pocketshell.env import (
    ENV_FILE,
    ENVRC_FILE,
    NEW_FILE_MODE,
    copy_keys,
    env_group,
    get_values,
    list_keys,
    merged_exports,
    parse_assignment,
    parse_env_file,
    render_export,
    unset_keys,
    write_keys,
)


# ---------------------------------------------------------------------------
# Parsing
# ---------------------------------------------------------------------------


def test_parse_plain_assignment():
    assert parse_assignment("FOO=bar") == ("FOO", "bar")


def test_parse_export_prefix():
    assert parse_assignment("export FOO=bar") == ("FOO", "bar")
    # `exportable` must not be treated as an `export ` prefix.
    assert parse_assignment("exportable=1") == ("exportable", "1")


def test_parse_double_quoted_value_keeps_spaces_and_hash():
    assert parse_assignment('FOO="a b # c"') == ("FOO", "a b # c")


def test_parse_single_quoted_value():
    assert parse_assignment("FOO='a b'") == ("FOO", "a b")


def test_parse_value_with_equals():
    assert parse_assignment("URL=postgres://u:p@h/db?x=1") == (
        "URL",
        "postgres://u:p@h/db?x=1",
    )


def test_parse_inline_comment_unquoted_is_stripped():
    assert parse_assignment("FOO=bar # trailing note") == ("FOO", "bar")


def test_parse_hash_without_leading_space_is_literal():
    assert parse_assignment("FOO=a#b") == ("FOO", "a#b")


def test_parse_blank_and_comment_lines_return_none():
    assert parse_assignment("") is None
    assert parse_assignment("   ") is None
    assert parse_assignment("# a comment") is None
    assert parse_assignment("   # indented comment") is None


def test_parse_rejects_invalid_keys():
    assert parse_assignment("1FOO=bar") is None
    assert parse_assignment("=bar") is None
    assert parse_assignment("no equals here") is None


def test_parse_env_file_missing_returns_empty(tmp_path: Path):
    assert parse_env_file(tmp_path / "nope.env") == []


# ---------------------------------------------------------------------------
# list / get
# ---------------------------------------------------------------------------


def test_list_keys_merges_both_files_without_values(tmp_path: Path):
    (tmp_path / ENV_FILE).write_text("A=1\nB=\n")
    (tmp_path / ENVRC_FILE).write_text("export C=3\n")
    keys = list_keys(tmp_path)
    rendered = {(k.key, k.file, k.has_value) for k in keys}
    assert rendered == {
        ("A", ENV_FILE, True),
        ("B", ENV_FILE, False),
        ("C", ENVRC_FILE, True),
    }


def test_get_values_envrc_overrides_env(tmp_path: Path):
    (tmp_path / ENV_FILE).write_text("A=fromenv\n")
    (tmp_path / ENVRC_FILE).write_text("export A=fromenvrc\n")
    assert get_values(tmp_path, ["A"]) == {"A": "fromenvrc"}


def test_get_values_missing_key_absent(tmp_path: Path):
    (tmp_path / ENV_FILE).write_text("A=1\n")
    assert get_values(tmp_path, ["A", "MISSING"]) == {"A": "1"}


# ---------------------------------------------------------------------------
# set / surgical write
# ---------------------------------------------------------------------------


def test_write_preserves_comments_order_and_other_keys(tmp_path: Path):
    original = (
        "# top comment\n"
        "FIRST=1\n"
        "\n"
        "# section\n"
        "SECOND=2\n"
        "THIRD=3\n"
    )
    path = tmp_path / ENV_FILE
    path.write_text(original)
    write_keys(tmp_path, ENV_FILE, {"SECOND": "two", "FOURTH": "4"})
    result = path.read_text()
    assert result == (
        "# top comment\n"
        "FIRST=1\n"
        "\n"
        "# section\n"
        "SECOND=two\n"
        "THIRD=3\n"
        "FOURTH=4\n"
    )


def test_write_envrc_uses_export_prefix(tmp_path: Path):
    write_keys(tmp_path, ENVRC_FILE, {"TOKEN": "abc"})
    assert (tmp_path / ENVRC_FILE).read_text() == "export TOKEN=abc\n"


def test_write_env_has_no_prefix(tmp_path: Path):
    write_keys(tmp_path, ENV_FILE, {"TOKEN": "abc"})
    assert (tmp_path / ENV_FILE).read_text() == "TOKEN=abc\n"


def test_write_quotes_value_with_spaces(tmp_path: Path):
    write_keys(tmp_path, ENV_FILE, {"MSG": "hello world"})
    text = (tmp_path / ENV_FILE).read_text()
    assert text == 'MSG="hello world"\n'
    # Round-trips back to the original value.
    assert get_values(tmp_path, ["MSG"]) == {"MSG": "hello world"}


def test_write_quotes_value_with_quotes_and_hash(tmp_path: Path):
    value = 'say "hi" # now'
    write_keys(tmp_path, ENV_FILE, {"MSG": value})
    assert get_values(tmp_path, ["MSG"]) == {"MSG": value}


def test_new_file_created_0600(tmp_path: Path):
    write_keys(tmp_path, ENV_FILE, {"A": "1"})
    mode = stat.S_IMODE((tmp_path / ENV_FILE).stat().st_mode)
    assert mode == NEW_FILE_MODE


def test_existing_file_perms_preserved(tmp_path: Path):
    path = tmp_path / ENV_FILE
    path.write_text("A=1\n")
    os.chmod(path, 0o640)
    write_keys(tmp_path, ENV_FILE, {"B": "2"})
    mode = stat.S_IMODE(path.stat().st_mode)
    assert mode == 0o640


# ---------------------------------------------------------------------------
# unset
# ---------------------------------------------------------------------------


def test_unset_removes_only_named_keys(tmp_path: Path):
    (tmp_path / ENV_FILE).write_text("# keep me\nA=1\nB=2\nC=3\n")
    removed = unset_keys(tmp_path, ["B"])
    assert removed == 1
    assert (tmp_path / ENV_FILE).read_text() == "# keep me\nA=1\nC=3\n"


def test_unset_missing_key_is_noop(tmp_path: Path):
    (tmp_path / ENV_FILE).write_text("A=1\n")
    assert unset_keys(tmp_path, ["NOPE"]) == 0
    assert (tmp_path / ENV_FILE).read_text() == "A=1\n"


def test_unset_spans_both_files(tmp_path: Path):
    (tmp_path / ENV_FILE).write_text("A=1\n")
    (tmp_path / ENVRC_FILE).write_text("export A=2\nexport B=3\n")
    removed = unset_keys(tmp_path, ["A"])
    assert removed == 2
    assert (tmp_path / ENV_FILE).read_text() == ""
    assert (tmp_path / ENVRC_FILE).read_text() == "export B=3\n"


# ---------------------------------------------------------------------------
# copy
# ---------------------------------------------------------------------------


def test_copy_copies_only_named_keys(tmp_path: Path):
    src = tmp_path / "src"
    dst = tmp_path / "dst"
    src.mkdir()
    dst.mkdir()
    (src / ENV_FILE).write_text("A=1\nB=2\nC=3\n")
    written = copy_keys(src, dst, ["A", "C"], dst_file=ENV_FILE)
    assert written == {"A": "1", "C": "3"}
    assert get_values(dst, ["A", "B", "C"]) == {"A": "1", "C": "3"}


def test_copy_into_existing_file_merges(tmp_path: Path):
    src = tmp_path / "src"
    dst = tmp_path / "dst"
    src.mkdir()
    dst.mkdir()
    (src / ENVRC_FILE).write_text("export TOKEN=secret\n")
    (dst / ENV_FILE).write_text("# existing\nEXISTING=yes\n")
    copy_keys(src, dst, ["TOKEN"], dst_file=ENV_FILE)
    assert (dst / ENV_FILE).read_text() == "# existing\nEXISTING=yes\nTOKEN=secret\n"


def test_copy_skips_missing_source_keys(tmp_path: Path):
    src = tmp_path / "src"
    dst = tmp_path / "dst"
    src.mkdir()
    dst.mkdir()
    (src / ENV_FILE).write_text("A=1\n")
    written = copy_keys(src, dst, ["A", "GONE"], dst_file=ENV_FILE)
    assert written == {"A": "1"}


# ---------------------------------------------------------------------------
# export
# ---------------------------------------------------------------------------


def test_export_is_eval_safe_for_tricky_values(tmp_path: Path):
    tricky = {
        "SIMPLE": "value",
        "SPACES": "a b c",
        "QUOTES": "she said \"hi\"",
        "DOLLAR": "$HOME stays literal",
        "HASH": "a # b",
        "SINGLE": "it's fine",
    }
    write_keys(tmp_path, ENV_FILE, tricky)
    block = render_export(tmp_path)
    # Run the block through a real shell and read back each var.
    probe = block + "\n".join(
        f'printf "%s\\037" "${{{key}}}"' for key in tricky
    )
    completed = subprocess.run(
        ["/bin/sh", "-c", probe],
        check=True,
        capture_output=True,
        text=True,
    )
    seen = completed.stdout.split("\x1f")[: len(tricky)]
    assert dict(zip(tricky.keys(), seen)) == tricky


def test_export_merges_both_files_envrc_wins(tmp_path: Path):
    (tmp_path / ENV_FILE).write_text("A=fromenv\nB=2\n")
    (tmp_path / ENVRC_FILE).write_text("export A=fromenvrc\n")
    merged = merged_exports(tmp_path)
    assert merged == {"A": "fromenvrc", "B": "2"}


def test_export_empty_dir_is_empty(tmp_path: Path):
    assert render_export(tmp_path) == ""


# ---------------------------------------------------------------------------
# CLI surface
# ---------------------------------------------------------------------------


def test_cli_registers_env_with_six_subcommands():
    runner = CliRunner()
    result = runner.invoke(cli, ["env", "--help"])
    assert result.exit_code == 0
    for sub in ("list", "get", "set", "unset", "copy", "export"):
        assert sub in result.output
    # Also surfaced on the top-level help.
    top = runner.invoke(cli, ["--help"])
    assert "env" in top.output


def test_cli_set_reads_stdin_json(tmp_path: Path):
    runner = CliRunner()
    result = runner.invoke(
        env_group,
        ["set", "--dir", str(tmp_path), "--file", ENV_FILE],
        input=json.dumps({"API_KEY": "sk-123", "REGION": "eu west"}),
    )
    assert result.exit_code == 0, result.output
    assert get_values(tmp_path, ["API_KEY", "REGION"]) == {
        "API_KEY": "sk-123",
        "REGION": "eu west",
    }


def test_cli_set_rejects_non_object_json(tmp_path: Path):
    runner = CliRunner()
    result = runner.invoke(
        env_group,
        ["set", "--dir", str(tmp_path)],
        input="[1, 2, 3]",
    )
    assert result.exit_code == 2


def test_cli_set_rejects_invalid_key(tmp_path: Path):
    runner = CliRunner()
    result = runner.invoke(
        env_group,
        ["set", "--dir", str(tmp_path)],
        input=json.dumps({"1BAD": "x"}),
    )
    assert result.exit_code == 2


def test_cli_list_json_schema_never_has_values(tmp_path: Path):
    (tmp_path / ENV_FILE).write_text("A=secret\nB=\n")
    runner = CliRunner()
    result = runner.invoke(env_group, ["list", "--dir", str(tmp_path), "--json"])
    assert result.exit_code == 0
    payload = json.loads(result.output)
    assert payload == [
        {"file": ENV_FILE, "has_value": True, "key": "A"},
        {"file": ENV_FILE, "has_value": False, "key": "B"},
    ]
    assert "secret" not in result.output


def test_cli_get_json(tmp_path: Path):
    (tmp_path / ENV_FILE).write_text("A=1\n")
    runner = CliRunner()
    result = runner.invoke(
        env_group, ["get", "--dir", str(tmp_path), "--key", "A", "--json"]
    )
    assert result.exit_code == 0
    assert json.loads(result.output) == {"A": "1"}


def test_cli_get_missing_key_is_not_an_error(tmp_path: Path):
    (tmp_path / ENV_FILE).write_text("A=1\n")
    runner = CliRunner()
    result = runner.invoke(
        env_group, ["get", "--dir", str(tmp_path), "--key", "NOPE", "--json"]
    )
    assert result.exit_code == 0
    assert json.loads(result.output) == {}


def test_cli_missing_dir_errors(tmp_path: Path):
    runner = CliRunner()
    result = runner.invoke(
        env_group, ["list", "--dir", str(tmp_path / "does-not-exist")]
    )
    assert result.exit_code == 2


def test_cli_export_output(tmp_path: Path):
    (tmp_path / ENV_FILE).write_text("A=1\nMSG=hi there\n")
    runner = CliRunner()
    result = runner.invoke(env_group, ["export", "--dir", str(tmp_path)])
    assert result.exit_code == 0
    assert result.output == "export A=1\nexport MSG='hi there'\n"


def test_cli_unset_and_copy_round_trip(tmp_path: Path):
    src = tmp_path / "src"
    dst = tmp_path / "dst"
    src.mkdir()
    dst.mkdir()
    runner = CliRunner()
    runner.invoke(
        env_group,
        ["set", "--dir", str(src)],
        input=json.dumps({"A": "1", "B": "2"}),
    )
    runner.invoke(
        env_group,
        ["copy", "--from", str(src), "--to", str(dst), "--key", "A"],
    )
    assert get_values(dst, ["A", "B"]) == {"A": "1"}
    runner.invoke(env_group, ["unset", "--dir", str(src), "--key", "A"])
    assert get_values(src, ["A", "B"]) == {"B": "2"}


# Make sure the module is importable under the project layout even when
# pytest is invoked from an odd cwd (mirrors test_repos defensive import).
def test_module_path_sanity():
    assert sys.modules["pocketshell.env"].ENV_FILE == ".env"
