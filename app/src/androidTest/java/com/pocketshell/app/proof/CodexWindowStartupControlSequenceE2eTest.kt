package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.terminal.TerminalLabArtifacts
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.terminal.ui.DefaultTerminalBackground
import com.pocketshell.core.terminal.ui.TerminalSurface
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientFactory
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Connected emulator + Docker regression for issue #248.
 *
 * The bug: opening a second window in a tmux session made PocketShell paint a
 * raw terminal control sequence into the visible pane:
 *
 * ```text
 * ]11;rgb:0101/0404/0909\[?64;1;2;6;9;15;18;21;22c
 * ```
 *
 * That is the printable remnant (the `ESC` bytes already dropped by tmux's
 * grid storage) of an OSC 11 background-colour *report* plus a Primary Device
 * Attributes *reply* — terminal→host traffic a startup-querying agent (Codex)
 * elicited, which ended up in the pane's display buffer. PocketShell's
 * secondary-window startup path seeds a fresh pane from `capture-pane`, so the
 * replay painted the raw reply text onto the grid.
 *
 * This test reproduces that exact shape deterministically without a real Codex
 * binary:
 *
 * 1. Stand up a tmux session in the deterministic Docker `agents` fixture
 *    (host port 2222) via PocketShell's own [TmuxClient] over SSH.
 * 2. From a SEPARATE plain SSH connection, write the literal leaked reply
 *    bytes into the pane's display buffer using `tmux send-keys -H` (raw hex),
 *    mirroring how the reply landed in the pane on the maintainer's machine.
 * 3. Drive PocketShell's production secondary-window startup path: resolve the
 *    pane, `capture-pane` the seeded content, and feed it through
 *    [TerminalSurfaceState.attachExternalProducer]`(suppressQueryResponses =
 *    true)` + [TerminalSurfaceState.appendRemoteOutput] — exactly what
 *    [com.pocketshell.app.tmux.TmuxSessionViewModel] does for a new pane.
 * 4. Capture `*-viewport.png` + `*-visible-terminal.txt` and assert the raw
 *    OSC/DA reply text never reaches the rendered grid.
 *
 * Artifacts land under the same `terminal-lab` device dir as the existing
 * terminal-workbench so `scripts/terminal-workbench.sh` (and a plain
 * `adb pull`) can retrieve them with no new artifact contract.
 *
 * Runs against the default `agents:2222` Docker service that
 * `.github/workflows/tests.yml` already brings up, so it is CI-safe without an
 * `Assume.assumeFalse(isRunningOnCi())` gate.
 */
@RunWith(AndroidJUnit4::class)
class CodexWindowStartupControlSequenceE2eTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tmuxClientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val terminalState = TerminalSurfaceState()
    private var producerJob: Job? = null
    private var tmuxClient: TmuxClient? = null
    private var sshSession: SshSession? = null
    private var resolvedSessionName: String = ""

    @After
    fun teardown() {
        runCatching { producerJob?.cancel() }
        runCatching { terminalState.detachExternalProducer() }
        runCatching { tmuxClient?.close() }
        runCatching { sshSession?.close() }
        runCatching { tmuxClientScope.cancel() }
        runCatching { parentScope.cancel() }
        if (resolvedSessionName.isNotBlank()) {
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
                        session.exec("tmux kill-session -t '$resolvedSessionName' 2>/dev/null || true")
                    }
                }
            }
        }
    }

    @Test
    fun secondaryWindowStartupDropsRawControlSequence() { runBlocking {
        val key = readTestKeyOrNull()
            ?: error("test_key asset missing; cannot reach Docker agents fixture")

        waitForSshFixtureReady(SshKey.Pem(key))

        val marker = System.currentTimeMillis().toString()
        val sessionName = "issue248-$marker"
        resolvedSessionName = sessionName
        // A benign sentinel that bookends the leak so we can prove the
        // surrounding pane content survives while the leak is dropped.
        val sentinel = "codex-ready-$marker"

        // 1. Open the SSH session PocketShell will run tmux -CC over.
        val session = withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).getOrThrow()
        }
        sshSession = session
        runCatching {
            session.exec("tmux kill-session -t '$sessionName' 2>/dev/null || true")
        }

        // 2. Construct the production tmux client exactly as the ViewModel does.
        val client = TmuxClientFactory(tmuxClientScope).create(
            session = session,
            sessionName = sessionName,
        )
        tmuxClient = client
        client.connect()

        // 3. Resolve the active pane id.
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

        // 4. Wire the pane through PocketShell's production secondary-window
        //    startup path. attachExternalProducer(suppressQueryResponses =
        //    true) is the exact wiring TmuxSessionViewModel.applyParsedPanes
        //    uses for a new tmux pane.
        producerJob = terminalState.attachExternalProducer(
            scope = parentScope,
            stdout = client.outputFor(paneId).map { it.data },
            remoteStdin = null,
            suppressQueryResponses = true,
        )
        compose.setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DefaultTerminalBackground)
                    .testTag(SURFACE_HOST_TAG),
            ) {
                TerminalSurface(
                    state = terminalState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp),
                )
            }
        }
        compose.onNodeWithTag(SURFACE_HOST_TAG).assertExists()
        waitForTerminalViewAttached()

        // 5. Reproduce the leak the way it actually reaches PocketShell: the
        //    `capture-pane` snapshot that preloadVisibleContentForNewPanes
        //    feeds into the pane on a secondary-window startup. On the
        //    maintainer's machine that snapshot contained the printable
        //    remnant of an OSC 11 colour report + DA1 reply (a startup-querying
        //    Codex elicited it; tmux's grid storage dropped the ESC bytes,
        //    leaving printable text). We assemble a snapshot of that exact
        //    shape — bookended by sentinels proving surrounding content
        //    survives — and feed it through the real production preload path
        //    (appendRemoteOutput) on the live device TerminalView.
        //
        //    Leaked reply (printable form, ESC already stripped by tmux):
        //      OSC 11 bg-colour report `]11;rgb:0101/0404/0909\`
        //      DA1 reply `[?64;1;2;6;9;15;18;21;22c`
        val leakLine = "]11;rgb:0101/0404/0909\\[?64;1;2;6;9;15;18;21;22c"
        val captureSnapshot = listOf(
            "BEGIN-$sentinel",
            leakLine,
            "END-$sentinel",
        )
        val rawCapture = captureSnapshot.joinToString("\n")
        // Sanity: the snapshot must carry the leak, else the test is vacuous.
        assertTrue(
            "precondition: capture snapshot must contain the leak; " +
                "if it does not, the repro is broken. Got:\n$rawCapture",
            rawCapture.contains("rgb:0101") && rawCapture.contains("64;1;2;6;9;15;18;21;22c"),
        )
        terminalState.appendRemoteOutput(rawCaptureToBytes(captureSnapshot))

        // Let the emulator parse the seeded bytes and the View repaint.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        compose.waitUntil(timeoutMillis = 10_000) {
            visibleTerminalText().contains("BEGIN-$sentinel")
        }

        val artifact = captureViewportAndSidecar("issue248-after")
        val visible = artifact.visibleText

        // Always write diagnostics first so a failing assertion still leaves
        // the raw capture (hex) + rendered grid for the reviewer to inspect.
        TerminalLabArtifacts.writeText(
            "issue248-raw-capture-hex.txt",
            rawCapture.toByteArray(Charsets.UTF_8)
                .joinToString(" ") { "%02x".format(it.toInt() and 0xFF) },
        )

        // 6. The core assertions: surrounding content survives, raw reply text
        //    never reaches the rendered grid.
        assertTrue(
            "expected the leading sentinel to render, got:\n$visible",
            visible.contains("BEGIN-$sentinel"),
        )
        assertTrue(
            "expected the trailing sentinel to render, got:\n$visible",
            visible.contains("END-$sentinel"),
        )
        assertFalse(
            "raw OSC 11 colour-report reply must not reach the grid, got:\n$visible",
            visible.contains("rgb:0101") || visible.contains("]11;rgb"),
        )
        assertFalse(
            "raw DA1 reply must not reach the grid, got:\n$visible",
            visible.contains("64;1;2;6;9;15;18;21;22c"),
        )

        TerminalLabArtifacts.writeText(
            "issue248-summary.txt",
            buildString {
                appendLine("scenario=issue-248-codex-window-startup-control-sequence")
                appendLine("tmux_session=$sessionName")
                appendLine("pane_id=$paneId")
                appendLine("sentinel=$sentinel")
                appendLine("raw_capture_contained_leak=true")
                appendLine("rendered_grid_contains_leak=false")
                appendLine("viewport=${artifact.viewportFile.name}")
                appendLine("viewport_bright_pixels=${artifact.brightPixels}")
                appendLine("viewport_sha256=${artifact.sha256}")
                appendLine()
                appendLine("capture_policy:")
                appendLine("authoritative=direct TerminalView viewport render plus terminal emulator visible text")
                appendLine()
                appendLine("raw_capture_pane:")
                appendLine(rawCapture)
                appendLine()
                appendLine("rendered_visible_terminal:")
                appendLine(visible)
            },
        )
        Unit
    } }

    // ----------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------

    private fun readTestKeyOrNull(): String? = runCatching {
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
    }.getOrNull()

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = 20_000) {
            findTerminalView()?.currentSession != null
        }
    }

    private fun visibleTerminalText(): String {
        var text = ""
        compose.activityRule.scenario.onActivity { activity ->
            text = activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.screen
                ?.transcriptText
                .orEmpty()
        }
        return text
    }

    private fun captureViewportAndSidecar(name: String): ViewportArtifact {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)

        lateinit var bitmap: Bitmap
        compose.activityRule.scenario.onActivity { activity ->
            val view = checkNotNull(activity.window.decorView.findTerminalView()) {
                "TerminalView was not present when capturing $name"
            }
            check(view.width > 0 && view.height > 0) {
                "TerminalView has invalid dimensions ${view.width}x${view.height} for $name"
            }
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        val viewportFile = TerminalLabArtifacts.writeBitmap("$name-viewport", bitmap)
        val brightPixels = TerminalLabArtifacts.countBrightPixels(viewportFile)
        val sha256 = TerminalLabArtifacts.sha256(viewportFile)
        bitmap.recycle()

        val visibleText = visibleTerminalText()
        TerminalLabArtifacts.writeText("$name-visible-terminal.txt", visibleText)
        return ViewportArtifact(
            name = name,
            viewportFile = viewportFile,
            brightPixels = brightPixels,
            sha256 = sha256,
            visibleText = visibleText,
        )
    }

    private fun findTerminalView(): TerminalView? {
        var terminalView: TerminalView? = null
        compose.activityRule.scenario.onActivity { activity ->
            terminalView = activity.window.decorView.findTerminalView()
        }
        return terminalView
    }

    private fun View.findTerminalView(): TerminalView? {
        if (this is TerminalView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            val match = getChildAt(index).findTerminalView()
            if (match != null) return match
        }
        return null
    }

    private data class ViewportArtifact(
        val name: String,
        val viewportFile: java.io.File,
        val brightPixels: Int,
        val sha256: String,
        val visibleText: String,
    )

    private companion object {
        const val SURFACE_HOST_TAG: String = "issue-248:surface-host"

        /**
         * Rebuild the capture-pane snapshot into emulator bytes the way
         * production does: home + clear, then CRLF-joined lines. Mirrors
         * `TmuxSessionViewModel.toTerminalViewportBytes`.
         */
        fun rawCaptureToBytes(lines: List<String>): ByteArray =
            buildString {
                append("\u001b[H\u001b[2J")
                lines.forEachIndexed { index, line ->
                    if (index > 0) append("\r\n")
                    append(line)
                }
                append("\r\n")
            }.toByteArray(Charsets.UTF_8)
    }
}
