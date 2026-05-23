package com.pocketshell.app.terminal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlin.math.max
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
class TerminalLabDockerTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<TerminalLabActivity>? = null
    private val timings = mutableListOf<String>()

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
    }

    @Test
    fun terminalLabConnectsAndRunsStressCommandsThroughInputPath() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val key = instrumentation.context.assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        val sshKey = SshKey.Pem(key)
        waitForSshFixtureReady(sshKey)

        val marker = "pslab${System.currentTimeMillis()}"
        val intent = TerminalLabActivity.intent(
            context = appContext,
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            privateKeyPem = key,
        )

        launchedActivity = ActivityScenario.launch(intent)
        compose.onNodeWithTag(TERMINAL_LAB_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForPrompt()
        waitForVisibleTerminalText("prompt") { it.isNotBlank() }
        recordTiming("connect_to_prompt_ms", requireController().uiState.value.connectToPromptMs)
        captureAndAssertTerminalInk("01-connected-prompt", minInkPixels = 1_500)

        sendViaTerminalInput("printf 'PWD-$marker\\n'; pwd", "PWD-$marker", "pwd")
        sendViaTerminalInput("printf 'LS-$marker\\n'; ls -la /home/testuser /usr/local/bin", "LS-$marker", "ls")
        captureAndAssertTerminalInk("02-pwd-ls", minInkPixels = 4_000)

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
        captureAndAssertTerminalInk("03-long-path-git-status", minInkPixels = 6_000)

        sendBackspaceEditViaTerminalInput(marker)
        sendViaTerminalInput(
            "for i in 1 2 3; do printf 'REPEAT-$marker-%s\\n' \"\$i\"; done",
            "REPEAT-$marker-3",
            "repeated-commands",
        )
        captureAndAssertTerminalInk("04-backspace-repeat", minInkPixels = 6_000)
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

    private fun captureAndAssertTerminalInk(name: String, minInkPixels: Int) {
        val bounds = terminalViewBounds()
        var screenshot: File? = null
        var inkPixels = 0
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            screenshot = TerminalLabArtifacts.capture(name)
            inkPixels = TerminalLabArtifacts.countBrightPixels(screenshot, bounds)
            if (inkPixels >= minInkPixels) break
            SystemClock.sleep(250)
        } while (SystemClock.elapsedRealtime() < deadline)

        TerminalLabArtifacts.writeText("$name-visible-terminal.txt", visibleTerminalText())
        recordTiming("visible_ink_${name}_px", inkPixels.toLong())
        val finalScreenshot = checkNotNull(screenshot) { "terminal screenshot was not captured" }
        assertTrue(
            "expected terminal viewport in $name screenshot to contain shell output ink; " +
                "brightPixels=$inkPixels min=$minInkPixels bounds=$bounds screenshot=${finalScreenshot.absolutePath}",
            inkPixels >= minInkPixels,
        )
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
}

object TerminalLabArtifacts {
    private const val DEVICE_DIR_NAME: String = "terminal-lab"

    fun capture(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Could not write screenshot: ${file.absolutePath}"
            }
        }
        bitmap.recycle()
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
