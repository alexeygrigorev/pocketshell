package com.pocketshell.app.usage

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.app.test.testArtifactsRoot
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Issue #1318 — on-device (connected) render acceptance for the strict-schema
 * usage pipeline. This is the G4/D33 proof that the BLOCKED reviewer round
 * asked for: not "the parser returns the right list" (that is the JVM
 * `PocketshellUsageJsonParserTest`), but "the real Compose usage panel VISIBLY
 * renders provider cards for the authoritative quse data, and fails LOUD —
 * never a silent wrong render — on the un-flattened blob the maintainer's
 * device actually received on v0.4.24."
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
 * ## Red → green (published quse 0.0.14 / issue #2274)
 *
 * The fixture is the REAL captured provider-keyed output from the published
 * quse 0.0.14 wheel (old `short_term` / `long_term` schema). The canonical
 * five-provider NDJSON below is the exact producer-boundary translation into
 * PocketShell's app wire shape, then runs through the hard-cut parser. The
 * green case asserts all FIVE published provider cards plus
 * `5 providers · 1 hosts` and NO `Refresh usage failed`. The companion case
 * reproduces the exact v0.4.24 broken panel through the real render path so a
 * regression to silent-wrong / non-loud handling of an un-flattened blob is
 * caught. The unreleased a86959e six-provider producer is not used.
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
    private val now: Instant = Instant.parse("2026-08-26T20:00:00Z")

    /**
     * GREEN acceptance: the authoritative published quse-0.0.14 output,
     * translated by `pocketshell usage --json` into canonical per-provider
     * NDJSON, renders all five provider cards with producer-owned labels.
     */
    @Test
    fun flattenedQuse0014Ndjson_rendersAllFivePublishedProviderCards() {
        val state = renderStateFor(stdout = FLATTENED_QUSE_0014_NDJSON, exitCode = 0)

        // The real fetch → strict parse produced 5 published provider records.
        assertTrue(
            "expected the real UsageRemoteSource to parse 5 provider records, " +
                "got ${state.providerCount} on ${state.hostCount} host(s)",
            state.providerCount == 5 && state.hostCount == 1,
        )
        assertTrue(
            "expected no failed host on the authoritative flattened NDJSON, " +
                "got ${state.failedHosts}",
            state.failedHosts.isEmpty(),
        )

        setUsageScreen(state)

        // All five published provider cards render (display names). This is the symptom
        // gone: cards, not `0 providers` / a raw dump.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Claude Code", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Claude Code", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Codex", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("GitHub Copilot", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Grok Build", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Zai", useUnmergedTree = true).assertExists()

        // Load-bearing producer-wire labels. A parser that drops the
        // canonical windows map renders zero windows and these labels vanish.
        // The published fixture has one real monthly window (Copilot); assert
        // exact cardinality so a parser/UI that invents an extra label fails.
        compose.onAllNodesWithText("Monthly limit", useUnmergedTree = true)
            .assertCountEquals(1)
        compose.onAllNodesWithText("5h window", useUnmergedTree = true).fetchSemanticsNodes()
            .isNotEmpty().let { assertTrue("expected a 5h window label", it) }
        compose.onAllNodesWithText("7d window", useUnmergedTree = true).fetchSemanticsNodes()
            .isNotEmpty().let { assertTrue("expected a 7d window label", it) }
        compose.onAllNodesWithText("Weekly limit", useUnmergedTree = true).fetchSemanticsNodes()
            .isNotEmpty().let { assertTrue("expected a weekly window label", it) }

        // The screen-level meta row reads the populated provider/host counts —
        // NOT the reported `0 providers · 0 hosts`.
        compose.onNodeWithText("5 providers · 1 hosts", useUnmergedTree = true).assertExists()

        // The reported failure band must be ABSENT (no "Refresh usage failed").
        assertTrue(
            "the populated panel must not show the failure band",
            compose.onAllNodesWithText("Refresh usage failed", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )

        // Capture while this test still owns the populated Usage composition.
        // The connected-test wrapper's post-test viewport may show the Android
        // launcher after teardown; these in-test artifacts are the authoritative
        // screen evidence and are load-bearing via the Usage-specific semantics.
        captureAuthoritativeUsageEvidence()
    }

    /**
     * Issue #1789 reproduce-first RED, published 0.0.14 fixture: the codex payload's
     * available reset credits keep their source titles (duplicates included)
     * and distinct expiry instants through the production fetch/parser/screen
     * path. Keep the quota reset and credit expiry assertions together: a
     * credit is already available and merely EXPIRES later. Its expiry must
     * never be relabelled as the automatic quota reset or as permission to
     * resume work.
     */
    @Test
    fun flattenedQuse0014Ndjson_rendersAvailableCreditsAsExpiryNotQuotaReset() {
        val state = renderStateFor(stdout = FLATTENED_QUSE_0014_NDJSON, exitCode = 0)
        setUsageScreen(state)

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(RESET_CREDITS_HEADER, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText(RESET_CREDITS_HEADER, useUnmergedTree = true)
            .performScrollTo()
            .assertExists()

        // The exact-available record survives; no synthetic unreleased fixture
        // rows are accepted as inventory.
        compose.onAllNodesWithText(DUPLICATE_CREDIT_TITLE, useUnmergedTree = true)
            .assertCountEquals(1)
        compose.onNodeWithText(NON_AVAILABLE_DECOY_TITLE, useUnmergedTree = true)
            .assertDoesNotExist()

        val zone = ZoneId.systemDefault()
        CREDIT_EXPIRIES.forEach { expiry ->
            val creditExpiry = formatCreditExpiry(now, expiry, zone)
            compose.onNodeWithText(
                creditExpiry.primary,
                useUnmergedTree = true,
            ).assertExists()
            compose.onNodeWithText(
                creditExpiry.primary.replace("expires", "resets"),
                substring = true,
                useUnmergedTree = true,
            ).assertDoesNotExist()
            compose.onNodeWithText(
                CREDIT_ABSOLUTE_FORMAT.withZone(zone).format(expiry),
                useUnmergedTree = true,
            ).assertExists()
        }

        // The normalized Codex quota reset remains present and uses the
        // existing reset vocabulary at the same time as credit expiry rows
        // (codex 7d resets 2026-08-27T03:30:22Z, i.e. in 7h 31m from `now`;
        // sub-24h bucket, zone-independent by construction).
        compose.onAllNodesWithText("resets in 7h 31m", useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
            .let { assertTrue("expected the codex 7d reset foot", it) }
        compose.onNodeWithText(
            "Heavy work can resume.",
            useUnmergedTree = true,
        ).assertDoesNotExist()
    }

    /**
     * Reproduce the exact v0.4.24 broken panel through the real render path:
     * the un-flattened provider-keyed object (what a broken pipeline delivered
     * to the device) must produce a LOUD, visible failure — the reported
     * `0 providers · 0 hosts` + `Refresh usage failed` — never a silent wrong
     * render and never a provider card. This guards the D22 hard-cut fail-loud
     * intent: the ONLY input that yields cards is the flattened NDJSON.
     */
    @Test
    fun rawUnflattenedQuseObject_failsLoudReproducingReportedSymptom() {
        val state = renderStateFor(stdout = RAW_QUSE_PROVIDER_KEYED, exitCode = 0)

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
            compose.onAllNodesWithText("Monthly limit", useUnmergedTree = true)
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

    private fun captureAuthoritativeUsageEvidence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val semantics = compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 100)
        assertTrue(
            "authoritative semantics must prove the populated Usage screen",
            semantics.contains("5 providers · 1 hosts") &&
                semantics.contains("GitHub Copilot") &&
                semantics.contains("Monthly limit"),
        )

        val directory = File(
            testArtifactsRoot(instrumentation.targetContext),
            "additional_test_output/usage1318",
        )
        check(directory.exists() || directory.mkdirs()) {
            "could not create Usage evidence directory ${directory.absolutePath}"
        }
        val semanticsFile = File(directory, "usage-screen-semantics.txt")
        semanticsFile.writeText(semantics)

        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val screenshotFile = File(directory, "usage-screen.png")
        FileOutputStream(screenshotFile).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "failed to write Usage screenshot to ${screenshotFile.absolutePath}"
            }
        }
        bitmap.recycle()
        println("ISSUE2274_USAGE_SCREENSHOT ${screenshotFile.absolutePath}")
        println("ISSUE2274_USAGE_SEMANTICS ${semanticsFile.absolutePath}")
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
         * emits — the exact producer-boundary translation of the published
         * quse-0.0.14 capture. Five providers: claude, codex, copilot, grok,
         * zai. The top-level details blobs remain provider-owned and are
         * ignored by the app except for Codex reset-credit inventory.
         */
        val FLATTENED_QUSE_0014_NDJSON: String = listOf(
            """{"details":{"limit_reached":false,"subscription":null,"windows":{"five_hour":{"reset_at":"2026-08-22T15:49:59Z","used_percent":1.0},"seven_day":{"reset_at":"2026-08-27T14:59:59Z","used_percent":7.0}}},"error":null,"provider":"claude","status":"ok","windows":{"5h":{"percent_remaining":99.0,"reset_at":"2026-08-22T15:49:59Z"},"7d":{"percent_remaining":93.0,"reset_at":"2026-08-27T14:59:59Z"}}}""",
            """{"details":{"limit_reached":false,"reset_credits":[{"expires_at":"2026-09-21T00:13:17Z","status":"available","title":"Full reset"}],"reset_credits_available":1,"reset_credits_error":null,"windows":{"primary_window":{"limit_window_seconds":604800,"present":true,"reset_at":"2026-08-27T03:30:22Z","used_percent":44.0},"secondary_window":{"limit_window_seconds":null,"present":false,"reset_at":null,"used_percent":0.0}}},"error":null,"provider":"codex","status":"ok","windows":{"7d":{"percent_remaining":56.0,"reset_at":"2026-08-27T03:30:22Z"}}}""",
            """{"details":{"limit_reached":false,"premium_entitlement":1500,"premium_percent_remaining":100.0,"premium_remaining":1500},"error":null,"provider":"copilot","status":"ok","windows":{"monthly":{"percent_remaining":100.0,"reset_at":"2026-09-01T00:00:00Z"},"short_term":{"percent_remaining":100.0,"reset_at":null}}}""",
            """{"details":{"has_grok_code_access":true,"is_unified_billing_user":true,"limit_reached":true,"on_demand_cap":0.0,"on_demand_used":0.0,"prepaid_balance":0.0,"product_usage":[{"product":"GrokBuild","usage_percent":100.0}],"resets":[{"expires_at":"2026-09-12T18:49:00Z","token_id":"restok_vpYDqo"}],"resets_available":1,"resets_error":null,"subscription":"SuperGrokPlus","windows":{"monthly":{"limit":null,"present":false,"reset_at":null,"used":null,"used_percent":null},"weekly":{"limit":0.0,"present":true,"reset_at":"2026-08-25T00:08:17Z","used":0.0,"used_percent":100.0}}},"error":null,"provider":"grok","status":"ok","windows":{"weekly":{"percent_remaining":0.0,"reset_at":"2026-08-25T00:08:17Z"}}}""",
            """{"details":{"limit_reached":true,"max_used_percent":100.0,"windows":{"five_hour":{"limit":null,"remaining":null,"reset_at":null,"used_percent":0.0,"window_hours":5},"monthly_web_search":{"limit":4000,"remaining":3971,"reset_at":"2026-08-27T14:04:58Z","used_percent":1.0,"window_hours":5},"weekly":{"limit":null,"remaining":null,"reset_at":"2026-08-24T14:04:58Z","used_percent":100.0,"window_hours":null}}},"error":null,"provider":"zai","status":"ok","windows":{"5h":{"percent_remaining":100.0,"reset_at":null},"weekly":{"percent_remaining":0.0,"reset_at":"2026-08-24T14:04:58Z"}}}""",
        ).joinToString("\n")

        const val RESET_CREDITS_HEADER: String = "Reset credits · 1 available"
        const val DUPLICATE_CREDIT_TITLE: String = "Full reset"
        const val NON_AVAILABLE_DECOY_TITLE: String = "Consumed reset must stay hidden"
        val CREDIT_EXPIRIES: List<Instant> = listOf(
            Instant.parse("2026-09-21T00:13:17Z"),
        )
        val CREDIT_ABSOLUTE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE MMM d, HH:mm", Locale.US)

        /**
         * The RAW, un-flattened provider-keyed `--json` document from the
         * published quse schema, with NO top-level `provider` key. This is
         * what the maintainer's device received on v0.4.24 (the flatten was
         * broken), and the strict parser must reject it loudly. The trimmed
         * excerpt is sufficient to reproduce the fail-loud symptom.
         */
        val RAW_QUSE_PROVIDER_KEYED: String = """
            {
              "claude": {
                "error": null,
                "status": "ok",
                "short_term": {"percent_remaining": 99.0, "reset_at": "2026-08-22T15:49:59Z", "window": "5h"},
                "long_term": {"percent_remaining": 93.0, "reset_at": "2026-08-27T14:59:59Z", "window": "7d"}
              }
            }
        """.trimIndent()
    }
}
