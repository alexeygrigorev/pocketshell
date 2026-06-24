package com.pocketshell.app.composer

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.proof.signals.waitForInputMethodVisible
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #682 — the composer IME-layout regression cluster introduced by #615.
 *
 * #615 reworked [PromptComposerSheet] into a fully-expanded sheet driven by a
 * host-window IME inset read plus an explicit `padding(bottom = hostImeBottomPx)`.
 * On the maintainer's device that combination:
 *
 *  1. opened a HUGE empty void between the composer controls and the keyboard;
 *  2. JUMPED / over-scrolled the content to the top on text-field focus,
 *     cutting off most of the text + composer elements;
 *  3. made Send RE-OPEN the keyboard after dispatch;
 *
 * while keeping the one thing it got right: Send reachable above the keyboard.
 *
 * The rework makes the composer a content-height (wrap-content) sheet anchored
 * directly above the keyboard via the standard `imePadding()` mechanism. This
 * test mounts the REAL production sheet, raises the REAL soft IME by focusing
 * the draft, and asserts the FOUR load-bearing geometry/behaviour invariants
 * with full-device keyboard-up screenshots so they cannot silently regress
 * again (the #615 fix shipped a regression precisely because it was never
 * device-verified for these states).
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class PromptComposerImeLayoutRegressionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class TestMicCapture : PromptComposerViewModel.MicCapture {
        override fun start() {}
        override fun stop(): ByteArray = ByteArray(0)
        override fun currentAmplitude(): Float = 0f
    }

    private class TestVault : PromptComposerViewModel.ApiKeyVault {
        private var key: CharArray? = "sk-test".toCharArray()
        override fun save(key: CharArray) { this.key = key.copyOf() }
        override fun load(): CharArray? = key?.copyOf()
        override fun clear() { key = null }
    }

    private class TestVoiceSettings : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
        override fun whisperLanguageHint(): String? = null
    }

    private fun newViewModel(): PromptComposerViewModel = PromptComposerViewModel(
        audioRecorder = TestMicCapture(),
        whisperClientFactory = WhisperClientFactory {
            object : WhisperClient {
                override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> =
                    Result.success("")
            }
        },
        apiKeyStorage = TestVault(),
        voiceSettings = TestVoiceSettings(),
    )

    @Test
    fun composerSitsCompactAboveKeyboardWithNoVoidNoJumpAndSendDoesNotReopenIme() {
        val vm = newViewModel()
        var dismissed = false
        // Match MainActivity's edge-to-edge window so IME inset propagation
        // into the sheet's dialog window mirrors production.
        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }

        compose.setContent {
            PocketShellTheme {
                // Mirror the production call sites (`SessionScreen` /
                // `TmuxSessionScreen`): `onDismiss` removes the sheet from
                // composition. Send dispatches -> ViewModel -> onDismiss ->
                // sheet leaves composition, exactly the real journey where the
                // keyboard must go down and STAY down after Send.
                val composerVisible = androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(true)
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    FauxTerminalBackdrop()
                    if (composerVisible.value) {
                        PromptComposerSheet(
                            onDismiss = {
                                dismissed = true
                                composerVisible.value = false
                            },
                            onSend = { _ -> true },
                            viewModel = vm,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        // --- Raise the REAL keyboard by the real user gesture: tap + type. ---
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performClick()
            .performTextInput("printf issue682")

        val imeShown = waitForInputMethodVisible(
            scenario = compose.activityRule.scenario,
            expected = true,
            timeoutMs = 30_000L,
        )
        assumeTrue(
            "IME not available on this emulator; cannot validate issue #682 geometry",
            imeShown,
        )
        compose.waitUntil(timeoutMillis = 5_000) { readImeBottomPx() > 0 }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(400)
        compose.waitForIdle()

        val decorHeight = readDecorHeightPx().toFloat()
        val imeTop = decorHeight - readImeBottomPx()

        val draftBounds = compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val sendBounds = compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot

        // Emit authoritative geometry for the reviewer to read from logcat —
        // BEFORE the (best-effort) screenshot so a flaky `takeScreenshot` on the
        // shared AVD can never swallow this line.
        println(
            "ISSUE682_GEOMETRY decorHeight=$decorHeight imeTop=$imeTop " +
                "draftTop=${draftBounds.top} draftBottom=${draftBounds.bottom} " +
                "sendTop=${sendBounds.top} sendBottom=${sendBounds.bottom}",
        )

        captureFullDevice("issue682-keyboard-up-composer-viewport.png")

        // Optional hold so a reviewer/maintainer can grab a crash-free
        // full-device keyboard-up screenshot host-side via `adb exec-out
        // screencap` while the composer is on screen with the keyboard up. Off
        // by default (CI/normal runs stay fast); enabled with
        // `-Pandroid.testInstrumentationRunnerArguments.issue682HoldMs=<ms>`.
        InstrumentationRegistry.getArguments().getString("issue682HoldMs")
            ?.toLongOrNull()?.let { holdMs ->
                if (holdMs > 0L) {
                    println("ISSUE682_HOLD_BEGIN ms=$holdMs")
                    android.os.SystemClock.sleep(holdMs)
                    println("ISSUE682_HOLD_END")
                }
            }

        // (d) Send reachable ABOVE the keyboard (the one thing #615 got right —
        // must be preserved).
        assertTrue(
            "Send must stay above the IME. sendBottom=${sendBounds.bottom} imeTop=$imeTop",
            sendBounds.bottom <= imeTop + 2f,
        )

        // (a) NO huge void: the composer's bottom-most control (Send) must sit
        // CLOSE to the keyboard, not floating far above it. The void regression
        // left a full-keyboard-height (~hundreds of px) gap; a healthy compact
        // composer sits within a modest band of the IME top. Budget = 25% of the
        // visible-above-IME height, which on a Pixel 7 (~imeTop ≈ 1400px) is
        // ~350px — generous, but nowhere near the keyboard-height void #615 left.
        val voidBudget = imeTop * 0.25f
        assertTrue(
            "Composer must sit just above the keyboard with NO large void. " +
                "gap=${imeTop - sendBounds.bottom} budget=$voidBudget imeTop=$imeTop sendBottom=${sendBounds.bottom}",
            (imeTop - sendBounds.bottom) <= voidBudget,
        )

        // (b) NO jump-to-top / cut-off: the composer must NOT slam its content to
        // the top of the screen (the #615 regression pinned content to the top,
        // cutting it off behind the status bar / pushing controls off-screen).
        // A compact composer anchored above the keyboard keeps its draft field in
        // the LOWER portion of the screen, leaving the terminal visible above it.
        // The draft-field position is the robust signal (an M3 drag-handle pixel
        // overlap can make a header-node bounds read flake to 0).
        assertTrue(
            "Composer must not jump to the top — the draft field should sit in " +
                "the lower portion of the screen. draftTop=${draftBounds.top} decorHeight=$decorHeight",
            draftBounds.top > decorHeight * 0.25f,
        )
        // And the draft field itself must be fully on-screen above the IME (not
        // cut off): its top non-negative, its bottom above the keyboard.
        assertTrue(
            "Draft field must be fully visible above the IME (not cut off). " +
                "draftTop=${draftBounds.top} draftBottom=${draftBounds.bottom} imeTop=$imeTop",
            draftBounds.top >= 0f && draftBounds.bottom <= imeTop + 2f,
        )

        // (c) Send must DISMISS the keyboard, not re-open it. Tap Send, then the
        // IME must hide and STAY hidden (the field's focus is cleared so nothing
        // re-requests it). Before the fix, the IME popped right back up.
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .performClick()
        compose.waitForIdle()

        // Send dispatched + dismissed the composer.
        compose.waitUntil(timeoutMillis = 5_000) { dismissed }

        // The keyboard must be OFF SCREEN after Send and STAY off — not pop back
        // up. We assert on the IME inset BOTTOM (the geometric truth of what the
        // user sees on screen) rather than the framework `isVisible(ime())`
        // flag: once the composer's dialog window is torn down with no focused
        // editor, the host window's `isVisible` flag can linger `true` while the
        // keyboard has actually animated fully off-screen (bottom == 0). The
        // maintainer's report is the visible keyboard re-appearing, so the
        // on-screen inset height is the right signal. Poll the bottom to 0, then
        // hold and confirm it never bounces back up.
        val downDeadline = android.os.SystemClock.elapsedRealtime() + 10_000L
        var imeBottom = readImeBottomPx()
        while (imeBottom > 0 && android.os.SystemClock.elapsedRealtime() < downDeadline) {
            android.os.SystemClock.sleep(100)
            imeBottom = readImeBottomPx()
        }
        assertTrue(
            "Send must dismiss the keyboard (not leave it / re-open it). " +
                "imeBottomPx=$imeBottom after Send.",
            imeBottom == 0,
        )
        // Hold a beat and confirm the IME does NOT bounce back up.
        repeat(8) {
            android.os.SystemClock.sleep(150)
            assertTrue(
                "Send must not RE-RAISE the keyboard after dismissing it. " +
                    "imeBottomPx=${readImeBottomPx()}",
                readImeBottomPx() == 0,
            )
        }
        captureFullDevice("issue682-after-send-keyboard-dismissed.png")
    }

    /**
     * Issue #682 + #567: the composer must STILL keep Send above the keyboard
     * (no void, no cut-off) when the draft is long AND staged attachment tiles
     * are present — the worst-case content height. The scrollable upper region
     * absorbs the overflow so the sticky Send row stays compact above the IME.
     */
    @Test
    fun longDraftWithAttachmentsKeepsSendCompactAboveKeyboard() {
        val vm = newViewModel()
        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        }
        compose.setContent {
            PocketShellTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    FauxTerminalBackdrop()
                    PromptComposerSheet(
                        onDismiss = {},
                        onSend = { _ -> true },
                        viewModel = vm,
                    )
                }
            }
        }
        compose.waitForIdle()
        // Stage two attachment tiles directly on the ViewModel (the production
        // state the composer renders above the controls row).
        compose.runOnIdle {
            vm.seedAttachment("~/.pocketshell/attachments/host-1/issue682-a.png")
            vm.seedAttachment("~/.pocketshell/attachments/host-1/issue682-b.png")
        }
        compose.waitForIdle()

        val longDraft = buildString {
            append("Open a new session after checking these screenshots. ")
            repeat(12) { append("Keep the folder picker visible while typing the prompt. ") }
        }
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performClick()
            .performTextInput(longDraft)

        val imeShown = waitForInputMethodVisible(
            scenario = compose.activityRule.scenario,
            expected = true,
            timeoutMs = 30_000L,
        )
        assumeTrue(
            "IME not available on this emulator; cannot validate issue #682 geometry",
            imeShown,
        )
        compose.waitUntil(timeoutMillis = 5_000) { readImeBottomPx() > 0 }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(400)
        compose.waitForIdle()

        val imeTop = readDecorHeightPx() - readImeBottomPx()
        val sendBounds = compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot

        println("ISSUE682_LONG_GEOMETRY imeTop=$imeTop sendTop=${sendBounds.top} sendBottom=${sendBounds.bottom}")
        captureFullDevice("issue682-long-draft-attachments-keyboard-up.png")

        assertTrue(
            "Send must stay above the IME even with a long draft + attachments. " +
                "sendBottom=${sendBounds.bottom} imeTop=$imeTop",
            sendBounds.bottom <= imeTop + 2f,
        )
        // No void: Send sits close to the keyboard, not floating far above it.
        assertTrue(
            "No large void with long content. gap=${imeTop - sendBounds.bottom} imeTop=$imeTop",
            (imeTop - sendBounds.bottom) <= imeTop * 0.25f,
        )
    }

    @Composable
    private fun FauxTerminalBackdrop() {
        Text(
            text = "alex@pocketshell:~$ tail -f deploy.log\n[ok] migrate complete",
            color = PocketShellColors.Text,
        )
    }

    private fun readImeBottomPx(): Int {
        var result = 0
        compose.activityRule.scenario.onActivity { activity ->
            val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
            result = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        }
        return result
    }

    private fun readDecorHeightPx(): Int {
        var result = 0
        compose.activityRule.scenario.onActivity { activity ->
            result = activity.window.decorView.height
        }
        return result
    }

    private fun captureFullDevice(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(250)
        // Best-effort: a shared-AVD sibling can leave UiAutomation half-connected
        // and crash `takeScreenshot()`. The geometry asserts are the
        // authoritative emulator proof; the screenshot is supplementary, so a
        // capture failure must not fail the test.
        val bitmap: Bitmap = runCatching {
            instrumentation.uiAutomation.takeScreenshot()
        }.getOrNull() ?: return
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val outDir = File(
            mediaRoot,
            "additional_test_output/issue682-composer-ime-layout",
        ).apply {
            if (!exists()) {
                assertTrue("Could not create screenshot directory: $absolutePath", mkdirs())
            }
        }
        val file = File(outDir, name)
        FileOutputStream(file).use { stream ->
            assertTrue(
                "Could not write screenshot: ${file.absolutePath}",
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream),
            )
        }
        println("ISSUE682_VIEWPORT ${file.absolutePath}")
    }
}
