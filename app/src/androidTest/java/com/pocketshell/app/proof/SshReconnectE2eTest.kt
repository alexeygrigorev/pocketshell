package com.pocketshell.app.proof

import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.projects.FOLDER_LIST_SCREEN_TAG
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.tmux.ISSUE_145_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_CONNECTING_PROGRESS_TAG
import com.pocketshell.app.tmux.TMUX_CONNECT_ATTEMPTS
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientFactory
import com.pocketshell.core.tmux.TmuxOutputBacklogOverflow
import com.pocketshell.core.tmux.protocol.ControlEvent
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #444 (retargets #145): connected coverage for the **current**
 * auto-reconnect contract (`88b309b` "Auto reconnect dropped sessions",
 * refined in #440).
 *
 * The old (#145-era) contract this test used to assert — a mid-session
 * drop surfaces a `Failed` error band and waits for a *manual* Reconnect
 * tap — was superseded when auto-reconnect landed. A dropped session now
 * auto-retries with backoff (`DEFAULT_AUTO_RECONNECT_DELAYS_MS`), so the
 * user-visible journey is: transport drop -> "Reconnecting (attempt
 * N/max)" progress row -> the session auto-recovers (reattaches to the
 * tmux session) with **no manual tap**. A non-retryable failure (bad
 * auth, unknown host, missing key) short-circuits the backoff loop and
 * falls back to the manual Reconnect affordance instead.
 *
 * This test exercises both halves:
 *
 *  - [transportDropAutoRecoversWithReconnectingProgressAndNoManualTap]
 *    drives the real app journey against the deterministic `flaky-agent`
 *    Docker fixture (host port 2226, `FLAKY_DISCONNECT_AFTER_SEC=12`).
 *    The fixture forcibly kills the SSH child after ~12s, so the app
 *    observes a wire-level transport drop. We assert the
 *    [TMUX_CONNECTING_PROGRESS_TAG] "Reconnecting to …" progress row
 *    surfaces, the session auto-recovers to a live terminal **without**
 *    ever showing the manual [TMUX_SESSION_ERROR_TAG] band, and the
 *    after-drop blip lands — proving the reattach actually re-bound the
 *    tmux pipe. The connect-attempt counter must advance by >= 2 (initial
 *    connect + at least one auto-reconnect attempt).
 *
 *  - [nonRetryableDropShortCircuitsToManualReconnectAffordance] drives a
 *    [TmuxSessionViewModel] directly (mirrors `TmuxAttachTimeoutDockerTest`):
 *    it seeds a Connected state whose [target host is **unresolvable**, then
 *    flips the attached client's `disconnected` signal. The auto-reconnect
 *    loop tries a fresh SSH connect to the bad host, hits a real
 *    `UnknownHostException` (a genuinely non-retryable failure), and falls
 *    straight back to the manual Reconnect affordance: status becomes
 *    `Failed` with an "authentication/host" reason and `canReconnect` is
 *    true. No Docker fixture is required for this half (the DNS failure is
 *    deterministic), so it runs on CI like the rest of the suite.
 *
 * The flaky fixture is owned by `tests/docker/flaky-agent/`; the host
 * port (2226) is set by `tests/docker/docker-compose.yml`'s `flaky-agent`
 * service and brought up by `.github/workflows/emulator-smoke.yml`. Run
 * the fixture before this test:
 *
 * ```bash
 * docker compose -f tests/docker/docker-compose.yml up -d --build flaky-agent
 * ```
 */
@RunWith(AndroidJUnit4::class)
class SshReconnectE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val timings = mutableListOf<String>()
    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
        factoryScope.cancel()
    }

    @Test
    fun transportDropAutoRecoversWithReconnectingProgressAndNoManualTap() = runBlocking {
        val key = readFixtureKey()
        // Wait for the flaky-agent fixture port (2226) to accept SSH.
        // The fixture kills each accepted SSH session after ~12s, but
        // `waitForSshFixtureReady` opens its own short-lived `exec`
        // session that completes in well under that window.
        waitForSshFixtureReady(SshKey.Pem(key), port = FLAKY_PORT)

        val marker = "r${System.currentTimeMillis().toString(36).takeLast(5)}"
        // The flaky-agent container's entrypoint seeds this exact name
        // before sshd starts; keep in lock-step with
        // `tests/docker/flaky-agent/Dockerfile` (env
        // `FLAKY_SEEDED_SESSION_NAME`).
        val sessionName = "flaky-main"
        val hostName = "Flaky Reconnect $marker"
        val hostRowTag = seedFlakyHost(key, hostName)

        // Snapshot the connect-attempt counter BEFORE we drive the app.
        // After auto-reconnect recovers it must have advanced by at least
        // 2 (the initial connect + at least one auto-reconnect attempt).
        val attemptsBefore = TMUX_CONNECT_ATTEMPTS.get()

        preGrantRuntimePermissions()
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        openHostPicker(hostRowTag, hostName)

        val attachStart = SystemClock.elapsedRealtime()
        waitForPickerSessionRowReady(sessionName)
        compose.onNodeWithText(sessionName).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        recordTiming("flaky_tmux_attach_ms", SystemClock.elapsedRealtime() - attachStart)

        // Before-drop blip: send a short command and assert it lands BEFORE
        // the fixture's disconnect window closes. The compose service is
        // configured with `FLAKY_DISCONNECT_AFTER_SEC=12`, leaving ~8-10s of
        // interactive headroom after the tmux attach burned the first 2-4s.
        val beforeBlipStart = SystemClock.elapsedRealtime()
        sendCommandThroughTerminalInput("printf 'BEFORE-$marker\\n'", label = "before-blip")
        waitForVisibleTerminalText(label = "before-blip output") { "BEFORE-$marker" in it }
        recordTiming("flaky_before_blip_ms", SystemClock.elapsedRealtime() - beforeBlipStart)

        // Wait for the deterministic transport drop to trigger auto-reconnect.
        // The fixture kills the SSH session ~12s after acceptance; the
        // [TmuxClient.readerLoop] observes EOF, latches `disconnected`, and
        // the view model's `disconnected` observer calls
        // `scheduleAutoReconnect`, which flips status to
        // [ConnectionStatus.Reconnecting] (-> [TMUX_CONNECTING_PROGRESS_TAG])
        // and then runs a fresh connect attempt (advancing
        // [TMUX_CONNECT_ATTEMPTS]).
        //
        // The authoritative "auto-reconnect fired" signal is the connect-
        // attempt counter advancing past the initial connect: the underlying
        // SSH lease is reused, so a successful reconnect can complete in
        // well under one compose poll frame, which makes the transient
        // "Reconnecting" progress row unreliable to latch as a hard gate.
        // We poll for EITHER the visible progress row (captured when it lasts
        // long enough) OR the counter advancing, then assert the contract on
        // the durable signals (no manual band, terminal re-attached, after-
        // drop blip lands). When we DO catch the row, we additionally assert
        // its human-readable "Reconnecting to …" copy.
        val dropWaitStart = SystemClock.elapsedRealtime()
        var sawReconnectingRow = false
        compose.waitUntil(timeoutMillis = RECONNECTING_PROGRESS_TIMEOUT_MS) {
            val rowVisible = compose
                .onAllNodesWithTag(TMUX_CONNECTING_PROGRESS_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
            if (rowVisible) sawReconnectingRow = true
            val reconnectFired = (TMUX_CONNECT_ATTEMPTS.get() - attemptsBefore) >= 2
            rowVisible || reconnectFired
        }
        recordTiming("flaky_reconnect_observed_ms", SystemClock.elapsedRealtime() - dropWaitStart)
        if (sawReconnectingRow) {
            // The progress row carries the "Reconnecting to …" copy with the
            // attempt counter. Assert the human-readable phrasing, not just
            // the tag, when the row was caught.
            assertTrue(
                "the auto-reconnect progress row must show 'Reconnecting' copy when visible",
                compose
                    .onAllNodesWithText("Reconnecting to", substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty(),
            )
        }

        // Auto-recovery: WITHOUT any manual tap, the progress row clears, the
        // manual Failed/Reconnect band never shows, and the terminal is
        // re-attached. We never touch [TMUX_SESSION_RECONNECT_TAG].
        val recoverStart = SystemClock.elapsedRealtime()
        compose.waitUntil(timeoutMillis = AUTO_RECOVERY_TIMEOUT_MS) {
            val progressGone = compose
                .onAllNodesWithTag(TMUX_CONNECTING_PROGRESS_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
            val screenUp = compose
                .onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
            progressGone && screenUp
        }
        // The whole point of auto-reconnect: the manual Failed/Reconnect band
        // must NOT be showing — the user does not tap anything for a
        // transient transport drop.
        assertTrue(
            "manual Failed error band must NOT appear during an auto-reconnect — the drop is " +
                "transient and recovers without a tap",
            compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        waitForTerminalViewAttached()
        recordTiming("flaky_auto_recovery_ms", SystemClock.elapsedRealtime() - recoverStart)

        // After-drop blip on the recovered session. Asserting the command
        // output renders proves the auto-reconnect actually re-attached the
        // tmux session and re-bound the terminal pipe — no manual tap was
        // needed at any point.
        val afterBlipStart = SystemClock.elapsedRealtime()
        sendCommandThroughTerminalInput("printf 'AFTER-$marker\\n'", label = "after-blip")
        waitForVisibleTerminalText(label = "after-blip output") { "AFTER-$marker" in it }
        recordTiming("flaky_after_blip_ms", SystemClock.elapsedRealtime() - afterBlipStart)

        // The connect-attempt counter must have advanced by at least 2:
        // the initial connect plus at least one auto-reconnect attempt.
        // Anything less than 2 means auto-reconnect never ran (i.e. the
        // user would have been stuck on a manual band — the regression
        // #444 guards against).
        val attemptsAfter = TMUX_CONNECT_ATTEMPTS.get()
        val totalAttempts = attemptsAfter - attemptsBefore
        assertTrue(
            "expected at least 2 connect attempts (initial + >=1 auto-reconnect), got $totalAttempts " +
                "(attemptsBefore=$attemptsBefore attemptsAfter=$attemptsAfter)",
            totalAttempts >= 2,
        )

        val artifactsDir = ensureArtifactDir()
        writeSummary(artifactsDir, sessionName, marker, totalAttempts)
        Unit
    }

    @Test
    fun nonRetryableDropShortCircuitsToManualReconnectAffordance() = runBlocking {
        // Issue #440 / #444: a non-retryable failure during the auto-reconnect
        // loop (bad auth, unknown host, missing key) must NOT burn the whole
        // backoff schedule — it short-circuits straight to the manual
        // Reconnect affordance so the user fixes the credential/host and taps
        // once, instead of watching doomed retries.
        //
        // We make this deterministic with a REAL `UnknownHostException` (no
        // Docker fixture, so it runs on CI): seed a Connected state whose
        // target host is unresolvable, then flip the attached client's
        // `disconnected` signal. The auto-reconnect loop runs a fresh SSH
        // connect to the bad host, the DNS lookup throws
        // `UnknownHostException` (classified non-retryable by the view model),
        // and it falls back to a `Failed` state with `canReconnect == true`.
        val vm = TmuxSessionViewModel(
            tmuxClientFactory = TmuxClientFactory(factoryScope),
            activeTmuxClients = ActiveTmuxClients(),
        )
        try {
            // Four backoff delays available. The non-retryable contract is
            // that the loop aborts after the FIRST attempt rather than
            // burning all four — so the connect-attempt counter must advance
            // by exactly 1 (not 4) before settling on Failed.
            vm.setAutoReconnectDelaysForTest(listOf(0L, 0L, 0L, 0L))
            val deadClient = DisconnectableStubTmuxClient()
            vm.replaceClientForTest(
                hostId = 444L,
                hostName = "Unresolvable Reconnect",
                // RFC 6761 reserves `.invalid` as never-resolvable, so the
                // auto-reconnect SSH connect deterministically throws
                // UnknownHostException rather than racing a real DNS server.
                host = "nonexistent-host.pocketshell.invalid",
                port = 22,
                user = "testuser",
                keyPath = "/does/not/matter.pem",
                sessionName = "unresolvable-main",
                client = deadClient,
            )
            assertTrue(
                "precondition: VM must be Connected before the drop",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )

            val attemptsBeforeDrop = TMUX_CONNECT_ATTEMPTS.get()

            // Drop: flip the attached client's latched `disconnected` flow.
            // The auto-reconnect loop runs one SSH connect to the bad host,
            // hits UnknownHostException (non-retryable), and short-circuits.
            deadClient.markDisconnected()

            // Wait for the loop to settle on a terminal Failed state with the
            // manual Reconnect affordance available. `runConnect` publishes a
            // transient raw-cause Failed on its first failed attempt before
            // `scheduleAutoReconnect` evaluates the non-retryable check, so we
            // wait for the durable post-abort state (canReconnect == true and
            // no further attempts) rather than latching that first transient.
            waitForCondition {
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed &&
                    vm.canReconnect.value
            }
            // Give any further would-be attempt a window to fire; with four
            // zero-delay backoff steps an un-aborted loop would burn all four
            // almost immediately, so a stable counter here proves the abort.
            delay(300)

            val attemptsAfter = TMUX_CONNECT_ATTEMPTS.get()
            val attempts = attemptsAfter - attemptsBeforeDrop
            assertTrue(
                "non-retryable abort must short-circuit after exactly 1 auto-reconnect " +
                    "attempt (not exhaust all 4 backoff steps), got $attempts",
                attempts == 1,
            )

            val message = (vm.connectionStatus.value as TmuxSessionViewModel.ConnectionStatus.Failed)
                .message
            assertFalse(
                "non-retryable abort must NOT report exhausting all backoff attempts, was: `$message`",
                "Auto reconnect failed after" in message,
            )
            assertTrue(
                "manual Reconnect affordance must remain available after a non-retryable abort",
                vm.canReconnect.value,
            )
        } finally {
            vm.clearForTest()
        }
        Unit
    }

    // ---- helpers ----

    private suspend fun waitForCondition(predicate: () -> Boolean) {
        withTimeout(NON_RETRYABLE_STATUS_TIMEOUT_MS) {
            while (!predicate()) {
                delay(25)
            }
        }
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedFlakyHost(key: String, hostName: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "flaky-reconnect-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = hostName,
                    hostname = DEFAULT_HOST,
                    port = FLAKY_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    // Pretend bootstrap already happened so the host-list
                    // tap goes straight to the session list (mirrors
                    // EmulatorWorkflowE2eTest's seed). The live tooling
                    // probe may still surface the "Host setup needed"
                    // bootstrap sheet, which `dismissBootstrapSheetIfPresent`
                    // skips past.
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
        // The compose hierarchy is not attached until MainActivity's
        // setContent runs after `ActivityScenario.launch`. Until then
        // `fetchSemanticsNodes()` THROWS `IllegalStateException("No compose
        // hierarchies found")` instead of returning empty, which would
        // escape the `waitUntil` predicate. Swallow that launch-window
        // throw so we keep polling until the host list renders (mirrors
        // the robust empty-rule + ActivityScenario pattern other
        // connected tests rely on).
        compose.waitUntil(timeoutMillis = 20_000) {
            composeHierarchyReady() &&
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
        compose.onNodeWithText(hostName, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        // Issue #171 (round 2, D22 hard-cut): tapping a host now routes to
        // the per-host FolderListScreen (`folder-list:screen`) where tmux
        // sessions render under their folder header. The live tooling probe
        // against the flaky-agent container can first surface the "Host
        // setup needed" bootstrap sheet (pocketshell isn't installed in
        // that fixture); skip past it so we land on the folder/session
        // tree.
        compose.waitUntil(timeoutMillis = 30_000) {
            dismissBootstrapSheetIfPresent()
            compose.onAllNodesWithTag(FOLDER_LIST_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    /**
     * If the "Host setup needed" bootstrap sheet is showing, tap its
     * "Skip" affordance so the host-tap flow continues to the session
     * list. No-op when the sheet is absent.
     */
    private fun dismissBootstrapSheetIfPresent() {
        val skip = compose
            .onAllNodesWithTag("host-bootstrap-skip", useUnmergedTree = true)
            .fetchSemanticsNodes()
        if (skip.isNotEmpty()) {
            runCatching {
                compose.onNodeWithTag("host-bootstrap-skip", useUnmergedTree = true).performClick()
            }
        }
    }

    private fun waitForPickerSessionRowReady(sessionName: String) {
        // On the FolderListScreen the seeded session lives inside a
        // collapsed folder ("[idle · 1 session]"). First wait for the
        // folder header to render once the gateway's `listSessions`
        // resolves, then expand every folder header so the inline session
        // rows (and their [sessionName] text) become visible.
        compose.waitUntil(timeoutMillis = 45_000) {
            val sessionVisible = compose
                .onAllNodesWithText(sessionName, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
            if (sessionVisible) return@waitUntil true
            expandFolderHeaders()
            false
        }
    }

    /**
     * Tap each collapsed folder header on the FolderListScreen so its
     * inline session rows expand into view. Folder headers carry the
     * `folder-list:header-click:<path>` tag. Only acts while the target
     * session text is still hidden (the caller re-checks visibility after
     * each pass), so a folder that is already expanded is not toggled
     * shut.
     */
    private fun expandFolderHeaders() {
        val headerTags = collectTestTags().filter {
            it.startsWith("folder-list:header-click:")
        }
        headerTags.forEach { tag ->
            runCatching {
                compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
            }
        }
    }

    /** Collect every `TestTag` value currently present in the semantics tree. */
    private fun collectTestTags(): List<String> {
        val roots = compose
            .onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true)
            .fetchSemanticsNodes()
        val tags = mutableListOf<String>()
        fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)
                ?.let { tags += it }
            node.children.forEach(::walk)
        }
        roots.forEach(::walk)
        return tags
    }

    /**
     * Pre-grant the Android-13+ `POST_NOTIFICATIONS` runtime permission so
     * MainActivity's first-launch request does not throw the system
     * `GrantPermissionsActivity` dialog over the activity window. That
     * dialog steals focus from MainActivity's compose hierarchy, which
     * makes the empty compose rule report "No compose hierarchies found"
     * and the whole journey times out at the host-list wait. Mirrors the
     * pre-grant other connected proof tests use (e.g.
     * `ForwardingIndicatorE2eTest`).
     */
    private fun preGrantRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            runCatching {
                instrumentation.uiAutomation.grantRuntimePermission(
                    instrumentation.targetContext.packageName,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                )
            }
        }
    }

    /**
     * Returns true once the launched MainActivity's compose tree is
     * attached. `createEmptyComposeRule()` + `ActivityScenario.launch`
     * is racy at launch: querying semantics before setContent runs
     * throws `IllegalStateException`, so we probe defensively.
     */
    private fun composeHierarchyReady(): Boolean =
        runCatching {
            compose.onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }.getOrDefault(false)

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

    private fun sendCommandThroughTerminalInput(command: String, label: String) {
        command.chunked(4).forEach { chunk ->
            val committed = terminalInputConnection().commitText(chunk, 1)
            assertTrue(
                "expected terminal input connection to commit `$chunk` for $label",
                committed,
            )
            SystemClock.sleep(35)
        }
        waitForVisibleTerminalText("$label command echo", timeoutMillis = 5_000) { transcript ->
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
            val terminalView = activity.window.decorView.findTerminalView()
            requireNotNull(terminalView) { "TerminalView was not found" }
            terminalView.requestFocus()
            connection = terminalView.onCreateInputConnection(EditorInfo())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return requireNotNull(connection) { "TerminalView did not create an InputConnection" }
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
        if (!satisfied) {
            val dir = ensureArtifactDir()
            File(dir, "failure-$label-visible-terminal.txt").writeText(last.printableForFailure())
        }
        assertTrue(
            "expected visible terminal text for $label, got:\n${last.printableForFailure()}",
            predicate(last),
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
        return grid ?: TerminalGridSize(columns = 80, rows = 24)
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

    private fun String.printableForFailure(): String =
        buildString(length) {
            for (ch in this@printableForFailure) {
                when {
                    ch == '' -> append("<ESC>")
                    ch == '\r' -> append("<CR>")
                    ch == ' ' -> append("<NUL>")
                    ch < ' ' && ch != '\n' && ch != '\t' -> append("<0x${ch.code.toString(16)}>")
                    else -> append(ch)
                }
            }
        }

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create flaky-reconnect artifact directory: ${dir.absolutePath}"
        }
        return dir
    }

    private fun recordTiming(name: String, value: Long) {
        val line = "FLAKY_RECONNECT_TIMING $name=$value"
        timings += line
        println(line)
    }

    private fun writeSummary(dir: File, sessionName: String, marker: String, totalAttempts: Int) {
        File(dir, "summary.txt").writeText(
            buildString {
                appendLine("scenario=ssh-mid-session-transport-drop-auto-reconnect")
                appendLine("contract=auto-reconnect (88b309b + #440), retargeted by #444")
                appendLine("fixture=tests/docker/flaky-agent (host port $FLAKY_PORT)")
                appendLine("session=$sessionName")
                appendLine("marker=$marker")
                appendLine("disconnect_window_sec=12 (compose.yml override)")
                appendLine("connect_attempts_total=$totalAttempts")
                appendLine("reconnect_signal_tag=$ISSUE_145_RECONNECT_TAG")
                appendLine()
                appendLine("timings:")
                timings.forEach(::appendLine)
            },
        )
        File(dir, "timings.txt").writeText(timings.joinToString(separator = "\n", postfix = "\n"))
    }

    private data class TerminalGridSize(val columns: Int, val rows: Int)

    /**
     * Minimal [TmuxClient] test double standing in for an already-attached
     * client whose underlying SSH transport then drops. Only the
     * `disconnected` latch is meaningful — flipping it via
     * [markDisconnected] drives the view model's `disconnected` observer
     * into the auto-reconnect path, where the REAL SSH connect to the
     * (unresolvable) target host produces the genuine `UnknownHostException`
     * the non-retryable classifier reacts to. Every other member is a
     * no-op: the view model never issues commands against this dead client.
     */
    private class DisconnectableStubTmuxClient : TmuxClient {
        private val disconnectedState = MutableStateFlow(false)

        override val events: Flow<ControlEvent> = emptyFlow()
        override val disconnected: StateFlow<Boolean> = disconnectedState.asStateFlow()
        override val outputBacklogOverflows: Flow<TmuxOutputBacklogOverflow> = emptyFlow()

        fun markDisconnected() {
            disconnectedState.value = true
        }

        override suspend fun connect() = Unit

        override suspend fun sendCommand(cmd: String): CommandResponse =
            CommandResponse(number = 0L, output = emptyList(), isError = false)

        override fun outputFor(paneId: String): Flow<ControlEvent.Output> = emptyFlow()

        override fun close() = Unit

        override suspend fun setWindowSizeLatest(sessionId: String): CommandResponse =
            CommandResponse(number = 0L, output = emptyList(), isError = false)

        override suspend fun refreshClientSize(cols: Int, rows: Int): CommandResponse =
            CommandResponse(number = 0L, output = emptyList(), isError = false)

        override suspend fun detachCleanly(timeoutMs: Long) = Unit
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val DEVICE_DIR_NAME: String = "flaky-reconnect-e2e"

        /**
         * Host port the `flaky-agent` compose service binds. Distinct
         * from the deterministic `agents` fixture (2222), the `tmux`
         * fixture (2224), and the bootstrap variants (2230-2235). See
         * `tests/docker/docker-compose.yml`.
         */
        const val FLAKY_PORT: Int = 2226

        /**
         * Ceiling for the auto-reconnect "Reconnecting" progress row to
         * surface after the fixture's ~12s deterministic drop. The CI
         * emulator can lag the `disconnected` observer by several seconds
         * under sibling-test load.
         */
        val RECONNECTING_PROGRESS_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 45_000L else 30_000L

        /**
         * Ceiling for the auto-reconnect loop to recover the session
         * (progress row clears, terminal re-attached) WITHOUT a manual tap.
         * The default backoff schedule is `[0, 1s, 2s, 5s]`, and each
         * attempt re-runs the full SSH handshake + tmux re-attach to the
         * Docker fixture, so CI needs generous head-room.
         */
        val AUTO_RECOVERY_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 30_000L

        /** Ceiling for the non-retryable abort to land on Failed. */
        val NON_RETRYABLE_STATUS_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 20_000L
    }
}
