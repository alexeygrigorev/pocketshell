package com.pocketshell.app.tmux

import com.pocketshell.core.tmux.CommandResponse
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2409 — a COLD ATTACH over a slow-but-progressing link must complete,
 * not surrender to "Tap Reconnect to retry".
 *
 * ## The reported symptom
 *
 * `ColdDialUnderBandwidthLimitE2eTest#coldDialUnderBufferbloatCompletesWithinBudget`
 * failed on 9 nightly fault-injection runs (streak 4, first seen 2026-08-20)
 * with a bare `ComposeTimeoutException`. The authoritative logcat from
 * https://github.com/alexeygrigorev/pocketshell/actions/runs/33232540342 shows
 * the app — not the harness — gave up:
 *
 * ```
 * tmux-list-panes-start   ... elapsedMs=2835
 * captureWithCursor exec timed out >2500ms (heal lane)   x3
 * tmux-connect-failed     ... elapsedMs=14838
 *   cause=TmuxAttachPanesReadyException: Timed out waiting for tmux panes ...
 * PsConnEffectDriver: state Attaching -> Unreachable
 * ```
 *
 * `14838 - 2835 = 12003ms` — the outer [ATTACH_PANES_READY_TIMEOUT_MS] fired
 * while the FIRST `reconcilePanes()` was still inside its OWN bounded work.
 *
 * ## The mechanism these tests pin
 *
 * One `reconcilePanes()` costs [RECONCILE_LIST_PANES_EXEC_TIMEOUT_MS] (the
 * `list-panes` exec lane) plus the seed-before-reveal `capture-pane` ladder that
 * `preloadVisibleContentForNewPanes` runs SYNCHRONOUSLY. While that ladder was
 * the full [SEED_CAPTURE_EMPTY_RETRY_ATTEMPTS] x [SEED_CAPTURE_TIMEOUT_MS]
 * force-heal budget (≈10.4s), one reconcile could cost ≈16.4s inside a 12s outer
 * ceiling. On a fast link no inner ceiling is ever reached, which is why this
 * defect was invisible to every test double that answers instantly.
 * [FakeTmuxClient]'s `#2409` slow-link seams therefore spend the REAL inner
 * budgets on the virtual clock, exactly the way the nightly's bufferbloat toxic
 * does on the wire.
 *
 * ## Class coverage (G2)
 *
 * The seed ladder burns its budget through three DISTINCT failure modes on a
 * degraded link, all handled by the same retry loop — a capture that outlasts
 * its ceiling (the nightly's shape), one that comes back as a tmux error, and
 * one that comes back empty (#693/#662's flaky-link shape). Each is exercised
 * below, because a fix that only covered the timeout branch would leave the
 * other two able to blow the same outer ceiling. A fourth case guards the
 * OPPOSITE direction: a genuinely wedged `list-panes` must still fail fast
 * through #1316's reconcile-level escape.
 *
 * These compile and run against the UNFIXED tree (they reference no constant the
 * fix introduces), so they are the red→green pin for the fix itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue2409SlowLinkAttachBudgetTest : TmuxSessionViewModelTestBase() {

    /**
     * Failure mode 1 — every seed `capture-pane` OUTLASTS the short seed
     * ceiling and the exec lane throws. This is the nightly's exact shape
     * (`captureWithCursor exec timed out >2500ms (heal lane)`).
     */
    @Test
    fun coldAttachCompletesWhenEverySeedCaptureOutlastsItsCeilingOnASlowLink() =
        runTest(scheduler) {
            val client = slowLinkClient().apply {
                // At the ceiling => the exec lane burns the full ceiling and throws,
                // exactly like RealTmuxClient.captureWithCursor.
                captureWithCursorLatencyMs = SEED_CAPTURE_TIMEOUT_MS
            }
            assertSlowLinkAttachSucceeds(client, "seed captures timing out")
        }

    /**
     * Failure mode 2 — the seed `capture-pane` answers INSIDE its ceiling but
     * with a tmux error, so the ladder retries and still spends most of the
     * budget on a slow link.
     */
    @Test
    fun coldAttachCompletesWhenEverySeedCaptureErrorsOnASlowLink() =
        runTest(scheduler) {
            val client = slowLinkClient().apply {
                captureWithCursorLatencyMs = SEED_CAPTURE_TIMEOUT_MS - 100L
                defaultCaptureResponse = CommandResponse(
                    number = 0L,
                    output = listOf("can't find pane"),
                    isError = true,
                )
            }
            assertSlowLinkAttachSucceeds(client, "seed captures erroring")
        }

    /**
     * Failure mode 3 — the seed `capture-pane` answers inside its ceiling but
     * EMPTY (#693/#662's degraded-but-connected channel). Same ladder, same
     * budget, same outer ceiling.
     */
    @Test
    fun coldAttachCompletesWhenEverySeedCaptureComesBackEmptyOnASlowLink() =
        runTest(scheduler) {
            val client = slowLinkClient().apply {
                captureWithCursorLatencyMs = SEED_CAPTURE_TIMEOUT_MS - 100L
                // No canned capture response => the fake serves an EMPTY success,
                // which the heal loop scores CaptureEmpty and retries.
            }
            assertSlowLinkAttachSucceeds(client, "seed captures coming back empty")
        }

    /**
     * The reconcile's OWN escape must survive the fix: a `list-panes` that
     * genuinely outlasts [RECONCILE_LIST_PANES_EXEC_TIMEOUT_MS] still has to
     * surface the fast retryable attach error #1316 built, rather than parking
     * the user until the outer ceiling. Guards the other direction — shrinking
     * the pre-reveal seed must not turn a wedged link into a slow one.
     */
    @Test
    fun aWedgedListPanesStillFailsFastViaTheReconcileEscapeNotTheOuterCeiling() =
        runTest(scheduler) {
            val vm = newVm()
            val client = slowLinkClient().apply {
                // Past the reconcile exec ceiling => the exec lane throws and the
                // reconcile reports Failed immediately.
                listPanesViaExecLatencyMs = RECONCILE_LIST_PANES_EXEC_TIMEOUT_MS
            }

            val startedAt = scheduler.currentTime
            val attach = startAttach(vm, client)
            pumpUntilComplete(attach)
            val elapsedMs = scheduler.currentTime - startedAt

            val status = vm.connectionStatus.value
            assertTrue(
                "a wedged list-panes must still surface a retryable Failed, got $status",
                status is TmuxSessionViewModel.ConnectionStatus.Failed,
            )
            assertTrue(
                "the reconcile-level escape must fire at its own " +
                    "${RECONCILE_LIST_PANES_EXEC_TIMEOUT_MS}ms ceiling, well before the outer " +
                    "${ATTACH_PANES_READY_TIMEOUT_MS}ms attach ceiling — otherwise a wedged " +
                    "attach just feels slower; took ${elapsedMs}ms",
                elapsedMs < ATTACH_PANES_READY_TIMEOUT_MS,
            )
            assertTrue("Reconnect must stay available", vm.canReconnect.value)
        }

    // ---- helpers ---------------------------------------------------------

    /**
     * A tmux client on a congested-but-healthy link: `list-panes` answers in
     * ~3 s (the nightly's measured value, comfortably inside its own 6 s exec
     * ceiling) with one real pane row.
     */
    private fun slowLinkClient(): FakeTmuxClient = FakeTmuxClient().apply {
        listPanesViaExecLatencyMs = SLOW_LINK_LIST_PANES_MS
        repeat(LIST_PANES_RESPONSES) {
            responses += CommandResponse(
                number = (it + 1).toLong(),
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            )
        }
    }

    private fun TestScope.startAttach(vm: TmuxSessionViewModel, client: FakeTmuxClient): Deferred<Unit> =
        async {
            vm.attachClientWithReadinessForTest(
                hostId = 1L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = client,
            )
        }.also { runCurrent() }

    /**
     * Bounded virtual-clock pump. Deliberately NOT `advanceUntilIdle()`: the
     * revealed session arms watchdog loops, and an unbounded drain would hang
     * this test rather than fail it.
     */
    private fun TestScope.pumpUntilComplete(attach: Deferred<Unit>) {
        var advancedMs = 0L
        while (!attach.isCompleted && advancedMs < PUMP_CEILING_MS) {
            advanceTimeBy(PUMP_STEP_MS)
            runCurrent()
            advancedMs += PUMP_STEP_MS
        }
        assertTrue(
            "the attach never settled within ${PUMP_CEILING_MS}ms of virtual time",
            attach.isCompleted,
        )
    }

    private fun TestScope.assertSlowLinkAttachSucceeds(
        client: FakeTmuxClient,
        label: String,
    ) {
        val vm = newVm()
        val startedAt = scheduler.currentTime
        val attach = startAttach(vm, client)
        pumpUntilComplete(attach)
        val elapsedMs = scheduler.currentTime - startedAt

        // Anti-vacuous #1: the pre-reveal seed really ran, and really was bounded by
        // the SHORT seed ceiling. Without this a "green" could mean the capture was
        // skipped entirely and the attach was never slow in the first place.
        assertTrue(
            "precondition ($label): the pre-reveal seed must have issued at least one " +
                "capture round-trip, got ${client.slowLinkCaptureAttempts}",
            client.slowLinkCaptureAttempts.isNotEmpty(),
        )
        assertTrue(
            "precondition ($label): the seed must be bounded by the SHORT seed ceiling " +
                "(${SEED_CAPTURE_TIMEOUT_MS}ms), got ${client.slowLinkCaptureAttempts.take(4)}",
            client.slowLinkCaptureAttempts.all { it.endsWith("@$SEED_CAPTURE_TIMEOUT_MS") },
        )
        // Anti-vacuous #2: the reconcile really rode the dedicated exec lane with
        // its own ceiling threaded through.
        assertTrue(
            "precondition ($label): the reconcile must ride the exec lane bounded by " +
                "${RECONCILE_LIST_PANES_EXEC_TIMEOUT_MS}ms, got ${client.slowLinkListPanesAttempts}",
            client.slowLinkListPanesAttempts.isNotEmpty() &&
                client.slowLinkListPanesAttempts
                    .all { it.endsWith("@$RECONCILE_LIST_PANES_EXEC_TIMEOUT_MS") },
        )
        // Anti-vacuous #3: the link really was slow — the attach had to pay BOTH inner
        // ceilings (a full `list-panes` round-trip plus a full seed capture) before it
        // could settle. Deliberately a floor, not a band: it holds on the UNFIXED tree
        // too (where the attach is cut at the 12s ceiling), so the assertion that goes
        // red on base is the LOAD-BEARING one below, never this scaffolding (G6).
        assertTrue(
            "precondition ($label): the fixture must make the attach pay both inner " +
                "ceilings (${SLOW_LINK_LIST_PANES_MS}ms list-panes + ${SEED_CAPTURE_TIMEOUT_MS}ms " +
                "seed capture) or it is not reproducing a slow link at all; took ${elapsedMs}ms",
            elapsedMs >= SLOW_LINK_LIST_PANES_MS + SEED_CAPTURE_TIMEOUT_MS,
        )

        // LOAD-BEARING: the app must NOT surrender a healthy attach.
        val status = vm.connectionStatus.value
        assertTrue(
            "a cold attach over a slow-but-progressing link ($label) must complete instead of " +
                "surrendering to the retryable attach error — the #2409 symptom is exactly " +
                "`TmuxAttachPanesReadyException: Timed out waiting for tmux panes` -> Unreachable " +
                "on a link that was merely slow. Took ${elapsedMs}ms, status=$status",
            status is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(
            "the attach must reveal the reconciled pane ($label)",
            listOf("%0"),
            vm.panes.value.map { it.paneId },
        )
    }

    private companion object {
        /**
         * The nightly measured `tmux-list-panes-start` -> first heal-capture
         * timeout at ~3.1 s on the bufferbloat fixture: slow, but comfortably
         * inside the reconcile's own 6 s exec ceiling.
         */
        const val SLOW_LINK_LIST_PANES_MS: Long = 3_000L

        /** Enough canned rows for the reconcile poll loop plus any post-reveal refresh. */
        const val LIST_PANES_RESPONSES: Int = 8

        const val PUMP_STEP_MS: Long = 250L
        const val PUMP_CEILING_MS: Long = 180_000L
    }
}
