package com.pocketshell.app.proof

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.projects.FOLDER_LIST_SCREEN_TAG
import com.pocketshell.app.proof.signals.waitForSshPtyReady
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #1845 — **a typed `;` must reach the remote pane.**
 *
 * ## The defect this reproduces
 *
 * PocketShell types into a tmux pane with
 * `tmux send-keys -l -t %N -- '<text>'`. tmux parses its own `argv` for command
 * separators and inspects the LAST character of every element: a trailing `;`
 * is **deleted** (it starts a new command) unless the character before it is a
 * `\`. So `send-keys -l -- 'abc;'` typed `abc`, tmux reported SUCCESS, and one
 * byte of the user's command vanished. `set-buffer -- 'chunk;'` (the composer's
 * atomic paste fill) behaves identically.
 *
 * On-device the position looked random because the per-pane input queue
 * ([com.pocketshell.app.tmux.TmuxPaneInputQueue.takeBatch]) batches keystrokes
 * by ARRIVAL TIMING — the `;` is lost whenever a batch boundary falls right
 * after a typed `;`, i.e. whenever the user types `...;` and pauses.
 *
 * ## Why the oracle is container-side, not the app's render
 *
 * The load-bearing question is "did the byte reach the remote", so the
 * assertion reads the fixture container's OWN view of the pane
 * (`tmux capture-pane -p`) over an **independent SSH session the app does not
 * own**. A client-side render/echo check could not distinguish "never sent"
 * from "sent but not painted".
 *
 * ## The failing state is CONSTRUCTED, not hoped for (#780 model)
 *
 * The command is typed in fragments that deliberately END on `;` with a pause
 * between them, so the `;` really is the last byte of its `send-keys` argument.
 * That is exactly the user gesture ("type `cd foo;` then pause") — no synthetic
 * seam, and no reliance on batching luck.
 *
 * ## Red on base
 *
 * Without the fix, phase 1's typed line arrives as
 * `d=/tmp/...mkdir -p $dcd $d;printf ...` (two `;` gone) and phase 2's
 * `zC3\;` arrives as `zC3;` (the `\` consumed). There is **no**
 * `Assume.assumeFalse(isRunningOnCi())` here: the assertions run on CI.
 */
@RunWith(AndroidJUnit4::class)
class Issue1845TypedSemicolonReachesPaneDockerTest {

    // Issue #788 harness: the compose rule owns MainActivity's launch, and the
    // remote tmux session + DB host row are seeded BEFORE that launch.
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private lateinit var fixtureKey: String
    private lateinit var hostRowTag: String
    private lateinit var marker: String
    private var observer: SshSession? = null
    private val observations = mutableListOf<String>()

    @After
    fun tearDown() {
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        runCatching { observer?.close() }
        observer = null
        writeReport()
        clearLastSessionPrefs()
    }

    @Test
    fun typedSemicolonsReachTheRemotePaneByteExact() { runBlocking {
        val session = openObserver()
        observer = session

        openSeededSession()

        // Phase 1 — the maintainer's reported command shape, typed the way a
        // user types it: fragments that end on `;`, with a pause after each.
        val workDir = "/tmp/i-$marker"
        val command = "d=$workDir;mkdir -p \$d;cd \$d;printf 'S1-$marker\\n';pwd"
        typeFragmentsEndingOnSemicolons(command)

        val typedLine = awaitPaneTextContaining(session, command)
        record("phase1 expected=`$command` received=`${typedLine ?: "<none>"}`")
        assertTrue(
            "the remote pane never received the typed command byte-exact — a `;` was " +
                "deleted by tmux's argv command-separator rule (issue #1845).\n" +
                "  expected: $command\n" +
                "  pane tail: $failureContext",
            typedLine != null,
        )

        // The command must also DO the right thing once submitted: a lost `;`
        // silently changes the program, not just the echo.
        submitEnter()
        val effect = awaitPaneTextContaining(session, "S1-$marker", alsoRequire = workDir)
        record("phase1 effect=`${effect ?: "<none>"}`")
        assertTrue(
            "expected the submitted command to print its marker AND cd into $workDir; " +
                "pane text did not show both (a dropped `;` corrupts the program)",
            effect != null,
        )

        // Phase 2 — class coverage for the separator rule on the keystroke lane:
        // a single trailing `;`, a doubled `;;`, and a payload that itself ends
        // in `\;` (tmux's own escape, which must survive as two bytes).
        val fragments = listOf("zA1;", "zB2;;", "zC3\\;")
        fragments.forEach { fragment ->
            typeFragment(fragment)
            val line = awaitPaneTextContaining(session, fragment)
            record("phase2 fragment=`$fragment` received=`${line ?: "<none>"}`")
            assertTrue(
                "the remote pane never received `$fragment` byte-exact (issue #1845 " +
                    "tmux argv separator rule).\n  pane tail: $failureContext",
                line != null,
            )
        }
    } }

    // ------------------------------------------------------------ typing

    /**
     * Type [command] in fragments split immediately AFTER each `;`, pausing
     * between fragments so each fragment is its own per-pane input batch — i.e.
     * so its trailing `;` really is the last byte of a `send-keys -l` argument.
     * A trailing-`;` fragment is what a user produces by typing `cd foo;` and
     * pausing; this is the reported gesture, not a synthetic seam.
     */
    private fun typeFragmentsEndingOnSemicolons(command: String) {
        val fragments = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in command) {
            current.append(ch)
            if (ch == ';') {
                fragments += current.toString()
                current.clear()
            }
        }
        if (current.isNotEmpty()) fragments += current.toString()
        assertTrue(
            "the reproduction requires at least two fragments ending on ';' — " +
                "got ${fragments.map { it.takeLast(1) }}",
            fragments.count { it.endsWith(';') } >= 2,
        )
        fragments.forEach { typeFragment(it) }
    }

    private fun typeFragment(fragment: String) {
        val committed = terminalInputConnection().commitText(fragment, 1)
        assertTrue("expected the terminal input connection to commit `$fragment`", committed)
        // Comfortably longer than one queue drain + `send-keys` exec round-trip,
        // so this fragment cannot be merged into the next batch.
        SystemClock.sleep(BATCH_SEPARATION_MS)
    }

    private fun submitEnter() {
        val committed = terminalInputConnection().commitText("\n", 1)
        assertTrue("expected the terminal input connection to submit", committed)
    }

    // ---------------------------------------------------- container oracle

    private suspend fun openObserver(): SshSession =
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(fixtureKey),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 20_000,
        ).getOrThrow()

    /**
     * Read the fixture container's own view of the pane over an SSH session the
     * app does not own, and return the last line containing [needle] (and
     * [alsoRequire] somewhere in the capture), or null on timeout.
     *
     * `capture-pane -J` JOINS soft-wrapped rows, so a command longer than the
     * emulator grid is compared as the one logical line the user typed instead
     * of failing on a cosmetic wrap.
     */
    private suspend fun awaitPaneTextContaining(
        session: SshSession,
        needle: String,
        alsoRequire: String? = null,
    ): String? {
        val deadline = SystemClock.elapsedRealtime() + observeTimeoutMs
        var lastCapture = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            val captured = session.exec("tmux capture-pane -p -J -t ${shellQuote(SESSION_NAME)}")
            lastCapture = captured.stdout
            if (needle in lastCapture && (alsoRequire == null || alsoRequire in lastCapture)) {
                return lastCapture.lineSequence().last { it.contains(needle) }
            }
            SystemClock.sleep(250)
        }
        // Timed out: hand back the most informative line we saw for the failure
        // message (never null-vs-found ambiguity — the caller asserts on null).
        failureContext = lastCapture.lines()
            .filter { it.isNotBlank() }
            .takeLast(6)
            .joinToString(" | ")
        return null
    }

    private var failureContext: String = ""

    // --------------------------------------------------------- navigation

    private fun openSeededSession() {
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            compose.onAllNodesWithTag(FOLDER_LIST_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            var attached = false
            compose.activityRule.scenario.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession != null && view.mEmulator != null
            }
            attached
        }
        val ready = waitForSshPtyReady(transcriptProvider = { visibleTerminalText() })
        assertTrue(
            "expected the fixture shell prompt before typing; visible=`" +
                visibleTerminalText().takeLast(300) + "`",
            ready,
        )
    }

    // -------------------------------------------------------------- seeding

    private suspend fun seedBeforeLaunch() {
        fixtureKey = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }
        marker = "m${System.currentTimeMillis().toString(36).takeLast(5)}"
        clearLastSessionPrefs()
        waitForSshFixtureReady(SshKey.Pem(fixtureKey))

        val setup = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(fixtureKey),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 20_000,
        ).getOrThrow()
        try {
            val result = setup.exec(
                "tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true; " +
                    "tmux new-session -d -s ${shellQuote(SESSION_NAME)} " +
                    shellQuote("printf 'READY-$marker\\n'; exec sh"),
            )
            assertTrue(
                "expected the tmux fixture session to be created, stderr='${result.stderr}'",
                result.exitCode == 0,
            )
        } finally {
            runCatching { setup.close() }
        }

        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue1845-${System.currentTimeMillis()}",
                content = fixtureKey,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue1845 $marker",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
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
    }

    // --------------------------------------------------------------- utils

    private fun terminalInputConnection(): InputConnection {
        var connection: InputConnection? = null
        compose.activityRule.scenario.onActivity { activity ->
            val terminalView = activity.window.decorView.findTerminalView()
            requireNotNull(terminalView) { "TerminalView was not found" }
            terminalView.requestFocus()
            connection = terminalView.onCreateInputConnection(EditorInfo())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return requireNotNull(connection) { "TerminalView did not create an InputConnection" }
    }

    private fun visibleTerminalText(): String {
        var text = ""
        compose.activityRule.scenario.onActivity { activity ->
            text = activity.window.decorView.findTerminalView()
                ?.currentSession?.emulator?.screen?.transcriptText.orEmpty()
        }
        return text
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

    private fun record(line: String) {
        observations += line
        println("ISSUE1845 $line")
    }

    private fun writeReport() {
        if (observations.isEmpty()) return
        runCatching {
            val dir = File(
                com.pocketshell.app.test.testArtifactsRoot(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                ),
                "additional_test_output/issue1845",
            )
            check(dir.exists() || dir.mkdirs()) { "could not create ${dir.absolutePath}" }
            val file = File(dir, "typed-semicolon-report.txt")
            file.writeText(observations.joinToString("\n", postfix = "\n"))
            println("ISSUE1845_REPORT ${file.absolutePath}")
        }
    }

    private val observeTimeoutMs: Long
        get() = if (TerminalTestTimeouts.isRunningOnCi()) 90_000L else 30_000L

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val SESSION_NAME: String = "claude-main"
        const val BATCH_SEPARATION_MS: Long = 400L
    }
}
