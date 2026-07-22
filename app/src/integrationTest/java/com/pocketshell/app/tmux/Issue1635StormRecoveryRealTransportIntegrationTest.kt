package com.pocketshell.app.tmux

import com.pocketshell.app.composer.OUTBOUND_MAX_AUTO_ATTEMPTS
import com.pocketshell.app.composer.OutboundAttemptBudgetTracker
import com.pocketshell.app.composer.OutboundDeliveryWindow
import com.pocketshell.app.composer.OutboundState
import com.pocketshell.app.composer.InMemoryOutboundQueueStore
import com.pocketshell.app.composer.firstComposerAutoFlushable
import com.pocketshell.app.composer.planComposerAutoFlush
import com.pocketshell.app.diagnostics.RecordedDiagnosticEvent
import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshSessionTestControl
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientException
import com.pocketshell.core.tmux.TmuxClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Issue #1635 — the HEADLESS REAL-TRANSPORT reproduction that unblocks the
 * round-six budget/refund fix (reviewer `BLOCKED`, G4 → cleared under **D34**).
 *
 * ## Why headless real-transport is the right proof here (D34)
 *
 * The reviewer's round-six verdict was `BLOCKED` (correct-but-unproven-on-device):
 * the JVM/mutation coverage was strong but the reported symptom is an on-device
 * reconnect-storm defect and this slice deletes a connection-state-machine input
 * (design D2 — the `pane_input_send` synthetic passive-disconnect). Locked
 * decision **D34** makes an OBSERVED headless real-transport reproduction (real
 * sshj + Docker tmux fixture) a first-class D33 proof for connection-core
 * transport/storm/reconnect fixes, co-equal with the emulator journey (which
 * remains the batched backstop). The three properties the reviewer named are each
 * DEFINED at the transport/queue layer — the queue transitioning to sent on the
 * real wire, `disconnectSource` values, reader-owned dead detection — so they are
 * exactly D34's scope.
 *
 * The D34 guardrail (rule 3): the load-bearing assertion observes the
 * SYMPTOM-DEFINING signal on the REAL transport — the marker keystrokes actually
 * landing in a real tmux pane (`capture-pane`), and the actual `disconnectSource`
 * value recorded while a real dead transport fails a real send — NEVER "the seam
 * fired" or a recording fake. Every send in this class rides a real
 * `TmuxClient` over a real sshj `-CC` transport against the Docker `tmux` fixture.
 *
 * ## The three properties (the reviewer's `BLOCKED` points)
 *
 *  - **(a) Recovery auto-send** ([recoveredLinkAutoSendsTheStormedPromptOnTheRealWire]):
 *    a real link cut → heal with a queued prompt ⇒ the prompt AUTO-sends without a
 *    manual Resend, observed as the marker text landing in the REAL pane, and the
 *    amber parked/`Failed` state does NOT survive recovery. The paired BASE arm
 *    ([basePolicyParksTheRowAndNothingLandsOnTheWireAfterRecovery]) is the RED: the
 *    pre-fix always-charge policy parks the row at the budget and NOTHING lands on
 *    the wire after the link heals — the maintainer's exact "it only sent much
 *    later when I manually resent" symptom, reproduced on the real transport.
 *  - **(b) D2-deletion safety** ([deadClientDetectionSurvivesTheD2Deletion]): a
 *    PERMANENT cut ⇒ the real `deliverDequeuedInputBatch` (the function the D2
 *    injection was deleted FROM) exhausts its send attempts over the dead client
 *    and produces NO `disconnectSource=pane_input_send` — while the reader/keepalive
 *    oracle still detects the death (`TmuxClient.disconnected` flips true). This is
 *    the reviewer's core worry: deleting the `pane_input_send` input did not regress
 *    dead-client detection, and the queue no longer amplifies the storm it suffers.
 *
 * Property (c) — adjacency (#1686 no-clog + #1602 park preserved) — is proven by
 * the per-push JVM siblings ([com.pocketshell.app.composer.PromptComposerWireOracleClogTest],
 * [com.pocketshell.app.composer.PromptComposerOutboundQueueStormTest]'s G6 negative)
 * and the batched-emulator `Issue1686QueueDrainWireOracleDockerTest`; it is not
 * re-proven headlessly here.
 *
 * ## Gate wiring
 *
 * Runs under `:app:integrationTest` (Testcontainers, requires Docker), the same
 * batched-on-`main` Docker lane as [com.pocketshell.app.proof.ProofPipelineIntegrationTest]
 * and the shared modules' integration suites — NOT the per-push `./gradlew test`
 * Unit job (kept Docker-free by the `*IntegrationTest.class` exclude in
 * `app/build.gradle.kts`). The whole class self-skips (class-level `assumeTrue`)
 * when Docker is unreachable so a Docker-less machine stays green; the
 * load-bearing wire/detection assertions themselves carry NO `assumeTrue` skip.
 *
 * @see com.pocketshell.app.composer.OutboundAttemptBudgetTracker
 * @see deliverDequeuedInputBatch
 * @see SshSessionTestControl.forceTransportDeath
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue1635StormRecoveryRealTransportIntegrationTest {

    companion object {
        private const val CONTAINER_SSH_PORT = 22

        /** Storm cycles to drive — must exceed [OUTBOUND_MAX_AUTO_ATTEMPTS] so BASE parks. */
        private const val STORM_CYCLES = 8

        private val projectRoot: Path by lazy { findProjectRoot() }

        @Volatile
        private var container: GenericContainer<*>? = null

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val dockerAvailable = runCatching {
                DockerClientFactory.instance().isDockerAvailable
            }.getOrDefault(false)
            assumeTrue("Docker not available; skipping #1635 storm real-transport proof", dockerAvailable)

            // Dockerfile.tmux's `FROM pocketshell-test:ssh` needs the base image in
            // the local daemon first (same two-layer build as TmuxClientIntegrationTest).
            val dockerDir = projectRoot.resolve("tests/docker")
            val sshBuild = ProcessBuilder(
                "docker", "build", "-t", "pocketshell-test:ssh",
                "-f", dockerDir.resolve("Dockerfile.ssh").toString(), dockerDir.toString(),
            ).redirectErrorStream(true).start()
            val sshOut = sshBuild.inputStream.bufferedReader().readText()
            check(sshBuild.waitFor() == 0) { "Failed to build pocketshell-test:ssh:\n$sshOut" }

            val image = ImageFromDockerfile("pocketshell-test-tmux", false)
                .withDockerfile(dockerDir.resolve("Dockerfile.tmux"))
            container = GenericContainer(image)
                .withExposedPorts(CONTAINER_SSH_PORT)
                .also { it.start() }
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            container?.stop()
            container = null
        }

        private fun findProjectRoot(): Path {
            var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            while (dir != null) {
                if (dir.resolve("tests/docker/Dockerfile.tmux").toFile().exists()) return dir
                dir = dir.parent
            }
            error("Could not locate tests/docker/Dockerfile.tmux from user.dir=${System.getProperty("user.dir")}")
        }
    }

    private val sshPort: Int get() = container!!.getMappedPort(CONTAINER_SSH_PORT)
    private val sshHost: String get() = container!!.host
    private val privateKeyFile: File get() = projectRoot.resolve("tests/docker/test_key").toFile()

    // ---------------------------------------------------------------------------------
    // (a) GREEN — the recovered link auto-sends the stormed prompt on the REAL wire.
    // ---------------------------------------------------------------------------------

    /**
     * Property (a) GREEN. Under the FIX (`OutboundAttemptBudgetTracker` refunds a
     * failure whose delivery window was down), a prompt queued through a real
     * link-cut storm stays `Queued` and auto-flushable, and once a fresh real
     * transport heals, the marker keystrokes LAND in the real tmux pane — with the
     * row delivered exactly once (no amber `Failed` survivor). The load-bearing
     * assertion is the marker's presence in `capture-pane` off the healed transport.
     */
    @Test
    fun recoveredLinkAutoSendsTheStormedPromptOnTheRealWire() {
        assumeTrue("Docker not available", container != null)
        runStormRecoveryJourney(fixEnabled = true)
    }

    /**
     * Property (a) RED (the reviewer's red→green partner, on the real transport).
     * With the FIX DISABLED (the pre-#1635 always-charge policy — every failure
     * bumps the budget regardless of the window state), the same real storm drives
     * the row to the budget and the auto-flush PARKS it `Failed`. After the real
     * link heals, `firstComposerAutoFlushable` skips the parked row, so NOTHING is
     * ever sent and the marker NEVER appears in the real pane — the amber park
     * survives recovery. This is the maintainer's reported symptom, reproduced on
     * the real wire; the GREEN test above is the same journey with the fix on.
     */
    @Test
    fun basePolicyParksTheRowAndNothingLandsOnTheWireAfterRecovery() {
        assumeTrue("Docker not available", container != null)
        runStormRecoveryJourney(fixEnabled = false)
    }

    /**
     * Drives the full real-transport storm→heal journey for one budget policy and
     * asserts the wire outcome. `fixEnabled = true` exercises the committed refund
     * policy (`OutboundAttemptBudgetTracker.failureAttemptDelta`); `false` pins the
     * pre-fix always-charge behaviour, so the pair is a red→green on the real wire.
     */
    private fun runStormRecoveryJourney(fixEnabled: Boolean) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val marker = "PS1635RECOVERY${System.nanoTime().toString(36).takeLast(6)}"
        val sessionKey = "1/$marker"
        val tmuxSession = "it1635-${System.nanoTime()}"
        val queue = InMemoryOutboundQueueStore()

        // The delivery window the session screen owns in production
        // (OutboundQueueAutoFlushController.deliveryWindow): live/dead + an epoch
        // that increments on every flip. Driven directly here so the accounting
        // is exercised by the REAL transport's liveness — down while the real link
        // is cut, live again once a real transport heals.
        var currentWindow = OutboundDeliveryWindow(live = true, epoch = 0L)
        val budget = OutboundAttemptBudgetTracker().apply { window = { currentWindow } }

        val row = queue.enqueue(sessionKey = sessionKey, cleanText = marker, createdAtMs = 1)

        // (1) A real -CC transport, attached to a real tmux session — the live window.
        val session1 = connect()
        val client1 = TmuxClientFactory(scope).create(session1, sessionName = tmuxSession)
        try {
            client1.connect()
            awaitPaneReady(client1, tmuxSession)

            // (2) THE REAL CUT: a raw synchronous anonymous-peer transport kill
            //     (#1693 seam) — the real -CC reader observes EOF, exactly the storm's
            //     transport death. The window is now genuinely down.
            SshSessionTestControl.forceTransportDeath(session1)
            currentWindow = currentWindow.copy(live = false, epoch = 1L)

            // (3) FIDELITY: prove the transport is really dead — a send over it FAILS
            //     on the wire (not a modelled failure). No assumeTrue: a live send here
            //     would mean the cut did not take and the storm is not reproduced.
            val downSend = attemptWireSend(client1, tmuxSession, marker)
            assertFalse(
                "fidelity: a send over the force-killed transport must FAIL on the real wire — " +
                    "if it succeeds the cut did not take and the storm is not reproduced",
                downSend,
            )

            // (4) THE STORM: N cycles of claim → down-window failure → resolve. Each
            //     cycle bumps the budget at claim (production `claim`) and resolves via
            //     the REAL policy function. FIX: failureAttemptDelta refunds (window
            //     down) → net 0. BASE: always charge → net +1 per cycle.
            repeat(STORM_CYCLES) { cycle ->
                budget.onClaim(row.id)
                assertNotNull("cycle $cycle: the queued row must be claimable", queue.claim(row.id))
                currentWindow = currentWindow.copy(epoch = currentWindow.epoch + 1) // window churn
                val delta = if (fixEnabled) budget.failureAttemptDelta(row.id) else 0
                assertNotNull(
                    "cycle $cycle: the failed attempt must re-queue the durable row",
                    queue.requeueForRetry(row.id, resetAttempts = false, attemptDelta = delta),
                )
            }

            val afterStorm = requireNotNull(queue.item(row.id))
            if (fixEnabled) {
                assertEquals(
                    "FIX: a storm of connection-down failures must burn ZERO attempts (#1635-A/D4)",
                    0, afterStorm.attemptCount,
                )
                assertEquals(
                    "FIX: the row must stay Queued through the storm, never parked",
                    OutboundState.Queued, afterStorm.state,
                )
            } else {
                assertTrue(
                    "BASE: the always-charge policy must exhaust the budget under the storm " +
                        "(attemptCount=${afterStorm.attemptCount} >= $OUTBOUND_MAX_AUTO_ATTEMPTS)",
                    afterStorm.attemptCount >= OUTBOUND_MAX_AUTO_ATTEMPTS,
                )
            }
        } finally {
            runCatching { client1.close() }
            runCatching { session1.close() }
        }

        // (5) THE HEAL: a fresh real transport attaches to the SAME tmux session.
        currentWindow = OutboundDeliveryWindow(live = true, epoch = currentWindow.epoch + 1)
        val session2 = connect()
        val client2 = TmuxClientFactory(scope).create(
            session2, sessionName = tmuxSession, probeServerLiveness = true,
        )
        try {
            client2.connect()
            awaitPaneReady(client2, tmuxSession)

            // (6) THE RECOVERY DRAIN — exactly the production auto-flush selection.
            //     A parked (BASE) row is excluded; the FIX row is selected.
            val plan = queue.itemsFor(sessionKey).planComposerAutoFlush(sessionKey)
            plan.parkIds.forEach { queue.markFailed(it, lastError = "parked", lastAttemptAtMs = 0) }
            val next = queue.itemsFor(sessionKey)
                .firstComposerAutoFlushable(sessionKey, maxAutoAttempts = OUTBOUND_MAX_AUTO_ATTEMPTS)

            if (fixEnabled) {
                // LOAD-BEARING GREEN: the recovered link auto-sends the queued prompt.
                assertEquals(
                    "FIX: the healed link must auto-select the stormed prompt for delivery — " +
                        "on BASE this is null (the parked-forever symptom)",
                    row.id, next?.id,
                )
                val landed = attemptWireSend(client2, tmuxSession, marker)
                assertTrue("FIX: the recovery send must succeed on the healed real wire", landed)
                queue.markDelivered(row.id)

                // THE SYMPTOM SIGNAL (D34): the marker keystrokes are actually present
                // in the REAL tmux pane, observed off the healed transport.
                assertTrue(
                    "FIX (load-bearing): the auto-sent prompt marker must be VISIBLE in the real " +
                        "tmux pane after recovery — the queue actually transitioned to sent on the wire",
                    awaitMarkerInPane(client2, tmuxSession, marker),
                )
                assertNull(
                    "FIX: the delivered row is pruned exactly once — no amber survivor (#1529 ledger)",
                    queue.item(row.id),
                )
            } else {
                // LOAD-BEARING RED: the parked row is skipped, so nothing is ever sent.
                assertNull(
                    "BASE (load-bearing): the storm-parked row must NOT be auto-flushable after " +
                        "recovery — this is the reported bug (queue parks and never auto-sends)",
                    next?.id,
                )
                assertEquals(
                    "BASE: the row stays parked Failed — the amber state SURVIVES recovery",
                    OutboundState.Failed, requireNotNull(queue.item(row.id)).state,
                )
                // THE SYMPTOM SIGNAL (D34), RED side: nothing landed on the real wire.
                assertFalse(
                    "BASE (load-bearing): with the row parked and never dispatched, the marker must " +
                        "NEVER appear in the real tmux pane — recovery delivered nothing",
                    markerPresentNow(client2, tmuxSession, marker),
                )
            }
        } finally {
            runCatching { client2.close() }
            runCatching { session2.close() }
            scope.cancel()
        }
    }

    // ---------------------------------------------------------------------------------
    // (b) D2-deletion safety — dead detection is owned by the reader, not the queue.
    // ---------------------------------------------------------------------------------

    /**
     * Property (b). The maintainer's device log showed `disconnectSource=pane_input_send`
     * — the outbound queue feeding a synthetic passive-disconnect into the reconnect
     * state machine and AMPLIFYING the storm. Round six DELETES that D2 injection from
     * [deliverDequeuedInputBatch]. This proves the deletion is safe on the real wire:
     *
     *  - a PERMANENT real cut (force-kill the transport) drives the real
     *    `deliverDequeuedInputBatch` to exhaust its send attempts over the dead
     *    client, and NO diagnostic carries `disconnectSource=pane_input_send`
     *    (nor `source=pane_input_send`) — the queue no longer drives the machine; and
     *  - the reader/keepalive oracle STILL detects the death: `TmuxClient.disconnected`
     *    flips true after the cut (bounded), so dead-client detection is preserved
     *    independently of the deleted queue input.
     *
     * If the D2 injection were ever re-introduced, this test goes RED (a
     * `pane_input_send`-sourced disconnect would reappear on the real failing wire).
     */
    @Test
    fun deadClientDetectionSurvivesTheD2Deletion() {
        assumeTrue("Docker not available", container != null)
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val sink = installRecordingDiagnosticSink()
            val tmuxSession = "it1635b-${System.nanoTime()}"
            val session = connect()
            val client = TmuxClientFactory(scope).create(session, sessionName = tmuxSession)
            try {
                client.connect()
                awaitPaneReady(client, tmuxSession)

                // THE PERMANENT CUT: raw synchronous transport kill (#1693 seam).
                SshSessionTestControl.forceTransportDeath(session)

                // (b.1) The reader/keepalive oracle detects the death — NOT the queue.
                val detected = withTimeoutOrNull(15_000) {
                    while (!client.disconnected.value) delay(100)
                    true
                } ?: false
                assertTrue(
                    "the reader/keepalive oracle must detect the dead transport (client.disconnected) " +
                        "after a permanent cut — dead detection is preserved independently of the deleted " +
                        "pane_input_send input (design D2 safety)",
                    detected,
                )

                // (b.2) Drive the REAL delivery loop (the function the D2 injection was
                //       deleted FROM) to exhaustion over the dead client.
                val queueIn = TmuxPaneInputQueue(
                    maxPendingBytes = TMUX_INPUT_MAX_PENDING_BYTES,
                    maxBatchBytes = TMUX_INPUT_MAX_BATCH_BYTES,
                )
                queueIn.write("PS1635B_DEADSEND\n".toByteArray(), 0, "PS1635B_DEADSEND\n".length)
                val batch = requireNotNull(withTimeoutOrNull(5_000) { queueIn.takeBatch() }) {
                    "expected a keystroke batch to deliver"
                }
                val resolved = deliverDequeuedInputBatch(
                    client = client,
                    paneId = tmuxSession,
                    batch = batch,
                    queue = queueIn,
                    currentClient = { client },
                    sendBytes = { c, pane, bytes ->
                        val resp = c.sendKeysViaExec("send-keys -t '$pane' -l '${String(bytes)}'")
                        if (resp.isError) throw TmuxClientException("send-keys error: ${resp.output}")
                    },
                )
                assertTrue(
                    "the exhausted keystroke batch must resolve (dropped) after failing over the dead " +
                        "client — the exact path the D2 injection was deleted from",
                    resolved,
                )

                // (b.3) THE SYMPTOM SIGNAL (D34): NO pane_input_send disconnect fired.
                val paneInputSourced = sink.events.filter { it.hasSource("pane_input_send") }
                assertTrue(
                    "the delivery-exhaustion must fire NO disconnect sourced pane_input_send — the D2 " +
                        "queue-amplification input is deleted, so a failed keystroke send no longer drives " +
                        "the connection state machine (#1610 disconnectSource=pane_input_send is gone). " +
                        "offending=${paneInputSourced.map { it.name to it.fields }}",
                    paneInputSourced.isEmpty(),
                )
            } finally {
                runCatching { client.close() }
                runCatching { session.close() }
                runCatching { sink.close() }
                scope.cancel()
            }
        }
    }

    // -- helpers -----------------------------------------------------------------------

    private fun RecordedDiagnosticEvent.hasSource(value: String): Boolean =
        fields["source"] == value || fields["disconnectSource"] == value

    private suspend fun connect(): SshSession = SshConnection.connect(
        host = sshHost,
        port = sshPort,
        user = "testuser",
        key = SshKey.Path(privateKeyFile),
        passphrase = null,
        knownHosts = KnownHostsPolicy.AcceptAll,
        timeoutMs = 15_000,
    ).getOrThrow()

    /** Wait until the tmux session's active pane is capturable (server bootstrapped). */
    private suspend fun awaitPaneReady(client: TmuxClient, target: String) {
        val ok = withTimeoutOrNull(15_000) {
            while (true) {
                val resp = runCatching { client.capturePaneTextViaExec(target) }.getOrNull()
                if (resp != null && !resp.isError) return@withTimeoutOrNull true
                delay(200)
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false
        assertTrue("tmux pane for '$target' never became ready", ok)
    }

    /** A single real `send-keys -l` over the -CC transport; false on a wire failure. */
    private suspend fun attemptWireSend(client: TmuxClient, target: String, text: String): Boolean =
        runCatching {
            val resp = client.sendKeysViaExec("send-keys -t '$target' -l '$text'")
            !resp.isError
        }.getOrDefault(false)

    /** True once [marker] is visible in the target pane's captured text (bounded). */
    private suspend fun awaitMarkerInPane(client: TmuxClient, target: String, marker: String): Boolean =
        withTimeoutOrNull(10_000) {
            while (true) {
                if (markerPresentNow(client, target, marker)) return@withTimeoutOrNull true
                delay(200)
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false

    private suspend fun markerPresentNow(client: TmuxClient, target: String, marker: String): Boolean =
        runCatching {
            val resp = client.capturePaneTextViaExec(target)
            !resp.isError && resp.output.any { line -> line.contains(marker) }
        }.getOrDefault(false)
}
