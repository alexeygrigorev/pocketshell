package com.pocketshell.app.tmux

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.composer.InMemoryOutboundQueueStore
import com.pocketshell.app.composer.OUTBOUND_AUTO_RETRY_EXHAUSTED_MESSAGE
import com.pocketshell.app.composer.OUTBOUND_MAX_AUTO_ATTEMPTS
import com.pocketshell.app.composer.OutboundQueueStore
import com.pocketshell.app.composer.OutboundRoute
import com.pocketshell.app.composer.OutboundState
import com.pocketshell.app.composer.acknowledgeLateOutboundDeliveries
import com.pocketshell.app.composer.collectPromptComposerSendRequests
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.composer.SharedPrefsOutboundQueueStore
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.session.SessionTab
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.voice.WhisperClient
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #1686 (Track C) — the ON-DEVICE queue-drain journey (reviewer G4 / D33
 * gate). The maintainer's daily blocker: "the composer queue gets clogged
 * because it thinks the connection is not there." The #1680 reconnect storm
 * produces a FALSE / flapping `ConnectionStatus` (not-Connected) while the `-CC`
 * transport is perfectly writable, and both enum-trusting layers shut the drain:
 *
 *  - **Admission** ([TmuxSessionViewModel.liveTmuxClientForSendOrNull]) refused a
 *    live `clientRef` unless the enum said `Connected`, so a dispatched send
 *    failed ("Session is disconnected") even though the wire would accept it.
 *  - **The drain gate** ([runOutboundQueueAutoFlush]) was hard-gated on the enum
 *    (`sessionLive`), so during a false-disconnect NOTHING even tried the wire.
 *
 * The JVM proofs ([Issue1686WireOracleSendTest], [PromptComposerWireOracleClogTest])
 * pin the seams, but the reviewer BLOCKED (G4) because a JVM proxy is not the
 * acceptance for a user-facing composer×connection fix — the acceptance is a
 * connected (emulator + Docker) journey proving the clog is GONE on the REAL wire.
 *
 * This test is that journey. It:
 *  1. attaches to a REAL `opencode-lab` tmux session over a REAL SSH `-CC`
 *     transport on the Docker `agents` fixture (the live activity-scoped
 *     [TmuxSessionViewModel] with a genuinely-writable `clientRef`),
 *  2. injects the EXACT false-disconnect the storm produces — the #780
 *     synthetic-injection model, deterministic, no self-skip:
 *     [TmuxSessionViewModel.forceControllerReconnectingStatusKeepingClientForTest]
 *     flips the inline enum to `Reconnecting` (the admission gate's oracle) while
 *     the real `clientRef` stays live, and the drain runs with `sessionLive=false`
 *     (the drain gate's enum oracle). BOTH enum labels are false; the wire is
 *     genuinely alive,
 *  3. enqueues a composer prompt and drives the PRODUCTION drain machinery — the
 *     real [runOutboundQueueAutoFlush] + [PromptComposerViewModel.retryNextOutboundItem]
 *     + the `PromptComposerSendDispatcher`-shaped collector whose `onSend` is the
 *     production [TmuxSessionViewModel.writeInputToPaneResult] — and asserts the
 *     prompt DRAINS over the writable wire: the marker appears in the real tmux
 *     pane (authoritative `capture-pane` artifact),
 *  4. covers the transport-alive-edge self-heal
 *     ([PromptComposerViewModel.unparkTransportFailedRows]): a storm-stranded
 *     auto-parked `Failed` backlog un-parks on the connected edge and then drains
 *     to the real pane.
 *
 * RED reproduction (reviewer, this run):
 *  - revert the admission hunk (restore the removed VM-private not-Connected
 *    return null` in [TmuxSessionViewModel.liveTmuxClientForSendOrNull]) → the send
 *    is refused on the false `Reconnecting` label → the marker NEVER reaches the
 *    pane (the clog) → this test times out red.
 *  - OR revert the drain-gate hunk (`drainGateOpen() = sessionLive`) → the drain
 *    never attempts the wire with `sessionLive=false` → the marker never reaches
 *    the pane → red.
 *
 * Uses the plain deterministic `agents:2222` fixture (no toxiproxy) — the false-
 * disconnect is injected at the two exact seams the fix touches, so the wire is a
 * genuinely healthy SSH connection whose writability is the strongest possible
 * proof that the enum label was false. Wired into `scripts/ci-journey-suite.sh`.
 */
@RunWith(AndroidJUnit4::class)
class Issue1686QueueDrainWireOracleDockerTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    // Grant runtime permissions before MainActivity launches (issue #470 blocker
    // #1) so the system GrantPermissionsActivity never steals Compose focus.
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val harnessScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val createdComposerVms = mutableListOf<PromptComposerViewModel>()

    @After
    fun tearDown() {
        harnessScope.cancel()
        runCatching {
            SharedPrefsOutboundQueueStore(
                InstrumentationRegistry.getInstrumentation().targetContext,
            ).clearSession(ISSUE_2056_TARGET)
        }
        compose.runOnUiThread {
            createdComposerVms.forEach { runCatching { it.clearForTest() } }
        }
        createdComposerVms.clear()
        runCatching { launchedActivity?.close() }
        launchedActivity = null
        runBlocking { runCatching { cleanupSeededSession(readFixtureKey()) } }
    }

    // ---------------------------------------------------------------- Tests

    @Test
    fun queuedComposerSendDrainsOverWritableWireWhileConnectionStatusFalselyNotConnected() {
        runBlocking {
            val key = readFixtureKey()
            waitForSshFixtureReady(SshKey.Pem(key))
            seedInteractiveSession(key)
            val hostRowTag = seedDockerHost(key, "Issue1686 Drain")

            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            attachToOpencodeLab(hostRowTag)

            val liveVm = liveTmuxViewModel()
            val paneId = awaitAttachedPaneId(liveVm)

            // --- Inject the EXACT false-disconnect (the #1680 storm): the inline
            //     enum flips to `Reconnecting` (admission oracle) while the real
            //     `clientRef` stays live + writable.
            onMainUnit { liveVm.forceControllerReconnectingStatusKeepingClientForTest() }

            // The WIRE is the oracle: it must report the transport truth over the
            // real socket even though the ConnectionStatus enum now says not-Connected.
            val writable = liveVm.isSendTransportWritable()
            assertTrue(
                "the wire-oracle must see the writable `-CC` transport over the REAL socket " +
                    "even though the inline ConnectionStatus enum falsely reports Reconnecting (#1686)",
                writable,
            )

            // --- Build the REAL composer + queue, target the drain session, and
            //     wire the WIRE-oracle probe to the live TmuxSessionViewModel exactly
            //     as TmuxSessionScreenEffects does in production.
            val queue = InMemoryOutboundQueueStore()
            val composer = newComposerVm(queue)
            val target = "issue1686/drain"
            onMainUnit {
                composer.onComposerTargetChanged(target)
                composer.setTransportWritableProbe { liveVm.isSendTransportWritable() }
                composer.setSendWatchdogTimeoutForTest(null)
            }

            startProductionDrain(composer, queue, liveVm, paneId, target)

            // --- Enqueue a prompt while the status FALSELY says not-Connected.
            val marker = "PSDRAIN${System.currentTimeMillis().toString(36).takeLast(6)}"
            onMainUnit {
                queue.enqueue(
                    sessionKey = target,
                    cleanText = "# $marker",
                    createdAtMs = System.currentTimeMillis(),
                )
                composer.refreshOutboundQueueItemsFor(target)
            }

            // --- The prompt must DRAIN over the writable wire and reach the REAL
            //     tmux pane (authoritative capture-pane), and the queue must empty.
            val pane = waitForPaneContains(key, marker, label = "drain")
            awaitTrue("queue drains to empty after delivery") {
                composer.outboundQueueItems.value.isEmpty()
            }

            captureViewport("01-drained-over-writable-wire")
            writeSummary(
                testName = "queuedComposerSendDrainsOverWritableWire",
                lines = listOf(
                    "target=$target",
                    "pane_id=$paneId",
                    "marker=$marker",
                    "inline_enum=Reconnecting (false-disconnect, injected)",
                    "wire_writable=$writable",
                    "drain_gate_sessionLive=false",
                    "captured_pane_contains_marker=${marker in pane}",
                    "queue_empty_after_delivery=true",
                ),
            )
        }
    }

    @Test
    fun transportAliveEdgeUnparksAutoFailedRowThenDrainsOverWire() {
        runBlocking {
            val key = readFixtureKey()
            waitForSshFixtureReady(SshKey.Pem(key))
            seedInteractiveSession(key)
            val hostRowTag = seedDockerHost(key, "Issue1686 Unpark")

            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            attachToOpencodeLab(hostRowTag)

            val liveVm = liveTmuxViewModel()
            val paneId = awaitAttachedPaneId(liveVm)
            onMainUnit { liveVm.forceControllerReconnectingStatusKeepingClientForTest() }

            val queue = InMemoryOutboundQueueStore()
            val composer = newComposerVm(queue)
            val target = "issue1686/unpark"
            onMainUnit {
                composer.onComposerTargetChanged(target)
                composer.setTransportWritableProbe { liveVm.isSendTransportWritable() }
                composer.setSendWatchdogTimeoutForTest(null)
            }

            // --- Model the storm-stranded backlog: an AUTO-parked Failed row
            //     (budget exhausted).
            val marker = "PSUNPARK${System.currentTimeMillis().toString(36).takeLast(6)}"
            val rowId = onMain {
                val row = queue.enqueue(
                    sessionKey = target,
                    cleanText = "# $marker",
                    createdAtMs = System.currentTimeMillis(),
                )
                repeat(OUTBOUND_MAX_AUTO_ATTEMPTS) {
                    checkNotNull(queue.claim(row.id))
                    checkNotNull(queue.requeueForRetry(row.id))
                }
                composer.refreshOutboundQueueItemsFor(target)
                // Exercise the production selection/parking transition instead of
                // fabricating a Failed row: the exhausted Queued head is surfaced as
                // the exact #1602 auto-retry failure shown in the user's screenshot.
                val parkingController = OutboundQueueAutoFlushController.boundTo(composer)
                parkingController.onConnectionWindowChanged(true, target) {}
                parkingController.onQueueSnapshotChanged(true) { excludingIds ->
                    composer.retryNextOutboundItem(excludingIds)
                }
                row.id
            }
            assertEquals(OUTBOUND_MAX_AUTO_ATTEMPTS, queue.item(rowId)?.attemptCount)
            assertEquals(OUTBOUND_AUTO_RETRY_EXHAUSTED_MESSAGE, queue.item(rowId)?.lastError)
            assertEquals(
                "precondition: the row is auto-parked Failed (a storm-stranded backlog)",
                OutboundState.Failed,
                queue.item(rowId)?.state,
            )

            // --- The transport-alive edge self-heal (production wires this to
            //     onConnectionWindowChanged's connected edge in TmuxSessionScreenEffects).
            val unparked = onMain { composer.unparkTransportFailedRows() }
            assertTrue(
                "the transport-alive edge must un-park the auto-parked backlog (#1686 self-heal)",
                rowId in unparked,
            )
            assertEquals(
                "the un-parked row re-arms to Queued so the drain can re-claim it",
                OutboundState.Queued,
                queue.item(rowId)?.state,
            )

            // --- The re-armed row must then DRAIN over the writable wire to the
            //     REAL pane.
            startProductionDrain(composer, queue, liveVm, paneId, target)
            val pane = waitForPaneContains(key, marker, label = "unpark-drain")
            awaitTrue("un-parked backlog drains to empty after delivery") {
                composer.outboundQueueItems.value.isEmpty()
            }

            captureViewport("02-unparked-then-drained")
            writeSummary(
                testName = "transportAliveEdgeUnparksAutoFailedRowThenDrains",
                lines = listOf(
                    "target=$target",
                    "pane_id=$paneId",
                    "marker=$marker",
                    "row_state_before_unpark=Failed(auto-exhausted)",
                    "unparked_ids=$unparked",
                    "row_state_after_unpark=Queued",
                    "captured_pane_contains_marker=${marker in pane}",
                    "queue_empty_after_delivery=true",
                ),
            )
        }
    }

    @Test
    fun silentWireRecoveryUnparksAutoFailedRowWithoutConnectionEnumEdge() {
        runBlocking {
            val key = readFixtureKey()
            waitForSshFixtureReady(SshKey.Pem(key))
            seedInteractiveSession(key)
            val hostRowTag = seedDockerHost(key, "Issue2042 Silent Wire Heal")

            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            attachToOpencodeLab(hostRowTag)

            val liveVm = liveTmuxViewModel()
            val paneId = awaitAttachedPaneId(liveVm)
            onMainUnit { liveVm.forceControllerReconnectingStatusKeepingClientForTest() }
            assertTrue("precondition: the real transport is writable", liveVm.isSendTransportWritable())

            val queue = InMemoryOutboundQueueStore()
            val composer = newComposerVm(queue)
            val target = "issue2042/silent-wire-heal"
            var exposeWritableWire = false
            val wireOracle = { exposeWritableWire && liveVm.isSendTransportWritable() }
            onMainUnit {
                composer.onComposerTargetChanged(target)
                composer.setTransportWritableProbe(wireOracle)
                composer.setSendWatchdogTimeoutForTest(null)
            }

            val marker = "PS2042${System.currentTimeMillis().toString(36).takeLast(6)}"
            val rowId = onMain {
                val row = queue.enqueue(
                    sessionKey = target,
                    cleanText = "# $marker",
                    createdAtMs = System.currentTimeMillis(),
                )
                repeat(OUTBOUND_MAX_AUTO_ATTEMPTS) {
                    checkNotNull(queue.claim(row.id))
                    checkNotNull(queue.requeueForRetry(row.id))
                }
                composer.refreshOutboundQueueItemsFor(target)
                val parkingController = OutboundQueueAutoFlushController.boundTo(composer)
                parkingController.onConnectionWindowChanged(true, target) {}
                parkingController.onQueueSnapshotChanged(true) { excludingIds ->
                    composer.retryNextOutboundItem(excludingIds)
                }
                row.id
            }
            assertEquals(OUTBOUND_MAX_AUTO_ATTEMPTS, queue.item(rowId)?.attemptCount)
            assertEquals(OUTBOUND_AUTO_RETRY_EXHAUSTED_MESSAGE, queue.item(rowId)?.lastError)

            // Start while the wire oracle is false. The coarse connection enum stays
            // Reconnecting for the entire journey, so there is deliberately NO
            // LaunchedEffect(sessionLive,target) edge to rescue the row.
            startProductionDrain(
                composer = composer,
                queue = queue,
                liveVm = liveVm,
                paneId = paneId,
                target = target,
                transportWritable = wireOracle,
            )
            // Cross a full production poll cadence while the wire stays hidden. This
            // proves the pre-heal row really is parked, rather than merely waiting for
            // the initial StateFlow collection turn.
            SystemClock.sleep(OUTBOUND_DEFERRED_REDISPATCH_BACKOFF_MS + 500L)
            assertEquals(OutboundState.Failed, queue.item(rowId)?.state)

            // The underlying real socket was alive throughout; reveal that truth to
            // the production poll oracle WITHOUT changing the enum. #2042 requires
            // the wire false→true edge itself to unpark and drain the failed head.
            exposeWritableWire = true

            val pane = waitForPaneContains(key, marker, label = "issue2042-silent-wire-heal")
            awaitTrue("silent wire recovery drains the failed row without Retry") {
                composer.outboundQueueItems.value.isEmpty()
            }
            captureViewport("03-issue2042-silent-wire-heal")
            writeSummary(
                testName = "silentWireRecoveryUnparksAutoFailedRowWithoutConnectionEnumEdge",
                lines = listOf(
                    "target=$target",
                    "pane_id=$paneId",
                    "marker=$marker",
                    "connection_enum_unchanged=Reconnecting",
                    "wire_transition=false->true",
                    "manual_retry=false",
                    "captured_pane_contains_marker=${marker in pane}",
                    "queue_empty_after_delivery=true",
                ),
            )
        }
    }

    /**
     * Issue #2056 (D33/G4 connected acceptance) — "the payload was delivered but the
     * composer queue is never cleared".
     *
     * Drives an AGENT-ROUTE composer send over the REAL `-CC` transport to a REAL
     * tmux pane that has NO transcript authority at all (a plain interactive shell,
     * exactly the `csp`-style relaunch the maintainer reported). The production
     * delivery path runs unmodified: [TmuxSessionViewModel.sendAgentPayloadToPaneResult]
     * with the row's durable identity, the production result -> deferral mapping in
     * [collectPromptComposerSendRequests], the production auto-flush drain, and the
     * production late-authority bridge.
     *
     * RED on base (this class, this fixture): the payload lands in the pane exactly
     * once, and the durable row NEVER leaves the queue — the bounded turnover proof
     * cannot read the pane's prompt, the durable submit baseline is null so #2037's
     * transcript-only late ack can never fire, and every auto-flush dispatch reports
     * the same unknown outcome while re-granting its retry budget, so the row also
     * holds the FIFO head forever.
     *
     * GREEN: the payload still reaches the pane EXACTLY ONCE (no duplicate), the row
     * reaches a terminal acknowledgement, the queue empties, and a second prompt
     * queued behind it also drains.
     */
    @Test
    fun deliveredAgentRouteRowLeavesTheQueueWithNoTranscriptAuthority() {
        runBlocking {
            val key = readFixtureKey()
            waitForSshFixtureReady(SshKey.Pem(key))
            seedInteractiveSession(key, prompt = "\u276f ")
            val hostRowTag = seedDockerHost(key, "Issue2056 Delivered Row")

            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            attachToOpencodeLab(hostRowTag)

            val liveVm = liveTmuxViewModel()
            val paneId = awaitAttachedPaneId(liveVm)
            val identity = readTmuxSessionIdentity(key)

            // The durable store the LIVE VM's delivery ledger writes its wire/submit
            // write-ahead into is the app's SharedPreferences-backed singleton, so the
            // journey's composer must read the SAME durable rows (Android caches one
            // SharedPreferences instance per name per process).
            val queue = SharedPrefsOutboundQueueStore(
                InstrumentationRegistry.getInstrumentation().targetContext,
            )
            val composer = newComposerVm(queue)
            // A durable `tmux:` session key: the rows carry a real tmux generation, and
            // `hasGenerationBoundRowsAwaitingPromotion` deliberately holds the drain for a
            // generation-stamped row parked under a host/name fallback key (#1944).
            val target = ISSUE_2056_TARGET
            onMainUnit {
                composer.onComposerTargetChanged(target)
                composer.setTransportWritableProbe { liveVm.isSendTransportWritable() }
                composer.setSendWatchdogTimeoutForTest(null)
            }

            val marker = "PS2056${System.currentTimeMillis().toString(36).takeLast(6)}"
            val tailMarker = "PS2056T${System.currentTimeMillis().toString(36).takeLast(5)}"

            // The PRODUCTION consumer: its onSend is the production agent-payload
            // delivery, and its result -> deferral mapping is the code under test.
            harnessScope.launch {
                collectPromptComposerSendRequests(
                    viewModel = composer,
                    onSend = { request ->
                        val rowId = request.outboundQueueItemId
                        liveVm.sendAgentPayloadToPaneResult(
                            paneId,
                            request.text,
                            AgentKind.ClaudeCode,
                            sendToken = rowId ?: "",
                            durableRow = rowId?.let { DurableOutboundRowIdentity(target, it) },
                        ).toComposerSendResult()
                    },
                )
            }
            // The PRODUCTION late-authority bridge, on the same bounded cadence the
            // composable effect uses.
            val binding = TmuxOutboundQueueBinding(
                targetKey = target,
                fallbackKey = target,
                durableKey = target,
                tmuxSessionId = identity.first,
                sessionCreated = identity.second,
                generationPaneIds = setOf(paneId),
            )
            harnessScope.launch {
                while (true) {
                    reconcileLateOutboundAcks(
                        rows = composer.outboundQueueItems.value,
                        binding = binding,
                        resolveAuthoritativeAck = liveVm::resolveLateAuthoritativeOutboundAck,
                        acknowledge = { resolved ->
                            composer.acknowledgeLateOutboundDeliveries(
                                resolved,
                                onAcknowledged = liveVm::consumeLateAuthoritativeOutboundAck,
                            )
                        },
                    )
                    kotlinx.coroutines.delay(500L)
                }
            }
            val controller =
                OutboundQueueAutoFlushController.boundTo(composer, clock = { SystemClock.elapsedRealtime() })
            harnessScope.launch {
                controller.onConnectionWindowChanged(sessionLive = true, targetSessionId = target) {}
                runOutboundQueueAutoFlush(
                    sessionLive = true,
                    outboundQueueItems = composer.outboundQueueItems,
                    controller = controller,
                    retryNext = { excludingIds -> composer.retryNextOutboundItem(excludingIds) },
                    transportWritable = { liveVm.isSendTransportWritable() },
                    unparkTransportFailedRows = { composer.unparkTransportFailedRows() },
                )
            }

            onMainUnit {
                listOf(marker to 0L, tailMarker to 1L).forEach { (text, offset) ->
                    queue.enqueue(
                        sessionKey = target,
                        cleanText = "# $text",
                        createdAtMs = System.currentTimeMillis() + offset,
                        paneId = paneId,
                        route = OutboundRoute.AgentPayload,
                        agentKind = "claude",
                        sendKey = "sk-$text",
                        tmuxSessionId = identity.first,
                        tmuxSessionCreated = identity.second,
                    )
                }
                composer.refreshOutboundQueueItemsFor(target)
            }

            // 1) The payload physically reaches the REAL pane.
            val pane = waitForPaneContains(key, marker, label = "issue2056-delivered")
            // 2) ... and the durable row must LEAVE the queue. RED on base: it never does.
            awaitTrue("the delivered row leaves the composer queue") {
                composer.outboundQueueItems.value.none { it.cleanText.contains(marker) }
            }
            // 3) The tail behind it also drains — a clogged head must not poison it.
            val tailPane = waitForPaneContains(key, tailMarker, label = "issue2056-tail")
            awaitTrue("the whole queue drains") { composer.outboundQueueItems.value.isEmpty() }

            // Exactly once means exactly once. The assertion used to allow `<= 2` while
            // its message claimed "exactly once" — a wording/constraint mismatch (G6)
            // that would have accepted a genuine duplicate re-paste. The `sh -i` pane
            // echoes each `# <marker>` comment line once and nothing re-prints it.
            val occurrences = Regex(Regex.escape(marker)).findAll(tailPane).count()
            assertEquals(
                "the delivered payload must reach the pane EXACTLY once (no duplicate " +
                    "re-paste); pane:\n$tailPane",
                1,
                occurrences,
            )
            val tailOccurrences = Regex(Regex.escape(tailMarker)).findAll(tailPane).count()
            assertEquals(
                "the tail payload must also reach the pane EXACTLY once; pane:\n$tailPane",
                1,
                tailOccurrences,
            )

            // Reviewer-usable artifacts: the app's OWN rendered terminal text next to the
            // authoritative server-side capture, and a viewport taken with the Terminal
            // tab selected so the PNG shows the delivered payload rather than whatever
            // tab happened to be mounted. The user-visible queue state is written out as
            // text because this journey's composer is a harness-owned view model that is
            // not mounted in the activity's UI.
            selectTerminalTabForArtifacts(liveVm, paneId)
            writeArtifactText(
                "issue2056-delivered-row-leaves-queue-visible-terminal.txt",
                visibleTerminalText(),
            )
            writeArtifactText(
                "issue2056-delivered-row-leaves-queue-server-capture.txt",
                tailPane,
            )
            writeArtifactText(
                "issue2056-delivered-row-leaves-queue-queue-surface.txt",
                buildString {
                    appendLine("composer_queue_rows=${composer.outboundQueueItems.value.size}")
                    composer.outboundQueueItems.value.forEach { row ->
                        appendLine("  id=${row.id} state=${row.state} text=${row.cleanText}")
                    }
                    appendLine("durable_rows_for_target=${queue.itemsFor(target).size}")
                },
            )
            captureViewport("04-issue2056-delivered-row-leaves-queue")
            writeSummary(
                testName = "deliveredAgentRouteRowLeavesTheQueueWithNoTranscriptAuthority",
                lines = listOf(
                    "target=$target",
                    "pane_id=$paneId",
                    "tmux_session_id=${identity.first} created=${identity.second}",
                    "marker=$marker tail_marker=$tailMarker",
                    "transcript_authority=none (plain interactive shell)",
                    "captured_pane_contains_marker=${marker in pane}",
                    "captured_pane_contains_tail_marker=${tailMarker in tailPane}",
                    "marker_occurrences=$occurrences",
                    "tail_marker_occurrences=$tailOccurrences",
                    "composer_queue_rows_after_delivery=${composer.outboundQueueItems.value.size}",
                    "durable_rows_after_delivery=${queue.itemsFor(target).size}",
                    "queue_empty_after_delivery=true",
                ),
            )
        }
    }

    // ------------------------------------------------------- Drain machinery

    /**
     * Start the PRODUCTION drain machinery, wired to the live [TmuxSessionViewModel]
     * over the real wire:
     *  - a `PromptComposerSendDispatcher`-shaped collector whose `onSend` is the
     *    production [TmuxSessionViewModel.writeInputToPaneResult] (so reverting the
     *    admission hunk reds this journey), and whose failure classification is the
     *    production `resetAttemptBudget = !isSendTransportWritable()` taxonomy;
     *  - the real [runOutboundQueueAutoFlush] with `sessionLive=false` and the
     *    wire-oracle `transportWritable` probe (so reverting the drain-gate hunk
     *    reds this journey).
     * Both run on the Main dispatcher on [harnessScope] and are cancelled in @After.
     */
    private fun startProductionDrain(
        composer: PromptComposerViewModel,
        queue: OutboundQueueStore,
        liveVm: TmuxSessionViewModel,
        paneId: String,
        target: String,
        transportWritable: () -> Boolean = { liveVm.isSendTransportWritable() },
    ) {
        // The dispatcher: mirrors PromptComposerSendDispatcher's #745 bounded send
        // + #1686 failure taxonomy, but its onSend is the PRODUCTION send path.
        harnessScope.launch {
            composer.sendRequests.collect { request ->
                val delivered = runCatching {
                    withTimeoutOrNull(PromptComposerViewModel.SEND_TIMEOUT_MS) {
                        liveVm.writeInputToPaneResult(
                            paneId,
                            (request.text + "\r").toByteArray(Charsets.UTF_8),
                        ).isSuccess
                    } == true
                }.getOrDefault(false)
                if (delivered) {
                    composer.markSendDelivered(request)
                } else {
                    composer.markOutboundSendDeferred(
                        request,
                        resetAttemptBudget = !transportWritable(),
                    )
                }
            }
        }
        // The real production drain: the WIRE-oracle gate opens on
        // `sessionLive || transportWritable()`. sessionLive=false models the enum
        // false-disconnect; the probe reads the live wire.
        val controller =
            OutboundQueueAutoFlushController.boundTo(composer, clock = { SystemClock.elapsedRealtime() })
        harnessScope.launch {
            controller.onConnectionWindowChanged(sessionLive = false, targetSessionId = target) {}
            runOutboundQueueAutoFlush(
                sessionLive = false,
                outboundQueueItems = composer.outboundQueueItems,
                controller = controller,
                retryNext = { excludingIds -> composer.retryNextOutboundItem(excludingIds) },
                transportWritable = transportWritable,
                unparkTransportFailedRows = { composer.unparkTransportFailedRows() },
            )
        }
    }

    // ------------------------------------------------------------ UI journey

    private fun attachToOpencodeLab(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 45_000) {
            compose.onAllNodesWithText(SESSION_LAB, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText(SESSION_LAB).performClick()
        compose.waitUntil(timeoutMillis = 45_000) {
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // Wait for the real terminal + `-CC` client to attach (the live clientRef).
        compose.waitUntil(timeoutMillis = 45_000) {
            var attached = false
            launchedActivity?.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession != null && view.mEmulator != null
            }
            attached
        }
    }

    /**
     * Wait for the live VM to register the attached pane (so
     * [TmuxSessionViewModel.writeInputToPaneResult] has a real pane id to target),
     * then return it. `panes` is a StateFlow — a thread-safe read off the test
     * thread; do NOT wrap in [onMain] (that would run on Main and any nested
     * instrumentation call there throws "can not be called from the main thread").
     */
    private fun awaitAttachedPaneId(liveVm: TmuxSessionViewModel): String {
        compose.waitUntil(timeoutMillis = 30_000) {
            (liveVm.panes.value.firstOrNull()?.paneId ?: "").isNotBlank()
        }
        return liveVm.panes.value.firstOrNull()?.paneId
            ?: error("no attached pane id from the live TmuxSessionViewModel")
    }

    private fun liveTmuxViewModel(): TmuxSessionViewModel {
        val scenario = launchedActivity ?: error("activity not launched")
        var vm: TmuxSessionViewModel? = null
        scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return requireNotNull(vm) { "could not resolve the activity-scoped TmuxSessionViewModel" }
    }

    /** Run [block] synchronously on the activity's Main thread and return its value. */
    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        var captured = false
        compose.runOnUiThread {
            result = block()
            captured = true
        }
        check(captured) { "onMain block did not run" }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun onMainUnit(block: () -> Unit) {
        compose.runOnUiThread { block() }
    }

    // ------------------------------------------------------------- Fixtures

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private fun newComposerVm(store: OutboundQueueStore): PromptComposerViewModel {
        val vm = PromptComposerViewModel(
            audioRecorder = NoopMicCapture(),
            whisperClientFactory = WhisperClientFactory {
                object : WhisperClient {
                    override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> =
                        Result.success("")
                }
            },
            apiKeyStorage = NoopVault(),
            voiceSettings = NoopVoiceSettings(),
            outboundQueueStore = store,
            savedStateHandle = SavedStateHandle(),
        )
        createdComposerVms += vm
        return vm
    }

    private suspend fun seedDockerHost(key: String, hostName: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue1686-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = hostName,
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    /**
     * Seed `opencode-lab` with an INTERACTIVE shell (`sh -i`) so a `send-keys`
     * prompt is echoed into the pane and shows up in `capture-pane` — the marker
     * the drain assertion looks for. A `# <marker>` comment line echoes the token
     * without any command-not-found noise.
     */
    private suspend fun seedInteractiveSession(key: String, prompt: String? = null) {
        val shellCommand = if (prompt == null) {
            "printf 'ISSUE1686-READY\\n'; exec sh -i"
        } else {
            // Issue #2056: an explicit prompt glyph so the pane's input surface is
            // identifiable — that identification is exactly what the submit-turnover
            // oracle needs, and what it could not do before this fix.
            "printf 'ISSUE1686-READY\\n'; PS1='$prompt'; export PS1; exec sh -i"
        }
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_LAB)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_LAB)} " + shellQuote(shellCommand),
            )
            appendLine("sleep 1")
            appendLine("tmux list-sessions")
        }
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec(script) }
        }
        val exec = result.getOrNull()
        assertTrue(
            "expected interactive session seed to succeed; exception=${result.exceptionOrNull()} " +
                "stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
    }

    /** Issue #2056: the REAL tmux generation the durable row and its binding are stamped with. */
    private suspend fun readTmuxSessionIdentity(key: String): Pair<String, Long> {
        val script =
            "tmux display-message -p -t ${shellQuote(SESSION_LAB)} " +
                shellQuote("#{session_id} #{session_created}")
        val out = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(script) } }.getOrNull()?.stdout.orEmpty().trim()
        val parts = out.split(Regex("\\s+"))
        assertTrue("could not read the tmux session identity; got '$out'", parts.size >= 2)
        return parts[0] to (parts[1].toLongOrNull() ?: 0L)
    }

    private suspend fun capturePane(key: String): String {
        val script = "tmux capture-pane -p -t ${shellQuote(SESSION_LAB)} 2>/dev/null || true"
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec(script) }
        }
        return result.getOrNull()?.stdout.orEmpty()
    }

    private suspend fun waitForPaneContains(key: String, marker: String, label: String): String {
        var last = ""
        val deadline = SystemClock.elapsedRealtime() + PANE_DRAIN_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            last = capturePane(key)
            if (marker in last) {
                artifactFile("$label-visible-terminal.txt").writeText(last)
                return last
            }
            SystemClock.sleep(300)
        }
        artifactFile("failure-$label-visible-terminal.txt").writeText(last)
        assertTrue(
            "expected the queued prompt to DRAIN over the writable wire and appear in the real " +
                "tmux pane for $label; marker '$marker' never landed. Captured pane:\n$last",
            marker in last,
        )
        return last
    }

    private suspend fun cleanupSeededSession(key: String) {
        runCatching {
            withTimeout(20_000) {
                SshConnection.connect(
                    host = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    user = DEFAULT_USER,
                    key = SshKey.Pem(key),
                    knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
                    timeoutMs = 15_000,
                ).mapCatching { session ->
                    session.use {
                        it.exec("tmux kill-session -t ${shellQuote(SESSION_LAB)} 2>/dev/null || true")
                    }
                }
            }
        }
    }

    private fun awaitTrue(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + PANE_DRAIN_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(150)
        }
        assertTrue("timed out waiting for: $label", predicate())
    }

    // ------------------------------------------------------------ Artifacts

    /**
     * Issue #2056 reviewer feedback: the journey's viewport PNG caught whatever tab
     * happened to be mounted (a Conversation tab mid "Loading conversation…"), so it
     * neither corroborated nor contradicted the delivery claim. Select the real
     * Terminal tab first so the screenshot shows the delivered payload.
     */
    private fun selectTerminalTabForArtifacts(liveVm: TmuxSessionViewModel, paneId: String) {
        onMainUnit { liveVm.selectSessionTab(paneId, SessionTab.Terminal) }
        // The view-model flag alone does not repaint the chrome the screenshot shows —
        // the first attempt at this left the PNG on "Loading conversation…". Tap the
        // real Terminal pill and wait for the Conversation surface to actually leave
        // the tree before capturing.
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(TMUX_TERMINAL_TAB_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(TMUX_TERMINAL_TAB_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            liveVm.agentConversations.value[paneId]?.selectedTab == SessionTab.Terminal &&
                compose.onAllNodesWithTag(TMUX_CONVERSATION_PANE_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isEmpty()
        }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(250)
    }

    /** The text the APP itself is rendering, alongside the server-side capture. */
    private fun visibleTerminalText(): String {
        var text = ""
        launchedActivity?.onActivity { activity ->
            text = activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.screen
                ?.transcriptText
                .orEmpty()
        }
        return text
    }

    private fun writeArtifactText(name: String, text: String) {
        artifactFile(name).writeText(text)
    }

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = artifactFile("$name-viewport.png")
        try {
            java.io.FileOutputStream(file).use { output ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            }
            println("ISSUE1686_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeSummary(testName: String, lines: List<String>) {
        artifactFile("$testName-summary.txt").writeText(
            buildString {
                appendLine("test=$testName")
                appendLine("host=$DEFAULT_HOST port=$DEFAULT_PORT user=$DEFAULT_USER")
                appendLine("seeded_session=$SESSION_LAB")
                appendLine("details:")
                lines.forEach { appendLine("  $it") }
            },
        )
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create issue #1686 artifact directory ${dir.absolutePath}"
        }
        return File(dir, name)
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

    // ----------------------------------------------------------- VM fakes

    private class NoopMicCapture : PromptComposerViewModel.MicCapture {
        override fun start() = Unit
        override fun stop(): ByteArray = ByteArray(0)
        override fun currentAmplitude(): Float = 0f
    }

    private class NoopVault : PromptComposerViewModel.ApiKeyVault {
        private var key: CharArray? = "sk-test".toCharArray()
        override fun save(key: CharArray) { this.key = key.copyOf() }
        override fun load(): CharArray? = key?.copyOf()
        override fun clear() { key = null }
    }

    private class NoopVoiceSettings : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
        override fun whisperLanguageHint(): String? = null
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val DEVICE_DIR_NAME: String = "issue1686-queue-drain-wire-oracle"

        /**
         * The session name the `agents` Docker fixture's picker stub recognises;
         * `opencode-lab` is the canonical choice shared with the other tmux E2Es.
         */
        const val SESSION_LAB: String = "opencode-lab"

        const val PANE_DRAIN_TIMEOUT_MS: Long = 30_000L

        /** Issue #2056: a durable `tmux:` session key (see the drain-promotion guard). */
        const val ISSUE_2056_TARGET: String = "tmux:issue2056/delivered"
    }
}
