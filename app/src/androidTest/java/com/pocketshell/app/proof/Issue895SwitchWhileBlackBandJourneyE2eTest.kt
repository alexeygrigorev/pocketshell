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
 * Issue #895 (switch-while-black) — per-push escapable-band JOURNEY on the
 * deterministic `agents:2222` fixture (the #638 mandate: the load-bearing
 * reconnect journeys run in REGULAR per-push CI, not only nightly/release).
 *
 * Reproduces the R1 trigger on the REAL screen: a session is attached LIVE, then
 * the VM is driven into the `Switching` (Attaching) window — exactly the window a
 * same-host fast switch holds — and a transport drop lands mid-switch (the
 * synthetic-injection #780 model, via [TmuxSessionViewModel.forceAttachingStateForTest]
 * + [TmuxSessionViewModel.triggerCleanPassiveDropForTest], which drive the SAME
 * production `handlePassiveClientDisconnect` + controller projection the live UI
 * renders from).
 *
 * Defect: the old `inlineConnectionStatus as? Connected ?: return` swallow gate
 * dropped the Switching-window drop on the floor — the user was left frozen on a
 * black pane with NO escapable affordance ("it froze, had to restart").
 *
 * Fix: the passive handler is status-agnostic and walks the controller into the
 * silent-heal ladder, so an ESCAPABLE band (Reconnecting band / Reconnect
 * affordance / no longer the swallowed Switching) surfaces PROMPTLY and the app
 * stays usable.
 *
 * The load-bearing assertion is USER-VISIBLE (D28(3)): the rendered escapable
 * indicator + the projected [TmuxSessionViewModel.ConnectionStatus] that drives
 * it. NO `assumeTrue` / `assumeFalse(isRunningOnCi())` on the load-bearing
 * assertion (D31/F3) — the synthetic seam makes the state reproducible on the CI
 * swiftshader AVD and HARD-fails otherwise.
 */
@RunWith(AndroidJUnit4::class)
class Issue895SwitchWhileBlackBandJourneyE2eTest {

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
    fun dropDuringSwitchingWindowSurfacesEscapableBand() { runBlocking<Unit> {
        val hostRowTag = requireNotNull(seededHostRowTag)
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")

        // Drive the VM into the Switching (Attaching) window — the window a
        // same-host fast switch holds before the Live flip. inlineConnectionStatus
        // now projects to Switching (the user-visible "switching" state).
        val vm = currentViewModel()
        compose.activityRule.scenario.onActivity {
            vm.forceAttachingStateForTest(DEFAULT_HOST, DEFAULT_PORT, DEFAULT_USER)
        }
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Switching
        }
        assertTrue(
            "precondition: VM is in the Switching window, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Switching,
        )

        // The transport drops mid-switch (the black/wedged-channel EOF case).
        val dropStart = SystemClock.elapsedRealtime()
        var fired = false
        compose.activityRule.scenario.onActivity {
            fired = vm.triggerCleanPassiveDropForTest()
        }
        assertTrue("the synthetic drop must dispatch through the production handler", fired)

        // Load-bearing assertion: an ESCAPABLE band surfaces PROMPTLY — the user is
        // never left stuck on the swallowed Switching state with nothing tappable.
        val escapable = waitForEscapableBand(BAND_WINDOW_MS)
        val elapsed = SystemClock.elapsedRealtime() - dropStart
        recordTiming("switch_drop_escapable_band_ms", if (escapable) elapsed else -1L)
        assertTrue(
            "#895 R1: a drop during the Switching window MUST surface an escapable " +
                "state within ${BAND_WINDOW_MS}ms (a Reconnecting band / Reconnect " +
                "affordance, NOT the swallowed Switching). observed=" +
                "${currentConnectionStatus()}",
            escapable,
        )

        // The session screen must still be up (the app did not freeze / require a
        // restart) — the escapable state is rendered, not a wedged black pane.
        assertTrue(
            "#895: the session screen must remain mounted (no freeze/restart)",
            hasTag(TMUX_SESSION_SCREEN_TAG),
        )
        writeTimings()
    } }

    // -- escapable-band helpers ----------------------------------------------------

    private fun waitForEscapableBand(timeoutMillis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (escapableBandVisible()) return true
            SystemClock.sleep(200)
        }
        return escapableBandVisible()
    }

    private fun escapableBandVisible(): Boolean {
        // The tappable Failed/"Tap to reconnect" + pull-to-reconnect affordances,
        // OR the calm Reconnecting band — any of these is an escapable state that
        // tells the user recovery is happening / available. The one thing that is
        // NOT escapable is staying stuck on the swallowed Switching (or Connected
        // over a dead channel).
        if (hasTag(TMUX_SESSION_ERROR_TAG) ||
            hasTag(TMUX_SESSION_RECONNECT_TAG) ||
            hasTag(TMUX_PULL_TO_RECONNECT_TAG)
        ) {
            return true
        }
        return when (currentConnectionStatus()) {
            is TmuxSessionViewModel.ConnectionStatus.Reconnecting -> true
            is TmuxSessionViewModel.ConnectionStatus.Failed -> true
            else -> false
        }
    }

    private fun hasTag(tag: String): Boolean =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    // -- attach helpers ------------------------------------------------------------

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
                name = "issue895-band-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue895 SwitchWhileBlack",
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
            description = "issue895 switch-while-black tmux seed session",
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
        println("ISSUE895_BAND_TIMINGS ${file.absolutePath}")
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
        println("ISSUE895_BAND_TIMING $line")
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
        const val DEVICE_DIR_NAME: String = "issue895-switch-while-black-band"
        const val SESSION_NAME: String = "issue895-band-proof"
        const val READY_MARKER: String = "ISSUE895-BAND-READY"

        val BAND_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 12_000L
        val HOST_ROW_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
