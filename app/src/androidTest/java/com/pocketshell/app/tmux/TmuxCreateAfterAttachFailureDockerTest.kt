package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.projects.SESSION_TYPE_PICKER_CONTENT_TAG
import com.pocketshell.app.projects.SESSION_TYPE_PICKER_CREATE_TAG
import com.pocketshell.app.projects.SESSION_TYPE_PICKER_CWD_TAG
import com.pocketshell.app.projects.SESSION_TYPE_PICKER_SHELL_TAG
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/** Issue #1832 production-screen proof: failed attach -> real New-session sheet -> real Toast. */
@RunWith(AndroidJUnit4::class)
class TmuxCreateAfterAttachFailureDockerTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val permissions = PreGrantPermissionsRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var fixtureKey: String
    private val remoteNames = mutableSetOf<String>()
    private val remoteFolders = mutableSetOf<String>()

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        if (::fixtureKey.isInitialized) {
            runBlocking {
                runCatching {
                    execRemote(
                        "rm -f /tmp/pocketshell-fail-tmux-control; " +
                            remoteNames.joinToString("; ") {
                                "tmux kill-session -t ${shellQuote("=$it")} 2>/dev/null || true"
                            } + "; " +
                            remoteFolders.joinToString("; ") { "rm -rf ${shellQuote(it)}" },
                    )
                }
            }
        }
    }

    @Test
    fun failedAttachKeepsNewSessionSurfaceReachableAndShowsToastWithoutCreating() { runBlocking {
        fixtureKey = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(fixtureKey))
        val suffix = System.nanoTime().toString().takeLast(7)
        val healthySession = "issue1832-live-$suffix"
        val createFolder = "/tmp/issue1832-create-$suffix"
        val rejectedCreate = "tmp-issue1832-create-$suffix"
        remoteNames += healthySession
        remoteNames += rejectedCreate
        remoteFolders += createFolder

        execRemote(
            "rm -f /tmp/pocketshell-fail-tmux-control; " +
                "mkdir -p ${shellQuote(createFolder)}; " +
                "tmux new-session -d -s ${shellQuote(healthySession)} " +
                shellQuote("printf 'ISSUE1832-LIVE\\n'; exec sh"),
        )
        proveHealthyAttachedThenDetached(healthySession)
        val healthyHostRow = seedDockerHost()
        clearLogcat()

        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForTag(healthyHostRow, 20_000)
        compose.onNodeWithTag(healthyHostRow, useUnmergedTree = true).performClick()
        waitForText(healthySession, 30_000)
        execRemote("touch /tmp/pocketshell-fail-tmux-control")
        compose.onNodeWithText(healthySession, useUnmergedTree = true).performClick()
        val failedAttachAt = SystemClock.elapsedRealtime()
        waitForTag(TMUX_SESSION_ERROR_TAG, 30_000)
        val attachFailureMs = SystemClock.elapsedRealtime() - failedAttachAt
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        assertEquals(0, attachedClientCount(healthySession))

        // The exact user path: kebab -> + New session -> rich sheet -> Shell -> Create.
        compose.onNodeWithTag(TMUX_FULL_CHROME_MORE_BUTTON_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()
        waitForText("+ New session", 10_000)
        compose.onNodeWithText("+ New session", useUnmergedTree = true).performClick()
        waitForTag(SESSION_TYPE_PICKER_CONTENT_TAG, 10_000)
        compose.onNodeWithTag(SESSION_TYPE_PICKER_SHELL_TAG, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_CWD_TAG, useUnmergedTree = true)
            .performTextClearance()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_CWD_TAG, useUnmergedTree = true)
            .performTextInput(createFolder)
        compose.waitForIdle()

        val expectedToast = "Session isn't attached yet. Reconnect, then try again."
        val createAt = SystemClock.elapsedRealtime()
        val toastEvent = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeAndWaitForEvent(
                {
                    compose.onNodeWithTag(SESSION_TYPE_PICKER_CREATE_TAG, useUnmergedTree = true)
                        .assertIsDisplayed()
                        .performClick()
                },
                { event ->
                    event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                        event.text.any { expectedToast in it.toString() }
                },
                8_000,
            )
        val toastText = toastEvent.text.joinToString(" ")
        val toastMs = SystemClock.elapsedRealtime() - createAt
        captureFullDevice("failed-attach-create-toast")

        assertTrue("actual Toast accessibility event must contain the actionable error", expectedToast in toastText)
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true).assertExists()
        assertSessionAbsent(rejectedCreate)
        val logcat = captureLogcat()
        assertTrue(
            "logcat must identify no_active_target; tail=$logcat",
            logcat.contains("create-session-failed reason=no_active_target"),
        )

        artifactFile("issue1832-production-screen.txt").writeText(
            buildString {
                appendLine("journey=real healthy control attach/detach -> MainActivity failed attach -> kebab -> + New session -> rich sheet -> Create")
                appendLine("healthy_session_attached=true")
                appendLine("failed_target=$healthySession")
                appendLine("failed_target_attached_clients=0")
                appendLine("failed_attach_band_visible=true")
                appendLine("failed_attach_ms=$attachFailureMs")
                appendLine("session_screen_reachable_after_failure=true")
                appendLine("requested_create=$rejectedCreate")
                appendLine("new_host_session_exists=false")
                appendLine("toast_text=$toastText")
                appendLine("toast_observed_ms=$toastMs")
                appendLine("log_reason=no_active_target")
            },
        )
    } }

    private fun waitForTag(tag: String, timeoutMs: Long) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            runCatching {
                compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
    }

    private fun waitForText(text: String, timeoutMs: Long) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            runCatching {
                compose.onAllNodesWithText(text, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
    }

    private suspend fun assertAttached(sessionName: String) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        var attached = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            attached = attachedClientCount(sessionName)
            if (attached > 0) break
            SystemClock.sleep(250)
        }
        assertTrue("#1820 discrimination: $sessionName must have a real attached client", attached > 0)
    }

    private suspend fun proveHealthyAttachedThenDetached(sessionName: String) {
        val session = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(fixtureKey),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).getOrThrow()
        try {
            session.startShell().use { shell ->
                shell.writeStdin(
                    "tmux -CC attach-session -t ${shellQuote("=$sessionName")}\n".toByteArray(),
                )
                assertAttached(sessionName)
            }
        } finally {
            session.close()
        }
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var attached = attachedClientCount(sessionName)
        while (attached != 0 && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(250)
            attached = attachedClientCount(sessionName)
        }
        assertEquals("healthy proof client must detach before failed production attach", 0, attached)
    }

    private suspend fun attachedClientCount(sessionName: String): Int =
        execRemote("tmux list-sessions -F '#{session_name} #{session_attached}' 2>/dev/null || true")
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.substringBefore(' ') == sessionName }
            ?.substringAfter(' ')
            ?.trim()
            ?.toIntOrNull()
            ?: 0

    private suspend fun assertSessionAbsent(sessionName: String) {
        val names = execRemote("tmux list-sessions -F '#{session_name}' 2>/dev/null || true")
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        assertTrue("Create after failed attach must create nothing; sessions=$names", sessionName !in names)
    }

    private suspend fun seedDockerHost(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = context,
                sshKeyDao = db.sshKeyDao(),
                name = "issue1832-key-${System.currentTimeMillis()}",
                content = fixtureKey,
            )
            val healthyHostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue1832 Healthy Host",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            HOST_ROW_TAG_PREFIX + healthyHostId
        } finally {
            db.close()
        }
    }

    private fun readFixtureKey(): String = InstrumentationRegistry.getInstrumentation()
        .context.assets.open("test_key").bufferedReader().use { it.readText() }

    private suspend fun execRemote(command: String): String = withTimeout(30_000) {
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(fixtureKey),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).getOrThrow().use { session ->
            val result = session.exec(command)
            assertEquals("remote command failed: $command stderr=${result.stderr}", 0, result.exitCode)
            result.stdout
        }
    }

    private fun clearLogcat() {
        shellOutput("logcat -c")
    }

    private fun captureLogcat(): String =
        shellOutput("logcat -d -v threadtime -t 3000 issue464-killsession:W PsTmuxReconnect:I *:S")

    private fun shellOutput(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun captureFullDevice(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = artifactFile("$name-viewport.png")
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun artifactFile(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = com.pocketshell.app.test.testArtifactsRoot(context)
        val dir = File(root, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs())
        return File(dir, name)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DATABASE_NAME = "pocketshell.db"
        const val DEVICE_DIR_NAME = "issue-1832-create-after-failure"
    }
}
