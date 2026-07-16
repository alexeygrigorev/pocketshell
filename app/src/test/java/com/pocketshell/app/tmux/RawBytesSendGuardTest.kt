package com.pocketshell.app.tmux

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClientException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #1586 (reproduce-first, D33/G10) — the composer **RawBytes lane** (shell
 * panes, `writeInputToPaneResult`) lacked the tmux-error check AND the
 * verify-before-resend ledger the agent-payload lane got in #1577, so it was
 * lane-asymmetric with two HIGH holes:
 *
 *  - **H1a false-success:** a RawBytes `send-keys` that returns tmux `%error`
 *    (dead/closed pane) was swallowed — the outbound row was marked Delivered with
 *    NOTHING delivered. RED on base: the send reports success. GREEN: it FAILS
 *    (surfaced retryable/visible).
 *  - **H1b blind duplicate:** an ambiguous failure (bytes landed, exec result lost)
 *    followed by the composer auto-retry re-ran the shell command TWICE. RED on
 *    base: the literal is pasted twice. GREEN: the retry probes `AlreadyLanded` and
 *    the literal is pasted exactly ONCE.
 *
 * These drive the REAL send path ([TmuxSessionViewModel.writeInputToPaneResult] —
 * the exact method the composer's `OutboundRoute.RawBytes` branch calls) against a
 * [FakeTmuxClient], with the ambiguous cut injected SYNTHETICALLY (#780 model, no
 * `assumeTrue`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RawBytesSendGuardTest : TmuxSessionViewModelTestBase() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun FakeTmuxClient.literalCount(text: String): Int =
        sentCommands.count { it == "send-keys -l -t %0 -- '$text'" }

    private fun FakeTmuxClient.enterCount(): Int =
        sentCommands.count { it == "send-keys -t %0 Enter" }

    private fun clientShowing(vararg lines: String): FakeTmuxClient = FakeTmuxClient().apply {
        defaultCaptureResponse = CommandResponse(number = 0L, output = lines.toList(), isError = false)
    }

    // ---------------------------------------------------------------------
    // H1a — false-success on a dead-pane RawBytes send
    // ---------------------------------------------------------------------

    /**
     * THE H1a reported case: a single-line shell command whose literal `send-keys`
     * hits a dead/closed pane and tmux returns `%error`. RED on base: the response
     * is discarded ⇒ the send reports success (row marked Delivered, nothing sent).
     * GREEN: the `%error` surfaces as a failure.
     */
    @Test
    fun deadPaneLiteralSendSurfacesFailureNotFalseSuccess() = runTest(scheduler) {
        val vm = newVm(applicationContext = context)
        val client = FakeTmuxClient().apply {
            errorOnCommandPrefix = "send-keys -l -t %0"
            errorOnCommandRemaining = 1
        }
        vm.attachClientForTest(client)

        val result = vm.writeInputToPaneResult("%0", "git status\r".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        assertTrue(
            "a RawBytes send whose tmux `send-keys -l` returns %error MUST fail — not be " +
                "swallowed and reported as a false-success (RED on base: result.isSuccess)",
            result.isFailure,
        )
    }

    /**
     * Class coverage — named-key send: the submit Enter of a shell command hits a
     * dead pane (`%error`). The named-key response was ALSO discarded on base.
     */
    @Test
    fun deadPaneNamedKeySendSurfacesFailure() = runTest(scheduler) {
        val vm = newVm(applicationContext = context)
        val client = FakeTmuxClient().apply {
            errorOnCommandPrefix = "send-keys -t %0 Enter"
            errorOnCommandRemaining = 1
        }
        vm.attachClientForTest(client)

        val result = vm.writeInputToPaneResult("%0", "ls -la\r".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        assertTrue(
            "a named-key (Enter) send that returns %error MUST surface as a failure",
            result.isFailure,
        )
    }

    /**
     * Class coverage — multi-line paste: a multi-line RawBytes payload rides the
     * bracketed-paste route (issue #1636: a `set-buffer` fill + one `paste-buffer`
     * commit); a `%error` on the fill must also fail (this lane already threw, this
     * proves the CLASS is covered end to end).
     */
    @Test
    fun deadPaneMultiLinePasteSurfacesFailure() = runTest(scheduler) {
        val vm = newVm(applicationContext = context)
        val client = FakeTmuxClient().apply {
            errorOnCommandPrefix = "set-buffer"
            errorOnCommandRemaining = 1
        }
        vm.attachClientForTest(client)

        val result = vm.writeInputToPaneResult(
            "%0",
            "echo one\necho two\r".toByteArray(Charsets.UTF_8),
        )
        advanceUntilIdle()

        assertTrue("a multi-line paste `%error` must fail the send", result.isFailure)
    }

    /**
     * A genuinely-healthy send still succeeds (the H1a error-check does not reject a
     * normal send).
     */
    @Test
    fun healthyRawBytesSendStillSucceeds() = runTest(scheduler) {
        val vm = newVm(applicationContext = context)
        val client = clientShowing()
        vm.attachClientForTest(client)

        val result = vm.writeInputToPaneResult("%0", "git status\r".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        assertTrue("a healthy RawBytes send must still succeed", result.isSuccess)
        assertEquals("and it pastes the literal exactly once", 1, client.literalCount("git status"))
    }

    // ---------------------------------------------------------------------
    // H1b — blind duplicate on ambiguous-failure retry
    // ---------------------------------------------------------------------

    /**
     * THE H1b reported case: attempt 1 types the shell command (literal lands) and
     * its submit Enter's exec times out — an AMBIGUOUS failure (the Enter may have
     * run server-side before the result was lost, the exact mid-send flap cut). The
     * composer keeps the row and auto-retries.
     *
     * RED on base: the retry blind-re-sends ⇒ the literal is pasted TWICE ⇒ the
     * shell command runs twice (dangerous for a destructive command).
     * GREEN: the retry probes the pane, sees the payload already landed, and
     * suppresses the resend ⇒ the literal is pasted EXACTLY ONCE.
     *
     * AND — the over-suppression guard (reviewer B1/B2, D33-F2): the landed-but-
     * unsubmitted command MUST still get its terminating submit on the retry. In this
     * exact cut the literal is typed on the prompt (`$ git status`) but attempt 1's
     * submit Enter threw, so the command is TYPED-BUT-NEVER-RUN. The probe needle
     * cannot tell "typed on the prompt" from "ran", so on `AlreadyLanded` the retry
     * must submit Enter-only (agent-lane parity), exactly ONCE — not drop the delivery
     * silently while reporting success. RED on the over-suppressing behavior (retry
     * sends NOTHING ⇒ zero new Enters ⇒ command left unsubmitted); GREEN: the retry
     * adds exactly one submit Enter with no second literal paste.
     */
    @Test
    fun ambiguousRetryOfLandedShellCommandDoesNotDuplicate() = runTest(scheduler) {
        val vm = newVm(applicationContext = context)
        // The pane advertises the typed command (attempt 1's literal landed);
        // the submit Enter times out ambiguously.
        val client = clientShowing("$ git status")
        client.throwOnCommandPrefix = "send-keys -t %0 Enter"
        client.throwOnCommandRemaining = 1
        client.throwOnCommandException = TmuxClientException("tmux command timed out")
        vm.attachClientForTest(client)

        // Issue #1529: the retry re-uses the SAME per-send token as attempt 1 (a queue-flush
        // re-dispatches the same durable row id) so the guard dedups it as a retry.
        val first = vm.writeInputToPaneResult("%0", "git status\r".toByteArray(Charsets.UTF_8), "r-landed")
        advanceUntilIdle()
        assertTrue("attempt 1 must surface the ambiguous failure", first.isFailure)
        assertEquals("attempt 1 pastes the literal exactly once", 1, client.literalCount("git status"))
        // Attempt 1's submit Enter reached the wire (recorded) then threw ambiguously.
        val entersAfterFirst = client.enterCount()

        // The retry (composer auto-flush): probes ⇒ AlreadyLanded ⇒ NO second paste,
        // but DOES complete the landed-but-unsubmitted command with an Enter-only submit.
        val second = vm.writeInputToPaneResult("%0", "git status\r".toByteArray(Charsets.UTF_8), "r-landed")
        advanceUntilIdle()
        assertTrue(
            "the AlreadyLanded retry must SUCCEED (the landed command is completed, not a " +
                "false-success silent drop)",
            second.isSuccess,
        )
        assertEquals(
            "an ambiguous retry of a shell command that already LANDED must be deduped to " +
                "EXACTLY ONE paste — never re-run the command a second time (RED on base: 2)",
            1,
            client.literalCount("git status"),
        )
        assertEquals(
            "the AlreadyLanded retry MUST submit the landed-but-unsubmitted command with " +
                "EXACTLY ONE Enter — the over-suppressing behavior sends nothing (RED: 0 new " +
                "Enters), leaving `git status` typed on the prompt but never run",
            entersAfterFirst + 1,
            client.enterCount(),
        )
    }

    /**
     * Reviewer B3 — the NO-SPURIOUS-ENTER direction of the same over-suppression
     * guard. A RawBytes send with `withEnter == false` carries NO trailing CR
     * (`TmuxSessionScreen.kt`'s composer sends the bare literal — partial typed input,
     * a caret-position paste), so `fullText.length == payload.length` and the guard's
     * CR/LF heuristic (`if (fullText.length > payload.length)`) must NOT fire a submit.
     *
     * This mirrors [ambiguousRetryOfLandedShellCommandDoesNotDuplicate] but with a
     * no-CR payload: attempt 1's literal `send-keys -l` lands on the pane (the pane
     * echoes it) yet throws ambiguously (result lost), recording a wire attempt. The
     * retry probes `AlreadyLanded`. Because the original send carried NO submit, the
     * retry MUST inject ZERO Enters (submitting an unintended command is a real
     * shell-lane hazard) AND MUST NOT re-paste the literal.
     *
     * Teeth: if the `AlreadyLanded` submit branch fired UNCONDITIONALLY (dropping the
     * `fullText.length > payload.length` guard), the retry would inject one Enter here
     * ⇒ `enterCount()` would rise from 0 to 1 ⇒ the no-spurious-Enter assertion FAILS.
     * With the correct guard it stays 0 (GREEN).
     */
    @Test
    fun ambiguousRetryOfNoSubmitLandedPayloadInjectsNoEnter() = runTest(scheduler) {
        val vm = newVm(applicationContext = context)
        // The pane echoes the typed literal (it landed); the literal send itself threw
        // ambiguously (result lost) — a no-CR, no-Enter RawBytes send.
        val client = clientShowing("$ git status")
        client.throwOnCommandPrefix = "send-keys -l -t %0"
        client.throwOnCommandRemaining = 1
        vm.attachClientForTest(client)

        // NOTE: no trailing "\r" — this is the `withEnter == false` composer send.
        // Issue #1529: the retry re-uses the SAME per-send token as attempt 1.
        val first = vm.writeInputToPaneResult("%0", "git status".toByteArray(Charsets.UTF_8), "r-nosub")
        advanceUntilIdle()
        assertTrue("attempt 1 (no-CR literal throws) must surface the ambiguous failure", first.isFailure)
        assertEquals("attempt 1 records the literal exactly once", 1, client.literalCount("git status"))
        // A no-submit send never issues an Enter — this must be zero and STAY zero.
        val entersAfterFirst = client.enterCount()
        assertEquals("a no-CR send issues no submit Enter on attempt 1", 0, entersAfterFirst)

        // The retry probes ⇒ AlreadyLanded. The original send carried NO submit, so the
        // guard must NOT complete it with a spurious Enter, and must NOT re-paste.
        val second = vm.writeInputToPaneResult("%0", "git status".toByteArray(Charsets.UTF_8), "r-nosub")
        advanceUntilIdle()
        assertTrue(
            "the AlreadyLanded retry of an already-landed no-submit payload must SUCCEED " +
                "(the literal already landed — nothing left to deliver)",
            second.isSuccess,
        )
        assertEquals(
            "the AlreadyLanded retry of a NO-CR (withEnter==false) payload must inject ZERO " +
                "Enters — an unconditional submit branch would fire a spurious Enter (RED: 1), " +
                "submitting an unintended shell command",
            entersAfterFirst,
            client.enterCount(),
        )
        assertEquals(
            "and the literal is NOT re-pasted on the AlreadyLanded retry (deduped to one)",
            1,
            client.literalCount("git status"),
        )
    }

    /**
     * Class coverage — the GENUINE not-landed failure still delivers (no
     * over-suppression). Attempt 1 throws BEFORE the bytes land (the pane never
     * shows the command); the retry probes `NotLanded` and re-sends, so the command
     * is delivered on the retry.
     */
    @Test
    fun genuineNotLandedFailureStillDeliversOnRetry() = runTest(scheduler) {
        val vm = newVm(applicationContext = context)
        // The pane never shows the command — the bytes did NOT land.
        val client = clientShowing("$ ")
        client.throwOnCommandPrefix = "send-keys -l -t %0"
        client.throwOnCommandRemaining = 1
        vm.attachClientForTest(client)

        // Issue #1529: the retry re-uses the SAME per-send token so the guard probes (and,
        // seeing NotLanded, re-sends) rather than treating it as an unrelated fresh send.
        val first = vm.writeInputToPaneResult("%0", "git status\r".toByteArray(Charsets.UTF_8), "r-notland")
        advanceUntilIdle()
        assertTrue("attempt 1 (literal throws before landing) must fail", first.isFailure)

        val second = vm.writeInputToPaneResult("%0", "git status\r".toByteArray(Charsets.UTF_8), "r-notland")
        advanceUntilIdle()
        assertTrue("the genuine not-landed retry must re-send and succeed", second.isSuccess)
        assertTrue(
            "a NOT-landed payload must NOT be suppressed as a false-`AlreadyLanded` — the " +
                "retry must actually paste it (over-suppression would silently drop the command)",
            client.literalCount("git status") >= 1,
        )
        assertTrue("the delivered retry submits the command (Enter)", client.enterCount() >= 1)
    }

    /**
     * Class coverage — a short single-line command already visible on the pane is
     * still deduped correctly by the baseline-aware probe (not a presence-only false
     * positive). Attempt 1 types "deploy" (its Enter times out); the retry sees the
     * count over the pre-send baseline ⇒ suppressed to one paste.
     */
    @Test
    fun ambiguousRetryOfShortCommandDeduped() = runTest(scheduler) {
        val vm = newVm(applicationContext = context)
        val client = clientShowing("$ deploy")
        client.throwOnCommandPrefix = "send-keys -t %0 Enter"
        client.throwOnCommandRemaining = 1
        vm.attachClientForTest(client)

        // Issue #1529: the retry re-uses attempt 1's per-send token so it is deduped.
        val first = vm.writeInputToPaneResult("%0", "deploy\r".toByteArray(Charsets.UTF_8), "r-short")
        advanceUntilIdle()
        assertTrue(first.isFailure)
        assertEquals(1, client.literalCount("deploy"))

        val second = vm.writeInputToPaneResult("%0", "deploy\r".toByteArray(Charsets.UTF_8), "r-short")
        advanceUntilIdle()
        assertEquals(
            "the short command must be deduped to exactly one paste on the ambiguous retry",
            1,
            client.literalCount("deploy"),
        )
    }

    /**
     * The fix must NOT falsely suppress a DISTINCT command sent right after a different
     * ambiguous one. Issue #1529: the ledger is keyed per (pane, SEND TOKEN) — each send
     * gets its own token — so an unrelated command (its own fresh token) is unaffected.
     * (Two DISTINCT sends of the IDENTICAL payload are likewise independent — see
     * Issue1529TokenDedupTest.)
     */
    @Test
    fun distinctCommandAfterLandedOneIsNotSuppressed() = runTest(scheduler) {
        val vm = newVm(applicationContext = context)
        val client = clientShowing("$ git status")
        client.throwOnCommandPrefix = "send-keys -t %0 Enter"
        client.throwOnCommandRemaining = 1
        vm.attachClientForTest(client)

        vm.writeInputToPaneResult("%0", "git status\r".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        val other = vm.writeInputToPaneResult("%0", "git push\r".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()
        assertTrue("a distinct command must send normally", other.isSuccess)
        assertEquals(
            "a DIFFERENT command must not be suppressed by the prior command's ledger entry",
            1,
            client.literalCount("git push"),
        )
    }
}
