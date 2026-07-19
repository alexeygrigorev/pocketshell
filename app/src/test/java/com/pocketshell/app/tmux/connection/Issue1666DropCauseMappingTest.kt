package com.pocketshell.app.tmux.connection

import com.pocketshell.core.connection.DropCause
import com.pocketshell.core.ssh.SshLeaseCloseReason
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxDisconnectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1666 (D28 Layer 3) — the derivation authority: [SelfInflictedClose] turns the
 * close reason the emitter named into a TYPED [DropCause]. This pins:
 *
 *  - every lease/`-CC` close reason maps to the intended cause (exhaustive, both edges);
 *  - the #1681 storm's `agent_kind_classify` self-close (a bounded-exec/probe that tears
 *    down or force-refreshes the shared lease it borrowed) maps to [DropCause.SelfInflicted],
 *    so the reducer refuses it;
 *  - a GENUINE death (`Disconnected`, keepalive, reader EOF/exception, server exit) maps to
 *    a NON-self-inflicted cause, so recovery still runs (the load-bearing negative);
 *  - the existing boolean predicates delegate to the typed authority (ONE source of truth,
 *    no parallel filter to drift — the #1632 rot).
 */
class Issue1666DropCauseMappingTest {

    // ---- Lease edge ----

    @Test
    fun `self-inflicted lease reasons map to SelfInflicted`() {
        val selfInflicted = listOf(
            SshLeaseCloseReason.ExplicitDisconnect,
            SshLeaseCloseReason.ForceRefresh,
            SshLeaseCloseReason.IdleExpired,
            SshLeaseCloseReason.IdleTrimmed,
            SshLeaseCloseReason.ProcessStopped,
            SshLeaseCloseReason.ManagerClosed,
            SshLeaseCloseReason.ConnectCancelled,
        )
        for (reason in selfInflicted) {
            val cause = SelfInflictedClose.dropCauseForLeaseClose(reason)
            assertTrue("$reason should be SelfInflicted, was $cause", cause is DropCause.SelfInflicted)
            assertEquals(reason.name, (cause as DropCause.SelfInflicted).reason)
        }
    }

    @Test
    fun `the #1681 agent_kind_classify self-close reason maps to SelfInflicted`() {
        // The bounded-exec/probe self-close (the #1681 mobile-latency storm) tears down or
        // force-refreshes the shared lease it borrowed — ExplicitDisconnect / ForceRefresh.
        assertTrue(
            SelfInflictedClose.dropCauseForLeaseClose(SshLeaseCloseReason.ExplicitDisconnect)
                is DropCause.SelfInflicted,
        )
        assertTrue(
            SelfInflictedClose.dropCauseForLeaseClose(SshLeaseCloseReason.ForceRefresh)
                is DropCause.SelfInflicted,
        )
    }

    @Test
    fun `genuine lease deaths map to a non-self-inflicted cause`() {
        assertEquals(
            DropCause.RemoteFailure("Disconnected"),
            SelfInflictedClose.dropCauseForLeaseClose(SshLeaseCloseReason.Disconnected),
        )
        assertEquals(
            DropCause.KeepaliveDead,
            SelfInflictedClose.dropCauseForLeaseClose(SshLeaseCloseReason.KeepaliveDead),
        )
        // An unnamed lease close carries no local-intent evidence -> conservative Unknown.
        assertEquals(DropCause.Unknown, SelfInflictedClose.dropCauseForLeaseClose(null))
    }

    @Test
    fun `every lease reason maps to a non-self-inflicted cause unless it is one of the seven self-close reasons`() {
        // Exhaustive guard: keeps the mapping honest if a new SshLeaseCloseReason is added.
        val selfCloseReasons = setOf(
            SshLeaseCloseReason.ExplicitDisconnect,
            SshLeaseCloseReason.ForceRefresh,
            SshLeaseCloseReason.IdleExpired,
            SshLeaseCloseReason.IdleTrimmed,
            SshLeaseCloseReason.ProcessStopped,
            SshLeaseCloseReason.ManagerClosed,
            SshLeaseCloseReason.ConnectCancelled,
        )
        for (reason in SshLeaseCloseReason.entries) {
            val cause = SelfInflictedClose.dropCauseForLeaseClose(reason)
            val isSelf = cause is DropCause.SelfInflicted
            assertEquals("$reason self-inflicted classification", reason in selfCloseReasons, isSelf)
            // The boolean predicate must agree with the typed authority (single source of truth).
            assertEquals(isSelf, SelfInflictedClose.isSelfInflictedLeaseClose(reason))
        }
    }

    // ---- -CC control-channel edge ----

    private fun ccEvent(reason: TmuxDisconnectReason, intent: String = "reader") =
        TmuxDisconnectEvent(reason = reason, source = "test", intent = intent)

    @Test
    fun `self-inflicted control-channel closes map to SelfInflicted`() {
        assertTrue(
            SelfInflictedClose.dropCauseForControlChannelClose(ccEvent(TmuxDisconnectReason.ExplicitClose))
                is DropCause.SelfInflicted,
        )
        assertTrue(
            SelfInflictedClose.dropCauseForControlChannelClose(ccEvent(TmuxDisconnectReason.ExplicitDetach))
                is DropCause.SelfInflicted,
        )
        // #1568: a detach performed as part of swapping/replacing the attached client.
        val swap = SelfInflictedClose.dropCauseForControlChannelClose(
            ccEvent(TmuxDisconnectReason.ReaderEof, intent = "detach_or_replace"),
        )
        assertTrue("client-swap detach is self-inflicted regardless of reason", swap is DropCause.SelfInflicted)
    }

    @Test
    fun `genuine control-channel losses map to a non-self-inflicted cause`() {
        assertEquals(
            DropCause.RemoteFailure("ReaderEof"),
            SelfInflictedClose.dropCauseForControlChannelClose(ccEvent(TmuxDisconnectReason.ReaderEof)),
        )
        assertEquals(
            DropCause.RemoteFailure("ReaderException"),
            SelfInflictedClose.dropCauseForControlChannelClose(ccEvent(TmuxDisconnectReason.ReaderException)),
        )
        assertEquals(
            DropCause.RemoteFailure("CommandTimeout"),
            SelfInflictedClose.dropCauseForControlChannelClose(ccEvent(TmuxDisconnectReason.CommandTimeout)),
        )
        assertEquals(
            DropCause.RemoteFailure("ServerExited"),
            SelfInflictedClose.dropCauseForControlChannelClose(ccEvent(TmuxDisconnectReason.ServerExited)),
        )
        assertEquals(
            DropCause.Unknown,
            SelfInflictedClose.dropCauseForControlChannelClose(ccEvent(TmuxDisconnectReason.Unknown)),
        )
        // A null event carries no evidence of intent -> conservative Unknown, still recovers.
        assertEquals(DropCause.Unknown, SelfInflictedClose.dropCauseForControlChannelClose(null))
    }

    @Test
    fun `control-channel boolean predicate delegates to the typed authority`() {
        for (reason in TmuxDisconnectReason.entries) {
            val event = ccEvent(reason)
            val isSelf = SelfInflictedClose.dropCauseForControlChannelClose(event) is DropCause.SelfInflicted
            assertEquals(isSelf, SelfInflictedClose.isSelfInflictedControlChannelClose(event))
        }
        assertEquals(false, SelfInflictedClose.isSelfInflictedControlChannelClose(null))
    }
}
