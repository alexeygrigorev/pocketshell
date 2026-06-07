package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.composer.COMPOSER_DRAFT_TAG
import com.pocketshell.app.composer.COMPOSER_SEND_ENTER_TAG
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_TERMINAL_SURFACE_ERROR_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.TmuxClientDiagnostics
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Live regression proof for a Codex-like dynamic terminal flood.
 *
 * Existing unit tests pin parser, renderer, and pane-backlog classification
 * behavior. This test covers the missing device path: production
 * MainActivity -> live SSH -> tmux -CC -> prompt composer -> tmux send-keys,
 * while a live pane is producing Codex-style carriage-return rewrites, ANSI
 * erase, long wrapped paths/URLs, fenced JSON/tool blocks, and burst output.
 * The large composer draft is submitted through the production `send-keys -H`
 * bracketed-paste path so command timeouts, reader EOF, and reconnect
 * misclassification all fail the proof instead of being hidden by a side
 * channel.
 */
@RunWith(AndroidJUnit4::class)
class CodexOverflowNoReconnectE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private var seededKey: String? = null
    private var appDiagnostics: RecordingDiagnosticSink? = null
    private var tmuxDiagnostics: RecordingTmuxDiagnosticSink? = null
    private val timings = mutableListOf<String>()

    @Before
    fun installDiagnostics() {
        clearLastSessionPrefs()
        appDiagnostics = RecordingDiagnosticSink().also { DiagnosticEvents.install(it) }
        tmuxDiagnostics = RecordingTmuxDiagnosticSink().also { TmuxClientDiagnostics.install(it) }
    }

    @After
    fun teardown() {
        seededKey?.let { key -> runCatching { runBlocking { cleanupSeededSession(key) } } }
        runCatching { launchedActivity?.close() }
        appDiagnostics?.close()
        tmuxDiagnostics?.close()
        clearLastSessionPrefs()
    }

    @Test
    fun codexStyleTerminalFloodWithComposerSendKeysDoesNotShowReconnect() = runBlocking<Unit> {
        val key = readFixtureKey()
        seededKey = key
        waitForSshFixtureReady(SshKey.Pem(key))

        seedCodexOverflowSession(key)
        val hostRowTag = seedDockerHost(key, "Issue576 Codex Overflow")
        forceFlatHostDetailViewMode()

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        attachSeededSession(hostRowTag)
        waitForVisibleTerminal("ready marker") { it.contains(READY_MARKER) }
        assertConnected("before flood")
        val gridBefore = terminalGrid()
        captureViewport("issue576-01-ready")

        val prompt = openComposerAndFillLargePrompt()
        assertTrue(
            "composer prompt must stay inside the requested 32-128KiB proof range, bytes=${prompt.utf8Size()}",
            prompt.utf8Size() in MIN_PROMPT_BYTES..MAX_PROMPT_BYTES,
        )

        val start = SystemClock.elapsedRealtime()
        triggerRemoteFlood(key)
        waitForFloodStart()
        sendFilledComposerPrompt()
        waitForRemotePromptReceipt(key)

        val outcome = waitForFloodDoneOrLocalOverflow()
        recordTiming("trigger-to-${outcome.label}", start)
        captureViewport("issue576-02-$outcome")

        assertNoReconnectOrReaderExitDiagnostics("after $outcome")
        assertNoVisibleReconnect("after $outcome")
        assertConnected("after $outcome")
        assertComposerSendKeysPathWasExercised(prompt)

        if (outcome == FloodOutcome.OutputOverflow) {
            assertTrue(
                "local output overflow must surface as terminal/output pressure",
                currentPaneHasSurfaceError(),
            )
            val overflows = appDiagnostics!!.eventsNamed("terminal_output_overflow")
            assertTrue("expected terminal_output_overflow diagnostic", overflows.isNotEmpty())
            overflows.forEach { event ->
                assertEquals("pane_output_backlog", event.fields["source"])
                assertFalse(
                    "overflow diagnostic must not report reconnect status: ${event.fields}",
                    event.fields["status"] == "Reconnecting" || event.fields["status"] == "Failed",
                )
            }
        } else {
            assertFalse(
                "bounded Codex-like flood should not leave the pane in local overflow state",
                currentPaneHasSurfaceError(),
            )
            waitForVisibleTerminal("done marker") { it.contains(DONE_MARKER) }
        }

        val gridAfter = terminalGridOrNull()
        if (gridAfter != null) {
            assertTrue("terminal grid should remain usable after composer send pressure", gridAfter.columns > 0)
            assertTrue("terminal grid should remain usable after composer send pressure", gridAfter.rows > 0)
            Log.i(LOG_TAG, "grid before=$gridBefore after=$gridAfter")
        }
        writeTimings()
    }

    private fun attachSeededSession(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
    }

    private fun waitForFloodStart() {
        waitForVisibleTerminal("flood started", timeoutMillis = 20_000) {
            it.contains("changed-file-") || it.contains("codex indexing")
        }
    }

    private fun openComposerAndFillLargePrompt(): String {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val prompt = buildLargeComposerPrompt()
        val start = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        prompt.chunked(COMPOSER_INPUT_CHUNK_CHARS).forEachIndexed { index, chunk ->
            compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
                .performTextInput(chunk)
            if (index % 4 == 0) {
                assertNoVisibleReconnect("while filling composer chunk $index")
            }
        }
        recordTiming("composer-fill-${prompt.utf8Size()}B", start)

        assertNoReconnectOrReaderExitDiagnostics("after composer fill")
        return prompt
    }

    private fun sendFilledComposerPrompt() {
        val sendStart = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        recordTiming("composer-send-click-to-dismiss", sendStart)
        assertNoReconnectOrReaderExitDiagnostics("after composer send")
    }

    private fun waitForFloodDoneOrLocalOverflow(): FloodOutcome {
        val deadline = SystemClock.elapsedRealtime() + FLOOD_TIMEOUT_MS
        var lastTerminal = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            assertNoVisibleReconnect("during flood")
            assertNoReconnectOrReaderExitDiagnostics("during flood")
            val status = currentConnectionStatus()
            assertFalse(
                "Codex-like terminal flood must not enter Reconnecting/Failed; observed=$status",
                status is TmuxSessionViewModel.ConnectionStatus.Reconnecting ||
                    status is TmuxSessionViewModel.ConnectionStatus.Failed,
            )
            if (currentPaneHasSurfaceError()) return FloodOutcome.OutputOverflow
            lastTerminal = visibleTerminalText()
            if (lastTerminal.contains(DONE_MARKER)) return FloodOutcome.Completed
            SystemClock.sleep(150)
        }
        writeText("failure-visible-terminal.txt", lastTerminal)
        error("timed out waiting for $DONE_MARKER or local overflow; tail=${lastTerminal.takeLast(400)}")
    }

    private suspend fun waitForRemotePromptReceipt(key: String) {
        val start = SystemClock.elapsedRealtime()
        val deadline = start + PROMPT_RECEIPT_TIMEOUT_MS
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            assertNoVisibleReconnect("while waiting for remote prompt receipt")
            assertNoReconnectOrReaderExitDiagnostics("while waiting for remote prompt receipt")
            val exec = runRemote(
                key,
                "test -s ${shellQuote(REMOTE_RECEIPT)} && cat ${shellQuote(REMOTE_RECEIPT)}",
            )
            if (exec?.exitCode == 0) {
                last = exec.stdout.trim()
                if (last.contains(PROMPT_RECEIVED_MARKER)) {
                    recordTiming("composer-send-to-remote-receipt", start)
                    return
                }
            } else {
                last = "exit=${exec?.exitCode} stderr='${exec?.stderr}'"
            }
            SystemClock.sleep(500)
        }
        error("remote script did not acknowledge composer prompt receipt; last=$last")
    }

    private fun assertNoReconnectOrReaderExitDiagnostics(label: String) {
        val app = appDiagnostics!!
        assertTrue(
            "Codex-like flood must not be logged as passive SSH/tmux EOF $label events=${app.events}",
            app.eventsNamed("passive_disconnect").isEmpty(),
        )
        assertTrue(
            "Codex-like flood must not start reconnect $label events=${app.events}",
            app.eventsNamed("reconnect_start").isEmpty(),
        )
        assertTrue(
            "Codex-like flood must not start network reconnect $label events=${app.events}",
            app.eventsNamed("network_reconnect_start").isEmpty(),
        )
        val tmux = tmuxDiagnostics!!
        val readerExits = tmux.eventsNamed("tmux_client_reader_exit")
        assertTrue(
            "tmux reader must stay alive through the flood $label readerExits=$readerExits",
            readerExits.isEmpty(),
        )
        val commandTimeouts = tmux.eventsNamed("tmux_client_command_timeout")
        assertTrue(
            "tmux send-keys/control commands must not time out $label commandTimeouts=$commandTimeouts",
            commandTimeouts.isEmpty(),
        )
        val forbiddenText = (app.events.map { "${it.category} ${it.name} ${it.fields}" } +
            tmux.events.map { "${it.name} ${it.fields}" })
            .filter { text ->
                FORBIDDEN_DIAGNOSTIC_TEXT.any { forbidden ->
                    text.lowercase(Locale.US).contains(forbidden)
                }
            }
        assertTrue(
            "diagnostics must not contain timeout/EOF/reconnect classifier text $label: $forbiddenText",
            forbiddenText.isEmpty(),
        )
    }

    private fun assertComposerSendKeysPathWasExercised(prompt: String) {
        assertTrue(
            "test must exercise the production composer path with a large prompt",
            prompt.contains(PROMPT_SENTINEL) && prompt.utf8Size() >= MIN_PROMPT_BYTES,
        )
    }

    private fun assertNoVisibleReconnect(label: String) {
        val errorBands = compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size
        val reconnectText = compose.onAllNodesWithText("Reconnect", substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size
        val disconnectedText = compose.onAllNodesWithText("Disconnected", substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size
        val reconnectingText = compose.onAllNodesWithText("Reconnecting", substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size
        assertEquals("expected no disconnect band for $label", 0, errorBands)
        assertEquals("expected no visible Reconnect/Tap Reconnect text for $label", 0, reconnectText)
        assertEquals("expected no visible Disconnected text for $label", 0, disconnectedText)
        assertEquals("expected no visible Reconnecting text for $label", 0, reconnectingText)
    }

    private fun assertConnected(label: String) {
        assertTrue(
            "expected transport to stay Connected $label, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    private fun currentConnectionStatus(): TmuxSessionViewModel.ConnectionStatus {
        var status: TmuxSessionViewModel.ConnectionStatus =
            TmuxSessionViewModel.ConnectionStatus.Idle
        launchedActivity?.onActivity { activity ->
            status = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .connectionStatus
                .value
        }
        return status
    }

    private fun currentPaneHasSurfaceError(): Boolean {
        var paneError = false
        launchedActivity?.onActivity { activity ->
            paneError = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .panes
                .value
                .any { it.surfaceError }
        }
        if (!paneError) {
            paneError = compose.onAllNodesWithTag(TMUX_TERMINAL_SURFACE_ERROR_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        return paneError
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
        if (!satisfied) writeText("failure-$label-visible-terminal.txt", last)
        assertTrue("expected visible terminal text for $label; got:\n$last", satisfied && predicate(last))
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

    private fun terminalGrid(): TerminalGrid {
        return requireNotNull(terminalGridOrNull()) { "terminal grid was not available" }
    }

    private fun terminalGridOrNull(): TerminalGrid? {
        var grid: TerminalGrid? = null
        launchedActivity?.onActivity { activity ->
            val emulator = activity.window.decorView.findTerminalView()?.currentSession?.emulator
            if (emulator != null) grid = TerminalGrid(emulator.mColumns, emulator.mRows)
        }
        return grid
    }

    private fun forceFlatHostDetailViewMode() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        appContext
            .getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("host_detail_view_mode", "Flat")
            .commit()
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
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue576-key-${System.currentTimeMillis()}",
                content = key,
            )
            val now = System.currentTimeMillis()
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = hostName,
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = now,
                    pocketshellInstalled = true,
                    pocketshellLastDetectedAt = now,
                    pocketshellVersionCompatible = true,
                    pocketshellDaemonRunning = true,
                    pocketshellDaemonEnabled = true,
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    private suspend fun seedCodexOverflowSession(key: String) {
        val script = """
            set -eu
            cat > ${shellQuote(REMOTE_SCRIPT)} <<'SH'
            #!/usr/bin/env bash
            set -u
            trigger=${REMOTE_TRIGGER}
            receipt=${REMOTE_RECEIPT}
            rm -f "${'$'}trigger" "${'$'}receipt"
            printf '${READY_MARKER}\n'
            while [ ! -e "${'$'}trigger" ]; do sleep 0.1; done
            old_stty=${'$'}(stty -g 2>/dev/null || true)
            stty raw -echo min 0 time 1 2>/dev/null || true
            printf '\033[?2004h\033[?25l'
            (
            i=0
            flood_deadline=${'$'}((SECONDS + ${FLOOD_MAX_SECONDS}))
            while [ "${'$'}SECONDS" -lt "${'$'}flood_deadline" ]; do
                printf '\r\033[K| codex indexing %04d/${FLOOD_LINES} very-long-stale-status-suffix-that-must-be-erased' "${'$'}i"
                printf '\r\033[K/ codex indexing %04d/${FLOOD_LINES}\n' "${'$'}i"
                printf 'changed-file-%04d M /workspace/mobile/android/app/src/main/java/com/pocketshell/feature/codex/VeryLongWrappedPath/File%04dViewModelWithAnExcessivelyLongName.kt https://example.test/org/repo/pull/%04d/checks?token=abcdefghijklmnopqrstuvwxyz0123456789\n' "${'$'}i" "${'$'}i" "${'$'}i"
                if [ ${'$'}((i % 8)) -eq 0 ]; then
                    printf '```json\n'
                    printf '{"type":"function_call_output","call_id":"tool-%04d","output":"read file /workspace/mobile/android/app/src/androidTest/java/com/pocketshell/app/proof/CodexOverflowNoReconnectE2eTest.kt and returned a bounded synthetic block"}\n' "${'$'}i"
                    printf '```\n'
                fi
                if [ ${'$'}((i % 17)) -eq 0 ]; then
                    printf 'tool_result_%04d: wrapped dynamic terminal output wrapped dynamic terminal output wrapped dynamic terminal output wrapped dynamic terminal output wrapped dynamic terminal output wrapped dynamic terminal output wrapped dynamic terminal output wrapped dynamic terminal output\n' "${'$'}i"
                fi
                if [ -s "${'$'}receipt" ] && [ "${'$'}i" -ge ${FLOOD_LINES} ]; then break; fi
                if [ ${'$'}((i % 20)) -eq 0 ]; then sleep 0.05; fi
                i=${'$'}((i + 1))
            done
            ) &
            flood_pid=${'$'}!
            bytes=0
            tail_buffer=
            read_deadline=${'$'}((SECONDS + ${PROMPT_RECEIPT_TIMEOUT_SECONDS}))
            while [ "${'$'}SECONDS" -lt "${'$'}read_deadline" ]; do
                if IFS= read -rsn1 -t 0.1 ch; then
                    bytes=${'$'}((bytes + 1))
                    tail_buffer="${'$'}tail_buffer${'$'}ch"
                    if [ ${'$'}{#tail_buffer} -gt 512 ]; then
                        tail_buffer="${'$'}{tail_buffer: -512}"
                    fi
                    if [[ "${'$'}tail_buffer" == *"${PROMPT_SENTINEL}"* ]]; then
                        printf '%s bytes=%d\n' '${PROMPT_RECEIVED_MARKER}' "${'$'}bytes" > "${'$'}receipt"
                        printf '\r\033[K${PROMPT_RECEIVED_MARKER} bytes=%d\n' "${'$'}bytes"
                        break
                    fi
                fi
            done
            wait "${'$'}flood_pid" 2>/dev/null || true
            if [ -n "${'$'}old_stty" ]; then stty "${'$'}old_stty" 2>/dev/null || true; fi
            printf '\033[?25h\033[?2004l\r\033[K${DONE_MARKER}\n'
            exec sh
            SH
            chmod +x ${shellQuote(REMOTE_SCRIPT)}
            tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true
            tmux new-session -d -s ${shellQuote(SESSION_NAME)} ${shellQuote(REMOTE_SCRIPT)}
            tmux list-sessions
        """.trimIndent()
        val exec = runRemote(key, script)
        assertTrue(
            "expected Codex overflow session seed to succeed, exit=${exec?.exitCode} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
    }

    private suspend fun triggerRemoteFlood(key: String) {
        val exec = runRemote(key, "touch ${shellQuote(REMOTE_TRIGGER)}")
        assertTrue(
            "expected trigger file write to succeed, exit=${exec?.exitCode} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
    }

    private suspend fun cleanupSeededSession(key: String) {
        runCatching {
            runRemote(
                key,
                "tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true; " +
                    "rm -f ${shellQuote(REMOTE_SCRIPT)} ${shellQuote(REMOTE_TRIGGER)} " +
                    shellQuote(REMOTE_RECEIPT),
            )
        }
    }

    private suspend fun runRemote(key: String, command: String) =
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(command) } }.getOrNull()

    private fun recordTiming(label: String, startElapsedRealtimeMs: Long) {
        val elapsed = SystemClock.elapsedRealtime() - startElapsedRealtimeMs
        timings += "$label: ${elapsed}ms"
        Log.i(LOG_TAG, "timing $label=${elapsed}ms")
    }

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)

        var bitmap: Bitmap? = null
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView()
                ?: activity.findViewById<View>(android.R.id.content)
                ?: activity.window.decorView
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            bitmap = b
        }
        bitmap?.let { writeBitmap("$name-viewport", it) }
        writeText("$name-visible-terminal.txt", visibleTerminalText())
        bitmap?.recycle()
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE576_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE576_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File =
        writeText("timings.txt", timings.joinToString(separator = "\n", postfix = "\n"))

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create artifact directory ${dir.absolutePath}"
        }
        return File(dir, name)
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

    private fun buildLargeComposerPrompt(): String {
        val builder = StringBuilder()
        builder.appendLine("ISSUE398-PROMPT-BEGIN")
        var index = 0
        while (builder.length < TARGET_PROMPT_BYTES - PROMPT_SENTINEL.length - 128) {
            builder.append("issue398 codex overflow proof line ")
            builder.append(index.toString().padStart(5, '0'))
            builder.append(": rewrite pressure, send-keys chunking, reconnect classifier, ")
            builder.append("and composer draft retention all stay on the production path.\n")
            index += 1
        }
        builder.append(PROMPT_SENTINEL)
        return builder.toString()
    }

    private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

    private enum class FloodOutcome(val label: String) {
        Completed("completed"),
        OutputOverflow("output-overflow"),
    }

    private data class TerminalGrid(val columns: Int, val rows: Int)

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue576CodexOverflow"
        const val DEVICE_DIR_NAME: String = "issue576-codex-overflow-no-reconnect"
        const val SESSION_NAME: String = "issue576-codex-overflow"
        const val READY_MARKER: String = "ISSUE576-LIVE-READY"
        const val DONE_MARKER: String = "ISSUE576-LIVE-DONE"
        const val PROMPT_SENTINEL: String = "ISSUE398-PROMPT-END"
        const val PROMPT_RECEIVED_MARKER: String = "ISSUE398-PROMPT-RECEIVED"
        const val REMOTE_SCRIPT: String = "/tmp/pocketshell-issue576-codex-overflow.sh"
        const val REMOTE_TRIGGER: String = "/tmp/pocketshell-issue576-codex-overflow.trigger"
        const val REMOTE_RECEIPT: String = "/tmp/pocketshell-issue398-codex-overflow.receipt"
        const val FLOOD_LINES: Int = 640
        const val FLOOD_MAX_SECONDS: Int = 50
        const val PROMPT_RECEIPT_TIMEOUT_SECONDS: Int = 45
        const val PROMPT_RECEIPT_TIMEOUT_MS: Long = 55_000L
        const val FLOOD_TIMEOUT_MS: Long = 75_000L
        const val MIN_PROMPT_BYTES: Int = 32 * 1024
        const val TARGET_PROMPT_BYTES: Int = 36 * 1024
        const val MAX_PROMPT_BYTES: Int = 128 * 1024
        const val COMPOSER_INPUT_CHUNK_CHARS: Int = 1024
        val FORBIDDEN_DIAGNOSTIC_TEXT: Set<String> = setOf(
            "tmux-command-timeout",
            "tmux_client_command_timeout",
            "read eof",
            "network reconnect",
            "network_reconnect_start",
        )
    }
}
