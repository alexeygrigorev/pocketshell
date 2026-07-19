package com.pocketshell.app.proof

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.diagnostics.ReconnectCauseTrail
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.pocketshell.PocketshellCommand
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.ssh.ExecResult
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #1641 — **the storm-ENTRY-EDGE journey**, end-to-end on the production
 * path over the deterministic `agents:2222` Docker fixture: a foreign-pane
 * kind-classify that overruns its 3.5s bound must NOT tear down the shared
 * per-host `-CC` lease transport.
 *
 * ## The bug this reproduces (finding 1 of the #1641 audit; C2 of epic #1670)
 *
 * `AgentKindRemoteSource.classify` runs the host daemon guess
 * (`pocketshell agents kind`) for a FOREIGN session over the SAME warm SSH
 * lease the live tmux `-CC` reader rides (D21 — no new connection). The exec is
 * bounded at 3.5s. Before this fix, a classify SLOWER than that bound —
 * a cold host Python CLI over mobile RTT routinely is — ran `close()` on the
 * SHARED lease transport, killing the live `-CC` reader. The reader's read then
 * threw `SSHException` indistinguishable from a genuine link drop, so the app
 * re-ingested its OWN close as a fresh passive failure and entered the #1610
 * reconnect storm — silently, with no log and no cause trail. It was an
 * **uncredited storm entry trigger**, and it selectively hit sessions with
 * foreign/shell panes (consistent with #1610's "not all sessions").
 *
 * The fix routes all five bounded-exec sites through
 * [com.pocketshell.app.ssh.BoundedSessionExec], which ABANDONS the slow exec
 * (cancel the channel-local coroutine; the transport is untouched) and records
 * a named cause-trail breadcrumb. A slow classify is now "no verdict" and
 * nothing more; the shared `-CC` transport is never a casualty.
 *
 * ## The fixture (faithful, and NOT a happy localhost-fast fixture — G10/#847)
 *
 * A localhost-fast classify can never exceed the 3.5s bound, so it proves
 * NOTHING about this edge (the v0.4.10/#847 happy-fixture-masks-reality lesson).
 * This journey makes the classify GENUINELY slow: it writes an integer number of
 * seconds into the `agents` fixture's delay file, and the fixture's
 * `pocketshell agents kind` handler then `sleep`s that long BEFORE it answers
 * (still returning a valid envelope, so the host is proven ALIVE, not failing).
 * Only `agents kind` is delayed; the `-CC` attach + every other exec run at full
 * speed, so the ONLY slow thing is the exact exec the bug fired on.
 *
 * ## Fixture fidelity is HARD-ASSERTED, never skipped (the #780 model)
 *
 * [assertFixtureInjectsRealClassifyLatency] runs the exact
 * `pocketshell agents kind` command shape the app runs, over a fresh SSH exec,
 * times it, and HARD-FAILS unless it (a) took strictly longer than the app's
 * 3.5s bound and (b) still returned a valid `{"results":...}` envelope. If the
 * environment cannot produce the slow-but-alive host, this test FAILS loudly —
 * it does not `assumeTrue` itself away and it does not silently pass on a happy
 * fixture. There is no `assumeTrue` / `assumeFalse(isRunningOnCi())` anywhere in
 * this file (D31/D32 F3).
 *
 * ## Red -> green
 *
 * - **Base** (the `close()`-on-timeout shim restored): the classify closes the
 *   shared lease at 3.5s -> the `-CC` reader EOFs -> the connection status
 *   leaves `Connected` for `Reconnecting` (a spurious reconnect on a proven-up
 *   link). The [assertNoSpuriousReconnect] load-bearing assertion FAILS.
 * - **Fixed**: the classify is abandoned, the lease is untouched, the status
 *   stays `Connected`, a fresh marker still round-trips through the SAME `-CC`
 *   reader, and the `agent_kind_classify` / `bounded_exec_timeout` breadcrumb is
 *   recorded with `transportClosed=false`.
 *
 * @see com.pocketshell.app.ssh.BoundedSessionExec
 * @see com.pocketshell.app.agents.AgentKindRemoteSource
 */
@RunWith(AndroidJUnit4::class)
class SlowClassifyKeepsSharedLeaseJourneyDockerTest {

    val compose = createAndroidComposeRule<MainActivity>()
    private val grantPermissions = PreGrantPermissionsRule()

    @get:Rule
    val testName: TestName = TestName()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(grantPermissions)
        .around(SeedBeforeLaunchRule { seedRemoteAndDb() })
        .around(compose)

    private var seededKey: String? = null
    private var seededHostRowTag: String? = null
    private var delayFileWritten: Boolean = false
    private val diagnostics = RecordingDiagnosticSink()
    private val timings = mutableListOf<String>()

    /** #788: seed the remote tmux session + the DB host row BEFORE MainActivity launches. */
    private suspend fun seedRemoteAndDb() {
        val key = readFixtureKey()
        seededKey = key
        waitForSshFixtureReady(SshKey.Pem(key))
        // Make the host classify genuinely slow BEFORE the app can fire it, so
        // the very first in-app classify already overruns the 3.5s bound.
        writeClassifyDelay(key, CLASSIFY_DELAY_SECS)
        seedForeignTmuxSession(key)
        seededHostRowTag = seedDockerHost(key)
    }

    @Before
    fun setUp() {
        clearLastSessionPrefs()
    }

    @After
    fun tearDown() {
        // Clear the delay file FIRST so a sibling journey class reusing the
        // shared `agents:2222` fixture is never left with a slow classify.
        runCatching { runBlocking { clearClassifyDelay() } }
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        runCatching { diagnostics.close() }
        clearLastSessionPrefs()
        seededKey?.let { key -> runCatching { runBlocking { cleanupRemoteTmuxSession(key) } } }
        runCatching { writeTimings() }
    }

    /**
     * THE #1641 PROOF. A foreign-pane classify overruns its bound while a live
     * `-CC` session rides the shared lease; the shared transport must survive.
     */
    @Test
    fun slowForeignPaneClassifyMustNotCloseTheSharedLeaseTransport() {
        runBlocking<Unit> {
            val key = requireNotNull(seededKey)

            // (0) FIXTURE FIDELITY — hard, never skipped. Prove the host really
            //     produces the maintainer's reported state: a classify that is
            //     ALIVE but SLOWER than the app's 3.5s bound. True on BOTH base
            //     and fix; a happy fast fixture fails here instead of passing
            //     vacuously later.
            assertFixtureInjectsRealClassifyLatency(key)

            attachSeededTmuxSession(requireNotNull(seededHostRowTag))
            waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
            waitForConnected("initial attach")

            DiagnosticEvents.install(diagnostics)
            diagnostics.clear()
            observedStatuses.clear()

            // ---- Let the app's own foreign-pane classify fire and overrun ----
            // The classify runs over the shared lease shortly after attach. We
            // watch until either the abandonment breadcrumb lands (fix) or the
            // connection leaves Connected (base storm), bounded by a ceiling.
            val start = SystemClock.elapsedRealtime()
            val settled = waitUntil(OBSERVE_WINDOW_MS) {
                classifyTimeoutBreadcrumbs().isNotEmpty() || sawReconnect()
            }
            // Keep sampling a little past the first signal so a delayed close on
            // base still registers as a reconnect rather than being missed.
            waitUntil(POST_SIGNAL_SETTLE_MS) { false }
            recordTiming("classify_observe_elapsed_ms", SystemClock.elapsedRealtime() - start)
            recordTiming("classify_settled_bool", if (settled) 1L else 0L)
            recordTiming("classify_timeout_breadcrumbs", classifyTimeoutBreadcrumbs().size.toLong())
            recordTiming("observed_statuses", observedStatuses.joinToString("|"))

            // (1) THE LOAD-BEARING ASSERTION — no spurious reconnect. On base the
            //     slow classify closes the shared lease and the `-CC` reader EOFs,
            //     flipping the status to Reconnecting; here it must never leave
            //     Connected.
            assertNoSpuriousReconnect()

            // (2) THE `-CC` CONTROL CHANNEL IS STILL LIVE — a fresh marker written
            //     into the pane over a SEPARATE exec must stream back through the
            //     SAME `-CC` reader the classify would have killed.
            emitMarkerIntoPane(key, LIVE_MARKER)
            val roundTripped = runCatching {
                waitForVisibleTerminal("post-classify live marker", ROUND_TRIP_WINDOW_MS) {
                    it.contains(LIVE_MARKER)
                }
                true
            }.getOrDefault(false)
            recordTiming("cc_marker_round_tripped_bool", if (roundTripped) 1L else 0L)
            assertTrue(
                "A marker written into the pane AFTER the slow classify did not round-trip " +
                    "through the shared `-CC` reader (status=${currentConnectionStatus()}). " +
                    "The classify killed the control channel the reader rides — the exact " +
                    "#1641 storm-entry edge.",
                roundTripped,
            )

            // (3) THE ABANDONMENT IS ATTRIBUTABLE (no more silent closes). The
            //     classify DID overrun and was abandoned WITHOUT closing the
            //     transport — the breadcrumb the silent close never wrote.
            val breadcrumbs = classifyTimeoutBreadcrumbs()
            recordTiming("classify_breadcrumb_count", breadcrumbs.size.toLong())
            assertTrue(
                "Expected at least one `agent_kind_classify` bounded_exec_timeout cause-trail " +
                    "breadcrumb proving the slow classify ran and was abandoned; found none. " +
                    "events=${diagnostics.eventsNamed(ReconnectCauseTrail.NAME).map { it.fields }}",
                breadcrumbs.isNotEmpty(),
            )
            val crumb = breadcrumbs.first()
            assertEquals(
                "the abandoned classify must record transportClosed=false — the shared lease " +
                    "was preserved, not torn down. crumb=${crumb.fields}",
                false,
                crumb.fields["transportClosed"],
            )
            assertEquals(
                "the abandoned classify must record the transport was still ALIVE when we " +
                    "walked away. crumb=${crumb.fields}",
                true,
                crumb.fields["transportAlive"],
            )
        }
    }

    // -- fixture fidelity ----------------------------------------------------------------

    /**
     * HARD-assert the host reproduces the reported state: `pocketshell agents
     * kind` is ALIVE but SLOWER than the app's 3.5s bound. Runs the EXACT command
     * shape [com.pocketshell.app.agents.AgentKindRemoteSource] runs, over a fresh
     * SSH exec, and times it. Never `assumeTrue` — a fast/happy fixture FAILS
     * here rather than passing the rest of the test vacuously.
     */
    private suspend fun assertFixtureInjectsRealClassifyLatency(key: String) {
        val requestJson = """{"panes":[{"pane_id":"%0","pane_pid":1}]}"""
        val command =
            "printf %s ${shellQuote(requestJson)} | { " +
                PocketshellCommand.wrap("agents kind") +
                " ; }"
        val started = SystemClock.elapsedRealtime()
        val result = execRemote(key, command)
        val elapsedMs = SystemClock.elapsedRealtime() - started
        recordTiming("fixture_classify_elapsed_ms", elapsedMs)
        recordTiming("fixture_classify_exit", result.exitCode.toLong())
        assertTrue(
            "FIXTURE DID NOT REPRODUCE THE REPORTED STATE: `pocketshell agents kind` returned " +
                "in ${elapsedMs}ms, which is NOT slower than the app's ${CLASSIFY_BOUND_MS}ms " +
                "bound. A localhost-fast classify can never overrun the bound, so it cannot " +
                "reproduce the storm-entry edge (the #847/G10 happy-fixture lesson). Check the " +
                "`agents` fixture was rebuilt with the #1641 delay hook and the delay file was " +
                "written.",
            elapsedMs > CLASSIFY_BOUND_MS,
        )
        assertTrue(
            "the slow classify must still return a valid `{\"results\":...}` envelope so the " +
                "host is proven ALIVE (slow, not failing); exit=${result.exitCode} " +
                "stdout='${result.stdout.take(200)}' stderr='${result.stderr.take(200)}'",
            result.exitCode == 0 && result.stdout.contains("\"results\""),
        )
    }

    // -- observation ---------------------------------------------------------------------

    /** Every distinct connection status this run has projected, sampled continuously. */
    private val observedStatuses = mutableSetOf<String>()

    private fun sampleStatus(): TmuxSessionViewModel.ConnectionStatus {
        val status = currentConnectionStatus()
        observedStatuses += status::class.simpleName ?: status.toString()
        return status
    }

    /** A reconnect is any transition OUT of Connected/idle-before-attach. */
    private fun sawReconnect(): Boolean = when (sampleStatus()) {
        is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        is TmuxSessionViewModel.ConnectionStatus.Connecting,
        is TmuxSessionViewModel.ConnectionStatus.Switching,
        is TmuxSessionViewModel.ConnectionStatus.Failed,
        -> true
        else -> false
    }

    /**
     * THE LOAD-BEARING ASSERTION. The connection must have STAYED Connected
     * across the whole classify window: no `reconnect_fail` diagnostic event and
     * no status that is not Connected were ever observed. On base the slow
     * classify closes the shared lease and this FAILS (Reconnecting appears).
     */
    private fun assertNoSpuriousReconnect() {
        val reconnectFails = diagnostics.eventsNamed("reconnect_fail")
        recordTiming("reconnect_fail_events", reconnectFails.size.toLong())
        assertEquals(
            "THE #1641 STORM-ENTRY EDGE: ${reconnectFails.size} passive-reconnect failure " +
                "event(s) fired while a merely-SLOW foreign-pane classify ran over the shared " +
                "lease. A slow classify on a proven-up link must be abandoned, never close the " +
                "`-CC` transport. events=${reconnectFails.map { it.fields }}",
            0,
            reconnectFails.size,
        )
        val nonConnected = observedStatuses.filter { it != "Connected" }
        assertTrue(
            "THE #1641 STORM-ENTRY EDGE: the connection left Connected (observed=$observedStatuses) " +
                "while a merely-SLOW classify ran. On the maintainer's degraded link this is the " +
                "spurious reconnect the classify's self-close triggered; the status must stay " +
                "Connected across the classify timeout.",
            nonConnected.isEmpty(),
        )
        assertTrue(
            "the session must be Connected at the end of the classify window; " +
                "status=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    private fun classifyTimeoutBreadcrumbs(): List<RecordedDiagnosticEvent> =
        diagnostics.eventsNamed(ReconnectCauseTrail.NAME).filter {
            it.fields["callerSite"] == CLASSIFY_CALLER_SITE &&
                it.fields["stage"] == BOUNDED_EXEC_TIMEOUT_STAGE
        }

    // -- the delay fixture ---------------------------------------------------------------

    private suspend fun writeClassifyDelay(key: String, seconds: Int) {
        val result = execRemote(
            key,
            "printf %s $seconds > ${shellQuote(DELAY_FILE)}; echo delay_written",
        )
        assertTrue(
            "expected to write the classify delay file; exit=${result.exitCode} " +
                "stderr='${result.stderr}'",
            result.exitCode == 0 && result.stdout.contains("delay_written"),
        )
        delayFileWritten = true
    }

    /** Remove the delay so the shared fixture is fast again. Idempotent. */
    private suspend fun clearClassifyDelay() {
        if (!delayFileWritten) return
        val key = seededKey ?: return
        execRemote(key, "rm -f ${shellQuote(DELAY_FILE)} 2>/dev/null || true; echo cleared")
        delayFileWritten = false
    }

    private suspend fun execRemote(key: String, command: String): ExecResult =
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(command) } }.getOrThrow()

    // -- attach + IO helpers -------------------------------------------------------------

    private fun attachSeededTmuxSession(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = HOST_ROW_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
    }

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            compose.activityRule.scenario.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession != null && view.mEmulator != null
            }
            attached
        }
    }

    private suspend fun emitMarkerIntoPane(key: String, marker: String) {
        execRemote(
            key,
            "tmux send-keys -t ${shellQuote(SESSION_NAME)} " +
                shellQuote("printf '$marker\\n'") + " Enter",
        )
    }

    private fun waitForConnected(label: String) {
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected
        }
        assertTrue(
            "expected Connected after $label, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    private fun currentConnectionStatus(): TmuxSessionViewModel.ConnectionStatus {
        var status: TmuxSessionViewModel.ConnectionStatus =
            TmuxSessionViewModel.ConnectionStatus.Idle
        compose.activityRule.scenario.onActivity { activity ->
            status = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .connectionStatus
                .value
        }
        return status
    }

    private fun waitForVisibleTerminal(
        label: String,
        timeoutMillis: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
        predicate: (String) -> Boolean,
    ): String {
        var last = ""
        compose.waitUntil(timeoutMillis = timeoutMillis) {
            last = visibleTerminalText()
            last.isNotBlank() && predicate(last)
        }
        assertTrue("expected visible terminal for $label; got:\n$last", predicate(last))
        return last
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

    /** Poll [condition] while continuously sampling the displayed status. */
    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            sampleStatus()
            if (condition()) return true
            SystemClock.sleep(POLL_MS)
        }
        sampleStatus()
        return condition()
    }

    // -- seeding / cleanup ---------------------------------------------------------------

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedDockerHost(key: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue1641-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue1641 Slow Classify",
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

    /**
     * Seed a FOREIGN tmux session — no `@ps_agent_kind` recorded option — so the
     * app takes the one-shot daemon-guess classify path (a session PocketShell
     * launched would carry the recorded kind and skip the guess entirely). The
     * shell pane is the classify's subject.
     */
    private suspend fun seedForeignTmuxSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_NAME)} " +
                    shellQuote("printf '$READY_MARKER\\n'; exec sh -i"),
            )
            appendLine("sleep 1")
            appendLine("tmux list-sessions")
        }
        val result = execRemoteSetupUntilReady(
            key = SshKey.Pem(key),
            command = script,
            description = "issue1641 slow-classify foreign tmux seed session",
        )
        assertTrue(
            "expected tmux seeding to succeed; exit=${result.exitCode} stderr='${result.stderr}'",
            result.exitCode == 0,
        )
    }

    private suspend fun cleanupRemoteTmuxSession(key: String) {
        runCatching {
            execRemote(
                key,
                "tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true",
            )
        }
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings-${testName.methodName}.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE1641_TIMINGS ${file.absolutePath}")
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

    private fun recordTiming(name: String, value: Long) = recordTiming(name, value.toString())

    private fun recordTiming(name: String, value: String) {
        val line = "$name=$value"
        timings += line
        println("ISSUE1641_TIMING $line")
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

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val DEVICE_DIR_NAME: String = "issue1641-slow-classify"
        const val SESSION_NAME: String = "issue1641-foreign"
        const val READY_MARKER: String = "ISSUE1641-CLASSIFY-READY"
        const val LIVE_MARKER: String = "ISSUE1641-CC-STILL-LIVE"
        const val POLL_MS: Long = 200L

        /** The `agents` fixture's #1641 latency hook (see tests/docker/agent-bin/pocketshell). */
        const val DELAY_FILE: String = "/tmp/pocketshell-agents-kind-delay-secs"

        /**
         * The app's bounded-exec ceiling — `AgentKindRemoteSource.EXEC_READ_TIMEOUT_MS`.
         * The classify must overrun this for the test to reproduce the reported state.
         */
        const val CLASSIFY_BOUND_MS: Long = 3_500L

        /**
         * How long the host `agents kind` sleeps. Comfortably past the 3.5s bound
         * so the classify times out deterministically on both the dev box and the
         * starved CI runner, while the transport underneath stays perfectly alive.
         */
        const val CLASSIFY_DELAY_SECS: Int = 10

        /** Cause-trail identifiers stamped by the fix (BoundedSessionExec + AgentKindRemoteSource). */
        const val CLASSIFY_CALLER_SITE: String = "agent_kind_classify"
        const val BOUNDED_EXEC_TIMEOUT_STAGE: String = "bounded_exec_timeout"

        /**
         * Ceiling for observing the classify overrun + its consequence. Must cover
         * the classify firing after attach + the 3.5s bound + host RTT + (on base)
         * the reconnect flip, generously so a starved CI runner does not flake.
         */
        val OBSERVE_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 30_000L

        /** Keep sampling briefly after the first signal so a delayed base close still registers. */
        val POST_SIGNAL_SETTLE_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 8_000L else 4_000L

        val ROUND_TRIP_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 45_000L else 30_000L
        val HOST_ROW_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
