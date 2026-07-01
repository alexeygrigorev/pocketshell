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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.proof.signals.waitForInputMethodVisible
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #790 — SCREENSHOT HARNESS (not a regression gate).
 *
 * Opens the REAL production [PromptComposerSheet] (a real `ModalBottomSheet`
 * dialog window), raises the REAL soft keyboard by focusing the draft field, and
 * holds for `issue790HoldMs` so a maintainer/reviewer can grab a crash-free
 * full-device screenshot HOST-SIDE via `adb exec-out screencap` while the
 * composer is on screen with the keyboard up. Two cases:
 *
 *  - [emptyDraftKeyboardUpHold]  — the reported #790 state (empty draft).
 *  - [longDraftKeyboardUpHold]   — a long multi-line draft (scroll + Send
 *                                  reachable, the #682 invariant).
 *
 * This file mirrors the host-side hold pattern of
 * [PromptComposerSheetImeReachabilityTest] (#615): it deliberately does NOT call
 * `uiAutomation.takeScreenshot()` itself (that crashes teardown under sibling
 * AVD contention). The authoritative geometry gate is the deterministic
 * synthetic-inset proof [PromptComposerImeEmptyDraftDeadSpaceProofTest]; this
 * harness only stages the real on-device state for the maintainer's screenshot.
 *
 * It uses `assumeTrue` on real-IME availability ON PURPOSE — it is a
 * screenshot-staging convenience, not the load-bearing regression assertion, so
 * a CI AVD that cannot raise the real soft IME skips it without weakening
 * coverage (the deterministic proof carries the assertion). Each method no-ops
 * unless `issue790HoldMs` is passed, so normal/CI runs never block.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class PromptComposerImeDeadSpaceScreenshotHarness {

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
    fun emptyDraftKeyboardUpHold() { stageAndHold(draft = null) }

    // Issue #873: the maintainer's EXACT reported state — a SHORT one-word draft
    // ("gghh") with the keyboard up. Stages it for the keyboard-up screenshot that
    // must show the compact field with NO ~1cm dead band below the text.
    @Test
    fun shortDraftKeyboardUpHold() { stageAndHold(draft = "gghh") }

    @Test
    fun longDraftKeyboardUpHold() { stageAndHold(
        draft = "Reduce the connector/indent cell width so the tree lines stop " +
            "overlapping the file name.\n" +
            "Then wrap the long folder paths instead of clipping them.\n" +
            "Wrote 23 lines to issue.md describing the repro.\n" +
            "Make the attachment tiles compact and re-check the keyboard-up layout.\n" +
            "Finally, confirm Send and the paperclip stay reachable above the IME.",
    ) }

    private fun stageAndHold(draft: String?) {
        val holdMs = InstrumentationRegistry.getArguments()
            .getString("issue790HoldMs")?.toLongOrNull() ?: 0L
        if (holdMs <= 0L) return

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

        // Real user gesture: tap the draft to raise the soft IME. For the long
        // case, type the multi-line draft too.
        val field = compose.onNodeWithTag(COMPOSER_DRAFT_TAG, useUnmergedTree = true)
            .performClick()
        if (draft != null) {
            field.performTextInput(draft)
        }

        val imeShown = waitForInputMethodVisible(
            scenario = compose.activityRule.scenario,
            expected = true,
            timeoutMs = 30_000L,
        )
        // JUSTIFIED: host-side screenshot-staging harness gated behind issue790HoldMs, not a load-bearing assertion. The real #790 dead-space regression is HARD-asserted (synthetic ime() inset, no skip) by PromptComposerImeEmptyDraftDeadSpaceProofTest; this harness only pauses with the soft IME up so the orchestrator can capture the keyboard-up screenshot, so an IME-unavailable skip here loses no coverage.
        assumeTrue(
            "IME not available on this emulator; cannot stage the #790 keyboard-up screenshot",
            imeShown,
        )
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(500)
        compose.waitForIdle()

        println("ISSUE790_HOLD_BEGIN draft=${if (draft == null) "empty" else "long"} ms=$holdMs")
        android.os.SystemClock.sleep(holdMs)
        println("ISSUE790_HOLD_END")
    }

    @Composable
    private fun FauxTerminalBackdrop() {
        Text(
            text = "alex@pocketshell:~$ tail -f deploy.log\n[ok] migrate complete",
            color = PocketShellColors.Text,
        )
    }
}
