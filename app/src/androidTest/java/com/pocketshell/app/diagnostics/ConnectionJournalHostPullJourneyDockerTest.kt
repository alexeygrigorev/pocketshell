package com.pocketshell.app.diagnostics

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.App
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.SeedBeforeLaunchRule
import com.pocketshell.app.proof.TerminalTestTimeouts
import com.pocketshell.app.proof.clearLastSessionPrefs
import com.pocketshell.app.proof.execRemoteSetupUntilReady
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.settings.DIAGNOSTICS_MIRROR_CONNECTION_JOURNAL_FEEDBACK_TAG
import com.pocketshell.app.settings.DIAGNOSTICS_MIRROR_CONNECTION_JOURNAL_TAG
import com.pocketshell.app.settings.SETTINGS_LAZY_COLUMN_TAG
import com.pocketshell.app.settings.HostDetailViewMode
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.app.test.testArtifactsRoot
import com.pocketshell.app.tmux.SSH_HANDSHAKE_ATTEMPTS
import com.pocketshell.app.tmux.TMUX_COMPACT_CHROME_MORE_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_FULL_CHROME_MORE_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_SETTINGS_BUTTON_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.connection.ConnectionJournalSchema
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Issue #1710 real Settings -> held warm SSH session -> fixed host-file journey.
 * It launches the production activity, opens a real Docker tmux session, and
 * drives the actual Settings row; no fake writer or lease acquisition seam.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionJournalHostPullJourneyDockerTest {
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private lateinit var fixtureKey: String
    private lateinit var hostRowTag: String
    private lateinit var markerPrefix: String

    @After
    fun tearDown() {
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        clearLastSessionPrefs()
        if (::fixtureKey.isInitialized) {
            runCatching { runBlocking { cleanupRemote() } }
        }
    }

    @Test
    fun settingsPullUsesHeldWarmSessionThenNoWarmDoesNoSshWork() {
        runBlocking {
            attachSeededSession()
            val vm = currentViewModel()
            waitForConnected(vm)

            val app = InstrumentationRegistry.getInstrumentation()
                .targetContext.applicationContext as App
            app.diagnosticRecorder.clear()
            val headMarkers = listOf(
                "$markerPrefix-construct",
                "$markerPrefix-submit-a",
                "$markerPrefix-submit-b",
            )
            val expectedMarkers = buildList {
                addAll(headMarkers)
                repeat(FILLER_ROW_COUNT) { index ->
                    add("$markerPrefix-filler-$index")
                }
            }
            assertEquals(
                "precondition: every seeded row has a unique marker",
                expectedMarkers.size,
                expectedMarkers.toSet().size,
            )
            headMarkers.forEachIndexed { index, marker ->
                app.diagnosticRecorder.record(
                    ConnectionJournalSchema.CATEGORY,
                    if (index == 0) {
                        ConnectionJournalSchema.CONSTRUCT
                    } else {
                        ConnectionJournalSchema.SUBMIT
                    },
                    mapOf(
                        "journalSeq" to index,
                        "markerValue" to marker,
                        "paddingValue" to "x".repeat(480),
                    ),
                )
            }
            // Make the upload large enough that the user-visible Mirroring state is
            // observable while still staying inside the recorder's bounded channel.
            repeat(FILLER_ROW_COUNT) { index ->
                app.diagnosticRecorder.record(
                    ConnectionJournalSchema.CATEGORY,
                    ConnectionJournalSchema.SUBMIT,
                    mapOf(
                        "journalSeq" to index + headMarkers.size,
                        "markerValue" to expectedMarkers[index + headMarkers.size],
                        "paddingValue" to "y".repeat(480),
                    ),
                )
            }
            val seededArchive = app.diagnosticRecorder.connectionJournalArchive()
            val seededMarkers = seededArchive
                .mapNotNull { event -> event.metadata["markerValue"] as? String }
                .filter(expectedMarkers.toSet()::contains)
            assertEquals(
                "precondition: the recorder retained every seeded row in exact order",
                expectedMarkers,
                seededMarkers,
            )
            val seededJsonl = app.diagnosticRecorder.connectionJournalJsonl()
            val seededBytes = seededJsonl.toByteArray().size
            assertTrue(
                "precondition: the seeded archive must exceed the automatic 64 KiB budget",
                seededBytes > MIRROR_BUDGET_BYTES,
            )
            val seededNewestMarkerOffset = byteOffsetOf(seededJsonl, expectedMarkers.last())
            assertTrue(
                "precondition: the newest unique marker must itself begin beyond 64 KiB",
                seededNewestMarkerOffset > MIRROR_BUDGET_BYTES,
            )
            clearRemoteJournal()

            val handshakesBeforeTap = SSH_HANDSHAKE_ATTEMPTS.get()
            openSettingsFromLiveSession()
            scrollToJournalRow()
            compose.onNodeWithTag(
                DIAGNOSTICS_MIRROR_CONNECTION_JOURNAL_TAG,
                useUnmergedTree = true,
            ).performClick()

            compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
                compose.onAllNodesWithText(
                    "Mirroring connection journal...",
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(timeoutMillis = UPLOAD_TIMEOUT_MS) {
                compose.onAllNodesWithText(
                    SUCCESS_COPY,
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(
                DIAGNOSTICS_MIRROR_CONNECTION_JOURNAL_FEEDBACK_TAG,
                useUnmergedTree = true,
            ).assertExists()

            assertEquals(
                "the one-shot action must not initiate another SSH handshake",
                handshakesBeforeTap,
                SSH_HANDSHAKE_ATTEMPTS.get(),
            )
            assertTrue(
                "the original held session must remain connected after the upload",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )

            val landed = readRemoteJournal()
            val landedLines = landed.lineSequence()
                .filter(String::isNotBlank)
                .toList()
            val decoded = landedLines.mapIndexed { index, line ->
                requireNotNull(DiagnosticEventJson.decode(line)) {
                    "host JSONL line ${index + 1} did not decode"
                }
            }
            val landedMarkers = decoded.mapNotNull { it.metadata["markerValue"] as? String }
            val landedSeededMarkers = landedMarkers.filter(expectedMarkers.toSet()::contains)
            assertEquals(
                "host JSONL must contain the exact complete seeded row count",
                expectedMarkers.size,
                landedSeededMarkers.size,
            )
            assertEquals(
                "every seeded marker must survive archive render + SCP in exact order",
                expectedMarkers,
                landedSeededMarkers,
            )
            assertEquals(
                "oldest unique seeded marker must survive",
                expectedMarkers.first(),
                landedSeededMarkers.first(),
            )
            assertEquals(
                "newest unique seeded marker must survive",
                expectedMarkers.last(),
                landedSeededMarkers.last(),
            )
            val hostPayloadBytes = landed.toByteArray().size
            assertTrue(
                "host payload must exceed 64 KiB",
                hostPayloadBytes > MIRROR_BUDGET_BYTES,
            )
            val hostNewestMarkerOffset = byteOffsetOf(landed, expectedMarkers.last())
            assertTrue(
                "host newest marker must begin beyond 64 KiB",
                hostNewestMarkerOffset > MIRROR_BUDGET_BYTES,
            )
            val successHash = sha256(landed)
            val artifactDir = artifactDir()
            File(artifactDir, "connection-journal.jsonl").writeText(landed)
            captureFullDevice(File(artifactDir, "settings-journal-success.png"))

            // Remove the activity-scoped VM's held target/lease/session, then drive
            // the SAME real Settings row. The non-empty archive remains, so this
            // discriminates NoWarmSession from Empty.
            vm.closeCurrentConnectionAndJoinForTest()
            val handshakesBeforeNoWarmTap = SSH_HANDSHAKE_ATTEMPTS.get()
            compose.onNodeWithTag(
                DIAGNOSTICS_MIRROR_CONNECTION_JOURNAL_TAG,
                useUnmergedTree = true,
            ).performClick()
            compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
                compose.onAllNodesWithText(NO_WARM_COPY, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(
                "no-warm action must not acquire/cold-dial",
                handshakesBeforeNoWarmTap,
                SSH_HANDSHAKE_ATTEMPTS.get(),
            )
            val unchanged = readRemoteJournal()
            assertEquals(
                "no-warm action must not change the host file",
                successHash,
                sha256(unchanged),
            )
            captureFullDevice(File(artifactDir, "settings-journal-no-warm.png"))
            captureLogcat(File(artifactDir, "emulator-logcat.txt"))
            File(artifactDir, "summary.txt").writeText(
                buildString {
                    appendLine("issue=1710")
                    appendLine("fixture=$DEFAULT_HOST:$DEFAULT_PORT")
                    appendLine("session=$SESSION_NAME")
                    appendLine("seeded_archive_rows=${seededArchive.size}")
                    appendLine("seeded_marker_count=${expectedMarkers.size}")
                    appendLine("seeded_jsonl_bytes=$seededBytes")
                    appendLine("seeded_newest_marker_byte_offset=$seededNewestMarkerOffset")
                    appendLine("landed_rows=${decoded.size}")
                    appendLine("landed_seeded_marker_count=${landedSeededMarkers.size}")
                    appendLine("oldest_seeded_marker=${expectedMarkers.first()}")
                    appendLine("newest_seeded_marker=${expectedMarkers.last()}")
                    appendLine("host_file_bytes=$hostPayloadBytes")
                    appendLine("host_newest_marker_byte_offset=$hostNewestMarkerOffset")
                    appendLine("all_landed_lines_decoded=true")
                    appendLine("exact_seeded_marker_order=true")
                    appendLine("host_file_sha256=$successHash")
                    appendLine("handshakes_before_tap=$handshakesBeforeTap")
                    appendLine("handshakes_after_success=${SSH_HANDSHAKE_ATTEMPTS.get()}")
                    appendLine("handshakes_before_no_warm_tap=$handshakesBeforeNoWarmTap")
                    appendLine("original_session_connected_after_success=true")
                    appendLine("no_warm_file_unchanged=true")
                },
            )
            println("ISSUE1710_ARTIFACT_DIR ${artifactDir.absolutePath}")
        }
    }

    private suspend fun seedBeforeLaunch() {
        clearLastSessionPrefs()
        fixtureKey = readFixtureKey()
        markerPrefix = "issue1710-${System.currentTimeMillis().toString(36)}"
        waitForSshFixtureReady(SshKey.Pem(fixtureKey))
        seedRemoteSession()
        hostRowTag = seedDockerHost()
        SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext)
            .setHostDetailViewMode(HostDetailViewMode.Flat)
    }

    private fun attachSeededSession() {
        compose.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = SESSION_TIMEOUT_MS) {
            compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        compose.waitUntil(timeoutMillis = SESSION_TIMEOUT_MS) {
            var attached = false
            compose.activityRule.scenario.onActivity { activity ->
                val terminal = activity.window.decorView.findTerminalView()
                attached = terminal?.currentSession != null && terminal.mEmulator != null
            }
            attached
        }
    }

    private fun waitForConnected(vm: TmuxSessionViewModel) {
        compose.waitUntil(timeoutMillis = SESSION_TIMEOUT_MS) {
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected
        }
    }

    private fun openSettingsFromLiveSession() {
        val moreTags = listOf(
            TMUX_COMPACT_CHROME_MORE_BUTTON_TAG,
            TMUX_FULL_CHROME_MORE_BUTTON_TAG,
        )
        val moreTag = moreTags.firstOrNull { tag ->
            compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        } ?: error("no live-session More button")
        compose.onNodeWithTag(moreTag, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SETTINGS_BUTTON_TAG, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG).assertExists()
    }

    private fun scrollToJournalRow() {
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG)
            .performScrollToNode(hasTestTag(DIAGNOSTICS_MIRROR_CONNECTION_JOURNAL_TAG))
        compose.onNodeWithTag(
            DIAGNOSTICS_MIRROR_CONNECTION_JOURNAL_TAG,
            useUnmergedTree = true,
        ).assertExists()
    }

    private fun currentViewModel(): TmuxSessionViewModel {
        var vm: TmuxSessionViewModel? = null
        compose.activityRule.scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
        }
        return requireNotNull(vm)
    }

    private suspend fun seedRemoteSession() {
        val result = execRemoteSetupUntilReady(
            key = SshKey.Pem(fixtureKey),
            command = buildString {
                appendLine("set -eu")
                appendLine("tmux kill-session -t ${quote(SESSION_NAME)} 2>/dev/null || true")
                appendLine(
                    "tmux new-session -d -s ${quote(SESSION_NAME)} " +
                        quote("printf '$READY_MARKER\\n'; exec sh -i"),
                )
                appendLine("sleep 1")
                appendLine("tmux has-session -t ${quote(SESSION_NAME)}")
            },
            description = "issue1710 journal-pull tmux seed",
        )
        assertEquals("remote tmux seed failed: ${result.stderr}", 0, result.exitCode)
    }

    private suspend fun seedDockerHost(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val key = SshKeyStorage.persistKey(
                context = context,
                sshKeyDao = db.sshKeyDao(),
                name = "issue1710-key-${System.currentTimeMillis()}",
                content = fixtureKey,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue 1710 Journal Pull",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = key.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            "$HOST_ROW_TAG_PREFIX$hostId"
        } finally {
            db.close()
        }
    }

    private suspend fun clearRemoteJournal() {
        remoteExec("rm -f \"\$HOME/${ConnectionLogHostMirror.JOURNAL_REMOTE_PATH}\"")
    }

    private suspend fun readRemoteJournal(): String {
        val result = remoteExec("cat \"\$HOME/${ConnectionLogHostMirror.JOURNAL_REMOTE_PATH}\"")
        assertEquals("host journal read failed: ${result.stderr}", 0, result.exitCode)
        return result.stdout
    }

    private suspend fun remoteExec(command: String): com.pocketshell.core.ssh.ExecResult =
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(fixtureKey),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow().use { it.exec(command) }

    private suspend fun cleanupRemote() {
        runCatching {
            remoteExec("tmux kill-session -t ${quote(SESSION_NAME)} 2>/dev/null || true")
        }
    }

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(
            testArtifactsRoot(instrumentation.targetContext),
            "additional_test_output/issue1710-connection-journal",
        )
        check(dir.exists() || dir.mkdirs()) { "could not create ${dir.absolutePath}" }
        return dir
    }

    private fun captureFullDevice(file: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot()
            ?: error("full-device screenshot unavailable")
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            println("ISSUE1710_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun captureLogcat(file: File) {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand("logcat -d -v threadtime -t 2000")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            file.outputStream().use(input::copyTo)
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun byteOffsetOf(value: String, marker: String): Int {
        val characterIndex = value.indexOf(marker)
        check(characterIndex >= 0) { "marker not found: $marker" }
        return value.substring(0, characterIndex).toByteArray().size
    }

    private fun View.findTerminalView(): TerminalView? {
        if (this is TerminalView) return this
        if (this !is ViewGroup) return null
        repeat(childCount) { index ->
            getChildAt(index).findTerminalView()?.let { return it }
        }
        return null
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DATABASE_NAME = "pocketshell.db"
        const val SESSION_NAME = "issue1710-journal-pull"
        const val READY_MARKER = "ISSUE1710-READY"
        const val FILLER_ROW_COUNT = 180
        const val MIRROR_BUDGET_BYTES = 64 * 1024
        const val SUCCESS_COPY =
            "Connection journal mirrored to `~/.pocketshell/connection-journal.jsonl`"
        const val NO_WARM_COPY = "Open a connected session, then try again."
        val UI_TIMEOUT_MS = if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
        val SESSION_TIMEOUT_MS = if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 30_000L
        val UPLOAD_TIMEOUT_MS = if (TerminalTestTimeouts.isRunningOnCi()) 45_000L else 25_000L
    }
}
