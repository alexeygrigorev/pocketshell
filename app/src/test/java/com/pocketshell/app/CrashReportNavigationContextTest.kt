package com.pocketshell.app

import com.pocketshell.app.nav.AppDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class CrashReportNavigationContextTest {
    @Test
    fun tmuxDestinationCapturesIdentifyingCrashContext() {
        val context = AppDestination.TmuxSession(
            hostId = 7L,
            hostName = "devbox",
            hostname = "dev.example",
            port = 22,
            username = "alexey",
            keyPath = "/keys/dev",
            passphrase = null,
            sessionName = "agent-main",
            startDirectory = "/home/alexey/git/pocketshell",
        ).crashReportContext()

        assertEquals("Tmux session", context.screen)
        assertEquals("devbox", context.hostName)
        assertEquals("dev.example", context.hostname)
        assertEquals("alexey", context.username)
        assertEquals("agent-main", context.sessionName)
        assertEquals("/home/alexey/git/pocketshell", context.startDirectory)
        assertEquals(
            "Tmux session · host=devbox · session=agent-main · cwd=/home/alexey/git/pocketshell",
            context.summary(),
        )
    }
}
