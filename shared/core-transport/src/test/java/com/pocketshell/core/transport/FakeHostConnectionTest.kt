package com.pocketshell.core.transport

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The fake's own contract test. Everything documented on [FakeHostConnection]
 * is asserted here, because every other module's tests will trust it: a fake
 * that silently drifts from its docs turns consumer tests into vacuous greens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeHostConnectionTest {

    // ------------------------------------------------------------ exec scripting

    @Test
    fun `exec returns the exact-match script and records the call`() = runTest {
        val host = FakeHostConnection()
        host.onExec("uname -a", ExecResult(0, "Linux rmthz\n", "", timedOut = false))

        val result = host.exec("uname -a", timeoutMs = 3_000)

        assertEquals(0, result.exitCode)
        assertEquals("Linux rmthz\n", result.stdout)
        assertFalse(result.timedOut)
        assertEquals(listOf(FakeHostConnection.ExecCall("uname -a", 3_000)), host.execCalls)
    }

    @Test
    fun `exec matches a prefix rule for the whole command family`() = runTest {
        val host = FakeHostConnection()
        host.onExecPrefix(
            "pocketshell sessions list",
            ExecResult(0, """{"schema":2}""", "", timedOut = false),
        )

        val json = host.exec("pocketshell sessions list --json").stdout

        assertEquals("""{"schema":2}""", json)
        // Sticky: the same rule answers a second, differently-suffixed call.
        assertEquals(
            """{"schema":2}""",
            host.exec("pocketshell sessions list --json --all").stdout,
        )
    }

    @Test
    fun `exact and prefix rules are matched in registration order`() = runTest {
        val host = FakeHostConnection()
        host.onExec("git status", ExecResult(0, "exact", "", timedOut = false))
        host.onExecPrefix("git ", ExecResult(0, "prefix", "", timedOut = false))

        assertEquals("exact", host.exec("git status").stdout)
        assertEquals("prefix", host.exec("git log").stdout)
    }

    @Test
    fun `one-shot rules replay in order then fall through`() = runTest {
        val host = FakeHostConnection()
        host.defaultExec = ExecResult(1, "", "unscripted", timedOut = false)
        host.onExec("poll", ExecResult(0, "first", "", timedOut = false), once = true)
        host.onExec("poll", ExecResult(0, "second", "", timedOut = false), once = true)

        assertEquals("first", host.exec("poll").stdout)
        assertEquals("second", host.exec("poll").stdout)
        val exhausted = host.exec("poll")
        assertEquals(1, exhausted.exitCode)
        assertEquals("unscripted", exhausted.stderr)
        assertTrue(host.scriptedExecRules.isEmpty())
    }

    @Test
    fun `a computed rule sees the command and can script a timeout`() = runTest {
        val host = FakeHostConnection()
        host.onExecMatching("echo *", match = { it.startsWith("echo ") }) { command ->
            ExecResult(0, command.removePrefix("echo ") + "\n", "", timedOut = false)
        }
        host.onExec("sleep 60", ExecResult(-1, "", "", timedOut = true))

        assertEquals("hi\n", host.exec("echo hi").stdout)
        assertTrue(host.exec("sleep 60").timedOut)
    }

    @Test
    fun `an unscripted command returns the default result and is still recorded`() = runTest {
        val host = FakeHostConnection()

        val result = host.exec("which quse")

        assertEquals(127, result.exitCode)
        assertTrue(result.stderr.contains("no exec script"))
        assertEquals(listOf("which quse"), host.executedCommands)
    }

    // ------------------------------------------------------------- PTY scripting

    @Test
    fun `openPty replays the queued frames then completes output and exit`() = runTest {
        val host = FakeHostConnection()
        host.enqueuePtyText("hello ", "world\r\n", exitCode = 0)

        val pty = host.openPty("bash -lc top", cols = 91, rows = 41)
        // toList() only returns if the flow COMPLETES at EOF, which is the contract.
        val frames = pty.output.toList().map { it.toString(Charsets.UTF_8) }

        assertEquals(listOf("hello ", "world\r\n"), frames)
        assertEquals(0, pty.exit.await())
        assertEquals(
            listOf(FakeHostConnection.PtyRequest("bash -lc top", 91, 41, "xterm-256color")),
            host.ptyRequests,
        )
    }

    @Test
    fun `queued pty scripts are handed out in order`() = runTest {
        val host = FakeHostConnection()
        host.enqueuePtyText("first\r\n")
        host.enqueuePtyText("second\r\n")

        val a = host.openPty("a", 80, 24)
        val b = host.openPty("b", 80, 24, term = "screen-256color")

        assertEquals(listOf("first\r\n"), a.output.toList().map { it.toString(Charsets.UTF_8) })
        assertEquals(listOf("second\r\n"), b.output.toList().map { it.toString(Charsets.UTF_8) })
        assertEquals("screen-256color", host.ptyRequests[1].term)
    }

    @Test
    fun `a live pty stays open for frames emitted after attach`() = runTest {
        val host = FakeHostConnection()
        host.enqueuePty(completeAfterFrames = false)
        val pty = host.openPty("bash", 80, 24) as FakePtyChannel

        val collected = mutableListOf<String>()
        val collector = launch { pty.output.collect { collected += it.toString(Charsets.UTF_8) } }

        pty.emitText("$ ")
        pty.emitText("ls\r\n")
        pty.finish(exitCode = 3)
        collector.join()

        assertEquals(listOf("$ ", "ls\r\n"), collected)
        assertEquals(3, pty.exit.await())
        assertTrue(pty.isEnded)
    }

    @Test
    fun `pty records writes and resizes`() = runTest {
        val host = FakeHostConnection()
        host.enqueuePty(completeAfterFrames = false)
        val pty = host.openPty("bash", 80, 24) as FakePtyChannel

        pty.writeText("ls -la\n")
        pty.write(byteArrayOf(0x03))
        pty.resize(120, 40)

        assertEquals("ls -la\n", pty.writtenText)
        assertEquals(listOf(120 to 40), pty.resizes)
        assertEquals(120, pty.cols)
        assertEquals(40, pty.rows)
    }

    @Test
    fun `closing a live pty completes output with a null exit status`() = runTest {
        val host = FakeHostConnection()
        host.enqueuePty(completeAfterFrames = false)
        val pty = host.openPty("bash", 80, 24)

        pty.close()

        assertEquals(emptyList<ByteArray>(), pty.output.toList())
        assertNull(pty.exit.await())
        assertThrows(IOException::class.java) { runBlockingWrite(pty) }
    }

    // ---------------------------------------------------------- state transitions

    @Test
    fun `markLost pushes Lost to state collectors`() = runTest {
        val host = FakeHostConnection()
        val seen = mutableListOf<TransportState>()
        // Unconfined so the collector is subscribed before the first flip and
        // every emission is delivered synchronously — otherwise StateFlow
        // conflation would hide the intermediate values and the assertion
        // would pass without proving a collector reacted.
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            host.state.collect { seen += it }
        }
        assertEquals(listOf<TransportState>(TransportState.Connected), seen)

        host.markConnecting()
        host.markConnected()
        host.markLost("peer reset")
        collector.cancel()

        assertEquals(
            listOf(
                TransportState.Connected,
                TransportState.Connecting,
                TransportState.Connected,
                TransportState.Lost("peer reset"),
            ),
            seen,
        )
    }

    @Test
    fun `a lost connection refuses new work`() = runTest {
        val host = FakeHostConnection()
        host.markLost("network dropped")

        val execFailure = assertThrows(IOException::class.java) { runBlockingExec(host) }
        assertTrue(execFailure.message!!.contains("network dropped"))
        assertThrows(IOException::class.java) { runBlockingOpenPty(host) }
        assertThrows(IOException::class.java) { runBlockingSftp(host) }
    }

    @Test
    fun `close ends open ptys and flips state to Closed`() = runTest {
        val host = FakeHostConnection()
        host.enqueuePty(completeAfterFrames = false)
        val pty = host.openPty("bash", 80, 24) as FakePtyChannel

        host.close()

        // `close()` is the "someone asked for this to end" path, so the fake
        // must report the same reason a real transport does (issue #2487) —
        // consumers branch on it.
        assertEquals(TransportState.Closed(CloseReason.Requested), host.state.value)
        assertEquals(CloseReason.Requested, host.closeReason)
        assertTrue(host.isClosed)
        assertTrue(pty.isEnded)
        assertNull(pty.exit.await())
        assertThrows(IOException::class.java) { runBlockingExec(host) }
    }

    // ------------------------------------------------------------------- grace

    @Test
    fun `scheduleGraceClose records a deadline from the injected clock`() = runTest {
        var now = 1_000L
        val host = FakeHostConnection(nowMs = { now })

        val handle = host.scheduleGraceClose(90_000)

        assertEquals(91_000L, handle.deadlineMs)
        assertTrue((handle as FakeGraceHandle).isLive)

        now = 5_000L
        val replacement = host.scheduleGraceClose(1_000) as FakeGraceHandle
        assertEquals(6_000L, replacement.deadlineMs)
        assertTrue("the first handle must be superseded, not left armed", handle.isReplaced)
        assertFalse(handle.isLive)
        assertEquals(listOf(handle, replacement), host.graceHandles)
    }

    @Test
    fun `a cancelled grace close does not fire`() = runTest {
        val host = FakeHostConnection()
        val handle = host.scheduleGraceClose(90_000)

        handle.cancel()
        host.fireGraceClose()

        assertFalse((handle as FakeGraceHandle).isLive)
        assertEquals(TransportState.Connected, host.state.value)
    }

    @Test
    fun `an un-cancelled grace close closes the connection when it fires`() = runTest {
        val host = FakeHostConnection()
        host.scheduleGraceClose(90_000)

        host.fireGraceClose()

        // ...as `GraceExpired`, never `Requested`: the remote session is still
        // alive, so a consumer must be able to tell this from a disconnect and
        // reattach instead of reporting the session over (issue #2487).
        assertEquals(TransportState.Closed(CloseReason.GraceExpired), host.state.value)
        assertEquals(CloseReason.GraceExpired, host.closeReason)
        assertNull(host.pendingGrace)
    }

    // -------------------------------------------------------------------- sftp

    @Test
    fun `sftp returns the same cached channel and round-trips files`() = runTest {
        val host = FakeHostConnection()
        host.sftpFixture().seedFile("/home/alexey/notes.md", "hello\n")

        val sftp = host.sftp()
        assertTrue("sftp() must be cached per connection", sftp === host.sftp())

        assertEquals("hello\n", sftp.read("/home/alexey/notes.md", maxBytes = 1_024).toString(Charsets.UTF_8))
        sftp.write("/home/alexey/new.txt", "written".toByteArray())
        sftp.mkdir("/home/alexey/sub")

        val names = sftp.list("/home/alexey").map { it.name }
        assertEquals(listOf("new.txt", "notes.md", "sub"), names)
        assertTrue(sftp.list("/home/alexey").single { it.name == "sub" }.isDirectory)
        assertEquals(7L, sftp.stat("/home/alexey/new.txt")!!.sizeBytes)

        sftp.rename("/home/alexey/new.txt", "/home/alexey/renamed.txt")
        assertNull(sftp.stat("/home/alexey/new.txt"))
        assertEquals("written", host.sftpFixture().textAt("/home/alexey/renamed.txt"))

        sftp.delete("/home/alexey/renamed.txt")
        assertNull(sftp.stat("/home/alexey/renamed.txt"))
    }

    @Test
    fun `sftp read fails instead of truncating an over-size file`() = runTest {
        val host = FakeHostConnection()
        host.sftpFixture().seedFile("/var/log/big.log", "0123456789")
        val sftp = host.sftp()

        val failure = assertThrows(IOException::class.java) {
            runBlockingRead(sftp)
        }
        assertTrue(failure.message!!.contains("over the 4 byte limit"))
        assertThrows(IOException::class.java) { runBlockingMissingRead(sftp) }
    }

    // --------------------------------------------------------- port forwarding

    @Test
    fun `openPortForward records the request and hands back a live fake forward`() = runTest {
        val host = FakeHostConnection()

        val forward = host.openPortForward(remoteHost = "127.0.0.1", remotePort = 3000, localPort = 3000)

        assertEquals(
            listOf(FakeHostConnection.PortForwardRequest("127.0.0.1", 3000, 3000)),
            host.portForwardRequests,
        )
        assertTrue(forward.isActive)
        assertEquals(3000, forward.localPort)
        assertEquals(0L, forward.bytesForwarded)

        (forward as FakePortForward).pump(out = 40, back = 60)
        assertEquals(40L, forward.bytesForwarded)
        assertEquals(60L, forward.bytesReceived)

        forward.close()
        assertFalse(forward.isActive)
        assertEquals(1, forward.closeCount)
    }

    @Test
    fun `a scripted opener failure is still recorded as an attempt`() = runTest {
        val host = FakeHostConnection()
        host.portForwardOpener = { throw IOException("channel refused") }

        val failure = assertThrows(IOException::class.java) { runBlockingOpenForward(host) }

        assertEquals("channel refused", failure.message)
        assertEquals(1, host.portForwardRequests.size)
        assertTrue(host.openedPortForwards.isEmpty())
    }

    @Test
    fun `closing the connection closes every forward it handed out`() = runTest {
        val host = FakeHostConnection()
        val first = host.openPortForward("127.0.0.1", 3000, 3000) as FakePortForward
        val second = host.openPortForward("127.0.0.1", 8080, 8080) as FakePortForward

        host.close()

        assertFalse("a forward cannot outlive its transport", first.isActive)
        assertFalse(second.isActive)
        assertEquals(listOf(first, second), host.openedPortForwards)
    }

    @Test
    fun `openPortForward on a spent connection throws like a dead transport`() = runTest {
        val host = FakeHostConnection()
        host.markLost("network dropped")

        val failure = assertThrows(IOException::class.java) { runBlockingOpenForward(host) }

        assertTrue(failure.message!!.contains("openPortForward"))
        assertTrue(host.portForwardRequests.isEmpty())
    }

    // `assertThrows` needs a non-suspending lambda; these keep the suspending
    // calls under test readable at the call site.
    private fun runBlockingExec(host: FakeHostConnection) =
        kotlinx.coroutines.runBlocking { host.exec("echo hi") }

    private fun runBlockingOpenPty(host: FakeHostConnection) =
        kotlinx.coroutines.runBlocking { host.openPty("bash", 80, 24) }

    private fun runBlockingOpenForward(host: FakeHostConnection) =
        kotlinx.coroutines.runBlocking { host.openPortForward("127.0.0.1", 3000, 3000) }

    private fun runBlockingSftp(host: FakeHostConnection) =
        kotlinx.coroutines.runBlocking { host.sftp() }

    private fun runBlockingWrite(pty: PtyChannel) =
        kotlinx.coroutines.runBlocking { pty.write("x".toByteArray()) }

    private fun runBlockingRead(sftp: SftpChannel) =
        kotlinx.coroutines.runBlocking { sftp.read("/var/log/big.log", maxBytes = 4) }

    private fun runBlockingMissingRead(sftp: SftpChannel) =
        kotlinx.coroutines.runBlocking { sftp.read("/var/log/missing.log", maxBytes = 4_096) }
}
