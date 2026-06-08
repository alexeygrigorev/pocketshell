package com.pocketshell.app.composer

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.createStdoutFlow
import com.pocketshell.app.proof.openShell
import com.pocketshell.app.session.SessionViewModel
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Emulator hooks for issue #68.
 *
 * The first test renders the composer with fake states so recording and
 * processing are asserted without a microphone or provider credentials.
 * The Docker smoke test drives a typed draft through the same
 * [SessionViewModel.sendText] bridge used by the real session screen.
 */
@RunWith(AndroidJUnit4::class)
class PromptComposerSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun recordingAndTranscribingStatesAreVisible() {
        // Issue #453: the recording UI is now indicator-driven (no
        // redundant "CAPTURING"/"LISTENING"/"TRANSCRIBING" text). Step
        // through pre-speech, capturing, and transcribing and assert on the
        // authoritative surfaces: the waveform a11y description + the mm:ss
        // timer (Recording) and the transcribing surface + "Transcribing…".
        var state by mutableStateOf(
            PromptComposerViewModel.UiState(
                draft = "check deploy logs",
                recording = PromptComposerViewModel.RecordingState.Recording,
                amplitude = 0f,
                hasDetectedSpeech = false,
                recordingElapsedMs = 3_000L,
            ),
        )
        renderComposer { state }

        // Pre-speech sub-state: idle waveform a11y label + the timer.
        compose.onNodeWithTag(COMPOSER_WAVEFORM_TAG)
            .assert(hasContentDescription("Prompt composer waiting for speech"))
        compose.onNodeWithTag(COMPOSER_TIMER_TAG).assertIsDisplayed()
        compose.onNodeWithText("00:03").assertExists()
        // No redundant status text any more (declutter).
        compose.onNodeWithText("CAPTURING").assertDoesNotExist()
        compose.onNodeWithText("LISTENING").assertDoesNotExist()
        compose.onNodeWithText("Locked").assertDoesNotExist()
        // Issue #508: the two explicit stop actions replace the old Auto-send
        // toggle in the Recording state. Issue #174 adds a separate Discard
        // action that does not transcribe.
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_TO_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_STOP_SEND_TAG).assertIsDisplayed()

        compose.runOnIdle {
            // Active-speech sub-state: the sampler loop has seen at least
            // one amplitude sample over `SILENCE_AMPLITUDE_THRESHOLD`.
            state = state.copy(amplitude = 0.8f, hasDetectedSpeech = true, recordingElapsedMs = 17_000L)
        }

        compose.onNodeWithTag(COMPOSER_WAVEFORM_TAG)
            .assert(hasContentDescription("Prompt composer capturing speech"))
        compose.onNodeWithText("00:17").assertExists()

        compose.runOnIdle {
            state = PromptComposerViewModel.UiState(
                draft = "check deploy logs",
                recording = PromptComposerViewModel.RecordingState.Transcribing,
                amplitude = 0f,
                hasDetectedSpeech = false,
            )
        }

        // Transcribing surface: the spinner row + the "Transcribing…" label.
        compose.onNodeWithTag(COMPOSER_TRANSCRIBING_SPINNER_TAG)
            .assert(hasContentDescription("Prompt composer transcribing"))
        compose.onNodeWithText("Transcribing…").assertExists()
        compose.onNodeWithText("TRANSCRIBING").assertDoesNotExist()
        // Issue #508: Cancel + Send are available during transcription (no
        // persistent Auto-send toggle).
        compose.onNodeWithTag(COMPOSER_CANCEL_TRANSCRIPTION_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_STOP_SEND_TAG).assertIsDisplayed()
    }

    @Test
    fun micPressStartsRecordingBeforeRelease() {
        var micTaps = 0
        var state by mutableStateOf(PromptComposerViewModel.UiState())
        renderInteractiveComposer(
            state = { state },
            onStateChange = { state = it },
            onMicTapCount = { micTaps += 1 },
        )

        compose.onNodeWithTag(COMPOSER_MIC_TAG)
            .assertIsDisplayed()
            .performTouchInput {
                down(center)
            }

        compose.waitForIdle()

        assertEquals(1, micTaps)
        assertEquals(PromptComposerViewModel.RecordingState.Recording, state.recording)
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_TO_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_STOP_SEND_TAG).assertIsDisplayed()

        compose.onRoot().performTouchInput { up() }
    }

    @Test
    fun micSwipeUpKeepsRecordingOpenAfterRelease() {
        var micTaps = 0
        var lockCalls = 0
        var state by mutableStateOf(PromptComposerViewModel.UiState())
        renderInteractiveComposer(
            state = { state },
            onStateChange = { state = it },
            onMicTapCount = { micTaps += 1 },
            onLockRecording = {
                lockCalls += 1
                if (state.recording == PromptComposerViewModel.RecordingState.Recording) {
                    state = state.copy(recordingLocked = true)
                }
            },
        )

        compose.onNodeWithTag(COMPOSER_MIC_TAG)
            .assertIsDisplayed()
            .performTouchInput {
                down(center)
                moveBy(Offset(x = 0f, y = -220f))
                up()
            }

        compose.waitForIdle()

        assertEquals(1, micTaps)
        assertEquals(1, lockCalls)
        assertEquals(PromptComposerViewModel.RecordingState.Recording, state.recording)
        assertTrue(state.recordingLocked)
        compose.onNodeWithTag(COMPOSER_RECORDING_LOCKED_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_TO_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_STOP_SEND_TAG).assertIsDisplayed()
    }

    @Test
    fun micSwipeUpLocksAfterPressRecomposesAwayMic() {
        var micTaps = 0
        var lockCalls = 0
        var state by mutableStateOf(PromptComposerViewModel.UiState())
        renderInteractiveComposer(
            state = { state },
            onStateChange = { state = it },
            onMicTapCount = { micTaps += 1 },
            onLockRecording = {
                lockCalls += 1
                if (state.recording == PromptComposerViewModel.RecordingState.Recording) {
                    state = state.copy(recordingLocked = true)
                }
            },
        )

        compose.onNodeWithTag(COMPOSER_MIC_TAG)
            .assertIsDisplayed()
            .performTouchInput {
                down(center)
            }

        compose.waitForIdle()

        assertEquals(1, micTaps)
        assertEquals(PromptComposerViewModel.RecordingState.Recording, state.recording)
        compose.onNodeWithTag(COMPOSER_MIC_TAG).assertDoesNotExist()

        compose.onRoot().performTouchInput {
            moveBy(Offset(x = 0f, y = -220f))
            up()
        }

        compose.waitForIdle()

        assertEquals(1, lockCalls)
        assertEquals(PromptComposerViewModel.RecordingState.Recording, state.recording)
        assertTrue(state.recordingLocked)
        compose.onNodeWithTag(COMPOSER_RECORDING_LOCKED_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_TO_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_STOP_SEND_TAG).assertIsDisplayed()
    }

    @Test
    fun micReleaseWithoutLockKeepsTapRecordingSemantics() {
        var micTaps = 0
        var state by mutableStateOf(PromptComposerViewModel.UiState())
        renderInteractiveComposer(
            state = { state },
            onStateChange = { state = it },
            onMicTapCount = { micTaps += 1 },
        )

        compose.onNodeWithTag(COMPOSER_MIC_TAG)
            .assertIsDisplayed()
            .performTouchInput {
                down(center)
                moveBy(Offset(x = 12f, y = -12f))
                up()
            }

        compose.waitForIdle()

        assertEquals(1, micTaps)
        assertEquals(PromptComposerViewModel.RecordingState.Recording, state.recording)
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_TO_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_STOP_SEND_TAG).assertIsDisplayed()
    }

    private fun renderInteractiveComposer(
        state: () -> PromptComposerViewModel.UiState,
        onStateChange: (PromptComposerViewModel.UiState) -> Unit,
        onMicTapCount: () -> Unit,
        onLockRecording: () -> Unit = {},
    ) {
        fun nextStateAfterMicTap(): PromptComposerViewModel.UiState {
            val current = state()
            return when (current.recording) {
                PromptComposerViewModel.RecordingState.Idle -> current.copy(
                    recording = PromptComposerViewModel.RecordingState.Recording,
                    recordingElapsedMs = 1_000L,
                )
                PromptComposerViewModel.RecordingState.Recording -> current.copy(
                    recording = PromptComposerViewModel.RecordingState.Transcribing,
                )
                PromptComposerViewModel.RecordingState.Transcribing -> current
            }
        }

        compose.setContent {
            PocketShellTheme {
                SheetContent(
                    state = state(),
                    onClose = {},
                    onDraftChange = {},
                    onMicTap = {
                        onMicTapCount()
                        onStateChange(nextStateAfterMicTap())
                    },
                    onLockRecording = onLockRecording,
                    onSend = {},
                    onCancelRecording = {
                        onStateChange(
                            state().copy(recording = PromptComposerViewModel.RecordingState.Idle),
                        )
                    },
                )
            }
        }
    }

    @Test
    fun attachmentsRenderAsSquareTilesWrapAndRemoveFromCorner() {
        val first = "~/.pocketshell/attachments/host-1/20260606-120000-01-very-long-file-name-that-should-not-stretch-layout.txt"
        val image = "~/.pocketshell/attachments/host-1/20260606-120000-02-screenshot.png"
        val third = "~/.pocketshell/attachments/host-1/20260606-120000-03-data.csv"
        val longDisplayName = "20260606-120000-01-very-long-file-name-that-should-not-stretch-layout.txt"
        val imageUri = writeTinyPreviewPng("composer-attachment-preview.png")
        var state by mutableStateOf(
            PromptComposerViewModel.UiState(
                attachments = listOf(
                    PromptComposerViewModel.StagedAttachment(
                        remotePath = first,
                        displayName = longDisplayName,
                    ),
                    PromptComposerViewModel.StagedAttachment(
                        remotePath = image,
                        displayName = "20260606-120000-02-screenshot.png",
                        previewUri = imageUri,
                        mimeType = "image/png",
                    ),
                    PromptComposerViewModel.StagedAttachment(
                        remotePath = third,
                        displayName = "20260606-120000-03-data.csv",
                    ),
                ),
            ),
        )
        val removed = mutableListOf<String>()

        compose.setContent {
            PocketShellTheme {
                Box(modifier = Modifier.width(180.dp)) {
                    SheetContent(
                        state = state,
                        onClose = {},
                        onDraftChange = {},
                        onMicTap = {},
                        onSend = {},
                        onRemoveAttachment = { remotePath ->
                            removed += remotePath
                            state = state.copy(
                                attachments = state.attachments.filterNot { it.remotePath == remotePath },
                            )
                        },
                    )
                }
            }
        }

        compose.onNodeWithTag(COMPOSER_ATTACHMENT_CHIPS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(composerAttachmentChipTestTag(first))
            .assertWidthIsEqualTo(ATTACHMENT_TILE_SIZE)
            .assertHeightIsEqualTo(ATTACHMENT_TILE_SIZE)
        compose.onNodeWithTag(composerAttachmentChipTestTag(image))
            .assertWidthIsEqualTo(ATTACHMENT_TILE_SIZE)
            .assertHeightIsEqualTo(ATTACHMENT_TILE_SIZE)
        compose.onNodeWithTag(composerAttachmentTypeTileTestTag(first)).assertIsDisplayed()
        compose.onNodeWithTag(composerAttachmentTypeTileTestTag(third)).assertIsDisplayed()
        compose.onNode(hasContentDescription("Attachment $longDisplayName")).assertExists()
        compose.onNodeWithTag(composerAttachmentRemoveTestTag(image))
            .assert(hasContentDescription("Remove 20260606-120000-02-screenshot.png"))
            .assert(hasClickAction())
            .assertWidthIsEqualTo(ATTACHMENT_TILE_REMOVE_TOUCH_SIZE)
            .assertHeightIsEqualTo(ATTACHMENT_TILE_REMOVE_TOUCH_SIZE)

        val firstBounds = compose.onNodeWithTag(composerAttachmentChipTestTag(first))
            .fetchSemanticsNode()
            .boundsInRoot
        val imageBounds = compose.onNodeWithTag(composerAttachmentChipTestTag(image))
            .fetchSemanticsNode()
            .boundsInRoot
        val thirdBounds = compose.onNodeWithTag(composerAttachmentChipTestTag(third))
            .fetchSemanticsNode()
            .boundsInRoot
        assertEquals(firstBounds.top, imageBounds.top, 1f)
        assertTrue("third tile should wrap below the first row", thirdBounds.top > firstBounds.top)

        compose.onNodeWithTag(composerAttachmentRemoveTestTag(image)).performClick()

        assertEquals(listOf(image), removed)
        compose.onNodeWithTag(composerAttachmentChipTestTag(image)).assertDoesNotExist()
        compose.onNodeWithTag(composerAttachmentChipTestTag(first)).assertIsDisplayed()
        compose.onNodeWithTag(composerAttachmentChipTestTag(third)).assertIsDisplayed()
    }

    @Test
    fun longDraftScrollsInsideComposerAndKeepsControlsTappable() {
        val longDraft = (1..80).joinToString(separator = "\n") { line ->
            "line $line: keep writing the prompt without hiding controls"
        }
        var micTaps = 0
        var attachTaps = 0
        val sendModes = mutableListOf<Boolean>()

        compose.setContent {
            PocketShellTheme {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(520.dp),
                ) {
                    SheetContent(
                        state = PromptComposerViewModel.UiState(draft = longDraft),
                        onClose = {},
                        onDraftChange = {},
                        onMicTap = { micTaps += 1 },
                        onSend = { withEnter -> sendModes += withEnter },
                        onAttachFiles = { attachTaps += 1 },
                    )
                }
            }
        }

        // Issue #453: the Idle controls collapse to attach + mic + a single
        // Send (the Insert button is gone). With a non-empty draft the Send
        // affordance is enabled and tappable even under a tall draft.
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_ATTACH_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithTag(COMPOSER_MIC_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG).assertIsDisplayed().performClick()

        compose.runOnIdle {
            assertEquals(1, attachTaps)
            assertEquals(1, micTaps)
            // The single Send always submits with Enter.
            assertEquals(listOf(true), sendModes)
        }
    }

    @Test
    fun typedDraftSendEnterReachesDockerShell() = runBlocking {
        val key = InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

        val viewModel = SessionViewModel(
            applicationContext = InstrumentationRegistry.getInstrumentation().targetContext,
        )
        val marker = "pocketshell-composer-e2e-${System.currentTimeMillis()}"

        val handle = withTimeout(20_000) {
            openShell(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
            )
        }

        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val received = StringBuilder()
        val outputJob = launch(Dispatchers.Default) {
            viewModel.terminalState.output.collect { bytes ->
                synchronized(received) {
                    received.append(bytes.toString(Charsets.UTF_8))
                }
            }
        }
        val producerJob = viewModel.terminalState.attachExternalProducer(
            scope = producerScope,
            stdout = createStdoutFlow(handle.shell),
            remoteStdin = handle.shell.outputStream,
        )

        try {
            var state by mutableStateOf(PromptComposerViewModel.UiState())
            compose.setContent {
                PocketShellTheme {
                    SheetContent(
                        state = state,
                        onClose = {},
                        onDraftChange = { state = state.copy(draft = it) },
                        onMicTap = {},
                        onSend = { withEnter -> viewModel.sendText(state.draft, withEnter) },
                    )
                }
            }

            compose.onNodeWithTag(COMPOSER_DRAFT_TAG)
                .performTextInput("printf '$marker\\n'")
            compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG)
                .performClick()

            withTimeout(10_000) {
                while (synchronized(received) { !received.contains(marker) }) {
                    delay(100)
                }
            }
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            outputJob.cancel()
            viewModel.terminalState.detachExternalProducer()
            runCatching { handle.shell.close() }
            runCatching { handle.sessionChannel.close() }
            runCatching { handle.client.disconnect() }
        }
    }

    private fun renderComposer(state: () -> PromptComposerViewModel.UiState) {
        compose.setContent {
            PocketShellTheme {
                SheetContent(
                    state = state(),
                    onClose = {},
                    onDraftChange = {},
                    onMicTap = {},
                    onSend = {},
                )
            }
        }
    }

    private fun writeTinyPreviewPng(name: String): Uri {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "share-target-tests").apply { mkdirs() }
        val file = File(dir, name)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.RED)
            file.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        } finally {
            bitmap.recycle()
        }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.shareprovider",
            file,
        )
    }
}
