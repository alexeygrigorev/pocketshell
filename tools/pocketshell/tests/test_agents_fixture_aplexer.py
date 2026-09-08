"""Static and unit contracts for the aplexer-only agents fixture."""

from __future__ import annotations

import os
import re
import subprocess
import tomllib
from pathlib import Path

import pytest


REPO_ROOT = Path(__file__).resolve().parents[3]
DOCKER_DIR = REPO_ROOT / "tests" / "docker"
DOCKERFILE = DOCKER_DIR / "Dockerfile.agents"
ENTRYPOINT = DOCKER_DIR / "agent-entrypoint.sh"
SHIM = DOCKER_DIR / "agent-bin" / "pocketshell"
SELFCHECK = DOCKER_DIR / "agents-aplexer-selfcheck.py"
GUARD = REPO_ROOT / "scripts" / "test-agents-fixture-aplexer.sh"
COMPOSE = DOCKER_DIR / "docker-compose.yml"

JOURNEYS = (
    REPO_ROOT / "app2/src/androidTest/java/com/pocketshell/next/tree/J02SessionTreeListJourney.kt",
    REPO_ROOT / "app2/src/androidTest/java/com/pocketshell/next/terminal/J03AttachAndTypeJourney.kt",
    REPO_ROOT / "app2/src/androidTest/java/com/pocketshell/next/tree/J04CreateSessionJourney.kt",
    REPO_ROOT / "app2/src/androidTest/java/com/pocketshell/next/tree/J14StopSessionJourney.kt",
)


def _text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _pinned_version() -> str:
    data = tomllib.loads(_text(REPO_ROOT / "tools/pocketshell/pyproject.toml"))
    requirements = [
        requirement
        for requirement in data["project"]["dependencies"]
        if requirement.split(";", 1)[0].strip().startswith("aplexer")
    ]
    assert len(requirements) == 1
    return requirements[0].split("==", 1)[1].split(";", 1)[0].strip()


def test_agents_image_is_glibc_and_installs_both_bundled_binaries() -> None:
    text = _text(DOCKERFILE)
    assert "FROM debian:trixie-slim" in text
    assert "curl -fsSL -o /usr/bin/a" in text
    assert "curl -fsSL -o /usr/bin/aplexer" in text
    assert "/usr/bin/a \"$base/a-${asset_arch}\"" in text
    assert "/usr/bin/aplexer \"$base/aplexer-${asset_arch}\"" in text
    assert "tmux" not in text.lower()
    assert "pocketshell-fixture-sessions" not in text
    assert "--backend" not in text


def test_fixture_derives_the_binary_release_from_the_production_pin() -> None:
    text = _text(DOCKERFILE)
    match = re.search(
        r'ver="\$\(sed -n .*?pyproject\.toml.*?\)"', text, re.DOTALL
    )
    assert match, "Dockerfile.agents must derive the aplexer release from pyproject.toml"
    assert "aplexer==\\([0-9]" in match.group(0)
    assert _pinned_version() in _text(REPO_ROOT / "tools/pocketshell/uv.lock")


def test_build_runs_the_fail_loudly_real_lifecycle_check() -> None:
    dockerfile = _text(DOCKERFILE)
    selfcheck = _text(SELFCHECK)
    assert re.search(
        r"^RUN su testuser .*pocketshell-fixture-aplexer-selfcheck",
        dockerfile,
        re.MULTILINE,
    )
    assert '"-m", "pocketshell"' in selfcheck
    for verb in ("sessions", "create", "list", "kill"):
        assert f'"{verb}"' in selfcheck
    assert not re.search(r'["\']--version["\']', selfcheck)
    assert 'payload.get("schema") != 3' in selfcheck
    assert 'payload.get("errors")' in selfcheck


def test_selfcheck_compiles() -> None:
    subprocess.run(
        [os.environ.get("PYTHON", "python3"), "-m", "py_compile", str(SELFCHECK)],
        check=True,
        cwd=REPO_ROOT,
    )


def test_fixture_shim_delegates_every_session_verb_to_the_real_cli() -> None:
    text = _text(SHIM)
    assert "set -- create --mem none \"$@\"" in text
    assert "exec /usr/local/bin/pocketshell-real sessions \"$@\"" in text
    assert "pocketshell-fixture-sessions" not in text
    assert "tmux" not in text.lower()
    assert "--backend" not in text
    assert '"manager"' not in text


def test_agents_entrypoint_precreates_real_journey_workspaces() -> None:
    text = _text(ENTRYPOINT)
    for workspace in ("j04-new", "j04-twice", "j04-idempotent"):
        assert f"/home/testuser/git/{workspace}" in text
    assert "install -d -o testuser -g testuser \"$workspace\"" in text


def test_required_journeys_use_real_aplexer_lifecycle() -> None:
    for path in JOURNEYS:
        text = _text(path)
        lowered = text.lower()
        assert "tmux" not in lowered, path
        assert "tmuxctl" not in lowered, path
        assert "--backend" not in text, path
        assert "sessions create" in text, path
        assert "sessions list" in text, path
    assert "sessions kill" in _text(JOURNEYS[0])
    assert "sessions attach" in _text(JOURNEYS[1])
    assert "a capture" in _text(JOURNEYS[1])
    assert "sessions kill" in _text(JOURNEYS[3])


def test_compose_has_one_product_agents_fixture_and_no_retired_service() -> None:
    text = _text(COMPOSE)
    assert "  agents:" in text
    assert "Dockerfile.agents" in text
    service_block = text.split("services:", 1)[1]
    assert re.search(r"^  tmux:", service_block, re.MULTILINE) is None


def test_docker_guard_is_executable_and_wired_into_ci() -> None:
    assert GUARD.is_file()
    assert os.access(GUARD, os.X_OK)
    workflow = _text(REPO_ROOT / ".github/workflows/tests.yml")
    assert "scripts/test-agents-fixture-aplexer.sh\n" in workflow
    assert "scripts/test-agents-fixture-aplexer.sh --docker" in workflow
    guard = _text(GUARD)
    assert "sessions create" in guard
    assert "sessions list --json" in guard
    assert "sessions attach" in guard
    assert "sessions kill" in guard
    assert "a capture --workspace" in guard


@pytest.mark.parametrize(
    "relative",
    (
        "agent-bin/tmux",
        "agent-bin/tmuxctl",
        "agent-bin-daemon/tmuxctl",
        "agent-fixtures/tmuxctl-list.txt",
        "agent-fixtures/pocketshell-sessions-list.txt",
        "agent-bin/pocketshell-fixture-sessions",
    ),
)
def test_retired_product_fixture_files_are_gone(relative: str) -> None:
    assert not (DOCKER_DIR / relative).exists(), relative
