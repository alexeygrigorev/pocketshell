package com.pocketshell.app.tmux

import com.pocketshell.app.sessions.HostTmuxSessionPickerRequest
import com.pocketshell.app.sessions.HostTmuxSessionPickerState
import com.pocketshell.app.sessions.HostTmuxSessionRow
import com.pocketshell.core.storage.entity.HostEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxSessionSwitcherPagesTest {
    @Test
    fun switcherPagesPutCurrentFirstSoOneSwipeLeftIsNotANamedPeerWhenAThirdSessionExists() {
        // Issue #2173r: the OutboundExactlyOnce switcher helper assumed one
        // swipeLeft() from B lands on renamed-A. Pages are current-first, then
        // the picker row order, so a leftover fixture session (claude-main)
        // sits at page 2 and the named peer is further right.
        val pages = sessionSwitcherPages(
            state = HostTmuxSessionPickerState.Ready(
                request = pickerRequest(),
                rows = listOf(
                    HostTmuxSessionRow(
                        name = "claude-main",
                        tmuxSessionId = "\$2",
                        createdAt = 1_787_881_769L,
                    ),
                    HostTmuxSessionRow(
                        name = "issue1944-switch-b",
                        tmuxSessionId = "\$47",
                        createdAt = 1_787_882_784L,
                    ),
                    HostTmuxSessionRow(
                        name = "issue1526-exactly-once-renamed",
                        tmuxSessionId = "\$0",
                        createdAt = 1_787_876_465L,
                    ),
                ),
            ),
            currentSessionName = "issue1944-switch-b",
        )
        assertEquals("issue1944-switch-b", pages[0].name)
        assertEquals(
            "adjacent swipe-left from B is the leftover fixture, not renamed A",
            "claude-main",
            pages[1].name,
        )
        assertTrue(
            pages.indexOfFirst { it.name == "issue1526-exactly-once-renamed" } > 1,
        )
    }

    private fun pickerRequest(): HostTmuxSessionPickerRequest =
        HostTmuxSessionPickerRequest(
            host = HostEntity(
                id = 1L,
                name = "alpha",
                hostname = "alpha.example",
                port = 22,
                username = "alex",
                keyId = 1L,
            ),
            keyPath = "/keys/alpha",
            passphrase = null,
        )
}
