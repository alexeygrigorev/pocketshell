package com.pocketshell.app.proof

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_RECONNECT_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File

/**
 * Issue #993 — DEVICE-TRUTH journey for the new kebab **"Reconnect"** action, on the
 * deterministic `agents:2222` Docker fixture.
 *
 * ## The maintainer's report (2026-06-26)
 *
 * "Sometimes I accidentally disconnect — I'm already in a session sending a message, the
 * disconnect happens, and there's nothing I can do: the session can't reconnect on its own.
 * So I have to go back, join ANOTHER session (that triggers a reconnect), then come back to
 * this session, and then the queued message sends. I want a **Reconnect button in the kebab
 * menu**." A deliberate HALF-MEASURE escape hatch until auto-reconnect is bulletproof (#928).
 *
 * ## What this journey proves on the REAL path (the acceptance criteria)
 *
 *  - **AC1 — reconnect IN PLACE, no switch dance.** A connected session is dropped (the
 *    `triggerCleanPassiveDropForTest` clean-passive-disconnect seam — the same EOF body a
 *    real reader-EOF drives, no toxiproxy), so a USER-VISIBLE connection-lost band surfaces.
 *    The user opens the kebab and taps the new **Reconnect** item (stable tag
 *    [TMUX_RECONNECT_BUTTON_TAG]); the SAME session recovers to Connected over a FRESH `-CC`
 *    client (a different client identity — proving a real re-dial, not a stale hold) WITHOUT
 *    navigating to another session and back.
 *  - **AC2/AC4 — a post-reconnect send round-trips.** After the manual reconnect, a fresh
 *    marker emitted into the pane STREAMS back through the SAME recovered `-CC` channel —
 *    proving the recovered session is live and input-accepting (the exact precondition the
 *    #900 outbound-queue auto-flush relies on; the flush-on-`sessionLive` wiring itself is
 *    pinned by the JVM `TmuxSessionScreenTest` controller tests).
 *
 * ## Fail-first (G10/D33)
 *
 * WITHOUT the kebab Reconnect item (the `onReconnect` → `viewModel.reconnect()` wiring) there
 * is NO in-session affordance to recover the dropped session — the [TMUX_RECONNECT_BUTTON_TAG]
 * node does not exist, so `tapKebabReconnect()` cannot find/click it and the session stays on
 * the connection-lost band: RED. WITH the fix the tap drives the VM's single
 * [TmuxSessionViewModel.reconnect] / TransportEffects entrypoint, the same session recovers in
 * place, and the post-reconnect send round-trips: GREEN.
 *
 * Uses ONLY the deterministic `agents` fixture and the synthetic clean-drop seam (no
 * toxiproxy, no `Assume.assumeFalse(isRunningOnCi())` on any load-bearing assertion), so it
 * RUNS on the per-PR CI emulator-journey job once wired into `scripts/ci-journey-suite.sh`.
 */
@RunWith(AndroidJUnit4::class)
class ReconnectKebabInPlaceJourneyE2eTest {

    val compose = createAndroidComposeRule<MainActivity>()
    private val grantPermissions = PreGrantPermissionsRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(grantPermissions)
        .around(seedFixtureRule())
        .around(compose)

    private var seededKey: String? = null
    private var seededHostRowTag: String? = null

    private fun seedFixtureRule(): TestRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                runBlocking {
                    val key = readFixtureKey()
                    seededKey = key
                    waitForSshFixtureReady(SshKey.Pem(key))
                    seedTmuxSession(key)
                    seededHostRowTag = seedDockerHost(key)
                }
                base.evaluate()
            }
        }
    }

    @Before
    fun setUp() {
        clearLastSessionPrefs()
    }

    @After
    fun tearDown() {
        runCatching {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
        clearLastSessionPrefs()
        seededKey?.let { key ->
            runCatching { runBlocking { cleanupRemoteTmuxSession(key) } }
        }
    }

    @Test
    fun kebabReconnectRecoversDroppedSessionInPlaceThenSendRoundTrips() { runBlocking<Unit> {
        val hostRowTag = requireNotNull(seededHostRowTag)
        val key = requireNotNull(seededKey)
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")

        // Live baseline: a fresh marker streams back through the live `-CC` channel.
        emitMarkerIntoPane(key, "LIVE-$MARKER")
        waitForVisibleTerminal("pre-drop-live") { it.contains("LIVE-$MARKER") }
        assertTrue(
            "expected Connected before the drop, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        val clientBeforeDrop = currentViewModel().currentClientIdentityForTest()

        // ---- DROP: the maintainer's "I'm in a session and it disconnects" ----
        // Fire the CLEAN passive-disconnect path directly (the same body a real reader EOF
        // drives). The session can no longer round-trip and a USER-VISIBLE connection-lost
        // band surfaces — the stuck state the maintainer has no in-session escape from.
        val dropped = currentViewModel().triggerCleanPassiveDropForTest()
        assertTrue("expected the clean-drop seam to fire on a live client", dropped)
        val bandShown = waitForConnectionLostIndicator(DROP_DETECT_WINDOW_MS)
        assertTrue(
            "expected a USER-VISIBLE connection-lost band after the drop " +
                "(status=${currentConnectionStatus()})",
            bandShown,
        )

        // ---- TAP KEBAB → RECONNECT (the new escape hatch) ----
        // The user opens the kebab and taps the new "Reconnect" item. This drives the VM's
        // single reconnect entrypoint to re-dial THIS session in place — NO navigation to
        // another session and back.
        tapKebabReconnect()

        val recovered = waitForSessionRecovered(RECOVER_WINDOW_MS)
        assertTrue(
            "expected the SAME session to recover to Connected IN PLACE after tapping the " +
                "kebab Reconnect — no switch dance (status=${currentConnectionStatus()}).",
            recovered,
        )
        // The session screen is still up (recovered in place, not torn down / navigated away).
        assertTrue(
            "tmux session screen must still be up after the in-place reconnect",
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
        // A FRESH `-CC` client proves a real re-dial happened (not a stale hold of the
        // dead client).
        val clientAfter = currentViewModel().currentClientIdentityForTest()
        assertNotEquals(
            "the manual reconnect must re-dial a FRESH `-CC` client (different identity)",
            clientBeforeDrop,
            clientAfter,
        )

        // ---- POST-RECONNECT SEND ROUND-TRIP (AC2/AC4) ----
        // A fresh marker must STREAM back through the SAME recovered channel — the recovered
        // session is live + input-accepting, the precondition the #900 queue auto-flush needs.
        emitMarkerIntoPane(key, "AFTER-$MARKER")
        val roundTripped = runCatching {
            waitForVisibleTerminal(
                "post-reconnect",
                timeoutMillis = ROUND_TRIP_WINDOW_MS,
            ) { it.contains("AFTER-$MARKER") }
            true
        }.getOrDefault(false)
        assertTrue(
            "expected a post-reconnect send to round-trip through the SAME session " +
                "(no switch dance). status=${currentConnectionStatus()}",
            roundTripped,
        )

        writeSummary()
    } }

    // -- kebab + indicator helpers -------------------------------------------------

    private fun tapKebabReconnect() {
        // Open the overflow kebab in the session header.
        compose.onNodeWithContentDescription("More session actions", useUnmergedTree = true)
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(TMUX_RECONNECT_BUTTON_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // AC1: the Reconnect item is present + reachable, then tap it.
        compose.onNodeWithTag(TMUX_RECONNECT_BUTTON_TAG, useUnmergedTree = true)
            .assertExists()
            .performClick()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun waitForConnectionLostIndicator(timeoutMillis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (connectionLostIndicatorVisible()) return true
            SystemClock.sleep(200)
        }
        return connectionLostIndicatorVisible()
    }

    private fun connectionLostIndicatorVisible(): Boolean {
        if (hasTag(TMUX_SESSION_ERROR_TAG) || hasTag(TMUX_SESSION_RECONNECT_TAG)) return true
        return when (currentConnectionStatus()) {
            is TmuxSessionViewModel.ConnectionStatus.Connected -> false
            is TmuxSessionViewModel.ConnectionStatus.Idle -> false
            else -> true
        }
    }

    private fun waitForSessionRecovered(timeoutMillis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (sessionHealthyConnected()) return true
            SystemClock.sleep(250)
        }
        return sessionHealthyConnected()
    }

    private fun sessionHealthyConnected(): Boolean {
        if (hasTag(TMUX_SESSION_ERROR_TAG) || hasTag(TMUX_SESSION_RECONNECT_TAG)) return false
        return currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected
    }

    private fun hasTag(tag: String): Boolean =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    // -- attach + IO helpers -------------------------------------------------------

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
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec(
                    "tmux send-keys -t ${shellQuote(SESSION_NAME)} " +
                        shellQuote("printf '$marker\\n'") + " Enter",
                )
            }
        }.getOrThrow()
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

    private fun currentViewModel(): TmuxSessionViewModel {
        var vm: TmuxSessionViewModel? = null
        compose.activityRule.scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
        }
        return requireNotNull(vm) { "TmuxSessionViewModel not available" }
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

    // -- seeding / cleanup ---------------------------------------------------------

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
                name = "issue993-reconnect-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue993 Reconnect Kebab",
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

    private suspend fun seedTmuxSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            // Interactive shell so a `tmux send-keys` printf actually EXECUTES.
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
            description = "issue993 reconnect-kebab tmux seed session",
        )
        assertTrue(
            "expected tmux seeding to succeed; exit=${result.exitCode} stderr='${result.stderr}'",
            result.exitCode == 0,
        )
    }

    private suspend fun cleanupRemoteTmuxSession(key: String) {
        runCatching {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use {
                    it.exec("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
                }
            }
        }
    }

    private fun writeSummary(): File {
        val file = artifactFile("issue993-reconnect-kebab-summary.txt")
        file.writeText(
            buildString {
                appendLine("test=ReconnectKebabInPlaceJourneyE2eTest")
                appendLine("issue=993")
                appendLine("fixture=tests/docker agents ($DEFAULT_HOST:$DEFAULT_PORT)")
                appendLine("running_on_ci=${TerminalTestTimeouts.isRunningOnCi()}")
                appendLine("session=$SESSION_NAME")
                appendLine(
                    "scenario=attach a live session, drop it via the clean-passive seam, then " +
                        "open the kebab and tap the new Reconnect item",
                )
                appendLine(
                    "expectation=the SAME session recovers to Connected in place (fresh `-CC` " +
                        "client, no switch dance) and a post-reconnect send round-trips",
                )
            },
        )
        println("ISSUE993_SUMMARY ${file.absolutePath}")
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
        const val DEVICE_DIR_NAME: String = "issue993-reconnect-kebab"
        const val SESSION_NAME: String = "issue993-reconnect-proof"
        const val READY_MARKER: String = "ISSUE993-READY"
        const val MARKER: String = "issue993reconnect"

        val DROP_DETECT_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 12_000L
        val RECOVER_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 45_000L
        val ROUND_TRIP_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 45_000L else 30_000L

        val HOST_ROW_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
