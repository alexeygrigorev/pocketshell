package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.session.SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.migrations.MIGRATION_1_2
import com.pocketshell.core.storage.migrations.MIGRATION_2_3
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * End-to-end emulator coverage for the real PocketShell app flows.
 *
 * These tests intentionally drive MainActivity instead of the isolated
 * TerminalLabActivity. The authoritative visual evidence is a direct
 * TerminalView viewport render plus the terminal emulator's visible text.
 */
@RunWith(AndroidJUnit4::class)
class EmulatorWorkflowE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val timings = mutableListOf<String>()
    private val captures = mutableListOf<WorkflowCapture>()

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
    }

    @Test
    fun realAppRawSshJourneyRunsShellCommandsAndInteractiveTui() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))
        val marker = "psraw${System.currentTimeMillis()}"
        val hostRowTag = seedDockerHost(key, "Workflow Raw Docker $marker")

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        openHostPicker(hostRowTag, "Workflow Raw Docker $marker")
        val openStart = SystemClock.elapsedRealtime()
        waitForPickerAction("Continue with SSH")
        compose.onNodeWithText("Continue with SSH").performClick()
        compose.onNodeWithTag(SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForVisibleTerminalText("raw ssh prompt") { it.isNotBlank() }
        recordTiming("raw_connect_to_prompt_ms", SystemClock.elapsedRealtime() - openStart)
        val prompt = captureAndAssert("raw-01-connected-prompt", minBrightPixels = 1_500)

        assertRemotePtyMatchesTerminalGrid("raw", marker)

        val workDir = "/tmp/pocketshell-e2e-$marker/alpha"
        val commandStart = SystemClock.elapsedRealtime()
        sendText(
            "mkdir -p '$workDir'; cd '$workDir'; " +
                "printf 'PWD-$marker '; pwd; " +
                "touch ls-visible-$marker.txt; " +
                "printf 'LS-$marker\\n'; ls -1",
            withEnter = true,
        )
        waitForVisibleTerminalText("mkdir cd ls output") {
            "PWD-$marker $workDir" in it &&
                "LS-$marker" in it &&
                "ls-visible-$marker.txt" in it
        }
        recordTiming("raw_command_to_output_ls_mkdir_cd_ms", SystemClock.elapsedRealtime() - commandStart)
        val shellCommands = captureAndAssert("raw-02-ls-mkdir-cd", minBrightPixels = 4_000)
        assertNotEquals(
            "expected shell command viewport to differ from prompt baseline",
            prompt.sha256,
            shellCommands.sha256,
        )

        sendText("pocketshell-tui-smoke", withEnter = true)
        waitForVisibleTerminalText("tui started") {
            "PocketShell interactive TUI smoke" in it && "draft:" in it
        }
        val tuiStart = captureAndAssert("raw-03-tui-started", minBrightPixels = 4_000)

        val input = terminalInputConnection()
        val inputStart = SystemClock.elapsedRealtime()
        input.commitText("abc", 1)
        waitForVisibleTerminalText("tui draft abc") { it.hasVisibleDraft("abc") }
        input.deleteSurroundingText(1, 0)
        waitForVisibleTerminalText("tui draft ab") { it.hasVisibleDraft("ab") }
        input.commitText("d", 1)
        waitForVisibleTerminalText("tui draft abd") { it.hasVisibleDraft("abd") }
        recordTiming("raw_tui_input_to_visible_state_ms", SystemClock.elapsedRealtime() - inputStart)
        val tuiInput = captureAndAssert("raw-04-tui-input-effect", minBrightPixels = 4_000)
        assertNotEquals(
            "expected TUI input viewport to differ from initial TUI viewport",
            tuiStart.sha256,
            tuiInput.sha256,
        )

        writeSummary("raw-ssh-workflow")
    }

    @Test
    fun realAppTmuxJourneyAttachesSessionAndAcceptsTerminalInput() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))
        val marker = "t${System.currentTimeMillis().toString(36).takeLast(5)}"
        val sessionName = "claude-main"
        val hostRowTag = seedDockerHost(key, "Workflow Tmux Docker $marker")
        prepareTmuxSession(key, sessionName, marker)

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        openHostPicker(hostRowTag, "Workflow Tmux Docker $marker")
        val sessionOpenStart = SystemClock.elapsedRealtime()
        waitForPickerAction(sessionName)
        compose.onNodeWithText(sessionName).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        recordTiming("tmux_session_open_ms", SystemClock.elapsedRealtime() - sessionOpenStart)

        val openMarkerStart = SystemClock.elapsedRealtime()
        sendCommandThroughTerminalInput("printf 'OPEN-$marker\\n'", "tmux open marker")
        waitForVisibleTerminalText("tmux session open marker") {
            "OPEN-$marker" in it
        }
        recordTiming("tmux_open_marker_to_output_ms", SystemClock.elapsedRealtime() - openMarkerStart)
        val opened = captureAndAssert("tmux-01-session-open-input", minBrightPixels = 1_500)

        val workDir = "/tmp/p-$marker"
        val inputStart = SystemClock.elapsedRealtime()
        sendCommandThroughTerminalInput(
            "d=$workDir;mkdir -p \$d;cd \$d;printf 'I$marker\\nP$marker ';pwd",
            "tmux input effect",
        )
        waitForVisibleTerminalText("tmux input effect") {
            "I$marker" in it &&
                "P$marker $workDir" in it
        }
        recordTiming("tmux_input_to_output_ms", SystemClock.elapsedRealtime() - inputStart)
        val inputEffect = captureAndAssert("tmux-02-input-effect", minBrightPixels = 3_000)
        assertNotEquals(
            "expected tmux input viewport to differ from opened session viewport",
            opened.sha256,
            inputEffect.sha256,
        )

        writeSummary("tmux-workflow")
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedDockerHost(key: String, hostName: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .fallbackToDestructiveMigration(dropAllTables = false)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "workflow-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = hostName,
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    private fun openHostPicker(hostRowTag: String, hostName: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText(hostName, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText("Tmux sessions", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForPickerAction(text: String) {
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText(text, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private suspend fun prepareTmuxSession(key: String, sessionName: String, marker: String) {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec(
                    "tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true; " +
                        "tmux new-session -d -s ${shellQuote(sessionName)} " +
                        "${shellQuote("printf 'READY-$marker\\n'; exec sh")}",
                )
            }
        }
        val exec = result.getOrNull()
        assertTrue(
            "expected tmux session setup to succeed, got ${result.exceptionOrNull()} " +
                "stdout='${exec?.stdout}' stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
    }

    private fun assertRemotePtyMatchesTerminalGrid(label: String, marker: String) {
        val grid = terminalGridSize()
        val ptyMarker = "PTY-$label-$marker"
        val expected = "$ptyMarker ${grid.rows} ${grid.columns}"
        val start = SystemClock.elapsedRealtime()
        sendText("printf '$ptyMarker '; stty size", withEnter = true)
        waitForVisibleTerminalText("$label pty size") { expected in it }
        recordTiming("${label}_pty_size_probe_ms", SystemClock.elapsedRealtime() - start)
    }

    private fun sendText(text: String, withEnter: Boolean) {
        val payload = if (withEnter) "$text\n" else text
        val committed = terminalInputConnection().commitText(payload, 1)
        assertTrue("expected terminal input connection to commit `$text`", committed)
    }

    private fun sendCommandThroughTerminalInput(command: String, label: String) {
        command.chunked(4).forEach { chunk ->
            val committed = terminalInputConnection().commitText(chunk, 1)
            assertTrue("expected terminal input connection to commit `$chunk` for $label", committed)
            SystemClock.sleep(35)
        }
        waitForVisibleTerminalText("$label command echo", timeoutMillis = 5_000) {
            command in it
        }
        val enterCommitted = terminalInputConnection().commitText("\n", 1)
        assertTrue("expected terminal input connection to submit $label", enterCommitted)
    }

    private fun terminalInputConnection(): InputConnection {
        var connection: InputConnection? = null
        launchedActivity?.onActivity { activity ->
            val terminalView = activity.window.decorView.findTerminalView()
            requireNotNull(terminalView) { "TerminalView was not found" }
            terminalView.requestFocus()
            connection = terminalView.onCreateInputConnection(EditorInfo())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return requireNotNull(connection) { "TerminalView did not create an InputConnection" }
    }

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = 20_000) {
            var attached = false
            launchedActivity?.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession != null && view.mEmulator != null
            }
            attached
        }
    }

    private fun waitForVisibleTerminalText(
        label: String,
        timeoutMillis: Long = 20_000,
        predicate: (String) -> Boolean,
    ) {
        var last = ""
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                last = visibleTerminalText()
                predicate(last)
            }
            true
        }.getOrDefault(false)
        if (!satisfied) {
            WorkflowArtifacts.writeText("failure-$label-visible-terminal.txt", last.printableForFailure())
        }
        assertTrue(
            "expected visible terminal text for $label, got:\n${last.printableForFailure()}",
            predicate(last),
        )
    }

    private fun captureAndAssert(name: String, minBrightPixels: Int): WorkflowCapture {
        var capture: WorkflowCapture? = null
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            capture = captureViewport(name)
            if (capture.brightPixels >= minBrightPixels && capture.visibleText.isNotBlank()) break
            SystemClock.sleep(250)
        }
        val finalCapture = checkNotNull(capture) { "terminal viewport capture failed" }
        captures += finalCapture
        recordTiming("viewport_ink_${name}_px", finalCapture.brightPixels.toLong())
        assertTrue(
            "expected direct terminal viewport render for $name to contain visible output; " +
                "brightPixels=${finalCapture.brightPixels} min=$minBrightPixels " +
                "viewport=${finalCapture.viewportFile.absolutePath}",
            finalCapture.brightPixels >= minBrightPixels,
        )
        assertTrue(
            "expected visible terminal sidecar for $name to be non-blank; " +
                "sidecar=${finalCapture.visibleTextFile.absolutePath}",
            finalCapture.visibleText.isNotBlank(),
        )
        return finalCapture
    }

    private fun captureViewport(name: String): WorkflowCapture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)

        lateinit var bitmap: Bitmap
        lateinit var bounds: Rect
        val grid = terminalGridSize()
        launchedActivity?.onActivity { activity ->
            val view = requireNotNull(activity.window.decorView.findTerminalView()) {
                "TerminalView was not found"
            }
            require(view.width > 0 && view.height > 0) {
                "TerminalView has invalid dimensions ${view.width}x${view.height}"
            }
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            bounds = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        val viewport = WorkflowArtifacts.writeBitmap("$name-viewport", bitmap)
        bitmap.recycle()
        val visible = visibleTerminalText()
        val sidecar = WorkflowArtifacts.writeText("$name-visible-terminal.txt", visible.printableForFailure())
        return WorkflowCapture(
            name = name,
            viewportFile = viewport,
            visibleTextFile = sidecar,
            bounds = bounds,
            grid = grid,
            brightPixels = WorkflowArtifacts.countBrightPixels(viewport),
            sha256 = WorkflowArtifacts.sha256(viewport),
            visibleText = visible,
        )
    }

    private fun visibleTerminalText(): String {
        var text = ""
        launchedActivity?.onActivity { activity ->
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

    private fun terminalGridSize(): WorkflowGridSize {
        var grid: WorkflowGridSize? = null
        launchedActivity?.onActivity { activity ->
            activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.let { emulator ->
                    grid = WorkflowGridSize(columns = emulator.mColumns, rows = emulator.mRows)
                }
        }
        return requireNotNull(grid) { "Terminal emulator grid was not available" }
    }

    private fun writeSummary(label: String) {
        val grid = terminalGridSize()
        WorkflowArtifacts.writeText(
            "$label-summary.txt",
            buildString {
                appendLine("terminal_grid_columns=${grid.columns}")
                appendLine("terminal_grid_rows=${grid.rows}")
                appendLine("capture_policy=authoritative direct TerminalView viewport render plus visible terminal text")
                appendLine()
                appendLine("timings:")
                timings.forEach(::appendLine)
                appendLine()
                appendLine("captures:")
                captures.forEach { capture ->
                    appendLine(
                        "${capture.viewportFile.name} " +
                            "name=${capture.name} " +
                            "grid=${capture.grid.columns}x${capture.grid.rows} " +
                            "bounds=${capture.bounds} " +
                            "viewport_bright_pixels=${capture.brightPixels} " +
                            "viewport_sha256=${capture.sha256} " +
                            "visible_terminal_chars=${capture.visibleText.length} " +
                            "visible_terminal_sidecar=${capture.visibleTextFile.name}",
                    )
                }
            },
        )
        WorkflowArtifacts.writeTimings("$label-timings.txt", timings)
    }

    private fun recordTiming(name: String, value: Long) {
        val line = "WORKFLOW_E2E_TIMING $name=$value"
        timings += line
        println(line)
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

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun String.hasVisibleDraft(expected: String): Boolean {
        val match = Regex("""draft:([^\s]*)\s+last:""").find(this) ?: return false
        return match.groupValues[1] == expected
    }

    private fun String.printableForFailure(): String =
        buildString(length) {
            for (ch in this@printableForFailure) {
                when {
                    ch == '\u001b' -> append("<ESC>")
                    ch == '\r' -> append("<CR>")
                    ch == '\u0000' -> append("<NUL>")
                    ch < ' ' && ch != '\n' && ch != '\t' -> append("<0x${ch.code.toString(16)}>")
                    else -> append(ch)
                }
            }
        }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
    }
}

private data class WorkflowCapture(
    val name: String,
    val viewportFile: File,
    val visibleTextFile: File,
    val bounds: Rect,
    val grid: WorkflowGridSize,
    val brightPixels: Int,
    val sha256: String,
    val visibleText: String,
)

private data class WorkflowGridSize(
    val columns: Int,
    val rows: Int,
)

private object WorkflowArtifacts {
    private const val DEVICE_DIR_NAME: String = "workflow-e2e"

    fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Could not write screenshot: ${file.absolutePath}"
            }
        }
        println("WORKFLOW_E2E_SCREENSHOT ${file.absolutePath}")
        return file
    }

    fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("WORKFLOW_E2E_TEXT ${file.absolutePath}")
        return file
    }

    fun writeTimings(name: String, lines: List<String>): File {
        val file = artifactFile(name)
        file.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
        println("WORKFLOW_E2E_TIMINGS ${file.absolutePath}")
        return file
    }

    fun countBrightPixels(file: File): Int {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            ?: error("Could not decode screenshot: ${file.absolutePath}")
        try {
            var brightPixels = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    val luminance = (
                        Color.red(pixel) * 299 +
                            Color.green(pixel) * 587 +
                            Color.blue(pixel) * 114
                        ) / 1000
                    if (luminance > 120) brightPixels++
                }
            }
            return brightPixels
        } finally {
            bitmap.recycle()
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(Locale.US, it) }
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create workflow artifact directory: ${dir.absolutePath}"
        }
        return File(dir, name)
    }
}
