package com.pocketshell.app.tmux

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.composer.ComposerSendResult
import com.pocketshell.app.composer.OutboundItem
import com.pocketshell.app.composer.OutboundRoute
import com.pocketshell.app.composer.SharedPrefsOutboundQueueStore
import com.pocketshell.app.composer.outboundQueueSummary
import com.pocketshell.app.settings.AppSettings
import com.pocketshell.app.settings.OutboundDeliveryAuthority
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.tmux.CommandResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #2124 (step 1b of epic #2121) — the host-CLI acknowledgement is the SOLE
 * delivery authority, and the old inference stack goes inert.
 *
 * ## The load-bearing reproduction (D33 / G10)
 *
 * [workingAgentSendReachesDeliveredOnTheAcknowledgedPath] is the photographed
 * #1602 symptom: a prompt sent while the agent is **Working**. That frame is
 * undecidable *by design* on the old oracle — the repo's own fixture says so
 * verbatim
 * ([Issue2056RouteCompleteSubmitAckTest.workingAgentBoxWithNoMarkerGlyphFailsClosedOnBothFrames]:
 * "the agent consumed the submit, but its working box still says nothing the
 * oracle can read") — so the bounded turnover proof times out, the row becomes
 * `Queued + wireOutcomeUnknown`, the auto-flush skips it forever and Retry is a
 * provable no-op. That is `2 queued · 2 unconfirmed` on a green session whose
 * terminal is visibly rendering the prompt.
 *
 * [legacyPathIsStillUndecidableOnTheSameFrame] pins that base behaviour on the
 * SAME fixture through the SAME production entry point, so the two tests are a
 * differential proof: identical scenario, only the authority differs.
 *
 * ## Why the probe assertions matter
 *
 * The flag is a HARD switch, not a fallback. [OutboundLegacyStackProbe] counts
 * every legacy entry point (verify-before-resend, paste-ack, turnover proof,
 * late-ack reconciler, ledger wire attempt, submit write-ahead); the acknowledged
 * arm asserts ZERO and the legacy arm asserts NON-ZERO, so the probe cannot pass
 * vacuously by being wired to nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue2124HostAckDeliveryTest : TmuxSessionViewModelTestBase() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun resetProbe() {
        OutboundLegacyStackProbe.reset()
        HostAckSendProbe.reset()
    }

    private companion object {
        /**
         * The bracketed-paste markers, spelled with an EXPLICIT escape. These were
         * literal (invisible) ESC bytes in the source, which reads as a plain
         * `"[200~"` in every diff and review tool — an assertion nobody can check
         * by eye is one nobody can trust.
         */
        const val PASTE_START: String = "\u001B[200~"
        const val PASTE_END: String = "\u001B[201~"
    }

    /**
     * The exact "Working" frame the old oracle documents as undecidable: a framed
     * agent box with no marker glyph, mid-turn.
     */
    private fun workingFrame(input: String): CommandResponse = CommandResponse(
        number = 0L,
        output = listOf(
            "FAKE-AGENT-READY",
            "✻ Working… (esc to interrupt)",
            if (input.isEmpty()) "┃" else "┃ $input",
        ),
        isError = false,
    )

    /**
     * A stand-in for the HOST, not for the code under test: it models
     * `pocketshell send`'s two-phase durable token journal (the one thing a JVM
     * test cannot reach) and answers with the CLI's documented exit codes. Every
     * assertion below is on the CLIENT's behaviour given those answers.
     *
     * The two phases are what make the AC4 cuts distinguishable, so they are
     * modelled rather than collapsed (`send.py`'s module docstring is the
     * contract):
     *
     *  - a **claim** (`pending` record carrying the writing process's identity)
     *    is written before the one exec that can put bytes in the pane;
     *  - the record is promoted to **delivered** once tmux answered.
     *
     * Therefore a second call for the same token reads one of three facts, and
     * they are three different exits: already `delivered` ⇒ exit 0
     * `already-delivered`; claimed with a **live** owner ⇒ exit 8
     * `send-in-progress` (the double-tap); claimed with a **dead** owner ⇒ exit 5
     * `send-interrupted` (a genuinely unknown previous attempt). This client
     * never passes `--resend-interrupted`, so none of them can inject twice.
     */
    private class FakeHostCli(
        private val answer: (call: Int, token: String) -> ExecResult? = { _, _ ->
            ExecResult(stdout = "delivered\n", stderr = "", exitCode = 0)
        },
        /**
         * Runs INSIDE the host call, after the claim and before the answer — the
         * exact window a reconnect / session switch / app kill opens. Suspending,
         * so a test can hold a call in flight while a sibling arrives.
         */
        private val duringCall: suspend (call: Int, token: String) -> Unit = { _, _ -> },
        /**
         * When true for a call, the HOST process itself dies after putting the
         * payload in the pane: the payload is injected, the claim is left
         * unresolved with a now-dead owner, and no answer reaches the client.
         */
        private val hostProcessDies: (call: Int, token: String) -> Boolean = { _, _ -> false },
    ) : HostAckSendExec {
        /** Tokens the host has RESOLVED as delivered — the SERVER-side ledger. */
        val journal: MutableSet<String> = linkedSetOf()

        /** Tokens with a claim written but no verdict yet. */
        val unresolved: MutableSet<String> = linkedSetOf()

        /** Claims whose writing process is still alive. */
        private val liveOwners: MutableSet<String> = linkedSetOf()

        /** Payload occurrences physically injected into the pane. */
        val injections: MutableList<String> = mutableListOf()

        val commands: MutableList<String> = mutableListOf()

        override suspend fun run(command: String, timeoutMs: Long): ExecResult? {
            val call = commands.size + 1
            commands += command
            val token = TOKEN.find(command)?.groupValues?.get(1).orEmpty()
            val payload = PAYLOAD.find(command)?.groupValues?.get(1).orEmpty()
            if (token in journal) {
                // Idempotency: the token is already journaled, so nothing is
                // injected and the call still acknowledges (exit 0).
                return ExecResult(stdout = "already-delivered 1\n", stderr = "", exitCode = 0)
            }
            if (token in liveOwners) {
                return ExecResult(
                    stdout = "send-in-progress\n",
                    stderr = "a live sibling owns this token",
                    exitCode = HOST_ACK_EXIT_SEND_IN_PROGRESS,
                )
            }
            if (token in unresolved) {
                return ExecResult(
                    stdout = "send-interrupted\n",
                    stderr = "a previous attempt died without an answer",
                    exitCode = HOST_ACK_EXIT_SEND_INTERRUPTED,
                )
            }
            unresolved += token
            liveOwners += token
            try {
                duringCall(call, token)
                if (hostProcessDies(call, token)) {
                    // Injected, then killed: the claim survives with a dead owner.
                    injections += payload
                    return null
                }
                val scripted = answer(call, token)
                if (scripted == null || scripted.exitCode == 0) {
                    // Modelled exactly like the real CLI: inject, THEN promote. A
                    // null answer means the reply was lost AFTER the host acted —
                    // the ambiguous cut a mid-send flap produces.
                    injections += payload
                    journal += token
                    unresolved -= token
                } else if (scripted.exitCode in CLAIM_ROLLED_BACK_EXITS) {
                    // Definitive failure: nothing reached the pane and no record
                    // survives (#2136 — bad usage and a missing CLI never get as
                    // far as a claim; `pane-not-found`/`tmux-failed`/
                    // `journal-failed` roll theirs back), so the token is cleanly
                    // retryable. The genuinely ambiguous exits (5, 6) keep the
                    // claim, which is what makes a later plain retry read exit 5.
                    unresolved -= token
                }
                return scripted
            } finally {
                liveOwners -= token
            }
        }

        private companion object {
            val TOKEN = Regex("--token '([^']*)'")
            val PAYLOAD = Regex("printf %s '((?:[^']|'\\\\'')*)'")

            /** Exits after which no unresolved record survives this call. */
            val CLAIM_ROLLED_BACK_EXITS: Set<Int> = setOf(
                HOST_ACK_EXIT_BAD_USAGE,
                HOST_ACK_EXIT_PANE_NOT_FOUND,
                HOST_ACK_EXIT_TMUX_FAILED,
                HOST_ACK_EXIT_JOURNAL_FAILED,
                HOST_ACK_EXIT_CLI_ABSENT,
            )
        }
    }

    private fun newRow(
        store: SharedPrefsOutboundQueueStore,
        session: String,
        text: String,
    ): OutboundItem = store.enqueue(
        sessionKey = session,
        cleanText = text,
        paneId = "%0",
        route = OutboundRoute.AgentPayload,
        agentKind = "claude",
        sendKey = "sk-$text",
        tmuxSessionId = "\$9",
        tmuxSessionCreated = 1900L,
    )

    /**
     * THE LOAD-BEARING TEST (issue acceptance criteria 1 + 2 + 6).
     *
     * Send while the agent is Working — the state the old oracle documents as
     * undecidable — and the row still reaches Delivered, decided by the host
     * CLI's ack alone: no `capture-pane` needle read, no turnover wait, no
     * late-ack poll took part in the decision.
     */
    @Test
    fun workingAgentSendReachesDeliveredOnTheAcknowledgedPath() = runTest(scheduler) {
        val payload = "restart the worker pool"
        val store = SharedPrefsOutboundQueueStore(context)
        val row = newRow(store, "sessAck", payload)
        val client = FakeTmuxClient()
        client.defaultCaptureResponse = workingFrame(payload)
        val host = FakeHostCli()
        val vm = newVm(applicationContext = context, outboundQueueStore = store)
        vm.attachClientForTest(client)
        vm.hostAck.authorityOverrideForTest = OutboundDeliveryAuthority.HostCliAck
        vm.hostAck.execOverrideForTest = host

        val sent = async {
            vm.sendAgentPayloadToPaneResult(
                "%0",
                payload,
                AgentKind.ClaudeCode,
                sendToken = row.id,
                durableRow = DurableOutboundRowIdentity("sessAck", row.id),
            )
        }
        advanceUntilIdle()

        assertEquals(
            "a send to a WORKING agent must reach Delivered — the host acknowledged it",
            ComposerSendResult.Delivered,
            sent.await().toComposerSendResult(),
        )
        assertTrue("the durable row must be prunable", store.markDelivered(row.id))
        assertNull("a delivered row is pruned", store.item(row.id))

        // The decision used the ack and nothing else.
        assertEquals(
            "the acknowledged path must not consult the legacy stack at all: " +
                OutboundLegacyStackProbe.snapshot(),
            0L,
            OutboundLegacyStackProbe.total(),
        )
        assertTrue(
            "no capture-pane needle read may take part in the decision",
            client.capturePaneTextViaExecCalls.isEmpty(),
        )
        assertTrue(
            "the payload and its Enter travel through the host CLI, not send-keys",
            client.sentCommands.none { it.startsWith("send-keys") || it.startsWith("set-buffer") },
        )
        assertEquals(1, host.commands.size)
        assertTrue(
            "the exec must carry --pane, --token and --enter: ${host.commands.first()}",
            host.commands.first().let {
                it.contains("--pane '%0'") && it.contains("--token '${row.id}'") &&
                    it.contains("--enter")
            },
        )
    }

    /**
     * The differential arm: the SAME Working frame through the SAME production
     * entry point on the LEGACY authority still cannot decide — it returns
     * `AuthoritativeAckPending`, which is the ONLY producer of the
     * `wireOutcomeUnknown` "unconfirmed" row. This is the base symptom, pinned.
     *
     * It also makes [OutboundLegacyStackProbe] non-vacuous: the counters it
     * asserts to be ZERO above are NON-ZERO here, so they are genuinely wired to
     * the stack they claim to watch.
     */
    @Test
    fun legacyPathIsStillUndecidableOnTheSameFrame() = runTest(scheduler) {
        val payload = "restart the worker pool"
        val store = SharedPrefsOutboundQueueStore(context)
        val row = newRow(store, "sessLegacy", payload)
        val client = FakeTmuxClient()
        client.defaultCaptureResponse = workingFrame(payload)
        val vm = newVm(applicationContext = context, outboundQueueStore = store)
        vm.attachClientForTest(client)
        vm.hostAck.authorityOverrideForTest = OutboundDeliveryAuthority.TerminalInference
        vm.setAgentSubmitEnterDelayForTest(0)
        vm.setAgentSubmitAckTimeoutForTest(50)

        val sent = async {
            vm.sendAgentPayloadToPaneResult(
                "%0",
                payload,
                AgentKind.ClaudeCode,
                sendToken = row.id,
                durableRow = DurableOutboundRowIdentity("sessLegacy", row.id),
            )
        }
        advanceUntilIdle()

        assertEquals(
            "the legacy oracle cannot decide a Working frame — this is the reported symptom",
            ComposerSendResult.AuthoritativeAckPending,
            sent.await().toComposerSendResult(),
        )
        assertTrue(
            "the legacy arm must actually traverse the legacy stack, otherwise the " +
                "zero-call assertion above proves nothing: " + OutboundLegacyStackProbe.snapshot(),
            OutboundLegacyStackProbe.total() > 0L,
        )
        assertTrue(
            "the legacy arm reads the pane to guess",
            client.capturePaneTextViaExecCalls.isNotEmpty(),
        )
        assertNotNull("and leaves the row behind", store.item(row.id))
    }

    /**
     * Acceptance criterion 3: retry after **any** failure produces a REAL delivery
     * attempt — a fresh host round trip that returns a real answer, never the
     * ledger-gated no-op the old stack produced — and the token keeps the pane to
     * at most ONE occurrence, whatever the first answer was.
     *
     * Every documented failure class is exercised, not one convenient case (G2):
     * the pre-claim rejections (bad usage, absent CLI), the definitive rollbacks
     * (`pane-not-found`, `tmux-failed`, `journal-failed`), and the genuinely
     * ambiguous ones that keep the claim (`send-interrupted`, `timeout`, and a
     * lost answer).
     */
    @Test
    fun retryAfterAnyFailureIsARealAttemptAndNeverDuplicates() = runTest(scheduler) {
        val failures: List<Pair<String, ExecResult?>> = listOf(
            "bad-usage" to ExecResult("bad-usage\n", "--pane is required", 2),
            "old-cli" to ExecResult("", "Error: No such command 'send'.", 2),
            "cli-absent" to ExecResult("", "", 127),
            "pane-not-found" to ExecResult("pane-not-found\n", "no such pane", 3),
            "tmux-failed" to ExecResult("tmux-failed\n", "tmux is missing", 4),
            "send-interrupted" to ExecResult("send-interrupted\n", "partial delivery", 5),
            "timeout" to ExecResult("timeout\n", "exceeded --timeout", 6),
            "journal-failed" to ExecResult("journal-failed\n", "permission denied", 7),
            "no-answer" to null,
        )
        failures.forEach { (name, firstAnswer) ->
            val payload = "retry after $name"
            val store = SharedPrefsOutboundQueueStore(context)
            val row = newRow(store, "sess-retry-$name", payload)
            val host = FakeHostCli(
                answer = { call, _ ->
                    if (call == 1) firstAnswer else ExecResult("delivered\n", "", 0)
                },
            )
            val vm = newVm(applicationContext = context, outboundQueueStore = store)
            vm.attachClientForTest(FakeTmuxClient())
            vm.hostAck.authorityOverrideForTest = OutboundDeliveryAuthority.HostCliAck
            vm.hostAck.execOverrideForTest = host
            val identity = DurableOutboundRowIdentity("sess-retry-$name", row.id)

            val first = async {
                vm.sendAgentPayloadToPaneResult(
                    "%0", payload, AgentKind.ClaudeCode, sendToken = row.id, durableRow = identity,
                )
            }
            advanceUntilIdle()
            assertEquals(
                "$name must be a plain retryable Failed",
                ComposerSendResult.Failed,
                first.await().toComposerSendResult(),
            )

            val retry = async {
                vm.sendAgentPayloadToPaneResult(
                    "%0", payload, AgentKind.ClaudeCode, sendToken = row.id, durableRow = identity,
                )
            }
            advanceUntilIdle()
            val retryResult = retry.await().toComposerSendResult()

            assertEquals(
                "$name: the retry must be a REAL host round trip, not a ledger-gated no-op",
                2,
                host.commands.size,
            )
            assertNotEquals(
                "$name: a retry may never produce the absorbing 'unconfirmed' state",
                ComposerSendResult.AuthoritativeAckPending,
                retryResult,
            )
            assertTrue(
                "$name: the token must keep the pane to at most one occurrence, got " +
                    "${host.injections}",
                host.injections.size <= 1 && host.injections.distinct() == host.injections,
            )
            assertFalse(
                "$name: no retry may mark the row unconfirmed",
                store.item(row.id)?.wireOutcomeUnknown ?: false,
            )
            assertEquals(
                "$name: no legacy entry point may run on the acknowledged path",
                0L,
                OutboundLegacyStackProbe.total(),
            )
        }
    }

    // --------------------------------------------------------------------
    // Acceptance criterion 4 — "no duplicate payload or Enter under: reconnect
    // mid-send, switch-away/back, app kill between wire-write and ack-read,
    // double-tap Retry".
    //
    // Four DIFFERENT states, each built for real, one test each. Round one had
    // one scenario ("the answer was lost") under four labels, which is not the
    // criterion: deleting three labels changed nothing, so three quarters of the
    // assertion was decorative. Each test below names the mutation that reddens
    // it, and creates the state its name claims — a reconnect that really swaps
    // the transport, a session switch that really moves the target, a process
    // boundary that really discards the ViewModel, and two genuinely CONCURRENT
    // dispatches of one row.
    // --------------------------------------------------------------------

    /**
     * Cut 1 — **reconnect mid-send**. The transport is replaced with a FRESH
     * client while the host call is in flight (a recovered connection is a
     * different client instance — `currentClientIdentityForTest`), and the answer
     * never crosses the dead link.
     *
     * Mutation that must redden it: make the host inject on the retry instead of
     * reporting `already-delivered`, and `injections` becomes two.
     */
    @Test
    fun reconnectMidSendDoesNotDuplicateThePayloadOrTheEnter() = runTest(scheduler) {
        val payload = "deploy after reconnect"
        val store = SharedPrefsOutboundQueueStore(context)
        val row = newRow(store, "sessReconnect", payload)
        val client = FakeTmuxClient()
        client.defaultCaptureResponse = workingFrame(payload)
        val vm = newVm(applicationContext = context, outboundQueueStore = store)
        vm.attachClientForTest(client)
        vm.hostAck.authorityOverrideForTest = OutboundDeliveryAuthority.HostCliAck
        val identityBefore = vm.currentClientIdentityForTest()
        var identityDuringCut: Int? = null
        val host = FakeHostCli(
            answer = { call, _ ->
                if (call == 1) null else ExecResult("delivered\n", "", 0)
            },
            duringCall = { call, _ ->
                if (call == 1) {
                    // The reconnect LANDS mid-send: a brand-new transport takes
                    // over while the exec is outstanding.
                    vm.attachClientForTest(FakeTmuxClient())
                    identityDuringCut = vm.currentClientIdentityForTest()
                }
            },
        )
        vm.hostAck.execOverrideForTest = host

        val first = async {
            vm.sendAgentPayloadToPaneResult(
                "%0",
                payload,
                AgentKind.ClaudeCode,
                sendToken = row.id,
                durableRow = DurableOutboundRowIdentity("sessReconnect", row.id),
            )
        }
        advanceUntilIdle()

        assertNotNull("precondition: a client was attached", identityBefore)
        assertNotEquals(
            "the cut must really have happened: the reconnect must swap the client " +
                "instance, otherwise this test is not about a reconnect",
            identityBefore,
            identityDuringCut,
        )
        assertEquals(
            "a lost answer across the dropped link is an honest Failed, never an unknown",
            ComposerSendResult.Failed,
            first.await().toComposerSendResult(),
        )

        val retry = async {
            vm.sendAgentPayloadToPaneResult(
                "%0",
                payload,
                AgentKind.ClaudeCode,
                sendToken = row.id,
                durableRow = DurableOutboundRowIdentity("sessReconnect", row.id),
            )
        }
        advanceUntilIdle()

        assertEquals("the retry must be a REAL attempt on the new transport", 2, host.commands.size)
        assertEquals(
            "the retry resolves the row on the reconnected transport",
            ComposerSendResult.Delivered,
            retry.await().toComposerSendResult(),
        )
        assertEquals("exactly one occurrence in the pane", listOf(payload), host.injections)
        assertEquals(
            "exactly one Enter — a second injection would carry a second submit",
            1,
            host.commands.count { it.contains("--enter") } - 1,
        )
        assertFalse(requireNotNull(store.item(row.id)).wireOutcomeUnknown)
        assertTrue(store.markDelivered(row.id))
    }

    /**
     * Cut 2 — **switch away and back**. The selected session identity really
     * moves to another session while the host call is in flight, then moves back
     * before the retry (`swapActiveSessionTargetForTest` is the #1739 seam that
     * changes ONLY the target, keeping the client and generation, so this is the
     * switch limb in isolation).
     *
     * Mutation that must redden it: drop the token from the retry command and the
     * host injects a second time.
     */
    @Test
    fun switchAwayAndBackDoesNotDuplicateThePayloadOrTheEnter() = runTest(scheduler) {
        val payload = "deploy across a switch"
        val store = SharedPrefsOutboundQueueStore(context)
        val row = newRow(store, "sessSwitch", payload)
        val client = FakeTmuxClient()
        client.defaultCaptureResponse = workingFrame(payload)
        val vm = newVm(applicationContext = context, outboundQueueStore = store)
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "session-a",
            client = client,
        )
        vm.hostAck.authorityOverrideForTest = OutboundDeliveryAuthority.HostCliAck
        val targetBefore = vm.currentTargetSessionKeyForTest()
        var targetDuringCut: String? = null
        val host = FakeHostCli(
            answer = { call, _ -> if (call == 1) null else ExecResult("delivered\n", "", 0) },
            duringCall = { call, _ ->
                if (call == 1) {
                    vm.swapActiveSessionTargetForTest("session-b")
                    targetDuringCut = vm.currentTargetSessionKeyForTest()
                    // …and back, exactly as the user returns to the session.
                    vm.swapActiveSessionTargetForTest("session-a")
                }
            },
        )
        vm.hostAck.execOverrideForTest = host

        val first = async {
            vm.sendAgentPayloadToPaneResult(
                "%0",
                payload,
                AgentKind.ClaudeCode,
                sendToken = row.id,
                durableRow = DurableOutboundRowIdentity("sessSwitch", row.id),
            )
        }
        advanceUntilIdle()

        assertNotNull("precondition: a session target exists to switch away from", targetBefore)
        assertNotEquals(
            "the cut must really have happened: the selected session must MOVE " +
                "mid-send, otherwise this test is not about a switch",
            targetBefore,
            targetDuringCut,
        )
        assertEquals(
            "the switch must leave the same target it started on",
            targetBefore,
            vm.currentTargetSessionKeyForTest(),
        )
        assertEquals(
            ComposerSendResult.Failed,
            first.await().toComposerSendResult(),
        )

        val retry = async {
            vm.sendAgentPayloadToPaneResult(
                "%0",
                payload,
                AgentKind.ClaudeCode,
                sendToken = row.id,
                durableRow = DurableOutboundRowIdentity("sessSwitch", row.id),
            )
        }
        advanceUntilIdle()

        assertEquals(
            "the retry after returning to the session resolves the row",
            ComposerSendResult.Delivered,
            retry.await().toComposerSendResult(),
        )
        assertEquals("exactly one occurrence in the pane", listOf(payload), host.injections)
        assertFalse(requireNotNull(store.item(row.id)).wireOutcomeUnknown)
        assertTrue(store.markDelivered(row.id))
    }

    /**
     * Cut 3 — **app kill between the wire-write and the ack-read**. The client
     * process is gone: its ViewModel is discarded and a NEW one is built, which is
     * what a relaunch actually does. What crosses the process boundary is exactly
     * what is durable — the row in [SharedPrefsOutboundQueueStore] and the token
     * in the host's journal.
     *
     * Both host outcomes are covered, because they are genuinely different facts
     * and the criterion ("no duplicate") must hold for both:
     *
     *  - the host process COMPLETED after the app died ⇒ the record is `delivered`,
     *    so the relaunched app's retry reads `already-delivered` and converges;
     *  - the host process died too, after the paste ⇒ the record is an unresolved
     *    claim with a dead owner, so a plain retry reads exit 5 `send-interrupted`
     *    — an honest retryable `Failed`, never an "unconfirmed" row, and still
     *    NOT a second injection (this client never passes `--resend-interrupted`).
     *
     * Mutation that must redden it: let the retry inject when the claim is
     * unresolved, and the second arm's `injections` becomes two.
     */
    @Test
    fun appKillBetweenWireWriteAndAckReadDoesNotDuplicateThePayloadOrTheEnter() =
        runTest(scheduler) {
            // --- arm A: the host completed while the app was dead ---------------
            val payloadA = "deploy across a kill"
            val storeA = SharedPrefsOutboundQueueStore(context)
            val rowA = newRow(storeA, "sessKillA", payloadA)
            val hostA = FakeHostCli(
                answer = { call, _ ->
                    if (call == 1) null else ExecResult("delivered\n", "", 0)
                },
            )
            val deadVm = newVm(applicationContext = context, outboundQueueStore = storeA)
            deadVm.attachClientForTest(FakeTmuxClient())
            deadVm.hostAck.authorityOverrideForTest = OutboundDeliveryAuthority.HostCliAck
            deadVm.hostAck.execOverrideForTest = hostA
            val doomed = async {
                deadVm.sendAgentPayloadToPaneResult(
                    "%0",
                    payloadA,
                    AgentKind.ClaudeCode,
                    sendToken = rowA.id,
                    durableRow = DurableOutboundRowIdentity("sessKillA", rowA.id),
                )
            }
            advanceUntilIdle()
            // The answer never reached the app. Drop the result on the floor: the
            // process that would have read it is gone.
            doomed.await()

            // Relaunch: a NEW ViewModel, and the row re-read from durable storage.
            val relaunchedStore = SharedPrefsOutboundQueueStore(context)
            val recovered = requireNotNull(relaunchedStore.item(rowA.id)) {
                "the durable row must survive the process boundary"
            }
            assertFalse(
                "an app kill must not leave the row unconfirmed",
                recovered.wireOutcomeUnknown,
            )
            val relaunchedVm =
                newVm(applicationContext = context, outboundQueueStore = relaunchedStore)
            assertNotEquals(
                "the relaunch must be a DIFFERENT ViewModel — the process boundary is " +
                    "the point of this cut",
                System.identityHashCode(deadVm),
                System.identityHashCode(relaunchedVm),
            )
            relaunchedVm.attachClientForTest(FakeTmuxClient())
            relaunchedVm.hostAck.authorityOverrideForTest = OutboundDeliveryAuthority.HostCliAck
            relaunchedVm.hostAck.execOverrideForTest = hostA

            val retryA = async {
                relaunchedVm.sendAgentPayloadToPaneResult(
                    "%0",
                    payloadA,
                    AgentKind.ClaudeCode,
                    sendToken = recovered.id,
                    durableRow = DurableOutboundRowIdentity("sessKillA", recovered.id),
                )
            }
            advanceUntilIdle()
            assertEquals(
                "the relaunched app's retry converges on the host's journal",
                ComposerSendResult.Delivered,
                retryA.await().toComposerSendResult(),
            )
            assertEquals(
                "exactly one occurrence in the pane across the process boundary",
                listOf(payloadA),
                hostA.injections,
            )

            // --- arm B: the host process died too, after the paste ---------------
            val payloadB = "deploy across a kill that took the host"
            val storeB = SharedPrefsOutboundQueueStore(context)
            val rowB = newRow(storeB, "sessKillB", payloadB)
            val hostB = FakeHostCli(hostProcessDies = { call, _ -> call == 1 })
            val deadVmB = newVm(applicationContext = context, outboundQueueStore = storeB)
            deadVmB.attachClientForTest(FakeTmuxClient())
            deadVmB.hostAck.authorityOverrideForTest = OutboundDeliveryAuthority.HostCliAck
            deadVmB.hostAck.execOverrideForTest = hostB
            val doomedB = async {
                deadVmB.sendAgentPayloadToPaneResult(
                    "%0",
                    payloadB,
                    AgentKind.ClaudeCode,
                    sendToken = rowB.id,
                    durableRow = DurableOutboundRowIdentity("sessKillB", rowB.id),
                )
            }
            advanceUntilIdle()
            doomedB.await()

            val relaunchedStoreB = SharedPrefsOutboundQueueStore(context)
            val relaunchedVmB =
                newVm(applicationContext = context, outboundQueueStore = relaunchedStoreB)
            relaunchedVmB.attachClientForTest(FakeTmuxClient())
            relaunchedVmB.hostAck.authorityOverrideForTest = OutboundDeliveryAuthority.HostCliAck
            relaunchedVmB.hostAck.execOverrideForTest = hostB

            val retryB = async {
                relaunchedVmB.sendAgentPayloadToPaneResult(
                    "%0",
                    payloadB,
                    AgentKind.ClaudeCode,
                    sendToken = rowB.id,
                    durableRow = DurableOutboundRowIdentity("sessKillB", rowB.id),
                )
            }
            advanceUntilIdle()
            assertEquals(
                "an unresolved claim with a dead owner is an honest retryable Failed",
                ComposerSendResult.Failed,
                retryB.await().toComposerSendResult(),
            )
            assertEquals(
                "and STILL exactly one occurrence — a plain retry never injects twice",
                listOf(payloadB),
                hostB.injections,
            )
            assertFalse(
                "and never an 'unconfirmed' row",
                requireNotNull(relaunchedStoreB.item(rowB.id)).wireOutcomeUnknown,
            )
        }

    /**
     * Cut 4 — **double-tap Retry**. Two dispatches of the SAME durable row are in
     * flight at the same time (the shape #1602 names: an automatic flush racing a
     * manual retry). The first is held inside the host call while the second
     * arrives, so the second really does meet a LIVE claim — the exit 8
     * `send-in-progress` branch of the CLI.
     *
     * Mutation that must redden it: answer the concurrent second call `delivered`
     * instead of `send-in-progress` and `injections` becomes two.
     */
    @Test
    fun doubleTapRetryDoesNotDuplicateThePayloadOrTheEnter() = runTest(scheduler) {
        val payload = "deploy on a double tap"
        val store = SharedPrefsOutboundQueueStore(context)
        val row = newRow(store, "sessDoubleTap", payload)
        val client = FakeTmuxClient()
        client.defaultCaptureResponse = workingFrame(payload)
        val vm = newVm(applicationContext = context, outboundQueueStore = store)
        vm.attachClientForTest(client)
        vm.hostAck.authorityOverrideForTest = OutboundDeliveryAuthority.HostCliAck
        val holdFirstCall = CompletableDeferred<Unit>()
        val host = FakeHostCli(
            duringCall = { call, _ -> if (call == 1) holdFirstCall.await() },
        )
        vm.hostAck.execOverrideForTest = host

        val identity = DurableOutboundRowIdentity("sessDoubleTap", row.id)
        val firstTap = async {
            vm.sendAgentPayloadToPaneResult(
                "%0",
                payload,
                AgentKind.ClaudeCode,
                sendToken = row.id,
                durableRow = identity,
            )
        }
        advanceUntilIdle()
        assertEquals(
            "precondition: the first tap must be parked INSIDE the host call, " +
                "otherwise the second tap is sequential and this is not a double tap",
            1,
            host.commands.size,
        )
        assertFalse("precondition: the first tap has not answered yet", firstTap.isCompleted)

        val secondTap = async {
            vm.sendAgentPayloadToPaneResult(
                "%0",
                payload,
                AgentKind.ClaudeCode,
                sendToken = row.id,
                durableRow = identity,
            )
        }
        advanceUntilIdle()
        assertEquals(
            "the second tap must reach the host WHILE the first is in flight",
            2,
            host.commands.size,
        )
        assertEquals(
            "a live sibling owns the outcome: an honest retryable Failed, never an unknown",
            ComposerSendResult.Failed,
            secondTap.await().toComposerSendResult(),
        )

        holdFirstCall.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            "the first tap still resolves normally",
            ComposerSendResult.Delivered,
            firstTap.await().toComposerSendResult(),
        )
        assertEquals(
            "two concurrent taps of one row put the payload in the pane ONCE",
            listOf(payload),
            host.injections,
        )
        assertFalse(
            "a double tap must never leave the row unconfirmed",
            requireNotNull(store.item(row.id)).wireOutcomeUnknown,
        )
        assertEquals(0L, OutboundLegacyStackProbe.total())

        // And a later sequential retry converges rather than injecting again.
        val thirdTap = async {
            vm.sendAgentPayloadToPaneResult(
                "%0",
                payload,
                AgentKind.ClaudeCode,
                sendToken = row.id,
                durableRow = identity,
            )
        }
        advanceUntilIdle()
        assertEquals(ComposerSendResult.Delivered, thirdTap.await().toComposerSendResult())
        assertEquals(listOf(payload), host.injections)
        assertTrue(store.markDelivered(row.id))
    }

    /**
     * Acceptance criterion 5: with the flag on new, `wireOutcomeUnknown` is
     * UNREACHABLE and no UI path can display "unconfirmed" — for EVERY documented
     * failure exit, not just the one convenient case (G2 class coverage).
     *
     * `AuthoritativeAckPending` is the sole producer of `wireOutcomeUnknown`
     * (`PromptComposerOutboundSend.kt`'s `when (result)`), so asserting that no
     * exit can produce it is the proof.
     */
    @Test
    fun everyDocumentedFailureExitIsAPlainRetryableFailedAndNeverUnconfirmed() =
        runTest(scheduler) {
            val cases: List<Pair<String, ExecResult?>> = listOf(
                "bad-usage" to ExecResult("bad-usage\n", "--pane is required", 2),
                "old-cli" to ExecResult("", "Error: No such command 'send'.", 2),
                "cli-absent" to ExecResult("", "", 127),
                "pane-not-found" to ExecResult("pane-not-found\n", "no such pane", 3),
                "tmux-failed" to ExecResult("tmux-failed\n", "tmux is missing", 4),
                "send-interrupted" to ExecResult("send-interrupted\n", "partial delivery", 5),
                "timeout" to ExecResult("timeout\n", "exceeded --timeout", 6),
                "journal-failed" to ExecResult("journal-failed\n", "permission denied", 7),
                "send-in-progress" to ExecResult("send-in-progress\n", "pid 42 still running", 8),
                "no-answer" to null,
                "unmapped-exit" to ExecResult("", "something else", 99),
            )
            cases.forEach { (name, answer) ->
                OutboundLegacyStackProbe.reset()
                val store = SharedPrefsOutboundQueueStore(context)
                val row = newRow(store, "sess-$name", "prompt $name")
                val client = FakeTmuxClient()
                client.defaultCaptureResponse = workingFrame("prompt $name")
                val vm = newVm(applicationContext = context, outboundQueueStore = store)
                vm.attachClientForTest(client)
                vm.hostAck.authorityOverrideForTest = OutboundDeliveryAuthority.HostCliAck
                // `no-answer` models the bounded exec that never returned; every
                // other case is the CLI's own documented answer.
                vm.hostAck.execOverrideForTest = HostAckSendExec { _, _ -> answer }

                val sent = async {
                    vm.sendAgentPayloadToPaneResult(
                        "%0",
                        "prompt $name",
                        AgentKind.ClaudeCode,
                        sendToken = row.id,
                        durableRow = DurableOutboundRowIdentity("sess-$name", row.id),
                    )
                }
                advanceUntilIdle()

                val result = sent.await().toComposerSendResult()
                assertEquals(
                    "$name must be a plain retryable Failed, never AuthoritativeAckPending " +
                        "(the sole producer of the absorbing 'unconfirmed' row)",
                    ComposerSendResult.Failed,
                    result,
                )
                assertEquals(
                    "$name must not consult the legacy stack even on failure: " +
                        OutboundLegacyStackProbe.snapshot(),
                    0L,
                    OutboundLegacyStackProbe.total(),
                )
                val stored = requireNotNull(store.item(row.id)) { "$name: row must survive" }
                assertFalse("$name must never mark the row unconfirmed", stored.wireOutcomeUnknown)
                assertFalse(
                    "$name must not arm the write-ahead barrier that strands a row",
                    stored.wireSubmitAttempted,
                )
                assertNull(
                    "$name must never surface an 'unconfirmed' count in the UI",
                    outboundQueueSummary(
                        listOf(stored),
                        connectionDegraded = false,
                    ).attentionSuffix,
                )
            }
        }

    /**
     * Acceptance criterion 8 (G10 unhappy state): an OLD/missing host CLI without
     * `send` fails CLOSED to a plain retryable `Failed` with an honest, actionable
     * message naming the host-CLI upgrade — bounded, never a hang, never a new
     * unknown state.
     *
     * The two shapes a real host produces: Click's unknown-command rejection
     * (stderr + exit 2, empty stdout — exactly what
     * `tests/docker/agent-bin-old-cli/pocketshell` emits), and
     * [PocketshellCommand.wrap]'s exit 127 when no binary exists at all.
     */
    @Test
    fun oldOrMissingHostCliFailsClosedWithAnUpgradeHint() = runTest(scheduler) {
        listOf(
            "old-cli" to ExecResult("", "Error: No such command 'send'.", 2),
            "absent" to ExecResult("", "", 127),
        ).forEach { (name, answer) ->
            val store = SharedPrefsOutboundQueueStore(context)
            val row = newRow(store, "sess-old-$name", "upgrade me")
            val vm = newVm(applicationContext = context, outboundQueueStore = store)
            vm.attachClientForTest(FakeTmuxClient())
            vm.hostAck.authorityOverrideForTest = OutboundDeliveryAuthority.HostCliAck
            vm.hostAck.execOverrideForTest = HostAckSendExec { _, _ -> answer }

            val sent = async {
                vm.sendAgentPayloadToPaneResult(
                    "%0",
                    "upgrade me",
                    AgentKind.ClaudeCode,
                    sendToken = row.id,
                    durableRow = DurableOutboundRowIdentity("sess-old-$name", row.id),
                )
            }
            advanceUntilIdle()

            assertTrue("$name: the send must COMPLETE, never hang", sent.isCompleted)
            val failure = sent.await().exceptionOrNull()
            assertTrue(
                "$name: the failure must be the acknowledged path's own type, not the " +
                    "turnover-not-proven type that becomes 'unconfirmed'",
                failure is HostAckSendFailedException,
            )
            assertEquals(
                "$name: the message must name the host-CLI upgrade",
                HOST_CLI_UPGRADE_HINT,
                failure?.message,
            )
            assertEquals(
                "$name: an old host CLI must not fall back to the old stack",
                0L,
                OutboundLegacyStackProbe.total(),
            )
            assertFalse(requireNotNull(store.item(row.id)).wireOutcomeUnknown)
        }
    }

    /**
     * Acceptance criterion 10: oracle-vs-ground-truth convergence — after a send
     * journey the SERVER's ledger equals the set of pruned rows, and the
     * unconfirmed count is zero. `OutboundExactlyOnceAcrossFlapE2eTest` asserts
     * no duplicates but never that the composer's BELIEF converged; this does.
     */
    @Test
    fun serverLedgerEqualsPrunedRowsAndNothingIsUnconfirmed() = runTest(scheduler) {
        val session = "sessConverge"
        val store = SharedPrefsOutboundQueueStore(context)
        // The SECOND row's answer is lost mid-way; every other call answers
        // normally. (Round one had `if (call == 2) null else null`, i.e. an
        // unconditional null, so the comment described a journey the script did
        // not create.)
        val host = FakeHostCli(
            answer = { call, _ ->
                if (call == 2) null else ExecResult("delivered\n", "", 0)
            },
        )
        val client = FakeTmuxClient()
        val vm = newVm(applicationContext = context, outboundQueueStore = store)
        vm.attachClientForTest(client)
        vm.hostAck.authorityOverrideForTest = OutboundDeliveryAuthority.HostCliAck
        vm.hostAck.execOverrideForTest = host

        val pruned = mutableSetOf<String>()
        listOf("first prompt", "second prompt", "third prompt").forEach { text ->
            val row = newRow(store, session, text)
            var result: ComposerSendResult
            // A real journey retries a failed row; the loop is bounded so a
            // never-resolving row would FAIL the convergence assertion below
            // rather than spin.
            var attempt = 0
            do {
                attempt += 1
                val sent = async {
                    vm.sendAgentPayloadToPaneResult(
                        "%0",
                        text,
                        AgentKind.ClaudeCode,
                        sendToken = row.id,
                        durableRow = DurableOutboundRowIdentity(session, row.id),
                    )
                }
                advanceUntilIdle()
                result = sent.await().toComposerSendResult()
            } while (result == ComposerSendResult.Failed && attempt < 3)
            assertEquals("$text must converge", ComposerSendResult.Delivered, result)
            assertTrue(store.markDelivered(row.id))
            pruned += row.id
        }

        assertEquals(
            "the server ledger must equal the set of pruned rows",
            pruned,
            host.journal.toSet(),
        )
        assertEquals(
            "every payload landed exactly once",
            listOf("first prompt", "second prompt", "third prompt"),
            host.injections,
        )
        assertTrue(
            "no row may be left unconfirmed",
            store.itemsFor(session).none { it.wireOutcomeUnknown },
        )
        assertTrue("every row is pruned", store.itemsFor(session).isEmpty())
        assertEquals(0L, OutboundLegacyStackProbe.total())
    }

    /**
     * The composer RAW-BYTES (shell route) lane rides the same acknowledgement,
     * with `--enter` derived from the trailing CR the composer appends.
     */
    @Test
    fun composerShellRouteAlsoRidesTheAcknowledgement() = runTest(scheduler) {
        val store = SharedPrefsOutboundQueueStore(context)
        val row = newRow(store, "sessRaw", "ls -la")
        val host = FakeHostCli()
        val vm = newVm(applicationContext = context, outboundQueueStore = store)
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.hostAck.authorityOverrideForTest = OutboundDeliveryAuthority.HostCliAck
        vm.hostAck.execOverrideForTest = host

        val sent = async {
            vm.writeInputToPaneResult(
                "%0",
                "ls -la\r".toByteArray(Charsets.UTF_8),
                sendToken = row.id,
                durableRow = DurableOutboundRowIdentity("sessRaw", row.id),
            )
        }
        advanceUntilIdle()

        assertEquals(ComposerSendResult.Delivered, sent.await().toComposerSendResult())
        assertEquals(listOf("ls -la"), host.injections)
        assertTrue(
            "the trailing CR becomes --enter, and the payload loses it",
            host.commands.single().let {
                it.contains("--enter") && it.contains("printf %s 'ls -la'")
            },
        )
        assertEquals(0L, OutboundLegacyStackProbe.total())
        assertTrue(client.sentCommands.none { it.startsWith("send-keys") })
    }

    /**
     * Issues #209 / #529, in the lane this slice reworks: a MULTI-LINE composer /
     * dictation send to a SHELL pane must arrive bracketed-paste framed.
     *
     * The lane this replaces (`deliverPaneInputBytes`) framed anything containing
     * a line break, precisely so a readline-based program treats the block as one
     * pasted prompt. The CLI deliberately does not frame and pastes with
     * `paste-buffer -r`, so newlines stay newlines on the wire — without the
     * client framing, the shell executes the transcript LINE BY LINE, which is the
     * closed #209 symptom ("Dictation newlines get sent as Enter — split into
     * multiple messages") resurrected.
     *
     * Mutation that must redden it: delete the `containsLineBreak` branch of
     * `frameForWire`, and the injected payload loses its `\e[200~` markers.
     */
    @Test
    fun aMultiLineShellRouteSendIsBracketedPasteFramedExactlyOnce() = runTest(scheduler) {
        val store = SharedPrefsOutboundQueueStore(context)
        val text = "cd /srv/app\ngit pull --rebase\n./deploy.sh"
        val row = newRow(store, "sessRawMulti", text)
        val host = FakeHostCli()
        val vm = newVm(applicationContext = context, outboundQueueStore = store)
        vm.attachClientForTest(FakeTmuxClient())
        vm.hostAck.authorityOverrideForTest = OutboundDeliveryAuthority.HostCliAck
        vm.hostAck.execOverrideForTest = host

        val sent = async {
            vm.writeInputToPaneResult(
                "%0",
                (text + "\r").toByteArray(Charsets.UTF_8),
                sendToken = row.id,
                durableRow = DurableOutboundRowIdentity("sessRawMulti", row.id),
            )
        }
        advanceUntilIdle()
        assertEquals(ComposerSendResult.Delivered, sent.await().toComposerSendResult())

        val injected = host.injections.single()
        assertTrue(
            "a multi-line SHELL send must be bracketed-paste framed or readline runs " +
                "it line by line (#209/#529): $injected",
            injected.startsWith(PASTE_START) && injected.endsWith(PASTE_END),
        )
        assertEquals(
            "exactly one opening marker — a double frame is #1854",
            1,
            Regex(Regex.escape(PASTE_START)).findAll(injected).count(),
        )
        assertTrue("the body survives byte-exact", injected.contains("git pull --rebase"))
        assertTrue(
            "the submit rides --enter, OUTSIDE the frame",
            host.commands.single().contains("--enter"),
        )
    }

    /**
     * Issue #1854 in the same lane: an ALREADY-framed block (the `TerminalView`
     * IME path frames a multi-line commit before it reaches the sink) must be
     * delivered as-is, never framed a second time — a doubled frame puts the inner
     * markers into the receiving program as literal content.
     */
    @Test
    fun anAlreadyFramedShellRouteSendIsNotFramedASecondTime() = runTest(scheduler) {
        val store = SharedPrefsOutboundQueueStore(context)
        val row = newRow(store, "sessRawFramed", "framed")
        val host = FakeHostCli()
        val vm = newVm(applicationContext = context, outboundQueueStore = store)
        vm.attachClientForTest(FakeTmuxClient())
        vm.hostAck.authorityOverrideForTest = OutboundDeliveryAuthority.HostCliAck
        vm.hostAck.execOverrideForTest = host
        val framed = com.pocketshell.core.terminal.input.BracketedPaste.frame("one\ntwo")

        val sent = async {
            vm.writeInputToPaneResult(
                "%0",
                framed,
                sendToken = row.id,
                durableRow = DurableOutboundRowIdentity("sessRawFramed", row.id),
            )
        }
        advanceUntilIdle()
        assertEquals(ComposerSendResult.Delivered, sent.await().toComposerSendResult())

        assertEquals(
            "an already-framed block must arrive byte-identical",
            String(framed, Charsets.UTF_8),
            host.injections.single(),
        )
        assertEquals(
            "exactly one opening marker — the #1854 double frame",
            1,
            Regex(Regex.escape(PASTE_START)).findAll(host.injections.single()).count(),
        )
    }

    /**
     * The one hole in "with the flag on new, the old stack's entry points are
     * never invoked": a submit-ONLY send on a durable row (`"\r"`) trims to an
     * empty payload. It must still ride the acknowledgement rather than falling
     * through to the legacy stack — the CLI accepts an empty payload WITH
     * `--enter`.
     *
     * Mutation that must redden it: restore the `if (payload.isEmpty()) return
     * null` early exit and the legacy probe counts stop being zero.
     */
    @Test
    fun aSubmitOnlyDurableSendStillRidesTheAcknowledgement() = runTest(scheduler) {
        val store = SharedPrefsOutboundQueueStore(context)
        val row = newRow(store, "sessSubmitOnly", "submit only")
        val host = FakeHostCli()
        val vm = newVm(applicationContext = context, outboundQueueStore = store)
        vm.attachClientForTest(FakeTmuxClient())
        vm.hostAck.authorityOverrideForTest = OutboundDeliveryAuthority.HostCliAck
        vm.hostAck.execOverrideForTest = host

        val sent = async {
            vm.writeInputToPaneResult(
                "%0",
                "\r".toByteArray(Charsets.UTF_8),
                sendToken = row.id,
                durableRow = DurableOutboundRowIdentity("sessSubmitOnly", row.id),
            )
        }
        advanceUntilIdle()

        assertEquals(ComposerSendResult.Delivered, sent.await().toComposerSendResult())
        assertEquals(
            "the Enter-only submit must be ONE acknowledged exec, not a legacy send",
            1,
            host.commands.size,
        )
        assertTrue(
            "an empty payload with --enter is the CLI's submit-only shape",
            host.commands.single().let { it.contains("--enter") && it.contains("printf %s ''") },
        )
        assertEquals(
            "the hard switch has no hole: the legacy stack must not run at all: " +
                OutboundLegacyStackProbe.snapshot(),
            0L,
            OutboundLegacyStackProbe.total(),
        )
    }

    /**
     * A raw KEYSTROKE (no durable row) is deliberately NOT a row-lifecycle send,
     * so it keeps the existing terminal path — routing every arrow key through an
     * SSH exec of a Python CLI would be a latency disaster and would mangle
     * control bytes. The negative case that keeps the switch narrow (G6).
     */
    @Test
    fun aRawKeystrokeWithNoDurableRowKeepsTheTerminalPath() = runTest(scheduler) {
        val host = FakeHostCli()
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.hostAck.authorityOverrideForTest = OutboundDeliveryAuthority.HostCliAck
        vm.hostAck.execOverrideForTest = host

        val sent = async { vm.writeInputToPaneResult("%0", "q".toByteArray(Charsets.UTF_8)) }
        advanceUntilIdle()

        assertTrue(sent.await().isSuccess)
        assertTrue("a keystroke must not pay for an SSH exec", host.commands.isEmpty())
        assertTrue(client.sentCommands.any { it.startsWith("send-keys") })
    }

    /**
     * Acceptance criterion 11 — the preserved machinery.
     *
     * This slice changes ONLY who answers "did it land". It must not disturb the
     * durable-queue behaviour the previous fixes earned: #961 sendKey coalesce (a
     * re-Send of the same logical prompt collapses onto the existing row instead
     * of minting a duplicate), the durable row IDENTITY the drain lease and
     * consumer generations key on, and the write-ahead ordering that keeps a
     * prompt recoverable.
     *
     * The composer VM, the queue store, the drain lease, the consumer generation
     * registry, identity promotion and the draft write-ahead are NOT in this
     * change's diff, and their own suites run in the same gate. This asserts the
     * one property that is genuinely NEW here: those invariants still hold while
     * the ACKNOWLEDGED authority owns delivery.
     */
    @Test
    fun theDurableQueueInvariantsSurviveUnderTheAcknowledgedPath() = runTest(scheduler) {
        val session = "sessPreserve"
        val store = SharedPrefsOutboundQueueStore(context)
        val host = FakeHostCli()
        val vm = newVm(applicationContext = context, outboundQueueStore = store)
        vm.attachClientForTest(FakeTmuxClient())
        vm.hostAck.authorityOverrideForTest = OutboundDeliveryAuthority.HostCliAck
        vm.hostAck.execOverrideForTest = host

        // #961 coalesce: a re-Send of the SAME logical prompt (same sendKey) must
        // collapse onto the existing un-delivered row, not mint a second one.
        val first = newRow(store, session, "coalesce me")
        val second = store.enqueue(
            sessionKey = session,
            cleanText = "coalesce me",
            paneId = "%0",
            route = OutboundRoute.AgentPayload,
            agentKind = "claude",
            sendKey = "sk-coalesce me",
            tmuxSessionId = "\$9",
            tmuxSessionCreated = 1900L,
        )
        assertEquals("a re-Send must coalesce, never duplicate", first.id, second.id)
        assertEquals(1, store.itemsFor(session).size)

        // The durable row IDENTITY the drain lease / consumer generations key on
        // is what the acknowledged send carries as its idempotency token, so a
        // retry of the SAME row is the SAME token to the host.
        val sent = async {
            vm.sendAgentPayloadToPaneResult(
                "%0",
                "coalesce me",
                AgentKind.ClaudeCode,
                sendToken = first.id,
                durableRow = DurableOutboundRowIdentity(session, first.id),
            )
        }
        advanceUntilIdle()
        assertEquals(ComposerSendResult.Delivered, sent.await().toComposerSendResult())
        assertTrue(
            "the exec must carry the DURABLE ROW ID as the host-side token",
            host.commands.single().contains("--token '${first.id}'"),
        )
        // Write-ahead ordering preserved: the prompt is recoverable from the
        // durable row right up until it is acknowledged and pruned.
        assertNotNull(store.item(first.id))
        assertTrue(store.markDelivered(first.id))
        assertNull(store.item(first.id))
    }

    /**
     * The production default MUST be the acknowledged path — the field test only
     * happens if it is the default. This is what stops
     * [TmuxSessionViewModelTestBase]'s legacy pin from silently becoming the
     * product's answer.
     */
    @Test
    fun theAcknowledgedPathIsTheProductionDefault() {
        assertEquals(
            OutboundDeliveryAuthority.HostCliAck,
            AppSettings.DEFAULT_OUTBOUND_DELIVERY_AUTHORITY,
        )
        assertEquals(
            OutboundDeliveryAuthority.HostCliAck,
            AppSettings().outboundDeliveryAuthority,
        )
        val port = HostAckDeliveryPort({ null }, { testMainDispatcher }, {}) { null }
        assertTrue(
            "with no persisted preference the acknowledged path owns row lifecycle",
            port.active,
        )
        assertEquals(OutboundDeliveryAuthority.HostCliAck, port.authority)
    }

    /**
     * Reviewer round 2, non-blocking finding 1 — the acknowledged-lane probe
     * must have no blind spot.
     *
     * [HostAckSendProbe] is the safety net that stops
     * `assertNoAcknowledgedSendsWereRecorded`'s ZERO from meaning "nothing
     * happened at all": a legacy-pinned connected class asserts the count is
     * zero, so anything this lane does and does NOT count is a way for that
     * assertion to pass while the pin is broken. With the increment below the
     * transport guard, a send that entered this lane while the session was down
     * was invisible — and the flap journey cuts the link on purpose, so that is
     * precisely the class where a dead transport plus a broken pin could have
     * read zero.
     *
     * This pins the count on the one path that produces no exec at all.
     */
    @Test
    fun anAcknowledgedSendAttemptedWhileDisconnectedStillCountsOnTheProbe() = runTest {
        val port = HostAckDeliveryPort({ null }, { testMainDispatcher }, {}) {
            OutboundDeliveryAuthority.HostCliAck
        }
        assertEquals("precondition: the probe starts clean", 0L, HostAckSendProbe.count())

        val result = port.sendAgentPayload(
            paneId = "%1",
            payload = "deploy while the session is down",
            sendToken = "row-no-transport",
        )

        val failure = result.exceptionOrNull()
        assertTrue(
            "a send with no transport is a plain retryable Failed, never an unknown",
            failure is HostAckSendFailedException,
        )
        assertEquals(
            "no-transport",
            (failure as HostAckSendFailedException).outcome.reason,
        )
        assertEquals(
            "the acknowledged lane must count the ATTEMPT even when the transport " +
                "guard returns before any exec — otherwise the legacy-pinned " +
                "journeys' zero can mean 'the session was down', not 'the legacy " +
                "path owned it' (issue #2124 reviewer round 2)",
            1L,
            HostAckSendProbe.count(),
        )
    }

    /**
     * The command shape is the contract with the host CLI: `--pane`, `--token`,
     * `--enter`, a bounded `--timeout`, the payload on stdin through `printf %s`,
     * and — the #847 trap — the resolver wrapper GROUPED so the pipe reaches the
     * real CLI instead of wrap()'s first statement.
     */
    @Test
    fun theCommandPipesStdinIntoTheGroupedResolverWrapper() {
        val command = buildHostAckSendCommand(
            paneId = "%3",
            token = "row-1",
            payload = "hello 'world' 100%",
            withEnter = true,
        )
        assertTrue(command.startsWith("printf %s 'hello '\"'\"'world'\"'\"' 100%'"))
        assertTrue(
            "the multi-statement resolver must be grouped or the CLI blocks on read() forever",
            command.contains("| { export PATH=") && command.trimEnd().endsWith(" ; }"),
        )
        assertTrue(command.contains("send --pane '%3' --token 'row-1' --enter --timeout 10"))
        assertTrue(
            "the CLI's own budget must be under the transport bound so the host can answer",
            HOST_ACK_SEND_CLI_TIMEOUT_SECONDS * 1_000L < HOST_ACK_SEND_EXEC_TIMEOUT_MS,
        )
    }

    /**
     * A multi-line agent prompt must arrive bracketed-paste framed — the CLI
     * deliberately does not frame (the client owns framing; framing twice put the
     * literal `\e[200~` markers into the receiving program, #1854).
     */
    @Test
    fun aMultiLinePromptIsBracketedPasteFramedExactlyOnce() = runTest(scheduler) {
        val payload = "line one\nline two"
        val host = FakeHostCli()
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.hostAck.authorityOverrideForTest = OutboundDeliveryAuthority.HostCliAck
        vm.hostAck.execOverrideForTest = host

        val sent = async {
            vm.sendAgentPayloadToPaneResult("%0", payload, AgentKind.ClaudeCode, "tok-frame")
        }
        advanceUntilIdle()
        assertTrue(sent.await().isSuccess)

        val injected = host.injections.single()
        assertTrue("framed once at the start", injected.startsWith(PASTE_START))
        assertTrue("framed once at the end", injected.endsWith(PASTE_END))
        assertEquals(
            "exactly one opening marker — a double frame is #1854",
            1,
            Regex(Regex.escape(PASTE_START)).findAll(injected).count(),
        )
        assertTrue(injected.contains("line one"))
        assertTrue(injected.contains("line two"))
    }
}
