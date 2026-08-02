package com.pocketshell.app.proof

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Issue1733JourneyContractTest {

    private object SinkOutput : TerminalOutput() {
        override fun write(data: ByteArray?, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private fun terminal(rows: Int = 3): TerminalEmulator =
        TerminalEmulator(SinkOutput, 40, rows, 13, 15, rows * 4, null)

    private fun TerminalEmulator.feed(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        append(bytes, bytes.size)
    }

    @Test
    fun fakeAgentIdentityCarriesTheAuthoritativeRealPaneId() {
        val identity = Issue1733JourneyContract.parseFakeAgentTmuxIdentity(
            "setup chatter\n\$7:1784911681:%12\n",
        )

        assertEquals("\$7", identity.sessionId)
        assertEquals(1_784_911_681L, identity.sessionCreated)
        assertEquals("%12", identity.paneId)
    }

    @Test
    fun deliveryDeadlineAlwaysOutlivesTheProductionSendTimeout() {
        val timeout = Issue1733JourneyContract.deliveryTerminalTimeoutMs(
            productionSendTimeoutMs = 50_000L,
            environmentFloorMs = 45_000L,
        )

        assertEquals(65_000L, timeout)
        assertTrue(timeout > 50_000L)
    }

    @Test
    fun xmlFailureTextEscapesForbiddenControlsWithoutChangingRawInput() {
        val raw = "\u001B[H\u0000ready\tline\n"
        val safe = Issue1733JourneyContract.xmlSafeFailureText(raw)

        assertEquals("<ESC>[H<U+0000>ready\tline\n", safe)
        assertFalse(safe.contains('\u001B'))
        assertFalse(safe.contains('\u0000'))
        assertEquals('\u001B', raw.first())
    }

    @Test
    fun viewportCaptureOracleRejectsMarkerThatOnlySurvivesInScrollback() {
        val terminal = terminal()
        terminal.feed("issue1733-live-input\r\n")
        repeat(6) { terminal.feed("later-line-$it\r\n") }

        assertTrue(terminal.screen.transcriptText.contains("issue1733-live-input"))
        assertFalse(terminal.screen.visibleScreenText.contains("issue1733-live-input"))
    }

    @Test
    fun viewportCaptureOracleAcceptsMarkerInVisibleRows() {
        val terminal = terminal()
        terminal.feed("issue1733-live-input")

        assertTrue(terminal.screen.visibleScreenText.contains("issue1733-live-input"))
    }
}
