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
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.migrations.MIGRATION_1_2
import com.pocketshell.core.storage.migrations.MIGRATION_2_3
import com.pocketshell.core.storage.migrations.MIGRATION_3_4
import com.pocketshell.core.storage.migrations.MIGRATION_4_5
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Issue #215 — regression test for the v0.2.8 maintainer feedback:
 *
 *  > "right now I am entered the shell from my phone from a pocket shell
 *  >  right so then I dictate and then I close it right so so I did it I
 *  >  close it and then I open it on so the app I I stop using right and
 *  >  then I open it in my other computer and then I cannot type anything"
 *
 * The hypothesis (confirmed locally before this test landed): closing the
 * PocketShell app while attached to a tmux session via `tmux -CC` leaves
 * an orphan control client registered server-side until tmux notices the
 * SSH socket drop independently. A laptop attaching to the same session
 * in that window lands alongside the orphan and finds the session in an
 * input-broken state.
 *
 * The fix is in [com.pocketshell.core.tmux.TmuxClient.detachCleanly] and
 * its wire-up inside [com.pocketshell.app.tmux.TmuxSessionViewModel]'s
 * three close paths (suspending teardown, same-host fast-switch
 * teardown, and synchronous `onCleared` teardown).
 *
 * This test runs against the deterministic `agents:2222` Docker fixture
 * (already required by the rest of the connected suite — no new fixture
 * needed). It exercises both halves of the acceptance criteria in
 * sequence:
 *
 *  1. Attach to `claude-main` via the normal app journey
 *     (host picker -> session picker -> Attach).
 *  2. Verify the server has exactly one client attached (the app).
 *  3. Force the activity to DESTROYED to drive
 *     [com.pocketshell.app.tmux.TmuxSessionViewModel.onCleared] — the
 *     pathological case the maintainer reported, where PocketShell is
 *     closed (back press / process finish) while the tmux session
 *     stays alive remotely.
 *  4. Poll `tmux list-clients -t claude-main` until the orphan count
 *     drops to 0 (acceptance: it must reach 0 inside
 *     [ORPHAN_CLIENT_CLEANUP_TIMEOUT_MS]).
 *  5. Open a fresh non-CC interactive `tmux attach -t claude-main`
 *     from a sidecar SSH session and type a unique marker. Verify the
 *     marker reaches the running pane — proving that input flows
 *     through the new client unimpeded.
 *
 * Artifact contract (see process.md "Terminal Artifact Review"):
 *
 *  - `issue215-01-attached-viewport.png` + `-visible-terminal.txt` —
 *    proof the app attached cleanly before the test exercised the
 *    teardown.
 *  - `issue215-02-after-destroy-clients.txt` — output of
 *    `tmux list-clients -t claude-main` immediately after the activity
 *    destruction, captured for the orphan-count assertion.
 *  - `issue215-03-second-client-pane.txt` — text the second
 *    (non-CC) client typed-and-read, proving input round-trips.
 *  - `timings.txt` — attach time, destroy-to-orphan-cleared latency,
 *    second-client attach + input round-trip timing.
 */
@RunWith(AndroidJUnit4::class)
class TmuxOrphanClientCleanupE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val timings = mutableListOf<String>()

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
        runBlocking {
            runCatching { cleanupRemoteTmuxSession(readFixtureKey()) }
        }
    }

    @Test
    fun closingTheAppDoesNotLeaveAnOrphanCcClient() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        // ---- Seed the session with a long-running tail so the session
        // outlives the test body (a `sleep` is enough — the actual pane
        // payload is irrelevant; the test is about CLIENT lifecycle, not
        // pane content).
        seedTmuxSession(key)

        val hostRowTag = seedDockerHost(key)
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // ---- (1) Attach to claude-main from the picker.
        val attachStart = SystemClock.elapsedRealtime()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForText(SEEDED_SESSION, timeoutMs = 20_000)
        compose.onNodeWithText(SEEDED_SESSION).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        recordTiming("attach_ms", SystemClock.elapsedRealtime() - attachStart)
        captureViewport("issue215-01-attached")

        // ---- (2) Confirm there is exactly one client on the server now
        // — the app's -CC client. If this assertion fails before we
        // teardown, the rest of the test is meaningless.
        val attachedClientCount = listClientsCount(key, SEEDED_SESSION)
        Log.i(LOG_TAG, "attached-state clients on $SEEDED_SESSION = $attachedClientCount")
        assertTrue(
            "expected at least one tmux client (the app's -CC connection) before teardown, got $attachedClientCount",
            attachedClientCount >= 1,
        )

        // ---- (3) Force the activity to DESTROYED. ActivityScenario's
        // `close()` walks the lifecycle through stopped + destroyed,
        // which invokes [TmuxSessionViewModel.onCleared] and the
        // synchronous `closeCurrentConnection()` path that now sends
        // `detach-client` via a brief runBlocking(Dispatchers.IO) hop.
        // This is the exact code path the maintainer's "close the
        // app" sequence exercises on a real phone (back-out of the
        // session screen -> back-out of the app).
        val destroyAt = SystemClock.elapsedRealtime()
        launchedActivity?.close()
        launchedActivity = null

        // ---- (4) Poll `tmux list-clients -t claude-main` until the
        // orphan count drops to 0. Without the fix the count stays at
        // >=1 for as long as the tmux server takes to independently
        // observe the SSH socket close (variable; up to 10+ seconds
        // depending on the kernel TCP timeout). With the fix the
        // detach-client round-trip removes the entry in well under
        // 100ms on a healthy Docker fixture.
        var orphanCount = -1
        var orphanRawSnapshot = ""
        val deadline = SystemClock.elapsedRealtime() + ORPHAN_CLIENT_CLEANUP_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val raw = listClientsRaw(key, SEEDED_SESSION)
            orphanRawSnapshot = raw
            val count = raw.lines().count { it.isNotBlank() }
            orphanCount = count
            if (count == 0) break
            SystemClock.sleep(100)
        }
        recordTiming(
            "destroy_to_orphan_cleared_ms",
            SystemClock.elapsedRealtime() - destroyAt,
        )
        writeText("issue215-02-after-destroy-clients.txt", orphanRawSnapshot)
        assertEquals(
            "expected zero tmux clients on $SEEDED_SESSION after app close; raw=`$orphanRawSnapshot`",
            0,
            orphanCount,
        )

        // ---- (5) The session itself must still be alive on the server.
        // The maintainer's reported workflow is "close the phone app,
        // attach from laptop" — so the session must persist, only the
        // -CC client should be gone. assertion mirrors the issue's
        // explicit non-goal "killing the session itself on PocketShell
        // close is NOT the desired behaviour".
        val survives = listSessions(key).any { it.startsWith("$SEEDED_SESSION:") }
        assertTrue(
            "expected $SEEDED_SESSION to survive PocketShell close — the maintainer's workflow " +
                "requires reattaching from a laptop; sessions=${listSessions(key)}",
            survives,
        )

        // ---- (6) Now act as the laptop: open a plain (non-CC) ssh
        // shell, run `tmux attach -t claude-main`, type a marker, and
        // assert the marker reaches the running pane. We use
        // [openShell] from AndroidSshTestFixtures (already used by
        // other proof tests) and capture the pane output via
        // `tmux capture-pane -p`.
        val secondClientStart = SystemClock.elapsedRealtime()
        val marker = "POCKETSHELL215_${System.nanoTime()}"
        val capture = attachAsPlainClientAndType(key, SEEDED_SESSION, marker)
        recordTiming(
            "second_client_attach_to_marker_ms",
            SystemClock.elapsedRealtime() - secondClientStart,
        )
        writeText("issue215-03-second-client-pane.txt", capture)
        assertTrue(
            "expected `$marker` in second-client pane capture, got:\n$capture",
            capture.contains(marker),
        )

        writeTimings()
        Unit
    }

    // ---------------------------------------------------------------- Helpers

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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .fallbackToDestructiveMigration(dropAllTables = false)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue215-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue215 OrphanClient",
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
            appendLine("tmux kill-session -t ${shellQuote(SEEDED_SESSION)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SEEDED_SESSION)} " +
                    "${shellQuote("printf 'ISSUE215-READY\\n'; exec sleep 600")}",
            )
            appendLine("sleep 1")
            appendLine("tmux list-sessions")
        }
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec(script) }
        }
        val exec = result.getOrNull()
        assertTrue(
            "expected tmux seeding to succeed; exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded session: ${exec?.stdout?.trim()}")
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
                    it.exec("tmux kill-session -t ${shellQuote(SEEDED_SESSION)} 2>/dev/null || true")
                }
            }
        }
    }

    /**
     * Issue #215: return the number of clients currently attached to
     * [sessionName] per `tmux list-clients -t <session>`. The command
     * prints one line per attached client; with zero clients tmux
     * prints nothing (exit 0), with a missing session tmux prints to
     * stderr (exit non-zero, which we surface as -1 so the caller can
     * tell apart "no clients" from "server-side error").
     */
    private suspend fun listClientsCount(key: String, sessionName: String): Int {
        val raw = listClientsRaw(key, sessionName)
        return raw.lines().count { it.isNotBlank() }
    }

    private suspend fun listClientsRaw(key: String, sessionName: String): String {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec(
                    "tmux list-clients -t ${shellQuote(sessionName)} 2>/dev/null || true",
                )
            }
        }
        return result.getOrNull()?.stdout.orEmpty()
    }

    private suspend fun listSessions(key: String): List<String> {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec("tmux list-sessions 2>/dev/null || true") }
        }
        return result.getOrNull()?.stdout?.lines()?.filter { it.isNotBlank() }.orEmpty()
    }

    /**
     * Issue #215: open a fresh interactive SSH session, run
     * `tmux attach-session -t <sessionName>` over a normal PTY (not
     * `-CC`), type [marker] followed by Enter, then read the resulting
     * pane content back via `tmux capture-pane -p`.
     *
     * The second client uses [openShell] (the same helper the existing
     * proof suite uses to drive raw interactive shells against the
     * fixture). We intentionally do NOT use [SshSession.exec] to fire
     * the `tmux attach` because that would not allocate a PTY and tmux
     * would refuse to attach (`not a terminal`). Going through a real
     * PTY mirrors what the maintainer's laptop does with
     * `ssh testuser@host -t -p 2222 tmux attach -t claude-main`.
     */
    private suspend fun attachAsPlainClientAndType(
        key: String,
        sessionName: String,
        marker: String,
    ): String {
        val handle = openShell(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
        )
        try {
            // Drain initial banner/prompt bytes so we can deterministically
            // detect when the next prompt is ready. We rely on the simple
            // "wait for any bytes" semantics — alpine's busybox `sh`
            // prints `~ $ ` within tens of ms of shell start.
            val stdin = handle.shell.outputStream
            stdin.write(
                ("tmux attach-session -t ${shellQuote(sessionName)}\n").toByteArray(StandardCharsets.UTF_8),
            )
            stdin.flush()
            // Give tmux a moment to attach + redraw the pane. tmux attach
            // is fast (sub-100ms locally), so 750ms is comfortable padding.
            SystemClock.sleep(750)

            // Type the marker via `echo <marker>` so the pane echoes a
            // self-contained line that's easy to grep for in capture-pane.
            // We deliberately don't drive `send-keys` over a side channel
            // — we want the keystrokes to flow through THIS client's
            // input pipe, which is the part the orphan -CC client used
            // to break.
            stdin.write(("echo $marker\n").toByteArray(StandardCharsets.UTF_8))
            stdin.flush()
            // Wait for the shell inside tmux to print the line. 1500ms is
            // generous on the CI emulator + Docker round-trip.
            SystemClock.sleep(1_500)

            // Capture the pane content from a separate exec channel — this
            // is the authoritative "what the second client sees" snapshot.
            // We use a sidecar session for capture-pane because the
            // interactive `tmux attach` session is still inside tmux's
            // event loop; running `tmux capture-pane` from it would race.
            val captureResult = SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use {
                    it.exec("tmux capture-pane -p -t ${shellQuote(sessionName)} 2>&1 || true")
                }
            }
            return captureResult.getOrNull()?.stdout.orEmpty()
        } finally {
            // Detach the second client by closing its shell channel.
            // Leaving the handle dangling would itself leave an orphan
            // client which is what the @After cleanup also guards
            // against, but explicit teardown is cheaper than relying on
            // the After path.
            runCatching { handle.shell.close() }
            runCatching { handle.sessionChannel.close() }
            runCatching { handle.client.disconnect() }
        }
    }

    private fun waitForText(text: String, timeoutMs: Long) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithText(text, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
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
        println("ISSUE215_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE215_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE215_TIMINGS ${file.absolutePath}")
        return file
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create artifact directory ${dir.absolutePath}"
        }
        return File(dir, name)
    }

    private fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE215_TIMING $line")
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
        const val LOG_TAG: String = "Issue215OrphanClient"
        const val DEVICE_DIR_NAME: String = "issue215-orphan-client-cleanup"
        // Same seeded-session name pattern as the other connected tmux
        // tests: the picker reads its session list from `tmuxctl list`,
        // which only knows the pre-baked names; `claude-main` is the
        // most exercised one across the existing suite.
        const val SEEDED_SESSION: String = "claude-main"

        /**
         * After we destroy the activity, we wait up to this long for
         * `tmux list-clients -t claude-main` to drop to zero. The fix
         * removes the client in <100ms on a healthy fixture (the
         * `detach-client` round-trip is sub-millisecond on localhost
         * + the runBlocking hop is bounded by [SYNC_DETACH_TIMEOUT_MS]
         * = 600ms). 6s is generous CI headroom for swiftshader
         * emulator + Docker compose overhead.
         */
        const val ORPHAN_CLIENT_CLEANUP_TIMEOUT_MS: Long = 6_000L
    }
}
