package com.pocketshell.next.composer

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.transport.FakeHostConnection
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * The attachment stager against `core-transport`'s in-memory SFTP (rewrite task
 * P-1).
 *
 * The oracle is the FILESYSTEM: every assertion reads the bytes back out of the
 * channel at the path the returned attachment claims. A test that only checked
 * the returned paths could pass with nothing uploaded at all.
 */
@RunWith(AndroidJUnit4::class)
class ComposerAttachmentStagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val resolver =
        ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver

    private val stager = ComposerAttachmentStager(
        resolver = resolver,
        dispatcher = Dispatchers.Unconfined,
        now = { NOW },
    )

    private val connection = FakeHostConnection()
    private val sftp get() = connection.sftpFixture()

    @Test
    fun `a staged file lands at the path the attachment names`() = runTest {
        val result = stage(pick("notes.md", "# hello"))

        val attachment = result.uploaded.single()
        assertNull(result.failure)
        assertEquals("~/.pocketshell/attachments/$SCOPE/${attachment.displayName}", attachment.remotePath)
        assertEquals(
            "# hello",
            sftp.textAt("$HOME/.pocketshell/attachments/$SCOPE/${attachment.displayName}"),
        )
    }

    @Test
    fun `the remote name carries the timestamp, the ordinal and the file name`() = runTest {
        val result = stage(pick("first.txt", "1"), pick("second.txt", "2"))

        val names = result.uploaded.map { it.displayName }
        assertTrue(
            "unexpected names: $names",
            names.all { Regex("""\d{8}-\d{6}-\d{2}-\w+\.txt""").matches(it) },
        )
        assertTrue(names[0].contains("-01-first.txt"))
        assertTrue(names[1].contains("-02-second.txt"))
    }

    @Test
    fun `the attachment directory is created when it does not exist`() = runTest {
        assertNull(sftp.stat("$HOME/.pocketshell"))

        stage(pick("x.txt", "x"))

        assertTrue(requireNotNull(sftp.stat("$HOME/.pocketshell/attachments/$SCOPE")).isDirectory)
    }

    /**
     * `mkdir -p`, not `mkdir`: the channel's mkdir fails when the path exists,
     * so a second attach into the same session must not be turned into an
     * error by its own first attach.
     */
    @Test
    fun `staging twice into the same session works`() = runTest {
        stage(pick("one.txt", "1"))
        val second = stage(pick("two.txt", "2"))

        assertNull(second.failure)
        assertEquals(1, second.uploaded.size)
    }

    /**
     * The shape that lost the maintainer's files in the old client: one bad
     * pick among several must never discard the ones that uploaded.
     */
    @Test
    fun `a failed pick keeps the survivors and reports the count`() = runTest {
        val missing = Uri.fromFile(File(temporaryFolder.root, "does-not-exist.txt"))

        val result = stage(pick("kept.txt", "kept"), missing)

        assertEquals(1, result.uploaded.size)
        assertEquals("kept", sftp.textAt(absolutePathOf(result.uploaded.single())))
        assertNotNull(result.failure)
        assertTrue(result.failure!!.contains("1 of 2"))
    }

    @Test
    fun `a batch where nothing uploads reports a total failure and stages nothing`() = runTest {
        val missing = Uri.fromFile(File(temporaryFolder.root, "nope.txt"))

        val result = stage(missing)

        assertTrue(result.uploaded.isEmpty())
        assertTrue(result.failure.orEmpty().startsWith("Attachment upload failed"))
    }

    @Test
    fun `an empty pick list is a no-op`() = runTest {
        val result = stager.stage(sftp, HOME, SESSION_KEY, emptyList())

        assertTrue(result.uploaded.isEmpty())
        assertNull(result.failure)
    }

    @Test
    fun `progress is reported once per file, one-based`() = runTest {
        val seen = mutableListOf<String>()

        stager.stage(sftp, HOME, SESSION_KEY, listOf(pick("a.txt", "a"), pick("b.txt", "b"))) {
            index, count, name ->
            seen += "$index/$count:${name.substringAfterLast('-')}"
        }

        assertEquals(listOf("1/2:a.txt", "2/2:b.txt"), seen)
    }

    /**
     * A content provider can claim anything as a display name. Only the
     * basename may survive, so a provider cannot talk the app into writing
     * outside the attachment directory.
     */
    @Test
    fun `a display name cannot escape the attachment directory`() {
        assertEquals("passwd", ComposerAttachmentStager.sanitiseFileName("../../etc/passwd"))
        assertEquals("attachment", ComposerAttachmentStager.sanitiseFileName(".."))
        assertEquals("attachment", ComposerAttachmentStager.sanitiseFileName(""))
        assertEquals("attachment", ComposerAttachmentStager.sanitiseFileName(null))
        assertEquals("a_b.txt", ComposerAttachmentStager.sanitiseFileName("a b.txt"))
        // Every shell metacharacter collapses into a single `_` run.
        assertEquals("q_x_.sh", ComposerAttachmentStager.sanitiseFileName("q;\$(x).sh"))
        assertFalse(ComposerAttachmentStager.sanitiseFileName("weird/name").contains('/'))
    }

    @Test
    fun `the scope segment is path-safe and bounded`() {
        assertEquals("7-devbox", ComposerAttachmentStager.safeScopeSegment("7/devbox"))
        assertEquals("7-my-project", ComposerAttachmentStager.safeScopeSegment("7/my project"))
        assertEquals("session", ComposerAttachmentStager.safeScopeSegment("///"))
        assertEquals(80, ComposerAttachmentStager.safeScopeSegment("a".repeat(200)).length)
    }

    // --------------------------------------------------------------- helpers

    private suspend fun stage(vararg picks: Uri): AttachmentStageResult =
        stager.stage(sftp, HOME, SESSION_KEY, picks.toList())

    private fun pick(name: String, contents: String): Uri {
        val file = File(temporaryFolder.root, name)
        file.writeText(contents)
        return Uri.fromFile(file)
    }

    private fun absolutePathOf(attachment: StagedAttachment): String =
        attachment.remotePath.replaceFirst("~", HOME)

    private companion object {
        const val HOME = "/home/testuser"
        const val SESSION_KEY = "7/devbox"
        const val SCOPE = "7-devbox"
        const val NOW = 1_700_000_000_000L
    }
}
