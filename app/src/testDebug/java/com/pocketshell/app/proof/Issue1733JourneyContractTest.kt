package com.pocketshell.app.proof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Issue1733JourneyContractTest {

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
}
