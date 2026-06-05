package com.pocketshell.app.composer

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.createStdoutFlow
import com.pocketshell.app.proof.openShell
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.session.ConversationPane
import com.pocketshell.app.session.SESSION_CONVERSATION_COMPOSER_INPUT_TAG
import com.pocketshell.app.session.SessionViewModel
import com.pocketshell.app.voice.ADD_PROMPT_CHIP_LABEL
import com.pocketshell.app.voice.BottomChipControls
import com.pocketshell.app.voice.SESSION_MIC_FAB_TAG
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #453 (redo): capture the prompt composer states EMBEDDED IN A RUNNING
 * SESSION, not as isolated harness renders.
 *
 * The reviewer rejected the prior `PromptComposerVisualScreenshotTest` captures
 * because they rendered `SheetContent` on a plain background with a "Prompt
 * Composer" title bar — no session chrome. This test instead:
 *
 *  1. Connects live to the deterministic Docker `agents` fixture over SSH
 *     (host port 2222) and drives a real [SessionViewModel] agent conversation
 *     (same code path as `ConversationInteractE2eTest`).
 *  2. Renders the production [ConversationPane] feed (the agent/Conversation
 *     pane chrome the user actually sees) at the top.
 *  3. Renders the production session band [BottomChipControls] — the FIRST
 *     thing the user sees before the sheet opens, and the surface whose mic
 *     glyph was the maintainer's #1 complaint. This proves the band mic is now
 *     the proper microphone (the shared [com.pocketshell.uikit.components.MicButton]
 *     no longer draws `Text("●")`).
 *  4. Renders the production composer [SheetContent] for each state (idle,
 *     recording, transcribing, text-inserted) BELOW the live conversation feed,
 *     so each composer state is shown inside running-session chrome.
 *
 * Capture path: [androidx.compose.ui.test.captureToImage] on the Compose root —
 * the same authoritative viewport capture `TmuxSessionVoiceSurfaceUiTest` uses.
 * Full-device `UiAutomation.takeScreenshot()` returns blank on this AVD, so the
 * Compose viewport capture is the reliable artifact. Each frame includes the
 * conversation feed + band + composer in one image for side-by-side review
 * against `issue-453-composer-states-mockup.png`.
 */
@RunWith(AndroidJUnit4::class)
class InSessionComposerStatesScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capturesComposerStatesEmbeddedInARunningSession() = runBlocking<Unit> {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        // --- Live SSH session to Docker agents, real conversation feed. ---
        val tailSession = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()
        val writerHandle = openShell(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
        )

        val viewModel = SessionViewModel(
            applicationContext = InstrumentationRegistry.getInstrumentation().targetContext,
        )
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val producerJob = viewModel.terminalState.attachExternalProducer(
            scope = producerScope,
            stdout = createStdoutFlow(writerHandle.shell),
            remoteStdin = writerHandle.shell.outputStream,
        )

        // The composer states we paint over the live conversation feed.
        var composerState by mutableStateOf(
            PromptComposerViewModel.UiState(
                draft = "",
                recording = PromptComposerViewModel.RecordingState.Idle,
                amplitude = 0f,
                hasDetectedSpeech = false,
            ),
        )

        try {
            val detection = AgentDetection(
                agent = AgentKind.ClaudeCode,
                sourcePath = CLAUDE_PATH,
                sessionId = CLAUDE_PATH.substringAfterLast('/').substringBeforeLast('.'),
                confidence = AgentDetection.Confidence.RecentFile,
            )
            // A realistic feed so the captured frame shows the agent/Conversation
            // chrome above the band + composer — the running-session context the
            // reviewer asked for.
            val seededFeed = listOf(
                ConversationEvent.Message(
                    id = "seed-assistant-1",
                    agent = AgentKind.ClaudeCode,
                    role = ConversationRole.Assistant,
                    text = "Welcome back! What would you like to do today?",
                ),
            )
            viewModel.startAgentConversationForTest(
                detection = detection,
                initialEvents = seededFeed,
            )
            viewModel.attachSessionForAgentRetryForTest(tailSession)

            compose.setContent {
                PocketShellTheme {
                    val conv by viewModel.agentConversation.collectAsState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(PocketShellColors.Background)
                            .testTag(ROOT_TAG),
                    ) {
                        // Live agent/Conversation feed — the running-session chrome.
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            ConversationPane(
                                events = conv.events,
                                onSendToAgent = { text -> viewModel.sendToAgentResult(text) },
                                agentName = detection.agent.displayName,
                            )
                        }
                        // The session band the user sees BEFORE the sheet opens —
                        // its mic FAB is the shared MicButton, now a real mic glyph.
                        BottomChipControls(
                            chips = listOf(AgentCommandsChipLabel),
                            onChipTap = {},
                            onDictateTap = {},
                            onShowKeyboardTap = null,
                            onAddSnippetTap = null,
                            addSnippetLabel = ADD_PROMPT_CHIP_LABEL,
                            addSnippetIcon = null,
                            onProjectNavigationTap = null,
                            modifier = Modifier.testTag(BAND_TAG),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // The composer sheet body, embedded directly under the
                        // running session so each state is captured in context.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(PocketShellColors.Surface)
                                .navigationBarsPadding(),
                            verticalArrangement = Arrangement.Top,
                        ) {
                            SheetContent(
                                state = composerState,
                                onClose = {},
                                onDraftChange = {},
                                onMicTap = {},
                                onSend = {},
                            )
                        }
                    }
                }
            }

            // Composer must be mounted (the live feed's composer input proves the
            // ConversationPane chrome rendered).
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag(SESSION_CONVERSATION_COMPOSER_INPUT_TAG)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            // The band mic FAB (shared MicButton) is present — proves the band's
            // mic glyph renders in a running session.
            compose.onNodeWithTag(SESSION_MIC_FAB_TAG, useUnmergedTree = true).assertIsDisplayed()

            // State 1: Idle.
            compose.onNodeWithText("Compose prompt…").assertExists()
            compose.waitForIdle()
            captureRoot("issue453-insession-01-idle")

            // State 2: Recording.
            compose.runOnIdle {
                composerState = composerState.copy(
                    recording = PromptComposerViewModel.RecordingState.Recording,
                    amplitude = 0.8f,
                    hasDetectedSpeech = true,
                    recordingElapsedMs = 17_000L,
                )
            }
            compose.onNodeWithTag(COMPOSER_TIMER_TAG).assertIsDisplayed()
            // The recording row now offers the two explicit stop actions (To
            // field / Send) instead of the removed in-surface "Stop" label.
            compose.onNodeWithTag(COMPOSER_TO_FIELD_TAG).assertIsDisplayed()
            compose.onNodeWithTag(COMPOSER_STOP_SEND_TAG).assertIsDisplayed()
            compose.onNodeWithText("Stop").assertDoesNotExist()
            compose.waitForIdle()
            captureRoot("issue453-insession-02-recording")

            // State 3: Transcribing.
            compose.runOnIdle {
                composerState = composerState.copy(
                    recording = PromptComposerViewModel.RecordingState.Transcribing,
                    amplitude = 0f,
                    hasDetectedSpeech = false,
                )
            }
            compose.onNodeWithText("Transcribing…").assertExists()
            compose.onNodeWithTag(COMPOSER_TRANSCRIBING_SPINNER_TAG).assertIsDisplayed()
            compose.waitForIdle()
            captureRoot("issue453-insession-03-transcribing")

            // State 4: Text-inserted.
            compose.runOnIdle {
                composerState = PromptComposerViewModel.UiState(
                    draft = "Refactor the tree structure in the projects list to reduce vertical space and improve readability.",
                    recording = PromptComposerViewModel.RecordingState.Idle,
                    amplitude = 0f,
                )
            }
            compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertIsDisplayed()
            compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG).assertIsDisplayed()
            compose.waitForIdle()
            captureRoot("issue453-insession-04-text-inserted")
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            viewModel.terminalState.detachExternalProducer()
            runCatching { writerHandle.shell.close() }
            runCatching { writerHandle.sessionChannel.close() }
            runCatching { writerHandle.client.disconnect() }
            // Do NOT close tailSession — sshj raises a transport exception when a
            // session is torn down here; same rationale as ConversationInteractE2eTest.
        }
    }

    private fun captureRoot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create screenshot dir: ${dir.absolutePath}"
        }
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Could not write screenshot: ${file.absolutePath}"
            }
        }
        bitmap.recycle()
        println("ISSUE453_INSESSION_SCREENSHOT ${file.absolutePath}")
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private companion object {
        const val ROOT_TAG = "issue453:insession-root"
        const val BAND_TAG = "issue453:insession-band"
        const val DEVICE_DIR_NAME = "issue-453-insession"
        const val AgentCommandsChipLabel = "/ commands"
        const val CLAUDE_PATH =
            "/home/testuser/.claude/projects/-workspace-pocketshell/pocketshell-claude.jsonl"
    }
}
