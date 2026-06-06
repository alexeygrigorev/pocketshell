package com.pocketshell.app.projects

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.assistant.AssistantSshExecutor
import com.pocketshell.app.assistant.AssistantSshParams
import com.pocketshell.app.assistant.AssistantTools
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.voice.ASSISTANT_CHOOSING_TAG
import com.pocketshell.app.voice.ASSISTANT_CONFIRM_TAG
import com.pocketshell.app.voice.assistantChoiceTag
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
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Connected journey for #442 (follow-up to #434, AC2/AC6): an ambiguous spoken
 * folder name drives the assistant into the `Choosing` disambiguation chooser,
 * the user taps a candidate, and — now that `onChoose` is bridged from the
 * screen to the controller — the pick resolves the gate and `start_session`
 * runs in the chosen folder (through the existing confirm gate).
 *
 * Hermetic: a real [FolderListViewModel] + [SessionAssistantController] +
 * AssistantAgentLoop + FolderResolver are driven against a fake gateway and a
 * scripted LLM, so the test reaches the new screen wiring without real Docker
 * yet exercises the production controller/loop/resolver path end to end.
 */
@RunWith(AndroidJUnit4::class)
class HostDetailAssistantFolderDisambiguationTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: AppDatabase
    private val hostId = 442L

    // Two "workshop" folders under the watched root → resolver returns Ambiguous.
    private val workshopA = "/home/u/code/rov/workshop"
    private val workshopB = "/home/u/code/notes/workshop"

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
            SshKeyEntity(name = "issue442-key", privateKeyPath = "/tmp/issue442"),
        )
        db.hostDao().insert(
            HostEntity(
                id = hostId,
                name = "issue442-host",
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
    fun ambiguousFolder_pickResolvesChooser_andStartsSessionInChosenFolder() {
        val fakeGateway = DisambiguationGateway(
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
                "/home/u/code" to listOf("/home/u/code/pocketshell", workshopA, workshopB),
            ),
        )
        // resolve_folder("the workshop") → Ambiguous; after the user picks workshopB
        // the loop relays its cwd, so the model issues start_session with it.
        val fakeAssistant = DisambigScriptedAssistantClient(
            ArrayDeque(
                listOf(
                    disambigToolCall(
                        AssistantTools.RESOLVE_FOLDER,
                        """{"host":"issue442-host","query":"the workshop"}""",
                        id = "rf",
                    ),
                    disambigToolCall(
                        AssistantTools.START_SESSION,
                        """{"host":"issue442-host","cwd":"$workshopB","agent":"claude"}""",
                        id = "ss",
                    ),
                    LlmResponse("Started a session in the workshop.", stopReason = StopReason.EndTurn),
                ),
            ),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val viewModel = FolderListViewModel(
            gateway = fakeGateway,
            hostDao = db.hostDao(),
            projectRootDao = db.projectRootDao(),
            applicationContext = context,
            assistantClientFactory = AssistantLlmClientFactory(
                store = DisambigAssistantConfigStore(),
                clientBuilder = { _: AssistantProvider, _: AssistantProviderConfig -> fakeAssistant },
            ),
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            forwardingController = ForwardingController(context),
            // Off-main-thread test: drive the foreground gate via the test
            // seam instead of touching the main-thread-affine
            // ProcessLifecycleOwner registry.
            attachLifecycle = false,
        ).apply {
            setProcessStartedForTest(true)
            setAssistantSshExecutor(DisambigNoOpAssistantSshExecutor)
        }

        compose.setContent {
            PocketShellTheme {
                FolderListScreen(
                    hostId = hostId,
                    hostName = "issue442-host",
                    hostname = "h.example",
                    port = 22,
                    username = "u",
                    keyPath = "/tmp/issue442",
                    passphrase = null,
                    onBack = {},
                    onOpenSession = { _, _ -> },
                    onSessionCreated = { _, _ -> },
                    onBrowseRepos = { _ -> },
                    onOpenWorkspaceSettings = {},
                    onEditEnv = { _, _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                )
            }
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            fakeGateway.listCallCount.get() >= 1 &&
                compose.onAllNodesWithTag(folderTreeRootTestTag("/home/u/code"))
                    .fetchSemanticsNodes().isNotEmpty()
        }

        // Open the assistant panel (now behind the header kebab, #522) and
        // submit an ambiguous request.
        compose.onNodeWithTag(FOLDER_LIST_OVERFLOW_TAG).performClick()
        compose.onNodeWithTag(FOLDER_LIST_ASSISTANT_TAG).performClick()
        compose.onNodeWithTag(FOLDER_LIST_ASSISTANT_PROMPT_TAG)
            .performTextInput("open a claude session in the workshop")
        compose.onNodeWithTag(FOLDER_LIST_ASSISTANT_SUBMIT_TAG).performClick()

        // The chooser appears with both ambiguous candidates.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(ASSISTANT_CHOOSING_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(ASSISTANT_CHOOSING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(assistantChoiceTag(workshopA)).assertIsDisplayed()
        compose.onNodeWithTag(assistantChoiceTag(workshopB)).assertIsDisplayed()
        assertTrue(
            "session must not start before the user picks a folder",
            fakeGateway.createdSessions.isEmpty(),
        )

        captureViewportArtifact("issue442-disambiguation-chooser-viewport.png")

        // Tap workshopB. This must resolve the Choosing gate (the previously
        // unwired seam) and advance to the confirm gate for start_session.
        compose.onNodeWithTag(assistantChoiceTag(workshopB)).performClick()

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(ASSISTANT_CONFIRM_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Start claude session in $workshopB on issue442-host")
            .assertIsDisplayed()
        assertTrue(
            "picking a folder must not start the session before confirmation",
            fakeGateway.createdSessions.isEmpty(),
        )

        captureViewportArtifact("issue442-disambiguation-confirm-viewport.png")

        // Confirm → start_session runs in the chosen folder.
        compose.onNodeWithTag(ASSISTANT_CONFIRM_TAG).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            fakeGateway.createdSessions.isNotEmpty()
        }
        assertEquals(
            "the session must be created in the folder the user picked",
            listOf(workshopB),
            fakeGateway.createdSessions,
        )

        captureViewportArtifact("issue442-disambiguation-created-viewport.png")
    }

    @Test
    fun ambiguousFolder_cancelDismissesChooser_andNoSessionStarts() {
        val fakeGateway = DisambiguationGateway(
            rows = emptyList(),
            projectFoldersByRoot = mapOf(
                "/home/u/code" to listOf(workshopA, workshopB),
            ),
        )
        val fakeAssistant = DisambigScriptedAssistantClient(
            ArrayDeque(
                listOf(
                    disambigToolCall(
                        AssistantTools.RESOLVE_FOLDER,
                        """{"host":"issue442-host","query":"the workshop"}""",
                        id = "rf",
                    ),
                    // The loop cancels on a chooser dismissal, so the model is
                    // never consulted again; this trailing turn is a safety net.
                    LlmResponse("Cancelled.", stopReason = StopReason.EndTurn),
                ),
            ),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val viewModel = FolderListViewModel(
            gateway = fakeGateway,
            hostDao = db.hostDao(),
            projectRootDao = db.projectRootDao(),
            applicationContext = context,
            assistantClientFactory = AssistantLlmClientFactory(
                store = DisambigAssistantConfigStore(),
                clientBuilder = { _: AssistantProvider, _: AssistantProviderConfig -> fakeAssistant },
            ),
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            forwardingController = ForwardingController(context),
            // Off-main-thread test: drive the foreground gate via the test
            // seam instead of touching the main-thread-affine
            // ProcessLifecycleOwner registry.
            attachLifecycle = false,
        ).apply {
            setProcessStartedForTest(true)
            setAssistantSshExecutor(DisambigNoOpAssistantSshExecutor)
        }

        compose.setContent {
            PocketShellTheme {
                FolderListScreen(
                    hostId = hostId,
                    hostName = "issue442-host",
                    hostname = "h.example",
                    port = 22,
                    username = "u",
                    keyPath = "/tmp/issue442",
                    passphrase = null,
                    onBack = {},
                    onOpenSession = { _, _ -> },
                    onSessionCreated = { _, _ -> },
                    onBrowseRepos = { _ -> },
                    onOpenWorkspaceSettings = {},
                    onEditEnv = { _, _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                )
            }
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            fakeGateway.listCallCount.get() >= 1 &&
                compose.onAllNodesWithTag(folderTreeRootTestTag("/home/u/code"))
                    .fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag(FOLDER_LIST_OVERFLOW_TAG).performClick()
        compose.onNodeWithTag(FOLDER_LIST_ASSISTANT_TAG).performClick()
        compose.onNodeWithTag(FOLDER_LIST_ASSISTANT_PROMPT_TAG)
            .performTextInput("open a claude session in the workshop")
        compose.onNodeWithTag(FOLDER_LIST_ASSISTANT_SUBMIT_TAG).performClick()

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(ASSISTANT_CHOOSING_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(ASSISTANT_CHOOSING_TAG).assertIsDisplayed()

        // Cancel the chooser. This must resolve the gate (cancelChoice) and
        // dismiss the chooser without creating any session.
        compose.onNodeWithText("Cancel").performClick()

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(ASSISTANT_CHOOSING_TAG).fetchSemanticsNodes().isEmpty()
        }
        assertTrue(
            "cancelling the chooser must not start a session",
            fakeGateway.createdSessions.isEmpty(),
        )
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
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
            ?: return
        val outDir = File(mediaRoot, "additional_test_output/issue442-folder-disambiguation").apply {
            if (!exists()) mkdirs()
        }
        FileOutputStream(File(outDir, name)).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
}

private fun disambigToolCall(name: String, args: String, id: String = name) =
    LlmResponse(null, listOf(LlmToolCall(id, name, args)), StopReason.ToolUse)

private class DisambigScriptedAssistantClient(
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

private class DisambigAssistantConfigStore : AssistantConfigStore {
    override fun loadSettings(): AssistantSettings = AssistantSettings()
    override fun setProvider(provider: AssistantProvider) = Unit
    override fun setEndpoint(provider: AssistantProvider, baseUrl: String, model: String) = Unit
    override fun saveKey(provider: AssistantProvider, key: CharArray) = Unit
    override fun loadKey(provider: AssistantProvider): CharArray = "test-key".toCharArray()
    override fun clearKey(provider: AssistantProvider) = Unit
}

private object DisambigNoOpAssistantSshExecutor : AssistantSshExecutor {
    override suspend fun <T> withSession(
        params: AssistantSshParams,
        block: suspend (SshSession) -> T,
    ): Result<T> = Result.success(block(DisambigNoOpSshSession))
}

private object DisambigNoOpSshSession : SshSession {
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

private class DisambiguationGateway(
    private val rows: List<FolderSessionRow>,
    private val projectFoldersByRoot: Map<String, List<String>>,
) : FolderListGateway {
    val listCallCount = AtomicInteger(0)
    val createdSessions = mutableListOf<String>()

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
    ): Result<String> {
        createdSessions += cwd
        return Result.success(sessionName)
    }

    override suspend fun createEmptyProject(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        parentPath: String,
        folderName: String,
    ): Result<String> = Result.success("$parentPath/$folderName")

    override suspend fun importFile(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        folderPath: String,
        payload: FolderImportPayload,
    ): Result<String> = Result.success("$folderPath/${payload.remoteName}")

    override suspend fun killSession(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        sessionName: String,
    ): Result<Unit> = Result.success(Unit)
}
