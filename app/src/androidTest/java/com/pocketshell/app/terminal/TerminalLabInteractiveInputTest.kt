package com.pocketshell.app.terminal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Connected coverage for full-screen agent-style terminal input.
 *
 * The remote command is a real PTY program in the Docker SSH fixture: it
 * switches terminal modes, repaints with ANSI clear/home sequences, updates
 * visible draft state for every key, accepts Enter/backspace/navigation, and
 * returns to a prompt on Ctrl-C. The input is driven through the embedded
 * Termux TerminalView, matching the path users exercise from the app.
 */
@RunWith(AndroidJUnit4::class)
class TerminalLabInteractiveInputTest {

    private val timings = mutableListOf<String>()
    private val captures = mutableListOf<InteractiveCapture>()

    @Test
    fun fullScreenTuiAcceptsEditingNavigationEnterAndCtrlCThroughTerminalView() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val key = instrumentation.context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        waitForSshFixtureReady(SshKey.Pem(key))

        ActivityScenario.launch<TerminalLabActivity>(
            TerminalLabActivity.intent(
                context = appContext,
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                privateKeyPem = key,
            ),
        ).use { scenario ->
            waitForVisibleTerminalText(scenario, "initial shell output") { it.isNotBlank() }
            captureAndAssertViewport(scenario, "interactive-01-before-command")

            val input = withTerminalInputConnection(scenario, instrumentation)
            timeInputToVisibleChange(scenario, "start_tui", "TUI started", {
                instrumentation.runOnMainSync {
                    input.commitText("pocketshell-tui-smoke", 1)
                    input.commitText("\n", 1)
                }
            }) {
                it.contains("PocketShell interactive TUI smoke")
            }
            captureAndAssertViewport(scenario, "interactive-02-tui-started")

            timeInputToVisibleChange(scenario, "type_abc", "text input changed draft", {
                instrumentation.runOnMainSync {
                    input.commitText("abc", 1)
                }
            }) {
                it.hasVisibleDraft("abc")
            }
            captureAndAssertViewport(scenario, "interactive-03-draft-abc")

            timeInputToVisibleChange(scenario, "backspace", "backspace edited draft", {
                instrumentation.runOnMainSync {
                    input.deleteSurroundingText(1, 0)
                }
            }) {
                it.hasVisibleDraft("ab")
            }
            captureAndAssertViewport(scenario, "interactive-04-backspace-ab")

            timeInputToVisibleChange(scenario, "type_d", "text input and backspace edited draft", {
                instrumentation.runOnMainSync {
                    input.commitText("d", 1)
                }
            }) {
                it.hasVisibleDraft("abd")
            }
            captureAndAssertViewport(scenario, "interactive-05-draft-abd")

            timeInputToVisibleChange(scenario, "arrow_up", "arrow-up navigation changed mode", {
                dispatchKey(scenario, instrumentation, KeyEvent.KEYCODE_DPAD_UP)
            }) {
                it.contains("mode:up")
            }
            captureAndAssertViewport(scenario, "interactive-06-arrow-up")

            timeInputToVisibleChange(scenario, "enter_submit", "Enter submitted draft", {
                instrumentation.runOnMainSync {
                    input.commitText("\n", 1)
                }
            }) {
                it.contains("last:abd") && it.hasVisibleDraft("")
            }
            captureAndAssertViewport(scenario, "interactive-07-enter-submitted")

            timeInputToVisibleChange(scenario, "ctrl_c_exit", "Ctrl-C returned to prompt", {
                dispatchKey(
                    scenario = scenario,
                    instrumentation = instrumentation,
                    keyCode = KeyEvent.KEYCODE_C,
                    metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON,
                )
            }) {
                it.contains("TUI_EXIT_PROMPT$")
            }
            captureAndAssertViewport(scenario, "interactive-08-ctrl-c-prompt")
            writeSummary(scenario)
            InteractiveArtifacts.writeTimings(timings)
            Unit
        }
    }

    private fun withTerminalInputConnection(
        scenario: ActivityScenario<TerminalLabActivity>,
        instrumentation: android.app.Instrumentation,
    ): InputConnection {
        var connection: InputConnection? = null
        scenario.onActivity { activity ->
            val view = findTerminalView(activity.window.decorView)
            assertNotNull("expected TerminalView in TerminalLabActivity", view)
            val terminal = requireNotNull(view)
            terminal.requestFocus()
            assertTrue("terminal view should accept focus for IME input", terminal.isFocused)
            connection = terminal.onCreateInputConnection(EditorInfo())
        }
        instrumentation.waitForIdleSync()
        return requireNotNull(connection) { "TerminalView did not create an InputConnection" }
    }

    private fun dispatchKey(
        scenario: ActivityScenario<TerminalLabActivity>,
        instrumentation: android.app.Instrumentation,
        keyCode: Int,
        metaState: Int = 0,
    ) {
        scenario.onActivity { activity ->
            val view = requireNotNull(findTerminalView(activity.window.decorView))
            val down = KeyEvent(
                0L,
                0L,
                KeyEvent.ACTION_DOWN,
                keyCode,
                0,
                metaState,
            )
            val up = KeyEvent(
                0L,
                0L,
                KeyEvent.ACTION_UP,
                keyCode,
                0,
                metaState,
            )
            view.dispatchKeyEvent(down)
            view.dispatchKeyEvent(up)
        }
        instrumentation.waitForIdleSync()
    }

    private suspend fun timeInputToVisibleChange(
        scenario: ActivityScenario<TerminalLabActivity>,
        name: String,
        label: String,
        input: () -> Unit,
        predicate: (String) -> Boolean,
    ) {
        val start = SystemClock.elapsedRealtime()
        input()
        waitForVisibleTerminalText(scenario, label, predicate)
        recordTiming("input_to_visible_${name}_ms", SystemClock.elapsedRealtime() - start)
    }

    private suspend fun waitForVisibleTerminalText(
        scenario: ActivityScenario<TerminalLabActivity>,
        label: String,
        predicate: (String) -> Boolean,
    ) {
        // CI fix: the GitHub Actions emulator (Pixel 7, api-34, 2 cores,
        // swiftshader GPU) is materially slower than the local Linux
        // emulators we develop against. SSH input → remote PTY render
        // → TerminalEmulator state round-trips can occasionally take
        // 25–40 s under load (the backspace step in this test was
        // observed timing out at the 20 s mark in CI while passing
        // locally well under 5 s). 60 s gives CI room without slowing
        // local runs (the predicate polls and exits as soon as it
        // matches).
        val deadline = SystemClock.elapsedRealtime() + 60_000
        var lastVisible = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            lastVisible = visibleTerminalText(scenario)
            if (predicate(lastVisible)) return
            kotlinx.coroutines.delay(100)
        }
        InteractiveArtifacts.writeText(
            "failure-$label-visible-terminal.txt",
            lastVisible.printableForFailure(),
        )
        error(
            "Timed out waiting for visible terminal text: $label. Last visible terminal:\n" +
                lastVisible.takeLast(4_000).printableForFailure(),
        )
    }

    private fun captureAndAssertViewport(
        scenario: ActivityScenario<TerminalLabActivity>,
        name: String,
    ): InteractiveCapture {
        val bounds = terminalViewBounds(scenario)
        val grid = terminalGridSize(scenario)
        val visibleText = visibleTerminalText(scenario)
        val viewport = captureTerminalViewport(scenario, "$name-viewport")
        val textFile = InteractiveArtifacts.writeText(
            "$name-visible-terminal.txt",
            visibleText.printableForFailure(),
        )
        val brightPixels = InteractiveArtifacts.countBrightPixels(viewport)
        val capture = InteractiveCapture(
            name = name,
            viewportFile = viewport,
            visibleTextFile = textFile,
            bounds = bounds,
            grid = grid,
            brightPixels = brightPixels,
            sha256 = InteractiveArtifacts.sha256(viewport),
            visibleTerminalText = visibleText,
        )
        captures += capture
        recordTiming("viewport_ink_${name}_px", brightPixels.toLong())
        assertTrue(
            "expected authoritative terminal viewport render to contain visible output for $name; " +
                "viewportBrightPixels=$brightPixels viewport=${viewport.absolutePath}",
            brightPixels > 1_000,
        )
        assertTrue(
            "expected visible terminal text sidecar to be non-blank for $name; " +
                "text=${textFile.absolutePath}",
            visibleText.isNotBlank(),
        )
        return capture
    }

    private fun captureTerminalViewport(
        scenario: ActivityScenario<TerminalLabActivity>,
        name: String,
    ): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)

        lateinit var bitmap: Bitmap
        scenario.onActivity { activity ->
            val view = checkNotNull(findTerminalView(activity.window.decorView)) {
                "TerminalView was not found"
            }
            check(view.width > 0 && view.height > 0) {
                "TerminalView has invalid dimensions ${view.width}x${view.height}"
            }
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }

        return InteractiveArtifacts.writeBitmap(name, bitmap).also {
            bitmap.recycle()
        }
    }

    private fun visibleTerminalText(scenario: ActivityScenario<TerminalLabActivity>): String {
        var text = ""
        scenario.onActivity { activity ->
            text = findTerminalView(activity.window.decorView)
                ?.currentSession
                ?.emulator
                ?.screen
                ?.transcriptText
                .orEmpty()
        }
        return text
    }

    private fun terminalGridSize(scenario: ActivityScenario<TerminalLabActivity>): InteractiveTerminalGrid {
        var grid: InteractiveTerminalGrid? = null
        scenario.onActivity { activity ->
            findTerminalView(activity.window.decorView)
                ?.currentSession
                ?.emulator
                ?.let { emulator ->
                    grid = InteractiveTerminalGrid(columns = emulator.mColumns, rows = emulator.mRows)
                }
        }
        return checkNotNull(grid) { "Terminal emulator grid was not available" }
    }

    private fun terminalViewBounds(scenario: ActivityScenario<TerminalLabActivity>): Rect {
        var bounds: Rect? = null
        scenario.onActivity { activity ->
            val view = findTerminalView(activity.window.decorView)
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

    private fun writeSummary(scenario: ActivityScenario<TerminalLabActivity>) {
        val bounds = terminalViewBounds(scenario)
        val grid = terminalGridSize(scenario)
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .resources
            .displayMetrics
            .density
        InteractiveArtifacts.writeText(
            "interactive-input-summary.txt",
            buildString {
                appendLine("terminal_grid_columns=${grid.columns}")
                appendLine("terminal_grid_rows=${grid.rows}")
                appendLine("terminal_bounds=$bounds")
                appendLine("display_density=${String.format(Locale.US, "%.2f", density)}")
                appendLine()
                appendLine("capture_policy:")
                appendLine("authoritative=direct TerminalView viewport render plus terminal emulator visible text")
                appendLine("timing=input action start through visible terminal text predicate satisfaction")
                appendLine()
                appendLine("authoritative_captures:")
                captures.forEach { capture ->
                    appendLine(
                        "${capture.viewportFile.name} " +
                            "name=${capture.name} " +
                            "grid=${capture.grid.columns}x${capture.grid.rows} " +
                            "bounds=${capture.bounds} " +
                            "viewport_bright_pixels=${capture.brightPixels} " +
                            "viewport_sha256=${capture.sha256} " +
                            "visible_terminal_chars=${capture.visibleTerminalText.length} " +
                            "visible_terminal_sidecar=${capture.visibleTextFile.name}",
                    )
                }
                appendLine()
                appendLine("timings:")
                timings.forEach(::appendLine)
            },
        )
    }

    private fun recordTiming(name: String, value: Long) {
        val line = "TERMINAL_LAB_TIMING $name=$value"
        timings += line
        println(line)
    }

    private fun findTerminalView(root: View): TerminalView? {
        if (root is TerminalView) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            val match = findTerminalView(root.getChildAt(index))
            if (match != null) return match
        }
        return null
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

    private fun String.hasVisibleDraft(expected: String): Boolean {
        val match = Regex("""draft:([^\s]*)\s+last:""").find(this) ?: return false
        return match.groupValues[1] == expected
    }
}

private data class InteractiveTerminalGrid(
    val columns: Int,
    val rows: Int,
)

private data class InteractiveCapture(
    val name: String,
    val viewportFile: File,
    val visibleTextFile: File,
    val bounds: Rect,
    val grid: InteractiveTerminalGrid,
    val brightPixels: Int,
    val sha256: String,
    val visibleTerminalText: String,
)

private object InteractiveArtifacts {
    private const val DEVICE_DIR_NAME: String = "terminal-lab"

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

    fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("TERMINAL_LAB_TEXT ${file.absolutePath}")
        return file
    }

    fun writeTimings(lines: List<String>): File {
        val file = artifactFile("interactive-input-timings.txt")
        file.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
        println("TERMINAL_LAB_TIMINGS ${file.absolutePath}")
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
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
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
