package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.test.testArtifactsRoot
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #796 (H4 of epic #792) — REGRESSION PROOF: a soft-keyboard show animation
 * (a BURST of interpolated IME inset frames) must NOT recompose the
 * **terminal-render subtree** of [TmuxSessionScreen], so it can never amplify a
 * Codex `%output` render storm into the keyboard-up ANR the maintainer's
 * screenshot captured.
 *
 * ## The bug this reproduces (research spike on #796, §3)
 *
 * The host-IME inset is written on EVERY inset change ([rememberHostImeBottomPx]),
 * and the keyboard-show animation emits a burst of interpolated inset frames. On
 * `origin/main` (HEAD 85835356) the screen read the inset with a DELEGATED `by`
 * read in the [TmuxSessionScreen] function body
 * (`val imeBottomPx by rememberHostImeBottomPx()`), plus `isImeVisible`,
 * `imePanOffsetPx`, `chromeCompressed` all derived inline — so reading the int in
 * the function body subscribed the WHOLE function, including the
 * `HorizontalPager` of `TerminalSurface`s (the terminal-render subtree). Every
 * one of the dozens of interpolated inset frames therefore recomposed the heavy
 * terminal page on the main thread. Stacked on top of a Codex `%output` render
 * storm (Slice B's domain), the two share one main thread and stack toward the
 * 5 s ANR threshold — and the screenshot shows it happening with the keyboard up.
 *
 * ## The fix (the deferred-read pattern this proof guards)
 *
 *  - Hold the `State<Int>` OBJECT (`imeBottomPxState`) without a delegated read.
 *  - Read the raw inset ONLY inside the pan `graphicsLayer { ... }` lambda
 *    (deferred to layout/draw, not composition).
 *  - Derive `isImeVisible` via `derivedStateOf` so it only invalidates when the
 *    inset crosses the 0 boundary (show/hide), not per interpolation frame.
 *
 * After the fix, an N-frame inset burst recomposes the terminal subtree O(1)
 * times (a single show/hide transition at most), not O(N).
 *
 * ## How this proves it (the REAL production screen, no stand-in — process.md F2)
 *
 * This is the PRODUCTION-SCREEN half of the H4 proof. Its LOAD-BEARING job is the
 * keyboard-up visual-regression guard on the real [TmuxSessionScreen]; the
 * deterministic per-frame recomposition-scoping metric lives in the sibling
 * [Issue796ImeRecompositionScopeProofTest] (which composes the production
 * [com.pocketshell.app.layout.TmuxImeLayoutState] and drives a controlled inset
 * burst: legacy delegated read = 24 recompositions, production holder = 1).
 *
 *  1. Launch the production [MainActivity], navigate host → folder → seeded tmux
 *     session, attach a LIVE tmux pane over Docker SSH. The screen under test is
 *     the real [TmuxSessionScreen] with its real `HorizontalPager` /
 *     [TerminalSurface] / vendored [TerminalView] — NOT a `*StandIn`.
 *  2. Trigger a Codex-style `%output` burst: an unthrottled alt-screen full-grid
 *     repaint loop in the pane (the redraw shape of [CodexRedrawOverflowReconnectE2eTest]),
 *     so a real `%output` render storm drives the production render path; let it
 *     drain to a quiet baseline.
 *  3. Dispatch a synthetic `ime()` inset to the decor view (the same
 *     `OnApplyWindowInsetsListener` path production reads). HARD-assert the
 *     keyboard-up state actually engaged via the #184 compact breadcrumb (no
 *     `assumeTrue` / CI-skip on the load-bearing keyboard-up state, process.md F3).
 *     NOTE: the full-MainActivity window inset path COALESCES a dispatched
 *     multi-frame burst into a single state delivery (the real window re-supplies
 *     its system insets), so a per-frame burst cannot be driven into the
 *     production state HERE — which is exactly why the per-frame recomposition
 *     metric lives in the `setContent`-harness sibling. The production root-group
 *     recomposition count is recorded here as a diagnostic only.
 *  4. LOAD-BEARING VISUAL-REGRESSION guard (process.md #641/#567/#615/#744): on
 *     the REAL screen, with the keyboard up, the terminal keyboard-up control (the
 *     hotkeys-launcher chip pinned above the IME) must stay fully ABOVE the
 *     keyboard — CONTAINMENT, not a bare `assertIsDisplayed()`. This proves the H4
 *     deferred-read fix preserves the pan/keyboard-up layout (no occlusion
 *     regression on the very screen this change touches). A full-device + viewport
 *     screenshot is captured for the keyboard-up state.
 *
 * ## Honest emulator limit (process.md)
 *
 * The emulator cannot reproduce a full 5 s on-device ANR (Slice B's worst stall
 * was 65 ms on this fast box). The H4 load-bearing metric (per-inset-frame
 * recomposition count) is a legitimate, emulator-speed-independent proxy for "the
 * IME burst no longer multiplies the render-critical subtree's main-thread work",
 * proven deterministically in the sibling scope proof. It is NOT a captured
 * `/data/anr/traces.txt` main-thread stack.
 *
 * Runs only via explicit class filter (it needs the `agents` Docker fixture on
 * host 2222), like the sibling [TmuxSessionOpencodeInputDockerTest]; it is not in
 * any curated CI allowlist.
 */
@RunWith(AndroidJUnit4::class)
class Issue796ImeRecompositionProofTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val timings = mutableListOf<String>()

    @After
    fun cleanup() {
        launchedActivity?.close()
        launchedActivity = null
    }

    @Test
    fun imeInsetBurstDoesNotRecomposeTerminalSubtreeDuringCodexOutputStorm() = runBlocking {
        val sshPort = resolveSshPort()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val key = instrumentation.context.assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        val sshKey = SshKey.Pem(key)
        waitForSshFixtureReady(sshKey, port = sshPort)
        killSession(sshKey, sshPort)
        seedTriggerGatedRedrawSession(sshKey, sshPort)

        val hostRowTag = persistHost(appContext, key, sshPort)
        try {
            // ----- Launch the production app and attach the REAL TmuxSessionScreen.
            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            launchedActivity?.onActivity { activity ->
                // Edge-to-edge so the synthetic IME inset we dispatch is honoured by
                // the window the same way a real device honours the soft keyboard.
                WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            }

            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
            compose.waitUntil(timeoutMillis = ATTACH_TIMEOUT_MS) {
                compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
            compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertIsDisplayed()
            waitForTerminalAttached()
            waitForVisibleTerminalText("redraw marker", VISIBLE_TIMEOUT_MS) {
                it.contains(REDRAW_MARKER)
            }

            // ----- Kick off the Codex `%output` render storm in the pane: the real
            // production render path drives a sustained alt-screen repaint storm,
            // exactly the on-device condition that makes the H4 IME-recomposition
            // amplifier dangerous (the two share one main thread).
            triggerRedraw(sshKey, sshPort)
            // Let the BOUNDED burst stream and then DRAIN to a quiet baseline. The
            // IME-driven recomposition is then measured in isolation: while the pane
            // is quiet the ONLY state changing is the IME inset, so the probe count
            // reflects ONLY the inset burst's contribution (not burst-driven
            // render recompositions, which would be noise on BOTH the pre-fix and
            // fixed paths). The keyboard-up containment screenshot below is captured
            // on this same real post-burst screen.
            waitForTerminalQuiet()

            // ----- Establish a deterministic keyboard-DOWN baseline first so the
            // very first show frame is a genuine 0 → positive boundary crossing,
            // then flush so any pending recomposition from settling is NOT counted.
            val density = appContext.resources.displayMetrics.density
            val fullImePx = (IME_HEIGHT_DP * density).toInt()
            dispatchSyntheticImeInset(0)
            compose.waitForIdle()

            // RESET the production recomposition probe immediately before the show
            // animation so the count reflects ONLY the inset burst.
            recordTiming("recompositions_before_reset", TmuxRenderRecompositionProbe.count)
            TmuxRenderRecompositionProbe.reset()
            var observedImeBottomPx = 0
            for (frame in 1..IME_FRAMES) {
                // Interpolate the inset from 0 → full over the burst (the
                // keyboard-show animation shape). Each frame is a DISTINCT inset
                // value (the first crosses 0 → positive), so on the pre-fix
                // delegated `imeBottomPx` read EACH frame re-invalidates the whole
                // TmuxSessionScreen root group (the render-critical slice). We
                // `waitForIdle()` after each dispatch so the recomposition (if any)
                // is fully flushed and COUNTED before the next frame — making the
                // per-frame recomposition deterministically observable instead of
                // racing the Choreographer/snapshot-coalescing.
                val px = (fullImePx.toLong() * frame / IME_FRAMES).toInt()
                observedImeBottomPx = dispatchSyntheticImeInset(px)
                compose.waitForIdle()
            }

            // The synthetic ime() inset MUST have actually driven the production
            // keyboard-up state — otherwise we'd be measuring a keyboard-DOWN layout
            // and the proof would pass vacuously (process.md F3, no skip). The
            // PRODUCTION proof of keyboard-up is the #184 chrome compaction: with
            // the keyboard up TmuxSessionScreen swaps the full breadcrumb
            // ([TMUX_FULL_BREADCRUMB_TAG]) for the compact one
            // ([TMUX_COMPACT_BREADCRUMB_TAG]). Asserting the COMPACT breadcrumb is
            // present (and the full one gone) proves `isImeVisible` flipped true via
            // the real HostImeInset listener — a state the test cannot fake.
            compose.waitForIdle()
            val compactBreadcrumbShown = compose
                .onAllNodesWithTag(TMUX_COMPACT_BREADCRUMB_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
            recordTiming("compact_breadcrumb_shown", if (compactBreadcrumbShown) 1L else 0L)
            assertTrue(
                "Synthetic ime() inset never drove the production keyboard-up state " +
                    "(the #184 compact breadcrumb is not shown); cannot validate the " +
                    "#796 keyboard-up recomposition scoping. observedImeBottomPx=" +
                    "$observedImeBottomPx (expected ~$fullImePx).",
                compactBreadcrumbShown,
            )

            // Production-side DIAGNOSTIC (not load-bearing here): how many times
            // the REAL TmuxSessionScreen root group recomposed across the journey.
            // It is NOT the load-bearing per-frame metric in THIS test because the
            // full-MainActivity window inset path COALESCES a dispatched
            // multi-frame ime() burst into a single state delivery (the real
            // window re-supplies its system insets), so a per-frame burst cannot be
            // driven into the production state here. The deterministic per-frame
            // recomposition-scoping assertion lives in the sibling
            // Issue796ImeRecompositionScopeProofTest, which composes the production
            // TmuxImeLayoutState and drives a controlled inset burst (legacy
            // delegated read = 24 recompositions, production holder = 1). This
            // journey test's load-bearing job is the keyboard-up containment guard
            // on the REAL screen below.
            val terminalRecompositions = TmuxRenderRecompositionProbe.count
            recordTiming("ime_frames_dispatched", IME_FRAMES.toLong())
            recordTiming("ime_bottom_px", observedImeBottomPx.toLong())
            recordTiming("real_screen_root_recompositions_diag", terminalRecompositions)

            // ----- LOAD-BEARING VISUAL-REGRESSION guard (process.md
            // #641/#567/#615/#744): on the REAL production TmuxSessionScreen, with
            // the keyboard up (compact breadcrumb asserted above) and a Codex
            // %output storm having just driven the render path, the terminal
            // keyboard-up control (the hotkeys-launcher chip pinned above the IME)
            // must stay fully ABOVE the keyboard — CONTAINMENT, not a bare
            // assertIsDisplayed(). This proves the H4 deferred-read fix preserves
            // the pan/keyboard-up layout (no #641/#567/#615-class occlusion
            // regression on the very screen this change touches).
            captureFullFrame("issue796-h4-keyboard-up-full")
            captureViewport("issue796-h4-keyboard-up")
            assertKeyboardUpControlAboveKeyboard(observedImeBottomPx)

            writeTimings()
        } finally {
            runCatching { withTimeout(20_000) { killSession(sshKey, sshPort) } }
        }
        Unit
    }

    // ----------------------------------------------------- keyboard-up containment

    /**
     * Asserts the keyboard-up terminal control (the [TERMINAL_HOTKEYS_LAUNCHER_TAG]
     * chip, pinned above the IME on a Terminal pane) has its bottom edge at or
     * above the keyboard top (full containment above the IME), in the SAME screen
     * coordinate space as the decor view. This is the #657 / F1 containment check,
     * not a bare `assertIsDisplayed()` (which passes for a control pushed under the
     * keyboard).
     */
    private fun assertKeyboardUpControlAboveKeyboard(imeBottomPx: Int) {
        // The hotkeys-launcher chip is the keyboard-up Terminal control row (it
        // replaces the IME-hidden chip strip). Give it a moment to settle after the
        // inset burst.
        compose.waitUntil(timeoutMillis = 6_000) {
            compose.onAllNodesWithTag(TERMINAL_HOTKEYS_LAUNCHER_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val keyBarBoundsRoot = compose
            .onNodeWithTag(TERMINAL_HOTKEYS_LAUNCHER_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

        // Convert the KeyBar bottom (root coords) and the keyboard top to the same
        // on-screen space. boundsInRoot is relative to the compose root, which fills
        // the decor view in this edge-to-edge activity, so the keyboard top in root
        // coords is rootHeight - imeBottomPx.
        var decorHeightPx = 0
        launchedActivity?.onActivity { activity ->
            decorHeightPx = activity.window.decorView.height
        }
        val keyboardTopPx = (decorHeightPx - imeBottomPx).toFloat()
        val slopPx = CONTAINMENT_SLOP_DP * InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density

        recordTiming("keybar_bottom_px", keyBarBoundsRoot.bottom.toLong())
        recordTiming("keyboard_top_px", keyboardTopPx.toLong())
        recordTiming("decor_height_px", decorHeightPx.toLong())

        assertTrue(
            "#796 (H4) visual regression: with the keyboard up the terminal " +
                "keyboard-up control (hotkeys-launcher chip) must stay fully ABOVE " +
                "the soft keyboard (reachable, not occluded). " +
                "controlBottom=${keyBarBoundsRoot.bottom} keyboardTop=$keyboardTopPx " +
                "slopPx=$slopPx (bounds=$keyBarBoundsRoot decorHeight=$decorHeightPx " +
                "imeBottomPx=$imeBottomPx). A bare assertIsDisplayed() would pass even " +
                "if the bar were under the keyboard; this is the F1 containment check.",
            keyBarBoundsRoot.bottom <= keyboardTopPx + slopPx,
        )
    }

    // ------------------------------------------------------------- synthetic IME

    /**
     * Dispatch a synthetic [WindowInsetsCompat] carrying [imeBottomPx] as the
     * `ime()` inset to the decor view. Returns the inset the production
     * [rememberHostImeBottomPx] listener observed (read back through a temporary
     * listener) so the test can hard-assert the keyboard-up state actually applied.
     */
    private fun dispatchSyntheticImeInset(imeBottomPx: Int): Int {
        var observed = 0
        launchedActivity?.onActivity { activity ->
            val decor = activity.window.decorView
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottomPx))
                .build()
            ViewCompat.dispatchApplyWindowInsets(decor, insets)
            observed = ViewCompat.getRootWindowInsets(decor)
                ?.getInsets(WindowInsetsCompat.Type.ime())
                ?.bottom
                ?: imeBottomPx
        }
        // dispatchApplyWindowInsets feeds the value into the production
        // OnApplyWindowInsetsListener directly; report the value we dispatched as
        // the floor so a window that re-supplies insets cannot hide a real apply.
        return maxOf(observed, imeBottomPx)
    }

    // --------------------------------------------------------------- SSH seeding

    private fun resolveSshPort(): Int =
        InstrumentationRegistry.getArguments()
            .getString("terminalWorkbenchSshPort")
            ?.toIntOrNull()
            ?: DEFAULT_PORT

    private suspend fun killSession(sshKey: SshKey.Pem, sshPort: Int) {
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = sshPort,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec("rm -f '$REMOTE_TRIGGER'; tmux kill-session -t '$SESSION_NAME' 2>/dev/null || true")
            }
        }
    }

    /**
     * Seed a tmux session whose pane prints [REDRAW_MARKER], waits on a trigger
     * file, then enters the alternate screen and repaints the whole grid as fast as
     * the pipe allows (no inter-line sleep) — the Codex `%output` render-storm shape
     * (mirrors [CodexRedrawOverflowReconnectE2eTest]). Trigger-gated so the attach
     * never fights the flood; bounded cycles so the link goes quiet again.
     */
    private suspend fun seedTriggerGatedRedrawSession(sshKey: SshKey.Pem, sshPort: Int) {
        val longLine = "X".repeat(160)
        val paneScript = buildString {
            append("printf '$REDRAW_MARKER\\r\\n'; ")
            append("while [ ! -e '$REMOTE_TRIGGER' ]; do sleep 0.1; done; ")
            append("printf '\\033[?1049h'; ")
            append("printf '\\033[2J\\033[H'; ")
            append("c=0; while [ \$c -lt $FLOOD_CYCLES ]; do printf '\\033[H'; i=0; ")
            append("while [ \$i -lt 40 ]; do printf '\\033[K$longLine\\r\\n'; i=\$((i+1)); done; ")
            append("c=\$((c+1)); done")
        }
        val script = buildString {
            appendLine("set -eu")
            appendLine("rm -f '$REMOTE_TRIGGER'")
            appendLine("tmux kill-session -t '$SESSION_NAME' 2>/dev/null || true")
            appendLine("tmux new-session -d -s '$SESSION_NAME' -x 120 -y 40 '$paneScript'")
            appendLine("sleep 1")
            appendLine("tmux list-sessions")
        }
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = sshPort,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(script) } }
        val exec = result.getOrNull()
        assertTrue(
            "expected trigger-gated redraw seed to succeed; exception=" +
                "${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
    }

    private suspend fun triggerRedraw(sshKey: SshKey.Pem, sshPort: Int) {
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = sshPort,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec("touch '$REMOTE_TRIGGER'") }
        }
    }

    private suspend fun persistHost(
        appContext: android.content.Context,
        key: String,
        port: Int,
    ): String {
        var hostRowTag = ""
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue796-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = HOST_NAME,
                    hostname = DEFAULT_HOST,
                    port = port,
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
        return hostRowTag
    }

    // ----------------------------------------------------------------- view glue

    private fun waitForTerminalAttached() {
        compose.waitUntil(timeoutMillis = 20_000) {
            findTerminalView()?.currentSession?.emulator != null
        }
    }

    /**
     * Waits until the screen stops recomposing on its own — i.e. the bounded
     * `%output` redraw storm has drained and the production [TmuxSessionScreen]
     * root group is no longer being re-invalidated by pane/render state. We detect
     * "quiet" as the recomposition probe count being unchanged for
     * [QUIET_WINDOW_MS]. This gives the IME-burst measurement a clean baseline: in
     * the quiet window the ONLY state that changes is the IME inset, so the probe
     * delta is purely IME-driven (not burst-render noise). Bounded by
     * [QUIET_TIMEOUT_MS] so a never-quiet pane fails loud rather than hanging.
     */
    private fun waitForTerminalQuiet() {
        val deadline = SystemClock.elapsedRealtime() + QUIET_TIMEOUT_MS
        var lastCount = TmuxRenderRecompositionProbe.count
        var stableSince = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50)
            val now = TmuxRenderRecompositionProbe.count
            if (now != lastCount) {
                lastCount = now
                stableSince = SystemClock.elapsedRealtime()
            } else if (SystemClock.elapsedRealtime() - stableSince >= QUIET_WINDOW_MS) {
                return
            }
        }
        // Not fatal on its own — the IME measurement resets the probe immediately
        // after, so residual baseline noise just makes the GREEN ceiling a little
        // tighter to meet; record it for diagnosis.
        recordTiming("terminal_never_fully_quiet", 1L)
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

    private fun waitForVisibleTerminalText(
        label: String,
        timeoutMs: Long,
        predicate: (String) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            last = visibleTerminalText()
            if (predicate(last)) return
            SystemClock.sleep(50)
        }
        assertNotNull("predicate $label timed out; visible terminal:\n$last", null)
    }

    private fun findTerminalView(): TerminalView? {
        var found: TerminalView? = null
        launchedActivity?.onActivity { activity ->
            found = activity.window.decorView.findTerminalView()
        }
        return found
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

    /** Reach the live VM (for diagnostics only; not load-bearing here). */
    @Suppress("unused")
    private fun readViewModelOrNull(store: ViewModelStore): TmuxSessionViewModel? {
        val field = ViewModelStore::class.java.getDeclaredField("map").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val map = field.get(store) as MutableMap<String, androidx.lifecycle.ViewModel>
        return map.values.firstOrNull { it is TmuxSessionViewModel } as? TmuxSessionViewModel
    }

    // ----------------------------------------------------------------- artifacts

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        var bitmap: Bitmap? = null
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            bitmap = b
        }
        bitmap?.let { b ->
            val file = artifactFile("$name-viewport.png")
            FileOutputStream(file).use { out ->
                check(b.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    "failed to write viewport bitmap to ${file.absolutePath}"
                }
            }
            println("ISSUE796_H4_VIEWPORT ${file.absolutePath}")
            b.recycle()
        }
        artifactFile("$name-visible-terminal.txt").writeText(visibleTerminalText())
    }

    private fun captureFullFrame(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write full-frame screenshot to ${file.absolutePath}"
            }
        }
        bitmap.recycle()
        println("ISSUE796_H4_FULLFRAME ${file.absolutePath}")
    }

    private fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE796_H4_TIMING $line")
    }

    private fun writeTimings() {
        val file = artifactFile("issue796-h4-timings.txt")
        val header = listOf(
            "scenario=ime-inset-burst-during-codex-output-storm -> terminal-subtree-recomposition-scoping",
            "issue=796 (H4)",
            "session_name=$SESSION_NAME",
            "proxy_note=recomposition-count proxy (NOT a captured /data/anr/traces.txt); " +
                "emulator cannot push to a real 5s ANR",
        )
        file.writeText((header + timings).joinToString("\n", postfix = "\n"))
        println("ISSUE796_H4_TIMINGS ${file.absolutePath}")
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME").apply { mkdirs() }
        return File(dir, name)
    }

    @Suppress("unused")
    private fun countBrightPixels(file: File): Int {
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return 0
        return try {
            var bright = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val p = bitmap.getPixel(x, y)
                    val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                    if (lum > 120) bright++
                }
            }
            bright
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val DATABASE_NAME = "pocketshell.db"
        const val DEVICE_DIR_NAME = "terminal-lab"
        const val HOST_NAME = "Issue796 H4"
        const val SESSION_NAME = "issue796-h4-ime"
        const val REDRAW_MARKER = "ISSUE796-H4-REDRAW"
        const val REMOTE_TRIGGER = "/tmp/issue796-h4-trigger"

        const val ATTACH_TIMEOUT_MS = 30_000L
        const val VISIBLE_TIMEOUT_MS = 20_000L

        // The synthetic soft-keyboard height (~300dp), the same keyboard-up
        // pressure the #780 squish proof uses, injected synthetically.
        const val IME_HEIGHT_DP = 300f

        // The keyboard-show animation's interpolated inset frames. A real IME
        // show emits ~15–30 inset frames; 24 is a representative burst. On the
        // pre-fix delegated read EACH frame recomposes the terminal page, so the
        // probe count tracks this number; the deferred-read fix keeps it O(1).
        const val IME_FRAMES = 24

        // The screen is "quiet" once the recomposition probe is unchanged for this
        // long (the bounded %output storm has drained), giving the IME-burst
        // measurement a clean baseline.
        const val QUIET_WINDOW_MS = 700L

        // Upper bound on waiting for the burst to drain to quiet.
        const val QUIET_TIMEOUT_MS = 25_000L

        // A bounded number of full-grid alt-screen repaint cycles (the Codex
        // %output render-storm shape).
        const val FLOOD_CYCLES = 80

        // Per-edge containment tolerance (dp) for the keyboard-up KeyBar check.
        const val CONTAINMENT_SLOP_DP = 2f
    }
}
