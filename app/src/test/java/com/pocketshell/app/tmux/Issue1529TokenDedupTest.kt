package com.pocketshell.app.tmux

import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClientException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #1529 — outbound exactly-once must NOT false-dedup an intentionally-repeated
 * identical prompt.
 *
 * The #1526 S1 verify-before-resend guard keyed its ambiguous-attempt ledger on the
 * PAYLOAD (needle = whitespace-stripped payload tail; ledger key = payload hash). So
 * two DISTINCT user sends of identical bytes collided: the second probed, saw the
 * first send's marker already on the pane, judged `AlreadyLanded`, and suppressed the
 * intentional second send — a false-dedup that DROPS a legitimate message.
 *
 * The fix makes the ledger identity a PER-SEND-ATTEMPT token (the durable outbound row
 * id, or an opaque per-send token for non-queue sends), NOT payload-derived. Two
 * identical payloads with distinct tokens both deliver; a RETRY of one attempt (same
 * token) still dedups to exactly-once.
 *
 * RED on base (payload-keyed): [twoDistinctIdenticalSendsBothDeliver] fails — the second
 * distinct send is suppressed (occurrence 1). GREEN (token-keyed): occurrence 2.
 * [oneAttemptRetryStillDeliversExactlyOnce] stays GREEN in both — the dedup that #1526
 * S1 added must not regress.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue1529TokenDedupTest {

    private val paneId = "%0"
    private val payloadText = "run the full release checklist now"
    private val bytes = "$payloadText\r".toByteArray(Charsets.UTF_8)

    /** A fake server counting every distinct paste, landing then losing the FIRST send's result. */
    private class FlapOnceServer {
        var pastes = 0
            private set
        var enters = 0
            private set
        private var firstStillFlapping = true

        suspend fun send() {
            pastes += 1
            if (firstStillFlapping) {
                firstStillFlapping = false
                // The bytes LAND server-side, but the result is lost (the ambiguous flap
                // the #1526 audit describes): the paste ran, the client sees a failure.
                throw TmuxClientException("tmux send-keys (exec interactive send lane) timed out after 10000ms")
            }
        }

        fun submitEnter() {
            enters += 1
        }
    }

    private fun captureShowingPayload(): FakeTmuxClient =
        FakeTmuxClient().apply {
            defaultCaptureResponse = CommandResponse(number = 0L, output = listOf("$ $payloadText"), isError = false)
            scrollbackCaptureResponse = CommandResponse(number = 0L, output = listOf("$ $payloadText"), isError = false)
        }

    private suspend fun deliverWithToken(
        ledger: OutboundDeliveryLedger,
        client: FakeTmuxClient,
        server: FlapOnceServer,
        sendToken: String,
    ): Result<Unit> = deliverRawInputWithGuard(
        ledger = ledger,
        client = client,
        paneId = paneId,
        bytes = bytes,
        localRenderText = "$ ",
        sendToken = sendToken,
        send = { _, _, _ -> server.send() },
        submitEnter = { _, _ -> server.submitEnter() },
        afterDelivered = { _, _, _ -> },
    )

    /**
     * THE #1529 case. Two DISTINCT intentional sends of identical bytes (distinct
     * per-send tokens). The first lands but its result is lost (flap), leaving an
     * ambiguous ledger entry. The second is a DIFFERENT user send: it must NOT be
     * suppressed by the first send's marker — it must deliver, so the payload reaches
     * the server TWICE (occurrence == 2).
     *
     * RED on base (payload-keyed ledger): the second send's token collides on payload,
     * the probe finds the first send's text already on the pane, judges AlreadyLanded,
     * and drops the intentional second send (occurrence 1).
     */
    @Test
    fun twoDistinctIdenticalSendsBothDeliver() = runTest {
        val ledger = OutboundDeliveryLedger()
        val client = captureShowingPayload()
        val server = FlapOnceServer()

        // Send #1 (its own token) — lands, then the result is lost (ambiguous flap).
        val first = deliverWithToken(ledger, client, server, sendToken = "send-1")
        assertTrue("the first send flaps (ambiguous failure) — its bytes landed", first.isFailure)
        assertEquals("the first send reached the server once", 1, server.pastes)

        // Send #2 — a DIFFERENT intentional user send of the identical prompt. It must
        // deliver, not be false-deduped against send #1's marker.
        val second = deliverWithToken(ledger, client, server, sendToken = "send-2")

        assertTrue("the intentional second send must deliver", second.isSuccess)
        assertEquals(
            "two DISTINCT identical sends must BOTH reach the server (occurrence == 2) — " +
                "the second must not be false-deduped against the first (RED on base: occurrence 1)",
            2,
            server.pastes,
        )
    }

    /**
     * The dedup #1526 S1 added must NOT regress: a single send whose ONE attempt flapped
     * (ambiguous, bytes landed) and is RETRIED — same per-send token — must probe, see
     * the payload already landed, and complete with an Enter-only submit WITHOUT
     * re-pasting (occurrence == 1).
     */
    @Test
    fun oneAttemptRetryStillDeliversExactlyOnce() = runTest {
        val ledger = OutboundDeliveryLedger()
        val client = captureShowingPayload()
        val server = FlapOnceServer()
        val token = "send-1"

        val first = deliverWithToken(ledger, client, server, sendToken = token)
        assertTrue("the attempt flaps (ambiguous failure)", first.isFailure)
        assertEquals(1, server.pastes)

        // The RETRY of the SAME attempt (same token) — the payload already landed.
        val retry = deliverWithToken(ledger, client, server, sendToken = token)

        assertTrue("the retry resolves (already landed)", retry.isSuccess)
        assertEquals(
            "a retry of ONE attempt must dedup to exactly-once — no re-paste (occurrence == 1)",
            1,
            server.pastes,
        )
        assertEquals("the already-landed retry completes via an Enter-only submit", 1, server.enters)
    }
}
