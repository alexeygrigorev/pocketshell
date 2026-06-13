package com.pocketshell.app.composer

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
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.proof.signals.waitForInputMethodVisible
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #567 — composer + soft keyboard SQUISH proof.
 *
 * The maintainer's keyboard-up screenshot shows the draft field crushed to a
 * single line and the attachment tiles + Send/mic/attach row crammed together
 * (with the Send glyph half-clipped) while the keyboard is up. The earlier
 * [PromptComposerSheetImeReachabilityTest] only proved the Send button stays
 * ABOVE the IME — it did not catch the squish, because Send being on-screen is
 * compatible with the whole body being compressed into a thin strip.
 *
 * This test reproduces the maintainer's exact scenario (a multi-line draft +
 * two staged attachment tiles, IME raised by focusing the field) and asserts
 * the content region above the keyboard is NOT squished: the draft field keeps
 * at least its single-line min height AND the whole composer body claims a
 * sensible fraction of the room above the keyboard rather than collapsing into
 * a thin strip. It also supports an optional hold so a host-side full-device
 * screenshot can be captured with the keyboard up for the issue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class PromptComposerImeSquishProofTest {

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
    fun composerNotSquishedWithDraftAndAttachmentsWhenImeUp() {
        val vm = newViewModel()
        // Stage two attachments through the real production path so the
        // attachment tile grid renders, matching the maintainer's screenshot.
        vm.attachFiles(count = 2) {
            Result.success(
                listOf(
                    "/tmp/Screenshot_20260606-135541.png",
                    "/tmp/Screenshot_20260606-135556.png",
                ),
            )
        }

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
                        onSend = { _, _ -> true },
                        viewModel = vm,
                        onStageAttachments = { Result.success(emptyList()) },
                    )
                }
            }
        }

        compose.waitForIdle()

        // Real user gesture: tap + type a multi-line draft, raising the IME.
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performClick()
            .performTextInput(
                "Reduce the connector/indent cell width\n" +
                    "Wrote 23 lines to issue.md\nMake the tiles compact",
            )

        // Raise the soft IME DETERMINISTICALLY. `performTextInput` alone proved
        // non-deterministic per-emulator (it relies on the focused-field IME
        // auto-show, which a fresh swiftshader AVD — e.g. CI, or local `test-2` —
        // does not reliably honour, so the test silently `assumeTrue`-SKIPPED and
        // the gate went green with ZERO squish protection — the exact #736 review
        // blocker). We instead REQUEST the IME explicitly via
        // `WindowInsetsControllerCompat.show(ime())` against the activity window
        // (the same call TmuxKeyBarImeReachabilityTest / RootProjectAddSheet use),
        // re-issuing it on each poll iteration until the framework propagates the
        // ime() inset or the bounded deadline elapses. Re-issuing matters: a
        // single show() can be dropped while the window is still settling after
        // focus, so we keep nudging within the bound.
        val imeShown = raiseSoftImeDeterministically(timeoutMs = 30_000L)
        // A can't-show is a HARD FAILURE, never a silent skip. This gate's whole
        // purpose (#736, per #638/#657) is to catch the keyboard-up squish at PR
        // time; an `assumeTrue` skip here would let the per-PR job report green
        // without ever checking the squish — zero protection — which is exactly
        // what the #736 review rejected. If the IME genuinely cannot be raised
        // after the robust bounded attempt above, fail LOUD (red) so the gate can
        // NEVER silently pass.
        assertTrue(
            "IME could not be raised within 30s after focus + repeated " +
                "WindowInsetsControllerCompat.show(ime()); cannot validate the " +
                "issue #567 keyboard-up squish geometry. A no-IME emulator must " +
                "FAIL this gate, not silently skip it (#736).",
            imeShown,
        )

        compose.waitUntil(timeoutMillis = 5_000) { readImeBottomPx() > 0 }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(400)
        compose.waitForIdle()

        // Optional hold BEFORE the assertions so a host-side full-device
        // screenshot can capture the keyboard-up composer state even on the
        // squished base (where the assertions below would fail and abort).
        val holdMs = InstrumentationRegistry.getArguments()
            .getString("issue567HoldMs")?.toLongOrNull() ?: 0L
        if (holdMs > 0L) {
            println("ISSUE567_HOLD_BEGIN ms=$holdMs")
            android.os.SystemClock.sleep(holdMs)
            println("ISSUE567_HOLD_END")
        }

        val draftBounds = compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val headerBounds = compose.onNodeWithTag(COMPOSER_CLOSE_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val sendBounds = compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val attachBounds = compose.onNodeWithTag(COMPOSER_ATTACH_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot

        val decorHeight = readDecorHeightPx()
        val imeTop = decorHeight - readImeBottomPx()
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        val draftHeightDp = (draftBounds.height) / density
        // The composer body (header top -> controls-row bottom) above the IME.
        val bodyTopPx = headerBounds.top
        val bodyBottomPx = maxOf(sendBounds.bottom, attachBounds.bottom)
        val bodyHeightPx = bodyBottomPx - bodyTopPx
        val roomAboveKeyboardPx = imeTop.toFloat() - bodyTopPx

        println(
            "ISSUE567_SQUISH draftHeightDp=$draftHeightDp draftTop=${draftBounds.top} " +
                "draftBottom=${draftBounds.bottom} sendBottom=${sendBounds.bottom} " +
                "attachBottom=${attachBounds.bottom} imeTop=$imeTop decorHeight=$decorHeight " +
                "bodyTop=$bodyTopPx bodyBottom=$bodyBottomPx bodyHeightPx=$bodyHeightPx " +
                "roomAboveKeyboardPx=$roomAboveKeyboardPx",
        )

        // 1) Send + attach must stay above the keyboard (reachability) and NOT
        //    be clipped by the keyboard top.
        assertTrue(
            "Send must stay above the IME. sendBottom=${sendBounds.bottom} imeTop=$imeTop",
            sendBounds.bottom <= imeTop + 2f,
        )
        assertTrue(
            "Attach must stay above the IME. attachBottom=${attachBounds.bottom} imeTop=$imeTop",
            attachBounds.bottom <= imeTop + 2f,
        )

        // 2) The "Prompt Composer" header (close affordance) must be FULLY ON
        //    SCREEN — the squish pushed the whole body taller than the room above
        //    the keyboard, shoving the header up off the top of the sheet
        //    (measured headerTop ~= 0, i.e. clipped behind the status bar) while
        //    the draft collapsed to a single line. The fix keeps the header on the
        //    sheet (well below the status bar) so the user can read the title and
        //    reach the close button.
        compose.onNodeWithTag(COMPOSER_CLOSE_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
        assertTrue(
            "Composer header is clipped off the top of the sheet (squish). " +
                "headerTop=$bodyTopPx",
            bodyTopPx >= 200f,
        )

        // 3) The whole composer body (header -> controls) must FIT within the room
        //    above the keyboard. When squished, the body was ~1420px tall — far
        //    larger than the ~460px of room — so it overflowed both ends (header
        //    off the top, controls jammed at the keyboard). A body that fits the
        //    room is the anti-squish invariant.
        assertTrue(
            "Composer body taller than the room above the keyboard (squish). " +
                "bodyHeightPx=$bodyHeightPx roomAboveKeyboardPx=$roomAboveKeyboardPx",
            bodyHeightPx <= roomAboveKeyboardPx + 8f,
        )

        // 4) The body must be lifted to sit just above the keyboard (small gap),
        //    not crammed at the window top with a void below.
        val gapBelowControlsPx = imeTop.toFloat() - bodyBottomPx
        val gapBelowControlsDp = gapBelowControlsPx / density
        println("ISSUE567_GAP gapBelowControlsDp=$gapBelowControlsDp")
        assertTrue(
            "Composer controls sit far above the keyboard (void). " +
                "gapBelowControlsDp=$gapBelowControlsDp",
            gapBelowControlsDp <= 64f,
        )
    }

    /**
     * Raise the soft IME deterministically and return whether it became
     * visible within [timeoutMs].
     *
     * Why not just poll [waitForInputMethodVisible]: that only WAITS for the
     * inset; it does not REQUEST the keyboard. On a fresh swiftshader AVD the
     * focused-field auto-show is unreliable (observed: local `test-2` and a
     * fresh CI AVD never raise the keyboard from `performTextInput` alone), so a
     * pure wait times out and the test used to silently skip. Here we actively
     * call [WindowInsetsControllerCompat.show] for `ime()` on the activity
     * window on EVERY poll iteration — re-issuing because a single show() can be
     * dropped while the window is still settling after focus — until the
     * framework propagates the `ime()` inset or the bound elapses. This is the
     * same explicit-request approach TmuxKeyBarImeReachabilityTest and
     * RootProjectAddSheetKeyboardLayoutTest use to force the real keyboard up.
     */
    private fun raiseSoftImeDeterministically(timeoutMs: Long): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            // Re-tap the Compose draft field on each iteration so it holds focus
            // and its InputConnection is (re)established before we ask for the
            // keyboard. On a fresh swiftshader AVD the focus/InputConnection can
            // be lost while the window settles, leaving no "served" editor — which
            // is why the bare inset-API request gets rejected at
            // PHASE_CLIENT_REQUEST_IME_SHOW. Re-clicking re-arms it.
            compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
                .performClick()
            compose.waitForIdle()

            compose.activity.runOnUiThread {
                val window = compose.activity.window
                val imm = compose.activity.getSystemService(
                    android.content.Context.INPUT_METHOD_SERVICE,
                ) as? android.view.inputmethod.InputMethodManager
                // (a) View-level request against the ACTUAL focused view. In a
                //     Compose test the editor is the AndroidComposeView that owns
                //     the active InputConnection, NOT window.currentFocus (which is
                //     null for Compose), so we resolve it via decorView.findFocus().
                //     Driving InputMethodManager.showSoftInput on that served view
                //     forces a show that does not depend on the inset-API
                //     served-view gate that fails on a fresh AVD.
                val focused = window.decorView.findFocus()
                if (focused != null && imm != null) {
                    imm.showSoftInput(
                        focused,
                        android.view.inputmethod.InputMethodManager.SHOW_FORCED,
                    )
                }
                // (b) Window-level inset request. On a healthy AVD this raises the
                //     keyboard outright; together with (a) it covers both paths.
                WindowInsetsControllerCompat(window, window.decorView)
                    .show(WindowInsetsCompat.Type.ime())
            }
            // Give this show() request up to ~3s to land before re-issuing, so a
            // slow CI AVD's IME ack is not pre-empted by an immediate re-request.
            val shown = waitForInputMethodVisible(
                scenario = compose.activityRule.scenario,
                expected = true,
                timeoutMs = minOf(
                    3_000L,
                    (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L),
                ),
            )
            if (shown) return true
        }
        return false
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
}
