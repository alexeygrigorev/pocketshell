package com.pocketshell.app.proof

import com.pocketshell.core.agents.ClaudeCodeParser
import com.pocketshell.core.agents.ConversationEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/** Issue #1526: the Docker submit oracle must emit parseable multiline JSONL. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FakeAgentTranscriptJsonTest {

    @Test
    fun multilineSubmitIsJsonEscapedAndParsedAsTheExactClaudeUserTurn() {
        val script = projectRoot().resolve(FAKE_AGENT_SCRIPT)
        val source = String(Files.readAllBytes(script), Charsets.UTF_8)
        assertTrue(
            "the live transcript write must use the tested JSON encoder",
            source.contains("transcript_text=\$(json_escape \"\$submit_buffer\")"),
        )

        val payload = "first line\nsecond \"quoted\" \\ path\tend"
        val command = """
            set -eu
            LF=${'$'}'\n'
            CR=${'$'}'\r'
            source <(sed -n '/^json_escape()/,/^}/p' "${'$'}1")
            json_escape "${'$'}PAYLOAD"
        """.trimIndent()
        val process = ProcessBuilder("bash", "-c", command, "fake-agent-json-test", script.toString())
            .directory(projectRoot().toFile())
            .redirectErrorStream(true)
            .apply { environment()["PAYLOAD"] = payload }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val completed = process.waitFor(10, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        assertTrue("fixture JSON encoder timed out", completed)
        assertEquals("fixture JSON encoder failed: $output", 0, process.exitValue())

        val jsonl =
            """{"uuid":"fake-agent-user-1","timestamp":"2026-08-03T12:00:00Z","message":{"role":"user","content":"$output"}}"""
        val parsed = ClaudeCodeParser().parseLine(jsonl)
            .filterIsInstance<ConversationEvent.Message>()
            .single()
        assertEquals(payload, parsed.text)
    }

    @Test
    fun tmux37cCaretEncodedBracketedPasteCollapsesToChip() {
        val script = projectRoot().resolve(FAKE_AGENT_SCRIPT)
        // tmux 3.7c paste-buffer vis-encodes ESC as `^[` (issue #2205).
        val caretFramed = "^[[200~echo line1\necho line2^[[201~"
        val process = ProcessBuilder(script.toString())
            .directory(projectRoot().toFile())
            .redirectErrorStream(true)
            .start()
        process.outputStream.use { it.write(caretFramed.toByteArray(Charsets.UTF_8)) }
        val completed = process.waitFor(5, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue("fake-agent timed out on caret-encoded paste: $output", completed)
        assertTrue(
            "tmux 3.7c caret-ESC framed paste must collapse to the Claude chip, got: $output",
            output.contains("[Pasted text #1 +1 lines]"),
        )
        assertTrue(
            "caret-encoded paste must not submit on the embedded newline, got: $output",
            !output.contains("FAKE-AGENT SUBMITTED:"),
        )
    }

    private fun projectRoot(): Path {
        var candidate: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (candidate != null) {
            if (Files.exists(candidate.resolve(FAKE_AGENT_SCRIPT))) return candidate
            candidate = candidate.parent
        }
        error("Could not locate $FAKE_AGENT_SCRIPT from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val FAKE_AGENT_SCRIPT = "tests/docker/agent-bin/pocketshell-fake-agent"
    }
}
