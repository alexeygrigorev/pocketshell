package com.pocketshell.app.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.core.connection.ConnectionJournalSchema
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue1710ConnectionJournalHostPullTest {
    private lateinit var context: Context
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "diagnostics").deleteRecursively()
        File(context.cacheDir, DIAGNOSTICS_EXPORT_CACHE_DIR).deleteRecursively()
        settings = SettingsRepository(context)
    }

    @Test
    fun fullArchiveRendersInOrderWithoutMirrorFilteringOr64KiBBudget() = runTest {
        val recorder = DiagnosticRecorder(context, settings)
        val marker = "issue1710-${"z".repeat(450)}"
        repeat(180) { index ->
            recorder.record(
                ConnectionJournalSchema.CATEGORY,
                ConnectionJournalSchema.SUBMIT,
                mapOf("journalSeq" to index, "markerValue" to "$marker-$index"),
            )
        }

        val archive = recorder.connectionJournalArchive()
        val jsonl = recorder.connectionJournalJsonl()
        val decoded = jsonl.lineSequence()
            .filter(String::isNotBlank)
            .mapNotNull(DiagnosticEventJson::decode)
            .toList()

        assertEquals("every archived row must be rendered in order", archive, decoded)
        assertTrue("one-shot archive must not use the 64 KiB mirror budget", jsonl.toByteArray().size > 64 * 1024)
        assertTrue(decoded.all { it.category == ConnectionJournalSchema.CATEGORY })
        assertTrue("automatic mirror policy remains separate", recorder.connectionLogJsonl().isBlank())
    }

    @Test
    fun emptyArchiveRendersBlankAndReturnsEmptyBeforeInspectingSsh() = runTest {
        val recorder = DiagnosticRecorder(context, settings)
        assertTrue(recorder.connectionJournalJsonl().isBlank())

        val state = ConnectionJournalHostPull.pull(
            recorder = recorder,
            expectedLeaseKey = null,
            heldLease = null,
            heldSession = null,
        )

        assertEquals(ConnectionJournalHostPullState.Empty, state)
        assertEquals("No connection journal recorded yet.", state.feedbackText())
    }

    @Test
    fun matchingHeldLiveLeaseWritesWithZeroAcquireOrRetarget() = runTest {
        val recorder = seededRecorder()
        val session = RecordingSession()
        val connector = CountingConnector(session)
        val manager = SshLeaseManager(connector = connector, scope = this)
        val lease = manager.acquire(TARGET).getOrThrow()
        assertEquals(1, connector.calls)

        val expected = recorder.connectionJournalJsonl()
        val state = ConnectionJournalHostPull.pull(
            recorder = recorder,
            expectedLeaseKey = TARGET.leaseKey,
            heldLease = lease,
            heldSession = session,
        )

        assertEquals(
            ConnectionJournalHostPullState.Succeeded(ConnectionLogHostMirror.JOURNAL_REMOTE_PATH),
            state,
        )
        assertEquals("pull must never call acquire", 1, connector.calls)
        assertEquals(expected, session.uploadedText)
        assertFalse("held warm session must stay open", session.closed)
    }

    @Test
    fun missingMismatchedDisconnectedAndClosingSnapshotsDoNoSshWork() = runTest {
        val recorder = seededRecorder()
        val live = RecordingSession()
        val liveLease = acquiredLease(live)

        val cases = listOf(
            Triple<SshLeaseKey?, SshLease?, SshSession?>(null, liveLease, live),
            Triple(TARGET.leaseKey, null, live),
            Triple(TARGET.leaseKey.copy(port = TARGET.leaseKey.port + 1), liveLease, live),
            Triple(TARGET.leaseKey, liveLease, RecordingSession(connected = false)),
        )
        cases.forEach { (key, lease, session) ->
            val before = live.sshWork
            val state = ConnectionJournalHostPull.pull(recorder, key, lease, session)
            assertEquals(ConnectionJournalHostPullState.NoWarmSession, state)
            assertEquals("rejected snapshot must do zero SSH work", before, live.sshWork)
        }

        val closing = RecordingSession(closeInitiated = true)
        val closingLease = acquiredLease(closing)
        val closingState = ConnectionJournalHostPull.pull(
            recorder,
            TARGET.leaseKey,
            closingLease,
            closing,
        )
        assertEquals(ConnectionJournalHostPullState.NoWarmSession, closingState)
        assertEquals(0, closing.sshWork)
        assertEquals("Open a connected session, then try again.", closingState.feedbackText())
    }

    @Test
    fun uploadFailureAndTimeoutMapToFailedWithoutClosingTheSession() = runTest {
        val recorder = seededRecorder()
        val failing = RecordingSession(failUpload = true)
        val failingState = ConnectionJournalHostPull.pull(
            recorder,
            TARGET.leaseKey,
            acquiredLease(failing),
            failing,
        )
        assertEquals(ConnectionJournalHostPullState.Failed, failingState)
        assertFalse(failing.closed)

        val wedged = RecordingSession(wedgeUpload = true)
        val timeoutState = ConnectionJournalHostPull.pull(
            recorder,
            TARGET.leaseKey,
            acquiredLease(wedged),
            wedged,
            timeoutMs = 100L,
        )
        assertEquals(ConnectionJournalHostPullState.Failed, timeoutState)
        assertFalse(wedged.closed)
        assertEquals("Could not mirror connection journal to host.", timeoutState.feedbackText())
    }

    @Test
    fun everyTerminalStateHasExactVisibleCopyAndCanBeConsumedToIdle() {
        assertNull(ConnectionJournalHostPullState.Idle.feedbackText())
        assertNull(ConnectionJournalHostPullState.Mirroring.feedbackText())
        assertEquals(
            "Connection journal mirrored to `~/.pocketshell/connection-journal.jsonl`",
            ConnectionJournalHostPullState.Succeeded(
                ConnectionLogHostMirror.JOURNAL_REMOTE_PATH,
            ).feedbackText(),
        )
        assertEquals("No connection journal recorded yet.", ConnectionJournalHostPullState.Empty.feedbackText())
        assertEquals(
            "Open a connected session, then try again.",
            ConnectionJournalHostPullState.NoWarmSession.feedbackText(),
        )
        assertEquals(
            "Could not mirror connection journal to host.",
            ConnectionJournalHostPullState.Failed.feedbackText(),
        )
        listOf(
            ConnectionJournalHostPullState.Succeeded(ConnectionLogHostMirror.JOURNAL_REMOTE_PATH),
            ConnectionJournalHostPullState.Empty,
            ConnectionJournalHostPullState.NoWarmSession,
            ConnectionJournalHostPullState.Failed,
        ).forEach { terminal ->
            assertEquals(ConnectionJournalHostPullState.Idle, terminal.consume())
        }
    }

    private suspend fun seededRecorder(): DiagnosticRecorder =
        DiagnosticRecorder(context, settings).also { recorder ->
            recorder.record(
                ConnectionJournalSchema.CATEGORY,
                ConnectionJournalSchema.CONSTRUCT,
                mapOf("journalSeq" to 0, "markerValue" to "issue1710"),
            )
            check(recorder.connectionJournalArchive().isNotEmpty())
        }

    private suspend fun acquiredLease(session: RecordingSession): SshLease {
        val manager = SshLeaseManager(
            connector = SshLeaseConnector { Result.success(session) },
        )
        return manager.acquire(TARGET).getOrThrow()
    }

    private class CountingConnector(private val session: SshSession) : SshLeaseConnector {
        var calls: Int = 0
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            calls += 1
            return Result.success(session)
        }
    }

    private class RecordingSession(
        private val connected: Boolean = true,
        private val closeInitiated: Boolean = false,
        private val failUpload: Boolean = false,
        private val wedgeUpload: Boolean = false,
    ) : SshSession {
        var sshWork: Int = 0
        var closed: Boolean = false
        var uploadedText: String? = null

        override val isConnected: Boolean get() = connected && !closed
        override val isCloseInitiated: Boolean get() = closeInitiated || closed

        override suspend fun exec(command: String): ExecResult {
            sshWork += 1
            return ExecResult("", "", 0)
        }

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String {
            sshWork += 1
            if (wedgeUpload) awaitCancellation()
            if (failUpload) throw IOException("upload failed")
            uploadedText = input.readBytes().toString(Charsets.UTF_8)
            return remotePath
        }

        override fun tail(path: String, onLine: (String) -> Unit) = error("not used")
        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")
        override fun startShell(): SshShell = error("not used")
        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")
        override fun close() {
            closed = true
        }
    }

    private companion object {
        val TARGET = SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = "10.0.2.2",
                port = 2222,
                user = "testuser",
                credentialId = "1710:/tmp/key",
            ),
            key = SshKey.Path(File("/tmp/key")),
            knownHosts = KnownHostsPolicy.AcceptAll,
        )
    }
}
