package com.pocketshell.app.bootstrap

import com.pocketshell.app.projects.HostPocketshellUpgrade
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2381 — the app's own git-derived `versionName` must not make a
 * correctly set-up host look like it needs a CLI update.
 *
 * ## The regression this pins
 *
 * `HostBootstrapper` treats the installed APK's `versionName` as "the
 * `pocketshell` CLI version this build expects on the host". Until issue
 * #2356 that was a hand-maintained `versionName = "0.4.44"` literal, so the
 * expectation was always a clean dotted release version. #2356 (commit
 * `824f2aa2`) replaced it with `scripts/derive-version.sh`, which emits four
 * shapes — `0.4.45`, `0.4.45-4-g9b1d784e`, `0.0.0-dev+525c87a`, `0.0.0-dev` —
 * three of which are not dotted-numeric.
 *
 * The 2026-08-28 nightly built the APK from a tagless (shallow) checkout, so
 * `aapt` recorded `versionName='0.0.0-dev+525c87a'`. The bootstrap fixtures
 * report back the very same string the test wrote into them, yet
 * `HostBootstrapScenarioSuiteTest` failed 8 of 10 methods, because:
 *
 *  1. `VERSION_PATTERN` excluded `+` from its qualifier class, so parsing the
 *     host's `pocketshell 0.0.0-dev+525c87a` truncated it to `0.0.0-dev`; and
 *  2. `compareSemver` returned `null` for both sides, dropping into a raw
 *     string-equality fallback which then said `0.0.0-dev != 0.0.0-dev+525c87a`
 *     → `VersionMismatch` → "Host setup needed" sheet that never clears.
 *
 * Each test below fails on the pre-fix code and passes after it. They also
 * cover the class, not just the reported instance (G2): every derived
 * `versionName` shape, crossed with a host CLI that is equal / older / newer,
 * plus the `pocketshell==<version>` install pin that the same raw string fed.
 */
class Issue2381DerivedAppVersionCliCompatibilityTest {

    private val bootstrapper = HostBootstrapper()

    // ---------------------------------------------------------------------
    // 1. The exact reported instance: the nightly's own versionName.
    // ---------------------------------------------------------------------

    @Test
    fun hostReportingTheAppsOwnDerivedVersionIsReady_notAVersionMismatch() = runTest {
        // Reproduces nightly run 33148401037 verbatim: APK versionName
        // '0.0.0-dev+525c87a', bootstrap fixture seeded with that same string.
        val report = checkServerSetup(
            hostReportedVersion = NIGHTLY_APK_VERSION_NAME,
            appVersionName = NIGHTLY_APK_VERSION_NAME,
        )

        assertEquals(
            "a host reporting the app's own version must be Installed, not VersionMismatch " +
                "(pre-fix this was VersionMismatch, so the bootstrap sheet never cleared)",
            ToolStatus.Installed(
                path = POCKETSHELL_PATH,
                version = NIGHTLY_APK_VERSION_NAME,
                expectedVersion = NIGHTLY_APK_VERSION_NAME,
            ),
            report.tools[BootstrapTool.Pocketshell],
        )
        assertTrue("host must be ready so the app navigates past setup", report.isReady)
        assertTrue(report.versionMismatchedTools.isEmpty())
    }

    @Test
    fun buildMetadataIsNotTruncatedOutOfAParsedVersion() {
        // Root cause (1): the `+` build-metadata suffix fell outside the
        // qualifier character class, so the app parsed a DIFFERENT string out
        // of the host's answer than the one the host actually printed.
        assertEquals(
            "0.0.0-dev+525c87a",
            bootstrapper.parsePocketshellVersion("pocketshell $NIGHTLY_APK_VERSION_NAME\n"),
        )
        assertEquals(
            "0.4.45-4-g9b1d784e",
            bootstrapper.parsePocketshellVersion("pocketshell bootstrap fixture 0.4.45-4-g9b1d784e\n"),
        )
        assertEquals("0.4.45", bootstrapper.parsePocketshellVersion("pocketshell, version 0.4.45\n"))
        assertNull(bootstrapper.parsePocketshellVersion("pocketshell dev build\n"))
    }

    // ---------------------------------------------------------------------
    // 2. The class (G2): every derived versionName shape.
    // ---------------------------------------------------------------------

    @Test
    fun releaseCoreIsResolvedForEveryShapeDeriveVersionCanEmit() {
        // scripts/derive-version.sh's four documented outputs.
        assertEquals("0.4.45", releaseVersionCore("0.4.45"))
        assertEquals("0.4.45", releaseVersionCore("0.4.45-4-g9b1d784e"))
        assertEquals("0.0.0", releaseVersionCore("0.0.0-dev+525c87a"))
        assertEquals("0.0.0", releaseVersionCore("0.0.0-dev"))
        // Plus the tag form and plain semver build metadata.
        assertEquals("0.4.45", releaseVersionCore("v0.4.45"))
        assertEquals("1.2.3", releaseVersionCore("1.2.3+build.7"))
        // Genuinely unparseable stays null so callers keep their conservative
        // fallback rather than guessing.
        assertNull(releaseVersionCore("dev"))
        assertNull(releaseVersionCore(""))
        assertNull(releaseVersionCore("v-next"))
    }

    @Test
    fun everyDerivedVersionShapeAcceptsAHostOnTheSameReleaseLine() = runTest {
        for (appVersionName in DERIVED_VERSION_NAME_SHAPES) {
            val core = requireNotNull(releaseVersionCore(appVersionName))
            // The host reports the plain published release (what a real
            // `uv tool install pocketshell` leaves behind), the app carries the
            // derived dev string. Same release line ⇒ ready.
            val report = checkServerSetup(hostReportedVersion = core, appVersionName = appVersionName)
            assertTrue(
                "app versionName '$appVersionName' must accept a host CLI on release core '$core'",
                report.isReady,
            )
            assertTrue(
                "app versionName '$appVersionName' must not flag a same-core host as mismatched",
                report.versionMismatchedTools.isEmpty(),
            )
        }
    }

    @Test
    fun aStrictlyOlderHostCliStaysAVersionMismatchOnEveryDerivedShape() = runTest {
        // The host-upgrade flow (#514/#779) must survive the fix: an actually
        // outdated CLI still raises the "pocketshell CLI update needed" row.
        for (appVersionName in listOf("0.4.45", "0.4.45-4-g9b1d784e")) {
            val report = checkServerSetup(hostReportedVersion = "0.3.6", appVersionName = appVersionName)

            assertEquals(
                "app versionName '$appVersionName' must still flag an older host CLI",
                listOf(BootstrapTool.Pocketshell),
                report.versionMismatchedTools,
            )
            assertEquals(
                ToolStatus.VersionMismatch(
                    path = POCKETSHELL_PATH,
                    currentVersion = "0.3.6",
                    expectedVersion = appVersionName,
                ),
                report.tools[BootstrapTool.Pocketshell],
            )
        }
    }

    @Test
    fun aStrictlyNewerHostCliIsAppUpdateRequiredOnEveryDerivedShape() = runTest {
        // Issue #514's remote-newer path. Pre-fix a dev versionName collapsed
        // this into VersionMismatch, which pops the takeover setup sheet and
        // runs the host installer in a loop instead of the soft banner —
        // exactly what broke `appUpdateRequired` in the nightly.
        for (appVersionName in DERIVED_VERSION_NAME_SHAPES) {
            val report = checkServerSetup(hostReportedVersion = "9999.0.0", appVersionName = appVersionName)

            val status = report.tools[BootstrapTool.Pocketshell]
            assertTrue(
                "app versionName '$appVersionName': remote-newer must be AppUpdateRequired, got $status",
                status is ToolStatus.AppUpdateRequired,
            )
            assertTrue(
                "app versionName '$appVersionName': remote-newer host stays fully usable (no setup sheet)",
                report.isReady,
            )
        }
    }

    @Test
    fun releaseCoresOrderNumericallyAndIgnoreBuildMetadata() {
        assertEquals(0, compareSemver("0.0.0-dev+525c87a", "0.0.0-dev"))
        assertEquals(0, compareSemver("0.4.45", "0.4.45-4-g9b1d784e"))
        assertEquals(0, compareSemver("v0.4.45", "0.4.45+ci.7"))
        assertTrue(requireNotNull(compareSemver("0.3.10", "0.3.9-rc1")) > 0)
        assertTrue(requireNotNull(compareSemver("0.0.0-dev", "0.3.6")) < 0)
        // Unparseable on either side still yields null (conservative path).
        assertNull(compareSemver("dev", "0.4.45"))
        assertNull(compareSemver("0.4.45", "nightly"))
    }

    // ---------------------------------------------------------------------
    // 3. The same raw string also fed the install pin.
    // ---------------------------------------------------------------------

    @Test
    fun expectedHostCliVersionFeedsAResolvableInstallPin() {
        // Pre-fix, `HostPocketshellUpgrade` pinned
        // `pocketshell==0.4.45-4-g9b1d784e` — a version published on no index,
        // so the "Update" action the sheet offered could never succeed.
        val command = HostPocketshellUpgrade.upgradeCommand(
            expectedHostCliVersion("0.4.45-4-g9b1d784e"),
        )
        assertTrue(
            "upgrade must pin the published release core, got: $command",
            command.contains("pocketshell==0.4.45"),
        )
        assertTrue(
            "upgrade must not pin the git-describe suffix, got: $command",
            !command.contains("g9b1d784e"),
        )
    }

    @Test
    fun expectedHostCliVersionIsEmptyWhenNoReleaseCoreCanBeResolved() {
        // Empty means "skip the version check", the pre-#2356 behaviour for a
        // build whose versionName cannot be read at all. It must never be a
        // half-parsed string that then mismatches everything.
        assertEquals("", expectedHostCliVersion(null))
        assertEquals("", expectedHostCliVersion(""))
        assertEquals("", expectedHostCliVersion("dev"))
        assertEquals("0.0.0", expectedHostCliVersion("0.0.0-dev+525c87a"))
    }

    // ---------------------------------------------------------------------

    private suspend fun checkServerSetup(
        hostReportedVersion: String,
        appVersionName: String,
    ): HostBootstrapReport {
        val session = FakeSshSession(
            mapOf(
                pathAware("command -v 'pocketshell'") to ExecResult("$POCKETSHELL_PATH\n", "", 0),
                pathAware("'$POCKETSHELL_PATH' --version") to
                    ExecResult("pocketshell $hostReportedVersion\n", "", 0),
                pathAware("command -v 'uv'") to ExecResult("/home/u/.local/bin/uv\n", "", 0),
                pathAware("command -v 'systemctl'") to ExecResult("/usr/bin/systemctl\n", "", 0),
                systemdAware("systemctl --user is-active pocketshell-jobs.service") to
                    ExecResult("active\n", "", 0),
                systemdAware("systemctl --user is-enabled pocketshell-jobs.service") to
                    ExecResult("enabled\n", "", 0),
            ),
        )
        // Deliberately the RAW derived versionName, not the normalized core:
        // `HostBootstrapper` is the layer that must survive whatever shape
        // `scripts/derive-version.sh` produced, and this is exactly what the
        // pre-#2381 ViewModels handed it.
        val report = bootstrapper.checkServerSetup(
            session,
            expectedPocketshellVersion = appVersionName,
        )
        assertNotNull("the version probe must actually have run", report.tools[BootstrapTool.Pocketshell])
        assertTrue(
            "the version probe must actually have run",
            session.recorded.contains(pathAware("'$POCKETSHELL_PATH' --version")),
        )
        return report
    }

    private fun pathAware(command: String): String =
        shell("PATH=${shellQuote(BOOTSTRAP_PATH)}; export PATH; $command")

    private fun systemdAware(command: String): String =
        shell(
            "PATH=${shellQuote(BOOTSTRAP_PATH)}; export PATH; " +
                "XDG_RUNTIME_DIR=\"\${XDG_RUNTIME_DIR:-/run/user/\$(id -u)}\"; export XDG_RUNTIME_DIR; " +
                "DBUS_SESSION_BUS_ADDRESS=\"\${DBUS_SESSION_BUS_ADDRESS:-unix:path=\$XDG_RUNTIME_DIR/bus}\"; " +
                "export DBUS_SESSION_BUS_ADDRESS; $command",
        )

    private fun shell(command: String): String = "/bin/sh -lc ${shellQuote(command)}"

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private class FakeSshSession(private val canned: Map<String, ExecResult>) : SshSession {
        val recorded: MutableList<String> = mutableListOf()

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            recorded += command
            return canned[command]
                ?: if (command == HostBootstrapper().detectBootstrapPathCommand()) {
                    ExecResult(
                        "__POCKETSHELL_PATH_BEGIN__\n$BOOTSTRAP_PATH\n__POCKETSHELL_PATH_END__\n",
                        "",
                        0,
                    )
                } else {
                    ExecResult("", "command not stubbed: $command", 127)
                }
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("unused")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("unused")

        override fun startShell(): SshShell = error("unused")

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String = error("unused")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("unused")

        override fun close() = Unit
    }

    private companion object {
        /** `aapt` output of the 2026-08-28 nightly APK (run 33148401037). */
        const val NIGHTLY_APK_VERSION_NAME: String = "0.0.0-dev+525c87a"

        const val POCKETSHELL_PATH: String = "/home/u/.local/bin/pocketshell"

        const val BOOTSTRAP_PATH: String =
            "/home/u/.local/bin:/home/u/bin:/home/u/.cargo/bin:/usr/local/bin:/usr/bin:/bin"

        /** Every shape `scripts/derive-version.sh version-name` can print. */
        val DERIVED_VERSION_NAME_SHAPES: List<String> = listOf(
            "0.4.45",
            "0.4.45-4-g9b1d784e",
            "0.0.0-dev+525c87a",
            "0.0.0-dev",
        )
    }
}
