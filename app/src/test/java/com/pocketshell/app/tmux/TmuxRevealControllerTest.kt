package com.pocketshell.app.tmux

import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.RevealState
import org.junit.Assert.assertEquals
import org.junit.Test

class TmuxRevealControllerTest {

    @Test
    fun `seed after exact generation adoption is accepted for the navigated session`() {
        val controller = TmuxRevealController(
            hostKeyForTarget = { target ->
                HostKey("${target.user}@${target.host}:${target.port}")
            },
        )
        val nameOnlyTarget = TmuxSessionViewModel.ConnectionTarget(
            hostId = 42L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "testuser",
            keyPath = "/keys/test",
            passphrase = null,
            sessionName = "work",
            startDirectory = null,
        )
        val exactTarget = nameOnlyTarget.copy(
            tmuxSessionId = "\$7",
            sessionCreated = 1_700_000_003L,
        )

        // This is the production order: navigation starts with the picker/name
        // identity, then pane reconciliation enriches the active target with
        // tmux's exact session generation before the first capture seed lands.
        controller.navigateTo(nameOnlyTarget)
        controller.adoptTargetIdentity(nameOnlyTarget, exactTarget)
        controller.offerSeed(exactTarget, paneId = "%0", frame = "shell prompt")

        assertEquals(
            "the exact-generation seed must reveal the session selected by name",
            RevealState.Live(
                targetId = controller.sessionId(exactTarget),
                targetName = "work",
                panes = listOf(
                    com.pocketshell.core.connection.Seed(
                        targetId = controller.sessionId(exactTarget),
                        paneId = "%0",
                        frame = "shell prompt",
                    ),
                ),
                agentName = null,
            ),
            controller.state.value,
        )
    }
}
