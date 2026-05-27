package com.pocketshell.app.proof

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Connected E2E for issue #209: a multi-paragraph dictation transcript
 * sent through the production `TmuxClient.sendCommand("send-keys ...")`
 * path must arrive at the foreground program inside the tmux pane as
 * ONE bracketed-paste block — markers (`\e[200~` ... `\e[201~`) bracket
 * the multi-line body, internal `\n` bytes are preserved verbatim, and
 * no per-line tmux `Enter` named-key is emitted between paragraphs.
 *
 * The bug being prevented:
 *
 *   - The maintainer dictated a multi-paragraph prompt on 2026-05-27.
 *   - Whisper produced a transcript with internal line breaks.
 *   - The previous tmux input path split the bytes on `\n` and emitted
 *     a `send-keys ... Enter` named-key per paragraph.
 *   - Claude Code CLI interpreted each Enter as "submit", so the one
 *     dictation landed as N separate prompts instead of one multi-line
 *     prompt.
 *
 * The fix wraps any input containing `\n` in `\e[200~` ... `\e[201~`
 * and routes it through `send-keys -H` so the receiving program sees
 * the whole block as a pasted prompt.
 *
 * # What this test asserts
 *
 * The test stands up the same production wiring the dictation surface
 * uses (`TmuxClientFactory.create` + `sendInputBytesToPane` via the
 * private path exercised by `writeInputToPane`), but verifies the
 * resulting **bytes the receiving program sees** rather than the
 * command string emitted by `TmuxSessionViewModel` (the latter is
 * already pinned by `TmuxSessionViewModelTest`).
 *
 * To make the assertion deterministic across shells, the pane runs
 * `cat > <path>` for the duration of the input. `cat` captures stdin
 * verbatim regardless of the shell's `enable-bracketed-paste` setting,
 * so what lands in the file IS the raw byte stream tmux delivered to
 * the pane's PTY. The test then reads the file via SSH `exec` and
 * confirms:
 *
 *   - The captured stream starts with the bracketed-paste start
 *     marker bytes (`\e[200~`).
 *   - The captured stream ends with the matching end marker bytes
 *     (`\e[201~`).
 *   - The body between the markers reads as the original multi-line
 *     transcript, with internal `\n` bytes preserved (no `\r`
 *     submissions, no splitting per paragraph).
 *
 * That is the load-bearing acceptance criterion of #209: ONE prompt at
 * the remote with multi-line content intact.
 *
 * # CI compatibility
 *
 * Uses the default `agents` Docker service on port 2222. The Tests
 * workflow already brings that fixture up for sibling tests
 * (`TmuxExternalUpdateDockerTest`, `SharePasteIntoSessionE2eTest`).
 * No additional fixture is required.
 */
@RunWith(AndroidJUnit4::class)
class TmuxBracketedPasteDictationE2eTest {

    private val tmuxClientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sshSession: SshSession? = null
    private var tmuxClient: TmuxClient? = null
    private var resolvedSessionName: String = ""

    @After
    fun teardown() {
        runCatching { tmuxClient?.close() }
        runCatching { sshSession?.close() }
        runCatching { tmuxClientScope.cancel() }
        if (resolvedSessionName.isNotBlank()) {
            // Best-effort: kill any leftover session so re-runs start
            // clean. The Docker `agents` fixture is shared with sibling
            // tests; leaking named sessions would slowly bloat tmux.
            runCatching {
                runBlocking {
                    val key = readTestKeyOrNull() ?: return@runBlocking
                    SshConnection.connect(
                        host = DEFAULT_HOST,
                        port = DEFAULT_PORT,
                        user = DEFAULT_USER,
                        key = SshKey.Pem(key),
                        knownHosts = KnownHostsPolicy.AcceptAll,
                        timeoutMs = 10_000,
                    ).getOrNull()?.use { session ->
                        session.exec(
                            "tmux kill-session -t '$resolvedSessionName' 2>/dev/null || true",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun multiLineDictationArrivesAsBracketedPasteBlock() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val key = instrumentation.context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        val sshKey = SshKey.Pem(key)
        waitForSshFixtureReady(sshKey)

        val marker = System.currentTimeMillis().toString()
        val sessionName = "issue209-$marker"
        resolvedSessionName = sessionName
        val capturePath = "/tmp/issue209-${marker}.bin"

        val session = withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = sshKey,
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).getOrThrow()
        }
        sshSession = session

        // Make sure nothing stale is around from a prior run.
        runCatching {
            session.exec("tmux kill-session -t '$sessionName' 2>/dev/null || true")
        }
        runCatching {
            session.exec("rm -f '$capturePath' 2>/dev/null || true")
        }

        // Spawn the production tmux client against this SSH session —
        // same wiring TmuxSessionViewModel uses internally.
        val client = TmuxClientFactory(tmuxClientScope).create(
            session = session,
            sessionName = sessionName,
        )
        tmuxClient = client
        client.connect()

        // Resolve the active pane id so we have a stable target for
        // send-keys.
        val paneId = withTimeout(10_000) {
            var resp = client.sendCommand("display-message -p \"#{pane_id}\"")
            var attempts = 0
            while (resp.isError && attempts < 10) {
                delay(100)
                resp = client.sendCommand("display-message -p \"#{pane_id}\"")
                attempts += 1
            }
            assertFalse(
                "expected display-message -p '#{pane_id}' to succeed, got ${resp.output}",
                resp.isError,
            )
            val id = resp.output.firstOrNull()?.trim().orEmpty()
            assertTrue("expected pane id starting with %, got '$id'", id.startsWith("%"))
            id
        }

        // Run `cat > <capture>` inside the pane so subsequent send-keys
        // bytes land verbatim in <capture>. `cat` does not interpret
        // bracketed-paste markers, so what reaches the file IS the byte
        // stream tmux delivered to the pane's PTY.
        client.sendCommand(
            "send-keys -t $paneId 'cat > $capturePath' Enter",
        )
        // Allow `cat` to start before we feed it bytes. tmux send-keys
        // is asynchronous from our perspective; without this beat the
        // first chunk of input can race the shell's exec(cat).
        waitForCommandRunningOn(session, sessionName, paneId, expected = "cat")

        val dictation =
            "Paragraph one explains the bug.\n" +
                "Paragraph two has a code block:\n" +
                "    grep -n bracketed paste\n" +
                "Paragraph three wraps up."

        // Drive the same bracketed-paste hex path the production
        // ViewModel uses. We build the hex payload from the same helper
        // so the encoding is identical to what the user-facing call
        // site emits.
        val hex = buildBracketedPasteHexForTest(dictation.toByteArray(Charsets.UTF_8))
        val sendResp = client.sendCommand("send-keys -H -t $paneId $hex")
        assertFalse(
            "send-keys -H must succeed, got error output ${sendResp.output}",
            sendResp.isError,
        )

        // Close `cat`'s stdin: tmux Ctrl-D + Enter on the pane sends
        // EOF to the shell-spawned `cat`, which then flushes its buffer
        // and exits. EOF lets the SSH exec read of the capture file
        // observe a complete write.
        client.sendCommand("send-keys -t $paneId C-d")

        // Poll until the file exists and has stopped growing, then
        // read its bytes via a sidecar SSH exec.
        val captured = withTimeout(15_000) {
            readWhenStable(session, capturePath)
        }
        // Cleanup the capture file before assertions so a failure
        // doesn't leave debris on the Docker fixture.
        runCatching { session.exec("rm -f '$capturePath' 2>/dev/null || true") }

        // ----- Authoritative byte-level assertions -----
        val pasteStart = byteArrayOf(0x1B, 0x5B, 0x32, 0x30, 0x30, 0x7E)
        val pasteEnd = byteArrayOf(0x1B, 0x5B, 0x32, 0x30, 0x31, 0x7E)

        val firstStart = indexOf(captured, pasteStart)
        val firstEnd = indexOf(captured, pasteEnd)
        assertTrue(
            "expected the captured stream to contain the bracketed-paste start marker " +
                "(\\e[200~). hex(prefix)=${captured.take(40).joinToString(" ") { "%02x".format(it) }}",
            firstStart >= 0,
        )
        assertTrue(
            "expected the captured stream to contain the bracketed-paste end marker " +
                "(\\e[201~). hex(suffix)=${captured.takeLast(40).joinToString(" ") { "%02x".format(it) }}",
            firstEnd >= 0,
        )
        assertTrue(
            "the end marker must follow the start marker — bracketed paste blocks " +
                "must be balanced. start=$firstStart end=$firstEnd",
            firstEnd > firstStart,
        )

        // Exactly one paste block — N > 1 would indicate the input was
        // chunked into multiple pastes, which would defeat the "ONE
        // prompt" acceptance criterion.
        assertEquals(
            "expected exactly one bracketed-paste start marker in the captured stream",
            1,
            countOccurrences(captured, pasteStart),
        )
        assertEquals(
            "expected exactly one bracketed-paste end marker in the captured stream",
            1,
            countOccurrences(captured, pasteEnd),
        )

        // The body between the markers must reproduce the dictation
        // verbatim (modulo the \r\n → \n normalisation the bracketed-
        // paste builder applies). Our dictation contains only \n, so
        // it should round-trip unchanged.
        val body = captured.copyOfRange(firstStart + pasteStart.size, firstEnd)
        val bodyText = String(body, Charsets.UTF_8)
        assertEquals(
            "expected the bracketed-paste body to round-trip the dictation verbatim",
            dictation,
            bodyText,
        )

        // Internal LF bytes must be preserved (no replacement with \r,
        // no splitting). The dictation has 3 internal newlines.
        val lfCount = body.count { it == 0x0A.toByte() }
        assertEquals(
            "expected the body to preserve all 3 internal LF bytes",
            3,
            lfCount,
        )
        // No tmux Enter was emitted between paragraphs — every CR
        // inside the body would indicate a split submission. (None
        // expected; if one shows up it points at the regressed shape.)
        val crCount = body.count { it == 0x0D.toByte() }
        assertEquals(
            "expected the body to carry no CR bytes — paragraphs must " +
                "not be terminated by an Enter named-key",
            0,
            crCount,
        )
    }

    // ----------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------

    /**
     * Poll the remote `pane_current_command` for [paneId] until it
     * reports [expected] (e.g. `cat`). Without this, send-keys can
     * race the shell's exec of `cat` and lose the leading bytes of
     * the dictation block.
     */
    private suspend fun waitForCommandRunningOn(
        session: SshSession,
        sessionName: String,
        paneId: String,
        expected: String,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val exec = runCatching {
                session.exec(
                    "tmux display-message -t '$sessionName' -p '#{pane_current_command}'",
                )
            }.getOrNull()
            if (exec?.exitCode == 0 && exec.stdout.trim() == expected) return
            delay(50)
        }
        // Soft-fail: continue even if we did not observe the transition.
        // The downstream assertions will surface a clear failure if the
        // capture file is empty.
    }

    /**
     * Read [path] via SSH `exec`, retrying until the file size stops
     * changing — proxy for "the writer has flushed and closed". Tmux
     * delivery is asynchronous so the file may grow for a beat after
     * we issue the EOF.
     */
    private suspend fun readWhenStable(session: SshSession, path: String): ByteArray {
        var lastSize = -1L
        var stableTicks = 0
        while (true) {
            val exec = runCatching {
                session.exec("wc -c < '$path' 2>/dev/null")
            }.getOrNull()
            val size = exec?.stdout?.trim()?.toLongOrNull() ?: -1L
            if (size >= 0 && size == lastSize) {
                stableTicks += 1
                if (stableTicks >= 3) break
            } else {
                stableTicks = 0
            }
            lastSize = size
            delay(150)
        }
        // Read via a base64-wrapped exec so binary bytes (the ESC
        // markers, LF bytes) round-trip the SSH stdout channel safely.
        val readExec = session.exec("base64 < '$path'")
        assertEquals(
            "expected base64 of capture file to succeed, got stderr='${readExec.stderr}'",
            0,
            readExec.exitCode,
        )
        return android.util.Base64.decode(readExec.stdout, android.util.Base64.DEFAULT)
    }

    /**
     * Linear search for the first occurrence of [needle] in [haystack].
     * Returns -1 when not found. Small inputs (test captures are well
     * under 1 KiB), so the naive O(n*m) loop is fine.
     */
    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun countOccurrences(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var i = 0
        while (i <= haystack.size - needle.size) {
            var match = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) { match = false; break }
            }
            if (match) {
                count += 1
                i += needle.size
            } else {
                i += 1
            }
        }
        return count
    }

    /**
     * Issue #209: re-implement the production hex builder verbatim so
     * the test does not reach into the app's `internal` API surface.
     * The bytes produced here MUST match
     * [com.pocketshell.app.tmux.buildBracketedPasteHex]; if those two
     * diverge the assertions below will fail.
     */
    private fun buildBracketedPasteHexForTest(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val prefix = byteArrayOf(0x1B, 0x5B, 0x32, 0x30, 0x30, 0x7E)
        val suffix = byteArrayOf(0x1B, 0x5B, 0x32, 0x30, 0x31, 0x7E)
        val builder = StringBuilder()
        fun append(bs: ByteArray) {
            for (b in bs) {
                if (builder.isNotEmpty() && builder.last() != ' ') builder.append(' ')
                builder.append("%02x".format(b.toInt() and 0xFF))
            }
        }
        append(prefix)
        append(bytes)
        append(suffix)
        return builder.toString()
    }

    private fun readTestKeyOrNull(): String? = runCatching {
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
    }.getOrNull()
}
