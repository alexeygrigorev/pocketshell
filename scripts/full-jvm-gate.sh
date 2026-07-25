#!/usr/bin/python3 -I
"""Canonical local forced full-JVM gate for issue #1761."""

# The absolute isolated-Python shebang is the first trust boundary: Bash startup
# files, exported functions, and aliases are never evaluated, while -I ignores
# Python startup/configuration variables before this program can reject them.
# LD_* values are rejected and never forwarded, but a caller already using
# LD_PRELOAD has native-code authority before this dynamic interpreter starts;
# that pre-entry authority is outside the trusted-repository boundary.
import os
import pwd
import re
import stat
import subprocess
import sys
from hashlib import sha256
from pathlib import Path


MEMORY_LIMIT = "8G"
MINIMAL_PATH = "/usr/sbin:/usr/bin:/sbin:/bin"
JAVA_SEARCH_ROOTS = ("/usr/lib/jvm",)
SDK_FALLBACK_ROOTS = ("$HOME/Android/Sdk", "/usr/local/lib/android/sdk")
SDK_REQUIRED_FILES = (
    ("platform-tools/adb", True),
    ("platforms/android-35/android.jar", False),
    ("build-tools/35.0.0/aapt2", True),
)
EXPECTED_CGROUP_RUNNER_SHA256 = (
    "6e4ce5f99cf4a6666aa9ac0c097776fb2bd4b87b2cc01744ddd06e4fee114b20"
)
EXPECTED_SCOPE_RUNNER_SHA256 = (
    "39406248dd3de35f3b6f5c47ba1ca4c6462b296b0a4995192160b8db5d61761d"
)
EXPECTED_PROFILE_GUARD_SHA256 = (
    "3a0731b57cd63f57ef8ca230aeea6739a8b5a781aa97047ce57a158318b6a386"
)
GRADLE_ARGS = (
    "test",
    "--rerun-tasks",
    "--no-daemon",
    "--no-build-cache",
    "--no-parallel",
    "--max-workers=1",
    "-Dorg.gradle.jvmargs=-Xmx1536m",
    "-Pkotlin.daemon.jvmargs=-Xmx3072m",
)
REJECTED_ENVIRONMENT = (
    "BASH_ENV",
    "BASHOPTS",
    "ENV",
    "GRADLE_FLAGS",
    "GRADLE_OPTS",
    "GRADLE_USER_HOME",
    "JAVA_HOME",
    "JAVA_OPTS",
    "JAVA_TOOL_OPTIONS",
    "JDK_JAVA_OPTIONS",
    "PYTHONHOME",
    "PYTHONINSPECT",
    "PYTHONPATH",
    "PYTHONSTARTUP",
    "SHELLOPTS",
    "_JAVA_OPTIONS",
)
REJECTED_PREFIXES = (
    "BASH_FUNC_",
    "GRADLE_",
    "KOTLIN_",
    "LD_",
    "ORG_GRADLE_PROJECT_",
    "POCKETSHELL_SCOPE_",
    "POCKETSHELL_TEST_",
    "PYTHON",
)


class SdkValidationError(ValueError):
    """An Android SDK candidate is unsafe or incomplete."""


def validate_sdk_root(value: str, label: str) -> Path:
    if not value or any(
        ord(character) < 32 or ord(character) == 127 for character in value
    ):
        raise SdkValidationError(f"{label} contains an empty/control-character value")
    candidate = Path(value)
    if not candidate.is_absolute():
        raise SdkValidationError(f"{label} must be an absolute path")
    try:
        resolved = candidate.resolve(strict=True)
    except OSError as error:
        raise SdkValidationError(f"{label} does not resolve: {error}") from error
    if not resolved.is_dir():
        raise SdkValidationError(f"{label} is not a directory")
    if any(ord(character) < 32 or ord(character) == 127 for character in str(resolved)):
        raise SdkValidationError(f"{label} resolves to a control-character path")
    for relative_path, executable in SDK_REQUIRED_FILES:
        required_file = resolved / relative_path
        try:
            required_metadata = required_file.stat()
        except OSError as error:
            raise SdkValidationError(
                f"{label} is incomplete: missing {relative_path}: {error}"
            ) from error
        if not stat.S_ISREG(required_metadata.st_mode) or (
            executable and not os.access(required_file, os.X_OK)
        ):
            requirement = "regular executable" if executable else "regular file"
            raise SdkValidationError(
                f"{label} is incomplete: {relative_path} must be a {requirement}"
            )
    return resolved


def discover_android_sdk(
    environment_entries: list[bytes],
    home_directory: str,
) -> Path:
    configured: dict[str, list[str]] = {
        "ANDROID_HOME": [],
        "ANDROID_SDK_ROOT": [],
    }
    for environment_entry in environment_entries:
        name, separator, raw_value = environment_entry.partition(b"=")
        if not separator or name not in (b"ANDROID_HOME", b"ANDROID_SDK_ROOT"):
            continue
        try:
            value = raw_value.decode("utf-8", errors="strict")
        except UnicodeDecodeError as error:
            raise SdkValidationError(f"{name.decode()} must be valid UTF-8") from error
        configured[name.decode()].append(value)
    duplicate_names = [name for name, values in configured.items() if len(values) > 1]
    if duplicate_names:
        raise SdkValidationError(
            "duplicate Android SDK environment entries: " + ", ".join(duplicate_names)
        )

    supplied = {name: values[0] for name, values in configured.items() if values}
    if supplied:
        resolved = {
            name: validate_sdk_root(value, name) for name, value in supplied.items()
        }
        if len(set(resolved.values())) != 1:
            raise SdkValidationError(
                "ANDROID_HOME and ANDROID_SDK_ROOT must resolve to the same directory"
            )
        return next(iter(resolved.values()))

    for fallback in SDK_FALLBACK_ROOTS:
        candidate = (
            str(Path(home_directory) / fallback.removeprefix("$HOME/"))
            if fallback.startswith("$HOME/")
            else fallback
        )
        try:
            return validate_sdk_root(candidate, f"SDK fallback {fallback}")
        except SdkValidationError:
            continue
    raise SdkValidationError(
        "no complete Android SDK found; set ANDROID_HOME and/or ANDROID_SDK_ROOT"
    )


identity = pwd.getpwuid(os.getuid())
trusted_bash_env = str(Path(identity.pw_dir) / ".bashenv")
root_dir = Path(__file__).resolve().parent.parent
unit = f"pocketshell-full-jvm-{os.getpid()}"
arguments = sys.argv[1:]
profile_guard_arguments = None
if arguments == ["--profile-guard-self-test"]:
    profile_guard_arguments = ("--self-test", "--verified-preflight")
    arguments = []
elif arguments == ["--profile-guard-check"]:
    profile_guard_arguments = ()
    arguments = []

try:
    initial_environment = Path("/proc/self/environ").read_bytes().split(b"\0")
except OSError as error:
    sys.stderr.write(f"FAIL: cannot inspect the initial environment: {error}\n")
    raise SystemExit(2) from error
ci_entries = []
for environment_entry in initial_environment:
    name, separator, value = environment_entry.partition(b"=")
    if separator and name.strip().lower() == b"ci":
        ci_entries.append((name, value))
if ci_entries and not (
    profile_guard_arguments is not None and ci_entries == [(b"CI", b"true")]
):
    sys.stderr.write(
        "FAIL: CI must be unset, except exact CI=true for guard-only workflow modes\n"
    )
    raise SystemExit(2)

while arguments:
    argument = arguments.pop(0)
    if argument in ("--help", "-h"):
        print(
            "Usage: scripts/full-jvm-gate.sh [--unit <name>]\n\n"
            "Runs the complete forced JVM graph under the immutable issue #1761 "
            "8 GiB/single-worker/split-heap profile."
        )
        raise SystemExit(0)
    if argument == "--unit":
        if not arguments or not arguments[0]:
            sys.stderr.write("FAIL: --unit needs a value\n")
            raise SystemExit(2)
        unit = arguments.pop(0)
        continue
    if argument.startswith("--unit="):
        unit = argument.removeprefix("--unit=")
        if not unit:
            sys.stderr.write("FAIL: --unit needs a value\n")
            raise SystemExit(2)
        continue
    sys.stderr.write(
        f"FAIL: unexpected argument {argument!r}; the full JVM task graph is fixed\n"
    )
    raise SystemExit(2)

unsafe_names = sorted(
    name
    for name in os.environ
    if (
        name in REJECTED_ENVIRONMENT
        and not (name == "BASH_ENV" and os.environ[name] == trusted_bash_env)
    )
    or any(name.startswith(prefix) for prefix in REJECTED_PREFIXES)
)
if unsafe_names:
    sys.stderr.write(
        "FAIL: unsafe inherited environment for the full JVM gate: "
        + ", ".join(unsafe_names)
        + "\n"
    )
    raise SystemExit(2)

try:
    android_sdk = discover_android_sdk(initial_environment, identity.pw_dir)
except SdkValidationError as error:
    sys.stderr.write(f"FAIL: invalid Android SDK configuration: {error}\n")
    raise SystemExit(2) from error

runtime_directory = f"/run/user/{identity.pw_uid}"
clean_environment = {
    "ANDROID_HOME": str(android_sdk),
    "ANDROID_SDK_ROOT": str(android_sdk),
    "DBUS_SESSION_BUS_ADDRESS": f"unix:path={runtime_directory}/bus",
    "HOME": identity.pw_dir,
    "LANG": "C.UTF-8",
    "LC_ALL": "C.UTF-8",
    "LOGNAME": identity.pw_name,
    "PATH": MINIMAL_PATH,
    "POCKETSHELL_TEST_MEM": MEMORY_LIMIT,
    "TMPDIR": "/tmp",
    "USER": identity.pw_name,
    "XDG_RUNTIME_DIR": runtime_directory,
}
# Exact hosted CI=true authorizes only the cgroup-free guard modes above. It is
# deliberately omitted from this child allowlist, so neither the verified guard
# nor cgroup-run can observe CI fallback semantics.
entrypoint = Path(__file__).resolve()
profile_guard = root_dir / "scripts" / "check-full-jvm-gate-profile.sh"
try:
    guard_descriptor = os.open(profile_guard, os.O_RDONLY | os.O_NOFOLLOW)
    guard_metadata = os.fstat(guard_descriptor)
    guard_path_metadata = os.lstat(profile_guard)
except OSError as error:
    sys.stderr.write(f"FAIL: cannot open canonical profile guard: {error}\n")
    raise SystemExit(2) from error
if (
    not stat.S_ISREG(guard_metadata.st_mode)
    or guard_metadata.st_dev != guard_path_metadata.st_dev
    or guard_metadata.st_ino != guard_path_metadata.st_ino
    or guard_metadata.st_mode & 0o111 == 0
):
    sys.stderr.write(
        "FAIL: canonical profile guard must be the reviewed regular executable\n"
    )
    raise SystemExit(2)
with os.fdopen(os.dup(guard_descriptor), "rb") as guard_file:
    actual_guard_digest = sha256(guard_file.read()).hexdigest()
if actual_guard_digest != EXPECTED_PROFILE_GUARD_SHA256:
    sys.stderr.write(
        "FAIL: canonical profile guard content digest mismatch: "
        f"{actual_guard_digest}\n"
    )
    raise SystemExit(2)
os.set_inheritable(guard_descriptor, True)
guard_path_before_spawn = os.lstat(profile_guard)
if (
    guard_path_before_spawn.st_dev != guard_metadata.st_dev
    or guard_path_before_spawn.st_ino != guard_metadata.st_ino
):
    sys.stderr.write("FAIL: canonical profile guard changed before preflight\n")
    raise SystemExit(2)
verified_guard = f"/proc/self/fd/{guard_descriptor}"
preflight = (
    "/usr/bin/python3",
    "-I",
    verified_guard,
    *(profile_guard_arguments or ()),
    str(entrypoint),
)
preflight_result = os.spawnve(
    os.P_WAIT,
    "/usr/bin/python3",
    preflight,
    clean_environment,
)
os.close(guard_descriptor)
if preflight_result != 0:
    raise SystemExit(preflight_result)
if profile_guard_arguments is not None:
    raise SystemExit(0)

cgroup_runner = root_dir / "scripts" / "cgroup-run.sh"
scope_runner = root_dir / "scripts" / "lib" / "scope-run.sh"
validated_fds = []
for artifact, expected_digest, label in (
    (cgroup_runner, EXPECTED_CGROUP_RUNNER_SHA256, "cgroup runner"),
    (scope_runner, EXPECTED_SCOPE_RUNNER_SHA256, "scope runner"),
):
    try:
        descriptor = os.open(artifact, os.O_RDONLY | os.O_NOFOLLOW)
        metadata = os.fstat(descriptor)
        path_metadata = os.lstat(artifact)
    except OSError as error:
        sys.stderr.write(f"FAIL: cannot open canonical {label}: {error}\n")
        raise SystemExit(2) from error
    if (
        not stat.S_ISREG(metadata.st_mode)
        or metadata.st_dev != path_metadata.st_dev
        or metadata.st_ino != path_metadata.st_ino
        or metadata.st_mode & 0o111 == 0
    ):
        sys.stderr.write(
            f"FAIL: canonical {label} must be the reviewed regular executable\n"
        )
        raise SystemExit(2)
    with os.fdopen(os.dup(descriptor), "rb") as artifact_file:
        actual_digest = sha256(artifact_file.read()).hexdigest()
    if actual_digest != expected_digest:
        sys.stderr.write(
            f"FAIL: canonical {label} content digest mismatch: {actual_digest}\n"
        )
        raise SystemExit(2)
    validated_fds.append((descriptor, metadata, artifact, label))

java_candidates = set()
for search_root_text in JAVA_SEARCH_ROOTS:
    search_root = Path(search_root_text)
    java_candidates.add(search_root / "bin" / "java")
    java_candidates.update(search_root.glob("*/bin/java"))
java_home = None
for java_candidate in sorted(java_candidates):
    try:
        resolved_java = java_candidate.resolve(strict=True)
        java_metadata = resolved_java.stat()
    except OSError:
        continue
    if not stat.S_ISREG(java_metadata.st_mode) or not os.access(resolved_java, os.X_OK):
        continue
    if not any(
        resolved_java.is_relative_to(Path(root).resolve()) for root in JAVA_SEARCH_ROOTS
    ):
        continue
    try:
        java_version = subprocess.run(
            (str(resolved_java), "-version"),
            env=clean_environment,
            check=False,
            capture_output=True,
            text=True,
            timeout=5,
        )
    except (OSError, subprocess.SubprocessError):
        continue
    version_match = re.search(
        r'version "(?:1\.)?([0-9]+)(?:[._+-]|")',
        java_version.stderr + java_version.stdout,
    )
    if (
        java_version.returncode == 0
        and version_match
        and version_match.group(1) == "21"
    ):
        java_home = resolved_java.parent.parent
        break
if java_home is None:
    sys.stderr.write(
        "FAIL: no executable Java 21 runtime found under the fixed system roots\n"
    )
    raise SystemExit(2)
clean_environment["JAVA_HOME"] = str(java_home)

command = (
    str(cgroup_runner),
    "--unit",
    unit,
    "--",
    "./gradlew",
    *GRADLE_ARGS,
)
os.chdir(root_dir)
for descriptor, metadata, artifact, label in validated_fds:
    current_metadata = os.lstat(artifact)
    if (
        current_metadata.st_dev != metadata.st_dev
        or current_metadata.st_ino != metadata.st_ino
    ):
        sys.stderr.write(f"FAIL: canonical {label} changed before execution\n")
        raise SystemExit(2)
    os.close(descriptor)

# The repository is the trust boundary. The descriptor/inode recheck rejects
# ordinary replacement and symlink redirection; it does not claim resistance to
# a hostile writer racing the final trusted-repository pathname lookup.
os.execve(
    "/usr/bin/bash",
    ("/usr/bin/bash", *command),
    clean_environment,
)
