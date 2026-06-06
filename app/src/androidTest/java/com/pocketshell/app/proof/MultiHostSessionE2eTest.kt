package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
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
import com.pocketshell.app.projects.FOLDER_LIST_BACK_TAG
import com.pocketshell.app.projects.FOLDER_LIST_SCREEN_TAG
import com.pocketshell.app.proof.signals.waitForSshPtyReady
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #147 — connected regression test for switching between tmux
 * sessions on two different hosts.
 *
 * The test uses the existing Docker fixtures:
 *  - Host A: `agents` exposed on `10.0.2.2:2222`.
 *  - Host B: `tmux` exposed on `10.0.2.2:2224`.
 *
 * It follows the current navigation path (host list -> folder list ->
 * tmux session), writes a host-specific marker in each remote session,
 * returns to Host A, and verifies Host A's transcript is still present
 * after the cross-host round trip. Terminal assertions use
 * [waitForSshPtyReady] and [TerminalTextMatcher.containsWrapTolerant],
 * matching the acceptance criteria.
 */
@RunWith(AndroidJUnit4::class)
class MultiHostSessionE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus
    // from the Compose hierarchy ("No compose hierarchies found").
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val timings = mutableListOf<String>()
    private var sessionA: String = ""
    private var sessionB: String = ""

    @After
    fun tearDown() {
        launchedActivity?.close()
        launchedActivity = null
        runBlocking {
            runCatching {
                val key = readFixtureKey()
                cleanupSession(key, AGENTS_PORT, sessionA)
                cleanupSession(key, TMUX_PORT, sessionB)
            }
        }
    }

    @Test
    fun switchingBetweenHostsPreservesRemoteSessionTranscript() = runBlocking {
        val key = readFixtureKey()
        val pem = SshKey.Pem(key)
        waitForSshFixtureReady(pem, port = AGENTS_PORT)
        waitForSshFixtureReady(pem, port = TMUX_PORT)

        val suffix = System.currentTimeMillis().toString().takeLast(6)
        sessionA = "issue147-a-$suffix"
        sessionB = "issue147-b-$suffix"
        val expectedHostA = seedSession(key, AGENTS_PORT, sessionA, "HOST-A-READY")
        val expectedHostB = seedSession(key, TMUX_PORT, sessionB, "HOST-B-READY")
        val baselineA = sshdProcessCount(key, AGENTS_PORT)
        val baselineB = sshdProcessCount(key, TMUX_PORT)
        Log.i(LOG_TAG, "sshd baseline: hostA=$baselineA hostB=$baselineB")

        val hosts = seedDockerHosts(key)
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        attachFromHostList(hosts.hostARowTag, hosts.hostAName, sessionA)
        waitForPtyReady("host A initial attach")
        val hostAMarker = "host-a:$expectedHostA"
        sendCommandThroughTerminalInput(
            command = "printf 'host-a:%s\\n' \"\$(hostname)\"",
            label = "host A hostname marker",
        )
        waitForVisibleTerminal("host A marker") { transcript ->
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                hostAMarker,
                terminalCols = terminalGridSize().columns,
            )
        }
        captureViewport("01-host-a-marker-visible")

        returnToHostList()
        assertHostListShowsBothHosts(hosts)

        attachFromHostList(hosts.hostBRowTag, hosts.hostBName, sessionB)
        waitForPtyReady("host B attach")
        val hostBMarker = "host-b:$expectedHostB"
        sendCommandThroughTerminalInput(
            command = "printf 'host-b:%s\\n' \"\$(hostname)\"",
            label = "host B hostname marker",
        )
        waitForVisibleTerminal("host B marker") { transcript ->
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                hostBMarker,
                terminalCols = terminalGridSize().columns,
            )
        }
        captureViewport("02-host-b-marker-visible")

        returnToHostList()
        assertHostListShowsBothHosts(hosts)
        assertRemoteSessionExists(key, AGENTS_PORT, sessionA)
        assertRemoteSessionExists(key, TMUX_PORT, sessionB)

        attachFromHostList(hosts.hostARowTag, hosts.hostAName, sessionA)
        waitForPtyReady("host A reattach")
        waitForVisibleTerminal("host A marker after host switch") { transcript ->
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                hostAMarker,
                terminalCols = terminalGridSize().columns,
            )
        }
        captureViewport("03-host-a-marker-preserved")

        bestEffortAssertNoRunawaySshdChildren(key, AGENTS_PORT, baselineA, "host A")
        bestEffortAssertNoRunawaySshdChildren(key, TMUX_PORT, baselineB, "host B")

        writeTimings()
        Unit
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedDockerHosts(key: String): SeededHosts {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue147-key-${System.currentTimeMillis()}",
                content = key,
            )
            fun host(name: String, port: Int): HostEntity =
                HostEntity(
                    name = name,
                    hostname = DEFAULT_HOST,
                    port = port,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    pocketshellInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                    pocketshellLastDetectedAt = System.currentTimeMillis(),
                )

            val hostAName = "Issue147 Host A"
            val hostBName = "Issue147 Host B"
            val hostAId = db.hostDao().insert(host(hostAName, AGENTS_PORT))
            val hostBId = db.hostDao().insert(host(hostBName, TMUX_PORT))
            SeededHosts(
                hostAName = hostAName,
                hostBName = hostBName,
                hostARowTag = HOST_ROW_TAG_PREFIX + hostAId,
                hostBRowTag = HOST_ROW_TAG_PREFIX + hostBId,
            )
        } finally {
            db.close()
        }
    }

    private suspend fun seedSession(
        key: String,
        port: Int,
        sessionName: String,
        readyMarker: String,
    ): String {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(sessionName)} " +
                    shellQuote("printf '$readyMarker\\n'; exec sh"),
            )
            appendLine("hostname")
        }
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = port,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec(script) }
        }
        val exec = result.getOrNull()
        assertTrue(
            "expected tmux session seeding to succeed on port $port, got " +
                "exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        return exec?.stdout?.lineSequence()?.lastOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    private suspend fun cleanupSession(key: String, port: Int, sessionName: String) {
        if (sessionName.isBlank()) return
        runCatching {
            withTimeout(20_000) {
                SshConnection.connect(
                    host = DEFAULT_HOST,
                    port = port,
                    user = DEFAULT_USER,
                    key = SshKey.Pem(key),
                    knownHosts = KnownHostsPolicy.AcceptAll,
                    timeoutMs = 15_000,
                ).mapCatching { session ->
                    session.use {
                        it.exec("tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true")
                    }
                }
            }
        }
    }

    private fun attachFromHostList(hostRowTag: String, hostName: String, sessionName: String) {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(FOLDER_LIST_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForText(hostName, timeoutMs = 10_000)
        waitForText(sessionName, timeoutMs = folderListWaitMs())
        compose.onNodeWithText(sessionName, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
    }

    private fun returnToHostList() {
        launchedActivity?.onActivity { activity ->
            (activity as ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithTag(FOLDER_LIST_SCREEN_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(FOLDER_LIST_BACK_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Hosts", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun assertHostListShowsBothHosts(hosts: SeededHosts) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hosts.hostARowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty() &&
                compose.onAllNodesWithTag(hosts.hostBRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
        compose.onNodeWithText(hosts.hostAName, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(hosts.hostBName, useUnmergedTree = true).assertExists()
        captureFullScreen("host-list-both-hosts")
    }

    private suspend fun assertRemoteSessionExists(key: String, port: Int, sessionName: String) {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = port,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec("tmux has-session -t ${shellQuote(sessionName)}")
            }
        }
        val exec = result.getOrNull()
        assertEquals(
            "expected remote tmux session `$sessionName` to survive host switching on port $port; " +
                "exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            0,
            exec?.exitCode,
        )
    }

    private fun waitForPtyReady(label: String) {
        val ready = waitForSshPtyReady(transcriptProvider = { visibleTerminalText() })
        assertTrue(
            "expected SSH PTY prompt for $label; visible_terminal_text=`${visibleTerminalText().take(500)}`",
            ready,
        )
    }

    private fun waitForText(text: String, timeoutMs: Long) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithText(text, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            launchedActivity?.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession != null && view.mEmulator != null
            }
            attached
        }
    }

    private fun waitForVisibleTerminal(
        label: String,
        timeoutMillis: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
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
        assertTrue(
            "expected visible terminal text for $label within ${timeoutMillis}ms; got:\n$last",
            satisfied && predicate(last),
        )
    }

    private fun sendCommandThroughTerminalInput(command: String, label: String) {
        command.chunked(4).forEach { chunk ->
            val committed = terminalInputConnection().commitText(chunk, 1)
            assertTrue("expected terminal input connection to commit `$chunk` for $label", committed)
            SystemClock.sleep(35)
        }
        waitForVisibleTerminal("$label command echo", timeoutMillis = 10_000) { transcript ->
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                command,
                terminalCols = terminalGridSize().columns,
            )
        }
        val enterCommitted = terminalInputConnection().commitText("\n", 1)
        assertTrue("expected terminal input connection to submit $label", enterCommitted)
    }

    private fun terminalInputConnection(): InputConnection {
        var connection: InputConnection? = null
        launchedActivity?.onActivity { activity ->
            val view = requireNotNull(activity.window.decorView.findTerminalView()) {
                "TerminalView was not found"
            }
            view.requestFocus()
            connection = view.onCreateInputConnection(EditorInfo())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return requireNotNull(connection) { "TerminalView did not create an InputConnection" }
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

    private fun terminalGridSize(): GridSize {
        var grid: GridSize? = null
        launchedActivity?.onActivity { activity ->
            activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.let { emulator ->
                    grid = GridSize(columns = emulator.mColumns, rows = emulator.mRows)
                }
        }
        return grid ?: GridSize(columns = 80, rows = 24)
    }

    private suspend fun sshdProcessCount(key: String, port: Int): Int? =
        runCatching {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = port,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 10_000,
            ).getOrThrow().use { session ->
                session.exec("ps -eo args | grep '[s]shd: testuser' | wc -l")
                    .stdout
                    .trim()
                    .toIntOrNull()
            }
        }.getOrNull()

    private suspend fun bestEffortAssertNoRunawaySshdChildren(
        key: String,
        port: Int,
        baseline: Int?,
        label: String,
    ) {
        val after = sshdProcessCount(key, port)
        writeText("$label-sshd-count.txt", "baseline=$baseline\nafter=$after\n")
        if (baseline == null || after == null) {
            Log.w(LOG_TAG, "skipping sshd leak assertion for $label: baseline=$baseline after=$after")
            return
        }
        assertTrue(
            "expected no runaway sshd children for $label; baseline=$baseline after=$after",
            after <= baseline + SSHD_PROCESS_LEAK_TOLERANCE,
        )
    }

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)

        var bitmap: Bitmap? = null
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            bitmap = b
        }
        bitmap?.let { writeBitmap("$name-viewport", it) }
        writeText("$name-visible-terminal.txt", visibleTerminalText())
        bitmap?.recycle()
    }

    private fun captureFullScreen(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        writeBitmap(name, bitmap)
        bitmap.recycle()
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE147_SCREENSHOT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE147_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE147_TIMINGS ${file.absolutePath}")
        return file
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

    @Suppress("SameReturnValue")
    private fun folderListWaitMs(): Long =
        if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L

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

    private data class SeededHosts(
        val hostAName: String,
        val hostBName: String,
        val hostARowTag: String,
        val hostBRowTag: String,
    )

    private data class GridSize(val columns: Int, val rows: Int)

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue147MultiHost"
        const val DEVICE_DIR_NAME: String = "issue147-multi-host"
        const val AGENTS_PORT: Int = 2222
        const val TMUX_PORT: Int = 2224
        const val SSHD_PROCESS_LEAK_TOLERANCE: Int = 6
    }
}
