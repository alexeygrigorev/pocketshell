package com.pocketshell.app.usage

import com.pocketshell.app.pocketshell.PocketshellCommand
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.usage.UsageStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageRemoteSourceTest {

    private val source = UsageRemoteSource()

    private val detectCommand = PocketshellCommand.detect()
    private val defaultFetchCommand = PocketshellCommand.wrap(UsageRemoteSource.DEFAULT_USAGE_ARGS)

    @Test
    fun detectPocketshell_installedWhenResolverPrintsPath() = runTest {
        // The PATH-robust resolver prints the absolute binary path it found.
        val session = FakeSshSession(
            mapOf(detectCommand to ExecResult("/home/me/.local/bin/pocketshell\n", "", 0)),
        )

        assertEquals(UsageToolStatus.Installed, source.detectPocketshell(session))
        assertEquals(listOf(detectCommand), session.recorded)
    }

    @Test
    fun detectPocketshell_missingWhenResolverExits127() = runTest {
        // Genuinely absent: neither `command -v` nor any absolute candidate hit,
        // so the wrapper exits 127.
        val session = FakeSshSession(
            mapOf(detectCommand to ExecResult("", "", 127)),
        )

        assertEquals(UsageToolStatus.Missing, source.detectPocketshell(session))
    }

    @Test
    fun detect_command_isPathRobust_probesLocalBinAndExits127WhenAbsent() {
        // The detection command must (a) prepend ~/.local/bin to PATH, (b) probe
        // the absolute ~/.local/bin/pocketshell candidate, and (c) exit 127 when
        // nothing resolves — so "not installed" only fires on a real absence.
        assertTrue(detectCommand.contains("\$HOME/.local/bin"))
        assertTrue(detectCommand.contains("\$HOME/.local/bin/pocketshell"))
        assertTrue(detectCommand.contains("command -v pocketshell"))
        assertTrue(detectCommand.contains("exit 127"))
    }

    @Test
    fun fetchUsage_offPathPocketshellStillResolvesAndParses() = runTest {
        // Simulate the #484 bug: a plain `command -v pocketshell` would fail
        // (binary off the non-interactive PATH), but the PATH-robust wrapper
        // resolves ~/.local/bin/pocketshell and runs it successfully.
        val session = FakeSshSession(
            canned = mapOf(
                defaultFetchCommand to ExecResult(
                    """{"provider":"codex","status":"blocked","short_term":null,"long_term":null,"block_reason":"weekly limit reached","error":null,"details":{}}""",
                    "",
                    0,
                ),
            ),
            // A bare `command -v pocketshell` (no PATH prefix) fails for this host.
            barePocketshellFails = true,
        )

        val result = source.fetchUsage(session)

        assertTrue(result is UsageFetchResult.Success)
        val success = result as UsageFetchResult.Success
        assertEquals(UsageStatus.Blocked, success.records.single().status)
        assertEquals(listOf(defaultFetchCommand), session.recorded)
    }

    @Test
    fun fetchUsage_usesCommandOverrideVerbatim() = runTest {
        // A per-host override is the maintainer's own script; it is run as-is,
        // NOT re-wrapped.
        val session = FakeSshSession(
            mapOf(
                "custom-usage --json" to ExecResult(
                    """{"provider":"claude","status":"ok","short_term":null,"long_term":null,"block_reason":null,"error":null,"details":{}}""",
                    "",
                    0,
                ),
            ),
        )

        val result = source.fetchUsage(session, commandOverride = "custom-usage --json")

        assertTrue(result is UsageFetchResult.Success)
        assertEquals(listOf("custom-usage --json"), session.recorded)
    }

    @Test
    fun fetchUsage_genuinelyAbsentIsToolMissing() = runTest {
        // The wrapper resolved nothing and exited 127 -> tool genuinely missing.
        val session = FakeSshSession(
            mapOf(defaultFetchCommand to ExecResult("", "", 127)),
        )

        assertEquals(UsageFetchResult.ToolMissing, source.fetchUsage(session))
    }

    @Test
    fun fetchUsage_nonzeroClaudeErrorRecordStillRendersUsageUnavailableState() = runTest {
        // #591: provider auth failures can be returned as a normalized JSON
        // record while the underlying provider probe exits non-zero. The app
        // must keep that row instead of classifying the host as skipped.
        val session = FakeSshSession(
            mapOf(
                defaultFetchCommand to ExecResult(
                    stdout = """{"provider":"claude","status":"error","short_term":null,"long_term":null,"block_reason":null,"error":"HTTP Error 401: Unauthorized","details":{}}""",
                    stderr = "",
                    exitCode = 1,
                ),
            ),
        )

        val result = source.fetchUsage(session)

        assertTrue(result is UsageFetchResult.Success)
        val record = (result as UsageFetchResult.Success).records.single()
        assertEquals("claude", record.provider)
        assertEquals(UsageStatus.Error, record.status)
        assertEquals(CLAUDE_USAGE_AUTH_SETUP_MESSAGE, record.lastError)
        assertTrue(record.lastError?.contains("claude " + "/login") == false)
        assertTrue(record.lastError?.contains("authentication " + "failed", ignoreCase = true) == false)
        assertTrue(record.lastError?.contains("HTTP Error 401", ignoreCase = true) == false)
    }

    // -- issue #1223: exit-0 per-record resilience (#847 version-skew class) --

    @Test
    fun fetchUsage_exit0PartialDrift_stillRendersHealthyProvider() = runTest {
        // An old/mismatched host CLI emits provider A fine but provider B
        // drifted (short_term is not an object). Before #1223 the whole usage
        // panel showed Failed/blank; the healthy provider must now render.
        val session = FakeSshSession(
            mapOf(
                defaultFetchCommand to ExecResult(
                    stdout = """{"provider":"codex","status":"ok","short_term":{"percent_remaining":77.0},"long_term":null,"block_reason":null,"error":null,"details":{}}""" +
                        "\n" +
                        """{"status":"ok","short_term":"drifted","long_term":null,"block_reason":null,"error":null,"details":{}}""",
                    stderr = "",
                    exitCode = 0,
                ),
            ),
        )

        val result = source.fetchUsage(session)

        assertTrue(result is UsageFetchResult.Success)
        val record = (result as UsageFetchResult.Success).records.single()
        assertEquals("codex", record.provider)
        assertEquals(UsageStatus.Ok, record.status)
    }

    @Test
    fun fetchUsage_exit0NonJsonPreamble_stillRendersAllProviders() = runTest {
        // A wrapper prepends a non-JSON MOTD/deprecation line before the valid
        // NDJSON. All valid providers must still render.
        val session = FakeSshSession(
            mapOf(
                defaultFetchCommand to ExecResult(
                    stdout = "WARNING: pocketshell 0.3.1 is deprecated\n" +
                        """{"provider":"codex","status":"ok","short_term":{"percent_remaining":50.0},"long_term":null,"block_reason":null,"error":null,"details":{}}""" +
                        "\n" +
                        """{"provider":"claude","status":"ok","short_term":{"percent_remaining":41.0},"long_term":null,"block_reason":null,"error":null,"details":{}}""",
                    stderr = "",
                    exitCode = 0,
                ),
            ),
        )

        val result = source.fetchUsage(session)

        assertTrue(result is UsageFetchResult.Success)
        val records = (result as UsageFetchResult.Success).records
        assertEquals(listOf("codex", "claude"), records.map { it.provider })
    }

    @Test
    fun fetchUsage_exit0AllRecordsUnparseable_reportsFailure() = runTest {
        // Zero parseable records must still surface a visible failure — never a
        // silent empty-success.
        val session = FakeSshSession(
            mapOf(
                defaultFetchCommand to ExecResult(
                    stdout = "this is not json at all\nalso not json {broken",
                    stderr = "",
                    exitCode = 0,
                ),
            ),
        )

        val result = source.fetchUsage(session)

        assertTrue("zero-parse must not be a silent Success", result is UsageFetchResult.Failed)
    }

    // -- issue #1220: pocketshell present but `quse` backend missing ---------

    @Test
    fun fetchUsage_exit0QuseMissingText_surfacesPocketshellOwnError_notParserInternals() = runTest {
        // Issue #1220 (reproduce-first): pocketshell IS installed and runs
        // (exit 0), but `pocketshell usage --json` prints pocketshell's OWN
        // dependency error — `quse` missing — as PLAIN TEXT on stdout instead
        // of usage JSON. The panel must surface pocketshell's real message +
        // install hint. It must NOT be classified as ToolMissing ("pocketshell
        // not installed"), and it must NOT leak the JSON parser internals.
        val quseMissing =
            "pocketshell: `quse` is not installed on this host. " +
                "Install it via `uv tool install quse` or `pipx install quse` and re-run."
        val session = FakeSshSession(
            mapOf(defaultFetchCommand to ExecResult(quseMissing, "", 0)),
        )

        val result = source.fetchUsage(session)

        assertTrue("quse-missing must NOT read as pocketshell missing", result !is UsageFetchResult.ToolMissing)
        assertTrue("zero-parse pocketshell error must be a visible Failed, got $result", result is UsageFetchResult.Failed)
        val reason = (result as UsageFetchResult.Failed).reason
        assertTrue("must surface pocketshell's own quse dependency error, got: $reason", reason.contains("quse"))
        assertTrue("must keep pocketshell's install hint, got: $reason", reason.contains("Install", ignoreCase = true))
        assertTrue("must not leak the JSON parser internals, got: $reason", !reason.contains("invalid usage JSON", ignoreCase = true))
    }

    @Test
    fun fetchUsage_nonzeroNonJsonStillReportsFailure() = runTest {
        val session = FakeSshSession(
            mapOf(
                defaultFetchCommand to ExecResult(
                    stdout = "",
                    stderr = "error: unknown provider",
                    exitCode = 2,
                ),
            ),
        )

        assertEquals(UsageFetchResult.Failed("error: unknown provider"), source.fetchUsage(session))
    }

    @Test
    fun detectPocketshell_propagatesCancellation() = runTest {
        val session = ThrowingSshSession(CancellationException("cancelled"))

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { source.detectPocketshell(session) }
        }
    }

    @Test
    fun fetchUsage_propagatesCancellation() = runTest {
        val session = ThrowingSshSession(CancellationException("cancelled"))

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { source.fetchUsage(session) }
        }
    }

    // -- issue #689: cached-reading path ------------------------------------

    private val cachedCommand = PocketshellCommand.wrap(UsageRemoteSource.CACHED_USAGE_ARGS)

    @Test
    fun fetchCachedUsage_parsesDocumentAndCapturedAt() = runTest {
        val session = FakeSshSession(
            mapOf(
                cachedCommand to ExecResult(
                    """{"captured_at":"2026-06-11T09:00:00Z","records":[""" +
                        """{"provider":"codex","status":"ok","short_term":{"percent_remaining":77.0},"long_term":null,"block_reason":null,"error":null,"details":{}}]}""",
                    "",
                    0,
                ),
            ),
        )

        val result = source.fetchCachedUsage(session)
        assertTrue(result is CachedUsageResult.Hit)
        val hit = result as CachedUsageResult.Hit
        assertEquals(java.time.Instant.parse("2026-06-11T09:00:00Z"), hit.capturedAt)
        assertEquals(1, hit.records.size)
        assertEquals("codex", hit.records.single().provider)
        assertEquals(listOf(cachedCommand), session.recorded)
    }

    @Test
    fun fetchCachedUsage_emptyWhenNoCaptureYet() = runTest {
        // `pocketshell usage --cached` exits 3 with a stderr note when no
        // capture has run — the app must collapse this to Empty so it falls
        // back to a pure live fetch.
        val session = FakeSshSession(
            mapOf(cachedCommand to ExecResult("", "no captured usage yet\n", 3)),
        )

        assertEquals(CachedUsageResult.Empty, source.fetchCachedUsage(session))
    }

    @Test
    fun fetchCachedUsage_emptyForPerHostOverride() = runTest {
        // A per-host usageCommandOverride is an arbitrary script that does
        // not speak `--cached`; the cache path is disabled for it.
        val session = FakeSshSession(emptyMap())
        assertEquals(
            CachedUsageResult.Empty,
            source.fetchCachedUsage(session, commandOverride = "my-quota-script"),
        )
        assertTrue("override must not invoke the cached command", session.recorded.isEmpty())
    }

    @Test
    fun fetchCachedUsage_emptyOnGarbageStdout() = runTest {
        val session = FakeSshSession(
            mapOf(cachedCommand to ExecResult("not json at all", "", 0)),
        )
        assertEquals(CachedUsageResult.Empty, source.fetchCachedUsage(session))
    }

    private class FakeSshSession(
        private val canned: Map<String, ExecResult>,
        private val barePocketshellFails: Boolean = false,
    ) : SshSession {
        val recorded = mutableListOf<String>()

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            recorded += command
            // A bare `command -v pocketshell` (the old, non-PATH-robust probe)
            // returns "not found" for this host to model the #484 PATH bug.
            if (barePocketshellFails && command == "command -v pocketshell") {
                return ExecResult("", "", 1)
            }
            return canned[command] ?: ExecResult("", "missing stub", 127)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("tail not used")

        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("port forward not used")

        override fun startShell(): SshShell = error("shell not used")

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() = Unit
    }

    private class ThrowingSshSession(
        private val throwable: Throwable,
    ) : SshSession {
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            throw throwable
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("tail not used")

        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("port forward not used")

        override fun startShell(): SshShell = error("shell not used")

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() = Unit
    }
}
