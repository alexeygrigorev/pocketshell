package com.pocketshell.core.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallSummaryTest {
    @Test
    fun bashSurfacesCommandTruncatedAtSixtyChars() {
        val event = toolCall(
            name = "Bash",
            input = """{"command":"git status --porcelain --untracked-files=all"}""",
        )

        assertEquals(
            "git status --porcelain --untracked-files=all",
            ToolCallSummary.forToolCall(event),
        )
    }

    @Test
    fun bashTruncatesLongCommands() {
        val long = "a".repeat(120)
        val event = toolCall(
            name = "Bash",
            input = """{"command":"$long"}""",
        )

        val summary = ToolCallSummary.forToolCall(event)
        assertTrue("summary too long: $summary", summary.length <= 60)
        assertTrue("ellipsis missing: $summary", summary.endsWith("…"))
    }

    @Test
    fun readShortensLongPathsToBasename() {
        val event = toolCall(
            name = "Read",
            input = """{"file_path":"/home/alexey/git/pocketshell/app/src/main/java/com/pocketshell/app/session/SessionScreen.kt"}""",
        )

        assertEquals("SessionScreen.kt", ToolCallSummary.forToolCall(event))
    }

    @Test
    fun readKeepsShortPathsIntact() {
        val event = toolCall(name = "Read", input = """{"file_path":"build.gradle.kts"}""")

        assertEquals("build.gradle.kts", ToolCallSummary.forToolCall(event))
    }

    @Test
    fun readAppendsLineRange() {
        val event = toolCall(
            name = "Read",
            input = """{"file_path":"file.kt","offset":40,"limit":20}""",
        )

        assertEquals("file.kt (L40-60)", ToolCallSummary.forToolCall(event))
    }

    @Test
    fun editShowsLineDiffSummary() {
        val event = toolCall(
            name = "Edit",
            input = """{"file_path":"app/src/Foo.kt","old_string":"a\nb\nc","new_string":"x\ny"}""",
        )

        // Short paths (<= 40 chars) keep their full relative path; the
        // diff summary follows.
        assertEquals("app/src/Foo.kt (+2/-3)", ToolCallSummary.forToolCall(event))
    }

    @Test
    fun editTrimsLongPathsToBasename() {
        val event = toolCall(
            name = "Edit",
            input = """{"file_path":"/home/alexey/git/pocketshell/app/src/main/java/com/pocketshell/app/session/SessionViewModel.kt","old_string":"a","new_string":"b\nc"}""",
        )

        assertEquals("SessionViewModel.kt (+2/-1)", ToolCallSummary.forToolCall(event))
    }

    @Test
    fun writeShowsLineCount() {
        val event = toolCall(
            name = "Write",
            input = """{"file_path":"x.txt","content":"line1\nline2\nline3"}""",
        )

        assertEquals("x.txt (3 lines)", ToolCallSummary.forToolCall(event))
    }

    @Test
    fun grepShowsPatternAndTarget() {
        val event = toolCall(
            name = "Grep",
            input = """{"pattern":"foo.*bar","path":"src"}""",
        )

        assertEquals("\"foo.*bar\" in src", ToolCallSummary.forToolCall(event))
    }

    @Test
    fun globShowsPatternAlone() {
        val event = toolCall(
            name = "Glob",
            input = """{"pattern":"**/*.kt"}""",
        )

        assertEquals("**/*.kt", ToolCallSummary.forToolCall(event))
    }

    @Test
    fun webFetchShortensLongUrlsToHost() {
        val event = toolCall(
            name = "WebFetch",
            input = """{"url":"https://example.com/some/very/very/long/path/with/many/segments?q=value&page=42"}""",
        )

        assertEquals("example.com", ToolCallSummary.forToolCall(event))
    }

    @Test
    fun webFetchKeepsShortUrlsIntact() {
        val event = toolCall(name = "WebFetch", input = """{"url":"https://x.com/y"}""")

        assertEquals("https://x.com/y", ToolCallSummary.forToolCall(event))
    }

    @Test
    fun taskShowsSubagentAndDescription() {
        val event = toolCall(
            name = "Task",
            input = """{"subagent_type":"general-purpose","description":"Audit gradle modules"}""",
        )

        assertEquals(
            "general-purpose: Audit gradle modules",
            ToolCallSummary.forToolCall(event),
        )
    }

    @Test
    fun unknownToolUsesNamePlusInputPreview() {
        val event = toolCall(
            name = "MysteryTool",
            input = "raw payload that is not JSON at all",
        )

        assertEquals(
            "MysteryTool: raw payload that is not JSON at all",
            ToolCallSummary.forToolCall(event),
        )
    }

    @Test
    fun toleratesInvalidJsonInBashInput() {
        val event = toolCall(name = "Bash", input = "not-json-at-all")

        val summary = ToolCallSummary.forToolCall(event)
        assertEquals("not-json-at-all", summary)
    }

    @Test
    fun bashWithoutKnownFieldUsesRawInput() {
        val event = toolCall(name = "Bash", input = """{"foo":"bar"}""")

        assertEquals("""{"foo":"bar"}""", ToolCallSummary.forToolCall(event))
    }

    private fun toolCall(name: String, input: String): ConversationEvent.ToolCall =
        ConversationEvent.ToolCall(
            id = "test:$name",
            agent = AgentKind.ClaudeCode,
            name = name,
            input = input,
        )
}
