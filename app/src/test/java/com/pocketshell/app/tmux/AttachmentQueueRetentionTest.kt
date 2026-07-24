package com.pocketshell.app.tmux

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.composer.DurableAttachmentRef
import com.pocketshell.app.composer.InMemoryOutboundQueueStore
import com.pocketshell.app.composer.OutboundItem
import com.pocketshell.app.composer.OutboundRoute
import com.pocketshell.app.composer.OutboundState
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.RemoteEntry
import com.pocketshell.core.ssh.RemoteListing
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.TmuxClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Issue #1548: upload-triggered pruning must retain remote bytes referenced by
 * an undelivered durable outbound row after the 24-hour safety window expires.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AttachmentQueueRetentionTest {

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    @Test
    fun issue1548_uploadPruneRetainsOldQueuedAttachmentBytesAndDeletesOldUnreferencedPeer() =
        runVmTest {
            val now = System.currentTimeMillis()
            val remoteDir = ".pocketshell/attachments/host-41-alpha"
            val referencedPath = "~/$remoteDir/referenced.png"
            val capPeerPath = "~/$remoteDir/cap-peer.png"
            val ttlPeerPath = "~/$remoteDir/ttl-peer.png"
            val deliveredPath = "~/$remoteDir/delivered.png"
            val collisionPath = "~/$remoteDir/shared.png"
            val otherCollisionPath = "~/.pocketshell/attachments/host-41-other/shared.png"
            val referencedBytes = "durable queued attachment".toByteArray()
            val otherCollisionBytes = "other directory".toByteArray()
            val store = InMemoryOutboundQueueStore()
            val row = store.enqueue(
                sessionKey = DURABLE_SESSION_KEY,
                cleanText = "inspect this",
                attachments = listOf(
                    DurableAttachmentRef(
                        remotePath = referencedPath,
                        displayName = "referenced.png",
                        mimeType = "image/png",
                    ),
                ),
                createdAtMs = now - days(2),
                paneId = "%1",
                route = OutboundRoute.AgentPayload,
            )
            val retainedByState = listOf(
                OutboundState.Uploading,
                OutboundState.InFlight,
                OutboundState.Failed,
            ).associateWith { state ->
                val path = "~/$remoteDir/${state.name.lowercase()}.png"
                val queued = store.enqueue(
                    sessionKey = DURABLE_SESSION_KEY,
                    cleanText = state.name,
                    attachments = listOf(DurableAttachmentRef(path, path.substringAfterLast('/'))),
                    createdAtMs = now - days(8),
                )
                when (state) {
                    OutboundState.Uploading -> store.markUploading(queued.id)!!
                    OutboundState.InFlight -> store.markInFlight(queued.id)!!
                    OutboundState.Failed -> store.markFailed(queued.id, "offline")!!
                    else -> error("unexpected state $state")
                }
                path
            }
            store.enqueueExisting(
                OutboundItem(
                    id = "delivered-row",
                    sessionKey = DURABLE_SESSION_KEY,
                    cleanText = "already sent",
                    attachments = listOf(DurableAttachmentRef(deliveredPath, "delivered.png")),
                    state = OutboundState.Delivered,
                    createdAtMs = now - days(8),
                ),
            )
            store.enqueue(
                sessionKey = DURABLE_SESSION_KEY,
                cleanText = "other scope",
                attachments = listOf(
                    DurableAttachmentRef(
                        "~/.pocketshell/attachments/host-41-other/shared.png",
                        "shared.png",
                    ),
                ),
                createdAtMs = now - days(8),
            )
            val session = RetentionSshSession(now).apply {
                seed(remoteDir, "referenced.png", referencedBytes, now - days(2))
                seed(remoteDir, "cap-peer.png", "cap peer".toByteArray(), now - days(2))
                seed(remoteDir, "ttl-peer.png", "ttl peer".toByteArray(), now - days(8))
                seed(remoteDir, "delivered.png", "delivered".toByteArray(), now - days(8))
                seed(remoteDir, "shared.png", "unrelated".toByteArray(), now - days(8))
                seed(
                    ".pocketshell/attachments/host-41-other",
                    "shared.png",
                    otherCollisionBytes,
                    now - days(8),
                )
                retainedByState.forEach { (state, path) ->
                    seed(remoteDir, path.substringAfterLast('/'), state.name.toByteArray(), now - days(8))
                }
                repeat(20) { index ->
                    seed(
                        remoteDir,
                        "fresh-$index.png",
                        byteArrayOf(index.toByte()),
                        now - minutes(index + 1L),
                    )
                }
            }
            val vm = newVm(store)
            vm.replaceClientForTest(
                hostId = HOST_ID,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "alpha",
                client = FakeTmuxClient(),
                session = session,
                tmuxSessionId = TMUX_SESSION_ID,
                sessionCreated = SESSION_CREATED,
            )

            val incoming = File.createTempFile(
                "issue1548-new-",
                ".png",
                ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
            ).apply { writeBytes("new upload".toByteArray()) }
            val staged = vm.stagePromptAttachments(listOf(Uri.fromFile(incoming)))

            assertTrue("the upload that triggers prune must succeed: $staged", staged.isSuccess)
            val deliverable = store.claim(row.id)
            assertNotNull("the durable queue row must remain claimable after prune", deliverable)
            assertArrayEquals(
                "the claimed row must still resolve to its original remote bytes after >24h prune",
                referencedBytes,
                session.read(deliverable!!.attachments.single().remotePath),
            )
            retainedByState.forEach { (state, path) ->
                assertArrayEquals(
                    "$state attachment must survive the independent >7-day TTL branch",
                    state.name.toByteArray(),
                    session.read(path),
                )
            }
            assertNull(
                "an equally-old unreferenced attachment outside the newest cap must be pruned",
                session.read(capPeerPath),
            )
            assertNull(
                "an unreferenced attachment older than seven days must be pruned by TTL",
                session.read(ttlPeerPath),
            )
            assertNull(
                "a Delivered row must not retain its remote bytes",
                session.read(deliveredPath),
            )
            assertNull(
                "the same basename referenced in another directory must not pin this object",
                session.read(collisionPath),
            )
            assertArrayEquals(
                "pruning the current directory must not delete the same basename elsewhere",
                otherCollisionBytes,
                session.read(otherCollisionPath),
            )
        }

    private fun runVmTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        LivenessProbeTestOverride.setAutoStartEnabledForTest(false)
        try {
            body()
        } finally {
            LivenessProbeTestOverride.clear()
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.newVm(store: InMemoryOutboundQueueStore): TmuxSessionViewModel =
        TmuxSessionViewModel(
            tmuxClientFactory = TmuxClientFactory(factoryScope),
            activeTmuxClients = ActiveTmuxClients(),
            runtimeCache = TmuxSessionRuntimeCache(),
            sshLeaseManager = SshLeaseManager(
                connector = SshLeaseConnector { target ->
                    error("unexpected SSH lease connect for ${target.leaseKey}")
                },
                idleTtlMillis = 0L,
                connectTimeoutContext = StandardTestDispatcher(testScheduler),
                nowMillis = { testScheduler.currentTime },
            ),
            sessionLifecycleSignals = null,
            applicationContext = ApplicationProvider.getApplicationContext(),
            outboundQueueStore = store,
        ).also {
            it.setSeedIoDispatcherForTest(StandardTestDispatcher(testScheduler))
        }

    private class RetentionSshSession(private val nowMillis: Long) : SshSession {
        private data class RemoteFile(
            val bytes: ByteArray,
            val modifiedMillis: Long,
        )

        private val files = ConcurrentHashMap<String, RemoteFile>()
        @Volatile
        private var connected = true

        override val isConnected: Boolean get() = connected

        fun seed(remoteDir: String, name: String, bytes: ByteArray, modifiedMillis: Long) {
            files["$remoteDir/$name"] = RemoteFile(bytes, modifiedMillis)
        }

        fun read(displayPath: String): ByteArray? =
            files[displayPath.removePrefix("~/")]?.bytes

        override suspend fun exec(command: String): ExecResult {
            if (!command.startsWith("rm -f -- ")) {
                return ExecResult(stdout = "", stderr = "", exitCode = 0)
            }
            val exactTargets = Regex("""~/'([^']*)'""")
                .findAll(command)
                .map { match -> match.groupValues[1] }
                .toSet()
            val deleted = exactTargets.count { path -> files.remove(path) != null }
            return ExecResult(stdout = "deleted\t$deleted\n", stderr = "", exitCode = 0)
        }

        override suspend fun listDirectory(remotePath: String, maxEntries: Int): RemoteListing {
            val dir = remotePath.removePrefix("~/").trimEnd('/')
            val entries = files.entries
                .asSequence()
                .filter { (path, _) -> path.substringBeforeLast('/') == dir }
                .map { (path, file) ->
                    RemoteEntry(
                        name = path.substringAfterLast('/'),
                        type = RemoteEntry.Type.FILE,
                        sizeBytes = file.bytes.size.toLong(),
                        modifiedEpochSec = file.modifiedMillis / 1_000L,
                    )
                }
                .sortedBy { it.name }
                .take(maxEntries)
                .toList()
            return RemoteListing(entries = entries, truncated = entries.size < files.size)
        }

        override suspend fun uploadFile(file: File, remotePath: String): String {
            files[remotePath] = RemoteFile(file.readBytes(), nowMillis)
            return remotePath
        }

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String {
            files[remotePath] = RemoteFile(input.readBytes(), nowMillis)
            return remotePath
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            Job().apply { complete() }

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = error("not used")

        override fun close() {
            connected = false
        }
    }

    private companion object {
        const val HOST_ID = 41L
        const val TMUX_SESSION_ID = "\$9"
        const val SESSION_CREATED = 1_700_000_000L
        const val DURABLE_SESSION_KEY = "tmux:$HOST_ID:$TMUX_SESSION_ID:$SESSION_CREATED"

        fun days(value: Long): Long = value * 24L * 60L * 60L * 1_000L
        fun minutes(value: Long): Long = value * 60L * 1_000L
    }
}
