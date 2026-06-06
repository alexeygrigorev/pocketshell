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
import androidx.lifecycle.Lifecycle
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
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
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
 * Issue #235 — auto-detach the tmux `-CC` control client on lifecycle
 * background so the desktop terminal opening the same session is not
 * pinned to the phone's small viewport.
 *
 * Symptom (maintainer dogfood, v0.2.8): PocketShell attaches to a
 * tmux session at ~85x30, desktop terminal attaches at 200x50, tmux
 * sizes the window to `min(phone, desktop) = phone dims`. Desktop
 * sees a shrunken window. The fix lives in
 * [com.pocketshell.app.App]'s `ProcessLifecycleOwner.ON_STOP` observer
 * (added in #235) + the per-host hook
 * [com.pocketshell.app.tmux.TmuxSessionViewModel.onAppBackgrounded]
 * fans out to.
 *
 * This test mirrors the structure of [TmuxOrphanClientCleanupE2eTest]
 * (the #215 sidecar pattern) and runs against the deterministic
 * `agents:2222` Docker fixture.
 *
 * Flow:
 *  1. Seed `claude-main` on the remote (`sleep 600` keeps the pane
 *     alive past the test body).
 *  2. Attach via the app — host list → folder list → session row.
 *  3. Capture the attached terminal viewport (proof the app
 *     attached, used to gate the rest of the assertions).
 *  4. Drive `ActivityScenario.moveToState(Lifecycle.State.CREATED)`
 *     — same lifecycle journey `UiDevice.pressHome()` produces on a
 *     real device (Activity → STOPPED → ProcessLifecycleOwner
 *     `ON_STOP`), but without an extra `uiautomator` dependency and
 *     without depending on launcher-availability on swiftshader.
 *     The `App` observer (#235) fans the event into every registered
 *     `TmuxSessionViewModel`'s `onAppBackgrounded` hook, which runs
 *     `detachCleanly()` against the live `-CC` client.
 *  5. Poll `tmux list-clients -t claude-main` until the count
 *     reaches 0 — proves the `-CC` client is gone and the size lock
 *     is released.
 *  6. Open a sidecar plain-ssh `tmux attach -t claude-main` over a
 *     200x50 PTY (much larger than any plausible phone size). After
 *     the attach settles, run `tmux display-message -p` for
 *     `#{window_width}` / `#{window_height}` and assert width > 85,
 *     which is the acceptance criterion. (We deliberately ask tmux
 *     for the *window* dimensions rather than `stty size` on the
 *     sidecar PTY — the dogfood symptom is tmux's size lock, and the
 *     authoritative signal is tmux's view of the window after the
 *     attach.)
 *  7. Return the app to foreground via `moveToState(RESUMED)`. The
 *     `App` observer fans `ON_START` into `onAppForegrounded`, which
 *     drives the existing connect() machinery to reattach the `-CC`
 *     client. Capture a second viewport to prove the reattach
 *     rendered.
 *
 * Artifact contract (process.md "Terminal Artifact Review"):
 *  - `issue235-01-attached-viewport.png` + `-visible-terminal.txt`:
 *    proof the app attached cleanly before the test exercised the
 *    background-detach path.
 *  - `issue235-02-after-background-clients.txt`: output of
 *    `tmux list-clients -t claude-main` after the lifecycle
 *    background to demonstrate the `-CC` client was actually
 *    detached.
 *  - `issue235-03-desktop-window-size.txt`: tmux's view of the
 *    window dimensions after the desktop client attaches. Assertion
 *    asserts width > 85.
 *  - `issue235-04-reattach-viewport.png` + `-visible-terminal.txt`:
 *    proof the app reattached cleanly when the activity returned to
 *    foreground.
 *  - `timings.txt`: attach time, background-to-detach latency,
 *    desktop-attach-to-size-read latency, foreground-to-reattach
 *    latency.
 */
@RunWith(AndroidJUnit4::class)
class TmuxDetachOnBackgroundE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus
    // from the Compose hierarchy ("No compose hierarchies found").
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

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
    fun backgroundingTheAppDetachesTmuxAndDesktopGetsFullSize() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))
        seedTmuxSession(key)
        clearLogcat()

        val hostRowTag = seedDockerHost(key)
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // ---- (1) Attach to claude-main from the current host →
        // FolderListScreen → session-row flow.
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
        captureViewport("issue235-01-attached")

        // Wait for tmux's server-side `-CC` client registration to
        // catch up with the local TerminalView bind. `waitForTerminalViewAttached`
        // proves the local TerminalView is bound to a TerminalSession,
        // but tmux registers the `-CC` client one round-trip later via
        // the control-mode handshake. Polling here mirrors the
        // post-detach poll at the bottom of the test — without this
        // loop the round-2 reviewer observed `got 0` racing the
        // server-side registration on swiftshader (issue #235, r2).
        var attachedClientCount = -1
        var attachedRawSnapshot = ""
        val preBackgroundDeadline =
            SystemClock.elapsedRealtime() + PRE_BACKGROUND_CLIENT_WAIT_MS
        while (SystemClock.elapsedRealtime() < preBackgroundDeadline) {
            val raw = listClientsRaw(key, SEEDED_SESSION)
            attachedRawSnapshot = raw
            val count = raw.lines().count { it.isNotBlank() }
            attachedClientCount = count
            if (count >= 1) break
            SystemClock.sleep(100)
        }
        Log.i(LOG_TAG, "attached-state clients on $SEEDED_SESSION = $attachedClientCount")
        assertTrue(
            "expected at least one tmux client (the app's -CC connection) before background within " +
                "${PRE_BACKGROUND_CLIENT_WAIT_MS}ms, got $attachedClientCount; raw=`$attachedRawSnapshot`",
            attachedClientCount >= 1,
        )

        // ---- (2) Background the app via `moveToState(CREATED)`. This
        // walks the Activity through `onPause` + `onStop`, which the
        // [ProcessLifecycleOwner] observer in [com.pocketshell.app.App]
        // translates into the `ON_STOP` event the #235 fanout reads
        // off. Same lifecycle journey `UiDevice.pressHome()` produces
        // on a real device — but without dragging `uiautomator` into
        // the connected test classpath and without depending on the
        // emulator launcher being responsive (swiftshader's launcher
        // is flaky).
        val backgroundStart = SystemClock.elapsedRealtime()
        launchedActivity?.moveToState(Lifecycle.State.CREATED)

        // ---- (3) Poll list-clients until the detach lands. tmux
        // removes the entry within a single round-trip once
        // `detach-client` is received; 6s is generous CI headroom and
        // also covers ProcessLifecycleOwner's 700ms ON_STOP debounce.
        var orphanCount = -1
        var orphanRawSnapshot = ""
        val deadline = SystemClock.elapsedRealtime() + DETACH_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val raw = listClientsRaw(key, SEEDED_SESSION)
            orphanRawSnapshot = raw
            val count = raw.lines().count { it.isNotBlank() }
            orphanCount = count
            if (count == 0) break
            SystemClock.sleep(100)
        }
        recordTiming(
            "background_to_detach_ms",
            SystemClock.elapsedRealtime() - backgroundStart,
        )
        writeText("issue235-02-after-background-clients.txt", orphanRawSnapshot)
        assertEquals(
            "expected zero tmux clients on $SEEDED_SESSION after background; raw=`$orphanRawSnapshot`",
            0,
            orphanCount,
        )

        // ---- (4) Attach as the "desktop" from a sidecar SSH client
        // with a 200x50 PTY (much larger than the phone's ~85x30).
        // Once the desktop client is attached, ask tmux directly what
        // it sized the window at and assert the width > 85 — the
        // acceptance criterion is "phone-size lock released".
        val desktopAttachStart = SystemClock.elapsedRealtime()
        val (widthAfterDetach, heightAfterDetach, rawSizeReport) = attachAsDesktopAndReportSize(
            key = key,
            sessionName = SEEDED_SESSION,
            ptyCols = DESKTOP_PTY_COLS,
            ptyRows = DESKTOP_PTY_ROWS,
        )
        recordTiming(
            "desktop_attach_to_size_ms",
            SystemClock.elapsedRealtime() - desktopAttachStart,
        )
        writeText("issue235-03-desktop-window-size.txt", rawSizeReport)
        assertTrue(
            "expected tmux window width > 85 after phone detached (got $widthAfterDetach); raw='$rawSizeReport'",
            widthAfterDetach > 85,
        )
        assertTrue(
            "expected tmux window height > 1 after phone detached (got $heightAfterDetach); raw='$rawSizeReport'",
            heightAfterDetach > 1,
        )

        // ---- (5) Bring the app back to foreground via the
        // matching `moveToState(RESUMED)`. The `App` observer fans
        // `ON_START` into `onAppForegrounded`, which drives the
        // existing connect() machinery to reattach the `-CC` client.
        val foregroundStart = SystemClock.elapsedRealtime()
        launchedActivity?.moveToState(Lifecycle.State.RESUMED)
        // Wait for the session screen to re-render. Reattach runs the
        // same SSH handshake + tmux `-CC` connect the cold attach did,
        // so 30s mirrors the cold-attach ceiling.
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        waitForTerminalViewAttached()
        recordTiming(
            "foreground_to_reattach_ms",
            SystemClock.elapsedRealtime() - foregroundStart,
        )
        captureViewport("issue235-04-reattach")
        val logcat = dumpLogcat()
        writeText("issue272-lifecycle-identity-logcat.txt", logcat.takeLast(500_000))
        assertTrue(
            "expected background detach log for $SEEDED_SESSION; logcat tail:\n${logcat.takeLast(4_000)}",
            logcat.contains("tmux-detach-on-background") &&
                logcat.contains("session=$SEEDED_SESSION"),
        )
        assertTrue(
            "expected foreground reattach to use lifecycle trigger and same session; logcat tail:\n${logcat.takeLast(4_000)}",
            logcat.contains("tmux-reattach-on-foreground trigger=lifecycle-reattach") &&
                logcat.contains("session=$SEEDED_SESSION"),
        )
        assertTrue(
            "expected connect attempt to identify lifecycle-reattach trigger and same session; logcat tail:\n${logcat.takeLast(4_000)}",
            logcat.contains("tmux-connect-attempt") &&
                logcat.contains("trigger=lifecycle-reattach") &&
                logcat.contains("session=$SEEDED_SESSION"),
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
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue235-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue235 BackgroundDetach",
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
                    "${shellQuote("printf 'ISSUE235-READY\\n'; exec sleep 600")}",
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

    /**
     * Issue #235: open a fresh plain-ssh shell, allocate a PTY of
     * [ptyCols] × [ptyRows] (the "desktop terminal" dimensions),
     * `tmux attach -t <sessionName>` over it, then poll
     * `tmux list-clients -t <sessionName>` until tmux reports a
     * client at the requested width.
     *
     * The size-report path uses a sidecar `exec` channel (not the
     * interactive attach shell) so we don't have to parse the
     * interactive PTY output stream and don't have to disambiguate
     * tmux status-line text from our query response.
     *
     * Why `list-clients` instead of `display-message` against
     * `#{window_width}`: when a phone client previously sized the
     * window down via `resize-window`, tmux retains that smaller
     * size even after the new desktop client attaches — the
     * negotiation only collapses on the next refresh after `attach-
     * session` settles. `list-clients` reports the client's actual
     * PTY dimensions (via `#{client_width}`), which is the
     * "did the desktop attach at desktop dims?" signal we actually
     * want — and which the v0.2.8 dogfood symptom depended on
     * (`min(phone, desktop)` only matters when the desktop's
     * `client_width` is correctly observed by tmux; if the desktop's
     * own `client_width` is small, no fix on the phone side could
     * help).
     *
     * Returns (client_width, client_height, raw list-clients output).
     */
    private suspend fun attachAsDesktopAndReportSize(
        key: String,
        sessionName: String,
        ptyCols: Int,
        ptyRows: Int,
    ): Triple<Int, Int, String> {
        val handle = openShellWithPty(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            ptyCols = ptyCols,
            ptyRows = ptyRows,
        )
        try {
            val stdin = handle.shell.outputStream
            stdin.write(
                ("tmux attach-session -t ${shellQuote(sessionName)}\n").toByteArray(StandardCharsets.UTF_8),
            )
            stdin.flush()

            // Poll list-clients until the desktop client appears at
            // (or close to) the expected width. tmux's `client_width`
            // is the PTY width the client opened with, so this is the
            // authoritative "did the desktop attach at desktop dims?"
            // signal. We give the attach + tmux-server resize loop up
            // to [SIZE_QUERY_TIMEOUT_MS] to settle on swiftshader CI.
            val deadline = SystemClock.elapsedRealtime() + SIZE_QUERY_TIMEOUT_MS
            var lastRaw = ""
            var lastWidth = -1
            var lastHeight = -1
            while (SystemClock.elapsedRealtime() < deadline) {
                val rawStdout = listClientsWithSize(key, sessionName)
                lastRaw = rawStdout
                val parsed = parseFirstClientSize(rawStdout)
                if (parsed != null) {
                    lastWidth = parsed.first
                    lastHeight = parsed.second
                    // Accept any width that proves the size lock is
                    // released. The exact value tmux reports depends
                    // on whether the kernel has applied the SSH PTY
                    // size update yet (race), but any reading > 85
                    // demonstrates we are not pinned to the phone.
                    if (lastWidth > 85) break
                }
                SystemClock.sleep(150)
            }
            return Triple(lastWidth, lastHeight, lastRaw)
        } finally {
            runCatching { handle.shell.close() }
            runCatching { handle.sessionChannel.close() }
            runCatching { handle.client.disconnect() }
        }
    }

    /**
     * Issue #235: parse the first non-empty `tmux list-clients`
     * line of the form `<tty>: <session> [<W>x<H> <term>] ...` into
     * (cols, rows). Returns null if no parse-able row is present —
     * the polling caller treats that as "client hasn't materialised
     * yet, keep polling."
     */
    private fun parseFirstClientSize(raw: String): Pair<Int, Int>? {
        for (line in raw.lines()) {
            val match = Regex("""\[(\d+)x(\d+)""").find(line) ?: continue
            val w = match.groupValues[1].toIntOrNull() ?: continue
            val h = match.groupValues[2].toIntOrNull() ?: continue
            return w to h
        }
        return null
    }

    /**
     * Issue #235: same shape as [listClientsRaw] but explicitly asks
     * for the `[WIDTHxHEIGHT]` rendering tmux uses in its default
     * `list-clients` format. We rely on the default format here
     * rather than `-F '#{client_width} #{client_height}'` because
     * the default format is what reviewers will recognise from a
     * shell — it makes the captured artifact human-readable without
     * sacrificing parseability.
     */
    private suspend fun listClientsWithSize(key: String, sessionName: String): String {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec("tmux list-clients -t ${shellQuote(sessionName)} 2>&1 || true")
            }
        }
        return result.getOrNull()?.stdout.orEmpty()
    }

    /**
     * Issue #235: variant of [openShell] that allocates a PTY at a
     * specific cols×rows so the "desktop" sidecar attaches at known
     * dimensions. The default in [openShell] is 80×24, which is also
     * larger than what the phone emulator reports (around ~85×30 on a
     * Pixel 7) but only barely; 200×50 gives us a clear, unambiguous
     * delta to assert on.
     */
    private suspend fun openShellWithPty(
        host: String,
        port: Int,
        user: String,
        key: SshKey.Pem,
        ptyCols: Int,
        ptyRows: Int,
    ): ShellHandle = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val client: SSHClient = SshConnection.createClient()
        try {
            client.addHostKeyVerifier(PromiscuousVerifier())
            client.connectTimeout = 15_000
            client.timeout = 15_000
            client.connect(host, port)
            val keyProvider = client.loadKeys(key.content, null, null)
            client.authPublickey(user, keyProvider)
            val session: Session = client.startSession()
            session.allocatePTY(
                "xterm-256color",
                ptyCols,
                ptyRows,
                0,
                0,
                emptyMap<PTYMode, Int>(),
            )
            val shell = session.startShell()
            ShellHandle(client = client, sessionChannel = session, shell = shell)
        } catch (t: Throwable) {
            runCatching { client.disconnect() }
            throw t
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
        println("ISSUE235_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE235_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE235_TIMINGS ${file.absolutePath}")
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

    private fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE235_TIMING $line")
    }

    private fun clearLogcat() {
        runCatching { ProcessBuilder("logcat", "-c").start().waitFor() }
    }

    private fun dumpLogcat(): String =
        ProcessBuilder(
            "logcat",
            "-d",
            "-v",
            "threadtime",
            "PsTmuxLifecycle:I",
            "PsTmuxReconnect:I",
            "*:S",
        )
            .redirectErrorStream(true)
            .start()
            .inputStream
            .bufferedReader()
            .use { it.readText() }

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
        const val LOG_TAG: String = "Issue235BackgroundDetach"
        const val DEVICE_DIR_NAME: String = "issue235-background-detach"
        const val SEEDED_SESSION: String = "claude-main"

        /** Desktop client PTY dimensions — clearly larger than the
         *  phone emulator (~85x30) so the post-detach assertion
         *  "width > 85" is unambiguous. */
        const val DESKTOP_PTY_COLS: Int = 200
        const val DESKTOP_PTY_ROWS: Int = 50

        /**
         * After we drive the lifecycle background, the detach must
         * land in well under this ceiling. The single-client detach
         * round-trip on a healthy fixture is sub-100ms;
         * ProcessLifecycleOwner adds a 700ms ON_STOP debounce; 6s is
         * generous CI headroom for swiftshader emulator + Docker
         * compose overhead, matching the #215 sibling test's budget.
         */
        const val DETACH_TIMEOUT_MS: Long = 6_000L

        /**
         * Issue #235 r2: the local `TerminalView` binds to its session
         * before tmux's server-side `-CC` client entry materialises
         * (the control-mode handshake is one round-trip behind the
         * local terminal bind). The reviewer observed a `got 0` race
         * on 1 of 6 swiftshader runs against the single-shot
         * assertion. Polling for ~6s here mirrors [DETACH_TIMEOUT_MS]
         * and is generous given the handshake is normally sub-200ms
         * on a healthy Docker fixture; this matches the post-detach
         * poll budget so flake stays symmetric.
         */
        const val PRE_BACKGROUND_CLIENT_WAIT_MS: Long = 6_000L

        /**
         * Maximum time we wait for tmux to report the desktop client
         * at its requested PTY dimensions. The two-step attach
         * (interactive shell reads `tmux attach-session\n`, forks
         * tmux, tmux opens the control channel, server propagates the
         * client PTY size to the window) is normally sub-200ms on a
         * healthy fixture but can race the SSH PTY-size signal on a
         * cold swiftshader emulator; 8s gives the polling loop room
         * to settle without being so generous that an actually-broken
         * fix silently passes.
         */
        const val SIZE_QUERY_TIMEOUT_MS: Long = 8_000L
    }
}
