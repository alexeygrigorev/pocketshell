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
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_PULL_TO_RECONNECT_TAG
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
 * EPIC #792 #833 — the PER-PR deterministic CLEAN sustained-outage reattach
 * resilience journey, on the deterministic `agents:2222` fixture.
 *
 * This is the per-push (D31) sibling of the toxiproxy-faithful
 * `SilentMidSessionDropDetectionE2eTest.silentDropAutoRecoversWithoutSessionSwitchDance`
 * (which runs nightly because it needs the toxiproxy `disableProxyFor` clean
 * FIN/connection-refused outage). Here the clean sustained outage is injected
 * DETERMINISTICALLY through two VM seams on the plain `agents:2222` channel:
 *  - [TmuxSessionViewModel.triggerCleanPassiveDropForTest] fires the CLEAN
 *    passive-disconnect path the EOF oracle drives ([com.pocketshell.core.tmux.TmuxDisconnectReason.ReaderEof]) —
 *    NOT the LivenessProbe / half-open path (that is the #822 Slice D sibling).
 *  - [TmuxSessionViewModel.forceCleanOutageForTest] makes the silent-reattach
 *    grace loop's reconnect primitives fail-fast as if the link were DOWN for the
 *    outage window, then clearing it lets the next re-dial succeed over the real
 *    healthy channel.
 *
 * The bug ([#833]): on a clean sustained outage the old silent-reattach grace
 * loop tried a fresh transport exactly ONCE (a `transportReattachTried` latch),
 * and because a failed transport reconnect nulls `sessionRef`, every later
 * iteration could only call the warm reattach (which can no longer succeed) — so
 * the loop span uselessly and the SAME session never auto-recovered when the link
 * returned (it stayed stuck `Reconnecting`/non-Connected until the full grace
 * elapsed, forcing the switch-session dance). With the fix the loop RE-DIALS a
 * fresh transport on every iteration within the bounded grace window, so the SAME
 * session auto-recovers the moment the link returns — WITHOUT the switch dance.
 *
 * The assertions are USER-VISIBLE (D28(3)): the rendered connection-lost
 * indicator (disconnect band / Reconnect button / pull-to-reconnect) + the
 * projected [TmuxSessionViewModel.ConnectionStatus] that drives them, plus a real
 * send round-trip for recovery — never internal/shadow state. NO `assumeTrue` /
 * `assumeFalse(isRunningOnCi())` on the load-bearing assertions (D31/F3).
 */
@RunWith(AndroidJUnit4::class)
class CleanOutageReattachResilienceE2eTest {

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
        runCatching {
            currentViewModel().forceCleanOutageForTest = false
        }
        clearLastSessionPrefs()
        seededKey?.let { key ->
            runCatching { runBlocking { cleanupRemoteTmuxSession(key) } }
        }
    }

    @Test
    fun cleanSustainedOutageAutoRecoversWithoutSessionSwitchDance() = runBlocking<Unit> {
        val hostRowTag = requireNotNull(seededHostRowTag)
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")

        // Shorten the passive-disconnect grace window so the deterministic re-dial
        // ladder fits the test budget. The grace window must STILL exceed the
        // synthetic outage so the loop is mid-window (re-dialling) when the outage
        // clears — this is the analogue of BackgroundGraceTestOverride, NOT a
        // weakening of the assertion (the loop genuinely re-dials a fresh transport
        // every iteration over the real channel). Per-attempt timeout kept short so
        // each failed re-dial returns fast during the fake outage.
        currentViewModel().setPassiveDisconnectRecoveryForTest(
            graceMs = GRACE_MS,
            silentReattachTimeoutMs = REATTACH_TIMEOUT_MS,
        )

        // Establish the live baseline by streaming a fresh marker into the pane over
        // the remote (`tmux send-keys`) and confirming it arrives via the live `-CC`
        // channel. After this NOTHING is sent (the user is reading / recording a
        // voice note — the #822/#833 scenario).
        val key = requireNotNull(seededKey)
        emitMarkerIntoPane(key, "LIVE-$MARKER")
        waitForVisibleTerminal("pre-drop-live") { it.contains("LIVE-$MARKER") }
        assertTrue(
            "expected Connected before the clean drop, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        // ---- 1) CLEAN SUSTAINED OUTAGE ----
        // Arm the synthetic outage (the link is DOWN — clean FIN/refused) THEN fire
        // the clean passive-disconnect path the EOF oracle drives. The silent-
        // reattach grace loop begins re-dialling a fresh transport; every attempt
        // fails while the outage is armed. Capture the client identity so we can
        // PROVE the recovery rebinds the SAME session onto a NEW (fresh-transport)
        // control client — i.e. it really re-dialled, not just held the dead one.
        val dropStart = SystemClock.elapsedRealtime()
        val vm = currentViewModel()
        val clientBeforeDrop = vm.currentClientIdentityForTest()
        vm.forceCleanOutageForTest = true
        val fired = vm.triggerCleanPassiveDropForTest()
        assertTrue("expected a current client to fire the clean drop against", fired)

        // Keep the link down for a SUSTAINED outage so the loop must re-dial many
        // times (the old one-shot `transportReattachTried` latch wedged here: after
        // a single failed fresh-transport attempt it could only retry the warm
        // reattach, which can never succeed once the failed transport nulled the SSH
        // session — so the session never recovered for the rest of the grace window).
        SystemClock.sleep(SILENT_OUTAGE_MS)

        // ---- 2) AUTO-RECOVERY, NO SWITCH DANCE ----
        // Restore the link: the very next grace-loop re-dial of a fresh transport
        // succeeds over the real healthy channel. The user does NOT switch sessions
        // and does NOT tap anything.
        vm.forceCleanOutageForTest = false
        val recovered = waitForSessionRecovered(WEDGE_RECOVER_WINDOW_MS)
        val recoverMs = SystemClock.elapsedRealtime() - dropStart
        recordTiming("clean_outage_recovered_bool", if (recovered) 1L else 0L)
        recordTiming("clean_outage_recover_elapsed_ms", recoverMs)
        assertTrue(
            "Expected the SAME session to auto-recover to a live, input-accepting state within " +
                "${WEDGE_RECOVER_WINDOW_MS}ms of the link returning — WITHOUT a switch dance " +
                "(status=${currentConnectionStatus()}). On base (#833) the silent-reattach loop " +
                "tries a fresh transport exactly once then spins, so the session stays wedged.",
            recovered,
        )

        // The recovered control client must be a DIFFERENT instance than the one
        // that dropped — proving the grace loop re-dialled a FRESH transport (the
        // resilience fix), not silently held the dead client.
        val clientAfterRecovery = vm.currentClientIdentityForTest()
        recordTiming("clean_outage_client_rebound_bool", if (clientAfterRecovery != clientBeforeDrop) 1L else 0L)
        assertTrue(
            "expected the recovered session to be rebound onto a fresh control client " +
                "(re-dialled transport), beforeDrop=$clientBeforeDrop afterRecovery=$clientAfterRecovery",
            clientAfterRecovery != null && clientAfterRecovery != clientBeforeDrop,
        )

        // A fresh marker emitted into the pane must STREAM back through the SAME
        // recovered `-CC` channel — proving the recovered session is genuinely live
        // and input-accepting (no switch dance was needed).
        emitMarkerIntoPane(key, "AFTER-$MARKER")
        val roundTripped = runCatching {
            waitForVisibleTerminal(
                "post-recovery",
                timeoutMillis = ROUND_TRIP_WINDOW_MS,
            ) { it.contains("AFTER-$MARKER") }
            true
        }.getOrDefault(false)
        recordTiming("clean_outage_round_tripped_bool", if (roundTripped) 1L else 0L)
        assertTrue(
            "Expected a post-recovery send to round-trip through the SAME session (no switch " +
                "dance). status=${currentConnectionStatus()}",
            roundTripped,
        )
        writeTimings()
    }

    // -- user-visible recovery helpers (parity with the Slice D specs) -------------

    private fun waitForSessionRecovered(timeoutMillis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (sessionHealthyConnected()) return true
            SystemClock.sleep(250)
        }
        return sessionHealthyConnected()
    }

    private fun sessionHealthyConnected(): Boolean {
        if (hasTag(TMUX_SESSION_ERROR_TAG) ||
            hasTag(TMUX_SESSION_RECONNECT_TAG) ||
            hasTag(TMUX_PULL_TO_RECONNECT_TAG)
        ) {
            return false
        }
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

    /**
     * Emit a fresh marker line into the seeded pane over the REMOTE
     * (`tmux send-keys`) so it streams back through the app's live `-CC` channel.
     * Used as the live-baseline + post-recovery round-trip signal (avoids the IME
     * input-connection machinery; the property under test is the channel's
     * liveness, not local keystroke routing).
     */
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
                name = "issue833-clean-outage-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue833 CleanOutage",
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
            // Run an interactive shell so `tmux send-keys` of a printf command
            // actually EXECUTES (a bare `sleep` would ignore the sent keystrokes).
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
            description = "issue833 clean-outage tmux seed session",
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

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE833_CLEAN_OUTAGE_TIMINGS ${file.absolutePath}")
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

    private val timings = mutableListOf<String>()

    private fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE833_CLEAN_OUTAGE_TIMING $line")
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
        const val DEVICE_DIR_NAME: String = "issue833-clean-outage-resilience"
        const val SESSION_NAME: String = "issue833-clean-outage-proof"
        const val READY_MARKER: String = "ISSUE833-CLEAN-OUTAGE-READY"
        const val MARKER: String = "issue833cleanoutage"

        /**
         * Shortened passive-disconnect grace window for a deterministic per-PR run.
         * Must EXCEED [SILENT_OUTAGE_MS] so the resilient re-dial loop is still
         * within the grace window (re-dialling a fresh transport every iteration)
         * when the synthetic outage clears. The 60s production default would make
         * the test slow; the loop's escalation logic is identical at any grace size.
         */
        val GRACE_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 40_000L else 25_000L

        /** Per-attempt reattach timeout — short so each failed re-dial returns fast. */
        const val REATTACH_TIMEOUT_MS: Long = 5_000L

        /**
         * Sustained clean outage before the link is restored (clean Wi-Fi blip / NAT
         * rebind). Forces the grace loop to re-dial MANY times — the old one-shot
         * latch wedged here.
         */
        const val SILENT_OUTAGE_MS: Long = 3_000L

        val WEDGE_RECOVER_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 45_000L
        val ROUND_TRIP_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 45_000L else 30_000L

        val HOST_ROW_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
