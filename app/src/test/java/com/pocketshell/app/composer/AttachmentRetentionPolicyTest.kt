package com.pocketshell.app.composer

import com.pocketshell.core.ssh.RemoteEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentRetentionPolicyTest {

    @Test
    fun defaultPolicyDeletesExpiredFilesButKeepsRecentFiles() {
        val now = 10_000_000_000L
        val plan = AttachmentRetentionPolicy().plan(
            entries = listOf(
                file("fresh.txt", now - hours(2)),
                file("expired.txt", now - days(8)),
            ),
            nowMillis = now,
        )

        assertEquals(listOf("expired.txt"), plan.delete.map { it.name })
    }

    @Test
    fun newestSafetyWindowWinsOverCap() {
        val now = 10_000_000_000L
        val policy = AttachmentRetentionPolicy(
            ttlMillis = days(7),
            keepNewest = 2,
            protectNewestMillis = days(1),
        )

        val plan = policy.plan(
            entries = listOf(
                file("a.txt", now - hours(1)),
                file("b.txt", now - hours(2)),
                file("c.txt", now - hours(3)),
                file("d.txt", now - hours(4)),
            ),
            nowMillis = now,
        )

        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun capDeletesOnlyFilesOutsideNewestWindow() {
        val now = 10_000_000_000L
        val policy = AttachmentRetentionPolicy(
            ttlMillis = days(7),
            keepNewest = 2,
            protectNewestMillis = days(1),
        )

        val plan = policy.plan(
            entries = listOf(
                file("newest.txt", now - days(2)),
                file("second.txt", now - days(3)),
                file("third.txt", now - days(4)),
                file("fourth.txt", now - days(5)),
            ),
            nowMillis = now,
        )

        assertEquals(listOf("third.txt", "fourth.txt"), plan.delete.map { it.name })
    }

    @Test
    fun entriesWithoutMtimeAndNonFilesAreNeverDeleted() {
        val now = 10_000_000_000L
        val plan = AttachmentRetentionPolicy().plan(
            entries = listOf(
                RemoteEntry(
                    name = "unknown-mtime.txt",
                    type = RemoteEntry.Type.FILE,
                    sizeBytes = 10L,
                    modifiedEpochSec = null,
                ),
                RemoteEntry(
                    name = "directory",
                    type = RemoteEntry.Type.DIRECTORY,
                    sizeBytes = 0L,
                    modifiedEpochSec = (now - days(30)) / 1_000L,
                ),
            ),
            nowMillis = now,
        )

        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun deleteCommandsAreChunkedAndShellQuoted() {
        val commands = RemoteAttachmentPruner.buildDeleteCommands(
            remoteDir = ".pocketshell/attachments/host-1",
            names = listOf("plain.txt", "quote's.txt", "nested/bad.txt", "."),
            dryRun = false,
            batchSize = 1,
        )

        assertEquals(
            listOf(
                "rm -f -- ~/'.pocketshell/attachments/host-1/plain.txt' && printf 'deleted\\t1\\n'",
                "rm -f -- ~/'.pocketshell/attachments/host-1/quote'\\''s.txt' && printf 'deleted\\t1\\n'",
            ),
            commands,
        )
    }

    @Test
    fun dryRunCommandsDoNotRemoveFiles() {
        val commands = RemoteAttachmentPruner.buildDeleteCommands(
            remoteDir = ".pocketshell/attachments/host-1",
            names = listOf("old.txt"),
            dryRun = true,
            batchSize = 50,
        )

        assertEquals(
            listOf("printf 'would-delete\\t%s\\n' '~/.pocketshell/attachments/host-1/old.txt'"),
            commands,
        )
        assertFalse(commands.single().contains("rm -f"))
    }

    @Test
    fun dryRunCommandsShellQuoteFileNames() {
        val commands = RemoteAttachmentPruner.buildDeleteCommands(
            remoteDir = ".pocketshell/attachments/host-1",
            names = listOf("quote's \$file.txt"),
            dryRun = true,
            batchSize = 50,
        )

        assertEquals(
            listOf("printf 'would-delete\\t%s\\n' '~/.pocketshell/attachments/host-1/quote'\\''s \$file.txt'"),
            commands,
        )
    }

    private fun file(name: String, modifiedMillis: Long): RemoteEntry =
        RemoteEntry(
            name = name,
            type = RemoteEntry.Type.FILE,
            sizeBytes = 1L,
            modifiedEpochSec = modifiedMillis / 1_000L,
        )

    private fun hours(value: Long): Long = value * 60L * 60L * 1_000L

    private fun days(value: Long): Long = value * 24L * 60L * 60L * 1_000L
}
