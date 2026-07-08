package com.pocketshell.core.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

/**
 * Issue #1326 (S3): the SINGLE `Throwable -> FailureReason` classifier. These pin
 * the typed reason contract that supersedes the VM's `nonRetryableReason` string
 * table — class-covering (auth / host / server / key / session / the closed-
 * transport default) so the whole failure CLASS is typed, not just one instance.
 */
class FailureReasonTest {

    // Exceptions whose SIMPLE NAME the classifier matches (it matches on the name so
    // core-connection need not depend on sshj / core-tmux types).
    private class UserAuthException(message: String) : Exception(message)
    private class TmuxServerDeadException(message: String) : Exception(message)

    @Test
    fun classifiesAuthFailure() {
        assertEquals(FailureReason.AuthFailed, classifyFailure(UserAuthException("auth")))
    }

    @Test
    fun classifiesUnknownHost() {
        assertEquals(FailureReason.HostUnresolved, classifyFailure(UnknownHostException("nope")))
    }

    @Test
    fun classifiesServerDeath() {
        assertEquals(FailureReason.ServerRestarted, classifyFailure(TmuxServerDeadException("no server running")))
    }

    @Test
    fun classifiesMissingKeyFromMessageFragment() {
        // The key-not-found case is a plain IOException identified by message text.
        assertEquals(
            FailureReason.KeyMissing,
            classifyFailure(IOException("Private key file not found: /keys/id_ed25519")),
        )
    }

    @Test
    fun classifiesSessionEndedFromMessageFragment() {
        assertEquals(FailureReason.SessionEnded, classifyFailure(IOException("can't find session: work")))
        assertEquals(FailureReason.SessionEnded, classifyFailure(IOException("no such session")))
    }

    @Test
    fun closedTransportPreflightDefaultsToRetryableUnreachable_1321Repro() {
        // The #1321 leak: a `TmuxClientException: failed to preflight tmux has-session
        // ... transport is closed`. It has none of the non-retryable signatures, so it
        // classifies to the CALM, retryable Unreachable — the "Tap Reconnect" state,
        // never a scary raw exception surfaced to the UI.
        val closedTransport = IOException("failed to preflight tmux has-session -t work: transport is closed")
        val reason = classifyFailure(closedTransport)
        assertEquals(FailureReason.Unreachable(retryable = true), reason)
        assertTrue("a closed-transport drop is auto-retryable", reason.retryable)
    }

    @Test
    fun nullCauseIsRetryableUnreachable() {
        assertEquals(FailureReason.Unreachable(retryable = true), classifyFailure(null))
    }

    @Test
    fun walksTheCauseChain() {
        val nested = RuntimeException("wrapper", UserAuthException("deep auth reject"))
        assertEquals(FailureReason.AuthFailed, classifyFailure(nested))
    }

    @Test
    fun retryableFlag_configLevelReasonsAreNotAutoRetryable() {
        // Class coverage: every config-level reason is non-retryable (user must act);
        // only a transient Unreachable is auto-retryable.
        assertFalse(FailureReason.AuthFailed.retryable)
        assertFalse(FailureReason.HostUnresolved.retryable)
        assertFalse(FailureReason.ServerRestarted.retryable)
        assertFalse(FailureReason.SessionEnded.retryable)
        assertFalse(FailureReason.KeyMissing.retryable)
        assertFalse(FailureReason.Unreachable(retryable = false).retryable)
        assertTrue(FailureReason.Unreachable(retryable = true).retryable)
    }
}
