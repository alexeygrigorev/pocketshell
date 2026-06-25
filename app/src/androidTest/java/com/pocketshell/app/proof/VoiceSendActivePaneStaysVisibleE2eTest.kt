package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
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
import com.pocketshell.app.tmux.TMUX_CONNECTING_PROGRESS_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_SWITCHING_LOADING_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #717 (epic #687 slice 2 — reveal/reflow-heal) — DEVICE-TRUTH journey: after
 * sending a (voice-recorded) prompt the active pane must NEVER go black.
 *
 * ## The production bug
 *
 * A voice-send dismisses the composer/soft keyboard. That dismissal shrinks the IME
 * inset, which fires `onTerminalSizeChanged` ->
 * [TmuxSessionViewModel.resizeRemotePty] -> `maybeRefreshControlClientSize`. For an
 * IDLE full-screen agent pane the reflow during the IME transition can wipe the LOCAL
 * emulator while the idle agent emits no fresh `%output`, so the pane is left BLACK.
 *
 * On `origin/main` (before slice 2) the post-resize heal in `maybeRefreshControlClientSize`
 * was `reseedBlankVisiblePanes` (blank-ONLY) AND — worse for the voice-send case — when
 * the composer-dismiss resize resolves to the SAME grid dims already applied, the whole
 * block SHORT-CIRCUITS (`cols == appliedControlClientColumns && rows == appliedControlClientRows`
 * -> early `return`), so NO heal runs at all: the active pane the IME transition wiped
 * stays black until a manual interaction.
 *
 * ## The slice-2 fix (absorbs #658's reflow-heal plan)
 *
 *  * Change A — the post-reflow heal is now `reseedActivePaneForReattach` (the P3
 *    UNCONDITIONAL full-viewport active-pane re-capture, not blank-only), so a
 *    black/garbled active pane is re-captured from tmux's authoritative grid.
 *  * Change B — the SAME-dimension short-circuit no longer returns blindly: it runs a
 *    cheap active-pane heal ([maybeHealActivePaneOnNoOpResize]) when the active pane is
 *    blank/suspect, re-capturing the lost frame with NO `refresh-client -C` wire op.
 *
 * ## How this reproduces the bug deterministically on agents:2222 (no toxiproxy)
 *
 * Model the on-screen state the composer/keyboard-dismiss reflow leaves directly on the
 * RETAINED live emulator: feed a `CSI 2J` + `CSI H` (erase display + home) THEN one fresh
 * live line — the static banner is WIPED while a lone live line (an agent's status/prompt
 * line) keeps the pane NON-blank. That lone live line is the whole bug: it makes
 * `transcriptText` non-blank, so every existing blank-gated net (`reseedBlankVisiblePanes`,
 * the reveal gate, the connected-blank watchdog) SKIPS the pane and the banner is never
 * restored. The REMOTE tmux grid is untouched, so `capture-pane` still holds the FULL
 * banner. Then drive the EXACT same-dimension short-circuit production branch via
 * [TmuxSessionViewModel.triggerSameDimensionResizeHealForTest] (the composer-dismiss
 * resolves to dims already applied). On base that branch returns blindly -> the banner is
 * never restored -> assertion RED. After the fix the UNCONDITIONAL active-pane heal
 * re-captures the full viewport -> GREEN. Uses ONLY the deterministic `agents` fixture
 * (host port 2222), feeds the emulator locally (no toxiproxy, no
 * `Assume.assumeFalse(isRunningOnCi())`), so it RUNS on the per-PR CI emulator-journey job.
 *
 * ## Contract (DEVICE TRUTH — asserts the user's pixels)
 *
 *  1. Before the send/dismiss, the active pane shows the full banner (baseline).
 *  2. The partial blank is REAL on screen: after the modelled composer-dismiss reflow the
 *     static banner is gone while a lone live line keeps the pane NON-blank — the exact
 *     origin/main skip precondition.
 *  3. After the (same-dimension) resize heal the active pane is RE-SEEDED to the full
 *     prior content: the banner is restored. On base the short-circuit + blank-only nets
 *     skip the non-blank pane, so the banner is never restored -> RED.
 *  4. NO Reconnecting/Disconnected/Connecting/Attaching surface appears (the resize heal
 *     is a calm in-place re-capture, not a reconnect).
 */
@RunWith(AndroidJUnit4::class)
class VoiceSendActivePaneStaysVisibleE2eTest {

    val compose = createAndroidComposeRule<MainActivity>()
    private val grantPermissions = PreGrantPermissionsRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(grantPermissions)
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private var seededKey: String? = null
    private var seededHostRowTag: String? = null
    private val timings = mutableListOf<String>()

    @After
    fun tearDown() {
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        seededKey?.let { key -> runCatching { runBlocking { cleanupRemoteTmuxSession(key) } } }
    }

    @Test
    fun voiceSendComposerDismissResizeKeepsActivePaneVisible() = runBlocking {
        val hostRowTag = requireNotNull(seededHostRowTag) { "seed-before-launch host row missing" }
        attachSeededTmuxSession(hostRowTag)

        // (1) Baseline: the static banner of the idle full-screen pane is on screen and
        // the channel is Connected. tmux's server grid holds the banner throughout.
        waitForVisibleTerminal("initial attach banner") { it.contains(BANNER_MARKER) }
        waitForConnected("initial attach")
        captureViewport("issue717-01-attached")

        val cycleStart = SystemClock.elapsedRealtime()

        // (2) Model the composer/keyboard-dismiss reflow that loses the idle agent's
        // static frame: erase the display + home, then paint ONE fresh live line —
        // straight into the SAME emulator the app renders. This is the exact on-screen
        // state the maintainer's #717 + #651 leaves: the static banner above is WIPED
        // while a lone live line (an agent's status/prompt line) keeps the pane
        // NON-blank. That one live line is the whole bug: it makes `transcriptText`
        // non-blank, so every existing blank-gated net ([reseedBlankVisiblePanes], the
        // reveal gate, the connected-blank watchdog) SKIPS the pane and the banner is
        // never restored. The REMOTE tmux grid is untouched, so `capture-pane` still has
        // the full banner; only the slice-2 UNCONDITIONAL active-pane heal restores it.
        wipeActivePaneEmulator()
        val wipedView = waitForVisibleTerminal("post-dismiss partial-blank pane") {
            it.contains(LIVE_LINE_MARKER) && !it.contains(BANNER_MARKER)
        }
        assertTrue(
            "post-dismiss precondition must keep the lone live line ('$LIVE_LINE_MARKER') so " +
                "the pane is NON-blank (the origin/main skip precondition); visible:\n$wipedView",
            wipedView.contains(LIVE_LINE_MARKER),
        )
        assertTrue(
            "post-dismiss precondition must have WIPED the static banner (the #717/#651 " +
                "symptom that the blank-only nets skip); visible:\n$wipedView",
            !wipedView.contains(BANNER_MARKER),
        )
        captureViewport("issue717-02-partial-blank")
        recordTiming("partial_blank_injected_ms", SystemClock.elapsedRealtime() - cycleStart)

        // (3) Drive the EXACT same-dimension resize short-circuit production branch the
        // composer-dismiss takes (resize back to dims already applied). On base the
        // branch returns blindly (and the blank-only nets skip the non-blank pane), so
        // the banner stays gone; the slice-2 active-pane heal re-captures the full
        // viewport.
        var triggered = false
        compose.activityRule.scenario.onActivity { activity ->
            triggered = viewModel(activity).triggerSameDimensionResizeHealForTest()
        }
        assertTrue("expected the same-dimension resize heal seam to find a live runtime", triggered)
        recordTiming("resize_heal_triggered_ms", SystemClock.elapsedRealtime() - cycleStart)

        // DEVICE-TRUTH assertion (3): the active pane is RE-SEEDED to the full prior
        // content — the static banner is restored. The slice-2 heal re-captures the pane
        // PROMPTLY (a single `capture-pane`), so a SHORT timeout is the discriminator:
        // on base `origin/main` the same-dimension short-circuit skips the heal and the
        // lone live line makes every blank-only net skip the non-blank pane, so the
        // banner is NOT restored within this window -> RED. (The timeout is deliberately
        // SHORT and asserted FIRST so a later, unrelated passive-disconnect transport
        // reattach — which would itself reseed the pane many seconds later — cannot mask
        // the missing heal.)
        waitForVisibleTerminal(
            "post-heal full-viewport restore",
            timeoutMillis = HEAL_RESTORE_TIMEOUT_MS,
        ) { it.contains(BANNER_MARKER) }
        val visibleAfter = visibleTerminalText()
        assertTrue(
            "voice-send composer-dismiss resize must PROMPTLY restore the active pane's full " +
                "viewport (static banner '$BANNER_MARKER') — the pane must NOT stay " +
                "partial-blank; visible terminal was:\n$visibleAfter",
            visibleAfter.contains(BANNER_MARKER),
        )
        recordTiming("banner_restored_ms", SystemClock.elapsedRealtime() - cycleStart)
        captureViewport("issue717-03-full-viewport-restored")

        // DEVICE-TRUTH assertion (4): NO reconnect surface across the heal + a short
        // settle — the heal is a calm in-place re-capture, not a reconnect band.
        watchNoVisibleReconnect("voice-send resize heal settle", OVERLAY_WATCH_MS)

        // The session screen is still up (an in-place heal, not a teardown).
        assertTrue(
            "tmux session screen must still be up after the voice-send resize heal",
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )

        writeSummary()
        writeTimings()
        Unit
    }

    // ---------------------------------------------------------------- Helpers

    /**
     * Model the composer/keyboard-dismiss reflow's PARTIAL blank straight into the SAME
     * emulator the app renders: a `CSI 2J` (erase entire display) + `CSI H` (cursor home)
     * wipes the static banner, then ONE fresh live line repaints. The lone live line is
     * the whole bug — it makes `transcriptText` NON-blank, so every blank-gated net
     * ([reseedBlankVisiblePanes], the reveal gate, the connected-blank watchdog) SKIPS
     * the pane. This is local to the emulator — the remote tmux grid keeps the full
     * banner, so only the slice-2 UNCONDITIONAL active-pane heal restores it.
     */
    private fun wipeActivePaneEmulator() {
        val esc = "\u001B"
        val frame = "$esc[2J$esc[H$LIVE_LINE_MARKER live-after-reflow\r\n".toByteArray(Charsets.UTF_8)
        var fed = false
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            val emulator = view.mEmulator ?: return@onActivity
            emulator.append(frame, frame.size)
            view.invalidate()
            fed = true
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue("expected to inject the partial-blank frame to the active pane emulator", fed)
        Log.i(LOG_TAG, "injected partial-blank frame (2J + home + one live line) modelling composer-dismiss reflow")
    }

    private fun attachSeededTmuxSession(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
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

    private fun viewModel(activity: MainActivity): TmuxSessionViewModel =
        ViewModelProvider(activity)[TmuxSessionViewModel::class.java]

    private fun currentConnectionStatus(): TmuxSessionViewModel.ConnectionStatus {
        var status: TmuxSessionViewModel.ConnectionStatus =
            TmuxSessionViewModel.ConnectionStatus.Idle
        compose.activityRule.scenario.onActivity { activity ->
            status = viewModel(activity).connectionStatus.value
        }
        return status
    }

    private fun waitForVisibleTerminal(
        label: String,
        timeoutMillis: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
        predicate: (String) -> Boolean,
    ): String {
        var last = ""
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                last = visibleTerminalText()
                predicate(last)
            }
            true
        }.getOrDefault(false)
        if (!satisfied) writeText("failure-$label-visible-terminal.txt", last)
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

    private fun assertNoVisibleReconnect(label: String) {
        assertEquals(
            "expected no Connecting overlay for $label",
            0,
            compose.onAllNodesWithTag(TMUX_CONNECTING_PROGRESS_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        assertEquals(
            "expected no disconnect band for $label",
            0,
            compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        assertEquals(
            "expected no Tap Reconnect button for $label",
            0,
            compose.onAllNodesWithTag(TMUX_SESSION_RECONNECT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        assertEquals(
            "expected no 'Attaching…' switching-loading overlay for $label",
            0,
            compose.onAllNodesWithTag(TMUX_SWITCHING_LOADING_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        listOf("Connecting", "Reconnecting", "Disconnected", "Tap Reconnect", "Attaching").forEach { text ->
            assertEquals(
                "expected no visible '$text' text for $label",
                0,
                compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes().size,
            )
        }
    }

    private fun watchNoVisibleReconnect(label: String, durationMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < deadline) {
            assertNoVisibleReconnect(label)
            SystemClock.sleep(100)
        }
        recordTiming("${label.replace(' ', '_')}_no_reconnect_ms", durationMs)
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedBeforeLaunch() {
        val key = readFixtureKey()
        seededKey = key
        try {
            waitForSshFixtureReady(SshKey.Pem(key))
            seedTmuxSession(key)
            seededHostRowTag = seedDockerHost(key)
        } catch (t: Throwable) {
            runCatching { cleanupRemoteTmuxSession(key) }
            throw t
        }
    }

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
                name = "issue717-voicesend-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue717 Voice Send Black Pane",
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
        // A multi-line static banner (the content that must survive the composer-dismiss
        // reflow black pane) running as an idle full-screen pane that emits no further
        // output — exactly the idle full-screen agent the maintainer hit.
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            val payload = buildString {
                append("printf '$BANNER_MARKER line 1\\n$BANNER_MARKER line 2\\n$BANNER_MARKER line 3\\n'; ")
                // Idle full-screen: paint once, then read forever (no fresh %output, like
                // an idle agent waiting on a prompt).
                append("exec cat")
            }
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_NAME)} ${shellQuote(payload)}",
            )
            appendLine("sleep 2")
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
                    it.exec("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
                }
            }
        }
    }

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)

        var bitmap: Bitmap? = null
        compose.activityRule.scenario.onActivity { activity ->
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
        println("ISSUE717_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE717_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File =
        writeText("timings.txt", timings.joinToString(separator = "\n", postfix = "\n"))

    private fun writeSummary(): File =
        writeText(
            "summary.txt",
            buildString {
                appendLine("test=VoiceSendActivePaneStaysVisibleE2eTest")
                appendLine("issue=717")
                appendLine("fixture=tests/docker agents ($DEFAULT_HOST:$DEFAULT_PORT)")
                appendLine("running_on_ci=${TerminalTestTimeouts.isRunningOnCi()}")
                appendLine("session=$SESSION_NAME")
                appendLine("banner_marker=$BANNER_MARKER")
                appendLine(
                    "scenario=attach idle full-screen pane, model composer/keyboard-dismiss " +
                        "reflow black pane (2J + home) on the retained emulator, drive the " +
                        "same-dimension resize short-circuit heal",
                )
                appendLine(
                    "expectation=active pane re-seeded to the full banner (NOT black), " +
                        "no Reconnecting/Disconnected/Attaching surface",
                )
                appendLine("timings:")
                timings.forEach { appendLine("  $it") }
            },
        )

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
        println("ISSUE717_TIMING $line")
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
        const val LOG_TAG: String = "Issue717VoiceSendBlackPane"
        const val DEVICE_DIR_NAME: String = "issue717-voice-send-black-pane"
        const val SESSION_NAME: String = "issue717-voicesend-proof"
        const val BANNER_MARKER: String = "ISSUE717-BANNER"
        const val LIVE_LINE_MARKER: String = "ISSUE717-LIVELINE"

        const val OVERLAY_WATCH_MS: Long = 2_500L

        // SHORT restore window: the slice-2 active-pane heal re-captures the pane within
        // a single `capture-pane` round-trip. Kept well below the idle passive-disconnect
        // timeout so a later transport reattach (which would itself reseed) cannot mask a
        // missing heal on base. CI gets a little more headroom for the busier AVD.
        val HEAL_RESTORE_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 10_000L else 6_000L

        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
