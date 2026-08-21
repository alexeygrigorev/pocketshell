package com.pocketshell.app.voice

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.composer.COMPOSER_CANCEL_RECORDING_TAG
import com.pocketshell.app.composer.COMPOSER_MIC_TAG
import com.pocketshell.app.composer.COMPOSER_STOP_SEND_TAG
import com.pocketshell.app.composer.COMPOSER_TIMER_TAG
import com.pocketshell.app.composer.COMPOSER_TO_FIELD_TAG
import com.pocketshell.app.composer.COMPOSER_WAVEFORM_TAG
import com.pocketshell.app.composer.ComposerSendResult
import com.pocketshell.app.composer.PromptComposerSheet
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.composer.PromptComposerViewModel.MicCapture
import com.pocketshell.app.composer.PromptComposerViewModel.RecordingState
import com.pocketshell.app.composer.PromptComposerViewModel.VoiceSettingsSnapshot
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.app.proof.signals.productionWindowChromePadding
import com.pocketshell.app.tmux.ReservedTmuxTerminalBottomBand
import com.pocketshell.app.tmux.TmuxSessionBottomBandPlacement
import com.pocketshell.core.voice.SpeechAudioGuard
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.RuleChain

/**
 * Issue #1753 reproduce-first proof.
 *
 * This deliberately uses only production symbols that exist on current main.
 * The coachmark and enabled-only TalkBack-action assertions are RED on the
 * pre-#1753 launcher; the physical swipe and plain-tap tests retain the
 * already-proven #585 behavior while making sure this issue cannot wire its
 * education/accessibility callbacks to a fake path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class VoiceGestureCoachmarkLauncherProofTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(compose)

    // These are the same callback/state split that TmuxSessionScreen owns. The
    // real mounted launcher + PromptComposerSheet below make the behavior proof
    // observe the user-visible result instead of only counting callbacks.
    private val showComposer = mutableStateOf(false)
    private val autoRecord = mutableStateOf(false)

    @Before
    fun clearEducationLedger() {
        showComposer.value = false
        autoRecord.value = false
        InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getSharedPreferences(EDUCATION_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun eligibleLauncherShowsOneTimeCoachmarkAboveTheLauncher() {
        renderConversationLauncher()
        awaitCoachmark()

        compose.onNodeWithTag(COACHMARK_TAG).assertExists()
        compose.onNodeWithText(COACHMARK_COPY).assertExists()
        compose.assertNodeFullyWithinRoot(COACHMARK_TAG)
        compose.assertNodeFullyWithinRoot(SESSION_COMPOSER_LAUNCHER_TAG)
        assertLauncherUsesPocketTerminalIcon()

        val coachmark = compose.onNodeWithTag(COACHMARK_TAG).getUnclippedBoundsInRoot()
        val launcher = compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).getUnclippedBoundsInRoot()
        assertTrue(
            "coachmark must be above the enabled launcher; " +
                "coachmark=$coachmark launcher=$launcher",
            coachmark.bottom <= launcher.top,
        )
        assertCoachmarkAnchoredToLauncher()
        com.pocketshell.app.proof.WalkthroughScreenshotArtifacts.capture(
            "issue-1753-fresh-eligible-coachmark",
        )
    }

    @Test
    fun launcherSwipeOpensPromptComposerAlreadyRecording() {
        val mic = FakeMicCapture()
        val viewModel = newComposerViewModel(mic)
        renderLauncherAndComposer(viewModel)

        // This is the load-bearing #585 path: a real pointer stays owned by the
        // production launcher while it leaves the small target and crosses the
        // upward threshold. The asserted timer/waveform live inside the real
        // PromptComposerSheet, not in a callback-only test double.
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true)
            .performTouchInput {
                down(center)
                advanceEventTime(80L)
                repeat(8) {
                    moveBy(Offset(0f, -28f))
                    advanceEventTime(16L)
                }
                up()
            }

        awaitRecording(viewModel)
        assertEquals("swipe-up must start exactly one capture", 1, mic.startCount)
        assertEquals("release after swipe-up must not stop capture", 0, mic.stopCount)
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_TO_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_STOP_SEND_TAG).assertIsDisplayed()
        com.pocketshell.app.proof.WalkthroughScreenshotArtifacts.capture(
            "issue-1753-launcher-swipe-recording",
        )

        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.recording == RecordingState.Idle
        }
        assertEquals("discard must stop the swipe-started capture once", 1, mic.stopCount)
    }

    @Test
    fun plainTapOpensPromptComposerInIdleState() {
        val mic = FakeMicCapture()
        val viewModel = newComposerViewModel(mic)
        renderLauncherAndComposer(viewModel)

        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(COMPOSER_MIC_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()

        compose.onNodeWithTag(COMPOSER_MIC_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_TIMER_TAG).assertDoesNotExist()
        compose.onNodeWithTag(COMPOSER_WAVEFORM_TAG).assertDoesNotExist()
        assertEquals("plain tap must remain compose-only", 0, mic.startCount)
        assertEquals(RecordingState.Idle, viewModel.uiState.value.recording)
    }

    @Test
    fun normalLauncherTapDismissesCoachmarkAndConsumesEducation() {
        renderConversationLauncher()
        awaitCoachmark()

        // Mutation target: removing the production launcher's dismissal hook
        // leaves this coachmark mounted after the normal compose activation.
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).performClick()
        awaitCoachmarkGone()
        awaitPresented()

        // Consumption must remain one-time after the launcher recomposes.
        compose.waitForIdle()
        compose.onNodeWithTag(COACHMARK_TAG).assertDoesNotExist()
    }

    @Test
    fun launcherTalkBackSemanticsKeepComposeActivationAndStartRealDictationAction() {
        val mic = FakeMicCapture()
        val viewModel = newComposerViewModel(mic)
        renderLauncherAndComposer(viewModel)

        val actions = customActionLabels()
        val launcherSemantics = compose
            .onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG)
            .fetchSemanticsNode()
        assertSame(
            "the launcher semantics must carry the actual ComposerLauncherIcon identity",
            ComposerLauncherIcon,
            launcherSemantics.config[ComposerLauncherIconSemanticsKey],
        )
        assertEquals(
            listOf(SESSION_COMPOSER_LAUNCHER_CONTENT_DESCRIPTION),
            launcherSemantics.config[SemanticsProperties.ContentDescription],
        )
        assertEquals(Role.Button, launcherSemantics.config[SemanticsProperties.Role])
        assertTrue(
            "swipe launcher must keep normal compose OnClick semantics",
            launcherSemantics.config.contains(SemanticsActions.OnClick),
        )
        assertTrue(
            "enabled launcher must expose '$DICTATION_ACTION'; observed=$actions",
            actions.contains(DICTATION_ACTION),
        )
        // Compose's CustomAccessibilityAction is the semantics contract
        // TalkBack invokes. Invoke the production action, then require the
        // same visible recording proof as the physical gesture above.
        invokeCustomAction()
        awaitRecording(viewModel)
        assertEquals("TalkBack dictation action must start one capture", 1, mic.startCount)
        assertTrue(
            "normal compose activation must remain exposed",
            launcherSemantics.config.contains(SemanticsActions.OnClick),
        )
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.recording == RecordingState.Idle
        }
    }

    @Test
    fun dictationAccessibilityActionDismissesCoachmarkAndConsumesEducation() {
        val mic = FakeMicCapture()
        val viewModel = newComposerViewModel(mic)
        renderLauncherAndComposer(viewModel)
        awaitCoachmark()

        // Invoke the same production CustomAccessibilityAction that TalkBack
        // uses. This must both start dictation and consume the visible hint.
        invokeCustomAction()
        awaitRecording(viewModel)
        awaitCoachmarkGone()
        awaitPresented()

        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.recording == RecordingState.Idle
        }

        // Consumption must remain one-time after the composer closes and the
        // launcher remains mounted.
        compose.waitForIdle()
        compose.onNodeWithTag(COACHMARK_TAG).assertDoesNotExist()
    }

    @Test
    fun launcherIconIdentityIsNotInferredFromAccentInk() {
        renderConversationLauncher()

        // The unmerged icon node carries the exact ImageVector object used by
        // the production Icon. This catches a mic/pencil/path substitution even
        // when the replacement happens to retain the same accent tint.
        assertLauncherUsesPocketTerminalIcon()
    }

    @Test
    fun disabledLauncherHasNoDictationActionAndDoesNotConsumeEducation() {
        renderConversationLauncher(enabled = false)

        compose.onNodeWithTag(COACHMARK_TAG).assertDoesNotExist()
        assertFalse(customActionLabels().contains(DICTATION_ACTION))
        assertTrue(
            "disabled launcher should publish Disabled semantics",
            compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG)
                .fetchSemanticsNode()
                .config
                .contains(SemanticsProperties.Disabled),
        )
        assertFalse(
            "disabled launcher must not persist the one-time education",
            educationPrefs().contains(EDUCATION_VERSION_KEY),
        )
    }

    @Test
    fun reenabledMountedLauncherCanPresentCoachmarkAfterTemporaryDisable() {
        val launcherEnabled = mutableStateOf(false)
        render {
            TmuxSessionBottomBandPlacement(
                isImeVisible = false,
                onConversationTab = true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ConversationComposerLauncherRow(
                    onDictateTap = {},
                    onDictateHoldSwipeUp = {},
                    inputEnabled = launcherEnabled.value,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).assertExists()
        compose.onNodeWithTag(COACHMARK_TAG).assertDoesNotExist()

        // The launcher stays mounted and keeps the same bounds while a
        // reconnect-like disabled state is active. Re-enabling it must not
        // depend on a second placement callback to complete education.
        compose.runOnIdle { launcherEnabled.value = true }
        awaitCoachmark()
        awaitPresented()
        assertEquals(
            EDUCATION_VERSION,
            educationPrefs().getInt(EDUCATION_VERSION_KEY, 0),
        )
    }

    @Test
    fun tapOnlyLauncherDoesNotPretendToOfferSwipeDictation() {
        var composeTaps = 0
        renderConversationLauncher(
            onCompose = { composeTaps += 1 },
            onDictate = null,
        )

        val launcher = compose
            .onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG)
            .assertExists()
            .assertHasClickAction()
            .fetchSemanticsNode()
        assertEquals(
            "tap-only callers retain the launcher's description",
            listOf(SESSION_COMPOSER_LAUNCHER_CONTENT_DESCRIPTION),
            launcher.config[SemanticsProperties.ContentDescription],
        )
        assertTrue(
            "tap-only callers retain compose activation semantics",
            launcher.config.contains(SemanticsActions.OnClick),
        )
        compose.onNodeWithTag(COACHMARK_TAG).assertDoesNotExist()
        assertFalse(customActionLabels().contains(DICTATION_ACTION))
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).performClick()
        compose.waitForIdle()
        assertEquals("tap-only activation remains compose-only", 1, composeTaps)
    }

    @Test
    fun terminalBandKeepsLauncherAndCoachmarkContainedAtNarrowWidth() {
        render {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale = 1.5f),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .width(360.dp)
                            .testTag(NARROW_BAND_TAG),
                    ) {
                        ReservedTmuxTerminalBottomBand(
                            isImeVisible = false,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            BottomChipControls(
                                onDictateTap = {},
                                onDictateHoldSwipeUp = {},
                                onEnterTap = {},
                                onShowKeyboardTap = {},
                                onAddSnippetTap = {},
                                onShowHotkeysTap = {},
                                inputEnabled = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        awaitCoachmark()
        compose.onNodeWithTag(COACHMARK_TAG).assertExists()
        compose.assertNodeFullyWithinRoot(COACHMARK_TAG)
        compose.assertNodeFullyWithinRoot(SESSION_COMPOSER_LAUNCHER_TAG)
        compose.assertNodeFullyWithinRoot(SESSION_ENTER_CHIP_TAG)
        compose.assertNodeFullyWithinRoot(SHOW_KEYBOARD_CHIP_TAG)

        val coachmark = compose.onNodeWithTag(COACHMARK_TAG).getUnclippedBoundsInRoot()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue(
            "coachmark must stay inside the visible root: coachmark=$coachmark root=$root",
            coachmark.left >= root.left &&
                coachmark.right <= root.right &&
                coachmark.top >= root.top &&
                coachmark.bottom <= root.bottom,
        )
        assertCoachmarkAnchoredToLauncher()
        com.pocketshell.app.proof.WalkthroughScreenshotArtifacts.capture(
            "issue-1753-narrow-large-font-coachmark",
        )
    }

    @Test
    fun presentedEducationSurvivesRecompositionAndActivityRecreation() {
        renderConversationLauncher()
        awaitCoachmark()
        awaitPresented()

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag(COACHMARK_TAG).assertDoesNotExist()
        assertEquals(
            EDUCATION_VERSION,
            educationPrefs().getInt(EDUCATION_VERSION_KEY, 0),
        )
    }

    @Test
    fun dismissingCoachmarkConsumesEducationAfterItIsActuallyShown() {
        renderConversationLauncher()
        awaitCoachmark()

        compose.onNodeWithTag(COACHMARK_DISMISS_TAG).performClick()
        awaitPresented()
        compose.onNodeWithTag(COACHMARK_TAG).assertDoesNotExist()

        compose.waitForIdle()
        compose.onNodeWithTag(COACHMARK_TAG).assertDoesNotExist()
        assertEquals(
            EDUCATION_VERSION,
            educationPrefs().getInt(EDUCATION_VERSION_KEY, 0),
        )
    }

    @Test
    fun losingEligibilityAfterPresentationHidesCoachmarkAndReleasesHost() {
        val launcherEnabled = mutableStateOf(true)
        render {
            TmuxSessionBottomBandPlacement(
                isImeVisible = false,
                onConversationTab = true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ConversationComposerLauncherRow(
                    onDictateTap = {},
                    onDictateHoldSwipeUp = {},
                    inputEnabled = launcherEnabled.value,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        awaitCoachmark()
        awaitPresented()

        // This is the post-presentation reconnect/held transition: the real
        // docked launcher remains mounted but is no longer eligible for the
        // swipe education.
        compose.runOnIdle { launcherEnabled.value = false }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(COACHMARK_TAG)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).assertExists()
        assertTrue(
            "ineligible mounted launcher must publish Disabled semantics",
            compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG)
                .fetchSemanticsNode()
                .config
                .contains(SemanticsProperties.Disabled),
        )
        assertEquals(
            EDUCATION_VERSION,
            educationPrefs().getInt(EDUCATION_VERSION_KEY, 0),
        )
    }

    @Test
    fun imeVisibleAfterPresentationClearsCoachmarkWithoutReoffering() {
        val imeVisible = mutableStateOf(false)
        render {
            ReservedTmuxTerminalBottomBand(
                isImeVisible = imeVisible.value,
                modifier = Modifier.fillMaxWidth(),
            ) {
                BottomChipControls(
                    onDictateTap = {},
                    onDictateHoldSwipeUp = {},
                    onEnterTap = {},
                    onShowKeyboardTap = {},
                    onAddSnippetTap = {},
                    onShowHotkeysTap = {},
                    inputEnabled = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        awaitCoachmark()
        awaitPresented()

        // The reserved terminal copy remains measured but is not placed while
        // the IME is visible. A presented coachmark must be cleared, not left
        // above an uninteractive launcher or reoffered on the next placement.
        compose.runOnIdle { imeVisible.value = true }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(COACHMARK_TAG)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        compose.runOnIdle { imeVisible.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag(COACHMARK_TAG).assertDoesNotExist()
        assertEquals(
            EDUCATION_VERSION,
            educationPrefs().getInt(EDUCATION_VERSION_KEY, 0),
        )
    }

    @Test
    fun measuredImeCopyDoesNotConsumeEducation() {
        compose.setContent {
            PocketShellTheme {
                com.pocketshell.app.tmux.ReservedTmuxTerminalBottomBand(
                    isImeVisible = true,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ConversationComposerLauncherRow(
                        onDictateTap = {},
                        onDictateHoldSwipeUp = {},
                        inputEnabled = true,
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(COACHMARK_TAG).assertDoesNotExist()
        assertFalse(educationPrefs().contains(EDUCATION_VERSION_KEY))
    }

    private class FakeMicCapture : MicCapture {
        var startCount = 0
        var stopCount = 0
        private var running = false

        override fun start() {
            startCount += 1
            running = true
        }

        override fun stop(): ByteArray {
            stopCount += 1
            running = false
            return SpeechAudioGuard.speechWavForTesting()
        }

        override fun currentAmplitude(): Float = if (running) 0.65f else 0f
    }

    private class FakeVault : ApiKeyVault {
        private var key: CharArray? = "sk-test".toCharArray()

        override fun save(key: CharArray) {
            this.key = key.copyOf()
        }

        override fun load(): CharArray? = key?.copyOf()

        override fun clear() {
            key = null
        }
    }

    private class FakeVoiceSettings : VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = 600_000L

        override fun whisperLanguageHint(): String? = null
    }

    private fun newComposerViewModel(mic: MicCapture): PromptComposerViewModel {
        val whisper = object : WhisperClient {
            override suspend fun transcribe(
                audio: ByteArray,
                language: String?,
            ): Result<String> = Result.success("launcher dictation")
        }
        return PromptComposerViewModel(
            audioRecorder = mic,
            whisperClientFactory = WhisperClientFactory { whisper },
            apiKeyStorage = FakeVault(),
            voiceSettings = FakeVoiceSettings(),
            savedStateHandle = SavedStateHandle(),
        )
    }

    /**
     * Mirrors TmuxSessionScreen's production launcher/sheet callback split:
     * tap opens with `autoStartRecording=false`; the upward route opens the
     * real PromptComposerSheet with `autoStartRecording=true`.
     */
    private fun renderLauncherAndComposer(viewModel: PromptComposerViewModel) {
        showComposer.value = false
        autoRecord.value = false
        render {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                ConversationComposerLauncherRow(
                    onDictateTap = {
                        autoRecord.value = false
                        showComposer.value = true
                    },
                    onDictateHoldSwipeUp = {
                        autoRecord.value = true
                        showComposer.value = true
                    },
                    inputEnabled = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (showComposer.value) {
                PromptComposerSheet(
                    onDismiss = { showComposer.value = false },
                    onSend = { _ -> ComposerSendResult.Delivered },
                    viewModel = viewModel,
                    autoStartRecording = autoRecord.value,
                )
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(SESSION_COMPOSER_LAUNCHER_TAG)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun awaitRecording(viewModel: PromptComposerViewModel) {
        compose.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.recording == RecordingState.Recording
        }
        compose.waitForIdle()
        compose.onNodeWithTag(COMPOSER_TIMER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_WAVEFORM_TAG).assertIsDisplayed()
    }

    private fun renderConversationLauncher(
        enabled: Boolean = true,
        onCompose: () -> Unit = {},
        onDictate: (() -> Unit)? = {},
    ) {
        render {
            // Mount the production conversation bottom-band placement and the
            // real docked launcher row. This keeps the proof tied to the same
            // end padding/inset relationship used by TmuxSessionScreen.
            TmuxSessionBottomBandPlacement(
                isImeVisible = false,
                onConversationTab = true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ConversationComposerLauncherRow(
                    onDictateTap = onCompose,
                    onDictateHoldSwipeUp = onDictate,
                    inputEnabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    private fun render(content: @Composable () -> Unit) {
        compose.setContent {
            PocketShellTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .productionWindowChromePadding()
                        .background(PocketShellColors.Background),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    content()
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertCoachmarkAnchoredToLauncher() {
        val coachmark = compose.onNodeWithTag(COACHMARK_TAG).getUnclippedBoundsInRoot()
        val launcher = compose
            .onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG)
            .getUnclippedBoundsInRoot()
        assertEquals(
            "coachmark must share the real launcher's end anchor",
            launcher.right,
            coachmark.right,
        )
        assertTrue(
            "coachmark must not overlap the launcher: coachmark=$coachmark launcher=$launcher",
            coachmark.bottom <= launcher.top,
        )
    }

    private fun assertLauncherUsesPocketTerminalIcon() {
        val icon = compose
            .onNodeWithTag(SESSION_COMPOSER_LAUNCHER_ICON_TAG, useUnmergedTree = true)
            .assertExists()
            .fetchSemanticsNode()
        assertEquals(
            "the rendered launcher semantics value must equal production ComposerLauncherIcon",
            ComposerLauncherIcon,
            icon.config[ComposerLauncherIconSemanticsKey],
        )
        assertEquals(
            "ComposerLauncher",
            icon.config[ComposerLauncherIconSemanticsKey]?.name,
        )
    }

    private fun awaitCoachmark() {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(COACHMARK_COPY)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun awaitCoachmarkGone() {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(COACHMARK_TAG)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        compose.waitForIdle()
    }

    private fun awaitPresented() {
        compose.waitUntil(timeoutMillis = 5_000) {
            educationPrefs().getInt(EDUCATION_VERSION_KEY, 0) >=
                EDUCATION_VERSION
        }
    }

    private fun customActionLabels(): List<String> = compose
        .onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG)
        .fetchSemanticsNode()
        .config
        .getOrNull(SemanticsActions.CustomActions)
        ?.map { it.label }
        .orEmpty()

    private fun invokeCustomAction() {
        val action = compose
            .onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.CustomActions)
            ?.firstOrNull { it.label == DICTATION_ACTION }
            ?: error("missing custom action '$DICTATION_ACTION'")
        compose.runOnIdle {
            assertTrue("custom action must report handled", action.action())
        }
    }

    private fun educationPrefs() = InstrumentationRegistry.getInstrumentation()
        .targetContext
        .getSharedPreferences(EDUCATION_PREFS_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val COACHMARK_TAG = "session:voice-gesture-coachmark"
        const val COACHMARK_DISMISS_TAG = "session:voice-gesture-coachmark-dismiss"
        const val COACHMARK_COPY = "Tap to compose · swipe up to dictate"
        const val DICTATION_ACTION = "Start dictation"
        const val NARROW_BAND_TAG = "issue-1753-narrow-band"
        const val EDUCATION_PREFS_NAME = "voice_education"
        const val EDUCATION_VERSION_KEY = "launcher_dictation_hint_presented_version"
        const val EDUCATION_VERSION = 1
    }
}
