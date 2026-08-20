package com.pocketshell.app.composer

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshFileNotFoundException
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.ssh.SshUploadProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #570: attaching 2-3 images wedged/crashed the composer. The
 * remaining composer-lane gap was that [PromptAttachmentStager.stage] was
 * all-or-nothing: a single stalling/failing image among N discarded every
 * already-uploaded path. These tests pin the resilient multi-file contract:
 *
 *  - N files all succeed -> all paths returned, all uploaded.
 *  - One file fails -> the other N-1 are still returned (partial success),
 *    and the failure surfaces via [PartialAttachmentUploadException] so the
 *    composer can attach the survivors AND show an error rather than wedge.
 *  - Unknown-size files drain to a temp file that is cleaned up afterwards.
 *  - All files fail -> a plain failure (no survivors).
 *
 * Uses real `file://` URIs over Robolectric's `ContentResolver` so the
 * unknown-size [PromptAttachmentStager.uploadUri] -> drain-to-temp ->
 * [SshSession.uploadFile] path is exercised end-to-end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PromptAttachmentStagerTest {

    private lateinit var context: Context
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cacheDir = File.createTempFile("stager-cache", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        AttachmentUploadProgressPort.resetForTest()
    }

    @After
    fun tearDown() {
        AttachmentUploadProgressPort.resetForTest()
    }

    private fun tempImageUri(name: String, bytes: ByteArray = ByteArray(64) { it.toByte() }): Uri {
        val file = File(cacheDir, name)
        file.writeBytes(bytes)
        return Uri.fromFile(file)
    }

    private fun newStager() = PromptAttachmentStager(
        resolver = context.contentResolver,
        cacheDir = cacheDir,
        now = { 1_717_000_000_000L },
    )

    @Test
    fun stagesThreeImagesReturningEveryPath() = runTest {
        val session = FakeStagingSshSession()
        val uris = listOf(
            tempImageUri("a.png"),
            tempImageUri("b.png"),
            tempImageUri("c.png"),
        )

        val result = newStager().stage(session, "host-7", uris)

        val paths = result.getOrNull()
        assertNotNull("3-image stage must succeed", paths)
        assertEquals(3, paths!!.size)
        assertEquals(3, session.uploadedRemotePaths.size)
        // Every survivor temp file must be deleted once uploaded.
        assertTrue(
            "drain temp dir must be empty after upload",
            File(cacheDir, "prompt-attachments").listFiles().orEmpty().isEmpty(),
        )
    }

    @Test
    fun largeFileUploadSurfacesAdvancingByteProgressFromUploadCallback() = runTest {
        // Issue #1563 reproduce-first: a ≥5 MB attach-time upload currently
        // calls the 2-arg `uploadFile` (no listener), so the composer can only
        // show a static "Uploading N attachment(s)..." spinner. The 3-arg
        // override below records ticks ONLY when production asks for them —
        // suppressing that callback (the G6 mutant) leaves this oracle red
        // while the bytes still land.
        val session = FakeStagingSshSession()
        val payload = ByteArray(5 * 1024 * 1024) { 1 }
        val result = newStager().stage(
            session,
            "host-7",
            listOf(tempImageUri("report.zip", payload)),
        )

        assertTrue("upload itself must still succeed", result.isSuccess)
        assertEquals(1, session.uploadedRemotePaths.size)
        assertTrue(
            "large upload must invoke the bytes-transferred callback; static spinner is the reported defect",
            session.progressTicks.size >= 2,
        )
        assertTrue(
            "progress must advance as bytes transfer",
            session.progressTicks.last().bytesTransferred > session.progressTicks.first().bytesTransferred,
        )
        assertEquals(payload.size.toLong(), session.progressTicks.last().bytesTransferred)
    }

    @Test
    fun suppressedProgressCallbackStillUploadsButLeavesProgressOracleRed() = runTest {
        // G6 mutant: bytes still succeed when the core/port callback is
        // suppressed; the advancing-progress oracle must stay red.
        val session = FakeStagingSshSession(emitProgress = false)
        val payload = ByteArray(5 * 1024 * 1024) { 2 }
        val result = newStager().stage(
            session,
            "host-7",
            listOf(tempImageUri("report.zip", payload)),
        )
        assertTrue(result.isSuccess)
        assertEquals(1, session.uploadedRemotePaths.size)
        assertTrue(
            "suppressing the upload callback must leave the progress oracle empty",
            session.progressTicks.isEmpty(),
        )
    }

    @Test
    fun multiFileStageForwardsPerFileProgressCallbacks() = runTest {
        val session = FakeStagingSshSession()
        val result = newStager().stage(
            session,
            "host-7",
            listOf(
                tempImageUri("one.bin", ByteArray(64) { 1 }),
                tempImageUri("two.bin", ByteArray(128) { 2 }),
            ),
        )
        assertTrue(result.isSuccess)
        assertEquals(2, session.uploadedRemotePaths.size)
        assertEquals(6, session.progressTicks.size)
        assertTrue(session.progressTicks[2].bytesTransferred >= session.progressTicks[0].bytesTransferred)
        assertTrue(session.progressTicks[5].bytesTransferred >= session.progressTicks[3].bytesTransferred)
    }

    @Test
    fun partialFailureKeepsSurvivorsAndReportsFailure() = runTest {
        // The 2nd of three images stalls/fails; the other two must still be
        // returned so the composer attaches them instead of throwing the
        // whole batch away (the #570 multi-image wedge/discard).
        val session = FakeStagingSshSession(failOnUploadIndex = 1)
        val uris = listOf(
            tempImageUri("first.png"),
            tempImageUri("second.png"),
            tempImageUri("third.png"),
        )

        val result = newStager().stage(session, "host-7", uris)

        val error = result.exceptionOrNull()
        assertTrue(
            "partial failure surfaces PartialAttachmentUploadException, was $error",
            error is PartialAttachmentUploadException,
        )
        val partial = error as PartialAttachmentUploadException
        assertEquals(
            "two survivors must be preserved",
            2,
            partial.uploadedPaths.size,
        )
        // The two that uploaded are present; the failed one is absent.
        assertEquals(2, session.uploadedRemotePaths.size)
        // No leaked temp files for either survivors or the failed upload.
        assertTrue(
            File(cacheDir, "prompt-attachments").listFiles().orEmpty().isEmpty(),
        )
    }

    @Test
    fun allFailuresReturnPlainFailureWithNoSurvivors() = runTest {
        val session = FakeStagingSshSession(failOnUploadIndex = -2) // fail all
        val uris = listOf(tempImageUri("x.png"), tempImageUri("y.png"))

        val result = newStager().stage(session, "host-7", uris)

        val error = result.exceptionOrNull()
        assertNotNull(error)
        assertFalse(
            "all-fail must not masquerade as partial",
            error is PartialAttachmentUploadException,
        )
        assertTrue(session.uploadedRemotePaths.isEmpty())
    }

    @Test
    fun disconnectedSessionFailsFast() = runTest {
        val session = FakeStagingSshSession().apply { close() }
        val result = newStager().stage(session, "host-7", listOf(tempImageUri("z.png")))
        assertNull(result.getOrNull())
        assertTrue(result.exceptionOrNull() is SshException)
    }

    @Test
    fun uriBytesAreMaterializedBeforeRemoteDirectoryCommand() = runTest {
        val source = File(cacheDir, "before-ssh.png").apply {
            writeBytes(ByteArray(64) { it.toByte() })
        }
        val session = FakeStagingSshSession(
            onExec = { source.delete() },
        )

        val result = newStager().stage(session, "host-7", listOf(Uri.fromFile(source)))

        assertTrue(result.isSuccess)
        assertEquals(1, session.uploadedRemotePaths.size)
        assertTrue(
            "source URI file is gone before upload; success proves the temp copy was made first",
            !source.exists(),
        )
    }

    /**
     * Minimal [SshSession] fake: records uploads, fails the upload at a
     * configured index (or all when negative), and reports the attachment
     * directory missing so [RemoteAttachmentPruner] is a clean no-op.
     */
    private class FakeStagingSshSession(
        private val failOnUploadIndex: Int = Int.MIN_VALUE,
        private val onExec: () -> Unit = {},
        private val emitProgress: Boolean = true,
    ) : SshSession {
        val uploadedRemotePaths = mutableListOf<String>()
        val progressTicks = mutableListOf<SshUploadProgress>()
        private var uploadCalls = 0
        var closed: Boolean = false

        override val isConnected: Boolean get() = !closed

        override suspend fun exec(command: String): ExecResult {
            onExec()
            return ExecResult(stdout = "", stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            Job().apply { complete() }

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = throw NotImplementedError("not needed")

        override fun startShell(): SshShell = throw NotImplementedError("not needed")

        override suspend fun uploadFile(file: File, remotePath: String): String {
            val index = uploadCalls++
            if (failOnUploadIndex == -2 || index == failOnUploadIndex) {
                throw SshException("induced upload failure at index $index")
            }
            file.readBytes()
            uploadedRemotePaths += remotePath
            return remotePath
        }

        override suspend fun uploadFile(
            file: File,
            remotePath: String,
            onProgress: ((SshUploadProgress) -> Unit)?,
        ): String {
            val total = file.length()
            if (emitProgress) {
                val ticks = listOf(
                    SshUploadProgress(0L, total),
                    SshUploadProgress(total / 2, total),
                    SshUploadProgress(total, total),
                )
                ticks.forEach { tick ->
                    onProgress?.invoke(tick)
                    if (onProgress != null) progressTicks += tick
                }
            }
            return uploadFile(file, remotePath)
        }

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String {
            val index = uploadCalls++
            input.readBytes()
            if (failOnUploadIndex == -2 || index == failOnUploadIndex) {
                throw SshException("induced upload failure at index $index")
            }
            uploadedRemotePaths += remotePath
            return remotePath
        }

        override suspend fun listDirectory(remotePath: String, maxEntries: Int) =
            throw SshFileNotFoundException("$remotePath not found")

        override fun close() {
            closed = true
        }
    }
}
