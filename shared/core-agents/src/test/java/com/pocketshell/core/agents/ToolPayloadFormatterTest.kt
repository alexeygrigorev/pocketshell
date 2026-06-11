package com.pocketshell.core.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPayloadFormatterTest {

    @Test
    fun formatsReadInputFilePathReadably() {
        val formatted = ToolPayloadFormatter.formatInput(
            """{"file_path":"/home/alexey/.pocketshell/attachments/host/shot.png"}""",
        )
        assertTrue(formatted.contains("\"file_path\""))
        assertTrue(formatted.contains("/home/alexey/.pocketshell/attachments/host/shot.png"))
        // Pretty-printed, not a single brace-soup line.
        assertTrue(formatted.contains("\n"))
    }

    @Test
    fun collapsesBase64ImageOutputToImageSummary() {
        val base64 = "iVBORw0KGgo" + "A".repeat(4_000)
        val output =
            """[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"$base64"}}]"""

        val formatted = ToolPayloadFormatter.formatOutput(output)

        assertTrue("expected [image] summary, got: $formatted", formatted.contains("[image"))
        assertTrue(formatted.contains("image/png"))
        // The multi-KB blob must never appear in the rendered text.
        assertFalse("base64 blob leaked into output", formatted.contains(base64))
        assertFalse(formatted.contains("A".repeat(100)))
    }

    @Test
    fun summarizesTextContentBlock() {
        val output = """[{"type":"text","text":"hello world"}]"""
        assertEquals("hello world", ToolPayloadFormatter.formatOutput(output))
    }

    @Test
    fun summarizesMixedTextAndImageBlocks() {
        val base64 = "Z".repeat(2_000)
        val output =
            """[{"type":"text","text":"saw this"},{"type":"image","source":{"type":"base64","data":"$base64"}}]"""

        val formatted = ToolPayloadFormatter.formatOutput(output)

        assertTrue(formatted.contains("saw this"))
        assertTrue(formatted.contains("[image"))
        assertFalse(formatted.contains(base64))
    }

    @Test
    fun elidesLongOpaqueBase64DataFieldInGenericObject() {
        val base64 = "Q".repeat(3_000)
        val input = """{"thumbnail":{"data":"$base64"}}"""

        val formatted = ToolPayloadFormatter.formatInput(input)

        assertFalse(formatted.contains(base64))
        assertTrue(formatted.contains("base64 data"))
        assertTrue(formatted.contains("3000"))
    }

    @Test
    fun keepsSmallJsonInputReadableAndIntact() {
        val formatted = ToolPayloadFormatter.formatInput(
            """{"command":"git status"}""",
        )
        assertTrue(formatted.contains("\"command\""))
        assertTrue(formatted.contains("git status"))
    }

    @Test
    fun passesNonJsonTextThroughUnchangedWhenShort() {
        val formatted = ToolPayloadFormatter.formatOutput("plain shell output line")
        assertEquals("plain shell output line", formatted)
    }

    @Test
    fun elidesRawNonJsonBase64Blob() {
        val blob = "iVBORw0KGgo" + "B".repeat(3_000)
        val formatted = ToolPayloadFormatter.formatOutput(blob)
        assertFalse(formatted.contains(blob))
        assertTrue(formatted.contains("base64 data"))
    }

    @Test
    fun emptyOutputStaysEmpty() {
        assertEquals("", ToolPayloadFormatter.formatOutput(""))
        assertEquals("", ToolPayloadFormatter.formatInput("   "))
    }

    @Test
    fun deterministicKeyOrderForNestedObjects() {
        val input = """{"file_path":"/a/b.kt","offset":10,"limit":50}"""
        val once = ToolPayloadFormatter.formatInput(input)
        val twice = ToolPayloadFormatter.formatInput(input)
        assertEquals(once, twice)
    }
}
