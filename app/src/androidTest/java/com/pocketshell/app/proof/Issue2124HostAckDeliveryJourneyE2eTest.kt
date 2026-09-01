package com.pocketshell.app.proof

import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.bootstrap.HOST_BOOTSTRAP_SKIP_TAG
import com.pocketshell.app.composer.COMPOSER_DRAFT_TAG
import com.pocketshell.app.composer.COMPOSER_OUTBOUND_QUEUE_BANNER_TAG
import com.pocketshell.app.composer.COMPOSER_OUTBOUND_QUEUE_TOGGLE_TAG
import com.pocketshell.app.composer.COMPOSER_SEND_ENTER_TAG
import com.pocketshell.app.composer.OutboundDeliveryOutcome
import com.pocketshell.app.composer.OutboundItem
import com.pocketshell.app.composer.OutboundRoute
import com.pocketshell.app.composer.OutboundState
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.composer.SharedPrefsOutboundQueueStore
import com.pocketshell.app.composer.composerOutboundQueueCheckTestTag
import com.pocketshell.app.composer.composerOutboundQueueHandledTestTag
import com.pocketshell.app.composer.composerOutboundQueueItemRowTestTag
import com.pocketshell.app.composer.composerOutboundQueueResendTestTag
import com.pocketshell.app.composer.composerOutboundQueueResendDialogBodyTestTag
import com.pocketshell.app.composer.composerOutboundQueueResendDialogConfirmTestTag
import com.pocketshell.app.composer.composerOutboundQueueResendDialogRootTestTag
import com.pocketshell.app.composer.composerOutboundQueueResendDialogTitleTestTag
import com.pocketshell.app.composer.composerOutboundQueueRetryTestTag
import com.pocketshell.app.composer.outboundQueueSummary
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.projects.TreeClientCache
import com.pocketshell.app.projects.TreeRemoteSource
import com.pocketshell.app.tmux.DurableOutboundRowIdentity
import com.pocketshell.app.tmux.HOST_CLI_UPGRADE_HINT
import com.pocketshell.app.tmux.HostAckSendFailedException
import com.pocketshell.app.tmux.HostAckPaneRefreshProbe
import com.pocketshell.app.tmux.HostAckSendProbe
import com.pocketshell.app.tmux.OutboundLegacyStackProbe
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_TAG
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * Issue #2124 (step 1b of epic #2121) — the ON-DEVICE end-to-end proof that the
 * host-CLI acknowledgement is the sole delivery authority.
 *
 * ## The reported scenario, on a real wire
 *
 * The maintainer's photograph (#1602, comment 5283865370) is `2 queued · 2
 * unconfirmed` on a GREEN session whose terminal is visibly rendering the
 * submitted prompt as **Working**. That state is undecidable to the old oracle
 * *by design*: the framed agent surface (`┃`, no marker glyph) is stripped as
 * decoration and nothing behind it is a prompt marker, so the bounded turnover
 * proof times out and the row parks at `wireOutcomeUnknown` forever.
 *
 * This journey drives exactly that surface —
 * `pocketshell-fake-agent` in [Issue2056InducedSubmitAmbiguity.FRAMED_INPUT_RENDER_MODE],
 * the same fixture the late-authority journeys use precisely BECAUSE no oracle
 * can read it — over the real Docker `agents:2222` SSH transport, on the SHIPPED
 * default (no authority pin), through the production send entry point the
 * composer drain calls. The row must reach Delivered anyway, because the host
 * answered.
 *
 * ## The unhappy host (G10)
 *
 * [oldHostCliWithoutSendFailsClosedAndNeverStrandsTheRow] runs the SAME send
 * against `agents-old-cli:2238`, whose `pocketshell` rejects `send` with Click's
 * exact unknown-command shape. A happy fixture cannot enter that state, which is
 * why the fixture that can is part of this change
 * (`tests/docker/agent-bin-old-cli/pocketshell`). The row must become a plain
 * retryable `Failed` naming the upgrade — bounded, never a hang, and never a new
 * "unknown" state.
 *
 * Docker services: `agents` on 2222 and `agents-old-cli` on 2238 — BOTH already
 * started by the emulator job in `.github/workflows/tests.yml` (2238 for the
 * existing `FolderListOldCliHydrateDockerTest`), so this class adds no new
 * service or port. Wired into `scripts/ci-journey-suite.sh`.
 */
@RunWith(AndroidJUnit4::class)
class Issue2124HostAckDeliveryJourneyE2eTest {
    private lateinit var trustedHostKeySha256: String

    val compose = createAndroidComposeRule<MainActivity>()

    val testName: TestName = TestName()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(testName)
        .around(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private lateinit var fixtureKey: String
    private lateinit var hostRowTag: String
    private lateinit var fixtureHostName: String
    private var seededPort: Int = DEFAULT_PORT

    /**
     * The old-CLI arm needs its host seeded on 2238 BEFORE launch, so the port is
     * derived from the test name (the rule chain runs before the method body).
     */
    private suspend fun seedBeforeLaunch() {
        clearLastSessionPrefs()
        // No authority pin: this journey must run on the SHIPPED default, which
        // is the whole point of step 1b. `clearOutboundDeliveryAuthorityPin`
        // asserts the singleton really answers the default, so a leftover pin
        // from a sibling class cannot silently turn this into a legacy run.
        clearOutboundDeliveryAuthorityPin()
        OutboundLegacyStackProbe.reset()
        HostAckSendProbe.reset()
        seededPort = if (testName.methodName.contains("oldHostCli", ignoreCase = true)) {
            OLD_CLI_PORT
        } else {
            DEFAULT_PORT
        }
        // TreeClientCache is keyed by HostEntity.name. This journey recreates the
        // same tmux session across methods and intentionally switches endpoints,
        // so a stable display name would make one method's exact generation an
        // explicit route for the next method. Give every seeded host its own
        // cache namespace; the journey still exercises the real host/port path.
        fixtureHostName = "$HOST_NAME_PREFIX ${testName.methodName} ${System.currentTimeMillis()}"
        fixtureKey = readFixtureKey()
        trustedHostKeySha256 = waitForSshFixtureReady(SshKey.Pem(fixtureKey), port = seededPort)
        seedFramedFakeAgentSession(fixtureKey, seededPort)
        hostRowTag = seedDockerHost(fixtureKey, seededPort)
    }

    @After
    fun tearDown() {
        runBlocking { runCatching { cleanupRemoteTmuxSession(fixtureKey, seededPort) } }
        runCatching {
            SharedPrefsOutboundQueueStore(
                InstrumentationRegistry.getInstrumentation().targetContext,
            ).clearSession(QUEUE_SESSION_KEY)
        }
    }

    /**
     * THE LOAD-BEARING JOURNEY: a prompt sent while the agent is Working reaches
     * Delivered, decided by the host CLI's exit status alone.
     */
    @Test
    fun workingAgentSendIsAcknowledgedAndTheRowClears() {
        val paneId = attachAndResolvePane()
        val vm = currentViewModel()
        val composer = currentComposerViewModel()
        val store = SharedPrefsOutboundQueueStore(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        val payload = "PSACK${System.currentTimeMillis().toString(36).takeLast(6)} restart pool"
        val row = store.enqueue(
            sessionKey = QUEUE_SESSION_KEY,
            cleanText = payload,
            paneId = paneId,
            route = OutboundRoute.AgentPayload,
            agentKind = "claude",
            sendKey = "sk-2124-${System.currentTimeMillis()}",
        )
        OutboundLegacyStackProbe.reset()
        HostAckSendProbe.reset()

        val sendStartedAt = SystemClock.elapsedRealtime()
        val result = runBlocking {
            vm.sendAgentPayloadToPaneResult(
                paneId,
                payload,
                AgentKind.ClaudeCode,
                sendToken = row.id,
                durableRow = DurableOutboundRowIdentity(QUEUE_SESSION_KEY, row.id),
            )
        }
        val sendElapsedMs = SystemClock.elapsedRealtime() - sendStartedAt

        assertEquals(
            "on the SHIPPED default exactly one acknowledged exec must have run — a " +
                "zero here means the default is not the acknowledged path on device",
            1L,
            HostAckSendProbe.count(),
        )
        // Every row-owning send now costs an SSH exec + a Python CLI start
        // instead of a `-CC` command on the warm transport. Send latency is a
        // live complaint (#1687), so the number is recorded rather than assumed.
        writeText(
            "issue2124-04-send-latency.txt",
            "acknowledged_send_ms=$sendElapsedMs\n" +
                "bound_ms=$HOST_ACK_SEND_LATENCY_BOUND_MS\n",
        )
        assertTrue(
            "an acknowledged send must stay inside its transport bound " +
                "(took ${sendElapsedMs}ms)",
            sendElapsedMs < HOST_ACK_SEND_LATENCY_BOUND_MS,
        )

        assertTrue(
            "a send to a WORKING agent must be ACKNOWLEDGED by the host, not guessed " +
                "from the screen; failure was ${result.exceptionOrNull()}",
            result.isSuccess,
        )
        assertEquals(
            "the acknowledged path must not consult the legacy inference stack at all: " +
                OutboundLegacyStackProbe.snapshot(),
            0L,
            OutboundLegacyStackProbe.total(),
        )
        assertTrue("the row must be prunable", store.markDelivered(row.id))
        assertNull("a delivered row is pruned", store.item(row.id))

        // GROUND TRUTH from the host, not from the app's belief: the payload is
        // in the pane exactly once, and the host journaled the token.
        val pane = runBlocking { remoteCapture(fixtureKey, seededPort, paneId) }
        writeText("issue2124-01-acknowledged-capture.txt", pane)
        assertEquals(
            "the token must keep the payload to exactly one occurrence in the pane:\n$pane",
            1,
            Regex(Regex.escape(payload.substringBefore(' '))).findAll(pane).count(),
        )
        val journal = runBlocking { remoteJournalListing(fixtureKey, seededPort) }
        writeText("issue2124-02-host-journal.txt", journal)
        assertTrue(
            "the host must have journaled the delivery token:\n$journal",
            journal.isNotBlank(),
        )
        assertTrue(
            "no row may be left unconfirmed",
            store.itemsFor(QUEUE_SESSION_KEY).none { it.wireOutcomeUnknown },
        )
    }

    /**
     * The SAME acknowledged delivery, driven the way the maintainer drives it:
     * typing into the production composer and tapping Send, on the shipped
     * default, against the Working agent surface.
     *
     * #1602 is a composer-queue symptom, so the composer drain — draft →
     * durable row → dispatch → prune — is the journey that matters. The
     * ViewModel-entry test above pins the delivery contract; this one pins that
     * the user-facing path reaches it and that the acknowledged lane is what ran.
     */
    @Test
    fun composerSendOnTheShippedDefaultIsAcknowledgedAndLeavesNoQueueRow() {
        val paneId = attachAndResolvePane()
        val store = SharedPrefsOutboundQueueStore(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        val payload = "PSCMP${System.currentTimeMillis().toString(36).takeLast(6)} ship it"
        OutboundLegacyStackProbe.reset()
        HostAckSendProbe.reset()

        val queueKey = currentQueueSessionKey()
        openComposerAndSend(payload)

        // The row must clear on its own: the acknowledged answer prunes it. If the
        // Send never dispatched, or the send never resolved, this is where it
        // fails — the row is the oracle, not the sheet dismissing.
        compose.waitUntil(timeoutMillis = QUEUE_DRAIN_TIMEOUT_MS) {
            HostAckSendProbe.count() >= 1L &&
                store.itemsFor(queueKey).none { it.cleanText == payload }
        }

        assertTrue(
            "the composer Send must have travelled the ACKNOWLEDGED lane on the " +
                "shipped default, not the legacy inference stack",
            HostAckSendProbe.count() >= 1L,
        )
        assertEquals(
            "the acknowledged path must not consult the legacy inference stack: " +
                OutboundLegacyStackProbe.snapshot(),
            0L,
            OutboundLegacyStackProbe.total(),
        )
        assertTrue(
            "no row may be left unconfirmed after a composer send",
            store.itemsFor(queueKey).none { it.wireOutcomeUnknown },
        )

        val pane = runBlocking { remoteCapture(fixtureKey, seededPort, paneId) }
        writeText("issue2124-05-composer-capture.txt", pane)
        assertEquals(
            "the composer prompt must be in the pane exactly once:\n$pane",
            1,
            Regex(Regex.escape(payload.substringBefore(' '))).findAll(pane).count(),
        )
    }

    /**
     * G10 unhappy host: an OLD host CLI without `send` must fail CLOSED to a plain
     * retryable `Failed` naming the upgrade — bounded, never a hang, never a new
     * unknown state.
     */
    @Test
    fun oldHostCliWithoutSendFailsClosedAndNeverStrandsTheRow() {
        assertFixtureCacheDoesNotReuseLegacyExactGeneration()
        val paneId = attachAndResolvePane()
        val vm = currentViewModel()
        val store = SharedPrefsOutboundQueueStore(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        val payload = "PSOLD${System.currentTimeMillis().toString(36).takeLast(6)} upgrade me"
        val row = store.enqueue(
            sessionKey = QUEUE_SESSION_KEY,
            cleanText = payload,
            paneId = paneId,
            route = OutboundRoute.AgentPayload,
            agentKind = "claude",
            sendKey = "sk-2124-old-${System.currentTimeMillis()}",
        )
        OutboundLegacyStackProbe.reset()

        val startedAt = SystemClock.elapsedRealtime()
        val result = runBlocking {
            vm.sendAgentPayloadToPaneResult(
                paneId,
                payload,
                AgentKind.ClaudeCode,
                sendToken = row.id,
                durableRow = DurableOutboundRowIdentity(QUEUE_SESSION_KEY, row.id),
            )
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt

        assertTrue("an old host CLI must fail, not silently succeed", result.isFailure)
        assertTrue(
            "the send must be BOUNDED, never a hang (took ${elapsed}ms)",
            elapsed < OLD_CLI_BOUND_MS,
        )
        val failure = result.exceptionOrNull()
        assertTrue(
            "the failure must be the acknowledged path's own type, not the turnover-" +
                "not-proven type that becomes the absorbing 'unconfirmed' row; got $failure",
            failure is HostAckSendFailedException,
        )
        assertEquals(
            "the message must name the host-CLI upgrade",
            HOST_CLI_UPGRADE_HINT,
            failure?.message,
        )
        assertEquals(
            "an old host CLI must NOT silently fall back to the old stack: " +
                OutboundLegacyStackProbe.snapshot(),
            0L,
            OutboundLegacyStackProbe.total(),
        )
        val stored = requireNotNull(store.item(row.id)) { "the row must survive as retryable" }
        assertFalse("the row must never be marked unconfirmed", stored.wireOutcomeUnknown)
        assertFalse("no write-ahead barrier may strand it", stored.wireSubmitAttempted)
        assertNull(
            "no UI path may show an 'unconfirmed' count",
            outboundQueueSummary(listOf(stored), connectionDegraded = false).attentionSuffix,
        )
        val pane = runBlocking { remoteCapture(fixtureKey, seededPort, paneId) }
        writeText("issue2124-03-old-cli-capture.txt", pane)
        assertFalse(
            "a failed acknowledgement must not have injected anything:\n$pane",
            pane.contains(payload.substringBefore(' ')),
        )
    }

    /**
     * Issue #2240's real ambiguity boundary: the repository host CLI has already
     * pasted the payload and is killed immediately after tmux accepts Enter. The
     * durable journal therefore remains `pending` with a dead owner. A plain
     * production queue dispatch must read the host's real exit 5, park the row as
     * typed UnknownMayHaveLanded, leave a healthy tail free to drain, and never
     * issue a second host send for the unknown head.
     */
    @Test
    fun realSendInterruptedJournalParksUnknownLeavesTailDrainingAndSurvivesStoreRestart() {
        val paneId = attachAndResolvePane()
        val composer = currentComposerViewModel()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SharedPrefsOutboundQueueStore(context)
        val sessionKey = currentQueueSessionKey()
        val suffix = System.currentTimeMillis().toString(36).takeLast(7)
        val headPayload = "PS2240H$suffix possible head"
        val tailPayload = "PS2240T$suffix healthy tail"
        val head = enqueueRawRow(store, sessionKey, paneId, headPayload, "head-$suffix")
        val tail = enqueueRawRow(store, sessionKey, paneId, tailPayload, "tail-$suffix")

        val seed = runBlocking {
            seedRealInterruptedJournal(
                key = fixtureKey,
                port = seededPort,
                paneId = paneId,
                token = head.id,
                payload = headPayload,
            )
        }
        writeText("issue2240-unknown-01-real-seed.txt", seed)
        assertTrue(
            "the real host seed must leave a pending journal record after tmux accepted Enter:\n$seed",
            seed.contains("\"state\": \"pending\"") || seed.contains("\"state\":\"pending\""),
        )
        assertTrue(
            "the real seed must name the durable row token:\n$seed",
            seed.contains(head.id),
        )

        // Refreshes the production collector only after the real pending record
        // exists. No direct send method is injected for this queue proof.
        compose.runOnUiThread { composer.refreshOutboundQueueItemsFor(sessionKey) }
        compose.waitUntil(timeoutMillis = QUEUE_DRAIN_TIMEOUT_MS) {
            store.item(head.id)?.hostAckOutcome == OutboundDeliveryOutcome.UnknownMayHaveLanded &&
                store.item(tail.id) == null
        }

        val unknown = requireNotNull(store.item(head.id))
        assertEquals(OutboundState.Queued, unknown.state)
        assertEquals(OutboundDeliveryOutcome.UnknownMayHaveLanded, unknown.hostAckOutcome)
        assertFalse("HostAck unknown must not set the legacy inference bit", unknown.wireOutcomeUnknown)
        assertEquals("the unknown head and healthy tail each use one host send", 2L, HostAckSendProbe.count())

        // Recreating the production SharedPreferences store is the process-death /
        // restart boundary. The typed outcome, not a volatile VM flag, must survive.
        val restartedStore = SharedPrefsOutboundQueueStore(context)
        val afterRestart = requireNotNull(restartedStore.item(head.id))
        assertEquals(OutboundDeliveryOutcome.UnknownMayHaveLanded, afterRestart.hostAckOutcome)
        assertEquals(OutboundState.Queued, afterRestart.state)
        assertNull("ordinary Retry must not re-arm an unknown host token", restartedStore.requeueForRetry(head.id))
        compose.runOnUiThread { composer.retryOutboundItem(head.id) }
        SystemClock.sleep(1_000)
        assertEquals("ordinary Retry must not dispatch the unknown head", 2L, HostAckSendProbe.count())

        val afterDrain = runBlocking { remoteCapture(fixtureKey, seededPort, paneId) }
        val submitLedger = runBlocking { remoteSubmitLedger(fixtureKey, seededPort) }
        writeText("issue2240-unknown-02-head-tail-capture.txt", afterDrain)
        writeText("issue2240-unknown-02-head-tail-submit-ledger.txt", submitLedger)
        // capture-pane intentionally shows only the fake agent's latest visible
        // turn. The ledger is the fixture's append-only ground truth for every
        // real tmux Enter, so it proves the unknown head was injected once by
        // the seed, the healthy tail once by the production drain, and that no
        // ordinary retry injected the head again.
        assertEquals(
            "the ledger must contain exactly the seed and tail submissions, including empty entries",
            2,
            countLedgerSubmissions(submitLedger),
        )
        assertEquals(1, countLedgerPayload(submitLedger, headPayload))
        assertEquals(1, countLedgerPayload(submitLedger, tailPayload))
        assertEquals(1, countOccurrences(afterDrain, tailPayload.substringBefore(' ')))

        openComposerQueue(head.id)
        assertTrue(hasTag(composerOutboundQueueCheckTestTag(head.id)))
        assertTrue(hasTag(composerOutboundQueueHandledTestTag(head.id)))
        assertTrue(hasTag(composerOutboundQueueResendTestTag(head.id)))
        assertFalse(
            "an unknown row must not render the ordinary Retry action",
            hasTag(composerOutboundQueueRetryTestTag(head.id)),
        )

        HostAckPaneRefreshProbe.reset()
        performVisibleQueueAction(composerOutboundQueueCheckTestTag(head.id))
        try {
            compose.waitUntil(timeoutMillis = QUEUE_DRAIN_TIMEOUT_MS) {
                HostAckPaneRefreshProbe.count() >= 1L
            }
        } catch (failure: Throwable) {
            writeText("issue2240-unknown-03-check-probe.txt", HostAckPaneRefreshProbe.snapshot())
            throw failure
        }
        assertEquals(paneId, HostAckPaneRefreshProbe.lastPaneId())
        assertEquals("Check pane is read-only and must not run another send", 2L, HostAckSendProbe.count())

        val afterCheck = runBlocking { remoteCapture(fixtureKey, seededPort, paneId) }
        val afterCheckLedger = runBlocking { remoteSubmitLedger(fixtureKey, seededPort) }
        writeText("issue2240-unknown-03-after-check.txt", afterCheck)
        writeText("issue2240-unknown-03-after-check-submit-ledger.txt", afterCheckLedger)
        assertEquals(
            "a read-only Check must not add even an empty ledger submission",
            2,
            countLedgerSubmissions(afterCheckLedger),
        )
        assertEquals(1, countLedgerPayload(afterCheckLedger, headPayload))
        assertEquals(1, countLedgerPayload(afterCheckLedger, tailPayload))

        openComposerQueue(head.id)
        performVisibleQueueAction(composerOutboundQueueHandledTestTag(head.id))
        compose.waitUntil(timeoutMillis = QUEUE_DRAIN_TIMEOUT_MS) {
            store.item(head.id) == null
        }
        writeText(
            "issue2240-unknown-04-final-queue.txt",
            "head=${store.item(head.id)}\ntail=${store.item(tail.id)}\n" +
                "hostAckSends=${HostAckSendProbe.count()}\n",
        )
    }

    /**
     * The only path that may inject a second copy is the confirmed, dialog-gated
     * explicit resend. This drives the same real pending journal left by the test
     * above, then proves the warning, `--resend-interrupted`, journal promotion,
     * and the resulting two physical occurrences in the pane.
     */
    @Test
    fun explicitResendAfterRealSendInterruptedRequiresWarningAndAcceptsDuplicateRisk() {
        val paneId = attachAndResolvePane()
        val composer = currentComposerViewModel()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SharedPrefsOutboundQueueStore(context)
        val sessionKey = currentQueueSessionKey()
        val suffix = System.currentTimeMillis().toString(36).takeLast(7)
        val payload = "PS2240R$suffix resend explicitly"
        val row = enqueueRawRow(store, sessionKey, paneId, payload, "resend-$suffix")

        val seed = runBlocking {
            seedRealInterruptedJournal(
                key = fixtureKey,
                port = seededPort,
                paneId = paneId,
                token = row.id,
                payload = payload,
            )
        }
        writeText("issue2240-resend-01-real-seed.txt", seed)
        assertTrue(
            "the resend test must start from the real pending journal:\n$seed",
            seed.contains("\"state\": \"pending\"") || seed.contains("\"state\":\"pending\""),
        )

        compose.runOnUiThread { composer.refreshOutboundQueueItemsFor(sessionKey) }
        compose.waitUntil(timeoutMillis = QUEUE_DRAIN_TIMEOUT_MS) {
            store.item(row.id)?.hostAckOutcome == OutboundDeliveryOutcome.UnknownMayHaveLanded
        }
        assertEquals(1L, HostAckSendProbe.count())

        openComposerQueue(row.id)
        performVisibleQueueAction(composerOutboundQueueResendTestTag(row.id))
        try {
            compose.waitUntil(timeoutMillis = COMPOSER_TIMEOUT_MS) {
                hasDirectTag(composerOutboundQueueResendDialogRootTestTag(row.id)) &&
                    hasDirectTag(composerOutboundQueueResendDialogTitleTestTag(row.id)) &&
                    hasDirectTag(composerOutboundQueueResendDialogBodyTestTag(row.id)) &&
                    hasDirectTag(composerOutboundQueueResendDialogConfirmTestTag(row.id))
            }
        } catch (failure: Throwable) {
            writeText(
                "issue2240-resend-01-dialog-probe.txt",
                "root=${hasDirectTag(composerOutboundQueueResendDialogRootTestTag(row.id))}\n" +
                    "title=${hasDirectTag(composerOutboundQueueResendDialogTitleTestTag(row.id))}\n" +
                    "body=${hasDirectTag(composerOutboundQueueResendDialogBodyTestTag(row.id))}\n" +
                    "confirm=${hasDirectTag(composerOutboundQueueResendDialogConfirmTestTag(row.id))}\n",
            )
            throw failure
        }
        compose.onNodeWithTag(
            composerOutboundQueueResendDialogConfirmTestTag(row.id),
            useUnmergedTree = true,
        ).performClick()

        compose.waitUntil(timeoutMillis = QUEUE_DRAIN_TIMEOUT_MS) {
            store.item(row.id) == null && HostAckSendProbe.count() == 2L
        }
        val record = runBlocking { remoteJournalRecord(fixtureKey, seededPort, row.id) }
        val capture = runBlocking { remoteCapture(fixtureKey, seededPort, paneId) }
        val submitLedger = runBlocking { remoteSubmitLedger(fixtureKey, seededPort) }
        writeText("issue2240-resend-02-delivered-record.txt", record)
        writeText("issue2240-resend-03-duplicate-risk-capture.txt", capture)
        writeText("issue2240-resend-03-duplicate-risk-submit-ledger.txt", submitLedger)
        assertTrue("explicit resend must promote the real journal", record.contains("\"state\": \"delivered\"") || record.contains("\"state\":\"delivered\""))
        assertEquals(
            "only the explicit duplicate-risk action may create the second occurrence",
            2,
            countLedgerPayload(submitLedger, payload),
        )
        assertEquals(
            "explicit resend must create exactly two total submissions, including empty entries",
            2,
            countLedgerSubmissions(submitLedger),
        )
    }

    // ------------------------------------------------------------- harness

    /** The durable-queue key the composer enqueues under for the live session. */
    private fun currentQueueSessionKey(): String =
        requireNotNull(currentViewModel().currentTargetSessionKeyForTest()) {
            "a live session target is required to read the composer queue"
        }

    private fun enqueueRawRow(
        store: SharedPrefsOutboundQueueStore,
        sessionKey: String,
        paneId: String,
        payload: String,
        sendKey: String,
    ): OutboundItem = store.enqueue(
        sessionKey = sessionKey,
        cleanText = payload,
        paneId = paneId,
        route = OutboundRoute.RawBytes,
        sendKey = "issue2240-$sendKey",
    )

    private fun hasTag(tag: String): Boolean = runCatching {
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    private fun hasDirectTag(tag: String): Boolean = runCatching {
            compose.onNodeWithTag(tag).fetchSemanticsNode()
        true
    }.getOrDefault(false)

    private fun hasText(text: String): Boolean = runCatching {
        compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    private fun sendEnabled(): Boolean = runCatching {
        val node = compose.onAllNodesWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull() ?: return false
        !node.config.contains(SemanticsProperties.Disabled)
    }.getOrDefault(false)

    /** Type into the production composer and tap its Send — no seam, no shortcut. */
    private fun openComposerAndSend(payload: String) {
        openComposer()
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true).performTextInput(payload)
        compose.waitUntil(timeoutMillis = COMPOSER_TIMEOUT_MS) { sendEnabled() }
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true).performClick()
    }

    private fun openComposer() {
        compose.waitUntil(timeoutMillis = COMPOSER_TIMEOUT_MS) {
            hasTag(SESSION_COMPOSER_LAUNCHER_TAG) || hasTag(COMPOSER_DRAFT_TAG)
        }
        if (!hasTag(COMPOSER_DRAFT_TAG)) {
            compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true)
                .performClick()
            compose.waitUntil(timeoutMillis = COMPOSER_TIMEOUT_MS) { hasTag(COMPOSER_DRAFT_TAG) }
        }
    }

    private fun openComposerQueue(itemId: String) {
        openComposer()
        compose.waitUntil(timeoutMillis = COMPOSER_TIMEOUT_MS) {
            hasTag(COMPOSER_OUTBOUND_QUEUE_BANNER_TAG)
        }
        val rowTag = composerOutboundQueueItemRowTestTag(itemId)
        if (!hasTag(rowTag)) {
            compose.onNodeWithTag(COMPOSER_OUTBOUND_QUEUE_TOGGLE_TAG, useUnmergedTree = true)
                .performClick()
        }
        compose.waitUntil(timeoutMillis = COMPOSER_TIMEOUT_MS) { hasTag(rowTag) }
        compose.onNodeWithTag(rowTag, useUnmergedTree = true).assertExists()
    }

    /**
     * Queue action tags are attached to the physical button, but the status
     * region is independently vertically scrollable. Match the merged
     * clickable node, scroll that node into the viewport, and only then invoke
     * it. A bare tag lookup can select an unmerged tag node whose bounds are
     * clipped while the real clickable ancestor remains below the fold.
     */
    private fun performVisibleQueueAction(tag: String) {
        val action = compose.onNode(
            hasTestTag(tag) and hasClickAction(),
            useUnmergedTree = false,
        )
        action.performScrollTo().assertIsDisplayed()
        action.performClick()
    }

    private fun attachAndResolvePane(): String {
        compose.waitUntil(timeoutMillis = HOST_ROW_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        // The `agents-old-cli` fixture reports an OLDER `pocketshell --version`
        // than the app, so connecting raises the "Host setup needed" bootstrap
        // sheet before the session list. Tap Skip exactly as
        // FolderListBootstrapSkipTreeLoadsDockerTest does. Bounded and
        // tolerant: the current-CLI fixture never shows the sheet.
        runCatching {
            compose.waitUntil(timeoutMillis = BOOTSTRAP_SHEET_PROBE_MS) {
                compose.onAllNodesWithTag(HOST_BOOTSTRAP_SKIP_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(HOST_BOOTSTRAP_SKIP_TAG, useUnmergedTree = true).performClick()
        }
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            compose.activityRule.scenario.onActivity { activity ->
                attached = activity.window.decorView.findTerminalView()
                    ?.currentSession?.emulator != null
            }
            attached
        }
        val vm = currentViewModel()
        return requireNotNull(vm.panes.value.firstOrNull()?.paneId) {
            "expected at least one attached pane to send into"
        }
    }

    private fun currentViewModel(): TmuxSessionViewModel {
        var vm: TmuxSessionViewModel? = null
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.activityRule.scenario.onActivity { activity ->
                vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
            }
            if ((vm?.panes?.value?.isNotEmpty()) == true) break
            SystemClock.sleep(100)
        }
        return requireNotNull(vm) { "TmuxSessionViewModel not available" }
    }

    private fun currentComposerViewModel(): PromptComposerViewModel {
        var vm: PromptComposerViewModel? = null
        compose.activityRule.scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[PromptComposerViewModel::class.java]
        }
        return requireNotNull(vm) { "PromptComposerViewModel not available" }
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }

    private suspend fun seedDockerHost(key: String, port: Int): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue2124-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = fixtureHostName,
                    hostname = DEFAULT_HOST,
                    port = port,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                    trustedHostKeySha256 = trustedHostKeySha256,
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    /**
     * The UNDECIDABLE surface: the framed fake-agent box real Claude/Codex draw
     * while WORKING. [Issue2056InducedSubmitAmbiguity] documents why no oracle can
     * read it — which is exactly why the acknowledged path must not need to.
     */
    private suspend fun seedFramedFakeAgentSession(key: String, port: Int) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            appendLine("rm -f /tmp/issue2240-submit-ledger")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_NAME)} -x 80 -y 40 " +
                    shellQuote(
                        "POCKETSHELL_FAKE_AGENT_RENDER_MODE=" +
                            Issue2056InducedSubmitAmbiguity.FRAMED_INPUT_RENDER_MODE +
                            " POCKETSHELL_FAKE_AGENT_SUBMIT_LEDGER=/tmp/issue2240-submit-ledger" +
                            " exec /usr/local/bin/pocketshell-fake-agent",
                    ),
            )
            appendLine("tmux set-option -p -t ${shellQuote(SESSION_NAME)} @ps_agent_kind claude")
            appendLine("sleep 1")
            appendLine("tmux list-sessions")
        }
        val result = execRemoteSetupUntilReady(
            key = SshKey.Pem(key),
            command = script,
            description = "issue2124 framed fake-agent seed",
            port = port,
        )
        assertTrue(
            "expected framed fake-agent seeding to succeed; exit=${result.exitCode} " +
                "stderr='${result.stderr}'",
            result.exitCode == 0,
        )
    }

    private suspend fun remoteExec(key: String, port: Int, command: String): String =
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = port,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(command).stdout } }.getOrElse { "" }

    private suspend fun remoteCapture(key: String, port: Int, paneId: String): String =
        remoteExec(key, port, "tmux capture-pane -p -t ${shellQuote(paneId)} 2>&1 || true")

    private suspend fun remoteJournalListing(key: String, port: Int): String =
        remoteExec(
            key,
            port,
            "ls -1 \"\${XDG_STATE_HOME:-\$HOME/.local/state}\"/pocketshell/sends 2>/dev/null || true",
        )

    private suspend fun remoteSubmitLedger(key: String, port: Int): String =
        remoteExec(key, port, "cat /tmp/issue2240-submit-ledger 2>/dev/null || true")

    /**
     * Run the repository's real `pocketshell send`, not the deterministic fixture
     * shim, and kill its owning Python process after the tmux send-keys call
     * returns. The shell survives to print the record, making the precondition
     * observable: one payload occurrence plus a genuine dead-owner `pending`
     * journal. The app's next ordinary call then has to receive the real exit 5.
     */
    private suspend fun seedRealInterruptedJournal(
        key: String,
        port: Int,
        paneId: String,
        token: String,
        payload: String,
    ): String {
        val payloadFile = "/tmp/issue2240-real-payload"
        val stdoutFile = "/tmp/issue2240-real-stdout"
        val stderrFile = "/tmp/issue2240-real-stderr"
        val script = buildString {
            appendLine("set +e")
            appendLine("state_dir=\"\${XDG_STATE_HOME:-\$HOME/.local/state}/pocketshell/sends\"")
            appendLine("mkdir -p \"\$state_dir\"")
            appendLine(
                "record=\"\$state_dir/\$(printf %s ${shellQuote(token)} | " +
                    "sha256sum | cut -d' ' -f1).json\"",
            )
            appendLine("rm -f \"\$record\" $payloadFile $stdoutFile $stderrFile /tmp/pocketshell-kill-after-enter")
            appendLine("printf %s ${shellQuote(payload)} > $payloadFile")
            appendLine("touch /tmp/pocketshell-kill-after-enter")
            appendLine(
                "/usr/local/bin/pocketshell-real-send send --pane ${shellQuote(paneId)} " +
                    "--token ${shellQuote(token)} --enter --timeout 10 < $payloadFile > $stdoutFile 2> $stderrFile",
            )
            appendLine("seed_rc=\$?")
            appendLine("rm -f /tmp/pocketshell-kill-after-enter $payloadFile")
            appendLine("printf '%s\\n' \"seed_rc=\$seed_rc\"")
            appendLine("printf '%s\\n' 'seed_stdout_begin'")
            appendLine("cat $stdoutFile 2>/dev/null || true")
            appendLine("printf '%s\\n' 'seed_stderr_begin'")
            appendLine("cat $stderrFile 2>/dev/null || true")
            appendLine("printf '%s\\n' 'seed_record_begin'")
            appendLine("printf '%s\\n' \"record=\$record\"")
            appendLine("cat \"\$record\" 2>/dev/null || printf '%s\\n' record_missing")
            appendLine("rm -f $stdoutFile $stderrFile")
        }
        return remoteExec(key, port, script)
    }

    private suspend fun remoteJournalRecord(key: String, port: Int, token: String): String {
        val digest = "printf %s ${shellQuote(token)} | sha256sum | cut -d' ' -f1"
        val record = "\$state_dir/\$($digest).json"
        return remoteExec(
            key,
            port,
            "state_dir=\"\${XDG_STATE_HOME:-\$HOME/.local/state}/pocketshell/sends\"; " +
                "record=\"$record\"; cat \"\$record\" 2>/dev/null || printf '%s\\n' record_missing",
        )
    }

    private fun countOccurrences(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var start = 0
        while (true) {
            val found = text.indexOf(needle, start)
            if (found < 0) return count
            count += 1
            start = found + needle.length
        }
    }

    private fun countLedgerPayload(ledger: String, payload: String): Int {
        val encoded = Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return ledger.lineSequence().count { it.substringAfter('|', missingDelimiterValue = "") == encoded }
    }

    /** Every `counter|payload` row counts, including the empty-payload `counter|` row. */
    private fun countLedgerSubmissions(ledger: String): Int =
        ledger.lineSequence().count { it.contains('|') }

    private suspend fun cleanupRemoteTmuxSession(key: String, port: Int) {
        remoteExec(key, port, "tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
    }

    private fun writeText(name: String, text: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) { "could not create ${dir.absolutePath}" }
        val file = File(dir, name)
        file.writeText(text)
        println("ISSUE2124_TEXT ${file.absolutePath}")
        return file
    }

    private fun View.findTerminalView(): TerminalView? {
        if (this is TerminalView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            val match = getChildAt(index).findTerminalView()
            if (match != null) return match
        }
        return null
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    /**
     * Load-bearing #2240 fixture guard. A prior run stored the recreated
     * session's exact tmux generation under the old shared display name. If
     * this journey ever goes back to that name-only namespace, the old host
     * bind can hydrate that generation before the authoritative list-panes
     * result, producing the Attaching -> Live-without-reveal failure. The
     * assertion is deliberately against the real cache implementation and an
     * exact-generation node, not a timing/wait heuristic.
     */
    private fun assertFixtureCacheDoesNotReuseLegacyExactGeneration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = TreeClientCache(context)
        cache.write(
            21240L,
            1L,
            TreeClientCache.CachedTree(
                nodes = listOf(
                    TreeRemoteSource.TreeNode(
                        session = SESSION_NAME,
                        order = 0,
                        folderPath = "/tmp/issue2240",
                        collapsed = false,
                        tmuxSessionId = "\$7",
                        sessionCreated = 1_700_000_038L,
                    ),
                ),
            ),
        )
        try {
            assertNull(
                "the #2240 fixture host must not hydrate the legacy shared-name " +
                    "exact generation; hostName=$fixtureHostName",
                cache.peek(21241L),
            )
        } finally {
            cache.write(21240L, 2L, TreeClientCache.CachedTree(nodes = emptyList()))
            cache.write(21241L, 2L, TreeClientCache.CachedTree(nodes = emptyList()))
        }
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val DEVICE_DIR_NAME: String = "issue2124-host-ack"
        const val SESSION_NAME: String = "issue2124-framed-agent"
        const val HOST_NAME_PREFIX: String = "Issue2124 Host Ack"
        const val LEGACY_HOST_NAME: String = HOST_NAME_PREFIX
        const val QUEUE_SESSION_KEY: String = "issue2124/hostack"

        /** `agents-old-cli` — the fixture whose `pocketshell` has no `send`. */
        const val OLD_CLI_PORT: Int = 2238

        /**
         * The old-CLI send must answer well inside the transport bound. Generous
         * enough for a contended emulator, tight enough that a HANG fails.
         */
        const val OLD_CLI_BOUND_MS: Long = 30_000L

        /**
         * The whole acknowledged round trip (SSH exec + Python CLI start + tmux
         * paste) must complete well inside the port's 15s transport bound. The
         * measured number is written to an artifact either way; this only fails
         * a send that has effectively hung.
         */
        const val HOST_ACK_SEND_LATENCY_BOUND_MS: Long = 20_000L

        const val HOST_ROW_TIMEOUT_MS: Long = 60_000L

        /**
         * How long to look for the old-CLI bootstrap sheet before concluding the
         * host does not raise one. Short: the sheet is decided at connect time.
         */
        const val BOOTSTRAP_SHEET_PROBE_MS: Long = 20_000L

        /** Composer launcher/draft/Send visibility on a contended emulator. */
        const val COMPOSER_TIMEOUT_MS: Long = 30_000L

        /**
         * How long the acknowledged answer has to prune the composer's durable
         * row. One SSH exec plus a CLI start; generous for a contended AVD, and a
         * genuine hang still fails.
         */
        const val QUEUE_DRAIN_TIMEOUT_MS: Long = 60_000L
    }
}
