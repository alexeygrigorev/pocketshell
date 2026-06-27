package com.pocketshell.app.share

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Unit tests for [ShareUploader]'s issue #473 target routing:
 *
 *  - [ShareTarget.HostInbox] keeps the unchanged
 *    `~/inbox/pocketshell/<file>` path and `mkdir` command.
 *  - [ShareTarget.Project] creates `<project>/.inbox/` (incl. `~`
 *    expansion via the exec channel) and writes the file into the
 *    resolved ABSOLUTE `.inbox/` directory.
 *  - The success-path string surfaces the resolved destination.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ShareUploaderTargetTest {

    private lateinit var context: Context
    private val seenLeasePurposes: MutableList<String?> = mutableListOf()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        seenLeasePurposes.clear()
    }

    @Test
    fun hostInboxTargetKeepsExistingPathAndMkdir() = runTest {
        val session = RecordingSession(execStdout = "")
        val uploader = newUploader(session)

        val result = uploader.upload(host(), keyEntity(), textItem("note"), ShareTarget.HostInbox)

        assertTrue(result.isSuccess)
        assertEquals(
            "host inbox mkdir must be unchanged",
            listOf("mkdir -p \"\$HOME/inbox/pocketshell\""),
            session.execCommands,
        )
        // remotePath written under the home-relative inbox dir.
        assertTrue(
            "upload must target inbox/pocketshell, got '${session.lastRemotePath}'",
            session.lastRemotePath!!.startsWith("inbox/pocketshell/"),
        )
        // Success string carries the ~/ display prefix.
        assertTrue(
            "success path must show ~/inbox/pocketshell, got '${result.getOrNull()}'",
            result.getOrNull()!!.startsWith("~/inbox/pocketshell/"),
        )
        assertEquals(listOf("share-upload"), seenLeasePurposes)
    }

    @Test
    fun projectTargetCreatesDotInboxAndWritesIntoResolvedAbsolutePath() = runTest {
        // Exec resolves `cd <path>/.inbox && pwd` -> absolute dir.
        val session = RecordingSession(execStdout = "/home/alexey/git/foo/.inbox\n")
        val uploader = newUploader(session)

        val result = uploader.upload(
            host(),
            keyEntity(),
            textItem("note"),
            ShareTarget.Project("/home/alexey/git/foo"),
        )

        assertTrue(result.isSuccess)
        // One exec: mkdir -p + cd + pwd in a single command.
        assertEquals(1, session.execCommands.size)
        val cmd = session.execCommands.single()
        assertTrue(
            "exec must mkdir the project .inbox, got '$cmd'",
            cmd.contains("mkdir -p \"/home/alexey/git/foo/.inbox\""),
        )
        assertTrue(
            "exec must resolve the absolute .inbox path via cd+pwd, got '$cmd'",
            cmd.contains("cd \"/home/alexey/git/foo/.inbox\"") && cmd.contains("pwd"),
        )
        // The file lands in the RESOLVED absolute .inbox directory.
        assertEquals(
            "/home/alexey/git/foo/.inbox/19700101-000000-note.txt".let { it.substringBeforeLast('/') },
            session.lastRemotePath!!.substringBeforeLast('/'),
        )
        assertTrue(
            "success path must show the project .inbox dir, got '${result.getOrNull()}'",
            result.getOrNull()!!.startsWith("/home/alexey/git/foo/.inbox/"),
        )
    }

    @Test
    fun projectTargetExpandsTildeViaShell() = runTest {
        // Raw path is `~/git/foo`; exec resolves it to an absolute dir.
        val session = RecordingSession(execStdout = "/home/alexey/git/foo/.inbox\n")
        val uploader = newUploader(session)

        val result = uploader.upload(
            host(),
            keyEntity(),
            textItem("shot"),
            ShareTarget.Project("~/git/foo"),
        )

        assertTrue(result.isSuccess)
        val cmd = session.execCommands.single()
        // `~/` rewritten to `$HOME/` so it expands inside double quotes.
        assertTrue(
            "tilde must be rewritten to \$HOME so the shell expands it, got '$cmd'",
            cmd.contains("mkdir -p \"\$HOME/git/foo/.inbox\"") &&
                cmd.contains("cd \"\$HOME/git/foo/.inbox\""),
        )
        // Upload still targets the resolved ABSOLUTE inbox.
        assertTrue(
            session.lastRemotePath!!.startsWith("/home/alexey/git/foo/.inbox/"),
        )
    }

    @Test
    fun projectTargetFailsClearlyWhenMkdirFails() = runTest {
        val session = RecordingSession(execStdout = "", execStderr = "Permission denied", execExit = 1)
        val uploader = newUploader(session)

        val result = uploader.upload(
            host(),
            keyEntity(),
            textItem("note"),
            ShareTarget.Project("/root/locked"),
        )

        assertTrue(result.isFailure)
        assertTrue(
            "failure must mention the project path and the reason, got '${result.exceptionOrNull()?.message}'",
            result.exceptionOrNull()!!.message!!.contains("/root/locked") &&
                result.exceptionOrNull()!!.message!!.contains("Permission denied"),
        )
        // No upload was attempted.
        assertEquals(null, session.lastRemotePath)
    }

    @Test
    fun uriItemIsMaterializedBeforeSshConnect() = runTest {
        val source = File(context.cacheDir, "picked-note.txt").apply {
            writeText("provider bytes")
        }
        val session = RecordingSession(execStdout = "")
        val uploader = ShareUploader(
            context = context,
            connect = { _, _, _, purpose ->
                seenLeasePurposes += purpose
                source.delete()
                Result.success(session)
            },
            now = { 0L },
        )

        val result = uploader.upload(
            host(),
            keyEntity(),
            ShareableItem.UriItem(
                uri = Uri.fromFile(source),
                displayName = "picked-note.txt",
                size = source.length(),
                mimeType = "text/plain",
                fallbackExtension = "txt",
            ),
            ShareTarget.HostInbox,
        )

        assertTrue(result.isSuccess)
        assertTrue(
            "upload must still succeed after the original URI source disappears at connect time",
            session.lastRemotePath!!.endsWith("picked-note.txt"),
        )
    }

    @Test
    fun toShellExpandablePathRewritesTildeAndTrimsTrailingSlash() {
        assertEquals("\$HOME", ShareUploader.toShellExpandablePath("~"))
        assertEquals("\$HOME/git/foo", ShareUploader.toShellExpandablePath("~/git/foo"))
        assertEquals("\$HOME/git/foo", ShareUploader.toShellExpandablePath("~/git/foo/"))
        assertEquals("/abs/path", ShareUploader.toShellExpandablePath("/abs/path/"))
        assertEquals("\$HOME/x", ShareUploader.toShellExpandablePath("\$HOME/x"))
    }

    private fun newUploader(session: SshSession): ShareUploader =
        ShareUploader(
            context = context,
            connect = { _, _, _, purpose ->
                seenLeasePurposes += purpose
                Result.success(session)
            },
            now = { 0L },
        )

    private fun textItem(name: String): ShareableItem.TextItem =
        ShareableItem.TextItem(text = "hello world", displayName = name)

    private fun host(): HostEntity =
        HostEntity(
            id = 1L,
            name = "hetzner",
            hostname = "hetzner.example",
            port = 22,
            username = "alex",
            keyId = 1L,
        )

    private fun keyEntity(): SshKeyEntity {
        val keyFile = File.createTempFile("share-key", ".pem", context.cacheDir)
        keyFile.writeText("dummy")
        keyFile.deleteOnExit()
        return SshKeyEntity(id = 1L, name = "key", privateKeyPath = keyFile.absolutePath)
    }

    /**
     * Minimal [SshSession] fake that records exec commands + the last
     * upload remote path, returns a canned exec result, and accepts
     * stream uploads without a live transport.
     */
    private class RecordingSession(
        private val execStdout: String,
        private val execStderr: String = "",
        private val execExit: Int = 0,
    ) : SshSession {
        val execCommands: MutableList<String> = mutableListOf()
        var lastRemotePath: String? = null
        private var closed = false

        override val isConnected: Boolean get() = !closed

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            return ExecResult(stdout = execStdout, stderr = execStderr, exitCode = execExit)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = error("not used")

        override suspend fun uploadFile(file: File, remotePath: String): String {
            file.readBytes()
            lastRemotePath = remotePath
            return remotePath
        }

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String {
            input.readBytes()
            lastRemotePath = remotePath
            return remotePath
        }

        override fun close() {
            closed = true
        }
    }
}
