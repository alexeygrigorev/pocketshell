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
 * ## Red → green (published quse 0.0.15 / issue #2293)
 *
 * The NDJSON below is the REAL `pocketshell usage --json` output for the
 * captured published quse 0.0.15 wheel — byte-identical to
 * `tools/pocketshell/tests/data/quse-0.0.15-usage.ndjson`, which
 * `test_kotlin_androidtest_literal_matches_the_python_producer_fixture`
 * (tools/pocketshell/tests/test_usage.py) keeps in lock-step with the real
 * producer. 0.0.15 is the canonical producer, so no translation happens: the
 * host injects the provider key and forwards the record.
 *
 * The green case asserts all SIX published provider cards — including
 * **OpenCode Go**, the card the maintainer actually wanted (#2293: the 0.0.14
 * pin answered `Unknown provider 'go'`, so no `go` record existed and no card
 * could render) — plus `6 providers · 1 hosts` and NO `Refresh usage failed`.
 * The companion case reproduces the exact v0.4.24 broken panel through the
 * real render path so a regression to silent-wrong / non-loud handling of an
 * un-flattened blob is caught.
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

    // A fixed "now" for the credit/reset case. Chosen so the codex 7d reset
    // (2026-09-03T16:26:48Z) lands in the SUB-24h countdown bucket, which is
    // zone-independent by construction.
    private val now: Instant = Instant.parse("2026-09-03T09:00:00Z")

    // The instant the 0.0.15 fixture was captured, used for the six-card
    // acceptance so every card renders the panel the maintainer would have
    // seen on the dev box at capture time.
    private val captureNow: Instant = Instant.parse("2026-08-28T13:00:00Z")

    /**
     * GREEN acceptance: the authoritative published quse-0.0.15 output,
     * forwarded by `pocketshell usage --json` as canonical per-provider
     * NDJSON, renders all SIX provider cards — OpenCode Go included — with
     * producer-owned window labels.
     */
    @Test
    fun flattenedQuse0015Ndjson_rendersAllSixPublishedProviderCardsIncludingOpenCodeGo() {
        val state = renderStateFor(stdout = FLATTENED_QUSE_0015_NDJSON, exitCode = 0)

        // The real fetch → strict parse produced 6 published provider records.
        assertTrue(
            "expected the real UsageRemoteSource to parse 6 provider records, " +
                "got ${state.providerCount} on ${state.hostCount} host(s)",
            state.providerCount == 6 && state.hostCount == 1,
        )
        assertTrue(
            "expected no failed host on the authoritative flattened NDJSON, " +
                "got ${state.failedHosts}",
            state.failedHosts.isEmpty(),
        )

        setUsageScreen(state, screenNow = captureNow)

        // All six published provider cards render (display names). This is the symptom
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

        // THE #2293 acceptance: the OpenCode Go card. On the 0.0.14 pin quse
        // reported no `go` provider at all, so this node could not exist.
        compose.onNodeWithText(OPENCODE_GO_CARD_TITLE, useUnmergedTree = true)
            .performScrollTo()
            .assertExists()

        // Load-bearing producer-wire labels with EXACT cardinality so a parser
        // or UI that drops / invents a window row fails. On this capture:
        //   5h      → claude, go, zai            (3)
        //   7d      → claude, codex, go, grok, zai (5)
        //   monthly → copilot, go                (2)
        // Every one of those `go` rows is new with the 0.0.15 pin.
        compose.onAllNodesWithText("5h window", useUnmergedTree = true).assertCountEquals(3)
        compose.onAllNodesWithText("7d window", useUnmergedTree = true).assertCountEquals(5)
        compose.onAllNodesWithText("Monthly limit", useUnmergedTree = true).assertCountEquals(2)

        // The go card's own numbers reach the screen: 36% remaining → 64% used,
        // and the 5h reset foot is 36m26s out from the capture instant.
        compose.onNodeWithText(GO_5H_USED_PERCENT, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(GO_5H_RESET_FOOT, useUnmergedTree = true).assertExists()

        // The screen-level meta row reads the populated provider/host counts —
        // NOT the reported `0 providers · 0 hosts`.
        compose.onNodeWithText("6 providers · 1 hosts", useUnmergedTree = true).assertExists()

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
     * Issue #1789 reproduce-first RED, published 0.0.15 fixture: the codex payload's
     * available reset credits keep their source titles (duplicates included)
     * and distinct expiry instants through the production fetch/parser/screen
     * path. Keep the quota reset and credit expiry assertions together: a
     * credit is already available and merely EXPIRES later. Its expiry must
     * never be relabelled as the automatic quota reset or as permission to
     * resume work.
     */
    @Test
    fun flattenedQuse0015Ndjson_rendersAvailableCreditsAsExpiryNotQuotaReset() {
        val state = renderStateFor(stdout = FLATTENED_QUSE_0015_NDJSON, exitCode = 0)
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
        // (codex 7d resets 2026-09-03T16:26:48Z, i.e. in 7h 27m from `now`;
        // sub-24h bucket, zone-independent by construction).
        compose.onAllNodesWithText("resets in 7h 27m", useUnmergedTree = true)
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

    private fun setUsageScreen(state: UsageScreenState, screenNow: Instant = now) {
        compose.setContent {
            PocketShellTheme {
                Column(modifier = Modifier.fillMaxSize().background(PocketShellColors.Background)) {
                    UsageScreen(
                        state = state,
                        onBack = {},
                        onRefresh = {},
                        now = screenNow,
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
            "authoritative semantics must prove the populated Usage screen, " +
                "including the #2293 OpenCode Go card",
            semantics.contains("6 providers · 1 hosts") &&
                semantics.contains("GitHub Copilot") &&
                semantics.contains(OPENCODE_GO_CARD_TITLE) &&
                semantics.contains(GO_5H_USED_PERCENT) &&
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
         * emits for the published quse-0.0.15 capture — byte-identical to
         * `tools/pocketshell/tests/data/quse-0.0.15-usage.ndjson`, kept in
         * lock-step by
         * `test_kotlin_androidtest_literal_matches_the_python_producer_fixture`.
         * SIX providers: claude, codex, copilot, go, grok, zai. The top-level
         * details blobs remain provider-owned and are ignored by the app
         * except for Codex reset-credit inventory.
         */
        val FLATTENED_QUSE_0015_NDJSON: String = listOf(
            """{"details": {"limit_reached": false, "subscription": null, "windows": {"five_hour": {"reset_at": "2026-08-28T16:20:00Z", "used_percent": 6.0}, "seven_day": {"reset_at": "2026-09-03T15:00:00Z", "used_percent": 10.0}}}, "error": null, "provider": "claude", "status": "ok", "windows": {"5h": {"percent_remaining": 94.0, "reset_at": "2026-08-28T16:20:00Z", "rolling": false}, "7d": {"percent_remaining": 90.0, "reset_at": "2026-09-03T15:00:00Z"}, "monthly": {"percent_remaining": null, "reset_at": null}}}""",
            """{"details": {"limit_reached": false, "reset_credits": [{"expires_at": "2026-09-21T00:13:17Z", "status": "available", "title": "Full reset"}], "reset_credits_available": 1, "reset_credits_error": null, "windows": {"primary_window": {"limit_window_seconds": 604800, "present": true, "reset_at": "2026-09-03T16:26:48Z", "used_percent": 13.0}, "secondary_window": {"limit_window_seconds": null, "present": false, "reset_at": null, "used_percent": 0.0}}}, "error": null, "provider": "codex", "status": "ok", "windows": {"5h": {"percent_remaining": null, "reset_at": null, "rolling": false}, "7d": {"percent_remaining": 87.0, "reset_at": "2026-09-03T16:26:48Z"}, "monthly": {"percent_remaining": null, "reset_at": null}}}""",
            """{"details": {"limit_reached": true, "premium_entitlement": 1500, "premium_percent_remaining": 0.0, "premium_remaining": -1}, "error": null, "provider": "copilot", "status": "ok", "windows": {"5h": {"percent_remaining": null, "reset_at": null, "rolling": false}, "7d": {"percent_remaining": null, "reset_at": null}, "monthly": {"percent_remaining": 0.0, "reset_at": "2026-09-01T00:00:00Z"}}}""",
            """{"details": {"limit_reached": false, "max_used_percent": 64.0}, "error": null, "provider": "go", "status": "ok", "windows": {"5h": {"percent_remaining": 36.0, "reset_at": "2026-08-28T13:36:26Z", "rolling": true}, "7d": {"percent_remaining": 74.0, "reset_at": "2026-08-31T00:00:00Z"}, "monthly": {"percent_remaining": 86.0, "reset_at": "2026-09-22T06:20:28Z"}}}""",
            """{"details": {"has_grok_code_access": true, "is_unified_billing_user": true, "limit_reached": true, "on_demand_cap": 0.0, "on_demand_used": 0.0, "prepaid_balance": 0.0, "product_usage": [{"product": "GrokBuild", "usage_percent": 100.0}], "resets": [{"expires_at": "2026-09-12T18:49:00Z", "token_id": "restok_vpYDqo"}], "resets_available": 1, "resets_error": null, "windows": {"monthly": {"limit": null, "present": false, "reset_at": null, "used": null, "used_percent": null}, "weekly": {"limit": 0.0, "present": true, "reset_at": "2026-09-01T00:08:17Z", "used": 0.0, "used_percent": 100.0}}}, "error": null, "provider": "grok", "status": "ok", "windows": {"5h": {"percent_remaining": null, "reset_at": null, "rolling": false}, "7d": {"percent_remaining": 0.0, "reset_at": "2026-09-01T00:08:17Z"}, "monthly": {"percent_remaining": null, "reset_at": null}}}""",
            """{"details": {"limit_reached": false, "max_used_percent": 45.0, "windows": {"five_hour": {"limit": null, "present": true, "remaining": null, "reset_at": null, "used_percent": 1.0, "window_hours": 5}, "weekly": {"limit": null, "present": true, "remaining": null, "reset_at": "2026-09-03T14:04:58Z", "used_percent": 45.0, "window_hours": null}}}, "error": null, "provider": "zai", "status": "ok", "windows": {"5h": {"percent_remaining": 99.0, "reset_at": null, "rolling": true}, "7d": {"percent_remaining": 55.0, "reset_at": "2026-09-03T14:04:58Z"}, "monthly": {"percent_remaining": null, "reset_at": null}}}""",
        ).joinToString("\n")

        /** #2293: the OpenCode Go card title (UsageProviderRecord.displayName). */
        const val OPENCODE_GO_CARD_TITLE: String = "OpenCode Go"

        /** go 5h: 36% remaining on the capture → 64% used. */
        const val GO_5H_USED_PERCENT: String = "64% used"

        /** go 5h resets 2026-08-28T13:36:26Z, 36m26s after [captureNow]. */
        const val GO_5H_RESET_FOOT: String = "resets in 37m"

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
                "windows": {
                  "5h": {"percent_remaining": 94.0, "reset_at": "2026-08-28T16:20:00Z", "rolling": false},
                  "7d": {"percent_remaining": 90.0, "reset_at": "2026-09-03T15:00:00Z"},
                  "monthly": {"percent_remaining": null, "reset_at": null}
                }
              }
            }
        """.trimIndent()
    }
}
