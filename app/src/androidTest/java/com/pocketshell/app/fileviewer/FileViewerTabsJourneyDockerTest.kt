package com.pocketshell.app.fileviewer

import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.WalkthroughScreenshotArtifacts
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #1715 — reproduce-first connected journey for persistent open-file tabs.
 *
 * This journey targets the real `agents-daemon` fixture on port 2239. It also
 * inspects the daemon's `registry.json`, so a separate workspace fixture file
 * cannot make the persistence proof pass.
 *
 * RED on current main: there is no host workspace, no strip, and no Open-files
 * restore. After opening A/B/C and recreating the viewer, the three tabs and
 * the previous active file must come back, and selecting a restored tab must
 * switch the viewer without rebinding the screen.
 *
 * G6 mutation: if bind(null) opened an empty workspace instead of hydrating
 * the host list, [openABCLeaveAndOpenFilesRestoresThreeTabsAndActive] fails.
 */
@RunWith(AndroidJUnit4::class)
class FileViewerTabsJourneyDockerTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private lateinit var leasing: CountingLeaseManager
    private val seededPaths = mutableListOf<String>()

    @Before
    fun setUp() {
        runBlocking {
            val keyText = InstrumentationRegistry.getInstrumentation()
                .context.assets.open("test_key").bufferedReader().use { it.readText() }
            sshKey = SshKey.Pem(keyText)
            leasing = CountingLeaseManager()
            val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            keyFile = File(cacheDir, "issue1715-file-tabs-key").apply {
                parentFile?.mkdirs()
                if (exists()) delete()
                FileOutputStream(this).use { it.write(keyText.toByteArray()) }
                setReadable(true, true)
            }
            waitForSshFixtureReady(sshKey, port = DAEMON_PORT)
            resetDaemonWorkspace()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            if (seededPaths.isNotEmpty()) {
                withTimeout(15_000) {
                    connect()?.use { session ->
                        for (path in seededPaths) {
                            runCatching { session.exec("rm -f '$path'") }
                        }
                        runCatching { resetDaemonWorkspace(session) }
                    }
                }
            }
            runCatching { keyFile.delete() }
            runCatching { leasing.manager.close() }
        }
    }

    @Test
    fun openABCLeaveAndOpenFilesRestoresThreeTabsAndActive() {
        runBlocking {
            val suffix = System.currentTimeMillis().toString().takeLast(6)
            val a = "/tmp/issue1715-a-$suffix.txt"
            val b = "/tmp/issue1715-b-$suffix.txt"
            val c = "/tmp/issue1715-c-$suffix.txt"
            withTimeout(20_000) {
                connect()?.use { session ->
                    assertEquals(0, session.exec("printf 'A body\\n' > '$a'").exitCode)
                    assertEquals(0, session.exec("printf 'B body\\n' > '$b'").exitCode)
                    assertEquals(0, session.exec("printf 'C body\\n' > '$c'").exitCode)
                    seededPaths += listOf(a, b, c)
                } ?: error("could not connect to seed tab files")
            }

            val first = FileViewerViewModel(
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
                leasing.manager,
            )
            var remotePath by mutableStateOf<String?>(a)
            var viewer by mutableStateOf(first)
            composeRule.setContent {
                FileViewerScreen(
                    hostId = TEST_HOST_ID,
                    hostName = "agents",
                    hostname = DEFAULT_HOST,
                    port = DAEMON_PORT,
                    username = DEFAULT_USER,
                    keyPath = keyFile.absolutePath,
                    passphrase = null,
                    remotePath = remotePath,
                    cwd = null,
                    onBack = {},
                    viewModel = viewer,
                )
            }
            composeRule.waitUntil(timeoutMillis = 30_000) {
                first.workspace.value.orderedTabs.any { it.absolutePath == a }
            }
            remotePath = b
            composeRule.waitUntil(timeoutMillis = 30_000) {
                first.workspace.value.orderedTabs.any { it.absolutePath == b }
            }
            remotePath = c
            composeRule.waitUntil(timeoutMillis = 30_000) {
                first.workspace.value.orderedTabs.size == 3 &&
                    first.workspace.value.activePath == c
            }
            withTimeout(15_000) {
                connect()?.use { session ->
                    val registry = session.exec(
                        "cat \"\${XDG_STATE_HOME:-\$HOME/.local/state}/pocketshell/tree/registry.json\"",
                    )
                    assertEquals(0, registry.exitCode)
                    assertTrue(registry.stdout.contains("\"file_workspaces\""))
                    assertTrue(registry.stdout.contains("\"default\""))
                    assertTrue(registry.stdout.contains(a))
                    assertTrue(registry.stdout.contains(b))
                    assertTrue(registry.stdout.contains(c))
                } ?: error("could not inspect the real daemon registry")
            }
            WalkthroughScreenshotArtifacts.capture("issue1715-three-tabs")

            // Acceptance criterion: an actual background/return must keep the
            // durable workspace visible before a fresh ViewModel hydrates it
            // below. This catches lifecycle teardown/rebind paths that a pure
            // Compose state assertion would miss.
            composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            composeRule.waitForIdle()
            assertEquals(c, first.workspace.value.activePath)
            composeRule.onNodeWithTag(FILE_VIEWER_TAB_STRIP_TAG).assertExists()
            WalkthroughScreenshotArtifacts.capture("issue1715-background-return")

            val restored = FileViewerViewModel(
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
                leasing.manager,
            )
            viewer = restored
            remotePath = null
            composeRule.waitUntil(timeoutMillis = 30_000) {
                restored.workspace.value.orderedTabs.map { it.absolutePath }.toSet() ==
                    setOf(a, b, c) &&
                    restored.workspace.value.activePath == c &&
                    composeRule.onAllNodesWithTagExists(FILE_VIEWER_TAB_STRIP_TAG)
            }
            assertEquals(c, restored.workspace.value.activePath)
            composeRule.onNodeWithTag(FILE_VIEWER_TAB_STRIP_TAG).assertExists()

            composeRule.onNodeWithTag(FILE_VIEWER_TAB_TAG_PREFIX + b).performClick()
            composeRule.waitUntil(timeoutMillis = 30_000) {
                restored.workspace.value.activePath == b &&
                    (restored.state.value as? FileViewerUiState.TextContent)?.displayPath == b
            }
            assertEquals("clicking a restored tab must activate that file", b, restored.workspace.value.activePath)

            composeRule.onNodeWithTag(FILE_VIEWER_TAB_TAG_PREFIX + a).performClick()
            composeRule.waitUntil(timeoutMillis = 30_000) {
                restored.workspace.value.activePath == a &&
                    (restored.state.value as? FileViewerUiState.TextContent)?.displayPath == a
            }
            assertEquals("the tab strip must switch back without a new bind", a, restored.workspace.value.activePath)
            WalkthroughScreenshotArtifacts.capture("issue1715-open-files-restored")
        }
    }

    @Test
    fun lastCloseShowsEmptyWorkspace() {
        runBlocking {
            val suffix = System.currentTimeMillis().toString().takeLast(6)
            val only = "/tmp/issue1715-solo-$suffix.txt"
            withTimeout(20_000) {
                connect()?.use { session ->
                    assertEquals(0, session.exec("printf 'solo\\n' > '$only'").exitCode)
                    seededPaths += only
                } ?: error("could not connect to seed solo file")
            }
            val vm = FileViewerViewModel(
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
                leasing.manager,
            )
            composeRule.setContent {
                FileViewerScreen(
                    hostId = TEST_HOST_ID,
                    hostName = "agents",
                    hostname = DEFAULT_HOST,
                    port = DAEMON_PORT,
                    username = DEFAULT_USER,
                    keyPath = keyFile.absolutePath,
                    passphrase = null,
                    remotePath = only,
                    cwd = null,
                    onBack = {},
                    viewModel = vm,
                )
            }
            composeRule.waitUntil(timeoutMillis = 30_000) {
                vm.workspace.value.orderedTabs.any { it.absolutePath == only }
            }
            val tab = vm.workspace.value.orderedTabs.single()
            composeRule.onNodeWithTag(FILE_VIEWER_TAB_CLOSE_TAG_PREFIX + tab.absolutePath)
                .performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodesWithTagExists(FILE_VIEWER_EMPTY_WORKSPACE_TAG)
            }
            assertTrue(vm.workspace.value.orderedTabs.isEmpty())
            WalkthroughScreenshotArtifacts.capture("issue1715-empty-workspace")
        }
    }

    private suspend fun connect() = SshConnection.connect(
        host = DEFAULT_HOST,
        port = DAEMON_PORT,
        user = DEFAULT_USER,
        key = sshKey,
        knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
        timeoutMs = 10_000,
    ).getOrNull()

    private companion object {
        const val TEST_HOST_ID: Long = 1715L
        const val DAEMON_PORT: Int = 2239
    }

    private suspend fun resetDaemonWorkspace() {
        connect()?.use { session -> resetDaemonWorkspace(session) }
    }

    private suspend fun resetDaemonWorkspace(session: com.pocketshell.core.ssh.SshSession) {
        val result = session.exec(
            "printf '%s' '{\"tabs\":[],\"active_path\":null}' | pocketshell tree workspace-upsert >/dev/null",
        )
        assertEquals(0, result.exitCode)
    }
}

private fun ComposeContentTestRule.onAllNodesWithTagExists(tag: String): Boolean =
    runCatching { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
