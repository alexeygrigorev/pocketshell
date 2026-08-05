#!/usr/bin/python3 -I
"""Fail-closed canonical-template guard for the issue #1761 local JVM gate."""

import hashlib
import io
import os
import pwd
import re
import shutil
import stat
import subprocess
import sys
import tempfile
from contextlib import redirect_stderr
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parent.parent
DEFAULT_ENTRYPOINT = ROOT_DIR / "scripts" / "full-jvm-gate.sh"
EXPECTED_ENTRYPOINT_SHA256 = (
    "7b3612eefb7c3ed1936540a6cb98905793e5d45d44c5238d386ce9d1a784c4af"
)
EXPECTED_CGROUP_RUNNER_SHA256 = (
    "6e4ce5f99cf4a6666aa9ac0c097776fb2bd4b87b2cc01744ddd06e4fee114b20"
)
EXPECTED_SCOPE_RUNNER_SHA256 = (
    "39406248dd3de35f3b6f5c47ba1ca4c6462b296b0a4995192160b8db5d61761d"
)


def fail(message: str) -> None:
    sys.stderr.write(f"FAIL: {message}\n")
    raise SystemExit(1)


def validate_regular_executable(
    artifact: Path,
    label: str,
    expected_digest: str | None = None,
) -> None:
    try:
        metadata = artifact.lstat()
    except OSError as error:
        fail(f"{label} not found: {artifact}: {error}")
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_mode & 0o111 == 0:
        fail(f"{label} must be a non-symlink regular executable: {artifact}")
    if expected_digest is None:
        return
    actual_digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    if actual_digest != expected_digest:
        fail(
            f"{label} differs from the canonical reviewed content "
            f"(expected {expected_digest}, found {actual_digest})"
        )


def canonical_entrypoint_digest(entrypoint_bytes: bytes) -> str:
    normalized, substitutions = re.subn(
        rb'(?<=EXPECTED_PROFILE_GUARD_SHA256 = \(\n    ")[0-9a-f]{64}'
        rb'(?="\n\))',
        b"0" * 64,
        entrypoint_bytes,
        count=1,
    )
    if substitutions != 1:
        fail("full-JVM entrypoint must contain one pinned profile-guard digest")
    return hashlib.sha256(normalized).hexdigest()


def validate_entrypoint(entrypoint: Path) -> None:
    validate_regular_executable(
        entrypoint,
        "full-JVM entrypoint",
    )
    actual_entrypoint_digest = canonical_entrypoint_digest(entrypoint.read_bytes())
    if actual_entrypoint_digest != EXPECTED_ENTRYPOINT_SHA256:
        fail(
            "full-JVM entrypoint differs from the canonical reviewed content "
            f"(expected {EXPECTED_ENTRYPOINT_SHA256}, "
            f"found {actual_entrypoint_digest})"
        )
    scripts_dir = entrypoint.parent
    validate_regular_executable(
        scripts_dir / "check-full-jvm-gate-profile.sh",
        "full-JVM profile guard",
    )
    validate_regular_executable(
        scripts_dir / "cgroup-run.sh",
        "cgroup runner",
        EXPECTED_CGROUP_RUNNER_SHA256,
    )
    validate_regular_executable(
        scripts_dir / "lib" / "scope-run.sh",
        "scope runner",
        EXPECTED_SCOPE_RUNNER_SHA256,
    )


def clean_test_environment() -> dict[str, str]:
    environment = {
        "HOME": os.environ.get("HOME", "/tmp"),
        "LOGNAME": os.environ.get("LOGNAME", "pocketshell-test"),
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
        "USER": os.environ.get("USER", "pocketshell-test"),
    }
    # Issue #2007: keep every fixture entrypoint's Gradle output lock inside the
    # self-test's own temporary directory. It is a pure relocation (the lock can
    # never be disabled) and it stops ~10 fixture runs per invocation from
    # leaving lock files in the real per-user lock directory. The gate does NOT
    # forward this variable into the child environment, so the exact-environment
    # assertion below is unaffected.
    lock_directory = os.environ.get("POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR", "")
    if lock_directory:
        environment["POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR"] = lock_directory
    return environment


def run_entrypoint(
    entrypoint: Path,
    fixture_scripts: Path,
    arguments: tuple[str, ...] = (),
    additions: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    oracle = fixture_scripts / "oracle.txt"
    environment = clean_test_environment()
    if additions:
        environment.update(additions)
    result = subprocess.run(
        (str(entrypoint), *arguments),
        cwd=fixture_scripts.parent,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )
    if oracle.exists() and result.returncode != 0:
        fail(
            f"entrypoint returned {result.returncode} after reaching fake cgroup-run: "
            f"{result.stderr}"
        )
    return result


def assert_rejected_before_cgroup(
    label: str,
    entrypoint: Path,
    fixture_scripts: Path,
    arguments: tuple[str, ...] = (),
    additions: dict[str, str] | None = None,
) -> None:
    oracle = fixture_scripts / "oracle.txt"
    oracle.unlink(missing_ok=True)
    result = run_entrypoint(
        entrypoint,
        fixture_scripts,
        arguments=arguments,
        additions=additions,
    )
    if result.returncode == 0 or oracle.exists():
        fail(f"self-test case {label!r} returned success or reached fake cgroup-run")


def write_mutation(
    source: str,
    destination: Path,
    label: str,
    old: str,
    new: str,
) -> None:
    if source.count(old) != 1:
        fail(f"self-test mutation {label!r} did not have one exact target")
    mutated = source.replace(old, new)
    if mutated == source:
        fail(f"self-test mutation {label!r} did not change the entrypoint")
    compile(mutated, str(destination), "exec")
    destination.write_text(mutated)
    destination.chmod(0o755)


def write_paired_fixture(
    entrypoint_source: str,
    guard_source: str,
    entrypoint_destination: Path,
    guard_destination: Path,
    java_root: Path,
    sdk_root: Path,
    runner_digest: str,
    replacements: tuple[tuple[str, str], ...] = (),
    guard_replacements: tuple[tuple[str, str], ...] = (),
) -> None:
    paired_entrypoint, substitutions = re.subn(
        r"^JAVA_SEARCH_ROOTS = .*?$",
        f"JAVA_SEARCH_ROOTS = ({str(java_root)!r},)",
        entrypoint_source,
        count=1,
        flags=re.MULTILINE,
    )
    if substitutions != 1:
        fail("paired fixture could not replace the Java search roots exactly once")
    paired_entrypoint, substitutions = re.subn(
        r"^SDK_FALLBACK_ROOTS = .*?$",
        f"SDK_FALLBACK_ROOTS = ({str(sdk_root)!r},)",
        paired_entrypoint,
        count=1,
        flags=re.MULTILINE,
    )
    if substitutions != 1:
        fail("paired fixture could not replace the SDK fallback roots exactly once")
    if EXPECTED_CGROUP_RUNNER_SHA256 != runner_digest:
        paired_entrypoint = paired_entrypoint.replace(
            EXPECTED_CGROUP_RUNNER_SHA256,
            runner_digest,
        )
    for old, new in replacements:
        if paired_entrypoint.count(old) != 1:
            fail(f"paired fixture replacement target was not unique: {old!r}")
        paired_entrypoint = paired_entrypoint.replace(old, new)
    paired_entrypoint_digest = canonical_entrypoint_digest(paired_entrypoint.encode())
    paired_guard = guard_source.replace(
        EXPECTED_ENTRYPOINT_SHA256,
        paired_entrypoint_digest,
    )
    if EXPECTED_CGROUP_RUNNER_SHA256 != runner_digest:
        paired_guard = paired_guard.replace(
            EXPECTED_CGROUP_RUNNER_SHA256,
            runner_digest,
        )
    for old, new in guard_replacements:
        if paired_guard.count(old) != 1:
            fail(f"paired guard replacement target was not unique: {old!r}")
        paired_guard = paired_guard.replace(old, new)
    paired_guard_digest = hashlib.sha256(paired_guard.encode()).hexdigest()
    paired_entrypoint, guard_digest_substitutions = re.subn(
        r'(?<=EXPECTED_PROFILE_GUARD_SHA256 = \(\n    ")[0-9a-f]{64}'
        r'(?="\n\))',
        paired_guard_digest,
        paired_entrypoint,
        count=1,
    )
    if guard_digest_substitutions != 1:
        fail("paired fixture could not pin the profile guard exactly once")

    compile(paired_entrypoint, str(entrypoint_destination), "exec")
    entrypoint_destination.write_text(paired_entrypoint)
    entrypoint_destination.chmod(0o755)
    compile(paired_guard, str(guard_destination), "exec")
    guard_destination.write_text(paired_guard)
    guard_destination.chmod(0o755)


def self_test(
    entrypoint: Path,
    include_workflow_self_test: bool = True,
) -> None:
    checks = 0
    validate_entrypoint(entrypoint)
    checks += 1

    with tempfile.TemporaryDirectory(prefix="pocketshell-1761-") as temp_dir:
        os.environ["POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR"] = str(
            Path(temp_dir) / "gradle-output-locks"
        )
        fixture_root = Path(temp_dir) / "repo"
        fixture_scripts = fixture_root / "scripts"
        fixture_scripts.mkdir(parents=True)
        (fixture_scripts / "lib").mkdir()
        copied_entrypoint = fixture_scripts / "full-jvm-gate.sh"
        copied_guard = fixture_scripts / "check-full-jvm-gate-profile.sh"
        shutil.copy2(
            entrypoint.parent / "lib" / "scope-run.sh",
            fixture_scripts / "lib" / "scope-run.sh",
        )

        fake_cgroup = fixture_scripts / "cgroup-run.sh"
        fake_cgroup.write_text(
            "#!/bin/dash\n"
            "set -eu\n"
            'oracle_dir="${0%/*}"\n'
            ': > "$oracle_dir/oracle.txt"\n'
            'for argument in "$@"; do\n'
            '  /usr/bin/printf "%s\\\\0" "$argument" >> "$oracle_dir/oracle.txt"\n'
            "done\n"
            '/bin/cp --remove-destination "/proc/$$/environ" '
            '"$oracle_dir/environment.bin"\n'
        )
        fake_cgroup.chmod(0o755)
        fake_runner_digest = hashlib.sha256(fake_cgroup.read_bytes()).hexdigest()

        fake_java_home = fixture_root / "fake-java-21"
        (fake_java_home / "bin").mkdir(parents=True)
        fake_java = fake_java_home / "bin" / "java"
        fake_java.write_text(
            "#!/bin/dash\nprintf 'openjdk version \"21.0.99\"\\n' >&2\n"
        )
        fake_java.chmod(0o755)
        fake_sdk_root = fixture_root / "fake-android-sdk"
        (fake_sdk_root / "platform-tools").mkdir(parents=True)
        (fake_sdk_root / "platforms" / "android-35").mkdir(parents=True)
        (fake_sdk_root / "build-tools" / "35.0.0").mkdir(parents=True)
        fake_adb = fake_sdk_root / "platform-tools" / "adb"
        fake_adb.write_text("#!/bin/dash\nexit 0\n")
        fake_adb.chmod(0o755)
        (fake_sdk_root / "platforms" / "android-35" / "android.jar").write_bytes(
            b"fake android platform\n"
        )
        fake_aapt2 = fake_sdk_root / "build-tools" / "35.0.0" / "aapt2"
        fake_aapt2.write_text("#!/bin/dash\nexit 0\n")
        fake_aapt2.chmod(0o755)
        guard_environment_capture = fixture_root / "guard-environment.bin"
        write_paired_fixture(
            entrypoint.read_text(),
            Path(__file__).read_text(),
            copied_entrypoint,
            copied_guard,
            fake_java_home,
            fake_sdk_root,
            fake_runner_digest,
            guard_replacements=(
                (
                    'if any(name.strip().lower() == "ci" for name in os.environ):\n',
                    f"Path({str(guard_environment_capture)!r}).write_bytes("
                    'Path("/proc/self/environ").read_bytes())\n'
                    'if any(name.strip().lower() == "ci" for name in os.environ):\n',
                ),
            ),
        )

        result = run_entrypoint(
            copied_entrypoint,
            fixture_scripts,
            arguments=("--unit", "issue-1761-self-test"),
        )
        if result.returncode != 0:
            fail(f"canonical fake-cgroup execution failed: {result.stderr}")
        guard_pass_line = (
            "PASS: forced full-JVM gate matches the immutable complete-graph "
            "8 GiB canonical template"
        )
        if result.stdout.splitlines().count(guard_pass_line) != 1:
            fail("verified profile guard did not run exactly once before cgroup")
        checks += 1

        expected_arguments = (
            "--unit",
            "issue-1761-self-test",
            "--",
            "./gradlew",
            "test",
            "--rerun-tasks",
            "--no-daemon",
            "--no-build-cache",
            "--no-parallel",
            "--max-workers=1",
            "-Dorg.gradle.jvmargs=-Xmx1536m",
            "-Pkotlin.daemon.jvmargs=-Xmx3072m",
        )
        actual_arguments = tuple(
            item.decode()
            for item in (fixture_scripts / "oracle.txt").read_bytes().split(b"\0")[:-1]
        )
        if actual_arguments != expected_arguments:
            fail(
                "canonical fake-cgroup argv mismatch: "
                f"expected {expected_arguments!r}, found {actual_arguments!r}"
            )
        environment_items = tuple(
            item.decode()
            for item in (fixture_scripts / "environment.bin").read_bytes().split(b"\0")
            if item
        )
        environment = dict(item.split("=", 1) for item in environment_items)
        identity = pwd.getpwuid(os.getuid())
        runtime_directory = f"/run/user/{identity.pw_uid}"
        expected_environment = {
            "ANDROID_HOME": str(fake_sdk_root.resolve()),
            "ANDROID_SDK_ROOT": str(fake_sdk_root.resolve()),
            "DBUS_SESSION_BUS_ADDRESS": f"unix:path={runtime_directory}/bus",
            "HOME": identity.pw_dir,
            "JAVA_HOME": str(fake_java_home.resolve()),
            "LANG": "C.UTF-8",
            "LC_ALL": "C.UTF-8",
            "LOGNAME": identity.pw_name,
            "PATH": "/usr/sbin:/usr/bin:/sbin:/bin",
            "POCKETSHELL_TEST_MEM": "8G",
            "TMPDIR": "/tmp",
            "USER": identity.pw_name,
            "XDG_RUNTIME_DIR": runtime_directory,
        }
        if environment != expected_environment:
            fail(
                "fake-cgroup environment did not match every independent value: "
                f"{environment!r}"
            )
        checks += 1

        def assert_sdk_environment(
            label: str,
            additions: dict[str, str],
        ) -> None:
            nonlocal checks
            result = run_entrypoint(
                copied_entrypoint,
                fixture_scripts,
                arguments=("--unit", "issue-1761-self-test"),
                additions=additions,
            )
            if result.returncode != 0:
                fail(f"{label} SDK environment was rejected: {result.stderr}")
            sdk_arguments = tuple(
                item.decode()
                for item in (fixture_scripts / "oracle.txt")
                .read_bytes()
                .split(b"\0")[:-1]
            )
            if sdk_arguments != expected_arguments:
                fail(f"{label} SDK input altered the fixed memory/task profile")
            child_environment = dict(
                item.decode().split("=", 1)
                for item in (fixture_scripts / "environment.bin")
                .read_bytes()
                .split(b"\0")
                if item
            )
            expected_sdk = str(fake_sdk_root.resolve())
            if (
                child_environment.get("ANDROID_HOME") != expected_sdk
                or child_environment.get("ANDROID_SDK_ROOT") != expected_sdk
            ):
                fail(f"{label} SDK was not canonicalized into both child variables")
            checks += 1

        assert_sdk_environment(
            "normal local ANDROID_HOME",
            {"ANDROID_HOME": str(fake_sdk_root)},
        )
        assert_sdk_environment(
            "ANDROID_SDK_ROOT-only local environment",
            {"ANDROID_SDK_ROOT": str(fake_sdk_root)},
        )
        sdk_alias = fixture_root / "github-android-sdk"
        sdk_alias.symlink_to(fake_sdk_root, target_is_directory=True)
        assert_sdk_environment(
            "GitHub Actions matching SDK variables",
            {
                "ANDROID_HOME": str(fake_sdk_root),
                "ANDROID_SDK_ROOT": str(sdk_alias),
            },
        )

        def assert_sdk_rejected(
            label: str,
            additions: dict[str, str],
        ) -> None:
            nonlocal checks
            oracle = fixture_scripts / "oracle.txt"
            oracle.unlink(missing_ok=True)
            result = run_entrypoint(
                copied_entrypoint,
                fixture_scripts,
                additions=additions,
            )
            if (
                result.returncode == 0
                or oracle.exists()
                or "invalid Android SDK configuration" not in result.stderr
            ):
                fail(f"{label} SDK configuration escaped preflight rejection")
            checks += 1

        sdk_file = fixture_root / "sdk-is-a-file"
        sdk_file.write_text("not a directory\n")
        incomplete_sdk = fixture_root / "incomplete-sdk"
        incomplete_sdk.mkdir()
        invalid_sdk_environments = (
            (
                "disagreeing roots",
                {
                    "ANDROID_HOME": str(fake_sdk_root),
                    "ANDROID_SDK_ROOT": str(incomplete_sdk),
                },
            ),
            ("empty root", {"ANDROID_HOME": ""}),
            ("relative root", {"ANDROID_HOME": "relative/android-sdk"}),
            (
                "missing root",
                {"ANDROID_SDK_ROOT": str(fixture_root / "missing-sdk")},
            ),
            ("non-directory root", {"ANDROID_HOME": str(sdk_file)}),
            ("incomplete root", {"ANDROID_HOME": str(incomplete_sdk)}),
            (
                "control-character root",
                {"ANDROID_HOME": f"{fake_sdk_root}\n"},
            ),
        )
        for label, additions in invalid_sdk_environments:
            assert_sdk_rejected(label, additions)

        non_executable_sdk = fixture_root / "non-executable-sdk"
        shutil.copytree(fake_sdk_root, non_executable_sdk)
        (non_executable_sdk / "platform-tools" / "adb").chmod(0o644)
        assert_sdk_rejected(
            "non-executable required tool",
            {"ANDROID_HOME": str(non_executable_sdk)},
        )

        duplicate_sdk_exec = fixture_root / "duplicate-sdk-env-exec.py"
        duplicate_sdk_exec.write_text(
            "import ctypes\n"
            "import os\n"
            "import sys\n"
            "target = os.fsencode(sys.argv[1])\n"
            "sdk = os.fsencode(sys.argv[2])\n"
            "environment_items = [\n"
            "    os.fsencode(f'{name}={value}')\n"
            "    for name, value in os.environ.items()\n"
            "    if name != 'ANDROID_HOME'\n"
            "]\n"
            "environment_items.extend([b'ANDROID_HOME=' + sdk] * 2)\n"
            "argv = (ctypes.c_char_p * 2)(target, None)\n"
            "envp = (ctypes.c_char_p * (len(environment_items) + 1))(\n"
            "    *environment_items, None\n"
            ")\n"
            "result = ctypes.CDLL(None).execve(target, argv, envp)\n"
            "raise OSError(ctypes.get_errno(), f'execve returned {result}')\n"
        )
        outer_oracle = fixture_scripts / "oracle.txt"
        outer_oracle.unlink(missing_ok=True)
        duplicate_sdk_result = subprocess.run(
            (
                "/usr/bin/python3",
                "-I",
                str(duplicate_sdk_exec),
                str(copied_entrypoint),
                str(fake_sdk_root),
            ),
            cwd=fixture_root,
            env=clean_test_environment(),
            check=False,
            capture_output=True,
            text=True,
        )
        if (
            duplicate_sdk_result.returncode == 0
            or outer_oracle.exists()
            or "duplicate Android SDK environment entries"
            not in duplicate_sdk_result.stderr
        ):
            fail("duplicate ANDROID_HOME entries escaped raw-environment rejection")
        checks += 1

        outer_oracle = fixture_scripts / "oracle.txt"
        outer_oracle.unlink(missing_ok=True)
        guard_check_result = run_entrypoint(
            copied_entrypoint,
            fixture_scripts,
            arguments=("--profile-guard-check",),
        )
        if (
            guard_check_result.returncode != 0
            or outer_oracle.exists()
            or guard_check_result.stdout.splitlines().count(guard_pass_line) != 1
        ):
            fail("verified profile-guard check mode was not exact and cgroup-free")
        checks += 1

        outer_oracle.unlink(missing_ok=True)
        captured_hosted_environment = {
            "CI": "true",
            "GRADLE_HOME": "/opt/hostedtoolcache/gradle/current",
            "JAVA_HOME": "/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17/x64",
            "JAVA_HOME_17_X64": (
                "/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17/x64"
            ),
            "MAVEN_ARGS": "-ntp",
        }
        hosted_check_result = run_entrypoint(
            copied_entrypoint,
            fixture_scripts,
            arguments=("--profile-guard-check",),
            additions=captured_hosted_environment,
        )
        if (
            hosted_check_result.returncode != 0
            or outer_oracle.exists()
            or hosted_check_result.stdout.splitlines().count(guard_pass_line) != 1
        ):
            fail(
                "captured hosted toolchain environment did not run one "
                "authenticated guard check"
            )
        checks += 1

        if include_workflow_self_test:
            outer_oracle.unlink(missing_ok=True)
            hosted_self_test_result = run_entrypoint(
                copied_entrypoint,
                fixture_scripts,
                arguments=("--profile-guard-self-test",),
                additions=captured_hosted_environment,
            )
            if (
                hosted_self_test_result.returncode != 0
                or outer_oracle.exists()
                or "PASS: forced full-JVM whole-entrypoint self-test"
                not in hosted_self_test_result.stdout
            ):
                fail(
                    "exact hosted CI=true did not run the authenticated guard self-test"
                )
            checks += 1

        hosted_override_environment = {
            **captured_hosted_environment,
            "ACTIONS_CACHE_URL": "https://hosted.invalid/cache",
            "ANDROID_HOME": "/definitely/not-an-sdk",
            "BASH_ENV": "/definitely/not-sourced",
            "GITHUB_ACTIONS": "true",
            "GITHUB_WORKFLOW": "Tests",
            "GRADLE_OPTS": "-Dorg.gradle.jvmargs=-Xmx12g",
            "JAVA_TOOL_OPTIONS": "-Xmx12g",
            "KOTLIN_DAEMON_JVM_OPTIONS": "-Xmx12g",
            "ORG_GRADLE_PROJECT_org.gradle.workers.max": "99",
            "POCKETSHELL_TEST_MEM": "99G",
            "PYTHONPATH": "/definitely/not-imported",
            "RUNNER_OS": "Linux",
        }
        outer_oracle.unlink(missing_ok=True)
        hosted_override_result = run_entrypoint(
            copied_entrypoint,
            fixture_scripts,
            arguments=("--profile-guard-check",),
            additions=hosted_override_environment,
        )
        expected_guard_environment = {
            "HOME": pwd.getpwuid(os.getuid()).pw_dir,
            "LANG": "C.UTF-8",
            "LC_ALL": "C.UTF-8",
            "LOGNAME": pwd.getpwuid(os.getuid()).pw_name,
            "PATH": "/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR": "/tmp",
            "USER": pwd.getpwuid(os.getuid()).pw_name,
        }
        actual_guard_environment = dict(
            item.decode().split("=", 1)
            for item in guard_environment_capture.read_bytes().split(b"\0")
            if item
        )
        if (
            hosted_override_result.returncode != 0
            or outer_oracle.exists()
            or hosted_override_result.stdout.splitlines().count(guard_pass_line) != 1
            or actual_guard_environment != expected_guard_environment
        ):
            fail(
                "hosted metadata/profile overrides affected or leaked into "
                "the authenticated guard"
            )
        checks += 1

        assert_rejected_before_cgroup(
            "full gate under hosted CI=true",
            copied_entrypoint,
            fixture_scripts,
            additions={"CI": "true"},
        )
        checks += 1

        hostile_ci_values = (
            "",
            "1",
            "TRUE",
            "True",
            "false",
            "true ",
            " true",
            "true\nCI=false",
        )
        for hostile_ci_value in hostile_ci_values:
            assert_rejected_before_cgroup(
                f"guard-only hostile CI={hostile_ci_value!r}",
                copied_entrypoint,
                fixture_scripts,
                arguments=("--profile-guard-check",),
                additions={"CI": hostile_ci_value},
            )
            checks += 1

        for hostile_ci_name in ("ci", "Ci", "cI", "CI "):
            assert_rejected_before_cgroup(
                f"guard-only hostile CI name {hostile_ci_name!r}",
                copied_entrypoint,
                fixture_scripts,
                arguments=("--profile-guard-check",),
                additions={hostile_ci_name: "true"},
            )
            checks += 1

        duplicate_env_exec = fixture_root / "duplicate-env-exec.py"
        duplicate_env_exec.write_text(
            "import ctypes\n"
            "import os\n"
            "import sys\n"
            "target = os.fsencode(sys.argv[1])\n"
            "argv_items = [target, b'--profile-guard-check']\n"
            "environment_items = [\n"
            "    os.fsencode(f'{name}={value}')\n"
            "    for name, value in os.environ.items()\n"
            "]\n"
            "environment_items.extend(\n"
            "    os.fsencode(f'CI={value}') for value in sys.argv[2:]\n"
            ")\n"
            "argv = (ctypes.c_char_p * (len(argv_items) + 1))(\n"
            "    *argv_items, None\n"
            ")\n"
            "envp = (ctypes.c_char_p * (len(environment_items) + 1))(\n"
            "    *environment_items, None\n"
            ")\n"
            "result = ctypes.CDLL(None).execve(target, argv, envp)\n"
            "raise OSError(ctypes.get_errno(), f'execve returned {result}')\n"
        )
        for duplicate_values in (("true", "true"), ("true", "false")):
            outer_oracle.unlink(missing_ok=True)
            duplicate_result = subprocess.run(
                (
                    "/usr/bin/python3",
                    "-I",
                    str(duplicate_env_exec),
                    str(copied_entrypoint),
                    *duplicate_values,
                ),
                cwd=fixture_root,
                env=clean_test_environment(),
                check=False,
                capture_output=True,
                text=True,
            )
            if (
                duplicate_result.returncode == 0
                or outer_oracle.exists()
                or guard_pass_line in duplicate_result.stdout
                or "CI must be unset" not in duplicate_result.stderr
            ):
                fail(f"duplicate CI entries escaped rejection: {duplicate_values!r}")
            checks += 1

        trusted_bash_env = str(Path(pwd.getpwuid(os.getuid()).pw_dir) / ".bashenv")
        result = run_entrypoint(
            copied_entrypoint,
            fixture_scripts,
            additions={"BASH_ENV": trusted_bash_env},
        )
        if result.returncode != 0:
            fail(f"trusted but stripped BASH_ENV execution failed: {result.stderr}")
        inherited_environment = (
            (fixture_scripts / "environment.bin").read_bytes().split(b"\0")
        )
        if any(item.startswith(b"BASH_ENV=") for item in inherited_environment):
            fail("trusted caller BASH_ENV leaked across the clean exec boundary")
        checks += 1

        canonical_guard_bytes = copied_guard.read_bytes()

        def assert_preflight_guard_rejected(
            label: str,
            outside_marker: Path | None = None,
        ) -> None:
            nonlocal checks
            oracle = fixture_scripts / "oracle.txt"
            oracle.unlink(missing_ok=True)
            if outside_marker is not None:
                outside_marker.unlink(missing_ok=True)
            result = run_entrypoint(copied_entrypoint, fixture_scripts)
            if (
                result.returncode == 0
                or oracle.exists()
                or (outside_marker is not None and outside_marker.exists())
            ):
                fail(
                    f"actual preflight path accepted or executed {label}; "
                    f"rc={result.returncode}"
                )
            checks += 1

        outside_guard = fixture_root / "outside-guard.py"
        outside_marker = fixture_root / "outside-guard.marker"
        outside_guard.write_text(
            "#!/usr/bin/python3 -I\n"
            "from pathlib import Path\n"
            f"Path({str(outside_marker)!r}).write_text('executed')\n"
            "raise SystemExit(37)\n"
        )
        outside_guard.chmod(0o755)
        copied_guard.unlink()
        copied_guard.symlink_to(outside_guard)
        assert_preflight_guard_rejected(
            "profile-guard symlink to outside rc37 marker",
            outside_marker,
        )
        copied_guard.unlink()
        copied_guard.write_bytes(canonical_guard_bytes)
        copied_guard.chmod(0o755)

        copied_guard.write_bytes(canonical_guard_bytes + b"\n# byte mutation\n")
        copied_guard.chmod(0o755)
        assert_preflight_guard_rejected("profile-guard byte mutation")
        copied_guard.write_bytes(canonical_guard_bytes)
        copied_guard.chmod(0o755)

        copied_guard.chmod(0o644)
        assert_preflight_guard_rejected("non-executable profile guard")
        copied_guard.chmod(0o755)

        false_success_marker = fixture_root / "false-success-guard.marker"
        copied_guard.write_text(
            "#!/usr/bin/python3 -I\n"
            "from pathlib import Path\n"
            f"Path({str(false_success_marker)!r}).write_text('executed')\n"
            "raise SystemExit(0)\n"
        )
        copied_guard.chmod(0o755)
        assert_preflight_guard_rejected(
            "profile-guard false-success replacement",
            false_success_marker,
        )
        copied_guard.write_bytes(canonical_guard_bytes)
        copied_guard.chmod(0o755)

        hostile_unit = "spaces ; $(touch never)\n--tests OnlyThisTest"
        result = run_entrypoint(
            copied_entrypoint,
            fixture_scripts,
            arguments=("--unit", hostile_unit),
        )
        if result.returncode != 0:
            fail(f"quoted hostile-unit execution failed: {result.stderr}")
        hostile_arguments = tuple(
            item.decode()
            for item in (fixture_scripts / "oracle.txt").read_bytes().split(b"\0")[:-1]
        )
        if (
            hostile_arguments[1] != hostile_unit
            or hostile_arguments[4:] != expected_arguments[4:]
        ):
            fail("hostile unit did not remain one quoted cgroup argument")
        checks += 1

        source = copied_entrypoint.read_text()
        exec_block = (
            "os.execve(\n"
            '    "/usr/bin/bash",\n'
            '    ("/usr/bin/bash", *command),\n'
            "    clean_environment,\n"
            ")\n"
        )
        mutations = (
            (
                "array/tuple append",
                "\nroot_dir = Path(__file__).resolve().parent.parent\n",
                '\nGRADLE_ARGS += ("--tests", "OnlyThisTest")\n'
                "root_dir = Path(__file__).resolve().parent.parent\n",
            ),
            (
                "profile element override",
                "\nroot_dir = Path(__file__).resolve().parent.parent\n",
                '\nGRADLE_ARGS = (":app:testDebugUnitTest", *GRADLE_ARGS[1:])\n'
                "root_dir = Path(__file__).resolve().parent.parent\n",
            ),
            (
                "memory append 8G2G",
                "\nroot_dir = Path(__file__).resolve().parent.parent\n",
                '\nMEMORY_LIMIT += "2G"\n'
                "root_dir = Path(__file__).resolve().parent.parent\n",
            ),
            (
                "alternate environment helper",
                "\n    unsafe_names = sorted(\n",
                "\n    os.environ = {}\n    unsafe_names = sorted(\n",
            ),
            (
                "exec alias",
                exec_block,
                "exec_alias = os.execve\n"
                'exec_alias("/usr/bin/bash", ("/usr/bin/bash", *command), '
                "clean_environment)\n",
            ),
            (
                "alternate exec function",
                exec_block,
                "def alternate_execve(*_arguments):\n"
                "    raise SystemExit(0)\n"
                'alternate_execve("/usr/bin/bash", command, clean_environment)\n',
            ),
            (
                "indirect exec",
                exec_block,
                'getattr(os, "execve")("/usr/bin/bash", '
                '("/usr/bin/bash", *command), clean_environment)\n',
            ),
            (
                "success without cgroup",
                exec_block,
                "raise SystemExit(0)\n",
            ),
            (
                "extra --tests",
                '    "-Pkotlin.daemon.jvmargs=-Xmx3072m",\n',
                '    "-Pkotlin.daemon.jvmargs=-Xmx3072m",\n'
                '    "--tests",\n'
                '    "OnlyThisTest",\n',
            ),
            (
                "extra task selector",
                '    "test",\n',
                '    "test",\n    ":app:testDebugUnitTest",\n',
            ),
            (
                "bare Gradle",
                'cgroup_runner = root_dir / "scripts" / "cgroup-run.sh"\n',
                'cgroup_runner = root_dir / "gradlew"\n',
            ),
            (
                "disabled preflight",
                "preflight_result = os.spawnve(\n",
                "preflight_result = 0 if True else os.spawnve(\n",
            ),
            (
                "late memory override",
                "\nroot_dir = Path(__file__).resolve().parent.parent\n",
                '\nMEMORY_LIMIT = "12G"\n'
                "root_dir = Path(__file__).resolve().parent.parent\n",
            ),
            (
                "late narrowed profile",
                "\nroot_dir = Path(__file__).resolve().parent.parent\n",
                '\nGRADLE_ARGS = ("test", "--rerun-tasks", "--tests", "OnlyThisTest")\n'
                "root_dir = Path(__file__).resolve().parent.parent\n",
            ),
            (
                "environment argument suffix",
                "    *GRADLE_ARGS,\n",
                '    *GRADLE_ARGS,\n    *os.environ.get("EXTRA_GRADLE_ARGS", "").split(),\n',
            ),
        )
        for index, (label, old, new) in enumerate(mutations):
            mutation = fixture_scripts / f"mutation-{index}.sh"
            write_mutation(source, mutation, label, old, new)
            oracle = fixture_scripts / "oracle.txt"
            oracle.unlink(missing_ok=True)
            guard_result = subprocess.run(
                (str(copied_guard), str(mutation)),
                cwd=fixture_root,
                env=clean_test_environment(),
                check=False,
                capture_output=True,
                text=True,
            )
            if guard_result.returncode == 0 or oracle.exists():
                fail(f"canonical-template guard accepted mutation {label!r}")
            checks += 1
            if label != "disabled preflight":
                assert_rejected_before_cgroup(label, mutation, fixture_scripts)
                checks += 1

        runtime_arguments = (
            ("--tests", "OnlyThisTest"),
            (":app:testDebugUnitTest",),
            ("-x", ":app:testReleaseUnitTest"),
            ("test",),
            ("--max-workers=2",),
        )
        for arguments in runtime_arguments:
            assert_rejected_before_cgroup(
                f"runtime arguments {arguments!r}",
                copied_entrypoint,
                fixture_scripts,
                arguments=arguments,
            )
            checks += 1

        hostile_environments = (
            ("BASH_ENV", "/definitely/not/sourced"),
            ("BASHOPTS", "extdebug"),
            ("CI", "1"),
            ("ENV", "/definitely/not/sourced"),
            ("GRADLE_FLAGS", "--max-workers=99"),
            ("GRADLE_OPTS", "-Dorg.gradle.jvmargs=-Xmx12g"),
            ("GRADLE_USER_HOME", "/tmp/unsafe-gradle-home"),
            ("JAVA_HOME", "/tmp/caller-selected-java"),
            ("JAVA_OPTS", "-Xmx12g"),
            ("JAVA_TOOL_OPTIONS", "-Xmx12g"),
            ("JDK_JAVA_OPTIONS", "-Xmx12g"),
            ("KOTLIN_DAEMON_JVM_OPTIONS", "-Xmx12g"),
            ("KOTLIN_DAEMON_JVMARGS", "-Xmx12g"),
            ("LD_LIBRARY_PATH", "/tmp/unsafe-library-path"),
            ("ORG_GRADLE_PROJECT_kotlin_daemon_jvmargs", "-Xmx12g"),
            ("ORG_GRADLE_PROJECT_org.gradle.workers.max", "99"),
            ("POCKETSHELL_SCOPE_ALLOW_BARE", "1"),
            ("POCKETSHELL_SCOPE_SLICE", "system.slice"),
            ("POCKETSHELL_TEST_HIGH", "1"),
            ("POCKETSHELL_TEST_MEM", "8G"),
            ("POCKETSHELL_TEST_SWAP", "99G"),
            ("PYTHONPATH", "/tmp/unsafe-python-path"),
            ("SHELLOPTS", "extdebug"),
            ("_JAVA_OPTIONS", "-Xmx12g"),
            ("BASH_FUNC_env%%", "() { :; }"),
            ("BASH_FUNC_exec%%", "() { :; }"),
        )
        for name, value in hostile_environments:
            assert_rejected_before_cgroup(
                f"unsafe environment {name}",
                copied_entrypoint,
                fixture_scripts,
                additions={name: value},
            )
            checks += 1

        canonical_runner_bytes = fake_cgroup.read_bytes()

        def assert_guard_and_entrypoint_reject_runner(label: str) -> None:
            nonlocal checks
            guard_result = subprocess.run(
                (str(copied_guard), str(copied_entrypoint)),
                cwd=fixture_root,
                env=clean_test_environment(),
                check=False,
                capture_output=True,
                text=True,
            )
            if guard_result.returncode == 0:
                fail(f"guard accepted {label}")
            checks += 1
            assert_rejected_before_cgroup(
                label,
                copied_entrypoint,
                fixture_scripts,
            )
            checks += 1

        fake_cgroup.unlink()
        fake_cgroup.symlink_to("/bin/true")
        assert_guard_and_entrypoint_reject_runner("runner symlink to false success")
        fake_cgroup.unlink()
        fake_cgroup.write_bytes(canonical_runner_bytes)
        fake_cgroup.chmod(0o755)

        fake_cgroup.write_bytes(canonical_runner_bytes + b"\n# byte mutation\n")
        fake_cgroup.chmod(0o755)
        assert_guard_and_entrypoint_reject_runner("runner byte mutation")
        fake_cgroup.write_bytes(canonical_runner_bytes)
        fake_cgroup.chmod(0o755)

        fake_cgroup.chmod(0o644)
        assert_guard_and_entrypoint_reject_runner("non-executable runner")
        fake_cgroup.chmod(0o755)

        fake_cgroup.write_text("#!/bin/dash\nexit 0\n")
        fake_cgroup.chmod(0o755)
        assert_guard_and_entrypoint_reject_runner("runner false-success replacement")
        fake_cgroup.write_bytes(canonical_runner_bytes)
        fake_cgroup.chmod(0o755)

        non_executable_entrypoint = fixture_scripts / "non-executable-entrypoint.sh"
        shutil.copy2(copied_entrypoint, non_executable_entrypoint)
        non_executable_entrypoint.chmod(0o644)
        mode_result = subprocess.run(
            (str(copied_guard), str(non_executable_entrypoint)),
            cwd=fixture_root,
            env=clean_test_environment(),
            check=False,
            capture_output=True,
            text=True,
        )
        if mode_result.returncode == 0:
            fail("guard accepted canonical entrypoint bytes without executable mode")
        checks += 1

        symlink_entrypoint = fixture_scripts / "symlink-entrypoint.sh"
        symlink_entrypoint.symlink_to(copied_entrypoint.name)
        symlink_result = subprocess.run(
            (str(copied_guard), str(symlink_entrypoint)),
            cwd=fixture_root,
            env=clean_test_environment(),
            check=False,
            capture_output=True,
            text=True,
        )
        if symlink_result.returncode == 0:
            fail("guard accepted a symlinked entrypoint")
        checks += 1

        copied_guard.chmod(0o644)
        try:
            with redirect_stderr(io.StringIO()):
                validate_regular_executable(
                    copied_guard,
                    "full-JVM profile guard fixture",
                )
        except SystemExit:
            checks += 1
        else:
            fail("metadata guard accepted a non-executable profile guard")
        copied_guard.chmod(0o755)

        copied_scope_runner = fixture_scripts / "lib" / "scope-run.sh"
        copied_scope_runner.chmod(0o644)
        scope_mode_result = subprocess.run(
            (str(copied_guard), str(copied_entrypoint)),
            cwd=fixture_root,
            env=clean_test_environment(),
            check=False,
            capture_output=True,
            text=True,
        )
        if scope_mode_result.returncode == 0:
            fail("guard accepted a non-executable scope runner")
        checks += 1
        copied_scope_runner.chmod(0o755)

        def create_java_variant(
            label: str,
            java_script: str | None,
            replacements: tuple[tuple[str, str], ...] = (),
            sdk_root: Path = fake_sdk_root,
        ) -> tuple[Path, Path, Path]:
            variant_root = fixture_root / f"java-{label}"
            variant_scripts = variant_root / "scripts"
            (variant_scripts / "lib").mkdir(parents=True)
            variant_runner = variant_scripts / "cgroup-run.sh"
            variant_runner.write_bytes(canonical_runner_bytes)
            variant_runner.chmod(0o755)
            shutil.copy2(copied_scope_runner, variant_scripts / "lib" / "scope-run.sh")
            variant_java_home = variant_root / "java-home"
            if java_script is not None:
                (variant_java_home / "bin").mkdir(parents=True)
                variant_java = variant_java_home / "bin" / "java"
                variant_java.write_text(java_script)
                variant_java.chmod(0o755)
            variant_entrypoint = variant_scripts / "full-jvm-gate.sh"
            variant_guard = variant_scripts / "check-full-jvm-gate-profile.sh"
            write_paired_fixture(
                entrypoint.read_text(),
                Path(__file__).read_text(),
                variant_entrypoint,
                variant_guard,
                variant_java_home,
                sdk_root,
                fake_runner_digest,
                replacements=replacements,
            )
            return variant_entrypoint, variant_guard, variant_scripts

        missing_sdk_entrypoint, _missing_sdk_guard, missing_sdk_scripts = (
            create_java_variant(
                "captured-missing-sdk",
                "#!/bin/dash\nprintf 'openjdk version \"21.0.99\"\\n' >&2\nexit 0\n",
                sdk_root=fixture_root / "definitely-missing-android-sdk",
            )
        )
        missing_sdk_result = run_entrypoint(
            missing_sdk_entrypoint,
            missing_sdk_scripts,
        )
        if (
            missing_sdk_result.returncode == 0
            or (missing_sdk_scripts / "oracle.txt").exists()
            or "no complete Android SDK found" not in missing_sdk_result.stderr
        ):
            fail("captured fresh-worktree missing-SDK failure was not reproduced")
        checks += 1

        java_failure_variants = (
            ("missing", None),
            ("non-java", "#!/bin/dash\nprintf 'not java\\n' >&2\nexit 0\n"),
            (
                "wrong-version",
                "#!/bin/dash\nprintf 'openjdk version \"17.0.99\"\\n' >&2\nexit 0\n",
            ),
        )
        for label, java_script in java_failure_variants:
            variant_entrypoint, _variant_guard, variant_scripts = create_java_variant(
                label, java_script
            )
            variant_oracle = variant_scripts / "oracle.txt"
            variant_oracle.unlink(missing_ok=True)
            java_result = run_entrypoint(
                variant_entrypoint,
                variant_scripts,
            )
            if (
                java_result.returncode == 0
                or variant_oracle.exists()
                or "no executable Java 21 runtime" not in java_result.stderr
            ):
                fail(f"{label} Java candidate did not fail at Java validation")
            checks += 1

        paired_semantic_mutations = (
            (
                "invalid-path",
                (
                    (
                        'MINIMAL_PATH = "/usr/sbin:/usr/bin:/sbin:/bin"',
                        'MINIMAL_PATH = "/definitely/missing-path"',
                    ),
                ),
            ),
            (
                "invalid-java-home",
                (
                    (
                        'clean_environment["JAVA_HOME"] = str(java_home)',
                        'clean_environment["JAVA_HOME"] = "/definitely/missing-jdk"',
                    ),
                ),
            ),
        )
        valid_java_script = (
            "#!/bin/dash\nprintf 'openjdk version \"21.0.99\"\\n' >&2\nexit 0\n"
        )

        inode_variant_entrypoint, _inode_variant_guard, inode_variant_scripts = (
            create_java_variant(
                "guard-inode-replacement",
                valid_java_script,
                (
                    (
                        "os.set_inheritable(guard_descriptor, True)\n",
                        "os.set_inheritable(guard_descriptor, True)\n"
                        "replacement_guard = profile_guard.with_name("
                        "'replacement-profile-guard.py')\n"
                        "replacement_guard.write_bytes("
                        "Path(f'/proc/self/fd/{guard_descriptor}').read_bytes())\n"
                        "replacement_guard.chmod(0o755)\n"
                        "os.replace(replacement_guard, profile_guard)\n",
                    ),
                ),
            )
        )
        inode_oracle = inode_variant_scripts / "oracle.txt"
        inode_oracle.unlink(missing_ok=True)
        inode_result = run_entrypoint(
            inode_variant_entrypoint,
            inode_variant_scripts,
        )
        if (
            inode_result.returncode == 0
            or inode_oracle.exists()
            or "profile guard changed before preflight" not in inode_result.stderr
        ):
            fail("profile-guard inode replacement escaped the descriptor recheck")
        checks += 1

        for label, replacements in paired_semantic_mutations:
            variant_entrypoint, variant_guard, variant_scripts = create_java_variant(
                label,
                valid_java_script,
                replacements,
            )
            semantic_result = subprocess.run(
                (str(variant_guard), "--self-test", str(variant_entrypoint)),
                cwd=variant_scripts.parent,
                env=clean_test_environment(),
                check=False,
                capture_output=True,
                text=True,
            )
            if (
                semantic_result.returncode == 0
                or "fake-cgroup environment did not match every independent value"
                not in semantic_result.stderr
            ):
                fail(f"paired digest update hid semantic mutation {label!r}")
            checks += 1

    print(f"PASS: forced full-JVM whole-entrypoint self-test ({checks} checks)")


if any(name.strip().lower() == "ci" for name in os.environ):
    fail("verified profile-guard child environment must omit CI")

arguments = sys.argv[1:]
self_test_requested = False
verified_preflight = False
if arguments and arguments[0] == "--self-test":
    self_test_requested = True
    arguments.pop(0)
if arguments and arguments[0] == "--verified-preflight":
    if not self_test_requested:
        fail("--verified-preflight is valid only with --self-test")
    verified_preflight = True
    arguments.pop(0)
if len(arguments) > 1:
    fail("usage: scripts/check-full-jvm-gate-profile.sh [--self-test] [entrypoint]")
target = Path(os.path.abspath(arguments[0])) if arguments else DEFAULT_ENTRYPOINT
if self_test_requested:
    self_test(
        target,
        include_workflow_self_test=not verified_preflight,
    )
else:
    validate_entrypoint(target)
    print(
        "PASS: forced full-JVM gate matches the immutable complete-graph "
        "8 GiB canonical template"
    )
