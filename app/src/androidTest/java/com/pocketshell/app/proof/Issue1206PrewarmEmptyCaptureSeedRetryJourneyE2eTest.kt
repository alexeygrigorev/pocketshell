package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeWithVelocity
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.signals.waitForSessionInPicker
import com.pocketshell.app.tmux.PrewarmSeedFaultTestOverride
import com.pocketshell.app.tmux.TMUX_COMPACT_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_CONSOLIDATED_SESSION_LABEL_TAG
import com.pocketshell.app.tmux.TMUX_FULL_BREADCRUMB_TAG
import com.pocketshell.app.tmux.TMUX_FULL_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_PAGER_OVERLAY_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_PAGER_PAGE_TAG_PREFIX
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
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #1206 (AC4) — DEVICE-TRUTH journey: a fresh pane whose FIRST
 * `capture-pane` seed comes back EMPTY on a busy shared `-CC` channel still
 * lands on a PAINTED Terminal grid, not fragments-over-black.
 *
 * ## Why this must inject the failing state synthetically (the #780 model)
 *
 * The maintainer's report is a fresh Claude session's first alt-screen frame
 * being unrecoverable because the prewarm seed `capture-pane` came back empty
 * (or the acquire wedged) on a startup-flooded `-CC` channel — even though the
 * pane HAS content. The happy real-agent workbench structurally CANNOT enter
 * that empty/wedged-first-capture state (a healthy fixture always captures
 * content on the first try), which is exactly why AC4 permits — and this test
 * uses — a #780 synthetic injection: [PrewarmSeedFaultTestOverride] forces the
 * FIRST prewarm seed capture (after the real wire round-trip ran) to be TREATED
 * as empty. Production never arms it (default 0), so this is a pure test hook.
 *
 * ## The journey
 *
 *  1. Seed exactly two same-host sessions A (attach) and B (the prewarm target),
 *     each printing a unique marker, killing every other session so the switcher
 *     lists only A + B (prewarm then targets exactly B's single pane).
 *  2. Attach to A.
 *  3. Open the in-session session switcher (swipe-down) — this fires
 *     `prewarmLikelySwitchTargets`, prewarming B. The armed override forces B's
 *     FIRST seed capture empty, driving the #1206 retry / deferred-reseed
 *     recovery against a real pane that HAS content.
 *  4. HARD-assert the injected fault was actually consumed by the prewarm seed
 *     path (`PrewarmSeedFaultTestOverride.consumedCount >= 1`) — this is NOT a
 *     vacuous pass: the #1206 code path provably ran.
 *  5. Swipe forward to B (only two sessions → deterministically lands on B) and
 *     HARD-assert B's terminal shows B's marker and is NOT blank — i.e. the
 *     background retry / deferred reseed painted the full grid over what the
 *     empty first capture would otherwise have left blank.
 *
 * ## Fail-first note (honest)
 *
 * The clean red→green for the retry logic is proven at the JVM layer
 * (`TmuxSessionViewModelTest.prewarmSeedRetries*` — the grid stays blank without
 * the retry, seeds with it). This connected journey is the on-device GREEN
 * acceptance G4/G10 require for the black-screen fix: with the empty-first-
 * capture injected, the user still lands on a painted B grid. (On the emulator,
 * independent post-reveal blank-heal safety nets could also eventually repaint,
 * so this test's teeth are the load-bearing "B is painted" assertion PLUS the
 * hard "the #1206 fault was consumed" assertion, not a device red.)
 *
 * Uses ONLY the deterministic `agents` fixture (host port 2222), so it RUNS on
 * the per-PR CI emulator-journey job — no toxiproxy, no
 * `Assume.assumeFalse(isRunningOnCi())`, no self-skip.
 */
@RunWith(AndroidJUnit4::class)
class Issue1206PrewarmEmptyCaptureSeedRetryJourneyE2eTest {

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

    private val pickerWaitMs: Long =
        if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L

    private val pickerReadyWaitMs: Long =
        if (TerminalTestTimeouts.isRunningOnCi()) 120_000L else 45_000L

    @After
    fun tearDown() {
        // Always disarm the process-global seam so it never leaks into a sibling
        // test in the same instrumentation process.
        PrewarmSeedFaultTestOverride.clear()
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        seededKey?.let { key ->
            runBlocking { runCatching { cleanupSeededSessions(key) } }
        }
    }

    @Test
    fun freshPaneWithEmptyFirstCaptureStillLandsOnPaintedGrid() { runBlocking {
        val hostRowTag = requireNotNull(seededHostRowTag) { "seed-before-launch host row missing" }

        // ---- (1) Attach to A.
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
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
        captureViewport("issue1206-00-attached-$SESSION_A")

        // ---- (2) Open the in-session session switcher (swipe-down). This fires
        // prewarmLikelySwitchTargets → prewarms B, whose FIRST seed capture the
        // armed override forces empty (the #1206 target state).
        openSessionSwitcherViaSwipeDown()
        waitForPagerReady()
        // The picker must have enumerated B, otherwise prewarm has no target to
        // seed (prewarmLikelySwitchTargets filters to non-current rows).
        waitForSessionInPager(SESSION_B)

        // ---- (3) HARD-assert the injected empty-first-capture fault was actually
        // consumed by B's prewarm seed path — proves the #1206 code path ran (no
        // vacuous pass). Re-poke the switcher once if prewarm has not fired yet.
        val consumed = waitForForcedEmptyConsumed()
        assertTrue(
            "the forced empty-first-capture fault must be consumed by the prewarm seed path " +
                "(the #1206 retry path must have run); consumedCount=" +
                "${PrewarmSeedFaultTestOverride.consumedCount} seedAttemptCount=" +
                "${PrewarmSeedFaultTestOverride.seedAttemptCount} (attempt=0 => prewarm never " +
                "seeded B; attempt>0 => seeded but seam not hit)",
            consumed,
        )
        recordTiming("forced_empty_consumed_count", PrewarmSeedFaultTestOverride.consumedCount.toLong())

        // ---- (4) Switch to B via the pager. Only two sessions exist, so a
        // forward swipe deterministically lands on B (the prewarmed target).
        val landed = swipeSessionPagerForwardOnce(captureOpenAs = "issue1206-01-pager-open")
        assertTrue(
            "forward session-pager swipe must land on the prewarmed target '$SESSION_B'; " +
                "landed on '$landed'",
            landed == SESSION_B,
        )
        waitForTerminalViewAttached()

        // ---- (5) LOAD-BEARING: B lands on a PAINTED grid — its marker visible,
        // NOT blank — despite the empty first prewarm capture. The #1206 retry /
        // deferred reseed recovered the full grid; without it the prewarmed grid
        // would stay empty (fragments-over-black).
        waitForTerminalContains(SESSION_B_MARKER, "B painted after empty-first-capture prewarm")
        val bText = visibleTerminalText()
        assertFalse(
            "B's terminal must NOT be blank after an empty-first-capture prewarm — the #1206 " +
                "retry/deferred-reseed must paint the full grid (transcript='${bText.take(200)}')",
            bText.isBlank(),
        )
        captureViewport("issue1206-02-$SESSION_B-painted")

        writeSummary(consumedCount = PrewarmSeedFaultTestOverride.consumedCount)
        writeTimings()
        Unit
    } }

    // ---------------------------------------------------------------- Gestures

    /**
     * Open the session pager with a swipe-DOWN on the top chrome, then wait for
     * the overlay to appear. Copied from the #237 swipe-switch journey — the
     * swipe surface wraps only the ~56dp top chrome, so an explicit multi-step
     * downward drag clears the 72.dp open threshold.
     */
    private fun openSessionSwitcherViaSwipeDown() {
        compose.onNodeWithTag(TMUX_FULL_BREADCRUMB_TAG, useUnmergedTree = true)
            .performTouchInput {
                down(Offset(centerX, centerY))
                repeat(SWIPE_DOWN_STEPS) { moveBy(Offset(0f, SWIPE_DOWN_STEP_PX)) }
                up()
            }
        compose.waitUntil(timeoutMillis = pickerWaitMs) {
            compose.onAllNodesWithTag(TMUX_SESSION_PAGER_OVERLAY_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        captureFullFrame("issue1206-switcher-open-fullframe")
    }

    /** Wait for the session pager to reach `Ready` (remote list-sessions resolved). */
    private fun waitForPagerReady() {
        val pickerReady = runCatching {
            compose.waitUntil(timeoutMillis = pickerReadyWaitMs) {
                val readyStatusVisible = compose
                    .onAllNodes(
                        hasText(READY_CURRENT_STATUS)
                            .and(hasAnyAncestor(hasTestTag(TMUX_SESSION_PAGER_OVERLAY_TAG))),
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes().isNotEmpty()
                val loadingGone = compose
                    .onAllNodesWithText(LOADING_PLACEHOLDER_STATUS, useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty()
                readyStatusVisible && loadingGone
            }
            true
        }.getOrDefault(false)
        if (!pickerReady) captureFullFrame("issue1206-FAIL-pager-not-ready-fullframe")
        assertTrue(
            "session pager never reached Ready within ${pickerReadyWaitMs}ms",
            pickerReady,
        )
    }

    /** Wait until [sessionName]'s card is present in the open session pager. */
    private fun waitForSessionInPager(sessionName: String) {
        val present = runCatching {
            compose.waitUntil(timeoutMillis = pickerReadyWaitMs) {
                compose.onAllNodes(
                    hasText(sessionName)
                        .and(hasAnyAncestor(hasTestTag(TMUX_SESSION_PAGER_OVERLAY_TAG))),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty()
            }
            true
        }.getOrDefault(false)
        if (!present) captureFullFrame("issue1206-FAIL-$sessionName-not-in-pager-fullframe")
        assertTrue(
            "session '$sessionName' must be enumerated in the switcher pager so prewarm can " +
                "target it (the picker's remote list-sessions must include it)",
            present,
        )
    }

    /**
     * Poll until the prewarm seed path has consumed the injected empty-capture
     * fault (or the budget elapses). The prewarm fires when the switcher opens,
     * so this must be awaited AFTER [openSessionSwitcherViaSwipeDown]. The
     * switcher is held OPEN and untouched for the whole window: any interaction
     * (dismiss/re-open) re-emits the picker state, which cancels an in-flight
     * `prewarmRuntime` before it can build + seed the target — so we wait quietly
     * and let a stable prewarm complete.
     */
    private fun waitForForcedEmptyConsumed(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + pickerReadyWaitMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (PrewarmSeedFaultTestOverride.consumedCount >= 1) return true
            SystemClock.sleep(100)
        }
        return PrewarmSeedFaultTestOverride.consumedCount >= 1
    }

    /**
     * Swipe the session pager one page forward and return the session the app
     * lands on (read from the top-chrome session label). Copied from the #237
     * swipe-switch journey.
     */
    private fun swipeSessionPagerForwardOnce(captureOpenAs: String): String {
        captureFullFrame(captureOpenAs)
        val previous = readActiveSessionName()
        val currentPageIndex = onScreenPageIndex()
        compose.onNodeWithTag(
            "$TMUX_SESSION_PAGER_PAGE_TAG_PREFIX$currentPageIndex",
            useUnmergedTree = true,
        ).performTouchInput {
            val midY = centerY
            swipeWithVelocity(
                start = Offset(right - SWIPE_PAGER_EDGE_INSET_PX, midY),
                end = Offset(left + SWIPE_PAGER_EDGE_INSET_PX, midY),
                endVelocity = SWIPE_PAGER_VELOCITY,
                durationMillis = SWIPE_PAGER_DURATION_MS,
            )
        }
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        return waitForActiveSessionChange(previous = previous)
    }

    private fun onScreenPageIndex(): Int {
        var bestIndex = 1
        var bestWidth = 0f
        for (index in 1..MAX_PAGER_PAGES_PROBE) {
            val nodes = compose.onAllNodesWithTag(
                "$TMUX_SESSION_PAGER_PAGE_TAG_PREFIX$index",
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            val node = nodes.firstOrNull() ?: continue
            val width = node.boundsInRoot.width
            if (width > bestWidth) {
                bestWidth = width
                bestIndex = index
            }
        }
        return bestIndex
    }

    private fun readActiveSessionName(): String? {
        val nodes = compose.onAllNodesWithTag(
            TMUX_CONSOLIDATED_SESSION_LABEL_TAG,
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        val node = nodes.firstOrNull() ?: return null
        return node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
    }

    private fun waitForActiveSessionChange(previous: String?): String {
        var current: String? = null
        compose.waitUntil(timeoutMillis = pickerWaitMs) {
            current = readActiveSessionName()
            current != null && current != previous
        }
        return requireNotNull(current) {
            "active session label never changed away from '$previous'"
        }
    }

    // ---------------------------------------------------------------- Seeding

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
            seedTwoSessions(key)
            seededHostRowTag = seedDockerHost(key)
            forceFlatHostDetailViewMode()
            // Arm the #1206 synthetic seam BEFORE launch: the FIRST prewarm seed
            // capture is treated as empty (a wedged/empty first `capture-pane`).
            PrewarmSeedFaultTestOverride.setForcedEmptyFirstCaptures(1)
        } catch (t: Throwable) {
            PrewarmSeedFaultTestOverride.clear()
            runCatching { cleanupSeededSessions(key) }
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
                name = "issue1206-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue1206 Prewarm Empty Capture",
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
     * Seed EXACTLY two same-host sessions (A + B), each printing its own idle
     * marker, and kill every other session so the switcher lists only A + B —
     * making the prewarm target exactly B's single pane (so the process-global
     * forced-empty budget lands on B).
     */
    private suspend fun seedTwoSessions(key: String) {
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
            "expected two-session seeding to succeed; " +
                "exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded sessions: ${exec?.stdout?.trim()}")
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
            compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
                runCatching {
                    compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }.getOrDefault(false)
            }
            compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        }
    }

    // ---------------------------------------------------------------- Terminal

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

    // ---------------------------------------------------------------- Artifacts

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

    private fun captureFullFrame(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        var bitmap: Bitmap? = null
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            bitmap = b
        }
        bitmap?.let { writeBitmap(name, it) }
        bitmap?.recycle()
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE1206_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE1206_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE1206_TIMINGS ${file.absolutePath}")
        return file
    }

    private fun writeSummary(consumedCount: Int): File {
        val file = artifactFile("summary.txt")
        file.writeText(
            buildString {
                appendLine("test=Issue1206PrewarmEmptyCaptureSeedRetryJourneyE2eTest")
                appendLine("issue=1206")
                appendLine("fixture=tests/docker agents ($DEFAULT_HOST:$DEFAULT_PORT)")
                appendLine("running_on_ci=${TerminalTestTimeouts.isRunningOnCi()}")
                appendLine("session_a=$SESSION_A (attach)")
                appendLine("session_b=$SESSION_B (prewarm target)")
                appendLine("b_marker=$SESSION_B_MARKER")
                appendLine("forced_empty_first_captures=1")
                appendLine("forced_empty_consumed_count=$consumedCount")
                appendLine(
                    "scenario=prewarm B with an injected empty FIRST capture-pane; the " +
                        "#1206 retry/deferred-reseed must still paint B's grid",
                )
                appendLine(
                    "expectation=B's terminal shows '$SESSION_B_MARKER' and is NOT blank " +
                        "after switching to it (painted grid, not fragments-over-black)",
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
        println("ISSUE1206_TIMING $line")
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
        const val DEVICE_DIR_NAME: String = "issue1206-prewarm-empty-capture-seed-retry"
        const val LOG_TAG: String = "Issue1206PrewarmSeed"

        // Host/port/user come from the package-level DEFAULT_HOST/DEFAULT_PORT/
        // DEFAULT_USER (backed by AgentsFixtureTarget), so a per-lane pool run
        // points this journey at its own agents container.

        const val SESSION_A: String = "issue1206-session-a"
        const val SESSION_B: String = "issue1206-session-b"
        const val SESSION_A_MARKER: String = "A1206-READY"
        const val SESSION_B_MARKER: String = "B1206-READY"

        const val SWIPE_DOWN_STEPS: Int = 8
        const val SWIPE_DOWN_STEP_PX: Float = 80f
        const val SWIPE_PAGER_EDGE_INSET_PX: Float = 20f
        const val SWIPE_PAGER_VELOCITY: Float = 2_000f
        const val SWIPE_PAGER_DURATION_MS: Long = 250L
        const val MAX_PAGER_PAGES_PROBE: Int = 24

        const val LOADING_PLACEHOLDER_STATUS: String = "loading same-host sessions"
        const val READY_CURRENT_STATUS: String = "current"
    }
}
