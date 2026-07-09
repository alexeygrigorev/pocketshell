package com.pocketshell.app.tmux

import com.pocketshell.core.tmux.TmuxClientException
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.transport.TransportException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TmuxSessionViewModelStaleChannelSymptomTest : TmuxSessionViewModelTestBase() {
    @Test
    fun staleChannelSymptomMatchesTransportDeadSpawnDisconnected() {
        // Issue #665 / #636: the transport-DEAD attach variant. When the pooled
        // SSH transport has silently died, the switch's `tmux -CC` spawn throws
        // `TmuxClientException("failed to spawn tmux -CC: Disconnected",
        // <TransportException [BY_APPLICATION] Disconnected>)`. The merged #621
        // heal only matched "open failed" / EOF-write / command-timeout, so this
        // slipped through and the switch stranded on the PREVIOUS session. The
        // extended matcher must recognise it so the dead lease is evicted + the
        // attach re-dialled on a fresh transport.
        val vm = newVm()
        val transportDead = TransportException(DisconnectReason.BY_APPLICATION, "Disconnected")
        val spawnFailure = TmuxClientException(
            "failed to spawn tmux -CC: ${transportDead.message}",
            transportDead,
        )
        assertTrue(
            "the `failed to spawn tmux -CC: Disconnected` (TransportException " +
                "[BY_APPLICATION]) attach variant must be treated as a stale-channel " +
                "symptom so the dead lease is evicted + re-dialled",
            vm.isStaleChannelSymptom(spawnFailure),
        )
    }

    @Test
    fun staleChannelSymptomMatchesBareTransportException() {
        // Even without the spawn wrapper, a bare sshj TransportException whose
        // disconnect reason is BY_APPLICATION (the dead-transport-during-attach
        // shape) is a stale-channel symptom - covers the deeper-wrap path.
        val vm = newVm()
        val transportDead = TransportException(DisconnectReason.BY_APPLICATION, "Disconnected")
        val wrapped = TmuxClientException("attach failed", transportDead)
        assertTrue(
            "a BY_APPLICATION TransportException in the cause chain is a stale-channel symptom",
            vm.isStaleChannelSymptom(wrapped),
        )
    }

    @Test
    fun staleChannelSymptomDoesNotMatchBenignFailures() {
        // Scope guard: the new matcher must NOT fire for failures that are not
        // the dead-transport attach symptom - a plain IO blip, a non-application
        // transport reason without "Disconnected", or null. Over-matching would
        // burn auto-recovery re-dials on failures that should surface normally.
        val vm = newVm()
        assertFalse(
            "a generic IOException is not a stale-channel symptom",
            vm.isStaleChannelSymptom(IOException("connection reset by peer")),
        )
        assertFalse(
            "a non-application transport reason without Disconnected text is not a symptom",
            vm.isStaleChannelSymptom(
                TransportException(DisconnectReason.PROTOCOL_ERROR, "protocol error"),
            ),
        )
        assertFalse(
            "null cause is not a stale-channel symptom",
            vm.isStaleChannelSymptom(null),
        )
    }

    @Test
    fun staleChannelSymptomStillMatchesOpenFailureAndEof() {
        // Regression guard: extending the matcher must not drop the pre-#665
        // variants the #621/#465 heal already recognised.
        val vm = newVm()
        assertTrue(
            "open-failed (channel-open failure) must still be a stale-channel symptom",
            vm.isStaleChannelSymptom(TmuxClientException("open failed")),
        )
        assertTrue(
            "tmux EOF-write failure must still be a stale-channel symptom",
            vm.isStaleChannelSymptom(
                TmuxClientException("failed to write tmux command `list-panes`: EOF"),
            ),
        )
        assertTrue(
            "tmux command timeout must still be a stale-channel symptom",
            vm.isStaleChannelSymptom(
                TmuxClientException("tmux command `list-panes` timed out after 100ms"),
            ),
        )
    }
}
