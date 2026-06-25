package com.pocketshell.core.ssh

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Issue #930 — attachment upload must be ATOMIC against a real sshd.
 *
 * Hard evidence (D7 forensics, #928): 9 zero-byte files were found in
 * `~/.pocketshell/attachments/` on the maintainer's device. Root cause:
 * [RealSshSession]'s upload primitive ran `cat > <final-path>` on the remote,
 * which TRUNCATES the destination to 0 bytes the instant the channel opens —
 * with no temp-file + rename. So any mid-stream disconnect/timeout/short read
 * left a 0-byte (or partial) file AT THE REAL attachment path: the "dropped
 * attachment" with a corrupt artifact left behind.
 *
 * Reproduce-first (D33/G10): each test below injects a non-happy upload state
 * over the REAL [RealSshSession] / [SshSession.uploadStream] path against a
 * real Docker sshd and asserts:
 *
 *  - GREEN (fixed): NO file ever appears at the FINAL attachment path on a
 *    failed/partial upload, the failure surfaces as an [SshException] (not a
 *    silent success), and no leftover `<final>.part-*` temp file is left
 *    behind. A successful upload still lands byte-exact at the final path.
 *  - RED (base, pre-fix): the final path exists with 0 bytes (or partial
 *    bytes) after the injected failure — the exact device symptom — and/or the
 *    failure is swallowed.
 *
 * Class coverage (issue ask):
 *  - mid-stream drop (the input stream throws partway through),
 *  - a wedged/stalled transfer (the upload is bounded by a timeout, not hung),
 *  - a zero-byte source (an empty upload must still be atomic + exact).
 *
 * Wired into the per-push required check `Integration tests (Docker)` via the
 * `:shared:core-ssh:integrationTest` Gradle task (the task includes all
 * `*IntegrationTest` classes).
 */
class UploadAtomicityIntegrationTest {

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
            assumeTrue("Docker not available; skipping upload-atomicity tests", dockerAvailable)

            val dockerDir = projectRoot.resolve("tests/docker")
            val image = ImageFromDockerfile("pocketshell-test-ssh", false)
                .withDockerfile(dockerDir.resolve("Dockerfile.ssh"))
            container = GenericContainer(image)
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

    private val sshPort: Int
        get() = container!!.getMappedPort(CONTAINER_SSH_PORT)

    private val privateKeyFile: File
        get() = projectRoot.resolve("tests/docker/test_key").toFile()

    private suspend fun connect(): SshSession =
        SshConnection.connect(
            host = container!!.host,
            port = sshPort,
            user = "testuser",
            key = SshKey.Path(privateKeyFile),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).getOrThrow()

    /** `stat -c '%s'` of [remoteRelative] (relative to `$HOME`), or null if absent. */
    private suspend fun remoteSizeOrNull(session: SshSession, remoteRelative: String): Long? {
        val stat = session.exec(
            "stat -c '%s' \"\$HOME/$remoteRelative\" 2>/dev/null || echo MISSING",
        )
        val out = stat.stdout.trim()
        return if (out == "MISSING" || out.isEmpty()) null else out.toLong()
    }

    /** Number of `<final>.part-*` temp files left in the attachment dir. */
    private suspend fun leftoverTempCount(session: SshSession, remoteDir: String, baseName: String): Int {
        val ls = session.exec(
            "ls \"\$HOME/$remoteDir/\" 2>/dev/null | grep -c '^${baseName}\\.part-' || true",
        )
        return ls.stdout.trim().toIntOrNull() ?: 0
    }

    /**
     * Mid-stream drop: the source stream throws after emitting only a prefix of
     * the bytes. On the buggy `cat > <final>` path this truncates the real file
     * to 0 bytes and leaves a corrupt artifact. With the atomic fix the final
     * path is never created/truncated and the temp `.part-*` is cleaned up.
     */
    @Test
    fun midStreamDropLeavesNoCorruptFileAtTheFinalPath() = runTest {
        val session = connect()
        session.use { s ->
            val dir = ".pocketshell/attachments/issue930-drop"
            val name = "dropme.bin"
            val rel = "$dir/$name"
            s.exec("rm -rf \"\$HOME/$dir\" && mkdir -p \"\$HOME/$dir\"")

            // The declared length is 4 KiB; the stream throws after 256 bytes —
            // a real mid-transfer disconnect.
            val declaredLen = 4096L
            val failing = ThrowAfterNBytesStream(emitBytes = 256, totalBytes = declaredLen.toInt())

            val ex = runCatching {
                s.uploadStream(failing, declaredLen, name, rel)
            }.exceptionOrNull()

            // 1) The failure must SURFACE (not a silent success that pretends
            //    the dropped attachment uploaded). Pre-fix this could pass
            //    silently with a 0-byte file; post-fix it throws.
            assertNotNull(
                "a mid-stream drop must surface as an exception, not a silent " +
                    "success leaving a 0-byte file (the device symptom)",
                ex,
            )
            assertTrue(
                "the surfaced failure must be an SshException, got $ex",
                ex is SshException,
            )

            // 2) THE LOAD-BEARING ASSERTION: no file (0-byte or partial) may
            //    exist at the REAL attachment path. RED on base: the file is
            //    there at 0 bytes.
            val finalSize = remoteSizeOrNull(s, rel)
            assertTrue(
                "no corrupt file may exist at the final attachment path after a " +
                    "mid-stream drop; found a file of size $finalSize bytes",
                finalSize == null,
            )

            // 3) No leftover temp `.part-*` file may accumulate.
            assertEquals(
                "the temp upload file must be cleaned up after a failed upload",
                0,
                leftoverTempCount(s, dir, name),
            )

            s.exec("rm -rf \"\$HOME/$dir\"")
        }
        Unit
    }

    /**
     * Wedged/stalled transfer: the source stream blocks indefinitely mid-copy.
     * The bounded upload (issue #930, folds in D5 W-4) must FAIL FAST with a
     * clear error instead of hanging — and still leave NO corrupt final file.
     */
    @Test
    fun stalledTransferTimesOutAndLeavesNoCorruptFinalFile() {
        // Use runBlocking (not runTest's virtual clock) so the upload path's
        // real `withTimeoutOrNull` wall-clock ceiling is exercised.
        runBlocking {
            val session = connect()
            session.use { s ->
                val dir = ".pocketshell/attachments/issue930-stall"
                val name = "stalled.bin"
                val rel = "$dir/$name"
                s.exec("rm -rf \"\$HOME/$dir\" && mkdir -p \"\$HOME/$dir\"")

                val declaredLen = 4096L
                val stalling = StallForeverStream(emitBytesThenBlock = 128)

                val started = System.nanoTime()
                val ex = runCatching {
                    s.uploadStream(stalling, declaredLen, name, rel)
                }.exceptionOrNull()
                val elapsedMs = (System.nanoTime() - started) / 1_000_000

                stalling.unblock()

                // 1) The wedged upload must NOT hang forever — it must fail fast.
                //    The upload-path ceiling is well under 60s; assert it
                //    returned in a small multiple of that, not the test runner's
                //    timeout.
                assertNotNull(
                    "a stalled upload must fail with a surfaced error, not hang",
                    ex,
                )
                assertTrue(
                    "a stalled upload must be bounded by a timeout (#930 / D5 W-4); " +
                        "it took ${elapsedMs}ms which exceeds the sane ceiling",
                    elapsedMs < 90_000,
                )
                assertTrue(
                    "the surfaced failure must be an SshException, got $ex",
                    ex is SshException,
                )

                // 2) No corrupt file at the final path.
                val finalSize = remoteSizeOrNull(s, rel)
                assertTrue(
                    "no corrupt file may exist at the final attachment path after a " +
                        "stalled upload; found a file of size $finalSize bytes",
                    finalSize == null,
                )
                assertEquals(
                    "the temp upload file must be cleaned up after a timed-out upload",
                    0,
                    leftoverTempCount(s, dir, name),
                )

                s.exec("rm -rf \"\$HOME/$dir\"")
            }
        }
    }

    /**
     * Happy path + zero-byte source: a successful upload (including an empty
     * one) must land byte-exact at the FINAL path with no temp leftover. This
     * guards the rename/verify path against breaking the normal case and
     * covers the zero-byte-source class member.
     */
    @Test
    fun successfulUploadLandsExactBytesIncludingZeroByteSource() = runTest {
        val session = connect()
        session.use { s ->
            val dir = ".pocketshell/attachments/issue930-ok"
            s.exec("rm -rf \"\$HOME/$dir\" && mkdir -p \"\$HOME/$dir\"")

            // (a) A non-trivial payload.
            val payload = ByteArray(2048) { (it % 256).toByte() }
            val name = "ok.bin"
            val rel = "$dir/$name"
            s.uploadStream(payload.inputStream(), payload.size.toLong(), name, rel)
            assertEquals(
                "a successful upload must land the exact byte count at the final path",
                payload.size.toLong(),
                remoteSizeOrNull(s, rel),
            )
            assertEquals(
                "no temp file may remain after a successful upload",
                0,
                leftoverTempCount(s, dir, name),
            )

            // (b) Zero-byte source: empty file must still arrive atomically at
            //     the final path with exactly 0 bytes (a legitimate 0-byte
            //     file is distinct from a corrupt truncation — it is the
            //     intended content).
            val emptyName = "empty.bin"
            val emptyRel = "$dir/$emptyName"
            s.uploadStream(ByteArray(0).inputStream(), 0L, emptyName, emptyRel)
            assertEquals(
                "a zero-byte source must produce a 0-byte file at the final path",
                0L,
                remoteSizeOrNull(s, emptyRel),
            )
            assertEquals(
                "no temp file may remain after a zero-byte upload",
                0,
                leftoverTempCount(s, dir, emptyName),
            )

            s.exec("rm -rf \"\$HOME/$dir\"")
        }
        Unit
    }

    /**
     * A truncated source (declares more bytes than it actually emits, then ends
     * cleanly at EOF) must be detected by the post-upload byte-count check and
     * rejected — never renamed to the final path as a short/corrupt file.
     */
    @Test
    fun shortSourceIsRejectedByIntegrityCheckAndNotRenamed() = runTest {
        val session = connect()
        session.use { s ->
            val dir = ".pocketshell/attachments/issue930-short"
            val name = "short.bin"
            val rel = "$dir/$name"
            s.exec("rm -rf \"\$HOME/$dir\" && mkdir -p \"\$HOME/$dir\"")

            // Declares 4096 but emits only 100 bytes then EOF (a clean short
            // read — no exception). The integrity check must catch the mismatch.
            val declaredLen = 4096L
            val short = ByteArray(100) { 1 }

            val ex = runCatching {
                s.uploadStream(short.inputStream(), declaredLen, name, rel)
            }.exceptionOrNull()

            assertNotNull(
                "a source shorter than its declared length must be rejected by the " +
                    "byte-count integrity check, not silently renamed",
                ex,
            )
            assertTrue(
                "the surfaced failure must be an SshException, got $ex",
                ex is SshException,
            )
            assertTrue(
                "no short/corrupt file may exist at the final attachment path",
                remoteSizeOrNull(s, rel) == null,
            )
            assertEquals(
                "the temp file must be cleaned up after an integrity-check failure",
                0,
                leftoverTempCount(s, dir, name),
            )

            s.exec("rm -rf \"\$HOME/$dir\"")
        }
        Unit
    }

    /** Emits [emitBytes] bytes, then throws — a real mid-transfer disconnect. */
    private class ThrowAfterNBytesStream(
        private val emitBytes: Int,
        @Suppress("UNUSED_PARAMETER") totalBytes: Int,
    ) : InputStream() {
        private var emitted = 0
        override fun read(): Int {
            if (emitted >= emitBytes) throw IOException("injected mid-stream upload drop (#930)")
            emitted += 1
            return 0x41
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (emitted >= emitBytes) throw IOException("injected mid-stream upload drop (#930)")
            val n = minOf(len, emitBytes - emitted)
            for (i in 0 until n) b[off + i] = 0x41
            emitted += n
            return n
        }
    }

    /** Emits a prefix, then blocks forever — a wedged channel/transfer. */
    private class StallForeverStream(
        private val emitBytesThenBlock: Int,
    ) : InputStream() {
        private var emitted = 0
        private val gate = Object()

        @Volatile
        private var released = false

        fun unblock() {
            synchronized(gate) {
                released = true
                gate.notifyAll()
            }
        }

        override fun read(): Int {
            val buf = ByteArray(1)
            val n = read(buf, 0, 1)
            return if (n < 0) -1 else buf[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (emitted < emitBytesThenBlock) {
                val n = minOf(len, emitBytesThenBlock - emitted)
                for (i in 0 until n) b[off + i] = 0x42
                emitted += n
                return n
            }
            // Block until released (only happens in teardown). The upload path's
            // own timeout must INTERRUPT this thread long before that — when it
            // does, `gate.wait` throws InterruptedException, which we let
            // propagate (do NOT swallow it: swallowing would defeat the very
            // timeout the production code under test must enforce).
            synchronized(gate) {
                while (!released) {
                    gate.wait(1000) // throws InterruptedException on interrupt
                }
            }
            return -1
        }
    }
}
