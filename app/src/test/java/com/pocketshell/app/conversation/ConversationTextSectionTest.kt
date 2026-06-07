package com.pocketshell.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTextSectionTest {

    @Test
    fun keepsSmallBodyUnchanged() {
        val result = conversationTextSectionDisplayBody("short output\nwith two lines")

        assertFalse(result.wasTruncated)
        assertEquals("short output\nwith two lines", result.text)
    }

    @Test
    fun truncatesHugeBodyForRendering() {
        val body = buildString {
            repeat(5_100) { append('x') }
            append("\nfull output tail")
        }

        val result = conversationTextSectionDisplayBody(body)

        assertTrue(result.wasTruncated)
        assertTrue(result.text.length < body.length)
        assertTrue(result.text.endsWith("[Output truncated in view. Copy for full text.]"))
    }
}
