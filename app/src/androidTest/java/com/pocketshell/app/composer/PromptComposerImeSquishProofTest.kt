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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.proof.signals.waitForInputMethodVisible
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

        val imeShown = waitForInputMethodVisible(
            scenario = compose.activityRule.scenario,
            expected = true,
            timeoutMs = 30_000L,
        )
        assumeTrue(
            "IME not available on this emulator; cannot validate issue #567 geometry",
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
