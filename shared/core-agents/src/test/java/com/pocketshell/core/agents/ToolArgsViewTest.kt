package com.pocketshell.core.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgsViewTest {

    /**
     * The reported case: Codex `exec_command` args dumped as a raw JSON blob.
     * The command must come out as a clean command line and the rest as labeled
     * fields — NOT a `{"cmd":...}` brace soup.
     */
    @Test
    fun codexExecCommandSurfacesCommandAndLabeledFields() {
        val input =
            """{"cmd":"gh run list --limit 25 --json status","timeout_ms":10000,"cwd":"/home/alexey/git/pocketshell"}"""

        val view = ToolArgsView.forInput("exec_command", input)

        assertTrue("expected Structured, got $view", view is ToolArgsView.Structured)
        view as ToolArgsView.Structured
        assertEquals("gh run list --limit 25 --json status", view.command)
        // No brace-soup envelope leaked into the command.
        assertFalse(view.command!!.contains("{"))
        assertFalse(view.command!!.contains("\"cmd\""))
        // The other fields are labeled rows, command key excluded.
        val labels = view.fields.map { it.label }
        assertEquals(listOf("timeout_ms", "cwd"), labels)
        assertEquals("10000", view.fields.first { it.label == "timeout_ms" }.value)
        assertEquals("/home/alexey/git/pocketshell", view.fields.first { it.label == "cwd" }.value)
    }

    @Test
    fun claudeBashCommandKeyIsAlsoStructured() {
        val view = ToolArgsView.forInput("Bash", """{"command":"git status","description":"check"}""")

        assertTrue(view is ToolArgsView.Structured)
        view as ToolArgsView.Structured
        assertEquals("git status", view.command)
        assertEquals(listOf("description"), view.fields.map { it.label })
    }

    @Test
    fun argvArrayCommandIsJoinedIntoOneLine() {
        val view = ToolArgsView.forInput(
            "exec_command",
            """{"cmd":["bash","-lc","gh run list"],"timeout_ms":5000}""",
        )

        assertTrue(view is ToolArgsView.Structured)
        view as ToolArgsView.Structured
        assertEquals("bash -lc gh run list", view.command)
        assertEquals(listOf("timeout_ms"), view.fields.map { it.label })
    }

    @Test
    fun objectWithNoCommandStillRendersLabeledFields() {
        val view = ToolArgsView.forInput(
            "Read",
            """{"file_path":"/a/b.kt","offset":10,"limit":50}""",
        )

        assertTrue(view is ToolArgsView.Structured)
        view as ToolArgsView.Structured
        assertNull(view.command)
        assertEquals(listOf("file_path", "offset", "limit"), view.fields.map { it.label })
        assertEquals("/a/b.kt", view.fields.first { it.label == "file_path" }.value)
    }

    @Test
    fun nestedObjectFieldIsPrettyPrintedNotBlobbed() {
        val view = ToolArgsView.forInput(
            "exec_command",
            """{"cmd":"echo hi","env":{"KEY":"value","OTHER":"x"}}""",
        )

        assertTrue(view is ToolArgsView.Structured)
        view as ToolArgsView.Structured
        assertEquals("echo hi", view.command)
        val env = view.fields.first { it.label == "env" }.value
        // Pretty-printed nested object spans lines, not one brace-soup line.
        assertTrue("expected pretty-printed nested object, got: $env", env.contains("\n"))
        assertTrue(env.contains("\"KEY\""))
    }

    @Test
    fun base64BlobFieldIsElidedNeverDumped() {
        val base64 = "Q".repeat(3_000)
        val view = ToolArgsView.forInput(
            "exec_command",
            """{"cmd":"upload","payload":{"data":"$base64"}}""",
        )

        assertTrue(view is ToolArgsView.Structured)
        view as ToolArgsView.Structured
        val payload = view.fields.first { it.label == "payload" }.value
        assertFalse("base64 blob leaked into field", payload.contains(base64))
        assertTrue(payload.contains("base64 data"))
    }

    /** Parse failure must degrade to pretty/elided text, never the raw one-liner. */
    @Test
    fun malformedJsonDegradesToRaw() {
        val malformed = """{"cmd":"oops" broken json"""
        val view = ToolArgsView.forInput("exec_command", malformed)

        assertTrue("expected Raw, got $view", view is ToolArgsView.Raw)
        view as ToolArgsView.Raw
        // Falls back to the same formatter text the card showed before.
        assertEquals(ToolPayloadFormatter.formatInput(malformed), view.text)
    }

    @Test
    fun nonObjectJsonArrayDegradesToRaw() {
        val view = ToolArgsView.forInput("tool", """["a","b"]""")
        assertTrue(view is ToolArgsView.Raw)
    }

    @Test
    fun plainTextArgsDegradeToRaw() {
        val view = ToolArgsView.forInput("tool", "just some text")
        assertTrue(view is ToolArgsView.Raw)
        view as ToolArgsView.Raw
        assertEquals("just some text", view.text)
    }

    @Test
    fun emptyObjectDegradesToRaw() {
        val view = ToolArgsView.forInput("tool", "{}")
        assertTrue(view is ToolArgsView.Raw)
    }

    @Test
    fun deterministicAcrossCalls() {
        val input = """{"cmd":"ls","timeout_ms":1000,"cwd":"/tmp"}"""
        assertEquals(
            ToolArgsView.forInput("exec_command", input),
            ToolArgsView.forInput("exec_command", input),
        )
    }
}
