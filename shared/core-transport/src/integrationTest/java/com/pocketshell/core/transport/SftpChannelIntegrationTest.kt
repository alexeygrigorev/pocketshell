package com.pocketshell.core.transport

import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Random
import kotlin.math.abs

/**
 * D34-class headless real-transport proof for [SftpChannelImpl] (rewrite task
 * T-4), driven against the same Testcontainers Docker sshd the T-2 suite uses
 * (`tests/docker/Dockerfile.ssh`, whose sshd_config enables the sftp
 * subsystem).
 *
 * Covers the T-4 acceptance journeys against a real sftp-server:
 * - write → list → read → rename → delete round-trip, byte-identical, with a
 *   payload big enough (100 KiB, random bytes) to span many SFTP packets in
 *   both directions
 * - [SftpChannel.mkdir] creates a directory that [SftpChannel.list] then sees
 * - the [SftpChannel.read] size cap: over-size reads fail with
 *   [SftpFileTooLargeException] instead of silently truncating, both when the
 *   server declares an honest size AND when it under-reports it (`/proc`
 *   entries stat as 0 bytes yet have content — the case a "just check
 *   stat().size" implementation gets wrong)
 * - [SftpChannel.stat] fields on a real file and a real directory, null for a
 *   missing path
 * - `sftp()` is cached per connection, and refuses to hand out a channel once
 *   the connection is spent
 *
 * Docker-less machines skip via `assumeTrue`, exactly like
 * [RealHostConnectionIntegrationTest]; the CI integration job runs them for
 * real.
 */
class SftpChannelIntegrationTest {

    companion object {
        private const val CONTAINER_SSH_PORT = 22
        private const val TEST_USER = "testuser"

        /**
         * A pseudo-file the kernel reports as 0 bytes but which reads back with
         * real content — the honest way to prove the mid-stream cap works.
         */
        private const val UNDER_REPORTING_PATH = "/proc/cpuinfo"

        private val projectRoot: Path by lazy { findProjectRoot() }

        private val sshImage: ImageFromDockerfile by lazy {
            ImageFromDockerfile("pocketshell-test-ssh", false)
                .withDockerfile(projectRoot.resolve("tests/docker/Dockerfile.ssh"))
        }

        @Volatile
        private var container: GenericContainer<*>? = null

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val dockerAvailable = runCatching {
                DockerClientFactory.instance().isDockerAvailable
            }.getOrDefault(false)
            assumeTrue("Docker not available; skipping T-4 SFTP integration tests", dockerAvailable)

            container = GenericContainer(sshImage)
                .withExposedPorts(CONTAINER_SSH_PORT)
                .also { it.start() }
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            container?.stop()
            container = null
        }

        private fun findProjectRoot(): Path {
            var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            while (dir != null) {
                if (dir.resolve("tests/docker/Dockerfile.ssh").toFile().exists()) {
                    return dir
                }
                dir = dir.parent
            }
            error(
                "Could not locate tests/docker/Dockerfile.ssh from user.dir=" +
                    System.getProperty("user.dir"),
            )
        }
    }

    /** Resolves every KeyRef to the disposable fixture keypair on disk. */
    private val fixtureSecrets = object : AuthSecretResolver {
        override suspend fun resolvePrivateKeyPem(keyId: Long): String =
            projectRoot.resolve("tests/docker/test_key").toFile().readText()

        override suspend fun resolvePassword(secretRef: String): CharArray =
            fail("password auth is not part of the Docker fixture") as Nothing
    }

    /** TOFU store over a single volatile slot. */
    private class InMemoryTrustStore(@Volatile private var stored: String? = null) : TrustStore {
        override suspend fun evaluate(target: HostTarget, presentedSha256: String): TrustDecision {
            val known = stored ?: return TrustDecision.Unknown(presentedSha256)
            return if (known == presentedSha256) {
                TrustDecision.Trusted
            } else {
                TrustDecision.Mismatch(storedSha256 = known, presentedSha256 = presentedSha256)
            }
        }

        override suspend fun recordTrusted(target: HostTarget, sha256: String) {
            stored = sha256
        }
    }

    private fun targetFor(container: GenericContainer<*>): HostTarget = HostTarget(
        hostId = 1L,
        hostname = container.host,
        port = container.getMappedPort(CONTAINER_SSH_PORT),
        username = TEST_USER,
        auth = AuthMaterial.KeyRef(keyId = 1L),
    )

    /** Dial, accept the Unknown fingerprint, retry, expect Connected. */
    private suspend fun connectTrusted(): HostConnection {
        val factory = RealHostConnectionFactory(secrets = fixtureSecrets)
        val target = targetFor(container!!)
        val trust = InMemoryTrustStore()
        val outcome = when (val first = factory.connect(target, trust)) {
            is ConnectResult.Connected -> first
            is ConnectResult.NeedsTrust -> {
                val decision = first.decision as? TrustDecision.Unknown
                    ?: fail("first contact should be Unknown, got ${first.decision}") as Nothing
                trust.recordTrusted(target, decision.fingerprintSha256)
                first.retry()
            }

            is ConnectResult.Failed ->
                fail("connect failed: ${first.message} (${first.cause})") as Nothing
        }
        assertTrue("retry after recordTrusted should connect, got $outcome", outcome is ConnectResult.Connected)
        return (outcome as ConnectResult.Connected).connection
    }

    /**
     * Runs [body] with a live connection, its cached [SftpChannel], and a fresh
     * empty remote scratch directory that is removed afterwards.
     */
    private fun withSftp(body: suspend (SftpChannel, String) -> Unit) = runBlocking {
        val connection = connectTrusted()
        val scratch = "/tmp/t4-sftp-${abs(Random().nextLong())}"
        try {
            val sftp = connection.sftp()
            sftp.mkdir(scratch)
            try {
                body(sftp, scratch)
            } finally {
                runCatching { cleanUp(sftp, scratch) }
            }
        } finally {
            connection.close()
        }
    }

    /** Removes everything under [dir], then [dir] itself. */
    private suspend fun cleanUp(sftp: SftpChannel, dir: String) {
        sftp.list(dir).forEach { entry ->
            if (entry.isDirectory) cleanUp(sftp, entry.path) else sftp.delete(entry.path)
        }
        sftp.delete(dir)
    }

    // ------------------------------------------------------------------ tests

    @Test(timeout = 180_000)
    fun writeListReadRenameDeleteRoundTrip() = withSftp { sftp, scratch ->
        // 100 KiB of random bytes: spans many SFTP packets in both directions
        // and would expose any UTF-8 / text-mode mangling in the chunking.
        val payload = ByteArray(100 * 1024).also { Random(1234).nextBytes(it) }
        val original = "$scratch/payload.bin"
        val renamed = "$scratch/payload-renamed.bin"

        sftp.write(original, payload)

        val listed = sftp.list(scratch)
        assertEquals("only the written file should be in the scratch dir: $listed", 1, listed.size)
        assertEquals("payload.bin", listed.single().name)
        assertEquals(original, listed.single().path)
        assertFalse(listed.single().isDirectory)
        assertEquals(payload.size.toLong(), listed.single().sizeBytes)
        assertTrue(
            "list must exclude . and .., got ${listed.map { it.name }}",
            listed.none { it.name == "." || it.name == ".." },
        )

        val readBack = sftp.read(original, maxBytes = 1024 * 1024)
        assertArrayEquals("read must return the written bytes verbatim", payload, readBack)

        sftp.rename(original, renamed)
        assertNull("the old path is gone after rename", sftp.stat(original))
        assertEquals(payload.size.toLong(), sftp.stat(renamed)!!.sizeBytes)
        assertArrayEquals(payload, sftp.read(renamed, maxBytes = 1024 * 1024))
        assertEquals(listOf("payload-renamed.bin"), sftp.list(scratch).map { it.name })

        sftp.delete(renamed)
        assertNull("the file is gone after delete", sftp.stat(renamed))
        assertEquals(emptyList<SftpEntry>(), sftp.list(scratch))
    }

    @Test(timeout = 180_000)
    fun mkdirCreatesADirectoryListCanSee() = withSftp { sftp, scratch ->
        val child = "$scratch/nested"
        sftp.mkdir(child)

        val entry = sftp.list(scratch).single()
        assertEquals("nested", entry.name)
        assertEquals(child, entry.path)
        assertTrue("mkdir'd entry must list as a directory: $entry", entry.isDirectory)

        // It is a usable directory, not just a name: a file written inside it
        // shows up when listing it.
        sftp.write("$child/inside.txt", "hi".toByteArray())
        assertEquals(listOf("inside.txt"), sftp.list(child).map { it.name })
        assertTrue(sftp.stat(child)!!.isDirectory)
    }

    @Test(timeout = 180_000)
    fun readRefusesAnOverSizeFileInsteadOfTruncating() = withSftp { sftp, scratch ->
        val path = "$scratch/big.log"
        val content = "0123456789".repeat(1_000).toByteArray() // 10_000 bytes
        sftp.write(path, content)

        val tooLarge = assertThrows(SftpFileTooLargeException::class.java) {
            runBlocking { sftp.read(path, maxBytes = 4_096) }
        }
        assertEquals(path, tooLarge.path)
        assertEquals(content.size.toLong(), tooLarge.sizeBytes)
        assertEquals(4_096L, tooLarge.maxBytes)

        // Exactly at the cap is fine, and still byte-identical: the cap is
        // "more than maxBytes fails", not "close to maxBytes fails".
        assertArrayEquals(content, sftp.read(path, maxBytes = content.size.toLong()))
        // One byte under the real size is over the cap.
        assertThrows(SftpFileTooLargeException::class.java) {
            runBlocking { sftp.read(path, maxBytes = content.size.toLong() - 1) }
        }
    }

    @Test(timeout = 180_000)
    fun readRefusesAFileWhoseServerReportedSizeUnderReportsItsContent() = withSftp { sftp, _ ->
        // /proc/cpuinfo stats as 0 bytes but reads back with real content. An
        // implementation that only trusted stat().size would happily return a
        // truncated prefix here and call it the whole file.
        val declared = sftp.stat(UNDER_REPORTING_PATH)
        assertEquals(
            "fixture assumption: $UNDER_REPORTING_PATH should stat as 0 bytes, got $declared",
            0L,
            declared!!.sizeBytes,
        )
        val whole = sftp.read(UNDER_REPORTING_PATH, maxBytes = 1024 * 1024)
        assertTrue(
            "fixture assumption: $UNDER_REPORTING_PATH should have content, got ${whole.size} bytes",
            whole.size > 64,
        )

        val tooLarge = assertThrows(SftpFileTooLargeException::class.java) {
            runBlocking { sftp.read(UNDER_REPORTING_PATH, maxBytes = 8) }
        }
        assertEquals(UNDER_REPORTING_PATH, tooLarge.path)
        assertEquals(8L, tooLarge.maxBytes)
        assertTrue(
            "the reported size should be the bytes actually delivered, got ${tooLarge.sizeBytes}",
            tooLarge.sizeBytes > 8,
        )
    }

    @Test(timeout = 180_000)
    fun statReportsSaneFieldsForFilesDirectoriesAndMissingPaths() = withSftp { sftp, scratch ->
        val path = "$scratch/notes.md"
        val bytes = "# notes\n".toByteArray()
        val beforeMs = System.currentTimeMillis()
        sftp.write(path, bytes)

        val file = sftp.stat(path)!!
        assertEquals(path, file.path)
        assertEquals("notes.md", file.name)
        assertFalse(file.isDirectory)
        assertEquals(bytes.size.toLong(), file.sizeBytes)
        assertTrue(
            "mtime should be a plausible epoch-ms just now, got ${file.modifiedEpochMs}",
            file.modifiedEpochMs in (beforeMs - 120_000)..(System.currentTimeMillis() + 120_000),
        )

        val dir = sftp.stat(scratch)!!
        assertEquals(scratch, dir.path)
        assertTrue("the scratch dir should stat as a directory: $dir", dir.isDirectory)
        assertTrue("a directory should have a plausible mtime", dir.modifiedEpochMs > 0)

        assertNull("a missing path stats as null, it does not throw", sftp.stat("$scratch/nope.txt"))
    }

    @Test(timeout = 180_000)
    fun sftpIsCachedPerConnectionAndRefusedOnceTheConnectionIsClosed() = runBlocking {
        val connection = connectTrusted()
        val sftp = connection.sftp()
        assertSame("sftp() must return the same cached channel", sftp, connection.sftp())
        // The cached channel is live, not a placeholder.
        assertTrue(sftp.stat("/tmp")!!.isDirectory)

        connection.close()

        val refused = assertThrows(IOException::class.java) {
            runBlocking { connection.sftp() }
        }
        assertTrue(
            "the refusal should say the connection is closed, got: ${refused.message}",
            refused.message!!.contains("closed"),
        )
    }
}
