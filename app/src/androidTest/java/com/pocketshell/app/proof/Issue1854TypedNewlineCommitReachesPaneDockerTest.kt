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
 * Issue #1854 — **a typed command submitted with a newline must reach the pane
 * whole, and must actually run.**
 *
 * ## The reported symptom
 *
 * `ColdInstallE2eTest` typed `printf 'cold-install-pass\n'` + a submit newline
 * through the live `TerminalView` `InputConnection` and the pane answered
 * `sh: tf: not found` — the first FOUR characters of `printf` were gone. That is
 * a LEADING, MULTI-character loss, structurally unlike #1845's trailing single
 * `;`, and #1845's fix provably cannot explain it (no `;` in the payload).
 *
 * ## The mechanism (why 4 characters, and why "leading")
 *
 * Two defects compose, both introduced by `d460d61b` ("Route multiline IME
 * commits as bracketed paste", #529, 2026-06-07):
 *
 *  1. **A one-line command plus its submit newline is misclassified as a
 *     multi-line PASTE.** `TerminalView.isMultiLinePasteText` asked only
 *     "does the commit contain an LF", and an AOSP-family soft keyboard commits
 *     the Enter key as a literal `\n` INSIDE the committed text (see the comment
 *     in `TerminalView.sendTextToTerminal`, which converts exactly that `\n` to
 *     the `\r` a terminal expects). So pressing Enter turned the whole command
 *     into a bracketed paste instead of "type the text, then submit".
 *  2. **The block was then framed TWICE.** `TerminalView` wraps it in
 *     `\e[200~`/`\e[201~`, and `TmuxSessionViewModel.sendInputBytesToPane` sees
 *     the LF and calls `sendBracketedPaste`, which frames it AGAIN.
 *
 * The pane's program then meets `\e[200~\e[200~printf …`. busybox `ash` (the
 * `agents` fixture's `/bin/sh`, and the shell `ColdInstallE2eTest` attaches to)
 * buffers an unrecognised escape sequence in a fixed **16-byte** keycode buffer
 * and DISCARDS the whole buffer on no match. `\e[200~` + `\e[200~` + `prin` is
 * exactly 16 bytes — so `printf` arrives as `tf`. Verified directly against the
 * fixture's own tmux + busybox: the double-framed block yields
 * `tf 'cold-install-pass\n'` / `sh: tf: not found`, 5/5.
 *
 * It is not busybox-specific. Against `bash` the duplicate markers leak into the
 * command line as literal `^[[200~` text, and — because a newline INSIDE a
 * bracketed paste is content, not a submit — the command is left typed but never
 * run. Both are silent: tmux reports success.
 *
 * ## Why `ColdInstallE2eTest` passing is not evidence of absence
 *
 * Its assertion is "`cold-install-pass` appears in the visible terminal text",
 * which the ECHO of `tf 'cold-install-pass\n'` satisfies. It only goes red when
 * the 16-byte swallow happens to eat into the marker. Three consecutive green
 * runs on unmutated `main` were recorded before this test existed; the corruption
 * was present in all of them. This test asserts the pane's own view of what it
 * RECEIVED and RAN, over an SSH session the app does not own.
 *
 * ## Coverage
 *
 * - **Phase 1** — the reported payload in the reported shell (busybox `sh`):
 *   one `commitText` of `printf '<marker>\n'` + submit newline. The pane must
 *   print the marker on its own line (the command RAN) and must never show
 *   `not found`.
 * - **Phase 2** — the sibling class, on `bash`: a genuinely multi-line commit
 *   (a real paste, no trailing submit) must arrive with EXACTLY one pair of
 *   paste markers, i.e. no literal `[200~` may appear in the pane text.
 */
// CI_JOURNEY_SUITE_JUSTIFIED: the implementer brief for #1854 explicitly ruled
// scripts/ci-journey-suite.sh out of scope for this change — #1846 is approved
// and awaiting integration and #1851 is in flight, both editing that file's test
// array, and a third concurrent edit would clobber one of them on merge. The
// FQCN to add on integration is
// `com.pocketshell.app.proof.Issue1854TypedNewlineCommitReachesPaneDockerTest`
// (default `agents:2222` fixture, no new Docker service). Until then the gated
// per-push coverage for this defect is
// `com.pocketshell.core.terminal.input.BracketedPasteTest`,
// `TmuxSessionViewModelInputTest.alreadyFramedPasteBlockIsDeliveredWithoutASecondFrame`
// and `Issue1854PasteFramingSourceGuardTest`, all in the required Unit job.
@RunWith(AndroidJUnit4::class)
class Issue1854TypedNewlineCommitReachesPaneDockerTest {

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
    private var failureContext: String = ""

    @After
    fun tearDown() {
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        runCatching { observer?.close() }
        observer = null
        writeReport()
        clearLastSessionPrefs()
    }

    @Test
    fun typedCommandSubmittedWithNewlineRunsOnTheRemotePane() { runBlocking {
        val session = openObserver()
        observer = session

        openSeededSession()

        // ------------------------------------------------------------------
        // Phase 1 — the exact ColdInstallE2eTest gesture, in the exact shell
        // it reported against: ONE commitText carrying the command AND its
        // submit newline.
        // ------------------------------------------------------------------
        val phase1Marker = "P1-$marker"
        commit("printf '$phase1Marker\\n'\n")

        val ran = awaitPaneLine(session) { line -> line.trim() == phase1Marker }
        record("phase1 marker=`$phase1Marker` ranLine=`${ran ?: "<none>"}`")
        assertTrue(
            "the typed command never RAN on the remote pane — its leading bytes were " +
                "swallowed (issue #1854: a one-line command plus its submit newline was " +
                "sent as a DOUBLE-framed bracketed paste).\n" +
                "  expected a pane line equal to: $phase1Marker\n" +
                "  pane tail: $failureContext",
            ran != null,
        )
        // The ECHO must be byte-exact too: under the defect the pane echoed
        // `tf '<marker>\\n'` — the command's first FOUR characters gone. Scoped to
        // THIS run's marker so a sibling lane sharing the fixture cannot make it
        // pass or fail.
        val echo = awaitPaneLine(session) { line -> line.contains("$phase1Marker\\n'") }
        record("phase1 echo=`${echo ?: "<none>"}`")
        assertTrue(
            "the pane never echoed the typed command; pane tail: $failureContext",
            echo != null,
        )
        val echoLine = requireNotNull(echo)
        assertTrue(
            "the typed command reached the pane with its LEADING characters missing " +
                "(issue #1854).\n  expected the line to end with the typed command" +
                "\n  echoed: $echoLine",
            echoLine.trimEnd().endsWith("printf '$phase1Marker\\n'"),
        )
        assertTrue(
            "a raw bracketed-paste marker leaked into the typed command line — it was " +
                "sent as a paste instead of typed+submitted (issue #1854).\n  echoed: " +
                echoLine,
            "[200~" !in echoLine && "[201~" !in echoLine,
        )

        // ------------------------------------------------------------------
        // Phase 2 — class coverage. Swap the pane's program to `bash`, which
        // DOES understand bracketed paste, and commit a genuinely multi-line
        // block (a real paste — no trailing submit). Exactly one pair of paste
        // markers must be applied, so no marker may survive as literal text.
        // ------------------------------------------------------------------
        commit("exec bash --norc --noprofile\n")
        val bashMarker = "BASH-$marker"
        commit("printf '$bashMarker\\n'\n")
        val bashReady = awaitPaneLine(session) { line -> line.trim() == bashMarker }
        record("phase2 bashReady=`${bashReady ?: "<none>"}`")
        assertTrue(
            "expected the pane's program to be bash before the multi-line paste phase; " +
                "pane tail: $failureContext",
            bashReady != null,
        )

        val p2a = "P2A-$marker"
        val p2b = "P2B-$marker"
        commit("$p2a\n$p2b")

        val pasted = awaitPaneLine(session) { line -> line.contains(p2b) }
        val pastedLines = capturePane(session).lines()
            .filter { it.contains(p2a) || it.contains(p2b) }
        record("phase2 pastedLine=`${pasted ?: "<none>"}` lines=$pastedLines")
        assertTrue(
            "the multi-line paste never reached the pane (issue #1854 phase 2).\n" +
                "  pane tail: $failureContext",
            pasted != null,
        )
        assertTrue(
            "the first line of the multi-line paste was swallowed (issue #1854).\n" +
                "  lines: $pastedLines",
            pastedLines.any { it.contains(p2a) },
        )
        assertTrue(
            "the multi-line paste was framed TWICE — the inner paste markers landed " +
                "in the pane as literal content (issue #1854).\n  lines: $pastedLines",
            pastedLines.none { it.contains("[200~") || it.contains("[201~") },
        )
    } }

    // ------------------------------------------------------------ typing

    private fun commit(text: String) {
        val committed = terminalInputConnection().commitText(text, 1)
        assertTrue("expected the terminal input connection to commit `$text`", committed)
        SystemClock.sleep(COMMIT_SETTLE_MS)
    }

    // ---------------------------------------------------- container oracle

    private suspend fun openObserver(): SshSession =
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(fixtureKey),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 20_000,
        ).getOrThrow()

    private suspend fun capturePane(session: SshSession): String =
        session.exec("tmux capture-pane -p -J -t ${shellQuote(SESSION_NAME)}").stdout

    /**
     * Poll the fixture container's OWN view of the pane over an SSH session the
     * app does not own, and return the last line satisfying [predicate], or null
     * on timeout. `capture-pane -J` joins soft-wrapped rows so the comparison is
     * against the logical line, not the emulator grid.
     */
    private suspend fun awaitPaneLine(
        session: SshSession,
        predicate: (String) -> Boolean,
    ): String? {
        val deadline = SystemClock.elapsedRealtime() + observeTimeoutMs
        var lastCapture = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            lastCapture = capturePane(session)
            val hit = lastCapture.lineSequence().lastOrNull(predicate)
            if (hit != null) return hit
            SystemClock.sleep(250)
        }
        failureContext = lastCapture.lines()
            .filter { it.isNotBlank() }
            .takeLast(6)
            .joinToString(" | ")
        return null
    }

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
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 20_000,
        ).getOrThrow()
        try {
            // `exec sh` is busybox ash in the `agents` fixture — the SAME shell
            // ColdInstallE2eTest attaches to, and the one whose 16-byte keycode
            // buffer produced the reported `printf` -> `tf`.
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
                name = "issue1854-${System.currentTimeMillis()}",
                content = fixtureKey,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue1854 $marker",
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
        println("ISSUE1854 $line")
    }

    private fun writeReport() {
        if (observations.isEmpty()) return
        runCatching {
            val dir = File(
                com.pocketshell.app.test.testArtifactsRoot(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                ),
                "additional_test_output/issue1854",
            )
            check(dir.exists() || dir.mkdirs()) { "could not create ${dir.absolutePath}" }
            val file = File(dir, "typed-newline-commit-report.txt")
            file.writeText(observations.joinToString("\n", postfix = "\n"))
            println("ISSUE1854_REPORT ${file.absolutePath}")
        }
    }

    private val observeTimeoutMs: Long
        get() = if (TerminalTestTimeouts.isRunningOnCi()) 90_000L else 30_000L

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val SESSION_NAME: String = "claude-main"
        const val COMMIT_SETTLE_MS: Long = 600L
    }
}
