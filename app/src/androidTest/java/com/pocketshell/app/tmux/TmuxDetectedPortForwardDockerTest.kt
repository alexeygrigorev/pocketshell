package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #448 (epic #432 slice C): end-to-end emulator + Docker proof of the
 * detect -> overlay -> forward -> return journey.
 *
 * Scenario:
 *   1. Persist a host pointing at the `pocketshell-test:agents` container
 *      and pre-seed a detached tmux session.
 *   2. Open the session (host -> folder list -> session) so the live
 *      [TmuxSessionScreen] mounts and its per-pane output flow is being
 *      collected (foreground).
 *   3. Inside the pane, print a "Listening on http://0.0.0.0:PORT" line AND
 *      actually bind that port with `nc -l` so the on-demand confirm scan
 *      ([com.pocketshell.core.portfwd.PortScanner]) sees it in LISTEN.
 *   4. Assert the non-blocking forward overlay appears (the confirm passed).
 *   5. Tap Forward -> the port-forward panel opens (the host name renders in
 *      its header). Back returns to the exact tmux session screen.
 *
 * The agents container has no python3 but does ship busybox `nc` (binds the
 * port) and `netstat -tlnp` (PortScanner's fallback confirm path), so the
 * confirm is a real listening-socket check, not just an echoed string.
 */
@RunWith(AndroidJUnit4::class)
class TmuxDetectedPortForwardDockerTest {

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val DEVICE_DIR_NAME: String = "tmux-detected-port"
        const val HOST_NAME: String = "Issue448 PortDetect"
        const val SESSION_NAME: String = "issue448-portdetect"
        const val DETECT_PORT: Int = 8765
        const val ATTACH_TIMEOUT_MS: Long = 30_000
        const val VISIBLE_TIMEOUT_MS: Long = 25_000
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus
    // from the Compose hierarchy ("No compose hierarchies found").
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val stamps = mutableListOf<String>()

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
    }

    @Test
    fun detectedPortOverlayForwardsAndReturnsToSession() { runBlocking {
        val sshPort = resolveSshPort()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val key = instrumentation.context.assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        val sshKey = SshKey.Pem(key)
        waitForSshFixtureReady(sshKey, port = sshPort)
        killTmuxSession(sshKey, sshPort)
        seedTmuxSession(sshKey, sshPort)

        val hostRowTag = persistHost(appContext, key, sshPort)
        // Pre-grant POST_NOTIFICATIONS so MainActivity's Android-13+ runtime
        // request does not pop a system dialog over the activity window,
        // which would steal focus from the compose hierarchy at launch.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                instrumentation.uiAutomation.grantRuntimePermission(
                    appContext.packageName,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                )
            }
        }
        try {
            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            stamp("host_row_visible")
            compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()

            compose.waitUntil(timeoutMillis = ATTACH_TIMEOUT_MS) {
                compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
            compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertIsDisplayed()
            waitForTerminalSessionAttached()
            stamp("tmux_session_attached")
            captureArtifact("issue448-00-attached")

            // Start a real listening server inside the pane: print the
            // "Listening on" line the detector's regex catches AND bind the
            // port so the on-demand confirm scan sees it in LISTEN.
            val controller = focusedPaneControllerOrFail()
            controller.viewModel.writeInputToPane(
                controller.paneId,
                (
                    "printf 'Listening on http://0.0.0.0:$DETECT_PORT/\\n'; " +
                        "nc -l -p $DETECT_PORT -e cat\r"
                    ).toByteArray(Charsets.UTF_8),
            )
            waitForVisibleTerminalText("listening-line", VISIBLE_TIMEOUT_MS) {
                "Listening on http://0.0.0.0:$DETECT_PORT" in it
            }
            stamp("listening_line_visible")

            // The overlay only appears AFTER the `ss`/`netstat` confirm
            // passes — proving the detection isn't just a string echo.
            compose.waitUntil(timeoutMillis = VISIBLE_TIMEOUT_MS) {
                compose.onAllNodesWithTag(TMUX_DETECTED_PORT_OVERLAY_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            stamp("forward_overlay_visible")
            assertEquals(DETECT_PORT, focusedDetectedPortOrNull())
            compose.onNodeWithText("New port $DETECT_PORT detected — forward it?", useUnmergedTree = true)
                .assertIsDisplayed()
            captureArtifact("issue448-01-overlay")
            captureFullFrame("issue448-01-overlay-full")

            // Tap Forward -> the port-forward panel opens for this host.
            compose.onNodeWithTag(TMUX_DETECTED_PORT_FORWARD_TAG, useUnmergedTree = true).performClick()
            compose.waitUntil(timeoutMillis = VISIBLE_TIMEOUT_MS) {
                compose.onAllNodesWithText(HOST_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                        .fetchSemanticsNodes().isEmpty()
            }
            stamp("port_forward_panel_open")
            captureFullFrame("issue448-02-panel-full")

            // Back returns to the exact tmux session screen.
            launchedActivity?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(timeoutMillis = VISIBLE_TIMEOUT_MS) {
                compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertIsDisplayed()
            stamp("returned_to_session")
            captureArtifact("issue448-03-returned")
            captureFullFrame("issue448-03-returned-full")

            writeSummary()
        } finally {
            runCatching { withTimeout(20_000) { killTmuxSession(sshKey, sshPort) } }
        }
        Unit
    } }

    // ====================================================== fixture helpers

    private fun resolveSshPort(): Int =
        InstrumentationRegistry.getArguments()
            .getString("terminalWorkbenchSshPort")
            ?.toIntOrNull()
            ?: DEFAULT_PORT

    private suspend fun killTmuxSession(sshKey: SshKey.Pem, sshPort: Int) {
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = sshPort,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec("pkill -f 'nc -l -p $DETECT_PORT' 2>/dev/null || true")
                it.exec("tmux kill-session -t '$SESSION_NAME' 2>/dev/null || true")
            }
        }
    }

    private suspend fun seedTmuxSession(sshKey: SshKey.Pem, sshPort: Int) {
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = sshPort,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec("tmux new-session -d -s '$SESSION_NAME' -c /tmp") }
        }
    }

    private suspend fun persistHost(
        appContext: android.content.Context,
        key: String,
        port: Int,
    ): String {
        var hostRowTag = ""
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue448-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = HOST_NAME,
                    hostname = DEFAULT_HOST,
                    port = port,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            hostRowTag = HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
        return hostRowTag
    }

    // ====================================================== view-model access

    private fun focusedPaneControllerOrFail(): FocusedPaneController {
        var viewModel: TmuxSessionViewModel? = null
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline && viewModel == null) {
            launchedActivity?.onActivity { activity ->
                val owner = activity as ViewModelStoreOwner
                viewModel = readViewModelOrNull(owner.viewModelStore)
            }
            if (viewModel == null) SystemClock.sleep(100)
        }
        val vm = checkNotNull(viewModel) {
            "TmuxSessionViewModel was not bound to the activity within 10s"
        }
        val paneDeadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < paneDeadline) {
            if (vm.panes.value.isNotEmpty()) break
            SystemClock.sleep(100)
        }
        val panes = vm.panes.value
        assertTrue("expected at least one pane after attach, got $panes", panes.isNotEmpty())
        return FocusedPaneController(viewModel = vm, paneId = panes.first().paneId)
    }

    private fun focusedDetectedPortOrNull(): Int? {
        var port: Int? = null
        launchedActivity?.onActivity { activity ->
            val owner = activity as ViewModelStoreOwner
            port = readViewModelOrNull(owner.viewModelStore)?.detectedPort?.value
        }
        return port
    }

    private fun readViewModelOrNull(store: ViewModelStore): TmuxSessionViewModel? {
        val field = ViewModelStore::class.java.getDeclaredField("map").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val map = field.get(store) as MutableMap<String, androidx.lifecycle.ViewModel>
        return map.values.firstOrNull { it is TmuxSessionViewModel } as? TmuxSessionViewModel
    }

    // ====================================================== terminal helpers

    private fun waitForTerminalSessionAttached() {
        compose.waitUntil(timeoutMillis = 20_000) {
            findTerminalView()?.currentSession?.emulator != null
        }
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

    private fun findTerminalView(): TerminalView? {
        var found: TerminalView? = null
        launchedActivity?.onActivity { activity ->
            found = activity.window.decorView.findTerminalView()
        }
        return found
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

    private fun waitForVisibleTerminalText(
        label: String,
        timeoutMs: Long,
        predicate: (String) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            last = visibleTerminalText()
            if (predicate(last)) return
            SystemClock.sleep(50)
        }
        writeText("failure-$label-visible-terminal.txt", last)
        assertNotNull("predicate $label timed out; visible terminal:\n$last", null)
    }

    // ====================================================== artifacts

    private fun captureArtifact(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        var bitmap: Bitmap? = null
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            bitmap = b
        }
        val b = bitmap ?: Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.BLACK) }
        writeBitmap("$name-viewport", b)
        b.recycle()
        writeText("$name-visible-terminal.txt", visibleTerminalText())
    }

    private fun captureFullFrame(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write full-frame screenshot to ${file.absolutePath}"
            }
        }
        bitmap.recycle()
        println("ISSUE448_FULLFRAME ${file.absolutePath}")
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE448_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE448_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeSummary() {
        val visible = visibleTerminalText()
        val body = buildString {
            appendLine("scenario=issue448-detect-overlay-forward-return")
            appendLine("issue=448 (epic #432 slice C)")
            appendLine("session_name=$SESSION_NAME")
            appendLine("detected_port=$DETECT_PORT")
            appendLine("confirm_path=netstat -tlnp (ss not installed on agents container)")
            appendLine()
            appendLine("acceptance:")
            appendLine("listening_line_in_visible_terminal=${"Listening on http://0.0.0.0:$DETECT_PORT" in visible}")
            appendLine("overlay_surfaced_after_confirm=true")
            appendLine("forward_opened_panel=true")
            appendLine("back_returned_to_session=true")
            appendLine()
            appendLine("per_stage_stamps:")
            stamps.forEach { appendLine(it) }
            appendLine()
            appendLine("visible_terminal:")
            appendLine(visible)
        }
        writeText("issue448-detect-overlay-summary.txt", body)
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create artifact directory ${dir.absolutePath}"
        }
        return File(dir, name)
    }

    private fun stamp(name: String) {
        val line = "[issue448] $name at ${SystemClock.elapsedRealtime()}"
        stamps += line
        println(line)
    }

    private data class FocusedPaneController(
        val viewModel: TmuxSessionViewModel,
        val paneId: String,
    )
}
