package com.pocketshell.core.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #842: a transcript image must be modeled (not dropped), across the
 * three shapes the agents emit — an image content block, a tool-result image,
 * and a pasted-image-by-absolute-path — for Claude Code / Codex / OpenCode.
 *
 * These are the red→green proofs: before the [ConversationImage] model + parser
 * wiring, every assertion on `.images` here returns an empty list (the image was
 * dropped). After, the image reference is carried on the event.
 */
class ConversationImageParsingTest {

    // ------------------------------------------------------------------
    // Claude Code
    // ------------------------------------------------------------------

    @Test
    fun claudeUserImageBlockByPathIsModeled() {
        val events = ClaudeCodeParser().parseLine(
            """
            {"type":"user","uuid":"u1","message":{"role":"user","content":[
              {"type":"text","text":"look at this"},
              {"type":"image","path":"/home/me/shot.png"}
            ]}}
            """.trimIndent(),
        )
        val message = events.filterIsInstance<ConversationEvent.Message>().first { it.text == "look at this" }
        assertEquals(1, message.images.size)
        assertEquals("/home/me/shot.png", message.images.single().path)
    }

    @Test
    fun claudeImageOnlyTurnEmitsImageCarryingMessage() {
        val events = ClaudeCodeParser().parseLine(
            """
            {"type":"user","uuid":"u2","message":{"role":"user","content":[
              {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}}
            ]}}
            """.trimIndent(),
        )
        val message = events.filterIsInstance<ConversationEvent.Message>().single()
        assertEquals("", message.text)
        val image = message.images.single()
        assertEquals("AAAA", image.base64Data)
        assertEquals("image/png", image.mediaType)
        assertNull(image.path)
    }

    @Test
    fun claudeToolResultImageIsModeled() {
        val events = ClaudeCodeParser().parseLine(
            """
            {"type":"user","uuid":"u3","message":{"role":"user","content":[
              {"type":"tool_result","tool_use_id":"t1","content":[
                {"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"ZZZZ"}}
              ]}
            ]}}
            """.trimIndent(),
        )
        val result = events.filterIsInstance<ConversationEvent.ToolResult>().single()
        assertEquals("t1", result.toolCallId)
        assertEquals("ZZZZ", result.images.single().base64Data)
        assertEquals("image/jpeg", result.images.single().mediaType)
    }

    @Test
    fun claudeDataUriUrlIsDecomposedToBase64() {
        val events = ClaudeCodeParser().parseLine(
            """
            {"type":"assistant","uuid":"a1","message":{"role":"assistant","content":[
              {"type":"image","url":"data:image/png;base64,QUJD"}
            ]}}
            """.trimIndent(),
        )
        val image = events.filterIsInstance<ConversationEvent.Message>().single().images.single()
        assertEquals("QUJD", image.base64Data)
        assertEquals("image/png", image.mediaType)
        assertNull(image.url)
    }

    // ------------------------------------------------------------------
    // Codex
    // ------------------------------------------------------------------

    @Test
    fun codexUserPastedImageByPathIsModeled() {
        val events = CodexParser().parseLine(
            """
            {"type":"message","role":"user","id":"m1","content":[
              {"type":"input_text","text":"see attached"},
              {"type":"input_image","path":"/tmp/paste-123.png"}
            ]}
            """.trimIndent(),
        )
        val message = events.filterIsInstance<ConversationEvent.Message>().single()
        assertEquals("see attached", message.text)
        assertEquals("/tmp/paste-123.png", message.images.single().path)
    }

    @Test
    fun codexImageUrlBlockIsModeled() {
        val events = CodexParser().parseLine(
            """
            {"type":"user_message","id":"m2","content":[
              {"type":"image_url","image_url":{"url":"https://example.com/a.png"}}
            ]}
            """.trimIndent(),
        )
        val message = events.filterIsInstance<ConversationEvent.Message>().single()
        assertEquals("https://example.com/a.png", message.images.single().url)
    }

    @Test
    fun codexFunctionCallOutputImageIsModeled() {
        val events = CodexParser().parseLine(
            """
            {"type":"function_call_output","call_id":"c1","output":[
              {"type":"image","path":"/srv/screenshot.png"}
            ]}
            """.trimIndent(),
        )
        val result = events.filterIsInstance<ConversationEvent.ToolResult>().single()
        assertEquals("/srv/screenshot.png", result.images.single().path)
    }

    // ------------------------------------------------------------------
    // OpenCode
    // ------------------------------------------------------------------

    @Test
    fun openCodeImagePartByPathIsModeled() {
        val events = OpenCodeReader().parseSqliteJsonRows(
            """
            [
              {"message_id":"om1","message_role":"user","part_id":"p1",
               "part_data":"{\"type\":\"image\",\"path\":\"/home/me/oc.png\"}"}
            ]
            """.trimIndent(),
        )
        val message = events.filterIsInstance<ConversationEvent.Message>().single()
        assertEquals("/home/me/oc.png", message.images.single().path)
    }

    @Test
    fun openCodeFileAttachmentWithImageMimeIsModeled() {
        val events = OpenCodeReader().parseSqliteJsonRows(
            """
            [
              {"message_id":"om2","message_role":"user","part_id":"p2",
               "part_data":"{\"type\":\"file\",\"mime\":\"image/png\",\"path\":\"/data/a.png\"}"}
            ]
            """.trimIndent(),
        )
        val message = events.filterIsInstance<ConversationEvent.Message>().single()
        assertEquals("/data/a.png", message.images.single().path)
        assertEquals("image/png", message.images.single().mediaType)
    }

    @Test
    fun openCodeToolResultImageIsModeled() {
        val events = OpenCodeReader().parseSqliteJsonRows(
            """
            [
              {"message_id":"om3","message_role":"assistant","part_id":"p3",
               "part_data":"{\"type\":\"tool_result\",\"tool_use_id\":\"t9\",\"content\":[{\"type\":\"image\",\"path\":\"/out/r.png\"}]}"}
            ]
            """.trimIndent(),
        )
        val result = events.filterIsInstance<ConversationEvent.ToolResult>().single()
        assertEquals("/out/r.png", result.images.single().path)
    }

    @Test
    fun openCodeNonImageFileAttachmentIsNotModeledAsImage() {
        val events = OpenCodeReader().parseSqliteJsonRows(
            """
            [
              {"message_id":"om4","message_role":"user","part_id":"p4",
               "part_data":"{\"type\":\"file\",\"mime\":\"application/pdf\",\"path\":\"/data/a.pdf\"}"}
            ]
            """.trimIndent(),
        )
        // A non-image attachment produces no image-carrying message.
        assertTrue(events.filterIsInstance<ConversationEvent.Message>().all { it.images.isEmpty() })
    }

    // ------------------------------------------------------------------
    // No false positives: plain text turns carry no images.
    // ------------------------------------------------------------------

    @Test
    fun plainTextTurnsHaveNoImages() {
        val claude = ClaudeCodeParser().parseLine(
            """{"type":"user","uuid":"u","message":{"role":"user","content":"hello"}}""",
        )
        assertTrue((claude.single() as ConversationEvent.Message).images.isEmpty())
    }
}
