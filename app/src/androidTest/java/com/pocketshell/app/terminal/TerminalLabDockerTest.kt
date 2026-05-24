package com.pocketshell.app.terminal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.ParcelFileDescriptor
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
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
class TerminalLabDockerTest {

    private companion object {
        const val REAL_AGENT_CLI_SCREENS_ARG = "terminalWorkbenchRealAgents"
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<TerminalLabActivity>? = null
    private val timings = mutableListOf<String>()
    private val screenshots = mutableListOf<TerminalScreenshotArtifact>()
    private val captureHelper = TerminalCaptureHelper(
        terminalBounds = ::terminalViewBounds,
        terminalGrid = ::terminalGridSize,
        visibleTerminalText = ::visibleTerminalText,
        captureTerminalViewport = ::captureTerminalViewport,
    )

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
        assumeRealAgentCliScreensEnabled()
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

    private fun assumeRealAgentCliScreensEnabled() {
        val enabled = InstrumentationRegistry.getArguments()
            .getString(REAL_AGENT_CLI_SCREENS_ARG)
            ?.lowercase(Locale.US) in setOf("1", "true", "yes")
        assumeTrue(
            "Real agent CLI screen capture requires -e $REAL_AGENT_CLI_SCREENS_ARG 1",
            enabled,
        )
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
    ): TerminalScreenshotArtifact {
        var artifact: TerminalScreenshotArtifact? = null
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            artifact = captureHelper.capture(name)
            if (artifact.brightPixels >= minInkPixels && artifact.visibleTerminalText.isNotBlank()) break
            SystemClock.sleep(250)
        } while (SystemClock.elapsedRealtime() < deadline)

        val finalArtifact = checkNotNull(artifact) { "terminal visual artifact was not captured" }
        recordTiming("visible_ink_${name}_px", finalArtifact.deviceBrightPixels.toLong())
        recordTiming("viewport_ink_${name}_px", finalArtifact.brightPixels.toLong())
        recordTiming("device_screencap_ink_${name}_px", finalArtifact.deviceScreencapBrightPixels?.toLong())
        screenshots += finalArtifact
        assertTrue(
            "expected direct terminal viewport render in $name screenshot to contain shell output ink; " +
                "viewportBrightPixels=${finalArtifact.brightPixels} min=$minInkPixels bounds=${finalArtifact.bounds} " +
                "viewport=${finalArtifact.viewportFile.absolutePath} advisoryDevice=${finalArtifact.deviceFile.absolutePath}",
            finalArtifact.brightPixels >= minInkPixels,
        )
        assertTrue(
            "expected terminal emulator visible text to be non-blank for $name; " +
                "viewport=${finalArtifact.viewportFile.absolutePath}",
            finalArtifact.visibleTerminalText.isNotBlank(),
        )
        return finalArtifact
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
                appendLine("capture_policy:")
                appendLine("authoritative=direct TerminalView viewport render plus terminal emulator visible text")
                appendLine("advisory=full-device/window screenshots; saved for diagnosis but not used for terminal ink assertions")
                appendLine(
                    "advisory_device_strategy=adopted UiAutomation.executeShellCommand(\"screencap -p\") " +
                        "when available; UiAutomation.takeScreenshot retained as fallback/legacy evidence",
                )
                appendLine()
                appendLine("authoritative_captures:")
                screenshots.forEach { screenshot ->
                    appendLine(
                        "${screenshot.fileName} " +
                            "name=${screenshot.name} " +
                            "grid=${screenshot.grid.columns}x${screenshot.grid.rows} " +
                            "bounds=${screenshot.bounds} " +
                            "viewport_bright_pixels=${screenshot.brightPixels} " +
                            "viewport_sha256=${screenshot.sha256} " +
                            "visible_terminal_chars=${screenshot.visibleTerminalText.length}",
                    )
                }
                appendLine()
                appendLine("advisory_device_captures:")
                screenshots.forEach { screenshot ->
                    appendLine(
                        "${screenshot.deviceFileName} " +
                            "name=${screenshot.name} " +
                            "device_bright_pixels=${screenshot.deviceBrightPixels} " +
                            "device_sha256=${screenshot.deviceSha256} " +
                            "device_blank=${screenshot.deviceAppearsBlank} " +
                            "device_contradicts_authoritative=${screenshot.deviceContradictsAuthoritative}",
                    )
                    appendLine(
                        "${screenshot.deviceScreencapFileName ?: "device_screencap_unavailable"} " +
                            "name=${screenshot.name} " +
                            "device_screencap_bright_pixels=${screenshot.deviceScreencapBrightPixels ?: -1} " +
                            "device_screencap_sha256=${screenshot.deviceScreencapSha256 ?: "unavailable"} " +
                            "device_screencap_blank=${screenshot.deviceScreencapAppearsBlank ?: "unavailable"} " +
                            "device_screencap_contradicts_authoritative=${screenshot.deviceScreencapContradictsAuthoritative ?: "unavailable"}",
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
    val viewportFile: File,
    val deviceFile: File,
    val deviceScreencapFile: File?,
    val fileName: String,
    val deviceFileName: String,
    val deviceScreencapFileName: String?,
    val bounds: Rect,
    val grid: TerminalGridSize,
    val brightPixels: Int,
    val deviceBrightPixels: Int,
    val deviceScreencapBrightPixels: Int?,
    val sha256: String,
    val deviceSha256: String,
    val deviceScreencapSha256: String?,
    val visibleTerminalText: String,
    val deviceAppearsBlank: Boolean,
    val deviceContradictsAuthoritative: Boolean,
    val deviceScreencapAppearsBlank: Boolean?,
    val deviceScreencapContradictsAuthoritative: Boolean?,
)

private data class TerminalGridSize(
    val columns: Int,
    val rows: Int,
)

private class TerminalCaptureHelper(
    private val terminalBounds: () -> Rect,
    private val terminalGrid: () -> TerminalGridSize,
    private val visibleTerminalText: () -> String,
    private val captureTerminalViewport: (String) -> File,
) {
    fun capture(name: String): TerminalScreenshotArtifact {
        val bounds = terminalBounds()
        val grid = terminalGrid()
        val viewportScreenshot = captureTerminalViewport("$name-viewport")
        val deviceScreenshot = TerminalLabArtifacts.capture(name)
        val deviceScreencap = TerminalLabArtifacts.captureDeviceScreencap("$name-device-screencap")
        val visibleText = visibleTerminalText()
        TerminalLabArtifacts.writeText("$name-visible-terminal.txt", visibleText)

        val viewportInkPixels = TerminalLabArtifacts.countBrightPixels(viewportScreenshot)
        val deviceInkPixels = TerminalLabArtifacts.countBrightPixels(deviceScreenshot, bounds)
        val deviceScreencapInkPixels = deviceScreencap?.let {
            TerminalLabArtifacts.countBrightPixels(it, bounds)
        }
        val viewportHash = TerminalLabArtifacts.sha256(viewportScreenshot)
        val deviceHash = TerminalLabArtifacts.sha256(deviceScreenshot)
        val deviceScreencapHash = deviceScreencap?.let(TerminalLabArtifacts::sha256)
        val authoritativeHasTerminal = viewportInkPixels > 0 && visibleText.isNotBlank()
        val deviceBlank = deviceInkPixels == 0
        val deviceScreencapBlank = deviceScreencapInkPixels?.let { it == 0 }

        return TerminalScreenshotArtifact(
            name = name,
            viewportFile = viewportScreenshot,
            deviceFile = deviceScreenshot,
            deviceScreencapFile = deviceScreencap,
            fileName = viewportScreenshot.name,
            deviceFileName = deviceScreenshot.name,
            deviceScreencapFileName = deviceScreencap?.name,
            bounds = bounds,
            grid = grid,
            brightPixels = viewportInkPixels,
            deviceBrightPixels = deviceInkPixels,
            deviceScreencapBrightPixels = deviceScreencapInkPixels,
            sha256 = viewportHash,
            deviceSha256 = deviceHash,
            deviceScreencapSha256 = deviceScreencapHash,
            visibleTerminalText = visibleText,
            deviceAppearsBlank = deviceBlank,
            deviceContradictsAuthoritative = authoritativeHasTerminal && deviceBlank,
            deviceScreencapAppearsBlank = deviceScreencapBlank,
            deviceScreencapContradictsAuthoritative = deviceScreencapBlank?.let {
                authoritativeHasTerminal && it
            },
        )
    }
}

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

    fun captureDeviceScreencap(name: String): File? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        return try {
            val descriptor = instrumentation.uiAutomation.executeShellCommand("screencap -p")
            descriptor.useInputStream { input ->
                val file = artifactFile("$name.png")
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
                if (file.length() <= 0L) {
                    file.delete()
                    null
                } else {
                    println("TERMINAL_LAB_DEVICE_SCREENCAP ${file.absolutePath}")
                    file
                }
            }
        } catch (t: Throwable) {
            writeText("$name-error.txt", "screencap -p unavailable: ${t::class.java.name}: ${t.message}\n")
            null
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

    private inline fun <T> ParcelFileDescriptor.useInputStream(block: (InputStream) -> T): T {
        return use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use(block)
        }
    }
}
