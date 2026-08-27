package com.pocketshell.app.tmux

import com.pocketshell.core.ssh.ExecResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #2240 reproduce-first regression.
 *
 * These assertions deliberately use the existing HostAck seam and the real
 * result mapper. On the current base, exit 5 is classified as the ordinary
 * failure and this test is RED; a later production fix must make both layers
 * preserve the host's unresolved outcome.
 */
class Issue2240HostAckUnknownOutcomeTest {

    @Test
    fun exitFiveIsClassifiedAsAnUnknownOutcomeOnTheHostAckSeam() {
        val result = classifyHostAckSend(
            ExecResult(
                stdout = "send-interrupted\n",
                stderr = "payload may already be in the pane",
                exitCode = HOST_ACK_EXIT_SEND_INTERRUPTED,
            ),
        )

        assertEquals("UnknownMayHaveLanded", result.javaClass.simpleName)
    }

    @Test
    fun exitFiveSurvivesTheProductionSendResultMapping() = runTest {
        val result = deliverViaHostAck(
            exec = HostAckSendExec { _, _ ->
                ExecResult(
                    stdout = "send-interrupted\n",
                    stderr = "payload may already be in the pane",
                    exitCode = HOST_ACK_EXIT_SEND_INTERRUPTED,
                )
            },
            paneId = "%0",
            token = "row-2240-red",
            payload = "prompt whose landing is unknown",
            withEnter = true,
        ).toComposerSendResult()

        assertEquals("UnknownMayHaveLanded", result.name)
    }
}
