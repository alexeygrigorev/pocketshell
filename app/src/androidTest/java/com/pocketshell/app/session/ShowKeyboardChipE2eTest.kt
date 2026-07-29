package com.pocketshell.app.session

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.SeedBeforeLaunchRule
import com.pocketshell.app.proof.TerminalTestTimeouts
import com.pocketshell.app.proof.clearLastSessionPrefs
import com.pocketshell.app.proof.execRemoteSetupUntilReady
import com.pocketshell.app.proof.signals.waitForInputMethodVisible
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.voice.SHOW_KEYBOARD_CHIP_TAG
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #131 / #1846: connected validation for the show-keyboard chip on the
 * live tmux session screen.
 *
 * ## What this pins
 *
 * The bug the chip fixes is "no obvious way to bring up the soft keyboard from
 * the session screen" — phone users otherwise have to discover the
 * tap-on-viewport gesture on their own. The contract is therefore *causal*:
 * **with the keyboard down, one tap on the chip must raise the real soft
 * keyboard.** This test drives the real `MainActivity` over the Docker `agents`
 * fixture, attaches to a plain shell tmux session, and asserts that contract
 * against the system-level `WindowInsetsCompat.Type.ime()` signal.
 *
 * ## Why the test looks like this (issue #1846)
 *
 * The original body was gated behind `Assume.assumeTrue(…, false)` — an
 * unconditional skip — so roughly 60 lines of it could never execute anywhere,
 * under any configuration. The stated justification was that the raw-SSH
 * "Continue with SSH" escape hatch it navigated through had been deleted by
 * #171 (D22) and that "`ShowKeyboardChipDockerTest` already covers the
 * tmux-attach chip behaviour". The navigation half of that was true; the
 * coverage half was not — `ShowKeyboardChipDockerTest` has never existed in
 * this repository (`git log --all -S ShowKeyboardChipDockerTest` returns only
 * the commit that wrote the comment). So the skip traded a real assertion for a
 * test that does not exist, and the chip's contract went unpinned.
 *
 * The behaviour still ships (`VoiceSessionSurface.kt` renders the chip;
 * `TmuxSessionScreen.kt` wires it to `showTerminalSoftKeyboard`), so per D22 the
 * answer is not to delete the method — it is to re-point it at the surviving
 * production surface (tmux attach via the folders-first host route) and make it
 * assert.
 *
 * ## Why it is not redundant with the neighbouring IME journeys
 *
 * `TmuxShellComposerOcclusionE2eTest` and `Issue887TerminalFixedUnderImeE2eTest`
 * both tap this chip, but only as the *mechanism* for raising the keyboard
 * before measuring geometry. Neither establishes that the IME was DOWN
 * beforehand, and the occlusion journey re-taps the chip on a retry loop — so
 * neither of them fails if the chip stops causing the keyboard to appear on a
 * clean first tap. This test closes that gap:
 *
 *  - the keyboard is **explicitly driven down and hard-asserted down** before
 *    each tap, so the post-tap assertion cannot pass on a keyboard that was
 *    already up (the vacuity the pre-#1846 body explicitly allowed: it accepted
 *    `ime_visible_before_tap` being either `false` *or* `true`);
 *  - the chip is tapped **exactly once** per cycle, with no re-tap loop, so a
 *    chip that only works on the third try is a failure;
 *  - the cycle runs **twice**, so a one-shot artefact of initial focus cannot
 *    masquerade as the chip working.
 *
 * ## Signal choice
 *
 * The IME-visibility check uses `waitForInputMethodVisible` from the
 * `com.pocketshell.app.proof.signals` package (#140). That helper polls
 * `WindowInsetsCompat.Type.ime()` on the activity decor view — the same signal
 * app code uses for keyboard-aware layouts, and the one the framework
 * propagates as soon as the IME window attaches its insets to the focused
 * window. `dumpsys input_method | grep mInputShown` is deliberately avoided: the
 * IME process updates `mInputShown` asynchronously *after* the insets have
 * already landed, which makes dumpsys lag visibly on swiftshader CI emulators.
 *
 * Per F3 there is no `assumeTrue` / `assumeFalse(isRunningOnCi())` anywhere in
 * this test: every assertion is a hard failure on every device.
 *
 * ## Artifacts
 *
 * Per run, under
 * `/sdcard/Android/media/com.pocketshell.app/additional_test_output/show-keyboard-chip/`:
 *
 *  - `01-before-tap-viewport.png` — full-device screenshot with the chip row
 *    visible and the IME hidden.
 *  - `02-after-tap-viewport.png`  — full-device screenshot with the IME raised
 *    after the first chip tap.
 *  - `03-second-cycle-after-tap-viewport.png` — the repeat cycle.
 *  - `summary.txt` — observed IME-visibility values per cycle, tap→visible
 *    latencies, and the chip-row tag.
 */
@RunWith(AndroidJUnit4::class)
class ShowKeyboardChipE2eTest {

    val compose = createAndroidComposeRule<MainActivity>()

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus from
    // the Compose hierarchy ("No compose hierarchies found").
    //
    // Issue #788: seed the remote tmux session + DB host row BEFORE
    // `createAndroidComposeRule<MainActivity>()` launches, so the Termux
    // `TerminalView` interop child is reliably placed into the window. The
    // chip's production handler resolves the TerminalView out of the compose
    // root view, so an unplaced interop child would make this test fail for the
    // wrong reason.
    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedFixture() })
        .around(compose)

    private var seededKey: String? = null
    private var seededHostRowTag: String? = null
    private val summaryLines = mutableListOf<String>()

    private suspend fun seedFixture() {
        clearLastSessionPrefs()
        val key = readFixtureKey()
        seededKey = key
        waitForSshFixtureReady(SshKey.Pem(key), port = DEFAULT_PORT)
        seedTmuxSession(key)
        seededHostRowTag = seedDockerHost(key)
    }

    @After
    fun tearDown() {
        clearLastSessionPrefs()
        seededKey?.let { key ->
            runCatching { runBlocking { cleanupRemoteTmuxSession(key) } }
        }
    }

    @Test
    fun showKeyboardChipBringsUpSoftInput() {
        val hostRowTag = requireNotNull(seededHostRowTag) { "host row was not seeded" }
        summaryLines += "issue=131,1846 scenario=show-keyboard-chip"
        summaryLines += "chip_test_tag=$SHOW_KEYBOARD_CHIP_TAG"
        summaryLines += "host=$DEFAULT_HOST port=$DEFAULT_PORT user=$DEFAULT_USER"

        attachSeededTmuxSession(hostRowTag)

        // The chip only renders once the session screen has reached the live
        // band; wait for the production node rather than a fixed sleep.
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithTag(SHOW_KEYBOARD_CHIP_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithTag(SHOW_KEYBOARD_CHIP_TAG, useUnmergedTree = true).assertExists()

        // Cycle 1 — the maintainer's reported scenario: keyboard down, one tap,
        // keyboard up.
        runShowKeyboardCycle(
            cycle = 1,
            beforeArtifact = "01-before-tap-viewport.png",
            afterArtifact = "02-after-tap-viewport.png",
        )

        // Cycle 2 — the same contract from a state where the IME has already
        // been raised and dismissed once. A chip that "works" only because the
        // terminal happened to gain focus on first attach fails here.
        runShowKeyboardCycle(
            cycle = 2,
            beforeArtifact = null,
            afterArtifact = "03-second-cycle-after-tap-viewport.png",
        )

        writeSummary()
    }

    /**
     * One full down → tap → up cycle.
     *
     * The pre-condition assertion is what makes the post-condition
     * load-bearing: without "the IME is verifiably DOWN before the tap", an
     * already-raised keyboard would satisfy the post-tap check while the chip
     * did nothing at all (G6 — the load-bearing assertion must be the one that
     * can go red).
     */
    private fun runShowKeyboardCycle(
        cycle: Int,
        beforeArtifact: String?,
        afterArtifact: String,
    ) {
        forceHideSoftInput()
        val downObserved = waitForInputMethodVisible(
            scenario = compose.activityRule.scenario,
            expected = false,
            timeoutMs = IME_HIDE_TIMEOUT_MS,
        )
        summaryLines += "cycle$cycle.ime_visible_before_tap=$downObserved"
        // HARD pre-condition — no assumeTrue skip. If the keyboard cannot be
        // driven down we have not reproduced the reported state, and the tap
        // assertion below would be vacuous.
        assertTrue(
            "expected the soft keyboard to be HIDDEN before tapping the show-keyboard " +
                "chip (cycle $cycle); observed ime_visible=$downObserved. Without this " +
                "pre-condition the post-tap assertion cannot fail and proves nothing.",
            !downObserved,
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        beforeArtifact?.let { captureFullDevice(File(ensureArtifactDir(), it)) }

        // Tap the chip EXACTLY once, via its stable test tag so the assertion
        // survives a caption rename ("keyboard" -> "show keyboard"). No retry
        // loop: "the user taps show-keyboard and the keyboard appears" is the
        // contract, and a chip that needs three taps has broken it.
        val tapAt = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(SHOW_KEYBOARD_CHIP_TAG, useUnmergedTree = true).performClick()

        val shown = waitForInputMethodVisible(
            scenario = compose.activityRule.scenario,
            expected = true,
            timeoutMs = IME_SHOW_TIMEOUT_MS,
        )
        val raisedMs = SystemClock.elapsedRealtime() - tapAt
        summaryLines += "cycle$cycle.ime_visible_after_tap=$shown"
        summaryLines += "cycle$cycle.tap_to_ime_visible_ms=$raisedMs"

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        captureFullDevice(File(ensureArtifactDir(), afterArtifact))

        // The #131 acceptance: the IME must be visible after a single tap.
        assertTrue(
            "expected the soft keyboard to be VISIBLE after ONE tap on the show-keyboard " +
                "chip (cycle $cycle); observed ime_visible_before_tap=$downObserved " +
                "ime_visible_after_tap=$shown raisedMs=$raisedMs",
            shown,
        )
    }

    // --- Navigation --------------------------------------------------------

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
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        waitForTerminalViewAttached()
    }

    /**
     * The production chip handler resolves the Termux [TerminalView] out of the
     * compose root and calls `imm.showSoftInput` on it. Waiting for a live
     * emulator here keeps a missing interop child from being reported as "the
     * chip does not raise the keyboard".
     */
    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            var attached = false
            compose.activityRule.scenario.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession != null && view.mEmulator != null
            }
            attached
        }
    }

    // --- IME control -------------------------------------------------------

    /**
     * Drive the soft keyboard down from the test side. Both routes are used:
     * `WindowInsetsControllerCompat.hide(ime())` is the modern signal-accurate
     * path, and `hideSoftInputFromWindow` covers the case where no view holds
     * an active connection yet.
     */
    private fun forceHideSoftInput() {
        compose.activityRule.scenario.onActivity { activity ->
            val window = activity.window ?: return@onActivity
            val decor = window.decorView
            runCatching {
                WindowInsetsControllerCompat(window, decor)
                    .hide(WindowInsetsCompat.Type.ime())
            }
            runCatching {
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? InputMethodManager
                imm?.hideSoftInputFromWindow(decor.windowToken, 0)
            }
        }
    }

    // --- Host / session seeding -------------------------------------------

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
                name = "show-keyboard-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "ShowKeyboard Chip",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    // Bootstrap already done so the host tap goes straight to
                    // the folders/session surface.
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
                    shellQuote("printf '$READY_MARKER\\n'; exec sleep 600"),
            )
            appendLine("sleep 1")
            appendLine("tmux list-sessions")
        }
        val result = execRemoteSetupUntilReady(
            key = SshKey.Pem(key),
            command = script,
            description = "show-keyboard chip tmux seed session",
        )
        assertTrue(
            "expected tmux seeding to succeed; exit=${result.exitCode} stderr='${result.stderr}'",
            result.exitCode == 0,
        )
    }

    private suspend fun cleanupRemoteTmuxSession(key: String) {
        execRemoteSetupUntilReady(
            key = SshKey.Pem(key),
            command = "tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true",
            description = "show-keyboard chip tmux cleanup",
        )
    }

    // --- Artifact helpers --------------------------------------------------

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create show-keyboard-chip artifact directory: ${dir.absolutePath}"
        }
        return dir
    }

    private fun writeSummary() {
        val file = File(ensureArtifactDir(), "summary.txt")
        file.writeText(summaryLines.joinToString(separator = "\n", postfix = "\n"))
        println("SHOW_KEYBOARD_CHIP_SUMMARY ${file.absolutePath}")
        summaryLines.forEach { println("SHOW_KEYBOARD_CHIP_SUMMARY_LINE $it") }
    }

    private fun captureFullDevice(file: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write show-keyboard screenshot: ${file.absolutePath}"
                }
            }
            println("SHOW_KEYBOARD_CHIP_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
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
        const val DEVICE_DIR_NAME: String = "show-keyboard-chip"
        const val SESSION_NAME: String = "issue131-show-keyboard"
        const val READY_MARKER: String = "ISSUE131-SHOW-KEYBOARD-READY"

        /**
         * Budget for the keyboard to go DOWN. Generous enough that a slow
         * swiftshader emulator never trips it, tight enough that a keyboard
         * that will not dismiss is reported as a failure rather than absorbed.
         */
        val IME_HIDE_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L

        /**
         * Budget for the keyboard to come UP after the single chip tap. The CI
         * swiftshader emulator commonly lags 5–15 s and can spike to ~25 s
         * under load (see `ImeSignals.IME_VISIBILITY_DEFAULT_TIMEOUT_MS`), so
         * 45 s on CI is a "this is broken, not slow" ceiling.
         */
        val IME_SHOW_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 45_000L else 30_000L
    }
}
