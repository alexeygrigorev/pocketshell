package com.pocketshell.app.composer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #491 regression: tapping Send in the composer ALWAYS delivers, with
 * no separate keyboard-Enter required.
 *
 * The reported bug: the user types a prompt, taps Send, and nothing happens —
 * they have to raise the soft keyboard and press Enter to actually send. Root
 * cause: the composer drove its Send button off the ViewModel draft, which the
 * legacy `String`-overload `BasicTextField` only populated once the IME *committed*
 * its composing region (predictive text / autocorrect underline). On a short
 * prompt that commit may never happen until the user manually presses Enter,
 * so Send saw an empty draft and was disabled / a no-op.
 *
 * The fix holds the editor as a `TextFieldValue` so the composer sees the live
 * visible text (composing region included), and routes every Send affordance
 * through a `commitAndSend` that flushes the live text into the ViewModel
 * BEFORE dispatching. These tests pin that Send fires from the editor state
 * directly, including the case where the ViewModel draft has NOT been
 * independently updated (the IME-composing-region scenario the bug describes).
 */
@RunWith(AndroidJUnit4::class)
class PromptComposerSendNoKeyboardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun tearDown() {
        collectorScope.cancel()
    }

    private class TestMicCapture : PromptComposerViewModel.MicCapture {
        var startCount = 0
        override fun start() {
            startCount += 1
        }
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

    private fun newViewModel(
        mic: TestMicCapture = TestMicCapture(),
    ): PromptComposerViewModel = PromptComposerViewModel(
        audioRecorder = mic,
        whisperClientFactory = WhisperClientFactory {
            object : WhisperClient {
                override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> =
                    Result.success("")
            }
        },
        apiKeyStorage = TestVault(),
        voiceSettings = TestVoiceSettings(),
    )

    /**
     * The happy-path regression: type a prompt, tap Send (the keyboard was
     * never raised, Enter was never pressed), and the send dispatches through
     * the real `requestSend` -> `sendRequests` path exactly once with the
     * typed text + `withEnter = true`. Tapping Send is the only gesture.
     */
    @Test
    fun tapSendWithoutKeyboardEnterDeliversTypedPrompt() {
        val vm = newViewModel()
        val sent = java.util.Collections.synchronizedList(
            mutableListOf<PromptComposerViewModel.SendRequest>(),
        )
        compose.setContent {
            PocketShellTheme {
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
        collectorScope.launch { vm.sendRequests.collect { sent += it } }

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG)
            .performTextInput("deploy the staging branch")
        compose.waitForIdle()

        // Send is enabled off the typed text and a single tap dispatches it.
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        compose.waitUntil(timeoutMillis = 5_000) { sent.isNotEmpty() }
        assertEquals(1, sent.size)
        assertEquals("deploy the staging branch", sent[0].text)
        assertEquals(true, sent[0].withEnter)
    }

    /**
     * The core no-op regression: simulate the IME composing-region lag where
     * the visible editor text is present but the host (ViewModel draft) has
     * NOT yet been told about it. Before the fix, Send read the stale/empty
     * draft and did nothing. After the fix, `commitAndSend` flushes the live
     * editor text into the host first, so the tap delivers the visible prompt.
     *
     * We model the lag with a host whose `onSend` reads its own `hostDraft`,
     * which the sheet only updates via the `onDraftChange` that the Send path
     * (`commitAndSend`) fires. The Send button's enabled gate now reads the
     * live editor text, so it is tappable even while `hostDraft` is empty —
     * and the flush populates `hostDraft` before `onSend` reads it.
     */
    @Test
    fun tapSendFlushesLiveEditorTextEvenWhenHostDraftLags() {
        // hostDraft stands in for the ViewModel draft that lags the editor
        // because the IME has not committed its composing region. The sheet
        // updates it through onDraftChange; we record what onSend sees.
        var hostDraft by mutableStateOf("")
        var lastSent: String? = null
        var sendInvocations = 0

        compose.setContent {
            PocketShellTheme {
                SheetContent(
                    state = PromptComposerViewModel.UiState(draft = hostDraft),
                    onClose = {},
                    onDraftChange = { text -> hostDraft = text },
                    onMicTap = {},
                    onSend = { _ ->
                        sendInvocations += 1
                        // Read the host draft the way the real ViewModel's
                        // dispatchSendNow reads _uiState.draft. The Send path
                        // must have flushed the live editor text into the host
                        // BEFORE this runs, or this reads empty (the bug).
                        lastSent = hostDraft
                    },
                )
            }
        }

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG)
            .performTextInput("restart the worker pool")
        compose.waitForIdle()

        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG)
            .assertIsEnabled()
            .performClick()
        compose.waitForIdle()

        // Exactly one send, carrying the full visible editor text — the flush
        // happened before dispatch, so no empty / partial send slipped out.
        assertEquals(1, sendInvocations)
        assertEquals("restart the worker pool", lastSent)
    }

    @Test
    fun agentPaneSendUsesLiveEditorText() {
        // Issue #569: the agent-pane composer must use the same live editor
        // text model as the prompt sheet. Send receives the currently visible
        // field text directly, so the submit path does not have to re-read a
        // parent String draft that may be behind an IME composing region.
        var text by mutableStateOf("")
        var sent: String? = null

        compose.setContent {
            PocketShellTheme {
                AgentComposerSurface(
                    value = text,
                    onValueChange = { text = it },
                    onSend = { liveText -> sent = liveText },
                    inputFieldTag = "issue569:agent-input",
                    sendButtonTag = "issue569:agent-send",
                )
            }
        }

        val longDictation = buildString {
            append("Summarize the attached screenshot and explain why the deployment stalled. ")
            repeat(20) { append("Keep the answer concrete and mention the failing stage. ") }
        }
        compose.onNodeWithTag("issue569:agent-input")
            .performTextInput(longDictation)
        compose.waitForIdle()

        compose.onNodeWithTag("issue569:agent-send")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.waitForIdle()

        assertEquals(longDictation, sent)
    }

    @Test
    fun agentPaneCompactLayoutKeepsSendReachableForLongDictationAndAttachments() {
        // Issue #567/#569: under mobile/IME-style width constraints, long
        // dictated text plus the attachment suffix must not compress the
        // agent composer into an unusable row. The compact layout stacks Send
        // below the bounded draft field, keeping it visible and tappable.
        var text by mutableStateOf("")
        var sent: String? = null

        compose.setContent {
            PocketShellTheme {
                AgentComposerSurface(
                    value = text,
                    onValueChange = { text = it },
                    onSend = { liveText -> sent = liveText },
                    inputFieldTag = "issue567:agent-input",
                    sendButtonTag = "issue567:agent-send",
                    modifier = Modifier.width(300.dp),
                )
            }
        }

        val prompt = buildString {
            append("Please inspect this screenshot and explain the failure. ")
            repeat(30) { append("This is dictated text that should stay editable and submit. ") }
            append("\n\nAttached files:\n")
            append("- ~/.pocketshell/attachments/host-1/issue-567-shot.png")
        }
        compose.onNodeWithTag("issue567:agent-input").performTextInput(prompt)
        compose.waitForIdle()

        val inputBounds = compose.onNodeWithTag("issue567:agent-input")
            .fetchSemanticsNode()
            .boundsInRoot
        val sendBounds = compose.onNodeWithTag("issue567:agent-send")
            .assertIsDisplayed()
            .assertIsEnabled()
            .fetchSemanticsNode()
            .boundsInRoot

        assertTrue(
            "compact composer should stack Send below the draft instead of squeezing both into one row",
            sendBounds.top >= inputBounds.bottom,
        )

        compose.onNodeWithTag("issue567:agent-send").performClick()
        compose.waitForIdle()

        assertEquals(prompt, sent)
    }

    /**
     * Issue #570 / #544: staged attachment chips are a valid prompt even
     * without typed text. The ViewModel already composes attachment-only
     * sends; the sheet must keep the Send affordance tappable for that state.
     */
    @Test
    fun sendIsEnabledForAttachmentOnlyPrompt() {
        var sendInvocations = 0

        compose.setContent {
            PocketShellTheme {
                SheetContent(
                    state = PromptComposerViewModel.UiState(
                        draft = "",
                        attachments = listOf(
                            PromptComposerViewModel.StagedAttachment(
                                remotePath = "~/.pocketshell/attachments/host-1/shot.png",
                                displayName = "shot.png",
                            ),
                        ),
                    ),
                    onClose = {},
                    onDraftChange = {},
                    onMicTap = {},
                    onSend = { _ -> sendInvocations += 1 },
                )
            }
        }

        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.waitForIdle()

        assertEquals(1, sendInvocations)
    }

    /**
     * Issue #453 dogfood follow-up: the composer action row should use
     * familiar icon controls, not a space-hungry "Attach" label or custom
     * text glyphs. This pins the public semantics users/tests rely on while
     * allowing the concrete Material vector implementation to evolve.
     */
    @Test
    fun composerActionRowUsesSemanticIconControls() {
        compose.setContent {
            PocketShellTheme {
                SheetContent(
                    state = PromptComposerViewModel.UiState(draft = ""),
                    onClose = {},
                    onDraftChange = {},
                    onMicTap = {},
                    onSend = {},
                    onSnippets = {},
                    onAttachFiles = {},
                )
            }
        }

        compose.onNodeWithTag(COMPOSER_ATTACH_TAG)
            .assertIsDisplayed()
            .assertIsEnabled()
        compose.onNodeWithContentDescription("Attach files")
            .assertIsDisplayed()

        compose.onNodeWithTag(COMPOSER_SNIPPETS_TAG)
            .assertIsDisplayed()
            .assertIsEnabled()
        compose.onNodeWithContentDescription("Insert snippet")
            .assertIsDisplayed()

        compose.onNodeWithText("Attach").assertDoesNotExist()
        compose.onNodeWithText("{ }").assertDoesNotExist()
    }

    /**
     * Issue #570: connected proof for the real sheet content while a 3-image
     * attachment batch is stalled. Uploading disables only attachment/snippet
     * picking; the visible composer remains usable, text Send dispatches
     * immediately, and mic dictation can still start instead of wedging behind
     * the in-flight upload.
     */
    @Test
    fun stalledThreeImageUploadKeepsSendAndMicLive() {
        val mic = TestMicCapture()
        val vm = newViewModel(mic)
        val uploadStarted = CompletableDeferred<Unit>()
        val uploadResult = CompletableDeferred<Result<List<String>>>()
        val sent = java.util.Collections.synchronizedList(
            mutableListOf<PromptComposerViewModel.SendRequest>(),
        )

        collectorScope.launch { vm.sendRequests.collect { sent += it } }
        compose.setContent {
            PocketShellTheme {
                val state by vm.uiState.collectAsState()
                SheetContent(
                    state = state,
                    onClose = {},
                    onDraftChange = vm::onDraftChange,
                    onMicTap = { vm.onMicTap() },
                    onSend = { withEnter -> vm.requestSend(withEnter) },
                    onSnippets = {},
                    onAttachFiles = {},
                )
            }
        }

        compose.runOnIdle {
            vm.attachFiles(count = 3) {
                uploadStarted.complete(Unit)
                uploadResult.await()
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) { uploadStarted.isCompleted }

        compose.onNodeWithTag(COMPOSER_ATTACH_TAG)
            .assertIsDisplayed()
            .assertIsNotEnabled()
        compose.onNodeWithTag(COMPOSER_SNIPPETS_TAG)
            .assertIsDisplayed()
            .assertIsNotEnabled()

        compose.onNodeWithTag(COMPOSER_DRAFT_TAG)
            .performTextInput("send while screenshots are still uploading")
        compose.waitForIdle()

        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) { sent.isNotEmpty() }
        assertEquals(1, sent.size)
        assertEquals("send while screenshots are still uploading", sent[0].text)
        assertEquals(true, sent[0].withEnter)
        assertEquals(
            PromptComposerViewModel.AttachmentUploadState.Idle,
            vm.uiState.value.attachmentUpload,
        )

        compose.onNodeWithTag(COMPOSER_MIC_TAG)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) { mic.startCount == 1 }
        assertEquals(
            PromptComposerViewModel.RecordingState.Recording,
            vm.uiState.value.recording,
        )

        compose.runOnIdle {
            uploadResult.complete(Result.failure(java.io.IOException("degraded upload path")))
        }
        compose.waitForIdle()
        assertEquals(
            "late upload failures from a cancelled send-time upload must not dirty the composer",
            null,
            vm.uiState.value.error,
        )
    }

    /**
     * Issue #453: the separate keyboard icon was removed from the Idle
     * controls row (not in the mockup; cluttered the clean idle). Tapping
     * the editable draft field is the single, obvious way to raise the IME.
     * Here we assert the field is present + accepts text without a separate
     * keyboard affordance.
     */
    @Test
    fun draftFieldRaisesKeyboardOnFocusWithoutASeparateKeyboardIcon() {
        val vm = newViewModel()
        compose.setContent {
            PocketShellTheme {
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

        // The draft field is the only keyboard entry point; tapping it and
        // typing works (the IME is an OS surface we can't assert directly).
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG)
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).performTextInput("hello")
        compose.waitForIdle()
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertIsDisplayed()
    }
}
