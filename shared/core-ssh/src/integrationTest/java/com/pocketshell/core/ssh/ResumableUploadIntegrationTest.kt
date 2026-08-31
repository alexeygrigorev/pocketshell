package com.pocketshell.core.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Issue #1733 real-wire regression suite. Every transfer goes through
 * [RealSshSession] and a real Docker OpenSSH server.
 */
class ResumableUploadIntegrationTest {

    companion object {
        private const val CONTAINER_SSH_PORT = 22
        private val projectRoot: Path by lazy { findProjectRoot() }

        @Volatile
        private var container: GenericContainer<*>? = null

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val dockerAvailable = runCatching {
                DockerClientFactory.instance().isDockerAvailable
            }.getOrDefault(false)
            assumeTrue("Docker not available; skipping resumable-upload tests", dockerAvailable)
            val image = ImageFromDockerfile("pocketshell-test-ssh", false)
                .withDockerfile(projectRoot.resolve("tests/docker/Dockerfile.ssh"))
            container = GenericContainer(image)
                .withExposedPorts(CONTAINER_SSH_PORT)
                .also { it.start() }
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            runCatching {
                container?.let {
                    DockerClientFactory.instance().client().unpauseContainerCmd(it.containerId).exec()
                }
            }
            container?.stop()
            container = null
        }

        private fun findProjectRoot(): Path {
            var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            while (dir != null) {
                if (dir.resolve("tests/docker/Dockerfile.ssh").toFile().exists()) return dir
                dir = dir.parent
            }
            error("Could not locate tests/docker/Dockerfile.ssh")
        }
    }

    private val host: String get() = container!!.host
    private val port: Int get() = container!!.getMappedPort(CONTAINER_SSH_PORT)
    private val keyFile: File get() = projectRoot.resolve("tests/docker/test_key").toFile()

    private suspend fun connect(): SshSession =
        SshConnection.connect(
            host = host,
            port = port,
            user = "testuser",
            key = SshKey.Path(keyFile),
            knownHosts = TestOnlyAcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()

    private fun connectReal(uploadStallTimeoutMs: Long): RealSshSession = runBlocking {
        val connector = SshConnection.RealSshConnector
        val client = connector.createClient()
        connector.applyKnownHostsPolicy(client, TestOnlyAcceptAll)
        connector.connect(client, host, port, 15_000)
        connector.authenticate(client, "testuser", SshKey.Path(keyFile), null)
        RealSshSession(client, uploadStallTimeoutMs = uploadStallTimeoutMs)
    }

    @Test
    fun transportCutCommitsNAndNewSessionSendsOnlyTotalMinusN() = runBlocking {
        val case = newCase("transport-cut", bytes = 12 * 1024 * 1024)
        val first = connect()
        prepare(first, case)
        val cutIssued = AtomicBoolean(false)
        val firstFailure = runCatching {
            first.uploadQueueSidecar(case.request) { progress ->
                if (progress.bytesTransferred >= 512 * 1024L && cutIssued.compareAndSet(false, true)) {
                    first.close()
                    throw IOException("issue #1733 genuine worker/transport cut")
                }
            }
        }.exceptionOrNull()
        first.close()
        assertNotNull("the controlled transport cut must fail attempt one", firstFailure)

        connect().use { inspector ->
            val committedN = remoteSize(inspector, case.paths.dataPath)
            assertNotNull("retryable transport failure must preserve its stable checkpoint", committedN)
            assertTrue("checkpoint N must be > 0, got $committedN", committedN!! > 0L)
            assertTrue("checkpoint N must be < total, got $committedN", committedN < case.request.expectedBytes)
            assertFalse("final must remain absent before promotion", remoteExists(inspector, case.request.remotePath))

            inspector.close()
            val second = connect()
            second.use { resumed ->
                val result = resumed.uploadQueueSidecar(case.request)
                assertEquals(committedN, result.resumedFromBytes)
                assertEquals(
                    "attempt two must spend only total-N bytes",
                    case.request.expectedBytes - committedN,
                    result.transmittedBytes,
                )
                assertEquals(QueueSidecarUploadDisposition.Uploaded, result.disposition)
                assertEquals(case.request.expectedBytes, remoteSize(resumed, case.request.remotePath))
                assertEquals(case.localSha256, remoteSha256(resumed, case.request.remotePath))
                assertFalse("checkpoint data must disappear after atomic mv", remoteExists(resumed, case.paths.dataPath))
                assertFalse("checkpoint identity must disappear after promotion", remoteExists(resumed, case.paths.identityPath))
            }
        }
    }

    @Test
    fun cancellationPreservesCheckpointAndResumes() = runBlocking {
        val case = newCase("cancellation", bytes = 4 * 1024 * 1024)
        connect().use { first ->
            prepare(first, case)
            val cancelled = runCatching {
                first.uploadQueueSidecar(case.request) { progress ->
                    if (progress.bytesTransferred >= 256 * 1024L) {
                        throw CancellationException("issue #1733 controlled cancellation")
                    }
                }
            }.exceptionOrNull()
            assertTrue(cancelled is CancellationException)
        }

        connect().use { second ->
            val committed = remoteSize(second, case.paths.dataPath)
            assertNotNull("cancellation must preserve committed checkpoint bytes", committed)
            assertTrue(committed!! in 1 until case.request.expectedBytes)
            val result = second.uploadQueueSidecar(case.request)
            assertEquals(committed, result.resumedFromBytes)
            assertEquals(case.request.expectedBytes - committed, result.transmittedBytes)
            assertEquals(case.localSha256, remoteSha256(second, case.request.remotePath))
        }
    }

    @Test
    fun noProgressTimeoutPreservesCheckpoint() = runBlocking {
        val case = newCase("stall", bytes = 24 * 1024 * 1024)
        val session = connectReal(uploadStallTimeoutMs = 500L)
        prepare(session, case)
        val paused = AtomicBoolean(false)
        val failure = try {
            runCatching {
                session.uploadQueueSidecar(case.request) { progress ->
                    if (progress.bytesTransferred >= 512 * 1024L && paused.compareAndSet(false, true)) {
                        DockerClientFactory.instance().client()
                            .pauseContainerCmd(container!!.containerId)
                            .exec()
                    }
                }
            }.exceptionOrNull()
        } finally {
            if (paused.get()) {
                DockerClientFactory.instance().client()
                    .unpauseContainerCmd(container!!.containerId)
                    .exec()
            }
            session.close()
        }
        assertNotNull("a no-progress stall must surface", failure)
        assertTrue("expected a bounded stall error, got $failure", failure is SshException)

        connect().use { inspector ->
            val committed = remoteSize(inspector, case.paths.dataPath)
            assertNotNull("no-progress timeout must preserve checkpoint", committed)
            assertTrue("preserved checkpoint must contain a strict prefix", committed!! in 1 until case.request.expectedBytes)
            assertFalse(remoteExists(inspector, case.request.remotePath))
            cleanup(inspector, case)
        }
    }

    @Test
    fun completeCheckpointPromotesAndLostSuccessReturnsAlreadyComplete() = runBlocking {
        val case = newCase("complete-and-lost-ack", bytes = 256 * 1024)
        connect().use { session ->
            prepare(session, case)
            seedCheckpoint(session, case, case.localFile.readBytes(), expectedIdentity = true)
            val promoted = session.uploadQueueSidecar(case.request)
            assertEquals(case.request.expectedBytes, promoted.resumedFromBytes)
            assertEquals(0L, promoted.transmittedBytes)
            assertEquals(QueueSidecarUploadDisposition.Uploaded, promoted.disposition)
            assertEquals(case.localSha256, remoteSha256(session, case.request.remotePath))

            val lostAckRetry = session.uploadQueueSidecar(case.request)
            assertEquals(QueueSidecarUploadDisposition.AlreadyComplete, lostAckRetry.disposition)
            assertEquals(case.request.expectedBytes, lostAckRetry.resumedFromBytes)
            assertEquals(0L, lostAckRetry.transmittedBytes)
        }
    }

    @Test
    fun corruptCheckpointCleansAndRestartsExplicitly() = runBlocking {
        val case = newCase("corrupt", bytes = 384 * 1024)
        connect().use { session ->
            prepare(session, case)
            val oversized = ByteArray(case.request.expectedBytes.toInt() + 17) { 0x55 }
            seedCheckpoint(session, case, oversized, expectedIdentity = false)

            val result = session.uploadQueueSidecar(case.request)
            assertEquals("corrupt checkpoint must clean-restart at zero", 0L, result.resumedFromBytes)
            assertEquals(case.request.expectedBytes, result.transmittedBytes)
            assertEquals(case.localSha256, remoteSha256(session, case.request.remotePath))
            assertFalse(remoteExists(session, case.paths.dataPath))
            assertFalse(remoteExists(session, case.paths.identityPath))
        }
    }

    @Test
    fun permanentLocalValidationCleansOnlyOwnedCheckpointAndPreservesFinal() = runBlocking {
        val case = newCase("local-validation", bytes = 128 * 1024)
        connect().use { session ->
            prepare(session, case)
            seedCheckpoint(session, case, ByteArray(4096) { 0x33 }, expectedIdentity = true)
            val sentinel = "existing-final-is-untouched"
            session.exec("printf '%s' ${shellSingleQuote(sentinel)} > ${shellSingleQuote(case.request.remotePath)}")
            case.localFile.appendBytes(byteArrayOf(1))

            val failure = runCatching { session.uploadQueueSidecar(case.request) }.exceptionOrNull()
            assertNotNull("changed immutable local sidecar must hard-fail", failure)
            assertFalse("owned checkpoint data must be cleaned", remoteExists(session, case.paths.dataPath))
            assertFalse("owned checkpoint identity must be cleaned", remoteExists(session, case.paths.identityPath))
            val final = session.exec("cat ${shellSingleQuote(case.request.remotePath)}").stdout
            assertEquals("permanent validation must not overwrite final", sentinel, final)
        }
    }

    @Test
    fun missingLocalFileCleansOwnedCheckpointAndPreservesFinal() = runBlocking {
        val case = newCase("missing-local", bytes = 128 * 1024)
        connect().use { session ->
            prepare(session, case)
            seedCheckpoint(session, case, ByteArray(4096) { 0x4d }, expectedIdentity = true)
            val finalBytes = ByteArray(8193) { index -> ((index * 17 + 11) % 251).toByte() }
            val finalSeed = File.createTempFile("issue1733-missing-local-final", ".bin").apply {
                writeBytes(finalBytes)
                deleteOnExit()
            }
            val finalSha256 = sha256(finalSeed)
            session.uploadFile(finalSeed, case.request.remotePath)
            assertEquals(4096L, remoteSize(session, case.paths.dataPath))
            assertTrue(remoteExists(session, case.paths.identityPath))
            assertEquals(
                queueSidecarCheckpointIdentity(case.request),
                session.exec("cat ${shellSingleQuote(case.paths.identityPath)}").stdout,
            )
            assertEquals(finalBytes.size.toLong(), remoteSize(session, case.request.remotePath))
            assertEquals(finalSha256, remoteSha256(session, case.request.remotePath))
            assertArrayEquals(
                finalBytes,
                session.downloadFile(case.request.remotePath, finalBytes.size.toLong()),
            )

            assertTrue("local source deletion must succeed", case.localFile.delete())
            assertFalse("local source must be absent before upload", case.localFile.exists())
            val failure = runCatching { session.uploadQueueSidecar(case.request) }.exceptionOrNull()

            assertTrue("missing immutable local sidecar must hard-fail", failure is SshException)
            assertEquals(
                "Queued attachment no longer exists: ${case.request.displayName}",
                failure?.message,
            )
            assertFalse("owned checkpoint data must be cleaned", remoteExists(session, case.paths.dataPath))
            assertFalse("owned checkpoint identity must be cleaned", remoteExists(session, case.paths.identityPath))
            assertEquals(finalBytes.size.toLong(), remoteSize(session, case.request.remotePath))
            assertEquals(finalSha256, remoteSha256(session, case.request.remotePath))
            assertArrayEquals(
                "permanent missing-local failure must preserve exact final bytes",
                finalBytes,
                session.downloadFile(case.request.remotePath, finalBytes.size.toLong()),
            )
        }
    }

    @Test
    fun wrongSizedPreExistingFinalFailsClosedAndCleansOwnedCheckpoint() = runBlocking {
        val case = newCase("wrong-sized-final", bytes = 128 * 1024)
        connect().use { session ->
            prepare(session, case)
            seedCheckpoint(session, case, ByteArray(4096) { 0x5a }, expectedIdentity = true)
            val finalBytes = ByteArray(12_289) { index -> ((index * 29 + 3) % 251).toByte() }
            val finalSeed = File.createTempFile("issue1733-wrong-sized-final", ".bin").apply {
                writeBytes(finalBytes)
                deleteOnExit()
            }
            val finalSha256 = sha256(finalSeed)
            session.uploadFile(finalSeed, case.request.remotePath)
            assertTrue(
                "fixture final must conflict with the immutable request size",
                finalBytes.size.toLong() != case.request.expectedBytes,
            )
            assertEquals(4096L, remoteSize(session, case.paths.dataPath))
            assertTrue(remoteExists(session, case.paths.identityPath))
            assertEquals(
                queueSidecarCheckpointIdentity(case.request),
                session.exec("cat ${shellSingleQuote(case.paths.identityPath)}").stdout,
            )
            assertEquals(finalBytes.size.toLong(), remoteSize(session, case.request.remotePath))
            assertEquals(finalSha256, remoteSha256(session, case.request.remotePath))
            assertArrayEquals(
                finalBytes,
                session.downloadFile(case.request.remotePath, finalBytes.size.toLong()),
            )

            val failure = runCatching { session.uploadQueueSidecar(case.request) }.exceptionOrNull()

            assertTrue("wrong-sized pre-existing final must hard-fail", failure is SshException)
            assertEquals(
                "Queue-sidecar upload refused for ${case.request.displayName}: final path has " +
                    "${finalBytes.size} bytes; expected ${case.request.expectedBytes}",
                failure?.message,
            )
            assertFalse("owned checkpoint data must be cleaned", remoteExists(session, case.paths.dataPath))
            assertFalse("owned checkpoint identity must be cleaned", remoteExists(session, case.paths.identityPath))
            assertEquals(finalBytes.size.toLong(), remoteSize(session, case.request.remotePath))
            assertEquals(finalSha256, remoteSha256(session, case.request.remotePath))
            assertArrayEquals(
                "conflicting final must remain byte-exact after fail-closed refusal",
                finalBytes,
                session.downloadFile(case.request.remotePath, finalBytes.size.toLong()),
            )
        }
    }

    private suspend fun prepare(session: SshSession, case: UploadCase) {
        session.exec(
            "rm -rf ${shellSingleQuote(case.remoteDir)} && mkdir -p ${shellSingleQuote(case.remoteDir)}",
        )
    }

    private suspend fun cleanup(session: SshSession, case: UploadCase) {
        session.exec("rm -rf ${shellSingleQuote(case.remoteDir)}")
    }

    private suspend fun seedCheckpoint(
        session: SshSession,
        case: UploadCase,
        bytes: ByteArray,
        expectedIdentity: Boolean,
    ) {
        val seed = File.createTempFile("issue1733-seed", ".bin").apply {
            writeBytes(bytes)
            deleteOnExit()
        }
        session.uploadFile(seed, case.paths.dataPath)
        val identity = if (expectedIdentity) {
            queueSidecarCheckpointIdentity(case.request)
        } else {
            "wrong-identity"
        }
        session.exec(
            "printf '%s' ${shellSingleQuote(identity)} > ${shellSingleQuote(case.paths.identityPath)}",
        )
    }

    private suspend fun remoteSize(session: SshSession, path: String): Long? {
        val result = session.exec(
            "if [ -f ${shellSingleQuote(path)} ]; then wc -c < ${shellSingleQuote(path)}; " +
                "else printf MISSING; fi",
        )
        return result.stdout.trim().toLongOrNull()
    }

    private suspend fun remoteExists(session: SshSession, path: String): Boolean =
        session.exec("test -e ${shellSingleQuote(path)}").exitCode == 0

    private suspend fun remoteSha256(session: SshSession, path: String): String {
        val result = session.exec("sha256sum ${shellSingleQuote(path)}")
        if (result.exitCode != 0) fail("sha256sum failed: ${result.stderr}")
        return result.stdout.substringBefore(' ').trim()
    }

    private fun newCase(name: String, bytes: Int): UploadCase {
        val file = File.createTempFile("issue1733-$name", ".bin").apply {
            outputStream().use { output ->
                val chunk = ByteArray(64 * 1024) { index -> ((index * 31 + 7) % 251).toByte() }
                var remaining = bytes
                while (remaining > 0) {
                    val count = minOf(remaining, chunk.size)
                    output.write(chunk, 0, count)
                    remaining -= count
                }
            }
            deleteOnExit()
        }
        val remoteDir = ".pocketshell/attachments/issue1733-$name"
        val request = QueueSidecarResumableUploadRequest(
            localFile = file,
            remotePath = "$remoteDir/payload.bin",
            stableToken = "durable-sidecar-$name",
            expectedBytes = file.length(),
            displayName = "payload.bin",
        )
        return UploadCase(
            localFile = file,
            localSha256 = sha256(file),
            remoteDir = remoteDir,
            request = request,
            paths = queueSidecarCheckpointPaths(request.remotePath, request.stableToken),
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private data class UploadCase(
        val localFile: File,
        val localSha256: String,
        val remoteDir: String,
        val request: QueueSidecarResumableUploadRequest,
        val paths: QueueSidecarCheckpointPaths,
    )
}
