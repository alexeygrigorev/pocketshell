package com.pocketshell.app.proof

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #470 (3rd attempt — FALLBACK path). The shared session-PICKER
 * enumeration (`FolderListGateway.listSessionsWithFolder` over the warm SSH
 * lease) is wedged on the AVD: every test that taps a host row and waits on
 * [com.pocketshell.app.proof.signals.waitForSessionInPicker] stalls because the
 * `tmux list-sessions` enumeration never completes (no `PsFolderProbe`, no
 * second SSH socket, the picker stays in `Loading` until the bound), so the
 * journey body's REAL terminal assertions are never reached. That picker stall
 * is a PRODUCTION-side enumeration defect (tracked for the terminal cluster /
 * #661/#692), NOT a harness-foregrounding bug — the activity reaches RESUMED and
 * the lifecycle logs `processForeground=true`.
 *
 * To still give the terminal stabilization cluster authoritative emulator proof
 * of the attach / switch / never-black path WITHOUT depending on the flaky
 * picker UI, this test drives the session attach PROGRAMMATICALLY via the
 * production deep-link intent ([MainActivity.EXTRA_OPEN_SESSION_*], the same
 * route the share-into-session flow uses). The intent seeds
 * [com.pocketshell.app.nav.AppDestination.TmuxSession] directly, so
 * `TmuxSessionScreen` auto-connects via the real `tmux -CC` attach on mount —
 * bypassing ONLY the broken picker enumeration, exercising the same terminal
 * attach the user hits AFTER the picker.
 *
 * It seeds two live sessions (A, B) on the deterministic Docker `agents`
 * fixture and asserts, for each programmatic deep-link attach:
 *   1. The terminal screen mounts and the [TerminalView] attaches (mEmulator +
 *      currentSession non-null) — NOT a black/blank pane.
 *   2. The attached session's own seed marker is visible in the live transcript
 *      (the correct, non-stale session attached and the pane is re-seeded).
 *   3. No Disconnected band ([TMUX_SESSION_ERROR_TAG]) / EOF text appears, and
 *      the OTHER session's marker does not bleed in.
 *
 * The "switch" is exercised by tearing down the A scenario and launching a fresh
 * deep-link to B: B's marker must be shown and A's must NOT appear in B's live
 * transcript. This is a picker-free analogue of the A->B switch the journey test
 * cannot currently reach.
 */
@RunWith(AndroidJUnit4::class)
class DeepLinkSessionSwitchE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus.
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val timings = mutableListOf<String>()

    @After
    fun teardown() {
        launchedActivity?.close()
        launchedActivity = null
        runBlocking { runCatching { cleanupSeededSessions(readFixtureKey()) } }
    }

    @Test
    fun deepLinkAttachShowsCorrectSessionContentWithoutPickerAndSwitchesCleanly() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))
        seedTmuxSessions(key)

        val seeded = seedDockerHost(key, "Issue470 DeepLink")

        // ---- (1) Programmatic deep-link attach to A (no picker). ----
        attachViaDeepLinkAndAssert(
            seeded = seeded,
            sessionName = SESSION_A,
            expectedMarker = SESSION_A_MARKER,
            otherMarker = SESSION_B_MARKER,
            label = "attach-a",
        )

        // ---- (2) "Switch" to B by tearing down A and deep-linking to B. ----
        // Picker-free analogue of the A->B switch: B's content must be shown,
        // A's marker must NOT bleed into B's live transcript.
        attachViaDeepLinkAndAssert(
            seeded = seeded,
            sessionName = SESSION_B,
            expectedMarker = SESSION_B_MARKER,
            otherMarker = SESSION_A_MARKER,
            label = "switch-b",
        )

        writeTimings()
        Unit
    }

    private fun attachViaDeepLinkAndAssert(
        seeded: SeededHost,
        sessionName: String,
        expectedMarker: String,
        otherMarker: String,
        label: String,
    ) {
        launchedActivity?.close()
        launchedActivity = null

        val intent = Intent(
            InstrumentationRegistry.getInstrumentation().targetContext,
            MainActivity::class.java,
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(MainActivity.EXTRA_OPEN_SESSION_HOST_ID, seeded.hostId)
            putExtra(MainActivity.EXTRA_OPEN_SESSION_HOST_NAME, seeded.hostName)
            putExtra(MainActivity.EXTRA_OPEN_SESSION_HOSTNAME, DEFAULT_HOST)
            putExtra(MainActivity.EXTRA_OPEN_SESSION_PORT, DEFAULT_PORT)
            putExtra(MainActivity.EXTRA_OPEN_SESSION_USERNAME, DEFAULT_USER)
            putExtra(MainActivity.EXTRA_OPEN_SESSION_KEY_PATH, seeded.keyPath)
            putExtra(MainActivity.EXTRA_OPEN_SESSION_NAME, sessionName)
        }

        val attachAt = SystemClock.elapsedRealtime()
        launchedActivity = ActivityScenario.launch(intent)

        // The deep-link lands DIRECTLY on the TmuxSession screen (no picker).
        compose.waitUntil(timeoutMillis = SCREEN_MOUNT_TIMEOUT_MS) {
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()

        // (1) The TerminalView attaches — a real emulator + session, not a
        // black/blank pane.
        waitForTerminalViewAttached()

        // (2) The attached session's seed marker is visible in the live
        // transcript (correct, non-stale, re-seeded pane).
        waitForTerminalContains(expectedMarker, label)
        recordTiming("${label}_attach_to_content_ms", SystemClock.elapsedRealtime() - attachAt)
        captureViewport("issue470-$label-$sessionName")

        val visible = visibleTerminalText()
        assertTrue("$label: pane must be re-seeded (non-blank)", visible.isNotBlank())

        // The OTHER session's marker must not be present — we attached to the
        // requested session, not a stale/previous one.
        assertFalse(
            "$label: attaching '$sessionName' must not show the other session's " +
                "marker ('$otherMarker') — that would be a wrong/stale attach",
            visible.contains(otherMarker),
        )

        // (3) No Disconnected band / EOF text.
        val bandCount = compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size
        assertTrue("$label: expected NO Disconnected band, found $bandCount", bandCount == 0)
        BROKEN_TRANSPORT_SIGNALS.forEach { signal ->
            assertFalse(
                "$label: expected NO '$signal' EOF text in transcript:\n$visible",
                visible.contains(signal, ignoreCase = true),
            )
        }
        Log.i(LOG_TAG, "deep-link $label attach to $sessionName OK")
    }

    // ---------------------------------------------------------------- Helpers

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedDockerHost(key: String, hostName: String): SeededHost {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue470-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = hostName,
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            SeededHost(
                hostId = hostId,
                hostName = hostName,
                keyPath = storedKey.privateKeyPath,
            )
        } finally {
            db.close()
        }
    }

    private suspend fun seedTmuxSessions(key: String) {
        val script = buildString {
            appendLine("set -eu")
            listOf(SESSION_A, SESSION_B).forEach { name ->
                appendLine("tmux kill-session -t ${shellQuote(name)} 2>/dev/null || true")
            }
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_A)} " +
                    shellQuote("printf '$SESSION_A_MARKER\\n'; exec sh"),
            )
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_B)} " +
                    shellQuote("printf '$SESSION_B_MARKER\\n'; exec sh"),
            )
            appendLine("tmux list-sessions")
        }
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(script) } }
        val exec = result.getOrNull()
        assertTrue(
            "expected tmux session seeding to succeed, got exception=${result.exceptionOrNull()} " +
                "stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded sessions: ${exec?.stdout?.trim()}")
    }

    private suspend fun cleanupSeededSessions(key: String) {
        runCatching {
            withTimeout(20_000) {
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
                            listOf(SESSION_A, SESSION_B).joinToString(separator = "; ") { name ->
                                "tmux kill-session -t ${shellQuote(name)} 2>/dev/null || true"
                            },
                        )
                    }
                }
            }
        }
    }

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = TERMINAL_ATTACH_TIMEOUT_MS) {
            var attached = false
            launchedActivity?.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession != null && view.mEmulator != null
            }
            attached
        }
    }

    private fun waitForTerminalContains(expected: String, label: String) {
        var last = ""
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
                last = visibleTerminalText()
                TerminalTextMatcher.containsWrapTolerant(
                    last,
                    expected,
                    terminalCols = terminalColumns(),
                )
            }
            true
        }.getOrDefault(false)
        if (!satisfied) {
            artifactFile("failure-$label-visible-terminal.txt").writeText(last)
        }
        assertTrue(
            "expected visible terminal for $label to contain '$expected'; got:\n$last",
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

    private fun terminalColumns(): Int {
        var cols = 80
        launchedActivity?.onActivity { activity ->
            activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.let { cols = it.mColumns }
        }
        return cols
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
        bitmap?.let {
            val file = artifactFile("$name-viewport.png")
            FileOutputStream(file).use { out ->
                check(it.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    "failed to write bitmap to ${file.absolutePath}"
                }
            }
            println("ISSUE470_VIEWPORT ${file.absolutePath}")
            it.recycle()
        }
        artifactFile("$name-visible-terminal.txt").writeText(visibleTerminalText())
    }

    private fun writeTimings() {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE470_TIMINGS ${file.absolutePath}")
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

    private fun recordTiming(name: String, value: Long) {
        timings += "$name=$value"
        println("ISSUE470_TIMING $name=$value")
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

    private data class SeededHost(
        val hostId: Long,
        val hostName: String,
        val keyPath: String,
    )

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue470DeepLink"
        const val DEVICE_DIR_NAME: String = "issue470-deeplink-switch"

        const val SESSION_A: String = "issue470-deeplink-a"
        const val SESSION_B: String = "issue470-deeplink-b"
        const val SESSION_A_MARKER: String = "DLA-READY-470"
        const val SESSION_B_MARKER: String = "DLB-READY-470"

        const val SCREEN_MOUNT_TIMEOUT_MS: Long = 20_000L
        const val TERMINAL_ATTACH_TIMEOUT_MS: Long = 30_000L

        val BROKEN_TRANSPORT_SIGNALS: List<String> = listOf(
            "Broken transport",
            "encountered EOF",
            "EOF'ed",
            "Getting data on EOF",
        )
    }
}
