package com.pocketshell.core.tmux

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections

/**
 * Issue #2174 — REPRODUCE-FIRST (G10/D33) for: terminal content garbled on
 * hosts whose SSH exec lacks a UTF-8 locale.
 *
 * `TmuxClient.captureWithCursor` and `capturePaneTextViaExec` read pane
 * content through a bare `tmux` client. On a host whose sshd hands the exec
 * channel no locale, tmux's `utf8_sanitize()` replaces every non-ASCII
 * character with `_` on read (`ПРИВЕТ` → `______`). Same mechanism as #2160,
 * applied to visible terminal capture rather than a user-option delimiter.
 *
 * ## Why a synthetic sanitiser on capture-pane
 *
 * Measured on the `agents` fixture (tmux 3.6b, empty exec env) AND on the
 * maintainer's box (tmux 3.4, `env -i` + an isolated `-L` socket):
 * `display-message -p` / `show-options -v` / `list-sessions -F` run
 * `utf8_sanitize()`, but `capture-pane -p` dumps the grid **without**
 * sanitising. A Docker test that asserted "bare capture-pane returns
 * `______`" would be green with AND without the fix — the #2160 locale
 * trap. The sibling androidTest therefore pins the live
 * `display-message` half and the no-regression grid; THIS class is the
 * reproduce-first ratchet that can still enter the failing state:
 * [NonUtf8CaptureHost] applies the sanitiser to capture-pane unless `-u`
 * is present, so dropping `-u` from either production command reddens.
 *
 * ## G6 mutations
 *
 *  - Drop `-u` from `captureWithCursor`'s `capture-pane` (leave
 *    `display-message` locale-proof):
 *    [healCapturePreservesNonAsciiOnAHostWithNoUtf8Locale] reddens —
 *    `ПРИВЕТ` comes back as `______`.
 *  - Drop `-u` from `capturePaneTextViaExec` (either the visible or the
 *    scrollback form): the matching text-lane test reddens the same way.
 *  - Leave the production command locale-proof but stop sanitising a bare
 *    `tmux capture-pane` in the fixture:
 *    [theFixtureCanEnterTheFailingState] reddens — that is the non-vacuity
 *    proof that this class is still a reproduction.
 */
class Issue2174LocaleProofPaneCaptureTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        TmuxClientDiagnostics.install(TmuxClientDiagnosticSink.Noop)
        scope.cancel()
    }

    private companion object {
        const val PANE = "%3"
        const val CYRILLIC = "ПРИВЕТ"
        const val MANGLED = "______"
        const val BOX = "─│┌"
        const val BOX_MANGLED = "___"
        const val CHECK = "✓"
        const val MIXED = "$CYRILLIC $CHECK $BOX"
        const val MIXED_MANGLED = "$MANGLED _ $BOX_MANGLED"
        const val ASCII = "hello-grid"

        /**
         * tmux `utf8_sanitize()` — every character that is not printable ASCII
         * (`0x20`..`0x7e`) becomes a single `_`. Verified against real tmux
         * 3.4 and 3.6b: `ПРИВЕТ` → `______`, `✓` → `_`, per CHARACTER.
         */
        fun utf8Sanitize(value: String): String =
            value.map { ch -> if (ch.code in 0x20..0x7e) ch else '_' }.joinToString("")

        /**
         * The `tmux …` words that precede `capture-pane` in [command]. The
         * heal lane concatenates `display-message` + `capture-pane` in one
         * exec, so a whole-string `isLocaleProofInvocation` would stay green
         * if only the cursor query carried `-u`.
         */
        fun captureInvocation(command: String): String? {
            val idx = command.indexOf("capture-pane")
            if (idx < 0) return null
            val start = command.lastIndexOf("tmux", idx)
            if (start < 0) return null
            return command.substring(start, idx)
        }
    }

    /**
     * A host whose sshd exports no `LANG`/`LC_*` (the Docker `agents`
     * fixture, Alpine/BusyBox, a hardened sshd). It serves genuine pane
     * text and then applies tmux's sanitiser **unless the capture-pane
     * invocation asked for UTF-8 output**.
     */
    private class NonUtf8CaptureHost(
        private val shell: FakeShell,
        var paneText: String,
        var cursor: String = "4,2",
    ) : SshSession {
        val execCommands: MutableList<String> =
            Collections.synchronizedList(mutableListOf())

        @Volatile
        private var closed = false

        override val isConnected: Boolean get() = !closed

        override fun isTransportProvenAliveWithinKeepAliveWindow(): Boolean = false

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            val printed = printedCapture(command, paneText)
            val stdout = if (command.contains("capture-pane") && command.contains("display-message")) {
                buildString {
                    append(cursor)
                    append('\n')
                    val marker = command.substringAfter("printf '%s\\n' '").substringBefore("'")
                    append(marker)
                    append('\n')
                    append(printed)
                    append('\n')
                }
            } else if (command.contains("capture-pane")) {
                printed + "\n"
            } else {
                ""
            }
            return ExecResult(stdout = stdout, stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("not used")

        override fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job =
            error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell {
            check(!closed) { "session closed" }
            return shell
        }

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() {
            closed = true
            shell.close()
        }

        companion object {
            fun printedCapture(command: String, stored: String): String {
                val invocation = captureInvocation(command) ?: return stored
                return if (TmuxRead.isLocaleProofInvocation(invocation)) stored else utf8Sanitize(stored)
            }
        }
    }

    /**
     * A host whose sshd already exports a UTF-8 locale (the maintainer's
     * box). `-u` is a no-op: a bare `tmux` already prints intact bytes.
     */
    private class Utf8CaptureHost(
        private val shell: FakeShell,
        var paneText: String,
        var cursor: String = "1,1",
    ) : SshSession {
        val execCommands: MutableList<String> =
            Collections.synchronizedList(mutableListOf())

        @Volatile
        private var closed = false

        override val isConnected: Boolean get() = !closed

        override fun isTransportProvenAliveWithinKeepAliveWindow(): Boolean = false

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            val stdout = if (command.contains("capture-pane") && command.contains("display-message")) {
                buildString {
                    append(cursor)
                    append('\n')
                    val marker = command.substringAfter("printf '%s\\n' '").substringBefore("'")
                    append(marker)
                    append('\n')
                    append(paneText)
                    append('\n')
                }
            } else if (command.contains("capture-pane")) {
                paneText + "\n"
            } else {
                ""
            }
            return ExecResult(stdout = stdout, stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")

        override fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job =
            error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell {
            check(!closed) { "session closed" }
            return shell
        }

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() {
            closed = true
            shell.close()
        }
    }

    /**
     * NON-VACUITY (G6): this fixture can ENTER the failing state. If the
     * sanitiser ever stopped matching real tmux, every assertion below
     * would pass with the bug present.
     */
    @Test
    fun theFixtureCanEnterTheFailingState() {
        assertEquals(
            "tmux utf8_sanitize must destroy ПРИВЕТ per character; if this " +
                "ever stops matching, the reproduction is gone",
            MANGLED,
            utf8Sanitize(CYRILLIC),
        )
        assertEquals(MIXED_MANGLED, utf8Sanitize(MIXED))
        assertEquals(
            "a bare capture-pane on this host must sanitise; if this returns " +
                "intact bytes the fixture can no longer reproduce #2174",
            MANGLED,
            NonUtf8CaptureHost.printedCapture("tmux capture-pane -p -t '%3'", CYRILLIC),
        )
        assertEquals(
            "`tmux -u` on this host must preserve the stored bytes",
            CYRILLIC,
            NonUtf8CaptureHost.printedCapture(
                "${TmuxRead.CLIENT} capture-pane -p -t '%3'",
                CYRILLIC,
            ),
        )
        // Discriminating: locale-proofing ONLY the cursor query must still
        // sanitise the capture body. A whole-string `-u` check would miss that.
        assertEquals(
            MANGLED,
            NonUtf8CaptureHost.printedCapture(
                "${TmuxRead.CLIENT} display-message -p -t '%3' '#{cursor_x},#{cursor_y}'; " +
                    "printf '%s\\n' 'SPLIT'; tmux capture-pane -p -e -S -200 -t '%3'",
                CYRILLIC,
            ),
        )
    }

    @Test
    fun healCapturePreservesNonAsciiOnAHostWithNoUtf8Locale() = runBlocking {
        val host = NonUtf8CaptureHost(FakeShell(), paneText = MIXED)
        val client = RealTmuxClient(host, scope)
        try {
            client.connect()
            val combined = withTimeout(5_000) {
                client.captureWithCursor(PANE, scrollbackLines = 200)
            }
            assertFalse(combined.capture.isError)
            val text = combined.capture.output.joinToString("\n")
            assertEquals(
                "#2174: captureWithCursor must preserve non-ASCII pane text on a " +
                    "host whose SSH exec carries no UTF-8 locale. `$MANGLED` / " +
                    "`$MIXED_MANGLED` means utf8_sanitize ate the grid on read. " +
                    "commands=${host.execCommands}",
                MIXED,
                text,
            )
            assertEquals("4,2", combined.cursorReply)
        } finally {
            client.close()
        }
    }

    @Test
    fun visibleTextCapturePreservesNonAsciiOnAHostWithNoUtf8Locale() = runBlocking {
        val host = NonUtf8CaptureHost(FakeShell(), paneText = CYRILLIC)
        val client = RealTmuxClient(host, scope)
        try {
            client.connect()
            val response = withTimeout(5_000) {
                client.capturePaneTextViaExec(PANE, timeoutMs = 2_500L)
            }
            assertFalse(response.isError)
            assertEquals(
                "#2174: capturePaneTextViaExec (visible) must preserve ПРИВЕТ. " +
                    "commands=${host.execCommands}",
                listOf(CYRILLIC),
                response.output,
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun scrollbackTextCapturePreservesNonAsciiOnAHostWithNoUtf8Locale() = runBlocking {
        val host = NonUtf8CaptureHost(FakeShell(), paneText = MIXED)
        val client = RealTmuxClient(host, scope)
        try {
            client.connect()
            val response = withTimeout(5_000) {
                client.capturePaneTextViaExec(PANE, timeoutMs = 2_500L, scrollbackLines = 200)
            }
            assertFalse(response.isError)
            assertEquals(
                "#2174: capturePaneTextViaExec (scrollback) must preserve the " +
                    "mixed non-ASCII grid. commands=${host.execCommands}",
                listOf(MIXED),
                response.output,
            )
        } finally {
            client.close()
        }
    }

    /**
     * Structural ratchet: the commands THIS path issues. The value assertions
     * above can only redden on non-ASCII content; this one also covers a
     * future ASCII-only fixture and is the mutation target for dropping `-u`.
     */
    @Test
    fun everyContentReadingExecLaneUsesTheLocaleProofClient() = runBlocking {
        val host = NonUtf8CaptureHost(FakeShell(), paneText = ASCII)
        val client = RealTmuxClient(host, scope)
        try {
            client.connect()
            withTimeout(5_000) { client.captureWithCursor(PANE, scrollbackLines = 200) }
            withTimeout(5_000) { client.capturePaneTextViaExec(PANE, timeoutMs = 2_500L) }
            withTimeout(5_000) {
                client.capturePaneTextViaExec(PANE, timeoutMs = 2_500L, scrollbackLines = 200)
            }

            val captureCmds = host.execCommands.filter { it.contains("capture-pane") }
            assertEquals(
                "expected heal + visible + scrollback capture execs, got $captureCmds",
                3,
                captureCmds.size,
            )
            captureCmds.forEach { command ->
                val invocation = captureInvocation(command)
                assertTrue(
                    "#2174: every content-reading exec must invoke capture-pane " +
                        "through `${TmuxRead.CLIENT}`; got `$command`",
                    invocation != null && TmuxRead.isLocaleProofInvocation(invocation),
                )
            }
            val heal = captureCmds.first { it.contains("display-message") }
            assertTrue(
                "#2174: captureWithCursor's cursor query is also a read " +
                    "(`display-message -p`) and must use `${TmuxRead.CLIENT}`; got `$heal`",
                TmuxRead.isLocaleProofInvocation(
                    heal.substring(0, heal.indexOf("display-message")),
                ),
            )
        } finally {
            client.close()
        }
    }

    /**
     * AC: no regression on an unaffected host. `-u` is a no-op when the
     * exec already has a UTF-8 locale; the captured grid stays intact.
     */
    @Test
    fun captureIsUnchangedOnAHostThatAlreadyHasAUtf8Locale() = runBlocking {
        val host = Utf8CaptureHost(FakeShell(), paneText = MIXED)
        val client = RealTmuxClient(host, scope)
        try {
            client.connect()
            val heal = withTimeout(5_000) {
                client.captureWithCursor(PANE, scrollbackLines = 200)
            }
            val visible = withTimeout(5_000) {
                client.capturePaneTextViaExec(PANE, timeoutMs = 2_500L)
            }
            assertEquals(listOf(MIXED), heal.capture.output)
            assertEquals(listOf(MIXED), visible.output)
        } finally {
            client.close()
        }
    }

    private class FakeShell : SshShell {
        private val pipeOut = PipedOutputStream()
        private val pipeIn = PipedInputStream(pipeOut, 64 * 1024)
        private val stdinCapture = SynchronizedByteArrayOutputStream()

        @Volatile
        var closed: Boolean = false
            private set

        override val stdin: OutputStream = stdinCapture
        override val stdout: InputStream = pipeIn
        override val stderr: InputStream = object : InputStream() {
            override fun read(): Int = -1
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { pipeOut.close() }
            runCatching { pipeIn.close() }
            runCatching { stdinCapture.close() }
        }
    }

    private class SynchronizedByteArrayOutputStream : ByteArrayOutputStream() {
        @Volatile
        private var closedForWrites: Boolean = false

        override fun write(b: Int) {
            maybeThrowIfClosed()
            synchronized(this) {
                maybeThrowIfClosed()
                super.write(b)
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            maybeThrowIfClosed()
            synchronized(this) {
                maybeThrowIfClosed()
                super.write(b, off, len)
            }
        }

        override fun close() {
            closedForWrites = true
            synchronized(this) { super.close() }
        }

        private fun maybeThrowIfClosed() {
            if (closedForWrites) throw IOException("stdin closed")
        }
    }
}
