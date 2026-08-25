package com.pocketshell.app.projects

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * UI proof for the multi-profile session-type picker (audit #657 Gap-3,
 * issues #718 / #627 / #631, tracked by #723).
 *
 * The picker discovers Claude / Codex profiles host-side ([ProfilesGateway])
 * and renders a "Profile" [com.pocketshell.uikit.components.SegmentedToggle]
 * only when the engine has more than one profile. Picking a non-default
 * profile must thread its NAME into the launched `pocketshell agent`
 * command as `--profile '<name>'` so the agent runs against the right
 * `CLAUDE_CONFIG_DIR` / `CODEX_HOME` host-side. A silent break here launches
 * the agent against the WRONG config dir with green command-builder unit
 * tests (`SessionTypeChoiceCommandTest`), which never render the picker.
 *
 * This pins, on-device:
 *  - the Claude profile toggle is shown only when `claudeProfiles.size > 1`;
 *  - both discovered profiles are listed by name;
 *  - selecting a non-default Claude profile threads `--profile '<name>'`
 *    into the launched command, and the default profile emits no flag;
 *  - the same routing for Codex profiles;
 *  - a single-profile engine hides the toggle (no false picker).
 *
 * Drives [SessionTypePickerContent] directly (no SSH / sheet animation),
 * matching the sibling [SessionTypePickerSkipPermissionsUiTest].
 */
@RunWith(AndroidJUnit4::class)
class SessionTypeProfilePickerUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val claudeProfiles = listOf(
        ClaudeProfile(name = "default", default = true),
        ClaudeProfile(name = "work"),
        ClaudeProfile(name = "oss"),
    )
    private val codexProfiles = listOf(
        CodexProfile(name = "default", default = true),
        CodexProfile(name = "team"),
    )

    private fun picker(
        claudeProfiles: List<ClaudeProfile> = emptyList(),
        codexProfiles: List<CodexProfile> = emptyList(),
        onCreate: (SessionTypeChoice) -> Unit = {},
    ) {
        compose.setContent {
            PocketShellTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    SessionTypePickerContent(
                        folderPath = "/srv/app",
                        folderLabel = "app",
                        onCancel = {},
                        onCreate = onCreate,
                        engines = pickerTestEngines,
                        claudeProfiles = claudeProfiles,
                        codexProfiles = codexProfiles,
                    )
                }
            }
        }
    }

    @Test
    fun claudeProfileToggleListsDiscoveredProfilesAndRoutesSelection() {
        var choice: SessionTypeChoice? = null
        picker(claudeProfiles = claudeProfiles, codexProfiles = codexProfiles) { choice = it }

        // Agent + Claude are the defaults, so with >1 Claude profile the
        // "Profile" toggle is shown and lists all discovered profiles by name.
        compose.onNodeWithTag(SESSION_TYPE_PICKER_PROFILE_TAG).assertIsDisplayed()
        compose.onNodeWithText("default").assertIsDisplayed()
        compose.onNodeWithText("work").assertIsDisplayed()
        compose.onNodeWithText("oss").assertIsDisplayed()

        // Select the non-default "work" profile, then Create.
        compose.onNodeWithTag("$SESSION_TYPE_PICKER_PROFILE_TAG:work").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_CREATE_TAG).performClick()
        compose.waitForIdle()

        assertTrue("create should route a choice", choice != null)
        assertEquals("claude", choice?.engineId)
        assertEquals("work", choice?.profileName)

        // The chosen profile threads into the launched command as --profile.
        val command = choice?.startCommand(
            claudeProfiles = claudeProfiles,
            codexProfiles = codexProfiles,
        )
        assertTrue(
            "non-default Claude profile must thread --profile 'work': $command",
            command?.contains("--profile 'work'") == true,
        )
    }

    @Test
    fun defaultClaudeProfileEmitsNoProfileFlag() {
        var choice: SessionTypeChoice? = null
        picker(claudeProfiles = claudeProfiles, codexProfiles = codexProfiles) { choice = it }

        // Pick the default profile explicitly, Create.
        compose.onNodeWithTag("$SESSION_TYPE_PICKER_PROFILE_TAG:default").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_CREATE_TAG).performClick()
        compose.waitForIdle()

        val command = choice?.startCommand(
            claudeProfiles = claudeProfiles,
            codexProfiles = codexProfiles,
        )
        assertEquals("default", choice?.profileName)
        // The default profile means "use the engine's built-in config dir" — no flag.
        assertTrue(
            "the default profile must emit no --profile flag: $command",
            command?.contains("--profile") == false,
        )
    }

    @Test
    fun codexProfileToggleListsDiscoveredProfilesAndRoutesSelection() {
        var choice: SessionTypeChoice? = null
        picker(claudeProfiles = claudeProfiles, codexProfiles = codexProfiles) { choice = it }

        // Switch to Codex; its >1-profile toggle then appears.
        compose.onNodeWithTag(sessionTypePickerAgentEngineTag("codex")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_PROFILE_TAG).assertIsDisplayed()
        compose.onNodeWithText("team").assertIsDisplayed()

        compose.onNodeWithTag("$SESSION_TYPE_PICKER_PROFILE_TAG:team").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_CREATE_TAG).performClick()
        compose.waitForIdle()

        assertEquals("codex", choice?.engineId)
        assertEquals("team", choice?.profileName)
        val command = choice?.startCommand(
            claudeProfiles = claudeProfiles,
            codexProfiles = codexProfiles,
        )
        assertTrue(
            "codex command must carry the codex kind: $command",
            command?.startsWith("pocketshell agent codex ") == true,
        )
        assertTrue(
            "non-default Codex profile must thread --profile 'team': $command",
            command?.contains("--profile 'team'") == true,
        )
    }

    @Test
    fun singleProfileEngineHidesProfileToggle() {
        // Exactly one Claude profile (just the default) -> no profile picker.
        picker(
            claudeProfiles = listOf(ClaudeProfile(name = "default", default = true)),
            codexProfiles = codexProfiles,
        )

        compose.onNodeWithTag(SESSION_TYPE_PICKER_PROFILE_TAG).assertDoesNotExist()
    }

    /**
     * Issue #1875 production-path regression. A bind-time profile probe can fail
     * transiently while the host tree itself goes on to become usable. Opening
     * the REAL host-screen New session sheet must retry discovery, render the
     * recovered Z.AI choice, and thread that choice into the command actually
     * handed to [FolderListGateway.createSession].
     *
     * This deliberately uses [FolderListScreen], not the standalone picker
     * content above: the old tests could stay green while the production screen
     * supplied an empty list forever.
     */
    @Test
    fun productionHostSheetRetriesTransientDiscoveryAndLaunchesSelectedProfile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val viewModelStore = ViewModelStore()
        val hostId = 1875L
        runBlocking {
            val keyId = db.sshKeyDao().insert(
                SshKeyEntity(
                    name = "issue1875-key",
                    privateKeyPath = "/tmp/issue1875-key",
                ),
            )
            db.hostDao().insert(
                HostEntity(
                    id = hostId,
                    name = "issue1875-host",
                    hostname = "profiles.example",
                    port = 22,
                    username = "alexey",
                    keyId = keyId,
                ),
            )
        }

        val profilesGateway = TransientThenZaiProfilesGateway()
        val enginesGateway = object : EnginesGateway {
            override suspend fun listEngines(
                host: HostEntity,
                keyPath: String,
                passphrase: CharArray?,
            ): EnginesResult = EnginesResult.Engines(pickerTestEngines)
        }
        val sessionGateway = RecordingSessionGateway()
        lateinit var viewModel: FolderListViewModel
        instrumentation.runOnMainSync {
            viewModel = FolderListViewModel(
                gateway = sessionGateway,
                hostDao = db.hostDao(),
                projectRootDao = db.projectRootDao(),
                sshLeaseManager = SshLeaseManager(
                    connector = SshLeaseConnector {
                        Result.failure(IllegalStateException("warm connect not used by fixture"))
                    },
                    idleTtlMillis = 0L,
                ),
                forwardingController = ForwardingController(context),
                profilesGateway = profilesGateway,
                enginesGateway = enginesGateway,
                attachLifecycle = false,
            ).also {
                it.setProcessStartedForTest(true)
                viewModelStore.put("issue1875", it)
            }
        }

        try {
            compose.setContent {
                PocketShellTheme {
                    FolderListScreen(
                        hostId = hostId,
                        hostName = "issue1875-host",
                        hostname = "profiles.example",
                        port = 22,
                        username = "alexey",
                        keyPath = "/tmp/issue1875-key",
                        passphrase = null,
                        onBack = {},
                        onOpenSession = { _, _, _, _ -> },
                        onSessionCreated = { _, _ -> },
                        onBrowseRepos = { _ -> },
                        onEditEnv = { _, _, _ -> },
                        modifier = Modifier.fillMaxSize(),
                        viewModel = viewModel,
                    )
                }
            }

            compose.waitUntil(timeoutMillis = 10_000) {
                profilesGateway.calls.get() >= 1 &&
                    compose.onAllNodesWithTag(FOLDER_LIST_NEW_SESSION_FAB_TAG)
                        .fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(
                "the bind-time fixture must reproduce one transient profile failure",
                viewModel.claudeProfiles.value.isEmpty(),
            )

            compose.onNodeWithTag(FOLDER_LIST_NEW_SESSION_FAB_TAG)
                .performScrollTo()
                .performClick()

            // Load-bearing UI assertion: this can only become true when the
            // production host-screen open path retries the transient failure.
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag(
                    "$SESSION_TYPE_PICKER_PROFILE_TAG:Claude (Z.AI)",
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(
                "$SESSION_TYPE_PICKER_PROFILE_TAG:Claude (Z.AI)",
                useUnmergedTree = true,
            ).performScrollTo().assertIsDisplayed().performClick()
            compose.waitForIdle()
            captureFullDevice("issue1875-profile-picker-green.png")

            compose.onNodeWithTag(SESSION_TYPE_PICKER_CREATE_TAG).performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                sessionGateway.lastStartCommand.get() != null
            }
            assertTrue(
                "the command actually launched must carry the recovered Z.AI profile: " +
                    sessionGateway.lastStartCommand.get(),
                sessionGateway.lastStartCommand.get()
                    ?.contains("--profile 'Claude (Z.AI)'") == true,
            )
            assertTrue(
                "opening the sheet must retry after the bind-time failure",
                profilesGateway.calls.get() >= 2,
            )
        } finally {
            instrumentation.runOnMainSync { viewModel.stopPolling() }
            viewModelStore.clear()
            db.close()
        }
    }

    private fun captureFullDevice(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val root = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val directory = File(root, "additional_test_output/issue1875-profile-picker")
            .apply { mkdirs() }
        FileOutputStream(File(directory, name)).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

private class TransientThenZaiProfilesGateway : ProfilesGateway {
    val calls = AtomicInteger(0)

    override suspend fun listProfiles(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        engine: String?,
    ): ProfilesResult =
        if (calls.incrementAndGet() == 1) {
            ProfilesResult.ConnectFailed(IllegalStateException("transient bind-time failure"))
        } else {
            ProfilesResult.Profiles(
                listOf(
                    RemoteProfile(
                        name = "Claude",
                        engine = RemoteProfile.ENGINE_CLAUDE,
                        default = true,
                    ),
                    RemoteProfile(
                        name = "Claude (Z.AI)",
                        engine = RemoteProfile.ENGINE_CLAUDE,
                        configDir = "/home/alexey/.zlaude",
                    ),
                ),
            )
        }
}

private class RecordingSessionGateway : FolderListGateway {
    val lastStartCommand = AtomicReference<String?>(null)

    override suspend fun listSessionsWithFolder(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        watchedRoots: List<ProjectRootEntity>,
    ): FolderListResult = FolderListResult.Sessions(rows = emptyList())

    override suspend fun createSession(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        sessionName: String,
        cwd: String,
        startCommand: String?,
        namePolicy: SessionNamePolicy,
    ): Result<SessionCreateOutcome> {
        lastStartCommand.set(startCommand)
        return Result.success(SessionCreateOutcome.Created(sessionName))
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
