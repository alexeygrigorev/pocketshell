package com.pocketshell.app.proof

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.projects.folderDetailRowTestTag
import com.pocketshell.app.projects.folderHeaderClickTestTag
import com.pocketshell.app.projects.folderRowTestTag
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Opt-in 10-minute foreground hold against the deterministic Docker
 * `agents` fixture (port 2222). Issue #148.
 *
 * Why this test exists:
 *
 *  - Reconnect loops, SSH keep-alive misconfiguration, and slow memory
 *    leaks only surface over an extended idle period. The release gate's
 *    2-minute smoke tests cannot catch any of these.
 *  - PocketShell deliberately does NOT run background work (locked
 *    decision). The realistic failure mode for the issues above is
 *    therefore an extended *foreground* hold: the user keeps the
 *    PocketShell session screen open for many minutes. This test
 *    reproduces that scenario.
 *
 * Why this is opt-in:
 *
 *  - 10 minutes per run is too expensive for the default
 *    `connectedDebugAndroidTest` suite. CI runtime for the whole
 *    instrumentation surface would jump 10x.
 *  - The test is invoked from `scripts/release-emulator-validation.sh`
 *    only when the caller sets `LONG_RUNNING_TEST=1`. The script then
 *    forwards an instrumentation arg (`pocketshellLongRunningTest=1`)
 *    to the device. Without that arg every `@Test` short-circuits via
 *    `Assume.assumeTrue` and the class is silently skipped.
 *  - Per-issue, only a single CI run is required when opted-in. The
 *    cost trade-off is documented on the issue; the cheaper smoke tests
 *    still run on every push, this one only on the explicit gate.
 *
 * Note on env-var-vs-instrumentation-arg:
 *
 *  - The issue brief sketches the opt-in as
 *    `System.getenv("LONG_RUNNING_TEST") == "1"`. That does NOT work
 *    on Android: tests run inside the app process under
 *    `AndroidJUnitRunner` on the emulator, so the runner host's env
 *    vars are not visible to test code. (See [TerminalTestTimeouts]'s
 *    kdoc for the same finding on a different opt-in.) The supported
 *    channel is an instrumentation runner argument, propagated via
 *    `am instrument -e pocketshellLongRunningTest 1` or gradle's
 *    `-Pandroid.testInstrumentationRunnerArguments.<key>=<value>`.
 *  - The shell script wrapper is the bridge: it reads
 *    `LONG_RUNNING_TEST` from its own host env (so the operator's
 *    workflow stays the env-var-driven flow the issue asked for) and
 *    forwards the on-device value through the instrumentation arg.
 *
 * What this test asserts (mapped to the acceptance criteria):
 *
 *  1. Attach a tmux session to the Docker `agents` fixture and hold the
 *     activity in `Lifecycle.State.RESUMED` for the full window.
 *  2. Every 2 minutes, six times (t=0, 2, 4, 6, 8, 10), send
 *     `printf 'tick:<epoch>\n'` and assert each tick appears in the
 *     visible terminal transcript via [TerminalTextMatcher.containsWrapTolerant].
 *     The matcher is the same wrap-tolerant matcher the other connected
 *     tests use; it survives the tmux pane wrapping the line at the
 *     emulator's grid width.
 *  3. During the quiet hold between visible terminal ticks, emit an
 *     instrumentation stream heartbeat and run a lightweight
 *     UiAutomation shell no-op every 15 seconds. This keeps both the
 *     host-side `adb shell am instrument -w -r` output stream and the
 *     shell-command path used for meminfo/logcat active without touching
 *     the app/session state that the test asserts on.
 *  4. Reconnect-loop assertion is signal-based: we read `adb logcat`
 *     filtered to the `issue105-diag` tag and count occurrences of
 *     `ssh-read-eof` / `ssh-read-failed`. Each one indicates the SSH
 *     transport feeding `TmuxClient` tore down. A healthy 10-minute hold
 *     produces ZERO of these — that is the signal, not a wall-clock
 *     heuristic.
 *  5. Production SSH keep-alive interval is
 *     [SshConnection.DEFAULT_KEEP_ALIVE_SECONDS] = 15 seconds (4 cycles
 *     per minute, NOT the "≤ 1 cycle per minute" the issue body suggested).
 *     The cycles are emitted by sshj's `KeepAlive` thread internally and
 *     are NOT logged at the app layer; we therefore cannot count them
 *     directly. What we CAN assert — and the load-bearing signal here —
 *     is that the keep-alive thread's job (keep the transport alive)
 *     succeeded: zero `ssh-read-eof`/`ssh-read-failed` events across
 *     the 10-minute hold. The keep-alive interval is recorded in the
 *     summary artifact for traceability.
 *  6. Memory growth is asserted via a deterministic parse of
 *     `dumpsys meminfo <package>` ("TOTAL PSS" line). Baseline is
 *     captured after the session is attached and one tick has landed.
 *     Final is captured at the end of the hold. Growth must be < 50 MB.
 *
 * Failure artifacts:
 *
 *  - `long-running-summary.txt` — timings, tick latencies, reconnect
 *    counters, memory baseline/final/growth, keep-alive interval.
 *  - `long-running-logcat-tail.txt` — last ~6 MB of logcat for the
 *    `issue105-diag` tag scoped to the run window.
 *  - `long-running-visible-terminal.txt` — final transcript snapshot.
 *
 * Sibling artifact contract: writes under the same
 * `additional_test_output/long-running-session/` device dir so
 * `scripts/release-emulator-validation.sh` can pull artifacts with the
 * usual `adb pull` step.
 */
@RunWith(AndroidJUnit4::class)
class LongRunningSessionStabilityTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val timings = mutableListOf<String>()

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
    }

    @Test
    fun tenMinuteForegroundHoldRetainsTmuxSessionWithoutReconnectsOrMemoryGrowth() = runBlocking {
        assumeLongRunningTestEnabled()
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        // Clear logcat before the test starts so the reconnect-counter
        // parse below operates on a slice that belongs to THIS run only.
        // The instrumentation runner inherits adb shell capability via
        // `UiAutomation.executeShellCommand`.
        execShellCommand("logcat -c")

        val testStart = SystemClock.elapsedRealtime()
        val marker = "lr${System.currentTimeMillis().toString(36).takeLast(5)}"
        val sessionName = "lr-$marker"
        val workDir = "/tmp/ps-lr-$marker"
        val hostRowTag = seedDockerHost(key, "Long Running Hold $marker")

        prepareTmuxSession(key, sessionName, marker, workDir)
        try {
            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            // Locked decision: PocketShell does NOT run in background. This
            // test holds the activity FOREGROUND for the entire window so
            // the assertion is about steady-state foreground stability,
            // not the (intentionally unsupported) background path.
            launchedActivity?.moveToState(Lifecycle.State.RESUMED)

            openHostPickerAndAttachTmux(
                hostRowTag,
                "Long Running Hold $marker",
                workDir,
                sessionName,
            )

            // --- Baseline: send tick 0 and capture meminfo PSS ----------------
            val tickLatencies = mutableListOf<Long>()
            val firstTick = sendTickAndAssertVisible(tickIndex = 0)
            tickLatencies += firstTick

            // Let the process settle for a brief moment after the first
            // tick lands so the baseline PSS is not contaminated by the
            // attach-time allocations still being reclaimed by GC.
            SystemClock.sleep(BASELINE_SETTLE_MS)
            val baselineMemoryKb = readTotalPssKb()
            recordTiming("baseline_total_pss_kb", baselineMemoryKb)
            recordTiming(
                "ssh_keep_alive_interval_seconds",
                PRODUCTION_SSH_KEEP_ALIVE_SECONDS.toLong(),
            )
            recordTiming(
                "instrumentation_heartbeat_interval_ms",
                LongRunningInstrumentationHeartbeat.DEFAULT_INTERVAL_MS,
            )
            emitInstrumentationHeartbeat(
                testStart = testStart,
                nextTickIndex = 1,
                label = "baseline-complete",
            )

            // --- Five further ticks, ~2 minutes apart -------------------------
            //
            // The cadence is "send every TICK_INTERVAL_MS regardless of how
            // long the previous tick took" — i.e. the wall-clock between
            // tick boundaries is the load-bearing measurement, not a
            // sleep-after-tick heuristic. We compute the next deadline from
            // the test-start timestamp so a slow tick does not drift the
            // schedule.
            for (tickIndex in 1..LAST_TICK_INDEX) {
                val nextDeadline = testStart + tickIndex.toLong() * TICK_INTERVAL_MS
                sleepUntilWithInstrumentationHeartbeats(
                    deadlineMs = nextDeadline,
                    testStart = testStart,
                    nextTickIndex = tickIndex,
                )
                tickLatencies += sendTickAndAssertVisible(tickIndex)
                if (tickIndex < LAST_TICK_INDEX) {
                    emitInstrumentationHeartbeat(
                        testStart = testStart,
                        nextTickIndex = tickIndex + 1,
                        label = "tick-$tickIndex-complete",
                    )
                }
            }

            // --- Final memory capture + reconnect counter --------------------
            val finalMemoryKb = readTotalPssKb()
            val growthKb = finalMemoryKb - baselineMemoryKb
            val growthMb = growthKb / 1024.0
            recordTiming("final_total_pss_kb", finalMemoryKb)
            recordTiming("memory_growth_kb", growthKb)

            // Capture the logcat slice for the entire run window so the
            // reviewer can audit alongside the assertion. We restrict to
            // the `issue105-diag` tag because that is where SSH transport
            // reader teardowns are logged.
            val logcatTail = captureLogcatTail()
            val reconnectEvents = countReconnectEventLines(logcatTail)
            recordTiming("reconnect_events", reconnectEvents.toLong())

            // Final transcript snapshot for the artifact bundle.
            val finalTranscript = visibleTerminalText()
            writeText("long-running-visible-terminal.txt", finalTranscript.takeLast(8_000))
            writeText("long-running-logcat-tail.txt", logcatTail.takeLast(MAX_LOGCAT_ARTIFACT_BYTES))

            // Sanity: the activity is still RESUMED — proves the
            // foreground-hold contract survived the wait.
            val resumedAtEnd = launchedActivity?.state == Lifecycle.State.RESUMED
            assertTrue(
                "expected ActivityScenario to still be in RESUMED at end of hold; " +
                    "actual=${launchedActivity?.state}",
                resumedAtEnd,
            )

            // Acceptance: zero SSH transport teardown events.
            assertEquals(
                "expected zero SSH transport teardown events (ssh-read-eof / ssh-read-failed) " +
                    "during the ${TOTAL_DURATION_MS / 60_000}-minute foreground hold; " +
                    "see long-running-logcat-tail.txt for the captured slice",
                0L,
                reconnectEvents.toLong(),
            )

            // Acceptance: memory growth under 50 MB.
            assertTrue(
                "expected app TOTAL PSS growth < ${MEMORY_GROWTH_BUDGET_MB} MB across the hold; " +
                    "baseline=${baselineMemoryKb} KB final=${finalMemoryKb} KB growth=${growthKb} KB " +
                    "(${"%.2f".format(Locale.US, growthMb)} MB)",
                growthMb < MEMORY_GROWTH_BUDGET_MB,
            )

            // Acceptance: tick output is still visible (last command output
            // remained on screen, i.e. the pane did not silently disconnect
            // and clear).
            val lastTickMarker = tickPayloadForLatest(tickLatencies.size - 1)
            val gridColumns = terminalGridSize().columns
            assertTrue(
                "expected last tick marker `$lastTickMarker` to still be visible at end of hold; " +
                    "final transcript tail (last 800 chars):\n${finalTranscript.takeLast(800)}",
                TerminalTextMatcher.containsWrapTolerant(
                    finalTranscript,
                    lastTickMarker,
                    terminalCols = gridColumns,
                ),
            )

            writeSummary(
                tickLatencies = tickLatencies,
                baselineMemoryKb = baselineMemoryKb,
                finalMemoryKb = finalMemoryKb,
                reconnectEvents = reconnectEvents,
                gridColumns = gridColumns,
                totalRuntimeMs = SystemClock.elapsedRealtime() - testStart,
            )
        } finally {
            runCatching { cleanupTmuxSession(key, sessionName, workDir) }
        }
        Unit
    }

    // ---------------- helpers ----------------

    private fun assumeLongRunningTestEnabled() {
        val enabled = InstrumentationRegistry.getArguments()
            .getString(LONG_RUNNING_TEST_ARG)
            ?.lowercase(Locale.US) in setOf("1", "true", "yes")
        assumeTrue(
            "LongRunningSessionStabilityTest is opt-in; pass " +
                "-e $LONG_RUNNING_TEST_ARG 1 (set by " +
                "LONG_RUNNING_TEST=1 scripts/release-emulator-validation.sh)",
            enabled,
        )
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
                name = "long-running-key-${System.currentTimeMillis()}",
                content = key,
            )
            val appVersion = appContext.packageManager
                .getPackageInfo(appContext.packageName, 0)
                .versionName ?: error("target app versionName is missing")
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = hostName,
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                    pocketshellInstalled = true,
                    pocketshellLastDetectedAt = System.currentTimeMillis(),
                    pocketshellCliVersion = appVersion,
                    pocketshellExpectedCliVersion = appVersion,
                    pocketshellVersionCompatible = true,
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    private suspend fun prepareTmuxSession(
        key: String,
        sessionName: String,
        marker: String,
        workDir: String,
    ) {
        val command = """
            mkdir -p ${shellQuote(workDir)}
            tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true
            tmux new-session -d -s ${shellQuote(sessionName)} -c ${shellQuote(workDir)} ${shellQuote("printf 'READY-$marker\\n'; exec sh")}
        """.trimIndent()
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec(command) }
        }
        val exec = result.getOrNull()
        assertTrue(
            "expected long-running tmux preparation to succeed, got ${result.exceptionOrNull()} " +
                "stdout='${exec?.stdout}' stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
    }

    private suspend fun cleanupTmuxSession(key: String, sessionName: String, workDir: String) {
        val command = """
            tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true
            rm -rf ${shellQuote(workDir)}
        """.trimIndent()
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec(command) }
        }
    }

    private fun openHostPickerAndAttachTmux(
        hostRowTag: String,
        hostName: String,
        workDir: String,
        sessionName: String,
    ) {
        // Picker stages can be slow under heavy emulator contention
        // (sibling instrumentation runs, CI swiftshader GPU). Use the
        // CI-aware terminal visibility deadline so the picker probes get
        // the same generous ceiling the rest of the connected suite uses
        // for "wait for remote round-trip to land in Compose".
        val pickerTimeoutMs = TerminalTestTimeouts.terminalVisibilityTimeoutMs()
        compose.waitUntil(timeoutMillis = pickerTimeoutMs) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText(hostName, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = pickerTimeoutMs) {
            compose.onAllNodesWithTag(folderRowTestTag(workDir), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        if (compose.onAllNodesWithTag(
                folderDetailRowTestTag(workDir, sessionName),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isEmpty()
        ) {
            compose.onNodeWithTag(
                folderHeaderClickTestTag(workDir),
                useUnmergedTree = true,
            ).performClick()
        }
        compose.waitUntil(timeoutMillis = pickerTimeoutMs) {
            compose.onAllNodesWithTag(
                folderDetailRowTestTag(workDir, sessionName),
                useUnmergedTree = true,
            )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(
            folderDetailRowTestTag(workDir, sessionName),
            useUnmergedTree = true,
        ).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        // Give tmux a moment to render the initial pane content before
        // the test starts driving input.
        waitForVisibleTerminalText("tmux pane ready") { it.isNotBlank() }
    }

    private fun sendTickAndAssertVisible(tickIndex: Int): Long {
        val tickStart = SystemClock.elapsedRealtime()
        val tickEpoch = System.currentTimeMillis() / 1000L
        // Embed the tick index into the marker too so the visible-output
        // assertion fails on a *specific* missing tick rather than just
        // "some tick", and so the per-tick latency lines in the summary
        // are unambiguous.
        val tickPayload = "tick:$tickIndex:$tickEpoch"
        sendCommandViaTerminalInput("printf '$tickPayload\\n'")
        waitForVisibleTerminalText(
            "long-running tick $tickIndex",
            timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
        ) { transcript ->
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                tickPayload,
                terminalCols = terminalGridSize().columns,
            )
        }
        val latency = SystemClock.elapsedRealtime() - tickStart
        recordTiming("tick_${tickIndex}_latency_ms", latency)
        return latency
    }

    private fun sleepUntilWithInstrumentationHeartbeats(
        deadlineMs: Long,
        testStart: Long,
        nextTickIndex: Int,
    ) {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val sleepMs = LongRunningInstrumentationHeartbeat.sleepSliceMs(
                nowMs = now,
                deadlineMs = deadlineMs,
            )
            if (sleepMs == 0L) return

            SystemClock.sleep(sleepMs)

            if (SystemClock.elapsedRealtime() < deadlineMs) {
                emitInstrumentationHeartbeat(
                    testStart = testStart,
                    nextTickIndex = nextTickIndex,
                    label = "hold",
                )
            }
        }
    }

    private fun emitInstrumentationHeartbeat(
        testStart: Long,
        nextTickIndex: Int,
        label: String,
    ) {
        touchUiAutomationShellHeartbeat()
        val line = LongRunningInstrumentationHeartbeat.line(
            elapsedMs = SystemClock.elapsedRealtime() - testStart,
            nextTickIndex = nextTickIndex,
            label = label,
        )
        println(line)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            LongRunningInstrumentationHeartbeat.streamBundle(line),
        )
    }

    private fun touchUiAutomationShellHeartbeat() {
        try {
            execShellCommand("true")
        } catch (error: Throwable) {
            throw AssertionError(
                "UiAutomation shell heartbeat failed during long-running hold",
                error,
            )
        }
    }

    /**
     * Compose the same `tick:<index>:<epoch>` payload [sendTickAndAssertVisible]
     * uses for [tickIndex]. Only the index portion is deterministic across
     * the test — the epoch portion is read back from the recorded timing
     * line so the end-of-test assertion can re-match what was actually
     * sent. We don't store every tick payload in memory; instead the
     * caller passes the index of the last tick and we rebuild the prefix.
     */
    private fun tickPayloadForLatest(tickIndex: Int): String =
        "tick:$tickIndex:"

    private fun sendCommandViaTerminalInput(command: String) {
        // Chunked commit mirrors the keystroke-by-keystroke path the
        // phone user hits when typing into the prompt composer.
        command.chunked(4).forEach { chunk ->
            val committed = terminalInputConnection().commitText(chunk, 1)
            assertTrue("expected terminal input connection to commit `$chunk`", committed)
            SystemClock.sleep(35)
        }
        val enterCommitted = terminalInputConnection().commitText("\n", 1)
        assertTrue("expected terminal input connection to submit command", enterCommitted)
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
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
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
            "expected visible terminal text for $label, got (last ${last.length} chars):\n$last",
            satisfied,
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
        return requireNotNull(grid) { "Terminal emulator grid was not available" }
    }

    /**
     * Capture `dumpsys meminfo <package>` and parse the "TOTAL PSS" /
     * "TOTAL" row for the app process's resident PSS in KB.
     *
     * The output format is consistent across api 26+ — the row of
     * interest looks like:
     *
     * ```
     *                    Pss      Pss   Shared  Private  Shared  Private     Swap      Rss     Heap     Heap     Heap
     *                  Total    Clean    Dirty    Dirty    Clean    Clean    Dirty    Total     Size    Alloc     Free
     *                 ------   ------   ------   ------   ------   ------   ------   ------   ------   ------   ------
     *  Native Heap     6128        0        0     6024        0        0        0     7184    14336     9824     4512
     *  ...
     *           TOTAL PSS:    63872            TOTAL RSS:    97216       TOTAL SWAP (KB):     0
     *           ...
     * ```
     *
     * We match `"TOTAL PSS:" <whitespace> <integer>` to pick the
     * deterministic numeric column. On api 28+ the line is sometimes
     * reformatted to `"TOTAL PSS:" <kb> "TOTAL RSS:" <kb>` on a single
     * line, which the regex below still handles.
     *
     * The parse intentionally fails (assertion) when no match is found
     * rather than silently returning 0 — that would mask a regression
     * in the meminfo format.
     */
    private fun readTotalPssKb(): Long {
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val output = execShellCommand("dumpsys meminfo $pkg")
        val match = TOTAL_PSS_REGEX.find(output)
        assertTrue(
            "expected `dumpsys meminfo $pkg` output to contain a `TOTAL PSS:` line; " +
                "first 1500 chars of output:\n${output.take(1500)}",
            match != null,
        )
        return match!!.groupValues[1].toLong()
    }

    private fun captureLogcatTail(): String =
        execShellCommand("logcat -d -v threadtime -t 60000 $ISSUE_105_DIAG_TAG_FILTER")

    /**
     * Count SSH transport teardown events the [TmuxClient] reader logs
     * under the `issue105-diag` tag. Each occurrence indicates the
     * underlying SSH channel either threw on read (`ssh-read-failed`)
     * or hit a clean EOF (`ssh-read-eof`). Either is a reconnect-loop
     * signal during a hold-the-foreground test because we never close
     * the session intentionally.
     *
     * The match is line-anchored on the diag-tag substring so we don't
     * pick up the literal strings appearing elsewhere (e.g. test
     * assertion error messages logged from the same process).
     */
    private fun countReconnectEventLines(logcatTail: String): Int {
        var count = 0
        for (line in logcatTail.lineSequence()) {
            if (!line.contains("issue105-diag")) continue
            if (line.contains("ssh-read-eof") || line.contains("ssh-read-failed")) {
                count++
            }
        }
        return count
    }

    private fun execShellCommand(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return descriptor.useReadingText()
    }

    private fun ParcelFileDescriptor.useReadingText(): String = try {
        ParcelFileDescriptor.AutoCloseInputStream(this).bufferedReader().use { it.readText() }
    } finally {
        runCatching { close() }
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

    private fun recordTiming(name: String, value: Long) {
        val line = "LONG_RUNNING_TIMING $name=$value"
        timings += line
        println(line)
    }

    private fun writeSummary(
        tickLatencies: List<Long>,
        baselineMemoryKb: Long,
        finalMemoryKb: Long,
        reconnectEvents: Int,
        gridColumns: Int,
        totalRuntimeMs: Long,
    ) {
        val growthKb = finalMemoryKb - baselineMemoryKb
        val text = buildString {
            appendLine("long-running session stability summary")
            appendLine("=======================================")
            appendLine()
            appendLine("total_runtime_ms=$totalRuntimeMs")
            appendLine("tick_interval_ms=$TICK_INTERVAL_MS")
            appendLine("tick_count=${tickLatencies.size}")
            tickLatencies.forEachIndexed { index, latency ->
                appendLine("tick_${index}_latency_ms=$latency")
            }
            appendLine()
            appendLine("ssh_keep_alive_interval_seconds=$PRODUCTION_SSH_KEEP_ALIVE_SECONDS")
            appendLine("reconnect_events=$reconnectEvents")
            appendLine()
            appendLine("baseline_total_pss_kb=$baselineMemoryKb")
            appendLine("final_total_pss_kb=$finalMemoryKb")
            appendLine("memory_growth_kb=$growthKb")
            appendLine("memory_growth_budget_mb=$MEMORY_GROWTH_BUDGET_MB")
            appendLine()
            appendLine("terminal_grid_columns=$gridColumns")
            appendLine()
            appendLine("timings:")
            timings.forEach(::appendLine)
        }
        writeText("long-running-summary.txt", text)
    }

    private fun writeText(name: String, contents: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create artifact directory: ${dir.absolutePath}"
        }
        val file = File(dir, name)
        FileOutputStream(file).use { it.write(contents.toByteArray()) }
        println("LONG_RUNNING_ARTIFACT ${file.absolutePath}")
        return file
    }

    private data class TerminalGridSize(val columns: Int, val rows: Int)

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"

        /**
         * Instrumentation runner argument name. Set on the device by
         * `am instrument -e pocketshellLongRunningTest 1`, which the
         * release validation script forwards when its host-side
         * `LONG_RUNNING_TEST=1` opt-in is set.
         */
        const val LONG_RUNNING_TEST_ARG: String = "pocketshellLongRunningTest"

        /**
         * Device dir for this test's artifact bundle. Lives alongside
         * the existing `workflow-e2e`, `terminal-lab`, etc. so
         * `scripts/release-emulator-validation.sh` can pull artifacts
         * with the usual `adb pull` step.
         */
        const val DEVICE_DIR_NAME: String = "long-running-session"

        /**
         * Tick cadence — 2 minutes between submissions. Total six ticks
         * (indices 0..5) covers the full 10-minute hold the issue
         * specifies (`t=0, 2, 4, 6, 8, 10`).
         */
        const val TICK_INTERVAL_MS: Long = 120_000L
        const val LAST_TICK_INDEX: Int = 5
        const val TOTAL_DURATION_MS: Long = TICK_INTERVAL_MS * LAST_TICK_INDEX

        /**
         * Mirror of the `internal const val DEFAULT_KEEP_ALIVE_SECONDS`
         * in `com.pocketshell.core.ssh.SshConnection`. The constant is
         * intentionally not part of the public API of `core-ssh`, so we
         * cannot read it directly from the test. Mirroring it here keeps
         * the summary artifact's reported interval in sync with what the
         * production connect path actually configures on sshj's
         * `client.connection.keepAlive.keepAliveInterval`.
         *
         * If the production constant ever changes, this mirror must move
         * with it — the value is only used for the summary trace and
         * does NOT drive the assertion (the reconnect-event signal does).
         */
        const val PRODUCTION_SSH_KEEP_ALIVE_SECONDS: Int = 15

        /**
         * Settle window after the first tick lands and before the
         * baseline PSS is captured. Two seconds is enough for the
         * `TerminalView` invalidation and tmux pane buffer growth to
         * finish, while staying small enough that the baseline still
         * reflects "freshly-attached" memory and not "session has been
         * running for a while".
         */
        const val BASELINE_SETTLE_MS: Long = 2_000L

        /**
         * 50 MB growth budget across the 10-minute hold. Matches the
         * acceptance-criterion ceiling on issue #148. The unit is MB
         * (not MiB) for cross-team readability; the comparison is
         * computed as `growthKb / 1024.0 < MEMORY_GROWTH_BUDGET_MB`.
         */
        const val MEMORY_GROWTH_BUDGET_MB: Double = 50.0

        /**
         * Logcat tag filter passed to `logcat -d`. The `issue105-diag`
         * tag is where `RealTmuxClient` emits `ssh-read-eof` and
         * `ssh-read-failed` lines on transport teardown. Restricting
         * the slice keeps the captured artifact small (well below the
         * 4 MB device-IPC pipe ceiling) even on a 10-minute hold.
         *
         * The trailing `*:S` silences everything except the listed
         * tag — standard logcat filter syntax.
         */
        const val ISSUE_105_DIAG_TAG_FILTER: String = "issue105-diag:V *:S"

        /**
         * Cap on the logcat artifact written to disk. The actual
         * `logcat -d` output is usually much smaller (a 10-minute hold
         * for a single quiet tag yields under 100 KB), but the cap
         * defends against an unexpectedly chatty tag flushing the
         * artifact past sensible sizes.
         */
        const val MAX_LOGCAT_ARTIFACT_BYTES: Int = 6 * 1024 * 1024

        /**
         * Match the numeric "TOTAL PSS:" column out of `dumpsys meminfo
         * <package>`. The api 26+ formatter prints the integer
         * immediately after a colon and one-or-more spaces, sometimes
         * followed by `TOTAL RSS:` on the same line. Anchoring on
         * `TOTAL PSS:` and capturing the next integer is robust to
         * either layout.
         */
        val TOTAL_PSS_REGEX: Regex = Regex("""TOTAL\s*PSS:\s*(\d+)""")
    }
}
