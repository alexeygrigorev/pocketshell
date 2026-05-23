package com.pocketshell.app.terminal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.SshKey
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
class TerminalLabDockerTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<TerminalLabActivity>? = null
    private val timings = mutableListOf<String>()
    private val screenshots = mutableListOf<TerminalScreenshotArtifact>()

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
    }

    @Test
    fun terminalLabConnectsAndRunsStressCommandsThroughInputPath() = runBlocking {
        runTerminalWorkbench(
            markerPrefix = "pslab",
            capturePrefix = "",
            holdOpenMs = 0L,
        )
    }

    @Test
    fun terminalWorkbenchKeepsDockerShellOpenForVisualIteration() = runBlocking {
        val holdOpenMs = InstrumentationRegistry.getArguments()
            .getString("terminalWorkbenchHoldMs")
            ?.toLongOrNull()
            ?: 0L
        runTerminalWorkbench(
            markerPrefix = "psworkbench",
            capturePrefix = "workbench-",
            holdOpenMs = holdOpenMs,
        )
    }

    @Test
    fun terminalWorkbenchCapturesRealAgentCliScreens() = runBlocking {
        launchTerminalWorkbench(markerPrefix = "psagent")
        assertRemotePtyMatchesTerminalGrid("agents")
        val promptArtifact = captureAndAssertTerminalInk("agents-01-prompt", minInkPixels = 1_500)

        runRealAgentCli(
            command = "opencode",
            versionExpected = "1.",
            screenExpected = "Ask anything",
            screenshotName = "agents-02-opencode",
            baselineArtifact = promptArtifact,
        )
        runRealAgentCli(
            command = "codex",
            versionExpected = "codex-cli",
            screenExpected = "Welcome to Codex",
            screenshotName = "agents-03-codex",
            baselineArtifact = promptArtifact,
        )
        runRealAgentCli(
            command = "claude",
            versionExpected = "Claude Code",
            screenExpected = "Welcome to Claude Code",
            screenshotName = "agents-04-claude",
            baselineArtifact = promptArtifact,
        )

        val debugHoldMs = InstrumentationRegistry.getArguments()
            .getString("terminalWorkbenchDebugHoldMs")
            ?.toLongOrNull()
            ?: 0L
        if (debugHoldMs > 0L) {
            SystemClock.sleep(debugHoldMs)
            captureAndAssertTerminalInk("agents-99-debug-hold-current", minInkPixels = 6_000)
        }

        TerminalLabArtifacts.writeTimings(timings)
        writeArtifactSummary("agents")
        Unit
    }

    private suspend fun runTerminalWorkbench(
        markerPrefix: String,
        capturePrefix: String,
        holdOpenMs: Long,
    ) {
        val marker = launchTerminalWorkbench(markerPrefix)
        assertRemotePtyMatchesTerminalGrid(capturePrefix.ifBlank { "terminal-lab" }.trimEnd('-'))
        captureAndAssertTerminalInk("${capturePrefix}01-connected-prompt", minInkPixels = 1_500)

        sendViaTerminalInput("printf 'PWD-$marker\\n'; pwd", "PWD-$marker", "pwd")
        sendViaTerminalInput("printf 'LS-$marker\\n'; ls -la /home/testuser /usr/local/bin", "LS-$marker", "ls")
        captureAndAssertTerminalInk("${capturePrefix}02-pwd-ls", minInkPixels = 4_000)

        val longPath = "/tmp/pocketshell-lab-$marker/alpha/beta/gamma/delta/epsilon/zeta/eta/theta/iota/kappa"
        sendViaTerminalInput(
            "long_path='$longPath'; mkdir -p \"\$long_path\"; printf 'LONG-$marker %s\\n' \"\$long_path\"",
            "LONG-$marker $longPath",
            "long-path",
        )
        sendViaTerminalInput(
            "printf 'GITSTATUS-$marker\\nOn branch main\\nYour branch is up to date with origin/main.\\n\\nChanges not staged for commit:\\n  modified: app/src/main/java/com/pocketshell/app/terminal/TerminalLabActivity.kt\\n\\nUntracked files:\\n  app/src/androidTest/java/com/pocketshell/app/terminal/TerminalLabDockerTest.kt\\n'",
            "GITSTATUS-$marker",
            "git-status-style",
        )
        captureAndAssertTerminalInk("${capturePrefix}03-long-path-git-status", minInkPixels = 6_000)

        sendBackspaceEditViaTerminalInput(marker)
        sendViaTerminalInput(
            "for i in 1 2 3; do printf 'REPEAT-$marker-%s\\n' \"\$i\"; done",
            "REPEAT-$marker-3",
            "repeated-commands",
        )
        captureAndAssertTerminalInk("${capturePrefix}04-backspace-repeat", minInkPixels = 6_000)
        writeWorkbenchSummary(capturePrefix, marker)
        TerminalLabArtifacts.writeTimings(timings)

        val transcript = transcriptSnapshot()
        listOf(
            "PWD-$marker",
            "/home/testuser",
            "LS-$marker",
            "LONG-$marker $longPath",
            "GITSTATUS-$marker",
            "lab-edit-good-$marker",
            "REPEAT-$marker-1",
            "REPEAT-$marker-2",
            "REPEAT-$marker-3",
        ).forEach { expected ->
            assertTrue("expected terminal transcript to contain '$expected', got:\n$transcript", expected in transcript)
        }

        if (holdOpenMs > 0L) {
            SystemClock.sleep(holdOpenMs)
            captureAndAssertTerminalInk("${capturePrefix}05-held-open", minInkPixels = 6_000)
        }
    }

    private suspend fun launchTerminalWorkbench(markerPrefix: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val key = instrumentation.context.assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        val sshKey = SshKey.Pem(key)
        val port = InstrumentationRegistry.getArguments()
            .getString("terminalWorkbenchSshPort")
            ?.toIntOrNull()
            ?: DEFAULT_PORT
        waitForSshFixtureReady(sshKey, port = port)

        val marker = "${markerPrefix}${System.currentTimeMillis()}"
        val intent = TerminalLabActivity.intent(
            context = appContext,
            host = DEFAULT_HOST,
            port = port,
            user = DEFAULT_USER,
            privateKeyPem = key,
        )

        launchedActivity = ActivityScenario.launch(intent)
        compose.onNodeWithTag(TERMINAL_LAB_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForPrompt()
        waitForVisibleTerminalText("prompt") { it.isNotBlank() }
        recordTiming("connect_to_prompt_ms", requireController().uiState.value.connectToPromptMs)
        return marker
    }

    private fun runRealAgentCli(
        command: String,
        versionExpected: String,
        screenExpected: String,
        screenshotName: String,
        baselineArtifact: TerminalScreenshotArtifact,
    ) {
        sendViaTerminalInput("$command --version", versionExpected, "$command-version")
        requireController().sendText(command, withEnter = true)
        waitForVisibleTerminalText("$command-screen") { screenExpected in it }
        val screenArtifact = captureAndAssertTerminalInk(
            name = screenshotName,
            minInkPixels = 6_000,
            requireDeviceInk = false,
        )
        assertTrue(
            "expected $command viewport capture to differ from prompt baseline; " +
                "baseline=${baselineArtifact.fileName} screen=${screenArtifact.fileName}",
            baselineArtifact.sha256 != screenArtifact.sha256,
        )
        TerminalLabArtifacts.writeText("$screenshotName-visible-terminal.txt", visibleTerminalText())
        requireController().terminalState.writeInput(byteArrayOf(0x03))
        SystemClock.sleep(500)
        requireController().sendText("", withEnter = true)
        waitForVisibleTerminalText("$command-return-to-prompt") { it.isNotBlank() }
    }

    private fun sendViaTerminalInput(command: String, expected: String, label: String) {
        val start = SystemClock.elapsedRealtime()
        requireController().sendText(command, withEnter = true)
        waitForTranscript(label) { expected in it }
        waitForVisibleTerminalText(label) { expected in it }
        recordTiming("send_to_output_${label}_ms", SystemClock.elapsedRealtime() - start)
    }

    private fun sendBackspaceEditViaTerminalInput(marker: String) {
        val start = SystemClock.elapsedRealtime()
        val controller = requireController()
        controller.sendText("printf 'lab-edit-bad", withEnter = false)
        controller.sendText("\u007F\u007F\u007F", withEnter = false)
        controller.terminalState.writeInput(byteArrayOf(0x15))
        controller.sendText("printf 'lab-edit-good-$marker'", withEnter = true)
        waitForTranscript("backspace-edit") { "lab-edit-good-$marker" in it }
        waitForVisibleTerminalText("backspace-edit") { "lab-edit-good-$marker" in it }
        recordTiming("send_to_output_backspace_edit_ms", SystemClock.elapsedRealtime() - start)
    }

    private fun assertRemotePtyMatchesTerminalGrid(label: String) {
        val start = SystemClock.elapsedRealtime()
        var lastVisible = ""
        var lastExpected = ""
        for (attempt in 1..5) {
            val grid = terminalGridSize()
            val marker = "PTY-$label-$attempt"
            val expected = "$marker ${grid.rows} ${grid.columns}"
            lastExpected = expected
            requireController().sendText("printf '$marker '; stty size", withEnter = true)
            waitForTranscript("$label-pty-size-$attempt") { marker in it }
            waitForVisibleTerminalText("$label-pty-size-$attempt") {
                lastVisible = it
                marker in it
            }
            if (expected in lastVisible) {
                recordTiming("send_to_output_${label}_pty_size_ms", SystemClock.elapsedRealtime() - start)
                return
            }
            SystemClock.sleep(500)
        }
        assertTrue(
            "expected remote PTY to match current terminal grid '$lastExpected', got visible terminal:\n$lastVisible",
            lastExpected in lastVisible,
        )
    }

    private fun terminalGridSize(): TerminalGridSize {
        var grid: TerminalGridSize? = null
        launchedActivity?.onActivity { activity ->
            activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.let { emulator ->
                    grid = TerminalGridSize(columns = emulator.mColumns, rows = emulator.mRows)
                }
        }
        return checkNotNull(grid) { "Terminal emulator grid was not available" }
    }

    private fun waitForPrompt() {
        compose.waitUntil(timeoutMillis = 30_000) {
            requireController().uiState.value.connectToPromptMs != null
        }
    }

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = 20_000) {
            findTerminalView()?.currentSession != null
        }
    }

    private fun waitForTranscript(label: String, predicate: (String) -> Boolean) {
        var last = ""
        try {
            compose.waitUntil(timeoutMillis = 20_000) {
                last = transcriptSnapshot()
                predicate(last)
            }
        } catch (t: Throwable) {
            TerminalLabArtifacts.writeText("failure-$label-transcript.txt", last)
            TerminalLabArtifacts.capture("failure-$label")
            throw t
        }
        assertTrue("expected transcript predicate for $label, got:\n$last", predicate(last))
    }

    private fun transcriptSnapshot(): String = requireController().transcriptSnapshot()

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

    private fun waitForVisibleTerminalText(label: String, predicate: (String) -> Boolean) {
        var last = ""
        try {
            compose.waitUntil(timeoutMillis = 20_000) {
                last = visibleTerminalText()
                predicate(last)
            }
        } catch (t: Throwable) {
            TerminalLabArtifacts.writeText("failure-$label-visible-terminal.txt", last)
            TerminalLabArtifacts.capture("failure-$label-visible-terminal")
            throw t
        }
        assertTrue("expected visible terminal text predicate for $label, got:\n$last", predicate(last))
    }

    private fun captureAndAssertTerminalInk(
        name: String,
        minInkPixels: Int,
        requireDeviceInk: Boolean = true,
    ): TerminalScreenshotArtifact {
        val bounds = terminalViewBounds()
        val grid = terminalGridSize()
        var deviceScreenshot: File? = null
        var viewportScreenshot: File? = null
        var deviceInkPixels = 0
        var viewportInkPixels = 0
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            deviceScreenshot = TerminalLabArtifacts.capture(name)
            viewportScreenshot = captureTerminalViewport("$name-viewport")
            deviceInkPixels = TerminalLabArtifacts.countBrightPixels(deviceScreenshot, bounds)
            viewportInkPixels = TerminalLabArtifacts.countBrightPixels(viewportScreenshot)
            val deviceSatisfied = !requireDeviceInk || deviceInkPixels >= minInkPixels
            if (deviceSatisfied && viewportInkPixels >= minInkPixels) break
            SystemClock.sleep(250)
        } while (SystemClock.elapsedRealtime() < deadline)

        TerminalLabArtifacts.writeText("$name-visible-terminal.txt", visibleTerminalText())
        recordTiming("visible_ink_${name}_px", deviceInkPixels.toLong())
        recordTiming("viewport_ink_${name}_px", viewportInkPixels.toLong())
        val finalDeviceScreenshot = checkNotNull(deviceScreenshot) { "device screenshot was not captured" }
        val finalViewportScreenshot = checkNotNull(viewportScreenshot) { "terminal viewport screenshot was not captured" }
        val viewportHash = TerminalLabArtifacts.sha256(finalViewportScreenshot)
        val artifact = TerminalScreenshotArtifact(
            name = name,
            fileName = finalViewportScreenshot.name,
            deviceFileName = finalDeviceScreenshot.name,
            bounds = bounds,
            grid = grid,
            brightPixels = viewportInkPixels,
            deviceBrightPixels = deviceInkPixels,
            sha256 = viewportHash,
        )
        screenshots += artifact
        if (requireDeviceInk) {
            assertTrue(
                "expected terminal viewport in actual device screenshot to contain shell output ink; " +
                    "deviceBrightPixels=$deviceInkPixels min=$minInkPixels bounds=$bounds " +
                    "device=${finalDeviceScreenshot.absolutePath}",
                deviceInkPixels >= minInkPixels,
            )
        }
        assertTrue(
            "expected direct terminal viewport render in $name screenshot to contain shell output ink; " +
                "viewportBrightPixels=$viewportInkPixels min=$minInkPixels bounds=$bounds " +
                "viewport=${finalViewportScreenshot.absolutePath} device=${finalDeviceScreenshot.absolutePath}",
            viewportInkPixels >= minInkPixels,
        )
        return artifact
    }

    private fun captureTerminalViewport(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)

        lateinit var bitmap: Bitmap
        launchedActivity?.onActivity { activity ->
            val view = checkNotNull(activity.window.decorView.findTerminalView()) {
                "TerminalView was not found"
            }
            check(view.width > 0 && view.height > 0) {
                "TerminalView has invalid dimensions ${view.width}x${view.height}"
            }
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }

        return TerminalLabArtifacts.writeBitmap(name, bitmap).also {
            bitmap.recycle()
        }
    }

    private fun terminalViewBounds(): Rect {
        var bounds: Rect? = null
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView()
            if (view != null) {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                bounds = Rect(
                    location[0],
                    location[1],
                    location[0] + view.width,
                    location[1] + view.height,
                )
            }
        }
        return checkNotNull(bounds) { "TerminalView was not found" }
    }

    private fun requireController(): TerminalLabController {
        var controller: TerminalLabController? = null
        launchedActivity?.onActivity { activity ->
            controller = activity.controller
        }
        return checkNotNull(controller) { "TerminalLabActivity was not launched" }
    }

    private fun findTerminalView(): TerminalView? {
        var terminalView: TerminalView? = null
        launchedActivity?.onActivity { activity ->
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

    private fun recordTiming(name: String, value: Long?) {
        val line = "TERMINAL_LAB_TIMING $name=${value ?: -1}"
        timings += line
        println(line)
    }

    private fun writeWorkbenchSummary(capturePrefix: String, marker: String) {
        if (capturePrefix.isBlank()) return
        writeArtifactSummary(capturePrefix.trimEnd('-'), marker)
    }

    private fun writeArtifactSummary(label: String, marker: String? = null) {
        val bounds = terminalViewBounds()
        val grid = terminalGridSize()
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .resources
            .displayMetrics
            .density
        TerminalLabArtifacts.writeText(
            "$label-summary.txt",
            buildString {
                if (marker != null) appendLine("marker=$marker")
                appendLine("terminal_grid_columns=${grid.columns}")
                appendLine("terminal_grid_rows=${grid.rows}")
                appendLine("terminal_bounds=$bounds")
                appendLine("display_density=${String.format(Locale.US, "%.2f", density)}")
                appendLine("transcript_chars=${transcriptSnapshot().length}")
                appendLine("visible_terminal_chars=${visibleTerminalText().length}")
                appendLine()
                appendLine("screenshots:")
                screenshots.forEach { screenshot ->
                    appendLine(
                        "${screenshot.fileName} " +
                            "device=${screenshot.deviceFileName} " +
                            "name=${screenshot.name} " +
                            "grid=${screenshot.grid.columns}x${screenshot.grid.rows} " +
                            "bounds=${screenshot.bounds} " +
                            "viewport_bright_pixels=${screenshot.brightPixels} " +
                            "device_bright_pixels=${screenshot.deviceBrightPixels} " +
                            "sha256=${screenshot.sha256}",
                    )
                }
                appendLine()
                appendLine("visible_terminal:")
                appendLine(visibleTerminalText())
            },
        )
    }
}

private data class TerminalScreenshotArtifact(
    val name: String,
    val fileName: String,
    val deviceFileName: String,
    val bounds: Rect,
    val grid: TerminalGridSize,
    val brightPixels: Int,
    val deviceBrightPixels: Int,
    val sha256: String,
)

private data class TerminalGridSize(
    val columns: Int,
    val rows: Int,
)

object TerminalLabArtifacts {
    private const val DEVICE_DIR_NAME: String = "terminal-lab"

    fun capture(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        return writeBitmap(name, bitmap).also {
            bitmap.recycle()
        }
    }

    fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Could not write screenshot: ${file.absolutePath}"
            }
        }
        println("TERMINAL_LAB_SCREENSHOT ${file.absolutePath}")
        return file
    }

    fun writeTimings(lines: List<String>): File {
        val file = artifactFile("timings.txt")
        file.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
        println("TERMINAL_LAB_TIMINGS ${file.absolutePath}")
        return file
    }

    fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("TERMINAL_LAB_TEXT ${file.absolutePath}")
        return file
    }

    fun countBrightPixels(file: File, bounds: Rect): Int {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            ?: error("Could not decode screenshot: ${file.absolutePath}")
        try {
            val left = max(0, bounds.left)
            val top = max(0, bounds.top)
            val right = min(bitmap.width, bounds.right)
            val bottom = min(bitmap.height, bounds.bottom)
            var brightPixels = 0
            for (y in top until bottom) {
                for (x in left until right) {
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

    fun countBrightPixels(file: File): Int {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            ?: error("Could not decode screenshot: ${file.absolutePath}")
        try {
            return countBrightPixels(bitmap, Rect(0, 0, bitmap.width, bitmap.height))
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
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private fun countBrightPixels(bitmap: Bitmap, bounds: Rect): Int {
        val left = max(0, bounds.left)
        val top = max(0, bounds.top)
        val right = min(bitmap.width, bounds.right)
        val bottom = min(bitmap.height, bounds.bottom)
        var brightPixels = 0
        for (y in top until bottom) {
            for (x in left until right) {
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
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create terminal lab artifact directory: ${dir.absolutePath}"
        }
        return File(dir, name)
    }
}
