package com.pocketshell.app.tmux

import com.pocketshell.core.terminal.input.BracketedPaste
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1845 — tmux deletes a TRAILING `;` from a command `argv` element.
 *
 * `tmux send-keys -l -t %0 -- 'abc;'` types `abc`, reports SUCCESS, and the `;`
 * is gone. That is the byte the maintainer saw disappear from a typed terminal
 * command: the app's per-pane input queue batches keystrokes by arrival timing,
 * so whenever a batch boundary lands right after a typed `;` that `;` never
 * reaches the remote — at a position that looks random because it depends on
 * typing/batching timing, not on the text.
 *
 * The observed real-tmux behaviour this test encodes (captured against the
 * Docker `agents` fixture, tmux argv sent through `send-keys -l`):
 *
 * | argv sent  | pane received |
 * |------------|---------------|
 * | `1<a;>`    | `1<a;>`       | (`;` not final -> already literal)
 * | `2<a\;>`   | `2<a\;>`      | (`\` only special immediately before a FINAL `;`)
 * | `3<;`      | `3<`          | **byte deleted**
 * | `4<\;`     | `4<;`         | (the escape)
 * | `5<a\`     | `5<a\`        | (trailing `\` untouched)
 * | `6<;;`     | `6<;`         | **byte deleted**
 * | `7<\;\;`   | `7<\;;`       |
 *
 * `set-buffer -b b1 -- 'chunkA;'` behaves identically (the buffer stores
 * `chunkA`), which is why the composer's atomic bracketed-paste fill is exposed
 * to the same silent corruption at a 1 KiB chunk boundary.
 *
 * These are the cheap per-push guards ([Issue1845][tmuxQuotedArgument] plus the
 * command builders that must use it). The load-bearing proof that the byte
 * actually reaches the remote is the connected journey
 * `com.pocketshell.app.proof.Issue1845TypedSemicolonReachesPaneDockerTest`.
 */
class Issue1845TmuxArgumentSeparatorTest {

    // ---------------------------------------------------------------- helper

    @Test
    fun `trailing semicolon is escaped so tmux keeps it literal`() {
        assertEquals("'abc\\;'", tmuxQuotedArgument("abc;"))
    }

    @Test
    fun `a bare semicolon argument is escaped`() {
        assertEquals("'\\;'", tmuxQuotedArgument(";"))
    }

    @Test
    fun `only the final semicolon needs escaping`() {
        // `a;b` is already literal for tmux; escaping it would be wrong.
        assertEquals("'a;b'", tmuxQuotedArgument("a;b"))
        // Two trailing semicolons: only the LAST one is the separator.
        assertEquals("'a;\\;'", tmuxQuotedArgument("a;;"))
    }

    @Test
    fun `a value that itself ends in backslash-semicolon survives round trip`() {
        // tmux resolves a trailing `\\;` back to `\;` (it inspects only the last
        // two characters), so `find ... -exec ls {} \;` keeps BOTH bytes.
        assertEquals("'find . -exec ls {} \\\\;'", tmuxQuotedArgument("find . -exec ls {} \\;"))
    }

    @Test
    fun `a trailing backslash without a semicolon is untouched`() {
        assertEquals("'a\\'", tmuxQuotedArgument("a\\"))
    }

    @Test
    fun `single quotes are still shell-escaped, including alongside the separator escape`() {
        assertEquals("""'printf '\''hi'\'''""", tmuxQuotedArgument("printf 'hi'"))
        assertEquals("""'echo '\''x'\''\;'""", tmuxQuotedArgument("echo 'x';"))
    }

    @Test
    fun `values with no semicolon are quoted exactly as before`() {
        assertEquals("'plain text'", tmuxQuotedArgument("plain text"))
        assertEquals("''", tmuxQuotedArgument(""))
    }

    // -------------------------------------------------------------- builders

    /**
     * The keystroke lane — the lane the maintainer's typed command rode.
     * MUST be red before the fix (`send-keys -l -t %0 -- 'e0r;'`).
     */
    @Test
    fun `sendLiteralTextKeys emits a separator-safe argument`() = runTest {
        val client = FakeTmuxClient()
        sendLiteralTextKeys(client, "%0", "d=/tmp/x;")
        assertEquals(
            listOf("send-keys -l -t %0 -- 'd=/tmp/x\\;'"),
            client.sentCommands,
        )
    }

    @Test
    fun `sendLiteralTextKeys leaves a non-semicolon payload byte-identical`() = runTest {
        val client = FakeTmuxClient()
        sendLiteralTextKeys(client, "%0", "mkdir -p \$d")
        assertEquals(listOf("send-keys -l -t %0 -- 'mkdir -p \$d'"), client.sentCommands)
    }

    /**
     * The composer / paste lane. `sendBracketedPaste` splits the framed payload
     * into fixed 1 KiB chunks, so a `;` that happens to be the last byte of a
     * chunk was silently dropped out of the middle of a pasted prompt.
     */
    @Test
    fun `sendBracketedPaste emits separator-safe set-buffer fills at a semicolon chunk boundary`() =
        runTest {
            val client = FakeTmuxClient()
            val payload = buildSemicolonAtChunkBoundaryPayload()
            val chunks = BracketedPaste.frameTextChunks(
                payload.toByteArray(Charsets.UTF_8),
                TMUX_PASTE_BODY_CHUNK_BYTES,
            )
            // Hard-assert the failing STATE was actually constructed (#780
            // model): without a chunk that ends on `;` this test would pass
            // vacuously against the unfixed code.
            assertTrue(
                "expected a paste chunk ending in ';' — got chunk tails " +
                    chunks.map { it.takeLast(3) },
                chunks.dropLast(1).any { it.endsWith(';') },
            )

            sendBracketedPaste(client, "%0", payload.toByteArray(Charsets.UTF_8))

            val fills = client.sentCommands.filter { it.startsWith("set-buffer") }
            assertTrue("expected at least two set-buffer fills", fills.size >= 2)
            fills.forEachIndexed { index, command ->
                val quoted = command.substringAfter("-- ")
                assertTrue(
                    "set-buffer fill #$index must not end its argument with an UNESCAPED ';' " +
                        "(tmux would delete that byte): ...${quoted.takeLast(8)}",
                    !quoted.endsWith(";'") || quoted.endsWith("\\;'"),
                )
                assertEquals(
                    "set-buffer fill #$index must use the separator-safe quoting",
                    tmuxQuotedArgument(chunks[index]),
                    quoted,
                )
            }
        }

    // ---------------------------------------------------- class-wide guard

    /**
     * G2 class coverage: EVERY production lane that hands arbitrary user text to
     * tmux as a command argument must go through [tmuxQuotedArgument]. The two
     * builders above are unit-drivable; the share-into-session and assistant
     * prompt lanes are not (they need a live lease), so this compile-free source
     * guard is what keeps the class from silently regrowing a bare-single-quoted
     * payload argument. Red before the fix at all four sites.
     */
    @Test
    fun `every tmux payload lane quotes through the separator-safe helper`() {
        val lanes = listOf(
            "app/src/main/java/com/pocketshell/app/tmux/TmuxInputCommands.kt" to listOf(
                "send-keys -l -t \$paneId -- \${tmuxQuotedArgument(text)}",
                "set-buffer \$setFlags \$bufferName -- \${tmuxQuotedArgument(chunk)}",
            ),
            "app/src/main/java/com/pocketshell/app/share/ShareViewModel.kt" to listOf(
                "val literal = tmuxQuotedArgument(text)",
                "send-keys -l -t \$paneId -- \$literal",
                "send-keys -l -- \$literal",
            ),
            "app/src/main/java/com/pocketshell/app/assistant/AppAssistantActions.kt" to listOf(
                "tmux send-keys -t \$target -l \${tmuxQuotedArgument(prompt)}",
            ),
        )
        lanes.forEach { (path, expectations) ->
            val text = source(path)
            expectations.forEach { expected ->
                assertTrue(
                    "$path must build its tmux payload argument with tmuxQuotedArgument " +
                        "(issue #1845 — a bare single-quoted argument loses a trailing `;`); " +
                        "missing: $expected",
                    text.contains(expected),
                )
            }
            assertTrue(
                "$path must not reintroduce a bare single-quoted tmux PAYLOAD argument " +
                    "('\${escapeSingleQuoted(...)}' after `-- `) — issue #1845",
                !Regex("""-- '\$\{escapeSingleQuoted\(""").containsMatchIn(text),
            )
        }
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

    /**
     * Build a multi-line paste payload whose FRAMED form puts a `;` exactly on a
     * [TMUX_PASTE_BODY_CHUNK_BYTES] boundary. Deterministic: the framing prefix
     * is a fixed 6 bytes, so body index `chunk - 6 - 1` is the last byte of the
     * first chunk.
     */
    private fun buildSemicolonAtChunkBoundaryPayload(): String {
        val framingPrefixBytes = 6
        val lastBodyIndexInFirstChunk = TMUX_PASTE_BODY_CHUNK_BYTES - framingPrefixBytes - 1
        val body = CharArray(TMUX_PASTE_BODY_CHUNK_BYTES + 64) { 'x' }
        body[lastBodyIndexInFirstChunk] = ';'
        // Multi-line, so the production path routes through sendBracketedPaste.
        body[10] = '\n'
        return String(body)
    }
}
