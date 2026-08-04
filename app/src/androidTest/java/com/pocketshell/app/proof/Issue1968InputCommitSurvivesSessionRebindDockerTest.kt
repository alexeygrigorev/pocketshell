package com.pocketshell.app.proof

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.terminal.ui.TerminalSurface
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.OutputStream
import java.util.Collections

/**
 * Issue #1968 — one whole InputConnection commit must not disappear while the
 * mounted terminal surface replaces its producer session.
 *
 * The nightly failure was byte-exact: `prin` reached tmux, the next four-byte
 * commit `tf '` was absent, then every later chunk arrived. This proof
 * constructs that exact ownership boundary instead of hoping lifecycle timing
 * reproduces it:
 *
 *  1. commit chunk 0 through the production [TerminalView] InputConnection and
 *     wait until an independent SSH observer sees it in tmux;
 *  2. on one main-thread turn, replace [TerminalSurfaceState]'s bridge and
 *     immediately commit chunk 1 through the still-mounted View;
 *  3. after the normal Compose idle boundary, commit the remaining chunks;
 *  4. assert an independent SSH session's `tmux capture-pane` contains the
 *     payload byte-exact, while recording every bridge→tmux write.
 *
 * Base classification (`fd6016a5`): PRODUCT. `attachExternalProducer` stopped
 * session A before the asynchronously observing mounted View attached session
 * B. The vendored InputConnection returned `true` while writing chunk 1 into
 * A's closed outbound queue, so that entire commit vanished before the first
 * bridge dispatch. There is no retry, per-character workaround, tolerant
 * matcher, skip, or widened timeout in this proof.
 */
@RunWith(AndroidJUnit4::class)
class Issue1968InputCommitSurvivesSessionRebindDockerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val producerFlows = mutableListOf<MutableSharedFlow<ByteArray>>()
    private val observations = Collections.synchronizedList(mutableListOf<String>())
    private var writerSession: SshSession? = null
    private var observerSession: SshSession? = null
    private lateinit var sessionName: String

    @After
    fun tearDown() {
        producerScope.cancel()
        runCatching { writerSession?.close() }
        runCatching { observerSession?.close() }
        writeReport()
    }

    @Test
    fun fourByteImeCommitsReachTmuxExactlyOnceAndInOrderAcrossSessionRebind() { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val keyText = instrumentation.context.assets.open("test_key")
            .bufferedReader().use { it.readText() }
        val key = SshKey.Pem(keyText)
        waitForSshFixtureReady(key)
        sessionName = "issue1968-${System.currentTimeMillis().toString(36)}"

        val setup = connect(key)
        val setupResult = setup.exec(
            "tmux new-session -d -s ${shellQuote(sessionName)} " +
                shellQuote("exec sh"),
        )
        assertEquals("tmux fixture setup failed: ${setupResult.stderr}", 0, setupResult.exitCode)
        setup.close()

        writerSession = connect(key)
        observerSession = connect(key)
        val state = TerminalSurfaceState()
        attachGeneration(state, generation = 0)

        compose.setContent {
            TerminalSurface(state = state, modifier = Modifier.fillMaxSize())
        }
        compose.waitForIdle()
        val mountedView = waitForTerminalView()
        assertEquals(
            "precondition: the mounted View owns the state's initial session",
            state.renderModelOwnerSnapshotForTesting().sessionIdentity,
            System.identityHashCode(mountedView.currentSession),
        )

        val results = mutableListOf<RepetitionResult>()
        repeat(REPETITIONS) { repetition ->
            val observationStart = observations.size
            val payload = "I${repetition.toString().padStart(2, '0')}-abcdefghijklmnopqrstuvwx"
            val chunks = payload.chunked(CHUNK_SIZE)
            assertTrue("reproduction needs at least three chunks", chunks.size >= 3)

            commit(mountedView, repetition, 0, chunks[0])
            awaitPaneContains(chunks[0])

            instrumentation.runOnMainSync {
                val before = System.identityHashCode(mountedView.currentSession)
                attachGeneration(state, generation = repetition + 1)
                val authoritative = state.renderModelOwnerSnapshotForTesting().sessionIdentity
                val mounted = System.identityHashCode(mountedView.currentSession)
                record(
                    "rep=$repetition rebind state_session=$authoritative " +
                        "view_before=$before view_during=$mounted",
                )
                val committed = mountedView.onCreateInputConnection(EditorInfo())
                    .commitText(chunks[1], 1)
                record(
                    "rep=$repetition commit=1 text=${quoted(chunks[1])} returned=$committed " +
                        "view_session=${System.identityHashCode(mountedView.currentSession)}",
                )
                assertTrue("InputConnection rejected repetition $repetition chunk 1", committed)
            }

            compose.waitForIdle()
            val activeIdentity = state.renderModelOwnerSnapshotForTesting().sessionIdentity
            assertEquals(
                "mounted View must settle on the replacement session before later input",
                activeIdentity,
                System.identityHashCode(mountedView.currentSession),
            )
            chunks.drop(2).forEachIndexed { index, chunk ->
                commit(mountedView, repetition, index + 2, chunk)
            }

            // The unique final chunk proves all post-boundary input has drained;
            // we can classify a missing middle chunk immediately, without waiting
            // for the exact expected string to appear.
            awaitPaneContains(chunks.last())
            val captured = capturePane()
            val receivedLine = captured.lineSequence()
                .lastOrNull { chunks.first() in it }
                .orEmpty()
            val received = if (receivedLine.isEmpty()) {
                ""
            } else {
                chunks.first() + receivedLine.substringAfter(chunks.first())
            }
            val writes = observations.drop(observationStart).filter { "tmux_write=" in it }
            val result = RepetitionResult(payload, received, writes.size)
            results += result
            record(
                "rep=$repetition remote_capture expected=${quoted(payload)} " +
                    "received=${quoted(received)} tmux_writes=${writes.size}",
            )

            val clear = requireNotNull(observerSession).exec(
                "tmux send-keys -t ${shellQuote(sessionName)} C-u",
            )
            assertEquals("failed to clear tmux prompt between repetitions", 0, clear.exitCode)
            awaitPaneExcludes(chunks.last())
        }

        assertEquals(
            "all four-byte InputConnection commits must reach independent tmux capture " +
                "exactly once and in order across every constructed rebind; results=$results",
            List(REPETITIONS) { true },
            results.map { it.received == it.expected },
        )
    } }

    private fun attachGeneration(state: TerminalSurfaceState, generation: Int) {
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        producerFlows += stdout
        val sink = TmuxTracingOutputStream(generation)
        state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = sink,
        )
        record(
            "generation=$generation attach state_session=" +
                state.renderModelOwnerSnapshotForTesting().sessionIdentity,
        )
    }

    private fun commit(view: TerminalView, repetition: Int, index: Int, chunk: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val committed = view.onCreateInputConnection(EditorInfo()).commitText(chunk, 1)
            record(
                "rep=$repetition commit=$index text=${quoted(chunk)} returned=$committed " +
                    "view_session=${System.identityHashCode(view.currentSession)}",
            )
            assertTrue("InputConnection rejected repetition $repetition chunk $index", committed)
        }
    }

    /** App outbound dispatch boundary followed by the real tmux write boundary. */
    private inner class TmuxTracingOutputStream(private val generation: Int) : OutputStream() {
        override fun write(value: Int) {
            write(byteArrayOf(value.toByte()), 0, 1)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length == 0) return
            val text = bytes.copyOfRange(offset, offset + length).toString(Charsets.UTF_8)
            record("generation=$generation bridge_dispatch=${quoted(text)}")
            val result = runBlocking {
                requireNotNull(writerSession).exec(
                    "tmux send-keys -l -t ${shellQuote(sessionName)} -- ${shellQuote(text)}",
                )
            }
            record(
                "generation=$generation tmux_write=${quoted(text)} exit=${result.exitCode}",
            )
            check(result.exitCode == 0) { "tmux write failed: ${result.stderr}" }
        }
    }

    private suspend fun connect(key: SshKey.Pem): SshSession =
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = key,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 20_000,
        ).getOrThrow()

    private suspend fun capturePane(): String = requireNotNull(observerSession)
        .exec("tmux capture-pane -p -J -t ${shellQuote(sessionName)}")
        .stdout

    private suspend fun awaitPaneContains(needle: String) {
        val deadline = SystemClock.elapsedRealtime() + OBSERVE_TIMEOUT_MS
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            last = capturePane()
            if (needle in last) return
            SystemClock.sleep(25)
        }
        error("independent tmux capture never contained ${quoted(needle)}; tail=${quoted(last.takeLast(300))}")
    }

    private suspend fun awaitPaneExcludes(needle: String) {
        val deadline = SystemClock.elapsedRealtime() + OBSERVE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (needle !in capturePane()) return
            SystemClock.sleep(25)
        }
        error("tmux prompt did not clear ${quoted(needle)}")
    }

    private fun waitForTerminalView(): TerminalView {
        var found: TerminalView? = null
        compose.waitUntil(timeoutMillis = OBSERVE_TIMEOUT_MS) {
            found = compose.activity.window.decorView.findTerminalView()
            found?.currentSession != null && found?.mEmulator != null
        }
        return requireNotNull(found)
    }

    private fun View.findTerminalView(): TerminalView? {
        if (this is TerminalView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findTerminalView()?.let { return it }
        }
        return null
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun quoted(value: String): String = "`" + value.replace("\n", "\\n") + "`"

    private fun record(line: String) {
        observations += line
        println("ISSUE1968 $line")
    }

    private fun writeReport() {
        if (observations.isEmpty()) return
        runCatching {
            val dir = File(
                com.pocketshell.app.test.testArtifactsRoot(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                ),
                "additional_test_output/issue1968",
            )
            check(dir.exists() || dir.mkdirs()) { "could not create ${dir.absolutePath}" }
            File(dir, "input-commit-rebind-boundaries.txt")
                .writeText(observations.joinToString("\n", postfix = "\n"))
        }
    }

    private data class RepetitionResult(
        val expected: String,
        val received: String,
        val tmuxWrites: Int,
    )

    private companion object {
        const val CHUNK_SIZE: Int = 4
        const val REPETITIONS: Int = 12
        const val OBSERVE_TIMEOUT_MS: Long = 30_000
    }
}
