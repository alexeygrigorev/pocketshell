package com.pocketshell.app.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1854 / #2266 — pin the input-boundary policy that keeps two distinct
 * byte paths from being collapsed into one.
 *
 * The behavioural halves live in
 * [com.pocketshell.core.terminal.input.BracketedPasteTest] (the predicates),
 * [TmuxSessionViewModelInputTest] (the tmux command wire and the received
 * bytes), and `Issue2266Tmux37cFramedPasteDeliveryIntegrationTest` (the real
 * tmux 3.7c transport, when the integration gate is available). This is the
 * source-level guard for the policy: already-framed bytes must
 * use one exact raw-byte command, while newly framed composer bytes must keep
 * the atomic paste-buffer route.
 *
 * Each required invariant below also has a source-copy mutation in
 * [routePolicyKillsItsNamedMutations]. That is a deterministic selectivity
 * audit of this guard's oracle; it is not a Gradle mutation-test result.
 */
class Issue1854PasteFramingSourceGuardTest {

    /**
     * `TerminalView` must classify a commit by its CONTENT line breaks. Using
     * `containsLineBreak` here turns the everyday "type a command, press Enter"
     * gesture (an AOSP-family keyboard commits Enter as a literal `\n` appended
     * to the text) into a bracketed paste, which is never submitted.
     */
    @Test
    fun `TerminalView routes a paste on content line breaks, not any line break`() {
        val path = "shared/core-terminal/src/main/java/com/termux/view/TerminalView.java"
        val text = source(path)
        assertTrue(
            "$path must classify a multi-line paste with BracketedPaste.hasContentLineBreak " +
                "(issue #1854: a single TRAILING newline is a submit, not paste content)",
            text.contains("return BracketedPaste.hasContentLineBreak(text);"),
        )
        assertTrue(
            "$path must not reintroduce the any-LF paste classifier (issue #1854)",
            !text.contains("BracketedPaste.containsLineBreak(text)"),
        )
    }

    /**
     * The tmux lane must preserve an already-framed block without either
     * applying a second application frame (#1854) or sending it through tmux
     * 3.7c's non-byte-exact `paste-buffer -r` path (#2266).
     */
    @Test
    fun `the tmux input lane preserves an already-framed paste block byte-exact`() {
        val cmd = source(TMUX_INPUT_COMMANDS_PATH)
        assertEquals(emptyList<String>(), tmuxRouteViolations(cmd))
        assertTrue(
            "TmuxSessionViewModel must route pane input through deliverPaneInputBytes " +
                "(issue #1854)",
            source("app/src/main/java/com/pocketshell/app/tmux/TmuxSessionViewModel.kt")
                .contains("deliverPaneInputBytes(client, paneId, bytes)"),
        )
    }

    /**
     * Exercise the source oracle against named, one-at-a-time mutations. The
     * clean source is expected to have no violations; every mutation must
     * introduce exactly the policy violation it claims. This catches a guard
     * that merely asserts a nearby token while leaving the load-bearing route
     * unconstrained.
     */
    @Test
    fun `route policy kills its named source mutations`() {
        val clean = source(TMUX_INPUT_COMMANDS_PATH)
        assertEquals(emptyList<String>(), tmuxRouteViolations(clean))

        val framedBranch =
            "sendRawInputBytes(client, paneId, bytes)\n" +
                "        return\n" +
                "    }\n" +
                "    if (BracketedPaste.containsLineBreak(bytes))"
        val atomicPasteCall =
            "sendPasteBlock(client, paneId, BracketedPaste.frame(bytes), beforeCommit)"

        val mutations = listOf(
            SourceMutation(
                name = "already-framed bytes use paste-buffer",
                source = replaceOnce(
                    clean,
                    framedBranch,
                    framedBranch.replace(
                        "sendRawInputBytes(client, paneId, bytes)",
                        "sendPasteBlock(client, paneId, bytes)",
                    ),
                ),
                expectedViolations = listOf("already-framed raw-byte route"),
            ),
            SourceMutation(
                name = "raw helper drops a byte before encoding",
                source = replaceOnce(
                    clean,
                    "val hex = BracketedPaste.hex(bytes)",
                    "val hex = BracketedPaste.hex(bytes.dropLast(1))",
                ),
                expectedViolations = listOf("raw helper exact-byte encoding"),
            ),
            SourceMutation(
                name = "raw helper stops using send-keys -H",
                source = replaceOnce(
                    clean,
                    RAW_SEND_COMMAND,
                    RAW_SEND_COMMAND.replace("send-keys -H", "send-keys -l"),
                ),
                expectedViolations = listOf("raw helper send-keys -H command"),
            ),
            SourceMutation(
                name = "raw helper stops surfacing tmux errors",
                source = replaceOnce(clean, RAW_ERROR_CHECK, ""),
                expectedViolations = listOf("raw helper tmux-error propagation"),
            ),
            SourceMutation(
                name = "newly framed payloads stop using atomic paste-buffer",
                source = replaceOnce(
                    clean,
                    atomicPasteCall,
                    "sendRawInputBytes(client, paneId, bytes)",
                ),
                expectedViolations = listOf("newly framed atomic paste route"),
            ),
        )

        mutations.forEach { mutation ->
            assertTrue(
                "mutation `${mutation.name}` must change the source under audit",
                mutation.source != clean,
            )
            assertEquals(
                "mutation `${mutation.name}` must name exactly one load-bearing invariant",
                1,
                mutation.expectedViolations.size,
            )
            val actualViolations = tmuxRouteViolations(mutation.source)
            assertEquals(
                "mutation `${mutation.name}` must be rejected by the source oracle",
                mutation.expectedViolations,
                actualViolations,
            )
            println(
                "ISSUE2266_MUTATION_SELECTIVE name=${mutation.name} " +
                    "expected=${mutation.expectedViolations} actual=$actualViolations",
            )
        }
    }

    private fun tmuxRouteViolations(cmd: String): List<String> = buildList {
        val framedBranch = sourceSection(
            cmd,
            start = "if (BracketedPaste.isFramed(bytes)) {",
            end = "if (BracketedPaste.containsLineBreak(bytes)) {",
        )
        if (!framedBranch.contains("sendRawInputBytes(client, paneId, bytes)") ||
            framedBranch.contains("sendPasteBlock")
        ) {
            add("already-framed raw-byte route")
        }

        if (!cmd.contains("BracketedPaste.textChunks(framedBytes, TMUX_PASTE_BODY_CHUNK_BYTES)") ||
            !cmd.contains(ATOMIC_PASTE_CALL)
        ) {
            add("newly framed atomic paste route")
        }

        val rawHelper = sourceSection(
            cmd,
            start = "internal suspend fun sendRawInputBytes(",
            end = "/**\n * Issue #1636",
        )
        if (!rawHelper.contains("val hex = BracketedPaste.hex(bytes)")) {
            add("raw helper exact-byte encoding")
        }
        if (!cmd.contains(RAW_SEND_COMMAND)) {
            add("raw helper send-keys -H command")
        }
        if (!rawHelper.contains(RAW_ERROR_CHECK)) {
            add("raw helper tmux-error propagation")
        }
    }

    private fun sourceSection(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        if (startIndex < 0) return ""
        val bodyStart = startIndex + start.length
        val endIndex = source.indexOf(end, bodyStart)
        return if (endIndex < 0) source.substring(bodyStart) else source.substring(bodyStart, endIndex)
    }

    private fun replaceOnce(source: String, original: String, replacement: String): String {
        val first = source.indexOf(original)
        assertTrue("mutation anchor must exist: `$original`", first >= 0)
        assertEquals(
            "mutation anchor must be unique: `$original`",
            first,
            source.lastIndexOf(original),
        )
        return source.replaceRange(first, first + original.length, replacement)
    }

    private fun source(path: String): String {
        var cursor = java.io.File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val candidate = java.io.File(cursor, path)
            if (candidate.isFile) return candidate.readText()
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Cannot locate $path from ${System.getProperty("user.dir")}")
    }

    private data class SourceMutation(
        val name: String,
        val source: String,
        val expectedViolations: List<String>,
    )

    private companion object {
        const val TMUX_INPUT_COMMANDS_PATH =
            "app/src/main/java/com/pocketshell/app/tmux/TmuxInputCommands.kt"
        const val RAW_SEND_COMMAND =
            "client.sendKeysViaExec(\"send-keys -H -t \$paneId \$hex\")"
        const val RAW_ERROR_CHECK =
            "        .throwIfTmuxError(\"send raw bytes to pane \$paneId\")"
        const val ATOMIC_PASTE_CALL =
            "sendPasteBlock(client, paneId, BracketedPaste.frame(bytes), beforeCommit)"
    }
}
