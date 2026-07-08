package com.pocketshell.app.usage

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.time.Instant

/**
 * Issue #1318 — on-device (connected) render acceptance for the quse-v0.0.9
 * strict-schema usage pipeline. This is the G4/D33 proof that the BLOCKED
 * reviewer round asked for: not "the parser returns the right list" (that is
 * the JVM `PocketshellUsageJsonParserTest`), but "the real Compose usage panel
 * VISIBLY renders provider cards for the authoritative quse-0.0.9 data, and
 * fails LOUD — never a silent wrong render — on the un-flattened blob the
 * maintainer's device actually received on v0.4.24."
 *
 * ## The reported symptom (maintainer dogfood, v0.4.24)
 *
 * `0 providers · 0 hosts`, `hetzner: Refresh usage failed`, and a raw JSON dump
 * where the provider cards should be. Root cause: quse changed its `--json`
 * schema to a provider-keyed object, but the whole pipeline expected per-line
 * NDJSON — so the app received the un-flattened blob and could not turn it into
 * cards.
 *
 * ## Why this drives the REAL path (F2, not a proxy)
 *
 * Both @Test cases feed a canned [SshSession] into the PRODUCTION
 * [UsageRemoteSource.fetchUsage] (which runs the PRODUCTION
 * `PocketshellUsageJsonParser`), map its result into the PRODUCTION
 * [UsageScreenState] exactly as [UsageViewModel.loadUsageState] does
 * (Records → [UsageHostSnapshot]; Failed → [UsageFailedHost]), and compose the
 * PRODUCTION [UsageScreen] in the real [PocketShellTheme]. Nothing about the
 * #1318 subject (fetch → strict parse → panel render) is stubbed; only the SSH
 * transport is canned, which is not what #1318 changed. There is no
 * `*StandIn` / `*Proxy` for the panel under test.
 *
 * ## Red → green
 *
 * The same-input base-vs-fix red→green for the schema change lives in the JVM
 * `PocketshellUsageJsonParserTest` (the OLD parser derived zai's long window as
 * `long_term` via `canonicalWindowName`; the NEW parser reads it straight from
 * `window: "weekly"`). This test is the on-device manifestation of that: the
 * green case HARD-asserts the panel renders the **"Weekly limit"** card (zai)
 * and **"Monthly limit"** card (copilot) — labels the OLD parser could not
 * produce for these providers — plus `4 providers · 1 hosts` and NO
 * `Refresh usage failed`. The companion case reproduces the exact v0.4.24
 * broken panel through the real render path so a regression to silent-wrong /
 * non-loud handling of an un-flattened blob is caught.
 *
 * Pure Compose-rule UI test (like [UsageGlancePillE2eTest]): no Docker fixture,
 * no SSH/tmux/toxiproxy, deterministic on the CI swiftshader AVD, and it does
 * NOT self-skip on CI. Wired into `scripts/ci-journey-suite.sh` so it gates at
 * per-push/batched time (G9).
 */
@RunWith(AndroidJUnit4::class)
class Usage1318StrictSchemaRenderE2eTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // A fixed "now" so the per-window reset foot is deterministic; the label
    // assertions below do not depend on it.
    private val now: Instant = Instant.parse("2026-07-07T20:00:00Z")

    /**
     * GREEN acceptance: the authoritative quse-0.0.9 output, flattened by
     * `pocketshell usage --json` into per-provider NDJSON, renders all four
     * provider cards with the unified window labels straight from quse's
     * `window` field.
     */
    @Test
    fun flattenedQuseV009Ndjson_rendersAllFourProviderCardsWithUnifiedWindows() {
        val state = renderStateFor(stdout = FLATTENED_QUSE_V009_NDJSON, exitCode = 0)

        // The real fetch → strict parse produced 4 provider records on 1 host.
        assertTrue(
            "expected the real UsageRemoteSource to parse 4 provider records, " +
                "got ${state.providerCount} on ${state.hostCount} host(s)",
            state.providerCount == 4 && state.hostCount == 1,
        )
        assertTrue(
            "expected no failed host on the authoritative flattened NDJSON, " +
                "got ${state.failedHosts}",
            state.failedHosts.isEmpty(),
        )

        setUsageScreen(state)

        // All four provider cards render (display names). This is the symptom
        // gone: cards, not `0 providers` / a raw dump.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Claude Code", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Claude Code", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Codex", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("GitHub Copilot", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Zai", useUnmergedTree = true).assertExists()

        // Load-bearing new-schema labels: these come STRAIGHT from quse's
        // `window` field. The OLD parser rendered zai's long window as
        // "Long term" (canonicalWindowName), never "Weekly limit"; and only
        // knew "monthly" for copilot's long window. Their presence is the
        // on-device manifestation of the Part-B parser fix.
        compose.onNodeWithText("Weekly limit", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Monthly limit", useUnmergedTree = true).assertExists()
        // The concrete short/long spans quse emits for claude/codex/zai.
        compose.onAllNodesWithText("5h window", useUnmergedTree = true).fetchSemanticsNodes()
            .isNotEmpty().let { assertTrue("expected a 5h window label", it) }
        compose.onAllNodesWithText("7d window", useUnmergedTree = true).fetchSemanticsNodes()
            .isNotEmpty().let { assertTrue("expected a 7d window label", it) }

        // The screen-level meta row reads the populated provider/host counts —
        // NOT the reported `0 providers · 0 hosts`.
        compose.onNodeWithText("4 providers · 1 hosts", useUnmergedTree = true).assertExists()

        // The reported failure band must be ABSENT (no "Refresh usage failed").
        assertTrue(
            "the populated panel must not show the failure band",
            compose.onAllNodesWithText("Refresh usage failed", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    /**
     * Reproduce the exact v0.4.24 broken panel through the real render path:
     * the un-flattened quse-0.0.9 provider-keyed object (what a broken pipeline
     * delivered to the device) must produce a LOUD, visible failure — the
     * reported `0 providers · 0 hosts` + `Refresh usage failed` — never a silent
     * wrong render and never a provider card. This guards the D22 hard-cut
     * fail-loud intent: the ONLY input that yields cards is the flattened NDJSON.
     */
    @Test
    fun rawUnflattenedQuseV009Object_failsLoudReproducingReportedSymptom() {
        val state = renderStateFor(stdout = RAW_QUSE_V009_PROVIDER_KEYED, exitCode = 0)

        // The strict parser throws on the un-flattened blob, so the real
        // UsageRemoteSource classifies the host as Failed — the reported state.
        assertTrue(
            "expected the un-flattened blob to fail loud (0 providers, host failed), " +
                "got providerCount=${state.providerCount} failed=${state.failedHosts.size}",
            state.providerCount == 0 && state.failedHosts.size == 1,
        )

        setUsageScreen(state)

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("hetzner: Refresh usage failed", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The exact reported symptom text.
        compose.onNodeWithText("hetzner: Refresh usage failed", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("0 providers · 0 hosts", useUnmergedTree = true).assertExists()

        // And crucially NOT a silently-rendered provider card: the un-flattened
        // blob must never masquerade as usable data.
        assertTrue(
            "an un-flattened blob must NOT render a provider card",
            compose.onAllNodesWithText("Weekly limit", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("Claude Code", useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty(),
        )
    }

    /**
     * Drive the PRODUCTION [UsageRemoteSource.fetchUsage] (real strict parser)
     * with a canned session, then map its result into [UsageScreenState] exactly
     * as [UsageViewModel.loadUsageState] does. Only the SSH transport is stubbed.
     */
    private fun renderStateFor(stdout: String, exitCode: Int): UsageScreenState = runBlocking {
        val source = UsageRemoteSource()
        val session = CannedUsageSshSession(stdout = stdout, exitCode = exitCode)
        when (val result = source.fetchUsage(session)) {
            is UsageFetchResult.Success -> UsageScreenState(
                hosts = listOf(
                    UsageHostSnapshot(
                        hostId = 1L,
                        hostName = "hetzner",
                        records = result.records,
                        lastSyncedAt = now,
                    ),
                ),
            )
            is UsageFetchResult.Failed -> UsageScreenState(
                failedHosts = listOf(
                    UsageFailedHost(hostId = 1L, hostName = "hetzner", reason = result.reason),
                ),
            )
            UsageFetchResult.ToolMissing -> UsageScreenState(
                missingToolHosts = listOf(
                    UsageMissingToolHost(hostId = 1L, hostName = "hetzner"),
                ),
            )
        }
    }

    private fun setUsageScreen(state: UsageScreenState) {
        compose.setContent {
            PocketShellTheme {
                Column(modifier = Modifier.fillMaxSize().background(PocketShellColors.Background)) {
                    UsageScreen(
                        state = state,
                        onBack = {},
                        onRefresh = {},
                        now = now,
                    )
                }
            }
        }
    }

    /**
     * Minimal canned [SshSession]: every `exec` returns the configured
     * stdout/exit so the real [UsageRemoteSource] runs its production parse path
     * against deterministic bytes. Non-usage methods are unused by this flow.
     */
    private class CannedUsageSshSession(
        private val stdout: String,
        private val exitCode: Int,
    ) : SshSession {
        override val isConnected: Boolean = true
        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = stdout, stderr = "", exitCode = exitCode)

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("not used")
        override fun startShell(): SshShell = error("not used")
        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")
        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")
        override fun close() = Unit
    }

    private companion object {
        /**
         * The authoritative per-provider NDJSON `pocketshell usage --json`
         * emits — the exact output of `normalize_usage_stdout` applied to the
         * `tools/pocketshell/tests/data/quse-0.0.9-usage.json` fixture (verified
         * this run by running the flatten on that fixture). Four providers:
         * claude (5h/7d), codex (5h/7d), copilot (—/monthly), zai (5h/weekly).
         */
        val FLATTENED_QUSE_V009_NDJSON: String = listOf(
            """{"details":{"limit_reached":false,"windows":{"five_hour":{"reset_at":"2026-07-07T23:19:59Z","used_percent":9.0},"seven_day":{"reset_at":"2026-07-09T14:59:59Z","used_percent":70.0}}},"error":null,"long_term":{"percent_remaining":30.0,"reset_at":"2026-07-09T14:59:59Z","window":"7d"},"provider":"claude","short_term":{"percent_remaining":91.0,"reset_at":"2026-07-07T23:19:59Z","window":"5h"},"status":"ok"}""",
            """{"details":{"limit_reached":true,"windows":{"primary_window":{"reset_at":"2026-07-07T23:57:08Z","used_percent":0.0},"secondary_window":{"reset_at":"2026-07-11T06:23:55Z","used_percent":98.0}}},"error":null,"long_term":{"percent_remaining":2.0,"reset_at":"2026-07-11T06:23:55Z","window":"7d"},"provider":"codex","short_term":{"percent_remaining":100.0,"reset_at":"2026-07-07T23:57:08Z","window":"5h"},"status":"ok"}""",
            """{"details":{"limit_reached":false,"premium_percent_remaining":97.1},"error":null,"long_term":{"percent_remaining":97.1,"reset_at":"2026-08-01T00:00:00Z","window":"monthly"},"provider":"copilot","short_term":{"percent_remaining":100.0,"reset_at":null,"window":null},"status":"ok"}""",
            """{"details":{"limit_reached":false,"max_used_percent":44.0},"error":null,"long_term":{"percent_remaining":56.0,"reset_at":"2026-07-11T14:04:58Z","window":"weekly"},"provider":"zai","short_term":{"percent_remaining":58.0,"reset_at":null,"window":"5h"},"status":"ok"}""",
        ).joinToString("\n")

        /**
         * The RAW, un-flattened quse-0.0.9 `--json` document — a provider-keyed
         * object with NO top-level `provider` key. This is what the maintainer's
         * device received on v0.4.24 (the flatten was broken), and the strict
         * parser must reject it loudly. Pretty-printed to match quse's real
         * multi-line dump; the trimmed 2-provider excerpt is sufficient to
         * reproduce the fail-loud symptom (the parser throws on the leading `{`).
         */
        val RAW_QUSE_V009_PROVIDER_KEYED: String = """
            {
              "claude": {
                "error": null,
                "long_term": {"percent_remaining": 30.0, "reset_at": "2026-07-09T14:59:59Z", "window": "7d"},
                "short_term": {"percent_remaining": 91.0, "reset_at": "2026-07-07T23:19:59Z", "window": "5h"},
                "status": "ok"
              },
              "codex": {
                "error": null,
                "long_term": {"percent_remaining": 2.0, "reset_at": "2026-07-11T06:23:55Z", "window": "7d"},
                "short_term": {"percent_remaining": 100.0, "reset_at": "2026-07-07T23:57:08Z", "window": "5h"},
                "status": "ok"
              }
            }
        """.trimIndent()
    }
}
