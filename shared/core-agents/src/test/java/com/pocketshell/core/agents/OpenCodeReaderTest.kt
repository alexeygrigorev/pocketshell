package com.pocketshell.core.agents

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeReaderTest {
    @Test
    fun convertsOpenCodeRowsToMessages() {
        val events = OpenCodeReader().parseRows(
            listOf(
                OpenCodeRow(id = "u1", role = "user", content = "fix tests", createdAtMillis = 12L),
                OpenCodeRow(id = "a1", role = "assistant", content = "I'll run them.", createdAtMillis = 13L),
            ),
        )

        assertEquals(2, events.size)
        assertEquals(ConversationRole.User, (events[0] as ConversationEvent.Message).role)
        assertEquals(AgentKind.OpenCode, events[1].agent)
        assertEquals("I'll run them.", (events[1] as ConversationEvent.Message).text)
    }
}
