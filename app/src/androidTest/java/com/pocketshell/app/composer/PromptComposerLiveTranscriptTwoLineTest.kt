package com.pocketshell.app.composer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.proof.WalkthroughScreenshotArtifacts
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.app.settings.VoiceTranscriptionProvider
import com.pocketshell.core.voice.SpeechAudioGuard
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellType
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #870 (REOPEN) — reproduce-first end-to-end proof (G10/D31) for the
 * maintainer's on-device report (v0.4.14, 2026-06-22): with the default Android
 * recognizer the LIVE transcript is STILL cut as it grows, so the newest words
 * scroll out of view.
 *
 * The superseded fix used a width-INDEPENDENT character budget
 * (`PromptComposerViewModel.LIVE_TRANSCRIPT_MAX_CHARS = 90`) trimmed in the
 * ViewModel and rendered in a single `Text(maxLines = 2, overflow = Ellipsis)`.
 * On a real device the 90-char tail did not FIT two lines at the panel width /
 * font scale, so the trailing ellipsis re-clipped the END — the newest words
 * were cut again. A char budget cannot know the panel width, so it cannot
 * guarantee the tail fits.
 *
 * This test renders the **production** [RecordingSurface] (via [SheetContent])
 * under a deliberately INFLATED font scale (so a long partial cannot fit two
 * lines as raw text — the real-phone condition that the wide swiftshader AVD
 * masked for the prior fix) and feeds a long growing partial through the
 * production recognizer FSM. The load-bearing assertion is that the ACTUAL
 * on-screen live-transcript text:
 *
 *   1. ends with the newest words (the tail is the visible content), and
 *   2. FITS within the two-line area at the real panel width (measured with a
 *      [TextMeasurer] against the node's real bounds) — i.e. it is NOT clipped.
 *
 * On the OLD single-line+char-budget rendering the visible text is the 90-char
 * tail, which needs >2 lines at this width → assertion (2) FAILS (red), which is
 * the maintainer's exact "still cut" symptom. On the new width-aware
 * [LiveTranscriptTwoLine] the visible text is the resolved tail that fits two
 * lines at this width → GREEN.
 *
 * Containment is asserted via [assertNodeFullyWithinRoot] (#657/F1), not a bare
 * `assertIsDisplayed()`. No `assumeTrue` / CI-skip on the load-bearing assertion.
 */
@RunWith(AndroidJUnit4::class)
class PromptComposerLiveTranscriptTwoLineTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // Reproduce the on-device condition WITHOUT breaking the sheet layout: inflate
    // the font scale so the live-transcript text is much bigger and a long phrase
    // needs many lines at the panel width. The wide swiftshader AVD fits ~90 chars
    // in two lines at the default scale (which is exactly why the wide AVD MASKED
    // this bug for the prior fix); a larger font reproduces the real phone's
    // "doesn't fit two lines" condition. Layout (mic, controls) scales with the
    // density, so the mic stays on-screen and tappable.
    private val fontScaleInflation = 2.6f

    private class TestMicCapture : PromptComposerViewModel.MicCapture {
        override fun start() = Unit
        override fun stop(): ByteArray = SpeechAudioGuard.speechWavForTesting()
        override fun currentAmplitude(): Float = 0f
    }

    private class TestVault : PromptComposerViewModel.ApiKeyVault {
        private var key: CharArray? = null
        override fun save(key: CharArray) { this.key = key.copyOf() }
        override fun load(): CharArray? = key?.copyOf()
        override fun clear() { key = null }
    }

    private class AndroidSpeechSettings : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
        override fun whisperLanguageHint(): String? = null
        override fun transcriptionProvider(): VoiceTranscriptionProvider =
            VoiceTranscriptionProvider.AndroidSpeech
    }

    private class FakeSpeechRecognitionProvider :
        PromptComposerViewModel.SpeechRecognitionProvider {
        @Volatile
        var listener: PromptComposerViewModel.SpeechRecognitionListener? = null

        override fun isAvailable(): Boolean = true

        override fun start(
            language: String?,
            listener: PromptComposerViewModel.SpeechRecognitionListener,
        ): PromptComposerViewModel.SpeechRecognitionSession {
            this.listener = listener
            return object : PromptComposerViewModel.SpeechRecognitionSession {
                override fun stopListening() = Unit
                override fun cancel() = Unit
            }
        }
    }

    @Test
    fun longLivePartialKeepsNewestWordsVisibleWithinTwoLineAreaAtRealWidth() {
        val speech = FakeSpeechRecognitionProvider()
        val vm = PromptComposerViewModel(
            audioRecorder = TestMicCapture(),
            whisperClientFactory = WhisperClientFactory {
                object : WhisperClient {
                    override suspend fun transcribe(
                        audio: ByteArray,
                        language: String?,
                    ): Result<String> = Result.success("unused")
                }
            },
            apiKeyStorage = TestVault(),
            voiceSettings = AndroidSpeechSettings(),
            speechRecognitionProvider = speech,
        )

        var measurer: TextMeasurer? = null
        compose.setContent {
            // Inflate the font scale around the WHOLE sheet so text wraps to more
            // lines (the real-phone condition) while the layout stays usable.
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = base.density,
                    fontScale = base.fontScale * fontScaleInflation,
                ),
            ) {
                PocketShellTheme {
                    // measurer inherits the inflated density, so the line-count
                    // check below measures at the SAME font scale as the render.
                    measurer = rememberTextMeasurer()
                    val state by vm.uiState.collectAsState()
                    SheetContent(
                        state = state,
                        onClose = {},
                        onDraftChange = vm::onDraftChange,
                        onMicTap = { vm.onMicTap() },
                        onSend = { withEnter -> vm.requestSend(withEnter) },
                    )
                }
            }
        }

        // Start the Android-recognizer recording via the production mic tap.
        compose.onNodeWithTag(COMPOSER_MIC_TAG).performClick()
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.recording == PromptComposerViewModel.RecordingState.Recording
        }

        // Feed a long, GROWING live partial through the production recognizer FSM.
        val newestWords = "deployed to production right now"
        val longPartial =
            "open the deployment pipeline and check the logs for the failing " +
                "build then restart the worker and confirm the latest commit is " +
                newestWords
        compose.runOnIdle { speech.listener!!.onPartial(longPartial) }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            !vm.uiState.value.liveTranscript.isNullOrBlank()
        }

        // Read the ACTUAL on-screen live-transcript text from the rendered node.
        val node = compose.onNodeWithTag(COMPOSER_LIVE_TRANSCRIPT_TEXT_TAG)
            .assertIsDisplayed()
            .fetchSemanticsNode()
        val visibleText = node.config.getOrNull(SemanticsProperties.Text)
            ?.joinToString("") { it.text }
            ?: error("live-transcript node has no Text semantics")
        val nodeWidthPx = node.boundsInRoot.width.toInt()

        // 1) The visible text keeps the NEWEST words at the tail.
        assertTrue(
            "the visible live transcript must keep the newest words; was: \"$visibleText\"",
            visibleText.endsWith(newestWords),
        )

        // 2) LOAD-BEARING: the visible text must FIT the two-line area at the real
        // panel width — i.e. it is NOT clipped. On the old char-budget rendering
        // the 90-char tail needs >2 lines at this width, so this fails (the
        // maintainer's "still cut" symptom). Measured with a real TextMeasurer
        // against the node's real laid-out width.
        val lineCount = measurer!!.measure(
            text = visibleText,
            style = PocketShellType.bodyDense,
            constraints = Constraints(maxWidth = nodeWidthPx.coerceAtLeast(1)),
        ).lineCount
        assertTrue(
            "the visible live transcript must fit within $LIVE_TRANSCRIPT_MAX_LINES lines at " +
                "width=${nodeWidthPx}px so the newest words are actually visible (not clipped); " +
                "it needed $lineCount lines. text=\"$visibleText\"",
            lineCount <= LIVE_TRANSCRIPT_MAX_LINES,
        )

        // 3) Containment (#657 / F1): the live-transcript area is fully within the
        // window root (not pushed off-screen / behind chrome).
        compose.assertNodeFullyWithinRoot(COMPOSER_LIVE_TRANSCRIPT_TAG)

        // Visual acceptance artifact: a full-device shot of the dedicated two-line
        // live transcript with a long phrase (the newest words on the second line).
        WalkthroughScreenshotArtifacts.capture("issue-870-live-transcript-two-line")
    }
}
