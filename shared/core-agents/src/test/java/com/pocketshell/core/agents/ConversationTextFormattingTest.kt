package com.pocketshell.core.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTextFormattingTest {

    @Test
    fun stripsBareTaskIdWrapper() {
        val cleaned = ConversationTextFormatting.stripInternalProtocolNoise(
            "<task-id>a1887b43e9b725929</task-id>",
        )
        assertEquals("", cleaned)
    }

    @Test
    fun reportsBareTaskIdAsOnlyNoise() {
        assertTrue(
            ConversationTextFormatting.isOnlyInternalProtocolNoise(
                "<task-id>a1887b43e9b725929</task-id>",
            ),
        )
    }

    @Test
    fun keepsSurroundingProseAndDropsNoiseWrapper() {
        val cleaned = ConversationTextFormatting.stripInternalProtocolNoise(
            "Review #690 server reset\n<task-id>a1887b43e9b725929</task-id>",
        )
        assertEquals("Review #690 server reset", cleaned)
    }

    @Test
    fun proseIsNotConsideredOnlyNoise() {
        assertFalse(
            ConversationTextFormatting.isOnlyInternalProtocolNoise(
                "Review #690 server reset\n<task-id>a1887b43e9b725929</task-id>",
            ),
        )
    }

    @Test
    fun stripsMultipleInternalTagKinds() {
        val cleaned = ConversationTextFormatting.stripInternalProtocolNoise(
            "<task-id>abc</task-id>\n<tool-use-id>xyz</tool-use-id>\nkeep me",
        )
        assertEquals("keep me", cleaned)
    }

    @Test
    fun toleratesAttributesAndCaseInTagName() {
        assertTrue(
            ConversationTextFormatting.isOnlyInternalProtocolNoise(
                "<Task-Id foo=\"bar\">abc</Task-Id>",
            ),
        )
    }

    @Test
    fun leavesOrdinaryAngleBracketTextUntouched() {
        val text = "compare a < b and <html> sample"
        assertEquals(text, ConversationTextFormatting.stripInternalProtocolNoise(text))
        assertFalse(ConversationTextFormatting.isOnlyInternalProtocolNoise(text))
    }

    @Test
    fun blankInputIsNotFlaggedAsNoise() {
        assertFalse(ConversationTextFormatting.isOnlyInternalProtocolNoise(""))
        assertFalse(ConversationTextFormatting.isOnlyInternalProtocolNoise("   "))
    }
}
