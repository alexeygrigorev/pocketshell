package com.pocketshell.next.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reconnect ladder, pinned (rewrite task U-7).
 *
 * This suite exists to make the policy a decision rather than an accident: the
 * rungs and the give-up point are the ENTIRE reconnect behaviour of the app, so
 * changing either has to be a deliberate edit to an assertion that spells the
 * numbers out, not a quiet tweak that nothing notices. The plan's non-goal list
 * (no jitter, no episode budgets, no storm classes) is only enforceable if the
 * shape is pinned somewhere.
 */
class ReconnectControllerTest {

    /**
     * The exact ladder from plan §C.3: try at once, then 1s, 2s, 5s, 10s.
     *
     * Written as one literal list rather than five assertions so a reader sees
     * the whole policy in one line, and so an inserted or reordered rung fails
     * loudly with both sequences printed.
     */
    @Test
    fun `the ladder is 0, 1s, 2s, 5s, 10s`() {
        val controller = ReconnectController()

        val decisions = (0..4).map { controller.decide(it) }

        assertEquals(
            listOf(
                ReconnectController.Decision.RetryAfter(0L, 0),
                ReconnectController.Decision.RetryAfter(1_000L, 1),
                ReconnectController.Decision.RetryAfter(2_000L, 2),
                ReconnectController.Decision.RetryAfter(5_000L, 3),
                ReconnectController.Decision.RetryAfter(10_000L, 4),
            ),
            decisions,
        )
    }

    /** Five rungs, so attempt 5 is where it stops — and it stays stopped. */
    @Test
    fun `attempt 5 gives up, and so does every attempt after it`() {
        val controller = ReconnectController()

        assertEquals(ReconnectController.Decision.GiveUp, controller.decide(5))
        assertEquals(ReconnectController.Decision.GiveUp, controller.decide(6))
        assertEquals(ReconnectController.Decision.GiveUp, controller.decide(100))
    }

    /**
     * The ladder is a constructor parameter, and the give-up point follows it.
     *
     * Not a hypothetical: it is how a test can pin behaviour that depends on
     * the ladder without waiting 18 real seconds, and it is the only supported
     * way to change the policy — there is deliberately no setter, no config and
     * no adaptive tuning.
     */
    @Test
    fun `a custom ladder decides its own length`() {
        val controller = ReconnectController(ladderMs = listOf(0L, 50L))

        assertEquals(ReconnectController.Decision.RetryAfter(0L, 0), controller.decide(0))
        assertEquals(ReconnectController.Decision.RetryAfter(50L, 1), controller.decide(1))
        assertEquals(ReconnectController.Decision.GiveUp, controller.decide(2))
    }
}
