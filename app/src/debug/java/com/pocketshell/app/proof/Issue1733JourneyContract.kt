package com.pocketshell.app.proof

/**
 * Pure, debug-only contracts used by the issue #1733 connected journey.
 *
 * Keeping these transformations outside the instrumentation class makes the
 * pane identity, deadline ordering, and XML-safe failure rendering directly
 * unit-testable without adding a production test seam.
 */
internal object Issue1733JourneyContract {

    data class FakeAgentTmuxIdentity(
        val sessionId: String,
        val sessionCreated: Long,
        val paneId: String,
    )

    private val identityLine = Regex("""^(\${'$'}\d+):(\d+):(%\d+)$""")

    fun parseFakeAgentTmuxIdentity(output: String): FakeAgentTmuxIdentity {
        val match = output.lineSequence()
            .map(String::trim)
            .mapNotNull(identityLine::matchEntire)
            .lastOrNull()
            ?: error("fake-agent setup did not return session_id:session_created:pane_id")
        return FakeAgentTmuxIdentity(
            sessionId = match.groupValues[1],
            sessionCreated = match.groupValues[2].toLong(),
            paneId = match.groupValues[3],
        )
    }

    /**
     * The journey must never declare delivery stuck before production's own
     * dispatcher timeout can defer the row. The additional headroom covers the
     * durable state write and the server transcript becoming observable.
     */
    fun deliveryTerminalTimeoutMs(
        productionSendTimeoutMs: Long,
        environmentFloorMs: Long,
        headroomMs: Long = 15_000L,
    ): Long {
        require(productionSendTimeoutMs > 0L)
        require(environmentFloorMs > 0L)
        require(headroomMs > 0L)
        return maxOf(environmentFloorMs, productionSendTimeoutMs + headroomMs)
    }

    /**
     * JUnit XML is XML 1.0: C0 controls such as ESC are forbidden even as a
     * numeric character reference. Raw artifacts remain untouched; only text
     * crossing the assertion/report boundary uses this printable form.
     */
    fun xmlSafeFailureText(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            when {
                ch == '\u001B' -> append("<ESC>")
                ch == '\t' || ch == '\n' || ch == '\r' -> append(ch)
                ch < '\u0020' || ch in '\uD800'..'\uDFFF' || ch == '\uFFFE' || ch == '\uFFFF' ->
                    append("<U+${ch.code.toString(16).uppercase().padStart(4, '0')}>")
                else -> append(ch)
            }
        }
    }
}
