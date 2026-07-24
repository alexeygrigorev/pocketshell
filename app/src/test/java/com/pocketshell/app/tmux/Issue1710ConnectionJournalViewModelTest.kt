package com.pocketshell.app.tmux

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.diagnostics.ConnectionJournalHostPullState
import com.pocketshell.app.diagnostics.DIAGNOSTICS_EXPORT_CACHE_DIR
import com.pocketshell.app.diagnostics.DiagnosticRecorder
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.core.connection.ConnectionJournalSchema
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class Issue1710ConnectionJournalViewModelTest : TmuxSessionViewModelTestBase() {
    @Test
    fun matchingTapTimeHeldLeaseSuppressesDuplicateTapAndConsumesSuccess() = runTest(scheduler) {
        val context = freshContext()
        val recorder = seededRecorder(context)
        val session = BlockingUploadSession()
        var acquireCalls = 0
        val manager = SshLeaseManager(
            connector = SshLeaseConnector {
                acquireCalls += 1
                Result.success(session)
            },
            scope = backgroundScope,
            connectTimeoutContext = StandardTestDispatcher(scheduler),
            abortTimeoutContext = StandardTestDispatcher(scheduler),
            nowMillis = { scheduler.currentTime },
        )
        val lease = manager.acquire(TARGET).getOrThrow()
        val vm = newVm(sshLeaseManager = manager, diagnosticRecorder = recorder)
        vm.replaceClientForTest(
            hostId = HOST_ID,
            hostName = "Issue 1710",
            host = TARGET.leaseKey.host,
            port = TARGET.leaseKey.port,
            user = TARGET.leaseKey.user,
            keyPath = KEY_PATH,
            sessionName = "journal",
            client = FakeTmuxClient(),
            lease = lease,
        )

        vm.mirrorFullConnectionJournalToHost()
        awaitCondition { session.uploadCalls == 1 }
        assertEquals(ConnectionJournalHostPullState.Mirroring, vm.connectionJournalHostPullState.value)
        assertEquals(1, session.uploadCalls)

        vm.mirrorFullConnectionJournalToHost()
        runCurrent()
        assertEquals("second tap while mirroring must be ignored", 1, session.uploadCalls)
        assertEquals("action must not call acquire", 1, acquireCalls)

        session.finishUpload.complete(Unit)
        awaitCondition {
            vm.connectionJournalHostPullState.value ==
                ConnectionJournalHostPullState.Succeeded(".pocketshell/connection-journal.jsonl")
        }
        assertEquals(
            ConnectionJournalHostPullState.Succeeded(".pocketshell/connection-journal.jsonl"),
            vm.connectionJournalHostPullState.value,
        )
        assertFalse("success must not close the live session", session.closed)

        vm.consumeConnectionJournalHostPullState()
        assertEquals(ConnectionJournalHostPullState.Idle, vm.connectionJournalHostPullState.value)
    }

    @Test
    fun mismatchedHeldLeaseMapsToNoWarmWithoutSshWork() = runTest(scheduler) {
        val context = freshContext()
        val recorder = seededRecorder(context)
        val session = BlockingUploadSession()
        val manager = SshLeaseManager(
            connector = SshLeaseConnector { Result.success(session) },
            scope = backgroundScope,
            connectTimeoutContext = StandardTestDispatcher(scheduler),
            abortTimeoutContext = StandardTestDispatcher(scheduler),
            nowMillis = { scheduler.currentTime },
        )
        val lease = manager.acquire(TARGET).getOrThrow()
        val vm = newVm(sshLeaseManager = manager, diagnosticRecorder = recorder)
        vm.replaceClientForTest(
            hostId = HOST_ID,
            hostName = "Issue 1710",
            host = TARGET.leaseKey.host,
            port = TARGET.leaseKey.port + 1,
            user = TARGET.leaseKey.user,
            keyPath = KEY_PATH,
            sessionName = "journal",
            client = FakeTmuxClient(),
            lease = lease,
        )

        vm.mirrorFullConnectionJournalToHost()
        awaitCondition {
            vm.connectionJournalHostPullState.value != ConnectionJournalHostPullState.Mirroring
        }

        assertEquals(
            ConnectionJournalHostPullState.NoWarmSession,
            vm.connectionJournalHostPullState.value,
        )
        assertEquals("mismatched tap-time lease must do zero SSH work", 0, session.execCalls)
        assertEquals(0, session.uploadCalls)
    }

    private fun freshContext(): Context =
        ApplicationProvider.getApplicationContext<Context>().also { context ->
            context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit().clear().commit()
            File(context.filesDir, "diagnostics").deleteRecursively()
            File(context.cacheDir, DIAGNOSTICS_EXPORT_CACHE_DIR).deleteRecursively()
        }

    private suspend fun seededRecorder(context: Context): DiagnosticRecorder =
        DiagnosticRecorder(context, SettingsRepository(context)).also { recorder ->
            recorder.record(
                ConnectionJournalSchema.CATEGORY,
                ConnectionJournalSchema.CONSTRUCT,
                mapOf("journalSeq" to 0, "markerValue" to "vm-1710"),
            )
            check(recorder.connectionJournalArchive().isNotEmpty())
        }

    private class BlockingUploadSession : SshSession {
        val finishUpload = CompletableDeferred<Unit>()
        var execCalls = 0
        var uploadCalls = 0
        var closed = false

        override val isConnected: Boolean get() = !closed
        override suspend fun exec(command: String): ExecResult {
            execCalls += 1
            return ExecResult("", "", 0)
        }
        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String {
            uploadCalls += 1
            finishUpload.await()
            input.readBytes()
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
        const val HOST_ID = 1710L
        const val KEY_PATH = "/tmp/issue1710-key"
        val TARGET = SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = "10.0.2.2",
                port = 2222,
                user = "testuser",
                credentialId = "$HOST_ID:$KEY_PATH",
            ),
            key = SshKey.Path(File(KEY_PATH)),
            knownHosts = KnownHostsPolicy.AcceptAll,
        )
    }
}
