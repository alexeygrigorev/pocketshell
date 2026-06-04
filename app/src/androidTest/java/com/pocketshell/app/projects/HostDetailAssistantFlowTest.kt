package com.pocketshell.app.projects

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.assistant.AssistantSshExecutor
import com.pocketshell.app.assistant.AssistantSshParams
import com.pocketshell.app.assistant.AssistantTools
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.session.InlineDictationViewModel
import com.pocketshell.app.voice.ASSISTANT_CONFIRM_TAG
import com.pocketshell.app.voice.ASSISTANT_CORRECTION_FIELD_TAG
import com.pocketshell.app.voice.ASSISTANT_CORRECTION_MIC_TAG
import com.pocketshell.app.voice.ASSISTANT_CORRECT_TAG
import com.pocketshell.app.voice.ASSISTANT_SEND_CORRECTION_TAG
import com.pocketshell.core.assistant.AssistantLlmClient
import com.pocketshell.core.assistant.AssistantLlmClientFactory
import com.pocketshell.core.assistant.AssistantProvider
import com.pocketshell.core.assistant.AssistantProviderConfig
import com.pocketshell.core.assistant.AssistantSettings
import com.pocketshell.core.assistant.LlmMessage
import com.pocketshell.core.assistant.LlmResponse
import com.pocketshell.core.assistant.LlmToolCall
import com.pocketshell.core.assistant.StopReason
import com.pocketshell.core.assistant.ToolChoice
import com.pocketshell.core.assistant.ToolSpec
import com.pocketshell.core.assistant.store.AssistantConfigStore
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.core.voice.SpeechAudioGuard
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class HostDetailAssistantFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: AppDatabase
    private val hostId = 334L

    @Before
    fun setUp(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        runCatching {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.RECORD_AUDIO,
            )
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "issue334-key", privateKeyPath = "/tmp/issue334"),
        )
        db.hostDao().insert(
            HostEntity(
                id = hostId,
                name = "issue334-host",
                hostname = "h.example",
                port = 22,
                username = "u",
                keyId = keyId,
            ),
        )
        db.projectRootDao().insert(
            ProjectRootEntity(
                hostId = hostId,
                label = "code",
                path = "/home/u/code",
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun assistantPromptCorrectionConfirmAndRefreshFlowUsesHostDetailContext() {
        val fakeGateway = HostDetailAssistantGateway(
            rows = listOf(
                FolderSessionRow(
                    sessionName = "claude-main",
                    lastActivity = 1_700_000_000L,
                    attached = true,
                    cwd = "/home/u/code/pocketshell",
                    agentKind = SessionAgentKind.Claude,
                ),
            ),
            projectFoldersByRoot = mapOf(
                "/home/u/code" to listOf("/home/u/code/pocketshell"),
            ),
        )
        val fakeAssistant = ScriptedAssistantClient(
            ArrayDeque(
                listOf(
                    toolCall(AssistantTools.GET_CONTEXT, "{}"),
                    toolCall(
                        AssistantTools.CREATE_PROJECT,
                        """{"host":"issue334-host","parent_path":"/home/u/code","folder_name":"notes"}""",
                    ),
                    toolCall(
                        AssistantTools.CREATE_PROJECT,
                        """{"host":"issue334-host","parent_path":"/home/u/code","folder_name":"journal"}""",
                    ),
                    LlmResponse("Created journal.", stopReason = StopReason.EndTurn),
                ),
            ),
        )
        val dictationViewModel = assistantDictationViewModel("under code", "call it journal")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val viewModel = FolderListViewModel(
            gateway = fakeGateway,
            hostDao = db.hostDao(),
            projectRootDao = db.projectRootDao(),
            applicationContext = context,
            assistantClientFactory = AssistantLlmClientFactory(
                store = FakeAssistantConfigStore(),
                clientBuilder = { _: AssistantProvider, _: AssistantProviderConfig -> fakeAssistant },
            ),
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            forwardingController = ForwardingController(context),
        ).apply {
            setAssistantSshExecutor(NoOpAssistantSshExecutor)
        }

        compose.setContent {
            PocketShellTheme {
                FolderListScreen(
                    hostId = hostId,
                    hostName = "issue334-host",
                    hostname = "h.example",
                    port = 22,
                    username = "u",
                    keyPath = "/tmp/issue334",
                    passphrase = null,
                    onBack = {},
                    onOpenSession = { _, _ -> },
                    onSessionCreated = { _, _ -> },
                    onBrowseRepos = { _ -> },
                    onOpenWorkspaceSettings = {},
                    onEditEnv = { _, _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                    assistantDictationViewModel = dictationViewModel,
                )
            }
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            fakeGateway.listCallCount.get() >= 1 &&
                compose.onAllNodesWithTag(folderTreeRootTestTag("/home/u/code"))
                    .fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag(FOLDER_LIST_ASSISTANT_TAG).performClick()
        compose.onNodeWithTag(FOLDER_LIST_ASSISTANT_PROMPT_TAG)
            .performTextInput("create notes")
        compose.onNodeWithTag(FOLDER_LIST_ASSISTANT_PROMPT_MIC_TAG).performClick()
        compose.onNodeWithTag(FOLDER_LIST_ASSISTANT_PROMPT_MIC_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onNodeWithTag(FOLDER_LIST_ASSISTANT_PROMPT_TAG)
                .fetchSemanticsNode()
                .config
                .toString()
                .contains("create notes under code")
        }
        compose.onNodeWithTag(FOLDER_LIST_ASSISTANT_PROMPT_TAG)
            .assertTextContains("create notes under code")
        compose.onNodeWithTag(FOLDER_LIST_ASSISTANT_SUBMIT_TAG).performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            fakeAssistant.turns.size >= 2 &&
                compose.onAllNodesWithTag("assistant:candidate").fetchSemanticsNodes().isNotEmpty()
        }
        val contextResult = fakeAssistant.turns
            .flatMap { turn -> turn.flatMap { it.toolResults } }
            .single { it.toolCallId == "ctx" }
            .content
        assertTrue(contextResult.contains("workspace_roots:"))
        assertTrue(contextResult.contains("- code: /home/u/code"))
        assertTrue(contextResult.contains("  - pocketshell: /home/u/code/pocketshell (1 sessions)"))
        assertTrue(contextResult.contains("known_sessions:"))
        assertTrue(contextResult.contains("- claude-main"))

        compose.onNodeWithText("Create notes in /home/u/code on issue334-host")
            .assertIsDisplayed()
        assertTrue("candidate must not execute before user confirmation", fakeGateway.createdProjects.isEmpty())

        compose.onNodeWithTag(ASSISTANT_CORRECT_TAG).performClick()
        compose.onNodeWithTag(ASSISTANT_CORRECTION_FIELD_TAG).performTextInput("not notes")
        compose.onNodeWithTag(ASSISTANT_CORRECTION_MIC_TAG).performClick()
        compose.onNodeWithTag(ASSISTANT_CORRECTION_MIC_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onNodeWithTag(ASSISTANT_CORRECTION_FIELD_TAG)
                .fetchSemanticsNode()
                .config
                .toString()
                .contains("not notes call it journal")
        }
        compose.onNodeWithTag(ASSISTANT_CORRECTION_FIELD_TAG)
            .assertTextContains("not notes call it journal")
        compose.onNodeWithTag(ASSISTANT_SEND_CORRECTION_TAG).performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("assistant:candidate")
                .fetchSemanticsNodes()
                .any { it.config.toString().contains("journal") }
        }
        compose.onNodeWithText("Create journal in /home/u/code on issue334-host")
            .assertIsDisplayed()
        assertTrue("correction must not execute until revised candidate is confirmed", fakeGateway.createdProjects.isEmpty())

        val callsBeforeConfirm = fakeGateway.listCallCount.get()
        compose.onNodeWithTag(ASSISTANT_CONFIRM_TAG).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            fakeGateway.createdProjects == listOf("/home/u/code/journal") &&
                fakeGateway.listCallCount.get() > callsBeforeConfirm &&
                (viewModel.state.value as? FolderListUiState.Ready)
                    ?.treeRoots
                    ?.flatMap { it.folders }
                    ?.any { it.path == "/home/u/code/journal" } == true
        }
        compose.onNodeWithTag(FOLDER_LIST_CONTENT_TAG)
            .performScrollToNode(hasTestTag(folderRowTestTag("/home/u/code/journal")))
        compose.onNodeWithTag(folderHeaderLabelTag("/home/u/code/journal"), useUnmergedTree = true)
            .assertIsDisplayed()

        captureViewportArtifact("issue334-host-detail-assistant-flow-viewport.png")
    }

    private fun captureViewportArtifact(name: String) {
        val bitmap = runCatching {
            val image = compose.onNodeWithTag(FOLDER_LIST_SCREEN_TAG).captureToImage()
            Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also { out ->
                val pixels = IntArray(image.width * image.height)
                image.readPixels(pixels)
                out.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            }
        }.getOrNull() ?: return

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
            ?: return
        val outDir = File(mediaRoot, "additional_test_output/issue334-host-detail-assistant").apply {
            if (!exists()) mkdirs()
        }
        FileOutputStream(File(outDir, name)).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
}

private fun toolCall(name: String, args: String, id: String = if (name == AssistantTools.GET_CONTEXT) "ctx" else name) =
    LlmResponse(null, listOf(LlmToolCall(id, name, args)), StopReason.ToolUse)

private class ScriptedAssistantClient(
    private val script: ArrayDeque<LlmResponse>,
) : AssistantLlmClient {
    val turns = mutableListOf<List<LlmMessage>>()

    override suspend fun complete(
        messages: List<LlmMessage>,
        tools: List<ToolSpec>,
        toolChoice: ToolChoice?,
    ): Result<LlmResponse> {
        turns += messages.toList()
        return Result.success(script.removeFirstOrNull() ?: LlmResponse("Done.", stopReason = StopReason.EndTurn))
    }
}

private class FakeAssistantConfigStore : AssistantConfigStore {
    override fun loadSettings(): AssistantSettings = AssistantSettings()
    override fun setProvider(provider: AssistantProvider) = Unit
    override fun setEndpoint(provider: AssistantProvider, baseUrl: String, model: String) = Unit
    override fun saveKey(provider: AssistantProvider, key: CharArray) = Unit
    override fun loadKey(provider: AssistantProvider): CharArray = "test-key".toCharArray()
    override fun clearKey(provider: AssistantProvider) = Unit
}

private object NoOpAssistantSshExecutor : AssistantSshExecutor {
    override suspend fun <T> withSession(
        params: AssistantSshParams,
        block: suspend (SshSession) -> T,
    ): Result<T> = Result.success(block(NoOpSshSession))
}

private object NoOpSshSession : SshSession {
    override val isConnected: Boolean = true
    override suspend fun exec(command: String): ExecResult = ExecResult("", "", 0)
    override fun tail(path: String, onLine: (String) -> Unit): Job = Job()
    override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
        throw NotImplementedError()
    override fun startShell(): SshShell = throw NotImplementedError()
    override suspend fun uploadFile(file: File, remotePath: String): String = remotePath
    override suspend fun uploadStream(input: InputStream, length: Long, name: String, remotePath: String): String =
        remotePath
    override fun close() = Unit
}

private fun assistantDictationViewModel(vararg transcripts: String): InlineDictationViewModel {
    val queue = ArrayDeque(transcripts.toList())
    return InlineDictationViewModel(
        audioRecorder = object : PromptComposerViewModel.MicCapture {
            override fun start() = Unit
            // Issue #452: real-speech WAV so the silence guard lets the
            // dictation reach Whisper — this flow asserts the queued
            // transcripts reach the assistant.
            override fun stop(): ByteArray = SpeechAudioGuard.speechWavForTesting()
            override fun currentAmplitude(): Float = 1f
        },
        whisperClientFactory = WhisperClientFactory {
            object : WhisperClient {
                override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> =
                    Result.success(queue.removeFirstOrNull().orEmpty())
            }
        },
        apiKeyStorage = object : PromptComposerViewModel.ApiKeyVault {
            override fun save(key: CharArray) = Unit
            override fun load(): CharArray = "test-key".toCharArray()
            override fun clear() = Unit
        },
        voiceSettings = object : PromptComposerViewModel.VoiceSettingsSnapshot {
            override fun silenceWindowMs(): Long = InlineDictationViewModel.SILENCE_WINDOW_MS
            override fun whisperLanguageHint(): String? = null
        },
    )
}

private class HostDetailAssistantGateway(
    private val rows: List<FolderSessionRow>,
    private val projectFoldersByRoot: Map<String, List<String>>,
) : FolderListGateway {
    val listCallCount = AtomicInteger(0)
    val createdProjects = mutableListOf<String>()

    override suspend fun listSessionsWithFolder(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        watchedRoots: List<ProjectRootEntity>,
    ): FolderListResult {
        listCallCount.incrementAndGet()
        return FolderListResult.Sessions(
            rows = rows,
            projectFoldersByRoot = projectFoldersByRoot,
        )
    }

    override suspend fun createSession(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        sessionName: String,
        cwd: String,
        startCommand: String?,
    ): Result<String> = Result.success(sessionName)

    override suspend fun createEmptyProject(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        parentPath: String,
        folderName: String,
    ): Result<String> {
        val path = "$parentPath/$folderName"
        createdProjects += path
        return Result.success(path)
    }

    override suspend fun importFile(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        folderPath: String,
        payload: FolderImportPayload,
    ): Result<String> = Result.success("$folderPath/${payload.remoteName}")
}
