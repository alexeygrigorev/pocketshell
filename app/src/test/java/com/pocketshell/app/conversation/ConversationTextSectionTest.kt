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
        assertFalse(result.text.contains("Copy for full text"))
    }

    @Test
    fun truncatesHugeExpandedMessageForRendering() {
        val body = buildString {
            append("assistant answer\n")
            repeat(5_100) { append('x') }
            append("\nfull message tail")
        }

        val result = conversationExpandedMessageDisplayBody(body)

        assertTrue(result.wasTruncated)
        assertTrue(result.text.length < body.length)
        assertTrue(result.text.startsWith("assistant answer"))
        assertFalse(result.text.contains("full message tail"))
        assertFalse(result.text.contains("Copy for full text"))
    }

    @Test
    fun characterAndLineCapsBothKeepThePreviewBounded() {
        val characterLimited = conversationExpandedMessageDisplayBody("x".repeat(5_001))
        val lineLimited = conversationExpandedMessageDisplayBody(
            buildString {
                repeat(201) { appendLine("line-$it") }
                append("tail")
            },
        )

        assertTrue(characterLimited.wasTruncated)
        assertTrue(characterLimited.text.length <= 5_000)
        assertTrue(lineLimited.wasTruncated)
        assertFalse(lineLimited.text.contains("tail"))
    }

    @Test
    fun stripsInternalProtocolNoiseFromExpandedMessageBody() {
        val result = conversationExpandedMessageDisplayBody(
            "Review #690 server reset\n<task-id>a1887b43e9b725929</task-id>",
        )

        assertFalse(result.wasTruncated)
        assertEquals("Review #690 server reset", result.text)
        assertFalse(result.text.contains("task-id"))
    }

    @Test
    fun protocolWrapperSpanningCleaningBoundDoesNotLeakOrHideVisibleAnswer() {
        val result = conversationExpandedMessageDisplayBody(
            "<task-id>${"opaque".repeat(4_167)}</task-id>\nVISIBLE ANSWER",
        )

        assertFalse(result.wasTruncated)
        assertEquals("VISIBLE ANSWER", result.text)
        assertFalse(result.text.contains("task-id"))
        assertFalse(result.text.contains("opaque"))
    }

    @Test
    fun fullTextChunksRejoinExactlyAndBoundEveryTextNode() {
        val body = buildString {
            append("prefix\uD83D\uDE80")
            repeat(7_000) { append(('a'.code + (it % 26)).toChar()) }
            repeat(170) { append("\nline-$it") }
            append("\uD83E\uDDEA-tail")
        }

        val chunks = conversationFullTextChunks(body)
        val materialized = (0 until chunks.size).map(chunks::get)

        assertEquals(body, materialized.joinToString(separator = ""))
        assertTrue(materialized.all { it.length <= FULL_TEXT_CHUNK_CHAR_LIMIT + 1 })
        assertTrue(
            materialized.all { chunk -> chunk.count { it == '\n' } <= FULL_TEXT_CHUNK_LINE_LIMIT },
        )
        assertTrue(materialized.none { chunk ->
            chunk.lastOrNull()?.isHighSurrogate() == true
        })
    }
}
