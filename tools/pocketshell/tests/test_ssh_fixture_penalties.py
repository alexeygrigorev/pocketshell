"""Static guard: no Docker test fixture may ship a penalising sshd (issue #2150).

THE CLASS THIS EXISTS FOR

OpenSSH >= 9.8 enables ``PerSourcePenalties`` by default, and the alpine fixture
base now ships 10.3p1. The feature blocks a SOURCE ADDRESS once its penalties
pass ``min:15s``. On the public internet that is right; on a test fixture it is
poison, because a fixture has exactly one source: every emulator/host client
reaches a published container port through the single docker gateway, so four
routine harness auth failures penalise *every* lane, including lanes that did
nothing wrong.

The cost is not a flake. A penalised lane presents as connection failures inside
the product under test, indistinguishable from a real regression in SSH
connect/lease/reattach — so it manufactures evidence pointing at a product bug
that does not exist. It burned two full emulator runs in #2111 before diagnosis,
and the container reported ``healthy`` the entire time.

WHY A STATIC GUARD AND NOT ONLY THE HEALTHCHECK

The healthcheck (see ``x-ssh-healthcheck`` in tests/docker/docker-compose.yml)
is the behavioural defence and the stronger one: it observes the real daemon.
But it only speaks when somebody brings a fixture up, which happens in the
batched Docker/emulator jobs, not per push. #2150's whole origin story is that
the fix existed only at RUNTIME — appended to a live container's config, so any
rebuild silently restored the penalty. A silent revert of a committed fixture
config is exactly how this comes back, so it gets a per-push check that needs
neither Docker nor an emulator. This suite runs in the required ``Python utility
tests (pocketshell)`` job.

WHAT IS CHECKED

C1  tests/docker/sshd_config disables PerSourcePenalties.
C2  every fixture Dockerfile installing openssh-server either inherits that
    config or is a named, justified, existence-checked exception.
C3  every SSH healthcheck asserts the effective daemon config, so a fixture that
    can penalise cannot report healthy.

C2's exception set is deliberately tiny and its members are re-verified to
exist, so it cannot rot into an allowlist that quietly swallows a new fixture.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[3]
DOCKER_DIR = REPO_ROOT / "tests" / "docker"
SHARED_SSHD_CONFIG = DOCKER_DIR / "sshd_config"

# Fixtures that run an sshd but deliberately do NOT copy the shared config.
# Each entry must state why, and the path must still exist (see test_c2_*).
#
# real-agent: Debian bookworm, OpenSSH 9.2 — predates PerSourcePenalties
# entirely, where the directive is not "defaulted off" but an unknown keyword
# ("Bad configuration option") that makes sshd refuse to start. Copying the
# shared alpine config would also point Subsystem sftp at the wrong path. It is
# covered instead by C3: its healthcheck asserts the effective config, so the
# day it is rebased onto an OpenSSH >= 9.8 base it goes UNHEALTHY rather than
# silently penalising every lane.
SHARED_CONFIG_EXEMPT = {
    "tests/docker/real-agent/Dockerfile": "Debian OpenSSH 9.2 predates PerSourcePenalties",
}


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _effective_directives(config_text: str, name: str) -> list[str]:
    """Values of `name` in an sshd_config, ignoring comments. Lowercased."""
    values = []
    for raw in config_text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split(None, 1)
        if parts[0].lower() == name.lower():
            values.append(parts[1].strip().lower() if len(parts) > 1 else "")
    return values


def _rel(path: Path) -> str:
    """Repo-relative id. Two fixtures are named plain `Dockerfile`, so a
    basename id would render them as `Dockerfile0`/`Dockerfile1` and a failure
    would not name the fixture it is about."""
    return path.relative_to(DOCKER_DIR).as_posix()


def _sshd_fixture_dockerfiles() -> list[Path]:
    """Every Dockerfile under tests/docker that ends up running an sshd.

    Either it installs one itself, or it extends a pocketshell-test image that
    did (Dockerfile.tmux builds on pocketshell-test:ssh). The second arm matters:
    without it the derived fixtures are invisible to this guard.
    """
    found = []
    for path in sorted(DOCKER_DIR.rglob("Dockerfile*")):
        if not path.is_file():
            continue
        text = _read(path)
        installs = "openssh-server" in text
        derives = re.search(r"^FROM\s+pocketshell-test:", text, re.MULTILINE) is not None
        if installs or derives:
            found.append(path)
    return found


def _compose_files() -> list[Path]:
    return sorted(
        p
        for p in DOCKER_DIR.rglob("*compose*.yml")
        if p.is_file()
    )


# --------------------------------------------------------------------------
# C1 — the committed shared config disables the penalty.
# --------------------------------------------------------------------------


def test_c1_shared_sshd_config_disables_per_source_penalties() -> None:
    assert SHARED_SSHD_CONFIG.is_file(), f"missing {SHARED_SSHD_CONFIG}"

    values = _effective_directives(_read(SHARED_SSHD_CONFIG), "PerSourcePenalties")

    assert values, (
        "tests/docker/sshd_config does not set PerSourcePenalties (issue #2150).\n"
        "OpenSSH >= 9.8 enables it by DEFAULT, so omitting the directive means "
        "penalties are ON. Every emulator lane NATs through one docker gateway "
        "address, so a penalty earned by any lane refuses all of them while the "
        "container still reports healthy.\n"
        "Remedy: add `PerSourcePenalties no` to tests/docker/sshd_config."
    )
    assert values == ["no"], (
        "tests/docker/sshd_config sets PerSourcePenalties to "
        f"{values!r}; a single-source test fixture must disable it entirely "
        "(issue #2150). Rate-limiting by source is meaningless when every lane "
        "shares one source address."
    )


# --------------------------------------------------------------------------
# C2 — every sshd fixture inherits that config, or is a justified exception.
# --------------------------------------------------------------------------


def test_c2_sshd_fixtures_exist_to_check() -> None:
    """Guard the guard: a scan that finds nothing must not read as a pass."""
    dockerfiles = _sshd_fixture_dockerfiles()
    assert len(dockerfiles) >= 7, (
        f"only found {len(dockerfiles)} sshd fixture Dockerfiles under "
        f"{DOCKER_DIR}; the scan is broken, not the tree."
    )


@pytest.mark.parametrize("dockerfile", _sshd_fixture_dockerfiles(), ids=_rel)
def test_c2_sshd_fixture_inherits_shared_config(dockerfile: Path) -> None:
    rel = dockerfile.relative_to(REPO_ROOT).as_posix()
    text = _read(dockerfile)

    # A fixture may inherit either by copying the shared config or by FROM-ing
    # an image that already did (e.g. Dockerfile.tmux extends pocketshell-test:ssh).
    copies_shared = re.search(r"^COPY\s+\S*sshd_config\s", text, re.MULTILINE) is not None
    extends_fixture = re.search(r"^FROM\s+pocketshell-test:", text, re.MULTILINE) is not None

    if rel in SHARED_CONFIG_EXEMPT:
        assert not copies_shared, (
            f"{rel} is listed in SHARED_CONFIG_EXEMPT but now copies the shared "
            "sshd_config. Drop it from the exemption list."
        )
        return

    assert copies_shared or extends_fixture, (
        f"{rel} installs openssh-server but neither copies tests/docker/sshd_config "
        "nor extends a pocketshell-test image that does (issue #2150).\n"
        "OpenSSH >= 9.8 turns PerSourcePenalties ON by default, so a fixture "
        "without that config penalises the shared docker-gateway source and "
        "refuses every journey lane — while still reporting healthy.\n"
        "Remedy: `COPY sshd_config /etc/ssh/sshd_config`, or add the fixture to "
        "SHARED_CONFIG_EXEMPT with a written reason (and keep its healthcheck's "
        "penalty assertion, which is what actually covers the exempt case)."
    )


def test_c2_exemptions_still_exist() -> None:
    """An exemption naming a deleted file is dead text that hides new fixtures."""
    for rel in SHARED_CONFIG_EXEMPT:
        assert (REPO_ROOT / rel).is_file(), (
            f"SHARED_CONFIG_EXEMPT names {rel}, which no longer exists. "
            "Remove the stale entry so the exemption list cannot rot."
        )


# --------------------------------------------------------------------------
# C3 — the healthcheck can observe the penalising state.
# --------------------------------------------------------------------------


def _ssh_healthchecks(compose_text: str) -> list[str]:
    """Each CMD-SHELL healthcheck body that runs the fixture SSH probe.

    Compose folds `>-` blocks onto one line, so match on the flattened text of
    each `test:` list entry.
    """
    flattened = re.sub(r"\s+", " ", compose_text)
    return [
        body
        for body in re.findall(r"- CMD-SHELL - (.+?) interval:", flattened)
        if "testuser@localhost" in body
    ]


def test_c3_ssh_healthchecks_exist_to_check() -> None:
    total = sum(len(_ssh_healthchecks(_read(p))) for p in _compose_files())
    assert total >= 2, (
        f"found only {total} SSH healthchecks across {[p.name for p in _compose_files()]}; "
        "the parser is broken, not the tree."
    )


@pytest.mark.parametrize("compose", _compose_files(), ids=lambda p: p.name)
def test_c3_ssh_healthcheck_asserts_effective_penalty_config(compose: Path) -> None:
    rel = compose.relative_to(REPO_ROOT).as_posix()
    bodies = _ssh_healthchecks(_read(compose))

    if not bodies:
        pytest.skip(f"{rel} declares no SSH healthcheck")

    for body in bodies:
        assert "sshd -T" in body and "persourcepenalties" in body.lower(), (
            f"an SSH healthcheck in {rel} probes connectivity but never checks "
            "whether sshd may penalise (issue #2150).\n"
            "The probe runs from inside the container, so its source is ::1 — a "
            "DIFFERENT source from the docker gateway every client under test "
            "arrives on. It therefore stays green while sshd refuses 100% of the "
            "traffic under test. That blind spot is what made #2150 cost two "
            "emulator runs instead of one obvious red.\n"
            "Remedy: keep the `sshd -T` / persourcepenalties assertion from the "
            "`x-ssh-healthcheck` anchor in tests/docker/docker-compose.yml."
        )
