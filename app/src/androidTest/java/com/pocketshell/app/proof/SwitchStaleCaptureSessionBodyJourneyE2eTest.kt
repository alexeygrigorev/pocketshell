package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import com.pocketshell.app.proof.signals.waitForSessionInPicker
import com.pocketshell.app.tmux.TMUX_COMPACT_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_CONSOLIDATED_SESSION_LABEL_TAG
import com.pocketshell.app.tmux.TMUX_FULL_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #686/#658 (epic #687 Phase 0, J3) — DEVICE-TRUTH journey: a switch
 * A→B while A is still emitting late frames must show the CORRECT session B in
 * BOTH the header label AND the rendered PANE BODY, with NO stray
 * `SessionBoundaryDivider(sessionName=A)` (TmuxSessionScreen.kt:1472) painting
 * the non-target session.
 *
 * ## Why the existing #686 CI test misses the maintainer's bug
 *
 * [MultiSessionSwitchJourneyE2eTest.backToPickerThenOpenShowsSingleTargetIdentityNeverStaleProjectCrumb]
 * (the #686 slice-1 test) samples HEADER TEXT NODES only — the session label and
 * the project crumb. It never reads the PANE BODY, and it uses a deterministic
 * fixture (each session idle after its seed) that hides the late-frame race. The
 * maintainer's actual report is the PANE showing the wrong session's content
 * (and a stray mid-pane session-boundary label) after a switch — a content-level
 * desync the header-only test cannot catch.
 *
 * ## How this reproduces the late/stale capture from A
 *
 * Session A runs a CONTINUOUS high-rate output stream (a fast counter), so A's
 * `-CC` runtime keeps producing `%output` frames. The test then switches A→B
 * repeatedly. A late frame from A's still-live stream can land after the switch
 * to B (the #634 content-bleed / #658 wrong-session race). The assertion reads
 * the PANE BODY (the rendered terminal transcript) and the full visible text
 * tree, asserting:
 *   (a) the header session label = B (never A);
 *   (b) the PANE BODY shows B's unique marker and NEVER A's unique marker
 *       (no late A frame bled into the shown pane);
 *   (c) NO visible text node bears A's session NAME — the
 *       `SessionBoundaryDivider(sessionName=A)` is a plain Text node painting
 *       the non-target session label mid-pane; its presence is the #686 stray
 *       boundary regression.
 *
 * Uses ONLY the deterministic `agents` fixture (host port 2222), so it RUNS on
 * the per-PR CI emulator-journey job — no toxiproxy, no
 * `Assume.assumeFalse(isRunningOnCi())`.
 *
 * ## Fail-first
 *
 * On base `origin/main` a late A frame can bleed into the shown pane and/or a
 * stale boundary divider bearing A's name can paint while A's runtime is still
 * resolving — RED on assertion (b) or (c). The Phase-1 screen-keyed-to-target
 * fix (filter panes + divider on the target sessionId, land the reveal state
 * machine) flips it GREEN.
 */
@RunWith(AndroidJUnit4::class)
class SwitchStaleCaptureSessionBodyJourneyE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val timings = mutableListOf<String>()

    private val pickerWaitMs: Long =
        if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
        runBlocking { runCatching { cleanupSeededSessions(readFixtureKey()) } }
    }

    @Test
    fun switchToBWhileAStillStreamsShowsBInBodyAndHeaderNoStaleBoundary() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        // Seed A (idle marker) + B (idle marker). A's continuous stream is
        // STARTED AFTER attach (a sidecar trigger) so the picker enumeration
        // sees a quiet session — a session producing constant output at
        // picker-time stalls the in-emulator `list-sessions` enumeration (#470),
        // which is setup noise, not the device-truth assertion we want to test.
        seedStreamingAndTargetSessions(key)
        val hostRowTag = seedDockerHost(key)
        forceFlatHostDetailViewMode()

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // Attach to A first.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForSessionInPicker(
            rule = compose,
            sessionName = SESSION_A,
            timeoutMs = pickerWaitMs,
            onRepoke = { repokeFolderListFromHostRow(hostRowTag) },
        )
        compose.onNodeWithText(SESSION_A, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForTerminalContains(SESSION_A_MARKER, "initial attach to A")

        // NOW start A's continuous stream over a sidecar tmux send-keys. From
        // here A keeps emitting `%output`, so each subsequent A→B switch races a
        // late/stale capture from A.
        startSessionAStream(key)
        waitForTerminalContains(SESSION_A_STREAM_PREFIX, "A streaming after sidecar trigger")
        captureViewport("issue686j3-00-attached-$SESSION_A")

        // Switch A→B several times. A keeps streaming the whole time, so each
        // switch races a late A frame. After EACH switch assert the device truth.
        for (step in 1..SWITCH_ROUNDS) {
            switchToBAndAssertCorrectSessionBody(step)
            // Return to A (also streaming) so the next round re-races the switch.
            if (step < SWITCH_ROUNDS) {
                switchBackToA(step)
            }
        }

        writeSummary()
        writeTimings()
        Unit
    }

    /**
     * Switch A→B (Back → picker → tap B), then assert from the rendered PANE
     * BODY + visible text tree that B (and only B) is shown.
     */
    private fun switchToBAndAssertCorrectSessionBody(step: Int) {
        Log.i(LOG_TAG, "switch step=$step $SESSION_A -> $SESSION_B")
        val switchAt = SystemClock.elapsedRealtime()
        clickTmuxBack()
        waitForSessionInPicker(rule = compose, sessionName = SESSION_B, timeoutMs = pickerWaitMs)
        compose.onNodeWithText(SESSION_B, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()

        // (b) PANE BODY shows B's marker (correct + re-seeded, non-stale).
        waitForTerminalContains(SESSION_B_MARKER, "step$step switch to B body shows B")
        recordTiming("switch_${step}_a_to_b_ms", SystemClock.elapsedRealtime() - switchAt)
        captureViewport("issue686j3-${"%02d".format(step)}-switched-to-$SESSION_B")

        // Watch the pane body + header + visible text tree CONTINUOUSLY across a
        // settle window — the late A frame / stray boundary appears transiently
        // during the reveal, so sampling once at rest would miss it.
        val watchDeadline = SystemClock.elapsedRealtime() + SETTLE_WATCH_MS
        val staleBodySightings = mutableListOf<String>()
        val staleBoundarySightings = mutableListOf<String>()
        val staleHeaderSightings = mutableListOf<String>()
        while (SystemClock.elapsedRealtime() < watchDeadline) {
            val body = visibleTerminalText()
            // (b) A's streaming marker must NOT bleed into the shown B pane body.
            if (body.contains(SESSION_A_STREAM_PREFIX)) {
                staleBodySightings.add(body.lineSequence().lastOrNull { it.contains(SESSION_A_STREAM_PREFIX) } ?: body.take(120))
            }
            // (c) No visible text node may bear A's SESSION NAME — that is the
            // stray `SessionBoundaryDivider(sessionName=A)` mid-pane label.
            val visibleTexts = allVisibleTexts()
            if (visibleTexts.any { it == SESSION_A }) {
                staleBoundarySightings.add("nodes=${visibleTexts.filter { it == SESSION_A }}")
            }
            // (a) The header session label must read B, never A.
            val headerLabel = headerSessionCrumbText() ?: ""
            if (headerLabel.contains(SESSION_A)) {
                staleHeaderSightings.add("label='$headerLabel'")
            }
            SystemClock.sleep(40)
        }
        recordTiming("switch_${step}_stale_body_sightings", staleBodySightings.size.toLong())
        recordTiming("switch_${step}_stale_boundary_sightings", staleBoundarySightings.size.toLong())
        recordTiming("switch_${step}_stale_header_sightings", staleHeaderSightings.size.toLong())

        // (b) The shown pane body must NEVER show A's live streaming content.
        assertFalse(
            "step$step switch to $SESSION_B: the rendered PANE BODY must NEVER show " +
                "session A's live streaming marker '$SESSION_A_STREAM_PREFIX' — a late A " +
                "frame bleeding into the shown B pane is the #634/#658 wrong-session " +
                "regression. Sightings: $staleBodySightings",
            staleBodySightings.isNotEmpty(),
        )
        // (c) No stray SessionBoundaryDivider bearing A's session name.
        assertTrue(
            "step$step switch to $SESSION_B: NO visible text node may bear session A's " +
                "name '$SESSION_A' — a `SessionBoundaryDivider(sessionName=$SESSION_A)` " +
                "label painting the non-target session mid-pane is the #686 stray-boundary " +
                "regression. Sightings: $staleBoundarySightings",
            staleBoundarySightings.isEmpty(),
        )
        // (a) The header session label must read B, never A.
        assertTrue(
            "step$step switch to $SESSION_B: the header session label " +
                "('$TMUX_CONSOLIDATED_SESSION_LABEL_TAG') must read the TARGET '$SESSION_B', " +
                "never the leaving session '$SESSION_A'. Sightings: $staleHeaderSightings",
            staleHeaderSightings.isEmpty(),
        )

        // Steady-state header label is B.
        val finalLabel = headerSessionCrumbText() ?: ""
        assertTrue(
            "step$step switch to $SESSION_B: the header session label must read " +
                "'$SESSION_B' at rest; got '$finalLabel'",
            finalLabel.contains(SESSION_B),
        )
    }

    private fun switchBackToA(step: Int) {
        clickTmuxBack()
        waitForSessionInPicker(rule = compose, sessionName = SESSION_A, timeoutMs = pickerWaitMs)
        compose.onNodeWithText(SESSION_A, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForTerminalContains(SESSION_A_STREAM_PREFIX, "step$step return to streaming A")
    }

    // ---------------------------------------------------------------- Helpers

    private fun headerSessionCrumbText(): String? {
        val nodes = compose.onAllNodesWithTag(
            TMUX_CONSOLIDATED_SESSION_LABEL_TAG,
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        val node = nodes.firstOrNull() ?: return null
        val texts = node.config.getOrNull(SemanticsProperties.Text) ?: return null
        return texts.joinToString(separator = "") { it.text }
    }

    /** Every visible text fragment in the whole semantics tree. */
    private fun allVisibleTexts(): List<String> {
        val root = compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull() ?: return emptyList()
        return collectTexts(root)
    }

    private fun collectTexts(
        node: androidx.compose.ui.semantics.SemanticsNode,
    ): List<String> {
        val here = node.config.getOrNull(SemanticsProperties.Text)
            ?.map { it.text }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        return here + node.children.flatMap { collectTexts(it) }
    }

    private fun clickTmuxBack() {
        val tags = listOf(
            TMUX_COMPACT_CHROME_BACK_BUTTON_TAG,
            TMUX_FULL_CHROME_BACK_BUTTON_TAG,
        )
        for (tag in tags) {
            if (compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            ) {
                compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
                return
            }
        }
        compose.onNodeWithTag(TMUX_FULL_CHROME_BACK_BUTTON_TAG, useUnmergedTree = true)
            .performClick()
    }

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
                name = "issue686j3-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue686J3 Stale Capture",
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

    /**
     * Seed A and B as QUIET interactive shells (each printing its own idle
     * marker). A's continuous stream is NOT started here — it is triggered by
     * [startSessionAStream] AFTER attach, so the picker enumeration sees a quiet
     * A (a session producing constant output at picker-time stalls the #470
     * `list-sessions` enumeration — setup noise, not the assertion under test).
     */
    private suspend fun seedStreamingAndTargetSessions(key: String) {
        val script = buildString {
            appendLine("set -eu")
            listOf(SESSION_A, SESSION_B).forEach { name ->
                appendLine("tmux kill-session -t ${shellQuote(name)} 2>/dev/null || true")
            }
            appendLine(
                "tmux list-sessions -F '#{session_name}' 2>/dev/null | " +
                    "grep -vx ${shellQuote(SESSION_A)} | grep -vx ${shellQuote(SESSION_B)} | " +
                    "while IFS= read -r s; do tmux kill-session -t \"\$s\" 2>/dev/null || true; done",
            )
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_A)} " +
                    shellQuote("printf '$SESSION_A_MARKER\\n'; exec sh"),
            )
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_B)} " +
                    shellQuote("printf '$SESSION_B_MARKER\\n'; exec sh"),
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
        ).mapCatching { session -> session.use { it.exec(script) } }
        val exec = result.getOrNull()
        assertTrue(
            "expected target tmux seeding to succeed; " +
                "exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded target sessions: ${exec?.stdout?.trim()}")
    }

    /**
     * Start A's continuous output stream via a sidecar `tmux send-keys` so A's
     * `-CC` runtime keeps emitting `%output` for the rest of the journey. This
     * is what makes a late/stale capture from A able to land after a switch to
     * B (the #634/#658 race). Triggered AFTER attach so the picker enumeration
     * never had to enumerate a busy session.
     */
    private suspend fun startSessionAStream(key: String) {
        val loop =
            "i=0; while true; do printf '$SESSION_A_STREAM_PREFIX %d\\n' \"\$i\"; " +
                "i=\$((i+1)); sleep 0.1; done"
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
                    "tmux send-keys -t ${shellQuote(SESSION_A)} ${shellQuote(loop)} Enter",
                )
            }
        }
        val exec = result.getOrNull()
        assertTrue(
            "expected A stream trigger to succeed; exception=${result.exceptionOrNull()} " +
                "stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "started A stream via sidecar send-keys")
    }

    private fun repokeFolderListFromHostRow(hostRowTag: String) {
        runCatching {
            val backTags = listOf(
                TMUX_COMPACT_CHROME_BACK_BUTTON_TAG,
                TMUX_FULL_CHROME_BACK_BUTTON_TAG,
            )
            for (tag in backTags) {
                if (compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                ) {
                    compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
                    break
                }
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        }
    }

    private suspend fun cleanupSeededSessions(key: String) {
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
                    it.exec(
                        listOf(SESSION_A, SESSION_B).joinToString(separator = "; ") { name ->
                            "tmux kill-session -t ${shellQuote(name)} 2>/dev/null || true"
                        },
                    )
                }
            }
        }
    }

    private fun forceFlatHostDetailViewMode() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        appContext
            .getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("host_detail_view_mode", "Flat")
            .commit()
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

    private fun waitForTerminalContains(
        expected: String,
        label: String,
        timeoutMillis: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
    ) {
        var last = ""
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                last = visibleTerminalText()
                last.contains(expected)
            }
            true
        }.getOrDefault(false)
        if (!satisfied) writeText("failure-$label-visible-terminal.txt", last)
        assertTrue(
            "expected visible terminal for $label to contain '$expected' within " +
                "${timeoutMillis}ms; got:\n$last",
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
        println("ISSUE686J3_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE686J3_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE686J3_TIMINGS ${file.absolutePath}")
        return file
    }

    private fun writeSummary(): File {
        val file = artifactFile("summary.txt")
        file.writeText(
            buildString {
                appendLine("test=SwitchStaleCaptureSessionBodyJourneyE2eTest")
                appendLine("issue=686,658")
                appendLine("fixture=tests/docker agents ($DEFAULT_HOST:$DEFAULT_PORT)")
                appendLine("running_on_ci=${TerminalTestTimeouts.isRunningOnCi()}")
                appendLine("session_a=$SESSION_A (continuous stream)")
                appendLine("session_b=$SESSION_B (idle target)")
                appendLine("a_stream_prefix=$SESSION_A_STREAM_PREFIX")
                appendLine("b_marker=$SESSION_B_MARKER")
                appendLine(
                    "scenario=switch A->B while A keeps streaming (late/stale " +
                        "capture from A races the switch)",
                )
                appendLine(
                    "expectation=header label B + pane BODY shows B (never A) + " +
                        "no stray SessionBoundaryDivider(sessionName=A)",
                )
                appendLine("timings:")
                timings.forEach { appendLine("  $it") }
            },
        )
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
        println("ISSUE686J3_TIMING $line")
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
        const val DEVICE_DIR_NAME: String = "issue686j3-switch-stale-capture-body"
        const val LOG_TAG: String = "Issue686J3StaleCapture"

        const val SESSION_A: String = "issue686j3-session-a"
        const val SESSION_B: String = "issue686j3-session-b"

        // A's continuous stream prefix — must NEVER appear in the shown B pane.
        const val SESSION_A_STREAM_PREFIX: String = "ASTREAM"
        const val SESSION_A_MARKER: String = "AAA-J3-686"
        const val SESSION_B_MARKER: String = "BBB-J3-686"

        const val SWITCH_ROUNDS: Int = 3
        const val SETTLE_WATCH_MS: Long = 3_000L
    }
}
